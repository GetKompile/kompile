/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.bayesian;

import java.util.*;

/**
 * A factor in a Bayesian network: a function mapping variable assignments to
 * non-negative real values. Used as the core data structure for variable
 * elimination inference.
 *
 * <p>Each factor has an ordered list of variables and a flat value array indexed
 * by the cross-product of variable states. For binary variables (TRUE/FALSE),
 * a factor over [A, B] has 4 entries: [A=0,B=0], [A=0,B=1], [A=1,B=0], [A=1,B=1].</p>
 *
 * <p>Supports three operations:</p>
 * <ul>
 *   <li><b>Product</b>: combine two factors by multiplying matching entries</li>
 *   <li><b>Marginalization</b>: sum out a variable to reduce dimensionality</li>
 *   <li><b>Normalization</b>: scale values to sum to 1.0</li>
 * </ul>
 */
public class Factor {

    private static final String MAX_FACTOR_BYTES_PROPERTY = "kompile.bayesian.maxFactorBytes";
    private static final long DEFAULT_HEAP_FRACTION = 16L;
    private static final long FALLBACK_FACTOR_BUDGET_BYTES = 4L * 1024L * 1024L;

    private final List<String> variables;
    private final int[] cardinalities;
    private final double[] values;

    /**
     * Create a factor over the given variables with specified cardinalities.
     *
     * @param variables     ordered variable names
     * @param cardinalities number of states per variable (same order as variables)
     * @param values        flat array of size product(cardinalities), row-major order
     */
    public Factor(List<String> variables, int[] cardinalities, double[] values) {
        Objects.requireNonNull(variables, "variables");
        Objects.requireNonNull(cardinalities, "cardinalities");
        Objects.requireNonNull(values, "values");
        if (variables.size() != cardinalities.length) {
            throw new IllegalArgumentException("Variable count " + variables.size()
                    + " != cardinality count " + cardinalities.length);
        }
        if (new HashSet<>(variables).size() != variables.size()) {
            throw new IllegalArgumentException("Factor variables must be unique: " + variables);
        }
        this.variables = List.copyOf(variables);
        this.cardinalities = cardinalities.clone();

        int expectedSize = checkedSize(cardinalities, "construction");
        if (values.length != expectedSize) {
            throw new IllegalArgumentException(
                    "Values array size " + values.length + " != expected " + expectedSize);
        }
        this.values = values.clone();
    }

    /**
     * Create a factor over binary variables (cardinality 2 each).
     */
    public static Factor binary(List<String> variables, double[] values) {
        int[] cards = new int[variables.size()];
        Arrays.fill(cards, 2);
        return new Factor(variables, cards, values);
    }

    public List<String> getVariables() {
        return variables;
    }

    public int[] getCardinalities() {
        return cardinalities.clone();
    }

    public double[] getValues() {
        return values.clone();
    }

    public int size() {
        return values.length;
    }

    /**
     * Multiply two factors, producing a new factor over the union of their variables.
     *
     * <p>For each assignment to the union of variables, the result value is the
     * product of the two input factor values for the corresponding sub-assignments.</p>
     */
    public static Factor product(Factor f1, Factor f2) {
        // Build the union of variables preserving order
        List<String> unionVars = new ArrayList<>(f1.variables);
        List<Integer> unionCards = new ArrayList<>();
        for (int c : f1.cardinalities) unionCards.add(c);

        for (int i = 0; i < f2.variables.size(); i++) {
            int existingIndex = unionVars.indexOf(f2.variables.get(i));
            if (existingIndex < 0) {
                unionVars.add(f2.variables.get(i));
                unionCards.add(f2.cardinalities[i]);
            } else if (unionCards.get(existingIndex) != f2.cardinalities[i]) {
                throw new IllegalArgumentException("Mismatched cardinality for variable '"
                        + f2.variables.get(i) + "': " + unionCards.get(existingIndex)
                        + " vs " + f2.cardinalities[i]);
            }
        }

        int[] resultCards = unionCards.stream().mapToInt(Integer::intValue).toArray();
        int resultSize = checkedSize(resultCards, "product");

        double[] resultValues = new double[resultSize];
        int[] assignment = new int[unionVars.size()];

        for (int idx = 0; idx < resultSize; idx++) {
            indexToAssignment(idx, resultCards, assignment);

            int f1Idx = projectAssignment(assignment, unionVars, f1.variables, f1.cardinalities);
            int f2Idx = projectAssignment(assignment, unionVars, f2.variables, f2.cardinalities);

            resultValues[idx] = f1.values[f1Idx] * f2.values[f2Idx];
        }

        return new Factor(unionVars, resultCards, resultValues);
    }

