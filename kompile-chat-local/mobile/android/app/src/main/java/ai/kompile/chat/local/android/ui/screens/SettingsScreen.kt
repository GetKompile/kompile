package ai.kompile.chat.local.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.DspDiagnosticsTraceLog
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.diagnostics.SmokeDecodeTraceLog
import ai.kompile.chat.local.android.staging.ModelStagingHandoff
import ai.kompile.chat.local.android.viewmodel.ChatViewModel
import ai.kompile.chat.local.android.viewmodel.GraphImportOutcome
import ai.kompile.chat.local.android.viewmodel.HuggingFaceConfigurationUiState
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportStep
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportUiState
import ai.kompile.chat.local.android.viewmodel.ImportOperationKind
import ai.kompile.chat.local.android.viewmodel.ModelSmokeUiState
import ai.kompile.chat.local.android.viewmodel.ModelUiState
import ai.kompile.chat.local.android.viewmodel.ProjectImportOutcome
import ai.kompile.chat.local.android.viewmodel.effectiveMaxTokensForTarget
import ai.kompile.chat.local.android.viewmodel.maxGenerationTokensForTarget
import ai.kompile.chat.local.android.viewmodel.stagingUrlProblem
import kotlinx.coroutines.launch
import org.nd4j.dsp.model.HuggingFaceGgmlResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    vm: ChatViewModel
) {
    val prefs = vm.prefs
    val context = LocalContext.current

    // Local state mirrors prefs; "Save" commits back.
    var kgraphPath  by remember { mutableStateOf(prefs.kgraphPath) }
    var modelPath   by remember { mutableStateOf(prefs.modelPath) }
    var projectName by remember { mutableStateOf(prefs.activeProjectName) }
    var projectRevision by remember { mutableStateOf(prefs.activeProjectRevision) }
    var projectSourceCount by remember { mutableIntStateOf(prefs.activeProjectSourceCount) }
    var stagingUrl by remember { mutableStateOf(prefs.modelStagingUrl) }
    var stagingArtifact by remember {
        mutableStateOf(ModelStagingHandoff.Artifact.MODEL)
    }
    var maxRounds   by remember { mutableIntStateOf(prefs.maxToolRounds) }
    var temperature by remember { mutableFloatStateOf(prefs.temperature) }
    val maxTokenLimit = maxGenerationTokensForTarget(BuildConfig.SDX_TARGET_PROFILE)
    var maxTokens by remember {
        mutableIntStateOf(
            effectiveMaxTokensForTarget(prefs.maxTokens, BuildConfig.SDX_TARGET_PROFILE)
        )
    }
    val importOperation by vm.importOperation.collectAsState()
    val importing = importOperation != ImportOperationKind.NONE
    val thinking by vm.thinking.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val modelLoadProgress by vm.modelLoadProgress.collectAsState()
    val activeModelLoaded = modelState is ModelUiState.Ready
    // A proven model owns the runtime until the user explicitly unloads it. This
    // mirrors the ViewModel gate and prevents stale picker callbacks from replacing it.
    val lifecycleBusy = importing || thinking
    val importBlocked = lifecycleBusy || activeModelLoaded
    val modelSmokeState by vm.modelSmokeState.collectAsState()
    val importDiagnostics by vm.importDiagnostics.collectAsState()
    val huggingFaceImportState by vm.huggingFaceImportState.collectAsState()
    val huggingFaceUrl by vm.huggingFaceReference.collectAsState()
    val huggingFaceDiscovery by vm.huggingFaceDiscovery.collectAsState()
    val huggingFaceSelection by vm.huggingFaceSelection.collectAsState()
    val huggingFaceConfigurationState by vm.huggingFaceConfigurationState.collectAsState()
    val modelPreparationOptions by vm.modelPreparationOptions.collectAsState()
    val localModelOptimizationState by vm.localModelOptimizationState.collectAsState()
    val localModelSources by vm.localModelSources.collectAsState()
    val huggingFaceBusy = huggingFaceImportState is HuggingFaceImportUiState.Working ||
        huggingFaceImportState is HuggingFaceImportUiState.Retrying
    var importError by remember { mutableStateOf<String?>(null) }
    var importErrorStackTrace by remember { mutableStateOf<String?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pendingHuggingFaceStart by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val pending = pendingHuggingFaceStart
        pendingHuggingFaceStart = null
        pending?.invoke()
    }

    fun startHuggingFaceWithNotificationPermission(action: () -> Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            return action()
        }
        pendingHuggingFaceStart = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    fun clearImportError() {
        importError = null
        importErrorStackTrace = null
    }
    val clipboard = LocalClipboardManager.current
    // Inline validation keeps the field, the Prepare button, and Save in one
    // consistent state; an invalid URL can neither launch nor be persisted.
    val stagingProblem = stagingUrlProblem(stagingUrl)
    val huggingFaceProblem = HuggingFaceGgmlAcquisition.problem(huggingFaceUrl)

    // Prefs are the single source of truth for the active selection. Standalone model
    // and graph imports deliberately clear project provenance, so re-read everything
    // after every import instead of patching individual fields and drifting.
    fun refreshSelectionFromPrefs() {
        kgraphPath = prefs.kgraphPath
        modelPath = prefs.modelPath
        projectName = prefs.activeProjectName
        projectRevision = prefs.activeProjectRevision
        projectSourceCount = prefs.activeProjectSourceCount
    }

    LaunchedEffect(huggingFaceImportState, localModelOptimizationState) {
        if (huggingFaceImportState is HuggingFaceImportUiState.Active ||
            localModelOptimizationState is HuggingFaceImportUiState.Active
        ) {
            refreshSelectionFromPrefs()
            vm.refreshLocalModelSources()
        }
    }

    // SAF launchers copy large assets on Dispatchers.IO; project archives may be gigabytes.
    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                clearImportError()
                importNotice = null
                when (val outcome = vm.importProjectAndActivate(it)) {
                    is ProjectImportOutcome.Active -> {
                        refreshSelectionFromPrefs()
                        importNotice = "Project activated with ${outcome.sourceCount} source files " +
                                "after a real accelerator decode passed."
                    }
                    is ProjectImportOutcome.Failed -> {
                        refreshSelectionFromPrefs()
                        importError = outcome.displayMessage
                        importErrorStackTrace = outcome.stackTrace
                    }
                }
            }
        }
    }

    val kgraphPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                clearImportError()
                importNotice = null
                when (val outcome = vm.importKgraphAndApply(it)) {
                    is GraphImportOutcome.Active -> refreshSelectionFromPrefs()
                    is GraphImportOutcome.Deferred -> {
                        refreshSelectionFromPrefs()
                        importNotice = outcome.message
                    }
                    is GraphImportOutcome.Failed -> {
                        refreshSelectionFromPrefs()
                        importError = outcome.message
                        importErrorStackTrace = outcome.stackTrace
                    }
                }
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                clearImportError()
                importNotice = null
                val result = vm.importModelAndActivate(it)
                refreshSelectionFromPrefs()
                if (result.isFailure) {
                    val failure = result.exceptionOrNull()
                    importError = failure?.message ?: "Complete model import failed."
                    importErrorStackTrace = failure?.stackTraceToString()
                } else {
                    importNotice =
                        "Model activated after tokenizer, chat template, target, and decode verification."
                }
            }
        }
    }

    val localModelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                clearImportError()
                importNotice = null
                val result = vm.optimizeLocalModel(it)
                refreshSelectionFromPrefs()
                result.exceptionOrNull()?.let { failure ->
                    importError = failure.message ?: "Local model optimization failed."
                    importErrorStackTrace = failure.stackTraceToString()
                }
            }
        }
    }

    fun save() {
        // Only engine-affecting values force a rebuild (which resets the conversation
        // and reloads the native model); anything else persists without disturbing a
        // working session, and an unchanged Save is a plain navigation.
        val engineSettingsChanged = maxRounds != prefs.maxToolRounds ||
            temperature != prefs.temperature ||
            maxTokens != prefs.maxTokens
        prefs.maxToolRounds   = maxRounds
        prefs.temperature     = temperature
        prefs.maxTokens       = maxTokens
        prefs.modelStagingUrl = stagingUrl
        if (engineSettingsChanged) {
            vm.onSettingsChanged()
        }
        onBack()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = onOpenChat,
                        modifier = Modifier.testTag("open_chat_from_settings"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Chat")
                    }
                    IconButton(
                        onClick = { save() },
                        enabled = !importing && stagingProblem == null,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save settings")
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ModelStatusHeader(
                modelState = modelState,
                importOperation = importOperation,
                loadProgress = modelLoadProgress,
                modifier = Modifier.padding(top = 4.dp),
            )

            // ── Canonical offline model and optional knowledge project ──────────
            SectionHeader(icon = Icons.Default.Memory, title = "Offline Model & Project")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (projectName.isBlank()) {
                            "No project is active."
                        } else {
                            "Active: " + projectName
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (projectRevision.isNotBlank()) {
                        Text(
                            text = "Revision " + projectRevision.take(12)
                                    + " · " + projectSourceCount + " source files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Target: " + BuildConfig.SDX_TARGET_PROFILE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "APK build: ${BuildConfig.APK_BUILD_ID} · versionCode ${BuildConfig.VERSION_CODE}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The active files are the ground truth the imports below replace;
                    // keep them visible so the card always matches what will run.
                    Text(
                        text = "Model storage: " +
                            (modelPath.takeIf(String::isNotBlank) ?: "none imported"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelPath.isBlank())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Graph: " + (kgraphPath.takeIf(String::isNotBlank)
                            ?.substringAfterLast('/') ?: "none (chat runs without graph tools)"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (activeModelLoaded) {
                        Text(
                            text = "This model exclusively owns the local runtime. Use it now, or unload it before selecting another model, project, or graph.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onOpenChat,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("use_active_model")
                        ) {
                            Text("Use active model")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    clearImportError()
                                    importNotice = null
                                    val result = vm.unloadActiveModel()
                                    refreshSelectionFromPrefs()
                                    result.fold(
                                        onSuccess = {
                                            importNotice =
                                                "Model unloaded. You can now import another model or project."
                                        },
                                        onFailure = { failure ->
                                            importError = failure.message
                                                ?: "The active model could not be unloaded."
                                            importErrorStackTrace = failure.stackTraceToString()
                                        }
                                    )
                                }
                            },
                            enabled = !lifecycleBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("unload_active_model")
                        ) {
                            Text("Unload active model")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(
                        onClick = { modelPicker.launch("*/*") },
                        enabled = !importBlocked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import chat model (.sdz)")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { projectPicker.launch("*/*") },
                        enabled = !importBlocked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import full project (.kproject)")
                    }
                    Spacer(Modifier.height(12.dp))
                    ModelOptimizationOptionsPane(
                        options = modelPreparationOptions,
                        onOptionsChanged = vm::updateModelPreparationOptions,
                        enabled = !importBlocked && !huggingFaceBusy,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Hugging Face source",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedTextField(
                        value = huggingFaceUrl,
                        onValueChange = vm::updateHuggingFaceReference,
                        enabled = !importBlocked && !huggingFaceBusy,
                        singleLine = true,
                        isError = huggingFaceProblem != null,
                        label = { Text("Hugging Face owner/repository or URL") },
                        supportingText = {
                            Text(
                                huggingFaceProblem
                                    ?: "Repository, tree, blob, and resolve references are supported."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            startHuggingFaceWithNotificationPermission {
                                clearImportError()
                                importNotice = null
                                vm.startHuggingFaceResolution()
                            }
                        },
                        enabled = !importBlocked &&
                            !huggingFaceBusy &&
                            huggingFaceUrl.isNotBlank() &&
                            huggingFaceProblem == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (huggingFaceBusy &&
                                (huggingFaceImportState as? HuggingFaceImportUiState.Observable)
                                    ?.step == HuggingFaceImportStep.RESOLVE) {
                                "Resolving Hugging Face repository…"
                            } else {
                                "Resolve Hugging Face GGUF/GGML"
                            }
                        )
                    }
                    HuggingFaceImportProgressPanel(
                        state = huggingFaceImportState,
                        onCancelStep = vm::cancelHuggingFaceStep,
                        onRetryStep = { step ->
                            startHuggingFaceWithNotificationPermission {
                                vm.retryHuggingFaceStep(step)
                            }
                        },
                        onOpenAppStorageSettings = { vm.openAppStorageSettings() },
                        onCopySmokeDecodeTrace = {
                            clipboard.setText(
                                AnnotatedString(SmokeDecodeTraceLog(context).readContents())
                            )
                        },
                        diagnostics = importDiagnostics
                    )
                    when (val configurationState = huggingFaceConfigurationState) {
                        HuggingFaceConfigurationUiState.Idle -> Unit
                        is HuggingFaceConfigurationUiState.Resolving -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("hugging_face_configuration_resolving")
                            )
                            Text(
                                text = "Resolving the shared tokenizer and repository configuration for " +
                                    configurationState.repository + "…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is HuggingFaceConfigurationUiState.Resolved -> {
                            val configuration = configurationState.configuration
                            Text(
                                text = "Repository tokenizer/configuration resolved",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("hugging_face_configuration_resolved")
                            )
                            Text(
                                text = "Weights: ${configuration.repository}@" +
                                    configuration.immutableRevision.take(12) + " · " +
                                    "${configuration.modelCandidatePaths.size} model candidate(s)\n" +
                                    "Canonical assets: ${configuration.assetSources.size} pinned source repo(s)\n" +
                                    configuration.assets.joinToString("\n") { asset ->
                                        "${asset.name}: ${asset.sourceRepository}@" +
                                            asset.sourceRevision.take(12) + "/${asset.path}"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is HuggingFaceConfigurationUiState.Failed -> {
                            Text(
                                text = "Repository configuration could not be resolved: " +
                                    configurationState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("hugging_face_configuration_failed")
                            )
                        }
                    }
                    huggingFaceDiscovery
                        ?.takeIf {
                            huggingFaceImportState is HuggingFaceImportUiState.SelectionRequired &&
                                it.requiresSelection()
                        }
                        ?.let { discovery ->
                            Text(
                                text = "Choose one resolved model file:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            discovery.candidates.forEach { candidate ->
                                FilterChip(
                                    selected = huggingFaceSelection?.path == candidate.path,
                                    onClick = { vm.selectHuggingFaceCandidate(candidate) },
                                    enabled = !importBlocked && !huggingFaceBusy,
                                    label = { Text(huggingFaceCandidateLabel(candidate)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    startHuggingFaceWithNotificationPermission {
                                        clearImportError()
                                        importNotice = null
                                        vm.startSelectedHuggingFaceImport()
                                    }
                                },
                                enabled = !importBlocked &&
                                    !huggingFaceBusy &&
                                    huggingFaceSelection != null &&
                                    (huggingFaceConfigurationState as?
                                        HuggingFaceConfigurationUiState.Resolved)
                                        ?.configuration
                                        ?.modelCandidatePaths
                                        ?.contains(huggingFaceSelection?.path) == true,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Import selected GGUF/GGML with SDX")
                            }
                        }
                    Text(
                        text = "Repository discovery and download go directly to public Hugging Face " +
                            "endpoints and never send the reference to Kompile. The app writes the " +
                            "selected GGUF/GGML into private storage, loads it through libsdx_llm, " +
                            "runs a real bounded decode, and only then makes it the active chat model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Optimize local model",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "Re-run the selected preparation profile against an app-retained GGUF/GGML source. " +
                            "The original stays in place; optimized GGUF and canonical SDZ outputs are content-addressed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { localModelPicker.launch("*/*") },
                        enabled = !importBlocked && !huggingFaceBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("optimize_local_model_picker")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose local GGUF/GGML to retain and optimize")
                    }
                    if (localModelSources.isEmpty()) {
                        Text(
                            text = "No retained raw model sources yet. A completed Hugging Face download appears here automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Retained sources",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        localModelSources.forEach { source ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        clearImportError()
                                        importNotice = null
                                        val result = vm.optimizeLocalModel(source.path)
                                        refreshSelectionFromPrefs()
                                        result.exceptionOrNull()?.let { failure ->
                                            importError = failure.message ?: "Local model optimization failed."
                                            importErrorStackTrace = failure.stackTraceToString()
                                        }
                                    }
                                },
                                enabled = !importBlocked && !huggingFaceBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = source.displayName + " · " +
                                        "%.2f GiB".format(source.bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HuggingFaceImportProgressPanel(
                        state = localModelOptimizationState,
                        onCancelStep = { false },
                        onRetryStep = vm::retryLocalModelOptimizationStep,
                        onOpenAppStorageSettings = { vm.openAppStorageSettings() },
                        onCopySmokeDecodeTrace = {
                            clipboard.setText(
                                AnnotatedString(SmokeDecodeTraceLog(context).readContents())
                            )
                        },
                        diagnostics = importDiagnostics,
                        pipelineTitle = "Local optimization pipeline",
                        pipelineDescription =
                            "Verification, conversion, accelerator preparation, decode, and activation remain observable.",
                        diagnosticOperationPrefix = "local model optimization",
                        visibleSteps = setOf(
                            HuggingFaceImportStep.PREFLIGHT,
                            HuggingFaceImportStep.VERIFY,
                            HuggingFaceImportStep.CONVERT_SDZ,
                            HuggingFaceImportStep.TARGET_CACHE,
                            HuggingFaceImportStep.SDX_LOAD,
                            HuggingFaceImportStep.SMOKE_DECODE,
                            HuggingFaceImportStep.ACTIVATE,
                            HuggingFaceImportStep.ACTIVE,
                        ),
                        testTagPrefix = "local_model_optimization",
                        failureCopyTitle = "Local model optimization failure",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Prepared Kompile artifacts",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedTextField(
                        value = stagingUrl,
                        onValueChange = { stagingUrl = it },
                        enabled = !importing,
                        singleLine = true,
                        isError = stagingProblem != null,
                        label = { Text("Kompile prepared-artifact server URL (optional)") },
                        supportingText = {
                            Text(stagingProblem ?: "For example, http://your-workstation:8090")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = stagingArtifact == ModelStagingHandoff.Artifact.MODEL,
                            onClick = { stagingArtifact = ModelStagingHandoff.Artifact.MODEL },
                            enabled = !importing,
                            label = { Text("Chat model (.sdz)") }
                        )
                        FilterChip(
                            selected = stagingArtifact == ModelStagingHandoff.Artifact.PROJECT,
                            onClick = { stagingArtifact = ModelStagingHandoff.Artifact.PROJECT },
                            enabled = !importing,
                            label = { Text("Full project (.kproject)") }
                        )
                    }
                    Text(
                        text = if (stagingArtifact == ModelStagingHandoff.Artifact.MODEL) {
                            "Downloads an already target-prepared chat model for this APK flavor."
                        } else {
                            "Downloads a prepared project with its SDZ, graph, and synchronized sources."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            prefs.modelStagingUrl = stagingUrl
                            clearImportError()
                            importNotice = null
                            val result = vm.openModelStaging(stagingArtifact)
                            val failure = result.exceptionOrNull()
                            importError = failure?.message
                            importErrorStackTrace = failure?.stackTraceToString()
                            if (result.isSuccess) {
                                importNotice = "Prepared ${stagingArtifact.fileExtension} downloads " +
                                    "opened. Download one, return here, then use the matching import button."
                            }
                        },
                        // Blank configuration stays clickable so the action explains how to
                        // configure this optional source instead of appearing mysteriously disabled.
                        enabled = !importing && stagingProblem == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open prepared ${stagingArtifact.fileExtension} downloads")
                    }
                    Text(
                        text = "Kompile staging is only a prepared SDZ/KProject source. It receives " +
                            "no Hugging Face URL or raw component bundle from this APK. Connected " +
                            "repository discovery is restricted to public huggingface.co and stores no credentials.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    importError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        CopyableStartupError(
                            message = message,
                            diagnostics = importDiagnostics,
                            exactStackTrace = importErrorStackTrace
                        )
                    }
                    importNotice?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ── Durable app diagnostics ────────────────────────────────────────
            SectionHeader(icon = Icons.Default.Memory, title = "App Diagnostics")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Bounded on-device import, activation, and execution history. URLs, " +
                            "credentials, and source paths are redacted; bounded technical details " +
                            "remain visible and copyable for diagnosis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (importDiagnostics.isEmpty()) {
                        Text(
                            text = "No import, activation, or execution events yet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        importDiagnostics.forEach { entry ->
                            val entryColor = when (entry.severity) {
                                ImportDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                                ImportDiagnosticSeverity.SUCCESS -> MaterialTheme.colorScheme.primary
                                ImportDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT,
                                    java.text.DateFormat.MEDIUM
                                ).format(java.util.Date(entry.timestampEpochMillis)) +
                                    " · " + entry.operation + " · " + entry.phase +
                                    " · " + entry.severity.name.lowercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = entryColor
                            )
                            Text(
                                text = entry.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = entryColor
                            )
                            if (entry.remediation.isNotBlank()) {
                                Text(
                                    text = "Next: " + entry.remediation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (entry.technicalDetails.isNotBlank()) {
                                SelectionContainer {
                                    Text(
                                        text = entry.technicalDetails,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 12,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (entry.severity == ImportDiagnosticSeverity.ERROR) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(
                                            AnnotatedString(ImportDiagnosticPolicy.copyText(entry))
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Copy error details")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        ImportDiagnosticPolicy.copyText(importDiagnostics.reversed())
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Copy all diagnostics")
                        }
                        OutlinedButton(
                            onClick = { vm.clearImportDiagnostics() },
                            enabled = !importing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear diagnostics")
                        }
                    }
                }
            }

            // ── Advanced compatibility imports ─────────────────────────────────
            SectionHeader(icon = Icons.Default.FolderOpen, title = "Advanced Files")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    FilePickerRow(
                        label = "Knowledge Graph (.kgraph)",
                        path = kgraphPath,
                        enabled = !importBlocked,
                        onPick = { kgraphPicker.launch("*/*") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.runModelSmokeTest() },
                        enabled = !importBlocked &&
                                modelState is ModelUiState.Ready &&
                                modelSmokeState !is ModelSmokeUiState.Running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run local model decode test")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(SmokeDecodeTraceLog(context).readContents())
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy_smoke_decode_trace")
                    ) {
                        Text("Copy smoke-decode trace")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(DspDiagnosticsTraceLog(context).readContents())
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy_dsp_diagnostics_trace")
                    ) {
                        Text("Copy DSP diagnostics")
                    }
                    Text(
                        text = "Both traces are retained in app-private storage with three rotating backups. Select DSP diagnostics in Model optimization before preparing or decoding to populate the deep report.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (val smoke = modelSmokeState) {
                        ModelSmokeUiState.NotRun -> Text(
                            text = "A real bounded decode runs automatically on every model or "
                                    + "project import. Run it again here after an app restart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is ModelSmokeUiState.Running -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "Decoding on ${smoke.route}…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is ModelSmokeUiState.Passed -> Text(
                            text = "Passed on ${smoke.route} in ${smoke.elapsedMs} ms: "
                                    + smoke.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is ModelSmokeUiState.Failed -> {
                            val diagnostic = ImportDiagnosticPolicy.errorForMessage(
                                importDiagnostics,
                                smoke.message,
                                operationPrefix = "local model"
                            )
                            Text(
                                text = "Decode failed on ${smoke.route}: ${smoke.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (diagnostic?.technicalDetails?.isNotBlank() == true) {
                                SelectionContainer {
                                    Text(
                                        text = diagnostic.technicalDetails,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            ImportDiagnosticPolicy.copyTextForError(
                                                smoke.message,
                                                importDiagnostics,
                                                operationPrefix = "local model"
                                            )
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Copy decode error details")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "A standalone graph import is for low-level testing. The primary "
                                + ".sdz button above requires tokenizer.json, tokenizer configuration "
                                + "or chat template, the SDX text-generation contract, and this APK's "
                                + "AOT target. A .kproject additionally activates graph, Markdown "
                                + "sources, target, and revision together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Generation ────────────────────────────────────────────────────
            SectionHeader(icon = Icons.Default.Settings, title = "Generation")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Temperature: ${"%.2f".format(temperature)}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 39
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Max Tokens: $maxTokens",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { maxTokens = it.toInt() },
                        valueRange = 64f..maxTokenLimit.toFloat(),
                        // Use exact 64-token stops. Tensor G3 is intentionally capped at
                        // the 128-token KV window already proven by the smoke decode.
                        steps = ((maxTokenLimit - 64) / 64 - 1).coerceAtLeast(0)
                    )
                    if (BuildConfig.SDX_TARGET_PROFILE == "android-arm64-nnapi-accelerator") {
                        Text(
                            text = "Tensor G3 limit: 128 tokens to keep the NNAPI KV-cache allocation within the device memory envelope.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Max Tool Rounds: $maxRounds",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = maxRounds.toFloat(),
                        onValueChange = { maxRounds = it.toInt() },
                        valueRange = 1f..8f,
                        steps = 6
                    )
                    Text(
                        text = "Maximum graph tool calls per conversation turn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun FilePickerRow(
    label: String,
    path: String,
    enabled: Boolean,
    onPick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (path.isBlank()) "Not set" else path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = if (path.isBlank())
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onPick, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Browse")
            }
        }
    }
}

@Composable
private fun settingsCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
)

private fun huggingFaceCandidateLabel(candidate: HuggingFaceGgmlResolver.Candidate): String {
    val details = buildList {
        candidate.quantizationHint?.let(::add)
        if (candidate.size > 0L) {
            add("${candidate.size / (1024L * 1024L)} MiB")
        }
    }
    return candidate.path + if (details.isEmpty()) "" else " — ${details.joinToString(" · ")}"
}
