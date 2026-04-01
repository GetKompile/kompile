/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.reranking;

/**
 * Configuration for reranking operations.
 * <p>
 * This class provides sensible defaults for common reranking algorithms
 * while allowing full customization.
 */
public class RerankerConfig {

    // Reranker type
    private RerankerType type = RerankerType.NONE;
    private boolean enabled = false;

    // Common feedback parameters
    private int fbDocs = 10;       // Number of feedback documents
    private int fbTerms = 10;      // Number of feedback terms

    // RM3 parameters
    private float originalQueryWeight = 0.5f;  // Interpolation weight for original query (0.0-1.0)
    private boolean filterTerms = true;         // Filter non-alphanumeric expansion terms
    private boolean outputQuery = false;        // Log expanded queries

    // BM25-PRF parameters
    private float k1 = 0.9f;                   // BM25 k1 parameter
    private float b = 0.4f;                    // BM25 b parameter
    private float newTermWeight = 0.2f;        // Weight boost for newly added terms

    // Rocchio parameters
    private float alpha = 1.0f;                // Weight for original query
    private float beta = 0.75f;                // Weight for positive feedback
    private float gamma = 0.15f;               // Weight for negative feedback
    private boolean useNegative = false;        // Use non-relevant documents

    // Axiom parameters
    private int r = 20;                         // Axiom R parameter
    private int n = 30;                         // Axiom N parameter
    private float axiomBeta = 0.4f;             // Axiom beta parameter
    private boolean deterministic = true;       // Reproducible runs
    private long seed = 42L;                    // Random seed if deterministic

    // RRF parameters
    private int rrfK = 60;                      // RRF constant k (typically 60)

    // MMR parameters
    private float lambda = 0.5f;                // MMR lambda: 1.0 = pure relevance, 0.0 = pure diversity

    // Additional options
    private int topK = -1;                      // Limit reranking to top-k docs (-1 = all)

    public RerankerConfig() {
    }

    /**
     * Create a disabled reranker config.
     */
    public static RerankerConfig disabled() {
        return new RerankerConfig();
    }

