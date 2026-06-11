/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.cli.main.codeindex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes git history to find files that frequently change together.
 * Co-change relationships capture implicit coupling not visible in import
 * analysis — e.g., a test file that always changes with its implementation,
 * or a config file that accompanies schema changes.
 *
 * <p>Scans the last N commits (default 300), groups files per commit,
 * and builds pairwise co-occurrence counts. Files that co-change above
 * a threshold are stored as co-change edges.</p>
 *
 * <p>Results are used by:
 * <ul>
 *   <li>ImpactAnalyzer — co-change partners in blast radius</li>
 *   <li>CodeRelevanceRanker — boost co-change neighbors in search results</li>
 *   <li>PageRankComputer — co-change edges as confidence=2 graph edges</li>
 * </ul></p>
 */
public class CoChangeAnalyzer {

    private static final int DEFAULT_COMMIT_LIMIT = 300;
    private static final int MAX_FILES_PER_COMMIT = 20; // Skip huge commits (merges, reformats)
    private static final int MIN_COCHANGE_COUNT = 2;    // Minimum co-occurrences to store

    private CoChangeAnalyzer() {}

    /**
     * Analyze git co-changes and store results in the index database.
     *
     * @param indexDir    project index directory (contains index.db)
     * @param rootDir     project root (git repository)
     * @param commitLimit max commits to analyze (default 300)
     * @return analysis results
     */
    public static CoChangeReport analyze(Path indexDir, Path rootDir, int commitLimit) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return analyzeInternal(db, rootDir, commitLimit > 0 ? commitLimit : DEFAULT_COMMIT_LIMIT);
        } catch (SQLException e) {
            throw new IOException("Co-change analysis failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get co-change partners for a specific file.
     */
    public static List<CoChangePair> getCoChanges(Path indexDir, String relPath) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return queryCoChanges(db, relPath);
        } catch (SQLException e) {
            throw new IOException("Co-change query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get top co-change pairs across the entire codebase.
     */
    public static List<CoChangePair> getTopCoChanges(Path indexDir, int limit) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            return queryTopCoChanges(db, limit);
        } catch (SQLException e) {
            throw new IOException("Co-change query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Check if co-change data exists.
     */
    public static boolean hasData(Path indexDir) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTable(db);
            Connection conn = db.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM cochanges")) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Data classes ---

    public record CoChangePair(
            String fileA,
            String fileB,
            int cochangeCount,
            double confidence
    ) {}

    public record CoChangeReport(
            int commitsAnalyzed,
            int pairsFound,
            int filesInvolved,
            long elapsedMs
    ) {}

    // --- Internal ---

    static void ensureTable(IndexDatabase db) throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cochanges (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_a      TEXT NOT NULL,
                    file_b      TEXT NOT NULL,
                    count       INTEGER NOT NULL,
                    confidence  REAL NOT NULL,
                    computed_at TEXT NOT NULL,
                    UNIQUE(file_a, file_b)
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cochanges_a ON cochanges(file_a)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cochanges_b ON cochanges(file_b)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cochanges_count ON cochanges(count DESC)");
        }
    }

    private static CoChangeReport analyzeInternal(IndexDatabase db, Path rootDir, int commitLimit)
            throws SQLException, IOException {
        long start = System.currentTimeMillis();
        Connection conn = db.getConnection();

        // Get indexed files for filtering
        Set<String> indexedFiles = db.getAllRelPaths();

        // Parse git log
        List<Set<String>> commits = parseGitLog(rootDir, commitLimit, indexedFiles);

        // Count pairwise co-occurrences
        Map<String, Map<String, Integer>> pairCounts = new HashMap<>();
        int commitsAnalyzed = 0;

        for (Set<String> commitFiles : commits) {
            if (commitFiles.size() < 2 || commitFiles.size() > MAX_FILES_PER_COMMIT) continue;
            commitsAnalyzed++;

            List<String> files = new ArrayList<>(commitFiles);
            for (int i = 0; i < files.size(); i++) {
                for (int j = i + 1; j < files.size(); j++) {
                    String a = files.get(i);
                    String b = files.get(j);
                    // Canonical ordering
                    if (a.compareTo(b) > 0) { String tmp = a; a = b; b = tmp; }
                    pairCounts.computeIfAbsent(a, k -> new HashMap<>())
                            .merge(b, 1, Integer::sum);
                }
            }
        }

        // Filter and store
        List<CoChangePair> pairs = new ArrayList<>();
        Set<String> involvedFiles = new HashSet<>();

        for (Map.Entry<String, Map<String, Integer>> outer : pairCounts.entrySet()) {
            String fileA = outer.getKey();
            for (Map.Entry<String, Integer> inner : outer.getValue().entrySet()) {
                String fileB = inner.getKey();
                int count = inner.getValue();
                if (count >= MIN_COCHANGE_COUNT) {
                    // Confidence = normalized co-change strength
                    // Scale: 2 co-changes = 1.0, 5+ = 2.5+ (capped)
                    double confidence = Math.min(count * 0.5, 5.0);
                    pairs.add(new CoChangePair(fileA, fileB, count, confidence));
                    involvedFiles.add(fileA);
                    involvedFiles.add(fileB);
                }
            }
        }

        // Store in database
        storePairs(conn, pairs);

        long elapsed = System.currentTimeMillis() - start;
        return new CoChangeReport(commitsAnalyzed, pairs.size(), involvedFiles.size(), elapsed);
    }

    /**
     * Parse git log to extract per-commit file lists.
     */
    private static List<Set<String>> parseGitLog(Path rootDir, int commitLimit, Set<String> indexedFiles)
            throws IOException {
        List<Set<String>> commits = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--all", "--name-only", "--pretty=format:---COMMIT---",
                    "--diff-filter=AMCR", "-" + commitLimit);
            pb.directory(rootDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            Set<String> currentCommit = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("---COMMIT---")) {
                        if (!currentCommit.isEmpty()) {
                            commits.add(currentCommit);
                            currentCommit = new HashSet<>();
                        }
                    } else if (!line.isEmpty()) {
                        // Only include files that are in the index
                        if (indexedFiles.contains(line)) {
                            currentCommit.add(line);
                        }
                    }
                }
            }
            // Don't forget the last commit
            if (!currentCommit.isEmpty()) {
                commits.add(currentCommit);
            }

            proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Git not available or not a git repo
            return List.of();
        }

        return commits;
    }

    private static void storePairs(Connection conn, List<CoChangePair> pairs) throws SQLException {
        String now = Instant.now().toString();
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM cochanges");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cochanges (file_a, file_b, count, confidence, computed_at) VALUES (?, ?, ?, ?, ?)")) {
            int batch = 0;
            for (CoChangePair pair : pairs) {
                ps.setString(1, pair.fileA());
                ps.setString(2, pair.fileB());
                ps.setInt(3, pair.cochangeCount());
                ps.setDouble(4, pair.confidence());
                ps.setString(5, now);
                ps.addBatch();
                if (++batch % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    // --- Queries ---

    private static List<CoChangePair> queryCoChanges(IndexDatabase db, String relPath) throws SQLException {
        Connection conn = db.getConnection();
        List<CoChangePair> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT file_a, file_b, count, confidence FROM cochanges " +
                        "WHERE file_a = ? OR file_b = ? ORDER BY count DESC")) {
            ps.setString(1, relPath);
            ps.setString(2, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CoChangePair(
                            rs.getString("file_a"), rs.getString("file_b"),
                            rs.getInt("count"), rs.getDouble("confidence")));
                }
            }
        }
        return results;
    }

    private static List<CoChangePair> queryTopCoChanges(IndexDatabase db, int limit) throws SQLException {
        Connection conn = db.getConnection();
        List<CoChangePair> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT file_a, file_b, count, confidence FROM cochanges " +
                        "ORDER BY count DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CoChangePair(
                            rs.getString("file_a"), rs.getString("file_b"),
                            rs.getInt("count"), rs.getDouble("confidence")));
                }
            }
        }
        return results;
    }

    /**
     * Format co-change report for display.
     */
    public static String formatReport(CoChangeReport report, List<CoChangePair> topPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Co-Change Analysis Report\n\n");
        sb.append(String.format("- Commits analyzed: %d\n", report.commitsAnalyzed()));
        sb.append(String.format("- Co-change pairs found: %d\n", report.pairsFound()));
        sb.append(String.format("- Files involved: %d\n", report.filesInvolved()));
        sb.append(String.format("- Elapsed: %dms\n\n", report.elapsedMs()));

        if (!topPairs.isEmpty()) {
            sb.append("Top co-change pairs (files that change together most often):\n\n");
            for (CoChangePair pair : topPairs) {
                sb.append(String.format("  %3d co-commits: %s\n                   \u2194 %s\n",
                        pair.cochangeCount(), pair.fileA(), pair.fileB()));
            }
        }

        return sb.toString();
    }
}
