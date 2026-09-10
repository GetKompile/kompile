/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.MainCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in end-to-end smoke for transcript-scoped tasks through the real MCP stdio
 * transport. It also runs a bounded project-local crawl to prove that the same
 * tool server remains usable through terminal crawl result and knowledge search.
 *
 * <p>The class ends in {@code IT}, so ordinary Surefire discovery does not run two
 * extra MCP JVMs on every unit-test invocation. Run it explicitly with
 * {@code -Dtest=McpTodoCrawlPipelineIT}.</p>
 */
@ResourceLock("user.home")
class McpTodoCrawlPipelineIT {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void scopesTasksByTranscriptAndCompletesLocalCrawlLifecycle() throws Exception {
        Path isolatedHome = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("crawl-note.md"),
                "The violet todo crawl marker proves the local MCP lifecycle completed.\n",
                StandardCharsets.UTF_8);

        String previousHome = System.getProperty("user.home");
        String sessionA = "todo-a-" + UUID.randomUUID();
        String sessionB = "todo-b-" + UUID.randomUUID();
        String jobId;
        String knowledgeBase;
        try {
            System.setProperty("user.home", isolatedHome.toString());
            try (McpStdioClient client = startClient(project, isolatedHome, sessionA)) {
                client.initialize();

                List<McpBundleToolLoader.RemoteTool> tools = client.listTools();
                McpBundleToolLoader.RemoteTool todoRead = tool(tools, "todoread");
                McpBundleToolLoader.RemoteTool todoWrite = tool(tools, "todowrite");
                assertTrue(todoRead.inputSchema().path("properties").has("session_id"));
                assertFalse(todoWrite.inputSchema().path("properties").has("session_id"),
                        "writes must default to the active transcript rather than mutate old sessions");

                ObjectNode write = mapper.createObjectNode().put("action", "set");
                write.putArray("todos").addObject()
                        .put("id", "crawl")
                        .put("subject", "Run the local crawl pipeline")
                        .put("status", "in_progress")
                        .put("priority", "high");
                assertToolSuccess(client.callTool("todowrite", write));

                ObjectNode crawl = mapper.createObjectNode();
                crawl.put("async", false);
                crawl.put("waitForCompletion", true);
                crawl.putArray("documents").addObject().put("path", "crawl-note.md");
                crawl.putObject("knowledgeBase").put("name", "todo-crawl-pipeline");
                crawl.putArray("steps")
                        .add("LOADING").add("MARKDOWN_EXTRACTION").add("CHUNKING");
                crawl.put("strictSteps", true);
                crawl.put("deriveOntology", false);
                crawl.putObject("embeddingTraining").put("enabled", false);
                crawl.putObject("reasoningLearning").put("enabled", false);
                crawl.putObject("runtimeConfig").put("runReasoningLearning", false);

                JsonNode crawlCall = client.callTool("crawl_documents", crawl);
                assertToolSuccess(crawlCall);
                JsonNode crawlMetadata = crawlCall.path("structuredContent").path("metadata");
                jobId = crawlMetadata.path("jobId").asText();
                knowledgeBase = crawlMetadata.path("knowledgeBase").asText();
                assertFalse(jobId.isBlank(), crawlCall.toPrettyString());
                assertEquals("todo-crawl-pipeline", knowledgeBase);
                JsonNode crawlHandle = crawlMetadata.path("crawlResult");
                assertEquals("kompile-crawl-result/v1", crawlHandle.path("schema").asText());
                assertTrue(crawlHandle.path("terminal").asBoolean(), crawlHandle.toPrettyString());

                JsonNode statusCall = client.callTool("crawl_control",
                        mapper.createObjectNode()
                                .put("operation", "status")
                                .put("jobId", jobId));
                assertToolSuccess(statusCall);
                JsonNode statusMetadata = statusCall.path("structuredContent").path("metadata");
                assertEquals("project-local", statusMetadata.path("backend").asText());
                assertEquals(jobId, statusMetadata.path("jobId").asText());
                assertTrue(statusMetadata.path("crawlResult").path("terminal").asBoolean(),
                        statusCall.toPrettyString());

                JsonNode resultCall = client.callTool("crawl_result",
                        mapper.createObjectNode().put("jobId", jobId));
                assertToolSuccess(resultCall);
                JsonNode resultHandle = resultCall.path("structuredContent")
                        .path("metadata").path("crawlResult");
                assertEquals(jobId, resultHandle.path("jobId").asText());
                assertEquals(knowledgeBase, resultHandle.path("knowledgeBase").asText());
                assertTrue(resultHandle.path("terminal").asBoolean());

                ObjectNode search = mapper.createObjectNode()
                        .put("query", "violet todo crawl marker")
                        .put("knowledgeBase", knowledgeBase);
                JsonNode searchCall = client.callTool("knowledge_search", search);
                assertToolSuccess(searchCall);
                assertTrue(toolText(searchCall).contains("violet todo crawl marker"),
                        searchCall.toPrettyString());
            }

            // A fresh MCP process defaults to its own transcript and can explicitly
            // inspect the prior transcript without changing the default binding.
            try (McpStdioClient client = startClient(project, isolatedHome, sessionB)) {
                client.initialize();
                JsonNode current = client.callTool("todoread", mapper.createObjectNode());
                assertToolSuccess(current);
                assertTrue(toolText(current).contains("No tasks for session '" + sessionB + "'"),
                        current.toPrettyString());

                JsonNode historical = client.callTool("todoread",
                        mapper.createObjectNode().put("session_id", sessionA));
                assertToolSuccess(historical);
                assertTrue(toolText(historical).contains("Run the local crawl pipeline"),
                        historical.toPrettyString());
                assertEquals(sessionA, historical.path("structuredContent")
                        .path("metadata").path("sessionId").asText());
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private McpStdioClient startClient(Path project, Path home, String sessionId) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> args = List.of(
                "-Xmx512m",
                "-Duser.home=" + home,
                "-Dkompile.coordination.systemRoot="
                        + home.resolve("coordination").toAbsolutePath().normalize(),
                // This tiny text-only crawl must exercise the real guard without depending on
                // unrelated host pressure from the Maven/Surefire process or peer activities.
                "-Dkompile.subprocess.watchdog.admissionMode=fail",
                "-Dkompile.subprocess.watchdog.admissionMaxRamUsedFraction=1.0",
                "-Dkompile.subprocess.watchdog.admissionMinAvailableRamMb=0",
                "-Dkompile.subprocess.watchdog.admissionMinAvailableGpuMb=0",
                "-Dkompile.subprocess.watchdog.admissionMaxGpuUsedFraction=0",
                "-cp", classpath,
                MainCommand.class.getName(),
                "mcp-stdio",
                "--no-daemon",
                "--profile", "full",
                "--work-dir", project.toString(),
                "--transcript-id", sessionId);
        return new McpStdioClient(
                mapper, java, args, Map.of(), project, 180,
                "todo-crawl-pipeline", sessionId);
    }

    private static McpBundleToolLoader.RemoteTool tool(
            List<McpBundleToolLoader.RemoteTool> tools, String name) {
        McpBundleToolLoader.RemoteTool tool = tools.stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst().orElse(null);
        assertNotNull(tool, "MCP tool was not registered: " + name);
        return tool;
    }

    private static void assertToolSuccess(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(), result.toPrettyString());
    }

    private static String toolText(JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : result.path("content")) {
            if ("text".equals(content.path("type").asText())) {
                if (!text.isEmpty()) text.append('\n');
                text.append(content.path("text").asText());
            }
        }
        return text.toString();
    }
}
