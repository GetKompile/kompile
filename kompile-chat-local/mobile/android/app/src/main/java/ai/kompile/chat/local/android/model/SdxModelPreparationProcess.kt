package ai.kompile.chat.local.android.model

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationCrashRecovery
import ai.kompile.chat.local.android.diagnostics.NativeOperationExitEvidence
import ai.kompile.chat.local.android.diagnostics.NativeOperationJournal
import ai.kompile.chat.local.android.diagnostics.NativeOperationKind
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val IMPORTER_PROCESS_SUFFIX = ":sdx_model_import"
private const val MSG_PID = 1
private const val MSG_PREPARE = 2
private const val EVENT_RESPONSE = 100
private const val EVENT_PROGRESS = 101
private const val KEY_REQUEST_ID = "request_id"
private const val KEY_PREPARATION_STAGE = "preparation_stage"
private const val KEY_PID = "pid"
private const val KEY_MODEL_PATH = "model_path"
private const val KEY_VERIFIED_SHA256 = "verified_sha256"
private const val KEY_VERIFIED_BYTES = "verified_bytes"
private const val KEY_HAS_VERIFIED_BYTES = "has_verified_bytes"
private const val KEY_OPERATION_ATTEMPT_ID = "operation_attempt_id"
private const val KEY_OPERATION_TERMINAL = "operation_terminal"
private const val KEY_SUCCESS = "success"
private const val KEY_FAILURE_CLASS = "failure_class"
private const val KEY_FAILURE_MESSAGE = "failure_message"
private const val KEY_FAILURE_STACK = "failure_stack"
private const val KEY_CACHE_HIT = "cache_hit"
private const val KEY_SOURCE_SHA256 = "source_sha256"
private const val KEY_SOURCE_BYTES = "source_bytes"
private const val KEY_CANONICAL_SDZ_LOGICAL_SHA256 = "canonical_sdz_logical_sha256"
private const val KEY_CANONICAL_SDZ_LOGICAL_BYTES = "canonical_sdz_logical_bytes"
private const val KEY_CANONICAL_SDZ_PATH = "canonical_sdz_path"
private const val KEY_CANONICAL_SDZ_BYTES = "canonical_sdz_bytes"
private const val KEY_RUNTIME_MODEL_PATH = "runtime_model_path"
private const val KEY_TOKENIZER_PATH = "tokenizer_path"
private const val KEY_COMPILE_KEY = "compile_key"
private const val KEY_TARGET_PROFILE = "target_profile"
private const val KEY_TARGET_SOC = "target_soc"
private const val KEY_CONTEXT_LENGTH = "context_length"
private const val KEY_MAX_PREFILL_LENGTH = "max_prefill_length"
private const val IMPORTER_BIND_TIMEOUT_MILLIS = 30_000L
private const val IMPORTER_EXIT_WAIT_MILLIS = 10_000L
private const val IMPORTER_CALL_TIMEOUT_MILLIS = 60L * 60L * 1_000L
private const val IMPORTER_CALL_POLL_MILLIS = 250L
private const val EXIT_EVIDENCE_WAIT_MILLIS = 5_000L
private const val PROCESS_POLL_MILLIS = 25L
private const val MAX_REMOTE_STACK_CHARS = 512 * 1024

internal fun sdxImporterProcessName(packageName: String): String =
    packageName + IMPORTER_PROCESS_SUFFIX

internal fun isFrameworkOnlySdxWireValueClass(valueClass: Class<*>): Boolean =
    valueClass == String::class.java ||
        valueClass == Boolean::class.javaObjectType ||
        valueClass == Long::class.javaObjectType ||
        valueClass == Int::class.javaObjectType

@Suppress("DEPRECATION")
internal fun requireFrameworkOnlySdxWireBundle(bundle: Bundle, description: String): Bundle {
    bundle.keySet().forEach { key ->
        val value = bundle.get(key)
        require(value == null || isFrameworkOnlySdxWireValueClass(value.javaClass)) {
            "$description contains non-framework IPC value '$key' of type ${value?.javaClass?.name}."
        }
    }
    return bundle
}

