package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.auth.OAuthCredentialLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transports only: expiry storage must not duplicate lifecycle refresh margins. */
class OAuthExpiryTest {
    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "xai", "radius", "google", "microsoft",
            "reddit", "atlassian", "openai-codex"})
    void refreshStoresFullProviderTtlAndPreservesOrRotatesRefreshToken(String provider) throws Exception {
        for (long ttlSeconds : new long[]{30L, 3600L}) {
            for (boolean rotate : new boolean[]{false, true}) {
                String body = "{\"access_token\":\"synthetic-access\",\"expires_in\":" + ttlSeconds
                        + (rotate ? ",\"refresh_token\":\"rotated-refresh\"" : "") + "}";
                AtomicInteger requests = new AtomicInteger();
                OAuthProviderFlow flow = flow(provider, request -> {
                    assertEquals(1, requests.incrementAndGet(), "Only one token request expected");
                    return new OAuthSupport.Response(200, body);
                });
                ManagedCredential previous = ManagedCredential.oauth("old-access", "old-refresh", 1L,
                        Map.of("clientId", "synthetic-client", "tenant", "synthetic-tenant",
                                "gateway", "https://radius.example.test", "accountId", "synthetic-account"));
                long before = System.currentTimeMillis();
                ManagedCredential refreshed = flow.refresh(previous);
                long after = System.currentTimeMillis();

                // Bracket the actual call, not a tight elapsed-time tolerance: scheduling pauses are allowed.
                long ttlMillis = ttlSeconds * 1000L;
                assertTrue(refreshed.getExpires() >= before + ttlMillis,
                        provider + " must retain the full advertised TTL");
                assertTrue(refreshed.getExpires() <= after + ttlMillis,
                        provider + " must not extend the advertised TTL");
                assertEquals(rotate ? "rotated-refresh" : "old-refresh", refreshed.getRefresh());
                assertEquals("synthetic-access", refreshed.getAccess());
                assertEquals("old-refresh", previous.getRefresh());
                assertEquals(1, requests.get());
            }
        }
    }

    @Test
    void copilotStoresExactAbsoluteExpiryAndPreservesGithubToken() throws Exception {
        long expiresAtSeconds = Long.MAX_VALUE / 1000L - 1L;
        AtomicInteger requests = new AtomicInteger();
        GitHubCopilotOAuthFlow flow = new GitHubCopilotOAuthFlow(request -> {
            assertEquals(1, requests.incrementAndGet());
            return new OAuthSupport.Response(200,
                    "{\"token\":\"synthetic-session\",\"expires_at\":" + expiresAtSeconds + "}");
        }, noPolling());

        ManagedCredential refreshed = flow.refresh(
                ManagedCredential.oauth("old-session", "synthetic-github-token", 1L));

        assertEquals(expiresAtSeconds * 1000L, refreshed.getExpires());
        assertEquals("synthetic-github-token", refreshed.getRefresh());
        assertEquals("synthetic-session", refreshed.getAccess());
        assertEquals(1, requests.get());
    }

    @Test
    void confirmedExpiredLegacyCodexTokenRequiresReauthentication() {
        IOException failure = assertThrows(OAuthCredentialLifecycle.ReauthenticationRequiredException.class,
                () -> OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(legacyToken("1")));
        assertTrue(failure.getMessage().contains("kompile auth login openai-codex"));
    }

    @Test
    void malformedLegacyCodexTokenIsNotClassifiedAsConfirmedExpiry() {
        IOException failure = assertThrows(IOException.class,
                () -> OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken("not-a-jwt"));
        assertFalse(failure instanceof OAuthCredentialLifecycle.ReauthenticationRequiredException);
    }

    @Test
    void liveLegacyCodexTokenStillResolves() throws Exception {
        assertEquals("synthetic-account", OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(
                legacyToken(Long.toString(Long.MAX_VALUE))).headers().get("chatgpt-account-id"));
    }

    private static String legacyToken(String expiry) {
        String payload = "{\"exp\":" + expiry
                + ",\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"synthetic-account\"}}";
        return "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8)) + ".synthetic-signature";
    }

    private static OAuthSupport.DeviceCodePoller noPolling() {
        return new OAuthSupport.DeviceCodePoller(millis -> {
            throw new AssertionError("Refresh must not poll or sleep");
        }, System::currentTimeMillis);
    }

    private static OAuthProviderFlow flow(String provider, OAuthSupport.HttpTransport transport) {
        return switch (provider) {
            case "anthropic" -> new AnthropicOAuthFlow(transport);
            case "xai" -> new XaiOAuthFlow(transport, noPolling());
            case "radius" -> new RadiusOAuthFlow(transport, noPolling());
            case "google" -> new GoogleOAuthFlow(transport, "synthetic-client");
            case "microsoft" -> new MicrosoftOAuthFlow(transport, noPolling(), "synthetic-client");
            case "reddit" -> new RedditOAuthFlow(transport, "synthetic-client", "synthetic-secret");
            case "atlassian" -> new AtlassianOAuthFlow(transport, "synthetic-client", "synthetic-secret");
            case "openai-codex" -> new OpenAiCodexOAuthFlow(transport, noPolling());
            default -> throw new AssertionError("Unexpected provider: " + provider);
        };
    }
}
