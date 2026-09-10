package ai.kompile.oauth.service;

import ai.kompile.oauth.domain.ConnectionStatus;
import ai.kompile.oauth.domain.OAuthConnection;
import ai.kompile.oauth.domain.PendingOAuthState;
import ai.kompile.oauth.dto.*;
import ai.kompile.oauth.repository.OAuthConnectionRepository;
import ai.kompile.oauth.repository.PendingOAuthStateRepository;
import ai.kompile.oauth.service.providers.OAuthProviderHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OAuthConnectionService")
class OAuthConnectionServiceTest {

    @Mock
    private OAuthConnectionRepository connectionRepository;

    @Mock
    private PendingOAuthStateRepository stateRepository;

    @Mock
    private TokenEncryptionService encryptionService;

    @Mock
    private OAuthProviderHandler googleHandler;

    private OAuthConnectionService service;

    @BeforeEach
    void setUp() throws Exception {
        when(googleHandler.getProviderId()).thenReturn("google");
        when(googleHandler.getDisplayName()).thenReturn("Google");
        when(googleHandler.getIcon()).thenReturn("google");
        when(googleHandler.isConfigured()).thenReturn(true);

        List<OAuthProviderHandler> handlers = List.of(googleHandler);
        service = new OAuthConnectionService(
                connectionRepository, stateRepository, encryptionService, handlers);
        when(stateRepository.consume(anyString(), anyString(), anyString()))
                .thenReturn(1);
        java.lang.reflect.Field owner = OAuthConnectionService.class
                .getDeclaredField("applicationName");
        owner.setAccessible(true);
        owner.set(service, "kompile-app-crawl-manager");
    }

