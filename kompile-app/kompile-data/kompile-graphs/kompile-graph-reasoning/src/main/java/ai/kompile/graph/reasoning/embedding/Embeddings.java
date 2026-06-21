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
package ai.kompile.graph.reasoning.embedding;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dense-vector helpers over the {@code double[]} embeddings carried by {@link GraphEntity} /
 * {@link ai.kompile.graph.reasoning.model.GraphRelation}. Pure math, no backend — the host supplies
 * the vectors (e.g. KGE embeddings projected from the knowledge graph).
 */
public final class Embeddings {

    private Embeddings() {
    }

    /** Dot product. Returns 0 for null/empty/length-mismatched inputs. */
    public static double dot(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** L2 norm (magnitude). Returns 0 for null/empty. */
    public static double magnitude(double[] v) {
        if (v == null || v.length == 0) {
            return 0.0;
        }
        return Math.sqrt(dot(v, v));
    }

    /**
     * Cosine similarity in {@code [-1, 1]}. Returns 0 when either vector is absent, empty,
     * zero-magnitude, or of mismatched length (so missing embeddings contribute nothing).
     */
    public static double cosine(double[] a, double[] b) {
        double ma = magnitude(a);
        double mb = magnitude(b);
        if (ma == 0.0 || mb == 0.0) {
            return 0.0;
        }
        return dot(a, b) / (ma * mb);
    }

    /** Euclidean distance. Returns {@link Double#POSITIVE_INFINITY} for null/mismatched inputs. */
    public static double euclidean(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return Double.POSITIVE_INFINITY;
        }
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }

    /** L2-normalized copy of {@code v} (unit vector), or a copy unchanged if zero-magnitude/empty. */
    public static double[] normalize(double[] v) {
        if (v == null) {
            return null;
        }
        double m = magnitude(v);
        double[] out = v.clone();
        if (m > 0.0) {
            for (int i = 0; i < out.length; i++) {
                out[i] /= m;
            }
        }
        return out;
    }

    /** A scored entity from a semantic search. */
    public record SemanticHit(String entityId, double similarity) {
    }

    /**
     * The {@code topK} entities in {@code graph} most cosine-similar to {@code query}, descending.
     * Entities without an embedding are skipped.
     */
    public static List<SemanticHit> mostSimilar(ReasoningGraph graph, double[] query, int topK) {
        List<SemanticHit> hits = new ArrayList<>();
        for (GraphEntity e : graph.entities()) {
            if (e.hasEmbedding()) {
                hits.add(new SemanticHit(e.id(), cosine(e.embedding(), query)));
            }
        }
        hits.sort(Comparator.comparingDouble(SemanticHit::similarity).reversed());
        return topK > 0 && hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }
}
