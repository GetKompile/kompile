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

import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Facade that runs a configurable combination of pruning policies over a {@link ReasoningGraph}
 * and merges their results into a single {@link PruneResult}.
 *
 * <p>Each policy is evaluated independently; the results are merged via
 * {@link PruneResult#merge(PruneResult)} so that if the same entity or relation is selected
 * by multiple policies, it appears once with the reason from the last policy that selected it.</p>
 *
 * <p>The pruner itself performs no store access or deletion — it is a pure coordinator of pure
 * decision functions.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * GraphPruner pruner = GraphPruner.builder()
 *         .orphan()
 *         .confidence(0.3, 0.2)
 *         .component(3)
 *         .staleness(Instant.now().minus(Duration.ofDays(30)))
 *         .build();
 *
 * PruneResult result = pruner.evaluate(reasoningGraph);
 * // apply result.entityIds() and result.relationIds() via the store API
 * }</pre>
 */
public final class GraphPruner {

    private static final Logger log = LoggerFactory.getLogger(GraphPruner.class);

    /** The ordered list of policies to run. */
    private final List<PolicyEntry> policies;

    private GraphPruner(List<PolicyEntry> policies) {
        this.policies = List.copyOf(policies);
    }

    /**
     * Evaluate all configured policies over {@code graph} and return the merged result.
     *
     * @param graph the graph to inspect; must not be {@code null}
     * @return the merged {@link PruneResult} from all policies
     */
    public PruneResult evaluate(ReasoningGraph graph) {
        PruneResult merged = PruneResult.empty();
        for (PolicyEntry entry : policies) {
            PruneResult partial = entry.evaluate(graph);
            log.debug("GraphPruner policy '{}': selected {} entities, {} relations",
                    entry.name(), partial.entityIds().size(), partial.relationIds().size());
            merged = merged.merge(partial);
        }
        log.debug("GraphPruner: total selected {} entities, {} relations",
                merged.entityIds().size(), merged.relationIds().size());
        return merged;
    }

    /** Start building a {@link GraphPruner}. */
    public static Builder builder() {
        return new Builder();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal wrapper
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface PolicyEvaluator {
        PruneResult evaluate(ReasoningGraph graph);
    }

    private static final class PolicyEntry {
        private final String name;
        private final PolicyEvaluator evaluator;

        PolicyEntry(String name, PolicyEvaluator evaluator) {
            this.name = name;
            this.evaluator = evaluator;
        }

        String name() { return name; }
        PruneResult evaluate(ReasoningGraph graph) { return evaluator.evaluate(graph); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builder
    // ─────────────────────────────────────────────────────────────────────────

    /** Fluent builder for {@link GraphPruner}. */
    public static final class Builder {

        private final List<PolicyEntry> policies = new ArrayList<>();

        private Builder() {}

        /** Add an {@link OrphanPruningPolicy} (no configuration needed). */
        public Builder orphan() {
            OrphanPruningPolicy policy = new OrphanPruningPolicy();
            policies.add(new PolicyEntry("orphan", policy::evaluate));
            return this;
        }

        /**
         * Add a {@link ConfidencePruningPolicy} with independent entity and relation thresholds.
         *
         * @param minEntityConfidence   minimum entity confidence to keep (exclusive lower bound)
         * @param minRelationConfidence minimum relation confidence to keep (exclusive lower bound)
         */
        public Builder confidence(double minEntityConfidence, double minRelationConfidence) {
            ConfidencePruningPolicy policy = new ConfidencePruningPolicy(minEntityConfidence, minRelationConfidence);
            policies.add(new PolicyEntry("confidence", policy::evaluate));
            return this;
        }

        /**
         * Add a {@link ConfidencePruningPolicy} with the same threshold for entities and relations.
         *
         * @param minConfidence minimum confidence to keep
         */
        public Builder confidence(double minConfidence) {
            return confidence(minConfidence, minConfidence);
        }

        /**
         * Add a {@link ComponentPruningPolicy}.
         *
         * @param minComponentSize components smaller than this are selected for pruning
         */
        public Builder component(int minComponentSize) {
            ComponentPruningPolicy policy = new ComponentPruningPolicy(minComponentSize);
            policies.add(new PolicyEntry("component(min=" + minComponentSize + ")", policy::evaluate));
            return this;
        }

        /**
         * Add a {@link StalenessPruningPolicy}.
         *
         * @param cutoff entities timestamped before this instant are selected
         */
        public Builder staleness(Instant cutoff) {
            StalenessPruningPolicy policy = new StalenessPruningPolicy(cutoff);
            policies.add(new PolicyEntry("staleness(cutoff=" + cutoff + ")", policy::evaluate));
            return this;
        }

        /** Build the {@link GraphPruner}. */
        public GraphPruner build() {
            return new GraphPruner(policies);
        }
    }
}
