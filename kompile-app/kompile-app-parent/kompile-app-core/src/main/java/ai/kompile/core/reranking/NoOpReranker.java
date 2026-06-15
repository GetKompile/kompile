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

import ai.kompile.core.embeddings.ScoredDocument;

import java.util.List;

/**
 * No-op reranker that returns documents unchanged.
 * Used as a fallback when no reranking is configured.
 */
public class NoOpReranker implements Reranker {

    private static final NoOpReranker INSTANCE = new NoOpReranker();

    private NoOpReranker() {
    }

    /**
     * Get the singleton instance.
     */
    public static NoOpReranker getInstance() {
        return INSTANCE;
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        // Return documents unchanged
        return documents;
    }

    @Override
    public String tag() {
        return "NoOp";
    }

    @Override
    public RerankerType getType() {
        return RerankerType.NONE;
    }
}
