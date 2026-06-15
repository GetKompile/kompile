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

package ai.kompile.oauth.service.providers;

import ai.kompile.oauth.dto.OAuthTokenResponse;
import ai.kompile.oauth.dto.OAuthUserInfo;
import ai.kompile.oauth.service.OAuthSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth handler for Google (Google Drive, Gmail, etc.).
 */
@Component
public class GoogleOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";

    private static final String DEFAULT_SCOPES = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.readonly email profile";

    private OAuthSettingsService settingsService;

    @Value("${kompile.oauth.google.client-id:}")
    private String defaultClientId;

    @Value("${kompile.oauth.google.client-secret:}")
    private String defaultClientSecret;

    @Value("${kompile.oauth.google.scopes:" + DEFAULT_SCOPES + "}")
    private String defaultScopes;

    public GoogleOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
        super(restTemplate, objectMapper);
    }

    @Autowired(required = false)
    public void setSettingsService(OAuthSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private String getEffectiveClientId() {
        if (settingsService != null) {
            String configured = settingsService.getClientId(getProviderId());
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        return defaultClientId;
    }

    private String getEffectiveClientSecret() {
        if (settingsService != null) {
            String configured = settingsService.getClientSecret(getProviderId());
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        return defaultClientSecret;
    }

    private String getEffectiveScopes() {
        if (settingsService != null) {
            String configured = settingsService.getScopes(getProviderId());
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        return defaultScopes != null && !defaultScopes.isEmpty() ? defaultScopes : DEFAULT_SCOPES;
    }

    @Override
    public String getProviderId() {
        return "google";
    }

    @Override
    public String getDisplayName() {
        return "Google";
    }

    @Override
    public String getDescription() {
        return "Connect to Google Drive, Gmail, and other Google services";
    }

    @Override
    public String getIcon() {
        return "google";
    }

    @Override
    public String getColor() {
        return "#4285F4";
    }

    @Override
    public List<String> getRequiredScopes() {
        return List.of(getEffectiveScopes().split("\\s+"));
    }

    @Override
    public List<String> getRelatedSources() {
        return List.of("gdrive", "email");
    }

    @Override
    protected String getClientId() {
        return getEffectiveClientId();
    }

    @Override
    protected String getClientSecret() {
        return getEffectiveClientSecret();
    }

    @Override
    protected String getTokenEndpoint() {
        return TOKEN_ENDPOINT;
    }

    @Override
    public String getNotConfiguredMessage() {
        return "Google OAuth requires client credentials. Set kompile.oauth.google.client-id and kompile.oauth.google.client-secret in your configuration.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", getEffectiveClientId());
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", getEffectiveScopes());
        params.put("state", state);
        params.put("access_type", "offline");
        params.put("prompt", "consent"); // Force consent to get refresh token

        return AUTHORIZATION_ENDPOINT + "?" + buildQueryString(params);
    }

    @Override
    public OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        return performTokenExchange(code, redirectUri);
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        return performTokenRefresh(refreshToken);
    }

    @Override
    public boolean revokeToken(String accessToken, String refreshToken) {
        try {
            // Google prefers revoking the refresh token (if available) as it also revokes all associated access tokens
            String tokenToRevoke = refreshToken != null ? refreshToken : accessToken;

            String url = REVOKE_ENDPOINT + "?token=" + tokenToRevoke;
            restTemplate.postForEntity(url, null, String.class);

            log.info("Successfully revoked Google OAuth token");
            return true;
        } catch (Exception e) {
            log.error("Failed to revoke Google OAuth token: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) {
        try {
            String response = makeAuthenticatedGetRequest(USERINFO_ENDPOINT, accessToken);
            JsonNode json = objectMapper.readTree(response);

            return OAuthUserInfo.builder()
                    .email(json.has("email") ? json.get("email").asText() : null)
                    .name(json.has("name") ? json.get("name").asText() : null)
                    .picture(json.has("picture") ? json.get("picture").asText() : null)
                    .userId(json.has("id") ? json.get("id").asText() : null)
                    .emailVerified(json.has("verified_email") ? json.get("verified_email").asBoolean() : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get Google user info: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean validateToken(String accessToken) {
        try {
            // Use tokeninfo endpoint to validate
            String url = "https://oauth2.googleapis.com/tokeninfo?access_token=" + accessToken;
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (Exception e) {
            log.debug("Google token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
