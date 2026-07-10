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
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.ProcessDiscoveryServiceImpl;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
import ai.kompile.process.service.ProcessEngineServiceImpl;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The full "same process, changed over time" story on one crawl-shaped graph — a procurement
 * process for an inventory item ("Widget") that DRIFTS between two eras:
 *
 * <ul>
 *   <li><b>Era 1</b> (Jan, 6 orders): unit price $80 — most orders are small (amount ≤ 450) and
 *       take the AUTO APPROVAL branch (guard: amount); bob approves everything; Acme Supply
 *       invoices.</li>
 *   <li><b>Era 2</b> (Jul, 7 orders): the SUPPLIER CHANGED (NuParts) and the unit price TRIPLED
 *       ($240) — every order now exceeds the auto-approval band, a new PRICE REVIEW step guards
 *       spend, carol took over approvals.</li>
 * </ul>
 *
 * What the machinery must recover, with no hints beyond the graph: the mined guard on the price
 * attribute; the ownership change (bob → carol) and vendor change (Acme Supply → NuParts) as
 * performer drift; the abandoned auto-approval branch and the new review step both as
 * cross-generation drift AND as a within-log change point dated to the price change; one stable
 * {@code processKey} across the re-mine; and accept-as-revision bumping the SAME definition.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvolvingProcurementIntegrationTest {

    private static final long FS = 88L;
    private static final LocalDateTime ERA1 = LocalDateTime.of(2025, 1, 6, 9, 0);
    private static final LocalDateTime ERA2 = LocalDateTime.of(2025, 7, 7, 9, 0);

    @Mock
    private KnowledgeGraphService graph;

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, GraphNode> byId = new LinkedHashMap<>();

    private MiningProcessDiscoveryService service;
    private KbGroundingService kb;
    private ProcessSuggestionStore store;

    private String originalUserHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        when(graph.getNodesInFactSheet(FS)).thenAnswer(inv -> new ArrayList<>(nodes));
        when(graph.getEdgesInFactSheet(FS)).thenAnswer(inv -> new ArrayList<>(edges));
        when(graph.getNode(anyString())).thenAnswer(inv -> java.util.Optional.ofNullable(
                byId.get(inv.getArgument(0, String.class))));
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        kb = new KbGroundingService();
        service = new MiningProcessDiscoveryService(graph);
        service.setKbGroundingService(kb);

        buildEra1();
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalUserHome);
    }

    // ── crawl-shaped fixture builders ────────────────────────────────────────────

    private GraphNode entity(String id, String type, String title, LocalDateTime at, String extraJson) {
        GraphNode node = GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).title(title).occurredAt(at)
                .metadataJson("{\"entity_type\":\"" + type + "\""
                        + (extraJson != null ? "," + extraJson : "") + "}")
                .build();
        nodes.add(node);
        byId.put(id, node);
        return node;
    }

    private void edge(String from, String to, String label) {
        edges.add(GraphEdge.builder()
                .edgeId(from + "::" + to + "::" + label)
                .sourceNodeId(from).targetNodeId(to)
                .relationType(label)
                .provenanceType(EdgeProvenance.EXTRACTED)
                .build());
    }

    private void actor(String id, String title) {
        if (!byId.containsKey(id)) {
            entity(id, "PERSON", title, null, null);
        }
    }

    /**
     * One procurement thread: Purchase Request (amount attribute) → [Price Review →]
     * Auto/Manager Approval → Invoice, with {@code *_BY} performer edges. Chain edges keep each
     * order its own case; shared people would otherwise merge everything.
     */
    private void order(String key, LocalDateTime at, int amount, int unitPrice,
                       boolean autoApproved, boolean priceReviewed,
                       String approverId, String vendorId) {
        String request = key + "-req";
        entity(request, "PURCHASE_REQUEST", "Widget order " + key, at,
                String.format(Locale.ROOT, "\"amount\":%d,\"unitPrice\":%d,\"item\":\"Widget\"",
                        amount, unitPrice));
        edge(request, "p-alice", "SUBMITTED_BY");

        String previous = request;
        if (priceReviewed) {
            String review = key + "-rev";
            entity(review, "PRICE_REVIEW", "Price review " + key, at.plusHours(1), null);
            edge(previous, review, "LEADS_TO");
            edge(review, approverId, "REVIEWED_BY");
            previous = review;
        }

        String approval = key + "-appr";
        entity(approval, autoApproved ? "AUTO_APPROVAL" : "MANAGER_APPROVAL",
                (autoApproved ? "Auto approval " : "Manager approval ") + key, at.plusHours(2), null);
        edge(previous, approval, "LEADS_TO");
        edge(approval, approverId, "APPROVED_BY");

        String invoice = key + "-inv";
        entity(invoice, "INVOICE", "Invoice " + key, at.plusDays(1),
                String.format(Locale.ROOT, "\"amount\":%d", amount));
        edge(approval, invoice, "LEADS_TO");
        edge(invoice, vendorId, "ISSUED_BY");
    }

    /** Era 1: cheap widgets, bob approves, Acme Supply invoices; 4 auto + 2 manager orders. */
    private void buildEra1() {
        actor("p-alice", "Alice Requester");
        actor("p-bob", "Bob Approver");
        actor("v-acme", "Acme Supply Sales");
        int[] autoAmounts = {300, 320, 380, 450};
        for (int i = 0; i < autoAmounts.length; i++) {
            order("e1a" + i, ERA1.plusWeeks(i), autoAmounts[i], 80, true, false, "p-bob", "v-acme");
        }
        int[] managerAmounts = {800, 900};
        for (int i = 0; i < managerAmounts.length; i++) {
            order("e1m" + i, ERA1.plusWeeks(4 + i), managerAmounts[i], 80, false, false, "p-bob", "v-acme");
        }
    }

    /** Era 2: the price tripled, the vendor changed, carol owns approvals, every order is reviewed. */
    private void buildEra2() {
        actor("p-carol", "Carol Approver");
        actor("v-nuparts", "NuParts Billing");
        int[] amounts = {900, 960, 1050, 1140, 1260, 1350, 1500};
        for (int i = 0; i < amounts.length; i++) {
            order("e2m" + i, ERA2.plusWeeks(i), amounts[i], 240, false, true, "p-carol", "v-nuparts");
        }
    }

    // ── the story, end to end ────────────────────────────────────────────────────

    @Test
    void twoGenerations_recoverOwnerVendorPriceAndStructureDrift(@TempDir Path storeDir) {
        store = new ProcessSuggestionStore(storeDir.resolve("suggestions"));
        service.setSuggestionStore(store);

        // ── Generation 1: mine the era-1 graph ──────────────────────────────────
        ProcessSuggestion first = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(first);
        Map<String, ProcessSuggestion.SuggestedStep> gen1 = stepsByName(first);

        // The mined guard reads the price data: small orders auto-approve, big ones escalate.
        ProcessSuggestion.SuggestedStep autoApproval = gen1.get("Auto Approval");
        assertNotNull(autoApproval, "era-1 must mine the auto branch, got " + gen1.keySet());
        assertTrue(autoApproval.getConditionExpression().contains("#amount <= 625.0"),
                "the amount guard splits 450|800 at its midpoint: " + autoApproval.getConditionExpression());
        assertTrue(autoApproval.getConditionLabel().contains("observed in 4 of 6 cases"),
                autoApproval.getConditionLabel());
        // Era-1 ownership: bob approves BOTH branches; Acme Supply issues every invoice.
        assertEquals("Bob Approver", gen1.get("Manager Approval").getRoleBinding());
        assertEquals("OBSERVED", gen1.get("Manager Approval").getRoleSource());
        assertEquals("Acme Supply Sales", gen1.get("Invoice").getRoleBinding());

        // ── The world changes: the crawl accumulates era 2 on the SAME graph ────
        buildEra2();
        ProcessSuggestion second = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(second);

        // Identity: one process, one lineage — 4-of-5 activity overlap clears the threshold.
        assertEquals(first.getId(), second.getProcessKey(), "the SAME process at a later time");
        assertEquals(first.getId(), second.getPreviousSuggestionId());
        assertNotNull(store.get(first.getId()).orElseThrow().getSupersededAt(),
                "the predecessor is marked superseded — never deleted");

        // Ownership drift: carol out-approves bob on Manager Approval (7 vs 2 observed instances);
        // the Auto branch has no era-2 instances, so bob's binding there stands — no false drift.
        Map<String, ProcessSuggestion.SuggestedStep> gen2 = stepsByName(second);
        assertEquals("Carol Approver", gen2.get("Manager Approval").getRoleBinding());
        assertEquals("Bob Approver", gen2.get("Auto Approval").getRoleBinding());
        // Vendor drift: NuParts issued 7 of the 13 invoices.
        assertEquals("NuParts Billing", gen2.get("Invoice").getRoleBinding());

        List<String> drift = evidence(second, "DRIFT");
        assertTrue(drift.stream().anyMatch(d -> d.contains("step added: 'Price Review'")),
                "the new review step is drift: " + drift);
        assertTrue(drift.stream().anyMatch(d ->
                        d.contains("performer of 'Manager Approval': Bob Approver → Carol Approver")),
                "the ownership change is drift: " + drift);
        assertTrue(drift.stream().anyMatch(d ->
                        d.contains("performer of 'Invoice': Acme Supply Sales → NuParts Billing")),
                "the vendor change is drift: " + drift);
        // The price change shows as routing drift: the auto branch's observed share collapsed.
        assertTrue(drift.stream().anyMatch(d -> d.contains("routing of 'Auto Approval' changed")
                        && d.contains("4 of 13") && d.contains("was:") && d.contains("4 of 6")),
                "the price increase shifts the branch shares: " + drift);
        // "Since" is the PREDECESSOR'S MINE date (when we last looked), not the fixture era —
        // drift is relative to the last observation, which is exactly what an operator asks.
        assertTrue(second.getNarrative().contains("Changes since ")
                        && second.getNarrative().contains("step added: 'Price Review'"),
                "the narrative tells the operator what moved: " + second.getNarrative());

        // The re-mine ALSO localizes the change inside its own (accumulated) log.
        assertTrue(drift.stream().anyMatch(d -> d.contains("Change point ~2025-07-07 within this log")
                        && d.contains("activity 'Price Review' appears (0 → 7 cases)")
                        && d.contains("activity 'Auto Approval' disappears (4 → 0 cases)")),
                "the within-log change point dates the drift to the price change: " + drift);
    }

    @Test
    void singleMineOverBothEras_localizesTheChangePoint_withoutAPredecessor(@TempDir Path storeDir) {
        // A FIRST mine over the full history (no earlier generation exists) must still find and
        // date the change — this is the recency-decay complement: decay weights it, this explains it.
        buildEra2();
        store = new ProcessSuggestionStore(storeDir.resolve("suggestions"));
        service.setSuggestionStore(store);

        ProcessSuggestion suggestion = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(suggestion);
        assertEquals(suggestion.getId(), suggestion.getProcessKey(), "first sighting mints identity");

        List<String> drift = evidence(suggestion, "DRIFT");
        assertEquals(1, drift.size(), "no predecessor ⇒ exactly the within-log change point: " + drift);
        assertTrue(drift.get(0).contains("Change point ~2025-07-07 within this log (6 cases before, 7 after)"),
                drift.get(0));
        assertTrue(drift.get(0).contains("activity 'Price Review' appears (0 → 7 cases)"), drift.get(0));
        assertTrue(drift.get(0).contains("activity 'Auto Approval' disappears (4 → 0 cases)"), drift.get(0));
    }

    @Test
    void acceptedProcess_reMinedAfterTheChange_revisesTheSameDefinition(@TempDir Path storeDir)
            throws Exception {
        // Real engine (versioned definition store) writing under an isolated home.
        Path home = Files.createDirectories(storeDir.resolve("home"));
        System.setProperty("user.home", home.toString());
        ProcessEngineServiceImpl engine = new ProcessEngineServiceImpl();
        engine.init();

        store = new ProcessSuggestionStore(storeDir.resolve("suggestions"));
        service.setSuggestionStore(store);
        ProcessDiscoveryServiceImpl acceptService = new ProcessDiscoveryServiceImpl(graph);
        acceptService.setProcessEngineService(engine);

        // Era 1 is mined and ACCEPTED into a live definition (v1).
        ProcessSuggestion first = service.discoverForFactSheet(FS, 0.0, null);
        ProcessDefinition v1 = acceptService.acceptSuggestion(first);
        store.markAccepted(first.getId(), v1.getId());
        assertEquals(1, v1.getVersion());

        // The world changes; the re-mine recognizes the accepted process and proposes a REVISION.
        buildEra2();
        ProcessSuggestion second = service.discoverForFactSheet(FS, 0.0, null);
        assertEquals(v1.getId(), second.getRevisesProcessDefinitionId(),
                "a re-mine matching an ACCEPTED process proposes to revise its live definition");
        assertTrue(store.get(first.getId()).orElseThrow().getSupersededAt() == null,
                "accepted suggestions are identity anchors — never superseded");

        // Accepting the drifted generation bumps the SAME definition, not a new one.
        ProcessDefinition v2 = acceptService.acceptSuggestion(second);
        assertEquals(v1.getId(), v2.getId(), "one process, one definition id");
        assertEquals(2, v2.getVersion());
        assertEquals(ProcessStatus.DRAFT, v2.getStatus(), "the revision awaits approval");
        assertTrue(v2.getPhases().stream().flatMap(p -> p.getSteps().stream())
                        .anyMatch(s -> "Price Review".equals(s.getName())),
                "the revised definition carries the new step");
        assertEquals("Widget order e1a0",
                engine.getProcess(v1.getId(), 1).getPhases().stream()
                        .flatMap(p -> p.getSteps().stream())
                        .filter(s -> "Purchase Request".equals(s.getName()))
                        .findFirst().map(s -> byId.get(s.getGraphNodeIds().get(0)).getTitle())
                        .orElse("(missing)"),
                "version 1 stays immutable and traceable to the era-1 graph");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

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
