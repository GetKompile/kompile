/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3: a RAG hit surfaces the chunk's {@code sourceMetadata} object (written by the crawl pipeline
 * for connector-sourced chunks) when present, and omits the field entirely -- rather than a
 * misleading {@code null} -- when the chunk row carries none. Uses the same lexical-fallback
 * embedding-runtime idiom as {@link LocalProjectRagSearchTest} so no embedding/LLM call happens.
 */
class LocalProjectRagSearchSourceMetadataTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @TempDir
    Path projectRoot;

    @Test
    void ragHitIncludesSourceMetadataWhenTheChunkRowCarriesIt() throws Exception {
        Path knowledgeBase = writeKnowledgeBase();
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> {
                    throw new IllegalStateException("local encoder is not materialized");
                });

        ToolResult result = search.search(projectRoot, List.of(knowledgeBase),
                "maintenance", null, 1);

        assertFalse(result.isError(), result.getOutput());
        JsonNode evidence = mapper.valueToTree(result.getMetadata().get("evidence"));
        assertEquals("vehicle-1", evidence.get(0).path("chunkId").asText());
        JsonNode sourceMetadata = evidence.get(0).path("sourceMetadata");
        assertTrue(sourceMetadata.isObject(), "expected sourceMetadata on the connector chunk: " + evidence);
        assertEquals("fleet-ops", sourceMetadata.path("slack.channelName").asText());
        assertEquals("alice", sourceMetadata.path("slack.userName").asText());
    }

    @Test
    void ragHitOmitsSourceMetadataFieldWhenTheChunkRowHasNone() throws Exception {
        Path knowledgeBase = writeKnowledgeBase();
        LocalProjectRagSearch search = new LocalProjectRagSearch(mapper,
                (root, ignored) -> {
                    throw new IllegalStateException("local encoder is not materialized");
                });

        ToolResult result = search.search(projectRoot, List.of(knowledgeBase),
                "bread", null, 1);

        assertFalse(result.isError(), result.getOutput());
        JsonNode evidence = mapper.valueToTree(result.getMetadata().get("evidence"));
        assertEquals("bread-1", evidence.get(0).path("chunkId").asText());
        assertFalse(evidence.get(0).has("sourceMetadata"),
                "a chunk with no sourceMetadata must not gain a null/empty placeholder: " + evidence);
    }

    private Path writeKnowledgeBase() throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/local-kb"));
        Files.writeString(directory.resolve("documents.jsonl"), """
                {"documentId":"vehicle","relativePath":"vehicle.md"}
                {"documentId":"bread","relativePath":"bread.md"}
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("chunks.jsonl"), """
                {"documentId":"vehicle","chunkId":"vehicle-1","text":"Vehicle maintenance schedule","sourceMetadata":{"slack.channelName":"fleet-ops","slack.userName":"alice"}}
                {"documentId":"bread","chunkId":"bread-1","text":"Bread baking guide"}
                """, StandardCharsets.UTF_8);
        return directory;
    }
}
