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
    void schemaDocumentsPrefixLookupAndUniqueRead() {
        String idDescription = tool.parameterSchema().path("properties").path("session_id")
                .path("description").asText();
        assertTrue(idDescription.contains("prefix"));
        assertTrue(idDescription.contains("exactly one"));
        assertTrue(tool.parameterSchema().path("properties").path("pattern")
                .path("description").asText().contains("Omit"));
    }
}
