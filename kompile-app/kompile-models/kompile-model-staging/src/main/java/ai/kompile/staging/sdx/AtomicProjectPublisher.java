/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.sdx;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Publishes a fully written project archive exactly once across threads and processes. */
final class AtomicProjectPublisher {
    static final String LOCK_DIRECTORY = ".publication-locks";
    private static final ConcurrentMap<Path, LocalLock> LOCAL_LOCKS =
            new ConcurrentHashMap<>();

    private AtomicProjectPublisher() {
    }

    static void publish(Path completedArchive, Path destination) throws IOException {
        Path source = Objects.requireNonNull(completedArchive, "completedArchive")
                .toAbsolutePath().normalize();
        Path output = Objects.requireNonNull(destination, "destination")
                .toAbsolutePath().normalize();
        Path outputDirectory = output.getParent();
        if (outputDirectory == null) {
            throw new IOException("Project output has no parent directory: " + output);
        }
        Files.createDirectories(outputDirectory);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new IOException("Completed project archive is not a regular file: " + source);
        }
        if (!outputDirectory.equals(source.getParent())) {
            throw new IOException(
                    "Atomic project publication requires staging beside the destination: "
                            + source);
        }

        Path lockDirectory = outputDirectory.resolve(LOCK_DIRECTORY);
        createLockDirectory(lockDirectory);
        Path lockPath = lockDirectory.resolve(output.getFileName() + ".lock");
        LocalLock localLock = retainLocalLock(lockPath);
        try {
            synchronized (localLock.monitor) {
                publishUnderFileLock(source, output, lockPath);
            }
        } finally {
            releaseLocalLock(lockPath, localLock);
        }
    }

    private static void publishUnderFileLock(Path source, Path output, Path lockPath)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                FileLock ignored = channel.lock()) {
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(output.toString());
            }
            try {
                Files.move(source, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException(
                        "Filesystem does not support atomic .kproject publication: " + output,
                        unsupported);
            }
        }
    }

    private static void createLockDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException exists) {
            // A persistent lock directory avoids inode replacement races between processes.
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("Project publication lock path is not a directory: " + directory);
        }
    }

    private static LocalLock retainLocalLock(Path lockPath) {
        return LOCAL_LOCKS.compute(lockPath, (ignored, current) -> {
            LocalLock retained = current == null ? new LocalLock() : current;
            retained.references++;
            return retained;
        });
    }

    private static void releaseLocalLock(Path lockPath, LocalLock expected) {
        LOCAL_LOCKS.computeIfPresent(lockPath, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    private static final class LocalLock {
        private final Object monitor = new Object();
        private int references;
    }
}
