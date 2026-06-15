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

package ai.kompile.metrics.binder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for knowledge graph operations (entity extraction, graph construction).
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.graph.entities.extracted} – total entities extracted</li>
 *   <li>{@code kompile.graph.relationships.extracted} – total relationships extracted</li>
 *   <li>{@code kompile.graph.communities.detected} – total communities detected</li>
 *   <li>{@code kompile.graph.extraction.jobs.total} – total extraction jobs started</li>
 *   <li>{@code kompile.graph.extraction.jobs.completed} – completed extraction jobs</li>
 *   <li>{@code kompile.graph.extraction.jobs.failed} – failed extraction jobs</li>
 *   <li>{@code kompile.graph.queries.total} – total graph queries executed</li>
 * </ul>
 *
 * Gauges:
 * <ul>
 *   <li>{@code kompile.graph.nodes.total} – current total node count</li>
 *   <li>{@code kompile.graph.edges.total} – current total edge count</li>
 *   <li>{@code kompile.graph.communities.total} – current total community count</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.graph.extraction.time} – extraction job duration distribution</li>
 *   <li>{@code kompile.graph.query.time} – graph query duration distribution</li>
 * </ul>
 */
public class KnowledgeGraphMetrics {

    private final MeterRegistry registry;
    private final AtomicLong totalNodes = new AtomicLong(0);
    private final AtomicLong totalEdges = new AtomicLong(0);
    private final AtomicLong totalCommunities = new AtomicLong(0);

    private Counter entitiesExtracted;
    private Counter relationshipsExtracted;
    private Counter communitiesDetected;
    private Counter extractionJobsTotal;
    private Counter extractionJobsCompleted;
    private Counter extractionJobsFailed;
    private Counter queriesTotal;
    private Timer extractionTimer;
    private Timer queryTimer;

    public KnowledgeGraphMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        entitiesExtracted = Counter.builder("kompile.graph.entities.extracted")
                .description("Total entities extracted from documents").register(registry);
        relationshipsExtracted = Counter.builder("kompile.graph.relationships.extracted")
                .description("Total relationships extracted from documents").register(registry);
        communitiesDetected = Counter.builder("kompile.graph.communities.detected")
                .description("Total communities detected").register(registry);

        extractionJobsTotal = Counter.builder("kompile.graph.extraction.jobs.total")
                .description("Total graph extraction jobs started").register(registry);
        extractionJobsCompleted = Counter.builder("kompile.graph.extraction.jobs.completed")
                .description("Completed graph extraction jobs").register(registry);
        extractionJobsFailed = Counter.builder("kompile.graph.extraction.jobs.failed")
                .description("Failed graph extraction jobs").register(registry);

        queriesTotal = Counter.builder("kompile.graph.queries.total")
                .description("Total graph queries executed").register(registry);

        extractionTimer = Timer.builder("kompile.graph.extraction.time")
                .description("Graph extraction job duration").register(registry);
        queryTimer = Timer.builder("kompile.graph.query.time")
                .description("Graph query duration").register(registry);

        Gauge.builder("kompile.graph.nodes.total", totalNodes, AtomicLong::get)
                .description("Current total graph node count").register(registry);
        Gauge.builder("kompile.graph.edges.total", totalEdges, AtomicLong::get)
                .description("Current total graph edge count").register(registry);
        Gauge.builder("kompile.graph.communities.total", totalCommunities, AtomicLong::get)
                .description("Current total community count").register(registry);
    }

    public void recordExtractionStarted() {
        extractionJobsTotal.increment();
    }

    public void recordExtractionCompleted(long durationMs, int entities, int relationships) {
        extractionJobsCompleted.increment();
        entitiesExtracted.increment(entities);
        relationshipsExtracted.increment(relationships);
        if (durationMs > 0) extractionTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordExtractionFailed() {
        extractionJobsFailed.increment();
    }

    public void recordCommunitiesDetected(int count) {
        communitiesDetected.increment(count);
    }

    public void recordQuery(long durationMs) {
        queriesTotal.increment();
        if (durationMs > 0) queryTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void setTotalNodes(long count) {
        totalNodes.set(count);
    }

    public void setTotalEdges(long count) {
        totalEdges.set(count);
    }

    public void setTotalCommunities(long count) {
        totalCommunities.set(count);
    }
}
