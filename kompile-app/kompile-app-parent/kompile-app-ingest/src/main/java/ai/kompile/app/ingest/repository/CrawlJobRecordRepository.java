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

package ai.kompile.app.ingest.repository;

import ai.kompile.app.ingest.domain.CrawlJobRecord;
import ai.kompile.app.ingest.domain.CrawlJobRecord.CrawlJobStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisted crawl job records.
 */
@Repository
public interface CrawlJobRecordRepository extends JpaRepository<CrawlJobRecord, Long> {

    Optional<CrawlJobRecord> findByCrawlJobId(String crawlJobId);

    List<CrawlJobRecord> findByStatusOrderByStartedAtDesc(CrawlJobStatus status);

    List<CrawlJobRecord> findByStatusInOrderByStartedAtDesc(List<CrawlJobStatus> statuses);

    List<CrawlJobRecord> findBySeedContainingIgnoreCaseOrderByStartedAtDesc(String seed);

    @Query("SELECT c FROM CrawlJobRecord c WHERE c.lastCheckpointJson IS NOT NULL " +
            "AND c.status IN ('INTERRUPTED', 'FAILED', 'CANCELLED') " +
            "ORDER BY c.startedAt DESC")
    List<CrawlJobRecord> findResumableJobs();

    @Query("SELECT c FROM CrawlJobRecord c WHERE c.status IN ('RUNNING', 'PAUSED') " +
            "ORDER BY c.startedAt DESC")
    List<CrawlJobRecord> findActiveOnStartup();

    List<CrawlJobRecord> findTop20ByOrderByStartedAtDesc();
}
