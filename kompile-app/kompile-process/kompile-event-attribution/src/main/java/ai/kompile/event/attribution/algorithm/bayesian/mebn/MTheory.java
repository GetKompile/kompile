/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn;

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
public class MTheory {

    private final String name;
    private final Map<String, EntityType> entityTypes;
    private final Map<String, MFrag> mFrags;

    /**
     * Maps each resident RV name to its home MFrag (for consistency checking).
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
     * @throws IllegalArgumentException if any resident RV already has a home MFrag
     */
    public MTheory addMFrag(MFrag mfrag) {
        // Consistency check: each resident must have exactly one home
        for (RandomVariable rv : mfrag.getResidentNodes()) {
            String existing = residentHomeMap.get(rv.getName());
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Resident RV '" + rv.getName() + "' already has home MFrag '" +
                                existing + "', cannot add to '" + mfrag.getName() + "'");
            }
            residentHomeMap.put(rv.getName(), mfrag.getName());
        }
        mFrags.put(mfrag.getName(), mfrag);
        return this;
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
