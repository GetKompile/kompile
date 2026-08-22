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
     *
     * <p>v3: relations.file_path (avg ~118 chars, ~120x repeated per distinct
     * path) interned into the {@code paths} table as {@code file_id}, the
     * per-row {@code project_id} column dropped (the DB is per-project), the
     * low-cardinality {@code idx_rel_type} dropped, and entities_fts switched
     * to external-content ({@code content='entities_meta'}) so name/fqn/
     * signature/doc are no longer stored twice. Together these were ~35% of
     * the measured on-disk size (relations + its text indices alone were 62%).</p>
     *
     * <p>v4: entities_meta.rel_path interned the same way ({@code path_id} into
     * the shared {@code paths} table — the text column plus its 57MB index were
     * the next-largest block) and the low-cardinality {@code idx_entities_type}
     * dropped. Entity ids and FTS-indexed column values are preserved by the
     * copy, so the external-content FTS index stays valid without a rebuild.</p>
     *
     * <p>v5: relations' symbol strings interned into the {@code fqns} table
     * ({@code source_id}/{@code target_id}/{@code target_name_id}) — after v4,
     * the three text indices over those columns plus the in-row strings were
     * over half the remaining file. Also sweeps stray indices that servers
     * still running PRE-migration binaries recreate against migrated DBs
     * (their unconditional CREATE INDEX succeeds for surviving columns —
     * observed as idx_rel_type reappearing at 12MB).</p>
     */
    private static final int SCHEMA_VERSION = 5;

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
            // Index DBs run to multiple GB; mmap shares pages via the OS page
            // cache ACROSS the open-per-operation connections in this package,
            // where the 8MB private page cache starts cold on every open.
            stmt.execute("PRAGMA mmap_size=1073741824"); // 1GB
        }
        IndexDatabase db = new IndexDatabase(conn);
        if (db.schemaVersion() != SCHEMA_VERSION) {
            // One transaction so a crash mid-migration can't leave a half-moved
            // relations table; SQLite's write lock also serializes concurrent
            // migrators (the loser re-checks the version and no-ops).
            db.beginTransaction();
            try {
                if (db.schemaVersion() != SCHEMA_VERSION) {
                    db.ensureSchema();
                    db.setSchemaVersion(SCHEMA_VERSION);
                }
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                try { conn.close(); } catch (SQLException ignored) {}
                throw e;
            }
            db.vacuumIfWorthwhile();
        }
        return db;
    }

    /**
     * Reclaim file space after a migration dropped large tables (the freed
     * pages otherwise sit on the freelist forever — reused, but the file never
     * shrinks). Only worth a full rewrite when a meaningful share of the file
     * is free; fresh DBs and already-vacuumed ones skip instantly.
     */
    private void vacuumIfWorthwhile() {
        try (Statement stmt = conn.createStatement()) {
            long freelist;
            long pageCount;
            try (ResultSet rs = stmt.executeQuery("PRAGMA freelist_count")) {
                freelist = rs.next() ? rs.getLong(1) : 0;
            }
            try (ResultSet rs = stmt.executeQuery("PRAGMA page_count")) {
                pageCount = rs.next() ? rs.getLong(1) : 0;
            }
            if (pageCount > 0 && freelist > 2500 && freelist * 100 / pageCount >= 15) {
                CodeIndexDiagnostics.alert("[code-index] vacuuming index db ("
                        + (freelist * 4096 / 1024 / 1024) + "MB reclaimable)...");
                long start = System.currentTimeMillis();
                stmt.execute("VACUUM");
                CodeIndexDiagnostics.alert("[code-index] vacuum done in "
                        + (System.currentTimeMillis() - start) + "ms");
            }
        } catch (SQLException e) {
            // Space reclamation is best-effort; the DB stays fully usable.
            CodeIndexDiagnostics.alert("[code-index] vacuum skipped: " + e.getMessage());
        }
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

            // Interning table for file paths, shared by entities_meta.path_id
            // and relations.file_id — must exist before either migration runs.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS paths (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL UNIQUE
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entities_meta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path_id INTEGER NOT NULL,
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

            migrateEntitiesToInternedPaths(stmt);

            // Entity indices AFTER the migration — indices on a pre-v4 table
            // die with its DROP, so creation must target the rebuilt table.
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_entities_path
                ON entities_meta(path_id)""");
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_entities_name
                ON entities_meta(name)""");
            // No entity_type index: a handful of values over ~10^5 rows — the
            // planner never picks it and it taxes every insert.
            stmt.execute("DROP INDEX IF EXISTS idx_entities_type");

            // External-content FTS5 over entities_meta — the FTS table stores
            // only the inverted index; name/fqn/signature/doc live once in
            // entities_meta instead of twice. Any earlier FTS shape (contentless
            // or regular) is dropped and rebuilt from entities_meta below.
            // NOTE: COUNT(*) on an external-content FTS reads the CONTENT table,
            // so "is the index empty" cannot be queried — track creation instead
            // (a skipped rebuild leaves an empty index whose delete-commands
            // then fail with SQLITE_CORRUPT_VTAB).
            if (tableExists(stmt, "entities_fts") && !isExternalContentFts(stmt)) {
                stmt.execute("DROP TABLE IF EXISTS entities_fts");
            }
            boolean ftsCreated = !tableExists(stmt, "entities_fts");
            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS entities_fts USING fts5(
                    name,
                    fqn,
                    signature,
                    doc_comment,
                    content='entities_meta',
                    content_rowid='id',
                    tokenize='unicode61'
                )""");
            ftsNeedsRebuild = ftsCreated;

            // Interning table for relations' symbol strings (source/target fqns
            // and bare target names). Entities keep their fqn as readable text —
            // this vocabulary is relations-only.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS fqns (
                    id  INTEGER PRIMARY KEY AUTOINCREMENT,
                    fqn TEXT NOT NULL UNIQUE
                )""");

            // --- Relations table for local graph ---
            // file paths AND symbol strings are interned: the repeated text plus
            // the text indices over it were the bulk of the on-disk index.
            // project_id is gone — the DB is per-project.
            migrateRelationsToInternedPaths(stmt);
            migrateRelationsToInternedFqns(stmt);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_source ON relations(source_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_target ON relations(target_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_target_name ON relations(target_name_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rel_file ON relations(file_id)");
            // No relation_type index: 9 values over ~10^6 rows — never the best
            // index, and it taxed every insert and carried 15MB on real DBs.

            // Servers still running pre-migration binaries re-run their own
            // unconditional ensureSchema against this DB and recreate any index
            // whose column survived. Sweep the known strays on every migration.
            stmt.execute("DROP INDEX IF EXISTS idx_rel_type");
            stmt.execute("DROP INDEX IF EXISTS idx_entities_type");
            stmt.execute("DROP INDEX IF EXISTS idx_entities_rel_path");

            // FQN index on entities — speeds up symbol lookup and connectivity resolution
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_fqn ON entities_meta(fqn)");
        }

        // If we just (re)created the FTS table, rebuild it from entities_meta
        if (ftsNeedsRebuild && hasEntities()) {
            rebuildFtsFromMeta();
        }
        ftsNeedsRebuild = false;
    }

    /** Set while ensureSchema (re)creates the FTS table; consumed by the rebuild step. */
    private boolean ftsNeedsRebuild;

    /**
     * v3 → v4: rebuild entities_meta with {@code path_id} referencing
     * {@code paths} instead of the repeated rel_path text. Entity ids and the
     * four FTS-indexed column values are preserved, so the external-content
     * FTS index remains valid without a rebuild. Runs inside the caller's
     * migration transaction; idempotent (column presence decides).
     */
    private void migrateEntitiesToInternedPaths(Statement stmt) throws SQLException {
        if (!tableExists(stmt, "entities_meta") || columnExists(stmt, "entities_meta", "path_id")) {
            return;
        }
        stmt.execute("DROP TABLE IF EXISTS entities_meta_v4");
        stmt.execute("INSERT OR IGNORE INTO paths(path) SELECT DISTINCT rel_path FROM entities_meta");
        stmt.execute("""
            CREATE TABLE entities_meta_v4 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path_id INTEGER NOT NULL,
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
        stmt.execute("""
            INSERT INTO entities_meta_v4(id, path_id, entity_type, name, fqn, language,
                                         start_line, end_line, signature, doc_comment,
                                         visibility, indexed_at, inherited_from,
                                         implements_list, annotations)
            SELECT m.id, p.id, m.entity_type, m.name, m.fqn, m.language,
                   m.start_line, m.end_line, m.signature, m.doc_comment,
                   m.visibility, m.indexed_at, m.inherited_from,
                   m.implements_list, m.annotations
            FROM entities_meta m JOIN paths p ON p.path = m.rel_path""");
        stmt.execute("DROP TABLE entities_meta");
        stmt.execute("ALTER TABLE entities_meta_v4 RENAME TO entities_meta");
    }

    /**
     * v2 → v3: rebuild the relations table with {@code file_id} referencing
     * {@code paths} and without {@code project_id}. Runs inside the caller's
     * migration transaction; idempotent (column presence decides).
     */
    private void migrateRelationsToInternedPaths(Statement stmt) throws SQLException {
        // Fresh DBs get the final shape from migrateRelationsToInternedFqns;
        // this step only lifts v2 tables (file_path text) to the interned-paths
        // intermediate so the fqn transform has one input shape to handle.
        if (!tableExists(stmt, "relations") || columnExists(stmt, "relations", "file_id")) {
            return;
        }
        stmt.execute("DROP TABLE IF EXISTS relations_v3");
        stmt.execute("INSERT OR IGNORE INTO paths(path) SELECT DISTINCT file_path FROM relations");
        stmt.execute("""
            CREATE TABLE relations_v3 (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                source_fqn    TEXT NOT NULL,
                target_name   TEXT NOT NULL,
                target_fqn    TEXT,
                relation_type TEXT NOT NULL,
                file_id       INTEGER NOT NULL,
                line          INTEGER
            )""");
        stmt.execute("""
            INSERT INTO relations_v3(id, source_fqn, target_name, target_fqn,
                                     relation_type, file_id, line)
            SELECT r.id, r.source_fqn, r.target_name, r.target_fqn,
                   r.relation_type, p.id, r.line
            FROM relations r JOIN paths p ON p.path = r.file_path""");
        stmt.execute("DROP TABLE relations");
        stmt.execute("ALTER TABLE relations_v3 RENAME TO relations");
    }

    /**
     * v4 → v5: intern relations' symbol strings into {@code fqns}. Handles the
     * fresh-create case too (new DBs get this shape directly). Idempotent —
     * gated on the source_id column.
     */
    private void migrateRelationsToInternedFqns(Statement stmt) throws SQLException {
        String v5Ddl = """
            CREATE TABLE %s (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id      INTEGER NOT NULL,
                target_name_id INTEGER NOT NULL,
                target_id      INTEGER,
                relation_type  TEXT NOT NULL,
                file_id        INTEGER NOT NULL,
                line           INTEGER
            )""";
        if (!tableExists(stmt, "relations")) {
            stmt.execute(v5Ddl.formatted("relations"));
            return;
        }
        if (columnExists(stmt, "relations", "source_id")) {
            return;
        }
        stmt.execute("DROP TABLE IF EXISTS relations_v5");
        stmt.execute("INSERT OR IGNORE INTO fqns(fqn) SELECT DISTINCT source_fqn FROM relations WHERE source_fqn IS NOT NULL");
        stmt.execute("INSERT OR IGNORE INTO fqns(fqn) SELECT DISTINCT target_name FROM relations WHERE target_name IS NOT NULL");
        stmt.execute("INSERT OR IGNORE INTO fqns(fqn) SELECT DISTINCT target_fqn FROM relations WHERE target_fqn IS NOT NULL");
        stmt.execute(v5Ddl.formatted("relations_v5"));
        stmt.execute("""
            INSERT INTO relations_v5(id, source_id, target_name_id, target_id,
                                     relation_type, file_id, line)
            SELECT r.id, sf.id, tn.id, tf.id, r.relation_type, r.file_id, r.line
            FROM relations r
            JOIN fqns sf ON sf.fqn = r.source_fqn
            JOIN fqns tn ON tn.fqn = r.target_name
            LEFT JOIN fqns tf ON tf.fqn = r.target_fqn""");
        stmt.execute("DROP TABLE relations");
        stmt.execute("ALTER TABLE relations_v5 RENAME TO relations");
    }

    private boolean tableExists(Statement stmt, String table) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','view') AND name='"
                        + table + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private boolean columnExists(Statement stmt, String table, String col) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='" + col + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private boolean isExternalContentFts(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT sql FROM sqlite_master WHERE name='entities_fts'")) {
            return rs.next() && rs.getString(1) != null
                    && rs.getString(1).contains("content='entities_meta'");
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

    private boolean hasEntities() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities_meta")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /**
     * Rebuild the FTS index from entities_meta. Used after schema migration.
     * The FTS5 'rebuild' command repopulates the whole index from the external
     * content table in one statement and is transaction-neutral, so it is safe
     * inside the migration transaction (the old per-row loop toggled autocommit,
     * which would have committed a surrounding migration halfway through).
     */
    private void rebuildFtsFromMeta() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO entities_fts(entities_fts) VALUES('rebuild')");
        }
    }

    // -----------------------------------------------------------------------
    // Path interning
    // -----------------------------------------------------------------------

    /** path → paths.id resolved this connection; connections are per-operation, so this stays small. */
    private final Map<String, Long> pathIdCache = new HashMap<>();

    /**
     * Intern a file path into the {@code paths} table and return its id.
     * Rows are never deleted (a stale path row is a few dozen bytes; the
     * relations pointing at it are what get removed on re-index).
     */
    private long pathId(String path) throws SQLException {
        Long cached = pathIdCache.get(path);
        if (cached != null) return cached;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT OR IGNORE INTO paths(path) VALUES (?)")) {
            ins.setString(1, path);
            ins.executeUpdate();
        }
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM paths WHERE path = ?")) {
            sel.setString(1, path);
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("paths row vanished for: " + path);
                }
                long id = rs.getLong(1);
                pathIdCache.put(path, id);
                return id;
            }
        }
    }

    /**
     * Shared SELECT prefix for relation queries: exposes the interned path and
     * symbol strings under their legacy column aliases so every row reader is
     * unchanged. target_id is nullable (unresolved calls), hence the LEFT JOIN.
     */
    private static final String RELATIONS_SELECT = """
            SELECT r.*, p.path AS file_path, sf.fqn AS source_fqn,
                   tn.fqn AS target_name, tf.fqn AS target_fqn
            FROM relations r
            JOIN paths p ON p.id = r.file_id
            JOIN fqns sf ON sf.id = r.source_id
            JOIN fqns tn ON tn.id = r.target_name_id
            LEFT JOIN fqns tf ON tf.id = r.target_id
            """;

    /** Indexed probe turning a symbol string into its interned id (or NULL when absent). */
    private static final String FQN_ID = "(SELECT id FROM fqns WHERE fqn = ?)";

    /** fqn → fqns.id resolved this connection; connections are per-operation, so this stays small. */
    private final Map<String, Long> fqnIdCache = new HashMap<>();

    /**
     * Intern a symbol string into the {@code fqns} table and return its id.
     * Rows are never deleted — a stale vocabulary row is a few dozen bytes.
     */
    private long fqnId(String fqn) throws SQLException {
        Long cached = fqnIdCache.get(fqn);
        if (cached != null) return cached;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT OR IGNORE INTO fqns(fqn) VALUES (?)")) {
            ins.setString(1, fqn);
            ins.executeUpdate();
        }
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM fqns WHERE fqn = ?")) {
            sel.setString(1, fqn);
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("fqns row vanished for: " + fqn);
                }
                long id = rs.getLong(1);
                fqnIdCache.put(fqn, id);
                return id;
            }
        }
    }

    /**
     * Shared SELECT prefix for entity queries: exposes {@code ep.path} under
     * the legacy {@code rel_path} alias so {@link #rowToEntity} is unchanged.
     */
    private static final String ENTITIES_SELECT =
            "SELECT m.*, ep.path AS rel_path FROM entities_meta m JOIN paths ep ON ep.id = m.path_id ";

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

            // FTS rows first — the external-content 'delete' command needs the
            // original column values, so entities_meta must still be populated
            // (values must byte-match what insertEntities wrote: sig/doc were
            // stored as '' when null, hence the COALESCEs).
            String pathIdsIn = "(SELECT id FROM paths WHERE path IN (" + placeholders + "))";
            String[] statements = {
                    "INSERT INTO entities_fts(entities_fts, rowid, name, fqn, signature, doc_comment) " +
                            "SELECT 'delete', id, name, fqn, COALESCE(signature,''), COALESCE(doc_comment,'') " +
                            "FROM entities_meta WHERE path_id IN " + pathIdsIn,
                    "DELETE FROM entities_meta WHERE path_id IN " + pathIdsIn,
                    "DELETE FROM relations WHERE file_id IN " + pathIdsIn,
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
        long pathId = pathId(relPath);
        try (PreparedStatement metaPs = conn.prepareStatement("""
                INSERT INTO entities_meta
                (id, path_id, entity_type, name, fqn, language, start_line, end_line,
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
                metaPs.setLong(2, pathId);
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
                (source_id, target_name_id, target_id, relation_type, file_id, line)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            for (Map<String, Object> r : relations) {
                String sourceFqn = str(r, "sourceFqn");
                String targetName = str(r, "targetName");
                String targetFqn = str(r, "targetFqn");
                ps.setLong(1, fqnId(sourceFqn != null ? sourceFqn : ""));
                ps.setLong(2, fqnId(targetName != null ? targetName : ""));
                if (targetFqn != null) {
                    ps.setLong(3, fqnId(targetFqn));
                } else {
                    ps.setNull(3, Types.INTEGER);
                }
                ps.setString(4, str(r, "relationType"));
                String relPath = str(r, "filePath");
                ps.setLong(5, pathId(relPath != null ? relPath : filePath));
                ps.setObject(6, r.get("line"));
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
                SELECT m.*, ep.path AS rel_path FROM entities_meta m
                JOIN entities_fts f ON f.rowid = m.id
                JOIN paths ep ON ep.id = m.path_id
                WHERE entities_fts MATCH ? AND m.entity_type = ?
                ORDER BY rank LIMIT ?""";
        } else {
            sql = """
                SELECT m.*, ep.path AS rel_path FROM entities_meta m
                JOIN entities_fts f ON f.rowid = m.id
                JOIN paths ep ON ep.id = m.path_id
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
            sql = ENTITIES_SELECT + """
                WHERE (LOWER(m.name) LIKE ? OR LOWER(m.fqn) LIKE ?
                       OR LOWER(m.signature) LIKE ? OR LOWER(m.doc_comment) LIKE ?)
                  AND m.entity_type = ?
                LIMIT ?""";
        } else {
            sql = ENTITIES_SELECT + """
                WHERE LOWER(m.name) LIKE ? OR LOWER(m.fqn) LIKE ?
                       OR LOWER(m.signature) LIKE ? OR LOWER(m.doc_comment) LIKE ?
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
                     "SELECT language, COUNT(DISTINCT path_id) FROM entities_meta GROUP BY language ORDER BY COUNT(*) DESC")) {
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
                RELATIONS_SELECT + "WHERE p.path = ? LIMIT 1000")) {
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

    /**
     * Rows still lacking a resolved cross-file target, in interned-id space.
     * The '' case: the scalar subquery yields NULL when '' was never interned,
     * and {@code target_id = NULL} is never true — same semantics as before.
     */
    private static final String UNRESOLVED_PRED =
            "(target_id IS NULL OR target_id = target_name_id"
                    + " OR target_id = (SELECT id FROM fqns WHERE fqn = ''))";

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
                "UPDATE relations SET target_id = ? WHERE target_name_id = " + FQN_ID
                        + " AND " + UNRESOLVED_PRED + " AND target_id IS NOT ?")) {
            for (Map.Entry<String, String> e : resolution.entrySet()) {
                // Newly resolved fqns may not be in the vocabulary yet.
                long resolvedId = fqnId(e.getValue());
                ps.setLong(1, resolvedId);
                ps.setString(2, e.getKey());
                ps.setLong(3, resolvedId);
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
                         "SELECT DISTINCT tn.fqn FROM relations r "
                                 + "JOIN fqns tn ON tn.id = r.target_name_id WHERE " + UNRESOLVED_PRED)) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (t != null && !t.isEmpty()) targets.add(t);
                }
            }
            return targets;
        }

        List<String> paths = List.copyOf(changedFiles);
        // Same shape as UNRESOLVED_PRED but r.-qualified for the joined queries.
        String unresolvedR = "(r.target_id IS NULL OR r.target_id = r.target_name_id"
                + " OR r.target_id = (SELECT id FROM fqns WHERE fqn = ''))";
        int batchSize = 500;
        for (int start = 0; start < paths.size(); start += batchSize) {
            List<String> batch = paths.subList(start, Math.min(start + batchSize, paths.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String[] queries = {
                    "SELECT DISTINCT tn.fqn FROM relations r "
                            + "JOIN fqns tn ON tn.id = r.target_name_id WHERE r.file_id IN "
                            + "(SELECT id FROM paths WHERE path IN (" + placeholders + ")) AND "
                            + unresolvedR,
                    "SELECT DISTINCT tn.fqn FROM relations r "
                            + "JOIN fqns tn ON tn.id = r.target_name_id "
                            + "JOIN entities_meta e ON e.name = tn.fqn WHERE e.path_id IN "
                            + "(SELECT id FROM paths WHERE path IN (" + placeholders + ")) AND "
                            + unresolvedR,
                    "SELECT DISTINCT tn.fqn FROM relations r "
                            + "JOIN fqns tn ON tn.id = r.target_name_id "
                            + "JOIN entities_meta e ON e.fqn = tn.fqn WHERE e.path_id IN "
                            + "(SELECT id FROM paths WHERE path IN (" + placeholders + ")) AND "
                            + unresolvedR
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
                ENTITIES_SELECT + "WHERE m.fqn = ? LIMIT 1")) {
            ps.setString(1, fqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToEntity(rs) : null;
            }
        }
    }

    public Map<String, Object> findEntityBySuffix(String name) throws SQLException {
        // A leading-wildcard LIKE cannot use any index, so the old
        // 'fqn LIKE %.name' form full-scanned entities_meta (multi-GB on big
        // projects) on EVERY name-based lookup — trace/callers/impact all
        // funnel through here. Entity names are the last FQN segment, so an
        // equality probe on the indexed name column narrows to a handful of
        // rows and the suffix check only runs against those.
        String simple = simpleName(name);
        try (PreparedStatement ps = conn.prepareStatement(
                ENTITIES_SELECT + "WHERE m.name = ? AND (m.fqn = ? OR m.fqn LIKE ?) LIMIT 1")) {
            ps.setString(1, simple);
            ps.setString(2, name);
            ps.setString(3, "%." + name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToEntity(rs);
            }
        }
        // Nothing under that simple name at all → a suffix scan cannot match
        // either (names are last segments); return fast instead of scanning.
        try (PreparedStatement probe = conn.prepareStatement(
                "SELECT 1 FROM entities_meta WHERE name = ? LIMIT 1")) {
            probe.setString(1, simple);
            try (ResultSet rs = probe.executeQuery()) {
                if (!rs.next()) return null;
            }
        }
        // Rows with the name exist but the dotted suffix didn't match through
        // the indexed probe — legacy scan as a last resort (rare).
        try (PreparedStatement ps = conn.prepareStatement(
                ENTITIES_SELECT + "WHERE m.fqn LIKE ? LIMIT 1")) {
            ps.setString(1, "%." + name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToEntity(rs) : null;
            }
        }
    }

    List<Map<String, Object>> getOutgoingRelations(String fqn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                RELATIONS_SELECT + "WHERE r.source_id = " + FQN_ID + " LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setInt(2, limit);
            return collectRelations(ps);
        }
    }

    List<Map<String, Object>> getIncomingRelations(String fqn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                RELATIONS_SELECT + "WHERE r.target_id = " + FQN_ID + " LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setInt(2, limit);
            return collectRelations(ps);
        }
    }

    List<Map<String, Object>> getEntitiesForFile(String relPath) throws SQLException {
        List<Map<String, Object>> entities = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                ENTITIES_SELECT + "WHERE ep.path = ?")) {
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
            String sql = ENTITIES_SELECT + "WHERE m.fqn IN (" + placeholders + ")";

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
            String sql = RELATIONS_SELECT + "WHERE r.target_id IN "
                    + "(SELECT id FROM fqns WHERE fqn IN (" + placeholders + ")) LIMIT 1000";

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
        // Indexed equality arms only — see getIncomingCallRelations for why the
        // 'LIKE %.name' arm is redundant and scan-inducing.
        String sql = """
            SELECT DISTINCT m.*, ep.path AS rel_path FROM entities_meta m
            JOIN paths ep ON ep.id = m.path_id
            JOIN fqns sf ON sf.fqn = m.fqn
            JOIN relations r ON r.source_id = sf.id
            WHERE (r.target_id = (SELECT id FROM fqns WHERE fqn = ?)
                   OR r.target_name_id = (SELECT id FROM fqns WHERE fqn = ?))
              AND r.relation_type IN ('IMPLEMENTS', 'EXTENDS')
              AND m.entity_type IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD')
            LIMIT ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolvedFqn);
            ps.setString(2, simpleName(resolvedFqn));
            ps.setInt(3, maxResults);
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
        // Indexed equality arms only — target_name always carries the bare
        // callee name, so the old 'target_fqn LIKE %.name' arm added nothing
        // except an un-indexable predicate that scanned every CALLS row.
        String sql = """
            SELECT sf.fqn AS source_fqn, p.path AS file_path, r.line,
                   tn.fqn AS target_name, tf.fqn AS target_fqn,
                   m.entity_type, m.name, m.fqn, m.signature, mp.path AS rel_path, m.start_line
            FROM relations r
            JOIN paths p ON p.id = r.file_id
            JOIN fqns sf ON sf.id = r.source_id
            JOIN fqns tn ON tn.id = r.target_name_id
            LEFT JOIN fqns tf ON tf.id = r.target_id
            LEFT JOIN entities_meta m ON m.fqn = sf.fqn
            LEFT JOIN paths mp ON mp.id = m.path_id
            WHERE r.relation_type = 'CALLS'
              AND (r.target_id = (SELECT id FROM fqns WHERE fqn = ?)
                   OR r.target_name_id = (SELECT id FROM fqns WHERE fqn = ?))
            GROUP BY r.source_id, r.file_id, r.line
            ORDER BY file_path, r.line
            LIMIT ?""";

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolvedFqn);
            ps.setString(2, simpleName(resolvedFqn));
            ps.setInt(3, maxResults);
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

        // Indexed equality arms only — see getIncomingCallRelations for why the
        // 'LIKE %.name' arm is redundant and scan-inducing.
        String sql = """
            SELECT DISTINCT sf.fqn AS source_fqn, p.path AS file_path, r.line,
                   m.entity_type, m.name, m.fqn, mp.path AS rel_path, m.start_line, m.signature
            FROM relations r
            JOIN paths p ON p.id = r.file_id
            JOIN fqns sf ON sf.id = r.source_id
            LEFT JOIN entities_meta m ON m.fqn = sf.fqn
            LEFT JOIN paths mp ON mp.id = m.path_id
            WHERE r.relation_type = 'SPRING_INJECTS'
              AND (r.target_id = (SELECT id FROM fqns WHERE fqn = ?)
                   OR r.target_name_id = (SELECT id FROM fqns WHERE fqn = ?))
            ORDER BY file_path
            LIMIT ?""";

        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolvedFqn);
            ps.setString(2, simpleName(resolvedFqn));
            ps.setInt(3, maxResults);
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
            // External-content FTS forbids plain DELETE — 'delete-all' is the
            // supported wipe for content= tables.
            stmt.execute("INSERT INTO entities_fts(entities_fts) VALUES('delete-all')");
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
                RELATIONS_SELECT + "WHERE r.source_id = " + FQN_ID
                        + " AND r.relation_type = 'CALLS' LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setInt(2, limit);
            return collectRelations(ps);
        }
    }

    private List<Map<String, Object>> getIncomingCallRelations(String fqn, int limit) throws SQLException {
        // No 'target_fqn LIKE %.name' arm: extractors always store target_name
        // as the bare callee name, so the indexed equality is a superset of the
        // suffix match — and one non-indexable OR arm forced a full scan of the
        // CALLS rows PER BFS NODE (getCallChain visits up to 200).
        try (PreparedStatement ps = conn.prepareStatement(
                RELATIONS_SELECT + "WHERE r.relation_type = 'CALLS'" +
                " AND (r.target_id = " + FQN_ID + " OR r.target_name_id = " + FQN_ID + ") LIMIT ?")) {
            ps.setString(1, fqn);
            ps.setString(2, simpleName(fqn));
            ps.setInt(3, limit);
            return collectRelations(ps);
        }
    }

    private List<Map<String, Object>> getRelationsOfType(String fqn, String relType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                RELATIONS_SELECT + "WHERE r.source_id = " + FQN_ID + " AND r.relation_type = ? LIMIT 20")) {
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
            String sql = ENTITIES_SELECT + "WHERE ep.path IN (" + placeholders
                    + ") ORDER BY rel_path, m.start_line";

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
