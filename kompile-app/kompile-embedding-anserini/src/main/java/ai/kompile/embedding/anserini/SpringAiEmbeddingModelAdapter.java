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

package ai.kompile.embedding.anserini;

import ai.kompile.core.embeddings.EmbeddingModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Adapter that bridges our custom EmbeddingModel to Spring AI's EmbeddingModel interface.
 * This allows our Anserini embedding model to work with Spring AI components.
 */
@Component
@ConditionalOnProperty(value = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
public class SpringAiEmbeddingModelAdapter implements org.springframework.ai.embedding.EmbeddingModel {

    private final EmbeddingModel kompileEmbeddingModel;

    @Autowired
    public SpringAiEmbeddingModelAdapter(@Qualifier("anseriniEmbeddingModelImpl") EmbeddingModel kompileEmbeddingModel) {
        this.kompileEmbeddingModel = kompileEmbeddingModel;
    }

    @Override
    public float[] embed(Document document) {
        List<Float> embedding = kompileEmbeddingModel.embed(document.getText());
        return convertListFloatToFloatArray(embedding);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<List<Float>> embeddings = kompileEmbeddingModel.embed(instructions);
        
        List<Embedding> springAiEmbeddings = embeddings.stream()
                .map(embedding -> new Embedding(convertListFloatToFloatArray(embedding), 0))
                .collect(Collectors.toList());
        
        return new EmbeddingResponse(springAiEmbeddings);
    }

    @Override
    public float[] embed(String text) {
        List<Float> embedding = kompileEmbeddingModel.embed(text);
        return convertListFloatToFloatArray(embedding);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<List<Float>> embeddings = kompileEmbeddingModel.embed(texts);
        
        return embeddings.stream()
                .map(this::convertListFloatToFloatArray)
                .collect(Collectors.toList());
    }

    @Override
    public int dimensions() {
        return kompileEmbeddingModel.dimensions();
    }

    /**
     * Helper method to convert List<Float> to float[].
     */
    private float[] convertListFloatToFloatArray(List<Float> floatList) {
        if (floatList == null || floatList.isEmpty()) {
            return new float[0];
        }
        
        float[] result = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            result[i] = floatList.get(i);
        }
        return result;
    }
}