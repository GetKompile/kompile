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

package ai.kompile.tool.crawler;

import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool for the unified crawl-to-graph pipeline.
 * Allows LLMs to start multi-source crawl jobs that automatically
 * build knowledge graphs and vector indexes.
 */
@Component
@ConditionalOnBean(UnifiedCrawlService.class)
public class UnifiedCrawlGraphTool {

    private static final Logger log = LoggerFactory.getLogger(UnifiedCrawlGraphTool.class);

    private final UnifiedCrawlService unifiedCrawlService;

    public UnifiedCrawlGraphTool(UnifiedCrawlService unifiedCrawlService) {
        this.unifiedCrawlService = unifiedCrawlService;
    }

    // ---- Input records ----

    record SourceInput(
            String label,
            String sourceType,
            String pathOrUrl,
            Integer maxDepth,
            Integer maxDocuments,
            List<String> includePatterns,
            List<String> excludePatterns,
            Map<String, Object> properties
    ) {}

    record GraphConfigInput(
            Boolean enabled,
            List<String> entityTypes,
            List<String> relationshipTypes,
            String llmProvider,
            String modelName,
            Double temperature,
            String schemaMode,
            Boolean entityResolution,
            Double entityResolutionSimilarityThreshold,
            Boolean entityResolutionUseEmbeddings,
            Double entityResolutionEmbeddingThreshold,
            Double minConfidence,
            String customPrompt
    ) {}

    record IndexConfigInput(
            Boolean enabled,
            String collectionName,
            String chunkerName,
            Integer chunkSize,
            Integer chunkOverlap
    ) {}

    record StartUnifiedCrawlInput(
            String name,
            List<SourceInput> sources,
            GraphConfigInput graphExtraction,
            IndexConfigInput vectorIndex
    ) {}

    record JobIdInput(String jobId) {}

    // ---- Tools ----

    @Tool(name = "unified_crawl_graph", description = "Start a multi-source crawl that automatically builds a knowledge graph and vector index. "
            + "Accepts multiple sources (directories, emails, web URLs, Slack, etc.) and processes them into "
            + "a unified graph with entities and relationships extracted via LLM. "
            + "Returns the job ID for tracking progress.")
    public Map<String, Object> startUnifiedCrawl(StartUnifiedCrawlInput input) {
        try {
            if (input.sources() == null || input.sources().isEmpty()) {
                return Map.of("error", "At least one source is required");
            }

            List<UnifiedCrawlSource> sources = input.sources().stream()
                    .map(this::toSource)
                    .collect(Collectors.toList());

            UnifiedCrawlRequest.UnifiedCrawlRequestBuilder builder = UnifiedCrawlRequest.builder()
                    .name(input.name() != null ? input.name() : "Unified crawl")
                    .sources(sources);

            if (input.graphExtraction() != null) {
                builder.graphExtraction(toGraphConfig(input.graphExtraction()));
            }

            if (input.vectorIndex() != null) {
                builder.vectorIndex(toIndexConfig(input.vectorIndex()));
            }

            UnifiedCrawlJob job = unifiedCrawlService.startJob(builder.build());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", job.getJobId());
            result.put("status", job.getStatus().get().name());
            result.put("sourceCount", sources.size());
            result.put("message", "Unified crawl-to-graph job started. Use unified_crawl_status to track progress.");
            return result;

        } catch (Exception e) {
            log.error("Failed to start unified crawl", e);
            return Map.of("error", "Failed to start unified crawl: " + e.getMessage());
        }
    }

