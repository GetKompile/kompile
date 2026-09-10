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

import ai.kompile.oauth.domain.PendingOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface PendingOAuthStateRepository extends JpaRepository<PendingOAuthState, String> {

    @Modifying
    @Query("DELETE FROM PendingOAuthState p WHERE p.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("DELETE FROM PendingOAuthState p WHERE p.state = :state "
            + "AND p.providerId = :providerId AND p.redirectUri = :redirectUri")
    int consume(@Param("state") String state,
                @Param("providerId") String providerId,
                @Param("redirectUri") String redirectUri);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("DELETE FROM PendingOAuthState p WHERE p.state = :state")
    int deleteStateClaim(@Param("state") String state);
}