    /**
     * Sum out (marginalize) a variable from this factor.
     *
     * @param variable the variable to eliminate
     * @return a new factor with the variable removed
     */
    public Factor marginalize(String variable) {
        int varIdx = variables.indexOf(variable);
        if (varIdx < 0) {
            return this; // Variable not in this factor
        }

        List<String> newVars = new ArrayList<>(variables);
        newVars.remove(varIdx);

        int[] newCards = new int[newVars.size()];
        for (int i = 0, j = 0; i < cardinalities.length; i++) {
            if (i != varIdx) newCards[j++] = cardinalities[i];
        }

        int newSize = checkedSize(newCards, "marginalization");

        double[] newValues = new double[newSize];
        int[] assignment = new int[variables.size()];

        for (int idx = 0; idx < values.length; idx++) {
            indexToAssignment(idx, cardinalities, assignment);

            // Compute the index in the marginalized factor (skip the eliminated variable)
            int newIdx = 0;
            int stride = 1;
            for (int i = newCards.length - 1; i >= 0; i--) {
                int origI = i >= varIdx ? i + 1 : i;
                newIdx += assignment[origI] * stride;
                stride *= newCards[i];
            }

            newValues[newIdx] += values[idx];
        }

        if (newVars.isEmpty()) {
            return new Factor(List.of(), new int[0], new double[]{newValues[0]});
        }
        return new Factor(newVars, newCards, newValues);
    }

