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

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * Google Workspace DocumentLoader — simpler alternative to the crawler for direct loading.
 * Fetches Gmail messages, Drive file metadata, and Calendar events using a Google OAuth access token.
 *
 * <p>Configuration via {@link DocumentSourceDescriptor#getMetadata()}:
 * <ul>
 *   <li>{@code accessToken} - Google OAuth access token (required)</li>
 *   <li>{@code services} - Comma-separated: gmail,drive,calendar (default: all)</li>
 *   <li>{@code gmailQuery} - Gmail search query (e.g. "label:inbox")</li>
 *   <li>{@code gmailMaxMessages} - Max Gmail messages (default 100)</li>
 *   <li>{@code driveQuery} - Drive search query</li>
 *   <li>{@code driveMaxFiles} - Max Drive files (default 100)</li>
 *   <li>{@code calendarIds} - Comma-separated calendar IDs (default: "primary")</li>
 *   <li>{@code calendarMaxEvents} - Max calendar events (default 100)</li>
 *   <li>{@code daysBack} - Days of history (default 30)</li>
 * </ul>
 */
@Slf4j
@Component
public class GWorkspaceLoaderImpl implements DocumentLoader {

    private final GmailMessageParser gmailParser = new GmailMessageParser();

    @Override
    public String getName() {
        return "Google Workspace Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor.getType() == SourceType.GOOGLE_WORKSPACE;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor, Consumer<LoaderProgress> progressCallback)
            throws Exception {
        Map<String, Object> meta = sourceDescriptor.getMetadata() != null
                ? sourceDescriptor.getMetadata() : Map.of();

        String accessToken = str(meta.get("accessToken"));
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalArgumentException("Google OAuth access token is required (metadata key: accessToken)");
        }

        GWorkspaceApiService api = new GWorkspaceApiService(accessToken);

        Set<String> services = parseServices(meta);
        int daysBack = intVal(meta.get("daysBack"), 30);
        String collectionName = sourceDescriptor.getCollectionName();

        List<Document> documents = new ArrayList<>();
        int totalSteps = services.size();
        int currentStep = 0;

        // ========== Gmail ==========
        if (services.contains("gmail")) {
            currentStep++;
            notifyProgress(progressCallback, "Loading Gmail", pct(currentStep, totalSteps, 0), "Fetching messages");

            int maxMessages = intVal(meta.get("gmailMaxMessages"), 100);
            String gmailQuery = str(meta.get("gmailQuery"));
            if (gmailQuery == null || gmailQuery.isEmpty()) {
                gmailQuery = "after:" + Instant.now().minus(Duration.ofDays(daysBack)).getEpochSecond();
            }

            try {
                List<String> messageIds = api.listGmailMessageIds(gmailQuery, maxMessages);
                log.info("Found {} Gmail messages", messageIds.size());

                for (int i = 0; i < messageIds.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    notifyProgress(progressCallback, "Loading Gmail", pct(currentStep, totalSteps, (double) i / messageIds.size()),
                            "Message " + (i + 1) + "/" + messageIds.size());

                    try {
                        JsonNode fullMessage = api.getGmailMessage(messageIds.get(i));
                        Document doc = gmailParser.parse(fullMessage, collectionName);
                        if (sourceDescriptor.getSourceId() != null) {
                            doc.getMetadata().put(GraphConstants.META_SOURCE_ID, sourceDescriptor.getSourceId());
                        }
                        documents.add(doc);
                    } catch (Exception e) {
                        log.debug("Failed to load Gmail message {}: {}", messageIds.get(i), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to list Gmail messages: {}", e.getMessage());
            }
        }

        // ========== Drive ==========
        if (services.contains("drive")) {
            if (Thread.currentThread().isInterrupted()) return documents;
            currentStep++;
            notifyProgress(progressCallback, "Loading Drive", pct(currentStep, totalSteps, 0), "Fetching files");

            int maxFiles = intVal(meta.get("driveMaxFiles"), 100);
            String driveQuery = str(meta.get("driveQuery"));
            String timeMin = Instant.now().minus(Duration.ofDays(daysBack))
                    .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            if (driveQuery == null || driveQuery.isEmpty()) {
                driveQuery = "modifiedTime > '" + timeMin + "' and trashed = false";
            }

            try {
                List<JsonNode> files = api.listDriveFiles(driveQuery, maxFiles);
                log.info("Found {} Drive files", files.size());

                for (int i = 0; i < files.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    notifyProgress(progressCallback, "Loading Drive", pct(currentStep, totalSteps, (double) i / files.size()),
                            "File " + (i + 1) + "/" + files.size());

                    JsonNode file = files.get(i);
                    documents.add(buildDriveDocument(file, collectionName, sourceDescriptor.getSourceId()));
                }
            } catch (Exception e) {
                log.warn("Failed to list Drive files: {}", e.getMessage());
            }
        }

        // ========== Calendar ==========
        if (services.contains("calendar")) {
            if (Thread.currentThread().isInterrupted()) return documents;
            currentStep++;
            notifyProgress(progressCallback, "Loading Calendar", pct(currentStep, totalSteps, 0), "Fetching events");

            int maxEvents = intVal(meta.get("calendarMaxEvents"), 100);
            String calendarIdsStr = str(meta.get("calendarIds"));
            List<String> calendarIds = (calendarIdsStr != null && !calendarIdsStr.isEmpty())
                    ? Arrays.asList(calendarIdsStr.split(",")) : List.of("primary");

            String timeMin = Instant.now().minus(Duration.ofDays(daysBack))
                    .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String timeMax = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            for (String calId : calendarIds) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    List<JsonNode> events = api.listCalendarEvents(calId.trim(), timeMin, timeMax, maxEvents);
                    log.info("Found {} events in calendar {}", events.size(), calId.trim());

                    for (JsonNode event : events) {
                        documents.add(buildCalendarDocument(event, calId.trim(), collectionName, sourceDescriptor.getSourceId()));
                    }
                } catch (Exception e) {
                    log.warn("Failed to list events for calendar {}: {}", calId.trim(), e.getMessage());
                }
            }
        }

        notifyProgress(progressCallback, "Complete", 100,
                "Loaded " + documents.size() + " documents from Google Workspace");
        log.info("Google Workspace loading complete: {} documents", documents.size());
        return documents;
    }

    private Document buildDriveDocument(JsonNode file, String collectionName, String sourceId) {
        String fileId = file.get("id").asText();
        String fileName = file.has("name") ? file.get("name").asText() : fileId;
        String mimeType = file.has("mimeType") ? file.get("mimeType").asText() : "";

        StringBuilder content = new StringBuilder();
        content.append("File: ").append(fileName).append("\n");
        content.append("Type: ").append(mimeType).append("\n");
        if (file.has("description")) content.append("Description: ").append(file.get("description").asText()).append("\n");
        if (file.has("modifiedTime")) content.append("Modified: ").append(file.get("modifiedTime").asText()).append("\n");
        if (file.has("webViewLink")) content.append("Link: ").append(file.get("webViewLink").asText()).append("\n");

        Document doc = new Document(content.toString());
        Map<String, Object> metadata = doc.getMetadata();
        String sourcePath = "gworkspace:drive/" + fileId;
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
        metadata.put(GraphConstants.META_FILE_NAME, fileName);
        metadata.put(GraphConstants.META_LOADER, getName());
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, resolveDocType(mimeType, fileName));
        metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "drive");
        metadata.put("gworkspace.drive.fileId", fileId);
        metadata.put("gworkspace.drive.fileName", fileName);
        metadata.put("gworkspace.drive.mimeType", mimeType);
        if (file.has("modifiedTime")) metadata.put("gworkspace.drive.modifiedTime", file.get("modifiedTime").asText());
        if (file.has("createdTime")) metadata.put("gworkspace.drive.createdTime", file.get("createdTime").asText());
        if (file.has("webViewLink")) metadata.put("gworkspace.drive.webViewLink", file.get("webViewLink").asText());
        if (file.has("size")) metadata.put("gworkspace.drive.size", file.get("size").asLong());
        if (file.has("shared")) metadata.put("gworkspace.drive.shared", file.get("shared").asBoolean());
        if (file.has("description") && !file.get("description").asText("").isBlank()) {
            metadata.put("gworkspace.drive.description", file.get("description").asText());
        }

        // Permissions → enables SHARED_WITH relations in the extractor
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
            if (!permList.isEmpty()) {
                metadata.put("gworkspace.drive.permissions", permList);
            }
        }

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

        if (file.has("lastModifyingUser")) {
            JsonNode modifier = file.get("lastModifyingUser");
            if (modifier.has("emailAddress")) metadata.put("gworkspace.drive.lastModifierEmail", modifier.get("emailAddress").asText());
            if (modifier.has("displayName")) metadata.put("gworkspace.drive.lastModifierName", modifier.get("displayName").asText());
        }

        // Parent folder (first entry in the parents array)
        if (file.has("parents") && file.get("parents").isArray() && !file.get("parents").isEmpty()) {
            String parentFolderId = file.get("parents").get(0).asText();
            if (!parentFolderId.isEmpty()) {
                metadata.put("gworkspace.drive.parentFolderId", parentFolderId);
            }
        }

        if (collectionName != null) metadata.put("collection_name", collectionName);
        if (sourceId != null) metadata.put(GraphConstants.META_SOURCE_ID, sourceId);
        return doc;
    }

    private Document buildCalendarDocument(JsonNode event, String calendarId, String collectionName, String sourceId) {
        String eventId = event.has("id") ? event.get("id").asText() : UUID.randomUUID().toString();
        String summary = event.has("summary") ? event.get("summary").asText() : "(no title)";

        StringBuilder content = new StringBuilder();
        content.append("Event: ").append(summary).append("\n");

        JsonNode start = event.get("start");
        if (start != null) {
            String startTime = start.has("dateTime") ? start.get("dateTime").asText()
                    : start.has("date") ? start.get("date").asText() : null;
            if (startTime != null) content.append("Start: ").append(startTime).append("\n");
        }
        JsonNode end = event.get("end");
        if (end != null) {
            String endTime = end.has("dateTime") ? end.get("dateTime").asText()
                    : end.has("date") ? end.get("date").asText() : null;
            if (endTime != null) content.append("End: ").append(endTime).append("\n");
        }
        if (event.has("location")) content.append("Location: ").append(event.get("location").asText()).append("\n");
        if (event.has("description")) content.append("Description: ").append(event.get("description").asText()).append("\n");

        JsonNode organizer = event.get("organizer");
        if (organizer != null && organizer.has("email")) {
            content.append("Organizer: ");
            if (organizer.has("displayName")) content.append(organizer.get("displayName").asText()).append(" ");
            content.append("<").append(organizer.get("email").asText()).append(">\n");
        }

        JsonNode attendees = event.get("attendees");
        if (attendees != null && attendees.isArray() && !attendees.isEmpty()) {
            content.append("Attendees: ");
            List<String> names = new ArrayList<>();
            for (JsonNode att : attendees) {
                if (att.has("displayName")) names.add(att.get("displayName").asText());
                else if (att.has("email")) names.add(att.get("email").asText());
            }
            content.append(String.join(", ", names)).append("\n");
        }

        Document doc = new Document(content.toString());
        Map<String, Object> metadata = doc.getMetadata();
        String sourcePath = "gworkspace:calendar/" + calendarId + "/" + eventId;
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
        metadata.put(GraphConstants.META_FILE_NAME, summary);
        metadata.put(GraphConstants.META_LOADER, getName());
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "calendar_event");
        metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "calendar");
        metadata.put("gworkspace.calendar.eventId", eventId);
        metadata.put("gworkspace.calendar.calendarId", calendarId);
        metadata.put("gworkspace.calendar.summary", summary);
        if (event.has("description")) metadata.put("gworkspace.calendar.description", event.get("description").asText());
        if (event.has("location")) metadata.put("gworkspace.calendar.location", event.get("location").asText());
        if (event.has("status")) metadata.put("gworkspace.calendar.status", event.get("status").asText());
        if (event.has("htmlLink")) metadata.put("gworkspace.calendar.htmlLink", event.get("htmlLink").asText());
        if (event.has("recurringEventId")) metadata.put("gworkspace.calendar.recurringEventId", event.get("recurringEventId").asText());

        if (start != null) {
            String startTime = start.has("dateTime") ? start.get("dateTime").asText()
                    : start.has("date") ? start.get("date").asText() : null;
            if (startTime != null) metadata.put("gworkspace.calendar.startTime", startTime);
        }
        if (end != null) {
            String endTime = end.has("dateTime") ? end.get("dateTime").asText()
                    : end.has("date") ? end.get("date").asText() : null;
            if (endTime != null) metadata.put("gworkspace.calendar.endTime", endTime);
        }

        if (organizer != null) {
            if (organizer.has("email")) metadata.put("gworkspace.calendar.organizerEmail", organizer.get("email").asText());
            if (organizer.has("displayName")) metadata.put("gworkspace.calendar.organizerName", organizer.get("displayName").asText());
        }

        JsonNode creator = event.get("creator");
        if (creator != null) {
            if (creator.has("email")) metadata.put("gworkspace.calendar.creatorEmail", creator.get("email").asText());
            if (creator.has("displayName")) metadata.put("gworkspace.calendar.creatorName", creator.get("displayName").asText());
        }

        if (attendees != null && attendees.isArray()) {
            List<Map<String, String>> attendeeList = new ArrayList<>();
            for (JsonNode att : attendees) {
                Map<String, String> a = new LinkedHashMap<>();
                if (att.has("email")) a.put("email", att.get("email").asText());
                if (att.has("displayName")) a.put("name", att.get("displayName").asText());
                if (att.has("responseStatus")) a.put("responseStatus", att.get("responseStatus").asText());
                if (!a.isEmpty()) attendeeList.add(a);
            }
            metadata.put("gworkspace.calendar.attendees", attendeeList);
            metadata.put("gworkspace.calendar.attendeeCount", attendeeList.size());
        }

        // Recurrence rules (e.g. "RRULE:FREQ=WEEKLY")
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

        if (collectionName != null) metadata.put("collection_name", collectionName);
        if (sourceId != null) metadata.put(GraphConstants.META_SOURCE_ID, sourceId);
        return doc;
    }

    private Set<String> parseServices(Map<String, Object> meta) {
        String servicesStr = str(meta.get("services"));
        if (servicesStr == null || servicesStr.isEmpty()) {
            return Set.of("gmail", "drive", "calendar");
        }
        Set<String> services = new LinkedHashSet<>();
        for (String s : servicesStr.split(",")) {
            services.add(s.trim().toLowerCase());
        }
        return services;
    }

    private int pct(int currentStep, int totalSteps, double subProgress) {
        double stepSize = 90.0 / totalSteps;
        return (int) (5 + (currentStep - 1) * stepSize + subProgress * stepSize);
    }

    private void notifyProgress(Consumer<LoaderProgress> callback, String phase, int pct, String step) {
        if (callback != null) {
            callback.accept(new LoaderProgress(phase, pct, step, null, null));
        }
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    private static int intVal(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (NumberFormatException e) { return defaultValue; }
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
