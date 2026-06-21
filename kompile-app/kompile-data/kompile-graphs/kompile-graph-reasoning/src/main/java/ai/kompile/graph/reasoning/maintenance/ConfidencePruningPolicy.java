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
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identifies entities and/or relations whose confidence scores fall below configurable thresholds.
 *
 * <p>This is a pure decision function operating solely over the {@link ReasoningGraph}: no store
 * access, no deletion, no side effects. The calling module retains responsibility for deletion.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * ConfidencePruningPolicy policy = new ConfidencePruningPolicy(0.3, 0.2);
 * PruneResult result = policy.evaluate(graph);
 * // apply result.entityIds() and result.relationIds() via the store's deletion API
 * }</pre>
 */
public final class ConfidencePruningPolicy {

    private static final Logger log = LoggerFactory.getLogger(ConfidencePruningPolicy.class);

    /** Entities with confidence strictly below this value are selected. */
    private final double minEntityConfidence;

    /** Relations with confidence strictly below this value are selected. */
    private final double minRelationConfidence;

    /**
     * Construct a policy with the given thresholds.
     *
     * @param minEntityConfidence   entities with confidence below this are selected (inclusive lower
     *                              bound means entities AT exactly this value are kept)
     * @param minRelationConfidence relations with confidence below this are selected
     */
    public ConfidencePruningPolicy(double minEntityConfidence, double minRelationConfidence) {
        this.minEntityConfidence = minEntityConfidence;
        this.minRelationConfidence = minRelationConfidence;
    }

    /** Convenience: same threshold for both entities and relations. */
    public ConfidencePruningPolicy(double minConfidence) {
        this(minConfidence, minConfidence);
    }

    /**
     * Evaluate the graph and return the ids of low-confidence entities and relations.
     *
     * @param graph the graph to inspect; must not be {@code null}
     * @return a {@link PruneResult} listing low-confidence entity and relation ids
     */
    public PruneResult evaluate(ReasoningGraph graph) {
        PruneResult.Builder builder = PruneResult.builder();
        int entityCount = 0, entitySelected = 0;
        int relationCount = 0, relationSelected = 0;

        for (GraphEntity entity : graph.entities()) {
            entityCount++;
            if (entity.confidence() < minEntityConfidence) {
                entitySelected++;
                builder.addEntity(entity.id(),
                        "confidence=" + entity.confidence() + " < threshold=" + minEntityConfidence);
            }
        }

        for (GraphRelation relation : graph.relations()) {
            relationCount++;
            if (relation.confidence() < minRelationConfidence) {
                relationSelected++;
                builder.addRelation(relation.id(),
                        "confidence=" + relation.confidence() + " < threshold=" + minRelationConfidence);
            }
        }

        log.debug("ConfidencePruningPolicy: selected {}/{} entities, {}/{} relations",
                entitySelected, entityCount, relationSelected, relationCount);
        return builder.build();
    }

    public double minEntityConfidence() {
        return minEntityConfidence;
    }

    public double minRelationConfidence() {
        return minRelationConfidence;
    }
}
