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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the gmail.* namespace fallback in {@link EmailGraphExtractor#applyEmailGraphExtraction}
 * (extracted from the crawl impl into its own collaborator).
 *
 * <p>Gmail/GWorkspace documents use {@code gmail.from}, {@code gmail.to}, {@code gmail.cc},
 * {@code gmail.subject}, and {@code gmail.attachments} metadata keys. The method should fall back
 * to these keys when the canonical {@code email.*} keys are absent, creating the same PERSON
 * entities and SENT_BY / SENT_TO / CC_TO / HAS_ATTACHMENT relationships.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailGraphGmailNamespaceTest {

    @Mock private KnowledgeGraphService knowledgeGraphService;

    private EmailGraphExtractor extractor;

    @BeforeEach
    void setUp() throws Exception {
        // Default stub: no pre-existing nodes or edges
        // P1: production calls 3-arg getNodeByExternalId(externalId, NodeLevel, factSheetId)
        when(knowledgeGraphService.getNodeByExternalId(anyString(), any(NodeLevel.class), any()))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.edgeExists(anyString(), anyString()))
                .thenReturn(false);

        // P1: production calls 6-arg createNode(NodeLevel, externalId, title, desc, metadata, factSheetId)
        when(knowledgeGraphService.createNode(any(NodeLevel.class), anyString(), anyString(), any(), anyMap(), any()))
                .thenAnswer(inv -> GraphNode.builder()
                        .nodeId(UUID.randomUUID().toString())
                        .externalId(inv.getArgument(1))
                        .nodeType(inv.getArgument(0))
                        .title(inv.getArgument(2))
                        .build());

        // P2: production calls 9-arg createEdgeWithMetadata; stub it to return a GraphEdge
        when(knowledgeGraphService.createEdgeWithMetadata(anyString(), anyString(), any(EdgeType.class),
                anyDouble(), anyString(), any(), any(), any(EdgeProvenance.class), any()))
                .thenAnswer(inv -> GraphEdge.builder()
                        .edgeId(UUID.randomUUID().toString())
                        .edgeType(inv.getArgument(2))
                        .build());

        // Wire the extractor the way the crawl slice does, without Spring (@Autowired fields).
        CrawlDocumentTracker tracker = new CrawlDocumentTracker();
        GraphPersistenceHelper persistence = new GraphPersistenceHelper();
        injectField(persistence, "knowledgeGraphService", knowledgeGraphService);
        injectField(persistence, "documentTracker", tracker);
        extractor = new EmailGraphExtractor();
        injectField(extractor, "knowledgeGraphService", knowledgeGraphService);
        injectField(extractor, "graphPersistenceHelper", persistence);
        injectField(extractor, "documentTracker", tracker);
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    private void invokeEmailGraphExtraction(String taskId, List<Document> docs) throws Exception {
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .jobId(taskId)
                .request(UnifiedCrawlRequest.builder().sources(List.of()).build())
                .build();
        extractor.applyEmailGraphExtraction(job, docs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: gmail.from creates a PERSON entity and SENT_BY relationship
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gmailFrom_createsPersonEntityAndSentByRelationship() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.from", "Carol <carol@gmail.com>");
        meta.put("gmail.subject", "Hello");
        Document doc = new Document("Email body", meta);

        invokeEmailGraphExtraction("task-1", List.of(doc));

        // Capture all createNode calls and confirm at least one has entity_type = EMAIL_MESSAGE
        // and at least one has entity_type = PERSON
        // P1: verify the 6-arg overload now used by production
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(2)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture(), any());

        List<Map<String, Object>> capturedMetas = metaCaptor.getAllValues();
        boolean hasEmailMessage = capturedMetas.stream()
                .anyMatch(m -> "EMAIL_MESSAGE".equals(m.get("entity_type")));
        boolean hasPerson = capturedMetas.stream()
                .anyMatch(m -> "PERSON".equals(m.get("entity_type")));
        assertTrue(hasEmailMessage, "Expected an EMAIL_MESSAGE entity to be created");
        assertTrue(hasPerson, "Expected a PERSON entity for the sender to be created");

        // P2: A SENT_BY edge should have been created via createEdgeWithMetadata
        verify(knowledgeGraphService, atLeast(1))
                .createEdgeWithMetadata(anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                        eq(1.0), eq("SENT_BY"), any(), any(), any(EdgeProvenance.class), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: gmail.to creates SENT_TO relationships for all recipients
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gmailTo_createsSentToRelationship() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.from", "alice@test.com");
        meta.put("gmail.to", "bob@test.com, charlie@test.com");
        Document doc = new Document("Email body", meta);

        invokeEmailGraphExtraction("task-2", List.of(doc));

        // Expect three PERSON nodes: alice, bob, charlie
        // P1: verify the 6-arg overload now used by production
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(3)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture(), any());

        long personCount = metaCaptor.getAllValues().stream()
                .filter(m -> "PERSON".equals(m.get("entity_type")))
                .count();
        assertEquals(3, personCount, "Expected PERSON nodes for alice, bob, and charlie");

        // P2: Verify SENT_BY edge (alice → email) and SENT_TO edges (email → bob, email → charlie)
        verify(knowledgeGraphService, times(1))
                .createEdgeWithMetadata(anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                        eq(1.0), eq("SENT_BY"), any(), any(), any(EdgeProvenance.class), any());
        verify(knowledgeGraphService, times(2))
                .createEdgeWithMetadata(anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                        eq(1.0), eq("SENT_TO"), any(), any(), any(EdgeProvenance.class), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: gmail.cc creates CC_TO relationship
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gmailCc_createsCcToRelationship() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.from", "a@b.com");
        meta.put("gmail.cc", "x@y.com");
        Document doc = new Document("Email body", meta);

        invokeEmailGraphExtraction("task-3", List.of(doc));

        // P2: Verify a CC_TO edge was created via createEdgeWithMetadata
        verify(knowledgeGraphService, times(1))
                .createEdgeWithMetadata(anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                        eq(1.0), eq("CC_TO"), any(), any(), any(EdgeProvenance.class), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: gmail.attachments (String) creates ATTACHMENT nodes + HAS_ATTACHMENT edges
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gmailAttachments_createsAttachmentNodes() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.from", "a@b.com");
        meta.put("gmail.attachments", "report.pdf,data.xlsx");
        Document doc = new Document("Email body", meta);

        invokeEmailGraphExtraction("task-4", List.of(doc));

        // P1: Verify ATTACHMENT entities were created via metadata (6-arg createNode)
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(1)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture(), any());

        long attachCount = metaCaptor.getAllValues().stream()
                .filter(m -> "ATTACHMENT".equals(m.get("entity_type")))
                .count();
        assertEquals(2, attachCount, "Expected two ATTACHMENT entity nodes (report.pdf and data.xlsx)");

        // P2: Verify HAS_ATTACHMENT edges via createEdgeWithMetadata
        verify(knowledgeGraphService, times(2))
                .createEdgeWithMetadata(anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                        eq(1.0), eq("HAS_ATTACHMENT"), any(), any(), any(EdgeProvenance.class), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: email.* takes precedence over gmail.*
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void emailNamespaceTakesPrecedenceOverGmail() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        // Both namespaces present — email.* should win
        meta.put("email.from", "alice@a.com");
        meta.put("gmail.from", "bob@b.com");
        Document doc = new Document("Email body", meta);

        invokeEmailGraphExtraction("task-5", List.of(doc));

        // P1: Capture all createNode calls (6-arg overload)
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(1)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture(), any());

        // The PERSON node's email metadata should be alice@a.com, NOT bob@b.com
        boolean alicePresent = metaCaptor.getAllValues().stream()
                .anyMatch(m -> "alice@a.com".equals(m.get("email")));
        boolean bobPresent = metaCaptor.getAllValues().stream()
                .anyMatch(m -> "bob@b.com".equals(m.get("email")));

        assertTrue(alicePresent, "Expected alice@a.com (email.* namespace) to be the sender");
        assertFalse(bobPresent, "bob@b.com (gmail.* namespace) should NOT be used when email.* is present");
    }
}
