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
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Adapter that bridges our custom EmbeddingModel to Spring AI's EmbeddingModel interface.
 * This allows our Anserini embedding model to work with Spring AI components.
 */
@Component
@ConditionalOnProperty(value = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
public class SpringAiEmbeddingModelAdapter implements org.springframework.ai.embedding.EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingModelAdapter.class);
    
    private final EmbeddingModel kompileEmbeddingModel;

    @Autowired
    public SpringAiEmbeddingModelAdapter(@Qualifier("anseriniEmbeddingModelImpl") EmbeddingModel kompileEmbeddingModel) {
        this.kompileEmbeddingModel = kompileEmbeddingModel;
    }

    @Override
    public float[] embed(Document document) {
        // Wrap ALL operations in try-catch to handle native pointer errors
        // that can occur at ANY stage - during embedding generation, validation,
        // or conversion. The native memory can become invalid after InferenceSession
        // closes its OpContexts, causing "Pointer address of argument X is NULL" errors.
        INDArray embedding = null;
        try {
            embedding = kompileEmbeddingModel.embed(document.getText());

            // Handle empty/interrupted embeddings - comprehensive validation
            if (embedding == null || embedding.isEmpty() || embedding.length() == 0) {
                log.debug("Received empty embedding, likely due to interrupt. Returning empty array.");
                return new float[0];
            }

            // Check if array was closed (pointer may be invalid)
            if (embedding.wasClosed()) {
                log.warn("Embedding array was closed before use. Returning empty array.");
                return new float[0];
            }

            // Check if data buffer is null or closed (pointer may be null)
            if (embedding.data() == null || embedding.data().wasClosed()) {
                log.warn("Embedding data buffer is null or closed. Returning empty array.");
                return new float[0];
            }

            return embedding.toFloatVector();
        } catch (NullPointerException e) {
            // This catches JavaCPP "Pointer address of argument X is NULL" errors
            // that can occur during ANY native operation on the INDArray
            log.warn("Native pointer error during embedding operation: {}", e.getMessage());
            return new float[0];
        } catch (IllegalStateException e) {
            // This catches "DataBuffer was already released" errors
            log.warn("DataBuffer state error during embedding operation: {}", e.getMessage());
            return new float[0];
        } catch (Exception e) {
            log.warn("Error during embedding operation, returning empty array", e);
            return new float[0];
        } finally {
            // CRITICAL: Close the INDArray to release native memory
            // Without this, each embedding call leaks memory, causing OOM during batch processing
            if (embedding != null && !embedding.wasClosed()) {
                try {
                    embedding.close();
                } catch (Exception e) {
                    log.trace("Error closing embedding array: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> ret = new ArrayList<>();
        INDArray embeddings = null;

        // Wrap ALL operations in try-catch to handle native pointer errors
        try {
            embeddings = kompileEmbeddingModel.embed(instructions);

            // Handle empty/interrupted embeddings
            if (embeddings == null || embeddings.isEmpty() || embeddings.rows() == 0) {
                log.debug("Received empty embeddings, likely due to interrupt. Returning empty response.");
                return new EmbeddingResponse(ret);
            }

            // Check if array was closed (pointer may be invalid)
            if (embeddings.wasClosed()) {
                log.warn("Embeddings array was closed before use. Returning empty response.");
                return new EmbeddingResponse(ret);
            }

            // Check if data buffer is null or closed (pointer may be null)
            if (embeddings.data() == null || embeddings.data().wasClosed()) {
                log.warn("Embeddings data buffer is null or closed. Returning empty response.");
                return new EmbeddingResponse(ret);
            }

            for(int i = 0; i < embeddings.rows(); i++) {
                INDArray row = null;
                try {
                    row = embeddings.getRow(i);
                    if (row != null && !row.isEmpty() && row.length() > 0 &&
                        !row.wasClosed() && row.data() != null && !row.data().wasClosed()) {
                        ret.add(new Embedding(row.toFloatVector(), i));
                    }
                } catch (Exception e) {
                    log.warn("Error processing embedding row {}, skipping", i, e);
                } finally {
                    // CRITICAL: Close the row view to release native memory reference
                    if (row != null && !row.wasClosed()) {
                        try {
                            row.close();
                        } catch (Exception e) {
                            log.trace("Error closing row view: {}", e.getMessage());
                        }
                    }
                }
            }

            return new EmbeddingResponse(ret);
        } catch (NullPointerException e) {
            log.warn("Native pointer error during embedding request: {}", e.getMessage());
            return new EmbeddingResponse(ret);
        } catch (IllegalStateException e) {
            log.warn("DataBuffer state error during embedding request: {}", e.getMessage());
            return new EmbeddingResponse(ret);
        } catch (Exception e) {
            log.warn("Error during embedding request, returning empty response", e);
            return new EmbeddingResponse(ret);
        } finally {
            // CRITICAL: Close the INDArray to release native memory
            if (embeddings != null && !embeddings.wasClosed()) {
                try {
                    embeddings.close();
                } catch (Exception e) {
                    log.trace("Error closing embeddings array: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public float[] embed(String text) {
        // Wrap ALL operations in try-catch to handle native pointer errors
        INDArray embedding = null;
        try {
            embedding = kompileEmbeddingModel.embed(text);

            // Handle empty/interrupted embeddings - comprehensive validation
            if (embedding == null || embedding.isEmpty() || embedding.length() == 0) {
                log.debug("Received empty embedding for text, likely due to interrupt. Returning empty array.");
                return new float[0];
            }

            // Check if array was closed (pointer may be invalid)
            if (embedding.wasClosed()) {
                log.warn("Embedding array was closed before use. Returning empty array.");
                return new float[0];
            }

            // Check if data buffer is null or closed (pointer may be null)
            if (embedding.data() == null || embedding.data().wasClosed()) {
                log.warn("Embedding data buffer is null or closed. Returning empty array.");
                return new float[0];
            }

            return embedding.toFloatVector();
        } catch (NullPointerException e) {
            log.warn("Native pointer error during text embedding operation: {}", e.getMessage());
            return new float[0];
        } catch (IllegalStateException e) {
            log.warn("DataBuffer state error during text embedding operation: {}", e.getMessage());
            return new float[0];
        } catch (Exception e) {
            log.warn("Error during text embedding operation, returning empty array", e);
            return new float[0];
        } finally {
            // CRITICAL: Close the INDArray to release native memory
            if (embedding != null && !embedding.wasClosed()) {
                try {
                    embedding.close();
                } catch (Exception e) {
                    log.trace("Error closing embedding array: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> ret = new ArrayList<>();
        INDArray embeddings = null;

        // Wrap ALL operations in try-catch to handle native pointer errors
        try {
            embeddings = kompileEmbeddingModel.embed(texts);

            // Handle empty/interrupted embeddings
            if (embeddings == null || embeddings.isEmpty() || embeddings.rows() == 0) {
                log.debug("Received empty embeddings, likely due to interrupt. Returning empty list.");
                return ret;
            }

            // Check if array was closed (pointer may be invalid)
            if (embeddings.wasClosed()) {
                log.warn("Embeddings array was closed before use. Returning empty list.");
                return ret;
            }

            // Check if data buffer is null or closed (pointer may be null)
            if (embeddings.data() == null || embeddings.data().wasClosed()) {
                log.warn("Embeddings data buffer is null or closed. Returning empty list.");
                return ret;
            }

            for(int i = 0; i < embeddings.rows(); i++) {
                INDArray row = null;
                try {
                    row = embeddings.getRow(i);
                    if (row != null && !row.isEmpty() && row.length() > 0 &&
                        !row.wasClosed() && row.data() != null && !row.data().wasClosed()) {
                        ret.add(row.toFloatVector());
                    } else {
                        ret.add(new float[0]);
                    }
                } catch (Exception e) {
                    log.warn("Error processing embedding row {}, adding empty array", i, e);
                    ret.add(new float[0]);
                } finally {
                    // CRITICAL: Close the row view to release native memory reference
                    if (row != null && !row.wasClosed()) {
                        try {
                            row.close();
                        } catch (Exception e) {
                            log.trace("Error closing row view: {}", e.getMessage());
                        }
                    }
                }
            }
            return ret;
        } catch (NullPointerException e) {
            log.warn("Native pointer error during batch embedding operation: {}", e.getMessage());
            return ret;
        } catch (IllegalStateException e) {
            log.warn("DataBuffer state error during batch embedding operation: {}", e.getMessage());
            return ret;
        } catch (Exception e) {
            log.warn("Error during batch embedding operation, returning empty list", e);
            return ret;
        } finally {
            // CRITICAL: Close the INDArray to release native memory
            if (embeddings != null && !embeddings.wasClosed()) {
                try {
                    embeddings.close();
                } catch (Exception e) {
                    log.trace("Error closing embeddings array: {}", e.getMessage());
                }
            }
        }
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
