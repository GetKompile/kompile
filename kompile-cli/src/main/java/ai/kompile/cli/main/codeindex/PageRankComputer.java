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

package ai.kompile.cli.main.codeindex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Computes PageRank over the file-level dependency graph extracted from
 * the code index relations table. Produces importance scores used by
 * the relevance ranker to prioritize central, high-impact files.
 *
 * <p>Supports personalized PageRank — teleports to recently-modified files
 * based on git history, making the ranking context-aware for active work.</p>
 *
 * <p>Algorithm: iterative power method with damping factor 0.85, 50 iterations.
 * Edges are weighted by relation type confidence:
 * IMPORTS/EXTENDS/IMPLEMENTS=3, CALLS=1.</p>
 */
public class PageRankComputer {

    private static final int MAX_ITERATIONS = 50;
    private static final double DAMPING = 0.85;
    private static final int GIT_LOG_COMMITS = 300;

    // Confidence weights per relation type (inspired by soulforge's multi-phase edges)
    private static final Map<String, Double> RELATION_WEIGHTS = Map.of(
            "IMPORTS", 3.0,
            "EXTENDS", 3.0,
            "IMPLEMENTS", 3.0,
            "CALLS", 1.0,
            "CONTAINS", 0.5
    );

    private PageRankComputer() {}

