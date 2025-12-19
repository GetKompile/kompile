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

import ai.kompile.oauth.domain.ConnectionStatus;
import ai.kompile.oauth.domain.OAuthConnection;
import ai.kompile.oauth.dto.*;
import ai.kompile.oauth.repository.OAuthConnectionRepository;
import ai.kompile.oauth.service.providers.OAuthProviderHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger log = LoggerFactory.getLogger(OAuthConnectionService.class);

    private final OAuthConnectionRepository repository;
    private final TokenEncryptionService encryptionService;
    private final Map<String, OAuthProviderHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, String> pendingStates = new ConcurrentHashMap<>();

    @Value("${kompile.oauth.redirect-base-url:}")
    private String redirectBaseUrl;

    @Autowired
    public OAuthConnectionService(
            OAuthConnectionRepository repository,
            TokenEncryptionService encryptionService,
            @Autowired(required = false) List<OAuthProviderHandler> providerHandlers) {
        this.repository = repository;
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

        // Generate state for CSRF protection
        String state = generateState();
        pendingStates.put(state, providerId);

        // Determine redirect URI
        String redirectUri = customRedirectUri;
        if (redirectUri == null || redirectUri.isEmpty()) {
            if (redirectBaseUrl != null && !redirectBaseUrl.isEmpty()) {
                redirectUri = redirectBaseUrl + "/api/oauth/" + providerId + "/callback";
            } else {
                // Will need to be provided by the frontend
                redirectUri = "/api/oauth/" + providerId + "/callback";
            }
        }

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
        // Validate state
        String expectedProvider = pendingStates.remove(state);
        if (expectedProvider == null || !expectedProvider.equals(providerId)) {
            throw new SecurityException("Invalid OAuth state parameter");
        }

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
        connection.setAccessTokenEncrypted(encryptionService.encrypt(tokenResponse.getAccessToken()));
        connection.setRefreshTokenEncrypted(
                tokenResponse.getRefreshToken() != null ?
                        encryptionService.encrypt(tokenResponse.getRefreshToken()) : null);
        connection.setTokenExpiresAt(tokenResponse.getExpiresAt());
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

        // Check if token needs refresh
        if (connection.isTokenExpired() && connection.canRefresh()) {
            refreshConnection(providerId);
            connection = repository.findById(providerId).orElse(null);
            if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
                return null;
            }
        }

        // Update last used timestamp
        connection.setLastUsedAt(Instant.now());
        repository.save(connection);

        return encryptionService.decrypt(connection.getAccessTokenEncrypted());
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

        String refreshToken = encryptionService.decrypt(connection.getRefreshTokenEncrypted());
        OAuthTokenResponse tokenResponse = handler.refreshAccessToken(refreshToken);

        if (!tokenResponse.isSuccess()) {
            // Mark connection as error
            connection.setStatus(ConnectionStatus.ERROR);
            connection.setLastError("Token refresh failed: " + tokenResponse.getErrorDescription());
            repository.save(connection);

            throw new RuntimeException("Token refresh failed: " + tokenResponse.getErrorDescription());
        }

        // Update connection with new tokens
        connection.setAccessTokenEncrypted(encryptionService.encrypt(tokenResponse.getAccessToken()));
        if (tokenResponse.getRefreshToken() != null) {
            connection.setRefreshTokenEncrypted(encryptionService.encrypt(tokenResponse.getRefreshToken()));
        }
        connection.setTokenExpiresAt(tokenResponse.getExpiresAt());
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

        // Try to revoke tokens
        if (handler != null) {
            String accessToken = encryptionService.decrypt(connection.getAccessTokenEncrypted());
            String refreshToken = connection.getRefreshTokenEncrypted() != null ?
                    encryptionService.decrypt(connection.getRefreshTokenEncrypted()) : null;

            handler.revokeToken(accessToken, refreshToken);
        }

        // Delete connection from database
        repository.delete(connection);

        log.info("OAuth connection disconnected for provider: {}", providerId);
        return true;
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
     * Clean up expired states (older than 10 minutes).
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void cleanupExpiredStates() {
        // States are automatically cleaned up when used
        // This could be enhanced with timestamp tracking if needed
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
