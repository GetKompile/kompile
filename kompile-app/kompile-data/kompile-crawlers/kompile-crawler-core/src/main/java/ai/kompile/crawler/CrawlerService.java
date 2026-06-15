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

package ai.kompile.crawler;

import ai.kompile.core.crawler.*;
import ai.kompile.core.crawler.pipeline.PipelineAwareCrawlListener;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.crawler.pipeline.RoutedCrawlItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service layer for managing crawl jobs. Provides:
 * <ul>
 *   <li>Starting crawl jobs with validation</li>
 *   <li>Job lifecycle management (pause, resume, cancel)</li>
 *   <li>Active job tracking and querying</li>
 *   <li>Crawl state persistence for incremental re-crawls</li>
 * </ul>
 *
 * <p>This service sits between the REST API and the Crawler implementations.
 * It does NOT directly integrate with the ingest pipeline — the controller
 * or a higher-level orchestrator connects crawl events to
 * {@code DocumentIngestService}.</p>
 */
@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private final CrawlerRegistry registry;
    private final ObjectMapper objectMapper;

    /** Active and recently completed jobs, keyed by jobId */
    private final Map<String, CrawlJob> jobs = new ConcurrentHashMap<>();

    /** Pipeline routers per job, for looking up routing decisions */
    private final Map<String, CrawlPipelineRouter> routers = new ConcurrentHashMap<>();

    /** Crawl state directory for persistence */
    private final Path stateDir;

    public CrawlerService(CrawlerRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.stateDir = Path.of(System.getProperty("user.home"), ".kompile", "crawl-state");
        try {
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            log.warn("Could not create crawl state directory: {}", e.getMessage());
        }
    }

    /**
     * Starts a new crawl job.
     *
     * @param config   The crawl configuration
     * @param listener Event listener for the crawl (may be null for no-op)
     * @return The started CrawlJob
     * @throws IllegalArgumentException if crawlerId is unknown or config is invalid
     */
    public CrawlJob startCrawl(CrawlConfig config, CrawlEventListener listener) {
        String crawlerId = config.getCrawlerId();
        if (crawlerId == null || crawlerId.isBlank()) {
            // Auto-detect crawler from source type
            crawlerId = autoDetectCrawler(config);
            config.setCrawlerId(crawlerId);
        }

        final String resolvedCrawlerId = crawlerId;
        Crawler crawler = registry.getCrawler(resolvedCrawlerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown crawler: " + resolvedCrawlerId));

        List<String> errors = crawler.validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid crawl config: " + String.join("; ", errors));
        }

        // Load previous state for incremental crawl if available (unless forceRecrawl)
        if (!config.isForceRecrawl() && config.getPreviousState() == null) {
            loadPreviousState(config).ifPresent(config::setPreviousState);
        }

        // Build the pipeline router from config
        CrawlPipelineRouter router = new CrawlPipelineRouter(config);

        // Wrap listener to add routing and state persistence
        CrawlEventListener safeDelegate = listener != null ? listener : new CrawlEventListener() {};
        CrawlEventListener wrappedListener = new CrawlEventListener() {

            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                safeDelegate.onDocumentDiscovered(item);

                // Route the item to the correct pipeline and notify
                RoutedCrawlItem routed = router.routeWithDetails(item);
                if (safeDelegate instanceof PipelineAwareCrawlListener pipelineListener) {
                    pipelineListener.onItemRouted(routed);
                }
            }
            @Override
            public void onDocumentProcessed(CrawlItem item) { safeDelegate.onDocumentProcessed(item); }
            @Override
            public void onDocumentFailed(CrawlItem item, Exception error) { safeDelegate.onDocumentFailed(item, error); }
            @Override
            public void onDocumentSkipped(String url, String reason) { safeDelegate.onDocumentSkipped(url, reason); }
            @Override
            public void onProgress(CrawlProgress progress) { safeDelegate.onProgress(progress); }

            @Override
            public void onComplete(CrawlSummary summary) {
                safeDelegate.onComplete(summary);
                if (summary.finalState() != null) {
                    persistState(config, summary.finalState());
                }
            }
        };

        CrawlJob job = crawler.start(config, wrappedListener);
        jobs.put(job.getJobId(), job);
        routers.put(job.getJobId(), router);

        log.info("Started crawl job {} with crawler '{}' from seed '{}', {} pipelines, {} route rules",
                job.getJobId(), resolvedCrawlerId, config.getSeed(),
                router.getAllPipelines().size(), config.getRouteRules().size());

        return job;
    }

    /** Get a job by ID */
    public Optional<CrawlJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** Get the pipeline router for a specific job */
    public Optional<CrawlPipelineRouter> getRouter(String jobId) {
        return Optional.ofNullable(routers.get(jobId));
    }

    /** Get all tracked jobs (active + recently completed) */
    public Collection<CrawlJob> getAllJobs() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    /** Get only active (running or paused) jobs */
    public List<CrawlJob> getActiveJobs() {
        List<CrawlJob> active = new ArrayList<>();
        for (CrawlJob job : jobs.values()) {
            CrawlStatus s = job.getStatus();
            if (s == CrawlStatus.RUNNING || s == CrawlStatus.PAUSED || s == CrawlStatus.PENDING) {
                active.add(job);
            }
        }
        return active;
    }

    /** Pause a running job */
    public boolean pauseJob(String jobId) {
        CrawlJob job = jobs.get(jobId);
        if (job != null && job.getStatus() == CrawlStatus.RUNNING) {
            job.pause();
            return true;
        }
        return false;
    }

    /** Resume a paused job */
    public boolean resumeJob(String jobId) {
        CrawlJob job = jobs.get(jobId);
        if (job != null && job.getStatus() == CrawlStatus.PAUSED) {
            job.resume();
            return true;
        }
        return false;
    }

    /** Cancel a job */
    public boolean cancelJob(String jobId) {
        CrawlJob job = jobs.get(jobId);
        if (job != null) {
            CrawlStatus s = job.getStatus();
            if (s == CrawlStatus.RUNNING || s == CrawlStatus.PAUSED) {
                job.cancel();
                return true;
            }
        }
        return false;
    }

    /** Get all registered crawler descriptors */
    public List<Map<String, Object>> listCrawlers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Crawler c : registry.getAll()) {
            Map<String, Object> desc = new LinkedHashMap<>();
            desc.put("id", c.getId());
            desc.put("name", c.getName());
            desc.put("description", c.getDescription());
            desc.put("supportedSourceTypes", c.getSupportedSourceTypes());
            result.add(desc);
        }
        return result;
    }

    /** Return true when at least one registered crawler supports the source type. */
    public boolean hasCrawlerForSourceType(DocumentSourceDescriptor.SourceType sourceType) {
        return sourceType != null && !registry.findBySourceType(sourceType).isEmpty();
    }

    /** Remove completed/failed/cancelled jobs older than the retention period */
    public int cleanupJobs() {
        int removed = 0;
        Iterator<Map.Entry<String, CrawlJob>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CrawlJob> entry = it.next();
            CrawlStatus s = entry.getValue().getStatus();
            if (s == CrawlStatus.COMPLETED || s == CrawlStatus.FAILED || s == CrawlStatus.CANCELLED) {
                routers.remove(entry.getKey());
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private String autoDetectCrawler(CrawlConfig config) {
        if (config.getSourceType() != null) {
            List<Crawler> matching = registry.findBySourceType(config.getSourceType());
            if (!matching.isEmpty()) {
                // For DIRECTORY sources, prefer the general-purpose "filesystem" crawler
                // over specialized ones (excel, html) that also claim DIRECTORY support
                if (config.getSourceType() == DocumentSourceDescriptor.SourceType.DIRECTORY) {
                    for (Crawler c : matching) {
                        if ("filesystem".equals(c.getId())) {
                            return c.getId();
                        }
                    }
                }
                return matching.get(0).getId();
            }
        }
        // Heuristic: if seed looks like a URL, use web crawler
        String seed = config.getSeed();
        if (seed != null && (seed.startsWith("http://") || seed.startsWith("https://"))) {
            return "web";
        }
        // Default to filesystem
        return "filesystem";
    }

    private void persistState(CrawlConfig config, CrawlState state) {
        try {
            String key = stateKey(config);
            Path file = stateDir.resolve(key + ".json");
            objectMapper.writeValue(file.toFile(), state);
            log.debug("Persisted crawl state to {}", file);
        } catch (Exception e) {
            log.warn("Failed to persist crawl state: {}", e.getMessage());
        }
    }

    private Optional<CrawlState> loadPreviousState(CrawlConfig config) {
        try {
            String key = stateKey(config);
            Path file = stateDir.resolve(key + ".json");
            if (Files.exists(file)) {
                CrawlState state = objectMapper.readValue(file.toFile(), CrawlState.class);
                log.debug("Loaded previous crawl state from {}", file);
                return Optional.of(state);
            }
        } catch (Exception e) {
            log.warn("Failed to load previous crawl state: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private String stateKey(CrawlConfig config) {
        // Use a hash of crawler ID + seed as the state file name
        String raw = config.getCrawlerId() + ":" + config.getSeed();
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
