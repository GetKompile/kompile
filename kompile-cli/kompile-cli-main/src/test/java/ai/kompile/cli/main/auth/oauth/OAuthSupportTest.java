package ai.kompile.cli.main.auth.oauth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OAuthSupportTest {
    @Test
    void pkceChallengeMatchesVerifierSha256() throws Exception {
        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        assertEquals(expected, pkce.challenge());
        assertTrue(pkce.verifier().matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void parsesRedirectQueryCodeStateAndManualCodeFormats() {
        OAuthSupport.AuthorizationInput redirect = OAuthSupport.parseAuthorizationInput(
                "http://localhost/callback?code=abc%20123&state=state-value");
        assertEquals("abc 123", redirect.code());
        assertEquals("state-value", redirect.state());

        OAuthSupport.AuthorizationInput hash = OAuthSupport.parseAuthorizationInput("code-value#state-value");
        assertEquals("code-value", hash.code());
        assertEquals("state-value", hash.state());

        assertEquals("bare-code", OAuthSupport.parseAuthorizationInput("bare-code").code());
    }

    @Test
    void loopbackCallbackRejectsWrongStateAndAcceptsExpectedState() throws Exception {
        try (OAuthSupport.CallbackServer callback = OAuthSupport.CallbackServer.start(
                "127.0.0.1", "127.0.0.1", 0, "/callback", "expected")) {
            HttpClient client = HttpClient.newHttpClient();
            URI wrong = URI.create(callback.redirectUri() + "?code=wrong&state=unexpected");
            HttpResponse<String> wrongResponse = client.send(
                    HttpRequest.newBuilder(wrong).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, wrongResponse.statusCode());

            URI correct = URI.create(callback.redirectUri() + "?code=accepted&state=expected");
            HttpResponse<String> correctResponse = client.send(
                    HttpRequest.newBuilder(correct).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, correctResponse.statusCode());

            OAuthSupport.AuthorizationInput result = callback.await(Duration.ofSeconds(2));
            assertEquals("accepted", result.code());
            assertEquals("expected", result.state());
        }
    }

    @Test
    void devicePollerHonorsPendingAndServerSlowDownInterval() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        List<Long> sleeps = new ArrayList<>();
        OAuthSupport.DeviceCodePoller poller = new OAuthSupport.DeviceCodePoller(
                millis -> {
                    sleeps.add(millis);
                    clock.addAndGet(millis);
                },
                clock::get);
        AtomicInteger attempts = new AtomicInteger();

        String token = poller.poll(1, 60, false, () -> switch (attempts.incrementAndGet()) {
            case 1 -> OAuthSupport.PollResult.pending();
            case 2 -> OAuthSupport.PollResult.slowDown(3);
            default -> OAuthSupport.PollResult.complete("token");
        });

        assertEquals("token", token);
        assertEquals(List.of(1_000L, 3_000L), sleeps);
    }
}
