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
 * OAuth handler for Microsoft (Azure AD, Microsoft 365, OneDrive, SharePoint).
 */
@Component
public class MicrosoftOAuthHandler extends AbstractOAuthProviderHandler {

    private static final String AUTHORIZATION_ENDPOINT_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
    private static final String TOKEN_ENDPOINT_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String GRAPH_USERINFO_ENDPOINT = "https://graph.microsoft.com/v1.0/me";
    private static final String DEFAULT_SCOPES = "Files.Read User.Read https://outlook.office365.com/IMAP.AccessAsUser.All offline_access";

    private OAuthSettingsService settingsService;

    @Value("${kompile.oauth.microsoft.client-id:}")
    private String defaultClientId;

    @Value("${kompile.oauth.microsoft.client-secret:}")
    private String defaultClientSecret;

    @Value("${kompile.oauth.microsoft.tenant-id:common}")
    private String defaultTenantId;

    @Value("${kompile.oauth.microsoft.scopes:" + DEFAULT_SCOPES + "}")
    private String defaultScopes;

    public MicrosoftOAuthHandler(RestTemplate restTemplate, ObjectMapper objectMapper) {
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

    private String getEffectiveTenantId() {
        if (settingsService != null) {
            String configured = settingsService.getTenantId(getProviderId());
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        return defaultTenantId != null && !defaultTenantId.isEmpty() ? defaultTenantId : "common";
    }

    @Override
    public String getProviderId() {
        return "microsoft";
    }

    @Override
    public String getDisplayName() {
        return "Microsoft 365";
    }

    @Override
    public String getDescription() {
        return "Connect to OneDrive, SharePoint, and other Microsoft 365 services";
    }

    @Override
    public String getIcon() {
        return "microsoft";
    }

    @Override
    public String getColor() {
        return "#00A4EF";
    }

    @Override
    public List<String> getRequiredScopes() {
        return List.of(getEffectiveScopes().split("\\s+"));
    }

    @Override
    public List<String> getRelatedSources() {
        return List.of("onedrive", "sharepoint", "email");
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
        return String.format(TOKEN_ENDPOINT_TEMPLATE, getEffectiveTenantId());
    }

    @Override
    public String getNotConfiguredMessage() {
        return "Microsoft OAuth requires Azure AD app registration. Set kompile.oauth.microsoft.client-id and kompile.oauth.microsoft.client-secret in your configuration.";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", getEffectiveClientId());
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", getEffectiveScopes());
        params.put("state", state);
        params.put("response_mode", "query");

        String authEndpoint = String.format(AUTHORIZATION_ENDPOINT_TEMPLATE, getEffectiveTenantId());
        return authEndpoint + "?" + buildQueryString(params);
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
        // Microsoft doesn't have a simple token revocation endpoint
        // The token will expire naturally, and the user can revoke access from their Microsoft account
        log.info("Microsoft tokens cannot be programmatically revoked. User should revoke access from Microsoft account settings.");
        return true;
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) {
        try {
            String response = makeAuthenticatedGetRequest(GRAPH_USERINFO_ENDPOINT, accessToken);
            JsonNode json = objectMapper.readTree(response);

            return OAuthUserInfo.builder()
                    .email(json.has("mail") ? json.get("mail").asText() :
                           (json.has("userPrincipalName") ? json.get("userPrincipalName").asText() : null))
                    .name(json.has("displayName") ? json.get("displayName").asText() : null)
                    .userId(json.has("id") ? json.get("id").asText() : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get Microsoft user info: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean validateToken(String accessToken) {
        try {
            makeAuthenticatedGetRequest(GRAPH_USERINFO_ENDPOINT, accessToken);
            return true;
        } catch (Exception e) {
            log.debug("Microsoft token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
