package ai.kompile.chat.local.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.kompile.chat.local.android.R
import ai.kompile.chat.local.android.diagnostics.ImportDiagnostic
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.SmokeDecodeTraceLog
import ai.kompile.chat.local.android.viewmodel.ChatViewModel
import ai.kompile.chat.local.android.viewmodel.EngineNotice
import ai.kompile.chat.local.android.viewmodel.GraphUiState
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportUiState
import ai.kompile.chat.local.android.viewmodel.ImportOperationKind
import ai.kompile.chat.local.android.viewmodel.ModelLoadProgressUi
import ai.kompile.chat.local.android.viewmodel.ModelUiState
import ai.kompile.chat.local.android.viewmodel.ProjectImportOutcome
import ai.kompile.chat.local.android.viewmodel.ToolRoundUi
import ai.kompile.chat.local.android.viewmodel.UiMessage
import ai.kompile.chat.local.android.viewmodel.engineNotice
import ai.kompile.chat.local.android.viewmodel.modelStatusUi
import ai.kompile.chat.local.android.viewmodel.routeBadgeUi
import ai.kompile.chat.local.android.viewmodel.routeCanCancelGeneration
import kotlinx.coroutines.launch

internal fun shouldShowHuggingFaceImportOnChat(
    state: HuggingFaceImportUiState,
    modelState: ModelUiState
): Boolean = modelState !is ModelUiState.Ready && state is HuggingFaceImportUiState.Observable

