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

package io.anserini.encoder.samediff.factory;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelDescriptor;
import io.anserini.encoder.samediff.*;
import io.anserini.encoder.samediff.sparse.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Factory for creating SameDiff encoders with automatic model downloading and management.
 * This factory integrates with KompileModelManager to automatically download models
 * that were converted using the model-conversion-utility.
 */
public class SameDiffEncoderFactory {
    private static final Logger logger = LoggerFactory.getLogger(SameDiffEncoderFactory.class);
    
    private final KompileModelManager modelManager;

    public SameDiffEncoderFactory(KompileModelManager modelManager) {
        this.modelManager = modelManager;
    }

    public SameDiffEncoderFactory() {
        this(new KompileModelManager());
    }

    /**
     * Create a BGE encoder with automatic model downloading.
     */
    public BgeSameDiffEncoder createBgeEncoder(String modelId) throws IOException {
        return createBgeEncoder(modelId, null, null);
    }

    /**
     * Create a BGE encoder with custom instruction and normalization settings.
     */
    public BgeSameDiffEncoder createBgeEncoder(String modelId, String instruction, Boolean normalizeEmbeddings) throws IOException {
        ModelDescriptor descriptor = ModelConstants.getSameDiffDenseEncoderDescriptor(modelId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown BGE model: " + modelId);
        }

        // Download model and vocab files
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        Path vocabPath = downloadVocabFile(modelId);

        // Extract settings from model descriptor
        String finalInstruction = instruction != null ? instruction : ModelConstants.getInstructionPrefix(modelId);
        boolean finalNormalize = normalizeEmbeddings != null ? normalizeEmbeddings : ModelConstants.shouldNormalizeEmbeddings(modelId);
        int maxSeqLength = ModelConstants.getMaxSequenceLength(modelId);

        logger.info("Creating BGE encoder for model: {} with instruction: '{}', normalize: {}", 
                   modelId, finalInstruction, finalNormalize);

        return new BgeSameDiffEncoder(
            modelId,
            modelPath.toString(),
            vocabPath.toString(),
            finalInstruction,
            finalNormalize,
            true, // doLowercaseAndStripAccents
            maxSeqLength,
            true  // addSpecialTokens
        );
    }

    /**
     * Create an Arctic Embed encoder with automatic model downloading.
     */
    public ArcticEmbedSameDiffEncoder createArcticEmbedEncoder(String modelId) throws IOException {
        ModelDescriptor descriptor = ModelConstants.getSameDiffDenseEncoderDescriptor(modelId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown Arctic Embed model: " + modelId);
        }

        // Download model and vocab files
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        Path vocabPath = downloadVocabFile(modelId);

        int maxSeqLength = ModelConstants.getMaxSequenceLength(modelId);

        logger.info("Creating Arctic Embed encoder for model: {}", modelId);

        return new ArcticEmbedSameDiffEncoder(
            modelId,
            modelPath.toString(),
            vocabPath.toString(),
            true, // doLowercaseAndStripAccents
            maxSeqLength,
            true  // addSpecialTokens
        );
    }

    /**
     * Create a CosDPR Distil encoder with automatic model downloading.
     */
    public CosDprDistilSameDiffEncoder createCosDprDistilEncoder(String modelId) throws IOException {
        ModelDescriptor descriptor = ModelConstants.getSameDiffDenseEncoderDescriptor(modelId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown CosDPR model: " + modelId);
        }

        // Download model and vocab files
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        Path vocabPath = downloadVocabFile(modelId);

        int maxSeqLength = ModelConstants.getMaxSequenceLength(modelId);

        logger.info("Creating CosDPR Distil encoder for model: {}", modelId);

        return new CosDprDistilSameDiffEncoder(
            modelId,
            modelPath.toString(),
            vocabPath.toString(),
            true, // doLowercaseAndStripAccents
            maxSeqLength,
            true  // addSpecialTokens
        );
    }

    /**
     * Create a SPLADE++ Self-Distil encoder with automatic model downloading.
     */
    public SpladePlusPlusSelfDistilSameDiffEncoder createSpladePlusPlusSelfDistilEncoder(String modelId) throws IOException {
        ModelDescriptor descriptor = ModelConstants.getSameDiffSparseEncoderDescriptor(modelId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown SPLADE++ Self-Distil model: " + modelId);
        }

        // Download model and vocab files
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        Path vocabPath = downloadVocabFile(modelId);

        int maxSeqLength = ModelConstants.getMaxSequenceLength(modelId);
        int weightRange = ModelConstants.getWeightRange(modelId);
        int quantRange = ModelConstants.getQuantRange(modelId);

        logger.info("Creating SPLADE++ Self-Distil encoder for model: {}", modelId);

        return new SpladePlusPlusSelfDistilSameDiffEncoder(
            modelId,
            modelPath.toString(),
            vocabPath.toString(),
            true, // doLowercaseAndStripAccents
            maxSeqLength,
            true, // addSpecialTokens
            weightRange,
            quantRange
        );
    }

