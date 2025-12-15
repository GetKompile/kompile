package ai.kompile.embedding.anserini;

import io.anserini.encoder.samediff.ArcticEmbedSameDiffEncoder;
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.CosDprDistilSameDiffEncoder;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import ai.kompile.modelmanager.ModelConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;

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
        COS_DPR_DISTIL,
        SPLADE_PP_ED,
        SPLADE_PP_SD
    }

    /**
     * Creates an encoder based on the model identifier using automatic model management.
     * This is the recommended approach that automatically handles model and vocabulary downloads.
     *
     * @param modelIdentifier The model identifier
     * @return The appropriate encoder
     */
    public static SameDiffEncoder<float[]> createEncoder(String modelIdentifier) throws IOException {
        if (!ModelConstants.isEncoderModelAvailable(modelIdentifier)) {
            throw new IOException("Model not available: " + modelIdentifier +
                    ". Available models: " + ModelConstants.getAvailableEncoderModelIds());
        }

        EncoderType encoderType = getEncoderTypeFromModelId(modelIdentifier);
        logger.info("Creating {} encoder for model: {} (using automatic model management)", encoderType, modelIdentifier);

        return createEncoder(encoderType, modelIdentifier);
    }

    /**
     * Creates an encoder with custom tokenizer settings.
     *
     * @param modelIdentifier The model identifier
     * @param doLowerCaseAndStripAccents Whether to lowercase and strip accents
     * @param maxSequenceLength Maximum sequence length
     * @param addSpecialTokens Whether to add special tokens
     * @return The appropriate encoder
     */
    public static SameDiffEncoder<float[]> createEncoder(String modelIdentifier,
                                                         boolean doLowerCaseAndStripAccents,
                                                         int maxSequenceLength,
                                                         boolean addSpecialTokens) throws IOException {
        if (!ModelConstants.isEncoderModelAvailable(modelIdentifier)) {
            throw new IOException("Model not available: " + modelIdentifier +
                    ". Available models: " + ModelConstants.getAvailableEncoderModelIds());
        }

        EncoderType encoderType = getEncoderTypeFromModelId(modelIdentifier);
        logger.info("Creating {} encoder for model: {} with custom settings", encoderType, modelIdentifier);

        return createEncoder(encoderType, modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
    }

    /**
     * Maps model identifier to encoder type.
     */
    public static EncoderType getEncoderTypeFromModelId(String modelIdentifier) {
        if (modelIdentifier == null) {
            return EncoderType.GENERIC_DENSE;
        }
        String lower = modelIdentifier.toLowerCase();
        if (lower.contains("bge")) {
            return EncoderType.BGE;
        } else if (lower.contains("arctic") || lower.contains("embed")) {
            return EncoderType.ARCTIC_EMBED;
        } else if (lower.contains("cos-dpr") || lower.contains("distil")) {
            return EncoderType.COS_DPR_DISTIL;
        } else if (lower.contains("splade-pp-ed")) {
            return EncoderType.SPLADE_PP_ED;
        } else if (lower.contains("splade-pp-sd")) {
            return EncoderType.SPLADE_PP_SD;
        }
        return EncoderType.GENERIC_DENSE;
    }

    /**
     * Creates a SameDiff encoder using the new simplified constructors with default settings.
     */
    public static SameDiffEncoder<float[]> createEncoder(
            EncoderType encoderType,
            String modelIdentifier) throws IOException {

        logger.info("Creating {} encoder for model: {} (using automatic model bundle management)", encoderType, modelIdentifier);

        // Use the new simplified constructors that handle model management automatically
        switch (encoderType) {
            case BGE:
                // BGE typically uses normalization and may have instruction prefix
                String instruction = getBgeInstructionForModel(modelIdentifier);
                return new BgeSameDiffEncoder(modelIdentifier, instruction, true);

            case ARCTIC_EMBED:
                return new ArcticEmbedSameDiffEncoder(modelIdentifier);

            case COS_DPR_DISTIL:
                return new CosDprDistilSameDiffEncoder(modelIdentifier);

            case SPLADE_PP_ED:
            case SPLADE_PP_SD:
            case GENERIC_DENSE:
            default:
                return new GenericDenseSameDiffEncoder(modelIdentifier);
        }
    }

    /**
     * Creates a SameDiff encoder using the new constructors with custom tokenizer settings.
     */
    public static SameDiffEncoder<float[]> createEncoder(
            EncoderType encoderType,
            String modelIdentifier,
            boolean doLowerCaseAndStripAccents,
            int maxSequenceLength,
            boolean addSpecialTokens) throws IOException {

        logger.info("Creating {} encoder for model: {} with custom tokenizer settings", encoderType, modelIdentifier);

        // Use the new constructors with custom settings
        switch (encoderType) {
            case BGE:
                String instruction = getBgeInstructionForModel(modelIdentifier);
                return new BgeSameDiffEncoder(modelIdentifier, instruction, true, 
                        doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);

            case ARCTIC_EMBED:
                return new ArcticEmbedSameDiffEncoder(modelIdentifier,
                        doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);

            case COS_DPR_DISTIL:
                return new CosDprDistilSameDiffEncoder(modelIdentifier,
                        doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);

            case SPLADE_PP_ED:
            case SPLADE_PP_SD:
            case GENERIC_DENSE:
            default:
                return new GenericDenseSameDiffEncoder(modelIdentifier,
                        doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens, true);
        }
    }

    /**
     * Get the appropriate instruction prefix for BGE models.
     * Different BGE models may have different instruction requirements.
     */
    private static String getBgeInstructionForModel(String modelIdentifier) {
        String lower = modelIdentifier.toLowerCase();
        
        // Common BGE instruction patterns
        if (lower.contains("query") || lower.contains("retrieval")) {
            return "Represent this sentence for searching relevant passages:";
        } else if (lower.contains("reranker")) {
            return null; // Rerankers typically don't use instructions
        } else if (lower.contains("base") || lower.contains("small") || lower.contains("large")) {
            return "Represent this sentence for searching relevant passages:";
        }
        
        // Default instruction for most BGE models
        return "Represent this sentence for searching relevant passages:";
    }

    /**
     * Creates an encoder with BGE-specific instruction override.
     */
    public static SameDiffEncoder<float[]> createBgeEncoder(String modelIdentifier,
                                                            String customInstruction,
                                                            boolean normalizeEmbeddings) throws IOException {
        if (!ModelConstants.isEncoderModelAvailable(modelIdentifier)) {
            throw new IOException("Model not available: " + modelIdentifier);
        }

        logger.info("Creating BGE encoder for model: {} with custom instruction: '{}'", 
                modelIdentifier, customInstruction);

        return new BgeSameDiffEncoder(modelIdentifier, customInstruction, normalizeEmbeddings);
    }

    /**
     * Legacy method for backward compatibility - creates encoder with explicit paths.
     * 
     * @deprecated Use createEncoder(String modelIdentifier) instead for automatic model management
     */
    @Deprecated
    public static SameDiffEncoder<float[]> createEncoderLegacy(
            EncoderType encoderType,
            String modelIdentifier,
            String modelPath,
            String vocabPath) throws IOException {

        logger.warn("Using legacy encoder creation method. Consider using createEncoder(modelIdentifier) for automatic model management.");

        // Use the deprecated legacy constructors
        switch (encoderType) {
            case BGE:
                return new BgeSameDiffEncoder(modelIdentifier, modelPath, vocabPath,
                        getBgeInstructionForModel(modelIdentifier), true, true, 512, true);

            case ARCTIC_EMBED:
                return new ArcticEmbedSameDiffEncoder(modelIdentifier, modelPath, vocabPath, true, 512, true);

            case COS_DPR_DISTIL:
                return new CosDprDistilSameDiffEncoder(modelIdentifier, modelPath, vocabPath, true, 512, true);

            case SPLADE_PP_ED:
            case SPLADE_PP_SD:
            case GENERIC_DENSE:
            default:
                return new GenericDenseSameDiffEncoder(modelIdentifier, modelPath, vocabPath,
                        null, null, true, 512, true, true);
        }
    }

    /**
     * Check if a model uses the new automatic model management
     */
    public static boolean usesAutoModelManagement(String modelIdentifier) {
        return ModelConstants.isEncoderModelAvailable(modelIdentifier);
    }

    /**
     * Get the model type (dense/sparse)
     */
    public static String getModelType(String modelIdentifier) {
        return ModelConstants.getModelType(modelIdentifier);
    }

    /**
     * Get the embedding dimension for a model
     */
    public static Integer getEmbeddingDimension(String modelIdentifier) {
        return ModelConstants.getEmbeddingDimension(modelIdentifier);
    }

    /**
     * Utility methods for model metadata
     */
    public static boolean isModelAvailable(String modelIdentifier) {
        return ModelConstants.isEncoderModelAvailable(modelIdentifier);
    }

    public static Set<String> getAvailableModelIds() {
        return ModelConstants.getAvailableEncoderModelIds();
    }

    public static String getModelInfo(String modelIdentifier) {
        if (!isModelAvailable(modelIdentifier)) {
            return "Model not available: " + modelIdentifier;
        }
        String type = getModelType(modelIdentifier);
        Integer dim = getEmbeddingDimension(modelIdentifier);
        EncoderType enc = getEncoderTypeFromModelId(modelIdentifier);
        return String.format("Model: %s, Type: %s, Encoder: %s, Dimension: %d",
                modelIdentifier, type, enc, dim != null ? dim : -1);
    }
}
