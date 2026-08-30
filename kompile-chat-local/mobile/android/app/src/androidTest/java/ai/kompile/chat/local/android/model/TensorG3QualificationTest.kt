package ai.kompile.chat.local.android.model

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class TensorG3QualificationTest {

    private val instrumentation: Instrumentation =
        InstrumentationRegistry.getInstrumentation()
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext

    @Test
    fun coldQwenDecodeUsesExactPackagedRuntime() {
        runDecode(expectedCacheHit = false, passMarker = "COLD_DECODE_PASS")
    }

    @Test
    fun warmQwenDecodeUsesExactPackagedRuntime() {
        runDecode(expectedCacheHit = true, passMarker = "WARM_DECODE_PASS")
        sendStatus("QUALIFICATION_PASS")
    }

    private fun runDecode(expectedCacheHit: Boolean, passMarker: String) {
        val expectedBuildId = requiredArgument("expected_build_id")
        val expectedSourceSha256 = requiredSha256Argument("expected_source_runtime_aar_sha256")
        val expectedProvenanceSha256 = requiredSha256Argument("expected_runtime_provenance_sha256")
        val expectedAotProvenanceSha256 = requiredSha256Argument("expected_sdx_aot_provenance_sha256")
        val expectedModelSha256 = requiredSha256Argument("model_sha256")
        val expectedModelBytes = requiredArgument("model_bytes").toLongOrNull()
            ?.takeIf { it > 0L }
            ?: error("model_bytes must be a positive integer")

        assertEquals("google-tensor-g3-nnapi", BuildConfig.ACCELERATOR_PROVIDER)
        assertEquals("android-arm64-nnapi-accelerator", BuildConfig.SDX_TARGET_PROFILE)
        assertEquals(expectedBuildId, BuildConfig.APK_BUILD_ID)
        assertEquals(expectedSourceSha256, BuildConfig.SOURCE_RUNTIME_AAR_SHA256)
        assertEquals(expectedProvenanceSha256, BuildConfig.RUNTIME_PROVENANCE_SHA256)
        assertEquals(expectedAotProvenanceSha256, BuildConfig.SDX_AOT_PROVENANCE_SHA256)

        val model = File(context.filesDir, "qualification/model.gguf").canonicalFile
        assertTrue("qualification model is missing: $model", model.isFile)
        assertEquals(expectedModelBytes, model.length())
        assertEquals(expectedModelSha256, sha256(model))
        sendStatus("PROCESS_PID:${android.os.Process.myPid()}")
        sendStatus("INPUT_VERIFIED")
        preflightMemoryFloor(passMarker)

        val options = GenOptions.builder()
            .temperature(0.0)
            .maxTokens(16)
            .build()
        val messages = listOf(Message.user("Reply with exactly one short word."))

        AcceleratedChatModelAndroid(
            context = context,
            modelPath = model.absolutePath,
            temperature = 0.0f,
            maxTokens = 16,
            verifiedSourceSha256 = expectedModelSha256,
            verifiedSourceBytes = expectedModelBytes
        ).use { runtime ->
            val info = assertNotNull(runtime.preparationInfo).let { runtime.preparationInfo!! }
            if (expectedCacheHit) {
                assertTrue("warm import did not reuse the exact target cache", info.cacheHit)
            } else {
                assertFalse("cold import unexpectedly reused a target cache", info.cacheHit)
            }
            assertEquals(expectedModelSha256, info.sourceSha256)
            assertEquals(expectedModelBytes, info.sourceBytes)
            assertEquals("LOCAL_TENSOR_G3_NNAPI", runtime.routeName)
            val answer = runtime.generate(messages, options)
            assertTrue("decode returned no text", answer.isNotBlank())
            sendStatus(passMarker)
        }
    }

    private fun requiredArgument(name: String): String =
        arguments.getString(name)?.takeIf(String::isNotBlank)
            ?: error("missing instrumentation argument: $name")

    private fun requiredSha256Argument(name: String): String =
        requiredArgument(name).also {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "$name must be a lowercase SHA-256 digest"
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The import pipeline legitimately needs ~2.6GB free RAM + swap headroom to
     * convert and optimize the canonical SDZ. The Pixel 8a shares its zram with
     * every background app, so when the phone is under load the import dies in
     * low-memory termination even though the flow itself is correct. Gate the
     * run on a memory floor: wait up to 90s for other apps to settle, then fail
     * fast with an unambiguous DEVICE_UNDER_LOAD diagnostic instead of letting
     * the importer die mid-convert and misreporting it as a code regression.
     */
    private fun preflightMemoryFloor(passMarker: String) {
        val minAvailableBytes = 2_500_000_000L
        val minSwapFreeBytes = 1_500_000_000L
        val deadline = System.currentTimeMillis() + 90_000L
        var last = memorySnapshot()
        while (System.currentTimeMillis() < deadline) {
            if (last.availableBytes >= minAvailableBytes &&
                last.swapFreeBytes >= minSwapFreeBytes) {
                break
            }
            Thread.sleep(5_000)
            last = memorySnapshot()
        }
        sendStatus(
            "MEMORY_PREFLIGHT:" +
                "availableBytes=${last.availableBytes}" +
                ",swapFreeBytes=${last.swapFreeBytes}" +
                ",thresholdMet=${last.availableBytes >= minAvailableBytes &&
                    last.swapFreeBytes >= minSwapFreeBytes}"
        )
        if (last.availableBytes < minAvailableBytes ||
            last.swapFreeBytes < minSwapFreeBytes) {
            sendStatus(
                "DEVICE_UNDER_LOAD: " +
                    "MemAvailable=${last.availableBytes / 1_000_000}MB " +
                    "(need ${minAvailableBytes / 1_000_000}MB), " +
                    "SwapFree=${last.swapFreeBytes / 1_000_000}MB " +
                    "(need ${minSwapFreeBytes / 1_000_000}MB). " +
                    "Close background apps and rerun; the import pipeline needs " +
                    "the memory floor and $passMarker would not be meaningful."
            )
            throw IllegalStateException(
                "DEVICE_UNDER_LOAD: insufficient free memory for import " +
                    "(available=${last.availableBytes}B, swapFree=${last.swapFreeBytes}B)"
            )
        }
    }

    private fun memorySnapshot(): MemorySnapshot {
        var availableBytes = -1L
        var swapFreeBytes = -1L
        java.io.File("/proc/meminfo").useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("MemAvailable:") -> availableBytes =
                        line.substringAfter(':').trim().split(' ').first().toLong() * 1024
                    line.startsWith("SwapFree:") -> swapFreeBytes =
                        line.substringAfter(':').trim().split(' ').first().toLong() * 1024
                }
            }
        }
        return MemorySnapshot(availableBytes, swapFreeBytes)
    }

    private data class MemorySnapshot(val availableBytes: Long, val swapFreeBytes: Long)

    private fun sendStatus(marker: String) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("tensor_g3_qualification", marker)
            }
        )
    }
}
