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
package ai.kompile.graph.reasoning.maintenance;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

/**
 * Identifies entities whose {@link GraphEntity#timestamp() timestamp} is non-null and strictly
 * before a caller-supplied cutoff instant.
 *
 * <p>Entities with a {@code null} timestamp are considered timeless and are never selected.
 * This mirrors the TTL sweep in the knowledge-graph module: the caller determines the cutoff
 * (typically {@code Instant.now()} minus a TTL period) and passes it in — this library does
 * NOT call {@code Instant.now()} internally, keeping the policy pure and deterministic in
 * tests.</p>
 *
 * <p>This is a pure decision function: no store access, no deletion, no side effects.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * Instant cutoff = Instant.now().minus(Duration.ofDays(30));
 * StalenessPruningPolicy policy = new StalenessPruningPolicy(cutoff);
 * PruneResult result = policy.evaluate(graph);
 * // apply result.entityIds() via the store's deletion API
 * }</pre>
 */
public final class StalenessPruningPolicy {

    private static final Logger log = LoggerFactory.getLogger(StalenessPruningPolicy.class);

    /**
     * Entities whose timestamp is strictly before this instant are selected.
     * Must not be {@code null}.
     */
    private final Instant cutoff;

    /**
     * Construct a policy with the given staleness cutoff.
     *
     * @param cutoff entities timestamped before this instant are selected; must not be {@code null}
     */
    public StalenessPruningPolicy(Instant cutoff) {
        this.cutoff = Objects.requireNonNull(cutoff, "cutoff must not be null");
    }

    /**
     * Evaluate the graph and return ids of entities whose timestamp is before the cutoff.
     *
     * <p>Entities with no timestamp ({@link GraphEntity#timestamp()} returns {@code null}) are
     * never selected — they are treated as perpetually valid.</p>
     *
     * @param graph the graph to inspect; must not be {@code null}
     * @return a {@link PruneResult} listing stale entity ids
     */
    public PruneResult evaluate(ReasoningGraph graph) {
        PruneResult.Builder builder = PruneResult.builder();
        int total = 0;
        int selected = 0;

        for (GraphEntity entity : graph.entities()) {
            total++;
            Instant ts = entity.timestamp();
            if (ts != null && ts.isBefore(cutoff)) {
                selected++;
                builder.addEntity(entity.id(),
                        "stale: timestamp=" + ts + " is before cutoff=" + cutoff);
            }
        }

        log.debug("StalenessPruningPolicy: {}/{} entities are stale (cutoff={})", selected, total, cutoff);
        return builder.build();
    }

    /** The cutoff instant used by this policy. */
    public Instant cutoff() {
        return cutoff;
    }
}
