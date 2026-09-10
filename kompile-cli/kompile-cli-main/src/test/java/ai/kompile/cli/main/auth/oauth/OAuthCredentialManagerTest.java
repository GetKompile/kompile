package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.common.auth.ManagedCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OAuthCredentialManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void namedOauthRefreshDoesNotActivateAccount() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("named.json"));
        store.putOAuth("test-oauth", "global", "global-access", "global-refresh", Long.MAX_VALUE, true);
        store.putOAuth("test-oauth", "pinned", "old-access", "refresh", System.currentTimeMillis() - 1000, false);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        var auth = manager.resolve("test-oauth", "oauth", "pinned");
        assertEquals("new-access", auth.token());
        assertEquals("pinned", auth.credentialName());
        assertEquals("global", store.activeCredentialName("test-oauth"));
        assertEquals("global-access", store.read("test-oauth").getAccess());
        assertThrows(java.io.IOException.class, () -> manager.resolve("test-oauth", null, "missing"));
    }

    @Test
    void namedUnauthorizedRefreshUsesPinnedRatherThanGlobalAccount() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("named-401.json"));
        store.putOAuth("test-oauth", "global", "global-access", "global-refresh", Long.MAX_VALUE, true);
        store.putOAuth("test-oauth", "pinned", "rejected", "refresh", Long.MAX_VALUE, false);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        var auth = manager.resolve("test-oauth", "oauth", "pinned");
        assertEquals("new-access", manager.refreshNamedAfterUnauthorized("test-oauth", auth).token());
        assertEquals("global", store.activeCredentialName("test-oauth"));
        assertEquals("global-access", store.read("test-oauth").getAccess());
    }

    @Test
    void resolvesExpiredOauthThroughLockedRefreshAndReusesStoredResult() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("auth.json"));
        store.putOAuth("test-oauth", "old-access", "refresh", System.currentTimeMillis() - 1_000L);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store,
                new OAuthProviderRegistry(List.of(flow)));

        OAuthProviderFlow.RequestAuth first = manager.resolve("test-oauth");
        OAuthProviderFlow.RequestAuth second = manager.resolve("test-oauth");

        assertEquals("new-access", first.token());
        assertEquals("new-access", second.token());
        assertEquals(1, flow.refreshes.get());
        assertEquals("new-access", store.read("test-oauth").getAccess());
    }

    @Test
    void refreshesProviderRejectedOauthTokenEvenWhenItsExpiryIsStillValid() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("rejected-auth.json"));
        store.putOAuth("test-oauth", "rejected-access", "refresh",
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store, new OAuthProviderRegistry(List.of(flow)));

        OAuthProviderFlow.RequestAuth refreshed =
                manager.refreshAfterUnauthorized("test-oauth", "rejected-access");

        assertEquals("new-access", refreshed.token());
        assertEquals(1, flow.refreshes.get());
        assertEquals("new-access", store.read("test-oauth").getAccess());
    }

    @Test
    void rejectedTokenRefreshReusesCredentialAlreadyRotatedByAnotherCaller() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("concurrent-refresh.json"));
        store.putOAuth("test-oauth", "newer-access", "refresh",
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store, new OAuthProviderRegistry(List.of(flow)));

        OAuthProviderFlow.RequestAuth current =
                manager.refreshAfterUnauthorized("test-oauth", "older-rejected-access");

        assertEquals("newer-access", current.token());
        assertEquals(0, flow.refreshes.get(),
                "a concurrent replacement must not rotate the refresh token twice");
    }

    @Test
    void noOpRefreshDoesNotReplayTheSameRejectedAccessToken() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("no-op-refresh.json"));
        store.putOAuth("test-oauth", "permanent-key", "",
                Long.MAX_VALUE);
        TestFlow flow = new TestFlow(false);
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store, new OAuthProviderRegistry(List.of(flow)));

        OAuthProviderFlow.RequestAuth refreshed =
                manager.refreshAfterUnauthorized("test-oauth", "permanent-key");

        assertNull(refreshed,
                "a provider that cannot rotate its token must not replay identical auth");
        assertEquals(0, flow.refreshes.get(), "non-refreshable credentials must not call the provider");
    }

    @Test
    void jwtExpiryOverridesStaleStoredDeadlineAndRefreshesProactively() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("jwt-expiry.json"));
        String payload = "{\"sub\":\"user\",\"exp\":1}";
        String token = "e30." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".signature";
        store.putOAuth("test-oauth", token, "refresh", Long.MAX_VALUE);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        assertEquals("new-access", manager.resolve("test-oauth").token());
        assertEquals(1, flow.refreshes.get());
        assertEquals("user", store.read("test-oauth").getMetadata("subject"));
        assertEquals(1, store.list().size());
    }

    @Test
    void rejectedRequestCannotRefreshOrReplayUnderAnotherSelectedAccount() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("switched-account.json"));
        store.put("test-oauth", "alice", ManagedCredential.oauth("alice-token", "alice-r", Long.MAX_VALUE,
                Map.of("subject", "alice")), true);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        var rejected = manager.resolve("test-oauth");
        assertEquals("alice", rejected.credentialName());
        store.put("test-oauth", "bob", ManagedCredential.oauth("bob-token", "bob-r", Long.MAX_VALUE,
                Map.of("subject", "bob")), true);
        assertNull(manager.refreshAfterUnauthorized("test-oauth", rejected));
        assertEquals(0, flow.refreshes.get());
        assertEquals("bob-token", store.read("test-oauth").getAccess());

        store.put("test-oauth", "alice", ManagedCredential.oauth("eve-token", "eve-r", Long.MAX_VALUE,
                Map.of("subject", "eve")), true);
        assertThrows(java.io.IOException.class, () -> manager.refreshAfterUnauthorized("test-oauth", rejected));
        assertEquals(0, flow.refreshes.get());
    }

    @Test
    void concurrentRefreshReusesSameIdentityWithoutRotatingTwice() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("same-account.json"));
        Map<String, String> identity = Map.of("subject", "alice");
        store.put("test-oauth", "alice", ManagedCredential.oauth("old", "old-r", Long.MAX_VALUE, identity), true);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        var rejected = manager.resolve("test-oauth");
        store.put("test-oauth", "alice", ManagedCredential.oauth("new", "new-r", Long.MAX_VALUE, identity), true);
        assertEquals("new", manager.refreshAfterUnauthorized("test-oauth", rejected).token());
        assertEquals(0, flow.refreshes.get());
    }

    @Test
    void apiKeyOnlyResolutionDoesNotTryRefreshingAnExpiredOauthProfile() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("api-only.json"));
        store.putOAuth("test-oauth", "old", "refresh", 1L);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        assertNull(manager.resolve("test-oauth", ManagedCredential.API_KEY));
        assertEquals(0, flow.refreshes.get());
    }

    @Test
    void rejectsExpiredCredentialWithoutRefreshInsteadOfSendingIt() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("expired.json"));
        store.putOAuth("test-oauth", "dead", "", 1L);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        assertThrows(java.io.IOException.class, () -> manager.resolve("test-oauth"));
        assertEquals(0, flow.refreshes.get());
    }

    @Test
    void rejectedRefreshKeepsIdentityAndDoesNotAddProfiles() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("identity-refresh.json"));
        store.put("test-oauth", "work", ManagedCredential.oauth("old", "refresh", Long.MAX_VALUE,
                Map.of("subject", "user", "accountId", "account")), true);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
        manager.refreshAfterUnauthorized("test-oauth", "old");
        assertEquals("user", store.read("test-oauth").getMetadata("subject"));
        assertEquals("account", store.read("test-oauth").getMetadata("accountId"));
        assertEquals("work", store.activeCredentialName("test-oauth"));
        assertEquals(1, store.list().size());
    }

    @Test
    void logoutRevokesProviderCredentialBeforeDeletingIt() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("logout-auth.json"));
        store.putOAuth("test-oauth", "access", "refresh", System.currentTimeMillis() + 60_000L);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store,
                new OAuthProviderRegistry(List.of(flow)));

        assertTrue(manager.logout("test-oauth"));
        assertEquals(1, flow.revocations.get());
        assertEquals("access", flow.revokedAccess);
        assertEquals("refresh", flow.revokedRefresh);
        assertNull(store.read("test-oauth"));
    }

    @Test
    void loginPersistsProviderOauthCredential() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("login-auth.json"));
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store,
                new OAuthProviderRegistry(List.of(flow)));

        ManagedCredential credential = manager.login(
                "test-oauth",
                new OAuthProviderFlow.LoginOptions("browser", true, null, null),
                new NoopInteraction());

        assertEquals("login-access", credential.getAccess());
        assertEquals("login-access", store.read("test-oauth").getAccess());
    }

    @Test
    void namedOauthLoginCanBeStoredWithoutReplacingTheActiveCredential() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("named-login-auth.json"));
        store.putApiKey("test-oauth", "personal", "personal-key", true);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store,
                new OAuthProviderRegistry(List.of(flow)));

        manager.login(
                "test-oauth",
                "work",
                false,
                new OAuthProviderFlow.LoginOptions("browser", true, null, null),
                new NoopInteraction());

        assertEquals("personal", store.activeCredentialName("test-oauth"));
        assertEquals("personal-key", store.read("test-oauth").getKey());
        assertEquals("login-access", store.read("test-oauth", "work").getAccess());
    }

    @Test
    void providerAndGlobalLogoutRevokeEveryNamedOauthCredential() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("multi-logout-auth.json"));
        long expires = System.currentTimeMillis() + 60_000L;
        store.putOAuth("test-oauth", "personal", "access-one", "refresh-one", expires, true);
        store.putOAuth("test-oauth", "work", "access-two", "refresh-two", expires, false);
        TestFlow flow = new TestFlow();
        OAuthCredentialManager manager = new OAuthCredentialManager(
                store,
                new OAuthProviderRegistry(List.of(flow)));

        assertEquals(2, manager.logoutAll());
        assertEquals(2, flow.revocations.get());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void transientEarlyRefreshUsesSameLiveCredentialWithoutRetryOrSwitching() throws Exception {
        for (java.io.IOException failure : List.of(new java.net.http.HttpTimeoutException("secret"),
                new java.io.EOFException("secret"), refreshHttpFailure(503), refreshHttpFailure(429))) {
            CredentialStore store = new CredentialStore(tempDir.resolve("early.json"));
            store.put("test-oauth", "work", ManagedCredential.oauth("live", "refresh",
                    System.currentTimeMillis() + 120_000L, Map.of("subject", "alice")), true);
            TestFlow flow = new TestFlow();
            flow.refreshFailure = failure;
            flow.failuresRemaining = 5;
            var auth = manager(store, flow).resolve("test-oauth");
            assertEquals("live", auth.token());
            assertEquals("work", auth.credentialName());
            assertEquals(1, flow.refreshes.get());
            assertEquals("live", store.read("test-oauth").getAccess());
            assertEquals("refresh", store.read("test-oauth").getRefresh());
        }
    }

    @Test
    void expiredTokenRefreshRetriesOnceAndPersistsSuccess() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("retry.json"));
        store.putOAuth("test-oauth", "expired", "refresh", 1L);
        TestFlow flow = new TestFlow();
        flow.refreshFailure = new java.net.ConnectException("secret");
        flow.failuresRemaining = 1;
        var manager = manager(store, flow);
        assertEquals("new-access", manager.resolve("test-oauth").token());
        assertEquals("new-access", manager.resolve("test-oauth").token());
        assertEquals(2, flow.refreshes.get());
        assertEquals("new-access", store.read("test-oauth").getAccess());
    }

    @Test
    void rejectedButUnexpiredTokenNeverFallsBackAndRefreshCanRecover() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("rejected-retry.json"));
        store.putOAuth("test-oauth", "rejected", "refresh", Long.MAX_VALUE);
        TestFlow flow = new TestFlow();
        flow.refreshFailure = new java.net.ConnectException("secret");
        flow.failuresRemaining = 1;
        var manager = manager(store, flow);
        var rejected = manager.resolve("test-oauth");
        assertEquals("new-access", manager.refreshAfterUnauthorized("test-oauth", rejected).token());
        assertEquals(2, flow.refreshes.get());
    }

    @Test
    void repeatedTransientFailureIsBoundedAndNeverSendsExpiredOrRejectedToken() throws Exception {
        for (boolean rejected : List.of(false, true)) {
            CredentialStore store = new CredentialStore(tempDir.resolve("bounded-" + rejected + ".json"));
            store.putOAuth("test-oauth", "old", "refresh", rejected ? Long.MAX_VALUE : 1L);
            TestFlow flow = new TestFlow();
            flow.refreshFailure = new java.net.ConnectException("secret");
            flow.failuresRemaining = 5;
            var manager = manager(store, flow);
            java.io.IOException error = assertThrows(java.io.IOException.class, () -> {
                if (rejected) manager.refreshAfterUnauthorized("test-oauth", manager.resolve("test-oauth"));
                else manager.resolve("test-oauth");
            });
            assertEquals(2, flow.refreshes.get());
            assertEquals(CredentialFailure.Kind.TEMPORARY, CredentialFailure.classify(error).kind());
            assertEquals(CredentialFailure.Operation.REFRESH, CredentialFailure.classify(error).operation());
            assertFalse(error.getMessage().contains("secret"));
            assertEquals("old", store.read("test-oauth").getAccess());
        }
    }

    @Test
    void terminalRefreshFailuresNeverRetryOrFallBackEvenWithLiveToken() throws Exception {
        for (java.io.IOException failure : List.of(refreshHttpFailure(400), refreshHttpFailure(401),
                refreshHttpFailure(403), new javax.net.ssl.SSLHandshakeException("secret"),
                new java.io.IOException("secret protocol error"))) {
            CredentialStore store = new CredentialStore(tempDir.resolve("terminal.json"));
            store.putOAuth("test-oauth", "live", "refresh", System.currentTimeMillis() + 120_000L);
            TestFlow flow = new TestFlow();
            flow.refreshFailure = failure;
            flow.failuresRemaining = 5;
            assertThrows(java.io.IOException.class, () -> manager(store, flow).resolve("test-oauth"));
            assertEquals(1, flow.refreshes.get());
        }
    }

    @Test
    void expiredRateLimitedRefreshDoesNotRetryOrFallBack() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("rate-limit.json"));
        store.putOAuth("test-oauth", "expired", "refresh", 1L);
        TestFlow flow = new TestFlow();
        flow.refreshFailure = refreshHttpFailure(429);
        flow.failuresRemaining = 5;
        var error = assertThrows(java.io.IOException.class, () -> manager(store, flow).resolve("test-oauth"));
        assertEquals(CredentialFailure.Kind.RATE_LIMITED, CredentialFailure.classify(error).kind());
        assertEquals(1, flow.refreshes.get());
    }

    @Test
    void interruptedRefreshPreservesCancellationAndNeverFallsBack() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("interrupt.json"));
        store.putOAuth("test-oauth", "live", "refresh", System.currentTimeMillis() + 120_000L);
        TestFlow flow = new TestFlow();
        flow.refreshFailure = new java.nio.channels.FileLockInterruptionException();
        flow.failuresRemaining = 5;
        try {
            var error = assertThrows(java.io.IOException.class, () -> manager(store, flow).resolve("test-oauth"));
            assertEquals(CredentialFailure.Kind.INTERRUPTED, CredentialFailure.classify(error).kind());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, flow.refreshes.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void corruptStoreHasSafeStorageDiagnosticsAndNeverCallsProvider() throws Exception {
        Path path = tempDir.resolve("corrupt.json");
        java.nio.file.Files.writeString(path, "secret-invalid-json");
        TestFlow flow = new TestFlow();
        var error = assertThrows(java.io.IOException.class,
                () -> manager(new CredentialStore(path), flow).resolve("test-oauth"));
        assertEquals(CredentialFailure.Operation.STORE, CredentialFailure.classify(error).operation());
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
        assertEquals(0, flow.refreshes.get());
    }

    @Test
    void possiblyConsumedRefreshTokenIsNeverAutomaticallyReplayed() throws Exception {
        for (java.io.IOException failure : List.of(new java.io.EOFException("lost response"),
                new java.net.http.HttpTimeoutException("lost response"),
                new java.net.SocketException("reset"), refreshHttpFailure(504))) {
            CredentialStore store = new CredentialStore(tempDir.resolve("ambiguous.json"));
            store.putOAuth("test-oauth", "expired", "possibly-consumed", 1L);
            TestFlow flow = new TestFlow();
            flow.refreshFailure = failure;
            flow.failuresRemaining = 1;
            assertThrows(java.io.IOException.class, () -> manager(store, flow).resolve("test-oauth"));
            assertEquals(1, flow.refreshes.get(), "a second use could revoke a rotated token family");
            assertEquals("possibly-consumed", store.read("test-oauth").getRefresh());
        }
    }

    @Test
    void refreshValidationFailuresAreNotStorageErrorsAndDoNotPersist() throws Exception {
        for (ManagedCredential result : java.util.Arrays.asList(null, ManagedCredential.apiKey("wrong-type"),
                ManagedCredential.oauth("expired-result", "new-r", 1L),
                ManagedCredential.oauth("other-user", "new-r", Long.MAX_VALUE, Map.of("subject", "bob")))) {
            for (boolean rejected : List.of(false, true)) {
                CredentialStore store = new CredentialStore(tempDir.resolve("invalid-" + rejected + ".json"));
                store.put("test-oauth", ManagedCredential.oauth("old", "old-r",
                        System.currentTimeMillis() + 120_000L, Map.of("subject", "alice")));
                TestFlow flow = new TestFlow();
                flow.refreshResponse = ignored -> result;
                var manager = manager(store, flow);
                var error = assertThrows(java.io.IOException.class, () -> {
                    if (rejected) manager.refreshAfterUnauthorized("test-oauth", "old");
                    else manager.resolve("test-oauth");
                });
                assertEquals(CredentialFailure.Operation.REFRESH, CredentialFailure.classify(error).operation());
                assertEquals(1, flow.refreshes.get());
                assertEquals("old", store.read("test-oauth").getAccess());
                assertEquals("old-r", store.read("test-oauth").getRefresh());
            }
        }
    }

    @Test
    void nearExpiryTokenDoesNotQualifyForFallback() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("near-expiry.json"));
        store.putOAuth("test-oauth", "nearly-expired", "refresh", System.currentTimeMillis() + 30_000L);
        TestFlow flow = new TestFlow();
        flow.refreshFailure = new java.net.http.HttpTimeoutException("secret");
        flow.failuresRemaining = 5;
        assertThrows(java.io.IOException.class, () -> manager(store, flow).resolve("test-oauth"));
        assertEquals(1, flow.refreshes.get());
    }

    @Test
    void concurrentCallersShareOneRecoveryAndPersistRotatedRefreshToken() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("parallel-retry.json"));
        store.putOAuth("test-oauth", "old", "old-r", 1L);
        TestFlow flow = new TestFlow();
        flow.refreshFailure = new java.net.UnknownHostException("secret");
        flow.failuresRemaining = 1;
        flow.refreshResponse = ignored -> ManagedCredential.oauth("new", "new-r", Long.MAX_VALUE);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> resolve = () -> {
                start.await();
                return manager(new CredentialStore(store.getAuthPath()), flow).resolve("test-oauth").token();
            };
            var first = executor.submit(resolve);
            var second = executor.submit(resolve);
            start.countDown();
            assertEquals("new", first.get(5, TimeUnit.SECONDS));
            assertEquals("new", second.get(5, TimeUnit.SECONDS));
            assertEquals(2, flow.refreshes.get());
            assertEquals("new-r", store.read("test-oauth").getRefresh());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void chatCompletesWithoutManualResendThroughRealManagerRecovery() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            boolean early = scenario == 0;
            boolean rejected = scenario == 2;
            CredentialStore store = new CredentialStore(tempDir.resolve("chat-" + scenario + ".json"));
            store.putOAuth("test-oauth", "old", "old-r",
                    early ? System.currentTimeMillis() + 120_000L : rejected ? Long.MAX_VALUE : 1L);
            TestFlow flow = new TestFlow();
            flow.refreshFailure = early ? new java.net.http.HttpTimeoutException("secret")
                    : new java.net.ConnectException("secret");
            flow.failuresRemaining = early ? 5 : 1;
            var manager = manager(store, flow);
            var requests = new AtomicInteger();
            var tokens = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                int count = requests.incrementAndGet();
                tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                boolean unauthorized = rejected && count == 1;
                String body = unauthorized ? "{}" : "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", unauthorized ? "application/json" : "text/event-stream");
                exchange.sendResponseHeaders(unauthorized ? 401 : 200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            var config = new ai.kompile.cli.main.chat.config.ChatConfig("openai-codex", null, "test",
                    "http://127.0.0.1:" + server.getAddress().getPort()) {
                @Override public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                    try { return manager.resolve("test-oauth"); }
                    catch (java.io.IOException e) { throw new AuthenticationException("openai-codex", e); }
                }
                @Override public OAuthProviderFlow.RequestAuth refreshRequestAuthAfterUnauthorized(
                        OAuthProviderFlow.RequestAuth auth) {
                    try { return manager.refreshAfterUnauthorized("test-oauth", auth); }
                    catch (java.io.IOException e) { throw new AuthenticationException("openai-codex", e); }
                }
            };
            try (var client = new ai.kompile.cli.main.chat.config.DirectLlmClient(config, OAuthSupport.MAPPER)) {
                client.setOutputConsumer(ignored -> {});
                var result = client.streamChat("hello", "system", null, null);
                assertFalse(result.failed, result.text);
                assertEquals("ok", result.text);
                assertEquals(early ? 1 : 2, flow.refreshes.get());
                assertEquals(rejected ? List.of("Bearer old", "Bearer new-access")
                        : List.of(early ? "Bearer old" : "Bearer new-access"), tokens);
                assertEquals(rejected ? 2 : 1, requests.get());
            } finally {
                server.stop(0);
            }
        }
    }

    private static OAuthCredentialManager manager(CredentialStore store, TestFlow flow) {
        return new OAuthCredentialManager(store, new OAuthProviderRegistry(List.of(flow)));
    }

    private static java.io.IOException refreshHttpFailure(int status) {
        return assertThrows(java.io.IOException.class, () -> OAuthSupport.requireSuccess(
                new OAuthSupport.Response(status, "{\"error\":\"invalid_grant\"}"), "synthetic"));
    }

    private static final class TestFlow implements OAuthProviderFlow {
        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicInteger revocations = new AtomicInteger();
        private final boolean rotateAccessToken;
        private java.io.IOException refreshFailure;
        private java.util.function.Function<ManagedCredential, ManagedCredential> refreshResponse;
        private int failuresRemaining;
        private String revokedAccess;
        private String revokedRefresh;

        private TestFlow() {
            this(true);
        }

        private TestFlow(boolean rotateAccessToken) {
            this.rotateAccessToken = rotateAccessToken;
        }

        @Override
        public String providerId() {
            return "test-oauth";
        }

        @Override
        public String displayName() {
            return "Test OAuth";
        }

        @Override
        public List<LoginMethod> loginMethods() {
            return List.of(new LoginMethod("browser", "Browser"));
        }

        @Override
        public String defaultLoginMethod() {
            return "browser";
        }

        @Override
        public ManagedCredential login(LoginOptions options, Interaction interaction) {
            return ManagedCredential.oauth(
                    "login-access",
                    "login-refresh",
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        }

        @Override
        public ManagedCredential refresh(ManagedCredential credential) throws java.io.IOException {
            refreshes.incrementAndGet();
            if (failuresRemaining-- > 0) throw refreshFailure;
            if (refreshResponse != null) return refreshResponse.apply(credential);
            if (!rotateAccessToken) return credential;
            return ManagedCredential.oauth(
                    "new-access",
                    credential.getRefresh(),
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        }

        @Override
        public boolean revoke(String accessToken, String refreshToken) {
            revocations.incrementAndGet();
            revokedAccess = accessToken;
            revokedRefresh = refreshToken;
            return true;
        }

        @Override
        public RequestAuth toRequestAuth(ManagedCredential credential) {
            return RequestAuth.oauth(
                    credential.getAccess(),
                    "https://provider.test/v1",
                    Map.of("Authorization", "Bearer " + credential.getAccess()));
        }
    }

    private static final class NoopInteraction implements OAuthProviderFlow.Interaction {
        @Override
        public void info(String message) {
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
        }

        @Override
        public void deviceCode(
                String userCode,
                URI verificationUri,
                Integer intervalSeconds,
                Integer expiresInSeconds) {
        }

        @Override
        public String prompt(String message) {
            return "";
        }
    }
}
