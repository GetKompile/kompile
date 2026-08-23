package ai.kompile.chat.local.android.model

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable app/native contract for GGUF preparation. A profile is immutable for the duration of an
 * import so resume and cache keys cannot silently change when UI defaults change.
 */
enum class WeightOptimization(
    val label: String,
    val description: String,
    val conversionMode: String,
    val requantizeType: String = "NONE",
) {
    Q4_K(
        "4-bit Q4_K",
        "Recommended mobile size. Re-quantizes dense/BF16 input to Q4_K, then retains packed weights.",
        "RUNTIME_QUANTIZED_MATMUL",
        "Q4_K",
    ),
    Q8_0(
        "8-bit Q8_0",
        "Higher fidelity and larger memory use. Re-quantizes input to packed Q8_0.",
        "RUNTIME_QUANTIZED_INT8",
        "Q8_0",
    ),
    Q6_K(
        "6-bit Q6_K",
        "Middle ground between Q4_K and Q8_0; retained as packed runtime weights.",
        "RUNTIME_QUANTIZED_MATMUL",
        "Q6_K",
    ),
    EXISTING_PACKED_AUTO(
        "Keep compatible packed weights",
        "Retains existing Q4_K/Q6_K/Q8_0 tensors without re-quantizing the source.",
        "RUNTIME_QUANTIZED_MATMUL",
    ),
    EXISTING_PACKED_INT4(
        "Require existing 4-bit",
        "Requires compatible Q4_K/Q6_K source tensors and rejects incompatible linear weights.",
        "RUNTIME_QUANTIZED_INT4",
    ),
    EXISTING_PACKED_INT8(
        "Require existing 8-bit",
        "Requires Q8_0 source tensors and rejects incompatible linear weights.",
        "RUNTIME_QUANTIZED_INT8",
    ),
    FP16(
        "FP16",
        "Dense half precision. Broadly compatible, but about twice the weight memory of Q8.",
        "DEQUANTIZE_TO_FLOAT16",
    ),
    BF16(
        "BF16",
        "Dense bfloat16 storage. Preserves the current BF16 path for comparison.",
        "DEQUANTIZE_TO_BFLOAT16",
    ),
    FP32(
        "FP32",
        "Full precision and highest memory use; intended for diagnostics only on mobile.",
        "DEQUANTIZE_TO_FLOAT32",
    ),
    FP8_E4M3(
        "FP8 E4M3",
        "Dense FP8 E4M3 storage when the selected backend supports it.",
        "DEQUANTIZE_TO_FLOAT8_E4M3",
    ),
    FP8_E5M2(
        "FP8 E5M2",
        "Dense FP8 E5M2 storage when the selected backend supports it.",
        "DEQUANTIZE_TO_FLOAT8_E5M2",
    ),
    PRESERVE(
        "Preserve source quantization",
        "Preserves quantization metadata for later reconstruction.",
        "PRESERVE_QUANTIZATION",
    ),
    HYBRID(
        "Hybrid",
        "Dequantizes selected layers while preserving eligible quantized tensors.",
        "HYBRID",
    ),
}

enum class KvCacheOptimization(val label: String, val wireValue: Int) {
    OFF("Float cache", 0),
    INT8("INT8 cache", 1),
    FP8_E4M3("FP8 E4M3 cache", 2),
    FP8_E5M2("FP8 E5M2 cache", 3),
    INT4("INT4 cache", 4),
}

enum class ModelDiagnosticMode(
    val label: String,
    val wireValue: String,
    val dspCategories: String?,
    val dspLevel: String?,
    val nativeOpSanity: Boolean,
    val capturesDspTrace: Boolean,
    val capturesSmokeTrace: Boolean,
) {
    OFF("Off", "off", null, null, false, false, false),
    STANDARD("Standard", "standard", null, null, false, false, true),
    BACKEND_AUDIT(
        "Backend audit",
        "backend_audit",
        "BACKEND,COMPILE,EXECUTE,SEGMENT,EMULATED_REPLAY,GRAPH_REPLAY",
        "detailed",
        false,
        true,
        true,
    ),
    VERBOSE("Verbose", "verbose", "COMPILE,EXECUTE,TIMING,MEMORY", "detailed", false, false, true),
    OP_SANITY("Op sanity", "op_sanity", "VERIFY", "full", true, true, true),
    DSP_DIAGNOSTICS("DSP diagnostics", "dsp", "ALL", "full", false, true, true),
}

