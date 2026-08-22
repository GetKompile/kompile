/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.run;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsCudaRequestForCudaDistribution() throws Exception {
        writeMetadata("nd4j-cuda-12.9");

        assertDoesNotThrow(() -> RunCommand.validateRequestedBackend("cuda", tempDir));
    }

    @Test
    void rejectsCpuRequestForCudaDistribution() throws Exception {
        writeMetadata("nd4j-cuda-12.9");

        IOException error = assertThrows(IOException.class,
                () -> RunCommand.validateRequestedBackend("cpu", tempDir));

        assertTrue(error.getMessage().contains("nd4j-cuda-12.9"));
        assertTrue(error.getMessage().contains("cannot be switched at runtime"));
    }

    @Test
    void permitsDevelopmentTreeWithoutDistributionMetadata() {
        assertDoesNotThrow(() -> RunCommand.validateRequestedBackend("cuda", tempDir));
    }

    @Test
    void rejectsUnknownBackendBeforeLaunching() {
        IOException error = assertThrows(IOException.class,
                () -> RunCommand.validateRequestedBackend("rocm", tempDir));

        assertTrue(error.getMessage().contains("expected cpu or cuda"));
    }

    @Test
    void mapsCliTuningToServingRuntimeOptions() {
        RunCommand command = new RunCommand();
        new CommandLine(command).parseArgs(
                "/tmp/model",
                "--port", "19090",
                "--host", "0.0.0.0",
                "--max-tokens", "128",
                "--temperature", "0.25");

        Map<String, Object> options = command.runtimeOptions();

        assertEquals(19090, options.get("port"));
        assertEquals("0.0.0.0", options.get("host"));
        assertEquals(128, options.get("maxNewTokens"));
        assertEquals(0.25d, options.get("temperature"));
    }

    @Test
    void usesCanonicalServingChatProtocol() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/llm/chat", exchange -> {
            try {
                captured.set(mapper.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"rawText":"unparsed","content":"hello from cuda",
                         "toolCalls":[],"parseErrors":[],
                         "finishReason":"completed","totalTimeMs":3}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            RunCommand command = new RunCommand();
            new CommandLine(command).parseArgs("/tmp/model", "--max-tokens", "64");
            List<Map<String, String>> messages =
                    List.of(Map.of("role", "user", "content", "hello"));

            String response = command.sendChatCompletion(
                    "http://127.0.0.1:" + server.getAddress().getPort(), messages);

            assertEquals("hello from cuda", response);
            JsonNode request = captured.get();
            assertEquals("hello",
                    request.path("request").path("messages").path(0).path("content").asText());
            assertEquals("NONE", request.path("request").path("toolChoice").asText());
            assertEquals(64, request.path("maxTokens").asInt());
        } finally {
            server.stop(0);
        }
    }

    private void writeMetadata(String backend) throws IOException {
        Files.writeString(tempDir.resolve(".dist-info.json"),
                "{\"backend\":\"" + backend + "\"}\n");
    }
}
