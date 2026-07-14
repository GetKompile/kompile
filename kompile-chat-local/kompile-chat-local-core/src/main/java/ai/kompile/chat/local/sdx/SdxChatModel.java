package ai.kompile.chat.local.sdx;

import ai.kompile.chat.local.ChatException;
import ai.kompile.chat.local.ChatModel;
import ai.kompile.chat.local.GenOptions;
import ai.kompile.chat.local.Message;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

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
 * <h2>Chat templates</h2>
 * <p>The SDX C ABI passes prompts verbatim — no template is applied inside the library.
 * This class applies the correct template before calling the ABI.  The template is
 * selected by {@link ChatTemplate}: pass one explicitly or let
 * {@link ChatTemplate#sniff(String)} auto-detect from the model filename.</p>
 */
public final class SdxChatModel implements ChatModel, Closeable {

    // ── Chat template enum ────────────────────────────────────────────────────

    /**
     * Chat prompt template variants.
     *
     * <ul>
     *   <li>{@link #CHATML_IM} — OpenAI ChatML / Qwen2.x / Phi-3.x style:
     *       {@code <|im_start|>role\ncontent<|im_end|>\n}</li>
     *   <li>{@link #GENERIC_PIPE} — older generic {@code <|role|>\ncontent\n} style</li>
     *   <li>{@link #PLAIN} — no template: messages are concatenated with role prefixes
     *       separated by newlines (for base / continued-text models)</li>
     * </ul>
     */
    public enum ChatTemplate {
        /**
         * ChatML with {@code <|im_start|>} / {@code <|im_end|>} delimiters,
         * with a {@code <think>\n\n</think>} block injected at the start of each
         * assistant turn to suppress chain-of-thought reasoning output.
         * Used by Qwen3.x models (which default to thinking mode).
         */
        CHATML_IM_NOTHINK,

        /**
         * ChatML with {@code <|im_start|>} / {@code <|im_end|>} delimiters.
         * Used by Qwen2.x, Phi-3.x, Hermes-2, and many Mistral-fine-tunes.
         */
        CHATML_IM,

        /**
         * Generic {@code <|system|>} / {@code <|user|>} / {@code <|assistant|>} pipe style.
         * A reasonable default for models that do not expose their template via filename.
         */
        GENERIC_PIPE,

        /**
         * No role delimiters — just {@code Role: content\n} blocks.
         * For base (non-instruct) models or plain-text continuation.
         */
        PLAIN;

        /**
         * Infer the best template from the model filename (case-insensitive).
         *
         * <p>Detection rules (applied in order, first match wins):
         * <ol>
         *   <li>Name contains {@code qwen3} or {@code qwen35} → {@link #CHATML_IM_NOTHINK}</li>
         *   <li>Name contains {@code qwen} or {@code phi} → {@link #CHATML_IM}</li>
         *   <li>Name contains {@code hermes} or {@code chatml} → {@link #CHATML_IM}</li>
         *   <li>Name contains {@code instruct} or {@code chat} → {@link #GENERIC_PIPE}</li>
         *   <li>Otherwise → {@link #PLAIN}</li>
         * </ol>
         *
         * @param modelFilename the base filename or parent directory name of the model
         *                      (e.g. {@code qwen2.5-0.5b-instruct-q4_k_m.gguf} or
         *                      {@code Qwen3.5-0.8B-Q4_K_M.gguf})
         * @return the detected template
         */
        public static ChatTemplate sniff(String modelFilename) {
            if (modelFilename == null) return GENERIC_PIPE;
            String lower = modelFilename.toLowerCase(Locale.ROOT);
            // Qwen3.x family (has thinking mode — needs suppression)
            if (lower.contains("qwen3") || lower.contains("qwen35")) {
                return CHATML_IM_NOTHINK;
            }
            // Qwen2.x, Phi-3.x and other ChatML models
            if (lower.contains("qwen") || lower.contains("phi")
                    || lower.contains("hermes") || lower.contains("chatml")) {
                return CHATML_IM;
            }
            if (lower.contains("instruct") || lower.contains("chat")) {
                return GENERIC_PIPE;
            }
            return PLAIN;
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
     * The chat template is auto-detected from the model filename.
     * The native library is NOT loaded until {@link #ensureLoaded()} is called.
     *
     * @param modelPath      filesystem path to the model file (GGUF, safetensors, …)
     * @param tokenizerPath  filesystem path to the tokenizer, or {@code null} to auto-detect
     */
    public SdxChatModel(String modelPath, String tokenizerPath) {
        this(modelPath, tokenizerPath,
                ChatTemplate.sniff(new File(modelPath).getName()));
    }

    /**
     * Create a model instance with an explicit chat template.
     *
     * @param modelPath      filesystem path to the model file (GGUF, safetensors, …)
     * @param tokenizerPath  filesystem path to the tokenizer, or {@code null} to auto-detect
     * @param template       the prompt template to apply before each generation call
     */
    public SdxChatModel(String modelPath, String tokenizerPath, ChatTemplate template) {
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.template = (template != null) ? template : ChatTemplate.sniff(new File(modelPath).getName());
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

    /** Prefix injected at the end of prompts to force tool-call JSON completion. */
    static final String TOOL_PREFILL = "{\"tool\": \"";

    @Override
    public String generate(List<Message> messages, GenOptions opts) throws ChatException {
        if (!isAvailable()) {
            throw new ChatException("SDX not available (library not loaded or model file missing): "
                    + modelPath);
        }
        ensureLoaded();

        String prompt = buildPrompt(messages, template);

        // If the prompt ends with our tool-call pre-fill prefix, remember to prepend it to output
        boolean hasPrefill = prompt.endsWith(TOOL_PREFILL);

        PointerByReference ref = new PointerByReference();
        int status = SdxLlmAbi.INSTANCE.sdxLlmGenerate(runtime, model, prompt,
                opts.toOptionsJson(), ref);
        if (status != 0) {
            throw new ChatException("Generate failed: " + getLastError());
        }

        Pointer outPtr = ref.getValue();
        if (outPtr == null) {
            throw new ChatException("Generate returned null output pointer");
        }
        String result = outPtr.getString(0);
        SdxLlmAbi.INSTANCE.sdxLlmFree(runtime, outPtr);
        String text = result.trim();

        // Restore the pre-fill prefix so the caller sees the complete JSON
        if (hasPrefill && !text.startsWith("{")) {
            text = TOOL_PREFILL + text;
        }
        return text;
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

    /**
     * Build the prompt string from message history using the specified template.
     *
     * <h3>CHATML_IM (Qwen2.x, Phi-3, Hermes-2)</h3>
     * <pre>
     * &lt;|im_start|&gt;system
     * ...content...&lt;|im_end|&gt;
     * &lt;|im_start|&gt;user
     * ...content...&lt;|im_end|&gt;
     * &lt;|im_start|&gt;assistant
     * </pre>
     *
     * <h3>GENERIC_PIPE</h3>
     * <pre>
     * &lt;|system|&gt;
     * ...content...
     * &lt;|user|&gt;
     * ...content...
     * &lt;|assistant|&gt;
     * </pre>
     *
     * <h3>PLAIN</h3>
     * <pre>
     * System: ...content...
     * User: ...content...
     * Assistant:
     * </pre>
     *
     * @param messages conversation history including system prompt
     * @param tmpl     which template style to apply
     * @return fully-formed prompt string ready for the SDX generate call
     */
    static String buildPrompt(List<Message> messages, ChatTemplate tmpl) {
        StringBuilder sb = new StringBuilder();
        switch (tmpl) {
            case CHATML_IM_NOTHINK -> {
                // Qwen3.x: same as CHATML_IM but assistant turn starts with
                // <think>\n\n</think>\n to immediately end the thinking phase.
                // For user turns (not assistant/tool_result), we also inspect whether
                // the last message is a user turn or tool_result turn to decide whether
                // to pre-fill with {"tool": to force tool call format.
                boolean lastIsToolResult = !messages.isEmpty() &&
                    "tool_result".equals(messages.get(messages.size() - 1).role());
                for (Message m : messages) {
                    String role = switch (m.role()) {
                        case "system"      -> "system";
                        case "user"        -> "user";
                        case "assistant"   -> "assistant";
                        case "tool_result" -> "user";
                        default            -> "user";
                    };
                    sb.append("<|im_start|>").append(role).append('\n')
                      .append(m.content()).append("<|im_end|>\n");
                }
                // Inject empty think block to suppress chain-of-thought
                sb.append("<|im_start|>assistant\n<think>\n\n</think>\n");
                // Pre-fill with {"tool": on the first user message turn (not after tool result)
                // to force the model to complete a tool call JSON
                if (!lastIsToolResult) {
                    sb.append("{\"tool\": \"");
                }
            }
            case CHATML_IM -> {
                for (Message m : messages) {
                    String role = switch (m.role()) {
                        case "system"      -> "system";
                        case "user"        -> "user";
                        case "assistant"   -> "assistant";
                        case "tool_result" -> "user";
                        default            -> "user";
                    };
                    sb.append("<|im_start|>").append(role).append('\n')
                      .append(m.content()).append("<|im_end|>\n");
                }
                sb.append("<|im_start|>assistant\n");
            }
            case GENERIC_PIPE -> {
                for (Message m : messages) {
                    String roleTag = switch (m.role()) {
                        case "system"      -> "<|system|>";
                        case "user"        -> "<|user|>";
                        case "assistant"   -> "<|assistant|>";
                        case "tool_result" -> "<|user|>";
                        default            -> "<|user|>";
                    };
                    sb.append(roleTag).append('\n').append(m.content()).append('\n');
                }
                sb.append("<|assistant|>");
            }
            case PLAIN -> {
                for (Message m : messages) {
                    String label = switch (m.role()) {
                        case "system"    -> "System";
                        case "user"      -> "User";
                        case "assistant" -> "Assistant";
                        default          -> "User";
                    };
                    sb.append(label).append(": ").append(m.content()).append('\n');
                }
                sb.append("Assistant:");
            }
        }
        return sb.toString();
    }

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
