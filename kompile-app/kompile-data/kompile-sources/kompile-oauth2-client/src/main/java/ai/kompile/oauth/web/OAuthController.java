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

package ai.kompile.oauth.web;

import ai.kompile.oauth.dto.*;
import ai.kompile.oauth.service.OAuthConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for OAuth connection management.
 */
@RestController("oauthConnectionController")
@ConditionalOnExpression("'${spring.application.name:}' == 'kompile-app-crawl-manager'")
@RequestMapping("/api/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthConnectionService connectionService;

    @Autowired
    public OAuthController(OAuthConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * Get all available OAuth providers.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<OAuthProviderInfo>> getProviders() {
        return ResponseEntity.ok(connectionService.getAvailableProviders());
    }

    /**
     * Get information about a specific provider.
     */
    @GetMapping("/providers/{providerId}")
    public ResponseEntity<OAuthProviderInfo> getProvider(@PathVariable String providerId) {
        return connectionService.getProviderInfo(providerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all OAuth connections.
     */
    @GetMapping("/connections")
    public ResponseEntity<List<OAuthConnectionDto>> getConnections() {
        return ResponseEntity.ok(connectionService.getAllConnections());
    }

    /**
     * Get connection status for a specific provider.
     */
    @GetMapping("/{providerId}/status")
    public ResponseEntity<OAuthConnectionStatus> getConnectionStatus(@PathVariable String providerId) {
        return ResponseEntity.ok(connectionService.getConnectionStatus(providerId));
    }

    /**
     * Initiate OAuth authorization flow.
     * Returns the authorization URL for the frontend to redirect to.
     */
    @GetMapping("/{providerId}/authorize")
    public ResponseEntity<AuthorizationUrlResponse> initiateAuthorization(
            @PathVariable String providerId,
            @RequestParam(required = false) String redirectUri,
            @RequestParam(required = false) String purpose,
            HttpServletRequest request) {
        try {
            // Build redirect URI from request if not provided
            if (redirectUri == null || redirectUri.isEmpty()) {
                redirectUri = connectionService.getConfiguredRedirectUri(providerId);
                if (redirectUri == null) {
                    redirectUri = getBaseUrl(request) + "/api/oauth/" + providerId + "/callback";
                }
            }

            AuthorizationUrlResponse response = connectionService.initiateAuthorization(
                    providerId, redirectUri, purpose);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid provider for authorization: {}", providerId);
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("Provider not configured: {}", providerId);
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                    .body(AuthorizationUrlResponse.builder()
                            .providerId(providerId)
                            .build());
        }
    }

    /** Java-level compatibility for callers that do not select a credential purpose. */
    public ResponseEntity<AuthorizationUrlResponse> initiateAuthorization(
            String providerId, String redirectUri, HttpServletRequest request) {
        return initiateAuthorization(providerId, redirectUri, null, request);
    }

    /**
     * Handle OAuth callback from provider.
     * Exchanges the code for tokens and redirects to frontend.
     */
    @GetMapping("/{providerId}/callback")
    public ResponseEntity<?> handleCallback(
            @PathVariable String providerId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            HttpServletRequest request) {

        if (error != null) {
            log.warn("OAuth callback error for {}: {} - {}", providerId, error, errorDescription);
            if (state != null && !state.isBlank()) {
                try {
                    connectionService.consumeDeniedAuthorization(providerId, state);
                } catch (SecurityException invalidState) {
                    log.warn("OAuth denial callback had invalid state for {}", providerId);
                    return oauthResultPage(providerId, false, "OAuth state validation failed");
                }
            }
            return oauthResultPage(providerId, false, "Authorization was denied");
        }

        if (code == null || state == null) {
            log.warn("OAuth callback missing code or state for provider: {}", providerId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required parameters"));
        }

        try {
            OAuthConnectionDto connection = connectionService.completeAuthorization(providerId, code, state);

            return oauthResultPage(providerId, true, null);
        } catch (SecurityException e) {
            log.error("OAuth state validation failed for {}: {}", providerId, e.getMessage());
            return oauthResultPage(providerId, false, "OAuth state validation failed");
        } catch (Exception e) {
            log.error("OAuth callback failed for {}: {}", providerId, e.getMessage());
            return oauthResultPage(providerId, false, "Token exchange failed");
        }
    }

    private static ResponseEntity<String> oauthResultPage(
            String providerId, boolean success, String error) {
        String provider = providerId == null ? "provider"
                : providerId.replaceAll("[^A-Za-z0-9_-]", "");
        String title = success ? "Connection complete" : "Connection failed";
        String message = success
                ? provider + " is now connected to Kompile."
                : (error == null ? "OAuth connection failed." : error + ".");
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
                + title + "</title></head><body><h1>" + title + "</h1><p>" + message
                + "</p><script>if(window.opener){window.opener.postMessage({type:'oauth-complete'},"
                + "window.location.origin);window.close();}</script></body></html>";
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /**
     * Force refresh of access token.
     */
    @PostMapping("/{providerId}/refresh")
    public ResponseEntity<OAuthConnectionStatus> refreshToken(@PathVariable String providerId) {
        try {
            OAuthConnectionStatus status = connectionService.refreshConnection(providerId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            log.warn("Refresh failed - no connection: {}", providerId);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Refresh failed - no refresh token: {}", providerId);
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(OAuthConnectionStatus.builder()
                            .providerId(providerId)
                            .connected(false)
                            .errorMessage(e.getMessage())
                            .build());
        } catch (Exception e) {
            log.error("Refresh failed for {}: {}", providerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(OAuthConnectionStatus.builder()
                            .providerId(providerId)
                            .connected(false)
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    /**
     * Disconnect from a provider (revoke tokens).
     */
    @DeleteMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> disconnect(@PathVariable String providerId) {
        try {
            boolean success = connectionService.disconnect(providerId);
            return ResponseEntity.ok(Map.of("success", success, "providerId", providerId));
        } catch (Exception e) {
            log.error("Disconnect failed for {}: {}", providerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Check connection health.
     */
    @GetMapping("/{providerId}/health")
    public ResponseEntity<Map<String, Object>> checkHealth(@PathVariable String providerId) {
        try {
            boolean healthy = connectionService.checkConnectionHealth(providerId);
            return ResponseEntity.ok(Map.of(
                    "healthy", healthy,
                    "providerId", providerId,
                    "message", healthy ? "Connection is healthy" : "Connection needs attention"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "healthy", false,
                    "providerId", providerId,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get access token for a provider (for internal use by other services).
     */
    @GetMapping("/{providerId}/token")
    public ResponseEntity<Map<String, Object>> getAccessToken(@PathVariable String providerId) {
        String token = connectionService.getValidAccessToken(providerId);
        if (token != null) {
            return ResponseEntity.ok(Map.of(
                    "hasToken", true,
                    "providerId", providerId
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "hasToken", false,
                    "providerId", providerId
            ));
        }
    }

    /**
     * Runtime OAuth credential handoff for authenticated source ingestion: the folder-local
     * crawl runtime has no in-process OAuth service, so the CLI bridges the locally connected
     * token into the crawl request. Served only from the admin-token-gated /api/oauth boundary;
     * the sibling /token endpoint keeps its boolean-only contract.
     */
    @GetMapping("/{providerId}/credential")
    public ResponseEntity<Map<String, Object>> getCredential(@PathVariable String providerId) {
        String token = connectionService.getValidAccessToken(providerId);
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                    .body(Map.of("hasToken", false, "providerId", providerId,
                            "error", "No connected OAuth account for provider: " + providerId));
        }
        return ResponseEntity.ok(Map.of(
                "hasToken", true,
                "providerId", providerId,
                "accessToken", token));
    }

    /**
     * Get base URL from request.
     */
    private String getBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();

        StringBuilder url = new StringBuilder();
        url.append(scheme).append("://").append(serverName);

        if (("http".equals(scheme) && serverPort != 80) ||
            ("https".equals(scheme) && serverPort != 443)) {
            url.append(":").append(serverPort);
        }

        return url.toString();
    }
}
