package ai.kompile.embedding.anserini;

import io.anserini.encoder.samediff.ArcticEmbedSameDiffEncoder;
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.CosDprDistilSameDiffEncoder;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import io.anserini.encoder.samediff.VlmImageEncoder;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.RegistryBasedModelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Factory for creating different types of Anserini SameDiff encoders.
 *
 * All models are loaded from the registry (~/.kompile/models/registry.json).
 * Use the archive import feature to add models to the registry.
 *
 * <p><b>Retry Behavior:</b> Model loading uses intelligent retry logic that only retries
 * on transient errors (network timeouts, connection refused, service unavailable).
 * Permanent errors (model validation failures, file corruption, parsing errors) fail
 * immediately to avoid wasting resources on unrecoverable situations.</p>
 */
public class AnseriniEncoderFactory {

    private static final Logger logger = LoggerFactory.getLogger(AnseriniEncoderFactory.class);

    // Singleton registry manager for efficient model lookups
    private static volatile RegistryBasedModelManager registryManager;

    private static RegistryBasedModelManager getRegistryManager() {
        if (registryManager == null) {
            synchronized (AnseriniEncoderFactory.class) {
                if (registryManager == null) {
                    registryManager = new RegistryBasedModelManager();
                }
            }
        }
        return registryManager;
    }

    /**
     * Supported encoder types.
     */
    public enum EncoderType {
        GENERIC_DENSE,
        BGE,
        ARCTIC_EMBED,
        COS_DPR_DISTIL,
        SPLADE_PP_ED,
        SPLADE_PP_SD,
        VLM_IMAGE
    }

    // Retry configuration for staging service connections
    private static final int MAX_STAGING_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;  // 1 second
    private static final long MAX_RETRY_DELAY_MS = 5000;      // 5 seconds

    /**
     * Creates an encoder based on the model identifier using the registry.
     * Models must be registered in ~/.kompile/models/registry.json via archive import.
     *
     * <p>If a remote staging service is configured and the model is not found,
     * this method will retry up to {@value #MAX_STAGING_RETRIES} times with
     * exponential backoff to handle transient network issues.</p>
     *
     * <p><b>Intelligent Retry:</b> Only transient errors (network timeouts, connection
     * refused, service unavailable) are retried. Permanent errors (model validation
     * failures, file corruption, parsing errors) fail immediately.</p>
     *
     * @param modelIdentifier The model identifier
     * @return The appropriate encoder
     * @throws IOException if the model is not in the registry after retries
     * @throws RetryableErrorClassifier.ModelLoadingException with retry info on failure
     */
    public static SameDiffEncoder<float[]> createEncoder(String modelIdentifier) throws IOException {
        RegistryBasedModelManager registry = getRegistryManager();

        // Check if staging service is configured - if so, use retry logic for transient errors
        boolean stagingConfigured = registry.getStagingUrl() != null && !registry.getStagingUrl().isBlank();
        int maxAttempts = stagingConfigured ? MAX_STAGING_RETRIES : 1;

        IOException lastException = null;
        boolean lastErrorWasRetriable = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Refresh registry if this is a retry
                if (attempt > 1) {
                    long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (1L << (attempt - 2)), MAX_RETRY_DELAY_MS);
                    logger.info("Retry {}/{}: Waiting {}ms before retrying connection to staging service...",
                            attempt, maxAttempts, delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw RetryableErrorClassifier.wrapWithRetryInfo(
                                "Interrupted while waiting to retry", e, false);
                    }
                    registry.refresh();
                }

                Optional<RegistryBasedModelManager.ModelEntry> registryEntry = registry.getModelEntry(modelIdentifier);

                if (registryEntry.isPresent() && isEncoderType(registryEntry.get().type)) {
                    logger.info("Creating encoder for model: {} from registry (type: {})", modelIdentifier, registryEntry.get().type);
                    return createEncoderFromRegistry(modelIdentifier, registryEntry.get());
                }

