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

package ai.kompile.chat.history.service;

import ai.kompile.chat.history.config.ChatHistoryProperties;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled service that automatically imports new CLI transcripts into the app database.
 * Runs after startup (async) and periodically (default 5 min) to discover and import
 * new conversations from all registered chat source adapters.
 *
 * Publishes progress events over WebSocket at /topic/chat-sync/progress so the
 * frontend can show a live progress indicator during background sync.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
@ConditionalOnProperty(name = "kompile.chat.history.cli-sync-enabled", havingValue = "true", matchIfMissing = true)
public class CliTranscriptSyncService {

    private static final String SYNC_TOPIC = "/topic/chat-sync/progress";

    @Autowired
    private final CliTranscriptService cliTranscriptService;
    @Autowired
    private final ChatHistoryProperties properties;
    @Autowired(required = false)
    private final SimpMessagingTemplate messagingTemplate;

    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    // Visible sync state — read from the REST status endpoint
    private volatile SyncStatus lastStatus = new SyncStatus();

    @Async
    @EventListener(ApplicationReadyEvent.class)
    void initialSync() {
        log.info("CLI transcript sync: running initial sync (async, non-blocking)...");
        syncAll();
    }

    @Scheduled(fixedRateString = "${kompile.chat.history.cli-sync-interval-ms:300000}")
    public void scheduledSync() {
        log.debug("CLI transcript sync: running scheduled sync...");
        syncAll();
    }

    /**
     * Manually trigger a full sync. Returns immediately if one is already running.
     */
    public boolean triggerSync() {
        if (syncInProgress.get()) {
            return false; // already running
        }
        new Thread(() -> syncAll(), "cli-sync-manual").start();
        return true;
    }

    /**
     * Return the current or most-recent sync status snapshot.
     */
    public SyncStatus getStatus() {
        return lastStatus;
    }

    private void syncAll() {
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("CLI transcript sync: skipping, previous sync still in progress");
            return;
        }
        try {
            doSyncAll();
        } finally {
            syncInProgress.set(false);
        }
    }

    private void doSyncAll() {
        SyncStatus status = new SyncStatus();
        status.running = true;
        status.startedAt = Instant.now().toString();
        this.lastStatus = status;
        publishProgress(status);

        int totalImported = 0;
        int totalFailed = 0;
        int totalPending = 0;

        List<ChatSourceAdapter> adapters = new ArrayList<>(ChatSourceRegistry.getInstance().all());
        status.totalSources = adapters.size();

        for (int i = 0; i < adapters.size(); i++) {
            ChatSourceAdapter adapter = adapters.get(i);
            status.currentSource = adapter.id();
            status.sourceIndex = i + 1;
            publishProgress(status);

            try {
                // Discover how many new sessions this source has
                List<CliTranscriptService.CliSessionSummary> newSessions =
                        cliTranscriptService.listNewSessions(adapter.id());

                int pending = newSessions.size();
                totalPending += pending;
                status.sourcePending = pending;
                status.sourceImported = 0;
                status.sourceFailed = 0;
                publishProgress(status);

                if (pending == 0) {
                    continue;
                }

                log.info("CLI transcript sync: {} has {} new sessions to import", adapter.id(), pending);

                // Import all pending sessions — no batch cap
                for (int j = 0; j < newSessions.size(); j++) {
                    CliTranscriptService.CliSessionSummary session = newSessions.get(j);
                    try {
                        long originalTs = session.lastModified() > 0
                                ? session.lastModified() : System.currentTimeMillis();
                        cliTranscriptService.importTranscript(
                                session.sessionId(), session.source(), originalTs);
                        totalImported++;
                        status.sourceImported++;
                    } catch (Exception e) {
                        totalFailed++;
                        status.sourceFailed++;
                        log.warn("CLI transcript sync: failed to import session {} from {}: {}",
                                session.sessionId(), adapter.id(), e.getMessage());
                    }

                    // Publish progress every 5 sessions or on the last one
                    if ((j + 1) % 5 == 0 || j == newSessions.size() - 1) {
                        status.totalImported = totalImported;
                        status.totalFailed = totalFailed;
                        publishProgress(status);
                    }
                }

                if (status.sourceImported > 0) {
                    log.info("CLI transcript sync: imported {} sessions from {} ({} failed)",
                            status.sourceImported, adapter.id(), status.sourceFailed);
                }
            } catch (Exception e) {
                log.warn("CLI transcript sync: failed to sync source {}: {}", adapter.id(), e.getMessage());
                status.errors.add(adapter.id() + ": " + e.getMessage());
            }
        }

        status.running = false;
        status.totalImported = totalImported;
        status.totalFailed = totalFailed;
        status.totalPending = totalPending;
        status.completedAt = Instant.now().toString();
        status.currentSource = null;
        this.lastStatus = status;
        publishProgress(status);

        if (totalImported > 0 || totalFailed > 0) {
            log.info("CLI transcript sync: complete — imported {}, failed {}, total pending was {}",
                    totalImported, totalFailed, totalPending);
        } else {
            log.debug("CLI transcript sync: no new sessions found");
        }
    }

    private void publishProgress(SyncStatus status) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(SYNC_TOPIC, status);
            } catch (Exception e) {
                log.debug("Failed to publish sync progress: {}", e.getMessage());
            }
        }
    }

    /**
     * Live sync status — published over WebSocket and available via REST.
     */
    @Data
    public static class SyncStatus {
        private boolean running;
        private String startedAt;
        private String completedAt;
        private String currentSource;
        private int sourceIndex;
        private int totalSources;
        private int sourcePending;
        private int sourceImported;
        private int sourceFailed;
        private int totalImported;
        private int totalFailed;
        private int totalPending;
        private List<String> errors = new ArrayList<>();
    }
}
