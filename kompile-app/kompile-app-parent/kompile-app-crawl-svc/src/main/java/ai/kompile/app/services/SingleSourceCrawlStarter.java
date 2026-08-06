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

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.crawl.graph.CrawlPipelineStepRegistry;
import ai.kompile.crawl.graph.CrawlStepPlan;
import ai.kompile.crawl.graph.GraphExtractionPreviewService;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Starts a normal unified crawl for one user-added source.
 *
 * <p>The add-source endpoints historically used the legacy ingest path, which meant pasted text
 * and other one-off sources skipped crawl-only behavior such as content-hash incremental updates,
 * LLM relationship extraction, entity resolution, vector indexing, enrichment, and crawl job
 * observability. This service builds a one-source {@link UnifiedCrawlRequest} so those endpoints
 * use the same pipeline as multi-source crawls.</p>
 */
@Service
public class SingleSourceCrawlStarter {

    private static final Logger log = LoggerFactory.getLogger(SingleSourceCrawlStarter.class);

    /** Default synchronous-wait budget when {@link SingleSourceCrawlOptions#waitForCompletion()} is set. */
    public static final long DEFAULT_WAIT_TIMEOUT_MS = 900_000L;

    /** Bound on how many job errors are copied into the result. */
    private static final int MAX_RESULT_ERRORS = 20;

    private static final Set<UnifiedCrawlJob.Status> TERMINAL_STATUSES = Set.of(
            UnifiedCrawlJob.Status.COMPLETED,
            UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING,
            UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH,
            UnifiedCrawlJob.Status.FAILED,
            UnifiedCrawlJob.Status.CANCELLED);

    private final UnifiedCrawlService unifiedCrawlService;
    private final FactSheetService factSheetService;
    private final GraphSchemaPresetService schemaPresetService;
    private final ProcessingRouteConfigService processingRouteConfigService;
    private final GraphExtractionConfigService graphExtractionConfigService;
    private final IndexingJobHistoryService jobHistoryService;

    @Autowired
    public SingleSourceCrawlStarter(
            @Autowired(required = false) UnifiedCrawlService unifiedCrawlService,
            @Autowired(required = false) FactSheetService factSheetService,
            @Autowired(required = false) GraphSchemaPresetService schemaPresetService,
            @Autowired(required = false) ProcessingRouteConfigService processingRouteConfigService,
            @Autowired(required = false) GraphExtractionConfigService graphExtractionConfigService,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService) {
        this.unifiedCrawlService = unifiedCrawlService;
        this.factSheetService = factSheetService;
        this.schemaPresetService = schemaPresetService;
        this.processingRouteConfigService = processingRouteConfigService;
        this.graphExtractionConfigService = graphExtractionConfigService;
        this.jobHistoryService = jobHistoryService;
    }

    public boolean isAvailable() {
        return unifiedCrawlService != null;
    }

    public SingleSourceCrawlResult start(String jobName, UnifiedCrawlSource source) {
        return start(jobName, source, (ProcessingRouteConfig) null);
    }

    public SingleSourceCrawlResult start(String jobName, UnifiedCrawlSource source, ProcessingRouteConfig processingRoute) {
        SingleSourceCrawlOptions options = processingRoute != null
                ? SingleSourceCrawlOptions.builder().processingRoute(processingRoute).build()
                : null;
        return start(jobName, source, options);
    }

