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

package ai.kompile.vectorstore.anserini.reranking;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.reranking.Reranker;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerContext;
import ai.kompile.core.reranking.RerankerType;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.KompileModelManager.CrossEncoderBundle;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import ai.kompile.modelmanager.RegistryBasedModelManager;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-Encoder reranker adapter using SameDiff models.
 * <p>
 * Cross-encoders process query-document pairs together through a transformer
 * model to produce a relevance score. This typically yields higher quality
 * reranking than bi-encoder approaches, but at higher computational cost.
 * <p>
 * Supports registry-driven model loading with fallback to ModelConstants.
 * Models can be imported via archives and registered in the registry,
 * which takes precedence over built-in ModelConstants.
 * <p>
 * Supported models (defined in ModelConstants, SameDiff .sdz format):
 * <ul>
 *   <li>ms-marco-MiniLM-L-6-v2 - Fast, 6-layer MiniLM for MS MARCO</li>
 *   <li>ms-marco-MiniLM-L-12-v2 - Higher quality, 12-layer MiniLM</li>
 *   <li>stsb-TinyBERT-L-4 - Lightweight for semantic similarity</li>
 *   <li>mmarco-mMiniLMv2-L12-H384-v1 - Multilingual reranking</li>
 *   <li>qnli-distilroberta-base - Question-answer relevance</li>
 * </ul>
 * <p>
 * Models are converted from HuggingFace PyTorch format to SameDiff (.sdz).
 * Source URLs are documented in ModelConstants for reference.
 * <p>
 * Note: This adapter requires SameDiff (ND4J) for model inference. If not available,
 * it falls back to a simple term overlap scoring as a baseline.
 */
