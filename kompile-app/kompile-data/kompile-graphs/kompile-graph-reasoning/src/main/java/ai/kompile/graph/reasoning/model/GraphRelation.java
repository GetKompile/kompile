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
import java.util.Optional;
import java.util.Set;

/**
 * A generic, store-agnostic relation (edge) between two {@link GraphEntity entities} in a
 * {@link ReasoningGraph}.
 *
 * <p>Like {@link GraphEntity}, this is intentionally free of any persistence or framework coupling.
 * The {@link #type()} is a free-form relation label; the originating store's typed edge (e.g. a
 * knowledge-graph {@code EdgeType} or a directly-follows arc) is mapped onto it by an adapter.
 * Probabilistic reasoning reads {@link #weight()} (relation strength) and {@link #confidence()};
 * semantic/hybrid reasoning may read {@link #embedding()}; temporal reasoning reads
 * {@link #timestamp()}; {@link #tags()} support filtering and typed traversal.</p>
 */
public interface GraphRelation {

    /** Stable, unique identifier of this relation within its graph. Never {@code null}. */
    String id();

    /** Id of the source/from entity. Never {@code null}. */
    String sourceId();

    /** Id of the target/to entity. Never {@code null}. */
    String targetId();

    /**
     * Coarse type/label of the relation (e.g. {@code "CITATION"}, {@code "CAUSES"}, a
     * directly-follows label, ...). Never {@code null} (use {@code ""} when unknown).
     */
    String type();

    /**
     * Relation strength, conventionally in {@code [0, 1]}. For directed causal/dependency
     * relations this is the directed strength source&rarr;target.
     */
    double weight();

    /** Confidence in the relation's existence, in {@code [0, 1]}; defaults to {@code 1.0}. */
    double confidence();

    /** Whether this relation is directed ({@code source -> target}) or symmetric. */
    boolean directed();

    /** Free-form tags/labels for filtering and typed traversal. Never {@code null} (may be empty). */
    default Set<String> tags() {
        return Set.of();
    }

    /** Whether this relation carries the given tag. */
    default boolean hasTag(String tag) {
        return tags().contains(tag);
    }

    /**
     * Dense vector embedding of the relation (e.g. a KGE relation vector), or {@code null} if none.
     * Shared (not copied) for efficiency — treat it as read-only.
     */
    default double[] embedding() {
        return null;
    }

    /** Whether a non-empty {@link #embedding()} is present. */
    default boolean hasEmbedding() {
        double[] e = embedding();
        return e != null && e.length > 0;
    }

    /** When the relation/event occurred, or {@code null} if it is not time-stamped. */
    default Instant timestamp() {
        return null;
    }

    /** {@link #timestamp()} as an {@link Optional}. */
    default Optional<Instant> timestampOpt() {
        return Optional.ofNullable(timestamp());
    }

    /**
     * The valid-time interval over which this relation is considered valid in the world, or
     * {@code null} if no interval has been declared.
     *
     * <p>The default implementation reads the {@code "validFrom"} and {@code "validUntil"} keys
     * from {@link #attributes()} (both expected to be {@link String}s in ISO-8601 format or
     * {@code null}). Implementations that carry {@link TemporalInterval} directly may override
     * this method.</p>
     *
     * @return the valid-time interval, or {@code null} when no temporal bounds are declared
     */
    default TemporalInterval validTime() {
        Object from  = attributes().get("validFrom");
        Object until = attributes().get("validUntil");
        if (from == null && until == null) {
            return null;
        }
        Instant start = from  == null ? null : Instant.parse(String.valueOf(from));
        Instant end   = until == null ? null : Instant.parse(String.valueOf(until));
        return TemporalInterval.of(start, end);
    }

    /**
     * Whether this relation is valid at instant {@code t}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@link #validTime()} is non-null, delegates to
     *       {@link TemporalInterval#contains(Instant)}.</li>
     *   <li>Otherwise falls back to {@link #timestamp()} (if a point timestamp exists, the
     *       relation is considered valid from that instant onward). If there is no temporal
     *       information at all, the relation is treated as always valid ({@code true}).</li>
     * </ol>
     *
     * @param t the instant to test (never {@code null})
     * @return {@code true} if this relation is valid at {@code t}
     */
    default boolean isValidAt(Instant t) {
        TemporalInterval vt = validTime();
        if (vt != null) {
            return vt.contains(t);
        }
        Instant ts = timestamp();
        return ts == null || !ts.isAfter(t);
    }

    /** Arbitrary, immutable-from-the-engine's-view metadata. Never {@code null} (may be empty). */
    Map<String, Object> attributes();

    /** Convenience: the raw attribute value, if present. */
    default Optional<Object> attribute(String key) {
        return Optional.ofNullable(attributes().get(key));
    }

    /** Convenience: a numeric attribute, or {@code defaultValue} if absent/non-numeric. */
    default double doubleAttribute(String key, double defaultValue) {
        Object v = attributes().get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** Convenience: a string attribute, or {@code null} if absent. */
    default String stringAttribute(String key) {
        Object v = attributes().get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Start a fluent builder for a relation with the given id and endpoints. */
    static GraphRelationBuilder builder(String id, String sourceId, String targetId) {
        return new GraphRelationBuilder(id, sourceId, targetId);
    }
}
