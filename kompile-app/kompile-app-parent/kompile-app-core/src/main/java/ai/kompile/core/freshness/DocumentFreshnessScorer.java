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

package ai.kompile.core.freshness;

/**
 * Functional interface for scoring document freshness.
 * Returns a value between 0.0 (very stale) and 1.0 (freshly indexed).
 */
@FunctionalInterface
public interface DocumentFreshnessScorer {

    /**
     * Calculate a freshness score for the given document ID.
     *
     * @param documentId the document's ID or source identifier
     * @return freshness score between 0.0 and 1.0
     */
    double score(String documentId);

    /**
     * Get the weight this freshness score should have in combined scoring.
     * Default 0.1 means 10% freshness, 90% raw similarity score.
     */
    default double getWeight() {
        return 0.1;
    }
}
