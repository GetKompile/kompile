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

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
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

        // Extract text content
        String textContent = extractTextContent(jsoupDoc);

        // Extract metadata
        Map<String, Object> metadata = extractMetadata(jsoupDoc, sourceDescriptor, baseUri);

        // Create Spring AI Document
        Document springDoc = new Document(textContent, metadata);

        logger.info("Loaded HTML document: {} characters, title: '{}'",
            textContent.length(), metadata.get("title"));

        return List.of(springDoc);
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
     * Extracts metadata from an HTML document.
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

        // Meta tags
        extractMetaTag(doc, "description", metadata, "description");
        extractMetaTag(doc, "author", metadata, "author");
        extractMetaTag(doc, "keywords", metadata, "keywords");
        extractMetaTag(doc, "og:title", metadata, "ogTitle");
        extractMetaTag(doc, "og:description", metadata, "ogDescription");
        extractMetaTag(doc, "og:site_name", metadata, "siteName");
        extractMetaTag(doc, "og:type", metadata, "pageType");
        extractMetaTag(doc, "article:published_time", metadata, "publishedTime");
        extractMetaTag(doc, "article:modified_time", metadata, "modifiedTime");
        extractMetaTag(doc, "article:author", metadata, "articleAuthor");

        // Twitter card metadata
        extractMetaTag(doc, "twitter:title", metadata, "twitterTitle");
        extractMetaTag(doc, "twitter:description", metadata, "twitterDescription");

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
}
