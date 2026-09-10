/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.codeindex.LocalCodeKGraphPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("user.home")
class ReadToolContextTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private String previousHome;
    private Path projectRoot;
    private Path source;
    private ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        projectRoot = tempDir.resolve("project");
        source = projectRoot.resolve("src/main/java/demo/ReadContext.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                final class ReadContext {
                    String value() { return "context"; }
                }
                """, StandardCharsets.UTF_8);
        String projectId = "read-context-" + Math.abs(System.nanoTime());
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(projectRoot, projectId, null, null, true, quiet);
        }
        LocalCodeKGraphPublisher.publish(projectRoot, projectId, null, null);

        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("read-context-session", null, permissions,
                projectRoot, new ToolRegistry(mapper));
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void ordinaryReadOutputRemainsUnchangedUntilContextIsRequested() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("file_path",
                projectRoot.relativize(source).toString());

        ToolResult result = new ReadTool().execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("1\tpackage demo;"), result.getOutput());
        assertFalse(result.getOutput().contains("File context"), result.getOutput());
        assertFalse(result.getMetadata().containsKey("fileContext"));
    }

    @Test
    void requestedReadIncludesNotesAndStructuredGraphContext() throws Exception {
        String relative = projectRoot.relativize(source).toString();
        ToolResult added = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add").put("file_path", relative)
                .put("content", "Read this together with its callers."), context);
        assertFalse(added.isError(), added.getOutput());

        ToolResult result = new ReadTool().execute(mapper.createObjectNode()
                .put("file_path", relative).put("include_context", true), context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("--- File context (not file contents) ---"), result.getOutput());
        assertTrue(result.getOutput().contains("Read this together with its callers."), result.getOutput());
        assertTrue(result.getOutput().contains("ReadContext"), result.getOutput());
        @SuppressWarnings("unchecked")
        Map<String, Object> fileContext = (Map<String, Object>) result.getMetadata().get("fileContext");
        assertEquals("AVAILABLE", fileContext.get("indexStatus"));
        assertEquals("AVAILABLE", fileContext.get("graphStatus"));
    }

    @Test
    void batchSupportsGlobalAndPerFileContextWithoutChangingOtherSections() throws Exception {
        Path plain = projectRoot.resolve("plain.txt");
        Files.writeString(plain, "plain\n");
        ObjectNode params = mapper.createObjectNode().put("include_context", true);
        ArrayNode files = params.putArray("files");
        files.add(projectRoot.relativize(source).toString());
        files.addObject().put("file_path", "plain.txt").put("include_context", false);

        ToolResult result = new ReadBatchTool().execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> contexts =
                (java.util.List<Map<String, Object>>) result.getMetadata().get("fileContexts");
        assertEquals(1, contexts.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) contexts.get(0).get("context");
        assertFalse(summary.containsKey("notes"), "batch metadata must stay summary-only");
        assertFalse(summary.containsKey("symbols"), "batch metadata must stay summary-only");
        assertFalse(summary.containsKey("relations"), "batch metadata must stay summary-only");
        assertEquals(1, count(result.getOutput(), "--- File context (not file contents) ---"));
        assertTrue(result.getOutput().contains("== plain.txt"), result.getOutput());
    }

    @Test
    void batchRejectsMoreThanFiveContextLookupsBeforeReading() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("include_context", true);
        ArrayNode files = params.putArray("files");
        for (int i = 0; i < 6; i++) files.add("missing-" + i + ".java");

        ToolResult result = new ReadBatchTool().execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("more than 5 files"), result.getOutput());
    }

    @Test
    void offsetPastEndRecordsAFreshRead() throws Exception {
        String relative = projectRoot.relativize(source).toString();
        ToolResult read = new ReadTool().execute(mapper.createObjectNode()
                .put("file_path", relative).put("offset", 10_000), context);

        assertFalse(read.isError(), read.getOutput());
        assertTrue(context.hasFreshFileRead(source),
                "a successful past-end window must satisfy the read-before-edit snapshot gate");
    }

    private static int count(String text, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }
}
