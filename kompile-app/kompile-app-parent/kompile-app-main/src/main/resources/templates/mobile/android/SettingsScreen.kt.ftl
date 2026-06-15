package {{packageName}}.ui.screens

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import {{packageName}}.config.AppConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var selectedMode by remember { mutableStateOf(AppConfig.inferenceMode) }
    var apiKey by remember { mutableStateOf(if (AppConfig.apiKey == "{{apiKeyPlaceholder}}") "" else AppConfig.apiKey) }
    var temperature by remember { mutableFloatStateOf(AppConfig.defaultTemperature) }
    var maxTokens by remember { mutableIntStateOf(AppConfig.maxGenerationTokens) }
    var ragTopK by remember { mutableIntStateOf(AppConfig.ragTopK) }
    var chunkSize by remember { mutableIntStateOf(AppConfig.chunkSize) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Inference Mode Section
            SectionHeader(
                icon = Icons.Default.Settings,
                title = "Inference Mode"
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InferenceModeOption(
                        label = "Local",
                        description = "Run model on device using SDX runtime",
                        icon = Icons.Default.Memory,
                        selected = selectedMode == "local",
                        onClick = { selectedMode = "local" }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    InferenceModeOption(
                        label = "Remote",
                        description = "Use cloud-based LLM API (OpenAI-compatible)",
                        icon = Icons.Default.Cloud,
                        selected = selectedMode == "remote",
                        onClick = { selectedMode = "remote" }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    InferenceModeOption(
                        label = "Hybrid",
                        description = "Local embeddings + remote generation",
                        icon = Icons.Default.Sync,
                        selected = selectedMode == "hybrid",
                        onClick = { selectedMode = "hybrid" }
                    )
                }
            }

            // API Key Section (visible for remote/hybrid)
            if (selectedMode == "remote" || selectedMode == "hybrid") {
                SectionHeader(
                    icon = Icons.Default.Cloud,
                    title = "API Configuration"
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API Key") },
                            placeholder = { Text("sk-...") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
            }

            // Model Info Section
            SectionHeader(
                icon = Icons.Default.Info,
                title = "Model Information"
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("Model ID", AppConfig.modelId)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("Model File", AppConfig.modelFileName)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("SDK Version", AppConfig.sdkVersion)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("Embedding Dimension", AppConfig.embeddingDimension.toString())
                }
            }

            // Generation Settings
            SectionHeader(
                icon = Icons.Default.Settings,
                title = "Generation Settings"
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Temperature slider
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
                    Text(
                        text = "Lower values produce more focused output, higher values increase creativity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Max tokens slider
                    Text(
                        text = "Max Tokens: $maxTokens",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { maxTokens = it.toInt() },
                        valueRange = 64f..2048f,
                        steps = 30
                    )
                }
            }

            // RAG Settings
            SectionHeader(
                icon = Icons.Default.Settings,
                title = "RAG Configuration"
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Top-K slider
                    Text(
                        text = "Retrieval Top-K: $ragTopK",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = ragTopK.toFloat(),
                        onValueChange = { ragTopK = it.toInt() },
                        valueRange = 1f..20f,
                        steps = 18
                    )
                    Text(
                        text = "Number of document chunks to retrieve for context",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Chunk size slider
                    Text(
                        text = "Chunk Size: $chunkSize tokens",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = chunkSize.toFloat(),
                        onValueChange = { chunkSize = it.toInt() },
                        valueRange = 128f..2048f,
                        steps = 14
                    )
                    Text(
                        text = "Size of document chunks for indexing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun InferenceModeOption(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
