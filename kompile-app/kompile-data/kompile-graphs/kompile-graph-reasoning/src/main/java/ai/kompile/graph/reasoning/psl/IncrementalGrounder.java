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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Incremental / delta grounding for a {@link PslProgram}.
 *
 * <p>Full re-grounding on every KB update is O(|entities|^k) for k-ary rules. When only
 * a small delta of atoms changes, we can do much better: ground only those rules that
 * mention a predicate affected by the delta, then add/remove the resulting ground rules
 * from the maintained set.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>An {@code IncrementalGrounder} wraps a {@link PslProgram} and maintains a
 *       live {@code List<GroundRule>} that always reflects the current ground closure.</li>
 *   <li>When {@link #addAtom} or {@link #removeAtom} is called, only rules whose template
 *       bodies/heads mention the changed predicate are re-grounded. All other ground rules
 *       stay unchanged.</li>
 *   <li>After each delta the maintained list is deduplicated (same {@link GroundRule#display()}
 *       is treated as the same instantiation).</li>
 *   <li>The invariant: {@code groundRules()} equals what you would get from a full
 *       {@link PslProgram#ground()} on the same program state (ignoring ordering), as long as
 *       atoms are only added/removed through this grounder and not directly on the program.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Callers must synchronize externally if needed.
 */
public class IncrementalGrounder {

    /** The program whose atoms and rules are managed. */
    private final PslProgram program;

    /** Maintained set of all current ground rules (deduplicated by display string). */
    private final List<GroundRule> groundRules = new ArrayList<>();

    /** Display strings of ground rules currently in the maintained set (for deduplication). */
    private final Set<String> groundRuleDisplays = new LinkedHashSet<>();

    /**
     * Index: predicate name → the rules that mention it (in body or head).
     * Used to scope re-grounding to only affected rules.
     */
    private final Map<String, List<PslRule>> predicateToRules = new LinkedHashMap<>();

    /**
     * Create an incremental grounder for {@code program}, performing an initial full
     * grounding to populate the maintained ground-rule set.
     *
     * @param program the PSL program to manage; must not be null
     */
    public IncrementalGrounder(PslProgram program) {
        if (program == null) throw new IllegalArgumentException("program must not be null");
        this.program = program;
        // Build predicate → rules index.
        for (PslRule rule : program.rules()) {
            for (String pred : predicatesOf(rule)) {
                predicateToRules.computeIfAbsent(pred, k -> new ArrayList<>()).add(rule);
            }
        }
        // Initial full grounding.
        for (GroundRule gr : program.ground()) {
            if (groundRuleDisplays.add(gr.display())) {
                groundRules.add(gr);
            }
        }
    }

    // ─── Atom delta API ──────────────────────────────────────────────────────────

    /**
     * Declare a new observed atom and update the ground rules incrementally.
     *
     * <p>This is the equivalent of {@link PslProgram#observe(PslAtom, double)} followed by
     * a targeted re-ground of all rules that mention the atom's predicate.</p>
     *
     * @param atom  the ground atom to add
     * @param value the observed truth value in [0, 1]
     */
    public void addAtom(PslAtom atom, double value) {
        if (!atom.isGround()) throw new IllegalArgumentException("Atom must be ground: " + atom);
        program.observe(atom, value);
        regroundForPredicate(atom.predicate());
    }

    /**
     * Declare a new target atom and update the ground rules incrementally.
     *
     * @param atom the ground target atom to add
     */
    public void addTargetAtom(PslAtom atom) {
        if (!atom.isGround()) throw new IllegalArgumentException("Atom must be ground: " + atom);
        program.target(atom);
        regroundForPredicate(atom.predicate());
    }

    /**
     * Remove an atom from the maintained ground-rule set.
     *
     * <p>All ground rules that reference the removed atom (in body or head) are dropped
     * from the maintained set. The atom itself is not removed from the underlying
     * {@link PslProgram} (the program API does not expose removal), but its ground rules
     * are pruned so they no longer participate in inference.</p>
     *
     * @param atomKey the canonical key of the atom to remove (e.g. {@code "State(alice)"})
     */
    public void removeAtom(String atomKey) {
        // Remove all ground rules that reference this atom.
        List<GroundRule> removed = new ArrayList<>();
        for (GroundRule gr : groundRules) {
            if (referencesAtom(gr, atomKey)) {
                removed.add(gr);
            }
        }
        for (GroundRule gr : removed) {
            groundRules.remove(gr);
            groundRuleDisplays.remove(gr.display());
        }
    }

    /**
     * Add a new rule to the program and incrementally ground it against all current atoms.
     *
     * @param rule the PSL rule to add
     */
    public void addRule(PslRule rule) {
        program.addRule(rule);
        for (String pred : predicatesOf(rule)) {
            predicateToRules.computeIfAbsent(pred, k -> new ArrayList<>()).add(rule);
        }
        // Ground the new rule against all current atoms via a temporary mini-program.
        List<GroundRule> newGround = groundSingleRule(rule);
        for (GroundRule gr : newGround) {
            if (groundRuleDisplays.add(gr.display())) {
                groundRules.add(gr);
            }
        }
    }

    // ─── Accessors ───────────────────────────────────────────────────────────────

    /**
     * Return the current maintained ground rules (unmodifiable view).
     *
     * <p>This list is always consistent with the current state of the program: it equals
     * what a full {@link PslProgram#ground()} would produce on the same atom set (modulo
     * ordering and any atoms removed via {@link #removeAtom}).</p>
     *
     * @return unmodifiable view of the maintained ground rules
     */
    public List<GroundRule> groundRules() {
        return Collections.unmodifiableList(groundRules);
    }

    /**
     * Return the underlying PSL program (for running inference).
     *
     * @return the wrapped {@link PslProgram}
     */
    public PslProgram program() {
        return program;
    }

    /**
     * Return the number of maintained ground rules.
     *
     * @return ground rule count
     */
    public int groundRuleCount() {
        return groundRules.size();
    }

    // ─── Internal helpers ────────────────────────────────────────────────────────

    /**
     * Re-ground all rules that mention {@code predicate} and add any new ground rules
     * to the maintained set.
     */
    private void regroundForPredicate(String predicate) {
        List<PslRule> affectedRules = predicateToRules.getOrDefault(predicate, List.of());
        for (PslRule rule : affectedRules) {
            for (GroundRule gr : groundSingleRule(rule)) {
                if (groundRuleDisplays.add(gr.display())) {
                    groundRules.add(gr);
                }
            }
        }
    }

    /**
     * Ground a single rule against all atoms currently in the program.
     * Uses the same backtracking join as {@link PslProgram#ground()} but restricted to one rule.
     */
    private List<GroundRule> groundSingleRule(PslRule rule) {
        // Build a temporary single-rule program sharing the same atoms by re-using the
        // grounding engine indirectly: create a fresh PslProgram that has the same atoms
        // but only this rule, and call ground() on it.
        PslProgram mini = new PslProgram();
        mini.addRule(rule);
        // Copy all atoms from the main program to mini.
        for (String key : program.atomKeys()) {
            PslAtom atom = parseAtomKey(key);
            if (atom == null) continue;
            if (program.isObserved(key)) {
                mini.observe(atom, program.value(key));
            } else {
                mini.target(atom);
            }
        }
        return mini.ground();
    }

    /**
     * Parse a canonical atom key (e.g. {@code "State(alice)"}) back into a {@link PslAtom}.
     * Returns null if the key cannot be parsed (defensive — should not happen in practice).
     */
    private static PslAtom parseAtomKey(String key) {
        try {
            return PslAtom.parse(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Collect all predicate names mentioned in a rule (body + head).
     */
    private static Set<String> predicatesOf(PslRule rule) {
        Set<String> preds = new LinkedHashSet<>();
        for (PslAtom a : rule.body()) preds.add(a.predicate());
        for (PslAtom a : rule.head()) preds.add(a.predicate());
        return preds;
    }

    /**
     * True if the given ground rule references {@code atomKey} in any literal.
     */
    private static boolean referencesAtom(GroundRule gr, String atomKey) {
        for (GroundRule.Lit l : gr.body()) {
            if (l.atomKey().equals(atomKey)) return true;
        }
        for (GroundRule.Lit l : gr.head()) {
            if (l.atomKey().equals(atomKey)) return true;
        }
        return false;
    }
}
