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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled service that automatically imports new CLI transcripts into the app database.
 * Runs after startup (async) and periodically (default 5 min) to discover and import
 * new conversations from all registered chat source adapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kompile.chat.history.cli-sync-enabled", havingValue = "true", matchIfMissing = true)
public class CliTranscriptSyncService {

    private final CliTranscriptService cliTranscriptService;
    private final ChatHistoryProperties properties;
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

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
        int totalImported = 0;
        int batchSize = properties.getCliSyncBatchSize();

        for (ChatSourceAdapter adapter : ChatSourceRegistry.getInstance().all()) {
            try {
                int count = cliTranscriptService.syncSource(adapter.id(), batchSize);
                if (count > 0) {
                    totalImported += count;
                    log.info("CLI transcript sync: imported {} sessions from {}", count, adapter.id());
                }
            } catch (Exception e) {
                log.warn("CLI transcript sync: failed to sync source {}: {}", adapter.id(), e.getMessage());
            }
        }

        if (totalImported > 0) {
            log.info("CLI transcript sync: total imported {} new sessions", totalImported);
        } else {
            log.debug("CLI transcript sync: no new sessions found");
        }
    }
}
