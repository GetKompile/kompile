/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * KGraph reads and rebuilds stage their files in a locked scratch directory beside the archive, and
 * every rebuild deletes the scratch that dead processes left behind. Locks are checked from child
 * JVMs because a POSIX record lock never conflicts with the process that holds it.
 */
class KGraphScratchTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    /** Exit code of {@link LockProbe} when another process holds the lock. */
    private static final int LOCK_HELD = 3;

    @TempDir
    Path dir;

    @Test
    void rebuildScratchIsLockedBesideTheArchiveAndGoneAfterClose() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path scratchDirectory;
        try (KGraphScratch scratch = KGraphScratch.forRebuild(graphs.resolve("graph.kgraph"))) {
            scratchDirectory = scratch.directory();
            assertEquals(graphs.toRealPath(), scratchDirectory.getParent());
            assertTrue(scratchDirectory.getFileName().toString().startsWith(KGraphScratch.EDIT_PREFIX));
            assertEquals(scratchDirectory, scratch.createFile("graph.kgraph.edit-", ".tmp").getParent());
            Files.createDirectories(scratchDirectory.resolve("nested"));
            Files.writeString(scratchDirectory.resolve("nested").resolve("part"), "part");
            assertTrue(lockedAgainstOtherProcesses(scratchDirectory.resolve(KGraphScratch.LOCK_FILE)));
        }
        assertFalse(Files.exists(scratchDirectory));
        assertEquals(List.of(), names(graphs));
    }

    @Test
    void readScratchFallsBackToTheTemporaryDirectoryWhenTheArchiveDirectoryIsReadOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Files.setPosixFilePermissions(graphs, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeFalse(Files.isWritable(graphs), "this user can write to read-only directories");
            Path scratchDirectory;
            try (KGraphScratch scratch = KGraphScratch.forRead(graphs.resolve("graph.kgraph"))) {
                scratchDirectory = scratch.directory();
                assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),
                        scratchDirectory.getParent());
                assertTrue(scratchDirectory.getFileName().toString().startsWith("kompile-kgraph-read-"));
                assertTrue(Files.exists(scratchDirectory.resolve(KGraphScratch.LOCK_FILE)));
            }
            assertFalse(Files.exists(scratchDirectory));
        } finally {
            Files.setPosixFilePermissions(graphs, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void sweepDeletesLockedScratchOfADeadProcessOnceTheGracePasses() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = graphs.resolve("graph.kgraph");
        Path left = scratchLeftBehind(graphs.resolve(KGraphScratch.EDIT_PREFIX + "1"), true, CREATED);
        assertFalse(lockedAgainstOtherProcesses(left.resolve(KGraphScratch.LOCK_FILE)));

        KGraphScratch.sweep(archive, null, CREATED.plus(KGraphScratch.LOCKED_GRACE).minusSeconds(1));
        assertTrue(Files.exists(left.resolve("staged.tmp")));

        KGraphScratch.sweep(archive, null, CREATED.plus(KGraphScratch.LOCKED_GRACE).plusSeconds(1));
        assertEquals(List.of(), names(graphs));
    }

    @Test
    void sweepNeverOpensTheLockOfScratchThisJvmOwns() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = graphs.resolve("graph.kgraph");
        try (KGraphScratch scratch = KGraphScratch.forRead(archive)) {
            Path lock = scratch.directory().resolve(KGraphScratch.LOCK_FILE);
            Files.setLastModifiedTime(scratch.directory(), FileTime.from(CREATED));

            KGraphScratch.sweep(archive, null, CREATED.plus(Duration.ofDays(30)));

            assertTrue(Files.exists(lock));
            // Closing any channel on the lock file would have dropped this JVM's lock on it.
            assertTrue(lockedAgainstOtherProcesses(lock));
        }
        assertEquals(List.of(), names(graphs));
    }

    @Test
    void sweepKeepsScratchAnotherProcessHoldsUntilItLetsGo() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = graphs.resolve("graph.kgraph");
        Path left = scratchLeftBehind(graphs.resolve(KGraphScratch.READ_PREFIX + "2"), true, CREATED);
        Path ready = dir.resolve("holder.ready");
        Path release = dir.resolve("holder.release");
        Path log = Files.createTempFile(dir, "child-", ".log");
        Process holder = startJava(log, LockHolder.class, left.resolve(KGraphScratch.LOCK_FILE), ready, release);
        try {
            awaitReady(ready, holder, log);
            KGraphScratch.sweep(archive, null, CREATED.plus(Duration.ofDays(30)));
            assertTrue(Files.exists(left.resolve("staged.tmp")));
        } finally {
            Files.writeString(release, "release");
            if (!holder.waitFor(30, TimeUnit.SECONDS)) holder.destroyForcibly();
        }
        assertEquals(0, holder.exitValue(), () -> "lock holder log: " + readQuietly(log));

        KGraphScratch.sweep(archive, null, CREATED.plus(Duration.ofDays(30)));
        assertEquals(List.of(), names(graphs));
    }

    @Test
    void sweepDeletesOnlyOldLeftoversOfOlderBuilds() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path tmp = Files.createDirectory(dir.resolve("tmp"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Instant now = Instant.parse("2026-09-30T00:00:00Z");
        Instant old = now.minus(KGraphScratch.LEGACY_AGE).minusSeconds(60);
        Instant recent = now.minus(KGraphScratch.LEGACY_AGE).plusSeconds(60);
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep");
        Files.setLastModifiedTime(precious, FileTime.from(old));
        Files.setLastModifiedTime(outside, FileTime.from(old));

        for (String name : List.of("graph.kgraph", "graph.kgraph.journal", ".graph.kgraph.journal.lock",
                ".graph.kgraph.lock", "graph.kgraph.migration.lock", "graph.kgraph.v2.bak",
                "other.kgraph.edit-1.tmp", "graph.kgraph.edit-2.bin", "graph.kgraph.edit-3.tmp",
                ".graph.kgraph-5.tmp", ".graph.kgraph-6.kgraph")) {
            file(graphs.resolve(name), old);
        }
        file(graphs.resolve("graph.kgraph.edit-4.tmp"), recent);
        scratchLeftBehind(graphs.resolve(KGraphScratch.READ_PREFIX + "7"), false, old);
        scratchLeftBehind(graphs.resolve(KGraphScratch.READ_PREFIX + "8"), false, recent);
        Files.createSymbolicLink(graphs.resolve(KGraphScratch.READ_PREFIX + "9"), outside);
        Files.createSymbolicLink(graphs.resolve("graph.kgraph.edit-10.tmp"), precious);

        for (String name : List.of("kompile-kgraph-edit-11.links",
                ".kompile-kgraph-migration-13.snapshot.journal.lock", "kompile-unified-graph-14.kgraph",
                "kompile-other-16.tmp", "graph.kgraph.edit-17.tmp")) {
            file(tmp.resolve(name), old);
        }
        file(tmp.resolve("kompile-kgraph-link-ids-12.idx"), recent);
        scratchLeftBehind(tmp.resolve("kompile-kgraph-read-15"), false, old);
        scratchLeftBehind(tmp.resolve(KGraphScratch.EDIT_PREFIX + "18"), true, old);

        KGraphScratch.sweep(graphs.resolve("graph.kgraph"), tmp, now);

        assertEquals(sorted(".graph.kgraph.journal.lock", ".graph.kgraph.lock",
                KGraphScratch.READ_PREFIX + "8", KGraphScratch.READ_PREFIX + "9", "graph.kgraph",
                "graph.kgraph.edit-10.tmp", "graph.kgraph.edit-2.bin", "graph.kgraph.edit-4.tmp",
                "graph.kgraph.journal", "graph.kgraph.migration.lock", "graph.kgraph.v2.bak",
                "other.kgraph.edit-1.tmp"), names(graphs));
        assertEquals(sorted("graph.kgraph.edit-17.tmp", "kompile-kgraph-link-ids-12.idx",
                "kompile-other-16.tmp"), names(tmp));
        assertEquals("keep", Files.readString(precious));
    }

    @Test
    void savingSweepsWhatDeadProcessesLeftBesideTheArchive() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = graphs.resolve("graph.kgraph");
        Instant stale = Instant.now().minus(Duration.ofDays(2));
        scratchLeftBehind(graphs.resolve(KGraphScratch.EDIT_PREFIX + "1"), true, stale);
        scratchLeftBehind(graphs.resolve(KGraphScratch.READ_PREFIX + "2"), false, stale);
        file(graphs.resolve("graph.kgraph.edit-3.tmp"), stale);

        compactGraph(archive);

        assertEquals(List.of("graph.kgraph"), names(graphs));
    }

    @Test
    void archiveEditStagesInScratchBesideTheArchiveAndLeavesNothingBehind() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = compactGraph(graphs.resolve("graph.kgraph"));
        assertEquals(List.of("graph.kgraph"), names(graphs));

        Set<String> seen = new TreeSet<>();
        UnifiedGraphArchiveEditor.rewrite(archive, archive,
                new UnifiedGraph().addEntity(SimpleGraphEntity.of("c")), null,
                link -> {
                    try {
                        seen.addAll(shapes(graphs));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                    return true;
                }, Map.of());

        assertTrue(seen.containsAll(Set.of(
                ".kompile-kgraph-edit-N/.lock",
                ".kompile-kgraph-edit-N/graph.kgraph.edit-N.tmp",
                ".kompile-kgraph-edit-N/kompile-kgraph-edit-N.links",
                ".kompile-kgraph-edit-N/kompile-kgraph-edit-N.properties",
                ".kompile-kgraph-edit-N/kompile-kgraph-edit-N.adjacency",
                ".kompile-kgraph-edit-N/kompile-kgraph-adjacency-ids-N.bin",
                ".kompile-kgraph-read-N/.lock",
                ".kompile-kgraph-read-N/archive.kgraph",
                ".kompile-kgraph-read-N/kompile-kgraph-link-ids-N.idx",
                ".kompile-kgraph-read-N/kompile-kgraph-link-ids-N.dat")), () -> "staged: " + seen);
        for (String shape : seen) {
            assertTrue(shape.equals("graph.kgraph") || shape.startsWith(".kompile-kgraph-"), shape);
        }
        assertEquals(List.of("graph.kgraph"), names(graphs));
        try (UnifiedGraphArchive edited = UnifiedGraphArchive.open(archive)) {
            assertEquals(3, edited.entityCount());
            assertEquals(1, edited.linkCount());
        }
        assertEquals(List.of("graph.kgraph"), names(graphs));
    }

    @Test
    void openArchiveStagesBesideItselfUntilClosed() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = compactGraph(graphs.resolve("graph.kgraph"));
        try (UnifiedGraphArchive open = UnifiedGraphArchive.open(archive);
             UnifiedGraphArchive.LinkCursor cursor = open.openLinks();
             CompactAdjacencyCodec.Index adjacency = open.openAdjacencyIndex()) {
            assertNotNull(cursor.next());
            assertEquals(new TreeSet<>(Set.of(
                    "graph.kgraph",
                    ".kompile-kgraph-read-N/.lock",
                    ".kompile-kgraph-read-N/archive.kgraph",
                    ".kompile-kgraph-read-N/kompile-kgraph-link-ids-N.idx",
                    ".kompile-kgraph-read-N/kompile-kgraph-link-ids-N.dat",
                    ".kompile-kgraph-read-N/kompile-kgraph-adjacency-N.bin")), shapes(graphs));
        }
        assertEquals(List.of("graph.kgraph"), names(graphs));
    }

    @Test
    void inPlaceMigrationLeavesTheArchiveItsBackupAndItsLockOnly() throws Exception {
        Path graphs = Files.createDirectory(dir.resolve("graphs"));
        Path archive = legacyGraph(graphs.resolve("graph.kgraph"));

        GraphArchiveMigrator.MigrationResult result = GraphArchiveMigrator.migrateInPlace(archive);

        assertEquals(GraphArchiveMigrator.Status.MIGRATED, result.status());
        assertEquals(List.of("graph.kgraph", "graph.kgraph.migration.lock", "graph.kgraph.v1.bak"),
                names(graphs));
    }

    @Test
    void migrationToAnotherDirectoryLeavesNoScratchInEither() throws Exception {
        Path sources = Files.createDirectory(dir.resolve("sources"));
        Path targets = dir.resolve("targets");
        Path source = legacyGraph(sources.resolve("graph.kgraph"));

        GraphArchiveMigrator.MigrationResult result =
                GraphArchiveMigrator.migrate(source, targets.resolve("graph.kgraph"));

        assertEquals(GraphArchiveMigrator.Status.MIGRATED, result.status());
        assertEquals(List.of("graph.kgraph"), names(sources));
        assertEquals(List.of("graph.kgraph", "graph.kgraph.migration.lock"), names(targets));
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /** Regular files under {@code root}, relative to it, with every run of digits replaced by N. */
    private static Set<String> shapes(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replaceAll("[0-9]+", "N"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static List<String> sorted(String... names) {
        return Stream.of(names).sorted().toList();
    }

    /** A scratch directory as a process that died mid-rebuild leaves it; its mtime is set last. */
    private static Path scratchLeftBehind(Path directory, boolean withLock, Instant modified) throws IOException {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("staged.tmp"), "staged");
        Files.writeString(directory.resolve("nested").resolve("part"), "part");
        if (withLock) Files.createFile(directory.resolve(KGraphScratch.LOCK_FILE));
        Files.setLastModifiedTime(directory, FileTime.from(modified));
        return directory;
    }

    private static void file(Path path, Instant modified) throws IOException {
        Files.writeString(path, path.getFileName().toString());
        Files.setLastModifiedTime(path, FileTime.from(modified));
    }

    private static Path compactGraph(Path archive) throws IOException {
        new UnifiedGraph()
                .addEntity(SimpleGraphEntity.of("a"))
                .addEntity(SimpleGraphEntity.of("b"))
                .addRelation(SimpleGraphRelation.directed("r", "a", "b", "CALLS", 1.0))
                .saveCompact(archive);
        return archive;
    }

    private static Path legacyGraph(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", """
                {"format":"kompile-graph","formatVersion":1,
                 "counts":{"entities":2,"relations":1,"vectorLayers":0},
                 "embeddingDim":0,"meta":{"graphId":"legacy"},
                 "sections":["entities.jsonl","relations.jsonl"],"vectorLayers":[]}
                """);
        entries.put("entities.jsonl", """
                {"id":"a","type":"CODE_SYMBOL","label":"A"}
                {"id":"b","type":"CODE_SYMBOL","label":"B"}
                """);
        entries.put("relations.jsonl", """
                {"id":"r","sourceId":"a","targetId":"b","type":"CALLS"}
                """);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private boolean lockedAgainstOtherProcesses(Path lockFile) throws Exception {
        Path log = Files.createTempFile(dir, "child-", ".log");
        Process probe = startJava(log, LockProbe.class, lockFile);
        if (!probe.waitFor(60, TimeUnit.SECONDS)) {
            probe.destroyForcibly();
            fail("lock probe did not finish: " + readQuietly(log));
        }
        return switch (probe.exitValue()) {
            case LOCK_HELD -> true;
            case 0 -> false;
            default -> fail("lock probe exited with " + probe.exitValue() + ": " + readQuietly(log));
        };
    }

    private static Process startJava(Path log, Class<?> main, Path... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(Path.of(KGraphScratchTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString());
        command.add(main.getName());
        for (Path argument : arguments) command.add(argument.toString());
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitReady(Path ready, Process holder, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(ready)) {
            if (!holder.isAlive()) {
                fail("lock holder exited with " + holder.exitValue() + ": " + readQuietly(log));
            }
            if (System.nanoTime() > deadline) fail("lock holder never took the lock: " + readQuietly(log));
            Thread.sleep(20);
        }
    }

    private static String readQuietly(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException failure) {
            return "(log unreadable: " + failure + ")";
        }
    }

    /** Child JVM: locks args[0], creates args[1], and lets go once args[2] exists. JDK classes only. */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Files.createFile(Path.of(args[1]));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!Files.exists(Path.of(args[2])) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
            }
        }
    }

    /** Child JVM: exits with {@link #LOCK_HELD} when another process holds a lock on args[0]. */
    public static final class LockProbe {
        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                if (lock == null) System.exit(LOCK_HELD);
                lock.release();
            }
        }
    }
}
