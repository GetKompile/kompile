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

import ai.kompile.cli.main.CliProcessLauncher;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MCP config injection for delegated CLI agents. {@link McpToolInjection#injectTools}
 * first cleans leaked Kompile entries out of agent configs under the user's home
 * (Codex, Gemini, OpenCode), and keeps its launch configs and settings leases under
 * {@code ~/.kompile/run}, so the class runs in a temporary one.
 */
@TemporaryUserHome
class McpToolInjectionTest {

    @TempDir
    Path tempDir;

    /** Tests pin the versions the injection would otherwise ask the installed agents for. */
    @AfterEach
    void forgetTheProbedAgentVersions() {
        McpToolInjection.qwenMcpConfigSupport = null;
        McpToolInjection.openCodeCrushFormat = null;
    }

    @Test
    void codexCommandLineOverridesDoNotMutateGlobalConfig() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path home = tempDir.resolve("isolated-home");
        Path workDir = tempDir.resolve("isolated-work");
        Path binary = createExecutable("kompile cli");
        Files.createDirectories(home);
        Files.createDirectories(workDir);

        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("kompile.cli.binary", binary.toString());

            List<String> overrides = McpToolInjection.codexCommandLineOverrides(workDir);
            String escapedWorkDir = workDir.toAbsolutePath().toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");

            assertEquals(List.of(
                    "-c",
                    "mcp_servers.kompile.command=\"" + binary + "\"",
                    "-c",
                    "mcp_servers.kompile.args=[\"mcp-stdio\", \"--work-dir\", \""
                            + escapedWorkDir + "\"]"),
                    overrides);
            assertFalse(Files.exists(home.resolve(".codex").resolve("config.toml")));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
    }

    @Test
    void aClaudeLaunchGetsAConfigOfItsOwnAndLeavesTheProjectMcpJsonAlone() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("claude-custom-work"));
        Path binary = createExecutable("claude-custom-kompile");
        Path configPath = workDir.resolve(".mcp.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode original = mapper.createObjectNode();
        original.putObject("mcpServers").putObject("project-tools")
                .put("command", "project-mcp");
        String originalText = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(original);
        Files.writeString(configPath, originalText);
        Path launchConfig = null;

        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            launchConfig = McpToolInjection.injectTools(
                    workDir, "claude", "http://localhost:8080/mcp/sse");

            assertNotEquals(configPath, launchConfig);
            assertEquals(McpToolInjection.launchConfigDirectory(), launchConfig.getParent());
            assertEquals(ProcessHandle.current().pid(), McpToolInjection.launchConfigOwner(launchConfig));
            assertEquals(originalText, Files.readString(configPath));
            assertFalse(Files.exists(workDir.resolve(".mcp.json.kompile-backup")));
            assertEquals(List.of("--mcp-config=" + launchConfig),
                    McpToolInjection.commandLineOverrides(workDir, "claude", launchConfig));
            assertEquals(List.of(), McpToolInjection.launchConfigArguments(configPath));

            // Claude Code loads the project's servers itself.
            JsonNode servers = mapper.readTree(launchConfig.toFile()).path("mcpServers");
            assertEquals(1, servers.size(), servers.toString());
            JsonNode kompile = servers.path("kompile");
            assertEquals(binary.toString(), kompile.path("command").asText());
            List<String> args = new ArrayList<>();
            kompile.path("args").forEach(arg -> args.add(arg.asText()));
            assertEquals(List.of("mcp-stdio", "--work-dir", workDir.toString()), args);
            assertFalse(kompile.has("url"), "custom servers force Claude's Kompile bridge to stdio");
            assertEquals("true", kompile.path("env")
                    .path(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV).asText());

            McpToolInjection.removeTools(launchConfig);
            assertFalse(Files.exists(launchConfig));
            assertEquals(originalText, Files.readString(configPath));
        } finally {
            McpToolInjection.removeTools(launchConfig);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void projectStartsRegistrationOutlivesAgentLaunchesAndKeepsConcurrentMcpEdits() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("project-start-work"));
        Path binary = createExecutable("project-start-kompile");
        Path configPath = workDir.resolve(".mcp.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode original = mapper.createObjectNode();
        original.putObject("mcpServers").putObject("project-tools")
                .put("command", "project-mcp");
        Files.writeString(configPath,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(original));
        Path registration = null;

        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            registration = McpToolInjection.injectProjectMcpJson(workDir);
            assertEquals(configPath, registration);
            JsonNode kompile = mapper.readTree(configPath.toFile()).path("mcpServers").path("kompile");
            assertEquals(binary.toString(), kompile.path("command").asText());
            assertEquals("true", kompile.path("env")
                    .path(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV).asText());

            // A Claude Code launch used to restore the shared file's backup on its way in
            // and out, which removed the live registration from every session in the project.
            String registered = Files.readString(configPath);
            McpToolInjection.removeTools(McpToolInjection.injectTools(workDir, "claude", null));
            assertEquals(registered, Files.readString(configPath));

            // Any other agent's launch cleaned up after crashed injections by restoring the
            // backup beside a .mcp.json that carries the Kompile server, which is what a live
            // registration looks like. (A legacy OpenCode, which leases a shared file too.)
            McpToolInjection.openCodeCrushFormat = false;
            McpToolInjection.removeTools(McpToolInjection.injectTools(workDir, "opencode", null));
            assertEquals(registered, Files.readString(configPath));
            assertTrue(Files.exists(workDir.resolve(".mcp.json.kompile-backup")),
                    "the registration keeps the backup it restores from");

            ObjectNode lateServer = mapper.createObjectNode().put("command", "late-mcp");
            new McpConfigStore(workDir).put("late-tools", lateServer,
                    McpConfigStore.Scope.PROJECT, false);

            McpToolInjection.removeTools(registration);
            registration = null;
            JsonNode restored = mapper.readTree(configPath.toFile()).path("mcpServers");
            assertTrue(restored.has("project-tools"));
            assertTrue(restored.has("late-tools"));
            assertFalse(restored.has("kompile"));
        } finally {
            McpToolInjection.removeTools(registration);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aClaudeLaunchReachesARunningAppOverATypedSseEntry() throws Exception {
        Path workDir = Files.createDirectories(tempDir.resolve("claude-sse-work"));
        Path launchConfig = null;
        try {
            launchConfig = McpToolInjection.injectTools(
                    workDir, "claude", "http://localhost:8080/mcp/sse");

            JsonNode kompile = new ObjectMapper().readTree(launchConfig.toFile())
                    .path("mcpServers").path("kompile");
            // Claude Code skips an SSE server whose type is missing, without saying so.
            assertEquals("sse", kompile.path("type").asText());
            assertEquals("http://localhost:8080/mcp/sse", kompile.path("url").asText());
            assertFalse(kompile.has("command"));
            assertFalse(Files.exists(workDir.resolve(".mcp.json")));
        } finally {
            McpToolInjection.removeTools(launchConfig);
        }
    }

    @Test
    @ResourceLock("SYSTEM_PROPERTIES")
    void stdioLauncherCarriesTheActiveTranscriptUuid() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        String previousTranscript = System.getProperty(
                TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        Path binary = createExecutable("transcript-kompile-cli");
        String transcriptId = java.util.UUID.randomUUID().toString();
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            System.setProperty(
                    TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, transcriptId);

            McpToolInjectionSupport.CliLauncher launcher =
                    McpToolInjectionSupport.findCliLauncher();
            List<String> args = launcher.buildArgs(tempDir.toAbsolutePath());

            assertEquals(List.of(
                    "mcp-stdio", "--work-dir", tempDir.toAbsolutePath().toString(),
                    "--transcript-id", transcriptId), args);
        } finally {
            restoreProperty("kompile.cli.binary", previousBinary);
            restoreProperty(
                    TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previousTranscript);
        }
    }

    @Test
    @ResourceLock("SYSTEM_PROPERTIES")
    void jarTierAlwaysUsesInstalledWrapperInsteadOfDirectJavaJar() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        String previousJar = System.getProperty("kompile.cli.jar");
        String previousInstall = System.getProperty("kompile.install.dir");
        String previousTranscript = System.getProperty(
                TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        Path install = tempDir.resolve("installed-dist");
        Path wrapper = install.resolve("bin/kompile");
        Path jar = tempDir.resolve("direct-cli.jar");
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, "#!/usr/bin/env bash\n");
        Files.writeString(jar, "jar");
        assertTrue(wrapper.toFile().setExecutable(true));
        try {
            System.clearProperty("kompile.cli.binary");
            System.setProperty("kompile.cli.jar", jar.toString());
            System.setProperty("kompile.install.dir", install.toString());
            System.setProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY,
                    java.util.UUID.randomUUID().toString());

            McpToolInjectionSupport.CliLauncher launcher =
                    McpToolInjectionSupport.findCliLauncher();

            assertNotNull(launcher);
            assertEquals(wrapper.toAbsolutePath().toString(), launcher.command());
            assertTrue(launcher.prefixArgs().isEmpty());

            System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            McpToolInjectionSupport.CliLauncher bareMcpLauncher =
                    McpToolInjectionSupport.findCliLauncher();
            assertNotNull(bareMcpLauncher);
            assertEquals(wrapper.toAbsolutePath().toString(), bareMcpLauncher.command());
        } finally {
            restoreProperty("kompile.cli.binary", previousBinary);
            restoreProperty("kompile.cli.jar", previousJar);
            restoreProperty("kompile.install.dir", previousInstall);
            restoreProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previousTranscript);
        }
    }

    @Test
    void codexInjectionUsesStdioEvenWhenSseUrlIsAvailable() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("work"));
        Path binary = createExecutable("kompile-cli");
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());

            written = McpToolInjection.injectTools(
                    workDir, "codex", "http://localhost:8080/mcp/sse");

            // A file of this launch alone: ~/.codex/config.toml is every Codex session's.
            assertTrue(McpToolInjection.isLaunchConfig(written), String.valueOf(written));
            assertTrue(written.getFileName().toString().startsWith("kompile-mcp-codex-"), written.toString());
            assertEquals(McpToolInjection.launchConfigDirectory(), written.getParent());
            assertFalse(Files.exists(Path.of(System.getProperty("user.home"), ".codex", "config.toml")));

            List<String> overrides = McpToolInjection.launchConfigArguments(written);
            assertEquals(overrides, McpToolInjection.commandLineOverrides(workDir, "codex", written));
            assertEquals(McpToolInjection.codexCommandLineOverrides(workDir), overrides);
            assertTrue(overrides.contains("mcp_servers.kompile.command=\"" + binary + "\""), overrides.toString());
            assertTrue(overrides.stream().anyMatch(option -> option.startsWith("mcp_servers.kompile.args=")
                    && option.contains("\"mcp-stdio\"")), overrides.toString());
            assertFalse(overrides.stream().anyMatch(option -> option.contains("/mcp/sse")), overrides.toString());

            McpToolInjection.removeTools(written);
            assertFalse(Files.exists(written));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void staleAbsoluteBinaryOverrideFallsBackToExecutableJar() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        String previousJar = System.getProperty("kompile.cli.jar");
        Path staleBinary = tempDir.resolve("missing-kompile-cli").toAbsolutePath();
        Path executableJar = Files.writeString(
                tempDir.resolve("kompile-cli-main-exec.jar"), "executable jar");
        try {
            System.setProperty("kompile.cli.binary", staleBinary.toString());
            System.setProperty("kompile.cli.jar", executableJar.toString());

            CliProcessLauncher.Launcher launcher = CliProcessLauncher.find();

            assertNotNull(launcher);
            assertNotEquals(staleBinary.toString(), launcher.command());
            assertEquals(List.of("-jar", executableJar.toAbsolutePath().toString()),
                    launcher.prefixArgs());
        } finally {
            restoreProperty("kompile.cli.binary", previousBinary);
            restoreProperty("kompile.cli.jar", previousJar);
        }
    }

    @Test
    void deletedProcessExecutableSuffixUsesTheRunnableReplacement() throws Exception {
        Path replacement = createExecutable("replacement-kompile-cli");

        assertEquals(replacement.toString(),
                McpToolInjectionSupport.normalizeCurrentCommand(replacement + " (deleted)"));
    }

    @Test
    void deletedProcessExecutableWithoutAReplacementIsRejected() {
        Path missing = tempDir.resolve("missing-replacement").toAbsolutePath();

        assertEquals(null,
                McpToolInjectionSupport.normalizeCurrentCommand(missing + " (deleted)"));
    }


    @Test
    void piInjectionWritesDirectToolsConfigAndRestoresExistingFile() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path workDir = tempDir.resolve("pi-work");
        Path adapter = tempDir.resolve("adapter");
        Files.createDirectories(workDir);
        Files.createDirectories(adapter);
        Files.writeString(adapter.resolve("package.json"), "{}");
        Path config = workDir.resolve(".pi").resolve("mcp.json");
        Files.createDirectories(config.getParent());
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(config, original);
        Path binary = createExecutable("pi-kompile-cli");

        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            System.setProperty("kompile.pi.adapter.path", adapter.toString());

            Path written = McpToolInjection.injectTools(workDir, "pi-cli", "http://localhost:8080/mcp/sse");
            assertEquals(config, written);

            String configured = Files.readString(config);
            assertTrue(configured.contains("\"other\""));
            assertTrue(configured.contains("\"kompile\""));
            assertTrue(configured.contains("\"directTools\" : true") || configured.contains("\"directTools\" : true"));
            assertTrue(configured.contains("\"toolPrefix\" : \"mcp\""));
            assertTrue(configured.contains("\"lifecycle\" : \"eager\""));
            assertTrue(configured.contains("http://localhost:8080/mcp/sse"));

            assertEquals(List.of("-e", adapter.toString()),
                    McpToolInjection.commandLineOverrides(workDir, "pi"));
        } finally {
            McpToolInjection.removeTools(config);
            assertEquals(original, Files.readString(config));
            restoreProperty("kompile.cli.binary", previousBinary);
            restoreProperty("kompile.pi.adapter.path", previousAdapter);
        }
    }

    @Test
    void piManagedCommandReceivesExtensionBeforeProviderFlags() throws Exception {
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path adapter = tempDir.resolve("adapter-command");
        Files.createDirectories(adapter);
        try {
            System.setProperty("kompile.pi.adapter.path", adapter.toString());
            List<String> base = SubprocessAgentRunner.buildManagedCommand(
                    "pi", "pi", "hello", false, null, true, tempDir, null);
            List<String> command = SubprocessAgentRunner.prependGlobalOptions(
                    base, McpToolInjection.commandLineOverrides(tempDir, "pi"));

            assertEquals("pi", command.get(0));
            assertEquals("-e", command.get(1));
            assertEquals(adapter.toString(), command.get(2));
        } finally {
            restoreProperty("kompile.pi.adapter.path", previousAdapter);
        }
    }

    @Test
    void piAdapterIsProvisionedFromClasspath() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path home = tempDir.resolve("pi-home");
        Files.createDirectories(home);
        try {
            System.setProperty("user.home", home.toString());
            System.clearProperty("kompile.pi.adapter.path");

            Path adapter = PiMcpAdapterProvisioner.ensureProvisioned();
            assertTrue(Files.isRegularFile(adapter.resolve("package.json")));
            assertTrue(Files.isRegularFile(adapter.resolve("index.ts")));
            assertEquals(adapter, PiMcpAdapterProvisioner.ensureProvisioned());
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("kompile.pi.adapter.path", previousAdapter);
        }
    }

    @Test
    void aDelegatedClaudesKompileServerRunsOnStdioAsItsParticipant() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("claude-workflow-work"));
        Path binary = createExecutable("claude-workflow-kompile");
        Path launchConfig = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            launchConfig = McpToolInjection.injectTools(
                    workDir, "claude", "http://localhost:8080/mcp/sse", workerIdentity());
            assertFalse(Files.exists(workDir.resolve(".mcp.json")));

            JsonNode kompile = new ObjectMapper().readTree(launchConfig.toFile()).path("mcpServers").path("kompile");
            assertEquals(binary.toString(), kompile.path("command").asText());
            // kompile-app's SSE server knows no teams; only the stdio server enforces them.
            assertFalse(kompile.has("url"));
            JsonNode env = kompile.path("env");
            assertEquals(4, env.size());
            assertEquals("wf", env.path(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME).asText());
            assertEquals("worker", env.path(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT).asText());
            assertEquals("s1", env.path(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION).asText());
            assertEquals("true", env.path(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV).asText());
        } finally {
            McpToolInjection.removeTools(launchConfig);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aDelegatedCodexCarriesItsParticipantInItsConfigAndOnItsCommandLine() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("codex-workflow-work"));
        Path binary = createExecutable("codex-workflow-kompile");
        Path configFile = null;
        List<String> identity = List.of(
                "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_NAME + "=\"wf\"",
                "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT + "=\"worker\"",
                "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION + "=\"s1\"");
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            configFile = McpToolInjection.injectTools(
                    workDir, "codex", "http://localhost:8080/mcp/sse", workerIdentity());

            List<String> launched = McpToolInjection.launchConfigArguments(configFile);
            assertEquals(identity, launched.subList(4, launched.size()));

            List<String> overrides = McpToolInjection.codexCommandLineOverrides(workDir, workerIdentity());
            assertEquals(identity, overrides.subList(4, overrides.size()));
        } finally {
            McpToolInjection.removeTools(configFile);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void anActiveWorkflowRunsTheLeadsKompileServerOnStdioAsTheLead() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("lead-work"));
        Path binary = createExecutable("lead-kompile");
        Path launchConfig = null;
        WorkflowTeam team = new WorkflowTeam("wf-lead", 1, "designer",
                Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect",
                                "cli", List.of("read", "plan", "delegate"), List.of("reviewer")),
                        "reviewer", new WorkflowTeam.Participant("reviewer", "reviewer",
                                "cli", List.of("read", "validate"), List.of())),
                Map.of("review", "reviewer"), null, null);
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            WorkflowSessionContext.activate(WorkflowTeamSnapshot.resolve(team, new RoleManager(workDir)));

            launchConfig = McpToolInjection.injectTools(workDir, "claude", "http://localhost:8080/mcp/sse");
            JsonNode kompile = new ObjectMapper().readTree(launchConfig.toFile()).path("mcpServers").path("kompile");
            assertFalse(kompile.has("url"));
            assertEquals("wf-lead", kompile.path("env").path(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME).asText());
            assertEquals("designer",
                    kompile.path("env").path(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT).asText());

            List<String> lead = McpToolInjection.codexCommandLineOverrides(workDir);
            assertEquals(List.of(
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_NAME + "=\"wf-lead\"",
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT + "=\"designer\""),
                    lead.subList(4, lead.size()));
            // A delegate's launch carries its own participant, not the lead's.
            List<String> delegate = McpToolInjection.codexCommandLineOverrides(workDir,
                    WorkflowSessionContext.inheritableEnvironment("reviewer"));
            assertEquals(List.of(
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_NAME + "=\"wf-lead\"",
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT + "=\"reviewer\""),
                    delegate.subList(4, delegate.size()));
        } finally {
            WorkflowSessionContext.activate(null);
            McpToolInjection.removeTools(launchConfig);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void openCodeCarriesTheParticipantInTheLayoutItsVersionReads() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("opencode-workflow-work"));
        Path binary = createExecutable("opencode-workflow-kompile");
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());

            // OpenCode 1.x: a config of the launch's own, with an "mcp" entry and its "environment".
            McpToolInjection.openCodeCrushFormat = true;
            written = McpToolInjection.injectTools(
                    workDir, "opencode", "http://localhost:8080/mcp/sse", workerIdentity());
            assertTrue(McpToolInjection.isLaunchConfig(written), String.valueOf(written));
            assertEquals(McpToolInjection.launchConfigDirectory(), written.getParent());
            assertFalse(Files.exists(workDir.resolve("opencode.json")));
            // It travels in the environment, not on the command line.
            assertEquals(List.of(), McpToolInjection.commandLineOverrides(workDir, "opencode", written));
            JsonNode kompile = mapper.readTree(written.toFile()).path("mcp").path("kompile");
            assertTrue(kompile.path("enabled").asBoolean(), kompile.toString());
            assertEquals("local", kompile.path("type").asText());
            assertEquals(binary.toString(), kompile.path("command").path(0).asText());
            assertFalse(kompile.has("url"));
            assertWorkerIdentity(kompile.path("environment"));

            // The launch keeps the config its environment already carries.
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put(McpToolInjection.OPENCODE_CONFIG_CONTENT_ENV,
                    "{\"model\":\"m\",\"mcp\":{\"other\":{\"type\":\"remote\",\"url\":\"http://other\"}}}");
            McpToolInjection.applyLaunchEnvironment(environment, written);
            JsonNode content = mapper.readTree(environment.get(McpToolInjection.OPENCODE_CONFIG_CONTENT_ENV));
            assertEquals("m", content.path("model").asText(), content.toString());
            assertEquals("http://other", content.path("mcp").path("other").path("url").asText());
            assertEquals(kompile, content.path("mcp").path("kompile"));

            McpToolInjection.removeTools(written);
            assertFalse(Files.exists(written));
            written = null;

            // The legacy 0.x reads the .opencode.json its sessions in the project share, with
            // "mcpServers" entries and their "env".
            McpToolInjection.openCodeCrushFormat = false;
            written = McpToolInjection.injectTools(
                    workDir, "opencode", "http://localhost:8080/mcp/sse", workerIdentity());
            assertEquals(workDir.resolve(".opencode.json"), written);
            JsonNode legacy = mapper.readTree(written.toFile()).path("mcpServers").path("kompile");
            assertFalse(legacy.has("url"));
            assertWorkerIdentity(legacy.path("env"));
            Map<String, String> untouched = new LinkedHashMap<>();
            McpToolInjection.applyLaunchEnvironment(untouched, written);
            assertTrue(untouched.isEmpty(), untouched.toString());

            McpToolInjection.removeTools(written);
            written = null;
            assertFalse(Files.exists(workDir.resolve(".opencode.json")), "the file the injection created is removed");
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void qwenGetsAConfigOfItsOwnLaunchWhenItTakesMcpConfigAndSharesItsSettingsOtherwise() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("qwen-work"));
        Path binary = createExecutable("qwen-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(settings, original);
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());

            McpToolInjection.qwenMcpConfigSupport = true;
            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertTrue(McpToolInjection.isLaunchConfig(written), String.valueOf(written));
            assertEquals(McpToolInjection.launchConfigDirectory(), written.getParent());
            assertEquals(List.of("--mcp-config=" + written),
                    McpToolInjection.commandLineOverrides(workDir, "qwen", written));
            assertEquals(binary.toString(),
                    mapper.readTree(written.toFile()).path("mcpServers").path("kompile").path("command").asText());
            assertEquals(original, Files.readString(settings));
            McpToolInjection.removeTools(written);
            assertFalse(Files.exists(written));
            written = null;

            // Without --mcp-config it reads the settings every Qwen Code session in the project shares.
            McpToolInjection.qwenMcpConfigSupport = false;
            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(settings, written);
            assertEquals(List.of(), McpToolInjection.commandLineOverrides(workDir, "qwen", written));
            JsonNode servers = mapper.readTree(settings.toFile()).path("mcpServers");
            assertTrue(servers.has("other"), servers.toString());
            assertEquals(binary.toString(), servers.path("kompile").path("command").asText());
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(original, Files.readString(settings));
            assertFalse(Files.exists(settings.resolveSibling("settings.json.kompile-backup")));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aSharedSettingsFileKeepsTheServerUntilItsLastLeaseIsReleased() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("pi-lease-work"));
        Path binary = createExecutable("pi-lease-kompile");
        Path config = Files.createDirectories(workDir.resolve(".pi")).resolve("mcp.json");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(config, original);
        Path first = null;
        Path second = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());

            // Two sessions of the agent in one project.
            first = McpToolInjection.injectTools(workDir, "pi", null);
            second = McpToolInjection.injectTools(workDir, "pi", null);
            assertEquals(config, first);
            assertEquals(config, second);

            McpToolInjection.removeTools(first);
            first = null;
            // Restoring the file here would take the server from the session still running.
            assertTrue(new ObjectMapper().readTree(config.toFile()).path("mcpServers").has("kompile"),
                    Files.readString(config));

            McpToolInjection.removeTools(second);
            second = null;
            assertEquals(original, Files.readString(config));
            assertFalse(Files.exists(McpToolInjection.leaseRegistryDirectory()
                    .resolve(McpToolInjection.LEASE_REGISTRY_FILE)), "no lease is left to record");
        } finally {
            McpToolInjection.removeTools(first);
            McpToolInjection.removeTools(second);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void cleanupRestoresASharedSettingsFileWhoseLeaseHoldersHaveAllExited() throws Exception {
        Path workDir = Files.createDirectories(tempDir.resolve("dead-lease-work"));
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        Path backup = settings.resolveSibling("settings.json.kompile-backup");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(backup, original);
        Files.writeString(settings,
                "{\"mcpServers\":{\"other\":{\"command\":\"other\"},\"kompile\":{\"command\":\"exited-kompile\"}}}");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode registry = mapper.createObjectNode();
        ObjectNode record = registry.putObject(settings.getParent().toRealPath().resolve("settings.json").toString());
        record.put("existed", true);
        ArrayNode holders = record.putArray("holders");
        // This process, under a lease it does not hold.
        holders.addObject().put("id", "released").put("pid", ProcessHandle.current().pid()).put("started", 0);
        holders.addObject().put("id", "no-process").put("pid", 0).put("started", 0);
        // A live process that started long after the holder did: the holder's pid was reused.
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        if (parent != null && parent.info().startInstant().isPresent()) {
            holders.addObject().put("id", "reused-pid").put("pid", parent.pid()).put("started", 1L);
        }
        Path registryFile = McpToolInjection.leaseRegistryDirectory().resolve(McpToolInjection.LEASE_REGISTRY_FILE);
        Files.createDirectories(registryFile.getParent());
        Files.writeString(registryFile, mapper.writeValueAsString(registry));
        try {
            McpToolInjection.cleanupLeakedEntries(workDir);

            assertEquals(original, Files.readString(settings));
            assertFalse(Files.exists(backup));
            assertFalse(Files.exists(registryFile), "the record of a file with no live holder is dropped");
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void aLeaseAnotherLiveProcessHoldsKeepsTheServerThroughCleanupAndThisProcesssRelease() throws Exception {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        assumeTrue(parent != null && parent.isAlive() && parent.info().startInstant().isPresent(),
                "needs a live parent process to stand in for another Kompile process");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("live-lease-work"));
        Path binary = createExecutable("live-lease-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        Path backup = settings.resolveSibling("settings.json.kompile-backup");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(backup, original);
        Files.writeString(settings,
                "{\"mcpServers\":{\"other\":{\"command\":\"other\"},\"kompile\":{\"command\":\"running-kompile\"}}}");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode registry = mapper.createObjectNode();
        String key = settings.getParent().toRealPath().resolve("settings.json").toString();
        ObjectNode record = registry.putObject(key);
        record.put("existed", true);
        record.putArray("holders").addObject().put("id", "running").put("pid", parent.pid())
                .put("started", parent.info().startInstant().get().toEpochMilli());
        Path registryFile = McpToolInjection.leaseRegistryDirectory().resolve(McpToolInjection.LEASE_REGISTRY_FILE);
        Files.createDirectories(registryFile.getParent());
        Files.writeString(registryFile, mapper.writeValueAsString(registry));
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            McpToolInjection.cleanupLeakedEntries(workDir);
            assertEquals("running-kompile",
                    mapper.readTree(settings.toFile()).path("mcpServers").path("kompile").path("command").asText());

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            McpToolInjection.removeTools(written);
            written = null;

            // The other process still runs with the server, and the original waits for its release.
            assertTrue(mapper.readTree(settings.toFile()).path("mcpServers").has("kompile"));
            assertEquals(original, Files.readString(backup));
            JsonNode holders = mapper.readTree(registryFile.toFile()).path(key).path("holders");
            assertEquals(1, holders.size(), holders.toString());
            assertEquals("running", holders.path(0).path("id").asText());
        } finally {
            McpToolInjection.removeTools(written);
            Files.deleteIfExists(registryFile);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void anOriginalWhoseRestoreFailedIsNotOverwrittenByTheNextLease() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("unrestored-work"));
        Path binary = createExecutable("unrestored-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        Path backup = settings.resolveSibling("settings.json.kompile-backup");
        // An earlier session could not restore this original, which is not valid JSON, and
        // left it in the backup with that session's server still in the file.
        String original = "{ \"mcpServers\": { \"other\": { \"command\": \"other\" } } oops\n";
        Files.writeString(backup, original);
        Files.writeString(settings, "{\"mcpServers\":{\"kompile\":{\"command\":\"earlier-kompile\"}}}");
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(settings, written);
            assertEquals(original, Files.readString(backup));
            assertEquals(binary.toString(), kompileCommand(settings), "the session still gets its tools");

            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(original, Files.readString(settings), "the original is put back as it was");
            assertEquals(List.of(), copiesBeside(settings));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void settingsWithCommentsAndTrailingCommasAreKeptAndRestoredAsTheyWere() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("jsonc-work"));
        Path binary = createExecutable("jsonc-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String original = "{\n"
                + "  // Qwen Code reads comments and trailing commas.\n"
                + "  \"mcpServers\": {\n"
                + "    \"other\": { \"command\": \"other\" },\n"
                + "  },\n"
                + "}\n";
        Files.writeString(settings, original);
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            JsonNode servers = new ObjectMapper().readTree(settings.toFile()).path("mcpServers");
            assertTrue(servers.has("other"), "the session keeps the file's own servers: " + servers);
            assertTrue(servers.has("kompile"), servers.toString());

            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(original, Files.readString(settings));
            assertEquals(List.of(), copiesBeside(settings));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void everyAgentOnASharedSettingsFileGetsItsToolsWhenTheFileDoesNotParse() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path binary = createExecutable("unparsed-lanes-kompile");
        // Cut off before its closing braces, as a half-saved edit is.
        String broken = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" }\n";
        Path agyConfig = McpToolInjection.agyMcpConfig();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;
            McpToolInjection.openCodeCrushFormat = false;

            for (String agent : List.of("qwen", "pi", "opencode", "agy")) {
                Path workDir = Files.createDirectories(tempDir.resolve("unparsed-" + agent + "-work"));
                Path settings = switch (agent) {
                    case "qwen" -> workDir.resolve(".qwen").resolve("settings.json");
                    case "pi" -> workDir.resolve(".pi").resolve("mcp.json");
                    case "opencode" -> workDir.resolve(".opencode.json");
                    default -> agyConfig;
                };
                Files.createDirectories(settings.getParent());
                Files.writeString(settings, broken);

                written = McpToolInjection.injectTools(workDir, agent, null);
                assertEquals(settings.toAbsolutePath().normalize(), written, agent);
                JsonNode servers = new ObjectMapper().readTree(settings.toFile()).path("mcpServers");
                assertEquals(1, servers.size(), agent + " runs with Kompile's server alone: " + servers);
                assertEquals(binary.toString(), kompileCommand(settings), agent);

                McpToolInjection.removeTools(written);
                written = null;
                assertEquals(broken, Files.readString(settings), agent);
                assertEquals(List.of(), copiesBeside(settings), agent);
            }
        } finally {
            McpToolInjection.removeTools(written);
            deleteWithCopies(agyConfig);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void geminiGetsItsServerAsTheSystemSettingsOfItsOwnLaunch() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("gemini-work"));
        Path binary = createExecutable("gemini-kompile");
        Path home = Path.of(System.getProperty("user.home"));
        // The system settings the launch's stand in for, with a server and a setting of their own.
        Path systemDirectory = Files.createDirectories(tempDir.resolve("gemini-system"));
        String system = "// managed\n{\"general\": {\"disableAutoUpdate\": true},"
                + " \"mcpServers\": {\"other\": {\"command\": \"other\"}}}";
        Path systemSettings = Files.writeString(systemDirectory.resolve("settings.json"), system);
        String derivedDefaults = systemDirectory.resolve("system-defaults.json").toString();
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());

            written = McpToolInjection.injectTools(workDir, "gemini", null, workerIdentity());
            assertTrue(McpToolInjection.isLaunchConfig(written), String.valueOf(written));
            assertEquals(McpToolInjection.launchConfigDirectory(), written.getParent());
            // It travels in the environment, not on the command line.
            assertEquals(List.of(), McpToolInjection.commandLineOverrides(workDir, "gemini", written));
            JsonNode kompile = mapper.readTree(written.toFile()).path("mcpServers").path("kompile");
            assertEquals(binary.toString(), kompile.path("command").asText(), kompile.toString());
            assertWorkerIdentity(kompile.path("env"));

            Map<String, String> environment = new LinkedHashMap<>();
            environment.put(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV, systemSettings.toString());
            // Empty, as unset, it leaves the system defaults beside the system settings.
            environment.put(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV, "");
            McpToolInjection.applyLaunchEnvironment(environment, written);
            assertEquals(written.toString(), environment.get(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV));
            assertEquals(derivedDefaults, environment.get(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV));
            JsonNode settings = mapper.readTree(written.toFile());
            assertTrue(settings.path("general").path("disableAutoUpdate").asBoolean(), settings.toString());
            assertEquals("other", settings.path("mcpServers").path("other").path("command").asText());
            assertEquals(kompile, settings.path("mcpServers").path("kompile"));
            assertEquals(system, Files.readString(systemSettings));

            // A relaunch applies it again and keeps system defaults the environment names.
            Map<String, String> relaunch = new LinkedHashMap<>();
            relaunch.put(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV, systemSettings.toString());
            relaunch.put(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV, "/opt/gemini/defaults.json");
            McpToolInjection.applyLaunchEnvironment(relaunch, written);
            assertEquals(written.toString(), relaunch.get(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV));
            assertEquals("/opt/gemini/defaults.json", relaunch.get(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV));
            assertEquals(settings, mapper.readTree(written.toFile()));

            // System settings that would stop Gemini CLI from starting give way to Kompile's server.
            String unparsed = "{\"mcpServers\": {";
            Files.writeString(systemSettings, unparsed);
            Map<String, String> broken = new LinkedHashMap<>();
            broken.put(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV, systemSettings.toString());
            McpToolInjection.applyLaunchEnvironment(broken, written);
            assertEquals(written.toString(), broken.get(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV));
            String inherited = System.getenv(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV);
            if (inherited == null || inherited.isBlank()) {
                assertEquals(derivedDefaults, broken.get(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV));
            }
            JsonNode alone = mapper.readTree(written.toFile());
            assertEquals(List.of("mcpServers"), fieldNames(alone));
            assertEquals(List.of("kompile"), fieldNames(alone.path("mcpServers")));
            assertEquals(kompile, alone.path("mcpServers").path("kompile"));
            assertEquals(unparsed, Files.readString(systemSettings));

            // With no system settings named, the launch's stand in for the platform's.
            if (System.getProperty("os.name", "").startsWith("Linux")) {
                assertEquals(Path.of("/etc/gemini-cli/settings.json"), McpToolInjection.geminiDefaultSystemSettings());
            }
            Map<String, String> platform = new LinkedHashMap<>();
            platform.put(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV, " ");
            platform.put(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV, "");
            McpToolInjection.applyLaunchEnvironment(platform, written);
            assertEquals(written.toString(), platform.get(McpToolInjection.GEMINI_SYSTEM_SETTINGS_ENV));
            assertEquals(McpToolInjection.geminiDefaultSystemSettings().resolveSibling("system-defaults.json").toString(),
                    platform.get(McpToolInjection.GEMINI_SYSTEM_DEFAULTS_ENV));

            // No settings file that other sessions read has changed.
            assertFalse(Files.exists(home.resolve(".gemini").resolve("settings.json")));
            assertFalse(Files.exists(home.resolve(".agy")));
            assertFalse(Files.exists(workDir.resolve(".gemini")));

            McpToolInjection.removeTools(written);
            assertFalse(Files.exists(written));
            written = null;

            written = McpToolInjection.injectTools(workDir, "gemini-cli", "http://localhost:8080/mcp/sse");
            assertTrue(McpToolInjection.isLaunchConfig(written), String.valueOf(written));
            JsonNode sse = mapper.readTree(written.toFile()).path("mcpServers").path("kompile");
            assertEquals("http://localhost:8080/mcp/sse", sse.path("url").asText(), sse.toString());
            // Without it Gemini CLI would take the URL for streamable HTTP.
            assertEquals("sse", sse.path("type").asText());
            assertFalse(sse.has("command"));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void antigravityGetsItsServerInTheConfigItReadsAndTheConfigBackAsItWas() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("agy-work"));
        Path binary = createExecutable("agy-kompile");
        Path home = Path.of(System.getProperty("user.home"));
        Path config = McpToolInjection.agyMcpConfig();
        assertEquals(home.resolve(".gemini").resolve("config").resolve("mcp_config.json"), config);
        // What Antigravity's own migration leaves behind.
        Files.createDirectories(config.getParent());
        Files.write(config, new byte[0]);
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());

            written = McpToolInjection.injectTools(workDir, "agy", null, workerIdentity());
            assertEquals(config.toAbsolutePath().normalize(), written);
            assertFalse(McpToolInjection.isLaunchConfig(written));
            assertEquals(List.of(), McpToolInjection.commandLineOverrides(workDir, "agy", written));
            JsonNode kompile = mapper.readTree(config.toFile()).path("mcpServers").path("kompile");
            assertEquals(binary.toString(), kompile.path("command").asText(), kompile.toString());
            assertWorkerIdentity(kompile.path("env"));
            assertFalse(Files.exists(home.resolve(".agy")), "Antigravity reads no ~/.agy/settings.json");

            McpToolInjection.removeTools(written);
            written = null;
            assertArrayEquals(new byte[0], Files.readAllBytes(config));
            assertEquals(List.of(), copiesBeside(config));

            written = McpToolInjection.injectTools(workDir, "antigravity", "http://localhost:8080/mcp/sse");
            assertEquals(config.toAbsolutePath().normalize(), written);
            JsonNode sse = mapper.readTree(config.toFile()).path("mcpServers").path("kompile");
            assertEquals(List.of("serverUrl"), fieldNames(sse), sse.toString());
            assertEquals("http://localhost:8080/mcp/sse", sse.path("serverUrl").asText());

            McpToolInjection.removeTools(written);
            written = null;
            assertArrayEquals(new byte[0], Files.readAllBytes(config));
            assertEquals(List.of(), copiesBeside(config));

            // The user's own servers stay beside Kompile's for the run.
            String withOther = "{\n  \"mcpServers\": {\n"
                    + "    \"other\": { \"serverUrl\": \"http://localhost:9000/sse\" }\n  }\n}\n";
            Files.writeString(config, withOther);
            written = McpToolInjection.injectTools(workDir, "agy", null, workerIdentity());
            JsonNode servers = mapper.readTree(config.toFile()).path("mcpServers");
            assertEquals("http://localhost:9000/sse", servers.path("other").path("serverUrl").asText(),
                    servers.toString());
            assertEquals(binary.toString(), servers.path("kompile").path("command").asText(), servers.toString());

            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(withOther, Files.readString(config));
            assertEquals(List.of(), copiesBeside(config));
        } finally {
            McpToolInjection.removeTools(written);
            deleteWithCopies(config);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aFileThatDoesNotParseRunsWithItsBackupsServersAndGetsItsBytesBack() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("unparsed-backup-work"));
        Path binary = createExecutable("unparsed-backup-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        Path backup = settings.resolveSibling("settings.json.kompile-backup");
        // The original of an earlier session, whose file no longer parses.
        String original = "{\"mcpServers\":{\"other\":{\"command\":\"other\"}}}";
        String broken = "{\n  \"mcpServers\": { \"mine\": { \"command\": \"mine\" }\n";
        Files.writeString(backup, original);
        Files.writeString(settings, broken);
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            JsonNode servers = new ObjectMapper().readTree(settings.toFile()).path("mcpServers");
            assertEquals("other", servers.path("other").path("command").asText(), servers.toString());
            assertEquals(binary.toString(), kompileCommand(settings));
            assertEquals(broken, Files.readString(settings.resolveSibling("settings.json.kompile-unparsed")));
            assertEquals(original, Files.readString(backup));

            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(broken, Files.readString(settings));
            assertEquals(original, Files.readString(backup));
            assertEquals(List.of("settings.json.kompile-backup"), copiesBeside(settings));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aSessionsChangesToAFileThatDidNotParseAreKeptAndItsBytesSaved() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("unparsed-edited-work"));
        Path binary = createExecutable("unparsed-edited-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String broken = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" }\n";
        Files.writeString(settings, broken);
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            addServer(settings, "mine");
            McpToolInjection.removeTools(written);
            written = null;

            assertEquals(mapper.readTree("{\"mine\":{\"command\":\"mine\"}}"),
                    mapper.readTree(settings.toFile()).path("mcpServers"));
            List<Path> saved = savedCopies(settings);
            assertEquals(1, saved.size(), copiesBeside(settings).toString());
            assertEquals(broken, Files.readString(saved.get(0)), "the file's own bytes are saved beside it");
            assertEquals(1, copiesBeside(settings).size(), copiesBeside(settings).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void anEditThatDoesNotParseIsKeptThroughTheNextSession() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("broken-edit-work"));
        Path binary = createExecutable("broken-edit-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        Path backup = settings.resolveSibling("settings.json.kompile-backup");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        String edit = "{\n  \"mcpServers\": { \"other\": { \"command\": \"edited\" }\n";
        Files.writeString(settings, original);
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            // The user saves an edit that does not parse yet.
            Files.writeString(settings, edit);
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(edit, Files.readString(settings), "the edit is not written over");
            assertEquals(original, Files.readString(backup));
            assertEquals(List.of("settings.json.kompile-backup"), copiesBeside(settings));

            // The next session still gets its tools, and the edit back when it ends.
            written = McpToolInjection.injectTools(workDir, "qwen", null);
            JsonNode servers = new ObjectMapper().readTree(settings.toFile()).path("mcpServers");
            assertEquals(binary.toString(), kompileCommand(settings));
            assertEquals("other", servers.path("other").path("command").asText(), servers.toString());
            assertEquals(edit, Files.readString(settings.resolveSibling("settings.json.kompile-unparsed")));

            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(edit, Files.readString(settings));
            assertEquals(original, Files.readString(backup));
            assertEquals(List.of("settings.json.kompile-backup"), copiesBeside(settings));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void settingsInAnyEncodingKeepTheirServersAndGetTheirBytesBack() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path binary = createExecutable("encodings-kompile");
        String json = "{\"mcpServers\":{\"other\":{\"command\":\"other\"}}}";
        Map<String, byte[]> originals = new LinkedHashMap<>();
        originals.put("utf8-bom", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                json.getBytes(StandardCharsets.UTF_8)));
        originals.put("utf16le-bom", concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                json.getBytes(StandardCharsets.UTF_16LE)));
        // Latin-1, which is not UTF-8: settings read with the byte replaced would not keep it.
        originals.put("latin1", concat(
                "{\"mcpServers\":{\"other\":{\"command\":\"caf".getBytes(StandardCharsets.US_ASCII),
                new byte[] {(byte) 0xE9},
                "\"}}}".getBytes(StandardCharsets.US_ASCII)));
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            for (Map.Entry<String, byte[]> original : originals.entrySet()) {
                String encoding = original.getKey();
                Path workDir = Files.createDirectories(tempDir.resolve(encoding + "-work"));
                Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
                Files.write(settings, original.getValue());

                written = McpToolInjection.injectTools(workDir, "qwen", null);
                JsonNode servers = new ObjectMapper().readTree(settings.toFile()).path("mcpServers");
                assertEquals(binary.toString(), kompileCommand(settings), encoding);
                // The Latin-1 file does not parse, so its session runs with Kompile's server alone.
                assertEquals("latin1".equals(encoding) ? 1 : 2, servers.size(), encoding + ": " + servers);

                McpToolInjection.removeTools(written);
                written = null;
                assertArrayEquals(original.getValue(), Files.readAllBytes(settings), encoding);
                assertEquals(List.of(), copiesBeside(settings), encoding);
            }
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aClaudeLaunchGetsItsToolsWhenTheProjectMcpJsonDoesNotParse() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("claude-unparsed-work"));
        Path binary = createExecutable("claude-unparsed-kompile");
        Path mcpJson = workDir.resolve(".mcp.json");
        String broken = "{\n  \"mcpServers\": { \"project-tools\": { \"command\": \"project-mcp\" }\n";
        Files.writeString(mcpJson, broken);
        ObjectMapper mapper = new ObjectMapper();
        Path launchConfig = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            launchConfig = McpToolInjection.injectTools(workDir, "claude", "http://localhost:8080/mcp/sse");

            assertEquals(McpToolInjection.launchConfigDirectory(), launchConfig.getParent());
            JsonNode kompile = mapper.readTree(launchConfig.toFile()).path("mcpServers").path("kompile");
            // Project servers it cannot read keep Kompile's on stdio, as custom ones do.
            assertEquals(binary.toString(), kompile.path("command").asText(), kompile.toString());
            assertFalse(kompile.has("url"));
            assertEquals("true", kompile.path("env").path(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV).asText());
            assertEquals(broken, Files.readString(mcpJson));
            assertEquals(List.of(), copiesBeside(mcpJson));
            Path localSettings = workDir.resolve(".claude").resolve("settings.local.json");
            assertTrue(Files.exists(localSettings), "Claude Code's hooks and allowed servers are still configured");
            List<String> allowed = new ArrayList<>();
            mapper.readTree(localSettings.toFile()).path("allowedMcpServers")
                    .forEach(entry -> allowed.add(entry.path("serverName").asText()));
            assertEquals(List.of("kompile"), allowed);

            McpToolInjection.removeTools(launchConfig);
            assertFalse(Files.exists(launchConfig));
            launchConfig = null;
            assertEquals(broken, Files.readString(mcpJson));
        } finally {
            McpToolInjection.removeTools(launchConfig);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void projectStartRegistersTheServerInAProjectMcpJsonThatDoesNotParse() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("project-start-unparsed-work"));
        Path binary = createExecutable("project-start-unparsed-kompile");
        Path mcpJson = workDir.resolve(".mcp.json");
        String broken = "{\n  \"mcpServers\": { \"project-tools\": { \"command\": \"project-mcp\" }\n";
        Files.writeString(mcpJson, broken);
        Path registration = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            registration = McpToolInjection.injectProjectMcpJson(workDir);
            assertEquals(mcpJson, registration);
            JsonNode kompile = new ObjectMapper().readTree(mcpJson.toFile()).path("mcpServers").path("kompile");
            assertEquals(binary.toString(), kompile.path("command").asText(), kompile.toString());
            assertEquals("true", kompile.path("env").path(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV).asText());

            McpToolInjection.removeTools(registration);
            registration = null;
            assertEquals(broken, Files.readString(mcpJson));
            assertEquals(List.of(), copiesBeside(mcpJson));
        } finally {
            McpToolInjection.removeTools(registration);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aSettingsFileThatIsASymbolicLinkStaysOne() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("symlink-work"));
        Path binary = createExecutable("symlink-kompile");
        Path target = Files.createDirectories(tempDir.resolve("dotfiles")).resolve("qwen-settings.json");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(target, original);
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        try {
            Files.createSymbolicLink(settings, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are unavailable here: " + e);
        }
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertTrue(Files.isSymbolicLink(settings));
            assertEquals(binary.toString(), kompileCommand(target));

            McpToolInjection.removeTools(written);
            written = null;
            assertTrue(Files.isSymbolicLink(settings), "a link into a dotfiles repository stays a link");
            assertEquals(original, Files.readString(target));
            assertEquals(List.of(), copiesBeside(settings));
            assertEquals(List.of(), copiesBeside(target));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void theCopiesOfASettingsFileKeepItsAttributes() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "needs POSIX file permissions");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path binary = createExecutable("permissions-kompile");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        String broken = "{\n  \"mcpServers\": { \"mine\": { \"command\": \"mine\" }\n";
        // Settings can hold tokens, so a file may be its owner's alone. The restore moves a copy
        // back into the file's place, so the copies keep its time as well as its mode.
        String ownerOnly = "rw-------";
        FileTime lastEdited = FileTime.from(Instant.parse("2024-01-02T03:04:05Z"));
        String kept = ownerOnly + " " + lastEdited;
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            // The backup, which the restore moves into the file's place.
            Path workDir = Files.createDirectories(tempDir.resolve("permissions-work"));
            Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
            Files.writeString(settings, original);
            Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString(ownerOnly));
            Files.setLastModifiedTime(settings, lastEdited);
            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(kept, attributes(settings.resolveSibling("settings.json.kompile-backup")));
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(original, Files.readString(settings));
            assertEquals(kept, attributes(settings));
            assertEquals(List.of(), copiesBeside(settings));

            // The bytes of a file that does not parse, which the restore moves into its place.
            Path unparsedWork = Files.createDirectories(tempDir.resolve("permissions-unparsed-work"));
            Path unparsed = Files.createDirectories(unparsedWork.resolve(".qwen")).resolve("settings.json");
            Files.writeString(unparsed.resolveSibling("settings.json.kompile-backup"), original);
            Files.writeString(unparsed, broken);
            Files.setPosixFilePermissions(unparsed, PosixFilePermissions.fromString(ownerOnly));
            Files.setLastModifiedTime(unparsed, lastEdited);
            written = McpToolInjection.injectTools(unparsedWork, "qwen", null);
            assertEquals(kept, attributes(unparsed.resolveSibling("settings.json.kompile-unparsed")));
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(broken, Files.readString(unparsed));
            assertEquals(kept, attributes(unparsed));
            assertEquals(List.of("settings.json.kompile-backup"), copiesBeside(unparsed));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aBackupWhoseFileIsGoneIsSavedBesideIt() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("orphan-backup-work"));
        Path binary = createExecutable("orphan-backup-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String orphan = "{\"mcpServers\":{\"other\":{\"command\":\"orphan\"}}}";
        Files.writeString(settings.resolveSibling("settings.json.kompile-backup"), orphan);
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(binary.toString(), kompileCommand(settings));
            McpToolInjection.removeTools(written);
            written = null;

            assertFalse(Files.exists(settings), "the file the session created is removed");
            List<Path> saved = savedCopies(settings);
            assertEquals(1, saved.size(), copiesBeside(settings).toString());
            assertEquals(orphan, Files.readString(saved.get(0)), "the last version of the file is not lost");
            assertEquals(1, copiesBeside(settings).size(), copiesBeside(settings).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void contentAfterTheSettingsObjectIsNotLost() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("trailing-work"));
        Path binary = createExecutable("trailing-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String original = "{\"mcpServers\":{\"other\":{\"command\":\"other\"}}}\n{\"second\": true}\n";
        Files.writeString(settings, original);
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(binary.toString(), kompileCommand(settings));
            addServer(settings, "mine");
            McpToolInjection.removeTools(written);
            written = null;

            // The session's server stays in the file, and the original, which no JSON written
            // back could hold, is saved beside it.
            assertEquals(mapper.readTree("{\"mine\":{\"command\":\"mine\"}}"),
                    mapper.readTree(settings.toFile()).path("mcpServers"));
            List<Path> saved = savedCopies(settings);
            assertEquals(1, saved.size(), copiesBeside(settings).toString());
            assertEquals(original, Files.readString(saved.get(0)));
            assertEquals(1, copiesBeside(settings).size(), copiesBeside(settings).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void anMcpServersValueThatIsNotAnObjectIsPutBack() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path binary = createExecutable("array-servers-kompile");
        String original = "{\"mcpServers\": [], \"theme\": \"dark\"}";
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            Path workDir = Files.createDirectories(tempDir.resolve("array-servers-work"));
            Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
            Files.writeString(settings, original);
            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(binary.toString(), kompileCommand(settings));
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(original, Files.readString(settings));
            assertEquals(List.of(), copiesBeside(settings));

            // A server added to the object that took its place keeps the object, so the
            // original is saved beside the file.
            Path editedWork = Files.createDirectories(tempDir.resolve("array-servers-edited-work"));
            Path edited = Files.createDirectories(editedWork.resolve(".qwen")).resolve("settings.json");
            Files.writeString(edited, original);
            written = McpToolInjection.injectTools(editedWork, "qwen", null);
            addServer(edited, "mine");
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(mapper.readTree("{\"mcpServers\":{\"mine\":{\"command\":\"mine\"}},\"theme\":\"dark\"}"),
                    mapper.readTree(edited.toFile()));
            List<Path> saved = savedCopies(edited);
            assertEquals(1, saved.size(), copiesBeside(edited).toString());
            assertEquals(original, Files.readString(saved.get(0)));
            assertEquals(1, copiesBeside(edited).size(), copiesBeside(edited).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void anEmptyMcpServersObjectIsPutBackAsItWas() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("empty-servers-work"));
        Path binary = createExecutable("empty-servers-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String original = "{\"mcpServers\": {}}";
        Files.writeString(settings, original);
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            assertEquals(binary.toString(), kompileCommand(settings));
            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(original, Files.readString(settings));
            assertEquals(List.of(), copiesBeside(settings));
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aCommentedOriginalTheSessionChangedIsSavedBesideTheFile() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("jsonc-edited-work"));
        Path binary = createExecutable("jsonc-edited-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String original = "{\n"
                + "  // Qwen Code reads comments and trailing commas.\n"
                + "  \"mcpServers\": {\n"
                + "    \"other\": { \"command\": \"other\" },\n"
                + "  },\n"
                + "}\n";
        Files.writeString(settings, original);
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            addServer(settings, "mine");
            McpToolInjection.removeTools(written);
            written = null;

            assertEquals(mapper.readTree("{\"other\":{\"command\":\"other\"},\"mine\":{\"command\":\"mine\"}}"),
                    mapper.readTree(settings.toFile()).path("mcpServers"));
            List<Path> saved = savedCopies(settings);
            assertEquals(1, saved.size(), copiesBeside(settings).toString());
            assertEquals(original, Files.readString(saved.get(0)), "the comments are kept in the saved copy");
            assertEquals(1, copiesBeside(settings).size(), copiesBeside(settings).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void aCommentedFileIsSavedBeforeItIsFirstWrittenOver() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("jsonc-stale-backup-work"));
        Path binary = createExecutable("jsonc-stale-backup-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        Path backup = settings.resolveSibling("settings.json.kompile-backup");
        // An earlier session's backup holds an older version, without the file's comments.
        Files.writeString(backup, "{\"mcpServers\":{\"other\":{\"command\":\"stale\"}}}");
        String commented = "{\n  // mine\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } },\n}\n";
        Files.writeString(settings, commented);
        ObjectMapper mapper = new ObjectMapper();
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            List<Path> saved = savedCopies(settings);
            assertEquals(1, saved.size(), copiesBeside(settings).toString());
            assertEquals(commented, Files.readString(saved.get(0)));
            assertEquals("other", mapper.readTree(settings.toFile())
                    .path("mcpServers").path("other").path("command").asText());

            McpToolInjection.removeTools(written);
            written = null;
            assertEquals(mapper.readTree("{\"mcpServers\":{\"other\":{\"command\":\"other\"}}}"),
                    mapper.readTree(settings.toFile()));
            assertFalse(Files.exists(backup));
            assertEquals(1, copiesBeside(settings).size(), copiesBeside(settings).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void commentsAddedDuringTheSessionAreSavedWhenTheOriginalIsPutBack() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("jsonc-note-work"));
        Path binary = createExecutable("jsonc-note-kompile");
        Path settings = Files.createDirectories(workDir.resolve(".qwen")).resolve("settings.json");
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(settings, original);
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            McpToolInjection.qwenMcpConfigSupport = false;

            written = McpToolInjection.injectTools(workDir, "qwen", null);
            Files.writeString(settings, "// user note\n" + Files.readString(settings));
            McpToolInjection.removeTools(written);
            written = null;

            assertEquals(original, Files.readString(settings));
            List<Path> saved = savedCopies(settings);
            assertEquals(1, saved.size(), copiesBeside(settings).toString());
            assertTrue(Files.readString(saved.get(0)).startsWith("// user note\n"), "the note is kept in the saved copy");
            assertEquals(1, copiesBeside(settings).size(), copiesBeside(settings).toString());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
    }

    @Test
    void launchConfigsOfExitedProcessesAreSweptAndNoOtherFile() throws Exception {
        long pid = ProcessHandle.current().pid();
        assertEquals(pid, McpToolInjection.launchConfigOwner(Path.of("kompile-mcp-claude-" + pid + "-42.json")));
        assertEquals(-1, McpToolInjection.launchConfigOwner(Path.of("kompile-mcp-codex-42.json")));
        assertEquals(-1, McpToolInjection.launchConfigOwner(Path.of("kompile-mcp-qwen-pid-42.json")));
        assertEquals(-1, McpToolInjection.launchConfigOwner(Path.of(".mcp.json")));

        Path directory = Files.createDirectories(tempDir.resolve("launch-configs"));
        // Above any pid Linux hands out.
        Path exited = Files.writeString(directory.resolve("kompile-mcp-claude-2000000000-1.json"), "{}");
        Path running = Files.writeString(directory.resolve("kompile-mcp-opencode-" + pid + "-2.json"), "{}");
        Path unnumbered = Files.writeString(directory.resolve("kompile-mcp-qwen-3.json"), "{}");
        Path unknown = Files.writeString(directory.resolve("kompile-mcp-kimi-2000000000-4.json"), "{}");
        Path exitedGemini = Files.writeString(directory.resolve("kompile-mcp-gemini-2000000000-5.json"), "{}");

        McpToolInjection.sweepLaunchConfigs(directory);

        assertFalse(Files.exists(exited));
        assertFalse(Files.exists(exitedGemini));
        assertTrue(Files.exists(running));
        assertTrue(Files.exists(unnumbered));
        assertTrue(Files.exists(unknown), "a file of no Kompile launch is not the sweep's to delete");
    }

    private static void assertWorkerIdentity(JsonNode environment) {
        assertEquals("wf", environment.path(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME).asText(), environment.toString());
        assertEquals("worker", environment.path(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT).asText());
        assertEquals("s1", environment.path(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION).asText());
    }

    /** The launch environment of a delegate run as participant {@code worker} of workflow {@code wf}. */
    private static Map<String, String> workerIdentity() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, "wf");
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT, "worker");
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION, "s1");
        return env;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private Path createExecutable(String name) throws Exception {
        Path binary = Files.createFile(tempDir.resolve(name)).toAbsolutePath();
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    /** What a user does during a session: add a server of their own to the settings file. */
    private static void addServer(Path settingsFile, String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(settingsFile.toFile());
        ((ObjectNode) root.get("mcpServers")).putObject(name).put("command", name);
        Files.writeString(settingsFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /** The names of the backups and saved copies beside {@code settingsFile}. */
    private static List<String> copiesBeside(Path settingsFile) throws Exception {
        String prefix = settingsFile.getFileName() + ".kompile-";
        try (Stream<Path> siblings = Files.list(settingsFile.getParent())) {
            return siblings.map(sibling -> sibling.getFileName().toString())
                    .filter(name -> name.startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    /** The earlier versions of {@code settingsFile} saved beside it. */
    private static List<Path> savedCopies(Path settingsFile) throws Exception {
        String prefix = settingsFile.getFileName() + ".kompile-saved-";
        try (Stream<Path> siblings = Files.list(settingsFile.getParent())) {
            return siblings.filter(sibling -> sibling.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    /** Delete {@code settingsFile} and the backups and saved copies beside it. */
    private static void deleteWithCopies(Path settingsFile) throws Exception {
        if (!Files.isDirectory(settingsFile.getParent())) {
            return;
        }
        Files.deleteIfExists(settingsFile);
        for (String copy : copiesBeside(settingsFile)) {
            Files.deleteIfExists(settingsFile.resolveSibling(copy));
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String kompileCommand(Path settingsFile) throws Exception {
        return new ObjectMapper().readTree(settingsFile.toFile())
                .path("mcpServers").path("kompile").path("command").asText();
    }

    /** A file's permissions and when it was last modified. */
    private static String attributes(Path file) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file))
                + " " + Files.getLastModifiedTime(file);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }
}
