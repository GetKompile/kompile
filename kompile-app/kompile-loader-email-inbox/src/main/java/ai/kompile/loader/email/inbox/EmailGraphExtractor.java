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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a structured knowledge graph from email documents using header
 * metadata. This is a deterministic, rule-based extractor — no LLM needed.
 *
 * <p>Extracts:</p>
 * <ul>
 *   <li><b>Entities</b>: PERSON (from/to/cc/bcc), EMAIL_MESSAGE, MAILING_LIST, ATTACHMENT</li>
 *   <li><b>Relationships</b>: SENT_BY, SENT_TO, CC_TO, BCC_TO, REPLIED_TO, REFERENCES,
 *       HAS_ATTACHMENT, MEMBER_OF (mailing list)</li>
 * </ul>
 *
 * <p>All entities get deterministic IDs derived from their email address or
 * message-id, allowing cross-document deduplication by the entity resolution
 * service downstream.</p>
 */
@Component
public class EmailGraphExtractor implements DocumentGraphExtractor {

    private static final Pattern EMAIL_ADDR_PATTERN =
            Pattern.compile("<?([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})>?");
    private static final Pattern NAME_ADDR_PATTERN =
            Pattern.compile("^(.+?)\\s*<[^>]+>$");
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final Pattern HTML_HREF_PATTERN =
            Pattern.compile("href=\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("email", "outlook_pst", "email_attachment", "mbox", "maildir", "emlx");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        // Gmail documents are handled by GmailGraphExtractor — don't claim them here
        if ("gmail".equals(str(meta.get(GraphConstants.META_SOURCE_TYPE))) || "gmail_attachment".equals(str(meta.get(GraphConstants.META_SOURCE_TYPE)))
                || meta.get(GraphConstants.META_GMAIL_MESSAGE_ID_RAW) != null) {
            return false;
        }
        // PST contacts, tasks, and appointments are not emails — PstItemGraphExtractor handles them
        String docType = str(meta.get(GraphConstants.META_DOCUMENT_TYPE));
        String contentType = str(meta.get(GraphConstants.META_CONTENT_TYPE));
        if (isPstItemType(docType) || isPstItemType(contentType)) {
            return false;
        }
        return meta.get(GraphConstants.META_EMAIL_FROM) != null
                || "OUTLOOK_PST".equals(str(meta.get(GraphConstants.META_SOURCE_TYPE)))
                || "EMAIL_INBOX".equals(str(meta.get(GraphConstants.META_SOURCE_TYPE)))
                || "EMAIL_ATTACHMENT".equals(str(meta.get(GraphConstants.META_SOURCE_TYPE)))
                || meta.get(GraphConstants.META_EMAIL_IS_ATTACHMENT) != null
                || "email".equals(str(meta.get(GraphConstants.META_CONTENT_TYPE_HINT)));
    }

    /**
     * Extracts entities and relationships from an email document's metadata.
     *
     * @param doc    a Document produced by {@link Mime4jMessageParser}
     * @return an {@link ExtractionResult} with deterministic entities and relationships
     */
    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();

        // Create the email message entity
        String messageId = str(meta.get(GraphConstants.META_EMAIL_MESSAGE_ID));
        String subject = str(meta.get(GraphConstants.META_EMAIL_SUBJECT));
        String date = str(meta.get(GraphConstants.META_EMAIL_DATE));

        // Build a reusable relation properties map that carries provenance + occurredAt for
        // all relations produced from this email document.
        final Map<String, String> emailRelProps;
        if (date != null) {
            emailRelProps = new LinkedHashMap<>();
            emailRelProps.put(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED);
            emailRelProps.put(GraphConstants.PROP_OCCURRED_AT, date);
        } else {
            emailRelProps = new LinkedHashMap<>(ExtractorUtils.PROVENANCE_MAP);
        }

        String msgEntityId = messageId != null ? entityId(messageId) : entityId(subject + date);
        Map<String, String> msgProps = new LinkedHashMap<>();
        if (subject != null) msgProps.put("subject", subject);
        if (date != null) msgProps.put("date", date);
        if (messageId != null) msgProps.put("messageId", messageId);
        if (meta.get(GraphConstants.META_EMAIL_FOLDER) != null) msgProps.put("folder", str(meta.get(GraphConstants.META_EMAIL_FOLDER)));
        if (meta.get(GraphConstants.META_EMAIL_PST_FOLDER) != null) msgProps.put("pstFolder", str(meta.get(GraphConstants.META_EMAIL_PST_FOLDER)));
        if (meta.get(GraphConstants.META_SOURCE_TYPE) != null) msgProps.put("sourceType", str(meta.get(GraphConstants.META_SOURCE_TYPE)));
        String mimeType = str(meta.get(GraphConstants.META_EMAIL_MIME_TYPE));
        if (mimeType != null) msgProps.put("mimeType", mimeType);
        String mailer = str(meta.get(GraphConstants.META_EMAIL_MAILER));
        if (mailer != null) msgProps.put("mailer", mailer);
        String userAgent = str(meta.get(GraphConstants.META_EMAIL_USER_AGENT));
        if (userAgent != null) msgProps.put("userAgent", userAgent);
        // IMAP message flags + reply/auto-reply metadata
        ExtractorUtils.copyMetaToProps(msgProps, meta,
                GraphConstants.META_EMAIL_FLAG_SEEN, "flagSeen",
                GraphConstants.META_EMAIL_FLAG_FLAGGED, "flagFlagged",
                GraphConstants.META_EMAIL_FLAG_DRAFT, "flagDraft",
                GraphConstants.META_EMAIL_FLAG_ANSWERED, "flagAnswered",
                GraphConstants.META_EMAIL_FLAG_DELETED, "flagDeleted",
                GraphConstants.META_EMAIL_REPLY_TO, GraphConstants.PROP_REPLY_TO,
                GraphConstants.META_EMAIL_RETURN_PATH, GraphConstants.PROP_RETURN_PATH,
                GraphConstants.META_EMAIL_AUTO_SUBMITTED, GraphConstants.PROP_AUTO_SUBMITTED,
                GraphConstants.META_EMAIL_PRECEDENCE, "precedence"
        );
        if (Boolean.TRUE.equals(meta.get(GraphConstants.META_EMAIL_IS_AUTO_REPLY)))
            msgProps.put(GraphConstants.PROP_IS_AUTO_REPLY, "true");
        String maildirFlags = str(meta.get(GraphConstants.META_EMAIL_MAILDIR_FLAGS));
        if (maildirFlags != null) msgProps.put("maildirFlags", maildirFlags);
        String maildirSubdir = str(meta.get(GraphConstants.META_EMAIL_MAILDIR_SUBDIR));
        if (maildirSubdir != null) msgProps.put("maildirSubdir", maildirSubdir);
        Object mboxIndex = meta.get(GraphConstants.META_EMAIL_MBOX_INDEX);
        if (mboxIndex != null) msgProps.put("mboxIndex", mboxIndex.toString());
        String mboxFile = str(meta.get(GraphConstants.META_EMAIL_MBOX_FILE));
        if (mboxFile != null) msgProps.put("mboxFile", mboxFile);
        String emailFormat = str(meta.get(GraphConstants.META_EMAIL_FORMAT));
        if (emailFormat != null) msgProps.put("format", emailFormat);
        // IMAP user-defined flags (e.g. $Forwarded, $MDNSent, $Phishing, $NotJunk)
        Object userFlagsObj = meta.get(GraphConstants.META_EMAIL_USER_FLAGS);
        if (userFlagsObj instanceof List<?> userFlagsList && !userFlagsList.isEmpty()) {
            String joined = userFlagsList.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
            if (!joined.isEmpty()) msgProps.put("userFlags", joined);
        }

