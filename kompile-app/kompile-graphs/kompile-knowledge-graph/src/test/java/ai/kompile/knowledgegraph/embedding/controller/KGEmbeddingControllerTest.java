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

package ai.kompile.knowledgegraph.embedding.controller;

import ai.kompile.core.kgembedding.EmbeddingScore;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.*;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KGEmbeddingController.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KGEmbeddingControllerTest {

    @Mock
    private KGEmbeddingJobService jobService;

    @Mock
    private KGEmbeddingStorageService storageService;

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private KGEmbeddingConfigService configService;

    private KGEmbeddingController controller;

    private static final KGEmbeddingConfig DEFAULT_CONFIG = KGEmbeddingConfig.defaults();

    @BeforeEach
    void setUp() {
        controller = new KGEmbeddingController(jobService, storageService, nodeRepository, configService);

        // Stub config service defaults
        when(configService.getConfig()).thenReturn(DEFAULT_CONFIG);
        when(configService.getTransEConfig()).thenReturn(TrainConfig.defaultTransE());
        when(configService.getRotatEConfig()).thenReturn(TrainConfig.defaultRotatE());
        when(configService.getGraphRAGConfig()).thenReturn(GraphRAGConfig.defaults());
        when(configService.getNeo4jConfig()).thenReturn(Neo4jConfig.defaults());
        when(configService.getTrainConfig(KGEmbeddingAlgorithm.TRANSE)).thenReturn(TrainConfig.defaultTransE());
        when(configService.getTrainConfig(KGEmbeddingAlgorithm.ROTATE)).thenReturn(TrainConfig.defaultRotatE());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // startTraining
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void startTraining_withValidRequest_returns200() {
        KGEmbeddingJob job = buildJob("job-1", JobStatus.PENDING);
        when(jobService.startTraining(anyLong(), any(), any())).thenReturn(job);

        KGEmbeddingController.TrainRequest request = new KGEmbeddingController.TrainRequest(
                1L, "TRANSE", 50, 10, 0.01, 128, 1.0, 5);

        ResponseEntity<KGEmbeddingJob> response = controller.startTraining(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void startTraining_whenJobServiceThrowsIllegalState_returns400() {
        when(jobService.startTraining(anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("Job already running"));

        KGEmbeddingController.TrainRequest request = new KGEmbeddingController.TrainRequest(
                1L, "TRANSE", null, null, null, null, null, null);

        ResponseEntity<KGEmbeddingJob> response = controller.startTraining(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void startTraining_usesDefaultsFromConfig_whenRequestFieldsNull() {
        KGEmbeddingJob job = buildJob("job-1", JobStatus.PENDING);
        when(jobService.startTraining(anyLong(), any(), any())).thenReturn(job);

        KGEmbeddingController.TrainRequest request = new KGEmbeddingController.TrainRequest(
                1L, "TRANSE", null, null, null, null, null, null);

        ResponseEntity<KGEmbeddingJob> response = controller.startTraining(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getJob / getJobs / cancelJob
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getJob_whenFound_returns200() {
        KGEmbeddingJob job = buildJob("job-1", JobStatus.RUNNING);
        when(jobService.getJob("job-1")).thenReturn(Optional.of(job));

        ResponseEntity<KGEmbeddingJob> response = controller.getJob("job-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getJob_whenNotFound_returns404() {
        when(jobService.getJob("missing")).thenReturn(Optional.empty());

        ResponseEntity<KGEmbeddingJob> response = controller.getJob("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getJobs_returnsPagedResults() {
        KGEmbeddingJob job = buildJob("job-1", JobStatus.COMPLETED);
        Page<KGEmbeddingJob> page = new PageImpl<>(List.of(job));
        when(jobService.getJobs(eq(1L), any())).thenReturn(page);

        ResponseEntity<Page<KGEmbeddingJob>> response = controller.getJobs(1L, 0, 20);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    void cancelJob_whenCancelled_returns200() {
        when(jobService.cancelJob("job-1")).thenReturn(true);

        ResponseEntity<Void> response = controller.cancelJob("job-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void cancelJob_whenNotFound_returns404() {
        when(jobService.cancelJob("missing")).thenReturn(false);

        ResponseEntity<Void> response = controller.cancelJob("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getEntityEmbeddings
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getEntityEmbeddings_withMatchingNodes_returns200() {
        GraphNode node = buildNodeWithEmbedding("Alice");
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(1L)).thenReturn(List.of(node));

        ResponseEntity<List<KGEmbeddingController.EntityEmbeddingDTO>> response =
                controller.getEntityEmbeddings(1L, null, 0, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty());
    }

    @Test
    void getEntityEmbeddings_withSearchFilter_filtersResults() {
        GraphNode aliceNode = buildNodeWithEmbedding("Alice");
        GraphNode bobNode = buildNodeWithEmbedding("Bob");
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(1L))
                .thenReturn(List.of(aliceNode, bobNode));

        ResponseEntity<List<KGEmbeddingController.EntityEmbeddingDTO>> response =
                controller.getEntityEmbeddings(1L, "ali", 0, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Alice", response.getBody().get(0).entityName());
    }

    @Test
    void getEntityEmbeddings_withPaginationBeyondEnd_returnsEmpty() {
        GraphNode node = buildNodeWithEmbedding("Alice");
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(1L)).thenReturn(List.of(node));

        // Page 10 beyond results
        ResponseEntity<List<KGEmbeddingController.EntityEmbeddingDTO>> response =
                controller.getEntityEmbeddings(1L, null, 10, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStats / clearEmbeddings
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getStats_returns200WithStats() {
        KGEmbeddingStorageService.EmbeddingStats stats =
                new KGEmbeddingStorageService.EmbeddingStats(10, 8, 5, 3, 1000L);
        when(storageService.getStats(1L)).thenReturn(stats);

        ResponseEntity<KGEmbeddingStorageService.EmbeddingStats> response = controller.getStats(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(10, response.getBody().totalNodes());
    }

    @Test
    void clearEmbeddings_returns200() {
        doNothing().when(storageService).clearEmbeddings(1L);

        ResponseEntity<Void> response = controller.clearEmbeddings(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storageService).clearEmbeddings(1L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAlgorithms
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getAlgorithms_returnsAllAlgorithms() {
        ResponseEntity<List<KGEmbeddingController.AlgorithmInfo>> response = controller.getAlgorithms();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(KGEmbeddingAlgorithm.values().length, response.getBody().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Configuration endpoints
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getConfig_returnsCurrentConfig() {
        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void updateConfig_returnsSavedConfig() {
        when(configService.updateConfig(any())).thenReturn(DEFAULT_CONFIG);

        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response =
                controller.updateConfig(DEFAULT_CONFIG);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void resetConfig_returnsDefaultConfig() {
        when(configService.resetToDefaults()).thenReturn(DEFAULT_CONFIG);

        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response = controller.resetConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getTransEConfig_returnsTransEConfig() {
        ResponseEntity<TrainConfig> response = controller.getTransEConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void updateTransEConfig_returnsUpdatedConfig() {
        when(configService.updateTransEConfig(any())).thenReturn(DEFAULT_CONFIG);

        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response =
                controller.updateTransEConfig(TrainConfig.defaultTransE());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getRotatEConfig_returnsRotatEConfig() {
        ResponseEntity<TrainConfig> response = controller.getRotatEConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void updateRotatEConfig_returnsUpdatedConfig() {
        when(configService.updateRotatEConfig(any())).thenReturn(DEFAULT_CONFIG);

        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response =
                controller.updateRotatEConfig(TrainConfig.defaultRotatE());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getGraphRAGConfig_returnsGraphRAGConfig() {
        ResponseEntity<GraphRAGConfig> response = controller.getGraphRAGConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void updateGraphRAGConfig_returnsUpdatedConfig() {
        when(configService.updateGraphRAGConfig(any())).thenReturn(DEFAULT_CONFIG);

        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response =
                controller.updateGraphRAGConfig(GraphRAGConfig.defaults());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getNeo4jConfig_returnsNeo4jConfig() {
        ResponseEntity<Neo4jConfig> response = controller.getNeo4jConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void updateNeo4jConfig_returnsUpdatedConfig() {
        when(configService.updateNeo4jConfig(any())).thenReturn(DEFAULT_CONFIG);

        ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> response =
                controller.updateNeo4jConfig(Neo4jConfig.defaults());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // testNeo4jConnection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testNeo4jConnection_withValidBoltUri_returnsSuccess() {
        Neo4jConfig config = new Neo4jConfig(true, "bolt://localhost:7687", "neo4j", "pass");
        ResponseEntity<KGEmbeddingController.ConnectionTestResult> response =
                controller.testNeo4jConnection(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().success());
    }

    @Test
    void testNeo4jConnection_withValidNeo4jUri_returnsSuccess() {
        Neo4jConfig config = new Neo4jConfig(true, "neo4j://localhost:7687", "neo4j", "pass");
        ResponseEntity<KGEmbeddingController.ConnectionTestResult> response =
                controller.testNeo4jConnection(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().success());
    }

    @Test
    void testNeo4jConnection_withBlankUri_returnsFailure() {
        Neo4jConfig config = new Neo4jConfig(true, "", "neo4j", "pass");
        ResponseEntity<KGEmbeddingController.ConnectionTestResult> response =
                controller.testNeo4jConnection(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().success());
        assertTrue(response.getBody().message().contains("URI is required"));
    }

    @Test
    void testNeo4jConnection_withInvalidUriScheme_returnsFailure() {
        Neo4jConfig config = new Neo4jConfig(true, "http://localhost:7687", "neo4j", "pass");
        ResponseEntity<KGEmbeddingController.ConnectionTestResult> response =
                controller.testNeo4jConnection(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().success());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // link prediction endpoints — return bad request when model is null
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void scoreTriple_whenNoModel_returns400() {
        when(storageService.getStoredAlgorithm(anyLong())).thenReturn(null);

        KGEmbeddingController.TripleRequest request =
                new KGEmbeddingController.TripleRequest(1L, "Alice", "KNOWS", "Bob");

        ResponseEntity<KGEmbeddingController.ScoreResult> response = controller.scoreTriple(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void predictTails_whenNoModel_returns400() {
        when(storageService.getStoredAlgorithm(anyLong())).thenReturn(null);

        KGEmbeddingController.PredictRequest request =
                new KGEmbeddingController.PredictRequest(1L, "Alice", "KNOWS", null, 5);

        ResponseEntity<List<EmbeddingScore>> response = controller.predictTails(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void predictHeads_whenNoModel_returns400() {
        when(storageService.getStoredAlgorithm(anyLong())).thenReturn(null);

        KGEmbeddingController.PredictRequest request =
                new KGEmbeddingController.PredictRequest(1L, null, "KNOWS", "Bob", 5);

        ResponseEntity<List<EmbeddingScore>> response = controller.predictHeads(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void predictRelations_whenNoModel_returns400() {
        when(storageService.getStoredAlgorithm(anyLong())).thenReturn(null);

        KGEmbeddingController.PredictRequest request =
                new KGEmbeddingController.PredictRequest(1L, "Alice", null, "Bob", 5);

        ResponseEntity<List<EmbeddingScore>> response = controller.predictRelations(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void findSimilarEntities_whenNoModel_returns400() {
        when(storageService.getStoredAlgorithm(anyLong())).thenReturn(null);

        ResponseEntity<List<EmbeddingScore>> response =
                controller.findSimilarEntities(1L, "Alice", 5);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private KGEmbeddingJob buildJob(String jobId, JobStatus status) {
        return KGEmbeddingJob.builder()
                .jobId(jobId)
                .factSheetId(1L)
                .algorithm(KGEmbeddingAlgorithm.TRANSE)
                .status(status)
                .embeddingDim(100)
                .epochs(100)
                .learningRate(0.01)
                .batchSize(1024)
                .margin(1.0)
                .negativeSamples(10)
                .createdAt(Instant.now())
                .build();
    }

    private GraphNode buildNodeWithEmbedding(String title) {
        GraphNode node = GraphNode.builder()
                .id(1L)
                .nodeId(UUID.randomUUID().toString())
                .title(title)
                .nodeType(NodeLevel.ENTITY)
                .externalId(title.toLowerCase())
                .factSheetId(1L)
                .build();
        node.setKgEmbedding(Nd4j.rand(1, 10));
        node.setKgEmbeddingAlgorithm(KGEmbeddingAlgorithm.TRANSE);
        node.setKgEmbeddingVersion(1000L);
        return node;
    }
}
