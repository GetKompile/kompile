package ai.kompile.chat.local.android.model

import android.content.Context
import android.system.Os
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.diagnostics.DspDiagnosticsTraceLog
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import org.json.JSONObject
import org.nd4j.dsp.model.SdxSourceIdentity
import org.nd4j.dsp.model.SdxTargetProfile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

private const val SDX_ERROR_INITIAL_CAPACITY = 2048
private const val SDX_LLM_LIBRARY_FILE_NAME = "libsdx_llm.so"
private const val SDX_LLM_JNI_LIBRARY_FILE_NAME = "libjnisdx_llm.so"

/** One Android loader for both GGUF ingestion and canonical SDZ execution workers. */
internal object SdxAndroidLlmLibrary {

    fun configure(
        context: Context,
        diagnosticMode: ModelDiagnosticMode = ModelDiagnosticMode.OFF,
    ): File {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val library = File(nativeDirectory, SDX_LLM_LIBRARY_FILE_NAME)
        val bridge = File(nativeDirectory, SDX_LLM_JNI_LIBRARY_FILE_NAME)
        if (!library.isFile || !bridge.isFile) {
            throw ChatException(
                "The SDX Android runtime is incomplete in ${nativeDirectory.absolutePath}: " +
                    "required=$SDX_LLM_LIBRARY_FILE_NAME,$SDX_LLM_JNI_LIBRARY_FILE_NAME"
            )
        }

        // Graal snapshots this process-local environment when it creates the isolate, so apply
        // the persisted Settings selection before the native runtime is created.
        val effectiveDiagnosticMode = effectiveDiagnosticModeForRuntime(diagnosticMode)
        Os.setenv("SDX_NATIVE_LIB_DIR", nativeDirectory.absolutePath, true)
        effectiveDiagnosticMode.dspCategories?.let {
            Os.setenv("ND4J_DSP_DIAGNOSTICS", it, true)
        } ?: Os.unsetenv("ND4J_DSP_DIAGNOSTICS")
        effectiveDiagnosticMode.dspLevel?.let {
            Os.setenv("ND4J_DSP_DIAGNOSTICS_LEVEL", it, true)
        } ?: Os.unsetenv("ND4J_DSP_DIAGNOSTICS_LEVEL")
        if (effectiveDiagnosticMode.nativeOpSanity) {
            Os.setenv("ND4J_DSP_NATIVE_DUMP_OUTPUTS", "1", true)
            // The known decode transition occurs around execution 36. Preserve the warmup,
            // compiled, and early replay evidence while keeping forensic output bounded.
            Os.setenv("ND4J_DSP_DIAG_EXEC_LIMIT", "64", true)
        } else {
            Os.unsetenv("ND4J_DSP_NATIVE_DUMP_OUTPUTS")
            Os.unsetenv("ND4J_DSP_DIAG_EXEC_LIMIT")
        }
        if (effectiveDiagnosticMode.capturesDspTrace) {
            val diagnosticFile = DspDiagnosticsTraceLog(context).prepareCapture()
            Os.setenv("ND4J_DSP_DIAGNOSTICS_FILE", diagnosticFile.absolutePath, true)
        } else {
            Os.unsetenv("ND4J_DSP_DIAGNOSTICS_FILE")
        }
        check(library.isFile) { "SDX Android runtime is missing: ${library.absolutePath}" }
        return library
    }

    fun bind(library: File): SdxAndroidLlmAbi {
        check(library.isFile) { "SDX Android runtime is missing: ${library.absolutePath}" }
        SdxAndroidLlmAbi.ensureLoaded()
        return SdxAndroidLlmAbi
    }
}

internal enum class PreparationStage {
    CONVERT_AND_CACHE_SDZ,
    TARGET_CACHE_READY,
    LOAD_ACCELERATOR
}

