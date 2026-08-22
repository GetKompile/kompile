package ai.kompile.chat.local.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.kompile.chat.local.android.model.KvCacheOptimization
import ai.kompile.chat.local.android.model.ModelDiagnosticMode
import ai.kompile.chat.local.android.model.ModelPreparationOptions
import ai.kompile.chat.local.android.model.WeightOptimization
import kotlin.math.roundToInt

@Composable
internal fun ModelOptimizationOptionsPane(
    options: ModelPreparationOptions,
    onOptionsChanged: (ModelPreparationOptions) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Preparation and optimization", style = MaterialTheme.typography.labelLarge)
        Text(
            "Q4_K is the mobile default. Every setting below is part of the conversion profile and cache key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModelOptionDropdown(
            label = "Weight optimization",
            selectedLabel = options.weightOptimization.label,
            enabled = enabled,
            items = WeightOptimization.entries.map { value -> value.label to value },
            onSelected = { onOptionsChanged(options.copy(weightOptimization = it)) },
        )
        Text(
            options.weightOptimization.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModelOptionDropdown(
            label = "KV cache",
            selectedLabel = options.kvCacheOptimization.label,
            enabled = enabled,
            items = KvCacheOptimization.entries.map { value -> value.label to value },
            onSelected = { onOptionsChanged(options.copy(kvCacheOptimization = it)) },
        )

        ModelOptionDropdown(
            label = "Logging",
            selectedLabel = options.diagnosticMode.label,
            enabled = enabled,
            items = ModelDiagnosticMode.entries.map { value -> value.label to value },
            onSelected = { onOptionsChanged(options.copy(diagnosticMode = it)) },
        )
        Text(
            when (options.diagnosticMode) {
                ModelDiagnosticMode.STANDARD ->
                    "Bounded preparation, activation, IPC, and smoke-decode logging."
                ModelDiagnosticMode.VERBOSE ->
                    "Adds DSP compile, execute, timing, and memory diagnostics."
                ModelDiagnosticMode.OP_SANITY ->
                    "Captures comparable per-op value fingerprints and finite-value statistics for the initial warmup and compiled passes."
                ModelDiagnosticMode.DSP_DIAGNOSTICS ->
                    "Captures all DSP categories at full detail under files/diagnostics/dsp."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "Tensor conversion batch: ${options.tensorBatchSize}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = options.tensorBatchSize.toFloat(),
            onValueChange = {
                onOptionsChanged(options.copy(tensorBatchSize = it.roundToInt().coerceIn(1, 32)))
            },
            enabled = enabled,
            valueRange = 1f..32f,
            steps = 30,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Memory-map model tensors")
                Text(
                    "Reduces copying and lets Android page immutable model data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = options.useMemoryMapping,
                onCheckedChange = {
                    onOptionsChanged(options.copy(useMemoryMapping = it))
                },
                enabled = enabled,
            )
        }

        Text(
            "Profile ${options.profileSha256().take(12)} · original GGUF/GGML is retained",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> ModelOptionDropdown(
    label: String,
    selectedLabel: String,
    enabled: Boolean,
    items: List<Pair<String, T>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$label: $selectedLabel")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { (itemLabel, value) ->
                DropdownMenuItem(
                    text = { Text(itemLabel) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}