/** The persisted Settings selection is authoritative for every build and accelerator flavor. */
internal fun effectiveDiagnosticModeForRuntime(requested: ModelDiagnosticMode): ModelDiagnosticMode =
    requested

internal const val MODEL_PREPARATION_GRAPH_IMPORT_ABI = "ggml-runtime-packed-gdn-v7"
internal const val MODEL_PREPARATION_EMBEDDING_DATA_TYPE = "HALF"
internal const val MODEL_PREPARATION_LOGITS_MODE = "LAST_POSITION_ONLY"

data class ModelPreparationOptions(
    val weightOptimization: WeightOptimization = WeightOptimization.Q4_K,
    val kvCacheOptimization: KvCacheOptimization = KvCacheOptimization.INT8,
    val tensorBatchSize: Int = 4,
    val useMemoryMapping: Boolean = true,
    val diagnosticMode: ModelDiagnosticMode = ModelDiagnosticMode.OFF,
) {
    init {
        require(tensorBatchSize in 1..256) { "tensorBatchSize must be between 1 and 256" }
    }

    fun conversionProfileJson(): String = buildString {
        append('{')
        append("\"graphImportAbi\":").append(jsonString(MODEL_PREPARATION_GRAPH_IMPORT_ABI))
        append(",\"conversionMode\":").append(jsonString(weightOptimization.conversionMode))
        append(",\"requantizeType\":").append(jsonString(weightOptimization.requantizeType))
        append(",\"embeddingDataType\":").append(jsonString(MODEL_PREPARATION_EMBEDDING_DATA_TYPE))
        append(",\"logitsMode\":").append(jsonString(MODEL_PREPARATION_LOGITS_MODE))
        append(",\"kvQuantFormat\":").append(kvCacheOptimization.wireValue)
        append(",\"tensorBatchSize\":").append(tensorBatchSize)
        append(",\"useMemoryMapping\":").append(useMemoryMapping)
        append('}')
    }

    fun profileSha256(): String = sha256(conversionProfileJson())

    fun optionsJson(verifiedSourceSha256: String?, verifiedSourceBytes: Long?): String {
        require((verifiedSourceSha256 == null) == (verifiedSourceBytes == null)) {
            "Verified source SHA-256 and byte count must be supplied together"
        }
        val normalizedSha256 = verifiedSourceSha256?.also {
            require(it.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Verified source SHA-256 must be 64 hexadecimal characters"
            }
            require(verifiedSourceBytes != null && verifiedSourceBytes > 0L) {
                "Verified source byte count must be positive"
            }
        }?.lowercase(Locale.ROOT)
        return buildString {
            append(conversionProfileJson().dropLast(1))
            append(",\"diagnosticMode\":").append(jsonString(diagnosticMode.wireValue))
            if (normalizedSha256 != null) {
                append(",\"verifiedSourceSha256\":").append(jsonString(normalizedSha256))
                append(",\"verifiedSourceBytes\":").append(verifiedSourceBytes)
            }
            append('}')
        }
    }

    companion object {
        private fun jsonString(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

        fun fromWire(
            weightOptimization: String?,
            kvCacheOptimization: String?,
            tensorBatchSize: Int,
            useMemoryMapping: Boolean,
            diagnosticMode: String?,
        ): ModelPreparationOptions = ModelPreparationOptions(
            weightOptimization = enumValueOrDefault(weightOptimization, WeightOptimization.Q4_K),
            kvCacheOptimization = enumValueOrDefault(kvCacheOptimization, KvCacheOptimization.INT8),
            tensorBatchSize = tensorBatchSize.coerceIn(1, 256),
            useMemoryMapping = useMemoryMapping,
            diagnosticMode = enumValueOrDefault(diagnosticMode, ModelDiagnosticMode.OFF),
        )

        private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
            runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return buildString(64) {
                digest.forEach { append("%02x".format(Locale.ROOT, it.toInt() and 0xff)) }
            }
        }
    }
}
