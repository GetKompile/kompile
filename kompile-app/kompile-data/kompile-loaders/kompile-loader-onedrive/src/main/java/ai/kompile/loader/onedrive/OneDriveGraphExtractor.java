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

package ai.kompile.loader.onedrive;

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

/**
 * Deterministic, rule-based graph extractor for OneDrive documents.
 *
 * <p>Entity types: ONEDRIVE_FILE, PERSON, FOLDER</p>
 * <p>Relationship types: CREATED_BY, LAST_MODIFIED_BY, CONTAINED_IN</p>
 */
@Component
public class OneDriveGraphExtractor implements DocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(OneDriveGraphExtractor.class);

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("onedrive");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        return "ONEDRIVE".equals(str(meta.get(GraphConstants.META_SOURCE_TYPE)))
                || meta.get("onedrive_item_id") != null;
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();

        String itemId = str(meta.get("onedrive_item_id"));
        String name = str(meta.get("onedrive_name"));
        String source = str(meta.get(GraphConstants.META_SOURCE));
        if (itemId == null && source == null) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        String displayName = name != null ? name : itemId;
        String fileEntityId = entityId("onedrive_file:" + (itemId != null ? itemId : source));

        // Build file entity properties
        Map<String, String> fileProps = new LinkedHashMap<>();
        if (itemId != null) fileProps.put("itemId", itemId);
        if (name != null) fileProps.put(GraphConstants.META_FILE_NAME, name);
        String mimeType = str(meta.get("onedrive_mime_type"));
        if (mimeType != null) fileProps.put("mimeType", mimeType);
        Object sizeBytes = meta.get("onedrive_size_bytes");
        if (sizeBytes != null) fileProps.put("sizeBytes", sizeBytes.toString());
        String lastModified = str(meta.get("onedrive_last_modified"));
        if (lastModified != null) fileProps.put(GraphConstants.META_LAST_MODIFIED, lastModified);
        String createdDateTime = str(meta.get("onedrive.createdDateTime"));
        if (createdDateTime != null) fileProps.put("createdDateTime", createdDateTime);
        String webUrl = str(meta.get("onedrive_web_url"));
        if (webUrl != null) fileProps.put("webUrl", webUrl);
        String driveId = str(meta.get("onedrive_drive_id"));
        if (driveId != null) fileProps.put("driveId", driveId);
        String sha1Hash = str(meta.get("onedrive.sha1Hash"));
        if (sha1Hash != null) fileProps.put("sha1Hash", sha1Hash);
        String sha256Hash = str(meta.get("onedrive.sha256Hash"));
        if (sha256Hash != null) fileProps.put("sha256Hash", sha256Hash);
        String quickXorHash = str(meta.get("onedrive.quickXorHash"));
        if (quickXorHash != null) fileProps.put("quickXorHash", quickXorHash);
        String sourcePath = str(meta.get(GraphConstants.META_SOURCE_PATH));
        if (sourcePath != null) fileProps.put("sourcePath", sourcePath);
        String loader = str(meta.get(GraphConstants.META_LOADER));
        if (loader != null) fileProps.put("loader", loader);
        String documentType = str(meta.get(GraphConstants.META_DOCUMENT_TYPE));
        if (documentType != null) fileProps.put("documentType", documentType);
        String sourceId = str(meta.get(GraphConstants.META_SOURCE_ID));
        if (sourceId != null) fileProps.put("sourceId", sourceId);
        String collectionName = str(meta.get("collection_name"));
        if (collectionName != null) fileProps.put("collectionName", collectionName);

        String resolvedType = resolveEntityType(mimeType, name);
        ExtractedEntity fileEntity = new ExtractedEntity(
                fileEntityId, displayName, resolvedType,
                null, "OneDrive file: " + displayName, 1.0, fileProps
        );
        addEntity(entityIndex, fileEntity);

        // CREATED_BY relationship
        String createdBy = str(meta.get("onedrive.createdBy"));
        String createdByEmail = str(meta.get("onedrive.createdByEmail"));
        if (createdBy != null || createdByEmail != null) {
            String personKey = createdByEmail != null ? createdByEmail : createdBy;
            String personId = entityId("person:" + personKey.toLowerCase());
            Map<String, String> personProps = new LinkedHashMap<>();
            if (createdBy != null) personProps.put("displayName", createdBy);
            if (createdByEmail != null) personProps.put("email", createdByEmail);

            ExtractedEntity creator = new ExtractedEntity(
                    personId,
                    createdBy != null ? createdBy : createdByEmail,
                    GraphConstants.ENTITY_PERSON, null, null, 1.0, personProps
            );
            addEntity(entityIndex, creator);
            relations.add(new ExtractedRelation(
                    fileEntityId, personId, GraphConstants.REL_CREATED_BY,
                    displayName + " created by " + creator.name(),
                    1.0, Map.of("provenance", GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }

        // LAST_MODIFIED_BY relationship
        String modifiedBy = str(meta.get("onedrive.lastModifiedBy"));
        String modifiedByEmail = str(meta.get("onedrive.lastModifiedByEmail"));
        if (modifiedBy != null || modifiedByEmail != null) {
            String personKey = modifiedByEmail != null ? modifiedByEmail : modifiedBy;
            String personId = entityId("person:" + personKey.toLowerCase());
            Map<String, String> personProps = new LinkedHashMap<>();
            if (modifiedBy != null) personProps.put("displayName", modifiedBy);
            if (modifiedByEmail != null) personProps.put("email", modifiedByEmail);

            ExtractedEntity modifier = new ExtractedEntity(
                    personId,
                    modifiedBy != null ? modifiedBy : modifiedByEmail,
                    GraphConstants.ENTITY_PERSON, null, null, 1.0, personProps
            );
            addEntity(entityIndex, modifier);
            relations.add(new ExtractedRelation(
                    fileEntityId, personId, GraphConstants.REL_LAST_MODIFIED_BY,
                    displayName + " last modified by " + modifier.name(),
                    1.0, Map.of("provenance", GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }

        // IN_DRIVE relationship for drive
        if (driveId != null) {
            String driveEntityId = entityId("onedrive_drive:" + driveId);
            ExtractedEntity driveEntity = new ExtractedEntity(
                    driveEntityId, "Drive " + driveId, GraphConstants.ENTITY_ONEDRIVE_DRIVE,
                    null, "OneDrive drive: " + driveId, 0.9,
                    Map.of("driveId", driveId)
            );
            addEntity(entityIndex, driveEntity);
            relations.add(new ExtractedRelation(
                    fileEntityId, driveEntityId, GraphConstants.REL_IN_DRIVE,
                    displayName + " is in drive " + driveId,
                    1.0, Map.of("provenance", GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }

        // CONTAINED_IN relationship for parent folder — build full folder hierarchy
        String parentPath = str(meta.get("onedrive.parentPath"));
        if (parentPath != null && !parentPath.isBlank()) {
            // Strip leading "/drives/.../root:" prefix if present
            String cleanPath = parentPath;
            int rootIdx = cleanPath.indexOf("root:");
            if (rootIdx >= 0) {
                cleanPath = cleanPath.substring(rootIdx + 5);
            }
            if (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);

            // Split path into segments and build chain: file → leaf → ... → root
            String[] segments = cleanPath.split("/");
            String prevEntityId = fileEntityId;
            String prevLabel = displayName;
            StringBuilder pathSoFar = new StringBuilder();
            for (int fi = segments.length - 1; fi >= 0; fi--) {
                String seg = segments[fi].trim();
                if (seg.isEmpty()) continue;
                pathSoFar.setLength(0);
                for (int j = 0; j <= fi; j++) {
                    if (pathSoFar.length() > 0) pathSoFar.append("/");
                    pathSoFar.append(segments[j]);
                }
                String folderFullPath = pathSoFar.toString();
                String folderId = entityId("onedrive_folder:" + folderFullPath.toLowerCase());
                ExtractedEntity folder = new ExtractedEntity(
                        folderId, seg, GraphConstants.ENTITY_ONEDRIVE_FOLDER,
                        null, "OneDrive folder: " + seg, 0.9,
                        Map.of("path", folderFullPath)
                );
                addEntity(entityIndex, folder);
                relations.add(new ExtractedRelation(
                        prevEntityId, folderId, GraphConstants.REL_CONTAINED_IN,
                        prevLabel + " is in folder " + seg,
                        1.0, Map.of("provenance", GraphConstants.PROVENANCE_EXTRACTED)
                ));
                prevEntityId = folderId;
                prevLabel = seg;
            }
        }

        // DATE entities from creation/modification timestamps
        if (createdDateTime != null) {
            String dateId = entityId("date:" + createdDateTime);
            addEntity(entityIndex, new ExtractedEntity(dateId, createdDateTime, GraphConstants.ENTITY_DATE,
                    null, "Creation date: " + createdDateTime, 0.85,
                    Map.of("date", createdDateTime, "dateType", "created")));
            relations.add(new ExtractedRelation(fileEntityId, dateId, GraphConstants.REL_PUBLISHED_ON,
                    displayName + " created on " + createdDateTime, 0.85, null));
        }
        if (lastModified != null) {
            String modDateId = entityId("date:" + lastModified);
            addEntity(entityIndex, new ExtractedEntity(modDateId, lastModified, GraphConstants.ENTITY_DATE,
                    null, "Modification date: " + lastModified, 0.85,
                    Map.of("date", lastModified, "dateType", "modified")));
            relations.add(new ExtractedRelation(fileEntityId, modDateId, GraphConstants.REL_MODIFIED_ON,
                    displayName + " modified on " + lastModified, 0.85, null));
        }

        // webUrl as EXTERNAL_RESOURCE
        if (webUrl != null && !webUrl.isBlank()) {
            String webUrlId = entityId("url:" + webUrl.toLowerCase());
            addEntity(entityIndex, new ExtractedEntity(webUrlId, webUrl,
                    GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                    null, "Web URL for OneDrive file: " + displayName, 0.85,
                    Map.of("url", webUrl)));
            relations.add(new ExtractedRelation(fileEntityId, webUrlId,
                    GraphConstants.REL_HYPERLINKS_TO,
                    displayName + " viewable at " + webUrl, 0.85, null));
        }

        // -- Shared link (from onedrive.sharedLink or onedrive_web_url with sharing scope) --
        String sharedLink = str(meta.get("onedrive.sharedLink"));
        String sharedScope = str(meta.get("onedrive.sharedScope"));
        if (sharedLink != null) {
            String linkEntityId = entityId("onedrive_shared_link:" + sharedLink);
            Map<String, String> linkProps = new LinkedHashMap<>();
            linkProps.put("url", sharedLink);
            if (sharedScope != null) linkProps.put("scope", sharedScope);
            ExtractedEntity linkEntity = new ExtractedEntity(
                    linkEntityId, "Shared: " + displayName, GraphConstants.ENTITY_ONEDRIVE_SHARED_LINK,
                    null, "Shared link for " + displayName, 0.85, linkProps
            );
            addEntity(entityIndex, linkEntity);
            relations.add(new ExtractedRelation(
                    fileEntityId, linkEntityId, GraphConstants.REL_HAS_SHARED_LINK,
                    displayName + " has shared link",
                    0.85, Map.of("provenance", GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }

        // -- SHARED_BY from onedrive.sharedOwner (the person who shared the file) --
        String sharedOwner = str(meta.get("onedrive.sharedOwner"));
        String sharedOwnerEmail = str(meta.get("onedrive.sharedOwnerEmail"));
        if (sharedOwner != null || sharedOwnerEmail != null) {
            // Use email for entity ID when available so person dedup works across creator/modifier/sharer
            String ownerKey = sharedOwnerEmail != null ? sharedOwnerEmail : sharedOwner;
            String ownerPersonId = entityId("person:" + ownerKey.toLowerCase());
            Map<String, String> ownerProps = new LinkedHashMap<>();
            if (sharedOwner != null) ownerProps.put("displayName", sharedOwner);
            if (sharedOwnerEmail != null) ownerProps.put("email", sharedOwnerEmail);
            ExtractedEntity ownerEntity = new ExtractedEntity(
                    ownerPersonId,
                    sharedOwner != null ? sharedOwner : sharedOwnerEmail,
                    GraphConstants.ENTITY_PERSON,
                    null, "OneDrive shared owner: " + (sharedOwner != null ? sharedOwner : sharedOwnerEmail),
                    0.85, ownerProps
            );
            addEntity(entityIndex, ownerEntity);
            relations.add(new ExtractedRelation(
                    fileEntityId, ownerPersonId, GraphConstants.REL_SHARED_BY,
                    displayName + " shared by " + (sharedOwner != null ? sharedOwner : sharedOwnerEmail),
                    0.85, Map.of("provenance", GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }

        // ── Cell-level table graph from META_TABLE_GRAPH ──
        // When OneDrive files are processed through MicrosoftOfficeLoaderImpl,
        // TableCellGraphBuilder produces cell-level entities. Import them here.
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
                log.warn("Failed to parse tableGraph JSON for OneDrive document: {}", e.getMessage());
            }
        }

        // ── URL extraction from body text ─────────────────────────────
        String bodyText = doc.getText();
        if (bodyText != null && !bodyText.isEmpty()) {
            Set<String> seenUrls = new HashSet<>();
            Matcher urlMatcher = URL_PATTERN.matcher(bodyText);
            int urlCount = 0;
            while (urlMatcher.find() && urlCount < 100) {
                String url = urlMatcher.group();
                if (seenUrls.add(url.toLowerCase())) {
                    String urlId = entityId("url:" + url.toLowerCase());
                    if (!entityIndex.containsKey(urlId)) {
                        addEntity(entityIndex, new ExtractedEntity(
                                urlId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "URL from OneDrive document", 0.8,
                                Map.of("url", url)));
                        relations.add(new ExtractedRelation(
                                fileEntityId, urlId, GraphConstants.REL_HYPERLINKS_TO,
                                displayName + " links to " + url, 0.8, null));
                        urlCount++;
                    }
                }
            }
        }

        entities.addAll(entityIndex.values());

        ExtractionMetadata extractionMeta = new ExtractionMetadata(
                source, source, "onedrive-graph-extractor", null, null, null
        );

        return ExtractionResult.of(entities, relations, extractionMeta);
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        return ExtractorUtils.extractBatch(this, docs, "onedrive-graph-extractor");
    }

    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String str(Object obj) {
        if (obj == null) return null;
        String s = obj.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Resolves a more specific entity type from the OneDrive mimeType.
     */
    private String resolveEntityType(String mimeType, String fileName) {
        if (mimeType != null) {
            String lower = mimeType.toLowerCase();
            // Microsoft Office types
            if (lower.contains("spreadsheet") || lower.contains("excel") || lower.contains("ms-excel")) {
                return GraphConstants.ENTITY_ONEDRIVE_SPREADSHEET;
            }
            if (lower.contains("presentation") || lower.contains("powerpoint") || lower.contains("ms-powerpoint")) {
                return GraphConstants.ENTITY_ONEDRIVE_PRESENTATION;
            }
            if (lower.contains("wordprocessing") || lower.contains("msword") || lower.contains("ms-word")) {
                return GraphConstants.ENTITY_ONEDRIVE_DOCUMENT;
            }
            if (lower.contains("pdf")) {
                return GraphConstants.ENTITY_ONEDRIVE_PDF;
            }
            if (lower.startsWith("image/")) {
                return GraphConstants.ENTITY_ONEDRIVE_IMAGE;
            }
            if (lower.startsWith("video/")) {
                return GraphConstants.ENTITY_ONEDRIVE_VIDEO;
            }
            if (lower.startsWith("audio/")) {
                return GraphConstants.ENTITY_ONEDRIVE_AUDIO;
            }
            if (lower.startsWith("text/")) {
                return GraphConstants.ENTITY_ONEDRIVE_TEXT;
            }
        }
        // Fallback: check file extension
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();
            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") || lowerName.endsWith(".csv")) {
                return GraphConstants.ENTITY_ONEDRIVE_SPREADSHEET;
            }
            if (lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt")) {
                return GraphConstants.ENTITY_ONEDRIVE_PRESENTATION;
            }
            if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc") || lowerName.endsWith(".rtf")) {
                return GraphConstants.ENTITY_ONEDRIVE_DOCUMENT;
            }
            if (lowerName.endsWith(".pdf")) {
                return GraphConstants.ENTITY_ONEDRIVE_PDF;
            }
        }
        return GraphConstants.ENTITY_ONEDRIVE_FILE;
    }

}
