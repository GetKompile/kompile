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

package ai.kompile.loader.gdocs;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Deterministic graph extractor for Google Docs documents.
 * Extracts entities (documents, folders, persons, comments) and relationships
 * (OWNED_BY, LAST_MODIFIED_BY, IN_FOLDER, COMMENTED_ON, REPLIED_TO)
 * from document metadata — no LLM required.
 */
@Slf4j
@Component
public class GoogleDocsGraphExtractor implements DocumentGraphExtractor {

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("gdocs", "gdocs_comment", "gdocs_revision");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        String sourceType = (String) doc.getMetadata().get(GraphConstants.META_SOURCE_TYPE);
        return "gdocs".equals(sourceType)
                || "gdocs_comment".equals(sourceType)
                || "gdocs_revision".equals(sourceType);
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        String sourceType = (String) metadata.get(GraphConstants.META_SOURCE_TYPE);

        if (!"gdocs".equals(sourceType)
                && !"gdocs_comment".equals(sourceType)
                && !"gdocs_revision".equals(sourceType)) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relationships = new ArrayList<>();

        if ("gdocs_comment".equals(sourceType)) {
            extractCommentGraph(metadata, entities, relationships);
        } else if ("gdocs_revision".equals(sourceType)) {
            extractRevisionGraph(metadata, entities, relationships);
        } else {
            extractDocumentGraph(metadata, doc.getText(), entities, relationships);
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
                ExtractorUtils.addEntity(entityMap, entity);
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

    // ── Document graph extraction ─────────────────────────────────────────

    private void extractDocumentGraph(Map<String, Object> meta, String bodyText,
                                       List<ExtractedEntity> entities,
                                       List<ExtractedRelation> relationships) {
        String documentId = (String) meta.get("gdocs.documentId");
        String title = (String) meta.get("gdocs.title");
        if (documentId == null) return;

        // Document entity
        String docEntityId = entityId("gdocs_document", documentId);
        Map<String, String> docProps = new HashMap<>();
        docProps.put("documentId", documentId);
        if (title != null) docProps.put("title", title);
        if (meta.get("gdocs.webViewLink") != null)
            docProps.put("webViewLink", String.valueOf(meta.get("gdocs.webViewLink")));
        if (meta.get("gdocs.modifiedTime") != null)
            docProps.put("modifiedTime", String.valueOf(meta.get("gdocs.modifiedTime")));
        if (meta.get("gdocs.createdTime") != null)
            docProps.put("createdTime", String.valueOf(meta.get("gdocs.createdTime")));
        if (meta.get("gdocs.version") != null)
            docProps.put("version", String.valueOf(meta.get("gdocs.version")));
        if (meta.get("gdocs.tableCount") != null)
            docProps.put("tableCount", String.valueOf(meta.get("gdocs.tableCount")));
        if (meta.get("gdocs.imageCount") != null)
            docProps.put("imageCount", String.valueOf(meta.get("gdocs.imageCount")));
        if (meta.get("gdocs.listItemCount") != null)
            docProps.put("listItemCount", String.valueOf(meta.get("gdocs.listItemCount")));
        if (meta.get("gdocs.fileName") != null)
            docProps.put(GraphConstants.META_FILE_NAME, String.valueOf(meta.get("gdocs.fileName")));
        if (meta.get("gdocs.crawlTimestamp") != null)
            docProps.put("crawlTimestamp", String.valueOf(meta.get("gdocs.crawlTimestamp")));
        if (meta.get("gdocs.parseMode") != null)
            docProps.put("parseMode", String.valueOf(meta.get("gdocs.parseMode")));

        entities.add(new ExtractedEntity(docEntityId, title != null ? title : "Document " + documentId,
                GraphConstants.ENTITY_GDOCS_DOCUMENT, null, null, 1.0, docProps));

        String displayTitle = title != null ? title : "Document " + documentId;

        // DATE entities from creation/modification timestamps
        String createdTime = meta.get("gdocs.createdTime") != null ? String.valueOf(meta.get("gdocs.createdTime")) : null;
        String modifiedTime = meta.get("gdocs.modifiedTime") != null ? String.valueOf(meta.get("gdocs.modifiedTime")) : null;

        // Build a relation props map with occurredAt set to the best available timestamp.
        // Use createdTime preferentially; fall back to modifiedTime.
        final String docTimestamp = createdTime != null ? createdTime : modifiedTime;
        final Map<String, String> docRelProps = docTimestamp != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, docTimestamp) : null;

        if (createdTime != null) {
            String dateId = entityId("date", createdTime);
            entities.add(new ExtractedEntity(dateId, createdTime, GraphConstants.ENTITY_DATE,
                    null, "Creation date: " + createdTime, 0.85,
                    Map.of("date", createdTime, "dateType", "created")));
            relationships.add(new ExtractedRelation(docEntityId, dateId, GraphConstants.REL_PUBLISHED_ON,
                    displayTitle + " created on " + createdTime, 0.85, docRelProps));
        }
        if (modifiedTime != null) {
            String modDateId = entityId("date", modifiedTime);
            entities.add(new ExtractedEntity(modDateId, modifiedTime, GraphConstants.ENTITY_DATE,
                    null, "Modification date: " + modifiedTime, 0.85,
                    Map.of("date", modifiedTime, "dateType", "modified")));
            relationships.add(new ExtractedRelation(docEntityId, modDateId, GraphConstants.REL_MODIFIED_ON,
                    displayTitle + " modified on " + modifiedTime, 0.85, docRelProps));
        }

        // webViewLink as EXTERNAL_RESOURCE
        String webViewLink = meta.get("gdocs.webViewLink") != null ? String.valueOf(meta.get("gdocs.webViewLink")) : null;
        if (webViewLink != null && !webViewLink.isBlank()) {
            String webLinkId = entityId("url", webViewLink.toLowerCase());
            entities.add(new ExtractedEntity(webLinkId, webViewLink,
                    GraphConstants.ENTITY_EXTERNAL_RESOURCE, null,
                    "Web view link for Google Doc: " + displayTitle, 0.85,
                    Map.of("url", webViewLink)));
            relationships.add(new ExtractedRelation(docEntityId, webLinkId,
                    GraphConstants.REL_HYPERLINKS_TO,
                    displayTitle + " viewable at " + webViewLink, 0.85, docRelProps));
        }

        // TABLE entities from table count — each table produces a placeholder node for
        // graph querying; cell-level detail comes from tableGraph JSON.
        Object tableCountObj = meta.get("gdocs.tableCount");
        if (tableCountObj instanceof Number && ((Number) tableCountObj).intValue() > 0) {
            int tableCount = ((Number) tableCountObj).intValue();
            for (int ti = 0; ti < Math.min(tableCount, 100); ti++) {
                String tableEntityId = entityId("gdocs_table", documentId + ":table:" + ti);
                Map<String, String> tableProps = new LinkedHashMap<>();
                tableProps.put(GraphConstants.PROP_TABLE_INDEX, String.valueOf(ti));
                entities.add(new ExtractedEntity(tableEntityId, "Table " + (ti + 1),
                        GraphConstants.ENTITY_TABLE, null,
                        "Table " + (ti + 1) + " in " + displayTitle, 0.9, tableProps));
                relationships.add(new ExtractedRelation(docEntityId, tableEntityId,
                        GraphConstants.REL_HAS_TABLE,
                        displayTitle + " has Table " + (ti + 1), 0.9, docRelProps));
            }
        }

        // Cell-level table graph from META_TABLE_GRAPH — convert Graph model entities/relations
        // into ExtractedEntity/ExtractedRelation so they appear in the knowledge graph.
        Object tableGraphObj = meta.get(GraphConstants.META_TABLE_GRAPH);
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
                        entities.add(new ExtractedEntity(
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
                        relationships.add(new ExtractedRelation(
                                r.getSource(), r.getTarget(), r.getType(),
                                r.getDescription(),
                                r.getWeight() != null ? r.getWeight() : 0.8, null));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse tableGraph JSON for {}: {}", documentId, e.getMessage());
            }
        }

        // LIST entities from list item count
        Object listCountObj = meta.get("gdocs.listItemCount");
        if (listCountObj instanceof Number && ((Number) listCountObj).intValue() > 0) {
            int listCount = ((Number) listCountObj).intValue();
            String listEntityId = entityId("gdocs_list", documentId + ":lists");
            Map<String, String> listProps = new LinkedHashMap<>();
            listProps.put("listItemCount", String.valueOf(listCount));
            entities.add(new ExtractedEntity(listEntityId, "Lists (" + listCount + " items)",
                    GraphConstants.ENTITY_LIST, null,
                    displayTitle + " has " + listCount + " list items", 0.85, listProps));
            relationships.add(new ExtractedRelation(docEntityId, listEntityId,
                    GraphConstants.REL_HAS_LIST,
                    displayTitle + " has " + listCount + " list items", 0.85, docRelProps));
        }

        // Owner
        String ownerEmail = (String) meta.get("gdocs.ownerEmail");
        String ownerName = (String) meta.get("gdocs.owner");
        if (ownerEmail != null) {
            String ownerId = entityId("person", ownerEmail.toLowerCase());
            Map<String, String> ownerProps = new LinkedHashMap<>();
            ownerProps.put("email", ownerEmail);
            if (ownerName != null) ownerProps.put("displayName", ownerName);
            entities.add(new ExtractedEntity(ownerId, ownerName != null ? ownerName : ownerEmail,
                    GraphConstants.ENTITY_PERSON, null, null, 1.0, ownerProps));
            relationships.add(new ExtractedRelation(docEntityId, ownerId, GraphConstants.REL_OWNED_BY, null, 1.0, docRelProps));
        }

        // Additional co-owners (beyond the primary owner)
        Object additionalOwnersObj = meta.get("gdocs.additionalOwners");
        if (additionalOwnersObj instanceof List<?> ownersList) {
            for (Object item : ownersList) {
                if (!(item instanceof Map<?, ?> ownerMap)) continue;
                String coEmail = ownerMap.get("emailAddress") instanceof String s ? s : null;
                String coName = ownerMap.get("displayName") instanceof String s ? s : null;
                if (coEmail != null) {
                    String coOwnerId = entityId("person", coEmail.toLowerCase());
                    Map<String, String> coProps = new LinkedHashMap<>();
                    coProps.put("email", coEmail);
                    if (coName != null) coProps.put("displayName", coName);
                    entities.add(new ExtractedEntity(coOwnerId, coName != null ? coName : coEmail,
                            GraphConstants.ENTITY_PERSON, null, "Co-owner", 0.9, coProps));
                    relationships.add(new ExtractedRelation(docEntityId, coOwnerId,
                            GraphConstants.REL_OWNED_BY, "Co-owned by " + (coName != null ? coName : coEmail), 0.9, docRelProps));
                }
            }
        }

        // Last modifier
        String modifierEmail = (String) meta.get("gdocs.lastModifiedByEmail");
        String modifierName = (String) meta.get("gdocs.lastModifiedBy");
        if (modifierEmail != null) {
            String modifierId = entityId("person", modifierEmail.toLowerCase());
            Map<String, String> modifierProps = new LinkedHashMap<>();
            modifierProps.put("email", modifierEmail);
            if (modifierName != null) modifierProps.put("displayName", modifierName);
            entities.add(new ExtractedEntity(modifierId, modifierName != null ? modifierName : modifierEmail,
                    GraphConstants.ENTITY_PERSON, null, null, 1.0, modifierProps));
            relationships.add(new ExtractedRelation(docEntityId, modifierId, GraphConstants.REL_LAST_MODIFIED_BY, null, 1.0, docRelProps));
        }

        // Parent folder
        String folderId = (String) meta.get("gdocs.folderId");
        if (folderId != null) {
            String folderEntityId = entityId("gdocs_folder", folderId);
            Map<String, String> folderProps = new HashMap<>();
            folderProps.put("folderId", folderId);
            String folderName = meta.get("gdocs.folderName") instanceof String s ? s : null;
            if (folderName != null) folderProps.put("folderName", folderName);
            String folderLabel = folderName != null ? folderName : "Folder " + folderId;
            entities.add(new ExtractedEntity(folderEntityId, folderLabel,
                    GraphConstants.ENTITY_GDOCS_FOLDER, null, null, 1.0, folderProps));
            relationships.add(new ExtractedRelation(docEntityId, folderEntityId, GraphConstants.REL_IN_FOLDER, null, 1.0, docRelProps));
        }

        // Headings → DOCUMENT_SECTION entities (with SUBSECTION_OF hierarchy)
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headingsList = meta.get("gdocs.headings") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("gdocs.headings") : null;
        if (headingsList != null) {
            Map<Integer, String> depthStack = new LinkedHashMap<>();
            for (Map<String, String> heading : headingsList) {
                String headingText = heading.get("text");
                String headingLevel = heading.get("level");
                if (headingText == null || headingText.isBlank()) continue;
                int depth;
                try { depth = headingLevel != null ? Integer.parseInt(headingLevel) : 1; } catch (NumberFormatException e) { depth = 1; }

                String sectionId = entityId("gdocs_section", documentId + ":" + headingText);
                Map<String, String> sectionProps = new LinkedHashMap<>();
                sectionProps.put("headingText", headingText);
                if (headingLevel != null) sectionProps.put("headingLevel", headingLevel);
                String idx = heading.get("index");
                if (idx != null) sectionProps.put("sectionIndex", idx);
                entities.add(new ExtractedEntity(sectionId, headingText,
                        GraphConstants.ENTITY_DOCUMENT_SECTION, null, "Section: " + headingText, 0.85, sectionProps));

                // H1 → HAS_SECTION to document; deeper → SUBSECTION_OF nearest ancestor
                if (depth <= 1) {
                    relationships.add(new ExtractedRelation(docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                            (title != null ? title : "Document") + " has section: " + headingText, 0.85, docRelProps));
                } else {
                    String parentSectionId = null;
                    for (int d = depth - 1; d >= 1; d--) {
                        if (depthStack.containsKey(d)) {
                            parentSectionId = depthStack.get(d);
                            break;
                        }
                    }
                    if (parentSectionId != null) {
                        relationships.add(new ExtractedRelation(sectionId, parentSectionId, GraphConstants.REL_SUBSECTION_OF,
                                headingText + " is subsection", 0.8, docRelProps));
                    } else {
                        relationships.add(new ExtractedRelation(docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                                (title != null ? title : "Document") + " has section: " + headingText, 0.85, docRelProps));
                    }
                }

                // Update depth stack — clear all deeper entries
                final int currentDepth = depth;
                depthStack.entrySet().removeIf(e -> e.getKey() >= currentDepth);
                depthStack.put(depth, sectionId);
            }
        }

        // Inline images → EMBEDDED_IMAGE entities from body text [Image: objectId] markers
        if (bodyText != null && !bodyText.isBlank()) {
            java.util.regex.Matcher imgMatcher = java.util.regex.Pattern
                    .compile("\\[Image:\\s*([^\\]]+)\\]").matcher(bodyText);
            int imgIdx = 0;
            Set<String> seenObjectIds = new HashSet<>();
            while (imgMatcher.find() && imgIdx < 100) {
                String objectId = imgMatcher.group(1).trim();
                if (objectId.isEmpty() || !seenObjectIds.add(objectId)) continue;
                String imgEntityId = entityId("gdocs_image", documentId + ":" + objectId);
                Map<String, String> imgProps = new LinkedHashMap<>();
                imgProps.put("objectId", objectId);
                imgProps.put("imageIndex", String.valueOf(imgIdx));
                entities.add(new ExtractedEntity(imgEntityId, "Image " + objectId,
                        GraphConstants.ENTITY_EMBEDDED_IMAGE, null,
                        "Inline image in document", 0.85, imgProps));
                relationships.add(new ExtractedRelation(docEntityId, imgEntityId,
                        GraphConstants.REL_HAS_IMAGE,
                        (title != null ? title : "Document") + " has image: " + objectId,
                        0.85, docRelProps));
                imgIdx++;
            }
        }

        // Hyperlinks → HYPERLINK entities
        @SuppressWarnings("unchecked")
        List<Map<String, String>> linksList = meta.get("gdocs.links") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("gdocs.links") : null;
        if (linksList != null) {
            Set<String> seenUrls = new HashSet<>();
            for (Map<String, String> link : linksList) {
                String url = link.get("url");
                if (url == null || url.isBlank() || !seenUrls.add(url)) continue;
                String linkText = link.getOrDefault("text", url);
                String linkId = entityId("gdocs_link", documentId + ":" + url);
                Map<String, String> linkProps = new LinkedHashMap<>();
                linkProps.put("url", url);
                linkProps.put("text", linkText);
                entities.add(new ExtractedEntity(linkId, linkText,
                        GraphConstants.ENTITY_HYPERLINK, null, "Link to: " + url, 0.9, linkProps));
                relationships.add(new ExtractedRelation(docEntityId, linkId, GraphConstants.REL_HAS_HYPERLINK,
                        (title != null ? title : "Document") + " links to: " + url, 0.9, docRelProps));
            }
        } else if (bodyText != null && !bodyText.isBlank()) {
            // Fallback: extract URLs from body text (plaintext_fallback mode)
            java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                    .compile("(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
                    .matcher(bodyText);
            Set<String> seenUrls = new HashSet<>();
            int urlCount = 0;
            while (urlMatcher.find() && urlCount < 100) {
                String url = urlMatcher.group();
                if (!seenUrls.add(url)) continue;
                String linkId = entityId("gdocs_link", documentId + ":" + url);
                Map<String, String> linkProps = new LinkedHashMap<>();
                linkProps.put("url", url);
                entities.add(new ExtractedEntity(linkId, url,
                        GraphConstants.ENTITY_HYPERLINK, null, "Link to: " + url, 0.8, linkProps));
                relationships.add(new ExtractedRelation(docEntityId, linkId, GraphConstants.REL_HAS_HYPERLINK,
                        (title != null ? title : "Document") + " links to: " + url, 0.8, docRelProps));
                urlCount++;
            }
        }

        // Footnotes → FOOTNOTE entities
        @SuppressWarnings("unchecked")
        List<Map<String, String>> footnotesList = meta.get("gdocs.footnotes") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("gdocs.footnotes") : null;
        if (footnotesList != null) {
            for (Map<String, String> fn : footnotesList) {
                String fnId = fn.get("id");
                String fnText = fn.get("text");
                if (fnId == null || fnText == null || fnText.isBlank()) continue;

                String footnoteEntityId = entityId("gdocs_footnote", documentId + ":" + fnId);
                Map<String, String> fnProps = new LinkedHashMap<>();
                fnProps.put("footnoteId", fnId);
                fnProps.put("text", fnText.length() > 500 ? fnText.substring(0, 500) : fnText);

                String fnLabel = fnText.length() > 80 ? fnText.substring(0, 80) + "..." : fnText;
                entities.add(new ExtractedEntity(
                        footnoteEntityId, "Footnote " + fnId + ": " + fnLabel,
                        GraphConstants.ENTITY_FOOTNOTE, null,
                        "Footnote in " + (title != null ? title : "document"), 0.9, fnProps
                ));
                relationships.add(new ExtractedRelation(
                        docEntityId, footnoteEntityId, GraphConstants.REL_HAS_FOOTNOTE,
                        (title != null ? title : "Document") + " has footnote: " + fnLabel,
                        0.9, docRelProps
                ));
            }
        }

        // Suggested edits → SUGGESTED_EDIT entities
        @SuppressWarnings("unchecked")
        List<Map<String, String>> suggestedEdits = meta.get("gdocs.suggestedEdits") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("gdocs.suggestedEdits") : null;
        if (suggestedEdits != null) {
            Set<String> seenSuggestionIds = new LinkedHashSet<>();
            for (Map<String, String> edit : suggestedEdits) {
                String suggestionId = edit.get("suggestionId");
                String editType = edit.get("type");
                String editText = edit.get("text");
                if (suggestionId == null || seenSuggestionIds.contains(suggestionId)) continue;
                seenSuggestionIds.add(suggestionId);
                if (seenSuggestionIds.size() > 100) break;

                String editEntityId = entityId("gdocs_suggestion", documentId + ":" + suggestionId);
                Map<String, String> editProps = new LinkedHashMap<>();
                editProps.put("suggestionId", suggestionId);
                if (editType != null) editProps.put("editType", editType);
                if (editText != null && !editText.isBlank()) {
                    editProps.put("text", editText.length() > 500 ? editText.substring(0, 500) : editText);
                }

                String label = (editType != null ? editType : "edit") + " suggestion: " + suggestionId;
                entities.add(new ExtractedEntity(
                        editEntityId, label,
                        GraphConstants.ENTITY_SUGGESTED_EDIT, null,
                        "Suggested " + (editType != null ? editType : "edit") + " in "
                                + (title != null ? title : "document"), 0.85, editProps
                ));
                relationships.add(new ExtractedRelation(
                        docEntityId, editEntityId, GraphConstants.REL_HAS_SUGGESTED_EDIT,
                        (title != null ? title : "Document") + " has suggested " + (editType != null ? editType : "edit"),
                        0.85, docRelProps
                ));
            }
        }
    }

    // ── Comment graph extraction ──────────────────────────────────────────

    private void extractCommentGraph(Map<String, Object> meta,
                                      List<ExtractedEntity> entities,
                                      List<ExtractedRelation> relationships) {
        String documentId = (String) meta.get("gdocs.documentId");
        String commentId = (String) meta.get("gdocs.commentId");
        String author = (String) meta.get("gdocs.commentAuthor");
        if (documentId == null || commentId == null) return;

        // Comment entity
        String commentEntityId = entityId("gdocs_comment", documentId + "/" + commentId);
        Map<String, String> commentProps = new LinkedHashMap<>();
        commentProps.put("commentId", commentId);
        commentProps.put("documentId", documentId);
        Object resolved = meta.get("gdocs.commentResolved");
        if (resolved != null) commentProps.put("resolved", resolved.toString());
        String commentContent = (String) meta.get("gdocs.commentContent");
        if (commentContent != null) commentProps.put("content", commentContent);
        String quotedText = (String) meta.get("gdocs.commentQuotedText");
        if (quotedText != null) commentProps.put("quotedText", quotedText);
        String commentDate = (String) meta.get("gdocs.commentCreatedTime");
        if (commentDate != null) commentProps.put("createdTime", commentDate);
        entities.add(new ExtractedEntity(commentEntityId, "Comment by " + (author != null ? author : "Unknown"),
                GraphConstants.ENTITY_GDOCS_COMMENT, null, null, 1.0, commentProps));

        // Build relation props with occurredAt set to the comment creation date.
        final Map<String, String> commentRelProps = commentDate != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, commentDate) : null;

        // Date entity for comment creation time
        if (commentDate != null && !commentDate.isBlank()) {
            String dateEntityId = entityId("date", commentDate);
            entities.add(new ExtractedEntity(dateEntityId, commentDate,
                    GraphConstants.ENTITY_DATE, null, "Comment creation date: " + commentDate, 0.85,
                    Map.of("date", commentDate, "dateType", "created")));
            relationships.add(new ExtractedRelation(commentEntityId, dateEntityId,
                    GraphConstants.REL_PUBLISHED_ON, "Comment created on " + commentDate, 0.85, commentRelProps));
        }

        // Document entity reference
        String docEntityId = entityId("gdocs_document", documentId);
        entities.add(new ExtractedEntity(docEntityId, "Document " + documentId,
                GraphConstants.ENTITY_GDOCS_DOCUMENT, null, null, 1.0, Map.of("documentId", documentId)));
        relationships.add(new ExtractedRelation(commentEntityId, docEntityId, GraphConstants.REL_COMMENTED_ON, null, 1.0, commentRelProps));

        // Author as person — prefer email-based ID for cross-source identity resolution
        if (author != null) {
            String authorEmail = (String) meta.get("gdocs.commentAuthorEmail");
            String authorId;
            Map<String, String> authorProps = new LinkedHashMap<>();
            authorProps.put("displayName", author);
            if (authorEmail != null && !authorEmail.isBlank()) {
                authorId = entityId("person", authorEmail.toLowerCase());
                authorProps.put("email", authorEmail);
            } else {
                authorId = entityId("person_name", author.toLowerCase());
            }
            entities.add(new ExtractedEntity(authorId, author, GraphConstants.ENTITY_PERSON, null, null, 1.0, authorProps));
            relationships.add(new ExtractedRelation(commentEntityId, authorId, GraphConstants.REL_AUTHORED_BY, null, 1.0, commentRelProps));
        }

        // Comment replies — create GDOCS_REPLY entities with REPLIED_TO edges
        Object repliesObj = meta.get("gdocs.commentReplies");
        if (repliesObj instanceof List<?> repliesList) {
            for (Object item : repliesList) {
                if (!(item instanceof Map<?, ?> replyMap)) continue;
                String replyId = replyMap.get("replyId") instanceof String s ? s : null;
                String replyAuthor = replyMap.get("author") instanceof String s ? s : "Unknown";
                if (replyId == null) continue;

                String replyContent = replyMap.get("content") instanceof String s ? s : null;

                String replyEntityId = entityId("gdocs_reply", documentId + "/" + commentId + "/" + replyId);
                Map<String, String> replyProps = new HashMap<>();
                replyProps.put("replyId", replyId);
                replyProps.put("commentId", commentId);
                replyProps.put("documentId", documentId);
                replyProps.put("author", replyAuthor);
                if (replyContent != null) replyProps.put("content", replyContent);
                String replyCreatedTime = replyMap.get("createdTime") instanceof String s3 ? s3 : null;
                String replyModifiedTime = replyMap.get("modifiedTime") instanceof String s4 ? s4 : null;
                if (replyCreatedTime != null) replyProps.put("createdTime", replyCreatedTime);
                if (replyModifiedTime != null) replyProps.put("modifiedTime", replyModifiedTime);
                entities.add(new ExtractedEntity(replyEntityId, "Reply by " + replyAuthor,
                        GraphConstants.ENTITY_GDOCS_REPLY, null, null, 0.9, replyProps));

                // Build reply relation props with occurredAt from reply creation time
                final Map<String, String> replyRelProps = replyCreatedTime != null
                        ? Map.of(GraphConstants.PROP_OCCURRED_AT, replyCreatedTime) : null;

                // DATE entity from reply creation time
                if (replyCreatedTime != null) {
                    String dateStr = replyCreatedTime.contains("T")
                            ? replyCreatedTime.substring(0, replyCreatedTime.indexOf('T')) : replyCreatedTime;
                    String replyDateId = entityId("date", dateStr);
                    entities.add(new ExtractedEntity(replyDateId, dateStr, GraphConstants.ENTITY_DATE,
                            null, "Date: " + dateStr, 0.85,
                            Map.of("date", dateStr, "dateType", "replyCreated")));
                    relationships.add(new ExtractedRelation(replyEntityId, replyDateId,
                            GraphConstants.REL_PUBLISHED_ON,
                            "Reply created on " + dateStr, 0.85, replyRelProps));
                }

                // DATE entity from reply modification time (MODIFIED_ON)
                if (replyModifiedTime != null && !replyModifiedTime.equals(replyCreatedTime)) {
                    String modDateStr = replyModifiedTime.contains("T")
                            ? replyModifiedTime.substring(0, replyModifiedTime.indexOf('T')) : replyModifiedTime;
                    String replyModDateId = entityId("date", modDateStr);
                    entities.add(new ExtractedEntity(replyModDateId, modDateStr, GraphConstants.ENTITY_DATE,
                            null, "Date: " + modDateStr, 0.85,
                            Map.of("date", modDateStr, "dateType", "replyModified")));
                    relationships.add(new ExtractedRelation(replyEntityId, replyModDateId,
                            GraphConstants.REL_MODIFIED_ON,
                            "Reply modified on " + modDateStr, 0.85, replyRelProps));
                }

                // Reply → parent comment
                relationships.add(new ExtractedRelation(replyEntityId, commentEntityId, GraphConstants.REL_REPLIED_TO,
                        "Reply to comment", 1.0, replyRelProps));

                // Reply author as person — prefer email-based ID for cross-source identity resolution
                String replyAuthorEmail = replyMap.get("authorEmail") instanceof String s2 ? s2 : null;
                String replyAuthorId;
                Map<String, String> replyAuthorProps = new LinkedHashMap<>();
                replyAuthorProps.put("displayName", replyAuthor);
                if (replyAuthorEmail != null && !replyAuthorEmail.isBlank()) {
                    replyAuthorId = entityId("person", replyAuthorEmail.toLowerCase());
                    replyAuthorProps.put("email", replyAuthorEmail);
                } else {
                    replyAuthorId = entityId("person_name", replyAuthor.toLowerCase());
                }
                entities.add(new ExtractedEntity(replyAuthorId, replyAuthor, GraphConstants.ENTITY_PERSON,
                        null, null, 1.0, replyAuthorProps));
                relationships.add(new ExtractedRelation(replyEntityId, replyAuthorId, GraphConstants.REL_AUTHORED_BY,
                        null, 1.0, replyRelProps));
            }
        }
    }