internal data class PreparedModelInfo(
    val cacheHit: Boolean,
    val sourceSha256: String,
    val sourceBytes: Long,
    val canonicalSdzLogicalSha256: String,
    val canonicalSdzLogicalBytes: Long,
    val canonicalSdzPath: String,
    val canonicalSdzBytes: Long,
    val modelPath: String,
    val tokenizerPath: String,
    val compileKey: String,
    val targetProfile: String,
    val targetSoc: String,
    val contextLength: Int,
    val maxPrefillLength: Int,
    val conversionProfileSha256: String,
    val diagnosticMode: String,
    val optimizedSourcePath: String,
    val optimizedSourceBytes: Long,
)

internal fun readCompleteSdxLastError(readInto: (ByteArray) -> Int): String {
    val initial = ByteArray(SDX_ERROR_INITIAL_CAPACITY)
    val requiredLength = readInto(initial)
    if (requiredLength == 0) return "no native error detail"
    if (requiredLength < 0) {
        throw ChatException("SDX returned an invalid last-error length: $requiredLength")
    }
    if (requiredLength < initial.size) {
        return String(initial, 0, requiredLength, StandardCharsets.UTF_8)
    }
    if (requiredLength == Int.MAX_VALUE) {
        throw ChatException("SDX last-error detail is too large to allocate: $requiredLength bytes")
    }

    val complete = ByteArray(requiredLength + 1)
    val confirmedLength = readInto(complete)
    if (confirmedLength < 0 || confirmedLength >= complete.size) {
        throw ChatException(
            "SDX last-error length changed while reading it: " +
                "required=$requiredLength confirmed=$confirmedLength capacity=${complete.size}"
        )
    }
    return String(complete, 0, confirmedLength, StandardCharsets.UTF_8)
}

/**
 * Ingestion-only GGUF/GGML adapter.
 *
 * The Graal C ABI imports the container directly to canonical SDZ and populates the normal
 * immutable target cache. It never owns a chat session. Import runs in an app-private process
 * because the native image side-loads CPU JavaCPP JNI libraries. The ART-facing C-ABI bridge is
 * direct JNI so its process-global state never aliases the embedded JVM. Once that process exits,
 * callers open the
 * returned canonical SDZ through the same [PlatformLocalChatModelFactory] used for every model.
 */
internal object SdxGgufModelImporter {

    private const val STATUS_OK = 0
    private const val MIN_MODEL_BYTES = 16L

    fun prepare(
        context: Context,
        modelPath: String,
        tokenizerPath: String? = null,
        verifiedSourceSha256: String? = null,
        verifiedSourceBytes: Long? = null,
        options: ModelPreparationOptions = ModelPreparationOptions(),
        onPreparationStage: (PreparationStage) -> Unit = {}
    ): PreparedModelInfo {
        val model = File(modelPath).canonicalFile
        validateModelFile(model)
        val prepared = SdxModelPreparationClient.prepare(
            context.applicationContext,
            model,
            tokenizerPath,
            verifiedSourceSha256,
            verifiedSourceBytes,
            options,
            onPreparationStage
        )
        onPreparationStage(PreparationStage.TARGET_CACHE_READY)
        return prepared
    }

        /** Runs only in [SdxModelPreparationService]'s private process. */
        internal fun prepareInImporterProcess(
            context: Context,
            modelPath: String,
            tokenizerPath: String?,
            verifiedSourceSha256: String?,
            verifiedSourceBytes: Long?,
            options: ModelPreparationOptions,
            operation: NativeOperationTransaction,
            onPreparationStage: (PreparationStage) -> Unit
        ): PreparedModelInfo {
            check(
                android.app.Application.getProcessName() ==
                    sdxImporterProcessName(context.packageName)
            ) {
                "GGUF preparation attempted outside the app-private SDX importer process."
            }
            val model = File(modelPath).canonicalFile
            validateModelFile(model)
            val library = SdxAndroidLlmLibrary.configure(context, options.diagnosticMode)

            // This is the same cache root used by MobileModelArtifactResolver. The generated
            // SDZ is moved into its immutable source store; target compilation references that
            // canonical file instead of copying the full model again.
            val modelCache = File(context.noBackupFilesDir, "sdx-model-cache")
            require(modelCache.mkdirs() || modelCache.isDirectory) {
                "Android could not create the app-owned SDX model cache: ${modelCache.absolutePath}"
            }
            return prepareInIsolate(
                library,
                model,
                modelCache,
                tokenizerPath,
                verifiedSourceSha256,
                verifiedSourceBytes,
                options,
                operation,
                onPreparationStage
            )
        }

