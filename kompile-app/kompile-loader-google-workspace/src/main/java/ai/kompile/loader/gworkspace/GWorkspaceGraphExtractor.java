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

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import static ai.kompile.core.graphrag.GraphConstants.*;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.springframework.ai.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, rule-based graph extractor for Google Workspace documents.
 * <p>
 * Handles three services' metadata namespaces and produces a unified graph:
 *
 * <h3>Entity Types:</h3>
 * <ul>
 *   <li><b>GOOGLE_PERSON</b> — A person identified by email (unifies senders, recipients, owners, attendees)</li>
 *   <li><b>GMAIL_MESSAGE</b> — A Gmail message</li>
 *   <li><b>GMAIL_THREAD</b> — A Gmail thread (group of related messages)</li>
 *   <li><b>GMAIL_LABEL</b> — A Gmail label</li>
 *   <li><b>GMAIL_ATTACHMENT</b> — A file attached to a Gmail message</li>
 *   <li><b>DRIVE_FILE</b> — A Google Drive file</li>
 *   <li><b>DRIVE_FOLDER</b> — A Google Drive folder</li>
 *   <li><b>DRIVE_COMMENT</b> — A comment on a Drive file</li>
 *   <li><b>CALENDAR_EVENT</b> — A Google Calendar event</li>
 *   <li><b>CALENDAR</b> — A Google Calendar</li>
 * </ul>
 *
 * <h3>Relationship Types:</h3>
 * <ul>
 *   <li><b>SENT_BY</b> — Message → Person (sender)</li>
 *   <li><b>SENT_TO</b> — Message → Person (recipient)</li>
 *   <li><b>CC_TO</b> — Message → Person (CC recipient)</li>
 *   <li><b>BCC_TO</b> — Message → Person (BCC recipient)</li>
 *   <li><b>REPLIED_TO</b> — Message → Message (In-Reply-To)</li>
 *   <li><b>IN_THREAD</b> — Message → Thread</li>
 *   <li><b>HAS_LABEL</b> — Message → Label</li>
 *   <li><b>HAS_ATTACHMENT</b> — Message → Attachment</li>
 *   <li><b>OWNS_FILE</b> — Person → File</li>
 *   <li><b>SHARED_WITH</b> — File → Person</li>
 *   <li><b>LAST_MODIFIED_BY</b> — File → Person</li>
 *   <li><b>COMMENTED_ON</b> — Comment → File</li>
 *   <li><b>COMMENT_BY</b> — Comment → Person</li>
 *   <li><b>ORGANIZED_BY</b> — Event → Person (organizer)</li>
 *   <li><b>CREATED_BY</b> — Event → Person (creator)</li>
 *   <li><b>ATTENDED_BY</b> — Event → Person (attendee)</li>
 *   <li><b>IN_CALENDAR</b> — Event → Calendar</li>
 * </ul>
 *
 * <p>Entity IDs are deterministic: {@code UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))} where key
 * is an email address, message ID, file ID, etc. — enabling cross-document and cross-service
 * deduplication. A person who sends an email, owns a Drive file, and attends a Calendar event
 * is unified into a single GOOGLE_PERSON entity when their email matches.
 */
