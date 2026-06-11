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

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic graph extractor for Gmail documents.
 * Extracts entities (persons, messages, threads, labels, attachments) and
 * relationships (SENT_BY, SENT_TO, CC_TO, IN_THREAD, HAS_LABEL, HAS_ATTACHMENT, REPLIED_TO)
 * from Gmail document metadata — no LLM required.
 */
@Slf4j
@Component
public class GmailGraphExtractor implements DocumentGraphExtractor {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern NAME_EMAIL_PATTERN =
            Pattern.compile("^\\s*\"?([^\"<]+?)\"?\\s*<([^>]+)>\\s*$");
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final Pattern HTML_HREF_PATTERN =
            Pattern.compile("href=\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("gmail", "gmail_attachment");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        String sourceType = (String) doc.getMetadata().get(GraphConstants.META_SOURCE_TYPE);
        return "gmail".equals(sourceType) || "gmail_attachment".equals(sourceType);
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        String sourceType = (String) metadata.get(GraphConstants.META_SOURCE_TYPE);

        if (!"gmail".equals(sourceType) && !"gmail_attachment".equals(sourceType)) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relationships = new ArrayList<>();

        if ("gmail_attachment".equals(sourceType)) {
            extractAttachmentGraph(metadata, doc.getText(), entities, relationships);
        } else {
            extractMessageGraph(metadata, entities, relationships);
        }

        // Extract inline URLs from email body → HYPERLINKS_TO → EXTERNAL_RESOURCE
        String bodyContent = doc.getText();
        if (bodyContent != null && !bodyContent.isBlank()) {
            String messageId = (String) metadata.get(GraphConstants.META_GMAIL_MESSAGE_ID_RAW);
            String msgEntityId = messageId != null ? entityId("gmail_message", messageId) : null;
            if (msgEntityId != null) {
                Set<String> seenUrls = new LinkedHashSet<>();
                Matcher urlMatcher = URL_PATTERN.matcher(bodyContent);
                while (urlMatcher.find() && seenUrls.size() < 50) {
                    String url = urlMatcher.group();
                    if (seenUrls.add(url)) {
                        String urlEntityId = entityId("url", url);
                        entities.add(new ExtractedEntity(
                                urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "URL referenced in email", 0.8,
                                Map.of("url", url)));
                        relationships.add(new ExtractedRelation(
                                msgEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                                "Email contains link to " + url, 0.8, null));
                    }
                }
            }
        }

        return ExtractionResult.of(entities, relationships, null);
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        Map<String, ExtractedEntity> entityMap = new LinkedHashMap<>();
        Set<String> relationshipKeys = new LinkedHashSet<>();
        List<ExtractedRelation> allRelationships = new ArrayList<>();

        for (Document doc : docs) {
            ExtractionResult result = extract(doc);

            for (ExtractedEntity entity : result.entities()) {
                entityMap.putIfAbsent(entity.id(), entity);
            }

            for (ExtractedRelation rel : result.relations()) {
                String key = rel.source() + "|" + rel.target() + "|" + rel.type();
                if (relationshipKeys.add(key)) {
                    allRelationships.add(rel);
                }
            }
        }

        return ExtractionResult.of(new ArrayList<>(entityMap.values()), allRelationships, null);
    }

