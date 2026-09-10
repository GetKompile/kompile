/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Non-destructive upgrader for persisted {@code .kgraph} archives.
 *
 * <p>Normal reads remain backward-compatible and never rewrite their source. This class is the
 * explicit migration seam: source-to-target migration is the default, while in-place migration
 * first retains a versioned backup. Legacy v1/v2 archives are transcoded directly to compact v3
 * without constructing a relation object graph.</p>
 */
public final class GraphArchiveMigrator {

    public static final String META_SOURCE_SHA256 = "migration.sourceSha256";
    public static final String META_SOURCE_FORMAT_VERSION = "migration.sourceFormatVersion";

    private GraphArchiveMigrator() { }

    public enum Status {
        MIGRATED,
        SKIPPED_CURRENT,
        SKIPPED_MATCHING_TARGET
    }

    public record MigrationResult(
            Path source, Path target, Path backup, int sourceVersion, Status status) { }

    public static MigrationResult migrate(Path source, Path target) throws IOException {
        Path normalizedSource = requireArchive(source);
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedTarget)) {
            return migrateInPlace(normalizedSource);
        }

        try (SourceSnapshot snapshot = stableSnapshot(normalizedSource)) {
            int sourceVersion = detectVersion(snapshot.path());
            String sourceHash = sha256Logical(snapshot.path());
            Path parent = normalizedTarget.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path lockPath = normalizedTarget.resolveSibling(
                    normalizedTarget.getFileName() + ".migration.lock");
            try (FileChannel lockChannel = FileChannel.open(
                         lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.tryLock()) {
                if (ignored == null) {
                    throw new IOException("Another migration is active for " + normalizedTarget);
                }
                if (Files.exists(normalizedTarget)) {
                    if (sourceVersion == UnifiedGraphFormat.CURRENT_VERSION
                            && sourceHash.equals(sha256(normalizedTarget))
                            && hasAdjacencyIndex(normalizedTarget)) {
                        return new MigrationResult(
                                normalizedSource, normalizedTarget, null, sourceVersion,
                                Status.SKIPPED_MATCHING_TARGET);
                    }
                    try (UnifiedGraphArchive existing = UnifiedGraphArchive.open(normalizedTarget)) {
                        Object rawMeta = existing.manifest().get("meta");
                        Object migratedHash = rawMeta instanceof Map<?, ?> meta
                                ? meta.get(META_SOURCE_SHA256) : null;
                        if (existing.formatVersion() == UnifiedGraphFormat.CURRENT_VERSION
                                && existing.hasAdjacencyIndex()
                                && sourceHash.equals(migratedHash)) {
                            existing.validateAllEntries();
                            return new MigrationResult(
                                    normalizedSource, normalizedTarget, null, sourceVersion,
                                    Status.SKIPPED_MATCHING_TARGET);
                        }
                    }
                }

                Path candidate = Files.createTempFile(
                        parent, "." + normalizedTarget.getFileName() + "-migration-", ".kgraph");
                try {
                    writeCurrentArchive(snapshot.path(), candidate, sourceHash, sourceVersion);
                    moveAtomically(candidate, normalizedTarget);
                } finally {
                    Files.deleteIfExists(candidate);
                }
                return new MigrationResult(
                        normalizedSource, normalizedTarget, null, sourceVersion, Status.MIGRATED);
            }
        }
    }

    public static MigrationResult migrateInPlace(Path source) throws IOException {
        Path normalizedSource = requireArchive(source);
        Path lockPath = normalizedSource.resolveSibling(normalizedSource.getFileName() + ".migration.lock");
        try (FileChannel lockChannel = FileChannel.open(
                     lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.tryLock()) {
            if (ignored == null) {
                throw new IOException("Another migration is active for " + normalizedSource);
            }
            return migrateInPlaceLocked(normalizedSource);
        }
    }

    private static MigrationResult migrateInPlaceLocked(Path normalizedSource) throws IOException {
        try (SourceSnapshot snapshot = stableSnapshot(normalizedSource)) {
            int sourceVersion = detectVersion(snapshot.path());
            if (sourceVersion == UnifiedGraphFormat.CURRENT_VERSION
                    && hasAdjacencyIndex(snapshot.path())) {
                return new MigrationResult(normalizedSource, normalizedSource, null, sourceVersion,
                        Status.SKIPPED_CURRENT);
            }

            String sourceHash = sha256(snapshot.path());

            Path backup = normalizedSource.resolveSibling(
                    normalizedSource.getFileName() + ".v" + sourceVersion + ".bak");
            snapshot.requireSourceUnchanged(normalizedSource);
            if (!Files.exists(backup)) {
                Files.copy(snapshot.path(), backup, StandardCopyOption.COPY_ATTRIBUTES);
            } else if (!sourceHash.equals(sha256(backup))) {
                throw new IOException("Migration backup already exists with different content: " + backup);
            }
            snapshot.requireSourceUnchanged(normalizedSource);
            Path candidate = Files.createTempFile(
                    normalizedSource.getParent(), "." + normalizedSource.getFileName() + "-migration-", ".kgraph");
            try {
                writeCurrentArchive(snapshot.path(), candidate, sourceHash, sourceVersion);
                snapshot.requireSourceUnchanged(normalizedSource);
                moveAtomically(candidate, normalizedSource);
            } finally {
                Files.deleteIfExists(candidate);
            }
            return new MigrationResult(
                    normalizedSource, normalizedSource, backup, sourceVersion, Status.MIGRATED);
        }
    }

    public static List<MigrationResult> migrateDirectory(Path sourceDirectory, Path targetDirectory)
            throws IOException {
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path target = targetDirectory.toAbsolutePath().normalize();
        Files.createDirectories(target);
        List<MigrationResult> results = new ArrayList<>();
        try (var files = Files.list(source)) {
            for (Path archive : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(UnifiedGraphFormat.EXTENSION))
                    .sorted()
                    .toList()) {
                results.add(migrate(archive, target.resolve(archive.getFileName())));
            }
        }
        return List.copyOf(results);
    }

    public static int detectVersion(Path archive) throws IOException {
        Path normalized = requireArchive(archive);
        try (UnifiedGraphArchive graph = UnifiedGraphArchive.open(normalized)) {
            return graph.formatVersion();
        }
    }

    private static boolean hasAdjacencyIndex(Path archive) throws IOException {
        try (UnifiedGraphArchive graph = UnifiedGraphArchive.open(archive)) {
            return graph.hasAdjacencyIndex();
        }
    }

    private static Path requireArchive(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Graph archive path is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Graph archive does not exist: " + normalized);
        }
        return normalized;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256Logical(Path file) throws IOException {
        Path journal = UnifiedGraphMutationJournal.pathFor(file);
        if (!Files.isRegularFile(journal)) return sha256(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path part : List.of(file, journal)) {
                try (var in = Files.newInputStream(part)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeCurrentArchive(
            Path source, Path target, String sourceHash, int sourceVersion) throws IOException {
        if (sourceVersion < 3) {
            LegacyGraphTranscoder.transcode(source, target, sourceHash, sourceVersion);
        } else {
            LegacyGraphTranscoder.copyCurrent(source, target);
        }
    }

    private static SourceSnapshot stableSnapshot(Path source) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            BasicFileAttributes before = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path snapshot = Files.createTempFile("kompile-kgraph-migration-", ".snapshot");
            Path sourceJournal = UnifiedGraphMutationJournal.pathFor(source);
            Path snapshotJournal = UnifiedGraphMutationJournal.pathFor(snapshot);
            boolean retained = false;
            try {
                Files.copy(source, snapshot, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                BasicFileAttributes journalBefore = Files.isRegularFile(sourceJournal)
                        ? Files.readAttributes(sourceJournal, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS) : null;
                if (journalBefore != null) {
                    Files.copy(sourceJournal, snapshotJournal, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                BasicFileAttributes after = Files.readAttributes(
                        source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes journalAfter = Files.isRegularFile(sourceJournal)
                        ? Files.readAttributes(sourceJournal, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS) : null;
                if (sameFileState(before, after) && Files.size(snapshot) == before.size()
                        && (journalBefore == null && journalAfter == null
                        || journalBefore != null && journalAfter != null
                        && sameFileState(journalBefore, journalAfter)
                        && Files.size(snapshotJournal) == journalBefore.size())) {
                    retained = true;
                    return new SourceSnapshot(snapshot, before);
                }
            } finally {
                if (!retained) {
                    Files.deleteIfExists(snapshot);
                    Files.deleteIfExists(snapshotJournal);
                }
            }
        }
        throw new IOException("Graph archive changed while creating a migration snapshot: " + source);
    }

    private static boolean sameFileState(BasicFileAttributes left, BasicFileAttributes right) {
        Object leftKey = left.fileKey();
        Object rightKey = right.fileKey();
        return (leftKey == null && rightKey == null || java.util.Objects.equals(leftKey, rightKey))
                && left.size() == right.size()
                && left.lastModifiedTime().equals(right.lastModifiedTime());
    }

    private record SourceSnapshot(Path path, BasicFileAttributes sourceState) implements AutoCloseable {
        private void requireSourceUnchanged(Path source) throws IOException {
            BasicFileAttributes current = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFileState(sourceState, current)) {
                throw new IOException("Graph archive changed during in-place migration: " + source);
            }
        }

        @Override public void close() throws IOException {
            Files.deleteIfExists(path);
            Files.deleteIfExists(UnifiedGraphMutationJournal.pathFor(path));
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && "--in-place".equals(args[0])) {
            System.out.println(migrateInPlace(Path.of(args[1])));
            return;
        }
        if (args.length == 2) {
            Path source = Path.of(args[0]);
            Path target = Path.of(args[1]);
            if (Files.isDirectory(source)) {
                for (MigrationResult result : migrateDirectory(source, target)) {
                    System.out.println(result);
                }
            } else {
                System.out.println(migrate(source, target));
            }
            return;
        }
        throw new IllegalArgumentException(
                "Usage: GraphArchiveMigrator <source> <target> | --in-place <archive>");
    }
}
