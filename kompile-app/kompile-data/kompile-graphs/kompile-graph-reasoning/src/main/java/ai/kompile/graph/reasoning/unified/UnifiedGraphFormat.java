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
package ai.kompile.graph.reasoning.unified;

/**
 * Constants describing the on-disk form of a persisted {@link UnifiedGraph} (its {@code save}/
 * {@code load} representation).
 *
 * <h2>Container</h2>
 * <p>A saved unified graph is a single ZIP container (pure JDK {@code java.util.zip}, no external
 * dependency) holding these entries:</p>
 * <pre>
 *   manifest.json        format/version, graph-level meta, counts, and the vector-layer index
 *   entities.jsonl       one JSON object per entity (scalar fields + tags + attributes + opinion)
 *   relations.jsonl      one JSON object per relation
 *   weights.json         (optional) named scalar weight maps (PSL/MEBN rule weights, ...)
 *   opinions.jsonl       (optional) opinions keyed to ids not present in topology rows
 *   vectors/&lt;name&gt;.kvec   one dtype-preserving {@link VectorBlobCodec} blob per vector layer
 * </pre>
 *
 * <p>Structural sections are UTF-8 JSON (human-inspectable via {@code unzip}); weight vectors are
 * compact self-describing binary. Together they represent every aspect of a kompile graph:
 * entities, relations, all their scalar/temporal/provenance attributes, subjective-logic opinions,
 * every weight-vector layer (sentence embeddings, KGE entity/relation vectors) at native dtype, and
 * named learned weight maps.</p>
 */
public final class UnifiedGraphFormat {

    private UnifiedGraphFormat() { }

    /** Conventional file extension for a saved unified graph. */
    public static final String EXTENSION = ".kgraph";

    /** {@code "kompile-graph"} — written to the manifest {@code format} field. */
    public static final String FORMAT = "kompile-graph";

    /** Current format version. Bumped only on incompatible manifest/section changes. */
    public static final int FORMAT_VERSION = 1;

    /** Identifies the producing library in the manifest. */
    public static final String GENERATOR = "kompile-graph-reasoning";

    // ── ZIP entry names ───────────────────────────────────────────────────────
    public static final String ENTRY_MANIFEST  = "manifest.json";
    public static final String ENTRY_ENTITIES   = "entities.jsonl";
    public static final String ENTRY_RELATIONS  = "relations.jsonl";
    public static final String ENTRY_WEIGHTS    = "weights.json";
    public static final String ENTRY_OPINIONS   = "opinions.jsonl";
    public static final String VECTOR_DIR       = "vectors/";
    public static final String VECTOR_SUFFIX    = ".kvec";
    public static final String MODELS_DIR       = "models/";

    // ── Reserved vector-layer names (primary per-node/edge embeddings) ─────────
    /**
     * Name of the vector layer that carries each entity's primary
     * {@link ai.kompile.graph.reasoning.model.GraphEntity#embedding()} (e.g. the dense sentence
     * embedding). Synthesized from the graph on save and re-attached to entities on load.
     */
    public static final String PRIMARY_ENTITY_VECTORS = "embedding";

    /**
     * Name of the vector layer that carries each relation's primary
     * {@link ai.kompile.graph.reasoning.model.GraphRelation#embedding()} (e.g. a KGE relation
     * vector). Synthesized from the graph on save and re-attached to relations on load.
     */
    public static final String PRIMARY_RELATION_VECTORS = "relationEmbedding";

    // ── Well-known manifest meta keys ─────────────────────────────────────────
    public static final String META_GRAPH_ID      = "graphId";
    public static final String META_FACT_SHEET_ID = "factSheetId";

    /** Whether {@code name} is one of the reserved primary-embedding layer names. */
    public static boolean isReservedVectorLayerName(String name) {
        return PRIMARY_ENTITY_VECTORS.equals(name) || PRIMARY_RELATION_VECTORS.equals(name);
    }

    /** The ZIP entry name for a vector layer blob. */
    public static String vectorEntry(String layerName) {
        return VECTOR_DIR + layerName + VECTOR_SUFFIX;
    }

    /** The ZIP entry name for a bundled model artifact. */
    public static String modelEntry(String artifactName) {
        return MODELS_DIR + artifactName;
    }
}
