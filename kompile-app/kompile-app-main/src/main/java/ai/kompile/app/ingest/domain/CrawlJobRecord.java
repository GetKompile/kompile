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

package ai.kompile.app.ingest.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted record of a crawl job, enabling resume after application restart.
 * Stores the full CrawlConfig and periodic CrawlState checkpoints as JSON
 * so interrupted crawls can be restarted from where they left off.
 */
@Entity
@Table(name = "crawl_job_record", indexes = {
        @Index(name = "idx_crawl_job_crawl_id", columnList = "crawlJobId", unique = true),
        @Index(name = "idx_crawl_job_status", columnList = "status"),
        @Index(name = "idx_crawl_job_seed", columnList = "seed")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlJobRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The runtime jobId assigned by CrawlerService.
     */
    @Column(nullable = false, length = 64, unique = true)
    private String crawlJobId;

    /**
     * Crawler implementation ID (e.g., "web", "filesystem").
     */
    @Column(length = 64)
    private String crawlerId;

    /**
     * Seed URL or root path for the crawl.
     */
    @Column(nullable = false, length = 1024)
    private String seed;

    /**
     * Full CrawlConfig serialized as JSON, used to re-launch on resume.
     */
    @Column(columnDefinition = "TEXT")
    private String crawlConfigJson;

    /**
     * Last CrawlState checkpoint serialized as JSON.
     * Contains visited URLs, content hashes, pending frontier, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String lastCheckpointJson;

    /**
     * Overall crawl job status.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private CrawlJobStatus status;

    /**
     * When the crawl job was started.
     */
    @Column
    private Instant startedAt;

    /**
     * When the last checkpoint was saved.
     */
    @Column
    private Instant lastCheckpointAt;

    /**
     * When the crawl job ended (completed, failed, or cancelled).
     */
    @Column
    private Instant endedAt;

    /**
     * Number of documents/URLs discovered.
     */
    @Column
    private Integer documentsDiscovered;

    /**
     * Number of documents/URLs successfully processed.
     */
    @Column
    private Integer documentsProcessed;

    /**
     * Number of documents/URLs skipped (unchanged, filtered, etc.).
     */
    @Column
    private Integer documentsSkipped;

    /**
     * Number of documents/URLs that failed processing.
     */
    @Column
    private Integer documentsFailed;

    /**
     * Error message if the job failed.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * FK link to IndexingJobHistory.taskId for the crawler history entry.
     */
    @Column(length = 64)
    private String historyTaskId;

    /**
     * If this crawl was created by resuming a previous crawl, this holds the original job ID.
     * Null for crawls that are not resumed from a previous run.
     */
    @Column(length = 64)
    private String resumedFromJobId;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == null) {
            status = CrawlJobStatus.RUNNING;
        }
    }

    /**
     * Crawl job status enumeration.
     */
    public enum CrawlJobStatus {
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED,
        /** Set on startup for jobs that were RUNNING/PAUSED when app was killed */
        INTERRUPTED
    }

    /**
     * Check if this record can be resumed.
     */
    public boolean isResumable() {
        return lastCheckpointJson != null
                && (status == CrawlJobStatus.INTERRUPTED
                || status == CrawlJobStatus.FAILED
                || status == CrawlJobStatus.CANCELLED);
    }

    /**
     * Update progress counters from a crawl progress snapshot.
     */
    public void updateProgress(int discovered, int processed, int skipped, int failed) {
        this.documentsDiscovered = discovered;
        this.documentsProcessed = processed;
        this.documentsSkipped = skipped;
        this.documentsFailed = failed;
    }
}
