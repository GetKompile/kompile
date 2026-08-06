package ai.kompile.chat.local.android.viewmodel

import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpoint
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpointStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceImportUiStateTest {

    @Test
    fun stagesStayInEndToEndExecutionOrder() {
        assertEquals(
            listOf(
                HuggingFaceImportStep.RESOLVE,
                HuggingFaceImportStep.PREFLIGHT,
                HuggingFaceImportStep.CONNECT,
                HuggingFaceImportStep.DOWNLOAD,
                HuggingFaceImportStep.VERIFY,
                HuggingFaceImportStep.TOKENIZER_ASSETS,
                HuggingFaceImportStep.CONVERT_SDZ,
                HuggingFaceImportStep.TARGET_CACHE,
                HuggingFaceImportStep.SDX_LOAD,
                HuggingFaceImportStep.SMOKE_DECODE,
                HuggingFaceImportStep.ACTIVATE,
                HuggingFaceImportStep.ACTIVE
            ),
            HuggingFaceImportStep.entries
        )
        assertSame(
            HuggingFaceImportStep.ACTIVE,
            HuggingFaceImportUiState.Active(
                "model.gguf",
                "SDX_GGUF_AOT",
                "/data/user/0/ai.kompile.chat/files/models/hugging-face/model.gguf"
            ).step
        )
    }

    @Test
    fun fractionIsDeterminateOnlyForKnownPositiveTotalsAndClamps() {
        assertNull(determinateFraction(1L, null))
        assertNull(determinateFraction(1L, 0L))
        assertNull(determinateFraction(1L, -1L))
        assertEquals(0f, determinateFraction(-10L, 100L)!!, 0f)
        assertEquals(0.25f, determinateFraction(25L, 100L)!!, 0f)
        assertEquals(1f, determinateFraction(150L, 100L)!!, 0f)
    }

    @Test
    fun percentRejectsUnknownAndNonFiniteValuesAndClamps() {
        assertNull(safePercent(null))
        assertNull(safePercent(Float.NaN))
        assertNull(safePercent(Float.POSITIVE_INFINITY))
        assertEquals(0, safePercent(-0.5f))
        assertEquals(13, safePercent(0.126f))
        assertEquals(100, safePercent(1.5f))
    }

    @Test
    fun binaryBytesAndRatesUseStableIecUnits() {
        assertEquals("0 B", formatBinaryBytes(-1L))
        assertEquals("1023 B", formatBinaryBytes(1023L))
        assertEquals("1.0 KiB", formatBinaryBytes(1024L))
        assertEquals("1.5 MiB", formatBinaryBytes(1_572_864L))
        assertEquals("—", formatBinaryRate(null))
        assertEquals("—", formatBinaryRate(Double.NaN))
        assertEquals("—", formatBinaryRate(-1.0))
        assertEquals("0 B/s", formatBinaryRate(0.0))
        assertEquals("512 B/s", formatBinaryRate(512.0))
        assertEquals("1.5 MiB/s", formatBinaryRate(1_572_864.0))
    }

    @Test
    fun etaFormattingHandlesUnknownSecondsMinutesAndHours() {
        assertEquals("—", formatEta(null))
        assertEquals("—", formatEta(-1L))
        assertEquals("0s", formatEta(0L))
        assertEquals("59s", formatEta(59L))
        assertEquals("1m 5s", formatEta(65L))
        assertEquals("2h 3m", formatEta(7_439L))
    }

    @Test
    fun workingRetryFailureAndCancellationExposeTheFullObservation() {
        val progress = HuggingFaceImportProgress(
            step = HuggingFaceImportStep.DOWNLOAD,
            message = "Downloading model",
            attempt = 2,
            maxAttempts = 4,
            resumedBytes = 1_048_576L,
            completedBytes = 2_097_152L,
            totalBytes = 4_194_304L,
            smoothedBytesPerSecond = 524_288.0,
            etaSeconds = 4L,
            retryWillResumeOrReuse = true
        )
        val states = listOf<HuggingFaceImportUiState.Observable>(
            HuggingFaceImportUiState.Working(progress),
            HuggingFaceImportUiState.Retrying(progress.copy(message = "Retrying download")),
            failed(progress.copy(message = "Download failed")),
            HuggingFaceImportUiState.Cancelled(progress.copy(message = "Download cancelled")),
            HuggingFaceImportUiState.Interrupted(progress.copy(message = "Import interrupted"))
        )

        states.forEach { state ->
            assertEquals(HuggingFaceImportStep.DOWNLOAD, state.step)
            assertTrue(state.message.isNotBlank())
            assertEquals(2, state.attempt)
            assertEquals(4, state.maxAttempts)
            assertEquals(1_048_576L, state.resumedBytes)
            assertEquals(2_097_152L, state.completedBytes)
            assertEquals(4_194_304L, state.totalBytes)
            assertEquals(524_288.0, state.smoothedBytesPerSecond!!, 0.0)
            assertEquals(4L, state.etaSeconds)
            assertTrue(state.retryWillResumeOrReuse)
            assertEquals(0.5f, state.progress.determinateFraction!!, 0f)
            assertEquals(50, state.progress.percent)
        }
    }

    @Test
    fun failedAndCancelledCanSayRetryMustRestartTheStep() {
        val nonReusable = progress(
            step = HuggingFaceImportStep.VERIFY,
            message = "Checksum mismatch",
            retryWillResumeOrReuse = false
        )

        val failed = failed(nonReusable)
        val cancelled = HuggingFaceImportUiState.Cancelled(
            nonReusable.copy(message = "Verification cancelled")
        )

        assertFalse(failed.retryWillResumeOrReuse)
        assertFalse(cancelled.retryWillResumeOrReuse)
        assertEquals(HuggingFaceImportStep.VERIFY, failed.step)
        assertEquals("Verification cancelled", cancelled.message)
    }

    @Test
    fun retryRoutesResolutionFreshImportAndPreparedStepRetrySeparately() {
        assertSame(
            HuggingFaceRetryAction.RESOLVE_REFERENCE,
            huggingFaceRetryAction(
                HuggingFaceImportStep.RESOLVE,
                hasSelectedCandidate = true,
                hasPreparedImport = true
            )
        )
        assertSame(
            HuggingFaceRetryAction.RESOLVE_REFERENCE,
            huggingFaceRetryAction(
                HuggingFaceImportStep.DOWNLOAD,
                hasSelectedCandidate = false,
                hasPreparedImport = true
            )
        )
        assertSame(
            HuggingFaceRetryAction.IMPORT_SELECTED,
            huggingFaceRetryAction(
                HuggingFaceImportStep.PREFLIGHT,
                hasSelectedCandidate = true,
                hasPreparedImport = false
            )
        )
        HuggingFaceImportStep.entries
            .filter {
                it.ordinal in HuggingFaceImportStep.CONNECT.ordinal..HuggingFaceImportStep.ACTIVATE.ordinal
            }
            .forEach { step ->
                assertSame(
                    HuggingFaceRetryAction.RETRY_PREPARED_IMPORT,
                    huggingFaceRetryAction(
                        step,
                        hasSelectedCandidate = true,
                        hasPreparedImport = true
                    )
                )
            }
    }

    @Test
    fun onlyNetworkTransferStagesExposeByteAndAttemptMetrics() {
        assertFalse(HuggingFaceImportStep.RESOLVE.isTransferStep)
        assertFalse(HuggingFaceImportStep.PREFLIGHT.isTransferStep)
        assertTrue(HuggingFaceImportStep.CONNECT.isTransferStep)
        assertTrue(HuggingFaceImportStep.DOWNLOAD.isTransferStep)
        assertTrue(HuggingFaceImportStep.VERIFY.isTransferStep)
        assertTrue(HuggingFaceImportStep.TOKENIZER_ASSETS.isTransferStep)
        assertFalse(HuggingFaceImportStep.SDX_LOAD.isTransferStep)
        assertFalse(HuggingFaceImportStep.SMOKE_DECODE.isTransferStep)
        assertFalse(HuggingFaceImportStep.ACTIVATE.isTransferStep)
    }

    @Test
    fun processDeathStateRetainsTheExactObservedBoundaryAndProgress() {
        val downloading = interruptedHuggingFaceImportState(
            checkpoint(
                stage = HuggingFaceImportCheckpointStage.SELECTED,
                observedStep = HuggingFaceImportStep.DOWNLOAD,
                message = "Downloading model.gguf",
                attempt = 3,
                maxAttempts = 4,
                completedBytes = 768L,
                totalBytes = 1_024L,
                retryWillResumeOrReuse = true
            )
        )
        val smokeDecode = interruptedHuggingFaceImportState(
            checkpoint(
                stage = HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD,
                observedStep = HuggingFaceImportStep.SMOKE_DECODE,
                message = "Running a bounded real-token decode",
                retryWillResumeOrReuse = true
            )
        )

        assertTrue(isHuggingFaceTerminal(downloading))
        assertTrue(isHuggingFaceTerminal(smokeDecode))
        assertEquals(HuggingFaceImportStep.DOWNLOAD, downloading.step)
        assertEquals(3, downloading.attempt)
        assertEquals(768L, downloading.completedBytes)
        assertEquals(1_024L, downloading.totalBytes)
        assertTrue(downloading.message.contains("Downloading model.gguf"))
        assertEquals(HuggingFaceImportStep.SMOKE_DECODE, smokeDecode.step)
        assertEquals("Resume interrupted import", huggingFaceRetryButtonLabel(downloading))
        assertTrue(huggingFaceRetryExplanation(downloading).contains("partial bytes"))
        assertTrue(huggingFaceRetryExplanation(smokeDecode).contains("verified app-owned model"))
    }

    @Test
    fun storagePreflightUsesResolvedSizeAndAppVolumeWithoutHiddenTwentyGibCap() {
        val gib = 1024L * 1024L * 1024L
        val expected = 32L * gib
        val plan = huggingFaceStoragePreflight(
            applicationId = "ai.kompile.chat.local.android.tensorg3.debug",
            destinationPath = "/data/user/0/ai.kompile.chat.local.android.tensorg3.debug/files/models/hugging-face/model.gguf",
            expectedBytes = expected,
            usableBytes = 100L * gib,
            reserveBytes = 128L * 1024L * 1024L,
            reusableBytes = 0L,
            reuse = HuggingFaceStorageReuse.NONE
        )

        assertTrue(plan.canProceed)
        assertNull(plan.blockReason)
        assertEquals(expected, plan.additionalBytesRequired)
        assertEquals(expected, plan.transferLimitBytes)
        assertTrue(huggingFaceStoragePreflightMessage(plan).contains("32.0 GiB"))
    }

    @Test
    fun storageShortageReportsExactAdditionalAvailableReserveAndDestination() {
        val gib = 1024L * 1024L * 1024L
        val reserve = 128L * 1024L * 1024L
        val destination = "/data/user/0/ai.kompile.chat/files/models/hugging-face/model.gguf"
        val plan = huggingFaceStoragePreflight(
            applicationId = "ai.kompile.chat",
            destinationPath = destination,
            expectedBytes = 8L * gib,
            usableBytes = 5L * gib + reserve,
            reserveBytes = reserve,
            reusableBytes = 2L * gib,
            reuse = HuggingFaceStorageReuse.VALIDATED_PARTIAL
        )

        assertFalse(plan.canProceed)
        assertSame(HuggingFaceStorageBlockReason.INSUFFICIENT_STORAGE, plan.blockReason)
        assertEquals(6L * gib, plan.additionalBytesRequired)
        assertEquals(5L * gib, plan.availableAfterReserveBytes)
        val message = huggingFaceStoragePreflightMessage(plan)
        assertTrue(message.contains("6.0 GiB"))
        assertTrue(message.contains("5.0 GiB"))
        assertTrue(message.contains("128.0 MiB"))
        assertTrue(message.contains(destination))
        assertFalse(message.contains((6L * gib).toString()))
    }

    @Test
    fun verifiedModelNeedsNoNewStorageAndPartialReducesNewBytes() {
        val gib = 1024L * 1024L * 1024L
        val partial = huggingFaceStoragePreflight(
            applicationId = "ai.kompile.chat",
            destinationPath = "/data/user/0/ai.kompile.chat/files/models/model.gguf",
            expectedBytes = 8L * gib,
            usableBytes = 7L * gib,
            reserveBytes = gib,
            reusableBytes = 2L * gib,
            reuse = HuggingFaceStorageReuse.VALIDATED_PARTIAL
        )
        val verified = huggingFaceStoragePreflight(
            applicationId = "ai.kompile.chat",
            destinationPath = "/data/user/0/ai.kompile.chat/files/models/model.gguf",
            expectedBytes = 32L * gib,
            usableBytes = 0L,
            reserveBytes = gib,
            reusableBytes = 32L * gib,
            reuse = HuggingFaceStorageReuse.VERIFIED_MODEL
        )

        assertTrue(partial.canProceed)
        assertEquals(6L * gib, partial.additionalBytesRequired)
        assertTrue(verified.canProceed)
        assertEquals(0L, verified.additionalBytesRequired)
        assertTrue(huggingFaceStoragePreflightMessage(verified).contains("no download is required"))
    }

    @Test
    fun verifiedModelStillPreflightsMissingTokenizerAssets() {
        val gib = 1024L * 1024L * 1024L
        val mib = 1024L * 1024L
        val plan = huggingFaceStoragePreflight(
            applicationId = "ai.kompile.chat",
            destinationPath = "/data/user/0/ai.kompile.chat/files/models/model.gguf",
            expectedBytes = 8L * gib + 2L * mib,
            usableBytes = mib,
            reserveBytes = mib,
            reusableBytes = 8L * gib,
            reuse = HuggingFaceStorageReuse.VERIFIED_MODEL
        )

        assertFalse(plan.canProceed)
        assertSame(HuggingFaceStorageBlockReason.INSUFFICIENT_STORAGE, plan.blockReason)
        assertEquals(2L * mib, plan.additionalBytesRequired)
        assertTrue(huggingFaceStoragePreflightMessage(plan).contains("model/tokenizer"))
    }

    @Test
    fun everyExecutionPhaseOwnsAProgressBarAndResumeContract() {
        val state = HuggingFaceImportUiState.Working(
            progress(
                step = HuggingFaceImportStep.SDX_LOAD,
                message = "Opening verified model",
                retryWillResumeOrReuse = true
            )
        )
        val phases = huggingFaceStepPresentations(state)

        assertEquals(HuggingFaceImportStep.entries.size, phases.size)
        assertEquals(HuggingFaceImportStep.entries, phases.map { it.step })
        assertTrue(phases.all { it.resumeBehavior.isNotBlank() })
        assertTrue(phases.take(HuggingFaceImportStep.SDX_LOAD.ordinal).all {
            it.status == HuggingFaceStepStatus.COMPLETE &&
                it.progressMode == HuggingFaceStepProgressMode.COMPLETE &&
                it.progressFraction == 1f
        })
        val current = phases.single { it.step == HuggingFaceImportStep.SDX_LOAD }
        assertSame(HuggingFaceStepStatus.RUNNING, current.status)
        assertSame(HuggingFaceStepProgressMode.INDETERMINATE, current.progressMode)
        assertTrue(current.resumeBehavior.contains("without redownloading"))
        assertFalse(current.canCancel)
        assertTrue(phases.drop(HuggingFaceImportStep.SDX_LOAD.ordinal + 1).all {
            it.status == HuggingFaceStepStatus.WAITING &&
                it.progressMode == HuggingFaceStepProgressMode.EMPTY &&
                it.progressFraction == 0f
        })
    }

    @Test
    fun failedPhaseOwnsTheOnlyStepLocalResumeActionAndRetainsItsProgress() {
        val failed = failed(
            HuggingFaceImportProgress(
                step = HuggingFaceImportStep.DOWNLOAD,
                message = "Connection lost",
                attempt = 3,
                maxAttempts = 4,
                resumedBytes = 10L,
                completedBytes = 25L,
                totalBytes = 100L,
                smoothedBytesPerSecond = null,
                etaSeconds = null,
                retryWillResumeOrReuse = true
            )
        )
        val phases = huggingFaceStepPresentations(failed)
        val current = phases.single { it.step == HuggingFaceImportStep.DOWNLOAD }

        assertSame(HuggingFaceStepStatus.FAILED, current.status)
        assertSame(HuggingFaceStepProgressMode.DETERMINATE, current.progressMode)
        assertEquals(0.25f, current.progressFraction!!, 0f)
        assertTrue(current.canRetry)
        assertTrue(current.actionLabel!!.contains("using saved bytes"))
        assertEquals(1, phases.count { it.actionLabel != null })
    }

    @Test
    fun activeStateShowsAllNineBarsCompleteAndExactStorage() {
        val path = "/data/user/0/ai.kompile.chat/files/models/hugging-face/model.gguf"
        val state = HuggingFaceImportUiState.Active(
            artifactName = "model.gguf",
            route = "SDX_GGUF_AOT",
            storageLocation = path,
            message = "ready for chat"
        )
        val phases = huggingFaceStepPresentations(state)

        assertEquals(HuggingFaceImportStep.entries.size, phases.size)
        assertTrue(phases.all {
            it.progressMode == HuggingFaceStepProgressMode.COMPLETE && it.progressFraction == 1f
        })
        assertSame(HuggingFaceStepStatus.ACTIVE, phases.last().status)
        assertTrue(phases.none { it.actionLabel != null })
    }

    @Test
    fun activePresentationAlwaysIncludesExactPrivateStoragePath() {
        val path = "/data/user/0/ai.kompile.chat/files/models/hugging-face/model.gguf"
        val presentation = huggingFaceActivePresentation(
            HuggingFaceImportUiState.Active(
                artifactName = "model.gguf",
                route = "SDX_GGUF_AOT",
                storageLocation = path,
                message = "ready for chat"
            )
        )

        assertTrue(presentation.summary.contains("SDX_GGUF_AOT"))
        assertEquals("Private app storage: $path", presentation.storage)
    }

    @Test
    fun compactDashboardKeepsAllNineBarsTogetherAndOnlyOneDetailFocus() {
        val state = HuggingFaceImportUiState.Working(
            HuggingFaceImportProgress(
                step = HuggingFaceImportStep.VERIFY,
                message = "Hashing saved bytes",
                attempt = 1,
                maxAttempts = 4,
                resumedBytes = 0L,
                completedBytes = 256L,
                totalBytes = 1024L,
                smoothedBytesPerSecond = 128.0,
                etaSeconds = 6L,
                retryWillResumeOrReuse = true
            )
        )

        val dashboard = huggingFacePipelineDashboard(state)

        assertEquals(HuggingFaceImportStep.entries, dashboard.rows.map { it.step })
        assertEquals(HuggingFaceImportStep.entries.size, dashboard.rows.size)
        assertEquals(HuggingFaceImportStep.VERIFY, dashboard.focused?.step)
        assertEquals(1, dashboard.rows.count { it.status == HuggingFaceStepStatus.RUNNING })
        assertTrue(dashboard.rows.all { it.resumeBehavior.isNotBlank() })
    }

    @Test
    fun importOperationKindSeparatesHuggingFaceFromPreparedArchives() {
        assertFalse(ImportOperationKind.NONE.isBusy)
        assertTrue(ImportOperationKind.HUGGING_FACE.isBusy)
        assertTrue(ImportOperationKind.MODEL_ARCHIVE.isBusy)
        assertTrue(ImportOperationKind.PROJECT_ARCHIVE.isBusy)
        assertTrue(ImportOperationKind.GRAPH.isBusy)
    }

    @Test
    fun transferFailuresRetainTheLastObservedStageInsteadOfFallingBackToConnect() {
        assertEquals(
            HuggingFaceImportStep.CONNECT,
            huggingFaceDownloadStep(HuggingFaceGgmlAcquisition.DownloadEvent.CONNECT)
        )
        listOf(
            HuggingFaceGgmlAcquisition.DownloadEvent.RESUME,
            HuggingFaceGgmlAcquisition.DownloadEvent.DOWNLOAD,
            HuggingFaceGgmlAcquisition.DownloadEvent.RETRY
        ).forEach { event ->
            assertEquals(HuggingFaceImportStep.DOWNLOAD, huggingFaceDownloadStep(event))
        }
        listOf(
            HuggingFaceGgmlAcquisition.DownloadEvent.VERIFY,
            HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE
        ).forEach { event ->
            assertEquals(HuggingFaceImportStep.VERIFY, huggingFaceDownloadStep(event))
        }

        val observedVerification = HuggingFaceImportUiState.Working(
            progress(
                step = HuggingFaceImportStep.VERIFY,
                message = "full file received; verifying",
                retryWillResumeOrReuse = true
            )
        )
        assertEquals(
            HuggingFaceImportStep.VERIFY,
            huggingFaceObservedOrFallbackStep(
                observedVerification,
                HuggingFaceImportStep.CONNECT
            )
        )
        assertEquals(
            HuggingFaceImportStep.CONNECT,
            huggingFaceObservedOrFallbackStep(null, HuggingFaceImportStep.CONNECT)
        )
    }

    @Test
    fun everyImportBoundaryFailureIsVisibleRetryableAndCopyable() {
        HuggingFaceImportStep.entries
            .filterNot { it == HuggingFaceImportStep.ACTIVE }
            .forEach { failedAt ->
                val observedStates = HuggingFaceImportStep.entries
                    .filter { it != HuggingFaceImportStep.ACTIVE && it.ordinal <= failedAt.ordinal }
                    .map { observedStep ->
                        HuggingFaceImportUiState.Working(
                            progress(
                                step = observedStep,
                                message = "simulating ${observedStep.name.lowercase()}",
                                retryWillResumeOrReuse =
                                    observedStep.ordinal >= HuggingFaceImportStep.DOWNLOAD.ordinal
                            )
                        )
                    }
                observedStates.forEach { observed ->
                    val observedDashboard = huggingFacePipelineDashboard(observed)
                    assertEquals(observed.step, observedDashboard.focused?.step)
                    assertEquals(
                        1,
                        observedDashboard.rows.count { it.status == HuggingFaceStepStatus.RUNNING }
                    )
                }

                // This is the production catch-path mapping that previously relabelled a completed
                // download/verification failure as CONNECT because its local fallback was stale.
                val lastObserved = observedStates.last()
                val failedStep = huggingFaceObservedOrFallbackStep(
                    lastObserved,
                    HuggingFaceImportStep.CONNECT
                )
                val failureMessage = "simulated ${failedAt.name.lowercase()} failure"
                val diagnostic = ImportDiagnosticPolicy.create(
                    timestampEpochMillis = failedAt.ordinal.toLong(),
                    operation = "hugging face model",
                    phase = failedAt.label.lowercase(),
                    severity = ImportDiagnosticSeverity.ERROR,
                    summary = failureMessage,
                    remediation = huggingFaceStepResumeBehavior(failedAt),
                    technicalDetails = "simulated.${failedAt.name}.Exception: boundary failed"
                )
                val boundaryFailure = IllegalStateException(failureMessage).apply {
                    stackTrace = arrayOf(
                        StackTraceElement(
                            "simulated.${failedAt.name}.Boundary",
                            "execute",
                            "Boundary.kt",
                            failedAt.ordinal + 1
                        )
                    )
                }
                val failure = HuggingFaceImportUiState.Failed(
                    lastObserved.progress.copy(
                        step = failedStep,
                        message = failureMessage
                    ),
                    diagnostic,
                    boundaryFailure
                )
                val dashboard = huggingFacePipelineDashboard(failure)
                val focused = dashboard.focused!!
                assertEquals(failedAt, failure.step)
                assertEquals(failedAt, focused.step)
                assertEquals(HuggingFaceStepStatus.FAILED, focused.status)
                assertTrue(focused.canRetry)
                assertTrue(focused.actionLabel!!.startsWith("Retry"))
                assertTrue(focused.resumeBehavior.isNotBlank())

                val hasSelectedCandidate = failedAt != HuggingFaceImportStep.RESOLVE
                val hasPreparedImport = failedAt.ordinal >= HuggingFaceImportStep.SDX_LOAD.ordinal
                val expectedRetry = when {
                    !hasSelectedCandidate -> HuggingFaceRetryAction.RESOLVE_REFERENCE
                    hasPreparedImport -> HuggingFaceRetryAction.RETRY_PREPARED_IMPORT
                    else -> HuggingFaceRetryAction.IMPORT_SELECTED
                }
                assertEquals(
                    expectedRetry,
                    huggingFaceRetryAction(failedAt, hasSelectedCandidate, hasPreparedImport)
                )

                if (hasSelectedCandidate) {
                    val checkpointStage = if (hasPreparedImport) {
                        HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD
                    } else {
                        HuggingFaceImportCheckpointStage.SELECTED
                    }
                    val interrupted = interruptedHuggingFaceImportState(
                        checkpoint(
                            stage = checkpointStage,
                            observedStep = failedAt,
                            message = failure.message,
                            attempt = 2,
                            maxAttempts = 4,
                            completedBytes = (failedAt.ordinal + 1L) * 128L,
                            totalBytes = 2_048L,
                            retryWillResumeOrReuse = failure.retryWillResumeOrReuse
                        )
                    )
                    assertEquals(failedAt, interrupted.step)
                    assertEquals(2, interrupted.attempt)
                    assertEquals((failedAt.ordinal + 1L) * 128L, interrupted.completedBytes)
                    assertTrue(interrupted.message.contains(failedAt.label))
                    assertTrue(huggingFacePipelineDashboard(interrupted).focused?.canRetry == true)
                }

                assertEquals(diagnostic, failure.diagnostic)
                assertSame(boundaryFailure, failure.failure)
                val copied = huggingFaceFailureCopyText(failure)
                assertTrue(copied.contains(failure.message))
                assertTrue(copied.contains("simulated.${failedAt.name}.Boundary"))
                assertTrue(copied.contains("Step: ${failedAt.label}"))
                assertTrue(copied.contains("Bytes processed:"))
            }
    }

    @Test
    fun currentFailureCopyRetainsTheEntireUnboundedStackTrace() {
        val tailMarker = "STACK_TRACE_TAIL_MUST_SURVIVE"
        val frameCount = 4_000
        val exactFailure = IllegalStateException("exact failure").apply {
            stackTrace = Array(frameCount) { index ->
                if (index == frameCount - 1) {
                    StackTraceElement("ai.kompile.Example", tailMarker, "Example.kt", frameCount)
                } else {
                    StackTraceElement("ai.kompile.Example", "frame$index", "Example.kt", index + 1)
                }
            }
        }
        val fullStack = exactFailure.stackTraceToString()
        val diagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 1L,
            operation = "hugging face model",
            phase = "verify",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = "Exact verification failure",
            remediation = "Retry explicitly.",
            technicalDetails = "bounded retained history"
        )
        val state = HuggingFaceImportUiState.Failed(
            progress(HuggingFaceImportStep.VERIFY, "Exact verification failure", false),
            diagnostic,
            exactFailure
        )

        assertTrue(fullStack.length > ImportDiagnosticPolicy.MAX_DETAIL_CHARS)
        assertTrue(huggingFaceFailureCopyText(state).contains(tailMarker))
        assertTrue(huggingFaceFailureCopyText(state).contains(fullStack))
    }

    private fun failed(progress: HuggingFaceImportProgress): HuggingFaceImportUiState.Failed {
        val failure = IllegalStateException(progress.message)
        val diagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 1L,
            operation = "hugging face model",
            phase = progress.step.label.lowercase(),
            severity = ImportDiagnosticSeverity.ERROR,
            summary = progress.message,
            remediation = huggingFaceStepResumeBehavior(progress.step),
            technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
        )
        return HuggingFaceImportUiState.Failed(progress, diagnostic, failure)
    }

    private fun checkpoint(
        stage: HuggingFaceImportCheckpointStage,
        observedStep: HuggingFaceImportStep,
        message: String,
        attempt: Int = 1,
        maxAttempts: Int = 1,
        completedBytes: Long = 0L,
        totalBytes: Long? = null,
        retryWillResumeOrReuse: Boolean
    ) = HuggingFaceImportCheckpoint(
        rawReference = "acme/tiny-chat",
        repository = "acme/tiny-chat",
        resolvedRevision = "a".repeat(40),
        candidatePath = "model.gguf",
        expectedBytes = 1_024L,
        expectedSha256 = "b".repeat(64),
        stage = stage,
        observedStep = observedStep.name,
        observedMessage = message,
        observedAttempt = attempt,
        observedMaxAttempts = maxAttempts,
        observedCompletedBytes = completedBytes,
        observedTotalBytes = totalBytes ?: HuggingFaceImportCheckpoint.UNKNOWN_SIZE,
        observedRetryWillResumeOrReuse = retryWillResumeOrReuse
    )

    private fun progress(
        step: HuggingFaceImportStep,
        message: String,
        retryWillResumeOrReuse: Boolean
    ) = HuggingFaceImportProgress(
        step = step,
        message = message,
        attempt = 1,
        maxAttempts = 3,
        resumedBytes = 0L,
        completedBytes = 0L,
        totalBytes = null,
        smoothedBytesPerSecond = null,
        etaSeconds = null,
        retryWillResumeOrReuse = retryWillResumeOrReuse
    )
}
