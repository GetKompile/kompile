package ai.kompile.chat.local.sdx;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA binding for the SDX LLM C shared library ({@code libsdx_llm.so} / {@code sdx_llm.dll}).
 *
 * <p>The library is loaded lazily in the static initializer. If the native library is not
 * found on the JVM library path, {@link #IS_AVAILABLE} is set to {@code false} and
 * {@link #INSTANCE} remains {@code null}. All callers must guard on {@link #IS_AVAILABLE}
 * before touching {@link #INSTANCE}.</p>
 */
public interface SdxLlmAbi extends Library {

    /** Native library name (without the {@code lib} prefix or file extension). */
    String LIB_NAME = "sdx_llm";

    // ── Runtime lifecycle ─────────────────────────────────────────────────────

    /**
     * Allocate and initialise the SDX LLM runtime context.
     *
     * @return opaque runtime pointer, or {@code null} on failure
     */
    Pointer sdxLlmCreateRuntime();

    /**
     * Destroy the runtime and release all associated resources.
     *
     * @param runtime the runtime pointer returned by {@link #sdxLlmCreateRuntime()}
     * @return 0 on success, non-zero on error
     */
    int sdxLlmDestroyRuntime(Pointer runtime);

    /**
     * Return the ABI version supported by the loaded library.
     *
     * @param runtime the active runtime
     * @return integer ABI version (2 for canonical SDZ resolution + chat-template rendering)
     */
    int sdxLlmAbiVersion(Pointer runtime);

    /**
     * Resolve and validate a canonical compiled SDZ for one target. The output JSON owns
     * the immutable runtime model and text-asset paths and must be released with
     * {@link #sdxLlmFree(Pointer, Pointer)}.
     */
    int sdxLlmResolveModelBundle(Pointer runtime, String sourceSdz, String targetProfile,
                                 String cacheDirectory, PointerByReference outJson);

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Load a model into the runtime.
     *
     * @param runtime        the active runtime
     * @param modelPath      filesystem path to the model file (GGUF/safetensors/etc.)
     * @param tokenizerPath  filesystem path to the tokenizer, or {@code null} to auto-detect
     * @param optionsJson    JSON string of load options, or {@code null} for defaults
     * @return opaque model pointer, or {@code null} on failure
     */
    Pointer sdxLlmLoadModel(Pointer runtime, String modelPath, String tokenizerPath,
                            String optionsJson);

    /**
     * Open a resolver-produced immutable accelerator bundle through the shared native
     * SDX text session. The target profile selects strict accelerator-only options.
     */
    Pointer sdxLlmLoadCompiledModel(Pointer runtime, String bundlePath, String tokenizerPath,
                                    String targetProfile, String optionsJson);

    /**
     * Unload a model and release its resources.
     *
     * @param runtime the active runtime
     * @param model   the model pointer returned by {@link #sdxLlmLoadModel}
     * @return 0 on success, non-zero on error
     */
    int sdxLlmUnloadModel(Pointer runtime, Pointer model);

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Run text generation for a single prompt.
     *
     * <p>On success ({@code return == 0}) the caller must read {@code outText.getValue()}
     * as a C string and then free it with {@link #sdxLlmFree(Pointer, Pointer)}.</p>
     *
     * @param runtime     the active runtime
     * @param model       the loaded model
     * @param prompt      the full prompt string (caller builds from conversation history)
     * @param optionsJson JSON generation options (temperature, maxNewTokens, …), or {@code null}
     * @param outText     receives the allocated output text pointer
     * @return 0 on success, non-zero on error
     */
    int sdxLlmGenerate(Pointer runtime, Pointer model, String prompt, String optionsJson,
                       PointerByReference outText);

    /**
     * Apply the tokenizer-owned chat template to an ordered JSON message array.
     */
    int sdxLlmRenderChatPrompt(Pointer runtime, Pointer model, String messagesJson,
                               int addGenerationPrompt, PointerByReference outPrompt);

    /**
     * Retrieve the last generation result as a JSON object containing timing, token counts,
     * finish reason, etc.
     *
     * @param runtime  the active runtime
     * @param model    the loaded model
     * @param outJson  receives the allocated JSON string pointer
     * @return 0 on success, non-zero on error
     */
    int sdxLlmLastResultJson(Pointer runtime, Pointer model, PointerByReference outJson);

    /**
     * Retrieve model metadata (name, architecture, context length, vocab size, etc.) as JSON.
     *
     * @param runtime the active runtime
     * @param model   the loaded model
     * @param outJson receives the allocated JSON string pointer
     * @return 0 on success, non-zero on error
     */
    int sdxLlmInfoJson(Pointer runtime, Pointer model, PointerByReference outJson);

    // ── Tokenization ─────────────────────────────────────────────────────────

    /**
     * Tokenise text into token IDs.
     *
     * <p>On success the caller owns the array at {@code outIds.getValue()} and must free it
     * with {@link #sdxLlmFree(Pointer, Pointer)}.</p>
     *
     * @param runtime          the active runtime
     * @param model            the loaded model
     * @param text             the text to tokenise
     * @param addSpecialTokens 1 to prepend/append BOS/EOS tokens, 0 otherwise
     * @param outIds           receives a pointer to an array of {@code int} token IDs
     * @param outCount         receives the number of tokens written to {@code outIds}
     * @return 0 on success, non-zero on error
     */
    int sdxLlmTokenize(Pointer runtime, Pointer model, String text, int addSpecialTokens,
                       PointerByReference outIds, IntByReference outCount);

    /**
     * Convert token IDs back to text.
     *
     * <p>On success the caller owns the string at {@code outText.getValue()} and must free it
     * with {@link #sdxLlmFree(Pointer, Pointer)}.</p>
     *
     * @param runtime           the active runtime
     * @param model             the loaded model
     * @param ids               array of token IDs
     * @param count             number of elements in {@code ids}
     * @param skipSpecialTokens 1 to suppress BOS/EOS/PAD tokens in the output, 0 to include them
     * @param outText           receives the allocated decoded string pointer
     * @return 0 on success, non-zero on error
     */
    int sdxLlmDetokenize(Pointer runtime, Pointer model, int[] ids, int count,
                         int skipSpecialTokens, PointerByReference outText);

    // ── Memory management ─────────────────────────────────────────────────────

    /**
     * Free a pointer that was allocated by the library (e.g. the output of
     * {@link #sdxLlmGenerate}, {@link #sdxLlmTokenize}, etc.).
     *
     * @param runtime the active runtime (used to locate the correct allocator)
     * @param pointer the pointer to free
     */
    void sdxLlmFree(Pointer runtime, Pointer pointer);

    // ── Error reporting ───────────────────────────────────────────────────────

    /**
     * Copy the last error message into a caller-provided byte buffer.
     *
     * @param runtime  the active runtime
     * @param buffer   destination buffer for the null-terminated error string
     * @param capacity size of {@code buffer} in bytes
     * @return number of bytes written (excluding the null terminator), or 0 if no error
     */
    int sdxLlmGetLastError(Pointer runtime, byte[] buffer, int capacity);

    // ── Static load ──────────────────────────────────────────────────────────

    /** {@code true} if the native {@code libsdx_llm} was successfully loaded at startup. */
    boolean IS_AVAILABLE = Loader.IS_AVAILABLE;

    /**
     * The loaded library instance, or {@code null} if {@link #IS_AVAILABLE} is {@code false}.
     * Always check {@link #IS_AVAILABLE} before using this field.
     */
    SdxLlmAbi INSTANCE = Loader.INSTANCE;

    /**
     * System property checked at load time for an absolute path to {@code libsdx_llm.so}.
     * Set via {@code -Dkompile.chat.sdx.lib=/path/to/libsdx_llm.so} or
     * {@link #configureLibPath(String)} before any class in this interface is first touched.
     */
    String SYSPROP_LIB_PATH = "kompile.chat.sdx.lib";

    /**
     * Configure the native library path before the static initializer runs.
     *
     * <p>Call this (once) before any class that references {@link #IS_AVAILABLE} or
     * {@link #INSTANCE} is loaded. Sets {@code jna.library.path} to the parent directory
     * of the given absolute path and stores the absolute path in
     * {@code kompile.chat.sdx.lib} so the loader can use {@link Native#load(String, Class)}
     * with the exact file when available.</p>
     *
     * @param absoluteLibPath absolute filesystem path to {@code libsdx_llm.so}
     */
    static void configureLibPath(String absoluteLibPath) {
        if (absoluteLibPath == null || absoluteLibPath.isBlank()) return;
        System.setProperty(SYSPROP_LIB_PATH, absoluteLibPath);
        // JNA searches jna.library.path for the library by name
        java.io.File f = new java.io.File(absoluteLibPath);
        String dir = f.getParent();
        if (dir != null) {
            String existing = System.getProperty("jna.library.path", "");
            System.setProperty("jna.library.path",
                    existing.isEmpty() ? dir : dir + java.io.File.pathSeparator + existing);
        }
    }

    /** Internal loader — static inner class defers initialisation until first access. */
    final class Loader {
        static final boolean IS_AVAILABLE;
        static final SdxLlmAbi INSTANCE;

        static {
            SdxLlmAbi inst = null;
            boolean available = false;
            try {
                String absPath = System.getProperty(SYSPROP_LIB_PATH);
                if (absPath != null && !absPath.isBlank() && new java.io.File(absPath).exists()) {
                    // Load by absolute path so there is no ambiguity about which file is used
                    inst = Native.load(absPath, SdxLlmAbi.class);
                } else {
                    // Fall back to library-name search (jna.library.path / LD_LIBRARY_PATH)
                    inst = Native.load(LIB_NAME, SdxLlmAbi.class);
                }
                available = true;
            } catch (UnsatisfiedLinkError e) {
                // Library not present on this system — degrade gracefully.
                // Callers check IS_AVAILABLE before using INSTANCE.
            }
            INSTANCE = inst;
            IS_AVAILABLE = available;
        }

        private Loader() {}
    }
}
