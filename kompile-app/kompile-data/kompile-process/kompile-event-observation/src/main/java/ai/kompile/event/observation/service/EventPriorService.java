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
package ai.kompile.event.observation.service;

import ai.kompile.core.events.ObservedEventStat;
import ai.kompile.event.observation.config.EventObservationConfig;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.BetaPrior;
import ai.kompile.event.observation.domain.EventKeys;
import ai.kompile.event.observation.domain.EventObservationRecord;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import ai.kompile.event.observation.store.ObservedEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Maintains and queries the Beta-Binomial empirical priors.
 *
 * <p>{@link #recordObservation} is the single mutation path: it loads or creates the per-event
 * counter, time-decays it (so recent crawls dominate), applies the new Binomial observation, and
 * appends a ledger row. The query methods expose an empirical prior only once enough <em>observed</em>
 * evidence (beyond the configured pseudo-count prior) has accumulated.</p>
 */
@Service
public class EventPriorService {

    private static final Logger log = LoggerFactory.getLogger(EventPriorService.class);
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final ObservedEventStore store;
    private final EventObservationConfigService configService;

    /** Package-visible for tests to inject a fixed clock and exercise decay deterministically. */
    private Clock clock = Clock.systemDefaultZone();

    public EventPriorService(ObservedEventStore store, EventObservationConfigService configService) {
        this.store = store;
        this.configService = configService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── Mutation ────────────────────────────────────────────────────────────────

    /**
     * Apply one observation to the event identified by {@code identity.eventKey}, creating it from
     * the configured prior if new. {@code identity} supplies the type and identity fields
     * (subject/connection/factSheet); its Beta fields are ignored when the event already exists.
     */
    public ObservedEvent recordObservation(ObservedEvent identity, long occurrences, long opportunities,
                                            EventSource source, String crawlJobId) {
        EventObservationConfig cfg = configService.getConfig();
        LocalDateTime now = LocalDateTime.now(clock);
        String key = identity.getEventKey();

        ObservedEvent event = store.findByKey(key).orElse(null);
        if (event == null) {
            event = identity;
            event.setAlpha(cfg.priorAlpha());
            event.setBeta(cfg.priorBeta());
            event.setOccurrenceCount(0L);
            event.setOpportunityCount(0L);
            event.setFirstObservedAt(now);
        } else if (cfg.decayOnEachCrawl()) {
            applyDecay(event, cfg, now);
        }

        BetaPrior beta = new BetaPrior(positive(event.getAlpha(), cfg.priorAlpha()),
                positive(event.getBeta(), cfg.priorBeta()));
        beta.update(occurrences, opportunities);
        event.setAlpha(beta.alpha());
        event.setBeta(beta.beta());
        event.setOccurrenceCount(event.getOccurrenceCount() + Math.max(0, occurrences));
        event.setOpportunityCount(event.getOpportunityCount() + Math.max(0, opportunities));
        event.setLastObservedAt(now);

        ObservedEvent saved = store.save(event);

        store.appendRecord(EventObservationRecord.builder()
                .eventKey(key)
                .occurrences(Math.max(0, occurrences))
                .opportunities(Math.max(0, opportunities))
                .probabilityAt(saved.probability())
                .observedAt(now)
                .source(source)
                .crawlJobId(crawlJobId)
                .factSheetId(saved.getFactSheetId())
                .build());
        return saved;
    }

    /** Force time-decay of every event toward the prior (used by the explicit decay endpoint). */
    public int decayAll() {
        EventObservationConfig cfg = configService.getConfig();
        LocalDateTime now = LocalDateTime.now(clock);
        int n = 0;
        for (ObservedEvent e : store.findAll()) {
            applyDecay(e, cfg, now);
            store.save(e);
            n++;
        }
        log.info("Decayed {} observed-event priors", n);
        return n;
    }

    private void applyDecay(ObservedEvent e, EventObservationConfig cfg, LocalDateTime now) {
        LocalDateTime last = e.getLastDecayedAt() != null ? e.getLastDecayedAt() : e.getLastObservedAt();
        if (last == null) {
            e.setLastDecayedAt(now);
            return;
        }
        double elapsedDays = Duration.between(last, now).toMillis() / (double) MILLIS_PER_DAY;
        if (elapsedDays <= 0) {
            return;
        }
        double factor = BetaPrior.decayFactor(elapsedDays, cfg.halfLifeDays());
        BetaPrior beta = new BetaPrior(positive(e.getAlpha(), cfg.priorAlpha()), positive(e.getBeta(), cfg.priorBeta()));
        beta.decayToward(cfg.priorAlpha(), cfg.priorBeta(), factor);
        e.setAlpha(beta.alpha());
        e.setBeta(beta.beta());
        e.setLastDecayedAt(now);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public OptionalDouble getNodePrior(String nodeId) {
        return priorForKey(EventKeys.entity(nodeId));
    }

    public OptionalDouble getConnectionPrior(String sourceNodeId, String edgeType, String targetNodeId) {
        return priorForKey(EventKeys.connection(sourceNodeId, edgeType, targetNodeId));
    }

    private OptionalDouble priorForKey(String key) {
        EventObservationConfig cfg = configService.getConfig();
        if (!cfg.enabled()) {
            return OptionalDouble.empty();
        }
        Optional<ObservedEvent> e = store.findByKey(key);
        if (e.isPresent() && excessEvidence(e.get(), cfg) >= cfg.minEvidenceForPrior()) {
            return OptionalDouble.of(e.get().probability());
        }
        return OptionalDouble.empty();
    }

    public Optional<ObservedEventStat> getNodeStat(String nodeId) {
        return store.findByKey(EventKeys.entity(nodeId)).map(this::toStat);
    }

    public Optional<ObservedEventStat> getStatByKey(String eventKey) {
        return store.findByKey(eventKey).map(this::toStat);
    }

    public List<ObservedEvent> topEvents(Long factSheetId, EventType type, int limit) {
        return store.topEvents(factSheetId, type, limit);
    }

    public List<EventObservationRecord> history(String eventKey) {
        return store.history(eventKey);
    }

    public ObservedEventStat toStat(ObservedEvent e) {
        BetaPrior beta = new BetaPrior(positive(e.getAlpha(), 1.0), positive(e.getBeta(), 1.0));
        double[] ci = beta.credibleInterval95();
        return new ObservedEventStat(
                e.getEventKey(),
                e.getEventType() == null ? null : e.getEventType().name(),
                e.probability(),
                e.getOccurrenceCount(),
                e.getOpportunityCount(),
                e.evidenceStrength(),
                ci[0],
                ci[1],
                e.getLastObservedAt() == null ? null
                        : e.getLastObservedAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    /** Observed evidence beyond the prior pseudo-counts — gates whether a prior is exposed. */
    private double excessEvidence(ObservedEvent e, EventObservationConfig cfg) {
        return (e.getAlpha() + e.getBeta()) - (cfg.priorAlpha() + cfg.priorBeta());
    }

    private static double positive(double v, double fallback) {
        return (Double.isFinite(v) && v > 0.0) ? v : fallback;
    }
}
