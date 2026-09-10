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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One named layer of dense weight vectors over a {@link UnifiedGraph} — an id&rarr;vector map of a
 * fixed dimension.
 *
 * <p>A {@link ai.kompile.graph.reasoning.model.GraphEntity} carries a single
 * {@link ai.kompile.graph.reasoning.model.GraphEntity#embedding() embedding()}, but a real kompile
 * graph has several distinct vector layers over the same nodes (dense sentence embeddings for ANN
 * retrieval, TransE/RotatE KGE entity vectors) plus vectors keyed by things that are not nodes at
 * all (KGE relation-type vectors keyed by relation label). A {@code VectorLayer} is exactly one such
 * layer: a {@link #name()}, what its keys refer to ({@link #target()}), the vector
 * {@link #dim() dimension}, the persisted {@link #dtype() element type}, and the id&rarr;vector rows.
 * A {@link UnifiedGraph} owns any number of them alongside the primary node/edge embeddings.</p>
 *
 * <p>Sparse coverage is first-class: a layer only needs rows for the ids that actually have a vector
 * in it (mirroring the infra behavior where only nodes with a non-zero embedding row are persisted).
 * Insertion order is preserved for deterministic output.</p>
 */
public final class VectorLayer {

    /** What the keys of a {@link VectorLayer} refer to. */
    public enum Target {
        /** Keys are {@link ai.kompile.graph.reasoning.model.GraphEntity#id() entity ids}. */
        ENTITY(0),
        /** Keys are {@link ai.kompile.graph.reasoning.model.GraphRelation#id() relation ids}. */
        RELATION(1),
        /** Keys are free-form labels (e.g. relation <em>types</em>, ontology classes). */
        GLOBAL(2);

        private final int code;
        Target(int code) { this.code = code; }
        public int code() { return code; }

        public static Target fromCode(int code) {
            for (Target t : values()) if (t.code == code) return t;
            throw new IllegalArgumentException("Unknown VectorLayer.Target code: " + code);
        }
        public static Target fromName(String name, Target fallback) {
            if (name == null) return fallback;
            for (Target t : values()) if (t.name().equalsIgnoreCase(name.trim())) return t;
            return fallback;
        }
    }

    private final String name;
    private final Target target;
    private final Dtype dtype;
    private int dim;
    private final LinkedHashMap<String, double[]> rows = new LinkedHashMap<>();

    /**
     * Create a layer. {@code dim} may be {@code 0} to be inferred from the first vector inserted;
     * once set, every inserted vector must match it.
     */
    public VectorLayer(String name, Target target, int dim, Dtype dtype) {
        this.name = Objects.requireNonNull(name, "name");
        this.target = Objects.requireNonNull(target, "target");
        this.dtype = Objects.requireNonNull(dtype, "dtype");
        if (dim < 0) throw new IllegalArgumentException("dim must be >= 0: " + dim);
        this.dim = dim;
    }

    /** Add or replace the vector for {@code id}. The array is stored by reference (treat as owned). */
    public VectorLayer put(String id, double[] vector) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(vector, "vector");
        if (dim == 0) {
            dim = vector.length;
        } else if (vector.length != dim) {
            throw new IllegalArgumentException(
                    "vector length " + vector.length + " != layer dim " + dim + " for id=" + id
                    + " in layer '" + name + "'");
        }
        rows.put(id, vector);
        return this;
    }

    /** Convenience: add a {@code float[]} vector (widened to {@code double[]}). */
    public VectorLayer putFloats(String id, float[] vector) {
        double[] d = new double[vector.length];
        for (int i = 0; i < vector.length; i++) d[i] = vector[i];
        return put(id, d);
    }

    /** The vector for {@code id}, or {@code null} if this layer has none for it. */
    public double[] get(String id) {
        return rows.get(id);
    }

    /** Whether this layer carries a vector for {@code id}. */
    public boolean contains(String id) {
        return rows.containsKey(id);
    }

    /** Remove the vector for {@code id}. No-ops when this layer has no such row. */
    public VectorLayer remove(String id) {
        rows.remove(Objects.requireNonNull(id, "id"));
        return this;
    }

    public String name() { return name; }
    public Target target() { return target; }
    public Dtype dtype() { return dtype; }

    /** Vector dimension, or {@code 0} if the layer is still empty and was created with an unset dim. */
    public int dim() { return dim; }

    /** Number of vectors (rows) in this layer. */
    public int size() { return rows.size(); }

    /** Whether the layer has no rows. */
    public boolean isEmpty() { return rows.isEmpty(); }

    /** The ids that have a vector, in insertion order. */
    public Set<String> ids() { return Collections.unmodifiableSet(rows.keySet()); }

    /** The id&rarr;vector rows, in insertion order. */
    public Map<String, double[]> rows() { return Collections.unmodifiableMap(rows); }
}
