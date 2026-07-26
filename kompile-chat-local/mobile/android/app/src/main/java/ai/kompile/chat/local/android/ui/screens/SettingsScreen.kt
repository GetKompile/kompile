package ai.kompile.chat.local.android.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.staging.ModelStagingHandoff
import ai.kompile.chat.local.android.viewmodel.ChatViewModel
import ai.kompile.chat.local.android.viewmodel.GraphImportOutcome
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportUiState
import ai.kompile.chat.local.android.viewmodel.ModelSmokeUiState
import ai.kompile.chat.local.android.viewmodel.ModelUiState
import ai.kompile.chat.local.android.viewmodel.ProjectImportOutcome
import ai.kompile.chat.local.android.viewmodel.stagingUrlProblem
import kotlinx.coroutines.launch
import org.nd4j.dsp.model.HuggingFaceGgmlResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val prefs = vm.prefs

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
    // Hugging Face references and discoveries are one-shot state and are never persisted.
    var huggingFaceUrl by remember { mutableStateOf("") }
    var huggingFaceDiscovery by remember {
        mutableStateOf<HuggingFaceGgmlResolver.Discovery?>(null)
    }
    var huggingFaceSelection by remember {
        mutableStateOf<HuggingFaceGgmlResolver.Candidate?>(null)
    }
    var huggingFaceResolving by remember { mutableStateOf(false) }
    var maxRounds   by remember { mutableIntStateOf(prefs.maxToolRounds) }
    var temperature by remember { mutableFloatStateOf(prefs.temperature) }
    var maxTokens   by remember { mutableIntStateOf(prefs.maxTokens) }
    // Import progress is ViewModel state shared with ChatScreen: an import started on
    // either screen disables import controls on both, and only one runs at a time.
    val importing by vm.importBusy.collectAsState()
    // Imports and the decode test are refused during generation; disabling the
    // buttons here beats a refusal message that only ChatScreen's snackbar shows.
    val thinking by vm.thinking.collectAsState()
    val importBlocked = importing || thinking
    val modelState by vm.modelState.collectAsState()
    val modelSmokeState by vm.modelSmokeState.collectAsState()
    val importDiagnostics by vm.importDiagnostics.collectAsState()
    val huggingFaceImportState by vm.huggingFaceImportState.collectAsState()
    var importError by remember { mutableStateOf<String?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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

    // SAF launchers copy large assets on Dispatchers.IO; project archives may be gigabytes.
    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importError = null
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
                importError = null
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
                importError = null
                importNotice = null
                val result = vm.importModelAndActivate(it)
                refreshSelectionFromPrefs()
                if (result.isFailure) {
                    importError = result.exceptionOrNull()?.message
                        ?: "Complete model import failed."
                } else {
                    importNotice =
                        "Model activated after tokenizer, chat template, target, and decode verification."
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
                    Button(
                        onClick = { save() },
                        enabled = !importing && stagingProblem == null,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Save")
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
            Spacer(Modifier.height(4.dp))

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
                    // The active files are the ground truth the imports below replace;
                    // keep them visible so the card always matches what will run.
                    Text(
                        text = "Model: " + (modelPath.takeIf(String::isNotBlank)
                            ?.substringAfterLast('/') ?: "none imported"),
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
                    Text(
                        text = "Hugging Face source",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedTextField(
                        value = huggingFaceUrl,
                        onValueChange = { value ->
                            huggingFaceUrl = value
                            huggingFaceDiscovery = null
                            huggingFaceSelection = null
                        },
                        enabled = !importing && !huggingFaceResolving,
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
                            scope.launch {
                                importError = null
                                importNotice = null
                                huggingFaceDiscovery = null
                                huggingFaceSelection = null
                                huggingFaceResolving = true
                                try {
                                    val result = vm.discoverHuggingFaceAcquisition(huggingFaceUrl)
                                    importError = result.exceptionOrNull()?.message
                                    result.getOrNull()?.let { discovery ->
                                        huggingFaceDiscovery = discovery
                                        if (discovery.requiresSelection()) {
                                            importNotice = "Found ${discovery.candidates.size} GGUF/GGML files. " +
                                                "Select the intended quantization below."
                                        } else {
                                            val candidate = discovery.selectedCandidate().orElseThrow()
                                            huggingFaceSelection = candidate
                                            val imported = vm.importHuggingFaceModelAndActivate(candidate)
                                            refreshSelectionFromPrefs()
                                            importError = imported.exceptionOrNull()?.message
                                            if (imported.isSuccess) {
                                                importNotice = "${candidate.path} downloaded into app storage, " +
                                                    "decoded by SDX, and activated for chat."
                                            }
                                        }
                                    }
                                } finally {
                                    huggingFaceResolving = false
                                }
                            }
                        },
                        enabled = !importBlocked &&
                            !huggingFaceResolving &&
                            huggingFaceUrl.isNotBlank() &&
                            huggingFaceProblem == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (huggingFaceResolving) {
                                "Resolving Hugging Face repository…"
                            } else {
                                "Resolve Hugging Face GGUF/GGML"
                            }
                        )
                    }
                    when (val state = huggingFaceImportState) {
                        HuggingFaceImportUiState.Idle -> if (huggingFaceResolving) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Resolving the Hugging Face repository…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is HuggingFaceImportUiState.Downloading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            val downloadedMiB = state.downloadedBytes / (1024L * 1024L)
                            val expected = state.expectedBytes?.let { total ->
                                " / ${total / (1024L * 1024L)} MiB"
                            }.orEmpty()
                            Text(
                                "Downloading ${state.fileName}: $downloadedMiB MiB$expected",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is HuggingFaceImportUiState.Activating -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Loading ${state.fileName} in SDX and running a real decode…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is HuggingFaceImportUiState.Active -> Text(
                            "Running through ${state.route}: ${state.path.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is HuggingFaceImportUiState.Failed -> Unit
                    }
                    huggingFaceDiscovery
                        ?.takeIf { it.requiresSelection() }
                        ?.let { discovery ->
                            Text(
                                text = "Choose one resolved model file:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            discovery.candidates.forEach { candidate ->
                                FilterChip(
                                    selected = huggingFaceSelection?.path == candidate.path,
                                    onClick = { huggingFaceSelection = candidate },
                                    enabled = !importBlocked && !huggingFaceResolving,
                                    label = { Text(huggingFaceCandidateLabel(candidate)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val candidate = huggingFaceSelection
                                        ?: return@OutlinedButton
                                    scope.launch {
                                        importError = null
                                        importNotice = null
                                        huggingFaceResolving = true
                                        try {
                                            val result = vm.importHuggingFaceModelAndActivate(candidate)
                                            refreshSelectionFromPrefs()
                                            importError = result.exceptionOrNull()?.message
                                            if (result.isSuccess) {
                                                importNotice = "${candidate.path} downloaded into app storage, " +
                                                    "decoded by SDX, and activated for chat."
                                            }
                                        } finally {
                                            huggingFaceResolving = false
                                        }
                                    }
                                },
                                enabled = !importBlocked &&
                                    !huggingFaceResolving &&
                                    huggingFaceSelection != null,
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
                            importError = null
                            importNotice = null
                            val result = vm.openModelStaging(stagingArtifact)
                            importError = result.exceptionOrNull()?.message
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
                    if (importing) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Verifying the selected archive, required assets, exact target, and a real decode…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    importError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
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

            // ── Durable import diagnostics ─────────────────────────────────────
            SectionHeader(icon = Icons.Default.Memory, title = "Import Diagnostics")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Bounded on-device history. URLs, credentials, source references, " +
                            "and stack traces are never stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (importDiagnostics.isEmpty()) {
                        Text(
                            text = "No import or browser handoff events yet.",
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
                            Spacer(Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            onClick = { vm.clearImportDiagnostics() },
                            enabled = !importing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear import history")
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
                        is ModelSmokeUiState.Failed -> Text(
                            text = "Decode failed on ${smoke.route}: ${smoke.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
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
                        valueRange = 64f..4096f,
                        // 63 intervals of exactly 64 tokens, so the shown value is
                        // always a clean multiple of 64 (steps counts interior stops).
                        steps = 62
                    )
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
