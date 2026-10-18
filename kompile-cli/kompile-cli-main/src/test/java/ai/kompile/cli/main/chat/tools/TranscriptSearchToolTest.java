/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class TranscriptSearchToolTest {
    @TempDir
    Path workingDirectory;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final TranscriptSearchTool tool = new TranscriptSearchTool();
    private ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        // TemporaryUserHome is class-scoped; each method needs a fresh corpus, otherwise
        // ChatHistory appends the fixture again and search counts depend on test order.
        Path corpus = KompileHome.homeDirectory().toPath().resolve("conversations");
        if (Files.exists(corpus)) {
            try (var files = Files.walk(corpus)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("transcript_search", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("test", AgentConfig.builder("tester")
                .enabledTools(Set.of("transcript_search")).build(), permissions,
                workingDirectory, new ToolRegistry(mapper));
        transcript("abc123-one", "codex", "failure in first session");
        transcript("abc123-two", "claude", "failure in second session");
        transcript("other-abc123", "codex", "failure in unrelated session");
    }

    private void transcript(String id, String agent, String message) throws Exception {
        ChatHistory history = new ChatHistory(id);
        try {
            history.open("", agent, false, workingDirectory);
            history.logUserMessage(message);
        } finally {
            history.close();
        }
    }

    private ObjectNode params(String action, String prefix) {
        return mapper.createObjectNode().put("action", action).put("session_id", prefix);
    }

    private ToolResult execute(ObjectNode params) throws Exception {
        return tool.execute(params, context);
    }

    @Test
    void searchByIdAloneListsEveryPrefixMatchButNotSubstringMatches() throws Exception {
        ToolResult result = execute(params("search", "abc123"));
        assertFalse(result.isError());
        assertEquals(2, result.getMetadata().get("count"));
        assertTrue(result.getOutput().contains("abc123-one"));
        assertTrue(result.getOutput().contains("abc123-two"));
        assertFalse(result.getOutput().contains("other-abc123"));
        assertFalse(result.getOutput().contains(">>>"));
    }

    @Test
    void listCombinesTrimmedPrefixAndCaseInsensitiveAgentFilter() throws Exception {
        ToolResult result = execute(params("list", " abc123 ").put("agent", " CODEX "));
        assertFalse(result.isError());
        assertEquals(1, result.getMetadata().get("count"));
        assertTrue(result.getOutput().contains("abc123-one"));
        assertFalse(result.getOutput().contains("abc123-two"));
        assertFalse(result.getOutput().contains("other-abc123"));
    }

    @Test
    void idLookupHonorsResultCap() throws Exception {
        ToolResult result = execute(params("search", "abc123").put("max_results", 1));
        assertEquals(1, result.getMetadata().get("count"));
        assertEquals(2, result.getMetadata().get("total"));
        assertEquals(true, result.getMetadata().get("truncated"));
    }

    @Test
    void contentSearchStillSupportsPrefixAndQueryAlias() throws Exception {
        ToolResult result = execute(params("search", "abc123").put("query", "failure")
                .put("literal", true).put("context", 0).put("files_with_matches", true));
        assertFalse(result.isError());
        assertEquals(2, result.getMetadata().get("scanned"));
        assertEquals(2, result.getMetadata().get("filesMatched"));
        assertTrue(result.getOutput().contains("abc123-one"));
        assertTrue(result.getOutput().contains("abc123-two"));
        assertFalse(result.getOutput().contains("other-abc123"));
    }

    @Test
    void idPrefixesAreLiteralAndCaseSensitiveIndependentOfContentOptions() throws Exception {
        for (String prefix : new String[]{"ABC123", "abc123*", "abc123.", "[abc123]"}) {
            ToolResult result = execute(params("search", prefix).put("case_sensitive", false));
            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("No saved conversations"));
        }
    }

    @Test
    void readResolvesUniquePrefixAndReturnsFullId() throws Exception {
        ToolResult result = execute(params("read", " abc123-o "));
        assertFalse(result.isError());
        assertEquals("abc123-one", result.getMetadata().get("sessionId"));
        assertTrue(result.getOutput().contains("failure in first session"));
        assertFalse(result.getOutput().contains("failure in second session"));
    }

    @Test
    void readRejectsAmbiguousPrefixAndListsCandidates() throws Exception {
        ToolResult result = execute(params("read", "abc123"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Ambiguous session ID prefix"));
        assertTrue(result.getOutput().contains("abc123-one"));
        assertTrue(result.getOutput().contains("abc123-two"));
        assertFalse(result.getOutput().contains("failure"));
    }

    @Test
    void headerOnlyTranscriptStillMakesReadPrefixAmbiguous() throws Exception {
        transcript("header-mixed-one", "codex", "populated transcript");
        Files.writeString(KompileHome.homeDirectory().toPath()
                .resolve("conversations/header-mixed-two.txt"), "Agent: codex\n");
        ToolResult result = execute(params("read", "header-mixed"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Ambiguous session ID prefix"));
        assertTrue(result.getOutput().contains("header-mixed-two"));
    }

    @Test
    void readCanResolveUniqueHeaderOnlyAndEmptyTranscripts() throws Exception {
        Path directory = KompileHome.homeDirectory().toPath().resolve("conversations");
        Files.writeString(directory.resolve("header-unique-one.txt"), "Agent: codex\n");
        ToolResult header = execute(params("read", "header-unique"));
        assertFalse(header.isError());
        assertEquals("header-unique-one", header.getMetadata().get("sessionId"));
        Files.writeString(directory.resolve("empty-unique-one.txt"), "");
        ToolResult empty = execute(params("read", "empty-unique"));
        assertFalse(empty.isError());
        assertTrue(empty.getOutput().contains("Transcript is empty for session: empty-unique-one"));
    }

    @Test
    void readPrefersExactIdEvenWhenItIsAlsoAPrefix() throws Exception {
        transcript("exact-id", "codex", "exact transcript");
        transcript("exact-id-longer", "claude", "longer transcript");
        ToolResult result = execute(params("read", "exact-id"));
        assertFalse(result.isError());
        assertEquals("exact-id", result.getMetadata().get("sessionId"));
        assertTrue(result.getOutput().contains("exact transcript"));
        assertFalse(result.getOutput().contains("longer transcript"));
    }

    @Test
    void noMatchingIdIsEmptyForLookupAndErrorForRead() throws Exception {
        assertFalse(execute(params("list", "missing-id")).isError());
        assertFalse(execute(params("search", "missing-id")).isError());
        assertTrue(execute(params("read", "missing-id")).isError());
    }

    @Test
    void missingSearchCriteriaAndBlankReadAreErrors() throws Exception {
        assertTrue(execute(params("search", " ")).isError());
        assertTrue(execute(params("read", " ")).isError());
    }

    @Test
    void invalidContentRegexStillReturnsError() throws Exception {
        assertTrue(execute(params("search", "abc123").put("pattern", "[")).isError());
    }

    @Test
    void readContinuationResumesBeyond500AndNeverShortensLongLines() throws Exception {
        Path file = KompileHome.homeDirectory().toPath().resolve("conversations/paged.txt");
        String longLine = "long-" + "x".repeat(12000) + "-complete";
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 620; line++) {
            content.append(line == 550 ? longLine : "line-" + line).append('\n');
        }
        Files.writeString(file, content);
        ToolResult first = execute(params("read", "paged"));
        assertEquals(500, first.getMetadata().get("returnedLines"));
        assertEquals(501, first.getMetadata().get("nextOffset"));
        assertEquals(620, first.getMetadata().get("totalLines"));
        assertTrue(first.getOutput().contains("line-500\n"));
        assertFalse(first.getOutput().contains("line-501\n"));
        ToolResult next = execute(params("read", "paged").put("offset", 501).put("limit", 120));
        assertEquals(120, next.getMetadata().get("returnedLines"));
        assertNull(next.getMetadata().get("nextOffset"));
        assertEquals(false, next.getMetadata().get("truncated"));
        assertTrue(next.getOutput().contains(longLine + "\n"));
        assertTrue(next.getOutput().endsWith("line-620\n"));
    }

    @Test
    void readValidatesPageBoundsAndRefusesPaths() throws Exception {
        assertTrue(execute(params("read", "abc123-one").put("offset", 0)).isError());
        assertTrue(execute(params("read", "abc123-one").put("limit", 2001)).isError());
        assertTrue(execute(params("read", "../other")).isError());
    }

    @Test
    void fulltextIsExplicitAndReturnsBoundedContinuationAndCombinedFilters() throws Exception {
        ToolResult first = execute(params("fulltext", "abc123").put("query", "failure").put("limit", 1));
        assertFalse(first.isError());
        assertEquals("fts5", first.getMetadata().get("mode"));
        assertEquals(1, first.getMetadata().get("matches"));
        assertEquals(1, first.getMetadata().get("nextOffset"));
        assertTrue(first.getOutput().contains("[failure]"));
        ToolResult second = execute(params("fulltext", "abc123").put("query", "failure")
                .put("limit", 1).put("offset", 1));
        assertFalse(second.isError());
        assertNull(second.getMetadata().get("nextOffset"));
        assertNotEquals(first.getOutput().split("\n")[1], second.getOutput().split("\n")[1]);
        ToolResult filtered = execute(params("fulltext", "abc123").put("query", "failure")
                .put("agent", "CODEX").put("project", workingDirectory.toString()));
        assertEquals(1, filtered.getMetadata().get("matches"));
        assertTrue(filtered.getOutput().contains("abc123-one"));
        assertFalse(filtered.getOutput().contains("abc123-two"));
    }

    @Test
    void indexedFtsNeverSilentlyChangesRegexOrLiteralSearch() throws Exception {
        ToolResult exact = execute(params("search", "abc123-one").put("pattern", "fail.*first").put("context", 0));
        assertFalse(exact.isError());
        assertEquals(1, exact.getMetadata().get("matches"));
        ToolResult literal = execute(params("search", "abc123-one").put("pattern", "fail.*first").put("literal", true));
        assertEquals(0, literal.getMetadata().get("matches"));
        assertTrue(execute(params("fulltext", "abc123-one").put("query", "fail.*first")).isError());
        assertTrue(execute(params("fulltext", "abc123-one").put("query", "failure").put("literal", true)).isError());
        assertTrue(execute(params("fulltext", "abc123-one").put("query", "failure").put("invert", true)).isError());
    }

    @Test
    void explicitRebuildDoesNotModifySourceTranscripts() throws Exception {
        Path file = KompileHome.homeDirectory().toPath().resolve("conversations/abc123-one.txt");
        String original = Files.readString(file);
        assertFalse(execute(params("rebuild_index", "")).isError());
        assertEquals(original, Files.readString(file));
        assertEquals(1, execute(params("fulltext", "abc123-one").put("query", "failure")).getMetadata().get("matches"));
    }

    @Test
    void schemaDocumentsPrefixLookupAndUniqueRead() {
        String idDescription = tool.parameterSchema().path("properties").path("session_id")
                .path("description").asText();
        assertTrue(idDescription.contains("prefix"));
        assertTrue(idDescription.contains("exactly one"));
        assertTrue(tool.parameterSchema().path("properties").path("pattern")
                .path("description").asText().contains("Omit"));
    }
}
