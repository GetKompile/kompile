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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Abstract base class for OAuth provider handlers with common HTTP utilities.
 */
public abstract class AbstractOAuthProviderHandler implements OAuthProviderHandler {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper;

    protected AbstractOAuthProviderHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Get the client ID for this provider.
     */
    protected abstract String getClientId();

    /**
     * Get the client secret for this provider.
     */
    protected abstract String getClientSecret();

    /**
     * Get the token endpoint URL.
     */
    protected abstract String getTokenEndpoint();

    @Override
    public boolean isConfigured() {
        String clientId = getClientId();
        String clientSecret = getClientSecret();
        return clientId != null && !clientId.isEmpty()
            && clientSecret != null && !clientSecret.isEmpty();
    }

    /**
     * Build a URL-encoded query string from parameters.
     */
    protected String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Exchange authorization code for tokens using standard OAuth2 flow.
     */
    protected OAuthTokenResponse performTokenExchange(String code, String redirectUri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "authorization_code");
            body.add("code", code);
            body.add("redirect_uri", redirectUri);
            body.add("client_id", getClientId());
            body.add("client_secret", getClientSecret());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    getTokenEndpoint(),
                    HttpMethod.POST,
                    request,
                    String.class
            );

            return parseTokenResponse(response.getBody());
        } catch (Exception e) {
            log.error("Token exchange failed for provider {}: {}", getProviderId(), e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("token_exchange_failed")
                    .errorDescription(e.getMessage())
                    .build();
        }
    }

    /**
     * Refresh access token using refresh token.
     */
    protected OAuthTokenResponse performTokenRefresh(String refreshToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", refreshToken);
            body.add("client_id", getClientId());
            body.add("client_secret", getClientSecret());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    getTokenEndpoint(),
                    HttpMethod.POST,
                    request,
                    String.class
            );

            OAuthTokenResponse tokenResponse = parseTokenResponse(response.getBody());

            // Keep the original refresh token if a new one wasn't provided
            if (tokenResponse.getRefreshToken() == null) {
                tokenResponse.setRefreshToken(refreshToken);
            }

            return tokenResponse;
        } catch (Exception e) {
            log.error("Token refresh failed for provider {}: {}", getProviderId(), e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("token_refresh_failed")
                    .errorDescription(e.getMessage())
                    .build();
        }
    }

    /**
     * Parse OAuth token response JSON.
     */
    protected OAuthTokenResponse parseTokenResponse(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);

            // Check for error
            if (json.has("error")) {
                return OAuthTokenResponse.builder()
                        .error(json.get("error").asText())
                        .errorDescription(json.has("error_description")
                                ? json.get("error_description").asText() : null)
                        .build();
            }

            OAuthTokenResponse response = OAuthTokenResponse.builder()
                    .accessToken(json.has("access_token") ? json.get("access_token").asText() : null)
                    .refreshToken(json.has("refresh_token") ? json.get("refresh_token").asText() : null)
                    .tokenType(json.has("token_type") ? json.get("token_type").asText() : "Bearer")
                    .expiresIn(json.has("expires_in") ? json.get("expires_in").asLong() : null)
                    .scope(json.has("scope") ? json.get("scope").asText() : null)
                    .build();

            response.calculateExpiresAt();
            return response;
        } catch (Exception e) {
            log.error("Failed to parse token response: {}", e.getMessage());
            return OAuthTokenResponse.builder()
                    .error("parse_error")
                    .errorDescription("Failed to parse token response: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Make a GET request with Bearer token authorization.
     */
    protected String makeAuthenticatedGetRequest(String url, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                String.class
        );

        return response.getBody();
    }

    /**
     * Make a POST request with Bearer token authorization.
     */
    protected String makeAuthenticatedPostRequest(String url, String accessToken, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                String.class
        );

        return response.getBody();
    }
}
