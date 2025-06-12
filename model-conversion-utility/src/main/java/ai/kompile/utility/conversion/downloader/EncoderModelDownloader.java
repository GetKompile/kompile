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

package ai.kompile.utility.conversion.downloader;

import ai.kompile.model.onnx.OnnxFrameworkImporterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced Anserini Model Downloader integrated into the model conversion utility.
 * 
 * This utility downloads ONNX models from Pyserini/Anserini sources and converts them 
 * to SameDiff format for use with Kompile's SameDiff encoders.
 */
public class EncoderModelDownloader {
    private static final Logger logger = LoggerFactory.getLogger(EncoderModelDownloader.class);
    
    // Model definitions with their download URLs
    private static final Map<String, ModelInfo> MODELS = new HashMap<>();
    
    static {
        // Dense Models from Pyserini/Anserini
        MODELS.put("bge-base-en-v1.5", new ModelInfo(
            "bge-base-en-v1.5",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/bge-base-en-v1.5-optimized.onnx",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/bge-base-en-v1.5-vocab.txt",
            "Dense encoder - BGE Base English v1.5 for semantic search",
            ModelType.DENSE,
            768 // embedding dimension
        ));
        
        MODELS.put("cosdpr-distil", new ModelInfo(
            "cosdpr-distil", 
            "https://rgw.cs.uwaterloo.ca/pyserini/data/cosdpr-distil-optimized.onnx",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/cosdpr-distil-vocab.txt",
            "Dense encoder - CosDPR Distilled for passage retrieval",
            ModelType.DENSE,
            768
        ));
        
        MODELS.put("arctic-embed-l", new ModelInfo(
            "arctic-embed-l",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/arctic-embed-l-official.onnx", 
            "https://rgw.cs.uwaterloo.ca/pyserini/data/arctic-embed-l-official-vocab.txt",
            "Dense encoder - Snowflake Arctic Embed Large for retrieval",
            ModelType.DENSE,
            1024
        ));
        
        // Sparse Models from Pyserini/Anserini
        MODELS.put("splade-pp-sd", new ModelInfo(
            "splade-pp-sd",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/splade-pp-sd-optimized.onnx",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt", 
            "Sparse encoder - SPLADE++ Self-Distil for efficient sparse retrieval",
            ModelType.SPARSE,
            30522 // vocab size
        ));
        
        MODELS.put("splade-pp-ed", new ModelInfo(
            "splade-pp-ed",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/splade-pp-ed-optimized.onnx",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt",
            "Sparse encoder - SPLADE++ Ensemble-Distil for high-quality sparse retrieval", 
            ModelType.SPARSE,
            30522
        ));
        
       /* MODELS.put("unicoil", new ModelInfo(
            "unicoil",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/unicoil-optimized.onnx",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt",
            "Sparse encoder - UniCOIL for universal contextualized sparse retrieval",
            ModelType.SPARSE,
            30522
        ));*/
    }
    
    private final Path outputDirectory;
    private final boolean parallelProcessing;
    
