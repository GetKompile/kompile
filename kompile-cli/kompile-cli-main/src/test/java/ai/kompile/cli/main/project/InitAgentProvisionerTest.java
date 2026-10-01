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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InitAgentProvisioner}.
 *
 * <p>Uses a package-private seam to avoid JVM-global env side-effects:
 * <ul>
 *   <li>{@link InitAgentProvisioner#launcherPathOverride} — injects a fake binary path without
 *       touching {@code KOMPILE_CLI_BINARY} (which is JVM-global and taints parallel tests)</li>
 *   <li>{@link InitAgentProvisioner#agentSearchPathOverride} — agent detection searches a
 *       directory of the test's own, so no test starts the agent CLIs installed on the machine</li>
 *   <li>{@link InitAgentProvisioner#versionProbeTimeout} — shortens the {@code --version} probe
 *       limit so the hung-probe test stays fast</li>
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

    private Path agentBin;
    private String savedUserHome;
    private String savedLauncherOverride;
    private String savedAgentSearchPath;
    private Duration savedVersionProbeTimeout;

    @BeforeEach
    void setUp() throws IOException {
        savedUserHome = System.getProperty("user.home");
        savedLauncherOverride = InitAgentProvisioner.launcherPathOverride;
        savedAgentSearchPath = InitAgentProvisioner.agentSearchPathOverride;
        savedVersionProbeTimeout = InitAgentProvisioner.versionProbeTimeout;

        // Redirect user.home so nothing provisioning touches lands in the real ~/.kompile
        System.setProperty("user.home", fakeHome.toString());
        // Use a fake binary path so findCliLauncher() doesn't grope around the classpath
        InitAgentProvisioner.launcherPathOverride = "/usr/local/bin/kompile";
        // Detect agents in a directory of our own; the real PATH would start every installed agent CLI
        agentBin = Files.createDirectories(fakeHome.resolve("agent-bin"));
        InitAgentProvisioner.agentSearchPathOverride = agentBin.toString();
    }

    @AfterEach
    void tearDown() {
        if (savedUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", savedUserHome);
        }
        InitAgentProvisioner.launcherPathOverride = savedLauncherOverride;
        InitAgentProvisioner.agentSearchPathOverride = savedAgentSearchPath;
        InitAgentProvisioner.versionProbeTimeout = savedVersionProbeTimeout;
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
    // AGENTS.md — an existing file keeps its content and gains the code-navigation section
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void existingAgentsMdKeepsItsContentAndGainsTheCodeNavigationSection() throws Exception {
        Path agentsMd = projectRoot.resolve("AGENTS.md");
        String original = "# My existing AGENTS.md\n\nDo not overwrite me.\n";
        Files.writeString(agentsMd, original);

        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        String merged = Files.readString(agentsMd);
        assertEquals(original + "\n" + InitAgentProvisioner.codeNavigationSection() + "\n", merged,
                "the existing content must be kept byte for byte, with the section appended");
        boolean sectionLine = result.summaryLines().stream()
                .anyMatch(l -> l.contains("AGENTS.md") && l.contains("code-navigation section"));
        assertTrue(sectionLine, "Summary must report the section was written. Summary: " + result.summaryLines());

        InitAgentProvisioner.AgentProvisionResult again =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        assertEquals(merged, Files.readString(agentsMd), "re-running init must not change AGENTS.md");
        boolean untouchedLine = again.summaryLines().stream()
                .anyMatch(l -> l.contains("AGENTS.md") && l.contains("left untouched")
                        && !l.contains("code-navigation"));
        assertTrue(untouchedLine, "Summary must report AGENTS.md was left untouched. Summary: " + again.summaryLines());
    }

    @Test
    void aFreshlyWrittenAgentsMdAlreadyCarriesTheCurrentSection() throws Exception {
        InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        Path agentsMd = projectRoot.resolve("AGENTS.md");
        String written = Files.readString(agentsMd);
        String section = InitAgentProvisioner.codeNavigationSection();
        assertTrue(written.contains(section), "the template must carry the marked section verbatim");
        assertEquals(written.indexOf(section), written.lastIndexOf(section), "the section must appear once");
        assertFalse(InitAgentProvisioner.mergeCodeNavigation(agentsMd),
                "re-running init must find the template's section current");
        assertEquals(written, Files.readString(agentsMd));
    }

    @Test
    void aStaleSectionIsRefreshedInPlace() throws Exception {
        Path agentsMd = projectRoot.resolve("AGENTS.md");
        String before = "# Mine\n\nbefore\n\n";
        String after = "\n\nafter\n";
        Files.writeString(agentsMd, before + InitAgentProvisioner.CODE_NAVIGATION_BEGIN
                + "\n## CODE NAVIGATION\n\nUse grep for everything.\n"
                + InitAgentProvisioner.CODE_NAVIGATION_END + after);

        assertTrue(InitAgentProvisioner.mergeCodeNavigation(agentsMd));
        assertEquals(before + InitAgentProvisioner.codeNavigationSection() + after,
                Files.readString(agentsMd), "only the text between the markers may change");
        assertFalse(InitAgentProvisioner.mergeCodeNavigation(agentsMd), "a current section must be left alone");
    }

    @Test
    void aBeginMarkerWithoutItsEndNeverSwallowsUserText() throws Exception {
        Path agentsMd = projectRoot.resolve("AGENTS.md");
        String original = "# Mine\n\n" + InitAgentProvisioner.CODE_NAVIGATION_BEGIN + "\nkeep this\n";
        Files.writeString(agentsMd, original);

        assertTrue(InitAgentProvisioner.mergeCodeNavigation(agentsMd));
        String merged = Files.readString(agentsMd);
        assertEquals(original + "\n" + InitAgentProvisioner.codeNavigationSection() + "\n", merged);

        assertFalse(InitAgentProvisioner.mergeCodeNavigation(agentsMd));
        assertEquals(merged, Files.readString(agentsMd), "a second merge must not reach back to the stray marker");
    }

    @Test
    void theAppendedSectionFollowsOneBlankLine() throws Exception {
        Path agentsMd = projectRoot.resolve("AGENTS.md");
        String section = InitAgentProvisioner.codeNavigationSection();
        for (String original : List.of("no newline", "one newline\n", "blank line\n\n")) {
            Files.writeString(agentsMd, original);
            assertTrue(InitAgentProvisioner.mergeCodeNavigation(agentsMd));
            assertEquals(original.stripTrailing() + "\n\n" + section + "\n", Files.readString(agentsMd), original);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // provision() — no external model server is registered
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void provisionRegistersNoExternalModelServer() {
        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        assertFalse(Files.exists(fakeHome.resolve(".kompile/config/api-agents.json")),
                "init must not register an API agent");
        assertTrue(result.summaryLines().stream().noneMatch(l -> l.toLowerCase().contains("ollama")),
                "Summary must not mention Ollama. Summary: " + result.summaryLines());
        assertTrue(result.warnings().stream().noneMatch(w -> w.toLowerCase().contains("ollama")),
                "Warnings must not mention Ollama. Warnings: " + result.warnings());
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
    // provision() — agent detection and the bounded --version probe
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anAgentOnTheSearchPathIsReportedWithItsVersion() throws Exception {
        fakeAgent("codex", "echo 'codex-cli 9.9.9'");

        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);

        assertTrue(result.anyAgentAvailable());
        assertTrue(result.summaryLines().contains("  ✓ codex (codex-cli 9.9.9)"),
                "Summary: " + result.summaryLines());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aVersionProbeThatNeverAnswersIsKilled() throws Exception {
        // Prints nothing and outlives the probe: the read used to wait for it to exit
        Path pidFile = agentBin.resolve("codex.pid");
        fakeAgent("codex", "echo $$ > '" + pidFile + "'\nexec sleep 60");
        InitAgentProvisioner.versionProbeTimeout = Duration.ofSeconds(1);

        long start = System.nanoTime();
        InitAgentProvisioner.AgentProvisionResult result =
                InitAgentProvisioner.provision(projectRoot, APP_PORT, STAGING_PORT);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 10_000, "provision must not wait out the probe; took " + elapsedMs + " ms");
        assertTrue(result.summaryLines().contains("  ✓ codex"),
                "the agent is found, without a version. Summary: " + result.summaryLines());
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle probe = ProcessHandle.of(pid).orElse(null);
        if (probe != null) {
            assertDoesNotThrow(() -> probe.onExit().get(5, TimeUnit.SECONDS),
                    "the timed-out probe must be killed, pid " + pid);
        }
    }

    private void fakeAgent(String command, String body) throws IOException {
        Path script = agentBin.resolve(command);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true), "could not mark " + script + " executable");
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
