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

package ai.kompile.loader.gworkspace;

import ai.kompile.core.crawler.*;
import ai.kompile.utils.MapUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Crawler that traverses Google Workspace services, emitting {@link CrawlItem}s for:
 * <ul>
 *   <li><b>Gmail</b>: Messages, threads, and attachments</li>
 *   <li><b>Drive</b>: Files, sharing metadata, and comments</li>
 *   <li><b>Calendar</b>: Events with attendees and organizer info</li>
 * </ul>
 *
 * <p>CrawlConfig properties:
 * <ul>
 *   <li>{@code accessToken} - Google OAuth access token (required)</li>
 *   <li>{@code services} - Comma-separated services to crawl: gmail,drive,calendar (default: all)</li>
 *   <li>{@code gmailQuery} - Gmail search query (e.g. "label:inbox")</li>
 *   <li>{@code gmailMaxMessages} - Max Gmail messages (default 500)</li>
 *   <li>{@code includeGmailAttachments} - Download Gmail attachments (default true)</li>
 *   <li>{@code driveQuery} - Drive search query</li>
 *   <li>{@code driveMaxFiles} - Max Drive files (default 500)</li>
 *   <li>{@code includeDriveComments} - Include Drive comments (default true)</li>
 *   <li>{@code calendarIds} - Comma-separated calendar IDs (default: "primary")</li>
 *   <li>{@code calendarMaxEvents} - Max calendar events (default 500)</li>
 *   <li>{@code daysBack} - Days of history (default 30)</li>
 * </ul>
 */
@Slf4j
@Component
public class GWorkspaceCrawler extends AbstractCrawler {

    private final GmailMessageParser gmailParser = new GmailMessageParser();

    @Override
    public String getId() {
        return "google-workspace";
    }

