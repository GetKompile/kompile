package ai.kompile.chat.local.android.model;

/**
 * Direct Android JNI transport for the SDX C ABI.
 *
 * This class intentionally has no JavaCPP dependency. Android ART and the embedded
 * GraalVM isolate are separate JVMs in one process, so they must not share JavaCPP's
 * process-global JNI class, field, and method caches.
 *
 * <p>This is intentionally the Android-used subset of {@code sdx_llm_c.h}, not a
 * second full binding. Android prepares verified GGUF in the isolated importer process,
 * loads compiled bundles, and implements chat as render + streaming generate + parse.
 * Raw-model loading, blocking generation, VLM/audio, info, and detokenization therefore
 * remain available in the stable C ABI without an ART wrapper. The packaging script
 * derives its JNI symbol audit directly from the native declarations below.</p>
 */
public final class SdxAndroidLlmNative {
    public static final int SDX_LLM_ABI_VERSION = 2;

    static {
        System.loadLibrary("jnisdx_llm");
    }

    private SdxAndroidLlmNative() {
    }

    public static void ensureLoaded() {
        // Invoking this method initializes the class and loads the direct JNI bridge.
    }

    public interface ChunkCallback {
        void onChunk(byte[] utf8Chunk);
    }

    public interface CancelCallback {
        int shouldCancel();
    }

    public static native long nativeCreateRuntime();
    public static native int nativeDestroyRuntime(long runtime);
    public static native int nativeAbiVersion(long runtime);

    public static native int nativePrepareGguf(
            long runtime,
            byte[] sourceGguf,
            byte[] tokenizerPath,
            byte[] targetProfile,
            byte[] cacheDirectory,
            byte[] optionsJson,
            long[] outJson);

    public static native int nativeResolveModelBundle(
            long runtime,
            byte[] sourceSdz,
            byte[] targetProfile,
            byte[] cacheDirectory,
            long[] outJson);

    public static native long nativeLoadCompiledModel(
            long runtime,
            byte[] bundlePath,
            byte[] tokenizerPath,
            byte[] targetProfile,
            byte[] optionsJson);

    public static native int nativeUnloadModel(long runtime, long model);

    public static native int nativeRenderChatPrompt(
            long runtime,
            long model,
            byte[] messagesJson,
            int addGenerationPrompt,
            long[] outPrompt);

    public static native int nativeTokenCount(
            long runtime,
            long model,
            byte[] text,
            int addSpecialTokens,
            int[] outCount);

    public static native int nativeParseChatResult(
            long runtime,
            long model,
            byte[] requestJson,
            byte[] rawText,
            long[] outJson);

    public static native int nativeLastResultJson(
            long runtime,
            long model,
            long[] outJson);

    public static native int nativeGenerateStreaming(
            long runtime,
            long model,
            byte[] prompt,
            byte[] optionsJson,
            ChunkCallback onChunk,
            CancelCallback shouldCancel,
            long[] outText);

    public static native byte[] nativeReadUtf8(long pointer);
    public static native void nativeFree(long runtime, long pointer);
    public static native int nativeGetLastError(long runtime, byte[] buffer, int capacity);
}
