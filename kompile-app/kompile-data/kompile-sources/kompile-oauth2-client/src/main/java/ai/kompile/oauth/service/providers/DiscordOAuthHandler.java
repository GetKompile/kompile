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
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth handler for Discord.
 */
@Component
public class DiscordOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT = "https://discord.com/oauth2/authorize";
    private static final String TOKEN_ENDPOINT = "https://discord.com/api/oauth2/token";
    private static final String REVOKE_ENDPOINT = "https://discord.com/api/oauth2/token/revoke";
    private static final String USER_INFO_ENDPOINT = "https://discord.com/api/users/@me";
    private static final String DEFAULT_SCOPES = "identify guilds guilds.members.read messages.read";

    private OAuthSettingsService settingsService;

    @Value("${kompile.oauth.discord.client-id:}")
    private String defaultClientId;

    @Value("${kompile.oauth.discord.client-secret:}")
    private String defaultClientSecret;

    @Value("${kompile.oauth.discord.scopes:" + DEFAULT_SCOPES + "}")
    private String defaultScopes;

    public DiscordOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
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
        return "discord";
    }

    @Override
    public String getDisplayName() {
        return "Discord";
    }

    @Override
    public String getDescription() {
        return "Connect to Discord servers for message and file ingestion";
    }

    @Override
    public String getIcon() {
        return "forum";
    }

    @Override
    public String getColor() {
        return "#5865F2";
    }

    @Override
    public List<String> getRequiredScopes() {
        return List.of(getEffectiveScopes().split("\\s+"));
    }

    @Override
    public List<String> getRelatedSources() {
        return List.of("discord", "discord-history");
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
    public boolean isConfigured() {
        String clientId = getEffectiveClientId();
        return clientId != null && !clientId.isEmpty();
    }

    @Override
    public String getNotConfiguredMessage() {
        return "Discord OAuth requires a Discord Application. Set kompile.oauth.discord.client-id and kompile.oauth.discord.client-secret in your configuration.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", getEffectiveClientId());
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", getEffectiveScopes());
        params.put("state", state);

        return AUTHORIZATION_ENDPOINT + "?" + buildQueryString(params);
    }

    @Override
    public OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "authorization_code");
            body.add("code", code);
            body.add("redirect_uri", redirectUri);
            body.add("client_id", getEffectiveClientId());
            body.add("client_secret", getEffectiveClientSecret());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    TOKEN_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            return parseTokenResponse(response.getBody());
        } catch (Exception e) {
            log.error("Discord token exchange failed: {}", e.getMessage(), e);
            return OAuthTokenResponse.builder()
                    .error("token_exchange_failed")
                    .errorDescription(e.getMessage())
                    .build();
        }
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", refreshToken);
            body.add("client_id", getEffectiveClientId());
            body.add("client_secret", getEffectiveClientSecret());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    TOKEN_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            OAuthTokenResponse tokenResponse = parseTokenResponse(response.getBody());

            // Keep the original refresh token if a new one was not provided
            if (tokenResponse.getRefreshToken() == null) {
                tokenResponse.setRefreshToken(refreshToken);
            }

            return tokenResponse;
        } catch (Exception e) {
            log.error("Discord token refresh failed: {}", e.getMessage(), e);
            return OAuthTokenResponse.builder()
                    .error("token_refresh_failed")
                    .errorDescription(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean revokeToken(String accessToken, String refreshToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("token", accessToken);
            body.add("client_id", getEffectiveClientId());
            body.add("client_secret", getEffectiveClientSecret());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    REVOKE_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            boolean success = response.getStatusCode().is2xxSuccessful();

            if (success) {
                log.info("Successfully revoked Discord OAuth token");
            } else {
                log.warn("Failed to revoke Discord token, status: {}", response.getStatusCode());
            }

            return success;
        } catch (Exception e) {
            log.error("Failed to revoke Discord OAuth token: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    USER_INFO_ENDPOINT,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());

            String userId = json.has("id") ? json.get("id").asText() : null;
            String username = json.has("username") ? json.get("username").asText() : null;
            String globalName = json.has("global_name") && !json.get("global_name").isNull()
                    ? json.get("global_name").asText() : username;
            String email = json.has("email") && !json.get("email").isNull()
                    ? json.get("email").asText() : null;
            Boolean emailVerified = json.has("verified") ? json.get("verified").asBoolean() : null;

            String avatar = null;
            if (userId != null && json.has("avatar") && !json.get("avatar").isNull()) {
                String avatarHash = json.get("avatar").asText();
                avatar = "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + ".png";
            }

            return OAuthUserInfo.builder()
                    .userId(userId)
                    .name(globalName)
                    .email(email)
                    .emailVerified(emailVerified)
                    .picture(avatar)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get Discord user info: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean validateToken(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    USER_INFO_ENDPOINT,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Discord token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
