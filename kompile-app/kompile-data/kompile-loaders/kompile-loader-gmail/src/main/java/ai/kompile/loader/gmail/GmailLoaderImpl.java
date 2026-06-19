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
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DocumentLoader implementation for Gmail.
 * Loads emails from a Gmail account using the Gmail REST API v1 with OAuth2.
 *
 * Supports configuration via DocumentSourceDescriptor metadata:
 * - accessToken (required): OAuth2 access token with gmail.readonly scope
 * - gmailQuery: Gmail search query (e.g., "label:inbox", "from:user@example.com")
 * - maxMessages: Maximum number of messages to load (default: 500)
 * - daysBack: Only load messages from the last N days (default: 30)
 * - includeAttachments: Whether to include attachment metadata (default: true)
 * - threadMode: If true, groups messages by thread (default: false)
 */
@Slf4j
@Component
public class GmailLoaderImpl implements DocumentLoader {

    private final GmailMessageParser messageParser = new GmailMessageParser();

    @Override
    public String getName() {
        return "Gmail Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.GMAIL;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor) throws Exception {
        return load(descriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        Map<String, Object> meta = descriptor.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException("Gmail loader requires metadata with at least 'accessToken'");
        }

        String accessToken = (String) meta.get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Gmail loader requires 'accessToken' in metadata");
        }

        String gmailQuery = (String) meta.getOrDefault("gmailQuery", "");
        int maxMessages = MapUtils.getInt(meta, "maxMessages", 500);
        int daysBack = MapUtils.getInt(meta, "daysBack", 30);
        boolean includeAttachments = MapUtils.getBoolean(meta, "includeAttachments", true);
        boolean threadMode = MapUtils.getBoolean(meta, "threadMode", false);

        // Build effective query with date filter
        String effectiveQuery = buildEffectiveQuery(gmailQuery, daysBack);