    @Override
    public String getName() {
        return "Google Workspace Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls Gmail, Google Drive, and Google Calendar for messages, files, and events";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.GOOGLE_WORKSPACE);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        if (str(props.get("accessToken")) == null) {
            errors.add("Google OAuth access token is required (property: accessToken)");
        }

        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new GWorkspaceCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        GWorkspaceCrawlJob job = (GWorkspaceCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        String accessToken = str(props.get("accessToken"));
        GWorkspaceApiService api = new GWorkspaceApiService(accessToken);

        Set<String> services = parseServices(props);
        int daysBack = MapUtils.toInt(props.get("daysBack"), 30);
        String timeMin = Instant.now().minus(Duration.ofDays(daysBack))
                .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        int totalEmitted = 0;

        // ========== Gmail ==========
        if (services.contains("gmail")) {
            job.setCurrentItem("Gmail");
            job.setCurrentDepth(0);

            int maxMessages = MapUtils.toInt(props.get("gmailMaxMessages"), 500);
            boolean includeAttachments = boolVal(props.get("includeGmailAttachments"), true);
            String gmailQuery = str(props.get("gmailQuery"));
            if (gmailQuery == null || gmailQuery.isEmpty()) {
                gmailQuery = "after:" + Instant.now().minus(Duration.ofDays(daysBack)).getEpochSecond();
            }

            log.info("Crawling Gmail with query: {}", gmailQuery);

            List<String> messageIds;
            try {
                messageIds = api.listGmailMessageIds(gmailQuery, maxMessages);
            } catch (Exception e) {
                job.recordError("gmail:list", e);
                log.warn("Failed to list Gmail messages: {}", e.getMessage());
                messageIds = List.of();
            }

            log.info("Found {} Gmail messages", messageIds.size());

            for (String msgId : messageIds) {
                if (job.shouldStop()) break;
                if (job.visitedIds.contains("gmail:" + msgId)) {
                    job.incrementSkipped();
                    continue;
                }

                try {
                    JsonNode fullMessage = api.getGmailMessage(msgId);
                    Document doc = gmailParser.parse(fullMessage, config.getCollectionName());

                    CrawlItem item = buildGmailCrawlItem(doc, msgId, fullMessage, config);
                    job.incrementDiscovered();
                    job.getListener().onDocumentDiscovered(item);
                    job.incrementProcessed();
                    job.getListener().onDocumentProcessed(item);
                    job.visitedIds.add("gmail:" + msgId);
                    totalEmitted++;

                    // Emit attachments
                    if (includeAttachments) {
                        totalEmitted += emitGmailAttachments(api, fullMessage, msgId, config, job);
                    }
                } catch (Exception e) {
                    job.recordError("gmail:" + msgId, e);
                    log.debug("Failed to process Gmail message {}: {}", msgId, e.getMessage());
                }
            }

            job.recordSyncTime("gmail", Instant.now().toString());
        }

        // ========== Drive ==========
        if (services.contains("drive")) {
            if (job.shouldStop()) return;
            job.setCurrentItem("Google Drive");
            job.setCurrentDepth(0);

            int maxFiles = MapUtils.toInt(props.get("driveMaxFiles"), 500);
            boolean includeComments = boolVal(props.get("includeDriveComments"), true);
            String driveQuery = str(props.get("driveQuery"));
            if (driveQuery == null || driveQuery.isEmpty()) {
                driveQuery = "modifiedTime > '" + timeMin + "' and trashed = false";
            }

            log.info("Crawling Drive with query: {}", driveQuery);

            List<JsonNode> files;
            try {
                files = api.listDriveFiles(driveQuery, maxFiles);
            } catch (Exception e) {
                job.recordError("drive:list", e);
                log.warn("Failed to list Drive files: {}", e.getMessage());
                files = List.of();
            }

            log.info("Found {} Drive files", files.size());
            Map<String, String> folderNameCache = new HashMap<>();

            for (JsonNode file : files) {
                if (job.shouldStop()) break;
                String fileId = file.get("id").asText();
                if (job.visitedIds.contains("drive:" + fileId)) {
                    job.incrementSkipped();
                    continue;
                }

                CrawlItem item = buildDriveCrawlItem(file, config, api, folderNameCache);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);
                job.visitedIds.add("drive:" + fileId);
                totalEmitted++;

                // Comments
                if (includeComments) {
                    try {
                        List<JsonNode> comments = api.getDriveComments(fileId);
                        for (JsonNode comment : comments) {
                            CrawlItem commentItem = buildDriveCommentCrawlItem(comment, file, config);
                            job.incrementDiscovered();
                            job.getListener().onDocumentDiscovered(commentItem);
                            job.incrementProcessed();
                            job.getListener().onDocumentProcessed(commentItem);
                            totalEmitted++;
                        }
                    } catch (Exception e) {
                        log.debug("Cannot access comments for file {}: {}", fileId, e.getMessage());
                    }
                }
            }

            job.recordSyncTime("drive", Instant.now().toString());
        }

        // ========== Calendar ==========
        if (services.contains("calendar")) {
            if (job.shouldStop()) return;
            job.setCurrentItem("Google Calendar");
            job.setCurrentDepth(0);

            int maxEvents = MapUtils.toInt(props.get("calendarMaxEvents"), 500);
            String calendarIdsStr = str(props.get("calendarIds"));
            List<String> calendarIds = (calendarIdsStr != null && !calendarIdsStr.isEmpty())
                    ? Arrays.asList(calendarIdsStr.split(",")) : List.of("primary");

            String timeMax = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            for (String calId : calendarIds) {
                if (job.shouldStop()) break;
                log.info("Crawling calendar: {}", calId.trim());

                List<JsonNode> events;
                try {
                    events = api.listCalendarEvents(calId.trim(), timeMin, timeMax, maxEvents);
                } catch (Exception e) {
                    job.recordError("calendar:" + calId.trim(), e);
                    log.warn("Failed to list events for calendar {}: {}", calId.trim(), e.getMessage());
                    continue;
                }

                log.info("Found {} events in calendar {}", events.size(), calId.trim());

                for (JsonNode event : events) {
                    if (job.shouldStop()) break;
                    String eventId = event.has("id") ? event.get("id").asText() : null;
                    if (eventId == null) continue;

                    if (job.visitedIds.contains("cal:" + eventId)) {
                        job.incrementSkipped();
                        continue;
                    }

                    CrawlItem item = buildCalendarCrawlItem(event, calId.trim(), config);
                    job.incrementDiscovered();
                    job.getListener().onDocumentDiscovered(item);
                    job.incrementProcessed();
                    job.getListener().onDocumentProcessed(item);
                    job.visitedIds.add("cal:" + eventId);
                    totalEmitted++;
                }
            }

            job.recordSyncTime("calendar", Instant.now().toString());
        }

