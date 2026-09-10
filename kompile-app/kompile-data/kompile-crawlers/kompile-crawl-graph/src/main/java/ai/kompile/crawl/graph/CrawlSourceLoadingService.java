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

import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawler.*;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.crawler.pipeline.PipelineAwareCrawlListener;
import ai.kompile.core.crawler.pipeline.RoutedCrawlItem;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.crawler.CrawlPipelineRouter;
import ai.kompile.crawler.CrawlerService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.generation.GraphGenerationContext;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Source-loading service for the unified crawl pipeline.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 * Responsibilities:</p>
 * <ul>
 *   <li>Parallel/sequential dispatch of per-source loading ({@link #loadSources})</li>
 *   <li>Source-type routing — crawler vs document-loader ({@link #loadFromSource})</li>
 *   <li>Crawl-based discovery + document loading ({@link #crawlSource})</li>
 *   <li>Registering crawled sources as facts ({@link #registerCrawledSourcesAsFacts})</li>
 * </ul>
 */
@Component
class CrawlSourceLoadingService {

    private static final Logger log = LoggerFactory.getLogger(CrawlSourceLoadingService.class);
    private static final ObjectMapper FINGERPRINT_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    // ── Optional dependencies ───────────────────────────────────────────────

    @Autowired(required = false)
    private CrawlerService crawlerService;

    @Autowired(required = false)
    private List<DocumentLoader> documentLoaders;

    @Autowired(required = false)
    private CrawlFactRegistrationCallback crawlFactRegistrationCallback;

    /** Persistent per-file SHA-256 hash store for incremental crawl skip. */
    @Autowired(required = false)
    private DocumentHashStore documentHashStore;

    /**
     * Knowledge-graph service used to purge stale nodes for CHANGED source documents
     * before re-extraction. Injected optionally so this service can compile in
     * environments where the knowledge-graph module is absent.
     */
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    // ── Required dependencies ───────────────────────────────────────────────

    private final CrawlDocumentTracker documentTracker;
    private final PipelineStepTracker pipelineStepTracker;

    @Value("${kompile.chunker.type:}")
    private String projectChunkerName = "";
    @Value("${kompile.chunker.chunkSize:0}")
    private int projectChunkSize;
    @Value("${kompile.chunker.chunkOverlap:-1}")
    private int projectChunkOverlap = -1;

    // ── Incremental-crawl configuration (set by UnifiedCrawlGraphServiceImpl before each run) ──

    /**
     * Mirror of {@link UnifiedCrawlGraphServiceImpl#crawlIncrementalByContentHash}.
     * Set by the outer service before each job run so that the per-file skip logic
     * here can read it without needing a back-reference to the outer service.
     */
    volatile boolean crawlIncrementalByContentHash = true;

    /**
     * Mirror of {@link UnifiedCrawlGraphServiceImpl#crawlForceFullRecrawl}.
     * When true, every file is (re-)processed this run regardless of stored hashes.
     */
    volatile boolean crawlForceFullRecrawl = false;

    CrawlSourceLoadingService(CrawlDocumentTracker documentTracker,
                              PipelineStepTracker pipelineStepTracker) {
        this.documentTracker = documentTracker;
        this.pipelineStepTracker = pipelineStepTracker;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Loads documents from all sources in the job request, dispatching in parallel
     * when {@code sourceLoadParallelism > 1} and there are multiple sources.
     *
     * @param job                  the running crawl job
     * @param sourceLoadParallelism max parallel source loaders
     * @param sharedSourceLoadPool shared executor (reused across jobs; never shutdown here)
     * @return per-source results (in order); results for failed sources contain an empty list
     */
    List<SourceLoadResult> loadSources(UnifiedCrawlJob job,
                                       int sourceLoadParallelism,
                                       ExecutorService sharedSourceLoadPool) throws InterruptedException {
        List<UnifiedCrawlSource> sources = job.getRequest().getSources();
        int parallelism = Math.min(Math.max(1, sourceLoadParallelism), sources.size());
        documentTracker.recordEvent(job, "LOADING", "INFO",
                "Starting source loading: " + sources.size() + " source(s) with parallelism=" + parallelism, null);

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
            futures.add(GraphGenerationContext.submit(
                    sourceExec, () -> loadSourceAt(job, sourceIndex, source)));
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
                documentTracker.recordEvent(job, "LOADING", "ERROR",
                        "Source load task failed", cause.getClass().getSimpleName() + ": " + cause.getMessage());
                job.getErrors().add("Source load task failed: " + cause.getMessage());
                job.getErrorCount().incrementAndGet();
            }
        }
        return results.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Loads a single source at the given index, updating per-source progress.
     */
    SourceLoadResult loadSourceAt(UnifiedCrawlJob job, int index, UnifiedCrawlSource source) {
        UnifiedCrawlJob.SourceProgress progress = job.getSourceProgress().get(index);
        String label = source.getLabel() != null ? source.getLabel() : "source-" + index;
        progress.setStatus(UnifiedCrawlJob.Status.RUNNING);
        progress.setCurrentPhase("LOADING");
        String safeLocation = SourceCredentialRedactor.redact(source.getPathOrUrl());
        progress.setCurrentItem(safeLocation);
        documentTracker.recordEvent(job, "LOADING", "INFO",
                "Loading source " + (index + 1) + "/" + job.getRequest().getSources().size(), label);
        documentTracker.recordEvent(job, "LOADING", "INFO",
                "Source " + (index + 1) + ": " + label + " (" + (source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN") + ")",
                safeLocation);

        try {
            log.info("[Job {}] Loading source '{}' (type={}, path={})",
                    job.getJobId(), label, source.getSourceType(), safeLocation);
            int loadedBefore = job.getDocumentsLoaded().get();
            List<Document> docs = loadFromSource(source, job, progress);
            // Pre-compute source metadata once — same for every doc from this source
            Map<String, Object> scopeMeta = sourceMetadata(source, job);
            for (Document doc : docs) {
                if (doc != null && doc.getMetadata() != null) {
                    removeSensitiveMetadata(doc.getMetadata());
                    scopeMeta.forEach(doc.getMetadata()::putIfAbsent);
                }
            }
            int delta = docs.size() - (job.getDocumentsLoaded().get() - loadedBefore);
            if (delta > 0) {
                job.getDocumentsLoaded().addAndGet(delta);
            }
            for (Document doc : docs) {
                documentTracker.recordDocumentProgress(job, doc, "LOADING", "LOADED", 0, 0, 0,
                        "Loaded from source " + label, null, List.of("loader"), false);
            }
            progress.setDocumentsLoaded(docs.size());
            progress.setDocumentsDiscovered(Math.max(progress.getDocumentsDiscovered(), docs.size()));
            progress.setStatus(UnifiedCrawlJob.Status.COMPLETED);
            progress.setCurrentPhase("COMPLETED");
            progress.setCurrentItem(null);
            documentTracker.recordEvent(job, "LOADING", "INFO",
                    "Loaded " + docs.size() + " document(s)", label);
            documentTracker.recordEvent(job, "LOADING", "INFO",
                    "Source " + (index + 1) + " complete: " + docs.size() + " document(s) from " + label,
                    "type=" + (source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN") + ", path=" + safeLocation);
            log.info("[Job {}] Loaded {} document(s) from source '{}'",
                    job.getJobId(), docs.size(), label);
            return new SourceLoadResult(index, label, docs);
        } catch (Throwable e) {
            String safeError = SourceCredentialRedactor.redact(e.getMessage());
            log.error("[Job {}] Failed to load from source '{}': {} - {}",
                    job.getJobId(), label, e.getClass().getSimpleName(), safeError);
            progress.setStatus(UnifiedCrawlJob.Status.FAILED);
            progress.setCurrentPhase("FAILED");
            progress.setCurrentItem(null);
            progress.setErrorMessage(e.getClass().getSimpleName() + ": " + safeError);
            job.getErrors().add("Source '" + label + "' failed: "
                    + e.getClass().getSimpleName() + ": " + safeError);
            job.getErrorCount().incrementAndGet();
            documentTracker.recordEvent(job, "LOADING", "ERROR",
                    "Source failed: " + label,
                    e.getClass().getSimpleName() + ": " + safeError);
            return new SourceLoadResult(index, label, List.of());
        }
    }

    /**
     * Routes a single source to the appropriate loader or crawler.
     */
    List<Document> loadFromSource(UnifiedCrawlSource source,
                                  UnifiedCrawlJob job,
                                  UnifiedCrawlJob.SourceProgress progress) throws Exception {
        if (source.getSourceType() == null) {
            throw new IllegalArgumentException(
                    "Source '" + source.getLabel() + "' has no sourceType. " +
                    "Set sourceType to one of: DIRECTORY, FILE, URL, WEB_CRAWL, etc. " +
                    "(pathOrUrl=" + SourceCredentialRedactor.redact(source.getPathOrUrl()) + ")");
        }
        Map<String, Object> directMetadata = runtimeSourceMetadata(source, job);
        if ((source.getPathOrUrl() == null || source.getPathOrUrl().isBlank())
                && !DocumentSourceDescriptor.locatorOptional(source.getSourceType())
                && !hasIdentityMetadata(directMetadata)) {
            throw new IllegalArgumentException(
                    "Source '" + source.getLabel() + "' has no pathOrUrl. " +
                    "Provide a directory path, file path, or URL. " +
                    "(sourceType=" + source.getSourceType() + ")");
        }
        applyDirectLoaderLimits(source, directMetadata);
        String directPathOrUrl = applyDirectIdentifierLimits(source, directMetadata);
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(source.getSourceType())
                .pathOrUrl(directPathOrUrl)
                .metadata(directMetadata)
                .build();

        // For WEB_CRAWL, DIRECTORY, and FILE sources, prefer the crawler path when
        // available. The crawler path is where unified crawl records discovered items
        // and applies the content-hash incremental skip/purge logic before loading.
        if (isCrawlPreferredSourceType(source.getSourceType())) {
            if (crawlerService != null && isSourceTypeCrawlable(source.getSourceType())) {
                return crawlSource(source, job, progress);
            }
            // If no crawler is available, fall through to try loaders as a last resort
            log.warn("[Job {}] No crawler available for {} source '{}', falling through to loaders",
                    job.getJobId(), source.getSourceType(), source.getLabel());
        }

        // Try document loaders for non-crawl source types (FILE, EMAIL, SLACK, etc.)
        RoutedCrawlItem directRoute = routeDirectSource(source, job, descriptor);
        IngestPipelineDefinition directPipeline = directRoute != null ? directRoute.pipeline() : null;
        DocumentLoader directLoader = resolveLoader(descriptor, firstNonBlank(source.getLoaderName(),
                directPipeline != null ? directPipeline.getLoaderName() : null));
        if (directLoader != null) {
                    DocumentLoader loader = directLoader;
                    log.info("[Job {}] Using loader '{}' for source '{}' (pipeline={})",
                            job.getJobId(), loader.getName(), source.getLabel(),
                            directPipeline != null ? directPipeline.getPipelineId() : "automatic");
                    List<Document> docs = loader.load(descriptor, loaderProgress -> {
                        // loaderProgress.progressPercent() is 0-100, not a document count.
                        // Use currentStep as currentFile if available; do not treat percent as a counter.
                        if (loaderProgress.currentStep() != null) {
                            String safeStep = SourceCredentialRedactor.redact(loaderProgress.currentStep());
                            job.getCurrentFile().set(safeStep);
                            progress.setCurrentItem(safeStep);
                        }
                        if (loaderProgress.message() != null) {
                            String safeMessage = SourceCredentialRedactor.redact(loaderProgress.message());
                            documentTracker.recordEvent(job, "LOADING", "INFO",
                                    safeMessage, source.getLabel());
                            log.info("[Job {}] Loader progress ({}%): {}", job.getJobId(),
                                    loaderProgress.progressPercent(), safeMessage);
                        }
                    });
                    if (source.getMaxDocuments() > 0 && docs.size() > source.getMaxDocuments()) {
                        docs = new ArrayList<>(docs.subList(0, source.getMaxDocuments()));
                    }
                    docs.forEach(doc -> applyPipelineMetadata(doc.getMetadata(), source, directRoute, job));
                    return docs;
        }

        // Fall back to crawler for other crawlable source types (URL, etc.)
        if (crawlerService != null && isSourceTypeCrawlable(source.getSourceType())) {
            return crawlSource(source, job, progress);
        }

        throw new IllegalStateException("No loader or crawler available for source type: " + source.getSourceType());
    }

    static void applyDirectLoaderLimits(
            UnifiedCrawlSource source, Map<String, Object> metadata) {
        int limit = source.getMaxDocuments();
        if (limit <= 0 || metadata == null || source.getSourceType() == null) return;
        if (source.getSourceType() == DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE) {
            metadata.put("gmailMaxMessages", boundedLimit(metadata.get("gmailMaxMessages"), limit));
            metadata.put("driveMaxFiles", boundedLimit(metadata.get("driveMaxFiles"), limit));
            metadata.put("calendarMaxEvents", boundedLimit(metadata.get("calendarMaxEvents"), limit));
        }
        String property = switch (source.getSourceType()) {
            case JIRA -> "maxIssues";
            case REDDIT -> "postLimit";
            case NOTION -> "maxPages";
            case CONFLUENCE, GDOCS -> "maxDocuments";
            case SLACK -> "limit";
            case SLACK_HISTORY, DISCORD, DISCORD_HISTORY, EMAIL, IMAP, POP3, GMAIL -> "maxMessages";
            default -> null;
        };
        if (property == null) return;
        metadata.put(property, boundedLimit(metadata.get(property), limit));
    }

    private static int boundedLimit(Object configured, int limit) {
        int requested = configured instanceof Number number ? number.intValue() : limit;
        if (configured instanceof String text) {
            try {
                requested = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                requested = limit;
            }
        }
        return Math.max(1, Math.min(limit, requested));
    }

    static String applyDirectIdentifierLimits(
            UnifiedCrawlSource source, Map<String, Object> metadata) {
        if (source == null || source.getSourceType() == null || metadata == null) {
            return source == null ? null : source.getPathOrUrl();
        }
        String property = switch (source.getSourceType()) {
            case GDOCS -> "documentIds";
            case GDRIVE -> "fileIds";
            case ONEDRIVE -> "itemIds";
            default -> null;
        };
        if (property == null) return source.getPathOrUrl();
        Object configured = metadata.get(property);
        List<String> identifiers = sourceIdentifiers(
                configured == null ? source.getPathOrUrl() : configured);
        if (identifiers.isEmpty() && configured != null) {
            identifiers = sourceIdentifiers(source.getPathOrUrl());
        }
        int limit = source.getMaxDocuments();
        if (limit > 0 && identifiers.size() > limit) {
            identifiers = new ArrayList<>(identifiers.subList(0, limit));
        }
        if (configured != null || source.getSourceType() == DocumentSourceDescriptor.SourceType.GDOCS) {
            metadata.put(property, List.copyOf(identifiers));
            return source.getPathOrUrl();
        }
        return String.join(",", identifiers);
    }

    private static List<String> sourceIdentifiers(Object value) {
        if (value == null) return List.of();
        List<String> identifiers = new ArrayList<>();
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) identifiers.add(item.toString().trim());
            }
        } else {
            for (String item : value.toString().split(",")) {
                if (!item.isBlank()) identifiers.add(item.trim());
            }
        }
        return identifiers;
    }

    /**
     * Crawls a source using the {@link CrawlerService}, collecting discovered items
     * and then loading documents from them.
     */
    List<Document> crawlSource(UnifiedCrawlSource source,
                               UnifiedCrawlJob job,
                               UnifiedCrawlJob.SourceProgress progress) throws Exception {
        // Extract crawlerId from source properties if provided
        String crawlerId = null;
        Map<String, Object> props = runtimeSourceMetadata(source, job);
        if (!props.isEmpty()) {
            Object id = props.get("crawlerId");
            if (id instanceof String s && !s.isBlank()) {
                crawlerId = s;
            }
        }

        // Forward web-crawl-specific properties from the source properties map
        boolean sameDomainOnly = boolProp(props, "sameDomainOnly", true);
        boolean respectRobotsTxt = boolProp(props, "respectRobotsTxt", true);
        String userAgent = stringProp(props, "userAgent", null);
        boolean followSymlinks = boolProp(props, "followSymlinks", false);
        boolean includeHidden = boolProp(props, "includeHidden", false);

        List<IngestPipelineDefinition> pipelines = effectivePipelines(job);
        validateSourcePipeline(source, pipelines);
        CrawlConfig.CrawlConfigBuilder configBuilder = CrawlConfig.builder()
                .crawlerId(crawlerId)
                .seed(source.getPathOrUrl())
                .sourceType(source.getSourceType())
                .maxDepth(source.getMaxDepth())
                .maxDocuments(source.getMaxDocuments() > 0 ? source.getMaxDocuments() : 1000)
                .includePatterns(source.getIncludePatterns())
                .excludePatterns(source.getExcludePatterns())
                .allowedContentTypes(source.getAllowedContentTypes())
                .properties(new HashMap<>(props))
                .sameDomainOnly(sameDomainOnly)
                .respectRobotsTxt(respectRobotsTxt)
                .forceRecrawl(true)
                .loaderName(source.getLoaderName())
                .chunkerName(source.getChunkerName())
                .pipelines(pipelines)
                .routeRules(hasText(source.getPipelineId()) ? List.of()
                        : job.getRequest() != null && job.getRequest().getRouteRules() != null
                        ? List.copyOf(job.getRequest().getRouteRules()) : List.of())
                .defaultPipelineId(hasText(source.getPipelineId()) ? source.getPipelineId()
                        : job.getRequest() != null ? job.getRequest().getDefaultPipelineId() : null);

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
        Map<CrawlItem, RoutedCrawlItem> routedItems = Collections.synchronizedMap(new IdentityHashMap<>());
        CompletableFuture<Void> crawlDone = new CompletableFuture<>();
        String sourceTypeName = source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN";
        int crawlErrorsBeforeDiscovery = job.getErrorCount().get();

        CrawlJob crawlJob = crawlerService.startCrawl(config, new PipelineAwareCrawlListener() {
            @Override
            public void onItemRouted(RoutedCrawlItem routedItem) {
                if (routedItem != null && routedItem.item() != null) {
                    routedItems.put(routedItem.item(), routedItem);
                }
            }

            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                String safeItemUrl = SourceCredentialRedactor.redact(item.getUrl());
                int discovered = progress.getDocumentsDiscovered() + 1;
                progress.setDocumentsDiscovered(discovered);
                job.getDocumentsDiscovered().incrementAndGet();
                job.recordDiscoveredItem(safeItemUrl,
                        sourceTypeName, source.getLabel());
                progress.setCurrentPhase("DISCOVERING");
                progress.setCurrentItem(safeItemUrl);
                documentTracker.recordEvent(job, "DISCOVERING", "INFO",
                        "Discovered " + discovered + " item(s) under " + source.getLabel(), safeItemUrl);
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                // Just collect the item — actual document loading happens after crawl completes
                discoveredItems.add(item);
                String shortName = CrawlDocumentTracker.shortName(
                        SourceCredentialRedactor.redact(item.getUrl()));
                job.recordDiscoveredItem(shortName,
                        sourceTypeName, source.getLabel());
                log.info("[Job {}] Discovered file: {} (total: {})",
                        job.getJobId(), shortName, discoveredItems.size());
                documentTracker.recordEvent(job, "DISCOVERING", "INFO",
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
                String shortName = CrawlDocumentTracker.shortName(
                        SourceCredentialRedactor.redact(item != null ? item.getUrl() : null));
                String errorMsg = "Failed to crawl '" + shortName + "': "
                        + SourceCredentialRedactor.redact(error != null ? error.getMessage() : null);
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
        pipelineStepTracker.completePipelineStep(job, "DISCOVERING", discoveredItems.size(),
                discoveredItems.size() + " item(s) discovered");

        log.info("[Job {}] Loading documents from {} discovered files...",
                job.getJobId(), discoveredItems.size());

        // Now load documents from discovered items (off the crawler thread).
        //
        // INCREMENTAL CRAWL LOGIC
        // ───────────────────────
        // When crawlIncrementalByContentHash=true AND crawlForceFullRecrawl=false:
        //   • Compute (or reuse) the SHA-256 hash of each file's content.
        //   • UNCHANGED (hash matches stored) → skip the expensive load + downstream steps;
        //     the file's existing graph nodes (by _sourceDocumentId provenance) are kept.
        //   • CHANGED (hash mismatch) → purge the old nodes first, then process as new.
        //   • NEW (no stored hash) → process normally.
        //
        // IMPORTANT: Only per-file steps are skipped here (LOADING → VECTOR_INDEXING).
        // ENTITY_RESOLUTION, EDGE_COMPUTATION, and ENRICHMENT are global steps that run
        // over the FULL current graph (kept nodes from skipped files + newly processed
        // nodes) and are NOT affected by this logic.
        //
        // Telemetry counters job.filesSkippedUnchanged and job.filesReprocessed are
        // updated so the progress snapshot surfaces the counts to the UI.
        Long factSheetId = job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
        boolean incrementalEnabled = crawlIncrementalByContentHash && !crawlForceFullRecrawl
                && documentHashStore != null;
        String sourceScopeId = sourceScopeId(source);

        // Reconcile local-file tombstones after a complete, error-free discovery pass.
        // Existing-but-excluded files are intentionally retained; only paths confirmed
        // absent on disk are removed. Remote discovery is not authoritative enough to
        // infer deletion (a transient network/robots/depth change must not erase graph data).
        if (incrementalEnabled
                && isLocalSourceType(source.getSourceType())
                && job.getErrorCount().get() == crawlErrorsBeforeDiscovery) {
            Set<String> liveSources = discoveredItems.stream()
                    .map(CrawlItem::getUrl)
                    .filter(Objects::nonNull)
                    .map(CrawlSourceLoadingService::persistentItemIdentity)
                    .collect(Collectors.toSet());
            List<String> missingSources = documentHashStore.findMissingSources(
                    factSheetId, sourceScopeId, liveSources);
            int stagedDeletes = 0;
            for (String missingSource : missingSources) {
                Path missingPath = toFilePath(missingSource);
                if (missingPath == null || Files.exists(missingPath)) {
                    continue;
                }
                if (purgeNodesForSource(job, factSheetId, missingSource)) {
                    documentHashStore.stageDeletion(factSheetId, missingSource, job.getJobId());
                    stagedDeletes++;
                }
            }
            if (stagedDeletes > 0) {
                log.info("[Job {}] Staged {} deleted source(s) for incremental manifest commit",
                        job.getJobId(), stagedDeletes);
                documentTracker.recordEvent(job, "LOADING", "INFO",
                        "Removed " + stagedDeletes + " deleted source(s) from the knowledge graph",
                        source.getLabel());
            }
        }

        // Items actually loaded (not skipped) stage their hashes in memory. The durable
        // manifest is published only by DocumentHashStore's GraphBuildCompletedEvent
        // listener, after graph persistence has succeeded.
        List<Document> collectedDocs = new ArrayList<>();
        for (CrawlItem item : discoveredItems) {
            if (isCancelled(job)) return collectedDocs;
            String itemUrl = item.getUrl();
            String safeItemUrl = SourceCredentialRedactor.redact(itemUrl);
            String persistentItemId = persistentItemIdentity(itemUrl);
            String shortName = CrawlDocumentTracker.shortName(safeItemUrl);

            job.getCurrentFile().set(shortName);
            progress.setCurrentPhase("LOADING");
            progress.setCurrentItem(shortName);
            int loaded = job.getDocumentsLoaded().get();
            log.info("[Job {}] Loading file: {} ({}/{})",
                    job.getJobId(), shortName, loaded + 1, discoveredItems.size());
            documentTracker.recordEvent(job, "LOADING", "INFO",
                    "Loading discovered file " + (collectedDocs.size() + 1) + "/" + discoveredItems.size(),
                    shortName);

            RoutedCrawlItem routed = routedItems.get(item);
            // Include the effective loader/chunker route in the incremental identity. The same file
            // must be reprocessed when its CODE pipeline or chunking contract changes.
            String itemFreshHash = incrementalEnabled ? resolveContentHash(item) : null;
            if (itemFreshHash != null) {
                itemFreshHash = processingAwareHash(itemFreshHash, source, routed, job);
            }

            try {
                // ── INCREMENTAL: decide skip or purge (pre-load) ────────────────
                if (incrementalEnabled && itemFreshHash != null) {
                    if (documentHashStore.isUnchanged(factSheetId, persistentItemId, itemFreshHash)) {
                        // UNCHANGED — skip the expensive load + downstream steps.
                        int skipped = job.getFilesSkippedUnchanged().incrementAndGet();
                        log.info("[Job {}] SKIP (unchanged hash) file: {} (total skipped: {})",
                                job.getJobId(), shortName, skipped);
                        documentTracker.recordEvent(job, "LOADING", "INFO",
                                "Skipped unchanged file (content hash match)", shortName);
                        // We do NOT add to collectedDocs — file is entirely skipped.
                        continue;
                    } else {
                        // CHANGED or NEW — purge prior nodes if an entry exists (CHANGED).
                        DocumentHashStore.HashEntry existing = documentHashStore.lookup(
                                factSheetId, persistentItemId);
                        if (existing != null) {
                            purgeNodesForSource(job, factSheetId, persistentItemId);
                        }
                    }
                }
                // ───────────────────────────────────────────────────────────────

                DocumentSourceDescriptor desc = item.getSourceDescriptor();
                if (desc == null) {
                    desc = DocumentSourceDescriptor.builder()
                            .type(source.getSourceType())
                            .pathOrUrl(itemUrl)
                            .build();
                }
                int docsBeforeLoad = collectedDocs.size();
                IngestPipelineDefinition pipeline = routed != null ? routed.pipeline() : null;
                String requestedLoader = firstNonBlank(source.getLoaderName(),
                        pipeline != null ? pipeline.getLoaderName() : null);
                DocumentLoader selectedLoader = resolveLoader(desc, requestedLoader);
                boolean loaderFound = selectedLoader != null;
                if (selectedLoader != null) {
                            DocumentLoader loader = selectedLoader;
                            log.info("[Job {}] Using loader '{}' for file: {} (type={}, pipeline={})",
                                    job.getJobId(), loader.getName(), shortName, desc.getType(),
                                    pipeline != null ? pipeline.getPipelineId() : "automatic");
                            List<Document> docs = loader.load(desc);
                            for (Document doc : docs) {
                                doc.getMetadata().put("source_url", safeItemUrl);
                                // Per-file source_path. Without this, loaders that don't set it (HTML,
                                // Tika/markdown) inherit the crawl-source DIRECTORY via the scopeMeta
                                // putIfAbsent in loadSource(), so every non-xlsx file collapses to ONE
                                // DOCUMENT node (dedup by source_path) → orphaned entities + fragmented graph.
                                doc.getMetadata().put(GraphConstants.META_SOURCE_PATH, persistentItemId);
                                doc.getMetadata().put(GraphConstants.META_SOURCE_TYPE, sourceTypeName);
                                if (item.getContentType() != null
                                        && !doc.getMetadata().containsKey(GraphConstants.META_CONTENT_TYPE)) {
                                    doc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, item.getContentType());
                                }
                                // Stamp the item URL so downstream provenance recording uses the
                                // same canonical key this hash store uses.
                                doc.getMetadata().put("_incrementalSourceUrl", persistentItemId);
                                applyPipelineMetadata(doc.getMetadata(), source, routed, job);
                            }
                            collectedDocs.addAll(docs);
                            int docsFromFile = collectedDocs.size() - docsBeforeLoad;
                            int newTotal = job.getDocumentsLoaded().addAndGet(docsFromFile);
                            progress.setDocumentsLoaded(newTotal);
                            for (int docIndex = docsBeforeLoad; docIndex < collectedDocs.size(); docIndex++) {
                                documentTracker.recordDocumentProgress(job, collectedDocs.get(docIndex),
                                        "LOADING", "LOADED", 0, 0, 0,
                                        "Loaded " + docsFromFile + " document(s) from file", null,
                                        List.of(loader.getName()), false);
                            }
                            documentTracker.recordEvent(job, "LOADING", "INFO",
                                    "Loaded " + docsFromFile + " document(s)", shortName);
                            log.info("[Job {}] Loaded file: {} - {} document(s) (total: {})",
                                    job.getJobId(), shortName, docsFromFile, newTotal);

                            // ── INCREMENTAL: stage hash; graph completion commits it ──
                            if (incrementalEnabled && docsFromFile > 0) {
                                if (itemFreshHash != null) {
                                    documentHashStore.stageHash(factSheetId, persistentItemId,
                                            itemFreshHash, job.getJobId(), sourceScopeId);
                                }
                                job.getFilesReprocessed().incrementAndGet();
                            }
                            // ─────────────────────────────────────────────────────
                }
                if (!loaderFound) {
                    String details = "type=" + desc.getType() + ", path=" + safeItemUrl;
                    log.warn("[Job {}] Skipping unsupported discovered file '{}' ({})",
                            job.getJobId(), shortName, details);
                    documentTracker.recordEvent(job, "LOADING", "WARN",
                            "Skipping unsupported discovered file", shortName + " - " + details);
                }
            } catch (Throwable e) {
                String errorMsg = "Failed to load '" + shortName + "': "
                        + e.getClass().getSimpleName() + ": "
                        + SourceCredentialRedactor.redact(e.getMessage());
                log.error("[Job {}] {}", job.getJobId(), errorMsg);
                job.getErrors().add(errorMsg);
                job.getErrorCount().incrementAndGet();
            }
        }

        // Emit incremental telemetry at the end of the source loading pass.
        if (incrementalEnabled) {
            int skipped = job.getFilesSkippedUnchanged().get();
            int reprocessed = job.getFilesReprocessed().get();
            if (skipped > 0 || reprocessed > 0) {
                log.info("[Job {}] Incremental crawl summary: {} file(s) skipped (unchanged), {} file(s) (re)processed",
                        job.getJobId(), skipped, reprocessed);
                documentTracker.recordEvent(job, "LOADING", "INFO",
                        "Incremental: " + skipped + " skipped (unchanged), " + reprocessed + " (re)processed",
                        source.getLabel());
            }
        }

        return collectedDocs;
    }

    /**
     * Register crawled sources as facts in the target fact sheet.
     * Each source with loaded documents becomes a Fact record so that
     * the fact sheet tracks what was crawled.
     */
    void registerCrawledSourcesAsFacts(UnifiedCrawlJob job, List<SourceLoadResult> sourceResults) {
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
                String pathOrUrl = matchedSource != null
                        ? SourceCredentialRedactor.redact(matchedSource.getPathOrUrl()) : null;

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
                    documentTracker.recordEvent(job, "LOADING", "INFO",
                            "Registered " + created + " crawled source(s) as facts",
                            "factSheetId=" + factSheetId);
                }
            }
        } catch (Exception e) {
            log.warn("[Job {}] Failed to register crawled sources as facts: {}",
                    job.getJobId(), e.getMessage());
            documentTracker.recordEvent(job, "LOADING", "WARN",
                    "Failed to register crawled sources as facts",
                    e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    Map<String, Object> sourceMetadata(UnifiedCrawlSource source, UnifiedCrawlJob job) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source.getProperties() != null) {
            metadata.putAll(sanitizedMetadata(source.getProperties()));
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
            String safeLocation = SourceCredentialRedactor.redact(source.getPathOrUrl());
            metadata.putIfAbsent(GraphConstants.META_SOURCE_PATH, safeLocation);
            metadata.putIfAbsent(GraphConstants.META_SOURCE, safeLocation);
        }
        if (source.getChunkerName() != null && !source.getChunkerName().isBlank()) {
            metadata.put(GraphConstants.META_CHUNKER_NAME, source.getChunkerName().strip());
        }
        if (source.getChunkSize() != null && source.getChunkSize() > 0) {
            metadata.put(GraphConstants.META_CHUNK_SIZE_OVERRIDE, source.getChunkSize());
        }
        if (source.getChunkOverlap() != null && source.getChunkOverlap() >= 0) {
            metadata.put(GraphConstants.META_CHUNK_OVERLAP_OVERRIDE, source.getChunkOverlap());
        }
        if (source.getChunkerOptions() != null && !source.getChunkerOptions().isEmpty()) {
            metadata.put(GraphConstants.META_CHUNKER_OPTIONS,
                    sanitizedMetadata(source.getChunkerOptions()));
        }
        return metadata;
    }

    List<IngestPipelineDefinition> effectivePipelines(UnifiedCrawlJob job) {
        if (job == null || job.getRequest() == null || job.getRequest().getPipelines() == null) {
            return List.of();
        }
        List<IngestPipelineDefinition> pipelines = job.getRequest().getPipelines().stream()
                .map(CrawlSourceLoadingService::copyPipeline)
                .toList();
        Set<String> ids = new LinkedHashSet<>();
        for (IngestPipelineDefinition pipeline : pipelines) {
            if (pipeline == null || !hasText(pipeline.getPipelineId()) || !ids.add(pipeline.getPipelineId())) {
                throw new IllegalArgumentException("Crawl pipeline IDs must be nonblank and unique");
            }
            if (pipeline.getPipelineType() == null) {
                throw new IllegalArgumentException("Pipeline type is required for " + pipeline.getPipelineId());
            }
            if (pipeline.getPipelineType() == IngestPipelineDefinition.PipelineType.CODE) {
                if (!hasText(pipeline.getLoaderName())) pipeline.setLoaderName("source-code");
                if (!hasText(pipeline.getChunkerName())) pipeline.setChunkerName("code-aware");
                if (pipeline.getChunkSize() == null) pipeline.setChunkSize(1800);
                if (pipeline.getChunkOverlap() == null) pipeline.setChunkOverlap(0);
            }
        }
        String defaultId = job.getRequest().getDefaultPipelineId();
        if (hasText(defaultId) && !ids.contains(defaultId)) {
            throw new IllegalArgumentException("Unknown defaultPipelineId: " + defaultId);
        }
        if (job.getRequest().getRouteRules() != null) {
            job.getRequest().getRouteRules().forEach(rule -> {
                if (rule != null && !ids.contains(rule.getPipelineId())) {
                    throw new IllegalArgumentException("Route rule references unknown pipeline: "
                            + rule.getPipelineId());
                }
            });
        }
        return List.copyOf(pipelines);
    }

    private static IngestPipelineDefinition copyPipeline(IngestPipelineDefinition source) {
        if (source == null) return null;
        return IngestPipelineDefinition.builder()
                .pipelineId(source.getPipelineId())
                .displayName(source.getDisplayName())
                .pipelineType(source.getPipelineType())
                .loaderName(source.getLoaderName())
                .chunkerName(source.getChunkerName())
                .embeddingModelName(source.getEmbeddingModelName())
                .language(source.getLanguage())
                .chunkSize(source.getChunkSize())
                .chunkOverlap(source.getChunkOverlap())
                .keywordOnly(source.isKeywordOnly())
                .enableVlm(source.isEnableVlm())
                .enableGraphExtraction(source.isEnableGraphExtraction())
                .processingMode(source.getProcessingMode())
                .collectionName(source.getCollectionName())
                .extractionEntityTypes(source.getExtractionEntityTypes() == null ? null
                        : List.copyOf(source.getExtractionEntityTypes()))
                .extractionRelationshipTypes(source.getExtractionRelationshipTypes() == null ? null
                        : List.copyOf(source.getExtractionRelationshipTypes()))
                .extractionPromptTemplate(source.getExtractionPromptTemplate())
                .promptAugmentations(source.getPromptAugmentations() == null ? List.of()
                        : List.copyOf(source.getPromptAugmentations()))
                .extractionLlmProvider(source.getExtractionLlmProvider())
                .extractionModelName(source.getExtractionModelName())
                .extractionTemperature(source.getExtractionTemperature())
                .extractionMaxTokens(source.getExtractionMaxTokens())
                .maxChunkChars(source.getMaxChunkChars())
                .options(source.getOptions() == null ? Map.of() : new LinkedHashMap<>(source.getOptions()))
                .build();
    }

    RoutedCrawlItem routeDirectSource(UnifiedCrawlSource source, UnifiedCrawlJob job,
                                      DocumentSourceDescriptor descriptor) {
        List<IngestPipelineDefinition> pipelines = effectivePipelines(job);
        validateSourcePipeline(source, pipelines);
        if (pipelines.isEmpty()) return null;
        CrawlConfig config = CrawlConfig.builder()
                .seed(source.getPathOrUrl())
                .sourceType(source.getSourceType())
                .pipelines(pipelines)
                .routeRules(hasText(source.getPipelineId()) ? List.of()
                        : job.getRequest().getRouteRules())
                .defaultPipelineId(hasText(source.getPipelineId())
                        ? source.getPipelineId() : job.getRequest().getDefaultPipelineId())
                .build();
        String contentType = source.getAllowedContentTypes() != null
                && source.getAllowedContentTypes().size() == 1
                ? source.getAllowedContentTypes().get(0) : null;
        Long contentLength = null;
        try {
            Path path = Path.of(source.getPathOrUrl());
            if (Files.isRegularFile(path)) {
                contentLength = Files.size(path);
                if (!hasText(contentType)) contentType = Files.probeContentType(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Non-file direct sources retain their caller-provided routing metadata.
        }
        CrawlItem item = CrawlItem.builder()
                .url(source.getPathOrUrl())
                .sourceDescriptor(descriptor)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        return new CrawlPipelineRouter(config).routeWithDetails(item);
    }

    private void validateSourcePipeline(UnifiedCrawlSource source,
                                        List<IngestPipelineDefinition> pipelines) {
        if (!hasText(source.getPipelineId())) return;
        boolean found = pipelines.stream().anyMatch(pipeline ->
                source.getPipelineId().equals(pipeline.getPipelineId()));
        if (!found) throw new IllegalArgumentException("Source references unknown pipeline: "
                + source.getPipelineId());
    }

    DocumentLoader resolveLoader(DocumentSourceDescriptor descriptor, String requestedName) {
        if (documentLoaders == null || documentLoaders.isEmpty()) return null;
        if (hasText(requestedName)) {
            for (DocumentLoader loader : documentLoaders) {
                if (loaderNameMatches(loader, requestedName)) {
                    if (!loader.supports(descriptor)) {
                        throw new IllegalArgumentException("Loader '" + requestedName
                                + "' does not support " + descriptor.getPathOrUrl());
                    }
                    return loader;
                }
            }
            throw new IllegalArgumentException("Requested loader is unavailable: " + requestedName);
        }
        return documentLoaders.stream().filter(loader -> loader.supports(descriptor)).findFirst().orElse(null);
    }

    void applyPipelineMetadata(Map<String, Object> metadata, UnifiedCrawlSource source,
                               RoutedCrawlItem routed, UnifiedCrawlJob job) {
        if (metadata == null) return;
        IngestPipelineDefinition pipeline = routed != null ? routed.pipeline() : null;
        if (pipeline != null) {
            metadata.put(GraphConstants.META_PIPELINE_ID, pipeline.getPipelineId());
            metadata.put(GraphConstants.META_PIPELINE_TYPE, pipeline.getPipelineType().name());
            metadata.put(GraphConstants.META_PIPELINE_ROUTE,
                    hasText(source.getPipelineId()) ? "source_override"
                            : routed.matchedRule() != null ? "rule" : "default");
        } else {
            metadata.put(GraphConstants.META_PIPELINE_ROUTE, "automatic");
        }
        metadata.put(GraphConstants.META_PIPELINE_FINGERPRINT,
                processingFingerprint(source, routed, job));

        String chunker = firstNonBlank(source.getChunkerName(),
                pipeline != null ? pipeline.getChunkerName() : null);
        Integer size = source.getChunkSize() != null ? source.getChunkSize()
                : pipeline != null ? pipeline.getChunkSize() : null;
        Integer overlap = source.getChunkOverlap() != null ? source.getChunkOverlap()
                : pipeline != null ? pipeline.getChunkOverlap() : null;
        if (hasText(chunker)) metadata.put(GraphConstants.META_CHUNKER_NAME, chunker);
        if (size != null && size > 0) metadata.put(GraphConstants.META_CHUNK_SIZE_OVERRIDE, size);
        if (overlap != null && overlap >= 0) metadata.put(GraphConstants.META_CHUNK_OVERLAP_OVERRIDE, overlap);

        Map<String, Object> options = new LinkedHashMap<>();
        if (pipeline != null && pipeline.getOptions() != null) {
            copyChunkerOptions(pipeline.getOptions(), options);
        }
        if (source.getChunkerOptions() != null) {
            options.putAll(sanitizedMetadata(source.getChunkerOptions()));
        }
        if (!options.isEmpty()) metadata.put(GraphConstants.META_CHUNKER_OPTIONS, options);
    }

    private static void copyChunkerOptions(Map<String, Object> source, Map<String, Object> target) {
        Set<String> allowed = Set.of("separators", "preserveParagraphs", "language", "codeLanguage",
                "collectGarbage", "includeGarbageChunk");
        source.forEach((key, value) -> {
            if (allowed.contains(key) && value != null) {
                target.put(key, value instanceof String text
                        ? SourceCredentialRedactor.redact(text) : value);
            }
        });
    }

    String processingAwareHash(String contentHash, UnifiedCrawlSource source,
                               RoutedCrawlItem routed, UnifiedCrawlJob job) {
        return DocumentHashStore.sha256Hex((contentHash + "\n" + processingFingerprint(source, routed, job))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    String processingFingerprint(UnifiedCrawlSource source, RoutedCrawlItem routed,
                                 UnifiedCrawlJob job) {
        IngestPipelineDefinition pipeline = routed != null ? routed.pipeline() : null;
        UnifiedCrawlRequest request = job != null ? job.getRequest() : null;
        Map<String, Object> sourceContract = new TreeMap<>();
        sourceContract.put("pipelineId", source.getPipelineId());
        sourceContract.put("loader", source.getLoaderName());
        sourceContract.put("chunker", source.getChunkerName());
        sourceContract.put("chunkSize", source.getChunkSize());
        sourceContract.put("chunkOverlap", source.getChunkOverlap());
        sourceContract.put("chunkerOptions", source.getChunkerOptions());
        sourceContract.put("properties", source.getProperties());

        Map<String, Object> stable = new TreeMap<>();
        stable.put("source", sourceContract);
        stable.put("pipeline", pipeline);
        stable.put("projectChunker", projectChunkerName);
        stable.put("projectChunkSize", projectChunkSize);
        stable.put("projectChunkOverlap", projectChunkOverlap);
        stable.put("availableLoaders", documentLoaders == null ? List.of()
                : documentLoaders.stream().map(loader -> loader.getClass().getName() + ":" + loader.getName())
                .toList());
        if (request != null) {
            stable.put("preprocessing", request.getPreprocessing());
            stable.put("chunking", request.getChunking());
            stable.put("legacyVectorIndex", request.getVectorIndex());
            stable.put("graphExtraction", request.getGraphExtraction());
            stable.put("processingRoute", request.getProcessingRoute());
            stable.put("deriveOntology", request.getDeriveOntology());
            stable.put("maxValidationRetries", request.getMaxValidationRetries());
            stable.put("hydration", request.getHydration());
            stable.put("enabledSteps", request.getEnabledSteps());
            stable.put("archivedSteps", request.getArchivedSteps());
            stable.put("strictSteps", request.getStrictSteps());
        }
        try {
            return DocumentHashStore.sha256Hex(FINGERPRINT_MAPPER.writeValueAsBytes(
                    sanitizeFingerprintNode(FINGERPRINT_MAPPER.valueToTree(stable))));
        } catch (IOException e) {
            throw new IllegalStateException("Could not fingerprint the crawl processing contract", e);
        }
    }

    private static JsonNode sanitizeFingerprintNode(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node.deepCopy();
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                object.set(field, isSensitiveMetadataKey(field)
                        ? FINGERPRINT_MAPPER.getNodeFactory().textNode("<secret>")
                        : sanitizeFingerprintNode(object.get(field)));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = FINGERPRINT_MAPPER.createArrayNode();
            node.forEach(value -> array.add(sanitizeFingerprintNode(value)));
            return array;
        }
        return node.isTextual()
                ? FINGERPRINT_MAPPER.getNodeFactory().textNode(
                        SourceCredentialRedactor.redact(node.asText()))
                : node;
    }

    private static boolean loaderNameMatches(DocumentLoader loader, String requestedName) {
        String requested = normalizeLoaderName(requestedName);
        return requested.equals(normalizeLoaderName(loader.getName()))
                || requested.equals(normalizeLoaderName(loader.getClass().getSimpleName()))
                || requested.equals(normalizeLoaderName(loader.getClass().getName()))
                || ("code".equals(requested) && "sourcecode".equals(normalizeLoaderName(loader.getName())));
    }

    private static String normalizeLoaderName(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.startsWith("apache")) normalized = normalized.substring("apache".length());
        if (normalized.endsWith("documentloader")) {
            normalized = normalized.substring(0, normalized.length() - "documentloader".length());
        } else if (normalized.endsWith("loader")) {
            normalized = normalized.substring(0, normalized.length() - "loader".length());
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (hasText(value)) return value.strip();
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    Map<String, Object> runtimeSourceMetadata(UnifiedCrawlSource source, UnifiedCrawlJob job) {
        Map<String, Object> runtime = new LinkedHashMap<>(sourceMetadata(source, job));
        if (source.getProperties() != null) runtime.putAll(source.getProperties());
        return runtime;
    }

    private static void removeSensitiveMetadata(Map<String, Object> metadata) {
        Iterator<Map.Entry<String, Object>> entries = metadata.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Object> entry = entries.next();
            if (isSensitiveMetadataKey(entry.getKey())) {
                entries.remove();
            } else {
                entry.setValue(sanitizeValue(entry.getValue()));
            }
        }
    }

    private static Map<String, Object> sanitizedMetadata(Map<?, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((rawKey, value) -> {
            String key = String.valueOf(rawKey);
            if (isSensitiveMetadataKey(key)) return;
            sanitized.put(key, sanitizeValue(value));
        });
        return sanitized;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> nested) return sanitizedMetadata(nested);
        if (value instanceof Collection<?> values) {
            return values.stream().map(CrawlSourceLoadingService::sanitizeValue).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitizeValue(java.lang.reflect.Array.get(value, i)));
            }
            return sanitized;
        }
        return value instanceof String text ? SourceCredentialRedactor.redact(text) : value;
    }

    private static boolean isSensitiveMetadataKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.endsWith("password")
                || normalized.endsWith("token")
                || normalized.endsWith("secret")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("secretkey")
                || normalized.endsWith("apikey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("authorization")
                || normalized.endsWith("authheader")
                || normalized.endsWith("credentials")
                || normalized.endsWith("credential");
    }

    static String persistentItemIdentity(String rawLocation) {
        if (rawLocation == null) return null;
        String redacted = SourceCredentialRedactor.redact(rawLocation);
        if (rawLocation.equals(redacted)) return rawLocation;
        return "sensitive-source:" + DocumentHashStore.sha256Hex(
                redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }

    boolean isCrawlPreferredSourceType(DocumentSourceDescriptor.SourceType type) {
        return type == DocumentSourceDescriptor.SourceType.WEB_CRAWL
                || type == DocumentSourceDescriptor.SourceType.DIRECTORY
                || type == DocumentSourceDescriptor.SourceType.FILE;
    }

    boolean isSourceTypeCrawlable(DocumentSourceDescriptor.SourceType type) {
        return hasCrawlerFor(type);
    }

    boolean hasCrawlerFor(DocumentSourceDescriptor.SourceType type) {
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

    boolean hasLoaderFor(DocumentSourceDescriptor.SourceType type) {
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

    boolean hasRuntimeFor(DocumentSourceDescriptor.SourceType type) {
        boolean registered = hasLoaderFor(type) || hasCrawlerFor(type);
        if (!registered) return false;
        return switch (type) {
            case SFTP -> executableOnPath("sftp");
            case SMB -> executableOnPath("smbclient");
            default -> true;
        };
    }

    private static boolean executableOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        List<String> names = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win") ? List.of(command + ".exe", command + ".cmd", command)
                : List.of(command);
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String name : names) {
                try {
                    if (Files.isRegularFile(Path.of(directory, name))
                            && Files.isExecutable(Path.of(directory, name))) return true;
                } catch (RuntimeException invalidPath) {
                    log.debug("Skipping invalid PATH entry while probing {}: {}",
                            command, invalidPath.getMessage());
                }
            }
        }
        return false;
    }

    String summarizeSourceLoadErrors(UnifiedCrawlJob job) {
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

    private boolean isCancelled(UnifiedCrawlJob job) {
        return job != null && job.isCancellationRequested();
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

    // ── Incremental crawl helpers ────────────────────────────────────────────

    /**
     * Resolve a SHA-256 content hash for the given crawl item.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Use the crawler-populated {@link CrawlItem#getContentHash()} when available
     *       (e.g. HTTP ETag derived or already hashed on download). This avoids a second
     *       read of the content.</li>
     *   <li>For {@code file://} or plain filesystem paths, read bytes and compute SHA-256.</li>
     *   <li>For remote URLs without a pre-computed hash, return {@code null} — the hash
     *       check is silently skipped and the item is processed normally.</li>
     * </ol>
     *
     * @param item the discovered crawl item
     * @return SHA-256 hex string, or {@code null} if the hash cannot be determined
     */
    private static String resolveContentHash(CrawlItem item) {
        // 1. Trust a crawler-provided hash (e.g. from ETag or crawler-side digest).
        if (item.getContentHash() != null && !item.getContentHash().isBlank()) {
            return item.getContentHash();
        }
        // 2. File-backed URL: read bytes and hash.
        String url = item.getUrl();
        if (url == null) {
            return null;
        }
        try {
            Path filePath = toFilePath(url);
            if (filePath != null && Files.isRegularFile(filePath)) {
                byte[] bytes = Files.readAllBytes(filePath);
                return DocumentHashStore.sha256Hex(bytes);
            }
        } catch (IOException e) {
            // Non-fatal: log at debug and fall through.
            log.debug("Could not read file bytes for hash computation (url={}): {}", url, e.getMessage());
        }
        // 3. Remote URL / unsupported scheme — skip hash check.
        return null;
    }

    /**
     * Convert a URL string to a local {@link Path} if it represents a file-system resource.
     * Returns {@code null} for HTTP(S) and other non-file schemes.
     */
    private static Path toFilePath(String url) {
        if (url == null) {
            return null;
        }
        try {
            if (url.startsWith("file:")) {
                return Path.of(URI.create(url));
            }
            // Plain path (no scheme) — treat as a filesystem path.
            if (!url.contains("://")) {
                return Path.of(url);
            }
        } catch (Exception ignored) {
            // Malformed URI or path — not a local file.
        }
        return null;
    }

    /** Stable grouping identity for deletion reconciliation across repeated crawls. */
    static String sourceScopeId(UnifiedCrawlSource source) {
        if (source == null) {
            return null;
        }
        String type = source.getSourceType() != null ? source.getSourceType().name() : "AUTO";
        String seed = source.getPathOrUrl();
        if (seed == null || seed.isBlank()) {
            return type + ":";
        }
        return type + ":" + SourceCredentialRedactor.redact(seed.trim()).replace('\\', '/');
    }

    /**
     * Locator-free identity: the item identifiers live in source properties instead of
     * pathOrUrl. Mirrors LocalExternalSourceLoaderRegistry.identityWithoutLocator.
     */
    private static boolean hasIdentityMetadata(Map<String, Object> properties) {
        if (properties == null) return false;
        for (String key : List.of("fileIds", "itemIds", "pageIds", "databaseIds",
                "guildId", "loadAllChannels")) {
            Object value = properties.get(key);
            if (value == null) continue;
            if (value instanceof Collection<?> values) {
                if (!values.isEmpty()) return true;
            } else if (!value.toString().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalSourceType(DocumentSourceDescriptor.SourceType sourceType) {
        return sourceType == DocumentSourceDescriptor.SourceType.FILE
                || sourceType == DocumentSourceDescriptor.SourceType.DIRECTORY;
    }

    /**
     * Purge graph nodes whose provenance {@code _sourceDocumentId} matches the given
     * source URL.  Called before re-processing a CHANGED file so that the re-extraction
     * replaces rather than duplicates the prior nodes.
     *
     * <p>Mirrors the logic in {@code KnowledgeGraphController.deleteByProvenance} but
     * executes in-process to avoid an HTTP round-trip overhead per changed file.
     *
     * @param job         current crawl job (for logging)
     * @param factSheetId fact-sheet scope; when non-null only nodes in that sheet are scanned
     * @param sourceUrl   the canonical source path/URL (the {@code _sourceDocumentId} value)
     */
    private boolean purgeNodesForSource(UnifiedCrawlJob job, Long factSheetId, String sourceUrl) {
        if (knowledgeGraphService == null || sourceUrl == null) {
            return false;
        }
        try {
            List<GraphNode> candidates;
            if (factSheetId != null) {
                candidates = knowledgeGraphService.getNodesInFactSheet(factSheetId);
            } else {
                candidates = new ArrayList<>();
                for (NodeLevel level : NodeLevel.values()) {
                    candidates.addAll(knowledgeGraphService.getNodesByType(level));
                }
            }
            int purged = 0;
            for (GraphNode node : candidates) {
                Map<String, Object> meta = node.getMetadata();
                if (meta != null && sourceUrl.equals(meta.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID))) {
                    try {
                        knowledgeGraphService.deleteNode(node.getNodeId());
                        purged++;
                    } catch (Exception ex) {
                        log.debug("[Job {}] Failed to purge node {} for source {}: {}",
                                job.getJobId(), node.getNodeId(), sourceUrl, ex.getMessage());
                    }
                }
            }
            if (purged > 0) {
                log.info("[Job {}] Purged {} stale node(s) for changed source: {}",
                        job.getJobId(), purged, CrawlDocumentTracker.shortName(sourceUrl));
                documentTracker.recordEvent(job, "LOADING", "INFO",
                        "Purged " + purged + " stale node(s) for changed source",
                        CrawlDocumentTracker.shortName(sourceUrl));
            }
            return true;
        } catch (Exception e) {
            log.warn("[Job {}] Could not purge nodes for changed source '{}': {}",
                    job.getJobId(), sourceUrl, e.getMessage());
            return false;
        }
    }

    // ── Result record ────────────────────────────────────────────────────────

    record SourceLoadResult(int index, String label, List<Document> documents) {}
}
