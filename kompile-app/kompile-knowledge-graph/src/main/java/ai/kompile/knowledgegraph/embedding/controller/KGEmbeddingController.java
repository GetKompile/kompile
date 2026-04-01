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
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.GraphRAGConfig;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.Neo4jConfig;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.TrainConfig;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.impl.RotatEModel;
import ai.kompile.knowledgegraph.embedding.impl.TransEModel;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for Knowledge Graph Embeddings.
 */
@RestController
@RequestMapping("/api/knowledge-graph/embeddings")
public class KGEmbeddingController {

    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingController.class);

    private final KGEmbeddingJobService jobService;
    private final KGEmbeddingStorageService storageService;
    private final GraphNodeRepository nodeRepository;
    private final KGEmbeddingConfigService configService;

    // In-memory models for scoring (loaded from DB)
    private final Map<Long, KGEmbeddingModel> loadedModels = new HashMap<>();

    @Autowired
    public KGEmbeddingController(
            KGEmbeddingJobService jobService,
            KGEmbeddingStorageService storageService,
            GraphNodeRepository nodeRepository,
            KGEmbeddingConfigService configService
    ) {
        this.jobService = jobService;
        this.storageService = storageService;
        this.nodeRepository = nodeRepository;
        this.configService = configService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRAINING JOBS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start a new training job.
     */
    @PostMapping("/train")
    public ResponseEntity<KGEmbeddingJob> startTraining(@RequestBody TrainRequest request) {
        log.info("Starting training for fact sheet {} with algorithm {}",
                request.factSheetId, request.algorithm);

        KGEmbeddingAlgorithm algorithm = KGEmbeddingAlgorithm.fromString(request.algorithm);

        // Get defaults from persisted config
        TrainConfig defaults = configService.getTrainConfig(algorithm);

        KGEmbeddingConfig config = KGEmbeddingConfig.builder()
                .embeddingDim(request.embeddingDim != null ? request.embeddingDim : defaults.embeddingDim())
                .epochs(request.epochs != null ? request.epochs : defaults.epochs())
                .learningRate(request.learningRate != null ? request.learningRate : defaults.learningRate())
                .batchSize(request.batchSize != null ? request.batchSize : defaults.batchSize())
                .margin(request.margin != null ? request.margin : defaults.margin())
                .negativeSamples(request.negativeSamples != null ? request.negativeSamples : defaults.negativeSamples())
                .normalizeEntities(true)
                .build();

        try {
            KGEmbeddingJob job = jobService.startTraining(request.factSheetId, algorithm, config);
            return ResponseEntity.ok(job);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get job status.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<KGEmbeddingJob> getJob(@PathVariable("jobId") String jobId) {
        return jobService.getJob(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List jobs for a fact sheet.
     */
    @GetMapping("/jobs")
    public ResponseEntity<Page<KGEmbeddingJob>> getJobs(
            @RequestParam(name = "factSheetId") Long factSheetId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        Page<KGEmbeddingJob> jobs = jobService.getJobs(factSheetId, PageRequest.of(page, size));
        return ResponseEntity.ok(jobs);
    }

    /**
     * Cancel a running job.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Void> cancelJob(@PathVariable("jobId") String jobId) {
        boolean cancelled = jobService.cancelJob(jobId);
        return cancelled ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get entity embeddings for a fact sheet.
     */
    @GetMapping("/entities/{factSheetId}")
    public ResponseEntity<List<EntityEmbeddingDTO>> getEntityEmbeddings(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(required = false, name = "search") String search,
            @RequestParam(defaultValue = "0", name = "page") int page,
            @RequestParam(defaultValue = "50", name = "size") int size
    ) {
        List<GraphNode> nodes = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);

        // Filter by search
        if (search != null && !search.isEmpty()) {
            String searchLower = search.toLowerCase();
            nodes = nodes.stream()
                    .filter(n -> n.getTitle().toLowerCase().contains(searchLower))
                    .collect(Collectors.toList());
        }

        // Paginate
        int start = page * size;
        int end = Math.min(start + size, nodes.size());
        if (start >= nodes.size()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<EntityEmbeddingDTO> result = nodes.subList(start, end).stream()
                .map(this::toEntityEmbeddingDTO)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get a single entity embedding.
     */
    @GetMapping("/entities/{factSheetId}/{nodeId}")
    public ResponseEntity<EntityEmbeddingDTO> getEntityEmbedding(
            @PathVariable("factSheetId") Long factSheetId,
            @PathVariable("nodeId") String nodeId
    ) {
        return nodeRepository.findByNodeId(nodeId)
                .filter(n -> n.getKgEmbedding() != null)
                .map(this::toEntityEmbeddingDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get embedding statistics for a fact sheet.
     */
    @GetMapping("/stats/{factSheetId}")
    public ResponseEntity<KGEmbeddingStorageService.EmbeddingStats> getStats(@PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(storageService.getStats(factSheetId));
    }

    /**
     * Clear embeddings for a fact sheet.
     */
    @DeleteMapping("/clear/{factSheetId}")
    public ResponseEntity<Void> clearEmbeddings(@PathVariable("factSheetId") Long factSheetId) {
        storageService.clearEmbeddings(factSheetId);
        loadedModels.remove(factSheetId);
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LINK PREDICTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Score a triple.
     */
    @PostMapping("/score")
    public ResponseEntity<ScoreResult> scoreTriple(@RequestBody TripleRequest request) {
        KGEmbeddingModel model = getOrLoadModel(request.factSheetId);
        if (model == null || !model.isTrained()) {
            return ResponseEntity.badRequest().build();
        }

        double score = model.scoreTriple(request.head, request.relation, request.tail);
        return ResponseEntity.ok(new ScoreResult(request.head, request.relation, request.tail, score));
    }

    /**
     * Predict tails given head and relation.
     */
    @PostMapping("/predict/tails")
    public ResponseEntity<List<EmbeddingScore>> predictTails(@RequestBody PredictRequest request) {
        KGEmbeddingModel model = getOrLoadModel(request.factSheetId);
        if (model == null || !model.isTrained()) {
            return ResponseEntity.badRequest().build();
        }

        List<EmbeddingScore> predictions = model.predictTails(
                request.head, request.relation, request.topK != null ? request.topK : 10
        );
        return ResponseEntity.ok(predictions);
    }

    /**
     * Predict heads given relation and tail.
     */
    @PostMapping("/predict/heads")
    public ResponseEntity<List<EmbeddingScore>> predictHeads(@RequestBody PredictRequest request) {
        KGEmbeddingModel model = getOrLoadModel(request.factSheetId);
        if (model == null || !model.isTrained()) {
            return ResponseEntity.badRequest().build();
        }

        List<EmbeddingScore> predictions = model.predictHeads(
                request.relation, request.tail, request.topK != null ? request.topK : 10
        );
        return ResponseEntity.ok(predictions);
    }

    /**
     * Predict relations given head and tail.
     */
    @PostMapping("/predict/relations")
    public ResponseEntity<List<EmbeddingScore>> predictRelations(@RequestBody PredictRequest request) {
        KGEmbeddingModel model = getOrLoadModel(request.factSheetId);
        if (model == null || !model.isTrained()) {
            return ResponseEntity.badRequest().build();
        }

        List<EmbeddingScore> predictions = model.predictRelations(
                request.head, request.tail, request.topK != null ? request.topK : 10
        );
        return ResponseEntity.ok(predictions);
    }

    /**
     * Find similar entities.
     */
    @GetMapping("/similar/entities/{factSheetId}/{entityName}")
    public ResponseEntity<List<EmbeddingScore>> findSimilarEntities(
            @PathVariable("factSheetId") Long factSheetId,
            @PathVariable("entityName") String entityName,
            @RequestParam(defaultValue = "10", name = "topK") int topK
    ) {
        KGEmbeddingModel model = getOrLoadModel(factSheetId);
        if (model == null || !model.isTrained()) {
            return ResponseEntity.badRequest().build();
        }

        List<EmbeddingScore> similar = model.findSimilarEntities(entityName, topK);
        return ResponseEntity.ok(similar);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALGORITHMS INFO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get available algorithms.
     */
    @GetMapping("/algorithms")
    public ResponseEntity<List<AlgorithmInfo>> getAlgorithms() {
        List<AlgorithmInfo> algorithms = Arrays.stream(KGEmbeddingAlgorithm.values())
                .map(a -> new AlgorithmInfo(a.name(), a.getDisplayName(), a.getDescription()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(algorithms);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the complete configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Update the complete configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> updateConfig(
            @RequestBody KGEmbeddingConfigService.KGEmbeddingConfig config) {
        return ResponseEntity.ok(configService.updateConfig(config));
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/config/reset")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> resetConfig() {
        return ResponseEntity.ok(configService.resetToDefaults());
    }

    /**
     * Get TransE configuration.
     */
    @GetMapping("/config/transe")
    public ResponseEntity<TrainConfig> getTransEConfig() {
        return ResponseEntity.ok(configService.getTransEConfig());
    }

    /**
     * Update TransE configuration.
     */
    @PutMapping("/config/transe")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> updateTransEConfig(
            @RequestBody TrainConfig config) {
        return ResponseEntity.ok(configService.updateTransEConfig(config));
    }

    /**
     * Get RotatE configuration.
     */
    @GetMapping("/config/rotate")
    public ResponseEntity<TrainConfig> getRotatEConfig() {
        return ResponseEntity.ok(configService.getRotatEConfig());
    }

    /**
     * Update RotatE configuration.
     */
    @PutMapping("/config/rotate")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> updateRotatEConfig(
            @RequestBody TrainConfig config) {
        return ResponseEntity.ok(configService.updateRotatEConfig(config));
    }

    /**
     * Get GraphRAG configuration.
     */
    @GetMapping("/config/graphrag")
    public ResponseEntity<GraphRAGConfig> getGraphRAGConfig() {
        return ResponseEntity.ok(configService.getGraphRAGConfig());
    }

    /**
     * Update GraphRAG configuration.
     */
    @PutMapping("/config/graphrag")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> updateGraphRAGConfig(
            @RequestBody GraphRAGConfig config) {
        return ResponseEntity.ok(configService.updateGraphRAGConfig(config));
    }

    /**
     * Get Neo4j configuration.
     */
    @GetMapping("/config/neo4j")
    public ResponseEntity<Neo4jConfig> getNeo4jConfig() {
        return ResponseEntity.ok(configService.getNeo4jConfig());
    }

    /**
     * Update Neo4j configuration.
     */
    @PutMapping("/config/neo4j")
    public ResponseEntity<KGEmbeddingConfigService.KGEmbeddingConfig> updateNeo4jConfig(
            @RequestBody Neo4jConfig config) {
        return ResponseEntity.ok(configService.updateNeo4jConfig(config));
    }

    /**
     * Test Neo4j connection.
     */
    @PostMapping("/config/neo4j/test")
    public ResponseEntity<ConnectionTestResult> testNeo4jConnection(@RequestBody Neo4jConfig config) {
        // Note: Actual Neo4j testing would require the driver to be on the classpath
        // This endpoint just validates the config format
        if (config.uri() == null || config.uri().isBlank()) {
            return ResponseEntity.ok(new ConnectionTestResult(false, "URI is required"));
        }
        if (!config.uri().startsWith("bolt://") && !config.uri().startsWith("neo4j://")) {
            return ResponseEntity.ok(new ConnectionTestResult(false, "URI must start with bolt:// or neo4j://"));
        }
        // The actual connection test would be done by kompile-graph-neo4j module
        return ResponseEntity.ok(new ConnectionTestResult(true, "Configuration format is valid"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH BUILDING CONFIGURATION (per FactSheet)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get graph building configuration for a fact sheet.
     */
    @GetMapping("/config/graph-building/{factSheetId}")
    public ResponseEntity<KGEmbeddingConfigService.GraphBuildingConfig> getGraphBuildingConfig(
            @PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(configService.getGraphBuildingConfig(factSheetId));
    }

    /**
     * Update graph building configuration for a fact sheet.
     */
    @PutMapping("/config/graph-building/{factSheetId}")
    public ResponseEntity<KGEmbeddingConfigService.GraphBuildingConfig> updateGraphBuildingConfig(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestBody KGEmbeddingConfigService.GraphBuildingConfig config) {
        return ResponseEntity.ok(configService.updateGraphBuildingConfig(factSheetId, config));
    }

    /**
     * Get available LLM providers.
     */
    @GetMapping("/config/llm-providers")
    public ResponseEntity<java.util.List<String>> getLlmProviders() {
        return ResponseEntity.ok(configService.getAvailableLlmProviders());
    }

    /**
     * Get available models for a specific LLM provider.
     */
    @GetMapping("/config/llm-models/{provider}")
    public ResponseEntity<java.util.List<String>> getLlmModels(@PathVariable("provider") String provider) {
        return ResponseEntity.ok(configService.getAvailableLlmModels(provider));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private KGEmbeddingModel getOrLoadModel(Long factSheetId) {
        return loadedModels.computeIfAbsent(factSheetId, id -> {
            // Determine algorithm from stored embeddings
            KGEmbeddingAlgorithm algorithm = storageService.getStoredAlgorithm(id);
            if (algorithm == null) {
                return null;
            }

            KGEmbeddingModel model = switch (algorithm) {
                case TRANSE -> new TransEModel();
                case ROTATE -> new RotatEModel();
            };

            if (storageService.loadEmbeddings(model, id)) {
                return model;
            }
            return null;
        });
    }

    private EntityEmbeddingDTO toEntityEmbeddingDTO(GraphNode node) {
        INDArray emb = node.getKgEmbedding();
        double[] embArray = emb != null ? emb.toDoubleVector() : null;

        return new EntityEmbeddingDTO(
                node.getNodeId(),
                node.getTitle(),
                node.getNodeType() != null ? node.getNodeType().name() : null,
                embArray,
                node.getKgEmbeddingAlgorithm() != null ? node.getKgEmbeddingAlgorithm().name() : null,
                node.getKgEmbeddingVersion(),
                node.getKgEmbeddingUpdatedAt() != null ? node.getKgEmbeddingUpdatedAt().toString() : null
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    public record TrainRequest(
            Long factSheetId,
            String algorithm,
            Integer embeddingDim,
            Integer epochs,
            Double learningRate,
            Integer batchSize,
            Double margin,
            Integer negativeSamples
    ) {}

    public record TripleRequest(
            Long factSheetId,
            String head,
            String relation,
            String tail
    ) {}

    public record PredictRequest(
            Long factSheetId,
            String head,
            String relation,
            String tail,
            Integer topK
    ) {}

    public record ScoreResult(
            String head,
            String relation,
            String tail,
            double score
    ) {}

    public record EntityEmbeddingDTO(
            String entityId,
            String entityName,
            String entityType,
            double[] embedding,
            String algorithm,
            Long version,
            String updatedAt
    ) {}

    public record AlgorithmInfo(
            String id,
            String displayName,
            String description
    ) {}

    public record ConnectionTestResult(
            boolean success,
            String message
    ) {}
}
