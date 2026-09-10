/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectRagSearchTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @TempDir
    Path projectRoot;

    @Test
    void semanticSearchFindsMeaningWithoutLexicalOverlapAndReusesPersistedVectors()
            throws Exception {
        Path knowledgeBase = writeKnowledgeBase();
        FakeEmbeddingRuntime embedding = new FakeEmbeddingRuntime();
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> {
                    assertEquals(projectRoot.toAbsolutePath().normalize(), root);
                    return embedding;
                });

        ToolResult first = search.search(projectRoot, List.of(knowledgeBase),
                "automobile upkeep", null, 1);

        assertFalse(first.isError(), first.getOutput());
        assertEquals("hybrid", first.getMetadata().get("retrievalMode"));
        assertEquals("test-encoder", first.getMetadata().get("embeddingModel"));
        assertTrue(first.getOutput().contains("Vehicle maintenance schedule"), first.getOutput());
        assertFalse(first.getOutput().contains("Bread baking guide"), first.getOutput());
        assertTrue(Files.isRegularFile(knowledgeBase.resolve("semantic-index.jsonl")));
        assertEquals(3, embedding.embeddedTexts.size(), "two chunks plus one query");

        ToolResult second = search.search(projectRoot, List.of(knowledgeBase),
                "automobile upkeep", null, 1);

        assertFalse(second.isError(), second.getOutput());
        assertEquals(4, embedding.embeddedTexts.size(),
                "the second search should embed only the query, not cached chunks");
    }

    @Test
    void unavailableLocalEncoderDegradesToLexicalSearchWithoutLeavingTheFolder()
            throws Exception {
        Path knowledgeBase = writeKnowledgeBase();
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> {
                    throw new IllegalStateException("local encoder is not materialized");
                });

        ToolResult result = search.search(projectRoot, List.of(knowledgeBase),
                "bread", null, 5);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("lexical-fallback", result.getMetadata().get("retrievalMode"));
        assertEquals("local encoder is not materialized",
                result.getMetadata().get("degradedReason"));
        assertTrue(result.getOutput().contains("Bread baking guide"), result.getOutput());
        assertFalse(Files.exists(knowledgeBase.resolve("semantic-index.jsonl")));
    }

    @Test
    void importedKgraphChunksRemainSearchableWithoutCrawlJsonlCompanions() throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/imported-kb"));
        UnifiedGraph graph = new UnifiedGraph().graphId("imported");
        graph.addEntity(GraphEntity.builder("document:service")
                .type("DOCUMENT")
                .label("ImportedService.java")
                .attribute("documentId", "service")
                .attribute("relativePath", "src/ImportedService.java")
                .build());
        graph.addEntity(GraphEntity.builder("chunk:service-1")
                .type("CHUNK")
                .label("portable graph search")
                .attribute("documentId", "service")
                .attribute("chunkId", "service-1")
                .attribute("content", "Imported service exposes portable graph search")
                .build());
        graph.addRelation("has-chunk", "document:service", "chunk:service-1", "HAS_CHUNK", 1.0);
        graph.save(directory.resolve("graph.kgraph"));

        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> {
                    throw new IllegalStateException("local encoder is not materialized");
                });
        ToolResult result = search.search(projectRoot, List.of(directory),
                "portable graph", null, 5);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("lexical-fallback", result.getMetadata().get("retrievalMode"));
        assertTrue(result.getOutput().contains("src/ImportedService.java"), result.getOutput());
        assertTrue(result.getOutput().contains("Imported service exposes portable graph search"),
                result.getOutput());
    }

    @Test
    void corruptOptionalKgraphDoesNotDiscardHealthyKnowledgeBaseResults() throws Exception {
        Path healthy = writeKnowledgeBase();
        Path corrupt = Files.createDirectories(projectRoot.resolve("data/crawls/corrupt-kb"));
        Files.writeString(corrupt.resolve("graph.kgraph"), "not a graph", StandardCharsets.UTF_8);
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> {
                    throw new IllegalStateException("local encoder is not materialized");
                });

        ToolResult result = search.search(projectRoot, List.of(healthy, corrupt), "bread", null, 5);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("Bread baking guide"), result.getOutput());
    }

    @Test
    void rejectsKnowledgeBasesOutsideTheCurrentProjectDirectory() throws Exception {
        Files.createDirectories(projectRoot.resolve("data/crawls"));
        Path outside = Files.createTempDirectory(projectRoot.getParent(), "foreign-kb-");
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> new FakeEmbeddingRuntime());

        ToolResult result = search.search(projectRoot, List.of(outside), "anything", null, 5);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("outside the current folder"), result.getOutput());
    }

    @Test
    void topicIsAddedOnlyToTheFolderLocalQueryEmbedding() throws Exception {
        Path knowledgeBase = writeKnowledgeBase();
        FakeEmbeddingRuntime embedding = new FakeEmbeddingRuntime();
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> embedding);

        ToolResult result = search.search(projectRoot, List.of(knowledgeBase),
                "maintenance", "fleet operations", 1);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("fleet operations", result.getMetadata().get("topic"));
        assertTrue(embedding.embeddedTexts.get(embedding.embeddedTexts.size() - 1)
                .contains("Topic: fleet operations"));
    }

    @Test
    void encoderFingerprintChangeInvalidatesPersistedChunkVectors() throws Exception {
        Path knowledgeBase = writeKnowledgeBase();
        FakeEmbeddingRuntime firstEmbedding = new FakeEmbeddingRuntime("fingerprint-a");
        LocalProjectRagSearch firstSearch = new LocalProjectRagSearch(mapper,
                (root, ignored) -> firstEmbedding);
        assertFalse(firstSearch.search(projectRoot, List.of(knowledgeBase),
                "automobile upkeep", null, 1).isError());

        FakeEmbeddingRuntime replacement = new FakeEmbeddingRuntime("fingerprint-b");
        LocalProjectRagSearch secondSearch = new LocalProjectRagSearch(mapper,
                (root, ignored) -> replacement);
        ToolResult result = secondSearch.search(projectRoot, List.of(knowledgeBase),
                "automobile upkeep", null, 1);

        assertFalse(result.isError(), result.getOutput());
        assertEquals(3, replacement.embeddedTexts.size(),
                "changed model artifacts must re-embed two chunks plus the query");
    }

    @Test
    void rejectsSymbolicLinkKnowledgeBaseInsideTheCrawlDirectory() throws Exception {
        Path crawlRoot = Files.createDirectories(projectRoot.resolve("data/crawls"));
        Path foreign = Files.createDirectories(projectRoot.resolve("foreign-kb"));
        Path link = crawlRoot.resolve("linked-kb");
        Files.createSymbolicLink(link, foreign);
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> new FakeEmbeddingRuntime());

        ToolResult result = search.search(projectRoot, List.of(link), "anything", null, 5);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("symbolic-link knowledge base"), result.getOutput());
    }

    @Test
    void rejectsSymbolicLinkKgraphFallbackArtifact() throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/linked-graph-kb"));
        Path outsideGraph = projectRoot.resolve("outside.kgraph");
        new UnifiedGraph().graphId("outside").save(outsideGraph);
        Files.createSymbolicLink(directory.resolve("graph.kgraph"), outsideGraph);
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> new FakeEmbeddingRuntime());

        ToolResult result = search.search(projectRoot, List.of(directory), "anything", null, 5);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("symbolic-link crawl artifact"), result.getOutput());
    }

    private Path writeKnowledgeBase() throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/local-kb"));
        Files.writeString(directory.resolve("documents.jsonl"), """
                {"documentId":"vehicle","relativePath":"vehicle.md"}
                {"documentId":"bread","relativePath":"bread.md"}
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("chunks.jsonl"), """
                {"documentId":"vehicle","chunkId":"vehicle-1","text":"Vehicle maintenance schedule"}
                {"documentId":"bread","chunkId":"bread-1","text":"Bread baking guide"}
                """, StandardCharsets.UTF_8);
        return directory;
    }

    private static final class FakeEmbeddingRuntime
            implements LocalProjectRagSearch.EmbeddingRuntime {
        private final List<String> embeddedTexts = new ArrayList<>();
        private final String fingerprint;

        private FakeEmbeddingRuntime() {
            this("test-fingerprint");
        }

        private FakeEmbeddingRuntime(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        @Override
        public String modelId() {
            return "test-encoder";
        }

        @Override
        public String fingerprint() {
            return fingerprint;
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            embeddedTexts.addAll(texts);
            return texts.stream().map(FakeEmbeddingRuntime::vector).toList();
        }

        private static float[] vector(String text) {
            String normalized = text.toLowerCase();
            if (normalized.contains("automobile") || normalized.contains("vehicle")
                    || normalized.contains("maintenance") || normalized.contains("fleet")) {
                return new float[]{1.0f, 0.0f};
            }
            return new float[]{0.0f, 1.0f};
        }

        @Override
        public void close() {
        }
    }
}
