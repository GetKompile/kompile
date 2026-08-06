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

import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.BatchRetryPolicy;
import ai.kompile.core.crawl.graph.DynamicBatchSizer;
import ai.kompile.core.crawl.graph.FallbackBackendSelector;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.LlmTranscriptLogger;
import ai.kompile.core.crawl.graph.ModelCapabilityResolver;
import ai.kompile.core.crawl.graph.ProcessingCapacityTracker;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ResourceGovernorAdapter;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.evaluation.graph.GraphDecisionTraceEvent;
import ai.kompile.core.evaluation.graph.GraphDecisionTraceSink;
import ai.kompile.core.evaluation.graph.GraphMissReason;
import ai.kompile.core.evaluation.graph.GraphMissStage;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.GraphConstructor.SourceSpan;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.format.LlmJsonExtractor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.ModelCapability;
import ai.kompile.core.llm.ModelContextWindows;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.crawl.graph.passes.CrawlExtractionToolBackend;
import ai.kompile.crawl.graph.passes.DecomposedExtractionExecutor;
import ai.kompile.crawl.graph.passes.ToolDrivenExtractionExecutor;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.confidence.ExtractionConfidenceStamper;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.ConceptExtractor;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.ExtractionToUnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates LLM-based and GraphConstructor-based graph extraction from documents.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 *
 * <p>Handles the graph extraction phase of the crawl pipeline:
 * <ul>
 *   <li>Dispatches to {@link GraphConstructor} when available</li>
 *   <li>Falls back to inline LLM extraction when GraphConstructor is absent</li>
 *   <li>Manages cost-balanced batch planning, adaptive parallelism, and retry policies</li>
 *   <li>Records per-document and per-batch progress through the pipeline step tracker</li>
 * </ul>
 */
