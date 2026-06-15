/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import java.util.*;

/**
 * A node (random variable) in a Bayesian network.
 *
 * <p>Each node has:</p>
 * <ul>
 *   <li>A variable name (mapped from the KG node ID)</li>
 *   <li>A list of states (default: binary [FALSE, TRUE])</li>
 *   <li>Parent nodes (directed edges point from parent → child)</li>
 *   <li>A conditional probability table (CPT) stored as a {@link Factor}</li>
 * </ul>
 *
 * <p>The CPT encodes P(this | parents). For a binary node with parents A, B,
 * the CPT has variables [A, B, this] and 2^3 = 8 entries.</p>
 */
public class BayesianNode {

    public static final List<String> BINARY_STATES = List.of("FALSE", "TRUE");

    private final String variableName;
    private final String kgNodeId;
    private final String title;
    private final List<String> states;
    private final List<BayesianNode> parents;
    private Factor cpt;

    /**
     * Create a binary Bayesian node.
     */
    public BayesianNode(String variableName, String kgNodeId, String title) {
        this(variableName, kgNodeId, title, BINARY_STATES);
    }

    /**
     * Create a Bayesian node with custom states.
     */
    public BayesianNode(String variableName, String kgNodeId, String title, List<String> states) {
        this.variableName = variableName;
        this.kgNodeId = kgNodeId;
        this.title = title;
        this.states = List.copyOf(states);
        this.parents = new ArrayList<>();
    }

    public String getVariableName() {
        return variableName;
    }

    public String getKgNodeId() {
        return kgNodeId;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getStates() {
        return states;
    }

    public int getCardinality() {
        return states.size();
    }

    public List<BayesianNode> getParents() {
        return Collections.unmodifiableList(parents);
    }

    public void addParent(BayesianNode parent) {
        if (!parents.contains(parent)) {
            parents.add(parent);
        }
    }

    public Factor getCpt() {
        return cpt;
    }

    public void setCpt(Factor cpt) {
        this.cpt = cpt;
    }

    /**
     * Check if this is a root node (no parents).
     */
    public boolean isRoot() {
        return parents.isEmpty();
    }

    /**
     * Get the state index for a given state name.
     */
    public int getStateIndex(String stateName) {
        int idx = states.indexOf(stateName);
        if (idx < 0) {
            throw new IllegalArgumentException("Unknown state '" + stateName + "' for variable " + variableName);
        }
        return idx;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BayesianNode that)) return false;
        return variableName.equals(that.variableName);
    }

    @Override
    public int hashCode() {
        return variableName.hashCode();
    }

    @Override
    public String toString() {
        return "BayesianNode{" + variableName + " (" + title + "), parents=" +
                parents.stream().map(BayesianNode::getVariableName).toList() + "}";
    }
}
