package ai.kompile.chat.local.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportProgress
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportStep
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportUiState
import ai.kompile.chat.local.android.viewmodel.huggingFaceFailureCopyText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HuggingFaceImportProgressPanelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun verificationShowsEveryPipelineBar() {
        val state = HuggingFaceImportUiState.Working(
            HuggingFaceImportProgress(
                step = HuggingFaceImportStep.VERIFY,
                message = "Verifying length and SHA-256 for model.gguf",
                attempt = 1,
                maxAttempts = 1,
                resumedBytes = 0L,
                completedBytes = 384L * 1024L * 1024L,
                totalBytes = 768L * 1024L * 1024L,
                smoothedBytesPerSecond = 128.0 * 1024.0 * 1024.0,
                etaSeconds = 3L,
                retryWillResumeOrReuse = true
            )
        )

        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    HuggingFaceImportProgressPanel(
                        state = state,
                        onCancelStep = { true },
                        onRetryStep = { true },
                        onOpenAppStorageSettings = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("hugging_face_import_pipeline").assertIsDisplayed()
        HuggingFaceImportStep.entries.forEach { step ->
            compose.onNodeWithTag("hugging_face_step_${step.name}").assertIsDisplayed()
        }
        compose.onNodeWithText("384.0 MiB / 768.0 MiB verified (50%)").assertIsDisplayed()
    }

    @Test
    fun connectFailureReplacesThePipelineWithTheExceptionPanel() {
        val thrown = java.net.SocketTimeoutException(
            "Hugging Face connection timed out after 30 seconds"
        )
        val failed = failedState(
            step = HuggingFaceImportStep.CONNECT,
            summary = "Connect failed",
            failure = thrown
        )

        compose.setContent {
            var state by remember {
                mutableStateOf<HuggingFaceImportUiState>(
                    HuggingFaceImportUiState.SelectionRequired(
                        repository = "namespace/model",
                        candidateCount = 12
                    )
                )
            }
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    HuggingFaceImportProgressPanel(
                        state = state,
                        onCancelStep = { true },
                        onRetryStep = { true },
                        onOpenAppStorageSettings = {}
                    )
                    if (state is HuggingFaceImportUiState.SelectionRequired) {
                        repeat(12) { candidate ->
                            Text(
                                text = "Resolved candidate $candidate",
                                modifier = Modifier.height(48.dp)
                            )
                        }
                        Button(
                            onClick = { state = failed },
                            modifier = Modifier.testTag("import_selected_candidate")
                        ) {
                            Text("Import selected GGUF/GGML with SDX")
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("import_selected_candidate")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("hugging_face_import_pipeline").assertDoesNotExist()
        compose.onNodeWithTag("hugging_face_step_CONNECT").assertDoesNotExist()
        compose.onNodeWithText("Step 3: Connect — Failed").assertDoesNotExist()
        compose.onNodeWithTag("hugging_face_failure_panel").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_failure_title").assertIsDisplayed()
        compose.onNodeWithText("Connect failed").assertIsDisplayed()
        compose.onNodeWithText("java.net.SocketTimeoutException").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_copy_error").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_toggle_error_details").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_retry_failure").assertIsDisplayed()
        compose.onNodeWithText("Retry Connect").assertIsDisplayed()
    }

    @Test
    fun failedStepShowsVisibleLogAndCopyControls() {
        val expectedDigest = "a".repeat(64)
        val actualDigest = "b".repeat(64)
        val summary = "Model verification failed: SHA-256 mismatch for 1073741824 bytes " +
            "(expected $expectedDigest, actual $actualDigest)"
        val currentDiagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 2,
            operation = "hugging face model",
            phase = "verify model bytes",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = "Discard the rejected bytes and retry this verification step.",
            technicalDetails = "org.nd4j.dsp.model.ResumableModelDownloader.PoisonedPartialException: $summary"
        )
        val tailMarker = "EXPANDED_STACK_TRACE_TAIL_MUST_BE_VISIBLE"
        val thrown = IllegalStateException(
            summary,
            IllegalArgumentException("Expected digest $expectedDigest but received $actualDigest")
        ).apply {
            stackTrace = Array(129) { index ->
                if (index == 128) {
                    StackTraceElement("org.nd4j.dsp.model.Verify", tailMarker, "Verify.java", 129)
                } else {
                    StackTraceElement("org.nd4j.dsp.model.Verify", "frame$index", "Verify.java", index + 1)
                }
            }
            addSuppressed(IllegalStateException("Rejected-byte cleanup also failed"))
        }
        val fullStack = thrown.stackTraceToString()
        val state = HuggingFaceImportUiState.Failed(
            HuggingFaceImportProgress(
                step = HuggingFaceImportStep.VERIFY,
                message = summary,
                attempt = 1,
                maxAttempts = 1,
                resumedBytes = 0L,
                completedBytes = 1_073_741_824L,
                totalBytes = 1_073_741_824L,
                smoothedBytesPerSecond = null,
                etaSeconds = null,
                retryWillResumeOrReuse = false
            ),
            currentDiagnostic,
            thrown
        )
        // Retained history deliberately contains only a stale failure. The current failure UI must
        // render and copy state.diagnostic directly instead of searching this list by phase.
        val diagnostics = listOf(
            ImportDiagnosticPolicy.create(
                timestampEpochMillis = 1,
                operation = "hugging face model",
                phase = "verify model bytes",
                severity = ImportDiagnosticSeverity.ERROR,
                summary = "stale verification failure",
                remediation = "Old remediation.",
                technicalDetails = "example.StaleVerificationException: old failure"
            )
        )

        lateinit var clipboard: ClipboardManager
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    HuggingFaceImportProgressPanel(
                        state = state,
                        onCancelStep = { true },
                        onRetryStep = { true },
                        onOpenAppStorageSettings = {},
                        diagnostics = diagnostics
                    )
                }
            }
        }

        compose.onNodeWithTag("hugging_face_failure_panel").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_failure_summary").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_failure_exception_type").assertIsDisplayed()
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_copy_error").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(huggingFaceFailureCopyText(state), clipboard.getText()?.text)
        compose.onNodeWithText("Show full stack trace").assertIsDisplayed()
        compose.onNodeWithTag("hugging_face_toggle_error_details")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("hugging_face_failure_technical_details").fetchSemanticsNode()
        compose.onNodeWithText(fullStack).fetchSemanticsNode()
        compose.onNodeWithText("Hide full stack trace").fetchSemanticsNode()
        compose.onNodeWithText(tailMarker, substring = true).fetchSemanticsNode()
    }

    @Test
    fun modelAndGraphErrorsShowTheirTechnicalLogAndCopyControl() {
        val message = "Native model activation failed"
        val diagnostics = listOf(
            ImportDiagnosticPolicy.create(
                timestampEpochMillis = 2,
                operation = "local model",
                phase = "activation",
                severity = ImportDiagnosticSeverity.ERROR,
                summary = message,
                remediation = "Verify the model and retry.",
                technicalDetails = "java.lang.UnsatisfiedLinkError: missing sdxLlmOpen"
            )
        )

        compose.setContent {
            MaterialTheme {
                CopyableStartupError(message = message, diagnostics = diagnostics)
            }
        }

        compose.onNodeWithText("Technical log").assertIsDisplayed()
        compose.onNodeWithText("java.lang.UnsatisfiedLinkError: missing sdxLlmOpen")
            .assertIsDisplayed()
        compose.onNodeWithText("Copy error details").assertIsDisplayed().performClick()
        compose.onNodeWithText(
            "Sanitized technical details are retained in Settings → App Diagnostics."
        ).assertIsDisplayed()
    }

    private fun failedState(
        step: HuggingFaceImportStep,
        summary: String,
        failure: Throwable
    ): HuggingFaceImportUiState.Failed {
        val diagnostic = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 1,
            operation = "hugging face model",
            phase = step.label.lowercase(),
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = "Copy the complete exception and retry this exact step.",
            technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
        )
        return HuggingFaceImportUiState.Failed(
            HuggingFaceImportProgress(
                step = step,
                message = summary,
                attempt = 1,
                maxAttempts = 1,
                resumedBytes = 0L,
                completedBytes = 768L * 1024L * 1024L,
                totalBytes = 768L * 1024L * 1024L,
                smoothedBytesPerSecond = null,
                etaSeconds = null,
                retryWillResumeOrReuse = true
            ),
            diagnostic,
            failure
        )
    }
}
