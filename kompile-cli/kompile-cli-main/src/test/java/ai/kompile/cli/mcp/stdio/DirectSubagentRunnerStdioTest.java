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
package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleAgentDefaults;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class DirectSubagentRunnerStdioTest {

    @TempDir
    Path tempDir;

    @Test
    void codexMcpOverridesPrecedeExecSubcommand() {
        List<String> base = SubprocessAgentRunner.buildManagedCommand(
                "codex", "/tmp/codex", "review", false, null,
                false, tempDir, null, "gpt-5.3-codex", "xhigh");

        List<String> command = SubprocessAgentRunner.prependGlobalOptions(
                base, List.of("-c", "mcp_servers.kompile.command=\"/tmp/kompile\""));

        assertEquals(List.of(
                "/tmp/codex",
                "-c",
                "mcp_servers.kompile.command=\"/tmp/kompile\"",
                "--model",
                "gpt-5.3-codex",
                "-c",
                "model_reasoning_effort=\"xhigh\"",
                "exec",
                "--json",
                "review"), command);
    }

    @Test
    void managedCommandsMapClaudeAndOpenCodeThinking() {
        assertEquals(List.of(
                        "/tmp/claude",
                        "--model", "claude-opus",
                        "--effort", "max",
                        "-p", "review",
                        "--output-format", "stream-json",
                        "--verbose"),
                SubprocessAgentRunner.buildManagedCommand(
                        "claude", "/tmp/claude", "review", false, null,
                        false, tempDir, null, "claude-opus", "max"));

        assertEquals(List.of(
                        "/tmp/opencode",
                        "run",
                        "--model", "openai/gpt",
                        "--variant", "high",
                        "--format", "json",
                        "review"),
                SubprocessAgentRunner.buildManagedCommand(
                        "opencode", "/tmp/opencode", "review", false, null,
                        false, tempDir, null, "openai/gpt", "high"));
    }

    @Test
    void deepSeekHarnessUsesTheOfficialOneShotHeadlessContract() {
        assertTrue(SubprocessAgentRunner.requiresManagedOneShot("dsh"));
        assertTrue(SubprocessAgentRunner.requiresManagedOneShot("DeepSeek Harness"));
        assertFalse(SubprocessAgentRunner.requiresManagedOneShot("codex"));

        assertEquals(List.of("/tmp/dsh", "--profile", "headless", "review"),
                SubprocessAgentRunner.buildManagedCommand(
                        "dsh", "/tmp/dsh", "review", false, null,
                        false, tempDir, null, "undocumented-model", "undocumented-effort"));
        assertEquals(List.of("/tmp/dsh", "--profile", "headless", "continue"),
                SubprocessAgentRunner.buildManagedCommand(
                        "dsh", "/tmp/dsh", "continue", true, "old-session",
                        false, tempDir, null, null, null));
    }

    @Test
    void managedCommandsKeepFullPermissionsEnabled() {
        List<String> codex = SubprocessAgentRunner.buildManagedCommand(
                "codex", "/tmp/codex", "implement", false, null, true, tempDir, null,
                "gpt-5.6-sol", "xhigh");
        assertTrue(codex.contains("--dangerously-bypass-approvals-and-sandbox"));
        assertFalse(codex.contains("read-only"));
        assertFalse(codex.contains("--ephemeral"));

        List<String> resumedCodex = SubprocessAgentRunner.buildManagedCommand(
                "codex", "/tmp/codex", "continue", true, "session-123", true, tempDir, null,
                "gpt-5.6-sol", "xhigh");
        int resumedExecIndex = resumedCodex.indexOf("exec");
        assertTrue(resumedExecIndex >= 0, resumedCodex.toString());
        assertTrue(resumedExecIndex < resumedCodex.indexOf("resume"), resumedCodex.toString());
        assertTrue(resumedCodex.contains("--dangerously-bypass-approvals-and-sandbox"));

        List<String> claude = SubprocessAgentRunner.buildManagedCommand(
                "claude", "/tmp/claude", "implement", false, null, true, tempDir, null,
                "gpt", "xhigh");
        assertTrue(claude.contains("--dangerously-skip-permissions"));
        assertFalse(claude.contains("--disallowedTools"));

        List<String> opencode = SubprocessAgentRunner.buildManagedCommand(
                "opencode", "/tmp/opencode", "implement", false, null, true, tempDir, null,
                "gpt", "xhigh");
        assertFalse(opencode.containsAll(List.of("--agent", "plan")));
    }

    @Test
    void exactResumeModeNeverUsesProviderGlobalLatestSession() throws Exception {
        TerminalRenderer renderer = new TerminalRenderer(false);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "codex", tempDir.toString(), true, false, "", 0,
                null, renderer, new AsciiRenderer(renderer, 100));
        runner.setExactResumeRequired(true);

        Field firstMessageSent = SubprocessAgentRunner.class.getDeclaredField("firstMessageSent");
        firstMessageSent.setAccessible(true);
        Method buildCommand = SubprocessAgentRunner.class.getDeclaredMethod(
                "buildCommand", String.class, String.class);
        buildCommand.setAccessible(true);

        for (String provider : List.of("claude", "codex", "gemini", "qwen", "opencode", "pi")) {
            runner.setAgent(provider);
            firstMessageSent.set(runner, true);
            @SuppressWarnings("unchecked")
            List<String> command = (List<String>) buildCommand.invoke(runner, provider, "second message");
            assertFalse(command.contains("--continue"), provider + ": " + command);
            assertFalse(command.contains("--last"), provider + ": " + command);
            assertFalse(command.contains("resume"), provider + ": " + command);
            assertFalse(command.contains("--resume"), provider + ": " + command);
            assertFalse(command.contains("--session"), provider + ": " + command);
            assertTrue(command.contains("second message"), provider + ": " + command);
        }
    }

    @Test
    void runSubagentUsesManagedRunnerAndCapturesOutput() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.setExtraEnvironment(Map.of("KOMPILE_TEST_FORK_ENV", "forked-value"));

        String result = runner.runSubagent(AgentConfig.builder("codex")
                .modelOverride("gpt-5.3-codex")
                .thinkingOverride("high")
                .build(), "inspect managed tools");

        FakeManagedRunner fake = runner.fake;
        assertNotNull(fake, "subagent should create the managed passthrough runner");
        assertEquals("codex", fake.agentName);
        assertTrue(fake.injectMcpTools, "top-level delegated agents should receive MCP tool injection");
        assertTrue(fake.injectMcpToolsCalled, "MCP tool injection should be performed by the managed runner");
        assertTrue(fake.cleanupCalled, "managed runner cleanup should restore injected tools/skills");
        assertEquals("inspect managed tools", fake.message);
        assertEquals("1", fake.extraEnvironment.get("KOMPILE_SUBAGENT_DEPTH"));
        assertEquals("codex", fake.extraEnvironment.get("KOMPILE_AGENT_NAME"));
        assertEquals("forked-value", fake.extraEnvironment.get("KOMPILE_TEST_FORK_ENV"));
        assertEquals("gpt-5.3-codex", fake.modelOverride);
        assertEquals("high", fake.thinkingOverride);
        assertTrue(result.contains("Subagent 'codex' completed"));
        assertTrue(result.contains("managed-output"));
        assertTrue(result.contains("Full output"));
    }

    @Test
    void baseCoordinationEnvironmentSurvivesTemporaryPolicyOverlayReset() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.setBaseEnvironment(Map.of("KOMPILE_PARENT_SESSION_ID", "parent-session"));
        runner.setExtraEnvironment(Map.of("KOMPILE_POLICY", "strict"));

        runner.runSubagent(AgentConfig.builder("codex").build(), "first task");
        assertEquals("parent-session", runner.fake.extraEnvironment.get("KOMPILE_PARENT_SESSION_ID"));
        assertEquals("strict", runner.fake.extraEnvironment.get("KOMPILE_POLICY"));

        runner.setExtraEnvironment(Map.of());
        runner.runSubagent(AgentConfig.builder("codex").build(), "second task");
        assertEquals("parent-session", runner.fake.extraEnvironment.get("KOMPILE_PARENT_SESSION_ID"));
        assertFalse(runner.fake.extraEnvironment.containsKey("KOMPILE_POLICY"));
    }

    @Test
    void persistedAgentRoleSuppliesPromptModelAndModelSpecificThinking() throws Exception {
        RoleConfig doer = RoleConfig.builder()
                .name("doer")
                .displayName("Focused Doer")
                .description("Implements bounded changes")
                .systemPrompt("Work through the implementation carefully.")
                .agentDefaults(Map.of(
                        "codex", new RoleAgentDefaults(
                                "gpt-5.6-terra", "medium",
                                Map.of("gpt-5.6-sol", "max"))))
                .build();
        RoleManager roleManager = new TestRoleManager(tempDir, doer, "doer");
        ManagedTestRunner runner = new ManagedTestRunner(tempDir, roleManager);

        runner.runSubagent(AgentConfig.builder("codex")
                .modelOverride("gpt-5.6-sol")
                .build(), "implement the focused change");

        assertTrue(runner.fake.message.contains("# Role: Focused Doer"));
        assertTrue(runner.fake.message.contains("implement the focused change"));
        assertEquals("gpt-5.6-sol", runner.fake.modelOverride);
        assertEquals("max", runner.fake.thinkingOverride);
    }

    @Test
    void explicitTaskRoleOverridesPersistedAgentRole() throws Exception {
        RoleConfig assigned = RoleConfig.builder()
                .name("assigned")
                .displayName("Assigned Role")
                .description("Persisted fallback")
                .systemPrompt("Use the assigned role.")
                .agentDefaults(Map.of(
                        "codex", new RoleAgentDefaults(
                                "gpt-5.6-terra", "low", Map.of())))
                .build();
        RoleConfig explicit = RoleConfig.builder()
                .name("explicit")
                .displayName("Explicit Role")
                .description("Task-selected role")
                .systemPrompt("Use the explicit role.")
                .agentDefaults(Map.of(
                        "codex", new RoleAgentDefaults(
                                "gpt-5.6-sol", "max", Map.of())))
                .build();
        RoleManager roleManager = new TestRoleManager(
                tempDir, Map.of("assigned", assigned, "explicit", explicit), "assigned");
        ManagedTestRunner runner = new ManagedTestRunner(tempDir, roleManager);

        runner.runSubagent(AgentConfig.builder("codex")
                .roleName("explicit")
                .build(), "review the integration");

        assertTrue(runner.fake.message.contains("# Role: Explicit Role"));
        assertFalse(runner.fake.message.contains("# Role: Assigned Role"));
        assertEquals("gpt-5.6-sol", runner.fake.modelOverride);
        assertEquals("max", runner.fake.thinkingOverride);
    }

    @Test
    void forkedRunnerStillCarriesManagedEnvironmentIntoManagedRunner() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.setExtraEnvironment(Map.of("KOMPILE_TEST_FORK_ENV", "forked-value"));

        ManagedTestRunner fork = (ManagedTestRunner) runner.forkForSubagent();
        String result = fork.runSubagent(AgentConfig.builder("opencode").build(), "delegate checks");

        assertTrue(result.contains("managed-output"));
        assertEquals("forked-value", fork.fake.extraEnvironment.get("KOMPILE_TEST_FORK_ENV"));
    }

    @Test
    void architectRoleKeepsPromptAndDefaultsWithoutRestrictingTools() throws Exception {
        RoleConfig architect = RoleConfig.builder()
                .name("architect")
                .displayName("Software Architect")
                .description("one bounded plan")
                .systemPrompt("Return one plan.")
                .enabledTools(Set.of("read", "grep"))
                .canSpawnSubagents(false)
                .agentDefaults(Map.of("codex", new RoleAgentDefaults("gpt-5.6-sol", "xhigh", Map.of())))
                .build();
        ManagedTestRunner runner = new ManagedTestRunner(
                tempDir, new TestRoleManager(tempDir, architect, null));

        String result = runner.runSubagent(AgentConfig.builder("codex")
                .roleName("architect")
                .build(), "design one thing");

        assertTrue(runner.fake.isSkipPermissions());
        assertFalse(runner.fake.extraEnvironment.containsKey("KOMPILE_SUBAGENT_CAN_SPAWN"));
        assertFalse(runner.fake.extraEnvironment.containsKey("KOMPILE_SUBAGENT_TOOL_ALLOWLIST"));
        assertTrue(runner.fake.message.contains("Return one plan."));
        assertTrue(result.contains("policy=FULL_ACCESS"));
    }

    @Test
    void unknownExplicitRoleFailsClosedBeforeLaunch() {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runner.runSubagent(AgentConfig.builder("codex")
                        .roleName("does-not-exist")
                        .build(), "do work"));

        assertTrue(error.getMessage().contains("Unknown role"));
        assertNull(runner.fake);
    }

    @Test
    void cancelForwardsToCurrentManagedRunner() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.blockRunMessage = true;

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.runSubagent(AgentConfig.builder("codex").build(), "long task");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(runner.runnerCreated.await(5, TimeUnit.SECONDS), "managed runner should be created");
        assertTrue(runner.fake.runStarted.await(5, TimeUnit.SECONDS), "managed runner should start its turn");

        runner.cancel();

        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(runner.fake.cancelCalled, "cancel should be forwarded to the managed runner");
        assertTrue(runner.fake.cleanupCalled, "cleanup should still run after cancellation");
    }

    static final class ManagedTestRunner extends DirectSubagentRunnerStdio {
        private final Path testWorkDir;
        private final RoleManager testRoleManager;
        final CountDownLatch runnerCreated = new CountDownLatch(1);
        volatile FakeManagedRunner fake;
        volatile boolean blockRunMessage;

        ManagedTestRunner(Path workDir) {
            this(workDir, new TestRoleManager(workDir, Map.of(), null));
        }

        ManagedTestRunner(Path workDir, RoleManager roleManager) {
            super(workDir, roleManager);
            this.testWorkDir = workDir;
            this.testRoleManager = roleManager;
        }

        @Override
        DirectSubagentRunnerStdio forkForSubagent() {
            ManagedTestRunner fork = new ManagedTestRunner(testWorkDir, testRoleManager);
            fork.setExtraEnvironment(Map.of("KOMPILE_TEST_FORK_ENV", "forked-value"));
            return fork;
        }

        @Override
        SubprocessAgentRunner createManagedRunner(String agentName, boolean injectMcpTools) {
            fake = new FakeManagedRunner(agentName, testWorkDir, injectMcpTools, () -> blockRunMessage);
            runnerCreated.countDown();
            return fake;
        }
    }

    static final class TestRoleManager extends RoleManager {
        private final Map<String, RoleConfig> roles;
        private final String assignedRole;

        TestRoleManager(Path workDir, RoleConfig role, String assignedRole) {
            this(workDir, role != null ? Map.of(role.getName(), role) : Map.of(), assignedRole);
        }

        TestRoleManager(Path workDir, Map<String, RoleConfig> roles, String assignedRole) {
            super(workDir);
            this.roles = roles;
            this.assignedRole = assignedRole;
        }

        @Override
        public RoleConfig getRole(String name) {
            return roles.get(name);
        }

        @Override
        public String getAgentRole(String agentName) {
            return assignedRole;
        }
    }

    static final class FakeManagedRunner extends SubprocessAgentRunner {
        final String agentName;
        final boolean injectMcpTools;
        final java.util.function.BooleanSupplier blockRunMessage;
        final CountDownLatch runStarted = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile boolean injectMcpToolsCalled;
        volatile boolean cleanupCalled;
        volatile boolean cancelCalled;
        volatile String message;
        volatile String modelOverride;
        volatile String thinkingOverride;
        volatile Map<String, String> extraEnvironment = Map.of();
        volatile Consumer<String> outputConsumer;

        FakeManagedRunner(String agentName, Path workDir, boolean injectMcpTools,
                          java.util.function.BooleanSupplier blockRunMessage) {
            super(agentName, workDir.toString(), true, injectMcpTools, "", 0, null,
                    new TerminalRenderer(false), new AsciiRenderer(new TerminalRenderer(false), 100));
            this.agentName = agentName;
            this.injectMcpTools = injectMcpTools;
            this.blockRunMessage = blockRunMessage;
        }

        @Override
        public void setLaunchOverrides(String modelOverride, String thinkingOverride) {
            this.modelOverride = modelOverride;
            this.thinkingOverride = thinkingOverride;
            super.setLaunchOverrides(modelOverride, thinkingOverride);
        }

        @Override
        public void setExtraEnvironment(Map<String, String> extraEnvironment) {
            this.extraEnvironment = extraEnvironment;
        }

        @Override
        public void setOutputConsumer(Consumer<String> outputConsumer) {
            this.outputConsumer = outputConsumer;
        }

        @Override
        public void setInputProvider(Function<String, String> inputProvider) {
            // No interactive input in fake runs.
        }

        @Override
        public void injectMcpTools() {
            injectMcpToolsCalled = true;
        }

        @Override
        public String runMessage(String message, ChatHistory history, ChatSessionMetrics metrics) {
            this.message = message;
            runStarted.countDown();
            if (blockRunMessage.getAsBoolean()) {
                try {
                    cancelled.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (outputConsumer != null) {
                outputConsumer.accept("managed-output");
            }
            return "managed-output";
        }

        @Override
        public void cancel() {
            cancelCalled = true;
            cancelled.countDown();
        }

        @Override
        public void cleanup() {
            cleanupCalled = true;
        }
    }
}