    /**
     * Normalize this factor so values sum to 1.0.
     *
     * @return a new normalized factor
     */
    public Factor normalize() {
        double sum = 0;
        for (double v : values) sum += v;
        if (sum == 0) return this;

        double[] normalized = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            normalized[i] = values[i] / sum;
        }
        return new Factor(variables, cardinalities, normalized);
    }

    /**
     * Reduce this factor by setting a variable to a specific value (evidence).
     *
     * @param variable the observed variable
     * @param value    the observed state index
     * @return a new factor with the variable fixed
     */
    public Factor reduce(String variable, int value) {
        int varIdx = variables.indexOf(variable);
        if (varIdx < 0) return this;

        List<String> newVars = new ArrayList<>(variables);
        newVars.remove(varIdx);

        int[] newCards = new int[newVars.size()];
        for (int i = 0, j = 0; i < cardinalities.length; i++) {
            if (i != varIdx) newCards[j++] = cardinalities[i];
        }

        int newSize = checkedSize(newCards, "evidence reduction");

        double[] newValues = new double[newSize];
        int[] assignment = new int[variables.size()];

        for (int idx = 0; idx < values.length; idx++) {
            indexToAssignment(idx, cardinalities, assignment);
            if (assignment[varIdx] != value) continue;

            int newIdx = 0;
            int stride = 1;
            for (int i = newCards.length - 1; i >= 0; i--) {
                int origI = i >= varIdx ? i + 1 : i;
                newIdx += assignment[origI] * stride;
                stride *= newCards[i];
            }
            newValues[newIdx] = values[idx];
        }

        if (newVars.isEmpty()) {
            return new Factor(List.of(), new int[0], new double[]{newValues[0]});
        }
        return new Factor(newVars, newCards, newValues);
    }

    /**
     * Get the value for a specific variable state from a single-variable factor.
     */
    public double getValue(int stateIndex) {
        if (stateIndex < 0 || stateIndex >= values.length) {
            throw new IndexOutOfBoundsException("State index " + stateIndex + " out of range [0, " + values.length + ")");
        }
        return values[stateIndex];
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INDEX HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert a flat index to a multi-dimensional assignment (row-major order).
     */
    static void indexToAssignment(int index, int[] cardinalities, int[] assignment) {
        for (int i = cardinalities.length - 1; i >= 0; i--) {
            assignment[i] = index % cardinalities[i];
            index /= cardinalities[i];
        }
    }

    /**
     * Validate and bound a dense factor allocation before any array is created.
     *
     * <p>Exact variable elimination is exponential in the largest intermediate scope.  A
     * checked, heap-relative work budget turns that unavoidable limitation into a deterministic
     * error instead of an integer wraparound or JVM-wide {@link OutOfMemoryError}.  Tests and
     * deployments may raise or lower the budget explicitly with
     * {@code -Dkompile.bayesian.maxFactorBytes=...}; the default reserves most of the heap for
     * the network and caller rather than allowing one factor to consume it.</p>
     */
    static int checkedSize(int[] cardinalities, String operation) {
        long cells = 1L;
        for (int cardinality : cardinalities) {
            if (cardinality <= 0) {
                throw new IllegalArgumentException("Factor cardinalities must be positive for "
                        + operation + ": " + cardinality);
            }
            if (cells > Integer.MAX_VALUE / (long) cardinality) {
                throw new IllegalArgumentException("Bayesian factor " + operation
                        + " requires " + cells + " x " + cardinality
                        + " cells and exceeds the Java array limit; exact elimination would be exponential");
            }
            cells *= cardinality;
        }

        long requestedBytes = cells * Double.BYTES;
        long budgetBytes = factorBudgetBytes();
        if (requestedBytes > budgetBytes) {
            throw new IllegalArgumentException("Bayesian factor " + operation + " requires "
                    + cells + " cells (" + requestedBytes + " bytes), exceeding the exact-elimination "
                    + "work budget of " + budgetBytes + " bytes; reduce the graph scope or configure "
                    + MAX_FACTOR_BYTES_PROPERTY);
        }
        return (int) cells;
    }

    private static long factorBudgetBytes() {
        String configured = System.getProperty(MAX_FACTOR_BYTES_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                long value = Long.parseLong(configured.trim());
                if (value < Double.BYTES) {
                    throw new IllegalArgumentException(MAX_FACTOR_BYTES_PROPERTY + " must be >= "
                            + Double.BYTES + " bytes");
                }
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(MAX_FACTOR_BYTES_PROPERTY
                        + " must be a positive byte count", e);
            }
        }

        long heap = Runtime.getRuntime().maxMemory();
        if (heap <= 0L || heap == Long.MAX_VALUE) {
            return FALLBACK_FACTOR_BUDGET_BYTES;
        }
        return Math.max(Double.BYTES, heap / DEFAULT_HEAP_FRACTION);
    }

    /**
     * Project an assignment from a superset of variables to a subset,
     * and compute the flat index in the subset's factor.
     */
    private static int projectAssignment(int[] fullAssignment, List<String> fullVars,
                                          List<String> subsetVars, int[] subsetCards) {
        int idx = 0;
        int stride = 1;
        for (int i = subsetVars.size() - 1; i >= 0; i--) {
            int fullIdx = fullVars.indexOf(subsetVars.get(i));
            idx += fullAssignment[fullIdx] * stride;
            stride *= subsetCards[i];
        }
        return idx;
    }

    @Override
    public String toString() {
        return "Factor{vars=" + variables + ", values=" + Arrays.toString(values) + "}";
    }
}
