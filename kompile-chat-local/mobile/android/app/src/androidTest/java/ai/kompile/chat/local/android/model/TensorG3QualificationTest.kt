package ai.kompile.chat.local.android.model

import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
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

    /** Functional evidence only: no environment-floor veto, cache deletion, or promotion marker. */
    @Test
    fun functionalQwenDecodeUsesRequestedPrecision() {
        val weightOptimization = WeightOptimization.valueOf(requiredArgument("weight_optimization"))
        runDecode(
            expectedCacheHit = null,
            passMarker = "FUNCTIONAL_DECODE_PASS",
            strictQualification = false,
            preparationOptions = ModelPreparationOptions.fromWire(
                weightOptimization = weightOptimization.name,
                kvCacheOptimization = arguments.getString("kv_cache_optimization"),
                tensorBatchSize = optionalLongArgument("tensor_batch_size", 4L, 1L..256L).toInt(),
                useMemoryMapping = arguments.getString("use_memory_mapping")?.let {
                    requireNotNull(it.toBooleanStrictOrNull()) { "use_memory_mapping must be true or false" }
                } ?: true,
                diagnosticMode = arguments.getString("diagnostic_mode"),
            ),
        )
    }

    /** Read-only, paged export; does not initialize a model or change its diagnostics. */
    @Test
    fun exportDiagnosticSnapshot() {
        assertEquals(requiredArgument("expected_build_id"), BuildConfig.APK_BUILD_ID)
        val relativePath = when (requiredArgument("diagnostic_file")) {
            "smoke" -> "diagnostics/smoke-decode/smoke-decode.log"
            "dsp" -> "diagnostics/dsp/dsp-diagnostics.json"
            else -> error("diagnostic_file must be smoke or dsp")
        }
        val offset = arguments.getString("diagnostic_offset")?.toLong() ?: 0L
        require(offset >= 0L) { "diagnostic_offset must be nonnegative" }
        val file = File(context.filesDir, relativePath)
        assertTrue("diagnostic file missing: $relativePath", file.isFile)
        if (arguments.getString("diagnostic_copy") == "true") {
            val digest = sha256(file)
            val destination = File(requireNotNull(context.getExternalFilesDir(null)),
                "diagnostic-${file.name}-$digest")
            if (!destination.exists()) file.copyTo(destination, overwrite = false)
            assertEquals(digest, sha256(destination))
            sendStatus("DIAGNOSTIC_COPY:${destination.absolutePath},bytes=${destination.length()},sha256=$digest")
            return
        }
        sendStatus("DIAGNOSTIC_FILE:$relativePath,bytes=${file.length()},characterOffset=$offset")
        file.bufferedReader().use { reader ->
            var remaining = offset
            while (remaining > 0L) {
                val skipped = reader.skip(remaining)
                if (skipped == 0L) break
                remaining -= skipped
            }
            require(remaining == 0L) { "diagnostic_offset exceeds file length" }
            val buffer = CharArray(2048)
            var exported = 0
            while (exported < 65536) {
                val count = reader.read(buffer, 0, minOf(buffer.size, 65536 - exported))
                if (count < 0) break
                sendStatus("DIAGNOSTIC_CHUNK:${offset + exported}:${String(buffer, 0, count)}")
                exported += count
            }
            sendStatus("DIAGNOSTIC_NEXT_CHARACTER_OFFSET:${offset + exported}")
        }
    }

    /** Export bounded preparation evidence only; never initialize or modify a model/cache. */
    @Test
    fun exportPreparationEvidence() {
        assertEquals(requiredArgument("expected_build_id"), BuildConfig.APK_BUILD_ID)
        val sourceSha = requiredSha256Argument("source_sha256")
        val report = org.json.JSONObject()
        val diagnostics = ai.kompile.chat.local.android.diagnostics.ImportDiagnosticStore(context).load()
        report.put("importDiagnostics", org.json.JSONArray().apply {
            diagnostics.forEach { entry ->
                put(org.json.JSONObject().put("timestamp", entry.timestampEpochMillis)
                    .put("details", ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy.copyText(entry)))
            }
        })
        val cache = File(context.noBackupFilesDir, "sdx-model-cache/v1").canonicalFile
        val entries = org.json.JSONArray()
        var visited = 0
        for (root in listOf(File(cache, "prepared/$sourceSha"), File(cache, "sources"))) {
            if (!root.isDirectory) continue
            for (file in root.walkTopDown().maxDepth(10)) {
                check(++visited <= 10000) { "Preparation inventory exceeds diagnostic bound" }
                check(file.canonicalPath.startsWith(cache.path + File.separator)) { "Cache path escaped root" }
                if (!file.isFile) continue
                val item = org.json.JSONObject().put("path", file.relativeTo(cache).path)
                    .put("bytes", file.length()).put("modified", file.lastModified())
                // Only small identity/phase metadata. Never read GGUF/SDZ weight payloads.
                if ((file.extension == "path" || file.extension == "json") && file.length() <= 8192L) {
                    file.bufferedReader().use { reader ->
                        val chars = CharArray(8193)
                        val count = reader.read(chars)
                        check(count <= 8192) { "Metadata grew beyond diagnostic bound" }
                        item.put("text", if (count > 0) String(chars, 0, count) else "")
                    }
                }
                entries.put(item)
            }
        }
        report.put("cacheEntries", entries)
        val text = report.toString(2)
        check(text.length <= 8 * 1024 * 1024) { "Preparation report exceeds diagnostic bound" }
        val destination = File.createTempFile("preparation-evidence-", ".json",
            requireNotNull(context.getExternalFilesDir(null)))
        destination.writeText(text)
        sendStatus("DIAGNOSTIC_COPY:${destination.absolutePath},bytes=${destination.length()},sha256=${sha256(destination)}")
    }

    /** Snapshot the exact failing artifact, never reimport or alter the runtime cache. */
    @Test
    fun exportCanonicalModelSnapshot() {
        assertEquals(requiredArgument("expected_build_id"), BuildConfig.APK_BUILD_ID)
        val sourceId = requiredSha256Argument("canonical_source_id")
        val expectedHash = requiredSha256Argument("canonical_sdz_sha256")
        val cache = File(context.noBackupFilesDir, "sdx-model-cache/v1").canonicalFile
        val model = File(cache, "sources/$sourceId/model.sdz")
        assertTrue("canonical model missing", model.isFile)
        assertEquals(expectedHash, sha256(model))
        val tokenizerFiles = listOf("tokenizer.json", "tokenizer_config.json").map { name ->
            File(context.filesDir, "functional/$name").also {
                assertTrue("tokenizer asset missing: $name", it.isFile)
            }
        }
        val external = requireNotNull(context.getExternalFilesDir(null))
        val destination = File(external, "diagnostic-snapshot-$sourceId")
        require(!destination.exists()) { "snapshot already exists; do not overwrite evidence" }
        require(external.usableSpace > model.length() + 128L * 1024 * 1024) {
            "insufficient space for a preserved model snapshot"
        }
        check(destination.mkdirs())
        val copy = File(destination, "model.sdz")
        model.copyTo(copy, overwrite = false)
        assertEquals(expectedHash, sha256(copy))
        sendStatus("SNAPSHOT_MODEL:${copy.absolutePath},bytes=${copy.length()},sha256=$expectedHash")
        // Keep small identity/compile/text-generation metadata in its original relative layout.
        cache.walkTopDown().maxDepth(10).filter {
            it.isFile && it.length() <= 1024 * 1024 &&
                (it.extension == "json" || it.extension == "path")
        }.forEach { file ->
            val relative = file.relativeTo(cache).path
            val output = File(destination, "cache-metadata/$relative")
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            file.copyTo(output, overwrite = false)
            sendStatus("SNAPSHOT_METADATA:$relative,bytes=${file.length()},sha256=${sha256(output)}")
        }
        for (file in tokenizerFiles) {
            file.copyTo(File(destination, file.name), overwrite = false)
            sendStatus("SNAPSHOT_TOKENIZER:${file.name},sha256=${sha256(file)}")
        }
        sendStatus("SNAPSHOT_COMPLETE:${destination.absolutePath}")
    }

    private fun runDecode(
        expectedCacheHit: Boolean?,
        passMarker: String,
        strictQualification: Boolean = true,
        preparationOptions: ModelPreparationOptions = ModelPreparationOptions(
            weightOptimization = WeightOptimization.Q4_K,
        ),
    ) {
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

        val modelDirectory = if (strictQualification) "qualification" else "functional"
        val model = File(context.filesDir, "$modelDirectory/model.gguf").canonicalFile
        assertTrue("test model is missing: $model", model.isFile)
        assertEquals(expectedModelBytes, model.length())
        assertEquals(expectedModelSha256, sha256(model))
        sendStatus("PROCESS_PID:${Process.myPid()}")
        sendStatus("INPUT_VERIFIED")
        sendStatus("MODEL_PROFILE:${preparationOptions.conversionProfileJson()}")
        sendStatus("RUN_POLICY:${if (strictQualification) "STRICT_QUALIFICATION" else "FUNCTIONAL"}")
        if (strictQualification) {
            preflightMemoryFloor()
        } else {
            recordMemory("FUNCTIONAL_PREFLIGHT")
        }

        val options = GenOptions.builder()
            .temperature(0.0)
            .maxTokens(16)
            .build()
        val messages = listOf(Message.user("Reply with the single word: ready"))

        val answer = AcceleratedChatModelAndroid(
            context = context,
            modelPath = model.absolutePath,
            temperature = 0.0f,
            maxTokens = 16,
            verifiedSourceSha256 = expectedModelSha256,
            verifiedSourceBytes = expectedModelBytes,
            preparationOptions = preparationOptions,
            onPreparationStage = { stage -> recordMemory("PREPARATION_STAGE:${stage.name}") },
        ).use { runtime ->
            val info = assertNotNull(runtime.preparationInfo).let { runtime.preparationInfo!! }
            if (expectedCacheHit == true) {
                assertTrue("warm import did not reuse the exact target cache", info.cacheHit)
            } else if (expectedCacheHit == false) {
                assertFalse("cold import unexpectedly reused a target cache", info.cacheHit)
            }
            assertEquals(expectedModelSha256, info.sourceSha256)
            assertEquals(expectedModelBytes, info.sourceBytes)
            assertEquals(preparationOptions.profileSha256(), info.conversionProfileSha256)
            assertEquals("LOCAL_TENSOR_G3_NNAPI", runtime.routeName)
            sendStatus(
                "PREPARED_MODEL:cacheHit=${info.cacheHit},profileSha256=${info.conversionProfileSha256}" +
                    ",canonicalSdzBytes=${info.canonicalSdzBytes},contextLength=${info.contextLength}" +
                    ",maxPrefillLength=${info.maxPrefillLength}"
            )
            recordMemory("BEFORE_DECODE")
            val answer = runtime.generate(messages, options)
            assertTrue("decode returned no text", answer.isNotBlank())
            sendStatus("DECODE_TEXT:$answer")
            recordMemory("AFTER_DECODE")
            answer
        }
        recordMemory("RUNTIME_CLOSED")
        assertEquals("decode must answer the prompt, not merely return nonblank text", "ready", answer.trim())
        sendStatus(passMarker)
    }

    private fun recordMemory(phase: String) {
        val snapshot = currentModelPreparationMemorySnapshot()
        sendStatus(
            "$phase:elapsedRealtimeMillis=${SystemClock.elapsedRealtime()}" +
                ",availableBytes=${snapshot.availableBytes},swapFreeBytes=${snapshot.swapFreeBytes}" +
                ",readable=${snapshot.readable}"
        )
    }

    private fun optionalLongArgument(name: String, fallback: Long, range: LongRange): Long {
        val raw = arguments.getString(name) ?: return fallback
        return raw.toLongOrNull()?.takeIf { it in range }
            ?: error("$name must be an integer in $range")
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

    /** Qualification environment policy only; this is not a model-fit admission test. */
    private fun preflightMemoryFloor() {
        val preflight = awaitModelPreparationMemoryPreflight(
            timeoutMillis = optionalLongArgument("memory_wait_timeout_ms",
                MODEL_PREPARATION_QUALIFICATION_WAIT_TIMEOUT_MILLIS, 0L..300_000L),
            onWaiting = { waiting ->
                requireReadableMemoryPreflight(waiting)
                sendStatus("MEMORY_PREFLIGHT_WAIT:${waiting.statusFields()}")
            },
            minimumAvailableBytes = optionalLongArgument("min_available_ram_bytes",
                TENSOR_G3_QUALIFICATION_MIN_AVAILABLE_BYTES, 0L..Long.MAX_VALUE),
            minimumSwapFreeBytes = optionalLongArgument("min_free_swap_bytes",
                TENSOR_G3_QUALIFICATION_MIN_SWAP_FREE_BYTES, 0L..Long.MAX_VALUE),
        )
        requireReadableMemoryPreflight(preflight)
        sendStatus("MEMORY_PREFLIGHT:${preflight.statusFields()}")
        if (!preflight.thresholdMet) {
            val message = preflight.userMessage("qualification model")
            sendStatus("DEVICE_UNDER_LOAD: Qualification environment criteria not met. $message")
            throw IllegalStateException("DEVICE_UNDER_LOAD: $message")
        }
    }

    private fun requireReadableMemoryPreflight(preflight: ModelPreparationMemoryPreflight) {
        if (!preflight.snapshot.readable) {
            sendStatus(
                "MEMORY_PREFLIGHT_ERROR:" +
                    "availableBytes=${preflight.snapshot.availableBytes}" +
                    ",swapFreeBytes=${preflight.snapshot.swapFreeBytes}"
            )
            throw IllegalStateException(
                "MEMORY_PREFLIGHT_ERROR: could not read MemAvailable/SwapFree from /proc/meminfo"
            )
        }
    }

    private fun sendStatus(marker: String) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("tensor_g3_qualification", marker)
            }
        )
    }
}
