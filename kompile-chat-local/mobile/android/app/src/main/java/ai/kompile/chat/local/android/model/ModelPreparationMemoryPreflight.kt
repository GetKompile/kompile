package ai.kompile.chat.local.android.model

import android.os.SystemClock
import ai.kompile.chat.local.ChatException
import java.io.File

/**
 * Historical cold-import memory observations, used only as advisory reference values in the app.
 *
 * These predate importer memory fixes and are not a model/profile-aware peak-memory estimate.
 * Free logical zram/swap is not an independent physical RAM budget. Neither reference may delay
 * or reject functional loading, including prepared-cache lookup. Controlled qualification has a
 * separate environment policy; actual load/decode results determine functional success.
 */
internal const val MODEL_PREPARATION_MIN_AVAILABLE_BYTES = 3_500_000_000L
internal const val MODEL_PREPARATION_MIN_SWAP_FREE_BYTES = 2_000_000_000L
internal const val MODEL_PREPARATION_QUALIFICATION_WAIT_TIMEOUT_MILLIS = 90_000L
internal const val MODEL_PREPARATION_RECHECK_INTERVAL_MILLIS = 5_000L

private const val KIBIBYTE_BYTES = 1024L
private const val DECIMAL_MEGABYTE_BYTES = 1_000_000L

/**
 * Retained controlled-qualification environment policy, not the memory required to fit a model.
 * Only the strict cold/warm qualification tests enforce these values. Functional diagnostics
 * record the memory snapshot and exercise the requested preparation profile without this veto.
 */
internal const val TENSOR_G3_QUALIFICATION_MIN_AVAILABLE_BYTES = 2_500_000_000L
internal const val TENSOR_G3_QUALIFICATION_MIN_SWAP_FREE_BYTES = 2_000_000_000L

internal data class ModelPreparationMemorySnapshot(
    val availableBytes: Long,
    val swapFreeBytes: Long,
) {
    val readable: Boolean
        get() = availableBytes > 0L && swapFreeBytes >= 0L
}

internal data class ModelPreparationMemoryPreflight(
    val snapshot: ModelPreparationMemorySnapshot,
    val minimumAvailableBytes: Long = MODEL_PREPARATION_MIN_AVAILABLE_BYTES,
    val minimumSwapFreeBytes: Long = MODEL_PREPARATION_MIN_SWAP_FREE_BYTES,
) {
    init {
        require(minimumAvailableBytes >= 0L) { "Minimum available RAM cannot be negative." }
        require(minimumSwapFreeBytes >= 0L) { "Minimum free swap cannot be negative." }
    }

    val thresholdMet: Boolean
        get() = snapshot.readable &&
            snapshot.availableBytes >= minimumAvailableBytes &&
            snapshot.swapFreeBytes >= minimumSwapFreeBytes

    fun statusFields(): String =
        "availableBytes=${snapshot.availableBytes}" +
            ",swapFreeBytes=${snapshot.swapFreeBytes}" +
            ",minAvailableBytes=$minimumAvailableBytes" +
            ",minSwapFreeBytes=$minimumSwapFreeBytes" +
            ",thresholdMet=$thresholdMet"

    fun userMessage(modelName: String): String {
        val retained = "The verified model remains saved and will not be downloaded again."
        if (!snapshot.readable) {
            return "Android could not verify free memory before preparing $modelName. " +
                "This snapshot cannot determine whether the model will fit. $retained"
        }
        return "Memory snapshot for $modelName: " +
            "Android reports ${formatMegabytes(snapshot.availableBytes)} available and " +
            "${formatMegabytes(snapshot.swapFreeBytes)} swap free. The configured reference is " +
            "${formatMegabytes(minimumAvailableBytes)} available and " +
            "${formatMegabytes(minimumSwapFreeBytes)} swap free. " +
            "These reference values are not a model-fit estimate. " +
            "Free swap is not additional physical RAM. Preparation can need more memory than decoding. $retained"
    }

    /** Explicit policy assertion for controlled checks, never an ordinary model-load admission. */
    fun requireReady(modelName: String): ModelPreparationMemoryPreflight {
        if (!thresholdMet) throw ChatException(userMessage(modelName))
        return this
    }

    private fun formatMegabytes(bytes: Long): String =
        "${bytes.coerceAtLeast(0L) / DECIMAL_MEGABYTE_BYTES} MB"
}

