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
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Computes per-file git signals for use in relevance ranking:
 * <ul>
 *   <li><b>Recency</b> — normalized position of a file's most recent commit (0.0 oldest, 1.0 newest)</li>
 *   <li><b>Churn</b> — number of commits that touched the file (hot files get higher rank)</li>
 *   <li><b>Authors</b> — number of distinct committers (proxy for centrality/importance)</li>
 * </ul>
 *
 * <p>All three signals are collected in a single {@code git log} pass and cached
 * per session. The ranker consults the cache via static methods — no separate DB
 * table is needed since the data is cheap to recompute at query time.</p>
 *
 * <p>Co-change boosting is also provided: given a set of candidate result files,
 * files that frequently co-change with other candidates get a mutual boost.</p>
 */
public class GitSignals {

    private static final int GIT_LOG_LIMIT = 500;

    private GitSignals() {}

    /**
     * Per-file git metrics.
     */
    public record FileSignals(
            double recency,     // 0.0 (oldest/never) to 1.0 (most recent commit)
            int commitCount,    // total commits touching this file
            int authorCount     // distinct authors
    ) {
        static final FileSignals EMPTY = new FileSignals(0.0, 0, 0);
    }

    // --- Session cache ---

    private static volatile Map<String, FileSignals> signalsCache = null;
    private static volatile Path signalsCacheRoot = null;

    /**
     * Get git signals for a file, computing the cache on first call.
     */
    static FileSignals getSignals(String relPath, Path rootDir) {
        if (relPath == null || rootDir == null) return FileSignals.EMPTY;
        ensureCache(rootDir);
        return signalsCache.getOrDefault(relPath, FileSignals.EMPTY);
    }

    /**
     * Compute a git-recency multiplier for ranking.
     * Range: 1.0 (no data / old) to the specified maxBoost (most recent files).
     */
    static double recencyMultiplier(String relPath, Path rootDir, double maxBoost) {
        FileSignals sig = getSignals(relPath, rootDir);
        if (sig.recency() <= 0) return 1.0;
        // Linear interpolation: 1.0 at recency=0 up to maxBoost at recency=1.0
        return 1.0 + sig.recency() * (maxBoost - 1.0);
    }

    /**
     * Compute a churn-based multiplier for ranking.
     * Files with more commits are hotter/more actively developed.
     * Range: 1.0 (0-1 commits) to 1.2 (high churn).
     */
    static double churnMultiplier(String relPath, Path rootDir) {
        FileSignals sig = getSignals(relPath, rootDir);
        if (sig.commitCount() <= 1) return 1.0;
        // Log scale: diminishing returns above ~20 commits
        double normalized = Math.min(Math.log(sig.commitCount()) / Math.log(50), 1.0);
        return 1.0 + normalized * 0.2; // 1.0 to 1.2
    }

    /**
     * Compute an author-diversity multiplier.
     * Files with many authors are more broadly important.
     * Range: 1.0 (single author) to 1.1 (many authors).
     */
    static double authorMultiplier(String relPath, Path rootDir) {
        FileSignals sig = getSignals(relPath, rootDir);
        if (sig.authorCount() <= 1) return 1.0;
        double normalized = Math.min((sig.authorCount() - 1.0) / 9.0, 1.0); // 2 authors=0.11, 10+=1.0
        return 1.0 + normalized * 0.1; // 1.0 to 1.1
    }

    /**
     * Compute co-change boost for a file relative to a set of other result files.
     * If file X frequently co-changes with files already in the result set,
     * it should be ranked higher.
     *
     * @param relPath      file to check
     * @param resultFiles  other files in the search result set
     * @param indexDir     index directory (for cochange DB table)
     * @return multiplier: 1.0 (no co-change signal) to 1.25 (strong co-change overlap)
     */
    static double coChangeBoost(String relPath, Set<String> resultFiles, Path indexDir) {
        if (relPath == null || resultFiles == null || resultFiles.isEmpty() || indexDir == null) {
            return 1.0;
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            Connection conn = db.getConnection();
            // Check if cochanges table exists
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "cochanges", null)) {
                if (!rs.next()) return 1.0;
            }

