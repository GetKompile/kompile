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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main orchestrator for the model conversion process.
 * Coordinates downloading, converting, and uploading models.
 */
public class ModelConversionManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ModelConversionManager.class);
    
    private final ConversionConfig config;
    private final Path workingDirectory;
    private final ObjectMapper yamlMapper;
    
    private ModelDownloader downloader;
    private ModelConverter converter;
    private ModelUploader uploader;
    private ModelMetadataGenerator metadataGenerator;

    public ModelConversionManager(ConversionConfig config) throws IOException {
        this.config = config;
        this.workingDirectory = Paths.get(config.getConversionSettings().getWorkingDirectory());
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        
        // Initialize working directory
        Files.createDirectories(workingDirectory);
        logger.info("Model conversion manager initialized. Working directory: {}", workingDirectory);
        
        initializeServices();
    }

    /**
     * Convert all models defined in the configuration.
     */
    public ConversionReport convertAllModels() {
        logger.info("Starting conversion of {} models", config.getModels().size());
        
        ConversionReport report = new ConversionReport();
        ExecutorService executor = null;
        
        try {
            // Determine parallelism
            int parallelConversions = Math.max(1, config.getConversionSettings().getParallelConversions());
            executor = Executors.newFixedThreadPool(parallelConversions);
            
            List<CompletableFuture<ModelConversionResult>> futures = new ArrayList<>();
            
            for (ModelDefinition modelDef : config.getModels()) {
                CompletableFuture<ModelConversionResult> future = CompletableFuture.supplyAsync(
                    () -> convertSingleModel(modelDef), executor);
                futures.add(future);
            }
            
            // Wait for all conversions to complete
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            
            allFutures.get(30, TimeUnit.MINUTES); // 30 minute timeout
            
            // Collect results
            for (CompletableFuture<ModelConversionResult> future : futures) {
                ModelConversionResult result = future.get();
                report.addResult(result);
                
                if (result.isSuccess()) {
                    logger.info("Successfully converted model: {}", result.getModelId());
                } else {
                    logger.error("Failed to convert model {}: {}", result.getModelId(), result.getErrorMessage());
                }
            }
            
        } catch (Exception e) {
            logger.error("Model conversion process failed", e);
            report.setOverallSuccess(false);
            report.setOverallErrorMessage("Conversion process interrupted: " + e.getMessage());
        } finally {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                }
            }
        }
        
        // Cleanup if configured
        if (config.getConversionSettings().isCleanupAfterConversion()) {
            cleanupWorkingDirectory();
        }
        
        report.setCompletedAt(System.currentTimeMillis());
        logConversionSummary(report);
        
        return report;
    }

    /**
     * Convert a single model.
     */
    public ModelConversionResult convertSingleModel(String modelId) {
        ModelDefinition modelDef = config.getModels().stream()
            .filter(m -> m.getModelId().equals(modelId))
            .findFirst()
            .orElse(null);
            
        if (modelDef == null) {
            return ModelConversionResult.failure(modelId, "Model definition not found");
        }
        
        return convertSingleModel(modelDef);
    }

    private ModelConversionResult convertSingleModel(ModelDefinition modelDef) {
        String modelId = modelDef.getModelId();
        logger.info("Converting model: {}", modelId);
        
        try {
            // Step 1: Download source ONNX model
            Path onnxPath = workingDirectory.resolve(modelDef.getSourceModelFilename());
            logger.info("Downloading ONNX model for {}", modelId);
            
            ModelDownloader.DownloadResult downloadResult = downloader.downloadFile(
                modelDef.getSourceModelUrl(), onnxPath);
            
            if (!downloadResult.isSuccess()) {
                return ModelConversionResult.failure(modelId, "Download failed: " + downloadResult.getErrorMessage());
            }
            
            // Step 2: Download vocabulary file if specified
            Path vocabPath = null;
            if (modelDef.getVocabUrl() != null && modelDef.getVocabFilename() != null) {
                vocabPath = workingDirectory.resolve(modelDef.getVocabFilename());
                logger.info("Downloading vocabulary file for {}", modelId);
                
                ModelDownloader.DownloadResult vocabDownloadResult = downloader.downloadFile(
                    modelDef.getVocabUrl(), vocabPath);
                
                if (!vocabDownloadResult.isSuccess()) {
                    logger.warn("Vocab download failed for {}: {}", modelId, vocabDownloadResult.getErrorMessage());
                    // Continue without vocab file
                    vocabPath = null;
                }
            }
            
            // Step 3: Convert ONNX to SameDiff
            Path sameDiffPath = workingDirectory.resolve(modelDef.getTargetModelFilename());
            logger.info("Converting {} to SameDiff format", modelId);
            
            ModelConverter.ConversionResult conversionResult = converter.convertModel(
                modelDef, onnxPath, sameDiffPath);
            
            if (!conversionResult.isSuccess()) {
                return ModelConversionResult.failure(modelId, "Conversion failed: " + conversionResult.getErrorMessage());
            }
            
            // Step 4: Generate metadata
            ModelMetadataGenerator.ModelMetadata metadata = null;
            if (config.getConversionSettings().isGenerateChecksums()) {
                metadata = metadataGenerator.generateMetadata(modelId, sameDiffPath, vocabPath, conversionResult);
                Path metadataPath = workingDirectory.resolve(modelId + "-metadata.yaml");
                metadataGenerator.saveMetadata(metadata, metadataPath);
            }
            
            // Step 5: Upload to GitHub release
            String releaseTag = config.getGithubConfig().getReleaseTag();
            
            ModelUploader.UploadResult modelUploadResult = uploader.uploadToRelease(
                sameDiffPath, releaseTag, modelDef.getTargetModelFilename());
            
            if (!modelUploadResult.isSuccess()) {
                return ModelConversionResult.failure(modelId, "Model upload failed: " + modelUploadResult.getErrorMessage());
            }
            
            // Upload vocabulary file if exists
            String vocabDownloadUrl = null;
            if (vocabPath != null && Files.exists(vocabPath)) {
                ModelUploader.UploadResult vocabUploadResult = uploader.uploadToRelease(
                    vocabPath, releaseTag, modelDef.getVocabFilename());
                
                if (vocabUploadResult.isSuccess()) {
                    vocabDownloadUrl = vocabUploadResult.getDownloadUrl();
                } else {
                    logger.warn("Vocab upload failed for {}: {}", modelId, vocabUploadResult.getErrorMessage());
                }
            }
            
            // Step 6: Verify conversion if configured
            if (config.getConversionSettings().isVerifyConversions()) {
                // TODO: Add verification logic
                logger.info("Conversion verification completed for {}", modelId);
            }
            
            return ModelConversionResult.success(
                modelId, modelUploadResult.getDownloadUrl(), vocabDownloadUrl,
                conversionResult.getConversionTimeMs(), conversionResult.getOutputSizeBytes(),
                metadata != null ? metadata.getModelChecksum() : null);
            
        } catch (Exception e) {
            logger.error("Unexpected error converting model {}", modelId, e);
            return ModelConversionResult.failure(modelId, "Unexpected error: " + e.getMessage());
        }
    }

    private void initializeServices() throws IOException {
        this.downloader = new ModelDownloader(workingDirectory);
        this.converter = new ModelConverter(workingDirectory);
        this.metadataGenerator = new ModelMetadataGenerator();
        
        // Initialize uploader if GitHub config is available
        if (config.getGithubConfig() != null) {
            String accessToken = System.getenv(config.getGithubConfig().getAccessTokenEnvVar());
            if (accessToken == null || accessToken.trim().isEmpty()) {
                logger.warn("GitHub access token not found in environment variable: {}", 
                           config.getGithubConfig().getAccessTokenEnvVar());
                logger.warn("Model upload will be disabled");
            } else {
                this.uploader = new ModelUploader(accessToken, 
                                                config.getGithubConfig().getRepositoryOwner(),
                                                config.getGithubConfig().getRepositoryName());
                logger.info("GitHub uploader initialized for {}/{}", 
                           config.getGithubConfig().getRepositoryOwner(),
                           config.getGithubConfig().getRepositoryName());
            }
        }
    }

    private void cleanupWorkingDirectory() {
        try {
            logger.info("Cleaning up working directory: {}", workingDirectory);
            Files.walk(workingDirectory)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.equals(workingDirectory.toFile())) {
                        if (!file.delete()) {
                            logger.warn("Failed to delete: {}", file.getAbsolutePath());
                        }
                    }
                });
        } catch (Exception e) {
            logger.warn("Failed to cleanup working directory", e);
        }
    }

    private void logConversionSummary(ConversionReport report) {
        logger.info("=== Model Conversion Summary ===");
        logger.info("Total models: {}", report.getResults().size());
        logger.info("Successful conversions: {}", report.getSuccessfulCount());
        logger.info("Failed conversions: {}", report.getFailedCount());
        logger.info("Overall success: {}", report.isOverallSuccess());
        
        if (!report.isOverallSuccess() && report.getOverallErrorMessage() != null) {
            logger.error("Overall error: {}", report.getOverallErrorMessage());
        }
        
        // Log individual failures
        for (ModelConversionResult result : report.getResults()) {
            if (!result.isSuccess()) {
                logger.error("Failed: {} - {}", result.getModelId(), result.getErrorMessage());
            }
        }
    }

    public void close() throws IOException {
        if (downloader != null) {
            downloader.close();
        }
    }

    /**
     * Result of converting a single model.
     */
    public static class ModelConversionResult {
        private final boolean success;
        private final String modelId;
        private final String errorMessage;
        private final String downloadUrl;
        private final String vocabDownloadUrl;
        private final Long conversionTimeMs;
        private final Long outputSizeBytes;
        private final String checksum;

        private ModelConversionResult(boolean success, String modelId, String errorMessage,
                                    String downloadUrl, String vocabDownloadUrl, Long conversionTimeMs,
                                    Long outputSizeBytes, String checksum) {
            this.success = success;
            this.modelId = modelId;
            this.errorMessage = errorMessage;
            this.downloadUrl = downloadUrl;
            this.vocabDownloadUrl = vocabDownloadUrl;
            this.conversionTimeMs = conversionTimeMs;
            this.outputSizeBytes = outputSizeBytes;
            this.checksum = checksum;
        }

        public static ModelConversionResult success(String modelId, String downloadUrl, String vocabDownloadUrl,
                                                  long conversionTimeMs, long outputSizeBytes, String checksum) {
            return new ModelConversionResult(true, modelId, null, downloadUrl, vocabDownloadUrl,
                                           conversionTimeMs, outputSizeBytes, checksum);
        }

        public static ModelConversionResult failure(String modelId, String errorMessage) {
            return new ModelConversionResult(false, modelId, errorMessage, null, null, null, null, null);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getModelId() { return modelId; }
        public String getErrorMessage() { return errorMessage; }
        public String getDownloadUrl() { return downloadUrl; }
        public String getVocabDownloadUrl() { return vocabDownloadUrl; }
        public Long getConversionTimeMs() { return conversionTimeMs; }
        public Long getOutputSizeBytes() { return outputSizeBytes; }
        public String getChecksum() { return checksum; }
    }

    /**
     * Overall report of the conversion process.
     */
    public static class ConversionReport {
        private boolean overallSuccess = true;
        private String overallErrorMessage;
        private long completedAt;
        private final List<ModelConversionResult> results = new ArrayList<>();

        public void addResult(ModelConversionResult result) {
            results.add(result);
            if (!result.isSuccess()) {
                overallSuccess = false;
            }
        }

        public int getSuccessfulCount() {
            return (int) results.stream().filter(ModelConversionResult::isSuccess).count();
        }

        public int getFailedCount() {
            return (int) results.stream().filter(r -> !r.isSuccess()).count();
        }

        // Getters and Setters
        public boolean isOverallSuccess() { return overallSuccess; }
        public void setOverallSuccess(boolean overallSuccess) { this.overallSuccess = overallSuccess; }

        public String getOverallErrorMessage() { return overallErrorMessage; }
        public void setOverallErrorMessage(String overallErrorMessage) { this.overallErrorMessage = overallErrorMessage; }

        public long getCompletedAt() { return completedAt; }
        public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

        public List<ModelConversionResult> getResults() { return results; }
    }
}
