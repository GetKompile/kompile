package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class JudgeDefaultsWizardTest {
    @TempDir Path project;

    @Test void skippingAndCancellationDoNotWriteDefaults() throws Exception {
        LineReader reader = reader("", "cancel");
        JudgeDefaultsWizard.configure(reader, ChatConfig.Scope.PROJECT, project, null);
        JudgeDefaultsWizard.configure(reader, ChatConfig.Scope.PROJECT, project, null);
        assertFalse(Files.exists(JudgeDefaults.configPath(ChatConfig.Scope.PROJECT, project)));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void wizardSavesSelectedModelAndMinimumWithoutMutatingMainChat() throws Exception {
        Path shim = codexShim(project);
        String originalHome = System.getProperty("user.home");
        String originalExecutable = System.getProperty("kompile.codex.executable");
        System.setProperty("user.home", project.resolve("home").toString());
        System.setProperty("kompile.codex.executable", shim.toString());
        ModelDiscoveryHttp.clearCache();
        try {
            storeCodexLogin();
            // The main chat's own login drives live discovery; the judge must not rewrite the chat settings.
            ChatConfig chat = new ChatConfig("openai-codex", null, "main-model", null);
            chat.setThinking("high");
            JudgeDefaultsWizard.configure(reader("yes", "openai", "create", "native-judge-model", "", "quick", "yes", ""),
                    ChatConfig.Scope.GLOBAL, project, chat);
            assertEquals(new JudgeDefaults.Selection("native-judge-model", "minimal", "openai-codex"),
                    JudgeDefaults.configured("openai-codex", project));
            assertEquals("quick", ChatProfiles.activeJudge(project, "openai-codex").name());
            assertFalse(Files.exists(ChatProfiles.path(project.resolve("home"))));
            assertFalse(Files.exists(JudgeDefaults.configPath(ChatConfig.Scope.GLOBAL, project)));
            assertEquals(1, Files.readAllLines(launches(project)).size(),
                    "reuse discovery metadata for thinking instead of fetching again");
            assertFalse(Files.readString(ChatProfiles.path(project)).contains("fixture-access-token"));
            assertEquals("openai-codex", chat.getProvider());
            assertEquals("main-model", chat.getModel());
            assertEquals("high", chat.getThinking());
            assertNull(chat.getBaseUrl());

            var review = ChatProfiles.captureJudge("review", "openai-codex", "review-model", null);
            ChatProfiles.save(project, review, false);
            ChatProfiles.activateJudge(project, "openai-codex", "review");
            JudgeDefaultsWizard.configure(reader("yes", "openai", "create", "native-judge-model", "high", "quick", "no", ""),
                    ChatConfig.Scope.PROJECT, project, chat);
            assertEquals("minimal", ChatProfiles.list(project, "judge").stream()
                    .filter(p -> p.name().equals("quick")).findFirst().orElseThrow().thinking());
            assertEquals(review, ChatProfiles.activeJudge(project, "openai-codex"));
            JudgeDefaultsWizard.configure(reader("yes", "openai", "create", "native-judge-model", "high", "quick", "yes", "no", ""),
                    ChatConfig.Scope.PROJECT, project, chat);
            assertEquals("high", ChatProfiles.list(project, "judge").stream()
                    .filter(p -> p.name().equals("quick")).findFirst().orElseThrow().thinking());
            assertEquals(review, ChatProfiles.activeJudge(project, "openai-codex"), "replacement must not switch active profiles");
        } finally {
            if (originalExecutable == null) System.clearProperty("kompile.codex.executable");
            else System.setProperty("kompile.codex.executable", originalExecutable);
            System.setProperty("user.home", originalHome);
            ModelDiscoveryHttp.clearCache();
        }
    }

    @Test void wizardSelectsClearsAndDeletesProjectProfilesWithoutChangingChatProfiles() throws Exception {
        var chat = ChatProfiles.capture("quick", new ChatConfig("openai", null, "main", null));
        ChatProfiles.save(project, chat, false);
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "openai", "o3", "low"), false);
        ChatProfiles.save(project, ChatProfiles.captureJudge("review", "openai", "gpt-5.6", null), false);
        JudgeDefaultsWizard.configure(reader("yes", "openai", "use", "quick", ""),
                ChatConfig.Scope.PROJECT, project, null);
        assertEquals("quick", ChatProfiles.activeJudge(project, "openai").name());
        JudgeDefaultsWizard.configure(reader("yes", "openai", "clear", ""),
                ChatConfig.Scope.PROJECT, project, null);
        assertNull(ChatProfiles.activeJudge(project, "openai"));
        assertEquals(2, ChatProfiles.list(project, "judge").size());
        JudgeDefaultsWizard.configure(reader("yes", "openai", "use", "review", "openai", "delete", "review", "no", ""),
                ChatConfig.Scope.PROJECT, project, null);
        assertEquals("review", ChatProfiles.activeJudge(project, "openai").name());
        JudgeDefaultsWizard.configure(reader("yes", "openai", "delete", "review", "yes", ""),
                ChatConfig.Scope.PROJECT, project, null);
        assertNull(ChatProfiles.activeJudge(project, "openai"));
        assertEquals(1, ChatProfiles.list(project, "judge").size());
        assertEquals(List.of(chat), ChatProfiles.list(project, "standard"));
    }

    /** A codex app-server stand-in that logs each launch and lists one model with high and minimal reasoning. */
    private static Path codexShim(Path directory) throws Exception {
        Path shim = directory.resolve("codex-fixture");
        Files.writeString(shim, """
                #!/bin/sh
                echo launch >> 'LAUNCHES'
                read initialize
                printf '%s\\n' '{"id":1,"result":{"userAgent":"fixture-codex/next"}}'
                read initialized
                read login
                case "$login" in
                  *'"accessToken":"fixture-access-token"'*'"chatgptAccountId":"fixture-account"'*) ;;
                  *) exit 21 ;;
                esac
                printf '%s\\n' '{"id":2,"result":{"type":"chatgptAuthTokens"}}'
                read account
                printf '%s\\n' '{"id":3,"result":{"account":{"type":"chatgpt","planType":"plus"}}}'
                read models
                printf '%s\\n' '{"id":4,"result":{"data":[{"id":"native-judge-model","supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"minimal"}]}],"nextCursor":null}}'
                """.replace("LAUNCHES", launches(directory).toString()), StandardCharsets.UTF_8);
        assertTrue(shim.toFile().setExecutable(true));
        return shim;
    }

    private static Path launches(Path directory) {
        return directory.resolve("codex-launches");
    }

    private static void storeCodexLogin() throws Exception {
        CredentialStore.create().put("openai-codex", ManagedCredential.oauth(
                "fixture-access-token", "fixture-refresh", System.currentTimeMillis() + 3_600_000,
                Map.of("accountId", "fixture-account")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void passthroughWizardUsesCodexCatalogWithoutAnApiKeyOrDirectChatEndpoint() throws Exception {
        Path shim = codexShim(project);
        String originalHome = System.getProperty("user.home");
        String originalExecutable = System.getProperty("kompile.codex.executable");
        System.setProperty("user.home", project.resolve("home").toString());
        System.setProperty("kompile.codex.executable", shim.toString());
        try {
            storeCodexLogin();
            ChatConfig chat = new ChatConfig(null, null, "main-model", "http://unused.invalid");
            chat.setChatMode("passthrough");
            chat.setPassthroughAgent("codex");
            chat.setThinking("high");
            JudgeDefaultsWizard.configure(reader("yes", "openai", "create", "native-judge-model", "", "quick", "yes", ""),
                    ChatConfig.Scope.PROJECT, project, chat);
            assertEquals(new JudgeDefaults.Selection("native-judge-model", "minimal", "openai-codex"),
                    JudgeDefaults.configured("codex", project));
            assertFalse(Files.readString(ChatProfiles.path(project)).contains("fixture-access-token"));
            assertNull(chat.getProvider());
            assertEquals("http://unused.invalid", chat.getBaseUrl());
            assertEquals("main-model", chat.getModel());
            assertEquals("high", chat.getThinking());
        } finally {
            if (originalExecutable == null) System.clearProperty("kompile.codex.executable");
            else System.setProperty("kompile.codex.executable", originalExecutable);
            System.setProperty("user.home", originalHome);
            ModelDiscoveryHttp.clearCache();
        }
    }

    private static LineReader reader(String... answers) {
        ArrayDeque<String> input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new AssertionError("Unexpected reader call: " + method);
                });
    }

    @Test void lowestLiveThinkingIsFirstEvenWhenProviderReturnsHighFirst() {
        var live = ModelDiscovery.Result.success(List.of(new LiveModelDiscovery.Model(
                "live-model", List.of("high", "minimal", "low"))), List.of());
        var capabilities = ChatProviderRegistry.find("openai").thinkingCapabilityProvider().resolve(live.models().get(0));
        assertEquals(List.of("high", "minimal", "low"),
                capabilities.options().stream().map(ThinkingCapabilityProvider.Option::value).toList(),
                () -> "Live provider capabilities: " + capabilities);
        assertEquals(List.of("", "high", "minimal", "low"),
                SetupWizard.thinkingOptions("openai", "live-model", null, null, live).stream()
                        .map(SetupWizard.ThinkingOption::value).toList());
        var options = JudgeDefaultsWizard.thinkingChoices("openai", "live-model", live);
        assertEquals("minimal", options.get(0).value());
        assertTrue(options.get(0).label().contains("default"));
        assertEquals(3, options.size());
        assertTrue(options.stream().anyMatch(o -> "high".equals(o.value())));
    }

    @Test void unsupportedModelDoesNotOfferInventedThinkingAndVendorsAreUnique() {
        var options = JudgeDefaultsWizard.thinkingChoices("custom", "unknown", null);
        assertEquals(1, options.size());
        assertEquals("", options.get(0).value());
        var vendors = JudgeDefaultsWizard.vendors();
        assertEquals(vendors.size(), vendors.stream().distinct().count());
        assertTrue(vendors.contains("openai"));
        assertTrue(vendors.contains("anthropic"));
        assertFalse(vendors.contains("openai-codex"));
    }
}
