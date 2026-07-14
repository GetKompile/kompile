package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generation options for local or remote LLM inference.
 *
 * <p>Instances are immutable and created through the builder. The {@link #defaults()} factory
 * returns a ready-to-use instance with sensible defaults.</p>
 *
 * <p>Use {@link #toOptionsJson()} to produce the {@code options_json} payload expected by the
 * SDX LLM ABI's {@code sdxLlmGenerate} call.</p>
 */
public final class GenOptions {

    private final double temperature;
    private final int maxTokens;
    private final double topP;
    private final int topK;
    private final long seed;

    private GenOptions(Builder b) {
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.topP = b.topP;
        this.topK = b.topK;
        this.seed = b.seed;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Sampling temperature (default 0.7). */
    public double temperature() { return temperature; }

    /** Maximum new tokens to generate (default 1024). */
    public int maxTokens() { return maxTokens; }

    /** Top-p nucleus sampling probability (default 0.9). */
    public double topP() { return topP; }

    /** Top-k sampling cutoff (default 40). */
    public int topK() { return topK; }

    /**
     * Random seed for reproducible generation, or {@code -1} for a random seed (default -1).
     * When {@code -1} the seed field is omitted from {@link #toOptionsJson()}.
     */
    public long seed() { return seed; }

    // ── JSON serialisation ────────────────────────────────────────────────────

    /**
     * Produce the {@code options_json} string consumed by {@code sdxLlmGenerate}.
     *
     * <p>Format:
     * <pre>
     * {"maxNewTokens":N,"sampling":{"temperature":T,"topK":K,"topP":P[,"seed":S]}}
     * </pre>
     * The {@code seed} field is omitted when {@link #seed()} == -1.</p>
     *
     * @return JSON string
     */
    public String toOptionsJson() {
        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("temperature", temperature);
        sampling.put("topK", (long) topK);
        sampling.put("topP", topP);
        if (seed != -1L) {
            sampling.put("seed", seed);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("maxNewTokens", (long) maxTokens);
        root.put("sampling", sampling);

        return MiniJson.write(root);
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    /**
     * Return a {@link GenOptions} instance with all defaults applied:
     * temperature=0.7, maxTokens=1024, topP=0.9, topK=40, seed=-1.
     *
     * @return default options
     */
    public static GenOptions defaults() {
        return new Builder().build();
    }

    /**
     * Return a new {@link Builder} for customising options.
     *
     * @return fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Fluent builder for {@link GenOptions}. */
    public static final class Builder {

        private double temperature = 0.7;
        private int maxTokens = 1024;
        private double topP = 0.9;
        private int topK = 40;
        private long seed = -1L;

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public GenOptions build() {
            return new GenOptions(this);
        }
    }
}
