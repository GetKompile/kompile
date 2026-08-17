package ai.kompile.chat.local.android.model

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message as AndroidMessage
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.ChatRequest
import ai.kompile.chat.local.ChatResponse
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationCrashRecovery
import ai.kompile.chat.local.android.diagnostics.NativeOperationExitEvidence
import ai.kompile.chat.local.android.diagnostics.NativeOperationJournal
import ai.kompile.chat.local.android.diagnostics.NativeOperationKind
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import ai.kompile.chat.local.android.diagnostics.SmokeDecodeTraceLog
import ai.kompile.graph.reasoning.unified.MiniJson
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.math.max

private const val RUNTIME_PROCESS_SUFFIX = ":sdx_model_runtime"
private const val MSG_PID = 1
private const val MSG_OPEN = 2
private const val MSG_GENERATE = 3
private const val MSG_CANCEL = 4
private const val MSG_CLOSE = 5
private const val EVENT_RESPONSE = 100
private const val EVENT_CHUNK = 101
private const val KEY_REQUEST_ID = "request_id"
private const val KEY_OPERATION_ATTEMPT_ID = "operation_attempt_id"
private const val KEY_PID = "pid"
private const val KEY_HAS_ACTIVE_SESSION = "has_active_session"
private const val KEY_WORKER_RETIRING = "worker_retiring"
private const val KEY_SUCCESS = "success"
private const val KEY_FAILURE_CLASS = "failure_class"
private const val KEY_FAILURE_MESSAGE = "failure_message"
private const val KEY_FAILURE_STACK = "failure_stack"
private const val KEY_MODEL_PATH = "model_path"
private const val KEY_DIAGNOSTIC_MODEL_PATH = "diagnostic_model_path"
private const val KEY_DIAGNOSTIC_MODE = "diagnostic_mode"
private const val KEY_ROUTE_NAME = "route_name"
private const val KEY_MODEL_ID_PREFIX = "model_id_prefix"
private const val KEY_SESSION_ID = "session_id"
private const val KEY_MODEL_ID = "model_id"
private const val KEY_CHAT_REQUEST_JSON = "chat_request_json"
private const val KEY_STRUCTURED_RESPONSE_JSON = "structured_response_json"
private const val KEY_OPTIONS_JSON = "options_json"
private const val KEY_CHUNK = "chunk"
private const val SERVICE_BIND_TIMEOUT_MILLIS = 30_000L
private const val SERVICE_OPEN_TIMEOUT_MILLIS = 15L * 60L * 1_000L
private const val SERVICE_MIN_GENERATE_TIMEOUT_MILLIS = 5L * 60L * 1_000L
private const val SERVICE_MAX_GENERATE_TIMEOUT_MILLIS = 2L * 60L * 60L * 1_000L
private const val SERVICE_TOKEN_TIMEOUT_MILLIS = 15_000L
private const val SERVICE_CONTROL_TIMEOUT_MILLIS = 60_000L
private const val SERVICE_POLL_MILLIS = 100L
private const val EXIT_EVIDENCE_WAIT_MILLIS = 5_000L
private const val MAX_CLEAN_WORKER_BIND_ATTEMPTS = 3
private const val MAX_REMOTE_STACK_CHARS = 512 * 1024
private const val TAG = "SdxRuntimeProcess"

// Service objects can be recreated in the same isolated process before queued process death runs.
// Retirement therefore belongs to the process, not to one Service instance.
private val sdxRuntimeWorkerRetiring = AtomicBoolean(false)

internal fun sdxRuntimeProcessName(packageName: String): String =
    packageName + RUNTIME_PROCESS_SUFFIX

internal data class SdxRuntimeWorkerState(
    val pid: Int,
    val ownsModelSession: Boolean,
    val retiring: Boolean
)

internal fun sdxRuntimeWorkerMustRestartBeforeOpen(state: SdxRuntimeWorkerState): Boolean =
    state.ownsModelSession || state.retiring

private fun isFrameworkOnlyRuntimeWireValueClass(valueClass: Class<*>): Boolean =
    valueClass == String::class.java ||
        valueClass == Boolean::class.javaObjectType ||
        valueClass == Long::class.javaObjectType ||
        valueClass == Int::class.javaObjectType

@Suppress("DEPRECATION")
private fun requireFrameworkOnlyRuntimeWireBundle(bundle: Bundle, description: String): Bundle {
    bundle.keySet().forEach { key ->
        val value = bundle.get(key)
        require(value == null || isFrameworkOnlyRuntimeWireValueClass(value.javaClass)) {
            "$description contains non-framework IPC value '$key' of type ${value?.javaClass?.name}."
        }
    }
    return bundle
}

/** Main-process proxy. Native model state exists only in [SdxRuntimeService]. */
internal object SdxPlatformChatSession {

