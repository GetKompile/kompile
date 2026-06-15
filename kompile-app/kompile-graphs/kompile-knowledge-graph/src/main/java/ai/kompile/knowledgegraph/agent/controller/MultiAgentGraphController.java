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
package ai.kompile.knowledgegraph.agent.controller;

import ai.kompile.app.core.chunking.HtmlChunker;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.GraphMergeStrategy;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService.AgentInfo;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService.PersistenceSummary;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller that exposes the multi-agent graph extraction pipeline.
 *
 * <p>Base path: {@code /api/graph/multi-agent}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /agents}             — list all registered extraction agents</li>
 *   <li>{@code GET  /strategies}         — list available merge strategies with descriptions</li>
 *   <li>{@code GET  /providers}          — list available LLM providers for extraction</li>
 *   <li>{@code POST /extract}            — run extraction (in-memory only)</li>
 *   <li>{@code POST /extract-and-persist}— run extraction and persist to knowledge graph</li>
 *   <li>{@code POST /extract-from-html}  — load HTML, chunk, and extract</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/graph/multi-agent")
@Slf4j
public class MultiAgentGraphController {

    private final MultiAgentExtractionService extractionService;
    private final HtmlChunker htmlChunker;

    /** Optional — some deployments may not have the knowledge graph module active. */
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    /** Optional — registry of LLM providers for extraction. */
    @Autowired(required = false)
    private ExtractionLlmServiceRegistry llmServiceRegistry;

