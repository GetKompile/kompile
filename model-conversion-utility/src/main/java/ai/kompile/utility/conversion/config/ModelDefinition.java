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

package ai.kompile.utility.conversion.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single model to be converted.
 */
public class ModelDefinition {
    
    @JsonProperty("model_id")
    private String modelId;
    
    @JsonProperty("model_type")
    private ModelType modelType;
    
    @JsonProperty("source_model_url")
    private String sourceModelUrl;
    
    @JsonProperty("source_model_filename")
    private String sourceModelFilename;
    
    @JsonProperty("target_model_filename")
    private String targetModelFilename;
    
    @JsonProperty("vocab_url")
    private String vocabUrl;
    
    @JsonProperty("vocab_filename")
    private String vocabFilename;
    
    @JsonProperty("conversion_parameters")
    private ConversionParameters conversionParameters;
    
    @JsonProperty("tensor_mapping")
    private TensorMapping tensorMapping;
    
    @JsonProperty("encoder_settings")
    private EncoderSettings encoderSettings;
    
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    // Constructors
    public ModelDefinition() {}

    // Getters and Setters
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public ModelType getModelType() { return modelType; }
    public void setModelType(ModelType modelType) { this.modelType = modelType; }

    public String getSourceModelUrl() { return sourceModelUrl; }
    public void setSourceModelUrl(String sourceModelUrl) { this.sourceModelUrl = sourceModelUrl; }

    public String getSourceModelFilename() { return sourceModelFilename; }
    public void setSourceModelFilename(String sourceModelFilename) { this.sourceModelFilename = sourceModelFilename; }

    public String getTargetModelFilename() { return targetModelFilename; }
    public void setTargetModelFilename(String targetModelFilename) { this.targetModelFilename = targetModelFilename; }

    public String getVocabUrl() { return vocabUrl; }
    public void setVocabUrl(String vocabUrl) { this.vocabUrl = vocabUrl; }

    public String getVocabFilename() { return vocabFilename; }
    public void setVocabFilename(String vocabFilename) { this.vocabFilename = vocabFilename; }

    public ConversionParameters getConversionParameters() { return conversionParameters; }
    public void setConversionParameters(ConversionParameters conversionParameters) { this.conversionParameters = conversionParameters; }

    public TensorMapping getTensorMapping() { return tensorMapping; }
    public void setTensorMapping(TensorMapping tensorMapping) { this.tensorMapping = tensorMapping; }

    public EncoderSettings getEncoderSettings() { return encoderSettings; }
    public void setEncoderSettings(EncoderSettings encoderSettings) { this.encoderSettings = encoderSettings; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /**
     * Model types supported for conversion.
     */
    public enum ModelType {
        DENSE_ENCODER,
        SPARSE_ENCODER
    }

    /**
     * Parameters for ONNX to SameDiff conversion.
     */
    public static class ConversionParameters {
        @JsonProperty("suggest_dynamic_variables")
        private boolean suggestDynamicVariables = false;
        
        @JsonProperty("track_variable_changes")
        private boolean trackVariableChanges = false;
        
        @JsonProperty("dynamic_variables")
        private Map<String, List<Long>> dynamicVariables;

        public boolean isSuggestDynamicVariables() { return suggestDynamicVariables; }
        public void setSuggestDynamicVariables(boolean suggestDynamicVariables) { this.suggestDynamicVariables = suggestDynamicVariables; }

        public boolean isTrackVariableChanges() { return trackVariableChanges; }
        public void setTrackVariableChanges(boolean trackVariableChanges) { this.trackVariableChanges = trackVariableChanges; }

        public Map<String, List<Long>> getDynamicVariables() { return dynamicVariables; }
        public void setDynamicVariables(Map<String, List<Long>> dynamicVariables) { this.dynamicVariables = dynamicVariables; }
    }

    /**
     * Tensor name mappings for the converted model.
     */
    public static class TensorMapping {
        @JsonProperty("input_tensor_names")
        private List<String> inputTensorNames;
        
        @JsonProperty("output_tensor_names")
        private List<String> outputTensorNames;

        public List<String> getInputTensorNames() { return inputTensorNames; }
        public void setInputTensorNames(List<String> inputTensorNames) { this.inputTensorNames = inputTensorNames; }

        public List<String> getOutputTensorNames() { return outputTensorNames; }
        public void setOutputTensorNames(List<String> outputTensorNames) { this.outputTensorNames = outputTensorNames; }
    }

    /**
     * Encoder-specific settings.
     */
    public static class EncoderSettings {
        @JsonProperty("max_sequence_length")
        private int maxSequenceLength = 512;
        
        @JsonProperty("do_lowercase_and_strip_accents")
        private boolean doLowercaseAndStripAccents = true;
        
        @JsonProperty("add_special_tokens")
        private boolean addSpecialTokens = true;
        
        @JsonProperty("normalize_embeddings")
        private Boolean normalizeEmbeddings;
        
        @JsonProperty("instruction_prefix")
        private String instructionPrefix;
        
        @JsonProperty("weight_range")
        private Integer weightRange;
        
        @JsonProperty("quant_range")
        private Integer quantRange;

        public int getMaxSequenceLength() { return maxSequenceLength; }
        public void setMaxSequenceLength(int maxSequenceLength) { this.maxSequenceLength = maxSequenceLength; }

        public boolean isDoLowercaseAndStripAccents() { return doLowercaseAndStripAccents; }
        public void setDoLowercaseAndStripAccents(boolean doLowercaseAndStripAccents) { this.doLowercaseAndStripAccents = doLowercaseAndStripAccents; }

        public boolean isAddSpecialTokens() { return addSpecialTokens; }
        public void setAddSpecialTokens(boolean addSpecialTokens) { this.addSpecialTokens = addSpecialTokens; }

        public Boolean getNormalizeEmbeddings() { return normalizeEmbeddings; }
        public void setNormalizeEmbeddings(Boolean normalizeEmbeddings) { this.normalizeEmbeddings = normalizeEmbeddings; }

        public String getInstructionPrefix() { return instructionPrefix; }
        public void setInstructionPrefix(String instructionPrefix) { this.instructionPrefix = instructionPrefix; }

        public Integer getWeightRange() { return weightRange; }
        public void setWeightRange(Integer weightRange) { this.weightRange = weightRange; }

        public Integer getQuantRange() { return quantRange; }
        public void setQuantRange(Integer quantRange) { this.quantRange = quantRange; }
    }
}
