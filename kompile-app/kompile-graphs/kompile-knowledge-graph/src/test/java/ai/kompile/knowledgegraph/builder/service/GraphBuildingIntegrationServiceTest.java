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

package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphBuildingIntegrationService} — shouldTriggerGraphBuilding,
 * getBuilderForFactSheet, triggerGraphBuildingAsync, requestCancellation.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GraphBuildingIntegrationServiceTest {

    @Mock private GraphBuilderRegistry builderRegistry;
    @Mock private ExtractionJobService jobService;
    @Mock private KnowledgeGraphBuilder mockBuilder;

    private GraphBuildingIntegrationService service;

    @BeforeEach
    void setUp() {
        service = new GraphBuildingIntegrationService(
                builderRegistry, jobService, new ObjectMapper());
    }

    private RetrievedDoc doc(String id, String text) {
        return RetrievedDoc.builder().id(id).text(text).metadata(Map.of()).build();
    }

    private ExtractionJob stubJob(String jobId, Long factSheetId) {
        ExtractionJob job = new ExtractionJob();
        job.setJobId(jobId);
        job.setFactSheetId(factSheetId);
        job.setStatus(ExtractionJob.JobStatus.PENDING);
        return job;
    }

    // ─── shouldTriggerGraphBuilding ──────────────────────────────────

    @Test
    void shouldTrigger_enabledAndBuilderExists_returnsTrue() {
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));

        assertTrue(service.shouldTriggerGraphBuilding(true, "llm-builder"));
    }

    @Test
    void shouldTrigger_disabled_returnsFalse() {
        assertFalse(service.shouldTriggerGraphBuilding(false, "llm-builder"));
    }

    @Test
    void shouldTrigger_nullEnabled_returnsFalse() {
        assertFalse(service.shouldTriggerGraphBuilding(null, "llm-builder"));
    }

    @Test
    void shouldTrigger_nullBuilderType_returnsFalse() {
        assertFalse(service.shouldTriggerGraphBuilding(true, null));
    }

    @Test
    void shouldTrigger_emptyBuilderType_returnsFalse() {
        assertFalse(service.shouldTriggerGraphBuilding(true, ""));
    }

    @Test
    void shouldTrigger_builderNotFound_returnsFalse() {
        when(builderRegistry.getBuilderByTypeString("unknown"))
                .thenReturn(Optional.empty());

        assertFalse(service.shouldTriggerGraphBuilding(true, "unknown"));
    }

    // ─── getBuilderForFactSheet ──────────────────────────────────────

    @Test
    void getBuilderForFactSheet_found_returnsBuilder() {
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));

        Optional<KnowledgeGraphBuilder> result = service.getBuilderForFactSheet("llm-builder", null);
        assertTrue(result.isPresent());
    }

    @Test
    void getBuilderForFactSheet_notFound_returnsEmpty() {
        when(builderRegistry.getBuilderByTypeString("missing"))
                .thenReturn(Optional.empty());

        assertTrue(service.getBuilderForFactSheet("missing", null).isEmpty());
    }

    @Test
    void getBuilderForFactSheet_withConfigJson_appliesConfig() {
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));

        String configJson = """
                {"modelProvider":"openai","modelName":"gpt-4","temperature":0.5,
                 "maxTokens":100,"entityTypes":["PERSON"],"relationshipTypes":["KNOWS"],
                 "minConfidence":0.5,"autoAccept":true,"autoAcceptThreshold":0.8}
                """;

        Optional<KnowledgeGraphBuilder> result = service.getBuilderForFactSheet("llm-builder", configJson);
        assertTrue(result.isPresent());
        verify(mockBuilder).configure(any(BuilderConfig.class));
    }

    @Test
    void getBuilderForFactSheet_invalidConfigJson_stillReturnsBuilder() {
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));

        Optional<KnowledgeGraphBuilder> result = service.getBuilderForFactSheet("llm-builder", "not-json");
        assertTrue(result.isPresent());
        verify(mockBuilder, never()).configure(any());
    }

    @Test
    void getBuilderForFactSheet_emptyConfigJson_skipsConfig() {
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));

        Optional<KnowledgeGraphBuilder> result = service.getBuilderForFactSheet("llm-builder", "");
        assertTrue(result.isPresent());
        verify(mockBuilder, never()).configure(any());
    }

    // ─── triggerGraphBuildingAsync ────────────────────────────────────

    @Test
    void triggerAsync_builderNotFound_failsJob() throws Exception {
        ExtractionJob job = stubJob("job-1", 1L);
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(builderRegistry.getBuilderByTypeString("unknown")).thenReturn(Optional.empty());

        CompletableFuture<String> future = service.triggerGraphBuildingAsync(
                1L, List.of(doc("c1", "text")), "unknown", null, null);

        assertEquals("job-1", future.get());
        verify(jobService).failJob(eq("job-1"), contains("Builder not found"));
    }

    @Test
    void triggerAsync_builderFound_startsJobAndRuns() throws Exception {
        ExtractionJob job = stubJob("job-2", 1L);
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));
        when(mockBuilder.buildFromChunks(anyList(), any(), any())).thenReturn(List.of());
        when(jobService.createProposalsFromTriples(anyString(), anyLong(), anyList())).thenReturn(0);

        List<RetrievedDoc> chunks = List.of(doc("c1", "hello"), doc("c2", "world"));

        CompletableFuture<String> future = service.triggerGraphBuildingAsync(
                1L, chunks, "llm-builder", null, null);

        assertEquals("job-2", future.get());
        verify(jobService).startJob("job-2", 2);
        verify(jobService).completeJob("job-2", 0);
    }

    @Test
    void triggerAsync_withConfig_configuresBuilder() throws Exception {
        ExtractionJob job = stubJob("job-3", 1L);
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));
        when(mockBuilder.buildFromChunks(anyList(), any(), any())).thenReturn(List.of());
        when(jobService.createProposalsFromTriples(anyString(), anyLong(), anyList())).thenReturn(0);

        BuilderConfig config = new BuilderConfig(
                "openai", "gpt-4", 0.5, 100,
                List.of("PERSON"), List.of("KNOWS"), 0.5, true, 0.8, null, Map.of());

        service.triggerGraphBuildingAsync(1L, List.of(doc("c1", "text")),
                "llm-builder", config, null);

        verify(mockBuilder).configure(config);
    }

    @Test
    void triggerAsync_builderThrows_failsJob() throws Exception {
        ExtractionJob job = stubJob("job-4", 1L);
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));
        when(mockBuilder.buildFromChunks(anyList(), any(), any()))
                .thenThrow(new RuntimeException("LLM error"));

        service.triggerGraphBuildingAsync(1L, List.of(doc("c1", "text")),
                "llm-builder", null, null);

        verify(jobService).failJob(eq("job-4"), contains("LLM error"));
    }

    @Test
    void triggerAsync_proposalsCreated_completesJob() throws Exception {
        ExtractionJob job = stubJob("job-5", 1L);
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));

        ProposedTriple triple = new ProposedTriple("Alice", "PERSON", "WORKS_AT",
                "Acme", "ORG", 0.9, "chunk-1", "doc-1", "Alice works at Acme", Map.of());
        when(mockBuilder.buildFromChunks(anyList(), any(), any())).thenReturn(List.of(triple));
        when(jobService.createProposalsFromTriples(anyString(), anyLong(), anyList())).thenReturn(1);

        service.triggerGraphBuildingAsync(1L, List.of(doc("c1", "text")),
                "llm-builder", null, null);

        verify(jobService).createProposalsFromTriples(eq("job-5"), eq(1L), anyList());
        verify(jobService).completeJob("job-5", 1);
    }

    @Test
    void triggerAsync_autoAcceptDisabled_skipsAutoAccept() throws Exception {
        ExtractionJob job = stubJob("job-6", 1L);
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));
        when(mockBuilder.buildFromChunks(anyList(), any(), any())).thenReturn(List.of());
        when(jobService.createProposalsFromTriples(anyString(), anyLong(), anyList())).thenReturn(3);

        BuilderConfig config = new BuilderConfig(
                "openai", "gpt-4", 0.5, 100,
                List.of(), List.of(), 0.5, false, 0.8, null, Map.of());

        service.triggerGraphBuildingAsync(1L, List.of(doc("c1", "text")),
                "llm-builder", config, null);

        verify(jobService, never()).autoAcceptHighConfidence(anyString(), anyDouble());
    }

    // ─── requestCancellation ─────────────────────────────────────────

    @Test
    void requestCancellation_setsFlag() {
        assertDoesNotThrow(() -> service.requestCancellation("job-99"));
    }

    // ─── triggerForFactSheet ─────────────────────────────────────────

    @Test
    void triggerForFactSheet_builderNotAvailable_returnsEmptyOptional() throws Exception {
        when(builderRegistry.getBuilderByTypeString("missing"))
                .thenReturn(Optional.empty());

        CompletableFuture<Optional<String>> result = service.triggerForFactSheet(
                1L, List.of(doc("c1", "text")), "missing", null, null);

        assertTrue(result.get().isEmpty());
    }

    @Test
    void triggerForFactSheet_builderAvailable_returnsJobId() throws Exception {
        ExtractionJob job = stubJob("job-7", 1L);
        when(builderRegistry.getBuilderByTypeString("llm-builder"))
                .thenReturn(Optional.of(mockBuilder));
        when(jobService.createJob(anyLong(), anyString(), any())).thenReturn(job);
        when(mockBuilder.buildFromChunks(anyList(), any(), any())).thenReturn(List.of());
        when(jobService.createProposalsFromTriples(anyString(), anyLong(), anyList())).thenReturn(0);

        CompletableFuture<Optional<String>> result = service.triggerForFactSheet(
                1L, List.of(doc("c1", "text")), "llm-builder", null, null);

        assertTrue(result.get().isPresent());
        assertEquals("job-7", result.get().get());
    }
}
