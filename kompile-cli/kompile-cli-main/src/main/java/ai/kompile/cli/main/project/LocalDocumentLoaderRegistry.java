/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.project;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loader.excel.ExcelLoaderImpl;
import ai.kompile.loader.pdf.PdfExtendedLoaderImpl;
import ai.kompile.loader.web.WebHtmlLoaderImpl;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small, explicit bridge from the local CLI crawl to application document loaders.
 *
 * <p>The local MCP host is not a Spring application, so {@code @Component}-discovered
 * loaders are not instantiated there. This registry keeps the bridge deliberately narrow:
 * formats with application loaders are selected explicitly and converted into the
 * local crawl's single searchable Markdown body.</p>
 */
public final class LocalDocumentLoaderRegistry {
    private LocalDocumentLoaderRegistry() {
    }

    public static boolean supports(String loaderName) {
        String canonicalLoader = normalize(loaderName);
        return "excel".equals(canonicalLoader) || "html".equals(canonicalLoader)
                || "pdf".equals(canonicalLoader);
    }

    public static LoadedDocument load(Path file, String loaderName, Map<String, Object> options)
            throws Exception {
        String canonicalLoader = normalize(loaderName);
        DocumentLoader loader = switch (canonicalLoader) {
            case "excel" -> new ExcelLoaderImpl();
            case "html" -> new WebHtmlLoaderImpl();
            case "pdf" -> new PdfExtendedLoaderImpl();
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

        List<Document> documents = loader.load(descriptor);
        List<String> sections = new ArrayList<>();
        List<LoadedOutput> outputs = new ArrayList<>();
        String title = file.getFileName().toString();
        int index = 0;
        for (Document document : documents) {
            if (document == null) continue;
            Map<String, Object> documentMetadata = document.getMetadata() == null
                    ? Map.of() : new LinkedHashMap<>(document.getMetadata());
            String outputTitle = firstText(documentMetadata, "title", "sheetName", "sheet_name", "fileName",
                    file.getFileName().toString());
            if (index == 0) title = outputTitle;
            String text = asText(documentMetadata.get("full_table_content"));
            if (text == null || text.isBlank()) {
                text = document.getText();
            }
            outputs.add(new LoadedOutput(index++, outputTitle,
                    Collections.unmodifiableMap(new LinkedHashMap<>(documentMetadata))));
            if (text != null && !text.isBlank()) {
                sections.add(text.trim());
            }
        }
        return new LoadedDocument(title, String.join("\n\n", sections), List.copyOf(outputs));
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