    @Tool(name = "unified_crawl_status", description = "Get the status and progress of a unified crawl-to-graph job. "
            + "Shows documents discovered, loaded, entities/relationships extracted, and per-source progress.")
    public Map<String, Object> getUnifiedCrawlStatus(JobIdInput input) {
        try {
            return unifiedCrawlService.getJob(input.jobId())
                    .map(job -> {
                        UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("jobId", snapshot.getJobId());
                        result.put("name", snapshot.getName());
                        result.put("status", snapshot.getStatus().name());
                        result.put("documentsDiscovered", snapshot.getDocumentsDiscovered());
                        result.put("documentsLoaded", snapshot.getDocumentsLoaded());
                        result.put("chunksProcessed", snapshot.getChunksProcessed());
                        result.put("documentsIndexed", snapshot.getDocumentsIndexed());
                        result.put("entitiesExtracted", snapshot.getEntitiesExtracted());
                        result.put("relationshipsExtracted", snapshot.getRelationshipsExtracted());
                        result.put("errorCount", snapshot.getErrorCount());
                        if (snapshot.getErrorMessage() != null) {
                            result.put("errorMessage", snapshot.getErrorMessage());
                        }

                        // Per-source progress
                        List<Map<String, Object>> sourceList = new ArrayList<>();
                        if (snapshot.getSourceProgress() != null) {
                            for (UnifiedCrawlJob.SourceProgress sp : snapshot.getSourceProgress()) {
                                Map<String, Object> s = new LinkedHashMap<>();
                                s.put("label", sp.getLabel());
                                s.put("sourceType", sp.getSourceType());
                                s.put("status", sp.getStatus() != null ? sp.getStatus().name() : "UNKNOWN");
                                s.put("documentsLoaded", sp.getDocumentsLoaded());
                                s.put("entitiesExtracted", sp.getEntitiesExtracted());
                                if (sp.getErrorMessage() != null) s.put("error", sp.getErrorMessage());
                                sourceList.add(s);
                            }
                        }
                        result.put("sources", sourceList);
                        return result;
                    })
                    .orElse(Map.of("error", "Job not found: " + input.jobId()));
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "unified_crawl_list", description = "List all unified crawl-to-graph jobs with their status and progress summaries.")
    public Map<String, Object> listUnifiedCrawlJobs() {
        try {
            List<Map<String, Object>> jobList = unifiedCrawlService.getAllJobs().stream()
                    .map(job -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("jobId", job.getJobId());
                        m.put("name", job.getRequest() != null ? job.getRequest().getName() : null);
                        m.put("status", job.getStatus().get().name());
                        m.put("documentsLoaded", job.getDocumentsLoaded().get());
                        m.put("entitiesExtracted", job.getEntitiesExtracted().get());
                        m.put("relationshipsExtracted", job.getRelationshipsExtracted().get());
                        m.put("sourceCount", job.getRequest() != null ? job.getRequest().getSources().size() : 0);
                        return m;
                    })
                    .collect(Collectors.toList());
            return Map.of("jobs", jobList, "total", jobList.size());
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "unified_crawl_cancel", description = "Cancel a running unified crawl-to-graph job.")
    public Map<String, Object> cancelUnifiedCrawl(JobIdInput input) {
        boolean cancelled = unifiedCrawlService.cancelJob(input.jobId());
        if (cancelled) {
            return Map.of("message", "Job cancelled", "jobId", input.jobId());
        }
        return Map.of("error", "Job not found or already finished: " + input.jobId());
    }

    @Tool(name = "unified_crawl_source_types", description = "List available source types for unified crawl-to-graph jobs. "
            + "Shows which crawlers and loaders are available (e.g., filesystem, email, web, Slack, Google Drive).")
    public Map<String, Object> listSourceTypes() {
        List<Map<String, Object>> types = unifiedCrawlService.getAvailableSourceTypes().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", t.type());
                    m.put("displayName", t.displayName());
                    m.put("description", t.description());
                    m.put("available", t.available());
                    m.put("requiredProperties", t.requiredProperties());
                    m.put("optionalProperties", t.optionalProperties());
                    return m;
                })
                .collect(Collectors.toList());
        return Map.of("sourceTypes", types);
    }

    // ---- Converters ----

    private UnifiedCrawlSource toSource(SourceInput input) {
        UnifiedCrawlSource.UnifiedCrawlSourceBuilder b = UnifiedCrawlSource.builder()
                .label(input.label())
                .pathOrUrl(input.pathOrUrl());

        if (input.sourceType() != null) {
            try {
                b.sourceType(DocumentSourceDescriptor.SourceType.valueOf(input.sourceType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown source type '{}', will auto-detect", input.sourceType());
            }
        }

        if (input.maxDepth() != null) b.maxDepth(input.maxDepth());
        if (input.maxDocuments() != null) b.maxDocuments(input.maxDocuments());
        if (input.includePatterns() != null) b.includePatterns(input.includePatterns());
        if (input.excludePatterns() != null) b.excludePatterns(input.excludePatterns());
        if (input.properties() != null) b.properties(input.properties());

        return b.build();
    }

    private GraphExtractionConfig toGraphConfig(GraphConfigInput input) {
        GraphExtractionConfig.GraphExtractionConfigBuilder b = GraphExtractionConfig.builder();

        if (input.enabled() != null) b.enabled(input.enabled());
        if (input.entityTypes() != null) b.entityTypes(input.entityTypes());
        if (input.relationshipTypes() != null) b.relationshipTypes(input.relationshipTypes());
        if (input.llmProvider() != null) b.llmProvider(input.llmProvider());
        if (input.modelName() != null) b.modelName(input.modelName());
        if (input.temperature() != null) b.temperature(input.temperature());
        if (input.entityResolution() != null) b.entityResolution(input.entityResolution());
        if (input.entityResolutionSimilarityThreshold() != null) {
            b.entityResolutionSimilarityThreshold(input.entityResolutionSimilarityThreshold());
        }
        if (input.entityResolutionUseEmbeddings() != null) {
            b.entityResolutionUseEmbeddings(input.entityResolutionUseEmbeddings());
        }
        if (input.entityResolutionEmbeddingThreshold() != null) {
            b.entityResolutionEmbeddingThreshold(input.entityResolutionEmbeddingThreshold());
        }
        if (input.minConfidence() != null) b.minConfidence(input.minConfidence());
        if (input.customPrompt() != null) b.customPrompt(input.customPrompt());

        if (input.schemaMode() != null) {
            try {
                b.schemaMode(SchemaEnforcementMode.valueOf(input.schemaMode().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown schemaMode value '{}', using default: {}", input.schemaMode(), e.getMessage());
            }
        }

        return b.build();
    }

    private VectorIndexConfig toIndexConfig(IndexConfigInput input) {
        VectorIndexConfig.VectorIndexConfigBuilder b = VectorIndexConfig.builder();

        if (input.enabled() != null) b.enabled(input.enabled());
        if (input.collectionName() != null) b.collectionName(input.collectionName());
        if (input.chunkerName() != null) b.chunkerName(input.chunkerName());
        if (input.chunkSize() != null) b.chunkSize(input.chunkSize());
        if (input.chunkOverlap() != null) b.chunkOverlap(input.chunkOverlap());

        return b.build();
    }
}
