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

package ai.kompile.app.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProcessWritebackDeadLetterStore}.
 */
class ProcessWritebackDeadLetterStoreTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private ProcessWritebackDeadLetterStore store;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = new ProcessWritebackDeadLetterStore(tempDir, mapper);
    }

    @Test
    void isEnabled_trueWhenDataDirSet() {
        assertTrue(store.isEnabled());
    }

    @Test
    void isEnabled_falseWhenDataDirNull() {
        ProcessWritebackDeadLetterStore disabled = new ProcessWritebackDeadLetterStore(null, mapper);
        assertFalse(disabled.isEnabled());
    }

    @Test
    void append_writesJsonlLine() throws Exception {
        ProcessWritebackDeadLetterStore.DeadLetterEntry e = stepEntry("run-1", "step-A");
        store.append(e);

        Path file = tempDir.resolve(ProcessWritebackDeadLetterStore.DEAD_LETTER_SUBPATH);
        assertTrue(Files.exists(file));
        String content = Files.readString(file).trim();
        assertTrue(content.contains("STEP_COMPLETED"));
        assertTrue(content.contains("run-1"));
        assertTrue(content.contains("step-A"));
    }

    @Test
    void readAll_returnsAllEntries() {
        store.append(stepEntry("run-1", "step-A"));
        store.append(runEntry("run-2"));

        List<ProcessWritebackDeadLetterStore.DeadLetterEntry> entries = store.readAll();
        assertEquals(2, entries.size());
    }

    @Test
    void readAll_survivesRestartByReadingFromFile() throws Exception {
        // Write via one store instance
        ProcessWritebackDeadLetterStore.DeadLetterEntry e = stepEntry("run-persist", "step-P");
        store.append(e);

        // Read via a new instance pointing to the same dir
        ProcessWritebackDeadLetterStore store2 = new ProcessWritebackDeadLetterStore(tempDir, mapper);
        List<ProcessWritebackDeadLetterStore.DeadLetterEntry> loaded = store2.readAll();
        assertEquals(1, loaded.size());
        assertEquals("run-persist", loaded.get(0).runId);
        assertEquals("step-P", loaded.get(0).stepId);
        assertEquals("STEP_COMPLETED", loaded.get(0).callbackType);
    }

    @Test
    void removeSucceeded_removesEntriesAndRewritesFile() throws Exception {
        ProcessWritebackDeadLetterStore.DeadLetterEntry e1 = stepEntry("run-1", "step-A");
        ProcessWritebackDeadLetterStore.DeadLetterEntry e2 = stepEntry("run-2", "step-B");
        store.append(e1);
        store.append(e2);

        int removed = store.removeSucceeded(List.of(e1.id));
        assertEquals(1, removed);
        assertEquals(1, store.size());
        assertEquals("run-2", store.readAll().get(0).runId);

        // File should only contain e2
        Path file = tempDir.resolve(ProcessWritebackDeadLetterStore.DEAD_LETTER_SUBPATH);
        String content = Files.readString(file);
        assertTrue(content.contains("run-2"));
        assertFalse(content.contains("run-1"));
    }

    @Test
    void removeById_removesCorrectEntry() {
        ProcessWritebackDeadLetterStore.DeadLetterEntry e1 = stepEntry("run-A", "step-1");
        ProcessWritebackDeadLetterStore.DeadLetterEntry e2 = runEntry("run-B");
        store.append(e1);
        store.append(e2);

        boolean removed = store.removeById(e1.id);
        assertTrue(removed);
        assertEquals(1, store.size());
        assertEquals("run-B", store.readAll().get(0).runId);
    }

    @Test
    void removeById_returnsFalseWhenNotFound() {
        store.append(stepEntry("run-X", "step-X"));
        assertFalse(store.removeById(UUID.randomUUID().toString()));
        assertEquals(1, store.size());
    }

    @Test
    void disabled_noFileWritten_sizeZero() throws Exception {
        ProcessWritebackDeadLetterStore disabled = new ProcessWritebackDeadLetterStore(null, mapper);
        ProcessWritebackDeadLetterStore.DeadLetterEntry e = stepEntry("run-dis", "step-dis");
        disabled.append(e); // should log WARN and return silently

        assertEquals(0, disabled.size());
        // No file should have been written
        Path file = tempDir.resolve(ProcessWritebackDeadLetterStore.DEAD_LETTER_SUBPATH);
        assertFalse(Files.exists(file), "No file should be written when disabled");
    }

    @Test
    void emptyFile_readAllReturnsEmpty() {
        // Just creating the store without appending
        assertEquals(0, store.size());
    }

    @Test
    void removeSucceeded_emptyList_doesNothing() {
        store.append(stepEntry("run-1", "step-A"));
        int removed = store.removeSucceeded(List.of());
        assertEquals(0, removed);
        assertEquals(1, store.size());
    }

    @Test
    void reloadAfterPartialRemove_correctCount() throws Exception {
        ProcessWritebackDeadLetterStore.DeadLetterEntry e1 = stepEntry("run-1", "step-A");
        ProcessWritebackDeadLetterStore.DeadLetterEntry e2 = stepEntry("run-2", "step-B");
        ProcessWritebackDeadLetterStore.DeadLetterEntry e3 = runEntry("run-3");
        store.append(e1);
        store.append(e2);
        store.append(e3);

        store.removeSucceeded(List.of(e1.id, e3.id));

        // New store reads the rewritten file
        ProcessWritebackDeadLetterStore reloaded = new ProcessWritebackDeadLetterStore(tempDir, mapper);
        List<ProcessWritebackDeadLetterStore.DeadLetterEntry> entries = reloaded.readAll();
        assertEquals(1, entries.size());
        assertEquals("run-2", entries.get(0).runId);
    }

    @Test
    void forStep_populatesAllFields() {
        ProcessWritebackDeadLetterStore.DeadLetterEntry e =
                ProcessWritebackDeadLetterStore.DeadLetterEntry.forStep(
                        "runId", "stepId", "procId",
                        "My Step", "COMPLETED",
                        List.of("node-1"),
                        "tool:ragQuery",
                        "key1,key2", "hash-out", "hash-in",
                        "error msg",
                        "Outputs: key1=val",
                        "KG down", 2);
        assertNotNull(e.id);
        assertEquals("STEP_COMPLETED", e.callbackType);
        assertEquals("runId", e.runId);
        assertEquals("stepId", e.stepId);
        assertEquals("procId", e.processDefinitionId);
        assertEquals("My Step", e.stepName);
        assertEquals("COMPLETED", e.stepStatus);
        assertEquals(List.of("node-1"), e.graphNodeIds);
        assertEquals("tool:ragQuery", e.executedBy);
        assertEquals("key1,key2", e.outputKeys);
        assertEquals("hash-out", e.outputHash);
        assertEquals("hash-in", e.inputHash);
        assertEquals("error msg", e.error);
        assertEquals("Outputs: key1=val", e.outputSummary);
        assertEquals("KG down", e.failureSummary);
        assertEquals(2, e.attemptNumber);
    }

    @Test
    void forRun_populatesAllFields() {
        ProcessWritebackDeadLetterStore.DeadLetterEntry e =
                ProcessWritebackDeadLetterStore.DeadLetterEntry.forRun(
                        "runId", "procId",
                        "COMPLETED", "2026-07-09T10:00:00Z", "2026-07-09T10:05:00Z",
                        List.of("node-A", "node-B"),
                        "KG exploded", 3);
        assertNotNull(e.id);
        assertEquals("RUN_COMPLETED", e.callbackType);
        assertEquals("runId", e.runId);
        assertNull(e.stepId);
        assertEquals("procId", e.processDefinitionId);
        assertEquals("COMPLETED", e.runStatus);
        assertEquals("2026-07-09T10:00:00Z", e.startedAt);
        assertEquals(2, e.graphNodeIds.size());
        assertEquals("KG exploded", e.failureSummary);
        assertEquals(3, e.attemptNumber);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private ProcessWritebackDeadLetterStore.DeadLetterEntry stepEntry(String runId, String stepId) {
        return ProcessWritebackDeadLetterStore.DeadLetterEntry.forStep(
                runId, stepId, "proc-test", "Test Step", "COMPLETED",
                List.of(UUID.randomUUID().toString()),
                null, null, null, null, null, null,
                "test failure", 3);
    }

    private ProcessWritebackDeadLetterStore.DeadLetterEntry runEntry(String runId) {
        return ProcessWritebackDeadLetterStore.DeadLetterEntry.forRun(
                runId, "proc-test", "COMPLETED", null, null,
                null, "test failure", 3);
    }
}