    private void extractMessageGraph(Map<String, Object> meta,
                                      List<ExtractedEntity> entities,
                                      List<ExtractedRelation> relationships) {
        String messageId = (String) meta.get(GraphConstants.META_GMAIL_MESSAGE_ID_RAW);
        String threadId = (String) meta.get("gmail.threadId");
        String subject = (String) meta.get("gmail.subject");
        String from = (String) meta.get("gmail.from");
        String to = (String) meta.get("gmail.to");
        String cc = (String) meta.get("gmail.cc");
        String inReplyTo = (String) meta.get("gmail.inReplyTo");

        if (messageId == null) return;

        // Message entity
        String msgEntityId = entityId("gmail_message", messageId);
        Map<String, String> msgProps = new LinkedHashMap<>();
        msgProps.put("messageId", messageId);
        msgProps.put("subject", Objects.toString(subject, ""));
        String date = (String) meta.get("gmail.date");
        if (date != null) msgProps.put("date", date);
        String internalDate = (String) meta.get("gmail.internalDate");
        if (internalDate != null) msgProps.put("internalDate", internalDate);
        String rfc822Id = (String) meta.get("gmail.rfc822MessageId");
        if (rfc822Id != null) msgProps.put("rfc822MessageId", rfc822Id);
        Object sizeEstimate = meta.get("gmail.sizeEstimate");
        if (sizeEstimate != null) msgProps.put("sizeEstimate", sizeEstimate.toString());
        Object threadPosition = meta.get("gmail.threadPosition");
        if (threadPosition != null) msgProps.put("threadPosition", threadPosition.toString());

        // Priority/importance
        String priority = str(meta.get(GraphConstants.META_EMAIL_PRIORITY));
        if (priority != null) msgProps.put("priority", priority);

        // DKIM/SPF/DMARC authentication results
        String dkimResult = str(meta.get(GraphConstants.META_EMAIL_DKIM_RESULT));
        if (dkimResult != null) msgProps.put("dkimResult", dkimResult);
        String spfResult = str(meta.get(GraphConstants.META_EMAIL_SPF_RESULT));
        if (spfResult != null) msgProps.put("spfResult", spfResult);
        String dmarcResult = str(meta.get(GraphConstants.META_EMAIL_DMARC_RESULT));
        if (dmarcResult != null) msgProps.put("dmarcResult", dmarcResult);

        // Return-Path, Auto-Submitted, Precedence, X-Mailer/User-Agent
        String returnPath = str(meta.get(GraphConstants.META_EMAIL_RETURN_PATH));
        if (returnPath != null) msgProps.put(GraphConstants.PROP_RETURN_PATH, returnPath);
        String autoSubmitted = str(meta.get(GraphConstants.META_EMAIL_AUTO_SUBMITTED));
        if (autoSubmitted != null) msgProps.put(GraphConstants.PROP_AUTO_SUBMITTED, autoSubmitted);
        if (Boolean.TRUE.equals(meta.get(GraphConstants.META_EMAIL_IS_AUTO_REPLY)))
            msgProps.put(GraphConstants.PROP_IS_AUTO_REPLY, "true");
        String precedenceVal = str(meta.get(GraphConstants.META_EMAIL_PRECEDENCE));
        if (precedenceVal != null) msgProps.put("precedence", precedenceVal);
        String mailer = str(meta.get(GraphConstants.META_EMAIL_MAILER));
        if (mailer != null) msgProps.put("mailer", mailer);
        String userAgent = str(meta.get(GraphConstants.META_EMAIL_USER_AGENT));
        if (userAgent != null) msgProps.put("userAgent", userAgent);

        entities.add(new ExtractedEntity(msgEntityId, subject != null ? subject : "Message " + messageId,
                GraphConstants.ENTITY_GMAIL_MESSAGE, null, null, 1.0, msgProps));

        // Register this message under its RFC822 Message-ID as well so that
        // REPLIED_TO / REFERENCES stubs from other messages can merge with this entity
        if (rfc822Id != null) {
            String rfc822EntityId = entityId("gmail_rfc822", rfc822Id);
            if (!rfc822EntityId.equals(msgEntityId)) {
                entities.add(new ExtractedEntity(rfc822EntityId, subject != null ? subject : "Message " + messageId,
                        GraphConstants.ENTITY_GMAIL_MESSAGE, null, null, 1.0,
                        Map.of("rfc822MessageId", rfc822Id, "messageId", messageId)));
                relationships.add(new ExtractedRelation(msgEntityId, rfc822EntityId,
                        GraphConstants.REL_SAME_AS, "Gmail API ID and RFC822 Message-ID for same message", 1.0, null));
            }
        }

        // DATE entity from sent date
        if (date != null) {
            String dateEntityId = entityId("date", date);
            entities.add(new ExtractedEntity(dateEntityId, date, GraphConstants.ENTITY_DATE,
                    null, "Email date: " + date, 0.9,
                    Map.of("date", date, "dateType", "sent")));
            relationships.add(new ExtractedRelation(msgEntityId, dateEntityId, GraphConstants.REL_PUBLISHED_ON,
                    (subject != null ? subject : "Email") + " sent on " + date, 0.9, null));
        }

        // Thread entity
        if (threadId != null) {
            String threadEntityId = entityId("gmail_thread", threadId);
            entities.add(new ExtractedEntity(threadEntityId, "Thread " + threadId,
                    GraphConstants.ENTITY_GMAIL_THREAD, null, null, 1.0, Map.of("threadId", threadId)));
            relationships.add(new ExtractedRelation(msgEntityId, threadEntityId, GraphConstants.REL_IN_THREAD, null, 1.0, null));
        }

        // From person
        if (from != null) {
            String personId = addPersonEntity(from, entities);
            if (personId != null) {
                relationships.add(new ExtractedRelation(msgEntityId, personId, GraphConstants.REL_SENT_BY, null, 1.0, null));
            }
        }

        // Sender domain → ORGANIZATION
        if (from != null && from.contains("@")) {
            String domain = from.contains("<") && from.contains(">")
                    ? from.substring(from.indexOf('<') + 1, from.indexOf('>'))
                    : from;
            int atIdx = domain.indexOf('@');
            if (atIdx > 0 && atIdx < domain.length() - 1) {
                domain = domain.substring(atIdx + 1).trim().toLowerCase();
                if (domain.contains(".") && !domain.startsWith(".")
                        && !Set.of("gmail.com", "yahoo.com", "hotmail.com", "outlook.com",
                                   "aol.com", "icloud.com", "mail.com", "protonmail.com",
                                   "zoho.com", "yandex.com", "live.com", "msn.com",
                                   "googlemail.com").contains(domain)) {
                    String domainOrgId = entityId("org", domain);
                    Map<String, String> orgProps = new LinkedHashMap<>();
                    orgProps.put("name", domain);
                    orgProps.put("domain", domain);
                    orgProps.put(GraphConstants.PROP_SOURCE_FIELD, "gmail.from");
                    entities.add(new ExtractedEntity(
                            domainOrgId, domain, GraphConstants.ENTITY_ORGANIZATION,
                            null, "Organization (email domain): " + domain, 0.7, orgProps));
                    relationships.add(new ExtractedRelation(
                            msgEntityId, domainOrgId, GraphConstants.REL_AFFILIATED_WITH,
                            "Email from " + domain + " organization", 0.7, null));
                    // Link sender person to their organization
                    String senderPersonId = addPersonEntity(from, entities);
                    if (senderPersonId != null) {
                        relationships.add(new ExtractedRelation(
                                senderPersonId, domainOrgId, GraphConstants.REL_AFFILIATED_WITH,
                                "Sender affiliated with " + domain, 0.7, null));
                    }
                }
            }
        }

        // To persons
        if (to != null) {
            for (String addr : parseAddressList(to)) {
                String personId = addPersonEntity(addr, entities);
                if (personId != null) {
                    relationships.add(new ExtractedRelation(msgEntityId, personId, GraphConstants.REL_SENT_TO, null, 1.0, null));
                }
            }
        }

        // CC persons
        if (cc != null) {
            for (String addr : parseAddressList(cc)) {
                String personId = addPersonEntity(addr, entities);
                if (personId != null) {
                    relationships.add(new ExtractedRelation(msgEntityId, personId, GraphConstants.REL_CC_TO, null, 1.0, null));
                }
            }
        }

        // BCC persons
        String bcc = (String) meta.get("gmail.bcc");
        if (bcc != null) {
            for (String addr : parseAddressList(bcc)) {
                String personId = addPersonEntity(addr, entities);
                if (personId != null) {
                    relationships.add(new ExtractedRelation(msgEntityId, personId, GraphConstants.REL_BCC_TO, null, 0.9, null));
                }
            }
        }

        // Reply-To address (distinct from the From sender — indicates where replies should go)
        String replyTo = (String) meta.get("gmail.replyTo");
        if (replyTo != null) {
            for (String addr : parseAddressList(replyTo)) {
                String personId = addPersonEntity(addr, entities);
                if (personId != null) {
                    relationships.add(new ExtractedRelation(msgEntityId, personId, GraphConstants.REL_REPLY_TO_DIRECTED_AT,
                            "Replies to this email should go to " + addr, 0.95, null));
                }
            }
        }

        // Reply-to chain
        if (inReplyTo != null) {
            String repliedMsgId = entityId("gmail_rfc822", inReplyTo);
            entities.add(new ExtractedEntity(repliedMsgId, "Message " + inReplyTo,
                    GraphConstants.ENTITY_GMAIL_MESSAGE, null, null, 1.0, Map.of("rfc822MessageId", inReplyTo)));
            relationships.add(new ExtractedRelation(msgEntityId, repliedMsgId, GraphConstants.REL_REPLIED_TO, null, 1.0, null));
        }

        // References chain (other messages in the thread)
        @SuppressWarnings("unchecked")
        List<String> references = meta.get("gmail.references") instanceof List
                ? (List<String>) meta.get("gmail.references") : null;
        if (references == null && meta.get("gmail.references") instanceof String refStr) {
            references = List.of(refStr.trim().split("\\s+"));
        }
        if (references != null) {
            for (String ref : references) {
                if (ref == null || ref.isBlank()) continue;
                // Skip the inReplyTo message — already handled above
                if (ref.equals(inReplyTo)) continue;
                String refEntityId = entityId("gmail_rfc822", ref);
                entities.add(new ExtractedEntity(refEntityId, "Message " + ref,
                        GraphConstants.ENTITY_GMAIL_MESSAGE, null, null, 0.7, Map.of("rfc822MessageId", ref)));
                relationships.add(new ExtractedRelation(msgEntityId, refEntityId, GraphConstants.REL_REFERENCES,
                        "Email references " + ref, 0.9, null));
            }
        }

        // Mailing list
        String listId = (String) meta.get("gmail.listId");
        if (listId != null && !listId.isBlank()) {
            String listEntityId = entityId("gmail_list", listId);
            entities.add(new ExtractedEntity(listEntityId, listId,
                    GraphConstants.ENTITY_MAILING_LIST, null, "Mailing list: " + listId, 1.0, Map.of("listId", listId)));
            relationships.add(new ExtractedRelation(msgEntityId, listEntityId, GraphConstants.REL_POSTED_TO,
                    "Email posted to mailing list " + listId, 1.0, null));
        }

        // Labels
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) meta.get("gmail.labels");
        if (labels != null) {
            for (String label : labels) {
                String labelId = entityId("gmail_label", label);
                entities.add(new ExtractedEntity(labelId, label, GraphConstants.ENTITY_GMAIL_LABEL, null, null, 1.0, Map.of("labelId", label)));
                relationships.add(new ExtractedRelation(msgEntityId, labelId, GraphConstants.REL_HAS_LABEL, null, 1.0, null));
            }
        }

