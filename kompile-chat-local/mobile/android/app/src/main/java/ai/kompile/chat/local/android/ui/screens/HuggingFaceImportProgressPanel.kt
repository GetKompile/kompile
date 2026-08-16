package ai.kompile.chat.local.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.ImportDiagnostic
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportStep
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportUiState
import ai.kompile.chat.local.android.viewmodel.HuggingFaceStepPresentation
import ai.kompile.chat.local.android.viewmodel.HuggingFaceStepProgressMode
import ai.kompile.chat.local.android.viewmodel.HuggingFaceStepStatus
import ai.kompile.chat.local.android.viewmodel.formatBinaryBytes
import ai.kompile.chat.local.android.viewmodel.formatBinaryRate
import ai.kompile.chat.local.android.viewmodel.formatEta
import ai.kompile.chat.local.android.viewmodel.huggingFaceActivePresentation
import ai.kompile.chat.local.android.viewmodel.huggingFaceFailureCopyText
import ai.kompile.chat.local.android.viewmodel.huggingFacePipelineDashboard
import ai.kompile.chat.local.android.viewmodel.huggingFaceStoragePreflightMessage
import ai.kompile.chat.local.android.viewmodel.isTransferStep

/**
 * One compact, authoritative rendering of the complete Hugging Face -> SDX -> chat pipeline.
 * All pipeline progress bars stay together; verbose checkpoint and transfer detail is rendered once
 * for the current/tapped row so it cannot push the other stages out of view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HuggingFaceImportProgressPanel(
    state: HuggingFaceImportUiState,
    onCancelStep: (HuggingFaceImportStep) -> Boolean,
    onRetryStep: (HuggingFaceImportStep) -> Boolean,
    onOpenAppStorageSettings: () -> Unit,
    onCopySmokeDecodeTrace: () -> Unit,
    diagnostics: List<ImportDiagnostic> = emptyList(),
    pipelineTitle: String = "Model import pipeline",
    pipelineDescription: String = "All stages are shown below. Tap a stage for its checkpoint and resume contract.",
    diagnosticOperationPrefix: String = "hugging face",
    visibleSteps: Set<HuggingFaceImportStep> = HuggingFaceImportStep.entries.toSet(),
    testTagPrefix: String = "hugging_face_import",
    failureCopyTitle: String = "Hugging Face model import failure",
    modifier: Modifier = Modifier
) {
    if (state is HuggingFaceImportUiState.Idle) return

    val clipboard = LocalClipboardManager.current
    if (state is HuggingFaceImportUiState.Failed) {
        val failureBringIntoView = remember { BringIntoViewRequester() }
        LaunchedEffect(state.failure) {
            failureBringIntoView.bringIntoView()
        }
        HuggingFaceFailurePanel(
            state = state,
            onCopy = {
                clipboard.setText(
                    AnnotatedString(
                        huggingFaceFailureCopyText(state).replaceFirst(
                            "Hugging Face model import failure",
                            failureCopyTitle,
                        )
                    )
                )
            },
            onCopySmokeDecodeTrace = onCopySmokeDecodeTrace,
            onRetry = { onRetryStep(state.step) },
            onOpenAppStorageSettings = onOpenAppStorageSettings,
            modifier = modifier.bringIntoViewRequester(failureBringIntoView)
        )
        return
    }

    val currentStep = when (state) {
        is HuggingFaceImportUiState.Observable -> state.step
        is HuggingFaceImportUiState.SelectionRequired -> HuggingFaceImportStep.RESOLVE
        is HuggingFaceImportUiState.Active -> HuggingFaceImportStep.ACTIVE
        HuggingFaceImportUiState.Idle -> null
    }
    var inspectedStep by remember { mutableStateOf(currentStep) }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(currentStep, state is HuggingFaceImportUiState.Active) {
        inspectedStep = currentStep
        if (state is HuggingFaceImportUiState.Observable || state is HuggingFaceImportUiState.Active) {
            bringIntoView.bringIntoView()
        }
    }
    val dashboard = huggingFacePipelineDashboard(state, inspectedStep).let { full ->
        full.copy(
            rows = full.rows.filter { it.step in visibleSteps },
            focused = full.focused?.takeIf { it.step in visibleSteps },
        )
    }
    val observable = state as? HuggingFaceImportUiState.Observable
    val importLog = diagnostics
        .filter { it.operation.startsWith(diagnosticOperationPrefix, ignoreCase = true) }
        .take(16)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_pipeline"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                pipelineTitle,
                modifier = Modifier
                    .bringIntoViewRequester(bringIntoView)
                    .testTag("${testTagPrefix}_pipeline_header"),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                pipelineDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            dashboard.rows.forEach { presentation ->
                CompactHuggingFaceStepRow(
                    presentation = presentation,
                    selected = presentation.step == dashboard.focused?.step,
                    onClick = { inspectedStep = presentation.step }
                )
            }

            dashboard.focused?.let { focused ->
                HorizontalDivider(modifier = Modifier.padding(top = 3.dp))
                Text(
                    "Step ${dashboard.rows.indexOfFirst { it.step == focused.step } + 1}: ${focused.step.label} — ${focused.status.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor(focused)
                )
                Text(focused.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    focused.resumeBehavior,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                observable
                    ?.takeIf { it.step == focused.step && focused.step.isTransferStep }
                    ?.let { progress ->
                        val unit = if (focused.step == HuggingFaceImportStep.VERIFY) "verified" else "received"
                        val total = progress.totalBytes
                        Text(
                            if (total != null && total > 0L) {
                                "${formatBinaryBytes(progress.completedBytes)} / ${formatBinaryBytes(total)} $unit" +
                                    (progress.progress.percent?.let { " ($it%)" } ?: "")
                            } else {
                                "${formatBinaryBytes(progress.completedBytes)} $unit · total size not reported"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Attempt ${progress.attempt}/${progress.maxAttempts} · " +
                                "${formatBinaryRate(progress.smoothedBytesPerSecond)} · ETA ${formatEta(progress.etaSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (progress.resumedBytes > 0L) {
                            Text(
                                "Reused ${formatBinaryBytes(progress.resumedBytes)} of validator-backed saved bytes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Transfer policy: ${HuggingFaceGgmlAcquisition.TRANSFER_POLICY_SUMMARY}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                focused.actionLabel?.let { label ->
                    OutlinedButton(
                        onClick = {
                            if (focused.canRetry) onRetryStep(focused.step)
                            else if (focused.canCancel) onCancelStep(focused.step)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
            }

            when (state) {
                is HuggingFaceImportUiState.SelectionRequired -> Text(
                    "Resolved ${state.candidateCount} files in ${state.repository}; choose the exact GGUF/GGML above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                is HuggingFaceImportUiState.Active -> {
                    val active = huggingFaceActivePresentation(state)
                    Text(active.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(active.storage, style = MaterialTheme.typography.bodySmall)
                }
                else -> Unit
            }

            val preflight = when (state) {
                is HuggingFaceImportUiState.Observable -> state.storagePreflight
                is HuggingFaceImportUiState.Active -> state.storagePreflight
                else -> null
            }
            preflight?.let {
                HorizontalDivider()
                Text(
                    "Storage: ${if (it.canProceed) "ready" else "blocked"} · ${it.destinationPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.canProceed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(huggingFaceStoragePreflightMessage(it), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onOpenAppStorageSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open this APK's storage settings")
                }
            }

            HorizontalDivider()
            Text(
                "$pipelineTitle log",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "Full import, activation, and execution history is available in Settings → App Diagnostics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (importLog.isEmpty()) {
                Text(
                    "No retained import events yet. Stage transitions and failures appear here as they occur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                importLog.forEach { entry ->
                    val color = when (entry.severity) {
                        ImportDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                        ImportDiagnosticSeverity.SUCCESS -> MaterialTheme.colorScheme.primary
                        ImportDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        "${entry.phase} · ${entry.severity.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                    Text(entry.summary, style = MaterialTheme.typography.bodySmall, color = color)
                    if (entry.technicalDetails.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                entry.technicalDetails,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(ImportDiagnosticPolicy.copyText(importLog.reversed()))
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hugging_face_copy_import_log")
                ) {
                    Text("Copy import log")
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceFailurePanel(
    state: HuggingFaceImportUiState.Failed,
    onCopy: () -> Unit,
    onCopySmokeDecodeTrace: () -> Unit,
    onRetry: () -> Unit,
    onOpenAppStorageSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(state.failure) { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hugging_face_failure_panel"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                "Import failed at ${state.step.label}",
                modifier = Modifier.testTag("hugging_face_failure_title"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            SelectionContainer {
                Text(
                    state.exceptionType,
                    modifier = Modifier.testTag("hugging_face_failure_exception_type"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            SelectionContainer {
                Text(
                    state.message,
                    modifier = Modifier.testTag("hugging_face_failure_summary"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                buildString {
                    append("Processed ").append(state.completedBytes)
                    state.totalBytes?.let { append(" of ").append(it) }
                    append(" bytes · attempt ").append(state.attempt).append('/').append(state.maxAttempts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hugging_face_copy_error")
            ) {
                Text("Copy full error")
            }
            OutlinedButton(
                onClick = onCopySmokeDecodeTrace,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hugging_face_copy_smoke_decode_trace")
            ) {
                Text("Copy smoke-decode trace")
            }
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hugging_face_toggle_error_details")
            ) {
                Text(if (expanded) "Hide full stack trace" else "Show full stack trace")
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hugging_face_retry_failure")
            ) {
                Text("Retry ${state.step.label}")
            }
            state.storagePreflight?.let {
                OutlinedButton(
                    onClick = onOpenAppStorageSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hugging_face_failure_open_storage")
                ) {
                    Text("Open this APK's storage settings")
                }
            }
            if (expanded) {
                HorizontalDivider()
                state.storagePreflight?.destinationPath?.takeIf(String::isNotBlank)?.let { destination ->
                    SelectionContainer {
                        Text(
                            "Destination: $destination",
                            modifier = Modifier.testTag("hugging_face_failure_destination"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Text(
                    "Next: ${state.diagnostic.remediation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Complete exception — causes and suppressed failures included",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                SelectionContainer {
                    Text(
                        state.stackTrace,
                        modifier = Modifier.testTag("hugging_face_failure_technical_details"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactHuggingFaceStepRow(
    presentation: HuggingFaceStepPresentation,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hugging_face_step_${presentation.step.name}")
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp, horizontal = if (selected) 4.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${presentation.step.ordinal + 1}. ${presentation.step.label}",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(presentation),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                presentation.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(presentation)
            )
        }
        when (presentation.progressMode) {
            HuggingFaceStepProgressMode.INDETERMINATE -> LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
            else -> LinearProgressIndicator(
                progress = { presentation.progressFraction ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
    }
}

@Composable
private fun statusColor(presentation: HuggingFaceStepPresentation) = when {
    presentation.canRetry -> MaterialTheme.colorScheme.error
    presentation.status == HuggingFaceStepStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    presentation.status == HuggingFaceStepStatus.COMPLETE -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.primary
}
