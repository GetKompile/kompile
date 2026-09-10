package ai.kompile.chat.local.android.model

import ai.kompile.chat.local.ChatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ModelPreparationMemoryPreflightTest {

    @Test
    fun clientRecordsMemoryWithoutWaitingOrRejectingBeforeImporterCacheLookup() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxModelPreparationProcess.kt"
        ).readText()
        val client = source.substring(source.indexOf("internal object SdxModelPreparationClient"))
        val prepare = client.substring(
            client.indexOf("    fun prepare("),
            client.indexOf("    private fun importerPidIfCurrentProcess")
        )
        val preflight = prepare.indexOf("modelPreparationMemoryPreflight(currentModelPreparationMemorySnapshot())")
        val bind = prepare.indexOf("SdxModelPreparationConnection.bind(")

        assertTrue("The product memory preflight is missing", preflight >= 0)
        assertTrue("The importer was started before memory was checked", bind > preflight)
        assertFalse("Product preflight must not block prepared-cache reuse", prepare.contains(".requireReady("))
        assertFalse("Product preflight must not wait on a fixed memory floor", prepare.contains("awaitModelPreparationMemoryPreflight("))
        assertTrue(prepare.contains("PreparationStage.MEMORY_PREFLIGHT"))
        assertTrue(prepare.contains("PreparationStage.MEMORY_PRESSURE_WARNING"))
    }

    @Test
    fun functionalDiagnosticRequiresPrecisionAndCannotEmitQualificationMarkers() {
        val source = File(
            "src/androidTest/java/ai/kompile/chat/local/android/model/TensorG3QualificationTest.kt"
        ).readText()
        val functional = source.substringAfter("fun functionalQwenDecodeUsesRequestedPrecision()")
            .substringBefore("private fun runDecode(")
        assertTrue(functional.contains("WeightOptimization.valueOf(requiredArgument(\"weight_optimization\"))"))
        assertTrue(functional.contains("strictQualification = false"))
        assertTrue(functional.contains("expectedCacheHit = null"))
        assertTrue(functional.contains("ModelPreparationOptions.fromWire("))
        assertTrue(functional.contains("weightOptimization = weightOptimization.name"))
        assertTrue(functional.contains("kv_cache_optimization"))
        assertTrue(functional.contains("toBooleanStrictOrNull()"))
        assertTrue(functional.contains("FUNCTIONAL_DECODE_PASS"))
        assertFalse(functional.contains("QUALIFICATION_PASS"))
        assertFalse(functional.contains("COLD_DECODE_PASS"))
        assertFalse(functional.contains("WARM_DECODE_PASS"))

        val decode = source.substringAfter("private fun runDecode(").substringBefore("private fun recordMemory(")
        assertTrue(decode.contains("if (strictQualification) {\n            preflightMemoryFloor()\n        } else {\n            recordMemory(\"FUNCTIONAL_PREFLIGHT\")"))
        assertTrue(decode.contains("preparationOptions = preparationOptions"))
        assertTrue("Functional inputs must not overwrite qualification inputs", decode.contains("if (strictQualification) \"qualification\" else \"functional\""))
        assertTrue(decode.contains("assertEquals(preparationOptions.profileSha256(), info.conversionProfileSha256)"))
        assertTrue(decode.contains("weightOptimization = WeightOptimization.Q4_K"))
        assertTrue(source.contains("optionalLongArgument(\"min_free_swap_bytes\""))
        assertTrue(source.contains("TENSOR_G3_QUALIFICATION_MIN_SWAP_FREE_BYTES, 0L..Long.MAX_VALUE"))
    }

    @Test
    fun requestedBf16ProfileIsNotTheDefaultPackedQ4Profile() {
        val bf16 = ModelPreparationOptions(weightOptimization = WeightOptimization.BF16)
        assertEquals("DEQUANTIZE_TO_BFLOAT16", bf16.weightOptimization.conversionMode)
        assertEquals("NONE", bf16.weightOptimization.requantizeType)
        assertTrue(bf16.conversionProfileJson().contains("\"conversionMode\":\"DEQUANTIZE_TO_BFLOAT16\""))
        assertFalse(bf16.profileSha256() == ModelPreparationOptions().profileSha256())
    }

    @Test
    fun lowSwapSnapshotDoesNotClaimModelCannotFitButStrictQualificationStillRejectsIt() {
        // Actual proc018 Pixel 8a reading: RAM passed the strict floor; only free swap failed it.
        val snapshot = ModelPreparationMemorySnapshot(2_591_330_304L, 793_583_616L)
        val preflight = modelPreparationMemoryPreflight(
            snapshot,
            minimumAvailableBytes = TENSOR_G3_QUALIFICATION_MIN_AVAILABLE_BYTES,
            minimumSwapFreeBytes = TENSOR_G3_QUALIFICATION_MIN_SWAP_FREE_BYTES,
        )
        assertTrue(snapshot.readable)
        assertTrue(snapshot.availableBytes >= preflight.minimumAvailableBytes)
        assertFalse(preflight.thresholdMet)
        assertTrue(preflight.userMessage("model.gguf").contains("not a model-fit estimate"))
        assertFalse(preflight.userMessage("model.gguf").contains("Not enough free memory"))
        assertThrowsChatException { preflight.requireReady("model.gguf") }
    }

    @Test
    fun rechecksUntilAndroidMemorySettlesThenProceeds() {
        val snapshots = ArrayDeque(
            listOf(
                ModelPreparationMemorySnapshot(2_000_000_000L, 500_000_000L),
                ModelPreparationMemorySnapshot(3_000_000_000L, 1_500_000_000L),
                ModelPreparationMemorySnapshot(3_600_000_000L, 2_100_000_000L),
            )
        )
        val waiting = mutableListOf<ModelPreparationMemoryPreflight>()
        val sleeps = mutableListOf<Long>()
        var now = 0L

        val result = awaitModelPreparationMemoryPreflight(
            timeoutMillis = 90_000L,
            recheckIntervalMillis = 5_000L,
            readSnapshot = { snapshots.removeFirst() },
            elapsedRealtime = { now },
            sleep = { millis ->
                sleeps += millis
                now += millis
            },
            onWaiting = waiting::add,
        )

        assertTrue(result.thresholdMet)
        assertEquals(2, waiting.size)
        assertEquals(listOf(5_000L, 5_000L), sleeps)
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun returnsLatestObservationOnlyAfterBoundedWaitExpires() {
        val observed = mutableListOf<ModelPreparationMemoryPreflight>()
        var reads = 0
        var now = 0L

        val result = awaitModelPreparationMemoryPreflight(
            timeoutMillis = 12_000L,
            recheckIntervalMillis = 5_000L,
            readSnapshot = {
                reads++
                ModelPreparationMemorySnapshot(
                    availableBytes = 1_000_000_000L + reads,
                    swapFreeBytes = 100_000_000L + reads,
                )
            },
            elapsedRealtime = { now },
            sleep = { millis -> now += millis },
            onWaiting = observed::add,
        )

        assertFalse(result.thresholdMet)
        assertEquals(4, reads)
        assertEquals(3, observed.size)
        assertEquals(12_000L, now)
        assertEquals(1_000_000_004L, result.snapshot.availableBytes)
    }

    @Test
    fun parsesAndroidMemInfoKilobytesWithoutUsingRuntimeSpecificApis() {
        val snapshot = parseModelPreparationMemorySnapshot(
            """
            MemTotal:        7754800 kB
            MemAvailable:    3560000 kB
            SwapTotal:       4194304 kB
            SwapFree:        2210000 kB
            """.trimIndent()
        )

        assertEquals(3_645_440_000L, snapshot.availableBytes)
        assertEquals(2_263_040_000L, snapshot.swapFreeBytes)
        assertTrue(snapshot.readable)
        assertTrue(modelPreparationMemoryPreflight(snapshot).thresholdMet)
    }

    @Test
    fun historicalReferenceIsReportedWithoutClaimingItIsAModelRequirement() {
        val preflight = modelPreparationMemoryPreflight(
            ModelPreparationMemorySnapshot(
                availableBytes = 3_253_284_864L,
                swapFreeBytes = 2_871_123_968L,
            )
        )

        assertFalse(preflight.thresholdMet)
        val message = preflight.userMessage("model.gguf")
        assertTrue(message.contains("3253 MB available"))
        assertTrue(message.contains("3500 MB available"))
        assertTrue(message.contains("not a model-fit estimate"))
        assertFalse(message.contains("restart the device"))
        assertFalse(message.contains("tap Retry"))
        assertTrue(message.contains("will not be downloaded again"))
        assertThrowsChatException { preflight.requireReady("model.gguf") }
    }

    @Test
    fun explicitPolicyAssertionRejectsSwapBelowItsConfiguredFloor() {
        val preflight = modelPreparationMemoryPreflight(
            ModelPreparationMemorySnapshot(
                availableBytes = 3_800_000_000L,
                swapFreeBytes = 1_500_000_000L,
            )
        )

        assertFalse(preflight.thresholdMet)
        assertTrue(preflight.statusFields().contains("minSwapFreeBytes=2000000000"))
        assertThrowsChatException { preflight.requireReady("model.gguf") }
    }

    @Test
    fun unreadableSnapshotIsHonestAndExplicitPolicyAssertionFailsClosed() {
        val snapshot = parseModelPreparationMemorySnapshot("MemTotal: 7754800 kB")
        val preflight = modelPreparationMemoryPreflight(snapshot)

        assertFalse(snapshot.readable)
        assertFalse(preflight.thresholdMet)
        assertTrue(preflight.userMessage("model.gguf").contains("could not verify free memory"))
        assertTrue(preflight.userMessage("model.gguf").contains("cannot determine whether the model will fit"))
        assertFalse(preflight.userMessage("model.gguf").contains("restart"))
        assertThrowsChatException { preflight.requireReady("model.gguf") }
    }

    @Test
    fun invalidUnitsOverflowAndMissingValuesAreNotReadable() {
        for (value in listOf("123 MB", "123", "-1 kB", "9223372036854775807 kB", "abc kB")) {
            assertFalse(parseModelPreparationMemorySnapshot("MemAvailable: $value\nSwapFree: 0 kB").readable)
        }
        assertEquals(1024L, parseModelPreparationMemorySnapshot("MemAvailable:\t1\tkB\nSwapFree: 0 kB").availableBytes)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            modelPreparationMemoryPreflight(ModelPreparationMemorySnapshot(1, 0), -1, 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            modelPreparationMemoryPreflight(ModelPreparationMemorySnapshot(1, 0), 0, -1)
        }
    }

    private fun assertThrowsChatException(operation: () -> Unit) {
        try {
            operation()
            fail("Expected ChatException")
        } catch (_: ChatException) {
            // Expected.
        }
    }
}