    /**
     * Flexible entry point for one-off single-source crawls. {@code options} (and every field on it)
     * may be null — a null options object reproduces the legacy fire-and-forget behavior exactly.
     * With options a caller can target a fact sheet directly, select pipeline steps (dependencies are
     * auto-resolved; unknown IDs are rejected), override the extraction model, and block until the job
     * reaches a terminal state.
     */
    public SingleSourceCrawlResult start(String jobName, UnifiedCrawlSource source, SingleSourceCrawlOptions options) {
        if (unifiedCrawlService == null) {
            throw new IllegalStateException("Unified crawl service is not available");
        }
        if (source == null) {
            throw new IllegalArgumentException("Source is required");
        }

        long startedAtMs = System.currentTimeMillis();
        List<String> steps = normalizeSteps(options != null ? options.steps() : null);

        GraphExtractionConfig graphConfig = defaultGraphExtractionConfig();
        if (options != null) {
            if (blankToNull(options.modelName()) != null) {
                graphConfig.setModelName(options.modelName().trim());
            }
            if (blankToNull(options.llmProvider()) != null) {
                graphConfig.setLlmProvider(options.llmProvider().trim());
            }
        }

        boolean vectorEnabled = options == null || options.vectorIndex() == null || options.vectorIndex();
        if (steps.contains("VECTOR_INDEXING")) {
            // The executor dual-gates embeddings on the step plan AND the vector config; selecting the
            // step must force the config on or the selection would silently do nothing.
            vectorEnabled = true;
        }

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name(jobName != null && !jobName.isBlank() ? jobName : "Single source crawl")
                .sources(List.of(source))
                .factSheetId(options != null ? options.factSheetId() : null)
                .factSheetName(options != null ? options.factSheetName() : null)
                .deriveOntology(options != null ? options.deriveOntology() : null)
                .graphExtraction(graphConfig)
                .vectorIndex(vectorEnabled
                        ? VectorIndexConfig.builder()
                                .enabled(true)
                                .chunkerName(source.getChunkerName())
                                .build()
                        : null)
                .processingRoute(mergeWithDefaultProcessingRoute(options != null ? options.processingRoute() : null))
                .build();
        if (!steps.isEmpty()) {
            request.setEnabledSteps(new ArrayList<>(steps));
            request.setStrictSteps(Boolean.TRUE);
        }

        resolveFactSheetScope(request);
        resolveSchemaPreset(request);
        applyDefaultProcessingRoute(request);

        // Same resolution executeJob performs — computed up front so even failed or timed-out runs
        // report the plan, including dependencies auto-added by the transitive closure.
        Map<String, String> stepsPlanned = resolveStepsPlanned(request);

        // Deliberately NOT routed through the ResourceAwareJobScheduler: single-source runs are small
        // and callers of this path want the job started (and optionally awaited) immediately.
        UnifiedCrawlJob job = unifiedCrawlService.startJob(request);
        publishToJobHistory(job, request.getName());

        Boolean completed = null;
        if (options != null && options.waitForCompletion()) {
            long timeoutMs = options.waitTimeoutMs() > 0 ? options.waitTimeoutMs() : DEFAULT_WAIT_TIMEOUT_MS;
            completed = awaitTerminal(job, timeoutMs);
        }
        return buildResult(job, request, stepsPlanned, completed, System.currentTimeMillis() - startedAtMs);
    }

