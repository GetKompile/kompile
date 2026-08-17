package ai.kompile.chat.local.android.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import ai.kompile.chat.local.android.AndroidJavaCppMemoryPolicy
import ai.kompile.chat.local.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** User-facing destination for a recovered fatal native operation. */
internal enum class NativeOperationRecoveryTarget {
    HUGGING_FACE_IMPORT,
    ACTIVE_MODEL
}

/** Every SDX/ND4J model-operation family is represented here, not only accelerator startup. */
internal enum class NativeOperationKind(
    val label: String,
    val recoveryTarget: NativeOperationRecoveryTarget,
    val remediation: String
) {
    SDX_MODEL_PREPARATION(
        "SDX model preparation",
        NativeOperationRecoveryTarget.HUGGING_FACE_IMPORT,
        "Expand and copy this entry before retrying. The verified original model bytes and any completely committed SDZ cache entry remain reusable."
    ),
    SDX_MODEL_LOAD(
        "SDX model load",
        NativeOperationRecoveryTarget.ACTIVE_MODEL,
        "Expand and copy this entry before retrying. The verified model, canonical SDZ, target cache, and device-driver cache remain reusable."
    ),
    SDX_MODEL_EXECUTION(
        "SDX model execution",
        NativeOperationRecoveryTarget.ACTIVE_MODEL,
        "Expand and copy this entry before reopening the cached model and retrying the decode. The downloaded model does not need to be downloaded again."
    ),
    SDX_MODEL_CANCELLATION(
        "SDX model cancellation",
        NativeOperationRecoveryTarget.ACTIVE_MODEL,
        "Expand and copy this entry. Reopen the cached model before starting another generation."
    ),
    SDX_MODEL_TEARDOWN(
        "SDX model teardown",
        NativeOperationRecoveryTarget.ACTIVE_MODEL,
        "Expand and copy this entry. The cached model remains reusable; reopen it in a new runtime process."
    )
}

/** Exact native boundary that was in flight when Android terminated an app-owned process. */
internal enum class NativeOperationCheckpoint(val label: String) {
    START_IMPORTER_PROCESS("Start the app-private SDX importer process"),
    LOAD_IMPORTER_TRANSPORT("Load the SDX GGUF importer library"),
    CREATE_IMPORTER_RUNTIME("Create the SDX GGUF import runtime"),
    QUERY_IMPORTER_ABI("Query the SDX GGUF importer ABI"),
    CONVERT_OPTIMIZE_SDZ("Convert and optimize SDZ"),
    QUERY_IMPORTER_LAST_ERROR("Read the SDX GGUF importer failure"),
    READ_PREPARED_MODEL("Read and validate the prepared SDZ result"),
    FREE_IMPORTER_RESULT("Free the prepared SDZ result"),
    DESTROY_IMPORTER_RUNTIME("Destroy the SDX GGUF import runtime"),
    STOP_IMPORTER_PROCESS("Stop the app-private SDX importer process"),

    RESOLVE_MODEL_ASSETS("Resolve cached model assets"),
    PREPARE_DEVICE_CACHE("Prepare the device compilation cache"),
    LOAD_NATIVE_TRANSPORT("Load the shared SDX runtime library"),
    CREATE_NATIVE_RUNTIME("Create the native SDX runtime"),
    QUERY_RUNTIME_ABI("Query the native runtime ABI"),
    LOAD_MODEL_BUNDLE("Compile or restore the accelerator execution plan"),
    LOAD_TOKENIZER("Load the tokenizer runtime"),
    CREATE_TEXT_SESSION("Create the generation session"),

    RENDER_CHAT_TEMPLATE("Render the model chat template"),
    ENCODE_PROMPT("Encode the rendered prompt"),
    RESET_TEXT_SESSION("Reset the generation session"),
    GENERATE_TOKENS("Execute SDX token generation"),
    DECODE_TOKENS("Decode generated tokens"),
    CANCEL_GENERATION("Cancel SDX token generation"),

    CLOSE_TEXT_SESSION("Close the generation session"),
    CLOSE_TOKENIZER("Close the tokenizer runtime"),
    CLOSE_MODEL("Close the SDX model"),
    CLOSE_RUNTIME("Close the SDX runtime"),

    LOAD_LITERT_RUNTIME("Load the LiteRT-LM accelerator runtime"),
    CREATE_LITERT_SESSION("Create the LiteRT-LM chat session"),
    EXECUTE_LITERT_GENERATION("Execute LiteRT-LM token generation"),
    CANCEL_LITERT_GENERATION("Cancel LiteRT-LM token generation"),
    CLOSE_LITERT_SESSION("Close the LiteRT-LM chat session")
}

