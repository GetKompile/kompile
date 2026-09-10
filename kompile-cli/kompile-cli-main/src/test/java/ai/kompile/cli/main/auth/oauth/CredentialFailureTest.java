package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.main.chat.config.ChatConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static ai.kompile.cli.main.auth.oauth.CredentialFailure.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely synthetic: no credential store, environment changes, or network calls. */
class CredentialFailureTest {
    private static final String SECRET = "reflected-secret-access-refresh-client-token";

    @Test
    void terminalRefreshCodesIncludeHttp400AndNestedVendorErrors() {
        for (String code : List.of("invalid_grant", "invalid_token", "expired_token", "token_expired",
                "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated",
                "refresh_token_revoked", "invalid_refresh_token", "refresh_token_invalid")) {
            assertFailure(http(400, "{\"error\":\"" + code + "\"}"), REAUTH_REQUIRED, 400);
            assertFailure(http(400, "{\"error\":{\"code\":\"" + code + "\"}}"), REAUTH_REQUIRED, 400);
        }
        assertFailure(http(400, "{\"error\":{\"type\":\"invalid_grant\"}}"), REAUTH_REQUIRED, 400);
        assertFailure(http(401, "{}"), REAUTH_REQUIRED, 401);
    }

    @Test
    void clientConfigurationAndUnknownFailuresDoNotRecommendLogin() {
        for (int status : List.of(400, 401)) {
            assertFailure(http(status, "{\"error\":\"invalid_client\"}"), LOCAL_OR_PROTOCOL, status);
            assertFailure(http(status, "{\"error\":{\"code\":\"invalid_client\"}}"), LOCAL_OR_PROTOCOL, status);
            assertFailure(http(status, "{\"error\":\"unauthorized_client\"}"), LOCAL_OR_PROTOCOL, status);
        }
        assertFailure(http(400, "{\"error\":\"unknown\"}"), LOCAL_OR_PROTOCOL, 400);
        assertFailure(http(400, "{\"error\":\"invalid_grant " + SECRET + "\"}"), LOCAL_OR_PROTOCOL, 400);
        assertFailure(http(400, "not json " + SECRET), LOCAL_OR_PROTOCOL, 400);
        assertFailure(http(400, "{\"error_description\":\"invalid_grant " + SECRET + "\"}"), LOCAL_OR_PROTOCOL, 400);
        assertFailure(new IOException("invalid_grant HTTP 401 " + SECRET), LOCAL_OR_PROTOCOL, 0);
        assertFailure(null, LOCAL_OR_PROTOCOL, 0);
    }

    @Test
    void httpStatusOverridesMisleadingTerminalCode() {
        assertFailure(http(403, "{\"error\":\"invalid_grant\"}"), PERMISSION_DENIED, 403);
        assertFailure(http(429, "{\"error\":\"invalid_grant\"}"), RATE_LIMITED, 429);
        assertFailure(http(408, "{}"), TEMPORARY, 408);
        for (int status : List.of(500, 502, 503, 504, 599)) {
            assertFailure(http(status, "{\"error\":\"invalid_grant\"}"), TEMPORARY, status);
        }
    }

    @Test
    void typedNetworkFailuresAreTemporaryIncludingWrappedCauses() {
        for (IOException cause : List.of(new HttpTimeoutException(SECRET), new SocketTimeoutException(SECRET),
                new ConnectException(SECRET), new UnknownHostException(SECRET), new java.io.EOFException(SECRET))) {
            assertFailure(cause, TEMPORARY, 0);
            assertFailure(new IOException(SECRET, cause), TEMPORARY, 0);
        }
        assertFailure(new IOException(SECRET, http(429, "{}")), RATE_LIMITED, 429);
        IOException cyclic = new IOException(SECRET);
        IOException other = new IOException(SECRET, cyclic);
        cyclic.initCause(other);
        assertFailure(cyclic, LOCAL_OR_PROTOCOL, 0);
    }

    @Test
    void interruptedStoreAndRefreshOperationsAreNotCredentialRejections() {
        for (IOException failure : List.of(
                new IOException(SECRET, new InterruptedException(SECRET)),
                new java.nio.channels.ClosedByInterruptException(),
                new java.nio.channels.FileLockInterruptionException(), new java.io.InterruptedIOException(SECRET))) {
            assertFailure(failure, INTERRUPTED, 0);
            assertTrue(CredentialFailure.classify(failure).message().contains("interrupted"));
        }
        IOException http = http(503, "{}");
        http.initCause(new InterruptedException(SECRET));
        assertFailure(http, TEMPORARY, 503);
    }

    @Test
    void preparationFailureDoesNotClaimARefreshOccurred() {
        var error = new ChatConfig.AuthenticationException("openai-codex", new IOException(SECRET));
        assertTrue(error.getMessage().startsWith("Could not prepare credentials for openai-codex."));
        assertFalse(error.getMessage().contains("refresh"));
        assertFalse(error.getMessage().contains("HTTP 401"));
    }

