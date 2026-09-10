package ai.kompile.chat.local.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pins the wire contract between the Android preparation vocabulary and the desktop
 * `SdxGgufModelPreparer` enums, and the `PreparationStage` IPC ordinal contract.
 *
 * These contracts are stringly/ordinally typed across a repo and process boundary:
 * the desktop side parses the strings this app emits ("RUNTIME_QUANTIZED_MATMUL",
 * "Q4_K", …) with strict `valueOf`/whitelist parsing, and the importer service sends
 * `PreparationStage.ordinal` over the Binder bundle where `values().getOrNull(ordinal)`
 * reconstructs it. Changing either side silently breaks device imports — these tests
 * turn that silent break into a build failure on whichever side changes first.
 */
class SdxWireContractTest {

    private fun locateDesktopPreparer(): File? {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            val direct = File(
                cursor,
                "nd4j/sdx-aot/src/main/java/org/eclipse/deeplearning4j/sdx/aot/" +
                    "SdxGgufModelPreparer.java",
            )
            if (direct.isFile) return direct
            val sibling = File(
                cursor,
                "deeplearning4j/nd4j/sdx-aot/src/main/java/org/eclipse/deeplearning4j/" +
                    "sdx/aot/SdxGgufModelPreparer.java",
            )
            if (sibling.isFile) return sibling
            cursor = cursor.parentFile
        }
        return null
    }

    @Test
    fun graphImportAbiMatchesTheDesktopProducer() {
        // Desktop: SdxGgufModelPreparer.GRAPH_IMPORT_ABI
        assertEquals(
            "ggml-fixed-plan-rolling-context-q4-linears-v9",
            MODEL_PREPARATION_GRAPH_IMPORT_ABI,
        )
    }

    @Test
    fun conversionModesAreSpelledExactlyAsTheDesktopEnumsParse() {
        // Desktop: ConversionOptions.QuantizationMode.valueOf(mode)
        val expected = setOf(
            "RUNTIME_QUANTIZED_MATMUL",
            "RUNTIME_QUANTIZED_INT8",
            "RUNTIME_QUANTIZED_INT4",
            "DEQUANTIZE_TO_FLOAT16",
            "DEQUANTIZE_TO_BFLOAT16",
            "DEQUANTIZE_TO_FLOAT32",
            "DEQUANTIZE_TO_FLOAT8_E4M3",
            "DEQUANTIZE_TO_FLOAT8_E5M2",
            "PRESERVE_QUANTIZATION",
            "HYBRID",
        )
        assertEquals(expected, WeightOptimization.entries.map { it.conversionMode }.toSet())
    }

    @Test
    fun requantizeTypesAreWithinTheDesktopMobileWhitelist() {
        // Desktop SdxGgufModelPreparer rejects anything outside Q4_K/Q6_K/Q8_0; NONE means skip.
        val allowed = setOf("NONE", "Q4_K", "Q6_K", "Q8_0")
        val used = WeightOptimization.entries.map { it.requantizeType }.toSet()
        assertTrue(
            "requantizeTypes outside the desktop whitelist: ${(used - allowed)}",
            used.all { it in allowed },
        )
    }

    @Test
    fun kvQuantFormatWireValuesMatchTheDesktopImporterRange() {
        // Desktop: kvQuantFormat must be 0..4; nonzero selects KvCacheStrategy.QUANTIZED.
        assertEquals(listOf(0, 1, 2, 3, 4), KvCacheOptimization.entries.map { it.wireValue })
    }

    @Test
    fun preparationStageOrdinalsAreStableBecauseTheyRideTheBinderBundle() {
        // SdxModelPreparationProcess sends stage.ordinal; the client reconstructs with
        // values().getOrNull(ordinal). Inserting/reordering entries silently corrupts
        // in-flight progress; appending must stay at the end.
        assertEquals(
            listOf(
                "CONVERT_AND_CACHE_SDZ",
                "TARGET_CACHE_READY",
                "LOAD_ACCELERATOR",
                "MEMORY_PREFLIGHT",
                "MEMORY_PRESSURE_WARNING",
            ),
            PreparationStage.entries.map { it.name },
        )
    }

    @Test
    fun preparedResultV6FieldListCoversEveryConsumedKey() {
        // The v6 result keys the app consumes (SdxRawGgufChatSession parses these through
        // SdxRawGgufContract constants). The desktop producer test
        // SdxPreparedResultContractTest pins the same list from the producer side; any
        // rename must update both lists and both builds fail until they agree.
        val expected = setOf(
            "schema",
            "targetProfile",
            "cacheHit",
            "sourceSha256",
            "sourceBytes",
            "canonicalSdzLogicalSha256",
            "canonicalSdzLogicalBytes",
            "canonicalSdzPath",
            "canonicalSdzBytes",
            "modelPath",
            "tokenizerPath",
            "compileKey",
            "targetSoc",
            "contextLength",
            "maxPrefillLength",
            "executionProvider",
            "conversionProfileSha256",
            "diagnosticMode",
            "optimizedSourcePath",
            "optimizedSourceBytes",
            "importResourcesReleased",
        )
        val declared = SdxRawGgufContract::class.java.declaredFields
            .filter { it.name.endsWith("_FIELD") }
            .map { (it.get(null) as String) }
            .toSet()
        assertEquals(expected, declared)
    }

    @Test
    fun preparedSchemaMarkerIsV6() {
        // Desktop SdxGgufModelPreparer.PREPARED_SCHEMA
        assertEquals("sdx-prepared-text-model-v6", SdxRawGgufContract.PREPARED_SCHEMA)
    }

    @Test
    fun graphImportAbiAlsoMatchesTheAotSideSourceOfTruth() {
        // Guard the desktop constant too when this test runs in a checkout that has it.
        val desktop = locateDesktopPreparer()
        assumeTrue("Deeplearning4j sibling checkout is not present", desktop != null)
        val source = requireNotNull(desktop).readText()
        val declared = Regex("GRAPH_IMPORT_ABI\\s*=\\s*\"([^\"]+)\"")
            .find(source)?.groupValues?.get(1)
        assertEquals(MODEL_PREPARATION_GRAPH_IMPORT_ABI, declared)
    }
}