            // Count how many result files co-change with this file
            int coChangeHits = 0;
            double totalConfidence = 0;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT file_a, file_b, confidence FROM cochanges " +
                            "WHERE file_a = ? OR file_b = ?")) {
                ps.setString(1, relPath);
                ps.setString(2, relPath);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String partner = rs.getString("file_a").equals(relPath)
                                ? rs.getString("file_b") : rs.getString("file_a");
                        if (resultFiles.contains(partner)) {
                            coChangeHits++;
                            totalConfidence += rs.getDouble("confidence");
                        }
                    }
                }
            }

            if (coChangeHits == 0) return 1.0;

            // Scale: 1 co-change partner with confidence 1.0 → ~1.05x
            //        3+ co-change partners → up to 1.25x
            double normalized = Math.min(totalConfidence / 10.0, 1.0);
            return 1.0 + normalized * 0.25;

        } catch (Exception e) {
            return 1.0;
        }
    }

    // --- Cache management ---

    private static void ensureCache(Path rootDir) {
        if (signalsCache != null && rootDir.equals(signalsCacheRoot)) return;

        synchronized (GitSignals.class) {
            if (signalsCache != null && rootDir.equals(signalsCacheRoot)) return;

            Map<String, FileSignals> cache = computeSignals(rootDir);
            signalsCache = cache;
            signalsCacheRoot = rootDir;
        }
    }

    /**
     * Invalidate the cache (e.g., after re-indexing).
     */
    static void invalidateCache() {
        signalsCache = null;
        signalsCacheRoot = null;
    }

    /**
     * Parse git log in a single pass to extract recency, churn, and author count per file.
     * Uses: git log --all --name-only --format='%ae' --diff-filter=AMCR -N
     * Each commit block looks like:
     *   author@email.com
     *   (blank)
     *   file1.java
     *   file2.java
     *   (blank or next commit)
     */
    private static Map<String, FileSignals> computeSignals(Path rootDir) {
        Map<String, Integer> commitCounts = new HashMap<>();
        Map<String, Set<String>> authorSets = new HashMap<>();
        Map<String, Integer> firstSeenOrder = new HashMap<>(); // lower = more recent
        int commitOrdinal = 0;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--all", "--name-only", "--format=%ae",
                    "--diff-filter=AMCR", "-" + GIT_LOG_LIMIT);
            pb.directory(rootDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String currentAuthor = null;
            boolean expectingFiles = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();

                    if (line.isEmpty()) {
                        if (currentAuthor != null) {
                            expectingFiles = true;
                        }
                        continue;
                    }

                    if (line.contains("@") && !line.contains("/")) {
                        // This is an author email line (new commit)
                        if (currentAuthor != null) {
                            commitOrdinal++;
                        }
                        currentAuthor = line;
                        expectingFiles = false;
                        continue;
                    }

                    // File name
                    if (currentAuthor != null) {
                        commitCounts.merge(line, 1, Integer::sum);
                        authorSets.computeIfAbsent(line, k -> new HashSet<>()).add(currentAuthor);
                        firstSeenOrder.putIfAbsent(line, commitOrdinal);
                    }
                }
            }
            // Count the last commit
            if (currentAuthor != null) {
                commitOrdinal++;
            }

            proc.waitFor();

            // Build FileSignals
            int totalCommits = commitOrdinal;
            Map<String, FileSignals> results = new HashMap<>();

            for (String file : commitCounts.keySet()) {
                int commits = commitCounts.getOrDefault(file, 0);
                int authors = authorSets.containsKey(file) ? authorSets.get(file).size() : 0;
                int order = firstSeenOrder.getOrDefault(file, totalCommits);
                // Recency: 0-based order where 0=most recent commit
                // Normalize so that most-recent=1.0, oldest=0.0
                double recency = totalCommits > 1
                        ? 1.0 - ((double) order / (totalCommits - 1))
                        : 1.0;
                results.put(file, new FileSignals(recency, commits, authors));
            }

            return results;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
