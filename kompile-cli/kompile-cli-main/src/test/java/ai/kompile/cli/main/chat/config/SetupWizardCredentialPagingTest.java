package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.CredentialStore;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SetupWizardCredentialPagingTest {
    @TempDir Path home;
    private String previousHome;
    private CredentialStore store;

    @BeforeEach
    void createAccounts() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        store = CredentialStore.create();
        for (int i = 1; i <= 25; i++) {
            store.putApiKey("openai", String.format("account-%02d", i), "test-key-" + i, i == 1);
        }
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void pagesSessionAccountsAndCanSelectAgainAfterResume() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = terminal(output)) {
            var selected = SetupWizard.authenticateSession(reader(terminal, "n", "n", "p", "13"),
                    "openai", SetupWizard.AuthMethod.API_KEY);
            assertNotNull(selected);
            assertEquals("account-13", selected.credentialName());
            ChatConfig config = new ChatConfig("openai", null, "test-model", null);
            config.setCredentialName(selected.credentialName());
            config.bindSession("paged-auth");

            ChatConfig resumed = ChatConfig.loadSession("paged-auth");
            assertEquals("account-13", resumed.getCredentialName());
            assertEquals("test-key-13", resumed.getApiKey());
            int standaloneOutputSize = output.size();
            List<List<String>> modalPages = new ArrayList<>();
            var replacement = SetupWizard.authenticateSession(reader(terminal, "n", "n", "25"),
                    "openai", SetupWizard.AuthMethod.API_KEY, (title, lines) -> modalPages.add(lines));
            assertNotNull(replacement);
            resumed.setCredentialName(replacement.credentialName());
            assertEquals("test-key-25", resumed.getApiKey());
            assertEquals("account-01", store.activeCredentialName("openai"));
            assertEquals(standaloneOutputSize, output.size(), "Modal paging must not clear or reset the chat terminal");
            assertEquals(3, modalPages.size());
            for (var page : modalPages) {
                assertPagesBounded(String.join("\n", page));
            }
            assertTrue(String.join("\n", modalPages.get(2)).contains("Showing 25-26 of 26"));

            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Showing 1-12 of 26"));
            assertTrue(rendered.contains("Showing 13-24 of 26"));
            assertTrue(rendered.contains("Showing 25-26 of 26"));
            assertTrue(rendered.contains("Sign in / add another credential"));
            assertTrue(rendered.contains("\033[r"));
            assertTrue(rendered.contains("\033[?1000l"));
            assertPagesBounded(rendered);
        }
    }

    @Test
    void pagesGlobalAuthenticationAndSelectsBeyondFirstPage() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = terminal(output)) {
            assertNotNull(SetupWizard.authenticate(reader(terminal, "n", "13"),
                    "openai", SetupWizard.AuthMethod.API_KEY));
            assertEquals("account-13", store.activeCredentialName("openai"));
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Showing 13-24 of 25"));
            assertPagesBounded(rendered);
            int standaloneOutputSize = output.size();
            List<List<String>> modalPages = new ArrayList<>();
            assertNotNull(SetupWizard.authenticate(reader(terminal, "n", "n", "25"),
                    "openai", SetupWizard.AuthMethod.API_KEY, (title, lines) -> modalPages.add(lines)));
            assertEquals("account-25", store.activeCredentialName("openai"));
            assertEquals(standaloneOutputSize, output.size());
            assertEquals(3, modalPages.size());
            modalPages.forEach(page -> assertPagesBounded(String.join("\n", page)));
        }
    }

    @Test
    void showsAllAccountsOnTallTerminalAndEnterReusesLastSelectionAcrossResume() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = terminal(output)) {
            terminal.setSize(new Size(120, 60));
            var selected = SetupWizard.authenticateSession(reader(terminal, "25"),
                    "openai", SetupWizard.AuthMethod.API_KEY);
            assertEquals("account-25", selected.credentialName());
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Showing 1-26 of 26"));
            for (int i = 1; i <= 25; i++) assertTrue(rendered.contains(String.format("account-%02d", i)));
            assertEquals("account-25", CredentialStore.create().defaultCredentialName("openai"));
            assertEquals("account-25", SetupWizard.authenticateSession(reader(terminal, ""),
                    "openai", SetupWizard.AuthMethod.API_KEY).credentialName());

            ChatConfig config = new ChatConfig("openai", null, "test-model", null);
            assertEquals("test-key-25", config.getApiKey(), "Startup must use last account even before binding");
            config.bindSession("remembered");
            assertEquals("account-25", config.getCredentialName());
            store.recordUsed("openai", "account-20");
            ChatConfig resumed = ChatConfig.loadSession("remembered");
            assertEquals("account-25", resumed.getCredentialName());
            assertEquals("test-key-25", resumed.getApiKey());
            assertEquals("account-25", SetupWizard.authenticateSession(reader(terminal, ""),
                    "openai", SetupWizard.AuthMethod.API_KEY, (title, page) -> {}).credentialName());
            assertEquals("account-01", store.activeCredentialName("openai"));
        }
    }

    @Test
    void expiredPinnedCredentialIsPurgedOnResumeWithoutSwitchingAccounts() throws Exception {
        store.putOAuth("openai-codex", "expired", "dead-access", "", 1L, true);
        store.putOAuth("openai-codex", "valid", "live-access", "live-refresh", Long.MAX_VALUE, false);
        ChatConfig config = new ChatConfig("openai-codex", null, "test-model", null);
        config.setCredentialName("expired");
        config.bindSession("expired-session");
        ChatConfig resumed = ChatConfig.loadSession("expired-session");
        assertThrows(ChatConfig.AuthenticationException.class, resumed::resolveRequestAuth);
        assertEquals("expired", resumed.getCredentialName());
        assertNull(CredentialStore.create().read("openai-codex", "expired"));
        assertNotNull(store.read("openai-codex", "valid"));
    }

    @Test
    void credentialPickerPurgesExpiredEntriesBeforeChoosingDefault() throws Exception {
        store.putOAuth("openai-codex", "expired", "dead-access", "", 1L, true);
        store.putOAuth("openai-codex", "renewable", "old-access", "refresh", 1L, false);
        store.putOAuth("openai-codex", "valid", "valid-access", "", Long.MAX_VALUE, false);
        store.recordUsed("openai-codex", "renewable");
        var output = new ByteArrayOutputStream();
        try (var terminal = terminal(output)) {
            assertEquals("valid", SetupWizard.authenticateSession(reader(terminal, ""),
                    "openai", SetupWizard.AuthMethod.OAUTH).credentialName());
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("expired —"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("renewable"));
            assertNull(CredentialStore.create().read("openai-codex", "expired"));
            assertNotNull(store.read("openai-codex", "renewable"));
        }
    }

    @Test
    void allExpiredAccountsLeaveOnlyAnExplicitLoginOption() throws Exception {
        store.putOAuth("openai-codex", "renewable", "old-access", "refresh", 1L, true);
        var output = new ByteArrayOutputStream();
        try (var terminal = terminal(output)) {
            assertNull(SetupWizard.authenticateSession(reader(terminal, "q"), "openai", SetupWizard.AuthMethod.OAUTH));
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertFalse(rendered.contains("renewable"));
            assertFalse(rendered.contains("[default]"));
            assertTrue(rendered.contains("Showing 1-1 of 1"));
            assertTrue(rendered.contains("Sign in / add another credential"));
            assertNotNull(store.read("openai-codex", "renewable"));
        }
    }

    @Test
    void cancellingLastPageDoesNotChangeCredentials() throws Exception {
        try (var terminal = terminal(new ByteArrayOutputStream())) {
            assertNull(SetupWizard.authenticateSession(reader(terminal, "n", "n", "q"),
                    "openai", SetupWizard.AuthMethod.API_KEY));
            assertEquals(25, store.list("openai").size());
            assertEquals("account-01", store.activeCredentialName("openai"));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void resumedCredentialPickerShowsExpiryInStandaloneAndModalRendering(boolean global, boolean modal) throws Exception {
        long expiry = java.time.Instant.parse("2099-07-08T09:10:11Z").toEpochMilli();
        store.putOAuth("openai-codex", "dated", "fixture-access", "fixture-refresh", expiry, true);
        store.putOAuth("openai-codex", "renewable", "fixture-old-access", "fixture-old-refresh", 1L, false);
        store.putOAuth("openai-codex", "permanent", "fixture-permanent-access", "", Long.MAX_VALUE, false);
        ChatConfig config = new ChatConfig("openai-codex", null, "test-model", null);
        config.setAuthenticationMethod("oauth");
        config.setCredentialName("dated");
        config.bindSession("expiry-label");
        ChatConfig resumed = ChatConfig.loadSession("expiry-label");
        store.recordUsed("openai-codex", "renewable"); // Hidden last-used entries cannot become the picker default.
        String vendor = SetupWizard.vendorForProvider(resumed.getProvider());
        var output = new ByteArrayOutputStream();
        List<String> modalLines = new ArrayList<>();
        try (var terminal = terminal(output)) {
            terminal.setSize(new Size(220, 24));
            java.util.function.BiConsumer<String, List<String>> renderer = modal
                    ? (title, lines) -> modalLines.addAll(lines) : null;
            // Cancel after rendering; labels must not require a token refresh/network call.
            assertNull(global ? SetupWizard.authenticate(reader(terminal, "q"), vendor, SetupWizard.AuthMethod.OAUTH, renderer)
                    : SetupWizard.authenticateSession(reader(terminal, "q"), vendor, SetupWizard.AuthMethod.OAUTH, renderer));
            String rendered = modal ? String.join("\n", modalLines) : output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("expires 2099-07-08T09:10:11Z"), rendered);
            assertFalse(rendered.contains("renewable"), rendered);
            assertFalse(rendered.contains("1970-01-01"), rendered);
            assertTrue(rendered.contains(global ? "Showing 1-2 of 2" : "Showing 1-3 of 3"), rendered);
            assertNotNull(store.read("openai-codex", "renewable"));
            assertTrue(rendered.contains("non-expiring"), rendered);
            assertTrue(rendered.contains("[default]"), rendered);
            assertFalse(rendered.contains("fixture-"), "expiry rendering must not expose token material");
            assertEquals("dated", resumed.getCredentialName());
        }
    }

    static java.util.stream.Stream<Arguments> apiKeyRoutes() {
        return SetupWizard.providerPickerOrder().stream().flatMap(vendor ->
                SetupWizard.authMethodsForPicker(vendor).stream()
                        .filter(method -> method == SetupWizard.AuthMethod.API_KEY
                                || method == SetupWizard.AuthMethod.API_KEY_CREDITS)
                        .flatMap(method -> java.util.stream.Stream.of(
                                Arguments.of(vendor, method, false), Arguments.of(vendor, method, true))));
    }

    @ParameterizedTest
    @MethodSource("apiKeyRoutes")
    void everyApiKeyProviderReusesLastCredentialForModelSelectionAndResume(
            String vendor, SetupWizard.AuthMethod method, boolean global) throws Exception {
        String provider = SetupWizard.resolveProviderForAuth(vendor, method);
        store.delete(provider); // Only this test's isolated fixture accounts.
        store.putApiKey(provider, "first", "fixture-first-key", true);
        store.putApiKey(provider, "last-used", "fixture-last-key", false);
        store.recordUsed(provider, "last-used");
        try (var terminal = terminal(new ByteArrayOutputStream())) {
            // One Enter accepts the remembered account; any API-key prompt fails this reader.
            var authentication = global
                    ? SetupWizard.authenticate(reader(terminal, ""), vendor, method)
                    : SetupWizard.authenticateSession(reader(terminal, ""), vendor, method);
            assertNotNull(authentication);
            assertNull(authentication.apiKey());
            assertEquals(global ? "last-used" : "first", store.activeCredentialName(provider));
            ChatConfig config = new ChatConfig(provider, null, "first-model", null);
            config.setAuthenticationScope(global ? "global" : "session");
            config.setAuthenticationMethod(method.configValue());
            config.setCredentialName(authentication.credentialName());
            config.bindSession("reuse-api-key");
            ChatConfig resumed = ChatConfig.loadSession("reuse-api-key");
            resumed.setModel("different-model");
            assertEquals("fixture-last-key", resumed.getApiKey());
            assertFalse(resumed.resolveRequestAuth().oauth());
            assertEquals(2, store.list(provider).size(), "model selection must not create duplicate keys");
        }
    }

    @Test
    void newGlobalKeyIsReusableBeforeModelSetupIsSavedAndDoesNotReplaceOAuth() throws Exception {
        assertNull(ChatProviderRegistry.environmentVariable("custom"));
        store.putOAuth("custom", "subscription", "fixture-access", "fixture-refresh", Long.MAX_VALUE, true);
        try (var terminal = terminal(new ByteArrayOutputStream())) {
            var first = SetupWizard.authenticate(reader(terminal, "fixture-new-key"),
                    "custom", SetupWizard.AuthMethod.API_KEY);
            assertNotNull(first);
            assertNull(first.apiKey(), "model discovery should resolve the saved key, not a transient secret");
            String name = store.defaultCredentialName("custom");
            assertEquals("fixture-new-key", store.resolveApiKey("custom", name, ignored -> null));
            assertTrue(store.read("custom", "subscription").isOAuth());

            // No ChatConfig save/commit between the two model setup attempts.
            assertNotNull(SetupWizard.authenticate(reader(terminal, ""), "custom", SetupWizard.AuthMethod.API_KEY));
            assertEquals(name, store.activeCredentialName("custom"));
            assertEquals(2, store.list("custom").size());
        }
    }

    @Test
    void sessionKeyIsSavedOnceAndAddingAnotherRemainsExplicit() throws Exception {
        try (var terminal = terminal(new ByteArrayOutputStream())) {
            var first = SetupWizard.authenticateSession(reader(terminal, "fixture-first"),
                    "custom", SetupWizard.AuthMethod.API_KEY);
            assertNotNull(first);
            var second = SetupWizard.authenticateSession(reader(terminal, "2", "fixture-second"),
                    "custom", SetupWizard.AuthMethod.API_KEY);
            assertNotEquals(first.credentialName(), second.credentialName());
            var reused = SetupWizard.authenticateSession(reader(terminal, ""),
                    "custom", SetupWizard.AuthMethod.API_KEY);
            assertEquals(second.credentialName(), reused.credentialName());
            assertEquals(first.credentialName(), store.activeCredentialName("custom"));
            assertEquals(2, store.list("custom").size());
        }
    }

    @Test
    void environmentKeyEnterMeansReuseWhileDeclineAndCancellationStillWork() throws Exception {
        try (var terminal = terminal(new ByteArrayOutputStream())) {
            assertEquals("fixture-environment-key", SetupWizard.promptApiKey(reader(terminal, ""),
                    "openai", ignored -> "fixture-environment-key"));
            assertEquals("fixture-replacement", SetupWizard.promptApiKey(reader(terminal, "n", "fixture-replacement"),
                    "openai", ignored -> "fixture-environment-key"));
            LineReader cancelled = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                    new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                        throw new org.jline.reader.UserInterruptException("");
                    });
            assertNull(SetupWizard.promptApiKey(cancelled, "openai", ignored -> "fixture-environment-key"));
        }
    }

    private static void assertPagesBounded(String rendered) {
        for (String page : rendered.split("\033\\[2J\033\\[H")) {
            assertTrue(page.lines().filter(line -> line.contains("account-")
                    || line.contains("Sign in / add another credential")).count() <= 12);
        }
        assertFalse(rendered.contains("test-key-"));
    }

    private static LineDisciplineTerminal terminal(ByteArrayOutputStream output) throws Exception {
        var terminal = new LineDisciplineTerminal("credential-paging", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(100, 18));
        return terminal;
    }

    private static LineReader reader(Terminal terminal, String... answers) {
        var input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTerminal" -> terminal;
                    case "readLine" -> {
                        assertFalse(input.isEmpty(), "Unexpected extra prompt");
                        yield input.removeFirst();
                    }
                    default -> throw new AssertionError("Unexpected reader call: " + method);
                });
    }
}