    /**
     * Create a SPLADE++ Ensemble-Distil encoder with automatic model downloading.
     */
    public SpladePlusPlusEnsembleDistilSameDiffEncoder createSpladePlusPlusEnsembleDistilEncoder(String modelId) throws IOException {
        ModelDescriptor descriptor = ModelConstants.getSameDiffSparseEncoderDescriptor(modelId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown SPLADE++ Ensemble-Distil model: " + modelId);
        }

        // Download model and vocab files
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        Path vocabPath = downloadVocabFile(modelId);

        int maxSeqLength = ModelConstants.getMaxSequenceLength(modelId);
        int weightRange = ModelConstants.getWeightRange(modelId);
        int quantRange = ModelConstants.getQuantRange(modelId);

        logger.info("Creating SPLADE++ Ensemble-Distil encoder for model: {}", modelId);

        return new SpladePlusPlusEnsembleDistilSameDiffEncoder(
            modelId,
            modelPath.toString(),
            vocabPath.toString(),
            true, // doLowercaseAndStripAccents
            maxSeqLength,
            true, // addSpecialTokens
            weightRange,
            quantRange
        );
    }

    /**
     * Create a UniCOIL encoder with automatic model downloading.
     */
    public UniCoilSameDiffEncoder createUniCoilEncoder(String modelId) throws IOException {
        ModelDescriptor descriptor = ModelConstants.getSameDiffSparseEncoderDescriptor(modelId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown UniCOIL model: " + modelId);
        }

        // Download model and vocab files
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        Path vocabPath = downloadVocabFile(modelId);

        int maxSeqLength = ModelConstants.getMaxSequenceLength(modelId);
        int weightRange = ModelConstants.getWeightRange(modelId);
        int quantRange = ModelConstants.getQuantRange(modelId);

        logger.info("Creating UniCOIL encoder for model: {}", modelId);

        return new UniCoilSameDiffEncoder(
            modelId,
            modelPath.toString(),
            vocabPath.toString(),
            true, // doLowercaseAndStripAccents
            maxSeqLength,
            true, // addSpecialTokens
            weightRange,
            quantRange
        );
    }

    /**
     * Generic method to create any supported SameDiff encoder by model ID.
     */
    public SameDiffEncoder<?> createEncoder(String modelId) throws IOException {
        // Try dense encoders first
        if (ModelConstants.getSameDiffDenseEncoderDescriptor(modelId) != null) {
            return createDenseEncoder(modelId);
        }
        
        // Try sparse encoders
        if (ModelConstants.getSameDiffSparseEncoderDescriptor(modelId) != null) {
            return createSparseEncoder(modelId);
        }
        
        throw new IllegalArgumentException("Unknown or unsupported model: " + modelId);
    }

    /**
     * Create a dense encoder based on model ID.
     */
    public SameDiffEncoder<float[]> createDenseEncoder(String modelId) throws IOException {
        switch (modelId) {
            case "bge-base-en-v1.5":
                return createBgeEncoder(modelId);
            case "arctic-embed-l":
                return createArcticEmbedEncoder(modelId);
            case "cosdpr-distil":
                return createCosDprDistilEncoder(modelId);
            default:
                throw new IllegalArgumentException("Unknown dense encoder model: " + modelId);
        }
    }

    /**
     * Create a sparse encoder based on model ID.
     */
    public SameDiffSparseEncoder createSparseEncoder(String modelId) throws IOException {
        switch (modelId) {
            case "splade-pp-sd":
                return createSpladePlusPlusSelfDistilEncoder(modelId);
            case "splade-pp-ed":
                return createSpladePlusPlusEnsembleDistilEncoder(modelId);
            case "unicoil":
                return createUniCoilEncoder(modelId);
            default:
                throw new IllegalArgumentException("Unknown sparse encoder model: " + modelId);
        }
    }

