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

import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.LogicalConstraint;

import java.util.*;
import java.util.function.BiFunction;

/**
 * An MFrag (MEBN Fragment): a template for a fragment of a Bayesian network.
 *
 * <p>An MFrag defines a conditional probability distribution for instances of its
 * resident random variables, given the values of their parents (input nodes) and
 * subject to context constraints. Key components:</p>
 *
 * <ul>
 *   <li><b>Resident nodes</b>: RVs whose CPTs are defined in this MFrag</li>
 *   <li><b>Input nodes</b>: RVs whose CPTs are defined elsewhere but appear as
 *       parents in this fragment's graph</li>
 *   <li><b>Context nodes</b>: logical constraints (first-order formulas) that must
 *       evaluate to TRUE for this MFrag to apply to a given set of entities</li>
 *   <li><b>Local distribution</b>: a function that computes the CPT entries for
 *       resident nodes given specific entity bindings</li>
 * </ul>
 *
 * <p>When grounded with specific entities, an MFrag produces concrete BN nodes
 * and edges — one instantiation per valid entity tuple satisfying all context
 * constraints.</p>
 */
public class MFrag {

    private final String name;
    private final List<RandomVariable> residentNodes;
    private final List<RandomVariable> inputNodes;
    private final List<LogicalConstraint> contextConstraints;

    /**
     * Parent relationships: maps each resident RV name to the list of
     * parent RV names (can be resident or input).
     */
    private final Map<String, List<String>> parentMap;

    /**
     * Local distribution function: given (resident RV name, parent causal strengths),
     * produces the CPT values using noisy-OR or custom logic.
     * If null, defaults to noisy-OR with the provided strengths.
     */
    private BiFunction<String, double[], double[]> localDistribution;

    /**
     * Default causal strengths for parent edges (used when no custom distribution is set).
     * Maps "parentRV -> residentRV" to strength.
     */
    private final Map<String, Double> edgeStrengths;

    public MFrag(String name) {
        this.name = name;
        this.residentNodes = new ArrayList<>();
        this.inputNodes = new ArrayList<>();
        this.contextConstraints = new ArrayList<>();
        this.parentMap = new LinkedHashMap<>();
        this.edgeStrengths = new LinkedHashMap<>();
    }

    public String getName() {
        return name;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    public MFrag addResidentNode(RandomVariable rv) {
        if (rv.getRole() != RandomVariable.NodeRole.RESIDENT) {
            throw new IllegalArgumentException("Expected RESIDENT role for " + rv.getName());
        }
        residentNodes.add(rv);
        return this;
    }

    public MFrag addInputNode(RandomVariable rv) {
        if (rv.getRole() != RandomVariable.NodeRole.INPUT) {
            throw new IllegalArgumentException("Expected INPUT role for " + rv.getName());
        }
        inputNodes.add(rv);
        return this;
    }

    public MFrag addContextConstraint(LogicalConstraint constraint) {
        contextConstraints.add(constraint);
        return this;
    }

    /**
     * Define a parent relationship: parentRV is a parent of residentRV in this fragment.
     *
     * @param parentRvName   name of the parent RV (can be resident or input)
     * @param residentRvName name of the child resident RV
     * @param strength       causal strength of this edge (for noisy-OR CPT)
     */
    public MFrag addParentEdge(String parentRvName, String residentRvName, double strength) {
        parentMap.computeIfAbsent(residentRvName, k -> new ArrayList<>()).add(parentRvName);
        edgeStrengths.put(parentRvName + "->" + residentRvName, strength);
        return this;
    }

    public void setLocalDistribution(BiFunction<String, double[], double[]> localDistribution) {
        this.localDistribution = localDistribution;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    public List<RandomVariable> getResidentNodes() {
        return Collections.unmodifiableList(residentNodes);
    }

    public List<RandomVariable> getInputNodes() {
        return Collections.unmodifiableList(inputNodes);
    }

    public List<LogicalConstraint> getContextConstraints() {
        return Collections.unmodifiableList(contextConstraints);
    }

    public List<String> getParentsOf(String residentRvName) {
        return parentMap.getOrDefault(residentRvName, List.of());
    }

    public double getEdgeStrength(String parentRvName, String residentRvName) {
        return edgeStrengths.getOrDefault(parentRvName + "->" + residentRvName, 0.5);
    }

    public Map<String, Double> getEdgeStrengths() {
        return Collections.unmodifiableMap(edgeStrengths);
    }

    /**
     * Get all RVs in this MFrag (resident + input).
     */
    public List<RandomVariable> getAllNodes() {
        List<RandomVariable> all = new ArrayList<>(residentNodes);
        all.addAll(inputNodes);
        return all;
    }

    /**
     * Find a random variable by name across all node types.
     */
    public Optional<RandomVariable> findVariable(String name) {
        for (RandomVariable rv : residentNodes) {
            if (rv.getName().equals(name)) return Optional.of(rv);
        }
        for (RandomVariable rv : inputNodes) {
            if (rv.getName().equals(name)) return Optional.of(rv);
        }
        return Optional.empty();
    }

    public BiFunction<String, double[], double[]> getLocalDistribution() {
        return localDistribution;
    }

    @Override
    public String toString() {
        return "MFrag{" + name +
                ", residents=" + residentNodes.size() +
                ", inputs=" + inputNodes.size() +
                ", contexts=" + contextConstraints.size() + "}";
    }
}