internal fun buildSdxModelPreparationRequest(
    modelPath: String,
    verifiedSourceSha256: String?,
    verifiedSourceBytes: Long?,
    operationAttemptId: String
): Bundle = requireFrameworkOnlySdxWireBundle(
    Bundle().apply {
        putString(KEY_MODEL_PATH, modelPath)
        putString(KEY_VERIFIED_SHA256, verifiedSourceSha256)
        putBoolean(KEY_HAS_VERIFIED_BYTES, verifiedSourceBytes != null)
        if (verifiedSourceBytes != null) putLong(KEY_VERIFIED_BYTES, verifiedSourceBytes)
        putString(KEY_OPERATION_ATTEMPT_ID, operationAttemptId)
    },
    "SDX model preparation request"
)

/**
 * Wire payload kept independent of Android Bundle so its exact round-trip remains host-testable.
 */
internal data class PreparedModelPayload(
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
    val maxPrefillLength: Int
) {
    fun toPreparedModelInfo(): PreparedModelInfo = PreparedModelInfo(
        cacheHit = cacheHit,
        sourceSha256 = sourceSha256,
        sourceBytes = sourceBytes,
        canonicalSdzLogicalSha256 = canonicalSdzLogicalSha256,
        canonicalSdzLogicalBytes = canonicalSdzLogicalBytes,
        canonicalSdzPath = canonicalSdzPath,
        canonicalSdzBytes = canonicalSdzBytes,
        modelPath = modelPath,
        tokenizerPath = tokenizerPath,
        compileKey = compileKey,
        targetProfile = targetProfile,
        targetSoc = targetSoc,
        contextLength = contextLength,
        maxPrefillLength = maxPrefillLength
    )

    companion object {
        fun from(info: PreparedModelInfo): PreparedModelPayload = PreparedModelPayload(
            cacheHit = info.cacheHit,
            sourceSha256 = info.sourceSha256,
            sourceBytes = info.sourceBytes,
            canonicalSdzLogicalSha256 = info.canonicalSdzLogicalSha256,
            canonicalSdzLogicalBytes = info.canonicalSdzLogicalBytes,
            canonicalSdzPath = info.canonicalSdzPath,
            canonicalSdzBytes = info.canonicalSdzBytes,
            modelPath = info.modelPath,
            tokenizerPath = info.tokenizerPath,
            compileKey = info.compileKey,
            targetProfile = info.targetProfile,
            targetSoc = info.targetSoc,
            contextLength = info.contextLength,
            maxPrefillLength = info.maxPrefillLength
        )
    }
}

/**
 * Main-process client for the app-private GGUF importer process.
 *
 * The Graal native image side-loads the CPU JavaCPP/ND4J JNI libraries. Those libraries cache a
 * JavaVM and JNI class references, so they must never be initialized in ART's accelerator process.
 * A bound important service supervises exactly one request without making Android kill the main
 * app when native importer code aborts. The binding keeps a long conversion out of cached-process
 * state; after the result is durably cached, the importer is terminated and observed dead before
 * the caller initializes accelerator code.
 */
internal object SdxModelPreparationClient {