        log.info("Google Workspace crawl complete: {} items emitted", totalEmitted);
    }

    // ========== CrawlItem builders ==========

    private CrawlItem buildGmailCrawlItem(Document doc, String messageId, JsonNode fullMessage, CrawlConfig config) {
        Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.GOOGLE_WORKSPACE)
                .pathOrUrl("gworkspace://gmail/message/" + messageId)
                .sourceId("gmail:" + messageId)
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .build();

        return CrawlItem.builder()
                .url(descriptor.getPathOrUrl())
                .depth(0)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType("text/plain")
                .discoveredAt(Instant.now())
                .build();
    }

    private int emitGmailAttachments(GWorkspaceApiService api, JsonNode fullMessage, String messageId,
                                      CrawlConfig config, GWorkspaceCrawlJob job) {
        List<Map<String, Object>> attachments = gmailParser.extractAttachmentMetadata(fullMessage);
        int emitted = 0;

        for (Map<String, Object> att : attachments) {
            if (job.shouldStop()) break;
            String attachmentId = (String) att.get("attachmentId");
            String filename = (String) att.get("filename");
            if (attachmentId == null || attachmentId.isEmpty()) continue;

            try {
                byte[] data = api.getGmailAttachment(messageId, attachmentId);

                String suffix = "";
                if (filename != null) {
                    int dotIdx = filename.lastIndexOf('.');
                    if (dotIdx > 0) suffix = filename.substring(dotIdx);
                }
                Path tempFile = java.nio.file.Files.createTempFile("kompile-gmail-att-", suffix);
                tempFile.toFile().deleteOnExit();
                java.nio.file.Files.write(tempFile, data);

                Map<String, Object> metadata = new LinkedHashMap<>();
                String attSource = "gworkspace://gmail/message/" + messageId + "/attachment/" + attachmentId;
                metadata.put(GraphConstants.META_SOURCE, attSource);
                metadata.put(GraphConstants.META_SOURCE_PATH, attSource);
                metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
                metadata.put(GraphConstants.META_LOADER, "Google Workspace Loader");
                metadata.put(GraphConstants.META_DOCUMENT_TYPE, "email_attachment");
                metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "gmail");
                metadata.put("gworkspace.gmail.parentMessageId", messageId);
                // Propagate parent message context for graph linking
                if (fullMessage.has("threadId")) {
                    metadata.put("gworkspace.gmail.threadId", fullMessage.get("threadId").asText());
                }
                JsonNode payload = fullMessage.get("payload");
                if (payload != null && payload.has("headers") && payload.get("headers").isArray()) {
                    for (JsonNode hdr : payload.get("headers")) {
                        if ("Subject".equalsIgnoreCase(hdr.path("name").asText())) {
                            metadata.put("gworkspace.gmail.parentSubject", hdr.path("value").asText());
                            break;
                        }
                    }
                }
                metadata.put(GraphConstants.META_FILE_NAME, filename);
                metadata.put("gworkspace.gmail.attachmentFilename", filename);
                metadata.put("gworkspace.gmail.attachmentId", attachmentId);
                metadata.put("gworkspace.gmail.attachmentMimeType", att.get("mimeType"));
                metadata.put("gworkspace.gmail.attachmentSize", att.get("size"));

                DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                        .type(SourceType.FILE)
                        .pathOrUrl(tempFile.toString())
                        .originalFileName(filename)
                        .sourceId("gmail-att:" + messageId + ":" + attachmentId)
                        .collectionName(config.getCollectionName())
                        .metadata(metadata)
                        .build();

                CrawlItem attItem = CrawlItem.builder()
                        .url("gworkspace://gmail/message/" + messageId + "/attachment/" + attachmentId)
                        .parentUrl("gworkspace://gmail/message/" + messageId)
                        .depth(1)
                        .sourceDescriptor(descriptor)
                        .metadata(metadata)
                        .contentType((String) att.get("mimeType"))
                        .contentLength(att.get("size") instanceof Number n ? n.longValue() : null)
                        .discoveredAt(Instant.now())
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(attItem);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(attItem);
                emitted++;
            } catch (Exception e) {
                job.recordError("gmail-att:" + filename, e);
                log.debug("Failed to download Gmail attachment {}: {}", filename, e.getMessage());
            }
        }

        return emitted;
    }

    private CrawlItem buildDriveCrawlItem(JsonNode file, CrawlConfig config,
                                            GWorkspaceApiService api,
                                            Map<String, String> folderNameCache) {
        String fileId = file.get("id").asText();
        String fileName = file.has("name") ? file.get("name").asText() : fileId;
        String mimeType = file.has("mimeType") ? file.get("mimeType").asText() : "application/octet-stream";

        Map<String, Object> metadata = new LinkedHashMap<>();
        String driveSource = "gworkspace://drive/file/" + fileId;
        metadata.put(GraphConstants.META_SOURCE, driveSource);
        metadata.put(GraphConstants.META_SOURCE_PATH, driveSource);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
        metadata.put(GraphConstants.META_LOADER, "Google Workspace Loader");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, resolveDocType(mimeType, fileName));
        metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "drive");
        metadata.put(GraphConstants.META_FILE_NAME, fileName);
        metadata.put("gworkspace.drive.fileId", fileId);
        metadata.put("gworkspace.drive.fileName", fileName);
        metadata.put("gworkspace.drive.mimeType", mimeType);
        if (file.has("size")) metadata.put("gworkspace.drive.size", file.get("size").asLong());
        if (file.has("modifiedTime")) metadata.put("gworkspace.drive.modifiedTime", file.get("modifiedTime").asText());
        if (file.has("createdTime")) metadata.put("gworkspace.drive.createdTime", file.get("createdTime").asText());
        if (file.has("webViewLink")) metadata.put("gworkspace.drive.webViewLink", file.get("webViewLink").asText());
        if (file.has("shared")) metadata.put("gworkspace.drive.shared", file.get("shared").asBoolean());
        if (file.has("description")) metadata.put("gworkspace.drive.description", file.get("description").asText());

        // Owners
        if (file.has("owners") && file.get("owners").isArray()) {
            List<String> ownerEmails = new ArrayList<>();
            List<String> ownerNames = new ArrayList<>();
            for (JsonNode owner : file.get("owners")) {
                if (owner.has("emailAddress")) ownerEmails.add(owner.get("emailAddress").asText());
                if (owner.has("displayName")) ownerNames.add(owner.get("displayName").asText());
            }
            metadata.put("gworkspace.drive.ownerEmails", ownerEmails);
            metadata.put("gworkspace.drive.ownerNames", ownerNames);
        }

        // Last modifier
        if (file.has("lastModifyingUser")) {
            JsonNode modifier = file.get("lastModifyingUser");
            if (modifier.has("emailAddress")) metadata.put("gworkspace.drive.lastModifierEmail", modifier.get("emailAddress").asText());
            if (modifier.has("displayName")) metadata.put("gworkspace.drive.lastModifierName", modifier.get("displayName").asText());
        }

        // Permissions (sharing info)
        if (file.has("permissions") && file.get("permissions").isArray()) {
            List<Map<String, String>> permList = new ArrayList<>();
            for (JsonNode perm : file.get("permissions")) {
                Map<String, String> p = new LinkedHashMap<>();
                if (perm.has("emailAddress")) p.put("email", perm.get("emailAddress").asText());
                if (perm.has("displayName")) p.put("name", perm.get("displayName").asText());
                if (perm.has("role")) p.put("role", perm.get("role").asText());
                if (perm.has("type")) p.put("type", perm.get("type").asText());
                if (!p.isEmpty()) permList.add(p);
            }
            if (!permList.isEmpty()) metadata.put("gworkspace.drive.permissions", permList);
        }

        // Parent folder (first entry in the parents array)
        if (file.has("parents") && file.get("parents").isArray() && !file.get("parents").isEmpty()) {
            String parentFolderId = file.get("parents").get(0).asText();
            if (!parentFolderId.isEmpty()) {
                metadata.put("gworkspace.drive.parentFolderId", parentFolderId);
                // Resolve folder name via cache + API lookup
                if (api != null && folderNameCache != null) {
                    String folderName = folderNameCache.computeIfAbsent(parentFolderId,
                            id -> {
                                String name = api.getDriveFileName(id);
                                return name != null ? name : "";
                            });
                    if (!folderName.isEmpty()) {
                        metadata.put("gworkspace.drive.parentFolderName", folderName);
                    }
                }
            }
        }

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.GOOGLE_WORKSPACE)
                .pathOrUrl("gworkspace://drive/file/" + fileId)
                .sourceId("drive:" + fileId)
                .originalFileName(fileName)
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .build();

        return CrawlItem.builder()
                .url(descriptor.getPathOrUrl())
                .depth(0)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType(mimeType)
                .discoveredAt(Instant.now())
                .build();
    }

    private CrawlItem buildDriveCommentCrawlItem(JsonNode comment, JsonNode file, CrawlConfig config) {
        String fileId = file.get("id").asText();
        String commentId = comment.has("id") ? comment.get("id").asText() : UUID.randomUUID().toString();
        String content = comment.has("content") ? comment.get("content").asText() : "";

        Map<String, Object> metadata = new LinkedHashMap<>();
        String commentSource = "gworkspace://drive/file/" + fileId + "/comment/" + commentId;
        metadata.put(GraphConstants.META_SOURCE, commentSource);
        metadata.put(GraphConstants.META_SOURCE_PATH, commentSource);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
        metadata.put(GraphConstants.META_LOADER, "Google Workspace Loader");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "drive_comment");
        metadata.put(GraphConstants.META_FILE_NAME, "Comment on " + (file.has("name") ? file.get("name").asText() : fileId));
        metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "drive_comment");
        metadata.put("gworkspace.drive.fileId", fileId);
        metadata.put("gworkspace.drive.fileName", file.has("name") ? file.get("name").asText() : "");
        metadata.put("gworkspace.drive.commentId", commentId);
        metadata.put("gworkspace.drive.commentContent", content);
        if (comment.has("createdTime")) metadata.put("gworkspace.drive.commentCreatedTime", comment.get("createdTime").asText());
        if (comment.has("resolved")) metadata.put("gworkspace.drive.commentResolved", comment.get("resolved").asBoolean());

        JsonNode author = comment.get("author");
        if (author != null) {
            if (author.has("emailAddress")) metadata.put("gworkspace.drive.commentAuthorEmail", author.get("emailAddress").asText());
            if (author.has("displayName")) metadata.put("gworkspace.drive.commentAuthorName", author.get("displayName").asText());
        }

        // Replies — serialize full reply data for graph extraction
        JsonNode replies = comment.get("replies");
        if (replies != null && replies.isArray() && !replies.isEmpty()) {
            metadata.put("gworkspace.drive.commentReplyCount", replies.size());
            List<Map<String, String>> replyList = new ArrayList<>();
            for (JsonNode reply : replies) {
                if (replyList.size() >= 100) break;
                Map<String, String> replyMap = new LinkedHashMap<>();
                if (reply.has("id")) replyMap.put("replyId", reply.get("id").asText());
                if (reply.has("content")) replyMap.put("content", reply.get("content").asText());
                if (reply.has("createdTime")) replyMap.put("createdTime", reply.get("createdTime").asText());
                if (reply.has("modifiedTime")) replyMap.put("modifiedTime", reply.get("modifiedTime").asText());
                if (reply.has("action")) replyMap.put("action", reply.get("action").asText());
                JsonNode replyAuthor = reply.get("author");
                if (replyAuthor != null) {
                    if (replyAuthor.has("emailAddress")) replyMap.put("authorEmail", replyAuthor.get("emailAddress").asText());
                    if (replyAuthor.has("displayName")) replyMap.put("authorName", replyAuthor.get("displayName").asText());
                }
                replyList.add(replyMap);
            }
            if (!replyList.isEmpty()) {
                metadata.put("gworkspace.drive.commentReplies", replyList);
            }
        }

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.GOOGLE_WORKSPACE)
                .pathOrUrl("gworkspace://drive/file/" + fileId + "/comment/" + commentId)
                .sourceId("drive-comment:" + commentId)
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .build();

        return CrawlItem.builder()
                .url(descriptor.getPathOrUrl())
                .parentUrl("gworkspace://drive/file/" + fileId)
                .depth(1)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType("text/plain")
                .discoveredAt(Instant.now())
                .build();
    }

    private CrawlItem buildCalendarCrawlItem(JsonNode event, String calendarId, CrawlConfig config) {
        String eventId = event.get("id").asText();
        String summary = event.has("summary") ? event.get("summary").asText() : "(no title)";

        String calendarSource = "gworkspace://calendar/" + calendarId + "/event/" + eventId;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GraphConstants.META_SOURCE, calendarSource);
        metadata.put(GraphConstants.META_SOURCE_PATH, calendarSource);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
        metadata.put(GraphConstants.META_LOADER, "Google Workspace Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "calendar_event");
        metadata.put(GraphConstants.META_FILE_NAME, summary);
        metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "calendar");
        metadata.put("gworkspace.calendar.eventId", eventId);
        metadata.put("gworkspace.calendar.calendarId", calendarId);
        metadata.put("gworkspace.calendar.summary", summary);
        if (event.has("description")) metadata.put("gworkspace.calendar.description", event.get("description").asText());
        if (event.has("location")) metadata.put("gworkspace.calendar.location", event.get("location").asText());
        if (event.has("status")) metadata.put("gworkspace.calendar.status", event.get("status").asText());
        if (event.has("htmlLink")) metadata.put("gworkspace.calendar.htmlLink", event.get("htmlLink").asText());

        // Start/end times
        JsonNode start = event.get("start");
        if (start != null) {
            String startTime = start.has("dateTime") ? start.get("dateTime").asText()
                    : start.has("date") ? start.get("date").asText() : null;
            if (startTime != null) metadata.put("gworkspace.calendar.startTime", startTime);
        }
        JsonNode end = event.get("end");
        if (end != null) {
            String endTime = end.has("dateTime") ? end.get("dateTime").asText()
                    : end.has("date") ? end.get("date").asText() : null;
            if (endTime != null) metadata.put("gworkspace.calendar.endTime", endTime);
        }

        // Organizer
        JsonNode organizer = event.get("organizer");
        if (organizer != null) {
            if (organizer.has("email")) metadata.put("gworkspace.calendar.organizerEmail", organizer.get("email").asText());
            if (organizer.has("displayName")) metadata.put("gworkspace.calendar.organizerName", organizer.get("displayName").asText());
        }

        // Creator
        JsonNode creator = event.get("creator");
        if (creator != null) {
            if (creator.has("email")) metadata.put("gworkspace.calendar.creatorEmail", creator.get("email").asText());
            if (creator.has("displayName")) metadata.put("gworkspace.calendar.creatorName", creator.get("displayName").asText());
        }

        // Attendees
        JsonNode attendees = event.get("attendees");
        if (attendees != null && attendees.isArray()) {
            List<Map<String, String>> attendeeList = new ArrayList<>();
            for (JsonNode att : attendees) {
                Map<String, String> a = new LinkedHashMap<>();
                if (att.has("email")) a.put("email", att.get("email").asText());
                if (att.has("displayName")) a.put("name", att.get("displayName").asText());
                if (att.has("responseStatus")) a.put("responseStatus", att.get("responseStatus").asText());
                if (att.has("organizer") && att.get("organizer").asBoolean()) a.put("organizer", "true");
                if (att.has("optional") && att.get("optional").asBoolean()) a.put("optional", "true");
                if (att.has("self") && att.get("self").asBoolean()) a.put("self", "true");
                if (!a.isEmpty()) attendeeList.add(a);
            }
            metadata.put("gworkspace.calendar.attendees", attendeeList);
            metadata.put("gworkspace.calendar.attendeeCount", attendeeList.size());
        }

        // Recurrence
        if (event.has("recurringEventId")) metadata.put("gworkspace.calendar.recurringEventId", event.get("recurringEventId").asText());
        JsonNode recurrence = event.get("recurrence");
        if (recurrence != null && recurrence.isArray() && !recurrence.isEmpty()) {
            List<String> rules = new ArrayList<>();
            for (JsonNode r : recurrence) rules.add(r.asText());
            metadata.put("gworkspace.calendar.recurrence", rules);
        }

        // Conference data (Google Meet link)
        JsonNode confData = event.get("conferenceData");
        if (confData != null) {
            JsonNode entryPoints = confData.get("entryPoints");
            if (entryPoints != null && entryPoints.isArray()) {
                for (JsonNode ep : entryPoints) {
                    if ("video".equals(ep.path("entryPointType").asText(null))) {
                        String meetUri = ep.path("uri").asText(null);
                        if (meetUri != null) metadata.put("gworkspace.calendar.conferenceUrl", meetUri);
                        break;
                    }
                }
            }
        }

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.GOOGLE_WORKSPACE)
                .pathOrUrl("gworkspace://calendar/" + calendarId + "/event/" + eventId)
                .sourceId("cal:" + eventId)
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .build();

        return CrawlItem.builder()
                .url(descriptor.getPathOrUrl())
                .depth(0)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType("text/plain")
                .discoveredAt(Instant.now())
                .build();
    }

    // ========== Helpers ==========

    private Set<String> parseServices(Map<String, Object> props) {
        String servicesStr = str(props.get("services"));
        if (servicesStr == null || servicesStr.isEmpty()) {
            return Set.of("gmail", "drive", "calendar");
        }
        Set<String> services = new LinkedHashSet<>();
        for (String s : servicesStr.split(",")) {
            services.add(s.trim().toLowerCase());
        }
        return services;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    private static boolean boolVal(Object obj, boolean defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Boolean b) return b;
        return Boolean.parseBoolean(obj.toString());
    }

    private static String resolveDocType(String mimeType, String fileName) {
        if (mimeType != null) {
            if (mimeType.contains("spreadsheet") || mimeType.contains("excel")) return "spreadsheet";
            if (mimeType.contains("presentation") || mimeType.contains("powerpoint")) return "presentation";
            if (mimeType.contains("document") || mimeType.contains("word")) return "document";
            if (mimeType.contains("pdf")) return "pdf";
            if (mimeType.startsWith("image/")) return "image";
            if (mimeType.startsWith("video/")) return "video";
            if (mimeType.startsWith("audio/")) return "audio";
            if (mimeType.contains("text/")) return "text";
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf")) return "pdf";
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv")) return "spreadsheet";
            if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "document";
            if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return "presentation";
            if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text";
        }
        return "drive_file";
    }
}
