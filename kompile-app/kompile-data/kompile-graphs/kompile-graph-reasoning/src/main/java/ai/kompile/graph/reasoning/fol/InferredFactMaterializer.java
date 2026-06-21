/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphEntityBuilder;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Materializes {@link InferredFact}s (PSL / MEBN / FOL conclusions) into a graph so inferences become
 * first-class, queryable structure rather than ephemeral request-scoped results.
 *
 * <p>The whole materialization <em>logic</em> — the atom grammar, the binary→relation / unary→attribute
 * classification, and the add/skip accounting — lives here, in the infrastructure-free library (no
 * store, Spring, or {@code jackson-databind} dependency). It writes through a generic
 * {@link InferredGraphSink}: the library ships a sink over its own {@link MutableReasoningGraph}, and
 * infrastructure clients (e.g. a knowledge-graph store adapter) implement the same sink to persist the
 * inferences to their backend. That way the only thing the store side owns is the ~2-method sink, not
 * a second copy of the materialization logic.</p>
 *
 * <p>Mapping of a canonical atom key:</p>
 * <ul>
 *   <li><b>Binary</b> {@code Pred(a, b)} → a directed relation {@code a --Pred--> b}, tagged
 *       {@code "INFERRED"}, with the soft-truth as weight and the run/version as attributes.</li>
 *   <li><b>Unary</b> {@code Pred(a)} → an {@code inferred.<Pred>} attribute on entity {@code a}.</li>
 * </ul>
 */
public final class InferredFactMaterializer {

    /** Tag applied to every inferred relation/entity so inferences can be filtered out or audited. */
    public static final String INFERRED_TAG = "INFERRED";

    /**
     * Target for materialized inferences — the infra-free seam between the materialization logic and
     * whatever graph receives it (an in-memory {@link MutableReasoningGraph}, a persistent store, ...).
     * Each method returns {@code false} when the fact can't be applied (e.g. an endpoint can't be
     * resolved), so the caller counts it as skipped rather than silently dropping it.
     */
    public interface InferredGraphSink {
        /** Apply a binary atom {@code Pred(from, to)} as an inferred relation. */
        boolean addRelation(ParsedAtom atom, InferredFact fact);

        /** Apply a unary atom {@code Pred(entity)} as an inferred attribute on the entity. */
        boolean setAttribute(ParsedAtom atom, InferredFact fact);
    }

    /** Outcome of a materialization run. */
    public record MaterializationResult(int relationsAdded, int attributesSet, int skipped) {
        public int total() {
            return relationsAdded + attributesSet;
        }
    }

    private InferredFactMaterializer() {
    }

    /**
     * Materialize a batch of inferred facts through {@code sink}. This is the shared logic: parse each
     * atom, route binary atoms to {@link InferredGraphSink#addRelation} and unary atoms to
     * {@link InferredGraphSink#setAttribute}, and tally what was applied vs skipped.
     *
     * @param facts the inferred facts (e.g. drained from an {@link InferredFactStore} after a run)
     * @param sink  the target graph/store
     * @return counts of relations added, attributes set, and facts skipped
     */
    public static MaterializationResult materialize(Collection<InferredFact> facts, InferredGraphSink sink) {
        if (facts == null || facts.isEmpty() || sink == null) {
            return new MaterializationResult(0, 0, 0);
        }
        int relations = 0, attrs = 0, skipped = 0;
        for (InferredFact fact : facts) {
            ParsedAtom atom = parseAtom(fact.atomKey());
            if (atom == null) {
                skipped++;
                continue;
            }
            if (atom.args().size() == 2) {
                if (sink.addRelation(atom, fact)) {
                    relations++;
                } else {
                    skipped++;
                }
            } else if (atom.args().size() == 1) {
                if (sink.setAttribute(atom, fact)) {
                    attrs++;
                } else {
                    skipped++;
                }
            } else {
                skipped++; // nullary / n-ary (n>2) atoms have no relation/attribute representation
            }
        }
        return new MaterializationResult(relations, attrs, skipped);
    }

    /**
     * Materialize into a {@link MutableReasoningGraph} (convenience: a sink over the library's own
     * generic graph). Endpoints are auto-created if absent, without clobbering existing entities.
     */
    public static MaterializationResult materialize(Collection<InferredFact> facts, MutableReasoningGraph graph) {
        if (graph == null) {
            return new MaterializationResult(0, 0, 0);
        }
        return materialize(facts, new ReasoningGraphSink(graph));
    }

    /** Sink that writes inferences into a {@link MutableReasoningGraph}. */
    private static final class ReasoningGraphSink implements InferredGraphSink {

        private final MutableReasoningGraph graph;

        ReasoningGraphSink(MutableReasoningGraph graph) {
            this.graph = graph;
        }

        @Override
        public boolean addRelation(ParsedAtom atom, InferredFact fact) {
            String from = atom.args().get(0);
            String to = atom.args().get(1);
            // Ensure endpoints exist without clobbering richer entities the caller already added.
            if (graph.entity(from).isEmpty()) {
                graph.addEntity(from, "ENTITY", from);
            }
            if (graph.entity(to).isEmpty()) {
                graph.addEntity(to, "ENTITY", to);
            }
            GraphRelation relation = GraphRelation.builder(fact.runId() + "#" + fact.atomKey(), from, to)
                    .type(atom.predicate())
                    .weight(fact.value())
                    .confidence(fact.confidence())
                    .directed(true)
                    .tag(INFERRED_TAG)
                    .timestamp(fact.inferredAt())
                    .attribute("inferenceRunId", fact.runId())
                    .attribute("inferenceVersion", fact.version())
                    .attribute("supportingRuleIds", fact.supportingRuleIds())
                    .build();
            graph.addRelation(relation);
            return true;
        }

        @Override
        public boolean setAttribute(ParsedAtom atom, InferredFact fact) {
            String key = atom.args().get(0);
            String base = "inferred." + atom.predicate();
            GraphEntityBuilder b = GraphEntity.builder(key);
            // Merge onto the existing entity so we don't drop its other data on upsert.
            graph.entity(key).ifPresent(e -> b
                    .type(e.type())
                    .label(e.label())
                    .weight(e.weight())
                    .confidence(e.confidence())
                    .tags(e.tags())
                    .attributes(e.attributes()));
            b.tag(INFERRED_TAG);
            b.attribute(base, fact.value());
            b.attribute(base + ".confidence", fact.confidence());
            b.attribute(base + ".runId", fact.runId());
            graph.addEntity(b.build());
            return true;
        }
    }

    /**
     * Parse a canonical atom key {@code "Pred(arg1, arg2, ...)"} into a predicate and its ordered
     * arguments. Returns {@code null} when the input is not of that form (so callers can skip it).
     */
    public static ParsedAtom parseAtom(String atomKey) {
        if (atomKey == null) {
            return null;
        }
        int open = atomKey.indexOf('(');
        int close = atomKey.lastIndexOf(')');
        if (open <= 0 || close <= open) {
            return null; // needs the form "Pred(...)"
        }
        String predicate = atomKey.substring(0, open).trim();
        if (predicate.isEmpty()) {
            return null;
        }
        String argsStr = atomKey.substring(open + 1, close).trim();
        List<String> args = new ArrayList<>();
        if (!argsStr.isEmpty()) {
            for (String a : argsStr.split(",")) {
                String t = a.trim();
                if (!t.isEmpty()) {
                    args.add(t);
                }
            }
        }
        return new ParsedAtom(predicate, args);
    }

    /** A parsed atom: a predicate name and its ordered arguments. */
    public record ParsedAtom(String predicate, List<String> args) {
    }
}
