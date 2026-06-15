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

package ai.kompile.app.graphrag;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic graph extractor for Confluence pages.
 * Extracts entities (pages, spaces, persons, dates) and relationships
 * (AUTHORED_BY, LAST_MODIFIED_BY, IN_SPACE, CHILD_OF, PUBLISHED_ON, MODIFIED_ON)
 * from metadata injected by ConfluenceService.
 */
@Component
public class ConfluenceGraphExtractor implements DocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceGraphExtractor.class);

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("confluence");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        boolean isConfluence = "confluence".equals(doc.getMetadata().get(GraphConstants.META_SOURCE_TYPE));
        if (isConfluence && doc.getMetadata().get(GraphConstants.META_CONFLUENCE_PAGE_ID) == null) {
            log.warn("Confluence document has source_type=confluence but missing pageId — "
                    + "will extract with available metadata. source_path={}",
                    doc.getMetadata().get(GraphConstants.META_SOURCE_PATH));
        }
        return isConfluence;
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null || !"confluence".equals(meta.get(GraphConstants.META_SOURCE_TYPE))) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        String pageId = str(meta.get(GraphConstants.META_CONFLUENCE_PAGE_ID));
        // Fall back to source_path or a generated ID if pageId is missing
        if (pageId == null) {
            String sourcePath = str(meta.get(GraphConstants.META_SOURCE_PATH));
            pageId = sourcePath != null ? sourcePath : "unknown-" + System.identityHashCode(doc);
            log.debug("Confluence document missing pageId, using fallback ID: {}", pageId);
        }

        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        String title = str(meta.get(GraphConstants.META_CONFLUENCE_TITLE));
        String displayTitle = title != null ? title : "Page " + pageId;

        // ── Page entity ────────────────────────────────────────────────
        String pageEntityId = entityId("confluence_page", pageId);
        Map<String, String> pageProps = new LinkedHashMap<>();
        pageProps.put("pageId", pageId);
        if (title != null) pageProps.put("title", title);
        putIfPresent(pageProps, "version", meta, GraphConstants.META_CONFLUENCE_VERSION);
        putIfPresent(pageProps, "type", meta, GraphConstants.META_CONFLUENCE_TYPE);
        putIfPresent(pageProps, "createdDate", meta, GraphConstants.META_CONFLUENCE_CREATED_DATE);
        putIfPresent(pageProps, "modifiedDate", meta, GraphConstants.META_CONFLUENCE_MODIFIED_DATE);
        putIfPresent(pageProps, "webUrl", meta, GraphConstants.META_CONFLUENCE_WEB_URL);
        putIfPresent(pageProps, "status", meta, GraphConstants.META_CONFLUENCE_STATUS);
        putIfPresent(pageProps, "childCount", meta, GraphConstants.META_CONFLUENCE_CHILD_COUNT);
        putIfPresent(pageProps, "hasChildren", meta, GraphConstants.META_CONFLUENCE_HAS_CHILDREN);
        String versionMessage = str(meta.get("confluence.versionMessage"));
        if (versionMessage != null) pageProps.put("versionMessage", versionMessage);
        String versionMinorEdit = str(meta.get("confluence.versionMinorEdit"));
        if (versionMinorEdit != null) pageProps.put("versionMinorEdit", versionMinorEdit);

        // Use BLOGPOST entity type when confluence.type is "blogpost"
        String confType = str(meta.get(GraphConstants.META_CONFLUENCE_TYPE));
        String entityType = "blogpost".equalsIgnoreCase(confType)
                ? GraphConstants.ENTITY_CONFLUENCE_BLOGPOST
                : GraphConstants.ENTITY_CONFLUENCE_PAGE;
        String entityDesc = "blogpost".equalsIgnoreCase(confType)
                ? "Confluence blogpost: " + displayTitle
                : "Confluence page: " + displayTitle;
        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(pageEntityId, displayTitle,
                entityType, null, entityDesc, 1.0, pageProps));

        // ── Page restrictions → PERSON/GROUP entities ──────────────────
        @SuppressWarnings("unchecked")
        List<Map<String, String>> readRestrictions = meta.get("confluence.readRestrictions") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("confluence.readRestrictions") : null;
        @SuppressWarnings("unchecked")
        List<Map<String, String>> updateRestrictions = meta.get("confluence.updateRestrictions") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("confluence.updateRestrictions") : null;
        addRestrictionEntities(entityIndex, relations, pageEntityId, readRestrictions, "READ_RESTRICTED_TO");
        addRestrictionEntities(entityIndex, relations, pageEntityId, updateRestrictions, "UPDATE_RESTRICTED_TO");

        // ── Space entity ───────────────────────────────────────────────
        String spaceKey = str(meta.get(GraphConstants.META_CONFLUENCE_SPACE_KEY));
        String spaceName = str(meta.get(GraphConstants.META_CONFLUENCE_SPACE_NAME));
        if (spaceKey != null) {
            String spaceEntityId = entityId("confluence_space", spaceKey);
            Map<String, String> spaceProps = new LinkedHashMap<>();
            spaceProps.put("spaceKey", spaceKey);
            if (spaceName != null) spaceProps.put("spaceName", spaceName);
            putIfPresent(spaceProps, "spaceType", meta, GraphConstants.META_CONFLUENCE_SPACE_TYPE);
            putIfPresent(spaceProps, "spaceDescription", meta, GraphConstants.META_CONFLUENCE_SPACE_DESCRIPTION);
            putIfPresent(spaceProps, "spaceStatus", meta, GraphConstants.META_CONFLUENCE_SPACE_STATUS);
            String spaceIconUrl = str(meta.get(GraphConstants.META_CONFLUENCE_SPACE_ICON_URL));
            if (spaceIconUrl != null) spaceProps.put("spaceIconUrl", spaceIconUrl);

            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(spaceEntityId,
                    spaceName != null ? spaceName : spaceKey,
                    GraphConstants.ENTITY_CONFLUENCE_SPACE, null,
                    "Confluence space: " + (spaceName != null ? spaceName : spaceKey),
                    1.0, spaceProps));

            relations.add(new ExtractedRelation(pageEntityId, spaceEntityId,
                    GraphConstants.REL_IN_SPACE, displayTitle + " is in space " + spaceKey, 1.0, null));

            // Space icon URL as EXTERNAL_RESOURCE entity
            if (spaceIconUrl != null && (spaceIconUrl.startsWith("http://") || spaceIconUrl.startsWith("https://"))) {
                String iconEntityId = entityId("url", spaceIconUrl.toLowerCase());
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(iconEntityId,
                        "Icon: " + (spaceName != null ? spaceName : spaceKey),
                        GraphConstants.ENTITY_EXTERNAL_RESOURCE, null,
                        "Space icon for " + spaceKey, 0.7,
                        Map.of("url", spaceIconUrl, "resourceType", "icon")));
                relations.add(new ExtractedRelation(spaceEntityId, iconEntityId,
                        GraphConstants.REL_HYPERLINKS_TO,
                        spaceKey + " space icon: " + spaceIconUrl, 0.7, null));
            }

            // Link space to its homepage page (if known)
            String homepageId = str(meta.get(GraphConstants.META_CONFLUENCE_SPACE_HOMEPAGE_ID));
            if (homepageId != null) {
                spaceProps.put("homepageId", homepageId);
                String homepageEntityId = entityId("confluence_page", homepageId);
                // Create a stub page entity for the homepage (will merge with the real one when ingested)
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(homepageEntityId,
                        "Homepage of " + (spaceName != null ? spaceName : spaceKey),
                        GraphConstants.ENTITY_CONFLUENCE_PAGE, null,
                        "Homepage of space " + spaceKey, 0.7,
                        Map.of("pageId", homepageId)));
                relations.add(new ExtractedRelation(spaceEntityId, homepageEntityId,
                        GraphConstants.REL_HAS_HOMEPAGE,
                        (spaceName != null ? spaceName : spaceKey) + " homepage is " + homepageId,
                        0.9, null));
            }
        }

        // ── Child page relations ──────────────────────────────────────
        Object childPagesObj = meta.get(GraphConstants.META_CONFLUENCE_CHILD_PAGE_IDS);
        if (childPagesObj instanceof List<?> childPagesList) {
            for (Object item : childPagesList) {
                if (!(item instanceof Map<?, ?> childMap)) continue;
                String childId = childMap.get("id") instanceof String s ? s : null;
                String childTitle = childMap.get("title") instanceof String s ? s : null;
                if (childId == null) continue;
                String childEntityId = entityId("confluence_page", childId);
                Map<String, String> childProps = new LinkedHashMap<>();
                childProps.put("pageId", childId);
                if (childTitle != null) childProps.put("title", childTitle);
                // Create stub child page entity (will merge with real entity when child is ingested)
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(childEntityId,
                        childTitle != null ? childTitle : "Page " + childId,
                        GraphConstants.ENTITY_CONFLUENCE_PAGE, null,
                        "Child page of " + displayTitle, 0.7, childProps));
                relations.add(new ExtractedRelation(childEntityId, pageEntityId,
                        GraphConstants.REL_CHILD_OF,
                        (childTitle != null ? childTitle : childId) + " is child of " + displayTitle,
                        0.9, null));
            }
        }

        // ── Author (createdBy) ─────────────────────────────────────────
        String createdBy = str(meta.get(GraphConstants.META_CONFLUENCE_CREATED_BY));
        String createdByAccountId = str(meta.get(GraphConstants.META_CONFLUENCE_CREATED_BY_ACCOUNT_ID));
        if (createdBy != null) {
            // Use accountId for stable entity key when available (consistent with comment author keying)
            String authorKey = createdByAccountId != null ? createdByAccountId : createdBy.toLowerCase();
            String authorId = entityId("person", authorKey);
            Map<String, String> authorProps = new LinkedHashMap<>();
            authorProps.put("displayName", createdBy);
            if (createdByAccountId != null) authorProps.put("accountId", createdByAccountId);
            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(authorId, createdBy,
                    GraphConstants.ENTITY_PERSON, null, "Confluence user: " + createdBy, 1.0, authorProps));
            relations.add(new ExtractedRelation(pageEntityId, authorId,
                    GraphConstants.REL_AUTHORED_BY, displayTitle + " authored by " + createdBy, 1.0, null));
        }

        // ── Last modifier ──────────────────────────────────────────────
        String lastModifiedBy = str(meta.get(GraphConstants.META_CONFLUENCE_LAST_MODIFIED_BY));
        String lastModifiedByAccountId = str(meta.get(GraphConstants.META_CONFLUENCE_LAST_MODIFIED_BY_ACCOUNT_ID));
        if (lastModifiedBy != null) {
            // Use accountId for stable entity key when available (consistent with comment author keying)
            String modifierKey = lastModifiedByAccountId != null ? lastModifiedByAccountId : lastModifiedBy.toLowerCase();
            String resolvedAuthorKey = createdByAccountId != null ? createdByAccountId : (createdBy != null ? createdBy.toLowerCase() : null);
            // Only create a new entity if this is a different person from the author
            boolean sameAsAuthor = resolvedAuthorKey != null && modifierKey.equals(resolvedAuthorKey);
            String modifierId = entityId("person", modifierKey);
            if (!sameAsAuthor) {
                Map<String, String> modifierProps = new LinkedHashMap<>();
                modifierProps.put("displayName", lastModifiedBy);
                if (lastModifiedByAccountId != null) modifierProps.put("accountId", lastModifiedByAccountId);
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(modifierId, lastModifiedBy,
                        GraphConstants.ENTITY_PERSON, null, "Confluence user: " + lastModifiedBy, 1.0, modifierProps));
            }
            relations.add(new ExtractedRelation(pageEntityId, modifierId,
                    GraphConstants.REL_LAST_MODIFIED_BY, displayTitle + " last modified by " + lastModifiedBy, 1.0, null));
        }

        // ── DATE entities from creation/modification timestamps ────────
        String createdDate = str(meta.get(GraphConstants.META_CONFLUENCE_CREATED_DATE));
        if (createdDate != null) {
            String dateId = entityId("date", createdDate);
            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(dateId, createdDate,
                    GraphConstants.ENTITY_DATE, null, "Creation date: " + createdDate, 0.85,
                    Map.of("date", createdDate, "dateType", "created")));
            relations.add(new ExtractedRelation(pageEntityId, dateId,
                    GraphConstants.REL_PUBLISHED_ON,
                    displayTitle + " created on " + createdDate, 0.85, null));
        }
        String modifiedDate = str(meta.get(GraphConstants.META_CONFLUENCE_MODIFIED_DATE));
        if (modifiedDate != null) {
            String modDateId = entityId("date", modifiedDate);
            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(modDateId, modifiedDate,
                    GraphConstants.ENTITY_DATE, null, "Modification date: " + modifiedDate, 0.85,
                    Map.of("date", modifiedDate, "dateType", "modified")));
            relations.add(new ExtractedRelation(pageEntityId, modDateId,
                    GraphConstants.REL_MODIFIED_ON,
                    displayTitle + " modified on " + modifiedDate, 0.85, null));
        }

        // ── Labels ─────────────────────────────────────────────────────
        Object labelsObj = meta.get(GraphConstants.META_CONFLUENCE_LABELS);
        if (labelsObj instanceof List<?> labelsList) {
            for (Object labelObj : labelsList) {
                String label = labelObj != null ? labelObj.toString().trim() : null;
                if (label == null || label.isEmpty()) continue;
                String labelEntityId = entityId("confluence_label", label.toLowerCase());
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(labelEntityId, label,
                        GraphConstants.ENTITY_CONFLUENCE_LABEL, null,
                        "Confluence label: " + label, 0.9,
                        Map.of("labelName", label)));
                relations.add(new ExtractedRelation(pageEntityId, labelEntityId,
                        GraphConstants.REL_HAS_LABEL, displayTitle + " has label: " + label, 0.9, null));
            }
        }

        // ── Parent page (immediate hierarchy) ──────────────────────────
        String parentPageId = str(meta.get(GraphConstants.META_CONFLUENCE_PARENT_PAGE_ID));
        if (parentPageId != null) {
            String parentEntityId = entityId("confluence_page", parentPageId);
            String parentTitle = str(meta.get(GraphConstants.META_CONFLUENCE_PARENT_PAGE_TITLE));
            Map<String, String> parentProps = new LinkedHashMap<>();
            parentProps.put("pageId", parentPageId);
            if (parentTitle != null) parentProps.put("title", parentTitle);

            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(parentEntityId,
                    parentTitle != null ? parentTitle : "Page " + parentPageId,
                    GraphConstants.ENTITY_CONFLUENCE_PAGE, null,
                    "Confluence page (parent)", 0.5, parentProps));

            relations.add(new ExtractedRelation(pageEntityId, parentEntityId,
                    GraphConstants.REL_CHILD_OF, displayTitle + " is child of " + (parentTitle != null ? parentTitle : parentPageId),
                    1.0, null));
        }

        // ── Full ancestor chain ────────────────────────────────────────
        Object ancestorsObj = meta.get(GraphConstants.META_CONFLUENCE_ANCESTORS);
        if (ancestorsObj instanceof List<?> ancestorsList) {
            for (Object ancestorObj : ancestorsList) {
                if (ancestorObj instanceof Map<?, ?> ancestorMap) {
                    String ancestorId = ancestorMap.get("id") != null ? ancestorMap.get("id").toString() : null;
                    String ancestorTitle = ancestorMap.get("title") != null ? ancestorMap.get("title").toString() : null;
                    if (ancestorId != null && !ancestorId.equals(parentPageId)) {
                        String ancestorEntityId = entityId("confluence_page", ancestorId);
                        Map<String, String> ancestorProps = new LinkedHashMap<>();
                        ancestorProps.put("pageId", ancestorId);
                        if (ancestorTitle != null) ancestorProps.put("title", ancestorTitle);

                        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(ancestorEntityId,
                                ancestorTitle != null ? ancestorTitle : "Page " + ancestorId,
                                GraphConstants.ENTITY_CONFLUENCE_PAGE, null,
                                "Confluence page (ancestor)", 0.4, ancestorProps));

                        relations.add(new ExtractedRelation(ancestorEntityId, pageEntityId,
                                GraphConstants.REL_ANCESTOR_OF,
                                (ancestorTitle != null ? ancestorTitle : ancestorId) + " is ancestor of " + displayTitle,
                                0.9, null));
                    }
                }
            }
        }

        // ── Comments ───────────────────────────────────────────────────
        Object commentsObj = meta.get(GraphConstants.META_CONFLUENCE_COMMENTS);
        if (commentsObj instanceof List<?> commentsList) {
            int commentIdx = 0;
            for (Object commentObj : commentsList) {
                if (commentObj instanceof Map<?, ?> commentMap) {
                    String commentId = commentMap.get("id") != null ? commentMap.get("id").toString() : String.valueOf(commentIdx);
                    String commentAuthor = commentMap.get("author") != null ? commentMap.get("author").toString() : null;
                    String commentAuthorAccountId = commentMap.get("authorAccountId") != null ? commentMap.get("authorAccountId").toString() : null;
                    String commentBody = commentMap.get("body") != null ? commentMap.get("body").toString() : null;
                    String commentDate = commentMap.get("date") != null ? commentMap.get("date").toString() : null;

                    String commentEntityId = entityId("confluence_comment", pageId + ":" + commentId);
                    Map<String, String> commentProps = new LinkedHashMap<>();
                    commentProps.put("commentId", commentId);
                    if (commentBody != null) commentProps.put("body", commentBody.length() > 500 ? commentBody.substring(0, 500) : commentBody);
                    if (commentDate != null) commentProps.put("date", commentDate);
                    if (commentAuthor != null) commentProps.put("author", commentAuthor);

                    ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(commentEntityId,
                            "Comment by " + (commentAuthor != null ? commentAuthor : "unknown"),
                            GraphConstants.ENTITY_CONFLUENCE_COMMENT, null,
                            "Comment on " + displayTitle, 0.8, commentProps));

                    relations.add(new ExtractedRelation(pageEntityId, commentEntityId,
                            GraphConstants.REL_HAS_COMMENT,
                            displayTitle + " has comment by " + (commentAuthor != null ? commentAuthor : "unknown"),
                            0.8, null));

                    // ── Comment DATE entity ──────────────────────────────────
                    if (commentDate != null && !commentDate.isBlank()) {
                        String commentDateId = entityId("date", commentDate);
                        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(commentDateId, commentDate,
                                GraphConstants.ENTITY_DATE, null,
                                "Comment date: " + commentDate, 0.85,
                                Map.of("date", commentDate, "dateType", "commentCreated")));
                        relations.add(new ExtractedRelation(commentEntityId, commentDateId,
                                GraphConstants.REL_PUBLISHED_ON,
                                "Comment published on " + commentDate, 0.85, null));
                    }

                    if (commentAuthor != null) {
                        // Use accountId for stable entity key when available (matches page author keying)
                        String authorKey = commentAuthorAccountId != null ? commentAuthorAccountId : commentAuthor.toLowerCase();
                        String authorEntityId = entityId("person", authorKey);
                        Map<String, String> authorProps = new LinkedHashMap<>();
                        authorProps.put("displayName", commentAuthor);
                        if (commentAuthorAccountId != null) authorProps.put("accountId", commentAuthorAccountId);
                        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(authorEntityId, commentAuthor,
                                GraphConstants.ENTITY_PERSON, null, "Confluence user: " + commentAuthor, 0.8,
                                authorProps));
                        relations.add(new ExtractedRelation(commentEntityId, authorEntityId,
                                GraphConstants.REL_COMMENT_BY,
                                "Comment by " + commentAuthor, 0.8, null));
                        // Person → Page: bidirectional traversal support
                        relations.add(new ExtractedRelation(authorEntityId, pageEntityId,
                                GraphConstants.REL_COMMENTED_ON,
                                commentAuthor + " commented on " + displayTitle, 0.8, null));
                    }
                }
                commentIdx++;
            }
        }

        // ── Attachments ────────────────────────────────────────────────
        Object attachmentsObj = meta.get(GraphConstants.META_CONFLUENCE_ATTACHMENTS);
        if (attachmentsObj instanceof List<?> attachmentsList) {
            int attachIdx = 0;
            for (Object attachObj : attachmentsList) {
                if (attachObj instanceof Map<?, ?> attachMap) {
                    String attachId = attachMap.get("id") != null ? attachMap.get("id").toString() : String.valueOf(attachIdx);
                    String attachTitle = attachMap.get("title") != null ? attachMap.get("title").toString() : "Attachment " + attachIdx;
                    String attachMediaType = attachMap.get("mediaType") != null ? attachMap.get("mediaType").toString() : null;
                    String attachSize = attachMap.get(GraphConstants.META_FILE_SIZE) != null ? attachMap.get(GraphConstants.META_FILE_SIZE).toString() : null;
                    String attachCreator = attachMap.get("creator") != null ? attachMap.get("creator").toString() : null;

                    String attachCreatorAccountId = attachMap.get("creatorAccountId") != null ? attachMap.get("creatorAccountId").toString() : null;
                    String attachDownloadUrl = attachMap.get("downloadUrl") != null ? attachMap.get("downloadUrl").toString() : null;
                    String attachUploadDate = attachMap.get("uploadDate") != null ? attachMap.get("uploadDate").toString() : null;

                    String attachEntityId = entityId("confluence_attachment", pageId + ":" + attachId);
                    Map<String, String> attachProps = new LinkedHashMap<>();
                    attachProps.put("attachmentId", attachId);
                    attachProps.put(GraphConstants.META_FILE_NAME, attachTitle);
                    if (attachMediaType != null) attachProps.put("mediaType", attachMediaType);
                    if (attachSize != null) attachProps.put(GraphConstants.META_FILE_SIZE, attachSize);
                    if (attachCreator != null) attachProps.put("creator", attachCreator);
                    if (attachCreatorAccountId != null) attachProps.put("creatorAccountId", attachCreatorAccountId);
                    if (attachDownloadUrl != null) attachProps.put("downloadUrl", attachDownloadUrl);
                    if (attachUploadDate != null) attachProps.put("uploadDate", attachUploadDate);

                    ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(attachEntityId, attachTitle,
                            GraphConstants.ENTITY_CONFLUENCE_ATTACHMENT, null,
                            "Attachment on " + displayTitle + ": " + attachTitle, 0.9, attachProps));

                    relations.add(new ExtractedRelation(pageEntityId, attachEntityId,
                            GraphConstants.REL_HAS_ATTACHMENT,
                            displayTitle + " has attachment: " + attachTitle, 0.9, null));

                    // ── Attachment download URL as EXTERNAL_RESOURCE ──────────
                    if (attachDownloadUrl != null && !attachDownloadUrl.isBlank()) {
                        String downloadUrlId = entityId("url", attachDownloadUrl.toLowerCase());
                        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(downloadUrlId, attachDownloadUrl,
                                GraphConstants.ENTITY_EXTERNAL_RESOURCE, null,
                                "Download URL for attachment: " + attachTitle, 0.85,
                                Map.of("url", attachDownloadUrl)));
                        relations.add(new ExtractedRelation(attachEntityId, downloadUrlId,
                                GraphConstants.REL_HYPERLINKS_TO,
                                attachTitle + " downloadable at " + attachDownloadUrl, 0.85, null));
                    }

                    if (attachCreator != null) {
                        // Use accountId for stable dedup when available
                        String creatorKey = attachCreatorAccountId != null ? attachCreatorAccountId : attachCreator.toLowerCase();
                        String creatorEntityId = entityId("person", creatorKey);
                        Map<String, String> creatorProps = new LinkedHashMap<>();
                        creatorProps.put("displayName", attachCreator);
                        if (attachCreatorAccountId != null) creatorProps.put("accountId", attachCreatorAccountId);
                        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(creatorEntityId, attachCreator,
                                GraphConstants.ENTITY_PERSON, null, "Confluence user: " + attachCreator, 0.8,
                                creatorProps));
                        relations.add(new ExtractedRelation(attachEntityId, creatorEntityId,
                                GraphConstants.REL_UPLOADED_BY,
                                attachTitle + " uploaded by " + attachCreator, 0.8, null));
                    }

                    // Attachment upload date → DATE entity
                    if (attachUploadDate != null && !attachUploadDate.isBlank()) {
                        String uploadDateId = entityId("date", attachUploadDate);
                        ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(uploadDateId, attachUploadDate,
                                GraphConstants.ENTITY_DATE, null,
                                "Attachment upload date: " + attachUploadDate, 0.8,
                                Map.of("date", attachUploadDate, "dateType", "uploaded")));
                        relations.add(new ExtractedRelation(attachEntityId, uploadDateId,
                                GraphConstants.REL_PUBLISHED_ON,
                                attachTitle + " uploaded on " + attachUploadDate, 0.8, null));
                    }
                }
                attachIdx++;
            }
        }

        // ── Internal page references from body content ────────────────
        String bodyText = doc.getText();
        if (bodyText != null && !bodyText.isBlank()) {
            Set<String> referencedPageIds = new LinkedHashSet<>();
            // Pattern 1: /wiki/spaces/<spaceKey>/pages/<pageId>/<title>
            java.util.regex.Matcher wikiMatcher = java.util.regex.Pattern
                    .compile("/wiki/spaces/[^/]+/pages/(\\d+)")
                    .matcher(bodyText);
            while (wikiMatcher.find() && referencedPageIds.size() < 50) {
                referencedPageIds.add(wikiMatcher.group(1));
            }
            // Pattern 2: pageId=<id> (e.g., viewpage.action?pageId=12345)
            java.util.regex.Matcher viewMatcher = java.util.regex.Pattern
                    .compile("pageId=(\\d+)")
                    .matcher(bodyText);
            while (viewMatcher.find() && referencedPageIds.size() < 50) {
                referencedPageIds.add(viewMatcher.group(1));
            }
            // Remove self-reference
            referencedPageIds.remove(pageId);
            for (String refPageId : referencedPageIds) {
                String refEntityId = entityId("confluence_page", refPageId);
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(refEntityId, "Page " + refPageId,
                        GraphConstants.ENTITY_CONFLUENCE_PAGE, null,
                        "Referenced Confluence page", 0.4,
                        Map.of("pageId", refPageId)));
                relations.add(new ExtractedRelation(pageEntityId, refEntityId,
                        GraphConstants.REL_REFERENCES,
                        displayTitle + " references page " + refPageId, 0.8, null));
            }

            // ── External URLs from body content ─────────────────────────
            java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                    .compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
                    .matcher(bodyText);
            Set<String> seenUrls = new LinkedHashSet<>();
            while (urlMatcher.find() && seenUrls.size() < 50) {
                String url = urlMatcher.group();
                if (url.contains("/wiki/spaces/") || url.contains("pageId=")) continue;
                if (!seenUrls.add(url)) continue;
                String urlEntityId = entityId("url", url);
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(urlEntityId, url,
                        GraphConstants.ENTITY_EXTERNAL_RESOURCE, null,
                        "External link from Confluence page", 0.7,
                        Map.of("url", url)));
                relations.add(new ExtractedRelation(pageEntityId, urlEntityId,
                        GraphConstants.REL_HYPERLINKS_TO,
                        displayTitle + " links to " + url, 0.7, null));
            }

            // ── @mention user extraction from body text ────────────────────
            Set<String> mentionedUsers = new LinkedHashSet<>();

            Matcher wikiMentionMatcher = Pattern.compile("\\[~([^\\]]+)\\]").matcher(bodyText);
            while (wikiMentionMatcher.find() && mentionedUsers.size() < 50) {
                String mentioned = wikiMentionMatcher.group(1).trim();
                if (!mentioned.isEmpty()) mentionedUsers.add(mentioned);
            }

            String rawStorageBody = meta.get(GraphConstants.META_CONFLUENCE_RAW_STORAGE_BODY) instanceof String s ? s : null;
            if (rawStorageBody != null) {
                Matcher riUserMatcher = Pattern.compile("ri:user(?:key|name)=\"([^\"]+)\"").matcher(rawStorageBody);
                while (riUserMatcher.find() && mentionedUsers.size() < 50) {
                    String mentioned = riUserMatcher.group(1).trim();
                    if (!mentioned.isEmpty()) mentionedUsers.add(mentioned);
                }
            } else {
                Matcher riUserMatcher = Pattern.compile("ri:user(?:key|name)=\"([^\"]+)\"").matcher(bodyText);
                while (riUserMatcher.find() && mentionedUsers.size() < 50) {
                    String mentioned = riUserMatcher.group(1).trim();
                    if (!mentioned.isEmpty()) mentionedUsers.add(mentioned);
                }
            }

            for (String mentioned : mentionedUsers) {
                // If the mention is an accountId reference, use it as a property but key on accountId
                // for consistent dedup (Confluence mentions may not have display names)
                boolean isAccountId = mentioned.startsWith("accountid:");
                String displayName = isAccountId ? mentioned.substring(10) : mentioned;
                // Key by lowercase display name for consistency with author/modifier entities
                String mentionEntityId = entityId("person", displayName.toLowerCase());
                Map<String, String> mentionProps = new LinkedHashMap<>();
                mentionProps.put("displayName", displayName);
                if (isAccountId) mentionProps.put("accountId", mentioned.substring(10));
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(mentionEntityId, displayName,
                        GraphConstants.ENTITY_PERSON, null,
                        "User mentioned in Confluence page", 0.7,
                        mentionProps));
                relations.add(new ExtractedRelation(pageEntityId, mentionEntityId,
                        GraphConstants.REL_MENTIONS_USER,
                        displayTitle + " mentions " + displayName, 0.8, null));
            }
        }

        // ── Structural extraction from Confluence storage format HTML ──────
        String storageBody = meta.get(GraphConstants.META_CONFLUENCE_RAW_STORAGE_BODY) instanceof String s ? s : null;
        if (storageBody == null && bodyText != null) {
            storageBody = bodyText;
        }
        if (storageBody != null) {
            if (storageBody.contains("<table")) {
                extractConfluenceTables(storageBody, pageId, displayTitle,
                        pageEntityId, entityIndex, relations);
            }
            extractConfluenceHeadings(storageBody, pageId, displayTitle,
                    pageEntityId, entityIndex, relations);
            extractConfluenceCodeBlocks(storageBody, pageId, displayTitle,
                    pageEntityId, entityIndex, relations);
        }

        return ExtractionResult.of(new ArrayList<>(entityIndex.values()), relations,
                ExtractionMetadata.forChunk(pageId, null, GraphConstants.SOURCE_CONFLUENCE_EXTRACTOR));
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        Map<String, ExtractedEntity> entityMap = new LinkedHashMap<>();
        Set<String> relKeys = new LinkedHashSet<>();
        List<ExtractedRelation> allRels = new ArrayList<>();

        for (Document doc : docs) {
            ExtractionResult result = extract(doc);
            for (ExtractedEntity e : result.entities()) {
                ExtractorUtils.addEntity(entityMap, e);
            }
            for (ExtractedRelation r : result.relations()) {
                if (relKeys.add(r.source() + "|" + r.target() + "|" + r.type())) {
                    allRels.add(r);
                }
            }
        }

        return ExtractionResult.of(new ArrayList<>(entityMap.values()), allRels,
                ExtractionMetadata.forChunk(null, null, GraphConstants.SOURCE_CONFLUENCE_EXTRACTOR));
    }

    /**
     * Extracts h1-h6 headings from Confluence storage format HTML and creates
     * DOCUMENT_SECTION entities with hierarchical SUBSECTION_OF relations.
     */
    private void extractConfluenceHeadings(String html, String pageId, String pageTitle,
                                            String pageEntityId,
                                            Map<String, ExtractedEntity> entityIndex,
                                            List<ExtractedRelation> relations) {
        Pattern headingPattern = Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = headingPattern.matcher(html);

        Map<Integer, String> depthStack = new LinkedHashMap<>();
        int headingIdx = 0;

        while (matcher.find() && headingIdx < 200) {
            int level = Integer.parseInt(matcher.group(1));
            // Strip HTML tags from heading text
            String headingText = matcher.group(2).replaceAll("<[^>]+>", "").trim();
            headingText = headingText.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&nbsp;", " ");
            if (headingText.isEmpty()) continue;

            String sectionId = entityId("confluence_section",
                    pageId + ":" + headingText.toLowerCase() + ":" + headingIdx);
            Map<String, String> sectionProps = new LinkedHashMap<>();
            sectionProps.put("headingText", headingText);
            sectionProps.put("headingLevel", String.valueOf(level));
            sectionProps.put("sectionIndex", String.valueOf(headingIdx));

            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(
                    sectionId, headingText,
                    GraphConstants.ENTITY_DOCUMENT_SECTION, null,
                    "Section in " + pageTitle + ": " + headingText, 0.85, sectionProps));

            if (level <= 1) {
                relations.add(new ExtractedRelation(
                        pageEntityId, sectionId,
                        GraphConstants.REL_HAS_SECTION,
                        pageTitle + " has section: " + headingText, 0.85, null));
            } else {
                String parentId = null;
                for (int d = level - 1; d >= 0; d--) {
                    if (depthStack.containsKey(d)) {
                        parentId = depthStack.get(d);
                        break;
                    }
                }
                if (parentId != null) {
                    relations.add(new ExtractedRelation(
                            sectionId, parentId,
                            GraphConstants.REL_SUBSECTION_OF,
                            headingText + " is subsection of parent", 0.85, null));
                } else {
                    relations.add(new ExtractedRelation(
                            pageEntityId, sectionId,
                            GraphConstants.REL_HAS_SECTION,
                            pageTitle + " has section: " + headingText, 0.85, null));
                }
            }
            depthStack.put(level, sectionId);
            final int currentLevel = level;
            depthStack.entrySet().removeIf(e -> e.getKey() > currentLevel);
            headingIdx++;
        }
    }

    /**
     * Extracts code blocks from Confluence structured macros (ac:structured-macro name="code")
     * and creates CODE_BLOCK entities linked to the page.
     */
    private void extractConfluenceCodeBlocks(String html, String pageId, String pageTitle,
                                              String pageEntityId,
                                              Map<String, ExtractedEntity> entityIndex,
                                              List<ExtractedRelation> relations) {
        // Confluence code macro: <ac:structured-macro ac:name="code">
        //   <ac:parameter ac:name="language">java</ac:parameter>
        //   <ac:plain-text-body><![CDATA[code here]]></ac:plain-text-body>
        // </ac:structured-macro>
        Pattern codeMacroPattern = Pattern.compile(
                "<ac:structured-macro[^>]*ac:name=\"code\"[^>]*>(.*?)</ac:structured-macro>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher macroMatcher = codeMacroPattern.matcher(html);
        int codeIdx = 0;

        while (macroMatcher.find() && codeIdx < 50) {
            String macroContent = macroMatcher.group(1);

            // Extract language parameter
            String language = null;
            Pattern langPattern = Pattern.compile(
                    "<ac:parameter[^>]*ac:name=\"language\"[^>]*>(.*?)</ac:parameter>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher langMatcher = langPattern.matcher(macroContent);
            if (langMatcher.find()) {
                language = langMatcher.group(1).trim();
            }

            // Extract code body from CDATA or plain text
            String codeContent = null;
            Pattern bodyPattern = Pattern.compile(
                    "<ac:plain-text-body>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</ac:plain-text-body>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher bodyMatcher = bodyPattern.matcher(macroContent);
            if (bodyMatcher.find()) {
                codeContent = bodyMatcher.group(1).trim();
            }

            if (codeContent == null || codeContent.isEmpty()) continue;

            String preview = codeContent.length() > 60
                    ? codeContent.substring(0, 57) + "..." : codeContent;
            String codeEntityId = entityId("confluence_code",
                    pageId + ":" + codeIdx + ":" + codeContent.hashCode());
            Map<String, String> codeProps = new LinkedHashMap<>();
            codeProps.put("codeContent", codeContent.length() > 2000
                    ? codeContent.substring(0, 2000) : codeContent);
            if (language != null && !language.isEmpty()) {
                codeProps.put("language", language);
            }
            codeProps.put("lineCount", String.valueOf(codeContent.split("\n").length));

            String name = (language != null && !language.isEmpty() ? language + " " : "")
                    + "code block";
            ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(
                    codeEntityId, name,
                    GraphConstants.ENTITY_CODE_BLOCK, null,
                    "Code block in " + pageTitle + ": " + preview, 0.8, codeProps));

            relations.add(new ExtractedRelation(
                    pageEntityId, codeEntityId,
                    GraphConstants.REL_HAS_CODE_BLOCK,
                    pageTitle + " has code block", 0.8, null));

            codeIdx++;
        }
    }

    /**
     * Parses HTML tables from Confluence storage format body and creates
     * TABLE + cell-level graph entities using TableCellGraphBuilder.
     */
    private void extractConfluenceTables(String html, String pageId, String pageTitle,
                                          String pageEntityId,
                                          Map<String, ExtractedEntity> entityIndex,
                                          List<ExtractedRelation> relations) {
        // Simple regex-based table extraction — handles Confluence storage format tables
        // which use standard <table>, <thead>, <tbody>, <tr>, <th>, <td> markup
        Pattern tablePattern = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher tableMatcher = tablePattern.matcher(html);
        int tableIdx = 0;

        while (tableMatcher.find() && tableIdx < 50) {
            String tableHtml = tableMatcher.group(1);
            // Strip nested tables to avoid extracting their rows as part of the outer table
            tableHtml = tableHtml.replaceAll("(?si)<table[^>]*>.*?</table>", "");
            // Extract all rows
            Pattern rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher rowMatcher = rowPattern.matcher(tableHtml);

            List<List<String>> allRows = new ArrayList<>();
            boolean firstRowIsHeader = false;

            while (rowMatcher.find()) {
                String rowHtml = rowMatcher.group(1);
                List<String> cells = new ArrayList<>();
                boolean rowHasHeaders = false;

                // Extract <th> and <td> cells
                Pattern cellPattern = Pattern.compile("<(th|td)[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                Matcher cellMatcher = cellPattern.matcher(rowHtml);
                while (cellMatcher.find()) {
                    String tag = cellMatcher.group(1).toLowerCase();
                    if ("th".equals(tag)) rowHasHeaders = true;
                    // Strip HTML tags from cell content
                    String cellText = cellMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                    // Decode basic HTML entities
                    cellText = cellText.replace("&amp;", "&").replace("&lt;", "<")
                            .replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"");
                    cells.add(cellText);
                }

                if (!cells.isEmpty()) {
                    if (allRows.isEmpty() && rowHasHeaders) {
                        firstRowIsHeader = true;
                    }
                    allRows.add(cells);
                }
            }

            // Skip empty tables or tables with only one cell
            if (allRows.isEmpty() || (allRows.size() == 1 && allRows.get(0).size() <= 1)) {
                continue;
            }

            // Build cell-level graph
            List<String> headers = firstRowIsHeader ? allRows.get(0) : null;
            String tableName = "Table-" + (tableIdx + 1);

            TableCellGraphBuilder builder = new TableCellGraphBuilder()
                    .namespace("confluence:" + pageId + "/tbl:" + tableIdx)
                    .tableName(tableName)
                    .firstRowIsHeader(firstRowIsHeader);
            if (headers != null) {
                builder.headers(headers);
            }
            for (List<String> row : allRows) {
                builder.addRow(row);
            }
            Graph cellGraph = builder.build();

            if (cellGraph.getEntities() != null && !cellGraph.getEntities().isEmpty()) {
                // Create a TABLE entity for this table linked to the page
                String tableEntityId = entityId("confluence_table", pageId + ":" + tableIdx);
                int rowCount = allRows.size();
                int colCount = allRows.stream().mapToInt(List::size).max().orElse(0);
                Map<String, String> tableProps = new LinkedHashMap<>();
                tableProps.put("rowCount", String.valueOf(rowCount));
                tableProps.put("columnCount", String.valueOf(colCount));
                if (headers != null) {
                    tableProps.put("headers", String.join(", ", headers));
                }
                tableProps.put("tableIndex", String.valueOf(tableIdx));

                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(
                        tableEntityId, tableName,
                        GraphConstants.ENTITY_TABLE, null,
                        "Table in Confluence page " + pageTitle + ": " + tableName,
                        0.9, tableProps));

                relations.add(new ExtractedRelation(
                        pageEntityId, tableEntityId,
                        GraphConstants.REL_HAS_TABLE,
                        pageTitle + " has " + tableName, 0.9, null));

                // Import cell-level entities from the graph
                for (Entity e : cellGraph.getEntities()) {
                    Map<String, String> props = new LinkedHashMap<>();
                    if (e.getMetadata() != null) {
                        e.getMetadata().forEach((k, v) -> {
                            if (v != null) props.put(k, v.toString());
                        });
                    }
                    ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(
                            e.getId(), e.getTitle(), e.getType(), null,
                            e.getDescription(),
                            e.getConfidence() != null ? e.getConfidence() : 0.8, props));
                }

                // Import relationships from the cell graph
                if (cellGraph.getRelationships() != null) {
                    for (Relationship r : cellGraph.getRelationships()) {
                        relations.add(new ExtractedRelation(
                                r.getSource(), r.getTarget(), r.getType(),
                                r.getDescription(),
                                r.getWeight() != null ? r.getWeight() : 0.8, null));
                    }
                }
            }

            tableIdx++;
        }
    }

    static String entityId(String prefix, String key) {
        return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    private static void putIfPresent(Map<String, String> props, String propKey,
                                      Map<String, Object> meta, String metaKey) {
        Object val = meta.get(metaKey);
        if (val != null) props.put(propKey, val.toString().trim());
    }

    private void addRestrictionEntities(Map<String, ExtractedEntity> entityIndex,
                                         List<ExtractedRelation> relations,
                                         String pageEntityId,
                                         List<Map<String, String>> restrictions,
                                         String relationType) {
        if (restrictions == null || restrictions.isEmpty()) return;
        for (Map<String, String> restriction : restrictions) {
            String type = restriction.get("type");
            if ("user".equals(type)) {
                String accountId = restriction.get("accountId");
                String displayName = restriction.get("displayName");
                if (accountId == null && displayName == null) continue;
                String key = accountId != null ? accountId : displayName.toLowerCase();
                String personId = entityId("person", key);
                Map<String, String> personProps = new LinkedHashMap<>();
                if (displayName != null) personProps.put("displayName", displayName);
                if (accountId != null) personProps.put("accountId", accountId);
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(
                        personId, displayName != null ? displayName : accountId,
                        GraphConstants.ENTITY_PERSON, null,
                        "Confluence user with " + relationType.toLowerCase().replace("_", " ") + " access",
                        0.7, personProps));
                relations.add(new ExtractedRelation(
                        pageEntityId, personId, relationType,
                        "Page " + relationType.toLowerCase().replace("_", " ") + ": " + (displayName != null ? displayName : accountId),
                        0.85, null));
            } else if ("group".equals(type)) {
                String groupName = restriction.get("name");
                if (groupName == null) continue;
                String groupId = entityId("confluence_group", groupName.toLowerCase());
                Map<String, String> groupProps = new LinkedHashMap<>();
                groupProps.put("name", groupName);
                ExtractorUtils.addEntity(entityIndex, new ExtractedEntity(
                        groupId, groupName, GraphConstants.ENTITY_ORGANIZATION, null,
                        "Confluence group with " + relationType.toLowerCase().replace("_", " ") + " access",
                        0.7, groupProps));
                relations.add(new ExtractedRelation(
                        pageEntityId, groupId, relationType,
                        "Page " + relationType.toLowerCase().replace("_", " ") + ": " + groupName,
                        0.85, null));
            }
        }
    }
}
