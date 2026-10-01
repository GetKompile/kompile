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
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /**
     * Default per-attachment size cap (bytes) above which an attachment is skipped instead of
     * saved, matching the shared "attachmentDirectory" contract (C4) used by every loader.
     */
    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 26_214_400L;

    /** Shared cap on saved attachment file names, matching DiscordAttachmentStorage exactly. */
    private static final int MAX_ATTACHMENT_FILENAME_LENGTH = 120;

    /** Extension fallback when an attachment's original name has none. */
    private static final Map<String, String> ATTACHMENT_EXTENSION_BY_CONTENT_TYPE = Map.ofEntries(
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/jpg", ".jpg"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/svg+xml", ".svg"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/html", ".html"),
            Map.entry("application/json", ".json"),
            Map.entry("application/zip", ".zip"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"));

    private final GmailMessageParser messageParser = new GmailMessageParser();
    private final OAuthConnectionService oauthService;

    public GmailLoaderImpl() {
        this(null);
    }

    @Autowired
    public GmailLoaderImpl(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

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
        Map<String, Object> meta = descriptor.getMetadata() == null
                ? Map.of() : descriptor.getMetadata();

        String accessToken = (String) meta.get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = oauthService == null ? null : oauthService.getValidAccessToken("google");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Gmail requires a connected Google OAuth account or metadata.accessToken");
        }

        GmailApiClient apiClient = new GmailApiClient(accessToken);
        return load(apiClient, meta, progressCallback);
    }

    /**
     * Package-private entry point taking an already-constructed {@link GmailApiClient}, so tests
     * can inject one pointed at an in-process fake HTTP server (per the shared "in-process fake
     * servers only" test rule) instead of exercising the real Gmail API. {@link #load} resolves
     * the access token and constructs the real client, then delegates here.
     */
    List<Document> load(GmailApiClient apiClient, Map<String, Object> meta,
                         Consumer<LoaderProgress> progressCallback) throws Exception {
        String gmailQuery = (String) meta.getOrDefault("gmailQuery", "");
        int maxMessages = MapUtils.getInt(meta, "maxMessages", 500);
        int daysBack = MapUtils.getInt(meta, "daysBack", 30);
        boolean includeAttachments = MapUtils.getBoolean(meta, "includeAttachments", true);
        boolean threadMode = MapUtils.getBoolean(meta, "threadMode", false);
        String attachmentDirectory = (String) meta.get("attachmentDirectory");
        long maxAttachmentBytes = MapUtils.getLong(meta, "maxAttachmentBytes", DEFAULT_MAX_ATTACHMENT_BYTES);

        // Shared "since" contract (C3): the effective lower bound is the LATER of the explicit
        // "since" metadata and the daysBack-derived cutoff, so a caller-supplied since never gets
        // silently widened by the (always-present) daysBack default.
        Instant sinceMeta = parseSinceInstant(meta.get("since"));
        Instant daysBackInstant = Instant.now().minus(daysBack, ChronoUnit.DAYS);
        Instant effectiveSince = sinceMeta != null && sinceMeta.isAfter(daysBackInstant) ? sinceMeta : daysBackInstant;

        // Build effective query with date filter
        String effectiveQuery = buildEffectiveQuery(gmailQuery, effectiveSince);

        List<Document> documents = new ArrayList<>();

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("gmail", 5,
                    "listing", "Listing Gmail messages...", Map.of()));
        }

        if (threadMode) {
            documents = loadByThread(apiClient, effectiveQuery, maxMessages, includeAttachments,
                    effectiveSince, attachmentDirectory, maxAttachmentBytes, progressCallback);
        } else {
            documents = loadByMessage(apiClient, effectiveQuery, maxMessages, includeAttachments,
                    effectiveSince, attachmentDirectory, maxAttachmentBytes, progressCallback);
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
                                          Instant effectiveSince, String attachmentDirectory,
                                          long maxAttachmentBytes,
                                          Consumer<LoaderProgress> progressCallback)
            throws Exception {
        // Gmail's messages.list is already newest-first and this stops paginating the instant
        // maxResults is reached, so the shared "total cap enforced while fetching" contract (C2)
        // is satisfied by the API client itself — no post-hoc trimming here.
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
                if (!passesSinceFilter(messageJson, effectiveSince)) {
                    continue;
                }
                Document doc = messageParser.parse(messageJson);
                documents.add(doc);

                if (includeAttachments) {
                    List<Document> attachmentDocs = processAttachments(apiClient, msgId, messageJson, doc,
                            attachmentDirectory, maxAttachmentBytes);
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
                                         Instant effectiveSince, String attachmentDirectory,
                                         long maxAttachmentBytes,
                                         Consumer<LoaderProgress> progressCallback)
            throws Exception {
        // listThreads(query, maxMessages) is a candidate pool of up to maxMessages threads
        // (newest-first, same as message mode) — since a thread can contribute more than one
        // message, the actual per-message cap below is what enforces the shared "total cap
        // across ... threads/replies, enforced while fetching" contract (C2), not this count.
        List<JsonNode> threadSummaries = apiClient.listThreads(query, maxMessages);
        log.info("Gmail: found {} threads matching query", threadSummaries.size());

        List<Document> documents = new ArrayList<>();
        int total = threadSummaries.size();
        int messagesEmitted = 0;

        for (int i = 0; i < total; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Gmail loader interrupted after loading {} of {} threads", i, total);
                break;
            }
            if (messagesEmitted >= maxMessages) {
                log.info("Gmail loader reached maxMessages={} across threads; stopping", maxMessages);
                break;
            }

            String threadId = threadSummaries.get(i).get("id").asText();
            try {
                JsonNode threadJson = apiClient.getThread(threadId);
                JsonNode messages = threadJson.get("messages");
                if (messages != null && messages.isArray()) {
                    for (JsonNode messageJson : messages) {
                        if (messagesEmitted >= maxMessages) {
                            break;
                        }
                        if (!passesSinceFilter(messageJson, effectiveSince)) {
                            continue;
                        }
                        Document doc = messageParser.parse(messageJson);
                        doc.getMetadata().put("gmail.threadPosition",
                                documents.stream()
                                        .filter(d -> threadId.equals(d.getMetadata().get("gmail.threadId")))
                                        .count());
                        documents.add(doc);
                        messagesEmitted++;

                        if (includeAttachments) {
                            String msgId = messageJson.get("id").asText();
                            List<Document> attachmentDocs = processAttachments(apiClient, msgId, messageJson, doc,
                                    attachmentDirectory, maxAttachmentBytes);
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
     * Dispatches attachment handling per the shared "attachmentDirectory" contract (C4): when
     * the descriptor carries an attachmentDirectory, attachment bytes are written to
     * {@code <attachmentDirectory>/<messageKey>/<fileName>} and {@code parentDoc}'s metadata
     * gets an "attachments" list describing what was saved/skipped, with NO separate Documents
     * produced. Otherwise (server-ingestion path), attachments continue to be emitted as
     * separate Documents exactly as before, but now correctly tagged with source/source_path/
     * parent_source_path.
     */
    private List<Document> processAttachments(GmailApiClient apiClient, String messageId,
                                               JsonNode messageJson, Document parentDoc,
                                               String attachmentDirectory, long maxAttachmentBytes) {
        JsonNode payload = messageJson.get("payload");
        List<Map<String, Object>> attachmentMeta = messageParser.extractAttachmentMetadata(payload);
        if (attachmentMeta.isEmpty()) {
            return List.of();
        }

        if (attachmentDirectory != null && !attachmentDirectory.isBlank()) {
            writeAttachmentFiles(apiClient, messageId, attachmentMeta, parentDoc, attachmentDirectory, maxAttachmentBytes);
            return List.of();
        }

        return buildAttachmentDocuments(apiClient, messageId, attachmentMeta, parentDoc);
    }

    /**
     * Server-ingestion mode: emits one Document per attachment (content describes the attachment,
     * with real text content inlined for text-like MIME types), each carrying "source"/
     * "source_path" (parent source_path plus "#attachment/<n>") and "parent_source_path" — the
     * A2 bug: they previously derived an independent "gmail://messages/id/attachments/name" path
     * with no link back to the parent message at all.
     */
    private List<Document> buildAttachmentDocuments(GmailApiClient apiClient, String messageId,
                                                     List<Map<String, Object>> attachmentMeta,
                                                     Document parentDoc) {
        List<Document> attachmentDocs = new ArrayList<>();
        String parentSourcePath = (String) parentDoc.getMetadata().get(GraphConstants.META_SOURCE_PATH);

        int index = 0;
        for (Map<String, Object> att : attachmentMeta) {
            index++;
            String filename = (String) att.get("filename");
            String mimeType = (String) att.get("mimeType");

            String content = "Attachment: " + filename + "\nType: " + mimeType;
            if (att.containsKey("size")) {
                content += "\nSize: " + att.get("size") + " bytes";
            }

            String attachmentSourcePath = parentSourcePath + "#attachment/" + index;

            Map<String, Object> meta = new HashMap<>();
            meta.put(GraphConstants.META_SOURCE, attachmentSourcePath);
            meta.put(GraphConstants.META_SOURCE_PATH, attachmentSourcePath);
            meta.put("parent_source_path", parentSourcePath);
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
                        content = new String(data, StandardCharsets.UTF_8);
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

    /**
     * C4 file-write mode: downloads each attachment's original bytes and saves them under the
     * configured attachmentDirectory, skipping inline images referenced by Content-ID (signature
     * logos — never saved or listed), and records every other attachment's outcome (saved or
     * skipped) in {@code parentDoc}'s "attachments" metadata list.
     */
    private void writeAttachmentFiles(GmailApiClient apiClient, String messageId,
                                       List<Map<String, Object>> attachmentMeta, Document parentDoc,
                                       String attachmentDirectory, long maxAttachmentBytes) {
        String parentSourcePath = (String) parentDoc.getMetadata().get(GraphConstants.META_SOURCE_PATH);
        Path normalizedRoot = Paths.get(attachmentDirectory).normalize();
        String messageKey = messageKeyFor(parentSourcePath);
        Set<String> usedFileNames = new HashSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();

        for (Map<String, Object> att : attachmentMeta) {
            if (isInlineCidImage(att)) {
                continue;
            }
            entries.add(saveAttachmentFile(apiClient, messageId, att, normalizedRoot, messageKey,
                    maxAttachmentBytes, usedFileNames));
        }

        parentDoc.getMetadata().put("attachments", entries);
    }

    /**
     * Downloads and saves one attachment's original bytes under {@code normalizedRoot}, or
     * records why it was skipped (oversize, a resolved path escaping the directory, or a
     * download/I/O failure). Mirrors DiscordAttachmentStorage's / the IMAP loader's
     * attachmentDirectory conventions exactly (identical skip-reason phrasing, messageKey/SHA-256
     * derivation, and filename sanitization) so every loader's "attachments" metadata looks the
     * same. Never throws — a per-attachment problem becomes a "skipped" entry so the rest of the
     * message's attachments still get a chance.
     */
    private Map<String, Object> saveAttachmentFile(GmailApiClient apiClient, String messageId,
                                                     Map<String, Object> att, Path normalizedRoot,
                                                     String messageKey, long maxAttachmentBytes,
                                                     Set<String> usedFileNames) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String originalName = (String) att.get("filename");
        String contentType = (String) att.get("mimeType");
        String fileName = sanitizeAttachmentFileName(originalName, contentType, usedFileNames);
        entry.put("fileName", fileName);
        if (contentType != null) {
            entry.put("contentType", simpleContentType(contentType));
        }

        byte[] data;
        try {
            String attachmentId = (String) att.get("attachmentId");
            String inlineData = (String) att.get("inlineData");
            if (attachmentId != null) {
                data = apiClient.getAttachment(messageId, attachmentId);
            } else if (inlineData != null) {
                data = Base64.getUrlDecoder().decode(inlineData);
            } else {
                entry.put("skipped", "no attachment data available");
                return entry;
            }
        } catch (Exception e) {
            entry.put("skipped", "download failed: " + e.getMessage());
            return entry;
        }

        entry.put("size", (long) data.length);
        if (data.length > maxAttachmentBytes) {
            entry.put("skipped", "exceeds maxAttachmentBytes (" + maxAttachmentBytes + ")");
            return entry;
        }

        try {
            Path messageDir = normalizedRoot.resolve(messageKey).normalize();
            Path target = messageDir.resolve(fileName).normalize();
            if (!target.startsWith(normalizedRoot)) {
                log.warn("Refusing to write Gmail attachment '{}' outside attachmentDirectory: {}", fileName, target);
                entry.put("skipped", "resolved path escaped attachmentDirectory");
                return entry;
            }

            Files.createDirectories(messageDir);
            Files.write(target, data);
            entry.put("path", messageKey + "/" + fileName);
            return entry;
        } catch (Exception e) {
            log.warn("Failed to save Gmail attachment '{}': {}", fileName, e.getMessage());
            entry.put("skipped", "save failed: " + e.getMessage());
            return entry;
        }
    }

    /**
     * True when an attachment part is an inline image referenced by Content-ID — a signature
     * logo the message body embeds via a {@code cid:} URL, not a real user-added attachment —
     * per the audit's "skip ... inline images referenced by Content-ID (signature logos)".
     */
    private boolean isInlineCidImage(Map<String, Object> att) {
        String mimeType = (String) att.get("mimeType");
        String contentId = (String) att.get("contentId");
        return mimeType != null && mimeType.startsWith("image/") && contentId != null && !contentId.isBlank();
    }

    /**
     * Derives the stable per-message directory name for C4 attachment storage: the first 16 hex
     * characters of SHA-256(sourcePath), matching DiscordAttachmentStorage#messageKey exactly so
     * every loader's message directories look the same.
     */
    private String messageKeyFor(String sourcePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sourcePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Sanitizes an original attachment file name exactly like DiscordAttachmentStorage's
     * sanitizeFileName, so every loader's saved-attachment naming convention is identical:
     * strips path separators and control characters, strips leading dots, derives an extension
     * from the content type when the name is missing one, truncates to keep the stem+extension
     * within the shared length cap, and de-duplicates within the message as name-2.ext, etc.
     */
    private String sanitizeAttachmentFileName(String original, String contentType, Set<String> usedFileNames) {
        String base = original == null ? "" : original;
        StringBuilder cleaned = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                continue;
            }
            cleaned.append(c);
        }
        String name = cleaned.toString();

        int firstNonDot = 0;
        while (firstNonDot < name.length() && name.charAt(firstNonDot) == '.') {
            firstNonDot++;
        }
        name = name.substring(firstNonDot);
        if (name.isEmpty()) {
            name = "attachment";
        }

        String ext;
        String stem;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot);
            stem = name.substring(0, dot);
        } else {
            stem = name;
            String derivedExt = extensionForContentType(contentType);
            ext = derivedExt != null ? derivedExt : "";
        }

        if (stem.length() + ext.length() > MAX_ATTACHMENT_FILENAME_LENGTH) {
            int stemBudget = Math.max(1, MAX_ATTACHMENT_FILENAME_LENGTH - ext.length());
            stem = stem.substring(0, Math.min(stem.length(), stemBudget));
        }

        String candidate = stem + ext;
        if (usedFileNames.add(candidate)) {
            return candidate;
        }

        for (int suffix = 2; ; suffix++) {
            String suffixed = stem + "-" + suffix + ext;
            if (usedFileNames.add(suffixed)) {
                return suffixed;
            }
        }
    }

    private String extensionForContentType(String contentType) {
        return contentType == null ? null : ATTACHMENT_EXTENSION_BY_CONTENT_TYPE.get(simpleContentType(contentType));
    }

    /**
     * Strips MIME parameters (e.g. "; charset=us-ascii") from a Content-Type value, returning
     * just the type/subtype in lower case.
     */
    private String simpleContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        String type = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the shared "since" metadata contract (C3): an ISO-8601 instant, also accepting the
     * offset form (e.g. "2026-09-01T00:00:00+02:00"). Returns null when absent or unparsable.
     */
    private Instant parseSinceInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // not a plain instant; try offset form
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            log.warn("Unable to parse Gmail 'since' value '{}'", trimmed);
            return null;
        }
    }

    /**
     * Client-side enforcement of the shared "since" contract (C3) on top of Gmail's coarser
     * query-level "after:" filter (day-granularity). A message with no internalDate is kept —
     * there's nothing to compare against, mirroring how IMAP/POP3 keep undated messages.
     */
    private boolean passesSinceFilter(JsonNode messageJson, Instant effectiveSince) {
        if (effectiveSince == null || messageJson == null || !messageJson.has("internalDate")) {
            return true;
        }
        long epochMillis = messageJson.get("internalDate").asLong();
        return !Instant.ofEpochMilli(epochMillis).isBefore(effectiveSince);
    }

    private String buildEffectiveQuery(String userQuery, Instant effectiveSince) {
        long epochSeconds = effectiveSince.getEpochSecond();
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
