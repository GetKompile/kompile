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

package ai.kompile.ocr.models.factory;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.ocr.*;
import ai.kompile.ocr.models.detection.DBNetDetector;
import ai.kompile.ocr.models.recognition.CRNNRecognizer;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and managing OCR models.
 * Provides caching and lazy loading of models.
 *
 * <p>When the model staging module is on the classpath, this factory will
 * check the staging registry for active OCR models before falling back
 * to the built-in model registry.</p>
 */
@Component
public class OcrModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(OcrModelFactory.class);

    private final KompileModelManager modelManager;

    // Optional - may be null if kompile-model-staging is not on classpath
    private final RegistryService registryService;

    // Cache of instantiated models
    private final Map<String, OcrModel> modelCache = new ConcurrentHashMap<>();

    // Registry of built-in available models
    private final Map<String, ModelRegistration> registry = new HashMap<>();

    @Autowired
    public OcrModelFactory(KompileModelManager modelManager,
                           @Autowired(required = false) RegistryService registryService) {
        this.modelManager = modelManager;
        this.registryService = registryService;
        initializeRegistry();

        if (registryService != null) {
            logger.info("RegistryService available - will check for staged OCR models");
        }
    }

    /**
     * Initializes the registry with known models.
     */
    private void initializeRegistry() {
        // Detection models
        registerModel(new ModelRegistration(
                "dbnet-v2",
                "DBNet Text Detector",
                OcrModelType.OCR_DETECTION,
                DBNetDetector.class,
                "Differentiable Binarization Network for text detection"
        ));

        // Recognition models
        registerModel(new ModelRegistration(
                "crnn-v2",
                "CRNN Text Recognizer",
                OcrModelType.OCR_RECOGNITION,
                CRNNRecognizer.class,
                "Convolutional Recurrent Neural Network for text recognition"
        ));

        // Additional models can be registered here or dynamically

        logger.info("Initialized OCR model factory with {} registered models", registry.size());
    }

    /**
     * Registers a model in the factory.
     */
    public void registerModel(ModelRegistration registration) {
        registry.put(registration.modelId(), registration);
    }

    /**
     * Gets or creates a model by ID.
     *
     * <p>The lookup order is:</p>
     * <ol>
     *   <li>Check model cache</li>
     *   <li>Check staging registry for active OCR models (if available)</li>
     *   <li>Check built-in registry</li>
     * </ol>
     */
    public Optional<OcrModel> getModel(String modelId) {
        // Check cache first
        OcrModel cached = modelCache.get(modelId);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Check staging registry if available
        if (registryService != null) {
            Optional<ModelEntry> stagedModel = findStagedOcrModel(modelId);
            if (stagedModel.isPresent()) {
                try {
                    OcrModel model = createModelFromStagedEntry(stagedModel.get());
                    if (model != null) {
                        modelCache.put(modelId, model);
                        logger.info("Loaded OCR model {} from staging registry", modelId);
                        return Optional.of(model);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load staged OCR model {}: {}. Falling back to built-in registry.",
                            modelId, e.getMessage());
                }
            }
        }

        // Fall back to built-in registry
        ModelRegistration registration = registry.get(modelId);
        if (registration == null) {
            logger.warn("No model registered with ID: {}", modelId);
            return Optional.empty();
        }

        // Create model instance
        try {
            OcrModel model = createModel(registration);
            modelCache.put(modelId, model);
            return Optional.of(model);
        } catch (Exception e) {
            logger.error("Failed to create model {}: {}", modelId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Gets a detection model.
     */
    public Optional<TextDetectionModel> getDetectionModel(String modelId) {
        return getModel(modelId)
                .filter(m -> m instanceof TextDetectionModel)
                .map(m -> (TextDetectionModel) m);
    }

    /**
     * Gets a recognition model.
     */
    public Optional<TextRecognitionModel> getRecognitionModel(String modelId) {
        return getModel(modelId)
                .filter(m -> m instanceof TextRecognitionModel)
                .map(m -> (TextRecognitionModel) m);
    }

    /**
     * Gets a table extraction model.
     */
    public Optional<TableExtractionModel> getTableModel(String modelId) {
        return getModel(modelId)
                .filter(m -> m instanceof TableExtractionModel)
                .map(m -> (TableExtractionModel) m);
    }

    /**
     * Gets a layout model.
     */
    public Optional<LayoutModel> getLayoutModel(String modelId) {
        return getModel(modelId)
                .filter(m -> m instanceof LayoutModel)
                .map(m -> (LayoutModel) m);
    }

    /**
     * Gets all models of a specific type.
     */
    public List<ModelRegistration> getModelsOfType(OcrModelType type) {
        return registry.values().stream()
                .filter(r -> r.type() == type)
                .toList();
    }

    /**
     * Gets all registered models.
     */
    public List<ModelRegistration> getAllModels() {
        return new ArrayList<>(registry.values());
    }

    /**
     * Gets the default detection model.
     */
    public Optional<TextDetectionModel> getDefaultDetectionModel() {
        return getDetectionModel("dbnet-v2");
    }

    /**
     * Gets the default recognition model.
     */
    public Optional<TextRecognitionModel> getDefaultRecognitionModel() {
        return getRecognitionModel("crnn-v2");
    }

    /**
     * Loads a model and adds it to the cache.
     */
    public void loadModel(String modelId) throws Exception {
        OcrModel model = getModel(modelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));

        if (!model.isLoaded()) {
            model.load();
        }
    }

    /**
     * Unloads a model from cache.
     */
    public void unloadModel(String modelId) {
        OcrModel model = modelCache.get(modelId);
        if (model != null) {
            model.unload();
            modelCache.remove(modelId);
        }
    }

    /**
     * Unloads all models.
     */
    public void unloadAll() {
        for (OcrModel model : modelCache.values()) {
            model.unload();
        }
        modelCache.clear();
    }

    /**
     * Creates a model instance from registration.
     */
    private OcrModel createModel(ModelRegistration registration) throws Exception {
        Class<? extends OcrModel> modelClass = registration.modelClass();

        // Try constructor with ModelManager
        try {
            return modelClass.getConstructor(KompileModelManager.class)
                    .newInstance(modelManager);
        } catch (NoSuchMethodException e) {
            // Try no-arg constructor
            return modelClass.getConstructor().newInstance();
        }
    }

    /**
     * Model registration information.
     */
    public record ModelRegistration(
            String modelId,
            String name,
            OcrModelType type,
            Class<? extends OcrModel> modelClass,
            String description
    ) {
        public boolean isDetection() {
            return type == OcrModelType.OCR_DETECTION;
        }

        public boolean isRecognition() {
            return type == OcrModelType.OCR_RECOGNITION;
        }

        public boolean isTable() {
            return type == OcrModelType.OCR_TABLE;
        }

        public boolean isLayout() {
            return type == OcrModelType.LAYOUT_MODEL;
        }
    }

    /**
     * Gets cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("registeredModels", registry.size());
        stats.put("cachedModels", modelCache.size());
        stats.put("loadedModels", modelCache.values().stream()
                .filter(OcrModel::isLoaded)
                .count());
        if (registryService != null) {
            stats.put("stagedOcrModels", registryService.getAllOcrModels().size());
        }
        return stats;
    }

    // ==================== Staging Registry Integration ====================

    /**
     * Find an active OCR model in the staging registry.
     */
    private Optional<ModelEntry> findStagedOcrModel(String modelId) {
        if (registryService == null) {
            return Optional.empty();
        }

        return registryService.getModel(modelId)
                .filter(entry -> entry.isActive())
                .filter(entry -> entry.getType().isOcr());
    }

    /**
     * Create an OcrModel from a staged ModelEntry.
     */
    private OcrModel createModelFromStagedEntry(ModelEntry entry) throws Exception {
        OcrModelType ocrType = mapModelTypeToOcrType(entry.getType());

        // Determine which implementation class to use based on model ID or type
        Class<? extends OcrModel> modelClass = determineModelClass(entry.getModelId(), ocrType);
        if (modelClass == null) {
            logger.warn("No implementation class found for staged model: {}", entry.getModelId());
            return null;
        }

        // Create using reflection with KompileModelManager
        try {
            return modelClass.getConstructor(KompileModelManager.class)
                    .newInstance(modelManager);
        } catch (NoSuchMethodException e) {
            return modelClass.getConstructor().newInstance();
        }
    }

    /**
     * Determine the implementation class for a model.
     */
    private Class<? extends OcrModel> determineModelClass(String modelId, OcrModelType type) {
        // Check built-in registry first
        ModelRegistration reg = registry.get(modelId);
        if (reg != null) {
            return reg.modelClass();
        }

        // Default classes by type (for staged models without explicit registration)
        switch (type) {
            case OCR_DETECTION:
                return DBNetDetector.class;  // Default detection model
            case OCR_RECOGNITION:
                return CRNNRecognizer.class;  // Default recognition model
            default:
                return null;
        }
    }

    /**
     * Map ModelType to OcrModelType by name (all OCR constant names are identical).
     */
    private OcrModelType mapModelTypeToOcrType(ModelType type) {
        if (!type.isOcr()) {
            throw new IllegalArgumentException("Not an OCR type: " + type);
        }
        return OcrModelType.valueOf(type.name());
    }

    /**
     * Map OcrModelType to ModelType by name (all OCR constant names are identical).
     */
    private ModelType mapOcrTypeToModelType(OcrModelType type) {
        return ModelType.valueOf(type.name());
    }

    /**
     * Gets the active model for a specific OCR type.
     * Prefers staged models over built-in models.
     */
    public Optional<OcrModel> getActiveModelByType(OcrModelType type) {
        // Check staging registry first
        if (registryService != null) {
            ModelType modelType = mapOcrTypeToModelType(type);
            Optional<ModelEntry> active = registryService.getActiveModelByType(modelType);
            if (active.isPresent()) {
                return getModel(active.get().getModelId());
            }
        }

        // Fall back to first built-in model of this type
        return registry.values().stream()
                .filter(r -> r.type() == type)
                .findFirst()
                .flatMap(r -> getModel(r.modelId()));
    }

    /**
     * Gets the active detection model (from staging or built-in).
     */
    public Optional<TextDetectionModel> getActiveDetectionModel() {
        return getActiveModelByType(OcrModelType.OCR_DETECTION)
                .filter(m -> m instanceof TextDetectionModel)
                .map(m -> (TextDetectionModel) m);
    }

    /**
     * Gets the active recognition model (from staging or built-in).
     */
    public Optional<TextRecognitionModel> getActiveRecognitionModel() {
        return getActiveModelByType(OcrModelType.OCR_RECOGNITION)
                .filter(m -> m instanceof TextRecognitionModel)
                .map(m -> (TextRecognitionModel) m);
    }

    /**
     * Checks if the staging registry is available.
     */
    public boolean isStagingRegistryAvailable() {
        return registryService != null;
    }
}
