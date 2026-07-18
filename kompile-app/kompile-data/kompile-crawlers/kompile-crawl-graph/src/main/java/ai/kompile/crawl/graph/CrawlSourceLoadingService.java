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
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.crawler.CrawlerService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
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
        progress.setCurrentItem(source.getPathOrUrl());
        documentTracker.recordEvent(job, "LOADING", "INFO",
                "Loading source " + (index + 1) + "/" + job.getRequest().getSources().size(), label);
        documentTracker.recordEvent(job, "LOADING", "INFO",
                "Source " + (index + 1) + ": " + label + " (" + (source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN") + ")",
                source.getPathOrUrl());

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
                    "type=" + (source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN") + ", path=" + source.getPathOrUrl());
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
            documentTracker.recordEvent(job, "LOADING", "ERROR",
                    "Source failed: " + label,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
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
                            documentTracker.recordEvent(job, "LOADING", "INFO",
                                    loaderProgress.message(), source.getLabel());
                            log.info("[Job {}] Loader progress ({}%): {}", job.getJobId(),
                                    loaderProgress.progressPercent(), loaderProgress.message());
                        }
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

    /**
     * Crawls a source using the {@link CrawlerService}, collecting discovered items
     * and then loading documents from them.
     */
    List<Document> crawlSource(UnifiedCrawlSource source,
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
                documentTracker.recordEvent(job, "DISCOVERING", "INFO",
                        "Discovered " + discovered + " item(s) under " + source.getLabel(), item.getUrl());
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                // Just collect the item — actual document loading happens after crawl completes
                discoveredItems.add(item);
                String shortName = CrawlDocumentTracker.shortName(item.getUrl());
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
                String shortName = CrawlDocumentTracker.shortName(item.getUrl());
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

        // Tracks which items were actually loaded (not skipped) so we can record their
        // hashes AFTER successful downstream processing.  We attach a metadata marker
        // to each loaded Document so the hash can be persisted when the file completes.
        // The actual recordHash call happens immediately after a successful load below
        // (optimistic: we assume the downstream pipeline will succeed; if extraction
        // fails the hash is not recorded and the file will be re-processed next time).
        List<Document> collectedDocs = new ArrayList<>();
        for (CrawlItem item : discoveredItems) {
            if (isCancelled(job)) return collectedDocs;
            String itemUrl = item.getUrl();
            String shortName = CrawlDocumentTracker.shortName(itemUrl);

            job.getCurrentFile().set(shortName);
            progress.setCurrentPhase("LOADING");
            progress.setCurrentItem(shortName);
            int loaded = job.getDocumentsLoaded().get();
            log.info("[Job {}] Loading file: {} ({}/{})",
                    job.getJobId(), shortName, loaded + 1, discoveredItems.size());
            documentTracker.recordEvent(job, "LOADING", "INFO",
                    "Loading discovered file " + (collectedDocs.size() + 1) + "/" + discoveredItems.size(),
                    shortName);

            // Compute the content hash once per item; reused for skip-check AND persist.
            // null means "hash not available" → always process (no skip possible).
            String itemFreshHash = incrementalEnabled ? resolveContentHash(item) : null;

            try {
                // ── INCREMENTAL: decide skip or purge (pre-load) ────────────────
                if (incrementalEnabled && itemFreshHash != null) {
                    if (documentHashStore.isUnchanged(factSheetId, itemUrl, itemFreshHash)) {
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
                        DocumentHashStore.HashEntry existing = documentHashStore.lookup(factSheetId, itemUrl);
                        if (existing != null) {
                            purgeNodesForSource(job, factSheetId, itemUrl);
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
                boolean loaderFound = false;
                if (documentLoaders != null) {
                    for (DocumentLoader loader : documentLoaders) {
                        if (loader.supports(desc)) {
                            loaderFound = true;
                            log.info("[Job {}] Using loader '{}' for file: {} (type={})",
                                    job.getJobId(), loader.getName(), shortName, desc.getType());
                            List<Document> docs = loader.load(desc);
                            for (Document doc : docs) {
                                doc.getMetadata().put("source_url", itemUrl);
                                // Per-file source_path. Without this, loaders that don't set it (HTML,
                                // Tika/markdown) inherit the crawl-source DIRECTORY via the scopeMeta
                                // putIfAbsent in loadSource(), so every non-xlsx file collapses to ONE
                                // DOCUMENT node (dedup by source_path) → orphaned entities + fragmented graph.
                                doc.getMetadata().put(GraphConstants.META_SOURCE_PATH, itemUrl);
                                doc.getMetadata().put(GraphConstants.META_SOURCE_TYPE, sourceTypeName);
                                if (item.getContentType() != null
                                        && !doc.getMetadata().containsKey(GraphConstants.META_CONTENT_TYPE)) {
                                    doc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, item.getContentType());
                                }
                                // Stamp the item URL so downstream provenance recording uses the
                                // same canonical key this hash store uses.
                                doc.getMetadata().put("_incrementalSourceUrl", itemUrl);
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

                            // ── INCREMENTAL: record hash after successful load ─────
                            if (incrementalEnabled && docsFromFile > 0) {
                                if (itemFreshHash != null) {
                                    documentHashStore.recordHash(factSheetId, itemUrl,
                                            itemFreshHash, job.getJobId());
                                }
                                job.getFilesReprocessed().incrementAndGet();
                            }
                            // ─────────────────────────────────────────────────────

                            break;
                        }
                    }
                }
                if (!loaderFound) {
                    String details = "type=" + desc.getType() + ", path=" + itemUrl;
                    log.warn("[Job {}] Skipping unsupported discovered file '{}' ({})",
                            job.getJobId(), shortName, details);
                    documentTracker.recordEvent(job, "LOADING", "WARN",
                            "Skipping unsupported discovered file", shortName + " - " + details);
                }
            } catch (Throwable e) {
                String errorMsg = "Failed to load '" + shortName + "': " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("[Job {}] {}", job.getJobId(), errorMsg, e);
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
    private void purgeNodesForSource(UnifiedCrawlJob job, Long factSheetId, String sourceUrl) {
        if (knowledgeGraphService == null || sourceUrl == null) {
            return;
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
        } catch (Exception e) {
            log.warn("[Job {}] Could not purge nodes for changed source '{}': {}",
                    job.getJobId(), sourceUrl, e.getMessage());
        }
    }

    // ── Result record ────────────────────────────────────────────────────────

    record SourceLoadResult(int index, String label, List<Document> documents) {}
}
