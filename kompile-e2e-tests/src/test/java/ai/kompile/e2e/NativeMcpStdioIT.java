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
