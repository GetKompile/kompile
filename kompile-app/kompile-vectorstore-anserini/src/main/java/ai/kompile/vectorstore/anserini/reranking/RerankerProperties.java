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

package ai.kompile.vectorstore.anserini.reranking;

import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for reranking.
 * <p>
 * Example configuration in application.properties:
 * <pre>
 * # Enable reranking
 * kompile.reranker.enabled=true
 * kompile.reranker.type=rm3
 *
 * # RM3 parameters
 * kompile.reranker.fb-docs=10
 * kompile.reranker.fb-terms=10
 * kompile.reranker.original-query-weight=0.5
 *
 * # BM25-PRF parameters
 * kompile.reranker.k1=0.9
 * kompile.reranker.b=0.4
 * kompile.reranker.new-term-weight=0.2
 *
 * # Rocchio parameters
 * kompile.reranker.alpha=1.0
 * kompile.reranker.beta=0.75
 * kompile.reranker.gamma=0.15
 * kompile.reranker.use-negative=false
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "kompile.reranker")
public class RerankerProperties {

    /**
     * Whether reranking is enabled.
     */
    private boolean enabled = false;

    /**
     * Type of reranker to use (rm3, bm25prf, rocchio, etc.)
     */
    private String type = "rm3";

    /**
     * Number of feedback documents to use.
     */
    private int fbDocs = 10;

    /**
     * Number of feedback terms to use.
     */
    private int fbTerms = 10;

    /**
     * Weight for original query in RM3 interpolation (0.0-1.0).
     */
    private float originalQueryWeight = 0.5f;

    /**
     * Whether to filter non-alphanumeric expansion terms.
     */
    private boolean filterTerms = true;

    /**
     * Whether to log expanded queries (for debugging).
     */
    private boolean outputQuery = false;

    /**
     * BM25 k1 parameter.
     */
    private float k1 = 0.9f;

    /**
     * BM25 b parameter.
     */
    private float b = 0.4f;

    /**
     * Weight boost for newly added expansion terms.
     */
    private float newTermWeight = 0.2f;

    /**
     * Rocchio alpha parameter (original query weight).
     */
    private float alpha = 1.0f;

    /**
     * Rocchio beta parameter (positive feedback weight).
     */
    private float beta = 0.75f;

    /**
     * Rocchio gamma parameter (negative feedback weight).
     */
    private float gamma = 0.15f;

    /**
     * Whether to use negative feedback in Rocchio.
     */
    private boolean useNegative = false;

    /**
     * Convert properties to RerankerConfig.
     */
    public RerankerConfig toRerankerConfig() {
        return new RerankerConfig()
                .setEnabled(enabled)
                .setType(RerankerType.fromId(type))
                .setFbDocs(fbDocs)
                .setFbTerms(fbTerms)
                .setOriginalQueryWeight(originalQueryWeight)
                .setFilterTerms(filterTerms)
                .setOutputQuery(outputQuery)
                .setK1(k1)
                .setB(b)
                .setNewTermWeight(newTermWeight)
                .setAlpha(alpha)
                .setBeta(beta)
                .setGamma(gamma)
                .setUseNegative(useNegative);
    }

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getFbDocs() {
        return fbDocs;
    }

    public void setFbDocs(int fbDocs) {
        this.fbDocs = fbDocs;
    }

    public int getFbTerms() {
        return fbTerms;
    }

    public void setFbTerms(int fbTerms) {
        this.fbTerms = fbTerms;
    }

    public float getOriginalQueryWeight() {
        return originalQueryWeight;
    }

    public void setOriginalQueryWeight(float originalQueryWeight) {
        this.originalQueryWeight = originalQueryWeight;
    }

    public boolean isFilterTerms() {
        return filterTerms;
    }

    public void setFilterTerms(boolean filterTerms) {
        this.filterTerms = filterTerms;
    }

    public boolean isOutputQuery() {
        return outputQuery;
    }

    public void setOutputQuery(boolean outputQuery) {
        this.outputQuery = outputQuery;
    }

    public float getK1() {
        return k1;
    }

    public void setK1(float k1) {
        this.k1 = k1;
    }

    public float getB() {
        return b;
    }

    public void setB(float b) {
        this.b = b;
    }

    public float getNewTermWeight() {
        return newTermWeight;
    }

    public void setNewTermWeight(float newTermWeight) {
        this.newTermWeight = newTermWeight;
    }

    public float getAlpha() {
        return alpha;
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    public float getBeta() {
        return beta;
    }

    public void setBeta(float beta) {
        this.beta = beta;
    }

    public float getGamma() {
        return gamma;
    }

    public void setGamma(float gamma) {
        this.gamma = gamma;
    }

    public boolean isUseNegative() {
        return useNegative;
    }

    public void setUseNegative(boolean useNegative) {
        this.useNegative = useNegative;
    }
}