    /** Trims, uppercases, and de-duplicates step IDs; rejects IDs the step registry does not know. */
    private List<String> normalizeSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String step : steps) {
            if (step == null || step.isBlank()) {
                continue;
            }
            String id = step.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (!CrawlPipelineStepRegistry.isKnown(id)) {
                throw new IllegalArgumentException("Unknown pipeline step '" + step + "'. Valid steps: "
                        + CrawlPipelineStepRegistry.all().stream()
                                .map(CrawlPipelineStepRegistry.StepDescriptor::id)
                                .collect(Collectors.joining(", ")));
            }
            if (!normalized.contains(id)) {
                normalized.add(id);
            }
        }
        return normalized;
    }

    private Map<String, String> resolveStepsPlanned(UnifiedCrawlRequest request) {
        try {
            Map<String, String> planned = new LinkedHashMap<>();
            CrawlStepPlan.from(request).actions().forEach((step, action) -> planned.put(step, action.name()));
            return planned;
        } catch (Exception e) {
            log.warn("Could not resolve step plan for single-source crawl: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Polls the live job until it reaches a terminal status or the timeout elapses. */
    private boolean awaitTerminal(UnifiedCrawlJob job, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (TERMINAL_STATUSES.contains(job.getStatus().get())) {
                return true;
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return TERMINAL_STATUSES.contains(job.getStatus().get());
    }

    private SingleSourceCrawlResult buildResult(UnifiedCrawlJob job, UnifiedCrawlRequest request,
            Map<String, String> stepsPlanned, Boolean completed, long elapsedMs) {
        List<UnifiedCrawlJob.PipelineStepSnapshot> stepSnapshots = new ArrayList<>();
        try {
            if (job.getPipelineSteps() != null) {
                for (UnifiedCrawlJob.PipelineStepProgress step : job.getPipelineSteps()) {
                    stepSnapshots.add(step.toSnapshot());
                }
            }
        } catch (Exception e) {
            log.debug("Could not snapshot pipeline steps for job {}: {}", job.getJobId(), e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        if (job.getErrors() != null) {
            for (String error : job.getErrors()) {
                if (errors.size() >= MAX_RESULT_ERRORS) {
                    break;
                }
                errors.add(error);
            }
        }
        return new SingleSourceCrawlResult(
                job.getJobId(),
                job.getStatus().get().name(),
                request.getFactSheetId(),
                request.getSources().size(),
                true,
                request.getVectorIndex() != null && request.getVectorIndex().isEnabled(),
                completed,
                Boolean.TRUE,
                stepsPlanned,
                stepSnapshots,
                job.getEntitiesExtracted().get(),
                job.getRelationshipsExtracted().get(),
                job.snapshotEntityTypeCounts(),
                job.snapshotRelationshipTypeCounts(),
                job.getChunksCreated().get(),
                job.getDocumentsLoaded().get(),
                job.getErrorCount().get(),
                errors,
                List.of(),
                elapsedMs);
    }

    GraphExtractionConfig defaultGraphExtractionConfig() {
        GraphExtractionConfig graphConfig = GraphExtractionConfig.builder().build();

        if (graphExtractionConfigService == null) {
            return graphConfig;
        }

        try {
            GraphExtractionConfigService.GraphExtractionConfig configured = graphExtractionConfigService.getConfig();
            if (configured == null) {
                return graphConfig;
            }
            graphConfig.setSchemaPresetId(blankToNull(configured.activeSchemaPresetId));
            if (configured.standardizedSchema != null) {
                graphConfig.setStandardizedSchema(configured.standardizedSchema);
            }
            if (configured.entityTypes != null) {
                graphConfig.setEntityTypes(configured.entityTypes);
            }
            if (configured.relationshipTypes != null) {
                graphConfig.setRelationshipTypes(configured.relationshipTypes);
            }
            if (configured.extractionModelProvider != null && !configured.extractionModelProvider.isBlank()) {
                graphConfig.setLlmProvider(configured.extractionModelProvider);
            }
            graphConfig.setModelName(blankToNull(configured.extractionModelName));
            if (configured.extractionTemperature != null) {
                graphConfig.setTemperature(configured.extractionTemperature);
            }
            if (configured.extractionMaxTokens != null) {
                graphConfig.setMaxTokens(configured.extractionMaxTokens);
            }
            graphConfig.setCustomPrompt(blankToNull(configured.customExtractionPrompt));
            graphConfig.setValidationPolicy(configured.validationPolicy == null
                    ? GraphExtractionValidationPolicy.defaults()
                    : configured.validationPolicy.copy());
            if (configured.schemaEnforcement != null && !configured.schemaEnforcement.isBlank()) {
                graphConfig.setSchemaMode(parseSchemaMode(configured.schemaEnforcement));
            }
            if (configured.deduplicationEnabled != null) {
                graphConfig.setEntityResolution(configured.deduplicationEnabled);
            }
            if (configured.similarityThreshold != null) {
                graphConfig.setEntityResolutionSimilarityThreshold(configured.similarityThreshold);
            }
            graphConfig.setExtractionModelProviderAllow(configured.extractionModelProviderAllow);
            graphConfig.setExtractionModelExcludeMarkers(configured.extractionModelExcludeMarkers);
            graphConfig.setExtractionModelAllow(configured.extractionModelAllow);
            graphConfig.setExtractionModelPriority(configured.extractionModelPriority);
        } catch (Exception e) {
            log.warn("Could not apply global graph extraction config to single-source crawl: {}", e.getMessage());
        }

        return graphConfig;
    }

    private void resolveFactSheetScope(UnifiedCrawlRequest request) {
        if (request == null || request.getFactSheetId() != null) {
            return;
        }
        String requestedName = request.getFactSheetName() != null
                && !request.getFactSheetName().isBlank()
                ? request.getFactSheetName().trim() : null;
        if (factSheetService == null) {
            if (requestedName != null) {
                throw new IllegalStateException(
                        "Fact-sheet service is unavailable; cannot resolve '" + requestedName + "'");
            }
            return;
        }
        try {
            if (requestedName != null) {
                FactSheet sheet = factSheetService.getSheetByName(requestedName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Fact sheet '" + requestedName + "' does not exist"));
                request.setFactSheetId(sheet.getId());
                log.info("Scoped single-source crawl '{}' to fact sheet '{}' (id={})",
                        request.getName(), sheet.getName(), sheet.getId());
                return;
            }
            FactSheet activeSheet = factSheetService.getActiveSheet();
            if (activeSheet == null || activeSheet.getId() == null) {
                throw new IllegalStateException("No active fact sheet is available for the crawl");
            }
            request.setFactSheetId(activeSheet.getId());
            log.info("Scoped single-source crawl '{}' to active fact sheet {}",
                    request.getName(), activeSheet.getId());
        } catch (IllegalArgumentException | IllegalStateException scopeFailure) {
            throw scopeFailure;
        } catch (RuntimeException lookupFailure) {
            throw new IllegalStateException("Could not resolve fact sheet for single-source crawl", lookupFailure);
        }
    }

    void resolveSchemaPreset(UnifiedCrawlRequest request) {
        GraphExtractionConfig ge = request.getGraphExtraction();
        if (ge == null || ge.getSchemaPresetId() == null || ge.getSchemaPresetId().isBlank()) {
            return;
        }
        if (schemaPresetService == null) {
            log.warn("Schema preset '{}' requested but GraphSchemaPresetService is not available", ge.getSchemaPresetId());
            return;
        }
        schemaPresetService.getSchema(ge.getSchemaPresetId()).ifPresentOrElse(
                schema -> {
                    ge.setStandardizedSchema(schema);
                    List<String> entityTypes = schema.getNodeTypes() == null
                            ? List.of()
                            : schema.getNodeTypes().stream()
                                    .filter(type -> type != null && type.getLabel() != null)
                                    .map(type -> type.getLabel())
                                    .toList();
                    List<String> relationshipTypes = schema.getRelationshipTypes() == null
                            ? List.of()
                            : schema.getRelationshipTypes().stream()
                                    .filter(type -> type != null && type.getType() != null)
                                    .map(type -> type.getType())
                                    .toList();
                    List<String> relationPatterns = schema.getPatterns() == null
                            ? List.of() : List.copyOf(schema.getPatterns());
                    if (!entityTypes.isEmpty()
                            && (ge.getEntityTypes() == null || ge.getEntityTypes().isEmpty())) {
                        ge.setEntityTypes(entityTypes);
                    }
                    if (!relationshipTypes.isEmpty()
                            && (ge.getRelationshipTypes() == null || ge.getRelationshipTypes().isEmpty())) {
                        ge.setRelationshipTypes(relationshipTypes);
                    }
                    GraphExtractionValidationPolicy policy = ge.getValidationPolicy() == null
                            ? GraphExtractionValidationPolicy.defaults()
                            : ge.getValidationPolicy().copy();
                    if (!relationPatterns.isEmpty() && policy.effectiveRelationPatterns().isEmpty()) {
                        policy.setRelationPatterns(relationPatterns);
                    }
                    ge.setValidationPolicy(policy);
                },
                () -> log.warn("Schema preset '{}' not found", ge.getSchemaPresetId()));
    }

    public ProcessingRouteConfig mergeWithDefaultProcessingRoute(ProcessingRouteConfig override) {
        if (override == null) {
            return null;
        }
        ProcessingRouteConfig base = processingRouteConfigService != null ? processingRouteConfigService.getConfig() : null;
        if (base == null) {
            return override;
        }

        ProcessingRouteConfig merged = new ProcessingRouteConfig();
        merged.setPdfRoutingMode(override.getPdfRoutingMode() != null
                ? override.getPdfRoutingMode()
                : base.getPdfRoutingMode());
        merged.setFallbackEnabled(base.isFallbackEnabled());
        merged.setBackends(base.getBackends() != null ? new ArrayList<>(base.getBackends()) : new ArrayList<>());
        merged.setVlmModelId(blankToNull(override.getVlmModelId()) != null
                ? blankToNull(override.getVlmModelId())
                : base.getVlmModelId());
        merged.setExtractTablesFromTextPdfs(override.isExtractTablesFromTextPdfs());
        merged.setTextThresholdCharsPerPage(override.getTextThresholdCharsPerPage() > 0
                ? override.getTextThresholdCharsPerPage()
                : base.getTextThresholdCharsPerPage());
        merged.setServingLaneEnabled(base.isServingLaneEnabled());
        return merged;
    }

    void applyDefaultProcessingRoute(UnifiedCrawlRequest request) {
        if (processingRouteConfigService == null || request.getProcessingRoute() != null) {
            return;
        }
        ProcessingRouteConfig defaultRoute = processingRouteConfigService.getConfig();
        if (defaultRoute != null) {
            request.setProcessingRoute(defaultRoute);
        }
    }

    private void publishToJobHistory(UnifiedCrawlJob job, String jobName) {
        if (jobHistoryService == null || job == null) {
            return;
        }
        String historyTaskId = "crawl-" + job.getJobId();
        try {
            jobHistoryService.createJob(historyTaskId, "[CRAWL] " + jobName);
            jobHistoryService.markJobRunning(historyTaskId);
        } catch (Exception e) {
            log.warn("Failed to publish single-source crawl job {} to history: {}", job.getJobId(), e.getMessage());
        }
    }

    private SchemaEnforcementMode parseSchemaMode(String value) {
        try {
            return SchemaEnforcementMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SchemaEnforcementMode.LENIENT;
        }
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    /**
     * Optional per-run overrides for {@link #start(String, UnifiedCrawlSource, SingleSourceCrawlOptions)}.
     * Every field is optional; a null options object (or null field) keeps the legacy defaults.
     */
    @Builder
    public record SingleSourceCrawlOptions(
            Long factSheetId,
            String factSheetName,
            List<String> steps,
            Boolean deriveOntology,
            Boolean vectorIndex,
            String modelName,
            String llmProvider,
            ProcessingRouteConfig processingRoute,
            boolean waitForCompletion,
            long waitTimeoutMs) {
    }

    /**
     * Result of a single-source crawl start. The first six fields are the original fire-and-forget
     * projection; the remainder are populated by {@link #buildResult} (nullable collections stay
     * non-null, {@code completed} is null when the caller did not ask to wait).
     */
    public record SingleSourceCrawlResult(
            String jobId,
            String status,
            Long factSheetId,
            int sourceCount,
            boolean graphExtractionEnabled,
            boolean vectorIndexEnabled,
            Boolean completed,
            Boolean persisted,
            Map<String, String> stepsPlanned,
            List<UnifiedCrawlJob.PipelineStepSnapshot> steps,
            Integer entityCount,
            Integer relationCount,
            Map<String, Long> entityTypeCounts,
            Map<String, Long> relationshipTypeCounts,
            Integer chunksCreated,
            Integer documentsLoaded,
            Integer errorCount,
            List<String> errors,
            List<String> warnings,
            Long elapsedMs) {
    }

    /**
     * Wire request for {@code POST /api/unified-crawl/single-source} — the flexible, modal-free
     * single-source crawl. Exactly one of {@code pathOrUrl} / {@code content} is required.
     * {@code dryRun=true} routes to the zero-persistence preview lane; otherwise the real pipeline
     * runs via {@link SingleSourceCrawlStarter}.
     */
    public record SingleSourceRunRequest(
            String sourceType,
            String label,
            String pathOrUrl,
            String content,
            String loaderName,
            String chunkerName,
            Integer maxDepth,
            Integer maxDocuments,
            String language,
            Map<String, Object> properties,
            boolean dryRun,
            List<String> steps,
            Long factSheetId,
            Boolean deriveOntology,
            Boolean vectorIndex,
            String modelName,
            String llmProvider,
            Integer waitTimeoutSeconds) {
    }

    /** Unified wire response for both lanes of {@code POST /api/unified-crawl/single-source}. */
    public record SingleSourceRunResponse(
            boolean dryRun,
            boolean completed,
            String jobId,
            Long factSheetId,
            boolean persisted,
            String status,
            Map<String, String> stepsPlanned,
            List<UnifiedCrawlJob.PipelineStepSnapshot> steps,
            int entityCount,
            int relationCount,
            Map<String, Long> entityTypeCounts,
            Map<String, Long> relationshipTypeCounts,
            int chunksCreated,
            int documentsLoaded,
            int errorCount,
            List<String> errors,
            List<String> warnings,
            List<GraphExtractionPreviewService.EntityPreview> sampleEntities,
            List<GraphExtractionPreviewService.RelationPreview> sampleRelations,
            long elapsedMs) {

        private static final int MAX_SAMPLES = 8;

        /** Maps a persist-lane starter result onto the unified wire shape (no per-entity samples). */
        public static SingleSourceRunResponse fromCrawlResult(SingleSourceCrawlResult result, List<String> warnings) {
            return new SingleSourceRunResponse(
                    false,
                    Boolean.TRUE.equals(result.completed()),
                    result.jobId(),
                    result.factSheetId(),
                    true,
                    result.status(),
                    result.stepsPlanned() != null ? result.stepsPlanned() : Map.of(),
                    result.steps() != null ? result.steps() : List.of(),
                    result.entityCount() != null ? result.entityCount() : 0,
                    result.relationCount() != null ? result.relationCount() : 0,
                    result.entityTypeCounts() != null ? result.entityTypeCounts() : Map.of(),
                    result.relationshipTypeCounts() != null ? result.relationshipTypeCounts() : Map.of(),
                    result.chunksCreated() != null ? result.chunksCreated() : 0,
                    result.documentsLoaded() != null ? result.documentsLoaded() : 0,
                    result.errorCount() != null ? result.errorCount() : 0,
                    result.errors() != null ? result.errors() : List.of(),
                    warnings != null ? warnings : List.of(),
                    List.of(),
                    List.of(),
                    result.elapsedMs() != null ? result.elapsedMs() : 0L);
        }

        /** Maps a dry-run preview onto the unified wire shape (nothing was persisted). */
        public static SingleSourceRunResponse fromPreview(
                SingleSourceCrawlPreviewService.SingleSourcePreviewResponse preview,
                List<String> extraWarnings,
                long elapsedMs) {
            GraphExtractionPreviewService.PreviewResponse graph = preview != null ? preview.graphExtraction() : null;
            List<GraphExtractionPreviewService.EntityPreview> entities =
                    graph != null && graph.entities() != null ? graph.entities() : List.of();
            List<GraphExtractionPreviewService.RelationPreview> relations =
                    graph != null && graph.relations() != null ? graph.relations() : List.of();

            Map<String, Long> entityTypeCounts = new LinkedHashMap<>();
            for (GraphExtractionPreviewService.EntityPreview entity : entities) {
                if (entity.type() != null && !entity.type().isBlank()) {
                    entityTypeCounts.merge(entity.type(), 1L, Long::sum);
                }
            }
            Map<String, Long> relationshipTypeCounts = new LinkedHashMap<>();
            for (GraphExtractionPreviewService.RelationPreview relation : relations) {
                if (relation.type() != null && !relation.type().isBlank()) {
                    relationshipTypeCounts.merge(relation.type(), 1L, Long::sum);
                }
            }

            List<String> warnings = new ArrayList<>();
            if (extraWarnings != null) {
                warnings.addAll(extraWarnings);
            }
            if (preview != null && preview.warnings() != null) {
                warnings.addAll(preview.warnings());
            }
            if (graph != null && graph.warnings() != null) {
                warnings.addAll(graph.warnings());
            }

            String status = graph != null && graph.status() != null && !graph.status().isBlank()
                    ? graph.status()
                    : (preview != null && preview.status() != null ? preview.status() : "DRY_RUN");

            return new SingleSourceRunResponse(
                    true,
                    true,
                    null,
                    null,
                    false,
                    status,
                    Map.of(),
                    List.of(),
                    graph != null ? graph.entityCount() : 0,
                    graph != null ? graph.relationCount() : 0,
                    entityTypeCounts,
                    relationshipTypeCounts,
                    preview != null ? preview.estimatedChunkCount() : 0,
                    preview != null ? preview.documentCount() : 0,
                    0,
                    List.of(),
                    warnings,
                    entities.size() > MAX_SAMPLES ? List.copyOf(entities.subList(0, MAX_SAMPLES)) : entities,
                    relations.size() > MAX_SAMPLES ? List.copyOf(relations.subList(0, MAX_SAMPLES)) : relations,
                    elapsedMs);
        }
    }
}