    @Autowired
    public MultiAgentGraphController(
            MultiAgentExtractionService extractionService,
            @Autowired(required = false) HtmlChunker htmlChunker) {
        this.extractionService = extractionService;
        this.htmlChunker = htmlChunker;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Discovery endpoints
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * List all registered extraction agents.
     */
    @GetMapping("/agents")
    public ResponseEntity<List<AgentInfo>> listAgents() {
        return ResponseEntity.ok(extractionService.getAvailableAgents());
    }

    /**
     * List all available merge strategies with human-readable descriptions.
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<Map<String, String>>> listStrategies() {
        List<Map<String, String>> strategies = new ArrayList<>();
        for (GraphMergeStrategy strategy : GraphMergeStrategy.values()) {
            Map<String, String> info = new HashMap<>();
            info.put("name", strategy.name());
            info.put("description", describeStrategy(strategy));
            strategies.add(info);
        }
        return ResponseEntity.ok(strategies);
    }

    /**
     * List all available LLM providers for extraction.
     * Pass the provider {@code id} as the {@code "llmProvider"} field in extraction requests
     * to select which LLM backend to use.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<ExtractionLlmServiceRegistry.ProviderInfo>> listProviders() {
        if (llmServiceRegistry == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(llmServiceRegistry.listProviders());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Extraction endpoints
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run multi-agent extraction and return the merged graph (no persistence).
     */
    @PostMapping("/extract")
    public ResponseEntity<ExtractionResponse> extract(@RequestBody ExtractionRequest request) {
        log.info("Multi-agent extraction request: agents={}, strategy={}, chunks={}",
                request.agentIds(),
                request.mergeStrategy(),
                countChunks(request));

        List<RetrievedDoc> chunks = buildChunks(request);
        ExtractionConfig config = buildConfig(request);

        MergedGraphResult result = extractionService.runExtraction(
                chunks,
                request.agentIds(),
                request.mergeStrategy(),
                config
        );

        return ResponseEntity.ok(ExtractionResponse.from(result));
    }

    /**
     * Run multi-agent extraction and persist the merged graph to the knowledge graph store.
     *
     * <p>Returns HTTP 503 if no {@link KnowledgeGraphService} is available.
     */
    @PostMapping("/extract-and-persist")
    public ResponseEntity<ExtractAndPersistResponse> extractAndPersist(
            @RequestBody ExtractionRequest request) {

        if (knowledgeGraphService == null) {
            return ResponseEntity.status(503).body(
                    new ExtractAndPersistResponse(null, null,
                            "KnowledgeGraphService is not available in this deployment"));
        }

        log.info("Multi-agent extract-and-persist request: agents={}, strategy={}, factSheetId={}",
                request.agentIds(), request.mergeStrategy(), request.factSheetId());

        List<RetrievedDoc> chunks = buildChunks(request);
        ExtractionConfig config = buildConfig(request);

        MergedGraphResult result = extractionService.runExtraction(
                chunks,
                request.agentIds(),
                request.mergeStrategy(),
                config
        );

        PersistenceSummary summary = extractionService.persistToGraph(
                result,
                knowledgeGraphService,
                request.factSheetId()
        );

        return ResponseEntity.ok(
                new ExtractAndPersistResponse(ExtractionResponse.from(result), summary, null));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTML file extraction endpoint
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Load an HTML file from disk, chunk it, and run entity/relation extraction.
     *
     * <p>Accepts a file path to a local HTML file, uses {@link HtmlChunker} to split it
     * into semantic chunks, then runs the multi-agent extraction pipeline.
     *
     * @param request body with {@code filePath}, optional {@code agentIds}, {@code mergeStrategy}, {@code config}
     */
    @PostMapping("/extract-from-html")
    public ResponseEntity<?> extractFromHtml(@RequestBody HtmlExtractionRequest request) {
        if (request.filePath() == null || request.filePath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filePath is required"));
        }

        if (htmlChunker == null) {
            return ResponseEntity.status(503).body(Map.of("error", "HtmlChunker not available"));
        }

        Path htmlPath = Paths.get(request.filePath().trim());
        if (!Files.exists(htmlPath) || !Files.isRegularFile(htmlPath)) {
            return ResponseEntity.badRequest().body(Map.of("error", "File does not exist: " + htmlPath));
        }

        try {
            String htmlContent = Files.readString(htmlPath);
            log.info("HTML extraction: loaded {} ({} chars)", htmlPath.getFileName(), htmlContent.length());

            // Chunk the HTML
            RetrievedDoc sourceDoc = new RetrievedDoc(
                    htmlPath.getFileName().toString(),
                    htmlContent,
                    Map.of("source", htmlPath.toString(), "format", "html")
            );
            List<RetrievedDoc> chunks = htmlChunker.chunk(sourceDoc, Map.of());
            log.info("HTML extraction: {} chunks from {}", chunks.size(), htmlPath.getFileName());

            if (chunks.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "totalEntities", 0,
                        "totalRelations", 0,
                        "chunks", 0,
                        "message", "No extractable content found in HTML file"
                ));
            }

            // Optionally limit chunks for large files
            int maxChunks = request.maxChunks() != null && request.maxChunks() > 0
                    ? request.maxChunks() : chunks.size();
            if (maxChunks < chunks.size()) {
                log.info("HTML extraction: limiting to {} of {} chunks", maxChunks, chunks.size());
                chunks = chunks.subList(0, maxChunks);
            }

            // Run extraction — inject llmProvider into config options
            ExtractionConfig config = request.config() != null ? request.config() : ExtractionConfig.defaults();
            if (request.llmProvider() != null && !request.llmProvider().isBlank()) {
                Map<String, Object> opts = new HashMap<>(config.options() != null ? config.options() : Map.of());
                opts.put("llmProvider", request.llmProvider());
                config = new ExtractionConfig(config.entityTypes(), config.relationshipTypes(),
                        config.minConfidence(), opts);
            }
            MergedGraphResult result = extractionService.runExtraction(
                    chunks,
                    request.agentIds(),
                    request.mergeStrategy(),
                    config
            );

            // Build response with chunk info
            Map<String, Object> response = new HashMap<>();
            ExtractionResponse extraction = ExtractionResponse.from(result);
            response.put("extraction", extraction);
            response.put("sourceFile", htmlPath.toString());
            response.put("sourceSize", htmlContent.length());
            response.put("chunksProcessed", chunks.size());
            response.put("chunksTotal", htmlChunker.chunk(sourceDoc, Map.of()).size());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("HTML extraction: failed to read file {}", htmlPath, e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to read file: " + e.getMessage()));
        }
    }

    /**
     * Request body for HTML file extraction.
     */
    public record HtmlExtractionRequest(
            String filePath,
            List<String> agentIds,
            String mergeStrategy,
            String llmProvider,
            Integer maxChunks,
            ExtractionConfig config
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private List<RetrievedDoc> buildChunks(ExtractionRequest request) {
        List<RetrievedDoc> chunks = new ArrayList<>();

        // Inline chunk texts from the request body
        if (request.chunkTexts() != null) {
            for (ChunkInput chunk : request.chunkTexts()) {
                if (chunk.text() == null || chunk.text().isBlank()) continue;
                Map<String, Object> metadata = chunk.metadata() != null
                        ? new HashMap<>(chunk.metadata())
                        : new HashMap<>();
                String id = chunk.id() != null && !chunk.id().isBlank()
                        ? chunk.id()
                        : java.util.UUID.randomUUID().toString();
                chunks.add(new RetrievedDoc(id, chunk.text(), metadata));
            }
        }

        return chunks;
    }

    private ExtractionConfig buildConfig(ExtractionRequest request) {
        ExtractionConfig base = request.config() != null ? request.config() : ExtractionConfig.defaults();

        // Inject llmProvider from top-level request field into config options
        if (request.llmProvider() != null && !request.llmProvider().isBlank()) {
            Map<String, Object> opts = new HashMap<>(base.options() != null ? base.options() : Map.of());
            opts.put("llmProvider", request.llmProvider());
            return new ExtractionConfig(base.entityTypes(), base.relationshipTypes(),
                    base.minConfidence(), opts);
        }
        return base;
    }

    private int countChunks(ExtractionRequest request) {
        int count = 0;
        if (request.chunkTexts() != null) count += request.chunkTexts().size();
        return count;
    }

    private String describeStrategy(GraphMergeStrategy strategy) {
        return switch (strategy) {
            case UNION ->
                    "Accept all entities and relationships from all agents. Duplicates are " +
                    "deduplicated, keeping the higher-confidence version.";
            case INTERSECTION ->
                    "Keep only entities and relationship types that appear in at least two " +
                    "agents' outputs. Confidence is averaged across agents.";
            case HIGHEST_CONFIDENCE ->
                    "When entities or relationships conflict, keep the version with the highest " +
                    "confidence score.";
            case FIRST_WINS ->
                    "First agent's output wins on conflicts. Later agents only contribute entities " +
                    "and relationships not already present.";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Request / response records
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A single inline chunk to extract from.
     *
     * @param id       optional chunk ID (UUID generated if absent)
     * @param text     chunk text content (required)
     * @param metadata optional metadata map (simple key-value pairs)
     */
    public record ChunkInput(
            String id,
            String text,
            Map<String, Object> metadata
    ) {}

    /**
     * Request body for extraction endpoints.
     *
     * @param factSheetId   optional fact sheet scope used during persistence
     * @param chunkTexts    inline chunks to extract from
     * @param agentIds      agent IDs to run; all registered agents are used if null/empty
     * @param mergeStrategy merge strategy name (UNION / INTERSECTION / HIGHEST_CONFIDENCE / FIRST_WINS)
     * @param llmProvider   LLM provider to use (e.g., "llm-chat", "claude-cli", "gemini-cli").
     *                      Use GET /providers to list available options. Null uses first available.
     * @param config        optional extraction configuration
     */
    public record ExtractionRequest(
            Long factSheetId,
            List<ChunkInput> chunkTexts,
            List<String> agentIds,
            String mergeStrategy,
            String llmProvider,
            ExtractionConfig config
    ) {}

    /**
     * Response containing the merged extraction result.
     *
     * @param totalEntities  total unique entities in the merged graph
     * @param totalRelations total unique relationships in the merged graph
     * @param totalTimeMs    wall-clock time for the entire multi-agent run
     * @param strategy       merge strategy that was applied
     * @param contributions  per-agent contribution statistics
     * @param entities       merged entity list (id, title, type, confidence)
     * @param relations      merged relation list (source, target, type, confidence)
     */
    public record ExtractionResponse(
            int totalEntities,
            int totalRelations,
            long totalTimeMs,
            String strategy,
            Map<String, Object> contributions,
            List<Map<String, Object>> entities,
            List<Map<String, Object>> relations
    ) {
        public static ExtractionResponse from(MergedGraphResult result) {
            // Build contributions map
            Map<String, Object> contributionMap = new HashMap<>();
            if (result.contributions() != null) {
                result.contributions().forEach((agentId, contribution) -> {
                    Map<String, Object> c = new HashMap<>();
                    c.put("entitiesExtracted", contribution.entitiesExtracted());
                    c.put("relationsExtracted", contribution.relationsExtracted());
                    c.put("entitiesRetained", contribution.entitiesRetained());
                    c.put("relationsRetained", contribution.relationsRetained());
                    c.put("extractionTimeMs", contribution.extractionTimeMs());
                    c.put("entityTypes", contribution.entityTypes());
                    c.put("relationTypes", contribution.relationTypes());
                    contributionMap.put(agentId, c);
                });
            }

            // Flatten entity list for JSON friendliness
            List<Map<String, Object>> entityList = new ArrayList<>();
            if (result.mergedGraph() != null && result.mergedGraph().getEntities() != null) {
                for (var entity : result.mergedGraph().getEntities()) {
                    Map<String, Object> e = new HashMap<>();
                    e.put("id", entity.getId());
                    e.put("title", entity.getTitle());
                    e.put("type", entity.getType());
                    e.put("description", entity.getDescription());
                    e.put("confidence", entity.getConfidence());
                    entityList.add(e);
                }
            }

            // Flatten relation list
            List<Map<String, Object>> relationList = new ArrayList<>();
            if (result.mergedGraph() != null && result.mergedGraph().getRelationships() != null) {
                for (var rel : result.mergedGraph().getRelationships()) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("source", rel.getSource());
                    r.put("target", rel.getTarget());
                    r.put("type", rel.getType());
                    r.put("description", rel.getDescription());
                    r.put("confidence", rel.getConfidence());
                    r.put("weight", rel.getWeight());
                    relationList.add(r);
                }
            }

            return new ExtractionResponse(
                    result.totalEntities(),
                    result.totalRelations(),
                    result.totalTimeMs(),
                    result.strategy() != null ? result.strategy().name() : null,
                    contributionMap,
                    entityList,
                    relationList
            );
        }
    }

    /**
     * Response for the extract-and-persist endpoint.
     *
     * @param extraction  the extraction result (null on failure)
     * @param persistence persistence summary (null on failure)
     * @param error       error message if the operation failed (null on success)
     */
    public record ExtractAndPersistResponse(
            ExtractionResponse extraction,
            PersistenceSummary persistence,
            String error
    ) {}
}
