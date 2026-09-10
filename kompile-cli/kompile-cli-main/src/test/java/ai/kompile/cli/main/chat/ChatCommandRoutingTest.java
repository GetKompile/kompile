package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.exec.HeadlessAgentRunner;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Reference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandRoutingTest {

    @Test
    void profilePromptIsSharedByModesButNeverHeadlessWizardOrResume() {
        for (String mode : List.of("standard", "passthrough")) {
            ChatCommand command = parse("--mode", mode);
            assertTrue(command.shouldOfferProjectProfile(false, false, false));
            assertFalse(command.shouldOfferProjectProfile(true, false, false));
            assertFalse(command.shouldOfferProjectProfile(false, true, false));
            assertFalse(command.shouldOfferProjectProfile(false, false, true));
        }
    }

    @Test
    void passthroughProfilesReachNativeArgumentsForBothStyles() {
        for (boolean managed : List.of(true, false)) {
            ChatCommand command = parse("--mode", "passthrough");
            ChatConfig config = passthroughConfig(managed);
            config.setProvider(null);
            config.setPassthroughAgent("codex");
            config.setModel("profile-model");
            config.setThinking("high");
            command.applyCommandLineOverrides(config);
            command.applyPassthroughProfileSettings(config, "codex");
            String model = (String) org.springframework.test.util.ReflectionTestUtils.getField(command, "model");
            String thinking = (String) org.springframework.test.util.ReflectionTestUtils.getField(command, "thinking");
            assertEquals("profile-model", model);
            assertEquals("high", thinking);
            assertEquals(List.of("--model", "profile-model", "-c", "model_reasoning_effort=\"high\""),
                    ai.kompile.cli.main.chat.agent.AgentLaunchDefaults.commandArguments("codex", model, thinking,
                            managed ? ai.kompile.cli.main.chat.agent.AgentLaunchDefaults.LaunchMode.MANAGED
                                    : ai.kompile.cli.main.chat.agent.AgentLaunchDefaults.LaunchMode.INTERACTIVE));
        }
    }

    @Test
    void passthroughProfileDoesNotLeakAcrossAgentsAndFlagsWin() {
        ChatConfig config = passthroughConfig(true);
        config.setProvider(null);
        config.setPassthroughAgent("codex");
        config.setModel("profile-model");
        config.setThinking("high");
        ChatCommand other = parse("--mode", "passthrough", "--agent", "claude");
        other.applyPassthroughProfileSettings(config, "claude");
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(other, "model"));
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(other, "thinking"));
        ChatCommand explicit = parse("--mode", "passthrough", "--model", "explicit-model", "--thinking", "low");
        explicit.applyCommandLineOverrides(config);
        explicit.applyPassthroughProfileSettings(config, "codex");
        assertEquals("explicit-model", org.springframework.test.util.ReflectionTestUtils.getField(explicit, "model"));
        assertEquals("low", org.springframework.test.util.ReflectionTestUtils.getField(explicit, "thinking"));
    }

    @Test
    void modelOverrideDoesNotRetainAnotherModelsProfileThinking() {
        ChatConfig config = new ChatConfig("ollama", null, "profile-model", null);
        config.setThinking("high");
        parse("--model", "different-model").applyCommandLineOverrides(config);
        assertEquals("different-model", config.getModel());
        assertNull(config.getThinking());
    }

    @Test
    void standardConfigForcedToPassthroughDoesNotLeakProviderModel() {
        ChatConfig config = new ChatConfig("anthropic", null, "api-model", null);
        config.setThinking("8192");
        config.setChatMode("passthrough");
        config.setPassthroughAgent("claude");
        ChatCommand command = parse("--mode", "passthrough");
        command.applyPassthroughProfileSettings(config, "claude");
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(command, "model"));
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(command, "thinking"));
    }

    @Test
    void modelTopPaneIncludesConfiguredThinkingEffort() {
        assertEquals("OpenAI / gpt-5 / effort: high",
                ChatRepl.modelTopPaneLabel("OpenAI / gpt-5", "high"));
        assertEquals("Anthropic / claude / effort: 8192",
                ChatRepl.modelTopPaneLabel("Anthropic / claude", "8192"));
        assertEquals("OpenAI / gpt-5 / effort: low",
                ChatRepl.modelTopPaneLabel("OpenAI / gpt-5", " low "));
    }

    @Test
    void modelTopPaneDistinguishesDefaultFromDisabledThinking() {
        for (String thinking : new String[] {null, "", "  "}) {
            assertEquals("vendor / model / effort: default",
                    ChatRepl.modelTopPaneLabel("vendor / model", thinking));
        }
        for (String thinking : new String[] {"off", "none"}) {
            assertEquals("vendor / model / effort: " + thinking,
                    ChatRepl.modelTopPaneLabel("vendor / model", thinking));
        }
    }

    @Test
    void generatedTranscriptIdentifierIsCanonicalUuid() {
        String transcriptUuid = ChatCommand.newTranscriptUuid();

        assertEquals(transcriptUuid, UUID.fromString(transcriptUuid).toString());
        assertFalse(transcriptUuid.startsWith("cli-"));
    }

    @Test
    void clearContinuesInSameSessionLoopWithFreshTranscriptUuid() throws Exception {
        String firstTranscriptUuid = ChatCommand.newTranscriptUuid();
        List<String> transcriptUuids = new ArrayList<>();
        List<Boolean> resumeFlags = new ArrayList<>();

        String finalTranscriptUuid = ChatCommand.runStandardSessionLoop(
                firstTranscriptUuid, true, (transcriptUuid, resume) -> {
                    transcriptUuids.add(transcriptUuid);
                    resumeFlags.add(resume);
                    return transcriptUuids.size() == 1;
                });

        assertEquals(2, transcriptUuids.size());
        assertEquals(firstTranscriptUuid, transcriptUuids.get(0));
        assertEquals(finalTranscriptUuid, transcriptUuids.get(1));
        assertNotEquals(firstTranscriptUuid, finalTranscriptUuid);
        assertEquals(finalTranscriptUuid, UUID.fromString(finalTranscriptUuid).toString());
        assertEquals(List.of(true, false), resumeFlags);
    }

    @Test
    void managedClearRequestsFreshConversationAndUsesUuidTranscript() {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();

        String result = command.handleSlashCommand("/clear", null, null, null);
        String transcriptId = EmulatedPassthroughCommand.newManagedTranscriptId();

        assertEquals("clear", result);
        assertTrue(command.isNewConversationRequested());
        assertTrue(transcriptId.startsWith("emulated-"));
        String uuid = transcriptId.substring("emulated-".length());
        assertEquals(uuid, UUID.fromString(uuid).toString());
    }

    @Test
    void resetSlashCommandsRouteBeforeTheSkillFallback() {
        TerminalRenderer renderer = new TerminalRenderer();
        ChatCommandRouter router = new ChatCommandRouter(
                null, null, null, null,
                null, null, "routing-session", true,
                null, null, renderer, new AsciiRenderer(renderer),
                null, null, null, null, null, null, null, null, null,
                List.of(), null);

        assertTrue(router.handleSlashCommand("/reset unexpected"));
        assertTrue(router.handleSlashCommand("/reset-all unexpected"));
    }

    @Test
    void naturalJudgeTextRoutesToConversationWithoutStealingControlCommands() {
        assertEquals(new ChatCommandRouter.JudgeCommand("status", ""),
                ChatCommandRouter.parseJudgeCommand(""));
        assertEquals(new ChatCommandRouter.JudgeCommand("status", ""),
                ChatCommandRouter.parseJudgeCommand("status"));
        assertEquals(new ChatCommandRouter.JudgeCommand("approve", "git revert HEAD"),
                ChatCommandRouter.parseJudgeCommand("approve git revert HEAD"));
        assertEquals(new ChatCommandRouter.JudgeCommand("approve", "off"),
                ChatCommandRouter.parseJudgeCommand("approve off"));
        assertEquals(new ChatCommandRouter.JudgeCommand("feedback", "be less strict"),
                ChatCommandRouter.parseJudgeCommand("feedback be less strict"));
        assertEquals(new ChatCommandRouter.JudgeCommand("chat", "why did you block that?"),
                ChatCommandRouter.parseJudgeCommand("why did you block that?"));
        assertEquals(new ChatCommandRouter.JudgeCommand("chat", "explain the last verdict"),
                ChatCommandRouter.parseJudgeCommand("ask explain the last verdict"));
    }

    @Test
    void managedChatUsesTheParentTranscriptUuidAndResumeReusesIt() {
        String parentTranscript = UUID.randomUUID().toString();
        String resumedTranscript = UUID.randomUUID().toString();

        assertEquals(parentTranscript,
                EmulatedPassthroughCommand.resolveManagedTranscriptId(
                        parentTranscript, resumedTranscript));
        assertEquals(resumedTranscript,
                EmulatedPassthroughCommand.resolveManagedTranscriptId(
                        null, resumedTranscript));
    }

    @Test
    void resumeWithoutWorkingDirectoryInfersRecordedProject(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("recorded-project").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        try {
            ChatHistory history = new ChatHistory("recorded-project-session");
            history.open("(local)", "coder", false, project);
            history.logUserMessage("resume in the recorded project");
            history.close();

            ChatCommand command = parse(
                    "--resume", "recorded-project-session", "--mode", "standard");
            command.inferResumeWorkingDirectory();

            assertEquals(project, command.effectiveWorkingDirectory());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void explicitWorkingDirectoryIsNormalizedForResumedProjectContext() {
        Path project = Path.of("target", "resume-project").toAbsolutePath().normalize();
        ChatCommand command = parse("--working-dir", project.toString());

        assertEquals(project, command.effectiveWorkingDirectory());
    }

    @Test
    void memoryUsesTheExplicitChatWorkingDirectory(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path memoryDir = tempDir.resolve(".kompile").resolve("memory");
        java.nio.file.Files.createDirectories(memoryDir);
        java.nio.file.Files.writeString(
                memoryDir.resolve("MEMORY.md"), "PROJECT_MEMORY_MARKER");

        ChatMemory memory = new ChatMemory(null, "memory-scope-test", true, tempDir);

        assertTrue(memory.buildMemoryContext("unrelated query")
                .contains("PROJECT_MEMORY_MARKER"));
    }

    @Test
    void serverSystemPromptIncludesProjectInstructionsAndSkillCatalog(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("AGENTS.md"),
                "SERVER_AGENTS_MARKER");
        Path skills = tempDir.resolve(".kompile/skills");
        java.nio.file.Files.createDirectories(skills);
        java.nio.file.Files.writeString(skills.resolve("server-check.md"), """
                ---
                name: server-check
                description: Server skill marker
                ---
                SERVER_SKILL_TEMPLATE {{args}}
                """);

        ChatCommand command = parse("--working-dir", tempDir.toString());
        String prompt = command.serverSystemPrompt();

        assertTrue(prompt.contains("SERVER_AGENTS_MARKER"));
        assertTrue(prompt.contains("/server-check"));
        assertTrue(prompt.contains("Server skill marker"));
    }

    @Test
    void directPassthroughDoesNotConsiderImplicitProjectEnforcement() {
        ChatConfig config = passthroughConfig(false);

        assertFalse(ChatCommand.shouldConsiderEnforcement(config, false));
    }

    @Test
    void directPassthroughCanStillUseExplicitRuleFlags() {
        ChatConfig config = passthroughConfig(false);

        assertTrue(ChatCommand.shouldConsiderEnforcement(config, true));
    }

    @Test
    void managedPassthroughConsidersProjectEnforcement() {
        ChatConfig config = passthroughConfig(true);

        assertTrue(ChatCommand.shouldConsiderEnforcement(config, false));
    }

    @Test
    void deepSeekHarnessForcesManagedOneShotPassthrough() {
        ChatConfig config = passthroughConfig(false);
        config.setPassthroughAgent("dsh");

        assertTrue(ChatCommand.shouldUseManagedPassthrough(config, false, "dsh"));
        assertFalse(ChatCommand.shouldUseManagedPassthrough(config, false, "codex"));
    }

    @Test
    void internalRestartResumeKeepsTheManagedUiAndBypassesCrossAgentRouting() {
        ChatConfig directStyle = passthroughConfig(false);
        ChatCommand restart = parse(
                "--resume", "session-abc",
                "--mode", "passthrough",
                "--agent", "codex",
                "--internal-managed-resume");
        ChatCommand ordinaryResume = parse(
                "--resume", "session-abc",
                "--mode", "passthrough",
                "--agent", "codex");

        assertTrue(restart.shouldUseManagedPassthroughForInvocation(
                directStyle, false, "codex"));
        assertFalse(restart.shouldDelegateManagedResume(true));
        assertTrue(ordinaryResume.shouldDelegateManagedResume(true));
    }

    @Test
    void explicitPassthroughRouteDoesNotNeedSavedConfig() {
        ChatCommand command = parse("--mode", "passthrough", "--agent", "codex");

        ChatConfig config = command.configFromExplicitRoute();

        assertNotNull(config);
        assertTrue(config.isValid());
        assertEquals("passthrough", config.getChatMode());
        assertEquals("codex", config.getPassthroughAgent());
    }

    @Test
    void explicitServerRouteDoesNotNeedSavedConfig() {
        ChatCommand command = parse("--url", "http://localhost:8081");

        ChatConfig config = command.configFromExplicitRoute();

        assertNotNull(config);
        assertTrue(config.isValid());
        assertEquals("kompile", config.getProvider());
        assertEquals("standard", config.getChatMode());
    }

    @Test
    void standardResumeDoesNotPromotePassthroughToHttp() {
        ChatCommand command = parse("--resume", "cli-test", "--mode", "standard");
        ChatConfig passthrough = passthroughConfig(true);

        ChatConfig config = command.normalizeResumeConfig(passthrough, true);

        assertNull(config);
    }

    @Test
    void standardResumeKeepsDirectProviderConfig() {
        ChatCommand command = parse("--resume", "cli-test", "--mode", "standard");
        ChatConfig direct = new ChatConfig("openai-codex", null, "gpt-5.6-sol", null);
        direct.setChatMode("standard");

        ChatConfig config = command.normalizeResumeConfig(direct, true);

        assertNotNull(config);
        assertEquals("openai-codex", config.getProvider());
        assertEquals("gpt-5.6-sol", config.getModel());
        assertEquals("standard", config.getChatMode());
        assertFalse(config.isKompileServer());
    }

    @Test
    void standardResumePreservesOauthWithoutFlatteningIt(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        try {
            Path conversations = tempDir.resolve("home").resolve(".kompile")
                    .resolve("conversations");
            java.nio.file.Files.createDirectories(conversations);
            java.nio.file.Files.writeString(
                    conversations.resolve("oauth-resume.metrics.json"),
                    "{\"session\":{\"provider\":\"openai-codex\","
                            + "\"model\":\"gpt-5.6-terra\"}}");
            ChatCommand command = parse(
                    "--resume", "oauth-resume", "--mode", "standard");
            ChatConfig fallback = new ChatConfig(
                    "openai-codex", "transient-token", "old", "https://chatgpt.test");
            fallback.setAuthenticationMethod("oauth");

            ChatConfig resumed = command.normalizeResumeConfig(fallback, true);

            assertNotNull(resumed);
            assertEquals("openai-codex", resumed.getProvider());
            assertEquals("oauth", resumed.getAuthenticationMethod());
            assertManagedOauthHasNoCopiedApiKey(resumed);
            assertEquals("https://chatgpt.test", resumed.getBaseUrl());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void noStartConnectsToDefaultServerWithoutSavedConfig() {
        ChatConfig config = parse("--no-start").configFromExplicitRoute();

        assertNotNull(config);
        assertEquals("kompile", config.getProvider());
        assertEquals("standard", config.getChatMode());
    }

    @Test
    void explicitLocalRouteStillNeedsProviderConfig() {
        ChatCommand command = parse("--local");

        assertNull(command.configFromExplicitRoute());
    }

    @Test
    void bareChatKeepsWizardFirstFlow() {
        ChatCommand command = parse();

        assertTrue(command.shouldRunSetupWizard(null, false));
        ChatConfig savedDirectConfig = new ChatConfig("ollama", null, "llama3.3", null);
        assertTrue(command.shouldRunSetupWizard(savedDirectConfig, false));
    }

    @Test
    void completedResumeWizardActionDoesNotRequireProviderConfiguration() {
        ChatConfig action = new ChatConfig(null, null, null, null);
        action.setChatMode("resume");

        assertFalse(action.isValid());
        assertTrue(ChatCommand.isWizardActionMode(action));
        assertFalse(ChatCommand.isWizardActionMode(
                new ChatConfig("ollama", null, "llama3.3", null)));
    }

    @Test
    void onlyStandardDirectConfigsCanHotSwitchInsideTheCurrentTranscript() {
        ChatConfig direct = new ChatConfig("anthropic", "key", "claude-sonnet-4-20250514", null);
        direct.setChatMode("standard");
        ChatConfig server = new ChatConfig("kompile", null, null, "http://localhost:8081");
        server.setChatMode("standard");
        ChatConfig passthrough = passthroughConfig(true);

        assertTrue(ChatRepl.canHotSwitchLocalProvider(direct));
        assertFalse(ChatRepl.canHotSwitchLocalProvider(server));
        assertFalse(ChatRepl.canHotSwitchLocalProvider(
                new ChatConfig("kompile-local", null, "Qwen2.5-0.5B-Instruct", null)));
        assertFalse(ChatRepl.canHotSwitchLocalProvider(passthrough));
    }

    @Test
    void modeHotkeysUseCtrlXChordsWithoutStealingPrintableLetters() {
        KeyMap<Binding> keyMap = new KeyMap<>();

        ChatRepl.bindModeSwitchingHotkeys(keyMap);

        for (char printable : new char[]{'p', 'P', 't', 'T', 'a', 'A'}) {
            assertNull(keyMap.getBound(String.valueOf(printable)),
                    () -> "Printable letter must remain available for typing: " + printable);
        }
        assertWidgetBinding(keyMap, 'p', "toggle-plan-mode");
        assertWidgetBinding(keyMap, 'P', "toggle-plan-mode");
        assertWidgetBinding(keyMap, 't', "show-todos");
        assertWidgetBinding(keyMap, 'T', "show-todos");
        assertWidgetBinding(keyMap, 'a', "cycle-agent");
        assertWidgetBinding(keyMap, 'A', "cycle-agent");
    }

    @Test
    void directProviderAndMissingConfigNeverStartInstalledSubprocess() {
        ChatCommand command = parse();
        ChatConfig direct = new ChatConfig("ollama", null, "llama3.3", null);
        ChatConfig firstPartyLocal =
                new ChatConfig("kompile-local", null, "Qwen2.5-0.5B-Instruct", null);

        assertFalse(command.shouldStartInstalledChatSubprocess(direct, true));
        assertFalse(command.shouldStartInstalledChatSubprocess(firstPartyLocal, true));
        assertFalse(command.shouldStartInstalledChatSubprocess(null, true));
    }

    @Test
    void firstPartyLocalConfigIsValidWithoutInstanceOrApiKey() {
        ChatConfig config =
                new ChatConfig("kompile-local", null, "Qwen2.5-0.5B-Instruct", null);

        assertTrue(config.isValid());
        assertTrue(config.isKompileLocalServing());
        assertFalse(config.isKompileServer());
        assertNull(config.resolveBaseUrl());
    }

    @Test
    void localKompileProviderCanStartInstalledSubprocessAfterSetup() {
        ChatCommand command = parse();
        ChatConfig localKompile = new ChatConfig("kompile", null, null,
                "http://localhost:9191");

        assertTrue(command.shouldStartInstalledChatSubprocess(localKompile, true));
        assertFalse(command.shouldStartInstalledChatSubprocess(localKompile, false));
    }

    @Test
    void configuredRemoteKompileInstanceRemainsConnectOnly() {
        ChatCommand command = parse();
        ChatConfig remoteKompile = new ChatConfig("kompile", null, null,
                "https://chat.example.com");

        assertFalse(command.shouldStartInstalledChatSubprocess(remoteKompile, true));
    }

    @Test
    void explicitRoutesAndNoStartDisableInstalledSubprocessFallback() {
        assertFalse(parse("--no-start").canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--local").canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--url", "http://localhost:8081")
                .canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--port", "8081").canUseInstalledChatSubprocessFallback());
        assertFalse(parse("--mode", "passthrough").canUseInstalledChatSubprocessFallback());
        assertTrue(parse("--mode", "standard").canUseInstalledChatSubprocessFallback());
    }

    @Test
    void kompileConfigResolvesSavedServerUrlWithoutExplicitFlags() {
        ChatCommand command = parse();
        ChatConfig config = new ChatConfig("kompile", null, null, "http://localhost:9191");

        assertEquals("http://localhost:9191", command.resolveServerUrl(config));
    }

    @Test
    void explicitServerUrlOverridesSavedKompileUrl() {
        ChatCommand command = parse("--url", "http://localhost:8181");
        ChatConfig config = new ChatConfig("kompile", null, null, "http://localhost:9191");

        assertEquals("http://localhost:8181", command.resolveServerUrl(config));
    }

    @Test
    void standardChatExposesExplicitDangerousPermissionBypass() {
        assertFalse(parse().dangerouslySkipsPermissions());
        assertTrue(parse("--dangerously-skip-permissions").dangerouslySkipsPermissions());
    }

    @Test
    void trueDefaultNegatableFeaturesHonorPositiveAndNegativeForms() {
        ChatCommand defaults = parse();
        ChatCommand positive = parse("--memory", "--rag");
        ChatCommand negative = parse("--no-memory", "--no-rag");
        ChatCommand explicitFalse = parse("--memory=false", "--rag=false");

        assertTrue(defaults.isMemoryEnabled());
        assertTrue(defaults.isRagEnabled());
        assertTrue(positive.isMemoryEnabled());
        assertTrue(positive.isRagEnabled());
        assertFalse(negative.isMemoryEnabled());
        assertFalse(negative.isRagEnabled());
        assertFalse(explicitFalse.isMemoryEnabled());
        assertFalse(explicitFalse.isRagEnabled());
    }

    @Test
    void positionalPromptSelectsNonInteractiveTextWithoutChangingBareChat() {
        ChatCommand bare = parse();
        ChatCommand prompted = parse("summarize", "the", "project");

        assertFalse(bare.isHeadlessRequested());
        assertTrue(prompted.isHeadlessRequested());
        assertEquals(HeadlessAgentRunner.OutputMode.TEXT,
                prompted.resolveHeadlessOutputMode());
    }

    @Test
    void chatAcceptsClaudeStyleStreamJsonAndCodexStyleJsonAlias() {
        ChatCommand streamJson = parse(
                "--output-format", "stream-json", "summarize", "README.md");
        ChatCommand jsonAlias = parse("--json", "summarize", "README.md");

        assertTrue(streamJson.isHeadlessRequested());
        assertEquals(HeadlessAgentRunner.OutputMode.JSON,
                streamJson.resolveHeadlessOutputMode());
        assertEquals(HeadlessAgentRunner.OutputMode.JSON,
                jsonAlias.resolveHeadlessOutputMode());
    }

    @Test
    void jsonAliasRejectsConflictingTextFormat() {
        ChatCommand command = parse(
                "--json", "--output-format", "text", "hello");

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, command::resolveHeadlessOutputMode);
        assertTrue(error.getMessage().contains("--json cannot be combined"));
    }

    @Test
    void credentialFlagsAreSessionLocalAndGlobalConflictIsRejected() {
        ChatConfig config = new ChatConfig("openai", null, "model", null);
        parse("--credential", "work").applyCommandLineOverrides(config);
        assertEquals("session", config.getAuthenticationScope());
        assertEquals("work", config.getCredentialName());
        ChatConfig global = config.copy();
        parse("--auth-scope", "global").applyCommandLineOverrides(global);
        assertNull(global.getCredentialName());
        assertEquals("work", config.getCredentialName());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> parse("--auth-scope", "global", "--credential", "work").applyCommandLineOverrides(global));
    }

    @Test
    void resumeRestoresSessionProviderAndAccountBeforeExplicitOverrides(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            ChatConfig saved = new ChatConfig("openai-codex", null, "saved-model", null);
            saved.setCredentialName("personal");
            saved.bindSession("saved-session");
            ChatCommand command = parse("--resume", "saved-session", "--model", "override", "--credential", "work");
            ChatConfig resumed = command.normalizeResumeConfig(new ChatConfig("anthropic", null, "other", null), true);
            assertEquals("openai-codex", resumed.getProvider());
            assertEquals("personal", resumed.getCredentialName());
            command.applyCommandLineOverrides(resumed);
            assertEquals("override", resumed.getModel());
            assertEquals("work", resumed.getCredentialName());
            ChatConfig candidate = ChatRepl.buildModelProviderCandidateFrom(resumed, "openai-codex", "next", null);
            assertEquals("work", candidate.getCredentialName());
            ChatConfig other = ChatRepl.buildModelProviderCandidateFrom(resumed, "anthropic", "next", null);
            assertNull(other.getCredentialName());
        } finally { System.setProperty("user.home", previousHome); }
    }

    @Test
    void providerOverrideDropsPreviousProviderBoundSettings() {
        ChatConfig config = new ChatConfig(
                "ollama", "old-secret", "old-model", "http://old-endpoint");
        config.setThinking("old-effort");
        config.setContextWindowTokens(32_000);
        config.setMaxOutputTokens(4_000);
        ChatCommand command = parse(
                "--provider", "custom", "--base-url", "http://new-endpoint");

        command.applyCommandLineOverrides(config);

        assertEquals("custom", config.getProvider());
        assertEquals("http://new-endpoint", config.getBaseUrl());
        assertNull(config.getApiKey());
        assertNull(config.getModel());
        assertNull(config.getThinking());
        assertEquals(0, config.getContextWindowTokens());
        assertEquals(0, config.getMaxOutputTokens());
        assertEquals("standard", config.getChatMode());
    }

    @Test
    void providerOverridePreservesTheWizardSelectedSubscriptionRoute() {
        ChatConfig subscription = new ChatConfig(
                "openai-codex", null, "gpt-5.6-terra", null);
        ChatCommand command = parse("--provider", "openai", "--model", "gpt-next");

        command.applyCommandLineOverrides(subscription);

        assertEquals("openai-codex", subscription.getProvider());
        assertEquals("gpt-next", subscription.getModel());
    }

    @Test
    void explicitAuthSelectsTheSameWireProviderAsTheWizard() {
        ChatConfig api = new ChatConfig("openai-codex", null, "old", null);
        ChatCommand apiCommand = parse(
                "--provider", "openai", "--auth", "api-key", "--model", "gpt-api");
        apiCommand.applyCommandLineOverrides(api);

        ChatConfig subscription = new ChatConfig("openai", null, "old", null);
        ChatCommand oauthCommand = parse(
                "--provider", "openai", "--auth", "subscription", "--model", "gpt-sub");
        oauthCommand.applyCommandLineOverrides(subscription);

        assertEquals("openai", api.getProvider());
        assertEquals("openai-codex", subscription.getProvider());
        assertEquals("api-key", api.getAuthenticationMethod());
        assertEquals("oauth", subscription.getAuthenticationMethod());
    }

    @Test
    void promptCacheRetentionOverrideIsValidatedAndApplied() {
        ChatConfig config = new ChatConfig("openai", null, "gpt-5.6-terra", null);
        ChatCommand longCache = parse("--prompt-cache-retention", "long");
        longCache.applyCommandLineOverrides(config);
        assertEquals("long", config.getPromptCacheRetention());

        ChatCommand invalid = parse("--prompt-cache-retention", "forever");
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> invalid.applyCommandLineOverrides(config));
        assertTrue(failure.getMessage().contains("none, short, or long"));
    }

    @Test
    void modelSwitchDoesNotFlattenManagedOauthIntoAnApiKey(
            @org.junit.jupiter.api.io.TempDir Path tempDir) {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        try {
            ChatConfig active = new ChatConfig(
                    "openai-codex", "transient-token", "gpt-old", "https://chatgpt.test");
            active.setAuthenticationMethod("oauth");

            ChatConfig candidate = ChatRepl.buildModelProviderCandidateFrom(
                    active, "openai-codex", "gpt-new", null);

            assertEquals("openai-codex", candidate.getProvider());
            assertEquals("gpt-new", candidate.getModel());
            assertEquals("oauth", candidate.getAuthenticationMethod());
            assertManagedOauthHasNoCopiedApiKey(candidate);
            assertEquals("https://chatgpt.test", candidate.getBaseUrl());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private static void assertManagedOauthHasNoCopiedApiKey(ChatConfig config) {
        org.junit.jupiter.api.Assertions.assertThrows(ChatConfig.AuthenticationException.class,
                config::resolveRequestAuth,
                "missing managed OAuth must fail closed rather than use the transient token");
        // getApiKey() resolves credentials. Isolate the copied in-memory field from
        // provider stores/environment without changing the original OAuth config.
        ChatConfig explicitOnly = config.copy();
        explicitOnly.setProvider(null);
        explicitOnly.setAuthenticationMethod("api-key");
        assertNull(explicitOnly.getApiKey(), "routing must not copy a transient OAuth token as an API key");
    }

    @Test
    void portAndProviderAreRejectedAsConflictingHeadlessRoutes() {
        ChatCommand command = parse(
                "--port", "8081", "--provider", "anthropic", "hello");

        assertEquals(2, command.call());
    }

    @Test
    void chatHelpKeepsUrlAndModelExamplesReadable() {
        String usage = new CommandLine(new ChatCommand()).getUsageMessage();

        assertTrue(usage.contains("Example: http://localhost:8081"), usage);
        assertTrue(usage.contains("Examples: haiku, gpt-5.2-codex"), usage);
        assertTrue(usage.contains("--[no-]start"), usage);
        assertTrue(usage.contains("--startup-timeout"), usage);
        assertTrue(usage.contains("--dangerously-skip-permissions"), usage);
        assertTrue(usage.contains("--output-format"), usage);
        assertTrue(usage.contains("stream-json"), usage);
        assertTrue(usage.contains("--capabilities"), usage);
        assertTrue(usage.contains("--attachment"), usage);
        assertTrue(usage.contains("--provider"), usage);
        assertTrue(usage.contains("--auth"), usage);
        assertFalse(usage.contains("--project"), usage);
        assertFalse(usage.contains("http:\n"), usage);
        assertFalse(usage.contains("gpt-5.\n"), usage);
    }

    @Test
    void passthroughHelpExplainsDeepSeekManagedModeAndKeepsModelExampleReadable() {
        String usage = new CommandLine(new PassthroughCommand()).getUsageMessage();

        assertTrue(usage.contains("claude, codex, opencode, gemini"), usage);
        assertTrue(usage.contains("DeepSeek Harness uses managed chat"), usage);
        assertTrue(usage.contains("passthrough)"), usage);
        assertTrue(usage.contains("Examples: haiku, gpt-5.2-codex"), usage);
        assertFalse(usage.contains("gpt-5.\n"), usage);
    }

    @Test
    void rawPassthroughRejectsDeepSeekBecauseItHasNoShippedTui() {
        assertEquals(2, new CommandLine(new PassthroughCommand()).execute("--agent", "dsh"));
    }

    private static void assertWidgetBinding(
            KeyMap<Binding> keyMap, char key, String expectedWidget) {
        Binding binding = keyMap.getBound(KeyMap.ctrl('X') + String.valueOf(key));
        assertInstanceOf(Reference.class, binding);
        assertEquals(expectedWidget, ((Reference) binding).name());
    }

    private static ChatConfig passthroughConfig(boolean managed) {
        ChatConfig config = new ChatConfig(null, null, null, null);
        config.setChatMode("passthrough");
        config.setPassthroughManaged(managed);
        config.setPassthroughAgent("codex");
        return config;
    }

    private static ChatCommand parse(String... args) {
        ChatCommand command = new ChatCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }
}
