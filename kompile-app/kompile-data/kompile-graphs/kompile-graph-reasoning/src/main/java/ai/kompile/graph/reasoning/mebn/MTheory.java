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

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An MTheory: a consistent collection of {@link MFrag}s that together define
 * a unique joint probability distribution over arbitrarily many entity instances.
 *
 * <p>An MTheory is the top-level container for a Multi-Entity Bayesian Network.
 * It contains:</p>
 * <ul>
 *   <li>A set of entity types defining the domain ontology</li>
 *   <li>A set of MFrags defining the probabilistic relationships</li>
 *   <li>Consistency constraints ensuring each resident RV has exactly one home MFrag</li>
 * </ul>
 *
 * <p>Given a set of specific entity instances and evidence, the MTheory is
 * grounded into a Situation-Specific Bayesian Network (SSBN) by the
 * {@link SSBNGenerator}.</p>
 */
public class MTheory implements Serializable {

    private final String name;
    private final Map<String, EntityType> entityTypes;
    private final Map<String, MFrag> mFrags;

    /**
     * Maps each resident RV signature (name + argument types) to its home MFrag.
     * The key format is "rvName(Type1,Type2,...)" to distinguish polymorphic overloads.
     * This implements the MEBN rule that each resident RV has exactly one home MFrag
     * while allowing isA-polymorphic MFrags (e.g., hasOwner(Dog) vs. hasOwner(Animal)).
     */
    private final Map<String, String> residentHomeMap;

    public MTheory(String name) {
        this.name = name;
        this.entityTypes = new LinkedHashMap<>();
        this.mFrags = new LinkedHashMap<>();
        this.residentHomeMap = new HashMap<>();
    }

    public String getName() {
        return name;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY TYPE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    public MTheory addEntityType(EntityType entityType) {
        entityTypes.put(entityType.getTypeName(), entityType);
        return this;
    }

    public EntityType getEntityType(String typeName) {
        return entityTypes.get(typeName);
    }

    public Collection<EntityType> getEntityTypes() {
        return Collections.unmodifiableCollection(entityTypes.values());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MFRAG MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add an MFrag to this theory.
     *
     * <p>The consistency check uses a composite key of RV name + argument type signature
     * to allow isA-polymorphic MFrags (e.g., {@code hasOwner(Dog)} and {@code hasOwner(Animal)}
     * can coexist — they are distinct RV signatures that happen to share a base name).</p>
     *
     * @throws IllegalArgumentException if any resident RV (same name AND same argument types)
     *                                  already has a home MFrag
     */
    public MTheory addMFrag(MFrag mfrag) {
        // Consistency check: each resident RV with the same typed signature must have exactly one home
        for (RandomVariable rv : mfrag.getResidentNodes()) {
            String key = rvSignatureKey(rv);
            String existing = residentHomeMap.get(key);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Resident RV '" + key + "' already has home MFrag '" +
                                existing + "', cannot add to '" + mfrag.getName() + "'");
            }
            residentHomeMap.put(key, mfrag.getName());
            // Also register the bare name → first home MFrag (for untyped findHomeMFrag fallback)
            residentHomeMap.putIfAbsent(rv.getName(), mfrag.getName());
        }
        mFrags.put(mfrag.getName(), mfrag);
        return this;
    }

    /**
     * Build a composite key for a resident RV that includes both name and argument types.
     * Allows the same base name with different typed argument lists to coexist.
     */
    private static String rvSignatureKey(RandomVariable rv) {
        if (rv.getArgumentTypes().isEmpty()) return rv.getName();
        String argTypes = rv.getArgumentTypes().stream()
                .map(EntityType::getTypeName)
                .reduce((a, b) -> a + "," + b).orElse("");
        return rv.getName() + "(" + argTypes + ")";
    }

    public MFrag getMFrag(String name) {
        return mFrags.get(name);
    }

    public Collection<MFrag> getMFrags() {
        return Collections.unmodifiableCollection(mFrags.values());
    }

    /**
     * Find the home MFrag for a given resident RV.
     */
    public Optional<MFrag> findHomeMFrag(String residentRvName) {
        String fragName = residentHomeMap.get(residentRvName);
        return fragName != null ? Optional.ofNullable(mFrags.get(fragName)) : Optional.empty();
    }

