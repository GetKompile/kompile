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
package ai.kompile.knowledgegraph.unified;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps canonical {@link GraphExtractionSchema.ExtractionResult} objects (the parsed output of LLM
 * or rule-based graph extraction) onto the infrastructure-free {@link UnifiedGraph} — headlessly:
 * no crawl pipeline, no matrix store, no Spring context.
 *
 * <p>The resulting graph is reasoning-ready ({@code UnifiedGraph} IS a {@code ReasoningGraph}):
 * hand it straight to the FOL / PSL / process-mining machinery, or
 * {@code UnifiedGraphBridge.importGraph} it into a live fact sheet.</p>
 *
 * <p>Mapping conventions:</p>
 * <ul>
 *   <li>entity {@code confidence} → entity {@code weight} AND {@code confidence} (Bayesian root
 *       priors read {@code weight()});</li>
 *   <li>entity {@code type} is normalized to the UPPER_SNAKE convention used by the extraction
 *       prompt ({@code "person"} → {@code "PERSON"}); relation types pass through untouched;</li>
 *   <li>{@code description}, {@code aliases} and every {@code properties} entry become entity /
 *       relation attributes; a relation's {@code occurredAt} is kept as an attribute and, when
 *       parseable, becomes the relation timestamp;</li>
 *   <li>results merge per chunk: re-applying an entity id merges attributes and aliases and keeps
 *       the highest confidence; identical {@code (type, source, target)} relations dedupe the same
 *       way instead of duplicating;</li>
 *   <li>a relation endpoint that no result declares is synthesized as a stub entity (marked
 *       {@link #ATTR_STUB}) so type atoms and traversals stay closed; a later chunk that declares
 *       the real entity upgrades the stub in place.</li>
 * </ul>
 */
public final class ExtractionToUnifiedGraph {

    /** Attribute holding an entity's extracted free-text description. */
    public static final String ATTR_DESCRIPTION = "description";

    /** Attribute holding the extracted alias list ({@code List<String>}). */
    public static final String ATTR_ALIASES = "aliases";

    /** Attribute holding a relation's raw {@code occurredAt} string as extracted. */
    public static final String ATTR_OCCURRED_AT = "occurredAt";

    /** Marker attribute on entities synthesized for undeclared relation endpoints. */
    public static final String ATTR_STUB = "_extractionStub";

    /** Graph meta key: distinct source document ids seen across applied results. */
    public static final String META_SOURCE_DOCUMENT_IDS = "sourceDocumentIds";

    /** Graph meta key: distinct extraction model names seen across applied results. */
    public static final String META_EXTRACTION_MODELS = "extractionModels";

    /** Type assigned to synthesized stub endpoints until a chunk declares the real entity. */
    public static final String STUB_TYPE = "ENTITY";

    private ExtractionToUnifiedGraph() {
    }

    /** Project a single extraction result into a fresh {@link UnifiedGraph}. */
    public static UnifiedGraph toGraph(ExtractionResult result) {
        return apply(new UnifiedGraph(), result);
    }

    /** Project and merge several per-chunk extraction results into a fresh {@link UnifiedGraph}. */
    public static UnifiedGraph toGraph(Collection<ExtractionResult> results) {
        UnifiedGraph graph = new UnifiedGraph();
        if (results != null) {
            for (ExtractionResult result : results) {
                apply(graph, result);
            }
        }
        return graph;
    }

    /**
     * Apply one extraction result onto an existing graph, merging with whatever is already there.
     * Returns the same graph for chaining.
     */
    public static UnifiedGraph apply(UnifiedGraph graph, ExtractionResult result) {
        Objects.requireNonNull(graph, "graph");
        if (result == null) {
            return graph;
        }
        for (ExtractedEntity entity : result.entities()) {
            if (entity == null || isBlank(entity.id())) {
                continue;
            }
            mergeEntity(graph, entity);
        }
        for (ExtractedRelation relation : result.relations()) {
            if (relation == null || isBlank(relation.source()) || isBlank(relation.target())
                    || isBlank(relation.type())) {
                continue;
            }
            ensureEndpoint(graph, relation.source());
            ensureEndpoint(graph, relation.target());
            mergeRelation(graph, relation);
        }
        recordMetadata(graph, result);
        return graph;
    }

    /**
     * Normalize an extracted entity type to the UPPER_SNAKE convention ({@code "email message"} →
     * {@code "EMAIL_MESSAGE"}). Blank types resolve to {@link #STUB_TYPE}.
     */
    public static String normalizeEntityType(String type) {
        if (isBlank(type)) {
            return STUB_TYPE;
        }
        String normalized = type.trim()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? STUB_TYPE : normalized;
    }

    // ── entities ────────────────────────────────────────────────────────────────

    private static void mergeEntity(UnifiedGraph graph, ExtractedEntity entity) {
        String id = entity.id().trim();
        double confidence = clamp01(entity.confidence() != null
                ? entity.confidence() : GraphExtractionSchema.DEFAULT_ENTITY_CONFIDENCE);
        String type = normalizeEntityType(entity.type());
        String label = isBlank(entity.name()) ? id : entity.name().trim();

        Optional<GraphEntity> existing = graph.entity(id);
        if (existing.isEmpty()) {
            graph.addEntity(GraphEntity.builder(id)
                    .type(type)
                    .label(label)
                    .weight(confidence)
                    .confidence(confidence)
                    .attributes(entityAttributes(entity, Map.of()))
                    .build());
            return;
        }

        GraphEntity old = existing.get();
        boolean oldIsStub = Boolean.TRUE.equals(old.attributes().get(ATTR_STUB));
        double mergedConfidence = Math.max(old.confidence(), confidence);
        String mergedType = oldIsStub || STUB_TYPE.equals(old.type()) ? type : old.type();
        String mergedLabel = oldIsStub || isBlank(old.label()) || old.label().equals(old.id())
                ? label : old.label();

        Map<String, Object> attributes = entityAttributes(entity, old.attributes());
        attributes.remove(ATTR_STUB);

        var builder = GraphEntity.builder(id)
                .type(mergedType)
                .label(mergedLabel)
                .weight(Math.max(old.weight(), confidence))
                .confidence(mergedConfidence)
                .tags(old.tags())
                .attributes(attributes);
        if (old.timestamp() != null) {
            builder.timestamp(old.timestamp());
        }
        if (old.hasEmbedding()) {
            builder.embedding(old.embedding());
        }
        graph.addEntity(builder.build());
    }

    /** Union of existing attributes, extracted properties, description and aliases. */
    private static Map<String, Object> entityAttributes(ExtractedEntity entity,
                                                        Map<String, Object> existing) {
        Map<String, Object> attributes = new LinkedHashMap<>(existing);
        entity.properties().forEach((key, value) -> {
            if (!isBlank(key) && value != null) {
                attributes.put(key, value);
            }
        });
        if (!isBlank(entity.description())) {
            Object prior = attributes.get(ATTR_DESCRIPTION);
            if (!(prior instanceof String s) || s.length() < entity.description().length()) {
                attributes.put(ATTR_DESCRIPTION, entity.description());
            }
        }
        List<String> aliases = mergedAliases(existing.get(ATTR_ALIASES), entity.aliases());
        if (!aliases.isEmpty()) {
            attributes.put(ATTR_ALIASES, aliases);
        }
        return attributes;
    }

    private static List<String> mergedAliases(Object existing, List<String> incoming) {
        LinkedHashSet<String> union = new LinkedHashSet<>();
        if (existing instanceof Collection<?> collection) {
            for (Object alias : collection) {
                if (alias instanceof String s && !s.isBlank()) {
                    union.add(s);
                }
            }
        }
        if (incoming != null) {
            for (String alias : incoming) {
                if (!isBlank(alias)) {
                    union.add(alias);
                }
            }
        }
        return new ArrayList<>(union);
    }

    private static void ensureEndpoint(UnifiedGraph graph, String entityId) {
        String id = entityId.trim();
        if (graph.entity(id).isPresent()) {
            return;
        }
        graph.addEntity(GraphEntity.builder(id)
                .type(STUB_TYPE)
                .label(id)
                .weight(GraphExtractionSchema.DEFAULT_ENTITY_CONFIDENCE)
                .confidence(GraphExtractionSchema.DEFAULT_ENTITY_CONFIDENCE)
                .attribute(ATTR_STUB, Boolean.TRUE)
                .build());
    }

    // ── relations ───────────────────────────────────────────────────────────────

    private static void mergeRelation(UnifiedGraph graph, ExtractedRelation relation) {
        String source = relation.source().trim();
        String target = relation.target().trim();
        String type = relation.type().trim();
        double confidence = clamp01(relation.confidence() != null
                ? relation.confidence() : GraphExtractionSchema.DEFAULT_RELATION_CONFIDENCE);

        GraphRelation existing = null;
        for (GraphRelation candidate : graph.outgoing(source)) {
            if (candidate.type().equals(type) && candidate.targetId().equals(target)) {
                existing = candidate;
                break;
            }
        }

        Map<String, Object> attributes = existing != null
                ? new LinkedHashMap<>(existing.attributes()) : new LinkedHashMap<>();
        relation.properties().forEach((key, value) -> {
            if (!isBlank(key) && value != null) {
                attributes.put(key, value);
            }
        });
        if (!isBlank(relation.description())) {
            attributes.putIfAbsent(ATTR_DESCRIPTION, relation.description());
        }
        if (!isBlank(relation.occurredAt())) {
            attributes.put(ATTR_OCCURRED_AT, relation.occurredAt());
        }

        double mergedConfidence = existing != null
                ? Math.max(existing.confidence(), confidence) : confidence;
        Instant timestamp = parseOccurredAt(relation.occurredAt());
        if (timestamp == null && existing != null) {
            timestamp = existing.timestamp();
        }

        if (existing != null) {
            graph.removeRelationById(existing.id());
        }
        var builder = GraphRelation.builder(relationId(type, source, target), source, target)
                .type(type)
                .weight(mergedConfidence)
                .confidence(mergedConfidence)
                .directed(true)
                .attributes(attributes);
        if (existing != null) {
            builder.tags(existing.tags());
        }
        if (timestamp != null) {
            builder.timestamp(timestamp);
        }
        graph.addRelation(builder.build());
    }

    /** Deterministic relation id so per-chunk re-application stays idempotent. */
    public static String relationId(String type, String source, String target) {
        return "rel:" + type + ":" + source + "->" + target;
    }

    /** Best-effort ISO parse of the extracted {@code occurredAt}; null when unparseable. */
    static Instant parseOccurredAt(String occurredAt) {
        if (isBlank(occurredAt)) {
            return null;
        }
        String raw = occurredAt.trim();
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // fall through to the local formats
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ── metadata ────────────────────────────────────────────────────────────────

    private static void recordMetadata(UnifiedGraph graph, ExtractionResult result) {
        if (result.metadata() == null) {
            return;
        }
        appendMeta(graph, META_SOURCE_DOCUMENT_IDS, result.metadata().sourceDocumentId());
        appendMeta(graph, META_EXTRACTION_MODELS, result.metadata().extractionModel());
    }

    private static void appendMeta(UnifiedGraph graph, String key, String value) {
        if (isBlank(value)) {
            return;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Object prior = graph.meta().get(key);
        if (prior instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String s) {
                    values.add(s);
                }
            }
        }
        values.add(value);
        graph.meta(key, new ArrayList<>(values));
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