public class CrossEncoderRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderRerankerAdapter.class);

    // Singleton registry manager for efficient model lookups
    private static volatile RegistryBasedModelManager registryManager;

    private static RegistryBasedModelManager getRegistryManager() {
        if (registryManager == null) {
            synchronized (CrossEncoderRerankerAdapter.class) {
                if (registryManager == null) {
                    registryManager = new RegistryBasedModelManager();
                }
            }
        }
        return registryManager;
    }

    private final String modelId;
    private final int topK;
    private final int batchSize;
    private final ModelDescriptor modelDescriptor;
    private final boolean samediffAvailable;
    private final KompileModelManager modelManager;
    private final boolean loadedFromRegistry;
    private CrossEncoderBundle modelBundle;
    private Path modelPath;
    private Path vocabularyPath;
    private boolean modelLoaded = false;

    // SameDiff model and tokenizer for neural inference
    private SameDiff sameDiffModel;
    private SamediffBertTokenizerPreProcessor tokenizer;
    private boolean sameDiffModelLoaded = false;
    private final ReentrantLock inferenceLock = new ReentrantLock();

    // Default tokenizer configuration
    private static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    private static final boolean DEFAULT_DO_LOWER_CASE = true;
    private static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public CrossEncoderRerankerAdapter(RerankerConfig config) {
        this(config, getDefaultModelId(), null);
    }

    public CrossEncoderRerankerAdapter(RerankerConfig config, String modelId) {
        this(config, modelId, null);
    }

    public CrossEncoderRerankerAdapter(RerankerConfig config, String modelId, KompileModelManager modelManager) {
        this.modelId = modelId;
        this.topK = config.getTopK() > 0 ? config.getTopK() : -1; // -1 means rerank all
        this.batchSize = 32; // Default batch size
        this.modelManager = modelManager != null ? modelManager : new KompileModelManager();

        // Try registry first, then fall back to ModelConstants
        RegistryBasedModelManager registry = getRegistryManager();
        Optional<RegistryBasedModelManager.ModelEntry> registryEntry = registry.getModelEntry(modelId);

        if (registryEntry.isPresent() && "cross_encoder".equalsIgnoreCase(registryEntry.get().type)) {
            // Load from registry
            this.loadedFromRegistry = true;
            this.modelDescriptor = null;
            log.info("Loading cross-encoder model '{}' from registry", modelId);
            ensureModelLoadedFromRegistry();
        } else {
            // Fall back to ModelConstants
            this.loadedFromRegistry = false;
            this.modelDescriptor = ModelConstants.getCrossEncoderModelDescriptor(modelId);
            if (modelDescriptor == null) {
                log.warn("Cross-encoder model '{}' not found in registry or ModelConstants. Using fallback scoring.", modelId);
            } else {
                log.info("Initialized CrossEncoderRerankerAdapter with model: {} ({}) from built-in catalog",
                        modelId, modelDescriptor.getMetadataString("description"));
                ensureModelLoaded();
            }
        }

        // Check if SameDiff (ND4J) is available
        this.samediffAvailable = checkSameDiffAvailable();
        if (!samediffAvailable) {
            log.warn("SameDiff (ND4J) not available. Cross-encoder will use fallback term overlap scoring. " +
                    "For neural reranking, ensure nd4j-native or nd4j-cuda backend is available.");
        }
    }

    /**
     * Get the default model ID, preferring registry if available.
     */
    private static String getDefaultModelId() {
        // Check if there's a cross-encoder in the registry with reranking role
        RegistryBasedModelManager registry = getRegistryManager();
        List<RegistryBasedModelManager.ModelEntry> rerankingModels = registry.getModelsByRagRole("reranking");
        if (!rerankingModels.isEmpty()) {
            String registryDefault = rerankingModels.get(0).modelId;
            log.debug("Using registry default cross-encoder: {}", registryDefault);
            return registryDefault;
        }
        return ModelConstants.getDefaultCrossEncoderModelId();
    }

    /**
     * Ensure the cross-encoder model is loaded from the registry.
     */
    private void ensureModelLoadedFromRegistry() {
        if (modelLoaded) {
            return;
        }

        try {
            modelBundle = getRegistryManager().getCrossEncoderModelBundle(modelId);
            if (modelBundle != null) {
                modelPath = modelBundle.getModelPath();
                vocabularyPath = modelBundle.getVocabularyPath();
                modelLoaded = true;
                log.info("Cross-encoder model loaded from registry: {} at {} (vocab: {})",
                        modelId, modelPath, vocabularyPath);
            } else {
                log.warn("Failed to get cross-encoder bundle from registry for '{}'", modelId);
            }
        } catch (Exception e) {
            log.warn("Failed to load cross-encoder model '{}' from registry: {}. Will use fallback scoring.",
                    modelId, e.getMessage());
        }
    }

    /**
     * Ensure the cross-encoder model is downloaded and available.
     */
    private void ensureModelLoaded() {
        if (modelLoaded || modelDescriptor == null) {
            return;
        }

        try {
            modelBundle = modelManager.ensureCrossEncoderModelAvailable(modelId);
            modelPath = modelBundle.getModelPath();
            vocabularyPath = modelBundle.getVocabularyPath();
            modelLoaded = true;
            log.info("Cross-encoder model loaded: {} at {} (vocab: {})", modelId, modelPath, vocabularyPath);
        } catch (IOException e) {
            log.warn("Failed to load cross-encoder model '{}': {}. Will use fallback scoring.", modelId, e.getMessage());
        }
    }

    /**
     * Check if the model is loaded and ready.
     */
    public boolean isModelLoaded() {
        return modelLoaded;
    }

    /**
     * Get the model bundle if loaded.
     */
    public CrossEncoderBundle getModelBundle() {
        return modelBundle;
    }

    /**
     * Get the path to the loaded model.
     */
    public Path getModelPath() {
        return modelPath;
    }

    /**
     * Get the path to the vocabulary file.
     */
    public Path getVocabularyPath() {
        return vocabularyPath;
    }

    /**
     * Check if SameDiff (ND4J) is available for neural inference.
     */
    private boolean checkSameDiffAvailable() {
        try {
            Class.forName("org.nd4j.autodiff.samediff.SameDiff");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Check if SameDiff is available for inference.
     */
    public boolean isSameDiffAvailable() {
        return samediffAvailable;
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        if (documents == null || documents.isEmpty() || query == null || query.isBlank()) {
            return documents;
        }

        log.debug("Cross-encoder reranking {} documents with model '{}'", documents.size(), modelId);

        // Determine how many documents to rerank
        int numToRerank = topK > 0 ? Math.min(topK, documents.size()) : documents.size();
        List<ScoredDocument> toRerank = documents.subList(0, numToRerank);
        List<ScoredDocument> remaining = numToRerank < documents.size()
                ? documents.subList(numToRerank, documents.size())
                : List.of();

        // Score documents
        List<ScoredDocument> reranked;
        if (samediffAvailable && modelLoaded && modelDescriptor != null) {
            reranked = rerankWithSameDiff(toRerank, query);
        } else {
            reranked = rerankWithFallback(toRerank, query);
        }

        // Sort by new scores (descending)
        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        // Append remaining documents that weren't reranked
        if (!remaining.isEmpty()) {
            List<ScoredDocument> result = new ArrayList<>(reranked);
            result.addAll(remaining);
            return result;
        }

        log.debug("Cross-encoder reranking complete: {} documents reranked", reranked.size());
        return reranked;
    }

    /**
     * Rerank using SameDiff neural model.
     * This method loads and runs the cross-encoder model in SameDiff format.
     */
    private List<ScoredDocument> rerankWithSameDiff(List<ScoredDocument> documents, String query) {
        // Ensure SameDiff model is initialized (lazy loading)
        if (!ensureSameDiffModelLoaded()) {
            log.warn("SameDiff model could not be loaded, falling back to term overlap scoring");
            return rerankWithFallback(documents, query);
        }

        List<ScoredDocument> reranked = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // Process documents in batches for efficiency
        int batchStart = 0;
        while (batchStart < documents.size()) {
            int batchEnd = Math.min(batchStart + batchSize, documents.size());
            List<ScoredDocument> batch = documents.subList(batchStart, batchEnd);

            try {
                List<Double> scores = scoreBatch(query, batch);
                for (int i = 0; i < batch.size(); i++) {
                    ScoredDocument doc = batch.get(i);
                    double newScore = scores.get(i);
                    reranked.add(new ScoredDocument(doc.document(), newScore));
                }
            } catch (Exception e) {
                log.warn("Batch inference failed, using fallback for {} documents: {}",
                        batch.size(), e.getMessage());
                // Fall back to term overlap for this batch
                for (ScoredDocument doc : batch) {
                    String[] queryTerms = query.toLowerCase().split("\\s+");
                    double score = computeFallbackScore(doc, queryTerms);
                    reranked.add(new ScoredDocument(doc.document(), score));
                }
            }

            batchStart = batchEnd;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("SameDiff cross-encoder scored {} documents in {}ms ({:.1f} docs/sec)",
                documents.size(), elapsed, documents.size() * 1000.0 / Math.max(1, elapsed));

        return reranked;
    }

    /**
     * Ensure the SameDiff model and tokenizer are loaded.
     * Uses lazy initialization for efficient resource usage.
     */
    private boolean ensureSameDiffModelLoaded() {
        if (sameDiffModelLoaded) {
            return true;
        }

        inferenceLock.lock();
        try {
            if (sameDiffModelLoaded) {
                return true;
            }

            if (modelPath == null || vocabularyPath == null) {
                log.warn("Model path or vocabulary path not available");
                return false;
            }

            if (!Files.exists(modelPath)) {
                log.warn("Model file not found: {}", modelPath);
                return false;
            }

            if (!Files.exists(vocabularyPath)) {
                log.warn("Vocabulary file not found: {}", vocabularyPath);
                return false;
            }

            log.info("Loading SameDiff cross-encoder model from: {}", modelPath);

            // Load vocabulary
            SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(
                    vocabularyPath.toFile(),
                    SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN
            );

            // Get tokenizer config from bundle if available
            int maxSeqLength = DEFAULT_MAX_SEQUENCE_LENGTH;
            boolean doLowerCase = DEFAULT_DO_LOWER_CASE;
            boolean addSpecialTokens = DEFAULT_ADD_SPECIAL_TOKENS;

            if (modelBundle != null && modelBundle.getTokenizerConfig() != null) {
                maxSeqLength = modelBundle.getTokenizerConfig().getMaxSequenceLength();
                doLowerCase = modelBundle.getTokenizerConfig().isDoLowerCase();
                addSpecialTokens = modelBundle.getTokenizerConfig().isAddSpecialTokens();
            }

            // Create tokenizer
            tokenizer = new SamediffBertTokenizerPreProcessor(
                    vocabulary,
                    doLowerCase,
                    addSpecialTokens,
                    maxSeqLength
            );

            // Load SameDiff model
            sameDiffModel = SDZSerializer.load(modelPath.toFile(), true);

            if (sameDiffModel == null) {
                log.error("Failed to load SameDiff model - serializer returned null");
                return false;
            }

            log.info("SameDiff cross-encoder model loaded successfully. Inputs: {}, Outputs: {}",
                    sameDiffModel.inputs(), sameDiffModel.outputs());

            sameDiffModelLoaded = true;
            return true;

        } catch (Exception e) {
            log.error("Failed to load SameDiff cross-encoder model: {}", e.getMessage(), e);
            return false;
        } finally {
            inferenceLock.unlock();
        }
    }

    /**
     * Score a batch of query-document pairs using the cross-encoder model.
     */
    private List<Double> scoreBatch(String query, List<ScoredDocument> documents) {
        inferenceLock.lock();
        try {
            int batchLen = documents.size();
            List<Double> scores = new ArrayList<>(batchLen);

            // Tokenize query-document pairs
            // Cross-encoder format: [CLS] query [SEP] document [SEP]
            List<int[]> inputIds = new ArrayList<>();
            List<int[]> attentionMasks = new ArrayList<>();
            List<int[]> tokenTypeIds = new ArrayList<>();
            int maxLen = 0;

            for (ScoredDocument doc : documents) {
                String docText = doc.getText();
                if (docText == null) docText = "";

                // Encode query-document pair
                // The tokenizer will handle [CLS] query [SEP] document [SEP] formatting
                SamediffBertTokenizerPreProcessor.BertEncoding encoding =
                        tokenizer.encodePair(query, docText);

                // Convert to int[] for ND4J compatibility
                inputIds.add(encoding.inputIdsAsInt());
                attentionMasks.add(encoding.attentionMaskAsInt());
                tokenTypeIds.add(encoding.tokenTypeIdsAsInt());

                maxLen = Math.max(maxLen, encoding.inputIds().length);
            }

            // Pad sequences to max length in batch
            int[][] paddedInputIds = new int[batchLen][maxLen];
            int[][] paddedAttentionMask = new int[batchLen][maxLen];
            int[][] paddedTokenTypeIds = new int[batchLen][maxLen];

            for (int i = 0; i < batchLen; i++) {
                int[] ids = inputIds.get(i);
                int[] mask = attentionMasks.get(i);
                int[] types = tokenTypeIds.get(i);

                for (int j = 0; j < ids.length; j++) {
                    paddedInputIds[i][j] = ids[j];
                    paddedAttentionMask[i][j] = mask[j];
                    paddedTokenTypeIds[i][j] = types[j];
                }
                // Padding values are 0 by default (already correct)
            }

            // Create ND4J tensors
            INDArray inputIdsTensor = Nd4j.createFromArray(paddedInputIds);
            INDArray attentionMaskTensor = Nd4j.createFromArray(paddedAttentionMask);
            INDArray tokenTypeIdsTensor = Nd4j.createFromArray(paddedTokenTypeIds);

            try {
                // Build input map - dynamically determine input names
                Map<String, INDArray> inputs = new HashMap<>();
                List<String> inputNames = sameDiffModel.inputs();

                // Map inputs based on common patterns
                for (String inputName : inputNames) {
                    String lowerName = inputName.toLowerCase();
                    if (lowerName.contains("input_ids") || lowerName.equals("input_ids")) {
                        inputs.put(inputName, inputIdsTensor);
                    } else if (lowerName.contains("attention_mask") || lowerName.contains("mask")) {
                        inputs.put(inputName, attentionMaskTensor);
                    } else if (lowerName.contains("token_type") || lowerName.contains("segment")) {
                        inputs.put(inputName, tokenTypeIdsTensor);
                    }
                }

                // If no inputs matched, try sequential assignment
                if (inputs.isEmpty() && inputNames.size() >= 2) {
                    inputs.put(inputNames.get(0), inputIdsTensor);
                    inputs.put(inputNames.get(1), attentionMaskTensor);
                    if (inputNames.size() >= 3) {
                        inputs.put(inputNames.get(2), tokenTypeIdsTensor);
                    }
                }

                // Run forward pass
                Map<String, INDArray> outputs = sameDiffModel.output(inputs, sameDiffModel.outputs());

                // Extract scores from output
                // Cross-encoders typically have a single output with shape [batch, 1] or [batch]
                // representing relevance scores
                INDArray outputTensor = null;
                for (String outputName : sameDiffModel.outputs()) {
                    outputTensor = outputs.get(outputName);
                    if (outputTensor != null) break;
                }

                if (outputTensor == null) {
                    throw new RuntimeException("No output tensor found from model");
                }

                // Extract scores - handle different output shapes
                long[] shape = outputTensor.shape();
                if (shape.length == 1) {
                    // Shape [batch]
                    for (int i = 0; i < batchLen; i++) {
                        scores.add(outputTensor.getDouble(i));
                    }
                } else if (shape.length == 2 && shape[1] == 1) {
                    // Shape [batch, 1]
                    for (int i = 0; i < batchLen; i++) {
                        scores.add(outputTensor.getDouble(i, 0));
                    }
                } else if (shape.length == 2 && shape[1] == 2) {
                    // Shape [batch, 2] - logits for [not_relevant, relevant]
                    // Use softmax to get probability of relevance
                    for (int i = 0; i < batchLen; i++) {
                        double logit0 = outputTensor.getDouble(i, 0);
                        double logit1 = outputTensor.getDouble(i, 1);
                        double expMax = Math.max(logit0, logit1);
                        double exp0 = Math.exp(logit0 - expMax);
                        double exp1 = Math.exp(logit1 - expMax);
                        double relevanceProb = exp1 / (exp0 + exp1);
                        scores.add(relevanceProb);
                    }
                } else {
                    // Unknown shape - take first element as score
                    log.warn("Unexpected output shape: {}. Taking first element as score.",
                            java.util.Arrays.toString(shape));
                    for (int i = 0; i < batchLen; i++) {
                        scores.add(outputTensor.getDouble(i));
                    }
                }

                // Close tensors to free memory
                outputTensor.close();

            } finally {
                // Always close input tensors
                inputIdsTensor.close();
                attentionMaskTensor.close();
                tokenTypeIdsTensor.close();
            }

            return scores;

        } finally {
            inferenceLock.unlock();
        }
    }

    /**
     * Fallback reranking using enhanced term overlap scoring.
     * This provides a baseline when neural inference is not available.
     */
    private List<ScoredDocument> rerankWithFallback(List<ScoredDocument> documents, String query) {
        List<ScoredDocument> reranked = new ArrayList<>();
        String[] queryTerms = query.toLowerCase().split("\\s+");

        for (ScoredDocument doc : documents) {
            double score = computeFallbackScore(doc, queryTerms);
            reranked.add(new ScoredDocument(doc.document(), score));
        }

        return reranked;
    }

    /**
     * Compute a fallback score using enhanced term overlap and position weighting.
     * This is a simple baseline that doesn't require neural inference.
     */
    private double computeFallbackScore(ScoredDocument doc, String[] queryTerms) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        String lowerText = text.toLowerCase();
        String[] docTerms = lowerText.split("\\s+");

        double score = 0.0;
        int totalMatches = 0;
        int consecutiveMatches = 0;
        int lastMatchIndex = -2;

        for (int i = 0; i < docTerms.length; i++) {
            String docTerm = docTerms[i];
            for (String queryTerm : queryTerms) {
                if (docTerm.contains(queryTerm) || queryTerm.contains(docTerm)) {
                    totalMatches++;

                    // Bonus for consecutive matches (phrase-like)
                    if (i == lastMatchIndex + 1) {
                        consecutiveMatches++;
                    }
                    lastMatchIndex = i;

                    // Position weighting: earlier matches are slightly better
                    double positionWeight = 1.0 - (0.5 * i / Math.max(1, docTerms.length));
                    score += positionWeight;
                    break;
                }
            }
        }

        // Normalize by query length
        if (queryTerms.length > 0) {
            score = score / queryTerms.length;
        }

        // Add bonus for consecutive matches (phrase matching)
        score += consecutiveMatches * 0.5;

        // Combine with original retrieval score for stability
        double originalWeight = 0.3;
        return originalWeight * doc.score() + (1 - originalWeight) * score;
    }

    @Override
    public String tag() {
        String source = loadedFromRegistry ? "registry" : "built-in";
        return String.format("CrossEncoder(model=%s,topK=%d,loaded=%s,samediff=%s,source=%s)",
                modelId, topK, modelLoaded ? "true" : "false",
                samediffAvailable ? "true" : "fallback", source);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.CROSS_ENCODER;
    }

    /**
     * Get the model ID being used.
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * Get the model descriptor.
     */
    public ModelDescriptor getModelDescriptor() {
        return modelDescriptor;
    }

    /**
     * Check if the model was loaded from registry.
     */
    public boolean isLoadedFromRegistry() {
        return loadedFromRegistry;
    }

    /**
     * Get the HuggingFace source URL for the model.
     */
    public String getHuggingFaceSource() {
        return modelDescriptor != null ? modelDescriptor.getMetadataString("huggingface_source") : null;
    }

    /**
     * Get available cross-encoder models from both registry and built-in catalog.
     */
    public static Set<String> getAvailableModels() {
        Set<String> allModels = new HashSet<>();

        // Add from registry
        allModels.addAll(getRegistryManager().listCrossEncoderModelIds());

        // Add from ModelConstants
        allModels.addAll(ModelConstants.getAvailableCrossEncoderModelIds());

        return allModels;
    }

    /**
     * Check if a cross-encoder model is available.
     */
    public static boolean isModelAvailable(String modelId) {
        // Check registry first
        if (getRegistryManager().isCrossEncoderModelAvailable(modelId)) {
            return true;
        }
        // Fall back to ModelConstants
        return ModelConstants.isCrossEncoderModelAvailable(modelId);
    }

    /**
     * Force refresh of the registry cache.
     */
    public static void refreshRegistry() {
        getRegistryManager().refreshRegistry();
        log.info("Cross-encoder registry cache refreshed");
    }
}
