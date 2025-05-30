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

package ai.kompile.utility.conversion;

import ai.kompile.utility.conversion.config.ConversionConfig;
import ai.kompile.utility.conversion.config.ModelDefinition;
import ai.kompile.utility.conversion.downloader.AnseriniModelDownloader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import picocli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Unified main class for all model management utilities.
 * 
 * This serves as the entry point for:
 * 1. Model conversion (ONNX to SameDiff)
 * 2. Anserini model downloading and conversion
 * 3. Model validation and management
 * 
 * Usage:
 *   java -jar model-conversion-utility.jar convert [config.yaml]
 *   java -jar model-conversion-utility.jar download-anserini [options]
 *   java -jar model-conversion-utility.jar validate [config.yaml]
 */
@CommandLine.Command(
    name = "kompile-model-utility",
    description = "Kompile Model Management Utility - Convert, download, and manage neural retrieval models",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = {
        ModelUtilityMain.ConvertCommand.class,
        ModelUtilityMain.DownloadAnseriniCommand.class,
        ModelUtilityMain.ValidateCommand.class,
        ModelUtilityMain.ListCommand.class
    }
)
public class ModelUtilityMain implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(ModelUtilityMain.class);

    public static void main(String[] args) {
        // Set up logging
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss");
        
        logger.info("=== Kompile Model Management Utility ===");
        
        int exitCode = new CommandLine(new ModelUtilityMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // Print help if no subcommand is provided
        CommandLine.usage(this, System.out);
        return 0;
    }

    /**
     * Model conversion subcommand (ONNX to SameDiff).
     */
    @CommandLine.Command(
        name = "convert",
        description = "Convert models according to configuration file"
    )
    static class ConvertCommand implements Callable<Integer> {

        @CommandLine.Parameters(
            index = "0",
            description = "Configuration file (YAML format)",
            defaultValue = "models-to-convert.yaml"
        )
        private String configFile;

        @CommandLine.Option(
            names = {"-m", "--model"},
            description = "Convert only the specified model (by model ID)"
        )
        private String specificModelId;

        @CommandLine.Option(
            names = {"-d", "--dry-run"},
            description = "Validate configuration and show what would be converted without performing conversion"
        )
        private boolean dryRun = false;

        @CommandLine.Option(
            names = {"-v", "--verbose"},
            description = "Enable verbose logging"
        )
        private boolean verbose = false;

        @CommandLine.Option(
            names = {"--skip-upload"},
            description = "Skip uploading to GitHub (only convert locally)"
        )
        private boolean skipUpload = false;

        @Override
        public Integer call() throws Exception {
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }

            logger.info("Model Conversion Utility");
            logger.info("Configuration file: {}", configFile);

            // Load configuration
            ConversionConfig config = loadConfiguration(new File(configFile));
            if (config == null) {
                return 1;
            }

            // Modify config based on CLI options
            if (skipUpload) {
                logger.info("Upload will be skipped (--skip-upload specified)");
                config.setGithubConfig(null); // Disable GitHub upload
            }

            if (dryRun) {
                return performDryRun(config, specificModelId);
            }

            // Perform conversion
            try (ModelConversionManager manager = new ModelConversionManager(config)) {
                ModelConversionManager.ConversionReport report;
                
                if (specificModelId != null) {
                    logger.info("Converting single model: {}", specificModelId);
                    ModelConversionManager.ModelConversionResult result = manager.convertSingleModel(specificModelId);
                    
                    if (result.isSuccess()) {
                        logger.info("Successfully converted {}", specificModelId);
                        logger.info("Download URL: {}", result.getDownloadUrl());
                        if (result.getVocabDownloadUrl() != null) {
                            logger.info("Vocab URL: {}", result.getVocabDownloadUrl());
                        }
                        return 0;
                    } else {
                        logger.error("Failed to convert {}: {}", specificModelId, result.getErrorMessage());
                        return 1;
                    }
                } else {
                    logger.info("Converting all models");
                    report = manager.convertAllModels();
                }
                
                return report.isOverallSuccess() ? 0 : 1;
            }
        }

        private ConversionConfig loadConfiguration(File configFile) {
            try {
                if (!configFile.exists() || !configFile.canRead()) {
                    logger.error("Configuration file not found or not readable: {}", configFile.getAbsolutePath());
                    return null;
                }

                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                ConversionConfig config = yamlMapper.readValue(configFile, ConversionConfig.class);
                
                logger.info("Loaded configuration with {} models", config.getModels().size());
                return config;
                
            } catch (Exception e) {
                logger.error("Failed to load configuration", e);
                return null;
            }
        }

        private int performDryRun(ConversionConfig config, String specificModelId) {
            logger.info("=== DRY RUN MODE ===");
            logger.info("Working directory: {}", config.getConversionSettings().getWorkingDirectory());
            logger.info("Parallel conversions: {}", config.getConversionSettings().getParallelConversions());
            logger.info("Generate checksums: {}", config.getConversionSettings().isGenerateChecksums());
            
            if (config.getGithubConfig() != null) {
                logger.info("GitHub repository: {}/{}", 
                           config.getGithubConfig().getRepositoryOwner(),
                           config.getGithubConfig().getRepositoryName());
                logger.info("Release tag: {}", config.getGithubConfig().getReleaseTag());
                logger.info("Base download URL: {}", config.getGithubConfig().getBaseDownloadUrl());
            } else {
                logger.info("GitHub upload: DISABLED");
            }
            
            logger.info("\nModels to convert:");
            for (ModelDefinition model : config.getModels()) {
                if (specificModelId == null || model.getModelId().equals(specificModelId)) {
                    logger.info("  - {}: {} -> {}", 
                               model.getModelId(), 
                               model.getSourceModelFilename(),
                               model.getTargetModelFilename());
                    logger.info("    Source: {}", model.getSourceModelUrl());
                    logger.info("    Type: {}", model.getModelType());
                }
            }
            
            logger.info("\nDry run completed successfully");
            return 0;
        }
    }

    /**
     * Anserini model download subcommand.
     */
    @CommandLine.Command(
        name = "download-anserini",
        description = "Download and convert ONNX models from Anserini/Pyserini"
    )
    static class DownloadAnseriniCommand implements Callable<Integer> {

        @CommandLine.Option(
            names = {"-o", "--output-dir"},
            description = "Output directory for downloaded models",
            defaultValue = "./anserini-models"
        )
        private String outputDir;

        @CommandLine.Option(
            names = {"-m", "--model"},
            description = "Download specific model only"
        )
        private String specificModel;

        @CommandLine.Option(
            names = {"-p", "--parallel"},
            description = "Use parallel processing"
        )
        private boolean parallel = false;

        @CommandLine.Option(
            names = {"--dense-only"},
            description = "Download dense models only"
        )
        private boolean denseOnly = false;

        @CommandLine.Option(
            names = {"--sparse-only"},
            description = "Download sparse models only"
        )
        private boolean sparseOnly = false;

        @CommandLine.Option(
            names = {"-l", "--list"},
            description = "List available models"
        )
        private boolean listModels = false;

        @CommandLine.Option(
            names = {"-v", "--verbose"},
            description = "Enable verbose logging"
        )
        private boolean verbose = false;

        @Override
        public Integer call() throws Exception {
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }

            if (listModels) {
                AnseriniModelDownloader.printAvailableModels();
                return 0;
            }

            // Validate arguments
            if (denseOnly && sparseOnly) {
                logger.error("Cannot specify both --dense-only and --sparse-only");
                return 1;
            }

            try {
                AnseriniModelDownloader downloader = new AnseriniModelDownloader(outputDir, parallel);
                
                if (specificModel != null) {
                    // Download specific model
                    downloader.downloadAndConvertModel(specificModel);
                    logger.info("Successfully downloaded and converted: {}", specificModel);
                } else {
                    // Download all models (or filtered subset)
                    downloader.downloadAndConvertModels(denseOnly, sparseOnly);
                    logger.info("Successfully completed model downloads");
                }
                
                return 0;
                
            } catch (Exception e) {
                logger.error("Failed to download/convert models", e);
                return 1;
            }
        }
    }

    /**
     * Configuration validation subcommand.
     */
    @CommandLine.Command(
        name = "validate",
        description = "Validate configuration file and check source URLs"
    )
    static class ValidateCommand implements Callable<Integer> {

        @CommandLine.Parameters(
            index = "0",
            description = "Configuration file (YAML format)",
            defaultValue = "models-to-convert.yaml"
        )
        private String configFile;

        @CommandLine.Option(
            names = {"-u", "--check-urls"},
            description = "Check if source URLs are accessible"
        )
        private boolean checkUrls = false;

        @Override
        public Integer call() throws Exception {
            logger.info("Validating configuration: {}", configFile);

            try {
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                ConversionConfig config = yamlMapper.readValue(new File(configFile), ConversionConfig.class);
                
                boolean isValid = validateConfiguration(config, checkUrls);
                
                if (isValid) {
                    logger.info("Configuration validation PASSED");
                    return 0;
                } else {
                    logger.error("Configuration validation FAILED");
                    return 1;
                }
                
            } catch (Exception e) {
                logger.error("Failed to validate configuration", e);
                return 1;
            }
        }

        private boolean validateConfiguration(ConversionConfig config, boolean checkUrls) {
            boolean isValid = true;
            
            // Validate basic structure
            if (config.getModels() == null || config.getModels().isEmpty()) {
                logger.error("No models defined in configuration");
                return false;
            }
            
            if (config.getConversionSettings() == null) {
                logger.error("Conversion settings not defined");
                return false;
            }
            
            // Validate GitHub config if upload is enabled
            if (config.getGithubConfig() != null) {
                if (config.getGithubConfig().getRepositoryOwner() == null || 
                    config.getGithubConfig().getRepositoryName() == null ||
                    config.getGithubConfig().getReleaseTag() == null) {
                    logger.error("Incomplete GitHub configuration");
                    isValid = false;
                }
            }
            
            // Validate individual models
            for (ModelDefinition model : config.getModels()) {
                if (!validateModel(model)) {
                    isValid = false;
                }
            }
            
            return isValid;
        }

        private boolean validateModel(ModelDefinition model) {
            boolean isValid = true;
            String modelId = model.getModelId();
            
            logger.info("Validating model: {}", modelId);
            
            // Check required fields
            if (modelId == null || modelId.trim().isEmpty()) {
                logger.error("Model ID is required");
                isValid = false;
            }
            
            if (model.getSourceModelUrl() == null || model.getSourceModelFilename() == null ||
                model.getTargetModelFilename() == null) {
                logger.error("Model {} missing required fields", modelId);
                isValid = false;
            }
            
            if (model.getModelType() == null) {
                logger.error("Model {} missing model type", modelId);  
                isValid = false;
            }
            
            return isValid;
        }
    }

    /**
     * List models subcommand.
     */
    @CommandLine.Command(
        name = "list",
        description = "List available models"
    )
    static class ListCommand implements Callable<Integer> {

        @CommandLine.Option(
            names = {"--conversion-config"},
            description = "List models from conversion configuration file"
        )
        private String conversionConfigFile;

        @CommandLine.Option(
            names = {"--anserini"},
            description = "List available Anserini models"
        )
        private boolean anseriniModels = false;

        @CommandLine.Option(
            names = {"-d", "--detailed"},
            description = "Show detailed information"
        )
        private boolean detailed = false;

        @Override
        public Integer call() throws Exception {
            if (anseriniModels) {
                // List Anserini models
                AnseriniModelDownloader.printAvailableModels();
            } else if (conversionConfigFile != null) {
                // List models from conversion config
                try {
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    ConversionConfig config = yamlMapper.readValue(new File(conversionConfigFile), ConversionConfig.class);
                    
                    logger.info("Models defined in configuration:");
                    logger.info("Total: {}", config.getModels().size());
                    logger.info("");
                    
                    for (ModelDefinition model : config.getModels()) {
                        logger.info("• {} ({})", model.getModelId(), model.getModelType());
                        
                        if (detailed) {
                            logger.info("  Source: {}", model.getSourceModelUrl());
                            logger.info("  Target: {}", model.getTargetModelFilename());
                            if (model.getVocabUrl() != null) {
                                logger.info("  Vocab: {}", model.getVocabFilename());
                            }
                            logger.info("");
                        }
                    }
                    
                } catch (IOException e) {
                    logger.error("Failed to load conversion config", e);
                    return 1;
                }
            } else {
                // List both
                logger.info("=== Available Model Sources ===");
                logger.info("");
                
                logger.info("1. Anserini Models (use: download-anserini):");
                logger.info("   - Pre-configured ONNX models from Pyserini/Anserini");
                logger.info("   - Automatic download and conversion");
                logger.info("   - Models: bge-base-en-v1.5, cosdpr-distil, arctic-embed-l,");
                logger.info("            splade-pp-sd, splade-pp-ed, unicoil");
                logger.info("");
                
                logger.info("2. Custom Models (use: convert [config.yaml]):");
                logger.info("   - Models defined in YAML configuration");
                logger.info("   - Custom ONNX sources and conversion parameters");
                logger.info("   - Upload to GitHub releases");
                logger.info("");
                
                logger.info("Use --anserini to see detailed Anserini model list");
                logger.info("Use --conversion-config <file> to see models in config file");
            }
            
            return 0;
        }
    }
}
