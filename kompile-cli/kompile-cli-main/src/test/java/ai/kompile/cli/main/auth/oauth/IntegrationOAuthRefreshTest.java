package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationOAuthRefreshTest {
    @TempDir Path tempDir;

    private static final String CONFIGURED_CLIENT_ID = "configured-client";
    private static final String STORED_CLIENT_ID = "stored-client";
    private static final String CLIENT_SECRET = "synthetic-client-secret";
    private static final String TENANT = "stored-tenant";
    private static final OAuthProviderFlow.LoginOptions MANUAL_BROWSER =
            new OAuthProviderFlow.LoginOptions("browser", true, TENANT, null);

    @ParameterizedTest(name = "{0}: rotated={1}, supplied scope={2}")
    @CsvSource({
            "google, false, false", "google, true, false",
            "google, false, true", "google, true, true",
            "microsoft, false, false", "microsoft, true, false",
            "microsoft, false, true", "microsoft, true, true",
            "reddit, false, false", "reddit, true, false",
            "reddit, false, true", "reddit, true, true",
            "atlassian, false, false", "atlassian, true, false",
            "atlassian, false, true", "atlassian, true, true"
    })
    void refreshPreservesOmittedFieldsAndReplacesSuppliedFields(
            String provider, boolean rotated, boolean suppliedScope) throws Exception {
        ObjectNode body = OAuthSupport.MAPPER.createObjectNode();
        body.put("access_token", "new-access").put("expires_in", 3600);
        if (rotated) {
            body.put("refresh_token", "rotated-refresh");
        }
        if (suppliedScope) {
            body.put("scope", "new-scope");
        }
        QueueTransport transport = new QueueTransport(json(body.toString()));
        OAuthProviderFlow flow = flow(provider, transport);
        Map<String, String> metadata = metadata(provider);
        ManagedCredential previous = ManagedCredential.oauth("old-access", "old-refresh", 1L, metadata);
        long before = System.currentTimeMillis();

        ManagedCredential refreshed = flow.refresh(previous);

        assertEquals("new-access", refreshed.getAccess());
        assertEquals(rotated ? "rotated-refresh" : "old-refresh", refreshed.getRefresh());
        assertTrue(refreshed.getExpires() > before);
        Map<String, String> expectedMetadata = new LinkedHashMap<>(metadata);
        if (suppliedScope) {
            expectedMetadata.put("scope", "new-scope");
        }
        assertEquals(expectedMetadata, refreshed.getMetadata());
        assertEquals(metadata, previous.getMetadata());
        assertEquals("old-refresh", previous.getRefresh());
        assertEquals("old-access", previous.getAccess());
        assertRefreshRequest(provider, transport, STORED_CLIENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft", "reddit", "atlassian"})
    void refreshUsesConfiguredClientIdWhenStoredIdIsMissingOrBlank(String provider) throws Exception {
        for (String storedId : new String[]{null, "", " "}) {
            QueueTransport transport = new QueueTransport(json("""
                    {"access_token":"new-access","expires_in":3600}
                    """));
            Map<String, String> metadata = metadata(provider);
            if (storedId == null) {
                metadata.remove("clientId");
            } else {
                metadata.put("clientId", storedId);
            }
            ManagedCredential previous = ManagedCredential.oauth(
                    "old-access", "old-refresh", 1L, metadata);

            ManagedCredential refreshed = flow(provider, transport).refresh(previous);

            Map<String, String> expectedMetadata = new LinkedHashMap<>(metadata);
            expectedMetadata.put("clientId", CONFIGURED_CLIENT_ID);
            assertEquals(expectedMetadata, refreshed.getMetadata());
            assertEquals("old-refresh", refreshed.getRefresh());
            assertRefreshRequest(provider, transport, CONFIGURED_CLIENT_ID);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft", "reddit", "atlassian"})
    void firstExchangeStillRequiresRefreshToken(String provider) {
        QueueTransport transport = new QueueTransport(json("""
                {"access_token":"new-access","expires_in":3600}
                """));

        IOException error = assertThrows(IOException.class,
                () -> flow(provider, transport).login(MANUAL_BROWSER, new SyntheticInteraction()));

        assertTrue(error.getMessage().contains("refresh"));
        assertEquals(1, transport.requests.size());
        assertTrue(transport.requests.get(0).body().contains("grant_type=authorization_code"));
        assertRequestClientId(provider, transport.requests.get(0), CONFIGURED_CLIENT_ID);
    }

    @Test
    void microsoftDeviceExchangeStillRequiresRefreshToken() {
        QueueTransport transport = new QueueTransport(
                json("""
                        {"device_code":"synthetic-device","user_code":"SYNTHETIC-CODE",
                         "verification_uri":"https://microsoft.com/devicelogin",
                         "interval":1,"expires_in":600}
                        """),
                json("""
                        {"access_token":"new-access","expires_in":3600}
                        """));

        IOException error = assertThrows(IOException.class, () -> flow("microsoft", transport).login(
                new OAuthProviderFlow.LoginOptions("device", false, TENANT, null),
                new SyntheticInteraction()));

        assertTrue(error.getMessage().contains("refresh_token"));
        assertEquals(2, transport.requests.size());
        assertRequestClientId("microsoft", transport.requests.get(1), CONFIGURED_CLIENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft", "reddit", "atlassian"})
    void refreshStillRequiresAccessTokenAndPositiveExpiry(String provider) {
        for (String body : List.of(
                "{\"expires_in\":3600}",
                "{\"access_token\":\"new-access\"}",
                "{\"access_token\":\"new-access\",\"expires_in\":0}")) {
            QueueTransport transport = new QueueTransport(json(body));
            ManagedCredential previous = ManagedCredential.oauth(
                    "old-access", "old-refresh", 1L, metadata(provider));

            assertThrows(IOException.class, () -> flow(provider, transport).refresh(previous));

            assertEquals(1, transport.requests.size());
            assertEquals("old-refresh", previous.getRefresh());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void optionalIdTokenIdentityDeduplicatesLoginsAndSurvivesOpaqueRefresh(String provider) throws Exception {
        // The ID token's deadline must never become the access token's deadline.
        String idToken = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("""
                {"sub":"stable-user","iss":"https://issuer.example.test",
                 "email":"person@example.test","exp":1}
                """.getBytes(StandardCharsets.UTF_8)) + ".synthetic";
        QueueTransport transport = new QueueTransport(
                json("""
                        {"access_token":"first-access","refresh_token":"first-refresh",
                         "expires_in":3600,"id_token":"%s","scope":"profile"}
                        """.formatted(idToken)),
                json("""
                        {"access_token":"second-access","refresh_token":"second-refresh",
                         "expires_in":3600,"id_token":"%s","scope":"profile"}
                        """.formatted(idToken)),
                json("""
                        {"access_token":"refreshed-access","expires_in":3600}
                        """));
        OAuthProviderFlow flow = flow(provider, transport);
        CredentialStore store = new CredentialStore(tempDir.resolve("auth.json"));
        ManagedCredential first = flow.login(MANUAL_BROWSER, new SyntheticInteraction());
        store.put(provider, "work", ManagedCredential.oauth(
                first.getAccess(), first.getRefresh(), 1L, first.getMetadata()), true);

        ManagedCredential second = flow.login(MANUAL_BROWSER, new SyntheticInteraction());
        assertTrue(second.getExpires() > System.currentTimeMillis());
        assertEquals("stable-user", second.getMetadata("subject"));
        assertEquals("https://issuer.example.test", second.getMetadata("issuer"));
        assertEquals("person@example.test", second.getMetadata("email"));
        assertFalse(second.getMetadata().containsValue(idToken));
        store.put(provider, "duplicate-name", second, true);
        assertEquals(1, store.list(provider).size());
        assertEquals("work", store.credentialName(provider, second));
        assertEquals("second-access", store.read(provider).getAccess());

        ManagedCredential refreshed = flow.refresh(store.read(provider));
        assertEquals("refreshed-access", refreshed.getAccess());
        assertEquals("second-refresh", refreshed.getRefresh());
        assertEquals(second.getMetadata(), refreshed.getMetadata());
        assertEquals(3, transport.requests.size(), "No extra identity lookup");
        assertTrue(transport.responses.isEmpty());
    }

    private static OAuthProviderFlow flow(String provider, OAuthSupport.HttpTransport transport) {
        return switch (provider) {
            case "google" -> new GoogleOAuthFlow(transport, CONFIGURED_CLIENT_ID);
            case "microsoft" -> new MicrosoftOAuthFlow(transport,
                    new OAuthSupport.DeviceCodePoller(millis -> {}, System::currentTimeMillis),
                    CONFIGURED_CLIENT_ID);
            case "reddit" -> new RedditOAuthFlow(transport, CONFIGURED_CLIENT_ID, CLIENT_SECRET);
            case "atlassian" -> new AtlassianOAuthFlow(transport, CONFIGURED_CLIENT_ID, CLIENT_SECRET);
            default -> throw new IllegalArgumentException("Unknown test provider: " + provider);
        };
    }

    private static Map<String, String> metadata(String provider) {
        Map<String, String> metadata = new LinkedHashMap<>(Map.of(
                "clientId", STORED_CLIENT_ID,
                "scope", "old-scope",
                "custom", "retained"));
        if ("microsoft".equals(provider)) {
            metadata.put("tenant", TENANT);
        } else if ("atlassian".equals(provider)) {
            metadata.put("cloudId", "existing-cloud");
            metadata.put("cloudName", "existing-site");
        }
        return metadata;
    }

    private static void assertRefreshRequest(String provider, QueueTransport transport, String clientId) {
        // Refresh must not add an identity or accessible-resources lookup.
        assertEquals(1, transport.requests.size());
        OAuthSupport.Request request = transport.requests.get(0);
        assertEquals("POST", request.method());
        List<String> fields = List.of(request.body().split("&"));
        assertTrue(fields.contains("grant_type=refresh_token"));
        assertTrue(fields.contains("refresh_token=old-refresh"));
        assertFalse(request.body().contains("redirect_uri="));
        assertRequestClientId(provider, request, clientId);
        if ("microsoft".equals(provider)) {
            assertEquals("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token",
                    request.uri().toString());
        }
        assertTrue(transport.responses.isEmpty());
    }

    private static void assertRequestClientId(
            String provider, OAuthSupport.Request request, String clientId) {
        if (!"reddit".equals(provider)) {
            assertTrue(List.of(request.body().split("&")).contains("client_id=" + clientId));
        }
        if ("reddit".equals(provider) || "atlassian".equals(provider)) {
            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            assertEquals("Basic " + basic, request.headers().get("Authorization"));
        }
    }

    private static OAuthSupport.Response json(String body) {
        return new OAuthSupport.Response(200, body);
    }

    private static final class QueueTransport implements OAuthSupport.HttpTransport {
        private final Deque<OAuthSupport.Response> responses = new ArrayDeque<>();
        private final List<OAuthSupport.Request> requests = new ArrayList<>();

        private QueueTransport(OAuthSupport.Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public OAuthSupport.Response send(OAuthSupport.Request request) {
            requests.add(request);
            if (responses.isEmpty()) {
                fail("Unexpected OAuth HTTP request: " + request.uri());
            }
            return responses.removeFirst();
        }
    }

    private static final class SyntheticInteraction implements OAuthProviderFlow.Interaction {
        @Override
        public void info(String message) {}

        @Override
        public void authorizationUrl(URI url, String instructions) {}

        @Override
        public void deviceCode(
                String userCode, URI verificationUri, Integer intervalSeconds, Integer expiresInSeconds) {}

        @Override
        public String prompt(String message) {
            return "synthetic-code";
        }
    }
}
