/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import java.util.Arrays;
import java.util.Map;

/**
 * A fully grounded {@link ArithmeticRule}: the linear combination has been instantiated
 * to a specific set of ground atom keys and coefficients.
 *
 * <p>The arithmetic potential is the hinge-loss over the linear constraint:
 * <pre>
 *   ℓ(y) = Σ_i coefficients[i] * y(atomKeys[i]) - rhs
 *   d    = max(0, ℓ(y))           for LEQ
 *   d    = max(0, -ℓ(y))          for GEQ (negated)
 *   d    = max(|ℓ(y)| - 0, 0)     for EQ (both directions; d = max(max(0,ℓ), max(0,-ℓ)))
 *   ϕ    = w * d^p
 * </pre>
 *
 * <p>Unlike a {@link GroundRule} (body→head implication), this record stores the grounded
 * linear combination directly: {@code coefficients[i]} correspond to atoms {@code atomKeys[i]}
 * on the LHS, and {@code rhs} is the constant on the right-hand side (after moving all
 * RHS atom terms to the LHS with negated coefficients).</p>
 *
 * @param weight       rule weight (ignored when {@code hard})
 * @param hard         {@code true} for a hard constraint
 * @param squared      {@code true} for squared hinge, {@code false} for linear hinge
 * @param coefficients per-atom linear coefficients (LHS − RHS terms moved to LHS)
 * @param atomKeys     ground atom keys corresponding to each coefficient
 * @param rhs          right-hand constant (after moving all LHS constant terms)
 * @param op           relational operator
 */
public record ArithmeticGroundRule(double weight, boolean hard, boolean squared,
                                   double[] coefficients, String[] atomKeys,
                                   double rhs, RelOp op) {

    public ArithmeticGroundRule {
        if (Double.isNaN(weight) || weight < 0.0) {
            throw new IllegalArgumentException("Rule weight must be non-negative, got: " + weight);
        }
        if (weight == Double.POSITIVE_INFINITY) {
            hard = true;
            squared = true;
        } else if (hard) {
            weight = Double.POSITIVE_INFINITY;
            squared = true;
        }
    }

    /**
     * Distance to satisfaction for this arithmetic constraint.
     *
     * <p>Compute {@code ℓ = Σ c_i * v_i − rhs}, then:
     * <ul>
     *   <li>{@link RelOp#LEQ}: {@code d = max(0, ℓ)}</li>
     *   <li>{@link RelOp#GEQ}: {@code d = max(0, -ℓ)}</li>
     *   <li>{@link RelOp#EQ}:  {@code d = max(max(0, ℓ), max(0, -ℓ)) = |ℓ|}</li>
     * </ul>
     */
    public double distanceToSatisfaction(Map<String, Double> truth) {
        double ell = -rhs;
        for (int i = 0; i < atomKeys.length; i++) {
            ell += coefficients[i] * truth.getOrDefault(atomKeys[i], 0.0);
        }
        return switch (op) {
            case LEQ -> Math.max(0.0, ell);
            case GEQ -> Math.max(0.0, -ell);
            case EQ -> Math.abs(ell);
        };
    }

    /**
     * Weighted potential contributed to the total MAP energy.
     *
     * @param truth      current atom truth assignment
     * @param hardWeight the penalty weight to use for hard constraints in place of ∞
     */
    public double potential(Map<String, Double> truth, double hardWeight) {
        double d = distanceToSatisfaction(truth);
        if (d <= 0.0) return 0.0;
        double w = hard ? hardWeight : weight;
        return w * (squared ? d * d : d);
    }

    /**
     * Gradient of the potential w.r.t. a given target atom.
     *
     * <p>When {@code d > 0} the hinge is active and the gradient of the linear function
     * is just {@code coefficient[i]} (for LEQ), {@code -coefficient[i]} (for GEQ), or
     * {@code sign(ℓ) * coefficient[i]} (for EQ).</p>
     *
     * @param atomKey   the target atom to differentiate with respect to
     * @param truth     current truth values
     * @param hardWeight penalty weight for hard constraints
     * @return gradient value (0 if the constraint is already satisfied)
     */
    public double gradient(String atomKey, Map<String, Double> truth, double hardWeight) {
        double ell = -rhs;
        int idx = -1;
        for (int i = 0; i < atomKeys.length; i++) {
            ell += coefficients[i] * truth.getOrDefault(atomKeys[i], 0.0);
            if (atomKeys[i].equals(atomKey)) idx = i;
        }
        if (idx < 0) return 0.0;
        double d = switch (op) {
            case LEQ -> Math.max(0.0, ell);
            case GEQ -> Math.max(0.0, -ell);
            case EQ -> Math.abs(ell);
        };
        if (d <= 0.0) return 0.0;
        double w = hard ? hardWeight : weight;
        double outer = squared ? 2.0 * w * d : w;
        double inner = switch (op) {
            case LEQ -> (ell > 0 ? 1.0 : 0.0) * coefficients[idx];
            case GEQ -> (ell < 0 ? 1.0 : 0.0) * (-coefficients[idx]);
            case EQ -> Math.signum(ell) * coefficients[idx];
        };
        return outer * inner;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ArithGroundRule[");
        for (int i = 0; i < atomKeys.length; i++) {
            if (i > 0) sb.append(" + ");
            sb.append(coefficients[i]).append('*').append(atomKeys[i]);
        }
        sb.append(' ').append(op).append(' ').append(rhs).append(']');
        return sb.toString();
    }
}