@Slf4j
@Component
public class GWorkspaceGraphExtractor implements DocumentGraphExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_URL_ENTITIES = 50;

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("gworkspace", "gmail", "gdrive", "gcalendar");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        // Match gworkspace.service for unified Google Workspace docs,
        // or source_type=GDRIVE for GoogleSheetsParser/GoogleDriveLoaderImpl docs
        // that don't set gworkspace.service
        return meta.get(META_GWORKSPACE_SERVICE) != null
                || "GDRIVE".equals(str(meta.get(META_SOURCE_TYPE)));
    }

    /**
     * Extract graph entities and relationships from a single Google Workspace document.
     * Dispatches to the appropriate handler based on {@code gworkspace.service} metadata.
     */
    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) return ExtractionResult.of(List.of(), List.of(), null);

        String service = str(meta.get(META_GWORKSPACE_SERVICE));

        // Fallback for docs from GoogleSheetsParser/GoogleDriveLoaderImpl that set
        // source_type=GDRIVE and gdrive_file_id but not gworkspace.service
        if (service == null && "GDRIVE".equals(str(meta.get(META_SOURCE_TYPE)))) {
            service = GWORKSPACE_SERVICE_DRIVE;
            // Copy gdrive_* keys to gworkspace.drive.* namespace for extractDrive compatibility
            if (meta.get("gworkspace.drive.fileId") == null && meta.get("gdrive_file_id") != null) {
                meta.put("gworkspace.drive.fileId", meta.get("gdrive_file_id"));
            }
            if (meta.get("gworkspace.drive.fileName") == null && meta.get("gdrive_file_name") != null) {
                meta.put("gworkspace.drive.fileName", meta.get("gdrive_file_name"));
            }
            if (meta.get("gworkspace.drive.mimeType") == null && meta.get("gdrive_mime_type") != null) {
                meta.put("gworkspace.drive.mimeType", meta.get("gdrive_mime_type"));
            }
            if (meta.get("gworkspace.drive.modifiedTime") == null && meta.get("gdrive_modified_time") != null) {
                meta.put("gworkspace.drive.modifiedTime", meta.get("gdrive_modified_time"));
            }
            if (meta.get("gworkspace.drive.webViewLink") == null && meta.get("gdrive_web_view_link") != null) {
                meta.put("gworkspace.drive.webViewLink", meta.get("gdrive_web_view_link"));
            }
            if (meta.get("gworkspace.drive.size") == null && meta.get("gdrive_size_bytes") != null) {
                meta.put("gworkspace.drive.size", meta.get("gdrive_size_bytes"));
            }
            if (meta.get("gworkspace.drive.createdTime") == null && meta.get("gdrive_created_time") != null) {
                meta.put("gworkspace.drive.createdTime", meta.get("gdrive_created_time"));
            }
            if (meta.get("gworkspace.drive.ownerEmails") == null && meta.get("gdrive_owner_emails") != null) {
                meta.put("gworkspace.drive.ownerEmails", meta.get("gdrive_owner_emails"));
            }
            if (meta.get("gworkspace.drive.ownerNames") == null && meta.get("gdrive_owner_names") != null) {
                meta.put("gworkspace.drive.ownerNames", meta.get("gdrive_owner_names"));
            }
            if (meta.get("gworkspace.drive.lastModifierEmail") == null && meta.get("gdrive_last_modifier_email") != null) {
                meta.put("gworkspace.drive.lastModifierEmail", meta.get("gdrive_last_modifier_email"));
            }
            if (meta.get("gworkspace.drive.lastModifierName") == null && meta.get("gdrive_last_modifier_name") != null) {
                meta.put("gworkspace.drive.lastModifierName", meta.get("gdrive_last_modifier_name"));
            }
            if (meta.get("gworkspace.drive.permissions") == null && meta.get("gdrive_permissions") != null) {
                meta.put("gworkspace.drive.permissions", meta.get("gdrive_permissions"));
            }
        }

        if (service == null) return ExtractionResult.of(List.of(), List.of(), null);

        Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        if (GWORKSPACE_SERVICE_GMAIL.equals(service)) {
            extractGmail(doc, meta, entities, relations);
        } else if (GWORKSPACE_SERVICE_DRIVE.equals(service)) {
            extractDrive(doc, meta, entities, relations);
        } else if (GWORKSPACE_SERVICE_DRIVE_COMMENT.equals(service)) {
            extractDriveComment(meta, entities, relations);
        } else if (GWORKSPACE_SERVICE_CALENDAR.equals(service)) {
            extractCalendar(doc, meta, entities, relations);
        }

        String sourceId = str(meta.get(META_SOURCE_ID));
        String chunkId = str(meta.get("gworkspace.gmail.messageId"));
        if (chunkId == null) chunkId = str(meta.get("gworkspace.drive.fileId"));
        if (chunkId == null) chunkId = str(meta.get("gworkspace.calendar.eventId"));

        ExtractionMetadata extractionMeta = ExtractionMetadata.forChunk(
                chunkId, sourceId, "gworkspace-rule-extractor"
        );

        return ExtractionResult.of(
                new ArrayList<>(entities.values()),
                relations,
                extractionMeta
        );
    }

    /**
     * Extract and merge graphs from a batch of Google Workspace documents.
     * Entities with the same ID are merged (aliases unioned, higher confidence kept).
     * Cross-service entity unification happens automatically via deterministic email-based IDs.
     */
    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        Map<String, ExtractedEntity> mergedEntities = new LinkedHashMap<>();
        List<ExtractedRelation> allRelations = new ArrayList<>();

        for (Document doc : docs) {
            ExtractionResult result = extract(doc);
            for (ExtractedEntity entity : result.entities()) {
                addEntity(mergedEntities, entity);
            }
            allRelations.addAll(result.relations());
        }

        // Deduplicate relations
        Set<String> seen = new HashSet<>();
        List<ExtractedRelation> uniqueRelations = allRelations.stream()
                .filter(r -> seen.add(r.source() + "|" + r.target() + "|" + r.type()))
                .collect(Collectors.toCollection(ArrayList::new));

        return ExtractionResult.of(
                new ArrayList<>(mergedEntities.values()),
                uniqueRelations,
                ExtractionMetadata.forChunk(null, null, "gworkspace-rule-extractor")
        );
    }

    // ========== Gmail extraction ==========

    private void extractGmail(Document doc, Map<String, Object> meta,
                              Map<String, ExtractedEntity> entities, List<ExtractedRelation> relations) {
        String messageId = str(meta.get("gworkspace.gmail.messageId"));
        String threadId = str(meta.get("gworkspace.gmail.threadId"));
        String subject = str(meta.get("gworkspace.gmail.subject"));
        String from = str(meta.get("gworkspace.gmail.from"));
        String to = str(meta.get("gworkspace.gmail.to"));
        String cc = str(meta.get("gworkspace.gmail.cc"));
        String bcc = str(meta.get("gworkspace.gmail.bcc"));
        String date = str(meta.get("gworkspace.gmail.date"));
        String internalDate = convertEpochMillisToIso(meta.get("gworkspace.gmail.internalDate"));
        String inReplyTo = str(meta.get("gworkspace.gmail.inReplyTo"));
        String rfc822MessageId = str(meta.get("gworkspace.gmail.rfc822MessageId"));
        String listId = str(meta.get("gworkspace.gmail.listId"));

        // -- Message entity --
        if (messageId != null) {
            Map<String, String> msgProps = new LinkedHashMap<>();
            msgProps.put("messageId", messageId);
            if (subject != null) msgProps.put("subject", subject);
            if (date != null) msgProps.put("date", date);
            if (internalDate != null) msgProps.put("internalDate", internalDate);
            if (rfc822MessageId != null) msgProps.put("rfc822MessageId", rfc822MessageId);
            String autoSubmitted = str(meta.get(GraphConstants.META_GMAIL_AUTO_SUBMITTED));
            if (autoSubmitted != null) msgProps.put(GraphConstants.PROP_AUTO_SUBMITTED, autoSubmitted);
            Object isAutoReply = meta.get(GraphConstants.META_GMAIL_IS_AUTO_REPLY);
            if (Boolean.TRUE.equals(isAutoReply)) msgProps.put(GraphConstants.PROP_IS_AUTO_REPLY, "true");
            Object attachmentCount = meta.get("gworkspace.gmail.attachmentCount");
            if (attachmentCount != null) msgProps.put("attachmentCount", attachmentCount.toString());
            Object attachmentNames = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES);
            if (attachmentNames != null) msgProps.put("attachmentNames", attachmentNames.toString());

            String msgContent = doc.getText();
            String description = msgContent != null && msgContent.length() > 200
                    ? msgContent.substring(0, 200) + "..."
                    : msgContent;

            addEntity(entities, new ExtractedEntity(
                    entityId("gmail:" + messageId),
                    subject != null ? subject : "Message " + messageId,
                    GraphConstants.ENTITY_GMAIL_MESSAGE,
                    List.of(),
                    description,
                    1.0,
                    msgProps
            ));

            // ── DATE entity from email date
            String emailDate = date != null ? date : internalDate;
            // Build a reusable relation properties map that carries occurredAt for all
            // relations produced from this Gmail message.
            final Map<String, String> emailRelProps = emailDate != null
                    ? Map.of(GraphConstants.PROP_OCCURRED_AT, emailDate) : Map.of();
            if (emailDate != null) {
                String emailDateId = entityId("date:" + emailDate);
                addEntity(entities, new ExtractedEntity(
                        emailDateId, emailDate, GraphConstants.ENTITY_DATE,
                        List.of(), "Email date: " + emailDate, 0.9,
                        Map.of("date", emailDate, "dateType", "sent")));
                relations.add(new ExtractedRelation(
                        entityId("gmail:" + messageId), emailDateId,
                        GraphConstants.REL_PUBLISHED_ON,
                        (subject != null ? subject : "Email") + " sent on " + emailDate,
                        0.9, emailRelProps));
            }

            // Message → Thread
            if (threadId != null) {
                addEntity(entities, new ExtractedEntity(
                        entityId("gmail-thread:" + threadId),
                        "Thread " + (subject != null ? subject : threadId),
                        GraphConstants.ENTITY_GMAIL_THREAD,
                        List.of(),
                        "Gmail conversation thread",
                        0.9,
                        Map.of("threadId", threadId)
                ));

                relations.add(new ExtractedRelation(
                        entityId("gmail:" + messageId),
                        entityId("gmail-thread:" + threadId),
                        GraphConstants.REL_IN_THREAD,
                        "Message belongs to thread",
                        1.0, emailRelProps
                ));
            }

            // Message → Sender (SENT_BY)
            if (from != null) {
                String email = extractEmail(from);
                String name = extractName(from);
                addPersonEntity(entities, email, name);

                relations.add(new ExtractedRelation(
                        entityId("gmail:" + messageId),
                        personEntityId(email),
                        GraphConstants.REL_SENT_BY,
                        "Message sent by " + (name != null ? name : email),
                        1.0, emailRelProps
                ));
            }

            // Message → Recipients (SENT_TO)
            if (to != null) {
                for (String recipient : parseAddressList(to)) {
                    String email = extractEmail(recipient);
                    String name = extractName(recipient);
                    addPersonEntity(entities, email, name);

                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + messageId),
                            personEntityId(email),
                            GraphConstants.REL_SENT_TO,
                            "Message sent to " + (name != null ? name : email),
                            1.0, emailRelProps
                    ));
                }
            }

            // Message → CC (CC_TO)
            if (cc != null) {
                for (String recipient : parseAddressList(cc)) {
                    String email = extractEmail(recipient);
                    String name = extractName(recipient);
                    addPersonEntity(entities, email, name);

                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + messageId),
                            personEntityId(email),
                            GraphConstants.REL_CC_TO,
                            "Message CC'd to " + (name != null ? name : email),
                            0.9, emailRelProps
                    ));
                }
            }

            // Message → BCC (BCC_TO)
            if (bcc != null) {
                for (String recipient : parseAddressList(bcc)) {
                    String email = extractEmail(recipient);
                    String name = extractName(recipient);
                    addPersonEntity(entities, email, name);

                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + messageId),
                            personEntityId(email),
                            GraphConstants.REL_BCC_TO,
                            "Message BCC'd to " + (name != null ? name : email),
                            0.9, emailRelProps
                    ));
                }
            }

            // Message → Reply-To (REPLY_TO)
            String replyTo = str(meta.get(GraphConstants.META_GMAIL_REPLY_TO));
            if (replyTo != null) {
                String replyToEmail = extractEmail(replyTo);
                String replyToName = extractName(replyTo);
                addPersonEntity(entities, replyToEmail, replyToName);
                msgProps.put(GraphConstants.PROP_REPLY_TO, replyTo);
                relations.add(new ExtractedRelation(
                        entityId("gmail:" + messageId),
                        personEntityId(replyToEmail),
                        GraphConstants.REL_REPLY_TO,
                        "Reply-To address: " + (replyToName != null ? replyToName : replyToEmail),
                        0.9, emailRelProps
                ));
            }

            // Message → In-Reply-To (REPLIED_TO)
            if (inReplyTo != null) {
                String replyMsgId = inReplyTo.replaceAll("[<>]", "").trim();
                addEntity(entities, new ExtractedEntity(
                        entityId("gmail-rfc:" + replyMsgId),
                        "Message " + replyMsgId,
                        GraphConstants.ENTITY_GMAIL_MESSAGE,
                        List.of(),
                        "Referenced message",
                        0.5,
                        Map.of("rfc822MessageId", replyMsgId)
                ));
                relations.add(new ExtractedRelation(
                        entityId("gmail:" + messageId),
                        entityId("gmail-rfc:" + replyMsgId),
                        GraphConstants.REL_REPLIED_TO,
                        "Message is a reply",
                        1.0, emailRelProps
                ));
            }

            // References chain (thread ancestry beyond immediate parent)
            Object refsObj = meta.get("gworkspace.gmail.references");
            List<String> references = null;
            if (refsObj instanceof List<?>) {
                references = ((List<?>) refsObj).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            } else if (refsObj instanceof String refStr) {
                references = List.of(refStr.trim().split("\\s+"));
            }
            if (references != null) {
                for (String ref : references) {
                    if (ref == null || ref.isBlank()) continue;
                    String cleanRef = ref.replaceAll("[<>]", "").trim();
                    // Skip the inReplyTo message — already handled above
                    if (inReplyTo != null && cleanRef.equals(inReplyTo.replaceAll("[<>]", "").trim())) continue;
                    String refEntityId = entityId("gmail-rfc:" + cleanRef);
                    addEntity(entities, new ExtractedEntity(
                            refEntityId, "Message " + cleanRef,
                            GraphConstants.ENTITY_GMAIL_MESSAGE, List.of(),
                            "Referenced message in thread",
                            0.7, Map.of("rfc822MessageId", cleanRef)
                    ));
                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + messageId),
                            refEntityId,
                            GraphConstants.REL_REFERENCES,
                            "Email references " + cleanRef,
                            0.9, emailRelProps
                    ));
                }
            }

            // Mailing list
            if (listId != null && !listId.isBlank()) {
                String listEntityId = entityId("gmail-list:" + listId);
                addEntity(entities, new ExtractedEntity(
                        listEntityId, listId,
                        GraphConstants.ENTITY_MAILING_LIST, List.of(),
                        "Mailing list: " + listId,
                        1.0, Map.of("listId", listId)
                ));
                relations.add(new ExtractedRelation(
                        entityId("gmail:" + messageId),
                        listEntityId,
                        GraphConstants.REL_POSTED_TO,
                        "Email posted to mailing list " + listId,
                        1.0, emailRelProps
                ));
            }

            // Labels — also extract system labels as boolean message properties
            Object labelIds = meta.get("gworkspace.gmail.labels");
            if (labelIds instanceof List<?> labels) {
                for (Object labelObj : labels) {
                    String label = str(labelObj);
                    if (label == null) continue;

                    // Detect system labels and store as message properties
                    switch (label) {
                        case GraphConstants.GMAIL_LABEL_STARRED   -> msgProps.put("isStarred", "true");
                        case GraphConstants.GMAIL_LABEL_IMPORTANT -> msgProps.put("isImportant", "true");
                        case GraphConstants.GMAIL_LABEL_UNREAD    -> msgProps.put("isUnread", "true");
                        case GraphConstants.GMAIL_LABEL_TRASH     -> msgProps.put("isTrashed", "true");
                        case GraphConstants.GMAIL_LABEL_SPAM      -> msgProps.put("isSpam", "true");
                        case GraphConstants.GMAIL_LABEL_DRAFT     -> msgProps.put("isDraft", "true");
                        case GraphConstants.GMAIL_LABEL_SENT      -> msgProps.put("isSent", "true");
                        case GraphConstants.GMAIL_LABEL_INBOX     -> msgProps.put("isInInbox", "true");
                        default -> { /* user-defined or category label — create entity below */ }
                    }

                    addEntity(entities, new ExtractedEntity(
                            entityId("gmail-label:" + label),
                            label,
                            GraphConstants.ENTITY_GMAIL_LABEL,
                            List.of(),
                            "Gmail label",
                            0.8,
                            Map.of("labelId", label)
                    ));

                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + messageId),
                            entityId("gmail-label:" + label),
                            GraphConstants.REL_HAS_LABEL,
                            "Message has label " + label,
                            1.0, emailRelProps
                    ));
                }
            }

            // Attachments
            Object attachmentsObj = meta.get("gworkspace.gmail.attachments");
            if (attachmentsObj instanceof List<?> attList) {
                for (Object attObj : attList) {
                    if (attObj instanceof Map<?, ?> attMap) {
                        String filename = str(attMap.get("filename"));
                        String attachmentId = str(attMap.get("attachmentId"));
                        String mimeType = str(attMap.get("mimeType"));
                        Object size = attMap.get("size");

                        String attKey = attachmentId != null ? attachmentId : filename;
                        if (attKey == null) continue;

                        Map<String, String> attProps = new LinkedHashMap<>();
                        if (filename != null) attProps.put("filename", filename);
                        if (mimeType != null) attProps.put("mimeType", mimeType);
                        if (size != null) attProps.put("size", size.toString());
                        if (attachmentId != null) attProps.put("attachmentId", attachmentId);

                        // Detect ICS calendar invites
                        if ("text/calendar".equalsIgnoreCase(mimeType)
                                || (filename != null && filename.toLowerCase().endsWith(".ics"))) {
                            addEntity(entities, new ExtractedEntity(
                                    entityId("gmail-cal:" + messageId + ":" + attKey),
                                    "Calendar: " + (filename != null ? filename : attKey),
                                    GraphConstants.ENTITY_CALENDAR_EVENT,
                                    List.of(),
                                    "Calendar invite from email " + messageId,
                                    1.0,
                                    attProps
                            ));
                            relations.add(new ExtractedRelation(
                                    entityId("gmail:" + messageId),
                                    entityId("gmail-cal:" + messageId + ":" + attKey),
                                    GraphConstants.REL_HAS_CALENDAR_EVENT,
                                    "Message has calendar invite",
                                    1.0, emailRelProps
                            ));
                        } else {
                            addEntity(entities, new ExtractedEntity(
                                    entityId("gmail-att:" + messageId + ":" + attKey),
                                    filename != null ? filename : "Attachment",
                                    GraphConstants.ENTITY_GMAIL_ATTACHMENT,
                                    List.of(),
                                    "Gmail attachment: " + (filename != null ? filename : attKey),
                                    1.0,
                                    attProps
                            ));

                            relations.add(new ExtractedRelation(
                                    entityId("gmail:" + messageId),
                                    entityId("gmail-att:" + messageId + ":" + attKey),
                                    GraphConstants.REL_HAS_ATTACHMENT,
                                    "Message has attachment " + (filename != null ? filename : ""),
                                    1.0, emailRelProps
                            ));
                        }
                    }
                }
            }
            // -- Inline URL extraction from message body --
            String msgBody = doc.getText();
            if (msgBody != null && !msgBody.isBlank()) {
                Matcher urlMatcher = URL_PATTERN.matcher(msgBody);
                Set<String> seenUrls = new HashSet<>();
                int urlCount = 0;
                while (urlMatcher.find() && urlCount < MAX_URL_ENTITIES) {
                    String url = urlMatcher.group();
                    if (!seenUrls.add(url.toLowerCase())) continue;
                    String urlEntityId = entityId("gws-url:" + messageId + ":" + url);
                    addEntity(entities, new ExtractedEntity(
                            urlEntityId, url,
                            GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                            List.of(),
                            "URL found in email body",
                            0.8,
                            Map.of("url", url)
                    ));
                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + messageId),
                            urlEntityId,
                            GraphConstants.REL_HYPERLINKS_TO,
                            "Email body links to " + url,
                            0.8, emailRelProps
                    ));
                    urlCount++;
                }
            }
        } else {
            // Attachment-only document: has parentMessageId but no messageId of its own
            String parentMessageId = str(meta.get("gworkspace.gmail.parentMessageId"));
            if (parentMessageId != null) {
                // Build relation props with occurredAt from the parent message date (if available).
                final String attachDate = date != null ? date : internalDate;
                final Map<String, String> attachRelProps = attachDate != null
                        ? Map.of(GraphConstants.PROP_OCCURRED_AT, attachDate) : Map.of();
                // Use parentSubject (set by crawler on attachment CrawlItems) for a
                // human-readable stub label; fall back to subject, then generic "Message <id>"
                String parentSubject = str(meta.get("gworkspace.gmail.parentSubject"));
                String stubLabel = parentSubject != null ? parentSubject
                        : (subject != null ? subject : "Message " + parentMessageId);
                Map<String, String> stubProps = new LinkedHashMap<>();
                stubProps.put("messageId", parentMessageId);
                if (parentSubject != null) stubProps.put("subject", parentSubject);
                // Create stub parent message entity so the attachment can link to it
                addEntity(entities, new ExtractedEntity(
                        entityId("gmail:" + parentMessageId),
                        stubLabel,
                        GraphConstants.ENTITY_GMAIL_MESSAGE,
                        List.of(),
                        "Parent message (stub for attachment)",
                        0.5,
                        stubProps
                ));

                // Thread linkage from parent if available
                if (threadId != null) {
                    addEntity(entities, new ExtractedEntity(
                            entityId("gmail-thread:" + threadId),
                            "Thread " + (subject != null ? subject : threadId),
                            GraphConstants.ENTITY_GMAIL_THREAD,
                            List.of(),
                            "Gmail conversation thread",
                            0.9,
                            Map.of("threadId", threadId)
                    ));
                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + parentMessageId),
                            entityId("gmail-thread:" + threadId),
                            GraphConstants.REL_IN_THREAD,
                            "Message belongs to thread",
                            1.0, attachRelProps
                    ));
                }

                // Create attachment entity from this document's own metadata
                String attFilename = str(meta.get("gworkspace.gmail.attachmentFilename"));
                String attMimeType = str(meta.get("gworkspace.gmail.attachmentMimeType"));
                String attId = str(meta.get("gworkspace.gmail.attachmentId"));
                String attKey = attId != null ? attId : attFilename;
                if (attKey == null) attKey = doc.getId();

                Map<String, String> attProps = new LinkedHashMap<>();
                if (attFilename != null) attProps.put("filename", attFilename);
                if (attMimeType != null) attProps.put("mimeType", attMimeType);
                if (attId != null) attProps.put("attachmentId", attId);
                Object attSize = meta.get("gworkspace.gmail.attachmentSize");
                if (attSize != null) attProps.put("size", attSize.toString());

                // Detect ICS calendar invites in standalone attachment documents
                if ("text/calendar".equalsIgnoreCase(attMimeType)
                        || (attFilename != null && attFilename.toLowerCase().endsWith(".ics"))) {
                    // Parse ICS fields from loaded attachment content
                    String icsBody = doc.getText();
                    if (icsBody != null && !icsBody.isBlank()) {
                        extractIcsFields(icsBody, attProps);
                    }

                    String calEntityId = entityId("gmail-cal:" + parentMessageId + ":" + attKey);
                    String calName = attProps.getOrDefault("summary",
                            attFilename != null ? attFilename : attKey);
                    addEntity(entities, new ExtractedEntity(
                            calEntityId,
                            calName,
                            GraphConstants.ENTITY_CALENDAR_EVENT,
                            List.of(),
                            "Calendar invite from email " + parentMessageId,
                            1.0,
                            attProps
                    ));
                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + parentMessageId),
                            calEntityId,
                            GraphConstants.REL_HAS_CALENDAR_EVENT,
                            "Message has calendar invite",
                            1.0, attachRelProps
                    ));
                    addIcsPersonEntities(calEntityId, attProps, entities, relations);
                } else {
                    addEntity(entities, new ExtractedEntity(
                            entityId("gmail-att:" + parentMessageId + ":" + attKey),
                            attFilename != null ? attFilename : "Attachment",
                            GraphConstants.ENTITY_GMAIL_ATTACHMENT,
                            List.of(),
                            "Gmail attachment: " + (attFilename != null ? attFilename : attKey),
                            1.0,
                            attProps
                    ));

                    relations.add(new ExtractedRelation(
                            entityId("gmail:" + parentMessageId),
                            entityId("gmail-att:" + parentMessageId + ":" + attKey),
                            GraphConstants.REL_HAS_ATTACHMENT,
                            "Message has attachment " + (attFilename != null ? attFilename : ""),
                            1.0, attachRelProps
                    ));
                }
            }
        }
    }

    // ========== Drive extraction ==========

    private void extractDrive(Document doc, Map<String, Object> meta,
                              Map<String, ExtractedEntity> entities, List<ExtractedRelation> relations) {
        String fileId = str(meta.get("gworkspace.drive.fileId"));
        String fileName = str(meta.get("gworkspace.drive.fileName"));
        String mimeType = str(meta.get("gworkspace.drive.mimeType"));
        String modifiedTime = str(meta.get("gworkspace.drive.modifiedTime"));
        String webViewLink = str(meta.get("gworkspace.drive.webViewLink"));

        if (fileId == null) return;

        String entityType = resolveEntityType(mimeType);
        boolean isFolder = GraphConstants.ENTITY_DRIVE_FOLDER.equals(entityType);

        String createdTime = str(meta.get("gworkspace.drive.createdTime"));
        Object fileSize = meta.get("gworkspace.drive.size");
        Object shared = meta.get("gworkspace.drive.shared");

        // Build relation props with occurredAt set to the best available file timestamp.
        final String fileTimestamp = createdTime != null ? createdTime : modifiedTime;
        final Map<String, String> fileRelProps = fileTimestamp != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, fileTimestamp) : Map.of();

        Map<String, String> fileProps = new LinkedHashMap<>();
        fileProps.put("fileId", fileId);
        if (fileName != null) fileProps.put(GraphConstants.META_FILE_NAME, fileName);
        if (mimeType != null) fileProps.put("mimeType", mimeType);
        if (modifiedTime != null) fileProps.put("modifiedTime", modifiedTime);
        if (createdTime != null) fileProps.put("createdTime", createdTime);
        if (webViewLink != null) fileProps.put("webViewLink", webViewLink);
        if (fileSize != null) fileProps.put("size", fileSize.toString());
        if (shared != null) fileProps.put("shared", shared.toString());
        String fileDescription = str(meta.get("gworkspace.drive.description"));
        if (fileDescription != null) fileProps.put("description", fileDescription);

        String entityDescription = descriptionForEntityType(entityType, fileName != null ? fileName : fileId);

        addEntity(entities, new ExtractedEntity(
                entityId("drive:" + fileId),
                fileName != null ? fileName : fileId,
                entityType,
                List.of(),
                entityDescription,
                1.0,
                fileProps
        ));

        // Owners → OWNS_FILE
        Object ownerEmails = meta.get("gworkspace.drive.ownerEmails");
        Object ownerNames = meta.get("gworkspace.drive.ownerNames");
        if (ownerEmails instanceof List<?> emailList) {
            List<?> nameList = ownerNames instanceof List<?> nl ? nl : List.of();
            for (int i = 0; i < emailList.size(); i++) {
                String email = str(emailList.get(i));
                String name = i < nameList.size() ? str(nameList.get(i)) : null;
                if (email == null) continue;

                addPersonEntity(entities, email, name);
                relations.add(new ExtractedRelation(
                        personEntityId(email),
                        entityId("drive:" + fileId),
                        GraphConstants.REL_OWNS_FILE,
                        (name != null ? name : email) + " owns file",
                        1.0, fileRelProps
                ));
            }
        }

        // Last modifier → LAST_MODIFIED_BY
        String lastModifierEmail = str(meta.get("gworkspace.drive.lastModifierEmail"));
        String lastModifierName = str(meta.get("gworkspace.drive.lastModifierName"));
        if (lastModifierEmail != null) {
            addPersonEntity(entities, lastModifierEmail, lastModifierName);
            relations.add(new ExtractedRelation(
                    entityId("drive:" + fileId),
                    personEntityId(lastModifierEmail),
                    GraphConstants.REL_LAST_MODIFIED_BY,
                    "File last modified by " + (lastModifierName != null ? lastModifierName : lastModifierEmail),
                    1.0, fileRelProps
            ));
        }

        // Permissions → SHARED_WITH
        Object permissions = meta.get("gworkspace.drive.permissions");
        if (permissions instanceof List<?> permList) {
            for (Object permObj : permList) {
                if (permObj instanceof Map<?, ?> perm) {
                    String email = str(perm.get("email"));
                    String name = str(perm.get("name"));
                    String role = str(perm.get("role"));
                    String type = str(perm.get("type"));

                    // Skip "anyone" or "domain" type permissions (not individual people)
                    if ("anyone".equals(type) || "domain".equals(type)) continue;
                    if (email == null) continue;

                    addPersonEntity(entities, email, name);
                    Map<String, String> sharedRelProps = new java.util.LinkedHashMap<>(fileRelProps);
                    if (role != null) sharedRelProps.put("role", role);
                    relations.add(new ExtractedRelation(
                            entityId("drive:" + fileId),
                            personEntityId(email),
                            GraphConstants.REL_SHARED_WITH,
                            "File shared with " + (name != null ? name : email)
                                    + (role != null ? " (" + role + ")" : ""),
                            0.9,
                            sharedRelProps
                    ));
                }
            }
        }

        // Parent folder → IN_FOLDER
        String parentFolderId = str(meta.get("gworkspace.drive.parentFolderId"));
        String parentFolderName = str(meta.get("gworkspace.drive.parentFolderName"));
        if (parentFolderId != null) {
            String folderLabel = parentFolderName != null ? parentFolderName : "Folder " + parentFolderId;
            addEntity(entities, new ExtractedEntity(
                    entityId("drive:" + parentFolderId),
                    folderLabel,
                    GraphConstants.ENTITY_DRIVE_FOLDER,
                    List.of(),
                    "Google Drive folder: " + folderLabel,
                    0.8,
                    Map.of("fileId", parentFolderId)
            ));
            relations.add(new ExtractedRelation(
                    entityId("drive:" + fileId),
                    entityId("drive:" + parentFolderId),
                    GraphConstants.REL_IN_FOLDER,
                    (fileName != null ? fileName : fileId) + " is in folder " + folderLabel,
                    1.0, fileRelProps
            ));
        }

        // ── DATE entities for creation/modification timestamps ──
        if (createdTime != null && !createdTime.isBlank()) {
            String createdDateId = entityId("date:" + createdTime);
            addEntity(entities, new ExtractedEntity(createdDateId, createdTime,
                    GraphConstants.ENTITY_DATE, List.of(),
                    "Drive file created: " + createdTime, 0.85,
                    Map.of("date", createdTime, "dateType", "created")));
            relations.add(new ExtractedRelation(entityId("drive:" + fileId), createdDateId,
                    GraphConstants.REL_PUBLISHED_ON,
                    (fileName != null ? fileName : fileId) + " created on " + createdTime, 0.85, fileRelProps));
        }
        if (modifiedTime != null && !modifiedTime.isBlank()) {
            String modDateId = entityId("date:" + modifiedTime);
            addEntity(entities, new ExtractedEntity(modDateId, modifiedTime,
                    GraphConstants.ENTITY_DATE, List.of(),
                    "Drive file modified: " + modifiedTime, 0.85,
                    Map.of("date", modifiedTime, "dateType", "modified")));
            relations.add(new ExtractedRelation(entityId("drive:" + fileId), modDateId,
                    GraphConstants.REL_MODIFIED_ON,
                    (fileName != null ? fileName : fileId) + " modified on " + modifiedTime, 0.85, fileRelProps));
        }

        // ── webViewLink as EXTERNAL_RESOURCE ──
        if (webViewLink != null && !webViewLink.isBlank()) {
            String webLinkId = entityId("url:" + webViewLink.toLowerCase());
            addEntity(entities, new ExtractedEntity(webLinkId, webViewLink,
                    GraphConstants.ENTITY_EXTERNAL_RESOURCE, List.of(),
                    "Web view link for Drive file: " + (fileName != null ? fileName : fileId), 0.85,
                    Map.of("url", webViewLink)));
            relations.add(new ExtractedRelation(entityId("drive:" + fileId), webLinkId,
                    GraphConstants.REL_HYPERLINKS_TO,
                    (fileName != null ? fileName : fileId) + " viewable at " + webViewLink, 0.85, fileRelProps));
        }

        // ── SPREADSHEET_SHEET from Google Sheets per-sheet documents ──
        String sheetName = str(meta.get(META_SHEET_NAME));
        Object sheetIdObj = meta.get(META_SHEET_ID);
        if (sheetName != null && GraphConstants.ENTITY_SPREADSHEET.equals(entityType)) {
            String sheetEntityId = entityId("gsheet:" + fileId + "/sheet:" + sheetName);
            Map<String, String> sheetProps = new LinkedHashMap<>();
            sheetProps.put("sheetName", sheetName);
            if (sheetIdObj != null) sheetProps.put("sheetId", sheetIdObj.toString());
            Object sheetIdx = meta.get(META_SHEET_INDEX);
            if (sheetIdx != null) sheetProps.put("sheetIndex", sheetIdx.toString());
            Object rowCount = meta.get(META_TABLE_ROW_COUNT);
            if (rowCount != null) sheetProps.put("rowCount", rowCount.toString());
            Object colCount = meta.get(META_TABLE_COLUMN_COUNT);
            if (colCount != null) sheetProps.put("columnCount", colCount.toString());
            String headersStr = str(meta.get(META_TABLE_HEADERS));
            if (headersStr != null) sheetProps.put("headers", headersStr);
            String gsheetSummary = str(meta.get("table_summary"));
            if (gsheetSummary != null) sheetProps.put("summary", gsheetSummary);

            addEntity(entities, new ExtractedEntity(
                    sheetEntityId, sheetName,
                    GraphConstants.ENTITY_SPREADSHEET_SHEET,
                    List.of(),
                    "Google Sheets tab: " + sheetName,
                    0.9,
                    sheetProps
            ));
            relations.add(new ExtractedRelation(
                    entityId("drive:" + fileId),
                    sheetEntityId,
                    GraphConstants.REL_HAS_SHEET,
                    (fileName != null ? fileName : fileId) + " has sheet " + sheetName,
                    1.0, fileRelProps
            ));
        }

        // ── Charts from gsheet.charts metadata ──
        Object chartsObj = meta.get("gsheet.charts");
        if (chartsObj instanceof List<?> chartsList) {
            for (Object chartObj : chartsList) {
                if (!(chartObj instanceof Map<?, ?> chartMap)) continue;
                String chartTitle = chartMap.get("title") != null ? chartMap.get("title").toString() : null;
                String chartType = chartMap.get("chartType") != null ? chartMap.get("chartType").toString() : null;
                String chartId = chartMap.get("chartId") != null ? chartMap.get("chartId").toString() : null;
                String chartSheetName = chartMap.get("sheet") != null ? chartMap.get("sheet").toString() : null;
                String chartLabel = chartTitle != null ? chartTitle : ("Chart" + (chartId != null ? " #" + chartId : ""));
                String chartEntityId = entityId("gsheet:" + fileId + "/chart:" + (chartId != null ? chartId : chartLabel));
                Map<String, String> chartProps = new LinkedHashMap<>();
                if (chartTitle != null) chartProps.put("title", chartTitle);
                if (chartType != null) chartProps.put("chartType", chartType);
                if (chartId != null) chartProps.put("chartId", chartId);
                if (chartSheetName != null) chartProps.put("sheet", chartSheetName);
                addEntity(entities, new ExtractedEntity(
                        chartEntityId, chartLabel, GraphConstants.ENTITY_CHART,
                        null, "Google Sheets chart: " + chartLabel, 0.85, chartProps));
                // Link chart to parent spreadsheet
                relations.add(new ExtractedRelation(
                        entityId("drive:" + fileId), chartEntityId,
                        GraphConstants.REL_HAS_CHART,
                        (fileName != null ? fileName : fileId) + " has chart " + chartLabel,
                        0.9, fileRelProps));
                // Link chart to its sheet if known
                if (chartSheetName != null) {
                    String chartSheetId = entityId("gsheet:" + fileId + "/sheet:" + chartSheetName);
                    relations.add(new ExtractedRelation(
                            chartSheetId, chartEntityId, GraphConstants.REL_CONTAINS,
                            chartSheetName + " contains chart " + chartLabel,
                            0.85, fileRelProps));
                }
            }
        }

        // ── Named ranges from gsheet.namedRanges metadata ──
        Object namedRangesObj = meta.get("gsheet.namedRanges");
        if (namedRangesObj instanceof List<?> nrList) {
            for (Object nrObj : nrList) {
                if (!(nrObj instanceof Map<?, ?> nrMap)) continue;
                String nrName = nrMap.get("name") != null ? nrMap.get("name").toString() : null;
                if (nrName == null || nrName.isBlank()) continue;
                String nrEntityId = entityId("gsheet:" + fileId + "/namedrange:" + nrName);
                Map<String, String> nrProps = new LinkedHashMap<>();
                nrProps.put("name", nrName);
                if (nrMap.get("namedRangeId") != null) nrProps.put("namedRangeId", nrMap.get("namedRangeId").toString());
                if (nrMap.get("rowRange") != null) nrProps.put("rowRange", nrMap.get("rowRange").toString());
                if (nrMap.get("colRange") != null) nrProps.put("colRange", nrMap.get("colRange").toString());
                if (nrMap.get("sheetId") != null) nrProps.put("sheetId", nrMap.get("sheetId").toString());
                addEntity(entities, new ExtractedEntity(
                        nrEntityId, nrName, GraphConstants.ENTITY_NAMED_RANGE,
                        null, "Named range: " + nrName, 0.85, nrProps));
                relations.add(new ExtractedRelation(
                        entityId("drive:" + fileId), nrEntityId,
                        GraphConstants.REL_CONTAINS,
                        (fileName != null ? fileName : fileId) + " has named range " + nrName,
                        0.85, fileRelProps));
            }
        }

        // ── PRESENTATION_SLIDE from Google Slides per-slide documents ──
        String slideTitle = str(meta.get("pptx.slideTitle"));
        Object slideIdx = meta.get("pptx.slideIndex");
        if (slideTitle != null && GraphConstants.ENTITY_PRESENTATION.equals(entityType)) {
            String slideEntityId = entityId("gslide:" + fileId + "/slide:" + slideIdx);
            Map<String, String> slideProps = new LinkedHashMap<>();
            slideProps.put("slideTitle", slideTitle);
            if (slideIdx != null) slideProps.put("slideIndex", slideIdx.toString());
            Object totalSlides = meta.get("pptx.totalSlides");
            if (totalSlides != null) slideProps.put("totalSlides", totalSlides.toString());
            String slideObjId = str(meta.get("slideObjectId"));
            if (slideObjId != null) slideProps.put("slideObjectId", slideObjId);

            addEntity(entities, new ExtractedEntity(
                    slideEntityId, slideTitle,
                    GraphConstants.ENTITY_PRESENTATION_SLIDE,
                    List.of(),
                    "Google Slides slide: " + slideTitle,
                    0.95,
                    slideProps
            ));
            relations.add(new ExtractedRelation(
                    entityId("drive:" + fileId),
                    slideEntityId,
                    GraphConstants.REL_HAS_SLIDE,
                    (fileName != null ? fileName : fileId) + " has slide " + slideTitle,
                    1.0, fileRelProps
            ));

            // Speaker notes
            String speakerNotes = str(meta.get(META_SPEAKER_NOTES));
            if (speakerNotes != null && !speakerNotes.isBlank()) {
                String noteEntityId = entityId("gslide_note:" + fileId + "/slide:" + slideIdx);
                Map<String, String> noteProps = new LinkedHashMap<>();
                noteProps.put("text", speakerNotes.length() > 2000
                        ? speakerNotes.substring(0, 2000) : speakerNotes);
                noteProps.put("slideTitle", slideTitle);
                String noteLabel = speakerNotes.length() > 80
                        ? speakerNotes.substring(0, 80) + "..." : speakerNotes;
                addEntity(entities, new ExtractedEntity(
                        noteEntityId, "Notes: " + noteLabel,
                        GraphConstants.ENTITY_SPEAKER_NOTE,
                        null, "Speaker notes on " + slideTitle, 0.9, noteProps));
                relations.add(new ExtractedRelation(
                        slideEntityId, noteEntityId,
                        GraphConstants.REL_HAS_SPEAKER_NOTE,
                        slideTitle + " has speaker notes", 0.9, fileRelProps));
            }

            // Slide hyperlinks
            Object slideLinks = meta.get("pptx.slideHyperlinks");
            if (slideLinks instanceof List<?> linkList) {
                int linkIdx = 0;
                for (Object linkObj : linkList) {
                    if (!(linkObj instanceof Map<?, ?> linkMap)) continue;
                    String url = linkMap.get("url") != null ? linkMap.get("url").toString() : null;
                    String linkText = linkMap.get("text") != null ? linkMap.get("text").toString() : null;
                    if (url == null) continue;
                    String urlEntityId = entityId("url:" + url.toLowerCase());
                    Map<String, String> urlProps = new LinkedHashMap<>();
                    urlProps.put("url", url);
                    if (linkText != null) urlProps.put("anchorText", linkText);
                    addEntity(entities, new ExtractedEntity(
                            urlEntityId, linkText != null ? linkText : url,
                            GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                            null, "Hyperlink in slide: " + url, 0.85, urlProps));
                    relations.add(new ExtractedRelation(
                            slideEntityId, urlEntityId,
                            GraphConstants.REL_HYPERLINKS_TO,
                            slideTitle + " links to " + url, 0.85, fileRelProps));
                    linkIdx++;
                }
            }

            // Slide images
            Object slideImages = meta.get("pptx.slideImages");
            if (slideImages instanceof List<?> imageList) {
                int imgIdx = 0;
                for (Object imgObj : imageList) {
                    if (!(imgObj instanceof Map<?, ?> imgMap)) continue;
                    String altText = imgMap.get("altText") != null ? imgMap.get("altText").toString() : null;
                    String sourceUrl = imgMap.get("sourceUrl") != null ? imgMap.get("sourceUrl").toString() : null;
                    String imgLabel = altText != null ? altText : "Image " + (imgIdx + 1);
                    String imgEntityId = entityId("gslide_img:" + fileId + "/slide:" + slideIdx + "/img:" + imgIdx);
                    Map<String, String> imgProps = new LinkedHashMap<>();
                    if (altText != null) imgProps.put("altText", altText);
                    if (sourceUrl != null) imgProps.put("sourceUrl", sourceUrl);
                    addEntity(entities, new ExtractedEntity(
                            imgEntityId, imgLabel,
                            GraphConstants.ENTITY_SLIDE_IMAGE,
                            null, "Image on " + slideTitle, 0.8, imgProps));
                    relations.add(new ExtractedRelation(
                            slideEntityId, imgEntityId,
                            GraphConstants.REL_CONTAINS,
                            slideTitle + " contains image " + imgLabel, 0.8, fileRelProps));
                    imgIdx++;
                }
            }

            // Slide bullets
            Object slideBullets = meta.get("pptx.slideBullets");
            if (slideBullets instanceof List<?> bulletList) {
                int bulletIdx = 0;
                for (Object bulletObj : bulletList) {
                    if (!(bulletObj instanceof Map<?, ?> bulletMap)) continue;
                    String bulletText = bulletMap.get("text") != null ? bulletMap.get("text").toString() : null;
                    if (bulletText == null || bulletText.isBlank()) continue;
                    String bulletEntityId = entityId("gslide_bullet:" + fileId + "/slide:" + slideIdx + "/b:" + bulletIdx);
                    addEntity(entities, new ExtractedEntity(
                            bulletEntityId, bulletText.length() > 100 ? bulletText.substring(0, 100) + "..." : bulletText,
                            GraphConstants.ENTITY_SLIDE_BULLET,
                            null, "Bullet point on " + slideTitle, 0.75,
                            Map.of("text", bulletText, "bulletIndex", String.valueOf(bulletIdx))));
                    relations.add(new ExtractedRelation(
                            slideEntityId, bulletEntityId,
                            GraphConstants.REL_CONTAINS,
                            slideTitle + " contains bullet point", 0.75, fileRelProps));
                    bulletIdx++;
                }
            }

            // Slide layout
            String slideLayout = str(meta.get(META_SLIDE_LAYOUT));
            if (slideLayout != null && !slideLayout.isBlank()) {
                String layoutId = entityId("gslide_layout:" + fileId + ":" + slideLayout.toLowerCase());
                addEntity(entities, new ExtractedEntity(
                        layoutId, slideLayout, GraphConstants.ENTITY_SLIDE_LAYOUT,
                        null, "Slide layout: " + slideLayout, 0.8,
                        Map.of("layoutName", slideLayout)));
                relations.add(new ExtractedRelation(
                        slideEntityId, layoutId,
                        GraphConstants.REL_USES_LAYOUT,
                        slideTitle + " uses layout: " + slideLayout, 0.8, fileRelProps));
            }
        }

        // ── Cell-level table graph from META_TABLE_GRAPH ──
        // GoogleSheetsParser/GoogleSlidesParser sets this; deserialize and import
        // into the knowledge graph so cell-level querying and linking work.
        Object tableGraphObj = meta.get(META_TABLE_GRAPH);
        if (tableGraphObj instanceof String tableGraphJson && !((String) tableGraphObj).isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ai.kompile.core.graphrag.model.Graph cellGraph = mapper.readValue(tableGraphJson,
                        ai.kompile.core.graphrag.model.Graph.class);
                if (cellGraph.getEntities() != null) {
                    for (ai.kompile.core.graphrag.model.Entity e : cellGraph.getEntities()) {
                        if (e == null || e.getId() == null || e.getTitle() == null || e.getType() == null) {
                            log.debug("Skipping table graph entity with null id/title/type: {}", e);
                            continue;
                        }
                        Map<String, String> props = new LinkedHashMap<>();
                        if (e.getMetadata() != null) {
                            e.getMetadata().forEach((k, v) -> {
                                if (v != null) props.put(k, v.toString());
                            });
                        }
                        addEntity(entities, new ExtractedEntity(
                                e.getId(), e.getTitle(), e.getType(),
                                null, e.getDescription(),
                                e.getConfidence() != null ? e.getConfidence() : 0.8, props));
                    }
                }
                if (cellGraph.getRelationships() != null) {
                    for (ai.kompile.core.graphrag.model.Relationship r : cellGraph.getRelationships()) {
                        if (r == null || r.getSource() == null || r.getTarget() == null || r.getType() == null) {
                            log.debug("Skipping table graph relationship with null source/target/type: {}", r);
                            continue;
                        }
                        relations.add(new ExtractedRelation(
                                r.getSource(), r.getTarget(), r.getType(),
                                r.getDescription(),
                                r.getWeight() != null ? r.getWeight() : 0.8, null));
                    }
                }
            } catch (Exception e) {
                // Failed to parse tableGraph JSON — not fatal, log and continue
            }
        }

        // ── FORMULA entities from Google Sheets formula_graph documents ──
        String contentType = str(meta.get(META_CONTENT_TYPE));
        String formulasStr = str(meta.get(META_FORMULAS));
        if (CONTENT_TYPE_FORMULA_GRAPH.equals(contentType) && formulasStr != null && !formulasStr.isBlank()) {
            String[] formulas = formulasStr.split(";\\s*");
            int formulaCap = Math.min(formulas.length, 100);
            for (int fi = 0; fi < formulaCap; fi++) {
                String formula = formulas[fi].trim();
                if (formula.isEmpty()) continue;

                // Parse "SheetName!CellRef=FORMULA" format
                int eqIdx = formula.indexOf('=');
                String cellRef = eqIdx > 0 ? formula.substring(0, eqIdx).trim() : formula;
                String formulaText = eqIdx > 0 ? formula.substring(eqIdx + 1).trim() : "";

                // Extract sheet name from "Sheet!Cell" if present
                int bangIdx = cellRef.indexOf('!');
                String formulaSheetName = bangIdx > 0 ? cellRef.substring(0, bangIdx) : null;

                String formulaEntityId = entityId("gsheet_formula:" + fileId + ":" + cellRef);
                Map<String, String> formulaProps = new LinkedHashMap<>();
                formulaProps.put("cellRef", cellRef);
                if (!formulaText.isEmpty()) formulaProps.put("formula", formulaText);
                if (formulaSheetName != null) formulaProps.put("sheetName", formulaSheetName);

                String formulaLabel = cellRef + "=" + (formulaText.length() > 60
                        ? formulaText.substring(0, 60) + "..." : formulaText);
                addEntity(entities, new ExtractedEntity(
                        formulaEntityId, formulaLabel, GraphConstants.ENTITY_FORMULA_CELL,
                        List.of(), "Google Sheets formula: " + formulaLabel, 0.85, formulaProps
                ));
                relations.add(new ExtractedRelation(
                        entityId("drive:" + fileId), formulaEntityId,
                        GraphConstants.REL_HAS_FORMULA,
                        (fileName != null ? fileName : fileId) + " has formula: " + cellRef,
                        0.85, fileRelProps
                ));
            }
        }

        // ── URL extraction from Drive file body text ──
        String bodyText = doc != null ? doc.getText() : null;
        if (bodyText != null && !bodyText.isBlank()) {
            Matcher urlMatcher = URL_PATTERN.matcher(bodyText);
            Set<String> seenUrls = new HashSet<>();
            int urlCount = 0;
            while (urlMatcher.find() && urlCount < MAX_URL_ENTITIES) {
                String url = urlMatcher.group();
                if (!seenUrls.add(url.toLowerCase())) continue;
                String urlEntityId = entityId("gws-url:" + fileId + ":" + url);
                addEntity(entities, new ExtractedEntity(
                        urlEntityId, url,
                        GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                        List.of(),
                        "URL found in Drive file content",
                        0.8,
                        Map.of("url", url)
                ));
                relations.add(new ExtractedRelation(
                        entityId("drive:" + fileId),
                        urlEntityId,
                        GraphConstants.REL_HYPERLINKS_TO,
                        "Drive file links to " + url,
                        0.8, fileRelProps
                ));
                urlCount++;
            }
        }
    }

    // ========== Drive comment extraction ==========

    private void extractDriveComment(Map<String, Object> meta,
                                     Map<String, ExtractedEntity> entities, List<ExtractedRelation> relations) {
        String fileId = str(meta.get("gworkspace.drive.fileId"));
        String fileName = str(meta.get("gworkspace.drive.fileName"));
        String commentId = str(meta.get("gworkspace.drive.commentId"));
        String content = str(meta.get("gworkspace.drive.commentContent"));
        String authorEmail = str(meta.get("gworkspace.drive.commentAuthorEmail"));
        String authorName = str(meta.get("gworkspace.drive.commentAuthorName"));
        String createdTime = str(meta.get("gworkspace.drive.commentCreatedTime"));

        if (commentId == null || fileId == null) return;

        // Build relation props with occurredAt set to the comment creation time.
        final Map<String, String> commentRelProps = createdTime != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, createdTime) : Map.of();

        // Comment entity
        Map<String, String> commentProps = new LinkedHashMap<>();
        commentProps.put("commentId", commentId);
        commentProps.put("fileId", fileId);
        if (createdTime != null) commentProps.put("createdTime", createdTime);
        Object resolved = meta.get("gworkspace.drive.commentResolved");
        if (resolved != null) commentProps.put("resolved", resolved.toString());
        Object commentReplyCount = meta.get("gworkspace.drive.commentReplyCount");
        if (commentReplyCount != null) commentProps.put("replyCount", commentReplyCount.toString());

        String description = content != null && content.length() > 200
                ? content.substring(0, 200) + "..."
                : content;

        addEntity(entities, new ExtractedEntity(
                entityId("drive-comment:" + commentId),
                "Comment on " + (fileName != null ? fileName : fileId),
                GraphConstants.ENTITY_DRIVE_COMMENT,
                List.of(),
                description,
                1.0,
                commentProps
        ));

        // File entity (stub if not seen)
        addEntity(entities, new ExtractedEntity(
                entityId("drive:" + fileId),
                fileName != null ? fileName : fileId,
                GraphConstants.ENTITY_DRIVE_FILE,
                List.of(),
                "Google Drive file",
                0.5,
                Map.of("fileId", fileId)
        ));

        // Comment → File (COMMENTED_ON)
        relations.add(new ExtractedRelation(
                entityId("drive-comment:" + commentId),
                entityId("drive:" + fileId),
                GraphConstants.REL_COMMENTED_ON,
                "Comment on file " + (fileName != null ? fileName : fileId),
                1.0, commentRelProps
        ));

        // Comment → Author (COMMENT_BY)
        if (authorEmail != null) {
            addPersonEntity(entities, authorEmail, authorName);
            relations.add(new ExtractedRelation(
                    entityId("drive-comment:" + commentId),
                    personEntityId(authorEmail),
                    GraphConstants.REL_COMMENT_BY,
                    "Comment by " + (authorName != null ? authorName : authorEmail),
                    1.0, commentRelProps
            ));
        }

        // ── Comment DATE entity ──────────────────────────────────
        if (createdTime != null && !createdTime.isBlank()) {
            String commentDateId = entityId("date:" + createdTime);
            addEntity(entities, new ExtractedEntity(commentDateId, createdTime,
                    GraphConstants.ENTITY_DATE, List.of(),
                    "Comment date: " + createdTime, 0.85,
                    Map.of("date", createdTime, "dateType", "commentCreated")));
            relations.add(new ExtractedRelation(entityId("drive-comment:" + commentId), commentDateId,
                    GraphConstants.REL_PUBLISHED_ON,
                    "Comment published on " + createdTime, 0.85, commentRelProps));
        }

        // ── Replies on this comment ───────────────────────────────────
        Object repliesObj = meta.get("gworkspace.drive.commentReplies");
        if (repliesObj instanceof List<?> repliesList) {
            int replyIdx = 0;
            for (Object replyObj : repliesList) {
                if (replyObj instanceof Map<?, ?> replyMap) {
                    String replyId = replyMap.get("replyId") != null ? replyMap.get("replyId").toString() : String.valueOf(replyIdx);
                    String replyContent = replyMap.get("content") != null ? replyMap.get("content").toString() : null;
                    String replyCreatedTime = replyMap.get("createdTime") != null ? replyMap.get("createdTime").toString() : null;
                    String replyAction = replyMap.get("action") != null ? replyMap.get("action").toString() : null;
                    String replyAuthorEmail = replyMap.get("authorEmail") != null ? replyMap.get("authorEmail").toString() : null;
                    String replyAuthorName = replyMap.get("authorName") != null ? replyMap.get("authorName").toString() : null;

                    String replyEntityId = entityId("drive-reply:" + commentId + ":" + replyId);
                    final Map<String, String> replyRelProps = replyCreatedTime != null
                            ? Map.of(GraphConstants.PROP_OCCURRED_AT, replyCreatedTime) : Map.of();
                    Map<String, String> replyProps = new LinkedHashMap<>();
                    replyProps.put("replyId", replyId);
                    replyProps.put("commentId", commentId);
                    replyProps.put("fileId", fileId);
                    if (replyContent != null) replyProps.put("content", replyContent.length() > 500 ? replyContent.substring(0, 500) : replyContent);
                    if (replyCreatedTime != null) replyProps.put("createdTime", replyCreatedTime);
                    if (replyAction != null) replyProps.put("action", replyAction);
                    if (replyAuthorName != null) replyProps.put("authorName", replyAuthorName);

                    String replyDesc = replyContent != null && replyContent.length() > 200
                            ? replyContent.substring(0, 200) + "..." : replyContent;

                    addEntity(entities, new ExtractedEntity(
                            replyEntityId,
                            "Reply on comment " + commentId,
                            GraphConstants.ENTITY_DRIVE_COMMENT_REPLY,
                            List.of(),
                            replyDesc,
                            0.9,
                            replyProps
                    ));

                    // Reply → Comment (REPLIED_TO)
                    relations.add(new ExtractedRelation(
                            replyEntityId,
                            entityId("drive-comment:" + commentId),
                            GraphConstants.REL_REPLIED_TO,
                            "Reply to comment on " + (fileName != null ? fileName : fileId),
                            0.9, replyRelProps
                    ));

                    // Reply → Author (COMMENT_BY)
                    if (replyAuthorEmail != null) {
                        addPersonEntity(entities, replyAuthorEmail, replyAuthorName);
                        relations.add(new ExtractedRelation(
                                replyEntityId,
                                personEntityId(replyAuthorEmail),
                                GraphConstants.REL_COMMENT_BY,
                                "Reply by " + (replyAuthorName != null ? replyAuthorName : replyAuthorEmail),
                                0.9, replyRelProps
                        ));
                    }

                    // Reply DATE entity
                    if (replyCreatedTime != null && !replyCreatedTime.isBlank()) {
                        String replyDateId = entityId("date:" + replyCreatedTime);
                        addEntity(entities, new ExtractedEntity(replyDateId, replyCreatedTime,
                                GraphConstants.ENTITY_DATE, List.of(),
                                "Reply date: " + replyCreatedTime, 0.85,
                                Map.of("date", replyCreatedTime, "dateType", "replyCreated")));
                        relations.add(new ExtractedRelation(replyEntityId, replyDateId,
                                GraphConstants.REL_PUBLISHED_ON,
                                "Reply published on " + replyCreatedTime, 0.85, replyRelProps));
                    }
                }
                replyIdx++;
            }
        }
    }

    // ========== Calendar extraction ==========

    private void extractCalendar(Document doc, Map<String, Object> meta,
                                 Map<String, ExtractedEntity> entities, List<ExtractedRelation> relations) {
        String eventId = str(meta.get("gworkspace.calendar.eventId"));
        String calendarId = str(meta.get("gworkspace.calendar.calendarId"));
        String summary = str(meta.get("gworkspace.calendar.summary"));
        String description = str(meta.get("gworkspace.calendar.description"));
        String location = str(meta.get("gworkspace.calendar.location"));
        String startTime = str(meta.get("gworkspace.calendar.startTime"));
        String endTime = str(meta.get("gworkspace.calendar.endTime"));
        String status = str(meta.get("gworkspace.calendar.status"));

        if (eventId == null) return;

        // Build relation props with occurredAt set to the event start time (best available timestamp).
        final Map<String, String> calRelProps = startTime != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, startTime) : Map.of();

        // Event entity
        Map<String, String> eventProps = new LinkedHashMap<>();
        eventProps.put("eventId", eventId);
        if (summary != null) eventProps.put("summary", summary);
        if (startTime != null) eventProps.put("startTime", startTime);
        if (endTime != null) eventProps.put("endTime", endTime);
        if (location != null) eventProps.put("location", location);
        if (status != null) eventProps.put("status", status);
        String htmlLink = str(meta.get("gworkspace.calendar.htmlLink"));
        if (htmlLink != null) eventProps.put("htmlLink", htmlLink);
        String conferenceUrl = str(meta.get("gworkspace.calendar.conferenceUrl"));
        if (conferenceUrl != null) eventProps.put("conferenceUrl", conferenceUrl);
        Object attendeeCount = meta.get("gworkspace.calendar.attendeeCount");
        if (attendeeCount != null) eventProps.put("attendeeCount", attendeeCount.toString());
        // Recurrence rules (RRULE, RDATE, EXRULE, EXDATE)
        Object recurrenceObj = meta.get("gworkspace.calendar.recurrence");
        if (recurrenceObj instanceof List<?> recList && !recList.isEmpty()) {
            eventProps.put("recurrence", recList.stream()
                    .map(Object::toString).collect(java.util.stream.Collectors.joining(";")));
        }

        addEntity(entities, new ExtractedEntity(
                entityId("cal:" + eventId),
                summary != null ? summary : "Event " + eventId,
                GraphConstants.ENTITY_CALENDAR_EVENT,
                List.of(),
                description != null && description.length() > 200
                        ? description.substring(0, 200) + "..." : description,
                1.0,
                eventProps
        ));

        // ── DATE entities from event start/end times
        if (startTime != null) {
            String startDateId = entityId("date:" + startTime);
            addEntity(entities, new ExtractedEntity(
                    startDateId, startTime, GraphConstants.ENTITY_DATE,
                    List.of(), "Event start: " + startTime, 0.9,
                    Map.of("date", startTime, "dateType", "eventStart")));
            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId), startDateId,
                    GraphConstants.REL_STARTS_ON,
                    (summary != null ? summary : "Event") + " starts on " + startTime,
                    0.9, calRelProps));
        }
        if (endTime != null) {
            String endDateId = entityId("date:" + endTime);
            addEntity(entities, new ExtractedEntity(
                    endDateId, endTime, GraphConstants.ENTITY_DATE,
                    List.of(), "Event end: " + endTime, 0.9,
                    Map.of("date", endTime, "dateType", "eventEnd")));
            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId), endDateId,
                    GraphConstants.REL_ENDS_ON,
                    (summary != null ? summary : "Event") + " ends on " + endTime,
                    0.9, calRelProps));
        }

        // ── EXTERNAL_RESOURCE from htmlLink and conferenceUrl
        String calEventEntityId = entityId("cal:" + eventId);
        if (htmlLink != null && !htmlLink.isBlank()) {
            String htmlLinkId = entityId("url:" + htmlLink.toLowerCase());
            addEntity(entities, new ExtractedEntity(
                    htmlLinkId, htmlLink, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                    List.of(), "Calendar event link", 0.85,
                    Map.of("url", htmlLink)));
            relations.add(new ExtractedRelation(
                    calEventEntityId, htmlLinkId,
                    GraphConstants.REL_HYPERLINKS_TO,
                    (summary != null ? summary : "Event") + " link: " + htmlLink,
                    0.85, calRelProps));
        }
        if (conferenceUrl != null && !conferenceUrl.isBlank()) {
            String confUrlId = entityId("url:" + conferenceUrl.toLowerCase());
            addEntity(entities, new ExtractedEntity(
                    confUrlId, conferenceUrl, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                    List.of(), "Conference/meeting URL", 0.9,
                    Map.of("url", conferenceUrl)));
            relations.add(new ExtractedRelation(
                    calEventEntityId, confUrlId,
                    GraphConstants.REL_HYPERLINKS_TO,
                    (summary != null ? summary : "Event") + " conference: " + conferenceUrl,
                    0.9, calRelProps));
        }

        // Calendar entity
        if (calendarId != null) {
            addEntity(entities, new ExtractedEntity(
                    entityId("calendar:" + calendarId),
                    calendarId,
                    GraphConstants.ENTITY_CALENDAR,
                    List.of(),
                    "Google Calendar",
                    0.8,
                    Map.of("calendarId", calendarId)
            ));

            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId),
                    entityId("calendar:" + calendarId),
                    GraphConstants.REL_IN_CALENDAR,
                    "Event in calendar " + calendarId,
                    1.0, calRelProps
            ));
        }

        // Location entity
        if (location != null && !location.isBlank()) {
            String locEntityId = entityId("location:" + location.toLowerCase());
            addEntity(entities, new ExtractedEntity(
                    locEntityId, location, GraphConstants.ENTITY_LOCATION,
                    List.of(), "Event location: " + location, 0.9,
                    Map.of("name", location)
            ));
            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId),
                    locEntityId, GraphConstants.REL_AT_LOCATION,
                    "Event at " + location,
                    1.0, calRelProps
            ));
        }

        // Organizer
        String organizerEmail = str(meta.get("gworkspace.calendar.organizerEmail"));
        String organizerName = str(meta.get("gworkspace.calendar.organizerName"));
        if (organizerEmail != null) {
            addPersonEntity(entities, organizerEmail, organizerName);
            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId),
                    personEntityId(organizerEmail),
                    GraphConstants.REL_ORGANIZED_BY,
                    "Event organized by " + (organizerName != null ? organizerName : organizerEmail),
                    1.0, calRelProps
            ));
        }

        // Creator
        String creatorEmail = str(meta.get("gworkspace.calendar.creatorEmail"));
        String creatorName = str(meta.get("gworkspace.calendar.creatorName"));
        if (creatorEmail != null) {
            addPersonEntity(entities, creatorEmail, creatorName);
            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId),
                    personEntityId(creatorEmail),
                    GraphConstants.REL_CREATED_BY,
                    "Event created by " + (creatorName != null ? creatorName : creatorEmail),
                    1.0, calRelProps
            ));
        }

        // Attendees
        Object attendeesObj = meta.get("gworkspace.calendar.attendees");
        if (attendeesObj instanceof List<?> attendeeList) {
            for (Object attObj : attendeeList) {
                if (attObj instanceof Map<?, ?> att) {
                    String email = str(att.get("email"));
                    String name = str(att.get("name"));
                    String responseStatus = str(att.get("responseStatus"));
                    if (email == null) continue;

                    String isOptional = str(att.get("optional"));
                    String isSelf = str(att.get("self"));
                    addPersonEntity(entities, email, name);
                    Map<String, String> attRelProps = new LinkedHashMap<>(calRelProps);
                    if (responseStatus != null) attRelProps.put("responseStatus", responseStatus);
                    if ("true".equals(isOptional)) attRelProps.put("optional", "true");
                    if ("true".equals(isSelf)) attRelProps.put("self", "true");
                    relations.add(new ExtractedRelation(
                            entityId("cal:" + eventId),
                            personEntityId(email),
                            GraphConstants.REL_ATTENDED_BY,
                            (name != null ? name : email) + " attends event"
                                    + (responseStatus != null ? " (" + responseStatus + ")" : ""),
                            1.0,
                            attRelProps
                    ));
                }
            }
        }

        // Recurring event → INSTANCE_OF parent series
        String recurringEventId = str(meta.get("gworkspace.calendar.recurringEventId"));
        if (recurringEventId != null && !recurringEventId.equals(eventId)) {
            String parentEventEntityId = entityId("cal:" + recurringEventId);
            addEntity(entities, new ExtractedEntity(
                    parentEventEntityId,
                    summary != null ? summary + " (series)" : "Recurring Event " + recurringEventId,
                    GraphConstants.ENTITY_CALENDAR_EVENT,
                    List.of(),
                    "Parent recurring calendar event series",
                    0.7,
                    Map.of("eventId", recurringEventId, "isRecurringSeries", "true")
            ));
            relations.add(new ExtractedRelation(
                    entityId("cal:" + eventId),
                    parentEventEntityId,
                    GraphConstants.REL_INSTANCE_OF,
                    "Event instance of recurring series",
                    1.0, calRelProps
            ));
        }

        // ── URL extraction from event description and body text ──
        // Check both the metadata description field and the document body text
        String bodyText = doc != null ? doc.getText() : null;
        String urlSource = description != null && !description.isBlank() ? description : null;
        if (urlSource == null && bodyText != null && !bodyText.isBlank()) {
            urlSource = bodyText;
        } else if (urlSource != null && bodyText != null && !bodyText.isBlank()
                && bodyText.length() > urlSource.length()) {
            // Body text may contain more content than just the description
            urlSource = bodyText;
        }
        if (urlSource != null) {
            Matcher urlMatcher = URL_PATTERN.matcher(urlSource);
            Set<String> seenUrls = new HashSet<>();
            int urlCount = 0;
            while (urlMatcher.find() && urlCount < MAX_URL_ENTITIES) {
                String url = urlMatcher.group();
                if (!seenUrls.add(url.toLowerCase())) continue;
                String urlEntityId = entityId("gws-url:" + eventId + ":" + url);
                addEntity(entities, new ExtractedEntity(
                        urlEntityId, url,
                        GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                        List.of(),
                        "URL found in calendar event description",
                        0.8,
                        Map.of("url", url)
                ));
                relations.add(new ExtractedRelation(
                        entityId("cal:" + eventId),
                        urlEntityId,
                        GraphConstants.REL_HYPERLINKS_TO,
                        "Calendar event links to " + url,
                        0.8, calRelProps
                ));
                urlCount++;
            }
        }
    }

    // ========== Person entity helpers ==========

    private void addPersonEntity(Map<String, ExtractedEntity> entities, String email, String name) {
        if (email == null) return;
        email = email.toLowerCase().trim();

        List<String> aliases = new ArrayList<>();
        if (name != null && !name.equals(email)) aliases.add(name);

        Map<String, String> props = new LinkedHashMap<>();
        props.put("email", email);

        addEntity(entities, new ExtractedEntity(
                personEntityId(email),
                name != null ? name : email,
                GraphConstants.ENTITY_GOOGLE_PERSON,
                aliases,
                "Person: " + (name != null ? name + " (" + email + ")" : email),
                1.0,
                props
        ));
    }

    private static String personEntityId(String email) {
        return entityId("person:" + (email != null ? email.toLowerCase().trim() : "unknown"));
    }

    // ========== Email parsing helpers ==========

    /**
     * Extract email address from "Name &lt;email@example.com&gt;" format.
     */
    static String extractEmail(String addressStr) {
        if (addressStr == null) return null;
        int lt = addressStr.indexOf('<');
        int gt = addressStr.indexOf('>');
        if (lt >= 0 && gt > lt) {
            return addressStr.substring(lt + 1, gt).trim().toLowerCase();
        }
        // Bare email
        String trimmed = addressStr.trim().toLowerCase();
        return trimmed.contains("@") ? trimmed : null;
    }

    /**
     * Extract display name from "Name &lt;email@example.com&gt;" format.
     */
    static String extractName(String addressStr) {
        if (addressStr == null) return null;
        int lt = addressStr.indexOf('<');
        if (lt > 0) {
            String name = addressStr.substring(0, lt).trim();
            // Remove surrounding quotes
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1);
            }
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    /**
     * Parse a comma-separated address list, respecting quoted strings and angle brackets.
     */
    static List<String> parseAddressList(String addresses) {
        if (addresses == null || addresses.isEmpty()) return List.of();

        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;

        for (int i = 0; i < addresses.length(); i++) {
            char c = addresses.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote) {
                if (c == '<') depth++;
                else if (c == '>') depth--;
                else if (c == ',' && depth == 0) {
                    String addr = addresses.substring(start, i).trim();
                    if (!addr.isEmpty()) result.add(addr);
                    start = i + 1;
                }
            }
        }
        String last = addresses.substring(start).trim();
        if (!last.isEmpty()) result.add(last);

        return result;
    }

    // ========== Drive entity type resolution ==========

    /**
     * Resolve the graph entity type from a Google Drive MIME type.
     * <ul>
     *   <li>{@code application/vnd.google-apps.folder}       → DRIVE_FOLDER</li>
     *   <li>{@code application/vnd.google-apps.spreadsheet}  → SPREADSHEET</li>
     *   <li>{@code application/vnd.google-apps.presentation} → PRESENTATION</li>
     *   <li>{@code application/vnd.google-apps.document}     → DOCUMENT</li>
     *   <li>{@code application/vnd.google-apps.form}         → GOOGLE_FORM</li>
     *   <li>{@code application/vnd.google-apps.drawing}      → GOOGLE_DRAWING</li>
     *   <li>Everything else                                   → DRIVE_FILE</li>
     * </ul>
     */
    static String resolveEntityType(String mimeType) {
        if (mimeType == null) return GraphConstants.ENTITY_DRIVE_FILE;
        return switch (mimeType) {
            case "application/vnd.google-apps.folder"       -> GraphConstants.ENTITY_DRIVE_FOLDER;
            case "application/vnd.google-apps.spreadsheet"  -> GraphConstants.ENTITY_SPREADSHEET;
            case "application/vnd.google-apps.presentation" -> GraphConstants.ENTITY_PRESENTATION;
            case "application/vnd.google-apps.document"     -> GraphConstants.ENTITY_DOCUMENT;
            case "application/vnd.google-apps.form"         -> GraphConstants.ENTITY_GOOGLE_FORM;
            case "application/vnd.google-apps.drawing"      -> GraphConstants.ENTITY_GOOGLE_DRAWING;
            default                                          -> GraphConstants.ENTITY_DRIVE_FILE;
        };
    }

    private static String descriptionForEntityType(String entityType, String label) {
        if (GraphConstants.ENTITY_DRIVE_FOLDER.equals(entityType))   return "Google Drive folder: " + label;
        if (GraphConstants.ENTITY_SPREADSHEET.equals(entityType))    return "Google Sheets spreadsheet: " + label;
        if (GraphConstants.ENTITY_PRESENTATION.equals(entityType))   return "Google Slides presentation: " + label;
        if (GraphConstants.ENTITY_DOCUMENT.equals(entityType))       return "Google Docs document: " + label;
        if (GraphConstants.ENTITY_GOOGLE_FORM.equals(entityType))    return "Google Form: " + label;
        if (GraphConstants.ENTITY_GOOGLE_DRAWING.equals(entityType)) return "Google Drawing: " + label;
        return "Google Drive file: " + label;
    }

    // ========== Common utilities ==========

    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private void addEntity(Map<String, ExtractedEntity> index, ExtractedEntity entity) {
        ExtractedEntity existing = index.get(entity.id());
        if (existing == null) {
            index.put(entity.id(), entity);
            return;
        }

        // Merge: union aliases, keep higher confidence, prefer real name over ID
        Set<String> aliases = new LinkedHashSet<>();
        if (existing.aliases() != null) aliases.addAll(existing.aliases());
        if (entity.aliases() != null) aliases.addAll(entity.aliases());

        String name = existing.name();
        // Prefer a real name over a generic label
        if (name.startsWith("Message ") || name.startsWith("Thread ") || name.startsWith("Event ")
                || name.contains("@") || name.matches("[a-f0-9-]+")) {
            if (!entity.name().startsWith("Message ") && !entity.name().startsWith("Thread ")
                    && !entity.name().startsWith("Event ") && !entity.name().matches("[a-f0-9-]+")) {
                name = entity.name();
            }
        }

        double confidence = Math.max(
                existing.confidence() != null ? existing.confidence() : 0,
                entity.confidence() != null ? entity.confidence() : 0
        );

        String description = existing.description();
        if ((description == null || description.length() < 20) && entity.description() != null) {
            description = entity.description();
        }

        Map<String, String> props = new LinkedHashMap<>();
        if (existing.properties() != null) props.putAll(existing.properties());
        if (entity.properties() != null) props.putAll(entity.properties());

        index.put(entity.id(), new ExtractedEntity(
                entity.id(), name, existing.type(),
                new ArrayList<>(aliases), description, confidence, props
        ));
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    /**
     * Converts an epoch-millis value (stored as Long or numeric String) to an ISO-8601 date string.
     * Gmail's internalDate is an epoch-millis long — storing it raw as "1716912000000" produces
     * broken DATE entities; this converts it to e.g. "2024-05-28T16:00:00Z".
     */
    private static String convertEpochMillisToIso(Object val) {
        if (val == null) return null;
        try {
            long millis;
            if (val instanceof Number n) {
                millis = n.longValue();
            } else {
                String s = val.toString().trim();
                if (s.isEmpty()) return null;
                // If it looks like a non-numeric string (e.g. already ISO), return as-is
                if (!s.chars().allMatch(c -> c == '-' || Character.isDigit(c))) return s;
                millis = Long.parseLong(s);
            }
            if (millis <= 0) return null;
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis));
        } catch (NumberFormatException e) {
            return val.toString().trim();
        }
    }

    // ========== ICS parsing ==========

    /**
     * Parses basic iCalendar (ICS) fields from raw content and populates
     * the provided properties map. Only scans VEVENT blocks.
     */
    static void extractIcsFields(String icsContent, Map<String, String> props) {
        if (icsContent == null || icsContent.isBlank()) return;
        boolean inEvent = false;
        List<String> attendees = new ArrayList<>();
        for (String rawLine : icsContent.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) { inEvent = true; continue; }
            if (line.equalsIgnoreCase("END:VEVENT")) { inEvent = false; continue; }
            if (!inEvent) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;
            String key = line.substring(0, colonIdx).toUpperCase();
            String value = line.substring(colonIdx + 1).trim();

            int semicolonIdx = key.indexOf(';');
            String baseKey = semicolonIdx >= 0 ? key.substring(0, semicolonIdx) : key;

            switch (baseKey) {
                case "SUMMARY"     -> props.put("summary", value);
                case "DTSTART"     -> props.put("dtstart", value);
                case "DTEND"       -> props.put("dtend", value);
                case "LOCATION"    -> props.put("location", value);
                case "DESCRIPTION" -> props.put("description", value);
                case "STATUS"      -> props.put("status", value);
                case "UID"         -> props.put("uid", value);
                case "SEQUENCE"    -> props.put("sequence", value);
                case "RRULE"       -> props.put("rrule", value);
                case "ORGANIZER"   -> {
                    String org = value.replaceFirst("(?i)^MAILTO:", "");
                    props.put("organizer", org);
                }
                case "ATTENDEE"    -> {
                    String att = value.replaceFirst("(?i)^MAILTO:", "");
                    attendees.add(att);
                }
                default -> { }
            }
        }
        if (!attendees.isEmpty()) {
            props.put("attendees", String.join(",", attendees));
        }
    }

    /**
     * Creates GOOGLE_PERSON entities and relationships for organizer, attendees, and location
     * parsed from ICS content. Called after extractIcsFields populates the props map.
     */
    private void addIcsPersonEntities(String calEntityId, Map<String, String> props,
                                       Map<String, ExtractedEntity> entities,
                                       List<ExtractedRelation> relations) {
        // Build relation props with occurredAt from the ICS DTSTART field.
        String icsStart = props.get("dtstart");
        final Map<String, String> icsRelProps = icsStart != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, icsStart) : Map.of();

        // Organizer → ORGANIZED_BY
        String organizer = props.get("organizer");
        if (organizer != null && !organizer.isBlank()) {
            addPersonEntity(entities, organizer, null);
            relations.add(new ExtractedRelation(
                    calEntityId, personEntityId(organizer),
                    GraphConstants.REL_ORGANIZED_BY,
                    "Calendar event organized by " + organizer,
                    1.0, icsRelProps
            ));
        }

        // Attendees → ATTENDED_BY
        String attendeesRaw = props.get("attendees");
        if (attendeesRaw != null) {
            for (String attendee : attendeesRaw.split(",")) {
                attendee = attendee.trim();
                if (attendee.isEmpty()) continue;
                addPersonEntity(entities, attendee, null);
                relations.add(new ExtractedRelation(
                        calEntityId, personEntityId(attendee),
                        GraphConstants.REL_ATTENDED_BY,
                        "Calendar event attended by " + attendee,
                        1.0, icsRelProps
                ));
            }
        }

        // Location → AT_LOCATION
        String location = props.get("location");
        if (location != null && !location.isBlank()) {
            String locEntityId = entityId("location:" + location.toLowerCase());
            addEntity(entities, new ExtractedEntity(
                    locEntityId, location, GraphConstants.ENTITY_LOCATION,
                    List.of(), "Calendar event location: " + location, 0.9,
                    Map.of("name", location)
            ));
            relations.add(new ExtractedRelation(
                    calEntityId, locEntityId,
                    GraphConstants.REL_AT_LOCATION,
                    "Calendar event at " + location,
                    1.0, icsRelProps
            ));
        }
    }
}