        private fun prepareInIsolate(
            library: File,
            model: File,
            modelCache: File,
            tokenizerPath: String?,
            verifiedSourceSha256: String?,
            verifiedSourceBytes: Long?,
            options: ModelPreparationOptions,
            operation: NativeOperationTransaction,
            onPreparationStage: (PreparationStage) -> Unit
        ): PreparedModelInfo {
            operation.checkpoint(NativeOperationCheckpoint.LOAD_IMPORTER_TRANSPORT)
            val native = SdxAndroidLlmLibrary.bind(library)
            operation.checkpoint(NativeOperationCheckpoint.CREATE_IMPORTER_RUNTIME)
            val runtime = native.sdxLlmCreateRuntime()
                ?: throw ChatException("SDX failed to create its Android import runtime")
            var primaryFailure: Throwable? = null
            try {
                operation.checkpoint(NativeOperationCheckpoint.QUERY_IMPORTER_ABI)
                val actualAbi = native.sdxLlmAbiVersion(runtime)
                if (actualAbi != SdxAndroidLlmAbi.ABI_VERSION) {
                    throw ChatException(
                        "libsdx_llm ABI mismatch: app=${SdxAndroidLlmAbi.ABI_VERSION} library=$actualAbi"
                    )
                }

                operation.checkpoint(NativeOperationCheckpoint.CONVERT_OPTIMIZE_SDZ)
                onPreparationStage(PreparationStage.CONVERT_AND_CACHE_SDZ)
                val preparationRef = SdxPointerByReference()
                val status = native.sdxLlmPrepareGguf(
                    runtime,
                    model.absolutePath,
                    tokenizerPath,
                    BuildConfig.SDX_TARGET_PROFILE,
                    modelCache.absolutePath,
                    SdxRawGgufContract.preparationOptionsJson(
                        verifiedSourceSha256,
                        verifiedSourceBytes,
                        options,
                    ),
                    preparationRef
                )
                if (status != STATUS_OK) {
                    throw ChatException(
                        "SDX could not prepare ${model.name} for " +
                            "${BuildConfig.SDX_TARGET_PROFILE} (status=$status): " +
                            lastError(operation, native, runtime)
                    )
                }
                operation.checkpoint(NativeOperationCheckpoint.READ_PREPARED_MODEL)
                return parsePreparedModel(
                    JSONObject(
                        readAndFree(
                            operation,
                            native,
                            runtime,
                            preparationRef.value,
                            "prepared model"
                        )
                    ),
                    modelCache,
                    verifiedSourceSha256,
                    verifiedSourceBytes,
                    options,
                )
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                var destroyIsJournaled = false
                try {
                    operation.checkpoint(NativeOperationCheckpoint.DESTROY_IMPORTER_RUNTIME)
                    destroyIsJournaled = true
                } catch (checkpointFailure: Throwable) {
                    if (primaryFailure == null) {
                        throw checkpointFailure
                    }
                    primaryFailure.addSuppressed(checkpointFailure)
                }
                if (destroyIsJournaled) {
                    val destroyStatus = native.sdxLlmDestroyRuntime(runtime)
                    if (destroyStatus != STATUS_OK) {
                        val cleanupFailure = ChatException(
                            "SDX import runtime destroy failed (status=$destroyStatus)"
                        )
                        if (primaryFailure == null) {
                            throw cleanupFailure
                        }
                        primaryFailure.addSuppressed(cleanupFailure)
                    }
                }
            }
        }