    /**
     * Compute PageRank for all indexed files and store in the database.
     *
     * @param indexDir path to the project's index directory (contains index.db)
     * @param rootDir  the project root (for git log personalization)
     * @return number of files ranked
     */
    public static int compute(Path indexDir, Path rootDir) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return computeInternal(db, rootDir);
        } catch (SQLException e) {
            throw new IOException("PageRank computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get top-ranked files by PageRank score.
     */
    public static List<Map<String, Object>> getTopFiles(Path indexDir, int limit) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return queryTopFiles(db, limit);
        } catch (SQLException e) {
            throw new IOException("PageRank query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get a specific file's PageRank score.
     */
    public static double getFileRank(Path indexDir, String relPath) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return queryFileRank(db, relPath);
        } catch (SQLException e) {
            throw new IOException("PageRank query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Check if PageRank has been computed (table exists and has data).
     */
    public static boolean isComputed(Path indexDir) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return hasData(db);
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Internal implementation ---

    static void ensureTable(IndexDatabase db) throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS pagerank (
                    rel_path    TEXT PRIMARY KEY,
                    score       REAL NOT NULL,
                    computed_at TEXT NOT NULL
                )""");
        }
    }

    private static boolean hasData(IndexDatabase db) throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pagerank")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static int computeInternal(IndexDatabase db, Path rootDir) throws SQLException {
        Connection conn = db.getConnection();

        // Step 1: Build file-level adjacency graph from relations
        Map<String, Map<String, Double>> graph = buildFileGraph(conn);
        if (graph.isEmpty()) return 0;

        // Step 2: Get personalization vector from git recency
        Map<String, Double> personalization = getGitRecency(rootDir, graph.keySet());

        // Step 3: Run iterative PageRank
        Map<String, Double> scores = iteratePageRank(graph, personalization);

        // Step 4: Store results
        storeScores(conn, scores);

        return scores.size();
    }

    /**
     * Build file-to-file weighted adjacency from the relations table.
     * Groups entity-level relations into file-level edges with confidence weights.
     */
    private static Map<String, Map<String, Double>> buildFileGraph(Connection conn) throws SQLException {
        Map<String, Map<String, Double>> graph = new HashMap<>();

        // Get file for each entity FQN
        Map<String, String> fqnToFile = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT fqn, rel_path FROM entities_meta")) {
            while (rs.next()) {
                fqnToFile.put(rs.getString("fqn"), rs.getString("rel_path"));
            }
        }

        // Build edges from relations
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT source_fqn, target_fqn, relation_type, file_path FROM relations WHERE target_fqn IS NOT NULL")) {
            while (rs.next()) {
                String sourceFqn = rs.getString("source_fqn");
                String targetFqn = rs.getString("target_fqn");
                String relType = rs.getString("relation_type");
                String sourceFile = rs.getString("file_path");

                String targetFile = fqnToFile.get(targetFqn);
                if (targetFile == null || targetFile.equals(sourceFile)) continue;

                double weight = RELATION_WEIGHTS.getOrDefault(relType, 1.0);

                graph.computeIfAbsent(sourceFile, k -> new HashMap<>())
                        .merge(targetFile, weight, Double::sum);

                // Ensure target is in the graph (even if it has no outgoing edges)
                graph.computeIfAbsent(targetFile, k -> new HashMap<>());
            }
        }

        // Also add files from file_status that have no relations (dangling nodes)
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT rel_path FROM file_status")) {
            while (rs.next()) {
                graph.computeIfAbsent(rs.getString("rel_path"), k -> new HashMap<>());
            }
        }

        return graph;
    }

    /**
     * Get git-recency-based personalization vector.
     * Files modified more recently get higher teleport probability.
     */
    private static Map<String, Double> getGitRecency(Path rootDir, Set<String> indexedFiles) {
        Map<String, Double> recency = new HashMap<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--all", "--name-only", "--pretty=format:",
                    "--diff-filter=AMCR", "-" + GIT_LOG_COMMITS);
            pb.directory(rootDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            Map<String, Integer> fileOrder = new HashMap<>();
            int order = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !fileOrder.containsKey(line)) {
                        fileOrder.put(line, order++);
                    }
                }
            }
            proc.waitFor();

            if (!fileOrder.isEmpty()) {
                int maxOrder = order;
                for (Map.Entry<String, Integer> entry : fileOrder.entrySet()) {
                    if (indexedFiles.contains(entry.getKey())) {
                        // More recent = higher score (linear decay)
                        double score = 1.0 - ((double) entry.getValue() / maxOrder);
                        recency.put(entry.getKey(), score);
                    }
                }
            }
        } catch (Exception ignored) {
            // Git not available or not a git repo — fall back to uniform
        }

        return recency;
    }

    /**
     * Iterative PageRank with personalization.
     */
    private static Map<String, Double> iteratePageRank(
            Map<String, Map<String, Double>> graph, Map<String, Double> personalization) {

        int n = graph.size();
        if (n == 0) return Map.of();

        List<String> nodes = new ArrayList<>(graph.keySet());
        Map<String, Integer> nodeIndex = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndex.put(nodes.get(i), i);
        }

        // Initialize scores uniformly
        double[] scores = new double[n];
        Arrays.fill(scores, 1.0 / n);

        // Build personalization vector
        double[] teleport = new double[n];
        if (personalization.isEmpty()) {
            Arrays.fill(teleport, 1.0 / n);
        } else {
            double sum = 0;
            for (String node : nodes) {
                double val = personalization.getOrDefault(node, 0.1); // small baseline
                teleport[nodeIndex.get(node)] = val;
                sum += val;
            }
            // Normalize
            for (int i = 0; i < n; i++) teleport[i] /= sum;
        }

        // Precompute outgoing weights per node
        double[][] outWeights = new double[n][];
        int[][] outTargets = new int[n][];
        for (int i = 0; i < n; i++) {
            Map<String, Double> edges = graph.get(nodes.get(i));
            if (edges == null || edges.isEmpty()) {
                outWeights[i] = new double[0];
                outTargets[i] = new int[0];
            } else {
                outWeights[i] = new double[edges.size()];
                outTargets[i] = new int[edges.size()];
                double totalWeight = edges.values().stream().mapToDouble(Double::doubleValue).sum();
                int j = 0;
                for (Map.Entry<String, Double> edge : edges.entrySet()) {
                    Integer targetIdx = nodeIndex.get(edge.getKey());
                    if (targetIdx != null) {
                        outTargets[i][j] = targetIdx;
                        outWeights[i][j] = edge.getValue() / totalWeight;
                        j++;
                    }
                }
                // Trim if some targets weren't in the graph
                if (j < outWeights[i].length) {
                    outWeights[i] = Arrays.copyOf(outWeights[i], j);
                    outTargets[i] = Arrays.copyOf(outTargets[i], j);
                }
            }
        }

        // Power iteration
        double[] newScores = new double[n];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            Arrays.fill(newScores, 0);

            // Distribute scores along edges
            double danglingSum = 0;
            for (int i = 0; i < n; i++) {
                if (outTargets[i].length == 0) {
                    danglingSum += scores[i];
                } else {
                    for (int j = 0; j < outTargets[i].length; j++) {
                        newScores[outTargets[i][j]] += scores[i] * outWeights[i][j];
                    }
                }
            }

            // Apply damping + teleport + dangling redistribution
            for (int i = 0; i < n; i++) {
                newScores[i] = DAMPING * (newScores[i] + danglingSum * teleport[i])
                        + (1.0 - DAMPING) * teleport[i];
            }

            // Swap
            System.arraycopy(newScores, 0, scores, 0, n);
        }

        // Build result map
        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            result.put(nodes.get(i), scores[i]);
        }
        return result;
    }

    private static void storeScores(Connection conn, Map<String, Double> scores) throws SQLException {
        String now = Instant.now().toString();
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM pagerank");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pagerank (rel_path, score, computed_at) VALUES (?, ?, ?)")) {
            int batch = 0;
            for (Map.Entry<String, Double> entry : scores.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setDouble(2, entry.getValue());
                ps.setString(3, now);
                ps.addBatch();
                if (++batch % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static List<Map<String, Object>> queryTopFiles(IndexDatabase db, int limit) throws SQLException {
        Connection conn = db.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT p.rel_path, p.score, p.computed_at FROM pagerank p ORDER BY p.score DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("relPath", rs.getString("rel_path"));
                    row.put("score", rs.getDouble("score"));
                    row.put("computedAt", rs.getString("computed_at"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    private static double queryFileRank(IndexDatabase db, String relPath) throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT score FROM pagerank WHERE rel_path = ?")) {
            ps.setString(1, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("score");
            }
        }
        return 0.0;
    }

    /**
     * Format top files for display.
     */
    public static String formatTopFiles(List<Map<String, Object>> files) {
        if (files.isEmpty()) return "No PageRank data. Run 'index' first to compute.";
        StringBuilder sb = new StringBuilder();
        sb.append("Top files by PageRank importance:\n\n");
        int i = 0;
        for (Map<String, Object> f : files) {
            i++;
            sb.append(String.format("  %3d. %s  (PR: %.6f)\n", i, f.get("relPath"), f.get("score")));
        }
        return sb.toString();
    }
}
