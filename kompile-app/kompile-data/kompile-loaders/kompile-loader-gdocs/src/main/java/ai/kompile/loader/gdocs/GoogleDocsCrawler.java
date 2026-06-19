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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gdocs;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.utils.MapUtils;
import ai.kompile.core.crawler.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Crawler implementation for Google Docs.
 *
 * Features:
 * - Discovers Google Docs files via the Drive API (search, folder, shared drives)
 * - Fetches structured content via the Google Docs API v1
 * - Falls back to Drive plain text export when Docs API is unavailable
 * - Incremental crawl: tracks visited doc IDs and last sync timestamp
 * - Optional comment/revision indexing
 * - Folder-recursive discovery
 *
 * CrawlConfig.properties keys:
 * - accessToken (required): OAuth2 access token with drive.readonly scope
 * - driveQuery: Additional Drive API file query (appended to mimeType filter)
 * - folderId: Restrict crawling to a specific Drive folder (recursive)
 * - daysBack: How many days back to look on initial crawl (default: 90)
 * - includeComments: Whether to index document comments (default: false)
 * - includeRevisions: Whether to index revision history (default: false)
 * - useDocsApi: Use structured Docs API parsing (default: true), false = plain text export
 */
@Slf4j
@Component
public class GoogleDocsCrawler extends AbstractCrawler {

    private final GoogleDocsParser docsParser = new GoogleDocsParser();

    @Override
    public String getId() {
        return "gdocs";
    }

    @Override
    public String getName() {
        return "Google Docs Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls Google Docs via the Drive and Docs REST APIs with OAuth2. "
                + "Supports folder-recursive discovery, structured document parsing, "
                + "comment indexing, and incremental sync.";
    }

