/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Quantitative Bipolar Argumentation Framework (QBAF).
 *
 * <p>A QBAF is a directed graph over {@link Argument} nodes with two types of edges:
 * <ul>
 *   <li><b>SUPPORT</b> edges: {@code (a → b)} meaning {@code a} supports {@code b}.</li>
 *   <li><b>ATTACK</b> edges: {@code (a → b)} meaning {@code a} attacks {@code b}.</li>
 * </ul>
 * Exactly one {@link ArgKind#CLAIM} node must be present. No self-edges are allowed.
 * All base scores must be in [0, 1].</p>
 *
 * <p>Build via {@link Builder}; evaluate with {@link DfQuadSemantics}.</p>
 */
public final class Qbaf {

    /** An edge in the QBAF: a directed relationship between two argument ids. */
    public enum EdgeType { SUPPORT, ATTACK }

    /** A single directed edge in the QBAF. */
    public record Edge(String sourceId, String targetId, EdgeType type) {
        public Edge {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(type, "type");
            if (sourceId.equals(targetId)) {
                throw new IllegalArgumentException("Self-edges not allowed: " + sourceId);
            }
        }
    }

    private final Map<String, Argument> arguments;  // id → argument
    private final List<Edge> edges;
    private final String claimId;

    // adjacency: targetId → list of (sourceId, type)
    private final Map<String, List<Edge>> incomingEdges;

    private Qbaf(Map<String, Argument> arguments, List<Edge> edges) {
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.edges = Collections.unmodifiableList(new ArrayList<>(edges));

        String foundClaim = null;
        for (Argument a : arguments.values()) {
            if (a.kind() == ArgKind.CLAIM) {
                if (foundClaim != null) {
                    throw new IllegalArgumentException(
                            "QBAF must have exactly one CLAIM node; found: " + foundClaim + " and " + a.id());
                }
                foundClaim = a.id();
            }
        }
        if (foundClaim == null) {
            throw new IllegalArgumentException("QBAF must have exactly one CLAIM node");
        }
        this.claimId = foundClaim;

        Map<String, List<Edge>> incoming = new LinkedHashMap<>();
        for (Edge e : edges) {
            if (!arguments.containsKey(e.sourceId())) {
                throw new IllegalArgumentException("Edge source not found: " + e.sourceId());
            }
            if (!arguments.containsKey(e.targetId())) {
                throw new IllegalArgumentException("Edge target not found: " + e.targetId());
            }
            incoming.computeIfAbsent(e.targetId(), k -> new ArrayList<>()).add(e);
        }
        this.incomingEdges = Collections.unmodifiableMap(incoming);
    }

    /** The id of the single CLAIM argument. */
    public String claimId() { return claimId; }

    /** The CLAIM argument. */
    public Argument claim() { return arguments.get(claimId); }

    /** All arguments in this QBAF. */
    public Collection<Argument> arguments() { return arguments.values(); }

    /** Look up an argument by id. */
    public Optional<Argument> argument(String id) { return Optional.ofNullable(arguments.get(id)); }

    /** All edges in this QBAF. */
    public List<Edge> edges() { return edges; }

    /** Incoming edges (both SUPPORT and ATTACK) to a given argument id. */
    public List<Edge> incomingEdges(String targetId) {
        return incomingEdges.getOrDefault(targetId, List.of());
    }

    /** The set of argument ids that attack the given target. */
    public Set<String> attackers(String targetId) {
        Set<String> result = new LinkedHashSet<>();
        for (Edge e : incomingEdges.getOrDefault(targetId, List.of())) {
            if (e.type() == EdgeType.ATTACK) result.add(e.sourceId());
        }
        return result;
    }

    /** The set of argument ids that support the given target. */
    public Set<String> supporters(String targetId) {
        Set<String> result = new LinkedHashSet<>();
        for (Edge e : incomingEdges.getOrDefault(targetId, List.of())) {
            if (e.type() == EdgeType.SUPPORT) result.add(e.sourceId());
        }
        return result;
    }

    // ── Builder ───────────────────────────────────────────────────────────────────

    /** Mutable builder for a {@link Qbaf}. */
    public static final class Builder {

        private final Map<String, Argument> arguments = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();

        /** Add an argument. Duplicate ids are rejected. */
        public Builder argument(Argument arg) {
            Objects.requireNonNull(arg, "arg");
            if (arguments.containsKey(arg.id())) {
                throw new IllegalArgumentException("Duplicate argument id: " + arg.id());
            }
            arguments.put(arg.id(), arg);
            return this;
        }

        /** Add an argument inline. */
        public Builder argument(String id, String label, double baseScore, ArgKind kind) {
            return argument(new Argument(id, label, baseScore, kind));
        }

        /** Add a SUPPORT edge from {@code sourceId} to {@code targetId}. */
        public Builder support(String sourceId, String targetId) {
            edges.add(new Edge(sourceId, targetId, EdgeType.SUPPORT));
            return this;
        }

        /** Add an ATTACK edge from {@code sourceId} to {@code targetId}. */
        public Builder attack(String sourceId, String targetId) {
            edges.add(new Edge(sourceId, targetId, EdgeType.ATTACK));
            return this;
        }

        /** Add an explicit edge of the given type. */
        public Builder edge(String sourceId, String targetId, EdgeType type) {
            edges.add(new Edge(sourceId, targetId, type));
            return this;
        }

        /**
         * Build and validate the QBAF.
         *
         * @throws IllegalArgumentException if there is not exactly one CLAIM node,
         *                                  if any self-edge is present, or if an edge
         *                                  references an undefined argument id
         */
        public Qbaf build() {
            return new Qbaf(arguments, edges);
        }
    }

    /** Return a new {@link Builder}. */
    public static Builder builder() { return new Builder(); }
}
