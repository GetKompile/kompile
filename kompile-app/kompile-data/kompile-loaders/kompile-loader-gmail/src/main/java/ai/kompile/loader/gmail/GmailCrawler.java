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

package ai.kompile.loader.gmail;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Crawler implementation for Gmail inboxes.
 *
 * Features:
 * - Full crawl: fetches all messages matching a query within a date range
 * - Incremental crawl: uses Gmail History API or date-based filtering to only fetch new messages
 * - Thread-aware: emits messages within their thread context
 * - Attachment support: optionally downloads and indexes text-based attachments
 * - Label filtering: supports Gmail label-based queries
 * - Rate limiting: respects Gmail API quotas with automatic retry
 *
 * CrawlConfig.properties keys:
 * - accessToken (required): OAuth2 access token
 * - gmailQuery: Gmail search query (default: "in:inbox")
 * - daysBack: How many days back to crawl on initial crawl (default: 30)
 * - includeAttachments: Whether to emit attachment CrawlItems (default: true)
 * - labelFilter: Comma-separated list of label names to include
 * - excludeLabels: Comma-separated list of label names to exclude
 */
@Slf4j
@Component
public class GmailCrawler extends AbstractCrawler {

    private final GmailMessageParser messageParser = new GmailMessageParser();

    @Override
    public String getId() {
        return "gmail";
    }

    @Override
    public String getName() {
        return "Gmail Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls a Gmail inbox via the Gmail REST API with OAuth2. " +
                "Supports incremental sync, label filtering, thread grouping, and attachment indexing.";
    }

    @Override
    public Set<DocumentSourceDescriptor.SourceType> getSupportedSourceTypes() {
        return Set.of(DocumentSourceDescriptor.SourceType.GMAIL);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = config.getProperties();
        if (props == null || props.get("accessToken") == null) {
            errors.add("Gmail crawler requires 'accessToken' in config properties");
        }
        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new GmailCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        GmailCrawlJob job = (GmailCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties();

        String accessToken = (String) props.get("accessToken");
        String gmailQuery = MapUtils.getStringNonBlank(props, "gmailQuery", "in:inbox");
        int daysBack = MapUtils.getInt(props, "daysBack", 30);
        boolean includeAttachments = MapUtils.getBoolean(props, "includeAttachments", true);
        String labelFilter = MapUtils.getStringNonBlank(props, "labelFilter", null);
        String excludeLabels = MapUtils.getStringNonBlank(props, "excludeLabels", null);

        GmailApiClient apiClient = new GmailApiClient(accessToken);

        // Get the user's profile for the history ID baseline
        JsonNode profile = apiClient.getProfile();
        String currentHistoryId = profile.has("historyId") ? profile.get("historyId").asText() : null;
        String userEmail = profile.has("emailAddress") ? profile.get("emailAddress").asText() : "unknown";
        log.info("Gmail crawl starting for {} (historyId={})", userEmail, currentHistoryId);

        // Build the effective query
        String effectiveQuery = buildCrawlQuery(gmailQuery, daysBack, labelFilter,
                excludeLabels, job.getLastSyncEpoch());

        log.info("Gmail crawl query: {}", effectiveQuery);

        // List messages
        int maxDocs = config.getMaxDocuments() > 0 ? config.getMaxDocuments() : 10000;
        List<String> messageIds = apiClient.listMessageIds(effectiveQuery, maxDocs);
        log.info("Gmail crawl: found {} messages matching query", messageIds.size());

        int processed = 0;
        int skipped = 0;

        for (String msgId : messageIds) {
            if (job.shouldStop()) {
                log.info("Gmail crawl stopped after processing {} messages ({} skipped)", processed, skipped);
                break;
            }

            if (!job.checkPauseAndContinue()) {
                break;
            }

            // Skip already-visited messages (incremental)
            if (job.isVisited(msgId)) {
                job.incrementSkipped();
                job.getListener().onDocumentSkipped("gmail://" + msgId, "Already visited in previous crawl");
                skipped++;
                continue;
            }

            try {
                JsonNode messageJson = apiClient.getMessage(msgId);
                Document doc = messageParser.parse(messageJson);

                // Build the CrawlItem for the message
                CrawlItem messageCrawlItem = buildMessageCrawlItem(msgId, messageJson, doc, config);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(messageCrawlItem);

                job.markVisited(msgId);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(messageCrawlItem);
                processed++;

                // Emit attachments as separate CrawlItems
                if (includeAttachments) {
                    emitAttachments(apiClient, msgId, messageJson, job, config);
                }

                // Respect rate limiting
                if (!config.getRequestDelay().isZero() && processed % 10 == 0) {
                    Thread.sleep(config.getRequestDelay().toMillis());
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("Gmail crawl interrupted at message {}", msgId);
                break;
            } catch (Exception e) {
                job.recordError("gmail://" + msgId, e);
                log.warn("Failed to crawl Gmail message {}: {}", msgId, e.getMessage());
            }

            // Log progress periodically
            if (processed % 100 == 0 && processed > 0) {
                log.info("Gmail crawl progress: {}/{} processed, {} skipped",
                        processed, messageIds.size(), skipped);
            }
        }

        // Update sync markers for next incremental crawl
        if (currentHistoryId != null) {
            job.setLastHistoryId(currentHistoryId);
        }
        job.setLastSyncEpoch(String.valueOf(Instant.now().getEpochSecond()));

        log.info("Gmail crawl complete: {} processed, {} skipped, {} errors",
                processed, skipped, job.getProgress().failed());
    }

    private CrawlItem buildMessageCrawlItem(String messageId, JsonNode messageJson,
                                              Document doc, CrawlConfig config) {
        String threadId = messageJson.get("threadId").asText();

        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
        metadata.put("gmail.crawlTimestamp", Instant.now().toString());
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "email");

        return CrawlItem.builder()
                .url("gmail://messages/" + messageId)
                .parentUrl("gmail://threads/" + threadId)
                .depth(0)
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(DocumentSourceDescriptor.SourceType.GMAIL)
                        .sourceId(messageId)
                        .pathOrUrl("gmail://messages/" + messageId)
                        .collectionName(config.getCollectionName())
                        .metadata(metadata)
                        .build())
                .metadata(metadata)
                .discoveredAt(Instant.now())
                .contentType("message/rfc822")
                .build();
    }

