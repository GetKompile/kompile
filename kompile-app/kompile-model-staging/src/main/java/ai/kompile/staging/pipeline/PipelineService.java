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

package ai.kompile.staging.pipeline;

import org.eclipse.deeplearning4j.pipeline.AutoModel;
import org.eclipse.deeplearning4j.pipeline.ModelFormat;
import org.eclipse.deeplearning4j.pipeline.ModelManifest;
import org.eclipse.deeplearning4j.pipeline.Pipeline;
import org.eclipse.deeplearning4j.pipeline.PipelineLoader;
import org.eclipse.deeplearning4j.pipeline.GenerationConfig;
import org.eclipse.deeplearning4j.pipeline.TokenizerConfig;
import org.eclipse.deeplearning4j.pipeline.PreprocessorConfig;
import org.nd4j.autodiff.samediff.SameDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Service for managing ML pipelines from HuggingFace Hub.
 *
 * Uses the samediff-pipeline modules for:
 * - Loading SafeTensors, GGUF, and ONNX models
 * - Parsing HuggingFace config files (config.json, tokenizer_config.json, etc.)
 * - Converting to SameDiff (.sdz) format for efficient inference
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Download and load a model from HuggingFace
 * Pipeline pipeline = pipelineService.loadPipeline("ds4sd/SmolDocling-256M-preview");
 *
 * // Get model components
 * SameDiff visionEncoder = pipeline.getVisionEncoder();
 * SameDiff decoder = pipeline.getDecoder();
 *
 * // Inspect a model without loading
 * PipelineInfo info = pipelineService.inspectModel("BAAI/bge-base-en-v1.5");
 * System.out.println("Format: " + info.getFormat());
 * System.out.println("Architecture: " + info.getArchitecture());
 * }</pre>
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private static final String HF_BASE_URL = "https://huggingface.co";
    private static final int CONNECTION_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 60000;
    private static final int BUFFER_SIZE = 8192;

    private final Path cacheDirectory;
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(4);
    private final Map<String, CompletableFuture<Pipeline>> loadingPipelines = new ConcurrentHashMap<>();

    public PipelineService(@Value("${kompile.pipeline.cache-dir:#{null}}") String cacheDir) {
        if (cacheDir != null && !cacheDir.isEmpty()) {
            this.cacheDirectory = Paths.get(cacheDir);
        } else {
            this.cacheDirectory = Paths.get(System.getProperty("user.home"), ".kompile", "pipelines");
        }

        try {
            Files.createDirectories(cacheDirectory);
            log.info("Pipeline cache directory: {}", cacheDirectory);
        } catch (IOException e) {
            log.warn("Failed to create pipeline cache directory: {}", cacheDirectory, e);
        }
    }

    /**
     * Load a pipeline from a local directory.
     *
     * @param modelDir the directory containing model files
     * @return the loaded Pipeline
     * @throws IOException if loading fails
     */
    public Pipeline loadPipeline(File modelDir) throws IOException {
        return loadPipeline(modelDir, defaultLoadConfig());
    }

    /**
     * Load a pipeline from a local directory with custom config.
     *
     * @param modelDir the directory containing model files
     * @param config the load configuration
     * @return the loaded Pipeline
     * @throws IOException if loading fails
     */
    public Pipeline loadPipeline(File modelDir, PipelineLoader.LoadConfig config) throws IOException {
        log.info("Loading pipeline from: {}", modelDir);
        return AutoModel.pipelineFromPretrained(modelDir, config);
    }

    /**
     * Load a single model from a local directory.
     *
     * @param modelDir the directory containing model files
     * @return the loaded SameDiff model
     * @throws IOException if loading fails
     */
    public SameDiff loadModel(File modelDir) throws IOException {
        return loadModel(modelDir, defaultLoadConfig());
    }

    /**
     * Load a single model from a local directory with custom config.
     *
     * @param modelDir the directory containing model files
     * @param config the load configuration
     * @return the loaded SameDiff model
     * @throws IOException if loading fails
     */
    public SameDiff loadModel(File modelDir, PipelineLoader.LoadConfig config) throws IOException {
        log.info("Loading model from: {}", modelDir);
        return AutoModel.fromPretrained(modelDir, config);
    }

    /**
     * Inspect a model directory without loading weights.
     *
     * @param modelDir the directory to inspect
     * @return PipelineInfo containing model metadata
     * @throws IOException if inspection fails
     */
    public PipelineInfo inspectModel(File modelDir) throws IOException {
        ModelManifest manifest = AutoModel.inspect(modelDir);
        return PipelineInfo.fromManifest(manifest);
    }

    /**
     * Download a model from HuggingFace Hub.
     *
     * @param repoId the HuggingFace repository ID (e.g., "BAAI/bge-base-en-v1.5")
     * @return the directory containing downloaded files
     * @throws IOException if download fails
     */
    public Path downloadFromHuggingFace(String repoId) throws IOException {
        return downloadFromHuggingFace(repoId, "main", null);
    }

    /**
     * Download a model from HuggingFace Hub with specific revision.
     *
     * @param repoId the HuggingFace repository ID
     * @param revision the git revision (branch, tag, or commit)
     * @param authToken optional authorization token for private models
     * @return the directory containing downloaded files
     * @throws IOException if download fails
     */
    public Path downloadFromHuggingFace(String repoId, String revision, String authToken) throws IOException {
        return downloadFromHuggingFace(repoId, revision, authToken, progress -> {});
    }

    /**
     * Download a model from HuggingFace Hub with progress callback.
     *
     * @param repoId the HuggingFace repository ID
     * @param revision the git revision
     * @param authToken optional authorization token
     * @param progressCallback callback for download progress
     * @return the directory containing downloaded files
     * @throws IOException if download fails
     */
    public Path downloadFromHuggingFace(String repoId, String revision, String authToken,
                                         Consumer<DownloadProgress> progressCallback) throws IOException {
        String effectiveRevision = revision != null ? revision : "main";
        String safeName = repoId.replace("/", "_").replace("\\", "_");
        Path modelDir = cacheDirectory.resolve(safeName);

        // Check if already downloaded
        if (isModelDownloaded(modelDir)) {
            log.info("Model already downloaded: {}", modelDir);
            progressCallback.accept(DownloadProgress.completed("Model already cached"));
            return modelDir;
        }

        Files.createDirectories(modelDir);
        progressCallback.accept(DownloadProgress.started("Fetching file list from HuggingFace"));

        // Get list of files to download
        List<String> files = getRequiredFiles(repoId, effectiveRevision, authToken);
        log.info("Found {} files to download for {}", files.size(), repoId);

        // Download each file
        int total = files.size();
        int current = 0;
        for (String file : files) {
            current++;
            int finalCurrent = current;
            progressCallback.accept(DownloadProgress.inProgress(
                    String.format("Downloading %s (%d/%d)", file, finalCurrent, total),
                    (double) current / total));

            String url = buildDownloadUrl(repoId, file, effectiveRevision);
            Path targetPath = modelDir.resolve(file);

            // Create parent directories if needed (for nested files like "onnx/model.onnx")
            Files.createDirectories(targetPath.getParent());

            downloadFile(url, targetPath, authToken);
            log.debug("Downloaded: {}", file);
        }

        progressCallback.accept(DownloadProgress.completed("Download complete"));
        log.info("Model downloaded to: {}", modelDir);
        return modelDir;
    }

    /**
     * Download and load a model from HuggingFace Hub.
     *
     * @param repoId the HuggingFace repository ID
     * @return the loaded Pipeline
     * @throws IOException if download or loading fails
     */
    public Pipeline downloadAndLoadPipeline(String repoId) throws IOException {
        Path modelDir = downloadFromHuggingFace(repoId);
        return loadPipeline(modelDir.toFile());
    }

    /**
     * Download and load a model from HuggingFace Hub asynchronously.
     *
     * @param repoId the HuggingFace repository ID
     * @return CompletableFuture for the loaded Pipeline
     */
    public CompletableFuture<Pipeline> downloadAndLoadPipelineAsync(String repoId) {
        return loadingPipelines.computeIfAbsent(repoId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return downloadAndLoadPipeline(id);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, downloadExecutor).whenComplete((p, ex) -> loadingPipelines.remove(id))
        );
    }

    /**
     * Get the default load configuration.
     */
    public PipelineLoader.LoadConfig defaultLoadConfig() {
        return PipelineLoader.LoadConfig.builder()
                .convertToFloat32(true)
                .cacheConvertedModel(true)
                .cacheDirectory(cacheDirectory.resolve("converted").toFile())
                .build();
    }

    /**
     * Check if a model is already downloaded.
     */
    public boolean isModelDownloaded(Path modelDir) {
        if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
            return false;
        }

        // Check for config.json (required for all HuggingFace models)
        Path configPath = modelDir.resolve("config.json");
        if (!Files.exists(configPath)) {
            return false;
        }

        // Check for weight files
        try {
            return Files.list(modelDir)
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
     * Get the cache directory path.
     */
    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    /**
     * List all cached models.
     */
    public List<String> listCachedModels() throws IOException {
        List<String> models = new ArrayList<>();
        if (Files.exists(cacheDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDirectory)) {
                for (Path path : stream) {
                    if (Files.isDirectory(path) && isModelDownloaded(path)) {
                        models.add(path.getFileName().toString());
                    }
                }
            }
        }
        return models;
    }

    /**
     * Delete a cached model.
     */
    public boolean deleteCachedModel(String modelId) throws IOException {
        Path modelDir = cacheDirectory.resolve(modelId.replace("/", "_"));
        if (Files.exists(modelDir)) {
            Files.walk(modelDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
            return true;
        }
        return false;
    }

    // Private helper methods

    private List<String> getRequiredFiles(String repoId, String revision, String authToken) throws IOException {
        // Core config files that should always be downloaded if present
        List<String> coreFiles = Arrays.asList(
                "config.json",
                "tokenizer_config.json",
                "tokenizer.json",
                "special_tokens_map.json",
                "generation_config.json",
                "preprocessor_config.json",
                "vocab.txt",
                "vocab.json",
                "merges.txt"
        );

        List<String> files = new ArrayList<>();

        // Try to download core files (they may not all exist)
        for (String file : coreFiles) {
            if (fileExistsInRepo(repoId, file, revision, authToken)) {
                files.add(file);
            }
        }

        // Detect weight files
        // Check for SafeTensors first (preferred)
        if (fileExistsInRepo(repoId, "model.safetensors", revision, authToken)) {
            files.add("model.safetensors");
        } else if (fileExistsInRepo(repoId, "model.safetensors.index.json", revision, authToken)) {
            // Sharded SafeTensors - need to download index and all shards
            files.add("model.safetensors.index.json");
            files.addAll(getShardedFiles(repoId, revision, authToken, ".safetensors"));
        } else if (fileExistsInRepo(repoId, "onnx/model.onnx", revision, authToken)) {
            files.add("onnx/model.onnx");
        } else if (fileExistsInRepo(repoId, "model.onnx", revision, authToken)) {
            files.add("model.onnx");
        } else if (fileExistsInRepo(repoId, "pytorch_model.bin", revision, authToken)) {
            files.add("pytorch_model.bin");
        }

        // Check for GGUF files
        List<String> ggufFiles = findFilesWithExtension(repoId, revision, authToken, ".gguf");
        if (!ggufFiles.isEmpty()) {
            files.addAll(ggufFiles);
        }

        return files;
    }

    private List<String> getShardedFiles(String repoId, String revision, String authToken,
                                         String extension) throws IOException {
        List<String> shards = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String shard = String.format("model-%05d-of-%05d%s", i, 0, extension);
            // Try common patterns
            for (int total = i; total <= 100; total++) {
                String shardName = String.format("model-%05d-of-%05d%s", i, total, extension);
                if (fileExistsInRepo(repoId, shardName, revision, authToken)) {
                    shards.add(shardName);
                    break;
                }
            }
        }
        return shards;
    }

    private List<String> findFilesWithExtension(String repoId, String revision,
                                                 String authToken, String extension) throws IOException {
        // This is a simplified implementation
        // In a full implementation, you would use the HuggingFace API to list files
        List<String> files = new ArrayList<>();

        // Common GGUF naming patterns
        String[] patterns = {
                "model.gguf",
                "model-q4_0.gguf",
                "model-q4_k_m.gguf",
                "model-q5_k_m.gguf",
                "model-q8_0.gguf"
        };

        for (String pattern : patterns) {
            if (fileExistsInRepo(repoId, pattern, revision, authToken)) {
                files.add(pattern);
            }
        }

        return files;
    }

    private boolean fileExistsInRepo(String repoId, String file, String revision,
                                     String authToken) {
        try {
            String url = buildDownloadUrl(repoId, file, revision);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECTION_TIMEOUT);
            if (authToken != null && !authToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildDownloadUrl(String repoId, String file, String revision) {
        return String.format("%s/%s/resolve/%s/%s", HF_BASE_URL, repoId, revision, file);
    }

    private void downloadFile(String urlStr, Path destination, String authToken) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECTION_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Kompile-Pipeline-Service/1.0");

        if (authToken != null && !authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        // Handle redirects
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == 307 || responseCode == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            downloadFile(newUrl, destination, authToken);
            return;
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + responseCode + " for " + urlStr);
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Progress information for download operations.
     */
    public static class DownloadProgress {
        private final String message;
        private final double progress;
        private final boolean completed;
        private final boolean error;

        private DownloadProgress(String message, double progress, boolean completed, boolean error) {
            this.message = message;
            this.progress = progress;
            this.completed = completed;
            this.error = error;
        }

        public static DownloadProgress started(String message) {
            return new DownloadProgress(message, 0.0, false, false);
        }

        public static DownloadProgress inProgress(String message, double progress) {
            return new DownloadProgress(message, progress, false, false);
        }

        public static DownloadProgress completed(String message) {
            return new DownloadProgress(message, 1.0, true, false);
        }

        public static DownloadProgress error(String message) {
            return new DownloadProgress(message, 0.0, false, true);
        }

        public String getMessage() { return message; }
        public double getProgress() { return progress; }
        public boolean isCompleted() { return completed; }
        public boolean isError() { return error; }
    }

    /**
     * Information about a pipeline/model.
     */
    public static class PipelineInfo {
        private final ModelFormat format;
        private final String architecture;
        private final boolean isLLM;
        private final boolean isVision;
        private final boolean isDiffusion;
        private final boolean isPipeline;
        private final boolean isSharded;
        private final Set<String> componentNames;
        private final TokenizerConfig tokenizerConfig;
        private final GenerationConfig generationConfig;
        private final PreprocessorConfig preprocessorConfig;

        private PipelineInfo(ModelManifest manifest) {
            this.format = manifest.getFormat();
            this.architecture = manifest.getArchitecture();
            this.isLLM = manifest.isLikelyLLM();
            this.isVision = manifest.isLikelyVisionModel();
            this.isDiffusion = manifest.isLikelyDiffusionModel();
            this.isPipeline = manifest.isPipeline();
            this.isSharded = manifest.isSharded();
            this.componentNames = manifest.getComponentNames();
            this.tokenizerConfig = manifest.getTokenizerConfig();
            this.generationConfig = manifest.getGenerationConfig();
            this.preprocessorConfig = manifest.getPreprocessorConfig();
        }

        public static PipelineInfo fromManifest(ModelManifest manifest) {
            return new PipelineInfo(manifest);
        }

        public ModelFormat getFormat() { return format; }
        public String getArchitecture() { return architecture; }
        public boolean isLLM() { return isLLM; }
        public boolean isVision() { return isVision; }
        public boolean isDiffusion() { return isDiffusion; }
        public boolean isPipeline() { return isPipeline; }
        public boolean isSharded() { return isSharded; }
        public Set<String> getComponentNames() { return componentNames; }
        public TokenizerConfig getTokenizerConfig() { return tokenizerConfig; }
        public GenerationConfig getGenerationConfig() { return generationConfig; }
        public PreprocessorConfig getPreprocessorConfig() { return preprocessorConfig; }

        @Override
        public String toString() {
            return "PipelineInfo{" +
                    "format=" + format +
                    ", architecture='" + architecture + '\'' +
                    ", isLLM=" + isLLM +
                    ", isVision=" + isVision +
                    ", isDiffusion=" + isDiffusion +
                    ", isPipeline=" + isPipeline +
                    ", components=" + componentNames +
                    '}';
        }
    }
}
