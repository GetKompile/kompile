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

package ai.kompile.loader.tika;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Fallback graph extractor for documents parsed by Tika that aren't handled by
 * more specific extractors (PDF, Office, HTML).
 *
 * <p>Covers: RTF, EPUB, plain text, images, and any other Tika-parsed format
 * that has author/title/keywords metadata.</p>
 *
 * <p>Entity types: DOCUMENT, PERSON, TOPIC, ORGANIZATION</p>
 * <p>Relationship types: AUTHORED_BY, HAS_TOPIC, PRODUCED_BY</p>
 */
@Component
public class TikaGenericGraphExtractor implements DocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaGenericGraphExtractor.class);

    private static final Pattern URL_PATTERN =
            Pattern.compile("(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    /** Matches Markdown-style links: [anchor text](url) */
    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile("\\[([^\\]]+)\\]\\(([a-zA-Z][a-zA-Z0-9+.-]*://[^)]+)\\)");

    /** Matches Markdown-style images: ![alt text](src) */
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /** Matches LaTeX display math: $$...$$ or \[...\] */
    private static final Pattern LATEX_DISPLAY_MATH_PATTERN = Pattern.compile(
            "\\$\\$(.+?)\\$\\$|\\\\\\[(.+?)\\\\\\]", Pattern.DOTALL);

    /** Matches LaTeX inline math: $...$ (not $$) */
    private static final Pattern LATEX_INLINE_MATH_PATTERN = Pattern.compile(
            "(?<!\\$)\\$(?!\\$)(.+?)(?<!\\$)\\$(?!\\$)");

    /** Matches fenced code blocks: ```...``` */
    private static final Pattern FENCED_CODE_BLOCK_PATTERN = Pattern.compile(
            "^```(\\w*)\\n(.*?)^```", Pattern.MULTILINE | Pattern.DOTALL);

    /** Matches wiki-style links: [[Page Name]] or [[Page Name|Display Text]] */
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile(
            "\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Matches Markdown task list items: - [ ] or - [x] */
    private static final Pattern TASK_ITEM_PATTERN = Pattern.compile(
            "^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.+)$", Pattern.MULTILINE);

    /** Matches VLM-generated [Image: objectId] markers in body text */
    private static final Pattern VLM_IMAGE_MARKER_PATTERN = Pattern.compile(
            "\\[Image:\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);

    /** Matches markdown-style headings in VLM body text */
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /** Matches pipe-table separator row: | --- | --- | */
    private static final Pattern PIPE_TABLE_SEPARATOR = Pattern.compile(
            "^\\|?\\s*:?-{3,}:?\\s*\\|", Pattern.MULTILINE);

    private static final Pattern EMAIL_ADDRESS_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");

    private static final Set<String> CLAIMED_DOC_TYPES = Set.of(
            "pdf", "word", "spreadsheet", "excel", "presentation", "powerpoint",
            "html", "odt", "ods", "odp",
            "docx", "xlsx", "pptx",
            "email", "outlook", "msg"
    );

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("rtf", "epub", "text", "image", "tika");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();

        // Skip types handled by more specific extractors
        String docType = str(meta.get(META_DOCUMENT_TYPE));
        if (docType != null) {
            String lower = docType.toLowerCase();
            for (String claimed : CLAIMED_DOC_TYPES) {
                if (lower.contains(claimed)) return false;
            }
        }

        // Skip email documents and attachments — EmailGraphExtractor handles these
        if (meta.get(META_EMAIL_FROM) != null || "email".equals(str(meta.get(META_CONTENT_TYPE_HINT)))) {
            return false;
        }
        String sourceType = str(meta.get(META_SOURCE_TYPE));
        if ("EMAIL_ATTACHMENT".equals(sourceType) || "OUTLOOK_PST".equals(sourceType)
                || "EMAIL_INBOX".equals(sourceType)) {
            return false;
        }
        if (docType != null && docType.toLowerCase().contains("email_attachment")) {
            return false;
        }

        // Accept Tika-loaded documents
        String loader = str(meta.get(META_LOADER));
        if (loader != null && loader.toLowerCase().contains("tika")) return true;

        // Accept OCR-processed non-PDF images (png, jpg, tiff, bmp)
        // PDF OCR docs are handled by PdfGraphExtractor
        Object ocrProcessed = meta.get(META_OCR_PROCESSED);
        if (Boolean.TRUE.equals(ocrProcessed)) {
            String fileName = str(meta.get(META_FILE_NAME));
            if (fileName != null && !fileName.toLowerCase().endsWith(".pdf")) {
                return true;
            }
        }

        // Accept plain text, CSV, and other text-based files regardless of loader —
        // these common formats may be loaded by direct file readers without Tika
        // and still deserve graph extraction (author, keywords, URLs, etc.)
        String fileName = str(meta.get(META_FILE_NAME));
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".tsv")
                    || lower.endsWith(".log") || lower.endsWith(".md") || lower.endsWith(".json")
                    || lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                    || lower.endsWith(".rtf") || lower.endsWith(".epub")
                    || lower.endsWith(".ics") || lower.endsWith(".vcf")
                    // Audio formats
                    || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")
                    || lower.endsWith(".ogg") || lower.endsWith(".aac") || lower.endsWith(".wma")
                    || lower.endsWith(".m4a") || lower.endsWith(".opus")
                    // Video formats
                    || lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mkv")
                    || lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".webm")
                    || lower.endsWith(".flv") || lower.endsWith(".m4v")
                    // Image formats
                    || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".tiff")
                    || lower.endsWith(".tif") || lower.endsWith(".webp") || lower.endsWith(".svg")
                    || lower.endsWith(".ico") || lower.endsWith(".heic") || lower.endsWith(".heif")) {
                return true;
            }
        }

        // Accept audio/video/image document types that resolveEntityType() can handle
        if (docType != null) {
            String lowerDocType = docType.toLowerCase();
            if (lowerDocType.contains("audio") || lowerDocType.contains("video")
                    || lowerDocType.contains("image")) {
                return true;
            }
            // Accept YouTube transcripts, OCR structural elements, web pages,
            // and generic file fallback types that no specific extractor claims
            if (lowerDocType.contains("youtube") || lowerDocType.contains("transcript")
                    || lowerDocType.startsWith("ocr_")
                    || lowerDocType.equals("web_page")
                    || lowerDocType.equals("file")) {
                return true;
            }
        }

        // Accept documents with content_type indicating text, audio, or video
        String contentType = str(meta.get(META_CONTENT_TYPE));
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.startsWith("text/") || "application/json".equals(lower)
                    || "application/xml".equals(lower) || "application/csv".equals(lower)
                    || lower.startsWith("audio/") || lower.startsWith("video/")
                    || lower.startsWith("image/")
                    // Also match bare content types set by TikaLoaderImpl ("audio", "video", "image")
                    || "audio".equals(lower) || "video".equals(lower) || "image".equals(lower)) {
                return true;
            }
        }

        // Final fallback: accept documents with generic/unknown document types
        // that passed the CLAIMED_DOC_TYPES filter above. These include:
        //   "Unknown (Parse Error)", "Unknown Document", "Document",
        //   "Google Drive File", "text", or any other type not claimed by
        //   a specific extractor. This ensures every document gets at least
        //   basic graph extraction (DOCUMENT entity, author, dates, URLs).
        if (docType != null) {
            return true;
        }

        return false;
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

        // ── DOCUMENT entity ─────────────────────────────────────────────
        String fileName = str(meta.get(META_FILE_NAME));
        String title = str(meta.get(META_TITLE));
        String source = str(meta.get(META_SOURCE));
        String docType = str(meta.get(META_DOCUMENT_TYPE));
        String displayTitle = title != null ? title : (fileName != null ? fileName : "Untitled Document");

        String docEntityId = entityId("tika:" + (source != null ? source : displayTitle));
        Map<String, String> docProps = new LinkedHashMap<>();
        if (fileName != null) docProps.put(META_FILE_NAME, fileName);
        if (title != null) docProps.put(META_TITLE, title);
        if (docType != null) docProps.put(META_DOCUMENT_TYPE, docType);
        putIfPresent(docProps, META_SUBJECT, meta, META_SUBJECT);
        putIfPresent(docProps, META_DESCRIPTION, meta, META_DESCRIPTION);
        putIfPresent(docProps, META_LANGUAGE, meta, META_LANGUAGE);
        putValueIfPresent(docProps, META_FILE_SIZE, meta, META_FILE_SIZE);
        putValueIfPresent(docProps, META_PAGE_COUNT, meta, META_PAGE_COUNT);
        putValueIfPresent(docProps, META_CREATION_DATE, meta, META_CREATION_DATE);
        putValueIfPresent(docProps, META_MODIFICATION_DATE, meta, META_MODIFICATION_DATE);
        putIfPresent(docProps, PROP_CONTENT_TYPE, meta, META_TIKA_CONTENT_TYPE);

        // lastModified fallback when modificationDate absent
        if (docProps.get(META_MODIFICATION_DATE) == null) {
            putIfPresent(docProps, META_LAST_MODIFIED, meta, META_LAST_MODIFIED);
        }

        // OCR metadata enrichment
        Object ocrProcessed = meta.get(META_OCR_PROCESSED);
        if (Boolean.TRUE.equals(ocrProcessed)) {
            docProps.put(PROP_OCR_PROCESSED, "true");
            String pdfMode = str(meta.get(META_PDF_PROCESSING_MODE));
            if (pdfMode != null) docProps.put(PROP_PROCESSING_MODE, pdfMode);
            String vlmModel = str(meta.get(META_VLM_MODEL));
            if (vlmModel != null) docProps.put(PROP_VLM_MODEL, vlmModel);
            String ocrConf = str(meta.get(META_OCR_CONFIDENCE));
            if (ocrConf != null) docProps.put(META_OCR_CONFIDENCE, ocrConf);
        }

        // EXIF/image metadata enrichment (populated by TikaLoaderImpl for image files)
        putIfPresent(docProps, "imageWidth", meta, "image.width");
        putIfPresent(docProps, "imageHeight", meta, "image.height");
        putIfPresent(docProps, "cameraMake", meta, "image.make");
        putIfPresent(docProps, "cameraModel", meta, "image.model");
        putIfPresent(docProps, "imageSoftware", meta, "image.software");
        putIfPresent(docProps, "imageDateTime", meta, "image.dateTime");
        putIfPresent(docProps, "geoLatitude", meta, "geo.lat");
        putIfPresent(docProps, "geoLongitude", meta, "geo.long");
        putIfPresent(docProps, "geoAltitude", meta, "geo.altitude");
        putIfPresent(docProps, "exposureTime", meta, "exif.exposureTime");
        putIfPresent(docProps, "fNumber", meta, "exif.fNumber");
        putIfPresent(docProps, "isoSpeed", meta, "exif.isoSpeedRatings");
        putIfPresent(docProps, "focalLength", meta, "exif.focalLength");
        putIfPresent(docProps, "flash", meta, "exif.flash");
        putIfPresent(docProps, "whiteBalance", meta, "exif.whiteBalance");
        putIfPresent(docProps, "dateTimeOriginal", meta, "exif.dateTimeOriginal");
        putIfPresent(docProps, "dateTimeDigitized", meta, "exif.dateTimeDigitized");
        putIfPresent(docProps, "orientation", meta, "image.orientation");
        putIfPresent(docProps, "bitsPerSample", meta, "image.bitsPerSample");
        putIfPresent(docProps, "compression", meta, "image.compression");
        putIfPresent(docProps, "xResolution", meta, "image.xResolution");
        putIfPresent(docProps, "yResolution", meta, "image.yResolution");

        String entityType = resolveEntityType(docType, meta);
        ExtractedEntity docEntity = new ExtractedEntity(
                docEntityId, displayTitle, entityType,
                null, entityType + ": " + displayTitle, 1.0, docProps
        );
        addEntity(entityIndex, docEntity);

        // ── PERSON from author ──────────────────────────────────────────
        String author = str(meta.get(META_AUTHOR));
        if (author != null) {
            extractAuthors(author, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── ORGANIZATION from producer/applicationName/publisher ─────────
        String producer = str(meta.get(META_PRODUCER));
        if (producer == null) producer = str(meta.get(META_APPLICATION_NAME));
        if (producer == null) producer = str(meta.get("publisher"));
        if (producer != null) {
            extractProducer(producer, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── Enrich doc entity with Dublin Core metadata ─────────────────
        String identifier = str(meta.get("identifier"));
        if (identifier != null) {
            docProps.put("identifier", identifier);
            // For EPUB documents, identifier is typically the ISBN
            if (ENTITY_EPUB_DOCUMENT.equals(entityType)) {
                String idEntityId = entityId("isbn:" + identifier.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(
                        idEntityId, identifier, ENTITY_IDENTIFIER,
                        null, "ISBN/Identifier: " + identifier, 0.9,
                        Map.of("identifier", identifier, "identifierType",
                                identifier.replaceAll("[^0-9Xx]", "").length() >= 10 ? "ISBN" : "URI")));
                relations.add(new ExtractedRelation(
                        docEntityId, idEntityId, REL_HAS_IDENTIFIER,
                        displayTitle + " has identifier: " + identifier, 0.9, null));
            }
        }
        String rights = str(meta.get("rights"));
        if (rights != null) docProps.put("rights", rights);
        String contributor = str(meta.get("contributor"));
        if (contributor != null) {
            docProps.put("contributor", contributor);
            // Create PERSON entities for contributors (similar to authors)
            extractAuthors(contributor, docEntityId, displayTitle, entityIndex, relations);
        }
        String publisher = str(meta.get(META_PUBLISHER));
        if (publisher != null) {
            docProps.put("publisher", publisher);
            // If publisher differs from producer, create a separate ORGANIZATION entity
            if (!publisher.equalsIgnoreCase(producer)) {
                String pubOrgId = entityId("org:" + publisher.toLowerCase());
                Map<String, String> pubOrgProps = new LinkedHashMap<>();
                pubOrgProps.put("name", publisher);
                pubOrgProps.put(PROP_SOURCE_FIELD, "publisher");
                addEntity(entityIndex, new ExtractedEntity(
                        pubOrgId, publisher, ENTITY_ORGANIZATION,
                        null, "Publisher: " + publisher, 0.85, pubOrgProps));
                relations.add(new ExtractedRelation(
                        docEntityId, pubOrgId, REL_PUBLISHED_BY,
                        displayTitle + " published by " + publisher, 0.85, null));
            }
        }
        // Dublin Core: coverage (geographic/temporal scope), relation (related resource), source (origin)
        String coverage = str(meta.get("coverage"));
        if (coverage != null) docProps.put("coverage", coverage);
        String relation = str(meta.get("relation"));
        if (relation != null) {
            docProps.put("relation", relation);
            // If relation looks like a URL, create an EXTERNAL_RESOURCE entity
            if (relation.startsWith("http://") || relation.startsWith("https://")) {
                String relEntityId = entityId("related_resource:" + relation.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(
                        relEntityId, relation, ENTITY_EXTERNAL_RESOURCE,
                        null, "Related resource: " + relation, 0.8,
                        Map.of("url", relation, PROP_SOURCE_FIELD, "dc:relation")));
                relations.add(new ExtractedRelation(
                        docEntityId, relEntityId, REL_HYPERLINKS_TO,
                        displayTitle + " related to: " + relation, 0.8, null));
            }
        }
        String dcSource = str(meta.get("dcSource"));
        if (dcSource != null) {
            docProps.put("dcSource", dcSource);
            if (dcSource.startsWith("http://") || dcSource.startsWith("https://")) {
                String srcEntityId = entityId("source_resource:" + dcSource.toLowerCase());
                addEntity(entityIndex, new ExtractedEntity(
                        srcEntityId, dcSource, ENTITY_EXTERNAL_RESOURCE,
                        null, "Source resource: " + dcSource, 0.8,
                        Map.of("url", dcSource, PROP_SOURCE_FIELD, "dc:source")));
                relations.add(new ExtractedRelation(
                        docEntityId, srcEntityId, REL_HYPERLINKS_TO,
                        displayTitle + " sourced from: " + dcSource, 0.8, null));
            }
        }

        // ── OLE/RTF-specific: company, manager, category, comments ──────
        String company = str(meta.get("company"));
        if (company != null) {
            docProps.put("company", company);
            String companyId = entityId("org:" + company.toLowerCase());
            Map<String, String> companyProps = new LinkedHashMap<>();
            companyProps.put("name", company);
            companyProps.put(PROP_SOURCE_FIELD, "company");
            addEntity(entityIndex, new ExtractedEntity(
                    companyId, company, ENTITY_ORGANIZATION,
                    null, "Company: " + company, 0.85, companyProps));
            relations.add(new ExtractedRelation(
                    docEntityId, companyId, REL_AFFILIATED_WITH,
                    displayTitle + " affiliated with " + company, 0.85, null));
        }
        String manager = str(meta.get("manager"));
        if (manager != null) {
            docProps.put("manager", manager);
            String managerId = entityId("person:" + manager.toLowerCase());
            Map<String, String> mgrProps = new LinkedHashMap<>();
            mgrProps.put("name", manager);
            mgrProps.put(PROP_SOURCE_FIELD, "manager");
            addEntity(entityIndex, new ExtractedEntity(
                    managerId, manager, ENTITY_PERSON,
                    null, "Manager: " + manager, 0.8, mgrProps));
            relations.add(new ExtractedRelation(
                    docEntityId, managerId, REL_MANAGED_BY,
                    displayTitle + " managed by " + manager, 0.8, null));
        }
        String category = str(meta.get("category"));
        if (category != null) {
            docProps.put("category", category);
            String catId = entityId("topic:" + category.toLowerCase());
            Map<String, String> catProps = new LinkedHashMap<>();
            catProps.put("name", category);
            catProps.put(PROP_SOURCE_FIELD, "category");
            addEntity(entityIndex, new ExtractedEntity(
                    catId, category, ENTITY_TOPIC,
                    null, "Category: " + category, 0.85, catProps));
            relations.add(new ExtractedRelation(
                    docEntityId, catId, REL_HAS_TOPIC,
                    displayTitle + " categorized as " + category, 0.85, null));
        }
        String comments = str(meta.get(META_COMMENTS));
        if (comments != null) {
            docProps.put("comments", comments);
            // Promote document-level comments to a DOCUMENT_COMMENT entity
            String commentId = entityId("comment:" + docEntityId + ":doc_comment");
            Map<String, String> commentProps = new LinkedHashMap<>();
            commentProps.put("text", comments);
            commentProps.put(PROP_SOURCE_FIELD, "comments");
            addEntity(entityIndex, new ExtractedEntity(
                    commentId, comments.length() > 80 ? comments.substring(0, 80) + "..." : comments,
                    ENTITY_DOCUMENT_COMMENT,
                    null, "Document comment: " + (comments.length() > 60 ? comments.substring(0, 60) + "..." : comments),
                    0.8, commentProps));
            relations.add(new ExtractedRelation(
                    docEntityId, commentId, REL_HAS_COMMENT,
                    displayTitle + " has document comment", 0.8, null));
        }

        // ── TOPICs from keywords ────────────────────────────────────────
        String keywords = str(meta.get(META_KEYWORDS));
        if (keywords != null) {
            extractTopics(keywords, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── TOPICs from Markdown frontmatter tags/categories ───────────
        String fmTags = str(meta.get("tags"));
        if (fmTags != null) {
            extractTopics(fmTags, docEntityId, displayTitle, entityIndex, relations);
        }
        String fmCategories = str(meta.get("categories"));
        if (fmCategories != null && !fmCategories.equals(fmTags)) {
            extractTopics(fmCategories, docEntityId, displayTitle, entityIndex, relations);
        }

        // ── Markdown frontmatter properties on DOCUMENT entity ────────
        Object frontmatterObj = meta.get("markdown.frontmatter");
        if (frontmatterObj instanceof Map<?, ?> fmMap) {
            for (Map.Entry<?, ?> entry : fmMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String val = String.valueOf(entry.getValue());
                // Skip keys already promoted as top-level (title, author, date, tags, categories)
                if (Set.of("title", "author", "date", "tags", "categories", "description").contains(key)) continue;
                if (!val.isEmpty()) {
                    docProps.put("frontmatter." + key, val);
                }
            }
        }

        // ── DATE entity from frontmatter date or creationDate ─────────
        String fmDate = str(meta.get("date"));
        if (fmDate == null) fmDate = str(meta.get(META_CREATION_DATE));
        if (fmDate != null) {
            docProps.put("date", fmDate);
            String dateEntityId = entityId("date:" + fmDate);
            addEntity(entityIndex, new ExtractedEntity(
                    dateEntityId, fmDate, ENTITY_DATE,
                    null, "Document date: " + fmDate, 0.85,
                    Map.of("date", fmDate, "dateType", "created")));
            relations.add(new ExtractedRelation(
                    docEntityId, dateEntityId, REL_PUBLISHED_ON,
                    displayTitle + " published on " + fmDate,
                    0.85, null));
        }

        // ── DATE entity from modificationDate / lastModified ─────────
        String modDate = str(meta.get(META_MODIFICATION_DATE));
        if (modDate == null) modDate = str(meta.get(META_LAST_MODIFIED));
        if (modDate != null && !modDate.equals(fmDate)) {
            String modDateId = entityId("date:" + modDate);
            addEntity(entityIndex, new ExtractedEntity(
                    modDateId, modDate, ENTITY_DATE,
                    null, "Modification date: " + modDate, 0.85,
                    Map.of("date", modDate, "dateType", "modified")));
            relations.add(new ExtractedRelation(
                    docEntityId, modDateId, REL_MODIFIED_ON,
                    displayTitle + " modified on " + modDate,
                    0.85, null));
        }

        // ── TABLE entity for CSV documents and any document with table metadata ──
        String contentType = str(meta.get(META_CONTENT_TYPE));
        boolean hasTableGraph = meta.get(META_TABLE_GRAPH) instanceof String tg && !tg.isBlank();
        boolean hasTableMeta = meta.get(META_TABLE_ROW_COUNT) != null || meta.get(META_TABLE_HEADERS) != null;
        if ("table".equals(contentType) || ENTITY_CSV_DOCUMENT.equals(entityType)
                || hasTableGraph || hasTableMeta) {
            String tableName = fileName != null ? fileName : displayTitle;
            String tableEntityId = entityId("tika_table:" + (source != null ? source : tableName));
            Map<String, String> tableProps = new LinkedHashMap<>();
            putValueIfPresent(tableProps, PROP_ROW_COUNT, meta, META_TABLE_ROW_COUNT);
            putValueIfPresent(tableProps, PROP_COLUMN_COUNT, meta, META_TABLE_COLUMN_COUNT);
            String headersValue = str(meta.get(META_TABLE_HEADERS));
            if (headersValue != null) tableProps.put(PROP_HEADERS, headersValue);
            String tikaTblSummary = str(meta.get("table_summary"));
            if (tikaTblSummary != null) tableProps.put("summary", tikaTblSummary);

            ExtractedEntity tableEntity = new ExtractedEntity(
                    tableEntityId, tableName, ENTITY_TABLE,
                    null, "Table: " + tableName, 0.95, tableProps);
            addEntity(entityIndex, tableEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, tableEntityId, REL_HAS_TABLE,
                    displayTitle + " has table: " + tableName, 0.95, null));

            // Create individual COLUMN entities for each header
            if (headersValue != null && !headersValue.isBlank()) {
                String[] headers = headersValue.split(",");
                for (int i = 0; i < headers.length; i++) {
                    String colName = headers[i].trim();
                    if (colName.isEmpty()) continue;
                    String colEntityId = entityId("tika_col:" + tableEntityId + ":" + i + ":" + colName.toLowerCase());
                    Map<String, String> colProps = new LinkedHashMap<>();
                    colProps.put(PROP_COLUMN_NAME, colName);
                    colProps.put("columnIndex", String.valueOf(i));
                    ExtractedEntity colEntity = new ExtractedEntity(
                            colEntityId, colName, ENTITY_COLUMN,
                            null, "Column: " + colName, 0.9, colProps);
                    addEntity(entityIndex, colEntity);
                    relations.add(new ExtractedRelation(
                            tableEntityId, colEntityId, REL_HAS_COLUMN,
                            tableName + " has column: " + colName, 0.9, null));
                }
            }
        }

        // ── Cell-level graph from META_TABLE_GRAPH (produced by TableCellGraphBuilder) ──
        Object tableGraphObj = meta.get(META_TABLE_GRAPH);
        if (tableGraphObj instanceof String tableGraphJson && !tableGraphJson.isBlank()) {
            try {
                Graph cellGraph = OBJECT_MAPPER.readValue(tableGraphJson, Graph.class);
                if (cellGraph.getEntities() != null) {
                    for (Entity e : cellGraph.getEntities()) {
                        if (e == null || e.getId() == null || e.getTitle() == null || e.getType() == null) {
                            log.debug("Skipping table graph entity with null id/title/type: {}", e);
                            continue;
                        }
                        Map<String, String> props = new LinkedHashMap<>();
                        if (e.getMetadata() != null) {
                            e.getMetadata().forEach((k, v) -> { if (v != null) props.put(k, v.toString()); });
                        }
                        addEntity(entityIndex, new ExtractedEntity(
                                e.getId(), e.getTitle(), e.getType(), null, e.getDescription(),
                                e.getConfidence() != null ? e.getConfidence() : 0.8, props));
                    }
                }
                if (cellGraph.getRelationships() != null) {
                    for (Relationship r : cellGraph.getRelationships()) {
                        if (r == null || r.getSource() == null || r.getTarget() == null || r.getType() == null) {
                            log.debug("Skipping table graph relationship with null source/target/type: {}", r);
                            continue;
                        }
                        relations.add(new ExtractedRelation(
                                r.getSource(), r.getTarget(), r.getType(), r.getDescription(),
                                r.getWeight() != null ? r.getWeight() : 0.8, null));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse tableGraph JSON for Tika document: {}", e.getMessage());
            }
        }

        // ── CAMERA entity from EXIF make/model ─────────────────────────
        String cameraMake = str(meta.get("image.make"));
        String cameraModel = str(meta.get("image.model"));
        if (cameraMake != null || cameraModel != null) {
            String cameraName = (cameraMake != null && cameraModel != null)
                    ? cameraMake + " " + cameraModel
                    : (cameraMake != null ? cameraMake : cameraModel);
            String cameraId = entityId("camera:" + cameraName.toLowerCase());
            Map<String, String> cameraProps = new LinkedHashMap<>();
            if (cameraMake != null) cameraProps.put("make", cameraMake);
            if (cameraModel != null) cameraProps.put("model", cameraModel);

            ExtractedEntity cameraEntity = new ExtractedEntity(
                    cameraId, cameraName, ENTITY_CAMERA,
                    null, "Camera: " + cameraName, 0.9, cameraProps
            );
            addEntity(entityIndex, cameraEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, cameraId, REL_TAKEN_WITH,
                    displayTitle + " taken with " + cameraName,
                    0.9, null
            ));
        }

        // ── ORGANIZATION from image software ──────────────────────────
        String imageSoftware = str(meta.get("image.software"));
        if (imageSoftware != null && producer == null) {
            // Only create if not already covered by producer/applicationName
            String softwareId = entityId("org:" + imageSoftware.toLowerCase());
            Map<String, String> softwareProps = new LinkedHashMap<>();
            softwareProps.put("name", imageSoftware);
            softwareProps.put(PROP_SOURCE_FIELD, "imageSoftware");
            ExtractedEntity softwareEntity = new ExtractedEntity(
                    softwareId, imageSoftware, ENTITY_ORGANIZATION,
                    null, "Software: " + imageSoftware, 0.8, softwareProps
            );
            addEntity(entityIndex, softwareEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, softwareId, REL_PROCESSED_BY,
                    displayTitle + " processed by " + imageSoftware,
                    0.8, null
            ));
        }

        // ── DATE entity from EXIF image capture timestamp ──────────────
        // Prefer DateTimeOriginal (actual shutter time) over tiff:DateTime (file-write time)
        String exifDateTimeOriginal = str(meta.get("exif.dateTimeOriginal"));
        String imageDateTime = str(meta.get("image.dateTime"));
        String captureDate = exifDateTimeOriginal != null ? exifDateTimeOriginal : imageDateTime;
        if (captureDate != null && !captureDate.isBlank()) {
            String captureDateId = entityId("date:" + captureDate);
            Map<String, String> captureDateProps = new LinkedHashMap<>();
            captureDateProps.put("date", captureDate);
            captureDateProps.put("dateType", "captured");
            if (exifDateTimeOriginal != null) captureDateProps.put("source", "exifDateTimeOriginal");
            addEntity(entityIndex, new ExtractedEntity(
                    captureDateId, captureDate, ENTITY_DATE,
                    null, "Photo capture date: " + captureDate, 0.85, captureDateProps));
            relations.add(new ExtractedRelation(
                    docEntityId, captureDateId, REL_PUBLISHED_ON,
                    displayTitle + " captured on " + captureDate, 0.85, null));
        }
        // Store DateTimeDigitized as property if distinct from capture date
        String exifDigitized = str(meta.get("exif.dateTimeDigitized"));
        if (exifDigitized != null && !exifDigitized.equals(captureDate)) {
            docProps.put("dateTimeDigitized", exifDigitized);
        }

        // ── Audio/Video metadata enrichment ────────────────────────────
        if (ENTITY_AUDIO_DOCUMENT.equals(entityType) || ENTITY_VIDEO_DOCUMENT.equals(entityType)) {
            // Enrich document entity with media properties
            putIfPresent(docProps, "duration", meta, "media.duration");
            putIfPresent(docProps, "codec", meta, "media.codec");
            putIfPresent(docProps, "sampleRate", meta, "media.sampleRate");
            putIfPresent(docProps, "channels", meta, "media.channels");
            putIfPresent(docProps, "bitRate", meta, "media.bitRate");
            putIfPresent(docProps, "artist", meta, "media.artist");
            putIfPresent(docProps, "album", meta, "media.album");
            putIfPresent(docProps, "genre", meta, "media.genre");
            putIfPresent(docProps, "trackNumber", meta, "media.trackNumber");
            putIfPresent(docProps, "releaseDate", meta, "media.releaseDate");
            putIfPresent(docProps, "composer", meta, "media.composer");
            putIfPresent(docProps, "discNumber", meta, "media.discNumber");
            putIfPresent(docProps, "tempo", meta, "media.tempo");
            putIfPresent(docProps, "comment", meta, "media.comment");
            putIfPresent(docProps, "copyright", meta, "media.copyright");
            if (ENTITY_VIDEO_DOCUMENT.equals(entityType)) {
                putIfPresent(docProps, "videoFrameRate", meta, "media.videoFrameRate");
                putIfPresent(docProps, "videoWidth", meta, "media.videoWidth");
                putIfPresent(docProps, "videoHeight", meta, "media.videoHeight");
            }

            // Create DATE entity from releaseDate if present
            String releaseDate = str(meta.get("media.releaseDate"));
            if (releaseDate != null && !releaseDate.isBlank()) {
                String relDateId = entityId("date:" + releaseDate);
                addEntity(entityIndex, new ExtractedEntity(
                        relDateId, releaseDate, ENTITY_DATE,
                        null, "Release date: " + releaseDate, 0.85,
                        Map.of("date", releaseDate, "dateType", "released")));
                relations.add(new ExtractedRelation(
                        docEntityId, relDateId, REL_RELEASED_ON,
                        displayTitle + " released on " + releaseDate, 0.85, null));
            }

            // Create PERSON entity from artist if present
            String artist = str(meta.get("media.artist"));
            if (artist != null && !artist.isBlank()) {
                extractAuthors(artist, docEntityId, displayTitle, entityIndex, relations);
            }

            // Create ALBUM entity from album metadata if present
            String album = str(meta.get("media.album"));
            if (album != null && !album.isBlank()) {
                String albumId = entityId("album:" + album.toLowerCase());
                Map<String, String> albumProps = new LinkedHashMap<>();
                albumProps.put("albumName", album);
                if (artist != null && !artist.isBlank()) albumProps.put("artist", artist);
                ExtractedEntity albumEntity = new ExtractedEntity(
                        albumId, album, ENTITY_ALBUM,
                        null, "Album: " + album, 0.9, albumProps);
                addEntity(entityIndex, albumEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, albumId, REL_IN_ALBUM,
                        displayTitle + " is in album: " + album, 0.9, null));
                // Link album to artist if both are present
                if (artist != null && !artist.isBlank()) {
                    String artistId = entityId("person:" + artist.toLowerCase());
                    relations.add(new ExtractedRelation(
                            albumId, artistId, REL_CREATED_BY,
                            "Album '" + album + "' by " + artist, 0.85, null));
                }
            }

            // Create GENRE entity from media.genre metadata
            String genre = str(meta.get("media.genre"));
            if (genre != null && !genre.isBlank()) {
                String genreId = entityId("genre:" + genre.toLowerCase().trim());
                Map<String, String> genreProps = new LinkedHashMap<>();
                genreProps.put("genreName", genre.trim());
                ExtractedEntity genreEntity = new ExtractedEntity(
                        genreId, genre.trim(), ENTITY_GENRE,
                        null, "Genre: " + genre.trim(), 0.85, genreProps);
                addEntity(entityIndex, genreEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, genreId, REL_IN_GENRE,
                        displayTitle + " is in genre: " + genre.trim(), 0.85, null));
            }

            // Create PERSON entity from composer if present and different from artist
            String composer = str(meta.get("media.composer"));
            if (composer != null && !composer.isBlank() && !composer.equals(artist)) {
                String composerId = entityId("person:" + composer.toLowerCase());
                Map<String, String> composerProps = new LinkedHashMap<>();
                composerProps.put("name", composer);
                composerProps.put(PROP_SOURCE_FIELD, "composer");
                ExtractedEntity composerEntity = new ExtractedEntity(
                        composerId, composer, ENTITY_PERSON,
                        null, "Composer: " + composer, 0.85, composerProps);
                addEntity(entityIndex, composerEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, composerId, REL_COMPOSED_BY,
                        displayTitle + " composed by " + composer, 0.85, null));
            }
        }

        // ── YouTube transcript entities ────────────────────────────────
        if (ENTITY_YOUTUBE_TRANSCRIPT.equals(entityType)) {
            String videoId = str(meta.get("videoId"));
            String videoUrl = str(meta.get("videoUrl"));
            String ytTitle = str(meta.get("title"));
            String ytLanguage = str(meta.get("language"));
            Object segmentCount = meta.get("segmentCount");
            Object durationSeconds = meta.get("durationSeconds");

            // Create YOUTUBE_VIDEO entity
            if (videoId != null) {
                String videoEntityId = entityId("yt_video:" + videoId);
                Map<String, String> videoProps = new LinkedHashMap<>();
                videoProps.put("videoId", videoId);
                if (videoUrl != null) videoProps.put("videoUrl", videoUrl);
                if (ytTitle != null) videoProps.put("title", ytTitle);
                if (ytLanguage != null) videoProps.put("language", ytLanguage);
                if (segmentCount != null) videoProps.put("segmentCount", String.valueOf(segmentCount));
                if (durationSeconds != null) videoProps.put("durationSeconds", String.valueOf(durationSeconds));
                addEntity(entityIndex, new ExtractedEntity(
                        videoEntityId, ytTitle != null ? ytTitle : "YouTube Video " + videoId,
                        ENTITY_YOUTUBE_VIDEO,
                        null, "YouTube video: " + (ytTitle != null ? ytTitle : videoId),
                        0.95, videoProps));
                relations.add(new ExtractedRelation(
                        docEntityId, videoEntityId, REL_TRANSCRIPT_OF,
                        displayTitle + " is transcript of video: " + (ytTitle != null ? ytTitle : videoId),
                        0.95, null));

                // Enrich doc entity with YouTube-specific properties
                docProps.put("videoId", videoId);
                if (videoUrl != null) docProps.put("videoUrl", videoUrl);
                if (ytLanguage != null) docProps.put("language", ytLanguage);
                if (durationSeconds != null) docProps.put("durationSeconds", String.valueOf(durationSeconds));
            }

            // Create YOUTUBE_CHANNEL entity from channel metadata if available
            String channelName = str(meta.get("channelName"));
            String channelId = str(meta.get("channelId"));
            if (channelName != null || channelId != null) {
                String chKey = channelId != null ? channelId : channelName.toLowerCase();
                String chEntityId = entityId("yt_channel:" + chKey);
                Map<String, String> chProps = new LinkedHashMap<>();
                if (channelName != null) chProps.put("channelName", channelName);
                if (channelId != null) chProps.put("channelId", channelId);
                String chDisplay = channelName != null ? channelName : channelId;
                addEntity(entityIndex, new ExtractedEntity(
                        chEntityId, chDisplay, ENTITY_YOUTUBE_CHANNEL,
                        null, "YouTube channel: " + chDisplay, 0.9, chProps));
                String videoEntityId = videoId != null ? entityId("yt_video:" + videoId) : docEntityId;
                relations.add(new ExtractedRelation(
                        videoEntityId, chEntityId, REL_FROM_CHANNEL,
                        "Video from channel: " + chDisplay, 0.9, null));
            }

            // Create TRANSCRIPT_SEGMENT entities from youtube.segments metadata
            @SuppressWarnings("unchecked")
            List<Map<String, String>> ytSegments = meta.get("youtube.segments") instanceof List<?>
                    ? (List<Map<String, String>>) meta.get("youtube.segments") : null;
            if (ytSegments != null && !ytSegments.isEmpty()) {
                int maxSegments = Math.min(ytSegments.size(), 500); // safety cap
                for (int i = 0; i < maxSegments; i++) {
                    Map<String, String> seg = ytSegments.get(i);
                    String segText = seg.get("text");
                    String segStart = seg.get("start");
                    String segEnd = seg.get("end");
                    String segDuration = seg.get("duration");
                    if (segText == null || segText.isBlank()) continue;

                    String segKey = (videoId != null ? videoId : source) + "/seg:" + i;
                    String segEntityId = entityId("yt_segment:" + segKey);
                    Map<String, String> segProps = new LinkedHashMap<>();
                    segProps.put("segmentIndex", String.valueOf(i));
                    segProps.put("text", segText.trim());
                    if (segStart != null) segProps.put("startTime", segStart);
                    if (segEnd != null) segProps.put("endTime", segEnd);
                    if (segDuration != null) segProps.put("duration", segDuration);
                    addEntity(entityIndex, new ExtractedEntity(
                            segEntityId, "Segment " + i + ": " + segText.trim().substring(0, Math.min(segText.trim().length(), 50)),
                            ENTITY_TRANSCRIPT_SEGMENT,
                            null, "Transcript segment " + i, 0.7, segProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, segEntityId, REL_HAS_SEGMENT,
                            "Transcript has segment " + i, 0.7, null));
                }
            }
        }

        // ── DOCUMENT_SECTION from tika.headings (EPUB chapters, RTF headings) ─
        @SuppressWarnings("unchecked")
        List<Map<String, String>> tikaHeadings = meta.get(META_TIKA_HEADINGS) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(META_TIKA_HEADINGS) : null;
        if (tikaHeadings != null) {
            // Track parent sections at each depth level for SUBSECTION_OF relations
            Map<Integer, String> depthStack = new LinkedHashMap<>();
            for (Map<String, String> heading : tikaHeadings) {
                String headingText = heading.get("text");
                String headingLevel = heading.get("level");
                if (headingText == null || headingText.isBlank()) continue;

                int level = 0;
                if (headingLevel != null) {
                    try { level = Integer.parseInt(headingLevel); } catch (NumberFormatException e) {
                        log.debug("Could not parse heading level '{}' as integer: {}", headingLevel, e.getMessage());
                    }
                }

                String sectionId = entityId("section:" + docEntityId + ":" + headingText.toLowerCase());
                Map<String, String> sectionProps = new LinkedHashMap<>();
                sectionProps.put("headingText", headingText);
                if (headingLevel != null) sectionProps.put("headingLevel", headingLevel);
                String idx = heading.get("index");
                if (idx != null) sectionProps.put("sectionIndex", idx);

                ExtractedEntity sectionEntity = new ExtractedEntity(
                        sectionId, headingText, ENTITY_DOCUMENT_SECTION,
                        null, "Section: " + headingText, 0.85, sectionProps);
                entityIndex.put(sectionId, sectionEntity);

                if (level <= 1) {
                    // Top-level section: linked directly to document
                    relations.add(new ExtractedRelation(
                            docEntityId, sectionId, REL_HAS_SECTION,
                            displayTitle + " has section: " + headingText, 0.85, null));
                } else {
                    // Find nearest parent at a higher level
                    String parentId = null;
                    for (int d = level - 1; d >= 0; d--) {
                        if (depthStack.containsKey(d)) {
                            parentId = depthStack.get(d);
                            break;
                        }
                    }
                    if (parentId != null) {
                        relations.add(new ExtractedRelation(
                                sectionId, parentId, REL_SUBSECTION_OF,
                                headingText + " is subsection of parent heading",
                                0.85, null));
                    } else {
                        // Fallback: link to document if no parent found
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, REL_HAS_SECTION,
                                displayTitle + " has section: " + headingText, 0.85, null));
                    }
                }
                depthStack.put(level, sectionId);
                // Clear deeper entries
                final int currentLevel = level;
                depthStack.entrySet().removeIf(e -> e.getKey() > currentLevel);
            }
        }

        // ── GEO_LOCATION from GPS coordinates ──────────────────────────
        String geoLat = str(meta.get("geo.lat"));
        String geoLong = str(meta.get("geo.long"));
        if (geoLat != null && geoLong != null) {
            String locKey = geoLat + "," + geoLong;
            String locId = entityId("geo:" + locKey);
            Map<String, String> locProps = new LinkedHashMap<>();
            locProps.put("latitude", geoLat);
            locProps.put("longitude", geoLong);
            String geoAlt = str(meta.get("geo.altitude"));
            if (geoAlt != null) locProps.put("altitude", geoAlt);

            ExtractedEntity locEntity = new ExtractedEntity(
                    locId, "Location " + locKey, ENTITY_GEO_LOCATION,
                    null, "GPS coordinates: " + locKey, 0.85, locProps
            );
            addEntity(entityIndex, locEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, locId, REL_LOCATED_AT,
                    displayTitle + " taken at " + locKey,
                    0.85, null
            ));
        }

        // ── Extract URLs from body text → HYPERLINKS_TO → EXTERNAL_RESOURCE ──
        String bodyText = doc.getText();
        if (bodyText != null && !bodyText.isBlank()) {
            Set<String> seenUrls = new LinkedHashSet<>();

            // First pass: extract Markdown-style links [text](url) — preserves anchor text
            Matcher mdLinkMatcher = MARKDOWN_LINK_PATTERN.matcher(bodyText);
            while (mdLinkMatcher.find() && seenUrls.size() < 100) {
                String anchorText = mdLinkMatcher.group(1).trim();
                String url = mdLinkMatcher.group(2).trim();
                if (seenUrls.add(url)) {
                    String urlEntityId = entityId("url:" + url);
                    Map<String, String> urlProps = new LinkedHashMap<>();
                    urlProps.put("url", url);
                    urlProps.put("anchorText", anchorText);
                    addEntity(entityIndex, new ExtractedEntity(
                            urlEntityId, anchorText, ENTITY_EXTERNAL_RESOURCE,
                            null, "Link: " + anchorText + " → " + url, 0.85,
                            urlProps
                    ));
                    relations.add(new ExtractedRelation(
                            docEntityId, urlEntityId, REL_HYPERLINKS_TO,
                            "Document links to " + url + " (" + anchorText + ")",
                            0.85, null
                    ));
                }
            }

            // Second pass: extract bare URLs not already captured from Markdown links
            Matcher urlMatcher = URL_PATTERN.matcher(bodyText);
            while (urlMatcher.find() && seenUrls.size() < 100) {
                String url = urlMatcher.group();
                if (seenUrls.add(url)) {
                    String urlEntityId = entityId("url:" + url);
                    addEntity(entityIndex, new ExtractedEntity(
                            urlEntityId, url, ENTITY_EXTERNAL_RESOURCE,
                            null, "URL referenced in document", 0.8,
                            Map.of("url", url)
                    ));
                    relations.add(new ExtractedRelation(
                            docEntityId, urlEntityId, REL_HYPERLINKS_TO,
                            "Document contains link to " + url,
                            0.8, null
                    ));
                }
            }

            // Extract Markdown images: ![alt](src) → EMBEDDED_IMAGE entities
            Matcher imgMatcher = MARKDOWN_IMAGE_PATTERN.matcher(bodyText);
            Set<String> seenImages = new LinkedHashSet<>();
            while (imgMatcher.find() && seenImages.size() < 50) {
                String altText = imgMatcher.group(1).trim();
                String imageSrc = imgMatcher.group(2).trim();
                if (seenImages.add(imageSrc)) {
                    String imgEntityId = entityId("img:" + imageSrc);
                    Map<String, String> imgProps = new LinkedHashMap<>();
                    imgProps.put("src", imageSrc);
                    if (!altText.isEmpty()) imgProps.put("altText", altText);
                    String imgName = !altText.isEmpty() ? altText : imageSrc;
                    addEntity(entityIndex, new ExtractedEntity(
                            imgEntityId, imgName, ENTITY_EMBEDDED_IMAGE,
                            null, "Image: " + imgName, 0.8, imgProps
                    ));
                    relations.add(new ExtractedRelation(
                            docEntityId, imgEntityId, REL_HAS_IMAGE,
                            "Document contains image: " + imgName,
                            0.8, null
                    ));
                }
            }

            // ── Email addresses in body text → PERSON entities ─────────────
            Matcher emailMatcher = EMAIL_ADDRESS_PATTERN.matcher(bodyText);
            Set<String> seenEmails = new LinkedHashSet<>();
            while (emailMatcher.find() && seenEmails.size() < 50) {
                String emailAddr = emailMatcher.group().toLowerCase();
                if (seenEmails.add(emailAddr)) {
                    String personId = entityId("person:" + emailAddr);
                    if (!entityIndex.containsKey(personId)) {
                        Map<String, String> emailProps = new LinkedHashMap<>();
                        emailProps.put("email", emailAddr);
                        emailProps.put(PROP_SOURCE_FIELD, "body_text_email");
                        addEntity(entityIndex, new ExtractedEntity(
                                personId, emailAddr, ENTITY_PERSON,
                                null, "Email address found in document body",
                                0.7, emailProps));
                    }
                    relations.add(new ExtractedRelation(
                            docEntityId, personId, REL_MENTIONS,
                            displayTitle + " mentions " + emailAddr,
                            0.7, null));
                }
            }

            // ── MATH_FORMULA entities from LaTeX notation ─────────────────
            int formulaIdx = 0;
            Matcher dispMath = LATEX_DISPLAY_MATH_PATTERN.matcher(bodyText);
            while (dispMath.find() && formulaIdx < 50) {
                String content = dispMath.group(1) != null ? dispMath.group(1) : dispMath.group(2);
                if (content != null && !content.isBlank()) {
                    String preview = content.trim();
                    if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
                    String fId = entityId("formula:" + docEntityId + ":" + formulaIdx + ":" + content.hashCode());
                    if (!entityIndex.containsKey(fId)) {
                        Map<String, String> fProps = new LinkedHashMap<>();
                        fProps.put("content", content.trim());
                        fProps.put("mathMode", "display");
                        addEntity(entityIndex, new ExtractedEntity(
                                fId, preview, ENTITY_MATH_FORMULA,
                                null, "Formula: " + preview, 0.75, fProps));
                        relations.add(new ExtractedRelation(
                                docEntityId, fId, REL_HAS_FORMULA,
                                displayTitle + " has formula: " + preview, 0.75, null));
                    }
                    formulaIdx++;
                }
            }
            Matcher inlineMath = LATEX_INLINE_MATH_PATTERN.matcher(bodyText);
            while (inlineMath.find() && formulaIdx < 100) {
                String content = inlineMath.group(1);
                if (content != null && !content.isBlank() && content.length() > 2) {
                    String preview = content.trim();
                    if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
                    String fId = entityId("formula:" + docEntityId + ":" + formulaIdx + ":" + content.hashCode());
                    if (!entityIndex.containsKey(fId)) {
                        Map<String, String> fProps = new LinkedHashMap<>();
                        fProps.put("content", content.trim());
                        fProps.put("mathMode", "inline");
                        addEntity(entityIndex, new ExtractedEntity(
                                fId, preview, ENTITY_MATH_FORMULA,
                                null, "Formula: " + preview, 0.75, fProps));
                        relations.add(new ExtractedRelation(
                                docEntityId, fId, REL_HAS_FORMULA,
                                displayTitle + " has formula: " + preview, 0.75, null));
                    }
                    formulaIdx++;
                }
            }

            // ── CODE_BLOCK entities from fenced code blocks ──────────────
            Matcher codeMatcher = FENCED_CODE_BLOCK_PATTERN.matcher(bodyText);
            int codeIdx = 0;
            while (codeMatcher.find() && codeIdx < 50) {
                String lang = codeMatcher.group(1);
                String code = codeMatcher.group(2);
                if (code != null && !code.isBlank()) {
                    String preview = code.trim();
                    if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
                    String cId = entityId("code:" + docEntityId + ":" + codeIdx + ":" + code.hashCode());
                    if (!entityIndex.containsKey(cId)) {
                        Map<String, String> cProps = new LinkedHashMap<>();
                        cProps.put("codeContent", code.trim());
                        if (lang != null && !lang.isEmpty()) cProps.put("language", lang);
                        cProps.put("lineCount", String.valueOf(code.trim().split("\n").length));
                        String name = (lang != null && !lang.isEmpty() ? lang + " " : "") + "code block";
                        addEntity(entityIndex, new ExtractedEntity(
                                cId, name, ENTITY_CODE_BLOCK,
                                null, "Code block: " + preview, 0.7, cProps));
                        relations.add(new ExtractedRelation(
                                docEntityId, cId, REL_HAS_CODE_BLOCK,
                                displayTitle + " has code block", 0.7, null));
                    }
                    codeIdx++;
                }
            }
        }

        // ── Wiki-style links [[Page]] for Markdown documents ──────────────
        if (bodyText != null && entityType.equals(ENTITY_MARKDOWN_DOCUMENT)) {
            Matcher wikiMatcher = WIKI_LINK_PATTERN.matcher(bodyText);
            Set<String> seenWikiTargets = new LinkedHashSet<>();
            while (wikiMatcher.find() && seenWikiTargets.size() < 50) {
                String targetPage = wikiMatcher.group(1).trim();
                String displayText = wikiMatcher.group(2);
                if (targetPage.isEmpty() || !seenWikiTargets.add(targetPage.toLowerCase())) continue;
                String wlId = entityId("wikilink:" + docEntityId + ":" + targetPage.toLowerCase());
                Map<String, String> wlProps = new LinkedHashMap<>();
                wlProps.put("targetPage", targetPage);
                if (displayText != null) wlProps.put("displayText", displayText.trim());
                addEntity(entityIndex, new ExtractedEntity(
                        wlId, targetPage, ENTITY_WIKI_LINK,
                        null, "Wiki link to: " + targetPage, 0.8, wlProps));
                relations.add(new ExtractedRelation(
                        docEntityId, wlId, REL_WIKI_LINKS_TO,
                        displayTitle + " links to page: " + targetPage, 0.8, null));
            }

            // ── Task list items - [ ] / - [x] ───────────────────────────
            Matcher taskMatcher = TASK_ITEM_PATTERN.matcher(bodyText);
            int taskIdx = 0;
            while (taskMatcher.find() && taskIdx < 100) {
                String checkChar = taskMatcher.group(1);
                String taskText = taskMatcher.group(2).trim();
                if (taskText.isEmpty()) continue;
                boolean completed = "x".equalsIgnoreCase(checkChar);
                String tId = entityId("task:" + docEntityId + ":" + taskIdx);
                Map<String, String> tProps = new LinkedHashMap<>();
                tProps.put("text", taskText.length() > 500 ? taskText.substring(0, 500) : taskText);
                tProps.put("completed", String.valueOf(completed));
                String tLabel = (completed ? "[x] " : "[ ] ") + (taskText.length() > 60
                        ? taskText.substring(0, 60) + "..." : taskText);
                addEntity(entityIndex, new ExtractedEntity(
                        tId, tLabel, ENTITY_TASK_ITEM,
                        null, "Task item: " + tLabel, 0.8, tProps));
                relations.add(new ExtractedRelation(
                        docEntityId, tId, REL_HAS_TASK,
                        displayTitle + " has task: " + tLabel, 0.8, null));
                taskIdx++;
            }
        }

        // ── VLM [Image:] markers (OCR-only) ──────────────────────────────
        if (bodyText != null && !bodyText.isBlank() && Boolean.TRUE.equals(ocrProcessed)) {
            Matcher imgMarker = VLM_IMAGE_MARKER_PATTERN.matcher(bodyText);
            int imgIdx = 0;
            while (imgMarker.find() && imgIdx < 200) {
                String objectId = imgMarker.group(1).trim();
                if (!objectId.isEmpty()) {
                    String imgId = entityId("vlmfigure:" + docEntityId + ":" + objectId.toLowerCase());
                    Map<String, String> imgProps = new LinkedHashMap<>();
                    imgProps.put("objectId", objectId);
                    imgProps.put(META_SOURCE, "vlm_image_marker");
                    addEntity(entityIndex, new ExtractedEntity(
                            imgId, "Image: " + objectId, GraphConstants.ENTITY_EMBEDDED_IMAGE,
                            null, "VLM-extracted image: " + objectId, 0.8, imgProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, imgId, GraphConstants.REL_HAS_IMAGE,
                            displayTitle + " contains image: " + objectId, 0.8, null));
                    imgIdx++;
                }
            }
        }

        // ── Markdown structural entities (headings, pipe tables) ─────────
        // Extract from OCR-processed docs (VLM output is markdown-like) AND
        // from actual markdown documents so .md files get proper SECTION and
        // TABLE entities in the graph.
        boolean isMarkdownStructured = ENTITY_MARKDOWN_DOCUMENT.equals(entityType)
                || Boolean.TRUE.equals(ocrProcessed);
        if (bodyText != null && !bodyText.isBlank() && isMarkdownStructured) {
            // ── Markdown headings → DOCUMENT_SECTION entities ──────────
            // Skip when tika.headings already produced sections (avoids duplicates)
            if (tikaHeadings == null || tikaHeadings.isEmpty()) {
            Matcher headingMatcher = MARKDOWN_HEADING_PATTERN.matcher(bodyText);
            Map<Integer, String> headingDepthStack = new LinkedHashMap<>();
            while (headingMatcher.find()) {
                int level = headingMatcher.group(1).length();
                String headingText = headingMatcher.group(2).trim();
                if (headingText.isEmpty()) continue;

                String sectionId = entityId("section:" + docEntityId + ":" + headingText.toLowerCase());
                Map<String, String> secProps = new LinkedHashMap<>();
                secProps.put("headingText", headingText);
                secProps.put("headingLevel", String.valueOf(level));
                secProps.put(META_SOURCE, Boolean.TRUE.equals(ocrProcessed) ? "vlm_markdown_heading" : "markdown_heading");
                addEntity(entityIndex, new ExtractedEntity(
                        sectionId, headingText, ENTITY_DOCUMENT_SECTION,
                        null, "Section: " + headingText, 0.85, secProps));

                if (level <= 1) {
                    relations.add(new ExtractedRelation(
                            docEntityId, sectionId, REL_HAS_SECTION,
                            displayTitle + " has section: " + headingText, 0.85, null));
                } else {
                    String parentId = headingDepthStack.get(level - 1);
                    if (parentId != null) {
                        relations.add(new ExtractedRelation(
                                parentId, sectionId, GraphConstants.REL_SUBSECTION_OF,
                                "Subsection: " + headingText, 0.8, null));
                    } else {
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, REL_HAS_SECTION,
                                displayTitle + " has section: " + headingText, 0.85, null));
                    }
                }
                headingDepthStack.put(level, sectionId);
            }
            } // end tikaHeadings guard

            // ── Pipe tables → TABLE + CELL entities ───────────────────
            // Detect markdown pipe tables (| col | col |\n|---|---|\n| val | val |)
            // and produce cell-level entities via TableCellGraphBuilder when
            // META_TABLE_GRAPH is not already present.
            boolean hasExistingTblGraph = meta.get(META_TABLE_GRAPH) instanceof String tgStr && !tgStr.isBlank();
            if (PIPE_TABLE_SEPARATOR.matcher(bodyText).find()) {
                String[] lines = bodyText.split("\n");
                int tableBlockIdx = 0;
                for (int i = 1; i < lines.length - 1 && tableBlockIdx < 50; i++) {
                    String line = lines[i].trim();
                    if (line.matches("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$")) {
                        // This is a separator row — the row above is headers
                        String headerLine = lines[i - 1].trim();
                        if (headerLine.contains("|")) {
                            String[] headerCells = headerLine.split("\\|");
                            List<String> headers = new ArrayList<>();
                            for (String cell : headerCells) {
                                String trimmed = cell.trim();
                                if (!trimmed.isEmpty()) headers.add(trimmed);
                            }
                            if (!headers.isEmpty()) {
                                // Count data rows below separator
                                List<List<String>> dataRows = new ArrayList<>();
                                for (int j = i + 1; j < lines.length; j++) {
                                    String dataLine = lines[j].trim();
                                    if (!dataLine.contains("|")) break;
                                    String[] dataCells = dataLine.split("\\|");
                                    List<String> rowVals = new ArrayList<>();
                                    for (String dc : dataCells) {
                                        String tv = dc.trim();
                                        if (!tv.isEmpty() || !rowVals.isEmpty()) rowVals.add(tv);
                                    }
                                    if (!rowVals.isEmpty() && rowVals.get(rowVals.size() - 1).isEmpty()) {
                                        rowVals.remove(rowVals.size() - 1);
                                    }
                                    if (!rowVals.isEmpty()) dataRows.add(rowVals);
                                }

                                String tblPrefix = Boolean.TRUE.equals(ocrProcessed) ? "VLM-Table-" : "MD-Table-";
                                String tblName = tblPrefix + (tableBlockIdx + 1);
                                String tableId = entityId("vlm_table:" + docEntityId + ":" + tableBlockIdx);
                                Map<String, String> tblProps = new LinkedHashMap<>();
                                tblProps.put(PROP_HEADERS, String.join(", ", headers));
                                tblProps.put(PROP_COLUMN_COUNT, String.valueOf(headers.size()));
                                tblProps.put(PROP_ROW_COUNT, String.valueOf(dataRows.size()));
                                tblProps.put(META_SOURCE, Boolean.TRUE.equals(ocrProcessed) ? "vlm_markdown_table" : "markdown_table");
                                addEntity(entityIndex, new ExtractedEntity(
                                        tableId, tblName, ENTITY_TABLE,
                                        null, "VLM table: " + String.join(", ", headers),
                                        0.85, tblProps));
                                relations.add(new ExtractedRelation(
                                        docEntityId, tableId, REL_HAS_TABLE,
                                        displayTitle + " has table: " + tblName, 0.85, null));

                                // Generate cell-level entities if no pre-built tableGraph
                                if (!hasExistingTblGraph && !dataRows.isEmpty()) {
                                    try {
                                        String ns = "tika:" + (displayTitle) + "/vlmtbl:" + tableBlockIdx;
                                        TableCellGraphBuilder builder = new TableCellGraphBuilder()
                                                .namespace(ns)
                                                .tableName(tblName)
                                                .headers(new ArrayList<>(headers));
                                        builder.addRow(new ArrayList<>(headers));
                                        for (List<String> row : dataRows) {
                                            builder.addRow(row);
                                        }
                                        Graph cellGraph = builder.build();
                                        if (cellGraph.getEntities() != null) {
                                            for (Entity ce : cellGraph.getEntities()) {
                                                Map<String, String> cProps = new LinkedHashMap<>();
                                                if (ce.getMetadata() != null) {
                                                    ce.getMetadata().forEach((k, v) -> { if (v != null) cProps.put(k, v.toString()); });
                                                }
                                                addEntity(entityIndex, new ExtractedEntity(
                                                        ce.getId(), ce.getTitle(), ce.getType(), null, ce.getDescription(),
                                                        ce.getConfidence() != null ? ce.getConfidence() : 0.8, cProps));
                                            }
                                        }
                                        if (cellGraph.getRelationships() != null) {
                                            for (Relationship cr : cellGraph.getRelationships()) {
                                                relations.add(new ExtractedRelation(
                                                        cr.getSource(), cr.getTarget(), cr.getType(), cr.getDescription(),
                                                        cr.getWeight() != null ? cr.getWeight() : 0.8, null));
                                            }
                                        }
                                    } catch (Exception cellEx) {
                                        log.warn("Failed to build cell graph for VLM table {}: {}", tblName, cellEx.getMessage());
                                    }
                                }

                                tableBlockIdx++;
                            }
                        }
                    }
                }
            }
        }

        // ── VLM DOCTAGS <figure> entities from metadata ─────────────────
        // OcrDocumentProcessor stores extracted <figure> tags as vlm.figures metadata.
        // For non-PDF images processed by VLM, TikaGenericGraphExtractor handles these.
        @SuppressWarnings("unchecked")
        List<Map<String, String>> vlmFigures = meta.get("vlm.figures") instanceof List<?>
                ? (List<Map<String, String>>) meta.get("vlm.figures") : null;
        if (vlmFigures != null) {
            for (Map<String, String> fig : vlmFigures) {
                String objectId = fig.get("objectId");
                if (objectId == null || objectId.isBlank()) continue;
                String imgId = entityId("vlmfigure:" + docEntityId + ":" + objectId.toLowerCase());
                if (entityIndex.containsKey(imgId)) continue;
                Map<String, String> imgProps = new LinkedHashMap<>();
                imgProps.put("objectId", objectId);
                String caption = fig.get("caption");
                if (caption != null && !caption.isEmpty()) imgProps.put("caption", caption);
                imgProps.put(META_SOURCE, "doctags_figure");
                String label = caption != null && !caption.isEmpty()
                        ? (caption.length() > 80 ? caption.substring(0, 77) + "..." : caption)
                        : "Figure: " + objectId;
                addEntity(entityIndex, new ExtractedEntity(
                        imgId, label, ENTITY_EMBEDDED_IMAGE,
                        null, "VLM DOCTAGS figure: " + objectId, 0.8, imgProps));
                relations.add(new ExtractedRelation(
                        docEntityId, imgId, REL_HAS_IMAGE,
                        displayTitle + " has figure: " + objectId, 0.8, null));
            }
        }

        // ── JSON structural entities (top-level keys, $schema) ──────────
        if (ENTITY_JSON_DOCUMENT.equals(entityType)) {
            Object keysFromArray = meta.get("json.keysFromArrayElement");
            if (Boolean.TRUE.equals(keysFromArray)) {
                docProps.put("jsonKeysFromArrayElement", "true");
            }
            @SuppressWarnings("unchecked")
            List<String> topKeys = meta.get("json.topLevelKeys") instanceof List<?>
                    ? (List<String>) meta.get("json.topLevelKeys") : null;
            if (topKeys != null) {
                int keyLimit = Math.min(topKeys.size(), 100);
                for (int i = 0; i < keyLimit; i++) {
                    String keyName = topKeys.get(i);
                    if (keyName == null || keyName.isBlank()) continue;
                    String kId = entityId("jsonkey:" + docEntityId + ":" + keyName);
                    Map<String, String> kProps = new LinkedHashMap<>();
                    kProps.put("keyName", keyName);
                    kProps.put("keyIndex", String.valueOf(i));
                    addEntity(entityIndex, new ExtractedEntity(
                            kId, keyName, ENTITY_JSON_KEY,
                            null, "JSON key: " + keyName, 0.85, kProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, kId, REL_HAS_JSON_KEY,
                            displayTitle + " has JSON key: " + keyName, 0.85, null));
                }
            }

            // Nested key entities — captures full JSON structure beyond top level
            @SuppressWarnings("unchecked")
            List<Map<String, String>> nestedKeys = meta.get("json.nestedKeys") instanceof List<?>
                    ? (List<Map<String, String>>) meta.get("json.nestedKeys") : null;
            if (nestedKeys != null) {
                int nkLimit = Math.min(nestedKeys.size(), 500);
                for (int i = 0; i < nkLimit; i++) {
                    Map<String, String> nk = nestedKeys.get(i);
                    String path = nk.get("path");
                    String key = nk.get("key");
                    String parentPath = nk.get("parentPath");
                    if (path == null || key == null) continue;
                    String nkId = entityId("jsonkey:" + docEntityId + ":" + path);
                    Map<String, String> nkProps = new LinkedHashMap<>();
                    nkProps.put("keyName", key);
                    nkProps.put("keyPath", path);
                    nkProps.put("depth", nk.getOrDefault("depth", "1"));
                    nkProps.put("valueType", nk.getOrDefault("valueType", "unknown"));
                    addEntity(entityIndex, new ExtractedEntity(
                            nkId, path, ENTITY_JSON_KEY,
                            null, "JSON key: " + path, 0.8, nkProps));
                    // Link to parent key or doc
                    String parentId = parentPath != null && !parentPath.isEmpty()
                            ? entityId("jsonkey:" + docEntityId + ":" + parentPath)
                            : docEntityId;
                    relations.add(new ExtractedRelation(
                            parentId, nkId, REL_HAS_JSON_KEY,
                            "has nested key: " + path, 0.8, null));
                }
            }

            String jsonSchema = str(meta.get("json.schema"));
            if (jsonSchema != null && !jsonSchema.isBlank()) {
                String sId = entityId("jsonschema:" + jsonSchema);
                Map<String, String> sProps = new LinkedHashMap<>();
                sProps.put("schemaUri", jsonSchema);
                addEntity(entityIndex, new ExtractedEntity(
                        sId, jsonSchema, ENTITY_JSON_SCHEMA,
                        null, "JSON $schema: " + jsonSchema, 0.9, sProps));
                relations.add(new ExtractedRelation(
                        docEntityId, sId, REL_HAS_JSON_SCHEMA,
                        displayTitle + " conforms to schema: " + jsonSchema, 0.9, null));
            }

            // JSON $id → IDENTIFIER entity
            String jsonId = str(meta.get("json.id"));
            if (jsonId != null && !jsonId.isBlank()) {
                String idEntityId = entityId("jsonid:" + jsonId);
                Map<String, String> idProps = new LinkedHashMap<>();
                idProps.put("identifier", jsonId);
                addEntity(entityIndex, new ExtractedEntity(
                        idEntityId, jsonId, ENTITY_IDENTIFIER,
                        null, "JSON $id: " + jsonId, 0.9, idProps));
                relations.add(new ExtractedRelation(
                        docEntityId, idEntityId, REL_HAS_IDENTIFIER,
                        displayTitle + " has identifier: " + jsonId, 0.9, null));
            }

            // Enrich doc entity with JSON structure info
            Object isArray = meta.get("json.isArray");
            if (Boolean.TRUE.equals(isArray)) {
                docProps.put("jsonRootType", "array");
                Object arrSize = meta.get("json.arraySize");
                if (arrSize != null) docProps.put("jsonArraySize", String.valueOf(arrSize));
            } else {
                Object isObject = meta.get("json.isObject");
                docProps.put("jsonRootType", Boolean.TRUE.equals(isObject) ? "object" : "unknown");
                Object keyCount = meta.get("json.keyCount");
                if (keyCount != null) docProps.put("jsonKeyCount", String.valueOf(keyCount));
            }
        }

        // ── XML structural entities (root element, namespaces) ──────────
        if (ENTITY_XML_DOCUMENT.equals(entityType)) {
            String rootTag = str(meta.get("xml.rootTag"));
            if (rootTag != null && !rootTag.isBlank()) {
                String rId = entityId("xmlroot:" + docEntityId + ":" + rootTag);
                Map<String, String> rProps = new LinkedHashMap<>();
                rProps.put("tagName", rootTag);
                String rootNs = str(meta.get("xml.rootNamespace"));
                if (rootNs != null) rProps.put("namespace", rootNs);
                addEntity(entityIndex, new ExtractedEntity(
                        rId, rootTag, ENTITY_XML_ELEMENT,
                        null, "XML root element: " + rootTag, 0.9, rProps));
                relations.add(new ExtractedRelation(
                        docEntityId, rId, REL_HAS_ROOT_ELEMENT,
                        displayTitle + " has root element: " + rootTag, 0.9, null));
            }

            @SuppressWarnings("unchecked")
            List<String> namespaces = meta.get("xml.namespaces") instanceof List<?>
                    ? (List<String>) meta.get("xml.namespaces") : null;
            @SuppressWarnings("unchecked")
            Map<String, String> nsPrefixes = meta.get("xml.namespacePrefixes") instanceof Map<?, ?>
                    ? (Map<String, String>) meta.get("xml.namespacePrefixes") : null;
            if (namespaces != null) {
                int nsLimit = Math.min(namespaces.size(), 30);
                for (int i = 0; i < nsLimit; i++) {
                    String nsUri = namespaces.get(i);
                    if (nsUri == null || nsUri.isBlank()) continue;
                    String nsId = entityId("xmlns:" + nsUri);
                    Map<String, String> nsProps = new LinkedHashMap<>();
                    nsProps.put("namespaceUri", nsUri);
                    // Add namespace prefix if available (e.g. "xsi", "xs", "dc")
                    if (nsPrefixes != null) {
                        String prefix = nsPrefixes.get(nsUri);
                        if (prefix != null && !prefix.isEmpty()) {
                            nsProps.put("prefix", prefix);
                        }
                    }
                    addEntity(entityIndex, new ExtractedEntity(
                            nsId, nsUri, ENTITY_XML_NAMESPACE,
                            null, "XML namespace: " + nsUri, 0.85, nsProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, nsId, REL_HAS_NAMESPACE,
                            displayTitle + " uses namespace: " + nsUri, 0.85, null));
                }
            }

            // XML DTD system ID → EXTERNAL_RESOURCE entity
            String dtdSystemId = str(meta.get("xml.dtdSystemId"));
            if (dtdSystemId != null && !dtdSystemId.isBlank()) {
                String dtdId = entityId("xmldtd:" + dtdSystemId);
                Map<String, String> dtdProps = new LinkedHashMap<>();
                dtdProps.put("url", dtdSystemId);
                dtdProps.put("resourceType", "DTD");
                addEntity(entityIndex, new ExtractedEntity(
                        dtdId, dtdSystemId, ENTITY_EXTERNAL_RESOURCE,
                        null, "XML DTD: " + dtdSystemId, 0.85, dtdProps));
                relations.add(new ExtractedRelation(
                        docEntityId, dtdId, REL_REFERENCES_DTD,
                        displayTitle + " references DTD: " + dtdSystemId, 0.85, null));
            }

            // XML no-namespace schema location → EXTERNAL_RESOURCE entity
            String noNsSchemaLoc = str(meta.get("xml.noNamespaceSchemaLocation"));
            if (noNsSchemaLoc != null && !noNsSchemaLoc.isBlank()) {
                String schemaId = entityId("xmlschema:" + noNsSchemaLoc);
                Map<String, String> schemaProps = new LinkedHashMap<>();
                schemaProps.put("url", noNsSchemaLoc);
                schemaProps.put("resourceType", "XSD");
                addEntity(entityIndex, new ExtractedEntity(
                        schemaId, noNsSchemaLoc, ENTITY_EXTERNAL_RESOURCE,
                        null, "XML Schema: " + noNsSchemaLoc, 0.85, schemaProps));
                relations.add(new ExtractedRelation(
                        docEntityId, schemaId, REL_REFERENCES_SCHEMA,
                        displayTitle + " references schema: " + noNsSchemaLoc, 0.85, null));
            }

            // Enrich doc entity with XML structure info
            if (rootTag != null) docProps.put("xmlRootTag", rootTag);
            putIfPresent(docProps, "xmlEncoding", meta, "xml.encoding");
            putIfPresent(docProps, "xmlVersion", meta, "xml.version");
            putIfPresent(docProps, "xmlChildElementCount", meta, "xml.childElementCount");
            putIfPresent(docProps, "xmlNamespaceCount", meta, "xml.namespaceCount");
            Object uniqueChildTags = meta.get("xml.uniqueChildTags");
            if (uniqueChildTags instanceof List<?> tagList && !tagList.isEmpty()) {
                docProps.put("xmlUniqueChildTags", String.valueOf(tagList.size()));
            }
            Object hasDtd = meta.get("xml.hasDtd");
            if (Boolean.TRUE.equals(hasDtd)) docProps.put("xmlHasDtd", "true");
            // Parse xsi:schemaLocation into individual namespace → schema-URL pairs
            String schemaLocRaw = str(meta.get("xml.schemaLocation"));
            if (schemaLocRaw != null && !schemaLocRaw.isBlank()) {
                docProps.put("xmlSchemaLocation", schemaLocRaw);
                // schemaLocation is alternating "namespace schema-url namespace schema-url ..."
                String[] tokens = schemaLocRaw.trim().split("\\s+");
                for (int si = 0; si + 1 < tokens.length; si += 2) {
                    String nsUri = tokens[si];
                    String schemaUrl = tokens[si + 1];
                    if (schemaUrl.isEmpty()) continue;
                    String schemaEntityId = entityId("xmlschema:" + schemaUrl);
                    Map<String, String> schemaUrlProps = new LinkedHashMap<>();
                    schemaUrlProps.put("url", schemaUrl);
                    schemaUrlProps.put("namespace", nsUri);
                    schemaUrlProps.put(PROP_SOURCE_FIELD, "xsi:schemaLocation");
                    addEntity(entityIndex, new ExtractedEntity(
                            schemaEntityId, schemaUrl, ENTITY_EXTERNAL_RESOURCE,
                            null, "XML Schema: " + schemaUrl + " (ns: " + nsUri + ")", 0.8, schemaUrlProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, schemaEntityId, REL_REFERENCES_SCHEMA,
                            displayTitle + " references schema: " + schemaUrl, 0.8, null));
                }
            }

            // XML child element entities — structural graphing of element hierarchy
            @SuppressWarnings("unchecked")
            List<Map<String, String>> childElements = meta.get("xml.childElements") instanceof List<?>
                    ? (List<Map<String, String>>) meta.get("xml.childElements") : null;
            if (childElements != null) {
                String rootId = rootTag != null ? entityId("xmlroot:" + docEntityId + ":" + rootTag) : docEntityId;
                int ceLimit = Math.min(childElements.size(), 200);
                for (int i = 0; i < ceLimit; i++) {
                    Map<String, String> elem = childElements.get(i);
                    String tagName = elem.get("tagName");
                    if (tagName == null || tagName.isBlank()) continue;
                    String parentPathStr = elem.get("parentPath");
                    String elemPath = parentPathStr != null && !parentPathStr.isBlank()
                            ? parentPathStr + "/" + tagName : tagName;
                    // Use index to distinguish same-name siblings
                    String ceId = entityId("xmlelem:" + docEntityId + ":" + elemPath + ":" + i);
                    Map<String, String> ceProps = new LinkedHashMap<>();
                    ceProps.put("tagName", tagName);
                    ceProps.put("depth", elem.getOrDefault("depth", "1"));
                    if (elem.containsKey("namespace")) ceProps.put("namespace", elem.get("namespace"));
                    // Include key attributes
                    for (Map.Entry<String, String> ae : elem.entrySet()) {
                        if (ae.getKey().startsWith("attr.")) {
                            ceProps.put(ae.getKey(), ae.getValue());
                        }
                    }
                    addEntity(entityIndex, new ExtractedEntity(
                            ceId, tagName, ENTITY_XML_CHILD_ELEMENT,
                            null, "XML element: " + tagName, 0.8, ceProps));
                    // Link to parent — depth 1 children link to root element, deeper ones link to parent path
                    String parentId;
                    if ("1".equals(elem.get("depth"))) {
                        parentId = rootId;
                    } else {
                        // Find parent element in the list by matching parentPath
                        parentId = rootId; // fallback
                        for (int j = i - 1; j >= 0; j--) {
                            Map<String, String> candidate = childElements.get(j);
                            String candPath = candidate.get("parentPath");
                            String candTag = candidate.get("tagName");
                            if (candPath != null && candTag != null) {
                                String candFullPath = candPath.isEmpty() ? candTag : candPath + "/" + candTag;
                                if (parentPathStr != null && parentPathStr.equals(candFullPath)) {
                                    parentId = entityId("xmlelem:" + docEntityId + ":" + candFullPath + ":" + j);
                                    break;
                                }
                            }
                        }
                    }
                    relations.add(new ExtractedRelation(
                            parentId, ceId, REL_CONTAINS,
                            "contains element: " + tagName, 0.8, null));
                }
            }
        }

        // ── YAML structural entities (top-level keys) ─────────────────────
        if (ENTITY_YAML_DOCUMENT.equals(entityType)) {
            @SuppressWarnings("unchecked")
            List<String> yamlKeys = meta.get("yaml.topLevelKeys") instanceof List<?>
                    ? (List<String>) meta.get("yaml.topLevelKeys") : null;
            if (yamlKeys != null) {
                int keyLimit = Math.min(yamlKeys.size(), 100);
                for (int i = 0; i < keyLimit; i++) {
                    String keyName = yamlKeys.get(i);
                    if (keyName == null || keyName.isBlank()) continue;
                    String keyId = entityId("yamlkey:" + docEntityId + ":" + keyName);
                    Map<String, String> keyProps = new LinkedHashMap<>();
                    keyProps.put("keyName", keyName);
                    keyProps.put("keyIndex", String.valueOf(i));
                    addEntity(entityIndex, new ExtractedEntity(
                            keyId, keyName, ENTITY_YAML_KEY,
                            null, "YAML key: " + keyName, 0.85, keyProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, keyId, REL_CONTAINS,
                            displayTitle + " contains key: " + keyName, 0.85, null));
                }
            }

            // Enrich doc entity with YAML structure info
            Object keyCount = meta.get("yaml.keyCount");
            if (keyCount != null) docProps.put("yamlKeyCount", String.valueOf(keyCount));
            Object docCount = meta.get("yaml.documentCount");
            if (docCount != null) docProps.put("yamlDocumentCount", String.valueOf(docCount));
            Object hasAnchors = meta.get("yaml.hasAnchors");
            if (Boolean.TRUE.equals(hasAnchors)) docProps.put("yamlHasAnchors", "true");
        }

        // ── ICS calendar event entities ─────────────────────────────────
        if (ENTITY_ICS_DOCUMENT.equals(entityType)) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> icsEvents = meta.get("ics.events") instanceof List<?>
                    ? (List<Map<String, String>>) meta.get("ics.events") : null;
            if (icsEvents != null) {
                int eventIdx = 0;
                for (Map<String, String> event : icsEvents) {
                    if (eventIdx >= 100) break;
                    String summary = event.get("summary");
                    String uid = event.get("uid");
                    String eventLabel = summary != null ? summary : "Event " + (eventIdx + 1);
                    String eventKey = uid != null ? uid : (docEntityId + ":event:" + eventIdx);
                    String eventEntityId = entityId("ics-event:" + eventKey);

                    Map<String, String> eventProps = new LinkedHashMap<>();
                    if (summary != null) eventProps.put("summary", summary);
                    if (uid != null) eventProps.put("uid", uid);
                    String location = event.get("location");
                    if (location != null) eventProps.put("location", location);
                    String startTime = event.get("startTime");
                    if (startTime != null) eventProps.put("startTime", startTime);
                    String endTime = event.get("endTime");
                    if (endTime != null) eventProps.put("endTime", endTime);
                    String status = event.get("status");
                    if (status != null) eventProps.put("status", status);

                    addEntity(entityIndex, new ExtractedEntity(
                            eventEntityId, eventLabel, GraphConstants.ENTITY_CALENDAR_EVENT,
                            null, "Calendar event: " + eventLabel, 0.9, eventProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, eventEntityId, GraphConstants.REL_HAS_CALENDAR_EVENT,
                            displayTitle + " contains event: " + eventLabel, 0.9, null));

                    // Organizer as PERSON
                    String organizerName = event.get("organizerName");
                    String organizerEmail = event.get("organizerEmail");
                    if (organizerName != null || organizerEmail != null) {
                        String orgKey = organizerEmail != null ? organizerEmail.toLowerCase()
                                : organizerName.toLowerCase();
                        String orgPersonId = entityId("person:" + orgKey);
                        Map<String, String> orgProps = new LinkedHashMap<>();
                        if (organizerName != null) orgProps.put("name", organizerName);
                        if (organizerEmail != null) orgProps.put("email", organizerEmail);
                        addEntity(entityIndex, new ExtractedEntity(
                                orgPersonId, organizerName != null ? organizerName : organizerEmail,
                                ENTITY_PERSON, null, "Organizer: " + orgKey, 0.85, orgProps));
                        relations.add(new ExtractedRelation(
                                eventEntityId, orgPersonId, GraphConstants.REL_ORGANIZED_BY,
                                eventLabel + " organized by " + orgKey, 0.85, null));
                    }

                    // Attendees as PERSON entities
                    String attendeesStr = event.get("attendees");
                    if (attendeesStr != null && !attendeesStr.isBlank()) {
                        for (String attendee : attendeesStr.split(",\\s*")) {
                            if (attendee.isBlank()) continue;
                            String attendeeId = entityId("person:" + attendee.toLowerCase());
                            Map<String, String> attProps = new LinkedHashMap<>();
                            attProps.put(attendee.contains("@") ? "email" : "name", attendee);
                            addEntity(entityIndex, new ExtractedEntity(
                                    attendeeId, attendee, ENTITY_PERSON,
                                    null, "Attendee: " + attendee, 0.8, attProps));
                            relations.add(new ExtractedRelation(
                                    eventEntityId, attendeeId, GraphConstants.REL_ATTENDED_BY,
                                    eventLabel + " attended by " + attendee, 0.8, null));
                        }
                    }

                    // Location as LOCATION entity
                    if (location != null && !location.isBlank()) {
                        String locId = entityId("location:" + location.toLowerCase());
                        addEntity(entityIndex, new ExtractedEntity(
                                locId, location, ENTITY_LOCATION,
                                null, "Location: " + location, 0.8,
                                Map.of("name", location)));
                        relations.add(new ExtractedRelation(
                                eventEntityId, locId, GraphConstants.REL_AT_LOCATION,
                                eventLabel + " at " + location, 0.8, null));
                    }

                    // Start/end times as DATE entities
                    if (startTime != null) {
                        String startDateId = entityId("date:" + startTime);
                        addEntity(entityIndex, new ExtractedEntity(
                                startDateId, startTime, ENTITY_DATE,
                                null, "Event start: " + startTime, 0.85,
                                Map.of("date", startTime, "dateType", "eventStart")));
                        relations.add(new ExtractedRelation(
                                eventEntityId, startDateId, GraphConstants.REL_STARTS_ON,
                                eventLabel + " starts " + startTime, 0.85, null));
                    }
                    if (endTime != null) {
                        String endDateId = entityId("date:" + endTime);
                        addEntity(entityIndex, new ExtractedEntity(
                                endDateId, endTime, ENTITY_DATE,
                                null, "Event end: " + endTime, 0.85,
                                Map.of("date", endTime, "dateType", "eventEnd")));
                        relations.add(new ExtractedRelation(
                                eventEntityId, endDateId, GraphConstants.REL_ENDS_ON,
                                eventLabel + " ends " + endTime, 0.85, null));
                    }

                    eventIdx++;
                }
                docProps.put("eventCount", String.valueOf(icsEvents.size()));
            }
        }

        // ── VCF contact entities ────────────────────────────────────────
        if (ENTITY_VCARD_DOCUMENT.equals(entityType)) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> vcfContacts = meta.get("vcf.contacts") instanceof List<?>
                    ? (List<Map<String, String>>) meta.get("vcf.contacts") : null;
            if (vcfContacts != null) {
                int contactIdx = 0;
                for (Map<String, String> contact : vcfContacts) {
                    if (contactIdx >= 500) break;
                    String displayName = contact.get("displayName");
                    String email = contact.get("email");
                    String contactLabel = displayName != null ? displayName
                            : (email != null ? email : "Contact " + (contactIdx + 1));
                    String contactKey = email != null ? email.toLowerCase()
                            : (displayName != null ? displayName.toLowerCase() : docEntityId + ":c:" + contactIdx);
                    String contactEntityId = entityId("vcf-contact:" + contactKey);

                    Map<String, String> contactProps = new LinkedHashMap<>();
                    if (displayName != null) contactProps.put("displayName", displayName);
                    if (email != null) contactProps.put("email", email);
                    String email2 = contact.get("email2");
                    if (email2 != null) contactProps.put("email2", email2);
                    String phone = contact.get("phone");
                    if (phone != null) contactProps.put("phone", phone);
                    String phone2 = contact.get("phone2");
                    if (phone2 != null) contactProps.put("phone2", phone2);
                    String jobTitle = contact.get("jobTitle");
                    if (jobTitle != null) contactProps.put("jobTitle", jobTitle);
                    String org = contact.get("organization");
                    if (org != null) contactProps.put("organization", org);
                    String url = contact.get("url");
                    if (url != null) contactProps.put("url", url);
                    String birthday = contact.get("birthday");
                    if (birthday != null) contactProps.put("birthday", birthday);
                    String notes = contact.get("notes");
                    if (notes != null) contactProps.put("notes", notes);

                    addEntity(entityIndex, new ExtractedEntity(
                            contactEntityId, contactLabel, GraphConstants.ENTITY_CONTACT,
                            null, "VCF Contact: " + contactLabel, 0.9, contactProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, contactEntityId, REL_CONTAINS,
                            displayTitle + " contains contact: " + contactLabel, 0.9, null));

                    // Also create a PERSON entity for cross-document linking
                    String personId = entityId("person:" + contactKey);
                    Map<String, String> personProps = new LinkedHashMap<>();
                    if (displayName != null) personProps.put("name", displayName);
                    if (email != null) personProps.put("email", email);
                    String structuredName = contact.get("structuredName");
                    if (structuredName != null) personProps.put("alias", structuredName);
                    if (notes != null) personProps.put("notes", notes);
                    addEntity(entityIndex, new ExtractedEntity(
                            personId, contactLabel, ENTITY_PERSON,
                            null, "Person: " + contactLabel, 0.85, personProps));
                    relations.add(new ExtractedRelation(
                            contactEntityId, personId, GraphConstants.REL_SAME_AS,
                            "Contact record for " + contactLabel, 0.9, null));

                    // Address as LOCATION entity
                    String address = contact.get("address");
                    if (address != null && !address.isBlank()) {
                        String locId = entityId("location:" + address.toLowerCase());
                        addEntity(entityIndex, new ExtractedEntity(
                                locId, address, ENTITY_LOCATION,
                                null, "Location: " + address, 0.8,
                                Map.of("name", address)));
                        relations.add(new ExtractedRelation(
                                personId, locId, REL_LOCATED_AT,
                                contactLabel + " located at " + address, 0.8, null));
                    }

                    // Organization entity
                    if (org != null && !org.isBlank()) {
                        // vCard ORG field may have semicolon-separated components: Company;Division
                        String companyName = org.contains(";") ? org.split(";")[0].trim() : org;
                        if (!companyName.isBlank()) {
                            String orgId = entityId("org:" + companyName.toLowerCase());
                            addEntity(entityIndex, new ExtractedEntity(
                                    orgId, companyName, ENTITY_ORGANIZATION,
                                    null, "Organization: " + companyName, 0.8,
                                    Map.of("name", companyName)));
                            relations.add(new ExtractedRelation(
                                    personId, orgId, GraphConstants.REL_AFFILIATED_WITH,
                                    contactLabel + " affiliated with " + companyName, 0.8, null));
                        }
                    }

                    contactIdx++;
                }
                docProps.put("contactCount", String.valueOf(vcfContacts.size()));
            }
        }

        entities.addAll(entityIndex.values());

        ExtractionMetadata extractionMeta = new ExtractionMetadata(
                source, source, SOURCE_TIKA_EXTRACTOR, null, null, null
        );

        return ExtractionResult.of(entities, relations, extractionMeta);
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        return ExtractorUtils.extractBatch(this, docs, SOURCE_TIKA_EXTRACTOR);
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private String resolveEntityType(String docType, Map<String, Object> meta) {
        if (docType != null) {
            String lower = docType.toLowerCase();
            if (lower.contains("youtube") || lower.contains("transcript")) return ENTITY_YOUTUBE_TRANSCRIPT;
            if (lower.contains("csv")) return ENTITY_CSV_DOCUMENT;
            if (lower.contains("markdown")) return ENTITY_MARKDOWN_DOCUMENT;
            if (lower.contains("rtf")) return ENTITY_RTF_DOCUMENT;
            if (lower.contains("epub")) return ENTITY_EPUB_DOCUMENT;
            if (lower.contains("yaml")) return ENTITY_YAML_DOCUMENT;
            if (lower.contains("text") || lower.contains("plain")) return ENTITY_TEXT_DOCUMENT;
            if (lower.contains("image")) return ENTITY_IMAGE_DOCUMENT;
            if (lower.contains("audio")) return ENTITY_AUDIO_DOCUMENT;
            if (lower.contains("video")) return ENTITY_VIDEO_DOCUMENT;
            if (lower.contains("json")) return ENTITY_JSON_DOCUMENT;
            if (lower.contains("xml")) return ENTITY_XML_DOCUMENT;
            if (lower.contains("ics") || lower.contains("calendar") || lower.contains("icalendar")) return ENTITY_ICS_DOCUMENT;
            if (lower.contains("vcard") || lower.contains("vcf")) return ENTITY_VCARD_DOCUMENT;
            if (lower.contains("tsv") || lower.contains("tab-separated")) return ENTITY_TSV_DOCUMENT;
            if (lower.contains("log")) return ENTITY_LOG_DOCUMENT;
        }
        // Filename-based fallback for types not indicated by docType
        String fn = meta != null ? str(meta.get(META_FILE_NAME)) : null;
        if (fn != null) {
            String lowerFn = fn.toLowerCase();
            if (lowerFn.endsWith(".ics")) return ENTITY_ICS_DOCUMENT;
            if (lowerFn.endsWith(".vcf")) return ENTITY_VCARD_DOCUMENT;
            if (lowerFn.endsWith(".tsv")) return ENTITY_TSV_DOCUMENT;
            if (lowerFn.endsWith(".log")) return ENTITY_LOG_DOCUMENT;
        }
        // OCR-processed image files
        Object ocrProcessed = meta != null ? meta.get(META_OCR_PROCESSED) : null;
        if (Boolean.TRUE.equals(ocrProcessed)) {
            String fileName = str(meta.get(META_FILE_NAME));
            if (fileName != null) {
                String lower = fileName.toLowerCase();
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".tiff") || lower.endsWith(".tif") || lower.endsWith(".bmp")) {
                    return ENTITY_OCR_IMAGE_DOCUMENT;
                }
            }
            return ENTITY_OCR_DOCUMENT;
        }
        return ENTITY_DOCUMENT;
    }
}
