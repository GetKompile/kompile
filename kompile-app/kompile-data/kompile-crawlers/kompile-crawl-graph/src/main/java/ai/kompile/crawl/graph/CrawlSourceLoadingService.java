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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    // ── Required dependencies ───────────────────────────────────────────────

    private final CrawlDocumentTracker documentTracker;
    private final PipelineStepTracker pipelineStepTracker;

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
                        "Discovered " + discovered + " item(s)", item.getUrl());
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

        // Now load documents from discovered items (off the crawler thread)
        List<Document> collectedDocs = new ArrayList<>();
        for (CrawlItem item : discoveredItems) {
            if (isCancelled(job)) return collectedDocs;
            String shortName = CrawlDocumentTracker.shortName(item.getUrl());

            job.getCurrentFile().set(shortName);
            progress.setCurrentPhase("LOADING");
            progress.setCurrentItem(shortName);
            int loaded = job.getDocumentsLoaded().get();
            log.info("[Job {}] Loading file: {} ({}/{})",
                    job.getJobId(), shortName, loaded + 1, discoveredItems.size());
            documentTracker.recordEvent(job, "LOADING", "INFO",
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
                                documentTracker.recordDocumentProgress(job, collectedDocs.get(docIndex),
                                        "LOADING", "LOADED", 0, 0, 0,
                                        "Loaded " + docsFromFile + " document(s) from file", null,
                                        List.of(loader.getName()), false);
                            }
                            documentTracker.recordEvent(job, "LOADING", "INFO",
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
                || type == DocumentSourceDescriptor.SourceType.DIRECTORY;
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
        return job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED;
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

    // ── Result record ────────────────────────────────────────────────────────

    record SourceLoadResult(int index, String label, List<Document> documents) {}
}