internal data class NativeOperationMemorySnapshot(
    val javaHeapUsedBytes: Long,
    val javaHeapMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val systemTotalBytes: Long,
    val systemAvailableBytes: Long,
    val systemLowMemoryThresholdBytes: Long,
    val systemLowMemory: Boolean,
    val javaCppMaxTrackedBytes: Long,
    val javaCppMaxPhysicalBytes: Long
) {
    companion object {
        fun capture(context: Context): NativeOperationMemorySnapshot {
            val runtime = Runtime.getRuntime()
            val memoryInfo = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
            val javaCppLimits = AndroidJavaCppMemoryPolicy.requireInstalled()
            return NativeOperationMemorySnapshot(
                javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                javaHeapMaxBytes = runtime.maxMemory(),
                nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
                systemTotalBytes = memoryInfo.totalMem,
                systemAvailableBytes = memoryInfo.availMem,
                systemLowMemoryThresholdBytes = memoryInfo.threshold,
                systemLowMemory = memoryInfo.lowMemory,
                javaCppMaxTrackedBytes = javaCppLimits.maxTrackedBytes,
                javaCppMaxPhysicalBytes = javaCppLimits.maxPhysicalBytes
            )
        }
    }
}

internal data class NativeOperationAttempt(
    val attemptId: String,
    val startedEpochMillis: Long,
    val checkpointEpochMillis: Long,
    val operation: NativeOperationKind,
    val checkpoint: NativeOperationCheckpoint,
    val processName: String,
    val processId: Int,
    val provider: String,
    val targetProfile: String,
    val buildId: String,
    val modelPathFingerprint: String,
    val modelBytes: Long,
    val transportLibraryBytes: Long,
    val acceleratorLibraryBytes: Long,
    val importerLibraryBytes: Long,
    val cpuImporterBackendBytes: Long,
    val memory: NativeOperationMemorySnapshot
)

internal data class NativeOperationExitEvidence(
    val timestampEpochMillis: Long,
    val processName: String,
    val processId: Int,
    val reason: Int,
    val status: Int,
    val description: String,
    val pssKilobytes: Long,
    val rssKilobytes: Long,
    val importance: Int,
    val trace: String,
    val traceReadFailure: String
)

internal data class RecoveredNativeOperation(
    val attempt: NativeOperationAttempt,
    val exitEvidence: NativeOperationExitEvidence?,
    val diagnostic: ImportDiagnostic
)

/** Pure formatting and correlation rules so crash evidence remains host-testable. */
internal object NativeOperationDiagnosticPolicy {
    private const val EXIT_MATCH_CLOCK_SLOP_MILLIS = 2_000L

    fun exitMatchesAttempt(
        attempt: NativeOperationAttempt,
        processName: String,
        processId: Int,
        exitTimestampEpochMillis: Long
    ): Boolean = exitMatchesProcess(
        expectedProcessName = attempt.processName,
        expectedProcessId = attempt.processId,
        startedEpochMillis = attempt.startedEpochMillis,
        processName = processName,
        processId = processId,
        exitTimestampEpochMillis = exitTimestampEpochMillis
    )

    fun exitMatchesProcess(
        expectedProcessName: String,
        expectedProcessId: Int,
        startedEpochMillis: Long,
        processName: String,
        processId: Int,
        exitTimestampEpochMillis: Long
    ): Boolean =
        processName == expectedProcessName &&
            (expectedProcessId <= 0 || processId <= 0 || processId == expectedProcessId) &&
            exitTimestampEpochMillis >= startedEpochMillis - EXIT_MATCH_CLOCK_SLOP_MILLIS

    fun modelPathFingerprint(path: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(File(path).absolutePath.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
    }

    fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "managed crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory termination"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "process initialization failure"
        ApplicationExitInfo.REASON_SIGNALED -> "signal termination"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency process death"
        ApplicationExitInfo.REASON_ANR -> "application not responding"
        ApplicationExitInfo.REASON_EXIT_SELF -> "self-requested exit"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested stop"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped package"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package update"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_FREEZER -> "cached-process freezer"
        ApplicationExitInfo.REASON_OTHER -> "other process termination"
        else -> "unknown process termination"
    }

