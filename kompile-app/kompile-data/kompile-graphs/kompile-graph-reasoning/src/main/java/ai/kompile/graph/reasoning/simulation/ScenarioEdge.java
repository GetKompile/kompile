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
package ai.kompile.graph.reasoning.simulation;

import java.util.Map;
import java.util.Objects;

/**
 * One observed relation emitted by a {@link GraphScenario}, store-agnostic: the simulation runner
 * maps it onto the live store's edge representation (semantic relation label + observed provenance
 * + confidence-stamped weight), mirroring the crawl extractors' conventions.
 *
 * @param sourceKey    {@link ScenarioNode#key()} of the subject
 * @param targetKey    {@link ScenarioNode#key()} of the object
 * @param relationType semantic relation label in UPPER_SNAKE (e.g. {@code "WORKS_FOR"}); becomes
 *                     the edge's relation label, and therefore the predicate name the projector /
 *                     PSL program reasons in
 * @param confidence   observation confidence in [0,1]; deliberately varied (never pinned to 1.0,
 *                     which would pin the PSL gradient)
 * @param metadata     extra edge attributes; may be empty, never null after canonicalization
 * @param noise        true when this edge is deliberately planted corruption (spurious or
 *                     contradictory). Noise edges are also listed in
 *                     {@link GroundTruthManifest#corruptedEdgeKeys()}
 */
public record ScenarioEdge(
        String sourceKey,
        String targetKey,
        String relationType,
        double confidence,
        Map<String, Object> metadata,
        boolean noise) {

    public ScenarioEdge {
        Objects.requireNonNull(sourceKey, "sourceKey must not be null");
        Objects.requireNonNull(targetKey, "targetKey must not be null");
        Objects.requireNonNull(relationType, "relationType must not be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        }
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience factory for a clean (non-noise) observed edge. */
    public static ScenarioEdge of(String sourceKey, String targetKey, String relationType, double confidence) {
        return new ScenarioEdge(sourceKey, targetKey, relationType, confidence, Map.of(), false);
    }
}