    /**
     * Find the most-specific MFrag defining {@code residentNodeName} for the given entity type.
     *
     * <p>Implements the MEBN isA polymorphism rule (Laskey 2008, Section 4.2; PR-OWL Costa/Laskey):
     * the MFrag whose resident RV argument type is the most-specific supertype of
     * {@code entityType} that still has a defining MFrag wins.  The search walks the isA
     * chain from {@code entityType} toward the root, returning the first match.</p>
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Collect all MFrags that have {@code residentNodeName} as a resident node.</li>
     *   <li>Walk the isA chain starting at {@code entityType} (most specific → least specific).</li>
     *   <li>At each level, pick the MFrag whose resident RV's first argument type matches.</li>
     *   <li>Return the first match; fall back to any MFrag that has the RV if no typed match found.</li>
     * </ol>
     *
     * @param residentNodeName the name of the resident random variable (e.g. "isActive")
     * @param entityType       the concrete entity type being queried (e.g. Dog)
     * @return the most-specific MFrag, or {@link Optional#empty()} if none exists
     */
    public Optional<MFrag> getMostSpecificMFrag(String residentNodeName, EntityType entityType) {
        // Walk the isA chain from most-specific to most-general
        EntityType current = entityType;
        while (current != null) {
            EntityType candidate = current; // effectively final for lambda
            // Find an MFrag that (a) has this residentNodeName as resident AND (b) whose
            // resident RV's first argument type matches the current type in the chain
            for (MFrag frag : mFrags.values()) {
                for (RandomVariable rv : frag.getResidentNodes()) {
                    if (rv.getName().equals(residentNodeName)) {
                        if (rv.getArity() == 0) {
                            // Propositional: matches any entity type
                            return Optional.of(frag);
                        }
                        EntityType rvArgType = rv.getArgumentTypes().get(0);
                        if (rvArgType.equals(candidate)) {
                            return Optional.of(frag);
                        }
                    }
                }
            }
            current = current.getSuperType();
        }
        // Fall back: return any MFrag that defines the resident node (no type discrimination)
        return findHomeMFrag(residentNodeName);
    }

    /**
     * Find a random variable by name across all MFrags.
     */
    public Optional<RandomVariable> findVariable(String rvName) {
        for (MFrag frag : mFrags.values()) {
            Optional<RandomVariable> rv = frag.findVariable(rvName);
            if (rv.isPresent()) return rv;
        }
        return Optional.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Validate that this MTheory is consistent:
     * - Every input RV referenced in an MFrag has a home MFrag as a resident
     * - No circular MFrag dependencies (at the MFrag level)
     *
     * @return list of validation errors (empty if valid)
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        for (MFrag frag : mFrags.values()) {
            for (RandomVariable input : frag.getInputNodes()) {
                if (!residentHomeMap.containsKey(input.getName())) {
                    errors.add("Input RV '" + input.getName() + "' in MFrag '" +
                            frag.getName() + "' has no home MFrag defining its distribution");
                }
            }
        }

        return errors;
    }

    /**
     * Get summary statistics about this MTheory.
     */
    public Map<String, Object> getStatistics() {
        int totalResidents = 0;
        int totalInputs = 0;
        int totalContexts = 0;

        for (MFrag frag : mFrags.values()) {
            totalResidents += frag.getResidentNodes().size();
            totalInputs += frag.getInputNodes().size();
            totalContexts += frag.getContextConstraints().size();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("name", name);
        stats.put("entityTypes", entityTypes.size());
        stats.put("mFrags", mFrags.size());
        stats.put("residentVariables", totalResidents);
        stats.put("inputVariables", totalInputs);
        stats.put("contextConstraints", totalContexts);
        stats.put("totalEntities", entityTypes.values().stream()
                .mapToInt(et -> et.getEntityIds().size()).sum());

        // Detailed MFrag breakdown
        List<Map<String, Object>> mFragDetails = new ArrayList<>();
        for (MFrag frag : mFrags.values()) {
            Map<String, Object> fragInfo = new LinkedHashMap<>();
            fragInfo.put("name", frag.getName());
            fragInfo.put("residentVariables", frag.getResidentNodes().stream()
                    .map(RandomVariable::getName).collect(Collectors.toList()));
            fragInfo.put("inputVariables", frag.getInputNodes().stream()
                    .map(RandomVariable::getName).collect(Collectors.toList()));
            fragInfo.put("contextConstraints", frag.getContextConstraints().stream()
                    .map(Object::toString).collect(Collectors.toList()));
            // Edge strengths between parent → child within this MFrag
            Map<String, Double> edgeStrengths = frag.getEdgeStrengths();
            if (edgeStrengths != null && !edgeStrengths.isEmpty()) {
                fragInfo.put("edgeStrengths", edgeStrengths);
            }
            mFragDetails.add(fragInfo);
        }
        stats.put("mFragDetails", mFragDetails);

        // Detailed entity type breakdown
        List<Map<String, Object>> entityTypeDetails = new ArrayList<>();
        for (EntityType et : entityTypes.values()) {
            Map<String, Object> etInfo = new LinkedHashMap<>();
            etInfo.put("typeName", et.getTypeName());
            etInfo.put("entityCount", et.getEntityIds().size());
            etInfo.put("entityIds", et.getEntityIds());
            entityTypeDetails.add(etInfo);
        }
        stats.put("entityTypeDetails", entityTypeDetails);

        return stats;
    }

    @Override
    public String toString() {
        return "MTheory{" + name + ", " + mFrags.size() + " MFrags, " +
                entityTypes.size() + " entity types}";
    }
}
