package ai.kompile.chat.local.sdx;

import ai.kompile.chat.local.ChatException;
import ai.kompile.chat.local.ChatModel;
import ai.kompile.chat.local.GenOptions;
import ai.kompile.chat.local.Message;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@link ChatModel} backed by the {@code sdx-llm} CLI binary running as a subprocess.
 *
 * <p>Unlike {@link SdxChatModel} (JNA in-process), this implementation forks the
 * {@code sdx-llm generate} command for each generation call. This avoids the GraalVM
 * isolate conflict that occurs when {@code libsdx_llm.so} is loaded into a JVM process
 * (both the JVM and the GraalVM native image inside the shared library try to own the
 * same ND4J native resources, causing an {@code ExceptionInInitializerError} on load).</p>
 *
 * <h2>Subprocess approach</h2>
 * <p>Each call to {@link #generate} writes the formatted prompt to a temp file, invokes:
 * <pre>
 *   sdx-llm generate \
 *     --model &lt;modelPath&gt; \
 *     --tokenizer &lt;tokenizerPath&gt; \
 *     --prompt-file &lt;tmpFile&gt; \
 *     --max-new-tokens &lt;n&gt; \
 *     [--greedy | --temperature &lt;t&gt;] \
 *     --stats
 * </pre>
 * then collects stdout (generated text + stats JSON on stderr) and returns the trimmed
 * output up to the first EOS marker ({@code <|im_end|>} or similar).</p>
 *
 * <h2>Library path</h2>
 * <p>The {@code sdx-llm} binary side-loads native libraries from {@code ../lib} relative
 * to its own directory. When the binary and its companion libs (libjnind4jcpu.so, etc.)
 * are co-located under an {@code aot-sdk} layout, this Just Works. If you keep a
 * standalone copy of {@code sdx-llm} elsewhere, set {@code sdxLibDir} to the directory
 * containing the companion libs — it is prepended to {@code LD_LIBRARY_PATH}.</p>
 *
 * <h2>Model formats</h2>
 * <ul>
 *   <li>{@code .gguf fp16} — works.</li>
 *   <li>{@code .gguf q4_k_m} — works (Q5_0/Q5_1 dequantization fixed 2026-07-12;
 *       requires a sidecar {@code tokenizer.json} alongside the model file so that
 *       ChatML special tokens like {@code <|im_start|>} are tokenized atomically — the
 *       GGUF-embedded tokenizer path misses special tokens).</li>
 *   <li>{@code .sdz} — pre-imported SDX native format; same results as GGUF equivalents.</li>
 * </ul>
 */
public final class SdxSubprocessChatModel implements ChatModel, Closeable {

    private final String sdxBinPath;         // absolute path to sdx-llm binary
    private final String sdxLibDir;          // optional: dir for LD_LIBRARY_PATH
    private final String modelPath;
    private final String tokenizerPath;      // may be null
    private final SdxChatModel.ChatTemplate template;
    private final long timeoutSeconds;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Create a subprocess model.
     *
     * @param sdxBinPath     absolute path to the {@code sdx-llm} binary
     * @param sdxLibDir      directory containing the binary's companion native libs
     *                       (prepended to {@code LD_LIBRARY_PATH}), or {@code null} to
     *                       use the sibling {@code ../lib} auto-resolution
     * @param modelPath      path to the model file (.gguf or .sdz)
     * @param tokenizerPath  tokenizer.json file or directory, or {@code null} to auto-detect
     * @param timeoutSeconds per-generate wall-clock timeout in seconds (default 120)
     */
    public SdxSubprocessChatModel(String sdxBinPath, String sdxLibDir,
                                   String modelPath, String tokenizerPath,
                                   long timeoutSeconds) {
        this.sdxBinPath = sdxBinPath;
        this.sdxLibDir = sdxLibDir;
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.template = SdxChatModel.ChatTemplate.sniff(new File(modelPath).getName());
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
    }

    /**
     * Create a subprocess model with 120 second timeout.
     * Library path is resolved from {@code sdx-llm}'s sibling {@code ../lib} dir.
     */
    public SdxSubprocessChatModel(String sdxBinPath, String modelPath, String tokenizerPath) {
        this(sdxBinPath, null, modelPath, tokenizerPath, 120);
    }

    // ── ChatModel ────────────────────────────────────────────────────────────

    @Override
    public boolean isAvailable() {
        File bin = new File(sdxBinPath);
        File mod = new File(modelPath);
        return bin.exists() && bin.canExecute() && mod.exists();
    }

    @Override
    public String modelId() {
        return "sdx-subprocess:" + new File(modelPath).getName();
    }

    /** Return the chat template used for prompt construction. */
    public SdxChatModel.ChatTemplate chatTemplate() {
        return template;
    }

    @Override
    public String generate(List<Message> messages, GenOptions opts) throws ChatException {
        if (!isAvailable()) {
            throw new ChatException("SdxSubprocessChatModel not available: binary="
                    + sdxBinPath + ", model=" + modelPath);
        }

        // Build prompt using the same template logic as the JNA model
        String basePrompt = SdxChatModel.buildPrompt(messages, template);

        // Determine whether to inject the tool-call JSON pre-fill.
        // We inject ONLY when:
        //   1. The last message is NOT a tool_result (would mean we want a synthesis/answer)
        //   2. There is no tool_result anywhere in the history (if there IS one, we're in
        //      the "answer after tool" phase and want free-text synthesis, not another JSON)
        //   3. The history only contains system + user messages (i.e. first call, not synthesis)
        //      Synthesis is triggered by the special "Please synthesize..." user message added
        //      by ChatEngine after maxToolRounds hit — detect it by the synthesis marker.
        // For templates that don't support structured completion, skip pre-fill.
        boolean lastIsToolResult = !messages.isEmpty()
                && "tool_result".equals(messages.get(messages.size() - 1).role());
        boolean anyToolResult = messages.stream()
                .anyMatch(m -> "tool_result".equals(m.role()));
        boolean lastIsSynthesisRequest = !messages.isEmpty()
                && messages.get(messages.size() - 1).content() != null
                && messages.get(messages.size() - 1).content().startsWith("Please synthesize");
        boolean hasPrefill = !lastIsToolResult
                && !anyToolResult
                && !lastIsSynthesisRequest
                && (template == SdxChatModel.ChatTemplate.CHATML_IM
                    || template == SdxChatModel.ChatTemplate.GENERIC_PIPE
                    || template == SdxChatModel.ChatTemplate.PLAIN);
        String prompt = hasPrefill ? basePrompt + SdxChatModel.TOOL_PREFILL : basePrompt;

        // Write prompt to a temp file (avoids all shell quoting / newline-collapse issues)
        Path promptFile;
        try {
            promptFile = Files.createTempFile("sdx_prompt_", ".txt");
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ChatException("Failed to write prompt temp file: " + e.getMessage());
        }

        try {
            List<String> cmd = buildCommand(promptFile, opts, hasPrefill);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", resolveLibPath());
            pb.redirectErrorStream(false); // stdout = text; stderr = stats + logs

            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                throw new ChatException("Failed to launch sdx-llm: " + e.getMessage());
            }

            // Drain stderr in background to avoid blocking
            StringBuilder stderr = new StringBuilder();
            Thread errDrain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        stderr.append(l).append('\n');
                    }
                } catch (IOException ignored) {}
            }, "sdx-stderr-drain");
            errDrain.setDaemon(true);
            errDrain.start();

            // Read stdout (generated text)
            StringBuilder stdout = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    stdout.append(l).append('\n');
                }
            } catch (IOException e) {
                proc.destroyForcibly();
                throw new ChatException("Error reading sdx-llm stdout: " + e.getMessage());
            }

            boolean finished;
            try {
                finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                throw new ChatException("sdx-llm subprocess interrupted");
            }
            if (!finished) {
                proc.destroyForcibly();
                throw new ChatException("sdx-llm timed out after " + timeoutSeconds + "s");
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                // Extract first meaningful error line from stderr
                String errSummary = stderr.toString().lines()
                        .filter(l -> l.contains("Exception") || l.contains("Error") || l.startsWith("Caused by"))
                        .findFirst().orElse("exit=" + exit);
                throw new ChatException("sdx-llm exited with " + exit + ": " + errSummary);
            }

            // Strip EOS tokens and trailing whitespace from output
            String text = stripEosTokens(stdout.toString()).trim();

            // Restore the pre-fill prefix so the caller sees the complete JSON object.
            // The prompt ended with {"tool": " — sdx-llm continued from there.
            if (hasPrefill && !text.startsWith("{")) {
                text = SdxChatModel.TOOL_PREFILL + text;
            }
            return text;

        } finally {
            try {
                Files.deleteIfExists(promptFile);
            } catch (IOException ignored) {}
        }
    }

    /** No-op: subprocess model has no persistent resources. */
    @Override
    public void close() {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> buildCommand(Path promptFile, GenOptions opts, boolean toolCallMode) {
        List<String> cmd = new ArrayList<>();
        cmd.add(sdxBinPath);
        cmd.add("generate");
        cmd.add("--model");
        cmd.add(modelPath);
        if (tokenizerPath != null) {
            cmd.add("--tokenizer");
            cmd.add(tokenizerPath);
        }
        cmd.add("--prompt-file");
        cmd.add(promptFile.toAbsolutePath().toString());
        cmd.add("--max-new-tokens");
        // Tool call mode: cap at 256 — a tool-call JSON object is always short.
        // Answer mode: use the caller's configured max (default 1024).
        int maxTokens = toolCallMode ? Math.min(opts.maxTokens(), 256) : opts.maxTokens();
        cmd.add(String.valueOf(Math.max(1, maxTokens)));
        // Tool call mode: use low-temperature sampling (not greedy) to avoid degenerate
        // repetition loops on 1–2B instruct models with structured JSON output.
        // Greedy decode causes "1 1 1 1..." loops; T=0.2, top-k=10 gives consistent JSON.
        // Answer mode: use the caller's configured temperature (default 0.7).
        if (toolCallMode) {
            cmd.add("--temperature");
            cmd.add("0.2");
            cmd.add("--top-k");
            cmd.add("10");
            cmd.add("--repetition-penalty");
            cmd.add("1.1");
        } else if (opts.temperature() < 1e-6) {
            cmd.add("--greedy");
        } else {
            cmd.add("--temperature");
            cmd.add(String.valueOf(opts.temperature()));
            if (opts.temperature() > 0.0) {
                cmd.add("--repetition-penalty");
                cmd.add("1.1");
            }
        }
        cmd.add("--stats");
        return cmd;
    }

    private String resolveLibPath() {
        // If an explicit lib dir was supplied, prepend it
        String existing = System.getenv("LD_LIBRARY_PATH");
        if (existing == null) existing = "";

        // Default: sibling ../lib relative to the binary
        File binFile = new File(sdxBinPath);
        File defaultLib = new File(binFile.getParentFile(), "../lib").getAbsoluteFile();

        List<String> parts = new ArrayList<>();
        if (sdxLibDir != null && !sdxLibDir.isBlank()) {
            parts.add(sdxLibDir);
        }
        if (defaultLib.exists()) {
            parts.add(defaultLib.getPath());
        }
        if (!existing.isBlank()) {
            parts.add(existing);
        }
        return String.join(File.pathSeparator, parts);
    }

    /**
     * Strip EOS / turn-end tokens from generated text.
     * The models emit {@code <|im_end|>} (ChatML), {@code </s>} (LLaMA-style), etc.
     * Strip them so callers see only the assistant's answer text.
     */
    static String stripEosTokens(String text) {
        if (text == null) return "";
        // Remove known EOS markers
        return text
                .replace("<|im_end|>", "")
                .replace("<|endoftext|>", "")
                .replace("</s>", "")
                .replace("<|eot_id|>", "")
                .trim();
    }
}