    fun open(
        context: Context,
        modelPath: String,
        diagnosticModelPath: String = modelPath,
        routeName: String,
        modelIdPrefix: String,
        diagnosticMode: ModelDiagnosticMode = ModelDiagnosticMode.STANDARD
    ): PlatformLocalChatSession {
        val applicationContext = context.applicationContext
        check(Application.getProcessName() != sdxRuntimeProcessName(applicationContext.packageName)) {
            "The SDX runtime IPC client cannot run inside its own worker process."
        }
        val (connection, workerState) =
            SdxRuntimeConnection.bindForNewSession(applicationContext)
        try {
            val pid = workerState.pid
            val processName = sdxRuntimeProcessName(applicationContext.packageName)
            check(pid != Process.myPid()) {
                "SDX runtime service was not isolated: runtime pid=$pid app pid=${Process.myPid()}"
            }
            val operation = NativeOperationJournal(applicationContext).begin(
                modelPath = diagnosticModelPath,
                operation = NativeOperationKind.SDX_MODEL_LOAD,
                checkpoint = NativeOperationCheckpoint.RESOLVE_MODEL_ASSETS,
                processName = processName,
                processId = pid
            )
            val request = Bundle().apply {
                putString(KEY_MODEL_PATH, modelPath)
                putString(KEY_DIAGNOSTIC_MODEL_PATH, diagnosticModelPath)
                putString(KEY_DIAGNOSTIC_MODE, diagnosticMode.name)
                putString(KEY_ROUTE_NAME, routeName)
                putString(KEY_MODEL_ID_PREFIX, modelIdPrefix)
                putString(KEY_OPERATION_ATTEMPT_ID, operation.snapshot().attemptId)
            }
            val response = executeJournaledRequest(
                applicationContext,
                connection,
                processName,
                pid,
                operation,
                MSG_OPEN,
                request,
                SERVICE_OPEN_TIMEOUT_MILLIS,
                null
            )
            val sessionId = response.requireString(KEY_SESSION_ID)
            return RemoteSession(
                applicationContext = applicationContext,
                connection = connection,
                processName = processName,
                processId = pid,
                diagnosticModelPath = diagnosticModelPath,
                sessionId = sessionId,
                routeName = response.requireString(KEY_ROUTE_NAME),
                modelId = response.requireString(KEY_MODEL_ID)
            )
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    private class RemoteSession(
        private val applicationContext: Context,
        private val connection: SdxRuntimeConnection,
        private val processName: String,
        private val processId: Int,
        private val diagnosticModelPath: String,
        private val sessionId: String,
        override val routeName: String,
        override val modelId: String
    ) : PlatformLocalChatSession {
        private val closed = AtomicBoolean(false)

        override fun generate(
            request: ChatRequest,
            opts: GenOptions,
            onChunk: Consumer<String>?
        ): ChatResponse {
            requireOpen()
            val operation = NativeOperationJournal(applicationContext).begin(
                modelPath = diagnosticModelPath,
                operation = NativeOperationKind.SDX_MODEL_EXECUTION,
                checkpoint = NativeOperationCheckpoint.RENDER_CHAT_TEMPLATE,
                processName = processName,
                processId = processId
            )
            val wireRequest = Bundle().apply {
                putString(KEY_SESSION_ID, sessionId)
                putString(KEY_CHAT_REQUEST_JSON, request.toJson())
                putString(KEY_OPTIONS_JSON, opts.toOptionsJson())
                putString(KEY_OPERATION_ATTEMPT_ID, operation.snapshot().attemptId)
            }
            val timeout = sdxRuntimeGenerationTimeoutMillis(opts.maxTokens())
            return try {
                val structuredJson = executeJournaledRequest(
                    applicationContext,
                    connection,
                    processName,
                    processId,
                    operation,
                    MSG_GENERATE,
                    wireRequest,
                    timeout,
                    onChunk
                ).requireString(KEY_STRUCTURED_RESPONSE_JSON)
                ChatResponse.fromStructuredJson(structuredJson)
            } catch (failure: Throwable) {
                if (!connection.isProcessAlive(processId)) {
                    closed.set(true)
                    connection.close()
                }
                throw failure
            }
        }

        override fun generate(
            messages: List<Message>,
            opts: GenOptions,
            onChunk: Consumer<String>?
        ): String {
            val response = generate(
                ChatRequest(messages, "[]", ChatRequest.ToolChoice.NONE),
                opts,
                onChunk
            )
            if (!response.isProtocolValid) {
                throw ChatException(
                    "Model protocol failure: " + response.protocolErrors().joinToString("; ")
                )
            }
            if (response.toolCalls().isNotEmpty()) {
                throw ChatException("Content-only generation unexpectedly returned tool calls")
            }
            return response.content()
        }

        override fun cancel() {
            requireOpen()
            val operation = NativeOperationJournal(applicationContext).begin(
                modelPath = diagnosticModelPath,
                operation = NativeOperationKind.SDX_MODEL_CANCELLATION,
                checkpoint = NativeOperationCheckpoint.CANCEL_GENERATION,
                processName = processName,
                processId = processId
            )
            executeJournaledRequest(
                applicationContext,
                connection,
                processName,
                processId,
                operation,
                MSG_CANCEL,
                Bundle().apply {
                    putString(KEY_SESSION_ID, sessionId)
                    putString(KEY_OPERATION_ATTEMPT_ID, operation.snapshot().attemptId)
                },
                SERVICE_CONTROL_TIMEOUT_MILLIS,
                null
            )
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                if (!connection.isProcessAlive(processId)) return
                val operation = NativeOperationJournal(applicationContext).begin(
                    modelPath = diagnosticModelPath,
                    operation = NativeOperationKind.SDX_MODEL_TEARDOWN,
                    checkpoint = NativeOperationCheckpoint.CLOSE_TEXT_SESSION,
                    processName = processName,
                    processId = processId
                )
                executeJournaledRequest(
                    applicationContext,
                    connection,
                    processName,
                    processId,
                    operation,
                    MSG_CLOSE,
                    Bundle().apply {
                        putString(KEY_SESSION_ID, sessionId)
                        putString(KEY_OPERATION_ATTEMPT_ID, operation.snapshot().attemptId)
                    },
                    SERVICE_CONTROL_TIMEOUT_MILLIS,
                    null
                )
            } finally {
                connection.close()
            }
        }

        private fun requireOpen() {
            if (closed.get()) throw ChatException("Local SDX runtime session is closed.")
        }
    }
}

private data class RuntimeReply(
    val bundle: Bundle,
    val callbackFailure: Throwable?
)

private fun executeJournaledRequest(
    context: Context,
    connection: SdxRuntimeConnection,
    processName: String,
    processId: Int,
    operation: NativeOperationTransaction,
    method: Int,
    request: Bundle,
    timeoutMillis: Long,
    onChunk: Consumer<String>?
): Bundle {
    val reply = try {
        connection.request(method, request, timeoutMillis, processId, onChunk)
    } catch (failure: Exception) {
        throw recoverRuntimeFailure(
            context,
            processName,
            processId,
            operation,
            failure,
            includeExitEvidence = true
        )
    }
    val remoteFailure = decodeRemoteFailure(reply.bundle, processName, processId)
    if (remoteFailure != null) {
        val attempt = operation.snapshot()
        SmokeDecodeTraceLog(context).recordFailure(
            "ipc_remote_failure",
            attempt.attemptId,
            remoteFailure,
            mapOf(
                "method" to method,
                "remote_pid" to processId,
                "checkpoint" to attempt.checkpoint.name,
                "remote_failure_class" to reply.bundle.getString(KEY_FAILURE_CLASS).orEmpty(),
                "remote_failure_message" to reply.bundle.getString(KEY_FAILURE_MESSAGE).orEmpty()
            )
        )
        throw recoverRuntimeFailure(
            context,
            processName,
            processId,
            operation,
            remoteFailure,
            includeExitEvidence = false
        )
    }
    operation.complete()
    reply.callbackFailure?.let { callbackFailure ->
        throw ChatException(
            "The model generated successfully, but delivering a streamed token to the chat UI failed.",
            callbackFailure
        )
    }
    return reply.bundle
}

private fun recoverRuntimeFailure(
    context: Context,
    processName: String,
    processId: Int,
    operation: NativeOperationTransaction,
    cause: Throwable,
    includeExitEvidence: Boolean
): ChatException {
    val persisted = try {
        NativeOperationJournal(context).resume(operation.snapshot().attemptId)
    } catch (journalFailure: Throwable) {
        cause.addSuppressed(journalFailure)
        operation
    }
    val evidence = if (includeExitEvidence) {
        awaitExitEvidence(
            context,
            processName,
            processId,
            persisted.snapshot().startedEpochMillis
        )
    } else {
        null
    }
    val recovered = NativeOperationCrashRecovery.recoverAttemptAndPersist(
        context = context,
        transaction = persisted,
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
    processId: Int,
    startedEpochMillis: Long
): NativeOperationExitEvidence? {
    val deadline = SystemClock.elapsedRealtime() + EXIT_EVIDENCE_WAIT_MILLIS
    do {
        NativeOperationCrashRecovery.findExitEvidence(
            context,
            processName,
            processId,
            startedEpochMillis
        )?.let { return it }
        SystemClock.sleep(SERVICE_POLL_MILLIS)
    } while (SystemClock.elapsedRealtime() < deadline)
    return NativeOperationCrashRecovery.findExitEvidence(
        context,
        processName,
        processId,
        startedEpochMillis
    )
}

private fun decodeRemoteFailure(response: Bundle, processName: String, pid: Int): ChatException? {
    if (response.getBoolean(KEY_SUCCESS, false)) return null
    return ChatException(
        buildString {
            appendLine("The app-private SDX runtime process failed.")
            appendLine("Process: $processName")
            appendLine("PID: $pid")
            appendLine(
                "Remote exception: " +
                    response.getString(KEY_FAILURE_CLASS).orEmpty().ifBlank { "unknown remote exception" }
            )
            response.getString(KEY_FAILURE_MESSAGE).orEmpty().takeIf(String::isNotBlank)?.let {
                appendLine("Remote message: $it")
            }
            appendLine("Remote full stack trace:")
            append(
                response.getString(KEY_FAILURE_STACK).orEmpty()
                    .ifBlank { "No remote stack trace was returned." }
            )
        }
    )
}

internal fun encodeSdxRuntimeMessages(messages: List<Message>): String = MiniJson.write(
    messages.map { message ->
        linkedMapOf<String, Any>(
            "role" to message.role(),
            "content" to message.content()
        ).apply {
            message.toolCallId()?.let { put("tool_call_id", it) }
            message.toolName()?.let { put("name", it) }
        }
    }
)

internal fun decodeSdxRuntimeMessages(json: String): List<Message> {
    val parsed = MiniJson.parse(json)
    require(parsed is List<*>) { "SDX runtime messages JSON must be an array." }
    return parsed.mapIndexed { index, value ->
        require(value is Map<*, *>) { "SDX runtime message $index must be an object." }
        val role = value["role"] as? String
            ?: throw IllegalArgumentException("SDX runtime message $index omitted string role.")
        val content = value["content"] as? String
            ?: throw IllegalArgumentException("SDX runtime message $index omitted string content.")
        val toolCallId = value["tool_call_id"]?.let {
            it as? String
                ?: throw IllegalArgumentException("SDX runtime message $index has non-string tool_call_id.")
        }
        val toolName = value["name"]?.let {
            it as? String
                ?: throw IllegalArgumentException("SDX runtime message $index has non-string name.")
        }
        Message(role, content, emptyList(), toolCallId, toolName)
    }
}

internal fun decodeSdxRuntimeGenerationOptions(json: String): GenOptions {
    val root = MiniJson.parse(json)
    require(root is Map<*, *>) { "SDX runtime options JSON must be an object." }
    val sampling = root["sampling"]
    require(sampling is Map<*, *>) { "SDX runtime options omitted the sampling object." }
    return GenOptions.builder()
        .maxTokens((root["maxNewTokens"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("SDX runtime options omitted maxNewTokens."))
        .temperature((sampling["temperature"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("SDX runtime options omitted sampling.temperature."))
        .topK((sampling["topK"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("SDX runtime options omitted sampling.topK."))
        .topP((sampling["topP"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("SDX runtime options omitted sampling.topP."))
        .seed((sampling["seed"] as? Number)?.toLong() ?: -1L)
        .build()
}

internal fun sdxRuntimeGenerationTimeoutMillis(maxTokens: Int): Long =
    (max(SERVICE_MIN_GENERATE_TIMEOUT_MILLIS, maxTokens.toLong() * SERVICE_TOKEN_TIMEOUT_MILLIS))
        .coerceAtMost(SERVICE_MAX_GENERATE_TIMEOUT_MILLIS)

private fun Bundle.requireString(key: String): String =
    getString(key)?.takeIf(String::isNotBlank)
        ?: throw ChatException("The SDX runtime IPC payload omitted '$key'.")

/** One bound connection and reply dispatcher for the lifetime of one model session. */
private class SdxRuntimeConnection private constructor(
    private val context: Context
) : ServiceConnection, IBinder.DeathRecipient, AutoCloseable {
    private val connected = CountDownLatch(1)
    private val connectionFailure = AtomicReference<Throwable?>()
    private val pending = ConcurrentHashMap<String, PendingReply>()
    private val replyThread = HandlerThread("sdx-runtime-replies").apply { start() }
    private val replyMessenger = Messenger(Handler(replyThread.looper) { message ->
        handleReply(message)
        true
    })
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
        markDead(ChatException("Android disconnected the app-private SDX runtime service."))
    }

    override fun onBindingDied(name: ComponentName) {
        markDead(ChatException("Android reported that the SDX runtime service binding died."))
    }

    override fun onNullBinding(name: ComponentName) {
        markDead(ChatException("The SDX runtime service returned a null Binder."))
        connected.countDown()
    }

    override fun binderDied() {
        markDead(ChatException("The app-private SDX runtime process Binder died."))
    }

    fun requireRemoteState(): SdxRuntimeWorkerState {
        val reply = request(
            MSG_PID,
            Bundle(),
            SERVICE_BIND_TIMEOUT_MILLIS,
            -1,
            null
        ).bundle
        val pid = reply.getInt(KEY_PID, -1)
        if (pid <= 0) throw ChatException("The SDX runtime service returned an invalid pid: $pid")
        remotePid = pid
        return SdxRuntimeWorkerState(
            pid = pid,
            ownsModelSession = reply.getBoolean(KEY_HAS_ACTIVE_SESSION, false),
            retiring = reply.getBoolean(KEY_WORKER_RETIRING, false)
        )
    }

    fun request(
        method: Int,
        data: Bundle,
        timeoutMillis: Long,
        processId: Int,
        onChunk: Consumer<String>?
    ): RuntimeReply {
        awaitConnected()
        val requestId = UUID.randomUUID().toString()
        val attemptId = data.getString(KEY_OPERATION_ATTEMPT_ID)
        val trace = SmokeDecodeTraceLog(context)
        val request = requireFrameworkOnlyRuntimeWireBundle(
            Bundle(data).apply { putString(KEY_REQUEST_ID, requestId) },
            "SDX runtime request"
        )
        val pendingReply = PendingReply(onChunk)
        check(pending.putIfAbsent(requestId, pendingReply) == null)
        try {
            val remote = remoteMessenger
                ?: throw connectionFailure.get()
                ?: ChatException("The SDX runtime service has no active Binder.")
            remote.send(AndroidMessage.obtain().apply {
                what = method
                this.data = request
                replyTo = replyMessenger
            })
            if (!attemptId.isNullOrBlank()) {
                trace.record(
                    "ipc_request_sent",
                    attemptId,
                    mapOf(
                        "method" to method,
                        "timeout_ms" to timeoutMillis,
                        "remote_pid" to remotePid
                    )
                )
            }
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (true) {
                if (pendingReply.completed.await(SERVICE_POLL_MILLIS, TimeUnit.MILLISECONDS)) break
                connectionFailure.get()?.let { throw it.asException() }
                val watchedPid = if (processId > 0) processId else remotePid
                if (watchedPid > 0 && !isProcessAlive(watchedPid)) {
                    throw ChatException(
                        "The app-private SDX runtime process $watchedPid disappeared during IPC method $method."
                    )
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    if (!attemptId.isNullOrBlank()) {
                        trace.record(
                            "ipc_timeout",
                            attemptId,
                            mapOf(
                                "method" to method,
                                "timeout_ms" to timeoutMillis,
                                "watched_pid" to watchedPid
                            )
                        )
                    }
                    if (watchedPid > 0 && isProcessAlive(watchedPid)) Process.killProcess(watchedPid)
                    throw ChatException(
                        "The app-private SDX runtime did not finish IPC method $method within " +
                            "$timeoutMillis ms. Android terminated that worker; the verified model, " +
                            "canonical SDZ, and device compilation cache remain reusable."
                    )
                }
            }
            connectionFailure.get()?.let { throw it.asException() }
            val response = pendingReply.response
                ?: throw ChatException("The SDX runtime service completed without a response bundle.")
            if (!attemptId.isNullOrBlank()) {
                val success = response.getBoolean(KEY_SUCCESS, false)
                val responseFields = linkedMapOf<String, Any?>(
                    "method" to method,
                    "success" to success,
                    "callback_failure" to (pendingReply.callbackFailure.get() != null)
                )
                if (!success) {
                    responseFields["failure_class"] = response.getString(KEY_FAILURE_CLASS).orEmpty()
                    responseFields["failure_message"] = response.getString(KEY_FAILURE_MESSAGE).orEmpty()
                    responseFields["failure_stack_chars"] =
                        response.getString(KEY_FAILURE_STACK).orEmpty().length
                }
                trace.record("ipc_response_received", attemptId, responseFields)
            }
            return RuntimeReply(
                requireFrameworkOnlyRuntimeWireBundle(response, "SDX runtime response"),
                pendingReply.callbackFailure.get()
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatException("Waiting for the SDX runtime process was interrupted.", interrupted)
        } catch (failure: RemoteException) {
            if (!attemptId.isNullOrBlank()) {
                trace.recordFailure("ipc_failed", attemptId, failure, mapOf("method" to method))
            }
            throw ChatException("The SDX runtime Binder request failed.", failure)
        } catch (failure: Throwable) {
            if (!attemptId.isNullOrBlank()) {
                trace.recordFailure("ipc_failed", attemptId, failure, mapOf("method" to method))
            }
            throw failure
        } finally {
            pending.remove(requestId)
        }
    }

    fun isProcessAlive(pid: Int): Boolean =
        connectionFailure.get() == null && File("/proc/$pid").exists()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        remoteBinder?.unlinkToDeath(this, 0)
        pending.values.forEach { it.completed.countDown() }
        try {
            context.unbindService(this)
        } catch (failure: IllegalArgumentException) {
            Log.w(TAG, "SDX runtime service was already unbound", failure)
        }
        context.stopService(Intent(context, SdxRuntimeService::class.java))
        replyThread.quitSafely()
    }

    private fun awaitConnected() {
        if (!connected.await(SERVICE_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            close()
            throw ChatException(
                "Android did not connect the app-private SDX runtime service within " +
                    "$SERVICE_BIND_TIMEOUT_MILLIS ms."
            )
        }
        connectionFailure.get()?.let { throw it.asException() }
    }

    private fun handleReply(message: AndroidMessage) {
        val data = message.data ?: return
        val requestId = data.getString(KEY_REQUEST_ID) ?: return
        val target = pending[requestId] ?: return
        when (message.what) {
            EVENT_CHUNK -> {
                val chunk = data.getString(KEY_CHUNK).orEmpty()
                if (chunk.isNotEmpty() && target.callbackFailure.get() == null) {
                    try {
                        target.onChunk?.accept(chunk)
                    } catch (failure: Throwable) {
                        target.callbackFailure.compareAndSet(null, failure)
                    }
                }
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
        this as? Exception ?: ChatException("The SDX runtime process failed.", this)

    private class PendingReply(
        val onChunk: Consumer<String>?
    ) {
        val completed = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>()

        @Volatile
        var response: Bundle? = null
    }

    companion object {
        fun bindForNewSession(context: Context): Pair<SdxRuntimeConnection, SdxRuntimeWorkerState> {
            var lastFailure: Throwable? = null
            repeat(MAX_CLEAN_WORKER_BIND_ATTEMPTS) { bindAttempt ->
                val connection = try {
                    bind(context)
                } catch (failure: Throwable) {
                    lastFailure = failure
                    if (bindAttempt == MAX_CLEAN_WORKER_BIND_ATTEMPTS - 1) throw failure
                    return@repeat
                }
                val state = try {
                    connection.requireRemoteState()
                } catch (failure: Throwable) {
                    connection.close()
                    lastFailure = failure
                    if (bindAttempt == MAX_CLEAN_WORKER_BIND_ATTEMPTS - 1) throw failure
                    return@repeat
                }
                check(state.pid != Process.myPid()) {
                    "SDX runtime service was not isolated: runtime pid=${state.pid} app pid=${Process.myPid()}"
                }
                if (!sdxRuntimeWorkerMustRestartBeforeOpen(state)) {
                    return connection to state
                }

                Log.w(
                    TAG,
                    "Recycling unavailable SDX runtime pid=${state.pid} " +
                        "active=${state.ownsModelSession} retiring=${state.retiring} " +
                        "before opening a new model session."
                )
                connection.close()
                if (File("/proc/${state.pid}").exists()) Process.killProcess(state.pid)
                if (!awaitWorkerExit(state.pid)) {
                    throw ChatException(
                        "The stale SDX runtime process ${state.pid} did not exit before model load."
                    )
                }
                lastFailure = ChatException(
                    "The previous SDX runtime process ${state.pid} was not reusable " +
                        "(active=${state.ownsModelSession}, retiring=${state.retiring})."
                )
            }
            throw ChatException(
                "Could not acquire a clean app-private SDX runtime process.",
                lastFailure
            )
        }

        private fun bind(context: Context): SdxRuntimeConnection {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "SDX runtime service binding must run off Android's UI thread."
            }
            val runtimeIntent = Intent(context, SdxRuntimeService::class.java)
            // A bound-only worker has no started-service lifetime. Android's remove-task path may
            // kill such a background process even while a user-started import foreground service
            // is waiting for it. Start before binding so the native load survives task removal;
            // close() explicitly stops this lifetime after the session is retired.
            context.startService(runtimeIntent)
                ?: throw ChatException("Android refused to start the app-private SDX runtime service.")
            val connection = SdxRuntimeConnection(context.applicationContext)
            val bound = context.bindService(
                runtimeIntent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            if (!bound) {
                connection.close()
                throw ChatException("Android refused to bind the app-private SDX runtime service.")
            }
            connection.awaitConnected()
            return connection
        }

        private fun awaitWorkerExit(pid: Int): Boolean {
            val deadline = SystemClock.elapsedRealtime() + EXIT_EVIDENCE_WAIT_MILLIS
            while (File("/proc/$pid").exists()) {
                if (SystemClock.elapsedRealtime() >= deadline) return false
                try {
                    Thread.sleep(SERVICE_POLL_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ChatException(
                        "Waiting for the stale SDX runtime process $pid to exit was interrupted.",
                        interrupted
                    )
                }
            }
            return true
        }
    }
}

/**
 * Non-exported, same-UID service hosted in :sdx_model_runtime. Its single executor is the owner
 * thread for runtime creation, model loading, tokenization, generation, and deterministic cleanup.
 */
class SdxRuntimeService : Service() {
    private val ownerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sdx-runtime-owner").apply { isDaemon = false }
    }

    @Volatile
    private var activeSession: SdxOwnedPlatformChatSession? = null

    @Volatile
    private var activeSessionId: String? = null

    private val inboundMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { request ->
            handleRequest(request)
            true
        })
    }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == sdxRuntimeProcessName(packageName)) {
            "SDX runtime service is not running in its declared app-private process."
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder = inboundMessenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Mark this process permanently unavailable before scheduling process death. A replacement
        // Service instance can race the posted kill, so its PID handshake and open gate must reject
        // the retiring worker instead of opening a session teardown is about to kill.
        sdxRuntimeWorkerRetiring.set(true)
        stopSelf()
        val pid = Process.myPid()
        Handler(Looper.getMainLooper()).post { Process.killProcess(pid) }
        return false
    }

    override fun onDestroy() {
        ownerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleRequest(request: AndroidMessage) {
        // Handler dispatch returns before owner-thread work runs. Android then recycles Message, so
        // every field and the Bundle must be detached before anything is queued.
        val method = request.what
        val sendingUid = request.sendingUid
        val replyTo = request.replyTo ?: return
        val wireData = Bundle(request.data)
        val requestData = try {
            requireFrameworkOnlyRuntimeWireBundle(wireData, "SDX runtime service request")
        } catch (failure: Throwable) {
            sendResponse(replyTo, wireData.getString(KEY_REQUEST_ID).orEmpty(), failureBundle(failure))
            return
        }
        val requestId = requestData.getString(KEY_REQUEST_ID).orEmpty()
        if (sendingUid != Process.myUid()) {
            sendResponse(
                replyTo,
                requestId,
                failureBundle(SecurityException("Only this application may invoke the SDX runtime service."))
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
                    putBoolean(KEY_HAS_ACTIVE_SESSION, activeSession != null)
                    putBoolean(KEY_WORKER_RETIRING, sdxRuntimeWorkerRetiring.get())
                }
            )
            MSG_CANCEL -> executeCancellation(replyTo, requestId, requestData)
            MSG_OPEN -> executeOwnedRequest(replyTo, requestId) {
                openSession(requestData)
            }
            MSG_GENERATE -> executeOwnedRequest(replyTo, requestId) {
                generate(requestData, replyTo, requestId)
            }
            MSG_CLOSE -> executeOwnedRequest(
                replyTo,
                requestId,
                stopServiceAfterSuccess = true
            ) {
                closeSession(requestData)
            }
            else -> sendResponse(
                replyTo,
                requestId,
                failureBundle(IllegalArgumentException("Unsupported SDX runtime IPC method: $method"))
            )
        }
    }

    private fun executeOwnedRequest(
        replyTo: Messenger,
        requestId: String,
        stopServiceAfterSuccess: Boolean = false,
        operation: () -> Bundle
    ) {
        ownerExecutor.execute {
            val response = try {
                operation()
            } catch (failure: Throwable) {
                failureBundle(failure)
            }
            sendResponse(replyTo, requestId, response)
            if (stopServiceAfterSuccess && response.getBoolean(KEY_SUCCESS, false)) stopSelf()
        }
    }

    private fun openSession(extras: Bundle): Bundle {
        val attemptId = extras.requireString(KEY_OPERATION_ATTEMPT_ID)
        val trace = SmokeDecodeTraceLog(applicationContext)
        trace.record(
            "runtime_open_request",
            attemptId,
            mapOf(
                "pid" to Process.myPid(),
                "active_session" to (activeSession != null),
                "diagnostic_mode" to extras.requireString(KEY_DIAGNOSTIC_MODE)
            )
        )
        check(!sdxRuntimeWorkerRetiring.get()) {
            "The SDX runtime process is retiring and cannot open another model session."
        }
        check(activeSession == null) { "The SDX runtime process already owns a model session." }
        val operation = try {
            resumeOperation(extras)
        } catch (failure: Throwable) {
            trace.recordFailure(
                "runtime_open_failed",
                attemptId,
                failure,
                mapOf("pid" to Process.myPid(), "checkpoint" to "RESUME_OPERATION")
            )
            throw failure
        }
        trace.record(
            "runtime_open_enter",
            attemptId,
            mapOf("pid" to Process.myPid(), "checkpoint" to operation.snapshot().checkpoint.name)
        )
        return try {
            val created = SdxPlatformRuntimeOwner.open(
                context = applicationContext,
                modelPath = extras.requireString(KEY_MODEL_PATH),
                diagnosticModelPath = extras.requireString(KEY_DIAGNOSTIC_MODEL_PATH),
                routeName = extras.requireString(KEY_ROUTE_NAME),
                modelIdPrefix = extras.requireString(KEY_MODEL_ID_PREFIX),
                loadTransaction = operation,
                diagnosticMode = ModelDiagnosticMode.valueOf(extras.requireString(KEY_DIAGNOSTIC_MODE))
            )
            val id = UUID.randomUUID().toString()
            activeSession = created
            activeSessionId = id
            trace.record(
                "runtime_open_return",
                attemptId,
                mapOf(
                    "pid" to Process.myPid(),
                    "checkpoint" to operation.snapshot().checkpoint.name,
                    "route" to created.routeName,
                    "diagnostic_mode" to extras.requireString(KEY_DIAGNOSTIC_MODE)
                )
            )
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putInt(KEY_PID, Process.myPid())
                putString(KEY_SESSION_ID, id)
                putString(KEY_ROUTE_NAME, created.routeName)
                putString(KEY_MODEL_ID, created.modelId)
            }
        } catch (failure: Throwable) {
            trace.recordFailure(
                "runtime_open_failed",
                attemptId,
                failure,
                mapOf(
                    "pid" to Process.myPid(),
                    "checkpoint" to operation.snapshot().checkpoint.name
                )
            )
            throw failure
        }
    }

    private fun generate(extras: Bundle, replyTo: Messenger, requestId: String): Bundle {
        val attemptId = extras.requireString(KEY_OPERATION_ATTEMPT_ID)
        val trace = SmokeDecodeTraceLog(applicationContext)
        trace.record(
            "runtime_generate_request",
            attemptId,
            mapOf("request_id" to requestId, "pid" to Process.myPid())
        )
        return try {
            val session = requireSession(extras)
            val operation = resumeOperation(extras)
            trace.record(
                "runtime_generate_enter",
                attemptId,
                mapOf("pid" to Process.myPid(), "checkpoint" to operation.snapshot().checkpoint.name)
            )
            val heartbeat = trace.startHeartbeat(attemptId, "session.generate")
            try {
                val result = session.generate(
                    extras.requireString(KEY_CHAT_REQUEST_JSON),
                    decodeSdxRuntimeGenerationOptions(extras.requireString(KEY_OPTIONS_JSON)),
                    Consumer { chunk -> sendChunk(replyTo, requestId, chunk) },
                    operation
                )
                trace.record(
                    "runtime_generate_return",
                    attemptId,
                    mapOf("result_chars" to result.length, "pid" to Process.myPid())
                )
                Bundle().apply {
                    putBoolean(KEY_SUCCESS, true)
                    putInt(KEY_PID, Process.myPid())
                    putString(KEY_STRUCTURED_RESPONSE_JSON, result)
                }
            } finally {
                heartbeat.close()
            }
        } catch (failure: Throwable) {
            trace.recordFailure("runtime_generate_failed", attemptId, failure, mapOf("pid" to Process.myPid()))
            throw failure
        }
    }

    private fun executeCancellation(replyTo: Messenger, requestId: String, extras: Bundle) {
        val response = try {
            val session = requireSession(extras)
            session.cancel(resumeOperation(extras))
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putInt(KEY_PID, Process.myPid())
            }
        } catch (failure: Throwable) {
            failureBundle(failure)
        }
        sendResponse(replyTo, requestId, response)
    }

    private fun closeSession(extras: Bundle): Bundle {
        val session = requireSession(extras)
        val operation = resumeOperation(extras)
        try {
            session.close(operation)
        } finally {
            activeSession = null
            activeSessionId = null
        }
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, true)
            putInt(KEY_PID, Process.myPid())
        }
    }

    private fun requireSession(extras: Bundle): SdxOwnedPlatformChatSession {
        val requested = extras.requireString(KEY_SESSION_ID)
        val currentId = activeSessionId
        val current = activeSession
        check(current != null && currentId == requested) {
            "SDX runtime session '$requested' is not active in pid=${Process.myPid()}."
        }
        return current
    }

    private fun resumeOperation(extras: Bundle): NativeOperationTransaction {
        val attemptId = extras.requireString(KEY_OPERATION_ATTEMPT_ID)
        val operation = NativeOperationJournal(applicationContext).resume(attemptId)
        val attempt = operation.snapshot()
        check(attempt.processName == Application.getProcessName()) {
            "SDX runtime journal targets ${attempt.processName}, not ${Application.getProcessName()}"
        }
        check(attempt.processId == Process.myPid()) {
            "SDX runtime journal targets pid=${attempt.processId}, not pid=${Process.myPid()}"
        }
        return operation
    }

    private fun sendChunk(replyTo: Messenger, requestId: String, chunk: String) {
        if (chunk.isEmpty()) return
        replyTo.send(AndroidMessage.obtain().apply {
            what = EVENT_CHUNK
            data = Bundle().apply {
                putString(KEY_REQUEST_ID, requestId)
                putString(KEY_CHUNK, chunk)
            }
        })
    }

    private fun sendResponse(replyTo: Messenger, requestId: String, response: Bundle) {
        val wire = requireFrameworkOnlyRuntimeWireBundle(
            Bundle(response).apply { putString(KEY_REQUEST_ID, requestId) },
            "SDX runtime service response"
        )
        try {
            replyTo.send(AndroidMessage.obtain().apply {
                what = EVENT_RESPONSE
                data = wire
            })
        } catch (failure: RemoteException) {
            // The UI process is gone. Keep the provider-owned journal intact for startup recovery.
            Log.e(TAG, "Could not deliver the complete SDX runtime response", failure)
        }
    }

    private fun failureBundle(failure: Throwable): Bundle {
        val fullStack = failure.stackTraceToString()
        val boundedStack = if (fullStack.length <= MAX_REMOTE_STACK_CHARS) {
            fullStack
        } else {
            fullStack.take(MAX_REMOTE_STACK_CHARS) +
                "\n... remote stack trace exceeded $MAX_REMOTE_STACK_CHARS characters"
        }
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, false)
            putInt(KEY_PID, Process.myPid())
            putString(KEY_FAILURE_CLASS, failure.javaClass.name)
            putString(KEY_FAILURE_MESSAGE, failure.message.orEmpty())
            putString(KEY_FAILURE_STACK, boundedStack)
        }
    }
}