internal fun copyableChatTranscript(
    messages: List<UiMessage>,
    route: String,
    error: String?,
    errorStackTrace: String?
): String = buildString {
    appendLine("Kompile Chat raw transcript")
    appendLine("Route: $route")
    messages.forEachIndexed { messageIndex, message ->
        appendLine()
        appendLine("message[$messageIndex].role=${message.role}")
        appendLine("message[$messageIndex].content:")
        appendLine(message.content)
        message.toolRounds.forEachIndexed { roundIndex, round ->
            appendLine("message[$messageIndex].tool[$roundIndex].name=${round.tool}")
            appendLine("message[$messageIndex].tool[$roundIndex].arguments:")
            appendLine(round.argsJson)
            appendLine("message[$messageIndex].tool[$roundIndex].result:")
            appendLine(round.resultJson)
        }
        message.protocolExchanges.forEachIndexed { exchangeIndex, exchange ->
            appendLine("message[$messageIndex].protocol[$exchangeIndex].request_json:")
            appendLine(exchange.requestJson)
            appendLine("message[$messageIndex].protocol[$exchangeIndex].raw_response:")
            appendLine(exchange.rawResponse)
            if (exchange.protocolErrors.isNotEmpty()) {
                appendLine("message[$messageIndex].protocol[$exchangeIndex].errors:")
                exchange.protocolErrors.forEach { appendLine(it) }
            }
        }
    }
    if (!error.isNullOrBlank()) {
        appendLine()
        appendLine("current_error:")
        appendLine(error)
    }
    if (!errorStackTrace.isNullOrBlank()) {
        appendLine("current_error_stack:")
        appendLine(errorStackTrace)
    }
}.trimEnd()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    vm: ChatViewModel
) {
    val messages by vm.messages.collectAsState()
    val thinking  by vm.thinking.collectAsState()
    val error      by vm.error.collectAsState()
    val errorStackTrace by vm.errorStackTrace.collectAsState()
    val route      by vm.activeRoute.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val modelLoadProgress by vm.modelLoadProgress.collectAsState()
    val graphState by vm.graphState.collectAsState()
    val diagnostics by vm.importDiagnostics.collectAsState()
    val clipboard = LocalClipboardManager.current
    val traceContext = LocalContext.current
    val copySmokeDecodeTrace = {
        clipboard.setText(AnnotatedString(SmokeDecodeTraceLog(traceContext).readContents()))
    }
    val copyTranscript = {
        clipboard.setText(
            AnnotatedString(copyableChatTranscript(messages, route, error, errorStackTrace))
        )
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val importOperation by vm.importOperation.collectAsState()
    val huggingFaceImportState by vm.huggingFaceImportState.collectAsState()
    val importing = importOperation != ImportOperationKind.NONE
    var importError by remember { mutableStateOf<String?>(null) }
    var importErrorStackTrace by remember { mutableStateOf<String?>(null) }

    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importError = null
                importErrorStackTrace = null
                when (val outcome = vm.importProjectAndActivate(it)) {
                    is ProjectImportOutcome.Active -> Unit
                    is ProjectImportOutcome.Failed -> {
                        importError = outcome.displayMessage
                        importErrorStackTrace = outcome.stackTrace
                    }
                }
            }
        }
    }

    // A staged .sdz is the primary first-run artifact (Settings mirrors this); the
    // chat screen must accept it directly or the browser-staging flow dead-ends here.
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importError = null
                importErrorStackTrace = null
                val result = vm.importModelAndActivate(it)
                if (result.isFailure) {
                    val failure = result.exceptionOrNull()
                    importError = failure?.message ?: "Complete model import failed."
                    importErrorStackTrace = failure?.stackTraceToString()
                }
            }
        }
    }

    val engineReady = modelState is ModelUiState.Ready && graphState is GraphUiState.Ready

    // Auto-scroll to newest message.
    LaunchedEffect(messages.size, thinking) {
        val target = messages.size + (if (thinking) 1 else 0)
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    // Brand lockup mirrors the kompile-app-main header: mark + wordmark.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.kompile_logo),
                            contentDescription = stringResource(R.string.brand_logo_description),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Kompile Chat",
                                style = MaterialTheme.typography.titleMedium
                            )
                            // Route badge names the exact device provider.
                            RouteBadge(route = route)
                        }
                    }
                },
                navigationIcon = {},
                actions = {
                    IconButton(
                        onClick = copyTranscript,
                        enabled = messages.isNotEmpty() || !error.isNullOrBlank()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy transcript")
                    }
                    // Small local context windows make resetting the conversation a
                    // first-class action, not a hidden side effect of Settings changes.
                    IconButton(
                        onClick = { vm.clearHistory() },
                        enabled = messages.isNotEmpty() && !thinking && !importing
                    ) {
                        Icon(Icons.Default.AddComment, contentDescription = "New chat")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            ModelStatusHeader(
                modelState = modelState,
                importOperation = importOperation,
                loadProgress = modelLoadProgress,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )

            // Thinking indicator.
            if (thinking) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (shouldShowHuggingFaceImportOnChat(huggingFaceImportState, modelState)) {
                HuggingFaceImportProgressPanel(
                    state = huggingFaceImportState,
                    onCancelStep = vm::cancelHuggingFaceStep,
                    onRetryStep = vm::retryHuggingFaceStep,
                    onOpenAppStorageSettings = { vm.openAppStorageSettings() },
                    onCopySmokeDecodeTrace = copySmokeDecodeTrace,
                    diagnostics = diagnostics,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            error?.let { message ->
                val diagnostic = ImportDiagnosticPolicy.errorForMessage(
                    diagnostics,
                    message,
                    operationPrefix = "local chat"
                )
                val exactStackTrace = errorStackTrace?.takeIf(String::isNotBlank)
                CopyableRuntimeError(
                    message = message,
                    technicalDetails = exactStackTrace
                        ?: diagnostic?.technicalDetails.orEmpty(),
                    clipboardText = exactStackTrace?.let {
                        "$message\n\nFull stack trace:\n$it"
                    } ?: ImportDiagnosticPolicy.copyTextForError(
                        message,
                        diagnostics,
                        operationPrefix = "local chat"
                    ),
                    exactStackTrace = exactStackTrace != null,
                    onDismiss = vm::clearError,
                    onCopySmokeDecodeTrace = copySmokeDecodeTrace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            // Message list.
            if (messages.isEmpty() && !thinking) {
                StartupStatePanel(
                    modelState = modelState,
                    graphState = graphState,
                    importError = importError,
                    importErrorStackTrace = importErrorStackTrace,
                    diagnostics = diagnostics,
                    onImportModel = { modelPicker.launch("*/*") },
                    onImportProject = { projectPicker.launch("*/*") },
                    onPrepareProject = onOpenSettings,
                    onOpenSettings = onOpenSettings,
                    onCopySmokeDecodeTrace = copySmokeDecodeTrace,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageRow(msg = msg)
                    }
                    if (thinking) {
                        item(key = "thinking") {
                            ThinkingBubble()
                        }
                    }
                }
            }

            // With history on screen the startup panel is gone, so a disabled input must
            // still explain itself: surface the exact model/graph state above the bar.
            if (messages.isNotEmpty() && !thinking) {
                engineNotice(modelState, graphState)?.let { notice ->
                    EngineStatusBanner(notice = notice, onOpenSettings = onOpenSettings)
                }
            }

            // Input bar. Imports swap the engine and reset the conversation mid-flight,
            // so sending stays disabled until the activation transaction settles.
            ChatInputBar(
                enabled = !thinking && engineReady && !importing,
                generating = thinking,
                canCancelGeneration = routeCanCancelGeneration(route),
                onSend = { text -> vm.sendMessage(text) },
                onCancel = { vm.cancelGeneration() }
            )
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
internal fun ModelStatusHeader(
    modelState: ModelUiState,
    importOperation: ImportOperationKind,
    loadProgress: ModelLoadProgressUi? = null,
    modifier: Modifier = Modifier,
) {
    val status = modelStatusUi(modelState, importOperation, loadProgress)
    val containerColor = when {
        status.error -> MaterialTheme.colorScheme.errorContainer
        status.loading -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("top_model_status"),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (status.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("model_loading_indicator"),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (status.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("model_compiler_progress"),
                )
            }
        }
    }
}

@Composable
private fun RouteBadge(route: String) {
    val badge = routeBadgeUi(route)
    val color = if (badge.active) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun CopyableRuntimeError(
    message: String,
    technicalDetails: String,
    clipboardText: String,
    exactStackTrace: Boolean,
    onDismiss: () -> Unit,
    onCopySmokeDecodeTrace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(technicalDetails) { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Execution error",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (technicalDetails.isNotBlank()) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (expanded) "Hide full stack trace" else "Show full stack trace")
                }
                if (expanded) {
                    SelectionContainer {
                        Text(
                            technicalDetails,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onCopySmokeDecodeTrace,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy smoke-decode trace")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(clipboardText)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy full error")
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Dismiss")
                }
            }
            Text(
                if (exactStackTrace) {
                    "The complete untruncated stack trace is available above and through Copy full error."
                } else {
                    "Sanitized retained diagnostics are available above and through Copy full error."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
internal fun CopyableStartupError(
    message: String,
    diagnostics: List<ImportDiagnostic>,
    exactStackTrace: String? = null,
    onCopySmokeDecodeTrace: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val diagnostic = ImportDiagnosticPolicy.errorForMessage(diagnostics, message)
    val technicalDetails = exactStackTrace?.takeIf(String::isNotBlank)
        ?: diagnostic?.technicalDetails?.takeIf(String::isNotBlank)
    var expanded by remember(technicalDetails, exactStackTrace) {
        mutableStateOf(exactStackTrace == null && technicalDetails != null)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (technicalDetails != null) {
            Text(
                "Technical log",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (expanded) "Hide full stack trace" else "Show full stack trace")
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        technicalDetails,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                "No additional stack trace was captured for this error.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick = {
                clipboard.setText(
                    AnnotatedString(
                        exactStackTrace?.let {
                            "$message\n\nFull stack trace:\n$it"
                        } ?: ImportDiagnosticPolicy.copyTextForError(message, diagnostics)
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy error details")
        }
        onCopySmokeDecodeTrace?.let { copyTrace ->
            OutlinedButton(
                onClick = copyTrace,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy smoke-decode trace")
            }
        }
        Text(
            if (exactStackTrace != null) {
                "The complete untruncated stack trace is available above and through Copy error details."
            } else {
                "Sanitized technical details are retained in Settings → App Diagnostics."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun StartupStatePanel(
    modelState: ModelUiState,
    graphState: GraphUiState,
    importError: String?,
    importErrorStackTrace: String?,
    diagnostics: List<ImportDiagnostic>,
    onImportModel: () -> Unit,
    onImportProject: () -> Unit,
    onPrepareProject: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopySmokeDecodeTrace: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            // First-run surface leads with the Kompile brand mark; the text below
            // carries the state, so the mark stays decorative.
            Icon(
                painter = painterResource(id = R.drawable.kompile_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .padding(bottom = 12.dp)
            )

            when {
                modelState is ModelUiState.Missing -> {
                    Text("Import a local model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        importError ?: "Choose a target-prepared chat model (.sdz) for chat only, or a "
                            + "full project (.kproject) that also activates the knowledge graph "
                            + "and Markdown sources. Settings keeps raw Hugging Face GGML/GGUF "
                            + "acquisition separate from prepared Kompile artifacts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (importError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (importError != null) {
                        CopyableStartupError(
                            importError,
                            diagnostics,
                            exactStackTrace = importErrorStackTrace,
                            onCopySmokeDecodeTrace = onCopySmokeDecodeTrace
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImportModel) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import chat model (.sdz)")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onImportProject) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import full project (.kproject)")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPrepareProject) {
                        Text("Get or prepare a model")
                    }
                    Text(
                        "Hugging Face opens directly for GGML/GGUF. A Kompile server is only an optional source of prepared .sdz/.kproject downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                modelState is ModelUiState.Failed -> {
                    Text("Model could not be opened", style = MaterialTheme.typography.titleMedium)
                    Text(
                        modelState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    CopyableStartupError(
                        modelState.message,
                        diagnostics,
                        exactStackTrace = modelState.stackTrace,
                        onCopySmokeDecodeTrace = onCopySmokeDecodeTrace
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImportModel) { Text("Choose another model (.sdz)") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onImportProject) { Text("Import full project (.kproject)") }
                }

                modelState is ModelUiState.Checking -> {
                    Text("Checking local model…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }

                graphState is GraphUiState.Failed -> {
                    Text("Knowledge graph could not be opened", style = MaterialTheme.typography.titleMedium)
                    Text(
                        graphState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    CopyableStartupError(
                        graphState.message,
                        diagnostics,
                        exactStackTrace = graphState.stackTrace,
                        onCopySmokeDecodeTrace = onCopySmokeDecodeTrace
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }

                graphState is GraphUiState.Checking || graphState is GraphUiState.WaitingForModel -> {
                    Text("Opening offline knowledge graph…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }

                else -> {
                    Text(
                        text = "Start a conversation",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Ask anything — graph tools activate automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact strip above the input bar naming why sending is unavailable. Actionable
 * states (missing/failed model or graph) link to Settings; transient states show
 * progress instead so the user knows the app is working, not wedged.
 */
@Composable
private fun EngineStatusBanner(
    notice: EngineNotice,
    onOpenSettings: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = if (notice.actionable)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (!notice.actionable) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (notice.actionable)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (notice.actionable) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(notice.message)) }
                ) {
                    Text("Copy")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onOpenSettings) {
                    Text("Settings")
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(end = 48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Thinking...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageRow(msg: UiMessage) {
    val isUser = msg.role == "user"

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (isUser) 12.dp else 2.dp,
                    bottomEnd = if (isUser) 2.dp else 12.dp
                ),
                color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .then(if (isUser) Modifier.padding(start = 48.dp) else Modifier.padding(end = 48.dp))
            ) {
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        // Tool-round cards for assistant messages.
        if (msg.toolRounds.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            msg.toolRounds.forEach { round ->
                ToolRoundCard(round = round)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ToolRoundCard(round: ToolRoundUi) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 48.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        // Header row — always visible.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Tool: ${round.tool}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
        }

        // Expanded detail — args + result.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
            ) {
                MonoLabel("Args:")
                MonoBlock(round.argsJson)
                Spacer(Modifier.height(6.dp))
                MonoLabel("Result:")
                MonoBlock(round.resultJson)
            }
        }
    }
}

@Composable
private fun MonoLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
}

@Composable
private fun MonoBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(8.dp)
    )
}

@Composable
private fun ChatInputBar(
    enabled: Boolean,
    generating: Boolean,
    canCancelGeneration: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    fun doSend() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && enabled) {
            onSend(trimmed)
            text = ""
        }
    }

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { doSend() }),
                enabled = enabled,
                trailingIcon = if (text.isNotBlank()) {
                    {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear input")
                        }
                    }
                } else null
            )
            Spacer(Modifier.width(8.dp))
            if (generating) {
                if (canCancelGeneration) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel generation",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = { doSend() },
                    enabled = enabled && text.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (enabled && text.isNotBlank())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}
