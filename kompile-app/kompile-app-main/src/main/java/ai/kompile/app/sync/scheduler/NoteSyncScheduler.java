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

package ai.kompile.app.sync.scheduler;

import ai.kompile.app.sync.config.NoteSyncConfigService;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.service.NoteSyncConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Background polling scheduler for sync connections. Runs every minute and
 * evaluates each enabled connection's pollCron expression to decide if it's
 * time to sync.
 *
 * Enabled at runtime via NoteSyncConfigService (JSON config, not @ConditionalOnProperty).
 */
@Component
public class NoteSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncScheduler.class);

    @Autowired
    private NoteSyncConfigService configService;

    @Autowired
    private NoteSyncConnectionRepository connectionRepository;

    @Autowired
    private NoteSyncConnectionService connectionService;

    @Scheduled(fixedDelayString = "60000")
    public void pollConnections() {
        if (!configService.isSchedulerEnabled()) {
            return;
        }

        List<NoteSyncConnection> connections = connectionRepository.findByEnabledTrue();
        for (NoteSyncConnection conn : connections) {
            if (conn.getPollCron() != null && !conn.getPollCron().isBlank()) {
                try {
                    if (isTimeToRun(conn)) {
                        log.info("Scheduler triggering sync for connection {} ({})",
                                conn.getId(), conn.getProvider());
                        connectionService.triggerSync(conn.getId());
                    }
                } catch (Exception e) {
                    log.error("Scheduler error for connection {}: {}", conn.getId(), e.getMessage());
                }
            }
        }
    }

    private boolean isTimeToRun(NoteSyncConnection conn) {
        try {
            CronExpression cron = CronExpression.parse(conn.getPollCron());
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastSync = conn.getLastSyncAt() != null
                    ? LocalDateTime.ofInstant(conn.getLastSyncAt(), ZoneId.systemDefault())
                    : now.minusYears(1);

            // Check if the cron should have fired between lastSync and now
            LocalDateTime nextFire = cron.next(lastSync);
            return nextFire != null && !nextFire.isAfter(now);
        } catch (Exception e) {
            log.warn("Invalid cron expression '{}' for connection {}: {}",
                    conn.getPollCron(), conn.getId(), e.getMessage());
            return false;
        }
    }
}
