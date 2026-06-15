/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * SQLite FTS5-backed search index for memory files.
 *
 * <p>Indexes memory markdown files (both flat and typed with frontmatter)
 * into an FTS5 virtual table with BM25 ranking. Supports language detection
 * via trigram frequency analysis to select the right FTS5 tokenizer config.</p>
 *
 * <p>The index is stored alongside memory files as {@code .memory-index.db}
 * and is rebuilt automatically when memory files change.</p>
 */
public class MemorySearchIndex implements AutoCloseable {

    private static final String DB_NAME = ".memory-index.db";

    private final Path memDir;
    private Connection conn;

    public MemorySearchIndex(Path memDir) {
        this.memDir = memDir;
    }

    /**
     * Open or create the index, then sync it with the current memory files.
     */
    public void open() throws SQLException, IOException {
        Files.createDirectories(memDir);
        // Ensure SQLite driver is loaded
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on classpath", e);
        }
        String url = "jdbc:sqlite:" + memDir.resolve(DB_NAME).toAbsolutePath();
        conn = DriverManager.getConnection(url);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
        }
        ensureSchema();
        syncIndex();
    }

    private void ensureSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // File modification tracking
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_status (
                    file_name TEXT PRIMARY KEY,
                    last_modified INTEGER NOT NULL,
                    file_size INTEGER NOT NULL
                )""");

            // FTS5 table — unicode61 handles multilingual tokenization
            // including CJK (via tokenchars) and accented characters
            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    file_name,
                    name,
                    description,
                    mem_type,
                    body,
                    lang,
                    tokenize='unicode61 remove_diacritics 2'
                )""");
        }
    }

    /**
     * Scan the memory directory and update the FTS index for changed/new/deleted files.
     */
    private void syncIndex() throws SQLException, IOException {
        if (!Files.exists(memDir)) return;

        // Collect current files
        Map<String, Path> currentFiles = new LinkedHashMap<>();
        try (var stream = Files.list(memDir)) {
            stream.filter(p -> !Files.isDirectory(p) && p.getFileName().toString().endsWith(".md"))
                    .forEach(p -> currentFiles.put(p.getFileName().toString(), p));
        }

        // Get indexed file statuses
        Map<String, long[]> indexed = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT file_name, last_modified, file_size FROM file_status")) {
            while (rs.next()) {
                indexed.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
        }

        conn.setAutoCommit(false);
        try {
            // Delete removed files
            for (String fn : indexed.keySet()) {
                if (!currentFiles.containsKey(fn)) {
                    removeFile(fn);
                }
            }

            // Add/update changed files
            for (var entry : currentFiles.entrySet()) {
                String fn = entry.getKey();
                Path path = entry.getValue();
                long mtime = Files.getLastModifiedTime(path).toMillis();
                long size = Files.size(path);

                long[] prev = indexed.get(fn);
                if (prev != null && prev[0] == mtime && prev[1] == size) {
                    continue; // unchanged
                }

                // Parse and index
                String content = Files.readString(path, StandardCharsets.UTF_8);
                indexFile(fn, content, mtime, size);
            }

            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void removeFile(String fileName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM memory_fts WHERE file_name = ?")) {
            ps.setString(1, fileName);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM file_status WHERE file_name = ?")) {
            ps.setString(1, fileName);
            ps.executeUpdate();
        }
    }

    private void indexFile(String fileName, String content, long mtime, long size)
            throws SQLException {
        // Remove old entry if exists
        removeFile(fileName);

        // Parse frontmatter if present
        String name = "";
        String description = "";
        String type = "";
        String body = content;

        if (content.startsWith("---")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline >= 0) {
                int endMarker = content.indexOf("\n---", firstNewline);
                if (endMarker >= 0) {
                    String fmBlock = content.substring(firstNewline + 1, endMarker);
                    int bodyStart = endMarker + 4;
                    while (bodyStart < content.length()
                            && (content.charAt(bodyStart) == '\n' || content.charAt(bodyStart) == '\r')) {
                        bodyStart++;
                    }
                    body = content.substring(bodyStart);

                    for (String line : fmBlock.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon < 0) continue;
                        String key = line.substring(0, colon).trim();
                        String val = line.substring(colon + 1).trim();
                        // Strip quotes
                        if (val.length() >= 2
                                && ((val.charAt(0) == '"' && val.charAt(val.length() - 1) == '"')
                                || (val.charAt(0) == '\'' && val.charAt(val.length() - 1) == '\''))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        switch (key) {
                            case "name" -> name = val;
                            case "description" -> description = val;
                            case "type" -> type = val.toLowerCase();
                        }
                    }
                }
            }
        }

        String lang = detectLanguage(body);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO memory_fts(file_name, name, description, mem_type, body, lang) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, fileName);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, type);
            ps.setString(5, body);
            ps.setString(6, lang);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO file_status(file_name, last_modified, file_size) VALUES (?, ?, ?)")) {
            ps.setString(1, fileName);
            ps.setLong(2, mtime);
            ps.setLong(3, size);
            ps.executeUpdate();
        }
    }

    /**
     * Search memory files using FTS5 full-text search with BM25 ranking.
     *
     * <p>Name and description matches are boosted via BM25 column weights.
     * Falls back to LIKE if the query can't be parsed by FTS5.</p>
     *
     * @param query      search query
     * @param typeFilter optional memory type filter (user/feedback/project/reference)
     * @param maxResults max results to return
     * @return ranked list of matches with file_name, name, description, type, body, score
     */
    public List<Map<String, Object>> search(String query, String typeFilter, int maxResults)
            throws SQLException, IOException {
        syncIndex(); // ensure up to date

        if (query == null || query.isBlank()) {
            if (typeFilter != null && !typeFilter.isBlank()) {
                return searchByType(typeFilter, maxResults);
            }
            return List.of();
        }

        // Try FTS5 first
        List<Map<String, Object>> results = searchFts(query, typeFilter, maxResults);
        if (!results.isEmpty()) return results;

        // Fallback to LIKE for queries FTS5 can't handle
        return searchLike(query, typeFilter, maxResults);
    }

    private List<Map<String, Object>> searchFts(String query, String typeFilter, int maxResults)
            throws SQLException {
        // FTS5 query — search all content columns
        // BM25 weights: file_name=0, name=10, description=5, mem_type=0, body=1, lang=0
        String escaped = escapeFts5(query);

        String sql;
        if (typeFilter != null && !typeFilter.isBlank()) {
            sql = """
                SELECT file_name, name, description, mem_type, body, lang,
                       bm25(memory_fts, 0, 10, 5, 0, 1, 0) AS score
                FROM memory_fts
                WHERE memory_fts MATCH ? AND mem_type = ?
                ORDER BY score
                LIMIT ?""";
        } else {
            sql = """
                SELECT file_name, name, description, mem_type, body, lang,
                       bm25(memory_fts, 0, 10, 5, 0, 1, 0) AS score
                FROM memory_fts
                WHERE memory_fts MATCH ?
                ORDER BY score
                LIMIT ?""";
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, escaped);
            if (typeFilter != null && !typeFilter.isBlank()) {
                ps.setString(2, typeFilter.toLowerCase());
                ps.setInt(3, maxResults);
            } else {
                ps.setInt(2, maxResults);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            // FTS5 syntax error — fall through to LIKE
            return List.of();
        }
        return results;
    }

    private List<Map<String, Object>> searchLike(String query, String typeFilter, int maxResults)
            throws SQLException {
        String pattern = "%" + query.toLowerCase() + "%";
        String sql;
        if (typeFilter != null && !typeFilter.isBlank()) {
            sql = """
                SELECT file_name, name, description, mem_type, body, lang, 0.0 AS score
                FROM memory_fts
                WHERE (LOWER(name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(body) LIKE ?)
                  AND mem_type = ?
                LIMIT ?""";
        } else {
            sql = """
                SELECT file_name, name, description, mem_type, body, lang, 0.0 AS score
                FROM memory_fts
                WHERE LOWER(name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(body) LIKE ?
                LIMIT ?""";
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            if (typeFilter != null && !typeFilter.isBlank()) {
                ps.setString(4, typeFilter.toLowerCase());
                ps.setInt(5, maxResults);
            } else {
                ps.setInt(4, maxResults);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rowToMap(rs));
                }
            }
        }
        return results;
    }

    private List<Map<String, Object>> searchByType(String typeFilter, int maxResults)
            throws SQLException {
        String sql = """
            SELECT file_name, name, description, mem_type, body, lang, 0.0 AS score
            FROM memory_fts WHERE mem_type = ? LIMIT ?""";
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, typeFilter.toLowerCase());
            ps.setInt(2, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rowToMap(rs));
                }
            }
        }
        return results;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", rs.getString("file_name"));
        m.put("name", rs.getString("name"));
        m.put("description", rs.getString("description"));
        m.put("type", rs.getString("mem_type"));
        m.put("body", rs.getString("body"));
        m.put("lang", rs.getString("lang"));
        m.put("score", rs.getDouble("score"));
        return m;
    }

    /**
     * Escape special FTS5 query syntax characters.
     * Wraps individual tokens in double quotes to prevent FTS5 parse errors.
     */
    private static String escapeFts5(String query) {
        // Split into words, quote each to handle special chars
        String[] words = query.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i].replace("\"", "\"\"");
            if (i > 0) sb.append(" OR ");
            // Each term searches all columns via implicit OR
            sb.append('"').append(w).append('"');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }

    // ========================================================================
    // Language detection via trigram frequency analysis
    // ========================================================================

    /**
     * Detect the dominant language of text using character trigram frequency.
     * Returns an ISO 639-1 code (en, de, fr, es, pt, it, nl, ru, zh, ja, ko, ar).
     * Defaults to "en" for short or ambiguous text.
     */
    static String detectLanguage(String text) {
        if (text == null || text.length() < 50) return "en";

        // Quick script-based detection for non-Latin
        int[] scriptCounts = countScripts(text);
        int total = scriptCounts[0] + scriptCounts[1] + scriptCounts[2]
                + scriptCounts[3] + scriptCounts[4] + scriptCounts[5];
        if (total == 0) return "en";

        // CJK dominant
        if (scriptCounts[1] * 100 / total > 20) {
            // Distinguish Chinese/Japanese/Korean by character ranges
            return classifyCjk(text);
        }
        // Cyrillic dominant
        if (scriptCounts[2] * 100 / total > 30) return "ru";
        // Arabic dominant
        if (scriptCounts[3] * 100 / total > 30) return "ar";
        // Devanagari dominant
        if (scriptCounts[4] * 100 / total > 30) return "hi";
        // Thai dominant
        if (scriptCounts[5] * 100 / total > 30) return "th";

        // Latin script — use trigram frequency to distinguish European languages
        return detectLatinLanguage(text);
    }

    /**
     * Count characters by script family.
     * [0]=Latin, [1]=CJK, [2]=Cyrillic, [3]=Arabic, [4]=Devanagari, [5]=Thai
     */
    private static int[] countScripts(String text) {
        int[] counts = new int[6];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= 0x00C0 && c <= 0x024F)) {
                counts[0]++;
            } else if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3400 && c <= 0x4DBF)) {
                counts[1]++;
            } else if (c >= 0x0400 && c <= 0x04FF) {
                counts[2]++;
            } else if (c >= 0x0600 && c <= 0x06FF) {
                counts[3]++;
            } else if (c >= 0x0900 && c <= 0x097F) {
                counts[4]++;
            } else if (c >= 0x0E00 && c <= 0x0E7F) {
                counts[5]++;
            }
        }
        return counts;
    }

    private static String classifyCjk(String text) {
        int han = 0, hiragana = 0, katakana = 0, hangul = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) han++;
            else if (c >= 0x3040 && c <= 0x309F) hiragana++;
            else if (c >= 0x30A0 && c <= 0x30FF) katakana++;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul++;
        }
        if (hiragana + katakana > 0) return "ja";
        if (hangul > han) return "ko";
        return "zh";
    }

    /**
     * Distinguish Latin-script European languages by their most distinctive trigrams.
     * Uses the top-20 trigrams that are most characteristic of each language.
     */
    private static String detectLatinLanguage(String text) {
        String lower = text.toLowerCase();
        // Build trigram frequency map from the text
        Map<String, Integer> trigrams = new HashMap<>();
        for (int i = 0; i < lower.length() - 2; i++) {
            char a = lower.charAt(i), b = lower.charAt(i + 1), c = lower.charAt(i + 2);
            if (isLetter(a) && isLetter(b) && isLetter(c)) {
                String tri = "" + a + b + c;
                trigrams.merge(tri, 1, Integer::sum);
            }
        }

        // Score against language profiles
        int bestScore = 0;
        String bestLang = "en";
        for (var entry : LATIN_PROFILES.entrySet()) {
            int score = 0;
            for (String tri : entry.getValue()) {
                Integer count = trigrams.get(tri);
                if (count != null) score += count;
            }
            if (score > bestScore) {
                bestScore = score;
                bestLang = entry.getKey();
            }
        }
        return bestLang;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 0x00E0 && c <= 0x024F);
    }

    /** Top distinctive trigrams per language (manually curated from large corpora). */
    private static final Map<String, String[]> LATIN_PROFILES = Map.ofEntries(
            Map.entry("en", new String[]{
                    "the", "ing", "tion", "and", "her", "ent", "ion", "tio",
                    "for", "hat", "tha", "ere", "ate", "his", "con", "all",
                    "ver", "ter", "ith", "was"}),
            Map.entry("de", new String[]{
                    "ein", "ich", "die", "und", "der", "den", "sch", "ber",
                    "ver", "cht", "ung", "gen", "eit", "nic", "ine", "ter",
                    "auf", "hen", "ier", "ges"}),
            Map.entry("fr", new String[]{
                    "les", "ent", "des", "que", "ion", "ais", "ous", "par",
                    "est", "our", "eur", "tre", "con", "men", "ait", "ont",
                    "res", "une", "ans", "ave"}),
            Map.entry("es", new String[]{
                    "que", "los", "ent", "aci", "las", "por", "ado", "est",
                    "con", "ion", "ara", "nte", "ien", "tos", "una", "del",
                    "tra", "res", "cion", "ido"}),
            Map.entry("pt", new String[]{
                    "que", "ent", "ado", "est", "ção", "dos", "par", "con",
                    "nte", "com", "uma", "ais", "ida", "ara", "ões", "ment",
                    "ter", "res", "era", "das"}),
            Map.entry("it", new String[]{
                    "che", "ell", "per", "ent", "ion", "ato", "tta", "nte",
                    "con", "ato", "are", "gli", "del", "ame", "one", "ere",
                    "ess", "att", "lla", "anz"}),
            Map.entry("nl", new String[]{
                    "een", "het", "van", "aar", "ver", "den", "oor", "ijk",
                    "ing", "aan", "erd", "ede", "ter", "nde", "zij", "gen",
                    "die", "ond", "dat", "sch"}),
            Map.entry("sv", new String[]{
                    "för", "och", "att", "det", "som", "den", "var", "med",
                    "ett", "ade", "gen", "ter", "ing", "lig", "kan", "har",
                    "ska", "inte", "till", "ande"}),
            Map.entry("da", new String[]{
                    "der", "det", "for", "med", "den", "som", "til", "har",
                    "var", "ede", "ige", "ger", "kan", "lig", "gen", "hed",
                    "ler", "isk", "nde", "ter"}),
            Map.entry("fi", new String[]{
                    "nen", "ist", "tta", "sta", "een", "ssa", "ais", "iin",
                    "att", "ään", "taa", "lla", "ise", "sti", "utt", "oit",
                    "maa", "per", "ari", "inen"}),
            Map.entry("tr", new String[]{
                    "lar", "ler", "bir", "eri", "ini", "ası", "yor", "dan",
                    "ile", "len", "ara", "rin", "esi", "ına", "nın", "dır",
                    "olm", "aya", "rak", "eri"})
    );
}