    /**
     * Create an RM3 reranker config with sensible defaults.
     */
    public static RerankerConfig rm3() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.RM3;
        config.enabled = true;
        config.fbDocs = 10;
        config.fbTerms = 10;
        config.originalQueryWeight = 0.5f;
        return config;
    }

    /**
     * Create a BM25-PRF reranker config with sensible defaults.
     */
    public static RerankerConfig bm25Prf() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.BM25_PRF;
        config.enabled = true;
        config.fbDocs = 10;
        config.fbTerms = 20;
        config.k1 = 0.9f;
        config.b = 0.4f;
        return config;
    }

    /**
     * Create a Rocchio reranker config with sensible defaults.
     */
    public static RerankerConfig rocchio() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.ROCCHIO;
        config.enabled = true;
        config.fbDocs = 10;
        config.fbTerms = 10;
        config.alpha = 1.0f;
        config.beta = 0.75f;
        config.gamma = 0.15f;
        return config;
    }

    /**
     * Create an Axiom reranker config with sensible defaults.
     */
    public static RerankerConfig axiom() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.AXIOM;
        config.enabled = true;
        config.r = 20;
        config.n = 30;
        config.axiomBeta = 0.4f;
        config.deterministic = true;
        config.seed = 42L;
        return config;
    }

    /**
     * Create an RRF (Reciprocal Rank Fusion) reranker config with sensible defaults.
     */
    public static RerankerConfig rrf() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.RRF;
        config.enabled = true;
        config.rrfK = 60;
        return config;
    }

    /**
     * Create a Normalize reranker config.
     */
    public static RerankerConfig normalize() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.NORMALIZE;
        config.enabled = true;
        return config;
    }

    /**
     * Create an MMR (Maximal Marginal Relevance) reranker config with sensible defaults.
     */
    public static RerankerConfig mmr() {
        RerankerConfig config = new RerankerConfig();
        config.type = RerankerType.MMR;
        config.enabled = true;
        config.lambda = 0.5f;  // Balance between relevance and diversity
        return config;
    }

    // Getters and setters

    public RerankerType getType() {
        return type;
    }

    public RerankerConfig setType(RerankerType type) {
        this.type = type;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RerankerConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public int getFbDocs() {
        return fbDocs;
    }

    public RerankerConfig setFbDocs(int fbDocs) {
        this.fbDocs = fbDocs;
        return this;
    }

    public int getFbTerms() {
        return fbTerms;
    }

    public RerankerConfig setFbTerms(int fbTerms) {
        this.fbTerms = fbTerms;
        return this;
    }

    public float getOriginalQueryWeight() {
        return originalQueryWeight;
    }

    public RerankerConfig setOriginalQueryWeight(float originalQueryWeight) {
        this.originalQueryWeight = originalQueryWeight;
        return this;
    }

    public boolean isFilterTerms() {
        return filterTerms;
    }

    public RerankerConfig setFilterTerms(boolean filterTerms) {
        this.filterTerms = filterTerms;
        return this;
    }

    public boolean isOutputQuery() {
        return outputQuery;
    }

    public RerankerConfig setOutputQuery(boolean outputQuery) {
        this.outputQuery = outputQuery;
        return this;
    }

    public float getK1() {
        return k1;
    }

    public RerankerConfig setK1(float k1) {
        this.k1 = k1;
        return this;
    }

    public float getB() {
        return b;
    }

    public RerankerConfig setB(float b) {
        this.b = b;
        return this;
    }

    public float getNewTermWeight() {
        return newTermWeight;
    }

    public RerankerConfig setNewTermWeight(float newTermWeight) {
        this.newTermWeight = newTermWeight;
        return this;
    }

    public float getAlpha() {
        return alpha;
    }

    public RerankerConfig setAlpha(float alpha) {
        this.alpha = alpha;
        return this;
    }

    public float getBeta() {
        return beta;
    }

    public RerankerConfig setBeta(float beta) {
        this.beta = beta;
        return this;
    }

    public float getGamma() {
        return gamma;
    }

    public RerankerConfig setGamma(float gamma) {
        this.gamma = gamma;
        return this;
    }

    public boolean isUseNegative() {
        return useNegative;
    }

    public RerankerConfig setUseNegative(boolean useNegative) {
        this.useNegative = useNegative;
        return this;
    }

    public int getR() {
        return r;
    }

    public RerankerConfig setR(int r) {
        this.r = r;
        return this;
    }

    public int getN() {
        return n;
    }

    public RerankerConfig setN(int n) {
        this.n = n;
        return this;
    }

    public float getAxiomBeta() {
        return axiomBeta;
    }

    public RerankerConfig setAxiomBeta(float axiomBeta) {
        this.axiomBeta = axiomBeta;
        return this;
    }

    public boolean isDeterministic() {
        return deterministic;
    }

    public RerankerConfig setDeterministic(boolean deterministic) {
        this.deterministic = deterministic;
        return this;
    }

    public long getSeed() {
        return seed;
    }

    public RerankerConfig setSeed(long seed) {
        this.seed = seed;
        return this;
    }

    public int getRrfK() {
        return rrfK;
    }

    public RerankerConfig setRrfK(int rrfK) {
        this.rrfK = rrfK;
        return this;
    }

    public float getLambda() {
        return lambda;
    }

    public RerankerConfig setLambda(float lambda) {
        this.lambda = lambda;
        return this;
    }

    public int getTopK() {
        return topK;
    }

    public RerankerConfig setTopK(int topK) {
        this.topK = topK;
        return this;
    }

    @Override
    public String toString() {
        return "RerankerConfig{" +
                "type=" + type +
                ", enabled=" + enabled +
                ", fbDocs=" + fbDocs +
                ", fbTerms=" + fbTerms +
                ", originalQueryWeight=" + originalQueryWeight +
                '}';
    }
}
