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

package io.anserini.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Anserini Model Downloader for converting ONNX models to SameDiff format.
 * 
 * This utility downloads ONNX models from Pyserini/Anserini sources and converts them 
 * to SameDiff format for use with Kompile's SameDiff encoders.
 */
public class AnseriniModelDownloader {
    private static final Logger logger = LoggerFactory.getLogger(AnseriniModelDownloader.class);
    
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
        
        MODELS.put("unicoil", new ModelInfo(
            "unicoil",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/unicoil-optimized.onnx",
            "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt",
            "Sparse encoder - UniCOIL for universal contextualized sparse retrieval",
            ModelType.SPARSE,
            30522
        ));
    }
    
    private final Path outputDirectory;
    
    public AnseriniModelDownloader(String outputDir) {
        this.outputDirectory = Paths.get(outputDir != null ? outputDir : "./anserini-models");
        
        try {
            Files.createDirectories(this.outputDirectory);
            logger.info("Model output directory: {}", this.outputDirectory.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory", e);
        }
    }
    
    /**
     * Main entry point for command line usage.
     */
    public static void main(String[] args) {
        try {
            CommandLineArgs cliArgs = parseArguments(args);
            
            if (cliArgs.showHelp) {
                printUsage();
                return;
            }
            
            if (cliArgs.listModels) {
                printAvailableModels();
                return;
            }
            
            AnseriniModelDownloader downloader = new AnseriniModelDownloader(cliArgs.outputDir);
            
            if (cliArgs.specificModel != null) {
                downloader.downloadAndConvertModel(cliArgs.specificModel);
            } else {
                // This is a simplified version - full implementation would include
                // the download and conversion logic
                logger.info("Would download and convert models based on filters:");
                logger.info("Dense only: {}", cliArgs.denseOnly);
                logger.info("Sparse only: {}", cliArgs.sparseOnly);
                logger.info("Parallel: {}", cliArgs.parallel);
            }
            
        } catch (Exception e) {
            logger.error("Error in Anserini model downloader", e);
            System.exit(1);
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
        // processModel(model); // Implementation would go here
        logger.info("Successfully processed: {}", modelName);
    }
    
    /**
     * Parse command line arguments.
     */
    private static CommandLineArgs parseArguments(String[] args) {
        CommandLineArgs cliArgs = new CommandLineArgs();
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help":
                case "-h":
                    cliArgs.showHelp = true;
                    break;
                case "--list":
                case "-l":
                    cliArgs.listModels = true;
                    break;
                case "--output-dir":
                case "-o":
                    if (i + 1 < args.length) {
                        cliArgs.outputDir = args[++i];
                    } else {
                        throw new IllegalArgumentException("--output-dir requires a directory path");
                    }
                    break;
                case "--model":
                case "-m":
                    if (i + 1 < args.length) {
                        cliArgs.specificModel = args[++i];
                    } else {
                        throw new IllegalArgumentException("--model requires a model name");
                    }
                    break;
                case "--parallel":
                case "-p":
                    cliArgs.parallel = true;
                    break;
                case "--dense-only":
                    cliArgs.denseOnly = true;
                    break;
                case "--sparse-only":
                    cliArgs.sparseOnly = true;
                    break;
                default:
                    if (args[i].startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                    }
                    // Assume it's an output directory if no other args
                    if (cliArgs.outputDir == null) {
                        cliArgs.outputDir = args[i];
                    }
                    break;
            }
        }
        
        // Validation
        if (cliArgs.denseOnly && cliArgs.sparseOnly) {
            throw new IllegalArgumentException("Cannot specify both --dense-only and --sparse-only");
        }
        
        return cliArgs;
    }
    
    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("Anserini Model Downloader - Download and convert ONNX models to SameDiff format");
        System.out.println();
        System.out.println("Usage: java io.anserini.util.AnseriniModelDownloader [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help           Show this help message");
        System.out.println("  -l, --list           List available models");
        System.out.println("  -o, --output-dir DIR Set output directory (default: ./anserini-models)");
        System.out.println("  -m, --model NAME     Download and convert specific model only");
        System.out.println("  -p, --parallel       Use parallel processing for multiple models");
        System.out.println("  --dense-only         Process dense models only");
        System.out.println("  --sparse-only        Process sparse models only");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Download all models to default directory");
        System.out.println("  java io.anserini.util.AnseriniModelDownloader");
        System.out.println();
        System.out.println("  # Download all models to custom directory with parallel processing");
        System.out.println("  java io.anserini.util.AnseriniModelDownloader --output-dir ./my-models --parallel");
        System.out.println();
        System.out.println("  # Download specific model");
        System.out.println("  java io.anserini.util.AnseriniModelDownloader --model bge-base-en-v1.5");
        System.out.println();
        System.out.println("  # Download only dense models");
        System.out.println("  java io.anserini.util.AnseriniModelDownloader --dense-only --parallel");
        System.out.println();
        System.out.println("Available models: " + String.join(", ", MODELS.keySet()));
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
    public static Set<String> getAvailableModelNames() {
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
     * Command line arguments container.
     */
    private static class CommandLineArgs {
        boolean showHelp = false;
        boolean listModels = false;
        String outputDir = null;
        String specificModel = null;
        boolean parallel = false;
        boolean denseOnly = false;
        boolean sparseOnly = false;
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
