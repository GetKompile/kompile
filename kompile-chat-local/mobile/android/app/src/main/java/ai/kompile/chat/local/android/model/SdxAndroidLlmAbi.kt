package ai.kompile.chat.local.android.model

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.nd4j.dsp.model.SdxLlmNative

internal class SdxPointerByReference {
    var value: Pointer? = null
}

/**
 * Android-only adapter around DL4J's JavaCPP transport for libsdx_llm.
 *
 * String and out-parameter conversion stays here so import and execution cannot
 * accidentally fall back to the desktop JNA ABI.
 */
internal object SdxAndroidLlmAbi {
    const val ABI_VERSION = SdxLlmNative.SDX_LLM_ABI_VERSION

    fun interface ChunkCallback {
        fun invoke(chunk: Pointer?)
    }

    fun interface CancelCallback {
        fun invoke(): Int
    }

    fun sdxLlmCreateRuntime(): Pointer? = SdxLlmNative.sdxLlmCreateRuntime()
    fun sdxLlmDestroyRuntime(runtime: Pointer): Int = SdxLlmNative.sdxLlmDestroyRuntime(runtime)
    fun sdxLlmAbiVersion(runtime: Pointer): Int = SdxLlmNative.sdxLlmAbiVersion(runtime)

    fun sdxLlmPrepareGguf(
        runtime: Pointer,
        sourceGguf: String,
        tokenizerPath: String?,
        targetProfile: String,
        cacheDirectory: String,
        optionsJson: String?,
        outJson: SdxPointerByReference
    ): Int = withStrings(sourceGguf, tokenizerPath, targetProfile, cacheDirectory, optionsJson) { values ->
        withOutput(outJson) { output ->
            SdxLlmNative.sdxLlmPrepareGguf(
                runtime, values[0], values[1], values[2], values[3], values[4], output
            )
        }
    }

    fun sdxLlmResolveModelBundle(
        runtime: Pointer,
        sourceSdz: String,
        targetProfile: String,
        cacheDirectory: String,
        outJson: SdxPointerByReference
    ): Int = withStrings(sourceSdz, targetProfile, cacheDirectory) { values ->
        withOutput(outJson) { output ->
            SdxLlmNative.sdxLlmResolveModelBundle(
                runtime, values[0], values[1], values[2], output
            )
        }
    }

    fun sdxLlmLoadCompiledModel(
        runtime: Pointer,
        bundlePath: String,
        tokenizerPath: String?,
        targetProfile: String,
        optionsJson: String?
    ): Pointer? = withStrings(bundlePath, tokenizerPath, targetProfile, optionsJson) { values ->
        SdxLlmNative.sdxLlmLoadCompiledModel(runtime, values[0], values[1], values[2], values[3])
    }

    fun sdxLlmUnloadModel(runtime: Pointer, model: Pointer): Int =
        SdxLlmNative.sdxLlmUnloadModel(runtime, model)

    fun sdxLlmRenderChatPrompt(
        runtime: Pointer,
        model: Pointer,
        messagesJson: String,
        addGenerationPrompt: Int,
        outPrompt: SdxPointerByReference
    ): Int = withStrings(messagesJson) { values ->
        withOutput(outPrompt) { output ->
            SdxLlmNative.sdxLlmRenderChatPrompt(
                runtime, model, values[0], addGenerationPrompt, output
            )
        }
    }

    fun sdxLlmGenerateStreaming(
        runtime: Pointer,
        model: Pointer,
        prompt: String,
        optionsJson: String?,
        onChunk: ChunkCallback?,
        shouldCancel: CancelCallback?,
        outText: SdxPointerByReference
    ): Int = withStrings(prompt, optionsJson) { values ->
        val nativeChunk = onChunk?.let { callback ->
            object : SdxLlmNative.ChunkCallback() {
                override fun call(chunk: BytePointer?) = callback.invoke(chunk)
            }
        }
        val nativeCancel = shouldCancel?.let { callback ->
            object : SdxLlmNative.CancelCallback() {
                override fun call(): Int = callback.invoke()
            }
        }
        withOutput(outText) { output ->
            SdxLlmNative.sdxLlmGenerateStreaming(
                runtime, model, values[0], values[1], nativeChunk, nativeCancel, output
            )
        }
    }

    fun sdxLlmFree(runtime: Pointer, pointer: Pointer) = SdxLlmNative.sdxLlmFree(runtime, pointer)

    fun sdxLlmGetLastError(runtime: Pointer, buffer: ByteArray, capacity: Int): Int =
        BytePointer(buffer.size.toLong()).use { nativeBuffer ->
            nativeBuffer.put(buffer, 0, buffer.size)
            val status = SdxLlmNative.sdxLlmGetLastError(runtime, nativeBuffer, capacity)
            nativeBuffer.get(buffer)
            status
        }

    private inline fun <T> withOutput(
        destination: SdxPointerByReference,
        block: (PointerPointer<Pointer>) -> T
    ): T = PointerPointer<Pointer>(1).use { output ->
        output.put(0, null as Pointer?)
        val result = block(output)
        destination.value = output.get(0)
        result
    }

    private inline fun <T> withStrings(
        vararg strings: String?,
        block: (Array<BytePointer?>) -> T
    ): T {
        val values = Array<BytePointer?>(strings.size) { index ->
            strings[index]?.let(::BytePointer)
        }
        return try {
            block(values)
        } finally {
            values.forEach { it?.close() }
        }
    }
}
