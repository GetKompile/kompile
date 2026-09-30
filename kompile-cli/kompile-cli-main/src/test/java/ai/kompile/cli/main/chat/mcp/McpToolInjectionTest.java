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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP config injection for delegated CLI agents. {@link McpToolInjection#injectTools}
 * first cleans leaked Kompile entries out of agent configs under the user's home
 * (Codex, Gemini, OpenCode), so the class runs in a temporary one.
 */
@TemporaryUserHome
class McpToolInjectionTest {

    @TempDir
    Path tempDir;

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
            // registration looks like.
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
        String previousHome = System.getProperty("user.home");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path home = tempDir.resolve("home");
        Path workDir = tempDir.resolve("work");
        Path binary = createExecutable("kompile-cli");
        Files.createDirectories(home);
        Files.createDirectories(workDir);

        Path configFile = home.resolve(".codex").resolve("config.toml");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("kompile.cli.binary", binary.toString());

            Path written = McpToolInjection.injectTools(
                    workDir, "codex", "http://localhost:8080/mcp/sse");

            assertTrue(Files.exists(written));
            String config = Files.readString(configFile);
            assertTrue(config.contains("[mcp_servers.kompile]"));
            assertTrue(config.contains("command = \"" + binary + "\""));
            assertTrue(config.contains("\"mcp-stdio\""));
            assertFalse(config.contains("url = "));
            assertFalse(config.contains("/mcp/sse"));
        } finally {
            McpToolInjection.removeTools(configFile);
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
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            configFile = McpToolInjection.injectTools(
                    workDir, "codex", "http://localhost:8080/mcp/sse", workerIdentity());

            String toml = Files.readString(configFile);
            assertTrue(toml.contains("\n[mcp_servers.kompile.env]\n"
                    + WorkflowTeamEnforcement.ENV_WORKFLOW_NAME + " = \"wf\"\n"
                    + WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT + " = \"worker\"\n"
                    + WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION + " = \"s1\"\n"), toml);

            List<String> overrides = McpToolInjection.codexCommandLineOverrides(workDir, workerIdentity());
            assertEquals(List.of(
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_NAME + "=\"wf\"",
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT + "=\"worker\"",
                    "-c", "mcp_servers.kompile.env." + WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION + "=\"s1\""),
                    overrides.subList(4, overrides.size()));
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
        Path written = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            written = McpToolInjection.injectTools(
                    workDir, "opencode", "http://localhost:8080/mcp/sse", workerIdentity());

            // OpenCode 1.x reads opencode.json's "mcp" entries and their "environment";
            // the legacy .opencode.json reads "mcpServers" and "env".
            boolean current = written.getFileName().toString().equals("opencode.json");
            JsonNode kompile = new ObjectMapper().readTree(written.toFile())
                    .path(current ? "mcp" : "mcpServers").path("kompile");
            assertFalse(kompile.has("url"));
            JsonNode env = kompile.path(current ? "environment" : "env");
            assertEquals("wf", env.path(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME).asText());
            assertEquals("worker", env.path(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT).asText());
            assertEquals("s1", env.path(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION).asText());
        } finally {
            McpToolInjection.removeTools(written);
            restoreProperty("kompile.cli.binary", previousBinary);
        }
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
}