@Component
class GraphExtractionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionOrchestrator.class);

    private static final int DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE = 10;

    private static final int CORPUS_SCHEMA_MAX_PASSAGES = 96;
    private static final int CORPUS_SCHEMA_MAX_PASSAGE_CHARS = 12_000;
    private static final int CORPUS_SCHEMA_MAX_TYPES = 180;
    private static final int CORPUS_SCHEMA_MIN_TYPE_OCCURRENCES = 1;
    private static final double CORPUS_SCHEMA_MIN_CONCEPT_CONFIDENCE = 0.45;
    private static final double CORPUS_SCHEMA_MIN_RELATION_STRENGTH = 0.22;
    private static final int CORPUS_SCHEMA_MAX_LABEL_LENGTH = 50;
    private static final Set<String> CORPUS_SCHEMA_CATEGORIES = Set.of("ENTITY", "TOPIC", "THEME");

    /** Default char budget per batch. When the operator leaves this untouched, the per-call budget is
     *  derived from the extraction model's real limits ({@link ModelCapability}); an explicit override
     *  (a non-default value) wins and is used as the starting budget for both local and remote models. */
    private static final int DEFAULT_GRAPH_EXTRACTION_TARGET_CHARS = 48_000;

    // Pre-allocated extractor lists — avoids per-call List.of() allocation in recordDocumentProgress
    private static final List<String> EXTRACTORS_GRAPH_CONSTRUCTOR = List.of("GraphConstructor");
    private static final List<String> EXTRACTORS_INLINE_LLM = List.of("InlineLLM");

    // Sentinel empty int[2] for getOrDefault — avoids allocation when key exists
    private static final int[] EMPTY_COUNTS = new int[2];

    /** Minimum interval between progress event recording (recordEvent + pipeline step update). */
    private static final long PROGRESS_EVENT_INTERVAL_NANOS = 250_000_000L; // 250ms
    private volatile long lastProgressEventNanos = 0L;

    /**
     * Optional SSE notifier injected by {@link UnifiedCrawlGraphServiceImpl} before each extraction
     * call and cleared afterwards. When set, the orchestrator's {@link #updateProgress} fires it on
     * every throttled tick so the crawl-step-monitor receives live PROGRESS SSE events — and therefore
     * polls the REST snapshot — during graph-extraction waves. Without this, the front-end sees no
     * events during extraction and the adaptive batch-sizing telemetry stays invisible until the step
     * completes.
     */
    volatile Consumer<UnifiedCrawlJob> progressNotifier = null;

    // -------------------------------------------------------------------------
    // Configuration — synced from the parent orchestrator on each extraction call
    // -------------------------------------------------------------------------

    volatile int graphExtractionBatchSize = DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
    volatile int graphExtractionTargetCharsPerBatch = DEFAULT_GRAPH_EXTRACTION_TARGET_CHARS;
    volatile int graphExtractionParallelism = 4;
    /** Remote (CLI/API) concurrent in-flight calls; local models use {@link #graphExtractionParallelism}.
     *  Synced from crawlGraphExtractionRemoteParallelism (project/global config) + per-job overrides. */
    volatile int graphExtractionRemoteParallelism = 4;
    /** Safety cap on chunks per batch (the model-derived char budget is the primary control). Synced
     *  from crawlGraphExtractionMaxItemsPerBatch (project/global config) + per-job overrides. */
    volatile int graphExtractionMaxItemsPerBatch = 64;
    volatile boolean costSortChunks = true;
    volatile boolean graphConstructorSkipEmbedding = true;
    volatile boolean graphConstructorPersistMatrixGraph = false;
    volatile boolean retainResultGraph = false;
    volatile int memoryCriticalThresholdPercent = 90;
    volatile int memoryWaitTimeoutSeconds = 300;
    volatile int graphExtractionBatchTimeoutSeconds = 2700;
    /** In-phase re-accumulation: failed chunks are retried per-chunk this many extra passes (with
     *  backoff + wait-for-capacity) before being deferred. Catches fast transients within the run. */
    volatile int maxInPhaseGraphRetryPasses = 2;
    volatile long graphRetryBackoffMs = 2000;
    // Per-chunk truncation ceiling for the inline-LLM extraction path (extractGraphViaLlmDocument /
    // extractGraphViaLlmChunkGroup). Raised from 12 000 / 16 000 → 50 000 / 60 000 chars so
    // large-context CLI agents (DeepSeek V4 / opencode-cli ~1 M tokens) receive substantial text
    // per call instead of being capped at the old ~3-chunk / 11.8 k-char average.
    // CrawlRuntimeConfigManager syncs these from the project/global config on every crawl start,
    // so projects targeting small/local models can override back to 12 000 in their .kompile config.
    volatile int maxCharsPerChunk = 50_000;
    volatile int maxCharsPerChunkVlm = 60_000;
    // Number of document chunks to group into a single LLM prompt call in the inline fallback path.
    volatile int graphExtractionChunksPerPrompt = 4;
    /**
     * Maximum split depth for rebatch-on-failure in the inline-LLM multi-chunk path.
     * When a group of N chunks fails (timeout / empty / parse-error), the group is halved and each
     * half retried; halving recurses up to this depth before treating individual chunks as failed.
     * Depth=4 allows splitting down to 1 from groups of up to 2^4=16 (a group of 32 has a natural
     * intermediate via the charBudget ceiling).  Set to 0 to disable rebatching.
     * Synced from crawlGraphExtractionMaxRebatchDepth in the project/global config.
     */
    volatile int maxRebatchDepth = 4;
    /**
     * Wholesale-failure threshold for the finish-early guard: skip downstream semantic steps
     * when entities==0 AND (failedChunks/totalChunks) >= this fraction.
     * Synced from crawlGraphExtractionWholesaleFailureThreshold (default 1.0 = only when all chunks fail).
     */
    volatile double wholesaleFailureThreshold = 1.0;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Autowired(required = false)
    KnowledgeGraphService knowledgeGraphService;

    /** Optional deterministic concept pre-pass. Hints guide coverage but never become evidence. */
    @Autowired(required = false)
    ConceptExtractor conceptExtractor;

    @Autowired(required = false)
    GraphConstructor graphConstructor;

    /** Read side of the unified crawl corpus assembled before extraction. */
    @Autowired(required = false)
    CrawlIndexTrackingCallback crawlIndexTrackingCallback;

    /** Production embedding store used to rank corpus passages; resolved lazily per extraction. */
    @Autowired(required = false)
    ObjectProvider<VectorStore> vectorStores;

    /** Projects persisted graph facets (embeddings, logic, provenance, analysis assets) for tools. */
    @Autowired(required = false)
    UnifiedGraphBridge unifiedGraphBridge;

    /** Production query facade over the reasoning-ready graph. */
    @Autowired(required = false)
    GraphReasoningQueryService graphReasoningQueryService;

    @Autowired
    GraphPersistenceHelper graphPersistenceHelper;

    /** Optional — stamps Opinion + provenance metadata onto every LLM-extracted edge.
     *  When absent (subprocess/test slices), the scalar fallback path below is used. */
    @Autowired(required = false)
    ExtractionConfidenceStamper confidenceStamper;

    @Autowired
    CrawlDocumentTracker documentTracker;

    @Autowired
    CrawlLlmDispatcher llmDispatcher;

    /** Active DECOMPOSED implementation: one model loop over production corpus/graph tools. */
    final ToolDrivenExtractionExecutor toolDrivenExecutor = new ToolDrivenExtractionExecutor();

    /** Complete pooled corpus visible to every shard in the active crawl, including its first shard. */
    private final ConcurrentMap<String, CrawlCorpusSnapshot> activeExtractionCorpora =
            new ConcurrentHashMap<>();

    @Autowired
    CrawlMemoryMonitor memoryMonitor;

    @Autowired
    PipelineStepTracker pipelineStepTracker;

    /** Live resource signal (CPU/RAM/heap/native/GPU-VRAM). Null on CPU-only/test contexts. */
    @Autowired(required = false)
    ResourceGovernorAdapter resourceGovernor;

    /** Resolves the extraction model's real context window + max output tokens for batch budgeting.
     *  Wired in app-main (over the model registry + model manager); null in test/subprocess slices,
     *  where {@link #resolveExtractionModelCapability} falls back to {@link ModelContextWindows}. */
    @Autowired(required = false)
    ModelCapabilityResolver modelCapabilityResolver;

    /** Live backend capacity/quota tracker — used to pick a fallback when a batch is rate-limited. */
    @Autowired(required = false)
    ProcessingCapacityTracker processingCapacityTracker;

    /** Durable archive for re-accumulated graph chunks (modular-crawl). Optional — when its impl is
     *  absent, survivors are surfaced via a warning rather than archived. */
    @Autowired(required = false)
    CrawlStepArchiveService crawlStepArchiveService;

    /** Durable completed-chunk checkpoint store used to skip graph chunks after a restart. */
    @Autowired(required = false)
    GraphExtractionCheckpointStore graphExtractionCheckpointStore;

    /** Optional production diagnostics; remains allocation-only at decision sites when enabled. */
    @Autowired(required = false)
    GraphDecisionTraceSink graphDecisionTraceSink = GraphDecisionTraceSink.noop();

    /**
     * Belt-and-suspenders transcript logger for inline LLM extraction calls.
     *
     * <p>{@link CrawlLlmDispatcher#promptWithCapacityFallback} already calls
     * {@link LlmTranscriptLogger} via its own {@code recordLlmCall} path — but only when the
     * dispatcher itself has the logger wired.  In contexts where the dispatcher's logger is absent
     * (e.g. graph subprocess, test slices) this field provides a direct recording path so that
     * extraction transcripts are never silently dropped.</p>
     *
     * <p>Usage is guarded by {@link CrawlLlmDispatcher#hasTranscriptLogger()} to prevent
     * double entries: we only record here when the dispatcher will NOT record the same call.</p>
     */
    @Autowired(required = false)
    LlmTranscriptLogger transcriptLogger;

    boolean hasGraphConstructor() {
        return graphConstructor != null;
    }

    boolean hasDecomposedDispatcher(GraphExtractionConfig config) {
        return DecomposedExtractionExecutor.isEnabled(config) && llmDispatcher != null;
    }

    /**
     * Builds a fallback-backend selector backed by the live capacity tracker, so the retry policy can
     * reroute a rate-limited graph-extraction batch onto a different backend. Returns null when no
     * route/fallback is configured (then rate-limited batches just back off on the same path).
     */
    private FallbackBackendSelector graphFallbackSelector(UnifiedCrawlJob job) {
        if (processingCapacityTracker == null || job.getRequest() == null) {
            return null;
        }
        ProcessingRouteConfig routeConfig = job.getRequest().getProcessingRoute();
        if (routeConfig == null || !routeConfig.isFallbackEnabled()) {
            return null;
        }
        return (stage, exclude) -> processingCapacityTracker.selectBackend("llm", routeConfig)
                .map(ProcessingRouteConfig.ProcessingBackend::getId)
                .filter(id -> exclude == null || !id.equals(exclude));
    }

    /**
     * Effective memory pressure (0..1) for the graph-extraction AIMD batch sizer.
     *
     * <p>Prefers the resource governor's unified signal (heap + native + GPU VRAM); falls back to
     * JVM-heap-only when no governor is wired. {@link DynamicBatchSizer#recordBatchResult} expects a
     * 0..1 fraction — passing the raw 0..100 percent here previously made the sizer read "always
     * critical" and pinned the batch at its minimum.</p>
     */
    private double stagePressure(UnifiedCrawlJob job) {
        if (resourceGovernor != null) {
            return resourceGovernor.effectiveMemoryPressure("GRAPH_EXTRACTION");
        }
        return job.getMemoryUsagePercent().get() / 100.0;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Dispatches graph extraction to either {@link GraphConstructor} or inline LLM extraction,
     * depending on availability.
     *
     * @param documents           documents to extract the graph from
     * @param config              extraction configuration (entity types, schema, prompt overrides)
     * @param targetGraph         in-memory graph to merge extracted entities/relationships into
     *                            (only populated when {@code retainResultGraph} is {@code true})
     * @param job                 the running crawl job
     * @param extractionPool      shared executor for concurrent batch extraction
     */
    void extractGraphFromDocuments(List<Document> documents,
                                   GraphExtractionConfig config,
                                   Graph targetGraph,
                                   UnifiedCrawlJob job,
                                   ExecutorService extractionPool) {
        CrawlCorpusSnapshot corpus = activateExtractionCorpus(job, documents, null);
        GraphSchema derivedCorpusSchema = deriveCorpusSchema(job, corpus, config);
        try {
            extractGraphFromDocumentsWithActiveCorpus(
                    documents, config, targetGraph, job, extractionPool, derivedCorpusSchema);
        } finally {
            deactivateExtractionCorpus(job, corpus);
        }
    }

    private void extractGraphFromDocumentsWithActiveCorpus(
            List<Document> documents,
            GraphExtractionConfig config,
            Graph targetGraph,
            UnifiedCrawlJob job,
            ExecutorService extractionPool,
            GraphSchema corpusSchemaOverride) {
        // Configure the graph constructor if available
        if (graphConstructor != null) {
            graphConstructor.configure(new GraphConstructor.ExtractionModelConfig(
                    config.getLlmProvider(),
                    config.getModelName(),
                    config.getTemperature(),
                    config.getMaxTokens(),
                    config.getCustomPrompt()
            ));
            graphConstructor.configureValidation(effectiveValidationPolicy(config));
        }

        if (graphConstructor == null && llmDispatcher == null) {
            log.warn("No GraphConstructor or LLMChat available, skipping graph extraction");
            return;
        }

        int graphErrorsBeforeExtraction = job.getErrorCount().get();

        // Pass 0: normal cost-balanced batched extraction. Returns chunks that failed all batch retries.
        // Unified crawl owns scoped fact-sheet graph persistence below, so by default the constructor
        // only extracts and returns semantic entities/relationships.
        List<Document> pending = graphConstructor != null
                ? extractGraphViaConstructor(documents, config, targetGraph, job, extractionPool,
                        corpusSchemaOverride)
                : extractGraphViaLlm(documents, config, targetGraph, job, extractionPool,
                        corpusSchemaOverride);

        // Passes 1..N: re-accumulate the failures and retry them PER CHUNK. Chunks are independent by
        // this step, so isolating one bad chunk from its (good) neighbours is safe. Backoff +
        // wait-for-capacity between passes lets fast transients (timeout, brief GPU/heap pressure, a
        // momentary backend hiccup) recover within the run instead of being lost.
        int pass = 0;
        while (pending != null && !pending.isEmpty()
                && pass < maxInPhaseGraphRetryPasses && !isCancelled(job)) {
            int before = pending.size();
            memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
            try {
                if (graphRetryBackoffMs > 0) Thread.sleep(graphRetryBackoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            // NOTE: schema pre-pass already happened at run start and is not re-derived
            // per-retry to keep in-run behavior consistent.
            pending = extractGraphChunksIndividually(pending, config, targetGraph, job,
                    corpusSchemaOverride);
                pass++;
            if (pending.size() >= before) {
                // No chunk recovered this pass — likely a sustained outage; stop hammering in-phase
                // and let the deferred resumer retry later when the condition has cleared.
                log.warn("[Job {}] Per-chunk graph retry pass {} recovered nothing ({} still failing); deferring",
                        job.getJobId(), pass, pending.size());
                break;
            }
        }

        // Survivors are archived durably (restart-safe) as a resumable GRAPH_EXTRACTION step rather
        // than dropped. The archive service is optional (modular-crawl, owned separately); until its
        // impl is present the chunks are surfaced via a warning so they are never silently lost.
        if (pending != null && !pending.isEmpty()) {
            // Lower-level attempts deliberately do not all increment the job counter: an invalid
            // response can still recover on the validation or isolated-chunk retry. Once the retry
            // loop ends, however, every surviving chunk is a permanent failure for this run. Count
            // each at least once without double-counting failures already recorded by timeout or
            // infrastructure paths.
            if (!isCancelled(job)) {
                int graphErrorsRecorded = Math.max(0,
                        job.getErrorCount().get() - graphErrorsBeforeExtraction);
                int uncountedPermanentFailures = Math.max(0, pending.size() - graphErrorsRecorded);
                if (uncountedPermanentFailures > 0) {
                    job.getErrorCount().addAndGet(uncountedPermanentFailures);
                }
            }
            String archiveDir = crawlStepArchiveService != null
                    ? crawlStepArchiveService.archive(job, "GRAPH_EXTRACTION", new ArrayList<>(pending), config)
                    : null;
            if (archiveDir != null) {
                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                        "Graph chunks archived for later retry",
                        pending.size() + " chunk(s) failed all in-phase retries; archived (resumable) at " + archiveDir);
                log.warn("[Job {}] {} graph chunk(s) archived for deferred retry at {}",
                        job.getJobId(), pending.size(), archiveDir);
            } else {
                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                        "Graph chunks failed all in-phase retries",
                        pending.size() + " chunk(s) failed all in-phase retries (durable archive not yet available)");
                log.warn("[Job {}] {} graph chunk(s) failed all in-phase retries; durable archive unavailable",
                        job.getJobId(), pending.size());
            }
        }
    }

    /**
     * Makes the complete pre-pass corpus visible before any shard is extracted. Current-run text wins
     * over persisted copies, while complete persisted passages fill incremental/retry gaps.
     */
    CrawlCorpusSnapshot activateExtractionCorpus(
            UnifiedCrawlJob job,
            List<Document> currentDocuments,
            String requestedSnapshotId) {
        CrawlCorpusSnapshot snapshot =
                buildExtractionCorpus(job, currentDocuments, requestedSnapshotId);
        String key = corpusKey(job);
        if (key != null) {
            activeExtractionCorpora.put(key, snapshot);
        }
        return snapshot;
    }

    void deactivateExtractionCorpus(UnifiedCrawlJob job, CrawlCorpusSnapshot snapshot) {
        String key = corpusKey(job);
        if (key != null && snapshot != null) {
            activeExtractionCorpora.remove(key, snapshot);
        }
    }

    int activeExtractionCorpusCount() {
        return activeExtractionCorpora.size();
    }

    private CrawlCorpusSnapshot extractionCorpus(
            UnifiedCrawlJob job,
            Document currentDocument,
            ExtractionTaskContext task) {
        String key = corpusKey(job);
        CrawlCorpusSnapshot active = key == null ? null : activeExtractionCorpora.get(key);
        if (active != null) {
            return active;
        }
        String requested = task == null ? null : task.corpusSnapshotId();
        return buildExtractionCorpus(job,
                currentDocument == null ? List.of() : List.of(currentDocument), requested);
    }

    private CrawlCorpusSnapshot buildExtractionCorpus(
            UnifiedCrawlJob job,
            List<Document> currentDocuments,
            String requestedSnapshotId) {
        Map<String, CrawlCorpusPassage> passages = new LinkedHashMap<>();
        int ordinal = 0;
        if (currentDocuments != null) {
            for (Document document : currentDocuments) {
                if (document == null || document.getText() == null) {
                    continue;
                }
                String id = hasText(document.getId())
                        ? document.getId()
                        : Objects.toString(job == null ? null : job.getJobId(), "preview")
                                + ":chunk:" + ordinal;
                Map<String, Object> metadata = cleanMetadata(document.getMetadata());
                int chunkIndex = metadataInt(metadata, ordinal, "chunk_index", "chunkIndex");
                String contentHash = metadataString(metadata, "content_hash", "contentHash");
                if (!hasText(contentHash)) {
                    contentHash = sha256Hex(document.getText());
                }
                passages.put(id, new CrawlCorpusPassage(
                        id, chunkIndex, document.getText(), contentHash, metadata, true));
                ordinal++;
            }
        }

        String persistedSnapshotId = null;
        Long factSheetId = jobFactSheetId(job);
        if (crawlIndexTrackingCallback != null && factSheetId != null) {
            try {
                Optional<CrawlCorpusSnapshot> persisted =
                        crawlIndexTrackingCallback.loadCorpusSnapshot(factSheetId);
                if (persisted.isPresent()) {
                    persistedSnapshotId = persisted.get().snapshotId();
                    for (CrawlCorpusPassage passage : persisted.get().passages()) {
                        if (passage != null && hasText(passage.chunkId())) {
                            passages.putIfAbsent(passage.chunkId(), passage);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[Job {}] Unified corpus pre-pass could not load persisted fact-sheet {} "
                                + "passages; current-run passages remain available: {}",
                        job == null ? "?" : job.getJobId(), factSheetId, e.toString());
            }
        }

        List<CrawlCorpusPassage> pooled = List.copyOf(passages.values());
        String snapshotId = hasText(requestedSnapshotId)
                ? requestedSnapshotId
                : corpusSnapshotId(persistedSnapshotId, pooled);
        return new CrawlCorpusSnapshot(snapshotId, pooled);
    }

    private static String corpusKey(UnifiedCrawlJob job) {
        return job != null && hasText(job.getJobId()) ? job.getJobId() : null;
    }

    private static Map<String, Object> cleanMetadata(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                cleaned.put(key, value);
            }
        });
        return cleaned;
    }

    private static int metadataInt(
            Map<String, Object> metadata,
            int fallback,
            String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(value.toString());
                } catch (NumberFormatException ignored) {
                    // Try the next project-standard spelling.
                }
            }
        }
        return fallback;
    }

    private static String metadataString(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private static String corpusSnapshotId(
            String persistedSnapshotId,
            List<CrawlCorpusPassage> passages) {
        MessageDigest digest = sha256();
        updateDigest(digest, hasText(persistedSnapshotId) ? persistedSnapshotId : "current-run");
        passages.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(passage ->
                        Objects.toString(passage.chunkId(), "")))
                .forEach(passage -> {
                    updateDigest(digest, passage.chunkId());
                    updateDigest(digest, passage.contentHash());
                    updateDigest(digest, Boolean.toString(passage.completeText()));
                });
        return "extraction-corpus-v1:" + hex(digest.digest());
    }

    /**
     * Derives schema hints from the real unified crawl corpus using the deterministic concept extractor.
     *
     * <p>This is a production-aligned pass intended to keep the schema vocabulary close to what
     * the actual corpus talks about, while staying bounded and explicit about risk:</p>
     * <ul>
     *   <li>Only top passage-sized windows are sampled to avoid runaway extractor cost.</li>
     *   <li>Only entity/topic/theme concepts above a confidence threshold are used.</li>
     *   <li>Only relation candidates above a confidence threshold are used.</li>
     *   <li>Everything is sanitized into schema-safe labels to avoid validator noise.</li>
     * </ul>
     */
    private GraphSchema deriveCorpusSchema(UnifiedCrawlJob job,
                                         CrawlCorpusSnapshot corpus,
                                         GraphExtractionConfig config) {
        if (conceptExtractor == null) {
            log.debug("[Job {}] Unified-corpus schema pre-pass skipped: no conceptExtractor bean",
                    job == null ? "?" : job.getJobId());
            return null;
        }

        List<CrawlCorpusPassage> passages = corpus == null ? List.of() : corpus.passages();
        if (passages == null || passages.isEmpty()) {
            log.debug("[Job {}] Unified-corpus schema pre-pass skipped: corpus empty",
                    job == null ? "?" : job.getJobId());
            return null;
        }

        Map<String, String> passageTexts = passages.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((CrawlCorpusPassage p) -> p.chunkIndex())
                        .thenComparing(p -> p.chunkId() == null ? "" : p.chunkId()))
                .limit(CORPUS_SCHEMA_MAX_PASSAGES)
                .map(passage -> {
                    if (!hasText(passage.content())) {
                        return null;
                    }
                    String chunkId = hasText(passage.chunkId()) ? passage.chunkId() : "chunk-" + UUID.randomUUID();
                    String content = passage.content();
                    if (content.length() > CORPUS_SCHEMA_MAX_PASSAGE_CHARS) {
                        content = content.substring(0, CORPUS_SCHEMA_MAX_PASSAGE_CHARS);
                    }
                    return Map.entry(chunkId, content);
                })
                .filter(Objects::nonNull)
                .filter(entry -> hasText(entry.getKey()) && hasText(entry.getValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (existing, ignored) -> existing,
                        LinkedHashMap::new));

        if (passageTexts.isEmpty()) {
            log.debug("[Job {}] Unified-corpus schema pre-pass skipped: no extractable passage text",
                    job == null ? "?" : job.getJobId());
            return null;
        }

        String jobId = job == null ? "?" : job.getJobId();
        try {
            ConceptExtractor.ExtractionConfig prepassConfig = new ConceptExtractor.ExtractionConfig(
                    CORPUS_SCHEMA_MAX_TYPES,
                    CORPUS_SCHEMA_MIN_CONCEPT_CONFIDENCE,
                    true,
                    true,
                    true,
                    CORPUS_SCHEMA_CATEGORIES.stream().toList(),
                    true);
            Map<String, ConceptExtractor.ExtractionResult> prepass = conceptExtractor
                    .extractConceptsFromPassages(
                    passageTexts, prepassConfig);
            if (prepass == null || prepass.isEmpty()) {
                return null;
            }

            Map<String, Integer> nodeSupport = new LinkedHashMap<>();
            Map<String, Double> nodeConfidence = new LinkedHashMap<>();
            for (Map.Entry<String, ConceptExtractor.ExtractionResult> entry : prepass.entrySet()) {
                    if (entry == null || entry.getValue() == null || entry.getValue().concepts() == null) {
                        continue;
                    }
                    for (ConceptExtractor.ExtractedConcept concept : entry.getValue().concepts()) {
                    if (concept == null || !CORPUS_SCHEMA_CATEGORIES.contains(concept.category())) {
                        continue;
                    }
                    double confidence = concept.confidence();
                    if (!Double.isFinite(confidence) || confidence < CORPUS_SCHEMA_MIN_CONCEPT_CONFIDENCE) {
                        continue;
                    }
                    String label = deriveSchemaLabel(concept.name(), concept.normalizedName(), concept.normalizedName());
                    if (!hasText(label)) {
                        continue;
                    }
                    int support = Math.max(1, concept.frequency());
                    nodeSupport.put(label, nodeSupport.getOrDefault(label, 0) + support);
                    nodeConfidence.put(label, Math.max(nodeConfidence.getOrDefault(label, 0.0), confidence));
                }
            }

            Map<String, Integer> relationSupport = new LinkedHashMap<>();
            Map<String, Double> relationStrength = new LinkedHashMap<>();
            for (Map.Entry<String, ConceptExtractor.ExtractionResult> entry : prepass.entrySet()) {
                if (entry == null || entry.getValue() == null || entry.getValue().relationships() == null) {
                    continue;
                }
                for (ConceptExtractor.ConceptRelationship relation : entry.getValue().relationships()) {
                    if (relation == null) {
                        continue;
                    }
                    double strength = relation.strength();
                    if (!Double.isFinite(strength) || strength < CORPUS_SCHEMA_MIN_RELATION_STRENGTH) {
                        continue;
                    }
                    String label = deriveSchemaLabel(relation.relationshipType(), null,
                            relation.relationshipType());
                    if (!hasText(label)) {
                        continue;
                    }
                    relationSupport.put(label, relationSupport.getOrDefault(label, 0) + 1);
                    relationStrength.put(label, Math.max(relationStrength.getOrDefault(label, 0.0), strength));
                }
            }

            List<NodeType> derivedNodeTypes = nodeSupport.entrySet().stream()
                    .filter(entry -> entry.getValue() >= CORPUS_SCHEMA_MIN_TYPE_OCCURRENCES)
                    .sorted((left, right) -> {
                        int countCmp = Integer.compare(
                                right.getValue(),
                                left.getValue());
                        if (countCmp != 0) {
                            return countCmp;
                        }
                        double leftScore = nodeConfidence.getOrDefault(left.getKey(), 0.0);
                        double rightScore = nodeConfidence.getOrDefault(right.getKey(), 0.0);
                        return Double.compare(rightScore, leftScore);
                    })
                    .limit(CORPUS_SCHEMA_MAX_TYPES)
                    .map(entry -> new NodeType(entry.getKey(),
                            "Inferred from unified-corpus concept extraction",
                            null))
                    .toList();

            List<RelationshipType> derivedRelationTypes = relationSupport.entrySet().stream()
                    .sorted((left, right) -> {
                        int countCmp = Integer.compare(
                                right.getValue(),
                                left.getValue());
                        if (countCmp != 0) {
                            return countCmp;
                        }
                        double leftScore = relationStrength.getOrDefault(left.getKey(), 0.0);
                        double rightScore = relationStrength.getOrDefault(right.getKey(), 0.0);
                        return Double.compare(rightScore, leftScore);
                    })
                    .limit(CORPUS_SCHEMA_MAX_TYPES)
                    .map(entry -> new RelationshipType(entry.getKey(),
                            "Inferred from unified-corpus co-occurrence extraction", null))
                    .toList();

            if (derivedNodeTypes.isEmpty() && derivedRelationTypes.isEmpty()) {
                log.debug("[Job {}] Unified-corpus schema pre-pass completed with no inferred candidates", jobId);
                return null;
            }

            log.debug("[Job {}] Unified-corpus schema pre-pass inferred {} node types and {} relation types from {} passages",
                    jobId, derivedNodeTypes.size(), derivedRelationTypes.size(), passageTexts.size());

            return new GraphSchema(
                    derivedNodeTypes.isEmpty() ? null : derivedNodeTypes,
                    derivedRelationTypes.isEmpty() ? null : derivedRelationTypes,
                    null);
        } catch (RuntimeException e) {
            log.warn("[Job {}] Unified-corpus schema pre-pass failed; continuing without derived schema: {}",
                    jobId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return null;
        }
    }

    private static String deriveSchemaLabel(String preferred,
                                          String normalized,
                                          String fallback) {
        String raw = hasText(preferred) ? preferred : normalized;
        if (!hasText(raw)) {
            raw = fallback;
        }
        if (!hasText(raw)) {
            return null;
        }
        String transformed = raw.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (!hasText(transformed)) {
            return null;
        }
        if (transformed.length() > CORPUS_SCHEMA_MAX_LABEL_LENGTH) {
            transformed = transformed.substring(0, CORPUS_SCHEMA_MAX_LABEL_LENGTH);
        }
        if (!Character.isLetter(transformed.charAt(0))) {
            transformed = "TYPE_" + transformed;
        }
        return transformed.substring(0, Math.min(transformed.length(), CORPUS_SCHEMA_MAX_LABEL_LENGTH));
    }

    private static String sha256Hex(String value) {
        MessageDigest digest = sha256();
        updateDigest(digest, value);
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    /**
     * Retry a re-accumulated set of failed chunks one chunk at a time (so a single poisoned chunk
     * never drags down its independent neighbours). Returns the chunks that still failed.
     */
    private List<Document> extractGraphChunksIndividually(List<Document> docs, GraphExtractionConfig config,
                                                          Graph targetGraph, UnifiedCrawlJob job,
                                                          GraphSchema corpusSchemaOverride) {
        List<Document> stillFailing = new ArrayList<>();
        String extractionPrompt = graphConstructor == null
                ? buildExtractionPrompt(config, buildGraphSchema(config, corpusSchemaOverride))
                : null;
        for (Document doc : docs) {
            if (isCancelled(job)) {
                stillFailing.add(doc);
                continue;
            }
            memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
            boolean failed;
            if (graphConstructor != null) {
                failed = !extractSingleChunkViaConstructor(doc, config, targetGraph, job);
            } else {
                failed = extractGraphViaLlmDocument(doc, 0, docs.size(), extractionPrompt, config,
                        targetGraph, job, corpusSchemaOverride, new ConcurrentHashMap<>(),
                        new ConcurrentHashMap<>(), jobFactSheetId(job));
            }
            if (failed) {
                stillFailing.add(doc);
            }
        }
        return stillFailing;
    }

    /**
     * Extract a single chunk through the GraphConstructor in isolation. Returns true on success.
     */
    private boolean extractSingleChunkViaConstructor(Document doc, GraphExtractionConfig config,
                                                     Graph targetGraph, UnifiedCrawlJob job) {
        // Guard: a blank chunk cannot yield entities and would throw in toRetrievedDoc.
        if (!hasExtractableText(doc)) {
            log.debug("[Job {}] Per-chunk constructor: skipping blank/empty chunk id={}",
                    job.getJobId(), doc != null ? doc.getId() : "(null)");
            return false;
        }
        GraphSchema schema = buildGraphSchema(config);
        SchemaEnforcementMode mode = config.getSchemaMode() != null
                ? config.getSchemaMode() : SchemaEnforcementMode.LENIENT;
        List<RetrievedDoc> single = List.of(toRetrievedDoc(doc));
        AgentCallContext.setJobId(job.getJobId());
        try {
            Graph graph = graphConstructor.constructGraphFromDocs(single, schema, mode,
                    graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph, null);
            if (graph == null) {
                recordEmptyConstructorChunk(job, doc, "GraphConstructor returned null graph");
                return false;
            }
            int entities = graphEntityCount(graph);
            int rels = graphRelationshipCount(graph);
            if (!hasSemanticGraphOutput(graph)) {
                releaseInMemoryGraph(graph);
                recordEmptyConstructorChunk(job, doc, "GraphConstructor returned empty graph");
                return false;
            }
            GraphPersistenceHelper.GraphPersistResult persisted =
                    graphPersistenceHelper.persistConstructedGraphBatch(job, graph, single, config);
            recordGraphExtractionCheckpoint(job, config, single, persisted);
            if (retainResultGraph) {
                synchronized (targetGraph) {
                    mergeGraphInto(graph, targetGraph, config);
                }
            }
            job.getEntitiesExtracted().addAndGet(entities);
            job.getRelationshipsExtracted().addAndGet(rels);
            documentTracker.recordDocumentProgress(job, toRetrievedDoc(doc), "GRAPH_EXTRACTION", "COMPLETED",
                    0, entities, rels, "Recovered chunk on per-chunk retry", null,
                    EXTRACTORS_GRAPH_CONSTRUCTOR, true);
            releaseInMemoryGraph(graph);
            return true;
        } catch (Exception e) {
            log.debug("[Job {}] Per-chunk constructor extraction failed for chunk: {}",
                    job.getJobId(), e.getMessage());
            return false;
        } finally {
            AgentCallContext.setJobId(null);
        }
    }

    /**
     * Extracts one chunk's graph and hands it back without writing anything.
     *
     * <p>A partition run needs the extraction the crawl already does, minus the persistence. It
     * stages every chunk it reads and commits the merged result once — so a per-chunk write here
     * would put the same facts in the graph twice, the second time unmerged. The write happens in
     * {@link PartitionGraphCommitter}, through this same
     * {@link GraphPersistenceHelper#persistConstructedGraphBatch}.</p>
     *
     * <p>Failures propagate rather than becoming a null. A staged run reads null as "this chunk
     * taught the partition nothing", which is a claim about the corpus; an extraction that fell
     * over has made no such claim, and the lifecycle defers the chunk for a later round when it is
     * told what happened.</p>
     *
     * @return what the chunk taught, or null when it holds no extractable text
     * @throws IllegalStateException when this deployment has no {@link GraphConstructor} to run
     */
    public Graph extractChunkGraph(Document doc, GraphExtractionConfig config, UnifiedCrawlJob job) {
        Graph context = new Graph();
        context.setEntities(new ArrayList<>());
        context.setRelationships(new ArrayList<>());
        return extractChunkGraph(doc, config, job, context, null);
    }

    /**
     * Context-aware partition extraction. DECOMPOSED mode is honored even when a
     * GraphConstructor bean is present; otherwise the constructor receives the same bounded task
     * and graph state rather than a context-free one-chunk prompt.
     */
    public Graph extractChunkGraph(Document doc, GraphExtractionConfig config, UnifiedCrawlJob job,
                                   Graph graphContext, ExtractionTaskContext taskContext) {
        Objects.requireNonNull(config, "extraction config");
        if (!hasExtractableText(doc)) {
            return null;
        }

        ExtractionTaskContext contextualTask = contextualize(taskContext, graphContext);
        if (DecomposedExtractionExecutor.isEnabled(config)) {
            if (llmDispatcher == null) {
                throw new IllegalStateException("decomposed partition extraction is configured but "
                        + "no LLM dispatcher is available");
            }
            String response = extractViaDecomposedPasses(doc.getText(), doc, config,
                    null, graphContext, job, contextualTask);
            if (!isUsableLlmResponse(response)) {
                throw new IllegalStateException(badLlmResponseMessage(
                        "decomposed partition extraction", response));
            }
            try {
                String json = extractJsonFromResponse(response);
                if (json == null) {
                    throw new IllegalStateException("decomposed partition extraction returned no JSON");
                }
                GraphExtractionSchema.ExtractionResult result =
                        GraphExtractionValidator.fromJson(json);
                var validation = GraphExtractionValidator.validate(result,
                        effectiveValidationPolicy(config), buildGraphSchema(config),
                        knownEntityTypes(graphContext, job, config));
                traceValidation(result, validation);
                if (!validation.valid()) {
                    throw new IllegalArgumentException("decomposed partition extraction validation "
                            + "failed: " + validationFeedback(validation.errors(),
                            effectiveValidationPolicy(config)));
                }
                return GraphExtractionValidator.toGraph(result, doc.getText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("decomposed partition extraction returned "
                        + "malformed JSON: " + e.getOriginalMessage(), e);
            }
        }

        if (graphConstructor == null) {
            throw new IllegalStateException("no GraphConstructor is configured; this deployment "
                    + "cannot extract a partition's chunks");
        }
        GraphSchema schema = buildGraphSchema(config);
        SchemaEnforcementMode mode = config.getSchemaMode() != null
                ? config.getSchemaMode() : SchemaEnforcementMode.LENIENT;
        AgentCallContext.setJobId(job == null ? null : job.getJobId());
        try {
            return graphConstructor.constructGraphFromDocs(List.of(toRetrievedDoc(doc)), schema,
                    mode, graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph, null,
                    contextualTask);
        } finally {
            AgentCallContext.setJobId(null);
        }
    }

    /** Adds newly extracted output to the in-run graph state used by later small-model tasks. */
    void mergeIntoContext(Graph produced, Graph context, GraphExtractionConfig config) {
        if (produced == null || context == null || config == null) {
            return;
        }
        synchronized (context) {
            if (context.getEntities() == null) {
                context.setEntities(new ArrayList<>());
            }
            if (context.getRelationships() == null) {
                context.setRelationships(new ArrayList<>());
            }
            mergeGraphInto(produced, context, config);
        }
    }

    private ExtractionTaskContext contextualize(ExtractionTaskContext task, Graph graph) {
        if (task == null) {
            return null;
        }
        String graphId = graph != null && hasText(graph.getId()) ? graph.getId() : "in-run";
        String revision = graphId + ":" + graphEntityCount(graph) + ":"
                + graphRelationshipCount(graph);
        return task.withGraphState(revision, renderGraphContext(graph, task.subjects()));
    }

    private static String renderGraphContext(Graph graph, List<String> subjects) {
        if (graph == null || graph.getEntities() == null || graph.getEntities().isEmpty()) {
            return "(no prior entities are available; resolve identities from the source text)";
        }
        Set<String> needles = subjects == null ? Set.of() : subjects.stream()
                .filter(Objects::nonNull).map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Entity> ordered = new ArrayList<>(graph.getEntities());
        ordered.sort(Comparator.comparing((Entity entity) -> !matchesSubject(entity, needles))
                .thenComparing(entity -> Objects.toString(entity.getTitle(), "")));
        List<Entity> chosen = ordered.stream().limit(16).toList();
        Set<String> chosenIds = chosen.stream().map(Entity::getId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        StringBuilder context = new StringBuilder("ENTITIES:\n");
        for (Entity entity : chosen) {
            context.append("- id=").append(Objects.toString(entity.getId(), "?"))
                    .append(" | title=").append(Objects.toString(entity.getTitle(), "?"))
                    .append(" | type=").append(Objects.toString(entity.getType(), "ENTITY"));
            if (entity.getAliases() != null && !entity.getAliases().isEmpty()) {
                context.append(" | aliases=").append(String.join(" / ",
                        entity.getAliases().stream().filter(Objects::nonNull).limit(6).toList()));
            }
            context.append('\n');
        }
        if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
            context.append("RELATIONSHIPS (prior identity/reconciliation context):\n");
            graph.getRelationships().stream()
                    .filter(relation -> chosenIds.contains(relation.getSource())
                            || chosenIds.contains(relation.getTarget()))
                    .limit(24)
                    .forEach(relation -> context.append("- ")
                            .append(Objects.toString(relation.getSource(), "?"))
                            .append(" --").append(Objects.toString(relation.getType(), "RELATED_TO"))
                            .append("--> ").append(Objects.toString(relation.getTarget(), "?"))
                            .append('\n'));
        }
        return context.length() <= 6000 ? context.toString() : context.substring(0, 6000);
    }

    private static boolean matchesSubject(Entity entity, Set<String> subjects) {
        if (entity == null || subjects == null || subjects.isEmpty()) {
            return false;
        }
        List<String> values = new ArrayList<>();
        values.add(entity.getId());
        values.add(entity.getTitle());
        if (entity.getAliases() != null) {
            values.addAll(entity.getAliases());
        }
        return values.stream().filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> subjects.stream().anyMatch(subject -> value.contains(subject)
                        || subject.contains(value)));
    }

    private static int graphEntityCount(Graph graph) {
        return graph != null && graph.getEntities() != null ? graph.getEntities().size() : 0;
    }

    private static int graphRelationshipCount(Graph graph) {
        return graph != null && graph.getRelationships() != null ? graph.getRelationships().size() : 0;
    }

    static boolean hasSemanticGraphOutput(Graph graph) {
        return graphEntityCount(graph) + graphRelationshipCount(graph) > 0;
    }

    static boolean isUsableLlmResponse(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        return !response.stripLeading().startsWith("Error:");
    }

    private static String badLlmResponseMessage(String label, String response) {
        if (response == null) {
            return label + " returned null";
        }
        if (response.isBlank()) {
            return label + " returned empty response";
        }
        String trimmed = response.stripLeading();
        if (trimmed.startsWith("Error:")) {
            return label + " returned error payload: "
                    + (trimmed.length() > 180 ? trimmed.substring(0, 180) : trimmed);
        }
        return label + " returned unusable response";
    }

    private void recordEmptyConstructorChunk(UnifiedCrawlJob job, Document doc, String reason) {
        if (job != null) {
            job.getErrorCount().incrementAndGet();
        }
        log.warn("[Job {}] Per-chunk GraphConstructor extraction produced no semantic output for chunk {}: {}",
                job != null ? job.getJobId() : "?", doc != null ? doc.getId() : "(null)", reason);
        if (job != null && doc != null) {
            documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                    "GraphConstructor returned no semantic output", reason,
                    EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        }
    }

    // -------------------------------------------------------------------------
    // GraphConstructor-based extraction
    // -------------------------------------------------------------------------

    /**
     * Delegates extraction to GraphConstructor which handles persistence and embedding.
     */
    private List<Document> extractGraphViaConstructor(List<Document> documents,
                                             GraphExtractionConfig config,
                                             Graph targetGraph,
                                             UnifiedCrawlJob job,
                                             ExecutorService extractExec,
                                             GraphSchema corpusSchemaOverride) {
        GraphSchema schema = buildGraphSchema(config, corpusSchemaOverride);
        SchemaEnforcementMode mode = config.getSchemaMode() != null
                ? config.getSchemaMode()
                : SchemaEnforcementMode.LENIENT;

        // Chunks that fail all batch retries this pass are returned for re-accumulation. docById maps
        // a failed RetrievedDoc back to its source Document (they share ids — see toRetrievedDoc).
        List<Document> failed = new CopyOnWriteArrayList<>();
        Map<String, Document> docById = new HashMap<>(documents.size());

        // Convert all documents → RetrievedDoc, then send all at once to constructGraphFromDocs.
        // The MatrixGraphConstructor handles parallelism internally (now 8 concurrent LLM threads).
        // BulkGraphSyncService handles JPA sync in <50ms even for 500+ entities, so batching for
        // DB performance is no longer needed.
        //
        // IMPORTANT: blank/empty chunks are filtered out here via filterAndConvertDocs before being
        // wrapped in RetrievedDoc or sent over the graph-subprocess RPC. A single null-text doc in
        // a batch causes the entire RPC call to fail (Jackson's convertValue on the subprocess aborts
        // the whole list deserialization), so filtering here protects all batch-mates. A WARN is
        // logged with the count and chunk ids so operators know real content was skipped.
        List<RetrievedDoc> allRetrievedDocs = filterAndConvertDocs(documents, job.getJobId());
        for (Document doc : documents) {
            if (hasExtractableText(doc) && doc.getId() != null) {
                docById.put(doc.getId(), doc);
            }
        }

        if (allRetrievedDocs.isEmpty()) return failed;

        int totalDocs = allRetrievedDocs.size();
        int checkpointSkipped = 0;
        List<RetrievedDoc> pendingRetrievedDocs = allRetrievedDocs;
        if (graphExtractionCheckpointStore != null) {
            Set<String> completedKeys = graphExtractionCheckpointStore.completedChunkKeys(jobFactSheetId(job), config);
            if (!completedKeys.isEmpty()) {
                pendingRetrievedDocs = new ArrayList<>(allRetrievedDocs.size());
                for (RetrievedDoc doc : allRetrievedDocs) {
                    String key = graphExtractionCheckpointStore.chunkKey(doc);
                    if (key != null && completedKeys.contains(key)) {
                        checkpointSkipped++;
                        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "SKIPPED",
                                0, 0, 0, "Skipped completed graph extraction checkpoint", null,
                                EXTRACTORS_GRAPH_CONSTRUCTOR, true);
                    } else {
                        pendingRetrievedDocs.add(doc);
                    }
                }
                if (checkpointSkipped > 0) {
                    documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                            "Skipped completed graph extraction checkpoints",
                            checkpointSkipped + "/" + totalDocs + " chunk(s) already persisted for this extraction config");
                    log.info("[Job {}] Skipping {} of {} graph extraction chunk(s) from durable checkpoint",
                            job.getJobId(), checkpointSkipped, totalDocs);
                }
            }
        }

        if (pendingRetrievedDocs.isEmpty()) {
            resetGraphExtractionProgress(job, totalDocs);
            completeGraphExtractionProgress(job);
            pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.COMPLETED,
                    totalDocs, totalDocs, 0, 0, 0, 0, null,
                    "Graph extraction already complete from checkpoint");
            documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                    "Graph extraction already complete from checkpoint",
                    totalDocs + " chunk(s) skipped");
            return failed;
        }

        int plannedDocs = pendingRetrievedDocs.size();

        // ── Provider-aware, output-token-safe adaptive batching ──────────────────────────────────
        // Each remote CLI/API LLM call carries a large *fixed* per-call cost (a 1.3k-char prompt was
        // measured at 156s), so we amortize by packing many chunks per call. BUT the extraction
        // OUTPUT (entities+rels JSON) grows with input, and a batch that exceeds the model's max
        // output tokens silently truncates (truncated JSON → parse failure → empty graph, no error).
        // So rather than a fixed huge batch we ramp the per-call char budget with an AIMD sizer that
        // grows while calls yield entities and shrinks on failure / zero-yield (truncation) / memory
        // pressure — discovering the output-limited sweet spot per dataset. Local SameDiff/ONNX
        // models get a smaller, faster profile (small context, cheap calls, higher parallelism).
        // Resolve the extraction model's REAL limits once (context window + max output tokens) and
        // budget batches from them — see ModelCapability. The binding constraint for extraction is the
        // model's max OUTPUT tokens (the entities JSON), so init/min/maxInputChars() are derived from
        // those true numbers (at 4 chars/token), not guessed. The yield-gated AIMD sizer then ramps
        // within [min, max] and shrinks on zero-yield (truncation) / failure / memory pressure.
        ModelCapability modelCap = resolveExtractionModelCapability(job, config);
        boolean remoteBackend = !modelCap.local();
        boolean targetOverridden = graphExtractionTargetCharsPerBatch != DEFAULT_GRAPH_EXTRACTION_TARGET_CHARS;
        int initChars = targetOverridden
                ? Math.max(1, graphExtractionTargetCharsPerBatch) : modelCap.initInputChars();
        int maxChars = targetOverridden
                ? Math.max(Math.max(1, graphExtractionTargetCharsPerBatch), modelCap.maxInputChars())
                : modelCap.maxInputChars();
        int minChars = Math.max(1, Math.min(modelCap.minInputChars(), initChars));
        // Item count is only a safety cap — the char budget is the real control. Configurable via
        // crawlGraphExtractionMaxItemsPerBatch (project/global .kompile config) and per job.
        // graphExtractionMaxItemsPerBatch is an upper-bound cap, so we take the MIN of the
        // configured batch size and the cap (not MAX, which would ignore the batch size setting).
        int maxItems = Math.min(resolveGraphExtractionBatchSize(config), Math.max(1, graphExtractionMaxItemsPerBatch));
        // Ramp step = one starting budget per healthy wave (init → max in a few waves). Memory
        // shrink thresholds reuse the crawl's configured memory ceiling instead of new constants.
        double criticalFrac = Math.min(0.99, Math.max(0.5, memoryCriticalThresholdPercent / 100.0));
        DynamicBatchSizer charSizer = DynamicBatchSizer.builder()
                .stageId("GRAPH_EXTRACTION_CHARS")
                .minBatchSize(minChars).maxBatchSize(maxChars).initialBatchSize(initChars)
                .additiveIncrease(Math.max(1, initChars)).multiplicativeDecrease(0.6)
                .memoryPressureThreshold(Math.max(0.5, criticalFrac - 0.08))
                .memoryCriticalThreshold(criticalFrac)
                .successThresholdForIncrease(1)
                .build();

        // Sort chunks heaviest-first so the greedy wave packer keeps each batch near the char budget.
        List<RetrievedDoc> sorted = pendingRetrievedDocs;
        if (costSortChunks) {
            sorted.sort((a, b) -> Long.compare(
                    estimateTextCost(b.getText(), b.getMetadata()),
                    estimateTextCost(a.getText(), a.getMetadata())));
        }
        long totalCharsEst = 0L;
        for (RetrievedDoc d : sorted) {
            totalCharsEst += Math.max(1L, estimateTextCost(d.getText(), d.getMetadata()));
        }
        // Display-only estimate. Large-context models can collapse the char-budget estimate to one
        // wave, so keep an item-count lower bound; otherwise the UI shows impossible labels like
        // GRAPH_BATCHES 8/1 even while chunks continue progressing.
        int itemBatchFloor = (plannedDocs + Math.max(1, maxItems) - 1) / Math.max(1, maxItems);
        int charBatchEstimate = (int) Math.max(1, (totalCharsEst + initChars - 1) / initChars);
        final int totalBatches = Math.max(itemBatchFloor, charBatchEstimate);
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicInteger globalBatchIndex = new AtomicInteger(0);
        // Remote: cap at the configured remote parallelism (few fat calls — each remote call is costly).
        // Local: the configured graphExtractionParallelism (small fast calls pipeline well). Both come
        // from project/global .kompile config + per-job overrides.
        // Pass totalDocs as the third argument so the item-count lower bound can correct the
        // char-budget wave estimate for small-document corpora on large-context models (where
        // the char estimate collapses to 1 and would incorrectly cap parallelism to 1).
        int resolvedParallelism = resolveGraphExtractionParallelism(job, totalBatches, plannedDocs);
        int remoteCap = Math.max(1, graphExtractionRemoteParallelism);
        if (remoteBackend && isLargeContextModel(modelCap)) {
            // Large-context remote models are selected specifically to amortize extraction over fat
            // prompts. Do not keep them behind the legacy two-call remote cap; let the configured graph
            // extraction parallelism drive wave width while still respecting any higher remote cap.
            remoteCap = Math.max(remoteCap, Math.max(1, graphExtractionParallelism));
        }
        // Local lane: clamp wave width to the serving lane's actual concurrent-generate capacity
        // (one loaded model instance = serial generation unless the server reports otherwise).
        // Wider waves would only queue at the server, and the queue wait would be recorded as
        // model latency — poisoning the latency/throughput EWMAs and the adaptive timeouts.
        int localCap = resolvedParallelism;
        if (!remoteBackend && modelCap.local() && modelCapabilityResolver != null) {
            try {
                localCap = Math.max(1, modelCapabilityResolver.localGenerationConcurrency());
                if (localCap < resolvedParallelism) {
                    log.info("[Job {}] Local serving lane reports concurrency {} — clamping extraction parallelism from {}",
                            job.getJobId(), localCap, resolvedParallelism);
                }
            } catch (Exception e) {
                localCap = resolvedParallelism;
            }
        }
        int parallelism = remoteBackend
                ? Math.min(resolvedParallelism, remoteCap)
                : Math.min(resolvedParallelism, localCap);
        final int waveWidth = Math.max(1, parallelism);
        OuterParallelismAdvisor outerAdvisor = new OuterParallelismAdvisor(parallelism);
        DynamicBatchSizer graphBatchSizer = DynamicBatchSizer.forGraphExtraction(maxItems);
        resetGraphExtractionProgress(job, totalDocs);
        if (checkpointSkipped > 0) {
            incrementGraphChunksProcessed(job, checkpointSkipped);
        }
        pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                checkpointSkipped, totalDocs, 0, 0, totalBatches, 0, null,
                "Planned graph extraction batches");
        job.getCurrentFile().set("(graph extraction: " + plannedDocs + "/" + totalDocs + " chunks, "
                + (remoteBackend ? "remote" : "local") + " adaptive batching from ~" + initChars + " chars/call)");
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                "Planned graph extraction batches",
                "chunks=" + plannedDocs + "/" + totalDocs + ", skipped=" + checkpointSkipped
                        + ", backend=" + (remoteBackend ? "remote" : "local")
                        + ", model=" + modelCap.modelId()
                        + ", contextTokens=" + modelCap.contextTokens()
                        + ", maxOutputTokens=" + modelCap.maxOutputTokens()
                        + ", startChars=" + initChars + ", maxChars=" + maxChars
                        + ", maxItems=" + maxItems + ", parallelism=" + parallelism);
        log.info("[Job {}] Starting graph extraction for {}/{} chunks (checkpoint skipped {}), backend={}, model={}, contextTokens={}, maxOutputTokens={}, adaptive char budget {}..{} (start {}), maxItems={}, parallelism={}",
                job.getJobId(), plannedDocs, totalDocs, checkpointSkipped, remoteBackend ? "remote" : "local",
                modelCap.modelId(), modelCap.contextTokens(), modelCap.maxOutputTokens(),
                minChars, maxChars, initChars, maxItems, parallelism);
        memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION", "after planning graph batches");

        try {
            try {
                AtomicInteger completedBatches = new AtomicInteger(0);
                // Adaptive wave loop: each wave pulls the next `waveWidth` cost-balanced batches sized
                // at the AIMD char budget, runs them through the (unchanged) submit/retry body below,
                // then feeds the wave's yield back to the sizer so the next wave grows or shrinks.
                while (cursor.get() < plannedDocs && !isCancelled(job)
                        && !Thread.currentThread().isInterrupted()) {
                    int charTarget = charSizer.currentBatchSize();
                    List<CostBatch<RetrievedDoc>> batches =
                            planGraphWave(sorted, cursor, charTarget, maxItems, waveWidth, globalBatchIndex);
                    if (batches.isEmpty()) break;
                    long waveStartMs = System.currentTimeMillis();
                    long waveEntitiesBefore = job.getEntitiesExtracted().get();
                    long waveChars = 0L;
                    for (CostBatch<RetrievedDoc> wb : batches) waveChars += wb.cost();
                List<Future<GraphBatchResult>> futures = new ArrayList<>(batches.size());
                for (CostBatch<RetrievedDoc> batch : batches) {
                    if (isCancelled(job)) {
                        return failed;
                    }
                    // Block submission until the advisor's concurrency gate has a free permit.
                    // This enforces adaptive parallelism: when memory is critical, only 1
                    // batch runs; when memory is low, permits ramp back up.
                    try {
                        outerAdvisor.acquirePermit();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return failed;
                    }
                    futures.add(extractExec.submit(() -> {
                        long batchStartTime = System.currentTimeMillis();
                        UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                        step.getActiveTasks().incrementAndGet();
                        step.setLastUpdatedAt(Instant.now());
                        try {
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled before batch start");
                                return new GraphBatchResult(batch, null, null);
                            }
                            memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled while waiting for memory");
                                return new GraphBatchResult(batch, null, null);
                            }
                            job.getCurrentBatchSize().set(batch.items().size());
                            String batchLabel = batch.index() + "/" + totalBatches;
                            job.getCurrentBatchStep().set("GRAPH_BATCH " + batchLabel);
                            for (RetrievedDoc item : batch.items()) {
                                documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                                        "LLM graph batch " + batchLabel,
                                        null, EXTRACTORS_GRAPH_CONSTRUCTOR, false);
                            }
                            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                                    "Extracting graph batch " + batchLabel,
                                    batch.items().size() + " chunk(s), cost=" + batch.cost());
                            Map<String, RetrievedDoc> batchDocsById = new HashMap<>(batch.items().size());
                            for (RetrievedDoc item : batch.items()) {
                                if (item.getId() != null) batchDocsById.putIfAbsent(item.getId(), item);
                            }
                            Set<String> terminalDocIds = ConcurrentHashMap.newKeySet();
                            GraphConstructor.ProgressListener progressListener = progress ->
                                    recordGraphConstructorProgress(job, batch, batchDocsById, totalBatches,
                                            progress, terminalDocIds);
                            AgentCallContext.setJobId(job.getJobId());
                            Graph graph;
                            try {
                                graph = graphConstructor.constructGraphFromDocs(batch.items(), schema, mode,
                                        graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph,
                                        progressListener);
                            } finally {
                                AgentCallContext.setJobId(null);
                            }

                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled; discarding batch result");
                                return new GraphBatchResult(batch, null, null);
                            }

                            if (graph == null) {
                                throw new IllegalStateException("GraphConstructor returned null graph for batch " + batchLabel);
                            }
                            int entities = graphEntityCount(graph);
                            int rels = graphRelationshipCount(graph);
                            if (!hasSemanticGraphOutput(graph)) {
                                releaseInMemoryGraph(graph);
                                throw new IllegalStateException("GraphConstructor returned empty graph for batch " + batchLabel
                                        + " (" + batch.items().size() + " chunk(s), cost=" + batch.cost() + ")");
                            }

                            GraphPersistenceHelper.GraphPersistResult persisted = graphPersistenceHelper.persistConstructedGraphBatch(job, graph, batch.items(), config);
                            recordGraphExtractionCheckpoint(job, config, batch.items(), persisted);
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled after graph persistence");
                                releaseInMemoryGraph(graph);
                                return new GraphBatchResult(batch, null, null);
                            }
                            if (terminalDocIds.isEmpty()) {
                                recordGraphExtractionDiagnostics(job, graph, batch, totalBatches);
                                recordGraphExtractionBatchPerDocument(job, graph, batch, totalBatches);
                            }
                            if (retainResultGraph) {
                                synchronized (targetGraph) {
                                    mergeGraphInto(graph, targetGraph, config);
                                }
                            }
                            if (terminalDocIds.isEmpty()) {
                                job.getEntitiesExtracted().addAndGet(entities);
                                job.getRelationshipsExtracted().addAndGet(rels);
                            }
                            log.info("[Job {}] Graph extraction batch {} complete: {} entities, {} rels (totals: {}/{})",
                                    job.getJobId(), batchLabel, entities, rels,
                                    job.getEntitiesExtracted().get(), job.getRelationshipsExtracted().get());
                            if (persisted.entities() > 0 || persisted.relationships() > 0) {
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                        "Persisted semantic graph batch " + batchLabel,
                                        persisted.entities() + " entities, " + persisted.relationships()
                                                + " relationships written to fact-sheet graph");
                            } else {
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                                        "Semantic graph batch persisted zero records " + batchLabel,
                                        entities + " extracted entities, " + rels + " extracted relationships");
                            }

                            int missingTerminalUpdates = Math.max(0, batch.items().size() - terminalDocIds.size());
                            if (missingTerminalUpdates > 0) {
                                incrementGraphChunksProcessed(job, missingTerminalUpdates);
                            }
                            int processed = normalizeGraphChunksProcessed(job);
                            int done = completedBatches.incrementAndGet();
                            UnifiedCrawlJob.PipelineStepProgress graphStepForUpdate = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                            pipelineStepTracker.applyPipelineStepUpdate(graphStepForUpdate, UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                    processed, totalDocs, graphStepForUpdate.getFailedItems().get(),
                                    done, totalBatches, 0, null,
                                    "Completed graph batch " + batchLabel);
                            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                                    "Completed graph batch " + batchLabel,
                                    processed + "/" + totalDocs + " chunk(s)");
                            // Notify outer advisor of batch completion for adaptive parallelism
                            memoryMonitor.updateMemorySnapshot(job);
                            double heapPct = job.getMemoryUsagePercent().get() / 100.0;
                            long batchElapsed = System.currentTimeMillis() - batchStartTime;
                            UnifiedCrawlJob.TuningDecision parallelismDecision =
                                    outerAdvisor.afterBatchComplete(batchElapsed, heapPct);
                            if (parallelismDecision != null) {
                                job.recordTuningDecision(parallelismDecision);
                            }
                            // Record success for AIMD graph batch sizer (governor-aware 0..1 pressure)
                            graphBatchSizer.recordBatchResult(batch.items().size(), batchElapsed, true, stagePressure(job));
                            graphBatchSizer.publishStats(job);
                            job.getCurrentBatchStep().set("GRAPH_BATCHES " + done + "/" + totalBatches
                                    + " parallelism=" + outerAdvisor.getCurrentParallelism());
                            documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                    "Completed graph batch " + batch.index() + "/" + totalBatches,
                                    processed + "/" + totalDocs + " chunk(s)");
                            if (graph != null) {
                                releaseInMemoryGraph(graph);
                            }
                            batchDocsById.clear();
                            terminalDocIds.clear();
                            batch.items().clear();

                            return new GraphBatchResult(batch, null, null);
                        } finally {
                            memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                    "after graph batch " + batch.index() + "/" + totalBatches);
                            step.getActiveTasks().updateAndGet(v -> Math.max(0, v - 1));
                            step.setLastUpdatedAt(Instant.now());
                            outerAdvisor.releasePermit();
                        }
                    }));
                }

                // Retry policy for graph extraction batches
                BatchRetryPolicy<RetrievedDoc> graphRetryPolicy = BatchRetryPolicy.forGraphExtraction();

                for (int i = 0; i < futures.size(); i++) {
                    Future<GraphBatchResult> extractFuture = futures.get(i);
                    CostBatch<RetrievedDoc> plannedBatch = batches.get(i);
                    if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                        cancelGraphFutures(futures);
                        recordCancelledGraphBatch(job, plannedBatch, "Graph extraction cancelled");
                        break;
                    }
                    try {
                        int batchTimeout = graphExtractionBatchTimeoutSeconds;
                        extractFuture.get(batchTimeout, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        extractFuture.cancel(true);
                        int batchTimeout = graphExtractionBatchTimeoutSeconds;
                        long timeoutMin = batchTimeout / 60;
                        log.warn("[Job {}] Graph extraction batch {}/{} timed out after {}s ({}min)",
                                job.getJobId(), plannedBatch.index(), totalBatches, batchTimeout, timeoutMin);
                        job.getErrors().add("Graph extraction batch " + plannedBatch.index() + "/" + totalBatches
                                + " timed out after " + timeoutMin + "min");
                        job.getErrorCount().incrementAndGet();
                        for (RetrievedDoc item : plannedBatch.items()) {
                            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction batch timed out",
                                    "Timed out after " + batchTimeout + "s",
                                    EXTRACTORS_GRAPH_CONSTRUCTOR, true);
                        }
                        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                "Graph extraction batch timed out",
                                "batch=" + plannedBatch.index() + "/" + totalBatches);
                        continue;
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        String errorDetail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                        log.warn("[Job {}] Graph extraction batch {}/{} failed: {}",
                                job.getJobId(), plannedBatch.index(), totalBatches, errorDetail);
                        // Record failure for AIMD graph batch sizer
                        memoryMonitor.updateMemorySnapshot(job);
                        graphBatchSizer.recordBatchResult(plannedBatch.items().size(), 0, false,
                                stagePressure(job));
                        graphBatchSizer.publishStats(job);
                        // Evaluate retry policy
                        BatchRetryPolicy.RetryDecision<RetrievedDoc> retryDecision = graphRetryPolicy.evaluateFailure(
                                plannedBatch.index(), plannedBatch.items(), plannedBatch.items().size(),
                                errorDetail, null, graphFallbackSelector(job), job);
                        switch (retryDecision.getAction()) {
                            case RETRY_SAME_BACKEND:
                            case RETRY_FALLBACK_BACKEND: {
                                log.info("[Job {}] Retrying graph extraction batch {}/{} (attempt {}), backoff={}ms",
                                        job.getJobId(), plannedBatch.index(), totalBatches,
                                        retryDecision.getAttempt(), retryDecision.getBackoffMs());
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                                        "Retrying graph extraction batch " + plannedBatch.index() + "/" + totalBatches,
                                        "attempt=" + retryDecision.getAttempt() + ", backoff=" + retryDecision.getBackoffMs() + "ms"
                                                + ", reason=" + retryDecision.getReason());
                                try { Thread.sleep(retryDecision.getBackoffMs()); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    cancelGraphFutures(futures);
                                    recordCancelledGraphBatch(job, plannedBatch, "Graph extraction interrupted during retry backoff");
                                    return failed;
                                }
                                // Resubmit the failed batch to the executor
                                try {
                                    outerAdvisor.acquirePermit();
                                } catch (InterruptedException ie2) {
                                    Thread.currentThread().interrupt();
                                    cancelGraphFutures(futures);
                                    return failed;
                                }
                                final CostBatch<RetrievedDoc> retryBatch = plannedBatch;
                                futures.set(i, extractExec.submit(() -> {
                                    long batchStartTime = System.currentTimeMillis();
                                    UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                                    step.getActiveTasks().incrementAndGet();
                                    step.setLastUpdatedAt(Instant.now());
                                    try {
                                        if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                            recordCancelledGraphBatch(job, retryBatch, "Graph extraction retry cancelled");
                                            return new GraphBatchResult(retryBatch, null, null);
                                        }
                                        memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                                        job.getCurrentBatchSize().set(retryBatch.items().size());
                                        job.getCurrentBatchStep().set("GRAPH_BATCH " + retryBatch.index() + "/" + totalBatches + " (retry)");
                                        AgentCallContext.setJobId(job.getJobId());
                                        Graph graph;
                                        try {
                                            graph = graphConstructor.constructGraphFromDocs(retryBatch.items(), schema, mode,
                                                    graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph, null);
                                        } finally {
                                            AgentCallContext.setJobId(null);
                                        }
                                        if (graph == null) {
                                            throw new IllegalStateException("GraphConstructor returned null graph for retry batch "
                                                    + retryBatch.index() + "/" + totalBatches);
                                        }
                                        int entities = graphEntityCount(graph);
                                        int rels = graphRelationshipCount(graph);
                                        if (!hasSemanticGraphOutput(graph)) {
                                            releaseInMemoryGraph(graph);
                                            throw new IllegalStateException("GraphConstructor returned empty graph for retry batch "
                                                    + retryBatch.index() + "/" + totalBatches
                                                    + " (" + retryBatch.items().size() + " chunk(s), cost=" + retryBatch.cost() + ")");
                                        }
                                        GraphPersistenceHelper.GraphPersistResult persisted = graphPersistenceHelper.persistConstructedGraphBatch(job, graph, retryBatch.items(), config);
                                        recordGraphExtractionCheckpoint(job, config, retryBatch.items(), persisted);
                                        if (retainResultGraph) {
                                            synchronized (targetGraph) {
                                                mergeGraphInto(graph, targetGraph, config);
                                            }
                                        }
                                        job.getEntitiesExtracted().addAndGet(entities);
                                        job.getRelationshipsExtracted().addAndGet(rels);
                                        releaseInMemoryGraph(graph);
                                        int done2 = completedBatches.incrementAndGet();
                                        incrementGraphChunksProcessed(job, retryBatch.items().size());
                                        long batchElapsed = System.currentTimeMillis() - batchStartTime;
                                        memoryMonitor.updateMemorySnapshot(job);
                                        graphBatchSizer.recordBatchResult(retryBatch.items().size(), batchElapsed, true,
                                                stagePressure(job));
                                        graphBatchSizer.publishStats(job);
                                        return new GraphBatchResult(retryBatch, null, null);
                                    } finally {
                                        memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                                "after graph batch retry " + retryBatch.index() + "/" + totalBatches);
                                        step.getActiveTasks().updateAndGet(v -> Math.max(0, v - 1));
                                        step.setLastUpdatedAt(Instant.now());
                                        outerAdvisor.releasePermit();
                                    }
                                }));
                                // Re-process this index to collect the retry future result
                                i--;
                                continue;
                            }
                            case DEAD_LETTER: {
                                // Don't drop the chunks — re-accumulate them for per-chunk retry then
                                // deferral. Chunks are independent here, so isolating one bad chunk from
                                // its (good) neighbours on the next pass is safe.
                                log.warn("[Job {}] Graph batch {}/{} exhausted batch retries; re-accumulating {} chunk(s)",
                                        job.getJobId(), plannedBatch.index(), totalBatches, plannedBatch.items().size());
                                job.getErrorCount().incrementAndGet();
                                for (RetrievedDoc item : plannedBatch.items()) {
                                    Document src = item.getId() != null ? docById.get(item.getId()) : null;
                                    if (src != null) {
                                        failed.add(src);
                                    }
                                    documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                                            "Re-accumulated after batch failure (will retry per-chunk)", errorDetail,
                                            EXTRACTORS_GRAPH_CONSTRUCTOR, false);
                                }
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                                        "Graph batch re-accumulated for retry",
                                        plannedBatch.items().size() + " chunk(s) after " + retryDecision.getAttempt() + " batch attempts");
                                int processed = incrementGraphChunksProcessed(job, plannedBatch.items().size());
                                int done = completedBatches.incrementAndGet();
                                UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                                pipelineStepTracker.applyPipelineStepUpdate(step, UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                        processed, totalDocs, step.getFailedItems().get(),
                                        done, totalBatches, 0, null,
                                        "Re-accumulated graph batch " + plannedBatch.index() + "/" + totalBatches);
                                plannedBatch.items().clear();
                                continue;
                            }
                            case ABORT:
                            default: {
                                // Fatal — same as before: cancel all futures and throw
                                cancelGraphFutures(futures);
                                String fatalMessage = "Fatal graph extraction failure: " + errorDetail;
                                job.getErrors().add(fatalMessage);
                                job.getErrorCount().incrementAndGet();
                                pipelineStepTracker.failPipelineStep(job, "GRAPH_EXTRACTION", fatalMessage);
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                        "Fatal graph extraction failure",
                                        fatalMessage);
                                throw new IllegalStateException(fatalMessage, cause);
                            }
                        }
                    } catch (CancellationException ce) {
                        if (isCancelled(job)) {
                            recordCancelledGraphBatch(job, plannedBatch, "Graph extraction batch cancelled");
                            break;
                        }
                        throw ce;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        cancelGraphFutures(futures);
                        recordCancelledGraphBatch(job, plannedBatch, "Graph extraction interrupted");
                        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN", "Graph extraction interrupted",
                                "batch=" + plannedBatch.index() + "/" + totalBatches);
                        return failed;
                    }
                }
                    // ── wave complete: feed the adaptive char-budget sizer ──
                    long waveElapsedMs = System.currentTimeMillis() - waveStartMs;
                    long waveEntities = job.getEntitiesExtracted().get() - waveEntitiesBefore;
                    memoryMonitor.updateMemorySnapshot(job);
                    // Yield gate: a full-size batch that produced ZERO entities almost certainly hit
                    // the model's max-output-token ceiling (truncated JSON → parse failure → empty
                    // graph), not genuinely empty input — record as a failure so the sizer shrinks
                    // instead of growing into a zero-yield regime.
                    boolean healthyYield = waveEntities > 0
                            || waveChars <= (long) charSizer.getMinBatchSize() * 2;
                    charSizer.recordBatchResult(charTarget, waveElapsedMs, healthyYield, stagePressure(job));
                    // Record the char-budget wave decision (including the zero-yield output-ceiling
                    // guard) into the job's tuning history. The char sizer deliberately does NOT
                    // publishStats() (that would clobber the item-count adaptiveBatchSize field), so
                    // we emit the decision inline here.
                    int charNew = charSizer.currentBatchSize();
                    if (charTarget != charNew || !healthyYield) {
                        DynamicBatchSizer.AdjustDirection charDir = charSizer.getLastDirection();
                        String charReason = !healthyYield ? "zero_yield" : charSizer.getLastReason();
                        String charDetail = !healthyYield
                                ? "yield " + waveEntities + " ent / " + waveChars + " chars (output-ceiling guard)"
                                : "char budget " + charTarget + " -> " + charNew;
                        job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                                .timestamp(Instant.now())
                                .stage("GRAPH_EXTRACTION_CHARS")
                                .oldValue(charTarget)
                                .newValue(charNew)
                                .direction(charDir != null ? charDir.name() : "HOLD")
                                .reason(charReason != null ? charReason : (charDir != null ? charDir.name() : "HOLD"))
                                .detail(charDetail)
                                .memoryPercent(job.getMemoryUsagePercent().get())
                                .build());
                    }
                    // Note: char-budget stats are surfaced via the batch-step label + log below; the
                    // per-batch item-count graphBatchSizer keeps ownership of the adaptiveBatchSize UI
                    // field, so we deliberately do NOT publishStats() here (it would clobber it).
                    job.getCurrentBatchStep().set("GRAPH_WAVE next≈" + charSizer.currentBatchSize()
                            + " chars (yield " + waveEntities + " ent / " + waveChars + " chars)");
                }
            } finally {
                // Don't shutdown — shared pool is reused across jobs
                job.getCurrentBatchSize().set(0);
                job.getCurrentBatchStep().set(null);
            }
        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.error("[Job {}] Graph extraction failed: {}", job.getJobId(), errorDetail, e);
            job.getErrors().add("Graph extraction failed: " + errorDetail);
            job.getErrorCount().incrementAndGet();
            if (isFatalLlmUnavailable(e)) {
                throw new RuntimeException("Graph extraction failed: " + errorDetail, e);
            }
        }
        return failed;
    }

    // -------------------------------------------------------------------------
    // Inline LLM-based extraction (fallback when no GraphConstructor)
    // -------------------------------------------------------------------------

    /**
     * Fallback: inline LLM extraction when no GraphConstructor is available.
     * Entities are persisted to KnowledgeGraphService and also stored in-memory on the job's result graph.
     */
    private List<Document> extractGraphViaLlm(List<Document> documents,
                                     GraphExtractionConfig config,
                                     Graph targetGraph,
                                     UnifiedCrawlJob job,
                                     ExecutorService llmExec,
                                     GraphSchema corpusSchemaOverride) {
        GraphSchema schema = buildGraphSchema(config, corpusSchemaOverride);
        String extractionPrompt = buildExtractionPrompt(config, schema);
        resetGraphExtractionProgress(job, documents.size());
        // Chunks that fail this pass are returned for re-accumulation (per-chunk retry then deferral).
        List<Document> failed = new CopyOnWriteArrayList<>();

        // Provider-aware, output-token-safe budget — same basis as the GraphConstructor path. This is
        // the GraphConstructor-absent fallback, so each LLM call is sized from the model's real limits
        // at the output-safe starting budget (no intra-run AIMD ramp). Honors an explicit
        // crawlGraphExtractionTargetCharsPerBatch override; item cap + remote parallelism are the same
        // configurable knobs as the constructor path.
        ModelCapability modelCap = resolveExtractionModelCapability(job, config);
        boolean remoteBackend = !modelCap.local();
        boolean targetOverridden = graphExtractionTargetCharsPerBatch != DEFAULT_GRAPH_EXTRACTION_TARGET_CHARS;
        int charBudget = targetOverridden
                ? Math.max(1, graphExtractionTargetCharsPerBatch) : modelCap.initInputChars();
        // graphExtractionMaxItemsPerBatch is an upper-bound cap on the configured batch size.
        int maxItems = Math.min(resolveGraphExtractionBatchSize(config), Math.max(1, graphExtractionMaxItemsPerBatch));

        List<CostBatch<Document>> batches = planCostBatches(
                documents,
                this::estimateDocumentCost,
                maxItems,
                charBudget,
                costSortChunks);
        int resolvedParallelism = resolveGraphExtractionParallelism(job, batches.size());
        int remoteCap = Math.max(1, graphExtractionRemoteParallelism);
        if (remoteBackend && isLargeContextModel(modelCap)) {
            remoteCap = Math.max(remoteCap, Math.max(1, graphExtractionParallelism));
        }
        int parallelism = remoteBackend
                ? Math.min(resolvedParallelism, remoteCap)
                : resolvedParallelism;
        if (requiresAtomicOrderedDispatch(config)) {
            // Decomposed extraction intentionally reads graph state produced by earlier chunks.
            // Concurrent batches make that state completion-order-dependent and recreate the
            // context-loss problem decomposition is meant to solve.
            parallelism = 1;
        }
        pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, documents.size(), 0, 0, batches.size(), 0, null,
                "Planned inline LLM extraction batches");
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                "Planned inline LLM extraction batches",
                "chunks=" + documents.size() + ", batches=" + batches.size()
                        + ", backend=" + (remoteBackend ? "remote" : "local")
                        + ", parallelism=" + parallelism + ", charBudget=" + charBudget + ", maxItems=" + maxItems);

        // Shared cross-chunk caches to eliminate N+1 DB queries:
        // - parentDocCache: same sourcePath repeated across chunks from same document
        // - entityNodeCache: same entity IDs may appear across chunks
        ConcurrentHashMap<String, Optional<GraphNode>> parentDocCache = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Optional<GraphNode>> entityNodeCache = new ConcurrentHashMap<>();
        Long factSheetId = jobFactSheetId(job);

        if (parallelism <= 1 || batches.size() <= 1) {
            // Use graphExtractionChunksPerPrompt (the dedicated per-prompt grouping field) rather
            // than maxItems (batch-planning size) — they control different dimensions.
            final int serialChunksPerPrompt = requiresAtomicOrderedDispatch(config)
                    ? 1 : Math.max(1, graphExtractionChunksPerPrompt);
            if (serialChunksPerPrompt <= 1) {
                // Legacy serial path: one call per chunk.
                for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
                    if (isCancelled(job)) return failed;
                            if (extractGraphViaLlmDocument(documents.get(docIndex), docIndex, documents.size(),
                                    extractionPrompt, config, targetGraph, job,
                                    corpusSchemaOverride, parentDocCache, entityNodeCache, factSheetId)) {
                                failed.add(documents.get(docIndex));
                            }
                    memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                            "after inline graph chunk " + (docIndex + 1) + "/" + documents.size());
                }
            } else {
                // Serial multi-chunk path: group up to N chunks per LLM call.
                int docIndex = 0;
                while (docIndex < documents.size()) {
                    if (isCancelled(job)) break;
                    List<Document> group = new ArrayList<>(serialChunksPerPrompt);
                    int combinedChars = 0;
                    for (int gi = docIndex; gi < documents.size() && group.size() < serialChunksPerPrompt; gi++) {
                        Document d = documents.get(gi);
                        String t = d.getText();
                        int tLen = t != null ? t.length() : 0;
                        if (!group.isEmpty() && charBudget > 0
                                && combinedChars + tLen > charBudget) {
                            break; // char budget exceeded — flush group now
                        }
                        if (!group.isEmpty() && !sameSourceDocument(group.get(0), d)) {
                            break; // cross-document boundary — flush group now; never mix source documents
                        }
                        group.add(d);
                        combinedChars += tLen;
                    }
                    int groupEndExcl = docIndex + group.size();
                    List<Document> failedInGroup = extractGraphViaLlmChunkGroup(
                            group, docIndex, documents.size(),
                            config, targetGraph, job, corpusSchemaOverride, parentDocCache,
                            entityNodeCache, factSheetId);
                    failed.addAll(failedInGroup);
                    for (int gi = docIndex; gi < groupEndExcl; gi++) {
                        memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                "after inline graph group chunk " + (gi + 1) + "/" + documents.size());
                    }
                    docIndex = groupEndExcl;
                }
            }
            job.getCurrentBatchStep().set(null);
            return failed;
        }

        // Use shared graph extraction pool — avoids per-job thread creation/teardown overhead
        // Snapshot chunksPerPrompt so the lambda captures a stable value.
        // Use graphExtractionChunksPerPrompt (per-prompt grouping) not maxItems (batch-planning size).
        final int chunksPerPrompt = requiresAtomicOrderedDispatch(config)
                ? 1 : Math.max(1, graphExtractionChunksPerPrompt);
        try {
            List<Future<?>> futures = new ArrayList<>(batches.size());
            AtomicInteger offset = new AtomicInteger(0);
            for (CostBatch<Document> batch : batches) {
                int baseIndex = offset.getAndAdd(batch.items().size());
                futures.add(llmExec.submit(() -> {
                    memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                    job.getCurrentBatchSize().set(batch.items().size());
                    job.getCurrentBatchStep().set("LLM_BATCH " + batch.index() + "/" + batches.size());
                    if (chunksPerPrompt <= 1) {
                        // Legacy path: one LLM call per chunk — byte-for-byte identical behaviour.
                            for (int i = 0; i < batch.items().size(); i++) {
                            if (isCancelled(job)) break;
                            if (extractGraphViaLlmDocument(batch.items().get(i), baseIndex + i, documents.size(),
                                    extractionPrompt, config, targetGraph, job,
                                    corpusSchemaOverride, parentDocCache, entityNodeCache, factSheetId)) {
                                failed.add(batch.items().get(i));
                            }
                            memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                    "after inline graph chunk " + (baseIndex + i + 1) + "/" + documents.size());
                        }
                    } else {
                        // Multi-chunk path: group up to N chunks into one LLM call, respecting
                        // graphExtractionTargetCharsPerBatch as the combined-prompt char ceiling.
                        List<Document> batchItems = batch.items();
                        int groupStart = 0;
                        while (groupStart < batchItems.size()) {
                            if (isCancelled(job)) break;
                            // Build the next group: up to chunksPerPrompt items, combined text
                            // under graphExtractionTargetCharsPerBatch chars.
                            List<Document> group = new ArrayList<>(chunksPerPrompt);
                            int combinedChars = 0;
                            for (int gi = groupStart; gi < batchItems.size() && group.size() < chunksPerPrompt; gi++) {
                                Document d = batchItems.get(gi);
                                String t = d.getText();
                                int tLen = t != null ? t.length() : 0;
                                if (!group.isEmpty() && charBudget > 0
                                        && combinedChars + tLen > charBudget) {
                                    break; // this chunk would overflow the budget — flush group now
                                }
                                if (!group.isEmpty() && !sameSourceDocument(group.get(0), d)) {
                                    break; // cross-document boundary — flush group now; never mix source documents
                                }
                                group.add(d);
                                combinedChars += tLen;
                            }
                            int groupEndExcl = groupStart + group.size();
                            List<Document> failedInGroup = extractGraphViaLlmChunkGroup(
                                    group, baseIndex + groupStart, documents.size(),
                                    config, targetGraph, job, corpusSchemaOverride, parentDocCache,
                                    entityNodeCache, factSheetId);
                            failed.addAll(failedInGroup);
                            for (int gi = groupStart; gi < groupEndExcl; gi++) {
                                memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                        "after inline graph group chunk " + (baseIndex + gi + 1) + "/" + documents.size());
                            }
                            groupStart = groupEndExcl;
                        }
                    }
                    updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                            "Completed inline LLM batch " + batch.index() + "/" + batches.size(),
                            batch.items().size() + " chunk(s), cost=" + batch.cost());
                    pipelineStepTracker.incrementPipelineStep(job, "GRAPH_EXTRACTION", 0, 1,
                            "Completed inline LLM batch " + batch.index() + "/" + batches.size());
                }));
            }
            // Index-based loop so we can map timed-out futures back to their batch docs
            // and add them to `failed` (pre-fix: timeouts silently dropped all chunks in the batch).
            for (int fi = 0; fi < futures.size(); fi++) {
                if (isCancelled(job)) break;
                Future<?> future = futures.get(fi);
                CostBatch<Document> timedOutBatch = batches.get(fi);
                try {
                    future.get(graphExtractionBatchTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(false);
                    int batchTimeout = graphExtractionBatchTimeoutSeconds;
                    log.warn("[Job {}] Inline LLM extraction batch {}/{} timed out after {}s — adding {} chunk(s) to failed for rebatch/retry",
                            job.getJobId(), timedOutBatch.index(), batches.size(), batchTimeout, timedOutBatch.items().size());
                    job.getErrors().add("Inline LLM extraction batch " + timedOutBatch.index() + "/" + batches.size()
                            + " timed out after " + (batchTimeout / 60) + "min");
                    job.getErrorCount().incrementAndGet();
                    documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                            "Inline LLM extraction batch timed out",
                            "batch=" + timedOutBatch.index() + "/" + batches.size()
                                    + ", chunks=" + timedOutBatch.items().size() + " queued for rebatch/retry");
                    // Add all timed-out docs to failed so the re-accumulation loop can retry them
                    // per-chunk (splitting down to size-1 via the rebatch path).
                    failed.addAll(timedOutBatch.items());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("[Job {}] Inline LLM extraction batch {}/{} failed: {}",
                            job.getJobId(), timedOutBatch.index(), batches.size(), cause.getMessage());
                    job.getErrors().add("Inline LLM extraction batch failed: " + cause.getMessage());
                    job.getErrorCount().incrementAndGet();
                    // Add all docs from the failed batch to failed for rebatch/retry
                    failed.addAll(timedOutBatch.items());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Don't shutdown — shared pool is reused across jobs
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
        return failed;
    }

    private boolean extractGraphViaLlmDocument(Document doc,
                                            int docIndex,
                                            int totalDocuments,
                                            String extractionPrompt,
                                            GraphExtractionConfig config,
                                            Graph targetGraph,
                                            UnifiedCrawlJob job,
                                            GraphSchema corpusSchemaOverride,
                                            ConcurrentHashMap<String, Optional<GraphNode>> parentDocCache,
                                            ConcurrentHashMap<String, Optional<GraphNode>> entityNodeCache,
                                            Long factSheetId) {
        String jobId = job.getJobId();
        if (isCancelled(job)) return false;
        memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
        job.getCurrentBatchStep().set("GRAPH_CHUNK " + (docIndex + 1) + "/" + totalDocuments);
        updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                "Extracting graph from chunk " + (docIndex + 1) + "/" + totalDocuments, null);
        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                "Inline LLM graph extraction " + (docIndex + 1) + "/" + totalDocuments,
                null, EXTRACTORS_INLINE_LLM, false);

        boolean recordedResult = false;
        boolean failed = false;
        try {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "SKIPPED", 0, 0, 0,
                        "Skipped blank chunk", null, EXTRACTORS_INLINE_LLM, false);
                return false; // blank chunk — nothing to retry
            }

            // VLM-extracted documents may contain valuable structural markup;
            // allow more text through for better entity/relationship extraction.
            boolean isVlmContent = doc.getMetadata() != null
                    && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            int maxLength = isVlmContent ? maxCharsPerChunkVlm : maxCharsPerChunk;

            // Truncate very long documents to avoid LLM context limits
            if (text.length() > maxLength) {
                text = text.substring(0, maxLength);
            }

            // Add VLM context hint when applicable
            String vlmHint = "";
            if (isVlmContent) {
                String vlmModel = doc.getMetadata().get("vlm_model") instanceof String
                        ? (String) doc.getMetadata().get("vlm_model") : "unknown";
                vlmHint = "\n\nNote: This text was extracted from a PDF using a Visual Language Model (" +
                        vlmModel + "). It may contain document structure markup (headings, tables, " +
                        "form fields, references). Extract entities and relationships from both the " +
                        "prose content and the structured elements (table rows, form field key-value " +
                        "pairs, section headings).\n";
            }

            String fullPrompt = extractionPrompt + vlmHint + "\n\nText to analyze:\n" + text;

            // Validation retry loop: if the LLM returns parseable but invalid output,
            // retry with a rephrased prompt that includes the validation errors.
            int maxValRetries = job.getRequest().getMaxValidationRetries();
            String lastValidationErrors = null;
            boolean extractionSucceeded = false;

            for (int valAttempt = 0; valAttempt <= maxValRetries && !extractionSucceeded; valAttempt++) {
                String promptToSend = fullPrompt;
                if (valAttempt > 0 && lastValidationErrors != null) {
                    promptToSend = fullPrompt + "\n\n[RETRY: Previous response had validation errors: "
                            + lastValidationErrors + ". Please fix these issues in your response.]";
                    log.debug("[Job {}] Validation retry {}/{} for document {}",
                            jobId, valAttempt, maxValRetries, docIndex);
                }

                long llmCallStart = System.currentTimeMillis();
                // DECOMPOSED mode replaces the one-shot prompt with one bounded model/tool loop.
                // It returns the same schema-shaped JSON, so everything below is unchanged. The
                // validation-retry loop still applies if no graph delta validates in that loop.
                String response = DecomposedExtractionExecutor.isEnabled(config)
                        ? extractViaDecomposedPasses(text, doc, config, corpusSchemaOverride,
                                targetGraph, job, null)
                        : llmDispatcher.promptWithCapacityFallback(promptToSend, "llm", job);
                long llmCallLatencyMs = System.currentTimeMillis() - llmCallStart;
                // Belt-and-suspenders: record a transcript here when the dispatcher's own logger is
                // absent (e.g. subprocess context) so the call is never silently dropped.
                boolean responseOk = isUsableLlmResponse(response);
                recordInlineTranscriptIfNeeded(jobId, promptToSend, response,
                        llmCallLatencyMs,
                        responseOk,
                        responseOk ? null : badLlmResponseMessage("LLM", response));

            if (responseOk) {
                String json = extractJsonFromResponse(response);
                if (json != null) {
                    GraphExtractionSchema.ExtractionResult result;
                    try {
                        result = GraphExtractionValidator.fromJson(json);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException parseException) {
                        String parseMessage = parseException.getOriginalMessage() != null
                                ? parseException.getOriginalMessage()
                                : parseException.getClass().getSimpleName();
                        lastValidationErrors = "Malformed JSON: " + parseMessage;
                        log.debug("[Job {}] Graph extraction JSON parse failed (attempt {}/{}): {}",
                                jobId, valAttempt + 1, maxValRetries + 1, parseMessage);
                        if (valAttempt >= maxValRetries) {
                            documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction returned malformed JSON (after " + (valAttempt + 1) + " attempts)",
                                    lastValidationErrors, EXTRACTORS_INLINE_LLM, true);
                            recordedResult = true;
                            failed = true;
                        }
                        continue;
                    }
                var validation = GraphExtractionValidator.validate(
                            result, effectiveValidationPolicy(config), buildGraphSchema(config, corpusSchemaOverride),
                            DecomposedExtractionExecutor.isEnabled(config)
                                    ? knownEntityTypes(targetGraph, job, config) : Map.of());
                    traceValidation(result, validation);
                    if (validation.valid()) {
                        logValidationWarnings(jobId, "single-document", validation.warnings());
                        extractionSucceeded = true;
                        Graph chunkGraph = GraphExtractionValidator.toGraph(result, text);
                        if (retainResultGraph) {
                            synchronized (targetGraph) {
                                mergeGraphInto(chunkGraph, targetGraph, config);
                            }
                        }
                        job.getEntitiesExtracted().addAndGet(
                                chunkGraph.getEntities() != null ? chunkGraph.getEntities().size() : 0);
                        job.getRelationshipsExtracted().addAndGet(
                                chunkGraph.getRelationships() != null ? chunkGraph.getRelationships().size() : 0);
                        int entityCount = chunkGraph.getEntities() != null ? chunkGraph.getEntities().size() : 0;
                        int relationshipCount = chunkGraph.getRelationships() != null ? chunkGraph.getRelationships().size() : 0;
                        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "COMPLETED", 0,
                                entityCount, relationshipCount,
                                "Inline LLM graph extraction complete", null, EXTRACTORS_INLINE_LLM, true);
                        recordedResult = true;

                        // Persist LLM-extracted entities and relations to KnowledgeGraphService
                        if (knowledgeGraphService != null && result.entities() != null && !result.entities().isEmpty()) {
                            Map<String, Object> docMeta = doc.getMetadata();
                            String sourcePath = docMeta != null && docMeta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                                    ? (String) docMeta.get(GraphConstants.META_SOURCE_PATH)
                                    : docMeta != null && docMeta.get(GraphConstants.META_SOURCE) instanceof String
                                    ? (String) docMeta.get(GraphConstants.META_SOURCE) : null;

                            GraphNode parentDocumentNode = null;
                            String parentNodeId = null;
                            if (sourcePath != null) {
                                final Long fsId = factSheetId;
                                Optional<GraphNode> docNode = parentDocCache.computeIfAbsent(
                                        sourcePath, sp -> knowledgeGraphService.getNodeByExternalId(
                                                sp, NodeLevel.DOCUMENT, fsId));
                                if (docNode.isPresent()) {
                                    parentDocumentNode = docNode.get();
                                    parentNodeId = parentDocumentNode.getNodeId();
                                    if (factSheetId == null) {
                                        factSheetId = parentDocumentNode.getFactSheetId();
                                    }
                                }
                            }

                            Map<String, String> externalToNodeId = new HashMap<>();
                            String inlineContainsLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_CONTAINS);
                            // doc→entity CONTAINS edges are batched into one createEdgesBatch after all nodes exist.
                            List<KnowledgeGraphService.EdgeSpec> containsEdgeSpecs = new ArrayList<>();
                            for (var entity : result.entities()) {
                                try {
                                    String entityType = graphPersistenceHelper.safeEntityType(entity.type());
                                    Map<String, Object> entityMeta = new LinkedHashMap<>();
                                    entityMeta.put("entity_type", entityType);
                                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                                    entityMeta.put("extraction_method", "llm");
                                    if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                                    if (entity.properties() != null) entityMeta.putAll(entity.properties());
                                    // Re-assert after properties merge so LLM can't overwrite with a junk value.
                                    entityMeta.put("entity_type", entityType);
                                    // Provenance under the reserved keys (read by /nodes/{id}/provenance, travels via export).
                                    entityMeta.putAll(GraphProvenanceKeys.crawl(jobId, sourcePath, doc.getId(), config.getModelName()));

                                    final Long entityFsId = factSheetId;
                                    Optional<GraphNode> existing = entityNodeCache.computeIfAbsent(
                                            entity.id(), eid -> knowledgeGraphService.getNodeByExternalId(
                                                    eid, NodeLevel.ENTITY, entityFsId));
                                    GraphNode node;
                                    if (existing.isPresent()) {
                                        node = existing.get();
                                    } else {
                                        node = knowledgeGraphService.createNode(NodeLevel.ENTITY, entity.id(),
                                                entity.name(), entity.description(), entityMeta, factSheetId);
                                        // Cache the newly created node
                                        entityNodeCache.put(entity.id(), Optional.of(node));
                                    }
                                    externalToNodeId.put(entity.id(), node.getNodeId());
                                    job.incrementEntityType(entityType);

                                    if (parentNodeId != null) {
                                        graphPersistenceHelper.recordEntityMention(parentDocumentNode,
                                                entity.name(),
                                                entityType,
                                                entity.confidence(),
                                                factSheetId,
                                                "inline_llm",
                                                sourcePath,
                                                null);
                                        String description = graphPersistenceHelper.semanticRelationDescription(
                                                "Document contains " + entityType + " " + entity.name(), inlineContainsLabel);
                                        // A2: stamp Opinion + provenance onto the doc→entity CONTAINS edge from inline
                                        // single-chunk LLM extraction. The hardcoded 1.0 was the bug: it caused
                                        // GraphToFactStoreProjector to treat every CONTAINS atom as hard-observed
                                        // (value >= 0.99 → Fact.observed), leaving the PSL MAP gradient at 0 so
                                        // derivation wrote 0 new inferred facts. Route through the stamper
                                        // (LLM_EXTRACTION basis, W=2.0) so the edge carries a Beta-MAP confidence.
                                        Map<String, Object> containsMeta = graphPersistenceHelper.metadataProperties(
                                                "entityType", entity.type(),
                                                "entityName", entity.name());
                                        double containsWeight = confidenceStamper != null
                                                ? confidenceStamper.stampEdgeConfidence(
                                                        containsMeta, "LLM_EXTRACTION", entity.confidence())
                                                : (entity.confidence() != null ? entity.confidence() : 0.5);
                                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                                "inline_llm", sourcePath, entity.id(), inlineContainsLabel, description,
                                                containsWeight, containsMeta);
                                        containsEdgeSpecs.add(new KnowledgeGraphService.EdgeSpec(parentNodeId, node.getNodeId(),
                                                EdgeType.CONTAINS, containsWeight, description, inlineContainsLabel, metaJson,
                                                EdgeProvenance.EXTRACTED, factSheetId));
                                    }
                                } catch (Exception e) {
                                    log.debug("[Job {}] Failed to persist LLM entity '{}': {}", jobId, entity.name(), e.getMessage());
                                }
                            }
                            if (!containsEdgeSpecs.isEmpty()) {
                                knowledgeGraphService.createEdgesBatch(containsEdgeSpecs);
                            }

                            if (result.relations() != null) {
                                // Accumulate the extracted relation edges and persist them in ONE createEdgesBatch
                                // call (a single subprocess /invoke) instead of one RPC per edge — the per-edge
                                // path otherwise dominated extraction (~one RPC per relation × thousands).
                                List<KnowledgeGraphService.EdgeSpec> relEdgeSpecs = new ArrayList<>();
                                for (var rel : result.relations()) {
                                    try {
                                        String srcNodeId = externalToNodeId.get(rel.source());
                                        String tgtNodeId = externalToNodeId.get(rel.target());
                                        if (srcNodeId == null || tgtNodeId == null) continue;
                                        String label = graphPersistenceHelper.semanticRelationLabel(rel.type());
                                        String description = graphPersistenceHelper.semanticRelationDescription(rel.description(), label);
                                        // A1: stamp Opinion + provenance onto inline-LLM extracted edge.
                                        // Never default a missing LLM confidence to 1.0 (pins PSL gradient).
                                        // Route through the stamper (LLM_EXTRACTION basis, W=2.0) so that
                                        // a missing confidence is seeded from sourceTrust, not hardcoded.
                                        Map<String, Object> relPropertiesMeta = new LinkedHashMap<>();
                                        if (rel.properties() != null) relPropertiesMeta.putAll(rel.properties());
                                        double relWeight;
                                        if (confidenceStamper != null) {
                                            relWeight = confidenceStamper.stampEdgeConfidence(
                                                    relPropertiesMeta, "LLM_EXTRACTION", rel.confidence());
                                        } else {
                                            // Stamper absent: use raw confidence or 0.5 (never 1.0)
                                            relWeight = rel.confidence() != null ? rel.confidence() : 0.5;
                                        }
                                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                                "inline_llm", rel.source(), rel.target(), label, description,
                                                relWeight, relPropertiesMeta);
                                        relEdgeSpecs.add(new KnowledgeGraphService.EdgeSpec(srcNodeId, tgtNodeId,
                                                EdgeType.USER_DEFINED, relWeight, description, label, metaJson,
                                                EdgeProvenance.EXTRACTED, factSheetId));
                                        job.incrementRelationshipType(label);
                                    } catch (Exception e) {
                                        log.debug("[Job {}] Failed to persist LLM relation '{}': {}", jobId, rel.type(), e.getMessage());
                                    }
                                }
                                if (!relEdgeSpecs.isEmpty()) {
                                    knowledgeGraphService.createEdgesBatch(relEdgeSpecs);
                                }
                            }
                        }
                    } else {
                        lastValidationErrors = validationFeedback(validation.errors(), effectiveValidationPolicy(config));
                        if (valAttempt >= maxValRetries) {
                            // Final attempt — mark as failed
                            log.debug("Graph extraction validation failed after {} retries: {}",
                                    valAttempt, validation.errors());
                            documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction validation failed (after " + (valAttempt + 1) + " attempts)",
                                    lastValidationErrors,
                                    EXTRACTORS_INLINE_LLM, true);
                            recordedResult = true;
                            failed = true;
                        } else {
                            log.debug("Validation failed (attempt {}/{}), retrying: {}",
                                    valAttempt + 1, maxValRetries + 1, validation.errors());
                        }
                    }
                }
            }
            } // end validation retry loop

            if (!recordedResult) {
                // LLM returned null or empty — this is a failure, not a quiet success
                job.getErrorCount().incrementAndGet();
                failed = true;
                documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                        "LLM graph extraction returned no result",
                        "LLM returned null/empty — check LLM configuration",
                        EXTRACTORS_INLINE_LLM, true);
            }

        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.warn("[Job {}] Graph extraction failed for document: {}", job.getJobId(), errorDetail, e);
            job.getErrors().add("Graph extraction failed: " + errorDetail);
            job.getErrorCount().incrementAndGet();
            failed = true;
            documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                    "Graph extraction failed", errorDetail, EXTRACTORS_INLINE_LLM, true);
        } finally {
            incrementGraphChunksProcessed(job, 1);
            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                    "Completed graph chunk " + (docIndex + 1) + "/" + totalDocuments, null);
        }
        return failed;
    }

    // -------------------------------------------------------------------------
    // Multi-chunk group LLM extraction (chunksPerPrompt > 1)
    // -------------------------------------------------------------------------

    /**
     * Sends a group of up to N document chunks as a single LLM prompt, parses the unified
     * response, and attributes each entity/relationship back to its source chunk via the
     * {@code "chunkId"} field the model is instructed to emit.
     *
     * <p>When {@code graphExtractionChunksPerPrompt == 1} this method is never called;
     * the existing {@link #extractGraphViaLlmDocument} path is used instead (identical behaviour).</p>
     *
     * <p>Provenance fallback: if the model omits {@code chunkId} for an item, or emits an
     * unrecognised value, the item is attributed to the first chunk in the group rather than
     * dropped — source-document provenance is never lost.</p>
     *
     * @return list of documents whose extraction failed (same contract as
     *         {@link #extractGraphViaLlmDocument})
     */
    private List<Document> extractGraphViaLlmChunkGroup(
            List<Document> group,
            int baseDocIndex,
            int totalDocuments,
            GraphExtractionConfig config,
            Graph targetGraph,
            UnifiedCrawlJob job,
            GraphSchema corpusSchemaOverride,
            ConcurrentHashMap<String, Optional<GraphNode>> parentDocCache,
            ConcurrentHashMap<String, Optional<GraphNode>> entityNodeCache,
            Long factSheetId) {
        // Public entry: start recursive rebatch at depth 0.
        return extractGraphViaLlmChunkGroup(group, baseDocIndex, totalDocuments, config,
                targetGraph, job, corpusSchemaOverride, parentDocCache,
                entityNodeCache, factSheetId, 0);
    }

    /**
     * Internal recursive implementation of multi-chunk LLM extraction with rebatch-on-failure.
     *
     * <p>When a group of N chunks fails (timeout / empty / parse-error), the group is halved and
     * each half is retried independently. Halving recurses up to {@link #maxRebatchDepth} levels
     * before a chunk is declared individually failed (size-1 is the terminal base case that
     * delegates to {@link #extractGraphViaLlmDocument}). A group of 32 failing at depth 0 →
     * 16→8→4→2→1, so even a single salvageable chunk in a bad batch will be rescued.</p>
     */
    private List<Document> extractGraphViaLlmChunkGroup(
            List<Document> group,
            int baseDocIndex,
            int totalDocuments,
            GraphExtractionConfig config,
            Graph targetGraph,
            UnifiedCrawlJob job,
            GraphSchema corpusSchemaOverride,
            ConcurrentHashMap<String, Optional<GraphNode>> parentDocCache,
            ConcurrentHashMap<String, Optional<GraphNode>> entityNodeCache,
            Long factSheetId,
            int rebatchDepth) {

        List<Document> failed = new ArrayList<>();
        if (group == null || group.isEmpty()) {
            return failed;
        }
        if (requiresAtomicOrderedDispatch(config)) {
            String extractionPrompt = buildExtractionPrompt(config, buildGraphSchema(config, corpusSchemaOverride));
            for (int index = 0; index < group.size(); index++) {
                Document document = group.get(index);
                boolean docFailed = extractGraphViaLlmDocument(document, baseDocIndex + index,
                        totalDocuments, extractionPrompt, config, targetGraph, job,
                        corpusSchemaOverride, parentDocCache, entityNodeCache, factSheetId);
                if (docFailed) {
                    failed.add(document);
                }
            }
            return failed;
        }
        // Single-chunk group: delegate to the original per-document path for byte-identical behaviour.
        if (group.size() == 1) {
            String extractionPrompt = buildExtractionPrompt(config,
                    buildGraphSchema(config, corpusSchemaOverride));
            boolean docFailed = extractGraphViaLlmDocument(
                    group.get(0), baseDocIndex, totalDocuments, extractionPrompt,
                    config, targetGraph, job, corpusSchemaOverride,
                    parentDocCache, entityNodeCache, factSheetId);
            if (docFailed) failed.add(group.get(0));
            return failed;
        }

        String jobId = job.getJobId();
        if (isCancelled(job)) {
            failed.addAll(group);
            return failed;
        }
        memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");

        // Build a stable chunkId -> Document map. Use the document's own id when available,
        // otherwise synthesise a positional identifier.
        List<String> chunkIds = new ArrayList<>(group.size());
        Map<String, Document> chunkIdToDoc = new LinkedHashMap<>();
        for (int k = 0; k < group.size(); k++) {
            Document d = group.get(k);
            String cid = (d.getId() != null && !d.getId().isBlank()) ? d.getId()
                    : ("chunk-" + (baseDocIndex + k));
            chunkIds.add(cid);
            chunkIdToDoc.putIfAbsent(cid, d);
        }
        String fallbackChunkId = chunkIds.get(0);

        // Assemble the multi-chunk prompt.
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(GraphExtractionValidator.getMultiChunkExtractionPromptInstructions(
                effectiveValidationPolicy(config), buildGraphSchema(config, corpusSchemaOverride)));
        if (config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
            promptBuilder.append("\n\nFocus on extracting these entity types: ");
            promptBuilder.append(String.join(", ", config.getEntityTypes()));
        }
        if (config.getRelationshipTypes() != null && !config.getRelationshipTypes().isEmpty()) {
            promptBuilder.append("\nFocus on extracting these relationship types: ");
            promptBuilder.append(String.join(", ", config.getRelationshipTypes()));
        }
        if (config.getCustomPrompt() != null && !config.getCustomPrompt().isBlank()) {
            promptBuilder.append("\n\nAdditional instructions: ").append(config.getCustomPrompt());
        }
        promptBuilder.append("\n\n");

        for (int k = 0; k < group.size(); k++) {
            Document d = group.get(k);
            String cid = chunkIds.get(k);
            String text = d.getText() != null ? d.getText() : "";
            boolean isVlm = d.getMetadata() != null
                    && Boolean.TRUE.equals(d.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            int limit = isVlm ? maxCharsPerChunkVlm : maxCharsPerChunk;
            if (text.length() > limit) {
                text = text.substring(0, limit);
            }
            promptBuilder.append("===== CHUNK ").append(k + 1).append(" | source=").append(cid).append(" =====\n");
            promptBuilder.append(text).append("\n\n");
        }

        // Update progress markers.
        job.getCurrentBatchStep().set("GRAPH_GROUP " + (baseDocIndex + 1) + "-"
                + (baseDocIndex + group.size()) + "/" + totalDocuments);
        for (int k = 0; k < group.size(); k++) {
            documentTracker.recordDocumentProgress(job, group.get(k), "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                    "Inline LLM multi-chunk extraction group " + (baseDocIndex + k + 1) + "/" + totalDocuments,
                    null, EXTRACTORS_INLINE_LLM, false);
        }
        updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                "Extracting graph from chunk group " + (baseDocIndex + 1) + "-"
                        + (baseDocIndex + group.size()) + "/" + totalDocuments, null);

        boolean groupSucceeded = false;
        try {
            int maxValRetries = job.getRequest().getMaxValidationRetries();
            String lastValidationErrors = null;
            String fullPrompt = promptBuilder.toString();

            for (int valAttempt = 0; valAttempt <= maxValRetries && !groupSucceeded; valAttempt++) {
                String promptToSend = fullPrompt;
                if (valAttempt > 0 && lastValidationErrors != null) {
                    promptToSend = fullPrompt + "\n\n[RETRY: Previous response had validation errors: "
                            + lastValidationErrors + ". Please fix these issues in your response.]";
                }

                long llmCallStart = System.currentTimeMillis();
                String response = llmDispatcher.promptWithCapacityFallback(promptToSend, "llm", job);
                long llmCallLatencyMs = System.currentTimeMillis() - llmCallStart;
                // Belt-and-suspenders: record a transcript here when the dispatcher's own logger is
                // absent (e.g. subprocess context) so the call is never silently dropped.
                boolean responseOk = isUsableLlmResponse(response);
                recordInlineTranscriptIfNeeded(jobId, promptToSend, response,
                        llmCallLatencyMs,
                        responseOk,
                        responseOk ? null : badLlmResponseMessage("LLM", response));
                if (!responseOk) {
                    continue;
                }

                String json = extractJsonFromResponse(response);
                if (json == null) continue;

                GraphExtractionSchema.ExtractionResult result;
                try {
                    result = GraphExtractionValidator.fromJson(json);
                } catch (com.fasterxml.jackson.core.JsonProcessingException parseException) {
                    String parseMessage = parseException.getOriginalMessage() != null
                            ? parseException.getOriginalMessage()
                            : parseException.getClass().getSimpleName();
                    lastValidationErrors = "Malformed JSON: " + parseMessage;
                    log.debug("[Job {}] Multi-chunk graph extraction JSON parse failed (attempt {}/{}): {}",
                            jobId, valAttempt + 1, maxValRetries + 1, parseMessage);
                    continue;
                }
                var validation = GraphExtractionValidator.validate(
                        result, effectiveValidationPolicy(config),
                        buildGraphSchema(config, corpusSchemaOverride));
                traceValidation(result, validation);
                if (!validation.valid()) {
                    lastValidationErrors = validationFeedback(validation.errors(), effectiveValidationPolicy(config));
                    if (valAttempt >= maxValRetries) {
                        log.debug("Multi-chunk graph extraction validation failed after {} retries: {}",
                                valAttempt, validation.errors());
                        // Mark all docs in the group failed
                        for (int k = 0; k < group.size(); k++) {
                            documentTracker.recordDocumentProgress(job, group.get(k), "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction validation failed (multi-chunk group, after " + (valAttempt + 1) + " attempts)",
                                    lastValidationErrors, EXTRACTORS_INLINE_LLM, true);
                            failed.add(group.get(k));
                        }
                        incrementGraphChunksProcessed(job, group.size());
                    }
                    continue;
                }

                logValidationWarnings(jobId, "multi-chunk", validation.warnings());
                groupSucceeded = true;

                // Partition entities and relations by their chunkId attribution.
                // For each chunkId seen in the response, collect its items and persist them
                // as if they came from a single-chunk call for that document.
                Map<String, List<GraphExtractionSchema.ExtractedEntity>> entitiesByChunk = new LinkedHashMap<>();
                Map<String, List<GraphExtractionSchema.ExtractedRelation>> relationsByChunk = new LinkedHashMap<>();

                // Initialise all known chunk slots so every doc gets at least an empty entry.
                for (String cid : chunkIds) {
                    entitiesByChunk.put(cid, new ArrayList<>());
                    relationsByChunk.put(cid, new ArrayList<>());
                }

                for (GraphExtractionSchema.ExtractedEntity e : result.entities()) {
                    String cid = resolveChunkId(e.properties(), chunkIds, fallbackChunkId);
                    entitiesByChunk.computeIfAbsent(cid, k2 -> new ArrayList<>()).add(e);
                }
                for (GraphExtractionSchema.ExtractedRelation r : result.relations()) {
                    String cid = resolveChunkId(r.properties(), chunkIds, fallbackChunkId);
                    relationsByChunk.computeIfAbsent(cid, k2 -> new ArrayList<>()).add(r);
                }

                // Persist results per source document.
                for (int k = 0; k < group.size(); k++) {
                    Document doc = group.get(k);
                    String cid = chunkIds.get(k);
                    List<GraphExtractionSchema.ExtractedEntity> docEntities =
                            entitiesByChunk.getOrDefault(cid, List.of());
                    List<GraphExtractionSchema.ExtractedRelation> docRelations =
                            relationsByChunk.getOrDefault(cid, List.of());

                    GraphExtractionSchema.ExtractionResult perDoc = GraphExtractionSchema.ExtractionResult.of(
                            docEntities, docRelations,
                            GraphExtractionSchema.ExtractionMetadata.forChunk(cid, cid, "inline_llm_multi"));

                    Graph chunkGraph = GraphExtractionValidator.toGraph(perDoc, doc.getText());
                    if (retainResultGraph) {
                        synchronized (targetGraph) {
                            mergeGraphInto(chunkGraph, targetGraph, config);
                        }
                    }
                    int entityCount = docEntities.size();
                    int relCount = docRelations.size();
                    job.getEntitiesExtracted().addAndGet(entityCount);
                    job.getRelationshipsExtracted().addAndGet(relCount);
                    documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "COMPLETED", 0,
                            entityCount, relCount,
                            "Inline LLM multi-chunk extraction complete", null, EXTRACTORS_INLINE_LLM, true);

                    // Persist to KnowledgeGraphService (same logic as the single-chunk path).
                    if (knowledgeGraphService != null && !docEntities.isEmpty()) {
                        Map<String, Object> docMeta = doc.getMetadata();
                        String sourcePath = docMeta != null && docMeta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                                ? (String) docMeta.get(GraphConstants.META_SOURCE_PATH)
                                : docMeta != null && docMeta.get(GraphConstants.META_SOURCE) instanceof String
                                ? (String) docMeta.get(GraphConstants.META_SOURCE) : null;

                        GraphNode parentDocumentNode = null;
                        String parentNodeId = null;
                        if (sourcePath != null) {
                            final Long fsId = factSheetId;
                            Optional<GraphNode> docNode = parentDocCache.computeIfAbsent(
                                    sourcePath, sp -> knowledgeGraphService.getNodeByExternalId(
                                            sp, NodeLevel.DOCUMENT, fsId));
                            if (docNode.isPresent()) {
                                parentDocumentNode = docNode.get();
                                parentNodeId = parentDocumentNode.getNodeId();
                            }
                        }

                        Map<String, String> externalToNodeId = new HashMap<>();
                        String inlineContainsLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_CONTAINS);
                        // doc→entity CONTAINS edges are batched into one createEdgesBatch after all nodes exist.
                        List<KnowledgeGraphService.EdgeSpec> multiContainsEdgeSpecs = new ArrayList<>();
                        for (GraphExtractionSchema.ExtractedEntity entity : docEntities) {
                            try {
                                String entityType = graphPersistenceHelper.safeEntityType(entity.type());
                                Map<String, Object> entityMeta = new LinkedHashMap<>();
                                entityMeta.put("entity_type", entityType);
                                entityMeta.put(GraphConstants.META_SOURCE, jobId);
                                entityMeta.put("extraction_method", "llm_multi");
                                if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                                if (entity.properties() != null) entityMeta.putAll(entity.properties());
                                // Re-assert after properties merge so LLM can't overwrite with a junk value.
                                entityMeta.put("entity_type", entityType);
                                // Provenance under the reserved keys (read by /nodes/{id}/provenance, travels via export).
                                entityMeta.putAll(GraphProvenanceKeys.crawl(jobId, sourcePath, doc.getId(), config.getModelName()));

                                final Long entityFsId = factSheetId;
                                Optional<GraphNode> existing = entityNodeCache.computeIfAbsent(
                                        entity.id(), eid -> knowledgeGraphService.getNodeByExternalId(
                                                eid, NodeLevel.ENTITY, entityFsId));
                                GraphNode node;
                                if (existing.isPresent()) {
                                    node = existing.get();
                                } else {
                                    node = knowledgeGraphService.createNode(NodeLevel.ENTITY, entity.id(),
                                            entity.name(), entity.description(), entityMeta, factSheetId);
                                    entityNodeCache.put(entity.id(), Optional.of(node));
                                }
                                externalToNodeId.put(entity.id(), node.getNodeId());
                                job.incrementEntityType(entityType);

                                if (parentNodeId != null) {
                                    graphPersistenceHelper.recordEntityMention(parentDocumentNode,
                                            entity.name(), entityType, entity.confidence(),
                                            factSheetId, "inline_llm_multi", sourcePath, null);
                                    String description = graphPersistenceHelper.semanticRelationDescription(
                                            "Document contains " + entityType + " " + entity.name(), inlineContainsLabel);
                                    // A2 (multi-chunk): same fix as single-chunk — stamp Opinion onto the
                                    // doc→entity CONTAINS edge so the PSL MAP gradient is non-zero.
                                    Map<String, Object> multiContainsMeta = graphPersistenceHelper.metadataProperties(
                                            "entityType", entity.type(), "entityName", entity.name());
                                    double multiContainsWeight = confidenceStamper != null
                                            ? confidenceStamper.stampEdgeConfidence(
                                                    multiContainsMeta, "LLM_EXTRACTION", entity.confidence())
                                            : (entity.confidence() != null ? entity.confidence() : 0.5);
                                    String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                            "inline_llm_multi", sourcePath, entity.id(), inlineContainsLabel, description,
                                            multiContainsWeight, multiContainsMeta);
                                    multiContainsEdgeSpecs.add(new KnowledgeGraphService.EdgeSpec(parentNodeId, node.getNodeId(),
                                            EdgeType.CONTAINS, multiContainsWeight, description, inlineContainsLabel, metaJson,
                                            EdgeProvenance.EXTRACTED, factSheetId));
                                }
                            } catch (Exception ex) {
                                log.debug("[Job {}] Failed to persist multi-chunk entity '{}': {}", jobId, entity.name(), ex.getMessage());
                            }
                        }
                        if (!multiContainsEdgeSpecs.isEmpty()) {
                            knowledgeGraphService.createEdgesBatch(multiContainsEdgeSpecs);
                        }

                        // Accumulate + single createEdgesBatch (one RPC) instead of one RPC per relation.
                        List<KnowledgeGraphService.EdgeSpec> multiRelEdgeSpecs = new ArrayList<>();
                        for (GraphExtractionSchema.ExtractedRelation rel : docRelations) {
                            try {
                                String srcNodeId = externalToNodeId.get(rel.source());
                                String tgtNodeId = externalToNodeId.get(rel.target());
                                if (srcNodeId == null || tgtNodeId == null) continue;
                                String label = graphPersistenceHelper.semanticRelationLabel(rel.type());
                                String description = graphPersistenceHelper.semanticRelationDescription(rel.description(), label);
                                // A1: stamp Opinion onto multi-chunk LLM extracted edge (same as single-chunk).
                                // Never default a missing LLM confidence to 1.0 (pins PSL gradient).
                                Map<String, Object> multiRelMeta = new LinkedHashMap<>();
                                if (rel.properties() != null) multiRelMeta.putAll(rel.properties());
                                double multiRelWeight;
                                if (confidenceStamper != null) {
                                    multiRelWeight = confidenceStamper.stampEdgeConfidence(
                                            multiRelMeta, "LLM_EXTRACTION", rel.confidence());
                                } else {
                                    multiRelWeight = rel.confidence() != null ? rel.confidence() : 0.5;
                                }
                                String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                        "inline_llm_multi", rel.source(), rel.target(), label, description,
                                        multiRelWeight, multiRelMeta);
                                multiRelEdgeSpecs.add(new KnowledgeGraphService.EdgeSpec(srcNodeId, tgtNodeId,
                                        EdgeType.USER_DEFINED, multiRelWeight, description, label,
                                        metaJson, EdgeProvenance.EXTRACTED, factSheetId));
                                job.incrementRelationshipType(label);
                            } catch (Exception ex) {
                                log.debug("[Job {}] Failed to persist multi-chunk relation '{}': {}", jobId, rel.type(), ex.getMessage());
                            }
                        }
                        if (!multiRelEdgeSpecs.isEmpty()) {
                            knowledgeGraphService.createEdgesBatch(multiRelEdgeSpecs);
                        }
                    }
                }
            } // end validation retry loop

            if (!groupSucceeded) {
                // The whole group produced nothing. Try rebatching — split into halves and retry each.
                // This rescues chunks that succeed in smaller context (oversized batches → truncation
                // → parse failure → zero output). Recurse until size==1 (falls to per-doc path above)
                // or depth exceeds maxRebatchDepth. A group of 32 failing at depth 0 follows:
                // 32 → 16 → 8 → 4 → 2 → 1 (per-doc). If group.size()==1 we already delegated above.
                if (group.size() > 1 && rebatchDepth < maxRebatchDepth && !isCancelled(job)) {
                    int half = group.size() / 2;
                    List<Document> firstHalf = group.subList(0, half);
                    List<Document> secondHalf = group.subList(half, group.size());
                    log.info("[Job {}] Rebatching group of {} chunks (depth {}/{}) → halves {}/{}",
                            job.getJobId(), group.size(), rebatchDepth, maxRebatchDepth,
                            firstHalf.size(), secondHalf.size());
                    documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                            "Rebatching failed multi-chunk group",
                            "size=" + group.size() + " → " + firstHalf.size() + "+" + secondHalf.size()
                                    + ", depth=" + rebatchDepth + "/" + maxRebatchDepth);
                    // Recurse each half; failures bubble up from the sub-calls.
                    List<Document> firstFailed = extractGraphViaLlmChunkGroup(
                            new ArrayList<>(firstHalf), baseDocIndex, totalDocuments,
                            config, targetGraph, job, corpusSchemaOverride, parentDocCache,
                            entityNodeCache, factSheetId, rebatchDepth + 1);
                    List<Document> secondFailed = extractGraphViaLlmChunkGroup(
                            new ArrayList<>(secondHalf), baseDocIndex + half, totalDocuments,
                            config, targetGraph, job, corpusSchemaOverride, parentDocCache,
                            entityNodeCache, factSheetId, rebatchDepth + 1);
                    for (Document d : firstFailed)  { if (!failed.contains(d)) failed.add(d); }
                    for (Document d : secondFailed) { if (!failed.contains(d)) failed.add(d); }
                } else {
                    // Terminal: depth limit reached or size==1 unexpectedly (safety guard).
                    job.getErrorCount().incrementAndGet();
                    for (Document doc : group) {
                        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                "LLM multi-chunk extraction returned no result (rebatch depth " + rebatchDepth + "/" + maxRebatchDepth + ")",
                                "LLM returned null/empty — check LLM configuration",
                                EXTRACTORS_INLINE_LLM, true);
                        if (!failed.contains(doc)) failed.add(doc);
                    }
                }
            }
        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.warn("[Job {}] Multi-chunk graph extraction failed for group of {} (depth {}): {}",
                    job.getJobId(), group.size(), rebatchDepth, errorDetail, e);
            // On exception, also attempt to rebatch rather than immediately failing all docs.
            if (group.size() > 1 && rebatchDepth < maxRebatchDepth && !isCancelled(job)) {
                int half = group.size() / 2;
                log.info("[Job {}] Rebatching after exception, group {} → halves {}/{} (depth {}/{})",
                        job.getJobId(), group.size(), half, group.size() - half, rebatchDepth, maxRebatchDepth);
                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                        "Rebatching after exception in multi-chunk group",
                        "size=" + group.size() + " → halves, depth=" + rebatchDepth + "/" + maxRebatchDepth
                                + ", error=" + errorDetail);
                List<Document> firstFailed = extractGraphViaLlmChunkGroup(
                        new ArrayList<>(group.subList(0, half)), baseDocIndex, totalDocuments,
                        config, targetGraph, job, corpusSchemaOverride, parentDocCache,
                        entityNodeCache, factSheetId, rebatchDepth + 1);
                List<Document> secondFailed = extractGraphViaLlmChunkGroup(
                        new ArrayList<>(group.subList(half, group.size())), baseDocIndex + half, totalDocuments,
                        config, targetGraph, job, corpusSchemaOverride, parentDocCache,
                        entityNodeCache, factSheetId, rebatchDepth + 1);
                for (Document d : firstFailed)  { if (!failed.contains(d)) failed.add(d); }
                for (Document d : secondFailed) { if (!failed.contains(d)) failed.add(d); }
            } else {
                job.getErrors().add("Multi-chunk graph extraction failed: " + errorDetail);
                job.getErrorCount().incrementAndGet();
                for (Document doc : group) {
                    if (!failed.contains(doc)) {
                        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                "Multi-chunk graph extraction failed", errorDetail, EXTRACTORS_INLINE_LLM, true);
                        failed.add(doc);
                    }
                }
            }
        } finally {
            // Progress increment: only at depth 0 (the top-level call for this group) to avoid
            // double-counting from sub-calls. At depth > 0 the recursion is part of the same group.
            if (rebatchDepth == 0) {
                incrementGraphChunksProcessed(job, group.size());
                updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                        "Completed graph group " + (baseDocIndex + 1) + "-"
                                + (baseDocIndex + group.size()) + "/" + totalDocuments, null);
            }
        }
        return failed;
    }

    /**
     * Resolves the chunkId for an extracted item from its properties map.
     *
     * <p>The model is instructed to emit {@code "chunkId"} in the {@code properties} field of
     * every entity and relation. If the value is absent or not in the known-chunk set, the
     * fallback chunkId (first chunk in the group) is returned so provenance is never dropped.</p>
     */
    private String resolveChunkId(Map<String, String> properties, List<String> knownChunkIds, String fallback) {
        if (properties == null) return fallback;
        String cid = properties.get("chunkId");
        if (cid == null || cid.isBlank()) return fallback;
        // Accept exact match or prefix match (model may emit a truncated id).
        if (knownChunkIds.contains(cid)) return cid;
        for (String known : knownChunkIds) {
            if (known.startsWith(cid) || cid.startsWith(known)) return known;
        }
        return fallback;
    }

    /**
     * Returns the source-document path from a Spring AI Document's metadata.
     * Checks META_SOURCE_PATH first, then META_SOURCE. Returns null when absent or blank.
     */
    private static String docSourcePath(Document d) {
        if (d == null || d.getMetadata() == null) return null;
        Object sp = d.getMetadata().get(GraphConstants.META_SOURCE_PATH);
        if (sp instanceof String s && !s.isBlank()) return s;
        sp = d.getMetadata().get(GraphConstants.META_SOURCE);
        if (sp instanceof String s && !s.isBlank()) return s;
        return null;
    }

    /**
     * Returns true only when both documents have the same known (non-null, non-blank) source path.
     * When either path is absent the provenance is unknown — the safe choice is NOT to group.
     */
    private static boolean sameSourceDocument(Document a, Document b) {
        if (a == null || b == null) return false;
        String spA = docSourcePath(a);
        String spB = docSourcePath(b);
        return spA != null && spA.equals(spB);
    }

    // -------------------------------------------------------------------------
    // Graph chunk progress tracking
    // -------------------------------------------------------------------------

    int resetGraphExtractionProgress(UnifiedCrawlJob job, int totalChunks) {
        int total = Math.max(0, totalChunks);
        job.getGraphChunksTotal().set(total);
        job.getGraphChunksProcessed().set(0);
        return total;
    }

    int incrementGraphChunksProcessed(UnifiedCrawlJob job, int delta) {
        if (job == null || delta <= 0) {
            return normalizeGraphChunksProcessed(job);
        }
        int total = graphChunksTotal(job);
        return job.getGraphChunksProcessed().updateAndGet(current -> {
            int next = Math.max(0, current) + delta;
            return total > 0 ? Math.min(total, next) : next;
        });
    }

    int normalizeGraphChunksProcessed(UnifiedCrawlJob job) {
        if (job == null) {
            return 0;
        }
        int total = graphChunksTotal(job);
        return job.getGraphChunksProcessed().updateAndGet(current -> {
            int normalized = Math.max(0, current);
            return total > 0 ? Math.min(total, normalized) : normalized;
        });
    }

    int completeGraphExtractionProgress(UnifiedCrawlJob job) {
        if (job == null) {
            return 0;
        }
        int total = graphChunksTotal(job);
        if (total <= 0) {
            return normalizeGraphChunksProcessed(job);
        }
        job.getGraphChunksProcessed().set(total);
        return total;
    }

    int graphChunksTotal(UnifiedCrawlJob job) {
        return job != null ? Math.max(0, job.getGraphChunksTotal().get()) : 0;
    }

    // -------------------------------------------------------------------------
    // Progress reporting helpers
    // -------------------------------------------------------------------------

    private void recordGraphConstructorProgress(UnifiedCrawlJob job,
                                                CostBatch<RetrievedDoc> batch,
                                                Map<String, RetrievedDoc> batchDocsById,
                                                int totalBatches,
                                                GraphConstructor.DocumentExtractionProgress progress,
                                                Set<String> terminalDocIds) {
        if (job == null || progress == null) {
            return;
        }
        if (isCancelled(job)) {
            return;
        }
        RetrievedDoc item = progress.documentId() != null && batchDocsById != null
                ? batchDocsById.get(progress.documentId())
                : null;
        if (item == null && batch != null && batch.items() != null
                && progress.documentIndex() >= 0 && progress.documentIndex() < batch.items().size()) {
            item = batch.items().get(progress.documentIndex());
        }
        String docKey = item != null ? documentTracker.documentKey(item) : progress.documentId();
        if (docKey == null || docKey.isBlank()) {
            docKey = "graph-doc-" + Math.max(0, progress.documentIndex());
        }
        String fileName = item != null
                ? documentTracker.documentFileName(item.getMetadata(), item.getId())
                : docKey;
        int ordinal = Math.max(0, progress.documentIndex()) + 1;
        int total = Math.max(1, progress.totalDocuments());
        int batchIndex = batch != null ? batch.index() : 0;
        String batchLabel = batchIndex > 0
                ? "batch " + batchIndex + "/" + totalBatches
                : "graph extraction";

        job.getCurrentPhase().set("GRAPH_EXTRACTION");
        job.getCurrentFile().set(fileName);
        job.getProgressPercent().accumulateAndGet(estimateProgressForPhase(job, "GRAPH_EXTRACTION"), Math::max);
        memoryMonitor.updateMemorySnapshot(job);
        UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");

        if (progress.status() == GraphConstructor.DocumentExtractionStatus.STARTED) {
            String message = "LLM graph extraction " + ordinal + "/" + total + " started (" + batchLabel + ")";
            job.getCurrentBatchStep().set("GRAPH_CHUNK " + ordinal + "/" + total + " (" + batchLabel + ")");
            pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    normalizeGraphChunksProcessed(job), graphChunksTotal(job),
                    step.getFailedItems().get(), step.getCompletedBatches().get(), step.getTotalBatches().get(),
                    1, fileName, message);
            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                    message, null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
            return;
        }

        boolean failed = progress.status() == GraphConstructor.DocumentExtractionStatus.FAILED
                || progress.status() == GraphConstructor.DocumentExtractionStatus.TIMED_OUT;
        String terminalKey = progress.documentId() != null ? progress.documentId() : docKey;
        if (terminalDocIds != null && !terminalDocIds.add(terminalKey)) {
            return;
        }

        int processed = incrementGraphChunksProcessed(job, 1);
        if (failed) {
            job.getErrorCount().incrementAndGet();
            String error = progress.errorMessage() != null && !progress.errorMessage().isBlank()
                    ? progress.errorMessage()
                    : progress.status().name();
            job.getErrors().add(fileName + ": " + error);
        } else {
            job.getEntitiesExtracted().addAndGet(Math.max(0, progress.entities()));
            job.getRelationshipsExtracted().addAndGet(Math.max(0, progress.relationships()));
        }

        job.getProgressPercent().accumulateAndGet(estimateProgressForPhase(job, "GRAPH_EXTRACTION"), Math::max);
        String message = failed
                ? "LLM graph extraction failed " + ordinal + "/" + total + " (" + batchLabel + ")"
                : "LLM graph extraction complete " + ordinal + "/" + total + " (" + batchLabel + ")";
        String details = failed
                ? progress.errorMessage()
                : progress.entities() + " entities, " + progress.relationships() + " relationships"
                + ", chars=" + progress.textLength() + ", took=" + progress.elapsedMs() + "ms";
        pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                processed, graphChunksTotal(job),
                step.getFailedItems().get() + (failed ? 1 : 0),
                step.getCompletedBatches().get(), step.getTotalBatches().get(),
                1, fileName, message);
        documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", failed ? "FAILED" : "COMPLETED", 0,
                failed ? 0 : Math.max(0, progress.entities()),
                failed ? 0 : Math.max(0, progress.relationships()),
                message, failed ? details : null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", failed ? "ERROR" : "INFO",
                fileName + ": " + message, details);
    }

    private void recordCancelledGraphBatch(UnifiedCrawlJob job,
                                           CostBatch<RetrievedDoc> batch,
                                           String message) {
        if (batch == null || batch.items() == null) {
            return;
        }
        for (RetrievedDoc item : batch.items()) {
            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "CANCELLED", 0, 0, 0,
                    message, null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        }
    }

    private void recordGraphExtractionDiagnostics(UnifiedCrawlJob job,
                                                   Graph graph,
                                                   CostBatch<?> batch,
                                                   int totalBatches) {
        if (graph.getMetadata() == null || graph.getMetadata().isEmpty()) {
            return;
        }
        Object failedDocsValue = graph.getMetadata().get("extractionFailedDocs");
        int failedDocs = failedDocsValue instanceof Number number ? number.intValue() : 0;
        if (failedDocs <= 0) {
            return;
        }

        Object errorsValue = graph.getMetadata().get("extractionErrors");
        String detail = errorsValue instanceof Collection<?> errors
                ? errors.stream().limit(5).map(String::valueOf).collect(Collectors.joining("; "))
                : String.valueOf(errorsValue);
        String message = "Graph extraction batch " + batch.index() + "/" + totalBatches
                + " had " + failedDocs + " document extraction error(s)";
        job.getErrorCount().addAndGet(failedDocs);
        job.getErrors().add(message + (detail == null || detail.isBlank() ? "" : ": " + detail));
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR", message, detail);
        log.warn("[Job {}] {}: {}", job.getJobId(), message, detail);
    }

    private void recordGraphExtractionBatchPerDocument(UnifiedCrawlJob job,
                                                       Graph graph,
                                                       CostBatch<RetrievedDoc> batch,
                                                       int totalBatches) {
        Map<String, int[]> countsByDocId = new HashMap<>();
        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                String docId = documentTracker.sourceDocumentId(entity.getMetadata());
                if (docId != null) {
                    countsByDocId.computeIfAbsent(docId, ignored -> new int[2])[0]++;
                }
            }
        }
        if (graph.getRelationships() != null) {
            for (Relationship relationship : graph.getRelationships()) {
                String docId = documentTracker.sourceDocumentId(relationship.getMetadata());
                if (docId != null) {
                    countsByDocId.computeIfAbsent(docId, ignored -> new int[2])[1]++;
                }
            }
        }

        String batchCompleteMsg = "LLM graph batch " + batch.index() + "/" + totalBatches + " complete";
        for (RetrievedDoc item : batch.items()) {
            int[] counts = countsByDocId.getOrDefault(item.getId(), EMPTY_COUNTS);
            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "COMPLETED", 0,
                    counts[0], counts[1],
                    batchCompleteMsg,
                    null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        }
    }

    // -------------------------------------------------------------------------
    // Configuration helpers
    // -------------------------------------------------------------------------

    private int resolveGraphExtractionBatchSize(GraphExtractionConfig config) {
        int size = graphExtractionBatchSize > 0
                ? graphExtractionBatchSize
                : DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
        return Math.max(1, Math.min(size, 128));
    }

    private int resolveGraphExtractionParallelism(UnifiedCrawlJob job, int plannedBatchCount) {
        return resolveGraphExtractionParallelism(job, plannedBatchCount, 0);
    }

    /**
     * Resolve effective graph extraction parallelism, applying GPU/memory guards only for local
     * inference and using the best available batch-count estimate to avoid capping parallelism
     * too aggressively for item-limited batches on large-context models.
     *
     * @param job               running crawl job
     * @param plannedBatchCount char-budget wave estimate (may be 1 for small-doc corpora on big models)
     * @param totalItemCount    total items to extract; when > 0, used to compute an item-based batch
     *                          count that lower-bounds the parallelism cap so small docs with a large
     *                          char budget don't spuriously collapse to parallelism=1
     */
    private int resolveGraphExtractionParallelism(UnifiedCrawlJob job, int plannedBatchCount,
                                                   int totalItemCount) {
        int configured = Math.max(1, graphExtractionParallelism);
        // When GraphConstructor persists its own matrix graph and embeds inline,
        // reduce parallelism to avoid GPU/OpenMP contention. The default unified
        // crawl path skips constructor-local persistence and writes only the
        // scoped fact-sheet graph, so no embedding throttle is needed there.
        if (graphConstructor != null && graphConstructorPersistMatrixGraph && !graphConstructorSkipEmbedding) {
            configured = Math.min(configured, 2);
        }
        // For remote CLI backends (opencode, deepseek-cli, etc.) the actual work runs in an
        // out-of-process subprocess; JVM heap pressure is irrelevant to model throughput and
        // should not gate parallelism. Only apply the critical-memory cap for local GPU/in-process
        // inference where heap and device memory are genuinely shared with the JVM.
        boolean isLocalInference = graphConstructor != null && graphConstructorPersistMatrixGraph;
        if (isLocalInference) {
            memoryMonitor.updateMemorySnapshot(job);
            if (memoryCriticalThresholdPercent > 0
                    && job.getMemoryUsagePercent().get() >= memoryCriticalThresholdPercent) {
                configured = 1;
            }
        }
        // The char-budget plannedBatchCount can undercount badly for small-document corpora on
        // large-context models: ceil(200 docs × 2 000 chars / 400 000 initChars) = 1, even though
        // the actual item-limited wave count is ceil(200 / maxItems=8) = 25. This incorrectly caps
        // parallelism to min(3, 1) = 1. When totalItemCount is provided, compute the item-based
        // lower bound and use whichever is larger.
        int effectiveBatchCount = plannedBatchCount;
        if (totalItemCount > 0) {
            int itemBasedBatches = Math.max(1,
                    (totalItemCount + Math.max(1, graphExtractionBatchSize) - 1)
                    / Math.max(1, graphExtractionBatchSize));
            effectiveBatchCount = Math.max(plannedBatchCount, itemBasedBatches);
        }
        return Math.max(1, Math.min(configured, Math.max(1, effectiveBatchCount)));
    }

    private boolean isLargeContextModel(ModelCapability modelCap) {
        return modelCap != null && modelCap.contextTokens() > 200_000;
    }

    // -------------------------------------------------------------------------
    // Provider-aware adaptive batching
    // -------------------------------------------------------------------------

    /**
     * Resolve the extraction model's real {@link ModelCapability} (context window + max output tokens)
     * once per batching pass, used to budget per-call char sizes. Uses the optional
     * {@link ModelCapabilityResolver} (wired in app-main over the model registry + model manager) when
     * present; otherwise falls back to the static {@link ModelContextWindows} on the configured model
     * name, defaulting to a safe remote profile. The route's
     * {@link ProcessingRouteConfig.ProcessingBackendType} (when configured) authoritatively decides
     * local-vs-remote.
     */
    ModelCapability resolveExtractionModelCapability(UnifiedCrawlJob job, GraphExtractionConfig config) {
        ProcessingRouteConfig.ProcessingBackendType backendType = primaryLlmBackendType(job);
        String provider = config != null ? config.getLlmProvider() : null;
        String modelName = config != null ? config.getModelName() : null;
        String agentName = primaryLlmAgentName(job);
        if (modelCapabilityResolver != null) {
            try {
                Optional<ModelCapability> resolved =
                        modelCapabilityResolver.resolve(backendType, provider, modelName, agentName);
                if (resolved != null && resolved.isPresent()) {
                    return resolved.get();
                }
            } catch (Exception e) {
                log.debug("[Job {}] ModelCapabilityResolver failed: {}", job.getJobId(), e.getMessage());
            }
        }
        // Fallback (resolver absent — e.g. test/subprocess slice): static registry on the configured
        // model name. LOCAL_MODEL route → local; otherwise treat as remote (the expensive case).
        boolean local = backendType == ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL;
        int contextTokens = ModelContextWindows.getContextWindow(modelName);
        int maxOutputTokens = ModelContextWindows.getMaxOutputTokens(modelName);
        return new ModelCapability(modelName, contextTokens, maxOutputTokens, local);
    }

    /** Lowest-priority (preferred) enabled llm-capable backend type from the job's route, or null. */
    private ProcessingRouteConfig.ProcessingBackendType primaryLlmBackendType(UnifiedCrawlJob job) {
        ProcessingRouteConfig.ProcessingBackend backend = primaryLlmBackend(job);
        return backend != null ? backend.getType() : null;
    }

    /** CLI agent name of the preferred llm backend (used to resolve its live model), or null. */
    private String primaryLlmAgentName(UnifiedCrawlJob job) {
        ProcessingRouteConfig.ProcessingBackend backend = primaryLlmBackend(job);
        return backend != null ? backend.getAgentName() : null;
    }

    /** Lowest-priority (preferred) enabled llm-capable backend from the job's route, or null. */
    private ProcessingRouteConfig.ProcessingBackend primaryLlmBackend(UnifiedCrawlJob job) {
        if (job == null || job.getRequest() == null) {
            return null;
        }
        ProcessingRouteConfig route = job.getRequest().getProcessingRoute();
        if (route == null || route.getBackends() == null || route.getBackends().isEmpty()) {
            return null;
        }
        return route.getBackends().stream()
                .filter(b -> b != null && b.isEnabled())
                .filter(b -> b.getCapabilities() == null || b.getCapabilities().isEmpty()
                        || b.getCapabilities().contains("llm"))
                .min(Comparator.comparingInt(ProcessingRouteConfig.ProcessingBackend::getPriority))
                .orElse(null);
    }

    /**
     * Pull the next wave of cost-balanced batches from {@code sorted} starting at {@code cursor}. Each
     * batch holds up to {@code maxItems} chunks and stays within the {@code charTarget} char budget — a
     * single chunk larger than the budget still gets its own batch (never dropped). Forms up to
     * {@code waveWidth} batches, advances {@code cursor} past consumed chunks, and stamps each batch
     * with a global index from {@code globalIndex}. Returns fewer than {@code waveWidth} on the tail.
     */
    List<CostBatch<RetrievedDoc>> planGraphWave(List<RetrievedDoc> sorted, AtomicInteger cursor,
                                                int charTarget, int maxItems, int waveWidth,
                                                AtomicInteger globalIndex) {
        int n = sorted.size();
        long target = Math.max(1L, charTarget);
        int maxI = Math.max(1, maxItems);
        int width = Math.max(1, waveWidth);
        List<CostBatch<RetrievedDoc>> wave = new ArrayList<>(width);
        while (wave.size() < width && cursor.get() < n) {
            List<RetrievedDoc> items = new ArrayList<>();
            long cost = 0L;
            while (cursor.get() < n && items.size() < maxI) {
                RetrievedDoc doc = sorted.get(cursor.get());
                long c = Math.max(1L, estimateTextCost(doc.getText(), doc.getMetadata()));
                if (!items.isEmpty() && cost + c > target) {
                    break; // adding this chunk would overflow the budget — leave it for the next batch
                }
                items.add(doc);
                cost += c;
                cursor.incrementAndGet();
            }
            if (items.isEmpty()) {
                break;
            }
            wave.add(new CostBatch<>(globalIndex.incrementAndGet(), items, cost));
        }
        return wave;
    }

    // -------------------------------------------------------------------------
    // Prompt and JSON extraction helpers
    // -------------------------------------------------------------------------

    /**
     * Runs one tool-driven model loop for a shard and returns schema-shaped JSON.
     *
     * <p>The model may retrieve exact passages from the complete unified corpus, query the current
     * reasoning graph (including schema, FOL, embeddings, PSL/Bayesian structure, provenance, and
     * assets), and submit any number of source-supported entities and relations. The engine owns
     * validation and mutation: rejected deltas become feedback, and only an accepted delta is returned
     * to the existing merge/persistence path. Every turn still uses the crawl's capacity-aware LLM
     * dispatcher, transcript logging, and progress scope.</p>
     *
     * <p>Returns {@code null} when no delta validates, which the caller already handles through its
     * ordinary retry and failure accounting.</p>
     */
    String extractViaDecomposedPasses(String text,
                                      Document doc,
                                      GraphExtractionConfig config,
                                      Graph targetGraph,
                                      UnifiedCrawlJob job) {
        return extractViaDecomposedPasses(text, doc, config, null, targetGraph, job, null);
    }

    String extractViaDecomposedPasses(String text,
                                      Document doc,
                                      GraphExtractionConfig config,
                                      GraphSchema corpusSchema,
                                      Graph targetGraph,
                                      UnifiedCrawlJob job,
                                      ExtractionTaskContext taskContext) {
        String jobId = job != null ? job.getJobId() : "?";
        String chunkId = doc != null && doc.getId() != null ? doc.getId() : "chunk";
        String sourcePath = docSourcePath(doc);
        String documentId = sourcePath != null ? sourcePath : chunkId;
        try {
            ModelCapability promptCapability = resolveExtractionModelCapability(job, config);
            CrawlCorpusSnapshot corpus = extractionCorpus(job, doc, taskContext);
            ExtractionTaskContext preparedTask = prepareDecomposedTaskContext(
                    text, doc, targetGraph, job, taskContext);
            ExtractionTaskContext effectiveTask = withCorpusSnapshot(
                    preparedTask, corpus.snapshotId());

            AtomicInteger passSequence = new AtomicInteger();
            String phase = effectiveTask.partitionId() != null && !effectiveTask.partitionId().isBlank()
                    ? EntityPartitionCrawlStep.STEP_ID : "GRAPH_EXTRACTION";
            VectorStoreResolution vectors = resolveVectorStore(job);
            String model = config == null ? null
                    : hasText(config.getModelName())
                            ? config.getModelName() : config.getLlmProvider();
            String graphId = targetGraph != null && hasText(targetGraph.getId())
                    ? targetGraph.getId()
                    : jobFactSheetId(job) == null ? "in-run" : "factsheet_" + jobFactSheetId(job);
            String parentGraphId = targetGraph == null ? null : targetGraph.getParentGraphId();

            CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                    chunkId,
                    documentId,
                    model,
                    graphId,
                    parentGraphId,
                    effectiveValidationPolicy(config),
                    buildGraphSchema(config, corpusSchema),
                    corpus,
                    vectors.store(),
                    vectors.initializationError(),
                    () -> reasoningGraphSnapshot(targetGraph, job, model),
                    graphReasoningQueryService);

            ToolDrivenExtractionExecutor.Result result = toolDrivenExecutor.extract(
                    text,
                    effectiveTask,
                    backend,
                    (passId, prompt) -> {
                        int invocation = passSequence.incrementAndGet();
                        int graphEntities = graphEntityCount(targetGraph);
                        int graphRelationships = graphRelationshipCount(targetGraph);
                        CrawlLlmDispatcher.LlmCallScope scope = new CrawlLlmDispatcher.LlmCallScope(
                                phase, passId, invocation, effectiveTask.taskId(),
                                effectiveTask.partitionId(), effectiveTask.chunkId(),
                                effectiveTask.corpusSnapshotId(), effectiveTask.graphRevision(),
                                graphEntities, graphRelationships);
                        emitDecomposedPassProgress(job, phase, scope, "started", "INFO", null);
                        try {
                            String response = llmDispatcher.promptWithCapacityFallback(
                                    prompt, "llm", job, scope);
                            boolean usable = CrawlLlmDispatcher.isUsableLlmResponse(response);
                            emitDecomposedPassProgress(job, phase, scope,
                                    usable ? "completed" : "returned no usable response",
                                    usable ? "INFO" : "WARN", null);
                            return response;
                        } catch (RuntimeException e) {
                            emitDecomposedPassProgress(job, phase, scope, "failed", "WARN",
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                            throw e;
                        }
                    },
                    DecomposedExtractionExecutor.promptProfileFrom(config, promptCapability));
            log.debug("[Job {}] Tool-driven extraction chunk {}: {}", jobId, chunkId, result.summary());
            if (log.isTraceEnabled()) {
                for (String note : result.notes()) {
                    log.trace("[Job {}] chunk {} tool loop note: {}", jobId, chunkId, note);
                }
            }
            return result.usable() ? result.json() : null;
        } catch (RuntimeException e) {
            log.warn("[Job {}] Tool-driven extraction failed for chunk {}: {}",
                    jobId, chunkId, e.toString());
            return null;
        }
    }

    private static ExtractionTaskContext withCorpusSnapshot(
            ExtractionTaskContext task,
            String snapshotId) {
        if (task == null || Objects.equals(task.corpusSnapshotId(), snapshotId)) {
            return task;
        }
        return new ExtractionTaskContext(
                task.taskId(),
                task.partitionId(),
                snapshotId,
                task.subjects(),
                task.chunkId(),
                task.discoveryChannel(),
                task.evidenceReason(),
                task.evidenceConfidence(),
                task.graphRevision(),
                task.graphContext(),
                task.conceptHints(),
                task.sourceSpans());
    }

    private record VectorStoreResolution(
            VectorStore store,
            String initializationError) {
    }

    private VectorStoreResolution resolveVectorStore(UnifiedCrawlJob job) {
        if (vectorStores == null) {
            return new VectorStoreResolution(null, null);
        }
        try {
            return new VectorStoreResolution(vectorStores.getIfAvailable(), null);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[Job {}] Unified-corpus embedding backend initialization failed: {}",
                    job == null ? "?" : job.getJobId(), detail);
            return new VectorStoreResolution(null, detail);
        }
    }

    /**
     * Combines persisted graph facets with not-yet-persisted in-run extraction state. The bridge
     * retains embeddings, FOL atoms, opinions, provenance, and analysis assets; the transient overlay
     * makes each accepted shard immediately visible to later model tool calls.
     */
    private UnifiedGraph reasoningGraphSnapshot(
            Graph targetGraph,
            UnifiedCrawlJob job,
            String model) {
        Long factSheetId = jobFactSheetId(job);
        UnifiedGraph graph = unifiedGraphBridge != null && factSheetId != null
                ? unifiedGraphBridge.export(factSheetId)
                : new UnifiedGraph()
                        .graphId(factSheetId == null ? "in-run" : "factsheet_" + factSheetId)
                        .factSheetId(factSheetId);
        if (targetGraph != null) {
            synchronized (targetGraph) {
                ExtractionToUnifiedGraph.apply(
                        graph, GraphExtractionValidator.fromGraph(targetGraph, model));
            }
        }
        return graph;
    }

    private Map<String, String> knownEntityTypes(
            Graph targetGraph,
            UnifiedCrawlJob job,
            GraphExtractionConfig config) {
        String model = config == null ? null : config.getModelName();
        Map<String, String> types = new LinkedHashMap<>();
        for (var entity : reasoningGraphSnapshot(targetGraph, job, model).entities()) {
            if (entity != null && hasText(entity.id()) && hasText(entity.type())) {
                types.put(entity.id(), entity.type());
            }
        }
        return types;
    }

    private void emitDecomposedPassProgress(
            UnifiedCrawlJob job,
            String phase,
            CrawlLlmDispatcher.LlmCallScope scope,
            String state,
            String level,
            String extraDetail) {
        if (job == null || scope == null) {
            return;
        }
        job.getCurrentPhase().set(phase);
        String passLabel = decomposedPassLabel(scope.passId());
        String currentItem = scope.passId() + " #" + scope.passInvocation()
                + (scope.partitionId() != null && !scope.partitionId().isBlank()
                        ? " | " + scope.partitionId() : "")
                + (scope.chunkId() != null && !scope.chunkId().isBlank()
                        ? " | " + scope.chunkId() : "");
        String message = passLabel + " pass " + state;
        if (pipelineStepTracker != null) {
            pipelineStepTracker.updatePipelineStep(job, phase,
                    UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    -1, -1, -1, -1, -1, -1, currentItem, message);
        }

        List<String> details = new ArrayList<>();
        details.add("pass=" + scope.passId() + "#" + scope.passInvocation());
        if (scope.taskId() != null && !scope.taskId().isBlank()) {
            details.add("task=" + scope.taskId());
        }
        if (scope.partitionId() != null && !scope.partitionId().isBlank()) {
            details.add("partition=" + scope.partitionId());
        }
        if (scope.chunkId() != null && !scope.chunkId().isBlank()) {
            details.add("chunk=" + scope.chunkId());
        }
        if (scope.corpusSnapshotId() != null && !scope.corpusSnapshotId().isBlank()) {
            details.add("corpus=" + scope.corpusSnapshotId());
        }
        if (scope.graphRevision() != null && !scope.graphRevision().isBlank()) {
            details.add("graph=" + scope.graphRevision());
        }
        details.add("graphState=" + scope.graphEntities() + " entities/"
                + scope.graphRelationships() + " relationships");
        if (extraDetail != null && !extraDetail.isBlank()) {
            details.add("detail=" + extraDetail);
        }
        if (documentTracker != null) {
            documentTracker.recordEvent(job, phase, level, message, String.join(", ", details));
        }
        Consumer<UnifiedCrawlJob> notifier = progressNotifier;
        if (notifier != null) {
            try {
                notifier.accept(job);
            } catch (Exception ignored) {
                // Progress notification is best-effort; extraction remains authoritative.
            }
        }
    }

    private static String decomposedPassLabel(String passId) {
        return switch (passId == null ? "" : passId) {
            case ToolDrivenExtractionExecutor.PASS_ID -> "Tool-guided extraction";
            case "propositions" -> "Atomic propositions";
            case "mentions" -> "Entity mentions";
            case "epistemic" -> "Epistemic classification";
            case "relations" -> "Relation extraction";
            case "claims" -> "Claim extraction";
            default -> passId == null || passId.isBlank() ? "Extraction" : passId;
        };
    }

    static boolean requiresAtomicOrderedDispatch(GraphExtractionConfig config) {
        return DecomposedExtractionExecutor.isEnabled(config);
    }

    /** Builds the exact bounded hints and incremental graph snapshot used by production passes. */
    ExtractionTaskContext prepareDecomposedTaskContext(
            String text,
            Document doc,
            Graph graph,
            UnifiedCrawlJob job,
            ExtractionTaskContext supplied) {
        String chunkId = doc != null && doc.getId() != null ? doc.getId() : "chunk";
        String jobId = job != null && job.getJobId() != null ? job.getJobId() : "preview";
        ExtractionTaskContext base = supplied != null ? supplied : new ExtractionTaskContext(
                jobId + ":" + chunkId,
                null,
                null,
                List.of(),
                chunkId,
                "SOURCE_CHUNK",
                "direct source text selected by the crawl",
                null,
                null,
                null);

        Map<String, ConceptHint> hints = new LinkedHashMap<>();
        if (base.conceptHints() != null) {
            for (ConceptHint hint : base.conceptHints()) {
                addConceptHint(hints, hint);
            }
        }
        for (String subject : base.subjects()) {
            addConceptHint(hints, new ConceptHint(subject, "PARTITION_SUBJECT",
                    "unified-corpus-partition", null));
        }
        if (conceptExtractor != null && text != null && !text.isBlank()) {
            ConceptExtractor.ExtractionConfig hintConfig = new ConceptExtractor.ExtractionConfig(
                    16, 0.3, true, true, false,
                    List.of("TOPIC", "THEME", "KEYWORD", "ENTITY"), true);
            ConceptExtractor.ExtractionResult extracted = conceptExtractor.extractConcepts(text,
                    hintConfig);
            if (extracted != null && extracted.concepts() != null) {
                for (ConceptExtractor.ExtractedConcept concept : extracted.concepts()) {
                    if (concept == null) {
                        continue;
                    }
                    addConceptHint(hints, new ConceptHint(concept.name(), concept.category(),
                            "deterministic-statistical-prepass", concept.context()));
                }
            }
        }
        ExtractionTaskContext prepared = base.withConceptHints(
                hints.values().stream().limit(16).toList());
        if (prepared.sourceSpans().isEmpty()) {
            prepared = prepared.withSourceSpans(sourceEventSpans(doc, text));
        }
        return contextualize(prepared, graph);
    }

    /**
     * Reads a source-native event plan emitted by a loader/preprocessor. The metadata format stays
     * plain JSON ({@code [{start,end,kind}, ...]}) so crawl archives and remote workers can preserve
     * it without depending on this Java record type. Offsets are relative to the exact chunk text.
     */
    static List<SourceSpan> sourceEventSpans(Document doc, String text) {
        if (doc == null || doc.getMetadata() == null || text == null) {
            return List.of();
        }
        Object raw = doc.getMetadata().get(GraphConstants.META_SOURCE_EVENT_SPANS);
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        List<SourceSpan> spans = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof SourceSpan span) {
                if (span.end() <= text.length()) {
                    spans.add(span);
                }
                continue;
            }
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            Integer start = spanOffset(map.get("start"));
            Integer end = spanOffset(map.get("end"));
            if (start == null || end == null || start < 0 || end <= start || end > text.length()) {
                continue;
            }
            Object kind = map.get("kind");
            spans.add(new SourceSpan(start, end, kind == null ? null : String.valueOf(kind)));
        }
        return List.copyOf(spans);
    }

    private static Integer spanOffset(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void addConceptHint(Map<String, ConceptHint> target, ConceptHint hint) {
        if (hint == null || hint.term() == null || hint.term().isBlank()) {
            return;
        }
        target.putIfAbsent(hint.term().strip().toLowerCase(Locale.ROOT), hint);
    }

    private String buildExtractionPrompt(GraphExtractionConfig config, GraphSchema schemaOverride) {
        StringBuilder sb = new StringBuilder();
        sb.append(GraphExtractionValidator.getExtractionPromptInstructions(
                effectiveValidationPolicy(config), buildGraphSchema(config, schemaOverride)));

        if (config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
            sb.append("\n\nFocus on extracting these entity types: ");
            sb.append(String.join(", ", config.getEntityTypes()));
        }

        if (config.getRelationshipTypes() != null && !config.getRelationshipTypes().isEmpty()) {
            sb.append("\nFocus on extracting these relationship types: ");
            sb.append(String.join(", ", config.getRelationshipTypes()));
        }

        if (config.getCustomPrompt() != null && !config.getCustomPrompt().isBlank()) {
            sb.append("\n\nAdditional instructions: ").append(config.getCustomPrompt());
        }

        return sb.toString();
    }

    private String extractJsonFromResponse(String response) {
        // Delegates to the shared isolator so tool-call-prefix handling stays in lockstep
        // with MatrixGraphConstructor and cannot silently regress (see LlmJsonExtractor).
        return LlmJsonExtractor.extractJsonObject(response);
    }

    // -------------------------------------------------------------------------
    // Graph merge and schema helpers
    // -------------------------------------------------------------------------

    private void mergeGraphInto(Graph source, Graph target, GraphExtractionConfig config) {
        if (target.getEntities() == null) {
            target.setEntities(new ArrayList<>());
        }
        if (target.getRelationships() == null) {
            target.setRelationships(new ArrayList<>());
        }

        Map<String, Entity> entitiesById = new LinkedHashMap<>();
        Map<String, Entity> entitiesByResolutionKey = new LinkedHashMap<>();
        for (Entity existing : target.getEntities()) {
            if (existing == null) {
                continue;
            }
            if (hasText(existing.getId())) {
                entitiesById.putIfAbsent(existing.getId(), existing);
            }
            String key = entityResolutionKey(existing);
            if (key != null) {
                entitiesByResolutionKey.putIfAbsent(key, existing);
            }
        }

        // Every source-local id is mapped to the entity id that actually survives in the target.
        // Without this map, title/type deduplication can skip a node while still appending edges
        // that point at its discarded id, creating an orphan relationship.
        Map<String, String> canonicalEntityIds = new HashMap<>();
        if (source.getEntities() != null) {
            for (Entity entity : source.getEntities()) {
                if (entity == null || !hasText(entity.getId())) {
                    continue;
                }
                if (belowConfidence(entity.getConfidence(), config)) {
                    traceDecision(entity.getId(), GraphMissStage.GRAPH_ADMISSION,
                            GraphMissReason.EXTRACTION_CONFIDENCE_BELOW_THRESHOLD,
                            "rejected_extraction_confidence", List.of(entity.getId()),
                            score(entity.getId(), entity.getConfidence()),
                            Map.of("threshold", String.valueOf(config.getEffectiveExtractionMinConfidence())));
                    continue;
                }

                Entity existing = entitiesById.get(entity.getId());
                String resolutionKey = entityResolutionKey(entity);
                if (existing == null && config.isEntityResolution() && resolutionKey != null) {
                    existing = entitiesByResolutionKey.get(resolutionKey);
                }
                if (existing != null) {
                    canonicalEntityIds.put(entity.getId(), existing.getId());
                    mergeEntityEvidence(existing, entity);
                    traceDecision(entity.getId(), GraphMissStage.MENTION_IDENTITY, null,
                            "merged", List.of(entity.getId(), existing.getId()), Map.of(),
                            Map.of("resolution", existing.getId().equals(entity.getId())
                                    ? "id" : "normalized_title_type"));
                    continue;
                }

                prepareEntityEvidence(entity);
                target.getEntities().add(entity);
                traceDecision(entity.getId(), GraphMissStage.GRAPH_ADMISSION, null,
                        "accepted_entity", List.of(entity.getId()),
                        score(entity.getId(), entity.getConfidence()), Map.of());
                entitiesById.put(entity.getId(), entity);
                canonicalEntityIds.put(entity.getId(), entity.getId());
                if (resolutionKey != null) {
                    entitiesByResolutionKey.putIfAbsent(resolutionKey, entity);
                }
            }
        }

        Set<String> targetEntityIds = target.getEntities().stream()
                .filter(Objects::nonNull)
                .map(Entity::getId)
                .filter(GraphExtractionOrchestrator::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Relationship> relationshipsByAtom = new LinkedHashMap<>();
        for (Relationship existing : target.getRelationships()) {
            String key = relationshipAtom(existing);
            if (key != null) {
                relationshipsByAtom.putIfAbsent(key, existing);
            }
        }

        if (source.getRelationships() != null) {
            for (Relationship relationship : source.getRelationships()) {
                if (relationship == null) {
                    continue;
                }
                String relationAtom = relationshipAtom(relationship);
                if (belowConfidence(relationship.getConfidence(), config)) {
                    traceDecision(relationAtom, GraphMissStage.GRAPH_ADMISSION,
                            GraphMissReason.EXTRACTION_CONFIDENCE_BELOW_THRESHOLD,
                            "rejected_extraction_confidence", List.of(), Map.of(),
                            Map.of("threshold", String.valueOf(config.getEffectiveExtractionMinConfidence()),
                                    "confidence", String.valueOf(relationship.getConfidence())));
                    continue;
                }
                String canonicalSource = canonicalEntityIds.getOrDefault(
                        relationship.getSource(), relationship.getSource());
                String canonicalTarget = canonicalEntityIds.getOrDefault(
                        relationship.getTarget(), relationship.getTarget());
                if (!targetEntityIds.contains(canonicalSource) || !targetEntityIds.contains(canonicalTarget)) {
                    log.warn("Skipping relationship {} -[{}]-> {} because its canonical endpoints "
                                    + "are not both present in the in-run graph",
                            relationship.getSource(), relationship.getType(), relationship.getTarget());
                    traceDecision(relationAtom, GraphMissStage.VALIDATOR,
                            GraphMissReason.VALIDATOR_REJECTED, "rejected_incomplete_endpoints",
                            List.of(relationship.getSource(), relationship.getTarget()), Map.of(),
                            Map.of("sourcePresent", String.valueOf(targetEntityIds.contains(canonicalSource)),
                                    "targetPresent", String.valueOf(targetEntityIds.contains(canonicalTarget))));
                    continue;
                }

                relationship.setSource(canonicalSource);
                relationship.setTarget(canonicalTarget);
                String atom = relationshipAtom(relationship);
                Relationship existing = relationshipsByAtom.get(atom);
                if (existing != null) {
                    mergeRelationshipEvidence(existing, relationship);
                    continue;
                }

                prepareRelationshipEvidence(relationship);
                target.getRelationships().add(relationship);
                traceDecision(atom, GraphMissStage.GRAPH_ADMISSION, null,
                        "accepted_relation", List.of(canonicalSource, canonicalTarget),
                        score(atom, relationship.getConfidence()), Map.of("endpointsComplete", "true"));
                relationshipsByAtom.put(atom, relationship);
            }
        }
    }

    private void traceDecision(String atom, GraphMissStage stage, GraphMissReason reason,
                               String disposition, List<String> candidates,
                               Map<String, Double> scores, Map<String, String> metadata) {
        graphDecisionTraceSink.trace(new GraphDecisionTraceEvent(
                UUID.randomUUID().toString(), null, null, null, null, atom, stage, reason,
                disposition, candidates, scores, metadata));
    }

    private void traceValidation(GraphExtractionSchema.ExtractionResult result,
                                 GraphExtractionValidator.ValidationResult validation) {
        if (validation == null) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        if (result != null) {
            result.entities().stream().map(GraphExtractionSchema.ExtractedEntity::id)
                    .filter(Objects::nonNull).forEach(candidates::add);
            result.relations().stream().filter(Objects::nonNull).forEach(relation -> candidates.add(
                    String.valueOf(relation.source()) + "-[" + relation.type() + "]->"
                            + String.valueOf(relation.target())));
        }
        traceDecision("extraction", GraphMissStage.VALIDATOR,
                validation.valid() ? null : GraphMissReason.VALIDATOR_REJECTED,
                validation.valid() ? "accepted_validation" : "rejected_validation",
                candidates, Map.of(),
                Map.of("errors", String.join(" | ", validation.errors()),
                        "warnings", String.join(" | ", validation.warnings())));
    }

    private static Map<String, Double> score(String id, Double value) {
        return id == null || value == null || !Double.isFinite(value) ? Map.of() : Map.of(id, value);
    }

    private static boolean belowConfidence(Double confidence, GraphExtractionConfig config) {
        return confidence != null && confidence < config.getEffectiveExtractionMinConfidence();
    }

    private static String entityResolutionKey(Entity entity) {
        if (entity == null || !hasText(entity.getTitle()) || !hasText(entity.getType())) {
            return null;
        }
        return entity.getType().strip().toUpperCase(Locale.ROOT) + "|"
                + entity.getTitle().strip().toLowerCase(Locale.ROOT);
    }

    private static String relationshipAtom(Relationship relationship) {
        if (relationship == null || !hasText(relationship.getSource())
                || !hasText(relationship.getTarget()) || !hasText(relationship.getType())) {
            return null;
        }
        return relationship.getSource() + "|"
                + relationship.getType().strip().toUpperCase(Locale.ROOT) + "|"
                + relationship.getTarget();
    }

    private static void mergeEntityEvidence(Entity existing, Entity incoming) {
        existing.setAliases(mergeStrings(existing.getAliases(), incoming.getAliases()));
        existing.setTextUnits(mergeStrings(existing.getTextUnits(), incoming.getTextUnits()));
        existing.setConfidence(max(existing.getConfidence(), incoming.getConfidence()));
        if (!hasText(existing.getDescription()) && hasText(incoming.getDescription())) {
            existing.setDescription(incoming.getDescription());
        }
        existing.setMetadata(mergeEvidenceMetadata(existing.getMetadata(), incoming.getMetadata(), true));
    }

    private static void prepareEntityEvidence(Entity entity) {
        entity.setAliases(mergeStrings(entity.getAliases(), List.of()));
        entity.setTextUnits(mergeStrings(entity.getTextUnits(), List.of()));
        entity.setMetadata(mergeEvidenceMetadata(Map.of(), entity.getMetadata(), false));
    }

    private static void mergeRelationshipEvidence(Relationship existing, Relationship incoming) {
        Map<String, Object> metadata = mergeEvidenceMetadata(
                existing.getMetadata(), incoming.getMetadata(), true);
        int uniqueEvidence = metadata.get("supportingEvidence") instanceof Collection<?> evidence
                ? evidence.size() : 0;
        int supporting = uniqueEvidence > 0 ? uniqueEvidence : Math.max(
                intMetadata(existing.getMetadata(), "supportingCount", 1),
                intMetadata(incoming.getMetadata(), "supportingCount", 1));
        int refuting = Math.max(intMetadata(existing.getMetadata(), "refutingCount", 0),
                intMetadata(incoming.getMetadata(), "refutingCount", 0));
        metadata.put("supportingCount", supporting);
        metadata.put("refutingCount", refuting);
        existing.setMetadata(metadata);
        existing.setConfidence(max(existing.getConfidence(), incoming.getConfidence()));
        existing.setWeight(max(existing.getWeight(), incoming.getWeight()));
        if (!hasText(existing.getDescription()) && hasText(incoming.getDescription())) {
            existing.setDescription(incoming.getDescription());
        }
        List<String> occurrenceTimes = mergeStrings(
                metadataStrings(existing.getMetadata(), "occurrenceTimes"),
                mergeStrings(singleton(existing.getOccurredAt()), singleton(incoming.getOccurredAt())));
        if (!occurrenceTimes.isEmpty()) {
            metadata.put("occurrenceTimes", occurrenceTimes);
            existing.setOccurredAt(occurrenceTimes.get(0));
        }
    }

    private static void prepareRelationshipEvidence(Relationship relationship) {
        Map<String, Object> metadata = mergeEvidenceMetadata(Map.of(), relationship.getMetadata(), false);
        metadata.putIfAbsent("supportingCount", 1);
        metadata.putIfAbsent("refutingCount", 0);
        List<String> occurrenceTimes = singleton(relationship.getOccurredAt());
        if (!occurrenceTimes.isEmpty()) {
            metadata.put("occurrenceTimes", occurrenceTimes);
        }
        relationship.setMetadata(metadata);
    }

    private static Map<String, Object> mergeEvidenceMetadata(Map<String, Object> first,
                                                              Map<String, Object> second,
                                                              boolean aggregateSupport) {
        Map<String, Object> merged = new LinkedHashMap<>();
        copyMetadata(merged, first);
        copyMetadata(merged, second);
        List<Map<String, Object>> evidence = new ArrayList<>();
        appendEvidence(evidence, first);
        appendEvidence(evidence, second);
        if (!evidence.isEmpty()) {
            merged.put("supportingEvidence", List.copyOf(evidence));
            List<String> chunkIds = evidence.stream()
                    .map(record -> Objects.toString(record.get("sourceChunkId"), null))
                    .filter(GraphExtractionOrchestrator::hasText)
                    .distinct()
                    .toList();
            if (!chunkIds.isEmpty()) {
                merged.put("sourceChunkIds", chunkIds);
            }
        }
        if (aggregateSupport) {
            merged.putIfAbsent("supportingCount", Math.max(1, evidence.size()));
        }
        return merged;
    }

    private static void copyMetadata(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() == null || "supportingEvidence".equals(entry.getKey())) {
                continue;
            }
            Object current = target.get(entry.getKey());
            if (current instanceof Collection<?> currentValues
                    && entry.getValue() instanceof Collection<?> incomingValues) {
                LinkedHashSet<Object> combined = new LinkedHashSet<>(currentValues);
                combined.addAll(incomingValues);
                target.put(entry.getKey(), List.copyOf(combined));
            } else {
                target.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void appendEvidence(List<Map<String, Object>> sink, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        Object archived = metadata.get("supportingEvidence");
        if (archived instanceof Collection<?> records) {
            for (Object value : records) {
                if (value instanceof Map<?, ?> record) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : record.entrySet()) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    appendEvidenceRecord(sink, copy);
                }
            }
        }
        Map<String, Object> direct = new LinkedHashMap<>();
        for (String key : List.of("sourceChunkId", "evidenceQuote", "evidenceStart", "evidenceEnd",
                "evidenceRole", "propositionId", "propositionText")) {
            Object value = metadata.get(key);
            if (value != null) {
                direct.put(key, value);
            }
        }
        appendEvidenceRecord(sink, direct);
    }

    private static void appendEvidenceRecord(List<Map<String, Object>> sink,
                                             Map<String, Object> record) {
        if (record == null || record.isEmpty()) {
            return;
        }
        for (int index = 0; index < sink.size(); index++) {
            Map<String, Object> existing = sink.get(index);
            if (!sameEvidenceOccurrence(existing, record)) {
                continue;
            }
            Map<String, Object> enriched = new LinkedHashMap<>(existing);
            record.forEach(enriched::putIfAbsent);
            sink.set(index, Map.copyOf(enriched));
            return;
        }
        sink.add(Map.copyOf(record));
    }

    /**
     * Evidence records often appear twice during graph projection: once as an archived record
     * enriched with document metadata and once through the legacy direct metadata fields. Treat
     * those as the same occurrence without collapsing different passages or source spans.
     */
    private static boolean sameEvidenceOccurrence(Map<String, Object> first,
                                                  Map<String, Object> second) {
        String firstChunk = Objects.toString(first.get("sourceChunkId"), null);
        String secondChunk = Objects.toString(second.get("sourceChunkId"), null);
        if (hasText(firstChunk) && hasText(secondChunk) && firstChunk.equals(secondChunk)) {
            Object firstStart = first.get("evidenceStart");
            Object firstEnd = first.get("evidenceEnd");
            Object secondStart = second.get("evidenceStart");
            Object secondEnd = second.get("evidenceEnd");
            if (firstStart != null && firstEnd != null && secondStart != null && secondEnd != null) {
                return Objects.equals(firstStart, secondStart) && Objects.equals(firstEnd, secondEnd);
            }
            String firstQuote = Objects.toString(first.get("evidenceQuote"), null);
            String secondQuote = Objects.toString(second.get("evidenceQuote"), null);
            if (hasText(firstQuote) && hasText(secondQuote)) {
                return firstQuote.equals(secondQuote);
            }
        }
        String firstProposition = Objects.toString(first.get("propositionId"), null);
        String secondProposition = Objects.toString(second.get("propositionId"), null);
        if (hasText(firstProposition) && hasText(secondProposition)) {
            return firstProposition.equals(secondProposition);
        }
        return first.equals(second);
    }

    private static int intMetadata(Map<String, Object> metadata, String key, int fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> metadataStrings(Map<String, Object> metadata, String key) {
        if (metadata == null || !(metadata.get(key) instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    private static List<String> singleton(String value) {
        return hasText(value) ? List.of(value) : List.of();
    }

    private static List<String> mergeStrings(Collection<String> first, Collection<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            first.stream().filter(GraphExtractionOrchestrator::hasText)
                    .map(String::strip).forEach(merged::add);
        }
        if (second != null) {
            second.stream().filter(GraphExtractionOrchestrator::hasText)
                    .map(String::strip).forEach(merged::add);
        }
        return List.copyOf(merged);
    }

    private static Double max(Double first, Double second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : Math.max(first, second);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Builds the exact standardized schema used by extraction tools and validation.
     *
     * <p>The full preset definitions are preserved: entity and relation descriptions, properties,
     * relation aliases, and allowed source/relation/target shapes. The legacy type-name lists are
     * optional focus subsets; they never flatten a full schema back into name-only placeholders.</p>
     *
     * <p>Package visibility lets production-parity harnesses reuse this mapping instead of
     * accidentally constructing a tool backend without the crawl's schema.</p>
     */
    GraphSchema buildGraphSchema(GraphExtractionConfig config) {
        return buildGraphSchema(config, null);
    }

    /**
     * Builds schema with an optional corpus-derived overlay.
     */
    GraphSchema buildGraphSchema(GraphExtractionConfig config, GraphSchema corpusSchemaOverride) {
        GraphSchema configured = parseConfiguredSchema(config);
        if (configured == null) {
            return corpusSchemaOverride;
        }
        if (corpusSchemaOverride == null) {
            return configured;
        }

        boolean allowInferredNodes = config.getEntityTypes() == null || config.getEntityTypes().isEmpty();
        boolean allowInferredRelations = config.getRelationshipTypes() == null
                || config.getRelationshipTypes().isEmpty();

        List<NodeType> nodeTypes = allowInferredNodes
                ? mergeNodeTypes(configured.getNodeTypes(), corpusSchemaOverride.getNodeTypes())
                : configured.getNodeTypes();
        List<RelationshipType> relationTypes = allowInferredRelations
                ? mergeRelationshipTypes(configured.getRelationshipTypes(),
                corpusSchemaOverride.getRelationshipTypes())
                : configured.getRelationshipTypes();

        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        if (configured.getPatterns() != null) {
            patterns.addAll(configured.getPatterns());
        }
        if (corpusSchemaOverride.getPatterns() != null) {
            patterns.addAll(corpusSchemaOverride.getPatterns());
        }

        if ((nodeTypes == null || nodeTypes.isEmpty())
                && (relationTypes == null || relationTypes.isEmpty())
                && patterns.isEmpty()) {
            return null;
        }

        return new GraphSchema(
                nodeTypes == null || nodeTypes.isEmpty() ? null : nodeTypes,
                relationTypes == null || relationTypes.isEmpty() ? null : relationTypes,
                patterns.isEmpty() ? null : List.copyOf(patterns));
    }

    private GraphSchema parseConfiguredSchema(GraphExtractionConfig config) {
        if (config == null) {
            return null;
        }

        GraphSchema standardized = config.getStandardizedSchema();
        Map<String, NodeType> definedEntities = new LinkedHashMap<>();
        if (standardized != null && standardized.getNodeTypes() != null) {
            for (NodeType type : standardized.getNodeTypes()) {
                if (type != null && hasText(type.getLabel())) {
                    definedEntities.put(schemaTypeKey(type.getLabel()), type);
                }
            }
        }
        Map<String, RelationshipType> definedRelations = new LinkedHashMap<>();
        if (standardized != null && standardized.getRelationshipTypes() != null) {
            for (RelationshipType type : standardized.getRelationshipTypes()) {
                if (type != null && hasText(type.getType())) {
                    definedRelations.put(schemaTypeKey(type.getType()), type);
                }
            }
        }

        List<String> requestedEntities = normalizedSchemaTypes(config.getEntityTypes());
        List<NodeType> nodeTypes = requestedEntities.isEmpty()
                ? (definedEntities.isEmpty() ? null : List.copyOf(definedEntities.values()))
                : requestedEntities.stream()
                        .map(type -> definedEntities.getOrDefault(
                                schemaTypeKey(type), new NodeType(type, null, null)))
                        .toList();

        List<String> requestedRelations = normalizedSchemaTypes(config.getRelationshipTypes());
        List<RelationshipType> relationTypes = requestedRelations.isEmpty()
                ? (definedRelations.isEmpty() ? null : List.copyOf(definedRelations.values()))
                : requestedRelations.stream()
                        .map(type -> definedRelations.getOrDefault(
                                schemaTypeKey(type), new RelationshipType(type, null, null)))
                        .toList();

        Set<String> patterns = new LinkedHashSet<>();
        if (standardized != null && standardized.getPatterns() != null) {
            standardized.getPatterns().stream()
                    .filter(GraphExtractionOrchestrator::hasText)
                    .map(String::trim)
                    .forEach(patterns::add);
        }
        patterns.addAll(effectiveValidationPolicy(config).effectiveRelationPatterns());

        if (nodeTypes == null && relationTypes == null && patterns.isEmpty()) {
            return null;
        }
        return new GraphSchema(nodeTypes, relationTypes,
                patterns.isEmpty() ? null : List.copyOf(patterns));
    }

    private static List<NodeType> mergeNodeTypes(List<NodeType> configured, List<NodeType> corpusDerived) {
        if (configured == null || configured.isEmpty()) {
            return corpusDerived;
        }
        if (corpusDerived == null || corpusDerived.isEmpty()) {
            return configured;
        }
        Set<String> known = configured.stream()
                .filter(Objects::nonNull)
                .map(NodeType::getLabel)
                .filter(GraphExtractionOrchestrator::hasText)
                .map(GraphExtractionOrchestrator::schemaTypeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<NodeType> merged = new ArrayList<>(configured);
        for (NodeType candidate : corpusDerived) {
            if (candidate == null || !hasText(candidate.getLabel())) {
                continue;
            }
            String key = schemaTypeKey(candidate.getLabel());
            if (known.add(key)) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private static List<RelationshipType> mergeRelationshipTypes(List<RelationshipType> configured,
                                                               List<RelationshipType> corpusDerived) {
        if (configured == null || configured.isEmpty()) {
            return corpusDerived;
        }
        if (corpusDerived == null || corpusDerived.isEmpty()) {
            return configured;
        }
        Set<String> known = configured.stream()
                .filter(Objects::nonNull)
                .map(RelationshipType::getType)
                .filter(GraphExtractionOrchestrator::hasText)
                .map(GraphExtractionOrchestrator::schemaTypeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<RelationshipType> merged = new ArrayList<>(configured);
        for (RelationshipType candidate : corpusDerived) {
            if (candidate == null || !hasText(candidate.getType())) {
                continue;
            }
            String key = schemaTypeKey(candidate.getType());
            if (known.add(key)) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private static List<String> normalizedSchemaTypes(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        configured.stream()
                .filter(GraphExtractionOrchestrator::hasText)
                .map(String::trim)
                .forEach(normalized::add);
        return List.copyOf(normalized);
    }

    private static String schemaTypeKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private GraphExtractionValidationPolicy effectiveValidationPolicy(GraphExtractionConfig config) {
        return config == null || config.getValidationPolicy() == null
                ? GraphExtractionValidationPolicy.defaults()
                : config.getValidationPolicy();
    }

    private String validationFeedback(List<String> errors,
                                      GraphExtractionValidationPolicy policy) {
        if (errors == null || errors.isEmpty()) {
            return "Unknown validation failure";
        }
        int limit = policy == null
                ? GraphExtractionValidationPolicy.DEFAULT_MAX_ERRORS_IN_RETRY_PROMPT
                : policy.effectiveMaxErrorsInRetryPrompt();
        String feedback = errors.stream().limit(limit).collect(Collectors.joining("; "));
        if (errors.size() > limit) {
            feedback += "; ... and " + (errors.size() - limit) + " more";
        }
        return feedback;
    }

    private void logValidationWarnings(String jobId, String scope, List<String> warnings) {
        if (warnings != null && !warnings.isEmpty()) {
            log.warn("[Job {}] Accepted {} graph extraction with {} validation warning(s): {}",
                    jobId, scope, warnings.size(),
                    validationFeedback(warnings, GraphExtractionValidationPolicy.defaults()));
        }
    }

    // -------------------------------------------------------------------------
    // Graph extraction checkpoint helper
    // -------------------------------------------------------------------------

    private void recordGraphExtractionCheckpoint(UnifiedCrawlJob job,
                                                 GraphExtractionConfig config,
                                                 Collection<RetrievedDoc> docs,
                                                 GraphPersistenceHelper.GraphPersistResult persisted) {
        if (graphExtractionCheckpointStore == null || job == null || docs == null || docs.isEmpty()) {
            return;
        }
        int entities = persisted != null ? persisted.entities() : 0;
        int relationships = persisted != null ? persisted.relationships() : 0;
        if (entities + relationships <= 0) {
            log.warn("[Job {}] Not checkpointing graph extraction batch with zero persisted semantic output ({} docs)",
                    job.getJobId(), docs.size());
            return;
        }
        graphExtractionCheckpointStore.recordCompletedBatch(jobFactSheetId(job), config, docs,
                job.getJobId(), entities, relationships);
    }

    // -------------------------------------------------------------------------
    // Document conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a Spring AI Document to a RetrievedDoc for use with GraphConstructor.
     *
     * <p><strong>Precondition:</strong> {@code doc.getText()} must be non-null and non-blank.
     * {@link RetrievedDoc} enforces "exactly one of text or media must be specified", so passing
     * a null-text document causes an {@link IllegalArgumentException} at construction time which
     * propagates as an {@link java.util.concurrent.ExecutionException} that fails the entire RPC
     * batch (not just the offending chunk). Callers must guard with
     * {@link #hasExtractableText(Document)} before calling this method.</p>
     */
    private static RetrievedDoc toRetrievedDoc(Document doc) {
        Map<String, Object> metadata = doc.getMetadata() != null
                ? new HashMap<>(doc.getMetadata())
                : new HashMap<>();
        return new RetrievedDoc(doc.getId(), doc.getText(), metadata);
    }

    /**
     * Returns {@code true} when the document has non-null, non-blank text content that can be
     * wrapped in a {@link RetrievedDoc} without triggering the "exactly one of text or media"
     * invariant check.
     */
    static boolean hasExtractableText(Document doc) {
        return doc != null && doc.getText() != null && !doc.getText().isBlank();
    }

    /**
     * Filters a list of Spring AI {@link Document}s, keeping only those with non-blank text,
     * and converts them to {@link RetrievedDoc}s ready for graph-extraction RPC calls.
     *
     * <p>Empty or whitespace-only documents cannot yield any entities or relationships and would
     * cause the entire RPC batch to fail (a single un-constructable {@link RetrievedDoc} aborts
     * Jackson's {@code convertValue} for the whole list). Filtering them here at the graph-
     * extraction boundary is safe: the chunks have already been recorded as crawled documents.
     * A WARN is emitted so operators know real content was skipped, not silently lost.</p>
     *
     * @param docs  raw documents from the chunker output
     * @param jobId job identifier for the log message (may be null)
     * @return list of {@link RetrievedDoc}s, never containing a null-text entry
     */
    static List<RetrievedDoc> filterAndConvertDocs(List<Document> docs, String jobId) {
        if (docs == null || docs.isEmpty()) return List.of();
        List<RetrievedDoc> result = new ArrayList<>(docs.size());
        List<String> skippedIds = null;
        for (Document doc : docs) {
            if (!hasExtractableText(doc)) {
                if (skippedIds == null) skippedIds = new ArrayList<>();
                skippedIds.add(doc != null && doc.getId() != null ? doc.getId() : "(null)");
            } else {
                result.add(toRetrievedDoc(doc));
            }
        }
        if (skippedIds != null) {
            log.warn("[Job {}] Skipping {} blank/empty chunk(s) before graph extraction (no text, no media — cannot yield entities): ids={}",
                    jobId != null ? jobId : "?", skippedIds.size(), skippedIds);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // In-memory graph release
    // -------------------------------------------------------------------------

    void releaseInMemoryGraph(Graph graph) {
        if (graph == null) {
            return;
        }
        if (graph.getEntities() != null) {
            graph.getEntities().clear();
        }
        if (graph.getRelationships() != null) {
            graph.getRelationships().clear();
        }
        if (graph.getCommunities() != null) {
            graph.getCommunities().clear();
        }
        if (graph.getMetadata() != null) {
            graph.getMetadata().clear();
        }
    }

    // -------------------------------------------------------------------------
    // Cost estimation and batch planning
    // -------------------------------------------------------------------------

    private long estimateTextCost(String text, Map<String, Object> metadata) {
        long cost = text != null ? Math.max(1, text.length()) : 1;
        if (metadata == null) {
            return cost;
        }
        if (Boolean.TRUE.equals(metadata.get(GraphConstants.META_VLM_PROCESSED))) {
            cost = Math.round(cost * 1.5);
        }
        Object contentType = metadata.get(GraphConstants.META_CONTENT_TYPE);
        if (contentType instanceof String type) {
            String normalized = type.toLowerCase(Locale.ROOT);
            if (normalized.contains("table") || normalized.contains("vlm")) {
                cost = Math.round(cost * 1.3);
            } else if (normalized.contains("html")) {
                cost = Math.round(cost * 1.15);
            }
        }
        return Math.max(1, cost);
    }

    private long estimateDocumentCost(Document document) {
        return estimateTextCost(document != null ? document.getText() : null,
                document != null ? document.getMetadata() : null);
    }

    private <T> List<CostBatch<T>> planCostBatches(List<T> items,
                                                    Function<T, Long> costEstimator,
                                                    int maxItemsPerBatch,
                                                    long targetCostPerBatch,
                                                    boolean balanceByCost) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        int maxItems = Math.max(1, maxItemsPerBatch);
        long targetCost = Math.max(0, targetCostPerBatch);
        List<CostItem<T>> costItems = new ArrayList<>(items.size());
        for (T item : items) {
            long cost = 1L;
            try {
                Long estimated = costEstimator.apply(item);
                if (estimated != null) {
                    cost = Math.max(1L, estimated);
                }
            } catch (Exception ignored) {
                cost = 1L;
            }
            costItems.add(new CostItem<>(item, cost));
        }

        if (!balanceByCost) {
            List<CostBatch<T>> batches = new ArrayList<>();
            List<T> current = new ArrayList<>();
            long currentCost = 0L;
            for (CostItem<T> costItem : costItems) {
                boolean overItemLimit = current.size() >= maxItems;
                boolean overCostLimit = targetCost > 0 && !current.isEmpty()
                        && currentCost + costItem.cost() > targetCost;
                if (overItemLimit || overCostLimit) {
                    batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(current), currentCost));
                    current.clear();
                    currentCost = 0L;
                }
                current.add(costItem.item());
                currentCost += costItem.cost();
            }
            if (!current.isEmpty()) {
                batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(current), currentCost));
            }
            return batches;
        }

        costItems.sort((a, b) -> Long.compare(b.cost(), a.cost()));

        // Use a min-heap by cost to find the lightest batch in O(log B) instead of O(B)
        PriorityQueue<MutableCostBatch<T>> batchHeap = new PriorityQueue<>(
                Comparator.comparingLong(b -> b.cost));
        for (CostItem<T> costItem : costItems) {
            MutableCostBatch<T> best = null;
            if (!batchHeap.isEmpty()) {
                MutableCostBatch<T> lightest = batchHeap.peek();
                if (lightest.items.size() < maxItems) {
                    long newCost = lightest.cost + costItem.cost();
                    if (targetCost <= 0 || lightest.items.isEmpty() || newCost <= targetCost) {
                        best = batchHeap.poll();
                    }
                }
            }
            if (best == null) {
                best = new MutableCostBatch<>();
            }
            best.add(costItem.item(), costItem.cost());
            batchHeap.offer(best);
        }

        List<CostBatch<T>> batches = new ArrayList<>(batchHeap.size());
        for (MutableCostBatch<T> batch : batchHeap) {
            batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(batch.items), batch.cost));
        }
        return batches;
    }

    // -------------------------------------------------------------------------
    // Progress update helpers
    // -------------------------------------------------------------------------

    private void updateProgress(UnifiedCrawlJob job, String phase, int progressPercent,
                                String message, String details) {
        job.getCurrentPhase().set(phase);
        int boundedProgress = Math.max(progressPercent, estimateProgressForPhase(job, phase));
        boundedProgress = Math.max(0, Math.min(100, boundedProgress));
        job.getProgressPercent().accumulateAndGet(boundedProgress, Math::max);
        memoryMonitor.updateMemorySnapshot(job);
        // Throttle expensive event recording + pipeline step updates to at most once per 250ms.
        // Atomic counters above are always updated — they're cheap.
        long now = System.nanoTime();
        if ((now - lastProgressEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS) {
            lastProgressEventNanos = now;
            if (message != null && !message.isBlank()) {
                documentTracker.recordEvent(job, phase, "INFO", message, details);
            }
            pipelineStepTracker.updatePipelineStepFromCounters(job, phase, message, details);
            // Notify the SSE controller so the front-end polls the REST snapshot (which carries
            // adaptiveBatchSize, recentTuningDecisions, etc.) — makes adaptive batch telemetry
            // visible in the crawl-step-monitor during extraction, not only after it completes.
            Consumer<UnifiedCrawlJob> notifier = progressNotifier;
            if (notifier != null) {
                try { notifier.accept(job); } catch (Exception ignored) {}
            }
        }
    }

    private int estimateProgress(UnifiedCrawlJob job) {
        UnifiedCrawlJob.Status status = job.getStatus().get();
        if (status == UnifiedCrawlJob.Status.COMPLETED) return 100;
        if (status == UnifiedCrawlJob.Status.FAILED || status == UnifiedCrawlJob.Status.CANCELLED) {
            return job.getProgressPercent().get();
        }
        return estimateProgressForPhase(job, job.getCurrentPhase().get());
    }

    private int estimateProgressForPhase(UnifiedCrawlJob job, String phase) {
        if (phase == null || phase.equals("QUEUED")) return 0;
        if (phase.equals("DISCOVERING") || phase.equals("LOADING")) {
            List<UnifiedCrawlJob.SourceProgress> sourceProgress = job.getSourceProgress();
            int totalSources = sourceProgress != null ? sourceProgress.size() : 0;
            if (totalSources > 0) {
                int done = 0;
                for (UnifiedCrawlJob.SourceProgress sp : sourceProgress) {
                    UnifiedCrawlJob.Status s = sp.getStatus();
                    if (s == UnifiedCrawlJob.Status.COMPLETED || s == UnifiedCrawlJob.Status.FAILED) {
                        done++;
                    }
                }
                return 2 + (int) Math.min(18, (done * 18L) / totalSources);
            }
            return 5;
        }
        if (phase.equals("CONVERTING")) return 22;
        if (phase.equals("ROUTING") || phase.equals("GRAPH_PREP")) return 28;
        if (phase.equals("CHUNKING")) return 35;
        if (phase.equals("GRAPH_EXTRACTION")) {
            int total = graphChunksTotal(job);
            int done = normalizeGraphChunksProcessed(job);
            return total > 0 ? 40 + (int) Math.min(30, (done * 30L) / total) : 40;
        }
        if (phase.equals("SURFACING")) return 71;
        if (phase.equals("ENTITY_RESOLUTION")) {
            UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, phase);
            int total = step.getTotalItems().get();
            int done = step.getCompletedItems().get();
            return total > 0 ? 72 + (int) Math.min(8, (done * 8L) / total) : 72;
        }
        if (phase.equals("EDGE_COMPUTATION")) return 82;
        if (phase.equals("EMBEDDING") || phase.equals("INDEXING") || phase.equals("VECTOR_INDEXING")) {
            int total = job.getChunksQueuedForEmbedding().get();
            int done = Math.max(job.getChunksEmbedded().get(), job.getDocumentsIndexed().get());
            return total > 0 ? 85 + (int) Math.min(14, (done * 14L) / total) : 85;
        }
        if (phase.equals("ENRICHMENT")) return 99;
        return Math.max(1, job.getProgressPercent().get());
    }

    // -------------------------------------------------------------------------
    // Inline private helpers
    // -------------------------------------------------------------------------

    /**
     * Records a transcript for an inline graph-extraction LLM call, but ONLY when the
     * dispatcher has no transcript logger of its own.
     *
     * <p>When {@link CrawlLlmDispatcher#hasTranscriptLogger()} returns {@code true} the
     * dispatcher's own {@code recordLlmCall} already persisted an entry for this call — we must
     * NOT add another one (double entries confuse the transcript viewer and skew counts).
     * When the dispatcher's logger is absent we are the only recording path and must fire.</p>
     *
     * <p>The {@code agentSessionId} is read from {@link AgentCallContext} on the calling thread.
     * For the fast (no-route-config) path this is valid because {@link CrawlLlmDispatcher#callLlmWithTimeout}
     * bridges the session id captured on the timeout-executor thread back to the caller before
     * returning.  For the CLI_AGENT backend path the session id is set inside
     * {@code promptViaCli}/{@code dispatchToBackendWithTimeout} and therefore also available on
     * the calling thread by the time this method runs.</p>
     *
     * @param jobId       crawl job identifier
     * @param prompt      full prompt text sent to the LLM
     * @param response    response text received, or {@code null} on failure
     * @param latencyMs   wall-clock duration of the {@code promptWithCapacityFallback} call in ms
     * @param success     {@code true} when a non-blank response was received
     * @param errorMsg    short error description on failure, or {@code null}
     */
    private void recordInlineTranscriptIfNeeded(String jobId,
                                                 String prompt,
                                                 String response,
                                                 long latencyMs,
                                                 boolean success,
                                                 String errorMsg) {
        if (transcriptLogger == null) {
            return;
        }
        // Guard: dispatcher already recorded — do not add a duplicate entry.
        if (llmDispatcher.hasTranscriptLogger()) {
            return;
        }
        try {
            transcriptLogger.logTranscript(
                    jobId,
                    "llm-chat",   // backendId: consistent with the configured extraction provider
                    "llm",        // taskType: same label used in promptWithCapacityFallback calls
                    prompt,
                    response,
                    latencyMs,
                    success,
                    errorMsg,
                    AgentCallContext.getSessionId());
        } catch (Exception ex) {
            log.debug("Failed to persist inline LLM transcript for job {}: {}", jobId, ex.getMessage());
        }
    }

    private boolean isCancelled(UnifiedCrawlJob job) {
        return job != null && job.isCancellationRequested();
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }

    private boolean isFatalLlmUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("all cli agents failed")
                        || lower.contains("terminalquotaerror")
                        || lower.contains("insufficient_quota")
                        || lower.contains("you've hit your limit")
                        || lower.contains("you have hit your limit")
                        || lower.contains("you've hit your usage limit")
                        || lower.contains("you have hit your usage limit")
                        || lower.contains("you have exhausted your capacity")
                        || lower.contains("rate limit reached")
                        || lower.contains("rate limit exceeded")
                        || lower.contains("quota exceeded")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void cancelGraphFutures(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inner types — batch planning data structures
    // -------------------------------------------------------------------------

    // Package-private (not private) so the wave-planner unit test can assert batch contents.
    record CostBatch<T>(int index, List<T> items, long cost) {
    }

    private record CostItem<T>(T item, long cost) {
    }

    private record GraphBatchResult(CostBatch<RetrievedDoc> batch, Graph graph, Throwable error) {
    }

    private static class MutableCostBatch<T> {
        private final List<T> items = new ArrayList<>();
        private long cost;

        void add(T item, long itemCost) {
            items.add(item);
            cost += itemCost;
        }
    }

    // -------------------------------------------------------------------------
    // OuterParallelismAdvisor — adaptive concurrency control for graph batches
    // -------------------------------------------------------------------------

    static class OuterParallelismAdvisor {
        private volatile int currentParallelism;
        private final int maxParallelism;
        private final Semaphore concurrencyGate;
        private int consecutiveLowMemoryBatches;
        private long lastRampTime;
        private static final long RAMP_COOLDOWN_MS = 12_000;
        // Thresholds calibrated for remote CLI backends (opencode/deepseek) where the JVM heap
        // is NOT the bottleneck — the subprocess does all inference. Normal post-startup JVM heap
        // sits at 70-82%, which is NOT a signal of extraction pressure. Only trip the critical
        // reduction at ≥ 92% (genuine GC stall territory) and hold at ≥ 87% (near-critical).
        // For local GPU inference these are still safe: if GPU OOM were happening the JVM heap
        // would spike much higher (native memory pressure shows up as heap pressure via GC).
        private static final double CRITICAL_THRESHOLD = 0.92;
        private static final double HIGH_THRESHOLD = 0.87;
        private static final double LOW_THRESHOLD = 0.80;
        private static final int RAMP_AFTER_LOW_COUNT = 2;

        OuterParallelismAdvisor(int initialParallelism) {
            this.maxParallelism = Math.max(1, initialParallelism);
            this.currentParallelism = this.maxParallelism;
            this.concurrencyGate = new Semaphore(this.maxParallelism);
            this.lastRampTime = System.currentTimeMillis();
        }

        /**
         * Acquire a permit before submitting a batch. Blocks if the advisor
         * has reduced parallelism and all permits are in use.
         */
        void acquirePermit() throws InterruptedException {
            concurrencyGate.acquire();
        }

        /**
         * Release a permit after a batch completes (call from task finally block).
         */
        void releasePermit() {
            concurrencyGate.release();
        }

        /**
         * Adjust parallelism based on heap pressure after a batch completes. Returns a
         * {@link UnifiedCrawlJob.TuningDecision} describing the change (so the caller can record it
         * in the job's tuning history), or {@code null} when parallelism was left unchanged.
         */
        synchronized UnifiedCrawlJob.TuningDecision afterBatchComplete(long batchMs, double heapPercent) {
            int oldParallelism = currentParallelism;
            int memPct = (int) Math.round(heapPercent * 100);

            if (heapPercent > CRITICAL_THRESHOLD) {
                currentParallelism = 1;
                consecutiveLowMemoryBatches = 0;
                if (oldParallelism != 1) {
                    // Drain excess permits so only 1 batch can run concurrently
                    drainPermits(oldParallelism, 1);
                    log.info("Outer graph parallelism reduced {} -> 1 (reason: heap {}% > critical {}%)",
                            oldParallelism, memPct, Math.round(CRITICAL_THRESHOLD * 100));
                    return UnifiedCrawlJob.TuningDecision.builder()
                            .timestamp(Instant.now())
                            .stage("GRAPH_PARALLELISM")
                            .oldValue(oldParallelism)
                            .newValue(1)
                            .direction("DOWN")
                            .reason("heap_critical")
                            .detail("heap " + memPct + "% > critical " + Math.round(CRITICAL_THRESHOLD * 100) + "%")
                            .memoryPercent(memPct)
                            .build();
                }
            } else if (heapPercent > HIGH_THRESHOLD) {
                consecutiveLowMemoryBatches = 0;
                // Hold at current level
            } else if (heapPercent < LOW_THRESHOLD) {
                consecutiveLowMemoryBatches++;
                long now = System.currentTimeMillis();
                if (consecutiveLowMemoryBatches >= RAMP_AFTER_LOW_COUNT
                        && now - lastRampTime >= RAMP_COOLDOWN_MS
                        && currentParallelism < maxParallelism) {
                    int newParallelism = Math.min(maxParallelism, currentParallelism + 1);
                    // Release additional permits to allow more concurrency
                    concurrencyGate.release(newParallelism - currentParallelism);
                    currentParallelism = newParallelism;
                    lastRampTime = now;
                    consecutiveLowMemoryBatches = 0;
                    log.info("Outer graph parallelism increased {} -> {} (reason: {} consecutive low-memory batches, heap {}%)",
                            oldParallelism, currentParallelism, RAMP_AFTER_LOW_COUNT, memPct);
                    return UnifiedCrawlJob.TuningDecision.builder()
                            .timestamp(Instant.now())
                            .stage("GRAPH_PARALLELISM")
                            .oldValue(oldParallelism)
                            .newValue(currentParallelism)
                            .direction("UP")
                            .reason("heap_recovered")
                            .detail(RAMP_AFTER_LOW_COUNT + " low-memory batches, heap " + memPct + "%")
                            .memoryPercent(memPct)
                            .build();
                }
            } else {
                consecutiveLowMemoryBatches = 0;
            }
            return null;
        }

        private void drainPermits(int from, int to) {
            int toDrain = from - to;
            for (int d = 0; d < toDrain; d++) {
                if (!concurrencyGate.tryAcquire()) break;
            }
        }

        int getCurrentParallelism() { return currentParallelism; }
    }
}