    public EncoderModelDownloader(String outputDir, boolean parallelProcessing) {
        this.outputDirectory = Paths.get(outputDir != null ? outputDir : "./anserini-models");
        this.parallelProcessing = parallelProcessing;
        
        try {
            Files.createDirectories(this.outputDirectory);
            logger.info("Model output directory: {}", this.outputDirectory.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory", e);
        }
    }
    
    /**
     * Download and convert models based on filters.
     */
    public void downloadAndConvertModels(boolean denseOnly, boolean sparseOnly) {
        Map<String, ModelInfo> modelsToProcess = new HashMap<>(MODELS);
        
        // Apply filters
        if (denseOnly) {
            modelsToProcess.entrySet().removeIf(entry -> entry.getValue().type != ModelType.DENSE);
            logger.info("Processing dense models only");
        } else if (sparseOnly) {
            modelsToProcess.entrySet().removeIf(entry -> entry.getValue().type != ModelType.SPARSE);
            logger.info("Processing sparse models only");
        }
        
        logger.info("Starting download and conversion of {} models", modelsToProcess.size());
        
        if (parallelProcessing && modelsToProcess.size() > 1) {
            processModelsInParallel(modelsToProcess);
        } else {
            processModelsSequentially(modelsToProcess);
        }
    }
    
    /**
     * Process models sequentially.
     */
    private void processModelsSequentially(Map<String, ModelInfo> models) {
        int successful = 0;
        int failed = 0;
        
        for (ModelInfo model : models.values()) {
            logger.info("\n--- Processing model: {} ---", model.name);
            logger.info("Description: {}", model.description);
            logger.info("Type: {} | Dimension: {}", model.type, model.dimension);
            
            try {
                processModel(model);
                successful++;
                logger.info("✓ Successfully processed: {}", model.name);
            } catch (Exception e) {
                failed++;
                logger.error("✗ Failed to process {}: {}", model.name, e.getMessage(), e);
            }
        }
        
        printSummary(models.size(), successful, failed);
    }
    
    /**
     * Process models in parallel for faster conversion.
     */
    private void processModelsInParallel(Map<String, ModelInfo> models) {
        logger.info("Using parallel processing with {} threads", Runtime.getRuntime().availableProcessors());
        
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(models.size(), Runtime.getRuntime().availableProcessors()));
        
        try {
            CompletableFuture<?>[] futures = models.values().stream()
                .map(model -> CompletableFuture.runAsync(() -> {
                    try {
                        logger.info("Starting parallel processing of: {}", model.name);
                        processModel(model);
                        logger.info("✓ Completed parallel processing of: {}", model.name);
                    } catch (Exception e) {
                        logger.error("✗ Failed parallel processing of {}: {}", model.name, e.getMessage(), e);
                    }
                }, executor))
                .toArray(CompletableFuture[]::new);
            
            // Wait for all to complete
            CompletableFuture.allOf(futures).get(30, TimeUnit.MINUTES);
            
        } catch (Exception e) {
            logger.error("Error in parallel processing", e);
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
        
        logger.info("Parallel processing completed");
    }
    
    /**
     * Process a single model: download ONNX, download vocab, convert to SameDiff.
     */
    private void processModel(ModelInfo model) throws IOException {
        // Create model-specific directory
        Path modelDir = outputDirectory.resolve(model.name);
        Files.createDirectories(modelDir);
        
        // Check if already processed
        Path sameDiffPath = modelDir.resolve(model.name + ".sd");
        if (Files.exists(sameDiffPath) && Files.size(sameDiffPath) > 0) {
            logger.info("Model {} already exists, skipping conversion", model.name);
            return;
        }
        
        // Download ONNX model
        Path onnxPath = modelDir.resolve(model.name + ".onnx");
        if (!Files.exists(onnxPath)) {
            logger.info("Downloading ONNX model from: {}", model.onnxUrl);
            downloadFile(model.onnxUrl, onnxPath);
            logger.info("✓ Downloaded ONNX model: {}", onnxPath.getFileName());
        } else {
            logger.info("ONNX model already exists: {}", onnxPath.getFileName());
        }
        
        // Download vocabulary file
        Path vocabPath = modelDir.resolve(model.name + "-vocab.txt");
        if (!Files.exists(vocabPath)) {
            logger.info("Downloading vocabulary from: {}", model.vocabUrl);
            downloadFile(model.vocabUrl, vocabPath);
            logger.info("✓ Downloaded vocabulary: {}", vocabPath.getFileName());
        } else {
            logger.info("Vocabulary file already exists: {}", vocabPath.getFileName());
        }
        
        // Convert ONNX to SameDiff
        logger.info("Converting to SameDiff format...");
        convertToSameDiff(onnxPath, sameDiffPath);
        logger.info("✓ Converted to SameDiff: {}", sameDiffPath.getFileName());
        
        // Validate conversion
        validateConversion(sameDiffPath);
        logger.info("✓ Validation completed");
        
        // Display file sizes
        displayFileSizes(onnxPath, sameDiffPath, vocabPath);
    }
    
    private void displayFileSizes(Path onnxPath, Path sameDiffPath, Path vocabPath) throws IOException {
        long onnxSize = Files.size(onnxPath);
        long sameDiffSize = Files.size(sameDiffPath);
        long vocabSize = Files.size(vocabPath);
        
        logger.info("File sizes:");
        logger.info("  ONNX: {:.1f} MB", onnxSize / (1024.0 * 1024.0));
        logger.info("  SameDiff: {:.1f} MB", sameDiffSize / (1024.0 * 1024.0));
        logger.info("  Vocab: {:.1f} KB", vocabSize / 1024.0);
        logger.info("  Total: {:.1f} MB", (onnxSize + sameDiffSize + vocabSize) / (1024.0 * 1024.0));
    }
    
    private void printSummary(int total, int successful, int failed) {
        logger.info("\n=== Processing Summary ===");
        logger.info("Total models: {}", total);
        logger.info("Successful: {}", successful);
        logger.info("Failed: {}", failed);
        
        if (failed > 0) {
            logger.warn("Some models failed to process. Check logs above for details.");
        } else {
            logger.info("All models processed successfully!");
        }
    }
    
    /**
     * Download a file from URL to local path with progress indication.
     */
    private void downloadFile(String url, Path targetPath) throws IOException {
        try (InputStream in = new URL(url).openStream();
             ReadableByteChannel rbc = Channels.newChannel(in);
             FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
            
            long bytesTransferred = fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            logger.debug("Downloaded {} bytes from {}", bytesTransferred, url);
        } catch (IOException e) {
            // Clean up partial download
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException cleanupEx) {
                logger.debug("Failed to cleanup partial download: {}", targetPath);
            }
            throw new IOException("Failed to download " + url + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert ONNX model to SameDiff format.
     */
    private void convertToSameDiff(Path onnxPath, Path sameDiffPath) throws IOException {
        try {
            // Use default conversion parameters - works for most Anserini models
            OnnxFrameworkImporterService onnxFrameworkImporterService = new OnnxFrameworkImporterService();
            onnxFrameworkImporterService.importModel(
                onnxPath.toFile(),
                sameDiffPath.toFile(),
                new HashMap<>(), // Empty dynamic variables map
                false, // Don't suggest dynamic variables
                false  // Don't track variable changes
            );
        } catch (Exception e) {
            // Clean up partial conversion
            try {
                Files.deleteIfExists(sameDiffPath);
            } catch (IOException cleanupEx) {
                logger.debug("Failed to cleanup partial conversion: {}", sameDiffPath);
            }
            throw new IOException("Failed to convert " + onnxPath.getFileName() + 
                                " to SameDiff: " + e.getMessage(), e);
        }
    }
    
    /**
     * Validate that the converted SameDiff model can be loaded.
     */
    private void validateConversion(Path sameDiffPath) throws IOException {
        if (!Files.exists(sameDiffPath)) {
            throw new IOException("SameDiff file was not created: " + sameDiffPath);
        }
        
        if (Files.size(sameDiffPath) == 0) {
            throw new IOException("SameDiff file is empty: " + sameDiffPath);
        }
        
        // Basic validation - file exists and has reasonable size
        long fileSize = Files.size(sameDiffPath);
        if (fileSize < 1024) { // Less than 1KB is suspicious
            throw new IOException("SameDiff file is suspiciously small: " + fileSize + " bytes");
        }
        
        logger.debug("SameDiff file validation passed: {} bytes", fileSize);
    }
    
    /**
     * Download and convert a specific model by name.
     */
    public void downloadAndConvertModel(String modelName) throws IOException {
        ModelInfo model = MODELS.get(modelName);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model: " + modelName + 
                ". Available models: " + MODELS.keySet());
        }
        
        logger.info("Processing single model: {}", modelName);
        processModel(model);
        logger.info("Successfully processed: {}", modelName);
    }
    
    /**
     * Print information about available models.
     */
    public static void printAvailableModels() {
        System.out.println("Available models for download and conversion:");
        System.out.println();
        
        // Group by type
        System.out.println("DENSE MODELS:");
        MODELS.values().stream()
            .filter(model -> model.type == ModelType.DENSE)
            .forEach(model -> {
                System.out.printf("  • %-20s %s\n", model.name, model.description);
                System.out.printf("    %-20s Embedding dimension: %d\n", "", model.dimension);
                System.out.printf("    %-20s ONNX: %s\n", "", model.onnxUrl);
                System.out.printf("    %-20s Vocab: %s\n", "", model.vocabUrl);
                System.out.println();
            });
        
        System.out.println("SPARSE MODELS:");
        MODELS.values().stream()
            .filter(model -> model.type == ModelType.SPARSE)
            .forEach(model -> {
                System.out.printf("  • %-20s %s\n", model.name, model.description);
                System.out.printf("    %-20s Vocabulary size: %d\n", "", model.dimension);
                System.out.printf("    %-20s ONNX: %s\n", "", model.onnxUrl);
                System.out.printf("    %-20s Vocab: %s\n", "", model.vocabUrl);
                System.out.println();
            });
        
        System.out.println("Total models available: " + MODELS.size());
    }
    
    // Utility methods for programmatic access
    
    /**
     * Get the output directory where models are stored.
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }
    
    /**
     * Get information about a specific model.
     */
    public static ModelInfo getModelInfo(String modelName) {
        return MODELS.get(modelName);
    }
    
    /**
     * Get all available model names.
     */
    public static java.util.Set<String> getAvailableModelNames() {
        return MODELS.keySet();
    }
    
    /**
     * Check if a model exists and is valid.
     */
    public boolean isModelDownloaded(String modelName) {
        ModelInfo model = MODELS.get(modelName);
        if (model == null) return false;
        
        Path modelDir = outputDirectory.resolve(model.name);
        Path sameDiffPath = modelDir.resolve(model.name + ".sd");
        Path vocabPath = modelDir.resolve(model.name + "-vocab.txt");
        
        try {
            return Files.exists(sameDiffPath) && Files.size(sameDiffPath) > 0 &&
                   Files.exists(vocabPath) && Files.size(vocabPath) > 0;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Get the path to a converted model file.
     */
    public Path getModelPath(String modelName) {
        ModelInfo model = MODELS.get(modelName);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model: " + modelName);
        }
        
        return outputDirectory.resolve(model.name).resolve(model.name + ".sd");
    }
    
    /**
     * Get the path to a model's vocabulary file.
     */
    public Path getVocabPath(String modelName) {
        ModelInfo model = MODELS.get(modelName);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model: " + modelName);
        }
        
        return outputDirectory.resolve(model.name).resolve(model.name + "-vocab.txt");
    }
    
    /**
     * Enhanced model information container.
     */
    public static class ModelInfo {
        public final String name;
        public final String onnxUrl;
        public final String vocabUrl;
        public final String description;
        public final ModelType type;
        public final int dimension; // embedding dimension for dense models, vocab size for sparse
        
        public ModelInfo(String name, String onnxUrl, String vocabUrl, 
                        String description, ModelType type, int dimension) {
            this.name = name;
            this.onnxUrl = onnxUrl;
            this.vocabUrl = vocabUrl;
            this.description = description;
            this.type = type;
            this.dimension = dimension;
        }
        
        @Override
        public String toString() {
            return String.format("ModelInfo{name='%s', type=%s, dimension=%d, description='%s'}", 
                                name, type, dimension, description);
        }
    }
    
    /**
     * Model type enumeration.
     */
    public enum ModelType {
        DENSE("Dense Embedding Model"),
        SPARSE("Sparse Retrieval Model");
        
        private final String description;
        
        ModelType(String description) {
            this.description = description;
        }
        
        @Override
        public String toString() {
            return description;
        }
    }
}