        // Attachment count
        Object attCountObj = meta.get("gmail.attachmentCount");
        if (attCountObj instanceof Number n && n.intValue() > 0) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) meta.get("gmail.attachments");
            if (attachments != null) {
                for (Map<String, Object> att : attachments) {
                    String filename = (String) att.get("filename");
                    if (filename == null) continue;
                    String attMimeType = Objects.toString(att.get("mimeType"), "");

                    // Detect ICS calendar invites
                    if ("text/calendar".equalsIgnoreCase(attMimeType)
                            || (filename.toLowerCase().endsWith(".ics"))) {
                        String calEntityId = entityId("gmail_calendar", messageId + "/" + filename);
                        Map<String, String> calProps = new LinkedHashMap<>();
                        calProps.put("filename", filename);
                        calProps.put("mimeType", attMimeType);
                        entities.add(new ExtractedEntity(calEntityId,
                                "Calendar: " + filename, GraphConstants.ENTITY_CALENDAR_EVENT,
                                null, "Calendar invite from email " + messageId, 1.0, calProps));
                        relationships.add(new ExtractedRelation(msgEntityId, calEntityId,
                                GraphConstants.REL_HAS_CALENDAR_EVENT, null, 1.0, null));
                    } else {
                        String attEntityId = entityId("gmail_attachment", messageId + "/" + filename);
                        entities.add(new ExtractedEntity(attEntityId, filename, GraphConstants.ENTITY_GMAIL_ATTACHMENT, null, null, 1.0,
                                Map.of("filename", filename, "mimeType", attMimeType)));
                        relationships.add(new ExtractedRelation(msgEntityId, attEntityId, GraphConstants.REL_HAS_ATTACHMENT, null, 1.0, null));
                    }
                }
            }
        }

        // Thread-Topic / Conversation topic (from Outlook Thread-Topic header)
        String threadTopic = (String) meta.get("gmail.threadTopic");
        if (threadTopic != null && !threadTopic.isBlank()) {
            String topicEntityId = entityId("conversation_topic", threadTopic.toLowerCase().trim());
            entities.add(new ExtractedEntity(topicEntityId, threadTopic.trim(),
                    GraphConstants.ENTITY_CONVERSATION_TOPIC, null,
                    "Conversation topic: " + threadTopic.trim(), 0.9,
                    Map.of("topic", threadTopic.trim())));
            relationships.add(new ExtractedRelation(msgEntityId, topicEntityId,
                    GraphConstants.REL_HAS_CONVERSATION_TOPIC,
                    "Email has conversation topic: " + threadTopic.trim(), 0.9, null));
        }

        // Extract href links from HTML body (invisible in plain-text version)
        String htmlBody = str(meta.get(GraphConstants.META_EMAIL_HTML_BODY));
        if (htmlBody != null) {
            // Collect URLs already found from plain-text body extraction (in extract() method)
            // so we can add HTML-only hrefs without duplication
            Set<String> htmlUrls = new LinkedHashSet<>();
            Matcher hrefMatcher = HTML_HREF_PATTERN.matcher(htmlBody);
            while (hrefMatcher.find() && htmlUrls.size() < 100) {
                String url = hrefMatcher.group(1);
                if (htmlUrls.add(url)) {
                    String urlEntityId = entityId("url", url);
                    Map<String, String> urlProps = new LinkedHashMap<>();
                    urlProps.put("url", url);
                    urlProps.put("linkSource", "htmlBody");
                    entities.add(new ExtractedEntity(
                            urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                            null, "URL from email HTML body", 0.8, urlProps));
                    relationships.add(new ExtractedRelation(
                            msgEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                            "Email HTML body contains link to " + url, 0.8, null));
                }
            }
        }

        // Inline CID images → EMBEDDED_IMAGE entities
        @SuppressWarnings("unchecked")
        List<Map<String, String>> inlineImages = meta.get(GraphConstants.META_EMAIL_INLINE_IMAGES) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(GraphConstants.META_EMAIL_INLINE_IMAGES) : null;
        if (inlineImages != null) {
            for (Map<String, String> img : inlineImages) {
                String cid = img.get("contentId");
                if (cid == null || cid.isBlank()) continue;
                String imgId = entityId("cid", cid);
                Map<String, String> imgProps = new LinkedHashMap<>();
                imgProps.put("contentId", cid);
                String imgMime = img.get("mimeType");
                if (imgMime != null) imgProps.put("mimeType", imgMime);
                String imgFileName = img.get("filename");
                if (imgFileName != null) imgProps.put("filename", imgFileName);
                imgProps.put("inline", "true");
                String imgName = imgFileName != null ? imgFileName : cid;
                entities.add(new ExtractedEntity(
                        imgId, imgName, GraphConstants.ENTITY_EMBEDDED_IMAGE,
                        null, "Inline image: " + imgName, 0.85, imgProps));
                relationships.add(new ExtractedRelation(
                        msgEntityId, imgId, GraphConstants.REL_HAS_IMAGE,
                        (subject != null ? subject : "Email") + " has inline image: " + imgName,
                        0.85, null));
            }
        }

        // Received headers → MAIL_SERVER entities with ROUTED_VIA chain
        @SuppressWarnings("unchecked")
        List<String> receivedHeaders = meta.get(GraphConstants.META_EMAIL_RECEIVED_HEADERS) instanceof List<?>
                ? (List<String>) meta.get(GraphConstants.META_EMAIL_RECEIVED_HEADERS) : null;
        if (receivedHeaders != null && !receivedHeaders.isEmpty()) {
            String previousServerId = null;
            for (int i = 0; i < receivedHeaders.size() && i < 20; i++) {
                String receivedLine = receivedHeaders.get(i);
                String serverName = parseReceivedServerName(receivedLine);
                if (serverName == null) continue;
                String serverId = entityId("mail_server", serverName.toLowerCase());
                Map<String, String> serverProps = new LinkedHashMap<>();
                serverProps.put("hostname", serverName);
                serverProps.put("hopIndex", String.valueOf(i));
                entities.add(new ExtractedEntity(serverId, serverName,
                        GraphConstants.ENTITY_MAIL_SERVER, null,
                        "Mail server from Received header", 0.85, serverProps));
                // First hop: message → server
                if (i == 0) {
                    relationships.add(new ExtractedRelation(msgEntityId, serverId,
                            GraphConstants.REL_ROUTED_VIA,
                            "Email routed via " + serverName, 0.85, null));
                }
                // Chain hops: previous server → this server
                if (previousServerId != null && !previousServerId.equals(serverId)) {
                    relationships.add(new ExtractedRelation(previousServerId, serverId,
                            GraphConstants.REL_ROUTED_VIA,
                            "Mail routed from previous hop to " + serverName, 0.8, null));
                }
                previousServerId = serverId;
            }
        }

        // X-Mailer / User-Agent → EMAIL_CLIENT entity + SENT_WITH relation
        if (mailer != null) {
            String clientId = entityId("email_client", mailer.toLowerCase());
            entities.add(new ExtractedEntity(clientId, mailer,
                    GraphConstants.ENTITY_EMAIL_CLIENT, null,
                    "Email client: " + mailer, 0.9,
                    Map.of("software", mailer, "headerSource", "X-Mailer")));
            relationships.add(new ExtractedRelation(msgEntityId, clientId,
                    GraphConstants.REL_SENT_WITH,
                    "Email sent with " + mailer, 0.9, null));
        } else if (userAgent != null) {
            String clientId = entityId("email_client", userAgent.toLowerCase());
            entities.add(new ExtractedEntity(clientId, userAgent,
                    GraphConstants.ENTITY_EMAIL_CLIENT, null,
                    "Email client: " + userAgent, 0.9,
                    Map.of("software", userAgent, "headerSource", "User-Agent")));
            relationships.add(new ExtractedRelation(msgEntityId, clientId,
                    GraphConstants.REL_SENT_WITH,
                    "Email sent with " + userAgent, 0.9, null));
        }

        // List-Unsubscribe → EXTERNAL_RESOURCE entity + relation
        String listUnsub = str(meta.get(GraphConstants.META_EMAIL_LIST_UNSUBSCRIBE));
        if (listUnsub != null) {
            // List-Unsubscribe may contain multiple URIs: <mailto:unsub@x.com>, <https://x.com/unsub>
            Matcher unsubMatcher = Pattern.compile("<([^>]+)>").matcher(listUnsub);
            while (unsubMatcher.find()) {
                String unsubUri = unsubMatcher.group(1);
                String unsubId = entityId("unsub", unsubUri.toLowerCase());
                Map<String, String> unsubProps = new LinkedHashMap<>();
                unsubProps.put("uri", unsubUri);
                unsubProps.put("type", unsubUri.startsWith("mailto:") ? "email" : "url");
                entities.add(new ExtractedEntity(unsubId, "Unsubscribe: " + unsubUri,
                        GraphConstants.ENTITY_EXTERNAL_RESOURCE, null,
                        "List unsubscribe endpoint", 0.8, unsubProps));
                relationships.add(new ExtractedRelation(msgEntityId, unsubId,
                        GraphConstants.REL_HYPERLINKS_TO,
                        "Email has unsubscribe link: " + unsubUri, 0.8, null));
            }
        }
    }

    private void extractAttachmentGraph(Map<String, Object> meta,
                                         String docText,
                                         List<ExtractedEntity> entities,
                                         List<ExtractedRelation> relationships) {
        String messageId = (String) meta.get(GraphConstants.META_GMAIL_MESSAGE_ID_RAW);
        String filename = (String) meta.get("gmail.attachment.filename");

        if (messageId == null || filename == null) return;

        String attMimeType = Objects.toString(meta.get("gmail.attachment.mimeType"), "");
        String msgEntityId = entityId("gmail_message", messageId);

        // Detect ICS calendar invites in standalone attachment documents
        if ("text/calendar".equalsIgnoreCase(attMimeType)
                || filename.toLowerCase().endsWith(".ics")) {
            String calEntityId = entityId("gmail_calendar", messageId + "/" + filename);
            Map<String, String> calProps = new LinkedHashMap<>();
            calProps.put("filename", filename);
            calProps.put("mimeType", attMimeType);
            Object attSize = meta.get("gmail.attachment.size");
            if (attSize != null) calProps.put("size", attSize.toString());
            String attApiId = Objects.toString(meta.get("gmail.attachment.id"), null);
            if (attApiId != null) calProps.put("attachmentId", attApiId);
            Object contentLoaded = meta.get("gmail.attachment.contentLoaded");
            if (contentLoaded != null) calProps.put("contentLoaded", contentLoaded.toString());

            // Parse ICS fields from loaded attachment content
            if (docText != null && !docText.isBlank()) {
                extractIcsFields(docText, calProps);
            }

            String summary = calProps.getOrDefault("summary", filename);
            entities.add(new ExtractedEntity(calEntityId,
                    summary, GraphConstants.ENTITY_CALENDAR_EVENT,
                    null, "Calendar invite from email " + messageId, 1.0, calProps));
            relationships.add(new ExtractedRelation(msgEntityId, calEntityId,
                    GraphConstants.REL_HAS_CALENDAR_EVENT, null, 1.0, null));

            // Organizer
            if (calProps.containsKey("organizer")) {
                String orgEmail = calProps.get("organizer");
                String orgId = entityId("person", orgEmail.toLowerCase());
                entities.add(new ExtractedEntity(orgId, orgEmail,
                        GraphConstants.ENTITY_PERSON, null,
                        "Calendar event organizer", 0.9,
                        Map.of("email", orgEmail)));
                relationships.add(new ExtractedRelation(calEntityId, orgId,
                        GraphConstants.REL_ORGANIZED_BY,
                        "Calendar event organized by " + orgEmail, 1.0, null));
            }

            // Attendees
            String attendeesRaw = calProps.get("attendees");
            if (attendeesRaw != null) {
                for (String attendee : attendeesRaw.split(",")) {
                    attendee = attendee.trim();
                    if (attendee.isEmpty()) continue;
                    String attId = entityId("person", attendee.toLowerCase());
                    entities.add(new ExtractedEntity(attId, attendee,
                            GraphConstants.ENTITY_PERSON, null,
                            "Calendar event attendee", 0.9,
                            Map.of("email", attendee)));
                    relationships.add(new ExtractedRelation(calEntityId, attId,
                            GraphConstants.REL_ATTENDED_BY,
                            "Calendar event attended by " + attendee, 1.0, null));
                }
            }

            // Location
            if (calProps.containsKey("location")) {
                String locName = calProps.get("location");
                String locId = entityId("location", locName.toLowerCase());
                entities.add(new ExtractedEntity(locId, locName,
                        GraphConstants.ENTITY_LOCATION, null,
                        "Calendar event location", 0.9,
                        Map.of("locationName", locName)));
                relationships.add(new ExtractedRelation(calEntityId, locId,
                        GraphConstants.REL_AT_LOCATION,
                        "Calendar event at " + locName, 1.0, null));
            }
        } else {
            String attEntityId = entityId("gmail_attachment", messageId + "/" + filename);
            Map<String, String> attProps = new LinkedHashMap<>();
            attProps.put("filename", filename);
            attProps.put("mimeType", attMimeType);
            Object attSize = meta.get("gmail.attachment.size");
            if (attSize != null) attProps.put("size", attSize.toString());
            String attApiId = Objects.toString(meta.get("gmail.attachment.id"), null);
            if (attApiId != null) attProps.put("attachmentId", attApiId);
            Object contentLoaded = meta.get("gmail.attachment.contentLoaded");
            if (contentLoaded != null) attProps.put("contentLoaded", contentLoaded.toString());
            entities.add(new ExtractedEntity(attEntityId, filename, GraphConstants.ENTITY_GMAIL_ATTACHMENT, null, null, 1.0, attProps));
            relationships.add(new ExtractedRelation(msgEntityId, attEntityId, GraphConstants.REL_HAS_ATTACHMENT, null, 1.0, null));
        }
    }

    private String addPersonEntity(String addressString,
                                    List<ExtractedEntity> entities) {
        String email = extractEmail(addressString);
        if (email == null) return null;

        String name = extractName(addressString);
        String personId = entityId("person", email.toLowerCase());
        Map<String, String> personProps = new LinkedHashMap<>();
        personProps.put("email", email);
        if (name != null) personProps.put("displayName", name);
        entities.add(new ExtractedEntity(personId, name != null ? name : email,
                GraphConstants.ENTITY_PERSON, null, null, 1.0, personProps));
        return personId;
    }

    // --- Utility methods ---

    /** Regex to extract "by <hostname>" from a Received header. */
    private static final Pattern RECEIVED_BY_PATTERN =
            Pattern.compile("\\bby\\s+([a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})\\b");
    /** Regex to extract "from <hostname>" from a Received header. */
    private static final Pattern RECEIVED_FROM_PATTERN =
            Pattern.compile("\\bfrom\\s+([a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})\\b");

    /**
     * Parses the receiving server hostname from a Received header line.
     * Prefers "by <host>" (the receiving server), falls back to "from <host>".
     */
    static String parseReceivedServerName(String receivedLine) {
        if (receivedLine == null || receivedLine.isBlank()) return null;
        Matcher byMatcher = RECEIVED_BY_PATTERN.matcher(receivedLine);
        if (byMatcher.find()) return byMatcher.group(1);
        Matcher fromMatcher = RECEIVED_FROM_PATTERN.matcher(receivedLine);
        if (fromMatcher.find()) return fromMatcher.group(1);
        return null;
    }

    private static String entityId(String prefix, String key) {
        return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String str(Object obj) {
        if (obj instanceof String s) return s.isBlank() ? null : s;
        return obj != null ? obj.toString() : null;
    }

    static String extractEmail(String addressString) {
        if (addressString == null) return null;
        Matcher m = NAME_EMAIL_PATTERN.matcher(addressString);
        if (m.matches()) return m.group(2).trim();
        Matcher emailMatcher = EMAIL_PATTERN.matcher(addressString);
        if (emailMatcher.find()) return emailMatcher.group();
        return null;
    }

    static String extractName(String addressString) {
        if (addressString == null) return null;
        Matcher m = NAME_EMAIL_PATTERN.matcher(addressString);
        if (m.matches()) {
            String name = m.group(1).trim();
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    static List<String> parseAddressList(String addressList) {
        if (addressList == null || addressList.isBlank()) return List.of();

        List<String> addresses = new ArrayList<>();
        int depth = 0;
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (char c : addressList.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == '<') {
                depth++;
                current.append(c);
            } else if (c == '>') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0 && !inQuotes) {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) addresses.add(trimmed);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) addresses.add(last);

        return addresses;
    }

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
}
