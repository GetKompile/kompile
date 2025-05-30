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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Service for generating metadata and checksums for converted models.
 */
public class ModelMetadataGenerator {
    private static final Logger logger = LoggerFactory.getLogger(ModelMetadataGenerator.class);
    
    private final ObjectMapper yamlMapper;

    public ModelMetadataGenerator() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Generate comprehensive metadata for a converted model.
     */
    public ModelMetadata generateMetadata(String modelId, Path modelPath, Path vocabPath,
                                        ModelConverter.ConversionResult conversionResult) {
        logger.info("Generating metadata for model: {}", modelId);
        
        try {
            ModelMetadata metadata = new ModelMetadata();
            metadata.setModelId(modelId);
            metadata.setGeneratedAt(System.currentTimeMillis());
            
            // Model file information
            if (Files.exists(modelPath)) {
                metadata.setModelFileSize(Files.size(modelPath));
                metadata.setModelChecksum(calculateChecksum(modelPath));
                metadata.setModelFilename(modelPath.getFileName().toString());
            }
            
            // Vocabulary file information
            if (vocabPath != null && Files.exists(vocabPath)) {
                metadata.setVocabFileSize(Files.size(vocabPath));
                metadata.setVocabChecksum(calculateChecksum(vocabPath));
                metadata.setVocabFilename(vocabPath.getFileName().toString());
            }
            
            // Conversion information
            if (conversionResult != null && conversionResult.isSuccess()) {
                metadata.setConversionTimeMs(conversionResult.getConversionTimeMs());
                metadata.setVariableCount(conversionResult.getVariableCount());
                metadata.setOperationCount(conversionResult.getOperationCount());
            }
            
            // Additional metadata
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("kompile_version", "0.1.0-SNAPSHOT");
            additionalInfo.put("conversion_tool", "model-conversion-utility");
            additionalInfo.put("conversion_timestamp", System.currentTimeMillis());
            metadata.setAdditionalInfo(additionalInfo);
            
            logger.info("Generated metadata for {}: {} bytes, checksum: {}", 
                       modelId, metadata.getModelFileSize(), metadata.getModelChecksum());
            
            return metadata;
            
        } catch (Exception e) {
            logger.error("Failed to generate metadata for {}", modelId, e);
            throw new RuntimeException("Metadata generation failed", e);
        }
    }

    /**
     * Save metadata to a YAML file.
     */
    public void saveMetadata(ModelMetadata metadata, Path outputPath) throws IOException {
        logger.info("Saving metadata to: {}", outputPath);
        
        Files.createDirectories(outputPath.getParent());
        yamlMapper.writeValue(outputPath.toFile(), metadata);
        
        logger.info("Metadata saved to: {}", outputPath);
    }

    /**
     * Load metadata from a YAML file.
     */
    public ModelMetadata loadMetadata(Path metadataPath) throws IOException {
        if (!Files.exists(metadataPath)) {
            throw new IOException("Metadata file not found: " + metadataPath);
        }
        
        return yamlMapper.readValue(metadataPath.toFile(), ModelMetadata.class);
    }

    /**
     * Generate a checksum manifest for multiple files.
     */
    public Map<String, String> generateChecksumManifest(Map<String, Path> files) {
        Map<String, String> checksums = new HashMap<>();
        
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String filename = entry.getKey();
            Path filePath = entry.getValue();
            
            try {
                if (Files.exists(filePath)) {
                    String checksum = calculateChecksum(filePath);
                    checksums.put(filename, checksum);
                    logger.debug("Generated checksum for {}: {}", filename, checksum);
                }
            } catch (Exception e) {
                logger.warn("Failed to generate checksum for {}: {}", filename, e.getMessage());
            }
        }
        
        return checksums;
    }

    /**
     * Save checksum manifest to a file.
     */
    public void saveChecksumManifest(Map<String, String> checksums, Path outputPath) throws IOException {
        logger.info("Saving checksum manifest to: {}", outputPath);
        
        Files.createDirectories(outputPath.getParent());
        
        StringBuilder manifest = new StringBuilder();
        manifest.append("# SHA-256 Checksums\n");
        manifest.append("# Generated at: ").append(new java.util.Date()).append("\n\n");
        
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            manifest.append(entry.getValue()).append("  ").append(entry.getKey()).append("\n");
        }
        
        Files.write(outputPath, manifest.toString().getBytes());
        logger.info("Checksum manifest saved with {} entries", checksums.size());
    }

    /**
     * Verify a file against its expected checksum.
     */
    public boolean verifyChecksum(Path filePath, String expectedChecksum) {
        try {
            String actualChecksum = calculateChecksum(filePath);
            boolean matches = expectedChecksum.equalsIgnoreCase(actualChecksum);
            
            if (matches) {
                logger.debug("Checksum verified for: {}", filePath.getFileName());
            } else {
                logger.warn("Checksum mismatch for {}: expected {}, got {}", 
                           filePath.getFileName(), expectedChecksum, actualChecksum);
            }
            
            return matches;
        } catch (Exception e) {
            logger.error("Failed to verify checksum for {}: {}", filePath.getFileName(), e.getMessage());
            return false;
        }
    }

    private String calculateChecksum(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Metadata class for converted models.
     */
    public static class ModelMetadata {
        private String modelId;
        private long generatedAt;
        private String modelFilename;
        private long modelFileSize;
        private String modelChecksum;
        private String vocabFilename;
        private Long vocabFileSize;
        private String vocabChecksum;
        private Long conversionTimeMs;
        private Integer variableCount;
        private Integer operationCount;
        private Map<String, Object> additionalInfo;

        // Constructors
        public ModelMetadata() {}

        // Getters and Setters
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }

        public long getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }

        public String getModelFilename() { return modelFilename; }
        public void setModelFilename(String modelFilename) { this.modelFilename = modelFilename; }

        public long getModelFileSize() { return modelFileSize; }
        public void setModelFileSize(long modelFileSize) { this.modelFileSize = modelFileSize; }

        public String getModelChecksum() { return modelChecksum; }
        public void setModelChecksum(String modelChecksum) { this.modelChecksum = modelChecksum; }

        public String getVocabFilename() { return vocabFilename; }
        public void setVocabFilename(String vocabFilename) { this.vocabFilename = vocabFilename; }

        public Long getVocabFileSize() { return vocabFileSize; }
        public void setVocabFileSize(Long vocabFileSize) { this.vocabFileSize = vocabFileSize; }

        public String getVocabChecksum() { return vocabChecksum; }
        public void setVocabChecksum(String vocabChecksum) { this.vocabChecksum = vocabChecksum; }

        public Long getConversionTimeMs() { return conversionTimeMs; }
        public void setConversionTimeMs(Long conversionTimeMs) { this.conversionTimeMs = conversionTimeMs; }

        public Integer getVariableCount() { return variableCount; }
        public void setVariableCount(Integer variableCount) { this.variableCount = variableCount; }

        public Integer getOperationCount() { return operationCount; }
        public void setOperationCount(Integer operationCount) { this.operationCount = operationCount; }

        public Map<String, Object> getAdditionalInfo() { return additionalInfo; }
        public void setAdditionalInfo(Map<String, Object> additionalInfo) { this.additionalInfo = additionalInfo; }
    }
}
