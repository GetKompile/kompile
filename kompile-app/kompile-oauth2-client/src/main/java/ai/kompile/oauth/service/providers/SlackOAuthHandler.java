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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth handler for Slack.
 */
@Component
public class SlackOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT = "https://slack.com/oauth/v2/authorize";
    private static final String TOKEN_ENDPOINT = "https://slack.com/api/oauth.v2.access";
    private static final String AUTH_TEST_ENDPOINT = "https://slack.com/api/auth.test";
    private static final String USERS_INFO_ENDPOINT = "https://slack.com/api/users.info";
    private static final String REVOKE_ENDPOINT = "https://slack.com/api/auth.revoke";

    @Value("${kompile.oauth.slack.client-id:}")
    private String clientId;

    @Value("${kompile.oauth.slack.client-secret:}")
    private String clientSecret;

    @Value("${kompile.oauth.slack.scopes:channels:history channels:read users:read}")
    private String scopes;

    public SlackOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
        super(restTemplate, objectMapper);
    }

    @Override
    public String getProviderId() {
        return "slack";
    }

    @Override
    public String getDisplayName() {
        return "Slack";
    }

    @Override
    public String getDescription() {
        return "Connect to Slack workspaces and channels";
    }

    @Override
    public String getIcon() {
        return "tag";
    }

    @Override
    public String getColor() {
        return "#4A154B";
    }

    @Override
    public List<String> getRequiredScopes() {
        return List.of(scopes.split("\\s+"));
    }

    @Override
    public List<String> getRelatedSources() {
        return List.of("slack", "slack_history");
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
        return "Slack OAuth requires a Slack App. Set kompile.oauth.slack.client-id and kompile.oauth.slack.client-secret in your configuration.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", scopes);
        params.put("state", state);

        return AUTHORIZATION_ENDPOINT + "?" + buildQueryString(params);
    }

    @Override
    public OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("code", code);
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("redirect_uri", redirectUri);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    TOKEN_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            return parseSlackTokenResponse(response.getBody());
        } catch (Exception e) {
            log.error("Slack token exchange failed: {}", e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("token_exchange_failed")
                    .errorDescription(e.getMessage())
                    .build();
        }
    }

    private OAuthTokenResponse parseSlackTokenResponse(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);

            if (!json.has("ok") || !json.get("ok").asBoolean()) {
                String error = json.has("error") ? json.get("error").asText() : "unknown_error";
                return OAuthTokenResponse.builder()
                        .error(error)
                        .errorDescription("Slack API returned error: " + error)
                        .build();
            }

            String accessToken = json.has("access_token") ? json.get("access_token").asText() : null;

            OAuthTokenResponse response = OAuthTokenResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .scope(json.has("scope") ? json.get("scope").asText() : null)
                    .build();

            // Store team/workspace info as provider data
            Map<String, String> providerData = new LinkedHashMap<>();
            if (json.has("team")) {
                JsonNode team = json.get("team");
                if (team.has("id")) providerData.put("team_id", team.get("id").asText());
                if (team.has("name")) providerData.put("team_name", team.get("name").asText());
            }
            if (json.has("authed_user")) {
                JsonNode user = json.get("authed_user");
                if (user.has("id")) providerData.put("user_id", user.get("id").asText());
            }
            if (json.has("bot_user_id")) {
                providerData.put("bot_user_id", json.get("bot_user_id").asText());
            }

            if (!providerData.isEmpty()) {
                response.setProviderData(objectMapper.writeValueAsString(providerData));
            }

            // Slack tokens don't expire by default
            return response;
        } catch (Exception e) {
            log.error("Failed to parse Slack token response: {}", e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("parse_error")
                    .errorDescription("Failed to parse token response: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        // Slack bot tokens don't expire by default
        // Token rotation can be enabled but requires different handling
        return OAuthTokenResponse.builder()
                .error("not_supported")
                .errorDescription("Slack tokens do not expire by default")
                .build();
    }

    @Override
    public boolean revokeToken(String accessToken, String refreshToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    REVOKE_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            boolean success = json.has("ok") && json.get("ok").asBoolean();

            if (success) {
                log.info("Successfully revoked Slack OAuth token");
            } else {
                log.warn("Failed to revoke Slack token: {}",
                        json.has("error") ? json.get("error").asText() : "unknown");
            }

            return success;
        } catch (Exception e) {
            log.error("Failed to revoke Slack OAuth token: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) {
        try {
            // First, get the authenticated user info
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> authResponse = restTemplate.exchange(
                    AUTH_TEST_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            JsonNode authJson = objectMapper.readTree(authResponse.getBody());

            if (!authJson.has("ok") || !authJson.get("ok").asBoolean()) {
                return null;
            }

            String userId = authJson.has("user_id") ? authJson.get("user_id").asText() : null;

            // Get detailed user info
            if (userId != null) {
                String userInfoUrl = USERS_INFO_ENDPOINT + "?user=" + userId;
                ResponseEntity<String> userResponse = restTemplate.exchange(
                        userInfoUrl,
                        HttpMethod.GET,
                        request,
                        String.class
                );

                JsonNode userJson = objectMapper.readTree(userResponse.getBody());

                if (userJson.has("ok") && userJson.get("ok").asBoolean() && userJson.has("user")) {
                    JsonNode user = userJson.get("user");
                    JsonNode profile = user.has("profile") ? user.get("profile") : null;

                    return OAuthUserInfo.builder()
                            .email(profile != null && profile.has("email") ? profile.get("email").asText() : null)
                            .name(profile != null && profile.has("real_name") ? profile.get("real_name").asText() :
                                  (user.has("real_name") ? user.get("real_name").asText() : null))
                            .picture(profile != null && profile.has("image_72") ? profile.get("image_72").asText() : null)
                            .userId(userId)
                            .build();
                }
            }

            // Fall back to basic info from auth.test
            return OAuthUserInfo.builder()
                    .name(authJson.has("user") ? authJson.get("user").asText() : null)
                    .userId(userId)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get Slack user info: {}", e.getMessage());
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
                    AUTH_TEST_ENDPOINT,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            return json.has("ok") && json.get("ok").asBoolean();
        } catch (Exception e) {
            log.debug("Slack token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
