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

    private static final AtomicInteger t_counter_chunk = new AtomicInteger(0);
    private static final AtomicInteger t_counter_extract = new AtomicInteger(0);
    private static final int DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE = 10;
    private static final int MIN_EMBEDDING_BATCH_SIZE = 4;
    private static final String GRAPH_EXTRACTION_CONFIG_FILENAME = "graph-extraction-config.json";
    private static final com.fasterxml.jackson.databind.ObjectMapper EDGE_METADATA_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // Pre-allocated extractor lists — avoids per-call List.of() allocation in recordDocumentProgress
    private static final List<String> EXTRACTORS_GRAPH_CONSTRUCTOR = List.of("GraphConstructor");
    private static final List<String> EXTRACTORS_INLINE_LLM = List.of("InlineLLM");
    private static final List<String> EXTRACTORS_LOADER = List.of("loader");

    // Sentinel empty int[2] for getOrDefault — avoids allocation when key exists
    private static final int[] EMPTY_COUNTS = new int[2];

    private final ConcurrentHashMap<String, UnifiedCrawlJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> jobFutures = new ConcurrentHashMap<>();
    // Token trackers delegated to CrawlLlmDispatcher
    private final ConcurrentHashMap<String, Long> queuedSequences = new ConcurrentHashMap<>();
    private final Set<String> runningJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong submitSequence = new AtomicLong(0L);
    private final ObjectMapper configObjectMapper = new ObjectMapper();
    private final Path graphExtractionConfigPath =
            KompileHome.dataDir().toPath().resolve("config").resolve(GRAPH_EXTRACTION_CONFIG_FILENAME);
    private volatile long graphExtractionConfigLastModified = Long.MIN_VALUE;
    private volatile CrawlRuntimeConfig crawlRuntimeConfig = CrawlRuntimeConfig.defaults();
    private volatile ThreadPoolExecutor executor;
    private int executorQueueCapacity = -1;

    // Shared thread pools — reused across jobs to avoid per-job allocation/teardown overhead.
    // Sized once in initializeExecutor(), torn down in shutdownExecutor().
    private volatile ExecutorService sharedChunkingPool;
    private volatile ExecutorService sharedGraphExtractionPool;
    private volatile ExecutorService sharedSourceLoadPool;
    private final AtomicInteger t_counter_source = new AtomicInteger(0);

    private volatile int maxConcurrentJobs = 1;

    private volatile int queueCapacity = 25;

    private volatile int memoryWaitThresholdPercent = 82;

    private volatile int memoryCriticalThresholdPercent = 90;

    private volatile int memoryWaitTimeoutSeconds = 300;

    private volatile boolean nativeMemoryCleanupEnabled = true;

    private volatile int nativeMemoryCleanupPasses = 3;

    private volatile int nativeMemoryWaitThresholdPercent = 82;

    private volatile int nativeMemoryCriticalThresholdPercent = 90;

    private volatile int graphExtractionBatchSize = DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;

    private volatile int backgroundGraphThreads = 2;

    private volatile int sourceLoadParallelism = 2;

    private volatile int chunkingParallelism = 2;

    private volatile int graphExtractionParallelism = 16;

    private volatile int graphExtractionTargetCharsPerBatch = 48_000;

    private volatile int chunkingTargetCharsPerTask = 200_000;

    private volatile int configuredVectorBatchSize = 0;

    private volatile boolean postProcessParallel = false;

    private volatile boolean graphConstructorSkipEmbedding = true;

    private volatile boolean graphConstructorPersistMatrixGraph = false;

    private volatile boolean retainResultGraph = false;

    private volatile boolean costSortChunks = true;

    private static final long CONFIG_REFRESH_INTERVAL_NANOS = 5_000_000_000L; // 5 seconds
    private volatile long lastConfigRefreshNanos = 0L;

    private synchronized CrawlRuntimeConfig refreshRuntimeConfig() {
        long now = System.nanoTime();
        if ((now - lastConfigRefreshNanos) < CONFIG_REFRESH_INTERVAL_NANOS && crawlRuntimeConfig != null) {
            return crawlRuntimeConfig;
        }
        lastConfigRefreshNanos = now;
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

        if (memoryMonitor != null) {
            memoryMonitor.applyConfig(memoryWaitThresholdPercent, memoryCriticalThresholdPercent,
                    memoryWaitTimeoutSeconds, nativeMemoryCleanupEnabled, nativeMemoryCleanupPasses,
                    nativeMemoryWaitThresholdPercent, nativeMemoryCriticalThresholdPercent);
        }

        if (graphExtractionOrchestrator != null) {
            graphExtractionOrchestrator.graphExtractionBatchSize = graphExtractionBatchSize;
            graphExtractionOrchestrator.graphExtractionTargetCharsPerBatch = graphExtractionTargetCharsPerBatch;
            graphExtractionOrchestrator.graphExtractionParallelism = graphExtractionParallelism;
            graphExtractionOrchestrator.costSortChunks = costSortChunks;
            graphExtractionOrchestrator.graphConstructorSkipEmbedding = graphConstructorSkipEmbedding;
            graphExtractionOrchestrator.graphConstructorPersistMatrixGraph = graphConstructorPersistMatrixGraph;
            graphExtractionOrchestrator.retainResultGraph = retainResultGraph;
            graphExtractionOrchestrator.memoryCriticalThresholdPercent = memoryCriticalThresholdPercent;
            graphExtractionOrchestrator.memoryWaitTimeoutSeconds = memoryWaitTimeoutSeconds;
        }

        if (vectorIndexingHelper != null) {
            vectorIndexingHelper.configuredVectorBatchSize = configuredVectorBatchSize;
            vectorIndexingHelper.memoryCriticalThresholdPercent = memoryCriticalThresholdPercent;
            vectorIndexingHelper.memoryWaitThresholdPercent = memoryWaitThresholdPercent;
            vectorIndexingHelper.memoryWaitTimeoutSeconds = memoryWaitTimeoutSeconds;
        }

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

    @Autowired
    private CrawlMemoryMonitor memoryMonitor;

    @Autowired
    private PipelineStepTracker pipelineStepTracker;

    @Autowired
    private CrawlLlmDispatcher llmDispatcher;

    @Autowired
    private CrawlDocumentTracker documentTracker;

    @Autowired
    private GraphPersistenceHelper graphPersistenceHelper;

    @Autowired
    private ContentTypeRouter contentTypeRouter;

    @Autowired
    private EmailGraphExtractor emailGraphExtractor;

    @Autowired
    private RuleBasedDocumentGraphExtractor ruleBasedDocumentGraphExtractor;

    @Autowired
    private VectorIndexingHelper vectorIndexingHelper;

    @Autowired
    private GraphExtractionOrchestrator graphExtractionOrchestrator;

    @PostConstruct
    public synchronized void initializeExecutor() {
        refreshRuntimeConfig();
        pipelineStepTracker.setGraphConstructorPresent(graphConstructor != null);
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

        // Initialize shared pools if not already running
        if (sharedChunkingPool == null || sharedChunkingPool.isShutdown()) {
            int chunkThreads = Math.max(1, chunkingParallelism);
            sharedChunkingPool = Executors.newFixedThreadPool(chunkThreads, r -> {
                Thread t = new Thread(r, "unified-crawl-chunk-shared-" + t_counter_chunk.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        if (sharedGraphExtractionPool == null || sharedGraphExtractionPool.isShutdown()) {
            int extractThreads = Math.max(1, graphExtractionParallelism);
            sharedGraphExtractionPool = Executors.newFixedThreadPool(extractThreads, r -> {
                Thread t = new Thread(r, "unified-crawl-extract-shared-" + t_counter_extract.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        if (sharedSourceLoadPool == null || sharedSourceLoadPool.isShutdown()) {
            int sourceThreads = Math.max(1, sourceLoadParallelism);
            sharedSourceLoadPool = Executors.newFixedThreadPool(sourceThreads, r -> {
                Thread t = new Thread(r, "unified-crawl-source-shared-" + t_counter_source.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }

        log.info("Unified crawl executor initialized: maxConcurrentJobs={}, queueCapacity={}, memoryWait={}%, memoryCritical={}%",
                threads, capacity, memoryWaitThresholdPercent, memoryCriticalThresholdPercent);
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (executor != null) {
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
        // Shutdown shared pools
        shutdownPool(sharedChunkingPool, "sharedChunkingPool");
        shutdownPool(sharedGraphExtractionPool, "sharedGraphExtractionPool");
        shutdownPool(sharedSourceLoadPool, "sharedSourceLoadPool");
    }

    private void shutdownPool(ExecutorService pool, String name) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
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
            llmDispatcher.removeTracker(jobId);
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
            applyPipelineStepUpdate(cancelledStep, UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
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

            // Initialize per-job token budget tracker for LLM cost visibility
            TokenBudgetTracker tokenTracker = new TokenBudgetTracker();
            tokenTracker.registerBackend("default");
            llmDispatcher.registerTracker(job.getJobId(), tokenTracker);
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
            ProcessingRouteConfig routeConfig = contentTypeRouter.resolveProcessingRouteConfig(job);
            if (routeConfig.getPdfRoutingMode() != ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
                allDocuments = contentTypeRouter.classifyAndRoutePdfs(job, allDocuments, routeConfig);
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
            allDocuments = new ArrayList<>(contentTypeRouter.routeByContentType(job, allDocuments));
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
                    emailGraphExtractor.applyEmailGraphExtraction(job, emailDocs);
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
                    ruleBasedDocumentGraphExtractor.applyDocumentGraphExtraction(job, docGraphDocs);
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
            contentTypeRouter.registerSnippetNodes(job, chunkedDocuments);
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
                graphExtractionOrchestrator.resetGraphExtractionProgress(job, chunkedDocuments.size());
                updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                        0, chunkedDocuments.size(), 0, 0, 0, 0, null,
                        "Starting graph extraction");
                updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                        "Starting graph extraction", chunkedDocuments.size() + " chunk(s)");
                waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                    log.info("[Job {}] Starting LLM graph extraction for {} chunks (vector indexing queued after graph cleanup)",
                            job.getJobId(), chunkedDocuments.size());
                graphExtractionOrchestrator.extractGraphFromDocuments(chunkedDocuments, graphConfig, unifiedGraph, job, sharedGraphExtractionPool);
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
                    completePipelineStep(job, "GRAPH_EXTRACTION", graphExtractionOrchestrator.completeGraphExtractionProgress(job),
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
                        EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                        if (!vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                            useEmbeddingResolution = false;
                            recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                    "Embedding-assisted entity resolution skipped because embedding model is not ready",
                                    vectorIndexingHelper.embeddingModelNotReadyReason(embModel));
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

            // Phase 7+8: Edge computation and vector indexing — overlapped for throughput.
            // Shared entity edges (CPU/DB) run concurrently with vector indexing (GPU/embedding).
            // Embedding similarity edges run after vector indexing completes since both need GPU.
            boolean doEdgeComputation = graphEdgeComputationService != null && knowledgeGraphService != null;

            // Start shared-entity edge computation in background (CPU/DB — no embedding needed)
            Future<?> sharedEdgeFuture = null;
            ExecutorService edgePool = null;
            if (doEdgeComputation) {
                edgePool = Executors.newSingleThreadExecutor(r -> {
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

            // Run vector indexing concurrently with shared entity edge computation
            // (vector indexing uses GPU/embedding, shared edges use CPU/DB — no contention)
            if (doVectorIndex) {
                EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                if (!vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                    vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                            "Embedding model not ready: " + vectorIndexingHelper.embeddingModelNotReadyReason(embModel));
                } else {
                    try {
                        job.getCurrentFile().set("(indexing " + chunksForIndex.size() + " chunks)");
                        log.info("[Job {}] Indexing {} chunks to vector store (concurrent with edge computation)",
                                job.getJobId(), chunksForIndex.size());
                        vectorIndexingHelper.indexDocuments(chunksForIndex, indexConfig, job);
                        log.info("[Job {}] Vector indexing complete", job.getJobId());
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName();
                        log.warn("[Job {}] Vector indexing deferred after failure: {}",
                                job.getJobId(), errorDetail, e);
                        vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                                "Vector indexing failed and was deferred: " + errorDetail);
                    }
                }
                chunksForIndex.clear();
            } else {
                chunksForIndex.clear();
                skipPipelineStep(job, "VECTOR_INDEXING", "Vector indexing disabled or unavailable");
            }
            job.getCurrentFile().set(null);

            // Now join shared entity edges and run embedding similarity edges
            // (embedding similarity needs GPU which is now free after vector indexing)
            if (doEdgeComputation) {
                if (sharedEdgeFuture != null) {
                    try { sharedEdgeFuture.get(300, TimeUnit.SECONDS); }
                    catch (Exception e) { log.warn("[Job {}] Shared edge future: {}", job.getJobId(), e.getMessage()); }
                }
                if (!isCancelled(job)) {
                    try {
                        boolean memoryReady = waitForMemoryCapacity(job, "EDGE_COMPUTATION");
                        Long factSheetId = jobFactSheetId(job);
                        EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                        if (memoryReady && vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                            graphEdgeComputationService.computeEmbeddingSimilarityEdges(factSheetId, 0.7, 10);
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Embedding similarity edges computed");
                        } else {
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0,
                                    "Embedding similarity edges skipped until embedding model is ready");
                            recordEvent(job, "EDGE_COMPUTATION", "WARN",
                                    "Embedding similarity edges skipped",
                                    memoryReady ? vectorIndexingHelper.embeddingModelNotReadyReason(embModel) : memoryPressureDetail(job));
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

            // Complete
            int finalChunkCount = chunkedDocuments.size();
            if (retainResultGraph) {
                job.setResultGraph(unifiedGraph);
            } else {
                graphExtractionOrchestrator.releaseInMemoryGraph(unifiedGraph);
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

        ExecutorService sourceExec = sharedSourceLoadPool;
        if (sourceExec == null || sourceExec.isShutdown()) {
            sourceExec = Executors.newFixedThreadPool(parallelism, r -> {
                Thread t = new Thread(r, "unified-crawl-source-" + job.getJobId().substring(0, 8));
                t.setDaemon(true);
                return t;
            });
        }
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
            // Pre-compute source metadata once — same for every doc from this source
            Map<String, Object> scopeMeta = sourceMetadata(source, job);
            for (Document doc : docs) {
                if (doc != null && doc.getMetadata() != null) {
                    scopeMeta.forEach(doc.getMetadata()::putIfAbsent);
                }
            }
            int delta = docs.size() - (job.getDocumentsLoaded().get() - loadedBefore);
            if (delta > 0) {
                job.getDocumentsLoaded().addAndGet(delta);
            }
            for (Document doc : docs) {
                recordDocumentProgress(job, doc, "LOADING", "LOADED", 0, 0, 0,
                        "Loaded from source " + label, null, EXTRACTORS_LOADER, false);
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
        String sourceTypeName = source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN";

        CrawlJob crawlJob = crawlerService.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                int discovered = progress.getDocumentsDiscovered() + 1;
                progress.setDocumentsDiscovered(discovered);
                job.getDocumentsDiscovered().incrementAndGet();
                job.recordDiscoveredItem(item.getUrl(),
                        sourceTypeName, source.getLabel());
                progress.setCurrentPhase("DISCOVERING");
                progress.setCurrentItem(item.getUrl());
                updateProgress(job, "DISCOVERING", estimateProgress(job),
                        "Discovered " + discovered + " item(s)", item.getUrl());
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                // Just collect the item — actual document loading happens after crawl completes
                discoveredItems.add(item);
                String shortName = shortName(item.getUrl());
                job.recordDiscoveredItem(shortName,
                        sourceTypeName, source.getLabel());
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
                String shortName = shortName(item.getUrl());
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
            String shortName = shortName(item.getUrl());

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
                                doc.getMetadata().put(GraphConstants.META_SOURCE_TYPE, sourceTypeName);
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

    // extractGraphFromDocuments moved to GraphExtractionOrchestrator

    // extractGraphViaConstructor, isFatalLlmUnavailable, cancelGraphFutures,
    // releaseInMemoryGraph, resetGraphExtractionProgress, incrementGraphChunksProcessed,
    // normalizeGraphChunksProcessed, completeGraphExtractionProgress, graphChunksTotal,
    // recordGraphConstructorProgress, recordCancelledGraphBatch, recordGraphExtractionDiagnostics,
    // recordGraphExtractionBatchPerDocument moved to GraphExtractionOrchestrator

    // indexDocuments, embedBatchOnly, storeEmbeddingBatch, addVectorBatch, collectPendingStoreResult,
    // indexableDocumentText, isIndexableEmbedding, resolveEmbeddingBatchSize, adaptBatchSizeForMemory,
    // primaryEmbeddingModel, isEmbeddingModelReady, embeddingModelNotReadyReason moved to VectorIndexingHelper
    // resolveGraphExtractionBatchSize, resolveGraphExtractionParallelism, deferVectorIndexing moved to
    // GraphExtractionOrchestrator / VectorIndexingHelper respectively

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


    @FunctionalInterface
    private interface PostProcessRunnable {
        void run() throws Exception;
    }

    // Semantic relation and entity mention delegations (no longer called from this class)
    // entityResolutionSimilarityThreshold is still used in executeJob
    private double entityResolutionSimilarityThreshold(GraphExtractionConfig config) { return graphPersistenceHelper.entityResolutionSimilarityThreshold(config); }

    private record SourceLoadResult(int index, String label, List<Document> documents) {
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

        // Use a min-heap by cost to find the lightest batch in O(log B) instead of O(B)
        java.util.PriorityQueue<MutableCostBatch<T>> batchHeap = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(b -> b.cost));
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
        private final java.util.concurrent.Semaphore concurrencyGate;
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
            this.concurrencyGate = new java.util.concurrent.Semaphore(this.maxParallelism);
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

        synchronized void afterBatchComplete(long batchMs, double heapPercent) {
            int oldParallelism = currentParallelism;

            if (heapPercent > CRITICAL_THRESHOLD) {
                currentParallelism = 1;
                consecutiveLowMemoryBatches = 0;
                if (oldParallelism != 1) {
                    // Drain excess permits so only 1 batch can run concurrently
                    drainPermits(oldParallelism, 1);
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
                    int newParallelism = Math.min(maxParallelism, currentParallelism + 1);
                    // Release additional permits to allow more concurrency
                    concurrencyGate.release(newParallelism - currentParallelism);
                    currentParallelism = newParallelism;
                    lastRampTime = now;
                    consecutiveLowMemoryBatches = 0;
                    log.info("Outer graph parallelism increased {} -> {} (reason: {} consecutive low-memory batches, heap {}%)",
                            oldParallelism, currentParallelism, RAMP_AFTER_LOW_COUNT, Math.round(heapPercent * 100));
                }
            } else {
                consecutiveLowMemoryBatches = 0;
            }
        }

        private void drainPermits(int from, int to) {
            int toDrain = from - to;
            for (int d = 0; d < toDrain; d++) {
                if (!concurrencyGate.tryAcquire()) break;
            }
        }

        int getCurrentParallelism() { return currentParallelism; }

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OuterParallelismAdvisor.class);
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
        pipelineStepTracker.initializePipelineSteps(job);
    }

    private UnifiedCrawlJob.PipelineStepProgress ensurePipelineStep(UnifiedCrawlJob job, String phase) {
        return pipelineStepTracker.ensurePipelineStep(job, phase);
    }

    private void updatePipelineStepFromCounters(UnifiedCrawlJob job, String phase, String message, String details) {
        pipelineStepTracker.updatePipelineStepFromCounters(job, phase, message, details);
    }

    // Pipeline step counter refresh delegated to PipelineStepTracker

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
        pipelineStepTracker.updatePipelineStep(job, phase, status,
                completedItems, totalItems, failedItems,
                completedBatches, totalBatches, currentBatchSize,
                currentItem, message);
    }

    private void applyPipelineStepUpdate(UnifiedCrawlJob.PipelineStepProgress step,
                                          UnifiedCrawlJob.PipelineStepStatus status,
                                          int completedItems,
                                          int totalItems,
                                          int failedItems,
                                          int completedBatches,
                                          int totalBatches,
                                          int currentBatchSize,
                                          String currentItem,
                                          String message) {
        pipelineStepTracker.applyPipelineStepUpdate(step, status,
                completedItems, totalItems, failedItems,
                completedBatches, totalBatches, currentBatchSize,
                currentItem, message);
    }

    private void completePipelineStep(UnifiedCrawlJob job, String phase, int completedItems, String message) {
        pipelineStepTracker.completePipelineStep(job, phase, completedItems, message);
    }

    private void failPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        pipelineStepTracker.failPipelineStep(job, phase, message);
    }

    private void skipPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        pipelineStepTracker.skipPipelineStep(job, phase, message);
    }

    private void incrementPipelineStep(UnifiedCrawlJob job,
                                       String phase,
                                       int completedItemsDelta,
                                       int completedBatchesDelta,
                                       String message) {
        pipelineStepTracker.incrementPipelineStep(job, phase, completedItemsDelta, completedBatchesDelta, message);
    }

    // Pipeline step tracking delegated to PipelineStepTracker

    // normalizeStepId, stepDisplayName, stepType delegated to PipelineStepTracker

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
        // Throttle expensive event recording + pipeline step updates to at most once per 250ms.
        // Atomic counters above are always updated — they're cheap.
        long now = System.nanoTime();
        if ((now - lastProgressEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS) {
            lastProgressEventNanos = now;
            if (message != null && !message.isBlank()) {
                recordEvent(job, phase, "INFO", message, details);
            }
            updatePipelineStepFromCounters(job, phase, message, details);
        }
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

    /** Minimum interval between progress event recording (recordEvent + pipeline step update). */
    private static final long PROGRESS_EVENT_INTERVAL_NANOS = 250_000_000L; // 250ms
    private volatile long lastProgressEventNanos = 0L;

    // Memory monitoring delegated to CrawlMemoryMonitor

    private void updateMemorySnapshot(UnifiedCrawlJob job) {
        memoryMonitor.updateMemorySnapshot(job);
    }

    private boolean hasMemoryPressure(UnifiedCrawlJob job, boolean critical) {
        return memoryMonitor.hasMemoryPressure(job, critical);
    }

    private boolean hasNativeMemoryPressure(UnifiedCrawlJob job, int thresholdPercent) {
        return memoryMonitor.hasNativeMemoryPressure(job, thresholdPercent);
    }

    private String memoryPressureDetail(UnifiedCrawlJob job) {
        return memoryMonitor.memoryPressureDetail(job);
    }

    private void trimNativeMemory(UnifiedCrawlJob job, String phase, String reason) {
        String detail = memoryMonitor.trimNativeMemory(job, phase, reason);
        if (detail != null) {
            recordEvent(job, phase, "INFO", "Native memory cleanup", detail);
            log.info("[Job {}] Native memory cleanup during {}: {}", job.getJobId(), phase, detail);
        }
    }

    private String formatBytes(long bytes) {
        return memoryMonitor.formatBytes(bytes);
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
            int total = graphExtractionOrchestrator.graphChunksTotal(job);
            int done = graphExtractionOrchestrator.normalizeGraphChunksProcessed(job);
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
        documentTracker.recordEvent(job, phase, level, message, details);
    }

    // Document progress tracking delegated to CrawlDocumentTracker

    private void recordDocumentProgress(UnifiedCrawlJob job, Document doc, String phase,
                                        String status, int chunksDelta, int entitiesDelta,
                                        int relationshipsDelta, String message, String errorMessage,
                                        List<String> extractors, boolean publishEvent) {
        documentTracker.recordDocumentProgress(job, doc, phase, status, chunksDelta, entitiesDelta,
                relationshipsDelta, message, errorMessage, extractors, publishEvent);
    }

    private void recordDocumentProgress(UnifiedCrawlJob job, RetrievedDoc doc, String phase,
                                        String status, int chunksDelta, int entitiesDelta,
                                        int relationshipsDelta, String message, String errorMessage,
                                        List<String> extractors, boolean publishEvent) {
        documentTracker.recordDocumentProgress(job, doc, phase, status, chunksDelta, entitiesDelta,
                relationshipsDelta, message, errorMessage, extractors, publishEvent);
    }

    private void recordDocumentProgress(UnifiedCrawlJob job, String documentKey, String fileName,
                                        String sourcePath, String sourceType, String contentType,
                                        String loaderName, String phase, String status,
                                        int chunksDelta, int entitiesDelta, int relationshipsDelta,
                                        String message, String errorMessage, List<String> extractors,
                                        boolean publishEvent) {
        documentTracker.recordDocumentProgress(job, documentKey, fileName, sourcePath, sourceType,
                contentType, loaderName, phase, status, chunksDelta, entitiesDelta,
                relationshipsDelta, message, errorMessage, extractors, publishEvent);
    }

    private void recordDocumentVectorProgress(UnifiedCrawlJob job, Document doc, String status,
                                              int embeddedDelta, int indexedDelta, String message,
                                              String errorMessage, boolean publishEvent) {
        documentTracker.recordDocumentVectorProgress(job, doc, status, embeddedDelta, indexedDelta,
                message, errorMessage, publishEvent);
    }

    // Document key/name utilities delegated to CrawlDocumentTracker

    private String documentKey(Document doc) { return documentTracker.documentKey(doc); }
    private String documentKey(RetrievedDoc doc) { return documentTracker.documentKey(doc); }
    private String documentKey(Map<String, Object> metadata, String fallbackId) { return documentTracker.documentKey(metadata, fallbackId); }
    private String documentSourcePath(Map<String, Object> metadata, String fallbackId) { return documentTracker.documentSourcePath(metadata, fallbackId); }
    private String documentFileName(Map<String, Object> metadata, String fallbackId) { return documentTracker.documentFileName(metadata, fallbackId); }
    private static String shortName(String path) { return CrawlDocumentTracker.shortName(path); }
    private String stringMeta(Map<String, Object> metadata, String... keys) { return documentTracker.stringMeta(metadata, keys); }

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
        return PipelineStepTracker.humanizePhase(phase);
    }

    // ---- Text conversion ----

    /**
     * Converts documents to normalized plain text, with VLM-aware lighter-touch
     * normalization for documents processed by visual language models.
     */
    private List<Document> convertDocumentText(List<Document> documents, UnifiedCrawlJob job) {
        if (documents == null || documents.isEmpty()) return List.of();

        // Text normalization is pure CPU regex work with no shared state.
        // Use parallelStream for batches >= 10 — regex normalization is CPU-heavy
        // enough that fork-join overhead is amortized even at small batch sizes.
        if (documents.size() >= 10) {
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
                        String plainText = isVlmContent ? CrawlTextNormalizer.normalizeStructuredText(text) : CrawlTextNormalizer.normalizeText(text);
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

        // Sequential path for small batches (< 10 docs)
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
            String plainText = isVlmContent ? CrawlTextNormalizer.normalizeStructuredText(text) : CrawlTextNormalizer.normalizeText(text);

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

    /**
     * Max text length retained in background graph copies. Rule-based extractors
     * only need metadata and a small text prefix for heuristic matching; keeping
     * full text wastes heap proportional to total corpus size.
     */
    private static final int BACKGROUND_GRAPH_TEXT_LIMIT = 500;

    /** Keys needed by background graph extractors — everything else is dropped to save heap. */
    private static final String[] BACKGROUND_GRAPH_META_KEYS = {
            GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
            GraphConstants.META_CONTENT_TYPE, GraphConstants.META_FILE_NAME,
            GraphConstants.META_LOADER, GraphConstants.META_SOURCE_TYPE,
            GraphConstants.META_VLM_PROCESSED, "document_type"
    };

    private List<Document> copyDocumentsForBackgroundGraph(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> copy = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            // Slim metadata copy — only routing/identification keys, not full maps
            // with formula graph JSON, table graphs, etc. that can be kilobytes each
            Map<String, Object> srcMeta = doc.getMetadata();
            Map<String, Object> metadata = new LinkedHashMap<>(BACKGROUND_GRAPH_META_KEYS.length);
            if (srcMeta != null) {
                for (String key : BACKGROUND_GRAPH_META_KEYS) {
                    Object val = srcMeta.get(key);
                    if (val != null) {
                        metadata.put(key, val);
                    }
                }
            }
            String text = doc.getText();
            if (text != null && text.length() > BACKGROUND_GRAPH_TEXT_LIMIT) {
                text = text.substring(0, BACKGROUND_GRAPH_TEXT_LIMIT);
            }
            copy.add(new Document(text != null ? text : "", metadata));
        }
        return copy;
    }

    // normalizeText and normalizeStructuredText moved to CrawlTextNormalizer

    // routeByContentType, classifyAndRoutePdfs, registerDocumentNodes, registerSnippetNodes,
    // promoteTableToGraphNode, persistFormulaGraph, persistGraphJson, findFormulaTableNode,
    // resolveProcessingRouteConfig, resolveSourcePath, getStringMeta, markDocumentsGraphIndexedInCrossIndex
    // moved to ContentTypeRouter

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
    // LLM dispatch delegated to CrawlLlmDispatcher

    private String promptWithCapacityFallback(String prompt, String taskType, UnifiedCrawlJob job) {
        return llmDispatcher.promptWithCapacityFallback(prompt, taskType, job);
    }

    private void recordTokenUsage(UnifiedCrawlJob job, String backendId, String prompt, String response) {
        llmDispatcher.recordTokenUsage(job, backendId, prompt, response);
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

    // markBatchVectorIndexedInCrossIndex, markDocumentsVectorIndexedInCrossIndex moved to VectorIndexingHelper

    // applyEmailGraphExtraction moved to EmailGraphExtractor
    // applyDocumentGraphExtraction moved to RuleBasedDocumentGraphExtractor

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

        // Use shared chunking pool — avoids per-job thread creation/teardown overhead
        ExecutorService chunkExec = sharedChunkingPool;
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
            // Don't shutdown — shared pool is reused across jobs
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
            // Build source metadata once, filtering nulls. This single copy is shared
            // across all chunks via the RetrievedDoc — avoids O(chunks × metadata_size)
            // HashMap allocations that dominated GC for metadata-rich documents.
            Map<String, Object> baseMeta = new HashMap<>();
            if (doc.getMetadata() != null) {
                for (Map.Entry<String, Object> e : doc.getMetadata().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        baseMeta.put(e.getKey(), e.getValue());
                    }
                }
            }
            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            RetrievedDoc retrievedDoc = new RetrievedDoc(id, text, baseMeta);

            List<RetrievedDoc> chunks = chunker.chunk(retrievedDoc, options);
            List<Document> chunkedDocuments = new ArrayList<>(chunks.size());
            for (RetrievedDoc chunk : chunks) {
                // Chunk metadata from the chunker may contain chunk-specific fields
                // (chunk_index, chunk_start, etc.) layered on top of the base metadata.
                // If the chunker returned the same map reference as baseMeta, we must
                // still copy it into the Document since Document.getMetadata() is mutable.
                Map<String, Object> chunkMeta = chunk.getMetadata();
                Document chunkDoc;
                if (chunkMeta == baseMeta || chunkMeta == null) {
                    // Chunker didn't add chunk-specific fields — share base via shallow copy
                    chunkDoc = new Document(chunk.getText(), new HashMap<>(baseMeta));
                } else {
                    // Chunker added fields — use its map directly (already contains base fields)
                    chunkDoc = new Document(chunk.getText(), chunkMeta);
                }
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


    // buildExtractionPrompt, extractJsonFromResponse, mergeGraphInto,
    // toRetrievedDoc, buildGraphSchema moved to GraphExtractionOrchestrator

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

    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
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

}
