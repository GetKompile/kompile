package ai.kompile.chat.local.android.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeRuntimeLoadDiagnosticsTest {

    @Test
    fun pathFingerprintIsStableWithoutPersistingThePrivatePath() {
        val first = NativeOperationDiagnosticPolicy.modelPathFingerprint(
            "/data/user/0/example/files/models/model.sdz"
        )
        val repeated = NativeOperationDiagnosticPolicy.modelPathFingerprint(
            "/data/user/0/example/files/models/model.sdz"
        )
        val other = NativeOperationDiagnosticPolicy.modelPathFingerprint(
            "/data/user/0/example/files/models/other.sdz"
        )

        assertEquals(first, repeated)
        assertNotEquals(first, other)
        assertEquals(64, first.length)
        assertFalse(first.contains("model.sdz"))
    }

    @Test
    fun exitCorrelationRequiresTheSameProcessPidAndCurrentAttemptWindow() {
        val attempt = attempt(started = 10_000L)

        assertTrue(
            NativeOperationDiagnosticPolicy.exitMatchesAttempt(
                attempt,
                attempt.processName,
                attempt.processId,
                10_001L
            )
        )
        assertTrue(
            NativeOperationDiagnosticPolicy.exitMatchesAttempt(
                attempt,
                attempt.processName,
                attempt.processId,
                8_000L
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesAttempt(
                attempt,
                attempt.processName,
                attempt.processId,
                7_999L
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesAttempt(
                attempt,
                attempt.processName + ":other",
                attempt.processId,
                10_001L
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesAttempt(
                attempt,
                attempt.processName,
                attempt.processId + 1,
                10_001L
            )
        )
    }

    @Test
    fun ownerProcessExitCorrelationAcceptsTheHistoricalMainPid() {
        assertTrue(
            NativeOperationDiagnosticPolicy.exitMatchesProcess(
                expectedProcessName = "ai.kompile.chat.local.android.tensorg3.debug",
                expectedProcessId = 0,
                startedEpochMillis = 10_000L,
                processName = "ai.kompile.chat.local.android.tensorg3.debug",
                processId = 9876,
                exitTimestampEpochMillis = 10_500L
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesProcess(
                expectedProcessName = "ai.kompile.chat.local.android.tensorg3.debug",
                expectedProcessId = 0,
                startedEpochMillis = 10_000L,
                processName = "ai.kompile.chat.local.android.tensorg3.debug:sdx_model_runtime",
                processId = 9876,
                exitTimestampEpochMillis = 10_500L
            )
        )
    }

    @Test
    fun processLivenessRequiresTheExactPendingPidDirectory() {
        val procRoot = Files.createTempDirectory("native-operation-proc").toFile()
        try {
            assertFalse(NativeOperationCrashRecovery.isProcessAlive(4321, procRoot))
            assertTrue(File(procRoot, "4321").mkdir())
            assertTrue(NativeOperationCrashRecovery.isProcessAlive(4321, procRoot))
            assertFalse(NativeOperationCrashRecovery.isProcessAlive(4322, procRoot))
            assertFalse(NativeOperationCrashRecovery.isProcessAlive(0, procRoot))
        } finally {
            procRoot.deleteRecursively()
        }
    }

    @Test
    fun nativeExitBecomesAnExactCopyableCheckpointDiagnostic() {
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
            attempt(checkpoint = NativeOperationCheckpoint.LOAD_MODEL_BUNDLE),
            NativeOperationExitEvidence(
                timestampEpochMillis = 12_345L,
                processName = "ai.kompile.chat.local.android.tensorg3.debug",
                processId = 1234,
                reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
                status = 11,
                description = "signal 11 (SIGSEGV)",
                pssKilobytes = 888_000L,
                rssKilobytes = 999_000L,
                importance = 100,
                trace = "backtrace:\n  #00 pc 1234 /data/app/private/libnd4jnnapi.so",
                traceReadFailure = ""
            )
        )

        assertEquals(ImportDiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("Load the canonical sharded SDZ model", diagnostic.phase)
        assertTrue(diagnostic.summary.contains("native crash"))
        assertTrue(diagnostic.technicalDetails.contains("Exit status or signal: 11"))
        assertTrue(diagnostic.technicalDetails.contains("Exit PID: 1234"))
        assertTrue(diagnostic.technicalDetails.contains("Exit PSS KiB: 888000"))
        assertTrue(diagnostic.technicalDetails.contains("Exit RSS KiB: 999000"))
        assertTrue(diagnostic.technicalDetails.contains("System total bytes: 8000000000"))
        assertTrue(diagnostic.technicalDetails.contains("JavaCPP max tracked bytes: 4800000000"))
        assertTrue(diagnostic.technicalDetails.contains("JavaCPP max physical bytes: 7200000000"))
        assertTrue(diagnostic.technicalDetails.contains("backtrace:"))
        assertFalse(diagnostic.technicalDetails.contains("/data/app/private"))
        assertTrue(
            ImportDiagnosticPolicy.copyText(diagnostic)
                .contains("Load the canonical sharded SDZ model")
        )
    }

    @Test
    fun importerConversionDeathTargetsTheHuggingFaceFailureSurface() {
        val conversion = attempt(
            operation = NativeOperationKind.SDX_MODEL_PREPARATION,
            checkpoint = NativeOperationCheckpoint.CONVERT_OPTIMIZE_SDZ,
            processName = "ai.kompile.chat.local.android.tensorg3.debug:sdx_model_import",
            processId = 4321
        )
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(conversion, null)

        assertEquals(
            NativeOperationRecoveryTarget.HUGGING_FACE_IMPORT,
            conversion.operation.recoveryTarget
        )
        assertEquals("Convert and optimize SDZ", diagnostic.phase)
        assertTrue(diagnostic.summary.contains("retained no matching exit record"))
        assertTrue(diagnostic.remediation.contains("verified original model bytes"))
    }

    @Test
    fun completeManagedFailureIncludesCausesAndSuppressedFailures() {
        val root = IllegalStateException("conversion failed")
        root.addSuppressed(IOExceptionForTest("runtime destroy failed"))
        val failure = RuntimeException("wrapper", root)
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
            attempt(
                operation = NativeOperationKind.SDX_MODEL_PREPARATION,
                checkpoint = NativeOperationCheckpoint.CONVERT_OPTIMIZE_SDZ
            ),
            null,
            failure
        )

        assertTrue(diagnostic.technicalDetails.contains("RuntimeException: wrapper"))
        assertTrue(diagnostic.technicalDetails.contains("IllegalStateException: conversion failed"))
        assertTrue(diagnostic.technicalDetails.contains("Suppressed:"))
        assertTrue(diagnostic.technicalDetails.contains("runtime destroy failed"))
    }

    @Test
    fun tombstoneCaptureAndParserFailuresRemainInTheCopyableDiagnostic() {
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
            attempt(checkpoint = NativeOperationCheckpoint.GENERATE_TOKENS),
            NativeOperationExitEvidence(
                timestampEpochMillis = 12_345L,
                processName = "ai.kompile.chat.local.android.tensorg3.debug:sdx_model_runtime",
                processId = 1234,
                reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
                status = 6,
                description = "signal 6 (SIGABRT)",
                pssKilobytes = 888_000L,
                rssKilobytes = 999_000L,
                importance = 100,
                trace = "Raw native tombstone protobuf retained on device\n" +
                    "App-owned path: no_backup/native-crash-dumps/" +
                    "tombstone-12345-1234-0123456789abcdef.pb",
                traceReadFailure = "Parsing Android's native tombstone protobuf failed:\n" +
                    "AndroidNativeTombstoneParseException: truncated varint at protobuf byte offset 81"
            )
        )

        val copied = ImportDiagnosticPolicy.copyText(diagnostic)
        assertTrue(copied.contains("Exit trace capture or parse failure"))
        assertTrue(copied.contains("AndroidNativeTombstoneParseException"))
        assertTrue(copied.contains("protobuf byte offset 81"))
        assertTrue(
            copied.contains(
                "no_backup/native-crash-dumps/tombstone-12345-1234-0123456789abcdef.pb"
            )
        )
    }

    @Test
    fun largeTombstoneKeepsEvidencePastTheOldFourKilobyteCutoff() {
        val tail = "TAIL_FRAME_MUST_REMAIN"
        val trace = "frame\n".repeat(2_000) + tail
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
            attempt(checkpoint = NativeOperationCheckpoint.GENERATE_TOKENS),
            NativeOperationExitEvidence(
                timestampEpochMillis = 12_345L,
                processName = "ai.kompile.chat.local.android.tensorg3.debug",
                processId = 1234,
                reason = ApplicationExitInfo.REASON_LOW_MEMORY,
                status = 0,
                description = "low memory",
                pssKilobytes = 2_000_000L,
                rssKilobytes = 2_500_000L,
                importance = 100,
                trace = trace,
                traceReadFailure = ""
            )
        )

        assertTrue(diagnostic.summary.contains("low-memory termination"))
        assertTrue(diagnostic.technicalDetails.length > 4_096)
        assertTrue(diagnostic.technicalDetails.contains(tail))
        assertTrue(diagnostic.technicalDetails.length <= ImportDiagnosticPolicy.MAX_DETAIL_CHARS)
    }

    private fun attempt(
        operation: NativeOperationKind = NativeOperationKind.SDX_MODEL_LOAD,
        checkpoint: NativeOperationCheckpoint = NativeOperationCheckpoint.CREATE_NATIVE_RUNTIME,
        started: Long = 10_000L,
        processName: String = "ai.kompile.chat.local.android.tensorg3.debug",
        processId: Int = 1234
    ): NativeOperationAttempt = NativeOperationAttempt(
        attemptId = "attempt-123",
        startedEpochMillis = started,
        checkpointEpochMillis = started + 100L,
        operation = operation,
        checkpoint = checkpoint,
        processName = processName,
        processId = processId,
        provider = "google-tensor-g3-nnapi",
        targetProfile = "android-arm64-nnapi-accelerator",
        buildId = "test-build",
        modelPathFingerprint = "a".repeat(64),
        modelBytes = 1_700_000_000L,
        transportLibraryBytes = 397_600L,
        acceleratorLibraryBytes = 199_315_312L,
        importerLibraryBytes = 546_789_456L,
        cpuImporterBackendBytes = 2_367_118_176L,
        memory = NativeOperationMemorySnapshot(
            javaHeapUsedBytes = 100_000_000L,
            javaHeapMaxBytes = 512_000_000L,
            nativeHeapAllocatedBytes = 750_000_000L,
            systemTotalBytes = 8_000_000_000L,
            systemAvailableBytes = 2_000_000_000L,
            systemLowMemoryThresholdBytes = 256_000_000L,
            systemLowMemory = false,
            javaCppMaxTrackedBytes = 4_800_000_000L,
            javaCppMaxPhysicalBytes = 7_200_000_000L
        )
    )

    private class IOExceptionForTest(message: String) : Exception(message)
}
