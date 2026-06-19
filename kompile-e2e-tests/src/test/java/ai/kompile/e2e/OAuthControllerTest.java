package ai.kompile.e2e;

import ai.kompile.kclaw.gateway.OAuthController;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OAuthController: config storage, authorize URL generation,
 * callback state validation, status, and disconnect.
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Controller")
class OAuthControllerTest {

    private OAuthController controller;
    private MockHttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        ChannelManager channelManager = new ChannelManager();
        ObjectMapper objectMapper = new ObjectMapper();
        controller = new OAuthController(channelManager, objectMapper);

        servletRequest = new MockHttpServletRequest();
        servletRequest.setScheme("http");
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(8080);
    }

    // ── Config ──

    @Test
    @DisplayName("Get config for unconfigured provider returns not connected")
    void testGetConfigUnconfigured() {
        ResponseEntity<Map<String, Object>> response = controller.getOAuthConfig("slack");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("connected"));
        assertEquals("slack", response.getBody().get("provider"));
    }

    @Test
    @DisplayName("Set config stores client ID and secret")
    void testSetConfig() {
        ResponseEntity<Void> response = controller.setOAuthClientConfig("slack",
                Map.of("clientId", "my-client-id", "clientSecret", "my-secret"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("Set config without clientId returns bad request")
    void testSetConfigMissingClientId() {
        ResponseEntity<Void> response = controller.setOAuthClientConfig("slack",
                Map.of("clientSecret", "my-secret"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("Set config without clientSecret returns bad request")
    void testSetConfigMissingSecret() {
        ResponseEntity<Void> response = controller.setOAuthClientConfig("slack",
                Map.of("clientId", "my-client-id"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ── Authorize ──

    @Test
    @DisplayName("Authorize without config returns bad request")
    void testAuthorizeWithoutConfig() {
        ResponseEntity<Map<String, String>> response = controller.authorize("slack", servletRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    @DisplayName("Authorize with config returns authorization URL for Slack")
    void testAuthorizeSlack() {
        controller.setOAuthClientConfig("slack",
                Map.of("clientId", "slack-client-id", "clientSecret", "slack-secret"));

        ResponseEntity<Map<String, String>> response = controller.authorize("slack", servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String authUrl = response.getBody().get("authorizationUrl");
        assertNotNull(authUrl);
        assertTrue(authUrl.startsWith("https://slack.com/oauth/v2/authorize"));
        assertTrue(authUrl.contains("client_id=slack-client-id"));
        assertTrue(authUrl.contains("redirect_uri="));
        assertTrue(authUrl.contains("state="));

        String state = response.getBody().get("state");
        assertNotNull(state);
        assertFalse(state.isEmpty());
    }

    @Test
    @DisplayName("Authorize with config returns authorization URL for Discord")
    void testAuthorizeDiscord() {
        controller.setOAuthClientConfig("discord",
                Map.of("clientId", "discord-client-id", "clientSecret", "discord-secret"));

        ResponseEntity<Map<String, String>> response = controller.authorize("discord", servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String authUrl = response.getBody().get("authorizationUrl");
        assertNotNull(authUrl);
        assertTrue(authUrl.startsWith("https://discord.com/api/oauth2/authorize"));
        assertTrue(authUrl.contains("client_id=discord-client-id"));
        assertTrue(authUrl.contains("response_type=code"));
    }

    @Test
    @DisplayName("Authorize generates unique state per call")
    void testAuthorizeUniqueState() {
        controller.setOAuthClientConfig("slack",
                Map.of("clientId", "id", "clientSecret", "secret"));

        ResponseEntity<Map<String, String>> r1 = controller.authorize("slack", servletRequest);
        ResponseEntity<Map<String, String>> r2 = controller.authorize("slack", servletRequest);

        assertNotEquals(r1.getBody().get("state"), r2.getBody().get("state"));
    }

    // ── Callback ──

    @Test
    @DisplayName("Callback with invalid state returns 403")
    void testCallbackInvalidState() {
        ResponseEntity<String> response = controller.callback("slack", "auth-code", "invalid-state", servletRequest);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid"));
    }

    @Test
    @DisplayName("Callback with mismatched provider returns 400")
    void testCallbackProviderMismatch() {
        controller.setOAuthClientConfig("slack",
                Map.of("clientId", "id", "clientSecret", "secret"));
        ResponseEntity<Map<String, String>> authResponse = controller.authorize("slack", servletRequest);
        String state = authResponse.getBody().get("state");

        // Use the slack state with discord provider
        ResponseEntity<String> response = controller.callback("discord", "auth-code", state, servletRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("mismatch"));
    }

    @Test
    @DisplayName("Callback state is single-use (replay returns 403)")
    void testCallbackStateIsOneTimeUse() {
        controller.setOAuthClientConfig("slack",
                Map.of("clientId", "id", "clientSecret", "secret"));
        ResponseEntity<Map<String, String>> authResponse = controller.authorize("slack", servletRequest);
        String state = authResponse.getBody().get("state");

        // First callback — will fail on token exchange (no real Slack), but state is consumed
        controller.callback("slack", "auth-code", state, servletRequest);

        // Second callback with same state — should get 403
        ResponseEntity<String> response = controller.callback("slack", "auth-code", state, servletRequest);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // ── Status ──

    @Test
    @DisplayName("Status returns not connected when no credentials")
    void testStatusNotConnected() {
        ResponseEntity<Map<String, Object>> response = controller.getStatus("slack");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("connected"));
    }

    // ── Disconnect ──

    @Test
    @DisplayName("Disconnect returns 204 no content")
    void testDisconnect() {
        ResponseEntity<Void> response = controller.disconnect("slack");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @DisplayName("Disconnect then status shows not connected")
    void testDisconnectThenStatus() {
        controller.disconnect("slack");

        ResponseEntity<Map<String, Object>> status = controller.getStatus("slack");
        assertEquals(false, status.getBody().get("connected"));
    }

    // ── Redirect URI Construction ──

    @Test
    @DisplayName("Redirect URI uses request scheme, host, and port")
    void testRedirectUriConstruction() {
        controller.setOAuthClientConfig("slack",
                Map.of("clientId", "id", "clientSecret", "secret"));

        servletRequest.setScheme("https");
        servletRequest.setServerName("myapp.example.com");
        servletRequest.setServerPort(443);

        ResponseEntity<Map<String, String>> response = controller.authorize("slack", servletRequest);
        String authUrl = response.getBody().get("authorizationUrl");

        // Port 443 should not appear in redirect URI
        assertTrue(authUrl.contains("redirect_uri="));
        assertTrue(authUrl.contains("myapp.example.com"));
    }

    @Test
    @DisplayName("Redirect URI includes non-standard port")
    void testRedirectUriNonStandardPort() {
        controller.setOAuthClientConfig("discord",
                Map.of("clientId", "id", "clientSecret", "secret"));

        servletRequest.setScheme("http");
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(9090);

        ResponseEntity<Map<String, String>> response = controller.authorize("discord", servletRequest);
        String authUrl = response.getBody().get("authorizationUrl");

        assertTrue(authUrl.contains("9090"));
    }

    // ── Scopes ──

    @Test
    @DisplayName("Slack config includes expected scopes")
    void testSlackScopes() {
        ResponseEntity<Map<String, Object>> response = controller.getOAuthConfig("slack");

        String scopes = (String) response.getBody().get("scopes");
        assertNotNull(scopes);
        assertTrue(scopes.contains("chat:write"));
        assertTrue(scopes.contains("channels:read"));
    }

    @Test
    @DisplayName("Discord config includes expected scopes")
    void testDiscordScopes() {
        ResponseEntity<Map<String, Object>> response = controller.getOAuthConfig("discord");

        String scopes = (String) response.getBody().get("scopes");
        assertNotNull(scopes);
        assertTrue(scopes.contains("bot"));
    }

    @Test
    @DisplayName("Unknown provider returns empty scopes")
    void testUnknownProviderScopes() {
        ResponseEntity<Map<String, Object>> response = controller.getOAuthConfig("unknown");

        String scopes = (String) response.getBody().get("scopes");
        assertNotNull(scopes);
        assertEquals("", scopes);
    }
}
