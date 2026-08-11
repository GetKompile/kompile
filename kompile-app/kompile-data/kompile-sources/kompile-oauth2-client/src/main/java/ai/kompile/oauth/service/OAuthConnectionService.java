/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.oauth.service;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.auth.OAuthCredentialLifecycle;
import ai.kompile.oauth.domain.ConnectionStatus;
import ai.kompile.oauth.domain.OAuthConnection;
import ai.kompile.oauth.domain.PendingOAuthState;
import ai.kompile.oauth.dto.*;
import ai.kompile.oauth.repository.OAuthConnectionRepository;
import ai.kompile.oauth.repository.PendingOAuthStateRepository;
import ai.kompile.oauth.service.providers.OAuthProviderHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing OAuth connections to external providers.
 * Provides a unified API for authorization, token management, and connection status.
 */
@Service
public class OAuthConnectionService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected OAuthConnectionService() {}


    private static final Logger log = LoggerFactory.getLogger(OAuthConnectionService.class);
    private static final long MINIMUM_TOKEN_VALIDITY_MILLIS = 300_000L;

    private OAuthConnectionRepository repository;
    private PendingOAuthStateRepository stateRepository;
    private TokenEncryptionService encryptionService;
    private final Map<String, OAuthProviderHandler> handlers = new ConcurrentHashMap<>();

    @Value("${kompile.oauth.redirect-base-url:}")
    private String redirectBaseUrl;

    @Autowired
    public OAuthConnectionService(
            OAuthConnectionRepository repository,
            PendingOAuthStateRepository stateRepository,
            TokenEncryptionService encryptionService,
            @Autowired(required = false) List<OAuthProviderHandler> providerHandlers) {
        this.repository = repository;
        this.stateRepository = stateRepository;
        this.encryptionService = encryptionService;

        // Register all available handlers
        if (providerHandlers != null) {
            for (OAuthProviderHandler handler : providerHandlers) {
                handlers.put(handler.getProviderId(), handler);
                log.info("Registered OAuth provider handler: {} (configured: {})",
                        handler.getProviderId(), handler.isConfigured());
            }
        }
    }

    @PostConstruct
    public void init() {
        log.info("OAuth Connection Service initialized with {} providers", handlers.size());
    }

    /**
     * Get information about all available OAuth providers.
     */
    public List<OAuthProviderInfo> getAvailableProviders() {
        return handlers.values().stream()
                .map(OAuthProviderHandler::getProviderInfo)
                .collect(Collectors.toList());
    }

    /**
     * Get information about a specific provider.
     */
    public Optional<OAuthProviderInfo> getProviderInfo(String providerId) {
        OAuthProviderHandler handler = handlers.get(providerId);
        return handler != null ? Optional.of(handler.getProviderInfo()) : Optional.empty();
    }

    /**
     * Get all OAuth connections with their status.
     */
    public List<OAuthConnectionDto> getAllConnections() {
        List<OAuthConnectionDto> result = new ArrayList<>();

        for (OAuthProviderHandler handler : handlers.values()) {
            Optional<OAuthConnection> connection = repository.findById(handler.getProviderId());
            if (connection.isPresent()) {
                result.add(OAuthConnectionDto.fromEntity(
                        connection.get(),
                        handler.getDisplayName(),
                        handler.getIcon()
                ));
            } else {
                // Add disconnected placeholder
                result.add(OAuthConnectionDto.builder()
                        .providerId(handler.getProviderId())
                        .providerDisplayName(handler.getDisplayName())
                        .providerIcon(handler.getIcon())
                        .status("disconnected")
                        .build());
            }
        }

        return result;
    }

    /**
     * Get connection status for a specific provider.
     */
    public OAuthConnectionStatus getConnectionStatus(String providerId) {
        Optional<OAuthConnection> connection = repository.findById(providerId);

        if (connection.isEmpty()) {
            return OAuthConnectionStatus.builder()
                    .providerId(providerId)
                    .status(ConnectionStatus.DISCONNECTED)
                    .connected(false)
                    .build();
        }

        OAuthConnection conn = connection.get();
        return OAuthConnectionStatus.builder()
                .providerId(providerId)
                .status(conn.getStatus())
                .connected(conn.getStatus() == ConnectionStatus.CONNECTED)
                .tokenValid(!conn.isTokenExpired())
                .needsRefresh(conn.isTokenExpired())
                .expiresAt(conn.getTokenExpiresAt())
                .userEmail(conn.getUserEmail())
                .userName(conn.getUserName())
                .errorMessage(conn.getStatus() == ConnectionStatus.ERROR ? conn.getLastError() : null)
                .build();
    }

    /**
     * Initiate OAuth authorization flow.
     * Returns the authorization URL and state for the provider.
     */
    public AuthorizationUrlResponse initiateAuthorization(String providerId, String customRedirectUri) {
        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + providerId);
        }

        if (!handler.isConfigured()) {
            throw new IllegalStateException("OAuth provider not configured: " + providerId);
        }

        // Determine and bind the redirect URI before persisting the CSRF state.
        String redirectUri = customRedirectUri;
        if (redirectUri == null || redirectUri.isEmpty()) {
            if (redirectBaseUrl != null && !redirectBaseUrl.isEmpty()) {
                redirectUri = redirectBaseUrl + "/api/oauth/" + providerId + "/callback";
            } else {
                // Will need to be provided by the frontend
                redirectUri = "/api/oauth/" + providerId + "/callback";
            }
        }

        String state = generateState();
        Instant createdAt = Instant.now();
        stateRepository.save(PendingOAuthState.builder()
                .state(state)
                .providerId(providerId)
                .redirectUri(redirectUri)
                .createdAt(createdAt)
                .expiresAt(createdAt.plusSeconds(600))
                .build());

        String authUrl = handler.buildAuthorizationUrl(redirectUri, state);

        return AuthorizationUrlResponse.builder()
                .authorizationUrl(authUrl)
                .state(state)
                .providerId(providerId)
                .build();
    }

    /**
     * Complete OAuth authorization by exchanging code for tokens.
     */
    @Transactional
    public OAuthConnectionDto completeAuthorization(String providerId, String code, String state, String redirectUri) {
        // Resolve and consume the persisted CSRF state exactly once.
        PendingOAuthState pendingState = stateRepository.findById(state)
                .orElseThrow(() -> new SecurityException("Invalid OAuth state parameter"));
        if (pendingState.isExpired()) {
            stateRepository.delete(pendingState);
            throw new SecurityException("Invalid or expired OAuth state parameter");
        }
        if (!pendingState.getProviderId().equals(providerId)
                || !Objects.equals(pendingState.getRedirectUri(), redirectUri)) {
            stateRepository.delete(pendingState);
            throw new SecurityException("Invalid OAuth state parameter");
        }
        stateRepository.delete(pendingState);

        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + providerId);
        }

        // Exchange code for tokens
        OAuthTokenResponse tokenResponse = handler.exchangeCodeForTokens(code, redirectUri);

        if (!tokenResponse.isSuccess()) {
            throw new RuntimeException("Token exchange failed: " +
                    tokenResponse.getError() + " - " + tokenResponse.getErrorDescription());
        }

        // Get user info
        OAuthUserInfo userInfo = handler.getUserInfo(tokenResponse.getAccessToken());

        // Create or update connection
        OAuthConnection connection = repository.findById(providerId)
                .orElse(new OAuthConnection());

        connection.setProviderId(providerId);
        applyCredential(connection, credentialFrom(tokenResponse, null));
        connection.setScope(tokenResponse.getScope());
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setLastError(null);
        connection.setCreatedAt(Instant.now());
        connection.setLastRefreshedAt(Instant.now());

        if (userInfo != null) {
            connection.setUserEmail(userInfo.getEmail());
            connection.setUserName(userInfo.getName());
            connection.setUserPicture(userInfo.getPicture());
        }

        connection.setProviderData(tokenResponse.getProviderData());

        repository.save(connection);

        log.info("OAuth connection established for provider: {} (user: {})",
                providerId, connection.getUserEmail());

        return OAuthConnectionDto.fromEntity(connection, handler.getDisplayName(), handler.getIcon());
    }

    /**
     * Get a valid access token for a provider, refreshing if necessary.
     */
    @Transactional
    public String getValidAccessToken(String providerId) {
        Optional<OAuthConnection> connectionOpt = repository.findById(providerId);
        if (connectionOpt.isEmpty()) {
            return null;
        }

        OAuthConnection connection = connectionOpt.get();

        if (connection.getStatus() != ConnectionStatus.CONNECTED) {
            return null;
        }

        ManagedCredential current = decryptCredential(connection);
        ManagedCredential resolved;
        try {
            resolved = OAuthCredentialLifecycle.resolve(
                    current,
                    MINIMUM_TOKEN_VALIDITY_MILLIS,
                    System.currentTimeMillis(),
                    ignored -> {
                        refreshConnection(providerId);
                        OAuthConnection refreshed = repository.findById(providerId).orElse(null);
                        if (refreshed == null || refreshed.getStatus() != ConnectionStatus.CONNECTED) {
                            throw new IOException("OAuth connection disappeared while refreshing " + providerId);
                        }
                        return decryptCredential(refreshed);
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve OAuth credential for " + providerId, e);
        }

        if (resolved != current) {
            connection = repository.findById(providerId).orElse(null);
            if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
                return null;
            }
        }

        connection.setLastUsedAt(Instant.now());
        repository.save(connection);
        return resolved.getAccess();
    }

    /**
     * Refresh the access token for a provider.
     */
    @Transactional
    public OAuthConnectionStatus refreshConnection(String providerId) {
        Optional<OAuthConnection> connectionOpt = repository.findById(providerId);
        if (connectionOpt.isEmpty()) {
            throw new IllegalArgumentException("No connection found for provider: " + providerId);
        }

        OAuthConnection connection = connectionOpt.get();
        if (!connection.canRefresh()) {
            throw new IllegalStateException("Connection cannot be refreshed (no refresh token)");
        }

        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + providerId);
        }

        ManagedCredential current = decryptCredential(connection);
        OAuthTokenResponse tokenResponse = handler.refreshAccessToken(current.getRefresh());

        if (!tokenResponse.isSuccess()) {
            connection.setStatus(ConnectionStatus.ERROR);
            connection.setLastError("Token refresh failed: " + tokenResponse.getErrorDescription());
            repository.save(connection);
            throw new RuntimeException("Token refresh failed: " + tokenResponse.getErrorDescription());
        }

        try {
            ManagedCredential refreshed = OAuthCredentialLifecycle.refresh(
                    current,
                    ignored -> credentialFrom(tokenResponse, current));
            applyCredential(connection, refreshed);
        } catch (IOException e) {
            throw new RuntimeException("Invalid OAuth refresh result for " + providerId, e);
        }
        connection.setLastRefreshedAt(Instant.now());
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setLastError(null);

        repository.save(connection);

        log.info("OAuth token refreshed for provider: {}", providerId);

        return getConnectionStatus(providerId);
    }

    /**
     * Disconnect from a provider (revoke tokens).
     */
    @Transactional
    public boolean disconnect(String providerId) {
        Optional<OAuthConnection> connectionOpt = repository.findById(providerId);
        if (connectionOpt.isEmpty()) {
            return true; // Already disconnected
        }

        OAuthConnection connection = connectionOpt.get();
        OAuthProviderHandler handler = handlers.get(providerId);

        if (handler != null) {
            ManagedCredential credential = decryptCredential(connection);
            try {
                OAuthCredentialLifecycle.revoke(credential, handler::revokeToken);
            } catch (IOException e) {
                throw new RuntimeException("Failed to revoke OAuth credential for " + providerId, e);
            }
        }

        // Delete connection from database
        repository.delete(connection);

        log.info("OAuth connection disconnected for provider: {}", providerId);
        return true;
    }

    private ManagedCredential decryptCredential(OAuthConnection connection) {
        String refreshToken = connection.getRefreshTokenEncrypted() == null
                ? ""
                : encryptionService.decrypt(connection.getRefreshTokenEncrypted());
        long expires = connection.getTokenExpiresAt() == null
                ? Long.MAX_VALUE
                : connection.getTokenExpiresAt().toEpochMilli();
        return ManagedCredential.oauth(
                encryptionService.decrypt(connection.getAccessTokenEncrypted()),
                refreshToken,
                expires);
    }

    private ManagedCredential credentialFrom(
            OAuthTokenResponse response,
            ManagedCredential current) {
        String refreshToken = response.getRefreshToken() != null
                ? response.getRefreshToken()
                : current == null ? "" : current.getRefresh();
        long expires = response.getExpiresAt() != null
                ? response.getExpiresAt().toEpochMilli()
                : current == null ? Long.MAX_VALUE : current.getExpires();
        return ManagedCredential.oauth(
                response.getAccessToken(),
                refreshToken,
                expires,
                current == null ? Map.of() : current.getMetadata());
    }

    private void applyCredential(OAuthConnection connection, ManagedCredential credential) {
        connection.setAccessTokenEncrypted(encryptionService.encrypt(credential.getAccess()));
        connection.setRefreshTokenEncrypted(credential.hasRefreshToken()
                ? encryptionService.encrypt(credential.getRefresh())
                : null);
        connection.setTokenExpiresAt(credential.getExpires() == Long.MAX_VALUE
                ? null
                : Instant.ofEpochMilli(credential.getExpires()));
    }

    /**
     * Check if a provider is connected.
     */
    public boolean isConnected(String providerId) {
        return repository.isConnected(providerId);
    }

    /**
     * Check connection health by validating the token.
     */
    public boolean checkConnectionHealth(String providerId) {
        String accessToken = getValidAccessToken(providerId);
        if (accessToken == null) {
            return false;
        }

        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null) {
            return false;
        }

        return handler.validateToken(accessToken);
    }

    /**
     * Scheduled task to refresh tokens that are about to expire.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void refreshExpiringTokens() {
        // Find connections expiring in the next 10 minutes
        Instant expiryThreshold = Instant.now().plusSeconds(600);
        List<OAuthConnection> expiringConnections = repository.findConnectionsNeedingRefresh(expiryThreshold);

        for (OAuthConnection connection : expiringConnections) {
            try {
                log.info("Auto-refreshing expiring OAuth token for provider: {}", connection.getProviderId());
                refreshConnection(connection.getProviderId());
            } catch (Exception e) {
                log.warn("Failed to auto-refresh OAuth token for {}: {}",
                        connection.getProviderId(), e.getMessage());
            }
        }
    }

    /**
     * Clean up expired authorization states.
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    @Transactional
    public void cleanupExpiredStates() {
        int deleted = stateRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.debug("Deleted {} expired OAuth authorization states", deleted);
        }
    }

    /**
     * Generate a secure random state string.
     */
    private String generateState() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Get the redirect URI for a provider callback.
     */
    public String getCallbackRedirectUri(String providerId) {
        if (redirectBaseUrl != null && !redirectBaseUrl.isEmpty()) {
            return redirectBaseUrl + "/api/oauth/" + providerId + "/callback";
        }
        return "/api/oauth/" + providerId + "/callback";
    }
}