    // ── Revision graph extraction ─────────────────────────────────────────

    private void extractRevisionGraph(Map<String, Object> meta,
                                       List<ExtractedEntity> entities,
                                       List<ExtractedRelation> relationships) {
        String documentId = (String) meta.get("gdocs.documentId");
        String revisionId = (String) meta.get("gdocs.revisionId");
        String modifier = (String) meta.get("gdocs.revisionModifier");
        if (documentId == null || revisionId == null) return;

        // Revision entity
        String revEntityId = entityId("gdocs_revision", documentId + "/" + revisionId);
        Map<String, String> revProps = new HashMap<>();
        revProps.put("revisionId", revisionId);
        revProps.put("documentId", documentId);
        if (meta.get("gdocs.revisionModifiedTime") != null) {
            revProps.put("modifiedTime", String.valueOf(meta.get("gdocs.revisionModifiedTime")));
        }
        entities.add(new ExtractedEntity(revEntityId, "Revision " + revisionId,
                GraphConstants.ENTITY_GDOCS_REVISION, null, null, 1.0, revProps));

        // Date entity for revision modification time
        String revModTime = meta.get("gdocs.revisionModifiedTime") != null
                ? String.valueOf(meta.get("gdocs.revisionModifiedTime")) : null;

        // Build relation props with occurredAt set to the revision modification time.
        final Map<String, String> revRelProps = revModTime != null
                ? Map.of(GraphConstants.PROP_OCCURRED_AT, revModTime) : null;

        if (revModTime != null && !revModTime.isBlank()) {
            String dateEntityId = entityId("date", revModTime);
            entities.add(new ExtractedEntity(dateEntityId, revModTime,
                    GraphConstants.ENTITY_DATE, null, "Revision date: " + revModTime, 0.85,
                    Map.of("date", revModTime, "dateType", "modified")));
            relationships.add(new ExtractedRelation(revEntityId, dateEntityId,
                    GraphConstants.REL_MODIFIED_ON, "Revision modified on " + revModTime, 0.85, revRelProps));
        }

        // Document entity reference
        String docEntityId = entityId("gdocs_document", documentId);
        entities.add(new ExtractedEntity(docEntityId, "Document " + documentId,
                GraphConstants.ENTITY_GDOCS_DOCUMENT, null, null, 1.0, Map.of("documentId", documentId)));
        relationships.add(new ExtractedRelation(revEntityId, docEntityId, GraphConstants.REL_REVISION_OF, null, 1.0, revRelProps));

        // Modifier as person — prefer email-based ID for cross-source identity resolution
        if (modifier != null) {
            String modifierEmail = (String) meta.get("gdocs.revisionModifierEmail");
            String modifierId;
            Map<String, String> modProps = new LinkedHashMap<>();
            modProps.put("displayName", modifier);
            if (modifierEmail != null && !modifierEmail.isBlank()) {
                modifierId = entityId("person", modifierEmail.toLowerCase());
                modProps.put("email", modifierEmail);
            } else {
                modifierId = entityId("person_name", modifier.toLowerCase());
            }
            entities.add(new ExtractedEntity(modifierId, modifier, GraphConstants.ENTITY_PERSON, null, null, 1.0, modProps));
            relationships.add(new ExtractedRelation(revEntityId, modifierId, GraphConstants.REL_MODIFIED_BY, null, 1.0, revRelProps));
        }

        // Chain revisions: SUCCESSOR_OF relationship from this revision to previous
        String previousRevisionId = (String) meta.get("gdocs.previousRevisionId");
        if (previousRevisionId != null) {
            String prevRevEntityId = entityId("gdocs_revision", documentId + "/" + previousRevisionId);
            entities.add(new ExtractedEntity(prevRevEntityId, "Revision " + previousRevisionId,
                    GraphConstants.ENTITY_GDOCS_REVISION, null, null, 1.0,
                    Map.of("revisionId", previousRevisionId, "documentId", documentId)));
            relationships.add(new ExtractedRelation(revEntityId, prevRevEntityId,
                    GraphConstants.REL_SUCCESSOR_OF, "Revision " + revisionId + " succeeds " + previousRevisionId,
                    1.0, revRelProps));
        }
    }

    // ── Utility methods ───────────────────────────────────────────────────

    static String entityId(String prefix, String key) {
        return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
