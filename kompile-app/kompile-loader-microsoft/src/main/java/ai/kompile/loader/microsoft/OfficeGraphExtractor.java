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

package ai.kompile.loader.microsoft;

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
 * Deterministic graph extractor for Microsoft Office documents (Word, Excel, PowerPoint).
 * Extracts author, title, keywords, subject, and producer metadata into knowledge graph
 * entities and relationships.
 *
 * <p>Relies on metadata set by TikaLoaderImpl (author, title, keywords, subject, etc.)
 * and MicrosoftOfficeLoaderImpl (documentType, sheetName, slideTitle, etc.).</p>
 *
 * <p>Entity types: OFFICE_DOCUMENT, PERSON, TOPIC, ORGANIZATION, SPREADSHEET_SHEET,
 * PRESENTATION_SLIDE</p>
 *
 * <p>Relationship types: AUTHORED_BY, HAS_TOPIC, PRODUCED_BY, HAS_SHEET, HAS_SLIDE</p>
 */
@Component
public class OfficeGraphExtractor implements DocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(OfficeGraphExtractor.class);

    private static final Pattern URL_PATTERN =
            Pattern.compile("(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private static final Set<String> OFFICE_EXTENSIONS = Set.of(
            ".doc", ".docx", ".xls", ".xlsx", ".xlsm",
            ".ppt", ".pptx", ".ods", ".odt", ".odp",
            ".mdb", ".accdb", ".msg"
    );

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("word", "excel", "powerpoint", "spreadsheet", "presentation", "access", "database");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();

        // Don't claim PDFs — PdfGraphExtractor handles those
        String docType = str(meta.get(META_DOCUMENT_TYPE));
        if (docType != null && docType.toLowerCase().contains("pdf")) return false;

        // Don't claim Google Drive / Google Workspace documents — GWorkspaceGraphExtractor handles those
        String sourceType = str(meta.get(META_SOURCE_TYPE));
        if ("GDRIVE".equals(sourceType) || meta.get("gworkspace.service") != null) return false;

        // Match on content_type=chart, image, table, or slide (Excel chart/image, Word table, PowerPoint slide documents)
        String contentType = str(meta.get(META_CONTENT_TYPE));
        if ("chart".equals(contentType) || "image".equals(contentType)
                || "table".equals(contentType) || "slide".equals(contentType)) return true;

        // Match on documentType
        if (docType != null) {
            String lower = docType.toLowerCase();
            if (lower.contains("word") || lower.contains("spreadsheet")
                    || lower.contains("excel") || lower.contains("presentation")
                    || lower.contains("powerpoint") || lower.contains("odt")
                    || lower.contains("ods") || lower.contains("odp")
                    || lower.contains("access") || lower.contains("database")
                    || lower.contains("msg") || lower.contains("outlook")
                    || lower.contains("docx") || lower.contains("xlsx")
                    || lower.contains("pptx")) {
                return true;
            }
        }

        // Match on file extension
        String fileName = str(meta.get(META_FILE_NAME));
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            for (String ext : OFFICE_EXTENSIONS) {
                if (lower.endsWith(ext)) return true;
            }
        }

        return false;
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        // Skip error documents — they have no useful metadata and would produce
        // disconnected root entities with no relations
        if (Boolean.TRUE.equals(meta.get("parseError"))) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();

        // ── OFFICE_DOCUMENT entity ──────────────────────────────────────
        String fileName = str(meta.get(META_FILE_NAME));
        String title = str(meta.get(META_TITLE));
        String source = str(meta.get(META_SOURCE));
        String docType = str(meta.get(META_DOCUMENT_TYPE));
        String displayTitle = title != null ? title : (fileName != null ? fileName : "Untitled Document");

        String docEntityId = entityId("office:" + (source != null ? source : displayTitle));
        Map<String, String> docProps = new LinkedHashMap<>();
        if (fileName != null) docProps.put(META_FILE_NAME, fileName);
        if (title != null) docProps.put(META_TITLE, title);
        if (docType != null) docProps.put(META_DOCUMENT_TYPE, docType);
        putIfPresent(docProps, META_SUBJECT, meta, META_SUBJECT);
        putIfPresent(docProps, META_DESCRIPTION, meta, META_DESCRIPTION);
        putValueIfPresent(docProps, META_FILE_SIZE, meta, META_FILE_SIZE);
        putValueIfPresent(docProps, META_PAGE_COUNT, meta, META_PAGE_COUNT);
        putValueIfPresent(docProps, META_CREATION_DATE, meta, META_CREATION_DATE);
        putValueIfPresent(docProps, META_MODIFICATION_DATE, meta, META_MODIFICATION_DATE);
        putIfPresent(docProps, META_LANGUAGE, meta, META_LANGUAGE);
        // Capture lastModified if modificationDate is absent
        if (docProps.get(META_MODIFICATION_DATE) == null) {
            putIfPresent(docProps, META_LAST_MODIFIED, meta, META_LAST_MODIFIED);
        }

        // Custom document properties — store as "custom.<key>" on the document entity
        Object customPropsObj = meta.get("office.customProperties");
        if (customPropsObj instanceof Map<?, ?> customMap) {
            for (Map.Entry<?, ?> entry : customMap.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String val) {
                    docProps.put("custom." + key, val);
                }
            }
        }

        String entityType = resolveEntityType(docType, fileName);
        ExtractedEntity docEntity = new ExtractedEntity(
                docEntityId, displayTitle, entityType,
                null, entityType + ": " + displayTitle, 1.0, docProps
        );
        addEntity(entityIndex, docEntity);

        // ── DATE entities from creation/modification dates ────────────────
        String creationDate = str(meta.get(META_CREATION_DATE));
        if (creationDate != null) {
            String dateId = entityId("date:" + creationDate);
            addEntity(entityIndex, new ExtractedEntity(
                    dateId, creationDate, ENTITY_DATE,
                    null, "Creation date: " + creationDate, 0.85,
                    Map.of("date", creationDate, "dateType", "created")));
            relations.add(new ExtractedRelation(
                    docEntityId, dateId, REL_PUBLISHED_ON,
                    displayTitle + " created on " + creationDate,
                    0.85, null));
        }
        String modificationDate = str(meta.get(META_MODIFICATION_DATE));
        if (modificationDate == null) modificationDate = str(meta.get(META_LAST_MODIFIED));

        // Compute occurredAt for all relations: prefer modificationDate > creationDate > lastModified
        String occurredAt = modificationDate != null ? modificationDate : creationDate;

        if (modificationDate != null) {
            String modDateId = entityId("date:" + modificationDate);
            if (!entityIndex.containsKey(modDateId)) {
                addEntity(entityIndex, new ExtractedEntity(
                        modDateId, modificationDate, ENTITY_DATE,
                        null, "Modification date: " + modificationDate, 0.85,
                        Map.of("date", modificationDate, "dateType", "modified")));
            }
            relations.add(new ExtractedRelation(
                    docEntityId, modDateId, REL_MODIFIED_ON,
                    displayTitle + " modified on " + modificationDate,
                    0.85, null));
        }

        // ── PERSON from author ──────────────────────────────────────────
        String author = str(meta.get(META_AUTHOR));
        if (author != null) {
            extractAuthors(author, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── PERSON from lastModifiedBy ─────────────────────────────────
        String lastModifiedBy = str(meta.get("lastModifiedBy"));
        if (lastModifiedBy != null && !lastModifiedBy.equals(author)) {
            String modifierId = entityId("person:" + lastModifiedBy.toLowerCase());
            addEntity(entityIndex, new ExtractedEntity(
                    modifierId, lastModifiedBy, ENTITY_PERSON,
                    null, "Last modifier: " + lastModifiedBy, 0.85,
                    Map.of(PROP_SOURCE_FIELD, "lastModifiedBy")));
            relations.add(new ExtractedRelation(
                    docEntityId, modifierId, REL_LAST_MODIFIED_BY,
                    displayTitle + " last modified by " + lastModifiedBy,
                    0.85, null));
        }

        // ── ORGANIZATION from producer/applicationName ──────────────────
        String producer = str(meta.get(META_PRODUCER));
        if (producer == null) producer = str(meta.get(META_APPLICATION_NAME));
        if (producer != null) {
            extractProducer(producer, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── ORGANIZATION from company ──────────────────────────────────
        String company = str(meta.get("company"));
        if (company != null && !company.equals(producer)) {
            String companyId = entityId("org:" + company.toLowerCase());
            if (!entityIndex.containsKey(companyId)) {
                Map<String, String> companyProps = new LinkedHashMap<>();
                companyProps.put("name", company);
                companyProps.put(PROP_SOURCE_FIELD, "company");
                addEntity(entityIndex, new ExtractedEntity(
                        companyId, company, ENTITY_ORGANIZATION,
                        null, "Document company: " + company, 0.85, companyProps));
            }
            relations.add(new ExtractedRelation(
                    docEntityId, companyId, REL_PRODUCED_BY,
                    displayTitle + " produced by " + company,
                    0.85, null));
        }

        // ── TOPICs from keywords ────────────────────────────────────────
        String keywords = str(meta.get(META_KEYWORDS));
        if (keywords != null) {
            extractTopics(keywords, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── SPREADSHEET_SHEET from sheetName ────────────────────────────
        String sheetName = str(meta.get(META_SHEET_NAME));
        if (sheetName != null) {
            String sheetId = entityId("sheet:" + source + ":" + sheetName);
            Map<String, String> sheetProps = new LinkedHashMap<>();
            sheetProps.put(META_SHEET_NAME, sheetName);
            putValueIfPresent(sheetProps, META_SHEET_INDEX, meta, META_SHEET_INDEX);
            putValueIfPresent(sheetProps, PROP_ROW_COUNT, meta, META_TABLE_ROW_COUNT);
            putValueIfPresent(sheetProps, PROP_COLUMN_COUNT, meta, META_TABLE_COLUMN_COUNT);
            putIfPresent(sheetProps, PROP_HEADERS, meta, META_TABLE_HEADERS);
            String sheetSummary = str(meta.get("table_summary"));
            if (sheetSummary != null) sheetProps.put("summary", sheetSummary);

            ExtractedEntity sheetEntity = new ExtractedEntity(
                    sheetId, sheetName, ENTITY_SPREADSHEET_SHEET,
                    null, "Spreadsheet sheet: " + sheetName, 0.95, sheetProps
            );
            addEntity(entityIndex, sheetEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, sheetId, REL_HAS_SHEET,
                    displayTitle + " has sheet: " + sheetName,
                    0.95, null
            ));
        }

        // ── PRESENTATION_SLIDE from slideTitle ──────────────────────────
        String slideTitle = str(meta.get(META_SLIDE_TITLE));
        Object slideNum = meta.get(META_SLIDE_NUMBER);
        if (slideTitle != null || slideNum != null) {
            String slideName = slideTitle != null ? slideTitle : "Slide " + slideNum;
            String slideId = entityId("slide:" + source + ":" + slideName);
            Map<String, String> slideProps = new LinkedHashMap<>();
            if (slideTitle != null) slideProps.put(META_SLIDE_TITLE, slideTitle);
            if (slideNum != null) slideProps.put(META_SLIDE_NUMBER, String.valueOf(slideNum));
            putIfPresent(slideProps, META_SPEAKER_NOTES, meta, META_SPEAKER_NOTES);
            putIfPresent(slideProps, META_SLIDE_LAYOUT, meta, META_SLIDE_LAYOUT);
            if (Boolean.TRUE.equals(meta.get(META_PPTX_IS_HIDDEN))) {
                slideProps.put(PROP_IS_HIDDEN, "true");
            }

            ExtractedEntity slideEntity = new ExtractedEntity(
                    slideId, slideName, ENTITY_PRESENTATION_SLIDE,
                    null, "Presentation slide: " + slideName, 0.95, slideProps
            );
            addEntity(entityIndex, slideEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, slideId, REL_HAS_SLIDE,
                    displayTitle + " has slide: " + slideName,
                    0.95, null
            ));

            // ── SLIDE_LAYOUT entity — queryable node for layout-based queries ──
            String slideLayout = str(meta.get(META_SLIDE_LAYOUT));
            if (slideLayout != null && !slideLayout.isBlank()) {
                String layoutId = entityId("layout:" + source + ":" + slideLayout.toLowerCase());
                Map<String, String> layoutProps = new LinkedHashMap<>();
                layoutProps.put("layoutName", slideLayout);
                layoutProps.put(PROP_ENTITY_SOURCE, SOURCE_OFFICE_EXTRACTOR);
                addEntity(entityIndex, new ExtractedEntity(
                        layoutId, slideLayout, ENTITY_SLIDE_LAYOUT,
                        null, "Slide layout: " + slideLayout, 0.8, layoutProps));
                relations.add(new ExtractedRelation(
                        slideId, layoutId, REL_USES_LAYOUT,
                        slideName + " uses layout: " + slideLayout,
                        0.8, null));
            }

            // ── SPEAKER_NOTE entity from speakerNotes ─────────────────
            String speakerNotes = str(meta.get(META_SPEAKER_NOTES));
            if (speakerNotes != null && !speakerNotes.isBlank()) {
                String noteEntityId = entityId("speaker_note:" + source + ":" + slideName);
                Map<String, String> noteProps = new LinkedHashMap<>();
                noteProps.put("text", speakerNotes.length() > 2000
                        ? speakerNotes.substring(0, 2000) : speakerNotes);
                noteProps.put("slideTitle", slideName);

                String noteLabel = speakerNotes.length() > 80
                        ? speakerNotes.substring(0, 80) + "..." : speakerNotes;
                ExtractedEntity noteEntity = new ExtractedEntity(
                        noteEntityId, "Notes: " + noteLabel, ENTITY_SPEAKER_NOTE,
                        null, "Speaker notes on " + slideName, 0.9, noteProps
                );
                addEntity(entityIndex, noteEntity);
                relations.add(new ExtractedRelation(
                        slideId, noteEntityId, REL_HAS_SPEAKER_NOTE,
                        slideName + " has speaker notes", 0.9, null
                ));
            }

            // ── SLIDE_IMAGE entities from pptx.slideImages ──────────────
            Object slideImagesObj = meta.get(META_PPTX_SLIDE_IMAGES);
            if (slideImagesObj instanceof List<?> slideImagesList) {
                for (int imgIdx = 0; imgIdx < slideImagesList.size(); imgIdx++) {
                    Object imgItem = slideImagesList.get(imgIdx);
                    if (!(imgItem instanceof Map<?, ?> imgMap)) continue;
                    String imgName = imgMap.get("name") instanceof String s ? s : "image-" + (imgIdx + 1);
                    String imgContentType = imgMap.get("contentType") instanceof String s ? s : null;

                    String imgEntityId = entityId("slide_image:" + source + ":" + slideName + ":" + imgIdx);
                    Map<String, String> imgProps = new LinkedHashMap<>();
                    imgProps.put("name", imgName);
                    if (imgContentType != null) imgProps.put(PROP_CONTENT_TYPE, imgContentType);
                    imgProps.put("slideTitle", slideName);
                    String altText = imgMap.get("altText") instanceof String s2 ? s2 : null;
                    if (altText != null) imgProps.put("altText", altText);

                    ExtractedEntity imgEntity = new ExtractedEntity(
                            imgEntityId, imgName, ENTITY_SLIDE_IMAGE,
                            null, altText != null ? altText : "Slide image on " + slideName + ": " + imgName, 0.85, imgProps
                    );
                    addEntity(entityIndex, imgEntity);
                    relations.add(new ExtractedRelation(
                            slideId, imgEntityId, REL_HAS_SLIDE_IMAGE,
                            slideName + " has image: " + imgName, 0.85, null
                    ));
                }
            }

            // ── SLIDE_CHART entities from pptx.slideCharts ──────────────
            Object slideChartsObj = meta.get(META_PPTX_SLIDE_CHARTS);
            if (slideChartsObj instanceof List<?> slideChartsList) {
                for (int chartIdx = 0; chartIdx < slideChartsList.size(); chartIdx++) {
                    Object chartItem = slideChartsList.get(chartIdx);
                    if (!(chartItem instanceof Map<?, ?> chartMap)) continue;
                    String chartName = chartMap.get("name") instanceof String s ? s : "chart-" + (chartIdx + 1);

                    String chartEntityId = entityId("slide_chart:" + source + ":" + slideName + ":" + chartIdx);
                    Map<String, String> chartProps = new LinkedHashMap<>();
                    chartProps.put("name", chartName);
                    chartProps.put("slideTitle", slideName);

                    ExtractedEntity chartEntity = new ExtractedEntity(
                            chartEntityId, chartName, ENTITY_SLIDE_CHART,
                            null, "Slide chart on " + slideName + ": " + chartName, 0.85, chartProps
                    );
                    addEntity(entityIndex, chartEntity);
                    relations.add(new ExtractedRelation(
                            slideId, chartEntityId, REL_HAS_SLIDE_CHART,
                            slideName + " has chart: " + chartName, 0.85, null
                    ));
                }
            }

            // ── SMART_ART entities from pptx.slideSmartArt ─────────────
            Object smartArtObj = meta.get(META_PPTX_SLIDE_SMART_ART);
            if (smartArtObj instanceof List<?> smartArtList) {
                for (int saIdx = 0; saIdx < smartArtList.size(); saIdx++) {
                    Object saItem = smartArtList.get(saIdx);
                    if (!(saItem instanceof Map<?, ?> saMap)) continue;
                    String saName = saMap.get("name") instanceof String s ? s : "SmartArt " + (saIdx + 1);
                    String saText = saMap.get("text") instanceof String s ? s : null;

                    String saEntityId = entityId("smart_art:" + source + ":" + slideName + ":" + saIdx);
                    Map<String, String> saProps = new LinkedHashMap<>();
                    saProps.put("name", saName);
                    if (saText != null) saProps.put("text", saText.length() > 500
                            ? saText.substring(0, 500) + "..." : saText);
                    saProps.put("slideTitle", slideName);

                    ExtractedEntity saEntity = new ExtractedEntity(
                            saEntityId, saName, ENTITY_SMART_ART,
                            null, "SmartArt diagram on " + slideName + ": " + saName, 0.85, saProps
                    );
                    addEntity(entityIndex, saEntity);
                    relations.add(new ExtractedRelation(
                            slideId, saEntityId, REL_HAS_SMART_ART,
                            slideName + " has SmartArt: " + saName, 0.85, null
                    ));
                }
            }

            // ── SLIDE_COMMENT entities from pptx.slideComments ───────────
            Object slideCommentsObj = meta.get(META_PPTX_SLIDE_COMMENTS);
            if (slideCommentsObj instanceof List<?> commentsList) {
                for (int cIdx = 0; cIdx < commentsList.size(); cIdx++) {
                    Object cItem = commentsList.get(cIdx);
                    if (!(cItem instanceof Map<?, ?> cMap)) continue;
                    String commentText = cMap.get("text") instanceof String s ? s : null;
                    String commentAuthor = cMap.get("author") instanceof String s ? s : null;
                    String commentDate = cMap.get("date") instanceof String s ? s : null;
                    if (commentText == null || commentText.isBlank()) continue;

                    String commentId = entityId("slide_comment:" + source + ":s" + slideNum + ":" + cIdx);
                    Map<String, String> commentProps = new LinkedHashMap<>();
                    commentProps.put("text", commentText.length() > 500
                            ? commentText.substring(0, 500) + "..." : commentText);
                    if (commentAuthor != null) commentProps.put("author", commentAuthor);
                    if (commentDate != null) commentProps.put("date", commentDate);
                    commentProps.put("slideTitle", slideName);

                    addEntity(entityIndex, new ExtractedEntity(
                            commentId, commentAuthor != null ? "Comment by " + commentAuthor : "Comment #" + (cIdx + 1),
                            ENTITY_SLIDE_COMMENT,
                            null, "Slide comment on " + slideName + ": " + commentText, 0.85, commentProps
                    ));
                    relations.add(new ExtractedRelation(
                            slideId, commentId, REL_HAS_SLIDE_COMMENT,
                            slideName + " has comment", 0.85, null
                    ));

                    if (commentAuthor != null && !commentAuthor.isBlank()) {
                        String commentAuthorId = entityId("person:" + commentAuthor.toLowerCase());
                        Map<String, String> authorProps = new LinkedHashMap<>();
                        authorProps.put("displayName", commentAuthor);
                        addEntity(entityIndex, new ExtractedEntity(
                                commentAuthorId, commentAuthor, ENTITY_PERSON,
                                null, "Slide comment author: " + commentAuthor, 0.85, authorProps
                        ));
                        relations.add(new ExtractedRelation(
                                commentId, commentAuthorId, REL_COMMENT_BY,
                                "Comment by " + commentAuthor, 0.85, null
                        ));
                    }

                    // DATE entity from slide comment date
                    if (commentDate != null && !commentDate.isBlank()) {
                        String dateId = entityId("date:" + commentDate);
                        addEntity(entityIndex, new ExtractedEntity(
                                dateId, commentDate, ENTITY_DATE,
                                null, "Slide comment date: " + commentDate, 0.8,
                                Map.of("date", commentDate, "dateType", "slideComment")));
                        relations.add(new ExtractedRelation(
                                commentId, dateId, REL_PUBLISHED_ON,
                                "Comment on " + commentDate, 0.8, null));
                    }
                }
            }

            // ── SLIDE_BULLET entities from pptx.slideBullets ──────────────
            Object slideBulletsObj = meta.get("pptx.slideBullets");
            if (slideBulletsObj instanceof List<?> bulletsList) {
                for (int bIdx = 0; bIdx < bulletsList.size(); bIdx++) {
                    Object bItem = bulletsList.get(bIdx);
                    if (!(bItem instanceof Map<?, ?> bMap)) continue;
                    String bulletText = bMap.get("text") instanceof String s ? s : null;
                    if (bulletText == null || bulletText.isBlank()) continue;

                    String bulletId = entityId("slide_bullet:" + source + ":s" + slideNum + ":" + bIdx);
                    Map<String, String> bulletProps = new LinkedHashMap<>();
                    bulletProps.put("text", bulletText.length() > 1000
                            ? bulletText.substring(0, 1000) + "..." : bulletText);
                    bulletProps.put("index", String.valueOf(bIdx));
                    String indentLevel = bMap.get("indentLevel") instanceof String s ? s : "0";
                    bulletProps.put("indentLevel", indentLevel);
                    if ("true".equals(bMap.get("isBullet"))) {
                        bulletProps.put("isBullet", "true");
                    }
                    String bulletChar = bMap.get("bulletChar") instanceof String s ? s : null;
                    if (bulletChar != null) bulletProps.put("bulletChar", bulletChar);
                    String shapeName = bMap.get("shapeName") instanceof String s ? s : null;
                    if (shapeName != null) bulletProps.put("shapeName", shapeName);
                    bulletProps.put("slideTitle", slideName);

                    String bulletLabel = bulletText.length() > 80
                            ? bulletText.substring(0, 80) + "..." : bulletText;
                    addEntity(entityIndex, new ExtractedEntity(
                            bulletId, bulletLabel, ENTITY_SLIDE_BULLET,
                            null, "Slide bullet on " + slideName + ": " + bulletLabel, 0.8, bulletProps
                    ));
                    relations.add(new ExtractedRelation(
                            slideId, bulletId, REL_HAS_SLIDE_BULLET,
                            slideName + " has bullet point", 0.8, null
                    ));
                }
            }

            // ── HYPERLINK entities from pptx.slideHyperlinks ─────────────
            Object slideLinksObj = meta.get(META_PPTX_SLIDE_HYPERLINKS);
            if (slideLinksObj instanceof List<?> linksList) {
                for (int lIdx = 0; lIdx < linksList.size(); lIdx++) {
                    Object lItem = linksList.get(lIdx);
                    if (!(lItem instanceof Map<?, ?> lMap)) continue;
                    String url = lMap.get("url") instanceof String s ? s : null;
                    if (url == null || url.isBlank()) continue;
                    String displayText = lMap.get("text") instanceof String s ? s : url;

                    String linkId = entityId("slide_hyperlink:" + url);
                    Map<String, String> linkProps = new LinkedHashMap<>();
                    linkProps.put("url", url);
                    if (displayText != null) linkProps.put("displayText", displayText);

                    addEntity(entityIndex, new ExtractedEntity(
                            linkId, displayText, ENTITY_HYPERLINK,
                            null, "Hyperlink on " + slideName + ": " + url, 0.9, linkProps
                    ));
                    relations.add(new ExtractedRelation(
                            slideId, linkId, REL_HAS_SLIDE_HYPERLINK,
                            slideName + " has hyperlink: " + url, 0.9, null
                    ));
                }
            }
        }

        // ── CHART entity from content_type=chart (Excel chart docs) ────
        String contentType = str(meta.get(META_CONTENT_TYPE));
        if ("chart".equals(contentType)) {
            String chartTitle = str(meta.get(META_CHART_TITLE));
            Object chartIdx = meta.get(META_CHART_INDEX);
            String chartName = chartTitle != null && !chartTitle.isEmpty()
                    ? chartTitle : "Chart " + (chartIdx != null ? chartIdx : "?");
            String chartId = entityId("chart:" + source + ":" + chartName);
            Map<String, String> chartProps = new LinkedHashMap<>();
            if (chartTitle != null) chartProps.put(PROP_CHART_TITLE, chartTitle);
            if (chartIdx != null) chartProps.put(PROP_CHART_INDEX, chartIdx.toString());
            String chartSheet = str(meta.get(META_SHEET_NAME));
            if (chartSheet != null) chartProps.put(META_SHEET_NAME, chartSheet);

            ExtractedEntity chartEntity = new ExtractedEntity(
                    chartId, chartName, ENTITY_CHART,
                    null, "Spreadsheet chart: " + chartName, 0.9, chartProps
            );
            addEntity(entityIndex, chartEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, chartId, REL_HAS_CHART,
                    displayTitle + " has chart: " + chartName,
                    0.9, null
            ));
        }

        // ── DATA_QUALITY_FLAGS from dq_flags metadata (Excel DQ detection) ──
        String dqFlagCount = str(meta.get(META_DQ_FLAG_COUNT));
        if (dqFlagCount != null) {
            Map<String, String> dqProps = new LinkedHashMap<>();
            dqProps.put("flagCount", dqFlagCount);
            String dqFlagsJson = str(meta.get(META_DQ_FLAGS));
            if (dqFlagsJson != null) dqProps.put("flags", dqFlagsJson);
            String dqId = entityId("dq:" + source + ":" + (sheetName != null ? sheetName : "default"));
            ExtractedEntity dqEntity = new ExtractedEntity(
                    dqId, "Data Quality (" + dqFlagCount + " flags)", ENTITY_DATA_QUALITY_REPORT,
                    null, "Data quality flags for " + displayTitle, 0.85, dqProps
            );
            addEntity(entityIndex, dqEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, dqId, REL_HAS_DATA_QUALITY,
                    displayTitle + " has " + dqFlagCount + " data quality flags",
                    0.85, null
            ));
        }

        // ── NAMED_RANGE entities from workbook named ranges ──────────────
        @SuppressWarnings("unchecked")
        List<Map<String, String>> namedRanges = meta.get(META_NAMED_RANGES) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(META_NAMED_RANGES) : null;
        if (namedRanges != null) {
            for (Map<String, String> nr : namedRanges) {
                String nrName = nr.get("name");
                if (nrName == null || nrName.isBlank()) continue;
                String nrId = entityId("namedrange:" + source + ":" + nrName);
                Map<String, String> nrProps = new LinkedHashMap<>();
                nrProps.put("name", nrName);
                if (nr.get("refersTo") != null) nrProps.put("refersToFormula", nr.get("refersTo"));
                if (nr.get("sheetName") != null) nrProps.put(META_SHEET_NAME, nr.get("sheetName"));
                addEntity(entityIndex, new ExtractedEntity(
                        nrId, nrName, ENTITY_NAMED_RANGE,
                        null, "Named range: " + nrName, 0.9, nrProps));
                relations.add(new ExtractedRelation(
                        docEntityId, nrId, REL_DEFINES,
                        displayTitle + " defines named range: " + nrName,
                        0.9, null));
                // Link to sheet entity if available
                if (nr.get("sheetName") != null) {
                    String targetSheetId = entityId("sheet:" + source + ":" + nr.get("sheetName"));
                    if (entityIndex.containsKey(targetSheetId)) {
                        relations.add(new ExtractedRelation(
                                nrId, targetSheetId, REL_RANGE_INPUT,
                                nrName + " refers to sheet: " + nr.get(META_SHEET_NAME),
                                0.85, null));
                    }
                }
            }
        }

        // ── CELL_COMMENT entities from cell comments metadata ──────────
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cellComments = meta.get(META_CELL_COMMENTS) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(META_CELL_COMMENTS) : null;
        if (cellComments != null) {
            for (Map<String, String> cm : cellComments) {
                String cellRef = cm.get(PROP_CELL_REF);
                String commentText = cm.get(PROP_TEXT);
                if (cellRef == null || commentText == null) continue;
                String cmId = entityId("cellcomment:" + source + ":"
                        + (sheetName != null ? sheetName + ":" : "") + cellRef);
                Map<String, String> cmProps = new LinkedHashMap<>();
                cmProps.put(PROP_CELL_REF, cellRef);
                cmProps.put(PROP_TEXT, commentText);
                if (cm.get(PROP_AUTHOR) != null) cmProps.put(PROP_AUTHOR, cm.get(PROP_AUTHOR));
                String cmLabel = "Comment on " + cellRef;
                addEntity(entityIndex, new ExtractedEntity(
                        cmId, cmLabel, ENTITY_CELL_COMMENT,
                        null, commentText, 0.85, cmProps));
                // Link to sheet if available
                String parentId = sheetName != null
                        ? entityId("sheet:" + source + ":" + sheetName) : docEntityId;
                relations.add(new ExtractedRelation(
                        parentId, cmId, REL_HAS_COMMENT,
                        "Cell " + cellRef + " has comment",
                        0.85, null));
                // Create PERSON entity for comment author
                if (cm.get(PROP_AUTHOR) != null && !cm.get(PROP_AUTHOR).isBlank()) {
                    String authorId = entityId("person:" + cm.get(PROP_AUTHOR).toLowerCase());
                    if (!entityIndex.containsKey(authorId)) {
                        Map<String, String> authorProps = new LinkedHashMap<>();
                        authorProps.put("name", cm.get(PROP_AUTHOR));
                        authorProps.put(PROP_SOURCE_FIELD, "cellCommentAuthor");
                        addEntity(entityIndex, new ExtractedEntity(
                                authorId, cm.get(PROP_AUTHOR), ENTITY_PERSON,
                                null, "Cell comment author", 0.75, authorProps));
                    }
                    relations.add(new ExtractedRelation(
                            cmId, authorId, REL_AUTHORED_BY,
                            cmLabel + " authored by " + cm.get(PROP_AUTHOR),
                            0.75, null));
                }
            }
        }

        // ── FORMULA_CELL entities from formula cells metadata ──────────
        @SuppressWarnings("unchecked")
        List<Map<String, String>> formulaCells = meta.get(META_FORMULA_CELLS) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(META_FORMULA_CELLS) : null;
        if (formulaCells != null) {
            for (Map<String, String> fc : formulaCells) {
                String cellRef = fc.get(PROP_CELL_REF);
                String formula = fc.get(PROP_FORMULA);
                if (cellRef == null || formula == null) continue;
                String fcId = entityId("formulacell:" + source + ":"
                        + (sheetName != null ? sheetName + ":" : "") + cellRef);
                Map<String, String> fcProps = new LinkedHashMap<>();
                fcProps.put(PROP_CELL_REF, cellRef);
                fcProps.put(PROP_FORMULA, formula);
                if (sheetName != null) fcProps.put(META_SHEET_NAME, sheetName);
                addEntity(entityIndex, new ExtractedEntity(
                        fcId, cellRef + " = " + formula, ENTITY_FORMULA_CELL,
                        null, "Formula cell " + cellRef + ": " + formula, 0.85, fcProps));
                String parentId = sheetName != null
                        ? entityId("sheet:" + source + ":" + sheetName) : docEntityId;
                relations.add(new ExtractedRelation(
                        parentId, fcId, REL_DEPENDS_ON,
                        sheetName + " has formula at " + cellRef,
                        0.85, null));
            }
        }

        // ── XLSX sheet charts from xlsx.sheetCharts metadata ──────────
        @SuppressWarnings("unchecked")
        List<Map<String, String>> xlsxCharts = meta.get("xlsx.sheetCharts") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("xlsx.sheetCharts") : null;
        if (xlsxCharts != null) {
            for (Map<String, String> cm : xlsxCharts) {
                String chartTitle = cm.get("title");
                String chartIndex = cm.get("index");
                String chartName = chartTitle != null && !chartTitle.isEmpty()
                        ? chartTitle : "Chart " + (chartIndex != null ? chartIndex : "?");
                String chartId = entityId("chart:" + source + ":" + (sheetName != null ? sheetName + ":" : "") + chartName);
                Map<String, String> chartProps = new LinkedHashMap<>();
                if (chartTitle != null) chartProps.put(PROP_CHART_TITLE, chartTitle);
                if (chartIndex != null) chartProps.put(PROP_CHART_INDEX, chartIndex);
                if (sheetName != null) chartProps.put(META_SHEET_NAME, sheetName);
                addEntity(entityIndex, new ExtractedEntity(
                        chartId, chartName, ENTITY_CHART,
                        null, "Spreadsheet chart: " + chartName, 0.9, chartProps));
                String parentId = sheetName != null
                        ? entityId("sheet:" + source + ":" + sheetName) : docEntityId;
                relations.add(new ExtractedRelation(
                        parentId, chartId, REL_HAS_CHART,
                        (sheetName != null ? sheetName : displayTitle) + " has chart: " + chartName,
                        0.9, null));
            }
        }

        // ── XLSX workbook images from xlsx.images metadata ──────────
        @SuppressWarnings("unchecked")
        List<Map<String, String>> xlsxImages = meta.get("xlsx.images") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("xlsx.images") : null;
        if (xlsxImages != null) {
            int imgCount = 0;
            for (Map<String, String> im : xlsxImages) {
                String imgIndex = im.get("index");
                String imgMime = im.get("mimeType");
                String imgName = "Image " + (imgIndex != null ? (Integer.parseInt(imgIndex) + 1) : (imgCount + 1));
                String imgId = entityId("xlsximage:" + source + ":" + imgName);
                Map<String, String> imgProps = new LinkedHashMap<>();
                if (imgIndex != null) imgProps.put(PROP_IMAGE_INDEX, imgIndex);
                if (imgMime != null) imgProps.put("mimeType", imgMime);
                if (im.get("size") != null) imgProps.put(PROP_SIZE_BYTES, im.get("size"));
                if (im.get("fileExtension") != null) imgProps.put("fileExtension", im.get("fileExtension"));
                addEntity(entityIndex, new ExtractedEntity(
                        imgId, imgName, ENTITY_EMBEDDED_IMAGE,
                        null, "Embedded image in workbook", 0.85, imgProps));
                relations.add(new ExtractedRelation(
                        docEntityId, imgId, REL_HAS_IMAGE,
                        displayTitle + " has embedded image: " + imgName,
                        0.85, null));
                imgCount++;
            }
        }

        // ── Enrich spreadsheet document entity with aggregate counts ──
        putIfPresent(docProps, "chartCount", meta, "xlsx.chartCount");
        putIfPresent(docProps, "imageCount", meta, "xlsx.imageCount");

        // ── Formula graph statistics (content_type=formula_graph) ──
        if ("formula_graph".equals(contentType)) {
            putValueIfPresent(docProps, "totalFormulas", meta, "totalFormulas");
            putValueIfPresent(docProps, "totalDependencies", meta, "totalDependencies");
            putValueIfPresent(docProps, "crossSheetDependencies", meta, "crossSheetDependencies");
            putValueIfPresent(docProps, "namedRangeCount", meta, "namedRangeCount");
            putValueIfPresent(docProps, "sheetCount", meta, "sheetCount");
            putValueIfPresent(docProps, "entityCount", meta, "entityCount");
            putValueIfPresent(docProps, "relationshipCount", meta, "relationshipCount");
        }

        // ── EMBEDDED_IMAGE from content_type=image (Excel embedded images) ──
        if ("image".equals(contentType)) {
            Object imageIdx = meta.get(META_IMAGE_INDEX);
            String imageMime = str(meta.get(META_IMAGE_MIME_TYPE));
            String imageName = "Image " + (imageIdx != null ? (Integer.parseInt(imageIdx.toString()) + 1) : "?");
            String imageId = entityId("image:" + source + ":" + imageName);
            Map<String, String> imageProps = new LinkedHashMap<>();
            if (imageIdx != null) imageProps.put(PROP_IMAGE_INDEX, imageIdx.toString());
            if (imageMime != null) imageProps.put("mimeType", imageMime);
            putValueIfPresent(imageProps, PROP_SIZE_BYTES, meta, META_IMAGE_SIZE_BYTES);

            ExtractedEntity imageEntity = new ExtractedEntity(
                    imageId, imageName, ENTITY_EMBEDDED_IMAGE,
                    null, "Embedded image in " + displayTitle, 0.85, imageProps
            );
            addEntity(entityIndex, imageEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, imageId, REL_HAS_IMAGE,
                    displayTitle + " has embedded image: " + imageName,
                    0.85, null
            ));
        }

        // ── WORD_TABLE from content_type=table (Word DOCX/DOC tables) ──
        if ("table".equals(contentType)) {
            Object tableIdx = meta.get(META_TABLE_INDEX);
            String wordTableName = "Table " + (tableIdx != null ? (Integer.parseInt(tableIdx.toString()) + 1) : "?");
            String wordTableId = entityId("wordtable:" + source + ":" + wordTableName);
            Map<String, String> wordTableProps = new LinkedHashMap<>();
            if (tableIdx != null) wordTableProps.put(PROP_TABLE_INDEX, tableIdx.toString());
            putValueIfPresent(wordTableProps, PROP_ROW_COUNT, meta, META_TABLE_ROW_COUNT);
            putValueIfPresent(wordTableProps, PROP_COLUMN_COUNT, meta, META_TABLE_COLUMN_COUNT);
            putIfPresent(wordTableProps, PROP_HEADERS, meta, META_TABLE_HEADERS);
            String wordTableSummary = str(meta.get("table_summary"));
            if (wordTableSummary != null) wordTableProps.put("summary", wordTableSummary);

            ExtractedEntity wordTableEntity = new ExtractedEntity(
                    wordTableId, wordTableName, ENTITY_TABLE,
                    null, "Word document table: " + wordTableName, 0.95, wordTableProps
            );
            addEntity(entityIndex, wordTableEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, wordTableId, REL_HAS_TABLE,
                    displayTitle + " has table: " + wordTableName,
                    0.95, null
            ));
        }

        // ── DATABASE_TABLE from Access/MDB tableName ───────────────────
        String tableName = str(meta.get(META_TABLE_NAME));
        if (tableName != null) {
            String tableId = entityId("dbtable:" + source + ":" + tableName);
            Map<String, String> tableProps = new LinkedHashMap<>();
            tableProps.put(PROP_TABLE_NAME, tableName);
            putValueIfPresent(tableProps, PROP_ROW_COUNT, meta, META_TABLE_ROW_COUNT);
            putValueIfPresent(tableProps, PROP_COLUMN_COUNT, meta, META_TABLE_COLUMN_COUNT);
            putIfPresent(tableProps, PROP_HEADERS, meta, META_TABLE_HEADERS);

            ExtractedEntity tableEntity = new ExtractedEntity(
                    tableId, tableName, ENTITY_DATABASE_TABLE,
                    null, "Database table: " + tableName, 0.95, tableProps
            );
            addEntity(entityIndex, tableEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, tableId, REL_HAS_TABLE,
                    displayTitle + " has table: " + tableName,
                    0.95, null
            ));
        }

        // ── DOCUMENT_SECTION from DOCX/DOC headings ─────────────────────
        Object headingsObj = meta.get(META_DOCX_HEADINGS);
        if (headingsObj == null) headingsObj = meta.get("doc.headings");
        if (headingsObj instanceof List<?> headingsList) {
            Map<Integer, String> headingDepthStack = new LinkedHashMap<>();
            for (Object item : headingsList) {
                if (!(item instanceof Map<?, ?> headingMap)) continue;
                String headingText = headingMap.get("text") instanceof String s ? s : null;
                String headingLevel = headingMap.get("level") instanceof String s ? s : "1";
                if (headingText == null || headingText.isBlank()) continue;

                int level = 1;
                try { level = Integer.parseInt(headingLevel); } catch (NumberFormatException e) {
                    log.debug("Could not parse Office document heading level '{}' as integer: {}", headingLevel, e.getMessage());
                }

                String sectionId = entityId("section:" + source + ":" + headingText.toLowerCase());
                Map<String, String> sectionProps = new LinkedHashMap<>();
                sectionProps.put("headingLevel", headingLevel);
                sectionProps.put("headingText", headingText);
                if (headingMap.get("paragraphIndex") instanceof String pi) {
                    sectionProps.put("paragraphIndex", pi);
                }

                ExtractedEntity sectionEntity = new ExtractedEntity(
                        sectionId, headingText, ENTITY_DOCUMENT_SECTION,
                        null, "Section heading (H" + headingLevel + "): " + headingText,
                        0.9, sectionProps
                );
                addEntity(entityIndex, sectionEntity);

                if (level <= 1) {
                    // Top-level heading: link directly to document
                    relations.add(new ExtractedRelation(
                            docEntityId, sectionId, REL_HAS_SECTION,
                            displayTitle + " has section: " + headingText,
                            0.9, null
                    ));
                } else {
                    // Find nearest parent at a higher level
                    String parentId = null;
                    for (int d = level - 1; d >= 0; d--) {
                        if (headingDepthStack.containsKey(d)) {
                            parentId = headingDepthStack.get(d);
                            break;
                        }
                    }
                    if (parentId != null) {
                        relations.add(new ExtractedRelation(
                                sectionId, parentId, REL_SUBSECTION_OF,
                                headingText + " is subsection of parent heading",
                                0.9, null
                        ));
                    } else {
                        // Fallback: link to document if no parent found
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, REL_HAS_SECTION,
                                displayTitle + " has section: " + headingText,
                                0.9, null
                        ));
                    }
                }
                headingDepthStack.put(level, sectionId);
                final int currentLevel = level;
                headingDepthStack.entrySet().removeIf(e -> e.getKey() > currentLevel);
            }
        }

        // ── DOCUMENT_SECTION from streaming section metadata (no headings) ──
        // When the streaming loader produces sections without headings, sectionNumber/totalSections
        // are set as metadata. Only create these section entities if no heading-based sections exist.
        Object sectionNumObj = meta.get("sectionNumber");
        Object totalSectionsObj = meta.get("totalSections");
        if (sectionNumObj != null && totalSectionsObj != null && headingsObj == null) {
            int sectionNum = sectionNumObj instanceof Number n ? n.intValue() : 0;
            int totalSects = totalSectionsObj instanceof Number n ? n.intValue() : 0;
            if (sectionNum > 0 && totalSects > 1) {
                String sectionId = entityId("section:" + source + ":s" + sectionNum);
                Map<String, String> sectionProps = new LinkedHashMap<>();
                sectionProps.put("sectionNumber", String.valueOf(sectionNum));
                sectionProps.put("totalSections", String.valueOf(totalSects));
                Object paraStart = meta.get("paragraphStart");
                Object paraEnd = meta.get("paragraphEnd");
                if (paraStart != null) sectionProps.put("paragraphStart", paraStart.toString());
                if (paraEnd != null) sectionProps.put("paragraphEnd", paraEnd.toString());
                addEntity(entityIndex, new ExtractedEntity(
                        sectionId, "Section " + sectionNum + " of " + totalSects,
                        ENTITY_DOCUMENT_SECTION, null,
                        "Streaming section " + sectionNum + " of " + displayTitle,
                        0.8, sectionProps
                ));
                relations.add(new ExtractedRelation(
                        docEntityId, sectionId, REL_HAS_SECTION,
                        displayTitle + " has section " + sectionNum,
                        0.8, null
                ));
            }
        }

        // ── DOCUMENT_LIST_ITEM entities from DOCX/DOC list items ─────────
        Object listItemsObj = meta.get("docx.listItems");
        if (listItemsObj == null) listItemsObj = meta.get("doc.listItems");
        if (listItemsObj instanceof List<?> listItemsList) {
            for (int liIdx = 0; liIdx < listItemsList.size(); liIdx++) {
                Object liItem = listItemsList.get(liIdx);
                if (!(liItem instanceof Map<?, ?> liMap)) continue;
                String liText = liMap.get("text") instanceof String s ? s : null;
                if (liText == null || liText.isBlank()) continue;

                String liId = entityId("list_item:" + source + ":" + liIdx);
                Map<String, String> liProps = new LinkedHashMap<>();
                liProps.put("text", liText.length() > 1000 ? liText.substring(0, 1000) + "..." : liText);
                liProps.put("index", String.valueOf(liIdx));
                String indentLevel = liMap.get("indentLevel") instanceof String s ? s : "0";
                liProps.put("indentLevel", indentLevel);
                if (liMap.get("numId") instanceof String numId) liProps.put("numId", numId);
                if (liMap.get("style") instanceof String style) liProps.put("style", style);

                String liLabel = liText.length() > 80 ? liText.substring(0, 80) + "..." : liText;
                addEntity(entityIndex, new ExtractedEntity(
                        liId, liLabel, ENTITY_DOCUMENT_LIST_ITEM,
                        null, "List item: " + liLabel, 0.8, liProps
                ));
                relations.add(new ExtractedRelation(
                        docEntityId, liId, REL_HAS_LIST_ITEM,
                        displayTitle + " has list item", 0.8, null
                ));
            }
        }

        // ── BOOKMARK entities from DOCX/DOC bookmarks ────────────────────
        Object bookmarksObj = meta.get(META_DOCX_BOOKMARKS);
        if (bookmarksObj == null) bookmarksObj = meta.get("doc.bookmarks");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> bookmarks = bookmarksObj instanceof List<?>
                ? (List<Map<String, String>>) bookmarksObj : null;
        if (bookmarks != null) {
            for (Map<String, String> bm : bookmarks) {
                String bmName = bm.get("name");
                if (bmName == null || bmName.isBlank()) continue;
                String bmId = entityId("bookmark:" + source + ":" + bmName);
                Map<String, String> bmProps = new LinkedHashMap<>();
                bmProps.put("name", bmName);
                if (bm.get("id") != null) bmProps.put("bookmarkId", bm.get("id"));
                if (bm.get("paragraphText") != null) bmProps.put("paragraphText", bm.get("paragraphText"));
                addEntity(entityIndex, new ExtractedEntity(
                        bmId, bmName, ENTITY_BOOKMARK,
                        null, "Word document bookmark: " + bmName, 0.85, bmProps));
                relations.add(new ExtractedRelation(
                        docEntityId, bmId, REL_HAS_BOOKMARK,
                        displayTitle + " has bookmark: " + bmName,
                        0.85, null));
            }
        }

        // ── DOCUMENT_COMMENT from docx.comments ──────────────────────────
        Object commentsObj = meta.get(META_DOCX_COMMENTS);
        if (commentsObj instanceof List<?> commentsList) {
            // First pass: build commentId → entityId map and parentParaId → commentId map
            Map<String, String> commentIdToEntityId = new LinkedHashMap<>();
            Map<String, String> paraIdToCommentId = new LinkedHashMap<>();
            // Track which comments are replies (have parentCommentParaId)
            Map<String, String> commentIdToParentParaId = new LinkedHashMap<>();

            for (int i = 0; i < commentsList.size(); i++) {
                Object item = commentsList.get(i);
                if (!(item instanceof Map<?, ?> commentMap)) continue;
                String commentText = commentMap.get("text") instanceof String s ? s : null;
                String commentAuthor = commentMap.get("author") instanceof String s ? s : null;
                String commentId = commentMap.get("commentId") instanceof String s ? s : String.valueOf(i);
                String commentDate = commentMap.get("date") instanceof String s ? s : null;
                String resolved = commentMap.get("resolved") instanceof String s ? s : null;
                String parentParaId = commentMap.get("parentCommentParaId") instanceof String s ? s : null;
                String initials = commentMap.get("initials") instanceof String s ? s : null;
                if (commentText == null || commentText.isBlank()) continue;

                String commentEntityId = entityId("comment:" + source + ":" + commentId);
                commentIdToEntityId.put(commentId, commentEntityId);
                if (parentParaId != null) {
                    commentIdToParentParaId.put(commentId, parentParaId);
                }

                Map<String, String> commentProps = new LinkedHashMap<>();
                commentProps.put("commentId", commentId);
                commentProps.put("text", commentText);
                if (commentAuthor != null) commentProps.put("author", commentAuthor);
                if (commentDate != null) commentProps.put("date", commentDate);
                if (initials != null) commentProps.put("initials", initials);
                if ("true".equals(resolved)) commentProps.put("resolved", "true");
                if (parentParaId != null) {
                    commentProps.put("isReply", "true");
                    commentProps.put("parentCommentParaId", parentParaId);
                }

                String commentLabel = commentAuthor != null
                        ? commentAuthor + ": " + (commentText.length() > 60 ? commentText.substring(0, 60) + "…" : commentText)
                        : (commentText.length() > 80 ? commentText.substring(0, 80) + "…" : commentText);

                ExtractedEntity commentEntity = new ExtractedEntity(
                        commentEntityId, commentLabel, ENTITY_DOCUMENT_COMMENT,
                        null, "Word document comment: " + commentLabel, 0.9, commentProps
                );
                addEntity(entityIndex, commentEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, commentEntityId, REL_HAS_COMMENT,
                        displayTitle + " has comment by " + (commentAuthor != null ? commentAuthor : "unknown"),
                        0.9, null
                ));

                // COMMENT_BY relation to PERSON if author is present
                if (commentAuthor != null && !commentAuthor.isBlank()) {
                    String commentAuthorId = entityId("person:" + commentAuthor.toLowerCase());
                    Map<String, String> authorProps = new LinkedHashMap<>();
                    authorProps.put("displayName", commentAuthor);
                    addEntity(entityIndex, new ExtractedEntity(
                            commentAuthorId, commentAuthor, ENTITY_PERSON,
                            null, "Comment author: " + commentAuthor, 0.9, authorProps
                    ));
                    relations.add(new ExtractedRelation(
                            commentEntityId, commentAuthorId, REL_COMMENT_BY,
                            commentLabel + " commented by " + commentAuthor, 0.9, null
                    ));
                }

                // DATE entity from docx comment date
                if (commentDate != null && !commentDate.isBlank()) {
                    String dateId = entityId("date:" + commentDate);
                    addEntity(entityIndex, new ExtractedEntity(
                            dateId, commentDate, ENTITY_DATE,
                            null, "Comment date: " + commentDate, 0.85,
                            Map.of("date", commentDate, "dateType", "comment")));
                    relations.add(new ExtractedRelation(
                            commentEntityId, dateId, REL_PUBLISHED_ON,
                            "Comment on " + commentDate, 0.85, null));
                }
            }

            // Second pass: create REPLY_TO relations for threaded comments
            // parentCommentParaId references the paraId of the parent comment's paragraph.
            // Since POI doesn't expose paraId directly, we use index-based heuristic:
            // replies typically follow the root comment sequentially in the comment list.
            // Match by commentId ordering — reply with parentParaId resolves to the
            // nearest preceding non-reply comment.
            for (Map.Entry<String, String> entry : commentIdToParentParaId.entrySet()) {
                String replyCommentId = entry.getKey();
                String replyEntityId = commentIdToEntityId.get(replyCommentId);
                if (replyEntityId == null) continue;

                // Find parent: iterate preceding comments to find one without a parentParaId
                // This is a best-effort heuristic since paraId ↔ commentId mapping isn't direct
                String parentEntityId = null;
                List<String> orderedIds = new ArrayList<>(commentIdToEntityId.keySet());
                int replyIdx = orderedIds.indexOf(replyCommentId);
                for (int p = replyIdx - 1; p >= 0; p--) {
                    String candidateId = orderedIds.get(p);
                    if (!commentIdToParentParaId.containsKey(candidateId)) {
                        parentEntityId = commentIdToEntityId.get(candidateId);
                        break;
                    }
                }
                if (parentEntityId != null) {
                    relations.add(new ExtractedRelation(
                            replyEntityId, parentEntityId, REL_REPLIED_TO,
                            "Comment reply", 0.8, null));
                }
            }
        }

        // ── HYPERLINK from docx/doc hyperlinks ──────────────────────────
        Object hyperlinksObj = meta.get(META_DOCX_HYPERLINKS);
        if (hyperlinksObj == null) hyperlinksObj = meta.get("doc.hyperlinks");
        if (hyperlinksObj instanceof List<?> hyperlinksList) {
            for (int i = 0; i < hyperlinksList.size(); i++) {
                Object item = hyperlinksList.get(i);
                if (!(item instanceof Map<?, ?> linkMap)) continue;
                String url = linkMap.get("url") instanceof String s ? s : null;
                String linkText = linkMap.get("text") instanceof String s ? s : null;
                if (url == null || url.isBlank()) continue;

                String hyperlinkEntityId = entityId("hyperlink:" + source + ":" + url);
                Map<String, String> linkProps = new LinkedHashMap<>();
                linkProps.put("url", url);
                if (linkText != null) linkProps.put("text", linkText);

                String hyperlinkLabel = linkText != null && !linkText.isBlank() ? linkText : url;

                ExtractedEntity hyperlinkEntity = new ExtractedEntity(
                        hyperlinkEntityId, hyperlinkLabel, ENTITY_HYPERLINK,
                        null, "Hyperlink: " + url, 0.9, linkProps
                );
                addEntity(entityIndex, hyperlinkEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, hyperlinkEntityId, REL_HAS_HYPERLINK,
                        displayTitle + " has hyperlink: " + url, 0.9, null
                ));
            }
        }

        // ── TRACKED_CHANGE from docx.trackedChanges ───────────────────────
        Object trackedChangesObj = meta.get(META_DOCX_TRACKED_CHANGES);
        if (trackedChangesObj instanceof List<?> trackedChangesList) {
            for (int i = 0; i < trackedChangesList.size(); i++) {
                Object item = trackedChangesList.get(i);
                if (!(item instanceof Map<?, ?> changeMap)) continue;
                String changeType = changeMap.get("type") instanceof String s ? s : "change";
                String changeAuthor = changeMap.get("author") instanceof String s ? s : null;
                String changeDate = changeMap.get("date") instanceof String s ? s : null;

                String changeText = changeMap.get("text") instanceof String s ? s : null;
                String changeParagraphIdx = changeMap.get("paragraphIndex") instanceof String s ? s : null;

                String changeEntityId = entityId("tracked_change:" + source + ":" + i);
                Map<String, String> changeProps = new LinkedHashMap<>();
                changeProps.put("type", changeType);
                if (changeAuthor != null) changeProps.put("author", changeAuthor);
                if (changeDate != null) changeProps.put("date", changeDate);
                if (changeText != null) changeProps.put("text", changeText);
                if (changeParagraphIdx != null) changeProps.put("paragraphIndex", changeParagraphIdx);

                String changeLabel = changeAuthor != null
                        ? changeType + " by " + changeAuthor
                        : changeType + " #" + (i + 1);

                ExtractedEntity changeEntity = new ExtractedEntity(
                        changeEntityId, changeLabel, ENTITY_TRACKED_CHANGE,
                        null, "Tracked " + changeLabel + " in " + displayTitle, 0.85, changeProps
                );
                addEntity(entityIndex, changeEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, changeEntityId, REL_HAS_TRACKED_CHANGE,
                        displayTitle + " has tracked change: " + changeLabel, 0.85, null
                ));

                // CHANGED_BY relation to PERSON if author is present
                if (changeAuthor != null && !changeAuthor.isBlank()) {
                    String changeAuthorId = entityId("person:" + changeAuthor.toLowerCase());
                    Map<String, String> authorProps = new LinkedHashMap<>();
                    authorProps.put("displayName", changeAuthor);
                    addEntity(entityIndex, new ExtractedEntity(
                            changeAuthorId, changeAuthor, ENTITY_PERSON,
                            null, "Tracked change author: " + changeAuthor, 0.85, authorProps
                    ));
                    relations.add(new ExtractedRelation(
                            changeEntityId, changeAuthorId, REL_CHANGED_BY,
                            changeLabel + " changed by " + changeAuthor, 0.85, null
                    ));
                }

                // DATE entity from tracked change date
                if (changeDate != null && !changeDate.isBlank()) {
                    String dateId = entityId("date:" + changeDate);
                    addEntity(entityIndex, new ExtractedEntity(
                            dateId, changeDate, ENTITY_DATE,
                            null, "Tracked change date: " + changeDate, 0.8,
                            Map.of("date", changeDate, "dateType", "trackedChange")));
                    relations.add(new ExtractedRelation(
                            changeEntityId, dateId, REL_MODIFIED_ON,
                            changeLabel + " on " + changeDate, 0.8, null));
                }
            }
        }

        // ── FOOTNOTE from docx.footnotes ──────────────────────────────────
        Object footnotesObj = meta.get(META_DOCX_FOOTNOTES);
        if (footnotesObj instanceof List<?> footnotesList) {
            for (int i = 0; i < footnotesList.size(); i++) {
                Object item = footnotesList.get(i);
                if (!(item instanceof Map<?, ?> fnMap)) continue;
                String fnId = fnMap.get("id") instanceof String s ? s : String.valueOf(i);
                String fnText = fnMap.get("text") instanceof String s ? s : null;
                if (fnText == null || fnText.isBlank()) continue;

                String footnoteEntityId = entityId("footnote:" + source + ":" + fnId);
                Map<String, String> fnProps = new LinkedHashMap<>();
                fnProps.put("footnoteId", fnId);
                fnProps.put("text", fnText.length() > 500 ? fnText.substring(0, 500) : fnText);

                String fnLabel = fnText.length() > 80 ? fnText.substring(0, 80) + "..." : fnText;
                addEntity(entityIndex, new ExtractedEntity(
                        footnoteEntityId, "Footnote " + fnId + ": " + fnLabel, ENTITY_FOOTNOTE,
                        null, "Footnote in " + displayTitle, 0.9, fnProps
                ));
                relations.add(new ExtractedRelation(
                        docEntityId, footnoteEntityId, REL_HAS_FOOTNOTE,
                        displayTitle + " has footnote: " + fnLabel, 0.9, null
                ));
            }
        }

        // ── ENDNOTE from docx.endnotes ───────────────────────────────────
        Object endnotesObj = meta.get(META_DOCX_ENDNOTES);
        if (endnotesObj instanceof List<?> endnotesList) {
            for (int i = 0; i < endnotesList.size(); i++) {
                Object item = endnotesList.get(i);
                if (!(item instanceof Map<?, ?> enMap)) continue;
                String enId = enMap.get("id") instanceof String s ? s : String.valueOf(i);
                String enText = enMap.get("text") instanceof String s ? s : null;
                if (enText == null || enText.isBlank()) continue;

                String endnoteEntityId = entityId("endnote:" + source + ":" + enId);
                Map<String, String> enProps = new LinkedHashMap<>();
                enProps.put("endnoteId", enId);
                enProps.put("text", enText.length() > 500 ? enText.substring(0, 500) : enText);

                String enLabel = enText.length() > 80 ? enText.substring(0, 80) + "..." : enText;
                addEntity(entityIndex, new ExtractedEntity(
                        endnoteEntityId, "Endnote " + enId + ": " + enLabel, ENTITY_ENDNOTE,
                        null, "Endnote in " + displayTitle, 0.9, enProps
                ));
                relations.add(new ExtractedRelation(
                        docEntityId, endnoteEntityId, REL_HAS_ENDNOTE,
                        displayTitle + " has endnote: " + enLabel, 0.9, null
                ));
            }
        }

        // ── Embedded images from docx.images / doc.images metadata ──────
        Object imagesObj = meta.get("docx.images");
        if (imagesObj == null) imagesObj = meta.get("doc.images");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> docxImages = imagesObj instanceof List<?>
                ? (List<Map<String, String>>) imagesObj : null;
        if (docxImages != null) {
            int imgCount = 0;
            for (Map<String, String> im : docxImages) {
                String imgFileName = im.get("filename");
                String imgMime = im.get("mimeType");
                // DOC images use "extension" instead of "filename"
                if (imgFileName == null && im.get("extension") != null) {
                    imgFileName = "image_" + imgCount + "." + im.get("extension");
                }
                String imgName = imgFileName != null ? imgFileName : ("Image " + (imgCount + 1));
                String imgId = entityId("docximage:" + source + ":" + imgName);
                Map<String, String> imgProps = new LinkedHashMap<>();
                if (imgFileName != null) imgProps.put("filename", imgFileName);
                if (imgMime != null) imgProps.put("mimeType", imgMime);
                if (im.get("pictureType") != null) imgProps.put("pictureType", im.get("pictureType"));
                if (im.get("width") != null) imgProps.put("width", im.get("width"));
                if (im.get("height") != null) imgProps.put("height", im.get("height"));
                if (im.get("sizeBytes") != null) imgProps.put("sizeBytes", im.get("sizeBytes"));
                if (im.get("description") != null) imgProps.put("description", im.get("description"));
                if (im.get("altText") != null) imgProps.put("altText", im.get("altText"));
                String imgDesc = im.get("altText") != null ? im.get("altText")
                        : im.get("description") != null ? im.get("description")
                        : "Embedded image in Word document";
                addEntity(entityIndex, new ExtractedEntity(
                        imgId, imgName, ENTITY_EMBEDDED_IMAGE,
                        null, imgDesc, 0.85, imgProps));
                relations.add(new ExtractedRelation(
                        docEntityId, imgId, REL_HAS_IMAGE,
                        displayTitle + " has embedded image: " + imgName,
                        0.85, null));
                imgCount++;
            }
        }

        // ── Headers/footers from docx/doc metadata ─────────────────────
        Object headersObj = meta.get("docx.headers");
        if (headersObj == null) headersObj = meta.get("doc.headers");
        @SuppressWarnings("unchecked")
        List<String> docxHeaders = headersObj instanceof List<?>
                ? (List<String>) headersObj : null;
        Object footersObj = meta.get("docx.footers");
        if (footersObj == null) footersObj = meta.get("doc.footers");
        @SuppressWarnings("unchecked")
        List<String> docxFooters = footersObj instanceof List<?>
                ? (List<String>) footersObj : null;
        if (docxHeaders != null && !docxHeaders.isEmpty()) {
            String headerContent = String.join(" | ", docxHeaders);
            Map<String, String> headerProps = new LinkedHashMap<>();
            headerProps.put("content", headerContent.length() > 500
                    ? headerContent.substring(0, 500) : headerContent);
            headerProps.put("type", "header");
            String headerId = entityId("docxheader:" + source);
            addEntity(entityIndex, new ExtractedEntity(
                    headerId, "Header: " + (headerContent.length() > 80
                            ? headerContent.substring(0, 80) + "..." : headerContent),
                    ENTITY_DOCUMENT_SECTION, null,
                    "Document header content", 0.7, headerProps));
            relations.add(new ExtractedRelation(
                    docEntityId, headerId, REL_HAS_SECTION,
                    displayTitle + " has header content", 0.7, null));
        }
        if (docxFooters != null && !docxFooters.isEmpty()) {
            String footerContent = String.join(" | ", docxFooters);
            Map<String, String> footerProps = new LinkedHashMap<>();
            footerProps.put("content", footerContent.length() > 500
                    ? footerContent.substring(0, 500) : footerContent);
            footerProps.put("type", "footer");
            String footerId = entityId("docxfooter:" + source);
            addEntity(entityIndex, new ExtractedEntity(
                    footerId, "Footer: " + (footerContent.length() > 80
                            ? footerContent.substring(0, 80) + "..." : footerContent),
                    ENTITY_DOCUMENT_SECTION, null,
                    "Document footer content", 0.7, footerProps));
            relations.add(new ExtractedRelation(
                    docEntityId, footerId, REL_HAS_SECTION,
                    displayTitle + " has footer content", 0.7, null));
        }

        // ── Email entities from PST/MSG documents (email.* namespace) ──────
        String emailFrom = str(meta.get(META_EMAIL_FROM));
        String emailSubject = str(meta.get(META_EMAIL_SUBJECT));
        if (emailFrom != null && ENTITY_OUTLOOK_MESSAGE.equals(entityType)) {
            // Sender → PERSON entity + SENT_BY relation
            String senderAddr = str(meta.get(META_EMAIL_FROM_ADDRESS));
            String senderName = str(meta.get(META_EMAIL_FROM_NAME));
            String senderKey = senderAddr != null ? senderAddr.toLowerCase() : emailFrom.toLowerCase();
            String senderId = entityId("person:" + senderKey);

            Map<String, String> senderProps = new LinkedHashMap<>();
            if (senderAddr != null) senderProps.put("email", senderAddr);
            if (senderName != null) senderProps.put("displayName", senderName);

            addEntity(entityIndex, new ExtractedEntity(
                    senderId, senderName != null ? senderName : senderKey,
                    ENTITY_PERSON, null, "Email sender: " + (senderName != null ? senderName : senderKey),
                    1.0, senderProps));
            relations.add(new ExtractedRelation(
                    docEntityId, senderId, REL_SENT_BY,
                    displayTitle + " sent by " + (senderName != null ? senderName : senderKey),
                    1.0, null));

            // Recipients → PERSON entities + SENT_TO relations
            String emailTo = str(meta.get(META_EMAIL_TO));
            if (emailTo != null) {
                for (String recipient : emailTo.split("[;,]")) {
                    String r = recipient.trim();
                    if (r.isEmpty()) continue;
                    String recipientId = entityId("person:" + r.toLowerCase());
                    Map<String, String> rProps = new LinkedHashMap<>();
                    rProps.put("displayName", r);
                    addEntity(entityIndex, new ExtractedEntity(
                            recipientId, r, ENTITY_PERSON, null, "Email recipient", 1.0, rProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, recipientId, REL_SENT_TO,
                            displayTitle + " sent to " + r, 1.0, null));
                }
            }

            // CC → CC_TO relations
            String emailCc = str(meta.get(META_EMAIL_CC));
            if (emailCc != null) {
                for (String cc : emailCc.split("[;,]")) {
                    String c = cc.trim();
                    if (c.isEmpty()) continue;
                    String ccId = entityId("person:" + c.toLowerCase());
                    addEntity(entityIndex, new ExtractedEntity(
                            ccId, c, ENTITY_PERSON, null, "Email CC recipient", 0.9,
                            Map.of("displayName", c)));
                    relations.add(new ExtractedRelation(
                            docEntityId, ccId, REL_CC_TO,
                            displayTitle + " CC'd to " + c, 0.9, null));
                }
            }

            // BCC → BCC_TO relations
            String emailBcc = str(meta.get(META_EMAIL_BCC));
            if (emailBcc != null) {
                for (String bcc : emailBcc.split("[;,]")) {
                    String b = bcc.trim();
                    if (b.isEmpty()) continue;
                    String bccId = entityId("person:" + b.toLowerCase());
                    Map<String, String> bccProps = new LinkedHashMap<>();
                    bccProps.put("displayName", b);
                    if (b.contains("@")) bccProps.put("email", b);
                    addEntity(entityIndex, new ExtractedEntity(
                            bccId, b, ENTITY_PERSON, null, "Email BCC recipient", 0.9,
                            bccProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, bccId, REL_BCC_TO,
                            displayTitle + " BCC'd to " + b, 0.9, null));
                }
            }

            // Threading: REPLIED_TO from email.inReplyTo
            String inReplyTo = str(meta.get(META_EMAIL_IN_REPLY_TO));
            if (inReplyTo != null) {
                String parentMsgId = entityId("email_msg:" + inReplyTo);
                addEntity(entityIndex, new ExtractedEntity(
                        parentMsgId, "Message " + inReplyTo, ENTITY_OUTLOOK_MESSAGE,
                        null, "Referenced email message", 0.5,
                        Map.of("messageId", inReplyTo)));
                relations.add(new ExtractedRelation(
                        docEntityId, parentMsgId, REL_REPLIED_TO,
                        displayTitle + " replies to " + inReplyTo, 1.0, null));
            }

            // References chain: REFERENCES relations to each message-id in email.references
            Object refsObj = meta.get(META_EMAIL_REFERENCES);
            List<String> refsList = null;
            if (refsObj instanceof List<?> rawList) {
                refsList = new ArrayList<>();
                for (Object item : rawList) {
                    String s = str(item);
                    if (s != null) refsList.add(s);
                }
            } else if (refsObj instanceof String refsStr) {
                refsList = new ArrayList<>();
                for (String s : refsStr.trim().split("[\\s,]+")) {
                    if (!s.isBlank()) refsList.add(s);
                }
            }
            if (refsList != null) {
                for (String ref : refsList) {
                    if (ref == null || ref.isBlank()) continue;
                    // Skip the inReplyTo message — already handled as REPLIED_TO above
                    if (ref.equals(inReplyTo)) continue;
                    String refEntityId = entityId("email_msg:" + ref);
                    addEntity(entityIndex, new ExtractedEntity(
                            refEntityId, "Message " + ref, ENTITY_OUTLOOK_MESSAGE,
                            null, "Referenced email message", 0.5,
                            Map.of("messageId", ref)));
                    relations.add(new ExtractedRelation(
                            docEntityId, refEntityId, REL_REFERENCES,
                            displayTitle + " references " + ref, 0.9, null));
                }
            }

            // Conversation topic
            String convTopic = str(meta.get(META_EMAIL_CONVERSATION_TOPIC));
            if (convTopic != null) {
                String topicId = entityId("conv_topic:" + convTopic.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(
                        topicId, convTopic, ENTITY_CONVERSATION_TOPIC,
                        null, "Email conversation: " + convTopic, 0.9,
                        Map.of("topic", convTopic)));
                relations.add(new ExtractedRelation(
                        docEntityId, topicId, REL_HAS_CONVERSATION_TOPIC,
                        displayTitle + " in conversation: " + convTopic, 0.9, null));
            }

            // EMAIL_THREAD entity — derive from conversation topic or subject fallback
            String threadKey = convTopic != null ? convTopic : emailSubject;
            if (threadKey != null && !threadKey.isBlank()) {
                String threadId = entityId("thread:" + threadKey.toLowerCase().trim());
                Map<String, String> threadProps = new LinkedHashMap<>();
                threadProps.put("threadSubject", threadKey);
                String msgIdForThread = str(meta.get(META_EMAIL_MESSAGE_ID));
                if (msgIdForThread != null) threadProps.put("latestMessageId", msgIdForThread);
                addEntity(entityIndex, new ExtractedEntity(
                        threadId, threadKey, ENTITY_EMAIL_THREAD,
                        null, "Email thread: " + threadKey, 0.85, threadProps));
                relations.add(new ExtractedRelation(
                        docEntityId, threadId, REL_IN_THREAD,
                        displayTitle + " is part of thread: " + threadKey, 0.85, null));
            }

            // PST folder → FOLDER entity hierarchy + IN_FOLDER relation
            String folder = str(meta.get(META_EMAIL_PST_FOLDER));
            if (folder == null) folder = str(meta.get(META_EMAIL_FOLDER));
            if (folder != null) {
                // Split folder path to build hierarchy
                String separator = folder.contains("/") ? "/" : (folder.contains("\\") ? "\\." : null);
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
                    folderProps.put(PROP_FOLDER_NAME, part);
                    folderProps.put(PROP_FOLDER_PATH, pathSoFar.toString());
                    folderProps.put(PROP_DEPTH, String.valueOf(fi));
                    addEntity(entityIndex, new ExtractedEntity(
                            currentFolderId, part, ENTITY_EMAIL_FOLDER,
                            null, "Email folder: " + pathSoFar, 0.9, folderProps));
                    if (previousFolderId != null) {
                        relations.add(new ExtractedRelation(
                                currentFolderId, previousFolderId, REL_SUBFOLDER_OF,
                                part + " is subfolder of " + parts[fi - 1],
                                0.9, ExtractorUtils.PROVENANCE_MAP));
                    }
                    previousFolderId = currentFolderId;
                    leafFolderEntityId = currentFolderId;
                }
                if (leafFolderEntityId != null) {
                    relations.add(new ExtractedRelation(
                            docEntityId, leafFolderEntityId, REL_IN_FOLDER,
                            "Email in folder " + folder,
                            1.0, ExtractorUtils.PROVENANCE_MAP));
                }
            }

            // Mailing list entity + POSTED_TO relation
            String listId = str(meta.get(META_EMAIL_LIST_ID));
            if (listId != null) {
                String listEntityId = entityId("list:" + listId);
                Map<String, String> listProps = new LinkedHashMap<>();
                listProps.put("listId", listId);
                ExtractedEntity listEntity = new ExtractedEntity(
                        listEntityId, listId, ENTITY_MAILING_LIST,
                        null, "Mailing list: " + listId, 1.0, listProps);
                addEntity(entityIndex, listEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, listEntityId, REL_POSTED_TO,
                        "Email posted to mailing list " + listId,
                        1.0, ExtractorUtils.PROVENANCE_MAP));
            }

            // Attachment metadata
            Object attachNames = meta.get(META_EMAIL_ATTACHMENT_NAMES);
            String attachMimeType = str(meta.get(META_EMAIL_ATTACHMENT_MIME_TYPE));
            if (attachNames instanceof List<?> nameList) {
                for (int i = 0; i < nameList.size(); i++) {
                    String attName = str(nameList.get(i));
                    if (attName == null) continue;
                    String attId = entityId("email_att:" + (source != null ? source : "") + ":" + attName);
                    Map<String, String> attProps = new LinkedHashMap<>();
                    attProps.put("filename", attName);
                    // Try per-attachment MIME type first, fall back to global
                    String perAttMime = str(meta.get("email.attachment." + i + ".mimeType"));
                    if (perAttMime != null) {
                        attProps.put("mimeType", perAttMime);
                    } else if (attachMimeType != null) {
                        attProps.put("mimeType", attachMimeType);
                    }
                    addEntity(entityIndex, new ExtractedEntity(
                            attId, attName, ENTITY_EMAIL_ATTACHMENT,
                            null, "Email attachment: " + attName, 0.95,
                            attProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, attId, REL_HAS_ATTACHMENT,
                            displayTitle + " has attachment: " + attName, 0.95, null));
                }
            }

            // Store email date on document entity
            String emailDate = str(meta.get(META_EMAIL_DATE));
            if (emailDate != null) docProps.put("date", emailDate);
            String emailMsgId = str(meta.get(META_EMAIL_MESSAGE_ID));
            if (emailMsgId != null) docProps.put("messageId", emailMsgId);
            if (emailSubject != null) docProps.put("subject", emailSubject);

            // Additional message header props: replyTo, returnPath, autoSubmitted, flags
            ExtractorUtils.copyMetaToProps(docProps, meta,
                    META_EMAIL_REPLY_TO, PROP_REPLY_TO,
                    META_EMAIL_RETURN_PATH, PROP_RETURN_PATH,
                    META_EMAIL_AUTO_SUBMITTED, PROP_AUTO_SUBMITTED,
                    META_EMAIL_PRECEDENCE, "precedence",
                    META_EMAIL_FLAG_SEEN, "flagSeen",
                    META_EMAIL_FLAG_FLAGGED, "flagFlagged",
                    META_EMAIL_FLAG_DRAFT, "flagDraft",
                    META_EMAIL_FLAG_ANSWERED, "flagAnswered",
                    META_EMAIL_FLAG_DELETED, "flagDeleted"
            );
            if (Boolean.TRUE.equals(meta.get(META_EMAIL_IS_AUTO_REPLY)))
                docProps.put(PROP_IS_AUTO_REPLY, "true");

            String mailer = str(meta.get(META_EMAIL_MAILER));
            if (mailer != null) docProps.put("mailer", mailer);
            String userAgent = str(meta.get(META_EMAIL_USER_AGENT));
            if (userAgent != null) docProps.put("userAgent", userAgent);

            String priority = str(meta.get(META_EMAIL_PRIORITY));
            if (priority == null) priority = str(meta.get(META_EMAIL_IMPORTANCE));
            if (priority != null) docProps.put("priority", priority);
            Object attachmentCount = meta.get("email.attachmentCount");
            if (attachmentCount != null) docProps.put("attachmentCount", attachmentCount.toString());

            // DATE entity from email.date
            if (emailDate != null) {
                String emailDateId = entityId("date:" + emailDate);
                addEntity(entityIndex, new ExtractedEntity(
                        emailDateId, emailDate, ENTITY_DATE,
                        null, "Email date: " + emailDate, 0.9,
                        Map.of("date", emailDate, "dateType", "sent")));
                relations.add(new ExtractedRelation(
                        docEntityId, emailDateId, REL_PUBLISHED_ON,
                        displayTitle + " sent on " + emailDate,
                        0.9, null));
            }

            // Extract inline URLs from email body → HYPERLINKS_TO → EXTERNAL_RESOURCE
            String bodyContent = doc.getText();
            if (bodyContent != null && !bodyContent.isBlank()) {
                Set<String> seenUrls = new LinkedHashSet<>();
                Matcher urlMatcher = URL_PATTERN.matcher(bodyContent);
                while (urlMatcher.find() && seenUrls.size() < 50) {
                    String url = urlMatcher.group();
                    if (seenUrls.add(url)) {
                        String urlEntityId = entityId("url:" + url);
                        addEntity(entityIndex, new ExtractedEntity(
                                urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "URL referenced in email", 0.8,
                                Map.of("url", url)));
                        relations.add(new ExtractedRelation(
                                docEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                                "Email contains link to " + url, 0.8, null));
                    }
                }
            }

            // Extract hyperlinks from HTML body (MSG files set email.htmlBody with <a href> tags)
            String htmlBody = str(meta.get(META_EMAIL_HTML_BODY));
            if (htmlBody != null && !htmlBody.isBlank()) {
                Set<String> seenHtmlUrls = new LinkedHashSet<>();
                java.util.regex.Pattern hrefPattern = java.util.regex.Pattern.compile(
                        "<a\\s+[^>]*href=[\"']?(https?://[^\"'\\s>]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher hrefMatcher = hrefPattern.matcher(htmlBody);
                while (hrefMatcher.find() && seenHtmlUrls.size() < 50) {
                    String href = hrefMatcher.group(1);
                    if (seenHtmlUrls.add(href)) {
                        String hrefEntityId = entityId("url:" + href);
                        addEntity(entityIndex, new ExtractedEntity(
                                hrefEntityId, href, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "Hyperlink in email HTML body", 0.8,
                                Map.of("url", href, PROP_SOURCE_FIELD, "email.htmlBody")));
                        relations.add(new ExtractedRelation(
                                docEntityId, hrefEntityId, GraphConstants.REL_HYPERLINKS_TO,
                                "Email HTML body links to " + href, 0.8, null));
                    }
                }
            }

            // Inline CID images → EMBEDDED_IMAGE entities + HAS_IMAGE relations
            @SuppressWarnings("unchecked")
            List<Map<String, String>> inlineImages = meta.get(META_EMAIL_INLINE_IMAGES) instanceof List<?>
                    ? (List<Map<String, String>>) meta.get(META_EMAIL_INLINE_IMAGES) : null;
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
                            imgId, imgName, ENTITY_EMBEDDED_IMAGE,
                            null, "Inline image: " + imgName, 0.85, imgProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, imgId, REL_HAS_IMAGE,
                            displayTitle + " has inline image: " + imgName,
                            0.85, null));
                }
            }

            // Received headers → MAIL_SERVER entities + ROUTED_VIA chain
            Object receivedObj = meta.get(META_EMAIL_RECEIVED_HEADERS);
            if (receivedObj instanceof List<?> receivedList && !receivedList.isEmpty()) {
                String previousServerId = null;
                for (int ri = 0; ri < receivedList.size() && ri < 20; ri++) {
                    String header = str(receivedList.get(ri));
                    if (header == null || header.isBlank()) continue;
                    String byHost = parseReceivedHost(header, "by");
                    if (byHost == null) continue;
                    String serverId = entityId("mailserver:" + byHost.toLowerCase());
                    addEntity(entityIndex, new ExtractedEntity(
                            serverId, byHost, ENTITY_MAIL_SERVER,
                            null, "Mail server: " + byHost, 0.8,
                            Map.of("hostname", byHost, "hopIndex", String.valueOf(ri))));
                    if (ri == 0) {
                        relations.add(new ExtractedRelation(
                                docEntityId, serverId, REL_ROUTED_VIA,
                                "Email delivered via " + byHost, 0.85, null));
                    }
                    if (previousServerId != null && !previousServerId.equals(serverId)) {
                        relations.add(new ExtractedRelation(
                                previousServerId, serverId, REL_ROUTED_VIA,
                                "Mail relay chain", 0.75, null));
                    }
                    previousServerId = serverId;
                }
            }

            // X-Mailer / User-Agent → EMAIL_CLIENT entity + SENT_WITH relation
            if (mailer != null) {
                String clientId = entityId("email_client:" + mailer.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(clientId, mailer,
                        ENTITY_EMAIL_CLIENT, null, "Email client: " + mailer, 0.9,
                        Map.of("software", mailer, "headerSource", "X-Mailer")));
                relations.add(new ExtractedRelation(docEntityId, clientId,
                        REL_SENT_WITH, "Email sent with " + mailer, 0.9, null));
            } else if (userAgent != null) {
                String clientId = entityId("email_client:" + userAgent.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(clientId, userAgent,
                        ENTITY_EMAIL_CLIENT, null, "Email client: " + userAgent, 0.9,
                        Map.of("software", userAgent, "headerSource", "User-Agent")));
                relations.add(new ExtractedRelation(docEntityId, clientId,
                        REL_SENT_WITH, "Email sent with " + userAgent, 0.9, null));
            }

            // List-Unsubscribe → EXTERNAL_RESOURCE entities
            String listUnsub = str(meta.get(META_EMAIL_LIST_UNSUBSCRIBE));
            if (listUnsub != null) {
                Matcher unsubMatcher = Pattern.compile("<([^>]+)>").matcher(listUnsub);
                while (unsubMatcher.find()) {
                    String unsubUri = unsubMatcher.group(1);
                    String unsubId = entityId("unsub:" + unsubUri.toLowerCase());
                    Map<String, String> unsubProps = new LinkedHashMap<>();
                    unsubProps.put("uri", unsubUri);
                    unsubProps.put("type", unsubUri.startsWith("mailto:") ? "email" : "url");
                    addEntity(entityIndex, new ExtractedEntity(unsubId,
                            "Unsubscribe: " + unsubUri, ENTITY_EXTERNAL_RESOURCE,
                            null, "List unsubscribe endpoint", 0.8, unsubProps));
                    relations.add(new ExtractedRelation(docEntityId, unsubId,
                            REL_HYPERLINKS_TO, "Email has unsubscribe link: " + unsubUri,
                            0.8, null));
                }
            }

            // DKIM/SPF/DMARC → entity properties
            String dkimResult = str(meta.get(META_EMAIL_DKIM_RESULT));
            if (dkimResult != null) docProps.put("dkimResult", dkimResult);
            String spfResult = str(meta.get(META_EMAIL_SPF_RESULT));
            if (spfResult != null) docProps.put("spfResult", spfResult);
            String dmarcResult = str(meta.get(META_EMAIL_DMARC_RESULT));
            if (dmarcResult != null) docProps.put("dmarcResult", dmarcResult);
        }

        // ── Cell-level table graph from META_TABLE_GRAPH ──────────────────
        // Converts the JSON Graph model (from TableCellGraphBuilder) into
        // ExtractedEntity/ExtractedRelation so cell-level data from Word tables,
        // Excel sheets, and PowerPoint table slides appears in the knowledge graph.
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
                log.warn("Failed to parse tableGraph JSON for Office document: {}", e.getMessage());
            }
        }

        // ── Extract URLs from body text for non-email Office docs ─────────
        // Email documents already have URL extraction above; for Word/Excel/PPT,
        // catch bare URLs in body text not captured by docx.hyperlinks metadata.
        if (!ENTITY_OUTLOOK_MESSAGE.equals(entityType)) {
            String bodyContent = doc.getText();
            if (bodyContent != null && !bodyContent.isBlank()) {
                Set<String> seenBodyUrls = new LinkedHashSet<>();
                Matcher urlMatcher = URL_PATTERN.matcher(bodyContent);
                while (urlMatcher.find() && seenBodyUrls.size() < 50) {
                    String url = urlMatcher.group();
                    if (seenBodyUrls.add(url)) {
                        String urlEntityId = entityId("url:" + url);
                        addEntity(entityIndex, new ExtractedEntity(
                                urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "URL referenced in document", 0.75,
                                Map.of("url", url)));
                        relations.add(new ExtractedRelation(
                                docEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                                displayTitle + " contains link to " + url, 0.75, null));
                    }
                }
            }
        }

        // Stamp occurredAt on every relation's properties map
        if (occurredAt != null) {
            relations.replaceAll(r -> {
                Map<String, String> props = new LinkedHashMap<>(r.properties() != null ? r.properties() : Map.of());
                props.putIfAbsent("occurredAt", occurredAt);
                return new ExtractedRelation(r.source(), r.target(), r.type(), r.description(), r.confidence(), props);
            });
        }

        entities.addAll(entityIndex.values());

        ExtractionMetadata extractionMeta = new ExtractionMetadata(
                source, source, SOURCE_OFFICE_EXTRACTOR, null, null, null
        );

        return ExtractionResult.of(entities, relations, extractionMeta);
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        return ExtractorUtils.extractBatch(this, docs, SOURCE_OFFICE_EXTRACTOR);
    }

    // ── Utility ───────────────────────────────────────────────────────────

    /**
     * Parses a hostname from a Received header for a given keyword ("from" or "by").
     */
    private static String parseReceivedHost(String receivedHeader, String keyword) {
        if (receivedHeader == null || keyword == null) return null;
        Pattern p = Pattern.compile("\\b" + keyword + "\\s+([a-zA-Z0-9._\\-]+\\.[a-zA-Z]{2,})",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(receivedHeader);
        if (m.find()) {
            String host = m.group(1);
            if (host.length() > 3 && host.contains(".")) return host;
        }
        return null;
    }

    private String resolveEntityType(String docType, String fileName) {
        if (docType != null) {
            String lower = docType.toLowerCase();
            if (lower.contains("word") || lower.contains("odt")) return ENTITY_WORD_DOCUMENT;
            if (lower.contains("spreadsheet") || lower.contains("excel") || lower.contains("ods")) return ENTITY_SPREADSHEET;
            if (lower.contains("presentation") || lower.contains("powerpoint") || lower.contains("odp")) return ENTITY_PRESENTATION;
            if (lower.contains("access") || lower.contains("database")) return ENTITY_DATABASE;
            if (lower.contains("msg") || lower.contains("outlook")) return ENTITY_OUTLOOK_MESSAGE;
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".odt")) return ENTITY_WORD_DOCUMENT;
            if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || lower.endsWith(".ods")) return ENTITY_SPREADSHEET;
            if (lower.endsWith(".ppt") || lower.endsWith(".pptx") || lower.endsWith(".odp")) return ENTITY_PRESENTATION;
            if (lower.endsWith(".mdb") || lower.endsWith(".accdb")) return ENTITY_DATABASE;
            if (lower.endsWith(".msg")) return ENTITY_OUTLOOK_MESSAGE;
        }
        return ENTITY_OFFICE_DOCUMENT;
    }
}
