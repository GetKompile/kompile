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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.kompile.chat.local.android.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val prefs = vm.prefs

    // Local state mirrors prefs; "Save" commits back.
    var remoteUrl   by remember { mutableStateOf(prefs.remoteBaseUrl) }
    var remoteModel by remember { mutableStateOf(prefs.remoteModel) }
    var apiKey      by remember { mutableStateOf(prefs.remoteApiKey) }
    var kgraphPath  by remember { mutableStateOf(prefs.kgraphPath) }
    var modelPath   by remember { mutableStateOf(prefs.modelPath) }
    var maxRounds   by remember { mutableIntStateOf(prefs.maxToolRounds) }
    var temperature by remember { mutableFloatStateOf(prefs.temperature) }
    var maxTokens   by remember { mutableIntStateOf(prefs.maxTokens) }

    // SAF launchers.
    val kgraphPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = vm.importKgraph(it)
            if (path != null) {
                kgraphPath = path
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = vm.importModel(it)
            if (path != null) {
                modelPath = path
            }
        }
    }

    fun save() {
        prefs.remoteBaseUrl   = remoteUrl.trim()
        prefs.remoteModel     = remoteModel.trim()
        prefs.remoteApiKey    = apiKey.trim()
        prefs.maxToolRounds   = maxRounds
        prefs.temperature     = temperature
        prefs.maxTokens       = maxTokens
        vm.onSettingsChanged()
        onBack()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { save() },
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

            // ── Remote endpoint ───────────────────────────────────────────────
            SectionHeader(icon = Icons.Default.Cloud, title = "Remote Endpoint")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("http://localhost:11434") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remoteModel,
                        onValueChange = { remoteModel = it },
                        label = { Text("Model ID") },
                        placeholder = { Text("gpt-4o-mini") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-... (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            }

            // ── Local files ───────────────────────────────────────────────────
            SectionHeader(icon = Icons.Default.Memory, title = "Local Files")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = settingsCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    FilePickerRow(
                        label = "Knowledge Graph (.kgraph)",
                        path = kgraphPath,
                        onPick = { kgraphPicker.launch("*/*") }
                    )
                    Spacer(Modifier.height(12.dp))
                    FilePickerRow(
                        label = "Model File (.gguf / .sdz)",
                        path = modelPath,
                        onPick = { modelPicker.launch("*/*") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Local inference requires libsdx_llm.so (see README for build instructions).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
            OutlinedButton(onClick = onPick) {
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