        GmailApiClient apiClient = new GmailApiClient(accessToken);
        List<Document> documents = new ArrayList<>();

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("gmail", 5,
                    "listing", "Listing Gmail messages...", Map.of()));
        }

        if (threadMode) {
            documents = loadByThread(apiClient, effectiveQuery, maxMessages, includeAttachments, progressCallback);
        } else {
            documents = loadByMessage(apiClient, effectiveQuery, maxMessages, includeAttachments, progressCallback);
        }

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("gmail", 95,
                    "complete", "Loaded " + documents.size() + " documents from Gmail", Map.of(
                    "totalDocuments", documents.size())));
        }

        log.info("Gmail loader finished: loaded {} documents", documents.size());
        return documents;
    }

    private List<Document> loadByMessage(GmailApiClient apiClient, String query,
                                          int maxMessages, boolean includeAttachments,
                                          Consumer<LoaderProgress> progressCallback)
            throws Exception {
        List<String> messageIds = apiClient.listMessageIds(query, maxMessages);
        log.info("Gmail: found {} messages matching query", messageIds.size());

        List<Document> documents = new ArrayList<>();
        int total = messageIds.size();

        for (int i = 0; i < total; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Gmail loader interrupted after loading {} of {} messages", i, total);
                break;
            }

            String msgId = messageIds.get(i);
            try {
                JsonNode messageJson = apiClient.getMessage(msgId);
                Document doc = messageParser.parse(messageJson);
                documents.add(doc);

                if (includeAttachments) {
                    List<Document> attachmentDocs = loadAttachmentDocuments(apiClient, msgId, messageJson);
                    documents.addAll(attachmentDocs);
                }
            } catch (Exception e) {
                log.warn("Failed to load Gmail message {}: {}", msgId, e.getMessage());
            }

            if (progressCallback != null && (i % 50 == 0 || i == total - 1)) {
                int pct = 10 + (int) ((80.0 * i) / total);
                progressCallback.accept(new LoaderProgress("gmail", pct,
                        "loading", "Loading message " + (i + 1) + " of " + total,
                        Map.of("loaded", i + 1, "total", total)));
            }
        }

        return documents;
    }

    private List<Document> loadByThread(GmailApiClient apiClient, String query,
                                         int maxMessages, boolean includeAttachments,
                                         Consumer<LoaderProgress> progressCallback)
            throws Exception {
        List<JsonNode> threadSummaries = apiClient.listThreads(query, maxMessages);
        log.info("Gmail: found {} threads matching query", threadSummaries.size());

        List<Document> documents = new ArrayList<>();
        int total = threadSummaries.size();

        for (int i = 0; i < total; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Gmail loader interrupted after loading {} of {} threads", i, total);
                break;
            }

            String threadId = threadSummaries.get(i).get("id").asText();
            try {
                JsonNode threadJson = apiClient.getThread(threadId);
                JsonNode messages = threadJson.get("messages");
                if (messages != null && messages.isArray()) {
                    for (JsonNode messageJson : messages) {
                        Document doc = messageParser.parse(messageJson);
                        doc.getMetadata().put("gmail.threadPosition",
                                documents.stream()
                                        .filter(d -> threadId.equals(d.getMetadata().get("gmail.threadId")))
                                        .count());
                        documents.add(doc);

                        if (includeAttachments) {
                            String msgId = messageJson.get("id").asText();
                            List<Document> attachmentDocs = loadAttachmentDocuments(apiClient, msgId, messageJson);
                            documents.addAll(attachmentDocs);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load Gmail thread {}: {}", threadId, e.getMessage());
            }

            if (progressCallback != null && (i % 20 == 0 || i == total - 1)) {
                int pct = 10 + (int) ((80.0 * i) / total);
                progressCallback.accept(new LoaderProgress("gmail", pct,
                        "loading", "Loading thread " + (i + 1) + " of " + total,
                        Map.of("loaded", i + 1, "total", total)));
            }
        }

        return documents;
    }

    /**
     * Creates placeholder documents for attachments (content describes the attachment;
     * binary content can be fetched on-demand via the attachment ID).
     */
    private List<Document> loadAttachmentDocuments(GmailApiClient apiClient, String messageId,
                                                    JsonNode messageJson) {
        List<Document> attachmentDocs = new ArrayList<>();
        JsonNode payload = messageJson.get("payload");
        List<Map<String, Object>> attachmentMeta = messageParser.extractAttachmentMetadata(payload);

        for (Map<String, Object> att : attachmentMeta) {
            String filename = (String) att.get("filename");
            String mimeType = (String) att.get("mimeType");

            String content = "Attachment: " + filename + "\nType: " + mimeType;
            if (att.containsKey("size")) {
                content += "\nSize: " + att.get("size") + " bytes";
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put(GraphConstants.META_SOURCE, "gmail://messages/" + messageId + "/attachments/" + filename);
            meta.put(GraphConstants.META_SOURCE_PATH, "gmail://messages/" + messageId + "/attachments/" + filename);
            meta.put(GraphConstants.META_SOURCE_TYPE, "gmail_attachment");
            meta.put(GraphConstants.META_FILE_NAME, filename);
            meta.put(GraphConstants.META_DOCUMENT_TYPE, "email_attachment");
            meta.put(GraphConstants.META_LOADER, getName());
            meta.put("gmail.messageId", messageId);
            meta.put("gmail.attachment.filename", filename);
            meta.put("gmail.attachment.mimeType", mimeType);
            if (att.containsKey("attachmentId")) {
                meta.put("gmail.attachment.id", att.get("attachmentId"));
            }
            if (att.containsKey("size")) {
                meta.put("gmail.attachment.size", att.get("size"));
            }

            // For text-based attachments, try to fetch and include the content
            if (mimeType != null && (mimeType.startsWith("text/") || mimeType.contains("json")
                    || mimeType.contains("xml") || mimeType.contains("csv"))) {
                String attachmentId = (String) att.get("attachmentId");
                if (attachmentId != null) {
                    try {
                        byte[] data = apiClient.getAttachment(messageId, attachmentId);
                        content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        meta.put("gmail.attachment.contentLoaded", true);
                    } catch (Exception e) {
                        log.debug("Could not load text attachment {}: {}", filename, e.getMessage());
                    }
                }
            }

            attachmentDocs.add(new Document(content, meta));
        }

        return attachmentDocs;
    }

    private String buildEffectiveQuery(String userQuery, int daysBack) {
        long epochSeconds = Instant.now().minus(daysBack, ChronoUnit.DAYS).getEpochSecond();
        String dateFilter = "after:" + epochSeconds;

        if (userQuery == null || userQuery.isBlank()) {
            return dateFilter;
        }

        // Don't add date filter if user already specified one
        if (userQuery.contains("after:") || userQuery.contains("before:")
                || userQuery.contains("newer_than:") || userQuery.contains("older_than:")) {
            return userQuery;
        }

        return userQuery + " " + dateFilter;
    }

}
