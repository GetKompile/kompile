/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers I3 from the 2026-09-28 CLI chat-crawler audit: the pdftotext 60s timeout used to start
 * only after the read loop's output ended, so a process that produced output slowly (or a process
 * that hung entirely) could never be killed by the timeout. A watchdog thread now destroys the
 * process independently of whether the read loop is progressing.
 *
 * <p>These call the package-private 4-arg streamPdfTextWithPdftotext(file, output, command,
 * timeoutSeconds) overload directly, injecting a short timeout and a synthetic shell command
 * instead of waiting out the real 60s production budget or depending on a real scanned PDF.</p>
 */
class ProjectCrawlPdftotextWatchdogTest {

    @TempDir
    Path tempDir;

    @Test
    void watchdogKillsAHangingProcessAndReportsTheTimeout() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "/bin/sh must be available for this test");
        Path file = Files.createFile(tempDir.resolve("hanging.pdf"));
        StringWriter output = new StringWriter();
        List<String> command = List.of("/bin/sh", "-c", "while true; do echo x; sleep 0.1; done");

        long start = System.nanoTime();
        IOException failure = assertThrows(IOException.class, () ->
                ProjectCrawlCommand.streamPdfTextWithPdftotext(file, output, command, 1));
        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        assertTrue(failure.getMessage().contains("pdftotext timed out after 1 s for"), failure.getMessage());
        assertTrue(elapsedSeconds < 30,
                "watchdog should fire close to the 1s timeout, took " + elapsedSeconds + "s");
    }

    @Test
    void streamsOutputNormallyWhenTheCommandFinishesBeforeTheTimeout() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "/bin/sh must be available for this test");
        Path file = Files.createFile(tempDir.resolve("normal.pdf"));
        StringWriter output = new StringWriter();
        List<String> command = List.of("/bin/sh", "-c", "printf 'hello pdftotext'");

        ProjectCrawlCommand.streamPdfTextWithPdftotext(file, output, command, 5);

        assertEquals("hello pdftotext", output.toString());
    }

    @Test
    void nonZeroExitSurfacesStderrTailWithoutLeakingItIntoTheExtractedText() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "/bin/sh must be available for this test");
        Path file = Files.createFile(tempDir.resolve("bad.pdf"));
        StringWriter output = new StringWriter();
        List<String> command = List.of("/bin/sh", "-c", "echo boom-stderr-message 1>&2; exit 3");

        IOException failure = assertThrows(IOException.class, () ->
                ProjectCrawlCommand.streamPdfTextWithPdftotext(file, output, command, 5));

        assertTrue(failure.getMessage().contains("boom-stderr-message"), failure.getMessage());
        assertEquals("", output.toString(), "stderr must never leak into the extracted text");
    }
}
