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

package ai.kompile.app.sync.repository;

import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface NoteSyncConnectionRepository extends JpaRepository<NoteSyncConnection, Long> {

    List<NoteSyncConnection> findByFactSheetIdOrderByCreatedAtDesc(Long factSheetId);

    List<NoteSyncConnection> findByEnabledTrue();

    List<NoteSyncConnection> findByFactSheetIdAndProvider(Long factSheetId, SyncProvider provider);

    Optional<NoteSyncConnection> findByWebhookId(String webhookId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NoteSyncConnection c
               set c.activeSyncRunId = :runId,
                   c.syncLeaseExpiresAt = :expiresAt,
                   c.updatedAt = :now
             where c.id = :connectionId
               and (c.activeSyncRunId is null
                    or c.syncLeaseExpiresAt is null
                    or c.syncLeaseExpiresAt < :now)
            """)
    int acquireRunLease(@Param("connectionId") Long connectionId,
                        @Param("runId") String runId,
                        @Param("now") Instant now,
                        @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NoteSyncConnection c
               set c.syncLeaseExpiresAt = :expiresAt,
                   c.updatedAt = :now
             where c.id = :connectionId
               and c.activeSyncRunId = :runId
            """)
    int renewRunLease(@Param("connectionId") Long connectionId,
                      @Param("runId") String runId,
                      @Param("now") Instant now,
                      @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NoteSyncConnection c
               set c.activeSyncRunId = null,
                   c.syncLeaseExpiresAt = null,
                   c.updatedAt = :now
             where c.id = :connectionId
               and c.activeSyncRunId = :runId
            """)
    int releaseRunLease(@Param("connectionId") Long connectionId,
                        @Param("runId") String runId,
                        @Param("now") Instant now);
}
