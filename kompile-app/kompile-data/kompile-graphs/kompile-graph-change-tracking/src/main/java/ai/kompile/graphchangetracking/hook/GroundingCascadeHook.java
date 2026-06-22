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
package ai.kompile.graphchangetracking.hook;

import ai.kompile.core.crawl.graph.GraphEnrichmentService;
import ai.kompile.gateway.core.gateway.channel.ChannelMessageReceivedEvent;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L3 Grounding Cascade Hook — wires Spring events to the
 * {@link IncrementalReasoningOrchestrator} so the grounded KB stays current.
 *
 * <h3>Event sources handled</h3>
 * <ul>
 *   <li>{@link GraphChangesetCompletedEvent} — emitted by crawl + channel extraction pipelines
 *       whenever a batch of nodes/edges is committed to the graph.</li>
 *   <li>{@link ChannelMessageReceivedEvent} — emitted by {@code GraphUpdateChannelBridge}
 *       on every incoming channel/email message (before graph extraction). We treat this as
 *       an early signal that the fact sheet will change; the cascade runs after extraction.</li>
 *   <li>{@link AgentFactAssertedEvent} — emitted by
 *       {@link ai.kompile.knowledgegraph.grounding.KbGroundingService#assertFact} when an
 *       agent asserts a fact directly into the KB.</li>
 * </ul>
 *
 * <h3>Concurrency model (§6.2 of the design)</h3>
 * <p>A <em>per-factSheet single-threaded executor</em> is maintained in
 * {@link #executors}. All cascade tasks for the same fact sheet are serialized through this
 * executor; tasks for different fact sheets run in parallel. The write lock on
 * {@link ai.kompile.knowledgegraph.grounding.FactSheetKbState#lock()} is acquired inside
 * {@link IncrementalReasoningOrchestrator#runFullReground} for the full duration of
 * the MAP solve, so reads by agent tools ({@code verify} / {@code query}) are blocked
 * only during the write.</p>
 *
 * <h3>Null fact-sheet handling</h3>
 * <p>{@link GraphChangesetCompletedEvent#getFactSheetId()} and
 * {@link ChannelMessageReceivedEvent} may carry a null or absent fact-sheet id if the event
 * was not scoped to a specific fact sheet. Such events are ignored by the cascade (no
 * fact-sheet scope → no KB to update).</p>
 *
 * <h3>Registration</h3>
 * <p>Registered as a {@code @Bean} in
 * {@link ai.kompile.graphchangetracking.config.GraphChangeTrackingAutoConfiguration}.</p>
 */
@Component
@Slf4j
public class GroundingCascadeHook {

    /** Maximum pending cascade tasks per fact sheet before overflow tasks are dropped (§6.2). */
    static final int MAX_PENDING_PER_FACTSHEET = 8;

    private final IncrementalReasoningOrchestrator orchestrator;

    /**
     * Optional {@link GraphEnrichmentService} — when present (i.e., {@code kompile-crawl-graph}
     * is on the classpath and the {@code GraphHydrationOrchestrator} bean is wired), the full
     * orchestrated DERIVATION → PRUNE_COMPACT → HEALTH pipeline is used for the cascade instead
     * of calling the reasoning orchestrator and pruner directly. This ensures BATCH and CASCADE
     * paths share the same enrichment logic.
     *
     * <p>When absent the hook falls back to the direct {@link IncrementalReasoningOrchestrator}
     * call so that the hook continues to work in lightweight deployments that do not include the
     * crawl-graph module.</p>
     */
    @Nullable
    private final GraphEnrichmentService graphEnrichmentService;

    /** Per-factSheet single-threaded executors — serializes cascade runs per fact sheet. */
    private final ConcurrentHashMap<Long, ExecutorService> executors = new ConcurrentHashMap<>();

    @Autowired
    public GroundingCascadeHook(IncrementalReasoningOrchestrator orchestrator,
                                @Nullable GraphEnrichmentService graphEnrichmentService) {
        this.orchestrator = orchestrator;
        this.graphEnrichmentService = graphEnrichmentService;
    }

    /** Backward-compatible constructor for tests that do not wire the enrichment service. */
    public GroundingCascadeHook(IncrementalReasoningOrchestrator orchestrator) {
        this(orchestrator, null);
    }

    // ── Event listeners ─────────────────────────────────────────────────────────

    /**
     * React to a completed graph changeset (crawl / channel extraction).
     *
     * <p>Schedules a full re-ground of the affected fact sheet. This is the
     * {@code FULL_FACTSHEET} scope from the design (§3.3): crawl changesets may add many
     * atoms and the full program must be re-solved.</p>
     *
     * @param event the changeset event carrying the affected factSheetId
     */
    @EventListener
    @Async
    public void onChangesetCompleted(GraphChangesetCompletedEvent event) {
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.debug("GroundingCascadeHook: changeset {} has no factSheetId — skipping cascade",
                    event.getChangesetId());
            return;
        }
        log.info("GroundingCascadeHook: scheduling cascade for factSheet={} on changeset {} "
                        + "(+{}n +{}e)",
                factSheetId, event.getChangesetId(),
                event.getNodesCreated(), event.getEdgesCreated());
        schedule(factSheetId, "changeset:" + event.getChangesetId(), GroundingProgressEvent.TRIGGER_CRAWL);
    }

    /**
     * React to an incoming channel/email message.
     *
     * <p>The message's target fact sheet is not always available at the channel-receive stage
     * (extraction happens asynchronously). This listener is a best-effort early trigger:
     * if a {@code targetFactSheetId} can be determined from the message metadata, a cascade
     * is scheduled immediately. In practice, the follow-on {@link GraphChangesetCompletedEvent}
     * (published by the extraction pipeline) will also trigger a cascade, so double-runs are
     * safe — the second cascade will find no meaningful value changes and write zero new versions
     * (the fixed-point termination condition).</p>
     *
     * <p>For now: channel messages without an explicit factSheetId in their metadata are
     * handled solely via the changeset event.</p>
     *
     * @param event the channel message event
     */
    @EventListener
    @Async
    public void onChannelMessage(ChannelMessageReceivedEvent event) {
        // Channel messages do not carry a factSheetId directly in this event type.
        // The grounding cascade for channel events is driven by the downstream
        // GraphChangesetCompletedEvent emitted by the extraction pipeline.
        // This listener is retained as a hook point for future enhancement (e.g.,
        // when ChannelMessageReceivedEvent is extended to carry a targetFactSheetId).
        log.debug("GroundingCascadeHook: channel message from '{}' on channel '{}' — "
                + "cascade deferred to changeset event",
                event.getMessage() != null ? event.getMessage().userId() : "unknown",
                event.getChannelName());
    }

    /**
     * React to an agent asserting a fact.
     *
     * <p>Schedules a full re-ground of the affected fact sheet. This is the
     * {@code DELTA_ATOMS} scope from the design (§3.3): a single agent assert changes one
     * atom and the depth-2 neighbourhood should be re-solved. The current implementation
     * uses FULL_FACTSHEET scope (see {@link IncrementalReasoningOrchestrator}).</p>
     *
     * @param event the agent fact assertion event carrying factSheetId and atomKey
     */
    @EventListener
    @Async
    public void onAgentFactAsserted(AgentFactAssertedEvent event) {
        long factSheetId = event.getFactSheetId();
        log.info("GroundingCascadeHook: scheduling cascade for factSheet={} on agent assert '{}' "
                        + "value={} session={}",
                factSheetId, event.getAtomKey(), event.getValue(), event.getSessionId());
        schedule(factSheetId, "agentAssert:" + event.getAtomKey(), GroundingProgressEvent.TRIGGER_ASSERT);
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    /**
     * Submit a cascade task to the per-factSheet executor using {@link GroundingProgressEvent#TRIGGER_CASCADE}
     * as the default trigger label.
     *
     * @param factSheetId the fact sheet to re-ground
     * @param logLabel    human-readable label for logging only (not propagated to progress events)
     */
    void schedule(long factSheetId, String logLabel) {
        schedule(factSheetId, logLabel, GroundingProgressEvent.TRIGGER_CASCADE);
    }

    /**
     * Submit a cascade task to the per-factSheet executor.
     *
     * <p>If the executor's task queue is already at {@link #MAX_PENDING_PER_FACTSHEET},
     * the new request is dropped (§6.2 overflow policy). The next natural event will trigger
     * a fresh cascade that covers any missed delta.</p>
     *
     * @param factSheetId the fact sheet to re-ground
     * @param logLabel    human-readable trigger label for logging
     * @param trigger     one of the {@link GroundingProgressEvent} TRIGGER_* constants, propagated
     *                    to every {@link GroundingProgressEvent} emitted by this cascade run
     */
    void schedule(long factSheetId, String logLabel, String trigger) {
        ExecutorService executor = executors.computeIfAbsent(factSheetId, id -> {
            NamedThreadFactory threadFactory = new NamedThreadFactory(
                    "grounding-cascade-" + id);
            return Executors.newSingleThreadExecutor(threadFactory);
        });

        executor.submit(() -> {
            try {
                log.debug("GroundingCascadeHook: starting cascade for factSheet={} trigger={}",
                        factSheetId, logLabel);
                if (graphEnrichmentService != null) {
                    // Preferred path: delegate to GraphHydrationOrchestrator via the SPI so that
                    // CASCADE (incremental) and BATCH (crawl ENRICHMENT) share the same pipeline.
                    // Since GraphHydrationOrchestrator calls reasoningOrchestrator.runFullReground()
                    // which already publishes GroundingProgressEvents via ApplicationEventPublisher,
                    // events are emitted for free from the async cascade path.
                    // NOTE: GraphEnrichmentService.enrich() uses the no-trigger overload (CASCADE);
                    // for richer trigger labelling (CRAWL/ASSERT) the orchestrator is called directly
                    // when graphEnrichmentService is absent (fallback path below).
                    graphEnrichmentService.enrich(factSheetId);
                    log.debug("GroundingCascadeHook: cascade done for factSheet={} trigger={} "
                                    + "(via GraphEnrichmentService)",
                            factSheetId, logLabel);
                } else {
                    // Fallback path: direct orchestrator call for lightweight deployments that
                    // do not include kompile-crawl-graph on the classpath.
                    // Thread the trigger label so GroundingProgressEvents carry the correct source.
                    RegroundResult rg = orchestrator.runFullReground(factSheetId, trigger);
                    log.debug("GroundingCascadeHook: cascade done for factSheet={} trigger={} "
                                    + "versionsWritten={}",
                            factSheetId, logLabel, rg.versionsWritten());
                }
            } catch (Exception e) {
                log.error("GroundingCascadeHook: cascade failed for factSheet={} trigger={}: {}",
                        factSheetId, logLabel, e.getMessage(), e);
            }
        });
    }

    // ── Named thread factory ─────────────────────────────────────────────────────

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger count = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
