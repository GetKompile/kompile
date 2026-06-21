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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits hard and soft PSL rules that enforce ontological constraints (type mutual exclusion,
 * subsumption, and functional dependencies) into a {@link PslProgram}.
 *
 * <p>This is the PSL analogue of the ontological constraint layer described in
 * Pujara et al. (2013) "Knowledge Graph Identification" (KGI), and Bach et al. (2017) §2.</p>
 *
 * <h3>Constraint types supported</h3>
 * <ul>
 *   <li><b>Type mutual exclusion</b>: two types are mutually exclusive if an entity cannot
 *       belong to both simultaneously. Emitted as a hard rule:
 *       {@code Type(X, A) & Type(X, B) -> . } (infinite weight, always enforced).</li>
 *   <li><b>Subsumption</b>: one type is a subtype of another; every instance of the subtype
 *       must also be an instance of the supertype. Emitted as a hard rule:
 *       {@code Type(X, Sub) -> Type(X, Super) .}</li>
 *   <li><b>Functional dependency</b>: a relation is single-valued (at most one target per
 *       source). Emitted as a hard rule:
 *       {@code Rel(X, Y) & Rel(X, Z) & (Y != Z) -> . }</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * OntologicalConstraintBuilder builder = new OntologicalConstraintBuilder();
 * builder.addMutualExclusion("Person", "Organization")
 *        .addSubsumption("Engineer", "Person")
 *        .addFunctionalDependency("HasBoss");
 * builder.applyTo(program);
 * }</pre>
 *
 * <p>The constraint rules reference two predicates already registered in the program:</p>
 * <ul>
 *   <li>{@code Type(X, T)} — observed atom: entity X has type T.</li>
 *   <li>{@code Rel(X, Y)} — observed atom: entity X has relation Rel to entity Y.</li>
 * </ul>
 *
 * <p>This builder does NOT register the observed atoms — the calling code (e.g.
 * {@link GraphPslProgramBuilder}) is responsible for populating them.</p>
 */
public class OntologicalConstraintBuilder {

    /** Predicate name used for type membership atoms: {@code Type(entityConst, typeConst)}. */
    public static final String TYPE_PREDICATE = "Type";

    /** Pairs of mutually exclusive types: (A, B). */
    private final List<String[]> mutualExclusions = new ArrayList<>();

    /** Subsumption pairs: (subtype, supertype). */
    private final List<String[]> subsumptions = new ArrayList<>();

    /** Relation predicates declared as functional (single-valued per source). */
    private final List<String> functionalRelations = new ArrayList<>();

    // ─── Builder methods ─────────────────────────────────────────────────────────

    /**
     * Declare that types {@code typeA} and {@code typeB} are mutually exclusive: no entity
     * may simultaneously be of both types.
     *
     * @param typeA the first type constant (e.g. "Person")
     * @param typeB the second type constant (e.g. "Organization")
     * @return this builder for chaining
     */
    public OntologicalConstraintBuilder addMutualExclusion(String typeA, String typeB) {
        mutualExclusions.add(new String[]{typeA, typeB});
        return this;
    }

    /**
     * Declare that {@code subtype} is subsumed by {@code supertype}: every entity of type
     * {@code subtype} must also be of type {@code supertype}.
     *
     * @param subtype   the more specific type (e.g. "Engineer")
     * @param supertype the more general type (e.g. "Person")
     * @return this builder for chaining
     */
    public OntologicalConstraintBuilder addSubsumption(String subtype, String supertype) {
        subsumptions.add(new String[]{subtype, supertype});
        return this;
    }

    /**
     * Declare that {@code relationPredicate} is a functional (single-valued) relation:
     * for each source entity there is at most one target entity.
     *
     * @param relationPredicate the predicate name (e.g. "HasBoss", "Link")
     * @return this builder for chaining
     */
    public OntologicalConstraintBuilder addFunctionalDependency(String relationPredicate) {
        functionalRelations.add(relationPredicate);
        return this;
    }

    // ─── Rule emission ───────────────────────────────────────────────────────────

    /**
     * Emit all configured constraint rules into {@code program}.
     *
     * <p>All type constraints use the predicate {@value #TYPE_PREDICATE}. Functional
     * dependency constraints use the predicate name given to {@link #addFunctionalDependency}.</p>
     *
     * @param program the PSL program to add rules to
     */
    public void applyTo(PslProgram program) {
        // 1. Mutual exclusion: Type(X, A) & Type(X, B) -> .   (hard)
        for (String[] pair : mutualExclusions) {
            String typeA = pair[0];
            String typeB = pair[1];
            // Hard rule: body fires iff entity has both types; head is empty = violated.
            List<PslAtom> body = List.of(
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(typeA)),
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(typeB))
            );
            program.addRule(new PslRule(Double.POSITIVE_INFINITY, true, true,
                    body, List.of(), List.of()));
        }

        // 2. Subsumption: Type(X, Sub) -> Type(X, Super) .   (hard)
        for (String[] pair : subsumptions) {
            String sub = pair[0];
            String sup = pair[1];
            List<PslAtom> body = List.of(
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(sub))
            );
            List<PslAtom> head = List.of(
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(sup))
            );
            program.addRule(new PslRule(Double.POSITIVE_INFINITY, true, true,
                    body, head, List.of()));
        }

        // 3. Functional dependency: Rel(X, Y) & Rel(X, Z) & (Y != Z) -> .   (hard)
        for (String rel : functionalRelations) {
            List<PslAtom> body = List.of(
                    PslAtom.of(rel, false, Term.var("X"), Term.var("Y")),
                    PslAtom.of(rel, false, Term.var("X"), Term.var("Z"))
            );
            List<String[]> distinct = new ArrayList<>();
            distinct.add(new String[]{"Y", "Z"});
            program.addRule(new PslRule(Double.POSITIVE_INFINITY, true, true,
                    body, List.of(), distinct));
        }
    }

    /**
     * Build and return all constraint rules without adding them to a program.
     *
     * @return the list of constraint rules
     */
    public List<PslRule> buildRules() {
        List<PslRule> rules = new ArrayList<>();
        // Mutual exclusion
        for (String[] pair : mutualExclusions) {
            List<PslAtom> body = List.of(
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(pair[0])),
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(pair[1]))
            );
            rules.add(new PslRule(Double.POSITIVE_INFINITY, true, true, body, List.of(), List.of()));
        }
        // Subsumption
        for (String[] pair : subsumptions) {
            List<PslAtom> body = List.of(
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(pair[0]))
            );
            List<PslAtom> head = List.of(
                    PslAtom.of(TYPE_PREDICATE, false, Term.var("X"), Term.con(pair[1]))
            );
            rules.add(new PslRule(Double.POSITIVE_INFINITY, true, true, body, head, List.of()));
        }
        // Functional dependency
        for (String rel : functionalRelations) {
            List<PslAtom> body = List.of(
                    PslAtom.of(rel, false, Term.var("X"), Term.var("Y")),
                    PslAtom.of(rel, false, Term.var("X"), Term.var("Z"))
            );
            List<String[]> funcDistinct = new ArrayList<>();
            funcDistinct.add(new String[]{"Y", "Z"});
            rules.add(new PslRule(Double.POSITIVE_INFINITY, true, true,
                    body, List.of(), funcDistinct));
        }
        return rules;
    }

    // ─── Accessors ───────────────────────────────────────────────────────────────

    public List<String[]> mutualExclusions() { return List.copyOf(mutualExclusions); }
    public List<String[]> subsumptions() { return List.copyOf(subsumptions); }
    public List<String> functionalRelations() { return List.copyOf(functionalRelations); }
}
