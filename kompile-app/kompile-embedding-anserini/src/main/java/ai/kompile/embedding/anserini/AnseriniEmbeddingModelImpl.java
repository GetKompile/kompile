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
import io.anserini.encoder.samediff.SameDiffEncoder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Anserini-based implementation of the EmbeddingModel interface.
 * Uses the SameDiff encoders from the Anserini fork to generate embeddings.
 * 
 * This implementation provides sane defaults and will work out of the box without configuration.
 */
@Service("anseriniEmbeddingModelImpl")
@ConditionalOnProperty(value = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
public class AnseriniEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(AnseriniEmbeddingModelImpl.class);

    // Default model identifier - lightweight and widely compatible
    private static final String DEFAULT_MODEL_IDENTIFIER = "bge-small-en-v1.5";

    private final SameDiffEncoder<float[]> encoder;
    private final String modelIdentifier;
    private final int embeddingDimensions;
    private final AnseriniEncoderFactory.EncoderType encoderType;

    /**
     * No-argument constructor for Spring AOT compatibility.
     * Uses default model identifier.
     */
    public AnseriniEmbeddingModelImpl() {
        this(DEFAULT_MODEL_IDENTIFIER);
    }

    /**
     * Constructor with model identifier parameter injection.
     */
    @Autowired
    public AnseriniEmbeddingModelImpl(
            @Value("${kompile.embedding.anserini.model-identifier:" + DEFAULT_MODEL_IDENTIFIER + "}") String modelIdentifier) {
        
        this.modelIdentifier = modelIdentifier != null && !modelIdentifier.trim().isEmpty() 
            ? modelIdentifier : DEFAULT_MODEL_IDENTIFIER;
        this.encoderType = AnseriniEncoderFactory.getEncoderTypeFromModelId(this.modelIdentifier);
        
        logger.info("Initializing AnseriniEmbeddingModel with model: {} (type: {})", 
                   this.modelIdentifier, encoderType);
        
        try {
            // Use auto-managed model downloading - no manual configuration required
            this.encoder = AnseriniEncoderFactory.createEncoder(encoderType, this.modelIdentifier);
            
            // Determine embedding dimensions by encoding a test string
            float[] testEmbedding = this.encoder.encode("test");
            this.embeddingDimensions = (testEmbedding != null) ? testEmbedding.length : -1;
            
            if (this.embeddingDimensions <= 0) {
                throw new IOException("Could not determine embedding dimensions for model: " + this.modelIdentifier);
            }
            
            logger.info("AnseriniEmbeddingModel initialized successfully. Model: {}, Type: {}, Dimensions: {}", 
                       this.modelIdentifier, encoderType, embeddingDimensions);
                       
        } catch (IOException e) {
            logger.error("Failed to initialize AnseriniEmbeddingModel with model: {}", this.modelIdentifier, e);
            throw new RuntimeException("Could not initialize Anserini embedding model: " + e.getMessage(), e);
        }
    }

    /**
     * Advanced constructor for explicit configuration.
     */
    public AnseriniEmbeddingModelImpl(String modelIdentifier,
                                      String modelPath,
                                      String vocabPath,
                                      List<String> inputTensorNames,
                                      String outputTensorName,
                                      boolean doLowerCase,
                                      int maxSequenceLength,
                                      boolean addSpecialTokens,
                                      boolean normalizeOutput) throws IOException {
        
        this.modelIdentifier = modelIdentifier;
        this.encoderType = AnseriniEncoderFactory.getEncoderTypeFromModelId(modelIdentifier);
        
        logger.info("Initializing AnseriniEmbeddingModel with explicit configuration. Model: {} (type: {})", 
                   modelIdentifier, encoderType);
        
        this.encoder = AnseriniEncoderFactory.createEncoder(
            encoderType,
            modelIdentifier,
            modelPath,
            vocabPath,
            inputTensorNames,
            outputTensorName,
            doLowerCase,
            maxSequenceLength,
            addSpecialTokens,
            normalizeOutput
        );
        
        // Determine embedding dimensions by encoding a test string
        float[] testEmbedding = this.encoder.encode("test");
        this.embeddingDimensions = (testEmbedding != null) ? testEmbedding.length : -1;
        
        if (this.embeddingDimensions <= 0) {
            throw new IOException("Could not determine embedding dimensions for model: " + modelIdentifier);
        }
        
        logger.info("AnseriniEmbeddingModel initialized successfully. Model: {}, Type: {}, Dimensions: {}", 
                   modelIdentifier, encoderType, embeddingDimensions);
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Received null or empty text for embedding, returning empty list.");
            return Collections.emptyList();
        }
        
        logger.debug("Embedding single text string using Anserini {} encoder...", encoderType);
        
        try {
            float[] embedding = encoder.encode(text);
            
            if (embedding == null) {
                logger.error("Anserini encoder returned null for text: {}", 
                           text.substring(0, Math.min(text.length(), 70)) + "...");
                return Collections.emptyList();
            }
            
            List<Float> result = new ArrayList<>(embedding.length);
            for (float f : embedding) {
                result.add(f);
            }
            return result;
            
        } catch (Exception e) {
            logger.error("Error during Anserini embedding for text: {}", 
                        text.substring(0, Math.min(text.length(), 70)) + "...", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().allMatch(t -> t == null || t.trim().isEmpty())) {
            logger.warn("Received null, empty, or all-empty list of texts for embedding, returning empty list.");
            return Collections.emptyList();
        }
        
        logger.debug("Embedding {} text strings using Anserini {} encoder...", texts.size(), encoderType);
        
        // Try to use bulk encoding if available
        try {
            var bulkResults = encoder.bulkEncode(texts);
            
            List<List<Float>> results = new ArrayList<>();
            for (String text : texts) {
                if (text == null || text.trim().isEmpty()) {
                    results.add(Collections.emptyList());
                    continue;
                }
                
                float[] embedding = bulkResults.get(text);
                if (embedding == null) {
                    logger.warn("Anserini encoder returned null for one text in bulk encoding.");
                    results.add(Collections.emptyList());
                    continue;
                }
                
                List<Float> floatList = new ArrayList<>(embedding.length);
                for (float f : embedding) {
                    floatList.add(f);
                }
                results.add(floatList);
            }
            
            return results;
            
        } catch (Exception e) {
            logger.warn("Bulk encoding failed, falling back to individual encoding: {}", e.getMessage());
            
            // Fallback to individual encoding
            List<List<Float>> results = new ArrayList<>();
            
            for (String text : texts) {
                if (text == null || text.trim().isEmpty()) {
                    logger.warn("Skipping null or empty text in batch embedding.");
                    results.add(Collections.emptyList());
                    continue;
                }
                
                try {
                    float[] embedding = encoder.encode(text);
                    
                    if (embedding == null) {
                        logger.warn("Anserini encoder returned null for one text in batch.");
                        results.add(Collections.emptyList());
                        continue;
                    }
                    
                    List<Float> floatList = new ArrayList<>(embedding.length);
                    for (float f : embedding) {
                        floatList.add(f);
                    }
                    results.add(floatList);
                    
                } catch (Exception ex) {
                    logger.error("Error during Anserini embedding for one text in batch", ex);
                    results.add(Collections.emptyList());
                }
            }
            
            return results;
        }
    }

    @Override
    public List<List<Float>> embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("Received null or empty list of documents for embedding, returning empty list.");
            return Collections.emptyList();
        }
        
        logger.debug("Embedding {} documents using Anserini {} encoder...", documents.size(), encoderType);
        
        List<String> contents = documents.stream()
                .map(Document::getText)
                .filter(content -> content != null && !content.trim().isEmpty())
                .collect(Collectors.toList());
        
        if (contents.isEmpty()) {
            logger.warn("All documents had null or empty content. Nothing to embed.");
            return Collections.emptyList();
        }
        
        return embed(contents);
    }

    @Override
    public int dimensions() {
        return embeddingDimensions;
    }

    /**
     * Gets the model identifier used by this embedding model.
     */
    public String getModelIdentifier() {
        return modelIdentifier;
    }

    /**
     * Gets the encoder type being used.
     */
    public AnseriniEncoderFactory.EncoderType getEncoderType() {
        return encoderType;
    }

    /**
     * Checks if the encoder is properly initialized and ready for use.
     */
    public boolean isReady() {
        return encoder != null && embeddingDimensions > 0;
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up AnseriniEmbeddingModel for model: {} (type: {})", modelIdentifier, encoderType);
        if (encoder != null) {
            try {
                encoder.close();
            } catch (IOException e) {
                logger.warn("Error closing Anserini encoder", e);
            }
        }
    }
}