    fun createDiagnostic(
        attempt: NativeOperationAttempt,
        exitEvidence: NativeOperationExitEvidence?,
        managedFailure: Throwable? = null
    ): ImportDiagnostic {
        val reason = exitEvidence?.let { reasonLabel(it.reason) }
        val summary = when {
            reason != null -> "Android recorded a $reason while ${attempt.checkpoint.label}."
            managedFailure != null -> {
                val detail = managedFailure.message?.takeIf(String::isNotBlank)
                    ?: managedFailure.javaClass.name
                "${attempt.operation.label} failed while ${attempt.checkpoint.label}: $detail"
            }
            else -> "The previous process ended while ${attempt.checkpoint.label}; Android retained no matching exit record."
        }
        val details = buildString {
            appendLine("Attempt ID: ${attempt.attemptId}")
            appendLine("Operation: ${attempt.operation.label}")
            appendLine("Checkpoint: ${attempt.checkpoint.label}")
            appendLine("Attempt started: ${attempt.startedEpochMillis}")
            appendLine("Checkpoint persisted: ${attempt.checkpointEpochMillis}")
            appendLine("Process: ${attempt.processName}")
            appendLine("PID: ${attempt.processId}")
            appendLine("Provider: ${attempt.provider}")
            appendLine("Target profile: ${attempt.targetProfile}")
            appendLine("APK build: ${attempt.buildId}")
            appendLine("Model path fingerprint: ${attempt.modelPathFingerprint}")
            appendLine("Model bytes: ${attempt.modelBytes}")
            appendLine("libjnisdx.so bytes: ${attempt.transportLibraryBytes}")
            appendLine("accelerator backend bytes: ${attempt.acceleratorLibraryBytes}")
            appendLine("libsdx_llm.so bytes: ${attempt.importerLibraryBytes}")
            appendLine("CPU importer backend bytes: ${attempt.cpuImporterBackendBytes}")
            appendLine("Java heap used bytes: ${attempt.memory.javaHeapUsedBytes}")
            appendLine("Java heap max bytes: ${attempt.memory.javaHeapMaxBytes}")
            appendLine("Native heap allocated: ${attempt.memory.nativeHeapAllocatedBytes}")
            appendLine("System total bytes: ${attempt.memory.systemTotalBytes}")
            appendLine("System available bytes: ${attempt.memory.systemAvailableBytes}")
            appendLine("System low-memory threshold bytes: ${attempt.memory.systemLowMemoryThresholdBytes}")
            appendLine("System low-memory flag: ${attempt.memory.systemLowMemory}")
            appendLine("JavaCPP max tracked bytes: ${attempt.memory.javaCppMaxTrackedBytes}")
            appendLine("JavaCPP max physical bytes: ${attempt.memory.javaCppMaxPhysicalBytes}")
            if (exitEvidence != null) {
                appendLine("Exit timestamp: ${exitEvidence.timestampEpochMillis}")
                appendLine("Exit process: ${exitEvidence.processName}")
                appendLine("Exit PID: ${exitEvidence.processId}")
                appendLine("Exit reason: ${reasonLabel(exitEvidence.reason)} (${exitEvidence.reason})")
                appendLine("Exit status or signal: ${exitEvidence.status}")
                appendLine("Exit importance: ${exitEvidence.importance}")
                appendLine("Exit PSS KiB: ${exitEvidence.pssKilobytes}")
                appendLine("Exit RSS KiB: ${exitEvidence.rssKilobytes}")
                if (exitEvidence.description.isNotBlank()) {
                    appendLine("Android description: ${exitEvidence.description}")
                }
                if (exitEvidence.traceReadFailure.isNotBlank()) {
                    appendLine("Exit trace capture or parse failure: ${exitEvidence.traceReadFailure}")
                }
                if (exitEvidence.trace.isNotBlank()) {
                    appendLine("Android exit trace:")
                    appendLine(exitEvidence.trace)
                }
            }
            if (managedFailure != null) {
                appendLine("Managed failure and complete stack trace:")
                append(managedFailure.stackTraceToString())
            }
        }
        return ImportDiagnosticPolicy.create(
            timestampEpochMillis = exitEvidence?.timestampEpochMillis
                ?: attempt.checkpointEpochMillis,
            operation = attempt.operation.label,
            phase = attempt.checkpoint.label,
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = attempt.operation.remediation,
            technicalDetails = details
        )
    }
}

/**
 * Cross-process, per-attempt checkpoint journal.
 *
 * Each operation owns one atomic file. This deliberately avoids SharedPreferences because Android
 * does not provide coherent multi-process SharedPreferences caching. A native SIGSEGV/abort cannot
 * execute Kotlin cleanup, so every checkpoint is fsynced before entering its native boundary and
 * removed only after a normal return or after durable recovery evidence has been stored.
 */