        Object attachmentCount = meta.get("email.attachmentCount");
        if (attachmentCount != null) msgProps.put("attachmentCount", attachmentCount.toString());
        // Exchange/IMAP Thread-Index conversation ID
        String conversationId = str(meta.get("email.conversationId"));
        if (conversationId != null) msgProps.put("conversationId", conversationId);
        // Nested message metadata (RFC-822 attachments)
        String nestedSubject = str(meta.get("email.nestedSubject"));
        if (nestedSubject != null) msgProps.put("nestedSubject", nestedSubject);
        String nestedFrom = str(meta.get("email.nestedFrom"));
        if (nestedFrom != null) msgProps.put("nestedFrom", nestedFrom);
        Object nestingDepth = meta.get("email.nestingDepth");
        if (nestingDepth != null) msgProps.put("nestingDepth", nestingDepth.toString());

        ExtractedEntity msgEntity = new ExtractedEntity(
                msgEntityId,
                subject != null ? subject : "(no subject)",
                GraphConstants.ENTITY_EMAIL_MESSAGE,
                null, // aliases
                "Email: " + (subject != null ? subject : "(no subject)"),
                1.0,
                msgProps
        );
        addEntity(entityIndex, msgEntity);

        // ── DATE entity from email date ──────────────────────────────────
        if (date != null) {
            String emailDateId = entityId("date:" + date);
            addEntity(entityIndex, new ExtractedEntity(
                    emailDateId, date, GraphConstants.ENTITY_DATE,
                    null, "Email date: " + date, 0.9,
                    Map.of("date", date, "dateType", "sent")
            ));
            relations.add(new ExtractedRelation(
                    msgEntityId, emailDateId, GraphConstants.REL_PUBLISHED_ON,
                    (subject != null ? subject : "Email") + " sent on " + date,
                    0.9, null
            ));
        }

        // From → SENT_BY
        String fromField = str(meta.get(GraphConstants.META_EMAIL_FROM));
        if (fromField != null) {
            List<PersonEntity> senders = parsePersons(fromField);
            // Enrich with explicit fromName/fromAddress when the parsed entity lacks a name
            String explicitFromName = str(meta.get(GraphConstants.META_EMAIL_FROM_NAME));
            String explicitFromAddr = str(meta.get(GraphConstants.META_EMAIL_FROM_ADDRESS));
            for (PersonEntity sender : senders) {
                ExtractedEntity senderEntity = sender.entity;
                // If the entity name is just an email address but we have a display name, enrich it
                if (explicitFromName != null && senderEntity.name().contains("@")) {
                    String enrichedId = explicitFromAddr != null
                            ? entityId(explicitFromAddr.toLowerCase()) : senderEntity.id();
                    Map<String, String> enrichedProps = new LinkedHashMap<>(senderEntity.properties());
                    enrichedProps.put("displayName", explicitFromName);
                    senderEntity = new ExtractedEntity(
                            enrichedId, explicitFromName, senderEntity.type(),
                            senderEntity.aliases(), senderEntity.description(),
                            senderEntity.confidence(), enrichedProps);
                }
                addEntity(entityIndex, senderEntity);
                relations.add(new ExtractedRelation(
                        msgEntityId, senderEntity.id(), GraphConstants.REL_SENT_BY,
                        senderEntity.name() + " sent this email",
                        1.0, emailRelProps
                ));
            }
        }

        // Sender domain → ORGANIZATION (extract org from email domain)
        String senderAddress = str(meta.get(GraphConstants.META_EMAIL_FROM_ADDRESS));
        if (senderAddress == null && fromField != null && fromField.contains("@")) {
            // Fallback: extract from the raw from field
            int atIdx = fromField.indexOf('@');
            if (atIdx > 0 && atIdx < fromField.length() - 1) {
                String afterAt = fromField.substring(atIdx + 1);
                // Strip trailing > or whitespace
                senderAddress = afterAt.replaceAll("[>\\s].*$", "").trim();
            }
        }
        if (senderAddress != null && senderAddress.contains("@")) {
            senderAddress = senderAddress.substring(senderAddress.indexOf('@') + 1).trim();
        }
        if (senderAddress != null && !senderAddress.isBlank()
                && senderAddress.contains(".") && !senderAddress.startsWith(".")
                // Skip common free email providers — they're not meaningful organizations
                && !Set.of("gmail.com", "yahoo.com", "hotmail.com", "outlook.com",
                           "aol.com", "icloud.com", "mail.com", "protonmail.com",
                           "zoho.com", "yandex.com", "live.com", "msn.com")
                        .contains(senderAddress.toLowerCase())) {
            String domainOrgId = entityId("org:" + senderAddress.toLowerCase());
            Map<String, String> orgProps = new LinkedHashMap<>();
            orgProps.put("name", senderAddress);
            orgProps.put("domain", senderAddress.toLowerCase());
            orgProps.put(GraphConstants.PROP_SOURCE_FIELD, "email.fromAddress");
            addEntity(entityIndex, new ExtractedEntity(
                    domainOrgId, senderAddress, GraphConstants.ENTITY_ORGANIZATION,
                    null, "Organization (email domain): " + senderAddress, 0.7, orgProps));
            relations.add(new ExtractedRelation(
                    msgEntityId, domainOrgId, GraphConstants.REL_AFFILIATED_WITH,
                    "Email from " + senderAddress + " organization",
                    0.7, null));
            // Link sender person to their organization
            String explicitSenderAddr = str(meta.get(GraphConstants.META_EMAIL_FROM_ADDRESS));
            if (explicitSenderAddr == null && fromField != null && fromField.contains("@")) {
                explicitSenderAddr = fromField;
            }
            if (explicitSenderAddr != null) {
                String senderPersonId = entityId(explicitSenderAddr.toLowerCase());
                relations.add(new ExtractedRelation(
                        senderPersonId, domainOrgId, GraphConstants.REL_AFFILIATED_WITH,
                        "Sender affiliated with " + senderAddress, 0.7, null));
            }
        }

        // To → SENT_TO (handles both String and List<String> from different loaders)
        String toField = strOrJoinList(meta.get(GraphConstants.META_EMAIL_TO));
        if (toField != null) {
            List<PersonEntity> recipients = parsePersons(toField);
            for (PersonEntity recipient : recipients) {
                addEntity(entityIndex, recipient.entity);
                relations.add(new ExtractedRelation(
                        msgEntityId, recipient.entity.id(), GraphConstants.REL_SENT_TO,
                        "Email sent to " + recipient.entity.name(),
                        1.0, emailRelProps
                ));
            }
        }

