/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.codeindexer.ingest;

import ai.kompile.codeindexer.service.LanguageRegistry;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.source.SourceMetadataConstants;
import org.springframework.ai.document.Document;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads source files as exact UTF-8 text before the generic Tika fallback. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SourceCodeDocumentLoader implements DocumentLoader {

    public static final String NAME = "source-code";
    private final LanguageRegistry languages;

    public SourceCodeDocumentLoader(LanguageRegistry languages) {
        this.languages = languages;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean supports(DocumentSourceDescriptor source) {
        if (source == null || source.getType() != DocumentSourceDescriptor.SourceType.FILE
                || source.getPathOrUrl() == null || source.getPathOrUrl().isBlank()) {
            return false;
        }
        try {
            return languages.isSourceCode(Path.of(source.getPathOrUrl()));
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor source) throws Exception {
        if (!supports(source)) {
            throw new IllegalArgumentException("Unsupported source-code file: "
                    + (source == null ? null : source.getPathOrUrl()));
        }
        Path path = Path.of(source.getPathOrUrl()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Source-code file does not exist: " + path);
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Source-code file contains NUL bytes: " + path);
        }

        String language = languages.detectLanguage(path);
        Map<String, Object> metadata = new LinkedHashMap<>(source.toSourceMetadata());
        metadata.put(GraphConstants.META_SOURCE, path.toString());
        metadata.put(GraphConstants.META_SOURCE_PATH, path.toString());
        metadata.put(GraphConstants.META_FILE_NAME, path.getFileName().toString());
        metadata.put(GraphConstants.META_FILE_SIZE, Files.size(path));
        metadata.put(GraphConstants.META_LAST_MODIFIED,
                Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()).toString());
        metadata.put(GraphConstants.META_LOADER, NAME);
        metadata.put(GraphConstants.META_CONTENT_TYPE, "code");
        metadata.putIfAbsent(GraphConstants.META_CHUNKER_NAME, DeclarationAwareCodeChunker.NAME);
        metadata.put("documentType", "source-code");
        metadata.put("code.language", language);
        metadata.put(SourceMetadataConstants.SOURCE_FILENAME, path.getFileName().toString());
        metadata.put(SourceMetadataConstants.SOURCE_SIZE_BYTES, Files.size(path));
        return List.of(new Document(content, metadata));
    }
}