        private fun parsePreparedModel(
            json: JSONObject,
            modelCache: File,
            expectedSourceSha256: String?,
            expectedSourceBytes: Long?,
            options: ModelPreparationOptions,
        ): PreparedModelInfo {
            val schema = json.optString(SdxRawGgufContract.PREPARED_SCHEMA_FIELD, "")
            val target = json.optString(SdxRawGgufContract.TARGET_PROFILE_FIELD, "")
            val executionProvider = json.optString(
                SdxRawGgufContract.EXECUTION_PROVIDER_FIELD,
                ""
            )
            val expectedProvider = SdxTargetProfile
                .fromId(BuildConfig.SDX_TARGET_PROFILE)
                .platformProvider()
                .providerId()
            val importResourcesReleased = json.optBoolean(
                SdxRawGgufContract.IMPORT_RESOURCES_RELEASED_FIELD,
                false
            )
            if (schema != SdxRawGgufContract.PREPARED_SCHEMA ||
                target != BuildConfig.SDX_TARGET_PROFILE ||
                executionProvider != expectedProvider ||
                !importResourcesReleased
            ) {
                throw ChatException(
                    "SDX preparation proof is invalid: schema='$schema', target='$target', " +
                        "executionProvider='$executionProvider', expectedProvider='$expectedProvider', " +
                        "importResourcesReleased=$importResourcesReleased"
                )
            }

            val sourceSha256 = json.getString(SdxRawGgufContract.SOURCE_SHA256_FIELD)
            val sourceBytes = json.getLong(SdxRawGgufContract.SOURCE_BYTES_FIELD)
            val conversionProfileSha256 = json.getString(
                SdxRawGgufContract.CONVERSION_PROFILE_SHA256_FIELD
            )
            if (conversionProfileSha256 != options.profileSha256()) {
                throw ChatException(
                    "SDX prepared conversion profile does not match the selected options: " +
                        "expected=${options.profileSha256()} actual=$conversionProfileSha256"
                )
            }
            if (!sourceSha256.matches(Regex("[0-9a-f]{64}")) || sourceBytes <= 0L) {
                throw ChatException(
                    "SDX prepared raw-source identity is invalid: " +
                        "sha256='$sourceSha256' bytes=$sourceBytes"
                )
            }
            if (expectedSourceSha256 != null &&
                !sourceSha256.equals(expectedSourceSha256, ignoreCase = true)
            ) {
                throw ChatException(
                    "SDX prepared source SHA-256 does not match the verified download: " +
                        "expected=$expectedSourceSha256 actual=$sourceSha256"
                )
            }
            if (expectedSourceBytes != null && sourceBytes != expectedSourceBytes) {
                throw ChatException(
                    "SDX prepared source byte count does not match the verified download: " +
                        "expected=$expectedSourceBytes actual=$sourceBytes"
                )
            }

            val cacheRoot = File(modelCache, "v1").canonicalFile
            val canonicalSdz = requireCacheFile(
                cacheRoot,
                json.getString(SdxRawGgufContract.CANONICAL_SDZ_PATH_FIELD),
                "canonical SDZ"
            )
            val tokenizer = requireCacheFile(
                cacheRoot,
                json.getString(SdxRawGgufContract.TOKENIZER_PATH_FIELD),
                "tokenizer"
            )
            val runtimeModel = requireCacheRuntimeModel(
                cacheRoot,
                json.getString(SdxRawGgufContract.MODEL_PATH_FIELD)
            )
            val canonicalSdzLogicalSha256 = json.getString(
                SdxRawGgufContract.CANONICAL_SDZ_LOGICAL_SHA256_FIELD
            )
            if (!canonicalSdzLogicalSha256.matches(Regex("[0-9a-f]{64}"))) {
                throw ChatException(
                    "SDX canonical SDZ logical SHA-256 is invalid: " +
                        "'$canonicalSdzLogicalSha256'"
                )
            }
            val actualCanonicalIdentity = SdxSourceIdentity.identify(canonicalSdz.toPath())
            val canonicalSdzLogicalBytes = json.getLong(
                SdxRawGgufContract.CANONICAL_SDZ_LOGICAL_BYTES_FIELD
            )
            if (canonicalSdzLogicalBytes <= 0L ||
                actualCanonicalIdentity.sha256() != canonicalSdzLogicalSha256 ||
                actualCanonicalIdentity.logicalBytes() != canonicalSdzLogicalBytes
            ) {
                throw ChatException(
                    "SDX canonical SDZ logical identity changed after preparation: " +
                        "declared=$canonicalSdzLogicalSha256/$canonicalSdzLogicalBytes " +
                        "actual=${actualCanonicalIdentity.sha256()}/" +
                        actualCanonicalIdentity.logicalBytes()
                )
            }
            val declaredCanonicalBytes = json.getLong(
                SdxRawGgufContract.CANONICAL_SDZ_BYTES_FIELD
            )
            if (declaredCanonicalBytes != canonicalSdz.length()) {
                throw ChatException(
                    "SDX canonical SDZ size changed after preparation: declared=" +
                        "$declaredCanonicalBytes actual=${canonicalSdz.length()}"
                )
            }
            val optimizedSource = File(
                json.getString(SdxRawGgufContract.OPTIMIZED_SOURCE_PATH_FIELD)
            ).canonicalFile
            val optimizedSourceBytes = json.getLong(
                SdxRawGgufContract.OPTIMIZED_SOURCE_BYTES_FIELD
            )
            if (!optimizedSource.isFile || optimizedSource.length() != optimizedSourceBytes) {
                throw ChatException(
                    "SDX optimized source evidence is invalid: path=${optimizedSource.absolutePath} " +
                        "declared=$optimizedSourceBytes actual=${optimizedSource.length()}"
                )
            }
            val compileKey = json.getString(SdxRawGgufContract.COMPILE_KEY_FIELD)
            if (!compileKey.matches(Regex("[0-9a-f]{64}"))) {
                throw ChatException("SDX prepared compile key is invalid: '$compileKey'")
            }
            return PreparedModelInfo(
                cacheHit = json.getBoolean(SdxRawGgufContract.CACHE_HIT_FIELD),
                sourceSha256 = sourceSha256,
                sourceBytes = sourceBytes,
                canonicalSdzLogicalSha256 = canonicalSdzLogicalSha256,
                canonicalSdzLogicalBytes = canonicalSdzLogicalBytes,
                canonicalSdzPath = canonicalSdz.absolutePath,
                canonicalSdzBytes = declaredCanonicalBytes,
                modelPath = runtimeModel.absolutePath,
                tokenizerPath = tokenizer.absolutePath,
                compileKey = compileKey,
                targetProfile = target,
                targetSoc = json.getString(SdxRawGgufContract.TARGET_SOC_FIELD),
                contextLength = json.getInt(SdxRawGgufContract.CONTEXT_LENGTH_FIELD),
                maxPrefillLength = json.getInt(SdxRawGgufContract.MAX_PREFILL_LENGTH_FIELD),
                conversionProfileSha256 = conversionProfileSha256,
                diagnosticMode = json.getString(SdxRawGgufContract.DIAGNOSTIC_MODE_FIELD),
                optimizedSourcePath = optimizedSource.absolutePath,
                optimizedSourceBytes = optimizedSourceBytes,
            )
        }

