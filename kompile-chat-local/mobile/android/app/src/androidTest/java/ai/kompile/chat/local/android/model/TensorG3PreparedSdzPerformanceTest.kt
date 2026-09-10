package ai.kompile.chat.local.android.model

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TensorG3PreparedSdzPerformanceTest {

    private val instrumentation: Instrumentation =
        InstrumentationRegistry.getInstrumentation()
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext

    @Test
    fun preparedSdzRunsOnAuditedTensorG3Plan() {
        val expectedBuildId = requiredArgument("expected_build_id")
        val model = File(requiredArgument("prepared_sdz_path")).canonicalFile
        val diagnosticMode = arguments.getString("diagnostic_mode")
            ?.takeIf(String::isNotBlank)
            ?.let(ModelDiagnosticMode::valueOf)
            ?: ModelDiagnosticMode.BACKEND_AUDIT
        assertEquals("google-tensor-g3-nnapi", BuildConfig.ACCELERATOR_PROVIDER)
        assertEquals("android-arm64-nnapi-accelerator", BuildConfig.SDX_TARGET_PROFILE)
        assertEquals(expectedBuildId, BuildConfig.APK_BUILD_ID)
        assertTrue("prepared SDZ is missing: $model", model.isFile)

        val options = GenOptions.builder()
            .temperature(0.0)
            .maxTokens(16)
            .build()
        val messages = listOf(Message.user("Reply with the single word: ready"))
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val answer: String
        AcceleratedChatModelAndroid(
            context = context,
            modelPath = model.absolutePath,
            temperature = 0.0f,
            maxTokens = 16,
            preparationOptions = ModelPreparationOptions(
                diagnosticMode = diagnosticMode
            )
        ).use { runtime ->
            assertEquals("LOCAL_TENSOR_G3_NNAPI", runtime.routeName)
            answer = runtime.generate(messages, options)
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedNanos
        assertEquals("ready", answer.trim())

        val diagnostics = File(
            context.filesDir,
            "diagnostics/dsp/dsp-diagnostics.json"
        )
        assertTrue("native DSP diagnostics were not finalized", diagnostics.isFile)
        assertTrue("native DSP diagnostics are empty", diagnostics.length() > 0L)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("tensor_g3_prepared_sdz", "QUALIFICATION_PASS")
                putString("answer", answer)
                putString("diagnostic_mode", diagnosticMode.name)
                putLong("elapsed_nanos", elapsedNanos)
                putLong("diagnostic_bytes", diagnostics.length())
            }
        )
    }

    /** Diagnostic capture only: no answer qualification and no generation request. */
    @Test
    fun preparedSdzWarmupNumericalSnapshot() {
        val model = File(requiredArgument("prepared_sdz_path")).canonicalFile
        assertEquals(requiredArgument("expected_build_id"), BuildConfig.APK_BUILD_ID)
        assertEquals("google-tensor-g3-nnapi", BuildConfig.ACCELERATOR_PROVIDER)
        assertEquals("android-arm64-nnapi-accelerator", BuildConfig.SDX_TARGET_PROFILE)
        assertTrue("prepared SDZ is missing: $model", model.isFile)
        val expectedSha = requiredArgument("prepared_sdz_sha256")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(expectedSha, digest.digest().joinToString("") { "%02x".format(it) })
        val startedMillis = System.currentTimeMillis()
        AcceleratedChatModelAndroid(
            context = context,
            modelPath = model.absolutePath,
            temperature = 0.0f,
            maxTokens = 16,
            preparationOptions = ModelPreparationOptions(diagnosticMode = ModelDiagnosticMode.OP_SANITY)
        ).use { runtime ->
            assertEquals("LOCAL_TENSOR_G3_NNAPI", runtime.routeName)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("numerical_snapshot", "WARMUP_COMPLETE")
            })
        }
        val diagnostics = File(context.filesDir, "diagnostics/dsp/dsp-diagnostics.json")
        assertTrue("native diagnostics missing", diagnostics.isFile && diagnostics.length() > 0L)
        assertTrue("native diagnostics are stale", diagnostics.lastModified() >= startedMillis)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numerical_snapshot", "WARMUP_SNAPSHOT_CLOSED")
            putLong("diagnostic_bytes", diagnostics.length())
        })
    }

    private fun requiredArgument(name: String): String =
        arguments.getString(name)?.takeIf(String::isNotBlank)
            ?: error("missing instrumentation argument: $name")
}
