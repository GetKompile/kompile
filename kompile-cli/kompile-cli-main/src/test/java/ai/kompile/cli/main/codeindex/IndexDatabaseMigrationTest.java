/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v2 → v3 migration: a database written with the old schema (relations carrying
 * project_id + repeated file_path TEXT, regular FTS) must open cleanly, keep
 * every relation, and serve the graph queries through the interned-path shape.
 */
class IndexDatabaseMigrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void buildV2Database() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("index.db").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE entities_meta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rel_path TEXT NOT NULL, entity_type TEXT NOT NULL,
                    name TEXT NOT NULL, fqn TEXT NOT NULL, language TEXT NOT NULL,
                    start_line INTEGER, end_line INTEGER, signature TEXT,
                    doc_comment TEXT, visibility TEXT, indexed_at TEXT,
                    inherited_from TEXT, implements_list TEXT, annotations TEXT
                )""");
            stmt.execute("""
                CREATE VIRTUAL TABLE entities_fts USING fts5(
                    name, fqn, signature, doc_comment, tokenize='unicode61')""");
            stmt.execute("""
                CREATE TABLE relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id TEXT NOT NULL, source_fqn TEXT NOT NULL,
                    target_name TEXT NOT NULL, target_fqn TEXT,
                    relation_type TEXT NOT NULL, file_path TEXT NOT NULL,
                    line INTEGER
                )""");
            stmt.execute("""
                CREATE TABLE file_status (
                    rel_path TEXT PRIMARY KEY, shard_name TEXT NOT NULL,
                    last_modified INTEGER NOT NULL, file_size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL, indexed_at TEXT NOT NULL
                )""");
            stmt.execute("CREATE INDEX idx_rel_file ON relations(file_path)");
            stmt.execute("CREATE INDEX idx_rel_type ON relations(relation_type)");

            stmt.execute("""
                INSERT INTO entities_meta(rel_path, entity_type, name, fqn, language, start_line)
                VALUES ('src/A.java', 'CLASS', 'Alpha', 'com.example.Alpha', 'java', 1),
                       ('src/A.java', 'METHOD', 'run', 'com.example.Alpha.run', 'java', 2),
                       ('src/B.java', 'METHOD', 'work', 'com.example.Beta.work', 'java', 3)""");
            stmt.execute("""
                INSERT INTO entities_fts(rowid, name, fqn, signature, doc_comment)
                SELECT id, name, fqn, '', '' FROM entities_meta""");
            stmt.execute("""
                INSERT INTO relations(project_id, source_fqn, target_name, target_fqn,
                                      relation_type, file_path, line)
                VALUES ('proj', 'com.example.Alpha.run', 'work', 'com.example.Beta.work',
                        'CALLS', 'src/A.java', 10),
                       ('proj', 'com.example.Alpha.run', 'helper', 'helper',
                        'CALLS', 'src/A.java', 11),
                       ('proj', 'com.example.Beta.work', 'other', 'com.example.Gamma.other',
                        'CALLS', 'src/B.java', 20)""");
            stmt.execute("PRAGMA user_version=2");
        }
    }

    @Test
    void readOnlyOpenRefusesLegacySchemaWithoutMigratingIt() throws Exception {
        java.sql.SQLException error = assertThrows(java.sql.SQLException.class,
                () -> IndexDatabase.openReadOnly(tempDir));
        assertTrue(error.getMessage().contains("schema version 2"), error.getMessage());

        String url = "jdbc:sqlite:" + tempDir.resolve("index.db").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "read-only open must not stamp a new schema");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='paths'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "read-only open must not create migration tables");
            }
        }
    }

    @Test
    void migrationUsesTargetNameWhenLegacyTargetIsEmpty() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("index.db").toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE relations SET target_fqn = '' WHERE line = 11");
        }
        try (IndexDatabase db = IndexDatabase.open(tempDir)) {
            db.insertEntities("src/Helper.java", List.of(Map.of("name", "helper",
                    "fullyQualifiedName", "Helper.helper", "entityType", "METHOD", "language", "java")));
            db.ensureConnectivity();
            try (Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT hint.fqn, target.fqn FROM relations r "
                         + "JOIN fqns hint ON hint.id = r.target_hint_id "
                         + "LEFT JOIN fqns target ON target.id = r.target_id WHERE r.line = 11")) {
                assertTrue(rs.next());
                assertEquals("helper", rs.getString(1));
                assertEquals("Helper.helper", rs.getString(2));
            }
        }
    }

    @Test
    void readOnlyV5AndV6QueriesDoNotRequireOrCreateTargetHints() throws Exception {
        // Build real pre-v7 shapes, not just a downgraded user_version stamp.
        try (IndexDatabase db = IndexDatabase.open(tempDir);
             Statement stmt = db.getConnection().createStatement()) {
            db.setIndexGeneration("old-generation");
            stmt.execute("DROP INDEX idx_rel_target_hint");
            stmt.execute("ALTER TABLE relations DROP COLUMN target_hint_id");
            stmt.execute("PRAGMA user_version=6");
        }
        for (int version : new int[]{6, 5}) {
            if (version == 5) {
                try (Connection conn = DriverManager.getConnection(
                        "jdbc:sqlite:" + tempDir.resolve("index.db").toAbsolutePath());
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE index_metadata");
                    stmt.execute("PRAGMA user_version=5");
                }
            }
            try (IndexDatabase db = IndexDatabase.openReadOnly(tempDir)) {
                assertEquals(version == 6 ? "old-generation" : null, db.getIndexGeneration());
                assertEquals(2, db.getEntitiesForFile("src/A.java").size());
                assertFalse(db.getCallers("work", 10).isEmpty());
                assertEquals(2, ((List<?>) db.getFileGraph("src/A.java").get("outgoingRelations")).size());
                java.util.ArrayList<Map<String, Object>> edges = new java.util.ArrayList<>();
                db.visitGraphSnapshot(entity -> { }, edges::add);
                assertEquals(3, edges.size());
                assertThrows(java.sql.SQLException.class, () -> db.setIndexGeneration("blocked"));
                try (Statement stmt = db.getConnection().createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                        assertTrue(rs.next());
                        assertEquals(version, rs.getInt(1));
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pragma_table_info('relations') "
                            + "WHERE name = 'target_hint_id'")) {
                        assertTrue(rs.next());
                        assertEquals(0, rs.getInt(1));
                    }
                }
            }
        }
    }

    @Test
    void migratedHintsPreserveQualificationAfterTargetDeletionAndReopen() throws Exception {
        try (IndexDatabase db = IndexDatabase.open(tempDir)) {
            try (Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT hint.fqn FROM relations r "
                         + "JOIN fqns hint ON hint.id = r.target_hint_id WHERE r.line = 10")) {
                assertTrue(rs.next());
                assertEquals("com.example.Beta.work", rs.getString(1));
            }
            db.deleteFile("src/B.java");
        }
        try (IndexDatabase db = IndexDatabase.open(tempDir)) {
            db.insertEntities("src/Other.java", List.of(Map.of("name", "work",
                    "fullyQualifiedName", "com.example.Other.work", "entityType", "METHOD",
                    "language", "java", "startLine", 1)));
            db.ensureConnectivity();
            try (Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT target_id FROM relations WHERE line = 10")) {
                assertTrue(rs.next(), "incoming call site survives target deletion");
                assertNull(rs.getObject(1), "qualified legacy hint must not bind to Other.work");
            }
        }
    }

    @Test
    void migratesV2DataAndServesQueries() throws Exception {
        try (IndexDatabase db = IndexDatabase.open(tempDir)) {
            // Shape: interned columns present, legacy columns gone.
            String url = "jdbc:sqlite:" + tempDir.resolve("index.db").toAbsolutePath();
            try (Connection conn = DriverManager.getConnection(url);
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                    assertTrue(rs.next());
                    assertEquals(7, rs.getInt(1), "schema version stamped");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('relations') "
                                + "WHERE name IN ('file_path','project_id','source_fqn','target_fqn','target_name')")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "legacy relation columns dropped");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('relations') "
                                + "WHERE name IN ('source_id','target_id','target_name_id','target_hint_id')")) {
                    assertTrue(rs.next());
                    assertEquals(4, rs.getInt(1), "interned symbol columns present");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM fqns")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 5, "symbol vocabulary populated");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('entities_meta') "
                                + "WHERE name = 'rel_path'")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "entities rel_path column dropped");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('entities_meta') "
                                + "WHERE name = 'path_id'")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "entities path_id column present");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM relations")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt(1), "every relation survived");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities_meta")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt(1), "every entity survived");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM paths")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "two distinct paths interned");
                }
            }

            // Queries resolve through the interned shape with real path strings.
            List<Map<String, Object>> callers = db.getCallers("work", 10);
            assertFalse(callers.isEmpty());
            assertEquals("com.example.Alpha.run", callers.get(0).get("callerFqn"));
            assertEquals("src/A.java", callers.get(0).get("callSiteFile"));
            assertEquals("src/A.java",
                    db.findEntityByFqn("com.example.Alpha.run").get("filePath"),
                    "entity file resolves through paths join");

            List<Map<String, Object>> entities = db.getEntitiesForFile("src/A.java");
            assertEquals(2, entities.size(), "entities found via interned path");
            assertEquals("src/A.java", entities.get(0).get("filePath"));

            Map<String, Object> fileGraph = db.getFileGraph("src/A.java");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outgoing =
                    (List<Map<String, Object>>) fileGraph.get("outgoingRelations");
            assertEquals(2, outgoing.size(), "both relations from src/A.java found via paths join");
            assertEquals("src/A.java", outgoing.get(0).get("filePath"));

            // FTS rebuilt as external-content and searchable.
            List<Map<String, Object>> hits = db.search("Alpha", null, 10);
            assertFalse(hits.isEmpty(), "FTS should find Alpha after rebuild");
        }
    }

    @Test
    void deleteAndReinsertRoundTripAfterMigration() throws Exception {
        try (IndexDatabase db = IndexDatabase.open(tempDir)) {
            db.deleteFile("src/A.java");
            assertTrue(db.getFileGraph("src/A.java")
                    .get("outgoingRelations").toString().equals("[]"));
            assertTrue(db.search("Alpha", null, 10).isEmpty(),
                    "FTS rows for the deleted file must be gone");

            db.insertRelations("src/A.java", List.of(Map.of(
                    "sourceFqn", "com.example.Alpha.run",
                    "targetName", "work",
                    "targetFqn", "com.example.Beta.work",
                    "relationType", "CALLS",
                    "filePath", "src/A.java",
                    "line", 12)));
            assertFalse(db.getCallers("work", 10).isEmpty());
        }
    }

    /**
     * The path deployed DBs actually take: v3 (interned relations, external-content
     * FTS, entities still carrying rel_path). The entities copy preserves ids and
     * indexed values, so the FTS index must remain searchable WITHOUT a rebuild.
     */
    @Test
    void migratesV3EntitiesPreservingFtsIndex() throws Exception {
        // Rebuild the fixture as v3: run the old fixture through... simpler: build v3 directly.
        Path v3Dir = tempDir.resolve("v3");
        java.nio.file.Files.createDirectories(v3Dir);
        String url = "jdbc:sqlite:" + v3Dir.resolve("index.db").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE entities_meta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rel_path TEXT NOT NULL, entity_type TEXT NOT NULL,
                    name TEXT NOT NULL, fqn TEXT NOT NULL, language TEXT NOT NULL,
                    start_line INTEGER, end_line INTEGER, signature TEXT,
                    doc_comment TEXT, visibility TEXT, indexed_at TEXT,
                    inherited_from TEXT, implements_list TEXT, annotations TEXT
                )""");
            stmt.execute("CREATE TABLE paths (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE)");
            stmt.execute("""
                CREATE TABLE relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_fqn TEXT NOT NULL, target_name TEXT NOT NULL,
                    target_fqn TEXT, relation_type TEXT NOT NULL,
                    file_id INTEGER NOT NULL, line INTEGER
                )""");
            stmt.execute("""
                CREATE TABLE file_status (
                    rel_path TEXT PRIMARY KEY, shard_name TEXT NOT NULL,
                    last_modified INTEGER NOT NULL, file_size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL, indexed_at TEXT NOT NULL
                )""");
            stmt.execute("""
                CREATE VIRTUAL TABLE entities_fts USING fts5(
                    name, fqn, signature, doc_comment,
                    content='entities_meta', content_rowid='id', tokenize='unicode61')""");
            stmt.execute("""
                INSERT INTO entities_meta(rel_path, entity_type, name, fqn, language, start_line)
                VALUES ('src/Zeta.java', 'CLASS', 'Zeta', 'com.example.Zeta', 'java', 1)""");
            stmt.execute("INSERT INTO paths(path) VALUES ('src/Zeta.java')");
            stmt.execute("""
                INSERT INTO relations(source_fqn, target_name, target_fqn, relation_type, file_id, line)
                VALUES ('com.example.Zeta.run', 'go', 'com.example.Zeta.go', 'CALLS', 1, 5)""");
            stmt.execute("INSERT INTO entities_fts(entities_fts) VALUES('rebuild')");
            stmt.execute("PRAGMA user_version=3");
        }

        try (IndexDatabase db = IndexDatabase.open(v3Dir)) {
            // FTS index preserved across the entities table swap — no rebuild ran,
            // so a hit proves the old index still aligns with the copied ids.
            assertFalse(db.search("Zeta", null, 10).isEmpty(),
                    "FTS index must survive the v3->v4 entities copy");
            assertEquals("src/Zeta.java",
                    db.findEntityByFqn("com.example.Zeta").get("filePath"));
            // And FTS delete-commands still work against the preserved index.
            db.deleteFile("src/Zeta.java");
            assertTrue(db.search("Zeta", null, 10).isEmpty());
        }
    }

    @Test
    void reopenAfterMigrationIsANoOp() throws Exception {
        try (IndexDatabase first = IndexDatabase.open(tempDir)) {
            assertNotNull(first.getGraphStats());
        }
        try (IndexDatabase second = IndexDatabase.open(tempDir)) {
            assertEquals(3, ((Map<?, ?>) second.getGraphStats().get("relationsByType"))
                    .values().stream().mapToInt(v -> (Integer) v).sum());
        }
    }
}
