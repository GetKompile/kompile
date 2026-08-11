package ai.kompile.chat.local.sdx;

import ai.kompile.chat.local.ChatException;
import ai.kompile.chat.local.ChatModel;
import ai.kompile.chat.local.ChatRequest;
import ai.kompile.chat.local.ChatResponse;
import ai.kompile.chat.local.GenOptions;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * {@link ChatModel} implementation backed by the SDX LLM native library.
 *
 * <p>The model is loaded lazily on the first {@link #generate} call via
 * {@link #ensureLoaded()}. If the native library is unavailable or the model file
 * does not exist, {@link #isAvailable()} returns {@code false} and {@link #generate}
 * throws {@link ChatException}.</p>
 *
 * <p>Callers must {@link #close()} this instance when done to release the runtime
 * and model handles.</p>
 *
 * <p>The imported tokenizer/model owns chat-template rendering, reasoning blocks,
 * special tokens, and tool-call decoding. This adapter only transports structured
 * requests and responses through the SDX ABI.</p>
 */
public final class SdxChatModel implements ChatModel, Closeable {

    // ── Chat template enum ────────────────────────────────────────────────────

    /** Compatibility enum retained for callers compiled against the old adapter. */
    @Deprecated
    public enum ChatTemplate {
        MODEL_OWNED,
        CHATML_IM_NOTHINK,
        CHATML_IM,
        GENERIC_PIPE,
        PLAIN;

        /** Filename sniffing is intentionally disabled; model metadata is authoritative. */
        public static ChatTemplate sniff(String modelFilename) {
            return MODEL_OWNED;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String modelPath;
    private final String tokenizerPath; // may be null
    private final ChatTemplate template;

    private Pointer runtime;
    private Pointer model;
    private boolean loaded = false;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Create a model instance pointing at the given paths.
     * The imported tokenizer/model owns its chat protocol; filenames are never inspected.
     * The native library is NOT loaded until {@link #ensureLoaded()} is called.
     *
     * @param modelPath      filesystem path to the model file (GGUF, safetensors, …)
     * @param tokenizerPath  filesystem path to the tokenizer, or {@code null} to auto-detect
     */
    public SdxChatModel(String modelPath, String tokenizerPath) {
        this(modelPath, tokenizerPath, ChatTemplate.MODEL_OWNED);
    }

    /**
     * Compatibility constructor retained for callers compiled against the old API.
     * Protocol selection is always model-owned; the template argument is ignored.
     *
     * @param modelPath      filesystem path to the model file (GGUF, safetensors, …)
     * @param tokenizerPath  filesystem path to the tokenizer, or {@code null} when embedded
     * @param template       retained compatibility value; model metadata remains authoritative
     */
    public SdxChatModel(String modelPath, String tokenizerPath, ChatTemplate template) {
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.template = ChatTemplate.MODEL_OWNED;
    }

    // ── ChatModel ────────────────────────────────────────────────────────────

    @Override
    public boolean isAvailable() {
        return SdxLlmAbi.IS_AVAILABLE && new File(modelPath).exists();
    }

    @Override
    public String modelId() {
        return "sdx-local:" + new File(modelPath).getName();
    }

    /** Return the chat template being used for prompt construction. */
    public ChatTemplate chatTemplate() {
        return template;
    }

    @Override
    public ChatResponse generate(ChatRequest request, GenOptions opts) throws ChatException {
        if (!isAvailable()) {
            throw new ChatException("SDX not available (library not loaded or model file missing): "
                    + modelPath);
        }
        ensureLoaded();

        PointerByReference ref = new PointerByReference();
        int status = SdxLlmAbi.INSTANCE.sdxLlmGenerateChat(runtime, model, request.toJson(),
                opts.toOptionsJson(), ref);
        if (status != 0) {
            throw new ChatException("Generate failed: " + getLastError());
        }

        Pointer outPtr = ref.getValue();
        if (outPtr == null) {
            throw new ChatException("Generate returned null output pointer");
        }
        String resultJson = outPtr.getString(0);
        SdxLlmAbi.INSTANCE.sdxLlmFree(runtime, outPtr);
        return ChatResponse.fromStructuredJson(resultJson);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Load the native runtime and model if not already loaded.
     * Must only be called after verifying {@link SdxLlmAbi#IS_AVAILABLE}.
     *
     * @throws ChatException if the runtime or model cannot be initialised
     */
    public synchronized void ensureLoaded() throws ChatException {
        if (loaded) {
            return;
        }
        runtime = SdxLlmAbi.INSTANCE.sdxLlmCreateRuntime();
        if (runtime == null) {
            throw new ChatException("Failed to create SDX LLM runtime: " + getLastErrorNoRuntime());
        }
        int abiVersion = SdxLlmAbi.INSTANCE.sdxLlmAbiVersion(runtime);
        if (abiVersion != SdxLlmAbi.ABI_VERSION) {
            SdxLlmAbi.INSTANCE.sdxLlmDestroyRuntime(runtime);
            runtime = null;
            throw new ChatException("SDX LLM ABI mismatch: required "
                    + SdxLlmAbi.ABI_VERSION + " but loaded " + abiVersion);
        }
        model = SdxLlmAbi.INSTANCE.sdxLlmLoadModel(runtime, modelPath, tokenizerPath, null);
        if (model == null) {
            String err = getLastError();
            SdxLlmAbi.INSTANCE.sdxLlmDestroyRuntime(runtime);
            runtime = null;
            throw new ChatException("Failed to load model: " + err);
        }
        loaded = true;
    }

    /** Close the model and runtime, releasing native resources. */
    @Override
    public synchronized void close() {
        if (!loaded) {
            return;
        }
        try {
            if (model != null) {
                SdxLlmAbi.INSTANCE.sdxLlmUnloadModel(runtime, model);
                model = null;
            }
        } finally {
            if (runtime != null) {
                SdxLlmAbi.INSTANCE.sdxLlmDestroyRuntime(runtime);
                runtime = null;
            }
            loaded = false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Read the last error from the native runtime (runtime must be non-null). */
    private String getLastError() {
        if (runtime == null || !SdxLlmAbi.IS_AVAILABLE) {
            return "<no runtime>";
        }
        byte[] buf = new byte[512];
        SdxLlmAbi.INSTANCE.sdxLlmGetLastError(runtime, buf, buf.length);
        return new String(buf, StandardCharsets.UTF_8).trim().replace("\0", "");
    }

    /** Read the last error before the runtime pointer is available. */
    private static String getLastErrorNoRuntime() {
        return "<runtime creation failed — check libsdx_llm is on LD_LIBRARY_PATH>";
    }
}