    @Override
    public Set<DocumentSourceDescriptor.SourceType> getSupportedSourceTypes() {
        return Set.of(DocumentSourceDescriptor.SourceType.GDOCS);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = config.getProperties();
        if (props == null || props.get("accessToken") == null) {
            errors.add("Google Docs crawler requires 'accessToken' in config properties");
        }
        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new GoogleDocsCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        GoogleDocsCrawlJob job = (GoogleDocsCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties();

        String accessToken = (String) props.get("accessToken");
        String driveQuery = MapUtils.getStringNonBlank(props, "driveQuery", null);
        String folderId = MapUtils.getStringNonBlank(props, "folderId", null);
        int daysBack = MapUtils.getInt(props, "daysBack", 90);
        boolean includeComments = MapUtils.getBoolean(props, "includeComments", false);
        boolean includeRevisions = MapUtils.getBoolean(props, "includeRevisions", false);
        boolean useDocsApi = MapUtils.getBoolean(props, "useDocsApi", true);

        GoogleDocsApiClient apiClient = new GoogleDocsApiClient(accessToken);

        // Get user info for logging
        try {
            JsonNode about = apiClient.getAbout();
            String userEmail = about.path("user").path("emailAddress").asText("unknown");
            log.info("Google Docs crawl starting for user {}", userEmail);
        } catch (Exception e) {
            log.debug("Could not fetch Drive user info: {}", e.getMessage());
        }

        // Build effective query with date filter for incremental crawl
        String effectiveQuery = buildCrawlQuery(driveQuery, daysBack, job.getLastSyncEpoch());

        // Discover documents
        int maxDocs = config.getMaxDocuments() > 0 ? config.getMaxDocuments() : 10000;
        List<JsonNode> docFiles;

        if (folderId != null) {
            docFiles = discoverInFolder(apiClient, folderId, maxDocs);
        } else {
            docFiles = apiClient.listDocFiles(effectiveQuery, maxDocs);
        }

        log.info("Google Docs crawl: discovered {} documents", docFiles.size());

        int processed = 0;
        int skipped = 0;

        for (JsonNode fileMetadata : docFiles) {
            if (job.shouldStop()) {
                log.info("Google Docs crawl stopped after {} processed, {} skipped", processed, skipped);
                break;
            }

            if (!job.checkPauseAndContinue()) break;

            String fileId = fileMetadata.path("id").asText();
            String fileName = fileMetadata.path("name").asText("Untitled");

            // Skip already-visited documents (incremental)
            if (job.isVisited(fileId)) {
                job.incrementSkipped();
                job.getListener().onDocumentSkipped("gdocs://" + fileId, "Already visited");
                skipped++;
                continue;
            }

            try {
                // Parse the document
                Document doc;
                if (useDocsApi) {
                    doc = fetchAndParseStructured(apiClient, fileId, fileMetadata);
                } else {
                    doc = fetchAndParsePlainText(apiClient, fileId, fileMetadata);
                }

                // Emit as CrawlItem
                CrawlItem crawlItem = buildDocCrawlItem(fileId, fileName, doc, fileMetadata, config);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(crawlItem);

                job.markVisited(fileId);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(crawlItem);
                processed++;

                // Index comments as separate CrawlItems
                if (includeComments) {
                    emitComments(apiClient, fileId, fileName, job, config);
                }

                // Index revisions as separate CrawlItems
                if (includeRevisions) {
                    emitRevisions(apiClient, fileId, fileName, job, config);
                }

                // Respect rate limiting
                if (!config.getRequestDelay().isZero() && processed % 10 == 0) {
                    Thread.sleep(config.getRequestDelay().toMillis());
                }

            } catch (Exception e) {
                job.recordError("gdocs://" + fileId, e);
                log.warn("Failed to crawl Google Doc '{}' ({}): {}", fileName, fileId, e.getMessage());
            }

            if (processed % 50 == 0 && processed > 0) {
                log.info("Google Docs crawl progress: {}/{} processed, {} skipped",
                        processed, docFiles.size(), skipped);
            }
        }

        // Update sync marker
        job.setLastSyncEpoch(String.valueOf(Instant.now().getEpochSecond()));

        log.info("Google Docs crawl complete: {} processed, {} skipped, {} errors",
                processed, skipped, job.getProgress().failed());
    }

    // ── Document fetching ─────────────────────────────────────────────────

    private Document fetchAndParseStructured(GoogleDocsApiClient apiClient,
                                              String fileId, JsonNode driveMetadata)
            throws Exception {
        try {
            JsonNode docsJson = apiClient.getDocument(fileId);
            return docsParser.parse(docsJson, driveMetadata);
        } catch (Exception e) {
            log.debug("Docs API failed for {}, falling back to plain text export: {}",
                    fileId, e.getMessage());
            return fetchAndParsePlainText(apiClient, fileId, driveMetadata);
        }
    }

    private Document fetchAndParsePlainText(GoogleDocsApiClient apiClient,
                                             String fileId, JsonNode driveMetadata)
            throws Exception {
        String text = apiClient.exportAsText(fileId);
        return docsParser.parseFromPlainText(text, driveMetadata);
    }

    // ── Folder-recursive discovery ────────────────────────────────────────

    private List<JsonNode> discoverInFolder(GoogleDocsApiClient apiClient,
                                             String folderId, int maxDocs) throws Exception {
        List<JsonNode> allDocs = new ArrayList<>();
        Queue<String> folderQueue = new LinkedList<>();
        Set<String> visitedFolders = new HashSet<>();
        folderQueue.add(folderId);

        while (!folderQueue.isEmpty() && allDocs.size() < maxDocs) {
            String currentFolder = folderQueue.poll();
            if (!visitedFolders.add(currentFolder)) continue;

            List<JsonNode> files = apiClient.listFilesInFolder(currentFolder, maxDocs - allDocs.size());
            for (JsonNode file : files) {
                String mimeType = file.path("mimeType").asText("");
                if ("application/vnd.google-apps.folder".equals(mimeType)) {
                    folderQueue.add(file.path("id").asText());
                } else if ("application/vnd.google-apps.document".equals(mimeType)) {
                    allDocs.add(file);
                    if (allDocs.size() >= maxDocs) break;
                }
            }
        }

        return allDocs;
    }

    // ── CrawlItem construction ────────────────────────────────────────────

    private CrawlItem buildDocCrawlItem(String fileId, String fileName,
                                         Document doc, JsonNode driveMetadata,
                                         CrawlConfig config) {
        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
        metadata.put("gdocs.crawlTimestamp", Instant.now().toString());
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "Google Doc");

        String parentFolder = null;
        JsonNode parents = driveMetadata.get("parents");
        if (parents != null && parents.isArray() && !parents.isEmpty()) {
            parentFolder = parents.get(0).asText();
        }

        return CrawlItem.builder()
                .url("gdocs://documents/" + fileId)
                .parentUrl(parentFolder != null ? "gdocs://folders/" + parentFolder : null)
                .depth(0)
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(DocumentSourceDescriptor.SourceType.GDOCS)
                        .sourceId(fileId)
                        .pathOrUrl("gdocs://documents/" + fileId)
                        .collectionName(config.getCollectionName())
                        .metadata(metadata)
                        .build())
                .metadata(metadata)
                .discoveredAt(Instant.now())
                .contentType("application/vnd.google-apps.document")
                .build();
    }

    // ── Comments ──────────────────────────────────────────────────────────

    private void emitComments(GoogleDocsApiClient apiClient, String fileId, String fileName,
                               GoogleDocsCrawlJob job, CrawlConfig config) {
        try {
            List<JsonNode> comments = apiClient.listComments(fileId, 100);
            for (JsonNode comment : comments) {
                if (job.shouldStop()) break;

                String commentId = comment.path("id").asText();
                String content = comment.path("content").asText("");
                String author = comment.path("author").path("displayName").asText("Unknown");
                String authorEmail = comment.path("author").path("emailAddress").asText(null);
                String createdTime = comment.path("createdTime").asText("");
                boolean resolved = comment.path("resolved").asBoolean(false);

                StringBuilder text = new StringBuilder();
                text.append("Comment on: ").append(fileName).append("\n");
                text.append("By: ").append(author).append("\n");
                text.append("Date: ").append(createdTime).append("\n");
                text.append("Status: ").append(resolved ? "Resolved" : "Open").append("\n\n");
                text.append(content);

                // Include replies and capture reply metadata for graph extraction
                JsonNode replies = comment.get("replies");
                List<Map<String, String>> replyMeta = new ArrayList<>();
                if (replies != null && replies.isArray()) {
                    for (JsonNode reply : replies) {
                        String replyId = reply.path("id").asText(null);
                        String replyAuthor = reply.path("author").path("displayName").asText("Unknown");
                        String replyAuthorEmail = reply.path("author").path("emailAddress").asText(null);
                        String replyContent = reply.path("content").asText("");
                        text.append("\n\nReply by ").append(replyAuthor).append(":\n").append(replyContent);
                        Map<String, String> rm = new LinkedHashMap<>();
                        if (replyId != null) rm.put("replyId", replyId);
                        rm.put("author", replyAuthor);
                        if (replyAuthorEmail != null && !replyAuthorEmail.isBlank()) {
                            rm.put("authorEmail", replyAuthorEmail);
                        }
                        replyMeta.add(rm);
                    }
                }

                Map<String, Object> meta = new HashMap<>();
                meta.put(GraphConstants.META_SOURCE, "gdocs://documents/" + fileId + "/comments/" + commentId);
                meta.put(GraphConstants.META_SOURCE_PATH, "gdocs://documents/" + fileId + "/comments/" + commentId);
                meta.put(GraphConstants.META_SOURCE_TYPE, "gdocs_comment");
                meta.put(GraphConstants.META_DOCUMENT_TYPE, "gdocs_comment");
                meta.put(GraphConstants.META_FILE_NAME, fileName != null ? fileName : fileId);
                meta.put(GraphConstants.META_LOADER, "Google Docs Crawler");
                meta.put("gdocs.documentId", fileId);
                meta.put("gdocs.commentId", commentId);
                meta.put("gdocs.commentAuthor", author);
                if (authorEmail != null && !authorEmail.isBlank()) {
                    meta.put("gdocs.commentAuthorEmail", authorEmail);
                }
                meta.put("gdocs.commentResolved", resolved);
                if (!content.isBlank()) {
                    meta.put("gdocs.commentContent", content);
                }
                if (!replyMeta.isEmpty()) {
                    meta.put("gdocs.commentReplies", replyMeta);
                }

                CrawlItem commentItem = CrawlItem.builder()
                        .url("gdocs://documents/" + fileId + "/comments/" + commentId)
                        .parentUrl("gdocs://documents/" + fileId)
                        .depth(1)
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(DocumentSourceDescriptor.SourceType.GDOCS)
                                .sourceId(commentId)
                                .pathOrUrl("gdocs://documents/" + fileId + "/comments/" + commentId)
                                .collectionName(config.getCollectionName())
                                .metadata(meta)
                                .build())
                        .metadata(meta)
                        .discoveredAt(Instant.now())
                        .contentType("text/plain")
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(commentItem);
                job.getListener().onDocumentProcessed(commentItem);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch comments for doc {}: {}", fileId, e.getMessage());
        }
    }

    // ── Revisions ─────────────────────────────────────────────────────────

    private void emitRevisions(GoogleDocsApiClient apiClient, String fileId, String fileName,
                                GoogleDocsCrawlJob job, CrawlConfig config) {
        try {
            List<JsonNode> revisions = apiClient.listRevisions(fileId, 50);
            if (revisions.size() <= 1) return; // Skip if only one revision (the current one)

            String previousRevisionId = null;
            for (JsonNode revision : revisions) {
                if (job.shouldStop()) break;

                String revisionId = revision.path("id").asText();
                String modifiedTime = revision.path("modifiedTime").asText("");
                String modifier = revision.path("lastModifyingUser").path("displayName").asText("Unknown");
                String modifierEmail = revision.path("lastModifyingUser").path("emailAddress").asText(null);

                Map<String, Object> meta = new HashMap<>();
                meta.put(GraphConstants.META_SOURCE, "gdocs://documents/" + fileId + "/revisions/" + revisionId);
                meta.put(GraphConstants.META_SOURCE_PATH, "gdocs://documents/" + fileId + "/revisions/" + revisionId);
                meta.put(GraphConstants.META_SOURCE_TYPE, "gdocs_revision");
                meta.put(GraphConstants.META_DOCUMENT_TYPE, "gdocs_revision");
                meta.put(GraphConstants.META_FILE_NAME, fileName != null ? fileName : fileId);
                meta.put(GraphConstants.META_LOADER, "Google Docs Crawler");
                meta.put("gdocs.documentId", fileId);
                meta.put("gdocs.revisionId", revisionId);
                meta.put("gdocs.revisionModifiedTime", modifiedTime);
                meta.put("gdocs.revisionModifier", modifier);
                if (modifierEmail != null && !modifierEmail.isBlank()) {
                    meta.put("gdocs.revisionModifierEmail", modifierEmail);
                }
                if (previousRevisionId != null) {
                    meta.put("gdocs.previousRevisionId", previousRevisionId);
                }

                CrawlItem revisionItem = CrawlItem.builder()
                        .url("gdocs://documents/" + fileId + "/revisions/" + revisionId)
                        .parentUrl("gdocs://documents/" + fileId)
                        .depth(1)
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(DocumentSourceDescriptor.SourceType.GDOCS)
                                .sourceId(revisionId)
                                .pathOrUrl("gdocs://documents/" + fileId + "/revisions/" + revisionId)
                                .collectionName(config.getCollectionName())
                                .metadata(meta)
                                .build())
                        .metadata(meta)
                        .discoveredAt(Instant.now())
                        .contentType("text/plain")
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(revisionItem);
                job.getListener().onDocumentProcessed(revisionItem);
                previousRevisionId = revisionId;
            }
        } catch (Exception e) {
            log.debug("Failed to fetch revisions for doc {}: {}", fileId, e.getMessage());
        }
    }

    // ── Query building ────────────────────────────────────────────────────

    private String buildCrawlQuery(String baseQuery, int daysBack, String lastSyncEpoch) {
        StringBuilder parts = new StringBuilder();

        if (baseQuery != null && !baseQuery.isBlank()) {
            parts.append(baseQuery);
        }

        // Date filter: use last sync epoch if available, else daysBack
        String modifiedAfter;
        if (lastSyncEpoch != null) {
            modifiedAfter = Instant.ofEpochSecond(Long.parseLong(lastSyncEpoch))
                    .toString().replace("Z", "");
        } else {
            modifiedAfter = Instant.now().minus(daysBack, ChronoUnit.DAYS)
                    .toString().replace("Z", "");
        }

        String dateFilter = "modifiedTime > '" + modifiedAfter + "'";
        if (parts.length() > 0) {
            parts.append(" and ");
        }
        parts.append(dateFilter);

        return parts.toString();
    }

}
