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

package ai.kompile.embedding.openai;

import ai.kompile.core.embeddings.EmbeddingModel; // Your core interface
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
// Spring AI's EmbeddingModel and related classes
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service("openAiEmbeddingModelImpl")
@ConditionalOnProperty(name = "spring.ai.openai.api-key")
public class OpenAiEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiEmbeddingModelImpl.class);
    private final org.springframework.ai.embedding.EmbeddingModel springAiEmbeddingModel; // Spring AI's interface

    @Autowired
    public OpenAiEmbeddingModelImpl(org.springframework.ai.embedding.EmbeddingModel springAiEmbeddingModel) {
        this.springAiEmbeddingModel = springAiEmbeddingModel;
        logger.info("OpenAiEmbeddingModelImpl initialized with Spring AI EmbeddingModel: {}",
                springAiEmbeddingModel.getClass().getName());
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Received null or empty text for embedding, returning empty list.");
            return Collections.emptyList();
        }
        logger.debug("Embedding single text string using OpenAI...");

        // ASSUMPTION: springAiEmbeddingModel.embed(String) returns float[]
        float[] floatArrayEmbedding = this.springAiEmbeddingModel.embed(text);

        if (floatArrayEmbedding == null) {
            logger.error("OpenAI embedding returned null for text: {}", text.substring(0, Math.min(text.length(), 70)) + "...");
            return Collections.emptyList();
        }

        List<Float> result = new ArrayList<>(floatArrayEmbedding.length);
        for (float f : floatArrayEmbedding) {
            result.add(f);
        }
        return result;
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().allMatch(t -> t == null || t.trim().isEmpty())) {
            logger.warn("Received null, empty, or all-empty list of texts for embedding, returning empty list.");
            return Collections.emptyList();
        }
        logger.debug("Embedding {} text strings using OpenAI...", texts.size());

        // ASSUMPTION: springAiEmbeddingModel.embed(List<String>) returns List<float[]>
        List<float[]> listOfFloatArrayEmbeddings = this.springAiEmbeddingModel.embed(texts);

        if (listOfFloatArrayEmbeddings == null) {
            logger.error("OpenAI embedding returned null for a list of texts.");
            return Collections.emptyList();
        }

        return listOfFloatArrayEmbeddings.stream()
                .map(floatArray -> {
                    if (floatArray == null) {
                        logger.warn("A null embedding was returned for one of the texts in the batch.");
                        return Collections.<Float>emptyList();
                    }
                    List<Float> floatList = new ArrayList<>(floatArray.length);
                    for (float f : floatArray) {
                        floatList.add(f);
                    }
                    return floatList;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<List<Float>> embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("Received null or empty list of documents for embedding, returning empty list.");
            return Collections.emptyList();
        }
        logger.debug("Embedding {} documents using OpenAI...", documents.size());

        List<String> contents = documents.stream()
                .map(Document::getText)
                .filter(content -> content != null && !content.trim().isEmpty())
                .collect(Collectors.toList());

        if (contents.isEmpty()) {
            logger.warn("All documents had null or empty content. Nothing to embed.");
            return Collections.emptyList();
        }

        // This now calls the corrected embed(List<String>) which expects List<float[]> from Spring AI
        return embed(contents);
    }

    @Override
    public int dimensions() {
        try {
            // If springAiEmbeddingModel.dimensions() exists and is reliable for M8
            int dims = this.springAiEmbeddingModel.dimensions();
            if (dims > 0) {
                return dims;
            }
            logger.warn("Spring AI EmbeddingModel returned non-positive dimensions ({}). " +
                    "Attempting fallback by embedding a test string.", dims);
            // Fallback: embed a test string and get its length
            float[] sampleEmbedding = this.springAiEmbeddingModel.embed("test");
            return (sampleEmbedding != null) ? sampleEmbedding.length : -1; // Return -1 if even sample fails

        } catch (Exception e) {
            logger.warn("Could not determine embedding dimensions from springAiEmbeddingModel.dimensions() method or sample call. Error: {}. " +
                    "Returning a common default (1536 for many OpenAI models) or -1 if error.", e.getMessage());
            // You might want to return a configured default if known, e.g., 1536 for text-embedding-ada-002
            return 1536; // Or -1 to indicate failure to determine
        }
    }
}