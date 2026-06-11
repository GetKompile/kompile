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

package ai.kompile.loader.pdf;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.GraphConstants;
import static ai.kompile.core.graphrag.GraphConstants.*;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
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

/**
 * Deterministic graph extractor for PDF documents. Converts PDF metadata
 * (author, title, producer, keywords, hyperlinks, form fields, bookmarks)
 * into structured knowledge graph entities and relationships.
 *
 * <p>Entity types produced:</p>
 * <ul>
 *   <li><b>PDF_DOCUMENT</b> — the document itself</li>
 *   <li><b>PERSON</b> — from author/creator fields, annotation authors, and signature signers</li>
 *   <li><b>ORGANIZATION</b> — from producer field (e.g., "Microsoft Word", "Adobe Acrobat")</li>
 *   <li><b>TOPIC</b> — from keywords</li>
 *   <li><b>EXTERNAL_RESOURCE</b> — from hyperlink annotations</li>
 *   <li><b>PDF_ANNOTATION</b> — from text/sticky-note annotations (comments with content)</li>
 *   <li><b>PDF_SECTION</b> — from bookmarks (document structure)</li>
 *   <li><b>FORM_FIELD</b> — from interactive form fields</li>
 *   <li><b>EMBEDDED_FILE</b> — from files embedded in the PDF (via pdf.embeddedFiles metadata)</li>
 *   <li><b>PDF_SIGNATURE</b> — from digital signatures in the PDF (via pdf.signatures metadata)</li>
 * </ul>
 *
 * <p>Relationship types produced:</p>
 * <ul>
 *   <li><b>AUTHORED_BY</b> — document → person</li>
 *   <li><b>CREATED_BY</b> — document → person (creator vs author distinction)</li>
 *   <li><b>PRODUCED_BY</b> — document → organization (software that produced the PDF)</li>
 *   <li><b>HAS_TOPIC</b> — document → topic</li>
 *   <li><b>HYPERLINKS_TO</b> — document → external resource</li>
 *   <li><b>HAS_ANNOTATION</b> — document → PDF_ANNOTATION (text/sticky-note annotations)</li>
 *   <li><b>ANNOTATED_BY</b> — PDF_ANNOTATION → person (annotation author cross-link)</li>
 *   <li><b>HAS_SECTION</b> — document → section (from bookmarks)</li>
 *   <li><b>HAS_FORM_FIELD</b> — document → form field</li>
 *   <li><b>HAS_EMBEDDED_FILE</b> — document → embedded file</li>
 *   <li><b>HAS_SIGNATURE</b> — document → digital signature</li>
 *   <li><b>SIGNED_BY</b> — signature → person (signer)</li>
 * </ul>
 */