    /**
     * Download vocabulary file for a model if it exists.
     */
    private Path downloadVocabFile(String modelId) throws IOException {
        String vocabUrl = ModelConstants.getVocabUrl(modelId);
        if (vocabUrl == null) {
            throw new IOException("No vocabulary URL found for model: " + modelId);
        }

        // Create a temporary ModelDescriptor for the vocab file
        String vocabFilename = vocabUrl.substring(vocabUrl.lastIndexOf('/') + 1);
        ModelDescriptor vocabDescriptor = new ModelDescriptor(
            modelId + "-vocab",
            ai.kompile.modelmanager.ModelType.NLP_MODEL,
            vocabUrl,
            "vocab/" + vocabFilename,
            "latest",
            null,
            Map.of("description", "Vocabulary file for " + modelId)
        );

        return modelManager.ensureModelAvailable(vocabDescriptor);
    }

    /**
     * List all available model IDs that can be created by this factory.
     */
    public Set<String> getAvailableModelIds() {
        Set<String> modelIds = new HashSet<>();
        modelIds.addAll(ModelConstants.getAllSameDiffDenseEncoders().keySet());
        modelIds.addAll(ModelConstants.getAllSameDiffSparseEncoders().keySet());
        return modelIds;
    }

    /**
     * Check if a model ID is supported by this factory.
     */
    public boolean isModelSupported(String modelId) {
        return ModelConstants.getSameDiffEncoderDescriptor(modelId) != null;
    }

    /**
     * Get model information without creating the encoder.
     */
    public ModelDescriptor getModelDescriptor(String modelId) {
        return ModelConstants.getSameDiffEncoderDescriptor(modelId);
    }

    /**
     * Placeholder ModelConstants class - this would need to be implemented
     * based on your actual model configuration system.
     */
    private static class ModelConstants {
        public static ModelDescriptor getSameDiffDenseEncoderDescriptor(String modelId) {
            // Placeholder implementation
            switch (modelId) {
                case "bge-base-en-v1.5":
                case "arctic-embed-l":
                case "cosdpr-distil":
                    return new ModelDescriptor(
                        modelId,
                        ai.kompile.modelmanager.ModelType.NLP_MODEL,
                        "https://example.com/" + modelId + ".onnx",
                        "models/" + modelId + ".sd",
                        "latest",
                        null,
                        Map.of("description", "Dense encoder model: " + modelId)
                    );
                default:
                    return null;
            }
        }

        public static ModelDescriptor getSameDiffSparseEncoderDescriptor(String modelId) {
            // Placeholder implementation
            switch (modelId) {
                case "splade-pp-sd":
                case "splade-pp-ed":
                case "unicoil":
                    return new ModelDescriptor(
                        modelId,
                        ai.kompile.modelmanager.ModelType.NLP_MODEL,
                        "https://example.com/" + modelId + ".onnx",
                        "models/" + modelId + ".sd",
                        "latest",
                        null,
                        Map.of("description", "Sparse encoder model: " + modelId)
                    );
                default:
                    return null;
            }
        }

        public static ModelDescriptor getSameDiffEncoderDescriptor(String modelId) {
            ModelDescriptor desc = getSameDiffDenseEncoderDescriptor(modelId);
            return desc != null ? desc : getSameDiffSparseEncoderDescriptor(modelId);
        }

        public static String getInstructionPrefix(String modelId) {
            return ""; // Default no instruction
        }

        public static boolean shouldNormalizeEmbeddings(String modelId) {
            return true; // Default normalize
        }

        public static int getMaxSequenceLength(String modelId) {
            return 512; // Default max sequence length
        }

        public static int getWeightRange(String modelId) {
            return 100; // Default weight range for sparse models
        }

        public static int getQuantRange(String modelId) {
            return 256; // Default quantization range
        }

        public static String getVocabUrl(String modelId) {
            return "https://example.com/" + modelId + "-vocab.txt";
        }

        public static Map<String, ModelDescriptor> getAllSameDiffDenseEncoders() {
            Map<String, ModelDescriptor> models = new java.util.HashMap<>();
            models.put("bge-base-en-v1.5", getSameDiffDenseEncoderDescriptor("bge-base-en-v1.5"));
            models.put("arctic-embed-l", getSameDiffDenseEncoderDescriptor("arctic-embed-l"));
            models.put("cosdpr-distil", getSameDiffDenseEncoderDescriptor("cosdpr-distil"));
            return models;
        }

        public static Map<String, ModelDescriptor> getAllSameDiffSparseEncoders() {
            Map<String, ModelDescriptor> models = new java.util.HashMap<>();
            models.put("splade-pp-sd", getSameDiffSparseEncoderDescriptor("splade-pp-sd"));
            models.put("splade-pp-ed", getSameDiffSparseEncoderDescriptor("splade-pp-ed"));
            models.put("unicoil", getSameDiffSparseEncoderDescriptor("unicoil"));
            return models;
        }
    }
}
