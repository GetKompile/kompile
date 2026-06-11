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

package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.CrawlJobRecord;
import ai.kompile.app.ingest.domain.CrawlJobRecord.CrawlJobStatus;
import ai.kompile.app.ingest.repository.CrawlJobRecordRepository;
import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlJob;
import ai.kompile.core.crawler.CrawlProgress;
import ai.kompile.core.crawler.CrawlState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Bridges the in-memory CrawlerService with database persistence for crawl job records.
 * Handles creating records on crawl start, periodic checkpoint saves, and
 * marking interrupted jobs on startup so they can be resumed.
 */
@Service
public class CrawlJobPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CrawlJobPersistenceService.class);

    private final CrawlJobRecordRepository repository;
    private final ObjectMapper objectMapper;

    public CrawlJobPersistenceService(CrawlJobRecordRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // Don't fail on unknown properties when deserializing configs from older versions
        this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * On startup, mark any RUNNING or PAUSED crawl records as INTERRUPTED.
     * These represent jobs that were active when the application was killed.
     */
    @PostConstruct
    @Transactional("ingestEventTransactionManager")
    public void markInterruptedOnStartup() {
        List<CrawlJobRecord> activeOnStartup = repository.findActiveOnStartup();
        if (activeOnStartup.isEmpty()) {
            return;
        }
        log.info("Found {} crawl jobs that were active when application stopped. Marking as INTERRUPTED.",
                activeOnStartup.size());
        for (CrawlJobRecord record : activeOnStartup) {
            record.setStatus(CrawlJobStatus.INTERRUPTED);
            record.setEndedAt(Instant.now());
            repository.save(record);
            log.info("Marked crawl job {} (seed: {}) as INTERRUPTED. {}",
                    record.getCrawlJobId(), record.getSeed(),
                    record.getLastCheckpointJson() != null ? "Checkpoint available for resume." : "No checkpoint.");
        }
    }

    /**
     * Create a new DB record when a crawl job starts.
     */
    @Transactional("ingestEventTransactionManager")
    public CrawlJobRecord createRecord(CrawlJob job, CrawlConfig config, String historyTaskId) {
        String configJson = null;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize CrawlConfig for job {}: {}", job.getJobId(), e.getMessage());
        }

        CrawlJobRecord record = CrawlJobRecord.builder()
                .crawlJobId(job.getJobId())
                .crawlerId(config.getCrawlerId())
                .seed(config.getSeed())
                .crawlConfigJson(configJson)
                .status(CrawlJobStatus.RUNNING)
                .startedAt(Instant.now())
                .historyTaskId(historyTaskId)
                .documentsDiscovered(0)
                .documentsProcessed(0)
                .documentsSkipped(0)
                .documentsFailed(0)
                .build();

        record = repository.save(record);
        log.debug("Created crawl job record for job {} (seed: {})", job.getJobId(), config.getSeed());
        return record;
    }

    /**
     * Save a periodic checkpoint for a running crawl job.
     */
    @Transactional("ingestEventTransactionManager")
    public void saveCheckpoint(String crawlJobId, CrawlState state, CrawlProgress progress) {
        repository.findByCrawlJobId(crawlJobId).ifPresent(record -> {
            try {
                record.setLastCheckpointJson(objectMapper.writeValueAsString(state));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize CrawlState checkpoint for job {}: {}",
                        crawlJobId, e.getMessage());
                return;
            }
            record.setLastCheckpointAt(Instant.now());
            if (progress != null) {
                record.updateProgress(
                        progress.discovered(),
                        progress.processed(),
                        progress.discovered() - progress.processed() - progress.failed() - progress.queued(),
                        progress.failed()
                );
            }
            repository.save(record);
            log.debug("Saved checkpoint for crawl job {} ({} processed, {} discovered)",
                    crawlJobId,
                    progress != null ? progress.processed() : "?",
                    progress != null ? progress.discovered() : "?");
        });
    }

    /**
     * Mark a crawl job as completed/failed/cancelled with final state.
     */
    @Transactional("ingestEventTransactionManager")
    public void finalizeRecord(String crawlJobId, CrawlJobStatus status,
                               CrawlState finalState, CrawlProgress finalProgress,
                               String errorMessage) {
        repository.findByCrawlJobId(crawlJobId).ifPresent(record -> {
            record.setStatus(status);
            record.setEndedAt(Instant.now());
            if (errorMessage != null) {
                record.setErrorMessage(errorMessage);
            }
            if (finalState != null) {
                try {
                    record.setLastCheckpointJson(objectMapper.writeValueAsString(finalState));
                    record.setLastCheckpointAt(Instant.now());
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize final CrawlState for job {}: {}",
                            crawlJobId, e.getMessage());
                }
            }
            if (finalProgress != null) {
                record.updateProgress(
                        finalProgress.discovered(),
                        finalProgress.processed(),
                        finalProgress.discovered() - finalProgress.processed()
                                - finalProgress.failed() - finalProgress.queued(),
                        finalProgress.failed()
                );
            }
            repository.save(record);
            log.info("Finalized crawl job {} with status {}", crawlJobId, status);
        });
    }

    /**
     * List all crawl job records that can be resumed.
     */
    public List<CrawlJobRecord> listResumable() {
        return repository.findResumableJobs();
    }

    /**
     * Set the resumedFromJobId on a crawl job record to track resume lineage.
     */
    @Transactional("ingestEventTransactionManager")
    public void setResumedFromJobId(String crawlJobId, String originalJobId) {
        repository.findByCrawlJobId(crawlJobId).ifPresent(record -> {
            record.setResumedFromJobId(originalJobId);
            repository.save(record);
            log.info("Marked crawl job {} as resumed from {}", crawlJobId, originalJobId);
        });
    }

    /**
     * List recent crawl job records.
     */
    public List<CrawlJobRecord> listRecent() {
        return repository.findTop20ByOrderByStartedAtDesc();
    }

    /**
     * Load the stored CrawlConfig for a given crawl job ID.
     */
    public Optional<CrawlConfig> loadConfig(String crawlJobId) {
        return repository.findByCrawlJobId(crawlJobId)
                .filter(r -> r.getCrawlConfigJson() != null)
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.getCrawlConfigJson(), CrawlConfig.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize CrawlConfig for job {}: {}",
                                crawlJobId, e.getMessage());
                        return null;
                    }
                });
    }

    /**
     * Load the stored CrawlState checkpoint for a given crawl job ID.
     */
    public Optional<CrawlState> loadCheckpoint(String crawlJobId) {
        return repository.findByCrawlJobId(crawlJobId)
                .filter(r -> r.getLastCheckpointJson() != null)
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.getLastCheckpointJson(), CrawlState.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize CrawlState checkpoint for job {}: {}",
                                crawlJobId, e.getMessage());
                        return null;
                    }
                });
    }

    /**
     * Get a crawl job record by its crawl job ID.
     */
    public Optional<CrawlJobRecord> getRecord(String crawlJobId) {
        return repository.findByCrawlJobId(crawlJobId);
    }
}
