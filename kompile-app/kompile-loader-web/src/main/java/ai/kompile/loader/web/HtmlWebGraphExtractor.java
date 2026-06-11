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

package ai.kompile.loader.web;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.kompile.core.graphrag.ExtractorUtils.*;
import static ai.kompile.core.graphrag.GraphConstants.*;

/**
 * Deterministic graph extractor for HTML/Web documents.
 * Extracts author, title, keywords, OpenGraph metadata, and site references
 * into knowledge graph entities and relationships.
 *
 * <p>Relies on metadata set by WebHtmlLoaderImpl (title, author, keywords, og:*, etc.)
 * and TikaLoaderImpl (author, title, keywords for file-based HTML).</p>
 *
 * <p>Entity types: WEB_PAGE, PERSON, TOPIC, WEBSITE, ATTACHMENT</p>
 * <p>Relationship types: AUTHORED_BY, HAS_TOPIC, HOSTED_ON, CANONICAL_OF,
 * SENT_TO, CC_TO, HAS_ATTACHMENT (for HTML-rendered emails)</p>
 */
@Component
public class HtmlWebGraphExtractor implements DocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(HtmlWebGraphExtractor.class);

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);

    /** Map of social-platform hostname fragments to platform names for promoting links to SOCIAL_ACCOUNT. */
    private static final Map<String, String> SOCIAL_PLATFORM_HOSTS = Map.ofEntries(
            Map.entry("twitter.com", "twitter"),
            Map.entry("x.com", "twitter"),
            Map.entry("facebook.com", "facebook"),
            Map.entry("fb.com", "facebook"),
            Map.entry("linkedin.com", "linkedin"),
            Map.entry("instagram.com", "instagram"),
            Map.entry("youtube.com", "youtube"),
            Map.entry("youtu.be", "youtube"),
            Map.entry("github.com", "github"),
            Map.entry("mastodon.social", "mastodon"),
            Map.entry("tiktok.com", "tiktok"),
            Map.entry("reddit.com", "reddit"),
            Map.entry("pinterest.com", "pinterest"),
            Map.entry("tumblr.com", "tumblr")
    );

    /**
     * Checks if a URL belongs to a known social platform.
     * Returns the platform name or null if not a social URL.
     */
    private static String detectSocialPlatform(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase();
        for (Map.Entry<String, String> entry : SOCIAL_PLATFORM_HOSTS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("html", "web", "webpage");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();

        // Don't claim PDFs
        String docType = str(meta.get(META_DOCUMENT_TYPE));
        if (docType != null && docType.toLowerCase().contains("pdf")) return false;

        // Skip chart/image sub-documents — their graph data is not handled here.
        // Table sub-documents ARE handled: canExtract returns true so extract() can
        // build a WEB_PAGE → TABLE relationship.
        String contentType = str(meta.get(META_CONTENT_TYPE));
        if ("chart".equals(contentType) || "image".equals(contentType)) return false;

        // Match HTML loader
        String loader = str(meta.get(META_LOADER));
        if (loader != null && loader.toLowerCase().contains("web/html")) return true;

        // Match on documentType
        if (docType != null) {
            String lowerDocType = docType.toLowerCase();
            if (lowerDocType.contains("html") || "web_page".equals(lowerDocType)) return true;
        }

        // Match on file extension
        String fileName = str(meta.get(META_FILE_NAME));
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return true;
        }

        // Match on tika content type
        String tikaContentType = str(meta.get(META_TIKA_CONTENT_TYPE));
        if (tikaContentType != null && tikaContentType.contains("html")) return true;

        return false;
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        // ── TABLE sub-documents ─────────────────────────────────────────
        String contentType = str(meta.get(META_CONTENT_TYPE));
        if ("table".equals(contentType)) {
            return extractTableSubDocument(meta);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();

        // ── WEB_PAGE entity ─────────────────────────────────────────────
        String fileName = str(meta.get(META_FILE_NAME));
        String title = str(meta.get(META_TITLE));
        if (title == null) title = str(meta.get(META_OG_TITLE));
        String source = str(meta.get(META_SOURCE));
        String baseUri = str(meta.get(META_BASE_URI));
        String displayTitle = title != null ? title : (fileName != null ? fileName : "Untitled Page");

        String docEntityId = entityId("webpage:" + (source != null ? source : displayTitle));
        Map<String, String> docProps = new LinkedHashMap<>();
        if (fileName != null) docProps.put(META_FILE_NAME, fileName);
        if (title != null) docProps.put(META_TITLE, title);
        if (source != null) docProps.put(META_SOURCE, source);
        if (baseUri != null) docProps.put(META_BASE_URI, baseUri);
        putIfPresent(docProps, META_LANGUAGE, meta, META_LANGUAGE);
        putIfPresent(docProps, META_DESCRIPTION, meta, META_DESCRIPTION);
        putIfPresent(docProps, META_OG_DESCRIPTION, meta, META_OG_DESCRIPTION);
        putIfPresent(docProps, META_PAGE_TYPE, meta, META_PAGE_TYPE);
        putIfPresent(docProps, META_PUBLISHED_TIME, meta, META_PUBLISHED_TIME);
        putIfPresent(docProps, META_MODIFIED_TIME, meta, META_MODIFIED_TIME);
        putValueIfPresent(docProps, META_LINK_COUNT, meta, META_LINK_COUNT);
        putValueIfPresent(docProps, META_IMAGE_COUNT, meta, META_IMAGE_COUNT);
        putIfPresent(docProps, META_TWITTER_TITLE, meta, META_TWITTER_TITLE);
        putIfPresent(docProps, META_TWITTER_DESCRIPTION, meta, META_TWITTER_DESCRIPTION);
        putIfPresent(docProps, META_TWITTER_CARD, meta, META_TWITTER_CARD);
        putIfPresent(docProps, META_TWITTER_IMAGE, meta, META_TWITTER_IMAGE);
        putIfPresent(docProps, META_TWITTER_SITE, meta, META_TWITTER_SITE);
        putIfPresent(docProps, META_OG_URL, meta, META_OG_URL);
        putIfPresent(docProps, META_OG_IMAGE, meta, META_OG_IMAGE);
        putIfPresent(docProps, META_SOURCE_TYPE, meta, META_SOURCE_TYPE);

        ExtractedEntity pageEntity = new ExtractedEntity(
                docEntityId, displayTitle, ENTITY_WEB_PAGE,
                null, "Web page: " + displayTitle, 1.0, docProps
        );
        addEntity(entityIndex, pageEntity);

        // ── PERSON from author / articleAuthor ──────────────────────────
        String author = str(meta.get(META_AUTHOR));
        if (author == null) author = str(meta.get(META_ARTICLE_AUTHOR));
        if (author != null) {
            extractAuthors(author, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── TOPICs from keywords ────────────────────────────────────────
        String keywords = str(meta.get(META_KEYWORDS));
        if (keywords != null) {
            extractTopics(keywords, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── Dublin Core: creator as PERSON (fallback if no OG/meta author) ─
        String dcCreator = str(meta.get(META_DC_CREATOR));
        if (dcCreator != null && author == null) {
            // Only extract if we didn't already get an author from meta/OG
            extractAuthors(dcCreator, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── Dublin Core: publisher as ORGANIZATION ──────────────────────
        String dcPublisher = str(meta.get(META_DC_PUBLISHER));
        if (dcPublisher != null) {
            String pubId = entityId("org:" + dcPublisher.toLowerCase());
            ExtractedEntity pubEntity = new ExtractedEntity(
                    pubId, dcPublisher, ENTITY_ORGANIZATION,
                    null, "Publisher: " + dcPublisher, 0.85,
                    Map.of("name", dcPublisher, PROP_SOURCE_FIELD, META_DC_PUBLISHER)
            );
            addEntity(entityIndex, pubEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, pubId, REL_PRODUCED_BY,
                    displayTitle + " published by " + dcPublisher,
                    0.85, null
            ));
        }

        // ── Dublin Core: date as DATE entity ─────────────────────────────
        String dcDate = str(meta.get(META_DC_DATE));
        if (dcDate != null) {
            String dcDateId = entityId("date:" + dcDate);
            if (!entityIndex.containsKey(dcDateId)) {
                addEntity(entityIndex, new ExtractedEntity(
                        dcDateId, dcDate, ENTITY_DATE,
                        null, "Dublin Core date: " + dcDate, 0.85,
                        Map.of("date", dcDate, "dateType", "dc.date")
                ));
            }
            relations.add(new ExtractedRelation(
                    docEntityId, dcDateId, REL_PUBLISHED_ON,
                    displayTitle + " dated " + dcDate, 0.85, null
            ));
        }

        // ── Dublin Core: description and rights as page entity properties ─
        putIfPresent(docProps, META_DC_DESCRIPTION, meta, META_DC_DESCRIPTION);
        putIfPresent(docProps, META_DC_RIGHTS, meta, META_DC_RIGHTS);

        // ── article:section as TOPIC entity ─────────────────────────────
        String articleSection = str(meta.get(META_ARTICLE_SECTION));
        if (articleSection != null) {
            String sectionTopicId = entityId("topic:" + articleSection.toLowerCase());
            addEntity(entityIndex, new ExtractedEntity(
                    sectionTopicId, articleSection, ENTITY_TOPIC,
                    null, "Article section: " + articleSection, 0.9,
                    Map.of("topic", articleSection, PROP_SOURCE_FIELD, META_ARTICLE_SECTION)
            ));
            relations.add(new ExtractedRelation(
                    docEntityId, sectionTopicId, REL_HAS_TOPIC,
                    displayTitle + " in section: " + articleSection, 0.9, null
            ));
        }

        // ── article:tag as TOPIC entities ───────────────────────────────
        Object articleTagObj = meta.get(META_ARTICLE_TAG);
        if (articleTagObj instanceof List<?> tagList) {
            for (Object tagObj : tagList) {
                String tag = str(tagObj);
                if (tag == null) continue;
                String tagTopicId = entityId("topic:" + tag.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(
                        tagTopicId, tag, ENTITY_TOPIC,
                        null, "Article tag: " + tag, 0.85,
                        Map.of("topic", tag, PROP_SOURCE_FIELD, META_ARTICLE_TAG)
                ));
                relations.add(new ExtractedRelation(
                        docEntityId, tagTopicId, REL_HAS_TOPIC,
                        displayTitle + " tagged: " + tag, 0.85, null
                ));
            }
        } else if (articleTagObj instanceof String tagStr) {
            // Single tag stored as string
            String tagTopicId = entityId("topic:" + tagStr.toLowerCase());
            addEntity(entityIndex, new ExtractedEntity(
                    tagTopicId, tagStr, ENTITY_TOPIC,
                    null, "Article tag: " + tagStr, 0.85,
                    Map.of("topic", tagStr, PROP_SOURCE_FIELD, META_ARTICLE_TAG)
            ));
            relations.add(new ExtractedRelation(
                    docEntityId, tagTopicId, REL_HAS_TOPIC,
                    displayTitle + " tagged: " + tagStr, 0.85, null
            ));
        }

        // ── WEBSITE from siteName ───────────────────────────────────────
        String siteName = str(meta.get(META_SITE_NAME));
        if (siteName != null) {
            String siteId = entityId("website:" + siteName.toLowerCase());
            ExtractedEntity siteEntity = new ExtractedEntity(
                    siteId, siteName, ENTITY_WEBSITE,
                    null, "Website: " + siteName, 0.85,
                    Map.of(META_SITE_NAME, siteName)
            );
            addEntity(entityIndex, siteEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, siteId, REL_HOSTED_ON,
                    displayTitle + " hosted on " + siteName,
                    0.85, null
            ));
        }

        // ── SOCIAL_ACCOUNT from twitterSite ────────────────────────────
        String twitterSite = str(meta.get(META_TWITTER_SITE));
        if (twitterSite != null) {
            String socialId = entityId("social:twitter:" + twitterSite.toLowerCase());
            ExtractedEntity socialEntity = new ExtractedEntity(
                    socialId, twitterSite, ENTITY_SOCIAL_ACCOUNT,
                    null, "Twitter/X account: " + twitterSite, 0.85,
                    Map.of("platform", "twitter", "handle", twitterSite)
            );
            addEntity(entityIndex, socialEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, socialId, REL_HAS_SOCIAL_ACCOUNT,
                    displayTitle + " associated with Twitter account " + twitterSite,
                    0.85, null
            ));
        }

        // ── PUBLISHED_ON from publishedTime ─────────────────────────────
        String publishedTime = str(meta.get(META_PUBLISHED_TIME));
        if (publishedTime != null) {
            String publishedId = entityId("date:" + publishedTime);
            ExtractedEntity publishedEntity = new ExtractedEntity(
                    publishedId, publishedTime, ENTITY_DATE,
                    null, "Publication date: " + publishedTime, 0.9,
                    Map.of("date", publishedTime, "dateType", "published")
            );
            addEntity(entityIndex, publishedEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, publishedId, REL_PUBLISHED_ON,
                    displayTitle + " published on " + publishedTime,
                    0.9, null
            ));
        }

        // ── MODIFIED_ON from modifiedTime ──────────────────────────────
        String modifiedTime = str(meta.get(META_MODIFIED_TIME));
        if (modifiedTime != null) {
            String modifiedId = entityId("date:" + modifiedTime);
            // Reuse existing DATE entity if publishedTime == modifiedTime
            if (!entityIndex.containsKey(modifiedId)) {
                ExtractedEntity modifiedEntity = new ExtractedEntity(
                        modifiedId, modifiedTime, ENTITY_DATE,
                        null, "Modification date: " + modifiedTime, 0.85,
                        Map.of("date", modifiedTime, "dateType", "modified")
                );
                addEntity(entityIndex, modifiedEntity);
            }
            relations.add(new ExtractedRelation(
                    docEntityId, modifiedId, REL_MODIFIED_ON,
                    displayTitle + " modified on " + modifiedTime,
                    0.85, null
            ));
        }

        // ── Canonical URL linkage ───────────────────────────────────────
        String canonicalUrl = str(meta.get(META_CANONICAL_URL));
        if (canonicalUrl != null && source != null && !canonicalUrl.equals(source)) {
            String canonicalId = entityId("webpage:" + canonicalUrl);
            ExtractedEntity canonEntity = new ExtractedEntity(
                    canonicalId, canonicalUrl, ENTITY_WEB_PAGE,
                    null, "Canonical page", 0.9,
                    Map.of("url", canonicalUrl)
            );
            addEntity(entityIndex, canonEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, canonicalId, REL_CANONICAL_OF,
                    displayTitle + " is canonical at " + canonicalUrl,
                    0.9, null
            ));
        }

        // ── Alternate links (language variants, RSS/Atom feeds) ────────
        Object alternateLinksObj = meta.get("html.alternateLinks");
        if (alternateLinksObj instanceof List<?> altList) {
            int altLimit = Math.min(altList.size(), 50);
            for (int i = 0; i < altLimit; i++) {
                Object altObj = altList.get(i);
                if (!(altObj instanceof Map<?, ?> altMap)) continue;
                String altHref = str(altMap.get("href"));
                if (altHref == null) continue;
                String altHreflang = str(altMap.get("hreflang"));
                String altType = str(altMap.get("type"));
                String altTitle = str(altMap.get("title"));

                // Determine entity type based on content
                boolean isFeed = altType != null && (altType.contains("rss") || altType.contains("atom") || altType.contains("xml"));
                String entityType = isFeed ? ENTITY_EXTERNAL_LINK : ENTITY_WEB_PAGE;
                String relType = isFeed ? REL_HAS_FEED : REL_ALTERNATE_OF;
                String label = altTitle != null ? altTitle : (altHreflang != null ? "Alternate (" + altHreflang + ")" : altHref);

                String altEntityId = entityId("alt_link:" + altHref);
                Map<String, String> altProps = new LinkedHashMap<>();
                altProps.put("url", altHref);
                if (altHreflang != null) altProps.put("hreflang", altHreflang);
                if (altType != null) altProps.put("type", altType);
                if (altTitle != null) altProps.put("title", altTitle);

                addEntity(entityIndex, new ExtractedEntity(
                        altEntityId, label, entityType,
                        null, (isFeed ? "Feed: " : "Alternate: ") + label, 0.8, altProps));
                relations.add(new ExtractedRelation(
                        docEntityId, altEntityId, relType,
                        displayTitle + (isFeed ? " has feed: " : " alternate: ") + label,
                        0.8, null));
            }
        }

        // ── Email entities (from content_type_hint=email + email.* metadata) ──
        String contentTypeHint = str(meta.get(META_CONTENT_TYPE_HINT));
        if ("email".equals(contentTypeHint)) {
            String emailFrom = str(meta.get(META_EMAIL_FROM));
            String emailFromName = str(meta.get(META_EMAIL_FROM_NAME));
            String emailFromAddr = str(meta.get(META_EMAIL_FROM_ADDRESS));
            String emailSubject = str(meta.get(META_EMAIL_SUBJECT));
            String emailDate = str(meta.get(META_EMAIL_DATE));
            String emailTo = str(meta.get(META_EMAIL_TO));
            String emailCc = str(meta.get(META_EMAIL_CC));

            // Record email properties on the page entity
            String emailMessageId = str(meta.get(META_EMAIL_MESSAGE_ID));
            if (emailSubject != null) docProps.put("emailSubject", emailSubject);
            if (emailDate != null) docProps.put("emailDate", emailDate);
            if (emailMessageId != null) docProps.put("messageId", emailMessageId);
            docProps.put("isEmail", "true");

            // DATE entity from email date
            if (emailDate != null) {
                String emailDateId = entityId("date:" + emailDate);
                if (!entityIndex.containsKey(emailDateId)) {
                    addEntity(entityIndex, new ExtractedEntity(
                            emailDateId, emailDate, ENTITY_DATE,
                            null, "Email date: " + emailDate, 0.9,
                            Map.of("date", emailDate, "dateType", "sent")
                    ));
                }
                relations.add(new ExtractedRelation(
                        docEntityId, emailDateId, REL_PUBLISHED_ON,
                        displayTitle + " sent on " + emailDate,
                        0.9, null
                ));
            }

            // Sender → PERSON + SENT_BY
            if (emailFrom != null || emailFromAddr != null) {
                String senderKey = emailFromAddr != null ? emailFromAddr.toLowerCase() : emailFrom.toLowerCase();
                String senderName = emailFromName != null ? emailFromName : (emailFromAddr != null ? emailFromAddr : emailFrom);
                String senderId = entityId("person:" + senderKey);
                Map<String, String> senderProps = new LinkedHashMap<>();
                if (emailFromAddr != null) senderProps.put("email", emailFromAddr);
                senderProps.put(PROP_SOURCE_FIELD, META_EMAIL_FROM);
                ExtractedEntity senderEntity = new ExtractedEntity(
                        senderId, senderName, ENTITY_PERSON,
                        null, "Email sender: " + senderName, 0.95, senderProps
                );
                addEntity(entityIndex, senderEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, senderId, REL_SENT_BY,
                        displayTitle + " sent by " + senderName,
                        0.95, null
                ));
            }

            // Recipients → PERSON + SENT_TO
            if (emailTo != null) {
                for (String recipient : emailTo.split(",")) {
                    recipient = recipient.trim();
                    if (recipient.isEmpty()) continue;
                    String recipientId = entityId("person:" + recipient.toLowerCase());
                    ExtractedEntity recipientEntity = new ExtractedEntity(
                            recipientId, recipient, ENTITY_PERSON,
                            null, "Email recipient: " + recipient, 0.9,
                            Map.of(PROP_SOURCE_FIELD, META_EMAIL_TO)
                    );
                    addEntity(entityIndex, recipientEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, recipientId, REL_SENT_TO,
                            displayTitle + " sent to " + recipient,
                            0.9, null
                    ));
                }
            }

            // CC recipients → PERSON + CC_TO
            if (emailCc != null) {
                for (String cc : emailCc.split(",")) {
                    cc = cc.trim();
                    if (cc.isEmpty()) continue;
                    String ccId = entityId("person:" + cc.toLowerCase());
                    ExtractedEntity ccEntity = new ExtractedEntity(
                            ccId, cc, ENTITY_PERSON,
                            null, "Email CC recipient: " + cc, 0.85,
                            Map.of(PROP_SOURCE_FIELD, META_EMAIL_CC)
                    );
                    addEntity(entityIndex, ccEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, ccId, REL_CC_TO,
                            displayTitle + " CC'd to " + cc,
                            0.85, null
                    ));
                }
            }

            // BCC recipients → PERSON + BCC_TO
            String emailBcc = str(meta.get(META_EMAIL_BCC));
            if (emailBcc != null) {
                for (String bcc : emailBcc.split(",")) {
                    bcc = bcc.trim();
                    if (bcc.isEmpty()) continue;
                    String bccId = entityId("person:" + bcc.toLowerCase());
                    ExtractedEntity bccEntity = new ExtractedEntity(
                            bccId, bcc, ENTITY_PERSON,
                            null, "Email BCC recipient: " + bcc, 0.8,
                            Map.of(PROP_SOURCE_FIELD, META_EMAIL_BCC)
                    );
                    addEntity(entityIndex, bccEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, bccId, REL_BCC_TO,
                            displayTitle + " BCC'd to " + bcc,
                            0.8, null
                    ));
                }
            }

            // Attachments
            Object attachObj = meta.get(META_EMAIL_ATTACHMENT_NAMES);
            if (attachObj instanceof List<?> attachList) {
                for (Object attachItem : attachList) {
                    String attachName = str(attachItem);
                    if (attachName == null) continue;
                    String attachId = entityId("attach:" + docEntityId + ":" + attachName);
                    ExtractedEntity attachEntity = new ExtractedEntity(
                            attachId, attachName, ENTITY_EMAIL_ATTACHMENT,
                            null, "Email attachment: " + attachName, 0.9,
                            Map.of("filename", attachName)
                    );
                    addEntity(entityIndex, attachEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, attachId, REL_HAS_ATTACHMENT,
                            displayTitle + " has attachment: " + attachName,
                            0.9, null
                    ));
                }
            }

            // In-Reply-To → REPLIED_TO relation to parent message
            String inReplyTo = str(meta.get(META_EMAIL_IN_REPLY_TO));
            if (inReplyTo != null && !inReplyTo.isBlank()) {
                String parentMsgId = entityId(inReplyTo.trim());
                ExtractedEntity parentMsg = new ExtractedEntity(
                        parentMsgId, inReplyTo.trim(), ENTITY_EMAIL_MESSAGE,
                        null, "Parent email message: " + inReplyTo.trim(), 0.8,
                        Map.of("messageId", inReplyTo.trim())
                );
                addEntity(entityIndex, parentMsg);
                relations.add(new ExtractedRelation(
                        docEntityId, parentMsgId, REL_REPLIED_TO,
                        displayTitle + " is reply to " + inReplyTo.trim(),
                        0.9, null
                ));
            }

            // References chain → stub entities + REFERENCES relations for thread reconstruction
            Object referencesObj = meta.get(META_EMAIL_REFERENCES);
            List<String> references = null;
            if (referencesObj instanceof List<?> refList) {
                references = new ArrayList<>();
                for (Object r : refList) {
                    if (r instanceof String s && !s.isBlank()) references.add(s.trim());
                }
            } else if (referencesObj instanceof String refStr && !refStr.isBlank()) {
                references = new ArrayList<>();
                for (String ref : refStr.trim().split("\\s+")) {
                    if (!ref.isBlank()) references.add(ref.trim());
                }
            }
            if (references != null) {
                for (String ref : references) {
                    if (ref.equals(inReplyTo)) continue; // already handled above
                    String refEntityId = entityId(ref);
                    addEntity(entityIndex, new ExtractedEntity(
                            refEntityId, ref, ENTITY_EMAIL_MESSAGE,
                            null, "Referenced email message: " + ref, 0.7,
                            Map.of("messageId", ref)));
                    relations.add(new ExtractedRelation(
                            docEntityId, refEntityId, REL_REFERENCES,
                            displayTitle + " references " + ref, 0.8, null));
                }
            }

            // EMAIL_THREAD entity — group emails by subject for thread traversal
            if (emailSubject != null && !emailSubject.isBlank()) {
                // Normalize subject: strip Re:/Fwd:/Fw: prefixes for consistent threading
                String normalizedSubject = emailSubject.replaceAll("(?i)^\\s*(re|fwd|fw)\\s*:\\s*", "").trim();
                if (!normalizedSubject.isEmpty()) {
                    String threadId = entityId("thread:" + normalizedSubject.toLowerCase());
                    Map<String, String> threadProps = new LinkedHashMap<>();
                    threadProps.put("threadSubject", normalizedSubject);
                    if (emailMessageId != null) threadProps.put("latestMessageId", emailMessageId);
                    addEntity(entityIndex, new ExtractedEntity(
                            threadId, normalizedSubject, ENTITY_EMAIL_THREAD,
                            null, "Email thread: " + normalizedSubject, 0.85, threadProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, threadId, REL_IN_THREAD,
                            displayTitle + " is part of thread: " + normalizedSubject, 0.85, null));
                }
            }
        }

        // ── Hyperlinks (from html.hyperlinks metadata) ─────────────────
        Object hyperlinksObj = meta.get("html.hyperlinks");
        if (hyperlinksObj instanceof List<?> linkList) {
            int linkLimit = Math.min(linkList.size(), 100); // cap to avoid graph explosion
            for (int i = 0; i < linkLimit; i++) {
                Object linkObj = linkList.get(i);
                String url;
                String anchorText = null;
                String linkTitle = null;
                String linkRel = null;
                if (linkObj instanceof Map<?, ?> linkMap) {
                    url = str(linkMap.get("url"));
                    anchorText = str(linkMap.get("text"));
                    linkTitle = str(linkMap.get("title"));
                    linkRel = str(linkMap.get("rel"));
                } else {
                    url = str(linkObj);
                }
                if (url == null) continue;
                String linkEntityId = entityId("link:" + url);
                String linkDisplayName = anchorText != null ? anchorText : url;
                Map<String, String> linkProps = new LinkedHashMap<>();
                linkProps.put("url", url);
                if (anchorText != null) linkProps.put("anchorText", anchorText);
                if (linkTitle != null) linkProps.put("title", linkTitle);
                if (linkRel != null) linkProps.put("rel", linkRel);

                // Detect social platform links and promote to SOCIAL_ACCOUNT
                String socialPlatform = detectSocialPlatform(url);
                if (socialPlatform != null) {
                    linkProps.put("platform", socialPlatform);
                    String socialId = entityId("social:" + socialPlatform + ":" + url.toLowerCase());
                    ExtractedEntity socialEntity = new ExtractedEntity(
                            socialId, linkDisplayName, ENTITY_SOCIAL_ACCOUNT,
                            null, socialPlatform + " profile: " + linkDisplayName, 0.85,
                            linkProps
                    );
                    addEntity(entityIndex, socialEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, socialId, REL_HAS_SOCIAL_ACCOUNT,
                            displayTitle + " has " + socialPlatform + " account: " + linkDisplayName,
                            0.85, null
                    ));
                } else {
                    ExtractedEntity linkEntity = new ExtractedEntity(
                            linkEntityId, linkDisplayName, ENTITY_EXTERNAL_LINK,
                            null, "Hyperlink: " + linkDisplayName, 0.8,
                            linkProps
                    );
                    addEntity(entityIndex, linkEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, linkEntityId, REL_HYPERLINKS_TO,
                            displayTitle + " links to " + linkDisplayName,
                            0.8, null
                    ));
                }
            }
        }

        // ── Mailto emails (from html.mailtoEmails metadata) ────────────
        Object mailtoObj = meta.get("html.mailtoEmails");
        if (mailtoObj instanceof List<?> mailtoList) {
            int mailtoLimit = Math.min(mailtoList.size(), 50);
            for (int i = 0; i < mailtoLimit; i++) {
                Object entry = mailtoList.get(i);
                String email = null;
                String displayText = null;
                if (entry instanceof Map<?, ?> m) {
                    email = m.get("email") instanceof String s ? s : null;
                    displayText = m.get("text") instanceof String s ? s : null;
                } else if (entry instanceof String s) {
                    email = s;
                }
                if (email == null || email.isBlank()) continue;
                String personKey = email.toLowerCase().trim();
                String personId = entityId("person:" + personKey);
                Map<String, String> personProps = new LinkedHashMap<>();
                personProps.put("email", email);
                if (displayText != null) personProps.put("name", displayText);
                addEntity(entityIndex, new ExtractedEntity(
                        personId,
                        displayText != null ? displayText : email,
                        GraphConstants.ENTITY_PERSON,
                        null, "Email contact: " + email, 0.8, personProps));
                relations.add(new ExtractedRelation(
                        docEntityId, personId, REL_MENTIONS,
                        displayTitle + " mentions email " + email, 0.8, null));
            }
        }

        // ── Images (from html.images metadata) ─────────────────────────
        Object imagesObj = meta.get("html.images");
        if (imagesObj instanceof List<?> imgList) {
            int imgLimit = Math.min(imgList.size(), 50); // cap to avoid graph explosion
            for (int i = 0; i < imgLimit; i++) {
                Object imgObj = imgList.get(i);
                if (!(imgObj instanceof Map<?, ?> imgMap)) continue;
                String src = str(imgMap.get("src"));
                if (src == null) continue;
                String alt = str(imgMap.get("alt"));
                String imgName = alt != null ? alt : src;

                String imgEntityId = entityId("img:" + src);
                Map<String, String> imgProps = new LinkedHashMap<>();
                imgProps.put("src", src);
                if (alt != null) imgProps.put("alt", alt);

                ExtractedEntity imgEntity = new ExtractedEntity(
                        imgEntityId, imgName, ENTITY_IMAGE,
                        null, "Image: " + imgName, 0.7, imgProps
                );
                addEntity(entityIndex, imgEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, imgEntityId, REL_HAS_IMAGE,
                        displayTitle + " contains image: " + imgName,
                        0.7, null
                ));

                // Create TOPIC entity from meaningful alt text (3+ words, not a filename)
                if (alt != null && alt.split("\\s+").length >= 3
                        && !alt.matches(".*\\.(png|jpg|jpeg|gif|svg|webp|bmp|ico)$")) {
                    String topicId = entityId("topic:" + alt.toLowerCase());
                    if (!entityIndex.containsKey(topicId)) {
                        addEntity(entityIndex, new ExtractedEntity(
                                topicId, alt, ENTITY_TOPIC,
                                null, "Topic from image alt text", 0.65,
                                Map.of(PROP_SOURCE_FIELD, "image_alt")));
                        relations.add(new ExtractedRelation(
                                docEntityId, topicId, REL_HAS_TOPIC,
                                displayTitle + " has topic: " + alt, 0.65, null));
                    }
                    // Also link image to its topic
                    relations.add(new ExtractedRelation(
                            imgEntityId, topicId, REL_DESCRIBES,
                            imgName + " describes: " + alt, 0.65, null));
                }
            }
        }

        // ── DOCUMENT_SECTION from html.headings (with SUBSECTION_OF hierarchy) ──
        Object headingsObj = meta.get("html.headings");
        if (headingsObj instanceof List<?> headingList) {
            // Track ancestor section IDs by depth for SUBSECTION_OF hierarchy
            Map<Integer, String> depthStack = new LinkedHashMap<>();

            for (Object headingObj : headingList) {
                if (!(headingObj instanceof Map<?, ?> headingMap)) continue;
                String headingText = str(headingMap.get("text"));
                String headingLevel = str(headingMap.get("level"));
                if (headingText == null || headingText.isBlank()) continue;
                if (headingLevel == null) headingLevel = "1";

                int depth;
                try { depth = Integer.parseInt(headingLevel); } catch (NumberFormatException e) { depth = 1; }

                String sectionId = entityId("section:" + (source != null ? source : displayTitle) + ":" + headingText.toLowerCase());
                Map<String, String> sectionProps = new LinkedHashMap<>();
                sectionProps.put("headingLevel", headingLevel);
                sectionProps.put("headingText", headingText);

                ExtractedEntity sectionEntity = new ExtractedEntity(
                        sectionId, headingText, ENTITY_DOCUMENT_SECTION,
                        null, "Section (H" + headingLevel + "): " + headingText, 0.9, sectionProps
                );
                addEntity(entityIndex, sectionEntity);

                // H1 sections → HAS_SECTION to document; deeper → SUBSECTION_OF nearest ancestor
                if (depth <= 1) {
                    relations.add(new ExtractedRelation(
                            docEntityId, sectionId, REL_HAS_SECTION,
                            displayTitle + " has section: " + headingText,
                            0.9, null
                    ));
                } else {
                    // Find nearest ancestor (closest depth < current)
                    String parentSectionId = null;
                    for (int d = depth - 1; d >= 1; d--) {
                        if (depthStack.containsKey(d)) {
                            parentSectionId = depthStack.get(d);
                            break;
                        }
                    }
                    if (parentSectionId != null) {
                        relations.add(new ExtractedRelation(
                                sectionId, parentSectionId, REL_SUBSECTION_OF,
                                headingText + " is subsection",
                                0.85, null
                        ));
                    } else {
                        // No ancestor found — attach directly to document
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, REL_HAS_SECTION,
                                displayTitle + " has section: " + headingText,
                                0.9, null
                        ));
                    }
                }

                // Update depth stack — clear all deeper entries
                final int currentDepth = depth;
                depthStack.entrySet().removeIf(e -> e.getKey() >= currentDepth);
                depthStack.put(depth, sectionId);
            }
        }

        // ── STRUCTURED_DATA from html.jsonld ─────────────────────────────
        Object jsonLdObj = meta.get("html.jsonld");
        if (jsonLdObj instanceof List<?> jsonLdList) {
            int sdIndex = 0;
            for (Object sdObj : jsonLdList) {
                String jsonRaw = str(sdObj);
                if (jsonRaw == null || jsonRaw.isBlank()) {
                    sdIndex++;
                    continue;
                }
                // Best-effort extraction of @type and name from the raw JSON
                // without pulling in a full JSON parser dependency here.
                String schemaType = extractJsonField(jsonRaw, "@type");
                String schemaName = extractJsonField(jsonRaw, "name");
                if (schemaName == null) schemaName = extractJsonField(jsonRaw, "headline");

                String sdLabel = schemaType != null ? schemaType : ("StructuredData[" + sdIndex + "]");
                if (schemaName != null) sdLabel = schemaName + " (" + sdLabel + ")";

                String sdId = entityId("structureddata:" + (source != null ? source : displayTitle) + ":" + sdIndex);
                Map<String, String> sdProps = new LinkedHashMap<>();
                sdProps.put("rawJson", jsonRaw.length() > 1000 ? jsonRaw.substring(0, 1000) + "..." : jsonRaw);
                if (schemaType != null) sdProps.put("schemaType", schemaType);
                if (schemaName != null) sdProps.put("schemaName", schemaName);
                sdProps.put("sdIndex", String.valueOf(sdIndex));

                ExtractedEntity sdEntity = new ExtractedEntity(
                        sdId, sdLabel, ENTITY_STRUCTURED_DATA,
                        null, "JSON-LD structured data: " + sdLabel, 0.9, sdProps
                );
                addEntity(entityIndex, sdEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, sdId, REL_HAS_STRUCTURED_DATA,
                        displayTitle + " has structured data: " + sdLabel,
                        0.9, null
                ));
                sdIndex++;
            }
        }

        // ── EMBEDDED_MEDIA from html.embeddedMedia ────────────────────────
        Object embeddedMediaObj = meta.get("html.embeddedMedia");
        if (embeddedMediaObj instanceof List<?> mediaList) {
            int mediaIndex = 0;
            for (Object mediaObj : mediaList) {
                if (!(mediaObj instanceof Map<?, ?> mediaMap)) {
                    mediaIndex++;
                    continue;
                }
                String mediaType = str(mediaMap.get("type"));
                String mediaSrc = str(mediaMap.get("src"));
                String mediaTitle = str(mediaMap.get("title"));

                if (mediaType == null) {
                    mediaIndex++;
                    continue;
                }

                String mediaLabel = mediaTitle != null ? mediaTitle
                        : (mediaSrc != null ? mediaSrc : (mediaType + "[" + mediaIndex + "]"));

                String mediaId = entityId("media:" + (source != null ? source : displayTitle)
                        + ":" + mediaType + ":" + mediaIndex);
                Map<String, String> mediaProps = new LinkedHashMap<>();
                mediaProps.put("mediaType", mediaType);
                if (mediaSrc != null && !mediaSrc.isEmpty()) mediaProps.put("src", mediaSrc);
                if (mediaTitle != null) mediaProps.put("title", mediaTitle);
                mediaProps.put("mediaIndex", String.valueOf(mediaIndex));

                ExtractedEntity mediaEntity = new ExtractedEntity(
                        mediaId, mediaLabel, ENTITY_EMBEDDED_MEDIA,
                        null, "Embedded " + mediaType + ": " + mediaLabel, 0.8, mediaProps
                );
                addEntity(entityIndex, mediaEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, mediaId, REL_HAS_MEDIA,
                        displayTitle + " has embedded " + mediaType + ": " + mediaLabel,
                        0.8, null
                ));
                mediaIndex++;
            }
        }

        // ── WEB_FORM from html.forms ───────────────────────────────────────
        Object formsObj = meta.get("html.forms");
        if (formsObj instanceof List<?> formList) {
            int formLimit = Math.min(formList.size(), 50);
            for (int fi = 0; fi < formLimit; fi++) {
                Object formObj = formList.get(fi);
                if (!(formObj instanceof Map<?, ?> formMap)) continue;
                String action = str(formMap.get("action"));
                String method = str(formMap.get("method"));
                String formName = str(formMap.get("name"));

                String formLabel = formName != null ? formName
                        : (action != null && !action.isEmpty() ? method + " " + action : "Form " + fi);

                String formEntityId = entityId("form:" + (source != null ? source : displayTitle) + ":" + fi);
                Map<String, String> formProps = new LinkedHashMap<>();
                if (action != null) formProps.put("action", action);
                if (method != null) formProps.put("method", method);
                if (formName != null) formProps.put("name", formName);
                Object fieldCountObj = formMap.get("fieldCount");
                if (fieldCountObj != null) formProps.put("fieldCount", String.valueOf(fieldCountObj));

                ExtractedEntity formEntity = new ExtractedEntity(
                        formEntityId, formLabel, ENTITY_WEB_FORM,
                        null, "HTML form: " + formLabel, 0.85, formProps
                );
                addEntity(entityIndex, formEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, formEntityId, REL_HAS_WEB_FORM,
                        displayTitle + " has form: " + formLabel,
                        0.85, null
                ));

                // Extract individual form fields as FORM_INPUT entities
                Object fieldsObj = formMap.get("fields");
                if (fieldsObj instanceof List<?> fieldsList) {
                    int fieldLimit = Math.min(fieldsList.size(), 30);
                    for (int ffi = 0; ffi < fieldLimit; ffi++) {
                        Object fieldObj = fieldsList.get(ffi);
                        if (!(fieldObj instanceof Map<?, ?> fieldMap)) continue;
                        String fieldName = str(fieldMap.get("name"));
                        if (fieldName == null || fieldName.isBlank()) continue;

                        String fieldType = str(fieldMap.get("type"));
                        String fieldTag = str(fieldMap.get("tag"));
                        String placeholder = str(fieldMap.get("placeholder"));

                        String inputEntityId = entityId("form_input:" + (source != null ? source : displayTitle)
                                + ":" + fi + ":" + fieldName);
                        Map<String, String> inputProps = new LinkedHashMap<>();
                        inputProps.put("name", fieldName);
                        if (fieldType != null) inputProps.put("type", fieldType);
                        if (fieldTag != null) inputProps.put("tag", fieldTag);
                        if (placeholder != null) inputProps.put("placeholder", placeholder);
                        String required = str(fieldMap.get("required"));
                        if ("true".equals(required)) inputProps.put("required", "true");

                        String inputLabel = fieldName + (fieldType != null ? " (" + fieldType + ")" : "");
                        ExtractedEntity inputEntity = new ExtractedEntity(
                                inputEntityId, inputLabel, ENTITY_FORM_INPUT,
                                null, "Form field: " + inputLabel, 0.8, inputProps
                        );
                        addEntity(entityIndex, inputEntity);
                        relations.add(new ExtractedRelation(
                                formEntityId, inputEntityId, REL_HAS_INPUT,
                                formLabel + " has input: " + inputLabel,
                                0.8, null
                        ));
                    }
                }
            }
        }

        // ── CODE_BLOCK from html.codeBlocks ──────────────────────────────
        Object codeBlocksObj = meta.get("html.codeBlocks");
        if (codeBlocksObj instanceof List<?> codeBlockList) {
            int codeIdx = 0;
            for (Object cbObj : codeBlockList) {
                if (codeIdx >= 50 || !(cbObj instanceof Map<?, ?> cbMap)) continue;
                String code = str(cbMap.get("code"));
                if (code == null || code.isBlank()) continue;

                String language = str(cbMap.get("language"));
                String lineCount = str(cbMap.get("lineCount"));
                String preview = code.length() > 60 ? code.substring(0, 57) + "..." : code;

                String codeEntityId = entityId("html_code:" + (source != null ? source : displayTitle)
                        + ":" + codeIdx + ":" + code.hashCode());
                Map<String, String> codeProps = new LinkedHashMap<>();
                codeProps.put("codeContent", code.length() > 2000 ? code.substring(0, 2000) : code);
                if (language != null) codeProps.put("language", language);
                if (lineCount != null) codeProps.put("lineCount", lineCount);

                String name = (language != null ? language + " " : "") + "code block";
                addEntity(entityIndex, new ExtractedEntity(
                        codeEntityId, name, ENTITY_CODE_BLOCK,
                        null, "Code block: " + preview, 0.8, codeProps));
                relations.add(new ExtractedRelation(
                        docEntityId, codeEntityId, REL_HAS_CODE_BLOCK,
                        displayTitle + " has code block", 0.8, null));
                codeIdx++;
            }
        }

        // ── URL scanning from body text ─────────────────────────────────
        // Extract URLs from the document body that weren't already captured
        // from hyperlinks metadata. This catches inline URLs in rendered text.
        String bodyContent = doc.getText();
        if (bodyContent != null && !bodyContent.isEmpty()) {
            Set<String> seenUrls = new HashSet<>();
            // Collect already-known URLs to avoid duplicates
            for (ExtractedEntity ent : entityIndex.values()) {
                if (ENTITY_EXTERNAL_LINK.equals(ent.type())) {
                    String url = ent.properties() != null ? ent.properties().get("url") : null;
                    if (url != null) seenUrls.add(url.toLowerCase());
                }
            }
            Matcher urlMatcher = URL_PATTERN.matcher(bodyContent);
            int urlCount = 0;
            while (urlMatcher.find() && urlCount < 100) {
                String url = urlMatcher.group();
                if (seenUrls.add(url.toLowerCase())) {
                    String urlId = entityId("url:" + url.toLowerCase());
                    addEntity(entityIndex, new ExtractedEntity(
                            urlId, url, ENTITY_EXTERNAL_LINK,
                            null, "URL from body text: " + url, 0.8,
                            Map.of("url", url)));
                    relations.add(new ExtractedRelation(
                            docEntityId, urlId, REL_HYPERLINKS_TO,
                            displayTitle + " links to " + url, 0.8, null));
                    urlCount++;
                }
            }
        }

        entities.addAll(entityIndex.values());

        ExtractionMetadata extractionMeta = new ExtractionMetadata(
                source, source, SOURCE_HTML_EXTRACTOR, null, null, null
        );

        return ExtractionResult.of(entities, relations, extractionMeta);
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        return ExtractorUtils.extractBatch(this, docs, SOURCE_HTML_EXTRACTOR);
    }

    /**
     * Handles table sub-documents (content_type=table) by building a stub WEB_PAGE entity
     * and a TABLE entity linked by a HAS_TABLE relationship.
     */
    private ExtractionResult extractTableSubDocument(Map<String, Object> meta) {
        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();

        String source = str(meta.get(META_SOURCE));
        if (source == null) source = str(meta.get(META_SOURCE_PATH));
        if (source == null) return ExtractionResult.of(List.of(), List.of(), null);

        String tableIdxStr = str(meta.get(META_TABLE_INDEX));
        int tableIdx = tableIdxStr != null ? Integer.parseInt(tableIdxStr) : 0;

        // Stub parent WEB_PAGE entity (so the HAS_TABLE edge has a valid source)
        String pageEntityId = entityId("webpage:" + source);
        addEntity(entityIndex, new ExtractedEntity(
                pageEntityId, source, ENTITY_WEB_PAGE,
                null, "Web page: " + source, 0.5, Map.of(META_SOURCE, source)
        ));

        // TABLE entity
        String tableEntityId = entityId("table:" + source + ":" + tableIdx);
        Map<String, String> tableProps = new LinkedHashMap<>();
        tableProps.put(PROP_TABLE_INDEX, String.valueOf(tableIdx));
        tableProps.put(META_SOURCE, source);
        putIfPresent(tableProps, PROP_HEADERS, meta, META_TABLE_HEADERS);
        putValueIfPresent(tableProps, PROP_ROW_COUNT, meta, META_TABLE_ROW_COUNT);
        putValueIfPresent(tableProps, PROP_COLUMN_COUNT, meta, META_TABLE_COLUMN_COUNT);
        String htmlTableSummary = str(meta.get("table_summary"));
        if (htmlTableSummary != null) tableProps.put("summary", htmlTableSummary);

        String headers = str(meta.get(META_TABLE_HEADERS));
        String tableName = headers != null ? "Table: " + headers : "Table " + tableIdx;

        addEntity(entityIndex, new ExtractedEntity(
                tableEntityId, tableName, ENTITY_TABLE,
                null, "HTML table " + tableIdx + " from " + source, 1.0, tableProps
        ));

        relations.add(new ExtractedRelation(
                pageEntityId, tableEntityId, REL_HAS_TABLE,
                source + " has table " + tableIdx,
                1.0, null
        ));

        // Cell-level table graph from META_TABLE_GRAPH — converts the JSON Graph model
        // (from TableCellGraphBuilder) into ExtractedEntity/ExtractedRelation for the knowledge graph.
        Object tableGraphObj = meta.get(META_TABLE_GRAPH);
        if (tableGraphObj instanceof String tableGraphJson && !tableGraphJson.isBlank()) {
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
                        addEntity(entityIndex, new ExtractedEntity(
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
                log.warn("Failed to parse tableGraph JSON for HTML document: {}", e.getMessage());
            }
        }

        entities.addAll(entityIndex.values());
        return ExtractionResult.of(entities, relations,
                new ExtractionMetadata(source, source, SOURCE_HTML_EXTRACTOR, null, null, null));
    }

    /**
     * Best-effort extraction of a simple string value for a JSON field from a raw JSON string.
     * Handles both {@code "field": "value"} (quoted) and {@code "field": SomeWord} patterns.
     * Does not use a full JSON parser intentionally to avoid heavy dependencies in this extractor.
     *
     * @param json      raw JSON string
     * @param fieldName the field name to look up (e.g. "@type", "name")
     * @return the extracted string value, or {@code null} if not found
     */
    private String extractJsonField(String json, String fieldName) {
        if (json == null || fieldName == null) return null;
        String searchKey = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;
        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length()) return null;
        char first = json.charAt(valueStart);
        if (first == '"') {
            // Quoted string value
            int end = json.indexOf('"', valueStart + 1);
            if (end < 0) return null;
            String value = json.substring(valueStart + 1, end).trim();
            return value.isEmpty() ? null : value;
        } else if (first == '[') {
            // Array — grab the first string element
            int arrStart = json.indexOf('"', valueStart + 1);
            if (arrStart < 0) return null;
            int arrEnd = json.indexOf('"', arrStart + 1);
            if (arrEnd < 0) return null;
            String value = json.substring(arrStart + 1, arrEnd).trim();
            return value.isEmpty() ? null : value;
        } else {
            // Unquoted token (boolean, null, number) — return it as-is
            int end = valueStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                    && json.charAt(end) != '\n' && json.charAt(end) != '\r') {
                end++;
            }
            String value = json.substring(valueStart, end).trim();
            return value.isEmpty() ? null : value;
        }
    }
}
