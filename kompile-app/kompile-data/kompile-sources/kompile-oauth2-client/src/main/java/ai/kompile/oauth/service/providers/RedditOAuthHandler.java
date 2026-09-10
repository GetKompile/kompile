/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.oauth.service.providers;

import ai.kompile.oauth.dto.OAuthTokenResponse;
import ai.kompile.oauth.dto.OAuthUserInfo;
import ai.kompile.oauth.service.OAuthSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OAuth2 handler for Reddit web applications. */
@Component
public class RedditOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT = "https://www.reddit.com/api/v1/authorize";
    private static final String TOKEN_ENDPOINT = "https://www.reddit.com/api/v1/access_token";
    private static final String REVOKE_ENDPOINT = "https://www.reddit.com/api/v1/revoke_token";
    private static final String USER_INFO_ENDPOINT = "https://oauth.reddit.com/api/v1/me";
    private static final String DEFAULT_SCOPES = "identity read";
    private static final String USER_AGENT = "Kompile/0.1 (Reddit source integration)";

    private OAuthSettingsService settingsService;

    @Value("${kompile.oauth.reddit.client-id:}")
    private String defaultClientId;

    @Value("${kompile.oauth.reddit.client-secret:}")
    private String defaultClientSecret;

    @Value("${kompile.oauth.reddit.scopes:" + DEFAULT_SCOPES + "}")
    private String defaultScopes;

    public RedditOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
        super(restTemplate, objectMapper);
    }

    @Autowired(required = false)
    public void setSettingsService(OAuthSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override public String getProviderId() { return "reddit"; }
    @Override public String getDisplayName() { return "Reddit"; }
    @Override public String getDescription() { return "Connect Reddit for subreddit post and comment ingestion"; }
    @Override public String getIcon() { return "forum"; }
    @Override public String getColor() { return "#FF4500"; }
    @Override public List<String> getRequiredScopes() { return List.of(scopes().split("\\s+")); }
    @Override public List<String> getRelatedSources() { return List.of("reddit"); }
    @Override protected String getClientId() { return clientId(); }
    @Override protected String getClientSecret() { return clientSecret(); }
    @Override protected String getTokenEndpoint() { return TOKEN_ENDPOINT; }

    @Override
    public String getNotConfiguredMessage() {
        return "Reddit OAuth requires a web app client ID and secret configured through kompile auth source configure-oauth reddit.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId());
        params.put("response_type", "code");
        params.put("state", state);
        params.put("redirect_uri", redirectUri);
        params.put("duration", "permanent");
        params.put("scope", scopes());
        return AUTHORIZATION_ENDPOINT + "?" + buildQueryString(params);
    }

    @Override
    public OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        return tokenRequest(body, "token_exchange_failed");
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        OAuthTokenResponse response = tokenRequest(body, "token_refresh_failed");
        if (response.isSuccess() && response.getRefreshToken() == null) {
            response.setRefreshToken(refreshToken);
        }
        return response;
    }

    @Override
    public boolean revokeToken(String accessToken, String refreshToken) {
        String token = refreshToken != null && !refreshToken.isBlank() ? refreshToken : accessToken;
        if (token == null || token.isBlank()) return true;
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", token);
        body.add("token_type_hint", refreshToken != null && !refreshToken.isBlank()
                ? "refresh_token" : "access_token");
        try {
            restTemplate.exchange(REVOKE_ENDPOINT, HttpMethod.POST,
                    new HttpEntity<>(body, basicHeaders()), String.class);
            return true;
        } catch (Exception e) {
            log.warn("Reddit token revocation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) {
        try {
            JsonNode user = objectMapper.readTree(authenticatedGet(USER_INFO_ENDPOINT, accessToken));
            return OAuthUserInfo.builder()
                    .userId(user.path("id").asText(null))
                    .name(user.path("name").asText(null))
                    .picture(user.path("icon_img").asText(null))
                    .emailVerified(user.has("has_verified_email")
                            ? user.path("has_verified_email").asBoolean() : null)
                    .build();
        } catch (Exception e) {
            log.warn("Reddit user lookup failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean validateToken(String accessToken) {
        try {
            authenticatedGet(USER_INFO_ENDPOINT, accessToken);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private OAuthTokenResponse tokenRequest(
            MultiValueMap<String, String> body, String errorCode) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    TOKEN_ENDPOINT, HttpMethod.POST,
                    new HttpEntity<>(body, basicHeaders()), String.class);
            return parseTokenResponse(response.getBody());
        } catch (Exception e) {
            log.error("Reddit OAuth request failed: {}", e.getMessage());
            return OAuthTokenResponse.builder()
                    .error(errorCode).errorDescription(e.getMessage()).build();
        }
    }

    private HttpHeaders basicHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId(), clientSecret());
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return headers;
    }

    private String authenticatedGet(String url, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
    }

    private String clientId() {
        String configured = settingsService == null ? null : settingsService.getClientId(getProviderId());
        return configured != null && !configured.isBlank() ? configured : defaultClientId;
    }

    private String clientSecret() {
        String configured = settingsService == null ? null : settingsService.getClientSecret(getProviderId());
        return configured != null && !configured.isBlank() ? configured : defaultClientSecret;
    }

    private String scopes() {
        String configured = settingsService == null ? null : settingsService.getScopes(getProviderId());
        if (configured != null && !configured.isBlank()) return configured;
        return defaultScopes != null && !defaultScopes.isBlank() ? defaultScopes : DEFAULT_SCOPES;
    }
}
