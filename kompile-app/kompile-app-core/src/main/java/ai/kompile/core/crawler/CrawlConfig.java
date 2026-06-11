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

package ai.kompile.core.crawler;

import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a crawl job. Covers all crawler types with
 * type-specific settings in the {@link #properties} map.
 *
 * <h3>Multi-pipeline support</h3>
 * <p>Each crawl job can define multiple {@link IngestPipelineDefinition}s and
 * {@link ContentRouteRule}s that route discovered items to the appropriate pipeline
 * based on content type, file extension, URL pattern, or source type.</p>
 *
 * <pre>
 * CrawlConfig.builder()
 *     .seed("https://docs.example.com")
 *     .pipelines(List.of(
 *         IngestPipelineDefinition.builder().pipelineId("html").pipelineType(STANDARD_TEXT).build(),
 *         IngestPipelineDefinition.builder().pipelineId("pdf-vlm").pipelineType(VLM).enableVlm(true).build()
 *     ))
 *     .routeRules(List.of(
 *         ContentRouteRule.builder().pipelineId("pdf-vlm").contentTypes(List.of("application/pdf")).build(),
 *         ContentRouteRule.builder().pipelineId("html").contentTypes(List.of("text/html")).build()
 *     ))
 *     .defaultPipelineId("html")
 *     .build();
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlConfig {

    /** Which crawler implementation to use (matches {@link Crawler#getId()}) */
    private String crawlerId;

    /** The seed URL, root directory path, or connection string */
    private String seed;

    /** Source type hint — crawlers use this to select behavior */
    private DocumentSourceDescriptor.SourceType sourceType;

    // ---- Crawl bounds ----

    /** Maximum link/directory depth to follow from the seed (default: 3) */
    @Builder.Default
    private int maxDepth = 3;

    /** Maximum number of documents to discover before stopping (default: 1000, 0 = unlimited) */
    @Builder.Default
    private int maxDocuments = 1000;

    /** Delay between requests to avoid overwhelming the source (default: 500ms) */
    @Builder.Default
    private Duration requestDelay = Duration.ofMillis(500);

    /** Overall timeout for the entire crawl job (default: 1 hour) */
    @Builder.Default
    private Duration timeout = Duration.ofHours(1);

    // ---- Filtering ----

    /** URL/path patterns to include (glob or regex). Empty = include all. */
    @Builder.Default
    private List<String> includePatterns = new ArrayList<>();

    /** URL/path patterns to exclude (glob or regex). Applied after include. */
    @Builder.Default
    private List<String> excludePatterns = new ArrayList<>();

    /** MIME types to accept (e.g., "text/html", "application/pdf"). Empty = accept all. */
    @Builder.Default
    private List<String> allowedContentTypes = new ArrayList<>();

    // ---- Web-specific ----

    /** Whether to restrict the crawl to the same domain as the seed URL (default: true) */
    @Builder.Default
    private boolean sameDomainOnly = true;

    /** Whether to respect robots.txt directives (default: true) */
    @Builder.Default
    private boolean respectRobotsTxt = true;

    /** User-Agent string for HTTP requests */
    @Builder.Default
    private String userAgent = "Mozilla/5.0 (compatible; KompileBot/1.0; +https://kompile.ai)";

    // ---- Incremental crawl ----

    /** State from a previous crawl, enabling incremental mode. Null = fresh crawl. */
    private CrawlState previousState;

    /** When true, ignore any persisted crawl state and perform a full re-crawl. */
    @Builder.Default
    private boolean forceRecrawl = false;

    // ---- Indexing integration ----

    /** Target collection name for indexed documents */
    private String collectionName;

    /** Preferred document loader name (null = auto-detect) */
    private String loaderName;

    /** Preferred chunker name (null = default) */
    private String chunkerName;

    // ---- Multi-pipeline routing ----

    /**
     * Pipeline definitions available for this crawl job.
     * Each defines a distinct processing strategy (e.g., VLM, code-aware, standard text).
     * If empty, a single default pipeline using loaderName/chunkerName is used.
     */
    @Builder.Default
    private List<IngestPipelineDefinition> pipelines = new ArrayList<>();

    /**
     * Rules that route discovered items to specific pipelines.
     * Evaluated in priority order; first match wins.
     * If empty, all items use the default pipeline.
     */
    @Builder.Default
    private List<ContentRouteRule> routeRules = new ArrayList<>();

    /**
     * ID of the pipeline to use when no route rule matches.
     * Must reference a pipeline in {@link #pipelines}.
     * If null and pipelines are defined, the first pipeline is the default.
     */
    private String defaultPipelineId;

    // ---- Extension point ----

    /**
     * Crawler-specific properties not covered by the standard fields.
     * For example:
     * <ul>
     *   <li>SharePoint: {@code siteUrl}, {@code driveId}, {@code clientId}</li>
     *   <li>IMAP: {@code host}, {@code port}, {@code folder}, {@code since}</li>
     *   <li>Filesystem: {@code watchForChanges}, {@code followSymlinks}</li>
     * </ul>
     */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();
}