                // Model not found - if staging is configured, this might be a transient issue
                if (stagingConfigured && attempt < maxAttempts) {
                    logger.warn("Attempt {}/{}: Model '{}' not found in staging registry. Will retry...",
                            attempt, maxAttempts, modelIdentifier);
                    lastException = new IOException("Model not found in registry: " + modelIdentifier);
                    lastErrorWasRetriable = true;  // Registry lookup failures are retriable
                    continue;
                }

                // Final attempt or no staging - throw appropriate error
                // Model not found after all retries - this is now a non-retriable error
                lastException = createModelNotFoundError(modelIdentifier, registry, stagingConfigured);
                lastErrorWasRetriable = false;

            } catch (IOException e) {
                lastException = e;

                // Check if this error is retriable
                boolean isRetriable = RetryableErrorClassifier.isRetriable(e);
                lastErrorWasRetriable = isRetriable;

                if (!isRetriable) {
                    // Non-retriable error - fail immediately, don't waste time on retries
                    String reason = RetryableErrorClassifier.getClassificationReason(e);
                    logger.error("Non-retriable error loading model '{}': {} ({})",
                            modelIdentifier, e.getMessage(), reason);
                    throw RetryableErrorClassifier.wrapWithRetryInfo(
                            "Model loading failed with permanent error: " + e.getMessage(), e, false);
                }

                if (stagingConfigured && attempt < maxAttempts) {
                    String reason = RetryableErrorClassifier.getClassificationReason(e);
                    logger.warn("Attempt {}/{}: Retriable error loading model '{}': {} ({}). Will retry...",
                            attempt, maxAttempts, modelIdentifier, e.getMessage(), reason);
                    continue;
                }
            } catch (RuntimeException e) {
                // RuntimeExceptions (like ModelValidationException) are typically non-retriable
                boolean isRetriable = RetryableErrorClassifier.isRetriable(e);
                lastErrorWasRetriable = isRetriable;

                if (!isRetriable) {
                    String reason = RetryableErrorClassifier.getClassificationReason(e);
                    logger.error("Non-retriable runtime error loading model '{}': {} ({})",
                            modelIdentifier, e.getMessage(), reason);
                    throw RetryableErrorClassifier.wrapWithRetryInfo(
                            "Model loading failed with permanent error: " + e.getMessage(), e, false);
                }

                // Wrap retriable RuntimeException as IOException for retry
                lastException = new IOException("Retriable error: " + e.getMessage(), e);
                if (stagingConfigured && attempt < maxAttempts) {
                    logger.warn("Attempt {}/{}: Retriable runtime error loading model '{}': {}. Will retry...",
                            attempt, maxAttempts, modelIdentifier, e.getMessage());
                    continue;
                }
            }
        }

        // All attempts exhausted - wrap with retry info
        if (lastException != null) {
            throw RetryableErrorClassifier.wrapWithRetryInfo(
                    lastException.getMessage(), lastException, lastErrorWasRetriable);
        }

        // Should not reach here, but just in case
        throw RetryableErrorClassifier.wrapWithRetryInfo(
                "Model not found: " + modelIdentifier, null, false);
    }

    /**
     * Creates an informative error message based on the configuration state.
     */
    private static IOException createModelNotFoundError(String modelIdentifier,
                                                         RegistryBasedModelManager registry,
                                                         boolean stagingConfigured) {
        StringBuilder message = new StringBuilder();
        message.append("Model not found: ").append(modelIdentifier).append("\n");

        Set<String> availableModels = getAvailableModelIds();

        if (stagingConfigured) {
            message.append("\nStaging service is configured (").append(registry.getStagingUrl()).append(") ");
            message.append("but the model is not available after ").append(MAX_STAGING_RETRIES).append(" attempts.\n");
            message.append("Possible causes:\n");
            message.append("  - The staging service may be offline or unreachable\n");
            message.append("  - The model may not be registered in the staging service\n");
            message.append("  - Network connectivity issues\n");
            message.append("\nTo resolve:\n");
            message.append("  - Check the staging service connection in the UI\n");
            message.append("  - Verify the model is registered in the staging service\n");
            message.append("  - Import a model archive directly if staging is unavailable\n");
        } else {
            message.append("\nNo model source configured.\n");
            message.append("To resolve:\n");
            message.append("  - Configure a staging service connection in the UI\n");
            message.append("  - Or import a model archive containing the model\n");
        }

        if (!availableModels.isEmpty()) {
            message.append("\nAvailable models: ").append(availableModels);
        } else {
            message.append("\nNo models currently available in the registry.");
        }

        return new IOException(message.toString());
    }

    /**
     * Check if a type string represents an encoder (dense or sparse).
     * Accepts: encoder, dense_encoder, sparse_encoder
     */
    private static boolean isEncoderType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase();
        return lower.equals("encoder") ||
               lower.equals("dense_encoder") ||
               lower.equals("sparse_encoder") ||
               lower.equals("vlm_image") ||
               lower.equals("image_encoder") ||
               lower.contains("encoder");
    }

    /**
     * Creates an encoder from a registry entry.
     */
    private static SameDiffEncoder<float[]> createEncoderFromRegistry(
            String modelIdentifier,
            RegistryBasedModelManager.ModelEntry entry) throws IOException {

        // Get encoder type from registry metadata, or fall back to pattern matching
        EncoderType encoderType;
        if (entry.metadata != null && entry.metadata.encoderType != null) {
            encoderType = parseEncoderType(entry.metadata.encoderType);
            logger.debug("Using encoder type from registry: {}", encoderType);
        } else {
            encoderType = getEncoderTypeFromModelId(modelIdentifier);
            logger.debug("Encoder type not in registry, detected from model ID: {}", encoderType);
        }

        // Get model bundle from registry
        KompileModelManager.ModelBundle bundle = getRegistryManager().getEncoderModelBundle(modelIdentifier);
        if (bundle == null) {
            throw new IOException("Failed to get model bundle from registry for: " + modelIdentifier);
        }

        logger.info("Creating {} encoder for model: {} with registry bundle (model={}, vocab={})",
                encoderType, modelIdentifier, bundle.getModelPath(), bundle.getVocabularyPath());

        // Create encoder with bundle from registry
        return createEncoderWithBundle(encoderType, modelIdentifier, bundle);
    }

    /**
     * Creates an encoder with a pre-loaded model bundle.
     */
    private static SameDiffEncoder<float[]> createEncoderWithBundle(
            EncoderType encoderType,
            String modelIdentifier,
            KompileModelManager.ModelBundle bundle) throws IOException {

        String modelPath = bundle.getModelPath().toString();
        String vocabPath = bundle.getVocabularyPath().toString();

        // Get tokenizer config from bundle
        boolean doLowerCase = true;
        int maxSequenceLength = 512;
        boolean addSpecialTokens = true;

        if (bundle.getTokenizerConfig() != null) {
            doLowerCase = bundle.getTokenizerConfig().isDoLowerCase();
            maxSequenceLength = bundle.getTokenizerConfig().getMaxSequenceLength();
            addSpecialTokens = bundle.getTokenizerConfig().isAddSpecialTokens();
        }

        switch (encoderType) {
            case BGE:
                String instruction = getBgeInstructionForModel(modelIdentifier);
                return new BgeSameDiffEncoder(modelIdentifier, modelPath, vocabPath,
                        instruction, true, doLowerCase, maxSequenceLength, addSpecialTokens);

            case ARCTIC_EMBED:
                return new ArcticEmbedSameDiffEncoder(modelIdentifier, modelPath, vocabPath,
                        doLowerCase, maxSequenceLength, addSpecialTokens);

            case COS_DPR_DISTIL:
                return new CosDprDistilSameDiffEncoder(modelIdentifier, modelPath, vocabPath,
                        doLowerCase, maxSequenceLength, addSpecialTokens);

            case SPLADE_PP_ED:
            case SPLADE_PP_SD:
            case GENERIC_DENSE:
            default:
                // Use legacy constructor with explicit paths - tensor names can be null (auto-detected)
                return new GenericDenseSameDiffEncoder(modelIdentifier, modelPath, vocabPath,
                        null, null, doLowerCase, maxSequenceLength, addSpecialTokens, true);
        }
    }

    /**
     * Parses encoder type from string (from registry metadata).
     */
    private static EncoderType parseEncoderType(String encoderTypeStr) {
        if (encoderTypeStr == null || encoderTypeStr.isBlank()) {
            return EncoderType.GENERIC_DENSE;
        }
        try {
            return EncoderType.valueOf(encoderTypeStr.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            // Try mapping common names
            String lower = encoderTypeStr.toLowerCase();
            if (lower.contains("bge")) return EncoderType.BGE;
            if (lower.contains("arctic")) return EncoderType.ARCTIC_EMBED;
            if (lower.contains("cosdpr") || lower.contains("dpr")) return EncoderType.COS_DPR_DISTIL;
            if (lower.contains("splade-pp-ed") || lower.contains("splade_pp_ed")) return EncoderType.SPLADE_PP_ED;
            if (lower.contains("splade-pp-sd") || lower.contains("splade_pp_sd")) return EncoderType.SPLADE_PP_SD;
            if (isVlmImageModel(lower)) return EncoderType.VLM_IMAGE;
            return EncoderType.GENERIC_DENSE;
        }
    }

    /**
     * Creates an encoder with custom tokenizer settings.
     * Model must be in the registry.
     *
     * @param modelIdentifier The model identifier
     * @param doLowerCaseAndStripAccents Whether to lowercase and strip accents
     * @param maxSequenceLength Maximum sequence length
     * @param addSpecialTokens Whether to add special tokens
     * @return The appropriate encoder
     * @throws IOException if model is not in the registry
     */
    public static SameDiffEncoder<float[]> createEncoder(String modelIdentifier,
                                                         boolean doLowerCaseAndStripAccents,
                                                         int maxSequenceLength,
                                                         boolean addSpecialTokens) throws IOException {
        if (!isModelAvailable(modelIdentifier)) {
            throw new IOException("Model not found in registry: " + modelIdentifier +
                    ". Import a model archive to add it. Available models: " + getAvailableModelIds());
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
        // Check VLM image models first (before generic patterns)
        if (isVlmImageModel(lower)) {
            return EncoderType.VLM_IMAGE;
        }
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
     * Check if a model identifier is a VLM image embedding model.
     */
    public static boolean isVlmImageModel(String modelIdLower) {
        if (modelIdLower == null) return false;
        return modelIdLower.contains("smoldocling") ||
               modelIdLower.contains("siglip") ||
               modelIdLower.contains("clip-vit") ||
               modelIdLower.contains("donut");
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
     * Model must be in the registry.
     */
    public static SameDiffEncoder<float[]> createBgeEncoder(String modelIdentifier,
                                                            String customInstruction,
                                                            boolean normalizeEmbeddings) throws IOException {
        if (!isModelAvailable(modelIdentifier)) {
            throw new IOException("Model not found in registry: " + modelIdentifier +
                    ". Import a model archive to add it.");
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
     * Check if a model is available in the registry.
     */
    public static boolean usesAutoModelManagement(String modelIdentifier) {
        return isModelAvailable(modelIdentifier);
    }

    /**
     * Get the model type (dense/sparse) from the registry.
     */
    public static String getModelType(String modelIdentifier) {
        return getRegistryManager().getModelType(modelIdentifier);
    }

    /**
     * Get the embedding dimension for a model from the registry.
     */
    public static Integer getEmbeddingDimension(String modelIdentifier) {
        return getRegistryManager().getEmbeddingDimension(modelIdentifier);
    }

    /**
     * Get the max sequence length for a model from the registry.
     */
    public static Integer getMaxSequenceLength(String modelIdentifier) {
        return getRegistryManager().getMaxSequenceLength(modelIdentifier);
    }

    /**
     * Check if a model is available in the registry.
     */
    public static boolean isModelAvailable(String modelIdentifier) {
        return getRegistryManager().isEncoderModelAvailable(modelIdentifier);
    }

    /**
     * Get all available model IDs from the registry.
     */
    public static Set<String> getAvailableModelIds() {
        return new HashSet<>(getRegistryManager().listEncoderModelIds());
    }

    /**
     * Get model info string from the registry.
     */
    public static String getModelInfo(String modelIdentifier) {
        if (!isModelAvailable(modelIdentifier)) {
            return "Model not in registry: " + modelIdentifier +
                    ". Import a model archive to add it.";
        }
        String type = getModelType(modelIdentifier);
        Integer dim = getEmbeddingDimension(modelIdentifier);
        EncoderType enc = getEncoderTypeFromModelId(modelIdentifier);

        return String.format("Model: %s, Type: %s, Encoder: %s, Dimension: %d, Source: registry",
                modelIdentifier, type, enc, dim != null ? dim : -1);
    }

    /**
     * Force refresh of the registry cache.
     * Call this after importing archives to pick up new models.
     */
    public static void refreshRegistry() {
        getRegistryManager().refresh();
    }

    /**
     * Configure the remote staging service.
     * Call this when the staging service configuration changes.
     */
    public static void configureStagingService(String url, String apiKey) {
        getRegistryManager().configureStagingService(url, apiKey);
    }

    /**
     * Configure the remote staging service with retry poll interval.
     * Call this when the staging service configuration changes.
     *
     * @param url the staging service URL
     * @param apiKey the API key for authentication (may be null)
     * @param retryPollIntervalSeconds the interval in seconds to poll when service is unavailable
     */
    public static void configureStagingService(String url, String apiKey, int retryPollIntervalSeconds) {
        getRegistryManager().configureStagingService(url, apiKey, retryPollIntervalSeconds);
    }

    /**
     * Get the configured retry poll interval in seconds.
     * This is the interval at which the application will poll when the staging service is unavailable.
     */
    public static int getStagingRetryPollIntervalSeconds() {
        return getRegistryManager().getStagingRetryPollIntervalSeconds();
    }

    /**
     * Load an archive file.
     */
    public static void loadArchive(java.nio.file.Path archivePath) throws IOException {
        getRegistryManager().loadArchive(archivePath);
    }

    /**
     * Clear the loaded archive.
     */
    public static void clearArchive() {
        getRegistryManager().clearArchive();
    }

    /**
     * Check if a source is configured.
     */
    public static boolean isSourceConfigured() {
        return getRegistryManager().isConfigured();
    }

    /**
     * Get the configured source type.
     */
    public static String getSourceType() {
        return getRegistryManager().getSourceType();
    }

    /**
     * Get the configured staging URL.
     * Used for subprocess inheritance.
     */
    public static String getStagingUrl() {
        return getRegistryManager().getStagingUrl();
    }

    /**
     * Get the configured staging API key.
     * Used for subprocess inheritance.
     */
    public static String getStagingApiKey() {
        return getRegistryManager().getStagingApiKey();
    }

    /**
     * Get the loaded archive path.
     * Used for subprocess inheritance.
     */
    public static java.nio.file.Path getLoadedArchivePath() {
        return getRegistryManager().getLoadedArchivePath();
    }

    // ==================== Active Model Selection ====================

    /**
     * Get the selected model ID for a role.
     */
    public static Optional<String> getSelectedModel(String role) {
        return getRegistryManager().getSelectedModel(role);
    }

    /**
     * Get the selected dense retrieval model ID.
     */
    public static Optional<String> getSelectedDenseRetrievalModel() {
        return getRegistryManager().getSelectedDenseRetrievalModel();
    }

    /**
     * Get the selected sparse retrieval model ID.
     */
    public static Optional<String> getSelectedSparseRetrievalModel() {
        return getRegistryManager().getSelectedSparseRetrievalModel();
    }

    /**
     * Get the selected reranking model ID.
     */
    public static Optional<String> getSelectedRerankingModel() {
        return getRegistryManager().getSelectedRerankingModel();
    }

    /**
     * Get all active selections.
     */
    public static java.util.Map<String, String> getActiveSelections() {
        return getRegistryManager().getActiveSelections();
    }

    /**
     * Check if a model is available in the registry.
     *
     * @param modelIdentifier The model identifier to check
     * @return true if the model is in the registry
     */
    public static boolean isModelInRegistry(String modelIdentifier) {
        return getRegistryManager().isEncoderModelAvailable(modelIdentifier);
    }

    /**
     * Get all available models from the registry.
     * Returns a map of model ID to source (always "registry").
     *
     * @return Map of model ID to source
     */
    public static Map<String, String> getAvailableModelsWithSources() {
        Map<String, String> result = new LinkedHashMap<>();

        for (String modelId : getRegistryManager().listEncoderModelIds()) {
            result.put(modelId, "registry");
        }

        return result;
    }

    // ==================== Model Registry & Optimization ====================

    /**
     * Get all available models from the registry with full details.
     * Returns a map of model ID to model info map.
     *
     * @return Map of model ID to model info
     */
    public static Map<String, Map<String, Object>> getAvailableModels() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        RegistryBasedModelManager registry = getRegistryManager();
        for (String modelId : registry.listEncoderModelIds()) {
            Optional<RegistryBasedModelManager.ModelEntry> entryOpt = registry.getModelEntry(modelId);
            if (entryOpt.isPresent()) {
                RegistryBasedModelManager.ModelEntry entry = entryOpt.get();
                Map<String, Object> modelInfo = new LinkedHashMap<>();
                modelInfo.put("modelId", modelId);
                modelInfo.put("type", entry.type);
                modelInfo.put("status", entry.status);
                modelInfo.put("path", entry.path);
                modelInfo.put("modelFile", entry.modelFile);
                modelInfo.put("vocabFile", entry.vocabFile);

                if (entry.metadata != null) {
                    modelInfo.put("embeddingDim", entry.metadata.embeddingDim);
                    modelInfo.put("optimized", entry.metadata.optimized);
                    modelInfo.put("optimizedAt", entry.metadata.optimizedAt);
                    modelInfo.put("optimizationTimeMs", entry.metadata.optimizationTimeMs);
                    modelInfo.put("unoptimizedBackupFile", entry.metadata.unoptimizedBackupFile);
                }

                result.put(modelId, modelInfo);
            }
        }

        return result;
    }

    /**
     * Get detailed info for a specific model.
     *
     * @param modelIdentifier The model ID
     * @return Map with model info, or null if not found
     */
    public static Map<String, Object> getModelInfoMap(String modelIdentifier) {
        RegistryBasedModelManager registry = getRegistryManager();
        Optional<RegistryBasedModelManager.ModelEntry> entryOpt = registry.getModelEntry(modelIdentifier);

        if (entryOpt.isEmpty()) {
            return null;
        }

        RegistryBasedModelManager.ModelEntry entry = entryOpt.get();
        Map<String, Object> modelInfo = new LinkedHashMap<>();
        modelInfo.put("modelId", modelIdentifier);
        modelInfo.put("type", entry.type);
        modelInfo.put("status", entry.status);
        modelInfo.put("path", entry.path);
        modelInfo.put("modelFile", entry.modelFile);
        modelInfo.put("vocabFile", entry.vocabFile);

        if (entry.metadata != null) {
            modelInfo.put("embeddingDim", entry.metadata.embeddingDim);
            modelInfo.put("optimized", entry.metadata.optimized);
            modelInfo.put("optimizedAt", entry.metadata.optimizedAt);
            modelInfo.put("optimizationTimeMs", entry.metadata.optimizationTimeMs);
            modelInfo.put("unoptimizedBackupFile", entry.metadata.unoptimizedBackupFile);
        }

        return modelInfo;
    }

    /**
     * Update the optimization status for a model.
     * This updates the registry metadata to reflect the optimization state.
     *
     * @param modelIdentifier The model ID
     * @param optimized Whether the model is optimized
     * @param backupFile Path to the unoptimized backup file (null to clear)
     * @param optimizationTimeMs Time taken to optimize in milliseconds (null if not optimized)
     */
    public static void updateModelOptimizationStatus(String modelIdentifier, boolean optimized,
                                                      String backupFile, Long optimizationTimeMs) {
        RegistryBasedModelManager registry = getRegistryManager();
        registry.updateModelOptimizationStatus(modelIdentifier, optimized, backupFile, optimizationTimeMs);
    }

    // ==================== VLM Image Encoder ====================

    /**
     * Creates a VLM image encoder that delegates to the staging service for image embedding.
     *
     * @param modelIdentifier the VLM model identifier (e.g., "smoldocling-256m", "siglip-vision")
     * @return a VlmImageEncoder instance
     */
    public static VlmImageEncoder createImageEncoder(String modelIdentifier) {
        String stagingUrl = getStagingUrl();
        if (stagingUrl == null || stagingUrl.isBlank()) {
            logger.warn("No staging service URL configured for VLM image encoder: {}", modelIdentifier);
        }
        logger.info("Creating VLM image encoder for model: {} via staging: {}", modelIdentifier, stagingUrl);
        return new VlmImageEncoder(modelIdentifier, stagingUrl);
    }

    /**
     * Creates a VLM image encoder with an explicit staging service URL.
     *
     * @param modelIdentifier the VLM model identifier
     * @param stagingServiceUrl the staging service base URL
     * @return a VlmImageEncoder instance
     */
    public static VlmImageEncoder createImageEncoder(String modelIdentifier, String stagingServiceUrl) {
        logger.info("Creating VLM image encoder for model: {} via staging: {}", modelIdentifier, stagingServiceUrl);
        return new VlmImageEncoder(modelIdentifier, stagingServiceUrl);
    }

    /**
     * Check if a model identifier represents a VLM image model.
     *
     * @param modelIdentifier the model identifier to check
     * @return true if it's a VLM image model
     */
    public static boolean isVlmImageModelId(String modelIdentifier) {
        return modelIdentifier != null && isVlmImageModel(modelIdentifier.toLowerCase());
    }

    /**
     * Get available VLM image model IDs by querying the registry dynamically.
     * Models are discovered from the staging service, not hardcoded.
     *
     * @return set of VLM image model IDs available in the registry
     */
    public static Set<String> getAvailableVlmImageModelIds() {
        Set<String> vlmModels = new HashSet<>();
        RegistryBasedModelManager registry = getRegistryManager();

        // Query all models and filter for VLM/image types
        for (String modelId : registry.listEncoderModelIds()) {
            Optional<RegistryBasedModelManager.ModelEntry> entry = registry.getModelEntry(modelId);
            if (entry.isPresent()) {
                String type = entry.get().type;
                if (type != null && (type.equalsIgnoreCase("vlm_image") ||
                        type.equalsIgnoreCase("image_encoder") ||
                        type.equalsIgnoreCase("vision_encoder"))) {
                    vlmModels.add(modelId);
                }
            }
            // Also check by model ID pattern for models registered as generic encoders
            if (isVlmImageModel(modelId.toLowerCase())) {
                vlmModels.add(modelId);
            }
        }

        return vlmModels;
    }

    /**
     * Check if the staging service has a VLM model loaded and available
     * for image embedding.
     *
     * @return true if a VLM model is available at the staging service
     */
    public static boolean isVlmImageAvailableAtStaging() {
        String stagingUrl = getStagingUrl();
        if (stagingUrl == null || stagingUrl.isBlank()) {
            return false;
        }

        try {
            VlmImageEncoder probe = new VlmImageEncoder("probe", stagingUrl);
            boolean available = probe.isAvailable();
            probe.close();
            return available;
        } catch (Exception e) {
            logger.debug("Could not check VLM availability at staging: {}", e.getMessage());
            return false;
        }
    }
}
