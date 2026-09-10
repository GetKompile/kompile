package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import com.sun.net.httpserver.HttpServer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test void wizardSavesSelectedModelAndMinimumWithoutMutatingMainChat() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/api/tags", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"models":[{"model":"judge-model","thinking":{"variants":[
                      {"value":"high","label":"High"},
                      {"value":"minimal","label":"Minimal"}]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", project.resolve("home").toString());
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            // Credential-free local discovery exercises live metadata without bypassing origin guards.
            ChatConfig chat = new ChatConfig("ollama", null, "main-model", endpoint);
            chat.setThinking("high");
            JudgeDefaultsWizard.configure(reader("yes", "ollama", "create", "judge-model", "", "quick", "yes", ""),
                    ChatConfig.Scope.GLOBAL, project, chat);
            assertEquals(new JudgeDefaults.Selection("judge-model", "minimal", "ollama"),
                    JudgeDefaults.configured("ollama", project));
            assertEquals("quick", ChatProfiles.activeJudge(project, "ollama").name());
            assertFalse(Files.exists(ChatProfiles.path(project.resolve("home"))));
            assertFalse(Files.exists(JudgeDefaults.configPath(ChatConfig.Scope.GLOBAL, project)));
            assertEquals(1, requests.get(), "reuse discovery metadata for thinking instead of fetching again");
            assertNull(authorization.get());
            assertEquals("main-model", chat.getModel());
            assertEquals("high", chat.getThinking());
            assertEquals(endpoint, chat.getBaseUrl());

            var review = ChatProfiles.captureJudge("review", "ollama", "review-model", null);
            ChatProfiles.save(project, review, false);
            ChatProfiles.activateJudge(project, "ollama", "review");
            JudgeDefaultsWizard.configure(reader("yes", "ollama", "create", "judge-model", "high", "quick", "no", ""),
                    ChatConfig.Scope.PROJECT, project, chat);
            assertEquals("minimal", ChatProfiles.list(project, "judge").stream()
                    .filter(p -> p.name().equals("quick")).findFirst().orElseThrow().thinking());
            assertEquals(review, ChatProfiles.activeJudge(project, "ollama"));
            JudgeDefaultsWizard.configure(reader("yes", "ollama", "create", "judge-model", "high", "quick", "yes", "no", ""),
                    ChatConfig.Scope.PROJECT, project, chat);
            assertEquals("high", ChatProfiles.list(project, "judge").stream()
                    .filter(p -> p.name().equals("quick")).findFirst().orElseThrow().thinking());
            assertEquals(review, ChatProfiles.activeJudge(project, "ollama"), "replacement must not switch active profiles");
        } finally {
            server.stop(0);
            ModelDiscoveryHttp.clearCache();
            System.setProperty("user.home", originalHome);
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

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void passthroughWizardUsesCodexCatalogWithoutAnApiKeyOrDirectChatEndpoint() throws Exception {
        Path shim = project.resolve("codex-fixture");
        Files.writeString(shim, """
                #!/bin/sh
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
                """, StandardCharsets.UTF_8);
        assertTrue(shim.toFile().setExecutable(true));
        String originalHome = System.getProperty("user.home");
        String originalExecutable = System.getProperty("kompile.codex.executable");
        System.setProperty("user.home", project.resolve("home").toString());
        System.setProperty("kompile.codex.executable", shim.toString());
        try {
            CredentialStore.create().put("openai-codex", ManagedCredential.oauth(
                    "fixture-access-token", "fixture-refresh", System.currentTimeMillis() + 3_600_000,
                    Map.of("accountId", "fixture-account")));
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
