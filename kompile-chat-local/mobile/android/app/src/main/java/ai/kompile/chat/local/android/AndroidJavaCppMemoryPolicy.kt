package ai.kompile.chat.local.android

import android.app.ActivityManager
import android.content.Context

private const val JAVACPP_MAX_BYTES_PROPERTY = "org.bytedeco.javacpp.maxbytes"
private const val JAVACPP_MAX_BYTES_ALIAS = "org.bytedeco.javacpp.maxBytes"
private const val JAVACPP_MAX_PHYSICAL_BYTES_PROPERTY = "org.bytedeco.javacpp.maxphysicalbytes"
private const val JAVACPP_MAX_PHYSICAL_BYTES_ALIAS = "org.bytedeco.javacpp.maxPhysicalBytes"

/**
 * Native-memory limits installed before JavaCPP can initialize in either Android app process.
 *
 * JavaCPP otherwise derives both limits from [Runtime.maxMemory]. Android deliberately keeps the
 * ART heap small even when an application owns a multi-gigabyte accelerator model, so that default
 * is not a meaningful native-memory budget. The tracked allocation limit remains below the physical
 * process ceiling, and the physical ceiling reserves memory for Android, ART, and untracked runtime
 * allocations.
 */
internal data class AndroidJavaCppMemoryLimits(
    val systemTotalBytes: Long,
    val systemLowMemoryThresholdBytes: Long,
    val javaHeapMaxBytes: Long,
    val maxTrackedBytes: Long,
    val maxPhysicalBytes: Long
) {
    fun requireLoadedValues(actualMaxTrackedBytes: Long, actualMaxPhysicalBytes: Long) {
        check(actualMaxTrackedBytes == maxTrackedBytes) {
            "JavaCPP maxBytes initialized before the Android native-memory policy: " +
                "loaded=$actualMaxTrackedBytes expected=$maxTrackedBytes"
        }
        check(actualMaxPhysicalBytes == maxPhysicalBytes) {
            "JavaCPP maxPhysicalBytes initialized before the Android native-memory policy: " +
                "loaded=$actualMaxPhysicalBytes expected=$maxPhysicalBytes"
        }
    }
}

/** Pure device-memory policy kept separate from Android service access for host regression tests. */
internal fun calculateAndroidJavaCppMemoryLimits(
    systemTotalBytes: Long,
    systemLowMemoryThresholdBytes: Long,
    javaHeapMaxBytes: Long
): AndroidJavaCppMemoryLimits {
    require(systemTotalBytes > 0L) { "Android reported no physical memory." }
    require(systemLowMemoryThresholdBytes >= 0L) {
        "Android reported a negative low-memory threshold: $systemLowMemoryThresholdBytes"
    }
    require(javaHeapMaxBytes > 0L) { "Android reported no maximum ART heap." }

    val tenPercentReserve = fractionOf(systemTotalBytes, numerator = 1L, denominator = 10L)
    val thresholdReserve = saturatingMultiply(systemLowMemoryThresholdBytes, 2L)
    val requestedReserve = maxOf(tenPercentReserve, thresholdReserve, javaHeapMaxBytes)
    val reserve = requestedReserve.coerceAtMost(systemTotalBytes / 2L)
    val maxPhysicalBytes = systemTotalBytes - reserve
    val maxTrackedBytes = minOf(
        fractionOf(systemTotalBytes, numerator = 3L, denominator = 5L),
        maxPhysicalBytes
    )

    check(maxTrackedBytes > 0L && maxTrackedBytes <= maxPhysicalBytes) {
        "Android native-memory policy is invalid: tracked=$maxTrackedBytes physical=$maxPhysicalBytes"
    }
    return AndroidJavaCppMemoryLimits(
        systemTotalBytes = systemTotalBytes,
        systemLowMemoryThresholdBytes = systemLowMemoryThresholdBytes,
        javaHeapMaxBytes = javaHeapMaxBytes,
        maxTrackedBytes = maxTrackedBytes,
        maxPhysicalBytes = maxPhysicalBytes
    )
}

private fun fractionOf(value: Long, numerator: Long, denominator: Long): Long =
    (value / denominator) * numerator + ((value % denominator) * numerator) / denominator

private fun saturatingMultiply(value: Long, multiplier: Long): Long =
    if (value == 0L || multiplier == 0L) {
        0L
    } else if (value > Long.MAX_VALUE / multiplier) {
        Long.MAX_VALUE
    } else {
        value * multiplier
    }

/**
 * Process-local bootstrap. [install] intentionally has no JavaCPP/ND4J type reference: touching
 * Pointer before these properties are set would permanently freeze the incorrect heap-derived
 * defaults in Pointer's static final fields.
 */
internal object AndroidJavaCppMemoryPolicy {
    @Volatile
    private var installedLimits: AndroidJavaCppMemoryLimits? = null

    @Synchronized
    fun install(context: Context): AndroidJavaCppMemoryLimits {
        installedLimits?.let { return it }

        val memoryInfo = ActivityManager.MemoryInfo()
        requireNotNull(context.getSystemService(ActivityManager::class.java)) {
            "Android ActivityManager is unavailable during JavaCPP memory bootstrap."
        }.getMemoryInfo(memoryInfo)
        val limits = calculateAndroidJavaCppMemoryLimits(
            systemTotalBytes = memoryInfo.totalMem,
            systemLowMemoryThresholdBytes = memoryInfo.threshold,
            javaHeapMaxBytes = Runtime.getRuntime().maxMemory()
        )

        // JavaCPP accepts both spellings and reads the camel-case alias last. Set both so no stale
        // process property can override the application-owned policy during Pointer initialization.
        System.setProperty(JAVACPP_MAX_BYTES_PROPERTY, limits.maxTrackedBytes.toString())
        System.setProperty(JAVACPP_MAX_BYTES_ALIAS, limits.maxTrackedBytes.toString())
        System.setProperty(JAVACPP_MAX_PHYSICAL_BYTES_PROPERTY, limits.maxPhysicalBytes.toString())
        System.setProperty(JAVACPP_MAX_PHYSICAL_BYTES_ALIAS, limits.maxPhysicalBytes.toString())
        installedLimits = limits
        return limits
    }

    fun requireInstalled(): AndroidJavaCppMemoryLimits =
        installedLimits ?: error(
            "The Android JavaCPP memory policy was not installed before native runtime startup."
        )

    fun installedOrNull(): AndroidJavaCppMemoryLimits? = installedLimits
}
