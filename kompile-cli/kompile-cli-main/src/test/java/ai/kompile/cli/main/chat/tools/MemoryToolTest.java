/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readsClaudeCodeAutoMemoryForCurrentProject() throws Exception {
        Path claudeHome = tempDir.resolve(".claude");
        Path project = tempDir.resolve("work tree").resolve("kompile");
        MemoryTool tool = new MemoryTool(claudeHome);
        Path memoryDir = tool.resolveClaudeMemoryDir(project);
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "# Claude memory\nRemember this.",
                StandardCharsets.UTF_8);

        ToolResult result = tool.readClaudeMemory(MAPPER.createObjectNode(), project);

        assertEquals("# Claude memory\nRemember this.", result.getOutput());
        assertTrue(memoryDir.toString().contains("work-tree-kompile"));
    }

    @Test
    void readsNamedClaudeCodeTopicMemory() throws Exception {
        Path project = tempDir.resolve("project");
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        Path memoryDir = tool.resolveClaudeMemoryDir(project);
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("project_notes.md"), "Direct topic memory",
                StandardCharsets.UTF_8);
        ObjectNode params = MAPPER.createObjectNode().put("file", "project_notes.md");

        assertEquals("Direct topic memory", tool.readClaudeMemory(params, project).getOutput());
    }

    @Test
    void providerScanIncludesClaudeCodeAutoMemoryAndHonorsQuery() throws Exception {
        Path project = tempDir.resolve("project");
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        Path memoryDir = tool.resolveClaudeMemoryDir(project);
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("feedback.md"), "Unique Claude auto-memory fact",
                StandardCharsets.UTF_8);
        ObjectNode params = MAPPER.createObjectNode()
                .put("source", "claude-code")
                .put("query", "unique claude");

        String output = tool.scanProviderMemories(params, project).getOutput();

        assertTrue(output.contains("feedback.md"));
        assertTrue(output.contains("Unique Claude auto-memory fact"));
    }

    @Test
    void searchUnifiesKompileClaudeAndOtherProviderMemory() throws Exception {
        Path project = tempDir.resolve("project");
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        Path kompileMemory = project.resolve(".kompile").resolve("memory");
        Path claudeMemory = tool.resolveClaudeMemoryDir(project);
        Path codexMemory = project.resolve(".codex").resolve("memory");
        Files.createDirectories(kompileMemory);
        Files.createDirectories(claudeMemory);
        Files.createDirectories(codexMemory);
        Files.writeString(kompileMemory.resolve("project.md"), "shared needle from Kompile");
        Files.writeString(claudeMemory.resolve("feedback.md"), "shared needle from Claude");
        Files.writeString(codexMemory.resolve("notes.md"), "shared needle from Codex");

        String output = tool.searchMemory("shared needle", project).getOutput();

        assertTrue(output.contains("project/project.md"));
        assertTrue(output.contains("claude-code/feedback.md"));
        assertTrue(output.contains("codex/notes.md"));
    }

    @Test
    void recallUsesTokenSearchRankingAndTopK() throws Exception {
        Path project = tempDir.resolve("project");
        Path memoryDir = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("dsp-high.md"), """
                ---
                name: DSP graph attention retention
                description: The complete lifecycle plan for graph memory retention
                type: project
                ---
                The DSP graph attention plan covers capture, replay, and retention.
                """);
        Files.writeString(memoryDir.resolve("attention-low.md"), """
                ---
                name: Attention note
                description: A general attention reminder
                type: project
                ---
                Attention is configured here.
                """);
        Files.writeString(memoryDir.resolve("raw-not-typed.md"),
                "DSP graph attention raw markdown should not appear in typed recall.");

        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ObjectNode params = MAPPER.createObjectNode()
                .put("query", "DSP graph attention")
                .put("memoryType", "project")
                .put("top_k", 2);

        String output = tool.recallTypedMemory(memoryDir, params, "project").getOutput();

        assertTrue(output.indexOf("## DSP graph attention retention")
                < output.indexOf("## Attention note"));

        params.put("top_k", 1);
        String limited = tool.recallTypedMemory(memoryDir, params, "project").getOutput();
        assertTrue(limited.contains("## DSP graph attention retention"));
        assertTrue(!limited.contains("## Attention note"));

        ObjectNode unfiltered = MAPPER.createObjectNode()
                .put("query", "DSP graph attention")
                .put("top_k", 10);
        String typedOnly = tool.recallTypedMemory(memoryDir, unfiltered, "project").getOutput();
        assertTrue(!typedOnly.contains("raw-not-typed.md"));
    }

    @Test
    void writeWithoutExplicitFileIsRejectedInsteadOfOverwritingTheIndex() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "write")
                .put("scope", "project")
                .put("content", "should not land in MEMORY.md");

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("action='save'"));
        assertFalse(Files.exists(tempDir.resolve(".kompile/memory/MEMORY.md")));
    }

    @Test
    void appendWithoutExplicitFileIsRejectedInsteadOfMutatingTheIndex() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "append")
                .put("scope", "project")
                .put("content", "should not land in MEMORY.md");

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("action='save'"));
        assertFalse(Files.exists(tempDir.resolve(".kompile/memory/MEMORY.md")));
    }

    @Test
    void writeWithExplicitMemoryMdFileStillEditsTheIndexOnPurpose() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "write")
                .put("scope", "project")
                .put("file", "MEMORY.md")
                .put("content", "deliberate index edit");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        assertEquals("deliberate index edit",
                Files.readString(tempDir.resolve(".kompile/memory/MEMORY.md")));
    }

    @Test
    void readWithoutFileStillDefaultsToMemoryMd() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        Path memoryDir = tempDir.resolve(".kompile").resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "default index content");
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "read")
                .put("scope", "project");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        assertTrue(result.getOutput().contains("default index content"));
    }

    private ToolContext newContext() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        return new ToolContext("memory-tool-test-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(MAPPER));
    }

    @Test
    void schemaAdvertisesDirectClaudeReadAction() {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));

        String actionDescription = tool.parameterSchema()
                .path("properties").path("action").path("description").asText();

        assertTrue(actionDescription.contains("read_claude"));
        assertTrue(tool.parameterSchema().path("properties").path("query")
                .path("description").asText().contains("all provider memory"));
        assertEquals("integer", tool.parameterSchema().path("properties").path("top_k")
                .path("type").asText());
    }

    @Test
    void writeIsAtomicAndLeavesNoLeftoverTempFiles() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "write")
                .put("scope", "project")
                .put("file", "notes.md")
                .put("content", "exact content for write");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        Path memoryDir = tempDir.resolve(".kompile").resolve("memory");
        assertEquals("exact content for write",
                Files.readString(memoryDir.resolve("notes.md"), StandardCharsets.UTF_8));
        assertNoLeftoverTempFiles(memoryDir);
    }

    @Test
    void appendIsAtomicAndLeavesNoLeftoverTempFiles() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "append")
                .put("scope", "project")
                .put("file", "notes.md")
                .put("content", "appended body");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        Path memoryDir = tempDir.resolve(".kompile").resolve("memory");
        String written = Files.readString(memoryDir.resolve("notes.md"), StandardCharsets.UTF_8);
        assertTrue(written.endsWith("appended body"));
        assertNoLeftoverTempFiles(memoryDir);
    }

    @Test
    void saveIsAtomicAndLeavesNoLeftoverTempFiles() throws Exception {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
        ToolContext context = newContext();
        ObjectNode params = MAPPER.createObjectNode()
                .put("action", "save")
                .put("scope", "project")
                .put("memoryType", "project")
                .put("name", "atomic write check")
                .put("description", "regression test for atomic memory writes")
                .put("content", "exact body for save");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        Path memoryDir = tempDir.resolve(".kompile").resolve("memory");
        String noteContent = Files.readString(memoryDir.resolve("atomic_write_check.md"),
                StandardCharsets.UTF_8);
        assertTrue(noteContent.endsWith("exact body for save\n"));
        assertTrue(Files.readString(memoryDir.resolve("MEMORY.md"), StandardCharsets.UTF_8)
                .contains("atomic_write_check.md"));
        assertNoLeftoverTempFiles(memoryDir);
    }

    @Test
    void failedAtomicRewriteLeavesMemoryIndexByteIdentical() throws Exception {
        Path memoryDir = tempDir.resolve(".kompile").resolve("memory");
        Files.createDirectories(memoryDir);
        Path indexFile = memoryDir.resolve("MEMORY.md");
        String originalIndex = "# Kompile Memory Index\n\n"
                + "- [Existing](existing.md) — [project] pre-existing entry\n";
        Files.writeString(indexFile, originalIndex, StandardCharsets.UTF_8);

        PosixFileAttributeView view =
                Files.getFileAttributeView(memoryDir, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "requires a POSIX filesystem");

        Set<PosixFilePermission> writable = view.readAttributes().permissions();
        Set<PosixFilePermission> readOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(memoryDir, readOnly);
            Assumptions.assumeTrue(!Files.isWritable(memoryDir),
                    "process can still write the read-only directory (e.g. running as root)");

            MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));
            ToolContext context = newContext();
            ObjectNode params = MAPPER.createObjectNode()
                    .put("action", "save")
                    .put("scope", "project")
                    .put("memoryType", "project")
                    .put("name", "should not persist")
                    .put("content", "this write must not corrupt the index");

            ToolResult result = tool.execute(params, context);

            assertTrue(result.isError(), result::getOutput);
        } finally {
            Files.setPosixFilePermissions(memoryDir, writable);
        }

        assertEquals(originalIndex, Files.readString(indexFile, StandardCharsets.UTF_8));
    }

    private void assertNoLeftoverTempFiles(Path memoryDir) throws IOException {
        try (var files = Files.list(memoryDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "leftover .tmp file(s) in " + memoryDir);
        }
    }
}
