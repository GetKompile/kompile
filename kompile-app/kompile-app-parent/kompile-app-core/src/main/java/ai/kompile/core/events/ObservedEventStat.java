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
package ai.kompile.core.events;

import java.time.Instant;

/**
 * Immutable summary of an observed event's empirical (Beta-Binomial) statistics.
 *
 * <p>Surfaced from the {@code kompile-event-observation} module to downstream consumers
 * (e.g. the Bayesian/MEBN attribution layer, process attribution) without coupling them to
 * that module's implementation. The probability is the posterior mean of a Beta-Binomial
 * model maintained over real observations across crawls.</p>
 *
 * @param eventKey         stable identity of the event (entity node id, connection triple, or process step)
 * @param eventType        ENTITY_OCCURRENCE | CONNECTION_OCCURRENCE | PROCESS_STEP_OCCURRENCE | USER_DEFINED
 * @param probability      posterior mean P(event) = alpha / (alpha + beta), in [0,1]
 * @param occurrences      total observed occurrences accumulated (post-decay)
 * @param opportunities    total observation opportunities accumulated (post-decay)
 * @param evidenceStrength alpha + beta — total pseudo-count evidence behind the estimate
 * @param credibleLow      lower bound of the ~95% credible interval
 * @param credibleHigh     upper bound of the ~95% credible interval
 * @param lastObservedAt   when this event was most recently observed (nullable)
 */
public record ObservedEventStat(
        String eventKey,
        String eventType,
        double probability,
        long occurrences,
        long opportunities,
        double evidenceStrength,
        double credibleLow,
        double credibleHigh,
        Instant lastObservedAt) {
}
