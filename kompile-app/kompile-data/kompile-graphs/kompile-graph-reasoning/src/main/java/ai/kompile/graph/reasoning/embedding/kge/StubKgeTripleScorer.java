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
package ai.kompile.graph.reasoning.embedding.kge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic stub implementation of {@link KgeTripleScorer} for unit tests.
 *
 * <p>Scores are provided via a pre-loaded map from triple keys to plausibility values.
 * Any triple not in the map returns {@code 0.0}.  Use the builder API:</p>
 * <pre>
 *   KgeTripleScorer scorer = StubKgeTripleScorer.builder()
 *       .withScore("alice", "KNOWS", "bob", 0.9)
 *       .withScore("alice", "KNOWS", "carol", 0.3)
 *       .build();
 * </pre>
 *
 * <p>This scorer is intentionally infra-free: no ND4J, no Spring, no I/O.
 * It is suitable for offline unit tests and for seeding a {@link KgePslBulkObserver}
 * or {@link KgeOpinionStoreBridge} in test contexts.</p>
 */
public final class StubKgeTripleScorer implements KgeTripleScorer {

    private final Map<String, Double> scores;
    private final Set<String>         knownEntities;
    private final Set<String>         knownRelations;

    private StubKgeTripleScorer(Map<String, Double> scores,
                                 Set<String> knownEntities,
                                 Set<String> knownRelations) {
        this.scores        = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        this.knownEntities = Collections.unmodifiableSet(knownEntities);
        this.knownRelations = Collections.unmodifiableSet(knownRelations);
    }

    @Override
    public double scoreTriple(String headId, String relationType, String tailId) {
        return scores.getOrDefault(key(headId, relationType, tailId), 0.0);
    }

    @Override
    public boolean knows(String headId, String relationType, String tailId) {
        return knownEntities.contains(headId)
                && knownRelations.contains(relationType)
                && knownEntities.contains(tailId);
    }

    // ─── Static factory / builder ─────────────────────────────────────────────

    /** Obtain a new builder for constructing a {@link StubKgeTripleScorer}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link StubKgeTripleScorer}. */
    public static final class Builder {

        private final Map<String, Double> scores        = new LinkedHashMap<>();
        private final Set<String>         knownEntities = new LinkedHashSet<>();
        private final Set<String>         knownRelations = new LinkedHashSet<>();

        private Builder() {}

        /**
         * Register a specific triple → plausibility score.
         * Both {@code headId} and {@code tailId} are automatically added to the known-entity set;
         * {@code relationType} is added to the known-relation set.
         *
         * @param headId       head entity id
         * @param relationType relation type string
         * @param tailId       tail entity id
         * @param score        plausibility in {@code [0, 1]}
         * @return this builder (fluent)
         */
        public Builder withScore(String headId, String relationType, String tailId, double score) {
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be in [0, 1], got " + score);
            }
            scores.put(key(headId, relationType, tailId), score);
            knownEntities.add(headId);
            knownEntities.add(tailId);
            knownRelations.add(relationType);
            return this;
        }

        /**
         * Explicitly register an entity id as "known" even if no scores reference it.
         * This allows {@link #knows} to return {@code true} for triples where the score
         * defaults to {@code 0.0}.
         */
        public Builder withKnownEntity(String entityId) {
            knownEntities.add(entityId);
            return this;
        }

        /**
         * Explicitly register a relation type as "known".
         */
        public Builder withKnownRelation(String relationType) {
            knownRelations.add(relationType);
            return this;
        }

        /** Build the scorer. */
        public StubKgeTripleScorer build() {
            return new StubKgeTripleScorer(scores,
                    Collections.unmodifiableSet(new LinkedHashSet<>(knownEntities)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(knownRelations)));
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private static String key(String head, String rel, String tail) {
        return head + "\0" + rel + "\0" + tail;
    }
}
