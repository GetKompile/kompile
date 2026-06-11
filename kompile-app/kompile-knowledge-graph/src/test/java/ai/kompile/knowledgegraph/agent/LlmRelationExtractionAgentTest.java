/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionResult;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmRelationExtractionAgent}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmRelationExtractionAgentTest {

    @Mock
    private ExtractionLlmServiceRegistry registry;

    @Mock
    private ExtractionLlmService llmService;

    private LlmRelationExtractionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new LlmRelationExtractionAgent();
        when(llmService.getId()).thenReturn("test-llm");
        when(llmService.isAvailable()).thenReturn(true);
    }

    @Test
    void getIdReturnsLlmActive() {
        assertEquals("llm-active", agent.getId());
    }

    @Test
    void getDescriptionIsNonEmpty() {
        assertFalse(agent.getDescription().isBlank());
    }

    @Test
    void supportedContentTypesIsEmpty() {
        // Empty means "any content type"
        assertTrue(agent.supportedContentTypes().isEmpty());
    }

    @Test
    void extractWithNullChunksReturnsEmptyGraph() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        ExtractionResult result = agent.extract(null, null);

        assertNotNull(result);
        assertNotNull(result.graph());
        assertNotNull(result.graph().getEntities());
        assertTrue(result.graph().getEntities().isEmpty());
    }

    @Test
    void extractWithEmptyChunksReturnsEmptyGraph() {
        agent.setLlmServiceRegistry(registry);

        ExtractionResult result = agent.extract(Collections.emptyList(), null);

        assertNotNull(result);
        assertEquals(0, result.graph().getEntities().size());
        assertEquals(0, result.graph().getRelationships().size());
    }

    @Test
    void extractWithNoRegistryReturnsEmptyGraph() {
        // No registry set
        RetrievedDoc doc = new RetrievedDoc("id1", "Some text about Alice.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), null);

        assertNotNull(result);
        assertTrue(result.graph().getEntities().isEmpty());
    }

    @Test
    void extractWithNoAvailableLlmReturnsEmptyGraph() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(anyString())).thenReturn(null);
        when(registry.getOrFallback(isNull())).thenReturn(null);

        RetrievedDoc doc = new RetrievedDoc("id1", "Some text about Alice.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), null);

        assertNotNull(result);
        assertTrue(result.graph().getEntities().isEmpty());
    }

    @Test
    void extractParsesValidJsonResponse() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        String validJson = """
                {
                  "entities": [
                    {"id":"e1","name":"Alice","type":"PERSON","description":"A person"},
                    {"id":"e2","name":"Acme Corp","type":"ORGANIZATION","description":"A company"}
                  ],
                  "relations": [
                    {"source":"e1","target":"e2","type":"WORKS_AT","description":"Alice works at Acme"}
                  ]
                }
                """;
        when(llmService.complete(anyString())).thenReturn(validJson);

        RetrievedDoc doc = new RetrievedDoc("id1", "Alice works at Acme Corp.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result);
        assertFalse(result.graph().getEntities().isEmpty());
        assertFalse(result.graph().getRelationships().isEmpty());
    }

    @Test
    void extractHandlesMarkdownFencedJson() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        String fencedJson = """
                ```json
                {
                  "entities": [{"id":"e1","name":"Bob","type":"PERSON","description":"A person"}],
                  "relations": []
                }
                ```
                """;
        when(llmService.complete(anyString())).thenReturn(fencedJson);

        RetrievedDoc doc = new RetrievedDoc("id1", "Bob is a developer.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result);
        assertFalse(result.graph().getEntities().isEmpty());
    }

    @Test
    void extractSkipsBlankChunks() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        RetrievedDoc blank = new RetrievedDoc("id1", "   ", Map.of());
        ExtractionResult result = agent.extract(List.of(blank), ExtractionConfig.defaults());

        assertNotNull(result);
        // LLM should not be called for blank chunks
        verify(llmService, never()).complete(anyString());
    }

    @Test
    void extractDeduplicatesEntitiesById() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        // Two chunks both return entity with same id
        String json = """
                {"entities":[{"id":"e1","name":"Alice","type":"PERSON","description":"person"}],"relations":[]}
                """;
        when(llmService.complete(anyString())).thenReturn(json);

        RetrievedDoc doc1 = new RetrievedDoc("id1", "Alice is here.", Map.of());
        RetrievedDoc doc2 = new RetrievedDoc("id2", "Alice is also here.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc1, doc2), ExtractionConfig.defaults());

        long aliceCount = result.graph().getEntities().stream()
                .filter(e -> "e1".equals(e.getId()))
                .count();
        assertEquals(1, aliceCount, "Duplicate entity ids should be deduplicated");
    }

    @Test
    void extractFiltersLowConfidenceEntities() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        String json = """
                {
                  "entities":[
                    {"id":"e1","name":"Alice","type":"PERSON","description":"person","confidence":0.9},
                    {"id":"e2","name":"Bob","type":"PERSON","description":"person","confidence":0.3}
                  ],
                  "relations":[]
                }
                """;
        when(llmService.complete(anyString())).thenReturn(json);

        ExtractionConfig config = new ExtractionConfig(
                List.of("PERSON"), List.of(), 0.7, Map.of());
        RetrievedDoc doc = new RetrievedDoc("id1", "Alice and Bob are people.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), config);

        // Only entities with confidence >= 0.7 should survive
        assertTrue(result.graph().getEntities().stream().allMatch(
                e -> e.getConfidence() == null || e.getConfidence() >= 0.7));
    }

    @Test
    void extractUsesRequestedLlmProvider() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback("claude-cli")).thenReturn(llmService);

        String json = """
                {"entities":[{"id":"e1","name":"Alice","type":"PERSON","description":"x"}],"relations":[]}
                """;
        when(llmService.complete(anyString())).thenReturn(json);

        ExtractionConfig config = new ExtractionConfig(
                List.of("PERSON"), List.of(), 0.0, Map.of("llmProvider", "claude-cli"));
        RetrievedDoc doc = new RetrievedDoc("id1", "Alice is here.", Map.of());

        ExtractionResult result = agent.extract(List.of(doc), config);
        assertNotNull(result);
        verify(registry).getOrFallback("claude-cli");
    }

    @Test
    void extractHandlesLlmExceptionGracefully() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);
        when(llmService.complete(anyString())).thenThrow(new RuntimeException("LLM timeout"));

        RetrievedDoc doc = new RetrievedDoc("id1", "Alice is here.", Map.of());
        // Should not throw, should return empty graph
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result);
        assertNotNull(result.graph());
    }

    @Test
    void extractMetricsContainCorrectAgentId() {
        agent.setLlmServiceRegistry(registry);
        when(registry.getOrFallback(isNull())).thenReturn(llmService);
        when(llmService.complete(anyString())).thenReturn(
                "{\"entities\":[],\"relations\":[]}");

        RetrievedDoc doc = new RetrievedDoc("id1", "Some text.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result.metrics());
        assertEquals("llm-active", result.metrics().agentId());
    }

    @Test
    void extractHandlesLlmChatProviderIdForLocalModel() {
        agent.setLlmServiceRegistry(registry);
        when(llmService.getId()).thenReturn("llm-chat");
        when(registry.getOrFallback(isNull())).thenReturn(llmService);

        // Local model response (without leading '{') should be completed by prepending '{'
        String partialJson = "\"entities\":[{\"id\":\"e1\",\"name\":\"Alice\",\"type\":\"PERSON\",\"description\":\"x\"}],\"relations\":[]}";
        when(llmService.complete(anyString())).thenReturn(partialJson);

        RetrievedDoc doc = new RetrievedDoc("id1", "Alice is here.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result);
        // The lenient parser should handle this
    }

    @Test
    void setLlmServiceRegistryNull() {
        agent.setLlmServiceRegistry(null);
        RetrievedDoc doc = new RetrievedDoc("id1", "Alice is here.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());
        assertNotNull(result);
        assertTrue(result.graph().getEntities().isEmpty());
    }
}
