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

/**
 * Metrics collected during document retrieval operations.
 * Useful for debugging, optimization, and observability.
 *
 * @param semanticHits Number of documents retrieved from semantic (vector) search
 * @param keywordHits Number of documents retrieved from keyword (BM25) search
 * @param duplicatesRemoved Number of duplicate documents removed during deduplication
 * @param embeddingTimeNanos Time spent generating query embedding in nanoseconds
 * @param semanticSearchTimeNanos Time spent on semantic search in nanoseconds
 * @param keywordSearchTimeNanos Time spent on keyword search in nanoseconds
 * @param totalTimeNanos Total retrieval time in nanoseconds
 */
public record RetrievalMetrics(
    int semanticHits,
    int keywordHits,
    int duplicatesRemoved,
    long embeddingTimeNanos,
    long semanticSearchTimeNanos,
    long keywordSearchTimeNanos,
    long totalTimeNanos
) {

    /**
     * Creates metrics for an empty/failed retrieval.
     */
    public static RetrievalMetrics empty() {
        return new RetrievalMetrics(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Total number of unique documents after deduplication.
     */
    public int totalUniqueHits() {
        return semanticHits + keywordHits - duplicatesRemoved;
    }

    /**
     * Returns embedding time in milliseconds.
     */
    public double embeddingTimeMs() {
        return embeddingTimeNanos / 1_000_000.0;
    }

    /**
     * Returns semantic search time in milliseconds.
     */
    public double semanticSearchTimeMs() {
        return semanticSearchTimeNanos / 1_000_000.0;
    }

    /**
     * Returns keyword search time in milliseconds.
     */
    public double keywordSearchTimeMs() {
        return keywordSearchTimeNanos / 1_000_000.0;
    }

    /**
     * Returns total time in milliseconds.
     */
    public double totalTimeMs() {
        return totalTimeNanos / 1_000_000.0;
    }

    /**
     * Builder for constructing RetrievalMetrics incrementally.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int semanticHits = 0;
        private int keywordHits = 0;
        private int duplicatesRemoved = 0;
        private long embeddingTimeNanos = 0;
        private long semanticSearchTimeNanos = 0;
        private long keywordSearchTimeNanos = 0;
        private long totalTimeNanos = 0;

        public Builder semanticHits(int hits) {
            this.semanticHits = hits;
            return this;
        }

        public Builder keywordHits(int hits) {
            this.keywordHits = hits;
            return this;
        }

        public Builder duplicatesRemoved(int count) {
            this.duplicatesRemoved = count;
            return this;
        }

        public Builder embeddingTimeNanos(long nanos) {
            this.embeddingTimeNanos = nanos;
            return this;
        }

        public Builder semanticSearchTimeNanos(long nanos) {
            this.semanticSearchTimeNanos = nanos;
            return this;
        }

        public Builder keywordSearchTimeNanos(long nanos) {
            this.keywordSearchTimeNanos = nanos;
            return this;
        }

        public Builder totalTimeNanos(long nanos) {
            this.totalTimeNanos = nanos;
            return this;
        }

        public RetrievalMetrics build() {
            return new RetrievalMetrics(
                semanticHits,
                keywordHits,
                duplicatesRemoved,
                embeddingTimeNanos,
                semanticSearchTimeNanos,
                keywordSearchTimeNanos,
                totalTimeNanos
            );
        }
    }
}
