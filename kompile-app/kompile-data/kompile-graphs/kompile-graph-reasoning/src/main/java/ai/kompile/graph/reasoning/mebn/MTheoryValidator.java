/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structural validator for {@link MTheory} consistency.
 *
 * <p>Two canonical MEBN consistency rules (Laskey 2008, §3; Costa and Laskey, PR-OWL §4) are
 * checked:</p>
 * <ol>
 *   <li><b>Unique home MFrag per resident-RV signature</b> — no two MFrags may define the same
 *       resident RV, identified by its name plus argument-type list
 *       (e.g. {@code "isRelevant(AllNodes)"}). The {@link MTheory#addMFrag} insertion guard
 *       enforces this at build time; this validator independently scans for violations so callers
 *       can detect inconsistencies in pre-built theories loaded from serialised form.</li>
 *   <li><b>No MFrag-template dependency cycle</b> — if MFrag A has an INPUT RV whose home
 *       (resident) fragment is MFrag B, then A <em>depends on</em> B. A cycle
 *       (A → B → … → A) would prevent consistent joint-distribution construction and is
 *       therefore an error.</li>
 * </ol>
 *
 * <p>Usage:</p>
 * <pre>
 * MTheoryValidator.ValidationResult result = MTheoryValidator.validate(theory);
 * if (!result.isValid()) {
 *     log.warn("MTheory invalid: {}", result.violations());
 * }
 * // or throw on violation:
 * MTheoryValidator.validateOrThrow(theory);
 * </pre>
 */
public final class MTheoryValidator {

    private MTheoryValidator() {}

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API types
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A single structural violation found in an {@link MTheory}.
     *
     * @param kind    short machine-readable category ({@code "DUPLICATE_HOME"} or
     *                {@code "DEPENDENCY_CYCLE"})
     * @param message human-readable description of the specific violation
     */
    public record Violation(String kind, String message) {
        @Override
        public String toString() {
            return "[" + kind + "] " + message;
        }
    }

    /**
     * The result of validating an {@link MTheory}. Immutable.
     *
     * @param violations all violations found; empty iff {@link #isValid()} is {@code true}
     */
    public record ValidationResult(List<Violation> violations) {
        /** {@code true} iff the theory has no violations. */
        public boolean isValid() {
            return violations.isEmpty();
        }

        @Override
        public String toString() {
            return isValid()
                    ? "ValidationResult[VALID]"
                    : "ValidationResult[INVALID, " + violations.size() + " violation(s): "
                            + violations + "]";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Entry points
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Validate {@code theory} and return a {@link ValidationResult}.
     *
     * <p>Read-only — does not mutate the theory.</p>
     *
     * @param theory the MTheory to validate; must not be {@code null}
     * @return a result containing zero or more violations
     */
    public static ValidationResult validate(MTheory theory) {
        List<Violation> violations = new ArrayList<>();
        checkUniqueHomeMFrags(theory, violations);
        checkNoDependencyCycles(theory, violations);
        return new ValidationResult(List.copyOf(violations));
    }

    /**
     * Validate and throw if the theory has any violations.
     *
     * @param theory the MTheory to validate
     * @throws IllegalStateException if any violations are found; the message includes all
     *                               violation strings joined by {@code "; "}
     */
    public static void validateOrThrow(MTheory theory) {
        ValidationResult result = validate(theory);
        if (!result.isValid()) {
            String msg = result.violations().stream()
                    .map(Violation::toString)
                    .collect(Collectors.joining("; ",
                            "MTheory '" + theory.getName() + "' is invalid: ", ""));
            throw new IllegalStateException(msg);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rule 1 — unique home MFrag per resident-RV signature
    // ─────────────────────────────────────────────────────────────────────────────

    private static void checkUniqueHomeMFrags(MTheory theory, List<Violation> out) {
        // signature → name of the first MFrag that defined it
        Map<String, String> firstSeen = new HashMap<>();
        for (MFrag frag : theory.getMFrags()) {
            for (RandomVariable rv : frag.getResidentNodes()) {
                String sig = rvSignature(rv);
                String prev = firstSeen.put(sig, frag.getName());
                if (prev != null) {
                    out.add(new Violation("DUPLICATE_HOME",
                            "Resident RV '" + sig + "' is defined in both MFrag '"
                                    + prev + "' and MFrag '" + frag.getName() + "'"));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rule 2 — no MFrag-template dependency cycle
    // ─────────────────────────────────────────────────────────────────────────────

    private static void checkNoDependencyCycles(MTheory theory, List<Violation> out) {
        // Build dependency adjacency: fragName → set of depended-on fragNames.
        // A depends on B when A has an INPUT RV whose home (resident) MFrag is B.
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (MFrag frag : theory.getMFrags()) {
            Set<String> deps = new LinkedHashSet<>();
            for (RandomVariable input : frag.getInputNodes()) {
                Optional<MFrag> home = theory.findHomeMFrag(input.getName());
                home.map(MFrag::getName)
                        .filter(hn -> !hn.equals(frag.getName()))
                        .ifPresent(deps::add);
            }
            adj.put(frag.getName(), deps);
        }

        // Iterative DFS cycle detection — avoids stack overflow on large theories.
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String start : adj.keySet()) {
            if (!visited.contains(start)) {
                detectCycle(start, adj, visited, inStack, out);
            }
        }
    }

    /**
     * DFS from {@code start}; appends a {@code DEPENDENCY_CYCLE} violation to {@code out} for the
     * first cycle detected from this starting node.
     *
     * <p>Uses an explicit stack of {@code (node, child-iterator, path)} frames to avoid deep Java
     * call-stack recursion.</p>
     */
    @SuppressWarnings("unchecked")
    private static void detectCycle(String start,
                                     Map<String, Set<String>> adj,
                                     Set<String> visited,
                                     Set<String> inStack,
                                     List<Violation> out) {
        // Each frame: [node (String), child-iterator (Iterator<String>), path (List<String>)]
        Deque<Object[]> stack = new ArrayDeque<>();
        List<String> startPath = new ArrayList<>();
        startPath.add(start);
        stack.push(new Object[]{start,
                adj.getOrDefault(start, Set.of()).iterator(),
                startPath});
        visited.add(start);
        inStack.add(start);

        while (!stack.isEmpty()) {
            Object[] frame = stack.peek();
            String node = (String) frame[0];
            Iterator<String> iter = (Iterator<String>) frame[1];
            List<String> path = (List<String>) frame[2];

            if (iter.hasNext()) {
                String neighbor = iter.next();
                if (inStack.contains(neighbor)) {
                    // Cycle detected: collect the path from the entry point of the cycle.
                    int idx = path.indexOf(neighbor);
                    List<String> cycle = new ArrayList<>(
                            idx >= 0 ? path.subList(idx, path.size()) : path);
                    cycle.add(neighbor); // close the loop
                    out.add(new Violation("DEPENDENCY_CYCLE",
                            "MFrag dependency cycle detected: "
                                    + String.join(" -> ", cycle)));
                    return; // report the first cycle found and stop
                }
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    inStack.add(neighbor);
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(neighbor);
                    stack.push(new Object[]{neighbor,
                            adj.getOrDefault(neighbor, Set.of()).iterator(),
                            newPath});
                }
            } else {
                // All children of this node processed — pop and unmark from in-stack.
                inStack.remove(node);
                stack.pop();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Build the composite signature key for a resident RV: {@code "name"} for propositional,
     * {@code "name(Type1,Type2,...)"} for parameterised. Must match
     * {@link MTheory}'s own {@code rvSignatureKey} (kept in sync).
     */
    private static String rvSignature(RandomVariable rv) {
        if (rv.getArgumentTypes().isEmpty()) {
            return rv.getName();
        }
        String argTypes = rv.getArgumentTypes().stream()
                .map(EntityType::getTypeName)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return rv.getName() + "(" + argTypes + ")";
    }
}
