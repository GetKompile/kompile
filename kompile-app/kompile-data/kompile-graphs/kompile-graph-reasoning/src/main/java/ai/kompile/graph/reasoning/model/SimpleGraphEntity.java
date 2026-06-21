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
 * Immutable, dependency-free {@link GraphEntity} value. Adapters typically build these through
 * {@link GraphEntity#builder(String)}; the {@code of(...)} factories cover the common simple cases.
 *
 * <p>Identity is {@link #id()} — equality/hashing are by record component and not meant for
 * structural comparison of the embedding array; callers key entities by id.</p>
 */
public record SimpleGraphEntity(
        String id,
        String type,
        String label,
        double weight,
        double confidence,
        Set<String> tags,
        double[] embedding,
        Instant timestamp,
        Map<String, Object> attributes) implements GraphEntity {

    public SimpleGraphEntity {
        Objects.requireNonNull(id, "id");
        type = type == null ? "" : type;
        label = label == null ? "" : label;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Minimal entity: id only, empty type/label, unit weight/confidence, no tags/embedding/time. */
    public static SimpleGraphEntity of(String id) {
        return new SimpleGraphEntity(id, "", "", 1.0, 1.0, Set.of(), null, null, Map.of());
    }

    /** Typed, labelled entity with unit weight/confidence and no tags/embedding/time. */
    public static SimpleGraphEntity of(String id, String type, String label) {
        return new SimpleGraphEntity(id, type, label, 1.0, 1.0, Set.of(), null, null, Map.of());
    }

    /** Typed, labelled, weighted entity (confidence defaults to the weight). */
    public static SimpleGraphEntity of(String id, String type, String label, double weight) {
        return new SimpleGraphEntity(id, type, label, weight, weight, Set.of(), null, null, Map.of());
    }
}