        // Cc → CC_TO
        String ccField = strOrJoinList(meta.get(GraphConstants.META_EMAIL_CC));
        if (ccField != null) {
            List<PersonEntity> ccRecipients = parsePersons(ccField);
            for (PersonEntity cc : ccRecipients) {
                addEntity(entityIndex, cc.entity);
                relations.add(new ExtractedRelation(
                        msgEntityId, cc.entity.id(), GraphConstants.REL_CC_TO,
                        "Email CC'd to " + cc.entity.name(),
                        1.0, emailRelProps
                ));
            }
        }

        // Bcc → BCC_TO
        String bccField = strOrJoinList(meta.get(GraphConstants.META_EMAIL_BCC));
        if (bccField != null) {
            List<PersonEntity> bccRecipients = parsePersons(bccField);
            for (PersonEntity bcc : bccRecipients) {
                addEntity(entityIndex, bcc.entity);
                relations.add(new ExtractedRelation(
                        msgEntityId, bcc.entity.id(), GraphConstants.REL_BCC_TO,
                        "Email BCC'd to " + bcc.entity.name(),
                        0.9, emailRelProps
                ));
            }
        }

        // Reply-To → REPLY_TO_DIRECTED_AT (person entity for Reply-To address)
        String replyToField = strOrJoinList(meta.get(GraphConstants.META_EMAIL_REPLY_TO));
        if (replyToField != null) {
            List<PersonEntity> replyToPersons = parsePersons(replyToField);
            for (PersonEntity rtp : replyToPersons) {
                addEntity(entityIndex, rtp.entity);
                relations.add(new ExtractedRelation(
                        msgEntityId, rtp.entity.id(), GraphConstants.REL_REPLY_TO_DIRECTED_AT,
                        "Replies to this email should go to " + rtp.entity.name(),
                        0.9, emailRelProps
                ));
            }
        }

        // In-Reply-To → REPLIED_TO
        String inReplyTo = str(meta.get(GraphConstants.META_EMAIL_IN_REPLY_TO));
        if (inReplyTo != null) {
            String replyTargetId = entityId(inReplyTo);
            ExtractedEntity replyTarget = new ExtractedEntity(
                    replyTargetId, inReplyTo, GraphConstants.ENTITY_EMAIL_MESSAGE,
                    null, "Referenced email message", 0.8,
                    Map.of("messageId", inReplyTo)
            );
            addEntity(entityIndex, replyTarget);
            relations.add(new ExtractedRelation(
                    msgEntityId, replyTargetId, GraphConstants.REL_REPLIED_TO,
                    "This email is a reply to " + inReplyTo,
                    1.0, emailRelProps
            ));
        }

        // References → REFERENCES
        Object refsObj = meta.get(GraphConstants.META_EMAIL_REFERENCES);
        if (refsObj instanceof List<?> refsList) {
            for (Object ref : refsList) {
                String refId = str(ref);
                if (refId != null && !refId.equals(inReplyTo)) {
                    String refEntityId = entityId(refId);
                    ExtractedEntity refEntity = new ExtractedEntity(
                            refEntityId, refId, GraphConstants.ENTITY_EMAIL_MESSAGE,
                            null, "Referenced email message", 0.7,
                            Map.of("messageId", refId)
                    );
                    addEntity(entityIndex, refEntity);
                    relations.add(new ExtractedRelation(
                            msgEntityId, refEntityId, GraphConstants.REL_REFERENCES,
                            "This email references " + refId,
                            0.9, emailRelProps
                    ));
                }
            }
        }

        // Thread grouping → IN_THREAD
        // Derive a thread ID from the References chain root (the original message that started the thread).
        // If no References, use In-Reply-To as the thread root. If neither, this is a standalone message.
        String threadRootId = null;
        if (refsObj instanceof List<?> refList2 && !refList2.isEmpty()) {
            threadRootId = str(refList2.get(0));
        }
        if (threadRootId == null && inReplyTo != null) {
            threadRootId = inReplyTo;
        }
        if (threadRootId != null) {
            String threadEntityId = entityId("email_thread:" + threadRootId);
            String threadLabel = subject != null ? "Thread: " + subject : "Thread: " + threadRootId;
            Map<String, String> threadProps = new LinkedHashMap<>();
            threadProps.put("rootMessageId", threadRootId);
            if (subject != null) threadProps.put("subject", subject);
            if (conversationId != null) threadProps.put("conversationId", conversationId);
            addEntity(entityIndex, new ExtractedEntity(
                    threadEntityId, threadLabel, GraphConstants.ENTITY_EMAIL_THREAD,
                    null, "Email thread rooted at " + threadRootId, 0.9, threadProps
            ));
            relations.add(new ExtractedRelation(
                    msgEntityId, threadEntityId, GraphConstants.REL_IN_THREAD,
                    (subject != null ? subject : "Email") + " is part of thread",
                    0.9, emailRelProps
            ));
        }

        // Mailing list → POSTED_TO
        String listId = str(meta.get(GraphConstants.META_EMAIL_LIST_ID));
        if (listId != null) {
            String listEntityId = entityId("list:" + listId);
            Map<String, String> listProps = new LinkedHashMap<>();
            listProps.put("listId", listId);
            String listPost = str(meta.get(GraphConstants.META_EMAIL_LIST_POST));
            if (listPost != null) listProps.put("postingAddress", listPost);
            ExtractedEntity listEntity = new ExtractedEntity(
                    listEntityId, listId, GraphConstants.ENTITY_MAILING_LIST,
                    null, "Mailing list: " + listId, 1.0, listProps
            );
            addEntity(entityIndex, listEntity);
            relations.add(new ExtractedRelation(
                    msgEntityId, listEntityId, GraphConstants.REL_POSTED_TO,
                    "Email posted to mailing list " + listId,
                    1.0, emailRelProps
            ));
        }

        // Conversation topic → HAS_CONVERSATION_TOPIC (from PST's conversationTopic)
        String conversationTopic = str(meta.get(GraphConstants.META_EMAIL_CONVERSATION_TOPIC));
        if (conversationTopic != null) {
            msgProps.put("conversationTopic", conversationTopic);
            String topicEntityId = entityId("email_topic:" + conversationTopic.toLowerCase());
            ExtractedEntity topicEntity = new ExtractedEntity(
                    topicEntityId, conversationTopic, GraphConstants.ENTITY_CONVERSATION_TOPIC,
                    null, "Email conversation topic: " + conversationTopic, 0.9,
                    Map.of("topic", conversationTopic)
            );
            addEntity(entityIndex, topicEntity);
            relations.add(new ExtractedRelation(
                    msgEntityId, topicEntityId, GraphConstants.REL_HAS_CONVERSATION_TOPIC,
                    "Email in conversation: " + conversationTopic,
                    0.9, emailRelProps
            ));
        }

