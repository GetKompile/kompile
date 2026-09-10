package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConnectivityPolicyTest {

    @Test
    void providerPoliciesKeepRemoteAndLocalFailureBudgetsDistinct() {
        ProviderConnectivityPolicy codex =
                ProviderConnectivityPolicy.forProvider("openai-codex");
        ProviderConnectivityPolicy groq =
                ProviderConnectivityPolicy.forProvider("groq");
        ProviderConnectivityPolicy local =
                ProviderConnectivityPolicy.forProvider("kompile-local");

        assertEquals(5, codex.maxAttempts());
        assertEquals(4, groq.maxAttempts());
        assertTrue(groq.streamIdleTimeout().compareTo(codex.streamIdleTimeout()) < 0);
        assertTrue(local.streamIdleTimeout().compareTo(codex.streamIdleTimeout()) > 0,
                "local model load/prefill silence must not be treated like a remote edge stall");
        assertTrue(local.connectTimeout().compareTo(codex.connectTimeout()) < 0,
                "local TCP failures should be detected quickly");
    }

    @Test
    void boundedUtilityPolicyKeepsProviderTimeoutsButUsesOneAttempt() {
        ProviderConnectivityPolicy provider =
                ProviderConnectivityPolicy.forProvider("openai-codex");
        ProviderConnectivityPolicy judge = provider.withMaxAttempts(1);

        assertEquals(5, provider.maxAttempts(), "the main chat retry policy must stay unchanged");
        assertEquals(1, judge.maxAttempts());
        assertEquals(provider.connectTimeout(), judge.connectTimeout());
        assertEquals(provider.requestTimeout(), judge.requestTimeout());
        assertEquals(provider.streamIdleTimeout(), judge.streamIdleTimeout());
    }

    @Test
    void retriesOnlyTransientHttpAndTransportFailures() {
        ProviderConnectivityPolicy policy = ProviderConnectivityPolicy.forProvider("openai");

        assertTrue(policy.isRetryableStatus(408));
        assertTrue(policy.isRetryableStatus(429));
        assertTrue(policy.isRetryableStatus(503));
        assertTrue(policy.isRetryableStatus(529),
                "Anthropic overloaded_error must be treated as transient");
        assertFalse(policy.isRetryableStatus(400));
        assertFalse(policy.isRetryableStatus(401));
        assertTrue(policy.isRetryableFailure(new ConnectException("connection refused")));
        assertTrue(policy.isRetryableFailure(new IOException(
                "upstream connect error or disconnect/reset before headers")));
        assertTrue(policy.isRetryableFailure(new IOException("closed")),
                "java.net.http can report an abruptly closed response as bare 'closed'");
        assertFalse(policy.isRetryableFailure(new IllegalArgumentException("closed")),
                "only transport IO failures with this terse message are replay candidates");
        assertTrue(policy.isRetryableFailure(new IOException("Overloaded")));
        assertTrue(policy.isRetryableFailure(new IOException("rate_limit_error: rate limit exceeded")));
        assertFalse(policy.isRetryableFailure(new IllegalArgumentException("bad request")));
    }

    @Test
    void openAiServerSideErrorEnvelopesCountForRetries() {
        ProviderConnectivityPolicy policy = ProviderConnectivityPolicy.forProvider("openai");

        // The in-band envelope the CLI classifies: message plus the serialized
        // error node, so the typed token is visible even when the human text is
        // opaque ("Unknown error").
        String serverErrorEnvelope = "Unknown error "
                + "{\"message\":\"Unknown error\",\"type\":\"server_error\","
                + "\"param\":null,\"code\":null}";
        assertTrue(policy.isRetryableFailure(new IOException(serverErrorEnvelope)),
                "an in-band server_error envelope is the provider's 5xx equivalent "
                        + "and must consume the retry budget");
        assertTrue(policy.isRetryableFailure(new IOException(
                "api_error {\"type\":\"api_error\"}")));
        assertTrue(policy.isRetryableFailure(new IOException(
                "internal_error {\"type\":\"internal_error\"}")));
        assertTrue(policy.isRetryableFailure(new IOException("Unknown error")),
                "an opaque unknown error is never a request-shape problem");

        // Authentication and request-shape failures must stay terminal.
        assertFalse(policy.isRetryableFailure(new IOException(
                "{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\"}")));
        assertFalse(policy.isRetryableFailure(new IOException(
                "billing_hard_limit_reached {\"type\":\"insufficient_quota\"}")));
    }

    @Test
    void retryAfterSecondsAndDatesAreHonoredAndCapped() {
        ProviderConnectivityPolicy policy = new ProviderConnectivityPolicy(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(2), 3, Duration.ofMillis(10), Duration.ofSeconds(2));
        HttpHeaders seconds = HttpHeaders.of(
                Map.of("Retry-After", List.of("1")), (name, value) -> true);
        assertEquals(Duration.ofSeconds(1), policy.retryDelay(1, seconds));

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String date = ZonedDateTime.ofInstant(now.plusSeconds(5), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        HttpHeaders dated = HttpHeaders.of(
                Map.of("Retry-After", List.of(date)), (name, value) -> true);
        assertEquals(Duration.ofSeconds(5),
                ProviderConnectivityPolicy.retryAfter(dated, now).orElseThrow());
        HttpHeaders longDelay = HttpHeaders.of(
                Map.of("Retry-After", List.of("5")), (name, value) -> true);
        assertEquals(Duration.ofSeconds(2), policy.retryDelay(1, longDelay),
                "provider Retry-After must remain bounded by the CLI retry budget");
    }
}
