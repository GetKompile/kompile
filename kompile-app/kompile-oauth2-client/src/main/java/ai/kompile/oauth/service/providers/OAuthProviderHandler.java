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

import ai.kompile.oauth.dto.OAuthProviderInfo;
import ai.kompile.oauth.dto.OAuthTokenResponse;
import ai.kompile.oauth.dto.OAuthUserInfo;

import java.util.List;

/**
 * Interface for OAuth provider-specific implementations.
 * Each provider (Google, Microsoft, Atlassian, Notion, Slack) implements this interface.
 */
public interface OAuthProviderHandler {

    /**
     * Get the unique provider identifier.
     * Examples: "google", "microsoft", "atlassian", "notion", "slack"
     */
    String getProviderId();

    /**
     * Get the display name for this provider.
     * Examples: "Google", "Microsoft 365", "Atlassian", "Notion", "Slack"
     */
    String getDisplayName();

    /**
     * Get a description of what this provider connects to.
     */
    String getDescription();

    /**
     * Get the Material icon name for this provider.
     */
    String getIcon();

    /**
     * Get the brand color for this provider (hex code).
     */
    String getColor();

    /**
     * Get the list of scopes required for this provider.
     */
    List<String> getRequiredScopes();

    /**
     * Get the list of source providers that use this OAuth connection.
     */
    List<String> getRelatedSources();

    /**
     * Check if this provider is configured (has client ID and secret).
     */
    boolean isConfigured();

    /**
     * Get a message explaining what's needed if not configured.
     */
    default String getNotConfiguredMessage() {
        return "This provider requires OAuth client credentials to be configured.";
    }

    /**
     * Build the authorization URL for the OAuth flow.
     *
     * @param redirectUri the callback URL
     * @param state       CSRF protection state parameter
     * @return full authorization URL
     */
    String buildAuthorizationUrl(String redirectUri, String state);

    /**
     * Exchange an authorization code for tokens.
     *
     * @param code        the authorization code from the callback
     * @param redirectUri the callback URL (must match the one used in authorization)
     * @return token response containing access and refresh tokens
     */
    OAuthTokenResponse exchangeCodeForTokens(String code, String redirectUri);

    /**
     * Refresh an access token using a refresh token.
     *
     * @param refreshToken the refresh token
     * @return token response containing new access token
     */
    OAuthTokenResponse refreshAccessToken(String refreshToken);

    /**
     * Revoke OAuth tokens (disconnect).
     *
     * @param accessToken  the access token to revoke
     * @param refreshToken the refresh token to revoke
     * @return true if revocation was successful
     */
    boolean revokeToken(String accessToken, String refreshToken);

    /**
     * Get user information using an access token.
     *
     * @param accessToken the access token
     * @return user information
     */
    OAuthUserInfo getUserInfo(String accessToken);

    /**
     * Validate that an access token is still valid.
     *
     * @param accessToken the access token to validate
     * @return true if the token is valid
     */
    boolean validateToken(String accessToken);

    /**
     * Get the full provider info DTO.
     */
    default OAuthProviderInfo getProviderInfo() {
        return OAuthProviderInfo.builder()
                .providerId(getProviderId())
                .displayName(getDisplayName())
                .description(getDescription())
                .icon(getIcon())
                .color(getColor())
                .configured(isConfigured())
                .notConfiguredMessage(isConfigured() ? null : getNotConfiguredMessage())
                .requiredScopes(getRequiredScopes())
                .relatedSources(getRelatedSources())
                .build();
    }
}
