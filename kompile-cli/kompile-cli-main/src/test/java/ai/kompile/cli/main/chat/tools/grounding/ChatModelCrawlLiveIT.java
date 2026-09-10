/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit, opt-in qualification of the real configured remote chat provider through the complete
 * asynchronous project-local crawl lifecycle. Normal unit/build runs never contact a provider.
 */
class ChatModelCrawlLiveIT {
    private static final String ENABLE_PROPERTY = "kompile.remote.chat.live";
    private static final String CONFIG_ROOT_PROPERTY = "kompile.remote.chat.configRoot";
    private static final String MARKER = "REMOTE CRAWL ORANGE IBIS 7429";

    @TempDir
    Path projectRoot;

    @Test
    void configuredChatVisionModelCompletesAnAsyncCrawlAndPersistsSearchableGraph() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY),
                "Live remote crawl is opt-in; pass -D" + ENABLE_PROPERTY + "=true");

        Path configRoot = Path.of(System.getProperty(
                CONFIG_ROOT_PROPERTY, System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        ChatConfig active = ChatConfig.loadOrFromEnv(configRoot);
        Assumptions.assumeTrue(active != null && active.isValid(),
                "No valid Kompile chat provider is configured");
        Assumptions.assumeFalse(active.isKompileLocalServing(),
                "Live CHAT_MODEL qualification requires a remote provider");
        Assumptions.assumeFalse(active.isKompileServer(),
                "Live CHAT_MODEL qualification requires a direct provider");
        Assumptions.assumeFalse("passthrough".equalsIgnoreCase(active.getChatMode()),
                "Live CHAT_MODEL qualification requires standard direct chat mode");

        // This object was freshly loaded. Lowering reasoning for the temporary test project does
        // not modify the source config and avoids spending an ultra-thinking turn on deterministic OCR.
        active.setThinking(System.getProperty("kompile.remote.chat.thinking", "low"));
        active.saveProject(projectRoot);

        Path image = projectRoot.resolve("remote-chat-marker.png");
        writeMarkerImage(image);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of(
                "crawl_documents", "crawl_control", "crawl_result",
                "knowledge_search", "knowledge_status")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        ToolContext context = new ToolContext(
                "remote-chat-live-it",
                AgentConfig.builder("remote-chat-live-it").enabledTools(Set.of("*")).build(),
                permissions,
                projectRoot,
                new ToolRegistry(mapper));

        String knowledgeBase = "remote-chat-live-it";
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "Remote chat live integration crawl");
        request.put("async", true);
        request.put("deriveOntology", false);
        request.putObject("knowledgeBase").put("name", knowledgeBase);
        request.putObject("runtimeConfig")
                .put("runReasoningLearning", false)
                .put("graphExtractionParallelism", 1)
                .put("graphExtractionRemoteParallelism", 1);
        request.putArray("documents").addObject()
                .put("path", image.toString())
                .put("pipelineId", "remote-chat-vision");
        ObjectNode pipeline = request.putArray("pipelines").addObject()
                .put("pipelineId", "remote-chat-vision")
                .put("pipelineType", "VLM")
                .put("loaderName", "auto")
                .put("chunkerName", "no-op");
        pipeline.putObject("processor").put("type", "CHAT_MODEL");
        pipeline.putObject("options")
                .put("prompt", "Read all text in this image. Return only Markdown containing the exact visible text.")
                .put("outputFormat", "MARKDOWN")
                .put("maxImageBytes", 1_048_576)
                .put("maxResponseChars", 20_000);

        CrawlDocumentsTool crawlDocuments = new CrawlDocumentsTool((String) null, mapper);
        ToolResult accepted = crawlDocuments.execute(request, context);
        assertFalse(accepted.isError(), accepted.getOutput());
        assertEquals("QUEUED", accepted.getMetadata().get("status"));
        String jobId = String.valueOf(accepted.getMetadata().get("jobId"));
        assertFalse(jobId.isBlank(), accepted.getOutput());

        CrawlControlTool crawlControl = new CrawlControlTool((String) null, mapper);
        ToolResult terminal = awaitTerminal(crawlControl, context, mapper, jobId, Duration.ofMinutes(5));
        assertFalse(terminal.isError(), terminal.getOutput());
        assertEquals(Boolean.TRUE, terminal.getMetadata().get("terminal"), terminal.getOutput());

        CrawlResultTool crawlResult = new CrawlResultTool((String) null, mapper);
        ToolResult result = crawlResult.execute(
                mapper.createObjectNode().put("jobId", jobId), context);
        assertFalse(result.isError(), result.getOutput());
        assertEquals("COMPLETED", terminal.getMetadata().get("status"), result.getOutput());
        assertEquals("COMPLETED", result.getMetadata().get("status"), result.getOutput());
        assertEquals(1, ((Number) result.getMetadata().get("documentCount")).intValue());
        assertTrue(((Number) result.getMetadata().get("chunkCount")).intValue() > 0);

        Path crawlDirectory = projectRoot.resolve("data/crawls").resolve(knowledgeBase);
        String chunks = Files.readString(crawlDirectory.resolve("chunks.jsonl"), StandardCharsets.UTF_8)
                .toUpperCase(Locale.ROOT);
        for (String word : MARKER.split(" ")) {
            assertTrue(chunks.contains(word), "Missing OCR token " + word + " in " + chunks);
        }
        assertTrue(Files.isRegularFile(crawlDirectory.resolve(LocalProjectGraphBackend.GRAPH_FILE)));

        String persistedRequest = Files.readString(
                crawlDirectory.resolve("mcp-request.json"), StandardCharsets.UTF_8);
        assertTrue(persistedRequest.contains("CHAT_MODEL"), persistedRequest);
        assertFalse(persistedRequest.contains("apiKey"), persistedRequest);
        assertFalse(persistedRequest.contains("Authorization"), persistedRequest);
        assertFalse(persistedRequest.contains("oauth"), persistedRequest);

        KnowledgeSearchCliTool search = new KnowledgeSearchCliTool((String) null, mapper);
        ObjectNode searchRequest = mapper.createObjectNode()
                .put("query", "orange ibis 7429")
                .put("knowledgeBase", knowledgeBase);
        ToolResult searchResult = search.execute(searchRequest, context);
        assertFalse(searchResult.isError(), searchResult.getOutput());
        assertTrue(searchResult.getOutput().toUpperCase(Locale.ROOT).contains("ORANGE"),
                searchResult.getOutput());
        assertTrue(searchResult.getOutput().contains("7429"), searchResult.getOutput());
    }

    private static ToolResult awaitTerminal(
            CrawlControlTool crawlControl,
            ToolContext context,
            ObjectMapper mapper,
            String jobId,
            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        ToolResult latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = crawlControl.execute(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId),
                    context);
            if (latest.isError() || Boolean.TRUE.equals(latest.getMetadata().get("terminal"))) {
                return latest;
            }
            Thread.sleep(Math.min(250L, Math.max(25L,
                    ((Number) latest.getMetadata().getOrDefault("pollAfterMs", 100)).longValue())));
        }
        assertNotNull(latest, "crawl_control returned no status before timeout");
        throw new AssertionError("Remote chat crawl did not reach terminal state within "
                + timeout + ": " + latest.getOutput());
    }

    private static void writeMarkerImage(Path target) throws Exception {
        BufferedImage image = new BufferedImage(1000, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString(MARKER, 35, 130);
        } finally {
            graphics.dispose();
        }
        assertTrue(ImageIO.write(image, "png", target.toFile()));
        image.flush();
        assertTrue(Files.size(target) < 1_048_576L);
    }
}
