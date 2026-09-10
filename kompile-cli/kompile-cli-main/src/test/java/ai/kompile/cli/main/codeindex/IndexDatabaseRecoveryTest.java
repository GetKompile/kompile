package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IndexDatabaseRecoveryTest {
    @TempDir Path temp;
    private final String project = "index-recovery-test-" + UUID.randomUUID();

    @AfterEach
    void cleanup() throws Exception {
        Path dir = LocalCodeIndexer.getIndexDir(project);
        IndexMaintenance.invalidate(dir);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<String, Object> entity(String name) {
        return Map.of("name", name, "fullyQualifiedName", "example." + name,
                "entityType", "CLASS", "language", "java", "startLine", 1);
    }

    private static void sql(IndexDatabase db, String sql) throws SQLException {
        try (var stmt = db.getConnection().createStatement()) { stmt.execute(sql); }
    }

    private static int count(IndexDatabase db, String table) throws SQLException {
        try (var stmt = db.getConnection().createStatement();
             var rows = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void seed(IndexDatabase db) throws Exception {
        db.beginTransaction();
        db.insertEntities("Alpha.java", List.of(entity("Alpha")));
        db.insertRelations("Alpha.java", List.of(Map.of("sourceFqn", "example.Alpha",
                "targetName", "Beta", "targetFqn", "example.Beta", "relationType", "EXTENDS")));
        db.setIndexGeneration("seed");
        db.commit();
    }

    @Test
    void repairsExternalContentFtsWithoutLosingGenerationOrRelations() throws Exception {
        try (var db = IndexDatabase.open(temp)) {
            seed(db);
            assertFalse(db.checkAndRepair());
            sql(db, "INSERT INTO entities_fts(entities_fts) VALUES('delete-all')");
            assertTrue(db.checkAndRepair());
            assertFalse(db.checkAndRepair());
            assertEquals("seed", db.getIndexGeneration());
            assertEquals(1, count(db, "relations"));
            assertEquals(1, count(db, "entities_meta"));
            assertFalse(db.search("Alpha", null, 10).isEmpty());
        }
    }

    @Test
    void readOnlyFailureRollsBackAndAllowsRepairRetry() throws Exception {
        try (var db = IndexDatabase.open(temp)) {
            seed(db);
            sql(db, "INSERT INTO entities_fts(entities_fts) VALUES('delete-all')");
            sql(db, "PRAGMA query_only=ON");
            SQLException error = assertThrows(SQLException.class, db::checkAndRepair);
            assertFalse(IndexDatabase.isCorruption(error));
            sql(db, "PRAGMA query_only=OFF");
            assertEquals("seed", db.getIndexGeneration());
            assertTrue(db.checkAndRepair());
        }
    }

    @Test
    void busyWriterIsNotCorruptionAndCanRetry() throws Exception {
        try (var owner = IndexDatabase.open(temp); var peer = IndexDatabase.open(temp)) {
            seed(owner);
            owner.beginTransaction();
            owner.setIndexGeneration("uncommitted");
            sql(peer, "PRAGMA busy_timeout=25");
            SQLException error = assertThrows(SQLException.class, peer::checkAndRepair);
            assertEquals(5, error.getErrorCode() & 255);
            assertFalse(IndexDatabase.isCorruption(error));
            owner.rollback();
            assertFalse(peer.checkAndRepair());
            assertEquals("seed", peer.getIndexGeneration());
        }
    }

    @Test
    void invalidDatabaseIsPreservedAndReportsOfflineRecovery() throws Exception {
        byte[] broken = "this is not a SQLite database".repeat(30).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(temp.resolve("index.db"), broken);
        IOException error = assertThrows(IOException.class,
                () -> IndexMaintenance.checkLocked(temp, Map.of(), true));
        assertTrue(error.getMessage().contains("offline recovery"), error.getMessage());
        assertArrayEquals(broken, Files.readAllBytes(temp.resolve("index.db")));
    }

    @Test
    void failedShardRebuildPreservesOldIndex() throws Exception {
        var store = new IndexFileStore(temp, JsonUtils.standardMapper());
        store.writeFileShard("Reject.java", new IndexFileStore.FileFingerprint(1, 2, "sha"), List.of(entity("Reject")));
        try (var db = IndexDatabase.open(temp)) {
            seed(db);
            sql(db, "CREATE TRIGGER reject_insert BEFORE INSERT ON entities_meta WHEN NEW.name='Reject' "
                    + "BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            assertThrows(SQLException.class, () -> db.rebuildFromShards(store));
            assertEquals("seed", db.getIndexGeneration());
            assertEquals(1, count(db, "entities_meta"));
            assertEquals(1, count(db, "relations"));
            assertFalse(db.checkAndRepair(), "rollback must keep FTS consistent without repair");
        }
    }

    @Test
    void unreadableShardDoesNotWipeDatabase() throws Exception {
        var store = new IndexFileStore(temp, JsonUtils.standardMapper());
        store.ensureFilesDir();
        Files.writeString(temp.resolve("files/broken.json"), "not json");
        try (var db = IndexDatabase.open(temp)) {
            seed(db);
            assertThrows(IOException.class, () -> db.rebuildFromShards(store));
            assertEquals(1, count(db, "entities_meta"));
            assertEquals("seed", db.getIndexGeneration());
        }
    }

    @Test
    void missingShardDirectoryDoesNotWipeDatabase() throws Exception {
        var store = new IndexFileStore(temp, JsonUtils.standardMapper());
        try (var db = IndexDatabase.open(temp)) {
            seed(db);
            assertThrows(IOException.class, () -> db.rebuildFromShards(store));
            assertEquals(1, count(db, "entities_meta"));
            assertEquals(1, count(db, "relations"));
            assertEquals("seed", db.getIndexGeneration());
            assertFalse(db.checkAndRepair());
        }
    }

    @Test
    void scopeOnlyUpdateKeepsCommittedGeneration() throws Exception {
        Files.writeString(temp.resolve("Alpha.java"), "public class Alpha {}\n");
        var indexer = new LocalCodeIndexer();
        index(indexer, false);
        Path dir = LocalCodeIndexer.getIndexDir(project);
        var store = new IndexFileStore(dir, JsonUtils.standardMapper());
        Object generation = store.loadMetadata().get("indexedAt");
        try (var quiet = new PrintStream(OutputStream.nullOutputStream())) {
            indexer.index(temp, project, "*.java", null, false, quiet);
        }
        assertEquals("*.java", store.loadMetadata().get("includePatterns"));
        assertEquals(generation, store.loadMetadata().get("indexedAt"));
        assertFalse(IndexMaintenance.repair(project).reindexRequired());
    }

    @Test
    void rollbackInvalidatesInternedIdsOnReusedConnection() throws Exception {
        try (var db = IndexDatabase.open(temp)) {
            db.beginTransaction();
            db.insertEntities("RolledBack.java", List.of(entity("RolledBack")));
            db.insertRelations("RolledBack.java", List.of(Map.of("sourceFqn", "source",
                    "targetName", "target", "relationType", "CALLS")));
            db.rollback();
            db.beginTransaction();
            db.insertEntities("RolledBack.java", List.of(entity("RolledBack")));
            db.insertRelations("RolledBack.java", List.of(Map.of("sourceFqn", "source",
                    "targetName", "target", "relationType", "CALLS")));
            db.commit();
            assertEquals(1, count(db, "paths"));
            assertEquals(2, count(db, "fqns"));
            assertFalse(db.search("RolledBack", null, 10).isEmpty());
            assertFalse(db.checkAndRepair());
        }
    }

    @Test
    void lockOpenFailureReleasesJvmLockForAnotherThread() throws Exception {
        Path notDirectory = temp.resolve("file");
        Files.writeString(notDirectory, "x");
        assertThrows(IOException.class, () -> IndexLockManager.acquireWriteLock(project, notDirectory));
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(pool.submit(() -> {
                try (var ignored = IndexLockManager.acquireWriteLock(project, temp.resolve("valid"))) { return true; }
            }).get(3, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    private void index(LocalCodeIndexer indexer, boolean force) throws Exception {
        try (var quiet = new PrintStream(OutputStream.nullOutputStream())) {
            indexer.index(temp, project, null, null, force, quiet);
        }
    }

    @Test
    void failedBatchRollsBackAndNextPassRecoversPendingPublication() throws Exception {
        Files.writeString(temp.resolve("Alpha.java"), "public class Alpha {}\n");
        var indexer = new LocalCodeIndexer();
        index(indexer, false);
        Path dir = LocalCodeIndexer.getIndexDir(project);
        String generation;
        int originalCount;
        try (var db = IndexDatabase.open(dir)) {
            generation = db.getIndexGeneration();
            originalCount = count(db, "entities_meta");
            // FILE row succeeds first; CLASS row fails before the FTS batch executes.
            sql(db, "CREATE TRIGGER reject_insert BEFORE INSERT ON entities_meta WHEN NEW.name='Changed' "
                    + "BEGIN SELECT RAISE(ABORT, 'injected mid-batch failure'); END");
        }
        Files.writeString(temp.resolve("Alpha.java"), "public class Changed { public void newMethod() {} }\n");
        assertThrows(IOException.class, () -> index(indexer, false));
        assertTrue(Files.exists(dir.resolve("update.pending")));
        try (var db = IndexDatabase.open(dir)) {
            assertEquals(generation, db.getIndexGeneration());
            assertEquals(originalCount, count(db, "entities_meta"));
            assertFalse(db.checkAndRepair(), "failed SQL batch must roll back both content and postings");
            sql(db, "DROP TRIGGER reject_insert");
        }
        index(indexer, false);
        assertFalse(Files.exists(dir.resolve("update.pending")));
        assertFalse(indexer.search(project, "Changed", "CLASS", 10).isEmpty());
    }

    @Test
    void periodicRefreshRepairsUnchangedTreeAndRestoresMissingDatabase() throws Exception {
        Files.writeString(temp.resolve("Alpha.java"), "public class Alpha extends Beta {}\n");
        Files.writeString(temp.resolve("Beta.java"), "public class Beta {}\n");
        var indexer = new LocalCodeIndexer();
        index(indexer, false);
        Path dir = LocalCodeIndexer.getIndexDir(project);
        int relations;
        try (var db = IndexDatabase.open(dir)) {
            relations = count(db, "relations");
            assertTrue(relations > 0);
            sql(db, "INSERT INTO entities_fts(entities_fts) VALUES('delete-all')");
        }
        IndexMaintenance.invalidate(dir);
        assertTrue(IndexAutoRefresher.refresh(indexer, project, 0, null, null).successful());
        assertTrue(IndexMaintenance.status(project).contains("REPAIRED"));
        try (var db = IndexDatabase.open(dir)) { assertFalse(db.checkAndRepair()); }
        // Only this test's DB, with all connections closed; no live user database is touched.
        Files.delete(dir.resolve("index.db"));
        Files.deleteIfExists(dir.resolve("index.db-wal"));
        Files.deleteIfExists(dir.resolve("index.db-shm"));
        assertTrue(IndexAutoRefresher.refresh(indexer, project, 0, null, null).successful());
        try (var db = IndexDatabase.open(dir)) {
            assertEquals(relations, count(db, "relations"));
            assertFalse(db.search("Alpha", "CLASS", 10).isEmpty());
            assertFalse(db.search("Beta", "CLASS", 10).isEmpty());
        }
        Files.delete(temp.resolve("Beta.java"));
        index(indexer, true);
        assertTrue(indexer.search(project, "Beta", "CLASS", 10).stream()
                .noneMatch(row -> "Beta".equals(row.get("name"))), "Alpha still references Beta in its signature");
        assertFalse(new IndexFileStore(dir, JsonUtils.standardMapper()).loadFingerprints().containsKey("Beta.java"));
    }
}
