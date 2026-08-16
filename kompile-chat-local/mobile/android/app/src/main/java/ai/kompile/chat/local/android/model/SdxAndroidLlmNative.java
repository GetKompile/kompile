package ai.kompile.chat.local.android.model;

/**
 * Direct Android JNI transport for the SDX C ABI.
 *
 * This class intentionally has no JavaCPP dependency. Android ART and the embedded
 * GraalVM isolate are separate JVMs in one process, so they must not share JavaCPP's
 * process-global JNI class, field, and method caches.
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
