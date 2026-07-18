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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.kompile.chat.local.android.viewmodel.ChatViewModel
import ai.kompile.chat.local.android.viewmodel.GraphUiState
import ai.kompile.chat.local.android.viewmodel.ModelUiState
import ai.kompile.chat.local.android.viewmodel.ProjectImportOutcome
import ai.kompile.chat.local.android.viewmodel.ToolRoundUi
import ai.kompile.chat.local.android.viewmodel.UiMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val messages by vm.messages.collectAsState()
    val thinking  by vm.thinking.collectAsState()
    val error      by vm.error.collectAsState()
    val route      by vm.activeRoute.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val graphState by vm.graphState.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var importingProject by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                importingProject = true
                importError = null
                try {
                    when (val outcome = vm.importProjectAndActivate(it)) {
                        is ProjectImportOutcome.Active -> Unit
                        is ProjectImportOutcome.Failed -> importError = outcome.displayMessage
                    }
                } finally {
                    importingProject = false
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

    // Show error in a snackbar.
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Kompile Chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                        // Route badge names the exact device provider.
                        RouteBadge(route = route)
                    }
                },
                navigationIcon = {},
                actions = {
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
            // Thinking indicator.
            if (thinking) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Message list.
            if (messages.isEmpty() && !thinking) {
                StartupStatePanel(
                    modelState = modelState,
                    graphState = graphState,
                    importingProject = importingProject,
                    importError = importError,
                    onImportProject = { projectPicker.launch("*/*") },
                    onPrepareProject = {
                        val result = vm.openModelStaging()
                        importError = result.exceptionOrNull()?.message
                        if (result.isFailure && vm.prefs.modelStagingUrl.isBlank()) {
                            onOpenSettings()
                        }
                    },
                    onOpenSettings = onOpenSettings,
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

            // Input bar.
            ChatInputBar(
                enabled = !thinking && engineReady,
                generating = thinking,
                onSend = { text -> vm.sendMessage(text) },
                onCancel = { vm.cancelGeneration() }
            )
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun RouteBadge(route: String) {
    val (label, color) = when (route) {
        "LOCAL_VULKAN"    -> "VULKAN" to MaterialTheme.colorScheme.tertiary
        "LOCAL_HEXAGON"          -> "HEXAGON" to MaterialTheme.colorScheme.tertiary
        "LOCAL_TENSOR_G3_NNAPI"  -> "TENSOR G3" to MaterialTheme.colorScheme.tertiary
        "LOCAL_TENSOR_G5"        -> "TENSOR G5" to MaterialTheme.colorScheme.tertiary
        else              -> "NO MODEL" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
internal fun StartupStatePanel(
    modelState: ModelUiState,
    graphState: GraphUiState,
    importingProject: Boolean,
    importError: String?,
    onImportProject: () -> Unit,
    onPrepareProject: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            val icon = if (modelState is ModelUiState.Ready) {
                Icons.Default.AutoAwesome
            } else {
                Icons.Default.FolderOpen
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(48.dp)
                    .padding(bottom = 12.dp)
            )

            when {
                importingProject -> {
                    Text("Installing offline project…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Verifying the target model, graph, sources, and native runtimes. Large projects may take a while.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                modelState is ModelUiState.Missing -> {
                    Text("Import an offline project", style = MaterialTheme.typography.titleMedium)
                    Text(
                        importError ?: "Choose a .kproject prepared for this APK. It contains the target SameDiff model, AOT graph, Markdown sources, and one verified revision.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (importError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImportProject) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import .kproject")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPrepareProject) {
                        Text("Prepare from GGUF / Hugging Face")
                    }
                    Text(
                        "Preparation opens a configured Kompile staging server in your browser; inference stays offline.",
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
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImportProject) { Text("Choose another .kproject") }
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
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel generation",
                        tint = MaterialTheme.colorScheme.error
                    )
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
