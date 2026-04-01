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

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.reranking.NoOpReranker;
import ai.kompile.core.reranking.Reranker;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.reranking.RerankerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Anserini-based implementation of RerankerService.
 * <p>
 * This service provides a comprehensive suite of reranking algorithms:
 * <p>
 * <b>Query Expansion (Algorithmic):</b>
 * <ul>
 *   <li>RM3 - Relevance Model 3 query expansion with pseudo-relevance feedback</li>
 *   <li>BM25-PRF - BM25-weighted pseudo-relevance feedback</li>
 *   <li>Rocchio - Vector space model query expansion</li>
 *   <li>Axiom - Axiomatic semantic relevance feedback</li>
 * </ul>
 * <p>
 * <b>Score Manipulation:</b>
 * <ul>
 *   <li>RRF - Reciprocal Rank Fusion for hybrid search (BM25 + vector)</li>
 *   <li>Normalize - Min-max score normalization to [0, 1]</li>
 *   <li>Score Ties Adjuster - Deterministic tie-breaking for reproducibility</li>
 * </ul>
 * <p>
 * <b>Diversity:</b>
 * <ul>
 *   <li>MMR - Maximal Marginal Relevance for reducing redundancy</li>
 * </ul>
 * <p>
 * <b>Neural (Model-based):</b>
 * <ul>
 *   <li>Cross-Encoder - Neural cross-encoder reranking</li>
 * </ul>
 * <p>
 * Note: Some reranking algorithms require access to the Lucene index
 * and may not work with pre-computed document results. For full
 * functionality, use the integrated search methods in
 * {@link ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl}.
 */
@Service
@ConditionalOnClass(name = "ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl")
@ConditionalOnProperty(name = "kompile.reranker.enabled", havingValue = "true", matchIfMissing = true)
public class AnseriniRerankerService implements RerankerService {

    private static final Logger log = LoggerFactory.getLogger(AnseriniRerankerService.class);

    private final RerankerConfig defaultConfig;

    public AnseriniRerankerService() {
        this.defaultConfig = RerankerConfig.rm3();
        log.info("AnseriniRerankerService initialized with default config: {}", defaultConfig);
    }

    public AnseriniRerankerService(RerankerConfig defaultConfig) {
        this.defaultConfig = defaultConfig != null ? defaultConfig : RerankerConfig.rm3();
        log.info("AnseriniRerankerService initialized with custom config: {}", this.defaultConfig);
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerConfig config) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        if (config == null || !config.isEnabled() || config.getType() == RerankerType.NONE) {
            log.debug("Reranking disabled or type is NONE, returning original documents");
            return documents;
        }

        Reranker reranker = createReranker(config);
        log.debug("Reranking {} documents with {} for query: '{}'",
                documents.size(), reranker.tag(), truncateQuery(query));

        return reranker.rerank(documents, query);
    }

    @Override
    public RerankerConfig getDefaultConfig() {
        return defaultConfig;
    }

    @Override
    public List<RerankerType> getSupportedTypes() {
        return Arrays.asList(
                RerankerType.NONE,
                RerankerType.RM3,
                RerankerType.BM25_PRF,
                RerankerType.ROCCHIO,
                RerankerType.AXIOM,
                RerankerType.SCORE_TIES_ADJUSTER,
                RerankerType.CROSS_ENCODER,
                RerankerType.RRF,
                RerankerType.NORMALIZE,
                RerankerType.MMR
        );
    }

    @Override
    public Reranker createReranker(RerankerConfig config) {
        if (config == null || !config.isEnabled() || config.getType() == RerankerType.NONE) {
            return NoOpReranker.getInstance();
        }

        switch (config.getType()) {
            case RM3:
                return new Rm3RerankerAdapter(config);
            case BM25_PRF:
                return new Bm25PrfRerankerAdapter(config);
            case ROCCHIO:
                return new RocchioRerankerAdapter(config);
            case AXIOM:
                return new AxiomRerankerAdapter(config);
            case CROSS_ENCODER:
                return new CrossEncoderRerankerAdapter(config);
            case SCORE_TIES_ADJUSTER:
                return new ScoreTiesAdjusterRerankerAdapter();
            case RRF:
                return new RrfRerankerAdapter(config);
            case NORMALIZE:
                return new NormalizeRerankerAdapter(config);
            case MMR:
                return new MmrRerankerAdapter(config);
            default:
                log.warn("Unsupported reranker type: {}, using NoOp", config.getType());
                return NoOpReranker.getInstance();
        }
    }

    private String truncateQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.length() > 50 ? query.substring(0, 50) + "..." : query;
    }
}
