/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.unified;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scratch directory for one KGraph read or rebuild, created beside the archive it serves.
 *
 * <p>Staging files used to go to {@code java.io.tmpdir}, often a RAM-backed tmpfs, and were
 * removed only by {@code finally} blocks, which never run when a JVM dies mid-edit. A scratch
 * directory holds an exclusive lock on its {@code .lock} file for its whole life, so every rebuild
 * can tell a live directory from one a dead process left behind, and deletes the latter.</p>
 *
 * <p>POSIX record locks belong to the process, and closing any channel on a file drops every lock
 * this JVM holds on it. The sweep therefore never opens the lock file of a directory this JVM
 * owns: {@link #OWNED} records each one from before it is created until after it is deleted.</p>
 */
final class KGraphScratch implements AutoCloseable {

    static final String READ_PREFIX = ".kompile-kgraph-read-";
    static final String EDIT_PREFIX = ".kompile-kgraph-edit-";
    static final String LOCK_FILE = ".lock";
    /** A lockable directory younger than this may belong to a process that has not locked it yet. */
    static final Duration LOCKED_GRACE = Duration.ofMinutes(5);
    /** Age after which lockless directories and loose files from older builds are deleted. */
    static final Duration LEGACY_AGE = Duration.ofHours(24);

    private static final String FALLBACK_READ_PREFIX = "kompile-kgraph-read-";
    private static final List<String> TEMPORARY_DIRECTORY_PREFIXES =
            List.of(READ_PREFIX, EDIT_PREFIX, FALLBACK_READ_PREFIX);
    private static final List<String> TEMPORARY_FILE_PREFIXES =
            List.of("kompile-kgraph-", ".kompile-kgraph-migration-", "kompile-unified-graph-");
    private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
    private static final Object SWEEP_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private KGraphScratch(Path directory, FileChannel lockChannel, FileLock lock) {
        this.directory = directory;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    /** Scratch beside {@code archive}, or in {@code java.io.tmpdir} when its directory is not writable. */
    static KGraphScratch forRead(Path archive) throws IOException {
        IOException adjacentFailure = null;
        Path parent = archive.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            try {
                // Android native images default java.io.tmpdir to /tmp, which is not writable by
                // applications. Keeping the transient reference beside an app-owned graph also
                // lets the hard-link fast path remain on the same filesystem.
                return create(parent, READ_PREFIX);
            } catch (IOException failure) {
                adjacentFailure = failure;
            }
        }
        try {
            return create(temporaryRoot(), FALLBACK_READ_PREFIX);
        } catch (IOException fallbackFailure) {
            if (adjacentFailure != null) fallbackFailure.addSuppressed(adjacentFailure);
            throw fallbackFailure;
        }
    }

    /** Sweeps what dead processes left behind, then creates scratch beside {@code target}. */
    static KGraphScratch forRebuild(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("KGraph archive has no parent directory: " + normalized);
        sweep(normalized);
        return create(parent, EDIT_PREFIX);
    }

    Path directory() { return directory; }

    Path createFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(directory, prefix, suffix);
    }

    /** A new file in {@code directory}, or in {@code java.io.tmpdir} when there is none. */
    static Path temporaryFile(Path directory, String prefix, String suffix) throws IOException {
        return directory == null
                ? Files.createTempFile(prefix, suffix)
                : Files.createTempFile(directory, prefix, suffix);
    }

    /** Best effort: whatever survives is swept by a later rebuild once the lock is released. */
    @Override
    public void close() {
        quietly(() -> deleteTree(directory, true));
        quietly(lock::release);
        quietly(lockChannel::close);
        quietly(() -> Files.deleteIfExists(directory.resolve(LOCK_FILE)));
        quietly(() -> Files.deleteIfExists(directory));
        OWNED.remove(directory);
    }

    /** Deletes scratch that dead processes left beside {@code archive} and in {@code java.io.tmpdir}. */
    static void sweep(Path archive) {
        sweep(archive, temporaryRoot(), Instant.now());
    }

    static void sweep(Path archive, Path temporaryRoot, Instant now) {
        synchronized (SWEEP_LOCK) {
            Path normalized = archive.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent != null) sweepArchiveDirectory(parent, normalized.getFileName().toString(), now);
            if (temporaryRoot != null) sweepTemporaryRoot(temporaryRoot, now);
        }
    }

    private static KGraphScratch create(Path parent, String prefix) throws IOException {
        Path realParent = parent.toRealPath();
        while (true) {
            Path candidate = realParent.resolve(prefix + Long.toUnsignedString(RANDOM.nextLong()));
            if (!OWNED.add(candidate)) continue;
            try {
                createPrivateDirectory(candidate);
            } catch (FileAlreadyExistsException collision) {
                OWNED.remove(candidate);
                continue;
            } catch (IOException | RuntimeException failure) {
                OWNED.remove(candidate);
                throw failure;
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(candidate.resolve(LOCK_FILE),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new KGraphScratch(candidate, channel, channel.lock());
            } catch (IOException | RuntimeException failure) {
                if (channel != null) quietly(channel::close);
                quietly(() -> Files.deleteIfExists(candidate.resolve(LOCK_FILE)));
                quietly(() -> Files.deleteIfExists(candidate));
                OWNED.remove(candidate);
                throw failure;
            }
        }
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(directory);
        }
    }

    private static void sweepArchiveDirectory(Path parent, String archiveName, Instant now) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent.toRealPath())) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(READ_PREFIX) || name.startsWith(EDIT_PREFIX)) {
                    sweepDirectory(entry, now);
                } else if (isLegacyStagingFile(name, archiveName)) {
                    deleteFileOlderThan(entry, now.minus(LEGACY_AGE));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the next rebuild sweeps again.
        }
    }

    /** Staging files that builds before scratch directories wrote beside the archive. */
    private static boolean isLegacyStagingFile(String name, String archiveName) {
        if (name.startsWith(archiveName + ".edit-")) return name.endsWith(".tmp");
        return name.startsWith("." + archiveName + "-")
                && (name.endsWith(".tmp") || name.endsWith(".kgraph"));
    }

    private static void sweepTemporaryRoot(Path temporaryRoot, Instant now) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(temporaryRoot.toRealPath())) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (startsWithAny(name, TEMPORARY_DIRECTORY_PREFIXES)) {
                    sweepDirectory(entry, now);
                } else if (startsWithAny(name, TEMPORARY_FILE_PREFIXES)) {
                    deleteFileOlderThan(entry, now.minus(LEGACY_AGE));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the next rebuild sweeps again.
        }
    }

    private static void sweepDirectory(Path candidate, Instant now) {
        if (OWNED.contains(candidate)) return;
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) return;
            Instant modified = attributes.lastModifiedTime().toInstant();
            FileChannel channel = openLockFile(candidate);
            if (channel == null) {
                // Left by a build that predates the lock file, or created a moment ago.
                if (modified.isBefore(now.minus(LEGACY_AGE))) deleteTree(candidate, false);
                return;
            }
            try (channel) {
                FileLock held;
                try {
                    held = channel.tryLock();
                } catch (OverlappingFileLockException lockedInThisJvm) {
                    return;
                }
                if (held == null) return;
                try {
                    if (!modified.isBefore(now.minus(LOCKED_GRACE))) return;
                    deleteTree(candidate, true);
                } finally {
                    held.release();
                }
            }
            Files.deleteIfExists(candidate.resolve(LOCK_FILE));
            Files.deleteIfExists(candidate);
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the next rebuild sweeps again.
        }
    }

    private static FileChannel openLockFile(Path directory) throws IOException {
        try {
            return FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.WRITE);
        } catch (NoSuchFileException lockless) {
            return null;
        }
    }

    private static void deleteFileOlderThan(Path file, Instant cutoff) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the next rebuild sweeps again.
        }
    }

    /** Deletes a tree without following links, keeping the root and its lock file when asked. */
    private static void deleteTree(Path root, boolean keepRootAndLock) throws IOException {
        Path lockFile = root.resolve(LOCK_FILE);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!keepRootAndLock || !file.equals(lockFile)) Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null && !(failure instanceof NoSuchFileException)) throw failure;
                if (!keepRootAndLock || !directory.equals(root)) Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Path temporaryRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private static void quietly(IoAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best effort; see close().
        }
    }
}
