/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.resolution;

import java.util.Optional;

/**
 * Pluggable probabilistic scoring for entity resolution.
 *
 * <p>Implementations compute P(isSameEntity = TRUE | observed signals) using
 * probabilistic inference (e.g., MEBN) instead of hard thresholds. When
 * injected into {@link GraphCompactionService}, the probabilistic score
 * is used as an additional signal alongside Levenshtein, embedding cosine,
 * and attribute-behavior scoring.</p>
 *
 * <p>This interface lives in {@code kompile-knowledge-graph} to avoid a
 * circular dependency: the MEBN-based implementation lives in
 * {@code kompile-event-attribution} and implements this interface.</p>
 */
public interface ProbabilisticScorer {

    /**
     * Compute the posterior probability that two entities refer to the same
     * real-world object.
     *
     * @param nodeIdA        first entity's KG node ID
     * @param nodeIdB        second entity's KG node ID
     * @param nameSimilarity Levenshtein similarity of normalized titles (0.0–1.0)
     * @param propertyScore  attribute/property overlap score (0.0–1.0)
     * @param typeCompatible whether entity types are compatible
     * @return the posterior P(isSameEntity = TRUE | signals), or empty if unavailable
     */
    Optional<Double> score(String nodeIdA, String nodeIdB,
                           double nameSimilarity, double propertyScore,
                           boolean typeCompatible);
}
