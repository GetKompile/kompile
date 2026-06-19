/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A PSL program: a set of weighted {@link PslRule}s plus the ground atoms they reason
 * over, partitioned into <b>observed</b> atoms (evidence, fixed during inference) and
 * <b>target</b> atoms (the soft-truth random variables to infer).
 *
 * <p>This is the hand-rolled, dependency-free analogue of PSL's {@code DataStore} +
 * {@code Model}: ground atoms are declared up front (observed evidence and inference
 * targets), then {@link #ground()} instantiates every rule against them via a
 * backtracking conjunctive join, producing the {@link GroundRule}s that
 * {@link HlMrfMapInference} optimizes.</p>
 */
public class PslProgram {

    /** Safety cap on the number of ground rules to avoid pathological grounding blow-ups. */
    public static final int MAX_GROUND_RULES = 500_000;

    private final List<PslRule> rules = new ArrayList<>();
    private final Map<String, PslAtom> atomsByKey = new LinkedHashMap<>();
    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Set<String> observed = new LinkedHashSet<>();

    // ─── Rules ───────────────────────────────────────────────────────────────

    public PslProgram addRule(PslRule rule) {
        rules.add(rule);
        return this;
    }

    public PslProgram addRule(String ruleText) {
        return addRule(PslRule.parse(ruleText));
    }

    public List<PslRule> rules() {
        return rules;
    }

    // ─── Atoms ───────────────────────────────────────────────────────────────

    private String register(PslAtom groundAtom) {
        if (!groundAtom.isGround()) {
            throw new IllegalArgumentException("Atom is not ground: " + groundAtom);
        }
        String key = groundAtom.key();
        atomsByKey.putIfAbsent(key, groundAtom);
        values.putIfAbsent(key, 0.0);
        return key;
    }

    /** Declare (or update) an observed atom fixed at {@code value} during inference. */
    public PslProgram observe(PslAtom groundAtom, double value) {
        String key = register(groundAtom);
        values.put(key, clamp01(value));
        observed.add(key);
        return this;
    }

    public PslProgram observe(String predicate, double value, String... args) {
        return observe(PslAtom.ground(predicate, args), value);
    }

    /** Declare a target atom whose soft truth is to be inferred. */
    public PslProgram target(PslAtom groundAtom) {
        register(groundAtom);
        return this;
    }

    public PslProgram target(String predicate, String... args) {
        return target(PslAtom.ground(predicate, args));
    }

    public boolean contains(String atomKey) {
        return atomsByKey.containsKey(atomKey);
    }

    public boolean isObserved(String atomKey) {
        return observed.contains(atomKey);
    }

    public double value(String atomKey) {
        return values.getOrDefault(atomKey, 0.0);
    }

    public Set<String> atomKeys() {
        return atomsByKey.keySet();
    }

    public Set<String> observedKeys() {
        return observed;
    }

    /** Keys of all target (non-observed) atoms. */
    public List<String> targetKeys() {
        List<String> targets = new ArrayList<>();
        for (String key : atomsByKey.keySet()) {
            if (!observed.contains(key)) targets.add(key);
        }
        return targets;
    }

    /** A mutable snapshot of the current truth assignment (observed + targets). */
    public Map<String, Double> valueSnapshot() {
        return new LinkedHashMap<>(values);
    }

    public int atomCount() {
        return atomsByKey.size();
    }

    // ─── Grounding ───────────────────────────────────────────────────────────

    /**
     * Instantiate every rule against the declared ground atoms via a backtracking join.
     * A rule grounds only when every one of its literals matches a declared ground atom
     * with a consistent variable binding (and all {@code A != B} guards hold).
     */
    public List<GroundRule> ground() {
        Map<String, List<PslAtom>> byPredicate = new LinkedHashMap<>();
        for (PslAtom atom : atomsByKey.values()) {
            byPredicate.computeIfAbsent(atom.predicate(), k -> new ArrayList<>()).add(atom);
        }

        List<GroundRule> out = new ArrayList<>();
        for (PslRule rule : rules) {
            groundInto(rule, rule.allAtoms(), byPredicate, 0, new LinkedHashMap<>(), out);
            if (out.size() >= MAX_GROUND_RULES) break;
        }
        return out;
    }

    private void groundInto(PslRule rule, List<PslAtom> atoms, Map<String, List<PslAtom>> byPredicate,
                            int index, Map<String, String> binding, List<GroundRule> out) {
        if (out.size() >= MAX_GROUND_RULES) return;
        if (index == atoms.size()) {
            if (satisfiesDistinct(rule, binding)) {
                out.add(instantiate(rule, binding));
            }
            return;
        }
        PslAtom template = atoms.get(index);
        List<PslAtom> candidates = byPredicate.get(template.predicate());
        if (candidates == null) return; // no atoms for this predicate ⇒ rule cannot ground
        for (PslAtom candidate : candidates) {
            Map<String, String> extended = unify(template, candidate, binding);
            if (extended != null) {
                groundInto(rule, atoms, byPredicate, index + 1, extended, out);
            }
        }
    }

    /** Try to match a (possibly non-ground) template against a ground candidate atom. */
    private Map<String, String> unify(PslAtom template, PslAtom candidate, Map<String, String> binding) {
        if (template.args().size() != candidate.args().size()) return null;
        Map<String, String> extended = null;
        for (int i = 0; i < template.args().size(); i++) {
            Term t = template.args().get(i);
            String c = candidate.args().get(i).name();
            if (t.variable()) {
                String bound = binding.get(t.name());
                if (bound != null) {
                    if (!bound.equals(c)) return null;
                } else {
                    if (extended == null) extended = new LinkedHashMap<>(binding);
                    extended.put(t.name(), c);
                    binding = extended; // so later positions in the same atom see the binding
                }
            } else if (!t.name().equals(c)) {
                return null;
            }
        }
        return extended != null ? extended : new LinkedHashMap<>(binding);
    }

    private boolean satisfiesDistinct(PslRule rule, Map<String, String> binding) {
        for (String[] pair : rule.distinct()) {
            String a = binding.getOrDefault(pair[0], pair[0]);
            String b = binding.getOrDefault(pair[1], pair[1]);
            if (a.equals(b)) return false;
        }
        return true;
    }

    private GroundRule instantiate(PslRule rule, Map<String, String> binding) {
        List<GroundRule.Lit> body = new ArrayList<>(rule.body().size());
        List<GroundRule.Lit> head = new ArrayList<>(rule.head().size());
        for (PslAtom atom : rule.body()) {
            body.add(new GroundRule.Lit(atom.ground(binding).key(), atom.negated()));
        }
        for (PslAtom atom : rule.head()) {
            head.add(new GroundRule.Lit(atom.ground(binding).key(), atom.negated()));
        }
        return new GroundRule(rule.weight(), rule.hard(), rule.squared(), body, head,
                renderGround(rule, binding));
    }

    private String renderGround(PslRule rule, Map<String, String> binding) {
        StringBuilder sb = new StringBuilder();
        if (!rule.hard()) sb.append(trim(rule.weight())).append(": ");
        sb.append(renderLiterals(rule.body(), binding, " & "));
        if (!rule.body().isEmpty()) sb.append(" -> ");
        sb.append(renderLiterals(rule.head(), binding, " | "));
        if (rule.hard()) sb.append(" .");
        return sb.toString();
    }

    private String renderLiterals(List<PslAtom> atoms, Map<String, String> binding, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < atoms.size(); i++) {
            if (i > 0) sb.append(sep);
            PslAtom atom = atoms.get(i);
            sb.append(atom.negated() ? "~" : "").append(atom.ground(binding).key());
        }
        return sb.toString();
    }

    private static String trim(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) return Long.toString((long) w);
        return Double.toString(w);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
