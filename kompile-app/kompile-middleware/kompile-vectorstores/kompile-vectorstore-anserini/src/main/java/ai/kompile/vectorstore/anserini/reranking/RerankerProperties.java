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
import lombok.Getter;
import lombok.Setter;
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
@Getter
@Setter
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

}
