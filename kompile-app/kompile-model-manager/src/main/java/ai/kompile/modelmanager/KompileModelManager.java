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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import java.io.BufferedInputStream;
import java.util.Map;

/**
 * Manages the download, caching, and retrieval of ML/NLP models.
 */
public class KompileModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(KompileModelManager.class);
    
    // Environment variable to specify the model cache directory at runtime
    public static final String ENV_KOMPILE_MODEL_CACHE_DIR = "KOMPILE_MODEL_CACHE_DIR";
    public static final String DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR = ".kompile" + File.separator + "models";
    
    private final Path baseCachePath;

    /**
     * Initializes the model manager with a specific base cache path.
     * If the KOMPILE_MODEL_CACHE_DIR environment variable is set, it's used.
     * Otherwise, defaults to ~/.kompile/models.
     */
    public KompileModelManager() {
        String cacheDirEnv = System.getenv(ENV_KOMPILE_MODEL_CACHE_DIR);
        if (cacheDirEnv != null && !cacheDirEnv.trim().isEmpty()) {
            this.baseCachePath = Paths.get(cacheDirEnv.trim());
        } else {
            this.baseCachePath = Paths.get(System.getProperty("user.home"), DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR);
        }
        try {
            Files.createDirectories(this.baseCachePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create base model cache directory: " + this.baseCachePath, e);
        }
        LOGGER.info("KompileModelManager initialized. Cache path: {}", this.baseCachePath.toAbsolutePath());
    }

    /**
     * Constructor with custom cache path.
     */
    public KompileModelManager(Path customCachePath) {
        this.baseCachePath = customCachePath;
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
     * Ensures both model and vocabulary files are available for an encoder model.
     * This method downloads both the model file (.sdz) and its corresponding vocabulary file.
     *
     * @param modelId The model identifier (e.g., "bge-base-en-v1.5")
     * @return ModelBundle containing paths to both model and vocabulary files
     * @throws IOException if download or caching fails
     */
    public ModelBundle ensureEncoderModelBundle(String modelId) throws IOException {
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
        
        return new ModelBundle(modelId, modelPath, vocabPath, modelDescriptor.getMetadata(),tokenizerConfig);
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
                        Files.walk(modelPathInCache).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(path)) {
                byte[] byteArray = new byte[1024];
                int bytesCount;
                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
            }
            byte[] bytes = digest.digest();
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
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
}
