package ai.kompile.chat.local.android.model

import org.nd4j.dsp.runtime.SdxRuntime

/**
 * The shared SDX session also serves the NNAPI flavor, whose ModelOptions exposes
 * a device compilation cache. Vulkan ModelOptions intentionally has no such API.
 *
 * These flavor-local extensions make that provider boundary explicit. The shared
 * branch is unreachable for mobileVulkan(); fail closed if that invariant changes.
 */
internal val SdxRuntime.ModelOptions.device_compilation_cache_directory: String?
    get() = null

internal fun SdxRuntime.ModelOptions.deviceCompilationCacheDirectory(
    @Suppress("UNUSED_PARAMETER") directory: String
): SdxRuntime.ModelOptions {
    check(backend != SdxRuntime.SDX_BACKEND_NNAPI) {
        "NNAPI compilation cache configuration is unavailable in the Vulkan runtime"
    }
    return this
}
