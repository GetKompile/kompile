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

package ai.kompile.core.rag.retrieval;

import ai.kompile.core.embeddings.ScoredDocument;

import java.util.List;

/**
 * Result of a document retrieval operation containing retrieved documents
 * and associated metrics.
 *
 * @param documents List of retrieved documents with scores, sorted by relevance descending
 * @param metrics Metrics about the retrieval operation
 */
public record RetrievalResult(
    List<ScoredDocument> documents,
    RetrievalMetrics metrics
) {

    /**
     * Creates an empty retrieval result.
     */
    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), RetrievalMetrics.empty());
    }

    /**
     * Creates a retrieval result with documents and default metrics.
     */
    public static RetrievalResult of(List<ScoredDocument> documents) {
        return new RetrievalResult(documents, RetrievalMetrics.empty());
    }

    /**
     * Returns true if no documents were retrieved.
     */
    public boolean isEmpty() {
        return documents == null || documents.isEmpty();
    }

    /**
     * Returns the number of retrieved documents.
     */
    public int size() {
        return documents != null ? documents.size() : 0;
    }

    /**
     * Gets the top document if available.
     */
    public ScoredDocument topDocument() {
        if (isEmpty()) {
            return null;
        }
        return documents.get(0);
    }

    /**
     * Gets documents up to the specified limit.
     */
    public List<ScoredDocument> topK(int k) {
        if (isEmpty() || k <= 0) {
            return List.of();
        }
        return documents.subList(0, Math.min(k, documents.size()));
    }

    /**
     * Concatenates document texts with separator.
     */
    public String getContextString(String separator) {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(documents.get(i).getText());
        }
        return sb.toString();
    }

    /**
     * Concatenates document texts with default separator.
     */
    public String getContextString() {
        return getContextString("\n\n---\n\n");
    }
}
