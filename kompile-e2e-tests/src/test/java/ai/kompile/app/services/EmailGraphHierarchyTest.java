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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.crawl.graph.UnifiedCrawlGraphServiceImpl;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.Queue;
import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the full graph hierarchy produced by the email ingest pipeline.
 *
 * <p>Uses an in-memory graph model (backed by maps) so that we can verify
 * the actual node/edge topology rather than just asserting mock interactions.
 *
 * <p>Expected hierarchy for an email with attachments:
 * <pre>
 *   SOURCE (ingest:task-id)
 *     └── DOCUMENT (/path/to/email.eml)  [HIERARCHICAL]
 *           └── EMAIL_MESSAGE (subject)   [CONTAINS]
 *                 ├── PERSON (sender)     [SENT_BY: person→email]
 *                 ├── PERSON (recipient1) [SENT_TO: email→person]
 *                 ├── PERSON (recipient2) [SENT_TO: email→person]
 *                 ├── PERSON (cc1)        [CC_TO: email→person]
 *                 ├── ATTACHMENT (file1)  [HAS_ATTACHMENT: email→att]
 *                 └── ATTACHMENT (file2)  [HAS_ATTACHMENT: email→att]
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailGraphHierarchyTest {

    private UnifiedCrawlGraphServiceImpl ingestService;
    private Method routeByContentTypeMethod;
    private Method applyEmailGraphExtraction;

    // ─── In-memory graph — simple model for topology verification ────────

    /** Lightweight edge record for the in-memory graph. */
    static class TestEdge {
        final String id;
        final String sourceNodeId;
        final String targetNodeId;
        final EdgeType edgeType;
        final String label;
        final double weight;

        TestEdge(String id, String sourceNodeId, String targetNodeId,
                 EdgeType edgeType, String label, double weight) {
            this.id = id;
            this.sourceNodeId = sourceNodeId;
            this.targetNodeId = targetNodeId;
            this.edgeType = edgeType;
            this.label = label;
            this.weight = weight;
        }
    }

    private final Map<String, GraphNode> nodesById = new ConcurrentHashMap<>();
    private final Map<String, GraphNode> nodesByExternalIdAndType = new ConcurrentHashMap<>();
    private final List<TestEdge> edges = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong edgeSeq = new AtomicLong();

    private static String extKey(String externalId, NodeLevel level) {
        return externalId + "::" + level.name();
    }

    /**
     * Wire up a mock KnowledgeGraphService backed by in-memory maps.
     */
    private KnowledgeGraphService buildGraphService() {
        KnowledgeGraphService gs = mock(KnowledgeGraphService.class);

        // ── addDocument ─────────────────────────────────────────────────
        when(gs.addDocument(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(inv -> {
                    String sourceExtId = inv.getArgument(0);
                    String sourceTitle = inv.getArgument(1);
                    String sourceType = inv.getArgument(2);
                    String docExtId = inv.getArgument(3);
                    String docTitle = inv.getArgument(4);
                    String content = inv.getArgument(5);

                    // find or create SOURCE
                    String srcKey = extKey(sourceExtId, NodeLevel.SOURCE);
                    GraphNode sourceNode = nodesByExternalIdAndType.computeIfAbsent(srcKey, k -> {
                        GraphNode n = GraphNode.builder()
                                .nodeId(UUID.randomUUID().toString())
                                .externalId(sourceExtId)
                                .nodeType(NodeLevel.SOURCE)
                                .title(sourceTitle)
                                .sourceType(sourceType)
                                .build();
                        nodesById.put(n.getNodeId(), n);
                        return n;
                    });

                    // find or create DOCUMENT
                    String docKey = extKey(docExtId, NodeLevel.DOCUMENT);
                    GraphNode docNode = nodesByExternalIdAndType.computeIfAbsent(docKey, k -> {
                        GraphNode n = GraphNode.builder()
                                .nodeId(UUID.randomUUID().toString())
                                .externalId(docExtId)
                                .nodeType(NodeLevel.DOCUMENT)
                                .title(docTitle)
                                .contentPreview(content)
                                .build();
                        nodesById.put(n.getNodeId(), n);
                        addEdge(sourceNode.getNodeId(), n.getNodeId(),
                                EdgeType.HIERARCHICAL, 1.0, "CONTAINS");
                        return n;
                    });
                    return docNode;
                });

        // ── getNodeByExternalId ─────────────────────────────────────────
        when(gs.getNodeByExternalId(anyString(), any(NodeLevel.class)))
                .thenAnswer(inv -> {
                    String extId = inv.getArgument(0);
                    NodeLevel lvl = inv.getArgument(1);
                    return Optional.ofNullable(nodesByExternalIdAndType.get(extKey(extId, lvl)));
                });

        // ── createNode ──────────────────────────────────────────────────
        when(gs.createNode(any(NodeLevel.class), anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> {
                    NodeLevel level = inv.getArgument(0);
                    String extId = inv.getArgument(1);
                    String title = inv.getArgument(2);
                    String desc = inv.getArgument(3);
                    Map<String, Object> meta = inv.getArgument(4);

                    String key = extKey(extId, level);
                    return nodesByExternalIdAndType.computeIfAbsent(key, k -> {
                        GraphNode n = GraphNode.builder()
                                .nodeId(UUID.randomUUID().toString())
                                .externalId(extId)
                                .nodeType(level)
                                .title(title)
                                .description(desc)
                                .metadataJson(metaToJson(meta))
                                .build();
                        nodesById.put(n.getNodeId(), n);
                        return n;
                    });
                });

        // ── createSnippetNode ───────────────────────────────────────────
        when(gs.createSnippetNode(any(GraphNode.class), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    GraphNode parent = inv.getArgument(0);
                    String snippetId = inv.getArgument(1);
                    String content = inv.getArgument(2);
                    int idx = inv.getArgument(3);

                    String key = extKey(snippetId, NodeLevel.SNIPPET);
                    GraphNode snippet = nodesByExternalIdAndType.computeIfAbsent(key, k -> {
                        GraphNode n = GraphNode.builder()
                                .nodeId(UUID.randomUUID().toString())
                                .externalId(snippetId)
                                .nodeType(NodeLevel.SNIPPET)
                                .title("Chunk " + idx)
                                .contentPreview(content.length() > 200
                                        ? content.substring(0, 200) : content)
                                .build();
                        nodesById.put(n.getNodeId(), n);
                        return n;
                    });
                    if (parent != null) {
                        addEdge(parent.getNodeId(), snippet.getNodeId(),
                                EdgeType.HIERARCHICAL, 1.0, "CHUNK");
                    }
                    return snippet;
                });

        // ── edgeExists ──────────────────────────────────────────────────
        when(gs.edgeExists(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String src = inv.getArgument(0);
                    String tgt = inv.getArgument(1);
                    return edges.stream()
                            .anyMatch(e -> e.sourceNodeId.equals(src) && e.targetNodeId.equals(tgt));
                });

        // ── createEdge ──────────────────────────────────────────────────
        when(gs.createEdge(anyString(), anyString(), any(EdgeType.class), anyDouble(), anyString()))
                .thenAnswer(inv -> {
                    String src = inv.getArgument(0);
                    String tgt = inv.getArgument(1);
                    EdgeType type = inv.getArgument(2);
                    double weight = inv.getArgument(3);
                    String label = inv.getArgument(4);
                    addEdge(src, tgt, type, weight, label);
                    // Return a stub GraphEdge — the callers only need a non-null return
                    return GraphEdge.builder()
                            .edgeId(UUID.randomUUID().toString())
                            .edgeType(type)
                            .build();
                });

        return gs;
    }

    private void addEdge(String src, String tgt, EdgeType type, double weight, String label) {
        edges.add(new TestEdge(
                String.valueOf(edgeSeq.incrementAndGet()),
                src, tgt, type, label, weight));
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String metaToJson(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : meta.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    // ─── Query helpers ──────────────────────────────────────────────────────

    private List<GraphNode> nodesByLevel(NodeLevel level) {
        return nodesById.values().stream()
                .filter(n -> n.getNodeType() == level)
                .collect(Collectors.toList());
    }

    private List<TestEdge> edgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.sourceNodeId.equals(nodeId)).collect(Collectors.toList());
    }

    private List<TestEdge> edgesTo(String nodeId) {
        return edges.stream().filter(e -> e.targetNodeId.equals(nodeId)).collect(Collectors.toList());
    }

    private List<TestEdge> edgesByLabel(String label) {
        return edges.stream().filter(e -> label.equals(e.label)).collect(Collectors.toList());
    }

    private List<TestEdge> edgesByType(EdgeType type) {
        return edges.stream().filter(e -> e.edgeType == type).collect(Collectors.toList());
    }

    private GraphNode nodeById(String nodeId) {
        return nodesById.get(nodeId);
    }

    private List<GraphNode> entitiesByMetadataType(String entityType) {
        return nodesById.values().stream()
                .filter(n -> n.getNodeType() == NodeLevel.ENTITY)
                .filter(n -> n.getMetadataJson() != null
                        && n.getMetadataJson().contains("\"entity_type\":\"" + entityType + "\""))
                .collect(Collectors.toList());
    }

    /**
     * BFS traversal from a start node, following edges in both directions, up to maxDepth hops.
     * Returns all reachable node IDs (excluding the start node).
     */
    private Set<String> bfsReachable(String startNodeId, int maxDepth) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> depthMap = new HashMap<>();
        queue.add(startNodeId);
        depthMap.put(startNodeId, 0);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depthMap.get(current);
            if (depth >= maxDepth) continue;

            for (TestEdge e : edges) {
                String neighbor = null;
                if (e.sourceNodeId.equals(current)) neighbor = e.targetNodeId;
                else if (e.targetNodeId.equals(current)) neighbor = e.sourceNodeId;
                if (neighbor != null && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    depthMap.put(neighbor, depth + 1);
                    queue.add(neighbor);
                }
            }
        }
        visited.remove(startNodeId);
        return visited;
    }

    /**
     * Find shortest path (by BFS) between two nodes, following edges bidirectionally.
     * Returns ordered list of node IDs from start to end, or empty if no path.
     */
    private List<String> shortestPath(String fromId, String toId, int maxDepth) {
        if (fromId.equals(toId)) return List.of(fromId);
        Map<String, String> parentMap = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(fromId);
        visited.add(fromId);
        boolean found = false;
        int level = 0;

        while (!queue.isEmpty() && level < maxDepth && !found) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize && !found; i++) {
                String current = queue.poll();
                for (TestEdge e : edges) {
                    String neighbor = null;
                    if (e.sourceNodeId.equals(current)) neighbor = e.targetNodeId;
                    else if (e.targetNodeId.equals(current)) neighbor = e.sourceNodeId;
                    if (neighbor != null && !visited.contains(neighbor)) {
                        visited.add(neighbor);
                        parentMap.put(neighbor, current);
                        if (neighbor.equals(toId)) { found = true; break; }
                        queue.add(neighbor);
                    }
                }
            }
            level++;
        }

        if (!found) return List.of();
        LinkedList<String> path = new LinkedList<>();
        String cur = toId;
        while (cur != null) {
            path.addFirst(cur);
            cur = parentMap.get(cur);
        }
        return path;
    }

    /**
     * Collect the labels of edges along a path (path is a list of node IDs).
     */
    private List<String> edgeLabelsAlongPath(List<String> path) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            String a = path.get(i);
            String b = path.get(i + 1);
            for (TestEdge e : edges) {
                if ((e.sourceNodeId.equals(a) && e.targetNodeId.equals(b))
                        || (e.sourceNodeId.equals(b) && e.targetNodeId.equals(a))) {
                    labels.add(e.label);
                    break;
                }
            }
        }
        return labels;
    }

    // ─── Setup ──────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        nodesById.clear();
        nodesByExternalIdAndType.clear();
        edges.clear();
        edgeSeq.set(0);

        KnowledgeGraphService graphService = buildGraphService();

        // UnifiedCrawlGraphServiceImpl uses @Autowired field injection, not constructor injection
        ingestService = new UnifiedCrawlGraphServiceImpl();
        injectField(ingestService, "knowledgeGraphService", graphService);

        routeByContentTypeMethod = UnifiedCrawlGraphServiceImpl.class
                .getDeclaredMethod("routeByContentType", String.class, List.class);
        routeByContentTypeMethod.setAccessible(true);

        applyEmailGraphExtraction = UnifiedCrawlGraphServiceImpl.class
                .getDeclaredMethod("applyEmailGraphExtraction", String.class, List.class);
        applyEmailGraphExtraction.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private List<Document> invokeRouteByContentType(String taskId, List<Document> docs) throws Exception {
        return (List<Document>) routeByContentTypeMethod.invoke(ingestService, taskId, docs);
    }

    private void invokeEmailGraphExtraction(String taskId, List<Document> docs) throws Exception {
        applyEmailGraphExtraction.invoke(ingestService, taskId, docs);
    }

    private Document emailDoc(String from, String to, String cc, String subject,
                              String body, String sourcePath, List<String> attachments) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", from);
        if (to != null) meta.put("email.to", to);
        if (cc != null) meta.put("email.cc", cc);
        meta.put("email.subject", subject);
        meta.put("source_path", sourcePath);
        meta.put("source", sourcePath);
        meta.put("source_filename", sourcePath.substring(sourcePath.lastIndexOf('/') + 1));
        meta.put("source_type", "FILE");
        meta.put("loader", "Mail Message Loader");
        if (attachments != null && !attachments.isEmpty()) {
            meta.put("email.attachmentNames", attachments);
        }
        return new Document(body, meta);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Document-level node registration
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. Document-level node registration")
    class DocumentNodeRegistration {

        @Test
        @DisplayName("routeByContentType creates SOURCE and DOCUMENT nodes with HIERARCHICAL edge")
        void createsSourceAndDocumentNodes() throws Exception {
            Document doc = emailDoc(
                    "alice@example.com", "bob@example.com", null,
                    "Q2 Budget Review", "Please find the Q2 budget attached.",
                    "/data/emails/budget.eml", null);

            invokeRouteByContentType("task-100", List.of(doc));

            List<GraphNode> sources = nodesByLevel(NodeLevel.SOURCE);
            assertEquals(1, sources.size(), "Expected one SOURCE node");
            assertEquals("ingest:task-100", sources.get(0).getExternalId());

            List<GraphNode> docs = nodesByLevel(NodeLevel.DOCUMENT);
            assertEquals(1, docs.size(), "Expected one DOCUMENT node");
            assertEquals("/data/emails/budget.eml", docs.get(0).getExternalId());
            assertEquals("budget.eml", docs.get(0).getTitle());

            List<TestEdge> hier = edgesByType(EdgeType.HIERARCHICAL);
            assertEquals(1, hier.size(), "Expected one HIERARCHICAL edge");
            assertEquals(sources.get(0).getNodeId(), hier.get(0).sourceNodeId);
            assertEquals(docs.get(0).getNodeId(), hier.get(0).targetNodeId);
        }

        @Test
        @DisplayName("Multiple documents from same task share one SOURCE node")
        void multipleDocsSameSource() throws Exception {
            Document doc1 = emailDoc("a@b.com", "c@d.com", null, "Subj1", "body1",
                    "/data/mail1.eml", null);
            Document doc2 = emailDoc("x@y.com", "z@w.com", null, "Subj2", "body2",
                    "/data/mail2.eml", null);

            invokeRouteByContentType("task-200", List.of(doc1, doc2));

            assertEquals(1, nodesByLevel(NodeLevel.SOURCE).size(), "Both docs share one SOURCE");
            assertEquals(2, nodesByLevel(NodeLevel.DOCUMENT).size(), "Two distinct DOCUMENT nodes");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. Sender and recipient entities
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. Email entity extraction — senders and recipients")
    class EmailEntityExtraction {

        @Test
        @DisplayName("Sender creates PERSON entity with SENT_BY edge pointing person→email")
        void senderPerson() throws Exception {
            Document doc = emailDoc(
                    "Alice Smith <alice@example.com>", null, null,
                    "Hello", "Hi there!",
                    "/data/hello.eml", null);

            invokeRouteByContentType("task-300", List.of(doc));
            invokeEmailGraphExtraction("task-300", List.of(doc));

            List<GraphNode> persons = entitiesByMetadataType("PERSON");
            assertEquals(1, persons.size());
            assertEquals("Alice Smith", persons.get(0).getTitle());
            assertTrue(persons.get(0).getMetadataJson().contains("alice@example.com"));

            List<GraphNode> emails = entitiesByMetadataType("EMAIL_MESSAGE");
            assertEquals(1, emails.size());
            assertEquals("Hello", emails.get(0).getTitle());

            // SENT_BY direction: person → email
            List<TestEdge> sentBy = edgesByLabel("SENT_BY");
            assertEquals(1, sentBy.size());
            assertEquals(persons.get(0).getNodeId(), sentBy.get(0).sourceNodeId,
                    "SENT_BY source must be the PERSON node");
            assertEquals(emails.get(0).getNodeId(), sentBy.get(0).targetNodeId,
                    "SENT_BY target must be the EMAIL_MESSAGE node");
        }

        @Test
        @DisplayName("To recipients create PERSON entities with SENT_TO edges email→person")
        void toRecipients() throws Exception {
            Document doc = emailDoc(
                    "sender@co.com",
                    "Bob Jones <bob@co.com>, carol@co.com",
                    null,
                    "Meeting", "Let's meet.",
                    "/data/meeting.eml", null);

            invokeRouteByContentType("task-301", List.of(doc));
            invokeEmailGraphExtraction("task-301", List.of(doc));

            List<GraphNode> persons = entitiesByMetadataType("PERSON");
            assertEquals(3, persons.size());

            List<TestEdge> sentTo = edgesByLabel("SENT_TO");
            assertEquals(2, sentTo.size(), "Two SENT_TO edges for two recipients");

            GraphNode emailNode = entitiesByMetadataType("EMAIL_MESSAGE").get(0);
            for (TestEdge e : sentTo) {
                assertEquals(emailNode.getNodeId(), e.sourceNodeId,
                        "SENT_TO source must be EMAIL_MESSAGE");
                assertNotEquals(emailNode.getNodeId(), e.targetNodeId,
                        "SENT_TO target must be a PERSON, not the email itself");
            }
        }

        @Test
        @DisplayName("Cc recipients create PERSON entities with CC_TO edges email→person")
        void ccRecipients() throws Exception {
            Document doc = emailDoc(
                    "a@b.com", "b@c.com",
                    "Dave <dave@co.com>, eve@co.com",
                    "FYI", "For your info.",
                    "/data/fyi.eml", null);

            invokeRouteByContentType("task-302", List.of(doc));
            invokeEmailGraphExtraction("task-302", List.of(doc));

            List<TestEdge> ccTo = edgesByLabel("CC_TO");
            assertEquals(2, ccTo.size(), "Two CC_TO edges");

            GraphNode emailNode = entitiesByMetadataType("EMAIL_MESSAGE").get(0);
            for (TestEdge e : ccTo) {
                assertEquals(emailNode.getNodeId(), e.sourceNodeId);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. DOCUMENT → EMAIL_MESSAGE connection
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. Document-level CONTAINS edge — DOCUMENT owns EMAIL_MESSAGE")
    class DocumentContainsEmail {

        @Test
        @DisplayName("CONTAINS edge connects DOCUMENT to EMAIL_MESSAGE")
        void containsEdge() throws Exception {
            Document doc = emailDoc(
                    "a@b.com", "c@d.com", null,
                    "Status Update", "Everything is fine.",
                    "/data/status.eml", null);

            invokeRouteByContentType("task-400", List.of(doc));
            invokeEmailGraphExtraction("task-400", List.of(doc));

            GraphNode docNode = nodesByLevel(NodeLevel.DOCUMENT).get(0);
            GraphNode emailNode = entitiesByMetadataType("EMAIL_MESSAGE").get(0);

            List<TestEdge> contains = edges.stream()
                    .filter(e -> "EMAIL_MESSAGE".equals(e.label) && e.edgeType == EdgeType.CONTAINS)
                    .collect(Collectors.toList());
            assertEquals(1, contains.size(), "Expected one CONTAINS edge");
            assertEquals(docNode.getNodeId(), contains.get(0).sourceNodeId,
                    "CONTAINS source must be DOCUMENT");
            assertEquals(emailNode.getNodeId(), contains.get(0).targetNodeId,
                    "CONTAINS target must be EMAIL_MESSAGE");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Attachment graph nodes
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. Attachment graph nodes")
    class AttachmentNodes {

        @Test
        @DisplayName("Attachments create ATTACHMENT entities with HAS_ATTACHMENT edges")
        void attachmentsCreated() throws Exception {
            Document doc = emailDoc(
                    "manager@corp.com", "team@corp.com", null,
                    "Q2 Report", "Please review the attached documents.",
                    "/data/report.eml",
                    List.of("Q2_Report.pdf", "Budget_Spreadsheet.xlsx", "Presentation.pptx"));

            invokeRouteByContentType("task-500", List.of(doc));
            invokeEmailGraphExtraction("task-500", List.of(doc));

            List<GraphNode> attachments = entitiesByMetadataType("ATTACHMENT");
            assertEquals(3, attachments.size(), "Expected 3 ATTACHMENT entities");

            Set<String> attNames = attachments.stream()
                    .map(GraphNode::getTitle).collect(Collectors.toSet());
            assertTrue(attNames.contains("Q2_Report.pdf"));
            assertTrue(attNames.contains("Budget_Spreadsheet.xlsx"));
            assertTrue(attNames.contains("Presentation.pptx"));

            List<TestEdge> hasAtt = edgesByLabel("HAS_ATTACHMENT");
            assertEquals(3, hasAtt.size());

            GraphNode emailNode = entitiesByMetadataType("EMAIL_MESSAGE").get(0);
            for (TestEdge e : hasAtt) {
                assertEquals(emailNode.getNodeId(), e.sourceNodeId,
                        "HAS_ATTACHMENT source must be EMAIL_MESSAGE");
                assertTrue(attachments.stream().anyMatch(a -> a.getNodeId().equals(e.targetNodeId)),
                        "HAS_ATTACHMENT target must be one of the ATTACHMENT nodes");
            }
        }

        @Test
        @DisplayName("Attachment metadata includes filename")
        void attachmentMetadata() throws Exception {
            Document doc = emailDoc(
                    "a@b.com", "c@d.com", null,
                    "Files", "See attached.",
                    "/data/files.eml", List.of("contract.pdf"));

            invokeRouteByContentType("task-501", List.of(doc));
            invokeEmailGraphExtraction("task-501", List.of(doc));

            GraphNode att = entitiesByMetadataType("ATTACHMENT").get(0);
            assertTrue(att.getMetadataJson().contains("\"filename\":\"contract.pdf\""),
                    "Attachment metadata must include filename");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Full hierarchy verification
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. Full hierarchy — SOURCE → DOCUMENT → EMAIL_MESSAGE → PERSON/ATTACHMENT")
    class FullHierarchy {

        @Test
        @DisplayName("Complete email produces correct node counts, edge counts, and topology")
        void fullEmailGraph() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>",
                    "Bob <bob@corp.com>, carol@corp.com",
                    "Dave <dave@ext.com>",
                    "Project Kickoff",
                    "Hi team, please find the project plan and budget attached.",
                    "/data/inbox/kickoff.eml",
                    List.of("project_plan.pdf", "budget.xlsx"));

            invokeRouteByContentType("task-600", List.of(doc));
            invokeEmailGraphExtraction("task-600", List.of(doc));

            // ── Node counts ─────────────────────────────────────────────
            assertEquals(1, nodesByLevel(NodeLevel.SOURCE).size(), "1 SOURCE");
            assertEquals(1, nodesByLevel(NodeLevel.DOCUMENT).size(), "1 DOCUMENT");
            assertEquals(1, entitiesByMetadataType("EMAIL_MESSAGE").size(), "1 EMAIL_MESSAGE");
            assertEquals(4, entitiesByMetadataType("PERSON").size(),
                    "4 PERSON entities (alice, bob, carol, dave)");
            assertEquals(2, entitiesByMetadataType("ATTACHMENT").size(), "2 ATTACHMENT entities");
            // 1+1+1+4+2 = 9 total
            assertEquals(9, nodesById.size(), "Total node count");

            // ── Edge counts ─────────────────────────────────────────────
            assertEquals(1, edgesByType(EdgeType.HIERARCHICAL).size(), "1 HIERARCHICAL");
            assertEquals(1, edgesByType(EdgeType.CONTAINS).size(), "1 CONTAINS");
            assertEquals(1, edgesByLabel("SENT_BY").size());
            assertEquals(2, edgesByLabel("SENT_TO").size());
            assertEquals(1, edgesByLabel("CC_TO").size());
            assertEquals(2, edgesByLabel("HAS_ATTACHMENT").size());
            assertEquals(8, edges.size(), "Total edge count");

            // ── Traversal: SOURCE → DOCUMENT ────────────────────────────
            GraphNode source = nodesByLevel(NodeLevel.SOURCE).get(0);
            GraphNode document = nodesByLevel(NodeLevel.DOCUMENT).get(0);
            GraphNode email = entitiesByMetadataType("EMAIL_MESSAGE").get(0);

            TestEdge hier = edgesByType(EdgeType.HIERARCHICAL).get(0);
            assertEquals(source.getNodeId(), hier.sourceNodeId);
            assertEquals(document.getNodeId(), hier.targetNodeId);

            // ── Traversal: DOCUMENT → EMAIL_MESSAGE ─────────────────────
            TestEdge cont = edgesByType(EdgeType.CONTAINS).get(0);
            assertEquals(document.getNodeId(), cont.sourceNodeId);
            assertEquals(email.getNodeId(), cont.targetNodeId);

            // ── SENT_BY: person → email ─────────────────────────────────
            TestEdge sentByEdge = edgesByLabel("SENT_BY").get(0);
            GraphNode senderNode = nodeById(sentByEdge.sourceNodeId);
            assertEquals(NodeLevel.ENTITY, senderNode.getNodeType());
            assertEquals("Alice", senderNode.getTitle());
            assertEquals(email.getNodeId(), sentByEdge.targetNodeId);

            // ── SENT_TO: email → person ─────────────────────────────────
            Set<String> recipientNames = edgesByLabel("SENT_TO").stream()
                    .map(e -> nodeById(e.targetNodeId))
                    .map(GraphNode::getTitle)
                    .collect(Collectors.toSet());
            assertTrue(recipientNames.contains("Bob"), "Bob should be a SENT_TO recipient");
            assertTrue(recipientNames.contains("carol"), "carol should be a SENT_TO recipient");

            // ── CC_TO: email → person ───────────────────────────────────
            TestEdge ccEdge = edgesByLabel("CC_TO").get(0);
            assertEquals("Dave", nodeById(ccEdge.targetNodeId).getTitle());

            // ── HAS_ATTACHMENT: email → attachment ──────────────────────
            Set<String> attTitles = edgesByLabel("HAS_ATTACHMENT").stream()
                    .map(e -> nodeById(e.targetNodeId))
                    .map(GraphNode::getTitle)
                    .collect(Collectors.toSet());
            assertTrue(attTitles.contains("project_plan.pdf"));
            assertTrue(attTitles.contains("budget.xlsx"));
        }

        @Test
        @DisplayName("All PERSON nodes are reachable from EMAIL_MESSAGE via edges")
        void allPersonsReachableFromEmail() throws Exception {
            Document doc = emailDoc(
                    "sender@a.com",
                    "recip1@b.com, recip2@c.com",
                    "cc1@d.com",
                    "Subj", "body",
                    "/data/x.eml", null);

            invokeRouteByContentType("task-601", List.of(doc));
            invokeEmailGraphExtraction("task-601", List.of(doc));

            GraphNode email = entitiesByMetadataType("EMAIL_MESSAGE").get(0);
            List<GraphNode> persons = entitiesByMetadataType("PERSON");
            assertEquals(4, persons.size());

            for (GraphNode person : persons) {
                boolean connected = edges.stream().anyMatch(e ->
                        (e.sourceNodeId.equals(person.getNodeId()) && e.targetNodeId.equals(email.getNodeId())) ||
                        (e.sourceNodeId.equals(email.getNodeId()) && e.targetNodeId.equals(person.getNodeId()))
                );
                assertTrue(connected,
                        "PERSON " + person.getTitle() + " must be connected to EMAIL_MESSAGE");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. SNIPPET nodes — chunks under DOCUMENT
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. SNIPPET nodes — chunks connected to DOCUMENT")
    class SnippetNodes {

        @Test
        @DisplayName("createSnippetNode produces SNIPPET under DOCUMENT with HIERARCHICAL edge")
        void snippetCreation() throws Exception {
            Document doc = emailDoc(
                    "a@b.com", "c@d.com", null,
                    "Long email", "First chunk. " + "x".repeat(500),
                    "/data/long.eml", null);

            invokeRouteByContentType("task-700", List.of(doc));

            GraphNode docNode = nodesByLevel(NodeLevel.DOCUMENT).get(0);

            // Simulate what IndexSyncService does after chunking
            KnowledgeGraphService gs = buildGraphService();
            gs.createSnippetNode(docNode, "chunk-0", "First chunk content.", 0);
            gs.createSnippetNode(docNode, "chunk-1", "Second chunk content.", 1);

            List<GraphNode> snippets = nodesByLevel(NodeLevel.SNIPPET);
            assertEquals(2, snippets.size(), "Two SNIPPET nodes");

            for (GraphNode snippet : snippets) {
                boolean hasParentEdge = edges.stream().anyMatch(e ->
                        e.edgeType == EdgeType.HIERARCHICAL &&
                        e.sourceNodeId.equals(docNode.getNodeId()) &&
                        e.targetNodeId.equals(snippet.getNodeId()));
                assertTrue(hasParentEdge,
                        "SNIPPET " + snippet.getExternalId() + " must have HIERARCHICAL edge from DOCUMENT");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. LLM-extracted entities — mocked extraction results
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. LLM-extracted entities — mock extraction results")
    class LlmExtractedEntities {

        @Test
        @DisplayName("LLM-extracted entities stored as ENTITY nodes with USER_DEFINED edges")
        void llmEntitiesStoredInGraph() throws Exception {
            Document doc = emailDoc(
                    "alice@corp.com",
                    "finance-team@corp.com",
                    null,
                    "Q2 Budget Review",
                    "The Q2 budget for Project Phoenix is $2.3M. Marketing allocation increased by 15%. "
                    + "John Smith approved the revised budget on May 15th.",
                    "/data/budget-review.eml",
                    List.of("Q2_Budget.xlsx"));

            invokeRouteByContentType("task-800", List.of(doc));
            invokeEmailGraphExtraction("task-800", List.of(doc));

            // Pre-LLM state
            assertEquals(1, entitiesByMetadataType("EMAIL_MESSAGE").size());
            assertEquals(2, entitiesByMetadataType("PERSON").size());
            assertEquals(1, entitiesByMetadataType("ATTACHMENT").size());

            KnowledgeGraphService gs = buildGraphService();

            // Simulate LLM extraction results (what JpaGraphStorage.storeProposal does):
            // Triple 1: "Project Phoenix" -[HAS_BUDGET]-> "$2.3M Q2 Budget"
            GraphNode projectNode = gs.createNode(NodeLevel.ENTITY, "entity_project_phoenix",
                    "Project Phoenix", "Software project",
                    Map.of("entityType", "PROJECT"));
            GraphNode budgetNode = gs.createNode(NodeLevel.ENTITY, "entity__2_3m_q2_budget",
                    "$2.3M Q2 Budget", "Quarterly budget",
                    Map.of("entityType", "BUDGET"));
            gs.createEdge(projectNode.getNodeId(), budgetNode.getNodeId(),
                    EdgeType.USER_DEFINED, 0.92, "HAS_BUDGET");

            // Triple 2: "John Smith" -[APPROVED]-> "$2.3M Q2 Budget"
            GraphNode johnNode = gs.createNode(NodeLevel.ENTITY, "entity_john_smith",
                    "John Smith", "Budget approver",
                    Map.of("entityType", "PERSON"));
            gs.createEdge(johnNode.getNodeId(), budgetNode.getNodeId(),
                    EdgeType.USER_DEFINED, 0.88, "APPROVED");

            // Triple 3: "Board Meeting" -[DISCUSSES]-> "Project Phoenix"
            GraphNode meetingNode = gs.createNode(NodeLevel.ENTITY, "entity_board_meeting",
                    "Board Meeting", "Scheduled for June 1st",
                    Map.of("entityType", "EVENT"));
            gs.createEdge(meetingNode.getNodeId(), projectNode.getNodeId(),
                    EdgeType.USER_DEFINED, 0.85, "DISCUSSES");

            // Triple 4: "Marketing" -[PART_OF]-> "Project Phoenix"
            GraphNode mktNode = gs.createNode(NodeLevel.ENTITY, "entity_marketing",
                    "Marketing", "Department with 15% budget increase",
                    Map.of("entityType", "DEPARTMENT"));
            gs.createEdge(mktNode.getNodeId(), projectNode.getNodeId(),
                    EdgeType.USER_DEFINED, 0.80, "PART_OF");

            // ── Verify combined graph ───────────────────────────────────
            // Email: 1 EMAIL_MESSAGE + 2 PERSON + 1 ATTACHMENT = 4
            // LLM: PROJECT + BUDGET + PERSON(John) + EVENT + DEPARTMENT = 5
            // Total ENTITY = 9
            assertEquals(9, nodesByLevel(NodeLevel.ENTITY).size(), "Total ENTITY nodes");

            // LLM relationship edges
            assertEquals(1, edgesByLabel("HAS_BUDGET").size());
            assertEquals(1, edgesByLabel("APPROVED").size());
            assertEquals(1, edgesByLabel("DISCUSSES").size());
            assertEquals(1, edgesByLabel("PART_OF").size());

            // Email edges still intact
            assertEquals(1, edgesByLabel("SENT_BY").size());
            assertEquals(1, edgesByLabel("SENT_TO").size());
            assertEquals(1, edgesByLabel("HAS_ATTACHMENT").size());

            // Project Phoenix connectivity
            List<TestEdge> projectInbound = edgesTo(projectNode.getNodeId());
            assertEquals(2, projectInbound.size(), "Project Phoenix has 2 inbound (DISCUSSES, PART_OF)");
            List<TestEdge> projectOutbound = edgesFrom(projectNode.getNodeId());
            assertEquals(1, projectOutbound.size(), "Project Phoenix has 1 outbound (HAS_BUDGET)");
        }

        @Test
        @DisplayName("LLM entities coexist with email rule-based entities without duplication")
        void noEntityDuplication() throws Exception {
            Document doc = emailDoc(
                    "alice@corp.com", "bob@corp.com", null,
                    "Follow-up", "Following up on Project Alpha.",
                    "/data/followup.eml", null);

            invokeRouteByContentType("task-801", List.of(doc));
            invokeEmailGraphExtraction("task-801", List.of(doc));

            int emailEntityCount = nodesByLevel(NodeLevel.ENTITY).size();

            KnowledgeGraphService gs = buildGraphService();
            gs.createNode(NodeLevel.ENTITY, "entity_project_alpha",
                    "Project Alpha", "Internal project",
                    Map.of("entityType", "PROJECT"));

            assertEquals(emailEntityCount + 1, nodesByLevel(NodeLevel.ENTITY).size());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 8. Idempotency
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. Idempotency — no duplicates on re-extraction")
    class Idempotency {

        @Test
        @DisplayName("Running email extraction twice does not duplicate entities or edges")
        void noDuplicatesOnRerun() throws Exception {
            Document doc = emailDoc(
                    "alice@corp.com", "bob@corp.com", null,
                    "Reminder", "Don't forget the meeting.",
                    "/data/reminder.eml", List.of("agenda.pdf"));

            invokeRouteByContentType("task-900", List.of(doc));
            invokeEmailGraphExtraction("task-900", List.of(doc));

            int nodeCount = nodesById.size();
            int edgeCount = edges.size();

            invokeEmailGraphExtraction("task-900", List.of(doc));

            assertEquals(nodeCount, nodesById.size(), "Node count unchanged on re-extraction");
            assertEquals(edgeCount, edges.size(), "Edge count unchanged on re-extraction");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 9. Edge direction conventions
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. Edge directions follow convention")
    class EdgeDirections {

        @Test
        @DisplayName("All edge directions are correct per the specified convention")
        void correctDirections() throws Exception {
            Document doc = emailDoc(
                    "sender@a.com", "recip@b.com", "cc@c.com",
                    "Test", "body",
                    "/data/test.eml", List.of("file.txt"));

            invokeRouteByContentType("task-1000", List.of(doc));
            invokeEmailGraphExtraction("task-1000", List.of(doc));

            GraphNode source = nodesByLevel(NodeLevel.SOURCE).get(0);
            GraphNode document = nodesByLevel(NodeLevel.DOCUMENT).get(0);
            GraphNode email = entitiesByMetadataType("EMAIL_MESSAGE").get(0);

            // HIERARCHICAL: SOURCE → DOCUMENT
            TestEdge hier = edgesByType(EdgeType.HIERARCHICAL).get(0);
            assertEquals(source.getNodeId(), hier.sourceNodeId, "HIERARCHICAL: source→doc");
            assertEquals(document.getNodeId(), hier.targetNodeId);

            // CONTAINS: DOCUMENT → EMAIL_MESSAGE
            TestEdge cont = edgesByType(EdgeType.CONTAINS).get(0);
            assertEquals(document.getNodeId(), cont.sourceNodeId, "CONTAINS: doc→email");
            assertEquals(email.getNodeId(), cont.targetNodeId);

            // SENT_BY: PERSON → EMAIL_MESSAGE
            TestEdge sentBy = edgesByLabel("SENT_BY").get(0);
            assertNotEquals(email.getNodeId(), sentBy.sourceNodeId);
            assertEquals(email.getNodeId(), sentBy.targetNodeId);

            // SENT_TO: EMAIL_MESSAGE → PERSON
            TestEdge sentTo = edgesByLabel("SENT_TO").get(0);
            assertEquals(email.getNodeId(), sentTo.sourceNodeId);
            assertNotEquals(email.getNodeId(), sentTo.targetNodeId);

            // CC_TO: EMAIL_MESSAGE → PERSON
            TestEdge ccTo = edgesByLabel("CC_TO").get(0);
            assertEquals(email.getNodeId(), ccTo.sourceNodeId);
            assertNotEquals(email.getNodeId(), ccTo.targetNodeId);

            // HAS_ATTACHMENT: EMAIL_MESSAGE → ATTACHMENT
            TestEdge hasAtt = edgesByLabel("HAS_ATTACHMENT").get(0);
            assertEquals(email.getNodeId(), hasAtt.sourceNodeId);
            assertNotEquals(email.getNodeId(), hasAtt.targetNodeId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 10. Name parsing
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. Name parsing — display names vs bare addresses")
    class NameParsing {

        @Test
        @DisplayName("Display Name <email> format parses name correctly")
        void displayNameFormat() throws Exception {
            Document doc = emailDoc(
                    "Dr. Jane Doe <jane.doe@hospital.org>", null, null,
                    "Results", "Lab results are ready.",
                    "/data/results.eml", null);

            invokeRouteByContentType("task-1100", List.of(doc));
            invokeEmailGraphExtraction("task-1100", List.of(doc));

            List<GraphNode> persons = entitiesByMetadataType("PERSON");
            assertEquals(1, persons.size());
            assertEquals("Dr. Jane Doe", persons.get(0).getTitle());
            assertTrue(persons.get(0).getMetadataJson().contains("jane.doe@hospital.org"));
        }

        @Test
        @DisplayName("Bare email address uses local part as name")
        void bareEmailAddress() throws Exception {
            Document doc = emailDoc(
                    "john.smith@example.com", null, null,
                    "Test", "body",
                    "/data/t.eml", null);

            invokeRouteByContentType("task-1101", List.of(doc));
            invokeEmailGraphExtraction("task-1101", List.of(doc));

            List<GraphNode> persons = entitiesByMetadataType("PERSON");
            assertEquals(1, persons.size());
            assertEquals("john.smith", persons.get(0).getTitle(),
                    "Local part of email should be used as title for bare addresses");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 11. Nested relationships — multi-hop traversal across emails
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. Nested relationships — cross-email multi-hop traversal")
    class NestedRelationships {

        @Test
        @DisplayName("Same person node is shared across multiple emails (dedup by address)")
        void personDeduplicatedAcrossEmails() throws Exception {
            // Email 1: Alice sends to Bob
            Document email1 = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Initial proposal", "Here is the proposal.",
                    "/data/email1.eml", List.of("proposal.pdf"));
            // Email 2: Bob replies to Alice, cc's Carol
            Document email2 = emailDoc(
                    "Bob <bob@corp.com>", "Alice <alice@corp.com>",
                    "Carol <carol@corp.com>",
                    "Re: Initial proposal", "Looks good, adding Carol.",
                    "/data/email2.eml", null);

            invokeRouteByContentType("task-1200", List.of(email1, email2));
            invokeEmailGraphExtraction("task-1200", List.of(email1, email2));

            // Alice appears in both emails — should be ONE node, not two
            List<GraphNode> allPersons = entitiesByMetadataType("PERSON");
            long aliceCount = allPersons.stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com"))
                    .count();
            assertEquals(1, aliceCount, "Alice must be a single shared PERSON node");

            // Bob appears in both emails — also ONE node
            long bobCount = allPersons.stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com"))
                    .count();
            assertEquals(1, bobCount, "Bob must be a single shared PERSON node");

            // Total unique people: alice, bob, carol = 3
            assertEquals(3, allPersons.size(), "3 unique PERSON nodes across both emails");

            // Two EMAIL_MESSAGE nodes
            assertEquals(2, entitiesByMetadataType("EMAIL_MESSAGE").size());
        }

        @Test
        @DisplayName("Sender → EMAIL_MESSAGE → ATTACHMENT path is traversable (2 hops)")
        void senderToAttachmentPath() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Report", "See attached.",
                    "/data/report.eml", List.of("quarterly_report.pdf"));

            invokeRouteByContentType("task-1300", List.of(doc));
            invokeEmailGraphExtraction("task-1300", List.of(doc));

            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);

            // Path: Alice -[SENT_BY]→ EMAIL_MESSAGE -[HAS_ATTACHMENT]→ ATTACHMENT
            List<String> path = shortestPath(alice.getNodeId(), attachment.getNodeId(), 5);
            assertEquals(3, path.size(), "Path should be 3 nodes: sender → email → attachment");
            assertEquals(alice.getNodeId(), path.get(0));
            assertEquals(attachment.getNodeId(), path.get(2));

            // Middle node should be EMAIL_MESSAGE
            GraphNode middle = nodeById(path.get(1));
            assertTrue(middle.getMetadataJson().contains("EMAIL_MESSAGE"),
                    "Middle hop must be the EMAIL_MESSAGE node");

            List<String> labels = edgeLabelsAlongPath(path);
            assertEquals(List.of("SENT_BY", "HAS_ATTACHMENT"), labels);
        }

        @Test
        @DisplayName("Sender → EMAIL → Recipient path (sender reaches recipient in 2 hops)")
        void senderToRecipientPath() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Hello", "Hi Bob.",
                    "/data/hi.eml", null);

            invokeRouteByContentType("task-1301", List.of(doc));
            invokeEmailGraphExtraction("task-1301", List.of(doc));

            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            GraphNode bob = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com")).findFirst().orElseThrow();

            // Path: Alice -[SENT_BY]→ EMAIL_MESSAGE -[SENT_TO]→ Bob
            List<String> path = shortestPath(alice.getNodeId(), bob.getNodeId(), 5);
            assertEquals(3, path.size(), "Sender to recipient is 2 hops through EMAIL_MESSAGE");

            List<String> labels = edgeLabelsAlongPath(path);
            assertEquals(List.of("SENT_BY", "SENT_TO"), labels);
        }

        @Test
        @DisplayName("Cross-email chain: Alice→Bob (email1), Bob→Carol (email2) — Alice reaches Carol in 4 hops")
        void crossEmailChain() throws Exception {
            Document email1 = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Proposal", "Here's the proposal.",
                    "/data/chain1.eml", null);
            Document email2 = emailDoc(
                    "Bob <bob@corp.com>", "Carol <carol@corp.com>", null,
                    "Fwd: Proposal", "Forwarding Alice's proposal.",
                    "/data/chain2.eml", null);

            invokeRouteByContentType("task-1400", List.of(email1, email2));
            invokeEmailGraphExtraction("task-1400", List.of(email1, email2));

            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            GraphNode carol = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("carol@corp.com")).findFirst().orElseThrow();

            // Alice -[SENT_BY]→ Email1 -[SENT_TO]→ Bob -[SENT_BY]→ Email2 -[SENT_TO]→ Carol
            List<String> path = shortestPath(alice.getNodeId(), carol.getNodeId(), 10);
            assertEquals(5, path.size(),
                    "Alice→Carol is 4 hops: Alice → Email1 → Bob → Email2 → Carol");

            // Verify Bob is the bridge node (shared PERSON between both emails)
            GraphNode bob = nodeById(path.get(2));
            assertTrue(bob.getMetadataJson().contains("bob@corp.com"),
                    "Bridge node must be Bob");

            List<String> labels = edgeLabelsAlongPath(path);
            assertEquals(List.of("SENT_BY", "SENT_TO", "SENT_BY", "SENT_TO"), labels);
        }

        @Test
        @DisplayName("Recipient reaches attachment via EMAIL_MESSAGE (2 hops)")
        void recipientToAttachmentPath() throws Exception {
            Document doc = emailDoc(
                    "alice@corp.com", "Bob <bob@corp.com>", null,
                    "Files", "Here are the files.",
                    "/data/files.eml", List.of("data.csv", "readme.txt"));

            invokeRouteByContentType("task-1500", List.of(doc));
            invokeEmailGraphExtraction("task-1500", List.of(doc));

            GraphNode bob = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com")).findFirst().orElseThrow();

            // Bob can reach both attachments in 2 hops: Bob ←[SENT_TO]— EMAIL —[HAS_ATTACHMENT]→ ATTACHMENT
            Set<String> bobReachable = bfsReachable(bob.getNodeId(), 2);
            List<GraphNode> attachments = entitiesByMetadataType("ATTACHMENT");
            assertEquals(2, attachments.size());

            for (GraphNode att : attachments) {
                assertTrue(bobReachable.contains(att.getNodeId()),
                        "Recipient Bob should reach attachment " + att.getTitle() + " within 2 hops");
            }
        }

        @Test
        @DisplayName("CC recipient reaches sender and attachments via EMAIL_MESSAGE")
        void ccReachesSenderAndAttachments() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>",
                    "Bob <bob@corp.com>",
                    "Carol <carol@corp.com>",
                    "Review", "Please review.",
                    "/data/review.eml", List.of("draft.docx"));

            invokeRouteByContentType("task-1501", List.of(doc));
            invokeEmailGraphExtraction("task-1501", List.of(doc));

            GraphNode carol = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("carol@corp.com")).findFirst().orElseThrow();
            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);

            // Carol (CC) reaches Alice (sender) in 2 hops via EMAIL_MESSAGE
            List<String> carolToAlice = shortestPath(carol.getNodeId(), alice.getNodeId(), 5);
            assertEquals(3, carolToAlice.size(),
                    "CC→sender is 2 hops through EMAIL_MESSAGE");

            // Carol reaches attachment in 2 hops
            List<String> carolToAtt = shortestPath(carol.getNodeId(), attachment.getNodeId(), 5);
            assertEquals(3, carolToAtt.size(),
                    "CC→attachment is 2 hops through EMAIL_MESSAGE");
        }

        @Test
        @DisplayName("Full star topology: all entities reachable from EMAIL_MESSAGE in 1 hop")
        void emailMessageIsHub() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>",
                    "Bob <bob@corp.com>, Carol <carol@corp.com>",
                    "Dave <dave@corp.com>",
                    "All-hands", "Meeting notes.",
                    "/data/allhands.eml", List.of("slides.pptx", "notes.pdf"));

            invokeRouteByContentType("task-1600", List.of(doc));
            invokeEmailGraphExtraction("task-1600", List.of(doc));

            GraphNode email = entitiesByMetadataType("EMAIL_MESSAGE").get(0);

            // EMAIL_MESSAGE should be a hub — all PERSON and ATTACHMENT nodes at distance 1
            Set<String> oneHop = bfsReachable(email.getNodeId(), 1);

            List<GraphNode> persons = entitiesByMetadataType("PERSON");
            assertEquals(4, persons.size());
            for (GraphNode p : persons) {
                assertTrue(oneHop.contains(p.getNodeId()),
                        "PERSON " + p.getTitle() + " must be 1 hop from EMAIL_MESSAGE");
            }

            List<GraphNode> atts = entitiesByMetadataType("ATTACHMENT");
            assertEquals(2, atts.size());
            for (GraphNode a : atts) {
                assertTrue(oneHop.contains(a.getNodeId()),
                        "ATTACHMENT " + a.getTitle() + " must be 1 hop from EMAIL_MESSAGE");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 12. LLM entities connect to email entities — cross-layer traversal
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. LLM entities bridge to email entities — cross-layer traversal")
    class LlmCrossLayerTraversal {

        @Test
        @DisplayName("LLM-extracted entity linked to email PERSON creates a cross-layer path")
        void llmEntityLinksToEmailPerson() throws Exception {
            // Email from Alice about Project Phoenix
            Document doc = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Project Phoenix Update",
                    "The Phoenix prototype is ready for review. Bob should demo it at the board meeting.",
                    "/data/phoenix.eml", null);

            invokeRouteByContentType("task-1700", List.of(doc));
            invokeEmailGraphExtraction("task-1700", List.of(doc));

            KnowledgeGraphService gs = buildGraphService();

            // LLM extracts: "Project Phoenix" -[MANAGED_BY]→ "Alice" (same person as email sender)
            // The LLM might use the same externalId pattern or a different one.
            // Here we simulate the LLM creating its own entity for Alice,
            // then a manual merge edge connecting LLM-Alice to email-Alice.
            GraphNode projectNode = gs.createNode(NodeLevel.ENTITY, "entity_project_phoenix",
                    "Project Phoenix", "Software prototype",
                    Map.of("entityType", "PROJECT"));

            // LLM also extracts "Board Meeting" entity
            GraphNode meetingNode = gs.createNode(NodeLevel.ENTITY, "entity_board_meeting",
                    "Board Meeting", "Demo scheduled",
                    Map.of("entityType", "EVENT"));

            // LLM relationships
            gs.createEdge(projectNode.getNodeId(), meetingNode.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "PRESENTED_AT");

            // Connect LLM entity to email-extracted person (Alice manages the project)
            GraphNode alicePerson = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com"))
                    .findFirst().orElseThrow();
            gs.createEdge(alicePerson.getNodeId(), projectNode.getNodeId(),
                    EdgeType.USER_DEFINED, 0.85, "MANAGES");

            // Now verify cross-layer traversal:
            // Bob → EMAIL_MESSAGE → Alice → Project Phoenix → Board Meeting
            GraphNode bob = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com"))
                    .findFirst().orElseThrow();

            List<String> bobToMeeting = shortestPath(bob.getNodeId(), meetingNode.getNodeId(), 10);
            assertFalse(bobToMeeting.isEmpty(), "Bob must be able to reach Board Meeting");
            // Bob → Email → Alice → Project → Meeting = 5 nodes, 4 hops
            assertEquals(5, bobToMeeting.size(),
                    "Bob→Meeting is 4 hops: Bob → Email → Alice → Project → Meeting");

            List<String> labels = edgeLabelsAlongPath(bobToMeeting);
            assertEquals(List.of("SENT_TO", "SENT_BY", "MANAGES", "PRESENTED_AT"), labels);
        }

        @Test
        @DisplayName("LLM entity references attachment by name — linked via EMAIL_MESSAGE hub")
        void llmEntityReferencesAttachment() throws Exception {
            Document doc = emailDoc(
                    "alice@corp.com", "bob@corp.com", null,
                    "Budget Approval",
                    "The Q2 budget of $2.3M for Project Phoenix has been approved. See attached spreadsheet.",
                    "/data/budget.eml", List.of("Q2_Budget.xlsx"));

            invokeRouteByContentType("task-1800", List.of(doc));
            invokeEmailGraphExtraction("task-1800", List.of(doc));

            KnowledgeGraphService gs = buildGraphService();

            // LLM extracts budget entity and links to the attachment
            GraphNode budgetEntity = gs.createNode(NodeLevel.ENTITY, "entity_q2_budget",
                    "Q2 Budget", "$2.3M quarterly allocation",
                    Map.of("entityType", "BUDGET"));

            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);
            gs.createEdge(budgetEntity.getNodeId(), attachment.getNodeId(),
                    EdgeType.USER_DEFINED, 0.95, "DOCUMENTED_IN");

            // Now: sender can reach the budget entity through email→attachment→budget
            GraphNode sender = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com"))
                    .findFirst().orElseThrow();

            List<String> senderToBudget = shortestPath(sender.getNodeId(), budgetEntity.getNodeId(), 10);
            assertFalse(senderToBudget.isEmpty(), "Sender must reach budget entity");
            // sender → email → attachment → budget = 4 nodes, 3 hops
            assertEquals(4, senderToBudget.size());

            List<String> labels = edgeLabelsAlongPath(senderToBudget);
            assertEquals(List.of("SENT_BY", "HAS_ATTACHMENT", "DOCUMENTED_IN"), labels);

            // Recipient also reaches budget entity
            GraphNode recipient = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com"))
                    .findFirst().orElseThrow();

            List<String> recipToBudget = shortestPath(recipient.getNodeId(), budgetEntity.getNodeId(), 10);
            assertFalse(recipToBudget.isEmpty());
            // recipient → email → attachment → budget = 4 nodes
            assertEquals(4, recipToBudget.size());
        }

        @Test
        @DisplayName("Multi-email thread: LLM entity creates direct bridge, shorter than document hierarchy path")
        void llmBridgesEmailSubgraphs() throws Exception {
            // Email 1: Alice tells Bob about Project Phoenix
            Document email1 = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Project Phoenix", "The Phoenix prototype is ready.",
                    "/data/thread1.eml", null);

            // Email 2: Carol tells Dave about Project Phoenix (separate thread, no shared people)
            Document email2 = emailDoc(
                    "Carol <carol@other.com>", "Dave <dave@other.com>", null,
                    "Phoenix Status", "Phoenix passed QA.",
                    "/data/thread2.eml", null);

            invokeRouteByContentType("task-1900", List.of(email1, email2));
            invokeEmailGraphExtraction("task-1900", List.of(email1, email2));

            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            GraphNode dave = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("dave@other.com")).findFirst().orElseThrow();

            // Before LLM: Alice and Dave connect only through the document hierarchy
            // Alice → Email1 → DOC1 → SOURCE → DOC2 → Email2 → Dave (long path through SOURCE hub)
            List<String> preLlmPath = shortestPath(alice.getNodeId(), dave.getNodeId(), 20);
            assertFalse(preLlmPath.isEmpty(),
                    "Alice and Dave are reachable via shared SOURCE node");
            int preLlmHops = preLlmPath.size();
            assertTrue(preLlmHops > 4,
                    "Pre-LLM path should be long (through document hierarchy), was " + preLlmHops);

            // LLM extracts "Project Phoenix" from both email bodies — creates direct bridge
            KnowledgeGraphService gs = buildGraphService();
            GraphNode phoenix = gs.createNode(NodeLevel.ENTITY, "entity_project_phoenix",
                    "Project Phoenix", "Software prototype",
                    Map.of("entityType", "PROJECT"));

            gs.createEdge(alice.getNodeId(), phoenix.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "WORKS_ON");
            gs.createEdge(dave.getNodeId(), phoenix.getNodeId(),
                    EdgeType.USER_DEFINED, 0.85, "REVIEWS");

            // Now Alice→Phoenix→Dave is only 2 hops — much shorter than document hierarchy
            List<String> postLlmPath = shortestPath(alice.getNodeId(), dave.getNodeId(), 20);
            assertEquals(3, postLlmPath.size(),
                    "LLM bridge gives 2-hop path: Alice → Phoenix → Dave");
            assertTrue(postLlmPath.size() < preLlmHops,
                    "LLM bridge path must be shorter than document hierarchy path");

            List<String> labels = edgeLabelsAlongPath(postLlmPath);
            assertEquals(List.of("WORKS_ON", "REVIEWS"), labels);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 13. Cell-level mentions — ATTACHMENT → TABLE → CELL with person mentions
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. Cell-level mentions — person references cell in attachment")
    class CellLevelMentions {

        /**
         * Simulate what persistFormulaGraph does: create TABLE (sheet) nodes under
         * an ATTACHMENT, then CELL entity nodes under the TABLE, connected by CONTAINS.
         */
        private Map<String, GraphNode> buildSpreadsheetGraph(
                KnowledgeGraphService gs, GraphNode attachmentNode,
                String sheetName, List<String[]> cells) {

            Map<String, GraphNode> nodeMap = new LinkedHashMap<>();

            // TABLE node for the sheet
            String tableExtId = "table:" + attachmentNode.getExternalId() + ":" + sheetName;
            GraphNode tableNode = gs.createNode(NodeLevel.TABLE, tableExtId,
                    sheetName, "Spreadsheet sheet",
                    Map.of("entity_subtype", "sheet", "rowCount", String.valueOf(cells.size())));
            nodeMap.put("TABLE:" + sheetName, tableNode);

            // ATTACHMENT → TABLE via CONTAINS
            gs.createEdge(attachmentNode.getNodeId(), tableNode.getNodeId(),
                    EdgeType.CONTAINS, 1.0, "Document contains sheet " + sheetName);

            // CELL entity nodes under the TABLE
            for (String[] cell : cells) {
                String cellRef = cell[0];
                String cellTitle = cell[1];
                String cellValue = cell[2];

                String cellExtId = "cell:" + cellRef;
                Map<String, Object> cellMeta = new LinkedHashMap<>();
                cellMeta.put("entity_type", "CELL");
                cellMeta.put("entity_subtype", "cell");
                cellMeta.put("cell_reference", cellRef);
                cellMeta.put("display_value", cellValue);
                cellMeta.put("source_path", attachmentNode.getExternalId());

                GraphNode cellNode = gs.createNode(NodeLevel.ENTITY, cellExtId,
                        cellTitle, cellValue, cellMeta);
                nodeMap.put("CELL:" + cellRef, cellNode);

                // TABLE → CELL via CONTAINS
                gs.createEdge(tableNode.getNodeId(), cellNode.getNodeId(),
                        EdgeType.CONTAINS, 1.0, "Table contains cell " + cellRef);
            }

            return nodeMap;
        }

        @Test
        @DisplayName("ATTACHMENT → TABLE → CELL hierarchy is correct")
        void attachmentTableCellHierarchy() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Q2 Financials", "See attached spreadsheet.",
                    "/data/financials.eml", List.of("Q2_Budget.xlsx"));

            invokeRouteByContentType("task-2100", List.of(doc));
            invokeEmailGraphExtraction("task-2100", List.of(doc));

            KnowledgeGraphService gs = buildGraphService();
            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);

            Map<String, GraphNode> spreadsheet = buildSpreadsheetGraph(gs, attachment,
                    "Revenue", List.of(
                            new String[]{"Revenue!A1", "Header: Department", "Department"},
                            new String[]{"Revenue!B1", "Header: Amount", "Amount"},
                            new String[]{"Revenue!A2", "Marketing", "Marketing"},
                            new String[]{"Revenue!B2", "Q2 Revenue", "$2.3M"}
                    ));

            GraphNode tableNode = spreadsheet.get("TABLE:Revenue");
            assertNotNull(tableNode);
            assertEquals(NodeLevel.TABLE, tableNode.getNodeType());

            // CONTAINS edge: ATTACHMENT → TABLE
            assertTrue(edges.stream().anyMatch(e ->
                    e.sourceNodeId.equals(attachment.getNodeId())
                    && e.targetNodeId.equals(tableNode.getNodeId())
                    && e.edgeType == EdgeType.CONTAINS));

            // CONTAINS edge: TABLE → CELL
            GraphNode cellB2 = spreadsheet.get("CELL:Revenue!B2");
            assertTrue(edges.stream().anyMatch(e ->
                    e.sourceNodeId.equals(tableNode.getNodeId())
                    && e.targetNodeId.equals(cellB2.getNodeId())
                    && e.edgeType == EdgeType.CONTAINS));

            // All 4 cells reachable from TABLE in 1 hop
            Set<String> tableNeighbors = bfsReachable(tableNode.getNodeId(), 1);
            long cellsReachable = tableNeighbors.stream()
                    .map(id -> nodeById(id))
                    .filter(n -> n.getMetadataJson() != null
                            && n.getMetadataJson().contains("\"entity_subtype\":\"cell\""))
                    .count();
            assertEquals(4, cellsReachable, "All 4 cells reachable from TABLE in 1 hop");
        }

        @Test
        @DisplayName("Person mentions cell value — LLM creates MENTIONS edge to cell")
        void personMentionsCellValue() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Budget Discussion",
                    "The Q2 revenue is $2.3M — see cell B2 in the Revenue sheet.",
                    "/data/budget-discuss.eml", List.of("Q2_Budget.xlsx"));

            invokeRouteByContentType("task-2200", List.of(doc));
            invokeEmailGraphExtraction("task-2200", List.of(doc));

            KnowledgeGraphService gs = buildGraphService();
            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);

            Map<String, GraphNode> spreadsheet = buildSpreadsheetGraph(gs, attachment,
                    "Revenue", List.of(
                            new String[]{"Revenue!B2", "Q2 Revenue", "$2.3M"},
                            new String[]{"Revenue!B3", "Q2 Expenses", "$1.8M"},
                            new String[]{"Revenue!B4", "Net Profit", "$0.5M"}
                    ));

            GraphNode cellB2 = spreadsheet.get("CELL:Revenue!B2");

            // LLM extraction: Alice MENTIONS the cell value $2.3M
            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            gs.createEdge(alice.getNodeId(), cellB2.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "MENTIONS");

            List<TestEdge> mentions = edgesByLabel("MENTIONS");
            assertEquals(1, mentions.size());
            assertEquals(alice.getNodeId(), mentions.get(0).sourceNodeId);
            assertEquals(cellB2.getNodeId(), mentions.get(0).targetNodeId);

            // Direct path: Alice → Cell B2 in 1 hop
            List<String> aliceToCell = shortestPath(alice.getNodeId(), cellB2.getNodeId(), 5);
            assertEquals(2, aliceToCell.size(), "Direct MENTIONS edge: 1 hop");
        }

        @Test
        @DisplayName("Full chain: Sender → Email → Attachment → Table → Cell ← MENTIONS ← Person")
        void fullCellMentionChain() throws Exception {
            Document doc = emailDoc(
                    "CFO <cfo@corp.com>",
                    "Alice <alice@corp.com>, Bob <bob@corp.com>",
                    null,
                    "Q2 Budget Approved",
                    "I've approved the $2.3M budget in cell B2. Marketing $500K in B3 is also approved.",
                    "/data/approved.eml", List.of("Q2_Budget.xlsx"));

            invokeRouteByContentType("task-2300", List.of(doc));
            invokeEmailGraphExtraction("task-2300", List.of(doc));

            KnowledgeGraphService gs = buildGraphService();
            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);
            GraphNode emailMsg = entitiesByMetadataType("EMAIL_MESSAGE").get(0);

            Map<String, GraphNode> spreadsheet = buildSpreadsheetGraph(gs, attachment,
                    "Budget", List.of(
                            new String[]{"Budget!B2", "Total Budget", "$2.3M"},
                            new String[]{"Budget!B3", "Marketing Allocation", "$500K"},
                            new String[]{"Budget!B4", "Engineering", "$1.2M"}
                    ));

            GraphNode cellB2 = spreadsheet.get("CELL:Budget!B2");
            GraphNode cellB3 = spreadsheet.get("CELL:Budget!B3");
            GraphNode tableNode = spreadsheet.get("TABLE:Budget");

            // LLM: CFO mentions both cells
            GraphNode cfo = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("cfo@corp.com")).findFirst().orElseThrow();
            gs.createEdge(cfo.getNodeId(), cellB2.getNodeId(),
                    EdgeType.USER_DEFINED, 0.92, "MENTIONS");
            gs.createEdge(cfo.getNodeId(), cellB3.getNodeId(),
                    EdgeType.USER_DEFINED, 0.88, "MENTIONS");

            // LLM: Budget entity linked to cell
            GraphNode budgetEntity = gs.createNode(NodeLevel.ENTITY, "entity_q2_budget",
                    "Q2 Budget", "Approved budget",
                    Map.of("entityType", "BUDGET"));
            gs.createEdge(cfo.getNodeId(), budgetEntity.getNodeId(),
                    EdgeType.USER_DEFINED, 0.95, "APPROVED");
            gs.createEdge(budgetEntity.getNodeId(), cellB2.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "VALUED_AT");

            // 1. EMAIL_MESSAGE can reach CELL
            //    Hierarchy path: Email → Attachment → Table → Cell (3 hops)
            //    But MENTIONS shortcut: Email → CFO → Cell (2 hops via SENT_BY + MENTIONS)
            List<String> emailToCell = shortestPath(emailMsg.getNodeId(), cellB2.getNodeId(), 10);
            assertFalse(emailToCell.isEmpty(), "EMAIL_MESSAGE must reach Cell B2");
            assertTrue(emailToCell.size() <= 4,
                    "Email reaches Cell in ≤3 hops (via MENTIONS shortcut or hierarchy)");

            // 2. CFO → Cell B2 direct (1 hop via MENTIONS)
            List<String> cfoToCell = shortestPath(cfo.getNodeId(), cellB2.getNodeId(), 5);
            assertEquals(2, cfoToCell.size(), "CFO → Cell B2 is 1 hop via MENTIONS");

            // 3. Budget → Cell B2 direct (1 hop via VALUED_AT)
            List<String> budgetToCell = shortestPath(budgetEntity.getNodeId(), cellB2.getNodeId(), 5);
            assertEquals(2, budgetToCell.size(), "Budget → Cell B2 is 1 hop via VALUED_AT");

            // 4. Bob (recipient) reaches cell: Bob → Email → CFO → Cell B2 (3 hops, shortest)
            GraphNode bob = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com")).findFirst().orElseThrow();
            List<String> bobToCell = shortestPath(bob.getNodeId(), cellB2.getNodeId(), 10);
            assertTrue(bobToCell.size() <= 4,
                    "Bob reaches Cell B2 in ≤3 hops (via CFO's MENTIONS edge)");

            // 5. Unmentioned cell B4 only reachable via hierarchy (no MENTIONS shortcut)
            GraphNode cellB4 = spreadsheet.get("CELL:Budget!B4");
            List<String> cfoToB4 = shortestPath(cfo.getNodeId(), cellB4.getNodeId(), 10);
            assertFalse(cfoToB4.isEmpty(), "CFO reaches unmentioned cell via hierarchy");
            assertTrue(cfoToB4.size() > 2,
                    "Unmentioned cell requires >1 hop (no direct MENTIONS edge)");
        }

        @Test
        @DisplayName("Multiple people mention same cell — cell becomes a hub")
        void multiplePeopleMentionSameCell() throws Exception {
            Document email1 = emailDoc(
                    "Alice <alice@corp.com>", "team@corp.com", null,
                    "Revenue Target", "We need to hit $10M in cell C5.",
                    "/data/target1.eml", List.of("targets.xlsx"));
            Document email2 = emailDoc(
                    "Bob <bob@corp.com>", "team@corp.com", null,
                    "Re: Revenue Target", "I agree, the $10M in C5 is ambitious.",
                    "/data/target2.eml", null);

            invokeRouteByContentType("task-2400", List.of(email1, email2));
            invokeEmailGraphExtraction("task-2400", List.of(email1, email2));

            KnowledgeGraphService gs = buildGraphService();
            GraphNode attachment = entitiesByMetadataType("ATTACHMENT").get(0);

            Map<String, GraphNode> spreadsheet = buildSpreadsheetGraph(gs, attachment,
                    "Targets", List.<String[]>of(
                            new String[]{"Targets!C5", "Revenue Target", "$10M"}
                    ));

            GraphNode cellC5 = spreadsheet.get("CELL:Targets!C5");

            // Both Alice and Bob mention the same cell
            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            GraphNode bob = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com")).findFirst().orElseThrow();

            gs.createEdge(alice.getNodeId(), cellC5.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "MENTIONS");
            gs.createEdge(bob.getNodeId(), cellC5.getNodeId(),
                    EdgeType.USER_DEFINED, 0.85, "MENTIONS");

            // Cell C5 is now a hub connecting Alice and Bob
            assertEquals(2, edgesByLabel("MENTIONS").size());

            Set<String> cellNeighbors = bfsReachable(cellC5.getNodeId(), 1);
            assertTrue(cellNeighbors.contains(alice.getNodeId()),
                    "Alice reachable from cell C5 in 1 hop");
            assertTrue(cellNeighbors.contains(bob.getNodeId()),
                    "Bob reachable from cell C5 in 1 hop");
        }

        @Test
        @DisplayName("Cross-attachment cell reference: cell in one attachment references cell in another")
        void crossAttachmentCellReference() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>", "Bob <bob@corp.com>", null,
                    "Budget vs Actuals",
                    "Budget cell B2 ($2.3M) feeds into the actuals report.",
                    "/data/budget-actuals.eml",
                    List.of("budget.xlsx", "actuals.xlsx"));

            invokeRouteByContentType("task-2500", List.of(doc));
            invokeEmailGraphExtraction("task-2500", List.of(doc));

            KnowledgeGraphService gs = buildGraphService();
            List<GraphNode> attachments = entitiesByMetadataType("ATTACHMENT");
            assertEquals(2, attachments.size());

            GraphNode budgetAtt = attachments.stream()
                    .filter(a -> a.getTitle().equals("budget.xlsx")).findFirst().orElseThrow();
            GraphNode actualsAtt = attachments.stream()
                    .filter(a -> a.getTitle().equals("actuals.xlsx")).findFirst().orElseThrow();

            Map<String, GraphNode> budgetCells = buildSpreadsheetGraph(gs, budgetAtt,
                    "Budget", List.<String[]>of(
                            new String[]{"Budget!B2", "Total Budget", "$2.3M"}
                    ));
            Map<String, GraphNode> actualsCells = buildSpreadsheetGraph(gs, actualsAtt,
                    "Actuals", List.<String[]>of(
                            new String[]{"Actuals!A1", "Budget Reference", "$2.3M"}
                    ));

            GraphNode budgetCell = budgetCells.get("CELL:Budget!B2");
            GraphNode actualsCell = actualsCells.get("CELL:Actuals!A1");

            // Actuals cell REFERENCES budget cell
            gs.createEdge(actualsCell.getNodeId(), budgetCell.getNodeId(),
                    EdgeType.USER_DEFINED, 0.85, "REFERENCES");

            // Alice mentions the budget cell
            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            gs.createEdge(alice.getNodeId(), budgetCell.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "MENTIONS");

            // Alice → Budget Cell → Actuals Cell (2 hops)
            List<String> aliceToActuals = shortestPath(alice.getNodeId(), actualsCell.getNodeId(), 10);
            assertEquals(3, aliceToActuals.size(),
                    "Alice → Budget Cell → Actuals Cell is 2 hops");
            assertEquals(List.of("MENTIONS", "REFERENCES"),
                    edgeLabelsAlongPath(aliceToActuals));

            // Both attachments connected through cross-cell REFERENCES
            List<String> budgetToActualsAtt = shortestPath(budgetAtt.getNodeId(), actualsAtt.getNodeId(), 10);
            assertFalse(budgetToActualsAtt.isEmpty(),
                    "Budget attachment connects to Actuals attachment via cell references");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 14. Full end-to-end: DOCUMENT → SNIPPET → LLM entities form complete hierarchy
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. Complete hierarchy with snippets and LLM entities")
    class CompleteHierarchyWithSnippets {

        @Test
        @DisplayName("SOURCE → DOCUMENT → EMAIL_MESSAGE → PERSON chain + DOCUMENT → SNIPPET + LLM entities all connected")
        void fullPipelineHierarchy() throws Exception {
            Document doc = emailDoc(
                    "Alice <alice@corp.com>",
                    "Bob <bob@corp.com>",
                    null,
                    "Q2 Review",
                    "The Q2 results for Project Phoenix show 15% growth. John approved the budget.",
                    "/data/q2.eml",
                    List.of("financials.xlsx"));

            invokeRouteByContentType("task-2000", List.of(doc));
            invokeEmailGraphExtraction("task-2000", List.of(doc));

            // Simulate snippet creation (post-chunking)
            GraphNode docNode = nodesByLevel(NodeLevel.DOCUMENT).get(0);
            KnowledgeGraphService gs = buildGraphService();
            GraphNode chunk0 = gs.createSnippetNode(docNode,
                    "chunk-0", "The Q2 results for Project Phoenix show 15% growth.", 0);
            GraphNode chunk1 = gs.createSnippetNode(docNode,
                    "chunk-1", "John approved the budget.", 1);

            // Simulate LLM entity extraction from chunks
            GraphNode phoenix = gs.createNode(NodeLevel.ENTITY, "entity_project_phoenix",
                    "Project Phoenix", "Growth project",
                    Map.of("entityType", "PROJECT"));
            GraphNode john = gs.createNode(NodeLevel.ENTITY, "entity_john",
                    "John", "Budget approver",
                    Map.of("entityType", "PERSON"));
            gs.createEdge(phoenix.getNodeId(), john.getNodeId(),
                    EdgeType.USER_DEFINED, 0.88, "BUDGET_APPROVED_BY");

            // Connect LLM entities to email-extracted PERSON for Alice (manages Phoenix)
            GraphNode alice = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("alice@corp.com")).findFirst().orElseThrow();
            gs.createEdge(alice.getNodeId(), phoenix.getNodeId(),
                    EdgeType.USER_DEFINED, 0.9, "REPORTS_ON");

            // ── Verify complete hierarchy ───────────────────────────────
            GraphNode source = nodesByLevel(NodeLevel.SOURCE).get(0);
            GraphNode document = nodesByLevel(NodeLevel.DOCUMENT).get(0);

            // 1. SOURCE → DOCUMENT (HIERARCHICAL)
            assertTrue(edges.stream().anyMatch(e ->
                    e.edgeType == EdgeType.HIERARCHICAL
                    && e.sourceNodeId.equals(source.getNodeId())
                    && e.targetNodeId.equals(document.getNodeId())));

            // 2. DOCUMENT → SNIPPET (HIERARCHICAL) x2
            long snippetEdges = edges.stream().filter(e ->
                    e.edgeType == EdgeType.HIERARCHICAL
                    && e.sourceNodeId.equals(document.getNodeId())
                    && nodeById(e.targetNodeId).getNodeType() == NodeLevel.SNIPPET).count();
            assertEquals(2, snippetEdges);

            // 3. DOCUMENT → EMAIL_MESSAGE (CONTAINS)
            GraphNode emailMsg = entitiesByMetadataType("EMAIL_MESSAGE").get(0);
            assertTrue(edges.stream().anyMatch(e ->
                    e.edgeType == EdgeType.CONTAINS
                    && e.sourceNodeId.equals(document.getNodeId())
                    && e.targetNodeId.equals(emailMsg.getNodeId())));

            // 4. EMAIL_MESSAGE → PERSON/ATTACHMENT (star)
            Set<String> emailNeighbors = bfsReachable(emailMsg.getNodeId(), 1);
            assertTrue(emailNeighbors.contains(alice.getNodeId()));
            GraphNode bob = entitiesByMetadataType("PERSON").stream()
                    .filter(p -> p.getMetadataJson().contains("bob@corp.com")).findFirst().orElseThrow();
            assertTrue(emailNeighbors.contains(bob.getNodeId()));
            GraphNode att = entitiesByMetadataType("ATTACHMENT").get(0);
            assertTrue(emailNeighbors.contains(att.getNodeId()));

            // 5. LLM subgraph: Alice → Phoenix → John
            List<String> aliceToJohn = shortestPath(alice.getNodeId(), john.getNodeId(), 10);
            assertEquals(3, aliceToJohn.size(),
                    "Alice→John is 2 hops through Project Phoenix");
            assertEquals(List.of("REPORTS_ON", "BUDGET_APPROVED_BY"),
                    edgeLabelsAlongPath(aliceToJohn));

            // 6. Cross-layer: Bob (recipient) can reach LLM entity John
            //    Bob → Email → Alice → Phoenix → John = 5 nodes
            List<String> bobToJohn = shortestPath(bob.getNodeId(), john.getNodeId(), 10);
            assertEquals(5, bobToJohn.size(),
                    "Bob→John is 4 hops: Bob→Email→Alice→Phoenix→John");

            // 7. DOCUMENT reachable from any entity via edge traversal
            // Source is at the root — everything should be connected
            Set<String> fromSource = bfsReachable(source.getNodeId(), 10);
            assertTrue(fromSource.contains(document.getNodeId()));
            assertTrue(fromSource.contains(emailMsg.getNodeId()));
            assertTrue(fromSource.contains(alice.getNodeId()));
            assertTrue(fromSource.contains(bob.getNodeId()));
            assertTrue(fromSource.contains(att.getNodeId()));
            assertTrue(fromSource.contains(chunk0.getNodeId()));
            assertTrue(fromSource.contains(chunk1.getNodeId()));
            // LLM entities connected via Alice
            assertTrue(fromSource.contains(phoenix.getNodeId()),
                    "LLM entity Project Phoenix must be reachable from SOURCE");
            assertTrue(fromSource.contains(john.getNodeId()),
                    "LLM entity John must be reachable from SOURCE");
        }
    }
}
