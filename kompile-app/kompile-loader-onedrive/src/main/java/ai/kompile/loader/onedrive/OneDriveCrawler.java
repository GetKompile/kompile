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

package ai.kompile.loader.onedrive;

import ai.kompile.cli.common.util.HttpConstants;
import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Crawler that recursively traverses Microsoft OneDrive folders via the
 * Microsoft Graph API v1.0, emitting {@link CrawlItem}s for each discovered file.
 *
 * <h3>Configuration (via {@link CrawlConfig#getProperties()})</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code accessToken}</td><td>—</td><td>OAuth 2.0 bearer token. Falls back to the
 *       {@code "microsoft"} provider in {@link OAuthConnectionService} when omitted.</td></tr>
 *   <tr><td>{@code driveId}</td><td>{@code "me"}</td><td>OneDrive drive identifier. Use
 *       {@code "me"} for the personal drive of the authenticated user.</td></tr>
 *   <tr><td>{@code folderId}</td><td>{@code "root"}</td><td>Starting folder item ID.</td></tr>
 *   <tr><td>{@code siteId}</td><td>—</td><td>SharePoint site ID. When provided, Graph API
 *       calls are routed via {@code /sites/{siteId}/drive/…} instead of
 *       {@code /drives/{driveId}/…}.</td></tr>
 *   <tr><td>{@code maxFileSize}</td><td>67108864</td><td>Maximum file size in bytes to
 *       download (64 MiB). Larger files are skipped.</td></tr>
 *   <tr><td>{@code requestDelayMs}</td><td>(from CrawlConfig.requestDelay)</td><td>Milliseconds
 *       to wait between Graph API calls. Overrides the CrawlConfig-level delay when set.</td></tr>
 * </table>
 *
 * <h3>Traversal</h3>
 * <p>The crawler performs a BFS folder traversal starting at {@code folderId}. For each
 * discovered file it downloads a local copy to a temporary directory and emits a
 * {@link CrawlItem} whose {@link DocumentSourceDescriptor} points to that local copy
 * (type {@link SourceType#FILE}) so the downstream ingest pipeline can process it
 * identically to local files.</p>
 *
 * <h3>Pagination</h3>
 * <p>The Microsoft Graph API pages large directory listings via an
 * {@code @odata.nextLink} field. This crawler follows all next-links transparently.</p>
 *
 * <h3>Incremental crawls</h3>
 * <p>When a previous {@link CrawlState} is present in the config, files whose
 * {@code lastModifiedDateTime} has not advanced since the prior run are skipped.</p>
 *
 * <h3>Rate limiting</h3>
 * <p>A configurable delay is inserted between each Graph API request. On HTTP 429
 * (Too Many Requests) the crawler honours the {@code Retry-After} header and
 * sleeps accordingly before retrying once.</p>
 *
 * <h3>Token refresh</h3>
 * <p>A 401 Unauthorized response triggers a single token-refresh attempt via
 * {@link OAuthConnectionService#refreshConnection(String)} (if the service is
 * available). The request is then retried with the new token.</p>
 */
@Component
public class OneDriveCrawler extends AbstractCrawler {

    private static final Logger log = LoggerFactory.getLogger(OneDriveCrawler.class);

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String OAUTH_PROVIDER_ID = "microsoft";
    private static final long DEFAULT_MAX_FILE_SIZE = 64L * 1024L * 1024L; // 64 MiB

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final OAuthConnectionService oauthService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    public OneDriveCrawler(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    // -----------------------------------------------------------------------
    // Crawler identity
    // -----------------------------------------------------------------------

    @Override
    public String getId() {
        return "onedrive";
    }

    @Override
    public String getName() {
        return "Microsoft OneDrive Crawler";
    }

    @Override
    public String getDescription() {
        return "Recursively traverses OneDrive/SharePoint folders via Microsoft Graph API v1.0 "
                + "and downloads files for ingestion";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.ONEDRIVE);
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();

        String token = resolveAccessToken(config);
        if (token == null || token.isBlank()) {
            if (oauthService == null) {
                errors.add("No Microsoft OAuth access token available and OAuthConnectionService is not present. "
                        + "Provide 'accessToken' in crawl properties or connect the 'microsoft' provider.");
            } else {
                // Token may be available at runtime — don't fail validation here
                log.debug("OneDrive access token not present at validation time; "
                        + "will resolve from OAuthConnectionService at crawl start.");
            }
        }

        return errors;
    }

    // -----------------------------------------------------------------------
    // Job factory
    // -----------------------------------------------------------------------

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new OneDriveCrawlJob(jobId, config, listener);
    }

    // -----------------------------------------------------------------------
    // Core crawl execution
    // -----------------------------------------------------------------------

    @Override
    protected void executeCrawl(AbstractCrawlJob job) throws Exception {
        OneDriveCrawlJob oneDriveJob = (OneDriveCrawlJob) job;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        // Resolve effective access token
        String accessToken = resolveAccessToken(config);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(
                    "No Microsoft OAuth access token available. Connect the 'microsoft' provider via the OAuth "
                            + "connections UI or pass 'accessToken' in CrawlConfig properties.");
        }

        String driveId = prop(props, "driveId", "me");
        String folderId = prop(props, "folderId", "root");
        String siteId   = prop(props, "siteId", null);
        long maxFileSize = propLong(props, "maxFileSize", DEFAULT_MAX_FILE_SIZE);
        long requestDelayMs = config.getRequestDelay() != null
                ? config.getRequestDelay().toMillis() : 500L;
        if (props.containsKey("requestDelayMs")) {
            requestDelayMs = propLong(props, "requestDelayMs", requestDelayMs);
        }

        // Compile include/exclude patterns
        List<Pattern> includes = compilePatterns(config.getIncludePatterns());
        List<Pattern> excludes = compilePatterns(config.getExcludePatterns());

        // Create temp download directory
        Path downloadDir = Files.createTempDirectory("kompile-onedrive-" + job.getJobId() + "-");
        oneDriveJob.downloadDir = downloadDir;

        log.info("[{}] OneDrive crawl starting: driveId={}, folderId={}, siteId={}, maxDepth={}",
                job.getJobId(), driveId, folderId, siteId, config.getMaxDepth());

        // Mutable token holder — may be refreshed on 401
        String[] tokenHolder = { accessToken };

        // BFS frontier: each entry is (folderId, depth, parentPath)
        Deque<FolderEntry> frontier = new ArrayDeque<>();
        frontier.add(new FolderEntry(folderId, 0, ""));

        while (!frontier.isEmpty()) {
            if (oneDriveJob.shouldStop()) break;
            if (!oneDriveJob.checkPauseAndContinue()) break;

            FolderEntry current = frontier.poll();
            oneDriveJob.setCurrentDepth(current.depth());
            log.debug("[{}] Listing folder {} at depth {}", job.getJobId(), current.itemId(), current.depth());

            String nextLink = buildChildrenUrl(driveId, current.itemId(), siteId);

            // Follow pagination
            while (nextLink != null) {
                if (oneDriveJob.shouldStop()) break;

                throttle(requestDelayMs);

                JsonNode page = fetchJson(nextLink, tokenHolder, oneDriveJob, config);
                if (page == null) break;

                JsonNode values = page.path("value");
                if (!values.isArray()) break;

                for (JsonNode item : values) {
                    if (oneDriveJob.shouldStop()) break;

                    String itemId   = item.path("id").asText();
                    String itemName = item.path("name").asText(itemId);
                    boolean isFolder = item.has("folder");
                    boolean isFile   = item.has("file");

                    String itemPath = current.parentPath().isEmpty()
                            ? itemName : current.parentPath() + "/" + itemName;

                    if (isFolder) {
                        // Recurse if within depth limit
                        int nextDepth = current.depth() + 1;
                        if (config.getMaxDepth() <= 0 || nextDepth <= config.getMaxDepth()) {
                            frontier.add(new FolderEntry(itemId, nextDepth, itemPath));
                        } else {
                            log.debug("[{}] Skipping folder {} (maxDepth {} reached)",
                                    job.getJobId(), itemPath, config.getMaxDepth());
                        }
                        continue;
                    }

                    if (!isFile) {
                        // OneNote notebooks etc. — skip
                        continue;
                    }

                    // Pattern filtering
                    if (!matchesPatterns(itemPath, itemName, includes, excludes)) {
                        oneDriveJob.incrementSkipped();
                        oneDriveJob.getListener().onDocumentSkipped(itemPath, "filtered by include/exclude patterns");
                        continue;
                    }

                    // Content type filtering
                    String mimeType = item.path("file").path("mimeType").asText(null);
                    if (mimeType == null) {
                        mimeType = probeContentTypeByExtension(itemName);
                    }
                    if (!config.getAllowedContentTypes().isEmpty() && mimeType != null) {
                        if (!matchesContentType(mimeType, config.getAllowedContentTypes())) {
                            oneDriveJob.incrementSkipped();
                            oneDriveJob.getListener().onDocumentSkipped(itemPath, "content type not accepted: " + mimeType);
                            continue;
                        }
                    }

                    // Size check
                    long fileSize = item.path("size").asLong(0L);
                    if (fileSize > maxFileSize) {
                        oneDriveJob.incrementSkipped();
                        oneDriveJob.getListener().onDocumentSkipped(itemPath,
                                "file too large: " + fileSize + " bytes (max " + maxFileSize + ")");
                        continue;
                    }

                    // Incremental: skip if not modified since last crawl
                    String lastModifiedStr = item.path("lastModifiedDateTime").asText(null);
                    long lastModifiedMs = parseEpochMs(lastModifiedStr);
                    CrawlState prevState = config.getPreviousState();
                    if (prevState != null && !config.isForceRecrawl() && lastModifiedMs > 0) {
                        if (!prevState.isModifiedSince(itemId, lastModifiedMs)) {
                            oneDriveJob.incrementSkipped();
                            oneDriveJob.getListener().onDocumentSkipped(itemPath, "unchanged since last crawl");
                            continue;
                        }
                    }

                    // Download file to temp directory
                    oneDriveJob.setCurrentItem(itemPath);
                    String downloadUrl = buildDownloadUrl(driveId, itemId, siteId);
                    Path localFile = downloadDir.resolve(sanitizePath(itemPath));
                    Files.createDirectories(localFile.getParent());

                    try {
                        throttle(requestDelayMs);
                        downloadFile(downloadUrl, tokenHolder, localFile, oneDriveJob, config);
                    } catch (Exception e) {
                        oneDriveJob.recordError(itemPath, e);
                        continue;
                    }

                    // Track for incremental state
                    oneDriveJob.visitedItemIds.add(itemId);
                    if (lastModifiedMs > 0) {
                        oneDriveJob.lastModifiedTimes.put(itemId, lastModifiedMs);
                    }

                    // Build metadata
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put(GraphConstants.META_FILE_NAME, itemName);
                    metadata.put(GraphConstants.META_FILE_SIZE, fileSize);
                    metadata.put(GraphConstants.META_SOURCE_PATH, itemPath);
                    metadata.put(GraphConstants.META_LOADER, getName());
                    metadata.put(GraphConstants.META_DOCUMENT_TYPE, resolveDocType(itemName));
                    metadata.put(GraphConstants.META_SOURCE_TYPE, SourceType.ONEDRIVE.name());
                    metadata.put("onedrive_item_id", itemId);
                    metadata.put("onedrive_drive_id", driveId);
                    if (siteId != null) metadata.put("onedrive_site_id", siteId);
                    metadata.put("crawlJobId", job.getJobId());
                    if (lastModifiedStr != null) {
                        metadata.put(GraphConstants.META_LAST_MODIFIED, lastModifiedMs);
                        metadata.put("onedrive_last_modified", lastModifiedStr);
                    }
                    if (item.has("webUrl")) {
                        metadata.put("onedrive_web_url", item.get("webUrl").asText());
                    }
                    if (item.has("createdDateTime")) {
                        metadata.put("onedrive_created", item.get("createdDateTime").asText());
                    }
                    if (item.path("createdBy").path("user").has("displayName")) {
                        metadata.put("onedrive_created_by",
                                item.path("createdBy").path("user").path("displayName").asText());
                    }
                    if (mimeType != null) {
                        metadata.put("onedrive_mime_type", mimeType);
                    }

                    String localPath = localFile.toAbsolutePath().toString();
                    CrawlItem crawlItem = CrawlItem.builder()
                            .url(localPath)
                            .parentUrl(config.getSeed())
                            .depth(current.depth())
                            .contentType(mimeType)
                            .contentLength(fileSize > 0 ? fileSize : null)
                            .discoveredAt(Instant.now())
                            .metadata(metadata)
                            .sourceDescriptor(DocumentSourceDescriptor.builder()
                                    .type(SourceType.FILE) // local copy is a regular file
                                    .pathOrUrl(localPath)
                                    .sourceId(itemId)
                                    .originalFileName(itemName)
                                    .collectionName(config.getCollectionName())
                                    .build())
                            .build();

                    oneDriveJob.incrementDiscovered();
                    oneDriveJob.getListener().onDocumentDiscovered(crawlItem);
                    oneDriveJob.incrementProcessed();
                    oneDriveJob.getListener().onDocumentProcessed(crawlItem);

                    int totalDiscovered = oneDriveJob.getDiscoveredCount();
                    if (totalDiscovered % 50 == 0) {
                        oneDriveJob.getListener().onProgress(oneDriveJob.getProgress());
                    }
                }

                // Move to next page (or stop)
                String rawNext = page.path("@odata.nextLink").asText(null);
                nextLink = (rawNext != null && !rawNext.isBlank()) ? rawNext : null;
            }
        }

        log.info("[{}] OneDrive crawl complete: discovered={}, downloadDir={}",
                job.getJobId(), oneDriveJob.getDiscoveredCount(), downloadDir);
    }

    // -----------------------------------------------------------------------
    // Microsoft Graph API helpers
    // -----------------------------------------------------------------------

    /**
     * Builds the URL to list the children of a folder item.
     * Routing prefers the SharePoint site path when {@code siteId} is provided.
     */
    private static String buildChildrenUrl(String driveId, String itemId, String siteId) {
        String encodedItem = encode(itemId);
        if (siteId != null && !siteId.isBlank()) {
            return GRAPH_BASE + "/sites/" + encode(siteId)
                    + "/drive/items/" + encodedItem + "/children"
                    + "?$select=id,name,size,file,folder,lastModifiedDateTime,createdDateTime,createdBy,webUrl"
                    + "&$top=200";
        }
        if ("me".equalsIgnoreCase(driveId)) {
            return GRAPH_BASE + "/me/drive/items/" + encodedItem + "/children"
                    + "?$select=id,name,size,file,folder,lastModifiedDateTime,createdDateTime,createdBy,webUrl"
                    + "&$top=200";
        }
        return GRAPH_BASE + "/drives/" + encode(driveId)
                + "/items/" + encodedItem + "/children"
                + "?$select=id,name,size,file,folder,lastModifiedDateTime,createdDateTime,createdBy,webUrl"
                + "&$top=200";
    }

    /**
     * Builds the content download URL for a file item.
     */
    private static String buildDownloadUrl(String driveId, String itemId, String siteId) {
        String encodedItem = encode(itemId);
        if (siteId != null && !siteId.isBlank()) {
            return GRAPH_BASE + "/sites/" + encode(siteId)
                    + "/drive/items/" + encodedItem + "/content";
        }
        if ("me".equalsIgnoreCase(driveId)) {
            return GRAPH_BASE + "/me/drive/items/" + encodedItem + "/content";
        }
        return GRAPH_BASE + "/drives/" + encode(driveId)
                + "/items/" + encodedItem + "/content";
    }

    /**
     * Fetches a JSON response from the Graph API, handling 401 (token refresh) and
     * 429 (rate limit) transparently.
     *
     * @param url         The URL to fetch (may be a paged nextLink)
     * @param tokenHolder Mutable single-element array so the token can be refreshed in place
     * @param job         Running crawl job (for error recording)
     * @param config      Crawl config (for OAuth provider lookup on refresh)
     * @return Parsed JSON node, or {@code null} if the request ultimately failed
     */
    private JsonNode fetchJson(String url, String[] tokenHolder, OneDriveCrawlJob job, CrawlConfig config) {
        try {
            HttpResponse<String> response = sendGet(url, tokenHolder[0], HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                log.warn("[{}] Graph API 401 — attempting token refresh", job.getJobId());
                String refreshed = refreshToken(config);
                if (refreshed != null) {
                    tokenHolder[0] = refreshed;
                    response = sendGet(url, tokenHolder[0], HttpResponse.BodyHandlers.ofString());
                }
            }

            if (response.statusCode() == 429) {
                long retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse("5"));
                log.warn("[{}] Graph API 429 — waiting {} s before retry", job.getJobId(), retryAfter);
                Thread.sleep(retryAfter * 1000L);
                response = sendGet(url, tokenHolder[0], HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() / 100 != 2) {
                log.warn("[{}] Graph API error {}: {}", job.getJobId(), response.statusCode(), url);
                return null;
            }

            return objectMapper.readTree(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("[{}] Graph API request failed for {}: {}", job.getJobId(), url, e.getMessage());
            return null;
        }
    }

    /**
     * Downloads a file from the Graph API content URL to {@code localFile}, handling
     * 401 and 429 responses the same way as {@link #fetchJson}.
     */
    private void downloadFile(String url, String[] tokenHolder, Path localFile,
                              OneDriveCrawlJob job, CrawlConfig config) throws Exception {
        HttpResponse<InputStream> response = sendGet(url, tokenHolder[0], HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 401) {
            log.warn("[{}] Download 401 — attempting token refresh for {}", job.getJobId(), localFile.getFileName());
            String refreshed = refreshToken(config);
            if (refreshed != null) {
                tokenHolder[0] = refreshed;
                response = sendGet(url, tokenHolder[0], HttpResponse.BodyHandlers.ofInputStream());
            }
        }

        if (response.statusCode() == 429) {
            long retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse("5"));
            log.warn("[{}] Download 429 — waiting {} s before retry", job.getJobId(), retryAfter);
            Thread.sleep(retryAfter * 1000L);
            response = sendGet(url, tokenHolder[0], HttpResponse.BodyHandlers.ofInputStream());
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Graph API download failed with HTTP " + response.statusCode()
                    + " for " + localFile.getFileName());
        }

        try (InputStream in = response.body()) {
            Files.copy(in, localFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Sends an authenticated GET request and returns the raw response. */
    private <T> HttpResponse<T> sendGet(String url, String token,
                                        HttpResponse.BodyHandler<T> bodyHandler) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header(HttpConstants.AUTHORIZATION, HttpConstants.BEARER_PREFIX + token)
                .header(HttpConstants.ACCEPT, HttpConstants.APPLICATION_JSON)
                .GET()
                .build();
        return httpClient.send(request, bodyHandler);
    }

    // -----------------------------------------------------------------------
    // Token resolution & refresh
    // -----------------------------------------------------------------------

    /**
     * Resolves the OAuth access token from the crawl config properties, falling
     * back to the {@link OAuthConnectionService} for the {@code "microsoft"} provider.
     */
    private String resolveAccessToken(CrawlConfig config) {
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();
        Object explicit = props.get("accessToken");
        if (explicit instanceof String s && !s.isBlank()) {
            return s;
        }
        if (oauthService != null) {
            return oauthService.getValidAccessToken(OAUTH_PROVIDER_ID);
        }
        return null;
    }

    /**
     * Attempts to refresh the Microsoft OAuth token via {@link OAuthConnectionService}.
     *
     * @return The new access token, or {@code null} if refresh is unavailable or fails.
     */
    private String refreshToken(CrawlConfig config) {
        if (oauthService == null) {
            log.debug("OAuthConnectionService not available — cannot refresh token");
            return null;
        }
        try {
            oauthService.refreshConnection(OAUTH_PROVIDER_ID);
            return oauthService.getValidAccessToken(OAUTH_PROVIDER_ID);
        } catch (Exception e) {
            log.warn("Failed to refresh Microsoft OAuth token: {}", e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Pattern matching
    // -----------------------------------------------------------------------

    private List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        List<Pattern> compiled = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            try {
                // Convert simple glob syntax to regex if no regex metacharacters are present
                String regex = (p.contains("*") && !p.contains(".*"))
                        ? p.replace(".", "\\.").replace("*", ".*") : p;
                compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                log.warn("Invalid crawl pattern '{}': {}", p, e.getMessage());
            }
        }
        return compiled;
    }

    private static boolean matchesPatterns(String itemPath, String itemName,
                                           List<Pattern> includes, List<Pattern> excludes) {
        if (!includes.isEmpty()) {
            boolean matched = false;
            for (Pattern p : includes) {
                if (p.matcher(itemPath).find() || p.matcher(itemName).find()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        for (Pattern p : excludes) {
            if (p.matcher(itemPath).find() || p.matcher(itemName).find()) return false;
        }
        return true;
    }

    private static boolean matchesContentType(String contentType, List<String> allowed) {
        for (String a : allowed) {
            if (contentType.startsWith(a.trim())) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Applies the configured inter-request delay. */
    private static void throttle(long delayMs) {
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** URL-encodes a path segment. */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Converts a OneDrive item path such as {@code "Reports/Q1/data.xlsx"} into a
     * safe relative path suitable for {@link Path#resolve(String)}.
     */
    private static String sanitizePath(String itemPath) {
        String normalized = itemPath.replace("\\", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        // Replace characters that are illegal in file names on common platforms
        return normalized.replaceAll("[\\x00-\\x1F<>:\"|?*]", "_");
    }

    /** Parses an ISO-8601 date-time string to epoch milliseconds; returns 0 on failure. */
    private static long parseEpochMs(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) return 0L;
        try {
            return java.time.OffsetDateTime.parse(dateTime).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Parses the {@code Retry-After} header value (seconds); returns 5 on failure. */
    private static long parseRetryAfter(String headerValue) {
        try {
            return Math.max(1L, Long.parseLong(headerValue.trim()));
        } catch (NumberFormatException e) {
            return 5L;
        }
    }

    /** Reads a string property from the properties map with a default fallback. */
    private static String prop(Map<String, Object> props, String key, String defaultValue) {
        Object v = props.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    /** Reads a long property from the properties map with a default fallback. */
    private static long propLong(Map<String, Object> props, String key, long defaultValue) {
        Object v = props.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) {
                log.debug("Property '{}' has non-numeric value '{}', using default {}: {}", key, s, defaultValue, e.getMessage());
            }
        }
        return defaultValue;
    }

    /** Infers a MIME type from the file extension. */
    private static String probeContentTypeByExtension(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml"))  return "application/xml";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".doc"))  return "application/msword";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt"))  return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".rtf"))  return "application/rtf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".eml"))  return "message/rfc822";
        return null;
    }

    /** Maps a file extension to a friendly document-type label. */
    private static String resolveDocType(String fileName) {
        if (fileName == null) return "file";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv")) return "spreadsheet";
        if (lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".rtf")) return "document";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return "presentation";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML Document";
        if (lower.endsWith(".eml") || lower.endsWith(".msg") || lower.endsWith(".mbox")) return "email";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".xml")) return "text";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) return "image";
        return "file";
    }

    // -----------------------------------------------------------------------
    // BFS frontier entry
    // -----------------------------------------------------------------------

    /** Immutable BFS queue entry carrying a folder's item ID, traversal depth, and virtual path. */
    private record FolderEntry(String itemId, int depth, String parentPath) {}
}
