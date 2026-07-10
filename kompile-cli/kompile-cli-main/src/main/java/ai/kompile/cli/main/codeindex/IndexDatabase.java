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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SQLite-backed search index for a single project's code entities.
 * Uses FTS5 for fast full-text search and WAL mode for concurrent read safety.
 *
 * <p>This is a rebuildable cache — the per-file JSON shards in
 * {@link IndexFileStore} are the durable source of truth. If the DB
 * is corrupt or missing, call {@link #rebuildFromShards} to recreate it.</p>
 */
public class IndexDatabase implements AutoCloseable {

    /**
     * Stamped into {@code PRAGMA user_version} once {@link #ensureSchema()}
     * has run. Opens against a DB already at this version skip schema setup
     * entirely — the DDL churn plus two full-table COUNT checks used to run
     * on every open, which dominated read-action latency on large indexes.
     * Bump this when the schema changes so existing DBs re-run the migration.
     */
    private static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    private IndexDatabase(Connection conn) {
        this.conn = conn;
    }

    /**
     * Open (or create) the index database for a project.
     */
    public static IndexDatabase open(Path indexDir) throws SQLException {
        String url = "jdbc:sqlite:" + indexDir.resolve("index.db").toAbsolutePath();
        Connection conn = DriverManager.getConnection(url);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA cache_size=-8000"); // 8MB cache
            stmt.execute("PRAGMA busy_timeout=5000"); // wait instead of SQLITE_BUSY across processes
            stmt.execute("PRAGMA temp_store=MEMORY");
        }
        IndexDatabase db = new IndexDatabase(conn);
        if (db.schemaVersion() != SCHEMA_VERSION) {
            db.ensureSchema();
            db.setSchemaVersion(SCHEMA_VERSION);
        }
        return db;
    }

    private int schemaVersion() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setSchemaVersion(int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version=" + version);
        }
    }

    private void ensureSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_status (
                    rel_path TEXT PRIMARY KEY,
                    shard_name TEXT NOT NULL,
                    last_modified INTEGER NOT NULL,
                    file_size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    indexed_at TEXT NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entities_meta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rel_path TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    fqn TEXT NOT NULL,
                    language TEXT NOT NULL,
                    start_line INTEGER,
                    end_line INTEGER,
                    signature TEXT,
                    doc_comment TEXT,
                    visibility TEXT,
                    indexed_at TEXT,
                    inherited_from TEXT,
                    implements_list TEXT,
                    annotations TEXT
                )""");

            // Schema migration: add inheritance columns if absent (for existing DBs)
            addColumnIfMissing(stmt, "entities_meta", "inherited_from", "TEXT");
            addColumnIfMissing(stmt, "entities_meta", "implements_list", "TEXT");
            addColumnIfMissing(stmt, "entities_meta", "annotations", "TEXT");

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_entities_rel_path
                ON entities_meta(rel_path)""");

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_entities_name
                ON entities_meta(name)""");

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_entities_type
                ON entities_meta(entity_type)""");

            // Migrate from old contentless FTS5 table if it exists.
            // Contentless (content='') doesn't support DELETE, which breaks
            // incremental updates. Replace with a regular FTS5 table.
            if (isContentlessFts(stmt)) {
                stmt.execute("DROP TABLE IF EXISTS entities_fts");
            }

            // Regular FTS5 table — supports INSERT, DELETE, UPDATE.
            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS entities_fts USING fts5(
                    name,
                    fqn,
                    signature,
                    doc_comment,
                    tokenize='unicode61'
                )""");

            // --- Relations table for local graph ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS relations (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id    TEXT NOT NULL,
                    source_fqn    TEXT NOT NULL,
                    target_name   TEXT NOT NULL,
                    target_fqn    TEXT,
                    relation_type TEXT NOT NULL,
                    file_path     TEXT NOT NULL,
                    line          INTEGER
                )""");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_source ON relations(source_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_target ON relations(target_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_target_name ON relations(target_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_file ON relations(file_path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_type ON relations(relation_type)");

            // FQN index on entities — speeds up symbol lookup and connectivity resolution
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_fqn ON entities_meta(fqn)");
        }

        // If we just recreated the FTS table, rebuild it from entities_meta
        if (isFtsEmpty() && hasEntities()) {
            rebuildFtsFromMeta();
        }
    }

    /**
     * Add a column to a table if it doesn't already exist.
     * Used for schema migration of existing databases.
     */
    private void addColumnIfMissing(Statement stmt, String table, String col, String type)
            throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='" + col + "'")) {
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
            }
        }
    }

    /**
     * Check if the existing FTS table is contentless (old schema).
     */
    private boolean isContentlessFts(Statement stmt) {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT sql FROM sqlite_master WHERE name='entities_fts' AND type='table'")) {
            if (rs.next()) {
                String sql = rs.getString(1);
                return sql != null && sql.contains("content=''");
            }
        } catch (SQLException ignored) {}
        return false;
    }

    private boolean isFtsEmpty() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities_fts")) {
            return !rs.next() || rs.getInt(1) == 0;
        }
    }

    private boolean hasEntities() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities_meta")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /**
     * Rebuild the FTS index from entities_meta. Used after schema migration.
     */
    private void rebuildFtsFromMeta() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, fqn, signature, doc_comment FROM entities_meta");
             PreparedStatement ftsPs = conn.prepareStatement(
                     "INSERT INTO entities_fts(rowid, name, fqn, signature, doc_comment) VALUES (?, ?, ?, ?, ?)")) {
            while (rs.next()) {
                ftsPs.setLong(1, rs.getLong("id"));
                ftsPs.setString(2, rs.getString("name") != null ? rs.getString("name") : "");
                ftsPs.setString(3, rs.getString("fqn") != null ? rs.getString("fqn") : "");
                ftsPs.setString(4, rs.getString("signature") != null ? rs.getString("signature") : "");
                ftsPs.setString(5, rs.getString("doc_comment") != null ? rs.getString("doc_comment") : "");
                ftsPs.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // -----------------------------------------------------------------------
    // Transaction management
    // -----------------------------------------------------------------------

    public void beginTransaction() throws SQLException {
        conn.setAutoCommit(false);
    }

    public void commit() throws SQLException {
        conn.commit();
        conn.setAutoCommit(true);
    }

    public void rollback() {
        try {
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {}
    }

    // -----------------------------------------------------------------------
    // File status CRUD
    // -----------------------------------------------------------------------

    public void upsertFile(String relPath, String shardName,
                           IndexFileStore.FileFingerprint fp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO file_status
                (rel_path, shard_name, last_modified, file_size, sha256, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, relPath);
            ps.setString(2, shardName);
            ps.setLong(3, fp.lastModified());
            ps.setLong(4, fp.size());
            ps.setString(5, fp.sha256());
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /**
     * Delete a file and all its entities and relations from the index.
     */
    public void deleteFile(String relPath) throws SQLException {
        deleteFiles(List.of(relPath));
    }

    /**
     * Bulk variant of {@link #deleteFile}: removes many files' entities,
     * relations and status rows with IN-chunked statements (4 statements per
     * ~500 files instead of 4 per file).
     */
    public void deleteFiles(Collection<String> relPaths) throws SQLException {
        if (relPaths == null || relPaths.isEmpty()) return;
        List<String> paths = List.copyOf(relPaths);
        int batchSize = 500;
        for (int start = 0; start < paths.size(); start += batchSize) {
            List<String> batch = paths.subList(start, Math.min(start + batchSize, paths.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));

            // FTS rows first — the subquery needs entities_meta still populated
            String[] statements = {
                    "DELETE FROM entities_fts WHERE rowid IN " +
                            "(SELECT id FROM entities_meta WHERE rel_path IN (" + placeholders + "))",
                    "DELETE FROM entities_meta WHERE rel_path IN (" + placeholders + ")",
                    "DELETE FROM relations WHERE file_path IN (" + placeholders + ")",
                    "DELETE FROM file_status WHERE rel_path IN (" + placeholders + ")"
            };
            for (String sql : statements) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setString(i + 1, batch.get(i));
                    }
                    ps.executeUpdate();
                }
            }
        }
    }

    /**
     * Get all indexed relative paths. Used to detect deleted files.
     */
    public Set<String> getAllRelPaths() throws SQLException {
        Set<String> paths = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT rel_path FROM file_status")) {
            while (rs.next()) paths.add(rs.getString(1));
        }
        return paths;
    }

    // -----------------------------------------------------------------------
    // Entity CRUD
    // -----------------------------------------------------------------------

    /**
     * Insert entities for a file. Call deleteFile() first to remove old entries.
     *
     * <p>Row ids are pre-allocated from {@code sqlite_sequence} so both the
     * meta and FTS inserts run as JDBC batches — the previous per-entity
     * executeUpdate + getGeneratedKeys pair cost two round trips per entity
     * and dominated index write time.</p>
     */
    public void insertEntities(String relPath, List<Map<String, Object>> entities)
            throws SQLException {
        if (entities == null || entities.isEmpty()) return;
        long nextId = nextEntityId();
        try (PreparedStatement metaPs = conn.prepareStatement("""
                INSERT INTO entities_meta
                (id, rel_path, entity_type, name, fqn, language, start_line, end_line,
                 signature, doc_comment, visibility, indexed_at, inherited_from, implements_list,
                 annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
             PreparedStatement ftsPs = conn.prepareStatement("""
                INSERT INTO entities_fts(rowid, name, fqn, signature, doc_comment)
                VALUES (?, ?, ?, ?, ?)""")) {

            for (Map<String, Object> e : entities) {
                String name = str(e, "name");
                String fqn = str(e, "fullyQualifiedName");
                String sig = str(e, "signature");
                String doc = str(e, "docComment");

                long rowId = nextId++;
                metaPs.setLong(1, rowId);
                metaPs.setString(2, relPath);
                metaPs.setString(3, str(e, "entityType"));
                metaPs.setString(4, name);
                metaPs.setString(5, fqn);
                metaPs.setString(6, str(e, "language"));
                metaPs.setObject(7, e.get("startLine"));
                metaPs.setObject(8, e.get("endLine"));
                metaPs.setString(9, sig);
                metaPs.setString(10, doc);
                metaPs.setString(11, str(e, "visibility"));
                metaPs.setString(12, str(e, "indexedAt"));
                metaPs.setString(13, str(e, "inheritedFrom"));
                metaPs.setString(14, str(e, "implementsList"));
                metaPs.setString(15, str(e, "annotations"));
                metaPs.addBatch();

                ftsPs.setLong(1, rowId);
                ftsPs.setString(2, name != null ? name : "");
                ftsPs.setString(3, fqn != null ? fqn : "");
                ftsPs.setString(4, sig != null ? sig : "");
                ftsPs.setString(5, doc != null ? doc : "");
                ftsPs.addBatch();
            }
            metaPs.executeBatch();
            ftsPs.executeBatch();
        }
    }

    /**
     * Next collision-free entities_meta id. AUTOINCREMENT keeps
     * {@code sqlite_sequence.seq} at the highest id ever used (explicit
     * inserts bump it too), so seq+1 is always safe; MAX(id) guards the
     * fresh-DB case where the sequence row doesn't exist yet. Same-connection
     * reads see uncommitted rows, so repeated calls inside one transaction
     * keep advancing.
     */
    private long nextEntityId() throws SQLException {
        long seq = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT seq FROM sqlite_sequence WHERE name='entities_meta'")) {
            if (rs.next()) seq = rs.getLong(1);
        } catch (SQLException ignored) {
            // sqlite_sequence doesn't exist until the first AUTOINCREMENT insert
        }
        long maxId = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(id), 0) FROM entities_meta")) {
            if (rs.next()) maxId = rs.getLong(1);
        }
        return Math.max(seq, maxId) + 1;
    }

    // -----------------------------------------------------------------------
    // Relations CRUD
    // -----------------------------------------------------------------------

    /**
     * Batch-insert relation records for a file.
     */
    public void insertRelations(String filePath, List<Map<String, Object>> relations)
            throws SQLException {
        if (relations == null || relations.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO relations
                (project_id, source_fqn, target_name, target_fqn, relation_type, file_path, line)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            for (Map<String, Object> r : relations) {
                ps.setString(1, str(r, "projectId"));
                ps.setString(2, str(r, "sourceFqn"));
                ps.setString(3, str(r, "targetName"));
                ps.setString(4, str(r, "targetFqn"));
                ps.setString(5, str(r, "relationType"));
                ps.setString(6, str(r, "filePath"));
                ps.setObject(7, r.get("line"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Search entities using FTS5 full-text search with fallback to LIKE.
     */
    public List<Map<String, Object>> search(String query, String entityType,
                                             int maxResults) throws SQLException {
        // Try FTS5 first
        List<Map<String, Object>> results = searchFts(query, entityType, maxResults);
        if (!results.isEmpty()) return results;

        // Fallback to LIKE for queries that FTS5 can't tokenize well
        return searchLike(query, entityType, maxResults);
    }

    private List<Map<String, Object>> searchFts(String query, String entityType,
                                                  int maxResults) throws SQLException {
        // Escape FTS5 special characters and build column-filtered query
        String escaped = escapeFts5(query);
        String ftsQuery = "name:" + escaped + " OR fqn:" + escaped +
                " OR signature:" + escaped + " OR doc_comment:" + escaped;

        String sql;
        if (entityType != null && !entityType.isEmpty()) {
            sql = """
                SELECT m.* FROM entities_meta m
                JOIN entities_fts f ON f.rowid = m.id
                WHERE entities_fts MATCH ? AND m.entity_type = ?
                ORDER BY rank LIMIT ?""";
        } else {
            sql = """
                SELECT m.* FROM entities_meta m
                JOIN entities_fts f ON f.rowid = m.id
                WHERE entities_fts MATCH ?
                ORDER BY rank LIMIT ?""";
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ftsQuery);
            if (entityType != null && !entityType.isEmpty()) {
                ps.setString(2, entityType.toUpperCase());
                ps.setInt(3, maxResults);
            } else {
                ps.setInt(2, maxResults);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(rowToEntity(rs));
            }
        } catch (SQLException e) {
            // FTS5 query syntax error — fall through to LIKE
            return List.of();
        }
        return results;
    }

    private List<Map<String, Object>> searchLike(String query, String entityType,
                                                   int maxResults) throws SQLException {
        String pattern = "%" + query.toLowerCase() + "%";
        String sql;
        if (entityType != null && !entityType.isEmpty()) {
            sql = """
                SELECT * FROM entities_meta
                WHERE (LOWER(name) LIKE ? OR LOWER(fqn) LIKE ?
                       OR LOWER(signature) LIKE ? OR LOWER(doc_comment) LIKE ?)
                  AND entity_type = ?
                LIMIT ?""";
        } else {
            sql = """
                SELECT * FROM entities_meta
                WHERE LOWER(name) LIKE ? OR LOWER(fqn) LIKE ?
                       OR LOWER(signature) LIKE ? OR LOWER(doc_comment) LIKE ?
                LIMIT ?""";
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setString(4, pattern);
            if (entityType != null && !entityType.isEmpty()) {
                ps.setString(5, entityType.toUpperCase());
                ps.setInt(6, maxResults);
            } else {
                ps.setInt(5, maxResults);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(rowToEntity(rs));
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    public int getEntityCount() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities_meta")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int getFileCount() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_status")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Map<String, Integer> getEntityCountsByType() throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT entity_type, COUNT(*) FROM entities_meta GROUP BY entity_type ORDER BY COUNT(*) DESC")) {
            while (rs.next()) counts.put(rs.getString(1), rs.getInt(2));
        }
        return counts;
    }

    public Map<String, Integer> getLanguageCounts() throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT language, COUNT(DISTINCT rel_path) FROM entities_meta GROUP BY language ORDER BY COUNT(*) DESC")) {
            while (rs.next()) counts.put(rs.getString(1), rs.getInt(2));
        }
        return counts;
    }

    // -----------------------------------------------------------------------
    // Graph queries
    // -----------------------------------------------------------------------

    /**
     * BFS over the relations table to build a local symbol graph.
     *
     * @param fqn   fully-qualified name (or suffix) of the focal symbol
     * @param depth BFS traversal depth (default 2)
     * @return map with keys: entity, nodes, edges, metadata
     */
    public Map<String, Object> getSymbolGraph(String fqn, int depth) throws SQLException {
        // 1. Find focal entity — exact match first, then suffix
        Map<String, Object> focalEntity = findEntityByFqn(fqn);
        if (focalEntity == null) {
            focalEntity = findEntityBySuffix(fqn);
        }

        String resolvedFqn = focalEntity != null
                ? (String) focalEntity.get("fullyQualifiedName") : fqn;

        // 2. BFS collecting nodes + edges
        Set<String> visited = new LinkedHashSet<>();
        List<Map<String, Object>> allEdges = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();

        queue.add(resolvedFqn);
        visited.add(resolvedFqn);

        int maxNodes = 500;

        for (int hop = 0; hop < depth && !queue.isEmpty() && visited.size() < maxNodes; hop++) {
            List<String> currentLayer = new ArrayList<>(queue);
            queue.clear();
            for (String currentFqn : currentLayer) {
                if (visited.size() >= maxNodes) break;

                List<Map<String, Object>> outgoing = getOutgoingRelations(currentFqn, 200);
                for (Map<String, Object> rel : outgoing) {
                    allEdges.add(rel);
                    String targetFqn = (String) rel.get("targetFqn");
                    if (targetFqn == null) targetFqn = (String) rel.get("targetName");
                    if (targetFqn != null && !targetFqn.isEmpty() && visited.add(targetFqn)) {
                        queue.add(targetFqn);
                    }
                }

                List<Map<String, Object>> incoming = getIncomingRelations(currentFqn, 200);
                for (Map<String, Object> rel : incoming) {
                    allEdges.add(rel);
                    String sourceFqn = (String) rel.get("sourceFqn");
                    if (sourceFqn != null && !sourceFqn.isEmpty() && visited.add(sourceFqn)) {
                        queue.add(sourceFqn);
                    }
                }
            }
        }

        // 3. Fetch entity records for visited FQNs
        List<Map<String, Object>> nodes = fetchEntitiesByFqns(new ArrayList<>(visited));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", focalEntity);
        result.put("nodes", nodes);
        result.put("edges", allEdges);
        result.put("metadata", Map.of("nodeCount", nodes.size(), "edgeCount", allEdges.size()));
        return result;
    }

    /**
     * Get all entities and relations for a file.
     */
    public Map<String, Object> getFileGraph(String relPath) throws SQLException {
        List<Map<String, Object>> entities = getEntitiesForFile(relPath);

        // All outgoing relations from this file
        List<Map<String, Object>> outgoing;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM relations WHERE file_path = ? LIMIT 1000")) {
            ps.setString(1, relPath);
            outgoing = collectRelations(ps);
        }

        // All incoming relations to entities defined in this file
        Set<String> fileFqns = new LinkedHashSet<>();
        for (Map<String, Object> e : entities) {
            String fqn = (String) e.get("fullyQualifiedName");
            if (fqn != null) fileFqns.add(fqn);
        }
        List<Map<String, Object>> incoming = getIncomingRelationsForFqns(fileFqns);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", relPath);
        result.put("entities", entities);
        result.put("entityCount", entities.size());
        result.put("outgoingRelations", outgoing);
        result.put("incomingRelations", incoming);
        result.put("metadata", Map.of(
                "nodeCount", fileFqns.size(),
                "edgeCount", outgoing.size() + incoming.size()
        ));
        return result;
    }

    /**
     * Get graph statistics: total relations, relations by type.
     */
    public Map<String, Object> getGraphStats() throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM relations")) {
            stats.put("totalRelations", rs.next() ? rs.getInt(1) : 0);
        }

        Map<String, Integer> byType = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT relation_type, COUNT(*) FROM relations GROUP BY relation_type ORDER BY COUNT(*) DESC")) {
            while (rs.next()) byType.put(rs.getString(1), rs.getInt(2));
        }
        stats.put("relationsByType", byType);

        stats.put("entityCountsByType", getEntityCountsByType());
        stats.put("totalEntities", getEntityCount());

        return stats;
    }

    /** Rows still lacking a resolved cross-file target. */
    private static final String UNRESOLVED_PRED =
            "(target_fqn IS NULL OR target_fqn = '' OR target_fqn = target_name)";

    /** Above this many distinct names, one streamed entities scan beats per-name probes. */
    private static final int CONNECTIVITY_PROBE_THRESHOLD = 200;

    /**
     * Full-index variant of {@link #ensureConnectivity(Collection)}.
     */
    public int ensureConnectivity() throws SQLException {
        return ensureConnectivity(null);
    }

    /**
     * Post-indexing pass: resolve target_fqn for relations that only carry a
     * target_name (exact FQN match, else an entity whose FQN ends with
     * "." + target_name).
     *
     * <p>The legacy implementation was a single UPDATE with a correlated
     * leading-wildcard LIKE — a full entities_meta scan per unresolved
     * relation, O(relations × entities), re-run over the whole table after
     * every incremental pass. This version collects the distinct unresolved
     * names once (scoped to {@code changedFiles} when given: their own
     * relations plus older unresolved relations anywhere that point at names
     * (re)defined in those files), resolves each name a single time — indexed
     * probes when few, one streamed scan when many — and applies the mapping
     * with batched UPDATEs on the target_name index. Suffix matches are
     * case-sensitive (the legacy LIKE was ASCII-case-insensitive, which only
     * ever added false links between differently-cased identifiers).</p>
     *
     * @param changedFiles rel paths re-indexed in this pass; null or empty
     *                     resolves across the whole index
     * @return number of relations whose target_fqn actually changed
     */
    public int ensureConnectivity(Collection<String> changedFiles) throws SQLException {
        Set<String> targets = collectUnresolvedTargets(changedFiles);
        if (targets.isEmpty()) return 0;

        Map<String, String> resolution = targets.size() <= CONNECTIVITY_PROBE_THRESHOLD
                ? resolveTargetsByProbe(targets)
                : resolveTargetsByScan(targets);
        if (resolution.isEmpty()) return 0;

        int updated = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE relations SET target_fqn = ? WHERE target_name = ? AND "
                        + UNRESOLVED_PRED + " AND target_fqn IS NOT ?")) {
            for (Map.Entry<String, String> e : resolution.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            for (int c : ps.executeBatch()) {
                if (c > 0) updated += c;
            }
        }
        return updated;
    }

    /**
     * Distinct target names worth resolving. Scoped mode also picks up older
     * unresolved relations elsewhere whose target matches a name or FQN
     * (re)defined in the changed files, so new definitions heal old edges.
     */
    private Set<String> collectUnresolvedTargets(Collection<String> changedFiles) throws SQLException {
        Set<String> targets = new LinkedHashSet<>();
        if (changedFiles == null || changedFiles.isEmpty()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT DISTINCT target_name FROM relations WHERE " + UNRESOLVED_PRED)) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (t != null && !t.isEmpty()) targets.add(t);
                }
            }
            return targets;
        }

        List<String> paths = List.copyOf(changedFiles);
        String unresolvedR = "(r.target_fqn IS NULL OR r.target_fqn = '' OR r.target_fqn = r.target_name)";
        int batchSize = 500;
        for (int start = 0; start < paths.size(); start += batchSize) {
            List<String> batch = paths.subList(start, Math.min(start + batchSize, paths.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String[] queries = {
                    "SELECT DISTINCT target_name FROM relations WHERE file_path IN ("
                            + placeholders + ") AND " + UNRESOLVED_PRED,
                    "SELECT DISTINCT r.target_name FROM relations r JOIN entities_meta e "
                            + "ON e.name = r.target_name WHERE e.rel_path IN ("
                            + placeholders + ") AND " + unresolvedR,
                    "SELECT DISTINCT r.target_name FROM relations r JOIN entities_meta e "
                            + "ON e.fqn = r.target_name WHERE e.rel_path IN ("
                            + placeholders + ") AND " + unresolvedR
            };
            for (String sql : queries) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setString(i + 1, batch.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String t = rs.getString(1);
                            if (t != null && !t.isEmpty()) targets.add(t);
                        }
                    }
                }
            }
        }
        return targets;
    }

    /**
     * Resolve a small set of names with indexed lookups: exact FQN, then
     * simple-name match verified as a suffix, then an FTS-narrowed suffix
     * probe (catches IMPORT/PACKAGE entities whose name is the full FQN and
     * dotted target names, which the name-equality probe can't see).
     */
    private Map<String, String> resolveTargetsByProbe(Set<String> targets) throws SQLException {
        Map<String, String> resolved = new LinkedHashMap<>();
        try (PreparedStatement exactPs = conn.prepareStatement(
                     "SELECT fqn FROM entities_meta WHERE fqn = ? LIMIT 1");
             PreparedStatement namePs = conn.prepareStatement(
                     "SELECT fqn FROM entities_meta WHERE name = ? AND fqn LIKE '%.' || ? LIMIT 1");
             PreparedStatement ftsPs = conn.prepareStatement(
                     "SELECT e.fqn FROM entities_fts f JOIN entities_meta e ON e.id = f.rowid "
                             + "WHERE entities_fts MATCH ? LIMIT 50")) {
            for (String target : targets) {
                exactPs.setString(1, target);
                try (ResultSet rs = exactPs.executeQuery()) {
                    if (rs.next()) {
                        resolved.put(target, target);
                        continue;
                    }
                }

                namePs.setString(1, target);
                namePs.setString(2, target);
                try (ResultSet rs = namePs.executeQuery()) {
                    if (rs.next()) {
                        resolved.put(target, rs.getString(1));
                        continue;
                    }
                }

                String segment = lastSegment(target);
                if (segment.isEmpty()) continue;
                try {
                    ftsPs.setString(1, "fqn:\"" + segment.replace("\"", "\"\"") + "\"");
                    try (ResultSet rs = ftsPs.executeQuery()) {
                        while (rs.next()) {
                            String fqn = rs.getString(1);
                            if (fqn != null && fqn.endsWith("." + target)) {
                                resolved.put(target, fqn);
                                break;
                            }
                        }
                    }
                } catch (SQLException ignored) {
                    // Un-tokenizable target — no FTS candidates, leave unresolved
                }
            }
        }
        return resolved;
    }

    /**
     * Resolve many names in one streamed pass over entities_meta.fqn:
     * O(entities + targets) with hash lookups instead of per-name queries.
     */
    private Map<String, String> resolveTargetsByScan(Set<String> targets) throws SQLException {
        Map<String, String> resolved = new LinkedHashMap<>();
        Set<String> undotted = new HashSet<>();
        Map<String, List<String>> dottedBySegment = new HashMap<>();
        for (String t : targets) {
            if (t.indexOf('.') < 0) {
                undotted.add(t);
            } else {
                dottedBySegment.computeIfAbsent(lastSegment(t), k -> new ArrayList<>()).add(t);
            }
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT fqn FROM entities_meta")) {
            while (rs.next()) {
                String fqn = rs.getString(1);
                if (fqn == null || fqn.isEmpty()) continue;

                // Exact FQN match is authoritative — overwrite any suffix pick
                if (targets.contains(fqn)) resolved.put(fqn, fqn);

                String segment = lastSegment(fqn);
                if (segment.length() == fqn.length()) continue; // no package prefix → no suffix match

                if (undotted.contains(segment)) resolved.putIfAbsent(segment, fqn);

                List<String> dotted = dottedBySegment.get(segment);
                if (dotted != null) {
                    for (String t : dotted) {
                        if (fqn.length() > t.length() && fqn.endsWith(t)
                                && fqn.charAt(fqn.length() - t.length() - 1) == '.') {
                            resolved.putIfAbsent(t, fqn);
                        }
                    }
                }
            }
        }
        return resolved;
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    // -----------------------------------------------------------------------
    // Graph query helpers
    // -----------------------------------------------------------------------

    public Map<String, Object> findEntityByFqn(String fqn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities_meta WHERE fqn = ? LIMIT 1")) {
            ps.setString(1, fqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToEntity(rs) : null;
            }
        }
    }

    public Map<String, Object> findEntityBySuffix(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities_meta WHERE fqn LIKE ? LIMIT 1")) {
            ps.setString(1, "%." + name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToEntity(rs) : null;
            }
        }
    }

    List<Map<String, Object>> getOutgoingRelations(String fqn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM relations WHERE source_fqn = ? LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setInt(2, limit);
            return collectRelations(ps);
        }
    }

    List<Map<String, Object>> getIncomingRelations(String fqn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM relations WHERE target_fqn = ? LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setInt(2, limit);
            return collectRelations(ps);
        }
    }

    List<Map<String, Object>> getEntitiesForFile(String relPath) throws SQLException {
        List<Map<String, Object>> entities = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities_meta WHERE rel_path = ?")) {
            ps.setString(1, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) entities.add(rowToEntity(rs));
            }
        }
        return entities;
    }

    private List<Map<String, Object>> fetchEntitiesByFqns(List<String> fqns) throws SQLException {
        if (fqns.isEmpty()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();

        // Chunk into batches of 500 (SQLite variable limit)
        int batchSize = 500;
        for (int start = 0; start < fqns.size(); start += batchSize) {
            List<String> batch = fqns.subList(start, Math.min(start + batchSize, fqns.size()));
            String placeholders = String.join(",", batch.stream().map(f -> "?").toArray(String[]::new));
            String sql = "SELECT * FROM entities_meta WHERE fqn IN (" + placeholders + ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(rowToEntity(rs));
                }
            }
        }
        return results;
    }

    private List<Map<String, Object>> getIncomingRelationsForFqns(Set<String> fqns) throws SQLException {
        if (fqns.isEmpty()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> fqnList = new ArrayList<>(fqns);

        int batchSize = 500;
        for (int start = 0; start < fqnList.size(); start += batchSize) {
            List<String> batch = fqnList.subList(start, Math.min(start + batchSize, fqnList.size()));
            String placeholders = String.join(",", batch.stream().map(f -> "?").toArray(String[]::new));
            String sql = "SELECT * FROM relations WHERE target_fqn IN (" + placeholders + ") LIMIT 1000";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 1, batch.get(i));
                }
                results.addAll(collectRelations(ps));
            }
        }
        return results;
    }

    private List<Map<String, Object>> collectRelations(PreparedStatement ps) throws SQLException {
        List<Map<String, Object>> rels = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("relationType", rs.getString("relation_type"));
                rel.put("sourceFqn", rs.getString("source_fqn"));
                rel.put("targetName", rs.getString("target_name"));
                rel.put("targetFqn", rs.getString("target_fqn"));
                rel.put("filePath", rs.getString("file_path"));
                int line = rs.getInt("line");
                if (!rs.wasNull()) rel.put("line", line);
                rels.add(rel);
            }
        }
        return rels;
    }

    // -----------------------------------------------------------------------
    // Callers / Implementors / Call-chain graph queries
    // -----------------------------------------------------------------------

    /**
     * Find all classes that implement or extend a given type.
     * Traverses IMPLEMENTS and EXTENDS relations in the graph.
     *
     * @param typeFqn    FQN (or suffix) of the interface/class
     * @param maxResults maximum results
     * @return list of implementing/extending entity maps
     */
    public List<Map<String, Object>> getImplementors(String typeFqn, int maxResults) throws SQLException {
        // First resolve the target FQN
        String resolvedFqn = typeFqn;
        Map<String, Object> target = findEntityByFqn(typeFqn);
        if (target == null) {
            target = findEntityBySuffix(typeFqn);
            if (target != null) resolvedFqn = (String) target.get("fullyQualifiedName");
        }

        // Query: find entities whose source_fqn points to this type via IMPLEMENTS or EXTENDS
        List<Map<String, Object>> results = new ArrayList<>();
        String sql = """
            SELECT DISTINCT m.* FROM entities_meta m
            JOIN relations r ON m.fqn = r.source_fqn
            WHERE (r.target_fqn = ? OR r.target_name = ? OR r.target_fqn LIKE ?)
              AND r.relation_type IN ('IMPLEMENTS', 'EXTENDS')
              AND m.entity_type IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD')
            LIMIT ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolvedFqn);
            ps.setString(2, simpleName(resolvedFqn));
            ps.setString(3, "%." + simpleName(resolvedFqn));
            ps.setInt(4, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(rowToEntity(rs));
            }
        }
        return results;
    }

    /**
     * Find all callers of a given method/function — reverse CALLS lookup.
     *
     * @param targetFqn  FQN (or simple name) of the method/function being called
     * @param maxResults maximum results
     * @return list of caller entity maps with call site info
     */
    public List<Map<String, Object>> getCallers(String targetFqn, int maxResults) throws SQLException {
        String resolvedFqn = targetFqn;
        Map<String, Object> target = findEntityByFqn(targetFqn);
        if (target == null) {
            target = findEntityBySuffix(targetFqn);
            if (target != null) resolvedFqn = (String) target.get("fullyQualifiedName");
        }

        // Find all CALLS relations pointing to this target
        String sql = """
            SELECT r.source_fqn, r.file_path, r.line, r.target_name, r.target_fqn,
                   m.entity_type, m.name, m.fqn, m.signature, m.rel_path, m.start_line
            FROM relations r
            LEFT JOIN entities_meta m ON m.fqn = r.source_fqn
            WHERE r.relation_type = 'CALLS'
              AND (r.target_fqn = ? OR r.target_name = ? OR r.target_fqn LIKE ?)
            GROUP BY r.source_fqn, r.file_path, r.line
            ORDER BY r.file_path, r.line
            LIMIT ?""";

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolvedFqn);
            ps.setString(2, simpleName(resolvedFqn));
            ps.setString(3, "%." + simpleName(resolvedFqn));
            ps.setInt(4, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> caller = new LinkedHashMap<>();
                    caller.put("callerFqn", rs.getString("source_fqn"));
                    caller.put("callerName", rs.getString("name"));
                    caller.put("callerType", rs.getString("entity_type"));
                    caller.put("callerSignature", rs.getString("signature"));
                    caller.put("callSiteFile", rs.getString("file_path"));
                    int callLine = rs.getInt("line");
                    if (!rs.wasNull()) caller.put("callSiteLine", callLine);
                    caller.put("targetName", rs.getString("target_name"));
                    caller.put("targetFqn", rs.getString("target_fqn"));
                    results.add(caller);
                }
            }
        }
        return results;
    }

    /**
     * Trace a call chain: given a starting method, BFS outward through CALLS
     * relations to build a complete call tree.
     *
     * @param startFqn  starting method FQN
     * @param maxDepth  max BFS depth (0 = unlimited, default 5)
     * @param direction "outgoing" (who does this call?) or "incoming" (who calls this?)
     * @return call chain with nodes and edges
     */
    public Map<String, Object> getCallChain(String startFqn, int maxDepth,
                                             String direction) throws SQLException {
        if (maxDepth <= 0) maxDepth = 5;
        boolean incoming = "incoming".equalsIgnoreCase(direction);

        // Resolve start FQN
        Map<String, Object> startEntity = findEntityByFqn(startFqn);
        if (startEntity == null) {
            startEntity = findEntityBySuffix(startFqn);
            if (startEntity != null) startFqn = (String) startEntity.get("fullyQualifiedName");
        }

        Set<String> visited = new LinkedHashSet<>();
        List<Map<String, Object>> chainEdges = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();

        queue.add(startFqn);
        visited.add(startFqn);

        int maxNodes = 200;

        for (int depth = 0; depth < maxDepth && !queue.isEmpty() && visited.size() < maxNodes; depth++) {
            List<String> layer = new ArrayList<>(queue);
            queue.clear();

            for (String currentFqn : layer) {
                if (visited.size() >= maxNodes) break;

                List<Map<String, Object>> rels;
                if (incoming) {
                    // Who calls currentFqn?
                    rels = getIncomingCallRelations(currentFqn, 50);
                } else {
                    // What does currentFqn call?
                    rels = getOutgoingCallRelations(currentFqn, 50);
                }

                for (Map<String, Object> rel : rels) {
                    rel.put("depth", depth + 1);
                    chainEdges.add(rel);

                    String nextFqn = incoming
                            ? (String) rel.get("sourceFqn")
                            : (String) rel.get("targetFqn");
                    if (nextFqn == null) nextFqn = incoming
                            ? "" : (String) rel.get("targetName");
                    if (nextFqn != null && !nextFqn.isEmpty() && visited.add(nextFqn)) {
                        queue.add(nextFqn);
                    }
                }
            }
        }

        // Fetch entity records
        List<Map<String, Object>> nodes = fetchEntitiesByFqns(new ArrayList<>(visited));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startFqn", startFqn);
        result.put("startEntity", startEntity);
        result.put("direction", incoming ? "incoming" : "outgoing");
        result.put("maxDepth", maxDepth);
        result.put("nodes", nodes);
        result.put("edges", chainEdges);
        result.put("totalNodes", visited.size());
        result.put("totalEdges", chainEdges.size());
        return result;
    }

    /**
     * Find Spring injection targets: given a type, find all classes that
     * inject it via @Autowired.
     *
     * @param typeFqn    FQN (or suffix) of the type being injected
     * @param maxResults maximum results
     * @return list of injector entity maps with injection site info
     */
    public List<Map<String, Object>> getSpringInjectors(String typeFqn, int maxResults) throws SQLException {
        String resolvedFqn = typeFqn;
        Map<String, Object> target = findEntityByFqn(typeFqn);
        if (target == null) {
            target = findEntityBySuffix(typeFqn);
            if (target != null) resolvedFqn = (String) target.get("fullyQualifiedName");
        }

        String sql = """
            SELECT DISTINCT r.source_fqn, r.file_path, r.line,
                   m.entity_type, m.name, m.fqn, m.rel_path, m.start_line, m.signature
            FROM relations r
            LEFT JOIN entities_meta m ON m.fqn = r.source_fqn
            WHERE r.relation_type = 'SPRING_INJECTS'
              AND (r.target_fqn = ? OR r.target_name = ? OR r.target_fqn LIKE ?)
            ORDER BY r.file_path
            LIMIT ?""";

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolvedFqn);
            ps.setString(2, simpleName(resolvedFqn));
            ps.setString(3, "%." + simpleName(resolvedFqn));
            ps.setInt(4, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> injector = new LinkedHashMap<>();
                    injector.put("injectorFqn", rs.getString("source_fqn"));
                    injector.put("injectorName", rs.getString("name"));
                    injector.put("injectorType", rs.getString("entity_type"));
                    injector.put("filePath", rs.getString("file_path"));
                    int injectLine = rs.getInt("line");
                    if (!rs.wasNull()) injector.put("line", injectLine);
                    results.add(injector);
                }
            }
        }
        return results;
    }

    /**
     * Get the Spring DI resolution for an interface: which concrete implementation
     * would Spring inject, considering @Primary, @ConditionalOnProperty, etc.
     *
     * @param interfaceFqn the interface FQN
     * @return map with: interface info, all implementations, primary candidate, conditionals
     */
    public Map<String, Object> resolveSpringBean(String interfaceFqn) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();

        // Find all implementors
        List<Map<String, Object>> implementors = getImplementors(interfaceFqn, 50);
        result.put("interface", interfaceFqn);
        result.put("implementors", implementors);

        // Check which ones are Spring components
        List<Map<String, Object>> springComponents = new ArrayList<>();
        Map<String, Object> primaryBean = null;
        List<Map<String, Object>> conditionalBeans = new ArrayList<>();

        for (Map<String, Object> impl : implementors) {
            String implFqn = (String) impl.get("fullyQualifiedName");

            // Check for SPRING_COMPONENT relation
            List<Map<String, Object>> componentRels = getRelationsOfType(implFqn, "SPRING_COMPONENT");
            if (!componentRels.isEmpty()) {
                springComponents.add(impl);
            }

            // Check for SPRING_PRIMARY
            List<Map<String, Object>> primaryRels = getRelationsOfType(implFqn, "SPRING_PRIMARY");
            if (!primaryRels.isEmpty()) {
                primaryBean = impl;
            }

            // Check for SPRING_CONDITIONAL
            List<Map<String, Object>> condRels = getRelationsOfType(implFqn, "SPRING_CONDITIONAL");
            if (!condRels.isEmpty()) {
                Map<String, Object> condEntry = new LinkedHashMap<>(impl);
                condEntry.put("conditionals", condRels);
                conditionalBeans.add(condEntry);
            }
        }

        result.put("springComponents", springComponents);
        result.put("primaryBean", primaryBean);
        result.put("conditionalBeans", conditionalBeans);

        // Resolution: @Primary wins, else single component, else ambiguous
        if (primaryBean != null) {
            result.put("resolvedBean", primaryBean);
            result.put("resolutionStrategy", "PRIMARY");
        } else if (springComponents.size() == 1) {
            result.put("resolvedBean", springComponents.get(0));
            result.put("resolutionStrategy", "SINGLE_IMPLEMENTATION");
        } else if (springComponents.isEmpty()) {
            result.put("resolvedBean", null);
            result.put("resolutionStrategy", "NO_SPRING_COMPONENT");
        } else {
            result.put("resolvedBean", null);
            result.put("resolutionStrategy", "AMBIGUOUS");
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Rebuild
    // -----------------------------------------------------------------------

    /**
     * Rebuild the entire database from per-file JSON shards.
     * Used after DB corruption or forced rebuild.
     */
    public void rebuildFromShards(IndexFileStore store) throws SQLException, IOException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entities_fts");
            stmt.execute("DELETE FROM entities_meta");
            stmt.execute("DELETE FROM file_status");
        }

        beginTransaction();
        try {
            for (IndexFileStore.FileShard shard : store.readAllShards()) {
                String relPath = shard.relativePath();
                IndexFileStore.FileFingerprint fp = shard.fingerprint();
                upsertFile(relPath, IndexFileStore.shardName(relPath), fp);
                insertEntities(relPath, shard.entities());
            }
            commit();
        } catch (Exception e) {
            rollback();
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // Package-private access for co-located analysis classes
    // -----------------------------------------------------------------------

    /**
     * Exposes the underlying JDBC connection for analysis classes in the same
     * package (e.g. {@link PageRankComputer}, {@link ImpactAnalyzer}).
     * Not intended for use outside of {@code ai.kompile.cli.main.codeindex}.
     */
    Connection getConnection() {
        return conn;
    }

    // -----------------------------------------------------------------------
    // Call-chain / injector private helpers
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> getOutgoingCallRelations(String fqn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM relations WHERE source_fqn = ? AND relation_type = 'CALLS' LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setInt(2, limit);
            return collectRelations(ps);
        }
    }

    private List<Map<String, Object>> getIncomingCallRelations(String fqn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM relations WHERE relation_type = 'CALLS'" +
                " AND (target_fqn = ? OR target_name = ? OR target_fqn LIKE ?) LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setString(2, simpleName(fqn));
            ps.setString(3, "%." + simpleName(fqn));
            ps.setInt(4, limit);
            return collectRelations(ps);
        }
    }

    private List<Map<String, Object>> getRelationsOfType(String fqn, String relType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM relations WHERE source_fqn = ? AND relation_type = ? LIMIT 20")) {
            ps.setString(1, fqn);
            ps.setString(2, relType);
            return collectRelations(ps);
        }
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> rowToEntity(ResultSet rs) throws SQLException {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityType", rs.getString("entity_type"));
        entity.put("name", rs.getString("name"));
        entity.put("fullyQualifiedName", rs.getString("fqn"));
        entity.put("filePath", rs.getString("rel_path"));
        entity.put("language", rs.getString("language"));
        int startLine = rs.getInt("start_line");
        if (!rs.wasNull()) entity.put("startLine", startLine);
        int endLine = rs.getInt("end_line");
        if (!rs.wasNull()) entity.put("endLine", endLine);
        String sig = rs.getString("signature");
        if (sig != null) entity.put("signature", sig);
        String doc = rs.getString("doc_comment");
        if (doc != null) entity.put("docComment", doc);
        String vis = rs.getString("visibility");
        if (vis != null) entity.put("visibility", vis);
        entity.put("indexedAt", rs.getString("indexed_at"));
        String inheritedFrom = rs.getString("inherited_from");
        if (inheritedFrom != null) entity.put("inheritedFrom", inheritedFrom);
        String implementsList = rs.getString("implements_list");
        if (implementsList != null) entity.put("implementsList", implementsList);
        String annotations = rs.getString("annotations");
        if (annotations != null) entity.put("annotations", annotations);
        return entity;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Escape a user query for FTS5 MATCH syntax.
     * Wraps each token in double quotes to prevent syntax errors.
     */
    private static String escapeFts5(String query) {
        // Split on whitespace and quote each token
        String[] tokens = query.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(" ");
            // Replace internal quotes
            String token = tokens[i].replace("\"", "\"\"");
            sb.append("\"").append(token).append("\"");
        }
        return sb.toString();
    }

    /**
     * Find files whose on-disk mtime is newer than the indexed mtime.
     * @param projectRoot root directory of the project
     * @return list of relative paths that are stale in the index
     */
    public List<String> getStaleFiles(Path projectRoot) throws SQLException {
        List<String> stale = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT rel_path, last_modified FROM file_status")) {
            while (rs.next()) {
                String relPath = rs.getString("rel_path");
                long indexedMtime = rs.getLong("last_modified");
                try {
                    Path filePath = projectRoot.resolve(relPath);
                    if (Files.exists(filePath)) {
                        long diskMtime = Files.getLastModifiedTime(filePath).toMillis();
                        if (diskMtime > indexedMtime) {
                            stale.add(relPath);
                        }
                    }
                } catch (IOException ignored) {
                    // Can't stat the file — skip
                }
            }
        }
        return stale;
    }

    /**
     * Check if specific files are stale (on-disk mtime newer than indexed).
     * @param projectRoot root directory of the project
     * @param relPaths    relative paths to check
     * @return set of relative paths that are stale
     */
    public Set<String> getStaleFilesInSet(Path projectRoot, Set<String> relPaths) throws SQLException {
        if (relPaths.isEmpty()) return Set.of();
        Set<String> stale = new LinkedHashSet<>();

        // Batch query file_status for the given paths
        List<String> pathList = new ArrayList<>(relPaths);
        int batchSize = 500;
        for (int start = 0; start < pathList.size(); start += batchSize) {
            List<String> batch = pathList.subList(start, Math.min(start + batchSize, pathList.size()));
            String placeholders = String.join(",", batch.stream().map(f -> "?").toArray(String[]::new));
            String sql = "SELECT rel_path, last_modified FROM file_status WHERE rel_path IN (" + placeholders + ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String relPath = rs.getString("rel_path");
                        long indexedMtime = rs.getLong("last_modified");
                        try {
                            Path filePath = projectRoot.resolve(relPath);
                            if (Files.exists(filePath)) {
                                long diskMtime = Files.getLastModifiedTime(filePath).toMillis();
                                if (diskMtime > indexedMtime) {
                                    stale.add(relPath);
                                }
                            }
                        } catch (IOException ignored) {}
                    }
                }
            }
        }
        return stale;
    }

    /**
     * Get entities for multiple files in a single query.
     * @param relPaths   relative paths to look up
     * @param maxPerFile max entities per file (0 = unlimited)
     * @return map of relPath -> entity list
     */
    public Map<String, List<Map<String, Object>>> getEntitiesForFiles(Set<String> relPaths, int maxPerFile) throws SQLException {
        if (relPaths.isEmpty()) return Map.of();
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();

        List<String> pathList = new ArrayList<>(relPaths);
        int batchSize = 500;
        for (int start = 0; start < pathList.size(); start += batchSize) {
            List<String> batch = pathList.subList(start, Math.min(start + batchSize, pathList.size()));
            String placeholders = String.join(",", batch.stream().map(f -> "?").toArray(String[]::new));
            String sql = "SELECT * FROM entities_meta WHERE rel_path IN (" + placeholders + ") ORDER BY rel_path, start_line";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> entity = rowToEntity(rs);
                        String path = (String) entity.get("filePath");
                        List<Map<String, Object>> list = result.computeIfAbsent(path, k -> new ArrayList<>());
                        if (maxPerFile <= 0 || list.size() < maxPerFile) {
                            list.add(entity);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Get the most recent indexed_at timestamp across all files.
     * @return the latest indexed_at string, or null if no files indexed
     */
    public String getIndexTimestamp() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(indexed_at) FROM file_status")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {}
    }
}
