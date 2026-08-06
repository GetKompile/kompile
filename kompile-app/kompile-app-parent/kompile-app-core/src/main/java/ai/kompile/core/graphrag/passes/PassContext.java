/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.SourceSpan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Version-pinned context for one decomposed extraction pass.
 *
 * <p>Every pass runs against a frozen view: the same chunk text, the same graph revision, the
 * same schema version. Two passes over the same object therefore see the same world, so their
 * outputs can be compared, cached and replayed. Downstream decision prompts expose
 * {@link #pinBlock()}; proposition atomization keeps those pins engine-owned so metadata cannot
 * be mistaken for source facts.</p>
 *
 * @param chunkId       identifier of the text chunk being processed
 * @param documentId    identifier of the source document
 * @param sourceText    the exact chunk text; evidence spans are validated against this string
 * @param graphId       graph revision the candidate context was read from
 * @param parentGraphId parent revision, when the graph is a derived snapshot
 * @param schemaVersion extraction schema version in force for this run
 * @param partitionId   entity-partition this chunk was scheduled under, when partitioned
 * @param modelId       model the pass is dispatched to, recorded for provenance
 * @param pins          additional pinned versions (rule set, ontology, profile catalog, ...)
 * @param graphContext  bounded existing graph neighborhood; identity context, never source evidence
 * @param conceptHints  bounded pre-pass concepts aligned back to source before model exposure
 * @param sourceSpans   engine-owned, chunk-relative source event boundaries from upstream parsing
 * @param schemaEntityTypes bounded entity-type vocabulary declared by the active graph schema
 */
public record PassContext(
        String chunkId,
        String documentId,
        String sourceText,
        String graphId,
        String parentGraphId,
        String schemaVersion,
        String partitionId,
        String modelId,
        Map<String, String> pins,
        String graphContext,
        List<ConceptHint> conceptHints,
        List<SourceSpan> sourceSpans,
        List<String> schemaEntityTypes) {

    public PassContext {
        sourceText = sourceText == null ? "" : sourceText;
        pins = pins == null ? Map.of() : Map.copyOf(pins);
        graphContext = bound(graphContext, graphContextCharLimit(pins));
        conceptHints = conceptHints == null ? List.of() : conceptHints.stream()
                .filter(hint -> hint != null && hint.term() != null && !hint.term().isBlank())
                .limit(conceptHintLimit(pins))
                .toList();
        sourceSpans = sourceSpans == null ? List.of() : sourceSpans.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        schemaEntityTypes = schemaEntityTypes == null ? List.of() : schemaEntityTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(String::strip)
                .distinct()
                .limit(64)
                .toList();
    }

    /** Source-compatible constructor for callers predating source span propagation. */
    public PassContext(String chunkId, String documentId, String sourceText, String graphId,
                       String parentGraphId, String schemaVersion, String partitionId,
                       String modelId, Map<String, String> pins, String graphContext,
                       List<ConceptHint> conceptHints) {
        this(chunkId, documentId, sourceText, graphId, parentGraphId, schemaVersion, partitionId,
                modelId, pins, graphContext, conceptHints, List.of(), List.of());
    }

    /** Source-compatible constructor for callers predating schema entity-type propagation. */
    public PassContext(String chunkId, String documentId, String sourceText, String graphId,
                       String parentGraphId, String schemaVersion, String partitionId,
                       String modelId, Map<String, String> pins, String graphContext,
                       List<ConceptHint> conceptHints, List<SourceSpan> sourceSpans) {
        this(chunkId, documentId, sourceText, graphId, parentGraphId, schemaVersion, partitionId,
                modelId, pins, graphContext, conceptHints, sourceSpans, List.of());
    }

    /** Minimal context for a chunk with no graph pin — used by tests and by first-pass crawls. */
    public static PassContext forChunk(String chunkId, String documentId, String sourceText) {
        return new PassContext(chunkId, documentId, sourceText, null, null, null, null, null,
                Map.of(), null, List.of(), List.of(), List.of());
    }

    /** Returns a copy with the graph revision pinned. */
    public PassContext withGraph(String graphId, String parentGraphId) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, graphContext, conceptHints, sourceSpans,
                schemaEntityTypes);
    }

    /** Returns a copy with the schema version and model recorded. */
    public PassContext withSchema(String schemaVersion, String modelId) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, graphContext, conceptHints, sourceSpans,
                schemaEntityTypes);
    }

    /** Returns a copy pinned to the entity partition that scheduled this chunk. */
    public PassContext withPartition(String partitionId) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, graphContext, conceptHints, sourceSpans,
                schemaEntityTypes);
    }

    /** Returns a copy carrying an additional named version pin. */
    public PassContext withPin(String name, String value) {
        if (name == null || name.isBlank() || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(pins);
        merged.put(name, value);
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, merged, graphContext, conceptHints, sourceSpans,
                schemaEntityTypes);
    }

    /** Returns a copy carrying bounded existing graph context for identity reuse. */
    public PassContext withGraphContext(String context) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, context, conceptHints, sourceSpans,
                schemaEntityTypes);
    }

    /** Returns a copy carrying deterministic or corpus-derived concept coverage hints. */
    public PassContext withConceptHints(List<ConceptHint> hints) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, graphContext, hints, sourceSpans,
                schemaEntityTypes);
    }

    /** Returns a copy carrying an upstream, engine-owned source event plan. */
    public PassContext withSourceSpans(List<SourceSpan> spans) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, graphContext, conceptHints, spans,
                schemaEntityTypes);
    }

    /** Returns a copy carrying the active schema's bounded entity-type vocabulary. */
    public PassContext withSchemaEntityTypes(List<String> entityTypes) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins, graphContext, conceptHints, sourceSpans,
                entityTypes);
    }

    /**
     * Human-readable pin block embedded in every pass prompt. Present so the model is told
     * exactly which state it is answering against, and so a stored answer can be matched back
     * to that state during replay.
     */
    public String pinBlock() {
        StringBuilder sb = new StringBuilder("CONTEXT VERSION (fixed for this task):\n");
        appendPin(sb, "document", documentId);
        appendPin(sb, "chunk", chunkId);
        appendPin(sb, "graph", graphId);
        appendPin(sb, "parentGraph", parentGraphId);
        appendPin(sb, "schema", schemaVersion);
        appendPin(sb, "partition", partitionId);
        pins.forEach((k, v) -> appendPin(sb, k, v));
        return sb.toString();
    }

    /** Existing graph state is identity/reconciliation context and is never evidence for a claim. */
    public String graphContextBlock() {
        if (graphContext == null || graphContext.isBlank()) {
            return "EXISTING GRAPH CONTEXT: none available for this task.";
        }
        return "EXISTING GRAPH CONTEXT (identity/reconciliation hints; NOT source evidence):\n"
                + graphContext
                + "\nUse this only to reuse or distinguish identities. SOURCE TEXT remains the only authority for new assertions.";
    }

    /**
     * Concept pre-pass output aligned back to exact source surfaces before model exposure.
     * Cross-shard hints that do not occur in this source window stay engine-owned.
     */
    public String conceptHintBlock() {
        return SourceGroundedConceptFacets.promptBlock(this);
    }

    /** Resolved prompt profile pinned by the crawl executor; absent pins preserve legacy bounds. */
    String promptTier() {
        String value = pins.get("promptTier");
        return value == null || value.isBlank()
                ? "LEGACY" : value.strip().toUpperCase(Locale.ROOT);
    }

    /** Hard prompt-input budget selected before dispatch, or zero for legacy direct callers. */
    int promptBudgetTokens() {
        String value = pins.get("promptBudgetTokens");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    int promptSourceCharLimit() {
        return switch (promptTier()) {
            case "COMPACT" -> 3_000;
            case "STANDARD" -> 6_000;
            case "RICH" -> 12_000;
            case "EXPANDED" -> 24_000;
            default -> 24_000;
        };
    }

    int promptDiagnosticCharLimit() {
        return switch (promptTier()) {
            case "COMPACT" -> 400;
            case "STANDARD" -> 800;
            case "RICH" -> 1_400;
            default -> 2_000;
        };
    }

    int promptConceptHintLimit() {
        return conceptHintLimit(pins);
    }

    /** Maximum schema/type ballot size for the selected small-model prompt tier. */
    int promptEntityTypeLimit() {
        return switch (promptTier()) {
            case "COMPACT" -> 6;
            case "STANDARD" -> 10;
            case "RICH" -> 16;
            case "EXPANDED" -> 24;
            default -> 16;
        };
    }

    /**
     * Compact explanation of how the unified-corpus scheduler found this shard.
     * Retrieval facets guide attention only; they never prove entity identity or a new assertion.
     */
    public String retrievalFacetBlock() {
        String channel = pins.get("discoveryChannel");
        String reason = pins.get("evidenceReason");
        String confidence = pins.get("evidenceConfidence");
        if ((channel == null || channel.isBlank()) && (reason == null || reason.isBlank())
                && (confidence == null || confidence.isBlank())) {
            return "ENGINE RETRIEVAL FACET: none recorded for this shard.";
        }
        String normalized = channel == null ? "" : channel.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        String family = switch (normalized) {
            case "SEMANTIC" -> "EMBEDDING_SIMILARITY";
            case "DIRECT_IDENTIFIER" -> "DIRECT_IDENTIFIER";
            case "STRUCTURED_RELATIONSHIP" -> "GRAPH_NEIGHBORHOOD";
            case "CONTRADICTION" -> "GRAPH_CONTRADICTION";
            case "UNIFIED_CORPUS_PARTITION" -> "UNIFIED_CORPUS_PARTITION";
            default -> normalized.isBlank() ? "UNSPECIFIED" : normalized;
        };
        StringBuilder rendered = new StringBuilder(
                "ENGINE RETRIEVAL FACET (why this source shard was scheduled; NOT identity or fact evidence):\n")
                .append("- family=").append(family);
        appendInline(rendered, "channel", channel);
        appendInline(rendered, "score", confidence);
        appendInline(rendered, "reason", reason);
        rendered.append('\n');
        if ("EMBEDDING_SIMILARITY".equals(family)) {
            rendered.append("Embedding similarity is a soft recall signal only. It can retrieve useful text, "
                    + "but it cannot establish that two entities are identical.");
        } else if ("DIRECT_IDENTIFIER".equals(family)) {
            rendered.append("The shard matched a partition identifier, but entity reuse still requires the "
                    + "candidate-specific identity controls below.");
        } else if ("GRAPH_NEIGHBORHOOD".equals(family)
                || "GRAPH_CONTRADICTION".equals(family)) {
            rendered.append("The graph route selected this source for reconciliation; SOURCE TEXT remains "
                    + "the authority for new assertions.");
        } else {
            rendered.append("This route controls recall only; candidate identity and source assertions are "
                    + "validated separately.");
        }
        return rendered.toString();
    }

    private static void appendPin(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(name).append(": ").append(value).append('\n');
        }
    }

    private static void appendInline(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String bounded = bound(value.replace('\n', ' '), 240);
        sb.append(" | ").append(name).append('=').append(bounded);
    }

    private static int graphContextCharLimit(Map<String, String> pins) {
        return switch (promptTier(pins)) {
            case "COMPACT" -> 1_500;
            case "STANDARD" -> 3_000;
            case "RICH" -> 6_000;
            case "EXPANDED" -> 12_000;
            default -> 6_000;
        };
    }

    private static int conceptHintLimit(Map<String, String> pins) {
        return switch (promptTier(pins)) {
            case "COMPACT" -> 4;
            case "STANDARD" -> 8;
            case "RICH" -> 12;
            case "EXPANDED" -> 16;
            default -> 16;
        };
    }

    private static String promptTier(Map<String, String> pins) {
        String value = pins == null ? null : pins.get("promptTier");
        return value == null || value.isBlank()
                ? "LEGACY" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String bound(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maxChars ? stripped : stripped.substring(0, maxChars) + "…";
    }
}
