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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import picocli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Command Line Interface for the Model Conversion Utility.
 */
@CommandLine.Command(
    name = "model-converter",
    description = "Convert ONNX models to SameDiff format and upload to GitHub releases",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = {
        ModelConversionCLI.ConvertCommand.class,
        ModelConversionCLI.ValidateCommand.class,
        ModelConversionCLI.ListCommand.class
    }
)
public class ModelConversionCLI implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(ModelConversionCLI.class);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ModelConversionCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // Print help if no subcommand is provided
        CommandLine.usage(this, System.out);
        return 0;
    }

    /**
     * Convert models subcommand.
     */
    @CommandLine.Command(
        name = "convert",
        description = "Convert models according to configuration file"
    )
    static class ConvertCommand implements Callable<Integer> {

        @CommandLine.Parameters(
            index = "0",
            description = "Configuration file (YAML format)"
        )
        private File configFile;

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

            logger.info("Model Conversion Utility v1.0.0");
            logger.info("Configuration file: {}", configFile.getAbsolutePath());

            // Load configuration
            ConversionConfig config = loadConfiguration(configFile);
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
                    if (model.getVocabUrl() != null) {
                        logger.info("    Vocab: {}", model.getVocabFilename());
                    }
                }
            }
            
            logger.info("\nDry run completed successfully");
            return 0;
        }
    }

    /**
     * Validate configuration subcommand.
     */
    @CommandLine.Command(
        name = "validate",
        description = "Validate configuration file and check source URLs"
    )
    static class ValidateCommand implements Callable<Integer> {

        @CommandLine.Parameters(
            index = "0",
            description = "Configuration file (YAML format)"
        )
        private File configFile;

        @CommandLine.Option(
            names = {"-u", "--check-urls"},
            description = "Check if source URLs are accessible"
        )
        private boolean checkUrls = false;

        @Override
        public Integer call() throws Exception {
            logger.info("Validating configuration: {}", configFile.getAbsolutePath());

            try {
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                ConversionConfig config = yamlMapper.readValue(configFile, ConversionConfig.class);
                
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

        private boolean validateConfiguration(ConversionConfig config, boolean checkUrls) throws IOException {
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
                
                String accessToken = System.getenv(config.getGithubConfig().getAccessTokenEnvVar());
                if (accessToken == null || accessToken.trim().isEmpty()) {
                    logger.warn("GitHub access token not found in environment variable: {}", 
                               config.getGithubConfig().getAccessTokenEnvVar());
                }
            }
            
            // Validate individual models
            ModelDownloader downloader = null;
            if (checkUrls) {
                downloader = new ModelDownloader(java.nio.file.Paths.get("."));
            }
            
            try {
                for (ModelDefinition model : config.getModels()) {
                    if (!validateModel(model, downloader)) {
                        isValid = false;
                    }
                }
            } finally {
                if (downloader != null) {
                    downloader.close();
                }
            }
            
            return isValid;
        }

        private boolean validateModel(ModelDefinition model, ModelDownloader downloader) {
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
            
            // Check URLs if requested
            if (downloader != null && model.getSourceModelUrl() != null) {
                if (!downloader.isUrlAccessible(model.getSourceModelUrl())) {
                    logger.error("Source URL not accessible for {}: {}", modelId, model.getSourceModelUrl());
                    isValid = false;
                } else {
                    logger.debug("Source URL accessible for {}", modelId);
                }
                
                if (model.getVocabUrl() != null) {
                    if (!downloader.isUrlAccessible(model.getVocabUrl())) {
                        logger.warn("Vocab URL not accessible for {}: {}", modelId, model.getVocabUrl());
                    } else {
                        logger.debug("Vocab URL accessible for {}", modelId);
                    }
                }
            }
            
            return isValid;
        }
    }

    /**
     * List models subcommand.
     */
    @CommandLine.Command(
        name = "list",
        description = "List models defined in configuration file"
    )
    static class ListCommand implements Callable<Integer> {

        @CommandLine.Parameters(
            index = "0",
            description = "Configuration file (YAML format)"
        )
        private File configFile;

        @CommandLine.Option(
            names = {"-d", "--detailed"},
            description = "Show detailed information for each model"
        )
        private boolean detailed = false;

        @Override
        public Integer call() throws Exception {
            try {
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                ConversionConfig config = yamlMapper.readValue(configFile, ConversionConfig.class);
                
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
                        if (model.getEncoderSettings() != null) {
                            logger.info("  Max seq length: {}", model.getEncoderSettings().getMaxSequenceLength());
                        }
                        logger.info("");
                    }
                }
                
                return 0;
                
            } catch (Exception e) {
                logger.error("Failed to list models", e);
                return 1;
            }
        }
    }
}