    fun prepare(
        context: Context,
        model: File,
        verifiedSourceSha256: String?,
        verifiedSourceBytes: Long?,
        onPreparationStage: (PreparationStage) -> Unit
    ): PreparedModelInfo {
        val applicationContext = context.applicationContext
        val processName = sdxImporterProcessName(applicationContext.packageName)
        check(Process.myPid() != importerPidIfCurrentProcess(processName)) {
            "GGUF preparation client cannot run inside the importer process."
        }
        val connection = SdxModelPreparationConnection.bind(
            applicationContext,
            onPreparationStage
        )
        var pid = -1
        try {
            pid = connection.requireRemotePid()
            check(pid != Process.myPid()) {
                "SDX importer service was not isolated: importer pid=$pid app pid=${Process.myPid()}"
            }
            val operation = NativeOperationJournal(applicationContext).begin(
                modelPath = model.absolutePath,
                operation = NativeOperationKind.SDX_MODEL_PREPARATION,
                checkpoint = NativeOperationCheckpoint.START_IMPORTER_PROCESS,
                processName = processName,
                processId = pid
            )
            var primaryFailure: Throwable? = null
            try {
                val request = buildSdxModelPreparationRequest(
                    modelPath = model.absolutePath,
                    verifiedSourceSha256 = verifiedSourceSha256,
                    verifiedSourceBytes = verifiedSourceBytes,
                    operationAttemptId = operation.snapshot().attemptId
                )
                val response = try {
                    val returned = connection.prepare(request, pid)
                    requireFrameworkOnlySdxWireBundle(
                        returned,
                        "SDX model preparation response"
                    ).also { finalizedResponse ->
                        if (!finalizedResponse.getBoolean(KEY_OPERATION_TERMINAL, false)) {
                            throw ChatException(
                                "The SDX importer returned before durably finalizing its operation journal."
                            )
                        }
                    }
                } catch (failure: Exception) {
                    throw importerProcessFailure(
                        applicationContext,
                        processName,
                        pid,
                        operation,
                        failure
                    )
                }

                decodeFailure(response, processName, pid)?.let { throw it }
                return decodePreparedModel(response)
            } catch (failure: Throwable) {
                primaryFailure = failure
                try {
                    operation.failAndPersist(failure)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            } finally {
                retireImporter(connection, pid)?.let { teardownFailure ->
                    val failure = primaryFailure
                    if (failure == null) {
                        throw teardownFailure
                    }
                    failure.addSuppressed(teardownFailure)
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun importerPidIfCurrentProcess(expectedProcessName: String): Int =
        if (android.app.Application.getProcessName() == expectedProcessName) Process.myPid() else -1

    private fun retireImporter(
        connection: SdxModelPreparationConnection,
        pid: Int
    ): ChatException? {
        connection.close()
        if (pid <= 0 || awaitProcessExit(pid)) return null
        return ChatException(
            "The SDX importer process $pid did not terminate within " +
                "$IMPORTER_EXIT_WAIT_MILLIS ms; accelerator loading was stopped to avoid retaining " +
                "the CPU importer and accelerator runtimes together."
        )
    }

    private fun awaitProcessExit(pid: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + IMPORTER_EXIT_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            SystemClock.sleep(PROCESS_POLL_MILLIS)
        }
        return !File("/proc/$pid").exists()
    }

    private fun reloadPersistedOperation(
        context: Context,
        operation: NativeOperationTransaction,
        failure: Throwable
    ): NativeOperationTransaction? = try {
        NativeOperationJournal(context).resumeOrNull(operation.snapshot().attemptId)
    } catch (journalFailure: Throwable) {
        failure.addSuppressed(journalFailure)
        operation
    }

    private fun importerProcessFailure(
        context: Context,
        processName: String,
        pid: Int,
        operation: NativeOperationTransaction,
        cause: Exception
    ): ChatException {
        // The importer advances this record from another process. Never diagnose or clear the stale
        // main-process snapshot: reload the atomically persisted checkpoint so a death in conversion,
        // validation, or teardown is attributed to that exact boundary.
        val persistedOperation = reloadPersistedOperation(context, operation, cause)
            ?: return ChatException(
                buildString {
                    appendLine(
                        "The SDX importer Binder call ended after the service durably finalized " +
                            "the model-preparation attempt."
                    )
                    appendLine(
                        "No native-crash diagnostic was synthesized because the operation journal " +
                            "was already terminal."
                    )
                    append(
                        "Retrying reuses either the committed content-addressed SDZ cache or the " +
                            "service-persisted full failure diagnostic."
                    )
                },
                cause
            )
        val attempt = persistedOperation.snapshot()
        val evidence = awaitExitEvidence(
            context,
            processName,
            pid,
            attempt.startedEpochMillis
        )
        val recovered = NativeOperationCrashRecovery.recoverAttemptAndPersist(
            context = context,
            transaction = persistedOperation,
            managedFailure = cause,
            exitEvidence = evidence
        )
        return ChatException(
            buildString {
                appendLine(recovered.diagnostic.summary)
                append(recovered.diagnostic.technicalDetails)
            },
            cause
        )
    }

    private fun awaitExitEvidence(
        context: Context,
        processName: String,
        pid: Int,
        startedEpochMillis: Long
    ): NativeOperationExitEvidence? {
        val deadline = SystemClock.elapsedRealtime() + EXIT_EVIDENCE_WAIT_MILLIS
        do {
            NativeOperationCrashRecovery.findExitEvidence(
                context,
                processName,
                pid,
                startedEpochMillis
            )?.let { return it }
            SystemClock.sleep(PROCESS_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return NativeOperationCrashRecovery.findExitEvidence(
            context,
            processName,
            pid,
            startedEpochMillis
        )
    }

    private fun decodeFailure(response: Bundle, processName: String, pid: Int): ChatException? {
        if (response.getBoolean(KEY_SUCCESS, false)) return null
        val failureClass = response.getString(KEY_FAILURE_CLASS).orEmpty()
            .ifBlank { "unknown remote exception" }
        val failureMessage = response.getString(KEY_FAILURE_MESSAGE).orEmpty()
        val remoteStack = response.getString(KEY_FAILURE_STACK).orEmpty()
        return ChatException(
            buildString {
                appendLine("The app-private SDX importer process failed.")
                appendLine("Process: $processName")
                appendLine("PID: $pid")
                appendLine("Remote exception: $failureClass")
                if (failureMessage.isNotBlank()) appendLine("Remote message: $failureMessage")
                appendLine("Remote full stack trace:")
                append(remoteStack.ifBlank { "No remote stack trace was returned." })
            }
        )
    }

    private fun decodePreparedModel(response: Bundle): PreparedModelInfo = PreparedModelPayload(
        cacheHit = response.getBoolean(KEY_CACHE_HIT),
        sourceSha256 = response.requireString(KEY_SOURCE_SHA256),
        sourceBytes = response.getLong(KEY_SOURCE_BYTES),
        canonicalSdzLogicalSha256 = response.requireString(KEY_CANONICAL_SDZ_LOGICAL_SHA256),
        canonicalSdzLogicalBytes = response.getLong(KEY_CANONICAL_SDZ_LOGICAL_BYTES),
        canonicalSdzPath = response.requireString(KEY_CANONICAL_SDZ_PATH),
        canonicalSdzBytes = response.getLong(KEY_CANONICAL_SDZ_BYTES),
        modelPath = response.requireString(KEY_RUNTIME_MODEL_PATH),
        tokenizerPath = response.requireString(KEY_TOKENIZER_PATH),
        compileKey = response.requireString(KEY_COMPILE_KEY),
        targetProfile = response.requireString(KEY_TARGET_PROFILE),
        targetSoc = response.requireString(KEY_TARGET_SOC),
        contextLength = response.getInt(KEY_CONTEXT_LENGTH),
        maxPrefillLength = response.getInt(KEY_MAX_PREFILL_LENGTH)
    ).toPreparedModelInfo()

    private fun Bundle.requireString(key: String): String =
        getString(key)?.takeIf(String::isNotBlank)
            ?: throw ChatException("The SDX importer result omitted '$key'.")
}

/**
 * Main-process supervision for the app-private importer service.
 *
 * The BIND_IMPORTANT relationship is deliberate: conversion is a user-visible, bounded operation
 * that performs native and driver Binder work. Keeping that relationship active prevents Android
 * from classifying the worker as cached while the conversion is still in progress.
 */
private class SdxModelPreparationConnection private constructor(
    private val context: Context,
    private val onPreparationStage: (PreparationStage) -> Unit
) : ServiceConnection, IBinder.DeathRecipient, AutoCloseable {
    private val connected = CountDownLatch(1)
    private val connectionFailure = AtomicReference<Throwable?>()
    private val pending = ConcurrentHashMap<String, PendingReply>()
    private val replyThread = HandlerThread("sdx-importer-replies").apply { start() }
    private val replyMessenger = Messenger(Handler(replyThread.looper) { message ->
        handleReply(message)
        true
    })
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    @Volatile
    private var remoteBinder: IBinder? = null

    @Volatile
    private var remoteMessenger: Messenger? = null

    @Volatile
    private var remotePid: Int = -1

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        try {
            service.linkToDeath(this, 0)
            remoteBinder = service
            remoteMessenger = Messenger(service)
        } catch (failure: RemoteException) {
            connectionFailure.compareAndSet(null, failure)
        } finally {
            connected.countDown()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        markDead(ChatException("Android disconnected the app-private SDX importer service."))
    }

    override fun onBindingDied(name: ComponentName) {
        markDead(ChatException("Android reported that the SDX importer service binding died."))
    }

    override fun onNullBinding(name: ComponentName) {
        markDead(ChatException("The SDX importer service returned a null Binder."))
        connected.countDown()
    }

    override fun binderDied() {
        markDead(ChatException("The app-private SDX importer process Binder died."))
    }

    fun requireRemotePid(): Int {
        val reply = request(
            method = MSG_PID,
            data = Bundle(),
            timeoutMillis = IMPORTER_BIND_TIMEOUT_MILLIS,
            processId = -1
        )
        val pid = reply.getInt(KEY_PID, -1)
        if (pid <= 0) throw ChatException("The SDX importer service returned an invalid pid: $pid")
        remotePid = pid
        return pid
    }

    fun prepare(data: Bundle, processId: Int): Bundle = request(
        method = MSG_PREPARE,
        data = data,
        timeoutMillis = IMPORTER_CALL_TIMEOUT_MILLIS,
        processId = processId
    )

    private fun request(
        method: Int,
        data: Bundle,
        timeoutMillis: Long,
        processId: Int
    ): Bundle {
        awaitConnected()
        val requestId = UUID.randomUUID().toString()
        val request = requireFrameworkOnlySdxWireBundle(
            Bundle(data).apply { putString(KEY_REQUEST_ID, requestId) },
            "SDX importer service request"
        )
        val pendingReply = PendingReply()
        check(pending.putIfAbsent(requestId, pendingReply) == null)
        try {
            val remote = remoteMessenger
                ?: throw connectionFailure.get()?.asException()
                ?: ChatException("The SDX importer service has no active Binder.")
            remote.send(Message.obtain().apply {
                what = method
                this.data = request
                replyTo = replyMessenger
            })
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (true) {
                if (pendingReply.completed.await(IMPORTER_CALL_POLL_MILLIS, TimeUnit.MILLISECONDS)) break
                connectionFailure.get()?.let { throw it.asException() }
                val watchedPid = if (processId > 0) processId else remotePid
                if (watchedPid > 0 && !File("/proc/$watchedPid").exists()) {
                    throw ChatException(
                        "The app-private SDX importer process $watchedPid disappeared during IPC method $method."
                    )
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    if (watchedPid > 0 && File("/proc/$watchedPid").exists()) {
                        Process.killProcess(watchedPid)
                    }
                    throw ChatException(
                        "The app-private SDX importer did not finish IPC method $method within " +
                            "$timeoutMillis ms. Android terminated that worker; the verified model " +
                            "and any completely committed content-addressed SDZ remain reusable."
                    )
                }
            }
            connectionFailure.get()?.let { throw it.asException() }
            val response = pendingReply.response
                ?: throw ChatException("The SDX importer service completed without a response bundle.")
            return requireFrameworkOnlySdxWireBundle(response, "SDX importer service response")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatException("Waiting for the SDX importer process was interrupted.", interrupted)
        } catch (failure: RemoteException) {
            throw ChatException("The SDX importer Binder request failed.", failure)
        } finally {
            pending.remove(requestId)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            remoteBinder?.unlinkToDeath(this, 0)
        } catch (_: Throwable) {
            // Binder may already be dead; unbinding and stopping the disposable process still applies.
        }
        pending.values.forEach { it.completed.countDown() }
        try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
            // Binding may have failed before Android registered the connection.
        }
        context.stopService(Intent(context, SdxModelPreparationService::class.java))
        replyThread.quitSafely()
    }

    private fun awaitConnected() {
        if (!connected.await(IMPORTER_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            close()
            throw ChatException(
                "Android did not connect the app-private SDX importer service within " +
                    "$IMPORTER_BIND_TIMEOUT_MILLIS ms."
            )
        }
        connectionFailure.get()?.let { throw it.asException() }
    }

    private fun handleReply(message: Message) {
        val data = message.data ?: return
        val requestId = data.getString(KEY_REQUEST_ID) ?: return
        val target = pending[requestId] ?: return
        when (message.what) {
            EVENT_PROGRESS -> {
                val ordinal = data.getInt(KEY_PREPARATION_STAGE, -1)
                val stage = PreparationStage.values().getOrNull(ordinal) ?: return
                mainHandler.post { onPreparationStage(stage) }
            }
            EVENT_RESPONSE -> {
                target.response = data
                target.completed.countDown()
            }
        }
    }

    private fun markDead(failure: Throwable) {
        connectionFailure.compareAndSet(null, failure)
        connected.countDown()
        pending.values.forEach { it.completed.countDown() }
    }

    private fun Throwable.asException(): Exception =
        this as? Exception ?: ChatException("The SDX importer process failed.", this)

    private class PendingReply {
        val completed = CountDownLatch(1)

        @Volatile
        var response: Bundle? = null
    }

    companion object {
        fun bind(
            context: Context,
            onPreparationStage: (PreparationStage) -> Unit
        ): SdxModelPreparationConnection {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "SDX importer service binding must run off Android's UI thread."
            }
            val appContext = context.applicationContext
            val connection = SdxModelPreparationConnection(appContext, onPreparationStage)
            val bound = appContext.bindService(
                Intent(appContext, SdxModelPreparationService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            if (!bound) {
                connection.close()
                throw ChatException("Android refused to bind the app-private SDX importer service.")
            }
            connection.awaitConnected()
            return connection
        }
    }
}

/**
 * Non-exported, same-UID service hosted in :sdx_model_import. Its single executor owns the complete
 * GGUF-to-SDZ operation. The bound-service lifetime keeps the worker important for the entire native
 * conversion and the process is discarded after the immutable result or terminal diagnostic is sent.
 */
class SdxModelPreparationService : Service() {
    private val ownerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sdx-importer-owner").apply { isDaemon = false }
    }
    private val activePreparation = AtomicBoolean(false)
    private val inboundMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { request ->
            handleRequest(request)
            true
        })
    }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == sdxImporterProcessName(packageName)) {
            "SDX model preparation service is not running in its declared app-private process."
        }
    }

    override fun onBind(intent: Intent?): IBinder = inboundMessenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        // This process has loaded Graal, JavaCPP, and CPU importer JNI state. It is intentionally
        // disposable and must be gone before the caller starts the accelerator runtime.
        val pid = Process.myPid()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(pid) }, 100L)
        return false
    }

    override fun onDestroy() {
        ownerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleRequest(request: Message) {
        // Handler dispatch returns before owner-thread work runs. Android then recycles Message, so
        // every field and the Bundle must be detached before anything is queued.
        val method = request.what
        val sendingUid = request.sendingUid
        val replyTo = request.replyTo ?: return
        val wireData = Bundle(request.data)
        val requestData = try {
            requireFrameworkOnlySdxWireBundle(wireData, "SDX importer service request")
        } catch (failure: Throwable) {
            sendResponse(
                replyTo,
                wireData.getString(KEY_REQUEST_ID).orEmpty(),
                failureBundle(failure, operationTerminal = false)
            )
            return
        }
        val requestId = requestData.getString(KEY_REQUEST_ID).orEmpty()
        if (sendingUid != Process.myUid()) {
            sendResponse(
                replyTo,
                requestId,
                failureBundle(
                    IllegalStateException("Only this application may invoke the SDX importer service."),
                    operationTerminal = false
                )
            )
            return
        }
        when (method) {
            MSG_PID -> sendResponse(
                replyTo,
                requestId,
                Bundle().apply {
                    putBoolean(KEY_SUCCESS, true)
                    putInt(KEY_PID, Process.myPid())
                }
            )
            MSG_PREPARE -> executePreparation(replyTo, requestId, requestData)
            else -> sendResponse(
                replyTo,
                requestId,
                failureBundle(
                    IllegalArgumentException("Unsupported SDX importer IPC method: $method"),
                    operationTerminal = false
                )
            )
        }
    }

    private fun executePreparation(
        replyTo: Messenger,
        requestId: String,
        requestData: Bundle
    ) {
        if (!activePreparation.compareAndSet(false, true)) {
            sendResponse(
                replyTo,
                requestId,
                failureBundle(
                    IllegalStateException("The SDX importer already owns a model-preparation request."),
                    operationTerminal = false
                )
            )
            return
        }
        ownerExecutor.execute {
            val response = try {
                prepare(requestData, replyTo, requestId)
            } catch (failure: Throwable) {
                failureBundle(failure, operationTerminal = false)
            }
            sendResponse(replyTo, requestId, response)
            activePreparation.set(false)
        }
    }

    private fun prepare(
        extras: Bundle,
        replyTo: Messenger,
        requestId: String
    ): Bundle {
        requireFrameworkOnlySdxWireBundle(extras, "SDX model preparation service request")
        val serviceContext = applicationContext
        check(Application.getProcessName() == sdxImporterProcessName(serviceContext.packageName)) {
            "SDX model preparation service is not running in its declared private process."
        }
        val modelPath = extras.getString(KEY_MODEL_PATH)
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("SDX importer request omitted the model path.")
        val verifiedBytes = if (extras.getBoolean(KEY_HAS_VERIFIED_BYTES, false)) {
            extras.getLong(KEY_VERIFIED_BYTES)
        } else {
            null
        }
        val operationId = extras.getString(KEY_OPERATION_ATTEMPT_ID)
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(
                "SDX importer request omitted the native-operation attempt id."
            )
        val operation = NativeOperationJournal(serviceContext).resume(operationId)
        check(operation.snapshot().processName == Application.getProcessName()) {
            "SDX importer journal targets ${operation.snapshot().processName}, not " +
                Application.getProcessName()
        }
        check(operation.snapshot().processId == Process.myPid()) {
            "SDX importer journal targets pid=${operation.snapshot().processId}, " +
                "not pid=${Process.myPid()}"
        }
        return try {
            val prepared = SdxGgufModelImporter.prepareInImporterProcess(
                serviceContext,
                modelPath,
                extras.getString(KEY_VERIFIED_SHA256),
                verifiedBytes,
                operation
            ) { stage ->
                sendProgress(replyTo, requestId, stage)
            }
            val response = successBundle(prepared)
            operation.complete()
            response
        } catch (failure: Exception) {
            failureBundleAfterPersistingTerminalState(operation, failure)
        } catch (failure: LinkageError) {
            failureBundleAfterPersistingTerminalState(operation, failure)
        } catch (failure: OutOfMemoryError) {
            failureBundleAfterPersistingTerminalState(operation, failure)
        }
    }

    private fun failureBundleAfterPersistingTerminalState(
        operation: NativeOperationTransaction,
        failure: Throwable
    ): Bundle {
        return try {
            operation.failAndPersist(failure)
            failureBundle(failure, operationTerminal = true)
        } catch (persistenceFailure: Throwable) {
            failure.addSuppressed(persistenceFailure)
            failureBundle(failure, operationTerminal = false)
        }
    }

    private fun successBundle(info: PreparedModelInfo): Bundle {
        val payload = PreparedModelPayload.from(info)
        return Bundle().apply {
            putBoolean(KEY_OPERATION_TERMINAL, true)
            putBoolean(KEY_SUCCESS, true)
            putInt(KEY_PID, Process.myPid())
            putBoolean(KEY_CACHE_HIT, payload.cacheHit)
            putString(KEY_SOURCE_SHA256, payload.sourceSha256)
            putLong(KEY_SOURCE_BYTES, payload.sourceBytes)
            putString(
                KEY_CANONICAL_SDZ_LOGICAL_SHA256,
                payload.canonicalSdzLogicalSha256
            )
            putLong(KEY_CANONICAL_SDZ_LOGICAL_BYTES, payload.canonicalSdzLogicalBytes)
            putString(KEY_CANONICAL_SDZ_PATH, payload.canonicalSdzPath)
            putLong(KEY_CANONICAL_SDZ_BYTES, payload.canonicalSdzBytes)
            putString(KEY_RUNTIME_MODEL_PATH, payload.modelPath)
            putString(KEY_TOKENIZER_PATH, payload.tokenizerPath)
            putString(KEY_COMPILE_KEY, payload.compileKey)
            putString(KEY_TARGET_PROFILE, payload.targetProfile)
            putString(KEY_TARGET_SOC, payload.targetSoc)
            putInt(KEY_CONTEXT_LENGTH, payload.contextLength)
            putInt(KEY_MAX_PREFILL_LENGTH, payload.maxPrefillLength)
        }
    }

    private fun failureBundle(failure: Throwable, operationTerminal: Boolean): Bundle {
        val fullStack = failure.stackTraceToString()
        val boundedStack = if (fullStack.length <= MAX_REMOTE_STACK_CHARS) {
            fullStack
        } else {
            fullStack.take(MAX_REMOTE_STACK_CHARS) +
                "\n... remote stack trace exceeded $MAX_REMOTE_STACK_CHARS characters"
        }
        return Bundle().apply {
            putBoolean(KEY_OPERATION_TERMINAL, operationTerminal)
            putBoolean(KEY_SUCCESS, false)
            putInt(KEY_PID, Process.myPid())
            putString(KEY_FAILURE_CLASS, failure.javaClass.name)
            putString(KEY_FAILURE_MESSAGE, failure.message.orEmpty())
            putString(KEY_FAILURE_STACK, boundedStack)
        }
    }

    private fun sendProgress(
        replyTo: Messenger,
        requestId: String,
        stage: PreparationStage
    ) {
        sendReply(
            replyTo,
            EVENT_PROGRESS,
            Bundle().apply { putInt(KEY_PREPARATION_STAGE, stage.ordinal) },
            requestId
        )
    }

    private fun sendResponse(
        replyTo: Messenger,
        requestId: String,
        response: Bundle
    ) {
        sendReply(replyTo, EVENT_RESPONSE, response, requestId)
    }

    private fun sendReply(
        replyTo: Messenger,
        event: Int,
        payload: Bundle,
        requestId: String
    ) {
        val wireData = requireFrameworkOnlySdxWireBundle(
            Bundle(payload).apply { putString(KEY_REQUEST_ID, requestId) },
            "SDX importer service reply"
        )
        try {
            replyTo.send(Message.obtain().apply {
                what = event
                data = wireData
            })
        } catch (_: RemoteException) {
            // The supervising binding owns process lifetime; onUnbind disposes this worker.
        }
    }
}
