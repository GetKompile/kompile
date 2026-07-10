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

import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
import ai.kompile.process.discovery.mining.rules.MinedRulePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end over an APPROXIMATED REAL CRAWL: the fixture mirrors what the crawl pipeline's two
 * email lanes actually write for a corporation's mailbox —
 *
 * <ul>
 *   <li><b>Email lane</b> ({@code EmailGraphExtractor}): one EMAIL_MESSAGE entity per mail
 *       (occurredAt = {@code email.date}), PERSON per address, ORGANIZATION per sender domain,
 *       ATTACHMENT entities; USER_DEFINED edges with free-form labels — person —SENT_BY→ email,
 *       email —SENT_TO/CC_TO→ person, email —REPLIED_TO/REFERENCES→ prior message,
 *       email —HAS_ATTACHMENT→ attachment, person —BELONGS_TO→ org — all EXTRACTED provenance;
 *       a SOURCE crawl-root node with CONTAINS edges to every message.</li>
 *   <li><b>Extraction lane</b> ({@code RuleBasedDocumentGraphExtractor} dispatch-merge): typed
 *       business entities from mail bodies (PURCHASE_REQUEST, APPROVAL, …) with occurredAt lifted
 *       from their incident relations, linked to their message via MENTIONS.</li>
 * </ul>
 *
 * Corpus: 4 procurement threads (Request → Approval → PO w/ attachment → Invoice), 3 recruiting
 * threads (Application → Interview → Offer), 1 undated newsletter, shared people/org hubs
 * (alice/bob/carol/dana + acme.com touch MANY threads — the exact topology that used to collapse
 * everything into one mega-case and turn PERSON into an "activity").
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorporateEmailCrawlIntegrationTest {

    private static final long FS = 77L;
    private static final LocalDateTime T0 = LocalDateTime.of(2025, 3, 3, 9, 0);

    @Mock
    private KnowledgeGraphService graph;

    @Mock
    private ApplicationEventPublisher publisher;

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, GraphNode> byId = new LinkedHashMap<>();

    private MiningProcessDiscoveryService service;
    private KbGroundingService kb;
    private ProcessSuggestionStore store;

    // ── crawl-output builders (shapes mirror EmailGraphExtractor / RuleBasedDocumentGraphExtractor) ──

    private GraphNode entity(String id, String entityType, String title, LocalDateTime occurredAt,
                             String extraJsonProps) {
        String json = "{\"entity_type\":\"" + entityType + "\",\"source\":\"job-1\""
                + (extraJsonProps != null ? "," + extraJsonProps : "") + "}";
        GraphNode node = GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .title(title)
                .occurredAt(occurredAt)
                .metadataJson(json)
                .build();
        nodes.add(node);
        byId.put(id, node);
        return node;
    }

    private GraphNode source(String id) {
        GraphNode node = GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.SOURCE).title("IMAP crawl root")
                .metadataJson("{\"source\":\"job-1\"}")
                .build();
        nodes.add(node);
        byId.put(id, node);
        return node;
    }

    private void edge(String fromId, String toId, String label) {
        edges.add(GraphEdge.builder()
                .edgeId(fromId + "::" + toId + "::" + label)
                .sourceNodeId(fromId).targetNodeId(toId)
                .sourceNode(byId.get(fromId)).targetNode(byId.get(toId))
                .relationType(label)
                .provenanceType(EdgeProvenance.EXTRACTED)
                .build());
    }

    private GraphNode person(String id, String email) {
        return entity(id, "PERSON", email.substring(0, email.indexOf('@')), null,
                "\"email\":\"" + email + "\"");
    }

    /** One email message wired the way the email lane writes it. */
    private GraphNode email(String id, String subject, LocalDateTime at, String senderId,
                            String sourceId, List<String> recipientIds, String repliedToId) {
        GraphNode mail = entity(id, "EMAIL_MESSAGE", subject, at, "\"messageId\":\"<" + id + "@acme.com>\"");
        edge(sourceId, id, "CONTAINS");
        edge(senderId, id, "SENT_BY");
        for (String r : recipientIds) {
            edge(id, r, "SENT_TO");
        }
        if (repliedToId != null) {
            edge(id, repliedToId, "REPLIED_TO");
        }
        return mail;
    }

    /** A body-extracted business entity, occurredAt lifted from its message (extraction lane). */
    private void businessEntity(String id, String type, String title, GraphNode mail) {
        entity(id, type, title, mail.getOccurredAt(), null);
        edge(mail.getNodeId(), id, "MENTIONS");
    }

    @BeforeEach
    void buildAcmeMailboxCrawl() {
        GraphNode root = source("src-imap");
        GraphNode alice = person("p-alice", "alice@acme.com");   // requester
        GraphNode bob = person("p-bob", "bob@acme.com");         // approver — touches EVERY procurement thread
        GraphNode carol = person("p-carol", "carol@acme.com");   // accounts payable
        GraphNode dana = person("p-dana", "dana@acme.com");      // recruiter
        GraphNode vendor = person("p-vendor", "sales@supplier.com");
        GraphNode acme = entity("org-acme", "ORGANIZATION", "acme.com", null, "\"domain\":\"acme.com\"");
        for (GraphNode p : List.of(alice, bob, carol, dana)) {
            edge(p.getNodeId(), acme.getNodeId(), "BELONGS_TO");
        }

        // 4 procurement threads: Request → Approval → PO (attachment) → Invoice.
        for (int k = 0; k < 4; k++) {
            LocalDateTime d0 = T0.plusDays(7L * k);
            GraphNode m1 = email("proc-" + k + "-m1", "Purchase request — laptops #" + k, d0,
                    alice.getNodeId(), root.getNodeId(), List.of(bob.getNodeId()), null);
            businessEntity("proc-" + k + "-req", "PURCHASE_REQUEST", "Laptop request #" + k, m1);

            GraphNode m2 = email("proc-" + k + "-m2", "Re: purchase request — approved", d0.plusHours(4),
                    bob.getNodeId(), root.getNodeId(),
                    List.of(alice.getNodeId(), carol.getNodeId()), m1.getNodeId());
            businessEntity("proc-" + k + "-appr", "APPROVAL", "Approval #" + k, m2);

            GraphNode m3 = email("proc-" + k + "-m3", "PO sent to supplier", d0.plusDays(1),
                    carol.getNodeId(), root.getNodeId(), List.of(vendor.getNodeId()), m2.getNodeId());
            entity("proc-" + k + "-att", "ATTACHMENT", "PO-" + k + ".pdf", d0.plusDays(1), null);
            edge(m3.getNodeId(), "proc-" + k + "-att", "HAS_ATTACHMENT");
            businessEntity("proc-" + k + "-po", "PURCHASE_ORDER", "PO #" + k, m3);

            GraphNode m4 = email("proc-" + k + "-m4", "Invoice received", d0.plusDays(5),
                    vendor.getNodeId(), root.getNodeId(), List.of(carol.getNodeId()), m3.getNodeId());
            businessEntity("proc-" + k + "-inv", "INVOICE", "Invoice #" + k, m4);
        }

        // 3 recruiting threads: Application → Interview → Offer.
        for (int k = 0; k < 3; k++) {
            LocalDateTime d0 = T0.plusDays(2L + 9L * k);
            GraphNode m1 = email("rec-" + k + "-m1", "Application received — SWE #" + k, d0,
                    dana.getNodeId(), root.getNodeId(), List.of(bob.getNodeId()), null);
            businessEntity("rec-" + k + "-app", "JOB_APPLICATION", "Application #" + k, m1);

            GraphNode m2 = email("rec-" + k + "-m2", "Re: interview scheduled", d0.plusDays(2),
                    dana.getNodeId(), root.getNodeId(), List.of(bob.getNodeId()), m1.getNodeId());
            businessEntity("rec-" + k + "-int", "INTERVIEW", "Interview #" + k, m2);

            GraphNode m3 = email("rec-" + k + "-m3", "Re: offer extended", d0.plusDays(6),
                    dana.getNodeId(), root.getNodeId(), List.of(bob.getNodeId()), m2.getNodeId());
            businessEntity("rec-" + k + "-off", "OFFER", "Offer #" + k, m3);
        }

        // Undated one-off newsletter (crawls always contain junk; date header missing).
        email("news-1", "ACME weekly digest", null,
                person("p-news", "digest@newsletter.io").getNodeId(),
                root.getNodeId(), List.of(alice.getNodeId()), null);

        when(graph.getNodesInFactSheet(FS)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(FS)).thenReturn(edges);
        when(graph.getNode(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0, String.class))));
        when(graph.createEdgesBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        kb = new KbGroundingService();
        service = new MiningProcessDiscoveryService(graph);
        service.setKbGroundingService(kb);
        service.setEventPublisher(publisher);
    }

    @Test
    void corporateMailboxCrawl_yieldsCoherentPerWorkflowSuggestions(@TempDir Path dataDir) throws Exception {
        MinedRulePersistenceService rulePersistence = new MinedRulePersistenceService();
        ReflectionTestUtils.setField(rulePersistence, "dataDir", dataDir.toString());
        service.setRulePersistenceService(rulePersistence);
        ReflectionTestUtils.setField(service, "dataDir", dataDir.toString());
        store = new ProcessSuggestionStore(dataDir.resolve("suggestions"));
        service.setSuggestionStore(store);

        ProcessSuggestion best = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(best, "a corporate mailbox must yield a process");

        // 1. Shared people/org hubs and the crawl root must NOT collapse the mailbox into one
        //    mega-process: procurement and recruiting come out as separate suggestions.
        List<ProcessSuggestion> stored = store.listByFactSheet(FS);
        assertEquals(2, stored.size(), "procurement and recruiting must mine separately, got "
                + stored.stream().map(ProcessSuggestion::getName).toList());

        ProcessSuggestion procurement = bySteps(stored, "Purchase Request");
        ProcessSuggestion recruiting = bySteps(stored, "Interview");
        Set<String> procurementSteps = stepNames(procurement);
        Set<String> recruitingSteps = stepNames(recruiting);

        // 2. Actors are resources, not steps: no PERSON/ORGANIZATION activities anywhere.
        for (ProcessSuggestion s : stored) {
            for (String step : stepNames(s)) {
                assertFalse(step.equalsIgnoreCase("Person") || step.equalsIgnoreCase("Organization"),
                        "actor/resource types must never appear as steps, got '" + step + "' in " + s.getName());
            }
        }

        // 3. Workflow vocabularies stay coherent — no cross-contamination.
        assertTrue(procurementSteps.containsAll(Set.of("Purchase Request", "Approval", "Purchase Order", "Invoice")),
                "procurement steps incomplete: " + procurementSteps);
        assertTrue(recruitingSteps.containsAll(Set.of("Job Application", "Interview", "Offer")),
                "recruiting steps incomplete: " + recruitingSteps);
        assertTrue(Set.of("Interview", "Offer", "Job Application").stream().noneMatch(procurementSteps::contains),
                "recruiting activities leaked into procurement: " + procurementSteps);
        assertTrue(Set.of("Purchase Request", "Invoice", "Purchase Order").stream().noneMatch(recruitingSteps::contains),
                "procurement activities leaked into recruiting: " + recruitingSteps);
        assertTrue(procurement.getDescription().contains("4 case(s)"),
                "each procurement thread is one case: " + procurement.getDescription());

        // 4. Entailment over the REAL vocabulary: Purchase Request precedes Invoice is never a
        //    directly-follows arc (Email Message interleaves) — it must be ENTAILED, and after
        //    creation the KB answers it.
        VerifyResult verdict = kb.verify(FS, "precedes(\"Purchase Request\", \"Invoice\")");
        assertEquals(VerifyResult.Status.SUPPORTED, verdict.status(),
                "transitively entailed ordering must be queryable knowledge");
        assertEquals(VerifyResult.Status.SUPPORTED,
                kb.verify(FS, "activity(\"Purchase Request\")").status(),
                "promoted activity existence facts must verify");
        assertTrue(kb.getState(FS).factStore().factFor("Occurs(Purchase Request)").isPresent(),
                "unquoted Occurs atoms must be promoted for cascade-rule unification");

        // 5. Control flow reaches the graph: directional DIRECTLY_FOLLOWS instance edges, and the
        //    entailed-only Purchase Request → Invoice pair as an activity-level PRECEDES edge.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(graph, atLeastOnce()).createEdgesBatch(captor.capture());
        List<KnowledgeGraphService.EdgeSpec> specs = captor.getAllValues().stream()
                .flatMap(List::stream).toList();
        assertTrue(specs.stream().anyMatch(s -> PrecedenceMaterializer.DIRECTLY_FOLLOWS.equals(s.label())),
                "observed control flow must be materialized");
        assertTrue(specs.stream().allMatch(s -> s.metaJson().contains("\"bidirectional\":false")),
                "control flow must be directional");
        assertTrue(specs.stream().anyMatch(s -> PrecedenceMaterializer.PRECEDES.equals(s.label())
                        && s.metaJson().contains("Purchase Request") && s.metaJson().contains("Invoice")),
                "entailed-only Purchase Request→Invoice must materialize as PRECEDES");
        assertTrue(specs.stream().anyMatch(s -> PrecedenceMaterializer.PERFORMED_BY.equals(s.label())
                        && "p-bob".equals(s.targetNodeId()) && s.metaJson().contains("Approval")),
                "the observed approver must materialize as an Approval —PERFORMED_BY→ bob edge");

        // 6. Mined rules persist once per fact sheet in the Occurs vocabulary, and staging is told.
        Path ruleFile = dataDir.resolve("rules").resolve(FS + "-mined.psl");
        assertTrue(Files.exists(ruleFile), "mined rule file must be written");
        assertTrue(Files.readString(ruleFile).contains("Occurs("),
                "declare/causal rules use the Occurs vocabulary");
        // Captor must match the overload javac bound at the call site: ModelTrainedEvent extends
        // ApplicationEvent, so the service invokes publishEvent(ApplicationEvent), not (Object).
        ArgumentCaptor<org.springframework.context.ApplicationEvent> events =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(publisher, atLeastOnce()).publishEvent(events.capture());
        assertTrue(events.getAllValues().stream()
                        .filter(e -> e instanceof ModelTrainedEvent)
                        .map(e -> (ModelTrainedEvent) e)
                        .anyMatch(e -> "psl-mined".equals(e.getBaseModelId()) && e.getFactSheetId() == FS),
                "staging must hear about the mined rules exactly like cascade weights");

        // 7. Evidence carries the reasoning outputs the UI renders.
        Set<String> evidenceTypes = new LinkedHashSet<>();
        stored.forEach(s -> s.getStructuredEvidence().forEach(ev -> evidenceTypes.add(ev.getType())));
        assertTrue(evidenceTypes.contains("ENTAILED"), "entailed orderings must surface, got " + evidenceTypes);
        assertTrue(evidenceTypes.contains("FUSION"), "fusion breakdown must surface, got " + evidenceTypes);

        // 8. The resource perspective: the SENT_BY relations that casing must ignore are exactly
        //    what binds roles — the observed senders, per activity, not name keywords.
        //    alice authored every request, bob every approval, carol every PO, the supplier ("sales")
        //    every invoice.
        Map<String, String> procurementRoles = new LinkedHashMap<>();
        Map<String, String> procurementSources = new LinkedHashMap<>();
        procurement.getPhases().forEach(p -> p.getSteps().forEach(step -> {
            procurementRoles.put(step.getName(), step.getRoleBinding());
            procurementSources.put(step.getName(), step.getRoleSource());
        }));
        assertEquals("alice", procurementRoles.get("Purchase Request"),
                "requester observed from SENT_BY, got " + procurementRoles);
        assertEquals("bob", procurementRoles.get("Approval"), "approver observed, got " + procurementRoles);
        assertEquals("carol", procurementRoles.get("Purchase Order"), "PO author observed, got " + procurementRoles);
        assertEquals("sales", procurementRoles.get("Invoice"),
                "the supplier sent every invoice — an observed performer, not UNASSIGNED");
        assertEquals("OBSERVED", procurementSources.get("Approval"),
                "role provenance must say the binding was observed, not guessed");

        // The observation is promoted knowledge: performedBy(...) is queryable, which is what
        // makes RoleBindingExtractor's KB tier answer on later mines even without actor edges.
        List<QueryBinding> who = kb.query(FS, List.of(
                new ConjunctiveQueryEngine.AtomPattern("performedBy",
                        List.of("\"Approval\"", "?Who"))), 5);
        assertFalse(who.isEmpty(), "promoted performedBy facts must be conjunctive-queryable");
        assertTrue(who.get(0).get("?Who").contains("bob"), "the observed approver answers the query");

        // And it surfaces to the UI as RESOURCE evidence with per-instance counts.
        assertTrue(procurement.getStructuredEvidence().stream().anyMatch(ev ->
                        "RESOURCE".equals(ev.getType())
                                && ev.getDescription().contains("Approval performed by bob")
                                && ev.getDescription().contains("4 of 4")),
                "observed performer evidence must render, got " + procurement.getStructuredEvidence().stream()
                        .filter(ev -> "RESOURCE".equals(ev.getType()))
                        .map(ProcessSuggestion.StructuredEvidence::getDescription).toList());
    }

    /**
     * Messy reality: crawls always contain degradation — emails whose Date header never parsed,
     * REPLIED_TO placeholders for messages the crawl never saw (the email lane creates phantom
     * EMAIL_MESSAGE nodes for unseen In-Reply-To ids), snake_case junk entity types leaking from
     * extraction, and duplicate PERSON nodes for the same human (IMAP vs provider lanes). None of
     * it may break mining or leak raw junk into step names.
     */
    @Test
    void degradedCrawlArtifacts_neverBreakMining_orLeakJunkLabels(@TempDir Path dataDir) {
        store = new ProcessSuggestionStore(dataDir.resolve("suggestions"));
        service.setSuggestionStore(store);

        // Undated procurement thread: date headers missing on BOTH mails (occurredAt null).
        GraphNode u1 = email("proc-undated-m1", "Purchase request — chairs", null,
                "p-alice", "src-imap", List.of("p-bob"), null);
        businessEntity("proc-undated-req", "PURCHASE_REQUEST", "Chair request", u1);
        GraphNode u2 = email("proc-undated-m2", "Re: purchase request — approved", null,
                "p-bob", "src-imap", List.of("p-alice"), u1.getNodeId());
        businessEntity("proc-undated-appr", "APPROVAL", "Chair approval", u2);

        // Phantom thread parent: REPLIED_TO points at a message the crawl never fetched — the
        // email lane materializes a placeholder EMAIL_MESSAGE with no date.
        entity("phantom-msg", "EMAIL_MESSAGE", "Message <lost@acme.com>", null, null);
        edge("proc-0-m1", "phantom-msg", "REPLIED_TO");

        // Snake_case junk entity type leaking from extraction on an invoice mail.
        businessEntity("proc-0-junk", "entity_invoice_number", "INV-0042", byId.get("proc-0-m4"));

        // Duplicate person for the same human (two lanes, two ids) — actors are resources, so
        // this must be invisible to mining either way.
        GraphNode bob2 = person("p-bob-gmail", "robert.j@acme.com");
        edge(bob2.getNodeId(), "org-acme", "BELONGS_TO");
        edge(bob2.getNodeId(), "proc-1-m2", "SENT_BY");

        ProcessSuggestion best = service.discoverForFactSheet(FS, 0.0, null);
        assertNotNull(best, "degraded artifacts must never sink discovery");

        List<ProcessSuggestion> stored = store.listByFactSheet(FS);
        assertEquals(2, stored.size(),
                "junk/undated artifacts must not change the workflow count: "
                        + stored.stream().map(ProcessSuggestion::getName).toList());
        for (ProcessSuggestion s : stored) {
            for (String step : stepNames(s)) {
                assertFalse(step.contains("_"),
                        "raw snake_case junk must be prettified, got '" + step + "'");
                assertFalse(step.equalsIgnoreCase("Person") || step.equalsIgnoreCase("Organization"),
                        "actors must stay excluded, got '" + step + "'");
            }
        }
        // The undated thread still mined (its cluster shares the procurement vocabulary) and the
        // junk type shows prettified.
        ProcessSuggestion procurement = bySteps(stored, "Purchase Request");
        assertTrue(procurement.getDescription().contains("5 case(s)"),
                "the undated thread joins the procurement cluster as a 5th case: "
                        + procurement.getDescription());
        assertTrue(stepNames(procurement).contains("Entity Invoice Number"),
                "junk type must appear prettified: " + stepNames(procurement));
    }

    /**
     * Re-crawl stability: a second crawl sees the FIRST run's materialized control-flow edges in
     * the graph. Case correlation must ignore them (derived-edge guard), so re-mining yields the
     * same workflows and the store supersedes instead of piling up.
     */
    @Test
    void reMiningWithMaterializedEdgesPresent_isStable(@TempDir Path dataDir) {
        store = new ProcessSuggestionStore(dataDir.resolve("suggestions"));
        service.setSuggestionStore(store);

        assertNotNull(service.discoverForFactSheet(FS, 0.0, null));
        List<ProcessSuggestion> first = store.listByFactSheet(FS);
        assertEquals(2, first.size());
        Set<Set<String>> firstVocabularies = new LinkedHashSet<>();
        first.forEach(s -> firstVocabularies.add(stepNames(s)));

        // Feed the materialized control flow back into the graph, as the next crawl would see it.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(graph, atLeastOnce()).createEdgesBatch(captor.capture());
        for (KnowledgeGraphService.EdgeSpec spec : captor.getAllValues().stream()
                .flatMap(List::stream).toList()) {
            edges.add(GraphEdge.builder()
                    .edgeId(spec.sourceNodeId() + "::" + spec.targetNodeId() + "::" + spec.label())
                    .sourceNodeId(spec.sourceNodeId()).targetNodeId(spec.targetNodeId())
                    .sourceNode(byId.get(spec.sourceNodeId())).targetNode(byId.get(spec.targetNodeId()))
                    .relationType(spec.label())
                    .provenanceType(spec.provenance())
                    .build());
        }

        assertNotNull(service.discoverForFactSheet(FS, 0.0, null));
        // Mark-never-delete: all four generations stay stored, exactly two remain HEADS — and
        // materialized derived edges must not have merged the two workflows into one.
        List<ProcessSuggestion> all = store.listByFactSheet(FS);
        assertEquals(4, all.size(), "both generations of both processes stay stored (lineage)");
        List<ProcessSuggestion> heads = all.stream().filter(s -> s.getSupersededAt() == null).toList();
        assertEquals(2, heads.size(),
                "re-mining must supersede predecessors — and materialized edges must not merge cases");
        Set<Set<String>> secondVocabularies = new LinkedHashSet<>();
        heads.forEach(s -> secondVocabularies.add(stepNames(s)));
        assertEquals(firstVocabularies, secondVocabularies,
                "the discovered workflows must be identical run-over-run");
        // Identity resolved across the re-mine: every head carries its predecessor's key.
        for (ProcessSuggestion head : heads) {
            assertNotNull(head.getPreviousSuggestionId(),
                    "an unchanged process must be recognized as the SAME process, not a new one");
            ProcessSuggestion predecessor = store.get(head.getPreviousSuggestionId()).orElseThrow();
            assertEquals(predecessor.getId(), head.getProcessKey(),
                    "the identity key carries across generations");
            assertEquals(head.getId(), predecessor.getSupersededBySuggestionId());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ProcessSuggestion bySteps(List<ProcessSuggestion> suggestions, String stepName) {
        return suggestions.stream()
                .filter(s -> stepNames(s).contains(stepName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no suggestion contains step '" + stepName + "'; got "
                                + suggestions.stream().map(CorporateEmailCrawlIntegrationTest::stepNames).toList()));
    }

    private static Set<String> stepNames(ProcessSuggestion s) {
        Set<String> names = new LinkedHashSet<>();
        s.getPhases().forEach(p -> p.getSteps().forEach(step -> names.add(step.getName())));
        return names;
    }
}
