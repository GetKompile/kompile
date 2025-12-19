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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth handler for Atlassian (Confluence, Jira Cloud).
 */
@Component
public class AtlassianOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT = "https://auth.atlassian.com/authorize";
    private static final String TOKEN_ENDPOINT = "https://auth.atlassian.com/oauth/token";
    private static final String USERINFO_ENDPOINT = "https://api.atlassian.com/me";
    private static final String ACCESSIBLE_RESOURCES_ENDPOINT = "https://api.atlassian.com/oauth/token/accessible-resources";

    @Value("${kompile.oauth.atlassian.client-id:}")
    private String clientId;

    @Value("${kompile.oauth.atlassian.client-secret:}")
    private String clientSecret;

    @Value("${kompile.oauth.atlassian.scopes:read:confluence-content.all read:confluence-space.summary read:jira-work read:jira-user offline_access}")
    private String scopes;

    public AtlassianOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
        super(restTemplate, objectMapper);
    }

    @Override
    public String getProviderId() {
        return "atlassian";
    }

    @Override
    public String getDisplayName() {
        return "Atlassian";
    }

    @Override
    public String getDescription() {
        return "Connect to Confluence and Jira Cloud";
    }

    @Override
    public String getIcon() {
        return "link";
    }

    @Override
    public String getColor() {
        return "#0052CC";
    }

    @Override
    public List<String> getRequiredScopes() {
        return List.of(scopes.split("\\s+"));
    }

    @Override
    public List<String> getRelatedSources() {
        return List.of("confluence", "jira");
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
        return "Atlassian OAuth requires an OAuth 2.0 (3LO) app. Set kompile.oauth.atlassian.client-id and kompile.oauth.atlassian.client-secret in your configuration.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("audience", "api.atlassian.com");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", scopes);
        params.put("state", state);
        params.put("prompt", "consent");

        return AUTHORIZATION_ENDPOINT + "?" + buildQueryString(params);
    }

    @Override
    public OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        OAuthTokenResponse response = performTokenExchange(code, redirectUri);

        // Get accessible resources (cloud IDs) after successful token exchange
        if (response.isSuccess()) {
            try {
                String resourcesJson = makeAuthenticatedGetRequest(ACCESSIBLE_RESOURCES_ENDPOINT, response.getAccessToken());
                response.setProviderData(resourcesJson);
            } catch (Exception e) {
                log.warn("Failed to get Atlassian accessible resources: {}", e.getMessage());
            }
        }

        return response;
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        return performTokenRefresh(refreshToken);
    }

    @Override
    public boolean revokeToken(String accessToken, String refreshToken) {
        // Atlassian doesn't have a token revocation endpoint
        // Users should revoke access from their Atlassian account settings
        log.info("Atlassian tokens cannot be programmatically revoked. User should revoke access from Atlassian account settings.");
        return true;
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
                    .userId(json.has("account_id") ? json.get("account_id").asText() : null)
                    .emailVerified(json.has("email_verified") ? json.get("email_verified").asBoolean() : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get Atlassian user info: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean validateToken(String accessToken) {
        try {
            makeAuthenticatedGetRequest(USERINFO_ENDPOINT, accessToken);
            return true;
        } catch (Exception e) {
            log.debug("Atlassian token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the list of accessible Atlassian cloud resources.
     */
    public String getAccessibleResources(String accessToken) {
        return makeAuthenticatedGetRequest(ACCESSIBLE_RESOURCES_ENDPOINT, accessToken);
    }
}
