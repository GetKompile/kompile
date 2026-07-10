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

package ai.kompile.process.discovery.mining;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.process.discovery.ProcessSuggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Optionally runs the LLM-free process miner automatically whenever a graph build finishes — the
 * principled counterpart to {@code ProcessDiscoveryServiceImpl.onGraphBuildCompleted}.
 *
 * <p>Enabled by default (disable with {@code kompile.process.mining.auto-discover=false}), so every
 * graph build yields a mined — and entailed — suggestion in the store without anyone having to call
 * the mining endpoint. Kept as a separate conditional bean (rather than a method on the service) so
 * the flow stays independently switchable.
 */
@Component
@ConditionalOnProperty(name = "kompile.process.mining.auto-discover", havingValue = "true", matchIfMissing = true)
public class MiningAutoDiscoveryListener {

    private static final Logger log = LoggerFactory.getLogger(MiningAutoDiscoveryListener.class);

    private final MiningProcessDiscoveryService miningService;

    public MiningAutoDiscoveryListener(MiningProcessDiscoveryService miningService) {
        this.miningService = miningService;
    }

    @Async
    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        if (event.getFactSheetId() == null) {
            return;
        }
        try {
            ProcessSuggestion suggestion = miningService.discoverForFactSheet(event.getFactSheetId(), 0.0, null);
            if (suggestion != null) {
                log.info("Process mining auto-discovered a process for fact sheet {} after graph build {}",
                        event.getFactSheetId(), event.getJobId());
            }
        } catch (Exception e) {
            log.warn("Process mining auto-discovery failed after graph build {}: {}",
                    event.getJobId(), e.getMessage());
        }
    }
}
