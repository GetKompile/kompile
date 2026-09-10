/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.codeindexer.ingest;

import ai.kompile.codeindexer.service.CodeEntityExtractor;
import ai.kompile.codeindexer.service.LanguageRegistry;
import ai.kompile.codeindexer.service.parsers.JvmLanguageParser;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceMetadataConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarationAwareCodeChunkerTest {

    private DeclarationAwareCodeChunker chunker;

    @BeforeEach
    void setUp() {
        LanguageRegistry registry = new LanguageRegistry();
        chunker = new DeclarationAwareCodeChunker(
                new CodeEntityExtractor(registry, List.of(new JvmLanguageParser())));
    }

    @Test
    void splitsTopLevelDeclarationsAndPreservesExactSourceCoverage() {
        String source = """
                package demo;

                class First {
                    void one() {}
                }

                class Second {
                    void two() {}
                }
                """;
        RetrievedDoc document = new RetrievedDoc("code-doc", source,
                Map.of(SourceMetadataConstants.SOURCE_PATH, "Example.java"));

        List<RetrievedDoc> chunks = chunker.chunk(document,
                Map.of("chunkSize", 10_000, "overlap", 0));

        assertEquals(2, chunks.size());
        assertEquals(source, chunks.stream().map(RetrievedDoc::getText)
                .reduce("", String::concat));
        assertEquals("First", chunks.get(0).getMetadata().get("code.declaration.name"));
        assertEquals("Second", chunks.get(1).getMetadata().get("code.declaration.name"));
        assertEquals(0, chunks.get(0).getMetadata().get(SourceMetadataConstants.CHAR_OFFSET_START));
        assertEquals(source.length(), chunks.get(1).getMetadata()
                .get(SourceMetadataConstants.CHAR_OFFSET_END));
    }

    @Test
    void oversizedDeclarationSplitsOnSourceBoundariesWithoutTrimming() {
        String source = "class Large {\n"
                + "    int first = 1;\n"
                + "    int second = 2;\n"
                + "    int third = 3;\n"
                + "}\n";
        RetrievedDoc document = new RetrievedDoc("large", source,
                Map.of(SourceMetadataConstants.SOURCE_PATH, "Large.java"));

        List<RetrievedDoc> chunks = chunker.chunk(document,
                Map.of("chunkSize", 32, "overlap", 0));

        assertTrue(chunks.size() > 1);
        assertEquals(source, chunks.stream().map(RetrievedDoc::getText)
                .reduce("", String::concat));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getText().length() <= 32));
    }

    @Test
    void oversizedClassUsesNestedMethodBoundariesBeforeHardSplitting() {
        String source = """
                class Service {
                    int field0 = 0;
                    int field1 = 1;
                    int field2 = 2;
                    int field3 = 3;
                    int field4 = 4;
                    int field5 = 5;
                    int field6 = 6;
                    int field7 = 7;

                    void first() {
                        int a = 1;
                        int b = 2;
                    }

                    void second() {
                        int c = 3;
                        int d = 4;
                    }
                }
                """;
        RetrievedDoc document = new RetrievedDoc("service", source,
                Map.of(SourceMetadataConstants.SOURCE_PATH, "Service.java"));

        List<RetrievedDoc> chunks = chunker.chunk(document,
                Map.of("chunkSize", 90, "overlap", 0));

        RetrievedDoc first = chunks.stream().filter(chunk -> chunk.getText().contains("void first"))
                .findFirst().orElseThrow();
        RetrievedDoc second = chunks.stream().filter(chunk -> chunk.getText().contains("void second"))
                .findFirst().orElseThrow();
        assertTrue(first.getText().contains("int b = 2;"));
        assertFalse(first.getText().contains("field7"),
                "large preamble must be split before the method's exact range");
        assertTrue(second.getText().contains("int d = 4;"));
        assertEquals("first", first.getMetadata().get("code.declaration.name"));
        assertEquals("second", second.getMetadata().get("code.declaration.name"));
        assertEquals(source, chunks.stream().map(RetrievedDoc::getText).reduce("", String::concat));
    }

    @Test
    void unsupportedLanguageUsesHonestNonDeclarationFallback() {
        String source = "first line\nsecond line\nthird line\n";
        RetrievedDoc document = new RetrievedDoc("plain", source,
                Map.of(SourceMetadataConstants.SOURCE_PATH, "notes.unknown"));

        List<RetrievedDoc> chunks = chunker.chunk(document,
                Map.of("chunkSize", 16, "overlap", 0));

        assertEquals(source, chunks.stream().map(RetrievedDoc::getText)
                .reduce("", String::concat));
        assertFalse((Boolean) chunks.get(0).getMetadata().get("code.declarationAware"));
    }
}