internal fun modelPreparationMemoryPreflight(
    snapshot: ModelPreparationMemorySnapshot,
    minimumAvailableBytes: Long = MODEL_PREPARATION_MIN_AVAILABLE_BYTES,
    minimumSwapFreeBytes: Long = MODEL_PREPARATION_MIN_SWAP_FREE_BYTES,
): ModelPreparationMemoryPreflight =
    ModelPreparationMemoryPreflight(snapshot, minimumAvailableBytes, minimumSwapFreeBytes)

/**
 * Bounded wait for callers that explicitly enforce a controlled memory environment.
 * Ordinary model loading must take a snapshot directly, without waiting for these reference values.
 */
internal fun awaitModelPreparationMemoryPreflight(
    timeoutMillis: Long,
    recheckIntervalMillis: Long = MODEL_PREPARATION_RECHECK_INTERVAL_MILLIS,
    minimumAvailableBytes: Long = MODEL_PREPARATION_MIN_AVAILABLE_BYTES,
    minimumSwapFreeBytes: Long = MODEL_PREPARATION_MIN_SWAP_FREE_BYTES,
    readSnapshot: () -> ModelPreparationMemorySnapshot = ::currentModelPreparationMemorySnapshot,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    sleep: (Long) -> Unit = SystemClock::sleep,
    onWaiting: (ModelPreparationMemoryPreflight) -> Unit = {},
): ModelPreparationMemoryPreflight {
    require(timeoutMillis >= 0L) { "Memory preflight timeout cannot be negative." }
    require(recheckIntervalMillis > 0L) { "Memory preflight interval must be positive." }

    val started = elapsedRealtime()
    val deadline = if (started > Long.MAX_VALUE - timeoutMillis) {
        Long.MAX_VALUE
    } else {
        started + timeoutMillis
    }
    var preflight = modelPreparationMemoryPreflight(
        readSnapshot(),
        minimumAvailableBytes,
        minimumSwapFreeBytes,
    )
    while (preflight.snapshot.readable && !preflight.thresholdMet) {
        val now = elapsedRealtime()
        if (now >= deadline) break
        onWaiting(preflight)
        sleep(minOf(recheckIntervalMillis, deadline - now))
        preflight = modelPreparationMemoryPreflight(
            readSnapshot(),
            minimumAvailableBytes,
            minimumSwapFreeBytes,
        )
    }
    return preflight
}

internal fun currentModelPreparationMemorySnapshot(
    readMemInfo: () -> String = { File("/proc/meminfo").readText() },
): ModelPreparationMemorySnapshot = runCatching {
    parseModelPreparationMemorySnapshot(readMemInfo())
}.getOrElse {
    ModelPreparationMemorySnapshot(availableBytes = -1L, swapFreeBytes = -1L)
}

internal fun parseModelPreparationMemorySnapshot(memInfo: String): ModelPreparationMemorySnapshot {
    var availableBytes = -1L
    var swapFreeBytes = -1L
    memInfo.lineSequence().forEach { line ->
        when {
            line.startsWith("MemAvailable:") -> availableBytes = memInfoValueBytes(line)
            line.startsWith("SwapFree:") -> swapFreeBytes = memInfoValueBytes(line)
        }
    }
    return ModelPreparationMemorySnapshot(availableBytes, swapFreeBytes)
}

private fun memInfoValueBytes(line: String): Long {
    val fields = line.substringAfter(':', "").trim().split(Regex("\\s+"))
    if (fields.size != 2 || fields[1] != "kB") return -1L
    val kibibytes = fields[0].toLongOrNull()
        ?.takeIf { it >= 0L && it <= Long.MAX_VALUE / KIBIBYTE_BYTES }
        ?: return -1L
    return kibibytes * KIBIBYTE_BYTES
}
