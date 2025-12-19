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

package ai.kompile.oauth.repository;

import ai.kompile.oauth.domain.ConnectionStatus;
import ai.kompile.oauth.domain.OAuthConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for OAuth connection persistence.
 */
@Repository
public interface OAuthConnectionRepository extends JpaRepository<OAuthConnection, String> {

    /**
     * Find all connections with a specific status.
     */
    List<OAuthConnection> findByStatus(ConnectionStatus status);

    /**
     * Find all connected (active) connections.
     */
    default List<OAuthConnection> findAllConnected() {
        return findByStatus(ConnectionStatus.CONNECTED);
    }

    /**
     * Find connections that need token refresh (expired or about to expire).
     */
    @Query("SELECT c FROM OAuthConnection c WHERE c.status = 'CONNECTED' " +
           "AND c.tokenExpiresAt IS NOT NULL " +
           "AND c.tokenExpiresAt < :expiryThreshold " +
           "AND c.refreshTokenEncrypted IS NOT NULL")
    List<OAuthConnection> findConnectionsNeedingRefresh(@Param("expiryThreshold") Instant expiryThreshold);

    /**
     * Update the last used timestamp for a connection.
     */
    @Modifying
    @Query("UPDATE OAuthConnection c SET c.lastUsedAt = :lastUsedAt WHERE c.providerId = :providerId")
    void updateLastUsedAt(@Param("providerId") String providerId, @Param("lastUsedAt") Instant lastUsedAt);

    /**
     * Check if a connection exists and is active.
     */
    default boolean isConnected(String providerId) {
        return findById(providerId)
                .map(c -> c.getStatus() == ConnectionStatus.CONNECTED)
                .orElse(false);
    }
}