internal class NativeOperationJournal(context: Context) {
    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.noBackupFilesDir, JOURNAL_DIRECTORY)
    private val smokeTrace = SmokeDecodeTraceLog(applicationContext)

    @Synchronized
    fun begin(
        modelPath: String,
        operation: NativeOperationKind,
        checkpoint: NativeOperationCheckpoint,
        processName: String = Application.getProcessName(),
        processId: Int = Process.myPid()
    ): NativeOperationTransaction {
        ensureDirectory()
        val nativeDirectory = File(applicationContext.applicationInfo.nativeLibraryDir)
        val now = System.currentTimeMillis()
        val attempt = NativeOperationAttempt(
            attemptId = UUID.randomUUID().toString(),
            startedEpochMillis = now,
            checkpointEpochMillis = now,
            operation = operation,
            checkpoint = checkpoint,
            processName = processName,
            processId = processId,
            provider = BuildConfig.ACCELERATOR_PROVIDER,
            targetProfile = BuildConfig.SDX_TARGET_PROFILE,
            buildId = BuildConfig.APK_BUILD_ID,
            modelPathFingerprint = NativeOperationDiagnosticPolicy.modelPathFingerprint(modelPath),
            modelBytes = File(modelPath).takeIf(File::isFile)?.length() ?: 0L,
            transportLibraryBytes = File(nativeDirectory, "libjnisdx.so").length(),
            acceleratorLibraryBytes = File(nativeDirectory, acceleratorLibraryName()).length(),
            importerLibraryBytes = File(nativeDirectory, "libsdx_llm.so").length(),
            cpuImporterBackendBytes = File(nativeDirectory, "libnd4jcpu.so").length(),
            memory = NativeOperationMemorySnapshot.capture(applicationContext)
        )
        persist(attempt)
        if (operation == NativeOperationKind.SDX_MODEL_EXECUTION) {
            smokeTrace.record(
                "attempt_started",
                attempt.attemptId,
                mapOf(
                    "operation" to operation.name,
                    "checkpoint" to checkpoint.name,
                    "provider" to attempt.provider,
                    "target_profile" to attempt.targetProfile,
                    "process_name" to processName,
                    "process_id" to processId,
                    "model_bytes" to attempt.modelBytes,
                    "java_heap_used" to attempt.memory.javaHeapUsedBytes,
                    "native_heap_used" to attempt.memory.nativeHeapAllocatedBytes,
                    "system_available" to attempt.memory.systemAvailableBytes
                )
            )
        }
        return NativeOperationTransaction(this, attempt)
    }

    @Synchronized
    fun resume(attemptId: String): NativeOperationTransaction {
        val attempt = loadPending(attemptId)
            ?: throw IllegalStateException("Native-operation attempt is no longer pending: $attemptId")
        return NativeOperationTransaction(this, attempt)
    }

    @Synchronized
    fun resumeOrNull(attemptId: String): NativeOperationTransaction? =
        loadPending(attemptId)?.let { NativeOperationTransaction(this, it) }

    @Synchronized
    fun loadPending(attemptId: String): NativeOperationAttempt? {
        val file = attemptFile(attemptId)
        if (!hasAttemptArtifacts(file)) return null
        return try {
            readAttempt(file).also { attempt ->
                check(attempt.attemptId == attemptId) {
                    "Native-operation journal attempt mismatch: expected=$attemptId actual=${attempt.attemptId}"
                }
            }
        } catch (failure: IOException) {
            if (!hasAttemptArtifacts(file)) null else throw failure
        }
    }

    @Synchronized
    fun loadPending(): List<NativeOperationAttempt> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles { file ->
            file.isFile && file.name.endsWith(JOURNAL_FILE_SUFFIX)
        } ?: throw IllegalStateException(
            "Android could not enumerate native-operation checkpoints in ${directory.absolutePath}."
        )
        return files.sortedBy(File::getName).map(::readAttempt)
    }

    @Synchronized
    internal fun advance(
        attempt: NativeOperationAttempt,
        checkpoint: NativeOperationCheckpoint,
        processName: String,
        processId: Int
    ): NativeOperationAttempt {
        val stored = readAttempt(attemptFile(attempt.attemptId))
        check(stored.attemptId == attempt.attemptId) {
            "Native-operation journal changed owners: expected=${attempt.attemptId} actual=${stored.attemptId}"
        }
        val updated = attempt.copy(
            checkpointEpochMillis = System.currentTimeMillis(),
            checkpoint = checkpoint,
            processName = processName,
            processId = processId,
            memory = NativeOperationMemorySnapshot.capture(applicationContext)
        )
        persist(updated)
        if (updated.operation == NativeOperationKind.SDX_MODEL_EXECUTION) {
            smokeTrace.record(
                "checkpoint",
                updated.attemptId,
                mapOf(
                    "checkpoint" to updated.checkpoint.name,
                    "checkpoint_label" to updated.checkpoint.label,
                    "process_name" to processName,
                    "process_id" to processId,
                    "java_heap_used" to updated.memory.javaHeapUsedBytes,
                    "native_heap_used" to updated.memory.nativeHeapAllocatedBytes,
                    "system_available" to updated.memory.systemAvailableBytes,
                    "system_low_memory" to updated.memory.systemLowMemory
                )
            )
        }
        return updated
    }

    @Synchronized
    internal fun persistManagedFailure(
        attemptId: String,
        failure: Throwable
    ): NativeOperationAttempt? {
        val stored = loadPending(attemptId) ?: run {
            // The provider or another transaction already finalized this cross-process attempt.
            return null
        }
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
            stored,
            exitEvidence = null,
            managedFailure = failure
        )
        smokeTrace.recordFailure(
            "attempt_failed",
            attemptId,
            failure,
            mapOf(
                "operation" to stored.operation.name,
                "checkpoint" to stored.checkpoint.name,
                "process_id" to stored.processId
            )
        )
        ImportDiagnosticStore(applicationContext).appendDurably(diagnostic)
        clear(attemptId)
        return stored
    }

    @Synchronized
    internal fun traceCompletion(attempt: NativeOperationAttempt) {
        if (attempt.operation == NativeOperationKind.SDX_MODEL_EXECUTION) {
            smokeTrace.record(
                "attempt_completed",
                attempt.attemptId,
                mapOf("checkpoint" to attempt.checkpoint.name)
            )
        }
    }

    @Synchronized
    internal fun clear(attemptId: String) {
        val file = attemptFile(attemptId)
        if (!hasAttemptArtifacts(file)) return
        AtomicFile(file).delete()
        check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
            "Android could not durably clear native-operation checkpoint $attemptId."
        }
    }

    private fun hasAttemptArtifacts(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Android could not create native-operation journal directory: ${directory.absolutePath}"
        }
    }

    private fun persist(attempt: NativeOperationAttempt) {
        ensureDirectory()
        val atomicFile = AtomicFile(attemptFile(attempt.attemptId))
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(encode(attempt).toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            stream?.let { failedStream ->
                try {
                    atomicFile.failWrite(failedStream)
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw IllegalStateException(
                "Android could not durably persist ${attempt.checkpoint.label} before native execution.",
                failure
            )
        }
    }

    private fun readAttempt(file: File): NativeOperationAttempt {
        val bytes = AtomicFile(file).openRead().use { stream ->
            stream.readNBytes(MAX_JOURNAL_BYTES + 1)
        }
        require(bytes.size <= MAX_JOURNAL_BYTES) {
            "Native-operation checkpoint exceeds $MAX_JOURNAL_BYTES bytes: ${file.name}"
        }
        return decode(String(bytes, StandardCharsets.UTF_8))
    }

    private fun attemptFile(attemptId: String): File {
        require(ATTEMPT_ID.matches(attemptId)) {
            "Invalid native-operation attempt id: $attemptId"
        }
        return File(directory, attemptId + JOURNAL_FILE_SUFFIX)
    }

    private fun encode(attempt: NativeOperationAttempt): String = JSONObject().apply {
        put(KEY_SCHEMA, JOURNAL_SCHEMA)
        put(KEY_ATTEMPT_ID, attempt.attemptId)
        put(KEY_STARTED, attempt.startedEpochMillis)
        put(KEY_UPDATED, attempt.checkpointEpochMillis)
        put(KEY_OPERATION, attempt.operation.name)
        put(KEY_CHECKPOINT, attempt.checkpoint.name)
        put(KEY_PROCESS_NAME, attempt.processName)
        put(KEY_PROCESS_ID, attempt.processId)
        put(KEY_PROVIDER, attempt.provider)
        put(KEY_TARGET_PROFILE, attempt.targetProfile)
        put(KEY_BUILD_ID, attempt.buildId)
        put(KEY_MODEL_FINGERPRINT, attempt.modelPathFingerprint)
        put(KEY_MODEL_BYTES, attempt.modelBytes)
        put(KEY_TRANSPORT_BYTES, attempt.transportLibraryBytes)
        put(KEY_ACCELERATOR_BYTES, attempt.acceleratorLibraryBytes)
        put(KEY_IMPORTER_BYTES, attempt.importerLibraryBytes)
        put(KEY_CPU_IMPORTER_BYTES, attempt.cpuImporterBackendBytes)
        put(KEY_JAVA_USED, attempt.memory.javaHeapUsedBytes)
        put(KEY_JAVA_MAX, attempt.memory.javaHeapMaxBytes)
        put(KEY_NATIVE_USED, attempt.memory.nativeHeapAllocatedBytes)
        put(KEY_SYSTEM_TOTAL, attempt.memory.systemTotalBytes)
        put(KEY_SYSTEM_AVAILABLE, attempt.memory.systemAvailableBytes)
        put(KEY_SYSTEM_THRESHOLD, attempt.memory.systemLowMemoryThresholdBytes)
        put(KEY_SYSTEM_LOW, attempt.memory.systemLowMemory)
        put(KEY_JAVACPP_MAX_TRACKED, attempt.memory.javaCppMaxTrackedBytes)
        put(KEY_JAVACPP_MAX_PHYSICAL, attempt.memory.javaCppMaxPhysicalBytes)
    }.toString()

    private fun decode(value: String): NativeOperationAttempt {
        val json = JSONObject(value)
        require(json.getInt(KEY_SCHEMA) == JOURNAL_SCHEMA) {
            "Unsupported native-operation journal schema: ${json.optInt(KEY_SCHEMA, -1)}"
        }
        return NativeOperationAttempt(
            attemptId = json.requireString(KEY_ATTEMPT_ID),
            startedEpochMillis = json.getLong(KEY_STARTED),
            checkpointEpochMillis = json.getLong(KEY_UPDATED),
            operation = NativeOperationKind.valueOf(json.requireString(KEY_OPERATION)),
            checkpoint = NativeOperationCheckpoint.valueOf(json.requireString(KEY_CHECKPOINT)),
            processName = json.requireString(KEY_PROCESS_NAME),
            processId = json.getInt(KEY_PROCESS_ID),
            provider = json.requireString(KEY_PROVIDER),
            targetProfile = json.requireString(KEY_TARGET_PROFILE),
            buildId = json.requireString(KEY_BUILD_ID),
            modelPathFingerprint = json.requireString(KEY_MODEL_FINGERPRINT),
            modelBytes = json.getLong(KEY_MODEL_BYTES),
            transportLibraryBytes = json.getLong(KEY_TRANSPORT_BYTES),
            acceleratorLibraryBytes = json.getLong(KEY_ACCELERATOR_BYTES),
            importerLibraryBytes = json.getLong(KEY_IMPORTER_BYTES),
            cpuImporterBackendBytes = json.getLong(KEY_CPU_IMPORTER_BYTES),
            memory = NativeOperationMemorySnapshot(
                javaHeapUsedBytes = json.getLong(KEY_JAVA_USED),
                javaHeapMaxBytes = json.getLong(KEY_JAVA_MAX),
                nativeHeapAllocatedBytes = json.getLong(KEY_NATIVE_USED),
                systemTotalBytes = json.optLong(KEY_SYSTEM_TOTAL, 0L),
                systemAvailableBytes = json.getLong(KEY_SYSTEM_AVAILABLE),
                systemLowMemoryThresholdBytes = json.getLong(KEY_SYSTEM_THRESHOLD),
                systemLowMemory = json.getBoolean(KEY_SYSTEM_LOW),
                javaCppMaxTrackedBytes = json.optLong(KEY_JAVACPP_MAX_TRACKED, 0L),
                javaCppMaxPhysicalBytes = json.optLong(KEY_JAVACPP_MAX_PHYSICAL, 0L)
            )
        )
    }

    private fun JSONObject.requireString(key: String): String =
        getString(key).takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Native-operation journal is missing '$key'.")

    private fun acceleratorLibraryName(): String = when (BuildConfig.ACCELERATOR_PROVIDER) {
        "google-tensor-g3-nnapi" -> "libnd4jnnapi.so"
        "vulkan-gpu" -> "libnd4jvulkan.so"
        "hexagon-htp" -> "libnd4jhexagon.so"
        else -> "libnd4j.so"
    }

    private companion object {
        const val JOURNAL_DIRECTORY = "native-operation-journal/v1"
        const val JOURNAL_FILE_SUFFIX = ".json"
        const val JOURNAL_SCHEMA = 1
        const val MAX_JOURNAL_BYTES = 64 * 1024
        val ATTEMPT_ID = Regex("[A-Za-z0-9-]{1,80}")

        const val KEY_SCHEMA = "schema"
        const val KEY_ATTEMPT_ID = "attempt_id"
        const val KEY_STARTED = "started"
        const val KEY_UPDATED = "updated"
        const val KEY_OPERATION = "operation"
        const val KEY_CHECKPOINT = "checkpoint"
        const val KEY_PROCESS_NAME = "process_name"
        const val KEY_PROCESS_ID = "process_id"
        const val KEY_PROVIDER = "provider"
        const val KEY_TARGET_PROFILE = "target_profile"
        const val KEY_BUILD_ID = "build_id"
        const val KEY_MODEL_FINGERPRINT = "model_fingerprint"
        const val KEY_MODEL_BYTES = "model_bytes"
        const val KEY_TRANSPORT_BYTES = "transport_bytes"
        const val KEY_ACCELERATOR_BYTES = "accelerator_bytes"
        const val KEY_IMPORTER_BYTES = "importer_bytes"
        const val KEY_CPU_IMPORTER_BYTES = "cpu_importer_bytes"
        const val KEY_JAVA_USED = "java_used"
        const val KEY_JAVA_MAX = "java_max"
        const val KEY_NATIVE_USED = "native_used"
        const val KEY_SYSTEM_TOTAL = "system_total"
        const val KEY_SYSTEM_AVAILABLE = "system_available"
        const val KEY_SYSTEM_THRESHOLD = "system_threshold"
        const val KEY_SYSTEM_LOW = "system_low"
        const val KEY_JAVACPP_MAX_TRACKED = "javacpp_max_tracked"
        const val KEY_JAVACPP_MAX_PHYSICAL = "javacpp_max_physical"
    }
}

internal class NativeOperationTransaction(
    private val journal: NativeOperationJournal,
    initialAttempt: NativeOperationAttempt
) {
    private var attempt = initialAttempt
    private var finished = false

    fun checkpoint(
        checkpoint: NativeOperationCheckpoint,
        processName: String = attempt.processName,
        processId: Int = attempt.processId
    ) {
        check(!finished) { "Native-operation transaction is already finished." }
        attempt = journal.advance(attempt, checkpoint, processName, processId)
    }

    fun snapshot(): NativeOperationAttempt = attempt

    fun complete() {
        finish()
    }

    fun failAndPersist(failure: Throwable) {
        if (finished) return
        journal.persistManagedFailure(attempt.attemptId, failure)?.let { persistedAttempt ->
            attempt = persistedAttempt
        }
        finished = true
    }

    fun recovered() {
        finish()
    }

    private fun finish() {
        if (finished) return
        journal.traceCompletion(attempt)
        journal.clear(attempt.attemptId)
        finished = true
    }
}

internal object NativeOperationCrashRecovery {
    private const val MAX_EXIT_RECORDS = 32
    private const val MAX_TEXT_TRACE_BYTES = 256 * 1024
    private const val MAX_NATIVE_TOMBSTONE_BYTES = 4 * 1024 * 1024
    private const val MAX_RETAINED_NATIVE_TOMBSTONES = 8
    private const val NATIVE_TOMBSTONE_DIRECTORY = "native-crash-dumps"
    private const val EXIT_EVIDENCE_GRACE_MILLIS = 5_000L
    private const val EXIT_EVIDENCE_POLL_MILLIS = 100L

    fun recoverAndPersist(context: Context): List<RecoveredNativeOperation> {
        val journal = NativeOperationJournal(context)
        return journal.loadPending().mapNotNull { initialAttempt ->
            val activeAttempt = journal.loadPending(initialAttempt.attemptId)
                ?: return@mapNotNull null

            // This runs only during a new main-process Application.onCreate. Any journal entry already
            // present therefore belongs to the previous main process. Even if its private importer or
            // runtime worker is still alive for a few milliseconds, this process has no Binder/session
            // handle with which to resume it. Treating that orphan as active loses both Android exit
            // evidence and the exact native checkpoint, leaving the UI with only a generic "interrupted"
            // state.
            val workerExitEvidence = if (isProcessAlive(activeAttempt.processId)) {
                null
            } else {
                awaitExitEvidence(context, activeAttempt)
            }
            val exitEvidence = workerExitEvidence
                ?: awaitMainProcessExitEvidence(context, activeAttempt)

            // The provider owns terminal success/failure. Re-read after the evidence grace so recovery
            // cannot synthesize a crash after the provider durably finalized and cleared the attempt.
            val unfinishedAttempt = journal.loadPending(activeAttempt.attemptId)
                ?: return@mapNotNull null

            val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
                unfinishedAttempt,
                exitEvidence
            )
            ImportDiagnosticStore(context).appendDurably(diagnostic)
            journal.clear(unfinishedAttempt.attemptId)
            RecoveredNativeOperation(unfinishedAttempt, exitEvidence, diagnostic)
        }
    }

    internal fun isProcessAlive(
        processId: Int,
        procRoot: File = File("/proc")
    ): Boolean = processId > 0 && File(procRoot, processId.toString()).isDirectory

    private fun awaitExitEvidence(
        context: Context,
        attempt: NativeOperationAttempt
    ): NativeOperationExitEvidence? {
        val deadline = SystemClock.elapsedRealtime() + EXIT_EVIDENCE_GRACE_MILLIS
        do {
            findExitEvidence(context, attempt)?.let { return it }
            if (isProcessAlive(attempt.processId)) return null
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            SystemClock.sleep(minOf(EXIT_EVIDENCE_POLL_MILLIS, remaining))
        } while (true)
        return findExitEvidence(context, attempt)
    }

    private fun awaitMainProcessExitEvidence(
        context: Context,
        attempt: NativeOperationAttempt
    ): NativeOperationExitEvidence? {
        val deadline = SystemClock.elapsedRealtime() + EXIT_EVIDENCE_GRACE_MILLIS
        do {
            findExitEvidence(
                context = context,
                processName = context.packageName,
                processId = 0,
                startedEpochMillis = attempt.startedEpochMillis
            )?.let { return it }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            SystemClock.sleep(minOf(EXIT_EVIDENCE_POLL_MILLIS, remaining))
        } while (true)
        return findExitEvidence(
            context = context,
            processName = context.packageName,
            processId = 0,
            startedEpochMillis = attempt.startedEpochMillis
        )
    }

    fun recoverAttemptAndPersist(
        context: Context,
        transaction: NativeOperationTransaction,
        managedFailure: Throwable? = null,
        exitEvidence: NativeOperationExitEvidence? = null
    ): RecoveredNativeOperation {
        val attempt = transaction.snapshot()
        val correlatedExit = exitEvidence ?: findExitEvidence(context, attempt)
        val diagnostic = NativeOperationDiagnosticPolicy.createDiagnostic(
            attempt,
            correlatedExit,
            managedFailure
        )
        ImportDiagnosticStore(context).appendDurably(diagnostic)
        transaction.recovered()
        return RecoveredNativeOperation(attempt, correlatedExit, diagnostic)
    }

    private fun findExitEvidence(
        context: Context,
        attempt: NativeOperationAttempt
    ): NativeOperationExitEvidence? = findExitEvidence(
        context = context,
        processName = attempt.processName,
        processId = attempt.processId,
        startedEpochMillis = attempt.startedEpochMillis
    )

    internal fun findExitEvidence(
        context: Context,
        processName: String,
        processId: Int,
        startedEpochMillis: Long
    ): NativeOperationExitEvidence? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val exit = activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            MAX_EXIT_RECORDS
        ).asSequence()
            .filter { info ->
                NativeOperationDiagnosticPolicy.exitMatchesProcess(
                    expectedProcessName = processName,
                    expectedProcessId = processId,
                    startedEpochMillis = startedEpochMillis,
                    processName = info.processName,
                    processId = info.pid,
                    exitTimestampEpochMillis = info.timestamp
                )
            }
            .maxByOrNull(ApplicationExitInfo::getTimestamp)
            ?: return null
        val traceResult = readTrace(context, exit)
        return NativeOperationExitEvidence(
            timestampEpochMillis = exit.timestamp,
            processName = exit.processName,
            processId = exit.pid,
            reason = exit.reason,
            status = exit.status,
            description = exit.description.orEmpty(),
            pssKilobytes = exit.pss,
            rssKilobytes = exit.rss,
            importance = exit.importance,
            trace = traceResult.first,
            traceReadFailure = traceResult.second
        )
    }

    private fun readTrace(context: Context, exit: ApplicationExitInfo): Pair<String, String> {
        val stream = try {
            exit.traceInputStream
        } catch (failure: Throwable) {
            return "" to buildString {
                appendLine("ApplicationExitInfo.getTraceInputStream() failed:")
                append(failure.stackTraceToString())
            }
        } ?: return "" to buildString {
            append(
                "Android returned no exit trace stream for " +
                    NativeOperationDiagnosticPolicy.reasonLabel(exit.reason) + "."
            )
            if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                append(
                    " Native tombstones are retained in a separate OS-wide circular buffer and " +
                        "may already have been overwritten."
                )
            }
        }

        val nativeTombstone = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        val maximumBytes = if (nativeTombstone) {
            MAX_NATIVE_TOMBSTONE_BYTES
        } else {
            MAX_TEXT_TRACE_BYTES
        }
        val failures = mutableListOf<String>()
        val bytes = try {
            stream.use { it.readNBytes(maximumBytes + 1) }
        } catch (failure: Throwable) {
            return "" to buildString {
                appendLine("Reading the Android exit trace stream failed:")
                append(failure.stackTraceToString())
            }
        }
        val traceWasTruncated = bytes.size > maximumBytes
        val capturedBytes = if (traceWasTruncated) bytes.copyOf(maximumBytes) else bytes
        if (traceWasTruncated) {
            failures +=
                "Android's exit trace exceeded the $maximumBytes-byte safety bound. " +
                    "The retained artifact is explicitly truncated; the UI is not presenting it as a complete dump."
        }

        if (!nativeTombstone) {
            val text = String(capturedBytes, StandardCharsets.UTF_8) +
                if (traceWasTruncated) {
                    "\n... Android text exit trace truncated after $maximumBytes bytes"
                } else {
                    ""
                }
            return text to failures.joinToString("\n\n")
        }

        var artifact: NativeTombstoneArtifact? = null
        try {
            artifact = persistNativeTombstone(
                context = context,
                exit = exit,
                bytes = capturedBytes,
                truncated = traceWasTruncated
            )
        } catch (failure: Throwable) {
            failures += buildString {
                appendLine("Persisting the raw native tombstone protobuf failed:")
                append(failure.stackTraceToString())
            }
        }

        val artifactDetails = artifact?.formatForUi(context.packageName).orEmpty()
        val rendered = try {
            AndroidNativeTombstone.render(capturedBytes)
        } catch (failure: Throwable) {
            failures += buildString {
                appendLine("Parsing Android's native tombstone protobuf failed:")
                append(failure.stackTraceToString())
            }
            buildString {
                appendLine("Native tombstone protobuf could not be rendered as structured text.")
                appendLine("Captured bytes: ${capturedBytes.size}")
                append("Binary prefix: ${binaryHexPrefix(capturedBytes, 64)}")
            }
        }
        val trace = buildString {
            if (artifactDetails.isNotBlank()) {
                appendLine(artifactDetails)
                appendLine()
            }
            append(rendered)
        }
        return trace to failures.joinToString("\n\n")
    }

    private fun persistNativeTombstone(
        context: Context,
        exit: ApplicationExitInfo,
        bytes: ByteArray,
        truncated: Boolean
    ): NativeTombstoneArtifact {
        val directory = File(context.noBackupFilesDir, NATIVE_TOMBSTONE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create native tombstone directory: ${directory.absolutePath}")
        }
        val sha256 = sha256Hex(bytes)
        val suffix = if (truncated) "-truncated" else ""
        val file = File(
            directory,
            "tombstone-${exit.timestamp}-${exit.pid}-${sha256.take(16)}$suffix.pb"
        )
        if (!file.exists()) {
            val atomicFile = AtomicFile(file)
            var output: FileOutputStream? = null
            try {
                output = atomicFile.startWrite()
                output.write(bytes)
                output.fd.sync()
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                if (output != null) {
                    try {
                        atomicFile.failWrite(output)
                    } catch (rollbackFailure: Throwable) {
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                throw failure
            }
        }
        if (file.length() != bytes.size.toLong()) {
            throw IOException(
                "Retained native tombstone length mismatch for ${file.name}: " +
                    "expected=${bytes.size}, actual=${file.length()}"
            )
        }

        val retained = directory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith("tombstone-") &&
                candidate.name.endsWith(".pb")
        } ?: throw IOException("Could not enumerate retained native tombstones in ${directory.absolutePath}")
        retained.asSequence()
            .filter { candidate -> candidate.absolutePath != file.absolutePath }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_NATIVE_TOMBSTONES - 1)
            .forEach { obsolete ->
                if (!obsolete.delete()) {
                    throw IOException("Could not prune obsolete native tombstone ${obsolete.name}")
                }
            }

        return NativeTombstoneArtifact(
            fileName = file.name,
            byteCount = bytes.size,
            sha256 = sha256,
            truncated = truncated
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
    }

    private fun binaryHexPrefix(bytes: ByteArray, maximumBytes: Int): String {
        if (bytes.isEmpty()) return "<empty>"
        val count = minOf(bytes.size, maximumBytes)
        val hex = "0123456789abcdef"
        return buildString(count * 2 + 3) {
            for (index in 0 until count) {
                val value = bytes[index].toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
            if (bytes.size > count) append("...")
        }
    }

    private data class NativeTombstoneArtifact(
        val fileName: String,
        val byteCount: Int,
        val sha256: String,
        val truncated: Boolean
    ) {
        fun formatForUi(packageName: String): String = buildString {
            appendLine("Raw native tombstone protobuf retained on device")
            appendLine("App-owned path: no_backup/$NATIVE_TOMBSTONE_DIRECTORY/$fileName")
            appendLine("Bytes: $byteCount")
            appendLine("SHA-256: $sha256")
            appendLine("Complete dump: ${!truncated}")
            append(
                "Debug APK export: adb exec-out run-as $packageName cat " +
                    "no_backup/$NATIVE_TOMBSTONE_DIRECTORY/$fileName > $fileName"
            )
        }
    }
}
