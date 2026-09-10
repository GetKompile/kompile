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

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Test-only JVM entry point; never starts a CLI agent, watcher, or application. */
public final class CodeIndexQualificationChild {
    private CodeIndexQualificationChild() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String project = args[1];
        Path signals = Path.of(args[2]);
        String mode = args[3];
        if (!mode.equals("writer") && !mode.equals("contender")) {
            throw new IllegalArgumentException("Unknown fixture mode: " + mode);
        }
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!home.equals(signals.resolve("home").toAbsolutePath().normalize())
                || !root.toAbsolutePath().normalize().equals(signals.resolve("sources").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Qualification requires its isolated home and source root");
        }
        boolean writer = mode.equals("writer");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        // This progress callback runs after 200 file writes, inside the real index transaction.
        // Holding it gives the parent a deterministic read window, not a scheduling-based sleep.
        PrintStream progress = writer ? new PrintStream(System.out, true) {
            private boolean paused;

            @Override
            public void print(String text) {
                super.print(text);
                if (!paused && text.startsWith("  Indexed 200/")) {
                    paused = true;
                    try {
                        Files.writeString(signals.resolve("writer-paused"), "200");
                        awaitRelease(signals, deadline);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Writer interrupted", e);
                    }
                }
            }
        } : System.out;
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        int conflicts = 0;
        while (true) {
            if (System.nanoTime() >= deadline) throw new IOException("Child index deadline exceeded");
            try {
                LocalCodeIndexer.IndexResult result = indexer.index(root, project, "*.java", null, writer, progress);
                if (result.errors() != 0 || result.filesProcessed() != 256
                        || result.filesSkipped() != (writer ? 0 : 256)
                        || result.filesDeleted() != 0) {
                    throw new AssertionError("Unexpected child result: " + result);
                }
                if (!writer && conflicts == 0) throw new AssertionError("No real cross-JVM contention observed");
                try (IndexDatabase db = IndexDatabase.openReadOnly(LocalCodeIndexer.getIndexDir(project))) {
                    Files.writeString(signals.resolve(mode + "-generation"), db.getIndexGeneration());
                }
                System.out.println("QUALIFIED " + mode + " conflicts=" + conflicts + " " + result);
                return;
            } catch (IOException failure) {
                // The public lock API is fail-fast, not a blocking cross-process queue.
                // Retry ONLY that documented failure, never SQLite, extraction or other I/O failures.
                if (writer || !("Index is locked by another process for project '" + project + "'.")
                        .equals(failure.getMessage())) throw failure;
                conflicts++;
                Files.writeString(signals.resolve("contender-blocked"), Integer.toString(conflicts));
                awaitRelease(signals, deadline);
                Thread.sleep(20);
            }
        }
    }

    private static void awaitRelease(Path signals, long deadline) throws IOException, InterruptedException {
        while (!Files.exists(signals.resolve("release-writer"))) {
            if (System.nanoTime() >= deadline) throw new IOException("Parent did not release qualification barrier");
            Thread.sleep(10);
        }
    }
}
