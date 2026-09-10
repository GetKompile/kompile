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

import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphPslProgramBuilderTest {

    @Test
    @DisplayName("build scans relations linearly and preserves legacy degree semantics")
    void buildScansRelationsLinearlyAndPreservesDegreeSemantics() {
        List<GraphEntity> entities = List.of(
                entity("a", 0.40),
                entity("b", 0.40),
                entity("c", 0.30),
                entity("d", 0.60));
        List<GraphRelation> relations = List.of(
                relation("r1", "a", "b", "CAUSES", 0.80, 1.0, true),
                relation("r2", "b", "c", "RELATED", 0.70, 1.0, false),
                relation("r3", "c", "d", "SAME_AS", 0.60, 1.0, true),
                relation("r4", "d", "a", "NOT_SAME_AS", 0.90, 1.0, true),
                relation("r4b", "a", "b", "NOT_SAME", 0.99, 1.0, true),
                relation("r4c", "b", "c", "DIFFERENT_FROM", 0.99, 1.0, true),
                relation("r5", "a", "c", "CAUSES", 0.10, 0.40, true),
                relation("r6", "missing", "b", "CAUSES", 0.90, 1.0, true),
                relation("r7", "missing", "d", "RELATED", 0.90, 1.0, false),
                relation("r8", "a", "a", "RELATED", 0.90, 1.0, false),
                relation("r9", "a", "b", "CAUSES", 0.90, 1.0, true),
                relation("r10", "a", "b", "CAUSES", 0.70, 1.0, true),
                relation("r11", "b", "d", "CONTRADICTS", 0.80, 0.50, true),
                relation("rNaN", "a", "d", "CAUSES", Double.NaN, 1.0, true));
        CountingGraph graph = new CountingGraph(entities, relations);

        GraphPslProgramBuilder builder = new GraphPslProgramBuilder()
                .includeDefaultRules(false);
        PslProgram program = builder.build(graph);

        // One degree pass plus the existing link/conflict pass: independent of entity count.
        assertEquals(relations.size() * 2, graph.relationIterationVisits());

        Map<String, GraphEntity> entitiesById = Map.of(
                "a", entities.get(0), "b", entities.get(1),
                "c", entities.get(2), "d", entities.get(3));
        for (Map.Entry<String, GraphEntity> entry : entitiesById.entrySet()) {
            String constant = builder.entityIdToConstant().get(entry.getKey());
            assertNotNull(constant);
            int legacyDegree = legacyEffectiveOutDegree(relations, entry.getKey(), 0.05);
            assertEquals(
                    NoisyOrCpt.estimatePrior(entry.getValue().confidence(), legacyDegree),
                    program.value("Prior(" + constant + ")"),
                    1e-12,
                    "prior for " + entry.getKey() + " must match the former per-entity scan");
        }

        String a = builder.entityIdToConstant().get("a");
        String b = builder.entityIdToConstant().get("b");
        String c = builder.entityIdToConstant().get("c");
        String d = builder.entityIdToConstant().get("d");
        assertEquals(0.90, program.value("Link(" + a + ", " + b + ")"), 1e-12,
                "duplicate links still merge by maximum strength");
        assertEquals(0.70, program.value("Link(" + c + ", " + b + ")"), 1e-12,
                "undirected relations still add the reverse link");
        assertEquals(0.60, program.value("Link(" + d + ", " + c + ")"), 1e-12,
                "symmetric typed relations still add the reverse link");
        assertEquals(0.90, program.value("Link(" + a + ", " + a + ")"), 1e-12,
                "self-loops remain a single observed atom");
        assertEquals(0.40, program.value("Conflict(" + b + ", " + d + ")"), 1e-12);
        assertFalse(program.contains("Link(" + d + ", " + a + ")"),
                "NOT_SAME_AS relations remain excluded");
        assertEquals(0.90, program.value("Link(" + a + ", " + b + ")"), 1e-12,
                "NOT_SAME relations remain excluded and preserve the stronger duplicate link");
        assertEquals(0.70, program.value("Link(" + b + ", " + c + ")"), 1e-12,
                "DIFFERENT_FROM relations remain excluded and preserve the undirected link");
        assertFalse(program.contains("Link(" + a + ", " + c + ")"),
                "sub-threshold relations remain excluded");
        assertFalse(program.contains("Link(" + a + ", " + d + ")"),
                "NaN edge strength clamps to zero and remains below the threshold");
        assertTrue(program.contains("Prior(" + a + ")"));
    }

    private static int legacyEffectiveOutDegree(Collection<GraphRelation> relations,
                                                 String entityId,
                                                 double minEdgeWeight) {
        int degree = 0;
        for (GraphRelation relation : relations) {
            if (GraphPslProgramBuilder.isIdentitySeparationType(relation.type())) {
                continue;
            }
            boolean incident = entityId.equals(relation.sourceId())
                    || ((!relation.directed()
                    || relation.type().trim().equalsIgnoreCase("SAME_AS")
                    || relation.type().trim().equalsIgnoreCase("EQUIVALENT_TO")
                    || relation.type().trim().equalsIgnoreCase("SIMILAR_TO")
                    || relation.type().trim().equalsIgnoreCase("COREFERS_TO")
                    || relation.type().trim().equalsIgnoreCase("SAME_ENTITY"))
                    && entityId.equals(relation.targetId()));
            double strength = Math.max(0.0, Math.min(1.0,
                    relation.weight() * relation.confidence()));
            if (incident && strength >= minEdgeWeight) {
                degree++;
            }
        }
        return degree;
    }

    private static GraphEntity entity(String id, double confidence) {
        return GraphEntity.builder(id).confidence(confidence).build();
    }

    private static GraphRelation relation(String id, String source, String target, String type,
                                          double weight, double confidence, boolean directed) {
        return GraphRelation.builder(id, source, target)
                .type(type)
                .weight(weight)
                .confidence(confidence)
                .directed(directed)
                .build();
    }

    private static final class CountingGraph implements ReasoningGraph {
        private final MutableReasoningGraph delegate = new MutableReasoningGraph();
        private int relationIterationVisits;

        private CountingGraph(List<GraphEntity> entities, List<GraphRelation> relations) {
            entities.forEach(delegate::addEntity);
            relations.forEach(delegate::addRelation);
        }

        private int relationIterationVisits() {
            return relationIterationVisits;
        }

        @Override
        public Collection<GraphEntity> entities() {
            return delegate.entities();
        }

        @Override
        public Collection<GraphRelation> relations() {
            Collection<GraphRelation> source = delegate.relations();
            return new AbstractCollection<>() {
                @Override
                public Iterator<GraphRelation> iterator() {
                    Iterator<GraphRelation> iterator = source.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return iterator.hasNext();
                        }

                        @Override
                        public GraphRelation next() {
                            relationIterationVisits++;
                            return iterator.next();
                        }
                    };
                }

                @Override
                public int size() {
                    return source.size();
                }
            };
        }

        @Override
        public java.util.Optional<GraphEntity> entity(String id) {
            return delegate.entity(id);
        }

        @Override
        public List<GraphRelation> outgoing(String entityId) {
            return delegate.outgoing(entityId);
        }

        @Override
        public List<GraphRelation> incoming(String entityId) {
            return delegate.incoming(entityId);
        }

        @Override
        public List<GraphRelation> relationsOf(String entityId) {
            return delegate.relationsOf(entityId);
        }
    }
}
