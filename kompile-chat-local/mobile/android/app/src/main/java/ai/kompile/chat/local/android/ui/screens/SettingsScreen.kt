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
import ai.kompile.chat.local.android.viewmodel.ChatViewModel
import ai.kompile.chat.local.android.viewmodel.GraphImportOutcome
import ai.kompile.chat.local.android.viewmodel.ProjectImportOutcome
import kotlinx.coroutines.launch

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
    var maxRounds   by remember { mutableIntStateOf(prefs.maxToolRounds) }
    var temperature by remember { mutableFloatStateOf(prefs.temperature) }
    var maxTokens   by remember { mutableIntStateOf(prefs.maxTokens) }
    var importing   by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // SAF launchers copy large assets on Dispatchers.IO; project archives may be gigabytes.
    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importing = true
                importError = null
                importNotice = null
                try {
                    when (val outcome = vm.importProjectAndActivate(it)) {
                        is ProjectImportOutcome.Active -> {
                            projectName = outcome.projectName
                            projectRevision = outcome.revision
                            projectSourceCount = outcome.sourceCount
                            modelPath = outcome.modelPath
                            kgraphPath = outcome.graphPath
                            importNotice = "Project activated with ${outcome.sourceCount} source files."
                        }
                        is ProjectImportOutcome.Failed -> {
                            importError = outcome.displayMessage
                        }
                    }
                } finally {
                    importing = false
                }
            }
        }
    }

    val kgraphPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importing = true
                importError = null
                importNotice = null
                try {
                    when (val outcome = vm.importKgraphAndApply(it)) {
                        is GraphImportOutcome.Active -> kgraphPath = outcome.path
                        is GraphImportOutcome.Deferred -> {
                            kgraphPath = outcome.path
                            importNotice = outcome.message
                        }
                        is GraphImportOutcome.Failed -> importError = outcome.message
                    }
                } finally {
                    importing = false
                }
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importing = true
                importError = null
                importNotice = null
                try {
                    val result = vm.importModelAndActivate(it)
                    val path = result.getOrNull()
                    if (path == null) {
                        importError = result.exceptionOrNull()?.message
                            ?: "Model import failed. Select a target-compiled SameDiff .sdz file."
                    } else {
                        modelPath = path
                    }
                } finally {
                    importing = false
                }
            }
        }
    }

    fun save() {
        prefs.maxToolRounds   = maxRounds
        prefs.temperature     = temperature
        prefs.maxTokens       = maxTokens
        prefs.modelStagingUrl = stagingUrl
        vm.onSettingsChanged()
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
                        enabled = !importing,
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

            // ── Canonical offline project ─────────────────────────────────────
            SectionHeader(icon = Icons.Default.Memory, title = "Offline Project")
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
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { projectPicker.launch("*/*") },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import Kompile project (.kproject)")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = stagingUrl,
                        onValueChange = { stagingUrl = it },
                        enabled = !importing,
                        singleLine = true,
                        label = { Text("Kompile staging server URL") },
                        supportingText = {
                            Text("For example, http://your-workstation:8090")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            prefs.modelStagingUrl = stagingUrl
                            val result = vm.openModelStaging()
                            importError = result.exceptionOrNull()?.message
                            if (result.isSuccess) {
                                importNotice = "Staging opened in your browser. Download a .kproject, then import it here."
                            }
                        },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Prepare from GGUF / Hugging Face")
                    }
                    Text(
                        text = "The browser handles connected staging. This APK keeps no INTERNET permission. "
                                + "The downloaded project carries the target SDZ, AOT graph, Markdown sources, "
                                + "and their hashes as one versioned artifact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (importing) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Verifying project, model target, graph, and native activation…",
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
                        enabled = !importing,
                        onPick = { kgraphPicker.launch("*/*") }
                    )
                    Spacer(Modifier.height(12.dp))
                    FilePickerRow(
                        label = "SameDiff Model (.sdz)",
                        path = modelPath,
                        enabled = !importing,
                        onPick = { modelPicker.launch("*/*") }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Use these only for low-level testing. A .kproject is the normal "
                                + "maintainable path because model, graph, sources, target, and revision "
                                + "are activated together.",
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
                        steps = 61
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
