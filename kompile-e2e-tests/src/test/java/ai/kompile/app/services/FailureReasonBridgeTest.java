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

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.services.subprocess.SubprocessRestartManager;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FailureReasonBridge} — string-to-enum conversion, enum-to-enum mapping,
 * pattern matching, OOM detection, recoverability, and descriptions.
 */
class FailureReasonBridgeTest {

    private FailureReasonBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new FailureReasonBridge();
    }

    // ─── toJobHistoryReason(String) — null/blank ──────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void toJobHistoryReason_nullOrBlank_returnsUnknown(String input) {
        assertEquals(IndexingJobHistory.FailureReason.UNKNOWN, bridge.toJobHistoryReason(input));
    }

    // ─── toJobHistoryReason(String) — direct enum match ───────────────

    @Test
    void toJobHistoryReason_directEnumName_matches() {
        assertEquals(IndexingJobHistory.FailureReason.OUT_OF_MEMORY,
                bridge.toJobHistoryReason("OUT_OF_MEMORY"));
    }

    @Test
    void toJobHistoryReason_directEnumName_caseInsensitive() {
        assertEquals(IndexingJobHistory.FailureReason.USER_CANCELLED,
                bridge.toJobHistoryReason("user_cancelled"));
    }

    // ─── toJobHistoryReason(String) — pattern matching ────────────────

    @ParameterizedTest
    @CsvSource({
            "'Model not found in registry',MODEL_NOT_FOUND",
            "'Staging service error',STAGING_ERROR",
            "'Registry unavailable',STAGING_ERROR",
            "'Out of memory during embedding',OUT_OF_MEMORY",
            "'OOM error occurred',OUT_OF_MEMORY",
            "'Memory kill triggered',MEMORY_KILLED",
            "'User cancelled the job',USER_CANCELLED",
            "'Connection timeout occurred',TIMEOUT",
            "'Operation timed out',TIMEOUT",
            "'Load error reading file',LOAD_ERROR",
            "'Conversion failed for document',CONVERSION_ERROR",
            "'Chunk splitting failed',CHUNKING_ERROR",
            "'Embedding generation error',EMBEDDING_ERROR",
            "'Indexing write failure',INDEXING_ERROR",
            "'Subprocess crashed',SUBPROCESS_ERROR",
            "'Process died unexpectedly',SUBPROCESS_ERROR",
            "'IO read failure',IO_ERROR",
            "'Network connection refused',IO_ERROR",
            "'Invalid input format',INVALID_INPUT"
    })
    void toJobHistoryReason_patternMatching(String input, String expected) {
        IndexingJobHistory.FailureReason expectedReason =
                IndexingJobHistory.FailureReason.valueOf(expected);
        assertEquals(expectedReason, bridge.toJobHistoryReason(input));
    }

    @Test
    void toJobHistoryReason_noMatch_returnsUnknown() {
        assertEquals(IndexingJobHistory.FailureReason.UNKNOWN,
                bridge.toJobHistoryReason("some completely unrelated error message"));
    }

    // ─── toJobHistoryReason(SubprocessRestartManager.FailureReason) ───

    @Test
    void toJobHistoryReason_subprocessNull_returnsUnknown() {
        assertEquals(IndexingJobHistory.FailureReason.UNKNOWN,
                bridge.toJobHistoryReason((SubprocessRestartManager.FailureReason) null));
    }

    @Test
    void toJobHistoryReason_subprocessOom_mapsToOom() {
        assertEquals(IndexingJobHistory.FailureReason.OUT_OF_MEMORY,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.OUT_OF_MEMORY));
    }

    @Test
    void toJobHistoryReason_subprocessOomKilled_mapsToMemoryKilled() {
        assertEquals(IndexingJobHistory.FailureReason.MEMORY_KILLED,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.OOM_KILLED));
    }

    @Test
    void toJobHistoryReason_subprocessGpuOom_mapsToOom() {
        assertEquals(IndexingJobHistory.FailureReason.OUT_OF_MEMORY,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.GPU_OUT_OF_MEMORY));
    }

    @Test
    void toJobHistoryReason_subprocessNativeCrash_mapsToSubprocess() {
        assertEquals(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.NATIVE_CRASH));
    }

    @Test
    void toJobHistoryReason_subprocessTimeout_mapsToTimeout() {
        assertEquals(IndexingJobHistory.FailureReason.TIMEOUT,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.TIMEOUT));
    }

    @Test
    void toJobHistoryReason_subprocessStalledNoHeartbeat_mapsToSubprocess() {
        assertEquals(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.STALLED_NO_HEARTBEAT));
    }

    @Test
    void toJobHistoryReason_subprocessBatchTooLarge_mapsToInvalidInput() {
        assertEquals(IndexingJobHistory.FailureReason.INVALID_INPUT,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE));
    }

    @Test
    void toJobHistoryReason_subprocessCancelled_mapsToUserCancelled() {
        assertEquals(IndexingJobHistory.FailureReason.USER_CANCELLED,
                bridge.toJobHistoryReason(SubprocessRestartManager.FailureReason.CANCELLED));
    }

    // ─── toJobHistoryReason(IngestProgressUpdate.FailureReason) ───────

    @Test
    void toJobHistoryReason_dtoNull_returnsUnknown() {
        assertEquals(IndexingJobHistory.FailureReason.UNKNOWN,
                bridge.toJobHistoryReason((IngestProgressUpdate.FailureReason) null));
    }

    @Test
    void toJobHistoryReason_dtoOom_mapsToOom() {
        assertEquals(IndexingJobHistory.FailureReason.OUT_OF_MEMORY,
                bridge.toJobHistoryReason(IngestProgressUpdate.FailureReason.OUT_OF_MEMORY));
    }

    @Test
    void toJobHistoryReason_dtoNativeCrash_mapsToSubprocess() {
        assertEquals(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR,
                bridge.toJobHistoryReason(IngestProgressUpdate.FailureReason.NATIVE_CRASH));
    }

    @Test
    void toJobHistoryReason_dtoRestartExhausted_mapsToSubprocess() {
        assertEquals(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR,
                bridge.toJobHistoryReason(IngestProgressUpdate.FailureReason.RESTART_EXHAUSTED));
    }

    @Test
    void toJobHistoryReason_dtoProcessStuck_mapsToSubprocess() {
        assertEquals(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR,
                bridge.toJobHistoryReason(IngestProgressUpdate.FailureReason.PROCESS_STUCK));
    }

    // ─── toDtoReason ──────────────────────────────────────────────────

    @Test
    void toDtoReason_null_returnsUnknown() {
        assertEquals(IngestProgressUpdate.FailureReason.UNKNOWN,
                bridge.toDtoReason(null));
    }

    @Test
    void toDtoReason_none_mapsToUnknown() {
        assertEquals(IngestProgressUpdate.FailureReason.UNKNOWN,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.NONE));
    }

    @Test
    void toDtoReason_oom_mapsToOom() {
        assertEquals(IngestProgressUpdate.FailureReason.OUT_OF_MEMORY,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.OUT_OF_MEMORY));
    }

    @Test
    void toDtoReason_memoryKilled_mapsToOomKilled() {
        assertEquals(IngestProgressUpdate.FailureReason.OOM_KILLED,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.MEMORY_KILLED));
    }

    @Test
    void toDtoReason_timeout_mapsToProcessStuck() {
        assertEquals(IngestProgressUpdate.FailureReason.PROCESS_STUCK,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.TIMEOUT));
    }

    @Test
    void toDtoReason_subprocessError_mapsToNativeCrash() {
        assertEquals(IngestProgressUpdate.FailureReason.NATIVE_CRASH,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR));
    }

    @Test
    void toDtoReason_modelNotFound_mapsToEmbeddingError() {
        assertEquals(IngestProgressUpdate.FailureReason.EMBEDDING_ERROR,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.MODEL_NOT_FOUND));
    }

    @Test
    void toDtoReason_conversionError_mapsToLoadError() {
        assertEquals(IngestProgressUpdate.FailureReason.LOAD_ERROR,
                bridge.toDtoReason(IndexingJobHistory.FailureReason.CONVERSION_ERROR));
    }

    // ─── toRestartReason ──────────────────────────────────────────────

    @Test
    void toRestartReason_null_returnsUnknown() {
        assertEquals(SubprocessRestartManager.FailureReason.UNKNOWN,
                bridge.toRestartReason(null));
    }

    @Test
    void toRestartReason_oom_mapsToOom() {
        assertEquals(SubprocessRestartManager.FailureReason.OUT_OF_MEMORY,
                bridge.toRestartReason(IndexingJobHistory.FailureReason.OUT_OF_MEMORY));
    }

    @Test
    void toRestartReason_memoryKilled_mapsToOomKilled() {
        assertEquals(SubprocessRestartManager.FailureReason.OOM_KILLED,
                bridge.toRestartReason(IndexingJobHistory.FailureReason.MEMORY_KILLED));
    }

    @Test
    void toRestartReason_userCancelled_mapsToCancelled() {
        assertEquals(SubprocessRestartManager.FailureReason.CANCELLED,
                bridge.toRestartReason(IndexingJobHistory.FailureReason.USER_CANCELLED));
    }

    @Test
    void toRestartReason_subprocessError_mapsToNativeCrash() {
        assertEquals(SubprocessRestartManager.FailureReason.NATIVE_CRASH,
                bridge.toRestartReason(IndexingJobHistory.FailureReason.SUBPROCESS_ERROR));
    }

    @Test
    void toRestartReason_loadError_mapsToUnknown() {
        assertEquals(SubprocessRestartManager.FailureReason.UNKNOWN,
                bridge.toRestartReason(IndexingJobHistory.FailureReason.LOAD_ERROR));
    }

    // ─── isOomRelated ─────────────────────────────────────────────────

    @Test
    void isOomRelated_null_returnsFalse() {
        assertFalse(bridge.isOomRelated(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OOM error", "OUT_OF_MEMORY", "MEMORY_KILLED", "MEMORY_PRESSURE",
            "Java out of memory", "Out Of Memory Error"})
    void isOomRelated_oomStrings_returnsTrue(String reason) {
        assertTrue(bridge.isOomRelated(reason));
    }

    @Test
    void isOomRelated_unrelatedString_returnsFalse() {
        assertFalse(bridge.isOomRelated("Timeout error"));
    }

    // ─── isModelOrStagingIssue ────────────────────────────────────────

    @Test
    void isModelOrStagingIssue_null_returnsFalse() {
        assertFalse(bridge.isModelOrStagingIssue(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_NOT_FOUND", "STAGING_ERROR", "Model not found in registry",
            "Staging service unavailable", "Registry lookup failed"})
    void isModelOrStagingIssue_stagingStrings_returnsTrue(String reason) {
        assertTrue(bridge.isModelOrStagingIssue(reason));
    }

    @Test
    void isModelOrStagingIssue_unrelated_returnsFalse() {
        assertFalse(bridge.isModelOrStagingIssue("Timeout error"));
    }

    // ─── isRecoverable ────────────────────────────────────────────────

    @Test
    void isRecoverable_null_returnsFalse() {
        assertFalse(bridge.isRecoverable(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUT_OF_MEMORY", "MEMORY_KILLED", "SUBPROCESS_ERROR", "TIMEOUT"})
    void isRecoverable_recoverableReasons_returnsTrue(String reason) {
        assertTrue(bridge.isRecoverable(IndexingJobHistory.FailureReason.valueOf(reason)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "USER_CANCELLED", "LOAD_ERROR", "INVALID_INPUT",
            "MODEL_NOT_FOUND", "STAGING_ERROR", "UNKNOWN"})
    void isRecoverable_nonRecoverableReasons_returnsFalse(String reason) {
        assertFalse(bridge.isRecoverable(IndexingJobHistory.FailureReason.valueOf(reason)));
    }

    // ─── getDescription ───────────────────────────────────────────────

    @Test
    void getDescription_null_returnsUnknownError() {
        assertEquals("Unknown error", bridge.getDescription(null));
    }

    @Test
    void getDescription_none_returnsNoFailure() {
        assertEquals("No failure", bridge.getDescription(IndexingJobHistory.FailureReason.NONE));
    }

    @Test
    void getDescription_oom_returnsOutOfMemory() {
        assertEquals("Out of memory error",
                bridge.getDescription(IndexingJobHistory.FailureReason.OUT_OF_MEMORY));
    }

    @Test
    void getDescription_timeout_returnsTimedOut() {
        assertEquals("Operation timed out",
                bridge.getDescription(IndexingJobHistory.FailureReason.TIMEOUT));
    }

    @Test
    void getDescription_allEnumValues_returnNonNull() {
        for (IndexingJobHistory.FailureReason reason : IndexingJobHistory.FailureReason.values()) {
            assertNotNull(bridge.getDescription(reason),
                    "Description should not be null for " + reason);
        }
    }

    // ─── roundtrip consistency ─────────────────────────────────────────

    @Test
    void roundtrip_subprocessToJobHistoryToDto_allValues() {
        for (SubprocessRestartManager.FailureReason sr : SubprocessRestartManager.FailureReason.values()) {
            IndexingJobHistory.FailureReason jhReason = bridge.toJobHistoryReason(sr);
            assertNotNull(jhReason, "toJobHistoryReason should not return null for " + sr);
            IngestProgressUpdate.FailureReason dtoReason = bridge.toDtoReason(jhReason);
            assertNotNull(dtoReason, "toDtoReason should not return null for " + jhReason);
        }
    }

    @Test
    void roundtrip_dtoToJobHistoryToRestart_allValues() {
        for (IngestProgressUpdate.FailureReason dr : IngestProgressUpdate.FailureReason.values()) {
            IndexingJobHistory.FailureReason jhReason = bridge.toJobHistoryReason(dr);
            assertNotNull(jhReason, "toJobHistoryReason should not return null for " + dr);
            SubprocessRestartManager.FailureReason restartReason = bridge.toRestartReason(jhReason);
            assertNotNull(restartReason, "toRestartReason should not return null for " + jhReason);
        }
    }
}
