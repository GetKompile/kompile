package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** All transport and refresh operations are synthetic; no credentials or sockets are used. */
class ModelDiscoveryRecoveryTest {
    private static final String ENDPOINT = "https://discovery.invalid/v1/models";
    private static final String PRIVATE_DETAIL = "synthetic-private-diagnostic";
    private static final RequestAuth ORIGINAL = auth("old", "selected", "identity", "https://discovery.invalid/v1");
    private static final RequestAuth REFRESHED = auth("new", "selected", "identity", "https://discovery.invalid/v1");

    @Test
    void refreshesOnceWithRejectedIdentityAndOriginalEndpointBudget() throws Exception {
        Transport transport = new Transport(401, 200);
        AtomicInteger refreshes = new AtomicInteger();
        var result = discover(transport, ORIGINAL, (provider, rejected) -> {
            assertEquals("openai", provider);
            assertSame(ORIGINAL, rejected);
            assertTrue(transport.bodies.get(0).closed);
            refreshes.incrementAndGet();
            return REFRESHED;
        });
        assertEquals(ModelDiscovery.Status.SUCCESS, result.status());
        assertEquals(1, refreshes.get());
        assertEquals(2, transport.requests.size());
        assertEquals(transport.requests.get(0).uri(), transport.requests.get(1).uri());
        assertEquals("Bearer old", transport.requests.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer new", transport.requests.get(1).headers().firstValue("Authorization").orElseThrow());
        assertTrue(transport.requests.get(1).timeout().orElseThrow()
                .compareTo(transport.requests.get(0).timeout().orElseThrow()) <= 0);
        assertTrue(transport.bodies.stream().allMatch(body -> body.closed));
    }

    @Test
    void second401IsTerminalEvenWithAnotherCandidateRoute() throws Exception {
        Transport transport = new Transport(401, 401, 200);
        AtomicInteger refreshes = new AtomicInteger();
        var result = discover(transport, ORIGINAL, (provider, rejected) -> {
            refreshes.incrementAndGet();
            return REFRESHED;
        });
        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, result.status());
        assertEquals(1, refreshes.get());
        assertEquals(2, transport.requests.size());
    }

