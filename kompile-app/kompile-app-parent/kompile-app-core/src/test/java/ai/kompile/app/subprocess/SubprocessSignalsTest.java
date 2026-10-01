/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stopping a child leaves what it wrote before the signal for the parent's readers.
 */
@Timeout(60)
class SubprocessSignalsTest {

    @TempDir
    Path tempDir;

    @Test
    void outputWrittenBeforeTheChildIsTerminatedIsStillRead() throws Exception {
        assertLastLineIsReadAfter(SubprocessSignals::terminate, 143);
    }

    @Test
    void outputWrittenBeforeTheChildIsKilledIsStillRead() throws Exception {
        assertLastLineIsReadAfter(SubprocessSignals::kill, 137);
    }

    /**
     * The child writes a line, waits until the parent has read it on its own, writes its last line and
     * waits to be stopped, which happens with that line still unread in the pipe.
     */
    private void assertLastLineIsReadAfter(Consumer<Process> stop, int exitCode) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(SubprocessProtocolChannel.SHELL)), "this platform has no /bin/sh");
        Path written = tempDir.resolve("written");
        Process process = new ProcessBuilder(SubprocessProtocolChannel.SHELL, "-c",
                "printf 'first\\n'; read go; printf 'last\\n'; : > '" + written + "'; exec sleep 60").start();
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("first", stdout.readLine());
            OutputStream stdin = process.getOutputStream();
            stdin.write('\n');
            stdin.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(written) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(written), "the child wrote its last line");

            stop.accept(process);

            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the child is still running");
            assertEquals(exitCode, process.exitValue());
            assertEquals("last", stdout.readLine());
            assertNull(stdout.readLine());
        } finally {
            process.destroyForcibly();
        }
    }
}
