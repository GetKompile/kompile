/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.sdx;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** Holds the exact destination lock from another JVM for an inter-process blocking test. */
public final class AtomicProjectPublisherLockHolderMain {
    private AtomicProjectPublisherLockHolderMain() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Expected: DESTINATION READY_MARKER RELEASE_MARKER");
            System.exit(2);
        }
        try {
            Path destination = Path.of(args[0]).toAbsolutePath().normalize();
            Path ready = Path.of(args[1]);
            Path release = Path.of(args[2]);
            Path lockDirectory = destination.getParent()
                    .resolve(AtomicProjectPublisher.LOCK_DIRECTORY);
            Files.createDirectories(lockDirectory);
            Path lockPath = lockDirectory.resolve(destination.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                Files.writeString(ready, "locked\n", StandardOpenOption.CREATE_NEW);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(release)) {
                    if (System.nanoTime() >= deadline) {
                        throw new IllegalStateException("Timed out waiting to release lock");
                    }
                    Thread.sleep(5L);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            interrupted.printStackTrace(System.err);
            System.exit(3);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(4);
        }
    }
}