    @Test
    void neverRefreshesApiKeysOrUnnamedTransientOAuth() throws Exception {
        for (RequestAuth auth : List.of(RequestAuth.apiKey("synthetic-key"),
                RequestAuth.oauth("transient", ORIGINAL.baseUrl(), Map.of()))) {
            Transport transport = new Transport(401, 200);
            var result = discover(transport, auth, (provider, rejected) -> {
                fail("Must not refresh unowned credentials");
                return null;
            });
            assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, result.status());
            assertEquals(1, transport.requests.size());
        }
    }

    @Test
    void missingIdentityDoesNotRefreshOrAskForLogin() throws Exception {
        Transport transport = new Transport(401);
        var result = discover(transport, ORIGINAL.withCredential("selected", null), (provider, rejected) -> {
            fail("Cannot refresh without a pinned identity");
            return null;
        });
        assertEquals(ModelDiscovery.Status.UNAVAILABLE, result.status());
    }

    @Test
    void structuredPermissionPolicyAndQuota401NeverRefresh() throws Exception {
        for (String code : List.of("permission_denied", "content_policy_violation", "insufficient_quota")) {
            Transport transport = new Transport(401);
            transport.errorBody = "{\"error\":{\"code\":\"" + code
                    + "\",\"message\":\"" + PRIVATE_DETAIL + "\"}}";
            var result = discover(transport, ORIGINAL, (provider, rejected) -> {
                fail("Must not refresh for " + code);
                return null;
            });
            assertEquals(code.equals("insufficient_quota") ? ModelDiscovery.Status.RATE_LIMITED
                    : ModelDiscovery.Status.FORBIDDEN, result.status());
            assertFalse(result.message().contains(PRIVATE_DETAIL));
            assertEquals(1, transport.requests.size());
            assertTrue(transport.bodies.get(0).closed);
        }
    }

    @Test
    void changedCredentialNameIdentityOrEndpointCannotBeRetried() throws Exception {
        for (RequestAuth changed : List.of(
                auth("new", "other", "identity", ORIGINAL.baseUrl()),
                auth("new", "selected", "other", ORIGINAL.baseUrl()),
                auth("new", "selected", "identity", "https://other.invalid"),
                RequestAuth.apiKey("new"))) {
            Transport transport = new Transport(401, 200);
            var result = discover(transport, ORIGINAL, (provider, rejected) -> changed);
            assertEquals(ModelDiscovery.Status.UNAVAILABLE, result.status());
            assertEquals(1, transport.requests.size());
        }
    }

    @Test
    void refreshFailuresUseSafeTypedClassificationNotLogin() throws Exception {
        List<IOException> failures = List.of(new IOException(PRIVATE_DETAIL),
                new HttpTimeoutException(PRIVATE_DETAIL),
                oauthHttpFailure(503, null), oauthHttpFailure(429, null),
                oauthHttpFailure(403, null), oauthHttpFailure(400, "invalid_grant"),
                oauthHttpFailure(401, "invalid_client"));
        List<ModelDiscovery.Status> expected = List.of(ModelDiscovery.Status.UNAVAILABLE,
                ModelDiscovery.Status.TIMEOUT, ModelDiscovery.Status.UNAVAILABLE,
                ModelDiscovery.Status.RATE_LIMITED, ModelDiscovery.Status.FORBIDDEN,
                ModelDiscovery.Status.AUTH_REQUIRED, ModelDiscovery.Status.UNAVAILABLE);
        for (int i = 0; i < failures.size(); i++) {
            IOException failure = failures.get(i);
            Transport transport = new Transport(401, 200);
            var result = discover(transport, ORIGINAL, (provider, rejected) -> { throw failure; });
            assertEquals(expected.get(i), result.status());
            assertFalse(result.message().contains(PRIVATE_DETAIL));
            assertEquals(1, transport.requests.size());
        }
    }

    @Test
    void exhaustedRefreshBudgetCannotRetryOrBecomeLogin() throws Exception {
        Transport transport = new Transport(401, 200);
        CountDownLatch interrupted = new CountDownLatch(1);
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                ModelDiscoveryHttp.discover(transport.context(ORIGINAL, Duration.ofMillis(500)),
                        List.of(ENDPOINT), null, (provider, rejected) -> {
                            try {
                                new CountDownLatch(1).await();
                                throw new AssertionError("unreachable");
                            } catch (InterruptedException cancelled) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                throw new IOException(PRIVATE_DETAIL, cancelled);
                            }
                        }));
        assertEquals(ModelDiscovery.Status.TIMEOUT, result.status());
        assertEquals(1, transport.requests.size());
        assertTrue(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void oversized401DiagnosticsAreBoundedAndNotExposed() throws Exception {
        Transport transport = new Transport(401);
        transport.errorBody = PRIVATE_DETAIL.repeat(1000);
        var result = discover(transport, ORIGINAL, (provider, rejected) -> null);
        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, result.status());
        assertEquals(8193, transport.bodies.get(0).bytesRead());
        assertFalse(result.message().contains(PRIVATE_DETAIL));
        assertTrue(transport.bodies.get(0).closed);
    }

    @Test
    void stalled401BodyUsesOriginalDeadlineAndNeverStartsRefreshAfterExpiry() throws Exception {
        Transport transport = new Transport(401);
        transport.overrideBody = new InputStream() {
            @Override public int read() throws IOException {
                try {
                    new CountDownLatch(1).await();
                    return -1;
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException(PRIVATE_DETAIL, cancelled);
                }
            }
        };
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                ModelDiscoveryHttp.discover(transport.context(ORIGINAL, Duration.ofMillis(100)),
                        List.of(ENDPOINT), null, (provider, rejected) -> {
                            fail("Expired discovery must not start refresh");
                            return null;
                        }));
        assertEquals(ModelDiscovery.Status.TIMEOUT, result.status());
        assertEquals(1, transport.requests.size());
        assertFalse(result.message().contains(PRIVATE_DETAIL));
    }

    @Test
    void nullRefreshDoesNotRetry() throws Exception {
        Transport transport = new Transport(401, 200);
        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED,
                discover(transport, ORIGINAL, (provider, rejected) -> null).status());
        assertEquals(1, transport.requests.size());
    }

    @Test
    void refreshedAuthIsReusedAcrossPagesWithoutASecondRefresh() throws Exception {
        Transport transport = new Transport(401, 200, 401, 200);
        transport.successBody = "{\"data\":[{\"id\":\"synthetic-model\"}],\"has_more\":true,\"last_id\":\"next\"}";
        AtomicInteger refreshes = new AtomicInteger();
        var result = ModelDiscoveryHttp.discover(transport.context(ORIGINAL, Duration.ofSeconds(2)),
                List.of(ENDPOINT), ProviderModelCatalogs.find("anthropic"), (provider, rejected) -> {
                    refreshes.incrementAndGet();
                    return REFRESHED;
                });
        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, result.status());
        assertEquals(1, refreshes.get());
        assertEquals(3, transport.requests.size());
        assertEquals("Bearer new", transport.requests.get(2).headers().firstValue("Authorization").orElseThrow());
        assertTrue(result.models().isEmpty());
    }

    private static ModelDiscovery.Result discover(Transport transport, RequestAuth auth,
                                                  ModelDiscoveryHttp.RefreshAuth refresh) {
        return ModelDiscoveryHttp.discover(transport.context(auth, Duration.ofSeconds(2)),
                List.of(ENDPOINT, ENDPOINT + "-alternative"), null, refresh);
    }

    private static RequestAuth auth(String token, String name, String identity, String base) {
        return RequestAuth.oauth(token, base, Map.of("Authorization", "Bearer " + token))
                .withCredential(name, identity);
    }

    // The typed OAuth transport exception is intentionally package-private. Construct synthetic
    // instances without invoking OAuthSupport or making its production surface public for tests.
    private static IOException oauthHttpFailure(int status, String code) throws Exception {
        var type = Class.forName("ai.kompile.cli.main.auth.oauth.OAuthSupport$OAuthHttpException");
        var constructor = type.getDeclaredConstructor(int.class, String.class, String.class);
        constructor.setAccessible(true);
        return (IOException) constructor.newInstance(status, code, PRIVATE_DETAIL);
    }

    private static final class TrackingBody extends ByteArrayInputStream {
        boolean closed;
        TrackingBody(String value) { super(value.getBytes(StandardCharsets.UTF_8)); }
        int bytesRead() { return pos; }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static final class Transport {
        final HttpClient client = mock(HttpClient.class);
        final List<HttpRequest> requests = new ArrayList<>();
        final List<TrackingBody> bodies = new ArrayList<>();
        InputStream overrideBody;
        String errorBody = "{\"error\":{\"code\":\"invalid_token\"}}";
        String successBody = "{\"data\":[{\"id\":\"synthetic-model\"}]}";

        @SuppressWarnings("unchecked")
        Transport(int... statuses) throws Exception {
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
                int status = statuses[requests.size()];
                requests.add(call.getArgument(0));
                TrackingBody body = new TrackingBody(status == 200 ? successBody : errorBody);
                bodies.add(body);
                HttpResponse<InputStream> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(status);
                when(response.body()).thenReturn(overrideBody == null ? body : overrideBody);
                return response;
            });
        }

        ModelDiscovery.Context context(RequestAuth auth, Duration timeout) {
            return new ModelDiscovery.Context("openai", ORIGINAL.baseUrl(), null, auth, client, timeout);
        }
    }
}
