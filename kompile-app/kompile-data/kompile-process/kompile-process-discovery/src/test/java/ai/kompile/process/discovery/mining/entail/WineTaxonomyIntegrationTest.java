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

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A wine-shop process whose orders are about DIFFERENT CATEGORIES of wine — Chianti and Malbec
 * (reds), Riesling (white) — where the OWL machinery has already run: each wine node carries its
 * cax-sco closure in {@code owlInferredTypes} metadata ([Chianti, RedWine, Wine]), and the has-a
 * closure is materialized as HIERARCHICAL/INFERRED edges (wine partOf order). Nothing here is a
 * new access path — the mining pipeline consumes exactly what
 * {@code OwlReasoningService.materializeInferences} wrote and retrieval already reads.
 *
 * <p>Expected recovery, with no hints beyond the graph: ONE process about Wine (not three thin
 * subtype variants); red-vs-white routing REDISCOVERED as a mined guard from the {@code color}
 * attribute; {@code Occurs(Wine)}'s opinion accumulated across every subtype's traces; the
 * taxonomy queryable in the KB ({@code isa(?Category, "Wine")}); and the has-a relation surfaced
 * and promoted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WineTaxonomyIntegrationTest {

    private static final long FS = 95L;
    private static final LocalDateTime T0 = LocalDateTime.of(2025, 4, 7, 10, 0);

    @Mock
    private KnowledgeGraphService graph;

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, GraphNode> byId = new LinkedHashMap<>();

    private MiningProcessDiscoveryService service;
    private KbGroundingService kb;

    @BeforeEach
    void setUp() {
        when(graph.getNodesInFactSheet(FS)).thenAnswer(inv -> new ArrayList<>(nodes));
        when(graph.getEdgesInFactSheet(FS)).thenAnswer(inv -> new ArrayList<>(edges));
        when(graph.getNode(anyString())).thenAnswer(inv -> java.util.Optional.ofNullable(
                byId.get(inv.getArgument(0, String.class))));
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        kb = new KbGroundingService();
        service = new MiningProcessDiscoveryService(graph);
        service.setKbGroundingService(kb);

        // 7 orders: reds (2× Chianti, 2× Malbec) age in the cellar; whites (3× Riesling) go to
        // cold storage; every order ships. Wine categories only exist in the OWL closure.
        wineOrder("c0", T0, "CHIANTI", "[\"Chianti\",\"RedWine\",\"Wine\"]", "red", "Cellar Aging");
        wineOrder("c1", T0.plusDays(2), "CHIANTI", "[\"Chianti\",\"RedWine\",\"Wine\"]", "red", "Cellar Aging");
        wineOrder("m0", T0.plusDays(4), "MALBEC", "[\"Malbec\",\"RedWine\",\"Wine\"]", "red", "Cellar Aging");
        wineOrder("m1", T0.plusDays(6), "MALBEC", "[\"Malbec\",\"RedWine\",\"Wine\"]", "red", "Cellar Aging");
        wineOrder("r0", T0.plusDays(1), "RIESLING", "[\"Riesling\",\"WhiteWine\",\"Wine\"]", "white", "Cold Storage");
        wineOrder("r1", T0.plusDays(3), "RIESLING", "[\"Riesling\",\"WhiteWine\",\"Wine\"]", "white", "Cold Storage");
        wineOrder("r2", T0.plusDays(5), "RIESLING", "[\"Riesling\",\"WhiteWine\",\"Wine\"]", "white", "Cold Storage");
    }

    private GraphNode node(String id, String type, String title, LocalDateTime at, String extraJson) {
        GraphNode n = GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).title(title).occurredAt(at)
                .metadataJson("{\"entity_type\":\"" + type + "\""
                        + (extraJson != null ? "," + extraJson : "") + "}")
                .build();
        nodes.add(n);
        byId.put(id, n);
        return n;
    }

    private void edge(String from, String to, String label, EdgeType type, EdgeProvenance provenance) {
        edges.add(GraphEdge.builder()
                .edgeId(from + "::" + to + "::" + label)
                .sourceNodeId(from).targetNodeId(to)
                .relationType(label).edgeType(type)
                .provenanceType(provenance)
                .build());
    }

    private void wineOrder(String key, LocalDateTime at, String wineType, String closure,
                           String color, String handling) {
        String order = key + "-order";
        String wine = key + "-wine";
        String handle = key + "-handle";
        String ship = key + "-ship";
        node(order, "ORDER_PLACED", "Order " + key, at, null);
        node(wine, wineType, wineType.toLowerCase(Locale.ROOT) + " " + key, at.plusHours(1),
                "\"owlInferredTypes\":" + closure + ",\"color\":\"" + color + "\"");
        node(handle, handling.toUpperCase(Locale.ROOT).replace(' ', '_'), handling + " " + key,
                at.plusHours(2), null);
        node(ship, "SHIPMENT", "Shipment " + key, at.plusDays(1), null);
        edge(order, wine, "LEADS_TO", EdgeType.USER_DEFINED, EdgeProvenance.EXTRACTED);
        edge(wine, handle, "LEADS_TO", EdgeType.USER_DEFINED, EdgeProvenance.EXTRACTED);
        edge(handle, ship, "LEADS_TO", EdgeType.USER_DEFINED, EdgeProvenance.EXTRACTED);
        // The OWL enrichment materialized the has-a closure: the wine item is part of its order.
        edge(wine, order, "PART_OF", EdgeType.HIERARCHICAL, EdgeProvenance.INFERRED);
    }

    @Test
    void wineCategories_mineAsOneProcessAboutWine_withTaxonomyGuardsFactsAndHasA(
            @TempDir Path storeDir) {
        service.setSuggestionStore(new ProcessSuggestionStore(storeDir.resolve("suggestions")));

        ProcessSuggestion suggestion = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(suggestion);

        // 1. ONE process about Wine — the categories rolled up through the materialized closure
        //    (Chianti/Malbec → RedWine → Wine; Riesling → Wine; fixpoint = Wine).
        Map<String, ProcessSuggestion.SuggestedStep> steps = stepsByName(suggestion);
        assertTrue(steps.containsKey("Wine"), "the process is about the CONCEPT: " + steps.keySet());
        assertFalse(steps.containsKey("Chianti") || steps.containsKey("Malbec") || steps.containsKey("Riesling"),
                "subtypes are categories of the concept, not separate steps: " + steps.keySet());
        List<String> taxonomy = evidence(suggestion, "TAXONOMY");
        assertTrue(taxonomy.stream().anyMatch(t -> t.contains("→ 'Wine' (OWL is-a closure)")),
                "the roll-up is a visible decision: " + taxonomy);

        // 2. Category routing REDISCOVERED as mined guards — the taxonomy abstraction made the
        //    choice mineable at all. The cellar branch spans two categories, so only the color
        //    attribute separates it perfectly; the cold branch is single-category, where color
        //    and category tie as perfect separators and the deterministic (lexicographic)
        //    tie-break picks category — both are correct routing semantics.
        ProcessSuggestion.SuggestedStep cellar = steps.get("Cellar Aging");
        assertNotNull(cellar, String.valueOf(steps.keySet()));
        assertTrue(cellar.getConditionExpression().contains("#color == 'red'"),
                "red wines age in the cellar — mined, not configured: " + cellar.getConditionExpression());
        String coldGuard = steps.get("Cold Storage").getConditionExpression();
        assertTrue(coldGuard.contains("#category == 'Riesling'") || coldGuard.contains("#color == 'white'"),
                "whites go to cold storage, by category or color: " + coldGuard);

        // 3. Facts/opinions prompted from the graph accumulate ACROSS the subtypes: one
        //    Occurs(Wine) carrying every category's evidence instead of three weak facts.
        assertEquals(VerifyResult.Status.SUPPORTED, kb.verify(FS, "activity(\"Wine\")").status());
        Fact occursWine = kb.getState(FS).factStore().factFor("Occurs(Wine)").orElseThrow();
        assertEquals(1.0, occursWine.value(), 1e-9, "wine occurs in 7 of 7 cases");
        Opinion opinion = occursWine.opinion();
        assertNotNull(opinion);
        assertTrue(opinion.belief() > 0.7 && opinion.uncertainty() < 0.3,
                "the rolled-up Beta evidence (7 traces) leaves little uncertainty: " + opinion);

        // 4. The taxonomy itself is queryable knowledge: which categories does this process cover?
        assertEquals(VerifyResult.Status.SUPPORTED, kb.verify(FS, "isa(\"Chianti\", \"Wine\")").status());
        List<QueryBinding> categories = kb.query(FS, List.of(
                new ConjunctiveQueryEngine.AtomPattern("isa", List.of("?Category", "\"Wine\""))), 10);
        assertTrue(categories.size() >= 3,
                "Chianti, Malbec and Riesling all answer isa(?C, Wine): " + categories);

        // 5. has-a consumed from the materialized HIERARCHICAL/INFERRED closure edges: the wine
        //    item is part of its order — surfaced and promoted, never re-derived.
        assertTrue(taxonomy.stream().anyMatch(t ->
                        t.contains("'Wine' is part of 'Order Placed'") && t.contains("OWL has-a closure")),
                String.valueOf(taxonomy));
        assertEquals(VerifyResult.Status.SUPPORTED,
                kb.verify(FS, "partOf(\"Wine\", \"Order Placed\")").status());
    }

    private static Map<String, ProcessSuggestion.SuggestedStep> stepsByName(ProcessSuggestion s) {
        Map<String, ProcessSuggestion.SuggestedStep> byName = new LinkedHashMap<>();
        s.getPhases().forEach(p -> p.getSteps().forEach(step -> byName.putIfAbsent(step.getName(), step)));
        return byName;
    }

    private static List<String> evidence(ProcessSuggestion s, String type) {
        return s.getStructuredEvidence().stream()
                .filter(ev -> type.equals(ev.getType()))
                .map(ProcessSuggestion.StructuredEvidence::getDescription)
                .toList();
    }
}
