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
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
import ai.kompile.process.discovery.mining.convert.ProcessNarrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CUSTOM domain categories end to end: nothing in the mining/entailment stack may be hardcoded to
 * known vocabularies. Two workflows made of entirely custom entity types — insurance claims
 * (CLAIM_INTAKE → … → CLAIM_PAYOUT) and security incidents (SECURITY_ALERT → … →
 * POSTMORTEM_REPORT) — with deliberately inconsistent casing across instances (extractors differ),
 * must come out as two coherent, business-sounding suggestions whose narratives read like real
 * process descriptions and whose orderings are queryable KB knowledge.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomCategoryWorkflowIntegrationTest {

    private static final long FS = 88L;
    private static final LocalDateTime T0 = LocalDateTime.of(2025, 5, 5, 8, 0);

    private static final List<String> CLAIM_TYPES = List.of(
            "CLAIM_INTAKE", "COVERAGE_REVIEW", "ADJUSTER_ASSESSMENT", "SETTLEMENT_OFFER", "CLAIM_PAYOUT");
    private static final List<String> INCIDENT_TYPES = List.of(
            "SECURITY_ALERT", "INCIDENT_TRIAGE", "ROOT_CAUSE_ANALYSIS", "REMEDIATION", "POSTMORTEM_REPORT");

    @Mock
    private KnowledgeGraphService graph;

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, GraphNode> byId = new LinkedHashMap<>();

    private MiningProcessDiscoveryService service;
    private KbGroundingService kb;

    private GraphNode entity(String id, String entityType, LocalDateTime at) {
        GraphNode node = GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).title(id)
                .occurredAt(at)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build();
        nodes.add(node);
        byId.put(id, node);
        return node;
    }

    /** One case: the given custom types chained in order, hours apart, as one component. */
    private void workflowCase(String casePrefix, List<String> types, LocalDateTime start, boolean lowercase) {
        GraphNode previous = null;
        for (int i = 0; i < types.size(); i++) {
            // Inconsistent casing across instances: extractors disagree, activities must still merge.
            String type = lowercase ? types.get(i).toLowerCase() : types.get(i);
            GraphNode current = entity(casePrefix + "-" + i, type, start.plusHours(3L * i));
            if (previous != null) {
                edges.add(GraphEdge.builder()
                        .edgeId(previous.getNodeId() + "->" + current.getNodeId())
                        .sourceNodeId(previous.getNodeId()).targetNodeId(current.getNodeId())
                        .sourceNode(previous).targetNode(current)
                        .relationType("FOLLOWED_BY")
                        .provenanceType(EdgeProvenance.EXTRACTED)
                        .build());
            }
            previous = current;
        }
    }

    @BeforeEach
    void buildCustomDomainCrawl() {
        for (int k = 0; k < 4; k++) {
            workflowCase("claim-" + k, CLAIM_TYPES, T0.plusDays(3L * k), k % 2 == 1);
        }
        for (int k = 0; k < 3; k++) {
            workflowCase("sec-" + k, INCIDENT_TYPES, T0.plusDays(1 + 4L * k), k % 2 == 1);
        }

        when(graph.getNodesInFactSheet(FS)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(FS)).thenReturn(edges);
        when(graph.getNode(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0, String.class))));
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        kb = new KbGroundingService();
        service = new MiningProcessDiscoveryService(graph);
        service.setKbGroundingService(kb);
    }

    @Test
    void customCategories_yieldCoherentNamedNarratedProcesses(@TempDir Path dataDir) {
        ProcessSuggestionStore store = new ProcessSuggestionStore(dataDir.resolve("suggestions"));
        service.setSuggestionStore(store);

        ProcessSuggestion best = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(best);

        List<ProcessSuggestion> stored = store.listByFactSheet(FS);
        assertEquals(2, stored.size(), "two custom domains = two processes, got "
                + stored.stream().map(ProcessSuggestion::getName).toList());

        // 1. Business-sounding names derived from the flow's endpoints — no "Mined process (fact
        //    sheet N)" placeholders, regardless of vocabulary.
        ProcessSuggestion claims = byName(stored, "Claim Intake → Claim Payout process");
        ProcessSuggestion incidents = byName(stored, "Security Alert → Postmortem Report process");

        // 2. Case-variant custom types merged: exactly the five Title-Cased steps each, in order.
        assertEquals(List.of("Claim Intake", "Coverage Review", "Adjuster Assessment",
                        "Settlement Offer", "Claim Payout"),
                ProcessNarrator.orderedStepNames(claims),
                "custom claim categories must merge across casings and keep mined order");
        assertEquals(List.of("Security Alert", "Incident Triage", "Root Cause Analysis",
                        "Remediation", "Postmortem Report"),
                ProcessNarrator.orderedStepNames(incidents));
        assertTrue(claims.getDescription().contains("4 case(s)"), claims.getDescription());

        // 3. Every suggestion ships a coherent narrative (template source until an LLM upgrades
        //    it) that mentions every step and the discovery stats.
        for (ProcessSuggestion s : stored) {
            assertEquals("TEMPLATE", s.getNarrativeSource());
            String narrative = s.getNarrative();
            assertNotNull(narrative);
            for (String step : ProcessNarrator.orderedStepNames(s)) {
                assertTrue(narrative.contains(step),
                        "narrative must mention '" + step + "': " + narrative);
            }
            assertTrue(narrative.contains("confidence"), narrative);
        }

        // 4. Custom-vocabulary orderings become queryable knowledge: intake→payout is never a
        //    directly-follows arc (three steps interleave) — entailed, then KB-supported.
        assertEquals(VerifyResult.Status.SUPPORTED,
                kb.verify(FS, "precedes(\"Claim Intake\", \"Claim Payout\")").status());
        assertEquals(VerifyResult.Status.SUPPORTED,
                kb.verify(FS, "activity(\"Root Cause Analysis\")").status());
        assertTrue(kb.getState(FS).factStore().factFor("Occurs(Settlement Offer)").isPresent());

        // 5. Control flow survives into dependencies with custom names.
        Set<String> payoutDeps = new LinkedHashSet<>();
        claims.getPhases().forEach(p -> p.getSteps().forEach(step -> {
            if ("Claim Payout".equals(step.getName()) && step.getDependsOn() != null) {
                payoutDeps.addAll(step.getDependsOn());
            }
        }));
        assertTrue(payoutDeps.contains("Settlement Offer"),
                "the payout step must wait for the offer: " + payoutDeps);
    }

    private static ProcessSuggestion byName(List<ProcessSuggestion> suggestions, String namePrefix) {
        return suggestions.stream()
                .filter(s -> s.getName() != null && s.getName().startsWith(namePrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no suggestion named '" + namePrefix + "…'; got "
                        + suggestions.stream().map(ProcessSuggestion::getName).toList()));
    }
}