        // Color categories (Outlook labels) → TOPIC entities with HAS_TOPIC
        Object categoriesObj = meta.get("email.categories");
        if (categoriesObj instanceof List<?> categoriesList && !categoriesList.isEmpty()) {
            for (Object catObj : categoriesList) {
                String category = catObj != null ? catObj.toString().trim() : null;
                if (category == null || category.isEmpty()) continue;
                String catEntityId = entityId("email_category:" + category.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(
                        catEntityId, category, GraphConstants.ENTITY_TOPIC,
                        null, "Email category: " + category, 0.9,
                        Map.of("category", category, "source", "outlook")
                ));
                relations.add(new ExtractedRelation(
                        msgEntityId, catEntityId, GraphConstants.REL_HAS_TOPIC,
                        "Email categorized as: " + category,
                        0.9, emailRelProps
                ));
            }
        }
        // Sensitivity level as property on message entity
        String sensitivity = str(meta.get("email.sensitivity"));
        if (sensitivity != null) {
            msgProps.put("sensitivity", sensitivity);
        }
        // Delivery time
        String deliveryTime = str(meta.get("email.deliveryTime"));
        if (deliveryTime != null) {
            msgProps.put("deliveryTime", deliveryTime);
        }

        // Folder → IN_FOLDER (from email.folder, email.pstFolder, email.maildirSubdir)
        // Also builds folder hierarchy with SUBFOLDER_OF relations for nested paths
        String folder = str(meta.get(GraphConstants.META_EMAIL_FOLDER));
        if (folder == null) folder = str(meta.get(GraphConstants.META_EMAIL_PST_FOLDER));
        if (folder == null) folder = str(meta.get(GraphConstants.META_EMAIL_MAILDIR_SUBDIR));
        if (folder != null && !folder.isBlank()) {
            // Split folder path to build hierarchy (e.g., "Work/Projects/Alpha" or "INBOX.Sent")
            String separator = folder.contains("/") ? "/" : (folder.contains(".") ? "\\." : null);
            String[] parts = separator != null ? folder.split(separator) : new String[]{folder};
            String leafFolderEntityId = null;
            String previousFolderId = null;
            StringBuilder pathSoFar = new StringBuilder();
            for (int fi = 0; fi < parts.length; fi++) {
                String part = parts[fi].trim();
                if (part.isEmpty()) continue;
                if (pathSoFar.length() > 0) pathSoFar.append("/");
                pathSoFar.append(part);
                String currentFolderId = entityId("email_folder:" + pathSoFar.toString().toLowerCase());
                Map<String, String> folderProps = new LinkedHashMap<>();
                folderProps.put(GraphConstants.PROP_FOLDER_NAME, part);
                folderProps.put(GraphConstants.PROP_FOLDER_PATH, pathSoFar.toString());
                folderProps.put(GraphConstants.PROP_DEPTH, String.valueOf(fi));
                addEntity(entityIndex, new ExtractedEntity(
                        currentFolderId, part, GraphConstants.ENTITY_EMAIL_FOLDER,
                        null, "Email folder: " + pathSoFar, 0.9, folderProps));
                if (previousFolderId != null) {
                    relations.add(new ExtractedRelation(
                            currentFolderId, previousFolderId, GraphConstants.REL_SUBFOLDER_OF,
                            part + " is subfolder of " + parts[fi - 1],
                            0.9, emailRelProps));
                }
                previousFolderId = currentFolderId;
                leafFolderEntityId = currentFolderId;
            }
            if (leafFolderEntityId != null) {
                relations.add(new ExtractedRelation(
                        msgEntityId, leafFolderEntityId, GraphConstants.REL_IN_FOLDER,
                        "Email in folder " + folder,
                        1.0, emailRelProps));
            }
        }

