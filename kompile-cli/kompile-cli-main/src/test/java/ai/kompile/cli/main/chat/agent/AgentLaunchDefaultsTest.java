/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.configure.ConfigureCommand;
import ai.kompile.cli.main.chat.roles.RoleAgentDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLaunchDefaultsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesModelSpecificThinkingBeforeAgentFallback() throws Exception {
        Path config = AgentLaunchDefaults.projectConfigPath(tempDir);
        AgentLaunchDefaults.save(config, "codex", "gpt-5.3-codex", "medium", null);
        AgentLaunchDefaults.save(config, "codex", null, "xhigh", "gpt-5.3-codex");

        AgentLaunchDefaults.Selection selected = AgentLaunchDefaults.resolve(
                "codex", tempDir.resolve("nested"), null, null);
        assertEquals("gpt-5.3-codex", selected.model());
        assertEquals("xhigh", selected.thinking());

        AgentLaunchDefaults.Selection otherModel = AgentLaunchDefaults.resolve(
                "codex", tempDir, "gpt-5.2-codex", null);
        assertEquals("gpt-5.2-codex", otherModel.model());
        assertEquals("medium", otherModel.thinking());
    }

    @Test
    void keepsDefaultsIsolatedByAgentAndExplicitValuesWin() throws Exception {
        Path config = AgentLaunchDefaults.projectConfigPath(tempDir);
        AgentLaunchDefaults.save(config, "codex", "gpt-codex", "high", "gpt-codex");
        AgentLaunchDefaults.save(config, "claude", "claude-opus", "max", "claude-opus");
        AgentLaunchDefaults.save(config, "opencode", "openai/gpt", "low", "openai/gpt");

        assertEquals("gpt-codex",
                AgentLaunchDefaults.resolve("codex", tempDir, null, null).model());
        assertEquals("claude-opus",
                AgentLaunchDefaults.resolve("claude", tempDir, null, null).model());
        assertEquals("openai/gpt",
                AgentLaunchDefaults.resolve("opencode", tempDir, null, null).model());

        AgentLaunchDefaults.Selection explicit = AgentLaunchDefaults.resolve(
                "claude", tempDir, "claude-sonnet", "xhigh");
        assertEquals("claude-sonnet", explicit.model());
        assertEquals("xhigh", explicit.thinking());
    }

    @Test
    void legacyCodexUltraThinkingNormalizesToMax() {
        AgentLaunchDefaults.Selection selected = AgentLaunchDefaults.resolve(
                "codex", tempDir, "gpt-5.6-sol", "ultra");

        assertEquals("max", selected.thinking());
    }

    @Test
    void roleDefaultsPrecedeProjectDefaultsForModelAndThinking() throws Exception {
        Path config = AgentLaunchDefaults.projectConfigPath(tempDir);
        AgentLaunchDefaults.save(config, "codex", "project-model", "low", null);
        AgentLaunchDefaults.save(config, "codex", null, "high", "role-model");

        RoleAgentDefaults roleDefaults = new RoleAgentDefaults(
                "role-model", "medium", Map.of("role-model", "max"));
        AgentLaunchDefaults.Selection selected = AgentLaunchDefaults.resolve(
                "codex", tempDir, null, null, roleDefaults);

        assertEquals("role-model", selected.model());
        assertEquals("max", selected.thinking());
    }

    @Test
    void explicitModelStillSelectsRoleThinkingAndExplicitThinkingWins() {
        RoleAgentDefaults roleDefaults = new RoleAgentDefaults(
                "gpt-5.6-terra", "medium", Map.of("gpt-5.6-sol", "max"));

        AgentLaunchDefaults.Selection selected = AgentLaunchDefaults.resolve(
                "codex", tempDir, "gpt-5.6-sol", null, roleDefaults);
        assertEquals("gpt-5.6-sol", selected.model());
        assertEquals("max", selected.thinking());

        AgentLaunchDefaults.Selection explicit = AgentLaunchDefaults.resolve(
                "codex", tempDir, "gpt-5.6-sol", "low", roleDefaults);
        assertEquals("low", explicit.thinking());
    }

    @Test
    void concurrentSavesPreserveDifferentAgents() {
        Path config = AgentLaunchDefaults.projectConfigPath(tempDir);
        CompletableFuture<Void> codex = CompletableFuture.runAsync(() ->
                saveUnchecked(config, "codex", "gpt-codex", "high"));
        CompletableFuture<Void> claude = CompletableFuture.runAsync(() ->
                saveUnchecked(config, "claude", "claude-opus", "max"));
        CompletableFuture.allOf(codex, claude).join();

        assertEquals("gpt-codex",
                AgentLaunchDefaults.resolve("codex", tempDir, null, null).model());
        assertEquals("claude-opus",
                AgentLaunchDefaults.resolve("claude", tempDir, null, null).model());
    }

    @Test
    void crossProcessSavesPreserveDifferentAgents() throws Exception {
        Path config = AgentLaunchDefaults.projectConfigPath(tempDir);
        Process codex = startSaveProcess(config, "codex", "gpt-codex", "high");
        Process claude = startSaveProcess(config, "claude", "claude-opus", "max");

        assertProcessSucceeded(codex);
        assertProcessSucceeded(claude);

        assertEquals("gpt-codex-39",
                AgentLaunchDefaults.resolve("codex", tempDir, null, null).model());
        assertEquals("claude-opus-39",
                AgentLaunchDefaults.resolve("claude", tempDir, null, null).model());
    }

    @Test
    void malformedProjectConfigFailsInsteadOfSilentlyFallingBack() throws Exception {
        Path config = AgentLaunchDefaults.projectConfigPath(tempDir);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{not-json");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentLaunchDefaults.resolve("codex", tempDir, null, null));
        assertTrue(error.getMessage().contains(config.toString()), error.getMessage());
    }

    @Test
    void configureCommandPersistsModelSpecificThinking() {
        int exitCode = new CommandLine(new ConfigureCommand.AgentDefaultsConfigureCommand())
                .execute("--agent", "claude",
                        "--project-dir", tempDir.toString(),
                        "--model", "claude-opus",
                        "--thinking", "max");

        assertEquals(0, exitCode);
        AgentLaunchDefaults.Selection selected = AgentLaunchDefaults.resolve(
                "claude", tempDir, null, null);
        assertEquals("claude-opus", selected.model());
        assertEquals("max", selected.thinking());
    }

    @Test
    void emitsProviderNativeManagedArguments() {
        assertEquals(List.of(
                        "--model", "gpt-5.3-codex",
                        "-c", "model_reasoning_effort=\"xhigh\""),
                AgentLaunchDefaults.commandArguments(
                        "codex", "gpt-5.3-codex", "xhigh",
                        AgentLaunchDefaults.LaunchMode.MANAGED));

        assertEquals(List.of(
                        "--model", "claude-opus",
                        "--effort", "max"),
                AgentLaunchDefaults.commandArguments(
                        "claude", "claude-opus", "max",
                        AgentLaunchDefaults.LaunchMode.MANAGED));

        assertEquals(List.of(
                        "--model", "openai/gpt",
                        "--variant", "high"),
                AgentLaunchDefaults.commandArguments(
                        "opencode", "openai/gpt", "high",
                        AgentLaunchDefaults.LaunchMode.MANAGED));
    }

    @Test
    void emitsProviderNativeResumeArguments() {
        assertEquals(List.of("resume", "session-id"),
                AgentLaunchDefaults.resumeArguments("codex", "session-id"));
        assertEquals(List.of("--resume", "session-id"),
                AgentLaunchDefaults.resumeArguments("claude", "session-id"));
        assertEquals(List.of("--resume", "session-id"),
                AgentLaunchDefaults.resumeArguments("qwen", "session-id"));
        assertEquals(List.of("--resume", "session-id"),
                AgentLaunchDefaults.resumeArguments("gemini", "session-id"));
        assertEquals(List.of("--session", "session-id"),
                AgentLaunchDefaults.resumeArguments("opencode", "session-id"));
        assertEquals(List.of("--session", "session-id"),
                AgentLaunchDefaults.resumeArguments("pi", "session-id"));
        assertTrue(AgentLaunchDefaults.resumeArguments("unknown", "session-id").isEmpty());
    }

    @Test
    void rejectsControlCharactersInProviderArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentLaunchDefaults.commandArguments(
                        "codex", "gpt-5.6", "medium\nmodel=\"unexpected\"",
                        AgentLaunchDefaults.LaunchMode.MANAGED));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLaunchDefaults.commandArguments(
                        "claude", "claude-opus\rnext", "high",
                        AgentLaunchDefaults.LaunchMode.MANAGED));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLaunchDefaults.resumeArguments("codex", "session\nresume unexpected"));
    }

    private static void saveUnchecked(Path config, String agent, String model, String thinking) {
        try {
            AgentLaunchDefaults.save(config, agent, model, thinking, model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Process startSaveProcess(Path config, String agent, String model, String thinking)
            throws Exception {
        String javaBinary = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe" : "java";
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", javaBinary).toString(),
                "-cp", System.getProperty("java.class.path"),
                SaveProcess.class.getName(), config.toString(), agent, model, thinking)
                .redirectErrorStream(true)
                .start();
    }

    private static void assertProcessSucceeded(Process process) throws Exception {
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "Agent defaults save process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    public static final class SaveProcess {
        private SaveProcess() {
        }

        public static void main(String[] args) throws Exception {
            Path config = Path.of(args[0]);
            String agent = args[1];
            String model = args[2];
            String thinking = args[3];
            for (int i = 0; i < 40; i++) {
                String iterationModel = model + "-" + i;
                AgentLaunchDefaults.save(config, agent, iterationModel, thinking, iterationModel);
            }
        }
    }

    @Test
    void omitsOpenCodeVariantForInteractiveTui() {
        assertEquals(List.of("--model", "openai/gpt"),
                AgentLaunchDefaults.commandArguments(
                        "opencode", "openai/gpt", "high",
                        AgentLaunchDefaults.LaunchMode.INTERACTIVE));

        AgentLaunchDefaults.Selection unsupported = AgentLaunchDefaults.resolve(
                "qwen", tempDir, null, null);
        assertNull(unsupported.model());
        assertNull(unsupported.thinking());
    }
}
