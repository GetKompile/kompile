package ai.kompile.chat.local.android.model

import java.nio.charset.StandardCharsets

internal data class SdxNativeHandle(val address: Long) {
    init {
        require(address != 0L) { "A native SDX handle cannot be null" }
    }
}

internal class SdxPointerByReference {
    var value: SdxNativeHandle? = null
}

/**
 * Android-only direct JNI adapter around libsdx_llm's stable C ABI.
 *
 * JavaCPP remains private to the embedded Graal image. Keeping it out of this
 * ART-facing transport prevents two JVMs from sharing JavaCPP's process-global
 * JNI class, field, and method caches.
 */
internal object SdxAndroidLlmAbi {
    const val ABI_VERSION = SdxAndroidLlmNative.SDX_LLM_ABI_VERSION

    fun interface ChunkCallback {
        fun invoke(chunk: String?)
    }

    fun interface CancelCallback {
        fun invoke(): Int
    }

    fun ensureLoaded() = SdxAndroidLlmNative.ensureLoaded()

    fun sdxLlmCreateRuntime(): SdxNativeHandle? =
        SdxAndroidLlmNative.nativeCreateRuntime().toHandle()

    fun sdxLlmDestroyRuntime(runtime: SdxNativeHandle): Int =
        SdxAndroidLlmNative.nativeDestroyRuntime(runtime.address)

    fun sdxLlmAbiVersion(runtime: SdxNativeHandle): Int =
        SdxAndroidLlmNative.nativeAbiVersion(runtime.address)

    fun sdxLlmPrepareGguf(
        runtime: SdxNativeHandle,
        sourceGguf: String,
        tokenizerPath: String?,
        targetProfile: String,
        cacheDirectory: String,
        optionsJson: String?,
        outJson: SdxPointerByReference
    ): Int = withOutput(outJson) { output ->
        SdxAndroidLlmNative.nativePrepareGguf(
            runtime.address,
            sourceGguf.utf8(),
            tokenizerPath.utf8OrNull(),
            targetProfile.utf8(),
            cacheDirectory.utf8(),
            optionsJson.utf8OrNull(),
            output
        )
    }

    fun sdxLlmResolveModelBundle(
        runtime: SdxNativeHandle,
        sourceSdz: String,
        targetProfile: String,
        cacheDirectory: String,
        outJson: SdxPointerByReference
    ): Int = withOutput(outJson) { output ->
        SdxAndroidLlmNative.nativeResolveModelBundle(
            runtime.address,
            sourceSdz.utf8(),
            targetProfile.utf8(),
            cacheDirectory.utf8(),
            output
        )
    }

    fun sdxLlmLoadCompiledModel(
        runtime: SdxNativeHandle,
        bundlePath: String,
        tokenizerPath: String?,
        targetProfile: String,
        optionsJson: String?
    ): SdxNativeHandle? = SdxAndroidLlmNative.nativeLoadCompiledModel(
        runtime.address,
        bundlePath.utf8(),
        tokenizerPath.utf8OrNull(),
        targetProfile.utf8(),
        optionsJson.utf8OrNull()
    ).toHandle()

    fun sdxLlmUnloadModel(runtime: SdxNativeHandle, model: SdxNativeHandle): Int =
        SdxAndroidLlmNative.nativeUnloadModel(runtime.address, model.address)

    fun sdxLlmRenderChatPrompt(
        runtime: SdxNativeHandle,
        model: SdxNativeHandle,
        messagesJson: String,
        addGenerationPrompt: Int,
        outPrompt: SdxPointerByReference
    ): Int = withOutput(outPrompt) { output ->
        SdxAndroidLlmNative.nativeRenderChatPrompt(
            runtime.address,
            model.address,
            messagesJson.utf8(),
            addGenerationPrompt,
            output
        )
    }

    fun sdxLlmParseChatResult(
        runtime: SdxNativeHandle,
        model: SdxNativeHandle,
        requestJson: String,
        rawText: String,
        outJson: SdxPointerByReference
    ): Int = withOutput(outJson) { output ->
        SdxAndroidLlmNative.nativeParseChatResult(
            runtime.address,
            model.address,
            requestJson.utf8(),
            rawText.utf8(),
            output
        )
    }

    fun sdxLlmLastResultJson(
        runtime: SdxNativeHandle,
        model: SdxNativeHandle,
        outJson: SdxPointerByReference
    ): Int = withOutput(outJson) { output ->
        SdxAndroidLlmNative.nativeLastResultJson(
            runtime.address,
            model.address,
            output
        )
    }

    fun sdxLlmGenerateStreaming(
        runtime: SdxNativeHandle,
        model: SdxNativeHandle,
        prompt: String,
        optionsJson: String?,
        onChunk: ChunkCallback?,
        shouldCancel: CancelCallback?,
        outText: SdxPointerByReference
    ): Int = withOutput(outText) { output ->
        val nativeChunk = onChunk?.let { callback ->
            SdxAndroidLlmNative.ChunkCallback { bytes ->
                callback.invoke(bytes?.let { String(it, StandardCharsets.UTF_8) })
            }
        }
        val nativeCancel = shouldCancel?.let { callback ->
            SdxAndroidLlmNative.CancelCallback { callback.invoke() }
        }
        SdxAndroidLlmNative.nativeGenerateStreaming(
            runtime.address,
            model.address,
            prompt.utf8(),
            optionsJson.utf8OrNull(),
            nativeChunk,
            nativeCancel,
            output
        )
    }

    fun sdxLlmReadUtf8(pointer: SdxNativeHandle): String =
        String(
            checkNotNull(SdxAndroidLlmNative.nativeReadUtf8(pointer.address)) {
                "SDX returned a null UTF-8 result"
            },
            StandardCharsets.UTF_8
        )

    fun sdxLlmFree(runtime: SdxNativeHandle, pointer: SdxNativeHandle) =
        SdxAndroidLlmNative.nativeFree(runtime.address, pointer.address)

    fun sdxLlmGetLastError(runtime: SdxNativeHandle, buffer: ByteArray, capacity: Int): Int =
        SdxAndroidLlmNative.nativeGetLastError(runtime.address, buffer, capacity)

    private inline fun <T> withOutput(
        destination: SdxPointerByReference,
        block: (LongArray) -> T
    ): T {
        val output = longArrayOf(0L)
        val result = block(output)
        destination.value = output[0].toHandle()
        return result
    }

    private fun Long.toHandle(): SdxNativeHandle? =
        takeUnless { it == 0L }?.let(::SdxNativeHandle)

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private fun String?.utf8OrNull(): ByteArray? = this?.utf8()
}
