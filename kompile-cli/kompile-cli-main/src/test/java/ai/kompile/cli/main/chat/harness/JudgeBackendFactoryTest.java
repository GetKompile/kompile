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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatProfiles;
import ai.kompile.cli.main.chat.config.JudgeDefaults;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeBackendFactoryTest {
    @TempDir Path project;
    private String originalHome;
    private String originalDir;

    @BeforeEach void isolateDefaults() {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.home", project.resolve("home").toString());
        System.setProperty("user.dir", project.toString());
    }

    @AfterEach void restoreDirectories() {
        System.setProperty("user.home", originalHome);
        System.setProperty("user.dir", originalDir);
    }

    @Test
    void savedVendorDefaultIsUsedAndExplicitModelDoesNotInheritItsThinking() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                new JudgeDefaults.Selection("gpt-5.6-sol", "high"));
        ChatConfig main = new ChatConfig("openai-codex", "test-key", "gpt-6-astra", "http://unused.invalid");
        main.setAuthenticationMethod("api-key");
        main.setThinking("max");
        try (var judge = JudgeBackendFactory.createDirectJudgeClient(
                main, null, null, null, null, new ObjectMapper(), project)) {
            assertEquals("gpt-5.6-sol", judge.getConfiguredModel());
            assertEquals("high", judge.getChatConfig().getThinking());
            assertEquals("gpt-6-astra", main.getModel());
            assertEquals("max", main.getThinking());
        }
        try (var judge = JudgeBackendFactory.createDirectJudgeClient(
                main, null, null, "gpt-5.6-terra", null, new ObjectMapper(), project)) {
            assertEquals("gpt-5.6-terra", judge.getConfiguredModel());
            assertEquals("low", judge.getChatConfig().getThinking());
        }
    }

    @Test
    void providerSwitchUsesItsOwnDefaultRatherThanMainChatModel() throws Exception {
        ChatConfig main = new ChatConfig("anthropic", "main-key", "claude-model", "http://main.invalid");
        assertNull(JudgeBackendFactory.createDirectJudgeClient(
                main, "openai", "judge-key", null, null, new ObjectMapper(), project));
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                new JudgeDefaults.Selection("gpt-5.6", null));
        try (var judge = JudgeBackendFactory.createDirectJudgeClient(
                main, "openai", "judge-key", null, null, new ObjectMapper(), project)) {
            assertEquals("gpt-5.6", judge.getConfiguredModel());
            assertEquals("none", judge.getChatConfig().getThinking());
            assertNull(judge.getChatConfig().getBaseUrl());
        }
    }

    @Test
    void serverJudgeUsesSavedDefaultButExplicitModelStillWinsWithoutStartingServer() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "ollama",
                new JudgeDefaults.Selection("saved-local-model", null));
        {
            var server = new ServerJudgeBackend(ServerJudgeBackend.ServerType.OLLAMA,
                    null, 11434, new ObjectMapper());
            assertTrue(server.describe().contains("saved-local-model"));
        }
        {
            var server = new ServerJudgeBackend(ServerJudgeBackend.ServerType.OLLAMA,
                    "explicit-model", 11434, new ObjectMapper());
            assertTrue(server.describe().contains("explicit-model"));
            assertFalse(server.describe().contains("saved-local-model"));
        }
    }

    @Test
    void mainChatFactoryUsesOwningProjectRatherThanProcessDirectory() throws Exception {
        Path owner = project.resolve("other-project");
        selectProfile(project, "ollama", "ambient-model", null);
        selectProfile(owner, "ollama", "owner-model", "minimal");
        ChatConfig main = new ChatConfig("ollama", null, "main-model", "http://unused.invalid");
        main.setThinking("high");
        HarnessConfig config = new HarnessConfig();
        config.setJudgeMode("remote");
        config.setJudgeDeadlineMs(0);
        try (var mainClient = new DirectLlmClient(main, new ObjectMapper(), owner)) {
            var backend = JudgeBackendFactory.create(mainClient, config, new ObjectMapper());
            try {
                DirectLlmClient judge = remoteClient(backend);
                assertEquals("owner-model", judge.getConfiguredModel());
                assertEquals("minimal", judge.getChatConfig().getThinking());
                assertEquals(owner, judge.getWorkingDirectory());
                assertEquals("http://unused.invalid", judge.getChatConfig().getBaseUrl());
                assertEquals("main-model", main.getModel());
                assertEquals("high", main.getThinking());
            } finally {
                backend.close();
            }
        }
    }

    @Test
    void headlessFactoryUsesProjectProfileForDedicatedAndInheritedRoutes() throws Exception {
        Path owner = project.resolve("other-project");
        selectProfile(project, "ollama", "ambient-model", null);
        selectProfile(owner, "ollama", "owner-model", "minimal");
        HarnessConfig config = new HarnessConfig();
        config.setJudgeMode("remote");
        config.setJudgeDeadlineMs(0);
        config.setJudgeProvider("ollama");
        config.setJudgeBaseUrl("http://unused.invalid");
        var backend = JudgeBackendFactory.create(config, new ObjectMapper(), owner);
        try {
            assertEquals("owner-model", remoteClient(backend).getConfiguredModel());
            assertEquals("minimal", remoteClient(backend).getChatConfig().getThinking());
            assertEquals(owner, remoteClient(backend).getWorkingDirectory());
        } finally {
            backend.close();
        }
        config.setJudgeModel("explicit-model");
        backend = JudgeBackendFactory.create(config, new ObjectMapper(), owner);
        try {
            assertEquals("explicit-model", remoteClient(backend).getConfiguredModel());
            assertNull(remoteClient(backend).getChatConfig().getThinking());
        } finally {
            backend.close();
        }
        new ChatConfig("ollama", null, "main-model", "http://project-route.invalid").saveProject(owner);
        config.setJudgeProvider(null);
        config.setJudgeBaseUrl(null);
        config.setJudgeModel(null);
        backend = JudgeBackendFactory.create(config, new ObjectMapper(), owner);
        try {
            assertEquals("owner-model", remoteClient(backend).getConfiguredModel());
            assertEquals("http://project-route.invalid", remoteClient(backend).getChatConfig().getBaseUrl());
        } finally {
            backend.close();
        }
    }

    @Test
    void cliAndServerFactoriesRetainProjectScopeWithoutLaunchingAnything() throws Exception {
        Path owner = project.resolve("other-project");
        selectProfile(project, "ollama", "ambient-model", null);
        selectProfile(owner, "ollama", "owner-model", null);
        selectProfile(owner, "codex", "o3", null);
        HarnessConfig config = new HarnessConfig();
        config.setJudgeMode("auto-server");
        config.setJudgeServerType("ollama");
        config.setJudgeDeadlineMs(0);
        var backend = JudgeBackendFactory.create(config, new ObjectMapper(), owner);
        try {
            assertTrue(backend.describe().contains("owner-model"));
            assertFalse(backend.describe().contains("ambient-model"));
        } finally {
            backend.close();
        }
        config.setJudgeMode("cli");
        config.setJudgeModel("codex"); // Existing CLI mode uses this field as the agent selector.
        backend = JudgeBackendFactory.create(config, new ObjectMapper(), owner);
        try {
            assertTrue(backend instanceof CliJudgeBackend);
            assertTrue(backend.describe().contains("model=o3"));
            var command = ((CliJudgeBackend) backend).buildSingleShotCommand("codex", "question", null);
            assertEquals("o3", command.get(command.indexOf("--model") + 1));
        } finally {
            backend.close();
        }
    }

    private static void selectProfile(Path project, String provider, String model, String thinking) throws Exception {
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", provider, model, thinking), false);
        ChatProfiles.activateJudge(project, provider, "quick");
    }

    private static DirectLlmClient remoteClient(JudgeBackend backend) throws Exception {
        var field = RemoteJudgeBackend.class.getDeclaredField("client");
        field.setAccessible(true);
        return (DirectLlmClient) field.get(backend);
    }

    @Test
    void unknownProviderJudgeDoesNotInventReasoningEffort() {
        ChatConfig main = new ChatConfig("custom", "test-key", "custom-model", "http://unused.invalid");
        main.setThinking("high");
        try (DirectLlmClient judge = JudgeBackendFactory.createDirectJudgeClient(
                main, null, null, null, null, new ObjectMapper(), project)) {
            assertNull(judge.getChatConfig().getThinking());
            assertEquals("high", main.getThinking());
        }
    }

    @Test
    void globalSwitchPreventsEveryBackendModeFromStarting() {
        HarnessConfig config = new HarnessConfig();
        config.setJudgeGlobalEnabled(false);
        config.setJudgeMode("local");
        config.setJudgeLocalModel("would-be-expensive");

        JudgeBackend backend = JudgeBackendFactory.create(config, new ObjectMapper());

        assertFalse(backend.isAvailable());
        assertTrue(backend.describe().contains("disabled(global)"));
        assertThrows(IllegalStateException.class,
                () -> backend.generate("request", "system"));
    }

    @Test
    void globalSwitchDefaultsOnForBackwardCompatibility() {
        assertTrue(new HarnessConfig().isJudgeGlobalEnabled());
    }

    @Test
    void directJudgeClientIsIsolatedLowLatencyAndDoesNotMutateMainChatConfig() {
        ChatConfig main = new ChatConfig(
                "openai-codex", "test-token", "gpt-6-astra", "http://unused.invalid");
        main.setAuthenticationMethod("api-key");
        main.setThinking("ultra");

        try (DirectLlmClient judge = JudgeBackendFactory.createDirectJudgeClient(
                main, null, null, null, null, new ObjectMapper(), project)) {
            assertNotSame(main, judge.getChatConfig());
            assertEquals("gpt-6-astra", judge.getConfiguredModel());
            assertEquals("low", judge.getChatConfig().getThinking(),
                    "machine verdicts must not inherit main-chat reasoning effort");
            assertEquals(1, judge.getConnectivityPolicy().maxAttempts(),
                    "the outer judge deadline owns retries");
            assertEquals("ultra", main.getThinking(), "the main chat config remains untouched");
            assertEquals(5, main.connectivityPolicy().maxAttempts());
        }
    }

    @Test
    void resilienceWrapperHonorsConfiguredBackupModels() {
        HarnessConfig config = new HarnessConfig();
        config.setJudgeProvider("custom");
        config.setJudgeApiKey("test-key");
        config.setJudgeModel("primary-model");
        config.setJudgeSwapCandidates(List.of("backup-model"));

        JudgeBackend primary = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                return "{\"compliant\":true}";
            }
            @Override public boolean isAvailable() { return true; }
        };
        JudgeBackend wrapped = JudgeBackendFactory.withResilience(
                primary, config, new ObjectMapper());

        assertTrue(wrapped instanceof ResilientJudgeBackend);
        assertTrue(((ResilientJudgeBackend) wrapped).hasBackups());
        wrapped.close();
    }

    @Test
    void inheritedJudgeProviderCanAlsoBuildConfiguredBackupModels() {
        ChatConfig inherited = new ChatConfig(
                "custom", "test-key", "primary-model", "http://unused.invalid");
        inherited.setAuthenticationMethod("api-key");
        HarnessConfig config = new HarnessConfig();
        config.setJudgeSwapCandidates(List.of("backup-model"));
        JudgeBackend primary = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                return "{\"compliant\":true}";
            }
            @Override public boolean isAvailable() { return true; }
        };

        JudgeBackend wrapped = JudgeBackendFactory.withResilience(
                primary, config, new ObjectMapper(), inherited, project);

        assertTrue(wrapped instanceof ResilientJudgeBackend);
        assertTrue(((ResilientJudgeBackend) wrapped).hasBackups());
        wrapped.close();
    }
}