    private void emitAttachments(GmailApiClient apiClient, String messageId,
                                  JsonNode messageJson, GmailCrawlJob job,
                                  CrawlConfig config) {
        JsonNode payload = messageJson.get("payload");
        List<Map<String, Object>> attachments = messageParser.extractAttachmentMetadata(payload);

        for (Map<String, Object> att : attachments) {
            if (job.shouldStop()) break;

            String filename = (String) att.get("filename");
            String mimeType = (String) att.get("mimeType");
            String attachmentId = (String) att.get("attachmentId");

            if (attachmentId == null) continue;

            String attUrl = "gmail://messages/" + messageId + "/attachments/" + attachmentId;

            // For text-based attachments, download content and emit as a document
            if (mimeType != null && isTextMimeType(mimeType)) {
                try {
                    byte[] data = apiClient.getAttachment(messageId, attachmentId);
                    Path tempFile = Files.createTempFile("gmail-att-", "-" + sanitizeFilename(filename));
                    Files.write(tempFile, data);

                    Map<String, Object> attMeta = new HashMap<>();
                    attMeta.put(GraphConstants.META_SOURCE, attUrl);
                    attMeta.put(GraphConstants.META_SOURCE_PATH, attUrl);
                    attMeta.put(GraphConstants.META_SOURCE_TYPE, "gmail_attachment");
                    attMeta.put(GraphConstants.META_DOCUMENT_TYPE, "email_attachment");
                    attMeta.put(GraphConstants.META_FILE_NAME, filename != null ? filename : "Attachment " + attachmentId);
                    attMeta.put(GraphConstants.META_LOADER, "Gmail Crawler");
                    attMeta.put("gmail.messageId", messageId);
                    attMeta.put("gmail.attachment.filename", filename);
                    attMeta.put("gmail.attachment.mimeType", mimeType);
                    if (att.containsKey("size")) {
                        attMeta.put("gmail.attachment.size", att.get("size"));
                    }
                    if (attachmentId != null) {
                        attMeta.put("gmail.attachment.id", attachmentId);
                    }

                    CrawlItem attItem = CrawlItem.builder()
                            .url(attUrl)
                            .parentUrl("gmail://messages/" + messageId)
                            .depth(1)
                            .sourceDescriptor(DocumentSourceDescriptor.builder()
                                    .type(DocumentSourceDescriptor.SourceType.FILE)
                                    .pathOrUrl(tempFile.toString())
                                    .originalFileName(filename)
                                    .sourceId(attachmentId)
                                    .collectionName(config.getCollectionName())
                                    .metadata(attMeta)
                                    .build())
                            .metadata(attMeta)
                            .discoveredAt(Instant.now())
                            .contentType(mimeType)
                            .contentLength(att.containsKey("size") ? ((Number) att.get("size")).longValue() : null)
                            .build();

                    job.incrementDiscovered();
                    job.getListener().onDocumentDiscovered(attItem);
                    job.getListener().onDocumentProcessed(attItem);

                } catch (Exception e) {
                    log.debug("Failed to download attachment {} from message {}: {}",
                            filename, messageId, e.getMessage());
                }
            }
        }
    }

    private String buildCrawlQuery(String baseQuery, int daysBack,
                                    String labelFilter, String excludeLabels,
                                    String lastSyncEpoch) {
        StringBuilder query = new StringBuilder();

        if (baseQuery != null && !baseQuery.isBlank()) {
            query.append(baseQuery);
        }

        // Add label filters
        if (labelFilter != null && !labelFilter.isBlank()) {
            for (String label : labelFilter.split(",")) {
                String trimmed = label.trim();
                if (!trimmed.isEmpty()) {
                    query.append(" label:").append(trimmed);
                }
            }
        }
        if (excludeLabels != null && !excludeLabels.isBlank()) {
            for (String label : excludeLabels.split(",")) {
                String trimmed = label.trim();
                if (!trimmed.isEmpty()) {
                    query.append(" -label:").append(trimmed);
                }
            }
        }

        // Date filter: use last sync epoch if available (incremental), else daysBack
        if (!queryHasDateFilter(query.toString())) {
            long epochSeconds;
            if (lastSyncEpoch != null) {
                epochSeconds = Long.parseLong(lastSyncEpoch);
            } else {
                epochSeconds = Instant.now().minus(daysBack, ChronoUnit.DAYS).getEpochSecond();
            }
            query.append(" after:").append(epochSeconds);
        }

        return query.toString().trim();
    }

    private boolean queryHasDateFilter(String query) {
        return query.contains("after:") || query.contains("before:")
                || query.contains("newer_than:") || query.contains("older_than:");
    }

    private boolean isTextMimeType(String mimeType) {
        return mimeType.startsWith("text/")
                || mimeType.contains("json")
                || mimeType.contains("xml")
                || mimeType.contains("csv")
                || mimeType.equals("application/javascript")
                || mimeType.equals("application/x-yaml")
                || mimeType.equals("application/x-sh");
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "attachment";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

}