        // Attachments → HAS_ATTACHMENT
        // Support both singular (email.attachmentName) and list (email.attachmentNames) keys
        java.util.Set<String> attachNames = new java.util.LinkedHashSet<>();
        Object attachNamesObj = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES);
        // Build a name→size map from the parallel email.attachmentSizes list (if present)
        Map<String, String> attachSizeByName = new LinkedHashMap<>();
        Object attachSizesObj = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZES);
        if (attachNamesObj instanceof List && attachSizesObj instanceof List) {
            List<?> namesList = (List<?>) attachNamesObj;
            List<?> sizesList = (List<?>) attachSizesObj;
            for (int i = 0; i < namesList.size(); i++) {
                String n = namesList.get(i) != null ? namesList.get(i).toString() : null;
                String s = (i < sizesList.size() && sizesList.get(i) != null)
                        ? sizesList.get(i).toString() : null;
                if (n != null && !n.isBlank() && s != null) {
                    attachSizeByName.put(n, s);
                }
            }
        }
        if (attachNamesObj instanceof List) {
            for (Object item : (List<?>) attachNamesObj) {
                if (item != null && !item.toString().isBlank()) {
                    attachNames.add(item.toString());
                }
            }
        }
        String singleAttachName = str(meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAME));
        if (singleAttachName != null) {
            attachNames.add(singleAttachName);
        }
        for (String attachmentName : attachNames) {
            String attachMimeType = str(meta.get(GraphConstants.META_EMAIL_ATTACHMENT_MIME_TYPE));
            boolean isIcs = "text/calendar".equalsIgnoreCase(attachMimeType)
                    || attachmentName.toLowerCase().endsWith(".ics");

            if (isIcs) {
                // Produce a CALENDAR_EVENT entity instead of a plain ATTACHMENT
                String calEntityId = entityId("cal:" + msgEntityId + ":" + attachmentName);
                Map<String, String> calProps = new LinkedHashMap<>();
                calProps.put("filename", attachmentName);
                if (attachMimeType != null) calProps.put("mimeType", attachMimeType);
                String calAttachSize = attachSizeByName.containsKey(attachmentName)
                        ? attachSizeByName.get(attachmentName)
                        : (meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZE) != null ? meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZE).toString() : null);
                if (calAttachSize != null) calProps.put("size", calAttachSize);
                // Populate ICS fields if the loader stored them
                String icsContent = str(meta.get(GraphConstants.META_EMAIL_ICS_CONTENT));
                if (icsContent != null) {
                    extractIcsFields(icsContent, calProps);
                }
                String summary = calProps.getOrDefault("summary", attachmentName);
                ExtractedEntity calEntity = new ExtractedEntity(
                        calEntityId, summary, GraphConstants.ENTITY_CALENDAR_EVENT,
                        null, "Calendar event from: " + attachmentName, 1.0, calProps
                );
                addEntity(entityIndex, calEntity);
                relations.add(new ExtractedRelation(
                        msgEntityId, calEntityId, GraphConstants.REL_HAS_CALENDAR_EVENT,
                        "Email has calendar event: " + summary,
                        1.0, emailRelProps
                ));
                // Organizer relationship
                if (calProps.containsKey("organizer")) {
                    String orgEmail = calProps.get("organizer");
                    String orgId = entityId(orgEmail.toLowerCase());
                    Map<String, String> orgProps = new LinkedHashMap<>();
                    orgProps.put("email", orgEmail);
                    ExtractedEntity orgEntity = new ExtractedEntity(
                            orgId, orgEmail, GraphConstants.ENTITY_PERSON, null,
                            "Calendar event organizer", 0.9, orgProps
                    );
                    addEntity(entityIndex, orgEntity);
                    relations.add(new ExtractedRelation(
                            calEntityId, orgId, GraphConstants.REL_ORGANIZED_BY,
                            "Calendar event organized by " + orgEmail,
                            1.0, emailRelProps
                    ));
                }
                // Attendee relationships
                String attendeesRaw = calProps.get("attendees");
                if (attendeesRaw != null) {
                    for (String attendee : attendeesRaw.split(",")) {
                        attendee = attendee.trim();
                        if (attendee.isEmpty()) continue;
                        String attId = entityId(attendee.toLowerCase());
                        Map<String, String> attProps = new LinkedHashMap<>();
                        attProps.put("email", attendee);
                        ExtractedEntity attEntity = new ExtractedEntity(
                                attId, attendee, GraphConstants.ENTITY_PERSON, null,
                                "Calendar event attendee", 0.9, attProps
                        );
                        addEntity(entityIndex, attEntity);
                        relations.add(new ExtractedRelation(
                                calEntityId, attId, GraphConstants.REL_ATTENDED_BY,
                                "Calendar event attended by " + attendee,
                                1.0, emailRelProps
                        ));
                    }
                }
                // Location entity
                if (calProps.containsKey("location")) {
                    String locName = calProps.get("location");
                    String locId = entityId("location:" + locName.toLowerCase());
                    ExtractedEntity locEntity = new ExtractedEntity(
                            locId, locName, GraphConstants.ENTITY_LOCATION, null,
                            "Calendar event location", 0.9,
                            Map.of("locationName", locName)
                    );
                    addEntity(entityIndex, locEntity);
                    relations.add(new ExtractedRelation(
                            calEntityId, locId, GraphConstants.REL_AT_LOCATION,
                            "Calendar event at " + locName,
                            1.0, emailRelProps
                    ));
                }
                // DATE entities from dtstart/dtend
                String dtstart = calProps.get("dtstart");
                if (dtstart != null && !dtstart.isBlank()) {
                    String startDateId = entityId("date:" + dtstart);
                    addEntity(entityIndex, new ExtractedEntity(
                            startDateId, dtstart, GraphConstants.ENTITY_DATE,
                            null, "Calendar event start: " + dtstart, 0.9,
                            Map.of("date", dtstart, "dateType", "eventStart")));
                    relations.add(new ExtractedRelation(
                            calEntityId, startDateId, GraphConstants.REL_STARTS_ON,
                            summary + " starts on " + dtstart,
                            0.9, emailRelProps));
                }
                String dtend = calProps.get("dtend");
                if (dtend != null && !dtend.isBlank()) {
                    String endDateId = entityId("date:" + dtend);
                    addEntity(entityIndex, new ExtractedEntity(
                            endDateId, dtend, GraphConstants.ENTITY_DATE,
                            null, "Calendar event end: " + dtend, 0.9,
                            Map.of("date", dtend, "dateType", "eventEnd")));
                    relations.add(new ExtractedRelation(
                            calEntityId, endDateId, GraphConstants.REL_ENDS_ON,
                            summary + " ends on " + dtend,
                            0.9, emailRelProps));
                }
            } else {
                String attachEntityId = entityId("attach:" + msgEntityId + ":" + attachmentName);
                Map<String, String> attachProps = new LinkedHashMap<>();
                attachProps.put("filename", attachmentName);
                if (attachMimeType != null) {
                    attachProps.put("mimeType", attachMimeType);
                }
                String resolvedAttachSize = attachSizeByName.containsKey(attachmentName)
                        ? attachSizeByName.get(attachmentName)
                        : (meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZE) != null ? meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZE).toString() : null);
                if (resolvedAttachSize != null) attachProps.put("size", resolvedAttachSize);
                ExtractedEntity attachEntity = new ExtractedEntity(
                        attachEntityId, attachmentName, GraphConstants.ENTITY_EMAIL_ATTACHMENT,
                        null, "Email attachment: " + attachmentName, 1.0,
                        attachProps
                );
                addEntity(entityIndex, attachEntity);
                relations.add(new ExtractedRelation(
                        msgEntityId, attachEntityId, GraphConstants.REL_HAS_ATTACHMENT,
                        "Email has attachment: " + attachmentName,
                        1.0, emailRelProps
                ));
            }
        }

        // Handle standalone attachment documents (from IMAP loader with email.isAttachment=true)
        Object isAttachment = meta.get(GraphConstants.META_EMAIL_IS_ATTACHMENT);
        if (isAttachment != null && (Boolean.TRUE.equals(isAttachment) || "true".equalsIgnoreCase(str(isAttachment)))) {
            String parentMsgId = str(meta.get(GraphConstants.META_EMAIL_PARENT_MESSAGE_ID));
            String attachName = str(meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAME));
            String attachMime = str(meta.get(GraphConstants.META_EMAIL_ATTACHMENT_MIME_TYPE));
            if (attachName != null) {
                String parentEntityId = parentMsgId != null ? entityId(parentMsgId) : msgEntityId;
                boolean isIcs = "text/calendar".equalsIgnoreCase(attachMime)
                        || attachName.toLowerCase().endsWith(".ics");

                if (isIcs) {
                    String calEntityId = entityId("cal:" + parentEntityId + ":" + attachName);
                    Map<String, String> calProps = new LinkedHashMap<>();
                    calProps.put("filename", attachName);
                    if (attachMime != null) calProps.put("mimeType", attachMime);
                    String attachSize = str(meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZE));
                    if (attachSize != null) calProps.put("size", attachSize);
                    if (parentMsgId != null) calProps.put("parentMessageId", parentMsgId);
                    String icsContent = str(meta.get(GraphConstants.META_EMAIL_ICS_CONTENT));
                    if (icsContent != null) {
                        extractIcsFields(icsContent, calProps);
                    }
                    String summary = calProps.getOrDefault("summary", attachName);
                    ExtractedEntity calEntity = new ExtractedEntity(
                            calEntityId, summary, GraphConstants.ENTITY_CALENDAR_EVENT,
                            null, "Calendar event from: " + attachName, 1.0, calProps
                    );
                    addEntity(entityIndex, calEntity);
                    // Always link calendar event to parent entity (msgEntityId if parentMsgId absent)
                    if (parentMsgId != null) {
                        String parentSubject = str(meta.get(GraphConstants.META_EMAIL_PARENT_SUBJECT));
                        String parentFrom = str(meta.get(GraphConstants.META_EMAIL_PARENT_FROM));
                        String parentDate = str(meta.get(GraphConstants.META_EMAIL_PARENT_DATE));
                        Map<String, String> parentProps = new LinkedHashMap<>();
                        parentProps.put("messageId", parentMsgId);
                        if (parentFrom != null) parentProps.put("senderName", parentFrom);
                        if (parentDate != null) parentProps.put("date", parentDate);
                        ExtractedEntity parentMsg = new ExtractedEntity(
                                parentEntityId, parentSubject != null ? parentSubject : parentMsgId,
                                GraphConstants.ENTITY_EMAIL_MESSAGE, null, "Parent email message", 0.8,
                                parentProps
                        );
                        addEntity(entityIndex, parentMsg);
                    }
                    relations.add(new ExtractedRelation(
                            parentEntityId, calEntityId, GraphConstants.REL_HAS_CALENDAR_EVENT,
                            "Email has calendar event: " + summary,
                            1.0, emailRelProps
                    ));
                    // Create DATE entities from dtstart/dtend
                    String dtstart = calProps.get("dtstart");
                    if (dtstart != null && !dtstart.isBlank()) {
                        String startDateId = entityId("date:" + dtstart);
                        addEntity(entityIndex, new ExtractedEntity(
                                startDateId, dtstart, GraphConstants.ENTITY_DATE,
                                null, "Calendar event start: " + dtstart, 0.9,
                                Map.of("date", dtstart, "dateType", "eventStart")));
                        relations.add(new ExtractedRelation(
                                calEntityId, startDateId, GraphConstants.REL_STARTS_ON,
                                summary + " starts on " + dtstart,
                                0.9, emailRelProps));
                    }
                    String dtend = calProps.get("dtend");
                    if (dtend != null && !dtend.isBlank()) {
                        String endDateId = entityId("date:" + dtend);
                        addEntity(entityIndex, new ExtractedEntity(
                                endDateId, dtend, GraphConstants.ENTITY_DATE,
                                null, "Calendar event end: " + dtend, 0.9,
                                Map.of("date", dtend, "dateType", "eventEnd")));
                        relations.add(new ExtractedRelation(
                                calEntityId, endDateId, GraphConstants.REL_ENDS_ON,
                                summary + " ends on " + dtend,
                                0.9, emailRelProps));
                    }
                } else {
                    String attachEntityId = entityId("attach:" + parentEntityId + ":" + attachName);
                    Map<String, String> attachProps = new LinkedHashMap<>();
                    attachProps.put("filename", attachName);
                    if (attachMime != null) attachProps.put("mimeType", attachMime);
                    String attachSize = str(meta.get(GraphConstants.META_EMAIL_ATTACHMENT_SIZE));
                    if (attachSize != null) attachProps.put("size", attachSize);
                    if (parentMsgId != null) attachProps.put("parentMessageId", parentMsgId);
                    ExtractedEntity attachEntity = new ExtractedEntity(
                            attachEntityId, attachName, GraphConstants.ENTITY_EMAIL_ATTACHMENT,
                            null, "Email attachment: " + attachName, 1.0, attachProps
                    );
                    addEntity(entityIndex, attachEntity);
                    // Create parent message entity if we know its ID
                    if (parentMsgId != null) {
                        String parentSubject = str(meta.get(GraphConstants.META_EMAIL_PARENT_SUBJECT));
                        String parentFrom = str(meta.get(GraphConstants.META_EMAIL_PARENT_FROM));
                        String parentDate = str(meta.get(GraphConstants.META_EMAIL_PARENT_DATE));
                        Map<String, String> parentProps = new LinkedHashMap<>();
                        parentProps.put("messageId", parentMsgId);
                        if (parentFrom != null) parentProps.put("senderName", parentFrom);
                        if (parentDate != null) parentProps.put("date", parentDate);
                        ExtractedEntity parentMsg = new ExtractedEntity(
                                parentEntityId, parentSubject != null ? parentSubject : parentMsgId,
                                GraphConstants.ENTITY_EMAIL_MESSAGE, null, "Parent email message", 0.8,
                                parentProps
                        );
                        addEntity(entityIndex, parentMsg);
                    }
                    // Always link attachment to parent entity (msgEntityId if parentMsgId absent)
                    relations.add(new ExtractedRelation(
                            parentEntityId, attachEntityId, GraphConstants.REL_HAS_ATTACHMENT,
                            "Email has attachment: " + attachName,
                            1.0, emailRelProps
                    ));
                }
            }
        }

        // Received headers → MAIL_SERVER entities + ROUTED_VIA relations (SMTP relay chain)
        Object receivedObj = meta.get(GraphConstants.META_EMAIL_RECEIVED_HEADERS);
        if (receivedObj instanceof List<?> receivedList && !receivedList.isEmpty()) {
            String previousServerId = null;
            for (int ri = 0; ri < receivedList.size(); ri++) {
                String header = str(receivedList.get(ri));
                if (header == null || header.isBlank()) continue;
                // Parse "from <hostname>" and "by <hostname>" from the Received header
                String fromHost = parseReceivedHost(header, "from");
                String byHost = parseReceivedHost(header, "by");
                // Create MAIL_SERVER entity for the "by" host (the receiving server)
                String byServerId = null;
                if (byHost != null) {
                    byServerId = entityId("mailserver:" + byHost.toLowerCase());
                    Map<String, String> serverProps = new LinkedHashMap<>();
                    serverProps.put("hostname", byHost);
                    serverProps.put("role", "receiving");
                    addEntity(entityIndex, new ExtractedEntity(
                            byServerId, byHost, GraphConstants.ENTITY_MAIL_SERVER,
                            null, "Mail server: " + byHost, 0.8, serverProps));
                }
                // Create MAIL_SERVER entity for the "from" host (the sending relay)
                String fromServerId = null;
                if (fromHost != null) {
                    fromServerId = entityId("mailserver:" + fromHost.toLowerCase());
                    Map<String, String> serverProps = new LinkedHashMap<>();
                    serverProps.put("hostname", fromHost);
                    serverProps.put("role", "sending");
                    addEntity(entityIndex, new ExtractedEntity(
                            fromServerId, fromHost, GraphConstants.ENTITY_MAIL_SERVER,
                            null, "Mail server: " + fromHost, 0.8, serverProps));
                }
                // ROUTED_VIA: from-server → by-server (within the same hop)
                if (fromServerId != null && byServerId != null) {
                    relations.add(new ExtractedRelation(
                            fromServerId, byServerId, GraphConstants.REL_ROUTED_VIA,
                            "Mail relayed from " + fromHost + " to " + byHost,
                            0.8, provenanceWithOccurredAt(emailRelProps, "hopIndex", String.valueOf(ri))));
                }
                // Chain hops: link previous hop's by-server to this hop's from-server
                if (previousServerId != null && fromServerId != null && !previousServerId.equals(fromServerId)) {
                    relations.add(new ExtractedRelation(
                            previousServerId, fromServerId, GraphConstants.REL_ROUTED_VIA,
                            "Mail relay chain",
                            0.7, emailRelProps));
                }
                // Link the message to the first receiving server (final destination)
                if (ri == 0 && byServerId != null) {
                    relations.add(new ExtractedRelation(
                            msgEntityId, byServerId, GraphConstants.REL_ROUTED_VIA,
                            "Email delivered via " + byHost,
                            0.9, emailRelProps));
                }
                previousServerId = byServerId;
            }
        }

        // Extract inline URLs from the email body → HYPERLINKS_TO → EXTERNAL_RESOURCE
        Set<String> seenUrls = new LinkedHashSet<>();
        String bodyContent = doc.getText();
        if (bodyContent != null && !bodyContent.isBlank()) {
            Matcher urlMatcher = URL_PATTERN.matcher(bodyContent);
            while (urlMatcher.find() && seenUrls.size() < 50) {
                String url = urlMatcher.group();
                if (seenUrls.add(url)) {
                    String urlEntityId = entityId("url:" + url);
                    Map<String, String> urlProps = new LinkedHashMap<>();
                    urlProps.put("url", url);
                    ExtractedEntity urlEntity = new ExtractedEntity(
                            urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                            null, "URL referenced in email", 0.8, urlProps
                    );
                    addEntity(entityIndex, urlEntity);
                    relations.add(new ExtractedRelation(
                            msgEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                            "Email contains link to " + url,
                            0.8, emailRelProps
                    ));
                }
            }
        }

        // Extract href links from HTML body → HYPERLINKS_TO → EXTERNAL_LINK
        // HTML bodies contain <a href="..."> links not visible in the plain-text version
        String htmlBody = str(meta.get(GraphConstants.META_EMAIL_HTML_BODY));
        if (htmlBody != null) {
            Matcher hrefMatcher = HTML_HREF_PATTERN.matcher(htmlBody);
            while (hrefMatcher.find() && seenUrls.size() < 100) {
                String url = hrefMatcher.group(1);
                if (seenUrls.add(url)) {
                    String urlEntityId = entityId("url:" + url);
                    Map<String, String> urlProps = new LinkedHashMap<>();
                    urlProps.put("url", url);
                    urlProps.put(GraphConstants.META_SOURCE, "htmlBody");
                    ExtractedEntity urlEntity = new ExtractedEntity(
                            urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_LINK,
                            null, "URL linked in HTML email body", 0.8, urlProps
                    );
                    addEntity(entityIndex, urlEntity);
                    relations.add(new ExtractedRelation(
                            msgEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                            "Email HTML body links to " + url,
                            0.8, provenanceWithOccurredAt(emailRelProps, "linkSource", "htmlBody")
                    ));
                }
            }
        }

        // Extract email priority/importance if present
        String priority = str(meta.get(GraphConstants.META_EMAIL_PRIORITY));
        if (priority == null) priority = str(meta.get(GraphConstants.META_EMAIL_IMPORTANCE));
        if (priority != null) {
            msgProps.put("priority", priority);
        }

        // DKIM/SPF/DMARC authentication results
        String dkimResult = str(meta.get(GraphConstants.META_EMAIL_DKIM_RESULT));
        if (dkimResult != null) msgProps.put("dkimResult", dkimResult);
        String spfResult = str(meta.get(GraphConstants.META_EMAIL_SPF_RESULT));
        if (spfResult != null) msgProps.put("spfResult", spfResult);
        String dmarcResult = str(meta.get(GraphConstants.META_EMAIL_DMARC_RESULT));
        if (dmarcResult != null) msgProps.put("dmarcResult", dmarcResult);
        String authResults = str(meta.get(GraphConstants.META_EMAIL_AUTH_RESULTS));
        if (authResults != null) {
            msgProps.put("authenticationResults",
                    authResults.length() > 500 ? authResults.substring(0, 500) : authResults);
        }

        // Inline CID images → EMBEDDED_IMAGE entities
        @SuppressWarnings("unchecked")
        List<Map<String, String>> inlineImages = meta.get(GraphConstants.META_EMAIL_INLINE_IMAGES) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(GraphConstants.META_EMAIL_INLINE_IMAGES) : null;
        if (inlineImages != null) {
            for (Map<String, String> img : inlineImages) {
                String cid = img.get("contentId");
                if (cid == null || cid.isBlank()) continue;
                String imgId = entityId("cid:" + cid);
                Map<String, String> imgProps = new LinkedHashMap<>();
                imgProps.put("contentId", cid);
                String imgMime = img.get("mimeType");
                if (imgMime != null) imgProps.put("mimeType", imgMime);
                String imgFileName = img.get("fileName");
                if (imgFileName != null) imgProps.put(GraphConstants.META_FILE_NAME, imgFileName);
                imgProps.put("inline", "true");
                String imgName = imgFileName != null ? imgFileName : cid;
                addEntity(entityIndex, new ExtractedEntity(
                        imgId, imgName, GraphConstants.ENTITY_EMBEDDED_IMAGE,
                        null, "Inline image: " + imgName, 0.85, imgProps));
                relations.add(new ExtractedRelation(
                        msgEntityId, imgId, GraphConstants.REL_HAS_IMAGE,
                        (subject != null ? subject : "Email") + " has inline image: " + imgName,
                        0.85, null));
            }
        }

        // X-Mailer / User-Agent → EMAIL_CLIENT entity + SENT_WITH relation
        if (mailer != null) {
            String clientId = entityId("email_client:" + mailer.toLowerCase());
            addEntity(entityIndex, new ExtractedEntity(clientId, mailer,
                    GraphConstants.ENTITY_EMAIL_CLIENT, null,
                    "Email client: " + mailer, 0.9,
                    Map.of("software", mailer, "headerSource", "X-Mailer")));
            relations.add(new ExtractedRelation(msgEntityId, clientId,
                    GraphConstants.REL_SENT_WITH,
                    "Email sent with " + mailer, 0.9, emailRelProps));
        } else if (userAgent != null) {
            String clientId = entityId("email_client:" + userAgent.toLowerCase());
            addEntity(entityIndex, new ExtractedEntity(clientId, userAgent,
                    GraphConstants.ENTITY_EMAIL_CLIENT, null,
                    "Email client: " + userAgent, 0.9,
                    Map.of("software", userAgent, "headerSource", "User-Agent")));
            relations.add(new ExtractedRelation(msgEntityId, clientId,
                    GraphConstants.REL_SENT_WITH,
                    "Email sent with " + userAgent, 0.9, emailRelProps));
        }

        // List-Unsubscribe → EXTERNAL_RESOURCE entities
        String listUnsubscribe = str(meta.get(GraphConstants.META_EMAIL_LIST_UNSUBSCRIBE));
        if (listUnsubscribe != null) {
            Matcher unsubMatcher = Pattern.compile("<([^>]+)>").matcher(listUnsubscribe);
            while (unsubMatcher.find()) {
                String unsubUri = unsubMatcher.group(1);
                String unsubId = entityId("unsub:" + unsubUri.toLowerCase());
                Map<String, String> unsubProps = new LinkedHashMap<>();
                unsubProps.put("uri", unsubUri);
                unsubProps.put("type", unsubUri.startsWith("mailto:") ? "email" : "url");
                addEntity(entityIndex, new ExtractedEntity(unsubId, "Unsubscribe: " + unsubUri,
                        GraphConstants.ENTITY_EXTERNAL_RESOURCE, null,
                        "List unsubscribe endpoint", 0.8, unsubProps));
                relations.add(new ExtractedRelation(msgEntityId, unsubId,
                        GraphConstants.REL_HYPERLINKS_TO,
                        "Email has unsubscribe link: " + unsubUri,
                        0.8, emailRelProps));
            }
        }

        // IMAP-specific metadata: flagRecent, receivedDate
        Object flagRecent = meta.get("email.flagRecent");
        if (flagRecent != null) msgProps.put("flagRecent", flagRecent.toString());
        String receivedDate = str(meta.get("email.receivedDate"));
        if (receivedDate != null) msgProps.put("receivedDate", receivedDate);

        entities.addAll(entityIndex.values());

        String sourceId = str(meta.get(GraphConstants.META_SOURCE));
        ExtractionMetadata extractionMeta = new ExtractionMetadata(
                sourceId, sourceId, GraphConstants.SOURCE_EMAIL_EXTRACTOR, null, null, null
        );

        return ExtractionResult.of(entities, relations, extractionMeta);
    }

    /**
     * Extracts a graph from a list of email documents. Entities across
     * documents are deduplicated by ID (email addresses produce the same ID).
     */
    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        Map<String, ExtractedEntity> mergedEntities = new LinkedHashMap<>();
        List<ExtractedRelation> mergedRelations = new ArrayList<>();

        for (Document doc : docs) {
            ExtractionResult result = extract(doc);
            for (ExtractedEntity entity : result.entities()) {
                addEntity(mergedEntities, entity);
            }
            mergedRelations.addAll(result.relations());
        }

        return ExtractionResult.of(
                new ArrayList<>(mergedEntities.values()),
                mergedRelations,
                new ExtractionMetadata(null, null, GraphConstants.SOURCE_EMAIL_EXTRACTOR, null, null, null)
        );
    }

    // ── Person parsing ────────────────────────────────────────────────────

    /**
     * Parses a comma-separated address list into person entities.
     * Handles "Name <addr>" and bare "addr" formats.
     */
    List<PersonEntity> parsePersons(String addressField) {
        List<PersonEntity> persons = new ArrayList<>();
        if (addressField == null || addressField.isBlank()) return persons;

        // Split on commas, but not commas inside angle brackets
        String[] parts = addressField.split(",(?=(?:[^<]*<[^>]*>)*[^>]*$)");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String email = null;
            String name = null;

            Matcher emailMatcher = EMAIL_ADDR_PATTERN.matcher(part);
            if (emailMatcher.find()) {
                email = emailMatcher.group(1).toLowerCase();
            }

            Matcher nameMatcher = NAME_ADDR_PATTERN.matcher(part);
            if (nameMatcher.find()) {
                name = nameMatcher.group(1).trim();
                // Remove surrounding quotes
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
            }

            if (email == null) continue;

            String displayName = name != null ? name : email;
            String personId = entityId(email);

            Map<String, String> props = new LinkedHashMap<>();
            props.put("email", email);

            List<String> aliases = new ArrayList<>();
            if (name != null && !name.equalsIgnoreCase(email)) {
                aliases.add(name);
            }

            ExtractedEntity entity = new ExtractedEntity(
                    personId, displayName, GraphConstants.ENTITY_PERSON,
                    aliases.isEmpty() ? null : aliases,
                    null, 1.0, props
            );

            persons.add(new PersonEntity(entity, email, name));
        }

        return persons;
    }

    // ── ICS parsing ───────────────────────────────────────────────────────

    /**
     * Parses basic iCalendar (ICS) fields from raw content and populates
     * the provided properties map. Only scans VEVENT blocks.
     * Fields extracted: summary, dtstart, dtend, location, organizer, attendees.
     */
    static void extractIcsFields(String icsContent, Map<String, String> props) {
        if (icsContent == null || icsContent.isBlank()) return;
        boolean inEvent = false;
        List<String> attendees = new ArrayList<>();
        for (String rawLine : icsContent.split("\r?\n")) {
            // Unfold continued lines (lines starting with space/tab are continuations)
            // Simple approach: treat each line independently
            String line = rawLine.trim();
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                inEvent = true;
                continue;
            }
            if (line.equalsIgnoreCase("END:VEVENT")) {
                inEvent = false;
                continue;
            }
            if (!inEvent) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;
            String key = line.substring(0, colonIdx).toUpperCase();
            String value = line.substring(colonIdx + 1).trim();

            // Strip parameter sections (e.g. DTSTART;TZID=America/New_York)
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
                    // Format: MAILTO:organizer@example.com
                    String org = value.replaceFirst("(?i)^MAILTO:", "");
                    props.put("organizer", org);
                }
                case "ATTENDEE"    -> {
                    String att = value.replaceFirst("(?i)^MAILTO:", "");
                    attendees.add(att);
                }
                default -> { /* ignore other fields */ }
            }
        }
        if (!attendees.isEmpty()) {
            props.put("attendees", String.join(",", attendees));
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * Creates a new map merging the given base relation props with one extra key/value pair.
     * Used for relations that need both the email's {@code occurredAt} and an additional property.
     */
    private static Map<String, String> provenanceWithOccurredAt(Map<String, String> base, String extraKey, String extraValue) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.put(extraKey, extraValue);
        return m;
    }

    private static boolean isPstItemType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase();
        return "contact".equals(lower) || "task".equals(lower) || "calendar_event".equals(lower)
                || lower.contains("pst contact") || lower.contains("pst task") || lower.contains("pst appointment");
    }

    private static String str(Object obj) {
        if (obj == null) return null;
        String s = obj.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Handles both String and List&lt;String&gt; for address fields.
     * IMAP stores email.to/cc/bcc as List, Mime4j stores them as comma-joined String.
     */
    private static String strOrJoinList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            if (list.isEmpty()) return null;
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        String s = obj.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Parses a hostname from a Received header for a given keyword ("from" or "by").
     * Handles patterns like "from mail.example.com (..." and "by mx.google.com with ...".
     */
    static String parseReceivedHost(String receivedHeader, String keyword) {
        if (receivedHeader == null || keyword == null) return null;
        // Match "from <hostname>" or "by <hostname>" — hostname is the next non-whitespace token
        Pattern p = Pattern.compile("\\b" + keyword + "\\s+([a-zA-Z0-9._\\-]+\\.[a-zA-Z]{2,})",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(receivedHeader);
        if (m.find()) {
            String host = m.group(1);
            // Skip obviously wrong matches like "from with" or "by id"
            if (host.length() > 3 && host.contains(".")) {
                return host;
            }
        }
        return null;
    }

    private void addEntity(Map<String, ExtractedEntity> index, ExtractedEntity entity) {
        // Merge: keep the entity with more information
        ExtractedEntity existing = index.get(entity.id());
        if (existing == null) {
            index.put(entity.id(), entity);
        } else {
            // Merge aliases
            Set<String> allAliases = new LinkedHashSet<>();
            if (existing.aliases() != null) allAliases.addAll(existing.aliases());
            if (entity.aliases() != null) allAliases.addAll(entity.aliases());

            // Keep longer description
            String desc = existing.description();
            if (desc == null || (entity.description() != null && entity.description().length() > desc.length())) {
                desc = entity.description();
            }

            // Merge properties
            Map<String, String> mergedProps = new LinkedHashMap<>();
            if (existing.properties() != null) mergedProps.putAll(existing.properties());
            if (entity.properties() != null) mergedProps.putAll(entity.properties());

            // Keep higher confidence
            double conf = Math.max(
                    existing.confidence() != null ? existing.confidence() : 0.0,
                    entity.confidence() != null ? entity.confidence() : 0.0
            );

            // Prefer a real name over just an email
            String name = existing.name();
            if (entity.name() != null && !entity.name().contains("@") && name.contains("@")) {
                name = entity.name();
            }

            index.put(entity.id(), new ExtractedEntity(
                    entity.id(), name, existing.type(), // keep existing type
                    allAliases.isEmpty() ? null : new ArrayList<>(allAliases),
                    desc, conf, mergedProps
            ));
        }
    }

    record PersonEntity(ExtractedEntity entity, String email, String displayName) {}
}
