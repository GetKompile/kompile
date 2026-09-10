/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.config.ChatConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelPipelineRunnerTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textDocumentUsesProjectChatProviderAndModelOverride() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startOpenAiServer(requests, "# Remote text\n\nprocessed");
        try {
            saveCustomChat(server, "configured-model");
            Path input = tempDir.resolve("notes.md");
            Files.writeString(input, "remote-text-albatross-marker", StandardCharsets.UTF_8);
            LocalCrawlCapabilities.ResolvedPipeline pipeline = pipeline(
                    "text", Map.of("modelId", "request-model", "maxInputChars", 10_000));

            List<Map<String, Object>> progress = new ArrayList<>();
            String result = ChatModelPipelineRunner.extract(
                    tempDir, input, pipeline, "", progress::add);

            assertTrue(result.contains("Remote text"), result);
            assertEquals(1, requests.size());
            assertEquals("request-model", requests.get(0).path("model").asText());
            JsonNode content = userContent(requests.get(0));
            assertTrue(content.asText().contains("remote-text-albatross-marker"), content.toString());
            assertEquals(100, progress.get(progress.size() - 1).get("progressPercent"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageDocumentUsesAnOpenAiCompatibleImageContentBlock() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startOpenAiServer(requests, "# Remote image\n\nblue square");
        try {
            saveCustomChat(server, "vision-model");
            Path input = tempDir.resolve("square.png");
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(image, "png", input.toFile()));
            image.flush();

            String result = ChatModelPipelineRunner.extract(
                    tempDir, input, pipeline("image", Map.of()), "", null);

            assertTrue(result.contains("Remote image"), result);
            JsonNode content = userContent(requests.get(0));
            assertTrue(content.isArray(), content.toString());
            assertEquals("image_url", content.get(0).path("type").asText());
            assertTrue(content.get(0).path("image_url").path("url").asText()
                    .startsWith("data:image/png;base64,"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pdfPagesAreRenderedAndBatchedWithoutAUnicodeOrNativeModelDependency() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startOpenAiServer(requests, "page batch extracted");
        try {
            saveCustomChat(server, "vision-model");
            Path input = tempDir.resolve("two-pages.pdf");
            try (PDDocument pdf = new PDDocument()) {
                pdf.addPage(new PDPage());
                pdf.addPage(new PDPage());
                pdf.save(input.toFile());
            }

            String result = ChatModelPipelineRunner.extract(
                    tempDir, input, pipeline("pdf", Map.of(
                            "maxPages", 2, "pageBatchSize", 2, "pdfRenderDpi", 72)), "", null);

            assertTrue(result.contains("## Pages 1-2"), result);
            assertEquals(1, requests.size(), "two pages should share the configured batch");
            JsonNode content = userContent(requests.get(0));
            long imageBlocks = 0;
            for (JsonNode block : content) {
                if ("image_url".equals(block.path("type").asText())) imageBlocks++;
            }
            assertEquals(2, imageBlocks, content.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pdfPageRangeUsesOriginalPageNumbersForBatchesPromptsOutputAndProgress() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startOpenAiServer(requests, "selected page batch");
        try {
            saveCustomChat(server, "vision-model");
            Path input = tempDir.resolve("ten-pages.pdf");
            try (PDDocument pdf = new PDDocument()) {
                for (int i = 0; i < 10; i++) pdf.addPage(new PDPage());
                pdf.save(input.toFile());
            }

            List<Map<String, Object>> progress = new ArrayList<>();
            String result = ChatModelPipelineRunner.extract(
                    tempDir, input, pipeline("pdf", Map.of(
                            "pageRange", "7-9", "maxPages", 10,
                            "pageBatchSize", 2, "pdfRenderDpi", 72)), "", progress::add);

            assertTrue(result.contains("## Pages 7-8"), result);
            assertTrue(result.contains("## Pages 9"), result);
            assertFalse(result.contains("## Pages 1"), result);
            assertEquals(2, requests.size());
            assertTrue(userContent(requests.get(0)).toString()
                    .contains("original PDF page(s) 7-8 of 10"));
            assertTrue(userContent(requests.get(1)).toString()
                    .contains("original PDF page(s) 9 of 10"));
            assertEquals(10, progress.get(progress.size() - 1).get("totalPages"));
            assertEquals(3, progress.get(progress.size() - 1).get("selectedPageCount"));
            assertEquals("7-9", progress.get(progress.size() - 1).get("pageRange"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitMaxPagesAboveTwoHundredIsNotSilentlyClamped() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startOpenAiServer(requests, "page 201");
        try {
            saveCustomChat(server, "vision-model");
            Path input = tempDir.resolve("two-hundred-one-pages.pdf");
            try (PDDocument pdf = new PDDocument()) {
                for (int i = 0; i < 201; i++) pdf.addPage(new PDPage());
                pdf.save(input.toFile());
            }

            List<Map<String, Object>> progress = new ArrayList<>();
            ChatModelPipelineRunner.extract(
                    tempDir, input, pipeline("pdf", Map.of(
                            "pageRange", "201", "maxPages", 201,
                            "pageBatchSize", 20, "pdfRenderDpi", 72)), "", progress::add);

            assertEquals(1, requests.size(), "pageRange should avoid rendering the other 200 pages");
            assertTrue(userContent(requests.get(0)).toString().contains("original PDF page(s) 201 of 201"));
            assertEquals(201, progress.get(progress.size() - 1).get("maxPages"));
            assertEquals(1, progress.get(progress.size() - 1).get("selectedPageCount"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedReversedAndOutOfBoundsPageRangesFailBeforeAnyProviderRequest() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = startOpenAiServer(requests, "should not be returned");
        try {
            saveCustomChat(server, "vision-model");
            Path input = tempDir.resolve("three-pages.pdf");
            try (PDDocument pdf = new PDDocument()) {
                for (int i = 0; i < 3; i++) pdf.addPage(new PDPage());
                pdf.save(input.toFile());
            }

            for (String pageRange : List.of("3-2", "1-4", "two", " ")) {
                assertThrows(IOException.class, () -> ChatModelPipelineRunner.extract(
                        tempDir, input, pipeline("pdf", Map.of(
                                "pageRange", pageRange, "maxPages", 10,
                                "pdfRenderDpi", 72)), "", null), pageRange);
            }
            assertTrue(requests.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localServingProviderIsRejectedInsteadOfSilentlyChangingExecutionMode() throws Exception {
        ChatConfig local = new ChatConfig("kompile-local", null, "local-model", "http://127.0.0.1:1");
        local.saveProject(tempDir);
        Path input = tempDir.resolve("notes.txt");
        Files.writeString(input, "text");

        IOException failure = assertThrows(IOException.class, () ->
                ChatModelPipelineRunner.extract(
                        tempDir, input, pipeline("text", Map.of()), "", null));

        assertTrue(failure.getMessage().contains("Use UNIFIED_PIPELINE"), failure.getMessage());
    }

    private LocalCrawlCapabilities.ResolvedPipeline pipeline(
            String loader, Map<String, Object> options) {
        return new LocalCrawlCapabilities.ResolvedPipeline(
                "chat-document", "CHAT_MODEL", loader, "no-op", 0, 0,
                options, Map.of("type", ChatModelPipelineRunner.PROCESSOR_TYPE));
    }

    private void saveCustomChat(HttpServer server, String model) throws IOException {
        ChatConfig config = new ChatConfig(
                "custom", null, model,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.saveProject(tempDir);
    }

    private JsonNode userContent(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if ("user".equals(message.path("role").asText())) {
                return message.path("content");
            }
        }
        throw new AssertionError("Captured request has no user message: " + request);
    }

    private HttpServer startOpenAiServer(List<JsonNode> requests, String output) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            String escaped = mapper.writeValueAsString(output);
            String body = "data: {\"choices\":[{\"delta\":{\"content\":" + escaped
                    + "},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        });
        server.start();
        assertFalse(server.getAddress().getPort() == 0);
        return server;
    }
}
