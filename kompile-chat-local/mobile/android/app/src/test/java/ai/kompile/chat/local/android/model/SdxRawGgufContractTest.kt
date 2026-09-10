package ai.kompile.chat.local.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdxRawGgufContractTest {

    @Test
    fun cachedCanonicalSdzBypassesRawModelPreparation() {
        assertTrue(SdxGgufModelImporter.supports("retained.gguf"))
        assertTrue(SdxGgufModelImporter.supports("retained.ggml"))
        assertFalse(SdxGgufModelImporter.supports("cache/v1/sources/abc/model.sdz"))

        val viewModel = File(
            "src/main/java/ai/kompile/chat/local/android/viewmodel/ChatViewModel.kt"
        ).readText()
        assertTrue(viewModel.contains("activateCachedOptimizedModel"))
        assertTrue(viewModel.contains("modelPath = cached.canonicalSdzPath"))
    }

    @Test
    fun runtimeDiagnosticsHonorThePersistedSettingsSelection() {
        ModelDiagnosticMode.entries.forEach { selected ->
            assertEquals(selected, effectiveDiagnosticModeForRuntime(selected))
        }
        assertEquals(ModelDiagnosticMode.OFF, ModelPreparationOptions().diagnosticMode)
        assertEquals(
            ModelDiagnosticMode.OFF,
            ModelPreparationOptions.fromWire(null, null, 4, true, null).diagnosticMode,
        )
        assertNull(ModelDiagnosticMode.OFF.dspCategories)
        assertNull(ModelDiagnosticMode.OFF.dspLevel)
        assertFalse(ModelDiagnosticMode.OFF.nativeOpSanity)
        assertFalse(ModelDiagnosticMode.OFF.capturesDspTrace)
        assertFalse(ModelDiagnosticMode.OFF.capturesSmokeTrace)
        assertTrue(ModelDiagnosticMode.STANDARD.capturesSmokeTrace)
        assertEquals(
            "BACKEND,COMPILE,EXECUTE,SEGMENT,EMULATED_REPLAY,GRAPH_REPLAY",
            ModelDiagnosticMode.BACKEND_AUDIT.dspCategories,
        )
        assertEquals("detailed", ModelDiagnosticMode.BACKEND_AUDIT.dspLevel)
        assertTrue(ModelDiagnosticMode.BACKEND_AUDIT.capturesDspTrace)
        assertFalse(ModelDiagnosticMode.BACKEND_AUDIT.nativeOpSanity)
        assertEquals("VERIFY", ModelDiagnosticMode.OP_SANITY.dspCategories)
        assertEquals("full", ModelDiagnosticMode.OP_SANITY.dspLevel)
        assertTrue(ModelDiagnosticMode.OP_SANITY.nativeOpSanity)
        assertTrue(ModelDiagnosticMode.OP_SANITY.capturesDspTrace)
        assertFalse(ModelDiagnosticMode.DSP_DIAGNOSTICS.nativeOpSanity)
        ModelDiagnosticMode.entries.forEach { selected ->
            assertEquals(
                selected,
                ModelPreparationOptions.fromWire(
                    null, null, 4, true, selected.name
                ).diagnosticMode,
            )
            assertEquals(
                selected,
                ModelPreparationOptions.fromWire(
                    null, null, 4, true, selected.wireValue
                ).diagnosticMode,
            )
        }
        assertEquals(
            ModelDiagnosticMode.DSP_DIAGNOSTICS,
            ModelPreparationOptions.fromWire(null, null, 4, true, "DSP").diagnosticMode,
        )
        val restored = ModelPreparationOptions.fromWire(null, null, 4, true, "OP_SANITY")
        assertEquals(ModelDiagnosticMode.OP_SANITY, restored.diagnosticMode)
        assertTrue(restored.optionsJson(null, null).contains("\"diagnosticMode\":\"op_sanity\""))
    }

    @Test
    fun preparedProofSchemaSeparatesRawCanonicalAndOptimizationIdentity() {
        assertEquals("sdx-prepared-text-model-v6", SdxRawGgufContract.PREPARED_SCHEMA)
        assertEquals("sourceSha256", SdxRawGgufContract.SOURCE_SHA256_FIELD)
        assertEquals("sourceBytes", SdxRawGgufContract.SOURCE_BYTES_FIELD)
        assertEquals(
            "canonicalSdzLogicalSha256",
            SdxRawGgufContract.CANONICAL_SDZ_LOGICAL_SHA256_FIELD
        )
        assertEquals(
            "canonicalSdzLogicalBytes",
            SdxRawGgufContract.CANONICAL_SDZ_LOGICAL_BYTES_FIELD
        )
        assertEquals("canonicalSdzBytes", SdxRawGgufContract.CANONICAL_SDZ_BYTES_FIELD)
        assertEquals("conversionProfileSha256", SdxRawGgufContract.CONVERSION_PROFILE_SHA256_FIELD)
        assertEquals("diagnosticMode", SdxRawGgufContract.DIAGNOSTIC_MODE_FIELD)
        assertEquals("optimizedSourcePath", SdxRawGgufContract.OPTIMIZED_SOURCE_PATH_FIELD)
        assertEquals("optimizedSourceBytes", SdxRawGgufContract.OPTIMIZED_SOURCE_BYTES_FIELD)
    }

    @Test
    fun verifiedPreparationOptionsCarryProfileAndExactDownloaderAttestation() {
        val sha256 = "a".repeat(64)
        val options = ModelPreparationOptions(
            weightOptimization = WeightOptimization.Q8_0,
            kvCacheOptimization = KvCacheOptimization.INT4,
            tensorBatchSize = 12,
            useMemoryMapping = false,
            diagnosticMode = ModelDiagnosticMode.DSP_DIAGNOSTICS,
        )

        assertEquals(
            "{\"graphImportAbi\":\"ggml-fixed-plan-rolling-context-q4-linears-v9\"," +
                "\"conversionMode\":\"RUNTIME_QUANTIZED_INT8\",\"requantizeType\":\"Q8_0\"," +
                "\"embeddingDataType\":\"HALF\",\"logitsMode\":\"LAST_POSITION_ONLY\"," +
                "\"kvQuantFormat\":4,\"tensorBatchSize\":12,\"useMemoryMapping\":false," +
                "\"diagnosticMode\":\"dsp\",\"verifiedSourceSha256\":\"$sha256\"," +
                "\"verifiedSourceBytes\":987654321}",
            SdxRawGgufContract.preparationOptionsJson(
                sha256.uppercase(),
                987_654_321L,
                options,
            )
        )
        assertEquals(
            "{\"graphImportAbi\":\"ggml-fixed-plan-rolling-context-q4-linears-v9\"," +
                "\"conversionMode\":\"RUNTIME_QUANTIZED_INT8\",\"requantizeType\":\"Q8_0\"," +
                "\"embeddingDataType\":\"HALF\",\"logitsMode\":\"LAST_POSITION_ONLY\"," +
                "\"kvQuantFormat\":4,\"tensorBatchSize\":12,\"useMemoryMapping\":false," +
                "\"diagnosticMode\":\"dsp\"}",
            SdxRawGgufContract.preparationOptionsJson(null, null, options)
        )
    }

    @Test
    fun conversionProfileHashChangesWithExecutionOptionsButNotLogging() {
        val baseline = ModelPreparationOptions()
        assertEquals(64, baseline.profileSha256().length)
        assertEquals(true, baseline.profileSha256().matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            baseline.profileSha256(),
            baseline.copy(diagnosticMode = ModelDiagnosticMode.DSP_DIAGNOSTICS).profileSha256(),
        )
        assertEquals(
            baseline.profileSha256(),
            baseline.copy(diagnosticMode = ModelDiagnosticMode.OP_SANITY).profileSha256(),
        )
        assertNotEquals(
            baseline.profileSha256(),
            baseline.copy(weightOptimization = WeightOptimization.Q8_0).profileSha256(),
        )
        assertNotEquals(
            baseline.profileSha256(),
            baseline.copy(kvCacheOptimization = KvCacheOptimization.OFF).profileSha256(),
        )
    }

    @Test
    fun preparationOptionsRejectPartialOrInvalidAttestation() {
        val options = ModelPreparationOptions()
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson("bad-sha", 1L, options)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson("a".repeat(64), null, options)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson(null, 1L, options)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson("a".repeat(64), 0L, options)
        }
    }
}
