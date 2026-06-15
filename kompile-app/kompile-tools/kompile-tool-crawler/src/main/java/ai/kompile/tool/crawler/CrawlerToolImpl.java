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

import ai.kompile.core.crawler.*;
import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.crawler.CrawlPipelineRouter;
import ai.kompile.crawler.CrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool implementation for crawler operations.
 * Allows agents to programmatically start, monitor, and control crawl jobs
 * with full multi-pipeline routing support.
 *
 * <p>This bean is only created when a {@link CrawlerService} is available.</p>
 */
@Component
@ConditionalOnBean(CrawlerService.class)
public class CrawlerToolImpl {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerToolImpl.class);

    private final CrawlerService crawlerService;

    @Autowired
    public CrawlerToolImpl(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
        logger.info("CrawlerToolImpl initialized");
    }

    // ---- Input records ----

    public record StartCrawlInput(
            String crawlerId,
            String seed,
            Integer maxDepth,
            Integer maxDocuments,
            Boolean sameDomainOnly,
            Boolean respectRobotsTxt,
            Long requestDelayMs,
            List<String> includePatterns,
            List<String> excludePatterns,
            String collectionName,
            String loaderName,
            String chunkerName,
            List<PipelineInput> pipelines,
            List<RouteRuleInput> routeRules,
            String defaultPipelineId
    ) {}

    public record PipelineInput(
            String pipelineId,
            String displayName,
            String pipelineType,
            String loaderName,
            String chunkerName,
            String embeddingModelName,
            Integer chunkSize,
            Integer chunkOverlap,
            Boolean keywordOnly,
            Boolean enableVlm,
            String collectionName
    ) {}

    public record RouteRuleInput(
            String pipelineId,
            List<String> contentTypes,
            List<String> fileExtensions,
            List<String> urlPatterns,
            List<String> sourceTypes,
            Integer priority,
            Long minSizeBytes,
            Long maxSizeBytes
    ) {}

    public record JobIdInput(String jobId) {}

    public record ListJobsInput(Boolean activeOnly) {}

    // ---- Tool methods ----

    @Tool(name = "list_crawlers",
            description = "Lists all available crawler implementations. Each crawler supports specific source types " +
                    "(e.g., 'web' for HTTP crawling, 'filesystem' for local file discovery). " +
                    "Use this to discover what crawlers are available before starting a crawl job.")
    public Map<String, Object> listCrawlers(ListJobsInput input) {
        try {
            List<Map<String, Object>> crawlers = crawlerService.listCrawlers();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("crawlers", crawlers);
            result.put("count", crawlers.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to list crawlers", e);
            return Map.of("error", "Failed to list crawlers: " + e.getMessage());
        }
    }

    @Tool(name = "start_crawl",
            description = "Starts a new crawl job to discover and ingest documents. " +
                    "Required: 'seed' (URL or file path to crawl from). " +
                    "Optional: 'crawlerId' (auto-detected from seed if omitted), 'maxDepth' (default 3), " +
                    "'maxDocuments' (default 1000), 'sameDomainOnly' (default true for web). " +
                    "For multi-pipeline routing, provide 'pipelines' (list of pipeline definitions) and " +
                    "'routeRules' (rules mapping content types/extensions to pipelines). " +
                    "Example: crawl a docs site with PDF and HTML pipelines by setting pipelines=[{pipelineId:'html',pipelineType:'STANDARD_TEXT'},{pipelineId:'pdf',pipelineType:'VLM'}] " +
                    "and routeRules=[{pipelineId:'pdf',contentTypes:['application/pdf'],priority:10}].")
    public Map<String, Object> startCrawl(StartCrawlInput input) {
        if (input == null || input.seed() == null || input.seed().isBlank()) {
            return Map.of("error", "seed is required");
        }

        try {
            CrawlConfig.CrawlConfigBuilder builder = CrawlConfig.builder()
                    .seed(input.seed());

            if (input.crawlerId() != null && !input.crawlerId().isBlank()) {
                builder.crawlerId(input.crawlerId());
            }
            if (input.maxDepth() != null) builder.maxDepth(input.maxDepth());
            if (input.maxDocuments() != null) builder.maxDocuments(input.maxDocuments());
            if (input.sameDomainOnly() != null) builder.sameDomainOnly(input.sameDomainOnly());
            if (input.respectRobotsTxt() != null) builder.respectRobotsTxt(input.respectRobotsTxt());
            if (input.requestDelayMs() != null) builder.requestDelay(java.time.Duration.ofMillis(input.requestDelayMs()));
            if (input.includePatterns() != null) builder.includePatterns(input.includePatterns());
            if (input.excludePatterns() != null) builder.excludePatterns(input.excludePatterns());
            if (input.collectionName() != null) builder.collectionName(input.collectionName());
            if (input.loaderName() != null) builder.loaderName(input.loaderName());
            if (input.chunkerName() != null) builder.chunkerName(input.chunkerName());
            if (input.defaultPipelineId() != null) builder.defaultPipelineId(input.defaultPipelineId());

            // Convert pipeline inputs
            if (input.pipelines() != null && !input.pipelines().isEmpty()) {
                List<IngestPipelineDefinition> pipelines = input.pipelines().stream()
                        .map(this::toPipelineDefinition)
                        .collect(Collectors.toList());
                builder.pipelines(pipelines);
            }

            // Convert route rule inputs
            if (input.routeRules() != null && !input.routeRules().isEmpty()) {
                List<ContentRouteRule> rules = input.routeRules().stream()
                        .map(this::toRouteRule)
                        .collect(Collectors.toList());
                builder.routeRules(rules);
            }

            CrawlConfig config = builder.build();
            CrawlJob job = crawlerService.startCrawl(config, null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("jobId", job.getJobId());
            result.put("jobStatus", job.getStatus().name());
            result.put("crawlerId", config.getCrawlerId());
            result.put("seed", config.getSeed());
            result.put("maxDepth", config.getMaxDepth());
            result.put("maxDocuments", config.getMaxDocuments());

            // Include pipeline info
            crawlerService.getRouter(job.getJobId()).ifPresent(router -> {
                result.put("pipelineCount", router.getAllPipelines().size());
                result.put("defaultPipeline", router.getDefaultPipeline().getPipelineId());
                List<String> pipelineIds = router.getAllPipelines().stream()
                        .map(IngestPipelineDefinition::getPipelineId)
                        .collect(Collectors.toList());
                result.put("pipelineIds", pipelineIds);
            });

            result.put("message", "Crawl job started successfully. Use get_crawl_job with the jobId to monitor progress.");
            return result;

        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to start crawl", e);
            return Map.of("error", "Failed to start crawl: " + e.getMessage());
        }
    }

    @Tool(name = "get_crawl_job",
            description = "Gets the status and progress of a crawl job by its ID. " +
                    "Returns: job status (PENDING/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED), " +
                    "progress (discovered, processed, failed, queued counts), current depth, " +
                    "estimated completion percentage, and pipeline information.")
    public Map<String, Object> getCrawlJob(JobIdInput input) {
        if (input == null || input.jobId() == null || input.jobId().isBlank()) {
            return Map.of("error", "jobId is required");
        }

        try {
            return crawlerService.getJob(input.jobId())
                    .map(job -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.put("jobId", job.getJobId());
                        result.put("jobStatus", job.getStatus().name());
                        result.put("crawlerId", job.getConfig().getCrawlerId());
                        result.put("seed", job.getConfig().getSeed());

                        CrawlProgress progress = job.getProgress();
                        Map<String, Object> progressMap = new LinkedHashMap<>();
                        progressMap.put("discovered", progress.discovered());
                        progressMap.put("processed", progress.processed());
                        progressMap.put("failed", progress.failed());
                        progressMap.put("queued", progress.queued());
                        progressMap.put("currentDepth", progress.currentDepth());
                        progressMap.put("maxDepth", progress.maxDepth());
                        progressMap.put("currentItem", progress.currentItem() != null ? progress.currentItem() : "");
                        progressMap.put("estimatedPercent", progress.estimatedPercent());
                        result.put("progress", progressMap);

                        // Include pipeline info if available
                        crawlerService.getRouter(input.jobId()).ifPresent(router -> {
                            List<Map<String, Object>> pipelines = new ArrayList<>();
                            for (IngestPipelineDefinition p : router.getAllPipelines()) {
                                Map<String, Object> pInfo = new LinkedHashMap<>();
                                pInfo.put("pipelineId", p.getPipelineId());
                                pInfo.put("displayName", p.getDisplayName() != null ? p.getDisplayName() : p.getPipelineId());
                                pInfo.put("pipelineType", p.getPipelineType().name());
                                pInfo.put("isDefault", p.getPipelineId().equals(router.getDefaultPipeline().getPipelineId()));
                                pipelines.add(pInfo);
                            }
                            result.put("pipelines", pipelines);
                        });

                        return result;
                    })
                    .orElse(Map.of("error", "Job not found: " + input.jobId()));

        } catch (Exception e) {
            logger.error("Failed to get crawl job", e);
            return Map.of("error", "Failed to get crawl job: " + e.getMessage());
        }
    }

    @Tool(name = "list_crawl_jobs",
            description = "Lists all crawl jobs. Set activeOnly=true to only show running/paused/pending jobs. " +
                    "Returns job IDs, statuses, seeds, and progress summaries.")
    public Map<String, Object> listCrawlJobs(ListJobsInput input) {
        try {
            Collection<CrawlJob> jobs;
            boolean activeOnly = input != null && Boolean.TRUE.equals(input.activeOnly());
            if (activeOnly) {
                jobs = crawlerService.getActiveJobs();
            } else {
                jobs = crawlerService.getAllJobs();
            }

            List<Map<String, Object>> jobList = new ArrayList<>();
            for (CrawlJob job : jobs) {
                Map<String, Object> jobInfo = new LinkedHashMap<>();
                jobInfo.put("jobId", job.getJobId());
                jobInfo.put("status", job.getStatus().name());
                jobInfo.put("crawlerId", job.getConfig().getCrawlerId());
                jobInfo.put("seed", job.getConfig().getSeed());

                CrawlProgress progress = job.getProgress();
                jobInfo.put("discovered", progress.discovered());
                jobInfo.put("processed", progress.processed());
                jobInfo.put("failed", progress.failed());
                jobInfo.put("estimatedPercent", progress.estimatedPercent());
                jobList.add(jobInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("activeOnly", activeOnly);
            result.put("count", jobList.size());
            result.put("jobs", jobList);
            return result;

        } catch (Exception e) {
            logger.error("Failed to list crawl jobs", e);
            return Map.of("error", "Failed to list crawl jobs: " + e.getMessage());
        }
    }

    @Tool(name = "pause_crawl_job",
            description = "Pauses a running crawl job. The job can be resumed later with resume_crawl_job. " +
                    "Only running jobs can be paused.")
    public Map<String, Object> pauseCrawlJob(JobIdInput input) {
        if (input == null || input.jobId() == null || input.jobId().isBlank()) {
            return Map.of("error", "jobId is required");
        }
        try {
            boolean paused = crawlerService.pauseJob(input.jobId());
            if (paused) {
                return Map.of("status", "success", "jobId", input.jobId(), "message", "Job paused");
            } else {
                return Map.of("error", "Job not found or not in a running state: " + input.jobId());
            }
        } catch (Exception e) {
            logger.error("Failed to pause crawl job", e);
            return Map.of("error", "Failed to pause job: " + e.getMessage());
        }
    }

    @Tool(name = "resume_crawl_job",
            description = "Resumes a paused crawl job. Only paused jobs can be resumed.")
    public Map<String, Object> resumeCrawlJob(JobIdInput input) {
        if (input == null || input.jobId() == null || input.jobId().isBlank()) {
            return Map.of("error", "jobId is required");
        }
        try {
            boolean resumed = crawlerService.resumeJob(input.jobId());
            if (resumed) {
                return Map.of("status", "success", "jobId", input.jobId(), "message", "Job resumed");
            } else {
                return Map.of("error", "Job not found or not paused: " + input.jobId());
            }
        } catch (Exception e) {
            logger.error("Failed to resume crawl job", e);
            return Map.of("error", "Failed to resume job: " + e.getMessage());
        }
    }

    @Tool(name = "cancel_crawl_job",
            description = "Cancels a running or paused crawl job. This stops the crawl permanently. " +
                    "Already completed, failed, or cancelled jobs cannot be cancelled.")
    public Map<String, Object> cancelCrawlJob(JobIdInput input) {
        if (input == null || input.jobId() == null || input.jobId().isBlank()) {
            return Map.of("error", "jobId is required");
        }
        try {
            boolean cancelled = crawlerService.cancelJob(input.jobId());
            if (cancelled) {
                return Map.of("status", "success", "jobId", input.jobId(), "message", "Job cancelled");
            } else {
                return Map.of("error", "Job not found or already finished: " + input.jobId());
            }
        } catch (Exception e) {
            logger.error("Failed to cancel crawl job", e);
            return Map.of("error", "Failed to cancel job: " + e.getMessage());
        }
    }

    @Tool(name = "cleanup_crawl_jobs",
            description = "Removes completed, failed, and cancelled crawl jobs from the job tracker. " +
                    "Running and paused jobs are not affected. Returns the number of jobs removed.")
    public Map<String, Object> cleanupCrawlJobs(ListJobsInput input) {
        try {
            int removed = crawlerService.cleanupJobs();
            return Map.of("status", "success", "removedCount", removed,
                    "message", removed + " finished job(s) removed");
        } catch (Exception e) {
            logger.error("Failed to cleanup crawl jobs", e);
            return Map.of("error", "Failed to cleanup jobs: " + e.getMessage());
        }
    }

    // ---- Conversion helpers ----

    private IngestPipelineDefinition toPipelineDefinition(PipelineInput input) {
        IngestPipelineDefinition.IngestPipelineDefinitionBuilder builder = IngestPipelineDefinition.builder()
                .pipelineId(input.pipelineId());

        if (input.displayName() != null) builder.displayName(input.displayName());
        if (input.pipelineType() != null) {
            try {
                builder.pipelineType(IngestPipelineDefinition.PipelineType.valueOf(input.pipelineType()));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown pipeline type '{}', defaulting to STANDARD_TEXT", input.pipelineType());
                builder.pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT);
            }
        }
        if (input.loaderName() != null) builder.loaderName(input.loaderName());
        if (input.chunkerName() != null) builder.chunkerName(input.chunkerName());
        if (input.embeddingModelName() != null) builder.embeddingModelName(input.embeddingModelName());
        if (input.chunkSize() != null) builder.chunkSize(input.chunkSize());
        if (input.chunkOverlap() != null) builder.chunkOverlap(input.chunkOverlap());
        if (input.keywordOnly() != null) builder.keywordOnly(input.keywordOnly());
        if (input.enableVlm() != null) builder.enableVlm(input.enableVlm());
        if (input.collectionName() != null) builder.collectionName(input.collectionName());

        return builder.build();
    }

    private ContentRouteRule toRouteRule(RouteRuleInput input) {
        ContentRouteRule.ContentRouteRuleBuilder builder = ContentRouteRule.builder()
                .pipelineId(input.pipelineId());

        if (input.contentTypes() != null) builder.contentTypes(input.contentTypes());
        if (input.fileExtensions() != null) builder.fileExtensions(input.fileExtensions());
        if (input.urlPatterns() != null) builder.urlPatterns(input.urlPatterns());
        if (input.priority() != null) builder.priority(input.priority());
        if (input.minSizeBytes() != null) builder.minSizeBytes(input.minSizeBytes());
        if (input.maxSizeBytes() != null) builder.maxSizeBytes(input.maxSizeBytes());

        if (input.sourceTypes() != null) {
            List<DocumentSourceDescriptor.SourceType> types = input.sourceTypes().stream()
                    .map(s -> {
                        try {
                            return DocumentSourceDescriptor.SourceType.valueOf(s);
                        } catch (IllegalArgumentException e) {
                            logger.warn("Unknown source type '{}', skipping", s);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            builder.sourceTypes(types);
        }

        return builder.build();
    }
}
