/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.service.impl;

import ai.kompile.cli.common.logs.LogRetentionManager;
import ai.kompile.cli.common.logs.LogRetentionPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodic sweep enforcing the log retention policy on agent logs.
 *
 * <p>Enabled by default. Disable with {@code kompile.logs.retention.enabled=false}.
 * The sweep interval is configured via {@code kompile.logs.retention.sweep-interval-ms}
 * (default: 1 hour).
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "kompile.logs.retention.enabled", havingValue = "true", matchIfMissing = true)
public class AgentLogRetentionService {

    @Value("${kompile.logs.retention.max-age-days:30}")
    private long maxAgeDays;

    @Value("${kompile.logs.retention.max-total-size-mb:2048}")
    private long maxTotalMb;

    @Value("${kompile.logs.retention.max-files-per-agent:100}")
    private int maxFilesPerAgent;

    @Scheduled(
            initialDelayString = "${kompile.logs.retention.initial-delay-ms:60000}",
            fixedDelayString = "${kompile.logs.retention.sweep-interval-ms:3600000}")
    public void sweep() {
        LogRetentionPolicy policy = LogRetentionPolicy.of(maxAgeDays, maxTotalMb, maxFilesPerAgent);
        LogRetentionManager manager = new LogRetentionManager(policy);
        LogRetentionManager.RetentionResult agentResult = manager.applyToAgents();
        LogRetentionManager.RetentionResult subprocessResult = manager.applyToSubprocesses();
        int totalDeleted = agentResult.totalDeleted() + subprocessResult.totalDeleted();
        if (totalDeleted > 0) {
            log.info("Log retention swept {} runs — agents(age={}, perAgent={}, size={}), subprocesses(age={}, perType={}, size={})",
                    totalDeleted,
                    agentResult.deletedByAge(), agentResult.deletedByPerAgent(), agentResult.deletedBySize(),
                    subprocessResult.deletedByAge(), subprocessResult.deletedByPerAgent(), subprocessResult.deletedBySize());
        } else {
            log.debug("Log retention swept — nothing to delete");
        }
    }
}
