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

package ai.kompile.embedding.openai;

import ai.kompile.core.embeddings.EmbeddingModel; // Your core interface
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
// Spring AI's EmbeddingModel and related classes
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service("openAiEmbeddingModelImpl")
@ConditionalOnClass(name = "ai.kompile.embedding.openai.OpenAiEmbeddingModelImpl")
@ConditionalOnProperty(name = "spring.ai.openai.api-key")
@ImportRuntimeHints(OpenAiEmbeddingModelImpl.OpenAiEmbeddingsHints.class)
public class OpenAiEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiEmbeddingModelImpl.class);
    private final org.springframework.ai.embedding.EmbeddingModel springAiEmbeddingModel; // Spring AI's interface


    static class OpenAiEmbeddingsHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            var memberCategories = MemberCategory.values();
            for (var c : new Class[] { Embedding.class, EmbeddingRequest.class, EmbeddingResponse.class})
                hints.reflection().registerType(c, memberCategories);

            hints.resources()
                    .registerResource(new ClassPathResource("embedding/embedding-model-dimensions.properties"));
        }

    }

    @Autowired
    public OpenAiEmbeddingModelImpl(org.springframework.ai.embedding.EmbeddingModel springAiEmbeddingModel) {
        this.springAiEmbeddingModel = springAiEmbeddingModel;
        logger.info("OpenAiEmbeddingModelImpl initialized with Spring AI EmbeddingModel: {}",
                springAiEmbeddingModel.getClass().getName());
    }

    @Override
    public INDArray embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Received null or empty text for embedding, returning empty list.");
            return Nd4j.empty(DataType.FLOAT);
        }
        logger.debug("Embedding single text string using OpenAI...");

        // ASSUMPTION: springAiEmbeddingModel.embed(String) returns float[]
        float[] floatArrayEmbedding = this.springAiEmbeddingModel.embed(text);

        if (floatArrayEmbedding == null) {
            logger.error("OpenAI embedding returned null for text: {}", text.substring(0, Math.min(text.length(), 70)) + "...");
            return Nd4j.empty(DataType.FLOAT);
        }

        return Nd4j.create(floatArrayEmbedding );
    }

    @Override
    public INDArray embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().allMatch(t -> t == null || t.trim().isEmpty())) {
            logger.warn("Received null, empty, or all-empty list of texts for embedding, returning empty list.");
            return Nd4j.empty(DataType.FLOAT);
        }
        logger.debug("Embedding {} text strings using OpenAI...", texts.size());

        List<float[]> listOfFloatArrayEmbeddings = this.springAiEmbeddingModel.embed(texts);

        if (listOfFloatArrayEmbeddings == null || listOfFloatArrayEmbeddings.isEmpty()) {
            logger.error("OpenAI embedding returned null or empty for a list of texts.");
            return Nd4j.empty(DataType.FLOAT);
        }

        float[][] arr = new float[listOfFloatArrayEmbeddings.size()][springAiEmbeddingModel.dimensions()];
        for(int i = 0; i < arr.length; i++) {
            arr[i] = listOfFloatArrayEmbeddings.get(i);
        }

        return Nd4j.create(arr);
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("Received null or empty list of documents for embedding, returning empty list.");
            return Nd4j.empty(DataType.FLOAT);
        }
        logger.debug("Embedding {} documents using OpenAI...", documents.size());

        List<String> contents = documents.stream()
                .map(Document::getText)
                .filter(content -> content != null && !content.trim().isEmpty())
                .collect(Collectors.toList());

        if (contents.isEmpty()) {
            logger.warn("All documents had null or empty content. Nothing to embed.");
            return Nd4j.empty(DataType.FLOAT);
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