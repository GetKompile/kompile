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
package ai.kompile.graph.reasoning.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, dependency-free {@link GraphRelation} value. Adapters typically build these through
 * {@link GraphRelation#builder(String, String, String)}; the {@code directed}/{@code undirected}
 * factories cover the common simple cases.
 */
public record SimpleGraphRelation(
        String id,
        String sourceId,
        String targetId,
        String type,
        double weight,
        double confidence,
        boolean directed,
        Set<String> tags,
        double[] embedding,
        Instant timestamp,
        Map<String, Object> attributes) implements GraphRelation {

    public SimpleGraphRelation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(targetId, "targetId");
        type = type == null ? "" : type;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Directed relation with unit confidence and no tags/embedding/time. */
    public static SimpleGraphRelation directed(String id, String sourceId, String targetId, String type, double weight) {
        return new SimpleGraphRelation(id, sourceId, targetId, type, weight, 1.0, true, Set.of(), null, null, Map.of());
    }

    /** Undirected relation with unit confidence and no tags/embedding/time. */
    public static SimpleGraphRelation undirected(String id, String sourceId, String targetId, String type, double weight) {
        return new SimpleGraphRelation(id, sourceId, targetId, type, weight, 1.0, false, Set.of(), null, null, Map.of());
    }
}
