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
package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.SubprocessLogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rotation contract of {@link DurableSubprocessLogSink}: a fresh log per app launch
 * (prior run preserved as {@code .log.1}) and an intra-run size cap — the fix for the sink previously
 * appending across every launch (graph-matrix.log reached ~1.5 GB and cumulative greps were bogus).
 */
class DurableSubprocessLogSinkTest {

    private static SubprocessLogEvent ev(String id, String msg) {
        return new SubprocessLogEvent(id, "run1", null,
                SubprocessLogEvent.Stream.STDOUT, "INFO", msg, 1_700_000_000_000L);
    }

    @Test
    void rotatesOnOpen_freshLogPerLaunch_priorRunKeptAsDotOne(@TempDir Path base) throws IOException {
        Path logFile = base.resolve("embedding.log");
        Path rotated = base.resolve("embedding.log.1");

        // Launch 1: three lines.
        DurableSubprocessLogSink first = new DurableSubprocessLogSink(base, 10_000_000);
        first.onLog(ev("embedding", "run1-a"));
        first.onLog(ev("embedding", "run1-b"));
        first.onLog(ev("embedding", "run1-c"));
        first.close();

        assertTrue(Files.exists(logFile), "log should exist after first launch");
        assertFalse(Files.exists(rotated), "no rotation yet — only one launch");
        assertEquals(3, Files.readAllLines(logFile).size());

        // Launch 2 (simulates app restart → new sink over the same dir): one line.
        DurableSubprocessLogSink second = new DurableSubprocessLogSink(base, 10_000_000);
        second.onLog(ev("embedding", "run2-only"));
        second.close();

        // The current log holds ONLY the second run (fresh, not appended) ...
        List<String> current = Files.readAllLines(logFile);
        assertEquals(1, current.size(), "second launch must start a fresh log, not append");
        assertTrue(current.get(0).contains("run2-only"));
        // ... and the first run is preserved as .log.1.
        assertTrue(Files.exists(rotated), "prior launch must be kept as .log.1");
        List<String> prior = Files.readAllLines(rotated);
        assertEquals(3, prior.size());
        assertTrue(prior.get(0).contains("run1-a"));
    }

    @Test
    void capsFileSizeWithinRun_rotatesToDotOne(@TempDir Path base) throws IOException {
        Path logFile = base.resolve("graph-matrix.log");
        Path rotated = base.resolve("graph-matrix.log.1");

        // Tiny 200-byte cap; each line is ~40 bytes → several rotations within the run.
        DurableSubprocessLogSink sink = new DurableSubprocessLogSink(base, 200);
        for (int i = 0; i < 50; i++) {
            sink.onLog(ev("graph-matrix", "line-" + i + "-padding-padding-xx"));
        }
        sink.close();

        assertTrue(Files.exists(rotated), "size cap must have rotated at least once");
        long size = Files.size(logFile);
        // Current file bounded to roughly the cap plus at most one over-cap line.
        assertTrue(size <= 200 + 80, "current log must stay bounded near the cap, was " + size + " bytes");
        // The most recent line is retained in the live file.
        List<String> current = Files.readAllLines(logFile);
        assertTrue(current.get(current.size() - 1).contains("line-49"), "latest line must be in the live log");
    }
}
