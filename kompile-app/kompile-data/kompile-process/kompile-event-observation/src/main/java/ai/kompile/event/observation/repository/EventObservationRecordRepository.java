/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.event.observation.repository;

import ai.kompile.event.observation.domain.EventObservationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA repository for the append-only observation ledger (the prior time-series).
 */
@Repository
public interface EventObservationRecordRepository extends JpaRepository<EventObservationRecord, Long> {

    List<EventObservationRecord> findByEventKeyOrderByObservedAtAsc(String eventKey);

    @Modifying
    @Query("DELETE FROM EventObservationRecord r WHERE r.observedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
