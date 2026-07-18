/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void schemaAdvertisesDirectClaudeReadAction() {
        MemoryTool tool = new MemoryTool(tempDir.resolve(".claude"));

        String actionDescription = tool.parameterSchema()
                .path("properties").path("action").path("description").asText();

        assertTrue(actionDescription.contains("read_claude"));
        assertTrue(tool.parameterSchema().path("properties").path("query")
                .path("description").asText().contains("all provider memory"));
    }
}
