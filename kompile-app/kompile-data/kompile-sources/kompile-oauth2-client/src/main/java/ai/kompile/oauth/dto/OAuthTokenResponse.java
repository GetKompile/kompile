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

package ai.kompile.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response from OAuth token exchange or refresh.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthTokenResponse {

    /**
     * The access token for API calls.
     */
    private String accessToken;

    /**
     * The refresh token for obtaining new access tokens.
     */
    private String refreshToken;

    /**
     * Token type (usually "Bearer").
     */
    private String tokenType;

    /**
     * When the access token expires.
     */
    private Instant expiresAt;

    /**
     * Seconds until expiration (from OAuth response).
     */
    private Long expiresIn;

    /**
     * Granted scopes (space-separated).
     */
    private String scope;

    /**
     * Provider-specific additional data (JSON).
     */
    private String providerData;

    /**
     * Error code if the request failed.
     */
    private String error;

    /**
     * Error description if the request failed.
     */
    private String errorDescription;

    /**
     * Check if this response indicates success.
     */
    public boolean isSuccess() {
        return accessToken != null && !accessToken.isEmpty() && error == null;
    }

    /**
     * Calculate expiration time from expiresIn seconds.
     */
    public void calculateExpiresAt() {
        if (expiresIn != null && expiresAt == null) {
            expiresAt = Instant.now().plusSeconds(expiresIn);
        }
    }
}
