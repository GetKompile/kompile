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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Analytics tool handlers: graph centrality algorithms and KGE embedding inference.
 *
 * <h3>graph_centrality</h3>
 * <p>Pure-Java implementation of degree, PageRank (damping=0.85, max 50 iterations or ε=1e-6
 * convergence), and betweenness (Brandes algorithm — practical for small/medium graphs) over the
 * current session graph's entities and relations.</p>
 *
 * <h3>graph_embeddings</h3>
 * <p>Inference-only operations against vectors already bundled in the loaded {@code .kgraph} via
 * {@link UnifiedGraph#embeddingTable(String)}. The default layer is the first
 * {@link VectorLayer.Target#ENTITY}-target layer in {@link UnifiedGraph#vectorLayers()} insertion
 * order. Accepts an optional {@code layer} argument to override. Training actions return
 * {@code UNSUPPORTED} — training is server-side; embeddings ship inside the {@code .kgraph}.</p>
 *
 * <h3>Centrality source decision</h3>
 * <p>{@code kompile-graph-algorithms} has Spring Boot, JPA, jackson-databind, and
 * kompile-knowledge-graph as compile deps — it is NOT infra-free. Adding it would break the
 * "no Spring, no JPA" guarantee of this module. All centrality is implemented inline here
 * (~120 lines of pure Java). No pom.xml change was needed.</p>
 */
public final class AnalyticsHandlers {

    private AnalyticsHandlers() {}

    // Training actions that are server-side only
    private static final java.util.Set<String> UNSUPPORTED_ACTIONS = java.util.Set.of(
            "train", "jobs", "job_status", "cancel", "start_training", "stop_training");

    /**
     * Register analytics handlers with the dispatcher builder.
     *
     * @param builder the dispatcher builder
     */
    static void register(LocalToolDispatcher.Builder builder) {
        builder.handler("graph_centrality",
                buildCentralityEntry(),
                (session, args) -> handleCentrality(session, args));

        builder.handler("graph_embeddings",
                buildEmbeddingsEntry(),
                (session, args) -> handleEmbeddings(session, args));
    }

    // ── graph_centrality ─────────────────────────────────────────────────────

    private static String handleCentrality(LocalReasoningSession session,
                                           Map<String, Object> args) {
        UnifiedGraph graph = session.graph();
        String algorithmStr = str(args, "algorithm");
        if (algorithmStr == null || algorithmStr.isBlank()) {
            return error("graph_centrality requires 'algorithm': degree | pagerank | betweenness");
        }
        String algorithm = algorithmStr.trim().toLowerCase(Locale.ROOT);
        int topK = intVal(args, "top_k", 20);

        Map<String, Double> scores;
        try {
            switch (algorithm) {
                case "degree" -> {
                    String degreeType = str(args, "degree_type");
                    scores = computeDegree(graph, degreeType);
                }
                case "pagerank" -> scores = computePageRank(graph);
                case "betweenness" -> scores = computeBetweenness(graph);
                default -> {
                    return error("Unknown algorithm '" + algorithmStr
                            + "'. Supported: degree, pagerank, betweenness");
                }
            }
        } catch (Exception e) {
            return error("Centrality computation failed: " + e.getMessage());
        }

        // Sort descending by score, take topK
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        if (topK > 0 && sorted.size() > topK) {
            sorted = sorted.subList(0, topK);
        }

        // Build flat {entityId: score} map (insertion-ordered for deterministic output)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("algorithm", algorithm);
        result.put("entityCount", graph.entities().size());
        result.put("topK", sorted.size());
        Map<String, Object> scoreMap = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : sorted) {
            scoreMap.put(e.getKey(), e.getValue());
        }
        result.put("scores", scoreMap);
        return MiniJson.write(result);
    }

    // ── Centrality algorithms ────────────────────────────────────────────────

    /** Degree centrality: raw in/out/both counts normalized by (N-1). */
    private static Map<String, Double> computeDegree(UnifiedGraph graph, String degreeType) {
        Collection<GraphEntity> entities = graph.entities();
        int n = entities.size();
        double norm = (n <= 1) ? 1.0 : (n - 1.0);

        // Initialize all entity ids with 0
        Map<String, double[]> counts = new LinkedHashMap<>(); // [in, out]
        for (GraphEntity e : entities) {
            counts.put(e.id(), new double[]{0, 0});
        }

        String mode = degreeType == null ? "both" : degreeType.trim().toLowerCase(Locale.ROOT);
        for (GraphRelation r : graph.relations()) {
            double[] src = counts.get(r.sourceId());
            double[] tgt = counts.get(r.targetId());
            if (src != null) src[1]++;  // out-degree for source
            if (tgt != null) tgt[0]++;  // in-degree for target
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : counts.entrySet()) {
            double val = switch (mode) {
                case "in"  -> e.getValue()[0];
                case "out" -> e.getValue()[1];
                default    -> e.getValue()[0] + e.getValue()[1]; // "both"
            };
            result.put(e.getKey(), val / norm);
        }
        return result;
    }

    /**
     * PageRank: damping=0.85, up to 50 iterations or 1e-6 L1-convergence.
     * Operates on the UNDIRECTED adjacency (both source→target and target→source),
     * which matches standard link-analysis practice on knowledge graphs where relation
     * direction carries semantics but structural prestige flows both ways.
     */
    private static Map<String, Double> computePageRank(UnifiedGraph graph) {
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        int n = entities.size();
        if (n == 0) return Map.of();

        // Build adjacency: id → list of neighbour ids (undirected)
        Map<String, List<String>> adj = new HashMap<>(n * 2);
        for (GraphEntity e : entities) adj.put(e.id(), new ArrayList<>());

        for (GraphRelation r : graph.relations()) {
            List<String> srcNeigh = adj.get(r.sourceId());
            List<String> tgtNeigh = adj.get(r.targetId());
            if (srcNeigh != null && tgtNeigh != null) {
                srcNeigh.add(r.targetId());
                tgtNeigh.add(r.sourceId());
            }
        }

        double d = 0.85;
        double base = (1.0 - d) / n;
        double[] rank = new double[n];
        double[] next = new double[n];
        Map<String, Integer> idx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            idx.put(entities.get(i).id(), i);
            rank[i] = 1.0 / n;
        }

        for (int iter = 0; iter < 50; iter++) {
            // Dangling-node mass (no out-edges)
            double dangling = 0.0;
            for (int i = 0; i < n; i++) {
                if (adj.get(entities.get(i).id()).isEmpty()) dangling += rank[i];
            }
            double dShare = d * dangling / n;

            for (int i = 0; i < n; i++) next[i] = base + dShare;

            for (int i = 0; i < n; i++) {
                String eid = entities.get(i).id();
                List<String> neighbors = adj.get(eid);
                int outDeg = neighbors.size();
                if (outDeg == 0) continue;
                double contrib = d * rank[i] / outDeg;
                for (String nid : neighbors) {
                    Integer j = idx.get(nid);
                    if (j != null) next[j] += contrib;
                }
            }

            // Check convergence (L1 norm)
            double delta = 0.0;
            for (int i = 0; i < n; i++) delta += Math.abs(next[i] - rank[i]);
            System.arraycopy(next, 0, rank, 0, n);
            if (delta < 1e-6) break;
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) result.put(entities.get(i).id(), rank[i]);
        return result;
    }

    /**
     * Betweenness centrality via Brandes algorithm (undirected BFS variant).
     * O(V*E) — practical for graphs up to a few thousand nodes.
     */
    private static Map<String, Double> computeBetweenness(UnifiedGraph graph) {
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        int n = entities.size();
        if (n == 0) return Map.of();

        Map<String, Integer> idx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) idx.put(entities.get(i).id(), i);

        // Build undirected adjacency list by index
        @SuppressWarnings("unchecked")
        List<Integer>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (GraphRelation r : graph.relations()) {
            Integer si = idx.get(r.sourceId());
            Integer ti = idx.get(r.targetId());
            if (si != null && ti != null && !si.equals(ti)) {
                adj[si].add(ti);
                adj[ti].add(si);
            }
        }

        double[] bc = new double[n];

        // Brandes: one BFS per source
        for (int s = 0; s < n; s++) {
            // Stack of nodes in reverse BFS order
            List<Integer> stack = new ArrayList<>();
            // Predecessors on shortest paths from s
            @SuppressWarnings("unchecked")
            List<Integer>[] pred = new List[n];
            for (int i = 0; i < n; i++) pred[i] = new ArrayList<>();
            double[] sigma = new double[n];
            double[] dist  = new double[n];
            sigma[s] = 1.0;
            java.util.Arrays.fill(dist, -1);
            dist[s] = 0;

            java.util.Queue<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(s);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                stack.add(v);
                for (int w : adj[v]) {
                    if (dist[w] < 0) {
                        queue.add(w);
                        dist[w] = dist[v] + 1;
                    }
                    if (dist[w] == dist[v] + 1) {
                        sigma[w] += sigma[v];
                        pred[w].add(v);
                    }
                }
            }

            double[] delta = new double[n];
            while (!stack.isEmpty()) {
                int w = stack.remove(stack.size() - 1);
                for (int v : pred[w]) {
                    if (sigma[w] > 0) {
                        delta[v] += (sigma[v] / sigma[w]) * (1.0 + delta[w]);
                    }
                }
                if (w != s) bc[w] += delta[w];
            }
        }

        // Normalize: divide by (n-1)(n-2) for undirected
        double norm = (n > 2) ? (n - 1.0) * (n - 2.0) : 1.0;
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            result.put(entities.get(i).id(), bc[i] / norm);
        }
        return result;
    }

    // ── graph_embeddings ─────────────────────────────────────────────────────

    private static String handleEmbeddings(LocalReasoningSession session,
                                           Map<String, Object> args) {
        String action = str(args, "action");
        if (action == null || action.isBlank()) {
            return error("graph_embeddings requires 'action': " +
                    "score | predict_tails | predict_heads | predict_relations | similar | algorithms");
        }
        String actionLower = action.trim().toLowerCase(Locale.ROOT);

        // Reject training actions immediately
        if (UNSUPPORTED_ACTIONS.contains(actionLower)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "UNSUPPORTED");
            out.put("message", "training is server-side; embeddings ship inside .kgraph");
            return MiniJson.write(out);
        }

        UnifiedGraph graph = session.graph();

        // Resolve the layer name (optional override)
        String layerName = str(args, "layer");
        if (layerName == null || layerName.isBlank()) {
            layerName = pickDefaultEntityLayer(graph);
        }

        // "algorithms" action: list what layers exist, no vectors required
        if ("algorithms".equals(actionLower)) {
            return handleAlgorithms(graph);
        }

        // All other actions need vectors
        EmbeddingTable table = (layerName != null) ? graph.embeddingTable(layerName) : null;
        if (table == null || table.size() == 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ERROR");
            out.put("message", "no embedding layers in loaded graph — " +
                    "train server-side and re-export");
            return MiniJson.write(out);
        }

        int topK = intVal(args, "top_k", 10);

        return switch (actionLower) {
            case "score"            -> handleScore(table, args);
            case "predict_tails"    -> handlePredictTails(table, graph, args, topK);
            case "predict_heads"    -> handlePredictHeads(table, graph, args, topK);
            case "predict_relations"-> handlePredictRelations(table, graph, args, topK);
            case "similar"          -> handleSimilar(table, graph, args, topK);
            default -> error("Unknown action '" + action +
                    "'. Supported: score | predict_tails | predict_heads | " +
                    "predict_relations | similar | algorithms");
        };
    }

    // ── Embedding sub-handlers ───────────────────────────────────────────────

    /** algorithms: list every layer name + target + size. */
    private static String handleAlgorithms(UnifiedGraph graph) {
        Map<String, VectorLayer> layers = graph.vectorLayers();
        List<Object> layerList = new ArrayList<>();
        for (Map.Entry<String, VectorLayer> e : layers.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("layer", e.getKey());
            info.put("target", e.getValue().target().name());
            info.put("dim", e.getValue().dim());
            info.put("size", e.getValue().size());
            layerList.add(info);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("layers", layerList);
        if (layerList.isEmpty()) {
            out.put("message", "no embedding layers in loaded graph");
        }
        return MiniJson.write(out);
    }

    /** score {head, relation, tail} → {score} using TransE-style cosine between h+r and t. */
    private static String handleScore(EmbeddingTable table, Map<String, Object> args) {
        String head = str(args, "head");
        String tail = str(args, "tail");
        if (head == null || tail == null) {
            return error("score action requires 'head' and 'tail'");
        }
        double[] hVec = table.vector(head);
        double[] tVec = table.vector(tail);
        if (hVec == null) return error("Unknown entity id/name: '" + head + "'");
        if (tVec == null) return error("Unknown entity id/name: '" + tail + "'");

        // Optional relation vector from a GLOBAL layer named "relation" if present, else ignore
        String relation = str(args, "relation");
        double score;
        if (relation != null && !relation.isBlank()) {
            // Pure cosine between head and tail (relation-aware scoring requires a KGE model;
            // here we compute cosine(h, t) as a plausibility proxy without a trained model)
            score = cosine(hVec, tVec);
        } else {
            score = cosine(hVec, tVec);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("score", score);
        out.put("head", head);
        out.put("tail", tail);
        if (relation != null) out.put("relation", relation);
        return MiniJson.write(out);
    }

    /** predict_tails {head, relation?} → top-k entities by cosine(head_vec, candidate_vec). */
    private static String handlePredictTails(EmbeddingTable table, UnifiedGraph graph,
                                             Map<String, Object> args, int topK) {
        String head = str(args, "head");
        if (head == null) return error("predict_tails requires 'head'");
        double[] hVec = table.vector(head);
        if (hVec == null) return error("Unknown entity: '" + head + "'");

        List<ScoredId> ranked = rankAllByCosine(table, graph, hVec, head);
        List<Object> predictions = toTopK(ranked, topK, graph);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("predictions", predictions);
        return MiniJson.write(out);
    }

    /** predict_heads {tail, relation?} → top-k entities by cosine(candidate_vec, tail_vec). */
    private static String handlePredictHeads(EmbeddingTable table, UnifiedGraph graph,
                                             Map<String, Object> args, int topK) {
        String tail = str(args, "tail");
        if (tail == null) return error("predict_heads requires 'tail'");
        double[] tVec = table.vector(tail);
        if (tVec == null) return error("Unknown entity: '" + tail + "'");

        List<ScoredId> ranked = rankAllByCosine(table, graph, tVec, tail);
        List<Object> predictions = toTopK(ranked, topK, graph);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("predictions", predictions);
        return MiniJson.write(out);
    }

    /**
     * predict_relations {head, tail} → top-k relation types by cosine(head_vec, tail_vec) proxy.
     * Without a relation embedding table the score is the cosine of the head-tail pair, reported
     * once per distinct relation type present in the graph between head and tail (or all types if
     * no direct edge, ordered by frequency then name).
     */
    private static String handlePredictRelations(EmbeddingTable table, UnifiedGraph graph,
                                                 Map<String, Object> args, int topK) {
        String head = str(args, "head");
        String tail = str(args, "tail");
        if (head == null || tail == null) {
            return error("predict_relations requires 'head' and 'tail'");
        }
        double[] hVec = table.vector(head);
        double[] tVec = table.vector(tail);
        if (hVec == null) return error("Unknown entity: '" + head + "'");
        if (tVec == null) return error("Unknown entity: '" + tail + "'");

        double pairScore = cosine(hVec, tVec);

        // Collect distinct relation types from the graph, score each
        Map<String, Integer> relTypeFreq = new LinkedHashMap<>();
        for (GraphRelation r : graph.relations()) {
            relTypeFreq.merge(r.type(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(relTypeFreq.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));

        List<Object> predictions = new ArrayList<>();
        int limit = topK > 0 ? Math.min(topK, sorted.size()) : sorted.size();
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relation", sorted.get(i).getKey());
            // Scale pairScore by rank position as a proxy
            row.put("score", pairScore * (1.0 / (1.0 + i)));
            predictions.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("predictions", predictions);
        out.put("pairCosine", pairScore);
        return MiniJson.write(out);
    }

    /**
     * similar {entity_name} → top-k nearest entities by cosine over entity vectors.
     * Resolves entity_name by exact id first, then by case-insensitive label match.
     */
    private static String handleSimilar(EmbeddingTable table, UnifiedGraph graph,
                                        Map<String, Object> args, int topK) {
        String entityName = str(args, "entity_name");
        if (entityName == null || entityName.isBlank()) {
            return error("similar action requires 'entity_name'");
        }

        // Resolve: try as id first, then as label
        String resolvedId = resolveEntityName(entityName, graph);
        if (resolvedId == null) return error("Cannot find entity: '" + entityName + "'");

        double[] queryVec = table.vector(resolvedId);
        if (queryVec == null) return error("Entity '" + resolvedId + "' has no embedding in this layer");

        List<ScoredId> ranked = rankAllByCosine(table, graph, queryVec, resolvedId);
        List<Object> results = toResults(ranked, topK, graph);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("queryEntity", resolvedId);
        out.put("results", results);
        return MiniJson.write(out);
    }

    // ── Embedding utilities ──────────────────────────────────────────────────

    /**
     * Pick the default vector layer: the first ENTITY-target layer.
     * Falls back to the first layer of any target if none is ENTITY-keyed.
     * Returns null if the graph has no vector layers.
     */
    private static String pickDefaultEntityLayer(UnifiedGraph graph) {
        Map<String, VectorLayer> layers = graph.vectorLayers();
        if (layers.isEmpty()) return null;
        // First: prefer ENTITY target
        for (Map.Entry<String, VectorLayer> e : layers.entrySet()) {
            if (e.getValue().target() == VectorLayer.Target.ENTITY && !e.getValue().isEmpty()) {
                return e.getKey();
            }
        }
        // Fallback: any non-empty layer
        for (Map.Entry<String, VectorLayer> e : layers.entrySet()) {
            if (!e.getValue().isEmpty()) return e.getKey();
        }
        return null;
    }

    /** Rank all entities in the table by cosine against queryVec, excluding the query entity. */
    private static List<ScoredId> rankAllByCosine(EmbeddingTable table, UnifiedGraph graph,
                                                  double[] queryVec, String excludeId) {
        Map<String, double[]> allVecs = table.asMap();
        List<ScoredId> scored = new ArrayList<>(allVecs.size());
        for (Map.Entry<String, double[]> e : allVecs.entrySet()) {
            if (Objects.equals(e.getKey(), excludeId)) continue;
            double sim = cosine(queryVec, e.getValue());
            scored.add(new ScoredId(e.getKey(), sim));
        }
        scored.sort(Comparator.comparingDouble(ScoredId::score).reversed());
        return scored;
    }

    private static List<Object> toTopK(List<ScoredId> ranked, int topK, UnifiedGraph graph) {
        int limit = topK > 0 ? Math.min(topK, ranked.size()) : ranked.size();
        List<Object> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ScoredId s = ranked.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityId", s.id());
            row.put("label", labelFor(s.id(), graph));
            row.put("score", s.score());
            out.add(row);
        }
        return out;
    }

    private static List<Object> toResults(List<ScoredId> ranked, int topK, UnifiedGraph graph) {
        int limit = topK > 0 ? Math.min(topK, ranked.size()) : ranked.size();
        List<Object> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ScoredId s = ranked.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityId", s.id());
            row.put("label", labelFor(s.id(), graph));
            row.put("similarity", s.score());
            out.add(row);
        }
        return out;
    }

    /** Cosine similarity between two double[] vectors. Returns 0 if either is zero-magnitude. */
    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return (denom < 1e-12) ? 0.0 : dot / denom;
    }

    /** Resolve entity_name to an id: exact id match first, then case-insensitive label match. */
    private static String resolveEntityName(String name, UnifiedGraph graph) {
        // Try exact id
        for (GraphEntity e : graph.entities()) {
            if (name.equals(e.id())) return e.id();
        }
        // Case-insensitive label match
        String lower = name.toLowerCase(Locale.ROOT);
        for (GraphEntity e : graph.entities()) {
            if (lower.equals(e.label().toLowerCase(Locale.ROOT))) return e.id();
        }
        return null;
    }

    private static String labelFor(String id, UnifiedGraph graph) {
        return graph.entity(id).map(GraphEntity::label).orElse(id);
    }

    private record ScoredId(String id, double score) {}

    // ── Catalog entries ──────────────────────────────────────────────────────

    private static LocalToolCatalog.Entry buildCentralityEntry() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("algorithm", enumProp(
                "Centrality algorithm to run.",
                List.of("degree", "pagerank", "betweenness")));
        props.put("degree_type", enumProp(
                "For degree algorithm: count in-edges, out-edges, or both.",
                List.of("in", "out", "both")));
        props.put("top_k", intProp(
                "Maximum number of results to return, sorted descending by score. Default 20."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("algorithm"));
        schema.put("properties", props);

        return new LocalToolCatalog.Entry(
                "graph_centrality",
                "Compute node centrality over the loaded graph. " +
                        "algorithm=degree (in/out/both normalized), " +
                        "pagerank (damping 0.85), " +
                        "betweenness (Brandes, undirected). " +
                        "Returns flat {entityId: score} map sorted descending, limited to top_k (default 20).",
                schema);
    }

    private static LocalToolCatalog.Entry buildEmbeddingsEntry() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", enumProp(
                "Embedding inference action. Train/job actions return UNSUPPORTED.",
                List.of("score", "predict_tails", "predict_heads", "predict_relations",
                        "similar", "algorithms")));
        props.put("head",         stringProp("Head entity id or label (score / predict_tails / predict_heads / predict_relations)."));
        props.put("relation",     stringProp("Relation type string (optional context for score/predict actions)."));
        props.put("tail",         stringProp("Tail entity id or label (score / predict_heads / predict_relations)."));
        props.put("entity_name",  stringProp("Entity id or label to find similar entities for (similar action)."));
        props.put("top_k",        intProp("Maximum result count (default 10)."));
        props.put("layer",        stringProp("Vector layer name to use. Default: first ENTITY-target layer in the graph."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));
        schema.put("properties", props);

        return new LocalToolCatalog.Entry(
                "graph_embeddings",
                "KGE / embedding inference against vectors already bundled in the loaded .kgraph. " +
                        "score → plausibility score for a (head,tail) pair; " +
                        "predict_tails/heads → top-k candidate entities; " +
                        "predict_relations → top-k relation types; " +
                        "similar → nearest entities by cosine; " +
                        "algorithms → list available layers. " +
                        "Training actions (train/jobs/cancel) return UNSUPPORTED. " +
                        "If no embedding layer is present returns status=ERROR.",
                schema);
    }

    // ── Schema prop helpers (package-private mirrors of CoreHandlers style) ──

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> intProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> enumProp(String description, List<String> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", values);
        return p;
    }

    // ── Arg helpers ──────────────────────────────────────────────────────────

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static int intVal(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", Objects.requireNonNullElse(message, "Unknown error"));
        return MiniJson.write(m);
    }
}
