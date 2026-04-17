/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpToolInjectionSupportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void createPreferredConfigPrefersSseWhenAvailable() throws Exception {
        McpToolInjectionSupport.McpConfig config =
                McpToolInjectionSupport.createPreferredConfig(tempDir, "http://localhost:8080/mcp/sse");

        assertNotNull(config);
        assertEquals("sse", config.transport());

        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(Path.of(config.path())));
        JsonNode server = root.path("mcpServers").path("kompile");

        assertEquals("http://localhost:8080/mcp/sse", server.path("url").asText());
        assertEquals("sse", server.path("transport").asText());
        assertFalse(server.has("command"));
    }

    @Test
    void createStdioConfigUsesKompileMcpStdioCommand() throws Exception {
        McpToolInjectionSupport.McpConfig config = McpToolInjectionSupport.createStdioConfig(tempDir);

        assertNotNull(config);
        assertEquals("stdio", config.transport());

        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(Path.of(config.path())));
        JsonNode server = root.path("mcpServers").path("kompile");
        JsonNode args = server.path("args");

        assertTrue(server.has("command"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), server.path("cwd").asText());
        assertTrue(arrayContains(args, "mcp-stdio"));
        assertTrue(arrayContains(args, "--work-dir"));
        assertTrue(arrayContains(args, tempDir.toAbsolutePath().normalize().toString()));
        assertFalse(arrayContains(args, "ai.kompile.cli.mcp.stdio.CliMcpStdioServer"));
    }

    private static boolean arrayContains(JsonNode node, String expected) {
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (expected.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
