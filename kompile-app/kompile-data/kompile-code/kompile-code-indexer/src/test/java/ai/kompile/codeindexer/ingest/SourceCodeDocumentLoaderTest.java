/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.codeindexer.ingest;

import ai.kompile.codeindexer.service.LanguageRegistry;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCodeDocumentLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsExactUtf8SourceAndStampsCodeMetadata() throws Exception {
        String source = "package demo;\nfinal class Café {\n    String value = \"☕\";\n}\n";
        Path file = tempDir.resolve("Cafe.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        SourceCodeDocumentLoader loader = new SourceCodeDocumentLoader(new LanguageRegistry());
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toString())
                .build();

        assertTrue(loader.supports(descriptor));
        List<Document> documents = loader.load(descriptor);

        assertEquals(1, documents.size());
        assertEquals(source, documents.get(0).getText());
        assertEquals("java", documents.get(0).getMetadata().get("code.language"));
        assertEquals("source-code", documents.get(0).getMetadata().get("documentType"));
        assertEquals("code", documents.get(0).getMetadata().get(GraphConstants.META_CONTENT_TYPE));
        assertEquals(SourceCodeDocumentLoader.NAME,
                documents.get(0).getMetadata().get(GraphConstants.META_LOADER));
    }

    @Test
    void doesNotStealStructuredOrDocumentationFilesFromSpecializedLoaders() throws Exception {
        SourceCodeDocumentLoader loader = new SourceCodeDocumentLoader(new LanguageRegistry());
        for (String name : List.of("config.json", "README.md", "page.html", "style.css")) {
            Path file = tempDir.resolve(name);
            Files.writeString(file, "content", StandardCharsets.UTF_8);
            assertFalse(loader.supports(DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(file.toString())
                    .build()), name);
        }
    }

    @Test
    void rejectsNulContainingSource() throws Exception {
        Path file = tempDir.resolve("Bad.java");
        Files.write(file, new byte[]{'c', 'l', 'a', 's', 's', ' ', 'X', 0, '{', '}'});
        SourceCodeDocumentLoader loader = new SourceCodeDocumentLoader(new LanguageRegistry());
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toString())
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }
}
