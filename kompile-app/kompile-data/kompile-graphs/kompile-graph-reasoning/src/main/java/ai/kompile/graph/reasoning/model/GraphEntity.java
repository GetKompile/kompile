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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * A generic, store-agnostic entity (node) in a {@link ReasoningGraph}.
 *
 * <p>This is the unit the reasoning engines operate on. It deliberately carries no persistence,
 * framework, or store coupling — a JPA {@code GraphNode}, a vector-store row, a mined process
 * activity, or any other domain object becomes a {@code GraphEntity} through a thin adapter. The
 * engines (PSL, Bayesian, MEBN, causal, hybrid) never see the originating store.</p>
 *
 * <p>Beyond identity ({@link #id()}) and description ({@link #type()}/{@link #label()}), entities
 * carry the first-class properties the reasoners and the knowledge graph share:
 * {@link #weight()} and {@link #confidence()} (probabilistic reasoning), {@link #tags()}
 * (filtering / typed reasoning), {@link #embedding()} (semantic / hybrid reasoning), and
 * {@link #timestamp()} (event-time / temporal reasoning). Anything else lives in
 * {@link #attributes()} so the model stays small while remaining extensible.</p>
 */
public interface GraphEntity {

    /** Stable, unique identifier within its graph. Never {@code null}. */
    String id();

    /**
     * Coarse type/label of this entity (e.g. {@code "DOCUMENT"}, {@code "Person"}, an activity
     * name, ...). Used by the engines for typed reasoning and by MEBN as the entity type.
     * Never {@code null} (use {@code ""} when unknown).
     */
    String type();

    /**
     * All crisp type memberships known for this entity.
     *
     * <p>The first membership is always {@link #type()} when present. Additional memberships are
     * read from store-agnostic metadata conventions such as {@code additionalTypes},
     * {@code owlInferredTypes}, and deterministic/declared rows in {@code ontology.typeCandidates}.
     * Probabilistic neural/LLM candidates are deliberately not promoted to crisp memberships unless
     * they are explicitly marked asserted/declared/observed.</p>
     */
    default Set<String> typeMemberships() {
        LinkedHashSet<String> memberships = new LinkedHashSet<>();
        addTypeMembership(memberships, type());
        Map<String, Object> attrs = attributes();
        addTypeMemberships(memberships, attrs.get("additionalType"));
        addTypeMemberships(memberships, attrs.get("additional_type"));
        addTypeMemberships(memberships, attrs.get("additionalTypes"));
        addTypeMemberships(memberships, attrs.get("additional_types"));
        addTypeMemberships(memberships, attrs.get("entity_types"));
        addTypeMemberships(memberships, attrs.get("ontology.inferredTypes"));
        addTypeMemberships(memberships, attrs.get("owlInferredTypes"));
        addTypeMemberships(memberships, attrs.get("owl.inferredTypes"));
        addTypeMemberships(memberships, attrs.get("inferredTypes"));
        addTypeMemberships(memberships, attrs.get("inferred_types"));
        addTypeMemberships(memberships, attrs.get("typeClosure"));
        addAssertedTypeCandidates(memberships, attrs.get("ontology.typeCandidates"));
        addAssertedTypeCandidates(memberships, attrs.get("ontology.inferredTypeCandidates"));
        addAssertedTypeCandidates(memberships, attrs.get("typeCandidates"));
        addAssertedTypeCandidates(memberships, attrs.get("inferredTypeCandidates"));
        addAssertedTypeCandidates(memberships, attrs.get("type_candidates"));
        return Collections.unmodifiableSet(memberships);
    }

    /** Whether {@link #typeMemberships()} contains {@code typeName}, case-insensitively. */
    default boolean hasTypeMembership(String typeName) {
        if (typeName == null || typeName.isBlank()) return false;
        for (String membership : typeMemberships()) {
            if (typeName.equalsIgnoreCase(membership)) {
                return true;
            }
        }
        return false;
    }

    /** Human-readable label/title. Never {@code null} (use {@code ""} when unknown). */
    String label();

    /**
     * A scalar salience/prior for this entity in {@code [0, 1]} unless a specific engine
     * documents another range; defaults to {@code 1.0} when no prior is known.
     */
    double weight();

    /** Confidence in this entity's existence/identity, in {@code [0, 1]}; defaults to {@code 1.0}. */
    default double confidence() {
        return 1.0;
    }

    /** Free-form tags/labels for filtering and typed reasoning. Never {@code null} (may be empty). */
    default Set<String> tags() {
        return Set.of();
    }

    /** Whether this entity carries the given tag. */
    default boolean hasTag(String tag) {
        return tags().contains(tag);
    }

    /**
     * Dense vector embedding for semantic / hybrid reasoning, or {@code null} if this entity has no
     * embedding. The vector is shared (not copied) for efficiency — treat it as read-only.
     */
    default double[] embedding() {
        return null;
    }

    /** Whether a non-empty {@link #embedding()} is present. */
    default boolean hasEmbedding() {
        double[] e = embedding();
        return e != null && e.length > 0;
    }

    /**
     * When the event this entity represents occurred, or {@code null} if it is not time-stamped.
     * Enables temporal/causal ordering without the engines knowing the source's date type.
     */
    default Instant timestamp() {
        return null;
    }

    /** {@link #timestamp()} as an {@link Optional}. */
    default Optional<Instant> timestampOpt() {
        return Optional.ofNullable(timestamp());
    }

    /**
     * The valid-time interval over which this entity is considered valid in the world, or
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
     * Whether this entity is valid at instant {@code t}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@link #validTime()} is non-null, delegates to
     *       {@link TemporalInterval#contains(Instant)}.</li>
     *   <li>Otherwise falls back to {@link #timestamp()}: if a point timestamp exists, the entity
     *       is considered valid from that instant onward ({@code timestamp <= t}). If there is no
     *       temporal information at all, the entity is treated as always valid ({@code true}).</li>
     * </ol>
     *
     * @param t the instant to test (never {@code null})
     * @return {@code true} if this entity is valid at {@code t}
     */
    default boolean isValidAt(Instant t) {
        TemporalInterval vt = validTime();
        if (vt != null) {
            return vt.contains(t);
        }
        Instant ts = timestamp();
        // No interval and no timestamp ⇒ timeless entity, always valid
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

    /** Start a fluent builder for an entity with the given id. */
    static GraphEntityBuilder builder(String id) {
        return new GraphEntityBuilder(id);
    }

    private static void addTypeMemberships(LinkedHashSet<String> memberships, Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addTypeMemberships(memberships, item);
            }
            return;
        }
        if (raw instanceof String s && s.contains(",")) {
            for (String part : s.split(",")) {
                addTypeMembership(memberships, part);
            }
            return;
        }
        addTypeMembership(memberships, raw);
    }

    private static void addTypeMembership(LinkedHashSet<String> memberships, Object raw) {
        if (raw == null) return;
        String type = String.valueOf(raw).trim();
        if (!type.isEmpty()) {
            memberships.add(type);
        }
    }

    private static void addAssertedTypeCandidates(LinkedHashSet<String> memberships, Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addAssertedTypeCandidates(memberships, item);
            }
            return;
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> map = stringKeyMap(rawMap);
        Object directType = firstNonNull(map, "type", "candidateType", "typeName", "inferredType", "label", "iri");
        if (directType != null) {
            if (isAssertedTypeCandidate(map)) {
                addTypeMembership(memberships, directType);
            }
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> candidate = stringKeyMap(nested);
                candidate.putIfAbsent("type", entry.getKey());
                if (isAssertedTypeCandidate(candidate)) {
                    addTypeMembership(memberships, entry.getKey());
                }
            }
        }
    }

    private static boolean isAssertedTypeCandidate(Map<String, Object> candidate) {
        Object asserted = firstNonNull(candidate,
                "asserted", "declared", "observed", "isAsserted", "isDeclared", "isObserved");
        if (truthy(asserted)) return true;

        Object confidence = firstNonNull(candidate, "confidence", "score", "probability", "posterior", "truthValue");
        if (confidence instanceof Number number && number.doubleValue() < 0.999d) {
            return false;
        }

        Object source = firstNonNull(candidate, "source", "inferenceSource", "engine", "model");
        if (!(source instanceof String sourceName)) {
            return false;
        }
        String normalized = sourceName.trim().toLowerCase();
        return normalized.equals("owl-rl")
                || normalized.equals("owl-dl")
                || normalized.equals("ontology")
                || normalized.equals("schema")
                || normalized.equals("declared")
                || normalized.equals("observed")
                || normalized.equals("manual");
    }

    private static boolean truthy(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String normalized = s.trim().toLowerCase();
            return normalized.equals("true") || normalized.equals("yes") || normalized.equals("1");
        }
        return false;
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return converted;
    }

    private static Object firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
