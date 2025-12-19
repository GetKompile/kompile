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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth handler for Notion.
 */
@Component
public class NotionOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT = "https://api.notion.com/v1/oauth/authorize";
    private static final String TOKEN_ENDPOINT = "https://api.notion.com/v1/oauth/token";
    private static final String USERS_ME_ENDPOINT = "https://api.notion.com/v1/users/me";

    @Value("${kompile.oauth.notion.client-id:}")
    private String clientId;

    @Value("${kompile.oauth.notion.client-secret:}")
    private String clientSecret;

    public NotionOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
        super(restTemplate, objectMapper);
    }

    @Override
    public String getProviderId() {
        return "notion";
    }

    @Override
    public String getDisplayName() {
        return "Notion";
    }

    @Override
    public String getDescription() {
        return "Connect to Notion workspaces, pages, and databases";
    }

    @Override
    public String getIcon() {
        return "auto_stories";
    }

    @Override
    public String getColor() {
        return "#000000";
    }

    @Override
    public List<String> getRequiredScopes() {
        // Notion doesn't use scopes in the traditional sense
        return List.of();
    }

    @Override
    public List<String> getRelatedSources() {
        return List.of("notion");
    }

    @Override
    protected String getClientId() {
        return clientId;
    }

    @Override
    protected String getClientSecret() {
        return clientSecret;
    }

    @Override
    protected String getTokenEndpoint() {
        return TOKEN_ENDPOINT;
    }

    @Override
    public String getNotConfiguredMessage() {
        return "Notion OAuth requires an integration. Set kompile.oauth.notion.client-id and kompile.oauth.notion.client-secret in your configuration.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("state", state);
        params.put("owner", "user");

        return AUTHORIZATION_ENDPOINT + "?" + buildQueryString(params);
    }

    @Override
    public OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        try {
            // Notion requires Basic Auth with client credentials
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String auth = clientId + ":" + clientSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);

            Map<String, String> body = new LinkedHashMap<>();
            body.put("grant_type", "authorization_code");
            body.put("code", code);
            body.put("redirect_uri", redirectUri);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    TOKEN_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            return parseNotionTokenResponse(response.getBody());
        } catch (Exception e) {
            log.error("Notion token exchange failed: {}", e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("token_exchange_failed")
                    .errorDescription(e.getMessage())
                    .build();
        }
    }

    private OAuthTokenResponse parseNotionTokenResponse(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);

            if (json.has("error")) {
                return OAuthTokenResponse.builder()
                        .error(json.get("error").asText())
                        .errorDescription(json.has("error_description") ?
                                json.get("error_description").asText() : null)
                        .build();
            }

            OAuthTokenResponse response = OAuthTokenResponse.builder()
                    .accessToken(json.has("access_token") ? json.get("access_token").asText() : null)
                    .tokenType(json.has("token_type") ? json.get("token_type").asText() : "Bearer")
                    .build();

            // Notion tokens don't expire, so no refresh token or expiry
            // Store workspace info as provider data
            if (json.has("workspace_id") || json.has("workspace_name")) {
                Map<String, String> providerData = new LinkedHashMap<>();
                if (json.has("workspace_id")) {
                    providerData.put("workspace_id", json.get("workspace_id").asText());
                }
                if (json.has("workspace_name")) {
                    providerData.put("workspace_name", json.get("workspace_name").asText());
                }
                if (json.has("workspace_icon")) {
                    providerData.put("workspace_icon", json.get("workspace_icon").asText());
                }
                if (json.has("bot_id")) {
                    providerData.put("bot_id", json.get("bot_id").asText());
                }
                response.setProviderData(objectMapper.writeValueAsString(providerData));
            }

            return response;
        } catch (Exception e) {
            log.error("Failed to parse Notion token response: {}", e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("parse_error")
                    .errorDescription("Failed to parse token response: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        // Notion tokens don't expire and don't support refresh
        return OAuthTokenResponse.builder()
                .error("not_supported")
                .errorDescription("Notion tokens do not expire and cannot be refreshed")
                .build();
    }

    @Override
    public boolean revokeToken(String accessToken, String refreshToken) {
        // Notion doesn't have a token revocation endpoint
        // Users should revoke access from their Notion settings
        log.info("Notion tokens cannot be programmatically revoked. User should revoke access from Notion settings.");
        return true;
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.set("Notion-Version", "2022-06-28");

            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    USERS_ME_ENDPOINT,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());

            String name = null;
            String email = null;
            String picture = null;

            if (json.has("bot") && json.get("bot").has("owner")) {
                JsonNode owner = json.get("bot").get("owner");
                if (owner.has("user")) {
                    JsonNode user = owner.get("user");
                    name = user.has("name") ? user.get("name").asText() : null;
                    if (user.has("person") && user.get("person").has("email")) {
                        email = user.get("person").get("email").asText();
                    }
                    if (user.has("avatar_url") && !user.get("avatar_url").isNull()) {
                        picture = user.get("avatar_url").asText();
                    }
                }
            }

            return OAuthUserInfo.builder()
                    .email(email)
                    .name(name)
                    .picture(picture)
                    .userId(json.has("id") ? json.get("id").asText() : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get Notion user info: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean validateToken(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.set("Notion-Version", "2022-06-28");

            HttpEntity<String> request = new HttpEntity<>(headers);

            restTemplate.exchange(USERS_ME_ENDPOINT, HttpMethod.GET, request, String.class);
            return true;
        } catch (Exception e) {
            log.debug("Notion token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
