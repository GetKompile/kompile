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
package ai.kompile.event.observation.listener;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.service.GraphEventScanner;
import ai.kompile.event.observation.service.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Keeps empirical event priors up to date over time from crawls and crawl updates: on every
 * {@link GraphBuildCompletedEvent} it scans the (re)built fact-sheet subgraph and ingests entity and
 * connection observations. Same wiring as {@code DataEnrichmentServiceImpl}, which proves a plain
 * {@code @EventListener} on this event fires in the running app.
 */
@Component
public class CrawlEventObservationListener {

    private static final Logger log = LoggerFactory.getLogger(CrawlEventObservationListener.class);

    private final GraphEventScanner scanner;
    private final EventObservationConfigService configService;

    public CrawlEventObservationListener(GraphEventScanner scanner, EventObservationConfigService configService) {
        this.scanner = scanner;
        this.configService = configService;
    }

    @Async
    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        if (!configService.getConfig().enabled()) {
            return;
        }
        try {
            ScanResult result = scanner.scan(event.getFactSheetId(), EventSource.CRAWL, event.getJobId());
            if (result.ran()) {
                log.info("Observed {} events ({} entity, {} connection) from crawl {} (factSheet={})",
                        result.total(), result.entitiesObserved(), result.connectionsObserved(),
                        event.getJobId(), event.getFactSheetId());
            }
        } catch (Exception e) {
            log.warn("Event observation scan failed for crawl {}: {}", event.getJobId(), e.getMessage());
        }
    }
}
