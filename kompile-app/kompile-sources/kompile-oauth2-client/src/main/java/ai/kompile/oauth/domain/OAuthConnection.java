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

package ai.kompile.oauth.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Entity representing an OAuth connection to an external provider.
 * Stores encrypted tokens and connection metadata.
 */
@Entity
@Table(name = "oauth_connections")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthConnection {

    /**
     * Provider identifier (e.g., "google", "microsoft", "atlassian", "notion", "slack").
     */
    @Id
    @Column(name = "provider_id", length = 50)
    private String providerId;

    /**
     * Encrypted access token.
     */
    @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    /**
     * Encrypted refresh token (may be null for providers that don't support refresh).
     */
    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    /**
     * When the access token expires.
     */
    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /**
     * When this connection was first established.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When the token was last refreshed.
     */
    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    /**
     * When the connection was last used for an API call.
     */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * OAuth scopes granted by the user.
     */
    @Column(name = "scope", length = 2048)
    private String scope;

    /**
     * Email address of the authenticated user.
     */
    @Column(name = "user_email", length = 255)
    private String userEmail;

    /**
     * Display name of the authenticated user.
     */
    @Column(name = "user_name", length = 255)
    private String userName;

    /**
     * URL to the user's profile picture.
     */
    @Column(name = "user_picture", length = 1024)
    private String userPicture;

    /**
     * Current status of the connection.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ConnectionStatus status;

    /**
     * Last error message if status is ERROR.
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * Provider-specific additional data (JSON).
     * For example, Atlassian cloudId, Microsoft tenant, etc.
     */
    @Column(name = "provider_data", columnDefinition = "TEXT")
    private String providerData;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = ConnectionStatus.CONNECTED;
        }
    }

    /**
     * Check if the access token is expired or about to expire.
     * Returns true if token expires within the next 5 minutes.
     */
    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) {
            return false; // Some tokens don't expire
        }
        return Instant.now().plusSeconds(300).isAfter(tokenExpiresAt);
    }

    /**
     * Check if this connection can be refreshed.
     */
    public boolean canRefresh() {
        return refreshTokenEncrypted != null && !refreshTokenEncrypted.isEmpty();
    }
}