        private fun requireCacheFile(root: File, path: String, description: String): File {
            val candidate = File(path).canonicalFile
            requireInsideCache(root, candidate, description)
            if (!candidate.isFile || !candidate.canRead()) {
                throw ChatException(
                    "Prepared $description is not a readable file: ${candidate.absolutePath}"
                )
            }
            return candidate
        }

        private fun requireCacheRuntimeModel(root: File, path: String): File {
            val candidate = File(path).canonicalFile
            requireInsideCache(root, candidate, "runtime model")
            if ((!candidate.isFile && !candidate.isDirectory) || !candidate.canRead()) {
                throw ChatException(
                    "Prepared runtime model is not a readable file or directory: " +
                        candidate.absolutePath
                )
            }
            return candidate
        }

        private fun requireInsideCache(root: File, candidate: File, description: String) {
            val rootPath = root.absolutePath
            val candidatePath = candidate.absolutePath
            if (candidatePath != rootPath &&
                !candidatePath.startsWith(rootPath + File.separator)
            ) {
                throw ChatException(
                    "Prepared $description escaped the app-owned SDX cache: $candidatePath"
                )
            }
        }

        private fun readAndFree(
            operation: NativeOperationTransaction,
            native: SdxAndroidLlmAbi,
            runtime: SdxNativeHandle,
            pointer: SdxNativeHandle?,
            description: String
        ): String {
            val value = pointer
                ?: throw ChatException("SDX returned a null $description pointer")
            return try {
                native.sdxLlmReadUtf8(value)
            } finally {
                operation.checkpoint(NativeOperationCheckpoint.FREE_IMPORTER_RESULT)
                native.sdxLlmFree(runtime, value)
            }
        }

