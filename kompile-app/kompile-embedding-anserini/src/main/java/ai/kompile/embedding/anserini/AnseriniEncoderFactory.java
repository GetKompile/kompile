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

import io.anserini.encoder.samediff.ArcticEmbedSameDiffEncoder;
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.CosDprDistilSameDiffEncoder;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Factory for creating different types of Anserini SameDiff encoders.
 */
public class AnseriniEncoderFactory {

    private static final Logger logger = LoggerFactory.getLogger(AnseriniEncoderFactory.class);

    /**
     * Supported encoder types.
     */
    public enum EncoderType {
        GENERIC_DENSE,
        BGE,
        ARCTIC_EMBED,
        COS_DPR_DISTIL
    }

    /**
     * Creates an encoder based on the model identifier.
     * 
     * @param modelIdentifier The model identifier
     * @return The appropriate encoder type
     */
    public static EncoderType getEncoderTypeFromModelId(String modelIdentifier) {
        if (modelIdentifier == null) {
            return EncoderType.GENERIC_DENSE;
        }
        
        String lowerModelId = modelIdentifier.toLowerCase();
        
        if (lowerModelId.contains("bge")) {
            return EncoderType.BGE;
        } else if (lowerModelId.contains("arctic") || lowerModelId.contains("embed")) {
            return EncoderType.ARCTIC_EMBED;
        } else if (lowerModelId.contains("cos-dpr") || lowerModelId.contains("distil")) {
            return EncoderType.COS_DPR_DISTIL;
        } else {
            return EncoderType.GENERIC_DENSE;
        }
    }

    /**
     * Creates a SameDiff encoder using model management for automatic model downloading.
     * This method resolves the model and vocabulary paths automatically.
     */
    public static SameDiffEncoder<float[]> createEncoder(
            EncoderType encoderType,
            String modelIdentifier) throws IOException {
        
        logger.info("Creating {} encoder for model: {} (using model management)", encoderType, modelIdentifier);
        
        // Use model management to get the model and vocab paths
        KompileModelManager modelManager = new KompileModelManager();
        ModelDescriptor modelDescriptor = ModelConstants.getAnseriniEncoderModelDescriptor(modelIdentifier);
        
        if (modelDescriptor == null) {
            throw new IOException("No model descriptor found for model identifier: " + modelIdentifier + 
                    ". Please ensure the model is defined in ModelConstants.getAnseriniEncoderModelDescriptor()");
        }
        
        // Ensure model is available through model manager
        Path modelPath = modelManager.ensureModelAvailable(modelDescriptor);
        
        if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
            throw new IOException("Model file not found at expected path after download: " + modelPath);
        }
        
        // Find vocabulary file - try common names
        Path vocabPath = modelPath.getParent().resolve("vocab.txt");
        if (!Files.exists(vocabPath)) {
            Path[] vocabCandidates = {
                modelPath.getParent().resolve("tokenizer.json"),
                modelPath.getParent().resolve("vocabulary.txt"),
                modelPath.getParent().resolve("vocab.json")
            };
            
            for (Path candidate : vocabCandidates) {
                if (Files.exists(candidate)) {
                    vocabPath = candidate;
                    break;
                }
            }
            
            if (!Files.exists(vocabPath)) {
                throw new IOException("Vocabulary file not found. Expected vocab.txt, tokenizer.json, vocabulary.txt, or vocab.json in: " + modelPath.getParent());
            }
        }
        
        logger.info("Using model path: {}", modelPath);
        logger.info("Using vocab path: {}", vocabPath);
        
        // Create encoder with resolved paths
        return createEncoderWithPaths(encoderType, modelIdentifier, 
                                    modelPath.toString(), vocabPath.toString());
    }

    /**
     * Creates a SameDiff encoder with explicit paths.
     */
    public static SameDiffEncoder<float[]> createEncoder(
            EncoderType encoderType,
            String modelIdentifier,
            String modelPath,
            String vocabPath,
            List<String> inputTensorNames,
            String outputTensorName,
            boolean doLowerCase,
            int maxSequenceLength,
            boolean addSpecialTokens,
            boolean normalizeOutput) throws IOException {
        
        logger.info("Creating {} encoder for model: {} with explicit paths", encoderType, modelIdentifier);
        
        switch (encoderType) {
            case BGE:
                return createBgeEncoder(modelIdentifier, modelPath, vocabPath, 
                                      doLowerCase, maxSequenceLength, addSpecialTokens, normalizeOutput);
            
            case ARCTIC_EMBED:
                return createArcticEmbedEncoder(modelIdentifier, modelPath, vocabPath, 
                                              doLowerCase, maxSequenceLength, addSpecialTokens);
            
            case COS_DPR_DISTIL:
                return createCosDprDistilEncoder(modelIdentifier, modelPath, vocabPath,
                                               doLowerCase, maxSequenceLength, addSpecialTokens);
            
            case GENERIC_DENSE:
            default:
                return new GenericDenseSameDiffEncoder(
                    modelIdentifier, modelPath, vocabPath, inputTensorNames, outputTensorName,
                    doLowerCase, maxSequenceLength, addSpecialTokens, normalizeOutput);
        }
    }

    /**
     * Helper method to create encoders with resolved paths using default parameters.
     */
    private static SameDiffEncoder<float[]> createEncoderWithPaths(
            EncoderType encoderType,
            String modelIdentifier,
            String modelPath,
            String vocabPath) throws IOException {
        
        switch (encoderType) {
            case BGE:
                return new BgeSameDiffEncoder(modelIdentifier, modelPath, vocabPath, 
                                            null, // instruction
                                            BgeSameDiffEncoder.DEFAULT_NORMALIZE);
            
            case ARCTIC_EMBED:
                return new ArcticEmbedSameDiffEncoder(modelIdentifier, modelPath, vocabPath);
            
            case COS_DPR_DISTIL:
                return new CosDprDistilSameDiffEncoder(modelIdentifier, modelPath, vocabPath);
            
            case GENERIC_DENSE:
            default:
                return new GenericDenseSameDiffEncoder(modelIdentifier, modelPath, vocabPath);
        }
    }

    private static BgeSameDiffEncoder createBgeEncoder(
            String modelIdentifier, String modelPath, String vocabPath,
            boolean doLowerCase, int maxSequenceLength, 
            boolean addSpecialTokens, boolean normalizeOutput) throws IOException {
        
        // BGE encoder has specific constructor parameters
        return new BgeSameDiffEncoder(
            modelIdentifier, modelPath, vocabPath, 
            null, // instruction - can be null for BGE
            normalizeOutput, // BGE supports normalization
            doLowerCase, maxSequenceLength, addSpecialTokens);
    }

    private static ArcticEmbedSameDiffEncoder createArcticEmbedEncoder(
            String modelIdentifier, String modelPath, String vocabPath,
            boolean doLowerCase, int maxSequenceLength, 
            boolean addSpecialTokens) throws IOException {
        
        // Arctic Embed encoder constructor
        return new ArcticEmbedSameDiffEncoder(
            modelIdentifier, modelPath, vocabPath,
            doLowerCase, maxSequenceLength, addSpecialTokens);
    }

    private static CosDprDistilSameDiffEncoder createCosDprDistilEncoder(
            String modelIdentifier, String modelPath, String vocabPath,
            boolean doLowerCase, int maxSequenceLength, 
            boolean addSpecialTokens) throws IOException {
        
        // CosDprDistil encoder constructor
        return new CosDprDistilSameDiffEncoder(
            modelIdentifier, modelPath, vocabPath,
            doLowerCase, maxSequenceLength, addSpecialTokens);
    }
}
