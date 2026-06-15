/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.gateway;

import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 authorization flows for KClaw channel integrations.
 *
 * For Slack: delegates to the main OAuthConnectionService (shared token storage,
 * encrypted persistence, survives restarts).
 *
 * For Discord: handles its own flow since Discord is KClaw-only and not in the
 * main OAuth module. Discord tokens are stored encrypted in the main module's
 * OAuthConnection table via the same OAuthConnectionService.
 */
@Slf4j
@RestController("kclawOAuthController")
@RequestMapping("/api/kclaw/oauth")
@ConditionalOnBean(ChannelManager.class)
public class OAuthController {

    private final ChannelManager channelManager;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired(required = false)
    private OAuthConnectionService oauthConnectionService;

    // Discord-specific pending states (Discord is KClaw-only)
    private final Map<String, DiscordOAuthState> pendingDiscordStates = new ConcurrentHashMap<>();

    public OAuthController(ChannelManager channelManager, ObjectMapper objectMapper) {
        this.channelManager = channelManager;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Status — delegates to main module for Slack, handles Discord locally
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/{provider}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String provider) {
        if ("slack".equals(provider) && oauthConnectionService != null) {
            boolean connected = oauthConnectionService.isConnected("slack");
            return ResponseEntity.ok(Map.of("connected", connected));
        }

        // Discord: check main module too
        if ("discord".equals(provider) && oauthConnectionService != null) {
            boolean connected = oauthConnectionService.isConnected("discord");
            return ResponseEntity.ok(Map.of("connected", connected));
        }

        return ResponseEntity.ok(Map.of("connected", false));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Authorize — for Slack, redirect to main module; for Discord, handle here
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Map<String, String>> authorize(
            @PathVariable String provider,
            HttpServletRequest request) {

        if ("slack".equals(provider) && oauthConnectionService != null) {
            // Delegate to the main OAuth module
            try {
                String redirectUri = buildRedirectUri(request, "slack");
                var response = oauthConnectionService.initiateAuthorization("slack", redirectUri);
                return ResponseEntity.ok(Map.of(
                        "authorizationUrl", response.getAuthorizationUrl(),
                        "state", response.getState()
                ));
            } catch (IllegalStateException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Slack OAuth not configured. Configure credentials in the main Connections settings."
                ));
            }
        }

        if ("discord".equals(provider)) {
            return authorizeDiscord(request);
        }

        return ResponseEntity.badRequest().body(Map.of(
                "error", "Unsupported OAuth provider: " + provider
        ));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Callback — for Slack, delegate to main module; for Discord, handle here
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/{provider}/callback")
    public ResponseEntity<String> callback(
            @PathVariable String provider,
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest request) {

        if ("slack".equals(provider) && oauthConnectionService != null) {
            try {
                String redirectUri = buildRedirectUri(request, "slack");
                oauthConnectionService.completeAuthorization("slack", code, state, redirectUri);
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(buildCallbackHtml("slack", true, null));
            } catch (Exception e) {
                log.error("Slack OAuth callback failed", e);
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(buildCallbackHtml("slack", false, e.getMessage()));
            }
        }

        if ("discord".equals(provider)) {
            return callbackDiscord(code, state, request);
        }

        return ResponseEntity.badRequest().body("Unsupported provider: " + provider);
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnect(@PathVariable String provider) {
        if (oauthConnectionService != null) {
            oauthConnectionService.disconnect(provider);
        }
        log.info("Disconnected OAuth for provider: {}", provider);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get the access token for a provider (for use by channel adapters).
     */
    public String getAccessToken(String provider) {
        if (oauthConnectionService != null) {
            return oauthConnectionService.getValidAccessToken(provider);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Discord-specific OAuth (KClaw-only, not in main module)
    // ═══════════════════════════════════════════════════════════════════

    private ResponseEntity<Map<String, String>> authorizeDiscord(HttpServletRequest request) {
        // Discord needs client config posted first since it's not in the main settings
        // For now, read from pending config
        DiscordOAuthState config = pendingDiscordStates.get("discord:config");
        if (config == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Discord OAuth client not configured. POST client ID and secret to /api/kclaw/oauth/config/discord first."
            ));
        }

        String state = UUID.randomUUID().toString();
        pendingDiscordStates.put(state, config);

        String redirectUri = buildRedirectUri(request, "discord");
        String authUrl = "https://discord.com/api/oauth2/authorize"
                + "?client_id=" + enc(config.clientId)
                + "&permissions=274877991936"
                + "&scope=" + enc("bot applications.commands")
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state);

        return ResponseEntity.ok(Map.of(
                "authorizationUrl", authUrl,
                "state", state
        ));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> callbackDiscord(String code, String state, HttpServletRequest request) {
        DiscordOAuthState oauthState = pendingDiscordStates.remove(state);
        if (oauthState == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Invalid or expired OAuth state. Please try again.");
        }

        String redirectUri = buildRedirectUri(request, "discord");
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(redirectUri);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                            .encodeToString((oauthState.clientId + ":" + oauthState.clientSecret).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            if (result.containsKey("error")) {
                throw new RuntimeException("Discord OAuth error: " + result.get("error"));
            }

            log.info("Discord OAuth completed (bot token obtained)");

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildCallbackHtml("discord", true, null));
        } catch (Exception e) {
            log.error("Discord OAuth token exchange failed", e);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildCallbackHtml("discord", false, e.getMessage()));
        }
    }

    // Discord config endpoint (still needed since Discord isn't in main settings)
    @PostMapping("/config/{provider}")
    public ResponseEntity<Void> setOAuthClientConfig(
            @PathVariable String provider,
            @RequestBody Map<String, String> config) {
        if (!"discord".equals(provider)) {
            return ResponseEntity.badRequest().build();
        }
        String clientId = config.get("clientId");
        String clientSecret = config.get("clientSecret");
        if (clientId == null || clientSecret == null) {
            return ResponseEntity.badRequest().build();
        }
        pendingDiscordStates.put("discord:config", new DiscordOAuthState(clientId, clientSecret));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/config/{provider}")
    public ResponseEntity<Map<String, Object>> getOAuthConfig(@PathVariable String provider) {
        if ("slack".equals(provider) && oauthConnectionService != null) {
            boolean connected = oauthConnectionService.isConnected("slack");
            return ResponseEntity.ok(Map.of("provider", provider, "connected", connected));
        }
        return ResponseEntity.ok(Map.of("provider", provider, "connected", false));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    private String buildRedirectUri(HttpServletRequest request, String provider) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String portStr = (port == 80 || port == 443) ? "" : ":" + port;
        return scheme + "://" + host + portStr + "/api/kclaw/oauth/" + provider + "/callback";
    }

    private String buildCallbackHtml(String provider, boolean success, String error) {
        String message = success ? "Connected to " + provider + " successfully." :
                "Connection failed: " + (error != null ? error : "Unknown error");
        return """
            <!DOCTYPE html>
            <html><head><title>OAuth Complete</title></head>
            <body>
            <p>%s This window will close.</p>
            <script>
              (function() {
                var result = { type: 'oauth-callback', provider: '%s', success: %s };
                if (window.opener) {
                  window.opener.postMessage(result, window.location.origin);
                }
                setTimeout(function() { window.close(); }, 1000);
              })();
            </script>
            </body></html>
            """.formatted(message, provider, success);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record DiscordOAuthState(String clientId, String clientSecret) {}
}
