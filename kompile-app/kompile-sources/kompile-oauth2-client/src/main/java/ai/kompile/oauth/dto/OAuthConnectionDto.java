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

import ai.kompile.oauth.domain.ConnectionStatus;
import ai.kompile.oauth.domain.OAuthConnection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * DTO for OAuth connection information (never exposes tokens).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthConnectionDto {

    /**
     * Provider identifier.
     */
    private String providerId;

    /**
     * Provider display name.
     */
    private String providerDisplayName;

    /**
     * Provider icon name.
     */
    private String providerIcon;

    /**
     * Current connection status.
     */
    private String status;

    /**
     * Email of connected user.
     */
    private String userEmail;

    /**
     * Name of connected user.
     */
    private String userName;

    /**
     * Profile picture URL.
     */
    private String userPicture;

    /**
     * When connection was established.
     */
    private Instant connectedAt;

    /**
     * When access token expires.
     */
    private Instant expiresAt;

    /**
     * When connection was last used.
     */
    private Instant lastUsedAt;

    /**
     * Granted scopes.
     */
    private List<String> scopes;

    /**
     * Error message if status is ERROR.
     */
    private String errorMessage;

    /**
     * Whether the token is expired or about to expire.
     */
    private boolean needsRefresh;

    /**
     * Create DTO from entity.
     */
    public static OAuthConnectionDto fromEntity(OAuthConnection connection, String displayName, String icon) {
        if (connection == null) {
            return null;
        }

        List<String> scopeList = null;
        if (connection.getScope() != null && !connection.getScope().isEmpty()) {
            scopeList = List.of(connection.getScope().split("\\s+"));
        }

        return OAuthConnectionDto.builder()
                .providerId(connection.getProviderId())
                .providerDisplayName(displayName)
                .providerIcon(icon)
                .status(connection.getStatus().name().toLowerCase())
                .userEmail(connection.getUserEmail())
                .userName(connection.getUserName())
                .userPicture(connection.getUserPicture())
                .connectedAt(connection.getCreatedAt())
                .expiresAt(connection.getTokenExpiresAt())
                .lastUsedAt(connection.getLastUsedAt())
                .scopes(scopeList)
                .errorMessage(connection.getStatus() == ConnectionStatus.ERROR ? connection.getLastError() : null)
                .needsRefresh(connection.isTokenExpired())
                .build();
    }
}
