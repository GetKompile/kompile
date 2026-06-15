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

package ai.kompile.loader.gdrive;

import ai.kompile.cli.common.util.HttpConstants;
import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crawler that recursively traverses Google Drive folder hierarchies via the
 * Drive REST API v3, emitting a {@link CrawlItem} for every file discovered.
 *
 * <h3>Configuration (via {@link CrawlConfig#getProperties()})</h3>
 * <ul>
 *   <li>{@code accessToken} – OAuth 2.0 bearer token. If absent, the token is
 *       resolved from {@link OAuthConnectionService} under provider ID
 *       {@code "google"}.</li>
 *   <li>{@code folderId} – Drive folder ID to start the traversal from. Defaults
 *       to {@code "root"} (My Drive).</li>
 *   <li>{@code includeSharedDrives} – {@code true} to include Shared Drive
 *       content when using the root folder. Default: {@code false}.</li>
 *   <li>{@code mimeTypeFilter} – comma-separated MIME types to include. Empty
 *       means accept all types.</li>
 *   <li>{@code maxFileSize} – maximum file size in bytes to download and emit.
 *       Files larger than this limit are skipped. Default: 64 MiB.</li>
 *   <li>{@code requestDelayMs} – override for the inter-request delay in
 *       milliseconds. Falls back to {@link CrawlConfig#getRequestDelay()}.</li>
 * </ul>
 *
 * <h3>Google Workspace file export</h3>
 * <p>Google Workspace document formats cannot be downloaded directly. This
 * crawler exports them to the closest plain-text equivalent:
 * <ul>
 *   <li>Google Docs → {@code text/plain}</li>
 *   <li>Google Sheets → {@code text/csv}</li>
 *   <li>Google Slides → {@code text/plain}</li>
 *   <li>Google Drawings → {@code image/svg+xml}</li>
 * </ul>
 * All other Workspace types are skipped.</p>
 *
 * <h3>Incremental crawls</h3>
 * <p>When a {@link ai.kompile.core.crawler.CrawlState} is provided in the
 * config, files whose {@code modifiedTime} has not changed since the last run
 * are skipped unless {@link CrawlConfig#isForceRecrawl()} is set.</p>
 *
 * <h3>Token refresh</h3>
 * <p>If a Drive API call returns HTTP 401, the crawler attempts one refresh via
 * {@link OAuthConnectionService#refreshConnection(String)} before failing the
 * crawl.</p>
 */
@Slf4j
@Component
public class GoogleDriveCrawler extends AbstractCrawler {

    // ---- Drive API constants ----
    private static final String DRIVE_API_BASE   = "https://www.googleapis.com/drive/v3";
    private static final String LIST_FIELDS       =
            "nextPageToken,files(id,name,mimeType,size,modifiedTime,webViewLink,parents,trashed)";
    private static final String FOLDER_MIME       = "application/vnd.google-apps.folder";
    private static final String GOOGLE_APPS_PREFIX = "application/vnd.google-apps.";
    private static final String OAUTH_PROVIDER_ID = "google";

    // ---- Defaults ----
    private static final long   DEFAULT_MAX_FILE_BYTES = 64L * 1024L * 1024L; // 64 MiB
    private static final String DEFAULT_FOLDER_ID      = "root";
    private static final int    PAGE_SIZE              = 100;

    private final HttpClient     httpClient;
    private final ObjectMapper   objectMapper;
    private final OAuthConnectionService oauthService;

    @Autowired
    public GoogleDriveCrawler(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Crawler identity
    // -------------------------------------------------------------------------

    @Override
    public String getId() {
        return "gdrive";
    }

    @Override
    public String getName() {
        return "Google Drive Crawler";
    }

    @Override
    public String getDescription() {
        return "Recursively traverses Google Drive folder hierarchies via the Drive API v3, "
                + "emitting CrawlItems for each discovered file.";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.GDRIVE);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        // Access token can come from the properties map or from OAuthConnectionService.
        // We can't validate the token itself here (would require a network call), so we
        // only flag the case where neither source is configured at all.
        String token = prop(config, "accessToken");
        if ((token == null || token.isBlank()) && oauthService == null) {
            errors.add("No Google OAuth access token provided and OAuthConnectionService is not available. "
                    + "Set properties.accessToken or wire in the OAuth module.");
        }
        return errors;
    }

    // -------------------------------------------------------------------------
    // Job factory
    // -------------------------------------------------------------------------

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new GoogleDriveCrawlJob(jobId, config, listener);
    }

    // -------------------------------------------------------------------------
    // Main crawl entry point
    // -------------------------------------------------------------------------

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        GoogleDriveCrawlJob job = (GoogleDriveCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();

        // Resolve access token
        job.accessToken = resolveAccessToken(config);
        if (job.accessToken == null || job.accessToken.isBlank()) {
            throw new IllegalStateException(
                    "No Google OAuth access token available. Complete the 'google' OAuth flow or "
                            + "set properties.accessToken in the CrawlConfig.");
        }

        // Read crawler-specific options
        String rootFolderId      = propOrDefault(config, "folderId", DEFAULT_FOLDER_ID);
        boolean includeShared    = Boolean.parseBoolean(propOrDefault(config, "includeSharedDrives", "false"));
        long    maxFileSize      = longPropOrDefault(config, "maxFileSize", DEFAULT_MAX_FILE_BYTES);
        Set<String> mimeFilter   = parseMimeFilter(prop(config, "mimeTypeFilter"));
        long    requestDelayMs   = requestDelayMs(config);
        int     maxDepth         = config.getMaxDepth() > 0 ? config.getMaxDepth() : Integer.MAX_VALUE;

        log.info("[{}] Starting Google Drive crawl: root={}, maxDepth={}, includeShared={}",
                job.getJobId(), rootFolderId, maxDepth, includeShared);

        traverseFolder(job, rootFolderId, null, 0, maxDepth,
                includeShared, maxFileSize, mimeFilter, requestDelayMs);

        log.info("[{}] Google Drive crawl finished: discovered={}", job.getJobId(), job.getDiscoveredCount());
    }

    // -------------------------------------------------------------------------
    // Recursive folder traversal
    // -------------------------------------------------------------------------

    /**
     * Lists all children of {@code folderId}, emits files as {@link CrawlItem}s,
     * and recurses into sub-folders up to {@code maxDepth}.
     */
    private void traverseFolder(
            GoogleDriveCrawlJob job,
            String folderId,
            String parentUrl,
            int depth,
            int maxDepth,
            boolean includeShared,
            long maxFileSize,
            Set<String> mimeFilter,
            long requestDelayMs) throws Exception {

        if (job.shouldStop()) return;

        job.setCurrentDepth(depth);
        String folderUrl = driveFileUrl(folderId);
        job.setCurrentItem(folderUrl);

        String pageToken = null;

        do {
            if (!job.checkPauseAndContinue()) return;
            if (job.shouldStop()) return;

            JsonNode page = listChildren(job, folderId, pageToken, includeShared);
            if (page == null) return; // unrecoverable error already recorded

            JsonNode files = page.path("files");
            if (!files.isArray()) break;

            List<JsonNode> subFolders = new ArrayList<>();

            for (JsonNode entry : files) {
                if (job.shouldStop()) return;

                String id       = entry.path("id").asText(null);
                String name     = entry.path("name").asText("");
                String mimeType = entry.path("mimeType").asText("application/octet-stream");
                boolean trashed = entry.path("trashed").asBoolean(false);

                if (id == null || trashed) continue;

                // Deduplicate multi-parent files within the same run
                if (job.wasVisited(id)) {
                    job.incrementSkipped();
                    job.getListener().onDocumentSkipped(driveFileUrl(id), "already visited");
                    continue;
                }

                if (FOLDER_MIME.equals(mimeType)) {
                    // Queue sub-folder for recursive descent
                    if (depth < maxDepth) {
                        subFolders.add(entry);
                    }
                    continue;
                }

                // Apply MIME-type filter
                if (!mimeFilter.isEmpty() && !mimeFilter.contains(mimeType)
                        && !matchesGoogleWorkspaceExport(mimeType, mimeFilter)) {
                    job.incrementSkipped();
                    job.getListener().onDocumentSkipped(driveFileUrl(id), "filtered by mimeTypeFilter");
                    continue;
                }

                // Incremental: skip unchanged files
                long modifiedMillis = parseModifiedTime(entry.path("modifiedTime").asText(null));
                if (!job.getConfig().isForceRecrawl() && job.isUnchanged(id, modifiedMillis)) {
                    job.incrementSkipped();
                    job.getListener().onDocumentSkipped(driveFileUrl(id), "unchanged since last crawl");
                    continue;
                }

                // Size check (Drive does not always return size for Workspace docs)
                long sizeBytes = entry.path("size").asLong(-1L);
                if (sizeBytes >= 0 && sizeBytes > maxFileSize) {
                    log.info("[{}] SKIP too large ({} bytes): {}", job.getJobId(), sizeBytes, name);
                    job.incrementSkipped();
                    job.getListener().onDocumentSkipped(driveFileUrl(id), "exceeds maxFileSize");
                    continue;
                }

                // Download / export to temp file
                Path tempFile = downloadOrExport(job, id, name, mimeType, requestDelayMs);
                if (tempFile == null) continue; // error already recorded

                // Build CrawlItem
                String fileUrl = driveFileUrl(id);
                String webViewLink = entry.path("webViewLink").asText(null);
                Map<String, Object> meta = buildFileMeta(entry, job.getJobId());

                DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                        .type(SourceType.GDRIVE)
                        .pathOrUrl(tempFile.toAbsolutePath().toString())
                        .sourceId(id)
                        .originalFileName(name)
                        .collectionName(job.getConfig().getCollectionName())
                        .metadata(meta)
                        .build();

                CrawlItem item = CrawlItem.builder()
                        .url(fileUrl)
                        .parentUrl(parentUrl != null ? parentUrl : folderUrl)
                        .depth(depth)
                        .contentType(resolvedContentType(mimeType))
                        .contentLength(sizeBytes >= 0 ? sizeBytes : null)
                        .discoveredAt(Instant.now())
                        .sourceDescriptor(descriptor)
                        .metadata(meta)
                        .build();

                job.markVisited(id, modifiedMillis);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);

                if (job.getDiscoveredCount() % 25 == 0) {
                    job.getListener().onProgress(job.getProgress());
                }

                // Rate limiting between file operations
                sleepQuietly(requestDelayMs);
            }

            pageToken = page.path("nextPageToken").asText(null);

            // Recurse into sub-folders after processing files on this page
            for (JsonNode folder : subFolders) {
                if (job.shouldStop()) return;
                String subId   = folder.path("id").asText(null);
                String subName = folder.path("name").asText("");
                if (subId == null) continue;

                log.debug("[{}] Descending into folder '{}' (id={})", job.getJobId(), subName, subId);
                sleepQuietly(requestDelayMs);
                traverseFolder(job, subId, folderUrl, depth + 1, maxDepth,
                        includeShared, maxFileSize, mimeFilter, requestDelayMs);
            }

        } while (pageToken != null);
    }

    // -------------------------------------------------------------------------
    // Drive API calls
    // -------------------------------------------------------------------------

    /**
     * Lists one page of children for {@code folderId}. Returns {@code null} when an
     * unrecoverable error occurs (the error is recorded on the job before returning).
     */
    private JsonNode listChildren(GoogleDriveCrawlJob job, String folderId,
                                   String pageToken, boolean includeShared) throws Exception {
        StringBuilder sb = new StringBuilder(DRIVE_API_BASE)
                .append("/files?q=")
                .append(URLEncoder.encode("'" + folderId + "' in parents and trashed=false", StandardCharsets.UTF_8))
                .append("&fields=").append(URLEncoder.encode(LIST_FIELDS, StandardCharsets.UTF_8))
                .append("&pageSize=").append(PAGE_SIZE)
                .append("&orderBy=").append(URLEncoder.encode("folder,name", StandardCharsets.UTF_8));

        if (includeShared) {
            sb.append("&includeItemsFromAllDrives=true&supportsAllDrives=true");
        }
        if (pageToken != null) {
            sb.append("&pageToken=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
        }

        String url = sb.toString();
        HttpResponse<String> response = sendGet(job, url);
        if (response == null) return null;

        int status = response.statusCode();
        if (status == 200) {
            return objectMapper.readTree(response.body());
        }
        if (status == 404) {
            log.warn("[{}] Folder not found: {}", job.getJobId(), folderId);
            job.recordError(driveFileUrl(folderId), new IOException("Folder not found: " + folderId));
            return null;
        }
        log.warn("[{}] Drive list API returned HTTP {}: {}", job.getJobId(), status, response.body());
        job.recordError(driveFileUrl(folderId), new IOException("Drive API HTTP " + status));
        return null;
    }

    /**
     * Downloads a regular file or exports a Google Workspace document to a temp file.
     * Returns {@code null} if the operation fails or the MIME type is not exportable.
     */
    private Path downloadOrExport(GoogleDriveCrawlJob job, String fileId, String name,
                                   String mimeType, long requestDelayMs) {
        try {
            sleepQuietly(requestDelayMs);

            String url;
            String extension;
            if (mimeType.startsWith(GOOGLE_APPS_PREFIX)) {
                String exportMime = chooseExportMimeType(mimeType);
                if (exportMime == null) {
                    log.info("[{}] Skipping unsupported Workspace type {} for '{}'",
                            job.getJobId(), mimeType, name);
                    job.incrementSkipped();
                    job.getListener().onDocumentSkipped(driveFileUrl(fileId),
                            "unsupported Google Workspace MIME type: " + mimeType);
                    return null;
                }
                url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                        + "/export?mimeType=" + URLEncoder.encode(exportMime, StandardCharsets.UTF_8);
                extension = extensionForMime(exportMime);
            } else {
                url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                        + "?alt=media";
                extension = extensionFromName(name);
            }

            HttpResponse<InputStream> response = sendGetStream(job, url);
            if (response == null) return null;

            int status = response.statusCode();
            if (status != 200) {
                log.warn("[{}] Download failed for '{}' (HTTP {})", job.getJobId(), name, status);
                job.recordError(driveFileUrl(fileId),
                        new IOException("Drive download HTTP " + status + " for " + name));
                return null;
            }

            String safeName = sanitizeFileName(name) + (extension.isEmpty() ? "" : ("." + extension));
            Path tempFile   = Files.createTempFile("gdrive-" + fileId + "-", "-" + safeName);
            tempFile.toFile().deleteOnExit();
            try (InputStream is = response.body()) {
                Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("[{}] Downloaded '{}' → {}", job.getJobId(), name, tempFile);
            return tempFile;

        } catch (Exception e) {
            log.warn("[{}] Error downloading '{}': {}", job.getJobId(), name, e.getMessage());
            job.recordError(driveFileUrl(fileId), e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    /**
     * Sends a GET request using the job's current access token. Handles a single
     * token-refresh attempt on HTTP 401. Returns {@code null} on terminal failure.
     */
    private HttpResponse<String> sendGet(GoogleDriveCrawlJob job, String url) throws Exception {
        HttpResponse<String> response = executeGet(url, job.accessToken);
        if (response.statusCode() == 401) {
            log.info("[{}] Got 401 — attempting OAuth token refresh", job.getJobId());
            String refreshed = tryRefreshToken(job);
            if (refreshed == null) {
                job.recordError(url, new IOException("OAuth token refresh failed after 401"));
                return null;
            }
            job.accessToken = refreshed;
            response = executeGet(url, job.accessToken);
        }
        return response;
    }

    /**
     * Streaming variant of {@link #sendGet} for file downloads.
     */
    private HttpResponse<InputStream> sendGetStream(GoogleDriveCrawlJob job, String url) throws Exception {
        HttpResponse<InputStream> response = executeGetStream(url, job.accessToken);
        if (response.statusCode() == 401) {
            log.info("[{}] Got 401 on download — attempting OAuth token refresh", job.getJobId());
            String refreshed = tryRefreshToken(job);
            if (refreshed == null) {
                job.recordError(url, new IOException("OAuth token refresh failed after 401"));
                return null;
            }
            job.accessToken = refreshed;
            response = executeGetStream(url, job.accessToken);
        }
        return response;
    }

    private HttpResponse<String> executeGet(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header(HttpConstants.AUTHORIZATION, HttpConstants.BEARER_PREFIX + token)
                .header(HttpConstants.ACCEPT, HttpConstants.APPLICATION_JSON)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<InputStream> executeGetStream(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header(HttpConstants.AUTHORIZATION, HttpConstants.BEARER_PREFIX + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // -------------------------------------------------------------------------
    // Token resolution & refresh
    // -------------------------------------------------------------------------

    private String resolveAccessToken(CrawlConfig config) {
        String token = prop(config, "accessToken");
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (oauthService != null) {
            return oauthService.getValidAccessToken(OAUTH_PROVIDER_ID);
        }
        return null;
    }

    /**
     * Asks OAuthConnectionService for a refreshed token. Returns the new token or
     * {@code null} if refresh is not possible.
     */
    private String tryRefreshToken(GoogleDriveCrawlJob job) {
        if (oauthService == null) {
            log.warn("[{}] Cannot refresh token — OAuthConnectionService not available", job.getJobId());
            return null;
        }
        try {
            oauthService.refreshConnection(OAUTH_PROVIDER_ID);
            return oauthService.getValidAccessToken(OAUTH_PROVIDER_ID);
        } catch (Exception e) {
            log.warn("[{}] Token refresh failed: {}", job.getJobId(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // MIME type mapping
    // -------------------------------------------------------------------------

    /**
     * Maps Google Workspace MIME types to a suitable export format.
     * Returns {@code null} for unsupported types.
     */
    private String chooseExportMimeType(String googleMime) {
        switch (googleMime) {
            case "application/vnd.google-apps.document":
                return "text/plain";
            case "application/vnd.google-apps.spreadsheet":
                return "text/csv";
            case "application/vnd.google-apps.presentation":
                return "text/plain";
            case "application/vnd.google-apps.drawing":
                return "image/svg+xml";
            default:
                return null;
        }
    }

    /**
     * Returns a short file extension for a given MIME type, used when naming
     * the temporary download file. Empty string when unknown.
     */
    private String extensionForMime(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "text/plain":    return "txt";
            case "text/csv":      return "csv";
            case "text/html":     return "html";
            case "image/svg+xml": return "svg";
            case "image/png":     return "png";
            case "application/pdf": return "pdf";
            default:              return "";
        }
    }

    private String extensionFromName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    /**
     * The content type to store on the emitted {@link CrawlItem}. For Workspace
     * types we use the export MIME; otherwise the original Drive MIME type.
     */
    private String resolvedContentType(String driveMime) {
        if (driveMime.startsWith(GOOGLE_APPS_PREFIX)) {
            String exported = chooseExportMimeType(driveMime);
            return exported != null ? exported : driveMime;
        }
        return driveMime;
    }

    /**
     * Checks if a Google Workspace export MIME matches the user's filter set.
     * Allows a filter entry of, e.g., {@code "text/plain"} to match a Google Doc.
     */
    private boolean matchesGoogleWorkspaceExport(String driveMime, Set<String> mimeFilter) {
        String exportMime = chooseExportMimeType(driveMime);
        return exportMime != null && mimeFilter.contains(exportMime);
    }

    // -------------------------------------------------------------------------
    // Configuration helpers
    // -------------------------------------------------------------------------

    private String prop(CrawlConfig config, String key) {
        Object val = config.getProperties().get(key);
        return val != null ? val.toString() : null;
    }

    private String propOrDefault(CrawlConfig config, String key, String defaultValue) {
        String val = prop(config, key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private long longPropOrDefault(CrawlConfig config, String key, long defaultValue) {
        String val = prop(config, key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid numeric property '{}': {}. Using default {}.", key, val, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Resolves the inter-request delay. Prefers the {@code requestDelayMs} property,
     * falls back to {@link CrawlConfig#getRequestDelay()}.
     */
    private long requestDelayMs(CrawlConfig config) {
        String override = prop(config, "requestDelayMs");
        if (override != null && !override.isBlank()) {
            try {
                return Long.parseLong(override.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        Duration d = config.getRequestDelay();
        return d != null ? d.toMillis() : 500L;
    }

    private Set<String> parseMimeFilter(String csv) {
        Set<String> result = new HashSet<>();
        if (csv == null || csv.isBlank()) return result;
        for (String part : Arrays.asList(csv.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Metadata & utility helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> buildFileMeta(JsonNode entry, String jobId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source",           "gdrive");
        meta.put("source_type",      "GDRIVE");
        meta.put("loader",           "Google Drive Crawler");
        meta.put("crawlJobId",       jobId);
        meta.put("gdrive_file_id",   entry.path("id").asText(""));
        meta.put("gdrive_file_name", entry.path("name").asText(""));
        meta.put("gdrive_mime_type", entry.path("mimeType").asText(""));

        if (entry.has("size")) {
            meta.put("gdrive_size_bytes", entry.get("size").asLong());
        }
        if (entry.has("modifiedTime")) {
            meta.put("gdrive_modified_time", entry.get("modifiedTime").asText());
        }
        if (entry.has("webViewLink")) {
            meta.put("gdrive_web_view_link", entry.get("webViewLink").asText());
        }
        return meta;
    }

    private String driveFileUrl(String fileId) {
        return "https://drive.google.com/file/d/" + fileId;
    }

    private long parseModifiedTime(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) return 0L;
        try {
            return Instant.parse(rfc3339).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "file";
        // Strip path separators and other problematic characters for temp file naming
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
