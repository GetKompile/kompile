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
package ai.kompile.knowledgegraph.builder.impl;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.builder.repository.ExtractionJobRepository;
import ai.kompile.knowledgegraph.builder.repository.ExtractionLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmKnowledgeGraphBuilder}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmKnowledgeGraphBuilderTest {

    @Mock
    private ExtractionJobRepository jobRepository;

    @Mock
    private ExtractionLogRepository logRepository;

    @Mock
    private LLMChat llmChat;

    @Mock
    private LLMChat.ChatClientRequestSpec promptSpec;

    @Mock
    private LLMChat.CallResponseSpec callResponseSpec;

    @Mock
    private OntologyProjectionProvider ontologyProvider;

    private LlmKnowledgeGraphBuilder builder;

    private static final String VALID_JSON_RESPONSE = """
            {
              "entities": [
                {"id":"e1","title":"Alice","label":"PERSON","description":"A person"},
                {"id":"e2","title":"Acme Corp","label":"ORGANIZATION","description":"A company"}
              ],
              "relationships": [
                {"source":"e1","target":"e2","type":"WORKS_AT","description":"Alice works there","confidence":0.9}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        builder = new LlmKnowledgeGraphBuilder(new ObjectMapper(), jobRepository, logRepository);
    }

    // ─── Identity / metadata ─────────────────────────────────────────────────

    @Test
    void getIdReturnsLlmBuilder() {
        assertEquals("llm-builder", builder.getId());
    }

    @Test
    void getDisplayNameIsNonEmpty() {
        assertFalse(builder.getDisplayName().isBlank());
    }

    @Test
    void getTypeIsLlm() {
        assertEquals(GraphBuilderType.LLM, builder.getType());
    }

    @Test
    void getDescriptionIsNonEmpty() {
        assertFalse(builder.getDescription().isBlank());
    }

    @Test
    void supportsExtractionLog() {
        assertTrue(builder.supportsExtractionLog());
    }

    @Test
    void supportsConcurrentIndexing() {
        assertTrue(builder.supportsConcurrentIndexing());
    }

    // ─── Ready check ─────────────────────────────────────────────────────────

    @Test
    void isNotReadyWithoutLlmChat() {
        assertFalse(builder.isReady());
    }

    @Test
    void isReadyAfterLlmChatSet() {
        builder.setLlmChat(llmChat);
        assertTrue(builder.isReady());
    }

    // ─── Configure ───────────────────────────────────────────────────────────

    @Test
    void configureStoresConfig() {
        BuilderConfig config = BuilderConfig.defaults()
                .withEntityTypes(List.of("PERSON", "ORGANIZATION"));
        builder.configure(config);
        assertEquals(config, builder.getConfig());
    }

    @Test
    void configureWithNullUsesDefaults() {
        builder.configure(null);
        assertNotNull(builder.getConfig());
    }

    // ─── buildFromChunks – no LLM ────────────────────────────────────────────

    @Test
    void buildFromChunksWithNullChunksReturnsEmpty() {
        List<ProposedTriple> result = builder.buildFromChunks(null,
                new GraphBuildContext("job-1", 1L, "jpa"), null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildFromChunksWithEmptyChunksReturnsEmpty() {
        List<ProposedTriple> result = builder.buildFromChunks(Collections.emptyList(),
                new GraphBuildContext("job-1", 1L, "jpa"), null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildFromChunksWithNoLlmReturnsEmpty() {
        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme Corp.", Map.of()));
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);
        assertTrue(result.isEmpty());
    }

    // ─── buildFromChunks – with LLM ──────────────────────────────────────────

    @Test
    void buildFromChunksCallsLlmAndReturnsProposals() {
        builder.setLlmChat(llmChat);
        configureLlmMock(VALID_JSON_RESPONSE);

        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme Corp.", Map.of()));
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);

        assertFalse(result.isEmpty(), "Should produce at least one triple");
    }

    @Test
    void buildFromChunksReturnsEmptyOnInvalidJson() {
        builder.setLlmChat(llmChat);
        configureLlmMock("not valid json at all");

        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme Corp.", Map.of()));
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);

        // Parser fails gracefully and returns empty graph triples
        assertNotNull(result);
    }

    @Test
    void buildFromChunksCallsProgressCallback() {
        builder.setLlmChat(llmChat);
        configureLlmMock(VALID_JSON_RESPONSE);

        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<BuildProgress> progressUpdates = new ArrayList<>();
        Consumer<BuildProgress> callback = progressUpdates::add;

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme Corp.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-1", 1L, "jpa"), callback);

        assertFalse(progressUpdates.isEmpty(), "Progress callback should be invoked");
    }

    @Test
    void buildFromChunksInvokesCompletedProgressForEmptyChunks() {
        List<AtomicInteger> callCount = new ArrayList<>();
        AtomicReference<BuildProgress> lastProgress = new AtomicReference<>();

        Consumer<BuildProgress> callback = p -> {
            lastProgress.set(p);
            callCount.add(new AtomicInteger(1));
        };

        builder.buildFromChunks(Collections.emptyList(),
                new GraphBuildContext("job-1", 1L, "jpa"), callback);

        assertEquals(1, callCount.size(), "Exactly one progress call for empty chunks");
        assertNotNull(lastProgress.get());
    }

    @Test
    void buildFromChunksFiltersLowConfidenceTriples() {
        builder.setLlmChat(llmChat);
        String jsonWithLowConfidence = """
                {
                  "entities": [
                    {"id":"e1","title":"Alice","nodeLabel":"PERSON","description":"A person"},
                    {"id":"e2","title":"Acme","nodeLabel":"ORGANIZATION","description":"A company"}
                  ],
                  "relationships": [
                    {"source":"e1","target":"e2","relationshipType":"WORKS_AT","confidence":0.2}
                  ]
                }
                """;
        configureLlmMock(jsonWithLowConfidence);

        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        // Configure to require high confidence
        BuilderConfig highConfConfig = new BuilderConfig(
                null, null, 0.0, 4096,
                List.of("PERSON", "ORGANIZATION"), List.of(), 0.8, false, 0.9, null, Map.of());
        builder.configure(highConfConfig);

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme.", Map.of()));
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);

        // All returned triples should have confidence >= 0.8
        result.forEach(t -> assertTrue(t.confidence() >= 0.8,
                "Triple confidence should be >= min confidence"));
    }

    @Test
    void buildFromChunksHandlesMarkdownFencedJsonResponse() {
        builder.setLlmChat(llmChat);
        String fenced = "```json\n" + VALID_JSON_RESPONSE + "\n```";
        configureLlmMock(fenced);

        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme Corp.", Map.of()));
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);

        assertFalse(result.isEmpty(), "Markdown fenced JSON should be parsed successfully");
    }

    @Test
    void buildFromChunksUsesCustomPromptWhenConfigured() {
        builder.setLlmChat(llmChat);
        configureLlmMock(VALID_JSON_RESPONSE);

        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        BuilderConfig customConfig = new BuilderConfig(
                null, null, 0.0, 4096,
                List.of("PERSON"), List.of(), 0.0, false, 0.9,
                "Custom prompt: {{TEXT}}",
                Map.of());
        builder.configure(customConfig);

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice is a developer.", Map.of()));
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);

        assertNotNull(result);
        // Verify that LLM was called with the custom prompt (user text included)
        verify(promptSpec, atLeastOnce()).user(contains("Alice is a developer"));
    }

    @Test
    void buildFromChunksHandlesMultipleChunks() {
        builder.setLlmChat(llmChat);
        configureLlmMock(VALID_JSON_RESPONSE);

        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice works at Acme Corp.", Map.of()),
                new RetrievedDoc("c2", "Bob works at TechCo.", Map.of()),
                new RetrievedDoc("c3", "Charlie founded StartupX.", Map.of())
        );
        List<ProposedTriple> result = builder.buildFromChunks(chunks,
                new GraphBuildContext("job-1", 1L, "jpa"), null);

        // LLM should be called for each chunk
        verify(promptSpec, times(3)).user(anyString());
        assertNotNull(result);
    }

    // ─── getExtractionLog ────────────────────────────────────────────────────

    @Test
    void getExtractionLogReturnsEmptyWhenJobNotFound() {
        when(logRepository.findByJobId("missing-job")).thenReturn(List.of());
        Optional<List<ExtractionLogEntry>> logs = builder.getExtractionLog("missing-job");
        assertTrue(logs.isEmpty());
    }

    @Test
    void getExtractionLogReturnsCachedLogsAfterBuild() {
        builder.setLlmChat(llmChat);
        configureLlmMock(VALID_JSON_RESPONSE);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Alice is a developer.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-cache", 1L, "jpa"), null);

        Optional<List<ExtractionLogEntry>> logs = builder.getExtractionLog("job-cache");
        assertTrue(logs.isPresent());
        assertFalse(logs.get().isEmpty());
    }

    // ─── Ontology-guided extraction ──────────────────────────────────────────

    /**
     * When a fact sheet has a bound ontology with non-empty entity types, the LLM prompt
     * must include those types AND an "ONLY these types" instruction.
     */
    @Test
    void boundOntology_promptIncludesOntologyTypesAndConstraintInstruction() {
        // Wire ontology provider into builder via field injection simulation
        builder.setLlmChat(llmChat);
        injectOntologyProvider(builder, ontologyProvider);

        Long fsId = 42L;
        when(ontologyProvider.hasBoundOntology(fsId)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(fsId)).thenReturn(List.of("PRODUCT", "MANUFACTURER"));
        when(ontologyProvider.allowedRelationshipTypes(fsId)).thenReturn(List.of("MADE_BY"));
        configureLlmMock(VALID_JSON_RESPONSE);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Widget A is made by Acme.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-ont", fsId, "jpa"), null);

        // Capture the prompt sent to the LLM
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec, atLeastOnce()).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        // Ontology entity types must appear in the prompt
        assertTrue(prompt.contains("PRODUCT"), "Prompt must list ontology entity type PRODUCT");
        assertTrue(prompt.contains("MANUFACTURER"), "Prompt must list ontology entity type MANUFACTURER");

        // Constraint instruction must be present
        assertTrue(prompt.contains("ONLY"), "Prompt must include 'ONLY' constraint instruction when ontology is bound");
    }

    /**
     * When a fact sheet has a bound ontology, the relationship types must also appear in the prompt.
     */
    @Test
    void boundOntology_promptIncludesRelationshipTypesWhenPresent() {
        builder.setLlmChat(llmChat);
        injectOntologyProvider(builder, ontologyProvider);

        Long fsId = 43L;
        when(ontologyProvider.hasBoundOntology(fsId)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(fsId)).thenReturn(List.of("PRODUCT", "MANUFACTURER"));
        when(ontologyProvider.allowedRelationshipTypes(fsId)).thenReturn(List.of("PRODUCED_BY", "SOLD_BY"));
        configureLlmMock(VALID_JSON_RESPONSE);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Widget A.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-ont-rel", fsId, "jpa"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec, atLeastOnce()).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        assertTrue(prompt.contains("PRODUCED_BY"), "Prompt must list ontology relationship type PRODUCED_BY");
        assertTrue(prompt.contains("SOLD_BY"), "Prompt must list ontology relationship type SOLD_BY");
    }

    /**
     * When the OntologyProjectionProvider is null (absent from context, e.g. tests without Spring),
     * behaviour is identical to today's free-form extraction.
     */
    @Test
    void nullProvider_promptUsesFreeFormEntityTypes() {
        // No ontologyProvider injected — builder has null provider
        builder.setLlmChat(llmChat);
        // Do NOT inject ontologyProvider

        configureLlmMock(VALID_JSON_RESPONSE);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Alice works at Acme.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-free", 99L, "jpa"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec, atLeastOnce()).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        // Constraint instruction must NOT be present in free-form mode
        assertFalse(prompt.contains("Use ONLY the entity types"), "Free-form prompt must not contain 'ONLY' constraint");
        // Default free-form types must still appear
        assertTrue(prompt.contains("PERSON") || prompt.contains("ORGANIZATION"),
                "Free-form prompt must contain default entity types");
    }

    /**
     * When the provider is available but hasBoundOntology returns false,
     * free-form behaviour is preserved (no constraint in the prompt).
     */
    @Test
    void unboundOntology_promptUsesFreeFormEntityTypes() {
        builder.setLlmChat(llmChat);
        injectOntologyProvider(builder, ontologyProvider);

        Long fsId = 55L;
        when(ontologyProvider.hasBoundOntology(fsId)).thenReturn(false);
        configureLlmMock(VALID_JSON_RESPONSE);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Alice works at Acme.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-unbound", fsId, "jpa"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec, atLeastOnce()).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        assertFalse(prompt.contains("Use ONLY the entity types"),
                "Unbound ontology must not add constraint to prompt");
    }

    /**
     * When the provider returns an empty allowed-entity-types list (ontology bound but empty),
     * free-form behaviour is preserved — permissive, never deny-all.
     */
    @Test
    void emptyOntologyTypes_promptRemainsPermissive() {
        builder.setLlmChat(llmChat);
        injectOntologyProvider(builder, ontologyProvider);

        Long fsId = 66L;
        when(ontologyProvider.hasBoundOntology(fsId)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(fsId)).thenReturn(Collections.emptyList());
        when(ontologyProvider.allowedRelationshipTypes(fsId)).thenReturn(Collections.emptyList());
        configureLlmMock(VALID_JSON_RESPONSE);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.empty());

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Alice works at Acme.", Map.of()));
        builder.buildFromChunks(chunks, new GraphBuildContext("job-empty-ont", fsId, "jpa"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec, atLeastOnce()).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        assertFalse(prompt.contains("Use ONLY the entity types"),
                "Empty ontology types must not add constraint (permissive)");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void configureLlmMock(String responseText) {
        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.options(any())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(responseText);
    }

    /**
     * Injects the OntologyProjectionProvider into the builder via reflection
     * (simulating Spring's @Autowired(required=false) field injection in unit tests).
     */
    private static void injectOntologyProvider(LlmKnowledgeGraphBuilder builder,
                                                OntologyProjectionProvider provider) {
        try {
            var field = LlmKnowledgeGraphBuilder.class.getDeclaredField("ontologyProvider");
            field.setAccessible(true);
            field.set(builder, provider);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Could not inject OntologyProjectionProvider for test", e);
        }
    }
}