    @Test
    void exposesConnectedProviderMetadataWithoutDecryptingTokens() {
        OAuthConnection connection = OAuthConnection.builder()
                .providerId("atlassian")
                .status(ConnectionStatus.CONNECTED)
                .providerData("[{\"id\":\"cloud-1\"}]")
                .build();
        when(connectionRepository.findById("atlassian")).thenReturn(Optional.of(connection));

        assertEquals("[{\"id\":\"cloud-1\"}]", service.getProviderData("atlassian"));
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void connectionReadinessRequiresAValidOrRefreshableToken() {
        OAuthConnection expiredWithoutRefresh = OAuthConnection.builder()
                .providerId("google")
                .status(ConnectionStatus.CONNECTED)
                .tokenExpiresAt(Instant.now().minusSeconds(60))
                .build();
        when(connectionRepository.findById("google"))
                .thenReturn(Optional.of(expiredWithoutRefresh));
        assertFalse(service.isConnectionUsable("google"));

        expiredWithoutRefresh.setRefreshTokenEncrypted("encrypted-refresh");
        assertTrue(service.isConnectionUsable("google"));
        expiredWithoutRefresh.setTokenExpiresAt(Instant.now().plusSeconds(3_600));
        expiredWithoutRefresh.setRefreshTokenEncrypted(null);
        assertTrue(service.isConnectionUsable("google"));
    }

    @Test
    void refreshFailureIsPersistedOutsideTheRollingBackCallerTransaction() {
        OAuthConnection connection = OAuthConnection.builder()
                .providerId("google")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .refreshTokenEncrypted("enc-refresh")
                .tokenExpiresAt(Instant.now().minusSeconds(60))
                .build();
        when(connectionRepository.findByProviderIdForUpdate("google"))
                .thenReturn(Optional.of(connection));
        when(encryptionService.decrypt("enc-access")).thenReturn("access");
        when(encryptionService.decrypt("enc-refresh")).thenReturn("refresh");
        when(googleHandler.refreshAccessToken("refresh")).thenReturn(
                OAuthTokenResponse.builder().error("invalid_grant")
                        .errorDescription("refresh revoked").build());

        assertThrows(RuntimeException.class, () -> service.refreshConnection("google"));

        assertEquals(ConnectionStatus.ERROR, connection.getStatus());
        assertEquals("Token refresh failed: refresh revoked", connection.getLastError());
        verify(connectionRepository).save(connection);
    }

    // ═══════════════════════════════════════════════════════════════
    // Authorization initiation
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("initiateAuthorization")
    class InitiateAuthorization {

        @Test
        @DisplayName("should reject unknown provider")
        void unknownProvider() {
            assertThrows(IllegalArgumentException.class, () ->
                    service.initiateAuthorization("unknown", "http://localhost/callback"));
        }

        @Test
        @DisplayName("should reject unconfigured provider")
        void unconfiguredProvider() {
            when(googleHandler.isConfigured()).thenReturn(false);

            assertThrows(IllegalStateException.class, () ->
                    service.initiateAuthorization("google", "http://localhost/callback"));
        }

        @Test
        @DisplayName("should persist CSRF state to database")
        void persistsStateToDB() {
            when(googleHandler.buildAuthorizationUrl(anyString(), anyString()))
                    .thenReturn("https://accounts.google.com/authorize?...");

            AuthorizationUrlResponse response = service.initiateAuthorization(
                    "google", "http://localhost/callback");

            assertNotNull(response);
            assertNotNull(response.getState());
            assertNotNull(response.getAuthorizationUrl());
            assertEquals("google", response.getProviderId());

            ArgumentCaptor<PendingOAuthState> captor = ArgumentCaptor.forClass(PendingOAuthState.class);
            verify(stateRepository).save(captor.capture());

            PendingOAuthState saved = captor.getValue();
            assertEquals("google", saved.getProviderId());
            assertEquals("http://localhost/callback", saved.getRedirectUri());
            assertNotNull(saved.getExpiresAt());
            assertTrue(saved.getExpiresAt().isAfter(Instant.now()));
        }

        @Test
        @DisplayName("configured public base is bound into OAuth state")
        void configuredPublicBaseIsBound() throws Exception {
            java.lang.reflect.Field redirectBase = OAuthConnectionService.class
                    .getDeclaredField("redirectBaseUrl");
            redirectBase.setAccessible(true);
            redirectBase.set(service, "https://public.example/");
            when(googleHandler.buildAuthorizationUrl(anyString(), anyString()))
                    .thenReturn("https://accounts.google.com/authorize");

            service.initiateAuthorization("google", null);

            ArgumentCaptor<PendingOAuthState> captor = ArgumentCaptor.forClass(PendingOAuthState.class);
            verify(stateRepository).save(captor.capture());
            assertEquals("https://public.example/api/oauth/google/callback",
                    captor.getValue().getRedirectUri());
        }

        @Test
        @DisplayName("should generate cryptographically unique state per call")
        void uniqueStates() {
            when(googleHandler.buildAuthorizationUrl(anyString(), anyString()))
                    .thenReturn("https://accounts.google.com/authorize");

            AuthorizationUrlResponse r1 = service.initiateAuthorization("google", "http://localhost/callback");
            AuthorizationUrlResponse r2 = service.initiateAuthorization("google", "http://localhost/callback");

            assertNotEquals(r1.getState(), r2.getState(),
                    "Each authorization flow must get a unique state token");
        }

        @Test
        @DisplayName("should set state expiry ~10 minutes in the future")
        void stateExpiry() {
            when(googleHandler.buildAuthorizationUrl(anyString(), anyString()))
                    .thenReturn("https://accounts.google.com/authorize");

            service.initiateAuthorization("google", "http://localhost/callback");

            ArgumentCaptor<PendingOAuthState> captor = ArgumentCaptor.forClass(PendingOAuthState.class);
            verify(stateRepository).save(captor.capture());

            PendingOAuthState saved = captor.getValue();
            Instant expectedMin = Instant.now().plusSeconds(590);
            Instant expectedMax = Instant.now().plusSeconds(610);
            assertTrue(saved.getExpiresAt().isAfter(expectedMin));
            assertTrue(saved.getExpiresAt().isBefore(expectedMax));
        }

        @Test
        @DisplayName("should pass only fixed supported authorization purposes to providers")
        void purposeProfile() {
            when(googleHandler.buildAuthorizationUrl(anyString(), anyString(), eq("channel")))
                    .thenReturn("https://accounts.google.com/authorize?scope=channel");

            AuthorizationUrlResponse response = service.initiateAuthorization(
                    "google", "http://localhost/callback", "channel");

            assertTrue(response.getAuthorizationUrl().contains("scope=channel"));
            verify(googleHandler).buildAuthorizationUrl(anyString(), anyString(), eq("channel"));
            assertThrows(IllegalArgumentException.class, () -> service.initiateAuthorization(
                    "google", "http://localhost/callback", "arbitrary"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Authorization completion (CSRF validation)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("completeAuthorization — state validation")
    class CompleteAuthorizationSecurity {

        @Test
        @DisplayName("should reject state that does not exist in DB")
        void invalidState() {
            when(stateRepository.findById("nonexistent-state")).thenReturn(Optional.empty());

            SecurityException ex = assertThrows(SecurityException.class, () ->
                    service.completeAuthorization("google", "code", "nonexistent-state", "http://localhost/callback"));
            assertTrue(ex.getMessage().contains("Invalid"));
        }

        @Test
        @DisplayName("should reject state bound to a different provider (CSRF attack)")
        void providerMismatch() {
            PendingOAuthState state = PendingOAuthState.builder()
                    .state("state-for-microsoft")
                    .providerId("microsoft")
                    .redirectUri("http://localhost/callback")
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(600))
                    .build();
            when(stateRepository.findById("state-for-microsoft")).thenReturn(Optional.of(state));

            assertThrows(SecurityException.class, () ->
                    service.completeAuthorization("google", "code", "state-for-microsoft", "http://localhost/callback"));
        }

        @Test
        @DisplayName("should reject expired state and clean it up")
        void expiredState() {
            PendingOAuthState state = PendingOAuthState.builder()
                    .state("expired-state")
                    .providerId("google")
                    .redirectUri("http://localhost/callback")
                    .createdAt(Instant.now().minusSeconds(700))
                    .expiresAt(Instant.now().minusSeconds(100))
                    .build();
            when(stateRepository.findById("expired-state")).thenReturn(Optional.of(state));

            assertThrows(SecurityException.class, () ->
                    service.completeAuthorization("google", "code", "expired-state", "http://localhost/callback"));

            verify(stateRepository).deleteStateClaim("expired-state");
        }

        @Test
        @DisplayName("should consume state after use — prevents replay attacks")
        void oneTimeUse() {
            PendingOAuthState state = validState("one-time-state");
            when(stateRepository.findById("one-time-state")).thenReturn(Optional.of(state));
            mockSuccessfulTokenExchange();

            service.completeAuthorization("google", "auth-code", "one-time-state", "http://localhost/callback");

            verify(stateRepository).consume(
                    "one-time-state", "google", "http://localhost/callback");
        }

        @Test
        @DisplayName("callback token exchange reuses redirect URI bound into state")
        void storedRedirectUriDrivesTokenExchange() {
            PendingOAuthState state = validState("stored-redirect-state");
            when(stateRepository.findById("stored-redirect-state")).thenReturn(Optional.of(state));
            mockSuccessfulTokenExchange();

            service.completeAuthorization("google", "auth-code", "stored-redirect-state");

            verify(googleHandler).exchangeCodeForTokens("auth-code", "http://localhost/callback");
        }

        @Test
        @DisplayName("a concurrently consumed state cannot exchange a second token")
        void atomicClaimRejectsReplay() {
            PendingOAuthState state = validState("raced-state");
            when(stateRepository.findById("raced-state")).thenReturn(Optional.of(state));
            when(stateRepository.consume("raced-state", "google", "http://localhost/callback"))
                    .thenReturn(0);

            assertThrows(SecurityException.class,
                    () -> service.completeAuthorization("google", "code", "raced-state"));
            verify(googleHandler, never()).exchangeCodeForTokens(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("completeAuthorization — token handling")
    class CompleteAuthorizationTokens {

        @Test
        @DisplayName("should encrypt tokens before persisting to DB")
        void encryptsTokens() {
            PendingOAuthState state = validState("enc-state");
            when(stateRepository.findById("enc-state")).thenReturn(Optional.of(state));

            OAuthTokenResponse tokenResponse = OAuthTokenResponse.builder()
                    .accessToken("real-access-token")
                    .refreshToken("real-refresh-token")
                    .expiresIn(3600L)
                    .build();
            when(googleHandler.exchangeCodeForTokens("code", "http://localhost/callback"))
                    .thenReturn(tokenResponse);
            when(googleHandler.getUserInfo("real-access-token")).thenReturn(null);
            when(encryptionService.encrypt("real-access-token")).thenReturn("enc-access");
            when(encryptionService.encrypt("real-refresh-token")).thenReturn("enc-refresh");
            when(connectionRepository.findById("google")).thenReturn(Optional.empty());
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.completeAuthorization("google", "code", "enc-state", "http://localhost/callback");

            ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
            verify(connectionRepository).save(captor.capture());

            OAuthConnection saved = captor.getValue();
            assertEquals("enc-access", saved.getAccessTokenEncrypted());
            assertEquals("enc-refresh", saved.getRefreshTokenEncrypted());
            assertEquals(ConnectionStatus.CONNECTED, saved.getStatus());
        }

        @Test
        @DisplayName("reauthorization preserves provider metadata when enrichment is temporarily unavailable")
        void preservesProviderDataOnReauthorization() {
            PendingOAuthState state = validState("provider-data-state");
            when(stateRepository.findById("provider-data-state")).thenReturn(Optional.of(state));
            OAuthTokenResponse tokenResponse = OAuthTokenResponse.builder()
                    .accessToken("new-access").refreshToken("new-refresh").expiresIn(3600L)
                    .providerData(null).build();
            when(googleHandler.exchangeCodeForTokens("code", "http://localhost/callback"))
                    .thenReturn(tokenResponse);
            when(googleHandler.getUserInfo("new-access")).thenReturn(null);
            when(encryptionService.encrypt(anyString())).thenReturn("encrypted");
            OAuthConnection existing = OAuthConnection.builder()
                    .providerId("google")
                    .providerData("[{\"id\":\"cloud-existing\"}]")
                    .build();
            when(connectionRepository.findById("google")).thenReturn(Optional.of(existing));
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.completeAuthorization("google", "code", "provider-data-state");

            assertEquals("[{\"id\":\"cloud-existing\"}]", existing.getProviderData());
        }

        @Test
        @DisplayName("should store user info from provider")
        void storesUserInfo() {
            PendingOAuthState state = validState("user-state");
            when(stateRepository.findById("user-state")).thenReturn(Optional.of(state));

            OAuthTokenResponse tokenResponse = OAuthTokenResponse.builder()
                    .accessToken("token").refreshToken("refresh").expiresIn(3600L).build();
            when(googleHandler.exchangeCodeForTokens("code", "http://localhost/callback"))
                    .thenReturn(tokenResponse);
            OAuthUserInfo userInfo = OAuthUserInfo.builder()
                    .email("user@gmail.com").name("Test User").picture("https://photo.url").build();
            when(googleHandler.getUserInfo("token")).thenReturn(userInfo);
            when(encryptionService.encrypt(anyString())).thenReturn("encrypted");
            when(connectionRepository.findById("google")).thenReturn(Optional.empty());
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.completeAuthorization("google", "code", "user-state", "http://localhost/callback");

            ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
            verify(connectionRepository).save(captor.capture());

            OAuthConnection saved = captor.getValue();
            assertEquals("user@gmail.com", saved.getUserEmail());
            assertEquals("Test User", saved.getUserName());
        }

        @Test
        @DisplayName("should reject failed token exchange")
        void failedTokenExchange() {
            PendingOAuthState state = validState("fail-state");
            when(stateRepository.findById("fail-state")).thenReturn(Optional.of(state));

            OAuthTokenResponse failedResponse = OAuthTokenResponse.builder()
                    .error("invalid_grant")
                    .errorDescription("Code has expired")
                    .build();
            when(googleHandler.exchangeCodeForTokens("bad-code", "http://localhost/callback"))
                    .thenReturn(failedResponse);

            assertThrows(RuntimeException.class, () ->
                    service.completeAuthorization("google", "bad-code", "fail-state", "http://localhost/callback"));

            verify(connectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should handle null refresh token gracefully")
        void nullRefreshToken() {
            PendingOAuthState state = validState("no-refresh-state");
            when(stateRepository.findById("no-refresh-state")).thenReturn(Optional.of(state));

            OAuthTokenResponse tokenResponse = OAuthTokenResponse.builder()
                    .accessToken("access-only")
                    .refreshToken(null)
                    .expiresIn(3600L)
                    .build();
            when(googleHandler.exchangeCodeForTokens("code", "http://localhost/callback"))
                    .thenReturn(tokenResponse);
            when(googleHandler.getUserInfo("access-only")).thenReturn(null);
            when(encryptionService.encrypt("access-only")).thenReturn("enc-access");
            when(connectionRepository.findById("google")).thenReturn(Optional.empty());
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.completeAuthorization("google", "code", "no-refresh-state", "http://localhost/callback");

            ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
            verify(connectionRepository).save(captor.capture());
            assertNull(captor.getValue().getRefreshTokenEncrypted());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Token retrieval
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getValidAccessToken")
    class GetValidAccessToken {

        @Test
        @DisplayName("should return null for missing connection")
        void missingConnection() {
            when(connectionRepository.findById("google")).thenReturn(Optional.empty());
            assertNull(service.getValidAccessToken("google"));
        }

        @Test
        @DisplayName("should return null for disconnected provider")
        void disconnectedProvider() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .status(ConnectionStatus.DISCONNECTED)
                    .build();
            when(connectionRepository.findById("google")).thenReturn(Optional.of(conn));

            assertNull(service.getValidAccessToken("google"));
        }

        @Test
        @DisplayName("should return null for error-state connection")
        void errorState() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .status(ConnectionStatus.ERROR)
                    .build();
            when(connectionRepository.findById("google")).thenReturn(Optional.of(conn));

            assertNull(service.getValidAccessToken("google"));
        }

        @Test
        @DisplayName("should decrypt and return valid token")
        void validToken() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .status(ConnectionStatus.CONNECTED)
                    .accessTokenEncrypted("encrypted-token")
                    .tokenExpiresAt(Instant.now().plusSeconds(3600))
                    .createdAt(Instant.now())
                    .build();
            when(connectionRepository.findById("google")).thenReturn(Optional.of(conn));
            when(encryptionService.decrypt("encrypted-token")).thenReturn("decrypted-token");
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertEquals("decrypted-token", service.getValidAccessToken("google"));
        }

        @Test
        @DisplayName("should update lastUsedAt on access")
        void updatesLastUsed() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .status(ConnectionStatus.CONNECTED)
                    .accessTokenEncrypted("encrypted")
                    .tokenExpiresAt(Instant.now().plusSeconds(3600))
                    .createdAt(Instant.now())
                    .build();
            when(connectionRepository.findById("google")).thenReturn(Optional.of(conn));
            when(encryptionService.decrypt("encrypted")).thenReturn("token");
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.getValidAccessToken("google");

            verify(connectionRepository).updateLastUsedAt(eq("google"), any(Instant.class));
        }

        @Test
        @DisplayName("current token read does not refresh or mutate OAuth lifecycle state")
        void currentTokenIsReadOnlyAndScopesAreCanonical() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .status(ConnectionStatus.CONNECTED)
                    .accessTokenEncrypted("encrypted")
                    .scope("chat:write,channels:read users:read")
                    .tokenExpiresAt(Instant.now().plusSeconds(3600))
                    .createdAt(Instant.now())
                    .build();
            when(connectionRepository.findById("google")).thenReturn(Optional.of(conn));
            when(encryptionService.decrypt("encrypted")).thenReturn("token");

            assertEquals("token", service.getCurrentAccessToken("google"));
            assertEquals(List.of("chat:write", "channels:read", "users:read"),
                    service.getGrantedScopes("google"));
            verify(connectionRepository, never()).updateLastUsedAt(anyString(), any());
            verify(googleHandler, never()).refreshAccessToken(anyString());
        }

        @Test
        @DisplayName("automatic refresh rechecks expiry after acquiring the provider lock")
        void refreshRechecksExpiryAfterLock() {
            OAuthConnection stale = OAuthConnection.builder()
                    .providerId("google").status(ConnectionStatus.CONNECTED)
                    .accessTokenEncrypted("old-access").refreshTokenEncrypted("old-refresh")
                    .tokenExpiresAt(Instant.now().minusSeconds(60)).build();
            OAuthConnection alreadyRefreshed = OAuthConnection.builder()
                    .providerId("google").status(ConnectionStatus.CONNECTED)
                    .accessTokenEncrypted("new-access").refreshTokenEncrypted("new-refresh")
                    .tokenExpiresAt(Instant.now().plusSeconds(3600)).build();
            when(connectionRepository.findById("google"))
                    .thenReturn(Optional.of(stale), Optional.of(alreadyRefreshed),
                            Optional.of(alreadyRefreshed));
            when(connectionRepository.findByProviderIdForUpdate("google"))
                    .thenReturn(Optional.of(alreadyRefreshed));
            when(encryptionService.decrypt("old-access")).thenReturn("old-token");
            when(encryptionService.decrypt("old-refresh")).thenReturn("old-refresh-token");
            when(encryptionService.decrypt("new-access")).thenReturn("new-token");
            when(encryptionService.decrypt("new-refresh")).thenReturn("new-refresh-token");
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertEquals("new-token", service.getValidAccessToken("google"));

            verify(googleHandler, never()).refreshAccessToken(anyString());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Disconnect
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("disconnect")
    class Disconnect {

        @Test
        @DisplayName("should revoke tokens and delete connection")
        void revokeAndDelete() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .accessTokenEncrypted("enc-access")
                    .refreshTokenEncrypted("enc-refresh")
                    .status(ConnectionStatus.CONNECTED)
                    .createdAt(Instant.now())
                    .build();
            when(connectionRepository.findByProviderIdForUpdate("google"))
                    .thenReturn(Optional.of(conn));
            when(encryptionService.decrypt("enc-access")).thenReturn("access-token");
            when(encryptionService.decrypt("enc-refresh")).thenReturn("refresh-token");
            when(googleHandler.revokeToken("access-token", "refresh-token")).thenReturn(true);

            assertTrue(service.disconnect("google"));

            verify(googleHandler).revokeToken("access-token", "refresh-token");
            verify(connectionRepository).delete(conn);
        }

        @Test
        @DisplayName("should succeed for already-disconnected provider")
        void alreadyDisconnected() {
            when(connectionRepository.findByProviderIdForUpdate("google"))
                    .thenReturn(Optional.empty());
            assertTrue(service.disconnect("google"));
            verify(connectionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("failed provider revocation retains retryable encrypted credentials")
        void failedRevocationRetainsConnection() {
            OAuthConnection conn = OAuthConnection.builder()
                    .providerId("google")
                    .accessTokenEncrypted("enc-access")
                    .refreshTokenEncrypted("enc-refresh")
                    .status(ConnectionStatus.CONNECTED)
                    .build();
            when(connectionRepository.findByProviderIdForUpdate("google"))
                    .thenReturn(Optional.of(conn));
            when(encryptionService.decrypt("enc-access")).thenReturn("access-token");
            when(encryptionService.decrypt("enc-refresh")).thenReturn("refresh-token");
            when(googleHandler.revokeToken("access-token", "refresh-token")).thenReturn(false);

            assertFalse(service.disconnect("google"));

            assertEquals(ConnectionStatus.ERROR, conn.getStatus());
            assertNotNull(conn.getRefreshTokenEncrypted());
            verify(connectionRepository).save(conn);
            verify(connectionRepository, never()).delete(any());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Scheduled cleanup
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cleanupExpiredStates")
    class CleanupExpiredStates {

        @Test
        @DisplayName("should delete expired states from DB")
        void deletesExpired() {
            when(stateRepository.deleteExpired(any())).thenReturn(3);
            service.cleanupExpiredStates();
            verify(stateRepository).deleteExpired(any(Instant.class));
        }
    }

    @Nested
    @DisplayName("Construction with no handlers")
    class NoHandlers {

        @Test
        @DisplayName("should work with null handler list")
        void nullHandlers() {
            OAuthConnectionService svc = new OAuthConnectionService(
                    connectionRepository, stateRepository, encryptionService, null);
            assertTrue(svc.getAvailableProviders().isEmpty());
        }

        @Test
        @DisplayName("should work with empty handler list")
        void emptyHandlers() {
            OAuthConnectionService svc = new OAuthConnectionService(
                    connectionRepository, stateRepository, encryptionService, List.of());
            assertTrue(svc.getAvailableProviders().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private PendingOAuthState validState(String stateValue) {
        return PendingOAuthState.builder()
                .state(stateValue)
                .providerId("google")
                .redirectUri("http://localhost/callback")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    private void mockSuccessfulTokenExchange() {
        OAuthTokenResponse tokenResponse = OAuthTokenResponse.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .expiresIn(3600L)
                .scope("email profile")
                .build();
        when(googleHandler.exchangeCodeForTokens(anyString(), anyString()))
                .thenReturn(tokenResponse);
        when(googleHandler.getUserInfo("access-token"))
                .thenReturn(OAuthUserInfo.builder()
                        .email("user@gmail.com").name("Test User").build());
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted");
        when(connectionRepository.findById("google")).thenReturn(Optional.empty());
        when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }
}
