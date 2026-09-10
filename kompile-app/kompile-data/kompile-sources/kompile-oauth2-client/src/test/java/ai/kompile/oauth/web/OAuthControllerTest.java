package ai.kompile.oauth.web;

import ai.kompile.oauth.dto.*;
import ai.kompile.oauth.service.OAuthConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OAuthController")
class OAuthControllerTest {

    @Mock
    private OAuthConnectionService connectionService;

    private OAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new OAuthController(connectionService);
    }

    private HttpServletRequest mockRequest(String scheme, String host, int port) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn(scheme);
        when(request.getServerName()).thenReturn(host);
        when(request.getServerPort()).thenReturn(port);
        return request;
    }

    private HttpServletRequest mockStandardRequest() {
        return mockRequest("https", "app.example.com", 443);
    }

    // ═══════════════════════════════════════════════════════════════
    // Authorization initiation
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /{providerId}/authorize")
    class Authorize {

        @Test
        @DisplayName("should return authorization URL for configured provider")
        void success() {
            HttpServletRequest request = mockStandardRequest();
            AuthorizationUrlResponse authResponse = AuthorizationUrlResponse.builder()
                    .authorizationUrl("https://accounts.google.com/o/oauth2/auth?client_id=123&state=abc")
                    .state("abc")
                    .providerId("google")
                    .build();
            when(connectionService.initiateAuthorization(eq("google"), anyString(), isNull()))
                    .thenReturn(authResponse);

            ResponseEntity<AuthorizationUrlResponse> response =
                    controller.initiateAuthorization("google", null, null, request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("abc", response.getBody().getState());
            assertNotNull(response.getBody().getAuthorizationUrl());
        }

        @Test
        @DisplayName("should forward fixed channel authorization purpose")
        void channelPurpose() {
            HttpServletRequest request = mockStandardRequest();
            AuthorizationUrlResponse authResponse = AuthorizationUrlResponse.builder()
                    .authorizationUrl("https://slack.com/oauth?scope=chat:write")
                    .state("state")
                    .providerId("slack")
                    .build();
            when(connectionService.initiateAuthorization(
                    eq("slack"), anyString(), eq("channel")))
                    .thenReturn(authResponse);

            ResponseEntity<AuthorizationUrlResponse> response =
                    controller.initiateAuthorization("slack", null, "channel", request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(connectionService).initiateAuthorization(
                    eq("slack"), anyString(), eq("channel"));
        }

        @Test
        @DisplayName("should return 400 for unknown provider")
        void unknownProvider() {
            HttpServletRequest request = mockStandardRequest();
            when(connectionService.initiateAuthorization(eq("unknown"), anyString(), isNull()))
                    .thenThrow(new IllegalArgumentException("Unknown provider"));

            ResponseEntity<AuthorizationUrlResponse> response =
                    controller.initiateAuthorization("unknown", null, null, request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        @DisplayName("should return 428 PRECONDITION_REQUIRED for unconfigured provider")
        void unconfiguredProvider() {
            HttpServletRequest request = mockStandardRequest();
            when(connectionService.initiateAuthorization(eq("google"), anyString(), isNull()))
                    .thenThrow(new IllegalStateException("Not configured"));

            ResponseEntity<AuthorizationUrlResponse> response =
                    controller.initiateAuthorization("google", null, null, request);

            assertEquals(HttpStatus.PRECONDITION_REQUIRED, response.getStatusCode());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OAuth callback
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /{providerId}/callback")
    class Callback {

        @Test
        @DisplayName("should redirect to success URL on valid auth")
        void successFlow() {
            HttpServletRequest request = mockStandardRequest();
            OAuthConnectionDto dto = OAuthConnectionDto.builder()
                    .providerId("google").status("connected").build();
            when(connectionService.completeAuthorization(
                    eq("google"), eq("code123"), eq("state456")))
                    .thenReturn(dto);

            ResponseEntity<?> response = controller.handleCallback(
                    "google", "code123", "state456", null, null, request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(((String) response.getBody()).contains("google is now connected"));
            assertTrue(((String) response.getBody()).contains("oauth-complete"));
        }

        @Test
        @DisplayName("should redirect to error URL when provider sends error parameter")
        void providerError() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "google", null, null, "access_denied", "User denied access", request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(((String) response.getBody()).contains("Connection failed"));
            assertTrue(((String) response.getBody()).contains("Authorization was denied"));
        }

        @Test
        @DisplayName("provider denial consumes returned OAuth state")
        void providerDenialConsumesState() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "reddit", null, "denied-state", "access_denied", "Denied", request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            verify(connectionService).consumeDeniedAuthorization("reddit", "denied-state");
        }

        @Test
        @DisplayName("should return 400 with error body when code is missing")
        void missingCode() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "google", null, "state", null, null, request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertNotNull(body);
            assertTrue(body.containsKey("error"), "Body must contain 'error' key");
        }

        @Test
        @DisplayName("should return 400 with error body when state is missing")
        void missingState() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "google", "code", null, null, null, request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertNotNull(body);
            assertTrue(body.containsKey("error"), "Body must contain 'error' key");
        }

        @Test
        @DisplayName("should redirect with invalid_state error when SecurityException is thrown")
        void invalidStateThrowsSecurityException() {
            HttpServletRequest request = mockStandardRequest();
            when(connectionService.completeAuthorization(
                    eq("google"), eq("code"), eq("forged-state")))
                    .thenThrow(new SecurityException("Invalid OAuth state parameter"));

            ResponseEntity<?> response = controller.handleCallback(
                    "google", "code", "forged-state", null, null, request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(((String) response.getBody()).contains("OAuth state validation failed"));
        }

        @Test
        @DisplayName("should redirect with token_exchange_failed on RuntimeException")
        void tokenExchangeFailed() {
            HttpServletRequest request = mockStandardRequest();
            when(connectionService.completeAuthorization(
                    eq("google"), eq("bad-code"), eq("state")))
                    .thenThrow(new RuntimeException("Token exchange failed"));

            ResponseEntity<?> response = controller.handleCallback(
                    "google", "bad-code", "state", null, null, request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(((String) response.getBody()).contains("Token exchange failed"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Redirect URL safety in callback
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Callback redirect URL safety")
    class CallbackRedirectSafety {

        @Test
        @DisplayName("should render a fixed error page without reflecting provider error input")
        void errorCodeInRedirectUrl() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "google", null, null, "access_denied", "User denied access", request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertFalse(((String) response.getBody()).contains("access_denied"));
        }

        @Test
        @DisplayName("should not reflect raw provider error descriptions into HTML")
        void errorDescriptionInRedirectUrl() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "google", null, null, "access_denied", "User denied access", request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertFalse(((String) response.getBody()).contains("User denied access"));
        }

        @Test
        @DisplayName("should notify an opener instead of redirecting to a missing page")
        void redirectsToConnectionsPage() {
            HttpServletRequest request = mockStandardRequest();

            ResponseEntity<?> response = controller.handleCallback(
                    "google", null, null, "access_denied", null, request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(((String) response.getBody()).contains("window.opener.postMessage"));
            assertFalse(((String) response.getBody()).contains("/connections"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Token endpoint
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /{providerId}/token")
    class Token {

        @Test
        @DisplayName("should indicate token presence without exposing value")
        void hasToken() {
            when(connectionService.getValidAccessToken("google"))
                    .thenReturn("secret-access-token-value");

            ResponseEntity<Map<String, Object>> response = controller.getAccessToken("google");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(true, body.get("hasToken"));
            assertEquals("google", body.get("providerId"));
        }

        @Test
        @DisplayName("should NEVER expose raw token value in response")
        void neverExposesTokenValue() {
            String secretToken = "ya29.secret-token-value-12345";
            when(connectionService.getValidAccessToken("google")).thenReturn(secretToken);

            ResponseEntity<Map<String, Object>> response = controller.getAccessToken("google");

            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertFalse(body.containsKey("token"),
                    "Response must not contain 'token' key");
            assertFalse(body.containsKey("accessToken"),
                    "Response must not contain 'accessToken' key");
            assertFalse(body.values().contains(secretToken),
                    "Token value must not appear anywhere in response body");
        }

        @Test
        @DisplayName("should indicate no token when provider is not connected")
        void noToken() {
            when(connectionService.getValidAccessToken("google")).thenReturn(null);

            ResponseEntity<Map<String, Object>> response = controller.getAccessToken("google");

            assertEquals(false, response.getBody().get("hasToken"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Credential endpoint
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /{providerId}/credential")
    class Credential {

        @Test
        @DisplayName("should hand the runtime token to the admin-token-gated caller")
        void exposesTokenToBridge() {
            when(connectionService.getValidAccessToken("google"))
                    .thenReturn("ya29.bridge-token");

            ResponseEntity<Map<String, Object>> response = controller.getCredential("google");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(true, body.get("hasToken"));
            assertEquals("google", body.get("providerId"));
            assertEquals("ya29.bridge-token", body.get("accessToken"));
        }

        @Test
        @DisplayName("should return 428 when the provider is not connected")
        void notConnected() {
            when(connectionService.getValidAccessToken("google")).thenReturn(null);

            ResponseEntity<Map<String, Object>> response = controller.getCredential("google");

            assertEquals(HttpStatus.PRECONDITION_REQUIRED, response.getStatusCode());
            assertEquals(false, response.getBody().get("hasToken"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Disconnect
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /{providerId}")
    class Disconnect {

        @Test
        @DisplayName("should return success on disconnect")
        void success() {
            when(connectionService.disconnect("google")).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.disconnect("google");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(true, response.getBody().get("success"));
        }

        @Test
        @DisplayName("should return 500 on disconnect error")
        void error() {
            when(connectionService.disconnect("google"))
                    .thenThrow(new RuntimeException("DB error"));

            ResponseEntity<Map<String, Object>> response = controller.disconnect("google");

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals(false, response.getBody().get("success"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Redirect URI construction
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Base URL construction for redirect URIs")
    class BaseUrlConstruction {

        @Test
        @DisplayName("configured public redirect base wins behind a reverse proxy")
        void configuredPublicBaseWins() {
            HttpServletRequest request = mockRequest("http", "crawl-manager.internal", 8082);
            when(connectionService.getConfiguredRedirectUri("reddit"))
                    .thenReturn("https://public.example/api/oauth/reddit/callback");
            when(connectionService.initiateAuthorization(eq("reddit"), anyString(), isNull()))
                    .thenReturn(AuthorizationUrlResponse.builder()
                            .authorizationUrl("url").state("s").providerId("reddit").build());

            controller.initiateAuthorization("reddit", null, null, request);

            verify(connectionService).initiateAuthorization("reddit",
                    "https://public.example/api/oauth/reddit/callback", null);
        }

        @Test
        @DisplayName("should omit port for standard HTTPS (443)")
        void standardHttps() {
            HttpServletRequest request = mockRequest("https", "app.example.com", 443);
            when(connectionService.initiateAuthorization(eq("google"), anyString(), isNull()))
                    .thenReturn(AuthorizationUrlResponse.builder()
                            .authorizationUrl("url").state("s").providerId("google").build());

            controller.initiateAuthorization("google", null, null, request);

            verify(connectionService).initiateAuthorization(eq("google"),
                    eq("https://app.example.com/api/oauth/google/callback"), isNull());
        }

        @Test
        @DisplayName("should omit port for standard HTTP (80)")
        void standardHttp() {
            HttpServletRequest request = mockRequest("http", "localhost", 80);
            when(connectionService.initiateAuthorization(eq("google"), anyString(), isNull()))
                    .thenReturn(AuthorizationUrlResponse.builder()
                            .authorizationUrl("url").state("s").providerId("google").build());

            controller.initiateAuthorization("google", null, null, request);

            verify(connectionService).initiateAuthorization(eq("google"),
                    eq("http://localhost/api/oauth/google/callback"), isNull());
        }

        @Test
        @DisplayName("should include non-standard port")
        void nonStandardPort() {
            HttpServletRequest request = mockRequest("http", "localhost", 9090);
            when(connectionService.initiateAuthorization(eq("google"), anyString(), isNull()))
                    .thenReturn(AuthorizationUrlResponse.builder()
                            .authorizationUrl("url").state("s").providerId("google").build());

            controller.initiateAuthorization("google", null, null, request);

            verify(connectionService).initiateAuthorization(eq("google"),
                    eq("http://localhost:9090/api/oauth/google/callback"), isNull());
        }

        @Test
        @DisplayName("should construct correct callback path per provider")
        void providerSpecificPath() {
            HttpServletRequest request = mockRequest("https", "app.example.com", 443);
            when(connectionService.initiateAuthorization(eq("atlassian"), anyString(), isNull()))
                    .thenReturn(AuthorizationUrlResponse.builder()
                            .authorizationUrl("url").state("s").providerId("atlassian").build());

            controller.initiateAuthorization("atlassian", null, null, request);

            verify(connectionService).initiateAuthorization(eq("atlassian"),
                    eq("https://app.example.com/api/oauth/atlassian/callback"), isNull());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Refresh
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /{providerId}/refresh")
    class Refresh {

        @Test
        @DisplayName("should return refreshed status on success")
        void success() {
            OAuthConnectionStatus status = OAuthConnectionStatus.builder()
                    .providerId("google").connected(true).tokenValid(true).build();
            when(connectionService.refreshConnection("google")).thenReturn(status);

            ResponseEntity<OAuthConnectionStatus> response = controller.refreshToken("google");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().isConnected());
        }

        @Test
        @DisplayName("should return 404 when no connection exists")
        void noConnection() {
            when(connectionService.refreshConnection("google"))
                    .thenThrow(new IllegalArgumentException("No connection"));

            ResponseEntity<OAuthConnectionStatus> response = controller.refreshToken("google");

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("should return 412 when no refresh token available")
        void noRefreshToken() {
            when(connectionService.refreshConnection("google"))
                    .thenThrow(new IllegalStateException("No refresh token"));

            ResponseEntity<OAuthConnectionStatus> response = controller.refreshToken("google");

            assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
        }
    }
}
