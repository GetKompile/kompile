/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loader.excel.ExcelLoaderImpl;
import ai.kompile.loader.mail.MailLoaderImpl;
import ai.kompile.loader.microsoft.MicrosoftOfficeLoaderImpl;
import ai.kompile.loader.pdf.PdfExtendedLoaderImpl;
import ai.kompile.loader.web.WebHtmlLoaderImpl;
import ai.kompile.utils.NativeImageInfo;
import org.springframework.ai.document.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Small, explicit bridge from the local CLI crawl to application document loaders.
 *
 * <p>The local MCP host is not a Spring application, so {@code @Component}-discovered
 * loaders are not instantiated there. This registry keeps the bridge deliberately narrow:
 * formats with application loaders are selected explicitly and converted into the
 * local crawl's single searchable Markdown body.</p>
 */
public final class LocalDocumentLoaderRegistry {
    private static final String EXTERNAL_METADATA_PREFIX = "<!-- kompile-source-metadata: ";
    private LocalDocumentLoaderRegistry() {
    }

    public static boolean supports(String loaderName) {
        String canonicalLoader = normalize(loaderName);
        return "excel".equals(canonicalLoader) || "html".equals(canonicalLoader)
                || "pdf".equals(canonicalLoader) || "external-materialized".equals(canonicalLoader)
                || "mail".equals(canonicalLoader) || "office".equals(canonicalLoader)
                || "rtf".equals(canonicalLoader);
    }

    public static LoadedDocument load(Path file, String loaderName, Map<String, Object> options)
            throws Exception {
        String canonicalLoader = normalize(loaderName);
        if ("external-materialized".equals(canonicalLoader)) {
            return loadExternalMaterialized(file);
        }
        if ("rtf".equals(canonicalLoader)) {
            // RtfText is a pure string transform, not a DocumentLoader: no descriptor to build,
            // and nothing here ever touches java.awt/javax.swing (native-image safe).
            return loadRtf(file);
        }
        if ("pdf".equals(canonicalLoader) && NativeImageInfo.isRunningInNativeImage()) {
            // PDFBox's PDDocument static initializer runs an unconditional AWT Raster/ColorModel
            // warm-up (PDFBox's own workaround for an unrelated JDK color-conversion race). Under
            // GraalVM Native Image, libawt.so DOES load from beside the binary, but its own
            // JNI_OnLoad fails inside JNU_NewStringPlatform because the image does not register
            // the JNI metadata that call needs; Substrate reports that as a FATAL, non-catchable
            // JNI error ("Could not allocate library name") that aborts the whole process, not a
            // Throwable any try/catch can stop. Never construct PdfExtendedLoaderImpl (or touch
            // PDDocument at all) under native image - extract pages with pdftotext instead, fed
            // through the same per-page loop below so citation offsets (pageNumber, bodyStart/
            // bodyEnd) stay on one code path for both extractors.
            return loadPdfViaPdftotext(file);
        }
        DocumentLoader loader = switch (canonicalLoader) {
            case "excel" -> new ExcelLoaderImpl();
            case "html" -> new WebHtmlLoaderImpl();
            case "pdf" -> new PdfExtendedLoaderImpl();
            case "mail" -> new MailLoaderImpl();
            case "office" -> new MicrosoftOfficeLoaderImpl();
            default -> throw new IllegalArgumentException("Unsupported application local loader: " + loaderName);
        };

        Map<String, Object> metadata = options == null
                ? new HashMap<>() : new HashMap<>(options);
        metadata.putIfAbsent("localLoader", canonicalLoader);
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().normalize().toString())
                .sourceId(file.toAbsolutePath().normalize().toString())
                .metadata(metadata)
                .build();

