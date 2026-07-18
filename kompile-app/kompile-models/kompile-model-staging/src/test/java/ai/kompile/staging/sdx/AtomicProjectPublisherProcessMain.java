/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.sdx;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** Separate-JVM entry point used to verify the project publisher's operating-system lock. */
public final class AtomicProjectPublisherProcessMain {
    static final int ALREADY_EXISTS_EXIT = 17;
    private static final long START_TIMEOUT_SECONDS = 15L;

    private AtomicProjectPublisherProcessMain() {
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Expected: SOURCE DESTINATION READY_MARKER START_MARKER");
            System.exit(2);
        }
        try {
            Path source = Path.of(args[0]);
            Path destination = Path.of(args[1]);
            Path ready = Path.of(args[2]);
            Path start = Path.of(args[3]);
            Files.writeString(ready, "ready\n", StandardOpenOption.CREATE_NEW);

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
            while (!Files.exists(start)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Timed out waiting for publication start");
                }
                Thread.sleep(5L);
            }
            AtomicProjectPublisher.publish(source, destination);
        } catch (FileAlreadyExistsException expected) {
            System.exit(ALREADY_EXISTS_EXIT);
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
