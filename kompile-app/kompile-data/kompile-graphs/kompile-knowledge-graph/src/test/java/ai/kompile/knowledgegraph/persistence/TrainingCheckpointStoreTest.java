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
package ai.kompile.knowledgegraph.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TrainingCheckpointStore}.
 *
 * <p>Verifies the complete checkpoint lifecycle:
 * <ol>
 *   <li>Cold-start: no checkpoint → {@link Optional#empty()}</li>
 *   <li>Save and load a checkpoint; all fields survive the round-trip</li>
 *   <li>Overwrite with a newer checkpoint; loaded value reflects the update</li>
 *   <li>Clear: checkpoint is deleted; subsequent load returns empty</li>
 *   <li>Null {@code mebnBackupId} serializes and deserializes correctly</li>
 *   <li>Corrupt file: load returns empty (no exception)</li>
 * </ol>
 * </p>
 */
class TrainingCheckpointStoreTest {

    @TempDir
    Path tempDir;

    // ── Helper: create a store rooted at the temp dir ─────────────────────────

    private TrainingCheckpointStore storeFor(Path base) throws Exception {
        TrainingCheckpointStore store = new TrainingCheckpointStore();
        // Inject dataDir via the package-visible checkpointPath logic
        Field f = TrainingCheckpointStore.class.getDeclaredField("dataDir");
        f.setAccessible(true);
        f.set(store, base.toString());
        return store;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void coldStart_noFile_returnsEmpty() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);
        Optional<TrainingCheckpointStore.TrainingCheckpoint> cp = store.load(42L);
        assertTrue(cp.isEmpty(), "Expected empty Optional when no checkpoint file exists");
    }

    @Test
    void saveAndLoad_allFieldsSurviveRoundTrip() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);

        TrainingCheckpointStore.TrainingCheckpoint original = TrainingCheckpointStore.of(
                42L, 7L, "42:cascade", 3, "20250622T120000Z");
        store.save(original);

        Optional<TrainingCheckpointStore.TrainingCheckpoint> loaded = store.load(42L);
        assertTrue(loaded.isPresent(), "Checkpoint should be present after save");
        TrainingCheckpointStore.TrainingCheckpoint cp = loaded.get();

        assertEquals(42L, cp.factSheetId());
        assertEquals(7L, cp.cascadesCompleted());
        assertEquals("42:cascade", cp.pslProgramKey());
        assertEquals(3, cp.pslWeightVersion());
        assertEquals("20250622T120000Z", cp.mebnBackupId());
        assertNotNull(cp.checkpointedAt(), "checkpointedAt should be non-null");
    }

    @Test
    void saveAndLoad_nullMebnBackupId_roundTrips() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);

        TrainingCheckpointStore.TrainingCheckpoint original = TrainingCheckpointStore.of(
                10L, 1L, "10:cascade", 1, null);
        store.save(original);

        Optional<TrainingCheckpointStore.TrainingCheckpoint> loaded = store.load(10L);
        assertTrue(loaded.isPresent());
        assertNull(loaded.get().mebnBackupId(), "null mebnBackupId should survive round-trip");
        assertEquals(1L, loaded.get().cascadesCompleted());
        assertEquals(1, loaded.get().pslWeightVersion());
    }

    @Test
    void overwrite_replacesExistingCheckpoint() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);

        store.save(TrainingCheckpointStore.of(5L, 2L, "5:cascade", 1, null));
        store.save(TrainingCheckpointStore.of(5L, 9L, "5:cascade", 4, "20250622T130000Z"));

        Optional<TrainingCheckpointStore.TrainingCheckpoint> loaded = store.load(5L);
        assertTrue(loaded.isPresent());
        assertEquals(9L, loaded.get().cascadesCompleted(), "Should reflect the newer checkpoint");
        assertEquals(4, loaded.get().pslWeightVersion());
        assertEquals("20250622T130000Z", loaded.get().mebnBackupId());
    }

    @Test
    void clear_deletesFile_subsequentLoadIsEmpty() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);

        store.save(TrainingCheckpointStore.of(99L, 5L, "99:cascade", 2, null));
        assertFalse(store.load(99L).isEmpty(), "Sanity: checkpoint should exist before clear");

        store.clear(99L);
        assertTrue(store.load(99L).isEmpty(), "After clear, load should return empty");
    }

    @Test
    void clear_idempotent_noExceptionWhenFileAbsent() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);
        // Double-clear: should not throw
        store.clear(123L);
        store.clear(123L);
        assertTrue(store.load(123L).isEmpty());
    }

    @Test
    void corruptFile_loadsEmpty_noException() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);
        // Write a garbage file to the checkpoint path
        Path cpPath = store.checkpointPath(77L);
        cpPath.getParent().toFile().mkdirs();
        java.nio.file.Files.writeString(cpPath, "not-json-at-all {{{}}}");

        Optional<TrainingCheckpointStore.TrainingCheckpoint> loaded = store.load(77L);
        assertTrue(loaded.isEmpty(), "Corrupt file should yield empty, not throw");
    }

    @Test
    void separateFactSheets_doNotInterfere() throws Exception {
        TrainingCheckpointStore store = storeFor(tempDir);

        store.save(TrainingCheckpointStore.of(1L, 3L, "1:cascade", 1, null));
        store.save(TrainingCheckpointStore.of(2L, 7L, "2:cascade", 5, "bak-ts"));

        assertEquals(3L, store.load(1L).get().cascadesCompleted());
        assertEquals(7L, store.load(2L).get().cascadesCompleted());

        store.clear(1L);
        assertTrue(store.load(1L).isEmpty());
        assertFalse(store.load(2L).isEmpty(), "Clearing factSheet=1 must not affect factSheet=2");
    }
}
