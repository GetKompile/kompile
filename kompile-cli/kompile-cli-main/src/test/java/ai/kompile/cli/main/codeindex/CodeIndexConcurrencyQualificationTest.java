/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded lifecycle qualification, not a large-repository performance benchmark.
 * Two real indexer JVMs share ONLY a temporary project/home. Independent WAL readers
 * inspect a deterministically paused partial rebuild and its commit, followed by an
 * unchanged-source relation-extractor upgrade. No application or live index is used.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CodeIndexConcurrencyQualificationTest {
    private static final int FILES = 256;
    private static final String PROJECT = "qualification";

    @Test
    @Timeout(value = 80, unit = TimeUnit.SECONDS)
    void contendingJvmsPublishAtomicGenerationsAndUpgradeUnchangedSources(@TempDir Path temporary) throws Exception {
        Path sandbox = temporary.toAbsolutePath();
        Path home = Files.createDirectory(sandbox.resolve("home"));
        Path root = Files.createDirectory(sandbox.resolve("sources"));
        Set<String> expectedPaths = new TreeSet<>();
        Set<String> expectedCalls = new TreeSet<>();
        for (int i = 0; i < FILES; i++) {
            String file = "Unit" + i + ".java";
            expectedPaths.add(file);
            expectedCalls.add("qualification.Unit" + i + ".run" + i + " -> qualification.Unit" + i + ".helper" + i);
            Files.writeString(root.resolve(file), """
                    package qualification;
                    public class Unit%d {
                        public void run%d() {
                            helper%d();
                        }
                        public void helper%d() {
                        }
                    }
                    """.formatted(i, i, i, i));
        }
        String previousHome = System.getProperty("user.home");
        List<Child> children = new ArrayList<>();
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            System.setProperty("user.home", home.toString());
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            Path indexDir = LocalCodeIndexer.getIndexDir(PROJECT);
            assertTrue(indexDir.startsWith(home));
            assertSuccessful(indexer.index(root, PROJECT, "*.java", null, false, quiet), 0);
            IndexFileStore store = new IndexFileStore(indexDir, JsonUtils.standardMapper());
            Map<String, IndexFileStore.FileFingerprint> fingerprints = store.loadFingerprints();
            assertEquals(expectedPaths, fingerprints.keySet());
            Snapshot initial;
            try (IndexDatabase db = IndexDatabase.openReadOnly(indexDir)) {
                initial = snapshot(db, expectedPaths, expectedCalls);
            }
            assertNotNull(initial.generation());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            Child writer = startChild(home, root, sandbox, "writer");
            children.add(writer);
            awaitMarker(sandbox.resolve("writer-paused"), writer, deadline);
            Child contender = startChild(home, root, sandbox, "contender");
            children.add(contender);
            awaitMarker(sandbox.resolve("contender-blocked"), contender, deadline);
            assertTrue(writer.process().isAlive(), "writer must still hold its partial transaction");

            Set<String> observedGenerations = new HashSet<>();
            try (IndexDatabase pinned = IndexDatabase.openReadOnly(indexDir);
                 IndexDatabase polling = IndexDatabase.openReadOnly(indexDir)) {
                pinned.beginTransaction();
                assertEquals(initial, snapshot(pinned, expectedPaths, expectedCalls));
                // Both independent connections read while the child has cleared/repopulated
                // only 200/256 files; publishing per-file commits would deterministically fail.
                for (int i = 0; i < 3; i++) {
                    polling.beginTransaction();
                    assertEquals(initial, snapshot(polling, expectedPaths, expectedCalls));
                    polling.rollback();
                }
                Files.writeString(sandbox.resolve("release-writer"), "release");
                do {
                    assertTrue(System.nanoTime() < deadline, () -> "Children exceeded shared deadline\n" + logs(children));
                    polling.beginTransaction();
                    Snapshot current = snapshot(polling, expectedPaths, expectedCalls);
                    assertEquals(initial.entities(), current.entities(), "unchanged source membership must remain complete");
                    observedGenerations.add(current.generation());
                    polling.rollback();
                    Thread.sleep(10);
                } while (writer.process().isAlive() || contender.process().isAlive());
                for (Child child : children) {
                    assertTrue(child.process().waitFor(1, TimeUnit.SECONDS), "child exit must be bounded");
                    assertEquals(0, child.process().exitValue(), () -> logs(children));
                }
                // A reader holding an old transaction cannot suddenly switch generations.
                assertEquals(initial, snapshot(pinned, expectedPaths, expectedCalls));
                pinned.rollback();
            }
            Snapshot committed;
            try (IndexDatabase db = IndexDatabase.openReadOnly(indexDir)) {
                committed = snapshot(db, expectedPaths, expectedCalls);
            }
            assertNotEquals(initial.generation(), committed.generation(), "forced child rebuild must commit a new generation");
            assertEquals(initial.entities(), committed.entities());
            assertTrue(Set.of(initial.generation(), committed.generation()).containsAll(observedGenerations),
                    "readers may observe only complete old/new generations");
            assertEquals(committed.generation(), Files.readString(sandbox.resolve("writer-generation")));
            assertEquals(committed.generation(), Files.readString(sandbox.resolve("contender-generation")),
                    "unchanged contender must not create another generation");
            assertPublished(store, committed, expectedPaths, fingerprints);
            assertIntegrity(indexDir);

            // Simulate an index produced before relationExtractionVersion existed. Source
            // bytes, sizes and mtimes stay untouched; only derived CALLS and the marker age.
            Object currentVersion = store.loadMetadata().get("relationExtractionVersion");
            assertTrue(currentVersion instanceof Number && ((Number) currentVersion).intValue() > 0);
            try (IndexLockManager.LockToken ignored = IndexLockManager.acquireWriteLock(PROJECT, indexDir);
                 IndexDatabase db = IndexDatabase.open(indexDir)) {
                db.beginTransaction();
                try (Statement statement = db.getConnection().createStatement()) {
                    assertEquals(FILES, statement.executeUpdate("DELETE FROM relations WHERE relation_type='CALLS'"));
                    db.commit();
                } catch (Exception failure) {
                    db.rollback();
                    throw failure;
                }
                Map<String, Object> oldMetadata = store.loadMetadata();
                oldMetadata.remove("relationExtractionVersion");
                store.saveMetadata(oldMetadata);
                assertEquals(committed.generation(), db.getIndexGeneration());
                assertTrue(db.getCallers("qualification.Unit0.helper0", 10).isEmpty());
            }
            assertSourceFingerprints(root, fingerprints);
            assertSuccessful(indexer.index(root, PROJECT, "*.java", null, false, quiet), 0);
            Snapshot upgraded;
            try (IndexDatabase db = IndexDatabase.openReadOnly(indexDir)) {
                upgraded = snapshot(db, expectedPaths, expectedCalls);
                List<Map<String, Object>> callers = db.getCallers("qualification.Unit0.helper0", 10);
                assertEquals(1, callers.size());
                assertEquals("qualification.Unit0.run0", callers.get(0).get("callerFqn"));
                assertEquals("qualification.Unit0.helper0", callers.get(0).get("targetFqn"));
            }
            assertNotEquals(committed.generation(), upgraded.generation(), "old metadata must cause actual reparse/commit");
            assertEquals(initial.entities(), upgraded.entities());
            assertEquals(currentVersion, store.loadMetadata().get("relationExtractionVersion"));
            assertEquals(FILES, ((Number) store.loadMetadata().get("filesReindexed")).intValue());
            assertPublished(store, upgraded, expectedPaths, fingerprints);
            assertSuccessful(indexer.index(root, PROJECT, "*.java", null, false, quiet), FILES);
            try (IndexDatabase db = IndexDatabase.openReadOnly(indexDir)) {
                assertEquals(upgraded, snapshot(db, expectedPaths, expectedCalls), "upgraded index must now take unchanged fast path");
            }
            assertSourceFingerprints(root, fingerprints);
            assertIntegrity(indexDir);
        } finally {
            // Kill only JVMs owned by this test, even after an assertion or JUnit timeout.
            // Clear/restore interruption so cleanup waits still run after @Timeout interrupts.
            boolean interrupted = Thread.interrupted();
            try {
                for (Child child : children) {
                    if (child.process().isAlive()) child.process().destroyForcibly();
                }
                for (Child child : children) {
                    try {
                        assertTrue(child.process().waitFor(3, TimeUnit.SECONDS), "Exact child survived cleanup: " + child.process().pid());
                    } catch (InterruptedException e) {
                        interrupted = true;
                        child.process().destroyForcibly();
                    }
                }
            } finally {
                if (previousHome == null) System.clearProperty("user.home");
                else System.setProperty("user.home", previousHome);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void assertSuccessful(LocalCodeIndexer.IndexResult result, int skipped) {
        assertEquals(0, result.errors(), result.toString());
        assertEquals(FILES, result.filesProcessed());
        assertEquals(skipped, result.filesSkipped());
        assertEquals(0, result.filesDeleted());
    }

    private record Snapshot(String generation, Set<String> entities) {}

    private static Snapshot snapshot(IndexDatabase db, Set<String> paths, Set<String> calls) throws Exception {
        // Caller opens an explicit transaction for multi-query concurrent snapshots.
        String generation = db.getIndexGeneration();
        assertNotNull(generation);
        assertEquals(paths, db.getAllRelPaths());
        assertEquals(FILES, db.getFileCount());
        Map<String, Integer> counts = db.getEntityCountsByType();
        assertEquals(FILES, counts.getOrDefault("FILE", 0).intValue());
        assertEquals(FILES, counts.getOrDefault("CLASS", 0).intValue());
        assertEquals(FILES * 2, counts.getOrDefault("METHOD", 0).intValue());
        assertEquals(FILES, counts.getOrDefault("PACKAGE", 0).intValue());
        assertEquals(FILES * 5, db.getEntityCount());
        Set<String> entities = new TreeSet<>();
        Set<String> actualCalls = new TreeSet<>();
        int entityRows = 0;
        try (Statement statement = db.getConnection().createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    SELECT e.entity_type, e.fqn, e.indexed_at, p.path FROM entities_meta e
                    LEFT JOIN paths p ON p.id = e.path_id
                    """)) {
                while (rs.next()) {
                    entityRows++;
                    // Only FILE entities carry the batch timestamp in the current parser.
                    if ("FILE".equals(rs.getString(1))) {
                        assertEquals(generation, rs.getString(3), "mixed file and generation transaction");
                    }
                    assertTrue(paths.contains(rs.getString(4)), "entity belongs to an unknown file");
                    assertTrue(entities.add(rs.getString(4) + ":" + rs.getString(1) + ":" + rs.getString(2)),
                            "duplicate entity within a file");
                }
            }
            assertEquals(db.getEntityCount(), entityRows);
            try (ResultSet rs = statement.executeQuery("""
                    SELECT source.fqn, target.fqn FROM relations r
                    LEFT JOIN fqns source ON source.id = r.source_id
                    LEFT JOIN fqns target ON target.id = r.target_id
                    WHERE r.relation_type = 'CALLS'
                    """)) {
                while (rs.next()) {
                    assertTrue(actualCalls.add(rs.getString(1) + " -> " + rs.getString(2)), "duplicate call");
                }
            }
        }
        assertEquals(calls, actualCalls, "resolved CALLS must publish in the same generation as declarations");
        assertEquals(generation, db.getIndexGeneration());
        return new Snapshot(generation, entities);
    }

    private static void assertPublished(IndexFileStore store, Snapshot snapshot, Set<String> paths,
                                        Map<String, IndexFileStore.FileFingerprint> fingerprints) throws Exception {
        Map<String, Object> metadata = store.loadMetadata();
        assertEquals(snapshot.generation(), metadata.get("indexedAt"));
        assertEquals(FILES, ((Number) metadata.get("filesProcessed")).intValue());
        assertEquals(snapshot.entities().size(), ((Number) metadata.get("entitiesFound")).intValue());
        assertEquals(0, ((Number) metadata.get("errors")).intValue());
        assertEquals(fingerprints, store.loadFingerprints());
        List<IndexFileStore.FileShard> shards = store.readAllShardsStrict();
        assertEquals(FILES, shards.size());
        assertEquals(paths, shards.stream().map(IndexFileStore.FileShard::relativePath).collect(Collectors.toSet()));
        Set<String> shardEntities = new TreeSet<>();
        for (IndexFileStore.FileShard shard : shards) {
            assertEquals(fingerprints.get(shard.relativePath()), shard.fingerprint());
            for (Map<String, Object> entity : shard.entities()) {
                if ("FILE".equals(entity.get("entityType"))) {
                    assertEquals(snapshot.generation(), entity.get("indexedAt"));
                }
                assertTrue(shardEntities.add(shard.relativePath() + ":" + entity.get("entityType") + ":" + entity.get("fullyQualifiedName")),
                        "duplicate shard entity");
            }
        }
        assertEquals(snapshot.entities(), shardEntities, "shards and SQLite must have identical membership");
        assertFalse(Files.exists(store.getIndexDir().resolve("update.pending")));
    }

    private static void assertSourceFingerprints(Path root, Map<String, IndexFileStore.FileFingerprint> fingerprints) throws Exception {
        for (var entry : fingerprints.entrySet()) {
            Path file = root.resolve(entry.getKey());
            assertEquals(entry.getValue().lastModified(), Files.getLastModifiedTime(file).toMillis());
            assertEquals(entry.getValue().size(), Files.size(file));
            assertEquals(entry.getValue().sha256(), IndexFileStore.sha256File(file));
        }
    }

    private static void assertIntegrity(Path indexDir) throws Exception {
        try (IndexDatabase db = IndexDatabase.open(indexDir);
             Statement statement = db.getConnection().createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                assertTrue(rs.next());
                assertEquals("ok", rs.getString(1));
                assertFalse(rs.next(), "integrity_check reported additional failures");
            }
            // Check FTS against content too; no repair/rebuild that could conceal damage.
            statement.execute("INSERT INTO entities_fts(entities_fts, rank) VALUES('integrity-check', 1)");
        }
    }

    private record Child(Process process, Path log) {}

    private static Child startChild(Path home, Path root, Path sandbox, String mode) throws Exception {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
        // Surefire supplies the full test classpath; absolutize it before changing child cwd.
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")).split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        Path log = sandbox.resolve(mode + ".log");
        ProcessBuilder builder = new ProcessBuilder(java, "-Xmx256m", "-XX:ActiveProcessorCount=2",
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + sandbox,
                "-cp", classpath, CodeIndexQualificationChild.class.getName(),
                root.toString(), PROJECT, sandbox.toString(), mode)
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // Inherited launcher options must not override the explicit memory/home isolation.
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        builder.environment().put("HOME", home.toString());
        builder.environment().put("USERPROFILE", home.toString());
        return new Child(builder.start(), log);
    }

    private static void awaitMarker(Path marker, Child child, long deadline) throws Exception {
        while (!Files.exists(marker)) {
            assertTrue(child.process().isAlive(), () -> "Child exited before " + marker + "\n" + logs(List.of(child)));
            assertTrue(System.nanoTime() < deadline, () -> "Missing barrier " + marker + "\n" + logs(List.of(child)));
            Thread.sleep(10);
        }
    }

    private static String logs(List<Child> children) {
        return children.stream().map(child -> {
            try {
                return "pid=" + child.process().pid() + " " + child.log() + "\n" + Files.readString(child.log());
            } catch (Exception e) {
                return "Cannot read child log " + child.log() + ": " + e;
            }
        }).collect(Collectors.joining("\n"));
    }
}