@Component
public class PdfGraphExtractor implements DocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfGraphExtractor.class);

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);

    /** Matches markdown-style headings (e.g., "## Section Title") in body text from VLM OCR or text extraction */
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /**
     * Matches VLM-generated image reference markers in the form "[Image: someObjectId]".
     * These are emitted by the VLM post-processor when a &lt;figure&gt; tag with an
     * objectId (or loc reference) is converted to plain text.
     */
    private static final Pattern VLM_IMAGE_MARKER_PATTERN = Pattern.compile(
            "\\[Image:\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);

    /**
     * Matches inline markdown image syntax: {@code ![alt text](url-or-path)}.
     * Produced by VLM markdown output and some PDF-to-markdown converters.
     */
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile(
            "!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /**
     * Matches unordered markdown list items: lines that start with "- ", "* ", or "+ ".
     * Used to identify list blocks in VLM body text.
     */
    private static final Pattern MARKDOWN_UNORDERED_LIST_PATTERN = Pattern.compile(
            "^[ \\t]*[-*+][ \\t]+(.+)$", Pattern.MULTILINE);

    /**
     * Matches ordered markdown list items: lines that start with a digit and a dot/paren.
     */
    private static final Pattern MARKDOWN_ORDERED_LIST_PATTERN = Pattern.compile(
            "^[ \\t]*(\\d+)[.)\\s][ \\t]+(.+)$", Pattern.MULTILINE);

    /**
     * Matches a markdown pipe-table row: lines containing at least two pipe characters
     * with content between them. Used to detect VLM-produced tables in body text.
     */
    private static final Pattern MARKDOWN_TABLE_ROW_PATTERN = Pattern.compile(
            "^\\|(.+\\|)+\\s*$", Pattern.MULTILINE);

    /** Matches a pipe-table separator row (e.g. "|---|---|"). */
    private static final Pattern MARKDOWN_TABLE_SEPARATOR = Pattern.compile(
            "^\\|[\\s:]*-{2,}[\\s:]*\\|");

    /** Matches lines written by PdfExtendedLoaderImpl: "Annotation on page N: <text> [subtype=X] [author=Y] [modified=Z]" */
    private static final Pattern ANNOTATION_LINE_PATTERN = Pattern.compile(
            "^Annotation on page (\\d+):\\s+(.+?)(?:\\s+\\[subtype=([^\\]]+)\\])?(?:\\s+\\[author=([^\\]]+)\\])?(?:\\s+\\[modified=([^\\]]+)\\])?$", Pattern.MULTILINE);

    /** Matches "Link on page N: <url>" lines emitted by PdfExtendedLoaderImpl for hyperlink annotations. */
    private static final Pattern LINK_ON_PAGE_PATTERN = Pattern.compile(
            "^Link on page (\\d+):\\s+(https?://\\S+)$", Pattern.MULTILINE);

    /** Matches "InternalLink on page N: page M" lines for GoTo cross-references within the PDF. */
    private static final Pattern INTERNAL_LINK_PATTERN = Pattern.compile(
            "^InternalLink on page (\\d+):\\s+page (\\d+)$", Pattern.MULTILINE);

    /** Matches LaTeX inline math: $...$ (not $$) */
    private static final Pattern LATEX_INLINE_MATH_PATTERN = Pattern.compile(
            "(?<!\\$)\\$(?!\\$)(.+?)(?<!\\$)\\$(?!\\$)");

    /** Matches LaTeX display math: $$...$$ or \[...\] */
    private static final Pattern LATEX_DISPLAY_MATH_PATTERN = Pattern.compile(
            "\\$\\$(.+?)\\$\\$|\\\\\\[(.+?)\\\\\\]", Pattern.DOTALL);

    /** Matches fenced code blocks: ```...``` */
    private static final Pattern FENCED_CODE_BLOCK_PATTERN = Pattern.compile(
            "^```(\\w*)\\n(.*?)^```", Pattern.MULTILINE | Pattern.DOTALL);

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("pdf");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        // Check for PDF-specific metadata set by PdfExtendedLoaderImpl
        String docType = str(meta.get(META_DOCUMENT_TYPE));
        if (docType != null && docType.toLowerCase().contains("pdf")) return true;
        String loader = str(meta.get(META_LOADER));
        if (loader != null && loader.toLowerCase().contains("pdf")) return true;
        String fileName = str(meta.get(META_FILE_NAME));
        if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) return true;
        // Also match OCR-processed PDFs
        Object ocrProcessed = meta.get(META_OCR_PROCESSED);
        if (Boolean.TRUE.equals(ocrProcessed) && fileName != null && fileName.toLowerCase().endsWith(".pdf")) return true;
        return false;
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        // Skip error documents to avoid ghost entities from corrupted PDFs
        if (Boolean.TRUE.equals(meta.get("parseError"))) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();

        // ── PDF_DOCUMENT entity ──────────────────────────────────────────
        String fileName = str(meta.get(META_FILE_NAME));
        String title = str(meta.get(META_TITLE));
        String source = str(meta.get(META_SOURCE));
        String displayTitle = title != null ? title : (fileName != null ? fileName : "Untitled PDF");

        String docEntityId = entityId("pdf:" + (source != null ? source : displayTitle));
        Map<String, String> docProps = new LinkedHashMap<>();
        if (fileName != null) docProps.put(META_FILE_NAME, fileName);
        if (title != null) docProps.put(META_TITLE, title);
        if (meta.get(META_PAGE_COUNT) != null) docProps.put(META_PAGE_COUNT, String.valueOf(meta.get(META_PAGE_COUNT)));
        if (meta.get(META_FILE_SIZE) != null) docProps.put(META_FILE_SIZE, String.valueOf(meta.get(META_FILE_SIZE)));
        if (meta.get(META_SUBJECT) != null) docProps.put(META_SUBJECT, str(meta.get(META_SUBJECT)));
        if (meta.get(META_CREATION_DATE) != null) docProps.put(META_CREATION_DATE, String.valueOf(meta.get(META_CREATION_DATE)));
        if (meta.get(META_MODIFICATION_DATE) != null) docProps.put(META_MODIFICATION_DATE, String.valueOf(meta.get(META_MODIFICATION_DATE)));
        String language = str(meta.get(META_PDF_LANGUAGE));
        if (language == null) language = str(meta.get(META_LANGUAGE));
        if (language != null) docProps.put(PROP_LANGUAGE, language);

        ExtractedEntity docEntity = new ExtractedEntity(
                docEntityId, displayTitle, GraphConstants.ENTITY_PDF_DOCUMENT,
                null, "PDF document: " + displayTitle, 1.0, docProps
        );
        addEntity(entityIndex, docEntity);

        // ── PERSON from author ───────────────────────────────────────────
        String author = str(meta.get(META_AUTHOR));
        if (author != null) {
            for (String name : splitAuthors(author)) {
                String personId = entityId("person:" + name.toLowerCase());
                ExtractedEntity personEntity = new ExtractedEntity(
                        personId, name, GraphConstants.ENTITY_PERSON,
                        null, "Document author", 0.9,
                        Map.of(GraphConstants.PROP_SOURCE_FIELD, "author")
                );
                addEntity(entityIndex, personEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, personId, GraphConstants.REL_AUTHORED_BY,
                        displayTitle + " authored by " + name,
                        0.9, null
                ));
            }
        }

        // ── PERSON from creator (may differ from author) ─────────────────
        String creator = str(meta.get(META_CREATOR));
        if (creator != null && !creator.equals(author)) {
            if (!looksLikeSoftware(creator)) {
                // Treat as a person
                String creatorId = entityId("person:" + creator.toLowerCase());
                ExtractedEntity creatorEntity = new ExtractedEntity(
                        creatorId, creator, GraphConstants.ENTITY_PERSON,
                        null, "Document creator", 0.8,
                        Map.of(GraphConstants.PROP_SOURCE_FIELD, "creator")
                );
                addEntity(entityIndex, creatorEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, creatorId, GraphConstants.REL_CREATED_BY,
                        displayTitle + " created by " + creator,
                        0.8, null
                ));
            } else {
                // Looks like software — create an ORGANIZATION entity with PRODUCED_BY
                String creatorOrgId = entityId("org:" + creator.toLowerCase());
                ExtractedEntity creatorOrgEntity = new ExtractedEntity(
                        creatorOrgId, creator, GraphConstants.ENTITY_ORGANIZATION,
                        null, "PDF creating software/organization", 0.7,
                        Map.of(GraphConstants.PROP_SOURCE_FIELD, "creator")
                );
                addEntity(entityIndex, creatorOrgEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, creatorOrgId, GraphConstants.REL_PRODUCED_BY,
                        displayTitle + " produced by " + creator,
                        0.7, null
                ));
            }
        }

        // ── ORGANIZATION from producer ───────────────────────────────────
        String producer = str(meta.get(META_PRODUCER));
        if (producer != null) {
            String orgId = entityId("org:" + producer.toLowerCase());
            ExtractedEntity orgEntity = new ExtractedEntity(
                    orgId, producer, GraphConstants.ENTITY_ORGANIZATION,
                    null, "PDF producing software/organization", 0.7,
                    Map.of(GraphConstants.PROP_SOURCE_FIELD, "producer")
            );
            addEntity(entityIndex, orgEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, orgId, GraphConstants.REL_PRODUCED_BY,
                    displayTitle + " produced by " + producer,
                    0.7, null
            ));
        }

        // ── TOPICs from keywords ─────────────────────────────────────────
        String keywords = str(meta.get(META_KEYWORDS));
        if (keywords != null) {
            for (String keyword : splitKeywords(keywords)) {
                String topicId = entityId("topic:" + keyword.toLowerCase());
                ExtractedEntity topicEntity = new ExtractedEntity(
                        topicId, keyword, GraphConstants.ENTITY_TOPIC,
                        null, "Document keyword/topic", 0.8,
                        Map.of(GraphConstants.PROP_SOURCE_FIELD, "keywords")
                );
                addEntity(entityIndex, topicEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, topicId, GraphConstants.REL_HAS_TOPIC,
                        displayTitle + " has topic: " + keyword,
                        0.8, null
                ));
            }
        }

        // ── TOPIC from subject metadata field ────────────────────────────
        String subject = str(meta.get(META_SUBJECT));
        if (subject != null) {
            String subjectTopicId = entityId("topic:" + subject.toLowerCase());
            ExtractedEntity subjectTopicEntity = new ExtractedEntity(
                    subjectTopicId, subject, GraphConstants.ENTITY_TOPIC,
                    null, "Document subject/topic", 0.85,
                    Map.of(GraphConstants.PROP_SOURCE_FIELD, "subject")
            );
            addEntity(entityIndex, subjectTopicEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, subjectTopicId, GraphConstants.REL_HAS_TOPIC,
                    displayTitle + " has topic: " + subject,
                    0.85, null
            ));
        }

        // ── Capture lastModified if available ────────────────────────────
        String lastModified = str(meta.get(META_LAST_MODIFIED));
        if (lastModified != null && docProps.get(META_MODIFICATION_DATE) == null) {
            docProps.put(META_LAST_MODIFIED, lastModified);
        }

        // ── DATE entities from creation/modification dates ────────────────
        String creationDate = str(meta.get(META_CREATION_DATE));
        if (creationDate != null) {
            String dateId = entityId("date:" + creationDate);
            addEntity(entityIndex, new ExtractedEntity(
                    dateId, creationDate, GraphConstants.ENTITY_DATE,
                    null, "Creation date: " + creationDate, 0.85,
                    Map.of("date", creationDate, "dateType", "created")));
            relations.add(new ExtractedRelation(
                    docEntityId, dateId, GraphConstants.REL_PUBLISHED_ON,
                    displayTitle + " created on " + creationDate,
                    0.85, null));
        }
        String modificationDate = str(meta.get(META_MODIFICATION_DATE));
        if (modificationDate == null) modificationDate = lastModified;
        if (modificationDate != null) {
            String modDateId = entityId("date:" + modificationDate);
            if (!entityIndex.containsKey(modDateId)) {
                addEntity(entityIndex, new ExtractedEntity(
                        modDateId, modificationDate, GraphConstants.ENTITY_DATE,
                        null, "Modification date: " + modificationDate, 0.85,
                        Map.of("date", modificationDate, "dateType", "modified")));
            }
            relations.add(new ExtractedRelation(
                    docEntityId, modDateId, GraphConstants.REL_MODIFIED_ON,
                    displayTitle + " modified on " + modificationDate,
                    0.85, null));
        }

        // ── EXTERNAL_RESOURCE from hyperlink annotations ─────────────────
        // ── PDF_ANNOTATION from text/sticky-note annotations ─────────────
        String extractionType = str(meta.get(META_PDF_EXTRACTION_TYPE));
        if (EXTRACTION_TYPE_ANNOTATIONS.equals(extractionType)) {
            // Store annotation count as a property
            Object linkCount = meta.get(META_PDF_LINK_COUNT);
            if (linkCount != null) docProps.put(PROP_LINK_COUNT, linkCount.toString());
            // The annotations document contains two line formats written by PdfExtendedLoaderImpl:
            //   "Link on page N: <url>"       — URL link annotations
            //   "Annotation on page N: <text>" — text/sticky-note annotations
            String text = doc.getText();
            if (text != null) {
                // Extract URL hyperlink annotations with page context
                Matcher linkPageMatcher = LINK_ON_PAGE_PATTERN.matcher(text);
                Set<String> seenUrls = new HashSet<>();
                while (linkPageMatcher.find()) {
                    String linkPageNum = linkPageMatcher.group(1);
                    String url = linkPageMatcher.group(2);
                    if (seenUrls.add(url.toLowerCase())) {
                        String urlId = entityId("url:" + url.toLowerCase());
                        ExtractedEntity urlEntity = new ExtractedEntity(
                                urlId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "Hyperlink target from PDF page " + linkPageNum, 0.9,
                                Map.of("url", url, PROP_PAGE_NUMBER, linkPageNum)
                        );
                        addEntity(entityIndex, urlEntity);
                        relations.add(new ExtractedRelation(
                                docEntityId, urlId, GraphConstants.REL_HYPERLINKS_TO,
                                displayTitle + " links to " + url,
                                0.9, null
                        ));
                        // Link the EXTERNAL_RESOURCE to its PDF_PAGE via ON_PAGE
                        String linkTargetPageId = entityId("page:" + docEntityId + ":p" + linkPageNum);
                        Map<String, String> linkPageProps = new LinkedHashMap<>();
                        linkPageProps.put(PROP_PAGE_NUMBER, linkPageNum);
                        addEntity(entityIndex, new ExtractedEntity(
                                linkTargetPageId, "Page " + linkPageNum, GraphConstants.ENTITY_PDF_PAGE,
                                null, "PDF page " + linkPageNum, 0.85, linkPageProps));
                        relations.add(new ExtractedRelation(
                                urlId, linkTargetPageId, GraphConstants.REL_ON_PAGE,
                                "Link to " + url + " on page " + linkPageNum,
                                0.85, null
                        ));
                    }
                }
                // Also catch any URLs not in "Link on page" format (fallback)
                Matcher urlMatcher = URL_PATTERN.matcher(text);
                while (urlMatcher.find()) {
                    String url = urlMatcher.group();
                    if (seenUrls.add(url.toLowerCase())) {
                        String urlId = entityId("url:" + url.toLowerCase());
                        addEntity(entityIndex, new ExtractedEntity(
                                urlId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                null, "Hyperlink target from PDF annotations", 0.9,
                                Map.of("url", url)));
                        relations.add(new ExtractedRelation(
                                docEntityId, urlId, GraphConstants.REL_HYPERLINKS_TO,
                                displayTitle + " links to " + url, 0.9, null));
                    }
                }

                // Extract internal GoTo cross-references as page-to-page relations
                Matcher internalLinkMatcher = INTERNAL_LINK_PATTERN.matcher(text);
                while (internalLinkMatcher.find()) {
                    String srcPage = internalLinkMatcher.group(1);
                    String targetPage = internalLinkMatcher.group(2);
                    // Create page entities for both source and target
                    String srcPageId = entityId("page:" + docEntityId + ":p" + srcPage);
                    Map<String, String> srcPageProps = new LinkedHashMap<>();
                    srcPageProps.put(PROP_PAGE_NUMBER, srcPage);
                    addEntity(entityIndex, new ExtractedEntity(
                            srcPageId, "Page " + srcPage, GraphConstants.ENTITY_PDF_PAGE,
                            null, "PDF page " + srcPage, 0.85, srcPageProps));
                    String tgtPageId = entityId("page:" + docEntityId + ":p" + targetPage);
                    Map<String, String> tgtPageProps = new LinkedHashMap<>();
                    tgtPageProps.put(PROP_PAGE_NUMBER, targetPage);
                    addEntity(entityIndex, new ExtractedEntity(
                            tgtPageId, "Page " + targetPage, GraphConstants.ENTITY_PDF_PAGE,
                            null, "PDF page " + targetPage, 0.85, tgtPageProps));
                    relations.add(new ExtractedRelation(
                            srcPageId, tgtPageId, GraphConstants.REL_INTERNAL_LINK_TO,
                            "Page " + srcPage + " links to page " + targetPage,
                            0.85, null));
                }

                // Extract text/sticky-note annotations as PDF_ANNOTATION entities
                Matcher annotMatcher = ANNOTATION_LINE_PATTERN.matcher(text);
                while (annotMatcher.find()) {
                    String pageNum = annotMatcher.group(1);
                    String annotText = annotMatcher.group(2).trim();
                    String subtype = annotMatcher.group(3);
                    String annotAuthor = annotMatcher.group(4);
                    String modifiedDate = annotMatcher.group(5);
                    if (!annotText.isEmpty()) {
                        // Truncate long annotation text for the entity name (use full text as description)
                        String entityName = annotText.length() > 80
                                ? annotText.substring(0, 77) + "..."
                                : annotText;
                        String annotId = entityId("annotation:" + docEntityId + ":p" + pageNum + ":" + annotText.toLowerCase());
                        Map<String, String> annotProps = new LinkedHashMap<>();
                        annotProps.put(PROP_PAGE_NUMBER, pageNum);
                        annotProps.put(PROP_TEXT, annotText);
                        if (subtype != null) annotProps.put(PROP_SUBTYPE, subtype);
                        if (annotAuthor != null) annotProps.put(PROP_AUTHOR, annotAuthor);
                        if (modifiedDate != null) annotProps.put(PROP_MODIFIED_DATE, modifiedDate);
                        ExtractedEntity annotEntity = new ExtractedEntity(
                                annotId, entityName, GraphConstants.ENTITY_PDF_ANNOTATION,
                                null, "Text annotation on page " + pageNum + ": " + annotText, 0.85,
                                annotProps
                        );
                        addEntity(entityIndex, annotEntity);
                        relations.add(new ExtractedRelation(
                                docEntityId, annotId, GraphConstants.REL_HAS_ANNOTATION,
                                displayTitle + " has annotation on page " + pageNum,
                                0.85, null
                        ));

                        // Link annotation to its page entity
                        String annotPageId = entityId("page:" + docEntityId + ":p" + pageNum);
                        Map<String, String> annotPageProps = new LinkedHashMap<>();
                        annotPageProps.put(PROP_PAGE_NUMBER, pageNum);
                        addEntity(entityIndex, new ExtractedEntity(
                                annotPageId, "Page " + pageNum, GraphConstants.ENTITY_PDF_PAGE,
                                null, "PDF page " + pageNum, 0.85, annotPageProps));
                        relations.add(new ExtractedRelation(
                                annotId, annotPageId, GraphConstants.REL_ON_PAGE,
                                "Annotation on page " + pageNum,
                                0.85, null
                        ));

                        // ── ANNOTATED_BY → PERSON cross-link ────────────────────────
                        if (annotAuthor != null) {
                            String authorPersonId = entityId("person:" + annotAuthor.toLowerCase());
                            ExtractedEntity authorPersonEntity = new ExtractedEntity(
                                    authorPersonId, annotAuthor, GraphConstants.ENTITY_PERSON,
                                    null, "Annotation author", 0.8,
                                    Map.of(GraphConstants.PROP_SOURCE_FIELD, "annotation_author")
                            );
                            addEntity(entityIndex, authorPersonEntity);
                            relations.add(new ExtractedRelation(
                                    annotId, authorPersonId, GraphConstants.REL_ANNOTATED_BY,
                                    "Annotation on page " + pageNum + " annotated by " + annotAuthor,
                                    0.8, null
                            ));
                        }
                    }
                }
            }
        }

        // ── PDF_SECTION from bookmarks (hierarchical parent-child tree) ──
        if (EXTRACTION_TYPE_BOOKMARKS.equals(extractionType)) {
            String text = doc.getText();
            if (text != null) {
                String[] lines = text.split("\n");
                // Stack tracks parent section IDs at each depth level
                // depthStack[d] = entity ID of the most recent section at depth d
                Map<Integer, String> depthStack = new LinkedHashMap<>();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    int depth = 0;
                    for (int i = 0; i < line.length() - 1; i += 2) {
                        if (line.charAt(i) == ' ' && line.charAt(i + 1) == ' ') depth++;
                        else break;
                    }
                    // Parse page number from "[page=N]" suffix if present
                    String sectionTitle = trimmed;
                    String bookmarkPageNum = null;
                    int pageTagStart = trimmed.indexOf(" [page=");
                    if (pageTagStart >= 0) {
                        int pageTagEnd = trimmed.indexOf("]", pageTagStart);
                        if (pageTagEnd > pageTagStart) {
                            bookmarkPageNum = trimmed.substring(pageTagStart + 7, pageTagEnd);
                            sectionTitle = trimmed.substring(0, pageTagStart).trim();
                        }
                    }
                    // Also strip "[destination=resolved]" suffix
                    int destTagStart = sectionTitle.indexOf(" [destination=");
                    if (destTagStart >= 0) {
                        sectionTitle = sectionTitle.substring(0, destTagStart).trim();
                    }
                    if (sectionTitle.isEmpty()) continue;
                    String sectionId = entityId("section:" + docEntityId + ":" + sectionTitle.toLowerCase());
                    Map<String, String> sectionProps = new LinkedHashMap<>();
                    sectionProps.put(GraphConstants.PROP_DEPTH, String.valueOf(depth));
                    if (bookmarkPageNum != null) sectionProps.put(GraphConstants.PROP_PAGE_NUMBER, bookmarkPageNum);
                    ExtractedEntity sectionEntity = new ExtractedEntity(
                            sectionId, sectionTitle, GraphConstants.ENTITY_PDF_SECTION,
                            null, "PDF bookmark/section", 0.85, sectionProps
                    );
                    addEntity(entityIndex, sectionEntity);
                    // Link section to page entity if page number is known
                    if (bookmarkPageNum != null) {
                        String pageId = entityId("page:" + docEntityId + ":p" + bookmarkPageNum);
                        Map<String, String> pageProps = new LinkedHashMap<>();
                        pageProps.put(GraphConstants.PROP_PAGE_NUMBER, bookmarkPageNum);
                        addEntity(entityIndex, new ExtractedEntity(
                                pageId, "Page " + bookmarkPageNum, GraphConstants.ENTITY_PDF_PAGE,
                                null, "PDF page " + bookmarkPageNum, 0.85, pageProps));
                        relations.add(new ExtractedRelation(
                                sectionId, pageId, GraphConstants.REL_ON_PAGE,
                                sectionTitle + " is on page " + bookmarkPageNum,
                                0.85, null));
                    }

                    if (depth == 0) {
                        // Top-level section: linked directly to document
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                                displayTitle + " has section: " + sectionTitle,
                                0.85, null
                        ));
                    } else {
                        // Find nearest parent at depth-1 (or deeper ancestor if gap)
                        String parentId = null;
                        for (int d = depth - 1; d >= 0; d--) {
                            if (depthStack.containsKey(d)) {
                                parentId = depthStack.get(d);
                                break;
                            }
                        }
                        if (parentId != null) {
                            // Child section: SUBSECTION_OF parent
                            relations.add(new ExtractedRelation(
                                    sectionId, parentId, GraphConstants.REL_SUBSECTION_OF,
                                    sectionTitle + " is subsection of parent bookmark",
                                    0.85, null
                            ));
                        } else {
                            // Fallback: link to document if no parent found
                            relations.add(new ExtractedRelation(
                                    docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                                    displayTitle + " has section: " + sectionTitle,
                                    0.85, null
                            ));
                        }
                    }
                    // Update depth stack — this section becomes the reference for its depth
                    depthStack.put(depth, sectionId);
                    // Clear any deeper entries (they're no longer valid parents)
                    final int currentDepth = depth;
                    depthStack.entrySet().removeIf(e -> e.getKey() > currentDepth);
                }
            }
        }

        // ── FORM_FIELD from form fields ──────────────────────────────────
        if (EXTRACTION_TYPE_FORM_FIELDS.equals(extractionType)) {
            Object fieldCount = meta.get(META_PDF_FIELD_COUNT);
            if (fieldCount != null) docProps.put(PROP_FIELD_COUNT, fieldCount.toString());

            // Create parent PDF_FORM entity
            String formEntityId = entityId("form:" + docEntityId);
            Map<String, String> formProps = new LinkedHashMap<>();
            if (fieldCount != null) formProps.put(PROP_FIELD_COUNT, fieldCount.toString());
            ExtractedEntity formEntity = new ExtractedEntity(
                    formEntityId, "Form in " + displayTitle, GraphConstants.ENTITY_PDF_FORM,
                    null, "PDF AcroForm with " + fieldCount + " fields", 0.95, formProps);
            addEntity(entityIndex, formEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, formEntityId, GraphConstants.REL_HAS_FORM,
                    displayTitle + " has a form", 0.95, null));

            @SuppressWarnings("unchecked")
            List<Map<String, String>> formFields = meta.get(META_PDF_FORM_FIELDS) instanceof List
                    ? (List<Map<String, String>>) meta.get(META_PDF_FORM_FIELDS) : null;

            if (formFields != null) {
                for (Map<String, String> fieldInfo : formFields) {
                    String fieldName = fieldInfo.get("name");
                    if (fieldName == null) continue;
                    String fieldType = fieldInfo.getOrDefault("fieldType", "");
                    String fieldValue = fieldInfo.getOrDefault("value", "");
                    String required = fieldInfo.get("required");
                    String readOnly = fieldInfo.get("readOnly");

                    String fieldId = entityId("field:" + docEntityId + ":" + fieldName);
                    Map<String, String> fieldProps = new LinkedHashMap<>();
                    fieldProps.put("fieldType", fieldType);
                    if (!fieldValue.isEmpty()) fieldProps.put("value", fieldValue);
                    if ("true".equals(required)) fieldProps.put("required", "true");
                    if ("true".equals(readOnly)) fieldProps.put("readOnly", "true");

                    ExtractedEntity fieldEntity = new ExtractedEntity(
                            fieldId, fieldName, GraphConstants.ENTITY_FORM_FIELD,
                            null, "PDF form field: " + fieldName + " (" + fieldType + ")",
                            0.95, fieldProps);
                    addEntity(entityIndex, fieldEntity);
                    relations.add(new ExtractedRelation(
                            formEntityId, fieldId, GraphConstants.REL_HAS_FORM_FIELD,
                            "Form has field: " + fieldName, 0.95, null));
                }
            } else {
                // Fallback: parse from text content (backward compatibility)
                String text = doc.getText();
                if (text != null) {
                    // Format: "Field: <name> (<type>) = <value>"
                    Pattern fieldPattern = Pattern.compile("Field: (.+?) \\((.+?)\\) =\\s?(.*)");
                    for (String line : text.split("\n")) {
                        Matcher m = fieldPattern.matcher(line.trim());
                        if (m.matches()) {
                            String fieldName = m.group(1);
                            String fieldType = m.group(2);
                            String fieldValue = m.group(3);
                            String fieldId = entityId("field:" + docEntityId + ":" + fieldName);
                            Map<String, String> fieldProps = new LinkedHashMap<>();
                            fieldProps.put("fieldType", fieldType);
                            if (!fieldValue.isEmpty()) fieldProps.put("value", fieldValue);
                            ExtractedEntity fieldEntity = new ExtractedEntity(
                                    fieldId, fieldName, GraphConstants.ENTITY_FORM_FIELD,
                                    null, "PDF form field: " + fieldName + " (" + fieldType + ")",
                                    0.95, fieldProps);
                            addEntity(entityIndex, fieldEntity);
                            relations.add(new ExtractedRelation(
                                    formEntityId, fieldId, GraphConstants.REL_HAS_FORM_FIELD,
                                    "Form has field: " + fieldName, 0.95, null));
                        }
                    }
                }
            }
        }

        // ── PDF_PAGE from per-page documents (extractByPage or streaming mode) ────
        if (EXTRACTION_TYPE_SINGLE_PAGE.equals(extractionType)
                || EXTRACTION_TYPE_STREAMING.equals(extractionType)) {
            Object pageNum = meta.get(META_PDF_PAGE_NUMBER);
            Object totalPages = meta.get(META_PDF_TOTAL_PAGES);
            if (pageNum != null) {
                String pageId = entityId("page:" + docEntityId + ":p" + pageNum);
                Map<String, String> pageProps = new LinkedHashMap<>();
                pageProps.put(PROP_PAGE_NUMBER, pageNum.toString());
                if (totalPages != null) pageProps.put(PROP_TOTAL_PAGES, totalPages.toString());
                // Enrich page entity with OCR metadata if this page was OCR-processed
                Object pageOcrProcessed = meta.get(META_OCR_PROCESSED);
                if (Boolean.TRUE.equals(pageOcrProcessed)) {
                    pageProps.put(PROP_OCR_PROCESSED, "true");
                    String pageProcessingMode = str(meta.get(META_PDF_PROCESSING_MODE));
                    if (pageProcessingMode != null) pageProps.put(PROP_PROCESSING_MODE, pageProcessingMode);
                    String pageVlmModel = str(meta.get(META_VLM_MODEL));
                    if (pageVlmModel != null) pageProps.put(PROP_VLM_MODEL, pageVlmModel);
                    String pageOcrConf = str(meta.get(META_OCR_CONFIDENCE));
                    if (pageOcrConf != null) pageProps.put(META_OCR_CONFIDENCE, pageOcrConf);
                }
                ExtractedEntity pageEntity = new ExtractedEntity(
                        pageId, "Page " + pageNum, GraphConstants.ENTITY_PDF_PAGE,
                        null, "PDF page " + pageNum + " of " + displayTitle, 0.9, pageProps
                );
                addEntity(entityIndex, pageEntity);
                relations.add(new ExtractedRelation(
                        docEntityId, pageId, GraphConstants.REL_HAS_PAGE,
                        displayTitle + " has page " + pageNum,
                        0.9, null
                ));
            }
        }

        // ── PDF_PAGE entities for full-document mode ────────────────────
        // In full-document mode, no per-page documents exist, so generate
        // page entities from the pageCount metadata to ensure the graph
        // captures the document's page structure.
        if (EXTRACTION_TYPE_FULL_DOCUMENT.equals(extractionType)
                || extractionType == null) {
            Object pageCountObj = meta.get(META_PAGE_COUNT);
            if (pageCountObj != null) {
                int pageCount;
                try {
                    pageCount = Integer.parseInt(String.valueOf(pageCountObj));
                } catch (NumberFormatException e) {
                    pageCount = 0;
                }
                // Safety cap: don't create hundreds of empty page nodes for very large PDFs
                int maxPages = Math.min(pageCount, 200);
                for (int p = 1; p <= maxPages; p++) {
                    String pageId = entityId("page:" + docEntityId + ":p" + p);
                    // Skip if this page entity was already created by annotations/bookmarks
                    if (entityIndex.containsKey(pageId)) continue;
                    Map<String, String> pageProps = new LinkedHashMap<>();
                    pageProps.put(PROP_PAGE_NUMBER, String.valueOf(p));
                    pageProps.put(PROP_TOTAL_PAGES, String.valueOf(pageCount));
                    ExtractedEntity pageEntity = new ExtractedEntity(
                            pageId, "Page " + p, GraphConstants.ENTITY_PDF_PAGE,
                            null, "PDF page " + p + " of " + displayTitle, 0.85, pageProps
                    );
                    addEntity(entityIndex, pageEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, pageId, GraphConstants.REL_HAS_PAGE,
                            displayTitle + " has page " + p,
                            0.85, null
                    ));
                }
            }
        }

        // ── PDF_TABLE from table extraction (PdfTableLoaderImpl/Tabula) ──
        String pdfContentType = str(meta.get(META_CONTENT_TYPE));
        if ("table".equals(pdfContentType)) {
            String tableId2 = str(meta.get(META_PDF_TABLE_ID));
            Object tablePageNum = meta.get(META_PDF_TABLE_PAGE_NUMBER);
            Object tableIdx = meta.get(META_TABLE_INDEX);
            Object rowCount = meta.get(META_TABLE_ROW_COUNT);
            Object colCount = meta.get(META_TABLE_COLUMN_COUNT);
            String tableHeaders = str(meta.get(META_TABLE_HEADERS));
            String extractionMethod = str(meta.get(META_PDF_TABLE_EXTRACTION_METHOD));

            String tblName = "Table" + (tableIdx != null ? " " + tableIdx : "")
                    + (tablePageNum != null ? " (p" + tablePageNum + ")" : "");
            String tblEntityId = entityId("pdftable:" + (tableId2 != null ? tableId2 : source + ":" + tblName));
            Map<String, String> tblProps = new LinkedHashMap<>();
            if (tableId2 != null) tblProps.put(PROP_TABLE_ID, tableId2);
            if (tablePageNum != null) tblProps.put(PROP_PAGE_NUMBER, tablePageNum.toString());
            if (tableIdx != null) tblProps.put(PROP_TABLE_INDEX, tableIdx.toString());
            if (rowCount != null) tblProps.put(PROP_ROW_COUNT, rowCount.toString());
            if (colCount != null) tblProps.put(PROP_COLUMN_COUNT, colCount.toString());
            if (tableHeaders != null) tblProps.put(PROP_HEADERS, tableHeaders);
            if (extractionMethod != null) tblProps.put(PROP_EXTRACTION_METHOD, extractionMethod);
            String tableSummary = str(meta.get("table_summary"));
            if (tableSummary != null) tblProps.put("summary", tableSummary);

            ExtractedEntity tblEntity = new ExtractedEntity(
                    tblEntityId, tblName, GraphConstants.ENTITY_PDF_TABLE,
                    null, "PDF table: " + tblName + " in " + displayTitle, 0.95, tblProps
            );
            addEntity(entityIndex, tblEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, tblEntityId, GraphConstants.REL_HAS_TABLE,
                    displayTitle + " has " + tblName,
                    0.95, null
            ));

            // Link table to its page entity if page number is available
            if (tablePageNum != null) {
                String pageId = entityId("page:" + docEntityId + ":p" + tablePageNum);
                Map<String, String> pageProps = new LinkedHashMap<>();
                pageProps.put(PROP_PAGE_NUMBER, tablePageNum.toString());
                ExtractedEntity pageEntity = new ExtractedEntity(
                        pageId, "Page " + tablePageNum, GraphConstants.ENTITY_PDF_PAGE,
                        null, "PDF page " + tablePageNum, 0.85, pageProps
                );
                addEntity(entityIndex, pageEntity);
                relations.add(new ExtractedRelation(
                        tblEntityId, pageId, GraphConstants.REL_ON_PAGE,
                        tblName + " is on page " + tablePageNum,
                        0.9, null
                ));
            }
        }

        // ── Cell-level graph from META_TABLE_GRAPH (produced by TableCellGraphBuilder) ──
        Object tableGraphObj = meta.get(META_TABLE_GRAPH);
        if (tableGraphObj instanceof String tableGraphJson && !tableGraphJson.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Graph cellGraph = mapper.readValue(tableGraphJson, Graph.class);
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
                log.warn("Failed to parse tableGraph JSON for PDF document: {}", e.getMessage());
            }
        }

        // ── EMBEDDED_FILE entities from pdf.embeddedFiles metadata ──────
        Object embeddedFilesRaw = meta.get(META_PDF_EMBEDDED_FILES);
        if (embeddedFilesRaw instanceof List<?> embeddedFilesList) {
            for (Object item : embeddedFilesList) {
                if (item instanceof Map<?, ?> fileMap) {
                    String efName = str(fileMap.get("name"));
                    if (efName == null) continue;
                    String efMimeType = str(fileMap.get("mimeType"));
                    String efSize = str(fileMap.get("size"));
                    String efId = entityId("embeddedfile:" + docEntityId + ":" + efName.toLowerCase());
                    Map<String, String> efProps = new LinkedHashMap<>();
                    efProps.put("name", efName);
                    if (efMimeType != null) efProps.put("mimeType", efMimeType);
                    if (efSize != null) efProps.put("size", efSize);
                    ExtractedEntity efEntity = new ExtractedEntity(
                            efId, efName, GraphConstants.ENTITY_EMBEDDED_FILE,
                            null, "Embedded file: " + efName + " in " + displayTitle, 0.9, efProps
                    );
                    addEntity(entityIndex, efEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, efId, GraphConstants.REL_HAS_EMBEDDED_FILE,
                            displayTitle + " has embedded file: " + efName,
                            0.9, null
                    ));
                }
            }
        }

        // ── PDF_SIGNATURE entities from pdf.signatures metadata ──────────
        Object signaturesRaw = meta.get(META_PDF_SIGNATURES);
        if (signaturesRaw instanceof List<?> signaturesList) {
            int sigIndex = 0;
            for (Object item : signaturesList) {
                if (item instanceof Map<?, ?> sigMap) {
                    sigIndex++;
                    String sigName = str(sigMap.get("name"));
                    String sigReason = str(sigMap.get("reason"));
                    String sigLocation = str(sigMap.get("location"));
                    String sigContact = str(sigMap.get("contactInfo"));
                    String sigDate = str(sigMap.get("signDate"));
                    String sigDisplayName = sigName != null ? sigName : "Signature " + sigIndex;
                    String sigId = entityId("signature:" + docEntityId + ":" + sigIndex + ":" + sigDisplayName.toLowerCase());
                    Map<String, String> sigProps = new LinkedHashMap<>();
                    if (sigName != null) sigProps.put("name", sigName);
                    if (sigReason != null) sigProps.put("reason", sigReason);
                    if (sigLocation != null) sigProps.put("location", sigLocation);
                    if (sigContact != null) sigProps.put("contactInfo", sigContact);
                    if (sigDate != null) sigProps.put("signDate", sigDate);
                    ExtractedEntity sigEntity = new ExtractedEntity(
                            sigId, sigDisplayName, GraphConstants.ENTITY_PDF_SIGNATURE,
                            null, "Digital signature in " + displayTitle
                                  + (sigReason != null ? ": " + sigReason : ""), 0.95, sigProps
                    );
                    addEntity(entityIndex, sigEntity);
                    relations.add(new ExtractedRelation(
                            docEntityId, sigId, GraphConstants.REL_HAS_SIGNATURE,
                            displayTitle + " has digital signature: " + sigDisplayName,
                            0.95, null
                    ));

                    // Parse certificate subject name: extract CN for person, O for org
                    if (sigName != null) {
                        String personName = parseCertCN(sigName);
                        String signerPersonId = entityId("person:" + personName.toLowerCase());
                        Map<String, String> signerProps = new LinkedHashMap<>();
                        signerProps.put(GraphConstants.PROP_SOURCE_FIELD, "signature_name");
                        if (!personName.equals(sigName)) signerProps.put("rawCertSubject", sigName);
                        if (sigContact != null) signerProps.put("contactInfo", sigContact);
                        ExtractedEntity signerEntity = new ExtractedEntity(
                                signerPersonId, personName, GraphConstants.ENTITY_PERSON,
                                null, "PDF signer", 0.8, signerProps
                        );
                        addEntity(entityIndex, signerEntity);
                        relations.add(new ExtractedRelation(
                                sigId, signerPersonId, GraphConstants.REL_SIGNED_BY,
                                sigDisplayName + " signed by " + personName,
                                0.8, null
                        ));
                        // Extract organization from cert subject O= field
                        String certOrg = parseCertField(sigName, "O");
                        if (certOrg != null) {
                            String orgId = entityId("org:" + certOrg.toLowerCase());
                            addEntity(entityIndex, new ExtractedEntity(
                                    orgId, certOrg, GraphConstants.ENTITY_ORGANIZATION,
                                    null, "Signing organization", 0.7,
                                    Map.of(GraphConstants.PROP_SOURCE_FIELD, "signature_org")));
                            relations.add(new ExtractedRelation(
                                    signerPersonId, orgId, GraphConstants.REL_AFFILIATED_WITH,
                                    personName + " affiliated with " + certOrg,
                                    0.7, null));
                        }
                    }
                    // Create DATE entity from signing date
                    if (sigDate != null && !sigDate.isBlank()) {
                        String sigDateId = entityId("date:" + sigDate);
                        addEntity(entityIndex, new ExtractedEntity(
                                sigDateId, sigDate, GraphConstants.ENTITY_DATE,
                                null, "Signature date: " + sigDate, 0.85,
                                Map.of("date", sigDate, "dateType", "signed")));
                        relations.add(new ExtractedRelation(
                                sigId, sigDateId, GraphConstants.REL_SIGNED_ON,
                                sigDisplayName + " signed on " + sigDate,
                                0.85, null));
                    }
                    // Create LOCATION entity from signing location
                    if (sigLocation != null && !sigLocation.isBlank()) {
                        String locId = entityId("location:" + sigLocation.toLowerCase());
                        addEntity(entityIndex, new ExtractedEntity(
                                locId, sigLocation, GraphConstants.ENTITY_LOCATION,
                                null, "Signing location: " + sigLocation, 0.8,
                                Map.of("locationName", sigLocation)));
                        relations.add(new ExtractedRelation(
                                sigId, locId, GraphConstants.REL_LOCATED_AT,
                                "Signed at " + sigLocation,
                                0.8, null));
                    }
                }
            }
        }

        // ── OCR metadata enrichment ─────────────────────────────────────
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

        // ── XMP metadata enrichment ─────────────────────────────────────
        String xmpRights = str(meta.get(META_PDF_XMP_RIGHTS));
        if (xmpRights != null) docProps.put("xmpRights", xmpRights);
        String xmpCreatorTool = str(meta.get(META_PDF_XMP_CREATOR_TOOL));
        if (xmpCreatorTool != null) {
            docProps.put("xmpCreatorTool", xmpCreatorTool);
            String toolOrgId = entityId("org:" + xmpCreatorTool.toLowerCase());
            if (!entityIndex.containsKey(toolOrgId)) {
                Map<String, String> toolProps = new LinkedHashMap<>();
                toolProps.put("name", xmpCreatorTool);
                toolProps.put(GraphConstants.PROP_SOURCE_FIELD, "xmpCreatorTool");
                addEntity(entityIndex, new ExtractedEntity(
                        toolOrgId, xmpCreatorTool, GraphConstants.ENTITY_ORGANIZATION,
                        null, "XMP creator tool: " + xmpCreatorTool, 0.75, toolProps));
                relations.add(new ExtractedRelation(
                        docEntityId, toolOrgId, GraphConstants.REL_PRODUCED_BY,
                        displayTitle + " produced by: " + xmpCreatorTool, 0.75, null));
            }
        }
        String pdfaConformance = str(meta.get(META_PDF_PDFA_CONFORMANCE));
        if (pdfaConformance != null) docProps.put("pdfaConformance", pdfaConformance);
        String xmpDescription = str(meta.get(META_PDF_XMP_DESCRIPTION));
        if (xmpDescription != null) docProps.put("xmpDescription", xmpDescription);
        // XMP publishers → ORGANIZATION entities
        @SuppressWarnings("unchecked")
        List<String> xmpPublishers = meta.get(GraphConstants.META_PDF_XMP_PUBLISHERS) instanceof List<?>
                ? (List<String>) meta.get(GraphConstants.META_PDF_XMP_PUBLISHERS) : null;
        if (xmpPublishers != null) {
            for (String publisher : xmpPublishers) {
                if (publisher == null || publisher.isBlank()) continue;
                String orgId = entityId("org:" + publisher.toLowerCase());
                if (!entityIndex.containsKey(orgId)) {
                    Map<String, String> orgProps = new LinkedHashMap<>();
                    orgProps.put("name", publisher);
                    orgProps.put(GraphConstants.PROP_SOURCE_FIELD, "xmpPublisher");
                    addEntity(entityIndex, new ExtractedEntity(
                            orgId, publisher, GraphConstants.ENTITY_ORGANIZATION,
                            null, "XMP publisher: " + publisher, 0.8, orgProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, orgId, GraphConstants.REL_PRODUCED_BY,
                            displayTitle + " published by: " + publisher, 0.8, null));
                }
            }
        }
        // XMP languages → document properties
        @SuppressWarnings("unchecked")
        List<String> xmpLanguages = meta.get(GraphConstants.META_PDF_XMP_LANGUAGES) instanceof List<?>
                ? (List<String>) meta.get(GraphConstants.META_PDF_XMP_LANGUAGES) : null;
        if (xmpLanguages != null && !xmpLanguages.isEmpty()) {
            docProps.put("xmpLanguages", String.join(", ", xmpLanguages));
        }
        // XMP contributors → additional PERSON entities
        @SuppressWarnings("unchecked")
        List<String> xmpContributors = meta.get(META_PDF_XMP_CONTRIBUTORS) instanceof List<?>
                ? (List<String>) meta.get(META_PDF_XMP_CONTRIBUTORS) : null;
        if (xmpContributors != null) {
            for (String contributor : xmpContributors) {
                if (contributor == null || contributor.isBlank()) continue;
                String personId = entityId("person:" + contributor.toLowerCase());
                if (!entityIndex.containsKey(personId)) {
                    Map<String, String> personProps = new LinkedHashMap<>();
                    personProps.put("name", contributor);
                    personProps.put(GraphConstants.PROP_SOURCE_FIELD, "xmpContributor");
                    addEntity(entityIndex, new ExtractedEntity(
                            personId, contributor, GraphConstants.ENTITY_PERSON,
                            null, "XMP contributor: " + contributor, 0.8, personProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, personId, GraphConstants.REL_CONTRIBUTED_BY,
                            displayTitle + " has contributor: " + contributor, 0.8, null));
                }
            }
        }

        // ── JavaScript detection ───────────────────────────────────────
        Object hasJs = meta.get(META_PDF_HAS_JAVASCRIPT);
        if (Boolean.TRUE.equals(hasJs)) {
            docProps.put("hasJavaScript", "true");
            String jsLocations = str(meta.get(META_PDF_JAVASCRIPT_LOCATIONS));
            if (jsLocations != null) docProps.put("javaScriptLocations", jsLocations);
        }

        // ── OCG layers → individual PDF_LAYER entities ──────────────────
        Object layerCount = meta.get(META_PDF_LAYER_COUNT);
        if (layerCount != null) {
            docProps.put("layerCount", String.valueOf(layerCount));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> layers = meta.get(META_PDF_LAYERS) instanceof List<?>
                    ? (List<Map<String, String>>) meta.get(META_PDF_LAYERS) : null;
            if (layers != null) {
                for (int li = 0; li < layers.size(); li++) {
                    Map<String, String> layer = layers.get(li);
                    String name = layer.get("name");
                    if (name == null || name.isBlank()) continue;
                    String layerId = entityId("pdf_layer:" + (source != null ? source : "") + ":" + name);
                    Map<String, String> layerProps = new LinkedHashMap<>();
                    layerProps.put("layerName", name);
                    layerProps.put("layerIndex", String.valueOf(li));
                    String visible = layer.get("visible");
                    if (visible != null) layerProps.put("visible", visible);
                    addEntity(entityIndex, new ExtractedEntity(
                            layerId, name, GraphConstants.ENTITY_PDF_LAYER,
                            null, "PDF layer: " + name, 0.85, layerProps));
                    relations.add(new ExtractedRelation(
                            docEntityId, layerId, GraphConstants.REL_HAS_LAYER,
                            displayTitle + " has layer: " + name,
                            0.85, null));
                }
            }
        }

        // ── PDF_SECTION from markdown headings in body text ─────────────
        // VLM OCR and some PDF-to-text pipelines produce markdown headings.
        // Parse these into PDF_SECTION entities when no bookmarks are present.
        // Uses SUBSECTION_OF hierarchy matching the bookmark section's approach.
        if (!EXTRACTION_TYPE_BOOKMARKS.equals(extractionType)) {
            String bodyText = doc.getText();
            if (bodyText != null && !bodyText.isEmpty()) {
                Matcher mdHeading = MARKDOWN_HEADING_PATTERN.matcher(bodyText);
                int headingIdx = 0;
                Map<Integer, String> mdDepthStack = new LinkedHashMap<>();
                while (mdHeading.find() && headingIdx < 200) {
                    String hashes = mdHeading.group(1);
                    String headingText = mdHeading.group(2).trim();
                    if (headingText.isEmpty()) continue;
                    int level = hashes.length();
                    String sectionId = entityId("section:" + docEntityId + ":" + headingText.toLowerCase());
                    // Skip if already created from bookmarks
                    if (entityIndex.containsKey(sectionId)) continue;
                    Map<String, String> sectionProps = new LinkedHashMap<>();
                    sectionProps.put("level", String.valueOf(level));
                    sectionProps.put("headingIndex", String.valueOf(headingIdx));
                    sectionProps.put(META_SOURCE, "markdown_heading");
                    ExtractedEntity sectionEntity = new ExtractedEntity(
                            sectionId, headingText, GraphConstants.ENTITY_PDF_SECTION,
                            null, "PDF section: " + headingText, 0.75, sectionProps
                    );
                    addEntity(entityIndex, sectionEntity);

                    // Build hierarchy: H1 → HAS_SECTION to document; deeper → SUBSECTION_OF parent
                    if (level <= 1) {
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                                displayTitle + " has section: " + headingText,
                                0.75, null
                        ));
                    } else {
                        String parentId = null;
                        for (int d = level - 1; d >= 1; d--) {
                            if (mdDepthStack.containsKey(d)) {
                                parentId = mdDepthStack.get(d);
                                break;
                            }
                        }
                        if (parentId != null) {
                            relations.add(new ExtractedRelation(
                                    sectionId, parentId, GraphConstants.REL_SUBSECTION_OF,
                                    headingText + " is subsection of parent",
                                    0.75, null
                            ));
                        } else {
                            relations.add(new ExtractedRelation(
                                    docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                                    displayTitle + " has section: " + headingText,
                                    0.75, null
                            ));
                        }
                    }

                    mdDepthStack.put(level, sectionId);
                    final int currentLevel = level;
                    mdDepthStack.entrySet().removeIf(e -> e.getKey() > currentLevel);
                    headingIdx++;
                }
            }
        }

        // ── PDF_SECTION from tika.headings metadata (DOCTAGS format) ──────
        // When VLM uses DOCTAGS output, OcrDocumentProcessor extracts <heading>
        // and <title> tags into tika.headings metadata. Process them the same way
        // as TikaGenericGraphExtractor does — creating PDF_SECTION entities with
        // hierarchical SUBSECTION_OF relations.
        @SuppressWarnings("unchecked")
        List<Map<String, String>> tikaHeadings = meta.get(META_TIKA_HEADINGS) instanceof List<?>
                ? (List<Map<String, String>>) meta.get(META_TIKA_HEADINGS) : null;
        if (tikaHeadings != null) {
            Map<Integer, String> thDepthStack = new LinkedHashMap<>();
            for (Map<String, String> heading : tikaHeadings) {
                String headingText = heading.get("text");
                String headingLevel = heading.get("level");
                if (headingText == null || headingText.isBlank()) continue;

                int level = 2;
                if (headingLevel != null) {
                    try { level = Integer.parseInt(headingLevel); } catch (NumberFormatException ignored) {}
                }

                String sectionId = entityId("section:" + docEntityId + ":" + headingText.toLowerCase());
                if (entityIndex.containsKey(sectionId)) continue;

                Map<String, String> sectionProps = new LinkedHashMap<>();
                sectionProps.put("level", String.valueOf(level));
                sectionProps.put(META_SOURCE, "doctags_heading");
                String idx2 = heading.get("index");
                if (idx2 != null) sectionProps.put("headingIndex", idx2);

                ExtractedEntity sectionEntity = new ExtractedEntity(
                        sectionId, headingText, GraphConstants.ENTITY_PDF_SECTION,
                        null, "PDF section: " + headingText, 0.8, sectionProps
                );
                addEntity(entityIndex, sectionEntity);

                if (level <= 1) {
                    relations.add(new ExtractedRelation(
                            docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                            displayTitle + " has section: " + headingText, 0.8, null
                    ));
                } else {
                    String parentId = null;
                    for (int d = level - 1; d >= 0; d--) {
                        if (thDepthStack.containsKey(d)) {
                            parentId = thDepthStack.get(d);
                            break;
                        }
                    }
                    if (parentId != null) {
                        relations.add(new ExtractedRelation(
                                sectionId, parentId, GraphConstants.REL_SUBSECTION_OF,
                                headingText + " is subsection of parent", 0.8, null
                        ));
                    } else {
                        relations.add(new ExtractedRelation(
                                docEntityId, sectionId, GraphConstants.REL_HAS_SECTION,
                                displayTitle + " has section: " + headingText, 0.8, null
                        ));
                    }
                }
                thDepthStack.put(level, sectionId);
                final int currentLevel = level;
                thDepthStack.entrySet().removeIf(e -> e.getKey() > currentLevel);
            }
        }

        // ── EMBEDDED_IMAGE / VLM_FIGURE from body text (Gap 1) ─────────────
        // VLM DocTags output may embed "[Image: objectId]" markers or markdown
        // "![alt](url)" patterns in the plain-text body.  Extract these as
        // EMBEDDED_IMAGE (for existing [Image:] markers) or VLM_FIGURE (for
        // markdown images) and link them to the document with HAS_IMAGE /
        // HAS_FIGURE relationships.
        {
            String bodyText = doc.getText();
            if (bodyText != null && !bodyText.isEmpty()) {
                // [Image: objectId] markers emitted by VLM post-processor
                Matcher imgMarker = VLM_IMAGE_MARKER_PATTERN.matcher(bodyText);
                int imgIdx = 0;
                while (imgMarker.find() && imgIdx < 200) {
                    String objectId = imgMarker.group(1).trim();
                    if (objectId.isEmpty()) continue;
                    String imgId = entityId("vlmfigure:" + docEntityId + ":" + objectId.toLowerCase());
                    if (!entityIndex.containsKey(imgId)) {
                        Map<String, String> imgProps = new LinkedHashMap<>();
                        imgProps.put("objectId", objectId);
                        imgProps.put(META_SOURCE, "vlm_image_marker");
                        // Add page number if this is a per-page document
                        Object imgPageNum = meta.get(GraphConstants.PROP_PAGE_NUMBER);
                        if (imgPageNum != null) imgProps.put(GraphConstants.PROP_PAGE_NUMBER, imgPageNum.toString());
                        ExtractedEntity imgEntity = new ExtractedEntity(
                                imgId, "Figure: " + objectId, GraphConstants.ENTITY_EMBEDDED_IMAGE,
                                null, "VLM-extracted figure with object ID: " + objectId, 0.8, imgProps
                        );
                        addEntity(entityIndex, imgEntity);
                        relations.add(new ExtractedRelation(
                                docEntityId, imgId, GraphConstants.REL_HAS_IMAGE,
                                displayTitle + " has image: " + objectId, 0.8, null
                        ));
                        // Link image to page entity if page number is known
                        if (imgPageNum != null) {
                            String imgPageId = entityId("page:" + docEntityId + ":p" + imgPageNum);
                            Map<String, String> pgProps = new LinkedHashMap<>();
                            pgProps.put(GraphConstants.PROP_PAGE_NUMBER, imgPageNum.toString());
                            addEntity(entityIndex, new ExtractedEntity(
                                    imgPageId, "Page " + imgPageNum, GraphConstants.ENTITY_PDF_PAGE,
                                    null, "PDF page " + imgPageNum, 0.85, pgProps));
                            relations.add(new ExtractedRelation(
                                    imgId, imgPageId, GraphConstants.REL_ON_PAGE,
                                    "Figure on page " + imgPageNum, 0.8, null));
                        }
                    }
                    imgIdx++;
                }

                // Markdown ![alt](url) images
                Matcher mdImg = MARKDOWN_IMAGE_PATTERN.matcher(bodyText);
                while (mdImg.find() && imgIdx < 400) {
                    String altText = mdImg.group(1).trim();
                    String imgUrl = mdImg.group(2).trim();
                    String label = altText.isEmpty() ? imgUrl : altText;
                    String mdImgId = entityId("vlmfigure:" + docEntityId + ":" + imgUrl.toLowerCase());
                    if (!entityIndex.containsKey(mdImgId)) {
                        Map<String, String> mdImgProps = new LinkedHashMap<>();
                        mdImgProps.put("url", imgUrl);
                        if (!altText.isEmpty()) mdImgProps.put("altText", altText);
                        mdImgProps.put(META_SOURCE, "markdown_image");
                        Object figPageNum = meta.get(GraphConstants.PROP_PAGE_NUMBER);
                        if (figPageNum != null) mdImgProps.put(GraphConstants.PROP_PAGE_NUMBER, figPageNum.toString());
                        ExtractedEntity mdImgEntity = new ExtractedEntity(
                                mdImgId, label.length() > 80 ? label.substring(0, 77) + "..." : label,
                                GraphConstants.ENTITY_VLM_FIGURE,
                                null, "VLM markdown image: " + label, 0.75, mdImgProps
                        );
                        addEntity(entityIndex, mdImgEntity);
                        relations.add(new ExtractedRelation(
                                docEntityId, mdImgId, GraphConstants.REL_HAS_FIGURE,
                                displayTitle + " has figure: " + label, 0.75, null
                        ));
                        // Link figure to page entity if page number is known
                        if (figPageNum != null) {
                            String figPageId = entityId("page:" + docEntityId + ":p" + figPageNum);
                            Map<String, String> fgProps = new LinkedHashMap<>();
                            fgProps.put(GraphConstants.PROP_PAGE_NUMBER, figPageNum.toString());
                            addEntity(entityIndex, new ExtractedEntity(
                                    figPageId, "Page " + figPageNum, GraphConstants.ENTITY_PDF_PAGE,
                                    null, "PDF page " + figPageNum, 0.85, fgProps));
                            relations.add(new ExtractedRelation(
                                    mdImgId, figPageId, GraphConstants.REL_ON_PAGE,
                                    "Figure on page " + figPageNum, 0.75, null));
                        }
                    }
                    imgIdx++;
                }
            }
        }

        // ── VLM DOCTAGS <figure> entities from metadata ──────────────────
        // OcrDocumentProcessor stores extracted <figure> tags as vlm.figures metadata.
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
                Object figPageNum = meta.get(GraphConstants.PROP_PAGE_NUMBER);
                if (figPageNum != null) imgProps.put(GraphConstants.PROP_PAGE_NUMBER, figPageNum.toString());
                String label = caption != null && !caption.isEmpty()
                        ? (caption.length() > 80 ? caption.substring(0, 77) + "..." : caption)
                        : "Figure: " + objectId;
                addEntity(entityIndex, new ExtractedEntity(
                        imgId, label, GraphConstants.ENTITY_EMBEDDED_IMAGE,
                        null, "VLM DOCTAGS figure: " + objectId, 0.8, imgProps));
                relations.add(new ExtractedRelation(
                        docEntityId, imgId, GraphConstants.REL_HAS_IMAGE,
                        displayTitle + " has figure: " + objectId, 0.8, null));
                if (figPageNum != null) {
                    String figPageId = entityId("page:" + docEntityId + ":p" + figPageNum);
                    Map<String, String> pgProps = new LinkedHashMap<>();
                    pgProps.put(GraphConstants.PROP_PAGE_NUMBER, figPageNum.toString());
                    addEntity(entityIndex, new ExtractedEntity(
                            figPageId, "Page " + figPageNum, GraphConstants.ENTITY_PDF_PAGE,
                            null, "PDF page " + figPageNum, 0.85, pgProps));
                    relations.add(new ExtractedRelation(
                            imgId, figPageId, GraphConstants.REL_ON_PAGE,
                            "Figure on page " + figPageNum, 0.8, null));
                }
            }
        }

        // ── TABLE entities from VLM markdown pipe-tables in body text ────
        // VLM output may contain pipe-delimited tables (| col1 | col2 |).
        // Detect contiguous runs of pipe-table rows and create TABLE entities.
        // When META_TABLE_GRAPH is not already set (i.e., OcrDocumentProcessor
        // didn't produce structured tables), also generate cell-level entities
        // via TableCellGraphBuilder so VLM-only tables get full graph coverage.
        {
            boolean hasExistingTableGraph = meta.get(META_TABLE_GRAPH) instanceof String tgj && !tgj.isBlank();
            String bodyText = doc.getText();
            if (bodyText != null && !bodyText.isEmpty()) {
                String[] lines = bodyText.split("\n");
                int tableBlockIdx = 0;
                int runStart = -1;
                List<String[]> currentRows = new ArrayList<>();
                List<String> headerRow = null;
                for (int li = 0; li <= lines.length && tableBlockIdx < 50; li++) {
                    String line = li < lines.length ? lines[li] : "";
                    boolean isTableRow = li < lines.length && MARKDOWN_TABLE_ROW_PATTERN.matcher(line).matches();
                    boolean isSeparator = isTableRow && MARKDOWN_TABLE_SEPARATOR.matcher(line.trim()).matches();

                    if (isTableRow && !isSeparator) {
                        if (runStart < 0) runStart = li;
                        String[] cells = line.split("\\|");
                        // Remove leading/trailing empty cells from split
                        List<String> trimmed = new ArrayList<>();
                        for (String c : cells) {
                            String val = c.trim();
                            if (!val.isEmpty() || trimmed.size() > 0) trimmed.add(val);
                        }
                        if (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).isEmpty()) {
                            trimmed.remove(trimmed.size() - 1);
                        }
                        if (headerRow == null) {
                            headerRow = trimmed.stream().map(String::trim).toList();
                        } else {
                            currentRows.add(trimmed.toArray(new String[0]));
                        }
                    } else if (isSeparator) {
                        // Skip separator rows — they don't contain data
                    } else {
                        // Non-table line — flush if we have accumulated rows
                        if (headerRow != null && !currentRows.isEmpty()) {
                            String tblName = "VLM-Table-" + (tableBlockIdx + 1);
                            String tableId = entityId("vlm_table:" + docEntityId + ":" + tableBlockIdx);
                            Map<String, String> tblProps = new LinkedHashMap<>();
                            tblProps.put(GraphConstants.PROP_ROW_COUNT, String.valueOf(currentRows.size()));
                            tblProps.put(GraphConstants.PROP_COLUMN_COUNT, String.valueOf(headerRow.size()));
                            tblProps.put(GraphConstants.PROP_HEADERS, String.join(", ", headerRow));
                            tblProps.put(META_SOURCE, "vlm_markdown_table");
                            addEntity(entityIndex, new ExtractedEntity(
                                    tableId, tblName, GraphConstants.ENTITY_TABLE,
                                    null, "VLM-extracted table: " + String.join(", ", headerRow),
                                    0.8, tblProps));
                            relations.add(new ExtractedRelation(
                                    docEntityId, tableId, GraphConstants.REL_HAS_TABLE,
                                    displayTitle + " has table: " + tblName, 0.8, null));

                            // Generate cell-level entities if no pre-built tableGraph exists
                            if (!hasExistingTableGraph) {
                                try {
                                    String ns = "pdf:" + (fileName != null ? fileName : displayTitle) + "/vlmtbl:" + tableBlockIdx;
                                    TableCellGraphBuilder builder = new TableCellGraphBuilder()
                                            .namespace(ns)
                                            .tableName(tblName)
                                            .headers(new ArrayList<>(headerRow));
                                    builder.addRow(new ArrayList<>(headerRow));
                                    for (String[] row : currentRows) {
                                        builder.addRow(Arrays.asList(row));
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
                                    log.warn("Failed to build cell-level graph for VLM table {}: {}", tblName, cellEx.getMessage());
                                }
                            }

                            tableBlockIdx++;
                        }
                        runStart = -1;
                        currentRows = new ArrayList<>();
                        headerRow = null;
                    }
                }
            }
        }

        // ── LIST entities from body text ────────────────────────────────
        // VLM and markdown-PDF pipelines produce bullet/numbered lists.
        // Identify contiguous list blocks (3+ consecutive list lines) and
        // create a LIST entity for each block with the first item as its name.
        {
            String bodyText = doc.getText();
            if (bodyText != null && !bodyText.isEmpty()) {
                // Collect all list item line positions (unordered + ordered)
                Set<Integer> listLineNumbers = new LinkedHashSet<>();
                Map<Integer, String> lineTextByIdx = new LinkedHashMap<>();
                String[] lines = bodyText.split("\n");
                for (int li = 0; li < lines.length; li++) {
                    String line = lines[li];
                    Matcher uo = MARKDOWN_UNORDERED_LIST_PATTERN.matcher(line);
                    Matcher ord = MARKDOWN_ORDERED_LIST_PATTERN.matcher(line);
                    if (uo.matches() || ord.matches()) {
                        listLineNumbers.add(li);
                        String itemText = uo.matches() ? uo.group(1) : ord.group(2);
                        lineTextByIdx.put(li, itemText.trim());
                    }
                }

                // Group contiguous runs of list lines; emit LIST entity for runs of >= 2 lines
                int listBlockIdx = 0;
                int runStart = -1;
                int prevLine = -2;
                List<String> currentItems = new ArrayList<>();

                for (int li : listLineNumbers) {
                    if (li == prevLine + 1) {
                        // Continuing a run
                        currentItems.add(lineTextByIdx.get(li));
                    } else {
                        // Flush previous run if large enough
                        if (currentItems.size() >= 2) {
                            emitListEntity(docEntityId, displayTitle, listBlockIdx, currentItems,
                                    entityIndex, relations);
                            listBlockIdx++;
                        }
                        currentItems = new ArrayList<>();
                        currentItems.add(lineTextByIdx.get(li));
                        runStart = li;
                    }
                    prevLine = li;
                    if (listBlockIdx >= 100) break; // Safety cap
                }
                // Flush final run
                if (currentItems.size() >= 2 && listBlockIdx < 100) {
                    emitListEntity(docEntityId, displayTitle, listBlockIdx, currentItems,
                            entityIndex, relations);
                }
            }

            // ── MATH_FORMULA entities from LaTeX notation ─────────────────
            {
                int formulaIdx = 0;
                // Display math ($$...$$ or \[...\]) — higher priority
                java.util.regex.Matcher dispMath = LATEX_DISPLAY_MATH_PATTERN.matcher(bodyText);
                while (dispMath.find() && formulaIdx < 50) {
                    String content = dispMath.group(1) != null ? dispMath.group(1) : dispMath.group(2);
                    if (content != null && !content.isBlank()) {
                        emitFormulaEntity(docEntityId, displayTitle, formulaIdx++, content.trim(),
                                "display", entityIndex, relations);
                    }
                }
                // Inline math ($...$) — skip if too many
                java.util.regex.Matcher inlineMath = LATEX_INLINE_MATH_PATTERN.matcher(bodyText);
                while (inlineMath.find() && formulaIdx < 100) {
                    String content = inlineMath.group(1);
                    if (content != null && !content.isBlank() && content.length() > 2) {
                        emitFormulaEntity(docEntityId, displayTitle, formulaIdx++, content.trim(),
                                "inline", entityIndex, relations);
                    }
                }
            }

            // ── CODE_BLOCK entities from fenced code blocks ──────────────
            {
                java.util.regex.Matcher codeMatcher = FENCED_CODE_BLOCK_PATTERN.matcher(bodyText);
                int codeIdx = 0;
                while (codeMatcher.find() && codeIdx < 50) {
                    String lang = codeMatcher.group(1);
                    String code = codeMatcher.group(2);
                    if (code != null && !code.isBlank()) {
                        emitCodeBlockEntity(docEntityId, displayTitle, codeIdx++,
                                code.trim(), lang, entityIndex, relations);
                    }
                }
            }
        }

        // ── URL extraction from body text (all extraction types) ────────
        // The annotations branch already extracts URLs from annotation text,
        // but fullDocument, single-page, and other extraction types also need
        // URL scanning from the actual document body text.
        if (!EXTRACTION_TYPE_ANNOTATIONS.equals(extractionType)) {
            String urlBodyText = doc.getText();
            if (urlBodyText != null && !urlBodyText.isEmpty()) {
                Set<String> seenBodyUrls = new HashSet<>();
                Matcher urlMatcher = URL_PATTERN.matcher(urlBodyText);
                int urlCount = 0;
                while (urlMatcher.find() && urlCount < 200) {
                    String url = urlMatcher.group();
                    if (seenBodyUrls.add(url.toLowerCase())) {
                        String urlId = entityId("url:" + url.toLowerCase());
                        if (!entityIndex.containsKey(urlId)) {
                            addEntity(entityIndex, new ExtractedEntity(
                                    urlId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                    null, "Hyperlink from PDF body text", 0.8,
                                    Map.of("url", url)));
                            relations.add(new ExtractedRelation(
                                    docEntityId, urlId, GraphConstants.REL_HYPERLINKS_TO,
                                    displayTitle + " links to " + url, 0.8, null));
                            urlCount++;
                        }
                    }
                }
            }
        }

        entities.addAll(entityIndex.values());

        ExtractionMetadata extractionMeta = new ExtractionMetadata(
                source, source, SOURCE_PDF_EXTRACTOR, null, null, null
        );

        return ExtractionResult.of(entities, relations, extractionMeta);
    }

    /**
     * Creates a LIST entity from a contiguous block of list items and links it to the document.
     */
    private void emitListEntity(String docEntityId, String displayTitle, int blockIdx,
                                List<String> items, Map<String, ExtractedEntity> entityIndex,
                                List<ExtractedRelation> relations) {
        String firstName = items.get(0);
        String listName = firstName.length() > 60 ? firstName.substring(0, 57) + "..." : firstName;
        // Build a stable ID from the first two items to avoid duplicate blocks
        String idKey = "list:" + docEntityId + ":" + blockIdx + ":"
                + items.get(0).toLowerCase().replaceAll("\\s+", "_");
        String listId = entityId(idKey);
        if (entityIndex.containsKey(listId)) return;

        Map<String, String> listProps = new LinkedHashMap<>();
        listProps.put("itemCount", String.valueOf(items.size()));
        listProps.put("firstItem", firstName);
        listProps.put(META_SOURCE, "markdown_list");
        ExtractedEntity listEntity = new ExtractedEntity(
                listId, listName, GraphConstants.ENTITY_LIST,
                null, "List with " + items.size() + " items starting: " + listName, 0.7, listProps
        );
        addEntity(entityIndex, listEntity);
        relations.add(new ExtractedRelation(
                docEntityId, listId, GraphConstants.REL_HAS_LIST,
                displayTitle + " has list starting: " + listName, 0.7, null
        ));
    }

    /**
     * Creates a MATH_FORMULA entity from LaTeX notation and links it to the document.
     */
    private void emitFormulaEntity(String docEntityId, String displayTitle, int idx,
                                    String formulaContent, String mathMode,
                                    Map<String, ExtractedEntity> entityIndex,
                                    List<ExtractedRelation> relations) {
        String preview = formulaContent.length() > 60
                ? formulaContent.substring(0, 57) + "..." : formulaContent;
        String idKey = "formula:" + docEntityId + ":" + idx + ":" + formulaContent.hashCode();
        String formulaId = entityId(idKey);
        if (entityIndex.containsKey(formulaId)) return;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("content", formulaContent);
        props.put("mathMode", mathMode);
        props.put("formulaIndex", String.valueOf(idx));
        ExtractedEntity entity = new ExtractedEntity(
                formulaId, preview, GraphConstants.ENTITY_MATH_FORMULA,
                null, "Formula: " + preview, 0.75, props
        );
        addEntity(entityIndex, entity);
        relations.add(new ExtractedRelation(
                docEntityId, formulaId, GraphConstants.REL_HAS_FORMULA,
                displayTitle + " has formula: " + preview, 0.75, null
        ));
    }

    /**
     * Creates a CODE_BLOCK entity from a fenced code block and links it to the document.
     */
    private void emitCodeBlockEntity(String docEntityId, String displayTitle, int idx,
                                      String code, String language,
                                      Map<String, ExtractedEntity> entityIndex,
                                      List<ExtractedRelation> relations) {
        String preview = code.length() > 60 ? code.substring(0, 57) + "..." : code;
        String idKey = "code:" + docEntityId + ":" + idx + ":" + code.hashCode();
        String codeId = entityId(idKey);
        if (entityIndex.containsKey(codeId)) return;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("codeContent", code);
        if (language != null && !language.isEmpty()) props.put("language", language);
        props.put("codeIndex", String.valueOf(idx));
        props.put("lineCount", String.valueOf(code.split("\n").length));
        ExtractedEntity entity = new ExtractedEntity(
                codeId, (language != null && !language.isEmpty() ? language + " " : "") + "code block",
                GraphConstants.ENTITY_CODE_BLOCK,
                null, "Code block: " + preview, 0.7, props
        );
        addEntity(entityIndex, entity);
        relations.add(new ExtractedRelation(
                docEntityId, codeId, GraphConstants.REL_HAS_CODE_BLOCK,
                displayTitle + " has code block", 0.7, null
        ));
    }

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
                new ExtractionMetadata(null, null, SOURCE_PDF_EXTRACTOR, null, null, null)
        );
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String str(Object obj) {
        if (obj == null) return null;
        String s = obj.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Splits author field on common separators: comma, semicolon, " and ", " & ".
     */
    private List<String> splitAuthors(String authorField) {
        List<String> authors = new ArrayList<>();
        String[] parts = authorField.split("[,;]|\\s+and\\s+|\\s*&\\s*");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                authors.add(trimmed);
            }
        }
        return authors;
    }

    /**
     * Splits keywords on comma, semicolon, or pipe.
     */
    private List<String> splitKeywords(String keywordField) {
        List<String> keywords = new ArrayList<>();
        String[] parts = keywordField.split("[,;|]");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.length() > 1) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    /**
     * Heuristic to detect software names in the creator field.
     * Returns true if the string looks like a software product rather than a person.
     */
    private boolean looksLikeSoftware(String name) {
        String lower = name.toLowerCase();
        return lower.contains("microsoft") || lower.contains("adobe")
                || lower.contains("libreoffice") || lower.contains("openoffice")
                || lower.contains("google") || lower.contains("writer")
                || lower.contains("acrobat") || lower.contains("word")
                || lower.contains("latex") || lower.contains("tex")
                || lower.contains("pdfkit") || lower.contains("wkhtmltopdf")
                || lower.contains("chrome") || lower.contains("firefox")
                || lower.contains("prince") || lower.contains("phantomjs")
                || lower.contains("reportlab") || lower.contains("itext")
                || lower.contains("fpdf") || lower.contains("tcpdf")
                || lower.contains("ghostscript") || lower.contains("pdftk")
                || lower.contains("quartz") || lower.contains("pdfbox")
                || lower.contains("aspose") || lower.contains("nitro")
                || lower.contains("foxit") || lower.contains("pdfcreator")
                || lower.contains("cups") || lower.contains("cairo")
                || lower.contains("poppler") || lower.contains("skia")
                || lower.contains("weasyprint") || lower.contains("dompdf");
    }

    /**
     * Extract the CN (Common Name) from a certificate subject string like
     * "CN=John Doe, O=Acme Corp, C=US". Falls back to the raw string.
     */
    private String parseCertCN(String certSubject) {
        String cn = parseCertField(certSubject, "CN");
        return cn != null ? cn : certSubject;
    }

    /**
     * Extract a named field from a certificate subject string (e.g. "O" from "CN=x, O=Acme, C=US").
     */
    private String parseCertField(String certSubject, String fieldName) {
        if (certSubject == null || !certSubject.contains("=")) return null;
        String prefix = fieldName + "=";
        int start = certSubject.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = certSubject.indexOf(',', start);
        return (end > start ? certSubject.substring(start, end) : certSubject.substring(start)).trim();
    }

    private void addEntity(Map<String, ExtractedEntity> index, ExtractedEntity entity) {
        ExtractedEntity existing = index.get(entity.id());
        if (existing == null) {
            index.put(entity.id(), entity);
        } else {
            // Merge: union aliases, keep longer description, higher confidence, merge properties
            Set<String> allAliases = new LinkedHashSet<>();
            if (existing.aliases() != null) allAliases.addAll(existing.aliases());
            if (entity.aliases() != null) allAliases.addAll(entity.aliases());

            String desc = existing.description();
            if (desc == null || (entity.description() != null && entity.description().length() > desc.length())) {
                desc = entity.description();
            }

            Map<String, String> mergedProps = new LinkedHashMap<>();
            if (existing.properties() != null) mergedProps.putAll(existing.properties());
            if (entity.properties() != null) mergedProps.putAll(entity.properties());

            double conf = Math.max(
                    existing.confidence() != null ? existing.confidence() : 0.0,
                    entity.confidence() != null ? entity.confidence() : 0.0
            );

            index.put(entity.id(), new ExtractedEntity(
                    entity.id(), existing.name(), existing.type(),
                    allAliases.isEmpty() ? null : new ArrayList<>(allAliases),
                    desc, conf, mergedProps
            ));
        }
    }
}
