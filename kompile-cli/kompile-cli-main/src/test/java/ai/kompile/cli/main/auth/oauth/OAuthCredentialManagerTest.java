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

    private static final class TestFlow implements OAuthProviderFlow {
        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicInteger revocations = new AtomicInteger();
        private String revokedAccess;
        private String revokedRefresh;

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
        public ManagedCredential refresh(ManagedCredential credential) {
            refreshes.incrementAndGet();
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
