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

import ai.kompile.graph.reasoning.confidence.InMemoryOpinionStore;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.embedding.kge.KgeEmbeddingTableBridge;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.subgraph.SubgraphMaterializer;
import ai.kompile.graph.reasoning.subgraph.SubgraphSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single, unified kompile graph — one structure you pass around <em>as a graph</em> that also
 * carries every aspect a kompile knowledge graph has.
 *
 * <p>{@code UnifiedGraph} <b>is</b> a {@link ReasoningGraph}: it implements the interface every
 * reasoning engine (PSL, Bayesian, MEBN, causal, hybrid) consumes, so it is a drop-in graph
 * everywhere — {@code engine.reason(unifiedGraph)} just works. On top of the topology and the
 * per-node/edge primary {@link GraphEntity#embedding() embedding}, it holds the things a bare graph
 * cannot:</p>
 *
 * <ul>
 *   <li><b>Additional weight-vector layers</b> ({@link #vectorLayers()}) — e.g. a KGE entity layer
 *       alongside the sentence-embedding layer, or a relation-type ({@link VectorLayer.Target#GLOBAL
 *       GLOBAL}) KGE layer.</li>
 *   <li><b>Subjective-logic opinions</b> ({@link #entityOpinion(String)}, {@link #relationOpinion(String)})
 *       keyed by entity/relation id.</li>
 *   <li><b>Named learned weight maps</b> ({@link #weightMaps()}) — PSL/MEBN rule weights, etc.</li>
 *   <li><b>Graph-level metadata</b> ({@link #meta()}) — graph id, fact-sheet scope, and any other
 *       envelope fields.</li>
 * </ul>
 *
 * <p>Everything store-specific (node level, edge type, provenance keys, source paths, KGE algorithm
 * and version, occurred/observed timestamps, ...) rides in the entity/relation
 * {@link GraphEntity#attributes() attributes} bag, so a single {@code UnifiedGraph} losslessly
 * represents a whole kompile graph without any store or framework coupling.</p>
 *
 * <p>The graph persists itself to a single file with {@link #save(Path)} and is reconstructed with
 * {@link #load(Path)} — the round-trip preserves every aspect above, so it doubles as the format for
 * quick bootstrapping. This class is a mutable builder; it is not thread-safe.</p>
 */
public final class UnifiedGraph implements ReasoningGraph {

    private final MutableReasoningGraph graph = new MutableReasoningGraph();
    private final LinkedHashMap<String, VectorLayer> vectorLayers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Opinion> entityOpinions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Opinion> relationOpinions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Map<String, Double>> weightMaps = new LinkedHashMap<>();
    private final LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> artifacts = new LinkedHashMap<>();

    /** Create an empty unified graph. */
    public UnifiedGraph() {
    }

    /**
     * Adopt an existing graph's entities and relations into a new {@code UnifiedGraph}. Entities and
     * relations are shallow-copied (by reference — they are immutable); the source is untouched.
     */
    public static UnifiedGraph of(ReasoningGraph source) {
        Objects.requireNonNull(source, "source");
        UnifiedGraph out = new UnifiedGraph();
        for (GraphEntity e : source.entities()) out.graph.addEntity(e);
        for (GraphRelation r : source.relations()) out.graph.addRelation(r);
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ReasoningGraph — this object IS the graph
    // ═════════════════════════════════════════════════════════════════════════

    @Override public Collection<GraphEntity> entities() { return graph.entities(); }
    @Override public Collection<GraphRelation> relations() { return graph.relations(); }
    @Override public Optional<GraphEntity> entity(String id) { return graph.entity(id); }
    @Override public List<GraphRelation> outgoing(String entityId) { return graph.outgoing(entityId); }
    @Override public List<GraphRelation> incoming(String entityId) { return graph.incoming(entityId); }
    @Override public List<GraphRelation> relationsOf(String entityId) { return graph.relationsOf(entityId); }

    // ── Mutation ────────────────────────────────────────────────────────────────

    /** Add or replace an entity (keyed by id). */
    public UnifiedGraph addEntity(GraphEntity entity) {
        graph.addEntity(entity);
        return this;
    }

    /** Convenience: add a simple entity by id/type/label. */
    public UnifiedGraph addEntity(String id, String type, String label) {
        graph.addEntity(id, type, label);
        return this;
    }

    /** Add a relation. */
    public UnifiedGraph addRelation(GraphRelation relation) {
        graph.addRelation(relation);
        return this;
    }

    /** Convenience: add a directed relation. */
    public UnifiedGraph addRelation(String id, String sourceId, String targetId, String type, double weight) {
        graph.addRelation(id, sourceId, targetId, type, weight);
        return this;
    }

    /**
     * Return an induced unified subgraph while preserving analysis assets that still apply to the
     * retained topology. This is a convenience wrapper around {@link SubgraphMaterializer}.
     */
    public UnifiedGraph inducedSubgraph(Collection<String> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        LinkedHashSet<String> seeds = entityIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (seeds.isEmpty()) {
            UnifiedGraph out = new UnifiedGraph();
            meta.forEach(out::meta);
            weightMaps.forEach(out::putWeightMap);
            artifacts.forEach(out::putArtifact);
            return out;
        }
        return SubgraphMaterializer.INSTANCE.materializeUnified(this,
                SubgraphSpec.builder().seedIds(seeds).radius(0).maxNodes(0).build());
    }

    /** Return a unified graph neighborhood around seed entities, preserving analysis assets. */
    public UnifiedGraph neighborhood(Collection<String> seedEntityIds, int depth) {
        Objects.requireNonNull(seedEntityIds, "seedEntityIds");
        LinkedHashSet<String> seeds = seedEntityIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (seeds.isEmpty()) {
            return inducedSubgraph(List.of());
        }
        return SubgraphMaterializer.INSTANCE.materializeUnified(this,
                SubgraphSpec.builder().seedIds(seeds).radius(Math.max(0, depth)).maxNodes(0).build());
    }

    /**
     * The backing {@link MutableReasoningGraph} — for library capabilities that require a mutable
     * graph specifically (e.g. {@code EmbeddingLearner.learnInto}, adapters that rewrite entities).
     * Mutations are reflected by this graph's {@link #entities()} / {@link #relations()}.
     */
    public MutableReasoningGraph mutableGraph() {
        return graph;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Weight-vector layers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Register an additional weight-vector layer. The reserved names
     * {@link UnifiedGraphFormat#PRIMARY_ENTITY_VECTORS} and
     * {@link UnifiedGraphFormat#PRIMARY_RELATION_VECTORS} are rejected — the primary per-node/edge
     * embedding is carried by the graph itself (set it via {@link GraphEntity#embedding()}).
     */
    public UnifiedGraph putVectorLayer(VectorLayer layer) {
        Objects.requireNonNull(layer, "layer");
        if (UnifiedGraphFormat.isReservedVectorLayerName(layer.name())) {
            throw new IllegalArgumentException("Vector-layer name '" + layer.name()
                    + "' is reserved for the primary embedding carried on the graph entities/relations."
                    + " Use another name for an additional layer, or set GraphEntity#embedding().");
        }
        vectorLayers.put(layer.name(), layer);
        return this;
    }

    /** Convenience: add an entity-keyed vector to {@code layer}, creating the layer (F32) if needed. */
    public UnifiedGraph putEntityVector(String layer, String entityId, double[] vector) {
        return putVectorInternal(layer, VectorLayer.Target.ENTITY, entityId, vector);
    }

    /** Convenience: add a relation-keyed vector to {@code layer}, creating the layer (F32) if needed. */
    public UnifiedGraph putRelationVector(String layer, String relationId, double[] vector) {
        return putVectorInternal(layer, VectorLayer.Target.RELATION, relationId, vector);
    }

    /** Convenience: add a label-keyed ({@code GLOBAL}) vector to {@code layer}, creating it (F32) if needed. */
    public UnifiedGraph putGlobalVector(String layer, String label, double[] vector) {
        return putVectorInternal(layer, VectorLayer.Target.GLOBAL, label, vector);
    }

    private UnifiedGraph putVectorInternal(String name, VectorLayer.Target target, String id, double[] vector) {
        if (UnifiedGraphFormat.isReservedVectorLayerName(name)) {
            throw new IllegalArgumentException("Vector-layer name '" + name + "' is reserved.");
        }
        VectorLayer layer = vectorLayers.get(name);
        if (layer == null) {
            layer = new VectorLayer(name, target, 0, Dtype.F32);
            vectorLayers.put(name, layer);
        } else if (layer.target() != target) {
            throw new IllegalArgumentException("Layer '" + name + "' already has target "
                    + layer.target() + ", cannot add a " + target + " vector");
        }
        layer.put(id, vector);
        return this;
    }

    /** An additional vector layer by name, or {@code null}. */
    public VectorLayer vectorLayer(String name) {
        return vectorLayers.get(name);
    }

    /** All additional vector layers, keyed by name, in insertion order. */
    public Map<String, VectorLayer> vectorLayers() {
        return Collections.unmodifiableMap(vectorLayers);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Opinions
    // ═════════════════════════════════════════════════════════════════════════

    public UnifiedGraph putEntityOpinion(String entityId, Opinion opinion) {
        entityOpinions.put(Objects.requireNonNull(entityId), Objects.requireNonNull(opinion));
        return this;
    }

    public UnifiedGraph putRelationOpinion(String relationId, Opinion opinion) {
        relationOpinions.put(Objects.requireNonNull(relationId), Objects.requireNonNull(opinion));
        return this;
    }

    /** The subjective-logic opinion on entity {@code id}, or {@code null}. */
    public Opinion entityOpinion(String entityId) {
        return entityOpinions.get(entityId);
    }

    /** The subjective-logic opinion on relation {@code id}, or {@code null}. */
    public Opinion relationOpinion(String relationId) {
        return relationOpinions.get(relationId);
    }

    public Map<String, Opinion> entityOpinions() {
        return Collections.unmodifiableMap(entityOpinions);
    }

    public Map<String, Opinion> relationOpinions() {
        return Collections.unmodifiableMap(relationOpinions);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Named weight maps
    // ═════════════════════════════════════════════════════════════════════════

    /** Register a named scalar weight map (e.g. {@code "pslWeights"} → rule→weight). */
    public UnifiedGraph putWeightMap(String name, Map<String, Double> weights) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(weights, "weights");
        weightMaps.put(name, new LinkedHashMap<>(weights));
        return this;
    }

    public Map<String, Double> weightMap(String name) {
        return weightMaps.get(name);
    }

    public Map<String, Map<String, Double>> weightMaps() {
        return Collections.unmodifiableMap(weightMaps);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Graph-level metadata
    // ═════════════════════════════════════════════════════════════════════════

    /** Set a graph-level metadata field (e.g. {@link UnifiedGraphFormat#META_GRAPH_ID}). */
    public UnifiedGraph meta(String key, Object value) {
        meta.put(Objects.requireNonNull(key), value);
        return this;
    }

    public Map<String, Object> meta() {
        return Collections.unmodifiableMap(meta);
    }

    /** The graph id from {@link UnifiedGraphFormat#META_GRAPH_ID}, or {@code null}. */
    public String graphId() {
        Object v = meta.get(UnifiedGraphFormat.META_GRAPH_ID);
        return v == null ? null : String.valueOf(v);
    }

    /** Convenience setter for the graph id. */
    public UnifiedGraph graphId(String graphId) {
        return meta(UnifiedGraphFormat.META_GRAPH_ID, graphId);
    }

    /** The fact-sheet scope from {@link UnifiedGraphFormat#META_FACT_SHEET_ID}, or {@code null}. */
    public Long factSheetId() {
        Object v = meta.get(UnifiedGraphFormat.META_FACT_SHEET_ID);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Convenience setter for the fact-sheet scope. */
    public UnifiedGraph factSheetId(Long factSheetId) {
        return meta(UnifiedGraphFormat.META_FACT_SHEET_ID, factSheetId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Model artifacts — bundle ANY serialized model with the graph (one file)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Attach a named model artifact (raw bytes) so it travels inside the single {@code .kgraph} file
     * alongside the graph — e.g. a serialized TypeRegistry, PSL weights, MEBN strengths, a
     * SameDiff/KGE model, or a Drools decision table. The archive is agnostic about the bytes:
     * serialize with the model's own IO (e.g. {@code TypeRegistryIO.toJson(...)},
     * {@code MebnWeightSerializer.strengthsToJson(...)}, {@code EmbeddingTableIO.toJson(...)}) and
     * read it back with the matching reader after {@link #load}.
     */
    public UnifiedGraph putArtifact(String name, byte[] data) {
        artifacts.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(data, "data"));
        return this;
    }

    /** Attach a UTF-8 text artifact (e.g. {@code TypeRegistryIO.toJson(snapshot)}). */
    public UnifiedGraph putArtifactText(String name, String text) {
        return putArtifact(name, text.getBytes(StandardCharsets.UTF_8));
    }

    /** The raw bytes of a named artifact, or {@code null}. */
    public byte[] artifact(String name) {
        return artifacts.get(name);
    }

    /** A named artifact decoded as UTF-8 text, or {@code null}. */
    public String artifactText(String name) {
        byte[] data = artifacts.get(name);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    /** All attached model artifacts, keyed by name, in insertion order. */
    public Map<String, byte[]> artifacts() {
        return Collections.unmodifiableMap(artifacts);
    }

    /**
     * Bundle a whole {@link Serializable} model object — a PSL program, an MTheory, a TypeRegistry,
     * or any other model in the reasoning library — by binary-serializing it into the archive under
     * {@code name}. Reconstruct the exact object with {@link #model}. This is the turnkey path for
     * carrying a model's full <em>structure</em> (not just its weights) inside the one file.
     */
    public UnifiedGraph putModel(String name, Serializable model) {
        return putArtifact(name, JavaSerde.toBytes(model));
    }

    /** Reconstruct a model bundled with {@link #putModel}, or {@code null} if absent. */
    public <T> T model(String name) {
        byte[] data = artifacts.get(name);
        return data == null ? null : JavaSerde.fromBytes(data);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Ontology / type enumeration
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Every distinct crisp type membership asserted across the entities — i.e. the instantiated
     * ontology classes. Reads each entity's {@link GraphEntity#typeMemberships()} (its {@code type}
     * plus {@code owlInferredTypes} / {@code additionalTypes} / declared-candidate attributes).
     */
    public Set<String> types() {
        Set<String> all = new LinkedHashSet<>();
        for (GraphEntity e : graph.entities()) all.addAll(e.typeMemberships());
        return all;
    }

    /** All entities that are members of {@code type} (case-insensitive) — the instances of that class. */
    public List<GraphEntity> entitiesOfType(String type) {
        List<GraphEntity> out = new ArrayList<>();
        for (GraphEntity e : graph.entities()) {
            if (e.hasTypeMembership(type)) out.add(e);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Facts view — the graph projected as a flat fact base
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The graph as a flat list of {@link Fact}s: each relation becomes a binary fact
     * {@code type(source, target)} and each entity's crisp type membership a unary fact
     * {@code Type(id)}. This is the fact base you can feed straight to the FOL / PSL / contradiction
     * machinery (e.g. {@code ContradictionDetector.findFactContradictions}).
     */
    public List<Fact> facts() {
        List<Fact> facts = new ArrayList<>();
        for (GraphRelation r : graph.relations()) {
            facts.add(Fact.observed(r.type() + "(" + r.sourceId() + ", " + r.targetId() + ")", "graph:relation"));
        }
        for (GraphEntity e : graph.entities()) {
            for (String t : e.typeMemberships()) {
                facts.add(Fact.observed(t + "(" + e.id() + ")", "graph:type"));
            }
        }
        return facts;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reasoning access — an imported graph is ready to reason on, no bridging
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The subjective-logic opinions as an {@link OpinionStore} keyed by entity/relation id — the
     * form the confidence / fusion / verification machinery consumes. So loading a graph and reading
     * its opinions into a reasoner is a single call, not side-map plumbing.
     */
    public OpinionStore opinionStore() {
        InMemoryOpinionStore store = new InMemoryOpinionStore();
        relationOpinions.forEach(store::put);
        entityOpinions.forEach(store::put); // entity opinions win on an id collision
        return store;
    }

    /**
     * A vector layer as an {@link EmbeddingTable} — the form the KGE scorers and
     * {@code EmbeddingPslEvidence} consume. One call turns any layer (KGE, node2vec, ...) into
     * reasoning-ready embeddings. Returns {@code null} if the layer is absent or empty.
     */
    public EmbeddingTable embeddingTable(String layerName) {
        VectorLayer layer = vectorLayers.get(layerName);
        if (layer == null || layer.isEmpty()) {
            return null;
        }
        return KgeEmbeddingTableBridge.fromVectorMap(layer.rows());
    }

    /**
     * A {@link ReasoningGraph} view of this graph in which every entity's
     * {@link GraphEntity#embedding()} is backed by the named vector layer (falling back to the
     * entity's own embedding where the layer has none). This lets any embedding-consuming engine
     * (semantic similarity, hybrid reasoning) run over a <em>non-primary</em> layer — e.g. reason
     * over the KGE layer instead of the sentence embedding — with a single call and no copying. If
     * the layer is absent, returns this graph unchanged.
     */
    public ReasoningGraph withEmbeddingLayer(String layerName) {
        VectorLayer layer = vectorLayers.get(layerName);
        return layer == null ? this : new LayeredReasoningGraph(this, layer);
    }

    /** A {@link ReasoningGraph} whose entity embeddings are overlaid from a {@link VectorLayer}. */
    private static final class LayeredReasoningGraph implements ReasoningGraph {
        private final UnifiedGraph base;
        private final VectorLayer layer;

        LayeredReasoningGraph(UnifiedGraph base, VectorLayer layer) {
            this.base = base;
            this.layer = layer;
        }

        private GraphEntity overlay(GraphEntity e) {
            double[] v = layer.get(e.id());
            return v != null ? new LayeredEntity(e, v) : e;
        }

        @Override public Collection<GraphEntity> entities() {
            return base.entities().stream().map(this::overlay).collect(Collectors.toList());
        }
        @Override public Collection<GraphRelation> relations() { return base.relations(); }
        @Override public Optional<GraphEntity> entity(String id) {
            return base.entity(id).map(this::overlay);
        }
        @Override public List<GraphRelation> outgoing(String id) { return base.outgoing(id); }
        @Override public List<GraphRelation> incoming(String id) { return base.incoming(id); }
        @Override public List<GraphRelation> relationsOf(String id) { return base.relationsOf(id); }
    }

    /** A {@link GraphEntity} delegate with only its {@link #embedding()} overridden. */
    private static final class LayeredEntity implements GraphEntity {
        private final GraphEntity delegate;
        private final double[] embedding;

        LayeredEntity(GraphEntity delegate, double[] embedding) {
            this.delegate = delegate;
            this.embedding = embedding;
        }

        @Override public String id() { return delegate.id(); }
        @Override public String type() { return delegate.type(); }
        @Override public String label() { return delegate.label(); }
        @Override public double weight() { return delegate.weight(); }
        @Override public double confidence() { return delegate.confidence(); }
        @Override public Set<String> tags() { return delegate.tags(); }
        @Override public Instant timestamp() { return delegate.timestamp(); }
        @Override public Map<String, Object> attributes() { return delegate.attributes(); }
        @Override public double[] embedding() { return embedding; }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Persistence — the graph saves/loads itself (one file, all aspects)
    // ═════════════════════════════════════════════════════════════════════════

    /** Save this graph to a single file (primary embeddings written as {@link Dtype#F32}). */
    public void save(Path file) throws IOException {
        UnifiedGraphWriter.write(this, file, Dtype.F32);
    }

    /** Save this graph to a single file with an explicit primary-embedding dtype. */
    public void save(Path file, Dtype primaryVectorDtype) throws IOException {
        UnifiedGraphWriter.write(this, file, primaryVectorDtype);
    }

    /** Save this graph to a stream (the stream is flushed but not closed). */
    public void save(OutputStream out) throws IOException {
        UnifiedGraphWriter.write(this, out, Dtype.F32);
    }

    /** Save this graph to a stream with an explicit primary-embedding dtype. */
    public void save(OutputStream out, Dtype primaryVectorDtype) throws IOException {
        UnifiedGraphWriter.write(this, out, primaryVectorDtype);
    }

    /** Load a unified graph from a file written by {@link #save(Path)}. */
    public static UnifiedGraph load(Path file) throws IOException {
        return UnifiedGraphReader.read(file);
    }

    /** Load a unified graph from a stream written by {@link #save(OutputStream)}. */
    public static UnifiedGraph load(InputStream in) throws IOException {
        return UnifiedGraphReader.read(in);
    }
}
