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

import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.ProcessDiscoveryServiceImpl;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end wiring of the entailment pass through {@code discoverForFactSheet}: entailed evidence
 * and fused confidence on the suggestion, entailed step dependencies that survive acceptance as
 * executor-enforced ids, {@code precedes(...)} facts queryable in the KB after creation, and
 * control-flow edges written to the graph.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MiningEntailmentWiringTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 5, 1, 8, 0);

    @Mock
    private KnowledgeGraphService graph;

    private MiningProcessDiscoveryService service;
    private KbGroundingService kb;

    @BeforeEach
    void setUp() {
        // 5 traces of Approve→Notify→Close as disconnected components (one case each).
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            GraphNode approve = node("a" + i, "Approve", BASE.plusHours(i));
            GraphNode notify = node("b" + i, "Notify", BASE.plusHours(i).plusMinutes(5));
            GraphNode close = node("c" + i, "Close", BASE.plusHours(i).plusMinutes(10));
            nodes.addAll(List.of(approve, notify, close));
            edges.add(edge("e-a" + i, approve, notify));
            edges.add(edge("e-b" + i, notify, close));
        }
        when(graph.getNodesInFactSheet(42L)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(42L)).thenReturn(edges);
        when(graph.getNode(anyString())).thenReturn(Optional.empty());
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        kb = new KbGroundingService();
        service = new MiningProcessDiscoveryService(graph);
        service.setKbGroundingService(kb);
    }

    @Test
    void discover_entailsAssertsMaterializesAndFuses() {
        ProcessSuggestion suggestion = service.discoverForFactSheet(42L, 0.0, null);
        assertNotNull(suggestion);

        // 1. Entailed evidence + fused confidence breakdown are on the suggestion.
        List<String> evidenceTypes = suggestion.getStructuredEvidence().stream()
                .map(ProcessSuggestion.StructuredEvidence::getType).toList();
        assertTrue(evidenceTypes.contains("ENTAILED"),
                "entailed-only orderings must surface as ENTAILED evidence, got " + evidenceTypes);
        assertTrue(evidenceTypes.contains("FUSION"),
                "the modality fusion breakdown must be attached, got " + evidenceTypes);
        assertTrue(evidenceTypes.contains("HYBRID"),
                "the HybridReasoner activation consensus must be attached, got " + evidenceTypes);
        assertTrue(suggestion.getConfidence() > 0.15,
                "fused confidence must lift above the all-UNKNOWN grounding floor, got "
                        + suggestion.getConfidence());

        // 2. Step dependencies: tree sequence + entailment, by name at suggestion level.
        Map<String, ProcessSuggestion.SuggestedStep> steps = stepsByName(suggestion);
        assertTrue(steps.get("Notify").getDependsOn().contains("Approve"),
                "tree sequence must wire Notify after Approve");
        assertTrue(steps.get("Close").getDependsOn().contains("Notify"),
                "tree sequence must wire Close after Notify");

        // 3. The process is now queryable knowledge: the KB supports the entailed ordering.
        VerifyResult verdict = kb.verify(42L, "precedes(\"Approve\", \"Close\")");
        assertEquals(VerifyResult.Status.SUPPORTED, verdict.status(),
                "after creation the KB must support precedes(Approve, Close)");
        assertTrue(verdict.confidence() > 0.5);

        // 3b. Fact promotion: the miner's derived atoms are KB observations now — hard
        // activity(...) existence facts verify SUPPORTED (the per-step grounding used them during
        // conversion), and the UNQUOTED Occurs(...) facts sit in the fact store where grounding
        // cascades unify them with the persisted mined rules.
        assertEquals(VerifyResult.Status.SUPPORTED, kb.verify(42L, "activity(\"Approve\")").status(),
                "promoted activity existence facts must verify SUPPORTED");
        assertTrue(kb.getState(42L).factStore().factFor("Occurs(Approve)").isPresent(),
                "unquoted Occurs atom must be promoted for cascade-rule unification");
        assertTrue(kb.getState(42L).factStore().factFor("precedes(\"Approve\", \"Close\")").isPresent(),
                "entailed precedence must be promoted as a cascade observation");
        assertTrue(suggestion.getGroundedSteps() != null && !suggestion.getGroundedSteps().isEmpty()
                        && suggestion.getGroundedSteps().stream().allMatch(g -> g.isVerified()),
                "with promoted facts, every grounded step must be VERIFIED rather than UNKNOWN");

        // 4. Control flow was materialized through the batch edge API.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(graph).createEdgesBatch(captor.capture());
        assertTrue(captor.getValue().stream()
                        .anyMatch(s -> PrecedenceMaterializer.DIRECTLY_FOLLOWS.equals(s.label())),
                "observed control flow must be written as DIRECTLY_FOLLOWS edges");

        // 5. Acceptance translates dependsOn names to the executor-enforced step ids.
        ProcessDiscoveryServiceImpl acceptService = new ProcessDiscoveryServiceImpl(graph);
        ProcessDefinition definition = acceptService.acceptSuggestion(suggestion);
        ProcessStep closeStep = definition.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .filter(s -> "Close".equals(s.getName()))
                .findFirst().orElseThrow();
        assertNotNull(closeStep.getDependsOn(), "accepted Close step must keep its dependencies");
        ProcessStep notifyStep = definition.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .filter(s -> "Notify".equals(s.getName()))
                .findFirst().orElseThrow();
        assertTrue(closeStep.getDependsOn().contains(notifyStep.getId()),
                "dependsOn must be translated from step names to step ids: "
                        + closeStep.getDependsOn() + " should contain " + notifyStep.getId());
    }

    @Test
    void repeatedDiscovery_linksGenerationsAndMarksSupersede_neverDeletes(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path storeDir) {
        ai.kompile.process.discovery.ProcessSuggestionStore store =
                new ai.kompile.process.discovery.ProcessSuggestionStore(storeDir);
        service.setSuggestionStore(store);

        ProcessSuggestion first = service.discoverForFactSheet(42L, 0.0, null);
        ProcessSuggestion second = service.discoverForFactSheet(42L, 0.0, null);

        // Same process at two times = ONE lineage: the key carries forward, the generations link,
        // and the predecessor is MARKED superseded — never deleted.
        List<ProcessSuggestion> all = store.listByFactSheet(42L);
        assertEquals(2, all.size(), "both generations must stay stored (mark, never delete)");
        assertEquals(first.getId(), second.getProcessKey(),
                "identity minted on first sighting must carry to the re-mine");
        assertEquals(first.getId(), second.getPreviousSuggestionId());
        ProcessSuggestion storedFirst = store.get(first.getId()).orElseThrow();
        assertNotNull(storedFirst.getSupersededAt(), "the predecessor stops being the head");
        assertEquals(second.getId(), storedFirst.getSupersededBySuggestionId());
        assertTrue(second.getSupersededAt() == null, "the fresh generation is the head");

        // Identical log ⇒ the drift verdict is STABILITY, stated explicitly.
        assertTrue(second.getStructuredEvidence().stream().anyMatch(ev ->
                        "DRIFT".equals(ev.getType()) && ev.getDescription().contains("Stable since")),
                "an unchanged process must say so rather than stay silent");
    }

    @Test
    void reMiningChangedProcess_reportsDrift_onTheSameProcessKey(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path storeDir) {
        ai.kompile.process.discovery.ProcessSuggestionStore store =
                new ai.kompile.process.discovery.ProcessSuggestionStore(storeDir);
        service.setSuggestionStore(store);

        ProcessSuggestion first = service.discoverForFactSheet(42L, 0.0, null);

        // The process changes: a Manager Review step appears between Notify and Close in every case.
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            GraphNode approve = node("a" + i, "Approve", BASE.plusHours(i));
            GraphNode notify = node("b" + i, "Notify", BASE.plusHours(i).plusMinutes(5));
            GraphNode review = node("r" + i, "Manager Review", BASE.plusHours(i).plusMinutes(8));
            GraphNode close = node("c" + i, "Close", BASE.plusHours(i).plusMinutes(10));
            nodes.addAll(List.of(approve, notify, review, close));
            edges.add(edge("e-a" + i, approve, notify));
            edges.add(edge("e-r" + i, notify, review));
            edges.add(edge("e-b" + i, review, close));
        }
        when(graph.getNodesInFactSheet(42L)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(42L)).thenReturn(edges);

        ProcessSuggestion second = service.discoverForFactSheet(42L, 0.0, null);

        assertEquals(first.getId(), second.getProcessKey(),
                "3-of-4 shared activities clears the identity threshold — same process");
        List<String> drift = second.getStructuredEvidence().stream()
                .filter(ev -> "DRIFT".equals(ev.getType()))
                .map(ProcessSuggestion.StructuredEvidence::getDescription).toList();
        assertTrue(drift.stream().anyMatch(d -> d.contains("step added: 'Manager Review'")),
                "the new step must be reported as drift, got " + drift);
        assertTrue(drift.stream().anyMatch(d -> d.contains("'Close' now waits for")),
                "the control-flow rewire must be reported, got " + drift);
        assertTrue(second.getNarrative() != null && second.getNarrative().contains("Changes since"),
                "the narrative must mention the changes: " + second.getNarrative());
    }

    @Test
    void disjointWorkflows_yieldSeparateCoherentSuggestions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path storeDir) {
        // Extend the fixture with a second, disjoint workflow: Interview→Offer (3 traces).
        List<GraphNode> nodes = new ArrayList<>(graph.getNodesInFactSheet(42L));
        List<GraphEdge> edges = new ArrayList<>(graph.getEdgesInFactSheet(42L));
        for (int i = 0; i < 3; i++) {
            GraphNode interview = node("h" + i, "Interview", BASE.plusDays(1 + i));
            GraphNode offer = node("o" + i, "Offer", BASE.plusDays(1 + i).plusHours(2));
            nodes.addAll(List.of(interview, offer));
            edges.add(edge("e-h" + i, interview, offer));
        }
        when(graph.getNodesInFactSheet(42L)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(42L)).thenReturn(edges);

        ai.kompile.process.discovery.ProcessSuggestionStore store =
                new ai.kompile.process.discovery.ProcessSuggestionStore(storeDir);
        service.setSuggestionStore(store);

        ProcessSuggestion best = service.discoverForFactSheet(42L, 0.0, null);
        assertNotNull(best);

        List<ProcessSuggestion> stored = store.listByFactSheet(42L);
        assertEquals(2, stored.size(),
                "two disjoint workflows must become two suggestions, not one mega-process");
        for (ProcessSuggestion s : stored) {
            java.util.Set<String> stepNames = new java.util.LinkedHashSet<>();
            s.getPhases().forEach(p -> p.getSteps().forEach(step -> stepNames.add(step.getName())));
            boolean invoicing = stepNames.contains("Approve");
            boolean hiring = stepNames.contains("Interview");
            assertTrue(invoicing ^ hiring,
                    "a cluster's suggestion must not mix workflows, got steps: " + stepNames);
        }
    }

    @Test
    void acceptDismissHistory_trainsRanker_thatScoresFreshMining(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dataDir) {
        ai.kompile.process.discovery.mining.ProcessCalibrationService calibration =
                new ai.kompile.process.discovery.mining.ProcessCalibrationService();
        org.springframework.test.util.ReflectionTestUtils.setField(
                calibration, "dataDir", dataDir.toString());
        service.setCalibrationService(calibration);
        service.setSuggestionStore(new ai.kompile.process.discovery.ProcessSuggestionStore(
                dataDir.resolve("suggestions")));

        ProcessSuggestion first = service.discoverForFactSheet(42L, 0.0, null);
        assertNotNull(first);
        assertTrue(first.getLearnedScore() == null,
                "no outcomes yet — confidence stands alone");

        // Teach: the real mined profile is what gets accepted; weak profiles get dismissed.
        for (int i = 0; i < 3; i++) {
            calibration.recordOutcome(first, true);
            ProcessSuggestion weak = ProcessSuggestion.builder()
                    .id("weak-" + i).discoverySource("PROCESS_MINING")
                    .rawConformanceScore(0.05).confidence(0.05).build();
            calibration.recordOutcome(weak, false);
        }

        ProcessSuggestion second = service.discoverForFactSheet(42L, 0.0, null);
        assertNotNull(second.getLearnedScore(),
                "with a trained ranker, fresh mining carries a learned score");
        assertTrue(second.getLearnedScore() > 0.5,
                "the fresh suggestion matches the accepted profile: " + second.getLearnedScore());
        assertTrue(second.getStructuredEvidence().stream()
                        .anyMatch(ev -> "LEARNED".equals(ev.getType())),
                "the learned score surfaces as evidence");
    }

    @Test
    void discoverAll_minesEveryFactSheetWithAGraph() {
        when(graph.getFactSheetIdsWithGraphs()).thenReturn(List.of(42L));
        Map<Long, String> discovered = service.discoverAll(0.0, null);
        assertEquals(1, discovered.size());
        assertTrue(discovered.containsKey(42L), "fact sheet 42 must have been mined");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Map<String, ProcessSuggestion.SuggestedStep> stepsByName(ProcessSuggestion s) {
        Map<String, ProcessSuggestion.SuggestedStep> byName = new java.util.LinkedHashMap<>();
        s.getPhases().forEach(p -> p.getSteps().forEach(step -> byName.putIfAbsent(step.getName(), step)));
        return byName;
    }

    private static GraphNode node(String id, String entityType, LocalDateTime time) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .occurredAt(time)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build();
    }

    private static GraphEdge edge(String edgeId, GraphNode src, GraphNode tgt) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNodeId(src.getNodeId())
                .targetNodeId(tgt.getNodeId())
                .sourceNode(src)
                .targetNode(tgt)
                .build();
    }
}
