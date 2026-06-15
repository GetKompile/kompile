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
import ai.kompile.core.reranking.Reranker;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerContext;
import ai.kompile.core.reranking.RerankerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RRF (Reciprocal Rank Fusion) reranker adapter.
 * <p>
 * Reciprocal Rank Fusion is a simple but effective method for combining
 * multiple ranked lists. It converts rankings to scores using the formula:
 * <pre>
 *   score(d) = 1 / (k + rank(d))
 * </pre>
 * where k is a constant (typically 60) that smooths the distribution.
 * <p>
 * RRF is particularly useful for hybrid search, combining keyword (BM25)
 * and semantic (vector) search results. It's robust to score differences
 * between retrieval methods since it only considers rank positions.
 * <p>
 * Reference: Cormack, G. V., Clarke, C. L., & Buettcher, S. (2009).
 * Reciprocal rank fusion outperforms condorcet and individual rank learning methods.
 */
public class RrfRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(RrfRerankerAdapter.class);

    private final int k;

    public RrfRerankerAdapter(RerankerConfig config) {
        this.k = config.getRrfK();
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        // Documents are already sorted by their original scores (rank)
        // Apply RRF scoring based on position
        List<ScoredDocument> reranked = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            int rank = i + 1;  // 1-based ranking
            double rrfScore = 1.0 / (k + rank);
            ScoredDocument doc = documents.get(i);
            reranked.add(new ScoredDocument(doc.document(), rrfScore));
        }

        // Already sorted by RRF score (decreasing with rank)
        log.debug("RRF reranking complete: {} documents with k={}", reranked.size(), k);
        return reranked;
    }

    @Override
    public String tag() {
        return String.format("RRF(k=%d)", k);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.RRF;
    }
}
