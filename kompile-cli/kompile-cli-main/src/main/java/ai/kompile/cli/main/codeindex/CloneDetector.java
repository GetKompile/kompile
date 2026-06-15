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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MinHash-based code clone detection. Identifies near-duplicate functions
 * and repeated code fragments across the indexed codebase.
 *
 * <p>Token normalization strips identifiers, strings, and numbers to their
 * structural roles, allowing detection of clones that differ only in naming.
 * Uses 128-hash MinHash signatures with 3-token shingles for Jaccard estimation,
 * plus 12-token sliding window fragment hashes for repeated patterns.</p>
 *
 * <p>Inspired by soulforge's clone-detection.ts approach, adapted for Java.</p>
 */
public class CloneDetector {

    private static final int NUM_HASHES = 128;
    private static final int SHINGLE_K = 3;
    private static final int FRAGMENT_WINDOW = 12;
    private static final int MIN_FRAGMENT_TOKENS = FRAGMENT_WINDOW + 4;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.8;

    // Token regex: identifiers, numbers (hex/bin/oct/dec), strings, operators
    private static final Pattern TOKEN_RE = Pattern.compile(
            "[a-zA-Z_$]\\w*|0[xXbBoO][\\da-fA-F_]+|\\d[\\d_.]*(?:[eE][+-]?\\d+)?[fFdDlLuU]?" +
                    "|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`|[^\\s\\w]");

    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
            "return", "throw", "try", "catch", "finally", "new", "delete", "typeof",
            "instanceof", "in", "of", "class", "extends", "implements", "interface",
            "enum", "const", "let", "var", "function", "async", "await", "yield",
            "import", "export", "from", "default", "static", "public", "private",
            "protected", "abstract", "override", "readonly", "void", "null",
            "undefined", "true", "false", "this", "super",
            // Python
            "def", "self", "lambda", "with", "as", "is", "not", "and", "or",
            "None", "True", "False", "pass", "raise", "except",
            // Rust
            "fn", "pub", "mut", "impl", "struct", "trait", "mod", "use", "crate",
            "match", "loop",
            // Go
            "func", "go", "chan", "select", "defer", "range", "type", "package",
            // Java extras
            "final", "synchronized", "volatile", "transient", "native",
            "int", "long", "short", "byte", "char", "float", "double", "boolean"
    );

    private CloneDetector() {}

    /**
     * Detect clones across all indexed functions/methods in the project.
     *
     * @param indexDir  project index directory
     * @param rootDir   project root (for reading source files)
     * @param threshold similarity threshold (0.0-1.0, default 0.8)
     * @return detection results
     */
    public static CloneReport detect(Path indexDir, Path rootDir, double threshold) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTables(db);
            return detectInternal(db, rootDir, threshold);
        } catch (SQLException e) {
            throw new IOException("Clone detection failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get previously computed clone results from the database.
     */
    public static List<ClonePair> getClones(Path indexDir, int limit) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTables(db);
            return queryClones(db, limit);
        } catch (SQLException e) {
            throw new IOException("Clone query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get repeated fragments from the database.
     */
    public static List<FragmentCluster> getFragments(Path indexDir, int limit) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTables(db);
            return queryFragments(db, limit);
        } catch (SQLException e) {
            throw new IOException("Fragment query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get clones for a specific file.
     */
    public static List<ClonePair> getClonesForFile(Path indexDir, String relPath) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            ensureTables(db);
            return queryClonesForFile(db, relPath);
        } catch (SQLException e) {
            throw new IOException("Clone query failed: " + e.getMessage(), e);
        }
    }

    // --- Data classes ---

    public record ClonePair(
            String fileA, String nameA, int lineA, int endLineA,
            String fileB, String nameB, int lineB, int endLineB,
            double similarity
    ) {}

    public record FragmentCluster(
            String fragmentHash,
            int occurrences,
            List<FragmentLocation> locations
    ) {}

    public record FragmentLocation(String filePath, String functionName, int line) {}

    public record CloneReport(
            int functionsAnalyzed,
            int clonePairsFound,
            int fragmentClustersFound,
            long elapsedMs
    ) {}

    // --- Internal ---

    static void ensureTables(IndexDatabase db) throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS clones (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_a      TEXT NOT NULL,
                    name_a      TEXT NOT NULL,
                    line_a      INTEGER NOT NULL,
                    end_line_a  INTEGER NOT NULL,
                    file_b      TEXT NOT NULL,
                    name_b      TEXT NOT NULL,
                    line_b      INTEGER NOT NULL,
                    end_line_b  INTEGER NOT NULL,
                    similarity  REAL NOT NULL,
                    computed_at TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS fragments (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    fragment_hash  TEXT NOT NULL,
                    file_path      TEXT NOT NULL,
                    function_name  TEXT NOT NULL,
                    token_offset   INTEGER NOT NULL,
                    line           INTEGER NOT NULL,
                    computed_at    TEXT NOT NULL
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_clones_file_a ON clones(file_a)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_clones_file_b ON clones(file_b)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_fragments_hash ON fragments(fragment_hash)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_fragments_file ON fragments(file_path)");
        }
    }

    private static CloneReport detectInternal(IndexDatabase db, Path rootDir, double threshold)
            throws SQLException, IOException {
        long start = System.currentTimeMillis();
        Connection conn = db.getConnection();

        // Clear previous results
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM clones");
            stmt.execute("DELETE FROM fragments");
        }

        // Get all function/method entities
        List<FunctionEntity> functions = getFunctions(conn);

        // Compute MinHash for each function
        List<FunctionSignature> signatures = new ArrayList<>();
        for (FunctionEntity func : functions) {
            Path filePath = rootDir.resolve(func.relPath());
            if (!Files.exists(filePath)) continue;

            String source = extractFunctionSource(filePath, func.startLine(), func.endLine());
            if (source == null || source.isBlank()) continue;

            List<String> tokens = tokenize(source);
            if (tokens.size() < SHINGLE_K + 2) continue;

            int[] minhash = computeMinHash(tokens);
            if (minhash == null) continue;

            List<FragmentHash> fragmentHashes = computeFragmentHashes(tokens);
            signatures.add(new FunctionSignature(func, minhash, tokens, fragmentHashes));
        }

        // Find clone pairs
        List<ClonePair> clonePairs = new ArrayList<>();
        for (int i = 0; i < signatures.size(); i++) {
            for (int j = i + 1; j < signatures.size(); j++) {
                double sim = jaccardSimilarity(signatures.get(i).minhash(), signatures.get(j).minhash());
                if (sim >= threshold) {
                    FunctionEntity a = signatures.get(i).entity();
                    FunctionEntity b = signatures.get(j).entity();
                    clonePairs.add(new ClonePair(
                            a.relPath(), a.name(), a.startLine(), a.endLine(),
                            b.relPath(), b.name(), b.startLine(), b.endLine(),
                            sim));
                }
            }
        }

        // Find repeated fragments
        Map<String, List<FragmentLocation>> fragmentMap = new HashMap<>();
        for (FunctionSignature sig : signatures) {
            for (FragmentHash fh : sig.fragmentHashes()) {
                int line = sig.entity().startLine() + estimateLineFromToken(sig.tokens(), fh.tokenOffset());
                fragmentMap.computeIfAbsent(fh.hash(), k -> new ArrayList<>())
                        .add(new FragmentLocation(sig.entity().relPath(), sig.entity().name(), line));
            }
        }

        // Filter to fragments that appear 3+ times across different functions
        List<FragmentCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<FragmentLocation>> entry : fragmentMap.entrySet()) {
            List<FragmentLocation> locs = entry.getValue();
            // Deduplicate by function (same function can't be a "repeated pattern")
            Set<String> uniqueFunctions = new HashSet<>();
            List<FragmentLocation> uniqueLocs = new ArrayList<>();
            for (FragmentLocation loc : locs) {
                String key = loc.filePath() + ":" + loc.functionName();
                if (uniqueFunctions.add(key)) {
                    uniqueLocs.add(loc);
                }
            }
            if (uniqueLocs.size() >= 3) {
                clusters.add(new FragmentCluster(entry.getKey(), uniqueLocs.size(), uniqueLocs));
            }
        }

        // Store results
        storeClones(conn, clonePairs);
        storeFragments(conn, clusters);

        long elapsed = System.currentTimeMillis() - start;
        return new CloneReport(signatures.size(), clonePairs.size(), clusters.size(), elapsed);
    }

    // --- Tokenization ---

    static List<String> tokenize(String source) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_RE.matcher(source);
        while (matcher.find()) {
            tokens.add(normalizeToken(matcher.group()));
        }
        return tokens;
    }

    static String normalizeToken(String token) {
        if (token.isEmpty()) return token;
        char first = token.charAt(0);
        if (first == '"' || first == '\'' || first == '`') return "$S";
        if (Character.isDigit(first) || (first == '0' && token.length() > 1 &&
                "xXbBoO".indexOf(token.charAt(1)) >= 0)) return "$N";
        if (Character.isLetter(first) || first == '_' || first == '$') {
            return KEYWORDS.contains(token) ? token : "$I";
        }
        return token;
    }

    // --- MinHash ---

    static int[] computeMinHash(List<String> tokens) {
        if (tokens.size() < SHINGLE_K + 2) return null;

        int[] sig = new int[NUM_HASHES];
        Arrays.fill(sig, Integer.MAX_VALUE);

        int shingleCount = tokens.size() - SHINGLE_K + 1;
        for (int s = 0; s < shingleCount; s++) {
            String shingle = tokens.get(s) + "\0" + tokens.get(s + 1) + "\0" + tokens.get(s + 2);
            byte[] bytes = shingle.getBytes(StandardCharsets.UTF_8);

            for (int h = 0; h < NUM_HASHES; h++) {
                int v = murmurHash(bytes, h);
                if (v < sig[h]) sig[h] = v;
            }
        }

        return sig;
    }

    static double jaccardSimilarity(int[] a, int[] b) {
        int matches = 0;
        for (int i = 0; i < NUM_HASHES; i++) {
            if (a[i] == b[i]) matches++;
        }
        return (double) matches / NUM_HASHES;
    }

    // --- Fragment hashing ---

    static List<FragmentHash> computeFragmentHashes(List<String> tokens) {
        if (tokens.size() < MIN_FRAGMENT_TOKENS) return List.of();

        List<FragmentHash> results = new ArrayList<>();
        int windowCount = tokens.size() - FRAGMENT_WINDOW + 1;
        for (int i = 0; i < windowCount; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < i + FRAGMENT_WINDOW; j++) {
                if (j > i) sb.append('\0');
                sb.append(tokens.get(j));
            }
            String hash = sha256Short(sb.toString());
            results.add(new FragmentHash(hash, i));
        }
        return results;
    }

    // --- Helpers ---

    private static String extractFunctionSource(Path file, int startLine, int endLine) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (startLine < 1 || startLine > lines.size()) return null;

            // When start_line == end_line (common: indexer records declaration line only),
            // scan forward from startLine to find the method body using brace matching.
            int effectiveEnd = endLine;
            if (endLine <= startLine) {
                effectiveEnd = findMethodEnd(lines, startLine - 1);
            }

            StringBuilder sb = new StringBuilder();
            for (int i = startLine - 1; i < Math.min(effectiveEnd, lines.size()); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Scan forward from {@code startIdx} (0-based) to find the closing brace of a method body.
     * Returns the 1-based end line (exclusive upper bound for extraction).
     * Falls back to startIdx + 30 if no braces found.
     */
    private static int findMethodEnd(List<String> lines, int startIdx) {
        int depth = 0;
        boolean seenOpen = false;
        for (int i = startIdx; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') { depth++; seenOpen = true; }
                else if (ch == '}') { depth--; }
                if (seenOpen && depth == 0) {
                    return i + 1; // 1-based exclusive
                }
            }
        }
        // Fallback: take up to 30 lines from start
        return Math.min(startIdx + 30, lines.size());
    }

    private static int estimateLineFromToken(List<String> tokens, int tokenOffset) {
        // Rough estimate: assume ~10 tokens per line
        return tokenOffset / 10;
    }

    private static List<FunctionEntity> getFunctions(Connection conn) throws SQLException {
        List<FunctionEntity> funcs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT rel_path, name, start_line, end_line FROM entities_meta " +
                             "WHERE entity_type IN ('METHOD', 'FUNCTION', 'CONSTRUCTOR') " +
                             "AND start_line IS NOT NULL AND end_line IS NOT NULL")) {
            while (rs.next()) {
                funcs.add(new FunctionEntity(
                        rs.getString("rel_path"),
                        rs.getString("name"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line")));
            }
        }
        return funcs;
    }

    /**
     * Simple murmur-style hash for MinHash. Produces a deterministic
     * 32-bit hash varied by seed.
     */
    private static int murmurHash(byte[] data, int seed) {
        int h = seed ^ data.length;
        int i = 0;
        while (i + 4 <= data.length) {
            int k = (data[i] & 0xFF) | ((data[i+1] & 0xFF) << 8) |
                    ((data[i+2] & 0xFF) << 16) | ((data[i+3] & 0xFF) << 24);
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
            i += 4;
        }
        int remaining = 0;
        switch (data.length - i) {
            case 3: remaining |= (data[i+2] & 0xFF) << 16;
            case 2: remaining |= (data[i+1] & 0xFF) << 8;
            case 1: remaining |= (data[i] & 0xFF);
                remaining *= 0xcc9e2d51;
                remaining = Integer.rotateLeft(remaining, 15);
                remaining *= 0x1b873593;
                h ^= remaining;
        }
        h ^= data.length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private static String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // Use first 8 bytes as hex (64-bit hash)
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Storage ---

    private static void storeClones(Connection conn, List<ClonePair> pairs) throws SQLException {
        String now = Instant.now().toString();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO clones (file_a, name_a, line_a, end_line_a, file_b, name_b, line_b, end_line_b, similarity, computed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (ClonePair pair : pairs) {
                ps.setString(1, pair.fileA());
                ps.setString(2, pair.nameA());
                ps.setInt(3, pair.lineA());
                ps.setInt(4, pair.endLineA());
                ps.setString(5, pair.fileB());
                ps.setString(6, pair.nameB());
                ps.setInt(7, pair.lineB());
                ps.setInt(8, pair.endLineB());
                ps.setDouble(9, pair.similarity());
                ps.setString(10, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void storeFragments(Connection conn, List<FragmentCluster> clusters) throws SQLException {
        String now = Instant.now().toString();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO fragments (fragment_hash, file_path, function_name, token_offset, line, computed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)")) {
            for (FragmentCluster cluster : clusters) {
                for (FragmentLocation loc : cluster.locations()) {
                    ps.setString(1, cluster.fragmentHash());
                    ps.setString(2, loc.filePath());
                    ps.setString(3, loc.functionName());
                    ps.setInt(4, 0);
                    ps.setInt(5, loc.line());
                    ps.setString(6, now);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    // --- Queries ---

    private static List<ClonePair> queryClones(IndexDatabase db, int limit) throws SQLException {
        Connection conn = db.getConnection();
        List<ClonePair> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM clones ORDER BY similarity DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ClonePair(
                            rs.getString("file_a"), rs.getString("name_a"),
                            rs.getInt("line_a"), rs.getInt("end_line_a"),
                            rs.getString("file_b"), rs.getString("name_b"),
                            rs.getInt("line_b"), rs.getInt("end_line_b"),
                            rs.getDouble("similarity")));
                }
            }
        }
        return results;
    }

    private static List<ClonePair> queryClonesForFile(IndexDatabase db, String relPath) throws SQLException {
        Connection conn = db.getConnection();
        List<ClonePair> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM clones WHERE file_a = ? OR file_b = ? ORDER BY similarity DESC")) {
            ps.setString(1, relPath);
            ps.setString(2, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ClonePair(
                            rs.getString("file_a"), rs.getString("name_a"),
                            rs.getInt("line_a"), rs.getInt("end_line_a"),
                            rs.getString("file_b"), rs.getString("name_b"),
                            rs.getInt("line_b"), rs.getInt("end_line_b"),
                            rs.getDouble("similarity")));
                }
            }
        }
        return results;
    }

    private static List<FragmentCluster> queryFragments(IndexDatabase db, int limit) throws SQLException {
        Connection conn = db.getConnection();
        List<FragmentCluster> results = new ArrayList<>();

        // Get top fragment hashes by occurrence count
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fragment_hash, COUNT(*) as cnt FROM fragments " +
                        "GROUP BY fragment_hash HAVING cnt >= 3 ORDER BY cnt DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String hash = rs.getString("fragment_hash");
                    int count = rs.getInt("cnt");

                    // Get locations for this fragment
                    List<FragmentLocation> locs = new ArrayList<>();
                    try (PreparedStatement locPs = conn.prepareStatement(
                            "SELECT file_path, function_name, line FROM fragments WHERE fragment_hash = ?")) {
                        locPs.setString(1, hash);
                        try (ResultSet locRs = locPs.executeQuery()) {
                            while (locRs.next()) {
                                locs.add(new FragmentLocation(
                                        locRs.getString("file_path"),
                                        locRs.getString("function_name"),
                                        locRs.getInt("line")));
                            }
                        }
                    }
                    results.add(new FragmentCluster(hash, count, locs));
                }
            }
        }
        return results;
    }

    /**
     * Format clone report for display.
     */
    public static String formatReport(CloneReport report, List<ClonePair> topClones, List<FragmentCluster> topFragments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Clone Detection Report\n\n");
        sb.append(String.format("- Functions analyzed: %d\n", report.functionsAnalyzed()));
        sb.append(String.format("- Clone pairs found: %d\n", report.clonePairsFound()));
        sb.append(String.format("- Fragment clusters: %d\n", report.fragmentClustersFound()));
        sb.append(String.format("- Elapsed: %dms\n\n", report.elapsedMs()));

        if (!topClones.isEmpty()) {
            sb.append("Near-duplicate functions (>80% similarity):\n\n");
            for (ClonePair pair : topClones) {
                sb.append(String.format("  %d%% — %s:%d %s\n       ↔ %s:%d %s\n\n",
                        Math.round(pair.similarity() * 100),
                        pair.fileA(), pair.lineA(), pair.nameA(),
                        pair.fileB(), pair.lineB(), pair.nameB()));
            }
        }

        if (!topFragments.isEmpty()) {
            sb.append("Repeated code fragments (3+ occurrences):\n\n");
            for (FragmentCluster cluster : topFragments) {
                sb.append(String.format("  Pattern — %d occurrences:\n", cluster.occurrences()));
                for (FragmentLocation loc : cluster.locations().subList(0, Math.min(6, cluster.locations().size()))) {
                    sb.append(String.format("    %s:%d in %s\n", loc.filePath(), loc.line(), loc.functionName()));
                }
                if (cluster.locations().size() > 6) {
                    sb.append(String.format("    + %d more\n", cluster.locations().size() - 6));
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // --- Internal records ---

    private record FunctionEntity(String relPath, String name, int startLine, int endLine) {}
    private record FunctionSignature(FunctionEntity entity, int[] minhash, List<String> tokens, List<FragmentHash> fragmentHashes) {}
    record FragmentHash(String hash, int tokenOffset) {}
}
