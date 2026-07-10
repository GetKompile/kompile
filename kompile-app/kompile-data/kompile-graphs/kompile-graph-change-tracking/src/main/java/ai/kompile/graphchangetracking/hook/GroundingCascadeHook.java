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
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L3 Grounding Cascade Hook — implements {@link GroundingResetPort} and drives the
 * {@link IncrementalReasoningOrchestrator} so the grounded KB stays current.
 *
 * <h3>Event dispatch</h3>
 * <p>Spring events ({@code GraphChangesetCompletedEvent}, {@code ChannelMessageReceivedEvent},
 * {@code AgentFactAssertedEvent}) are received by {@link GroundingCascadeEventListener}, which
 * does <em>not</em> implement any interface and is therefore always CGLIB-proxied. That
 * companion component delegates here via {@link GroundingResetPort#schedule} so that the
 * {@code @EventListener} methods remain reachable regardless of the {@code proxyTargetClass}
 * setting on {@code @EnableAsync}.</p>
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
 * <h3>Registration</h3>
 * <p>Picked up via {@code @ComponentScan} in
 * {@link ai.kompile.graphchangetracking.config.GraphChangeTrackingAutoConfiguration}.</p>
 */
@Component
@Slf4j
public class GroundingCascadeHook implements GroundingResetPort {

    // ── Defaults for debounce parameters ────────────────────────────────────────

    /** Default quiet-period after the last mutation before the cascade fires (ms). */
    static final long DEFAULT_DEBOUNCE_MS = 15_000L;

    /** Default maximum wait before forcing a cascade run even under continuous mutations (ms). */
    static final long DEFAULT_MAX_WAIT_MS = 300_000L;

    // ── Debounce state (one entry per fact sheet) ────────────────────────────────

    /** Per-fact-sheet: timestamp of the last mutation that touched this fact sheet. */
    private final ConcurrentHashMap<Long, AtomicLong> lastMutationAt = new ConcurrentHashMap<>();

    /** Per-fact-sheet: timestamp when we first started waiting (for max-wait cap). */
    private final ConcurrentHashMap<Long, AtomicLong> debounceStartAt = new ConcurrentHashMap<>();

    /** Per-fact-sheet: whether a debounce task is currently scheduled (not yet running). */
    private final ConcurrentHashMap<Long, AtomicBoolean> debounceScheduled = new ConcurrentHashMap<>();

    /**
     * Coalescing policy for RUNNING cascades: at most one cascade task may be running per fact
     * sheet at any time. The debounce gate above prevents a second debounce task from being
     * scheduled while one is pending; this flag prevents a new cascade from starting while the
     * previous one is still running.
     */
    private final ConcurrentHashMap<Long, AtomicBoolean> pendingFlags = new ConcurrentHashMap<>();

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

    /**
     * Optional grounding-service reference, wired at startup via {@link #setKbGroundingService}.
     * Used to mark/clear the per-factSheet stale flag so the REST API can surface accurate
     * {@code stale} values in verify/explain responses.
     */
    @Nullable
    private KbGroundingService kbGroundingService;

    /** Per-factSheet single-threaded executors — serializes cascade runs per fact sheet. */
    private final ConcurrentHashMap<Long, ExecutorService> executors = new ConcurrentHashMap<>();

    /**
     * Shared scheduler for debounce timers. A single thread is sufficient because debounce tasks
     * are short (they either reschedule themselves or submit to the per-factSheet cascade executor).
     */
    private final ScheduledExecutorService debounceScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "grounding-debounce");
                t.setDaemon(true);
                return t;
            });

    /** Quiet period after last mutation (ms) before the cascade fires. Configurable for tests. */
    private final long debounceMs;

    /** Maximum total wait before a forced cascade run, even under continuous mutations (ms). */
    private final long maxWaitMs;

    @Autowired
    public GroundingCascadeHook(IncrementalReasoningOrchestrator orchestrator,
                                @Nullable GraphEnrichmentService graphEnrichmentService) {
        this(orchestrator, graphEnrichmentService, DEFAULT_DEBOUNCE_MS, DEFAULT_MAX_WAIT_MS);
    }

    /** Backward-compatible constructor for tests that do not wire the enrichment service. */
    public GroundingCascadeHook(IncrementalReasoningOrchestrator orchestrator) {
        this(orchestrator, null, DEFAULT_DEBOUNCE_MS, DEFAULT_MAX_WAIT_MS);
    }

    /**
     * Full constructor for tests that need custom debounce timing.
     *
     * @param orchestrator          the reasoning orchestrator
     * @param graphEnrichmentService optional enrichment service (may be null)
     * @param debounceMs            quiet-period after last mutation before cascade fires (ms)
     * @param maxWaitMs             max total wait before forced cascade, even under continuous mutations (ms)
     */
    public GroundingCascadeHook(IncrementalReasoningOrchestrator orchestrator,
                                @Nullable GraphEnrichmentService graphEnrichmentService,
                                long debounceMs,
                                long maxWaitMs) {
        this.orchestrator = orchestrator;
        this.graphEnrichmentService = graphEnrichmentService;
        this.debounceMs = debounceMs;
        this.maxWaitMs = maxWaitMs;
    }

    /**
     * Setter injection for {@link KbGroundingService} — {@code required=false} so the hook
     * remains functional in lightweight test contexts that do not wire the grounding service.
     */
    @Autowired(required = false)
    public void setKbGroundingService(KbGroundingService kbGroundingService) {
        this.kbGroundingService = kbGroundingService;
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    /**
     * Submit a cascade task to the per-factSheet executor using {@link GroundingProgressEvent#TRIGGER_CASCADE}
     * as the default trigger label.
     *
     * <p>This variant bypasses the debounce window and submits immediately. Use it for
     * high-priority triggers (changeset completed, agent assert) where latency matters.</p>
     *
     * @param factSheetId the fact sheet to re-ground
     * @param logLabel    human-readable label for logging only (not propagated to progress events)
     */
    public void schedule(long factSheetId, String logLabel) {
        schedule(factSheetId, logLabel, GroundingProgressEvent.TRIGGER_CASCADE);
    }

    /**
     * Submit a cascade task to the per-factSheet executor.
     *
     * <p><strong>Coalescing:</strong> if a cascade task is already queued or running for
     * {@code factSheetId}, the new request is silently dropped. This collapses a burst of
     * per-node mutation events (e.g. a crawl inserting 10 000 nodes via
     * {@code EventPublishingKnowledgeGraphService}) into a single reground, preventing a
     * storm of queued cascades. The {@link #pendingFlags} AtomicBoolean acts as the guard:
     * {@code compareAndSet(false, true)} claims the slot; the running task clears it on
     * completion so the next external trigger can queue again.</p>
     *
     * <p>The stale flag is marked IMMEDIATELY before the task is submitted so that any
     * verify/explain served between now and cascade completion correctly reports {@code stale=true}.</p>
     *
     * @param factSheetId the fact sheet to re-ground
     * @param logLabel    human-readable trigger label for logging
     * @param trigger     one of the {@link GroundingProgressEvent} TRIGGER_* constants, propagated
     *                    to every {@link GroundingProgressEvent} emitted by this cascade run
     */
    public void schedule(long factSheetId, String logLabel, String trigger) {
        // Mark stale IMMEDIATELY so that any verify/explain served while the cascade is pending
        // reports stale=true. This is intentionally before the coalescing gate so the flag is
        // always accurate regardless of whether this call wins the gate.
        if (kbGroundingService != null) {
            kbGroundingService.markStale(factSheetId);
        }

        // ── Coalescing gate ──────────────────────────────────────────────────────
        AtomicBoolean pending = pendingFlags.computeIfAbsent(factSheetId, id -> new AtomicBoolean(false));
        if (!pending.compareAndSet(false, true)) {
            log.debug("GroundingCascadeHook: coalesced redundant cascade for factSheet={} trigger={} — "
                    + "a cascade is already queued/running", factSheetId, logLabel);
            return;
        }

        submitCascadeTask(factSheetId, logLabel, trigger, pending);
    }

    /**
     * Schedule a debounced cascade for {@code factSheetId}.
     *
     * <p>Unlike {@link #schedule}, this method does NOT immediately submit a cascade task. Instead
     * it records the current time as the "last mutation" timestamp and ensures a single debounce
     * check task is scheduled. The check task fires after {@link #debounceMs} of quiet time and
     * either submits the cascade or reschedules itself if mutations are still arriving.</p>
     *
     * <p>A max-wait cap ({@link #maxWaitMs}) ensures the cascade always fires eventually even
     * during a sustained crawl with continuous batch mutations.</p>
     *
     * <p>The KB stale flag is marked immediately on the <em>first</em> call for a given fact
     * sheet (when {@code debounceStartAt} has no entry yet) so the stale indicator stays
     * truthful throughout the debounce window.</p>
     *
     * @param factSheetId the fact sheet to eventually re-ground
     * @param logLabel    human-readable trigger label for logging
     * @param trigger     one of the {@link GroundingProgressEvent} TRIGGER_* constants
     */
    public void scheduleDebounced(long factSheetId, String logLabel, String trigger) {
        long now = System.currentTimeMillis();

        // Update the "last mutation" timestamp for this fact sheet.
        lastMutationAt.computeIfAbsent(factSheetId, id -> new AtomicLong(0L)).set(now);

        // Record when we first started waiting (for max-wait cap).
        AtomicLong startAt = debounceStartAt.computeIfAbsent(factSheetId, id -> new AtomicLong(0L));
        startAt.compareAndSet(0L, now);   // only sets if not already tracking

        // Mark stale immediately on first call so stale flag is truthful during the debounce window.
        if (kbGroundingService != null) {
            kbGroundingService.markStale(factSheetId);
        }

        // Ensure only one debounce check task is scheduled at a time.
        AtomicBoolean scheduled = debounceScheduled.computeIfAbsent(factSheetId, id -> new AtomicBoolean(false));
        if (!scheduled.compareAndSet(false, true)) {
            // A check task is already scheduled — the timestamp update above is sufficient
            // for the pending check to pick up the latest mutation time.
            log.debug("GroundingCascadeHook: debounce touch for factSheet={} trigger={} (timer already running)",
                    factSheetId, logLabel);
            return;
        }

        log.debug("GroundingCascadeHook: debounce timer started for factSheet={} trigger={} quietMs={} maxWaitMs={}",
                factSheetId, logLabel, debounceMs, maxWaitMs);
        scheduleDebounceCheck(factSheetId, trigger, logLabel);
    }

    /**
     * Internal: schedule a single debounce check task that fires after {@link #debounceMs}.
     * The task re-evaluates whether the quiet period has elapsed (or the max-wait cap has been
     * reached) and either fires the cascade or reschedules itself.
     */
    private void scheduleDebounceCheck(long factSheetId, String trigger, String logLabel) {
        debounceScheduler.schedule(() -> {
            long now = System.currentTimeMillis();
            long lastMut = lastMutationAt.getOrDefault(factSheetId, new AtomicLong(0L)).get();
            long start = debounceStartAt.getOrDefault(factSheetId, new AtomicLong(now)).get();
            long quietMs = now - lastMut;
            long waitedMs = now - start;

            boolean quietPeriodElapsed = quietMs >= debounceMs;
            boolean maxWaitExceeded = waitedMs >= maxWaitMs;

            if (quietPeriodElapsed || maxWaitExceeded) {
                // Clear debounce state before firing so new mutations after the cascade can
                // start a fresh debounce window.
                debounceScheduled.getOrDefault(factSheetId, new AtomicBoolean(true)).set(false);
                debounceStartAt.remove(factSheetId);

                String reason = maxWaitExceeded ? "max-wait" : "quiet";
                log.debug("GroundingCascadeHook: debounce fired ({}) for factSheet={} after {}ms quiet / {}ms total",
                        reason, factSheetId, quietMs, waitedMs);
                schedule(factSheetId, logLabel + ":debounce-" + reason, trigger);
            } else {
                // Still receiving mutations — reschedule the check after another debounceMs.
                long remainingMs = debounceMs - quietMs;
                log.debug("GroundingCascadeHook: debounce rescheduled for factSheet={} quietMs={} remainMs={}",
                        factSheetId, quietMs, remainingMs);
                scheduleDebounceCheck(factSheetId, trigger, logLabel);
            }
        }, debounceMs, TimeUnit.MILLISECONDS);
    }

    /** Internal: submit the actual cascade task to the per-factSheet executor. */
    private void submitCascadeTask(long factSheetId, String logLabel, String trigger, AtomicBoolean pending) {
        ExecutorService executor = executors.computeIfAbsent(factSheetId, id ->
                Executors.newSingleThreadExecutor(new NamedThreadFactory("grounding-cascade-" + id)));

        executor.submit(() -> {
            boolean succeeded = false;
            try {
                log.debug("GroundingCascadeHook: starting cascade for factSheet={} trigger={}",
                        factSheetId, logLabel);
                if (graphEnrichmentService != null) {
                    // Preferred path: delegate to GraphHydrationOrchestrator via the SPI so that
                    // CASCADE (incremental) and BATCH (crawl ENRICHMENT) share the same pipeline.
                    graphEnrichmentService.enrich(factSheetId);
                    log.debug("GroundingCascadeHook: cascade done for factSheet={} trigger={} "
                                    + "(via GraphEnrichmentService)",
                            factSheetId, logLabel);
                } else {
                    // Fallback path: direct orchestrator call for lightweight deployments that
                    // do not include kompile-crawl-graph on the classpath.
                    RegroundResult rg = orchestrator.runFullReground(factSheetId, trigger);
                    log.debug("GroundingCascadeHook: cascade done for factSheet={} trigger={} "
                                    + "versionsWritten={}",
                            factSheetId, logLabel, rg.versionsWritten());
                }
                succeeded = true;
            } catch (Exception e) {
                log.error("GroundingCascadeHook: cascade failed for factSheet={} trigger={}: {}",
                        factSheetId, logLabel, e.getMessage(), e);
            } finally {
                // Release the coalescing gate so the next mutation can queue a fresh cascade.
                pending.set(false);
                // Clear the stale flag ONLY on a successful cascade. A failed reground leaves
                // the KB stale, so the indicator must stay true (the next mutation/crawl retries)
                // rather than falsely reporting stale data as current.
                if (succeeded && kbGroundingService != null) {
                    kbGroundingService.clearStale(factSheetId);
                }
            }
        });
    }

    // ── GroundingResetPort implementation ────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Drops the cached KB state for {@code factSheetId} (via {@link KbGroundingService#resetState})
     * so stale in-memory facts are evicted, then schedules a full re-ground. Both operations
     * are safe to call from any thread; the cascade itself is asynchronous.</p>
     */
    @Override
    public void invalidateAndReground(long factSheetId, String trigger) {
        if (kbGroundingService != null) {
            kbGroundingService.resetState(factSheetId);
        }
        schedule(factSheetId, trigger, GroundingProgressEvent.TRIGGER_CASCADE);
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
