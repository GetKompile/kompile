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

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Document loader for HTML content from web pages or local HTML files.
 * Uses Jsoup for HTML parsing and text extraction.
 *
 * Supports:
 * - URL source type: Fetches and parses HTML from web URLs
 * - FILE source type: Parses local HTML files or files with HTML content
 *
 * Features:
 * - Extracts clean text from HTML (removes scripts, styles, etc.)
 * - Preserves document structure with paragraph breaks
 * - Extracts metadata (title, description, author, etc.)
 * - Handles various HTML encodings
 * - Content-sniffing for files without .html extension
 */
@Component
public class WebHtmlLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(WebHtmlLoaderImpl.class);

    // Common HTML file extensions
    private static final Set<String> HTML_EXTENSIONS = Set.of(
        ".html", ".htm", ".xhtml", ".shtml"
    );

    // HTTP connection timeout in milliseconds
    private static final int CONNECTION_TIMEOUT_MS = 30000;

    // Maximum file size to read for HTML content (10MB)
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    @Override
    public String getName() {
        return "Web/HTML Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor == null) {
            return false;
        }

        DocumentSourceDescriptor.SourceType type = sourceDescriptor.getType();
        String pathOrUrl = sourceDescriptor.getPathOrUrl();

        if (pathOrUrl == null || pathOrUrl.isEmpty()) {
            return false;
        }

        // Support URL source type directly
        if (type == DocumentSourceDescriptor.SourceType.URL) {
            return true;
        }

        // For FILE source type, check if it's HTML
        if (type == DocumentSourceDescriptor.SourceType.FILE) {
            // Check by extension first
            String lowerPath = pathOrUrl.toLowerCase();
            for (String ext : HTML_EXTENSIONS) {
                if (lowerPath.endsWith(ext)) {
                    return true;
                }
            }

            // Check by content sniffing for files without extension
            // or with unknown extensions (like downloaded web pages)
            File file = new File(pathOrUrl);
            if (file.exists() && file.isFile() && file.length() > 0 && file.length() < MAX_FILE_SIZE) {
                return isHtmlContent(file);
            }
        }

        return false;
    }

    /**
     * Checks if a file contains HTML content by examining its first bytes.
     */
    private boolean isHtmlContent(File file) {
        try {
            // Read the first 2KB to check for HTML markers
            byte[] buffer = new byte[2048];
            try (FileInputStream fis = new FileInputStream(file)) {
                int bytesRead = fis.read(buffer);
                if (bytesRead <= 0) {
                    return false;
                }

                String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8).toLowerCase().trim();

                // Check for common HTML indicators
                return content.startsWith("<!doctype html") ||
                       content.startsWith("<html") ||
                       content.contains("<head") ||
                       content.contains("<body") ||
                       content.contains("<div") ||
                       content.contains("<p>") ||
                       content.contains("<meta") ||
                       content.contains("<title>");
            }
        } catch (Exception e) {
            logger.debug("Could not read file for HTML content check: {}", file.getName());
            return false;
        }
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        String pathOrUrl = sourceDescriptor.getPathOrUrl();
        DocumentSourceDescriptor.SourceType type = sourceDescriptor.getType();

        logger.info("Loading HTML content from: {} (type: {})", pathOrUrl, type);

        org.jsoup.nodes.Document jsoupDoc;
        String baseUri = "";

        if (type == DocumentSourceDescriptor.SourceType.URL) {
            // Fetch from URL
            jsoupDoc = fetchFromUrl(pathOrUrl);
            baseUri = pathOrUrl;
        } else {
            // Load from file
            File file = new File(pathOrUrl);
            if (!file.exists() || !file.isFile()) {
                throw new IllegalArgumentException("File does not exist or is not a regular file: " + pathOrUrl);
            }

            if (file.length() > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("File too large for HTML processing: " + file.length() + " bytes");
            }

            String html = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            jsoupDoc = Jsoup.parse(html);
            baseUri = file.toURI().toString();
        }

        // Structural mode: emit each <table> as its own table Document (plus a prose Document) so
        // HTML tables become first-class TABLE graph nodes that render in the index browser. Enabled
        // explicitly via the "structuralMode" descriptor flag, or auto-detected when tables exist.
        if (resolveStructuralMode(sourceDescriptor, jsoupDoc)) {
            List<Document> structural = extractStructural(jsoupDoc, sourceDescriptor, baseUri);
            if (!structural.isEmpty()) {
                logger.info("Loaded HTML in structural mode: {} document(s) from {}", structural.size(), pathOrUrl);
                return structural;
            }
        }

        // Flat mode: single document with the whole page text.
        // Extract metadata BEFORE extractTextContent because that method mutates the document
        // by removing script/style/noscript elements (which would strip JSON-LD, etc.).
        Map<String, Object> metadata = extractMetadata(jsoupDoc, sourceDescriptor, baseUri);
        // Run email detection in flat mode too (structural mode runs it in extractStructural).
        new HtmlEmailMetadataExtractor().detectAndExtract(jsoupDoc, metadata);
        String textContent = extractTextContent(jsoupDoc);
        Document springDoc = new Document(textContent, metadata);

        logger.info("Loaded HTML document: {} characters, title: '{}'",
            textContent.length(), metadata.get("title"));

        return List.of(springDoc);
    }

    /**
     * Decide whether to use structural (table-aware) extraction. Honors an explicit
     * {@code structuralMode} descriptor flag (Boolean or String); otherwise auto-detects by the
     * presence of at least one &lt;table&gt; element.
     */
    private boolean resolveStructuralMode(DocumentSourceDescriptor desc, org.jsoup.nodes.Document jsoupDoc) {
        Object flag = desc.getMetadata() != null ? desc.getMetadata().get("structuralMode") : null;
        if (flag instanceof Boolean b) {
            return b;
        }
        if (flag instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return !jsoupDoc.select("table").isEmpty();
    }

    /**
     * Extract each top-level &lt;table&gt; as a content_type=table Document (with a TableCellGraphBuilder
     * cell graph + markdown), plus one prose Document for the remaining text. Returns an empty list when
     * no tables are found so the caller can fall back to flat extraction.
     */
    private List<Document> extractStructural(org.jsoup.nodes.Document jsoupDoc,
                                             DocumentSourceDescriptor desc, String baseUri) {
        org.jsoup.nodes.Document working = jsoupDoc.clone();
        working.select("script, style, noscript, iframe, svg, canvas").remove();
        Map<String, Object> baseMeta = extractMetadata(working, desc, baseUri);
        // Email detection: sets content_type_hint=email + email.* fields when the page is a rendered
        // email, so every emitted document (tables and prose) inherits the email context.
        new HtmlEmailMetadataExtractor().detectAndExtract(working, baseMeta);

        // Prose Document comes first (docs[0]) so callers can use docs.get(0) as the main document.
        // Table sub-documents follow (docs[1..n]).
        List<Document> docs = new ArrayList<>();

        // Build prose doc (remove tables first, then extract text)
        org.jsoup.nodes.Document proseClone = working.clone();
        proseClone.select("table").remove();
        String prose = extractTextContent(proseClone);
        if (prose != null && !prose.isBlank()) {
            Map<String, Object> proseMeta = new LinkedHashMap<>(baseMeta);
            proseMeta.put("content_type", "text");
            docs.add(new Document(prose, proseMeta));
        }

        // Table sub-documents
        int tableIndex = 0;
        for (Element table : working.select("table")) {
            // Skip nested tables — they are handled via the outer table's direct rows.
            if (table.parents().stream().anyMatch(p -> "table".equals(p.tagName()))) {
                continue;
            }
            Document tableDoc = buildHtmlTableDocument(table, baseMeta, tableIndex, baseUri);
            if (tableDoc != null) {
                docs.add(tableDoc);
                tableIndex++;
            }
        }

        return docs;
    }

    /** Build a content_type=table Document from a single HTML table element. */
    private Document buildHtmlTableDocument(Element table, Map<String, Object> baseMeta,
                                            int tableIndex, String baseUri) {
        // Direct rows only: a <tr> whose nearest table ancestor is THIS table (no nested-table bleed).
        List<List<String>> cellRows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            Element ancestorTable = tr.parents().stream()
                    .filter(p -> "table".equals(p.tagName())).findFirst().orElse(null);
            if (ancestorTable != table) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                if ("td".equals(cell.tagName()) || "th".equals(cell.tagName())) {
                    cells.add(cell.text().trim());
                }
            }
            if (!cells.isEmpty()) {
                cellRows.add(cells);
            }
        }
        if (cellRows.isEmpty()) {
            return null;
        }

        List<String> headers = cellRows.get(0);
        int rowCount = cellRows.size() - 1; // exclude header row
        int colCount = cellRows.stream().mapToInt(List::size).max().orElse(headers.size());

        // Skip trivial tables: must have at least 1 data row and at least 2 columns.
        // Single-row (header-only) or single-column tables are structural/layout artifacts,
        // not semantic data tables worth indexing separately.
        if (rowCount < 1 || colCount < 2) {
            return null;
        }

        String markdownContent = TableCellGraphBuilder.toMarkdown(cellRows, true);

        Map<String, Object> meta = new LinkedHashMap<>(baseMeta);
        meta.put("content_type", "table");
        meta.put("table_extraction_method", "html-jsoup");
        meta.put("table_index", tableIndex);
        meta.put("table_row_count", rowCount);
        meta.put("table_column_count", colCount);
        meta.put("table_headers", String.join(",", headers));
        meta.put("full_table_content", markdownContent);

        Graph graph = new TableCellGraphBuilder()
                .namespace("html:" + baseUri + "#" + tableIndex)
                .tableName("Table " + (tableIndex + 1))
                .rows(cellRows)
                .firstRowIsHeader(true)
                .build();
        if (!graph.getEntities().isEmpty()) {
            meta.put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(graph));
        }

        // Use markdown content as document text so extractors and search can see cell data directly.
        return new Document(markdownContent, meta);
    }

    /**
     * Fetches and parses HTML from a URL.
     */
    private org.jsoup.nodes.Document fetchFromUrl(String url) throws IOException {
        logger.debug("Fetching HTML from URL: {}", url);

        return Jsoup.connect(url)
            .timeout(CONNECTION_TIMEOUT_MS)
            .userAgent("Mozilla/5.0 (compatible; KompileBot/1.0; +https://kompile.ai)")
            .followRedirects(true)
            .maxBodySize(0) // No limit
            .get();
    }

    /**
     * Extracts clean text content from an HTML document.
     * Removes scripts, styles, and other non-content elements.
     * Preserves document structure with appropriate line breaks.
     */
    private String extractTextContent(org.jsoup.nodes.Document doc) {
        // Remove script and style elements
        doc.select("script, style, noscript, iframe, svg, canvas").remove();

        // Also remove hidden elements
        doc.select("[hidden], [style*='display: none'], [style*='display:none']").remove();

        // Get the body content (or whole document if no body)
        Element body = doc.body();
        if (body == null) {
            body = doc;
        }

        // Configure output settings for clean text
        doc.outputSettings()
            .prettyPrint(true)
            .outline(false);

        // Get text with whitespace normalization
        String text = body.wholeText();

        // Clean up the text
        text = cleanText(text);

        return text;
    }

    /**
     * Cleans extracted text by normalizing whitespace and removing excessive blank lines.
     */
    private String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Replace multiple spaces with single space
        text = text.replaceAll("[ \\t]+", " ");

        // Normalize line endings
        text = text.replaceAll("\\r\\n", "\n");
        text = text.replaceAll("\\r", "\n");

        // Replace multiple newlines with double newline (paragraph break)
        text = text.replaceAll("\\n{3,}", "\n\n");

        // Trim each line
        String[] lines = text.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                sb.append(trimmed).append("\n");
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    /**
     * Extracts metadata from an HTML document, including rich html.* keys consumed by
     * HtmlWebGraphExtractor (headings, hyperlinks, JSON-LD, forms, embedded media, images).
     */
    private Map<String, Object> extractMetadata(org.jsoup.nodes.Document doc,
                                                 DocumentSourceDescriptor sourceDescriptor,
                                                 String baseUri) {
        Map<String, Object> metadata = new HashMap<>();

        // Basic source info
        metadata.put("source", sourceDescriptor.getPathOrUrl());
        metadata.put("loader", getName());
        metadata.put("sourceType", sourceDescriptor.getType().name());

        if (sourceDescriptor.getOriginalFileName() != null) {
            metadata.put("fileName", sourceDescriptor.getOriginalFileName());
        }

        // Document title
        String title = doc.title();
        if (title != null && !title.isEmpty()) {
            metadata.put("title", title);
        }

        // Standard meta tags
        extractMetaTag(doc, "description", metadata, "description");
        extractMetaTag(doc, "author", metadata, "author");
        extractMetaTag(doc, "keywords", metadata, "keywords");
        extractMetaTag(doc, "og:title", metadata, "ogTitle");
        extractMetaTag(doc, "og:description", metadata, "ogDescription");
        extractMetaTag(doc, "og:site_name", metadata, "siteName");
        extractMetaTag(doc, "og:type", metadata, "pageType");
        extractMetaTag(doc, "og:url", metadata, "ogUrl");
        extractMetaTag(doc, "og:image", metadata, "ogImage");
        extractMetaTag(doc, "article:published_time", metadata, "publishedTime");
        extractMetaTag(doc, "article:modified_time", metadata, "modifiedTime");
        extractMetaTag(doc, "article:author", metadata, "articleAuthor");
        extractMetaTag(doc, "article:section", metadata, "article.section");
        extractMetaTag(doc, "article:tag", metadata, "article.tag");

        // Twitter card metadata
        extractMetaTag(doc, "twitter:card", metadata, "twitterCard");
        extractMetaTag(doc, "twitter:title", metadata, "twitterTitle");
        extractMetaTag(doc, "twitter:description", metadata, "twitterDescription");
        extractMetaTag(doc, "twitter:image", metadata, "twitterImage");
        extractMetaTag(doc, "twitter:site", metadata, "twitterSite");

        // Dublin Core metadata
        extractMetaTag(doc, "DC.creator", metadata, "dc.creator");
        extractMetaTag(doc, "DC.publisher", metadata, "dc.publisher");
        extractMetaTag(doc, "DC.date", metadata, "dc.date");
        extractMetaTag(doc, "DC.description", metadata, "dc.description");
        extractMetaTag(doc, "DC.rights", metadata, "dc.rights");

        // Canonical URL
        Element canonical = doc.selectFirst("link[rel=canonical]");
        if (canonical != null) {
            String href = canonical.attr("href");
            if (!href.isEmpty()) {
                metadata.put("canonicalUrl", href);
            }
        }

        // Language
        String lang = doc.select("html").attr("lang");
        if (!lang.isEmpty()) {
            metadata.put("language", lang);
        }

        // Base URI
        if (!baseUri.isEmpty()) {
            metadata.put("baseUri", baseUri);
        }

        // ── html.alternateLinks ─────────────────────────────────────────────
        Elements alternateLinks = doc.select("link[rel=alternate]");
        if (!alternateLinks.isEmpty()) {
            List<Map<String, String>> altList = new ArrayList<>();
            for (Element link : alternateLinks) {
                String href = link.attr("href");
                if (href.isEmpty()) continue;
                Map<String, String> alt = new LinkedHashMap<>();
                alt.put("href", href);
                String hreflang = link.attr("hreflang");
                if (!hreflang.isEmpty()) alt.put("hreflang", hreflang);
                String type = link.attr("type");
                if (!type.isEmpty()) alt.put("type", type);
                String altTitle = link.attr("title");
                if (!altTitle.isEmpty()) alt.put("title", altTitle);
                altList.add(alt);
            }
            if (!altList.isEmpty()) {
                metadata.put("html.alternateLinks", altList);
            }
        }

        // ── html.headings ───────────────────────────────────────────────────
        Elements headingElements = doc.select("h1, h2, h3, h4, h5, h6");
        if (!headingElements.isEmpty()) {
            List<Map<String, String>> headings = new ArrayList<>();
            for (Element heading : headingElements) {
                String text = heading.text().trim();
                if (text.isEmpty()) continue;
                String level = heading.tagName().substring(1); // "1" through "6"
                Map<String, String> h = new LinkedHashMap<>();
                h.put("level", level);
                h.put("text", text);
                headings.add(h);
            }
            if (!headings.isEmpty()) {
                metadata.put("html.headings", headings);
            }
        }

        // ── html.hyperlinks ─────────────────────────────────────────────────
        Elements anchors = doc.select("a[href]");
        if (!anchors.isEmpty()) {
            List<Map<String, String>> hyperlinks = new ArrayList<>();
            List<Map<String, String>> mailtoEmails = new ArrayList<>();
            for (Element anchor : anchors) {
                String href = anchor.attr("href");
                if (href.isEmpty()) continue;
                String text = anchor.text().trim();
                String anchorTitle = anchor.attr("title");
                String rel = anchor.attr("rel");
                if (href.startsWith("mailto:")) {
                    String email = href.substring("mailto:".length()).trim();
                    if (!email.isEmpty()) {
                        Map<String, String> mailEntry = new LinkedHashMap<>();
                        mailEntry.put("email", email);
                        if (!text.isEmpty()) mailEntry.put("text", text);
                        mailtoEmails.add(mailEntry);
                    }
                } else {
                    Map<String, String> link = new LinkedHashMap<>();
                    link.put("url", href);
                    if (!text.isEmpty()) link.put("text", text);
                    if (!anchorTitle.isEmpty()) link.put("title", anchorTitle);
                    if (!rel.isEmpty()) link.put("rel", rel);
                    hyperlinks.add(link);
                }
            }
            if (!hyperlinks.isEmpty()) {
                metadata.put("html.hyperlinks", hyperlinks);
            }
            if (!mailtoEmails.isEmpty()) {
                metadata.put("html.mailtoEmails", mailtoEmails);
            }
        }

        // ── html.images ─────────────────────────────────────────────────────
        Elements imgElements = doc.select("img[src]");
        if (!imgElements.isEmpty()) {
            List<Map<String, String>> images = new ArrayList<>();
            for (Element img : imgElements) {
                String src = img.attr("src");
                if (src.isEmpty()) continue;
                Map<String, String> imgMeta = new LinkedHashMap<>();
                imgMeta.put("src", src);
                String alt = img.attr("alt");
                if (!alt.isEmpty()) imgMeta.put("alt", alt);
                String imgTitle = img.attr("title");
                if (!imgTitle.isEmpty()) imgMeta.put("title", imgTitle);
                images.add(imgMeta);
            }
            if (!images.isEmpty()) {
                metadata.put("html.images", images);
                metadata.put("imageCount", images.size());
            }
        }

        // ── html.jsonld ─────────────────────────────────────────────────────
        Elements jsonLdScripts = doc.select("script[type=application/ld+json]");
        if (!jsonLdScripts.isEmpty()) {
            List<String> jsonLdBlocks = new ArrayList<>();
            for (Element script : jsonLdScripts) {
                String jsonContent = script.html().trim();
                if (!jsonContent.isEmpty()) {
                    jsonLdBlocks.add(jsonContent);
                }
            }
            if (!jsonLdBlocks.isEmpty()) {
                metadata.put("html.jsonld", jsonLdBlocks);
            }
        }

        // ── html.forms ──────────────────────────────────────────────────────
        Elements formElements = doc.select("form");
        if (!formElements.isEmpty()) {
            List<Map<String, Object>> forms = new ArrayList<>();
            for (Element form : formElements) {
                Map<String, Object> formMeta = new LinkedHashMap<>();
                String action = form.attr("action");
                String method = form.attr("method");
                String formName = form.attr("name");
                if (!action.isEmpty()) formMeta.put("action", action);
                if (!method.isEmpty()) formMeta.put("method", method);
                if (!formName.isEmpty()) formMeta.put("name", formName);

                // Extract fields (input, select, textarea)
                Elements fieldElements = form.select("input[name], select[name], textarea[name]");
                List<Map<String, String>> fields = new ArrayList<>();
                for (Element field : fieldElements) {
                    String fieldName = field.attr("name");
                    if (fieldName.isEmpty()) continue;
                    Map<String, String> fieldMeta = new LinkedHashMap<>();
                    fieldMeta.put("name", fieldName);
                    fieldMeta.put("tag", field.tagName());
                    String fieldType = field.attr("type");
                    if (!fieldType.isEmpty()) fieldMeta.put("type", fieldType);
                    String placeholder = field.attr("placeholder");
                    if (!placeholder.isEmpty()) fieldMeta.put("placeholder", placeholder);
                    String required = field.attr("required");
                    if (!required.isEmpty()) fieldMeta.put("required", "true");
                    fields.add(fieldMeta);
                }
                if (!fields.isEmpty()) {
                    formMeta.put("fields", fields);
                    formMeta.put("fieldCount", fields.size());
                }
                forms.add(formMeta);
            }
            if (!forms.isEmpty()) {
                metadata.put("html.forms", forms);
            }
        }

        // ── html.embeddedMedia ──────────────────────────────────────────────
        List<Map<String, String>> embeddedMedia = new ArrayList<>();

        // Iframes
        for (Element iframe : doc.select("iframe[src]")) {
            String src = iframe.attr("src");
            if (src.isEmpty()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("type", "iframe");
            m.put("src", src);
            String iframeTitle = iframe.attr("title");
            if (!iframeTitle.isEmpty()) m.put("title", iframeTitle);
            String width = iframe.attr("width");
            if (!width.isEmpty()) m.put("width", width);
            String height = iframe.attr("height");
            if (!height.isEmpty()) m.put("height", height);
            embeddedMedia.add(m);
        }

        // Video elements
        for (Element video : doc.select("video")) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("type", "video");
            String src = video.attr("src");
            if (src.isEmpty()) {
                // Check <source> child
                Element source = video.selectFirst("source[src]");
                if (source != null) src = source.attr("src");
            }
            if (!src.isEmpty()) m.put("src", src);
            String videoTitle = video.attr("title");
            if (!videoTitle.isEmpty()) m.put("title", videoTitle);
            embeddedMedia.add(m);
        }

        // Audio elements
        for (Element audio : doc.select("audio")) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("type", "audio");
            String src = audio.attr("src");
            if (src.isEmpty()) {
                Element source = audio.selectFirst("source[src]");
                if (source != null) src = source.attr("src");
            }
            if (!src.isEmpty()) m.put("src", src);
            embeddedMedia.add(m);
        }

        if (!embeddedMedia.isEmpty()) {
            metadata.put("html.embeddedMedia", embeddedMedia);
        }

        // ── html.codeBlocks ─────────────────────────────────────────────────
        // <pre><code> (and bare <pre>) → code-block metadata consumed by HtmlWebGraphExtractor to
        // emit CODE_BLOCK entities. No producer emitted this key before, so that branch was dead.
        Elements codeElements = doc.select("pre code, pre");
        if (!codeElements.isEmpty()) {
            List<Map<String, Object>> codeBlocks = new ArrayList<>();
            for (Element codeEl : codeElements) {
                // "pre code" already captured the <code>; skip the wrapping <pre> to avoid duplicates.
                if ("pre".equals(codeEl.tagName()) && !codeEl.select("code").isEmpty()) {
                    continue;
                }
                String code = codeEl.wholeText();
                if (code == null || code.isBlank()) {
                    continue;
                }
                Map<String, Object> cb = new LinkedHashMap<>();
                cb.put("code", code);
                String language = detectCodeLanguage(codeEl);
                if (language != null) {
                    cb.put("language", language);
                }
                cb.put("lineCount", code.split("\n", -1).length);
                codeBlocks.add(cb);
                if (codeBlocks.size() >= 50) {
                    break;
                }
            }
            if (!codeBlocks.isEmpty()) {
                metadata.put("html.codeBlocks", codeBlocks);
            }
        }

        // Add any metadata from the source descriptor
        if (sourceDescriptor.getMetadata() != null) {
            for (Map.Entry<String, Object> entry : sourceDescriptor.getMetadata().entrySet()) {
                if (!metadata.containsKey(entry.getKey())) {
                    metadata.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return metadata;
    }

    /**
     * Extracts a meta tag value and adds it to metadata if present.
     */
    private void extractMetaTag(org.jsoup.nodes.Document doc, String metaName,
                                Map<String, Object> metadata, String key) {
        // Try name attribute
        Element meta = doc.selectFirst("meta[name=" + metaName + "]");
        if (meta != null) {
            String content = meta.attr("content");
            if (!content.isEmpty()) {
                metadata.put(key, content);
                return;
            }
        }

        // Try property attribute (for Open Graph tags)
        meta = doc.selectFirst("meta[property=" + metaName + "]");
        if (meta != null) {
            String content = meta.attr("content");
            if (!content.isEmpty()) {
                metadata.put(key, content);
            }
        }
    }

    /**
     * Best-effort code language from a highlight class such as {@code language-java}, {@code lang-py},
     * or GitHub's {@code highlight-source-*}, checked on the element and its parent {@code <pre>}.
     */
    private String detectCodeLanguage(Element codeEl) {
        String cls = codeEl.className();
        if ((cls == null || cls.isBlank()) && codeEl.parent() != null) {
            cls = codeEl.parent().className();
        }
        if (cls == null) {
            return null;
        }
        for (String token : cls.trim().split("\\s+")) {
            if (token.startsWith("language-") && token.length() > 9) {
                return token.substring(9);
            }
            if (token.startsWith("lang-") && token.length() > 5) {
                return token.substring(5);
            }
            if (token.startsWith("highlight-source-") && token.length() > 17) {
                return token.substring(17);
            }
        }
        return null;
    }
}