        private fun lastError(
            operation: NativeOperationTransaction,
            native: SdxAndroidLlmAbi,
            runtime: SdxNativeHandle
        ): String = readCompleteSdxLastError { buffer ->
            operation.checkpoint(NativeOperationCheckpoint.QUERY_IMPORTER_LAST_ERROR)
            native.sdxLlmGetLastError(runtime, buffer, buffer.size)
        }

        fun supports(modelPath: String): Boolean {
            val extension = File(modelPath).extension.lowercase()
            return extension == "gguf" || extension == "ggml"
        }

        private fun validateModelFile(model: File) {
            if (!model.isFile || !model.canRead()) {
                throw ChatException("GGUF/GGML model is not a readable file: ${model.absolutePath}")
            }
            if (!supports(model.path)) {
                throw ChatException("Raw SDX import requires a .gguf or .ggml file: ${model.name}")
            }
            if (model.length() < MIN_MODEL_BYTES) {
                throw ChatException("Model file is truncated: ${model.name}")
            }
            val magic = ByteArray(4)
            RandomAccessFile(model, "r").use { it.readFully(magic) }
            if (!hasSupportedMagic(magic)) {
                val signature = magic.joinToString(separator = "") {
                    "%02x".format(it.toInt() and 0xff)
                }
                throw ChatException(
                    "${model.name} is not a GGUF/GGML model (magic=0x$signature)"
                )
            }
        }

        /** Mirrors DL4J's GGMLFormatDetector without pulling its JVM jar into the APK. */
        internal fun hasSupportedMagic(magic: ByteArray): Boolean {
            if (magic.size < 4) return false
            val littleEndian = ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).int
            val bigEndian = ByteBuffer.wrap(magic).order(ByteOrder.BIG_ENDIAN).int
            return littleEndian == GGUF_MAGIC ||
                littleEndian == GGML_MAGIC || littleEndian == GGMF_MAGIC ||
                littleEndian == GGJT_MAGIC || bigEndian == GGML_MAGIC ||
                bigEndian == GGMF_MAGIC || bigEndian == GGJT_MAGIC
        }

        private const val GGUF_MAGIC = 0x46554747
        private const val GGML_MAGIC = 0x67676D6C
        private const val GGMF_MAGIC = 0x67676D66
        private const val GGJT_MAGIC = 0x67676A74
}
