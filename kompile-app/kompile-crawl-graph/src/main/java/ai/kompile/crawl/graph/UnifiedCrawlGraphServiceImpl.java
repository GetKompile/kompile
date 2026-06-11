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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawler.*;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.crawler.CrawlerService;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.EntityMention;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of {@link UnifiedCrawlService} that orchestrates
 * multi-source crawling with automatic graph extraction and vector indexing.
 *
 * <p>Pipeline flow per source:</p>
 * <ol>
 *   <li>Crawl/load documents from each source (using CrawlerService or DocumentLoader)</li>
 *   <li>Chunk documents for processing</li>
 *   <li>Extract graph entities and relationships from each chunk via LLM</li>
 *   <li>Merge entities across sources (entity resolution)</li>
 *   <li>Index chunks to vector store</li>
 *   <li>Assemble final unified graph</li>
 * </ol>
 */
@Service
public class UnifiedCrawlGraphServiceImpl implements UnifiedCrawlService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedCrawlGraphServiceImpl.class);

    // Text normalization patterns (same as TextConversionService in kompile-app-main)
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern TAB_TO_SPACE = Pattern.compile("\t");
    private static final Pattern CARRIAGE_RETURN = Pattern.compile("\r\n?");
    private static final Pattern FORM_FEED = Pattern.compile("\f");
    private static final Pattern NULL_CHARS = Pattern.compile("\u0000");
    private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile("[\u200B-\u200D\uFEFF]");
    private static final Pattern BINARY_INDICATOR = Pattern.compile("(?i)\\[?(?:binary|image|figure|table|chart|graph)(?:\\s*\\d+)?\\]?");
    private static final Pattern PAGE_HEADER_FOOTER = Pattern.compile("(?m)^\\s*(?:Page\\s+\\d+|\\d+\\s*of\\s*\\d+|^-\\s*\\d+\\s*-)\\s*$");
    private static final Pattern ENTITY_SUFFIX_PATTERN = Pattern.compile(
            "\\b(Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|LLC|Co\\.?|Company|Group|Plc\\.?)$",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_RECENT_EVENTS = 120;
    private static final int DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE = 10;
    private static final int MIN_EMBEDDING_BATCH_SIZE = 4;
    private static final String GRAPH_EXTRACTION_CONFIG_FILENAME = "graph-extraction-config.json";
    private static final com.fasterxml.jackson.databind.ObjectMapper EDGE_METADATA_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ConcurrentHashMap<String, UnifiedCrawlJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> jobFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> queuedSequences = new ConcurrentHashMap<>();
    private final Set<String> runningJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong submitSequence = new AtomicLong(0L);
    private final ObjectMapper configObjectMapper = new ObjectMapper();
    private final Path graphExtractionConfigPath =
            KompileHome.dataDir().toPath().resolve("config").resolve(GRAPH_EXTRACTION_CONFIG_FILENAME);
    private volatile long graphExtractionConfigLastModified = Long.MIN_VALUE;
    private volatile CrawlRuntimeConfig crawlRuntimeConfig = CrawlRuntimeConfig.defaults();
    private ThreadPoolExecutor executor;
    private int executorQueueCapacity = -1;

    private int maxConcurrentJobs = 1;

    private int queueCapacity = 25;

    private int memoryWaitThresholdPercent = 82;

    private int memoryCriticalThresholdPercent = 90;

    private int memoryWaitTimeoutSeconds = 300;

    private boolean nativeMemoryCleanupEnabled = true;

    private int nativeMemoryCleanupPasses = 3;

    private int nativeMemoryWaitThresholdPercent = 82;

    private int nativeMemoryCriticalThresholdPercent = 90;

    private int graphExtractionBatchSize = DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;

    private int backgroundGraphThreads = 2;

    private int sourceLoadParallelism = 2;

    private int chunkingParallelism = 2;

    private int graphExtractionParallelism = 16;

    private int graphExtractionTargetCharsPerBatch = 48_000;

    private int chunkingTargetCharsPerTask = 200_000;

    private int configuredVectorBatchSize = 0;

    private boolean postProcessParallel = false;

    private boolean graphConstructorSkipEmbedding = true;

    private boolean graphConstructorPersistMatrixGraph = false;

    private boolean retainResultGraph = false;

    private boolean costSortChunks = true;

    private synchronized CrawlRuntimeConfig refreshRuntimeConfig() {
        try {
            CrawlRuntimeConfig config;
            if (!Files.exists(graphExtractionConfigPath)) {
                graphExtractionConfigLastModified = Long.MIN_VALUE;
                config = CrawlRuntimeConfig.defaults();
            } else {
                long lastModified = Files.getLastModifiedTime(graphExtractionConfigPath).toMillis();
                if (lastModified != graphExtractionConfigLastModified) {
                    JsonNode root = configObjectMapper.readTree(graphExtractionConfigPath.toFile());
                    crawlRuntimeConfig = CrawlRuntimeConfig.from(root);
                    graphExtractionConfigLastModified = lastModified;
                    log.info("Loaded unified crawl runtime config from {}", graphExtractionConfigPath);
                }
                config = crawlRuntimeConfig;
            }
            applyRuntimeConfig(config);
            return config;
        } catch (Exception e) {
            log.warn("Failed to read unified crawl runtime config from {}: {}",
                    graphExtractionConfigPath, e.getMessage());
            applyRuntimeConfig(crawlRuntimeConfig);
            return crawlRuntimeConfig;
        }
    }

    private void applyRuntimeConfig(CrawlRuntimeConfig config) {
        maxConcurrentJobs = config.maxConcurrentJobs;
        queueCapacity = config.queueCapacity;
        memoryWaitThresholdPercent = config.memoryWaitThresholdPercent;
        memoryCriticalThresholdPercent = config.memoryCriticalThresholdPercent;
        memoryWaitTimeoutSeconds = config.memoryWaitTimeoutSeconds;
        nativeMemoryCleanupEnabled = config.nativeMemoryCleanupEnabled;
        nativeMemoryCleanupPasses = config.nativeMemoryCleanupPasses;
        nativeMemoryWaitThresholdPercent = config.nativeMemoryWaitThresholdPercent;
        nativeMemoryCriticalThresholdPercent = config.nativeMemoryCriticalThresholdPercent;
        graphExtractionBatchSize = config.graphExtractionBatchSize;
        backgroundGraphThreads = config.backgroundGraphThreads;
        sourceLoadParallelism = config.sourceLoadParallelism;
        chunkingParallelism = config.chunkingParallelism;
        graphExtractionParallelism = config.graphExtractionParallelism;
        graphExtractionTargetCharsPerBatch = config.graphExtractionTargetCharsPerBatch;
        chunkingTargetCharsPerTask = config.chunkingTargetCharsPerTask;
        configuredVectorBatchSize = config.vectorBatchSize;
        postProcessParallel = config.postProcessParallel;
        graphConstructorSkipEmbedding = config.graphConstructorSkipEmbedding;
        graphConstructorPersistMatrixGraph = config.graphConstructorPersistMatrixGraph;
        retainResultGraph = config.retainResultGraph;
        costSortChunks = config.costSortChunks;

        if (executor != null && !executor.isShutdown()) {
            int threads = Math.max(1, maxConcurrentJobs);
            if (threads > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(threads);
                executor.setCorePoolSize(threads);
            } else {
                executor.setCorePoolSize(threads);
                executor.setMaximumPoolSize(threads);
            }

            int capacity = Math.max(1, queueCapacity);
            if (executorQueueCapacity != capacity
                    && executor.getActiveCount() == 0
                    && executor.getQueue().isEmpty()) {
                executor.shutdown();
                executor = null;
                executorQueueCapacity = -1;
            }
        }
    }

    private void applyRequestOverrides(UnifiedCrawlRequest.RuntimeConfig overrides) {
        if (overrides == null) return;
        if (overrides.getGraphExtractionParallelism() != null) {
            graphExtractionParallelism = Math.max(1, Math.min(32, overrides.getGraphExtractionParallelism()));
        }
        if (overrides.getGraphExtractionBatchSize() != null) {
            graphExtractionBatchSize = Math.max(1, Math.min(128, overrides.getGraphExtractionBatchSize()));
        }
        if (overrides.getGraphExtractionTargetCharsPerBatch() != null) {
            graphExtractionTargetCharsPerBatch = Math.max(1000, Math.min(500000, overrides.getGraphExtractionTargetCharsPerBatch()));
        }
        if (overrides.getSourceLoadParallelism() != null) {
            sourceLoadParallelism = Math.max(1, Math.min(32, overrides.getSourceLoadParallelism()));
        }
        if (overrides.getChunkingParallelism() != null) {
            chunkingParallelism = Math.max(1, Math.min(32, overrides.getChunkingParallelism()));
        }
        if (overrides.getVectorBatchSize() != null) {
            configuredVectorBatchSize = Math.max(0, Math.min(4096, overrides.getVectorBatchSize()));
        }
        if (overrides.getCostSortChunks() != null) {
            costSortChunks = overrides.getCostSortChunks();
        }
    }

    private static class CrawlRuntimeConfig {
        private int maxConcurrentJobs = 1;
        private int queueCapacity = 25;
        private int memoryWaitThresholdPercent = 82;
        private int memoryCriticalThresholdPercent = 90;
        private int memoryWaitTimeoutSeconds = 300;
        private boolean nativeMemoryCleanupEnabled = true;
        private int nativeMemoryCleanupPasses = 3;
        private int nativeMemoryWaitThresholdPercent = 82;
        private int nativeMemoryCriticalThresholdPercent = 90;
        private int graphExtractionBatchSize = DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
        private int backgroundGraphThreads = 2;
        private int sourceLoadParallelism = 2;
        private int chunkingParallelism = 2;
        private int graphExtractionParallelism = 16;
        private int graphExtractionTargetCharsPerBatch = 48_000;
        private int chunkingTargetCharsPerTask = 200_000;
        private int vectorBatchSize = 0;
        private boolean postProcessParallel = false;
        private boolean graphConstructorSkipEmbedding = true;
        private boolean graphConstructorPersistMatrixGraph = false;
        private boolean retainResultGraph = false;
        private boolean costSortChunks = true;

        static CrawlRuntimeConfig defaults() {
            return new CrawlRuntimeConfig();
        }

        static CrawlRuntimeConfig from(JsonNode root) {
            CrawlRuntimeConfig config = defaults();
            if (root == null) {
                return config;
            }
            config.maxConcurrentJobs = intField(root, "crawlMaxConcurrentJobs", config.maxConcurrentJobs, 1, 16);
            config.queueCapacity = intField(root, "crawlQueueCapacity", config.queueCapacity, 1, 500);
            config.memoryWaitThresholdPercent = intField(root, "crawlMemoryWaitThresholdPercent", config.memoryWaitThresholdPercent, 1, 99);
            config.memoryCriticalThresholdPercent = intField(root, "crawlMemoryCriticalThresholdPercent", config.memoryCriticalThresholdPercent, 1, 100);
            config.memoryWaitTimeoutSeconds = intField(root, "crawlMemoryWaitTimeoutSeconds", config.memoryWaitTimeoutSeconds, 1, 7200);
            config.nativeMemoryCleanupEnabled = boolField(root, "crawlNativeMemoryCleanupEnabled", config.nativeMemoryCleanupEnabled);
            config.nativeMemoryCleanupPasses = intField(root, "crawlNativeMemoryCleanupPasses", config.nativeMemoryCleanupPasses, 1, 10);
            config.nativeMemoryWaitThresholdPercent = intField(root, "crawlNativeMemoryWaitThresholdPercent", config.nativeMemoryWaitThresholdPercent, 1, 99);
            config.nativeMemoryCriticalThresholdPercent = intField(root, "crawlNativeMemoryCriticalThresholdPercent", config.nativeMemoryCriticalThresholdPercent, 1, 100);
            config.graphExtractionBatchSize = intField(root, "crawlGraphExtractionBatchSize", config.graphExtractionBatchSize, 1, 128);
            config.backgroundGraphThreads = intField(root, "crawlBackgroundGraphThreads", config.backgroundGraphThreads, 1, 16);
            config.sourceLoadParallelism = intField(root, "crawlSourceLoadParallelism", config.sourceLoadParallelism, 1, 32);
            config.chunkingParallelism = intField(root, "crawlChunkingParallelism", config.chunkingParallelism, 1, 32);
            config.graphExtractionParallelism = intField(root, "crawlGraphExtractionParallelism", config.graphExtractionParallelism, 1, 32);
            config.graphExtractionTargetCharsPerBatch = intField(root, "crawlGraphExtractionTargetCharsPerBatch", config.graphExtractionTargetCharsPerBatch, 1000, 500000);
            config.chunkingTargetCharsPerTask = intField(root, "crawlChunkingTargetCharsPerTask", config.chunkingTargetCharsPerTask, 1000, 2000000);
            config.vectorBatchSize = intField(root, "crawlVectorBatchSize", config.vectorBatchSize, 0, 4096);
            config.postProcessParallel = boolField(root, "crawlPostProcessParallel", config.postProcessParallel);
            config.graphConstructorSkipEmbedding = boolField(root, "crawlGraphConstructorSkipEmbedding", config.graphConstructorSkipEmbedding);
            config.graphConstructorPersistMatrixGraph = boolField(root, "crawlGraphConstructorPersistMatrixGraph", config.graphConstructorPersistMatrixGraph);
            config.retainResultGraph = boolField(root, "crawlRetainResultGraph", config.retainResultGraph);
            config.costSortChunks = boolField(root, "crawlCostSortChunks", config.costSortChunks);
            if (config.memoryCriticalThresholdPercent < config.memoryWaitThresholdPercent) {
                config.memoryCriticalThresholdPercent = config.memoryWaitThresholdPercent;
            }
            if (config.nativeMemoryCriticalThresholdPercent < config.nativeMemoryWaitThresholdPercent) {
                config.nativeMemoryCriticalThresholdPercent = config.nativeMemoryWaitThresholdPercent;
            }
            return config;
        }

        private static int intField(JsonNode root, String name, int fallback, int min, int max) {
            JsonNode node = root.get(name);
            if (node == null || !node.canConvertToInt()) {
                return fallback;
            }
            return Math.max(min, Math.min(max, node.asInt()));
        }

        private static boolean boolField(JsonNode root, String name, boolean fallback) {
            JsonNode node = root.get(name);
            return node != null && node.isBoolean() ? node.asBoolean() : fallback;
        }
    }

    @Autowired(required = false)
    private CrawlerService crawlerService;

    @Autowired(required = false)
    private List<DocumentLoader> documentLoaders;

    @Autowired(required = false)
    private GraphConstructor graphConstructor;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private CrossDocumentRelationCallback crossDocumentRelationCallback;

    @Autowired(required = false)
    private List<ai.kompile.core.graphrag.DocumentGraphExtractor> documentGraphExtractors;

    @Autowired(required = false)
    private List<TextChunker> textChunkers;

    @Autowired(required = false)
    private ai.kompile.knowledgegraph.service.GraphEdgeComputationService graphEdgeComputationService;

    @Autowired(required = false)
    private ai.kompile.knowledgegraph.resolution.GraphCompactionService graphCompactionService;

    @Autowired(required = false)
    private EntityMentionRepository entityMentionRepository;

    @Autowired(required = false)
    private CrawlFactRegistrationCallback crawlFactRegistrationCallback;

    @Autowired(required = false)
    private CrawlIndexTrackingCallback crawlIndexTrackingCallback;

    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private ai.kompile.core.loaders.PdfContentClassifier pdfContentClassifier;

    @Autowired(required = false)
    private ProcessingCapacityTracker processingCapacityTracker;

    @PostConstruct
    public synchronized void initializeExecutor() {
        refreshRuntimeConfig();
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        int threads = Math.max(1, maxConcurrentJobs);
        int capacity = Math.max(1, queueCapacity);
        executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(capacity),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("unified-crawl-" + t.getId());
                    t.setUncaughtExceptionHandler((thread, ex) ->
                            log.error("Uncaught error in thread {}: {}", thread.getName(), ex.getMessage(), ex));
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executorQueueCapacity = capacity;
        log.info("Unified crawl executor initialized: maxConcurrentJobs={}, queueCapacity={}, memoryWait={}%, memoryCritical={}%",
                threads, capacity, memoryWaitThresholdPercent, memoryCriticalThresholdPercent);
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public UnifiedCrawlJob startJob(UnifiedCrawlRequest request) {
        refreshRuntimeConfig();
        applyRequestOverrides(request.getRuntimeConfig());
        if (request.getSources() == null || request.getSources().isEmpty()) {
            throw new IllegalArgumentException("At least one source is required");
        }

        String jobId = UUID.randomUUID().toString();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .jobId(jobId)
                .request(request)
                .status(new AtomicReference<>(UnifiedCrawlJob.Status.PENDING))
                .createdAt(Instant.now())
                .queuedAt(Instant.now())
                .build();
        job.getMaxConcurrentJobs().set(Math.max(1, maxConcurrentJobs));
        job.getQueueCapacity().set(Math.max(1, queueCapacity));
        job.getCurrentPhase().set("QUEUED");
        recordEvent(job, "QUEUED", "INFO", "Queued unified crawl job",
                request.getSources().size() + " source(s)");

        // Initialize per-source progress
        List<UnifiedCrawlJob.SourceProgress> sourceProgressList = new ArrayList<>();
        for (UnifiedCrawlSource source : request.getSources()) {
            sourceProgressList.add(UnifiedCrawlJob.SourceProgress.builder()
                    .label(source.getLabel())
                    .sourceType(source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN")
                    .pathOrUrl(source.getPathOrUrl())
                    .status(UnifiedCrawlJob.Status.PENDING)
                    .build());
        }
        job.setSourceProgress(sourceProgressList);
        initializePipelineSteps(job);
        jobs.put(jobId, job);

        long sequence = submitSequence.incrementAndGet();
        queuedSequences.put(jobId, sequence);
        updateQueueSnapshots();

        try {
            Future<?> future = executor().submit(() -> runQueuedJob(job));
            jobFutures.put(jobId, future);
            updateQueueSnapshots();
        } catch (RejectedExecutionException e) {
            queuedSequences.remove(jobId);
            job.getStatus().set(UnifiedCrawlJob.Status.FAILED);
            job.setErrorMessage("Unified crawl queue is full");
            job.setCompletedAt(Instant.now());
            recordEvent(job, "QUEUED", "ERROR", "Unified crawl queue is full",
                    "queueCapacity=" + queueCapacity + ", activeJobs=" + runningJobIds.size());
            updateQueueSnapshots();
            throw new IllegalStateException("Unified crawl queue is full; try again after running jobs finish");
        }

        log.info("Queued unified crawl job {} with {} sources", jobId, request.getSources().size());
        return job;
    }

    private ThreadPoolExecutor executor() {
        if (executor == null || executor.isShutdown()) {
            initializeExecutor();
        } else {
            refreshRuntimeConfig();
        }
        return executor;
    }

    private void runQueuedJob(UnifiedCrawlJob job) {
        String jobId = job.getJobId();
        queuedSequences.remove(jobId);
        runningJobIds.add(jobId);
        updateQueueSnapshots();
        try {
            if (isCancelled(job)) {
                return;
            }
            executeJob(job);
        } finally {
            runningJobIds.remove(jobId);
            jobFutures.remove(jobId);
            updateQueueSnapshots();
        }
    }

    @Override
    public Optional<UnifiedCrawlJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<UnifiedCrawlJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public List<UnifiedCrawlJob> getActiveJobs() {
        return jobs.values().stream()
                .filter(j -> j.getStatus().get() == UnifiedCrawlJob.Status.PENDING
                        || j.getStatus().get() == UnifiedCrawlJob.Status.RUNNING)
                .collect(Collectors.toList());
    }

    @Override
    public boolean cancelJob(String jobId) {
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null) return false;
        UnifiedCrawlJob.Status current = job.getStatus().get();
        if (current == UnifiedCrawlJob.Status.COMPLETED
                || current == UnifiedCrawlJob.Status.FAILED
                || current == UnifiedCrawlJob.Status.CANCELLED) {
            return false;
        }
        job.getStatus().set(UnifiedCrawlJob.Status.CANCELLED);
        String cancelledPhase = job.getCurrentPhase().get();
        UnifiedCrawlJob.PipelineStepProgress cancelledStep = ensurePipelineStep(job, cancelledPhase);
        if (cancelledStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.RUNNING
                || cancelledStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE
                || cancelledStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.PENDING) {
            updatePipelineStep(job, cancelledPhase, UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                    cancelledStep.getCompletedItems().get(), cancelledStep.getTotalItems().get(),
                    cancelledStep.getFailedItems().get(), cancelledStep.getCompletedBatches().get(),
                    cancelledStep.getTotalBatches().get(), cancelledStep.getCurrentBatchSize().get(),
                    cancelledStep.getCurrentItem().get(), "Job cancelled");
        }
        job.getCurrentPhase().set("CANCELLED");
        job.getProgressPercent().set(Math.max(job.getProgressPercent().get(), 0));
        job.setCompletedAt(Instant.now());
        queuedSequences.remove(jobId);
        Future<?> future = jobFutures.get(jobId);
        if (future != null) {
            // The crawl workers poll the job cancellation flag. Interrupting them while
            // H2 is compacting or reading graph rows can close the MVStore file channel.
            future.cancel(false);
            if (current == UnifiedCrawlJob.Status.PENDING) {
                jobFutures.remove(jobId);
            }
        }
        recordEvent(job, "CANCELLED", "WARN", "Unified crawl job cancelled", null);
        updateQueueSnapshots();
        return true;
    }

    @Override
    public int cleanupJobs() {
        int removed = 0;
        Iterator<Map.Entry<String, UnifiedCrawlJob>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, UnifiedCrawlJob> entry = it.next();
            UnifiedCrawlJob.Status status = entry.getValue().getStatus().get();
            if (status == UnifiedCrawlJob.Status.COMPLETED
                    || status == UnifiedCrawlJob.Status.FAILED
                    || status == UnifiedCrawlJob.Status.CANCELLED) {
                String jobId = entry.getKey();
                queuedSequences.remove(jobId);
                Future<?> future = jobFutures.remove(jobId);
                if (future != null) {
                    future.cancel(false);
                }
                runningJobIds.remove(jobId);
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public List<AvailableSourceType> getAvailableSourceTypes() {
        List<AvailableSourceType> types = new ArrayList<>();

        addSourceType(types, DocumentSourceDescriptor.SourceType.DIRECTORY,
                "Local Directory", "Crawl a local filesystem directory",
                List.of("pathOrUrl"), List.of("includePatterns", "excludePatterns", "maxDepth", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.FILE,
                "Single File", "Load a single file",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.URL,
                "Web URL", "Load content from a single URL",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.WEB_CRAWL,
                "Web Crawl", "Recursively crawl a website starting from a seed URL",
                List.of("pathOrUrl"), List.of("maxDepth", "maxDocuments", "sameDomainOnly", "crawlerId"));

        addSourceType(types, DocumentSourceDescriptor.SourceType.EMAIL,
                "Email", "Load email content from configured mail sources",
                List.of("pathOrUrl"), List.of("host", "port", "username", "password", "folder", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.IMAP,
                "IMAP Inbox", "Crawl an email inbox via IMAP",
                List.of("pathOrUrl"), List.of("host", "port", "username", "password", "folder", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.POP3,
                "POP3 Inbox", "Crawl an email inbox via POP3",
                List.of("pathOrUrl"), List.of("host", "port", "username", "password", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.MBOX,
                "MBOX Archive", "Load an mbox email archive",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.MAILDIR,
                "Maildir Archive", "Load a Maildir email archive",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.EMLX_DIR,
                "Apple Mail Archive", "Load an Apple Mail .emlx directory",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.PST,
                "Outlook PST", "Load an Outlook PST archive",
                List.of("pathOrUrl"), List.of("crawlerId"));

        addSourceType(types, DocumentSourceDescriptor.SourceType.SLACK,
                "Slack", "Ingest messages from a Slack workspace",
                List.of("pathOrUrl"), List.of("token", "workspaceId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.SLACK_HISTORY,
                "Slack History", "Load exported Slack history",
                List.of("pathOrUrl"), List.of("token", "workspaceId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.DISCORD,
                "Discord", "Ingest messages from a Discord server",
                List.of("pathOrUrl"), List.of("botToken", "guildId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.DISCORD_HISTORY,
                "Discord History", "Load exported Discord message history",
                List.of("pathOrUrl"), List.of("botToken", "guildId", "channels", "crawlerId"));

        addSourceType(types, DocumentSourceDescriptor.SourceType.GMAIL,
                "Gmail", "Crawl Gmail messages via the Gmail API",
                List.of("pathOrUrl"), List.of("oauthToken", "query", "labels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GDOCS,
                "Google Docs", "Crawl Google Docs via the Google APIs",
                List.of("pathOrUrl"), List.of("oauthToken", "documentId", "folderId", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GDRIVE,
                "Google Drive", "Load files from Google Drive",
                List.of("pathOrUrl"), List.of("folderId", "oauthToken", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.ONEDRIVE,
                "OneDrive", "Load files from Microsoft OneDrive",
                List.of("pathOrUrl"), List.of("driveId", "folderId", "oauthToken", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE,
                "Google Workspace", "Crawl Gmail, Drive, Docs, and Calendar from Google Workspace",
                List.of("pathOrUrl"), List.of("oauthToken", "workspaceId", "includeGmail", "includeDrive", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.CONFLUENCE,
                "Confluence", "Load pages from Confluence",
                List.of("pathOrUrl"), List.of("spaceKey", "apiToken", "crawlerId"));

        return types;
    }

    // ---- Internal pipeline execution ----

    private void executeJob(UnifiedCrawlJob job) {
        try {
            refreshRuntimeConfig();
            job.getStatus().set(UnifiedCrawlJob.Status.RUNNING);
            job.setStartedAt(Instant.now());
            initializePipelineSteps(job);
            updateMemorySnapshot(job);
            updateProgress(job, "LOADING", 1, "Starting source loading",
                    job.getRequest().getSources().size() + " source(s)");

            // ═══════════════════════════════════════════════════════════════════
            // PIPELINED EXECUTION — overlaps cheap/rule-based work with expensive
            // LLM extraction.  The critical path is:
            //   load → text-convert → route → chunk → LLM extract → entity resolution
            // Everything else (email graph, doc graph, cross-doc edges, vector
            // indexing, edge computation) runs in parallel when possible.
            // ═══════════════════════════════════════════════════════════════════

            // Phase 1: Crawl/load documents from all sources
            Map<String, List<Document>> docsBySource = new LinkedHashMap<>();
            List<SourceLoadResult> sourceResults = loadSources(job);
            List<Document> allDocuments = new ArrayList<>();
            for (SourceLoadResult result : sourceResults) {
                if (result.documents() == null) {
                    continue;
                }
                docsBySource.put(result.label(), result.documents());
                allDocuments.addAll(result.documents());
            }
            int sourceCount = sourceResults.size();
            if (allDocuments.isEmpty() && job.getErrorCount().get() > 0) {
                String reason = summarizeSourceLoadErrors(job);
                failPipelineStep(job, "LOADING", reason);
                throw new IllegalStateException(reason);
            }
            completePipelineStep(job, "LOADING", sourceCount,
                    allDocuments.size() + " document(s) loaded from " + sourceCount + " source(s)");

            // Register crawled sources as facts in the target fact sheet
            registerCrawledSourcesAsFacts(job, sourceResults);

            sourceResults.clear();
            docsBySource.clear();

            if (isCancelled(job)) return;

            // Phase 2: Text conversion (fast, CPU-only)
            updatePipelineStep(job, "CONVERTING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, allDocuments.size(), 0, 0, 0, 0, null,
                    "Normalizing loaded document text");
            updateProgress(job, "CONVERTING", estimateProgress(job),
                    "Normalizing loaded document text", allDocuments.size() + " document(s)");
            waitForMemoryCapacity(job, "CONVERTING");
            allDocuments = new ArrayList<>(convertDocumentText(allDocuments, job));
            completePipelineStep(job, "CONVERTING", allDocuments.size(),
                    allDocuments.size() + " document(s) normalized");
            log.info("[Job {}] Text conversion complete: {} documents", job.getJobId(), allDocuments.size());

            if (isCancelled(job)) return;

            // Phase 2.5: Dynamic PDF classification and routing
            ProcessingRouteConfig routeConfig = resolveProcessingRouteConfig(job);
            if (routeConfig.getPdfRoutingMode() != ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
                allDocuments = classifyAndRoutePdfs(job, allDocuments, routeConfig);
            }

            if (isCancelled(job)) return;

            // Phase 3: Content-type routing (registers DOCUMENT nodes, formula graphs)
            final List<Document> docGraphDocs = copyDocumentsForBackgroundGraph(allDocuments);
            updatePipelineStep(job, "ROUTING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, allDocuments.size(), 0, 0, 0, 0, null,
                    "Routing content by document type");
            updateProgress(job, "ROUTING", estimateProgress(job),
                    "Routing content by document type", allDocuments.size() + " document(s)");
            waitForMemoryCapacity(job, "ROUTING");
            job.getCurrentFile().set("(formula graph extraction for " + allDocuments.size() + " documents)");
            allDocuments = new ArrayList<>(routeByContentType(job.getJobId(), allDocuments));
            job.getCurrentFile().set(null);
            completePipelineStep(job, "ROUTING", docGraphDocs.size(),
                    allDocuments.size() + " document(s) routed to text pipeline");
            updateProgress(job, "ROUTING", estimateProgress(job),
                    "Content routing complete", allDocuments.size() + " text-pipeline document(s)");
            log.info("[Job {}] Content routing complete: {} documents for text pipeline", job.getJobId(), allDocuments.size());

            if (isCancelled(job)) return;

            // ═══════════════════════════════════════════════════════════════════
            // PARALLEL FORK: start cheap rule-based graph work in background
            // while the critical path continues to chunking + LLM extraction.
            // ═══════════════════════════════════════════════════════════════════
            updateProgress(job, "GRAPH_PREP", estimateProgress(job),
                    "Starting rule-based graph preparation", null);
            int graphPrepTaskCount = crossDocumentRelationCallback != null && knowledgeGraphService != null ? 4 : 3;
            updatePipelineStep(job, "GRAPH_PREP", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, graphPrepTaskCount, 0, 0, 0, 0, null,
                    "Starting rule-based graph preparation");
            ExecutorService backgroundGraphPool = Executors.newFixedThreadPool(Math.max(1, backgroundGraphThreads), r -> {
                Thread t = new Thread(r, "unified-crawl-bg-" + job.getJobId().substring(0, 8));
                t.setDaemon(true);
                return t;
            });
            List<Future<?>> backgroundGraphFutures = new ArrayList<>();

            // Background task 1: Email graph extraction (rule-based, no LLM)
            final List<Document> emailDocs = copyDocumentsForBackgroundGraph(allDocuments);
            backgroundGraphFutures.add(backgroundGraphPool.submit(() -> {
                try {
                    if (isCancelled(job)) {
                        return;
                    }
                    log.info("[Job {}] [BG] Email graph extraction starting", job.getJobId());
                    applyEmailGraphExtraction(job.getJobId(), emailDocs);
                    if (isCancelled(job)) {
                        return;
                    }
                    incrementPipelineStep(job, "GRAPH_PREP", 1, 0, "Email graph extraction complete");
                    log.info("[Job {}] [BG] Email graph extraction complete", job.getJobId());
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                    if (!isCancelled(job)) {
                        failPipelineStep(job, "GRAPH_PREP", "Email graph extraction failed: " + errorDetail);
                    }
                    log.warn("[Job {}] [BG] Email graph extraction failed: {}", job.getJobId(), errorDetail, e);
                } finally {
                    int released = emailDocs.size();
                    emailDocs.clear();
                    if (!isCancelled(job)) {
                        recordEvent(job, "GRAPH_PREP", "INFO",
                                "Released email graph document batch", released + " document reference(s)");
                    }
                    trimNativeMemory(job, "GRAPH_PREP", "after releasing email graph documents");
                }
            }));

            // Background task 2: Document metadata graph extraction (rule-based)
            backgroundGraphFutures.add(backgroundGraphPool.submit(() -> {
                try {
                    if (isCancelled(job)) {
                        return;
                    }
                    log.info("[Job {}] [BG] Document graph extraction for {} documents",
                            job.getJobId(), docGraphDocs.size());
                    applyDocumentGraphExtraction(job.getJobId(), docGraphDocs);
                    if (isCancelled(job)) {
                        return;
                    }
                    incrementPipelineStep(job, "GRAPH_PREP", 1, 0, "Document graph extraction complete");
                    log.info("[Job {}] [BG] Document graph extraction complete", job.getJobId());
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                    if (!isCancelled(job)) {
                        failPipelineStep(job, "GRAPH_PREP", "Document graph extraction failed: " + errorDetail);
                    }
                    log.warn("[Job {}] [BG] Document graph extraction failed: {}", job.getJobId(), errorDetail, e);
                } finally {
                    int released = docGraphDocs.size();
                    docGraphDocs.clear();
                    if (!isCancelled(job)) {
                        recordEvent(job, "GRAPH_PREP", "INFO",
                                "Released document graph input batch", released + " document reference(s)");
                    }
                    trimNativeMemory(job, "GRAPH_PREP", "after releasing document graph inputs");
                }
            }));

            // Background task 3: Cross-document relation extraction
            if (crossDocumentRelationCallback != null && knowledgeGraphService != null) {
                backgroundGraphFutures.add(backgroundGraphPool.submit(() -> {
                    try {
                        if (isCancelled(job)) {
                            return;
                        }
                        Long factSheetId = jobFactSheetId(job);
                        log.info("[Job {}] [BG] Cross-document relation extraction starting (factSheetId={})",
                                job.getJobId(), factSheetId);
                        int edgesCreated = crossDocumentRelationCallback.extractRelationsFromGraphNodes(factSheetId);
                        if (isCancelled(job)) {
                            return;
                        }
                        job.getRelationshipsExtracted().addAndGet(edgesCreated);
                        incrementPipelineStep(job, "GRAPH_PREP", 1, 0,
                                "Cross-document relation extraction complete");
                        log.info("[Job {}] [BG] Cross-document relations: {} edges created (factSheetId={})",
                                job.getJobId(), edgesCreated, factSheetId);
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                        if (!isCancelled(job)) {
                            failPipelineStep(job, "GRAPH_PREP",
                                    "Cross-document relation extraction failed: " + errorDetail);
                        }
                        log.warn("[Job {}] [BG] Cross-document relation extraction failed: {}", job.getJobId(), errorDetail, e);
                    }
                }));
            }
            backgroundGraphPool.shutdown();

            // ═══════════════════════════════════════════════════════════════════
            // CRITICAL PATH: chunk → sort by cost → LLM extract (the bottleneck)
            // Starts immediately — doesn't wait for background graph work.
            // ═══════════════════════════════════════════════════════════════════

            // Phase 5: Chunking
            job.getCurrentFile().set("(chunking " + allDocuments.size() + " documents)");
            updatePipelineStep(job, "CHUNKING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, allDocuments.size(), 0, 0, 0, 0, null,
                    "Chunking routed documents");
            updateProgress(job, "CHUNKING", estimateProgress(job),
                    "Chunking routed documents", allDocuments.size() + " document(s)");
            waitForMemoryCapacity(job, "CHUNKING");
            log.info("[Job {}] Chunking {} documents...", job.getJobId(), allDocuments.size());
            List<Document> chunkedDocuments = new ArrayList<>(chunkDocuments(allDocuments, job));
            job.getChunksProcessed().set(chunkedDocuments.size());
            job.getChunksCreated().set(chunkedDocuments.size());
            job.getCurrentFile().set(null);
            updateProgress(job, "CHUNKING", estimateProgress(job),
                    "Chunking complete", chunkedDocuments.size() + " chunk(s)");
            completePipelineStep(job, "CHUNKING", allDocuments.size(),
                    allDocuments.size() + " document(s) chunked into " + chunkedDocuments.size() + " chunk(s)");
            log.info("[Job {}] Chunking complete: {} documents -> {} chunks",
                    job.getJobId(), allDocuments.size(), chunkedDocuments.size());

            // Phase 5a: Register SNIPPET nodes
            updateProgress(job, "GRAPH_PREP", estimateProgress(job),
                    "Registering snippet graph nodes", chunkedDocuments.size() + " chunk(s)");
            registerSnippetNodes(job.getJobId(), chunkedDocuments);
            incrementPipelineStep(job, "GRAPH_PREP", 1, 0,
                    "Snippet graph nodes registered");

            // Phase 5a-ii: Register documents and passages in cross-index tracker
            // so they appear in the index browser's Documents and Tables tabs.
            registerChunksInCrossIndex(job, chunkedDocuments);

            int releasedRoutedDocs = allDocuments.size();
            allDocuments.clear();
            recordEvent(job, "CHUNKING", "INFO",
                    "Released routed document batch after chunking",
                    releasedRoutedDocs + " document reference(s), chunks retained for graph/vector phases");
            trimNativeMemory(job, "CHUNKING", "after releasing routed documents");

            if (isCancelled(job)) return;

            // Phase 5b: optionally sort chunks by text length descending so expensive
            // chunks start first and worker threads do not drain into only long tail work.
            if (costSortChunks && chunkedDocuments.size() > 1) {
                chunkedDocuments.sort((a, b) -> {
                    int lenA = a.getText() != null ? a.getText().length() : 0;
                    int lenB = b.getText() != null ? b.getText().length() : 0;
                    return Integer.compare(lenB, lenA);
                });
            }
            if (costSortChunks && !chunkedDocuments.isEmpty()) {
                int longest = chunkedDocuments.get(0).getText() != null ? chunkedDocuments.get(0).getText().length() : 0;
                int shortest = chunkedDocuments.get(chunkedDocuments.size() - 1).getText() != null
                        ? chunkedDocuments.get(chunkedDocuments.size() - 1).getText().length() : 0;
                log.info("[Job {}] Chunks sorted by cost: longest={}chars, shortest={}chars",
                        job.getJobId(), longest, shortest);
            }

            // Vector indexing is intentionally queued until after graph surfacing
            // and cleanup. bge-m3 is required for multilingual/Japanese retrieval,
            // but it is memory-heavy and must not block the crawl graph from
            // surfacing or entity cleanup from running.
            VectorIndexConfig indexConfig = job.getRequest().getVectorIndex();
            final List<Document> chunksForIndex = new ArrayList<>(chunkedDocuments);
            boolean doVectorIndex = indexConfig != null && indexConfig.isEnabled()
                    && vectorStore != null && !chunksForIndex.isEmpty();
            if (doVectorIndex) {
                updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.PENDING,
                        0, chunksForIndex.size(), 0, 0, 0, 0, null,
                        "Queued until after crawl graph cleanup");
                recordEvent(job, "VECTOR_INDEXING", "INFO",
                        "Vector indexing queued after graph cleanup",
                        chunksForIndex.size() + " chunk(s)");
            }

            // Phase 6: LLM graph extraction. Vector indexing is queued until
            // after this graph surface has been cleaned.
            Graph unifiedGraph = new Graph();
            unifiedGraph.setId(job.getJobId());
            unifiedGraph.setEntities(new ArrayList<>());
            unifiedGraph.setRelationships(new ArrayList<>());
            unifiedGraph.setCommunities(new ArrayList<>());

            GraphExtractionConfig graphConfig = job.getRequest().getGraphExtraction();

            // ── Configuration validation: log clearly what IS and IS NOT available ──
            {
                boolean graphRequested = graphConfig != null && graphConfig.isEnabled();
                boolean vectorRequested = job.getRequest().getVectorIndex() != null
                        && job.getRequest().getVectorIndex().isEnabled();
                StringBuilder configReport = new StringBuilder();
                configReport.append("Crawl configuration for job ").append(job.getJobId()).append(":\n");
                configReport.append("  Graph extraction: ").append(graphRequested ? "ENABLED" : "DISABLED").append("\n");
                configReport.append("  Vector indexing:  ").append(vectorRequested ? "ENABLED" : "DISABLED").append("\n");
                configReport.append("  GraphConstructor: ").append(graphConstructor != null ? "AVAILABLE" : "NOT CONFIGURED").append("\n");
                configReport.append("  LLMChat:          ").append(llmChat != null ? "AVAILABLE (" + llmChat.getClass().getSimpleName() + ")" : "NOT CONFIGURED").append("\n");
                configReport.append("  KnowledgeGraph:   ").append(knowledgeGraphService != null ? "AVAILABLE" : "NOT CONFIGURED");
                log.info("[Job {}] {}", job.getJobId(), configReport);
                recordEvent(job, job.getCurrentPhase().get(), "INFO",
                        "Configuration summary",
                        "graphExtraction=" + (graphRequested ? "ON" : "OFF")
                                + ", vectorIndex=" + (vectorRequested ? "ON" : "OFF")
                                + ", graphConstructor=" + (graphConstructor != null ? "YES" : "NO")
                                + ", llm=" + (llmChat != null ? llmChat.getClass().getSimpleName() : "NONE")
                                + ", knowledgeGraph=" + (knowledgeGraphService != null ? "YES" : "NO"));

                if (graphRequested && graphConstructor == null && llmChat == null) {
                    String warnMsg = "Graph extraction was requested but NO LLM or GraphConstructor is configured. "
                            + "Graph extraction will be SKIPPED. Configure an LLM provider to enable it.";
                    log.warn("[Job {}] {}", job.getJobId(), warnMsg);
                    job.getErrors().add(warnMsg);
                    job.getErrorCount().incrementAndGet();
                    recordEvent(job, "GRAPH_EXTRACTION", "WARN", warnMsg, null);
                }
            }

            if (graphConfig != null && graphConfig.isEnabled()
                    && (graphConstructor != null || llmChat != null)) {
                job.getCurrentFile().set("(graph extraction: " + chunkedDocuments.size() + " chunks, parallel LLM)");
                resetGraphExtractionProgress(job, chunkedDocuments.size());
                updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                        0, chunkedDocuments.size(), 0, 0, 0, 0, null,
                        "Starting graph extraction");
                updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                        "Starting graph extraction", chunkedDocuments.size() + " chunk(s)");
                waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                    log.info("[Job {}] Starting LLM graph extraction for {} chunks (vector indexing queued after graph cleanup)",
                            job.getJobId(), chunkedDocuments.size());
                extractGraphFromDocuments(chunkedDocuments, graphConfig, unifiedGraph, job);
                job.getCurrentFile().set(null);

                // Check if LLM extraction produced any results at all
                int extractedEntities = job.getEntitiesExtracted().get();
                int extractedRelationships = job.getRelationshipsExtracted().get();
                int graphErrors = job.getErrorCount().get();
                String graphSummary = extractedEntities + " entities, " + extractedRelationships + " relationships";

                if (extractedEntities == 0 && extractedRelationships == 0 && graphErrors > 0) {
                    // Every LLM call failed — mark the step as FAILED so the job reports it
                    String failMsg = "LLM graph extraction failed for all " + chunkedDocuments.size()
                            + " chunks (" + graphErrors + " errors). Check LLM configuration.";
                    log.error("[Job {}] {}", job.getJobId(), failMsg);
                    job.getErrors().add(failMsg);
                    recordEvent(job, "GRAPH_EXTRACTION", "ERROR", failMsg, null);
                    failPipelineStep(job, "GRAPH_EXTRACTION", failMsg);
                } else {
                    if (graphErrors > 0) {
                        String warnMsg = graphErrors + " of " + chunkedDocuments.size()
                                + " chunks failed LLM graph extraction. " + graphSummary + " from successful chunks.";
                        log.warn("[Job {}] {}", job.getJobId(), warnMsg);
                        recordEvent(job, "GRAPH_EXTRACTION", "WARN", warnMsg, null);
                    }
                    updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                            "Graph extraction complete", graphSummary);
                    completePipelineStep(job, "GRAPH_EXTRACTION", completeGraphExtractionProgress(job),
                            graphSummary);
                }
                trimNativeMemory(job, "GRAPH_EXTRACTION", "after graph extraction");
            } else {
                if (graphConfig == null || !graphConfig.isEnabled()) {
                    skipPipelineStep(job, "GRAPH_EXTRACTION", "Graph extraction disabled in request configuration");
                } else {
                    skipPipelineStep(job, "GRAPH_EXTRACTION",
                            "Graph extraction SKIPPED: no LLM or GraphConstructor configured. "
                                    + "GraphConstructor=" + (graphConstructor != null) + ", LLMChat=" + (llmChat != null));
                }
            }

            if (isCancelled(job)) return;

            // ═══════════════════════════════════════════════════════════════════
            // JOIN: wait for background graph work to finish. These tasks populate
            // required graph structure, so the crawl should not report a terminal
            // state while they are still mutating the job/graph stores.
            // ═══════════════════════════════════════════════════════════════════
            for (int i = 0; i < backgroundGraphFutures.size(); i++) {
                waitForBackgroundGraphFuture(job, backgroundGraphFutures.get(i),
                        i + 1, backgroundGraphFutures.size());
            }
            emailDocs.clear();
            docGraphDocs.clear();
            UnifiedCrawlJob.PipelineStepProgress graphPrepStep = ensurePipelineStep(job, "GRAPH_PREP");
            if (graphPrepStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.RUNNING
                    || graphPrepStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE) {
                completePipelineStep(job, "GRAPH_PREP",
                        Math.max(graphPrepStep.getCompletedItems().get(), graphPrepStep.getTotalItems().get()),
                        "Rule-based graph preparation complete");
            }
            surfaceCrawlResults(job, chunkedDocuments);
            trimNativeMemory(job, "GRAPH_PREP", "before entity resolution");

            if (isCancelled(job)) return;

            // Phase 6.5: Entity resolution — must run AFTER all graph work AND
            // before enrichment/indexing sees the graph.
            GraphExtractionConfig graphConfigForResolution = job.getRequest().getGraphExtraction();
            if (graphCompactionService != null && graphConfigForResolution != null
                    && graphConfigForResolution.isEntityResolution()) {
                try {
                    updateProgress(job, "ENTITY_RESOLUTION", estimateProgress(job),
                            "Running entity resolution", null);
                    boolean memoryReady = waitForMemoryCapacity(job, "ENTITY_RESOLUTION");
                    log.info("[Job {}] Running entity resolution / graph compaction", job.getJobId());
                    updatePipelineStep(job, "ENTITY_RESOLUTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                            0, 1, 0, 0, 0, 0, null,
                            "Running entity resolution");
                    Long factSheetId = jobFactSheetId(job);
                    boolean useEmbeddingResolution = graphConfigForResolution.isEntityResolutionUseEmbeddings();
                    double embeddingResolutionThreshold = graphConfigForResolution.getEntityResolutionEmbeddingThreshold() > 0
                            ? graphConfigForResolution.getEntityResolutionEmbeddingThreshold()
                            : 0.88;
                    if (useEmbeddingResolution && (!memoryReady || hasNativeMemoryPressure(job, nativeMemoryWaitThresholdPercent))) {
                        useEmbeddingResolution = false;
                        recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                "Embedding-assisted entity resolution disabled for memory pressure",
                                memoryPressureDetail(job));
                    }
                    if (useEmbeddingResolution) {
                        EmbeddingModel embModel = primaryEmbeddingModel();
                        if (!isEmbeddingModelReady(embModel)) {
                            useEmbeddingResolution = false;
                            recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                    "Embedding-assisted entity resolution skipped because embedding model is not ready",
                                    embeddingModelNotReadyReason(embModel));
                        }
                    }
                    recordEvent(job, "ENTITY_RESOLUTION", "INFO",
                            "Entity resolution mode",
                            "embeddings=" + useEmbeddingResolution
                                    + ", threshold=" + entityResolutionSimilarityThreshold(graphConfigForResolution)
                                    + ", embeddingThreshold=" + embeddingResolutionThreshold);
                    var compactionResult = graphCompactionService.compact(factSheetId,
                            new ai.kompile.knowledgegraph.resolution.GraphCompactionService.CompactionConfig(
                                    entityResolutionSimilarityThreshold(graphConfigForResolution),
                                    true,
                                    useEmbeddingResolution,
                                    embeddingResolutionThreshold,
                                    progress -> recordEntityResolutionProgress(job, progress)));
                    if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                        updatePipelineStep(job, "ENTITY_RESOLUTION",
                                UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                                0, 1, 0, 0, 0, 0, null,
                                "Entity resolution cancelled");
                        return;
                    }
                    log.info("[Job {}] Graph compaction: {} entities merged into {} ({}ms)",
                            job.getJobId(),
                            compactionResult.entitiesMerged(),
                            compactionResult.finalEntityCount(),
                            compactionResult.elapsedMs());
                    completePipelineStep(job, "ENTITY_RESOLUTION", 1,
                            compactionResult.entitiesMerged() + " merge(s), "
                                    + compactionResult.finalEntityCount() + " final entities");
                } catch (CancellationException e) {
                    updatePipelineStep(job, "ENTITY_RESOLUTION",
                            UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                            0, 1, 0, 0, 0, 0, null,
                            "Entity resolution cancelled");
                    recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                            "Entity resolution cancelled", e.getMessage());
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                    failPipelineStep(job, "ENTITY_RESOLUTION", "Graph compaction failed: " + errorDetail);
                    log.warn("[Job {}] Graph compaction failed (non-fatal): {}", job.getJobId(), errorDetail, e);
                } finally {
                    trimNativeMemory(job, "ENTITY_RESOLUTION", "after entity resolution");
                }
            } else {
                skipPipelineStep(job, "ENTITY_RESOLUTION", "Entity resolution disabled or unavailable");
            }

            if (isCancelled(job)) return;

            // Phase 7: Edge computation — graph edges that need cleaned graph data.
            boolean doEdgeComputation = graphEdgeComputationService != null && knowledgeGraphService != null;

            // Start shared-entity edge computation in background (no embedding needed)
            Future<?> sharedEdgeFuture = null;
            if (doEdgeComputation) {
                ExecutorService edgePool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "unified-crawl-edges-" + job.getJobId().substring(0, 8));
                    t.setDaemon(true);
                    return t;
                });
                updatePipelineStep(job, "EDGE_COMPUTATION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                        0, 2, 0, 0, 0, 0, null,
                        "Computing automatic graph edges");
                sharedEdgeFuture = edgePool.submit(() -> {
                    try {
                        Long factSheetId = jobFactSheetId(job);
                        log.info("[Job {}] Computing shared entity edges for factSheetId={}",
                                job.getJobId(), factSheetId);
                        graphEdgeComputationService.computeSharedEntityEdges(factSheetId, 2);
                        incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Shared entity edges computed");
                        log.info("[Job {}] Shared entity edge computation complete", job.getJobId());
                    } catch (Exception e) {
                        log.warn("[Job {}] Shared entity edge computation failed: {}", job.getJobId(), e.getMessage());
                    }
                });
                edgePool.shutdown();
            }
            if (!doVectorIndex) {
                skipPipelineStep(job, "VECTOR_INDEXING", "Vector indexing disabled or unavailable");
            }

            // Wait for shared-entity edges, then run embedding similarity edges sequentially
            if (doEdgeComputation) {
                if (sharedEdgeFuture != null) {
                    try { sharedEdgeFuture.get(300, TimeUnit.SECONDS); }
                    catch (Exception e) { log.warn("[Job {}] Shared edge future: {}", job.getJobId(), e.getMessage()); }
                }
                if (!isCancelled(job)) {
                    try {
                        boolean memoryReady = waitForMemoryCapacity(job, "EDGE_COMPUTATION");
                        Long factSheetId = jobFactSheetId(job);
                        EmbeddingModel embModel = primaryEmbeddingModel();
                        if (memoryReady && isEmbeddingModelReady(embModel)) {
                            graphEdgeComputationService.computeEmbeddingSimilarityEdges(factSheetId, 0.7, 10);
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Embedding similarity edges computed");
                        } else {
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0,
                                    "Embedding similarity edges skipped until embedding model is ready");
                            recordEvent(job, "EDGE_COMPUTATION", "WARN",
                                    "Embedding similarity edges skipped",
                                    memoryReady ? embeddingModelNotReadyReason(embModel) : memoryPressureDetail(job));
                        }
                    } catch (Exception e) {
                        log.warn("[Job {}] Embedding similarity edge computation failed: {}", job.getJobId(), e.getMessage());
                    }
                }
                completePipelineStep(job, "EDGE_COMPUTATION", 2, "Graph edge computation complete");
                log.info("[Job {}] Graph edge computation complete", job.getJobId());
            } else {
                skipPipelineStep(job, "EDGE_COMPUTATION", "Graph edge computation disabled or unavailable");
            }

            if (isCancelled(job)) return;

            // Phase 8: Vector indexing / multilingual retrieval enrichment.
            // This is intentionally after surfacing and graph cleanup so bge-m3
            // memory pressure cannot prevent clean process-map graph output.
            if (doVectorIndex) {
                EmbeddingModel embModel = primaryEmbeddingModel();
                if (!isEmbeddingModelReady(embModel)) {
                    deferVectorIndexing(job, chunksForIndex, indexConfig,
                            "Embedding model not ready: " + embeddingModelNotReadyReason(embModel));
                } else {
                    try {
                        job.getCurrentFile().set("(indexing " + chunksForIndex.size() + " chunks)");
                        log.info("[Job {}] Indexing {} chunks to vector store after graph cleanup",
                                job.getJobId(), chunksForIndex.size());
                        indexDocuments(chunksForIndex, indexConfig, job);
                        log.info("[Job {}] Vector indexing complete", job.getJobId());
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName();
                        log.warn("[Job {}] Vector indexing deferred after failure: {}",
                                job.getJobId(), errorDetail, e);
                        deferVectorIndexing(job, chunksForIndex, indexConfig,
                                "Vector indexing failed and was deferred: " + errorDetail);
                    }
                }
                chunksForIndex.clear();
            } else {
                chunksForIndex.clear();
                skipPipelineStep(job, "VECTOR_INDEXING", "Vector indexing disabled or unavailable");
            }
            job.getCurrentFile().set(null);

            // Complete
            int finalChunkCount = chunkedDocuments.size();
            if (retainResultGraph) {
                job.setResultGraph(unifiedGraph);
            } else {
                releaseInMemoryGraph(unifiedGraph);
                job.setResultGraph(null);
                recordEvent(job, "COMPLETED", "INFO",
                        "Released in-memory extraction graph",
                        "Persisted fact-sheet graph is the source of truth for UI/API graph results");
            }
            chunkedDocuments.clear();
            trimNativeMemory(job, "COMPLETED", "after releasing crawl document/result buffers");
            // Check if any pipeline step FAILED — if so, the job must not report as COMPLETED
            boolean hasFailedSteps = job.getPipelineSteps().stream()
                    .anyMatch(step -> step.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.FAILED);
            boolean hasDeferredEmbedding = !job.getDeferredEmbeddingChunks().isEmpty();

            if (hasFailedSteps) {
                // Loud failure: one or more pipeline steps failed (e.g., VECTOR_INDEXING crashed)
                List<String> failedNames = job.getPipelineSteps().stream()
                        .filter(step -> step.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.FAILED)
                        .map(step -> step.getStepId() != null ? step.getStepId() : step.getDisplayName())
                        .toList();
                String failedStepsStr = String.join(", ", failedNames);
                String errorMsg = "Crawl completed but pipeline step(s) FAILED: " + failedStepsStr;
                log.error("[Job {}] {}", job.getJobId(), errorMsg);
                job.getStatus().set(UnifiedCrawlJob.Status.FAILED);
                job.getCurrentPhase().set("FAILED");
                job.setErrorMessage(errorMsg);
                job.setCompletedAt(Instant.now());
                job.getErrors().add(errorMsg);
                recordEvent(job, "FAILED", "ERROR", "Pipeline step failure",
                        errorMsg + ". " + job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
            } else if (hasDeferredEmbedding) {
                String message = "Crawl graph completed; "
                        + job.getDeferredEmbeddingChunks().size()
                        + " chunk(s) pending bge-m3/vector embedding";
                log.warn("[Job {}] {}", job.getJobId(), message);
                job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING);
                job.getCurrentPhase().set("PENDING_EMBEDDING");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "PENDING_EMBEDDING", "WARN", "Unified crawl completed pending embedding",
                        message + ". " + job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
                publishGraphBuildCompletedEvent(job);
            } else {
                job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED);
                job.getCurrentPhase().set("COMPLETED");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "COMPLETED", "INFO", "Unified crawl completed",
                        job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");

                publishGraphBuildCompletedEvent(job);
            }
            log.info("Unified crawl job {} completed: {} docs, {} chunks, {} entities, {} relationships",
                    job.getJobId(),
                    job.getDocumentsLoaded().get(),
                    finalChunkCount,
                    job.getEntitiesExtracted().get(),
                    job.getRelationshipsExtracted().get());

        } catch (Throwable e) {
            log.error("Unified crawl job {} failed: {}", job.getJobId(), e.getMessage(), e);
            failPipelineStep(job, job.getCurrentPhase().get(), e.getClass().getSimpleName() + ": " + e.getMessage());
            job.getStatus().set(UnifiedCrawlJob.Status.FAILED);
            job.getCurrentPhase().set("FAILED");
            job.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            job.setCompletedAt(Instant.now());
            recordEvent(job, "FAILED", "ERROR", "Unified crawl failed",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private List<SourceLoadResult> loadSources(UnifiedCrawlJob job) throws InterruptedException {
        List<UnifiedCrawlSource> sources = job.getRequest().getSources();
        int parallelism = Math.min(Math.max(1, sourceLoadParallelism), sources.size());
        updateProgress(job, "LOADING", estimateProgress(job),
                "Planning source loading",
                sources.size() + " source(s), parallelism=" + parallelism);

        if (parallelism <= 1 || sources.size() <= 1) {
            List<SourceLoadResult> results = new ArrayList<>(sources.size());
            for (int i = 0; i < sources.size(); i++) {
                if (isCancelled(job)) break;
                results.add(loadSourceAt(job, i, sources.get(i)));
            }
            return results;
        }

        ExecutorService sourceExec = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "unified-crawl-source-" + job.getJobId().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<SourceLoadResult>> futures = new ArrayList<>(sources.size());
            for (int i = 0; i < sources.size(); i++) {
                final int sourceIndex = i;
                UnifiedCrawlSource source = sources.get(i);
                futures.add(sourceExec.submit(() -> loadSourceAt(job, sourceIndex, source)));
            }

            List<SourceLoadResult> results = new ArrayList<>(Collections.nCopies(sources.size(), null));
            for (int i = 0; i < futures.size(); i++) {
                if (isCancelled(job)) {
                    break;
                }
                try {
                    SourceLoadResult result = futures.get(i).get();
                    results.set(result.index(), result);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    recordEvent(job, "LOADING", "ERROR",
                            "Source load task failed", cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    job.getErrors().add("Source load task failed: " + cause.getMessage());
                    job.getErrorCount().incrementAndGet();
                }
            }
            return results.stream().filter(Objects::nonNull).collect(Collectors.toList());
        } finally {
            sourceExec.shutdownNow();
        }
    }

    private String summarizeSourceLoadErrors(UnifiedCrawlJob job) {
        List<String> errors = job.getErrors();
        if (errors == null || errors.isEmpty()) {
            return "No documents were loaded from any configured source";
        }
        int limit = Math.min(3, errors.size());
        String summary = String.join("; ", errors.subList(0, limit));
        if (errors.size() > limit) {
            summary += "; +" + (errors.size() - limit) + " more";
        }
        return "No documents were loaded from any configured source: " + summary;
    }

    private SourceLoadResult loadSourceAt(UnifiedCrawlJob job, int index, UnifiedCrawlSource source) {
        UnifiedCrawlJob.SourceProgress progress = job.getSourceProgress().get(index);
        String label = source.getLabel() != null ? source.getLabel() : "source-" + index;
        progress.setStatus(UnifiedCrawlJob.Status.RUNNING);
        progress.setCurrentPhase("LOADING");
        progress.setCurrentItem(source.getPathOrUrl());
        updateProgress(job, "LOADING", estimateProgress(job),
                "Loading source " + (index + 1) + "/" + job.getRequest().getSources().size(),
                label);
        waitForMemoryCapacity(job, "LOADING");

        try {
            log.info("[Job {}] Loading source '{}' (type={}, path={})",
                    job.getJobId(), label, source.getSourceType(), source.getPathOrUrl());
            int loadedBefore = job.getDocumentsLoaded().get();
            List<Document> docs = loadFromSource(source, job, progress);
            docs.forEach(doc -> applyCrawlScopeMetadata(job, source, doc));
            int delta = docs.size() - (job.getDocumentsLoaded().get() - loadedBefore);
            if (delta > 0) {
                job.getDocumentsLoaded().addAndGet(delta);
            }
            for (Document doc : docs) {
                recordDocumentProgress(job, doc, "LOADING", "LOADED", 0, 0, 0,
                        "Loaded from source " + label, null, List.of("loader"), false);
            }
            progress.setDocumentsLoaded(docs.size());
            progress.setDocumentsDiscovered(Math.max(progress.getDocumentsDiscovered(), docs.size()));
            progress.setStatus(UnifiedCrawlJob.Status.COMPLETED);
            progress.setCurrentPhase("COMPLETED");
            progress.setCurrentItem(null);
            updateProgress(job, "LOADING", estimateProgress(job),
                    "Loaded " + docs.size() + " document(s)",
                    label);
            log.info("[Job {}] Loaded {} document(s) from source '{}'",
                    job.getJobId(), docs.size(), label);
            return new SourceLoadResult(index, label, docs);
        } catch (Throwable e) {
            log.error("[Job {}] Failed to load from source '{}': {} - {}",
                    job.getJobId(), label, e.getClass().getSimpleName(), e.getMessage(), e);
            progress.setStatus(UnifiedCrawlJob.Status.FAILED);
            progress.setCurrentPhase("FAILED");
            progress.setCurrentItem(null);
            progress.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            job.getErrors().add("Source '" + label + "' failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.getErrorCount().incrementAndGet();
            recordEvent(job, "LOADING", "ERROR",
                    "Source failed: " + label,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new SourceLoadResult(index, label, List.of());
        }
    }

    private List<Document> loadFromSource(UnifiedCrawlSource source,
                                          UnifiedCrawlJob job,
                                          UnifiedCrawlJob.SourceProgress progress) throws Exception {
        if (source.getSourceType() == null) {
            throw new IllegalArgumentException(
                    "Source '" + source.getLabel() + "' has no sourceType. " +
                    "Set sourceType to one of: DIRECTORY, FILE, URL, WEB_CRAWL, etc. " +
                    "(pathOrUrl=" + source.getPathOrUrl() + ")");
        }
        if (source.getPathOrUrl() == null || source.getPathOrUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Source '" + source.getLabel() + "' has no pathOrUrl. " +
                    "Provide a directory path, file path, or URL. " +
                    "(sourceType=" + source.getSourceType() + ")");
        }
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(source.getSourceType())
                .pathOrUrl(source.getPathOrUrl())
                .metadata(sourceMetadata(source, job))
                .build();

        // For WEB_CRAWL and DIRECTORY sources, prefer the crawler path — these need
        // recursive traversal (link following / directory descent) that only the
        // crawler provides. Document loaders would only handle a single URL or file.
        if (isCrawlPreferredSourceType(source.getSourceType())) {
            if (crawlerService != null && isSourceTypeCrawlable(source.getSourceType())) {
                return crawlSource(source, job, progress);
            }
            // If no crawler is available, fall through to try loaders as a last resort
            log.warn("[Job {}] No crawler available for {} source '{}', falling through to loaders",
                    job.getJobId(), source.getSourceType(), source.getLabel());
        }

        // Try document loaders for non-crawl source types (FILE, EMAIL, SLACK, etc.)
        if (documentLoaders != null) {
            for (DocumentLoader loader : documentLoaders) {
                if (loader.supports(descriptor)) {
                    log.info("[Job {}] Using loader '{}' for source '{}'", job.getJobId(), loader.getName(), source.getLabel());
                    List<Document> docs = loader.load(descriptor, loaderProgress -> {
                        // loaderProgress.progressPercent() is 0-100, not a document count.
                        // Use currentStep as currentFile if available; do not treat percent as a counter.
                        if (loaderProgress.currentStep() != null) {
                            job.getCurrentFile().set(loaderProgress.currentStep());
                            progress.setCurrentItem(loaderProgress.currentStep());
                        }
                        if (loaderProgress.message() != null) {
                            recordEvent(job, "LOADING", "INFO",
                                    loaderProgress.message(), source.getLabel());
                            log.info("[Job {}] Loader progress ({}%): {}", job.getJobId(),
                                    loaderProgress.progressPercent(), loaderProgress.message());
                        }
                        updateProgress(job, "LOADING", estimateProgress(job),
                                loaderProgress.message() != null ? loaderProgress.message() : "Loading source",
                                loaderProgress.currentStep());
                    });
                    return docs;
                }
            }
        }

        // Fall back to crawler for other crawlable source types (URL, etc.)
        if (crawlerService != null && isSourceTypeCrawlable(source.getSourceType())) {
            return crawlSource(source, job, progress);
        }

        throw new IllegalStateException("No loader or crawler available for source type: " + source.getSourceType());
    }

    private Map<String, Object> sourceMetadata(UnifiedCrawlSource source, UnifiedCrawlJob job) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source.getProperties() != null) {
            metadata.putAll(source.getProperties());
        }
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId != null) {
            metadata.put("factSheetId", factSheetId);
            metadata.put("fact_sheet_id", factSheetId);
        }
        if (source.getLabel() != null) {
            metadata.putIfAbsent("source_label", source.getLabel());
        }
        if (source.getSourceType() != null) {
            metadata.putIfAbsent(GraphConstants.META_SOURCE_TYPE, source.getSourceType().name());
        }
        if (source.getPathOrUrl() != null) {
            metadata.putIfAbsent(GraphConstants.META_SOURCE_PATH, source.getPathOrUrl());
            metadata.putIfAbsent(GraphConstants.META_SOURCE, source.getPathOrUrl());
        }
        return metadata;
    }

    private void applyCrawlScopeMetadata(UnifiedCrawlJob job, UnifiedCrawlSource source, Document doc) {
        if (doc == null) return;
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata == null) return;
        sourceMetadata(source, job).forEach(metadata::putIfAbsent);
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }

    private Long jobFactSheetId(String jobId) {
        UnifiedCrawlJob job = jobs.get(jobId);
        return jobFactSheetId(job);
    }

    /**
     * Register crawled sources as facts in the target fact sheet.
     * Each source with loaded documents becomes a Fact record so that
     * the fact sheet tracks what was crawled.
     */
    private void registerCrawledSourcesAsFacts(UnifiedCrawlJob job, List<SourceLoadResult> sourceResults) {
        if (crawlFactRegistrationCallback == null) {
            return;
        }
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId == null) {
            return;
        }
        try {
            List<UnifiedCrawlSource> requestSources = job.getRequest().getSources();
            List<CrawlFactRegistrationCallback.CrawledSourceInfo> sourceInfos = new ArrayList<>();
            for (SourceLoadResult result : sourceResults) {
                if (result == null || result.documents() == null) {
                    continue;
                }
                // Find the matching UnifiedCrawlSource for this result
                UnifiedCrawlSource matchedSource = null;
                if (result.index() >= 0 && result.index() < requestSources.size()) {
                    matchedSource = requestSources.get(result.index());
                }
                String sourceType = matchedSource != null && matchedSource.getSourceType() != null
                        ? matchedSource.getSourceType().name() : null;
                String pathOrUrl = matchedSource != null ? matchedSource.getPathOrUrl() : null;

                sourceInfos.add(new CrawlFactRegistrationCallback.CrawledSourceInfo(
                        result.label(),
                        sourceType,
                        pathOrUrl,
                        result.documents().size()
                ));
            }
            if (!sourceInfos.isEmpty()) {
                int created = crawlFactRegistrationCallback.registerCrawledSources(factSheetId, sourceInfos);
                if (created > 0) {
                    recordEvent(job, "LOADING", "INFO",
                            "Registered " + created + " crawled source(s) as facts",
                            "factSheetId=" + factSheetId);
                }
            }
        } catch (Exception e) {
            log.warn("[Job {}] Failed to register crawled sources as facts: {}",
                    job.getJobId(), e.getMessage());
            recordEvent(job, "LOADING", "WARN",
                    "Failed to register crawled sources as facts",
                    e.getMessage());
        }
    }

    private List<Document> crawlSource(UnifiedCrawlSource source,
                                       UnifiedCrawlJob job,
                                       UnifiedCrawlJob.SourceProgress progress) throws Exception {
        // Extract crawlerId from source properties if provided
        String crawlerId = null;
        if (source.getProperties() != null) {
            Object id = source.getProperties().get("crawlerId");
            if (id instanceof String s && !s.isBlank()) {
                crawlerId = s;
            }
        }

        // Forward web-crawl-specific properties from the source properties map
        Map<String, Object> props = source.getProperties() != null ? source.getProperties() : Map.of();
        boolean sameDomainOnly = boolProp(props, "sameDomainOnly", true);
        boolean respectRobotsTxt = boolProp(props, "respectRobotsTxt", true);
        String userAgent = stringProp(props, "userAgent", null);
        boolean followSymlinks = boolProp(props, "followSymlinks", false);
        boolean includeHidden = boolProp(props, "includeHidden", false);

        CrawlConfig.CrawlConfigBuilder configBuilder = CrawlConfig.builder()
                .crawlerId(crawlerId)
                .seed(source.getPathOrUrl())
                .sourceType(source.getSourceType())
                .maxDepth(source.getMaxDepth())
                .maxDocuments(source.getMaxDocuments() > 0 ? source.getMaxDocuments() : 1000)
                .includePatterns(source.getIncludePatterns())
                .excludePatterns(source.getExcludePatterns())
                .allowedContentTypes(source.getAllowedContentTypes())
                .properties(source.getProperties() != null ? source.getProperties() : new HashMap<>())
                .sameDomainOnly(sameDomainOnly)
                .respectRobotsTxt(respectRobotsTxt)
                .forceRecrawl(true); // Unified crawl manages its own lifecycle — always re-crawl

        if (userAgent != null && !userAgent.isBlank()) {
            configBuilder.userAgent(userAgent);
        }

        // Ensure filesystem-specific flags are in properties for FileSystemCrawler
        if (source.getSourceType() == DocumentSourceDescriptor.SourceType.DIRECTORY
                || source.getSourceType() == DocumentSourceDescriptor.SourceType.FILE) {
            Map<String, Object> mergedProps = new HashMap<>(props);
            mergedProps.putIfAbsent("followSymlinks", followSymlinks);
            mergedProps.putIfAbsent("includeHidden", includeHidden);
            configBuilder.properties(mergedProps);
        }

        CrawlConfig config = configBuilder.build();

        // Collect discovered items during crawl, then load documents after crawl completes.
        // This avoids blocking the crawler's file-walk thread with document loading.
        List<CrawlItem> discoveredItems = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> crawlDone = new CompletableFuture<>();

        CrawlJob crawlJob = crawlerService.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                int discovered = progress.getDocumentsDiscovered() + 1;
                progress.setDocumentsDiscovered(discovered);
                job.getDocumentsDiscovered().incrementAndGet();
                job.recordDiscoveredItem(item.getUrl(),
                        source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN",
                        source.getLabel());
                progress.setCurrentPhase("DISCOVERING");
                progress.setCurrentItem(item.getUrl());
                updateProgress(job, "DISCOVERING", estimateProgress(job),
                        "Discovered " + discovered + " item(s)", item.getUrl());
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                // Just collect the item — actual document loading happens after crawl completes
                discoveredItems.add(item);
                String fileName = item.getUrl();
                int lastSlash = fileName.lastIndexOf('/');
                if (lastSlash < 0) lastSlash = fileName.lastIndexOf('\\');
                String shortName = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
                job.recordDiscoveredItem(shortName,
                        source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN",
                        source.getLabel());
                log.info("[Job {}] Discovered file: {} (total: {})",
                        job.getJobId(), shortName, discoveredItems.size());
                updateProgress(job, "DISCOVERING", estimateProgress(job),
                        "Queued discovered item for loading", shortName);
            }

            @Override
            public void onProgress(CrawlProgress p) {
                // Fine-grained progress is tracked via discovered/processed callbacks above
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                log.info("[Job {}] Crawl discovery complete: {} items found",
                        job.getJobId(), discoveredItems.size());
                crawlDone.complete(null);
            }

            @Override
            public void onDocumentFailed(CrawlItem item, Exception error) {
                String shortName = item.getUrl();
                int lastSlash = shortName.lastIndexOf('/');
                if (lastSlash < 0) lastSlash = shortName.lastIndexOf('\\');
                if (lastSlash >= 0) shortName = shortName.substring(lastSlash + 1);
                String errorMsg = "Failed to crawl '" + shortName + "': " + error.getMessage();
                log.warn("[Job {}] {}", job.getJobId(), errorMsg);
                job.getErrors().add(errorMsg);
                job.getErrorCount().incrementAndGet();
            }
        });

        // Wait for crawl discovery to complete (with timeout)
        try {
            crawlDone.get(1, TimeUnit.HOURS);
        } catch (TimeoutException e) {
            crawlJob.cancel();
            throw new RuntimeException("Crawl timed out for source: " + source.getLabel());
        }
        completePipelineStep(job, "DISCOVERING", discoveredItems.size(),
                discoveredItems.size() + " item(s) discovered");

        log.info("[Job {}] Loading documents from {} discovered files...",
                job.getJobId(), discoveredItems.size());

        // Now load documents from discovered items (off the crawler thread)
        List<Document> collectedDocs = new ArrayList<>();
        for (CrawlItem item : discoveredItems) {
            if (isCancelled(job)) return collectedDocs;
            waitForMemoryCapacity(job, "LOADING");
            String fileName = item.getUrl();
            int lastSlash = fileName.lastIndexOf('/');
            if (lastSlash < 0) lastSlash = fileName.lastIndexOf('\\');
            String shortName = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;

            job.getCurrentFile().set(shortName);
            progress.setCurrentPhase("LOADING");
            progress.setCurrentItem(shortName);
            int loaded = job.getDocumentsLoaded().get();
            log.info("[Job {}] Loading file: {} ({}/{})",
                    job.getJobId(), shortName, loaded + 1, discoveredItems.size());
            updateProgress(job, "LOADING", estimateProgress(job),
                    "Loading discovered file " + (collectedDocs.size() + 1) + "/" + discoveredItems.size(),
                    shortName);

            try {
                DocumentSourceDescriptor desc = item.getSourceDescriptor();
                if (desc == null) {
                    desc = DocumentSourceDescriptor.builder()
                            .type(source.getSourceType())
                            .pathOrUrl(item.getUrl())
                            .build();
                }
                int docsBeforeLoad = collectedDocs.size();
                boolean loaderFound = false;
                if (documentLoaders != null) {
                    for (DocumentLoader loader : documentLoaders) {
                        if (loader.supports(desc)) {
                            loaderFound = true;
                            log.info("[Job {}] Using loader '{}' for file: {} (type={})",
                                    job.getJobId(), loader.getName(), shortName, desc.getType());
                            List<Document> docs = loader.load(desc);
                            for (Document doc : docs) {
                                doc.getMetadata().put("source_url", item.getUrl());
                                doc.getMetadata().put(GraphConstants.META_SOURCE_TYPE,
                                        source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN");
                                if (item.getContentType() != null
                                        && !doc.getMetadata().containsKey(GraphConstants.META_CONTENT_TYPE)) {
                                    doc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, item.getContentType());
                                }
                            }
                            collectedDocs.addAll(docs);
                            int docsFromFile = collectedDocs.size() - docsBeforeLoad;
                            int newTotal = job.getDocumentsLoaded().addAndGet(docsFromFile);
                            progress.setDocumentsLoaded(newTotal);
                            for (int docIndex = docsBeforeLoad; docIndex < collectedDocs.size(); docIndex++) {
                                recordDocumentProgress(job, collectedDocs.get(docIndex), "LOADING", "LOADED", 0, 0, 0,
                                        "Loaded " + docsFromFile + " document(s) from file", null,
                                        List.of(loader.getName()), false);
                            }
                            updateProgress(job, "LOADING", estimateProgress(job),
                                    "Loaded " + docsFromFile + " document(s)", shortName);
                            log.info("[Job {}] Loaded file: {} - {} document(s) (total: {})",
                                    job.getJobId(), shortName, docsFromFile, newTotal);
                            break;
                        }
                    }
                }
                if (!loaderFound) {
                    log.debug("[Job {}] Skipping unsupported file '{}' (type={}, path={})",
                            job.getJobId(), shortName, desc.getType(), item.getUrl());
                }
            } catch (Throwable e) {
                String errorMsg = "Failed to load '" + shortName + "': " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("[Job {}] {}", job.getJobId(), errorMsg, e);
                job.getErrors().add(errorMsg);
                job.getErrorCount().incrementAndGet();
            }
        }

        return collectedDocs;
    }

    private void extractGraphFromDocuments(List<Document> documents,
                                           GraphExtractionConfig config,
                                           Graph targetGraph,
                                           UnifiedCrawlJob job) {
        // Configure the graph constructor if available
        if (graphConstructor != null) {
            graphConstructor.configure(new GraphConstructor.ExtractionModelConfig(
                    config.getLlmProvider(),
                    config.getModelName(),
                    config.getTemperature(),
                    config.getMaxTokens(),
                    config.getCustomPrompt()
            ));
        }

        // Delegate to GraphConstructor when available. Unified crawl owns scoped
        // fact-sheet graph persistence below, so by default the constructor only
        // extracts and returns semantic entities/relationships.
        if (graphConstructor != null) {
            extractGraphViaConstructor(documents, config, targetGraph, job);
        } else if (llmChat != null) {
            extractGraphViaLlm(documents, config, targetGraph, job);
        } else {
            log.warn("No GraphConstructor or LLMChat available, skipping graph extraction");
        }
    }

    /**
     * Delegates extraction to GraphConstructor which handles persistence and embedding.
     */
    private void extractGraphViaConstructor(List<Document> documents,
                                             GraphExtractionConfig config,
                                             Graph targetGraph,
                                             UnifiedCrawlJob job) {
        GraphSchema schema = buildGraphSchema(config);
        SchemaEnforcementMode mode = config.getSchemaMode() != null
                ? config.getSchemaMode()
                : SchemaEnforcementMode.LENIENT;

        // Convert all documents → RetrievedDoc, then send all at once to constructGraphFromDocs.
        // The MatrixGraphConstructor handles parallelism internally (now 8 concurrent LLM threads).
        // BulkGraphSyncService handles JPA sync in <50ms even for 500+ entities, so batching for
        // DB performance is no longer needed.
        List<RetrievedDoc> allRetrievedDocs = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;
            allRetrievedDocs.add(toRetrievedDoc(doc));
        }

        if (allRetrievedDocs.isEmpty()) return;

        int batchSize = resolveGraphExtractionBatchSize(config);
        int totalDocs = allRetrievedDocs.size();
        List<CostBatch<RetrievedDoc>> batches = planCostBatches(
                allRetrievedDocs,
                doc -> estimateTextCost(doc.getText(), doc.getMetadata()),
                batchSize,
                Math.max(1, graphExtractionTargetCharsPerBatch),
                costSortChunks);
        allRetrievedDocs.clear();
        int totalBatches = batches.size();
        int parallelism = resolveGraphExtractionParallelism(job, totalBatches);
        OuterParallelismAdvisor outerAdvisor = new OuterParallelismAdvisor(parallelism);
        resetGraphExtractionProgress(job, totalDocs);
        updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, totalDocs, 0, 0, totalBatches, 0, null,
                "Planned graph extraction batches");
        job.getCurrentFile().set("(graph extraction: " + totalDocs
                + " chunks in " + totalBatches + " cost-balanced batch(es))");
        recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                "Planned graph extraction batches",
                "chunks=" + totalDocs + ", batches=" + totalBatches
                        + ", maxBatchSize=" + batchSize + ", targetChars=" + graphExtractionTargetCharsPerBatch
                        + ", parallelism=" + parallelism);
        log.info("[Job {}] Starting graph extraction for {} chunks in {} cost-balanced batch(es), parallelism={}, maxItems={}, targetChars={}",
                job.getJobId(), totalDocs, totalBatches, parallelism, batchSize,
                graphExtractionTargetCharsPerBatch);
        trimNativeMemory(job, "GRAPH_EXTRACTION", "after planning graph batches");

        try {
            java.util.concurrent.ExecutorService extractExec = java.util.concurrent.Executors.newFixedThreadPool(
                    parallelism,
                    r -> {
                        Thread t = new Thread(r, "unified-crawl-graph-" + job.getJobId().substring(0, 8));
                        t.setDaemon(true);
                        return t;
                    });
            try {
                List<Future<GraphBatchResult>> futures = new ArrayList<>(batches.size());
                AtomicInteger completedBatches = new AtomicInteger(0);
                for (CostBatch<RetrievedDoc> batch : batches) {
                    if (isCancelled(job)) {
                        return;
                    }
                    futures.add(extractExec.submit(() -> {
                        long batchStartTime = System.currentTimeMillis();
                        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, "GRAPH_EXTRACTION");
                        step.getActiveTasks().incrementAndGet();
                        step.setLastUpdatedAt(Instant.now());
                        try {
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled before batch start");
                                return new GraphBatchResult(batch, null, null);
                            }
                            waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled while waiting for memory");
                                return new GraphBatchResult(batch, null, null);
                            }
                            job.getCurrentBatchSize().set(batch.items().size());
                            job.getCurrentBatchStep().set("GRAPH_BATCH " + batch.index() + "/" + totalBatches);
                            for (RetrievedDoc item : batch.items()) {
                                recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                                        "LLM graph batch " + batch.index() + "/" + totalBatches,
                                        null, List.of("GraphConstructor"), false);
                            }
                            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                                    "Extracting graph batch " + batch.index() + "/" + totalBatches,
                                    batch.items().size() + " chunk(s), cost=" + batch.cost());
                            Map<String, RetrievedDoc> batchDocsById = batch.items().stream()
                                    .filter(item -> item.getId() != null)
                                    .collect(Collectors.toMap(RetrievedDoc::getId, item -> item, (left, right) -> left));
                            Set<String> terminalDocIds = ConcurrentHashMap.newKeySet();
                            GraphConstructor.ProgressListener progressListener = progress ->
                                    recordGraphConstructorProgress(job, batch, batchDocsById, totalBatches,
                                            progress, terminalDocIds);
                            Graph graph = graphConstructor.constructGraphFromDocs(batch.items(), schema, mode,
                                    graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph,
                                    progressListener);

                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled; discarding batch result");
                                return new GraphBatchResult(batch, null, null);
                            }

                            if (graph != null) {
                                GraphPersistResult persisted = persistConstructedGraphBatch(job, graph, batch.items(), config);
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
                                int entities = graph.getEntities() != null ? graph.getEntities().size() : 0;
                                int rels = graph.getRelationships() != null ? graph.getRelationships().size() : 0;
                                if (terminalDocIds.isEmpty()) {
                                    job.getEntitiesExtracted().addAndGet(entities);
                                    job.getRelationshipsExtracted().addAndGet(rels);
                                }
                                log.info("[Job {}] Graph extraction batch {}/{} complete: {} entities, {} rels (totals: {}/{})",
                                        job.getJobId(), batch.index(), totalBatches, entities, rels,
                                        job.getEntitiesExtracted().get(), job.getRelationshipsExtracted().get());
                                if (persisted.entities() > 0 || persisted.relationships() > 0) {
                                    recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                            "Persisted semantic graph batch " + batch.index() + "/" + totalBatches,
                                            persisted.entities() + " entities, " + persisted.relationships()
                                                    + " relationships written to fact-sheet graph");
                                }
                            }

                            int missingTerminalUpdates = Math.max(0, batch.items().size() - terminalDocIds.size());
                            if (missingTerminalUpdates > 0) {
                                incrementGraphChunksProcessed(job, missingTerminalUpdates);
                            }
                            int processed = normalizeGraphChunksProcessed(job);
                            int done = completedBatches.incrementAndGet();
                            UnifiedCrawlJob.PipelineStepProgress graphStepForUpdate = ensurePipelineStep(job, "GRAPH_EXTRACTION");
                            updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                    processed, totalDocs, graphStepForUpdate.getFailedItems().get(),
                                    done, totalBatches, 0, null,
                                    "Completed graph batch " + batch.index() + "/" + totalBatches);
                            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                                    "Completed graph batch " + batch.index() + "/" + totalBatches,
                                    processed + "/" + totalDocs + " chunk(s)");
                            // Notify outer advisor of batch completion for adaptive parallelism
                            updateMemorySnapshot(job);
                            double heapPct = job.getMemoryUsagePercent().get() / 100.0;
                            long batchElapsed = System.currentTimeMillis() - batchStartTime;
                            outerAdvisor.afterBatchComplete(batchElapsed, heapPct);
                            job.getCurrentBatchStep().set("GRAPH_BATCHES " + done + "/" + totalBatches
                                    + " parallelism=" + outerAdvisor.getCurrentParallelism());
                            recordEvent(job, "GRAPH_EXTRACTION", "INFO",
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
                            trimNativeMemory(job, "GRAPH_EXTRACTION",
                                    "after graph batch " + batch.index() + "/" + totalBatches);
                            step.getActiveTasks().updateAndGet(v -> Math.max(0, v - 1));
                            step.setLastUpdatedAt(Instant.now());
                        }
                    }));
                }

                for (int i = 0; i < futures.size(); i++) {
                    Future<GraphBatchResult> extractFuture = futures.get(i);
                    CostBatch<RetrievedDoc> plannedBatch = batches.get(i);
                    if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                        cancelGraphFutures(futures);
                        recordCancelledGraphBatch(job, plannedBatch, "Graph extraction cancelled");
                        break;
                    }
                    try {
                        extractFuture.get(2700, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        extractFuture.cancel(true);
                        log.warn("[Job {}] Graph extraction batch {}/{} timed out after 45min",
                                job.getJobId(), plannedBatch.index(), totalBatches);
                        job.getErrors().add("Graph extraction batch " + plannedBatch.index() + "/" + totalBatches
                                + " timed out after 45min");
                        job.getErrorCount().incrementAndGet();
                        for (RetrievedDoc item : plannedBatch.items()) {
                            recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction batch timed out", "Timed out after 45min",
                                    List.of("GraphConstructor"), true);
                        }
                        recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                "Graph extraction batch timed out",
                                "batch=" + plannedBatch.index() + "/" + totalBatches);
                        continue;
                    } catch (java.util.concurrent.ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        log.warn("[Job {}] Graph extraction batch {}/{} failed: {}",
                                job.getJobId(), plannedBatch.index(), totalBatches, cause.getMessage());
                        if (isFatalLlmUnavailable(cause)) {
                            cancelGraphFutures(futures);
                            String fatalMessage = "Fatal graph extraction LLM failure: " + cause.getMessage();
                            job.getErrors().add(fatalMessage);
                            job.getErrorCount().incrementAndGet();
                            failPipelineStep(job, "GRAPH_EXTRACTION", fatalMessage);
                            recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                    "Fatal graph extraction LLM failure",
                                    fatalMessage);
                            throw new IllegalStateException(fatalMessage, cause);
                        }
                        job.getErrors().add("Graph extraction batch " + plannedBatch.index() + "/" + totalBatches
                                + " failed: " + cause.getMessage());
                        job.getErrorCount().incrementAndGet();
                        for (RetrievedDoc item : plannedBatch.items()) {
                            recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction batch failed", cause.getMessage(),
                                    List.of("GraphConstructor"), true);
                        }
                        recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                "Graph extraction batch failed",
                                "batch=" + plannedBatch.index() + "/" + totalBatches + ", error=" + cause.getMessage());
                        int processed = incrementGraphChunksProcessed(job, plannedBatch.items().size());
                        int done = completedBatches.incrementAndGet();
                        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, "GRAPH_EXTRACTION");
                        int failed = step.getFailedItems().incrementAndGet();
                        updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                processed, totalDocs, failed,
                                done, totalBatches, 0, null,
                                "Failed graph batch " + plannedBatch.index() + "/" + totalBatches);
                        plannedBatch.items().clear();
                        continue;
                    } catch (java.util.concurrent.CancellationException ce) {
                        if (isCancelled(job)) {
                            recordCancelledGraphBatch(job, plannedBatch, "Graph extraction batch cancelled");
                            break;
                        }
                        throw ce;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        cancelGraphFutures(futures);
                        recordCancelledGraphBatch(job, plannedBatch, "Graph extraction interrupted");
                        recordEvent(job, "GRAPH_EXTRACTION", "WARN", "Graph extraction interrupted",
                                "batch=" + plannedBatch.index() + "/" + totalBatches);
                        return;
                    }
                }
            } finally {
                extractExec.shutdownNow();
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

    private void releaseInMemoryGraph(Graph graph) {
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
        String documentKey = item != null ? documentKey(item) : progress.documentId();
        if (documentKey == null || documentKey.isBlank()) {
            documentKey = "graph-doc-" + Math.max(0, progress.documentIndex());
        }
        String fileName = item != null
                ? documentFileName(item.getMetadata(), item.getId())
                : documentKey;
        int ordinal = Math.max(0, progress.documentIndex()) + 1;
        int total = Math.max(1, progress.totalDocuments());
        int batchIndex = batch != null ? batch.index() : 0;
        String batchLabel = batchIndex > 0
                ? "batch " + batchIndex + "/" + totalBatches
                : "graph extraction";

        job.getCurrentPhase().set("GRAPH_EXTRACTION");
        job.getCurrentFile().set(fileName);
        job.getProgressPercent().accumulateAndGet(estimateProgressForPhase(job, "GRAPH_EXTRACTION"), Math::max);
        updateMemorySnapshot(job);
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, "GRAPH_EXTRACTION");

        if (progress.status() == GraphConstructor.DocumentExtractionStatus.STARTED) {
            String message = "LLM graph extraction " + ordinal + "/" + total + " started (" + batchLabel + ")";
            job.getCurrentBatchStep().set("GRAPH_CHUNK " + ordinal + "/" + total + " (" + batchLabel + ")");
            updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    normalizeGraphChunksProcessed(job), graphChunksTotal(job),
                    step.getFailedItems().get(), step.getCompletedBatches().get(), step.getTotalBatches().get(),
                    1, fileName, message);
            recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                    message, null, List.of("GraphConstructor"), true);
            return;
        }

        boolean failed = progress.status() == GraphConstructor.DocumentExtractionStatus.FAILED
                || progress.status() == GraphConstructor.DocumentExtractionStatus.TIMED_OUT;
        String terminalKey = progress.documentId() != null ? progress.documentId() : documentKey;
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
        updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                processed, graphChunksTotal(job),
                step.getFailedItems().get() + (failed ? 1 : 0),
                step.getCompletedBatches().get(), step.getTotalBatches().get(),
                1, fileName, message);
        recordDocumentProgress(job, item, "GRAPH_EXTRACTION", failed ? "FAILED" : "COMPLETED", 0,
                failed ? 0 : Math.max(0, progress.entities()),
                failed ? 0 : Math.max(0, progress.relationships()),
                message, failed ? details : null, List.of("GraphConstructor"), true);
        recordEvent(job, "GRAPH_EXTRACTION", failed ? "ERROR" : "INFO",
                fileName + ": " + message, details);
    }

    private void recordCancelledGraphBatch(UnifiedCrawlJob job,
                                           CostBatch<RetrievedDoc> batch,
                                           String message) {
        if (batch == null || batch.items() == null) {
            return;
        }
        for (RetrievedDoc item : batch.items()) {
            recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "CANCELLED", 0, 0, 0,
                    message, null, List.of("GraphConstructor"), true);
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
        recordEvent(job, "GRAPH_EXTRACTION", "ERROR", message, detail);
        log.warn("[Job {}] {}: {}", job.getJobId(), message, detail);
    }

    private void recordGraphExtractionBatchPerDocument(UnifiedCrawlJob job,
                                                       Graph graph,
                                                       CostBatch<RetrievedDoc> batch,
                                                       int totalBatches) {
        Map<String, int[]> countsByDocId = new HashMap<>();
        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                String docId = sourceDocumentId(entity.getMetadata());
                if (docId != null) {
                    countsByDocId.computeIfAbsent(docId, ignored -> new int[2])[0]++;
                }
            }
        }
        if (graph.getRelationships() != null) {
            for (Relationship relationship : graph.getRelationships()) {
                String docId = sourceDocumentId(relationship.getMetadata());
                if (docId != null) {
                    countsByDocId.computeIfAbsent(docId, ignored -> new int[2])[1]++;
                }
            }
        }

        for (RetrievedDoc item : batch.items()) {
            int[] counts = countsByDocId.getOrDefault(item.getId(), new int[2]);
            recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "COMPLETED", 0,
                    counts[0], counts[1],
                    "LLM graph batch " + batch.index() + "/" + totalBatches + " complete",
                    null, List.of("GraphConstructor"), true);
        }
    }

    private GraphPersistResult persistConstructedGraphBatch(UnifiedCrawlJob job,
                                                            Graph graph,
                                                            Collection<RetrievedDoc> batchDocs,
                                                            GraphExtractionConfig config) {
        if (knowledgeGraphService == null || job == null || graph == null || isCancelled(job)) {
            return GraphPersistResult.empty();
        }

        String jobId = job.getJobId();
        Long factSheetId = jobFactSheetId(job);
        Map<String, RetrievedDoc> docsById = new HashMap<>();
        if (batchDocs != null) {
            for (RetrievedDoc doc : batchDocs) {
                if (doc != null && doc.getId() != null) {
                    docsById.put(doc.getId(), doc);
                }
            }
        }

        Map<String, String> externalToNodeId = new HashMap<>();
        int entitiesPersisted = 0;
        int relationshipsPersisted = 0;

        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                if (isCancelled(job)) {
                    return new GraphPersistResult(entitiesPersisted, relationshipsPersisted);
                }
                if (entity == null || belowMinConfidence(entity.getConfidence(), config)) {
                    continue;
                }
                try {
                    RetrievedDoc sourceDoc = graphEntitySourceDoc(entity, docsById, batchDocs);
                    String sourcePath = graphEntitySourcePath(entity, sourceDoc, batchDocs);
                    Optional<GraphNode> parentDoc = findOrCreateDocumentNode(job, sourcePath, sourceDoc, factSheetId);
                    if (factSheetId == null && parentDoc.isPresent()) {
                        factSheetId = parentDoc.get().getFactSheetId();
                    }

                    String externalId = graphConstructorEntityExternalId(entity, sourcePath);
                    Map<String, Object> entityMeta = new LinkedHashMap<>();
                    if (entity.getMetadata() != null) {
                        entityMeta.putAll(entity.getMetadata());
                    }
                    entityMeta.put("entity_type", safeEntityType(entity.getType()));
                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                    entityMeta.put("extraction_method", "graph_constructor");
                    String sourceDocumentId = sourceDocumentId(entity.getMetadata());
                    if (sourceDocumentId != null) {
                        entityMeta.put("sourceDocumentId", sourceDocumentId);
                    }
                    if (sourcePath != null) {
                        entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                    }
                    if (entity.getTextUnits() != null && !entity.getTextUnits().isEmpty()) {
                        entityMeta.put("textUnits", new ArrayList<>(entity.getTextUnits()));
                    }
                    Double confidence = finiteDouble(entity.getConfidence());
                    if (confidence != null) {
                        entityMeta.put("confidence", confidence);
                    }

                    GraphNode node = knowledgeGraphService.createNode(
                            NodeLevel.ENTITY,
                            externalId,
                            firstNonBlank(entity.getTitle(), entity.getId(), "Entity"),
                            entity.getDescription(),
                            entityMeta,
                            factSheetId);
	                    entitiesPersisted++;
	                    job.incrementEntityType(safeEntityType(entity.getType()));
	                    externalToNodeId.put(externalId, node.getNodeId());
	                    if (entity.getId() != null && !entity.getId().isBlank()) {
	                        externalToNodeId.put(entity.getId(), node.getNodeId());
	                    }

	                    if (parentDoc.isPresent()) {
	                        recordEntityMention(parentDoc.get(),
	                                firstNonBlank(entity.getTitle(), entity.getId(), "Entity"),
	                                safeEntityType(entity.getType()),
	                                confidence,
	                                factSheetId,
	                                "graph_constructor",
	                                sourcePath,
	                                sourceDocumentId);
	                        String label = semanticRelationLabel(GraphConstants.REL_CONTAINS);
	                        String description = semanticRelationDescription(
	                                "Document contains " + safeEntityType(entity.getType()) + " "
	                                        + firstNonBlank(entity.getTitle(), entity.getId(), "entity"),
                                label);
                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                "graph_constructor", sourcePath, externalId, label, description,
                                confidence,
                                metadataProperties(
                                        "entityType", entity.getType(),
                                        "entityName", firstNonBlank(entity.getTitle(), entity.getId(), "Entity"),
                                        "sourceDocumentId", sourceDocumentId));
                        knowledgeGraphService.createEdgeWithMetadata(parentDoc.get().getNodeId(), node.getNodeId(),
                                EdgeType.CONTAINS, 1.0, label, description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                    }
                } catch (Exception e) {
                    log.debug("[Job {}] Failed to persist GraphConstructor entity '{}': {}",
                            jobId, entity.getTitle(), e.getMessage());
                }
            }
        }

        if (graph.getRelationships() != null) {
            for (Relationship rel : graph.getRelationships()) {
                if (isCancelled(job)) {
                    return new GraphPersistResult(entitiesPersisted, relationshipsPersisted);
                }
                if (rel == null || belowMinConfidence(rel.getConfidence(), config)) {
                    continue;
                }
                try {
                    String srcNodeId = externalToNodeId.get(rel.getSource());
                    if (srcNodeId == null) {
                        srcNodeId = resolveGraphConstructorNodeId(rel.getSource(), factSheetId);
                    }
                    String tgtNodeId = externalToNodeId.get(rel.getTarget());
                    if (tgtNodeId == null) {
                        tgtNodeId = resolveGraphConstructorNodeId(rel.getTarget(), factSheetId);
                    }
                    if (srcNodeId == null || tgtNodeId == null) {
                        continue;
                    }

                    RetrievedDoc sourceDoc = docsById.get(sourceDocumentId(rel.getMetadata()));
                    String sourcePath = graphRelationshipSourcePath(rel, sourceDoc, batchDocs);
                    String label = semanticRelationLabel(rel.getType());
                    String description = semanticRelationDescription(rel.getDescription(), label);
                    Double confidence = finiteDouble(rel.getConfidence());
                    Double weight = finiteDouble(rel.getWeight());
                    Map<String, Object> relMeta = new LinkedHashMap<>();
                    if (rel.getMetadata() != null) {
                        relMeta.putAll(rel.getMetadata());
                    }
                    relMeta.put("relationshipType", label);
                    relMeta.put("extractionMethod", "graph_constructor");
                    if (sourcePath != null) {
                        relMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                    }
                    if (weight != null) {
                        relMeta.put("weight", weight);
                    }

                    String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                            "graph_constructor", rel.getSource(), rel.getTarget(), label, description,
                            confidence, relMeta);
                    knowledgeGraphService.createEdgeWithMetadata(srcNodeId, tgtNodeId,
                            EdgeType.USER_DEFINED,
                            confidence != null ? confidence : weight != null ? weight : 1.0,
                            label, description, metaJson,
                            EdgeProvenance.EXTRACTED, factSheetId);
                    relationshipsPersisted++;
                    job.incrementRelationshipType(label);
                } catch (Exception e) {
                    log.debug("[Job {}] Failed to persist GraphConstructor relation '{}': {}",
                            jobId, rel.getType(), e.getMessage());
                }
            }
        }

        return new GraphPersistResult(entitiesPersisted, relationshipsPersisted);
    }

    private RetrievedDoc graphEntitySourceDoc(Entity entity,
                                              Map<String, RetrievedDoc> docsById,
                                              Collection<RetrievedDoc> batchDocs) {
        if (entity != null) {
            String sourceDocumentId = sourceDocumentId(entity.getMetadata());
            if (sourceDocumentId != null && docsById != null && docsById.containsKey(sourceDocumentId)) {
                return docsById.get(sourceDocumentId);
            }
            if (entity.getTextUnits() != null && docsById != null) {
                for (String textUnit : entity.getTextUnits()) {
                    if (textUnit != null && docsById.containsKey(textUnit)) {
                        return docsById.get(textUnit);
                    }
                }
            }
        }
        if (batchDocs != null && batchDocs.size() == 1) {
            return batchDocs.iterator().next();
        }
        return null;
    }

    private String graphEntitySourcePath(Entity entity,
                                         RetrievedDoc sourceDoc,
                                         Collection<RetrievedDoc> batchDocs) {
        String sourcePath = entity != null
                ? stringMeta(entity.getMetadata(), GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
                "source_path", "sourcePath", "source_url", "documentPath", "path")
                : null;
        if ((sourcePath == null || sourcePath.isBlank()) && sourceDoc != null) {
            sourcePath = documentSourcePath(sourceDoc.getMetadata(), sourceDoc.getId());
        }
        if ((sourcePath == null || sourcePath.isBlank()) && batchDocs != null && batchDocs.size() == 1) {
            RetrievedDoc onlyDoc = batchDocs.iterator().next();
            sourcePath = documentSourcePath(onlyDoc.getMetadata(), onlyDoc.getId());
        }
        return sourcePath;
    }

    private String graphRelationshipSourcePath(Relationship rel,
                                               RetrievedDoc sourceDoc,
                                               Collection<RetrievedDoc> batchDocs) {
        String sourcePath = rel != null
                ? stringMeta(rel.getMetadata(), GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
                "source_path", "sourcePath", "source_url", "documentPath", "path")
                : null;
        if ((sourcePath == null || sourcePath.isBlank()) && sourceDoc != null) {
            sourcePath = documentSourcePath(sourceDoc.getMetadata(), sourceDoc.getId());
        }
        if ((sourcePath == null || sourcePath.isBlank()) && batchDocs != null && batchDocs.size() == 1) {
            RetrievedDoc onlyDoc = batchDocs.iterator().next();
            sourcePath = documentSourcePath(onlyDoc.getMetadata(), onlyDoc.getId());
        }
        return sourcePath;
    }

    private Optional<GraphNode> findOrCreateDocumentNode(UnifiedCrawlJob job,
                                                         String sourcePath,
                                                         RetrievedDoc sourceDoc,
                                                         Long factSheetId) {
        if (knowledgeGraphService == null || sourcePath == null || sourcePath.isBlank()) {
            return Optional.empty();
        }
        Optional<GraphNode> existing = knowledgeGraphService.getNodeByExternalId(
                sourcePath, NodeLevel.DOCUMENT, factSheetId);
        if (existing.isPresent()) {
            return existing;
        }

        String jobId = job.getJobId();
        Map<String, Object> metadata = sourceDoc != null && sourceDoc.getMetadata() != null
                ? new LinkedHashMap<>(sourceDoc.getMetadata())
                : new LinkedHashMap<>();
        metadata.putIfAbsent("jobId", jobId);
        metadata.putIfAbsent(GraphConstants.META_SOURCE, sourcePath);
        metadata.putIfAbsent(GraphConstants.META_SOURCE_PATH, sourcePath);
        String sourceType = firstNonBlank(
                stringMeta(metadata, GraphConstants.META_SOURCE_TYPE, "source_type"),
                "FILE");
        String fileName = documentFileName(metadata, sourcePath);
        String preview = sourceDoc != null && sourceDoc.getText() != null
                ? sourceDoc.getText().substring(0, Math.min(200, sourceDoc.getText().length()))
                : null;
        try {
            GraphNode node = knowledgeGraphService.addDocument(
                    "crawl:" + jobId, jobId, sourceType, sourcePath, fileName, preview, metadata, factSheetId);
            return Optional.ofNullable(node);
        } catch (Exception e) {
            log.debug("[Job {}] Failed to create DOCUMENT node for GraphConstructor source '{}': {}",
                    jobId, sourcePath, e.getMessage());
            return knowledgeGraphService.getNodeByExternalId(sourcePath, NodeLevel.DOCUMENT, factSheetId);
        }
    }

    private String resolveGraphConstructorNodeId(String externalId, Long factSheetId) {
        if (externalId == null || externalId.isBlank() || knowledgeGraphService == null) {
            return null;
        }
        return knowledgeGraphService.getNodeByExternalId(externalId, NodeLevel.ENTITY, factSheetId)
                .map(GraphNode::getNodeId)
                .orElse(null);
    }

    private String graphConstructorEntityExternalId(Entity entity, String sourcePath) {
        String entityId = entity != null ? entity.getId() : null;
        if (entityId != null && !entityId.isBlank()) {
            return entityId;
        }
        String title = entity != null ? firstNonBlank(entity.getTitle(), entity.getDescription(), "entity") : "entity";
        String type = entity != null ? safeEntityType(entity.getType()) : "entity";
        return "graph-constructor:" + type + ":" + Integer.toHexString(Objects.hash(sourcePath, title, type));
    }

    private boolean belowMinConfidence(Double confidence, GraphExtractionConfig config) {
        Double value = finiteDouble(confidence);
        return value != null && config != null && value < config.getMinConfidence();
    }

    private Double finiteDouble(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String sourceDocumentId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        for (String key : List.of("sourceDocumentId", "source_document_id", "documentId", "document_id")) {
            Object value = metadata.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Fallback: inline LLM extraction when no GraphConstructor is available.
     * Entities are persisted to KnowledgeGraphService and also stored in-memory on the job's result graph.
     */
    private void extractGraphViaLlm(List<Document> documents,
                                     GraphExtractionConfig config,
        Graph targetGraph,
                                     UnifiedCrawlJob job) {
        String extractionPrompt = buildExtractionPrompt(config);
        resetGraphExtractionProgress(job, documents.size());

        List<CostBatch<Document>> batches = planCostBatches(
                documents,
                this::estimateDocumentCost,
                resolveGraphExtractionBatchSize(config),
                Math.max(1, graphExtractionTargetCharsPerBatch),
                costSortChunks);
        int parallelism = resolveGraphExtractionParallelism(job, batches.size());
        updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, documents.size(), 0, 0, batches.size(), 0, null,
                "Planned inline LLM extraction batches");
        recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                "Planned inline LLM extraction batches",
                "chunks=" + documents.size() + ", batches=" + batches.size()
                        + ", parallelism=" + parallelism + ", targetChars=" + graphExtractionTargetCharsPerBatch);

        if (parallelism <= 1 || batches.size() <= 1) {
            for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
                if (isCancelled(job)) return;
                extractGraphViaLlmDocument(documents.get(docIndex), docIndex, documents.size(),
                        extractionPrompt, config, targetGraph, job);
                trimNativeMemory(job, "GRAPH_EXTRACTION",
                        "after inline graph chunk " + (docIndex + 1) + "/" + documents.size());
            }
            job.getCurrentBatchStep().set(null);
            return;
        }

        ExecutorService llmExec = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "unified-crawl-llm-" + job.getJobId().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(batches.size());
            AtomicInteger offset = new AtomicInteger(0);
            for (CostBatch<Document> batch : batches) {
                int baseIndex = offset.getAndAdd(batch.items().size());
                futures.add(llmExec.submit(() -> {
                    waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                    job.getCurrentBatchSize().set(batch.items().size());
                    job.getCurrentBatchStep().set("LLM_BATCH " + batch.index() + "/" + batches.size());
                    for (int i = 0; i < batch.items().size(); i++) {
                        if (isCancelled(job)) break;
                        extractGraphViaLlmDocument(batch.items().get(i), baseIndex + i, documents.size(),
                                extractionPrompt, config, targetGraph, job);
                        trimNativeMemory(job, "GRAPH_EXTRACTION",
                                "after inline graph chunk " + (baseIndex + i + 1) + "/" + documents.size());
                    }
                    updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                            "Completed inline LLM batch " + batch.index() + "/" + batches.size(),
                            batch.items().size() + " chunk(s), cost=" + batch.cost());
                    incrementPipelineStep(job, "GRAPH_EXTRACTION", 0, 1,
                            "Completed inline LLM batch " + batch.index() + "/" + batches.size());
                }));
            }
            for (Future<?> future : futures) {
                if (isCancelled(job)) break;
                try {
                    future.get(2700, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(false);
                    job.getErrors().add("Inline LLM extraction batch timed out after 45min");
                    job.getErrorCount().incrementAndGet();
                    recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                            "Inline LLM extraction batch timed out", null);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("[Job {}] Inline LLM extraction batch failed: {}",
                            job.getJobId(), cause.getMessage());
                    job.getErrors().add("Inline LLM extraction batch failed: " + cause.getMessage());
                    job.getErrorCount().incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            llmExec.shutdownNow();
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
    }

    private void extractGraphViaLlmDocument(Document doc,
                                            int docIndex,
                                            int totalDocuments,
                                            String extractionPrompt,
                                            GraphExtractionConfig config,
                                            Graph targetGraph,
                                            UnifiedCrawlJob job) {
        String jobId = job.getJobId();
        if (isCancelled(job)) return;
        waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
        job.getCurrentBatchStep().set("GRAPH_CHUNK " + (docIndex + 1) + "/" + totalDocuments);
        updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                "Extracting graph from chunk " + (docIndex + 1) + "/" + totalDocuments, null);
        recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                "Inline LLM graph extraction " + (docIndex + 1) + "/" + totalDocuments,
                null, List.of("InlineLLM"), false);

        boolean recordedResult = false;
        try {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "SKIPPED", 0, 0, 0,
                        "Skipped blank chunk", null, List.of("InlineLLM"), false);
                return;
            }

            // VLM-extracted documents may contain valuable structural markup;
            // allow more text through for better entity/relationship extraction.
            boolean isVlmContent = doc.getMetadata() != null
                    && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            int maxLength = isVlmContent ? 16000 : 12000;

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
            String response = promptWithCapacityFallback(fullPrompt, "llm", job);

            if (response != null && !response.isBlank()) {
                String json = extractJsonFromResponse(response);
                if (json != null) {
                    GraphExtractionSchema.ExtractionResult result = GraphExtractionValidator.fromJson(json);
                    var validation = GraphExtractionValidator.validate(result);
                    if (validation.valid()) {
                        Graph chunkGraph = GraphExtractionValidator.toGraph(result);
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
                        recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "COMPLETED", 0,
                                entityCount, relationshipCount,
                                "Inline LLM graph extraction complete", null, List.of("InlineLLM"), true);
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
	                            Long factSheetId = jobFactSheetId(jobId);
	                            if (sourcePath != null) {
	                                Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
	                                        sourcePath, NodeLevel.DOCUMENT, factSheetId);
	                                if (docNode.isPresent()) {
	                                    parentDocumentNode = docNode.get();
	                                    parentNodeId = parentDocumentNode.getNodeId();
	                                    if (factSheetId == null) {
	                                        factSheetId = parentDocumentNode.getFactSheetId();
	                                    }
	                                }
	                            }

                            Map<String, String> externalToNodeId = new HashMap<>();
                            for (var entity : result.entities()) {
                                try {
                                    Map<String, Object> entityMeta = new LinkedHashMap<>();
                                    entityMeta.put("entity_type", entity.type());
                                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                                    entityMeta.put("extraction_method", "llm");
                                    if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                                    if (entity.properties() != null) entityMeta.putAll(entity.properties());

                                    Optional<GraphNode> existing = knowledgeGraphService.getNodeByExternalId(
                                            entity.id(), NodeLevel.ENTITY, factSheetId);
                                    GraphNode node;
                                    if (existing.isPresent()) {
                                        node = existing.get();
                                    } else {
                                        node = knowledgeGraphService.createNode(NodeLevel.ENTITY, entity.id(),
                                                entity.name(), entity.description(), entityMeta, factSheetId);
                                    }
	                                    externalToNodeId.put(entity.id(), node.getNodeId());
	                                    job.incrementEntityType(safeEntityType(entity.type()));

	                                    if (parentNodeId != null) {
	                                        recordEntityMention(parentDocumentNode,
	                                                entity.name(),
	                                                safeEntityType(entity.type()),
	                                                entity.confidence(),
	                                                factSheetId,
	                                                "inline_llm",
	                                                sourcePath,
	                                                null);
	                                        String label = semanticRelationLabel(GraphConstants.REL_CONTAINS);
	                                        String description = semanticRelationDescription(
	                                                "Document contains " + safeEntityType(entity.type()) + " " + entity.name(), label);
                                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                                "inline_llm", sourcePath, entity.id(), label, description, null,
                                                metadataProperties(
                                                        "entityType", entity.type(),
                                                        "entityName", entity.name()));
                                        knowledgeGraphService.createEdgeWithMetadata(parentNodeId, node.getNodeId(),
                                                EdgeType.CONTAINS, 1.0, label, description, metaJson,
                                                EdgeProvenance.EXTRACTED, factSheetId);
                                    }
                                } catch (Exception e) {
                                    log.debug("[Job {}] Failed to persist LLM entity '{}': {}", jobId, entity.name(), e.getMessage());
                                }
                            }

                            if (result.relations() != null) {
                                for (var rel : result.relations()) {
                                    try {
                                        String srcNodeId = externalToNodeId.get(rel.source());
                                        String tgtNodeId = externalToNodeId.get(rel.target());
                                        if (srcNodeId == null || tgtNodeId == null) continue;
                                        String label = semanticRelationLabel(rel.type());
                                        String description = semanticRelationDescription(rel.description(), label);
                                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                                "inline_llm", rel.source(), rel.target(), label, description,
                                                rel.confidence(), rel.properties());
                                        knowledgeGraphService.createEdgeWithMetadata(srcNodeId, tgtNodeId,
                                                EdgeType.USER_DEFINED,
                                                rel.confidence() != null ? rel.confidence() : 1.0,
                                                label, description, metaJson,
                                                EdgeProvenance.EXTRACTED, factSheetId);
                                        job.incrementRelationshipType(label);
                                    } catch (Exception e) {
                                        log.debug("[Job {}] Failed to persist LLM relation '{}': {}", jobId, rel.type(), e.getMessage());
                                    }
                                }
                            }
                        }
                    } else {
                        log.debug("Graph extraction validation failed: {}", validation.errors());
                        recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                "Graph extraction validation failed", String.join("; ", validation.errors()),
                                List.of("InlineLLM"), true);
                        recordedResult = true;
                    }
                }
            }
            if (!recordedResult) {
                // LLM returned null or empty — this is a failure, not a quiet success
                job.getErrorCount().incrementAndGet();
                recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                        "LLM graph extraction returned no result",
                        "LLM returned null/empty — check LLM configuration",
                        List.of("InlineLLM"), true);
            }

        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.warn("[Job {}] Graph extraction failed for document: {}", job.getJobId(), errorDetail, e);
            job.getErrors().add("Graph extraction failed: " + errorDetail);
            job.getErrorCount().incrementAndGet();
            recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                    "Graph extraction failed", errorDetail, List.of("InlineLLM"), true);
        } finally {
            incrementGraphChunksProcessed(job, 1);
            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                    "Completed graph chunk " + (docIndex + 1) + "/" + totalDocuments, null);
        }
    }

    private void indexDocuments(List<Document> documents,
                               VectorIndexConfig config,
                               UnifiedCrawlJob job) {
        try {
            updateProgress(job, "EMBEDDING", estimateProgress(job),
                    "Preparing vector indexing", documents.size() + " chunk(s)");
            // Switch to target collection if specified
            if (config.getCollectionName() != null && !config.getCollectionName().isBlank()) {
                vectorStore.switchIndexPath(config.getCollectionName());
            }

            int batchSize = resolveEmbeddingBatchSize(config, job);
            int totalBatches = (int) Math.ceil(documents.size() / (double) batchSize);
            job.getChunksQueuedForEmbedding().set(documents.size());
            job.getVectorBatchesTotal().set(totalBatches);
            job.getVectorBatchesCompleted().set(0);
            job.getEmbeddingBatchSize().set(batchSize);
            updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, documents.size(), 0, 0, totalBatches, 0, null,
                    "Prepared " + totalBatches + " embedding/indexing batch(es)");

            for (int i = 0; i < documents.size();) {
                if (isCancelled(job)) return;
                waitForMemoryCapacity(job, "EMBEDDING");

                int adaptiveBatchSize = config.isAdaptiveBatching()
                        ? adaptBatchSizeForMemory(batchSize, job)
                        : batchSize;
                int end = Math.min(i + adaptiveBatchSize, documents.size());
                List<Document> batch = new ArrayList<>(documents.subList(i, end));
                int batchNumber = job.getVectorBatchesCompleted().get() + 1;
                job.getCurrentBatchSize().set(batch.size());
                job.getCurrentBatchStep().set("EMBEDDING_BATCH " + batchNumber + "/" + totalBatches);
                updateProgress(job, "EMBEDDING", estimateProgress(job),
                        "Embedding/indexing batch " + batchNumber + "/" + totalBatches,
                        batch.size() + " chunk(s), effectiveBatchSize=" + adaptiveBatchSize);
                for (Document document : batch) {
                    recordDocumentVectorProgress(job, document, "RUNNING", 0, 0,
                            "Embedding/indexing batch " + batchNumber + "/" + totalBatches,
                            null, false);
                }

                int indexed;
                try {
                    indexed = addVectorBatch(batch);
                    for (int docIndex = 0; docIndex < batch.size(); docIndex++) {
                        boolean indexedDocument = docIndex < indexed;
                        recordDocumentVectorProgress(job, batch.get(docIndex),
                                indexedDocument ? "COMPLETED" : "FAILED",
                                indexedDocument ? 1 : 0,
                                indexedDocument ? 1 : 0,
                                indexedDocument
                                        ? "Vector indexed in batch " + batchNumber + "/" + totalBatches
                                        : "Vector store accepted fewer documents than were embedded",
                                indexedDocument ? null : "Vector store accepted " + indexed + "/" + batch.size()
                                        + " document(s) for this batch",
                                !indexedDocument);
                    }
                    // Mark successfully vector-indexed passages in cross-index tracker
                    markBatchVectorIndexedInCrossIndex(batch, indexed);
                    job.getChunksEmbedded().addAndGet(indexed);
                    job.getDocumentsIndexed().addAndGet(indexed);
                    job.getVectorBatchesCompleted().incrementAndGet();
                    incrementPipelineStep(job, "VECTOR_INDEXING", indexed, 1,
                            "Indexed vector batch " + batchNumber + "/" + totalBatches);
                    updateProgress(job, "INDEXING", estimateProgress(job),
                            "Indexed vector batch " + batchNumber + "/" + totalBatches,
                            indexed + " chunk(s)");
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    for (Document document : batch) {
                        recordDocumentVectorProgress(job, document, "FAILED", 0, 0,
                                "Vector indexing failed in batch " + batchNumber + "/" + totalBatches,
                                errorDetail, true);
                    }
                    throw e;
                } finally {
                    batch.clear();
                    trimNativeMemory(job, "EMBEDDING",
                            "after vector batch " + batchNumber + "/" + totalBatches);
                }
                i = end;
            }

            job.getCurrentBatchStep().set("COMMITTING");
            updateProgress(job, "INDEXING", estimateProgress(job),
                    "Committing vector store", null);
            vectorStore.flushAndCommit();
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);

            // Update document-level vector status in cross-index tracker
            markDocumentsVectorIndexedInCrossIndex(job, documents);

            updateProgress(job, "INDEXING", estimateProgress(job),
                    "Vector indexing complete", job.getDocumentsIndexed().get() + " chunk(s)");
            completePipelineStep(job, "VECTOR_INDEXING", job.getDocumentsIndexed().get(),
                    job.getDocumentsIndexed().get() + " chunk(s) indexed");
            log.info("Indexed {} documents to vector store", job.getDocumentsIndexed().get());
        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.error("Vector indexing failed: {}", errorDetail, e);
            job.getErrorCount().incrementAndGet();
            job.getErrors().add("Vector indexing failed: " + e.getClass().getSimpleName() + ": " + errorDetail);
            failPipelineStep(job, "VECTOR_INDEXING", "Vector indexing failed: " + errorDetail);
            recordEvent(job, "INDEXING", "ERROR", "Vector indexing failed",
                    e.getClass().getSimpleName() + ": " + errorDetail);
            throw new RuntimeException("Vector indexing failed: " + errorDetail, e);
        } finally {
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
    }

    private void waitForBackgroundGraphFuture(UnifiedCrawlJob job, Future<?> future, int index, int total) {
        long lastHeartbeatNanos = 0L;
        while (!isCancelled(job)) {
            try {
                updateProgress(job, "GRAPH_PREP", estimateProgress(job),
                        "Waiting for background graph task " + index + "/" + total, null);
                future.get(15, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                long now = System.nanoTime();
                if (now - lastHeartbeatNanos >= TimeUnit.SECONDS.toNanos(30)) {
                    lastHeartbeatNanos = now;
                    recordEvent(job, "GRAPH_PREP", "INFO",
                            "Still waiting for background graph task " + index + "/" + total,
                            "Rule-based graph preparation remains active");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                updatePipelineStep(job, "GRAPH_PREP", UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                        0, total, 0, 0, 0, 0, null,
                        "Interrupted while waiting for background graph preparation");
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String errorDetail = cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName();
                failPipelineStep(job, "GRAPH_PREP",
                        "Background graph task " + index + "/" + total + " failed: " + errorDetail);
                recordEvent(job, "GRAPH_PREP", "ERROR",
                        "Background graph task failed",
                        "task=" + index + "/" + total + ", error=" + errorDetail);
                return;
            } catch (CancellationException e) {
                updatePipelineStep(job, "GRAPH_PREP", UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                        0, total, 0, 0, 0, 0, null,
                        "Background graph task was cancelled");
                return;
            }
        }
    }

    private int addVectorBatch(List<Document> batch) {
        EmbeddingModel embeddingModel = primaryEmbeddingModel();
        if (embeddingModel == null) {
            throw new IllegalStateException("Embedding model is null during vector indexing — "
                    + "this should have been caught before reaching addVectorBatch. "
                    + batch.size() + " documents NOT indexed.");
        }

        List<String> texts = new ArrayList<>(batch.size());
        List<float[]> embeddings = null;
        float[][] embeddingArray = null;
        try {
            for (Document document : batch) {
                texts.add(indexableDocumentText(document));
            }
            embeddings = embeddingModel.embedBatch(texts);
            if (embeddings == null || embeddings.size() != batch.size()) {
                int actual = embeddings == null ? -1 : embeddings.size();
                throw new IllegalStateException("Embedding model returned " + actual
                        + " embedding(s) for " + batch.size() + " document(s)");
            }
            embeddingArray = new float[embeddings.size()][];
            for (int i = 0; i < embeddings.size(); i++) {
                float[] embedding = embeddings.get(i);
                if (!isIndexableEmbedding(embedding)) {
                    throw new IllegalStateException("Embedding model returned a non-indexable vector for document "
                            + batch.get(i).getId());
                }
                embeddingArray[i] = embedding;
            }
            return vectorStore.addWithFloatArrayEmbeddings(batch, embeddingArray);
        } catch (Exception e) {
            log.error("Embedding batch FAILED for {} documents: {}", batch.size(), e.getMessage(), e);
            throw new IllegalStateException("Embedding batch failed for " + batch.size()
                    + " document(s): " + e.getMessage(), e);
        } finally {
            texts.clear();
            if (embeddings != null) {
                embeddings.clear();
            }
            if (embeddingArray != null) {
                Arrays.fill(embeddingArray, null);
            }
        }
    }

    private String indexableDocumentText(Document document) {
        String text = document != null ? document.getText() : null;
        if (text != null && !text.isBlank()) {
            return text;
        }
        String id = document != null ? document.getId() : null;
        Map<String, Object> metadata = document != null ? document.getMetadata() : null;
        String source = stringMeta(metadata, GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
                "source_path", "sourcePath", "source", "path", "fileName", "file_name", "title");
        return "document " + firstNonBlank(id, source, "chunk");
    }

    private boolean isIndexableEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return false;
        }
        double magnitude = 0.0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                return false;
            }
            magnitude += (double) value * value;
        }
        return Double.isFinite(magnitude) && magnitude > 1e-18;
    }

    private int resolveEmbeddingBatchSize(VectorIndexConfig config, UnifiedCrawlJob job) {
        int optimal = 32;
        int max = 128;
        EmbeddingModel embeddingModel = primaryEmbeddingModel();
        if (embeddingModel != null) {
            try {
                optimal = Math.max(1, embeddingModel.getOptimalBatchSize());
                max = Math.max(optimal, embeddingModel.getMaxBatchSize());
                job.getEmbeddingModelOptimalBatchSize().set(optimal);
                job.getEmbeddingModelMaxBatchSize().set(max);
                job.getEmbeddingSingleDspPlan().set(embeddingModel.isSingleDspPlan());
                job.getEmbeddingDspPlanBatchSize().set(embeddingModel.getDspPlanBatchSize());
            } catch (Exception e) {
                log.debug("Could not read embedding model batch sizes: {}", e.getMessage());
            }
        }

        if (configuredVectorBatchSize > 0) {
            optimal = configuredVectorBatchSize;
        }
        if (config.getEmbeddingBatchSize() != null && config.getEmbeddingBatchSize() > 0) {
            optimal = config.getEmbeddingBatchSize();
        }
        if (config.getMaxEmbeddingBatchSize() != null && config.getMaxEmbeddingBatchSize() > 0) {
            max = Math.min(max, config.getMaxEmbeddingBatchSize());
        }
        int effectiveMax = Math.max(1, max);
        int candidate = Math.max(1, Math.min(optimal, effectiveMax));
        if (candidate < MIN_EMBEDDING_BATCH_SIZE && effectiveMax >= MIN_EMBEDDING_BATCH_SIZE) {
            return MIN_EMBEDDING_BATCH_SIZE;
        }
        return candidate;
    }

    private int resolveGraphExtractionBatchSize(GraphExtractionConfig config) {
        int size = graphExtractionBatchSize > 0
                ? graphExtractionBatchSize
                : DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
        return Math.max(1, Math.min(size, 128));
    }

    private int adaptBatchSizeForMemory(int configuredBatchSize, UnifiedCrawlJob job) {
        updateMemorySnapshot(job);
        int usage = job.getMemoryUsagePercent().get();
        if (memoryCriticalThresholdPercent > 0 && usage >= memoryCriticalThresholdPercent) {
            return Math.max(1, Math.min(MIN_EMBEDDING_BATCH_SIZE, configuredBatchSize));
        }
        if (memoryWaitThresholdPercent > 0 && usage >= memoryWaitThresholdPercent) {
            return Math.max(1, Math.min(configuredBatchSize, Math.max(1, configuredBatchSize / 2)));
        }
        return configuredBatchSize;
    }

    private EmbeddingModel primaryEmbeddingModel() {
        if (embeddingModels == null || embeddingModels.isEmpty()) {
            return null;
        }
        for (EmbeddingModel model : embeddingModels) {
            try {
                if (model != null && model.isInitialized()) {
                    return model;
                }
            } catch (Exception ignored) {
                // Try next model.
            }
        }
        return embeddingModels.get(0);
    }

    private boolean isEmbeddingModelReady(EmbeddingModel embeddingModel) {
        if (embeddingModel == null) {
            return false;
        }
        List<float[]> readinessProbe = null;
        try {
            if (embeddingModel.isInitialized()) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Embedding readiness check failed: {}", e.getMessage());
        }

        try {
            int knownDimensions = embeddingModel.dimensions();
            if (knownDimensions > 0 && embeddingModel.isInitialized()) {
                return true;
            }
        } catch (Exception e) {
            log.debug("Embedding dimension readiness check failed: {}", e.getMessage());
        }

        try {
            log.info("Embedding model is not initialized; running readiness probe before vector indexing");
            readinessProbe = embeddingModel.embedBatch(List.of("kompile embedding readiness probe"));
            boolean ready = readinessProbe != null
                    && readinessProbe.size() == 1
                    && isIndexableEmbedding(readinessProbe.get(0));
            if (!ready) {
                log.warn("Embedding readiness probe returned non-indexable output");
                return false;
            }
            return embeddingModel.isInitialized() || embeddingModel.dimensions() > 0;
        } catch (Exception e) {
            log.warn("Embedding readiness probe failed: {}", e.getMessage());
            return false;
        } finally {
            if (readinessProbe != null) {
                try { readinessProbe.clear(); } catch (UnsupportedOperationException ignored) { }
            }
        }
    }

    private String embeddingModelNotReadyReason(EmbeddingModel embeddingModel) {
        if (embeddingModel == null) {
            return "No embedding model available";
        }

        String modelId = embeddingModel.getClass().getSimpleName();
        String phase = "unknown";
        String error = null;
        String message = null;
        try {
            String value = embeddingModel.getModelIdentifier();
            if (value != null && !value.isBlank()) {
                modelId = value;
            }
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }
        try {
            String value = embeddingModel.getLoadingPhase();
            if (value != null && !value.isBlank()) {
                phase = value;
            }
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }
        try {
            error = embeddingModel.getInitializationError();
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }
        try {
            message = embeddingModel.getLoadingMessage();
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }

        StringBuilder reason = new StringBuilder();
        reason.append("Embedding model '").append(modelId)
                .append("' not initialized (phase: ").append(phase).append(")");
        if (error != null && !error.isBlank()) {
            reason.append(", error: ").append(error);
        } else if (message != null && !message.isBlank()) {
            reason.append(", message: ").append(message);
        }
        return reason.toString();
    }

    @FunctionalInterface
    private interface PostProcessRunnable {
        void run() throws Exception;
    }

    private String semanticRelationLabel(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "RELATED_TO";
        }
        String normalized = relationType.trim()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "RELATED_TO" : normalized.toUpperCase(Locale.ROOT);
    }

    private String semanticRelationDescription(String description, String label) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return label != null && !label.isBlank() ? label : "RELATED_TO";
    }

    private String semanticRelationMetadataJson(String jobId,
                                                String sourcePath,
                                                String extractionMethod,
                                                String sourceEntityId,
	                                                String targetEntityId,
	                                                String label,
	                                                String description,
	                                                Double confidence,
	                                                Map<?, ?> properties) {
	        Map<String, Object> metadata = new LinkedHashMap<>();
	        if (properties != null && !properties.isEmpty()) {
	            properties.forEach((key, value) -> {
	                if (key != null && value != null) {
	                    metadata.put(String.valueOf(key), value);
	                }
	            });
	            Map<String, Object> propertyCopy = new LinkedHashMap<>();
	            properties.forEach((key, value) -> {
	                if (key != null && value != null) {
	                    propertyCopy.put(String.valueOf(key), value);
	                }
	            });
	            metadata.put("properties", propertyCopy);
	        }
        metadata.putIfAbsent("semanticType", label);
        metadata.putIfAbsent("relationshipType", label);
        metadata.putIfAbsent("semanticContext", description);
        metadata.putIfAbsent("sourceEntityId", sourceEntityId);
        metadata.putIfAbsent("targetEntityId", targetEntityId);
        metadata.putIfAbsent("extractionMethod", extractionMethod);
        if (jobId != null) {
            metadata.putIfAbsent("jobId", jobId);
        }
        if (sourcePath != null) {
            metadata.putIfAbsent(GraphConstants.META_SOURCE_PATH, sourcePath);
        }
        if (confidence != null) {
            metadata.putIfAbsent("confidence", confidence);
        }
        try {
            return EDGE_METADATA_MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{\"semanticType\":\"" + label + "\"}";
	        }
	    }

	    private Double numberAsDouble(Object value) {
	        if (value instanceof Number number) {
	            return number.doubleValue();
	        }
	        if (value instanceof String text && !text.isBlank()) {
	            try {
	                return Double.parseDouble(text.trim());
	            } catch (NumberFormatException ignored) {
	                return null;
	            }
	        }
	        return null;
	    }

    private Map<String, Object> metadataProperties(Object... keyValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (keyValues == null) {
            return properties;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                properties.put(String.valueOf(key), value);
            }
        }
        return properties;
    }

    private double entityResolutionSimilarityThreshold(GraphExtractionConfig config) {
        if (config == null || config.getEntityResolutionSimilarityThreshold() <= 0.0) {
            return 0.85;
        }
        return Math.max(0.0, Math.min(1.0, config.getEntityResolutionSimilarityThreshold()));
    }

    private void recordEntityMention(GraphNode documentNode,
                                     String entityName,
                                     String entityType,
                                     Double confidence,
                                     Long factSheetId,
                                     String extractionMethod,
                                     String sourcePath,
                                     String sourceDocumentId) {
        if (entityMentionRepository == null || documentNode == null || entityName == null || entityName.isBlank()) {
            return;
        }
        String normalizedName = normalizeEntityMentionName(entityName);
        if (normalizedName.isBlank()) {
            return;
        }
        try {
            EntityMention mention = entityMentionRepository
                    .findByNodeAndEntityNameAndFactSheet(documentNode, normalizedName, factSheetId)
                    .orElseGet(() -> EntityMention.builder()
                            .node(documentNode)
                            .entityName(normalizedName)
                            .entityType(safeEntityType(entityType).toUpperCase(Locale.ROOT))
                            .mentionCount(0)
                            .confidence(confidence != null ? confidence : 1.0)
                            .factSheetId(factSheetId)
                            .build());
            mention.setMentionCount((mention.getMentionCount() != null ? mention.getMentionCount() : 0) + 1);
            if (confidence != null) {
                mention.setConfidence(mention.getConfidence() != null
                        ? Math.max(mention.getConfidence(), confidence)
                        : confidence);
            }
            mention.setContextJson(entityMentionContextJson(entityName, extractionMethod, sourcePath, sourceDocumentId));
            entityMentionRepository.save(mention);
        } catch (Exception e) {
            log.debug("Failed to persist entity mention '{}' for document node {}: {}",
                    entityName, documentNode.getNodeId(), e.getMessage());
        }
    }

    private String normalizeEntityMentionName(String name) {
        String normalized = ENTITY_SUFFIX_PATTERN.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll("").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String entityMentionContextJson(String entityName,
                                            String extractionMethod,
                                            String sourcePath,
                                            String sourceDocumentId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("entityName", entityName);
        if (extractionMethod != null) context.put("extractionMethod", extractionMethod);
        if (sourcePath != null) context.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        if (sourceDocumentId != null) context.put("sourceDocumentId", sourceDocumentId);
        try {
            return EDGE_METADATA_MAPPER.writeValueAsString(List.of(context));
        } catch (Exception e) {
            return "[]";
        }
    }

    private String safeEntityType(String type) {
        return type != null && !type.isBlank() ? type : "entity";
    }

    private record SourceLoadResult(int index, String label, List<Document> documents) {
    }

    private record GraphPersistResult(int entities, int relationships) {
        static GraphPersistResult empty() {
            return new GraphPersistResult(0, 0);
        }
    }

    private record CostBatch<T>(int index, List<T> items, long cost) {
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
        List<MutableCostBatch<T>> mutableBatches = new ArrayList<>();
        for (CostItem<T> costItem : costItems) {
            MutableCostBatch<T> best = null;
            long bestRemaining = Long.MAX_VALUE;
            for (MutableCostBatch<T> batch : mutableBatches) {
                if (batch.items.size() >= maxItems) {
                    continue;
                }
                long newCost = batch.cost + costItem.cost();
                if (targetCost > 0 && newCost > targetCost && !batch.items.isEmpty()) {
                    continue;
                }
                long remaining = targetCost > 0 ? targetCost - newCost : batch.items.size();
                if (remaining < bestRemaining) {
                    best = batch;
                    bestRemaining = remaining;
                }
            }
            if (best == null) {
                best = new MutableCostBatch<>();
                mutableBatches.add(best);
            }
            best.add(costItem.item(), costItem.cost());
        }

        List<CostBatch<T>> batches = new ArrayList<>(mutableBatches.size());
        for (MutableCostBatch<T> batch : mutableBatches) {
            batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(batch.items), batch.cost));
        }
        return batches;
    }

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

    /**
     * Dynamically adjusts outer graph extraction batch parallelism based on memory pressure.
     * Created per-job and used to gate concurrent batch submissions.
     */
    static class OuterParallelismAdvisor {
        private volatile int currentParallelism;
        private final int maxParallelism;
        private int consecutiveLowMemoryBatches;
        private long lastRampTime;
        private static final long RAMP_COOLDOWN_MS = 12_000;
        private static final double CRITICAL_THRESHOLD = 0.82;
        private static final double HIGH_THRESHOLD = 0.70;
        private static final double LOW_THRESHOLD = 0.60;
        private static final int RAMP_AFTER_LOW_COUNT = 2;

        OuterParallelismAdvisor(int initialParallelism) {
            this.maxParallelism = Math.max(1, initialParallelism);
            this.currentParallelism = this.maxParallelism;
            this.lastRampTime = System.currentTimeMillis();
        }

        synchronized void afterBatchComplete(long batchMs, double heapPercent) {
            int oldParallelism = currentParallelism;

            if (heapPercent > CRITICAL_THRESHOLD) {
                currentParallelism = 1;
                consecutiveLowMemoryBatches = 0;
                if (oldParallelism != 1) {
                    log.info("Outer graph parallelism reduced {} -> 1 (reason: heap {}% > critical {}%)",
                            oldParallelism, Math.round(heapPercent * 100), Math.round(CRITICAL_THRESHOLD * 100));
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
                    currentParallelism = Math.min(maxParallelism, currentParallelism + 1);
                    lastRampTime = now;
                    consecutiveLowMemoryBatches = 0;
                    log.info("Outer graph parallelism increased {} -> {} (reason: {} consecutive low-memory batches, heap {}%)",
                            oldParallelism, currentParallelism, RAMP_AFTER_LOW_COUNT, Math.round(heapPercent * 100));
                }
            } else {
                consecutiveLowMemoryBatches = 0;
            }
        }

        int getCurrentParallelism() { return currentParallelism; }

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OuterParallelismAdvisor.class);
    }

    private int resolveGraphExtractionParallelism(UnifiedCrawlJob job, int plannedBatchCount) {
        int configured = Math.max(1, graphExtractionParallelism);
        // When GraphConstructor persists its own matrix graph and embeds inline,
        // reduce parallelism to avoid GPU/OpenMP contention. The default unified
        // crawl path skips constructor-local persistence and writes only the
        // scoped fact-sheet graph, so no embedding throttle is needed there.
        if (graphConstructor != null && graphConstructorPersistMatrixGraph && !graphConstructorSkipEmbedding) {
            configured = Math.min(configured, 2);
        }
        updateMemorySnapshot(job);
        // Only force to 1 at CRITICAL memory pressure, not just the wait threshold
        if (memoryCriticalThresholdPercent > 0
                && job.getMemoryUsagePercent().get() >= memoryCriticalThresholdPercent) {
            configured = 1;
        }
        return Math.max(1, Math.min(configured, Math.max(1, plannedBatchCount)));
    }

    private void runPostProcessTask(UnifiedCrawlJob job, String phase, PostProcessRunnable task) throws Exception {
        if (isCancelled(job)) return;
        updateProgress(job, phase, estimateProgress(job), "Starting " + humanizePhase(phase), null);
        waitForMemoryCapacity(job, phase);
        task.run();
        updateProgress(job, phase, estimateProgress(job), "Completed " + humanizePhase(phase), null);
    }

    private void surfaceCrawlResults(UnifiedCrawlJob job, List<Document> chunkedDocuments) {
        if (isCancelled(job)) {
            return;
        }
        int chunks = chunkedDocuments != null ? chunkedDocuments.size() : 0;
        updatePipelineStep(job, "SURFACING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, 1, 0, 0, 0, 0, null,
                "Surfacing raw crawl graph and chunk evidence");
        updateProgress(job, "SURFACING", estimateProgress(job),
                "Surfacing crawl output",
                job.getDocumentsLoaded().get() + " document(s), " + chunks + " chunk(s), "
                        + job.getEntitiesExtracted().get() + " entities, "
                        + job.getRelationshipsExtracted().get() + " relationships");
        completePipelineStep(job, "SURFACING", 1,
                "Raw crawl surface available before cleanup/enrichment");
        recordEvent(job, "SURFACING", "INFO",
                "Crawl surface ready",
                "Graph and chunk evidence persisted; cleanup/enrichment can run from this surface");
    }

    private void deferVectorIndexing(UnifiedCrawlJob job,
                                     List<Document> documents,
                                     VectorIndexConfig config,
                                     String reason) {
        if (job == null) {
            return;
        }
        List<Document> source = documents != null ? documents : List.of();
        int alreadyIndexed = Math.max(0, Math.min(job.getDocumentsIndexed().get(), source.size()));
        List<Document> remaining = new ArrayList<>(source.subList(alreadyIndexed, source.size()));
        job.getDeferredEmbeddingChunks().clear();
        job.getDeferredEmbeddingChunks().addAll(remaining);
        job.setDeferredVectorIndexConfig(config);
        updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.DEFERRED,
                alreadyIndexed, source.size(), 0,
                job.getVectorBatchesCompleted().get(), job.getVectorBatchesTotal().get(),
                0, null,
                reason + "; " + remaining.size() + " chunk(s) queued for later embedding");
        recordEvent(job, "VECTOR_INDEXING", "WARN",
                "Vector indexing deferred",
                reason + "; indexed=" + alreadyIndexed + ", pending=" + remaining.size());
    }

    private void publishGraphBuildCompletedEvent(UnifiedCrawlJob job) {
        if (eventPublisher == null || job == null) {
            return;
        }
        try {
            Long factSheetId = jobFactSheetId(job);
            eventPublisher.publishEvent(new ai.kompile.core.graphbuilder.GraphBuildCompletedEvent(
                    this, job.getJobId(),
                    job.getEntitiesExtracted().get(),
                    job.getRelationshipsExtracted().get(),
                    factSheetId, job.snapshotEntityTypeCounts()));
        } catch (Exception ex) {
            log.warn("[Job {}] Failed to publish GraphBuildCompletedEvent: {}",
                    job.getJobId(), ex.getMessage());
        }
    }

    private void initializePipelineSteps(UnifiedCrawlJob job) {
        if (job == null) {
            return;
        }
        for (String phase : List.of(
                "LOADING",
                "DISCOVERING",
                "CONVERTING",
                "ROUTING",
                "GRAPH_PREP",
                "CHUNKING",
                "GRAPH_EXTRACTION",
                "SURFACING",
                "ENTITY_RESOLUTION",
                "EDGE_COMPUTATION",
                "VECTOR_INDEXING",
                "ENRICHMENT")) {
            ensurePipelineStep(job, phase);
        }
        int sourceCount = job.getRequest() != null && job.getRequest().getSources() != null
                ? job.getRequest().getSources().size() : 0;
        updatePipelineStep(job, "LOADING", UnifiedCrawlJob.PipelineStepStatus.PENDING,
                0, 0, 0, 0, 0, 0, null, "Waiting for crawl slot");
    }

    private UnifiedCrawlJob.PipelineStepProgress ensurePipelineStep(UnifiedCrawlJob job, String phase) {
        String stepId = normalizeStepId(phase);
        List<UnifiedCrawlJob.PipelineStepProgress> steps = job.getPipelineSteps();
        // CopyOnWriteArrayList is thread-safe for reads. Steps are only ever added
        // (never removed), so a duplicate add under a race is harmless — the first
        // match will always be found on subsequent lookups.
        for (UnifiedCrawlJob.PipelineStepProgress step : steps) {
            if (stepId.equals(step.getStepId())) {
                return step;
            }
        }
        UnifiedCrawlJob.PipelineStepProgress step = UnifiedCrawlJob.PipelineStepProgress.builder()
                .stepId(stepId)
                .displayName(stepDisplayName(phase))
                .stepType(stepType(phase))
                .lastUpdatedAt(Instant.now())
                .build();
        steps.add(step);
        return step;
    }

    private void updatePipelineStepFromCounters(UnifiedCrawlJob job, String phase, String message, String details) {
        if (job == null || phase == null) {
            return;
        }
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        UnifiedCrawlJob.PipelineStepStatus current = step.getStatus().get();
        if (current == UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                || current == UnifiedCrawlJob.PipelineStepStatus.FAILED
                || current == UnifiedCrawlJob.PipelineStepStatus.CANCELLED
                || current == UnifiedCrawlJob.PipelineStepStatus.SKIPPED
                || current == UnifiedCrawlJob.PipelineStepStatus.DEFERRED) {
            return;
        }
        if (step.getStartedAt() == null) {
            step.setStartedAt(Instant.now());
        }
        step.getStatus().set("MEMORY_BACKPRESSURE".equals(job.getCurrentBatchStep().get())
                ? UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE
                : UnifiedCrawlJob.PipelineStepStatus.RUNNING);
        step.getMessage().set(message != null ? message : details);
        step.getCurrentItem().set(job.getCurrentFile().get());
        step.getCurrentBatchSize().set(job.getCurrentBatchSize().get());
        step.setLastUpdatedAt(Instant.now());

        refreshStepCounters(job, phase, step);
    }

    private void refreshStepCounters(UnifiedCrawlJob job,
                                     String phase,
                                     UnifiedCrawlJob.PipelineStepProgress step) {
        switch (normalizeStepId(phase)) {
            case "LOADING" -> {
                int total = job.getSourceProgress() != null ? job.getSourceProgress().size() : 0;
                int completed = (int) (job.getSourceProgress() != null
                        ? job.getSourceProgress().stream()
                        .filter(sp -> sp.getStatus() == UnifiedCrawlJob.Status.COMPLETED
                                || sp.getStatus() == UnifiedCrawlJob.Status.FAILED
                                || sp.getStatus() == UnifiedCrawlJob.Status.CANCELLED)
                        .count()
                        : 0);
                step.getTotalItems().set(total);
                step.getCompletedItems().set(completed);
                step.getFailedItems().set(job.getErrorCount().get());
                step.getProgressPercent().set(percent(completed, total));
            }
            case "GRAPH_EXTRACTION" -> {
                int total = graphChunksTotal(job);
                int completed = normalizeGraphChunksProcessed(job);
                step.getTotalItems().set(total);
                step.getCompletedItems().set(completed);
                step.getFailedItems().set(job.getErrorCount().get());
                step.getProgressPercent().set(percent(completed, total));
            }
            case "VECTOR_INDEXING" -> {
                int total = job.getChunksQueuedForEmbedding().get();
                int completed = Math.max(job.getChunksEmbedded().get(), job.getDocumentsIndexed().get());
                step.getTotalItems().set(total);
                step.getCompletedItems().set(completed);
                step.getTotalBatches().set(job.getVectorBatchesTotal().get());
                step.getCompletedBatches().set(job.getVectorBatchesCompleted().get());
                step.getProgressPercent().set(percent(completed, total));
            }
            case "CHUNKING" -> {
                int total = job.getDocumentsLoaded().get();
                int completed = step.getCompletedItems().get();
                step.getTotalItems().set(total);
                step.getProgressPercent().set(percent(completed, total));
            }
            default -> {
                int total = Math.max(1, step.getTotalItems().get());
                int completed = Math.min(total, step.getCompletedItems().get());
                step.getProgressPercent().set(percent(completed, total));
            }
        }
    }

    private void updatePipelineStep(UnifiedCrawlJob job,
                                    String phase,
                                    UnifiedCrawlJob.PipelineStepStatus status,
                                    int completedItems,
                                    int totalItems,
                                    int failedItems,
                                    int completedBatches,
                                    int totalBatches,
                                    int currentBatchSize,
                                    String currentItem,
                                    String message) {
        if (job == null || phase == null) {
            return;
        }
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        Instant now = Instant.now();
        if (status == UnifiedCrawlJob.PipelineStepStatus.RUNNING && step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if (status == UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                || status == UnifiedCrawlJob.PipelineStepStatus.FAILED
                || status == UnifiedCrawlJob.PipelineStepStatus.CANCELLED
                || status == UnifiedCrawlJob.PipelineStepStatus.SKIPPED
                || status == UnifiedCrawlJob.PipelineStepStatus.DEFERRED) {
            if (step.getStartedAt() == null) {
                step.setStartedAt(now);
            }
            step.setCompletedAt(now);
        }
        step.getStatus().set(status);
        if (totalItems >= 0) step.getTotalItems().set(totalItems);
        if (completedItems >= 0) step.getCompletedItems().set(completedItems);
        if (failedItems >= 0) step.getFailedItems().set(failedItems);
        if (totalBatches >= 0) step.getTotalBatches().set(totalBatches);
        if (completedBatches >= 0) step.getCompletedBatches().set(completedBatches);
        if (currentBatchSize >= 0) step.getCurrentBatchSize().set(currentBatchSize);
        step.getCurrentItem().set(currentItem);
        step.getMessage().set(message);
        step.setLastUpdatedAt(now);
        int total = step.getTotalItems().get();
        int done = step.getCompletedItems().get();
        step.getProgressPercent().set(status == UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                ? 100
                : percent(done, total));
    }

    private void completePipelineStep(UnifiedCrawlJob job, String phase, int completedItems, String message) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        int total = Math.max(step.getTotalItems().get(), completedItems);
        updatePipelineStep(job, phase, UnifiedCrawlJob.PipelineStepStatus.COMPLETED,
                completedItems, total, step.getFailedItems().get(),
                step.getCompletedBatches().get(), step.getTotalBatches().get(),
                0, null, message);
    }

    private void failPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        updatePipelineStep(job, phase, UnifiedCrawlJob.PipelineStepStatus.FAILED,
                step.getCompletedItems().get(), Math.max(1, step.getTotalItems().get()),
                step.getFailedItems().incrementAndGet(), step.getCompletedBatches().get(),
                step.getTotalBatches().get(), step.getCurrentBatchSize().get(),
                step.getCurrentItem().get(), message);
    }

    private void skipPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        updatePipelineStep(job, phase, UnifiedCrawlJob.PipelineStepStatus.SKIPPED,
                0, 0, 0, 0, 0, 0, null, message);
    }

    private void incrementPipelineStep(UnifiedCrawlJob job,
                                       String phase,
                                       int completedItemsDelta,
                                       int completedBatchesDelta,
                                       String message) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        int completedItems = completedItemsDelta > 0
                ? step.getCompletedItems().addAndGet(completedItemsDelta)
                : step.getCompletedItems().get();
        int completedBatches = completedBatchesDelta > 0
                ? step.getCompletedBatches().addAndGet(completedBatchesDelta)
                : step.getCompletedBatches().get();
        int totalItems = step.getTotalItems().get();
        UnifiedCrawlJob.PipelineStepStatus status =
                "GRAPH_PREP".equals(normalizeStepId(phase)) && totalItems > 0 && completedItems >= totalItems
                        ? UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                        : UnifiedCrawlJob.PipelineStepStatus.RUNNING;
        updatePipelineStep(job, phase, status,
                completedItems, step.getTotalItems().get(), step.getFailedItems().get(),
                completedBatches, step.getTotalBatches().get(), step.getCurrentBatchSize().get(),
                step.getCurrentItem().get(), message);
    }

    private int percent(int completed, int total) {
        if (total <= 0) {
            return completed > 0 ? 100 : 0;
        }
        return Math.max(0, Math.min(100, (int) Math.round((completed * 100.0) / total)));
    }

    private String normalizeStepId(String phase) {
        if ("EMBEDDING".equals(phase) || "INDEXING".equals(phase)) {
            return "VECTOR_INDEXING";
        }
        return phase != null ? phase : "UNKNOWN";
    }

    private String stepDisplayName(String phase) {
	        return switch (normalizeStepId(phase)) {
	            case "LOADING" -> "Source Loading";
	            case "DISCOVERING" -> "Source Discovery";
	            case "CONVERTING" -> "Text Conversion";
	            case "ROUTING" -> "Content Routing";
            case "GRAPH_PREP" -> "Rule Graph Prep";
            case "CHUNKING" -> "Chunking";
            case "GRAPH_EXTRACTION" -> "Graph Extraction";
            case "SURFACING" -> "Crawl Surface";
            case "ENTITY_RESOLUTION" -> "Entity Resolution";
            case "EDGE_COMPUTATION" -> "Graph Edge Cleanup";
            case "VECTOR_INDEXING" -> "Embedding & Vector Index";
            case "ENRICHMENT" -> "Post-Crawl Enrichment";
            default -> humanizePhase(phase);
        };
    }

    private String stepType(String phase) {
	        return switch (normalizeStepId(phase)) {
		            case "LOADING", "DISCOVERING" -> "IO";
		            case "CONVERTING", "ROUTING", "CHUNKING" -> "CPU";
            case "GRAPH_PREP", "SURFACING", "ENTITY_RESOLUTION", "EDGE_COMPUTATION" -> "GRAPH";
            case "GRAPH_EXTRACTION" -> graphConstructor != null ? "GRAPH_CONSTRUCTOR" : "LLM";
            case "VECTOR_INDEXING" -> "EMBEDDING";
            case "ENRICHMENT" -> "ENRICHMENT";
            default -> "PIPELINE";
        };
    }

    private boolean waitForMemoryCapacity(UnifiedCrawlJob job, String phase) {
        refreshRuntimeConfig();
        updateMemorySnapshot(job);
        if (!hasMemoryPressure(job, false)) {
            return true;
        }

        String detail = memoryPressureDetail(job);
        job.getCurrentBatchStep().set("MEMORY_BACKPRESSURE");
        updatePipelineStepFromCounters(job, phase, "Memory backpressure", detail);
        recordEvent(job, phase, "WARN", "Memory pressure detected", detail);
        log.warn("[Job {}] Memory pressure before phase {}: {}", job.getJobId(), phase, detail);

        // One-time cleanup attempt: trim native memory and request GC once
        trimNativeMemory(job, phase, "memory backpressure");
        System.gc();
        updateMemorySnapshot(job);

        if (!hasMemoryPressure(job, true)) {
            updateMemorySnapshot(job);
            return true;
        }

        // Spin-wait with increasing sleep intervals.  Do NOT call System.gc() or
        // trimNativeMemory on every iteration — they cause full stop-the-world pauses
        // and the cleanup work above already freed everything reclaimable.
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, memoryWaitTimeoutSeconds));
        int iteration = 0;
        while (!isCancelled(job) && System.currentTimeMillis() < deadline) {
            try {
                // Back-off: 2s, 3s, 4s, then cap at 5s — avoids tight-loop overhead
                long sleepMs = Math.min(5000L, 2000L + iteration * 1000L);
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            refreshRuntimeConfig();
            updateMemorySnapshot(job);
            // Only re-attempt GC every ~15 seconds (every 5th iteration after back-off stabilizes)
            if (iteration > 0 && iteration % 5 == 0) {
                System.gc();
            }
            updateProgress(job, phase, estimateProgress(job),
                    "Waiting for memory pressure to drop",
                    memoryPressureDetail(job));
            if (!hasMemoryPressure(job, false)) {
                job.getCurrentBatchStep().set(null);
                return true;
            }
            iteration++;
        }
        recordEvent(job, phase, "WARN", "Continuing despite memory pressure",
                memoryPressureDetail(job) + " after waiting " + memoryWaitTimeoutSeconds + "s");
        return false;
    }

    private void updateProgress(UnifiedCrawlJob job, String phase, int progressPercent,
                                String message, String details) {
        job.getCurrentPhase().set(phase);
        int boundedProgress = Math.max(progressPercent, estimateProgressForPhase(job, phase));
        boundedProgress = Math.max(0, Math.min(100, boundedProgress));
        job.getProgressPercent().accumulateAndGet(boundedProgress, Math::max);
        updateMemorySnapshot(job);
        if (message != null && !message.isBlank()) {
            recordEvent(job, phase, "INFO", message, details);
        }
        updatePipelineStepFromCounters(job, phase, message, details);
    }

    private void recordEntityResolutionProgress(
            UnifiedCrawlJob job,
            ai.kompile.knowledgegraph.resolution.GraphCompactionService.CompactionProgress progress) {
        if (job == null || progress == null || isCancelled(job)) {
            return;
        }
        job.getCurrentPhase().set("ENTITY_RESOLUTION");
        updateMemorySnapshot(job);

        int total = Math.max(1, progress.total());
        int processed = Math.max(0, Math.min(total, progress.processed()));
        int phaseProgress = 72 + (int) Math.min(8, (processed * 8L) / total);
        job.getProgressPercent().accumulateAndGet(phaseProgress, Math::max);

        String currentItem = progress.blockType() != null && !progress.blockType().isBlank()
                ? progress.blockType()
                : progress.stage();
        String message = progress.message() != null && !progress.message().isBlank()
                ? progress.message()
                : "Entity resolution progress";
        String details = "stage=" + progress.stage()
                + ", processed=" + processed + "/" + total
                + ", block=" + progress.blockIndex() + "/" + progress.blockCount()
                + ", blockSize=" + progress.blockSize()
                + ", candidates=" + progress.candidates()
                + ", elapsedMs=" + progress.elapsedMs()
                + ", " + memoryPressureDetail(job);

        updatePipelineStep(job, "ENTITY_RESOLUTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                processed, total, 0, 0, 0, 0, currentItem, message);
        recordEvent(job, "ENTITY_RESOLUTION",
                progress.stage() != null && progress.stage().contains("MEMORY_PRESSURE") ? "WARN" : "INFO",
                message, details);
    }

    private void updateMemorySnapshot(UnifiedCrawlJob job) {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        int percent = max > 0 ? (int) Math.min(100, Math.round((used * 100.0) / max)) : 0;
        job.getHeapUsedBytes().set(used);
        job.getHeapMaxBytes().set(max);
        job.getMemoryUsagePercent().set(percent);
        job.getPeakMemoryUsagePercent().accumulateAndGet(percent, Math::max);

        long directBytes = 0L;
        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) {
                    directBytes = Math.max(directBytes, pool.getMemoryUsed());
                }
            }
        } catch (Throwable ignored) {
            directBytes = 0L;
        }
        job.getDirectBufferBytes().set(Math.max(0L, directBytes));

        try {
            long physicalBytes = Math.max(0L, Pointer.physicalBytes());
            long totalBytes = Math.max(0L, Pointer.totalBytes());
            long maxPhysicalBytes = Math.max(0L, Pointer.maxPhysicalBytes());
            int nativePercent = maxPhysicalBytes > 0
                    ? (int) Math.min(100, Math.round((physicalBytes * 100.0) / maxPhysicalBytes))
                    : 0;
            job.getNativePhysicalBytes().set(physicalBytes);
            job.getPeakNativePhysicalBytes().accumulateAndGet(physicalBytes, Math::max);
            job.getNativeTotalBytes().set(totalBytes);
            job.getNativeMaxPhysicalBytes().set(maxPhysicalBytes);
            job.getNativeMemoryUsagePercent().set(nativePercent);
            job.getPeakNativeMemoryUsagePercent().accumulateAndGet(nativePercent, Math::max);
        } catch (Throwable ignored) {
            // JavaCPP accounting is not available for every runtime/backend.
        }
    }

    private boolean hasMemoryPressure(UnifiedCrawlJob job, boolean critical) {
        int heapThreshold = critical ? memoryCriticalThresholdPercent : memoryWaitThresholdPercent;
        boolean heapPressure = heapThreshold > 0 && job.getMemoryUsagePercent().get() >= heapThreshold;

        int nativeThreshold = critical ? nativeMemoryCriticalThresholdPercent : nativeMemoryWaitThresholdPercent;
        boolean nativePressure = hasNativeMemoryPressure(job, nativeThreshold);
        return heapPressure || nativePressure;
    }

    private boolean hasNativeMemoryPressure(UnifiedCrawlJob job, int thresholdPercent) {
        return job.getNativeMaxPhysicalBytes().get() > 0
                && thresholdPercent > 0
                && job.getNativeMemoryUsagePercent().get() >= thresholdPercent;
    }

    private String memoryPressureDetail(UnifiedCrawlJob job) {
        return "heap=" + job.getMemoryUsagePercent().get() + "%"
                + " (" + formatBytes(job.getHeapUsedBytes().get()) + "/"
                + formatBytes(job.getHeapMaxBytes().get()) + ")"
                + ", native=" + job.getNativeMemoryUsagePercent().get() + "%"
                + " (" + formatBytes(job.getNativePhysicalBytes().get()) + "/"
                + formatBytes(job.getNativeMaxPhysicalBytes().get()) + " physical, total="
                + formatBytes(job.getNativeTotalBytes().get()) + ")"
                + ", direct=" + formatBytes(job.getDirectBufferBytes().get())
                + ", thresholds heap=" + memoryWaitThresholdPercent + "/" + memoryCriticalThresholdPercent + "%"
                + ", native=" + nativeMemoryWaitThresholdPercent + "/" + nativeMemoryCriticalThresholdPercent + "%";
    }

    /** Rate-limit trimNativeMemory to avoid repeated expensive cleanup within short windows. */
    private final AtomicLong lastTrimNanos = new AtomicLong(0);
    private static final long TRIM_MIN_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);

    private void trimNativeMemory(UnifiedCrawlJob job, String phase, String reason) {
        if (!nativeMemoryCleanupEnabled) {
            return;
        }
        // Rate-limit: skip if called again within 5 seconds (these calls are very expensive)
        long now = System.nanoTime();
        long last = lastTrimNanos.get();
        if (now - last < TRIM_MIN_INTERVAL_NS) {
            return;
        }
        if (!lastTrimNanos.compareAndSet(last, now)) {
            return;
        }

        updateMemorySnapshot(job);
        long beforePhysical = job.getNativePhysicalBytes().get();
        long beforeTotal = job.getNativeTotalBytes().get();
        int devicesTrimmed = 0;
        int pointerPasses = 0;
        // Single pass is sufficient — multiple passes just repeat the same work
        // since there's no new garbage created between passes
        int passes = 1;

        for (int pass = 0; pass < passes; pass++) {
            try {
                Pointer.deallocateReferences();
                pointerPasses++;
            } catch (Throwable t) {
                log.debug("[Job {}] JavaCPP reference cleanup skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                Nd4j.getExecutioner().commit();
            } catch (Throwable t) {
                log.debug("[Job {}] ND4J commit skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
            } catch (Throwable t) {
                log.debug("[Job {}] Workspace cleanup skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                int devices = Math.max(1, Nd4j.getAffinityManager().getNumberOfDevices());
                for (int d = 0; d < devices; d++) {
                    Nd4j.getNativeOps().trimMemoryPool(d);
                    devicesTrimmed++;
                }
            } catch (Throwable t) {
                log.debug("[Job {}] Native pool trim skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            // Skip System.gc() and System.runFinalization() — they cause full stop-the-world
            // pauses and are called far too frequently in the pipeline. Let the JVM manage GC.
            try {
                Pointer.deallocateReferences();
            } catch (Throwable t) {
                log.debug("[Job {}] Final native cleanup pass skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }
        }

        updateMemorySnapshot(job);
        long afterPhysical = job.getNativePhysicalBytes().get();
        long afterTotal = job.getNativeTotalBytes().get();
        long physicalDelta = beforePhysical - afterPhysical;
        long totalDelta = beforeTotal - afterTotal;
        String detail = "reason=" + reason
                + ", cleanupPasses=" + passes
                + ", pointerPasses=" + pointerPasses
                + ", devicesTrimmed=" + devicesTrimmed
                + ", physical=" + formatBytes(beforePhysical) + " -> " + formatBytes(afterPhysical)
                + " (delta=" + formatBytes(physicalDelta) + ")"
                + ", total=" + formatBytes(beforeTotal) + " -> " + formatBytes(afterTotal)
                + " (delta=" + formatBytes(totalDelta) + ")";
        recordEvent(job, phase, "INFO", "Native memory cleanup", detail);
        log.info("[Job {}] Native memory cleanup during {}: {}", job.getJobId(), phase, detail);
    }

    private String formatBytes(long bytes) {
        if (bytes == 0) return "0B";
        boolean negative = bytes < 0;
        double value = Math.abs((double) bytes);
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        String formatted = unit == 0
                ? String.format(Locale.ROOT, "%d%s", Math.abs(bytes), units[unit])
                : String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
        return negative ? "-" + formatted : formatted;
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
            int totalSources = job.getSourceProgress() != null ? job.getSourceProgress().size() : 0;
            if (totalSources > 0) {
                long done = job.getSourceProgress().stream()
                        .filter(sp -> sp.getStatus() == UnifiedCrawlJob.Status.COMPLETED
                                || sp.getStatus() == UnifiedCrawlJob.Status.FAILED)
                        .count();
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
            UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
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

    private void recordEvent(UnifiedCrawlJob job, String phase, String level, String message, String details) {
        if (job == null) return;
        List<UnifiedCrawlJob.StageEvent> events = job.getRecentEvents();
        UnifiedCrawlJob.StageEvent event = UnifiedCrawlJob.StageEvent.builder()
                .timestamp(Instant.now())
                .phase(phase)
                .level(level)
                .message(message)
                .details(details)
                .progressPercent(job.getProgressPercent().get())
                .build();
        // CopyOnWriteArrayList is already thread-safe. The cap enforcement isn't
        // strictly atomic without a lock, but the worst case is 121 events briefly
        // instead of 120 — not worth blocking every caller for.
        while (events.size() >= MAX_RECENT_EVENTS) {
            try { events.remove(0); } catch (IndexOutOfBoundsException ignored) { break; }
        }
        events.add(event);
    }

    private void recordDocumentProgress(UnifiedCrawlJob job,
                                        Document doc,
                                        String phase,
                                        String status,
                                        int chunksDelta,
                                        int entitiesDelta,
                                        int relationshipsDelta,
                                        String message,
                                        String errorMessage,
                                        List<String> extractors,
                                        boolean publishEvent) {
        Map<String, Object> metadata = doc != null ? doc.getMetadata() : null;
        recordDocumentProgress(job,
                documentKey(doc),
                documentFileName(metadata, doc != null ? doc.getId() : null),
                documentSourcePath(metadata, doc != null ? doc.getId() : null),
                stringMeta(metadata, GraphConstants.META_SOURCE_TYPE, "source_type"),
                stringMeta(metadata, GraphConstants.META_CONTENT_TYPE, "content_type", GraphConstants.META_DOCUMENT_TYPE),
                stringMeta(metadata, GraphConstants.META_LOADER, "loader_name"),
                phase, status, chunksDelta, entitiesDelta, relationshipsDelta,
                message, errorMessage, extractors, publishEvent);
    }

    private void recordDocumentProgress(UnifiedCrawlJob job,
                                        RetrievedDoc doc,
                                        String phase,
                                        String status,
                                        int chunksDelta,
                                        int entitiesDelta,
                                        int relationshipsDelta,
                                        String message,
                                        String errorMessage,
                                        List<String> extractors,
                                        boolean publishEvent) {
        Map<String, Object> metadata = doc != null ? doc.getMetadata() : null;
        recordDocumentProgress(job,
                documentKey(doc),
                documentFileName(metadata, doc != null ? doc.getId() : null),
                documentSourcePath(metadata, doc != null ? doc.getId() : null),
                stringMeta(metadata, GraphConstants.META_SOURCE_TYPE, "source_type"),
                stringMeta(metadata, GraphConstants.META_CONTENT_TYPE, "content_type", GraphConstants.META_DOCUMENT_TYPE),
                stringMeta(metadata, GraphConstants.META_LOADER, "loader_name"),
                phase, status, chunksDelta, entitiesDelta, relationshipsDelta,
                message, errorMessage, extractors, publishEvent);
    }

    private void recordDocumentProgress(UnifiedCrawlJob job,
                                        String documentKey,
                                        String fileName,
                                        String sourcePath,
                                        String sourceType,
                                        String contentType,
                                        String loaderName,
                                        String phase,
                                        String status,
                                        int chunksDelta,
                                        int entitiesDelta,
                                        int relationshipsDelta,
                                        String message,
                                        String errorMessage,
                                        List<String> extractors,
                                        boolean publishEvent) {
        if (job == null || documentKey == null || documentKey.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        UnifiedCrawlJob.DocumentProgress progress = job.getDocumentProgress().computeIfAbsent(documentKey, key ->
                UnifiedCrawlJob.DocumentProgress.builder()
                        .documentKey(key)
                        .fileName(fileName != null ? fileName : key)
                        .sourcePath(sourcePath)
                        .sourceType(sourceType)
                        .contentType(contentType)
                        .loaderName(loaderName)
                        .phase(phase)
                        .status(status)
                        .startedAt(now)
                        .updatedAt(now)
                        .build());

        synchronized (progress) {
            boolean samePhaseStatusRegression = isTerminalDocumentStatus(progress.getStatus())
                    && Objects.equals(progress.getPhase(), phase)
                    && !isTerminalDocumentStatus(status);
            if (samePhaseStatusRegression) {
                progress.setUpdatedAt(now);
                return;
            }
            if (fileName != null && !fileName.isBlank()) progress.setFileName(fileName);
            if (sourcePath != null && !sourcePath.isBlank()) progress.setSourcePath(sourcePath);
            if (sourceType != null && !sourceType.isBlank()) progress.setSourceType(sourceType);
            if (contentType != null && !contentType.isBlank()) progress.setContentType(contentType);
            if (loaderName != null && !loaderName.isBlank()) progress.setLoaderName(loaderName);
            if (phase != null && !phase.isBlank()) progress.setPhase(phase);
            if (status != null && !status.isBlank()) progress.setStatus(status);
            if (message != null && !message.isBlank()) progress.setMessage(message);
            if (errorMessage != null && !errorMessage.isBlank()) progress.setErrorMessage(errorMessage);
            progress.setChunksCreated(progress.getChunksCreated() + Math.max(0, chunksDelta));
            progress.setEntitiesExtracted(progress.getEntitiesExtracted() + Math.max(0, entitiesDelta));
            progress.setRelationshipsExtracted(progress.getRelationshipsExtracted() + Math.max(0, relationshipsDelta));
            progress.setGraphNodesCreated(progress.getGraphNodesCreated() + Math.max(0, entitiesDelta));
            progress.setGraphEdgesCreated(progress.getGraphEdgesCreated() + Math.max(0, relationshipsDelta));
            if (extractors != null && !extractors.isEmpty()) {
                List<String> merged = new ArrayList<>(progress.getExtractors() != null
                        ? progress.getExtractors() : List.of());
                for (String extractor : extractors) {
                    if (extractor != null && !extractor.isBlank() && !merged.contains(extractor)) {
                        merged.add(extractor);
                    }
                }
                progress.setExtractors(merged);
            }
            if (progress.getStartedAt() == null) progress.setStartedAt(now);
            progress.setUpdatedAt(now);
            if (isTerminalDocumentStatus(status)) {
                progress.setCompletedAt(now);
            }
        }

        if (publishEvent) {
            String displayName = progress.getFileName() != null ? progress.getFileName() : documentKey;
            String details = (entitiesDelta > 0 || relationshipsDelta > 0)
                    ? entitiesDelta + " entities, " + relationshipsDelta + " relationships"
                    : message;
            recordEvent(job, phase != null ? phase : "DOCUMENT", errorMessage != null ? "ERROR" : "INFO",
                    displayName + ": " + (message != null ? message : status), details);
        }
    }

    private void recordDocumentVectorProgress(UnifiedCrawlJob job,
                                              Document doc,
                                              String status,
                                              int embeddedDelta,
                                              int indexedDelta,
                                              String message,
                                              String errorMessage,
                                              boolean publishEvent) {
        if (job == null || doc == null) {
            return;
        }
        Map<String, Object> metadata = doc.getMetadata();
        String documentKey = documentKey(doc);
        if (documentKey == null || documentKey.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        UnifiedCrawlJob.DocumentProgress progress = job.getDocumentProgress().computeIfAbsent(documentKey, key ->
                UnifiedCrawlJob.DocumentProgress.builder()
                        .documentKey(key)
                        .fileName(documentFileName(metadata, doc.getId()))
                        .sourcePath(documentSourcePath(metadata, doc.getId()))
                        .sourceType(stringMeta(metadata, GraphConstants.META_SOURCE_TYPE, "source_type"))
                        .contentType(stringMeta(metadata, GraphConstants.META_CONTENT_TYPE, GraphConstants.META_DOCUMENT_TYPE))
                        .loaderName(stringMeta(metadata, GraphConstants.META_LOADER, "loader_name"))
                        .phase("VECTOR_INDEXING")
                        .status(status)
                        .startedAt(now)
                        .updatedAt(now)
                        .build());

        synchronized (progress) {
            progress.setPhase("VECTOR_INDEXING");
            progress.setStatus(status);
            progress.setUpdatedAt(now);
            if (progress.getStartedAt() == null) progress.setStartedAt(now);
            if (message != null && !message.isBlank()) progress.setMessage(message);
            if (errorMessage != null && !errorMessage.isBlank()) progress.setErrorMessage(errorMessage);
            progress.setChunksEmbedded(progress.getChunksEmbedded() + Math.max(0, embeddedDelta));
            progress.setChunksIndexed(progress.getChunksIndexed() + Math.max(0, indexedDelta));
            if (isTerminalDocumentStatus(status)) {
                progress.setCompletedAt(now);
            }
        }

        if (publishEvent) {
            String displayName = progress.getFileName() != null ? progress.getFileName() : documentKey;
            recordEvent(job, "VECTOR_INDEXING", errorMessage != null ? "ERROR" : "INFO",
                    displayName + ": " + (message != null ? message : status),
                    "embedded=" + embeddedDelta + ", indexed=" + indexedDelta);
        }
    }

    private boolean isTerminalDocumentStatus(String status) {
        if (status == null) return false;
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "FAILED", "SKIPPED", "CANCELLED", "LOADED" -> true;
            default -> false;
        };
    }

    private String documentKey(Document doc) {
        if (doc == null) return null;
        return documentKey(doc.getMetadata(), doc.getId());
    }

    private String documentKey(RetrievedDoc doc) {
        if (doc == null) return null;
        return documentKey(doc.getMetadata(), doc.getId());
    }

    private String documentKey(Map<String, Object> metadata, String fallbackId) {
        String sourcePath = documentSourcePath(metadata, fallbackId);
        if (sourcePath != null && !sourcePath.isBlank()) return sourcePath;
        return fallbackId;
    }

    private String documentSourcePath(Map<String, Object> metadata, String fallbackId) {
        String sourcePath = stringMeta(metadata, GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE, "source_url", "path");
        return sourcePath != null && !sourcePath.isBlank() ? sourcePath : fallbackId;
    }

    private String documentFileName(Map<String, Object> metadata, String fallbackId) {
        String fileName = stringMeta(metadata, "source_filename", GraphConstants.META_FILE_NAME, "file_name", "title");
        if (fileName != null && !fileName.isBlank()) return fileName;
        String sourcePath = documentSourcePath(metadata, fallbackId);
        if (sourcePath == null) return fallbackId;
        int slash = Math.max(sourcePath.lastIndexOf('/'), sourcePath.lastIndexOf('\\'));
        return slash >= 0 ? sourcePath.substring(slash + 1) : sourcePath;
    }

    private String stringMeta(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) return null;
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private void updateQueueSnapshots() {
        int active = runningJobIds.size();
        int queued = queuedSequences.size();
        for (UnifiedCrawlJob job : jobs.values()) {
            job.getActiveJobs().set(active);
            job.getQueuedJobs().set(queued);
            job.getMaxConcurrentJobs().set(Math.max(1, maxConcurrentJobs));
            job.getQueueCapacity().set(Math.max(1, queueCapacity));
            if (job.getStatus().get() == UnifiedCrawlJob.Status.PENDING) {
                Long seq = queuedSequences.get(job.getJobId());
                if (seq != null) {
                    long ahead = queuedSequences.values().stream().filter(other -> other < seq).count();
                    job.getQueuePosition().set((int) ahead + 1);
                }
            } else {
                job.getQueuePosition().set(0);
            }
        }
    }

    private String humanizePhase(String phase) {
        if (phase == null) return "post-processing";
        return phase.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ---- Text conversion ----

    /**
     * Converts documents to normalized plain text, with VLM-aware lighter-touch
     * normalization for documents processed by visual language models.
     */
    private List<Document> convertDocumentText(List<Document> documents, UnifiedCrawlJob job) {
        if (documents == null || documents.isEmpty()) return List.of();

        // Text normalization is pure CPU regex work with no shared state.
        // Use parallelStream for documents > 50 to get multi-core speedup.
        if (documents.size() > 50) {
            AtomicInteger counter = new AtomicInteger(0);
            List<Document> converted = documents.parallelStream()
                    .map(doc -> {
                        int idx = counter.incrementAndGet();
                        if (idx % 100 == 0) {
                            updateProgress(job, "CONVERTING", estimateProgress(job),
                                    "Converted " + idx + "/" + documents.size() + " document(s)", null);
                        }
                        String text = doc.getText();
                        if (text == null) return null;
                        boolean isVlmContent = doc.getMetadata() != null
                                && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
                        String plainText = isVlmContent ? normalizeStructuredText(text) : normalizeText(text);
                        if (plainText.isBlank()) return null;
                        Document convertedDoc = new Document(plainText);
                        convertedDoc.getMetadata().putAll(doc.getMetadata());
                        convertedDoc.getMetadata().put("converted", true);
                        return convertedDoc;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            updateProgress(job, "CONVERTING", estimateProgress(job),
                    "Text conversion complete", converted.size() + " document(s)");
            return converted;
        }

        // Sequential path for small batches (< 50 docs)
        List<Document> converted = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            if (i % 25 == 0) {
                updateProgress(job, "CONVERTING", estimateProgress(job),
                        "Converted " + i + "/" + documents.size() + " document(s)", null);
            }
            String text = doc.getText();
            if (text == null) continue;

            boolean isVlmContent = doc.getMetadata() != null
                    && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            String plainText = isVlmContent ? normalizeStructuredText(text) : normalizeText(text);

            if (plainText.isBlank()) continue;

            Document convertedDoc = new Document(plainText);
            convertedDoc.getMetadata().putAll(doc.getMetadata());
            convertedDoc.getMetadata().put("converted", true);
            converted.add(convertedDoc);
        }
        updateProgress(job, "CONVERTING", estimateProgress(job),
                "Text conversion complete", converted.size() + " document(s)");
        return converted;
    }

    private List<Document> copyDocumentsForBackgroundGraph(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> copy = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            Map<String, Object> metadata = doc.getMetadata() != null
                    ? new LinkedHashMap<>(doc.getMetadata())
                    : new LinkedHashMap<>();
            copy.add(new Document(doc.getText() != null ? doc.getText() : "", metadata));
        }
        return copy;
    }

    private String normalizeText(String text) {
        String result = NULL_CHARS.matcher(text).replaceAll("");
        result = ZERO_WIDTH_CHARS.matcher(result).replaceAll("");
        result = CARRIAGE_RETURN.matcher(result).replaceAll("\n");
        result = FORM_FEED.matcher(result).replaceAll("\n\n");
        result = CONTROL_CHARS.matcher(result).replaceAll("");
        result = TAB_TO_SPACE.matcher(result).replaceAll("    ");
        result = BINARY_INDICATOR.matcher(result).replaceAll("");
        result = PAGE_HEADER_FOOTER.matcher(result).replaceAll("");
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    /**
     * Lighter normalization for VLM/structured content — preserves markdown tables,
     * structural markers like [Table], section headings, and DocTags markup.
     */
    private String normalizeStructuredText(String text) {
        String result = NULL_CHARS.matcher(text).replaceAll("");
        result = ZERO_WIDTH_CHARS.matcher(result).replaceAll("");
        result = CARRIAGE_RETURN.matcher(result).replaceAll("\n");
        result = FORM_FEED.matcher(result).replaceAll("\n\n");
        result = CONTROL_CHARS.matcher(result).replaceAll("");
        // Skip BINARY_INDICATOR and PAGE_HEADER_FOOTER removal — meaningful in VLM output
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    // ---- Content-type routing ----

    /**
     * Routes documents by content type, matching the logic in DocumentIngestService.
     * Registers DOCUMENT graph nodes, promotes tables to graph nodes, handles formula
     * graphs, and filters images/charts from the text pipeline.
     */
    private List<Document> routeByContentType(String jobId, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job != null && isCancelled(job)) {
            return List.of();
        }

        // Register DOCUMENT graph nodes for unique source files
        registerDocumentNodes(jobId, documents);

        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            if (job != null && isCancelled(job)) {
                return result;
            }
            int routedCountBefore = result.size();
            Map<String, Object> meta = doc.getMetadata();
            String contentType = meta != null ? (String) meta.get(GraphConstants.META_CONTENT_TYPE) : null;
            recordDocumentProgress(job, doc, "ROUTING", "RUNNING", 0, 0, 0,
                    "Routing content type " + (contentType != null ? contentType : "text"),
                    null, null, false);

            if (contentType == null || "text".equals(contentType)) {
                result.add(doc);
                // Persist cell-level table graph if present (e.g. from Google Docs, generic loaders)
                String textTableGraphJson = meta != null && meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (textTableGraphJson != null && !textTableGraphJson.isBlank()) {
                    String textSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, textTableGraphJson, textSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                // Persist formula graph if present (e.g. text docs with embedded formulas)
                String textFormulaGraphJson = meta != null && meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (textFormulaGraphJson != null && !textFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
            } else if ("table".equals(contentType)) {
                // Use full table content for better embeddings when available
                String fullContent = meta.get("full_table_content") instanceof String
                        ? (String) meta.get("full_table_content") : null;
                if (fullContent != null && !fullContent.isBlank()) {
                    result.add(new Document(fullContent, meta));
                } else {
                    result.add(doc);
                }
                // Promote table to knowledge graph node
                promoteTableToGraphNode(doc, jobId);
                // Persist cell-level table graph if present
                String tableGraphJson = meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (tableGraphJson != null && !tableGraphJson.isBlank()) {
                    String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, tableGraphJson, sourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                // Persist formula graph if present (e.g. table docs with computed formulas)
                String tableFormulaGraphJson = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (tableFormulaGraphJson != null && !tableFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
            } else if ("vlm_document".equals(contentType)) {
                // VLM docs with tables — promote tables, pass through for text pipeline
                Boolean vlmProcessed = meta.get(GraphConstants.META_VLM_PROCESSED) instanceof Boolean
                        ? (Boolean) meta.get(GraphConstants.META_VLM_PROCESSED) : false;
                Integer tableCount = meta.get(GraphConstants.META_TABLE_COUNT) instanceof Integer
                        ? (Integer) meta.get(GraphConstants.META_TABLE_COUNT) : 0;
                boolean hasTableGraph = meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        && !((String) meta.get(GraphConstants.META_TABLE_GRAPH)).isBlank();
                if (tableCount > 0 || hasTableGraph) {
                    promoteTableToGraphNode(doc, jobId);
                }
                // Persist cell-level table graph if present (VLM may produce cell graphs)
                String vlmTableGraphJson = meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (vlmTableGraphJson != null && !vlmTableGraphJson.isBlank()) {
                    String vlmSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, vlmTableGraphJson, vlmSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                // Persist formula graph if present
                String vlmFormulaGraphJson = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (vlmFormulaGraphJson != null && !vlmFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
                result.add(doc);
            } else if ("formula_graph".equals(contentType)) {
                // Promote formula graph to TABLE node and persist cell-level graph
                promoteTableToGraphNode(doc, jobId);
                persistFormulaGraph(jobId, doc);
                // Still pass through for keyword indexing
                result.add(doc);
            } else if ("slide".equals(contentType) || "presentation".equals(contentType)) {
                // Slides with embedded tables — promote TABLE node for graph hierarchy
                promoteTableToGraphNode(doc, jobId);
                // Persist cell-level table graph if present
                String slideTableGraphJson = meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (slideTableGraphJson != null && !slideTableGraphJson.isBlank()) {
                    String slideSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, slideTableGraphJson, slideSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                // Persist formula graph if present (e.g. embedded chart formulas)
                String slideFormulaGraphJson = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (slideFormulaGraphJson != null && !slideFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
                result.add(doc);
            } else if ("spreadsheet".equals(contentType)) {
                // Spreadsheet items from ExcelCrawler — treat like tables for graph purposes
                promoteTableToGraphNode(doc, jobId);
                String spreadsheetGraphJson = meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (spreadsheetGraphJson != null && !spreadsheetGraphJson.isBlank()) {
                    String spreadsheetSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, spreadsheetGraphJson, spreadsheetSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                // Also persist formula graph if present
                String formulaGraphJson = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (formulaGraphJson != null && !formulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
                result.add(doc);
            } else if ("image".equals(contentType)) {
                // Exclude image documents from the text/LLM pipeline — they
                // don't contain meaningful text for embedding or LLM extraction.
                // Graph extraction for these docs is handled separately via
                // applyDocumentGraphExtraction called on imageChartDocs.
                log.debug("[Job {}] Routing image document to graph-only extraction", jobId);
                // Persist cell-level table/formula graph if present
                String imgTableGraphJson = meta != null && meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (imgTableGraphJson != null && !imgTableGraphJson.isBlank()) {
                    String imgSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, imgTableGraphJson, imgSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                String imgFormulaGraphJson = meta != null && meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (imgFormulaGraphJson != null && !imgFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
            } else if ("audio".equals(contentType) || "video".equals(contentType)) {
                // Exclude audio/video documents from the text/LLM pipeline — they
                // don't contain meaningful text for embedding or LLM extraction.
                // Graph extraction is handled via applyDocumentGraphExtraction.
                log.debug("[Job {}] Routing {} document to graph-only extraction", jobId, contentType);
                // Persist cell-level table/formula graph if present (e.g. subtitle tables, embedded data)
                String avTableGraphJson = meta != null && meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (avTableGraphJson != null && !avTableGraphJson.isBlank()) {
                    String avSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, avTableGraphJson, avSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                String avFormulaGraphJson = meta != null && meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (avFormulaGraphJson != null && !avFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
            } else if ("chart".equals(contentType)) {
                // Charts may have underlying data tables — promote to TABLE node
                // to match DocumentIngestService behavior
                promoteTableToGraphNode(doc, jobId);
                log.debug("[Job {}] Routing chart document to graph-only extraction with TABLE promotion", jobId);
                // Persist cell-level table/formula graph if present
                String chartTableGraphJson = meta != null && meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (chartTableGraphJson != null && !chartTableGraphJson.isBlank()) {
                    String chartSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, chartTableGraphJson, chartSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                String chartFormulaGraphJson = meta != null && meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (chartFormulaGraphJson != null && !chartFormulaGraphJson.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
            } else {
                // Persist any table/formula graphs that may be present on unknown content types
                String otherTableGraph = meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
                if (otherTableGraph != null && !otherTableGraph.isBlank()) {
                    String otherSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    persistGraphJson(jobId, otherTableGraph, otherSourcePath, GraphConstants.META_TABLE_GRAPH);
                }
                String otherFormulaGraph = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                        ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
                if (otherFormulaGraph != null && !otherFormulaGraph.isBlank()) {
                    persistFormulaGraph(jobId, doc);
                }
                result.add(doc);
            }
            recordDocumentProgress(job, doc, "ROUTING", "COMPLETED", 0, 0, 0,
                    "Routed to " + (result.size() > routedCountBefore ? "text pipeline" : "graph-only pipeline"),
                    null, null, false);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // DYNAMIC PDF CLASSIFICATION AND ROUTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Resolve the effective processing route config for a crawl job.
     * Uses per-request config if provided, otherwise falls back to global default.
     */
    private ProcessingRouteConfig resolveProcessingRouteConfig(UnifiedCrawlJob job) {
        ProcessingRouteConfig perJob = job.getRequest().getProcessingRoute();
        if (perJob != null) {
            return perJob;
        }
        // Return a sensible default when no config is set
        return ProcessingRouteConfig.builder()
                .pdfRoutingMode(ProcessingRouteConfig.PdfRoutingMode.AUTO)
                .extractTablesFromTextPdfs(true)
                .build();
    }

    /**
     * Classify PDF documents and route them to the appropriate processing pipeline.
     *
     * <p>In AUTO mode, each PDF is inspected for embedded images:</p>
     * <ul>
     *   <li><b>Text-only PDFs</b>: Kept with their existing content type (text);
     *       tables are preserved via Tabula extraction by the downstream loader.</li>
     *   <li><b>Image-based PDFs</b>: Tagged as {@code vlm_document} so the downstream
     *       content-type router sends them through VLM processing.</li>
     *   <li><b>Mixed PDFs</b>: Tagged as {@code vlm_document} because any image
     *       content requires VLM for accurate extraction.</li>
     * </ul>
     *
     * <p>Non-PDF documents pass through unchanged.</p>
     */
    private List<Document> classifyAndRoutePdfs(UnifiedCrawlJob job, List<Document> documents,
                                                 ProcessingRouteConfig routeConfig) {
        if (pdfContentClassifier == null) {
            log.debug("[Job {}] No PdfContentClassifier available, skipping PDF classification", job.getJobId());
            return documents;
        }

        ProcessingRouteConfig.PdfRoutingMode mode = routeConfig.getPdfRoutingMode();
        if (mode == ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
            return documents;
        }

        recordEvent(job, "PDF_CLASSIFICATION", "INFO",
                "Starting PDF classification", "mode=" + mode);

        int textOnlyCount = 0;
        int vlmCount = 0;
        int mixedCount = 0;
        int nonPdfCount = 0;
        int classifiedCount = 0;

        for (Document doc : documents) {
            if (isCancelled(job)) break;

            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            // Only classify PDF documents
            String source = getStringMeta(meta, "source");
            String fileName = getStringMeta(meta, "fileName");
            String filePath = source != null ? source : fileName;

            if (filePath == null || !filePath.toLowerCase().endsWith(".pdf")) {
                nonPdfCount++;
                continue;
            }

            // FORCE modes skip classification
            if (mode == ProcessingRouteConfig.PdfRoutingMode.FORCE_VLM) {
                meta.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
                meta.put("pdf_route", "force_vlm");
                vlmCount++;
                classifiedCount++;
                continue;
            }
            if (mode == ProcessingRouteConfig.PdfRoutingMode.FORCE_TEXT) {
                // Leave as text (don't set vlm_document)
                meta.put("pdf_route", "force_text");
                textOnlyCount++;
                classifiedCount++;
                continue;
            }

            // AUTO mode: classify by inspecting page resources
            java.io.File pdfFile = new java.io.File(filePath);
            if (!pdfFile.exists()) {
                log.debug("[Job {}] PDF file not found for classification: {}", job.getJobId(), filePath);
                nonPdfCount++;
                continue;
            }

            try {
                ai.kompile.core.loaders.PdfClassificationResult result = pdfContentClassifier.classify(pdfFile);
                classifiedCount++;

                meta.put("pdf_classification", result.contentType().name());
                meta.put("pdf_classification_time_ms", result.classificationTimeMs());
                meta.put("pdf_page_count", result.pageCount());
                meta.put("pdf_image_pages", result.imagePagesCount());
                meta.put("pdf_text_chars", result.textCharCount());

                switch (result.contentType()) {
                    case TEXT_ONLY:
                        // Keep as text — standard extraction + Tabula will handle tables
                        meta.put("pdf_route", "text_extraction");
                        if (routeConfig.isExtractTablesFromTextPdfs()) {
                            meta.put("extract_tables", true);
                        }
                        textOnlyCount++;
                        break;

                    case IMAGE_BASED:
                        // Route to VLM pipeline
                        meta.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
                        meta.put("pdf_route", "vlm");
                        vlmCount++;
                        break;

                    case MIXED:
                        // Any image content means we need VLM for that document
                        meta.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
                        meta.put("pdf_route", "vlm_mixed");
                        meta.put("pdf_image_page_indices", result.imagePageIndices().toString());
                        mixedCount++;
                        break;

                    default:
                        // UNKNOWN — leave as-is, standard pipeline handles it
                        meta.put("pdf_route", "unknown_fallback");
                        textOnlyCount++;
                        break;
                }
            } catch (Exception e) {
                log.warn("[Job {}] PDF classification failed for {}: {}", job.getJobId(), filePath, e.getMessage());
                meta.put("pdf_route", "classification_failed");
                textOnlyCount++;
            }
        }

        log.info("[Job {}] PDF classification complete: {} classified, {} text-only, {} VLM, {} mixed, {} non-PDF",
                job.getJobId(), classifiedCount, textOnlyCount, vlmCount, mixedCount, nonPdfCount);
        recordEvent(job, "PDF_CLASSIFICATION", "INFO",
                "PDF classification complete",
                classifiedCount + " classified: " + textOnlyCount + " text-only, " + vlmCount + " VLM, " +
                        mixedCount + " mixed, " + nonPdfCount + " non-PDF");

        return documents;
    }

    private String getStringMeta(Map<String, Object> meta, String key) {
        Object val = meta.get(key);
        return val instanceof String ? (String) val : null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAPACITY-BASED FALLBACK ROUTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Prompt the LLM with capacity-aware fallback routing.
     *
     * <p>When the crawl job has a {@link ProcessingRouteConfig} with fallback enabled,
     * this method selects the best available backend via the
     * {@link ProcessingCapacityTracker}. If the local LLM is at capacity, the request
     * falls back to CLI agents or API agents.</p>
     *
     * <p>When fallback is not configured, this delegates directly to
     * {@code llmChat.prompt()}.</p>
     *
     * @param prompt   the full prompt to send
     * @param taskType "llm", "vlm", or "embedding"
     * @param job      the current crawl job (for route config access)
     * @return the LLM response text, or null if all backends failed
     */
    private String promptWithCapacityFallback(String prompt, String taskType, UnifiedCrawlJob job) {
        ProcessingRouteConfig routeConfig = job.getRequest().getProcessingRoute();

        // Fast path: no fallback configured, use default LLM directly
        if (routeConfig == null || !routeConfig.isFallbackEnabled()
                || processingCapacityTracker == null
                || routeConfig.getBackends() == null || routeConfig.getBackends().isEmpty()) {
            if (llmChat == null) {
                log.error("[Job {}] NO LLM CONFIGURED — cannot perform graph extraction. "
                        + "Configure an LLM provider (OpenAI, Anthropic, or CLI agent) in the application settings.",
                        job.getJobId());
                return null;
            }
            return llmChat.prompt(prompt).call().content();
        }

        // Capacity-aware backend selection
        Optional<ProcessingRouteConfig.ProcessingBackend> selected =
                processingCapacityTracker.selectBackend(taskType, routeConfig);

        if (selected.isEmpty()) {
            // All backends at capacity — try the default LLM as last resort
            if (llmChat != null) {
                log.debug("[Job {}] All backends at capacity, falling back to default LLM", job.getJobId());
                return llmChat.prompt(prompt).call().content();
            }
            log.warn("[Job {}] All backends at capacity and no default LLM available", job.getJobId());
            return null;
        }

        ProcessingRouteConfig.ProcessingBackend backend = selected.get();
        String backendId = backend.getId();

        try {
            processingCapacityTracker.recordDispatch(backendId, taskType);

            String response;
            switch (backend.getType()) {
                case LOCAL_MODEL:
                    // Use the local LLMChat
                    if (llmChat == null) {
                        throw new IllegalStateException("LOCAL_MODEL backend selected but no LLMChat available");
                    }
                    response = llmChat.prompt(prompt).call().content();
                    break;

                case CLI_AGENT:
                    // Dispatch to CLI agent via subprocess
                    response = promptViaCli(prompt, backend, job);
                    break;

                case API_AGENT:
                    // Dispatch to external API endpoint
                    response = promptViaApi(prompt, backend, job);
                    break;

                default:
                    log.warn("[Job {}] Unknown backend type: {}", job.getJobId(), backend.getType());
                    response = null;
            }

            processingCapacityTracker.recordCompletion(backendId, taskType, response != null);
            return response;

        } catch (Exception e) {
            processingCapacityTracker.recordCompletion(backendId, taskType, false);
            log.warn("[Job {}] Backend '{}' failed: {}, trying fallback",
                    job.getJobId(), backendId, e.getMessage());

            // Try next available backend on failure
            for (ProcessingRouteConfig.ProcessingBackend fallback : routeConfig.getBackends()) {
                if (fallback.getId().equals(backendId) || !fallback.isEnabled()) continue;
                if (!processingCapacityTracker.canAccept(fallback.getId(), taskType)) continue;

                try {
                    processingCapacityTracker.recordDispatch(fallback.getId(), taskType);
                    String fallbackResponse = dispatchToBackend(prompt, fallback, job);
                    processingCapacityTracker.recordCompletion(fallback.getId(), taskType, fallbackResponse != null);
                    if (fallbackResponse != null) {
                        recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                "Fallback to " + fallback.getId() + " succeeded",
                                "Original backend " + backendId + " failed");
                        return fallbackResponse;
                    }
                } catch (Exception fe) {
                    processingCapacityTracker.recordCompletion(fallback.getId(), taskType, false);
                    log.debug("[Job {}] Fallback '{}' also failed: {}", job.getJobId(), fallback.getId(), fe.getMessage());
                }
            }

            // All fallbacks failed, try direct default LLM
            if (llmChat != null) {
                return llmChat.prompt(prompt).call().content();
            }
            return null;
        }
    }

    private String dispatchToBackend(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                                     UnifiedCrawlJob job) {
        return switch (backend.getType()) {
            case LOCAL_MODEL -> llmChat != null ? llmChat.prompt(prompt).call().content() : null;
            case CLI_AGENT -> promptViaCli(prompt, backend, job);
            case API_AGENT -> promptViaApi(prompt, backend, job);
        };
    }

    /**
     * Send a prompt to a CLI agent (e.g., Claude Code, Codex) via subprocess.
     */
    private String promptViaCli(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                                UnifiedCrawlJob job) {
        String agentName = backend.getAgentName();
        if (agentName == null || agentName.isBlank()) {
            agentName = "claude-cli";
        }

        try {
            // Build a subprocess command to the CLI agent with the prompt on stdin
            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = new ArrayList<>();
            command.add(agentName);
            command.add("-p"); // print/prompt mode
            command.add(prompt);
            pb.command(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Job {}] CLI agent '{}' timed out after 300s", job.getJobId(), agentName);
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[Job {}] CLI agent '{}' exited with code {}", job.getJobId(), agentName, exitCode);
                return null;
            }

            return output;
        } catch (Exception e) {
            log.warn("[Job {}] CLI agent '{}' failed: {}", job.getJobId(), agentName, e.getMessage());
            return null;
        }
    }

    /**
     * Send a prompt to an external API endpoint (OpenAI-compatible).
     */
    private String promptViaApi(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                                UnifiedCrawlJob job) {
        String endpointUrl = backend.getEndpointUrl();
        String apiKey = backend.getApiKey();
        String modelName = backend.getModelName();

        if (endpointUrl == null || endpointUrl.isBlank()) {
            log.warn("[Job {}] API backend '{}' has no endpoint URL", job.getJobId(), backend.getId());
            return null;
        }

        try {
            // Build OpenAI-compatible chat completion request
            String requestBody = buildChatCompletionRequest(prompt, modelName);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpointUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody));

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            java.net.http.HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("[Job {}] API backend '{}' returned 429 (rate limited)", job.getJobId(), backend.getId());
                return null;
            }
            if (response.statusCode() != 200) {
                log.warn("[Job {}] API backend '{}' returned {}: {}",
                        job.getJobId(), backend.getId(), response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }

            return extractContentFromChatResponse(response.body());
        } catch (Exception e) {
            log.warn("[Job {}] API backend '{}' call failed: {}", job.getJobId(), backend.getId(), e.getMessage());
            return null;
        }
    }

    private String buildChatCompletionRequest(String prompt, String modelName) {
        // Build a minimal OpenAI-compatible chat completion request
        String model = modelName != null ? modelName : "default";
        String escapedPrompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}],\"temperature\":0.0}";
    }

    private String extractContentFromChatResponse(String responseBody) {
        try {
            JsonNode root = configObjectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse chat completion response: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Registers DOCUMENT graph nodes for unique source files, creating the
     * SOURCE → DOCUMENT hierarchy in the knowledge graph.
     */
    private void registerDocumentNodes(String jobId, List<Document> documents) {
        if (knowledgeGraphService == null) return;

        Set<String> registeredSources = new HashSet<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                    ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                    : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
            // Fallback: use file_name as source identifier when source_path/source are absent
            // This ensures audio/video/image documents always get a DOCUMENT graph node
            if (sourcePath == null) {
                String fallbackName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                        ? (String) meta.get(GraphConstants.META_FILE_NAME) : null;
                if (fallbackName != null) {
                    sourcePath = "unnamed:" + fallbackName;
                }
            }
            if (sourcePath == null || registeredSources.contains(sourcePath)) continue;

            try {
                String fileName = meta.get("source_filename") instanceof String
                        ? (String) meta.get("source_filename")
                        : meta.get(GraphConstants.META_FILE_NAME) instanceof String ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                String loaderName = meta.get("loader_name") instanceof String
                        ? (String) meta.get("loader_name")
                        : meta.get(GraphConstants.META_LOADER) instanceof String ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                String contentPreview = doc.getText() != null && doc.getText().length() > 200
                        ? doc.getText().substring(0, 200) + "..." : doc.getText();

                // Copy ALL original metadata so cross-document relation strategies
                // can find author, keywords, email fields, hyperlinks, etc.
                Map<String, Object> docMeta = new LinkedHashMap<>(meta);
                docMeta.put(GraphConstants.META_LOADER, loaderName);
                docMeta.put("taskId", jobId);
                if (meta.get("file_extension") instanceof String) {
                    docMeta.put("fileExtension", meta.get("file_extension"));
                }

                knowledgeGraphService.addDocument(
                        "crawl:" + jobId, jobId, sourceType,
                        sourcePath, fileName, contentPreview, docMeta, jobFactSheetId(jobId));
                registeredSources.add(sourcePath);
            } catch (Exception e) {
                log.debug("Failed to register DOCUMENT node for '{}': {}", sourcePath, e.getMessage());
            }
        }
        if (!registeredSources.isEmpty()) {
            log.info("[Job {}] Registered {} DOCUMENT graph nodes", jobId, registeredSources.size());
        }
    }

    /**
     * Creates SNIPPET graph nodes for each chunk with a CONTAINS edge back to
     * the parent DOCUMENT node, providing chunk→document provenance.
     */
    private void registerSnippetNodes(String jobId, List<Document> chunkedDocuments) {
        if (knowledgeGraphService == null || chunkedDocuments == null) return;

        int snippetCount = 0;
        for (int i = 0; i < chunkedDocuments.size(); i++) {
            Document chunk = chunkedDocuments.get(i);
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;

            String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                    ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                    : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
            if (sourcePath == null) continue;

            try {
                // Find the parent DOCUMENT node by its external ID (the source path)
                Optional<GraphNode> parentDoc = knowledgeGraphService.getNodeByExternalId(
                        sourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                if (parentDoc.isEmpty()) continue;

                String snippetId = "chunk:" + jobId + ":" + sourcePath + ":" + i;
                String content = chunk.getText();
                String preview = content != null && content.length() > 200
                        ? content.substring(0, 200) + "..." : content;

                knowledgeGraphService.createSnippetNode(parentDoc.get(), snippetId, preview, i);
                snippetCount++;

                // Mark this passage as graph-indexed in the cross-index tracker
                if (crawlIndexTrackingCallback != null) {
                    String chunkDocId = chunk.getId();
                    if (chunkDocId != null) {
                        crawlIndexTrackingCallback.markPassageGraphIndexed(chunkDocId, snippetId);
                    }
                }
            } catch (Exception e) {
                log.debug("[Job {}] Failed to register SNIPPET node for chunk {}: {}", jobId, i, e.getMessage());
            }
        }

        if (snippetCount > 0) {
            log.info("[Job {}] Registered {} SNIPPET graph nodes", jobId, snippetCount);

            // Update document-level graph status in cross-index tracker
            if (crawlIndexTrackingCallback != null) {
                markDocumentsGraphIndexedInCrossIndex(jobId, chunkedDocuments, snippetCount);
            }
        }
    }

    /**
     * Promotes a table document to a graph node with CONTAINS edge from parent DOCUMENT.
     */
    private void promoteTableToGraphNode(Document doc, String jobId) {
        if (knowledgeGraphService == null) return;
        try {
            Map<String, Object> meta = doc.getMetadata();
            String sheetName = meta.get(GraphConstants.META_SHEET_NAME) instanceof String ? (String) meta.get(GraphConstants.META_SHEET_NAME) : null;
            String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                    ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                    : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
            String structuralSection = meta.get("structural_section") instanceof String
                    ? (String) meta.get("structural_section") : null;

            // Resolve table title: prefer sheetName, then structural_section, then table_id,
            // then compose from page/index to avoid collisions when a PDF has multiple tables
            String tableTitle;
            if (sheetName != null) {
                tableTitle = sheetName;
            } else if (structuralSection != null) {
                tableTitle = structuralSection;
            } else {
                String tableId = meta.get("table_id") instanceof String ? (String) meta.get("table_id") : null;
                if (tableId != null) {
                    tableTitle = tableId;
                } else {
                    Object pageNum = meta.get("table_page_number");
                    Object tableIdx = meta.get("table_index");
                    if (pageNum != null || tableIdx != null) {
                        tableTitle = "Table"
                                + (tableIdx != null ? " " + tableIdx : "")
                                + (pageNum != null ? " (p" + pageNum + ")" : "");
                    } else {
                        tableTitle = "Table";
                    }
                }
            }
            String externalId = "table:" + (sourcePath != null ? sourcePath : jobId) + ":" + tableTitle;

            int rowCount = meta.get(GraphConstants.META_TABLE_ROW_COUNT) instanceof Number
                    ? ((Number) meta.get(GraphConstants.META_TABLE_ROW_COUNT)).intValue() : 0;
            int columnCount = meta.get(GraphConstants.META_TABLE_COLUMN_COUNT) instanceof Number
                    ? ((Number) meta.get(GraphConstants.META_TABLE_COLUMN_COUNT)).intValue() : 0;
            String headerStr = meta.get(GraphConstants.META_TABLE_HEADERS) instanceof String
                    ? (String) meta.get(GraphConstants.META_TABLE_HEADERS) : null;
            List<String> headers = headerStr != null
                    ? Arrays.asList(headerStr.split(",")) : List.of();
            String fullContent = meta.get("full_table_content") instanceof String
                    ? (String) meta.get("full_table_content") : null;
            String preview = fullContent != null && fullContent.length() > 500
                    ? fullContent.substring(0, 500) + "..." : fullContent;

            // Find or create parent DOCUMENT node (mirrors DIS behavior)
            String parentNodeId = null;
            if (sourcePath != null) {
                Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
                        sourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                if (docNode.isEmpty()) {
                    // Create the DOCUMENT node if it doesn't exist yet — prevents
                    // table nodes from being silently dropped when timing causes
                    // the DOCUMENT node to not yet be committed.
                    String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                            ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                    String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                    String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                            ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                    Map<String, Object> docMeta = new LinkedHashMap<>();
                    docMeta.put(GraphConstants.META_LOADER, loaderName);
                    docMeta.put("jobId", jobId);
                    knowledgeGraphService.addDocument(
                            "crawl:" + jobId, jobId, sourceType, sourcePath, fileName, null, docMeta, jobFactSheetId(jobId));
                    docNode = knowledgeGraphService.getNodeByExternalId(
                            sourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                }
                if (docNode.isPresent()) parentNodeId = docNode.get().getNodeId();
            }
            if (parentNodeId == null) {
                log.warn("[Job {}] No DOCUMENT node could be found or created for table '{}' (source: {}), skipping promotion",
                        jobId, tableTitle, sourcePath);
                return;
            }

            Map<String, Object> tableMeta = new LinkedHashMap<>();
            if (meta.get("table_index") instanceof Number) {
                tableMeta.put("tableIndex", ((Number) meta.get("table_index")).intValue());
            }
            if (meta.get("table_page_number") instanceof Number) {
                tableMeta.put("pageNumber", ((Number) meta.get("table_page_number")).intValue());
            }
            if (meta.get("table_extraction_method") instanceof String) {
                tableMeta.put("extractionMethod", (String) meta.get("table_extraction_method"));
            }
            if (meta.get("table_summary") instanceof String tableSummary && !tableSummary.isBlank()) {
                tableMeta.put("tableSummary", tableSummary);
            }
            // Store full table content in metadata so entity preview can render it
            if (fullContent != null) {
                tableMeta.put("fullTableContent", fullContent);
            }

            knowledgeGraphService.createTableNode(parentNodeId, externalId, tableTitle,
                    rowCount, columnCount, headers, preview, tableMeta);
            log.debug("[Job {}] Promoted table '{}' to graph node", jobId, tableTitle);
        } catch (Exception e) {
            log.warn("[Job {}] Failed to promote table to graph node: {}", jobId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CROSS-INDEX TRACKING HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Registers all chunked documents in the cross-index tracker, grouped by source.
     * This makes crawl-originated documents and passages visible in the index browser.
     */
    private void registerChunksInCrossIndex(UnifiedCrawlJob job, List<Document> chunkedDocuments) {
        if (crawlIndexTrackingCallback == null || chunkedDocuments == null || chunkedDocuments.isEmpty()) {
            return;
        }
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId == null) return;

        try {
            // Group chunks by source document
            Map<String, List<Document>> chunksBySource = new LinkedHashMap<>();
            Map<String, String> fileNameBySource = new LinkedHashMap<>();
            for (int i = 0; i < chunkedDocuments.size(); i++) {
                Document chunk = chunkedDocuments.get(i);
                Map<String, Object> meta = chunk.getMetadata();
                if (meta == null) continue;

                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                if (sourcePath == null) continue;

                chunksBySource.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(chunk);

                if (!fileNameBySource.containsKey(sourcePath)) {
                    String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                            ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                    fileNameBySource.put(sourcePath, fileName);
                }
            }

            int totalRegistered = 0;
            for (Map.Entry<String, List<Document>> entry : chunksBySource.entrySet()) {
                String sourcePath = entry.getKey();
                List<Document> chunks = entry.getValue();
                String fileName = fileNameBySource.get(sourcePath);

                List<CrawlIndexTrackingCallback.CrawlPassageInfo> passages = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    Document chunk = chunks.get(i);
                    String chunkId = chunk.getId();
                    if (chunkId == null) continue;
                    passages.add(new CrawlIndexTrackingCallback.CrawlPassageInfo(
                            chunkId, i, chunk.getText(), chunk.getMetadata()));
                }

                totalRegistered += crawlIndexTrackingCallback.registerDocumentAndPassages(
                        sourcePath, fileName, factSheetId, passages);
            }

            if (totalRegistered > 0) {
                log.info("[Job {}] Registered {} passage(s) across {} source(s) in cross-index tracker",
                        job.getJobId(), totalRegistered, chunksBySource.size());
            }
        } catch (Exception e) {
            log.warn("[Job {}] Cross-index registration failed (non-fatal): {}",
                    job.getJobId(), e.getMessage());
        }
    }

    /**
     * Marks a successfully vector-indexed batch in the cross-index tracker.
     */
    private void markBatchVectorIndexedInCrossIndex(List<Document> batch, int indexed) {
        if (crawlIndexTrackingCallback == null || batch == null || indexed <= 0) return;
        try {
            List<String> chunkIds = new ArrayList<>(Math.min(indexed, batch.size()));
            for (int i = 0; i < Math.min(indexed, batch.size()); i++) {
                String id = batch.get(i).getId();
                if (id != null) chunkIds.add(id);
            }
            if (!chunkIds.isEmpty()) {
                crawlIndexTrackingCallback.markPassagesVectorIndexed(chunkIds);
            }
        } catch (Exception e) {
            log.debug("Failed to mark vector-indexed batch in cross-index: {}", e.getMessage());
        }
    }

    /**
     * Updates document-level vector index status after all batches complete.
     */
    private void markDocumentsVectorIndexedInCrossIndex(UnifiedCrawlJob job, List<Document> documents) {
        if (crawlIndexTrackingCallback == null || documents == null || documents.isEmpty()) return;
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId == null) return;

        try {
            // Count indexed passages per source
            Map<String, Integer> countBySource = new LinkedHashMap<>();
            for (Document doc : documents) {
                Map<String, Object> meta = doc.getMetadata();
                if (meta == null) continue;
                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                if (sourcePath != null) {
                    countBySource.merge(sourcePath, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : countBySource.entrySet()) {
                crawlIndexTrackingCallback.markDocumentVectorIndexed(
                        entry.getKey(), factSheetId, entry.getValue());
            }
        } catch (Exception e) {
            log.debug("[Job {}] Failed to update document vector status in cross-index: {}",
                    job.getJobId(), e.getMessage());
        }
    }

    /**
     * Updates document-level graph index status after snippet registration completes.
     */
    private void markDocumentsGraphIndexedInCrossIndex(String jobId,
                                                        List<Document> chunkedDocuments,
                                                        int totalSnippets) {
        Long factSheetId = jobFactSheetId(jobId);
        if (factSheetId == null) return;

        try {
            // Count graph nodes per source
            Map<String, Integer> countBySource = new LinkedHashMap<>();
            for (Document chunk : chunkedDocuments) {
                Map<String, Object> meta = chunk.getMetadata();
                if (meta == null) continue;
                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                if (sourcePath != null) {
                    countBySource.merge(sourcePath, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : countBySource.entrySet()) {
                crawlIndexTrackingCallback.markDocumentGraphIndexed(
                        entry.getKey(), factSheetId, entry.getValue());
            }
        } catch (Exception e) {
            log.debug("[Job {}] Failed to update document graph status in cross-index: {}",
                    jobId, e.getMessage());
        }
    }

    /**
     * Persists an Excel formula dependency graph into the knowledge graph.
     * Creates SHEET (TABLE) and CELL (ENTITY) nodes with CONTAINS and
     * DEPENDS_ON / RANGE_INPUT / CROSS_SHEET_DEPENDS_ON edges.
     */
    @SuppressWarnings("unchecked")
    private GraphPersistResult persistFormulaGraph(String jobId, Document doc) {
        if (knowledgeGraphService == null) return GraphPersistResult.empty();
        Map<String, Object> meta = doc.getMetadata();
        String graphJson = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
        if (graphJson == null || graphJson.isBlank()) return GraphPersistResult.empty();
        String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
        return persistGraphJson(jobId, graphJson, sourcePath);
    }

    @SuppressWarnings("unchecked")
    private GraphPersistResult persistGraphJson(String jobId, String graphJson, String sourcePath) {
        return persistGraphJson(jobId, graphJson, sourcePath, GraphConstants.META_FORMULA_GRAPH);
    }

    @SuppressWarnings("unchecked")
    private GraphPersistResult persistGraphJson(String jobId, String graphJson, String sourcePath, String metadataKey) {
        if (knowledgeGraphService == null) return GraphPersistResult.empty();
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job != null && isCancelled(job)) {
            return GraphPersistResult.empty();
        }
        try {

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> graph = mapper.readValue(graphJson, Map.class);

            List<Map<String, Object>> entities = (List<Map<String, Object>>) graph.get("entities");
            List<Map<String, Object>> relationships = (List<Map<String, Object>>) graph.get("relationships");

            // Resolve or create the parent DOCUMENT node — inherit its factSheetId for scoped persistence
            String parentNodeId = null;
            Long factSheetId = jobFactSheetId(jobId);
            if (sourcePath != null) {
                if (job != null && isCancelled(job)) {
                    return GraphPersistResult.empty();
                }
                Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
                        sourcePath, NodeLevel.DOCUMENT, factSheetId);
                if (docNode.isEmpty()) {
                    // Create DOCUMENT node if missing — prevents formula/table graph entities
                    // from being orphaned when the DOCUMENT node hasn't been committed yet
                    try {
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put("jobId", jobId);
                        knowledgeGraphService.addDocument(
                                "crawl:" + jobId, jobId, "FILE", sourcePath, sourcePath, null, docMeta, jobFactSheetId(jobId));
                        docNode = knowledgeGraphService.getNodeByExternalId(sourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                    } catch (Exception createEx) {
                        log.warn("[Job {}] Failed to create DOCUMENT node for source '{}': {}",
                                jobId, sourcePath, createEx.getMessage());
                    }
                }
                if (docNode.isPresent()) {
                    parentNodeId = docNode.get().getNodeId();
                    factSheetId = docNode.get().getFactSheetId();
                    // Store graph JSON on the DOCUMENT node so resolveExcelGraphJson can find it
                    try {
                        Map<String, Object> docMeta = new HashMap<>();
                        docMeta.put(metadataKey, graphJson);
                        knowledgeGraphService.updateNode(parentNodeId, null, null, docMeta);
                    } catch (Exception e) {
                        log.debug("[Job {}] Could not store {} on DOCUMENT node: {}", jobId, metadataKey, e.getMessage());
                    }
                }
            }

            Map<String, String> externalToNodeId = new HashMap<>();
            Map<String, String> sheetNameToTableNodeId = new HashMap<>();

            // Sort so SHEET/TABLE entities are processed before CELL entities
            if (entities != null) {
                entities.sort((a, b) -> {
                    String typeA = (String) a.getOrDefault("type", "CELL");
                    String typeB = (String) b.getOrDefault("type", "CELL");
                    boolean containerA = "SHEET".equals(typeA) || "TABLE".equals(typeA);
                    boolean containerB = "SHEET".equals(typeB) || "TABLE".equals(typeB);
                    if (containerA && !containerB) return -1;
                    if (!containerA && containerB) return 1;
                    return 0;
                });
            }

            if (entities != null) {
                for (Map<String, Object> entity : entities) {
                    if (job != null && isCancelled(job)) {
                        return GraphPersistResult.empty();
                    }
                    String externalId = (String) entity.get("id");
                    String title = (String) entity.get("title");
                    String type = (String) entity.getOrDefault("type", "CELL");
                    String description = (String) entity.get("description");
                    NodeLevel nodeLevel = ("SHEET".equals(type) || "TABLE".equals(type))
                            ? NodeLevel.TABLE : NodeLevel.ENTITY;

                    Map<String, Object> entityMeta = new HashMap<>();
                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                    entityMeta.put("entity_subtype", type.toLowerCase());
                    if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                    // Extract cell reference from namespaced IDs (e.g., "wb:Budget.xlsx/cell:Sheet1!A1")
                    if (externalId != null) {
                        int cellIdx = externalId.indexOf("cell:");
                        if (cellIdx >= 0) {
                            entityMeta.put("cell_reference", externalId.substring(cellIdx + 5));
                        }
                    }

                    // Create or update node — factSheetId-scoped duplicate detection + update on re-ingest
                    GraphNode created = knowledgeGraphService.createNode(
                            nodeLevel, externalId, title, description, entityMeta, factSheetId);
                    externalToNodeId.put(externalId, created.getNodeId());
                    if (job != null) job.incrementEntityType(type);

                    // Link cells to their TABLE node; TABLE nodes to DOCUMENT
	                    if (parentNodeId != null && nodeLevel == NodeLevel.ENTITY) {
	                        String tableNodeId = findFormulaTableNode(externalId, title, sheetNameToTableNodeId);
	                        String linkParent = tableNodeId != null ? tableNodeId : parentNodeId;
	                        String label = semanticRelationLabel(GraphConstants.REL_CONTAINS);
	                        String containsDescription = semanticRelationDescription(
	                                (tableNodeId != null ? "Table" : "Document") + " contains " + type.toLowerCase() + " " + title,
	                                label);
	                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath, metadataKey,
	                                linkParent, externalId, label, containsDescription, null,
	                                Map.of("entityType", type, "metadataKey", metadataKey));
	                        knowledgeGraphService.createEdgeWithMetadata(
	                                linkParent, created.getNodeId(), EdgeType.CONTAINS, 1.0,
	                                label, containsDescription, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
	                    } else if (parentNodeId != null && nodeLevel == NodeLevel.TABLE) {
	                        if (title != null) {
	                            sheetNameToTableNodeId.put(title.toLowerCase(), created.getNodeId());
	                        }
	                        String label = semanticRelationLabel(GraphConstants.REL_CONTAINS);
	                        String containsDescription = semanticRelationDescription("Document contains sheet " + title, label);
	                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath, metadataKey,
	                                parentNodeId, externalId, label, containsDescription, null,
	                                Map.of("entityType", type, "metadataKey", metadataKey));
	                        knowledgeGraphService.createEdgeWithMetadata(
	                                parentNodeId, created.getNodeId(), EdgeType.CONTAINS, 1.0,
	                                label, containsDescription, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
	                    }
                }
            }

            // Create dependency edges between cells with full metadata
            if (relationships != null) {
                for (Map<String, Object> rel : relationships) {
                    if (job != null && isCancelled(job)) {
                        return GraphPersistResult.empty();
                    }
                    String sourceExtId = (String) rel.get("source");
                    String targetExtId = (String) rel.get("target");
                    String type = (String) rel.getOrDefault("type", "REFERENCES");
                    String relDescription = (String) rel.get("description");
                    String sourceNodeId = externalToNodeId.get(sourceExtId);
                    String targetNodeId = externalToNodeId.get(targetExtId);
	                    if (sourceNodeId != null && targetNodeId != null) {
	                        Map<String, Object> relMeta = (Map<String, Object>) rel.get("metadata");
	                        String label = semanticRelationLabel(type);
	                        String description = semanticRelationDescription(relDescription, label);
	                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath, metadataKey,
	                                sourceExtId, targetExtId, label, description,
	                                numberAsDouble(rel.get("confidence")), relMeta);
	                        knowledgeGraphService.createEdgeWithMetadata(
	                                sourceNodeId, targetNodeId, EdgeType.USER_DEFINED, 1.0,
	                                label, description, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
	                        if (job != null) job.incrementRelationshipType(label);
	                    }
                }
            }

            int entityCount = entities != null ? entities.size() : 0;
            int relCount = relationships != null ? relationships.size() : 0;

            // Update job counters so the UI shows formula graph progress
            if (job != null && !isCancelled(job)) {
                job.getEntitiesExtracted().addAndGet(entityCount);
                job.getRelationshipsExtracted().addAndGet(relCount);
                recordDocumentProgress(job,
                        sourcePath != null ? sourcePath : metadataKey + ":" + Integer.toHexString(graphJson.hashCode()),
                        documentFileName(Map.of(GraphConstants.META_SOURCE_PATH, sourcePath != null ? sourcePath : ""), sourcePath),
                        sourcePath,
                        null,
                        metadataKey,
                        null,
                        "STRUCTURAL_GRAPH",
                        "COMPLETED",
                        0,
                        entityCount,
                        relCount,
                        "Persisted " + metadataKey,
                        null,
                        List.of(metadataKey),
                        true);
            }

            log.info("[Job {}] Persisted formula graph: {} entities, {} relationships", jobId, entityCount, relCount);
            return new GraphPersistResult(entityCount, relCount);
        } catch (Exception e) {
            log.warn("[Job {}] Failed to persist formula graph: {}", jobId, e.getMessage());
            if (job != null && !isCancelled(job)) {
                recordDocumentProgress(job,
                        sourcePath != null ? sourcePath : metadataKey + ":" + Integer.toHexString(graphJson.hashCode()),
                        documentFileName(Map.of(GraphConstants.META_SOURCE_PATH, sourcePath != null ? sourcePath : ""), sourcePath),
                        sourcePath,
                        null,
                        metadataKey,
                        null,
                        "STRUCTURAL_GRAPH",
                        "FAILED",
                        0,
                        0,
                        0,
                        "Failed to persist " + metadataKey,
                        e.getMessage(),
                        List.of(metadataKey),
                        true);
            }
            return GraphPersistResult.empty();
        }
    }

    /**
     * Finds the TABLE node for a cell by parsing sheet name from externalId (e.g., "cell:Sheet1!A1").
     */
    private String findFormulaTableNode(String externalId, String title, Map<String, String> sheetNameToTableNodeId) {
        if (sheetNameToTableNodeId.isEmpty()) return null;
        // Extract cell reference from namespaced IDs: "wb:X/cell:Sheet1!A1" or plain "cell:Sheet1!A1"
        String cellRef = null;
        if (externalId != null) {
            int cellIdx = externalId.indexOf("cell:");
            if (cellIdx >= 0) {
                cellRef = externalId.substring(cellIdx + 5);
            }
        }
        if (cellRef == null && title != null && title.contains("!")) {
            cellRef = title;
        }
        if (cellRef != null && cellRef.contains("!")) {
            String sheetName = cellRef.substring(0, cellRef.indexOf('!')).toLowerCase();
            return sheetNameToTableNodeId.get(sheetName);
        }
        // Fallback for generic table cell IDs: "tbl:ns/table:Name/cell:R0C2"
        if (externalId != null && externalId.contains("/table:") && externalId.contains("/cell:")) {
            int tblStart = externalId.indexOf("/table:") + 7;
            int cellStart = externalId.indexOf("/cell:", tblStart);
            if (cellStart > tblStart) {
                String tblName = externalId.substring(tblStart, cellStart).toLowerCase();
                return sheetNameToTableNodeId.get(tblName);
            }
        }
        return null;
    }

    // ---- Email graph extraction ----

    /**
     * Rule-based email graph extraction. Creates PERSON entities and
     * SENT_BY/SENT_TO/CC_TO/HAS_ATTACHMENT relationships from email metadata.
     * Supports both email.* namespace (HtmlEmailMetadataExtractor, MailLoaderImpl,
     * ImapPopDocumentLoader) and gmail.* namespace (GmailLoaderImpl, GWorkspaceLoaderImpl).
     * No LLM needed.
     */
    private void applyEmailGraphExtraction(String jobId, List<Document> documents) {
        if (knowledgeGraphService == null || documents == null) return;

        UnifiedCrawlJob job = jobs.get(jobId);
        if (job != null && isCancelled(job)) {
            return;
        }
        int entitiesCreated = 0;
        int relationsCreated = 0;

        for (Document doc : documents) {
            if (job != null && isCancelled(job)) {
                return;
            }
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            // Detect email.* or gmail.* namespace and normalize to common keys
            String emailFrom = meta.get("email.from") instanceof String
                    ? (String) meta.get("email.from") : null;
            String emailTo = meta.get("email.to") instanceof String
                    ? (String) meta.get("email.to") : null;
            String emailCc = meta.get("email.cc") instanceof String
                    ? (String) meta.get("email.cc") : null;
            String emailBcc = meta.get("email.bcc") instanceof String
                    ? (String) meta.get("email.bcc") : null;
            String emailSubject = meta.get("email.subject") instanceof String
                    ? (String) meta.get("email.subject") : null;
            String emailInReplyTo = meta.get("email.inReplyTo") instanceof String
                    ? (String) meta.get("email.inReplyTo") : null;
            Object emailRefsObj = meta.get("email.references");
            String emailMessageId = meta.get("email.messageId") instanceof String
                    ? (String) meta.get("email.messageId") : null;
            Object attachObj = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES);

            // Fall back to gmail.* namespace
            if (emailFrom == null) {
                emailFrom = meta.get("gmail.from") instanceof String
                        ? (String) meta.get("gmail.from") : null;
            }
            if (emailTo == null) {
                emailTo = meta.get("gmail.to") instanceof String
                        ? (String) meta.get("gmail.to") : null;
            }
            if (emailCc == null) {
                emailCc = meta.get("gmail.cc") instanceof String
                        ? (String) meta.get("gmail.cc") : null;
            }
            if (emailSubject == null) {
                emailSubject = meta.get("gmail.subject") instanceof String
                        ? (String) meta.get("gmail.subject") : null;
            }
            if (emailBcc == null) {
                emailBcc = meta.get("gmail.bcc") instanceof String
                        ? (String) meta.get("gmail.bcc") : null;
            }
            if (emailInReplyTo == null) {
                emailInReplyTo = meta.get("gmail.inReplyTo") instanceof String
                        ? (String) meta.get("gmail.inReplyTo") : null;
            }
            if (emailRefsObj == null) {
                emailRefsObj = meta.get("gmail.references");
            }
            if (emailMessageId == null) {
                emailMessageId = meta.get("gmail.messageId") instanceof String
                        ? (String) meta.get("gmail.messageId") : null;
            }
            if (attachObj == null) {
                attachObj = meta.get("gmail.attachments");
            }

            if (emailFrom == null) continue;
            if (emailSubject == null) emailSubject = "Email";

            int entitiesBeforeDoc = entitiesCreated;
            int relationsBeforeDoc = relationsCreated;
            recordDocumentProgress(job, doc, "EMAIL_GRAPH", "RUNNING", 0, 0, 0,
                    "Extracting email message graph", null, List.of("EmailGraphExtraction"), false);

            try {
                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;

                // Find or create parent DOCUMENT node
                String parentNodeId = null;
                Long factSheetId = jobFactSheetId(jobId);
                if (sourcePath != null) {
                    Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
                            sourcePath, NodeLevel.DOCUMENT, factSheetId);
                    if (docNode.isEmpty()) {
                        String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                        String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "EMAIL";
                        String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put(GraphConstants.META_LOADER, loaderName);
                        docMeta.put("jobId", jobId);
                        knowledgeGraphService.addDocument(
                                "crawl:" + jobId, jobId, sourceType, sourcePath, fileName, null, docMeta, jobFactSheetId(jobId));
                        docNode = knowledgeGraphService.getNodeByExternalId(
                                sourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                    }
                    if (docNode.isPresent()) parentNodeId = docNode.get().getNodeId();
                }

                // Create EMAIL_MESSAGE entity
                String emailId = "email-msg:" + UUID.nameUUIDFromBytes(
                        (emailFrom + "|" + emailSubject).getBytes()).toString();
                Map<String, Object> emailMeta = new LinkedHashMap<>();
                emailMeta.put("entity_type", "EMAIL_MESSAGE");
                emailMeta.put(GraphConstants.META_SOURCE, jobId);
                if (sourcePath != null) emailMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);

                GraphNode emailNode;
                Optional<GraphNode> existingEmail = knowledgeGraphService.getNodeByExternalId(
                        emailId, NodeLevel.ENTITY, factSheetId);
                if (existingEmail.isPresent()) {
                    emailNode = existingEmail.get();
                } else {
                    emailNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, emailId,
                            emailSubject, "Email from " + emailFrom, emailMeta, factSheetId);
                    entitiesCreated++;
                }

                // Link email to parent DOCUMENT
                if (parentNodeId != null) {
                    String label = semanticRelationLabel(GraphConstants.REL_CONTAINS);
                    String description = semanticRelationDescription(
                            "Document contains email message '" + emailSubject + "'", label);
                    String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                            "email_graph", sourcePath, emailId, label, description, 1.0,
                            metadataProperties(
                                    "entityType", "EMAIL_MESSAGE",
                                    "subject", emailSubject,
                                    "from", emailFrom));
                    knowledgeGraphService.createEdgeWithMetadata(parentNodeId, emailNode.getNodeId(),
                            EdgeType.CONTAINS, 1.0, label, description, metaJson,
                            EdgeProvenance.EXTRACTED, factSheetId);
                }

                // Create PERSON entities from From/To/Cc/Bcc fields
                String[][] addressFields = {
                        {emailFrom, "SENT_BY"},
                        {emailTo, "SENT_TO"},
                        {emailCc, "CC_TO"},
                        {emailBcc, "BCC_TO"}
                };
                for (String[] pair : addressFields) {
                    String addressField = pair[0];
                    String relationType = pair[1];
                    if (addressField == null || addressField.isBlank()) continue;

                    for (String addr : addressField.split(",")) {
                        addr = addr.trim();
                        if (addr.isEmpty()) continue;

                        String emailAddr = addr;
                        String personName = addr;
                        int ltIdx = addr.indexOf('<');
                        if (ltIdx >= 0) {
                            personName = addr.substring(0, ltIdx).trim();
                            int gtIdx = addr.indexOf('>', ltIdx);
                            emailAddr = gtIdx > ltIdx
                                    ? addr.substring(ltIdx + 1, gtIdx).trim()
                                    : addr.substring(ltIdx + 1).trim();
                        }
                        if (personName.isEmpty() || personName.equals(emailAddr)) {
                            personName = emailAddr.contains("@")
                                    ? emailAddr.substring(0, emailAddr.indexOf('@'))
                                    : emailAddr;
                        }

                        String personId = "person:" + UUID.nameUUIDFromBytes(
                                emailAddr.toLowerCase().getBytes()).toString();
                        Map<String, Object> personMeta = new LinkedHashMap<>();
                        personMeta.put("entity_type", "PERSON");
                        personMeta.put("email", emailAddr);
                        personMeta.put(GraphConstants.META_SOURCE, jobId);
                        if (sourcePath != null) personMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);

                        GraphNode personNode;
                        Optional<GraphNode> existingPerson = knowledgeGraphService.getNodeByExternalId(
                                personId, NodeLevel.ENTITY, factSheetId);
                        if (existingPerson.isPresent()) {
                            personNode = existingPerson.get();
                        } else {
                            personNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, personId,
                                    personName, "Email contact: " + emailAddr, personMeta, factSheetId);
                            entitiesCreated++;
                        }

                        String srcId = "SENT_BY".equals(relationType)
                                ? personNode.getNodeId() : emailNode.getNodeId();
                        String tgtId = "SENT_BY".equals(relationType)
                                ? emailNode.getNodeId() : personNode.getNodeId();
                        String label = semanticRelationLabel(relationType);
                        String description = switch (relationType) {
                            case "SENT_BY" -> personName + " sent email '" + emailSubject + "'";
                            case "SENT_TO" -> "Email '" + emailSubject + "' was sent to " + personName;
                            case "CC_TO" -> "Email '" + emailSubject + "' copied " + personName;
                            case "BCC_TO" -> "Email '" + emailSubject + "' blind-copied " + personName;
                            default -> relationType + " relationship";
                        };
                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                "email_graph",
                                "SENT_BY".equals(relationType) ? personId : emailId,
                                "SENT_BY".equals(relationType) ? emailId : personId,
                                label, semanticRelationDescription(description, label), 1.0,
                                metadataProperties(
                                        "email", emailAddr,
                                        "personName", personName,
                                        "subject", emailSubject));
                        knowledgeGraphService.createEdgeWithMetadata(srcId, tgtId,
                                EdgeType.USER_DEFINED, 1.0, label,
                                semanticRelationDescription(description, label), metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                    }
                }

                // Create ATTACHMENT entities
                List<String> attachments = null;
                if (attachObj instanceof List) {
                    attachments = ((List<?>) attachObj).stream()
                            .filter(o -> o instanceof String)
                            .map(o -> (String) o)
                            .collect(Collectors.toList());
                } else if (attachObj instanceof String && !((String) attachObj).isBlank()) {
                    attachments = Arrays.asList(((String) attachObj).split(","));
                }

                if (attachments != null) {
                    for (String attName : attachments) {
                        attName = attName.trim();
                        if (attName.isEmpty()) continue;
                        String attId = "attachment:" + UUID.nameUUIDFromBytes(
                                (emailId + "|" + attName).getBytes()).toString();
                        Map<String, Object> attMeta = new LinkedHashMap<>();
                        attMeta.put("entity_type", "ATTACHMENT");
                        attMeta.put("filename", attName);
                        attMeta.put(GraphConstants.META_SOURCE, jobId);
                        if (sourcePath != null) attMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);

                        GraphNode attNode;
                        Optional<GraphNode> existingAtt = knowledgeGraphService.getNodeByExternalId(
                                attId, NodeLevel.ENTITY, factSheetId);
                        if (existingAtt.isPresent()) {
                            attNode = existingAtt.get();
                        } else {
                            attNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, attId,
                                    attName, "Email attachment", attMeta, factSheetId);
                            entitiesCreated++;
                        }

                        String label = semanticRelationLabel(GraphConstants.REL_HAS_ATTACHMENT);
                        String description = semanticRelationDescription(
                                "Email '" + emailSubject + "' has attachment " + attName, label);
                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                "email_graph", emailId, attId, label, description, 1.0,
                                metadataProperties(
                                        "subject", emailSubject,
                                        "attachmentName", attName));
                        knowledgeGraphService.createEdgeWithMetadata(emailNode.getNodeId(), attNode.getNodeId(),
                                EdgeType.USER_DEFINED, 1.0, label, description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                    }
                }

                // Create REPLIED_TO edge from In-Reply-To header
                if (emailInReplyTo != null && !emailInReplyTo.isBlank()) {
                    String repliedMsgId = "email-msg:" + UUID.nameUUIDFromBytes(
                            emailInReplyTo.getBytes()).toString();
                    Map<String, Object> repliedMeta = new LinkedHashMap<>();
                    repliedMeta.put("entity_type", "EMAIL_MESSAGE");
                    repliedMeta.put("messageId", emailInReplyTo);
                    repliedMeta.put(GraphConstants.META_SOURCE, jobId);

                    GraphNode repliedNode;
                    Optional<GraphNode> existingReplied = knowledgeGraphService.getNodeByExternalId(
                            repliedMsgId, NodeLevel.ENTITY, factSheetId);
                    if (existingReplied.isPresent()) {
                        repliedNode = existingReplied.get();
                    } else {
                        repliedNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, repliedMsgId,
                                "Message " + emailInReplyTo, "Referenced email message", repliedMeta, factSheetId);
                        entitiesCreated++;
                    }

                    String label = semanticRelationLabel(GraphConstants.REL_REPLIED_TO);
                    String description = semanticRelationDescription(
                            "Email '" + emailSubject + "' replies to " + emailInReplyTo, label);
                    String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                            "email_graph", emailId, repliedMsgId, label, description, 1.0,
                            metadataProperties(
                                    "subject", emailSubject,
                                    "messageId", emailInReplyTo));
                    knowledgeGraphService.createEdgeWithMetadata(emailNode.getNodeId(), repliedNode.getNodeId(),
                            EdgeType.USER_DEFINED, 1.0, label, description, metaJson,
                            EdgeProvenance.EXTRACTED, factSheetId);
                    relationsCreated++;
                }

                // Create REFERENCES edges from References header
                List<String> refsList = null;
                if (emailRefsObj instanceof List) {
                    refsList = ((List<?>) emailRefsObj).stream()
                            .filter(o -> o instanceof String)
                            .map(o -> ((String) o).trim())
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                } else if (emailRefsObj instanceof String && !((String) emailRefsObj).isBlank()) {
                    refsList = Arrays.asList(((String) emailRefsObj).trim().split("\\s+"));
                }

                if (refsList != null) {
                    for (String ref : refsList) {
                        // Skip the In-Reply-To message — already handled above
                        if (ref.equals(emailInReplyTo)) continue;
                        String refMsgId = "email-msg:" + UUID.nameUUIDFromBytes(
                                ref.getBytes()).toString();
                        Map<String, Object> refMeta = new LinkedHashMap<>();
                        refMeta.put("entity_type", "EMAIL_MESSAGE");
                        refMeta.put("messageId", ref);
                        refMeta.put(GraphConstants.META_SOURCE, jobId);

                        GraphNode refNode;
                        Optional<GraphNode> existingRef = knowledgeGraphService.getNodeByExternalId(
                                refMsgId, NodeLevel.ENTITY, factSheetId);
                        if (existingRef.isPresent()) {
                            refNode = existingRef.get();
                        } else {
                            refNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, refMsgId,
                                    "Message " + ref, "Referenced email message", refMeta, factSheetId);
                            entitiesCreated++;
                        }

                        String label = semanticRelationLabel(GraphConstants.REL_REFERENCES);
                        String description = semanticRelationDescription(
                                "Email '" + emailSubject + "' references " + ref, label);
                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                "email_graph", emailId, refMsgId, label, description, 0.9,
                                metadataProperties(
                                        "subject", emailSubject,
                                        "messageId", ref));
                        knowledgeGraphService.createEdgeWithMetadata(emailNode.getNodeId(), refNode.getNodeId(),
                                EdgeType.USER_DEFINED, 0.9, label, description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                    }
                }
                if (job == null || !isCancelled(job)) {
                    recordDocumentProgress(job, doc, "EMAIL_GRAPH", "COMPLETED", 0,
                            entitiesCreated - entitiesBeforeDoc,
                            relationsCreated - relationsBeforeDoc,
                            "Email graph extraction complete", null, List.of("EmailGraphExtraction"), true);
                }
            } catch (Exception e) {
                log.warn("[Job {}] Email graph extraction failed for document: {}", jobId, e.getMessage());
                if (job == null || !isCancelled(job)) {
                    recordDocumentProgress(job, doc, "EMAIL_GRAPH", "FAILED", 0, 0, 0,
                            "Email graph extraction failed", e.getMessage(), List.of("EmailGraphExtraction"), true);
                }
            }
        }

        if ((job == null || !isCancelled(job)) && (entitiesCreated > 0 || relationsCreated > 0)) {
            if (job != null) {
                job.getEntitiesExtracted().addAndGet(entitiesCreated);
                job.getRelationshipsExtracted().addAndGet(relationsCreated);
            }
            log.info("[Job {}] Email graph extraction: {} entities, {} relations created",
                    jobId, entitiesCreated, relationsCreated);
        }
    }

    /**
     * Applies document-type-specific graph extraction using registered DocumentGraphExtractor
     * implementations (PDF, Office, Tika, etc.) — rule-based, no LLM.
     */
    private void applyDocumentGraphExtraction(String jobId, List<Document> documents) {
        if (knowledgeGraphService == null || documents == null
                || documentGraphExtractors == null || documentGraphExtractors.isEmpty()) return;

        UnifiedCrawlJob job = jobs.get(jobId);
        if (job != null && isCancelled(job)) {
            return;
        }
        List<Document> documentSnapshot = new ArrayList<>(documents);
        List<ai.kompile.core.graphrag.DocumentGraphExtractor> extractorSnapshot =
                new ArrayList<>(documentGraphExtractors);
        int entitiesCreated = 0;
        int relationsCreated = 0;
        int entitiesExtracted = 0;
        int relationsExtracted = 0;

        for (Document doc : documentSnapshot) {
            if (job != null && isCancelled(job)) {
                return;
            }
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "RUNNING", 0, 0, 0,
                    "Finding matching deterministic graph extractors", null, null, false);
            // For non-Gmail emails, applyEmailGraphExtraction already creates
            // EMAIL_MESSAGE, PERSON, and ATTACHMENT entities. We still run
            // EmailGraphExtractor here to capture additional entity types
            // (CONVERSATION_TOPIC, URL, CALENDAR_EVENT, MAILING_LIST, etc.)
            // that the inline extraction doesn't produce, but we filter out
            // the types already handled to avoid duplicate nodes.
            boolean isNonGmailEmail = meta.get("email.from") != null && meta.get("gmail.from") == null;

            List<ai.kompile.core.graphrag.DocumentGraphExtractor> matchingExtractors = new ArrayList<>();
            for (ai.kompile.core.graphrag.DocumentGraphExtractor candidate : extractorSnapshot) {
                if (candidate.canExtract(doc)) {
                    matchingExtractors.add(candidate);
                }
            }
            List<String> extractorNames = matchingExtractors.stream()
                    .map(extractor -> extractor.getClass().getSimpleName())
                    .collect(Collectors.toList());
            if (matchingExtractors.isEmpty()) {
                String docType = meta.get(GraphConstants.META_DOCUMENT_TYPE) instanceof String ? (String) meta.get(GraphConstants.META_DOCUMENT_TYPE) : "unknown";
                String fName = meta.get(GraphConstants.META_FILE_NAME) instanceof String ? (String) meta.get(GraphConstants.META_FILE_NAME) : "unknown";
                log.debug("[Job {}] No graph extractor matched document: documentType={}, fileName={}", jobId, docType, fName);
                // Even when no extractor matches, ensure a DOCUMENT node exists so every
                // crawled file has graph presence (entities may be added later via re-extraction)
                String noMatchSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                // Fallback to file_name when source_path/source are absent
                if (noMatchSourcePath == null) {
                    String fbName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                            ? (String) meta.get(GraphConstants.META_FILE_NAME) : null;
                    if (fbName != null) noMatchSourcePath = "unnamed:" + fbName;
                }
                if (noMatchSourcePath != null && knowledgeGraphService != null) {
                    var existingDoc = knowledgeGraphService.getNodeByExternalId(
                            noMatchSourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                    if (existingDoc.isEmpty()) {
                        String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                ? (String) meta.get(GraphConstants.META_FILE_NAME) : noMatchSourcePath;
                        String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                        String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put(GraphConstants.META_LOADER, loaderName);
                        docMeta.put("jobId", jobId);
                        knowledgeGraphService.addDocument(
                                "crawl:" + jobId, jobId, sourceType, noMatchSourcePath, fileName, null, docMeta, jobFactSheetId(jobId));
                        log.debug("[Job {}] Created fallback DOCUMENT node for unmatched document: {}", jobId, noMatchSourcePath);
                    }
                }
                recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "SKIPPED", 0, 0, 0,
                        "No deterministic graph extractor matched", null, null, false);
                continue;
            }

            try {
                // Merge results from all matching extractors
                List<ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity> allEntities = new ArrayList<>();
                List<ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation> allRelations = new ArrayList<>();
                for (ai.kompile.core.graphrag.DocumentGraphExtractor extractor : matchingExtractors) {
                    if (job != null && isCancelled(job)) {
                        return;
                    }
                    ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult partial = extractor.extract(doc);
                    allEntities.addAll(partial.entities());
                    allRelations.addAll(partial.relations());
                }
                if (job != null && isCancelled(job)) {
                    return;
                }
                // For non-Gmail emails, filter out entity types already created by
                // applyEmailGraphExtraction to avoid duplicate nodes with different IDs
                if (isNonGmailEmail) {
                    Set<String> inlineHandledTypes = Set.of("EMAIL_MESSAGE", "PERSON", "ATTACHMENT");
                    Set<String> filteredEntityIds = new HashSet<>();
                    allEntities.removeIf(e -> {
                        if (inlineHandledTypes.contains(e.type())) {
                            filteredEntityIds.add(e.id());
                            return true;
                        }
                        return false;
                    });
                    // Remove relations whose source or target was filtered
                    allRelations.removeIf(r -> filteredEntityIds.contains(r.source())
                            || filteredEntityIds.contains(r.target()));
                }
                ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult result =
                        ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult.of(allEntities, allRelations, null);
                if (result.entities().isEmpty() && result.relations().isEmpty()) {
                    // Still ensure a DOCUMENT node exists even when extraction yielded nothing
                    String emptySourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    if (emptySourcePath != null && knowledgeGraphService != null) {
                        var existingDoc = knowledgeGraphService.getNodeByExternalId(
                                emptySourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                        if (existingDoc.isEmpty()) {
                            String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                    ? (String) meta.get(GraphConstants.META_FILE_NAME) : emptySourcePath;
                            String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                    ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                            String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                    ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                            Map<String, Object> docMeta = new LinkedHashMap<>();
                            docMeta.put(GraphConstants.META_LOADER, loaderName);
                            docMeta.put("jobId", jobId);
                            knowledgeGraphService.addDocument(
                                    "crawl:" + jobId, jobId, sourceType, emptySourcePath, fileName, null, docMeta, jobFactSheetId(jobId));
                            log.debug("[Job {}] Created DOCUMENT node for zero-entity extraction: {}", jobId, emptySourcePath);
                        }
                    }
                    recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "COMPLETED", 0, 0, 0,
                            "Extractors returned no entities or relationships", null, extractorNames, true);
                    continue;
                }

                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;

                String parentNodeId = null;
                Long factSheetId = jobFactSheetId(jobId);
                if (sourcePath != null) {
                    Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
                            sourcePath, NodeLevel.DOCUMENT, factSheetId);
                    if (docNode.isEmpty()) {
                        // Create DOCUMENT node if missing — prevents extracted entities
                        // from being orphaned when the DOCUMENT node hasn't been committed yet
                        String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                        String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                        String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put(GraphConstants.META_LOADER, loaderName);
                        docMeta.put("jobId", jobId);
                        knowledgeGraphService.addDocument(
                                "crawl:" + jobId, jobId, sourceType, sourcePath, fileName, null, docMeta, jobFactSheetId(jobId));
                        docNode = knowledgeGraphService.getNodeByExternalId(sourcePath, NodeLevel.DOCUMENT, jobFactSheetId(jobId));
                    }
                    if (docNode.isPresent()) {
                        parentNodeId = docNode.get().getNodeId();
                        if (factSheetId == null) {
                            factSheetId = docNode.get().getFactSheetId();
                        }
                    }
                }

                Map<String, String> externalToNodeId = new HashMap<>();
                for (var entity : result.entities()) {
                    if (job != null && isCancelled(job)) {
                        return;
                    }
                    try {
                        Map<String, Object> entityMeta = new LinkedHashMap<>();
                        entityMeta.put("entity_type", entity.type());
                        entityMeta.put(GraphConstants.META_SOURCE, jobId);
                        if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                        if (entity.properties() != null) entityMeta.putAll(entity.properties());

                        GraphNode node;
                        Optional<GraphNode> existing = knowledgeGraphService.getNodeByExternalId(
                                entity.id(), NodeLevel.ENTITY, factSheetId);
                        if (existing.isPresent()) {
                            node = existing.get();
                            // Merge properties from additional extractors into existing node
                            if (entity.properties() != null && !entity.properties().isEmpty()) {
                                try {
                                    knowledgeGraphService.updateNode(node.getNodeId(), null, null, entityMeta);
                                } catch (Exception mergeEx) {
                                    log.debug("[Job {}] Failed to merge properties into existing entity '{}': {}",
                                            jobId, entity.name(), mergeEx.getMessage());
                                }
                            }
                        } else {
                            node = knowledgeGraphService.createNode(NodeLevel.ENTITY, entity.id(),
                                    entity.name(), entity.description(), entityMeta, factSheetId);
                            entitiesCreated++;
                        }
                        externalToNodeId.put(entity.id(), node.getNodeId());
                        if (job != null) job.incrementEntityType(safeEntityType(entity.type()));

                        if (parentNodeId != null) {
                            String label = semanticRelationLabel(GraphConstants.REL_CONTAINS);
                            String description = semanticRelationDescription(
                                    "Document contains " + safeEntityType(entity.type()) + " " + entity.name(), label);
                            String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                    "document_graph_extractor", sourcePath, entity.id(), label, description, null,
                                    metadataProperties(
                                            "entityType", entity.type(),
                                            "entityName", entity.name()));
                            knowledgeGraphService.createEdgeWithMetadata(parentNodeId, node.getNodeId(),
                                    EdgeType.CONTAINS, 1.0, label, description, metaJson,
                                    EdgeProvenance.EXTRACTED, factSheetId);
                        }
                    } catch (Exception e) {
                        log.debug("[Job {}] Failed to persist entity '{}': {}", jobId, entity.name(), e.getMessage());
                    }
                }

                for (var rel : result.relations()) {
                    if (job != null && isCancelled(job)) {
                        return;
                    }
                    try {
                        String srcNodeId = externalToNodeId.get(rel.source());
                        String tgtNodeId = externalToNodeId.get(rel.target());
                        if (srcNodeId == null || tgtNodeId == null) continue;
                        String label = semanticRelationLabel(rel.type());
                        String description = semanticRelationDescription(rel.description(), label);
                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                "document_graph_extractor", rel.source(), rel.target(), label, description,
                                rel.confidence(), rel.properties());
                        knowledgeGraphService.createEdgeWithMetadata(srcNodeId, tgtNodeId, EdgeType.USER_DEFINED,
                                rel.confidence() != null ? rel.confidence() : 1.0,
                                label, description, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                        if (job != null) job.incrementRelationshipType(label);
                    } catch (Exception e) {
                        log.debug("[Job {}] Failed to persist relation '{}': {}", jobId, rel.type(), e.getMessage());
                    }
                }
                entitiesExtracted += result.entities().size();
                relationsExtracted += result.relations().size();
                if (job == null || !isCancelled(job)) {
                    recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "COMPLETED", 0,
                            result.entities().size(), result.relations().size(),
                            "Deterministic graph extraction complete", null, extractorNames, true);
                }
            } catch (Exception e) {
                String errorDetail = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                log.warn("[Job {}] Document graph extraction failed: {}", jobId, errorDetail, e);
                if (job == null || !isCancelled(job)) {
                    recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "FAILED", 0, 0, 0,
                            "Deterministic graph extraction failed", errorDetail, extractorNames, true);
                }
            }
        }

        if ((job == null || !isCancelled(job)) && (entitiesExtracted > 0 || relationsExtracted > 0)) {
            if (job != null) {
                job.getEntitiesExtracted().addAndGet(entitiesExtracted);
                job.getRelationshipsExtracted().addAndGet(relationsExtracted);
            }
            log.info("[Job {}] Document graph extraction: {} entities, {} relations extracted ({} entities, {} relations created)",
                    jobId, entitiesExtracted, relationsExtracted, entitiesCreated, relationsCreated);
        }
    }

    // ---- Chunking ----

    /**
     * Chunks documents using content-type-aware chunker selection.
     * If no chunkers are available, returns the original documents unchanged.
     */
    private List<Document> chunkDocuments(List<Document> documents, UnifiedCrawlJob job) {
        if (documents == null || documents.isEmpty()) return List.of();
        if (textChunkers == null || textChunkers.isEmpty()) {
            log.debug("No text chunkers available, passing documents through unchunked");
            return documents;
        }

        // Select the appropriate chunker based on content type
        TextChunker chunker = resolveChunkerForContent(documents);
        if (chunker == null) {
            log.debug("No suitable chunker found, passing documents through unchunked");
            return documents;
        }

        log.info("Using chunker '{}' for {} documents", chunker.getName(), documents.size());
        Map<String, Object> options = chunker.getDefaultOptions();

        int parallelism = Math.min(Math.max(1, chunkingParallelism), documents.size());
        List<CostBatch<Document>> batches = planCostBatches(
                documents,
                this::estimateDocumentCost,
                Math.max(1, documents.size()),
                Math.max(1, chunkingTargetCharsPerTask),
                costSortChunks);
        UnifiedCrawlJob.PipelineStepProgress chunkStep = ensurePipelineStep(job, "CHUNKING");
        chunkStep.getTotalItems().set(documents.size());
        chunkStep.getTotalBatches().set(batches.size());
        recordEvent(job, "CHUNKING", "INFO",
                "Planned chunking tasks",
                "documents=" + documents.size() + ", tasks=" + batches.size()
                        + ", parallelism=" + parallelism + ", targetChars=" + chunkingTargetCharsPerTask);

        if (parallelism <= 1 || batches.size() <= 1) {
            List<Document> chunkedDocuments = new ArrayList<>();
            for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
                if (isCancelled(job)) return chunkedDocuments;
                if (docIndex % 10 == 0) {
                    waitForMemoryCapacity(job, "CHUNKING");
                    updateProgress(job, "CHUNKING", estimateProgress(job),
                            "Chunking document " + (docIndex + 1) + "/" + documents.size(),
                            chunkedDocuments.size() + " chunk(s) created");
                }
                chunkedDocuments.addAll(chunkOneDocument(documents.get(docIndex), chunker, options, job));
            }
            return chunkedDocuments;
        }

        ExecutorService chunkExec = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "unified-crawl-chunk-" + job.getJobId().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<List<Document>>> futures = new ArrayList<>(batches.size());
            for (CostBatch<Document> batch : batches) {
                futures.add(chunkExec.submit(() -> {
                    waitForMemoryCapacity(job, "CHUNKING");
                    job.getCurrentBatchSize().set(batch.items().size());
                    job.getCurrentBatchStep().set("CHUNK_TASK " + batch.index() + "/" + batches.size());
                    List<Document> chunked = new ArrayList<>();
                    for (Document document : batch.items()) {
                        if (isCancelled(job)) break;
                        chunked.addAll(chunkOneDocument(document, chunker, options, job));
                    }
                    updateProgress(job, "CHUNKING", estimateProgress(job),
                            "Completed chunking task " + batch.index() + "/" + batches.size(),
                            chunked.size() + " chunk(s), cost=" + batch.cost());
                    incrementPipelineStep(job, "CHUNKING", 0, 1,
                            "Completed chunking task " + batch.index() + "/" + batches.size());
                    return chunked;
                }));
            }

            List<Document> chunkedDocuments = new ArrayList<>();
            for (Future<List<Document>> future : futures) {
                if (isCancelled(job)) break;
                try {
                    chunkedDocuments.addAll(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("Chunking task failed: {}", cause.getMessage());
                    job.getErrors().add("Chunking task failed: " + cause.getMessage());
                    job.getErrorCount().incrementAndGet();
                    recordEvent(job, "CHUNKING", "WARN",
                            "Chunking task failed", cause.getMessage());
                }
            }
            return chunkedDocuments;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            chunkExec.shutdownNow();
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
    }

    private List<Document> chunkOneDocument(Document doc,
                                            TextChunker chunker,
                                            Map<String, Object> options,
                                            UnifiedCrawlJob job) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            incrementPipelineStep(job, "CHUNKING", 1, 0, "Skipped blank document");
            recordDocumentProgress(job, doc, "CHUNKING", "SKIPPED", 0, 0, 0,
                    "Skipped blank document", null, List.of(chunker.getName()), false);
            return List.of();
        }

        try {
            Map<String, Object> meta = new HashMap<>();
            if (doc.getMetadata() != null) {
                for (Map.Entry<String, Object> e : doc.getMetadata().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        meta.put(e.getKey(), e.getValue());
                    }
                }
            }
            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            RetrievedDoc retrievedDoc = new RetrievedDoc(id, text, meta);

            List<RetrievedDoc> chunks = chunker.chunk(retrievedDoc, options);
            List<Document> chunkedDocuments = new ArrayList<>(chunks.size());
            for (RetrievedDoc chunk : chunks) {
                Document chunkDoc = new Document(chunk.getText());
                chunkDoc.getMetadata().putAll(chunk.getMetadata());
                chunkedDocuments.add(chunkDoc);
                job.getChunksCreated().incrementAndGet();
                job.getChunksProcessed().incrementAndGet();
            }
            incrementPipelineStep(job, "CHUNKING", 1, 0,
                    chunkedDocuments.size() + " chunk(s) created");
            recordDocumentProgress(job, doc, "CHUNKING", "COMPLETED", chunkedDocuments.size(), 0, 0,
                    chunkedDocuments.size() + " chunk(s) created", null, List.of(chunker.getName()), false);
            return chunkedDocuments;
        } catch (Exception e) {
            log.warn("Chunking failed for document, using original: {}", e.getMessage());
            job.getChunksCreated().incrementAndGet();
            job.getChunksProcessed().incrementAndGet();
            recordEvent(job, "CHUNKING", "WARN",
                    "Chunking failed; using original document", e.getMessage());
            incrementPipelineStep(job, "CHUNKING", 1, 0,
                    "Chunking failed; using original document");
            recordDocumentProgress(job, doc, "CHUNKING", "FAILED", 1, 0, 0,
                    "Chunking failed; using original document", e.getMessage(), List.of(chunker.getName()), true);
            return List.of(doc);
        }
    }

    /**
     * Content-type-aware chunker selection. Inspects document metadata to pick
     * the most appropriate chunker (HTML, table-aware, or default).
     */
    private TextChunker resolveChunkerForContent(List<Document> documents) {
        if (textChunkers == null || textChunkers.isEmpty()) return null;

        boolean hasVlmContent = false;
        boolean hasHtmlContent = false;
        boolean hasTables = false;

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            if (Boolean.TRUE.equals(meta.get(GraphConstants.META_VLM_PROCESSED))) hasVlmContent = true;
            String contentType = meta.get(GraphConstants.META_CONTENT_TYPE) instanceof String
                    ? (String) meta.get(GraphConstants.META_CONTENT_TYPE) : null;
            if ("table".equals(contentType) || "vlm_document".equals(contentType)) hasTables = true;
            String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                    ? (String) meta.get(GraphConstants.META_LOADER) : null;
            if (loaderName != null && loaderName.toLowerCase().contains("html")) hasHtmlContent = true;
            Object tableCount = meta.get(GraphConstants.META_TABLE_COUNT);
            if (tableCount instanceof Number && ((Number) tableCount).intValue() > 0) hasTables = true;
        }

        // Try HTML chunker for HTML content
        if (hasHtmlContent) {
            TextChunker html = findChunkerByName("html");
            if (html != null) {
                log.info("Auto-selecting 'html' chunker for HTML content");
                return html;
            }
        }

        // Try table-aware chunker for VLM or table content
        if (hasVlmContent || hasTables) {
            TextChunker tableAware = findChunkerByName("table-aware");
            if (tableAware != null) {
                log.info("Auto-selecting 'table-aware' chunker for {} content",
                        hasVlmContent ? "VLM-processed" : "table-heavy");
                return tableAware;
            }
        }

        // Fall back to first available real chunker
        return textChunkers.stream()
                .filter(c -> !isNoOpChunker(c))
                .findFirst()
                .orElse(null);
    }

    private TextChunker findChunkerByName(String name) {
        if (textChunkers == null) return null;
        return textChunkers.stream()
                .filter(c -> name.equals(c.getName()) && !isNoOpChunker(c))
                .findFirst()
                .orElse(null);
    }

    private boolean isNoOpChunker(TextChunker chunker) {
        return chunker.getClass().getSimpleName().contains("NoOp");
    }

    // ---- Helpers ----

    private String buildExtractionPrompt(GraphExtractionConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(GraphExtractionValidator.getExtractionPromptInstructions());

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
        // Find JSON block — may be wrapped in ```json ... ```
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = response.indexOf('\n', jsonStart) + 1;
            int jsonEnd = response.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return response.substring(contentStart, jsonEnd).trim();
            }
        }

        // Try to find raw JSON object
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        return null;
    }

    private void mergeGraphInto(Graph source, Graph target, GraphExtractionConfig config) {
        if (source.getEntities() != null) {
            for (Entity entity : source.getEntities()) {
                // Filter by confidence threshold
                if (entity.getConfidence() != null && entity.getConfidence() < config.getMinConfidence()) {
                    continue;
                }

                // Check for duplicate by title+type (simple entity resolution)
                if (config.isEntityResolution()) {
                    Optional<Entity> existing = target.getEntities().stream()
                            .filter(e -> e.getTitle() != null && e.getTitle().equalsIgnoreCase(entity.getTitle())
                                    && e.getType() != null && e.getType().equals(entity.getType()))
                            .findFirst();
                    if (existing.isPresent()) {
                        // Merge text units
                        if (entity.getTextUnits() != null) {
                            List<String> units = existing.get().getTextUnits();
                            if (units == null) {
                                units = new ArrayList<>();
                                existing.get().setTextUnits(units);
                            }
                            units.addAll(entity.getTextUnits());
                        }
                        continue; // Skip duplicate
                    }
                }
                target.getEntities().add(entity);
            }
        }

        if (source.getRelationships() != null) {
            for (Relationship rel : source.getRelationships()) {
                if (rel.getConfidence() != null && rel.getConfidence() < config.getMinConfidence()) {
                    continue;
                }
                target.getRelationships().add(rel);
            }
        }
    }

    private boolean hasLoaderFor(DocumentSourceDescriptor.SourceType type) {
        if (documentLoaders == null) return false;
        DocumentSourceDescriptor probe = DocumentSourceDescriptor.builder().type(type).pathOrUrl("probe").build();
        return documentLoaders.stream().anyMatch(loader -> {
            try {
                return loader.supports(probe);
            } catch (Exception e) {
                log.debug("Loader support probe failed for source type {}: {}", type, e.getMessage());
                return false;
            }
        });
    }

    private void addSourceType(List<AvailableSourceType> types,
                               DocumentSourceDescriptor.SourceType sourceType,
                               String displayName,
                               String description,
                               List<String> requiredProperties,
                               List<String> optionalProperties) {
        types.add(new AvailableSourceType(
                sourceType.name(),
                displayName,
                description,
                isSourceTypeAvailable(sourceType),
                requiredProperties,
                optionalProperties));
    }

    private boolean isSourceTypeAvailable(DocumentSourceDescriptor.SourceType type) {
        if (type == DocumentSourceDescriptor.SourceType.DIRECTORY
                || type == DocumentSourceDescriptor.SourceType.FILE
                || type == DocumentSourceDescriptor.SourceType.URL
                || type == DocumentSourceDescriptor.SourceType.WEB_CRAWL) {
            return true;
        }
        return hasLoaderFor(type) || hasCrawlerFor(type);
    }

    private boolean hasCrawlerFor(DocumentSourceDescriptor.SourceType type) {
        if (crawlerService == null) {
            return false;
        }
        try {
            return crawlerService.hasCrawlerForSourceType(type);
        } catch (Exception e) {
            log.debug("Crawler support probe failed for source type {}: {}", type, e.getMessage());
            return false;
        }
    }

    private boolean isSourceTypeCrawlable(DocumentSourceDescriptor.SourceType type) {
        return hasCrawlerFor(type);
    }

    /**
     * Returns true when the source type should prefer the crawler path over document loaders.
     * WEB_CRAWL and DIRECTORY sources need recursive traversal that only the crawler provides.
     */
    private boolean isCrawlPreferredSourceType(DocumentSourceDescriptor.SourceType type) {
        return type == DocumentSourceDescriptor.SourceType.WEB_CRAWL
                || type == DocumentSourceDescriptor.SourceType.DIRECTORY;
    }

    private static boolean boolProp(Map<String, Object> props, String key, boolean defaultValue) {
        Object val = props.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private static String stringProp(Map<String, Object> props, String key, String defaultValue) {
        Object val = props.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    /**
     * Converts a Spring AI Document to a RetrievedDoc for use with GraphConstructor.
     */
    private static RetrievedDoc toRetrievedDoc(Document doc) {
        Map<String, Object> metadata = doc.getMetadata() != null
                ? new HashMap<>(doc.getMetadata())
                : new HashMap<>();
        return new RetrievedDoc(doc.getId(), doc.getText(), metadata);
    }

    /**
     * Builds a GraphSchema from GraphExtractionConfig entity/relationship type lists.
     */
    private GraphSchema buildGraphSchema(GraphExtractionConfig config) {
        List<NodeType> nodeTypes = null;
        List<RelationshipType> relTypes = null;

        if (config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
            nodeTypes = config.getEntityTypes().stream()
                    .map(t -> new NodeType(t, t + " entity type", null))
                    .collect(Collectors.toList());
        }

        if (config.getRelationshipTypes() != null && !config.getRelationshipTypes().isEmpty()) {
            relTypes = config.getRelationshipTypes().stream()
                    .map(t -> new RelationshipType(t, t + " relationship type", null))
                    .collect(Collectors.toList());
        }

        if (nodeTypes == null && relTypes == null) {
            return null;
        }

        return new GraphSchema(nodeTypes, relTypes, null);
    }
}
