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
    void setUp() {
        when(googleHandler.getProviderId()).thenReturn("google");
        when(googleHandler.getDisplayName()).thenReturn("Google");
        when(googleHandler.getIcon()).thenReturn("google");
        when(googleHandler.isConfigured()).thenReturn(true);

        List<OAuthProviderHandler> handlers = List.of(googleHandler);
        service = new OAuthConnectionService(connectionRepository, encryptionService, handlers);
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

            verify(stateRepository).delete(state);
        }

        @Test
        @DisplayName("should consume state after use — prevents replay attacks")
        void oneTimeUse() {
            PendingOAuthState state = validState("one-time-state");
            when(stateRepository.findById("one-time-state")).thenReturn(Optional.of(state));
            mockSuccessfulTokenExchange();

            service.completeAuthorization("google", "auth-code", "one-time-state", "http://localhost/callback");

            verify(stateRepository).delete(state);
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

            assertNotNull(conn.getLastUsedAt());
            verify(connectionRepository).save(conn);
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
            when(connectionRepository.findById("google")).thenReturn(Optional.of(conn));
            when(encryptionService.decrypt("enc-access")).thenReturn("access-token");
            when(encryptionService.decrypt("enc-refresh")).thenReturn("refresh-token");

            assertTrue(service.disconnect("google"));

            verify(googleHandler).revokeToken("access-token", "refresh-token");
            verify(connectionRepository).delete(conn);
        }

        @Test
        @DisplayName("should succeed for already-disconnected provider")
        void alreadyDisconnected() {
            when(connectionRepository.findById("google")).thenReturn(Optional.empty());
            assertTrue(service.disconnect("google"));
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
                    connectionRepository, encryptionService, null);
            assertTrue(svc.getAvailableProviders().isEmpty());
        }

        @Test
        @DisplayName("should work with empty handler list")
        void emptyHandlers() {
            OAuthConnectionService svc = new OAuthConnectionService(
                    connectionRepository, encryptionService, List.of());
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