        // Keep physical page identity; full-document extraction cannot support precise citations.
        if (loader instanceof PdfExtendedLoaderImpl pdf) pdf.setExtractByPage(true);
        List<Document> documents = loader.load(descriptor);
        return toLoadedDocument(file, documents, canonicalLoader);
    }

    /**
     * Extracts a PDF's pages with {@code pdftotext}, never touching PDFBox/{@code PDDocument}.
     *
     * <p>Used unconditionally under GraalVM Native Image (see {@link #load}) and as the JVM-mode
     * fallback when PDFBox itself throws, so citation offsets survive either way.</p>
     */
    static LoadedDocument loadPdfViaPdftotext(Path file) throws IOException {
        return toLoadedDocument(file, loadPdfPagesWithPdftotext(file), "pdf");
    }

    /**
     * Converts one {@code .rtf} file to plain text with {@link RtfText} - a pure string
     * transform, not a {@link DocumentLoader}, so it never builds a
     * {@link DocumentSourceDescriptor} and never touches AWT/Swing.
     *
     * <p>Read as ISO-8859-1, a lossless byte&lt;-&gt;char mapping, rather than UTF-8, so every raw
     * byte - including the ones inside a {@code \'hh} hex escape meant to be reinterpreted through
     * the document's own {@code \ansicpg} code page - survives into the string RtfText parses.</p>
     */
    private static LoadedDocument loadRtf(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.ISO_8859_1);
        Document document = new Document(RtfText.toPlainText(raw));
        document.getMetadata().put("source", file.toAbsolutePath().normalize().toString());
        document.getMetadata().put("fileName", file.getFileName().toString());
        document.getMetadata().put("fileSize", Files.size(file));
        document.getMetadata().put("documentType", "rtf");
        document.getMetadata().put("loader", "RtfText");
        return toLoadedDocument(file, List.of(document), "rtf");
    }

    private static LoadedDocument toLoadedDocument(Path file, List<Document> documents, String canonicalLoader)
            throws IOException {
        List<String> sections = new ArrayList<>();
        List<LoadedOutput> outputs = new ArrayList<>();
        String title = file.getFileName().toString();
        int index = 0;
        long bodyOffset = 0;
        for (Document document : documents) {
            if (document == null) continue;
            // Same drop/redact rules as the external-source connector path: strip
            // password/token/secret/...-suffixed keys outright and redact URI userinfo or
            // key=value secrets in the surviving string values (mail headers, Office document
            // properties, etc. can otherwise carry credential-shaped text straight into the
            // crawl's stored metadata).
            Map<String, Object> documentMetadata = document.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : LocalExternalSourceLoaderRegistry.sanitizeMetadata(document.getMetadata());
            String outputTitle = firstText(documentMetadata, "title", "sheetName", "sheet_name", "fileName",
                    file.getFileName().toString());
            if (index == 0) title = outputTitle;
            String text = asText(documentMetadata.get("full_table_content"));
            if (text == null || text.isBlank()) {
                text = document.getText();
            }
            if ("pdf".equals(canonicalLoader)) {
                text = ProjectCrawlCommand.normalizeLocalBody(text);
                if (!text.isBlank()) {
                    if (!sections.isEmpty()) bodyOffset += 2; // joining separator
                    documentMetadata.put("bodyStart", bodyOffset);
                    bodyOffset += text.length();
                    documentMetadata.put("bodyEnd", bodyOffset);
                }
            }
            outputs.add(new LoadedOutput(index++, outputTitle,
                    Collections.unmodifiableMap(new LinkedHashMap<>(documentMetadata))));
            if (text != null && !text.isBlank()) {
                sections.add("pdf".equals(canonicalLoader) ? text : text.trim());
            }
        }
        return new LoadedDocument(title, String.join("\n\n", sections), List.copyOf(outputs));
    }

    /**
     * Runs {@code pdftotext -enc UTF-8 <file> -} (reading order, no {@code -layout}, closer to
     * PDFBox's stripper) and splits its stdout into one {@link Document} per non-blank page.
     */
    static List<Document> loadPdfPagesWithPdftotext(Path file) throws IOException {
        return pdfPagesFromFormFeedText(new String(
                runPoppler(file, "pdftotext", "-enc", "UTF-8", file.toString(), "-"), StandardCharsets.UTF_8));
    }

    /** Page count from {@code pdfinfo}: the native-image replacement for PDFBox's page count. */
    static int pdfPageCountWithPdfinfo(Path file) throws IOException {
        String info = new String(runPoppler(file, "pdfinfo", file.toString()), StandardCharsets.UTF_8);
        // Keep the last "Pages:" line: only fields printed before it (Title, Subject, ...) carry
        // document-controlled text that could embed a line of its own.
        int pages = -1;
        for (String line : info.split("\\R")) {
            if (!line.startsWith("Pages:")) continue;
            try {
                pages = Integer.parseInt(line.substring("Pages:".length()).trim());
            } catch (NumberFormatException e) {
                pages = -1;
            }
        }
        if (pages < 0) throw new IOException("pdfinfo reported no page count for " + file);
        return pages;
    }

    /** Renders one 1-based page to PNG with {@code pdftoppm}, which writes to stdout without an output root. */
    static byte[] renderPdfPageWithPdftoppm(Path file, int pageNumber, int dpi) throws IOException {
        String page = Integer.toString(pageNumber);
        return runPoppler(file, "pdftoppm", "-png", "-r", Integer.toString(dpi),
                "-f", page, "-l", page, "-singlefile", file.toString());
    }

    /**
     * Runs one poppler-utils command and returns its stdout.
     *
     * <p>stdout and stderr are captured to separate temp files - never {@code redirectErrorStream
     * (true)} - so poppler's "Syntax Warning/Error" chatter can never leak into the extracted
     * output. A real 10-minute {@code waitFor} timeout applies regardless of how much output the
     * tool has produced (a plain read-until-EOF loop before waiting is unbounded).</p>
     */
    private static byte[] runPoppler(Path file, String... command) throws IOException {
        return runPoppler(file, 10, TimeUnit.MINUTES, command);
    }

    /**
     * Runs one poppler-utils command with an injectable timeout and returns its stdout.
     *
     * <p>The timeout bounds the process's entire lifetime, not just its startup: stdout/stderr
     * are redirected straight to temp files at the OS level (never read back via an in-JVM loop
     * before the {@code waitFor} bound applies), so the single {@code waitFor(timeout, unit)} call
     * below already covers everything from "process started" to "process exited or was forcibly
     * killed," with no unbounded step in between - a plain read-until-EOF loop before waiting
     * would NOT have this property. Package-private so tests can inject a short timeout against a
     * deliberately hanging command.</p>
     */
    static byte[] runPoppler(Path file, long timeout, TimeUnit unit, String... command) throws IOException {
        String tool = command[0];
        Path stdoutFile = Files.createTempFile("kompile-" + tool + "-out-", ".bin");
        Path stderrFile = Files.createTempFile("kompile-" + tool + "-err-", ".txt");
        try {
            Process process;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(stdoutFile.toFile())
                        .redirectError(stderrFile.toFile())
                        .start();
            } catch (IOException e) {
                throw new IOException("Unable to start " + tool + " for " + file + "; install poppler-utils ("
                        + tool + ") for PDF extraction under GraalVM native image: " + e.getMessage(), e);
            }
            boolean finished;
            try {
                finished = process.waitFor(timeout, unit);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while running " + tool + " for " + file, interrupted);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(tool + " timed out after " + unit.toSeconds(timeout) + " s for " + file);
            }
            if (process.exitValue() != 0) {
                throw new IOException(tool + " failed for " + file + ": " + readTail(stderrFile, 4_000));
            }
            return Files.readAllBytes(stdoutFile);
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    /**
     * Splits raw {@code pdftotext} stdout into one {@link Document} per non-blank page.
     *
     * <p>Poppler's pdftotext terminates every page - including the last - with a form-feed
     * ({@code \f}); verified against poppler 22.01.0 on both a multi-page and a single-page
     * extraction. Splitting on {@code \f} with a negative limit therefore normally yields exactly
     * {@code pageCount + 1} segments, the last of which is the empty tail after the final
     * form-feed and must be dropped rather than treated as a page. That trailing segment is only
     * dropped when the raw output actually ends in {@code \f}, so a hypothetical pdftotext build
     * (or version) that omits the final form-feed still yields every page instead of silently
     * losing the last one. Blank pages are skipped - just as
     * {@code PdfExtendedLoaderImpl.extractByPages} never emits a {@code Document} for them either
     * - but numbering is never compacted around the gap, so a citation still names the true
     * physical page.</p>
     *
     * <p>Package-private and pure (no process spawning) so it is unit-testable without depending
     * on the {@code pdftotext} binary being on {@code PATH}.</p>
     */
    static List<Document> pdfPagesFromFormFeedText(String pdftotextOutput) {
        if (pdftotextOutput == null || pdftotextOutput.isEmpty()) {
            return List.of();
        }
        String[] segments = pdftotextOutput.split("\f", -1);
        int totalPages = pdftotextOutput.endsWith("\f") ? segments.length - 1 : segments.length;
        List<Document> pages = new ArrayList<>();
        for (int i = 0; i < totalPages; i++) {
            String pageText = segments[i];
            if (pageText == null || pageText.trim().isEmpty()) continue;
            Document page = new Document(pageText);
            page.getMetadata().put("extractionType", "singlePage");
            page.getMetadata().put("pageNumber", i + 1);
            page.getMetadata().put("totalPages", totalPages);
            pages.add(page);
        }
        return pages;
    }

    private static String readTail(Path file, int maxChars) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            return content.length() > maxChars ? content.substring(content.length() - maxChars) : content;
        } catch (IOException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static LoadedDocument loadExternalMaterialized(Path file) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("<!-- kompile-source-type: ")) {
                throw new IllegalArgumentException("Missing materialized external source header: " + file);
            }
            line = reader.readLine();
            if (line == null || !line.startsWith(EXTERNAL_METADATA_PREFIX) || !line.endsWith(" -->")) {
                throw new IllegalArgumentException("Missing materialized external metadata header: " + file);
            }
            String json = line.substring(EXTERNAL_METADATA_PREFIX.length(), line.length() - 4);
            metadata.putAll(JsonUtils.standardMapper().readValue(json, LinkedHashMap.class));
            while ((line = reader.readLine()) != null) {
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
        }
        String title = file.getFileName().toString();
        for (String key : List.of("title", "fileName", "file_name", "subject", "name")) {
            String candidate = asText(metadata.get(key));
            if (candidate != null && !candidate.isBlank()) {
                title = candidate;
                break;
            }
        }
        LoadedOutput output = new LoadedOutput(0, title,
                Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
        return new LoadedDocument(title, body.toString().trim(), List.of(output));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
    }

    private static String firstText(Map<String, Object> metadata, String firstKey,
                                    String secondKey, String thirdKey, String fourthKey,
                                    String fallback) {
        for (String key : List.of(firstKey, secondKey, thirdKey, fourthKey)) {
            String value = asText(metadata.get(key));
            if (value != null && !value.isBlank()) return value;
        }
        return fallback;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record LoadedDocument(String title, String text, List<LoadedOutput> outputs) {
    }

    public record LoadedOutput(int index, String title, Map<String, Object> metadata) {
    }
}
