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
 * Persisted CSRF state for OAuth authorization flows.
 * Replaces the in-memory ConcurrentHashMap so that pending authorizations
 * survive server restarts.
 */
@Entity
@Table(name = "pending_oauth_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingOAuthState {

    @Id
    @Column(name = "state", length = 64)
    private String state;

    @Column(name = "provider_id", length = 50, nullable = false)
    private String providerId;

    @Column(name = "redirect_uri", length = 2048)
    private String redirectUri;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (expiresAt == null) {
            expiresAt = Instant.now().plusSeconds(600); // 10 minute default
        }
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
