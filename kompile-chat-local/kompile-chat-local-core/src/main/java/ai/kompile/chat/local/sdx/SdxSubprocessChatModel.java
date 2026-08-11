package ai.kompile.chat.local.sdx;

import ai.kompile.chat.local.ChatException;
import ai.kompile.chat.local.ChatModel;
import ai.kompile.chat.local.ChatRequest;
import ai.kompile.chat.local.ChatResponse;
import ai.kompile.chat.local.GenOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Each call to {@link #generate} writes a structured request to a temp file, invokes:
 * <pre>
 *   sdx-llm chat \
 *     --model &lt;modelPath&gt; \
 *     --tokenizer &lt;tokenizerPath&gt; \
 *     --request-file &lt;tmpFile&gt; \
 *     --max-new-tokens &lt;n&gt; \
 *     [--greedy | --temperature &lt;t&gt;]
 * </pre>
 * then consumes the canonical structured result emitted by SameDiff/SDX.</p>
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
   *   <li>{@code .gguf q4_k_m} — supported through the same imported tokenizer metadata
   *       and structured chat path as other GGUF variants.</li>
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
        this.template = SdxChatModel.ChatTemplate.MODEL_OWNED;
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
    public ChatResponse generate(ChatRequest request, GenOptions opts) throws ChatException {
        if (!isAvailable()) {
            throw new ChatException("SdxSubprocessChatModel not available: binary="
                    + sdxBinPath + ", model=" + modelPath);
        }

        // Write the provider-neutral request to a temp file.
        Path requestFile;
        try {
            requestFile = Files.createTempFile("sdx_chat_request_", ".json");
            Files.writeString(requestFile, request.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ChatException("Failed to write chat request temp file: " + e.getMessage());
        }

        try {
            List<String> cmd = buildCommand(requestFile, opts);
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

            // Read stdout (one canonical structured chat result)
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

            return ChatResponse.fromStructuredJson(stdout.toString().trim());

        } finally {
            try {
                Files.deleteIfExists(requestFile);
            } catch (IOException ignored) {}
        }
    }

    /** No-op: subprocess model has no persistent resources. */
    @Override
    public void close() {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> buildCommand(Path requestFile, GenOptions opts) {
        List<String> cmd = new ArrayList<>();
        cmd.add(sdxBinPath);
        cmd.add("chat");
        cmd.add("--model");
        cmd.add(modelPath);
        if (tokenizerPath != null) {
            cmd.add("--tokenizer");
            cmd.add(tokenizerPath);
        }
        cmd.add("--request-file");
        cmd.add(requestFile.toAbsolutePath().toString());
        cmd.add("--max-new-tokens");
        cmd.add(String.valueOf(Math.max(1, opts.maxTokens())));
        if (opts.temperature() < 1e-6) {
            cmd.add("--greedy");
        } else {
            cmd.add("--temperature");
            cmd.add(String.valueOf(opts.temperature()));
        }
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

}
