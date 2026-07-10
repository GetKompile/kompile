/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.mining.declare.DeclareMiner;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the control-flow write-back: instance-level {@code DIRECTLY_FOLLOWS} edges for observed
 * order, activity-level {@code PRECEDES} edges only for entailed-but-unobserved pairs, directional
 * metadata (the store would default TEMPORAL edges to bidirectional), and spec-level dedup.
 */
@ExtendWith(MockitoExtension.class)
class PrecedenceMaterializerTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 3, 1, 9, 0);

    @Mock
    private KnowledgeGraphService graph;

    private static EventLog sequenceLog(int n) {
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String c = "case-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Approve", BASE.plusHours(i), "a" + i),
                    Event.of(c, "Notify", BASE.plusHours(i).plusMinutes(5), "b" + i),
                    Event.of(c, "Close", BASE.plusHours(i).plusMinutes(10), "c" + i))));
        }
        return new EventLog(traces);
    }

    @Test
    void materializesObservedDfAndEntailedPrecedes_directionalWithProvenance() {
        EventLog log = sequenceLog(3);
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);
        ProcessEntailmentResult entailment =
                ProcessEntailment.entail(log, dfg, DeclareMiner.mine(log, 0.2, 0.66));
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        PrecedenceMaterializer.Result result = PrecedenceMaterializer.materialize(
                graph, 42L, "mined-test", log, dfg, entailment, 0.6);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(graph).createEdgesBatch(captor.capture());
        List<KnowledgeGraphService.EdgeSpec> specs = captor.getValue();

        // Observed instance-level flow: each trace contributes a->b and b->c between ITS nodes.
        long dfSpecs = specs.stream().filter(s -> "DIRECTLY_FOLLOWS".equals(s.label())).count();
        assertEquals(6, dfSpecs, "3 traces × 2 consecutive pairs, all distinct node pairs");
        assertEquals(result.directlyFollowsSpecs(), dfSpecs);

        // Entailed-but-unobserved Approve→Close becomes ONE activity-level PRECEDES edge between
        // the representative (earliest) nodes; observed pairs must NOT get PRECEDES edges.
        List<KnowledgeGraphService.EdgeSpec> precedes = specs.stream()
                .filter(s -> "PRECEDES".equals(s.label())).toList();
        assertEquals(result.precedesSpecs(), precedes.size());
        assertTrue(precedes.stream().anyMatch(s ->
                        s.sourceNodeId().equals("a0") && s.targetNodeId().equals("c0")),
                "entailed Approve→Close must link the earliest representative nodes");
        assertTrue(precedes.stream().noneMatch(s ->
                        s.sourceNodeId().startsWith("a") && s.targetNodeId().startsWith("b")),
                "observed Approve→Notify must not be duplicated as PRECEDES");

        // Every spec is a directional TEMPORAL edge with INFERRED provenance and full metadata.
        for (KnowledgeGraphService.EdgeSpec spec : specs) {
            assertEquals(EdgeType.TEMPORAL, spec.edgeType());
            assertEquals(EdgeProvenance.INFERRED, spec.provenance());
            assertEquals(42L, spec.factSheetId());
            assertTrue(spec.metaJson().contains("\"provenanceType\":\"INFERRED\""));
            assertTrue(spec.metaJson().contains("\"bidirectional\":false"),
                    "control flow must be directional — TEMPORAL edges default bidirectional otherwise");
            assertTrue(spec.metaJson().contains("\"suggestionId\":\"mined-test\""));
        }
        assertTrue(result.created() > 0);
    }

    @Test
    void observedRoles_materializeAsPerformedByEdges_toTheActorNode() {
        EventLog log = sequenceLog(2);
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        java.util.Map<String, ai.kompile.process.discovery.mining.extract.ActorResourceObservations.ObservedRole> roles =
                java.util.Map.of(
                        "Approve", new ai.kompile.process.discovery.mining.extract.ActorResourceObservations.ObservedRole(
                                "bob", "bob", "PERSON", "p-bob", 2, 2, 2),
                        // No graph node id (KB-derived binding) — must be skipped, never NPE.
                        "Notify", new ai.kompile.process.discovery.mining.extract.ActorResourceObservations.ObservedRole(
                                "NOTIFIER", "NOTIFIER", null, 0, 1, 2),
                        // Activity outside this cluster's log — no representative node, skipped.
                        "Foreign Step", new ai.kompile.process.discovery.mining.extract.ActorResourceObservations.ObservedRole(
                                "carol", "carol", "PERSON", "p-carol", 3, 3, 3));

        PrecedenceMaterializer.Result result = PrecedenceMaterializer.materialize(
                graph, 42L, "mined-test", log, dfg, ProcessEntailmentResult.empty(), 0.6, roles);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(graph).createEdgesBatch(captor.capture());
        List<KnowledgeGraphService.EdgeSpec> performedBy = captor.getValue().stream()
                .filter(s -> PrecedenceMaterializer.PERFORMED_BY.equals(s.label())).toList();

        assertEquals(1, performedBy.size(), "only the role with a node id AND a representative activity");
        assertEquals(result.performedBySpecs(), performedBy.size());
        KnowledgeGraphService.EdgeSpec spec = performedBy.get(0);
        assertEquals("a0", spec.sourceNodeId(), "representative (earliest) Approve node");
        assertEquals("p-bob", spec.targetNodeId(), "the observed actor's node");
        assertEquals(EdgeType.USER_DEFINED, spec.edgeType(), "actor relations are not control flow");
        assertEquals(EdgeProvenance.INFERRED, spec.provenance(),
                "INFERRED keeps materialized performers out of the next mine's casing and tally");
        assertTrue(spec.metaJson().contains("\"basis\":\"observed-actor\""));
        assertTrue(spec.metaJson().contains("\"bidirectional\":false"));
    }

    @Test
    void emptyLog_writesNothing() {
        EventLog empty = new EventLog(List.of());
        PrecedenceMaterializer.Result result = PrecedenceMaterializer.materialize(
                graph, 1L, "s", empty, DfgBuilder.build(empty),
                ProcessEntailmentResult.empty(), 0.7);
        assertEquals(0, result.created());
        verify(graph, never()).createEdgesBatch(anyList());
    }

    @Test
    void duplicateConsecutivePairs_dedupToOneSpec() {
        // Two traces over the SAME nodes → the a->b pair must be materialized once.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String c = "case-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Approve", BASE.plusDays(i), "shared-a"),
                    Event.of(c, "Notify", BASE.plusDays(i).plusMinutes(5), "shared-b"))));
        }
        EventLog log = new EventLog(traces);
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        PrecedenceMaterializer.Result result = PrecedenceMaterializer.materialize(
                graph, 7L, "s2", log, dfg, ProcessEntailmentResult.empty(), 0.7);

        assertEquals(1, result.directlyFollowsSpecs(), "same node pair must dedup to one spec");
        assertFalse(result.created() > 1);
    }
}
