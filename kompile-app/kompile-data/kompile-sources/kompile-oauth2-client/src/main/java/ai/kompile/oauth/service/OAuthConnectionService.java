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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    public static final class OAuthRefreshException extends RuntimeException {
        OAuthRefreshException(String message) {
            super(message);
        }
    }

    private OAuthConnectionRepository repository;
    private PendingOAuthStateRepository stateRepository;
    private TokenEncryptionService encryptionService;
    private final Map<String, OAuthProviderHandler> handlers = new ConcurrentHashMap<>();
    private TransactionTemplate refreshTransaction;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${kompile.oauth.redirect-base-url:}")
    private String redirectBaseUrl;

    @Value("${spring.application.name:}")
    private String applicationName;

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

    @Autowired(required = false)
    void configureRefreshTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.refreshTransaction = template;
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

    /** Fixed provider scope profile used by server-owned integrations such as channels. */
    public List<String> getRequiredScopes(String providerId, String purpose) {
        OAuthProviderHandler handler = handlers.get(providerId);
        return handler == null
                ? List.of()
                : handler.getRequiredScopes(normalizePurpose(purpose));
    }

    /** Granted scopes, normalized across whitespace- and comma-delimited providers. */
    @Transactional(readOnly = true)
    public List<String> getGrantedScopes(String providerId) {
        return repository.findById(providerId)
                .filter(connection -> connection.getStatus() == ConnectionStatus.CONNECTED)
                .map(OAuthConnection::getScope)
                .map(OAuthConnectionService::parseScopes)
                .orElseGet(List::of);
    }

    /**
     * Current token for another server-side runtime. This deliberately does not
     * refresh, validate remotely, or update last-used state; crawl-manager remains
     * the sole owner of OAuth lifecycle writes.
     */
    @Transactional(readOnly = true)
    public String getCurrentAccessToken(String providerId) {
        return repository.findById(providerId)
                .filter(connection -> connection.getStatus() == ConnectionStatus.CONNECTED)
                .filter(connection -> !connection.isTokenExpired())
                .map(connection -> encryptionService.decrypt(connection.getAccessTokenEncrypted()))
                .filter(token -> token != null && !token.isBlank())
                .orElse(null);
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

    /** Whether a connected provider can serve a request now or refresh before serving it. */
    @Transactional(readOnly = true)
    public boolean isConnectionUsable(String providerId) {
        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null || !handler.isConfigured()) return false;
        return repository.findById(providerId)
                .filter(connection -> connection.getStatus() == ConnectionStatus.CONNECTED)
                .map(connection -> !connection.isTokenExpired() || connection.canRefresh())
                .orElse(false);
    }

    /**
     * Initiate OAuth authorization flow.
     * Returns the authorization URL and state for the provider.
     */
    public AuthorizationUrlResponse initiateAuthorization(String providerId, String customRedirectUri) {
        return initiateAuthorization(providerId, customRedirectUri, null);
    }

    /** Initiate authorization with a fixed provider-owned purpose profile. */
    public AuthorizationUrlResponse initiateAuthorization(
            String providerId, String customRedirectUri, String purpose) {
        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + providerId);
        }

        if (!handler.isConfigured()) {
            throw new IllegalStateException("OAuth provider not configured: " + providerId);
        }

        // Determine and bind the redirect URI before persisting the CSRF state.
        String redirectUri = customRedirectUri;
        if (redirectUri == null || redirectUri.isBlank()) {
            redirectUri = getConfiguredRedirectUri(providerId);
            if (redirectUri == null) redirectUri = "/api/oauth/" + providerId + "/callback";
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

        String normalizedPurpose = normalizePurpose(purpose);
        String authUrl = normalizedPurpose == null
                ? handler.buildAuthorizationUrl(redirectUri, state)
                : handler.buildAuthorizationUrl(redirectUri, state, normalizedPurpose);

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
        PendingOAuthState pendingState = claimAuthorizationState(providerId, state, redirectUri);

        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + providerId);
        }

        // Exchange code for tokens
        OAuthTokenResponse tokenResponse = handler.exchangeCodeForTokens(code, pendingState.getRedirectUri());

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

        if (tokenResponse.getProviderData() != null && !tokenResponse.getProviderData().isBlank()) {
            connection.setProviderData(tokenResponse.getProviderData());
        }

        repository.save(connection);

        log.info("OAuth connection established for provider: {} (user: {})",
                providerId, connection.getUserEmail());

        return OAuthConnectionDto.fromEntity(connection, handler.getDisplayName(), handler.getIcon());
    }

    /** Complete a callback using the exact redirect URI bound into the one-time OAuth state. */
    @Transactional
    public OAuthConnectionDto completeAuthorization(String providerId, String code, String state) {
        return completeAuthorization(providerId, code, state, null);
    }

    /** Consume state returned with a provider-denial callback so it cannot be replayed later. */
    public void consumeDeniedAuthorization(String providerId, String state) {
        claimAuthorizationState(providerId, state, null);
    }

    private PendingOAuthState claimAuthorizationState(
            String providerId, String state, String expectedRedirectUri) {
        PendingOAuthState pendingState = stateRepository.findById(state)
                .orElseThrow(() -> new SecurityException("Invalid OAuth state parameter"));
        if (pendingState.isExpired()) {
            stateRepository.deleteStateClaim(state);
            throw new SecurityException("Invalid or expired OAuth state parameter");
        }
        if (!pendingState.getProviderId().equals(providerId)
                || expectedRedirectUri != null
                && !Objects.equals(pendingState.getRedirectUri(), expectedRedirectUri)) {
            stateRepository.deleteStateClaim(state);
            throw new SecurityException("Invalid OAuth state parameter");
        }
        if (stateRepository.consume(
                state, pendingState.getProviderId(), pendingState.getRedirectUri()) != 1) {
            throw new SecurityException("OAuth state has already been consumed");
        }
        return pendingState;
    }

    /** Public callback URI configured for reverse-proxy/hosted deployments, if any. */
    public String getConfiguredRedirectUri(String providerId) {
        if (redirectBaseUrl == null || redirectBaseUrl.isBlank()) return null;
        return redirectBaseUrl.trim().replaceAll("/+$", "")
                + "/api/oauth/" + providerId + "/callback";
    }

    /**
     * Get a valid access token for a provider, refreshing if necessary.
     */
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
                        refreshConnection(providerId, false);
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

        repository.updateLastUsedAt(providerId, Instant.now());
        return resolved.getAccess();
    }

    /** Provider-specific, non-secret connection metadata such as Atlassian accessible resources. */
    @Transactional(readOnly = true)
    public String getProviderData(String providerId) {
        return repository.findById(providerId)
                .filter(connection -> connection.getStatus() == ConnectionStatus.CONNECTED)
                .map(OAuthConnection::getProviderData)
                .orElse(null);
    }

    /**
     * Refresh the access token for a provider.
     */
    public OAuthConnectionStatus refreshConnection(String providerId) {
        return refreshConnection(providerId, true);
    }

    private OAuthConnectionStatus refreshConnection(String providerId, boolean force) {
        RefreshOutcome outcome = refreshTransaction != null
                ? refreshTransaction.execute(status -> refreshLocked(providerId, force))
                : refreshLocked(providerId, force);
        if (outcome == null) throw new IllegalStateException("OAuth refresh produced no result");
        if (outcome.error() != null) throw new OAuthRefreshException(outcome.error());
        return outcome.status();
    }

    private RefreshOutcome refreshLocked(String providerId, boolean force) {
        Optional<OAuthConnection> connectionOpt = repository.findByProviderIdForUpdate(providerId);
        if (connectionOpt.isEmpty()) {
            throw new IllegalArgumentException("No connection found for provider: " + providerId);
        }

        OAuthConnection connection = connectionOpt.get();
        refreshLockedEntity(connection);
        if (!force && !connection.isTokenExpired()) {
            return RefreshOutcome.success(connectionStatus(connection));
        }
        if (!connection.canRefresh()) {
            throw new IllegalStateException("Connection cannot be refreshed (no refresh token)");
        }

        OAuthProviderHandler handler = handlers.get(providerId);
        if (handler == null || !handler.isConfigured()) {
            throw new IllegalStateException("OAuth provider is not configured: " + providerId);
        }

        ManagedCredential current = decryptCredential(connection);
        OAuthTokenResponse tokenResponse = handler.refreshAccessToken(current.getRefresh());

        if (!tokenResponse.isSuccess()) {
            String message = "Token refresh failed: " + tokenResponse.getErrorDescription();
            connection.setStatus(ConnectionStatus.ERROR);
            connection.setLastError(message);
            repository.save(connection);
            return RefreshOutcome.failure(message);
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
        if (tokenResponse.getProviderData() != null && !tokenResponse.getProviderData().isBlank()) {
            connection.setProviderData(tokenResponse.getProviderData());
        }

        repository.save(connection);

        log.info("OAuth token refreshed for provider: {}", providerId);
        return RefreshOutcome.success(connectionStatus(connection));
    }

    private OAuthConnectionStatus connectionStatus(OAuthConnection connection) {
        return OAuthConnectionStatus.builder()
                .providerId(connection.getProviderId())
                .status(connection.getStatus())
                .connected(connection.getStatus() == ConnectionStatus.CONNECTED)
                .tokenValid(!connection.isTokenExpired())
                .needsRefresh(connection.isTokenExpired())
                .expiresAt(connection.getTokenExpiresAt())
                .userEmail(connection.getUserEmail())
                .userName(connection.getUserName())
                .errorMessage(connection.getStatus() == ConnectionStatus.ERROR
                        ? connection.getLastError() : null)
                .build();
    }

    private record RefreshOutcome(OAuthConnectionStatus status, String error) {
        static RefreshOutcome success(OAuthConnectionStatus status) {
            return new RefreshOutcome(status, null);
        }

        static RefreshOutcome failure(String error) {
            return new RefreshOutcome(null, error);
        }
    }

    /**
     * Disconnect from a provider (revoke tokens).
     */
    @Transactional
    public boolean disconnect(String providerId) {
        Optional<OAuthConnection> connectionOpt = repository.findByProviderIdForUpdate(providerId);
        if (connectionOpt.isEmpty()) {
            return true; // Already disconnected
        }

        OAuthConnection connection = connectionOpt.get();
        refreshLockedEntity(connection);
        OAuthProviderHandler handler = handlers.get(providerId);

        if (handler == null) {
            return retainFailedRevocation(connection,
                    "OAuth provider is unavailable; token revocation was not attempted");
        }
        ManagedCredential credential = decryptCredential(connection);
        try {
            if (!OAuthCredentialLifecycle.revoke(credential, handler::revokeToken)) {
                return retainFailedRevocation(connection,
                        "Provider rejected OAuth token revocation");
            }
        } catch (Exception e) {
            return retainFailedRevocation(connection,
                    "OAuth token revocation failed: " + e.getMessage());
        }

        // Delete connection from database
        repository.delete(connection);

        log.info("OAuth connection disconnected for provider: {}", providerId);
        return true;
    }

    private boolean retainFailedRevocation(OAuthConnection connection, String message) {
        connection.setStatus(ConnectionStatus.ERROR);
        connection.setLastError(message);
        repository.save(connection);
        log.warn("Retaining OAuth connection {} after revocation failure: {}",
                connection.getProviderId(), message);
        return false;
    }

    private void refreshLockedEntity(OAuthConnection connection) {
        if (entityManager != null) {
            entityManager.refresh(connection, LockModeType.PESSIMISTIC_WRITE);
        }
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
        if (!ownsOAuthLifecycle()) return;
        // Find connections expiring in the next 10 minutes
        Instant expiryThreshold = Instant.now().plusSeconds(600);
        List<OAuthConnection> expiringConnections = repository.findConnectionsNeedingRefresh(expiryThreshold);

        for (OAuthConnection connection : expiringConnections) {
            try {
                log.info("Auto-refreshing expiring OAuth token for provider: {}", connection.getProviderId());
                refreshConnection(connection.getProviderId(), false);
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
        if (!ownsOAuthLifecycle()) return;
        int deleted = stateRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.debug("Deleted {} expired OAuth authorization states", deleted);
        }
    }

    private boolean ownsOAuthLifecycle() {
        return "kompile-app-crawl-manager".equals(applicationName);
    }

    private static String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank() || "source".equalsIgnoreCase(purpose)) {
            return null;
        }
        if ("channel".equalsIgnoreCase(purpose)) {
            return "channel";
        }
        throw new IllegalArgumentException("Unsupported OAuth authorization purpose: " + purpose);
    }

    private static List<String> parseScopes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.trim().split("[,\\s]+"))
                .filter(scope -> !scope.isBlank())
                .distinct()
                .toList();
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
