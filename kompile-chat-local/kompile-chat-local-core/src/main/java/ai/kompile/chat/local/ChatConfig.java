package ai.kompile.chat.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Application configuration for the local chat module.
 *
 * <p>Values are loaded from a {@code .properties} file and then overridden by environment
 * variables.  The environment-variable names follow the {@code KOMPILE_CHAT_*} convention.</p>
 *
 * <h2>Properties / env vars</h2>
 * <pre>
 * Property key              Env var                          Default
 * kgraph.path               KOMPILE_CHAT_KGRAPH_PATH         (none)
 * model.path                KOMPILE_CHAT_MODEL_PATH          (none)
 * tokenizer.path            KOMPILE_CHAT_TOKENIZER_PATH      (none)
 * sdx.libPath               KOMPILE_CHAT_SDX_LIB             (none)
 * sdx.binPath               KOMPILE_CHAT_SDX_BIN             (none)
 * sdx.mode                  KOMPILE_CHAT_SDX_MODE            auto
 * remote.baseUrl            KOMPILE_CHAT_REMOTE_URL          (none)
 * remote.model              KOMPILE_CHAT_REMOTE_MODEL        gpt-4o-mini
 * remote.apiKey             KOMPILE_CHAT_REMOTE_API_KEY      (none)
 * remote.timeoutSeconds     KOMPILE_CHAT_REMOTE_TIMEOUT      60
 * maxToolRounds             KOMPILE_CHAT_MAX_TOOL_ROUNDS     4
 * temperature               KOMPILE_CHAT_TEMPERATURE         0.7
 * </pre>
 *
 * <p>sdx.mode values:</p>
 * <ul>
 *   <li>{@code auto} (default) — in-process JNA when {@code libsdx_llm.so} passes a load
 *       probe ({@link ai.kompile.chat.local.sdx.SdxLlmAbi#IS_AVAILABLE} == true and
 *       the library reports a valid ABI version without crashing), otherwise subprocess.</li>
 *   <li>{@code inprocess} — always use JNA in-process (fails if lib not loadable).</li>
 *   <li>{@code subprocess} — always use the {@code sdx-llm} binary subprocess.</li>
 * </ul>
 */
public final class ChatConfig {

    private Path kgraphPath;
    private String modelPath;
    private String tokenizerPath;
    private String sdxLibPath;
    private String sdxBinPath;
    private String sdxMode = "auto";
    private String remoteBaseUrl;
    private String remoteModel = "gpt-4o-mini";
    private String remoteApiKey;
    private int remoteTimeoutSeconds = 60;
    private int maxToolRounds = 4;
    private double temperature = 0.7;

    private ChatConfig() {}

    // ── Factories ─────────────────────────────────────────────────────────────

    /**
     * Load configuration from a properties file, then override with environment variables.
     *
     * @param propsFile path to the {@code .properties} file
     * @return populated config
     * @throws IOException if the file cannot be read
     */
    public static ChatConfig fromFile(Path propsFile) throws IOException {
        ChatConfig cfg = new ChatConfig();
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(propsFile)) {
            props.load(is);
        }
        // Load from properties
        String v;
        if ((v = props.getProperty("kgraph.path")) != null && !v.isBlank())
            cfg.kgraphPath = Path.of(v.trim());
        if ((v = props.getProperty("model.path")) != null && !v.isBlank())
            cfg.modelPath = v.trim();
        if ((v = props.getProperty("tokenizer.path")) != null && !v.isBlank())
            cfg.tokenizerPath = v.trim();
        if ((v = props.getProperty("sdx.libPath")) != null && !v.isBlank())
            cfg.sdxLibPath = v.trim();
        if ((v = props.getProperty("sdx.binPath")) != null && !v.isBlank())
            cfg.sdxBinPath = v.trim();
        if ((v = props.getProperty("sdx.mode")) != null && !v.isBlank())
            cfg.sdxMode = v.trim();
        if ((v = props.getProperty("remote.baseUrl")) != null && !v.isBlank())
            cfg.remoteBaseUrl = v.trim();
        if ((v = props.getProperty("remote.model")) != null && !v.isBlank())
            cfg.remoteModel = v.trim();
        if ((v = props.getProperty("remote.apiKey")) != null && !v.isBlank())
            cfg.remoteApiKey = v.trim();
        if ((v = props.getProperty("remote.timeoutSeconds")) != null && !v.isBlank())
            cfg.remoteTimeoutSeconds = Integer.parseInt(v.trim());
        if ((v = props.getProperty("maxToolRounds")) != null && !v.isBlank())
            cfg.maxToolRounds = Integer.parseInt(v.trim());
        if ((v = props.getProperty("temperature")) != null && !v.isBlank())
            cfg.temperature = Double.parseDouble(v.trim());

        cfg.applyEnv();
        return cfg;
    }

    /**
     * Create a config seeded entirely from environment variables (no file).
     *
     * @return config with env-var values applied (fields without env vars keep defaults)
     */
    public static ChatConfig defaults() {
        ChatConfig cfg = new ChatConfig();
        cfg.applyEnv();
        return cfg;
    }

    // ── Env override ──────────────────────────────────────────────────────────

    private void applyEnv() {
        String v;
        if ((v = env("KOMPILE_CHAT_KGRAPH_PATH")) != null)
            kgraphPath = Path.of(v);
        if ((v = env("KOMPILE_CHAT_MODEL_PATH")) != null)
            modelPath = v;
        if ((v = env("KOMPILE_CHAT_TOKENIZER_PATH")) != null)
            tokenizerPath = v;
        if ((v = env("KOMPILE_CHAT_SDX_LIB")) != null)
            sdxLibPath = v;
        if ((v = env("KOMPILE_CHAT_SDX_BIN")) != null)
            sdxBinPath = v;
        if ((v = env("KOMPILE_CHAT_SDX_MODE")) != null)
            sdxMode = v;
        if ((v = env("KOMPILE_CHAT_REMOTE_URL")) != null)
            remoteBaseUrl = v;
        if ((v = env("KOMPILE_CHAT_REMOTE_MODEL")) != null)
            remoteModel = v;
        if ((v = env("KOMPILE_CHAT_REMOTE_API_KEY")) != null)
            remoteApiKey = v;
        if ((v = env("KOMPILE_CHAT_REMOTE_TIMEOUT")) != null)
            remoteTimeoutSeconds = Integer.parseInt(v);
        if ((v = env("KOMPILE_CHAT_MAX_TOOL_ROUNDS")) != null)
            maxToolRounds = Integer.parseInt(v);
        if ((v = env("KOMPILE_CHAT_TEMPERATURE")) != null)
            temperature = Double.parseDouble(v);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v.trim() : null;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Path to the {@code .kgraph} file, if configured. */
    public Optional<Path> kgraphPath() {
        return Optional.ofNullable(kgraphPath);
    }

    /** Path to the local SDX model file, if configured. */
    public Optional<String> modelPath() {
        return Optional.ofNullable(modelPath);
    }

    /**
     * Path to the tokenizer file or directory for the local SDX model.
     * If absent, the SDX library attempts auto-detection from the model directory.
     */
    public Optional<String> tokenizerPath() {
        return Optional.ofNullable(tokenizerPath);
    }

    /**
     * Absolute path to {@code libsdx_llm.so} (or platform equivalent), if configured.
     * When present, passed to {@link ai.kompile.chat.local.sdx.SdxLlmAbi#configureLibPath(String)}
     * before any JNA load attempt.
     */
    public Optional<String> sdxLibPath() {
        return Optional.ofNullable(sdxLibPath);
    }

    /**
     * Absolute path to the {@code sdx-llm} binary, if configured.
     * Used by subprocess mode to fork the CLI per generate call.
     */
    public Optional<String> sdxBinPath() {
        return Optional.ofNullable(sdxBinPath);
    }

    /**
     * SDX inference mode: {@code auto} (default), {@code inprocess}, or {@code subprocess}.
     *
     * <ul>
     *   <li>{@code auto} — probe the native lib; use in-process JNA if it loads cleanly
     *       (requires the export-allowlist fix in libsdx_llm.so), else fall back to subprocess.</li>
     *   <li>{@code inprocess} — always JNA in-process (requires fixed lib).</li>
     *   <li>{@code subprocess} — always fork the {@code sdx-llm} binary.</li>
     * </ul>
     */
    public String sdxMode() {
        return sdxMode;
    }

    /** Base URL for the remote OpenAI-compatible endpoint, if configured. */
    public Optional<String> remoteBaseUrl() {
        return Optional.ofNullable(remoteBaseUrl);
    }

    /** Remote model identifier (default: {@code gpt-4o-mini}). */
    public String remoteModel() {
        return remoteModel;
    }

    /** API key for the remote endpoint, if configured. */
    public Optional<String> remoteApiKey() {
        return Optional.ofNullable(remoteApiKey);
    }

    /** HTTP request timeout in seconds for the remote endpoint (default: 60). */
    public int remoteTimeoutSeconds() {
        return remoteTimeoutSeconds;
    }

    /** Maximum tool rounds per conversation turn (default: 4). */
    public int maxToolRounds() {
        return maxToolRounds;
    }

    /** Sampling temperature (default: 0.7). */
    public double temperature() {
        return temperature;
    }
}
