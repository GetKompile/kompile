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
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.format.ConversationExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpInjectionCommandTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void passthroughFallsBackToCliStdioConfigWhenNoServerIsResolved() throws Exception {
        PassthroughCommand command = new PassthroughCommand();
        command.agent = "codex";
        command.workingDir = tempDir.toString();
        command.injectTools = true;
        command.skipPermissions = true;

        setField(command, "mcpUrlResolved", true);
        setField(command, "resolvedMcpUrl", null);
        setField(command, "mcpConfigResolved", false);
        setField(command, "resolvedMcpConfig", null);

        Method buildCommand = PassthroughCommand.class.getDeclaredMethod("buildCommand", String.class);
        buildCommand.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) buildCommand.invoke(command, "/usr/bin/codex");

        assertTrue(args.contains("--mcp-config"));
        Path configPath = Path.of(args.get(args.indexOf("--mcp-config") + 1));
        assertValidStandaloneConfig(configPath, tempDir);
    }

    @Test
    void resumeFallsBackToCliStdioConfigWhenNoServerIsResolved() throws Exception {
        ResumeCommand command = new ResumeCommand();
        setField(command, "injectTools", true);
        setField(command, "mcpUrlResolved", true);
        setField(command, "resolvedMcpUrl", null);
        setField(command, "mcpConfigResolved", false);
        setField(command, "resolvedMcpConfig", null);

        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "codex",
                tempDir.resolve("session.jsonl"),
                "codex resume --all resume-session",
                tempDir);

        Method buildAgentCommand = ResumeCommand.class.getDeclaredMethod(
                "buildAgentCommand", String.class, ConversationExporter.ExportResult.class);
        buildAgentCommand.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) buildAgentCommand.invoke(command, "codex", exportResult);

        assertTrue(args.contains("--mcp-config"));
        Path configPath = Path.of(args.get(args.indexOf("--mcp-config") + 1));
        assertValidStandaloneConfig(configPath, tempDir);
    }

    private static void assertValidStandaloneConfig(Path configPath, Path expectedWorkingDir) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(configPath));
        JsonNode server = root.path("mcpServers").path("kompile");
        JsonNode args = server.path("args");

        assertTrue(server.has("command"));
        assertEquals(expectedWorkingDir.toAbsolutePath().normalize().toString(), server.path("cwd").asText());
        assertTrue(arrayContains(args, "mcp-stdio"));
        assertTrue(arrayContains(args, "--work-dir"));
        assertTrue(arrayContains(args, expectedWorkingDir.toAbsolutePath().normalize().toString()));
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
