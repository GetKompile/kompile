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

package ai.kompile.modelmanager;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ai.kompile.utils.HashUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Manages the download, caching, and retrieval of ML/NLP models.
 *
 * Model Loading Priority:
 * 1. First checks local registry.json at ~/.kompile/models/registry.json
 * 2. Falls back to ModelConstants for hard-coded model descriptors
 *
 * This allows optimized models (staged via kompile-model-staging) to be loaded
 * instead of downloading fresh copies from remote URLs.
 */
public class KompileModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(KompileModelManager.class);
    private static final String SDX_MANIFEST_CHECKSUM_NAME = SdxSdkManifest.FILE_NAME + ".sha256";
    private static final long MAX_SDX_MANIFEST_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_SDX_CHECKSUM_BYTES = 512L;
    private static final Duration SDX_DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    private static final Pattern SDX_MANIFEST_CHECKSUM = Pattern.compile(
            "^([0-9a-fA-F]{64})[ \\t]+\\*?" + Pattern.quote(SdxSdkManifest.FILE_NAME) + "\\s*$");
    private static final HttpClient SDX_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Environment variable to specify the model cache directory at runtime
    public static final String ENV_KOMPILE_MODEL_CACHE_DIR = "KOMPILE_MODEL_CACHE_DIR";
    public static final String DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR = ".kompile" + File.separator + "models";
    private static final String REGISTRY_FILENAME = "registry.json";

    private final Path baseCachePath;
    private final ObjectMapper objectMapper;

    // Cached registry
    private volatile JsonNode cachedRegistry;
    private volatile long registryLastModified = -1;

    /**
     * Initializes the model manager with a specific base cache path.
     * If the KOMPILE_MODEL_CACHE_DIR environment variable is set, it's used.
     * Otherwise, defaults to ~/.kompile/models.
     */
    public KompileModelManager() {
        this(ModelConstants.resolveBaseModelCacheDir());
    }

    /**
     * Constructor with custom cache path.
     */
    public KompileModelManager(Path customCachePath) {
        this.baseCachePath = customCachePath;
        this.objectMapper = JsonUtils.standardMapper();
        try {
            Files.createDirectories(this.baseCachePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create base model cache directory: " + this.baseCachePath, e);
        }
        LOGGER.info("KompileModelManager initialized with custom cache path: {}", this.baseCachePath.toAbsolutePath());
    }

    public Path getBaseCachePath() {
        return baseCachePath;
    }

    /**
     * Get the path to the registry file.
     */
    public Path getRegistryPath() {
        return baseCachePath.resolve(REGISTRY_FILENAME);
    }

    /**
     * Load the local registry.json if it exists.
     * Returns cached version if file hasn't changed.
     */
    private JsonNode loadLocalRegistry() {
        Path registryPath = getRegistryPath();
        if (!Files.exists(registryPath)) {
            return null;
        }

        try {
            long currentModified = Files.getLastModifiedTime(registryPath).toMillis();
            if (cachedRegistry != null && currentModified == registryLastModified) {
                return cachedRegistry;
            }

            synchronized (this) {
                // Double-check after acquiring lock
                currentModified = Files.getLastModifiedTime(registryPath).toMillis();
                if (cachedRegistry != null && currentModified == registryLastModified) {
                    return cachedRegistry;
                }

                cachedRegistry = objectMapper.readTree(registryPath.toFile());
                registryLastModified = currentModified;
                LOGGER.debug("Loaded registry from {}", registryPath);
                return cachedRegistry;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load registry from {}: {}", registryPath, e.getMessage());
            return null;
        }
    }

    /**
     * Look up an ACTIVE encoder model in the local registry.
     * Returns empty if not found or not active.
     */
    private Optional<RegistryModelEntry> findActiveEncoderInRegistry(String modelId) {
        JsonNode registry = loadLocalRegistry();
        if (registry == null) {
            return Optional.empty();
        }

        JsonNode models = registry.get("models");
        if (models == null || !models.has(modelId)) {
            return Optional.empty();
        }

        JsonNode model = models.get(modelId);
        String status = model.has("status") ? model.get("status").asText() : "";
        String type = model.has("type") ? model.get("type").asText() : "";

        // Only use active models, and only encoder types (not cross_encoder)
        if (!"active".equalsIgnoreCase(status)) {
            LOGGER.debug("Model {} found in registry but status is '{}', not 'active'", modelId, status);
            return Optional.empty();
        }

        // Check for encoder types (dense_encoder, sparse_encoder, or legacy 'encoder')
        if (!type.contains("encoder") || type.contains("cross")) {
            LOGGER.debug("Model {} found in registry but type is '{}', not an encoder type", modelId, type);
            return Optional.empty();
        }

        return Optional.of(parseModelEntry(modelId, model));
    }

    /**
     * Look up an ACTIVE cross-encoder model in the local registry.
     * Returns empty if not found or not active.
     */
    private Optional<RegistryModelEntry> findActiveCrossEncoderInRegistry(String modelId) {
        JsonNode registry = loadLocalRegistry();
        if (registry == null) {
            return Optional.empty();
        }

        JsonNode models = registry.get("models");
        if (models == null || !models.has(modelId)) {
            return Optional.empty();
        }

        JsonNode model = models.get(modelId);
        String status = model.has("status") ? model.get("status").asText() : "";
        String type = model.has("type") ? model.get("type").asText() : "";

        // Only use active models
        if (!"active".equalsIgnoreCase(status)) {
            LOGGER.debug("Cross-encoder {} found in registry but status is '{}', not 'active'", modelId, status);
            return Optional.empty();
        }

        // Check for cross_encoder type (or legacy 'reranker')
        if (!type.contains("cross_encoder") && !type.contains("reranker")) {
            LOGGER.debug("Cross-encoder {} found in registry but type is '{}', not a cross-encoder type", modelId, type);
            return Optional.empty();
        }

        return Optional.of(parseModelEntry(modelId, model));
    }

    /**
     * Parse a model entry from the registry JSON.
     */
    private RegistryModelEntry parseModelEntry(String modelId, JsonNode model) {
        RegistryModelEntry entry = new RegistryModelEntry();
        entry.modelId = modelId;
        entry.type = model.has("type") ? model.get("type").asText() : null;
        entry.path = model.has("path") ? model.get("path").asText() : null;
        entry.modelFile = model.has("model_file") ? model.get("model_file").asText() : "model.sdz";
        entry.vocabFile = model.has("vocab_file") ? model.get("vocab_file").asText() : "vocab.txt";
        entry.checksum = model.hasNonNull("checksum") ? model.get("checksum").asText() : null;
        entry.status = model.has("status") ? model.get("status").asText() : null;

        // Parse metadata
        JsonNode metadata = model.get("metadata");
        if (metadata != null) {
            entry.embeddingDim = metadata.has("embedding_dim") ? metadata.get("embedding_dim").asInt() : null;
            entry.hiddenSize = metadata.has("hidden_size") ? metadata.get("hidden_size").asInt() : null;
            entry.numLayers = metadata.has("num_layers") ? metadata.get("num_layers").asInt() : null;
            entry.maxSequenceLength = metadata.has("max_sequence_length") ? metadata.get("max_sequence_length").asInt() : 512;
            entry.encoderType = metadata.has("encoder_type") ? metadata.get("encoder_type").asText() : null;
            entry.poolingStrategy = metadata.has("pooling_strategy")
                    ? metadata.get("pooling_strategy").asText() : null;
            entry.inputPrefix = metadata.has("input_prefix")
                    ? metadata.get("input_prefix").asText() : null;
            entry.normalizeOutput = !metadata.has("normalize_output")
                    || metadata.get("normalize_output").asBoolean(true);
            entry.supportedLanguages = readStringList(metadata.get("supported_languages"));
            entry.optimized = metadata.has("optimized") && metadata.get("optimized").asBoolean(false);
        }

        // Parse tokenizer config
        JsonNode tokenizer = model.get("tokenizer");
        if (tokenizer != null) {
            entry.doLowerCase = !tokenizer.has("do_lower_case") || tokenizer.get("do_lower_case").asBoolean(true);
            entry.addSpecialTokens = !tokenizer.has("add_special_tokens") || tokenizer.get("add_special_tokens").asBoolean(true);
            entry.stripAccents = !tokenizer.has("strip_accents") || tokenizer.get("strip_accents").asBoolean(true);
            entry.tokenizerMaxLength = tokenizer.has("max_length") ? tokenizer.get("max_length").asInt() : 512;
        }

        return entry;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            ArrayList<String> values = new ArrayList<>();
            for (JsonNode element : node) {
                String value = element.asText(null);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
            return List.copyOf(values);
        }
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    /**
     * Build a ModelBundle from a registry entry.
     */
    private ModelBundle buildBundleFromRegistry(RegistryModelEntry entry) throws IOException {
        // Resolve paths - registry uses relative paths from the model cache directory
        Path cacheRoot = baseCachePath.toAbsolutePath().normalize();
        Path modelDir = cacheRoot.resolve(entry.path).normalize();
        Path modelPath = modelDir.resolve(entry.modelFile).normalize();
        Path vocabPath = modelDir.resolve(entry.vocabFile).normalize();
        if (!modelDir.startsWith(cacheRoot)
                || !modelPath.startsWith(modelDir)
                || !vocabPath.startsWith(modelDir)) {
            throw new IOException("Registry model path escapes the configured model cache: "
                    + entry.modelId);
        }

        // Verify files exist
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Model file not found at registry path: " + modelPath);
        }
        if (!Files.isRegularFile(vocabPath)) {
            throw new IOException("Vocabulary file not found at registry path: " + vocabPath);
        }
        if (entry.checksum != null && !entry.checksum.isBlank()) {
            String expected = entry.checksum.startsWith("sha256:")
                    ? entry.checksum.substring("sha256:".length())
                    : entry.checksum;
            String actual = calculateSha256(modelPath);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("Registry checksum mismatch for " + entry.modelId
                        + ": expected " + expected + ", got " + actual);
            }
        }

        // Build metadata map
        Map<String, Object> metadata = new HashMap<>();
        if (entry.embeddingDim != null) metadata.put("embedding_dim", entry.embeddingDim);
        if (entry.encoderType != null) metadata.put("encoder_type", entry.encoderType);
        if (entry.poolingStrategy != null) metadata.put("pooling_strategy", entry.poolingStrategy);
        if (entry.inputPrefix != null) metadata.put("input_prefix", entry.inputPrefix);
        metadata.put("normalize_output", entry.normalizeOutput);
        if (entry.supportedLanguages != null && !entry.supportedLanguages.isEmpty()) {
            metadata.put("supported_languages", List.copyOf(entry.supportedLanguages));
        }
        metadata.put("optimized", entry.optimized);
        metadata.put("tokenizer_do_lower_case", entry.doLowerCase);
        metadata.put("tokenizer_add_special_tokens", entry.addSpecialTokens);
        metadata.put("tokenizer_strip_accents", entry.stripAccents);
        metadata.put("tokenizer_max_sequence_length", entry.tokenizerMaxLength);

        TokenizerConfig tokenizerConfig = TokenizerConfig.builder()
                .doLowerCase(entry.doLowerCase)
                .addSpecialTokens(entry.addSpecialTokens)
                .stripAccents(entry.stripAccents)
                .maxSequenceLength(entry.tokenizerMaxLength)
                .build();

        LOGGER.info("Loading model {} from registry path: {} (optimized: {})",
                entry.modelId, modelPath, entry.optimized);

        return new ModelBundle(entry.modelId, modelPath, vocabPath, metadata, tokenizerConfig);
    }

    /**
     * Build a CrossEncoderBundle from a registry entry.
     */
    private CrossEncoderBundle buildCrossEncoderBundleFromRegistry(RegistryModelEntry entry) throws IOException {
        // Resolve paths - registry uses relative paths from the model cache directory
        Path cacheRoot = baseCachePath.toAbsolutePath().normalize();
        Path modelDir = cacheRoot.resolve(entry.path).normalize();
        Path modelPath = modelDir.resolve(entry.modelFile).normalize();
        Path vocabPath = modelDir.resolve(entry.vocabFile).normalize();
        if (!modelDir.startsWith(cacheRoot)
                || !modelPath.startsWith(modelDir)
                || !vocabPath.startsWith(modelDir)) {
            throw new IOException("Registry cross-encoder path escapes the model cache: "
                    + entry.modelId);
        }

        // Verify files exist
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Cross-encoder model file not found at registry path: " + modelPath);
        }
        if (!Files.isRegularFile(vocabPath)) {
            throw new IOException("Cross-encoder vocabulary file not found at registry path: " + vocabPath);
        }
        if (entry.checksum != null && !entry.checksum.isBlank()) {
            String expected = entry.checksum.startsWith("sha256:")
                    ? entry.checksum.substring("sha256:".length())
                    : entry.checksum;
            String actual = calculateSha256(modelPath);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("Registry checksum mismatch for " + entry.modelId
                        + ": expected " + expected + ", got " + actual);
            }
        }

        // Build metadata map
        Map<String, Object> metadata = new HashMap<>();
        if (entry.hiddenSize != null) metadata.put("hidden_size", entry.hiddenSize);
        if (entry.numLayers != null) metadata.put("num_layers", entry.numLayers);
        if (entry.supportedLanguages != null && !entry.supportedLanguages.isEmpty()) {
            metadata.put("supported_languages", List.copyOf(entry.supportedLanguages));
        }
        metadata.put("max_sequence_length", entry.maxSequenceLength);
        metadata.put("optimized", entry.optimized);
        metadata.put("tokenizer_do_lower_case", entry.doLowerCase);
        metadata.put("tokenizer_add_special_tokens", entry.addSpecialTokens);
        metadata.put("tokenizer_strip_accents", entry.stripAccents);
        metadata.put("tokenizer_max_sequence_length", entry.tokenizerMaxLength);

        TokenizerConfig tokenizerConfig = TokenizerConfig.builder()
                .doLowerCase(entry.doLowerCase)
                .addSpecialTokens(entry.addSpecialTokens)
                .stripAccents(entry.stripAccents)
                .maxSequenceLength(entry.tokenizerMaxLength)
                .build();

        LOGGER.info("Loading cross-encoder {} from registry path: {} (optimized: {})",
                entry.modelId, modelPath, entry.optimized);

        return new CrossEncoderBundle(entry.modelId, modelPath, vocabPath, metadata, tokenizerConfig);
    }

    /**
     * Clear the cached registry (useful for testing or forcing a reload).
     */
    public void clearRegistryCache() {
        synchronized (this) {
            cachedRegistry = null;
            registryLastModified = -1;
        }
    }

    /**
     * Check if a model is registered in the local registry (regardless of type or status).
     *
     * @param modelId The model identifier
     * @return true if the model exists in the registry
     */
    public boolean isModelInRegistry(String modelId) {
        JsonNode registry = loadLocalRegistry();
        if (registry == null) {
            return false;
        }
        JsonNode models = registry.get("models");
        return models != null && models.has(modelId);
    }

    /**
     * Check if a model is active in the registry (for encoder or cross-encoder).
     *
     * @param modelId The model identifier
     * @return true if the model is active in the registry
     */
    public boolean isModelActiveInRegistry(String modelId) {
        return findActiveEncoderInRegistry(modelId).isPresent()
                || findActiveCrossEncoderInRegistry(modelId).isPresent();
    }

    /**
     * Get the source of where a model will be loaded from.
     *
     * @param modelId The model identifier
     * @return "registry" if from local registry, "model_constants" if from hardcoded descriptors, or null if not found
     */
    public String getModelSource(String modelId) {
        if (findActiveEncoderInRegistry(modelId).isPresent() || findActiveCrossEncoderInRegistry(modelId).isPresent()) {
            return "registry";
        }
        if (ModelConstants.getAnseriniEncoderModelDescriptor(modelId) != null
                || ModelConstants.getCrossEncoderModelDescriptor(modelId) != null) {
            return "model_constants";
        }
        return null;
    }

    /**
     * Internal class representing a model entry from the registry.
     */
    private static class RegistryModelEntry {
        String modelId;
        String type;
        String path;
        String modelFile;
        String vocabFile;
        String checksum;
        String status;
        Integer embeddingDim;
        Integer hiddenSize;
        Integer numLayers;
        int maxSequenceLength = 512;
        String encoderType;
        String poolingStrategy;
        String inputPrefix;
        boolean normalizeOutput = true;
        List<String> supportedLanguages;
        boolean optimized;
        boolean doLowerCase = true;
        boolean addSpecialTokens = true;
        boolean stripAccents = true;
        int tokenizerMaxLength = 512;
    }

    /**
     * Ensures both model and vocabulary files are available for an encoder model.
     * This method first checks the local registry for the model (supports optimized models),
     * then falls back to downloading from ModelConstants if not found.
     *
     * @param modelId The model identifier (e.g., "bge-base-en-v1.5")
     * @return ModelBundle containing paths to both model and vocabulary files
     * @throws IOException if download or caching fails
     */
    public ModelBundle ensureEncoderModelBundle(String modelId) throws IOException {
        // FIRST: Check the local registry for an active model
        Optional<RegistryModelEntry> registryEntry = findActiveEncoderInRegistry(modelId);
        if (registryEntry.isPresent()) {
            try {
                return buildBundleFromRegistry(registryEntry.get());
            } catch (IOException e) {
                LOGGER.warn("Model {} found in registry but failed to load: {}. Falling back to ModelConstants.",
                        modelId, e.getMessage());
                // Fall through to ModelConstants
            }
        }

        // FALLBACK: Use ModelConstants for hard-coded model descriptors
        LOGGER.debug("Model {} not found in registry or failed to load, using ModelConstants", modelId);
        ModelDescriptor modelDescriptor = ModelConstants.getAnseriniEncoderModelDescriptor(modelId);
        ModelDescriptor vocabDescriptor = ModelConstants.getAnseriniEncoderVocabDescriptor(modelId);

        if (modelDescriptor == null) {
            throw new IOException("No model descriptor found for model ID: " + modelId);
        }
        if (vocabDescriptor == null) {
            throw new IOException("No vocabulary descriptor found for model ID: " + modelId);
        }

        // Ensure both model and vocabulary are downloaded
        Path modelPath = ensureModelAvailable(modelDescriptor);
        Path vocabPath = ensureModelAvailable(vocabDescriptor);
        TokenizerConfig tokenizerConfig = TokenizerConfig.fromMetadata(modelDescriptor.getMetadata());
        LOGGER.info("Model bundle ready for {}: model={}, vocab={}", modelId, modelPath, vocabPath);

        return new ModelBundle(modelId, modelPath, vocabPath, modelDescriptor.getMetadata(), tokenizerConfig);
    }

    /**
     * Ensures a model is available in the cache, downloading it if necessary.
     *
     * @param descriptor The descriptor of the model to ensure.
     * @return The path to the cached model artifact or directory.
     * @throws IOException if an I/O error occurs during download or caching.
     */
    public Path ensureModelAvailable(ModelDescriptor descriptor) throws IOException {
        Path modelPathInCache = baseCachePath.resolve(descriptor.getExpectedCacheSubpath());

        // A simple check: if a directory is expected, check if it exists and is not empty.
        // If a file is expected, check if it exists.
        // More sophisticated versioning/update checks could be added here.
        boolean needsDownload = true;
        if (Files.exists(modelPathInCache)) {
            if (descriptor.getDownloadUrl().endsWith(".tar.gz")) { // Assuming tar.gz implies a directory after extraction
                if (Files.isDirectory(modelPathInCache) && modelPathInCache.toFile().list().length > 0) {
                    LOGGER.info("Model directory {} already exists in cache and is not empty: {}", descriptor.getModelId(), modelPathInCache);
                    needsDownload = false;
                } else {
                    LOGGER.info("Model directory {} exists but is empty or not a directory. Re-downloading.", descriptor.getModelId());
                }
            } else { // Assuming it's a single file
                LOGGER.info("Model file {} already exists in cache: {}", descriptor.getModelId(), modelPathInCache);
                needsDownload = false;
            }
        }

        if (needsDownload) {
            LOGGER.info("Downloading model {} from {} to {}", descriptor.getModelId(), descriptor.getDownloadUrl(), modelPathInCache.getParent());
            Files.createDirectories(modelPathInCache.getParent()); // Ensure parent directory exists

            Path tempDownloadPath = Files.createTempFile(baseCachePath, descriptor.getModelId() + "_download", ".tmp");
            try {
                URL url = new URL(descriptor.getDownloadUrl());
                try (InputStream in = url.openStream();
                     ReadableByteChannel rbc = Channels.newChannel(in);
                     FileOutputStream fos = new FileOutputStream(tempDownloadPath.toFile())) {
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                }

                LOGGER.info("Successfully downloaded {} to temporary location: {}", descriptor.getModelId(), tempDownloadPath);

                if (descriptor.getChecksum() != null && !descriptor.getChecksum().trim().isEmpty()) {
                    String fileChecksum = calculateSha256(tempDownloadPath);
                    if (!descriptor.getChecksum().equalsIgnoreCase(fileChecksum)) {
                        Files.deleteIfExists(tempDownloadPath);
                        throw new IOException("Checksum mismatch for model " + descriptor.getModelId() + ". Expected " +
                                descriptor.getChecksum() + ", but got " + fileChecksum);
                    }
                    LOGGER.info("Checksum verified for model {}", descriptor.getModelId());
                }

                if (descriptor.getDownloadUrl().endsWith(".tar.gz")) {
                    LOGGER.info("Extracting {} to {}", tempDownloadPath, modelPathInCache.getParent());
                    // Ensure the target modelPathInCache directory is clean or created
                    if(Files.exists(modelPathInCache) && Files.isDirectory(modelPathInCache)) {
                        // Simple cleanup, for robust solution use more careful deletion
                        try (var walkStream = Files.walk(modelPathInCache)) {
                            walkStream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                        }
                    }
                    Files.createDirectories(modelPathInCache);
                    extractTarGz(tempDownloadPath, modelPathInCache.getParent()); // Extract into parent, then rename/move if structure differs
                    LOGGER.info("Successfully extracted {} to {}", descriptor.getModelId(), modelPathInCache.getParent());
                } else {
                    Files.move(tempDownloadPath, modelPathInCache, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Moved {} to {}", descriptor.getModelId(), modelPathInCache);
                }

            } finally {
                Files.deleteIfExists(tempDownloadPath); // Clean up temp file
            }
        }
        return modelPathInCache;
    }

    /**
     * Ensures a cross-encoder reranking model is available in the cache.
     * Downloads both the model (.sdz) and vocabulary files if not present.
     *
     * @param modelId The cross-encoder model identifier (e.g., "ms-marco-MiniLM-L-6-v2")
     * @return CrossEncoderBundle containing paths to model and vocabulary, plus metadata
     * @throws IOException if download or caching fails
     */
    public CrossEncoderBundle ensureCrossEncoderModelAvailable(String modelId) throws IOException {
        // FIRST: Check the local registry for an active cross-encoder model
        Optional<RegistryModelEntry> registryEntry = findActiveCrossEncoderInRegistry(modelId);
        if (registryEntry.isPresent()) {
            try {
                return buildCrossEncoderBundleFromRegistry(registryEntry.get());
            } catch (IOException e) {
                LOGGER.warn("Cross-encoder {} found in registry but failed to load: {}. Falling back to ModelConstants.",
                        modelId, e.getMessage());
                // Fall through to ModelConstants
            }
        }

        // FALLBACK: Use ModelConstants for hard-coded model descriptors
        LOGGER.debug("Cross-encoder {} not found in registry or failed to load, using ModelConstants", modelId);
        ModelDescriptor modelDescriptor = ModelConstants.getCrossEncoderModelDescriptor(modelId);
        ModelDescriptor vocabDescriptor = ModelConstants.getCrossEncoderVocabDescriptor(modelId);

        if (modelDescriptor == null) {
            throw new IOException("No cross-encoder model descriptor found for model ID: " + modelId);
        }
        if (vocabDescriptor == null) {
            throw new IOException("No cross-encoder vocabulary descriptor found for model ID: " + modelId);
        }

        // Ensure both model and vocabulary are downloaded
        Path modelPath = ensureModelAvailable(modelDescriptor);
        Path vocabPath = ensureModelAvailable(vocabDescriptor);
        TokenizerConfig tokenizerConfig = TokenizerConfig.fromMetadata(modelDescriptor.getMetadata());

        LOGGER.info("Cross-encoder model bundle ready for {}: model={}, vocab={}", modelId, modelPath, vocabPath);

        return new CrossEncoderBundle(modelId, modelPath, vocabPath, modelDescriptor.getMetadata(), tokenizerConfig);
    }

    /**
     * Checks if a cross-encoder model is cached locally.
     *
     * @param modelId The cross-encoder model identifier
     * @return true if the model is already cached
     */
    public boolean isCrossEncoderModelCached(String modelId) {
        // First check registry
        Optional<RegistryModelEntry> registryEntry = findActiveCrossEncoderInRegistry(modelId);
        if (registryEntry.isPresent()) {
            Path modelDir = baseCachePath.resolve(registryEntry.get().path);
            Path modelPath = modelDir.resolve(registryEntry.get().modelFile);
            return Files.exists(modelPath);
        }

        // Fall back to ModelConstants
        ModelDescriptor descriptor = ModelConstants.getCrossEncoderModelDescriptor(modelId);
        if (descriptor == null) {
            return false;
        }
        Path modelPathInCache = baseCachePath.resolve(descriptor.getExpectedCacheSubpath());
        return Files.exists(modelPathInCache);
    }

    /**
     * Gets the cached path for a cross-encoder model without downloading.
     *
     * @param modelId The cross-encoder model identifier
     * @return The path to the cached model, or null if not cached
     */
    public Path getCrossEncoderModelPath(String modelId) {
        // First check registry
        Optional<RegistryModelEntry> registryEntry = findActiveCrossEncoderInRegistry(modelId);
        if (registryEntry.isPresent()) {
            Path modelDir = baseCachePath.resolve(registryEntry.get().path);
            Path modelPath = modelDir.resolve(registryEntry.get().modelFile);
            return Files.exists(modelPath) ? modelPath : null;
        }

        // Fall back to ModelConstants
        ModelDescriptor descriptor = ModelConstants.getCrossEncoderModelDescriptor(modelId);
        if (descriptor == null) {
            return null;
        }
        Path modelPathInCache = baseCachePath.resolve(descriptor.getExpectedCacheSubpath());
        return Files.exists(modelPathInCache) ? modelPathInCache : null;
    }

    // ==================== OCR Model Support ====================

    /**
     * Look up an ACTIVE OCR model in the local registry.
     * Returns empty if not found or not active.
     *
     * @param modelId The OCR model identifier
     * @param expectedType Optional expected type (e.g., "ocr_detection")
     */
    private Optional<RegistryModelEntry> findActiveOcrModelInRegistry(String modelId, String expectedType) {
        JsonNode registry = loadLocalRegistry();
        if (registry == null) {
            return Optional.empty();
        }

        JsonNode models = registry.get("models");
        if (models == null || !models.has(modelId)) {
            return Optional.empty();
        }

        JsonNode model = models.get(modelId);
        String status = model.has("status") ? model.get("status").asText() : "";
        String type = model.has("type") ? model.get("type").asText() : "";

        if (!"active".equalsIgnoreCase(status)) {
            LOGGER.debug("OCR model {} found in registry but status is '{}', not 'active'", modelId, status);
            return Optional.empty();
        }

        // Check for OCR type
        if (expectedType != null && !type.equals(expectedType)) {
            LOGGER.debug("OCR model {} found in registry but type is '{}', expected '{}'", modelId, type, expectedType);
            return Optional.empty();
        }

        // Check if it's an OCR type
        if (!type.startsWith("ocr_") && !type.equals("layout_model") && !type.equals("document_classifier")) {
            return Optional.empty();
        }

        return Optional.of(parseModelEntry(modelId, model));
    }

    /**
     * Gets a model file for OCR models. First checks registry, then ModelConstants.
     *
     * @param modelId The OCR model identifier
     * @return The path to the model file
     * @throws IOException if model is not found or not accessible
     */
    public File getModelFile(String modelId) throws IOException {
        // Check registry first
        Optional<RegistryModelEntry> registryEntry = findActiveOcrModelInRegistry(modelId, null);
        if (registryEntry.isPresent()) {
            Path modelDir = baseCachePath.resolve(registryEntry.get().path);
            Path modelPath = modelDir.resolve(registryEntry.get().modelFile);
            if (Files.exists(modelPath)) {
                LOGGER.info("Using OCR model {} from registry: {}", modelId, modelPath);
                return modelPath.toFile();
            }
        }

        // Fall back to ModelConstants (if OCR descriptors are defined)
        ModelDescriptor descriptor = ModelConstants.getOcrModelDescriptor(modelId);
        if (descriptor != null) {
            Path modelPath = ensureModelAvailable(descriptor);
            return modelPath.toFile();
        }

        throw new IOException("No OCR model found for ID: " + modelId);
    }

    /**
     * Gets a vocabulary file for OCR models. First checks registry, then ModelConstants.
     *
     * @param modelId The OCR model identifier
     * @return The vocabulary file, or null if not found
     */
    public File getVocabularyFile(String modelId) {
        try {
            // Check registry first
            Optional<RegistryModelEntry> registryEntry = findActiveOcrModelInRegistry(modelId, null);
            if (registryEntry.isPresent() && registryEntry.get().vocabFile != null) {
                Path modelDir = baseCachePath.resolve(registryEntry.get().path);
                Path vocabPath = modelDir.resolve(registryEntry.get().vocabFile);
                if (Files.exists(vocabPath)) {
                    LOGGER.info("Using OCR vocabulary {} from registry: {}", modelId, vocabPath);
                    return vocabPath.toFile();
                }
            }

            // Fall back to ModelConstants
            ModelDescriptor vocabDescriptor = ModelConstants.getOcrVocabDescriptor(modelId);
            if (vocabDescriptor != null) {
                Path vocabPath = ensureModelAvailable(vocabDescriptor);
                return vocabPath.toFile();
            }
        } catch (IOException e) {
            LOGGER.warn("Could not retrieve vocabulary file for {}: {}", modelId, e.getMessage());
        }

        return null;
    }

    /**
     * Ensures an OCR model is available.
     * Checks registry first, then falls back to ModelConstants.
     *
     * @param modelId The OCR model identifier
     * @param modelType Optional expected model type (e.g., "ocr_detection")
     * @return OcrModelBundle containing paths and metadata
     * @throws IOException if model cannot be found or loaded
     */
    public OcrModelBundle ensureOcrModelAvailable(String modelId, String modelType) throws IOException {
        // FIRST: Check the local registry
        Optional<RegistryModelEntry> registryEntry = findActiveOcrModelInRegistry(modelId, modelType);
        if (registryEntry.isPresent()) {
            try {
                return buildOcrBundleFromRegistry(registryEntry.get());
            } catch (IOException e) {
                LOGGER.warn("OCR model {} found in registry but failed to load: {}. Falling back.",
                        modelId, e.getMessage());
            }
        }

        // FALLBACK: Use ModelConstants for hard-coded descriptors
        LOGGER.debug("OCR model {} not found in registry, checking ModelConstants", modelId);
        ModelDescriptor descriptor = ModelConstants.getOcrModelDescriptor(modelId);
        if (descriptor == null) {
            throw new IOException("No OCR model descriptor found for: " + modelId);
        }

        Path modelPath = ensureModelAvailable(descriptor);
        Path vocabPath = null;
        ModelDescriptor vocabDescriptor = ModelConstants.getOcrVocabDescriptor(modelId);
        if (vocabDescriptor != null) {
            vocabPath = ensureModelAvailable(vocabDescriptor);
        }

        return new OcrModelBundle(modelId, modelPath, vocabPath, descriptor.getMetadata());
    }

    /**
     * Build an OcrModelBundle from a registry entry.
     */
    private OcrModelBundle buildOcrBundleFromRegistry(RegistryModelEntry entry) throws IOException {
        Path modelDir = baseCachePath.resolve(entry.path);
        Path modelPath = modelDir.resolve(entry.modelFile);
        Path vocabPath = entry.vocabFile != null ? modelDir.resolve(entry.vocabFile) : null;

        if (!Files.exists(modelPath)) {
            throw new IOException("OCR model file not found: " + modelPath);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("model_type", entry.type);
        if (entry.embeddingDim != null) metadata.put("embedding_dim", entry.embeddingDim);
        if (entry.supportedLanguages != null && !entry.supportedLanguages.isEmpty()) {
            metadata.put("supported_languages", List.copyOf(entry.supportedLanguages));
        }
        metadata.put("optimized", entry.optimized);

        LOGGER.info("Loading OCR model {} from registry: {}", entry.modelId, modelPath);

        return new OcrModelBundle(entry.modelId, modelPath, vocabPath, metadata);
    }

    /**
     * Container for OCR model bundle.
     */
    public static class OcrModelBundle {
        private final String modelId;
        private final Path modelPath;
        private final Path vocabularyPath;
        private final Map<String, Object> metadata;

        public OcrModelBundle(String modelId, Path modelPath, Path vocabularyPath,
                              Map<String, Object> metadata) {
            this.modelId = modelId;
            this.modelPath = modelPath;
            this.vocabularyPath = vocabularyPath;
            this.metadata = metadata;
        }

        public String getModelId() { return modelId; }
        public Path getModelPath() { return modelPath; }
        public Path getVocabularyPath() { return vocabularyPath; }
        public Map<String, Object> getMetadata() { return metadata; }

        public String getModelType() {
            return metadata != null ? (String) metadata.get("model_type") : null;
        }

        public Boolean isOptimized() {
            Object val = metadata != null ? metadata.get("optimized") : null;
            return val instanceof Boolean ? (Boolean) val : false;
        }

        @Override
        public String toString() {
            return "OcrModelBundle{" +
                    "modelId='" + modelId + '\'' +
                    ", modelPath=" + modelPath +
                    ", vocabularyPath=" + vocabularyPath +
                    ", modelType=" + getModelType() +
                    '}';
        }
    }

    /**
     * Container for cross-encoder model bundle (SameDiff format with vocabulary)
     */
    public static class CrossEncoderBundle {
        private final String modelId;
        private final Path modelPath;
        private final Path vocabularyPath;
        private final Map<String, Object> metadata;
        private final TokenizerConfig tokenizerConfig;

        public CrossEncoderBundle(String modelId, Path modelPath, Path vocabularyPath, Map<String, Object> metadata, TokenizerConfig tokenizerConfig) {
            this.modelId = modelId;
            this.modelPath = modelPath;
            this.vocabularyPath = vocabularyPath;
            this.metadata = metadata;
            this.tokenizerConfig = tokenizerConfig;
        }

        public String getModelId() { return modelId; }
        public Path getModelPath() { return modelPath; }
        public Path getVocabularyPath() { return vocabularyPath; }
        public Map<String, Object> getMetadata() { return metadata; }
        public TokenizerConfig getTokenizerConfig() { return tokenizerConfig; }

        public String getDescription() {
            return metadata != null ? (String) metadata.get("description") : null;
        }

        public Integer getHiddenSize() {
            Object val = metadata != null ? metadata.get("hidden_size") : null;
            return val instanceof Integer ? (Integer) val : null;
        }

        public Integer getNumLayers() {
            Object val = metadata != null ? metadata.get("num_layers") : null;
            return val instanceof Integer ? (Integer) val : null;
        }

        public Integer getMaxSequenceLength() {
            Object val = metadata != null ? metadata.get("max_sequence_length") : null;
            return val instanceof Integer ? (Integer) val : null;
        }

        public String getFramework() {
            return metadata != null ? (String) metadata.get("framework") : null;
        }

        public String getHuggingFaceSource() {
            return metadata != null ? (String) metadata.get("huggingface_source") : null;
        }

        public String getInputFormat() {
            return metadata != null ? (String) metadata.get("input_format") : null;
        }

        public String getOutputType() {
            return metadata != null ? (String) metadata.get("output_type") : null;
        }

        @Override
        public String toString() {
            return "CrossEncoderBundle{" +
                    "modelId='" + modelId + '\'' +
                    ", modelPath=" + modelPath +
                    ", vocabularyPath=" + vocabularyPath +
                    ", framework=" + getFramework() +
                    ", hiddenSize=" + getHiddenSize() +
                    ", numLayers=" + getNumLayers() +
                    '}';
        }
    }

    /**
     * Container for model bundle containing model and vocabulary paths
     */
    public static class ModelBundle {
        private final String modelId;
        private final Path modelPath;
        private final Path vocabularyPath;
        private final Map<String, Object> metadata;
        private TokenizerConfig tokenizerConfig;

        public ModelBundle(String modelId, Path modelPath, Path vocabularyPath, Map<String, Object> metadata,TokenizerConfig tokenizerConfig) {
            this.modelId = modelId;
            this.modelPath = modelPath;
            this.vocabularyPath = vocabularyPath;
            this.metadata = metadata;
            this.tokenizerConfig = tokenizerConfig;
        }
        
        public String getModelId() { return modelId; }
        public Path getModelPath() { return modelPath; }
        public Path getVocabularyPath() { return vocabularyPath; }
        public Map<String, Object> getMetadata() { return metadata; }

        public TokenizerConfig getTokenizerConfig() {
            return tokenizerConfig;
        }

        @Override
        public String toString() {
            return "ModelBundle{" +
                    "modelId='" + modelId + '\'' +
                    ", modelPath=" + modelPath +
                    ", vocabularyPath=" + vocabularyPath +
                    ", metadata=" + metadata +
                    '}';
        }
    }

    private String calculateSha256(Path path) throws IOException {
        return HashUtils.sha256Hex(path);
    }

    private void extractTarGz(Path tarGzPath, Path destinationDir) throws IOException {
        LOGGER.info("Extracting TAR.GZ file: {} to {}", tarGzPath, destinationDir);
        try (InputStream fi = Files.newInputStream(tarGzPath);
             BufferedInputStream bi = new BufferedInputStream(fi);
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                Path newPath = destinationDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent()); // Ensure parent dir exists
                    Files.copy(ti, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        LOGGER.info("Finished extracting TAR.GZ file: {}", tarGzPath);
    }

    // ==================== VLM Model Support ====================

    /**
     * Look up an ACTIVE VLM model in the local registry.
     * Returns empty if not found or not active.
     *
     * @param modelId The VLM model identifier
     */
    private Optional<RegistryModelEntry> findActiveVlmModelInRegistry(String modelId) {
        JsonNode registry = loadLocalRegistry();
        if (registry == null) {
            return Optional.empty();
        }

        JsonNode models = registry.get("models");
        if (models == null) {
            return Optional.empty();
        }

        // Try exact match first, then with -pipeline suffix
        JsonNode model = models.get(modelId);
        String matchedId = modelId;
        if (model == null) {
            model = models.get(modelId + "-pipeline");
            matchedId = modelId + "-pipeline";
        }
        if (model == null) {
            // Scan all models for a VLM type matching the base model ID
            var fields = models.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                String entryType = val.has("type") ? val.get("type").asText() : "";
                if (isVlmType(entryType) && (key.equals(modelId) || key.startsWith(modelId))) {
                    model = val;
                    matchedId = key;
                    break;
                }
            }
        }
        if (model == null) {
            return Optional.empty();
        }

        String status = model.has("status") ? model.get("status").asText() : "";
        String type = model.has("type") ? model.get("type").asText() : "";

        if (!"active".equalsIgnoreCase(status)) {
            LOGGER.debug("VLM model {} found in registry but status is '{}', not 'active'", matchedId, status);
            return Optional.empty();
        }

        if (!isVlmType(type)) {
            LOGGER.debug("VLM model {} found in registry but type is '{}', not a VLM type", matchedId, type);
            return Optional.empty();
        }

        return Optional.of(parseModelEntry(matchedId, model));
    }

    private static boolean isVlmType(String type) {
        return type.equals("vlm_pipeline") || type.equals("vlm_model") || type.equals("vlm")
                || type.contains("vision_language");
    }

    /**
     * Ensures a VLM model is available in the cache.
     * First checks the local registry, then falls back to ModelConstants.
     *
     * VLM models must be in SameDiff (.sdz) format. They should be imported
     * from HuggingFace ONNX models using samediff-import-onnx.
     *
     * @param modelId The VLM model identifier (e.g., "smoldocling-256m")
     * @return VlmModelBundle containing the model directory path and metadata
     * @throws IOException if the model cannot be found or loaded
     */
    public VlmModelBundle ensureVlmModelAvailable(String modelId) throws IOException {
        // Normalize model ID
        String normalizedId = modelId.toLowerCase().replace("_", "-");

        // FIRST: Check the local registry for an active VLM model
        Optional<RegistryModelEntry> registryEntry = findActiveVlmModelInRegistry(normalizedId);
        if (registryEntry.isPresent()) {
            try {
                return buildVlmBundleFromRegistry(registryEntry.get());
            } catch (IOException e) {
                LOGGER.warn("VLM model {} found in registry but failed to load: {}. Falling back.",
                        modelId, e.getMessage());
            }
        }

        // FALLBACK: Use ModelConstants for VLM model descriptors
        LOGGER.debug("VLM model {} not found in registry, checking ModelConstants", modelId);
        ModelConstants.VlmModelDescriptor descriptor = ModelConstants.getVlmModelDescriptor(normalizedId);
        if (descriptor == null) {
            throw new IOException("No VLM model descriptor found for: " + modelId +
                    ". Available models: " + ModelConstants.getAvailableVlmModelIds());
        }

        // Get the model directory path
        Path modelDir = baseCachePath.resolve(descriptor.getCacheSubpath());

        // Check if model is already downloaded (SDZ format)
        if (!isVlmModelCached(normalizedId)) {
            LOGGER.info("VLM model {} not cached. Model needs to be imported to SDZ format.", modelId);
            LOGGER.info("Source: {} - Import using samediff-import-onnx to: {}",
                    descriptor.getHuggingFaceUrl(), modelDir.resolve(descriptor.getModelFile()));
        }

        return new VlmModelBundle(
                normalizedId,
                descriptor.getDisplayName(),
                modelDir,
                descriptor.getMetadata()
        );
    }

    /**
     * Build a VlmModelBundle from a registry entry.
     */
    private VlmModelBundle buildVlmBundleFromRegistry(RegistryModelEntry entry) throws IOException {
        Path modelDir = baseCachePath.resolve(entry.path);

        if (!Files.exists(modelDir)) {
            throw new IOException("VLM model directory not found: " + modelDir);
        }

        Map<String, Object> metadata = new HashMap<>();
        if (entry.embeddingDim != null) metadata.put("hidden_size", entry.embeddingDim);
        metadata.put("optimized", entry.optimized);
        metadata.put("model_file", entry.modelFile);

        LOGGER.info("Loading VLM model {} from registry: {}", entry.modelId, modelDir);

        return new VlmModelBundle(entry.modelId, entry.modelId, modelDir, metadata);
    }

    /**
     * Checks if a VLM model is cached locally (SDZ format).
     *
     * @param modelId The VLM model identifier
     * @return true if the model SDZ file is cached
     */
    public boolean isVlmModelCached(String modelId) {
        String normalizedId = modelId.toLowerCase().replace("_", "-");

        // Check registry first
        Optional<RegistryModelEntry> registryEntry = findActiveVlmModelInRegistry(normalizedId);
        if (registryEntry.isPresent()) {
            Path modelDir = baseCachePath.resolve(registryEntry.get().path);
            Path modelFile = modelDir.resolve(registryEntry.get().modelFile);
            return Files.exists(modelFile);
        }

        // Check ModelConstants
        ModelConstants.VlmModelDescriptor descriptor = ModelConstants.getVlmModelDescriptor(normalizedId);
        if (descriptor == null) {
            return false;
        }

        Path modelDir = baseCachePath.resolve(descriptor.getCacheSubpath());
        if (!Files.exists(modelDir)) {
            return false;
        }

        // Check for SDZ model file
        Path modelFile = modelDir.resolve(descriptor.getModelFile());
        return Files.exists(modelFile);
    }

    /**
     * Gets the cached path for a VLM model without downloading.
     *
     * @param modelId The VLM model identifier
     * @return The path to the cached model directory, or null if not cached
     */
    public Path getVlmModelPath(String modelId) {
        String normalizedId = modelId.toLowerCase().replace("_", "-");

        // Check registry first
        Optional<RegistryModelEntry> registryEntry = findActiveVlmModelInRegistry(normalizedId);
        if (registryEntry.isPresent()) {
            Path modelDir = baseCachePath.resolve(registryEntry.get().path);
            return Files.exists(modelDir) ? modelDir : null;
        }

        // Check ModelConstants
        ModelConstants.VlmModelDescriptor descriptor = ModelConstants.getVlmModelDescriptor(normalizedId);
        if (descriptor == null) {
            return null;
        }

        Path modelDir = baseCachePath.resolve(descriptor.getCacheSubpath());
        return Files.exists(modelDir) ? modelDir : null;
    }

    /**
     * Container for VLM model bundle.
     * VLM models must be in SameDiff (.sdz) format. Use samediff-import-onnx
     * to convert HuggingFace ONNX models to SDZ format.
     */
    public static class VlmModelBundle {
        private final String modelId;
        private final String displayName;
        private final Path modelDirectory;
        private final Map<String, Object> metadata;

        public VlmModelBundle(String modelId, String displayName, Path modelDirectory,
                              Map<String, Object> metadata) {
            this.modelId = modelId;
            this.displayName = displayName;
            this.modelDirectory = modelDirectory;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }

        public String getModelId() { return modelId; }
        public String getDisplayName() { return displayName; }
        public Path getModelDirectory() { return modelDirectory; }
        public Map<String, Object> getMetadata() { return metadata; }

        /**
         * Gets the SDZ model file name.
         */
        public String getModelFile() {
            return (String) metadata.getOrDefault("model_file", modelId + ".sdz");
        }

        /**
         * Gets the path to the SDZ model file.
         */
        public Path getModelFilePath() {
            return modelDirectory.resolve(getModelFile());
        }

        /**
         * Gets the File reference for the model directory.
         */
        public File getModelDirectoryFile() {
            return modelDirectory.toFile();
        }

        /**
         * Gets the File reference for the SDZ model file.
         */
        public File getModelFileFile() {
            return getModelFilePath().toFile();
        }

        /**
         * Check if the model directory exists.
         */
        public boolean exists() {
            return Files.exists(modelDirectory) && Files.isDirectory(modelDirectory);
        }

        /**
         * Check if the SDZ model file exists.
         */
        public boolean modelFileExists() {
            return Files.exists(getModelFilePath());
        }

        public String getDescription() {
            return (String) metadata.get("description");
        }

        public Integer getMaxNewTokens() {
            Object val = metadata.get("max_new_tokens");
            return val instanceof Integer ? (Integer) val : 4096;
        }

        public Integer getMaxImageSize() {
            Object val = metadata.get("max_image_size");
            return val instanceof Integer ? (Integer) val : 1024;
        }

        public String getFramework() {
            return (String) metadata.getOrDefault("framework", "samediff");
        }

        public String getVocabFile() {
            return (String) metadata.getOrDefault("vocab_file", "vocab.txt");
        }

        public Path getVocabFilePath() {
            return modelDirectory.resolve(getVocabFile());
        }

        public String getTokenizerConfigFile() {
            return (String) metadata.getOrDefault("tokenizer_config", "tokenizer_config.json");
        }

        public Path getTokenizerConfigPath() {
            return modelDirectory.resolve(getTokenizerConfigFile());
        }

        @Override
        public String toString() {
            return "VlmModelBundle{" +
                    "modelId='" + modelId + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", modelDirectory=" + modelDirectory +
                    ", modelFile='" + getModelFile() + '\'' +
                    ", exists=" + modelFileExists() +
                    '}';
        }
    }

    // ==================== Pipeline Model Support ====================

    /**
     * Container for pipeline-based model bundle.
     * Supports models loaded via samediff-pipeline from HuggingFace formats
     * (SafeTensors, GGUF, ONNX).
     */
    public static class PipelineModelBundle {
        private final String modelId;
        private final String displayName;
        private final Path modelDirectory;
        private final Map<String, Object> metadata;
        private final String format;

        public PipelineModelBundle(String modelId, String displayName, Path modelDirectory,
                                   Map<String, Object> metadata, String format) {
            this.modelId = modelId;
            this.displayName = displayName;
            this.modelDirectory = modelDirectory;
            this.metadata = metadata != null ? metadata : new HashMap<>();
            this.format = format;
        }

        public String getModelId() { return modelId; }
        public String getDisplayName() { return displayName; }
        public Path getModelDirectory() { return modelDirectory; }
        public Map<String, Object> getMetadata() { return metadata; }
        public String getFormat() { return format; }

        public File getModelDirectoryFile() {
            return modelDirectory.toFile();
        }

        public boolean exists() {
            return Files.exists(modelDirectory) && Files.isDirectory(modelDirectory);
        }

        public boolean hasConfigJson() {
            return Files.exists(modelDirectory.resolve("config.json"));
        }

        public boolean hasTokenizerJson() {
            return Files.exists(modelDirectory.resolve("tokenizer.json")) ||
                   Files.exists(modelDirectory.resolve("tokenizer_config.json"));
        }

        public String getArchitecture() {
            return (String) metadata.get("architecture");
        }

        public boolean isSharded() {
            Object val = metadata.get("sharded");
            return val instanceof Boolean && (Boolean) val;
        }

        @Override
        public String toString() {
            return "PipelineModelBundle{" +
                    "modelId='" + modelId + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", modelDirectory=" + modelDirectory +
                    ", format='" + format + '\'' +
                    ", exists=" + exists() +
                    '}';
        }
    }

    /**
     * Download a pipeline model from HuggingFace Hub.
     * Downloads all model files (config.json, tokenizer, weights) into the local cache directory.
     *
     * @param huggingFaceRepoId The HuggingFace repository ID (e.g., "Qwen/Qwen3-0.6B")
     * @param revision           Git revision or branch name (null/empty for "main")
     * @param hfToken            HuggingFace API token for private models (may be null)
     * @param progressConsumer   Receives progress messages during download (may be null)
     * @return Path to the downloaded model directory
     * @throws IOException if the download fails
     */
    public Path downloadPipelineModel(String huggingFaceRepoId, String revision, String hfToken,
                                      Consumer<String> progressConsumer)
            throws IOException {
        String effectiveRevision = (revision != null && !revision.isBlank()) ? revision : "main";
        Path modelDir = getPipelineModelDirectory(huggingFaceRepoId);
        Files.createDirectories(modelDir);

        // Build the HuggingFace Hub URL base
        String baseUrl = "https://huggingface.co/" + huggingFaceRepoId + "/resolve/" + effectiveRevision;

        // Files to try downloading (standard HuggingFace repo layout)
        String[] candidateFiles = {
            "config.json",
            "tokenizer.json",
            "tokenizer_config.json",
            "special_tokens_map.json",
            "model.safetensors",
            "pytorch_model.bin",
            "model.gguf",
            "model.onnx"
        };

        boolean anyDownloaded = false;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        for (String fileName : candidateFiles) {
            String fileUrl = baseUrl + "/" + fileName;
            Path destPath = modelDir.resolve(fileName);

            if (Files.exists(destPath)) {
                if (progressConsumer != null) progressConsumer.accept("Cached: " + fileName);
                anyDownloaded = true;
                continue;
            }

            try {
                var requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(fileUrl))
                        .timeout(Duration.ofMinutes(30))
                        .GET();
                if (hfToken != null && !hfToken.isBlank()) {
                    requestBuilder.header("Authorization", "Bearer " + hfToken);
                }

                var response = httpClient.send(requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200) {
                    if (progressConsumer != null) progressConsumer.accept("Downloading: " + fileName);
                    try (var in = response.body()) {
                        Files.copy(in, destPath,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    anyDownloaded = true;
                    if (progressConsumer != null) progressConsumer.accept("Downloaded: " + fileName);
                }
                // 404/401 = file doesn't exist in this repo, skip silently
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            } catch (Exception e) {
                LOGGER.debug("Could not download {}: {}", fileUrl, e.getMessage());
            }
        }

        if (!anyDownloaded) {
            throw new IOException("Could not download any files for model: " + huggingFaceRepoId
                    + ". Check the model ID and ensure the model exists on HuggingFace Hub.");
        }

        if (progressConsumer != null) {
            progressConsumer.accept("Model saved to: " + modelDir);
        }
        return modelDir;
    }

    /**
     * Get the directory path for a HuggingFace model.
     * This is where downloaded model files will be stored.
     *
     * @param huggingFaceRepoId The HuggingFace repository ID (e.g., "BAAI/bge-base-en-v1.5")
     * @return Path to the model directory
     */
    public Path getPipelineModelDirectory(String huggingFaceRepoId) {
        String safeName = huggingFaceRepoId.replace("/", "_").replace("\\", "_");
        return baseCachePath.resolve("pipelines").resolve(safeName);
    }

    /**
     * Check if a pipeline model is cached locally.
     *
     * @param huggingFaceRepoId The HuggingFace repository ID
     * @return true if the model directory exists and contains a config.json
     */
    public boolean isPipelineModelCached(String huggingFaceRepoId) {
        Path modelDir = getPipelineModelDirectory(huggingFaceRepoId);
        if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
            return false;
        }

        // Must have config.json
        if (!Files.exists(modelDir.resolve("config.json"))) {
            return false;
        }

        // Check for weight files
        try (var modelFiles = Files.list(modelDir)) {
            return modelFiles
                    .anyMatch(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".safetensors") ||
                               name.endsWith(".gguf") ||
                               name.endsWith(".onnx") ||
                               name.endsWith(".sdz") ||
                               name.endsWith(".bin");
                    });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get a cached pipeline model bundle without downloading.
     *
     * @param huggingFaceRepoId The HuggingFace repository ID
     * @return PipelineModelBundle if cached, null otherwise
     */
    public PipelineModelBundle getCachedPipelineModel(String huggingFaceRepoId) {
        Path modelDir = getPipelineModelDirectory(huggingFaceRepoId);
        if (!isPipelineModelCached(huggingFaceRepoId)) {
            return null;
        }

        // Detect format from files present
        String format = detectCachedModelFormat(modelDir);

        Map<String, Object> metadata = loadModelMetadata(modelDir);

        return new PipelineModelBundle(
                huggingFaceRepoId,
                huggingFaceRepoId,
                modelDir,
                metadata,
                format
        );
    }

    /**
     * Detect the model format from cached files.
     */
    private String detectCachedModelFormat(Path modelDir) {
        try (var dirFiles = Files.list(modelDir)) {
            for (Path p : (Iterable<Path>) dirFiles::iterator) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.endsWith(".safetensors")) return "SAFETENSORS";
                if (name.endsWith(".gguf")) return "GGUF";
                if (name.endsWith(".onnx")) return "ONNX";
                if (name.endsWith(".sdz")) return "SAMEDIFF";
                if (name.endsWith(".bin")) return "PYTORCH";
            }
        } catch (IOException e) {
            LOGGER.debug("Error detecting model format: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    /**
     * Load model metadata from config.json if present.
     */
    private Map<String, Object> loadModelMetadata(Path modelDir) {
        Map<String, Object> metadata = new HashMap<>();
        Path configPath = modelDir.resolve("config.json");
        if (Files.exists(configPath)) {
            try {
                JsonNode config = objectMapper.readTree(configPath.toFile());

                if (config.has("model_type")) {
                    metadata.put("model_type", config.get("model_type").asText());
                }
                if (config.has("architectures") && config.get("architectures").isArray()) {
                    metadata.put("architecture", config.get("architectures").get(0).asText());
                }
                if (config.has("hidden_size")) {
                    metadata.put("hidden_size", config.get("hidden_size").asInt());
                }
                if (config.has("num_hidden_layers")) {
                    metadata.put("num_layers", config.get("num_hidden_layers").asInt());
                }
                if (config.has("max_position_embeddings")) {
                    metadata.put("max_sequence_length", config.get("max_position_embeddings").asInt());
                }

                // Check if sharded
                Path indexPath = modelDir.resolve("model.safetensors.index.json");
                if (Files.exists(indexPath)) {
                    metadata.put("sharded", true);
                }
            } catch (IOException e) {
                LOGGER.debug("Error reading config.json: {}", e.getMessage());
            }
        }
        return metadata;
    }

    /**
     * List all cached pipeline models.
     *
     * @return List of model IDs that are cached
     */
    public List<String> listCachedPipelineModels() {
        List<String> models = new ArrayList<>();
        Path pipelinesDir = baseCachePath.resolve("pipelines");
        if (!Files.exists(pipelinesDir)) {
            return models;
        }

        try (Stream<Path> stream = Files.list(pipelinesDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> isPipelineModelCached(p.getFileName().toString().replace("_", "/")))
                  .forEach(p -> models.add(p.getFileName().toString().replace("_", "/")));
        } catch (IOException e) {
            LOGGER.warn("Error listing cached pipeline models: {}", e.getMessage());
        }

        return models;
    }

    /**
     * Delete a cached pipeline model.
     *
     * @param huggingFaceRepoId The HuggingFace repository ID
     * @return true if deleted, false if not found
     */
    public boolean deleteCachedPipelineModel(String huggingFaceRepoId) {
        Path modelDir = getPipelineModelDirectory(huggingFaceRepoId);
        if (!Files.exists(modelDir)) {
            return false;
        }

        try (var walkStream = Files.walk(modelDir)) {
            walkStream
                 .sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         LOGGER.warn("Failed to delete: {}", path);
                     }
                 });
            return true;
        } catch (IOException e) {
            LOGGER.error("Error deleting cached model {}: {}", huggingFaceRepoId, e.getMessage());
            return false;
        }
    }

    // ==================== SDK Support ====================

    /**
     * Downloads an SDX SDK artifact for a specific platform classifier.
     * Downloaded to ~/.kompile/models/sdx-sdk/{sdkId}/{version}/{platform}/{variant}/
     *
     * @param sdkDescriptor The SDK descriptor
     * @param platformClassifier The platform classifier (e.g., "ios-arm64", "android-arm64-nnapi")
     * @return Path to the downloaded artifact
     * @throws IOException if download fails
     */
    public Path downloadSdk(SdkDescriptor sdkDescriptor, String platformClassifier) throws IOException {
        if ("sdx-runtime".equals(sdkDescriptor.getSdkId()) && sdkDescriptor.getPlatformArtifacts().isEmpty()) {
            SdxSdkManifest manifest = loadSdxManifest(sdkDescriptor.getVersion(), sdkDescriptor.getBaseDownloadUrl());
            return downloadManifestArtifact(sdkDescriptor.getSdkId(), manifest,
                    manifest.selectClassifier("runtime", "platform-sdk", platformClassifier),
                    releaseBaseUrl(sdkDescriptor.getVersion(), sdkDescriptor.getBaseDownloadUrl()));
        }
        SdkDescriptor.PlatformArtifact artifact = sdkDescriptor.getArtifact(platformClassifier);
        if (artifact == null) {
            throw new IOException("No SDK artifact found for platform: " + platformClassifier +
                    ". Available platforms: " + sdkDescriptor.getPlatformArtifacts().keySet());
        }

        requireSafeCacheSegment(sdkDescriptor.getSdkId(), "sdkId");
        requireSafeCacheSegment(sdkDescriptor.getVersion(), "version");
        requireSafeCacheSegment(platformClassifier, "platformClassifier");
        Path sdkDir = baseCachePath.resolve("sdx-sdk")
                .resolve(sdkDescriptor.getSdkId())
                .resolve(sdkDescriptor.getVersion())
                .resolve(platformClassifier);
        Path artifactPath = sdkDir.resolve(artifact.getArtifactFileName());

        if (Files.exists(artifactPath)) {
            LOGGER.info("SDK artifact already cached: {}", artifactPath);
            return artifactPath;
        }

        Files.createDirectories(sdkDir);
        LOGGER.info("Downloading SDK artifact {} from {}", artifact.getArtifactFileName(), artifact.getDownloadUrl());

        Path tempPath = Files.createTempFile(sdkDir, "sdk-download-", ".tmp");
        try {
            URL url = new URL(artifact.getDownloadUrl());
            try (InputStream in = url.openStream();
                 ReadableByteChannel rbc = Channels.newChannel(in);
                 FileOutputStream fos = new FileOutputStream(tempPath.toFile())) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }

            if (artifact.getChecksum() != null && !artifact.getChecksum().isEmpty()) {
                String actualChecksum = calculateSha256(tempPath);
                String expectedChecksum = artifact.getChecksum().startsWith("sha256:")
                        ? artifact.getChecksum().substring(7) : artifact.getChecksum();
                if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                    Files.deleteIfExists(tempPath);
                    throw new IOException("Checksum mismatch for SDK artifact " + artifact.getArtifactFileName() +
                            ". Expected " + expectedChecksum + ", got " + actualChecksum);
                }
                LOGGER.info("Checksum verified for SDK artifact {}", artifact.getArtifactFileName());
            }

            Files.move(tempPath, artifactPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("SDK artifact downloaded to: {}", artifactPath);
        } finally {
            Files.deleteIfExists(tempPath);
        }

        return artifactPath;
    }

    /**
     * Resolves and downloads one canonical SDX SDK artifact from the release manifest.
     * The manifest, not the consumer, owns the artifact filename.
     */
    public record ResolvedSdxSdkArtifact(Path path, SdxSdkManifest.Artifact artifact) {}

    public Path downloadSdxSdk(String version, String component, String packageRole,
                               String platform, String variant, String baseUrl) throws IOException {
        return resolveAndDownloadSdxSdk(version, component, packageRole, platform, variant, baseUrl).path();
    }

    /** Resolves the exact manifest entry and returns it with the verified cached artifact. */
    public ResolvedSdxSdkArtifact resolveAndDownloadSdxSdk(String version, String component, String packageRole,
                                                           String platform, String variant, String baseUrl)
            throws IOException {
        if (version == null) version = SdkConstants.DEFAULT_SDX_SDK_VERSION;
        if (baseUrl == null) baseUrl = SdkConstants.resolveBaseUrl();
        String versionUrl = releaseBaseUrl(version, baseUrl);
        SdxSdkManifest manifest = loadSdxManifest(version, versionUrl);
        if (variant == null || variant.isBlank()) {
            variant = SdxSdkManifest.defaultVariant(manifest, component, packageRole, platform);
        }
        SdxSdkManifest.Artifact artifact = manifest.select(component, packageRole, platform, variant);
        Path path = downloadManifestArtifact(manifestSdkId(artifact.component()), manifest, artifact, versionUrl);
        return new ResolvedSdxSdkArtifact(path, artifact);
    }

    public SdxSdkManifest loadSdxManifest(String version, String baseUrl) throws IOException {
        requireSafeCacheSegment(version, "version");
        String versionUrl = releaseBaseUrl(version, baseUrl == null ? SdkConstants.resolveBaseUrl() : baseUrl);
        Path manifestDir = getSdxManifestCachePath(version, versionUrl).getParent();
        Path manifestPath = manifestDir.resolve(SdxSdkManifest.FILE_NAME);
        Path checksumPath = manifestDir.resolve(SDX_MANIFEST_CHECKSUM_NAME);
        boolean hasManifest = Files.exists(manifestPath);
        boolean hasChecksum = Files.exists(checksumPath);
        if (hasManifest != hasChecksum) {
            throw new IOException("Incomplete cached SDX manifest at " + manifestDir
                    + ": both manifest and checksum sidecar are required");
        }
        if (!hasManifest) {
            Files.createDirectories(manifestDir);
            Path tempManifest = Files.createTempFile(manifestDir, "manifest-download-", ".tmp");
            Path tempChecksum = Files.createTempFile(manifestDir, "manifest-checksum-download-", ".tmp");
            try {
                URI releaseUri = URI.create(versionUrl);
                downloadUrl(releaseUri.resolve(SDX_MANIFEST_CHECKSUM_NAME), tempChecksum,
                        MAX_SDX_CHECKSUM_BYTES);
                downloadUrl(releaseUri.resolve(SdxSdkManifest.FILE_NAME), tempManifest,
                        MAX_SDX_MANIFEST_BYTES);
                parseAndVerifyManifest(version, tempManifest, tempChecksum);
                Files.move(tempChecksum, checksumPath, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempManifest, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempManifest);
                Files.deleteIfExists(tempChecksum);
            }
        }
        return parseAndVerifyManifest(version, manifestPath, checksumPath);
    }

    /** Returns the source-qualified manifest cache path used for a release URL. */
    Path getSdxManifestCachePath(String version, String baseUrl) throws IOException {
        requireSafeCacheSegment(version, "version");
        String versionUrl = releaseBaseUrl(version, baseUrl == null ? SdkConstants.resolveBaseUrl() : baseUrl);
        return baseCachePath.resolve("sdx-sdk").resolve("sdx-runtime").resolve(version)
                .resolve("origins").resolve(sourceCacheKey(versionUrl)).resolve(SdxSdkManifest.FILE_NAME);
    }

    private SdxSdkManifest parseAndVerifyManifest(String version, Path manifestPath, Path checksumPath)
            throws IOException {
        if (!Files.isRegularFile(manifestPath) || Files.size(manifestPath) > MAX_SDX_MANIFEST_BYTES) {
            throw new IOException("Cached " + SdxSdkManifest.FILE_NAME + " is missing or exceeds the size limit");
        }
        if (!Files.isRegularFile(checksumPath) || Files.size(checksumPath) > MAX_SDX_CHECKSUM_BYTES) {
            throw new IOException("Cached " + SDX_MANIFEST_CHECKSUM_NAME + " is missing or exceeds the size limit");
        }
        String expected = readManifestChecksum(checksumPath);
        String actual = calculateSha256(manifestPath);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("Checksum mismatch for " + SdxSdkManifest.FILE_NAME
                    + ": expected " + expected + ", got " + actual);
        }
        try (InputStream input = Files.newInputStream(manifestPath)) {
            return SdxSdkManifest.parse(objectMapper, input, version);
        }
    }

    private static String readManifestChecksum(Path checksumPath) throws IOException {
        String sidecar = Files.readString(checksumPath);
        Matcher matcher = SDX_MANIFEST_CHECKSUM.matcher(sidecar);
        if (!matcher.matches()) {
            throw new IOException("Invalid " + SDX_MANIFEST_CHECKSUM_NAME
                    + ": expected '<64 hex>  " + SdxSdkManifest.FILE_NAME + "'");
        }
        return matcher.group(1).toLowerCase();
    }

    private Path downloadManifestArtifact(String sdkId, SdxSdkManifest manifest,
                                          SdxSdkManifest.Artifact artifact, String versionUrl) throws IOException {
        Path qualifiedDir = baseCachePath.resolve("sdx-sdk").resolve(sdkId)
                .resolve(manifest.releaseVersion()).resolve("origins").resolve(sourceCacheKey(versionUrl))
                .resolve(artifact.platform()).resolve(artifact.variant());
        Path qualifiedPath = qualifiedDir.resolve(artifact.fileName());
        if (validArtifact(qualifiedPath, artifact)) return qualifiedPath;
        Files.deleteIfExists(qualifiedPath);

        Files.createDirectories(qualifiedDir);
        Path temp = Files.createTempFile(qualifiedDir, "sdk-download-", ".tmp");
        try {
            downloadUrl(URI.create(versionUrl).resolve(artifact.fileName()), temp, artifact.size());
            verifyArtifact(temp, artifact);
            Files.move(temp, qualifiedPath, StandardCopyOption.REPLACE_EXISTING);
            return qualifiedPath;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private boolean validArtifact(Path path, SdxSdkManifest.Artifact artifact) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        try {
            verifyArtifact(path, artifact);
            return true;
        } catch (IOException invalid) {
            LOGGER.warn("Ignoring invalid cached SDK artifact {}: {}", path, invalid.getMessage());
            return false;
        }
    }

    private void verifyArtifact(Path path, SdxSdkManifest.Artifact artifact) throws IOException {
        long actualSize = Files.size(path);
        if (actualSize != artifact.size()) {
            throw new IOException("Size mismatch for SDK artifact " + artifact.fileName() +
                    ": expected " + artifact.size() + ", got " + actualSize);
        }
        String actual = calculateSha256(path);
        if (!artifact.sha256().equalsIgnoreCase(actual)) {
            throw new IOException("Checksum mismatch for SDK artifact " + artifact.fileName() +
                    ": expected " + artifact.sha256() + ", got " + actual);
        }
    }

    private static String releaseBaseUrl(String version, String baseUrl) throws IOException {
        requireSafeCacheSegment(version, "version");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("SDX SDK base URL must not be blank");
        }
        String value = baseUrl.trim();
        String tag = "sdk-v" + version;
        String suffix = "/" + tag + "/";
        if (value.endsWith("/" + tag)) {
            value += "/";
        } else if (!value.endsWith(suffix)) {
            value = (value.endsWith("/") ? value : value + "/") + tag + "/";
        }
        final URI uri;
        try {
            uri = URI.create(value).normalize();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid SDX SDK base URL", invalid);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("file")) || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IOException("SDX SDK base URL must be an absolute http, https, or file URL without query/fragment");
        }
        return uri.toString();
    }

    private static String sourceCacheKey(String versionUrl) {
        return HashUtils.sha256HexShort(versionUrl, 16);
    }

    private static String manifestSdkId(String component) throws IOException {
        return switch (component) {
            case "runtime" -> "sdx-runtime";
            case "aot" -> "sdx-aot";
            case "java" -> "sdx-java";
            default -> throw new IOException("Unsupported SDX manifest component: " + component);
        };
    }

    private static void requireSafeCacheSegment(String value, String name) throws IOException {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..") ||
                value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IOException(name + " is not a safe cache path segment");
        }
    }

    private static void downloadUrl(URI source, Path destination, long maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IOException("Download size limit must be positive");
        }
        String scheme = source.getScheme();
        if (scheme == null) {
            throw new IOException("Download URI has no scheme: " + source);
        }
        if (scheme.equalsIgnoreCase("file")) {
            try (InputStream input = Files.newInputStream(Paths.get(source))) {
                copyBounded(input, destination, maxBytes);
            }
            return;
        }
        if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) {
            throw new IOException("Unsupported SDK download URI scheme: " + scheme);
        }
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(SDX_DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = SDX_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("SDK download interrupted: " + source, interrupted);
        }
        try (InputStream input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("SDK download failed with HTTP " + response.statusCode() + ": " + source);
            }
            Optional<String> contentLength = response.headers().firstValue("Content-Length");
            if (contentLength.isPresent()) {
                try {
                    if (Long.parseLong(contentLength.get()) > maxBytes) {
                        throw new IOException("SDK download exceeds size limit of " + maxBytes + " bytes: " + source);
                    }
                } catch (NumberFormatException invalidLength) {
                    throw new IOException("Invalid Content-Length for SDK download: " + source, invalidLength);
                }
            }
            copyBounded(input, destination, maxBytes);
        }
    }

    private static void copyBounded(InputStream input, Path destination, long maxBytes) throws IOException {
        try (OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > maxBytes - total) {
                    throw new IOException("SDK download exceeds size limit of " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
    }

    /**
     * Downloads an SDZ model bundle.
     * Downloaded to ~/.kompile/models/sdz-bundles/{modelId}/
     *
     * @param descriptor The model descriptor for the SDZ bundle
     * @return Path to the downloaded SDZ file
     * @throws IOException if download fails
     */
    public Path downloadSdzBundle(ModelDescriptor descriptor) throws IOException {
        if (descriptor.getModelType() != ModelType.SDX_MODEL_BUNDLE) {
            throw new IllegalArgumentException("Descriptor must be of type SDX_MODEL_BUNDLE, got: " + descriptor.getModelType());
        }
        return ensureModelAvailable(descriptor);
    }

    /**
     * Lists available SDKs by merging hardcoded SdkConstants with registry.json "sdks" section.
     */
    public List<SdkDescriptor> listAvailableSdks() {
        List<SdkDescriptor> sdks = new ArrayList<>();

        // Add hardcoded SDKs
        sdks.add(SdkConstants.createSdxRuntimeDescriptor(null, null));
        sdks.add(SdkConstants.createKompileReasoningDescriptor(null, null));
        sdks.add(SdkConstants.createKompileLocalSdkDescriptor(null, null));

        // Merge from registry
        JsonNode registry = loadLocalRegistry();
        if (registry != null && registry.has("sdks")) {
            JsonNode sdksNode = registry.get("sdks");
            sdksNode.fieldNames().forEachRemaining(sdkId -> {
                JsonNode sdkNode = sdksNode.get(sdkId);
                try {
                    String version = sdkNode.has("version") ? sdkNode.get("version").asText() : SdkConstants.DEFAULT_SDX_SDK_VERSION;
                    String baseUrl = sdkNode.has("base_url") ? sdkNode.get("base_url").asText() : SdkConstants.resolveBaseUrl();

                    Map<String, SdkDescriptor.PlatformArtifact> artifacts = new LinkedHashMap<>();
                    if (sdkNode.has("platforms")) {
                        JsonNode platforms = sdkNode.get("platforms");
                        platforms.fieldNames().forEachRemaining(platform -> {
                            JsonNode pa = platforms.get(platform);
                            String artifactFile = pa.has("artifact") ? pa.get("artifact").asText() : "";
                            String packaging = pa.has("packaging") ? pa.get("packaging").asText() : SdkConstants.getPackagingForPlatform(platform);
                            String downloadUrl = baseUrl + artifactFile;
                            String checksum = pa.has("checksum") ? pa.get("checksum").asText() : null;
                            artifacts.put(platform, new SdkDescriptor.PlatformArtifact(
                                    platform, artifactFile, packaging, downloadUrl, checksum));
                        });
                    }

                    sdks.add(new SdkDescriptor(sdkId, version, baseUrl, artifacts));
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse SDK entry {}: {}", sdkId, e.getMessage());
                }
            });
        }

        return sdks;
    }

    /**
     * Lists available SDZ model bundles by merging hardcoded constants with registry.json "sdz_bundles" section.
     */
    public List<ModelDescriptor> listAvailableSdzBundles() {
        List<ModelDescriptor> bundles = new ArrayList<>();

        // Add hardcoded bundles
        ModelDescriptor smollm = SdkConstants.getSdzBundleDescriptor("smollm-135m");
        if (smollm != null) {
            bundles.add(smollm);
        }

        // Merge from registry
        JsonNode registry = loadLocalRegistry();
        if (registry != null && registry.has("sdz_bundles")) {
            JsonNode bundlesNode = registry.get("sdz_bundles");
            bundlesNode.fieldNames().forEachRemaining(modelId -> {
                JsonNode bundleNode = bundlesNode.get(modelId);
                try {
                    String version = bundleNode.has("version") ? bundleNode.get("version").asText() : "1.0";
                    String url = bundleNode.has("url") ? bundleNode.get("url").asText() : "";
                    String checksum = bundleNode.has("checksum") ? bundleNode.get("checksum").asText() : null;

                    Map<String, Object> metadata = new HashMap<>();
                    if (bundleNode.has("metadata")) {
                        JsonNode meta = bundleNode.get("metadata");
                        meta.fieldNames().forEachRemaining(key -> {
                            JsonNode val = meta.get(key);
                            if (val.isInt()) metadata.put(key, val.asInt());
                            else metadata.put(key, val.asText());
                        });
                    }

                    bundles.add(new ModelDescriptor(
                            modelId, ModelType.SDX_MODEL_BUNDLE,
                            url, "sdz-bundles/" + modelId + "/" + modelId + ".sdz",
                            version, checksum, metadata));
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse SDZ bundle entry {}: {}", modelId, e.getMessage());
                }
            });
        }

        return bundles;
    }

    /**
     * Checks if an SDK artifact is cached for the given platform.
     */
    public boolean isSdkCached(String sdkVersion, String platformClassifier) {
        return isSdkCached("sdx-runtime", sdkVersion, platformClassifier);
    }

    public boolean isSdkCached(String sdkId, String sdkVersion, String platformClassifier) {
        return findSdkArtifactPath(sdkId, sdkVersion, platformClassifier) != null;
    }

    /**
     * Gets the cached SDK artifact path, or null if not cached.
     */
    public Path getSdkArtifactPath(String sdkVersion, String platformClassifier) {
        return getSdkArtifactPath("sdx-runtime", sdkVersion, platformClassifier);
    }

    public Path getSdkArtifactPath(String sdkId, String sdkVersion, String platformClassifier) {
        return findSdkArtifactPath(sdkId, sdkVersion, platformClassifier);
    }

    private Path findSdkArtifactPath(String sdkId, String sdkVersion, String platformClassifier) {
        if (sdkId == null || sdkVersion == null || platformClassifier == null) return null;
        String canonicalClassifier = platformClassifier.startsWith("macosx-")
                ? "macos-" + platformClassifier.substring("macosx-".length()) : platformClassifier;
        Path versionDir = baseCachePath.resolve("sdx-sdk").resolve(sdkId).resolve(sdkVersion);
        Path legacyDir = versionDir.resolve(platformClassifier);
        if (Files.isDirectory(legacyDir)) {
            try (Stream<Path> files = Files.list(legacyDir)) {
                Optional<Path> legacy = files.filter(Files::isRegularFile).sorted().findFirst();
                if (legacy.isPresent()) return legacy.get();
            } catch (IOException ignored) {
                return null;
            }
        }
        Path origins = versionDir.resolve("origins");
        if (!Files.isDirectory(origins)) return null;
        try (Stream<Path> paths = Files.walk(origins, 4)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path relative = origins.relativize(path);
                        return relative.getNameCount() == 4
                                && (relative.getName(1) + "-" + relative.getName(2)).equals(canonicalClassifier);
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
