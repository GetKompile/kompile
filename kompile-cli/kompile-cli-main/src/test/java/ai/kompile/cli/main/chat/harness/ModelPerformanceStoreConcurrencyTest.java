/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPerformanceStoreConcurrencyTest {

    @Test
    void staleConcurrentWritersMergeInsteadOfClobbering(@TempDir Path tempDir) {
        Path storeFile = tempDir.resolve("perf-data.json");
        ModelPerformanceStore first = new ModelPerformanceStore();
        ModelPerformanceStore second = new ModelPerformanceStore();
        first.loadFromFile(storeFile);
        second.loadFromFile(storeFile);

        // Force both JVM-like stores to observe the same empty starting snapshot.
        assertEquals(0, first.size());
        assertEquals(0, second.size());
        first.record(record("session-a", Instant.parse("2026-09-05T00:00:00Z")));
        second.record(record("session-b", Instant.parse("2026-09-05T00:00:01Z")));

        CompletableFuture.allOf(
                CompletableFuture.runAsync(first::flush),
                CompletableFuture.runAsync(second::flush)).join();

        ModelPerformanceStore verifier = new ModelPerformanceStore();
        verifier.loadFromFile(storeFile);
        assertEquals(2, verifier.size());
        assertEquals(Set.of("session-a", "session-b"), verifier.getRecords().stream()
                .map(ModelPerformanceRecord::getSessionId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(Files.isRegularFile(storeFile));
        assertTrue(storeFile.toFile().length() > 0L);
        assertFalse(first.isDirty());
        assertFalse(second.isDirty());
    }

    @Test
    void emptyInterruptedWriteIsAtomicallyRecovered(@TempDir Path tempDir) throws Exception {
        Path storeFile = tempDir.resolve("perf-data.json");
        Files.writeString(storeFile, "");
        ModelPerformanceStore store = new ModelPerformanceStore();
        store.loadFromFile(storeFile);
        store.record(record("recovered", Instant.parse("2026-09-05T00:00:02Z")));

        store.flush();

        ModelPerformanceStore verifier = new ModelPerformanceStore();
        verifier.loadFromFile(storeFile);
        assertEquals(1, verifier.size());
        assertEquals("recovered", verifier.getRecords().get(0).getSessionId());
        assertTrue(storeFile.toFile().length() > 0L);
    }

    private static ModelPerformanceRecord record(String sessionId, Instant timestamp) {
        return ModelPerformanceRecord.builder()
                .sessionId(sessionId)
                .agentName("coder")
                .model("test-model")
                .taskType("general")
                .timestamp(timestamp)
                .build();
    }
}
