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

package ai.kompile.app.services;

import ai.kompile.core.source.SourceDocumentStorageService;
import ai.kompile.core.source.SourceMetadataConstants;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts browsed/collected sources into Markdown files that can be indexed by
 * the existing Markdown loader and chunker pipeline.
 */
@Service
public class SourceMarkdownConversionService {

    private static final Logger log = LoggerFactory.getLogger(SourceMarkdownConversionService.class);

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "xml", "csv", "tsv", "log", "conf", "cfg", "ini",
            "properties", "env", "java", "py", "js", "ts", "css", "sql", "yaml", "yml", "sh", "bash"
    );

    private final SourceDocumentStorageService storageService;
    private final Path uploadsPath;

    @Autowired
    public SourceMarkdownConversionService(
            @Autowired(required = false) SourceDocumentStorageService storageService,
            @Autowired(required = false) AppDocumentSourceProperties sourceProperties) {
        this.storageService = storageService != null ? storageService : new SourceDocumentStorageService();
        if (sourceProperties == null || sourceProperties.getUploadsPath() == null
                || sourceProperties.getUploadsPath().isBlank()) {
            this.uploadsPath = Paths.get("./uploads").toAbsolutePath().normalize();
        } else {
            this.uploadsPath = Paths.get(sourceProperties.getUploadsPath()).toAbsolutePath().normalize();
        }
    }

    public ConversionResult convertSource(String fileName, String checksum) {
        SourceReference source = resolveSource(fileName, checksum);
        return convertPath(source.path(), source.fileName(), source.sourceUrl(), source.checksum());
    }

    public ConversionResult convertPath(Path sourcePath, String sourceFileName, String sourceUrl) {
        return convertPath(sourcePath, sourceFileName, sourceUrl, null);
    }

    public ConversionResult convertPath(Path sourcePath, String sourceFileName, String sourceUrl, String sourceChecksum) {
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }

        try {
            Files.createDirectories(uploadsPath);
            String extension = extension(sourceFileName);
            if (!isDirectlyConvertible(extension)) {
                throw new IllegalArgumentException("Source type cannot be converted directly to markdown: " + extension);
            }
            String raw = Files.readString(sourcePath, StandardCharsets.UTF_8);
            String markdown = toMarkdown(sourceFileName, extension, raw, sourceUrl);
            String outputFileName = uniqueMarkdownFileName(sourceFileName);
            Path outputPath = uploadsPath.resolve(outputFileName).normalize();
            if (!outputPath.startsWith(uploadsPath)) {
                throw new IllegalArgumentException("Invalid markdown output path: " + outputFileName);
            }

            Files.writeString(outputPath, markdown, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            String markdownChecksum = null;
            if (storageService != null && storageService.isEnabled()) {
                Optional<SourceDocumentStorageService.StorageResult> stored = storageService.storeDocument(outputPath);
                if (stored.isPresent()) {
                    markdownChecksum = stored.get().checksum();
                    storageService.storeMetadata(markdownChecksum, metadata(sourcePath, sourceFileName, sourceUrl, sourceChecksum));
                }
            }

            long sizeBytes = Files.size(outputPath);
            log.info("Converted source {} to markdown {}", sourcePath, outputPath);
            return new ConversionResult(
                    outputFileName,
                    outputPath.toString(),
                    markdownChecksum,
                    sourceFileName,
                    sourcePath.toString(),
                    sourceChecksum,
                    sourceUrl,
                    sizeBytes,
                    Instant.now().toString()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to convert source to markdown: " + e.getMessage(), e);
        }
    }

    public Path getUploadsPath() {
        return uploadsPath;
    }

    private SourceReference resolveSource(String fileName, String checksum) {
        String sourceUrl = null;
        if (storageService != null && checksum != null && !checksum.isBlank()) {
            Optional<Path> storedPath = storageService.getStoredPath(checksum);
            if (storedPath.isPresent()) {
                sourceUrl = storageService.getSourceUrl(checksum).orElse(null);
                String storedFileName = storedPath.get().getFileName().toString();
                return new SourceReference(storedPath.get(), originalFileName(checksum, storedFileName), checksum, sourceUrl);
            }
        }

        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName or checksum is required");
        }

        String sanitized = Paths.get(fileName).getFileName().toString();
        Path uploadPath = uploadsPath.resolve(sanitized).normalize();
        if (uploadPath.startsWith(uploadsPath) && Files.isRegularFile(uploadPath)) {
            return new SourceReference(uploadPath, sanitized, null, null);
        }

        Path stored = storageService == null ? null : findInStoredDocuments(sanitized);
        if (stored != null) {
            String storedChecksum = checksumFromStoredFile(stored.getFileName().toString());
            sourceUrl = storageService.getSourceUrl(storedChecksum).orElse(null);
            return new SourceReference(stored, originalFileName(storedChecksum, sanitized), storedChecksum, sourceUrl);
        }

        throw new IllegalArgumentException("Source file not found: " + fileName);
    }

    private String originalFileName(String checksum, String fallback) {
        if (storageService == null || checksum == null || checksum.isBlank()) {
            return fallback;
        }
        try {
            Optional<Map<String, Object>> metadata = storageService.getMetadata(checksum);
            if (metadata.isPresent()) {
                Object sourceFileName = metadata.get().get(SourceMetadataConstants.SOURCE_FILENAME);
                if (sourceFileName != null && !sourceFileName.toString().isBlank()) {
                    return sourceFileName.toString();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve original filename for checksum {}: {}", checksum, e.getMessage());
        }
        return fallback;
    }

    private Path findInStoredDocuments(String fileName) {
        Path root = storageService.getStorageRoot();
        if (!Files.exists(root)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Failed to search stored documents for {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    private boolean isDirectlyConvertible(String extension) {
        return "html".equals(extension) || "htm".equals(extension) || TEXT_EXTENSIONS.contains(extension);
    }

    private String toMarkdown(String sourceFileName, String extension, String raw, String sourceUrl) {
        String body;
        String title = stripExtension(sourceFileName);
        if ("html".equals(extension) || "htm".equals(extension)) {
            org.jsoup.nodes.Document html = Jsoup.parse(raw, sourceUrl == null ? "" : sourceUrl);
            html.select("script,style,noscript").remove();
            if (html.title() != null && !html.title().isBlank()) {
                title = html.title().trim();
            }
            body = htmlBodyToMarkdown(html.body());
        } else if (TEXT_EXTENSIONS.contains(extension)) {
            body = raw;
        } else {
            throw new IllegalArgumentException("Source type cannot be converted directly to markdown: " + extension);
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n");
        markdown.append("source_file: ").append(escapeYaml(sourceFileName)).append("\n");
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            markdown.append("source_url: ").append(escapeYaml(sourceUrl)).append("\n");
        }
        markdown.append("converted_at: ").append(Instant.now()).append("\n");
        markdown.append("conversion: source-to-markdown\n");
        markdown.append("---\n\n");
        markdown.append("# ").append(title).append("\n\n");
        markdown.append(body.trim()).append("\n");
        return markdown.toString();
    }

    private String htmlBodyToMarkdown(Element body) {
        if (body == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Element child : body.children()) {
            appendElement(child, out);
        }
        return compactBlankLines(out.toString());
    }

    private void appendElement(Element element, StringBuilder out) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Integer.parseInt(tag.substring(1));
                out.append("\n").append("#".repeat(level)).append(" ").append(element.text().trim()).append("\n\n");
            }
            case "p" -> appendParagraph(out, inlineMarkdown(element));
            case "br" -> out.append("\n");
            case "ul", "ol" -> appendList(element, out, "ol".equals(tag));
            case "pre" -> out.append("\n```\n").append(element.text()).append("\n```\n\n");
            case "blockquote" -> appendBlockquote(element, out);
            case "table" -> appendTable(element, out);
            case "hr" -> out.append("\n---\n\n");
            case "article", "section", "main", "div", "body" -> {
                for (Element child : element.children()) {
                    appendElement(child, out);
                }
                if (element.children().isEmpty()) {
                    appendParagraph(out, element.text());
                }
            }
            default -> {
                if (element.children().isEmpty()) {
                    appendParagraph(out, element.text());
                } else {
                    for (Element child : element.children()) {
                        appendElement(child, out);
                    }
                }
            }
        }
    }

    private void appendParagraph(StringBuilder out, String text) {
        String cleaned = text == null ? "" : text.trim();
        if (!cleaned.isBlank()) {
            out.append(cleaned).append("\n\n");
        }
    }

    private void appendList(Element list, StringBuilder out, boolean ordered) {
        int index = 1;
        out.append("\n");
        for (Element item : list.children()) {
            if (!"li".equalsIgnoreCase(item.tagName())) {
                continue;
            }
            out.append(ordered ? index + ". " : "- ")
                    .append(inlineMarkdown(item).trim())
                    .append("\n");
            index++;
        }
        out.append("\n");
    }

    private void appendBlockquote(Element element, StringBuilder out) {
        String quoted = inlineMarkdown(element).trim();
        if (quoted.isBlank()) {
            return;
        }
        out.append("\n");
        for (String line : quoted.split("\\R")) {
            out.append("> ").append(line).append("\n");
        }
        out.append("\n");
    }

    private void appendTable(Element table, StringBuilder out) {
        List<Element> rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }
        out.append("\n");
        boolean wroteHeader = false;
        for (Element row : rows) {
            List<String> cells = row.select("th,td").stream()
                    .map(Element::text)
                    .map(text -> text.replace("|", "\\|").trim())
                    .collect(Collectors.toList());
            if (cells.isEmpty()) {
                continue;
            }
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (!wroteHeader) {
                out.append("| ").append(cells.stream().map(cell -> "---").collect(Collectors.joining(" | "))).append(" |\n");
                wroteHeader = true;
            }
        }
        out.append("\n");
    }

    private String inlineMarkdown(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                out.append(textNode.text());
            } else if (child instanceof Element childElement) {
                String tag = childElement.tagName().toLowerCase(Locale.ROOT);
                if ("a".equals(tag)) {
                    String text = childElement.text().trim();
                    String href = childElement.absUrl("href");
                    if (href == null || href.isBlank()) {
                        href = childElement.attr("href");
                    }
                    out.append(href == null || href.isBlank() ? text : "[" + text + "](" + href + ")");
                } else if ("code".equals(tag)) {
                    out.append('`').append(childElement.text()).append('`');
                } else if ("br".equals(tag)) {
                    out.append("\n");
                } else {
                    out.append(inlineMarkdown(childElement));
                }
            }
        }
        return out.toString().replaceAll("[ \\t]+", " ").trim();
    }

    private String compactBlankLines(String markdown) {
        return markdown.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private Map<String, Object> metadata(Path sourcePath, String sourceFileName, String sourceUrl, String sourceChecksum) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SourceMetadataConstants.SOURCE_PATH, sourcePath.toString());
        metadata.put(SourceMetadataConstants.SOURCE_FILENAME, sourceFileName);
        metadata.put(SourceMetadataConstants.SOURCE_TYPE, "MARKDOWN_DERIVED");
        metadata.put("markdown_converted_at", Instant.now().toString());
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            metadata.put(SourceMetadataConstants.SOURCE_URL, sourceUrl);
        }
        if (sourceChecksum != null && !sourceChecksum.isBlank()) {
            metadata.put("markdown_derived_from_checksum", sourceChecksum);
        }
        return metadata;
    }

    private String uniqueMarkdownFileName(String sourceFileName) {
        String base = stripExtension(sourceFileName);
        if (base == null || base.isBlank()) {
            base = "source_" + UUID.randomUUID().toString().substring(0, 8);
        }
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        String candidate = base + ".md";
        Path path = uploadsPath.resolve(candidate).normalize();
        int suffix = 2;
        while (Files.exists(path)) {
            candidate = base + "-markdown-" + suffix + ".md";
            path = uploadsPath.resolve(candidate).normalize();
            suffix++;
        }
        return candidate;
    }

    private String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "source";
        }
        String name = Paths.get(fileName).getFileName().toString();
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(0, lastDot) : name;
    }

    private String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < fileName.length() - 1
                ? fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    private String checksumFromStoredFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String escapeYaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record SourceReference(Path path, String fileName, String checksum, String sourceUrl) {}

    public record ConversionResult(
            String fileName,
            String filePath,
            String checksum,
            String originalFileName,
            String originalPath,
            String originalChecksum,
            String sourceUrl,
            long sizeBytes,
            String convertedAt
    ) {}
}
