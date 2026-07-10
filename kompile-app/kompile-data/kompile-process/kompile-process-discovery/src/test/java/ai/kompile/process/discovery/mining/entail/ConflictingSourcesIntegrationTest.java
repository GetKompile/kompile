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

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Two crawl sources describe the SAME procurement process and DISAGREE:
 *
 * <ul>
 *   <li><b>finance-crawl</b> (4 threads): Purchase Request → Manager Approval (bob) → Invoice —
 *       approval comes first, always.</li>
 *   <li><b>ops-crawl</b> (3 threads): Purchase Request → Invoice → Manager Approval (carol) —
 *       the invoice arrives first and approval is retroactive.</li>
 * </ul>
 *
 * The threads INTERLEAVE in time (this is a live disagreement, not drift), each source is
 * internally consistent, and the owners conflict too. The pipeline must surface BOTH accounts
 * with source attribution, score the contradiction with the reasoning library's
 * {@code ProbabilisticContradictionDetector}/{@code Opinion} machinery, propose an
 * explicitly-GUESSED reconciliation — and only rewrite the KB (via {@code BeliefReviser}) when
 * the store actually holds both mutually-exclusive directions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConflictingSourcesIntegrationTest {

    private static final long FS = 91L;
    private static final LocalDateTime T0 = LocalDateTime.of(2025, 3, 3, 9, 0);

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

        actor("p-alice", "Alice Requester");
        actor("p-bob", "Bob Approver");
        actor("p-carol", "Carol Approver");
        // finance-crawl: approval BEFORE invoice; ops-crawl threads interleave between them.
        for (int i = 0; i < 4; i++) {
            thread("fin" + i, "finance-crawl", T0.plusWeeks(i), true, "p-bob");
        }
        for (int i = 0; i < 3; i++) {
            thread("ops" + i, "ops-crawl", T0.plusWeeks(i).plusDays(3), false, "p-carol");
        }
    }

    private void actor(String id, String title) {
        node(id, "PERSON", title, null, null);
    }

    private GraphNode node(String id, String type, String title, LocalDateTime at, String source) {
        GraphNode n = GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).title(title).occurredAt(at)
                .metadataJson("{\"entity_type\":\"" + type + "\""
                        + (source != null ? ",\"source\":\"" + source + "\"" : "") + "}")
                .build();
        nodes.add(n);
        byId.put(id, n);
        return n;
    }

    private void edge(String from, String to, String label) {
        edges.add(GraphEdge.builder()
                .edgeId(from + "::" + to + "::" + label)
                .sourceNodeId(from).targetNodeId(to)
                .relationType(label)
                .provenanceType(EdgeProvenance.EXTRACTED)
                .build());
    }

    /** One thread: Request → (approval → invoice | invoice → approval), stamped with its source. */
    private void thread(String key, String source, LocalDateTime at, boolean approvalFirst, String approverId) {
        String request = key + "-req";
        node(request, "PURCHASE_REQUEST", "Order " + key, at, source);
        edge(request, "p-alice", "SUBMITTED_BY");

        String approval = key + "-appr";
        String invoice = key + "-inv";
        if (approvalFirst) {
            node(approval, "MANAGER_APPROVAL", "Approval " + key, at.plusHours(2), source);
            node(invoice, "INVOICE", "Invoice " + key, at.plusDays(1), source);
            edge(request, approval, "LEADS_TO");
            edge(approval, invoice, "LEADS_TO");
        } else {
            node(invoice, "INVOICE", "Invoice " + key, at.plusHours(2), source);
            node(approval, "MANAGER_APPROVAL", "Approval " + key, at.plusDays(1), source);
            edge(request, invoice, "LEADS_TO");
            edge(invoice, approval, "LEADS_TO");
        }
        edge(approval, approverId, "APPROVED_BY");
    }

    @Test
    void conflictingSources_surfaceBothAccounts_andGuessAReconciliation(@TempDir Path storeDir) {
        service.setSuggestionStore(new ProcessSuggestionStore(storeDir.resolve("suggestions")));

        ProcessSuggestion suggestion = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(suggestion);

        // Both steps exist — neither account was silently dropped.
        List<String> stepNames = suggestion.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .map(ProcessSuggestion.SuggestedStep::getName).toList();
        assertTrue(stepNames.contains("Manager Approval") && stepNames.contains("Invoice"), String.valueOf(stepNames));

        // 1. The ORDERING conflict surfaces with BOTH sides and their sources.
        List<String> conflicts = evidence(suggestion, "CONFLICT");
        String orderConflict = conflicts.stream()
                .filter(c -> c.startsWith("Conflicting order:"))
                .findFirst().orElseThrow(() -> new AssertionError("no ordering conflict in " + conflicts));
        assertTrue(orderConflict.contains("'Manager Approval → Invoice' in 4 case(s)"), orderConflict);
        assertTrue(orderConflict.contains("'Invoice → Manager Approval' in 3 case(s)"), orderConflict);
        assertTrue(orderConflict.contains("finance-crawl: Manager Approval first ×4"), orderConflict);
        assertTrue(orderConflict.contains("ops-crawl: Invoice first ×3"), orderConflict);

        // 2. The OWNERSHIP conflict surfaces with source attribution — bob and carol both
        //    well-supported performers of the same step.
        String ownerConflict = conflicts.stream()
                .filter(c -> c.startsWith("Conflicting owner for 'Manager Approval'"))
                .findFirst().orElseThrow(() -> new AssertionError("no owner conflict in " + conflicts));
        assertTrue(ownerConflict.contains("Bob Approver (finance-crawl ×4)"), ownerConflict);
        assertTrue(ownerConflict.contains("Carol Approver (ops-crawl ×3)"), ownerConflict);

        // 3. Reconciliations are GUESSES with a basis and the library's fused-opinion residue —
        //    interleaved dates + internally-pure sources = the source-split situation.
        List<String> reconciliations = evidence(suggestion, "RECONCILIATION");
        String orderReconciliation = reconciliations.stream()
                .filter(r -> r.contains("source-specific variants"))
                .findFirst().orElseThrow(() -> new AssertionError("no order reconciliation in " + reconciliations));
        assertTrue(orderReconciliation.startsWith("GUESS:"), orderReconciliation);
        assertTrue(orderReconciliation.contains("'Manager Approval → Invoice' as canonical (majority 4 vs 3)"),
                orderReconciliation);
        assertTrue(orderReconciliation.contains("fused opinion across sources: E="),
                "the reconciliation strength is the library's cumulative-fused Opinion: " + orderReconciliation);
        assertTrue(orderReconciliation.contains("[basis: source-split]"), orderReconciliation);
        assertTrue(reconciliations.stream().anyMatch(r ->
                        r.contains("bind 'Manager Approval' to Bob Approver (4 vs 3 performer instances)")),
                "the owner guess names the loser as a possible delegate: " + reconciliations);

        // 4. The KB stays UNTOUCHED and honestly AGNOSTIC — a 4v3 contested ordering clears
        //    neither the assert threshold (forward) nor survives refutation (reverse), so NEITHER
        //    direction is confidently asserted, and a guess must never rewrite a consistent KB.
        assertTrue(reconciliations.stream().noneMatch(r -> r.startsWith("Applied")),
                "no BeliefReviser application on a consistent KB: " + reconciliations);
        assertTrue(kb.getState(FS).factStore()
                        .factFor("precedes(\"Manager Approval\", \"Invoice\")").isEmpty(),
                "a contested ordering is not confidently asserted forward…");
        assertTrue(kb.getState(FS).factStore()
                        .factFor("precedes(\"Invoice\", \"Manager Approval\")").isEmpty(),
                "…or backward — the KB abstains while the sources disagree");

        // 5. The narrative tells the operator, in prose, that the sources disagree and what the
        //    guess is.
        assertTrue(suggestion.getNarrative().contains("The sources DISAGREE"), suggestion.getNarrative());
        assertTrue(suggestion.getNarrative().contains("GUESS:"), suggestion.getNarrative());
    }

    @Test
    void whenTheKbActuallyHoldsBothDirections_theGuessedLoserIsRetractedViaBeliefReviser(
            @TempDir Path storeDir) {
        service.setSuggestionStore(new ProcessSuggestionStore(storeDir.resolve("suggestions")));

        // An earlier system (or a prior mine of the ops account alone) asserted the OPS ordering
        // as a fact. Mining the combined graph then promotes the FINANCE ordering — at which
        // point the KB holds a mutually-exclusive pair it cannot honestly keep.
        kb.assertFactsBatch(FS, List.of(new Fact(
                "precedes(\"Invoice\", \"Manager Approval\")", 0.8, "ops-legacy-import",
                Instant.now(), false)));

        ProcessSuggestion suggestion = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(suggestion);

        List<String> reconciliations = evidence(suggestion, "RECONCILIATION");
        assertTrue(reconciliations.stream().anyMatch(r -> r.startsWith("Applied (KB held")
                        && r.contains("retracted precedes(\"Invoice\", \"Manager Approval\")")
                        && r.contains("BeliefReviser")),
                "the TMS resolves the contested assertion and says so: " + reconciliations);
        assertTrue(kb.getState(FS).factStore()
                        .factFor("precedes(\"Invoice\", \"Manager Approval\")").isEmpty(),
                "the stale contested assertion was retracted — the KB abstains where the sources disagree");
    }

    private static List<String> evidence(ProcessSuggestion s, String type) {
        return s.getStructuredEvidence().stream()
                .filter(ev -> type.equals(ev.getType()))
                .map(ProcessSuggestion.StructuredEvidence::getDescription)
                .toList();
    }
}
