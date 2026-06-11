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

package ai.kompile.app.services;

import ai.kompile.crawl.graph.UnifiedCrawlGraphServiceImpl;
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
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the gmail.* namespace fallback in {@link UnifiedCrawlGraphServiceImpl#applyEmailGraphExtraction}.
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

    private UnifiedCrawlGraphServiceImpl crawlService;

    /** Reflective handle to the private {@code applyEmailGraphExtraction} method. */
    private Method applyEmailGraphExtraction;

    @BeforeEach
    void setUp() throws Exception {
        // Default stub: no pre-existing nodes or edges
        when(knowledgeGraphService.getNodeByExternalId(anyString(), any(NodeLevel.class)))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.edgeExists(anyString(), anyString()))
                .thenReturn(false);

        // createNode returns a unique GraphNode per call (nodeId based on first arg combo)
        when(knowledgeGraphService.createNode(any(NodeLevel.class), anyString(), anyString(), any(), anyMap()))
                .thenAnswer(inv -> GraphNode.builder()
                        .nodeId(UUID.randomUUID().toString())
                        .externalId(inv.getArgument(1))
                        .nodeType(inv.getArgument(0))
                        .title(inv.getArgument(2))
                        .build());

        // createEdge returns a stub GraphEdge
        when(knowledgeGraphService.createEdge(anyString(), anyString(), any(EdgeType.class), anyDouble(), anyString()))
                .thenAnswer(inv -> GraphEdge.builder()
                        .edgeId(UUID.randomUUID().toString())
                        .edgeType(inv.getArgument(2))
                        .build());

        // Build service via field injection (UnifiedCrawlGraphServiceImpl uses @Autowired fields)
        crawlService = new UnifiedCrawlGraphServiceImpl();
        injectField(crawlService, "knowledgeGraphService", knowledgeGraphService);

        // Obtain reflective access to the private method once
        applyEmailGraphExtraction = UnifiedCrawlGraphServiceImpl.class
                .getDeclaredMethod("applyEmailGraphExtraction", String.class, List.class);
        applyEmailGraphExtraction.setAccessible(true);
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
        applyEmailGraphExtraction.invoke(crawlService, taskId, docs);
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
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(2)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture());

        List<Map<String, Object>> capturedMetas = metaCaptor.getAllValues();
        boolean hasEmailMessage = capturedMetas.stream()
                .anyMatch(m -> "EMAIL_MESSAGE".equals(m.get("entity_type")));
        boolean hasPerson = capturedMetas.stream()
                .anyMatch(m -> "PERSON".equals(m.get("entity_type")));
        assertTrue(hasEmailMessage, "Expected an EMAIL_MESSAGE entity to be created");
        assertTrue(hasPerson, "Expected a PERSON entity for the sender to be created");

        // A SENT_BY edge should have been created
        verify(knowledgeGraphService, atLeast(1))
                .createEdge(anyString(), anyString(), eq(EdgeType.USER_DEFINED), eq(1.0), eq("SENT_BY"));
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
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(3)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture());

        long personCount = metaCaptor.getAllValues().stream()
                .filter(m -> "PERSON".equals(m.get("entity_type")))
                .count();
        assertEquals(3, personCount, "Expected PERSON nodes for alice, bob, and charlie");

        // Verify SENT_BY edge (alice → email) and SENT_TO edges (email → bob, email → charlie)
        verify(knowledgeGraphService, times(1))
                .createEdge(anyString(), anyString(), eq(EdgeType.USER_DEFINED), eq(1.0), eq("SENT_BY"));
        verify(knowledgeGraphService, times(2))
                .createEdge(anyString(), anyString(), eq(EdgeType.USER_DEFINED), eq(1.0), eq("SENT_TO"));
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

        // Verify a CC_TO edge was created
        verify(knowledgeGraphService, times(1))
                .createEdge(anyString(), anyString(), eq(EdgeType.USER_DEFINED), eq(1.0), eq("CC_TO"));
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

        // Verify ATTACHMENT entities were created via metadata
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(1)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture());

        long attachCount = metaCaptor.getAllValues().stream()
                .filter(m -> "ATTACHMENT".equals(m.get("entity_type")))
                .count();
        assertEquals(2, attachCount, "Expected two ATTACHMENT entity nodes (report.pdf and data.xlsx)");

        // Verify HAS_ATTACHMENT edges
        verify(knowledgeGraphService, times(2))
                .createEdge(anyString(), anyString(), eq(EdgeType.USER_DEFINED), eq(1.0), eq("HAS_ATTACHMENT"));
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

        // Capture all createNode calls
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService, atLeast(1)).createNode(
                eq(NodeLevel.ENTITY), anyString(), anyString(), anyString(), metaCaptor.capture());

        // The PERSON node's email metadata should be alice@a.com, NOT bob@b.com
        boolean alicePresent = metaCaptor.getAllValues().stream()
                .anyMatch(m -> "alice@a.com".equals(m.get("email")));
        boolean bobPresent = metaCaptor.getAllValues().stream()
                .anyMatch(m -> "bob@b.com".equals(m.get("email")));

        assertTrue(alicePresent, "Expected alice@a.com (email.* namespace) to be the sender");
        assertFalse(bobPresent, "bob@b.com (gmail.* namespace) should NOT be used when email.* is present");
    }
}
