/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.embeddings;

import java.util.List;
import java.util.Map;

/**
 * Interface for a component that generates vector embeddings for given texts.
 */
public interface EmbeddingModel {

    /**
     * Generates a single vector embedding for a given text.
     *
     * @param text The text to embed.
     * @return A list of floats representing the vector embedding.
     */
    List<Float> embed(String text);

    /**
     * Generates vector embeddings for a list of texts.
     *
     * @param texts The list of texts to embed.
     * @return A list of vector embeddings, where each embedding is a list of floats.
     */
    List<List<Float>> embed(List<String> texts);

    /**
     * Generates embeddings for a list of Spring AI Document objects.
     * This is useful if you want to embed documents directly.
     * The implementation would extract the content from the documents.
     *
     * @param documents The list of Spring AI Document objects.
     * @return A list of vector embeddings.
     */
    List<List<Float>> embedDocuments(List<org.springframework.ai.document.Document> documents);


    /**
     * Gets the dimensionality of the embeddings produced by this model.
     * @return The dimension of the vectors.
     */
    int dimensions();
}