/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Second-level MCP gate: launches the compiled native CLI and exercises real JSON-RPC stdio.
 *
 * <p>The JVM business-logic matrix remains the exhaustive tool implementation gate. This test is
 * deliberately executable-gated so ordinary module tests do not depend on a native image. Supply
 * {@code -Dkompile.native.mcp.binary=/absolute/path/to/kompile} after building the lean local
 * distribution.</p>
 */
@Tag("integration")
class NativeMcpStdioIT {

    private static final String BINARY_PROPERTY = "kompile.native.mcp.binary";
    private static final String VLM_PDF_PROPERTY = "kompile.native.mcp.vlm.pdf";
    private static final String VLM_PROJECT_ROOT_PROPERTY = "kompile.native.mcp.vlm.projectRoot";
    private static final String VLM_WORKER_PROPERTY = "kompile.native.mcp.vlm.worker";
    private static final String VLM_MODEL_PROPERTY = "kompile.native.mcp.vlm.modelId";
    private static final String VLM_PAGE_RANGE_PROPERTY = "kompile.native.mcp.vlm.pageRange";
    private static final String VLM_MAX_TOKENS_PROPERTY = "kompile.native.mcp.vlm.maxNewTokens";
    private static final String VLM_DPI_PROPERTY = "kompile.native.mcp.vlm.pdfRenderDpi";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void nativeBinaryInitializesListsAndExecutesLocalToolsOverStdio() throws Exception {
        String configured = System.getProperty(BINARY_PROPERTY);
        assertTrue(configured != null && !configured.isBlank(),
                () -> "Set -D" + BINARY_PROPERTY + " to the rebuilt native CLI");
        Path binary = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(binary) && Files.isExecutable(binary),
                () -> "Native CLI is not executable: " + binary);

