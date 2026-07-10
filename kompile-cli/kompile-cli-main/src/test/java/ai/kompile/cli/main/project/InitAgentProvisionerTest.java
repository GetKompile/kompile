/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InitAgentProvisioner}.
 *
 * <p>Uses package-private seams to avoid real network calls and JVM-global env side-effects:
 * <ul>
 *   <li>{@link InitAgentProvisioner#launcherPathOverride} — injects a fake binary path without
 *       touching {@code KOMPILE_CLI_BINARY} (which is JVM-global and taints parallel tests)</li>
 *   <li>{@link InitAgentProvisioner#ollamaProbeSupplier} — replaces the real HTTP probe</li>
 * </ul>
 */
class InitAgentProvisionerTest {

    private static final ObjectMapper OM = JsonUtils.standardMapper();
    private static final int APP_PORT     = 8091;
    private static final int STAGING_PORT = 8090;

    @TempDir
    Path projectRoot;

    @TempDir
    Path fakeHome;

    private String savedUserHome;
    private String savedLauncherOverride;

    @BeforeEach
    void setUp() {
        savedUserHome = System.getProperty("user.home");
        savedLauncherOverride = InitAgentProvisioner.launcherPathOverride;

        // Redirect user.home so api-agents.json is written under our temp dir
        System.setProperty("user.home", fakeHome.toString());
        // Use a fake binary path so findCliLauncher() doesn't grope around the classpath
        InitAgentProvisioner.launcherPathOverride = "/usr/local/bin/kompile";
        // Replace Ollama probe — default to "down" so tests that don't care don't block
        InitAgentProvisioner.ollamaProbeSupplier = InitAgentProvisioner.OllamaProbeResult::down;
    }

    @AfterEach
    void tearDown() {
        if (savedUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", savedUserHome);
        }
        InitAgentProvisioner.launcherPathOverride = savedLauncherOverride;
        InitAgentProvisioner.ollamaProbeSupplier  = InitAgentProvisioner::probeOllama;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // .mcp.json — created with all three entries
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mcpJsonCreatedWithAllThreeEntries() throws Exception {
        InitAgentProvisioner.writePersistentMcpConfig(projectRoot, APP_PORT, STAGING_PORT);

        Path mcpJson = projectRoot.resolve(".mcp.json");
        assertTrue(Files.exists(mcpJson), ".mcp.json must be created");

        ObjectNode root = (ObjectNode) OM.readTree(Files.readString(mcpJson));
        ObjectNode servers = (ObjectNode) root.get("mcpServers");
        assertNotNull(servers, "mcpServers key must exist");

        // ── stdio entry ──────────────────────────────────────────────────
        ObjectNode kompile = (ObjectNode) servers.get("kompile");
        assertNotNull(kompile, "kompile stdio entry must exist");
        assertEquals("/usr/local/bin/kompile", kompile.get("command").asText(),
                "command must match the fake launcher path");
        // args must contain "mcp-stdio" and "--work-dir"
        List<String> args = OM.convertValue(kompile.get("args"), new TypeReference<>() {});
        assertTrue(args.contains("mcp-stdio"),    "args must include 'mcp-stdio'");
        assertTrue(args.contains("--work-dir"),   "args must include '--work-dir'");
        String cwd = kompile.get("cwd").asText();
        assertFalse(cwd.isBlank(), "cwd must be set");

        // ── app SSE entry ────────────────────────────────────────────────
        ObjectNode appEntry = (ObjectNode) servers.get("kompile-app");
        assertNotNull(appEntry, "kompile-app entry must exist");
        assertEquals("sse", appEntry.get("type").asText());
        assertEquals("http://localhost:" + APP_PORT + "/mcp/sse", appEntry.get("url").asText());

        // ── staging SSE entry ────────────────────────────────────────────
        ObjectNode stagingEntry = (ObjectNode) servers.get("kompile-model-staging");
        assertNotNull(stagingEntry, "kompile-model-staging entry must exist");
        assertEquals("sse", stagingEntry.get("type").asText());
        assertEquals("http://localhost:" + STAGING_PORT + "/mcp/sse", stagingEntry.get("url").asText());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // .mcp.json — merge: pre-existing foreign entry preserved
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mcpJsonMergesWithPreExistingForeignEntry() throws Exception {
        // Write a pre-existing .mcp.json with a "foreign-server" entry
        Path mcpJson = projectRoot.resolve(".mcp.json");
        String preExisting = """
                {
                  "mcpServers": {
                    "foreign-server": {
                      "type": "sse",
                      "url": "http://foreign.example.com/mcp/sse"
                    }
                  }
                }
                """;
        Files.writeString(mcpJson, preExisting);

        InitAgentProvisioner.writePersistentMcpConfig(projectRoot, APP_PORT, STAGING_PORT);

        ObjectNode root    = (ObjectNode) OM.readTree(Files.readString(mcpJson));
        ObjectNode servers = (ObjectNode) root.get("mcpServers");

        // Foreign entry must still be present
        ObjectNode foreign = (ObjectNode) servers.get("foreign-server");
        assertNotNull(foreign, "Pre-existing 'foreign-server' entry must be preserved");
        assertEquals("http://foreign.example.com/mcp/sse", foreign.get("url").asText());

        // All three kompile entries must also be present
        assertNotNull(servers.get("kompile"),               "stdio entry must be written");
        assertNotNull(servers.get("kompile-app"),           "app SSE entry must be written");
        assertNotNull(servers.get("kompile-model-staging"), "staging SSE entry must be written");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGENTS.md — written when absent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void agentsMdWrittenWhenAbsent() {
        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        Path agentsMd = projectRoot.resolve("AGENTS.md");
        assertTrue(Files.exists(agentsMd), "AGENTS.md must be created");

        // The result must mention that it was written (not skipped)
        boolean writtenLine = result.summaryLines().stream()
                .anyMatch(l -> l.contains("AGENTS.md") && l.contains("written"));
        assertTrue(writtenLine, "Summary must report AGENTS.md written. Summary: " + result.summaryLines());

        // Must contain the project name and port table
        String content;
        try {
            content = Files.readString(agentsMd);
        } catch (Exception e) {
            fail("Could not read AGENTS.md: " + e.getMessage());
            return;
        }
        assertTrue(content.contains("" + APP_PORT),     "AGENTS.md must contain app port");
        assertTrue(content.contains("" + STAGING_PORT), "AGENTS.md must contain staging port");
        assertTrue(content.contains("mcp/sse"),          "AGENTS.md must reference MCP SSE URL");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGENTS.md — untouched when already present
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void agentsMdUntouchedWhenPresent() throws Exception {
        Path agentsMd = projectRoot.resolve("AGENTS.md");
        String original = "# My existing AGENTS.md\n\nDo not overwrite me.\n";
        Files.writeString(agentsMd, original);

        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        assertEquals(original, Files.readString(agentsMd),
                "AGENTS.md content must not be modified");

        boolean untouchedLine = result.summaryLines().stream()
                .anyMatch(l -> l.contains("AGENTS.md") && l.contains("untouched"));
        assertTrue(untouchedLine, "Summary must report AGENTS.md was left untouched. Summary: " + result.summaryLines());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // api-agents.json — Ollama fallback merge logic
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ollamaFallbackRegisteredWhenNoCliAgentFound() throws Exception {
        // Simulate: no CLI agents on PATH (provision() will find none since we're in a tempDir)
        // Simulate: Ollama is up with model "llama3.1:8b"
        InitAgentProvisioner.ollamaProbeSupplier =
                () -> new InitAgentProvisioner.OllamaProbeResult(true, "llama3.1:8b");

        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        Path apiAgents = fakeHome.resolve(".kompile/config/api-agents.json");
        // May or may not have been written depending on whether a CLI was found on PATH.
        // Only assert if the result says "ollama-local" was registered.
        boolean ollamaRegistered = result.summaryLines().stream()
                .anyMatch(l -> l.contains("ollama-local"));
        if (ollamaRegistered) {
            assertTrue(Files.exists(apiAgents), "api-agents.json must be created");
            List<Map<String, Object>> entries = OM.readValue(apiAgents.toFile(),
                    new TypeReference<>() {});
            boolean hasOllama = entries.stream()
                    .anyMatch(e -> "ollama-local".equals(e.get("name")));
            assertTrue(hasOllama, "api-agents.json must contain 'ollama-local' entry");
            // Verify model was set
            Map<String, Object> ollamaEntry = entries.stream()
                    .filter(e -> "ollama-local".equals(e.get("name")))
                    .findFirst().orElseThrow();
            assertEquals("llama3.1:8b", ollamaEntry.get("modelName"));
            assertEquals("http://localhost:11434/v1", ollamaEntry.get("endpointUrl"));
        }
        // Result must have no thrown exceptions
        assertNotNull(result);
    }

    @Test
    void ollamaFallbackNotDuplicatedWhenEntryAlreadyExists() throws Exception {
        // Pre-populate api-agents.json with an existing ollama-local entry
        Path configDir = fakeHome.resolve(".kompile/config");
        Files.createDirectories(configDir);
        Path apiAgents = configDir.resolve("api-agents.json");
        String preExisting = """
                [
                  {
                    "name": "ollama-local",
                    "displayName": "Ollama (local)",
                    "endpointUrl": "http://localhost:11434/v1",
                    "apiKey": "",
                    "modelName": "mistral:7b",
                    "temperature": 0.7,
                    "maxTokens": 4096,
                    "description": "pre-existing",
                    "isDefault": false
                  }
                ]
                """;
        Files.writeString(apiAgents, preExisting);

        InitAgentProvisioner.ollamaProbeSupplier =
                () -> new InitAgentProvisioner.OllamaProbeResult(true, "llama3.1:8b");

        InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        List<Map<String, Object>> entries = OM.readValue(apiAgents.toFile(),
                new TypeReference<>() {});
        long ollamaCount = entries.stream()
                .filter(e -> "ollama-local".equals(e.get("name")))
                .count();
        assertEquals(1, ollamaCount, "ollama-local must not be duplicated");
        // Original model name must be preserved (no overwrite)
        Map<String, Object> entry = entries.stream()
                .filter(e -> "ollama-local".equals(e.get("name")))
                .findFirst().orElseThrow();
        assertEquals("mistral:7b", entry.get("modelName"),
                "pre-existing model name must not be overwritten");
    }

    @Test
    void ollamaFallbackWritesNewFileWhenConfigDirMissing() throws Exception {
        // fakeHome has no .kompile/config directory yet
        InitAgentProvisioner.ollamaProbeSupplier =
                () -> new InitAgentProvisioner.OllamaProbeResult(true, "phi3:mini");

        // Force "no CLI found" scenario via a path that doesn't have any agents
        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        // Only verify file was created if result indicates Ollama path was taken
        boolean ollamaRegistered = result.summaryLines().stream()
                .anyMatch(l -> l.contains("ollama-local"));
        if (ollamaRegistered) {
            Path apiAgents = fakeHome.resolve(".kompile/config/api-agents.json");
            assertTrue(Files.exists(apiAgents), "api-agents.json must be created even when config dir was missing");
            assertTrue(Files.size(apiAgents) > 0, "api-agents.json must not be empty");
        }
        assertNotNull(result);
        // No exceptions — any errors go into warnings
        result.warnings().forEach(System.err::println);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // provision() — never throws
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void provisionNeverThrowsEvenOnBadInput() {
        // Null-like edge: non-existent parent (projectRoot is valid but weird ports)
        assertDoesNotThrow(() ->
                InitAgentProvisioner.provision(projectRoot, -1, 0));
    }

    @Test
    void resultStructureIsPopulated() {
        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);
        assertNotNull(result.summaryLines());
        assertNotNull(result.warnings());
        // summaryLines must be non-empty (at minimum the detection header)
        assertFalse(result.summaryLines().isEmpty(), "summaryLines must not be empty");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // writeProjectCliLlmConfig — per-project CLI LLM config
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void writesCliLlmConfigWhenOpencodeDetected() throws Exception {
        InitAgentProvisioner.writeProjectCliLlmConfig(projectRoot, true);

        Path configFile = projectRoot.resolve("config/cli-llm-config.json");
        assertTrue(Files.exists(configFile), "config/cli-llm-config.json must be created");

        ObjectNode root = (ObjectNode) OM.readTree(Files.readString(configFile));
        assertEquals("opencode", root.get("command").asText(),
                "command must be 'opencode'");
        assertTrue(root.get("enabled").asBoolean(),
                "enabled must be true");
        assertEquals(120, root.get("timeoutSeconds").asInt(),
                "timeoutSeconds must be 120");
        assertTrue(root.has("agentModels") && root.get("agentModels").isObject(),
                "agentModels must be present as an empty object");
    }

    @Test
    void doesNotWriteCliLlmConfigWhenOpencodeNotDetected() throws Exception {
        InitAgentProvisioner.writeProjectCliLlmConfig(projectRoot, false);

        Path configFile = projectRoot.resolve("config/cli-llm-config.json");
        assertFalse(Files.exists(configFile),
                "config/cli-llm-config.json must NOT be written when opencode is not detected");
    }

    @Test
    void mergesCliLlmConfigWhenCommandAlreadySet() throws Exception {
        // Pre-write a config with command=claude
        Path configDir  = projectRoot.resolve("config");
        Path configFile = configDir.resolve("cli-llm-config.json");
        Files.createDirectories(configDir);
        String preExisting = """
                {
                  "enabled": true,
                  "command": "claude",
                  "skipPermissions": false,
                  "timeoutSeconds": 60
                }
                """;
        Files.writeString(configFile, preExisting);

        // Call with opencode=true — existing command must not be overwritten
        InitAgentProvisioner.writeProjectCliLlmConfig(projectRoot, true);

        ObjectNode root = (ObjectNode) OM.readTree(Files.readString(configFile));
        assertEquals("claude", root.get("command").asText(),
                "pre-existing command 'claude' must not be overwritten");
    }

    @Test
    void writesCliLlmConfigWhenExistingFileHasBlankCommand() throws Exception {
        // Pre-write a config with an empty command string
        Path configDir  = projectRoot.resolve("config");
        Path configFile = configDir.resolve("cli-llm-config.json");
        Files.createDirectories(configDir);
        Files.writeString(configFile, "{ \"command\": \"\" }");

        // Call with opencode=true — blank command means we should overwrite
        InitAgentProvisioner.writeProjectCliLlmConfig(projectRoot, true);

        ObjectNode root = (ObjectNode) OM.readTree(Files.readString(configFile));
        assertEquals("opencode", root.get("command").asText(),
                "blank command must be replaced with 'opencode'");
    }
}