    @Test
    void locallyExpiredCredentialRequiresLoginWithoutMatchingExceptionText() {
        IOException expired = assertThrows(IOException.class, () ->
                ai.kompile.cli.common.auth.OAuthCredentialLifecycle.requireUnexpired(
                        ai.kompile.cli.common.auth.ManagedCredential.oauth("synthetic", "", 1L), 2L));
        assertFailure(expired, REAUTH_REQUIRED, 0);
    }

    @Test
    void missingCredentialsRemainReauthAndExistingExceptionTypeIsPreserved() {
        ChatConfig.AuthenticationException error = new ChatConfig.AuthenticationException("codex");
        assertInstanceOf(IllegalStateException.class, error);
        assertEquals(REAUTH_REQUIRED, error.failure().kind());
        assertEquals(0, error.failure().statusCode());
        assertTrue(error.getMessage().contains("kompile auth login codex"));
        assertNull(error.getCause());
    }

    @Test
    void reflectedSecretsNeverReachSafeObjectExceptionStackOrSerialization() throws Exception {
        for (IOException original : List.of(
                http(400, "{\"error\":\"invalid_grant\",\"error_description\":\"" + SECRET + "\"}"),
                http(403, "{\"error\":{\"code\":\"" + SECRET + "\",\"message\":\"" + SECRET + "\"}}"),
                new IOException(SECRET, new IOException(SECRET)),
                new IOException(SECRET, new InterruptedException(SECRET)))) {
            original.addSuppressed(new IOException(SECRET));
            ChatConfig.AuthenticationException error = new ChatConfig.AuthenticationException("codex", original);
            assertNull(error.getCause());
            assertEquals(0, error.getSuppressed().length);
            assertFalse(error.toString().contains(SECRET));
            assertFalse(error.failure().toString().contains(SECRET));
            assertFalse(error.failure().message().contains(SECRET));
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(SECRET));
            String json = OAuthSupport.MAPPER.writeValueAsString(error.failure());
            assertFalse(json.contains(SECRET));
            assertEquals(error.failure().kind().name(), OAuthSupport.MAPPER.readTree(json).get("kind").asText());
            assertEquals(error.failure().statusCode(), OAuthSupport.MAPPER.readTree(json).get("statusCode").asInt());
            assertFalse(OAuthSupport.MAPPER.writeValueAsString(error).contains(SECRET));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(error);
            }
            assertFalse(bytes.toString(StandardCharsets.ISO_8859_1).contains(SECRET));
        }
    }

    @Test
    void diagnosticsKeepOnlyFixedOperationAndReasonAcrossWrappers() throws Exception {
        IOException original = new IOException(SECRET, new java.io.EOFException(SECRET));
        IOException refresh = CredentialFailure.during(CredentialFailure.Operation.REFRESH, original);
        IOException stored = CredentialFailure.during(CredentialFailure.Operation.STORE, refresh);
        var error = new ChatConfig.AuthenticationException("openai-codex", stored);
        assertEquals(CredentialFailure.Operation.REFRESH, error.failure().operation());
        assertEquals(CredentialFailure.Reason.END_OF_STREAM, error.failure().reason());
        assertTrue(error.getMessage().contains("operation=REFRESH"));
        assertTrue(error.getMessage().contains("reason=END_OF_STREAM"));
        assertNull(stored.getCause());
        assertFalse(OAuthSupport.MAPPER.writeValueAsString(error).contains(SECRET));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(stored);
        }
        assertFalse(bytes.toString(StandardCharsets.ISO_8859_1).contains(SECRET));
    }

    @Test
    void tlsErrorsAreNotRetriedEvenWithTransientNestedCauses() {
        var tls = new javax.net.ssl.SSLHandshakeException(SECRET);
        tls.initCause(new java.io.EOFException(SECRET));
        assertFailure(tls, LOCAL_OR_PROTOCOL, 0);
        assertEquals(CredentialFailure.Reason.TLS, CredentialFailure.classify(tls).reason());
        var file = new java.nio.file.AccessDeniedException(SECRET);
        assertEquals(CredentialFailure.Reason.FILE_IO, CredentialFailure.classify(file).reason());
    }

    private static IOException http(int status, String body) {
        return assertThrows(IOException.class,
                () -> OAuthSupport.requireSuccess(new OAuthSupport.Response(status, body), "Synthetic refresh"));
    }

    private static void assertFailure(IOException original, CredentialFailure.Kind kind, int status) {
        CredentialFailure failure = CredentialFailure.classify(original);
        assertEquals(kind, failure.kind());
        assertEquals(status, failure.statusCode());
        ChatConfig.AuthenticationException error = new ChatConfig.AuthenticationException("codex", original);
        assertEquals(kind, error.failure().kind());
        assertEquals(status, error.failure().statusCode());
        assertEquals(kind == REAUTH_REQUIRED, error.getMessage().contains("kompile auth login"));
        assertNull(error.getCause());
    }
}
