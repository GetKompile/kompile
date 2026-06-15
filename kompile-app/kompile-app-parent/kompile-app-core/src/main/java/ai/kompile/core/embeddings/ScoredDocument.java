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

package ai.kompile.core.embeddings;

import org.springframework.ai.document.Document;

import java.util.Map;
import java.util.Objects;

/**
 * A document with an associated similarity/relevance score.
 * Used for search results where ranking matters.
 *
 * @param document The Spring AI document
 * @param score The similarity or relevance score (higher is typically better)
 */
public record ScoredDocument(
    Document document,
    double score
) {

    public ScoredDocument {
        Objects.requireNonNull(document, "document cannot be null");
    }

    /**
     * Creates a ScoredDocument with default score of 0.0.
     */
    public static ScoredDocument of(Document document) {
        return new ScoredDocument(document, 0.0);
    }

    /**
     * Creates a ScoredDocument from raw components.
     */
    public static ScoredDocument of(String id, String content, Map<String, Object> metadata, double score) {
        return new ScoredDocument(new Document(id, content, metadata), score);
    }

    /**
     * Convenience method to get document ID.
     */
    public String getId() {
        return document.getId();
    }

    /**
     * Convenience method to get document text content.
     */
    public String getText() {
        return document.getText();
    }

    /**
     * Convenience method to get document metadata.
     */
    public Map<String, Object> getMetadata() {
        return document.getMetadata();
    }
}