        try (NativeMcpClient client = new NativeMcpClient(binary, project)) {
            client.initialize();
            client.call("activate_tools", MAPPER.createObjectNode()
                    .put("action", "activate")
                    .put("group", "all"));

            Set<String> tools = client.listTools();
            assertTrue(tools.containsAll(Set.of(
                            "crawl_discover", "crawl_source", "model_runtime", "knowledge_status")),
                    () -> "Native tools/list omitted project-local tools: " + tools);

            client.call("crawl_discover", MAPPER.createObjectNode().put("section", "all"));

            ObjectNode source = MAPPER.createObjectNode()
                    .put("text", "Kompile native stdio integration evidence.")
                    .put("title", "native-stdio-source")
                    .put("dryRun", true);
            source.putArray("steps").add("PREPROCESSING");
            client.call("crawl_source", source);

            client.call("model_runtime", MAPPER.createObjectNode().put("action", "status"));
            client.call("knowledge_status", MAPPER.createObjectNode());
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void nativeBinaryBootstrapsAndIndexesPropertyProvidedVlmPdfOverStdio() throws Exception {
        Path binary = requiredExecutable(BINARY_PROPERTY);
        Path pdf = requiredFile(VLM_PDF_PROPERTY);
        Path projectRoot = requiredDirectory(VLM_PROJECT_ROOT_PROPERTY);
        Path worker = requiredExecutable(VLM_WORKER_PROPERTY);
        String modelId = System.getProperty(VLM_MODEL_PROPERTY, "smoldocling-256m");
        String pageRange = System.getProperty(VLM_PAGE_RANGE_PROPERTY, "1");
        int maxNewTokens = positiveInt(VLM_MAX_TOKENS_PROPERTY, 256);
        int pdfRenderDpi = positiveInt(VLM_DPI_PROPERTY, 144);
        String knowledgeBase = "native-vlm-pdf-it-" + ProcessHandle.current().pid();
        String pipelineId = "native-vlm-pdf";

        try (NativeMcpClient client = new NativeMcpClient(binary, projectRoot)) {
            client.initialize();
            client.call("activate_tools", MAPPER.createObjectNode()
                    .put("action", "activate")
                    .put("group", "all"));

            ObjectNode bootstrap = MAPPER.createObjectNode()
                    .put("action", "bootstrap")
                    .put("modelId", modelId)
                    .put("source", "huggingface")
                    .put("repository", "ds4sd/SmolDocling-256M-preview")
                    .put("format", "vlm")
                    .put("type", "vlm_pipeline")
                    .put("timeoutMinutes", 30);
            JsonNode bootstrapResult = client.call("model_runtime", bootstrap);
            assertTrue(toolText(bootstrapResult).contains(modelId),
                    () -> "model_runtime bootstrap did not report " + modelId + ": "
                            + bootstrapResult);

            ObjectNode crawl = MAPPER.createObjectNode()
                    .put("name", "Native stdio image-PDF VLM indexing");
            crawl.putObject("knowledgeBase").put("name", knowledgeBase);
            crawl.putArray("documents").addObject()
                    .put("path", pdf.toString())
                    .put("sourceType", "FILE")
                    .put("pipelineId", pipelineId);
            ObjectNode pipeline = crawl.putArray("pipelines").addObject()
                    .put("pipelineId", pipelineId)
                    .put("pipelineType", "VLM")
                    .put("loaderName", "pdf")
                    .put("chunkerName", "sentence");
            pipeline.putObject("options")
                    .put("vlmModel", modelId)
                    .put("modelId", modelId)
                    .put("outputFormat", "MARKDOWN")
                    .put("maxPages", 1)
                    .put("pageRange", pageRange)
                    .put("maxNewTokens", maxNewTokens)
                    .put("pdfRenderDpi", pdfRenderDpi)
                    .put("pageBatchSize", 1)
                    .put("temperature", 0.0d)
                    .put("doSample", false)
                    .put("timeoutMinutes", 30);
            crawl.putObject("modelRuntime")
                    .put("autoBootstrap", false)
                    .put("type", "vlm_pipeline")
                    .put("timeoutMinutes", 30);
            crawl.putObject("runtimeConfig")
                    .put("documentModelExecutable", worker.toString())
                    .put("documentModelExecutableMode", "DEDICATED");
            crawl.putArray("steps")
                    .add("LOADING")
                    .add("MARKDOWN_EXTRACTION")
                    .add("CHUNKING")
                    .add("LEXICAL_INDEX");
            crawl.put("strictSteps", true);
            crawl.put("deriveOntology", false);
            crawl.putObject("embeddingTraining").put("enabled", false);
            crawl.putObject("reasoningLearning").put("enabled", false);

            JsonNode crawlResult = client.call("crawl_documents", crawl);
            JsonNode payload = toolJson(crawlResult);
            assertTrue("COMPLETED".equals(payload.path("status").asText()),
                    () -> "native crawl did not complete: " + crawlResult);
            assertTrue(payload.path("failedDocumentCount").asInt(-1) == 0,
                    () -> "native crawl reported document failures: " + crawlResult);
            assertTrue(payload.path("documentCount").asInt() == 1,
                    () -> "native crawl did not process exactly one PDF: " + crawlResult);
            assertTrue(payload.path("chunkCount").asInt() > 0,
                    () -> "native crawl produced no chunks: " + crawlResult);

            Path chunks = projectRoot.resolve("data/crawls")
                    .resolve(knowledgeBase)
                    .resolve("chunks.jsonl");
            assertTrue(Files.isRegularFile(chunks),
                    () -> "native crawl did not persist chunks: " + chunks);
            String indexedText = Files.readString(chunks, StandardCharsets.UTF_8);
            assertTrue(indexedText.length() > 200,
                    () -> "native VLM produced no substantive indexed text: " + indexedText);

            client.call("knowledge_search", MAPPER.createObjectNode()
                    .put("knowledgeBase", knowledgeBase)
                    .put("query", "What text appears in this indexed document?")
                    .put("limit", 3));
        }
    }

    private static int positiveInt(String property, int defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(configured);
        assertTrue(parsed > 0, () -> property + " must be positive: " + configured);
        return parsed;
    }

    private static Path requiredExecutable(String property) {
        Path path = requiredPath(property);
        assertTrue(Files.isRegularFile(path) && Files.isExecutable(path),
                () -> property + " is not an executable file: " + path);
        return path;
    }

    private static Path requiredFile(String property) {
        Path path = requiredPath(property);
        assertTrue(Files.isRegularFile(path), () -> property + " is not a file: " + path);
        return path;
    }

    private static Path requiredDirectory(String property) {
        Path path = requiredPath(property);
        assertTrue(Files.isDirectory(path), () -> property + " is not a directory: " + path);
        return path;
    }

    private static Path requiredPath(String property) {
        String configured = System.getProperty(property);
        assertTrue(configured != null && !configured.isBlank(),
                () -> "Set -D" + property + " to run the native VLM integration");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static JsonNode toolJson(JsonNode result) throws Exception {
        String text = toolText(result);
        int start = text.indexOf('{');
        assertTrue(start >= 0, () -> "MCP tool result did not contain JSON: " + result);
        return MAPPER.readTree(text.substring(start));
    }

    private static String toolText(JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : result.path("content")) {
            if (content.has("text")) {
                text.append(content.path("text").asText()).append('\n');
            }
        }
        return text.toString();
    }

    private static final class NativeMcpClient implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final StringBuilder stderr = new StringBuilder();
        private final Thread stderrThread;
        private int nextId = 1;

        private NativeMcpClient(Path binary, Path project) throws Exception {
            process = new ProcessBuilder(
                    binary.toString(),
                    "mcp-stdio",
                    "--work-dir", project.toString(),
                    "--profile", "full",
                    "--schema-level", "compact")
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            stderrThread = new Thread(() -> {
                try (BufferedReader errors = new BufferedReader(new InputStreamReader(
                        process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errors.readLine()) != null) {
                        synchronized (stderr) {
                            if (stderr.length() < 100_000) {
                                stderr.append(line).append('\n');
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // The process may close the stream while the test tears down.
                }
            }, "native-mcp-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();
        }

        private void initialize() throws Exception {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("protocolVersion", "2024-11-05");
            params.putObject("capabilities");
            params.putObject("clientInfo")
                    .put("name", "kompile-native-stdio-it")
                    .put("version", "1");
            request("initialize", params);
            notify("notifications/initialized", MAPPER.createObjectNode());
        }

        private Set<String> listTools() throws Exception {
            JsonNode result = request("tools/list", MAPPER.createObjectNode());
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode tool : result.path("tools")) {
                String name = tool.path("name").asText();
                assertFalse(name.isBlank(), () -> "Native tools/list exposed an empty name: " + tool);
                names.add(name);
            }
            return names;
        }

        private JsonNode call(String name, ObjectNode arguments) throws Exception {
            ObjectNode params = MAPPER.createObjectNode().put("name", name);
            params.set("arguments", arguments);
            JsonNode result = request("tools/call", params);
            assertFalse(result.path("isError").asBoolean(false),
                    () -> "Native tool " + name + " failed: " + result);
            assertTrue(result.path("content").isArray(),
                    () -> "Native tool " + name + " returned no MCP content array: " + result);
            return result;
        }

        private JsonNode request(String method, ObjectNode params) throws Exception {
            int id = nextId++;
            ObjectNode message = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method);
            message.set("params", params);
            send(message);

            JsonNode response = readResponse(id, Duration.ofSeconds(120));
            if (response.has("error")) {
                throw new AssertionError("JSON-RPC " + method + " failed: " + response
                        + "\nserver stderr:\n" + stderrSnapshot());
            }
            return response.path("result");
        }

        private void notify(String method, ObjectNode params) throws Exception {
            ObjectNode message = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("method", method);
            message.set("params", params);
            send(message);
        }

        private void send(ObjectNode message) throws Exception {
            writer.write(MAPPER.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        }

        private JsonNode readResponse(int id, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    JsonNode message = MAPPER.readTree(line);
                    if (message.has("id") && message.path("id").asInt(Integer.MIN_VALUE) == id) {
                        return message;
                    }
                    continue;
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(10L);
            }
            throw new AssertionError("Timed out waiting for JSON-RPC id " + id
                    + "; processAlive=" + process.isAlive()
                    + "\nserver stderr:\n" + stderrSnapshot());
        }

        private String stderrSnapshot() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }

        @Override
        public void close() throws Exception {
            try {
                writer.close();
            } finally {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(3, TimeUnit.SECONDS);
                    }
                }
                stderrThread.join(1_000L);
            }
        }
    }
}
