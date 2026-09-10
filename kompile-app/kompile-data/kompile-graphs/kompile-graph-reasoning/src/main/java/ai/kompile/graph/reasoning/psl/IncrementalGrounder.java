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
import java.util.Arrays;
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
 *   <li>Atom deltas are applied to the wrapped program first, then all affected templates are
 *       invalidated and re-grounded once per batch. All other ground rules stay unchanged.</li>
 *   <li>Ground rules are keyed by template index plus display, so distinct templates that happen
 *       to render identically (including equal weights) are not collapsed.</li>
 *   <li>The invariant: {@code groundRules()} equals what you would get from a full
 *       {@link PslProgram#ground()} on the same program state (ignoring ordering), as long as
 *       atoms are only added/removed through this grounder and not directly on the program.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Callers must synchronize externally if needed.
 */
public class IncrementalGrounder {

    /** Operation represented by one entry in a batched atom delta. */
    public enum DeltaKind { OBSERVE, TARGET, REMOVE }

    /** Immutable atom mutation used by {@link #applyAtomDeltas(List)}. */
    public record AtomDelta(DeltaKind kind, PslAtom atom, double value) {
        public AtomDelta {
            if (kind == null) throw new IllegalArgumentException("kind must not be null");
            if (atom == null || !atom.isGround()) {
                throw new IllegalArgumentException("Atom must be ground: " + atom);
            }
        }

        public static AtomDelta observe(PslAtom atom, double value) {
            return new AtomDelta(DeltaKind.OBSERVE, atom, value);
        }

        public static AtomDelta target(PslAtom atom) {
            return new AtomDelta(DeltaKind.TARGET, atom, 0.0);
        }

        public static AtomDelta remove(PslAtom atom) {
            return new AtomDelta(DeltaKind.REMOVE, atom, 0.0);
        }
    }

    /** Identity of one grounded template instance. */
    private record GroundRuleKey(int templateIndex, String display) { }

    /** The program whose atoms and rules are managed. */
    private final PslProgram program;

    /** Maintained list of all current ground rules. */
    private final List<GroundRule> groundRules = new ArrayList<>();

    /** Ground rules indexed without conflating distinct template indexes. */
    private final Map<GroundRuleKey, GroundRule> groundRulesByKey = new LinkedHashMap<>();

    /** Index: predicate name → the template indexes that mention it. */
    private final Map<String, List<Integer>> predicateToTemplateIndexes = new LinkedHashMap<>();

    /**
     * Create an incremental grounder for {@code program}, performing an initial full
     * grounding to populate the maintained ground-rule set.
     *
     * @param program the PSL program to manage; must not be null
     */
    public IncrementalGrounder(PslProgram program) {
        if (program == null) throw new IllegalArgumentException("program must not be null");
        this.program = program;
        // Build predicate → template-index index.
        for (int i = 0; i < program.rules().size(); i++) {
            PslRule rule = program.rules().get(i);
            for (String pred : predicatesOf(rule)) {
                predicateToTemplateIndexes.computeIfAbsent(pred, k -> new ArrayList<>()).add(i);
            }
        }
        // Initial full grounding.
        for (GroundRule gr : program.ground()) {
            groundRulesByKey.put(groundRuleKey(gr), gr);
        }
        rebuildGroundRuleList();
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
        applyAtomDeltas(List.of(AtomDelta.observe(atom, value)));
    }

    /**
     * Declare a new target atom and update the ground rules incrementally.
     *
     * @param atom the ground target atom to add
     */
    public void addTargetAtom(PslAtom atom) {
        applyAtomDeltas(List.of(AtomDelta.target(atom)));
    }

    /**
     * Apply a batch of atom mutations and re-ground each affected template at most once.
     *
     * <p>The operations are applied in list order, so a remove followed by an add in the same
     * batch has the expected final state. Structural changes and observed/target role changes
     * are coalesced by predicate before any grounding work is performed.</p>
     *
     * @param deltas atom operations to apply
     */
    public void applyAtomDeltas(List<AtomDelta> deltas) {
        if (deltas == null) throw new IllegalArgumentException("deltas must not be null");
        if (deltas.isEmpty()) return;
        // Validate the complete batch before mutating the program so a malformed entry cannot
        // leave earlier entries applied without their corresponding grounding replacement.
        for (AtomDelta delta : deltas) {
            if (delta == null) throw new IllegalArgumentException("delta must not be null");
        }

        Set<String> affectedPredicates = new LinkedHashSet<>();
        for (AtomDelta delta : deltas) {
            switch (delta.kind()) {
                case OBSERVE -> {
                    program.observe(delta.atom(), delta.value());
                    affectedPredicates.add(delta.atom().predicate());
                }
                case TARGET -> {
                    program.target(delta.atom());
                    affectedPredicates.add(delta.atom().predicate());
                }
                case REMOVE -> {
                    if (program.removeAtom(delta.atom())) {
                        affectedPredicates.add(delta.atom().predicate());
                    }
                }
            }
        }

        replaceTemplatesForPredicates(affectedPredicates);
    }

    /** Varargs convenience overload for callers constructing a small delta batch inline. */
    public void applyAtomDeltas(AtomDelta... deltas) {
        if (deltas == null) throw new IllegalArgumentException("deltas must not be null");
        applyAtomDeltas(Arrays.asList(deltas));
    }

    /**
     * Remove an atom from both the maintained ground-rule set and the underlying program.
     *
     * @param atomKey the canonical key of the atom to remove (e.g. {@code "State(alice)"})
     */
    public void removeAtom(String atomKey) {
        if (atomKey == null) return;
        PslAtom atom = parseAtomKey(atomKey);
        if (atom != null) {
            applyAtomDeltas(List.of(AtomDelta.remove(atom)));
        }
    }

    /**
     * Add a new rule to the program and incrementally ground it against all current atoms.
     *
     * @param rule the PSL rule to add
     */
    public void addRule(PslRule rule) {
        int templateIndex = program.rules().size();
        program.addRule(rule);
        for (String pred : predicatesOf(rule)) {
            predicateToTemplateIndexes.computeIfAbsent(pred, k -> new ArrayList<>()).add(templateIndex);
        }
        replaceTemplates(Set.of(templateIndex));
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

    /** Re-ground all templates mentioning any changed predicate, once per template. */
    private void replaceTemplatesForPredicates(Set<String> predicates) {
        if (predicates.isEmpty()) return;
        Set<Integer> affectedTemplates = new LinkedHashSet<>();
        for (String predicate : predicates) {
            affectedTemplates.addAll(predicateToTemplateIndexes.getOrDefault(predicate, List.of()));
        }
        replaceTemplates(affectedTemplates);
    }

    /** Replace all maintained groundings for a set of templates with fresh groundings. */
    private void replaceTemplates(Set<Integer> affectedTemplates) {
        if (affectedTemplates.isEmpty()) return;
        groundRulesByKey.entrySet().removeIf(entry ->
                affectedTemplates.contains(entry.getKey().templateIndex()));
        for (GroundRule gr : program.groundRulesForTemplates(affectedTemplates)) {
            groundRulesByKey.put(groundRuleKey(gr), gr);
        }
        rebuildGroundRuleList();
    }

    private GroundRuleKey groundRuleKey(GroundRule groundRule) {
        return new GroundRuleKey(groundRule.templateIndex(), groundRule.display());
    }

    private void rebuildGroundRuleList() {
        groundRules.clear();
        groundRules.addAll(groundRulesByKey.values());
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

}
