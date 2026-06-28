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

import ai.kompile.graph.reasoning.mebn.logic.Constraints;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure MEBN builder for a <em>typed, relational</em> {@link MTheory} — no infrastructure
 * dependencies (no graph store, no Spring, no domain edge types). Given a list of
 * {@link RelationDescriptor}s it produces:
 *
 * <ul>
 *   <li>A base {@link EntityType} ({@value #ALL_NODES_TYPE}) covering every entity in the theory.
 *       All per-relation source/target types are registered as subtypes of {@code AllNodes} so that
 *       {@link MTheory#getMostSpecificMFrag} can walk the isA chain and find the shared
 *       {@code EntityRelevance} home fragment when resolving typed {@code isRelevant} inputs.</li>
 *   <li>Per-relation-type {@link EntityType}s derived from the descriptors' source and target type
 *       names — one EntityType per distinct type name, populated with the entity IDs supplied in
 *       each descriptor.</li>
 *   <li>A foundational {@code EntityRelevance} {@link MFrag} with a unary
 *       {@code isRelevant(AllNodes)} resident RV — the shared parent of every relationship.</li>
 *   <li>One binary relationship {@link MFrag} per descriptor: {@code rel(SourceType, TargetType)}
 *       resident, conditioned on {@code isRelevant(SourceType)} (INPUT + parent edge whose
 *       strength is the descriptor's {@link RelationDescriptor#activationProbability()}), and gated
 *       by four context constraints (canonical PR-OWL):
 *       <ol>
 *         <li>IsA(arg0, SourceType) via {@link Constraints#hasType}</li>
 *         <li>IsA(arg1, TargetType) via {@link Constraints#hasType}</li>
 *         <li>{@code notEqual(arg0, arg1)} — distinct endpoints</li>
 *         <li>{@code edgeExists(arg0, arg1)} — SSBN grounding bounded to real edges</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <p>Arg-var alignment: the binary resident RV's argument variables are named
 * {@code SourceTypeName_0} and {@code TargetTypeName_1} (standard
 * {@link RandomVariable} convention), and all four context constraints reference those same names,
 * so the SSBN generator can unify shared logical variables across the fragment. The INPUT
 * {@code isRelevant(SourceType)} also produces arg-var {@code SourceTypeName_0}, directly matching
 * the resident RV's first argument position for correct SSBN binding.</p>
 *
 * <p>Because every relationship fragment carries an {@code edgeExists} context, SSBN grounding is
 * bounded by the entities that actually share an edge — not the N² node-pair Cartesian product —
 * and the noisy-OR weight learner sees exactly one template edge per fragment. This is the generic
 * primitive behind both the auto-built crawl theory and any other relation-derived MTheory; callers
 * supply the (infra-specific) per-type entity ids and activation probabilities.</p>
 *
 * <p>Deterministic: the fragment order follows the iteration order of {@code relations}.</p>
 */
public final class RelationalMTheoryBuilder {

    /**
     * Entity-type name covering all supplied nodes. Its arg-var name ({@code AllNodes_0}) is what
     * the {@code EntityRelevance} context constraint ({@code entityExists}) references.
     * All per-relation EntityTypes are subtypes of this one, enabling isA-polymorphic SSBN lookup.
     */
    public static final String ALL_NODES_TYPE = "AllNodes";

    /** Resident RV of the foundational relevance fragment; the shared parent of every relationship. */
    public static final String RELEVANCE_RV = "isRelevant";

    private RelationalMTheoryBuilder() {}

    // ─────────────────────────────────────────────────────────────────────────────
    // RelationDescriptor — the per-relation input to the builder
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Descriptor for a single relation in the theory.
     *
     * <h3>Activation-probability semantics</h3>
     * <p>{@code activationProbability} is the noisy-OR leak probability:
     * P({@code relation} = TRUE | {@code isRelevant}(src) = TRUE, {@code edgeExists}(src, tgt)).
     * It estimates how often this relation type activates between two entities given that the
     * source is relevant and a graph edge exists. Typical derivation options:</p>
     * <ul>
     *   <li><em>From mean edge weight</em> — when edge weights represent co-occurrence or
     *       confidence in [0,1], the mean observed weight over all edges of this type is a
     *       valid per-build frequency estimate and is used as the activation probability.
     *       This conflates topology with the probability only in the prior; the noisy-OR weight
     *       learner can refine it from observations.</li>
     *   <li><em>Default (0.5)</em> — when no edge weight data is available, a uniform prior is
     *       used as a conservative starting estimate.</li>
     * </ul>
     * <p>Cross-cascade pooled learning (estimating the probability from held-out observation sets)
     * is out of scope here; that belongs in the noisy-OR weight learner downstream of this
     * builder.</p>
     *
     * @param name                  relation name — used as both the MFrag name and the resident RV
     *                              name; must not contain spaces (use sanitized identifiers)
     * @param sourceTypeName        dominant source entity-type name (e.g. {@code "ENTITY"},
     *                              {@code "PERSON"}); becomes arg-var {@code sourceTypeName_0}
     * @param targetTypeName        dominant target entity-type name (e.g. {@code "ENTITY"},
     *                              {@code "ORG"}); becomes arg-var {@code targetTypeName_1}
     * @param activationProbability noisy-OR activation probability in [0, 1]
     * @param sourceEntityIds       IDs of entities whose type is {@code sourceTypeName}
     * @param targetEntityIds       IDs of entities whose type is {@code targetTypeName}
     */
    public record RelationDescriptor(
            String name,
            String sourceTypeName,
            String targetTypeName,
            double activationProbability,
            Collection<String> sourceEntityIds,
            Collection<String> targetEntityIds) {

        /** Compact constructor — validates invariants. */
        public RelationDescriptor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sourceTypeName, "sourceTypeName");
            Objects.requireNonNull(targetTypeName, "targetTypeName");
            if (activationProbability < 0.0 || activationProbability > 1.0) {
                throw new IllegalArgumentException(
                        "activationProbability must be in [0,1], got " + activationProbability);
            }
            sourceEntityIds = sourceEntityIds != null ? List.copyOf(sourceEntityIds) : List.of();
            targetEntityIds = targetEntityIds != null ? List.copyOf(targetEntityIds) : List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // build
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Build the typed, relational MTheory from a list of {@link RelationDescriptor}s.
     *
     * <p>For each descriptor a distinct binary relationship MFrag is produced. The source and
     * target entity types are registered in the theory as subtypes of {@value #ALL_NODES_TYPE},
     * enabling isA-polymorphic SSBN resolution of the shared {@code isRelevant} RV.
     * See the class-level Javadoc for full arg-var alignment rules.</p>
     *
     * @param theoryName the MTheory name
     * @param relations  per-relation descriptors; {@code null} or empty yields the foundational
     *                   relevance fragment only; iteration order determines fragment order
     * @return the MTheory (foundational relevance fragment + one typed fragment per descriptor)
     */
    public static MTheory build(String theoryName, List<RelationDescriptor> relations) {
        MTheory mTheory = new MTheory(theoryName);

        // AllNodes supertype — domain of the foundational isRelevant RV.
        EntityType allNodes = new EntityType(ALL_NODES_TYPE);
        mTheory.addEntityType(allNodes);

        // Per-type EntityTypes (subtypes of AllNodes for isA-polymorphic SSBN lookup).
        // We collect and de-duplicate before registering so each type name maps to exactly one
        // EntityType instance even when multiple descriptors share the same type name.
        Map<String, EntityType> typeRegistry = new LinkedHashMap<>();
        if (relations != null) {
            for (RelationDescriptor rel : relations) {
                if (rel == null || rel.name().isBlank()) {
                    continue;
                }
                // Ensure source and target EntityType objects exist in the registry.
                typeRegistry.computeIfAbsent(rel.sourceTypeName(), tn -> {
                    EntityType et = new EntityType(tn);
                    et.setSuperType(allNodes); // IsA hierarchy for SSBN lookup
                    return et;
                });
                typeRegistry.computeIfAbsent(rel.targetTypeName(), tn -> {
                    EntityType et = new EntityType(tn);
                    et.setSuperType(allNodes);
                    return et;
                });
                // Populate entity IDs into both the specific-type EntityType and AllNodes.
                EntityType srcType = typeRegistry.get(rel.sourceTypeName());
                for (String id : rel.sourceEntityIds()) {
                    if (id != null) {
                        srcType.addEntity(id);
                        allNodes.addEntity(id);
                    }
                }
                EntityType tgtType = typeRegistry.get(rel.targetTypeName());
                for (String id : rel.targetEntityIds()) {
                    if (id != null) {
                        tgtType.addEntity(id);
                        allNodes.addEntity(id);
                    }
                }
            }
        }
        for (EntityType et : typeRegistry.values()) {
            mTheory.addEntityType(et);
        }

        // Foundational EntityRelevance MFrag — the shared parent for every relationship.
        MFrag relevanceFrag = new MFrag("EntityRelevance");
        relevanceFrag.addResidentNode(
                RandomVariable.unary(RELEVANCE_RV, allNodes, RandomVariable.NodeRole.RESIDENT));
        relevanceFrag.addContextConstraint(Constraints.entityExists(ALL_NODES_TYPE + "_0"));
        mTheory.addMFrag(relevanceFrag);

        if (relations == null) {
            return mTheory;
        }

        // One typed, relational MFrag per descriptor.
        for (RelationDescriptor rel : relations) {
            if (rel == null || rel.name().isBlank()) {
                continue;
            }
            EntityType srcType = typeRegistry.get(rel.sourceTypeName());
            EntityType tgtType = typeRegistry.get(rel.targetTypeName());

            // Arg-var names are "TypeName_i" as generated by RandomVariable's default constructor:
            //   position 0 → sourceTypeName_0, position 1 → targetTypeName_1.
            // All context constraints and the INPUT RV must reference these exact names.
            String srcArgVar = rel.sourceTypeName() + "_0";
            String tgtArgVar = rel.targetTypeName() + "_1";

            MFrag frag = new MFrag(rel.name());

            // Resident: rel(srcType, tgtType) — binary, typed relationship RV.
            // Arg-vars: [srcArgVar, tgtArgVar]
            frag.addResidentNode(RandomVariable.binary(
                    rel.name(), srcType, tgtType, RandomVariable.NodeRole.RESIDENT));

            // Input: isRelevant(srcType) — arg-var = srcArgVar, matching the binary RV's first
            // position for correct SSBN binding. srcType.getSuperType() = AllNodes, so
            // getMostSpecificMFrag("isRelevant", srcType) walks the isA chain and finds the
            // EntityRelevance home fragment.
            frag.addInputNode(RandomVariable.unary(
                    RELEVANCE_RV, srcType, RandomVariable.NodeRole.INPUT));

            // Parent edge: activationProbability = P(rel=TRUE | isRelevant(src)=TRUE, edgeExists).
            frag.addParentEdge(RELEVANCE_RV, rel.name(), rel.activationProbability());

            // IsA context constraints (canonical PR-OWL / Laskey 2008 §4.2):
            // verify the bound variables belong to the expected entity types at SSBN grounding time.
            frag.addContextConstraint(Constraints.hasType(srcArgVar, rel.sourceTypeName()));
            frag.addContextConstraint(Constraints.hasType(tgtArgVar, rel.targetTypeName()));

            // Grounding bounds: distinct endpoints + an edge must exist → SSBN stays bounded
            // to actual edges, not the N² node-pair Cartesian product.
            frag.addContextConstraint(Constraints.notEqual(srcArgVar, tgtArgVar));
            frag.addContextConstraint(Constraints.edgeExists(srcArgVar, tgtArgVar));

            mTheory.addMFrag(frag);
        }
        return mTheory;
    }
}
