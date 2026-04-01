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

package ai.kompile.staging.tool;

import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.compiler.CompilerService;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.execution.ChatTemplateService;
import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.execution.PromptTemplateService;
import ai.kompile.staging.execution.TextPipelineService;
import ai.kompile.staging.execution.VlmExecutionService;
import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ImportService;
import ai.kompile.staging.optimization.ComparisonService;
import ai.kompile.staging.optimization.OptimizationService;
import ai.kompile.staging.pipeline.PipelineService;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.config.StagingSettings;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.training.*;
import ai.kompile.staging.web.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Tool for model staging operations.
 * Exposes the full model staging lifecycle: registry management, catalog browsing,
 * downloading, staging, conversion, promotion, optimization, comparison, compilation,
 * training, distillation, alignment, evaluation, PEFT, datasets, LLM/VLM execution,
 * and pipeline management.
 */
@Component("stagingModelStagingTool")
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class ModelStagingTool {

    private static final Logger logger = LoggerFactory.getLogger(ModelStagingTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RegistryService registryService;
    private final StagingService stagingService;
    private final CatalogService catalogService;
    private final ExportService exportService;
    private final ImportService importService;
    private final ConversionService conversionService;
    private final OptimizationService optimizationService;
    private final ComparisonService comparisonService;
    private final StagingSettingsService settingsService;
    private final CompilerService compilerService;
    private final TrainingService trainingService;
    private final DistillationService distillationService;
    private final AlignmentService alignmentService;
    private final EvaluationService evaluationService;
    private final PeftService peftService;
    private final DatasetService datasetService;
    private final LlmExecutionService llmExecutionService;
    private final ChatTemplateService chatTemplateService;
    private final PromptTemplateService promptTemplateService;
    private final TextPipelineService textPipelineService;
    private final VlmExecutionService vlmExecutionService;
    private final PipelineService pipelineService;

    @Autowired
    public ModelStagingTool(RegistryService registryService,
                            StagingService stagingService,
                            CatalogService catalogService,
                            ExportService exportService,
                            ImportService importService,
                            ConversionService conversionService,
                            OptimizationService optimizationService,
                            ComparisonService comparisonService,
                            StagingSettingsService settingsService,
                            CompilerService compilerService,
                            TrainingService trainingService,
                            DistillationService distillationService,
                            AlignmentService alignmentService,
                            EvaluationService evaluationService,
                            PeftService peftService,
                            DatasetService datasetService,
                            LlmExecutionService llmExecutionService,
                            ChatTemplateService chatTemplateService,
                            PromptTemplateService promptTemplateService,
                            TextPipelineService textPipelineService,
                            VlmExecutionService vlmExecutionService,
                            PipelineService pipelineService) {
        this.registryService = registryService;
        this.stagingService = stagingService;
        this.catalogService = catalogService;
        this.exportService = exportService;
        this.importService = importService;
        this.conversionService = conversionService;
        this.optimizationService = optimizationService;
        this.comparisonService = comparisonService;
        this.settingsService = settingsService;
        this.compilerService = compilerService;
        this.trainingService = trainingService;
        this.distillationService = distillationService;
        this.alignmentService = alignmentService;
        this.evaluationService = evaluationService;
        this.peftService = peftService;
        this.datasetService = datasetService;
        this.llmExecutionService = llmExecutionService;
        this.chatTemplateService = chatTemplateService;
        this.promptTemplateService = promptTemplateService;
        this.textPipelineService = textPipelineService;
        this.vlmExecutionService = vlmExecutionService;
        this.pipelineService = pipelineService;
        logger.info("ModelStagingTool initialized with all services");
    }

    // ==================== Input Records ====================

    public record ListRegistryModelsInput(String type) {}
    public record GetModelInput(String modelId) {}
    public record DeleteModelInput(String modelId, Boolean deleteFiles) {}
    public record ActivateModelInput(String modelId) {}
    public record UpdateModelInput(String modelId, String type, String status,
                                   // Core metadata
                                   Integer embeddingDim, Integer hiddenSize, Integer numLayers,
                                   Integer maxSequenceLength, String description, String framework,
                                   Integer vocabSize, String trainingData,
                                   String sourceOrigin, String sourceRepository,
                                   // Pipeline identity
                                   String encoderType, String ragRole, String version,
                                   // OCR-specific
                                   Integer inputHeight, Integer inputWidth,
                                   String supportedLanguages, // comma-separated
                                   Boolean supportsBatch, Integer maxBatchSize,
                                   Boolean supportsHandwriting, Double averageAccuracy,
                                   Integer ocrVocabSize, Boolean usesCtc,
                                   // VLM-specific
                                   Integer visionFrames, Integer imageSize, Integer tileSize,
                                   String components, // comma-separated
                                   // Vision encoder IO config
                                   String visionEncoderPixelValuesName,
                                   String visionEncoderPixelAttentionMaskName,
                                   String visionEncoderPrimaryOutputName,
                                   String visionEncoderOutputNames, // comma-separated
                                   // Tokenizer config
                                   Boolean doLowerCase, Boolean addSpecialTokens, Boolean stripAccents,
                                   Integer maxTokenLength, String padding, Boolean truncation,
                                   // Image preprocessor config
                                   String imageProcessorType,
                                   Boolean doResize, Integer sizeHeight, Integer sizeWidth,
                                   Integer sizeShortestEdge, Integer sizeLongestEdge, Integer resample,
                                   Boolean doRescale, Double rescaleFactor,
                                   Boolean doNormalize, String imageMean, String imageStd, // comma-separated doubles
                                   Boolean doConvertRgb, Boolean doCenterCrop,
                                   Integer cropSizeHeight, Integer cropSizeWidth,
                                   Boolean doPad, Integer padSizeHeight, Integer padSizeWidth,
                                   Integer patchSize, Integer numChannels) {}
    public record StageFromCatalogInput(String modelId) {}
    public record StageFromSourceInput(String source, String repository, String modelId,
                                       String format, String modelType, String revision, String authToken) {}
    public record StageLocalModelInput(String modelId, String filePath, String format, Boolean autoPromote) {}
    public record PromoteModelInput(String modelId) {}
    public record GetStagingStatusInput(String modelId) {}
    public record CancelStagingInput(String modelId) {}
    public record ExportModelsInput(String modelIds, String outputPath) {}
    public record ImportBundleInput(String bundlePath, Boolean verifyChecksums) {}
    public record ConvertModelInput(String inputPath, String outputPath, String format) {}
    public record ValidateModelInput(String modelPath) {}
    public record GetSettingsInput() {}
    public record UpdateSettingsInput(String callbackUrl, Boolean autoReloadEnabled,
                                      Integer callbackTimeoutMs, Boolean optimizerEnabled,
                                      Boolean optimizerFp16Enabled, String defaultOptimizationProfile) {}

    // --- Optimization Input Records ---
    public record OptimizeModelInput(String modelId, String optimizations, String quantizationType,
                                     Boolean quantizePerChannel, Boolean createBackup, Boolean force) {}
    public record CompareModelsInput(String modelId, String sampleText, Integer sequenceLength) {}

    // --- Compiler Input Records ---
    public record CompilerOptimizeInput(String modelId, String selectedPasses, Integer maxIterations,
                                        String profile, String outputModelId, String quantizationType,
                                        Boolean force, Boolean createBackup, Boolean dryRun) {}
    public record GraphInfoInput(String modelId) {}
    public record CompareGraphsInput(String model1Id, String model2Id) {}
    public record TritonCompileInput(String modelId, Integer numWarps, Integer numStages,
                                      Integer numCTAs, Boolean fpFusion, String arch) {}
    public record SaveCompiledInput(String sourceModelId, String outputModelId, String selectedPasses,
                                     String profile, Integer maxIterations, String saveFormat, String description) {}
    public record ClearCacheInput(String cacheType) {}
    public record StartCompilationInput(String modelId, String compilationMode, String executionMode,
                                         String selectedPasses, String profile, Integer maxIterations,
                                         Boolean createBackup, String outputModelId) {}
    public record CancelJobInput(String jobId) {}

    // --- Training Input Records ---
    public record StartTrainingInput(String modelId, String datasetId, String peftType,
                                      String lrSchedule, Integer epochs, Integer batchSize,
                                      Boolean fp16, Integer loggingSteps, Integer saveSteps, Integer seed) {}
    public record GetJobInput(String jobId) {}

    // --- Distillation Input Records ---
    public record StartDistillationInput(String teacherModelId, String studentModelId,
                                          String distillationType, Double temperature, Double alpha,
                                          String datasetId, Integer epochs, Integer batchSize) {}

    // --- Alignment Input Records ---
    public record StartAlignmentInput(String algorithm, String baseModelId, String rewardModelId,
                                       String datasetId, Double beta, Integer maxPromptLength,
                                       Integer maxCompletionLength, Integer epochs, Integer batchSize) {}

    // --- Evaluation Input Records ---
    public record EvaluateModelInput(String modelId, String datasetId, String metrics,
                                      Integer batchSize, Integer maxSamples) {}
    public record GetEvaluationInput(String evaluationId) {}

    // --- PEFT Input Records ---
    public record CreatePeftInput(String modelId, String peftType, Integer loraRank, Double loraAlpha,
                                   Double loraDropout, String targetModules) {}
    public record MergePeftInput(String peftModelId) {}
    public record GetPeftInfoInput(String modelId) {}

    // --- Dataset Input Records ---
    public record GetDatasetInput(String id) {}
    public record DeleteDatasetInput(String id) {}
    public record PreviewDatasetInput(String id, Integer rows) {}
    public record ComputeDatasetStatsInput(String id) {}

    // --- LLM Execution Input Records ---
    public record LlmLoadInput(String modelId, String modelPath, String kvCacheType) {}
    public record LlmGenerateInput(String prompt, Integer maxTokens, Double temperature,
                                    Integer topK, Double topP, Double repetitionPenalty,
                                    Boolean doSample, String presetName) {}
    public record LlmSpeculativeConfigInput(Boolean enabled, Integer ngramSize,
                                             Integer maxSpeculativeTokens, Boolean useDraftModel,
                                             String draftModelId) {}

    // --- VLM Execution Input Records ---
    public record VlmLoadInput(String modelSetId) {}
    public record VlmGenerateInput(String prompt, Integer maxTokens, Boolean tilingEnabled,
                                    Integer maxTiles, String modelSetId) {}
    public record VlmOcrConfigInput(String engineType, String language, Double confidenceThreshold) {}

    // --- Pipeline Input Records ---
    public record PipelineDownloadInput(String repoId, String revision, String authToken) {}
    public record PipelineInspectInput(String modelPath) {}
    public record PipelineCacheInput(String modelId) {}

    // ==================== Registry Operations ====================

    @Tool(name = "staging_list_models",
            description = "Lists all models in the staging registry. Optionally filter by type: " +
                    "'dense_encoder', 'sparse_encoder', 'cross_encoder', 'ocr_detection', 'ocr_recognition', " +
                    "'ocr_table', 'layout_model', 'ocr_pipeline', 'document_classifier', 'llm_ggml', " +
                    "'vlm_vision_encoder', 'vlm_decoder', 'vlm_embed_tokens', 'vlm_pipeline'. " +
                    "Returns model IDs, types, statuses, and metadata.")
    public Map<String, Object> listRegistryModels(ListRegistryModelsInput input) {
        logger.info("Listing registry models with filter: {}", input.type());
        try {
            ModelRegistry registry = registryService.loadRegistry();
            List<Map<String, Object>> modelList;

            if (input.type() != null && !input.type().isEmpty()) {
                ModelType modelType = ModelType.fromValue(input.type());
                List<ModelEntry> entries = registryService.getModelsByType(modelType);
                modelList = entries.stream().map(this::modelEntryToMap).collect(Collectors.toList());
            } else {
                Map<String, ModelEntry> models = registry.getModels();
                modelList = models != null
                        ? models.values().stream().map(this::modelEntryToMap).collect(Collectors.toList())
                        : Collections.emptyList();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("totalModels", modelList.size());
            result.put("models", modelList);
            return result;
        } catch (Exception e) {
            logger.error("Error listing registry models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list models: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_model",
            description = "Gets detailed information about a specific model in the staging registry by its model ID. " +
                    "Returns full metadata, tokenizer config, status, and file paths.")
    public Map<String, Object> getModel(GetModelInput input) {
        logger.info("Getting model details: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            return registryService.getModel(input.modelId())
                    .map(entry -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.put("model", modelEntryToMap(entry));
                        return result;
                    })
                    .orElse(Map.of("status", "error", "error", "Model not found: " + input.modelId()));
        } catch (Exception e) {
            logger.error("Error getting model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_update_model",
            description = "Updates a model entry in the registry. You can update the type, status, metadata " +
                    "(embeddingDim, hiddenSize, numLayers, maxSequenceLength, description, framework, vocabSize, " +
                    "trainingData, sourceOrigin, sourceRepository, encoderType, ragRole, version), " +
                    "tokenizer config (doLowerCase, addSpecialTokens, stripAccents, maxTokenLength, padding, truncation), " +
                    "and image preprocessor config (imageProcessorType, doResize, sizeHeight, sizeWidth, doRescale, " +
                    "rescaleFactor, doNormalize, imageMean, imageStd, doCenterCrop, cropSizeHeight, cropSizeWidth, " +
                    "doPad, padSizeHeight, padSizeWidth, patchSize, numChannels). Only non-null fields are updated. " +
                    "For OCR models: inputHeight, inputWidth, supportedLanguages (comma-separated), supportsBatch, " +
                    "maxBatchSize, supportsHandwriting, averageAccuracy, ocrVocabSize, usesCtc. " +
                    "For VLM models: visionFrames, imageSize, tileSize, components (comma-separated), " +
                    "visionEncoderPixelValuesName, visionEncoderPixelAttentionMaskName, " +
                    "visionEncoderPrimaryOutputName, visionEncoderOutputNames (comma-separated).")
    public Map<String, Object> updateModel(UpdateModelInput input) {
        logger.info("Updating model: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            ModelEntry.ModelEntryBuilder updateBuilder = ModelEntry.builder().modelId(input.modelId());

            if (input.type() != null) {
                updateBuilder.type(ModelType.fromValue(input.type()));
            }
            if (input.status() != null) {
                updateBuilder.status(ModelStatus.fromValue(input.status()));
            }

            // Build metadata from all available fields
            ModelMetadata.ModelMetadataBuilder metaBuilder = ModelMetadata.builder();
            boolean hasMetadata = false;
            // Core metadata
            if (input.embeddingDim() != null) { metaBuilder.embeddingDim(input.embeddingDim()); hasMetadata = true; }
            if (input.hiddenSize() != null) { metaBuilder.hiddenSize(input.hiddenSize()); hasMetadata = true; }
            if (input.numLayers() != null) { metaBuilder.numLayers(input.numLayers()); hasMetadata = true; }
            if (input.maxSequenceLength() != null) { metaBuilder.maxSequenceLength(input.maxSequenceLength()); hasMetadata = true; }
            if (input.description() != null) { metaBuilder.description(input.description()); hasMetadata = true; }
            if (input.framework() != null) { metaBuilder.framework(input.framework()); hasMetadata = true; }
            if (input.vocabSize() != null) { metaBuilder.vocabSize(input.vocabSize()); hasMetadata = true; }
            if (input.trainingData() != null) { metaBuilder.trainingData(input.trainingData()); hasMetadata = true; }
            if (input.sourceOrigin() != null) { metaBuilder.sourceOrigin(input.sourceOrigin()); hasMetadata = true; }
            if (input.sourceRepository() != null) { metaBuilder.sourceRepository(input.sourceRepository()); hasMetadata = true; }
            // Pipeline identity
            if (input.encoderType() != null) { metaBuilder.encoderType(input.encoderType()); hasMetadata = true; }
            if (input.ragRole() != null) { metaBuilder.ragRole(input.ragRole()); hasMetadata = true; }
            if (input.version() != null) { metaBuilder.version(input.version()); hasMetadata = true; }
            // OCR-specific
            if (input.inputHeight() != null) { metaBuilder.inputHeight(input.inputHeight()); hasMetadata = true; }
            if (input.inputWidth() != null) { metaBuilder.inputWidth(input.inputWidth()); hasMetadata = true; }
            if (input.supportedLanguages() != null) {
                metaBuilder.supportedLanguages(java.util.Arrays.asList(input.supportedLanguages().split("\\s*,\\s*")));
                hasMetadata = true;
            }
            if (input.supportsBatch() != null) { metaBuilder.supportsBatch(input.supportsBatch()); hasMetadata = true; }
            if (input.maxBatchSize() != null) { metaBuilder.maxBatchSize(input.maxBatchSize()); hasMetadata = true; }
            if (input.supportsHandwriting() != null) { metaBuilder.supportsHandwriting(input.supportsHandwriting()); hasMetadata = true; }
            if (input.averageAccuracy() != null) { metaBuilder.averageAccuracy(input.averageAccuracy()); hasMetadata = true; }
            if (input.ocrVocabSize() != null) { metaBuilder.ocrVocabSize(input.ocrVocabSize()); hasMetadata = true; }
            if (input.usesCtc() != null) { metaBuilder.usesCtc(input.usesCtc()); hasMetadata = true; }
            // VLM-specific
            if (input.visionFrames() != null) { metaBuilder.visionFrames(input.visionFrames()); hasMetadata = true; }
            if (input.imageSize() != null) { metaBuilder.imageSize(input.imageSize()); hasMetadata = true; }
            if (input.tileSize() != null) { metaBuilder.tileSize(input.tileSize()); hasMetadata = true; }
            if (input.components() != null) {
                metaBuilder.components(java.util.Arrays.asList(input.components().split("\\s*,\\s*")));
                hasMetadata = true;
            }
            // Vision encoder IO
            if (input.visionEncoderPixelValuesName() != null) { metaBuilder.visionEncoderPixelValuesName(input.visionEncoderPixelValuesName()); hasMetadata = true; }
            if (input.visionEncoderPixelAttentionMaskName() != null) { metaBuilder.visionEncoderPixelAttentionMaskName(input.visionEncoderPixelAttentionMaskName()); hasMetadata = true; }
            if (input.visionEncoderPrimaryOutputName() != null) { metaBuilder.visionEncoderPrimaryOutputName(input.visionEncoderPrimaryOutputName()); hasMetadata = true; }
            if (input.visionEncoderOutputNames() != null) {
                metaBuilder.visionEncoderOutputNames(java.util.Arrays.asList(input.visionEncoderOutputNames().split("\\s*,\\s*")));
                hasMetadata = true;
            }
            if (hasMetadata) {
                updateBuilder.metadata(metaBuilder.build());
            }

            // Build tokenizer config
            if (input.doLowerCase() != null || input.maxTokenLength() != null ||
                    input.addSpecialTokens() != null || input.stripAccents() != null ||
                    input.padding() != null || input.truncation() != null) {
                TokenizerConfig.TokenizerConfigBuilder tokBuilder = TokenizerConfig.builder();
                if (input.doLowerCase() != null) tokBuilder.doLowerCase(input.doLowerCase());
                if (input.addSpecialTokens() != null) tokBuilder.addSpecialTokens(input.addSpecialTokens());
                if (input.stripAccents() != null) tokBuilder.stripAccents(input.stripAccents());
                if (input.maxTokenLength() != null) tokBuilder.maxLength(input.maxTokenLength());
                if (input.padding() != null) tokBuilder.padding(input.padding());
                if (input.truncation() != null) tokBuilder.truncation(input.truncation());
                updateBuilder.tokenizer(tokBuilder.build());
            }

            // Build image preprocessor config
            if (input.imageProcessorType() != null || input.doResize() != null ||
                    input.sizeHeight() != null || input.sizeWidth() != null ||
                    input.doRescale() != null || input.rescaleFactor() != null ||
                    input.doNormalize() != null || input.imageMean() != null || input.imageStd() != null ||
                    input.doConvertRgb() != null || input.doCenterCrop() != null ||
                    input.cropSizeHeight() != null || input.cropSizeWidth() != null ||
                    input.doPad() != null || input.padSizeHeight() != null || input.padSizeWidth() != null ||
                    input.patchSize() != null || input.numChannels() != null ||
                    input.sizeShortestEdge() != null || input.sizeLongestEdge() != null ||
                    input.resample() != null) {
                ImagePreprocessorConfig.ImagePreprocessorConfigBuilder ppBuilder = ImagePreprocessorConfig.builder();
                if (input.imageProcessorType() != null) ppBuilder.imageProcessorType(input.imageProcessorType());
                if (input.doResize() != null) ppBuilder.doResize(input.doResize());
                if (input.sizeHeight() != null) ppBuilder.sizeHeight(input.sizeHeight());
                if (input.sizeWidth() != null) ppBuilder.sizeWidth(input.sizeWidth());
                if (input.sizeShortestEdge() != null) ppBuilder.sizeShortestEdge(input.sizeShortestEdge());
                if (input.sizeLongestEdge() != null) ppBuilder.sizeLongestEdge(input.sizeLongestEdge());
                if (input.resample() != null) ppBuilder.resample(input.resample());
                if (input.doRescale() != null) ppBuilder.doRescale(input.doRescale());
                if (input.rescaleFactor() != null) ppBuilder.rescaleFactor(input.rescaleFactor());
                if (input.doNormalize() != null) ppBuilder.doNormalize(input.doNormalize());
                if (input.imageMean() != null) ppBuilder.imageMean(parseDoubleArray(input.imageMean()));
                if (input.imageStd() != null) ppBuilder.imageStd(parseDoubleArray(input.imageStd()));
                if (input.doConvertRgb() != null) ppBuilder.doConvertRgb(input.doConvertRgb());
                if (input.doCenterCrop() != null) ppBuilder.doCenterCrop(input.doCenterCrop());
                if (input.cropSizeHeight() != null) ppBuilder.cropSizeHeight(input.cropSizeHeight());
                if (input.cropSizeWidth() != null) ppBuilder.cropSizeWidth(input.cropSizeWidth());
                if (input.doPad() != null) ppBuilder.doPad(input.doPad());
                if (input.padSizeHeight() != null) ppBuilder.padSizeHeight(input.padSizeHeight());
                if (input.padSizeWidth() != null) ppBuilder.padSizeWidth(input.padSizeWidth());
                if (input.patchSize() != null) ppBuilder.patchSize(input.patchSize());
                if (input.numChannels() != null) ppBuilder.numChannels(input.numChannels());
                updateBuilder.preprocessor(ppBuilder.build());
            }

            return registryService.updateModel(input.modelId(), updateBuilder.build())
                    .map(updated -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.put("message", "Model updated successfully");
                        result.put("model", modelEntryToMap(updated));
                        return result;
                    })
                    .orElse(Map.of("status", "error", "error", "Model not found: " + input.modelId()));
        } catch (Exception e) {
            logger.error("Error updating model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to update model: " + e.getMessage());
        }
    }

    private double[] parseDoubleArray(String commaSeparated) {
        String[] parts = commaSeparated.split("\\s*,\\s*");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
        }
        return result;
    }

    @Tool(name = "staging_delete_model",
            description = "Deletes a model from the registry. Set deleteFiles=true (default) to also remove " +
                    "model files from disk. Returns details about what was deleted.")
    public Map<String, Object> deleteModel(DeleteModelInput input) {
        logger.info("Deleting model: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            boolean deleteFiles = input.deleteFiles() == null || input.deleteFiles();
            RegistryService.DeleteResult result = registryService.deleteModelCompletely(input.modelId(), deleteFiles);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("modelId", result.getModelId());
            response.put("message", result.getMessage());
            response.put("registryRemoved", result.isRegistryRemoved());
            response.put("filesDeleted", result.isFilesDeleted());
            return response;
        } catch (Exception e) {
            logger.error("Error deleting model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to delete model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_activate_model",
            description = "Activates a model in the registry, making it the active model for its type. " +
                    "Only one model per type can be active at a time. The previously active model of " +
                    "the same type will be set to 'available'.")
    public Map<String, Object> activateModel(ActivateModelInput input) {
        logger.info("Activating model: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            ModelRegistry registry = registryService.loadRegistry();
            registry.setActiveModel(input.modelId());
            registryService.saveRegistry(registry);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Model activated: " + input.modelId());
            result.put("modelId", input.modelId());
            return result;
        } catch (Exception e) {
            logger.error("Error activating model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to activate model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_active_models",
            description = "Gets all currently active models grouped by type. Returns the active encoder, " +
                    "cross-encoder, sparse encoder, OCR models, LLM, and VLM models.")
    public Map<String, Object> getActiveModels(ListRegistryModelsInput input) {
        logger.info("Getting active models");
        try {
            ModelRegistry registry = registryService.loadRegistry();
            Map<String, Object> activeModels = new LinkedHashMap<>();

            List<ModelEntry> activeEncoders = registry.getActiveEncoders();
            if (!activeEncoders.isEmpty()) {
                activeModels.put("encoders", activeEncoders.stream()
                        .map(this::modelEntryToMap).collect(Collectors.toList()));
            }

            List<ModelEntry> activeOcr = registry.getActiveOcrModels();
            if (!activeOcr.isEmpty()) {
                activeModels.put("ocrModels", activeOcr.stream()
                        .map(this::modelEntryToMap).collect(Collectors.toList()));
            }

            List<ModelEntry> allVlm = registry.getAllVlmModels();
            if (!allVlm.isEmpty()) {
                activeModels.put("vlmModels", allVlm.stream()
                        .map(this::modelEntryToMap).collect(Collectors.toList()));
            }

            // Gather all active models across all types
            if (registry.getModels() != null) {
                List<Map<String, Object>> allActive = registry.getModels().values().stream()
                        .filter(ModelEntry::isActive)
                        .map(this::modelEntryToMap)
                        .collect(Collectors.toList());
                activeModels.put("allActive", allActive);
                activeModels.put("totalActive", allActive.size());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(activeModels);
            return result;
        } catch (Exception e) {
            logger.error("Error getting active models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get active models: " + e.getMessage());
        }
    }

    // ==================== Catalog Operations ====================

    @Tool(name = "staging_list_catalog",
            description = "Lists all models available in the catalog for download. " +
                    "The catalog contains pre-configured model sources from HuggingFace and other repositories.")
    public Map<String, Object> listCatalog(ListRegistryModelsInput input) {
        logger.info("Listing catalog models");
        try {
            List<CatalogModel> models = new ArrayList<>();
            models.addAll(catalogService.getEncoders());
            models.addAll(catalogService.getCrossEncoders());
            models.addAll(catalogService.getVlm());
            List<Map<String, Object>> catalogList = models.stream()
                    .map(this::catalogModelToMap)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("totalModels", catalogList.size());
            result.put("models", catalogList);
            return result;
        } catch (Exception e) {
            logger.error("Error listing catalog: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list catalog: " + e.getMessage());
        }
    }

    // ==================== Staging Operations ====================

    @Tool(name = "staging_stage_from_catalog",
            description = "Stages a model from the catalog by model ID. This downloads the model from its " +
                    "configured source, converts it to SameDiff format, and prepares it for promotion. " +
                    "The model ID must match an entry in the catalog (use staging_list_catalog to see available models).")
    public Map<String, Object> stageFromCatalog(StageFromCatalogInput input) {
        logger.info("Staging model from catalog: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            Optional<CatalogModel> optModel = catalogService.getModel(input.modelId());
            if (optModel.isEmpty()) {
                return Map.of("status", "error", "error", "Model not found in catalog: " + input.modelId());
            }
            CatalogModel catalogModel = optModel.get();

            DownloadRequest request = DownloadRequest.builder()
                    .source(catalogModel.getSource())
                    .repository(catalogModel.getRepo())
                    .modelId(catalogModel.getId())
                    .format(catalogModel.getFormat())
                    .files(catalogModel.getFiles() != null ? catalogModel.getFiles() : new HashMap<>())
                    .build();

            StagingModelInfo stagingInfo = stagingService.stageModel(request);
            return stagingModelInfoToMap(stagingInfo);
        } catch (Exception e) {
            logger.error("Error staging from catalog: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to stage model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_stage_from_source",
            description = "Stages a model from an external source. Provide source ('huggingface', 'github', or 'http'), " +
                    "repository path, and model ID. For HuggingFace, repository is like 'BAAI/bge-base-en-v1.5'. " +
                    "Format can be 'onnx', 'tensorflow', 'keras', 'safetensors', 'gguf', or 'auto'. " +
                    "ModelType: 'dense_encoder', 'sparse_encoder', 'cross_encoder', 'llm_ggml', etc.")
    public Map<String, Object> stageFromSource(StageFromSourceInput input) {
        logger.info("Staging model from source: {} repo={}", input.source(), input.repository());
        if (input.source() == null || input.repository() == null || input.modelId() == null) {
            return Map.of("status", "error", "error", "source, repository, and modelId are required");
        }
        try {
            DownloadRequest.DownloadRequestBuilder builder = DownloadRequest.builder()
                    .source(input.source())
                    .repository(input.repository())
                    .modelId(input.modelId())
                    .format(input.format() != null ? input.format() : "auto");

            if (input.modelType() != null) {
                builder.modelType(ModelType.fromValue(input.modelType()));
            }
            if (input.revision() != null) {
                builder.revision(input.revision());
            }
            if (input.authToken() != null) {
                builder.authToken(input.authToken());
            }

            StagingModelInfo stagingInfo = stagingService.stageModel(builder.build());
            return stagingModelInfoToMap(stagingInfo);
        } catch (Exception e) {
            logger.error("Error staging from source: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to stage model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_stage_local_model",
            description = "Stages a local model file (ONNX, TensorFlow, GGUF, etc.) into the registry. " +
                    "Provide the file path, format, and model ID. Set autoPromote=true to automatically " +
                    "promote the model after successful staging.")
    public Map<String, Object> stageLocalModel(StageLocalModelInput input) {
        logger.info("Staging local model: {} from {}", input.modelId(), input.filePath());
        if (input.modelId() == null || input.filePath() == null) {
            return Map.of("status", "error", "error", "modelId and filePath are required");
        }
        try {
            boolean autoPromote = input.autoPromote() != null && input.autoPromote();
            StagingModelInfo stagingInfo = stagingService.stageLocalModel(
                    input.modelId(), input.filePath(),
                    input.format() != null ? input.format() : "auto",
                    autoPromote);
            return stagingModelInfoToMap(stagingInfo);
        } catch (Exception e) {
            logger.error("Error staging local model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to stage local model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_promote_model",
            description = "Promotes a staged model to the registry, making it available for use. " +
                    "The model must have been previously staged. This moves it from the staging area " +
                    "to the model directory and registers it.")
    public Map<String, Object> promoteModel(PromoteModelInput input) {
        logger.info("Promoting model: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            boolean success = stagingService.promoteModel(input.modelId(), null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", success ? "success" : "error");
            result.put("modelId", input.modelId());
            result.put("message", success ? "Model promoted successfully" : "Failed to promote model");
            return result;
        } catch (Exception e) {
            logger.error("Error promoting model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to promote model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_status",
            description = "Gets the staging status for a model or all staging operations. " +
                    "If modelId is provided, returns that model's staging status. " +
                    "If modelId is empty, returns all active staging operations.")
    public Map<String, Object> getStagingStatus(GetStagingStatusInput input) {
        logger.info("Getting staging status for: {}", input.modelId());
        try {
            if (input.modelId() != null && !input.modelId().isEmpty()) {
                StagingModelInfo info = stagingService.getStagingModel(input.modelId());
                if (info == null) {
                    return Map.of("status", "error", "error", "No staging info found for: " + input.modelId());
                }
                return stagingModelInfoToMap(info);
            } else {
                List<StagingModelInfo> allStaging = stagingService.getStagingModels();
                List<Map<String, Object>> stagingList = allStaging.stream()
                        .map(this::stagingModelInfoToMap)
                        .collect(Collectors.toList());

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "success");
                result.put("totalStaging", stagingList.size());
                result.put("stagingModels", stagingList);
                return result;
            }
        } catch (Exception e) {
            logger.error("Error getting staging status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get staging status: " + e.getMessage());
        }
    }

    @Tool(name = "staging_cancel",
            description = "Cancels an active staging operation for the specified model.")
    public Map<String, Object> cancelStaging(CancelStagingInput input) {
        logger.info("Cancelling staging for: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            boolean cancelled = stagingService.cancelStaging(input.modelId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", cancelled ? "success" : "error");
            result.put("modelId", input.modelId());
            result.put("message", cancelled ? "Staging cancelled" : "Could not cancel staging (not found or already complete)");
            return result;
        } catch (Exception e) {
            logger.error("Error cancelling staging: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to cancel staging: " + e.getMessage());
        }
    }

    // ==================== Conversion Operations ====================

    @Tool(name = "staging_convert_model",
            description = "Converts a model file from one format to another. Supported input formats: " +
                    "'onnx', 'tensorflow', 'keras', 'ggml/gguf'. Output is SameDiff format (.fb/.sdz). " +
                    "Provide the input file path and optionally an output path and format.")
    public Map<String, Object> convertModel(ConvertModelInput input) {
        logger.info("Converting model: {} format={}", input.inputPath(), input.format());
        if (input.inputPath() == null || input.inputPath().isEmpty()) {
            return Map.of("status", "error", "error", "inputPath is required");
        }
        try {
            Path inputPath = Paths.get(input.inputPath());
            Path outputPath = input.outputPath() != null
                    ? Paths.get(input.outputPath())
                    : inputPath.resolveSibling(inputPath.getFileName().toString().replaceAll("\\.[^.]+$", ".fb"));
            String format = input.format() != null ? input.format() : "auto";

            var result = conversionService.convert(inputPath, outputPath, format);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("inputPath", input.inputPath());
            response.put("outputPath", outputPath.toString());
            response.put("format", format);
            if (result.isSuccess()) {
                response.put("message", "Conversion successful");
            } else {
                response.put("error", result.getErrorMessage());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error converting model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to convert model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_validate_model",
            description = "Validates a SameDiff model file. Checks that the model can be loaded and " +
                    "reports the number of operations and variables.")
    public Map<String, Object> validateModel(ValidateModelInput input) {
        logger.info("Validating model: {}", input.modelPath());
        if (input.modelPath() == null || input.modelPath().isEmpty()) {
            return Map.of("status", "error", "error", "modelPath is required");
        }
        try {
            var result = conversionService.validate(Paths.get(input.modelPath()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isValid() ? "success" : "error");
            response.put("valid", result.isValid());
            response.put("modelPath", input.modelPath());
            if (result.isValid()) {
                response.put("numOperations", result.getNumOperations());
                response.put("numVariables", result.getNumVariables());
            } else {
                response.put("error", result.getErrorMessage());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error validating model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to validate model: " + e.getMessage());
        }
    }

    // ==================== Export/Import Operations ====================

    @Tool(name = "staging_export_models",
            description = "Exports one or more models as a portable bundle (.tar.gz). " +
                    "Provide comma-separated model IDs and an output path. " +
                    "If no model IDs given, exports all models.")
    public Map<String, Object> exportModels(ExportModelsInput input) {
        logger.info("Exporting models: {} to {}", input.modelIds(), input.outputPath());
        try {
            Path outputPath = input.outputPath() != null
                    ? Paths.get(input.outputPath())
                    : Paths.get(System.getProperty("user.home"), ".kompile", "exports", exportService.generateBundleFilename());

            ExportService.ExportResult result;
            if (input.modelIds() != null && !input.modelIds().isEmpty()) {
                List<String> ids = Arrays.asList(input.modelIds().split(","));
                ids = ids.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                result = exportService.export(ids, outputPath, "MCP export");
            } else {
                result = exportService.exportAll(outputPath);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            if (result.isSuccess()) {
                response.put("bundlePath", result.getBundlePath().toString());
                response.put("modelCount", result.getModelCount());
                response.put("bundleSize", result.getBundleSize());
            } else {
                response.put("error", result.getErrorMessage());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error exporting models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to export models: " + e.getMessage());
        }
    }

    @Tool(name = "staging_import_bundle",
            description = "Imports a model bundle (.tar.gz) into the registry. " +
                    "Provide the path to the bundle file. Set verifyChecksums=true (default) " +
                    "to verify model integrity during import.")
    public Map<String, Object> importBundle(ImportBundleInput input) {
        logger.info("Importing bundle: {}", input.bundlePath());
        if (input.bundlePath() == null || input.bundlePath().isEmpty()) {
            return Map.of("status", "error", "error", "bundlePath is required");
        }
        try {
            boolean verify = input.verifyChecksums() == null || input.verifyChecksums();
            var result = importService.importBundle(Paths.get(input.bundlePath()), verify);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            if (result.isSuccess()) {
                response.put("importedCount", result.getImportedCount());
                response.put("totalCount", result.getTotalCount());
                response.put("message", "Import successful");
            } else {
                response.put("error", result.getErrorMessage());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error importing bundle: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to import bundle: " + e.getMessage());
        }
    }

    // ==================== Settings Operations ====================

    @Tool(name = "staging_get_settings",
            description = "Gets the current staging service settings including callback URL, " +
                    "auto-reload, optimizer config, and performance profile.")
    public Map<String, Object> getSettings(GetSettingsInput input) {
        logger.info("Getting staging settings");
        try {
            StagingSettings settings = settingsService.getSettings();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("callbackUrl", settings.getCallbackUrl());
            result.put("autoReloadEnabled", settings.isAutoReloadEnabled());
            result.put("callbackTimeoutMs", settings.getCallbackTimeoutMs());
            result.put("optimizerEnabled", settings.isOptimizerEnabled());
            result.put("optimizerFp16Enabled", settings.isOptimizerFp16Enabled());
            result.put("optimizerMaxIterations", settings.getOptimizerMaxIterations());
            result.put("defaultOptimizationProfile", settings.getDefaultOptimizationProfile());
            result.put("defaultPerformanceProfile", settings.getDefaultPerformanceProfile());
            return result;
        } catch (Exception e) {
            logger.error("Error getting settings: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get settings: " + e.getMessage());
        }
    }

    @Tool(name = "staging_update_settings",
            description = "Updates staging service settings. You can change the callback URL for reload notifications, " +
                    "enable/disable auto-reload, optimizer settings (enabled, FP16, profile), " +
                    "and callback timeout.")
    public Map<String, Object> updateSettings(UpdateSettingsInput input) {
        logger.info("Updating staging settings");
        try {
            StagingSettings settings = settingsService.getSettings();

            if (input.callbackUrl() != null) settings.setCallbackUrl(input.callbackUrl());
            if (input.autoReloadEnabled() != null) settings.setAutoReloadEnabled(input.autoReloadEnabled());
            if (input.callbackTimeoutMs() != null) settings.setCallbackTimeoutMs(input.callbackTimeoutMs());
            if (input.optimizerEnabled() != null) settings.setOptimizerEnabled(input.optimizerEnabled());
            if (input.optimizerFp16Enabled() != null) settings.setOptimizerFp16Enabled(input.optimizerFp16Enabled());
            if (input.defaultOptimizationProfile() != null) settings.setDefaultOptimizationProfile(input.defaultOptimizationProfile());

            settingsService.updateSettings(settings);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Settings updated successfully");
            result.put("callbackUrl", settings.getCallbackUrl());
            result.put("autoReloadEnabled", settings.isAutoReloadEnabled());
            result.put("optimizerEnabled", settings.isOptimizerEnabled());
            result.put("optimizerFp16Enabled", settings.isOptimizerFp16Enabled());
            result.put("defaultOptimizationProfile", settings.getDefaultOptimizationProfile());
            return result;
        } catch (Exception e) {
            logger.error("Error updating settings: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to update settings: " + e.getMessage());
        }
    }

    // ==================== Maintenance Operations ====================

    @Tool(name = "staging_cleanup_failed",
            description = "Cleans up failed staging operations. Removes staging data for models " +
                    "that failed during download or conversion.")
    public Map<String, Object> cleanupFailed(ListRegistryModelsInput input) {
        logger.info("Cleaning up failed staging operations");
        try {
            int cleaned = stagingService.cleanupFailed();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("cleanedCount", cleaned);
            result.put("message", cleaned > 0 ? "Cleaned up " + cleaned + " failed staging operations" : "No failed operations to clean up");
            return result;
        } catch (Exception e) {
            logger.error("Error cleaning up: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to clean up: " + e.getMessage());
        }
    }

    @Tool(name = "staging_repair_models",
            description = "Repairs staged models by checking for missing vocab files and other issues. " +
                    "Returns a map of model IDs to their repair status.")
    public Map<String, Object> repairModels(ListRegistryModelsInput input) {
        logger.info("Repairing staged models");
        try {
            Map<String, Boolean> repairResults = stagingService.repairStagedModels();
            List<String> missingVocab = stagingService.getStagedModelsMissingVocab();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("repairResults", repairResults);
            result.put("modelsMissingVocab", missingVocab);
            result.put("message", "Repair complete. " + repairResults.size() + " models processed.");
            return result;
        } catch (Exception e) {
            logger.error("Error repairing models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to repair models: " + e.getMessage());
        }
    }

    // ==================== Optimization Operations ====================

    @Tool(name = "staging_optimize_model",
            description = "Optimizes a model using SameDiff graph optimizations. Provide a comma-separated list of " +
                    "optimizations: UNUSED_FUNCTION, CONSTANT_FUNCTION, IDENTITY_FUNCTION, SHAPE_FUNCTION, " +
                    "ALGEBRAIC, ACTIVATION_FUSION, NORMALIZATION_FUSION, LINEAR_FUSION, ATTENTION_FUSION, " +
                    "CUDNN_FUNCTION, QUANTIZATION. Optional quantization type: INT8, UINT8, FLOAT16, BFLOAT16.")
    public Map<String, Object> optimizeModel(OptimizeModelInput input) {
        logger.info("Optimizing model: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            OptimizationService.OptimizationConfig config = new OptimizationService.OptimizationConfig();
            if (input.optimizations() != null && !input.optimizations().isEmpty()) {
                Set<OptimizationService.OptimizationType> types = new HashSet<>();
                for (String opt : input.optimizations().split(",")) {
                    types.add(OptimizationService.OptimizationType.valueOf(opt.trim()));
                }
                config.setEnabledOptimizations(types);
            } else {
                config.enableAll();
            }
            if (input.quantizationType() != null) {
                config.setQuantizationType(OptimizationService.QuantizationType.valueOf(input.quantizationType()));
            }
            if (input.quantizePerChannel() != null) config.setQuantizePerChannel(input.quantizePerChannel());
            if (input.createBackup() != null) config.setCreateBackup(input.createBackup());

            boolean force = input.force() != null && input.force();
            OptimizationService.OptimizationResult result = optimizationService.optimize(input.modelId(), config, force);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("modelId", result.getModelId());
            response.put("message", result.getMessage());
            if (result.isSuccess()) {
                response.put("optimizationTimeMs", result.getOptimizationTimeMs());
                response.put("appliedOptimizations", result.getAppliedOptimizations());
                if (result.getBackupFile() != null) response.put("backupFile", result.getBackupFile());
            } else {
                response.put("error", result.getError());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error optimizing model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to optimize model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_list_optimizations",
            description = "Lists all available SameDiff graph optimizations that can be applied to models.")
    public Map<String, Object> listOptimizations(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "optimizations", optimizationService.getAvailableOptimizations());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_optimization_presets",
            description = "Lists available optimization presets (pre-configured optimization combinations).")
    public Map<String, Object> listOptimizationPresets(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "presets", optimizationService.getPresets());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_quantization_types",
            description = "Lists available quantization types for model compression: INT8, UINT8, FLOAT16, BFLOAT16.")
    public Map<String, Object> listQuantizationTypes(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "quantizationTypes", optimizationService.getAvailableQuantizationTypes());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_compare_models",
            description = "Compares an optimized model against its original version. Runs inference on both and " +
                    "reports speedup factor, output differences, and model size changes. Optionally provide " +
                    "sample text and sequence length for the comparison.")
    public Map<String, Object> compareModels(CompareModelsInput input) {
        logger.info("Comparing models for: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            ComparisonService.ComparisonRequest request = new ComparisonService.ComparisonRequest();
            if (input.sampleText() != null) request.setSampleText(input.sampleText());
            if (input.sequenceLength() != null) request.setSequenceLength(input.sequenceLength());

            ComparisonService.ComparisonResult result = comparisonService.compare(input.modelId(), request);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("modelId", result.getModelId());
            if (result.isSuccess()) {
                response.put("speedupFactor", result.getSpeedupFactor());
                response.put("outputsMatch", result.isOutputsMatch());
                response.put("maxAbsoluteDifference", result.getMaxAbsoluteDifference());
                response.put("meanAbsoluteDifference", result.getMeanAbsoluteDifference());
                if (result.getOptimizedResult() != null) {
                    response.put("optimizedInferenceTimeMs", result.getOptimizedResult().getInferenceTimeMs());
                    response.put("optimizedNumOps", result.getOptimizedResult().getNumOps());
                    response.put("optimizedSizeBytes", result.getOptimizedResult().getModelSizeBytes());
                }
                if (result.getOriginalResult() != null) {
                    response.put("originalInferenceTimeMs", result.getOriginalResult().getInferenceTimeMs());
                    response.put("originalNumOps", result.getOriginalResult().getNumOps());
                    response.put("originalSizeBytes", result.getOriginalResult().getModelSizeBytes());
                }
            } else {
                response.put("error", result.getError());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error comparing models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to compare models: " + e.getMessage());
        }
    }

    // ==================== Compiler Operations ====================

    @Tool(name = "staging_compiler_optimize",
            description = "Optimizes a model's computation graph using the compiler service. Apply passes like " +
                    "dead_code_elimination, constant_folding, algebraic_simplification, identity_removal, " +
                    "shape_fusion, activation_fusion, normalization_fusion, linear_fusion, attention_fusion, " +
                    "cudnn_replacement, quantization. Or use a profile: NONE, BASIC, TRANSFORMER, GPU, FULL.")
    public Map<String, Object> compilerOptimize(CompilerOptimizeInput input) {
        logger.info("Compiler optimizing model: {}", input.modelId());
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            CompilerOptimizeRequest.CompilerOptimizeRequestBuilder builder = CompilerOptimizeRequest.builder()
                    .modelId(input.modelId());
            if (input.selectedPasses() != null) {
                builder.selectedPasses(Arrays.asList(input.selectedPasses().split(",")));
            }
            if (input.maxIterations() != null) builder.maxIterations(input.maxIterations());
            if (input.profile() != null) builder.profile(input.profile());
            if (input.outputModelId() != null) builder.outputModelId(input.outputModelId());
            if (input.quantizationType() != null) builder.quantizationType(input.quantizationType());
            if (input.force() != null) builder.force(input.force());
            if (input.createBackup() != null) builder.createBackup(input.createBackup());
            if (input.dryRun() != null) builder.dryRun(input.dryRun());

            CompilerOptimizeResponse result = compilerService.optimizeGraph(builder.build());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("modelId", result.getModelId());
            response.put("message", result.getMessage());
            if (result.isSuccess()) {
                response.put("opsRemoved", result.getOpsRemoved());
                response.put("opsFused", result.getOpsFused());
                response.put("passesApplied", result.getPassesApplied());
                response.put("beforeOpsCount", result.getBeforeOpsCount());
                response.put("afterOpsCount", result.getAfterOpsCount());
                response.put("sizeBeforeBytes", result.getSizeBeforeBytes());
                response.put("sizeAfterBytes", result.getSizeAfterBytes());
                response.put("reductionPercent", result.getReductionPercent());
                response.put("optimizationTimeMs", result.getOptimizationTimeMs());
                response.put("dryRun", result.isDryRun());
                if (result.getBackupFile() != null) response.put("backupFile", result.getBackupFile());
            } else {
                response.put("error", result.getError());
            }
            return response;
        } catch (Exception e) {
            logger.error("Error in compiler optimize: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to optimize graph: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_graph_info",
            description = "Gets detailed graph information for a model: operation types, counts, " +
                    "input/output names, model size.")
    public Map<String, Object> getGraphInfo(GraphInfoInput input) {
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            GraphInfoResponse info = compilerService.getGraphInfo(input.modelId());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("modelId", info.getModelId());
            response.put("totalOps", info.getTotalOps());
            response.put("totalVariables", info.getTotalVariables());
            response.put("opTypes", info.getOpTypes());
            response.put("inputNames", info.getInputNames());
            response.put("outputNames", info.getOutputNames());
            response.put("modelSizeBytes", info.getModelSizeBytes());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to get graph info: " + e.getMessage());
        }
    }

    @Tool(name = "staging_compare_graphs",
            description = "Compares the computation graphs of two models. Shows ops added/removed/changed, " +
                    "size differences, and output matching.")
    public Map<String, Object> compareGraphs(CompareGraphsInput input) {
        if (input.model1Id() == null || input.model2Id() == null) {
            return Map.of("status", "error", "error", "model1Id and model2Id are required");
        }
        try {
            CompilerCompareResponse result = compilerService.compareGraphs(input.model1Id(), input.model2Id());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            if (result.isSuccess()) {
                response.put("opsAdded", result.getOpsAdded());
                response.put("opsRemoved", result.getOpsRemoved());
                response.put("opsChanged", result.getOpsChanged());
                response.put("sizeChange", result.getSizeChange());
                response.put("outputsMatch", result.isOutputsMatch());
                response.put("speedupFactor", result.getSpeedupFactor());
                response.put("maxAbsoluteDifference", result.getMaxAbsoluteDifference());
            } else {
                response.put("error", result.getError());
            }
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to compare graphs: " + e.getMessage());
        }
    }

    @Tool(name = "staging_compile_triton",
            description = "Compiles a model for Triton inference server with GPU-optimized kernels.")
    public Map<String, Object> compileTriton(TritonCompileInput input) {
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            TritonCompileRequest.TritonCompileRequestBuilder builder = TritonCompileRequest.builder()
                    .modelId(input.modelId());
            if (input.numWarps() != null) builder.numWarps(input.numWarps());
            if (input.numStages() != null) builder.numStages(input.numStages());
            if (input.numCTAs() != null) builder.numCTAs(input.numCTAs());
            if (input.fpFusion() != null) builder.fpFusion(input.fpFusion());
            if (input.arch() != null) builder.arch(input.arch());

            CompilerOptimizeResponse result = compilerService.compileWithTriton(builder.build());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("modelId", result.getModelId());
            response.put("message", result.getMessage());
            if (!result.isSuccess()) response.put("error", result.getError());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to compile for Triton: " + e.getMessage());
        }
    }

    @Tool(name = "staging_save_compiled_graph",
            description = "Saves an optimized/compiled graph as a new model. Applies selected passes or profile " +
                    "and saves to a new model ID.")
    public Map<String, Object> saveCompiledGraph(SaveCompiledInput input) {
        if (input.sourceModelId() == null || input.outputModelId() == null) {
            return Map.of("status", "error", "error", "sourceModelId and outputModelId are required");
        }
        try {
            SaveCompiledGraphRequest.SaveCompiledGraphRequestBuilder builder = SaveCompiledGraphRequest.builder()
                    .sourceModelId(input.sourceModelId())
                    .outputModelId(input.outputModelId());
            if (input.selectedPasses() != null) {
                builder.selectedPasses(Arrays.asList(input.selectedPasses().split(",")));
            }
            if (input.profile() != null) builder.profile(input.profile());
            if (input.maxIterations() != null) builder.maxIterations(input.maxIterations());
            if (input.saveFormat() != null) builder.saveFormat(input.saveFormat());
            if (input.description() != null) builder.description(input.description());

            SaveCompiledGraphResponse result = compilerService.saveCompiledGraph(builder.build());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            response.put("outputModelId", result.getOutputModelId());
            if (result.isSuccess()) {
                response.put("outputPath", result.getOutputPath());
                response.put("beforeOpsCount", result.getBeforeOpsCount());
                response.put("afterOpsCount", result.getAfterOpsCount());
                response.put("reductionPercent", result.getReductionPercent());
                response.put("passesApplied", result.getPassesApplied());
            } else {
                response.put("error", result.getError());
            }
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to save compiled graph: " + e.getMessage());
        }
    }

    @Tool(name = "staging_list_compiled_models",
            description = "Lists all compiled/optimized model files available in the models directory.")
    public Map<String, Object> listCompiledModels(ListRegistryModelsInput input) {
        try {
            List<CompiledModelInfo> models = compilerService.listCompiledModels();
            List<Map<String, Object>> list = models.stream().map(m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("modelId", m.getModelId());
                map.put("filePath", m.getFilePath());
                map.put("sizeBytes", m.getSizeBytes());
                map.put("lastModified", m.getLastModified());
                map.put("totalOps", m.getTotalOps());
                map.put("totalVariables", m.getTotalVariables());
                return map;
            }).collect(Collectors.toList());
            return Map.of("status", "success", "models", list, "totalModels", list.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to list compiled models: " + e.getMessage());
        }
    }

    @Tool(name = "staging_list_compiler_passes",
            description = "Lists all available compiler optimization passes.")
    public Map<String, Object> listCompilerPasses(ListRegistryModelsInput input) {
        try {
            List<OptimizationPassInfo> passes = compilerService.getAvailablePasses();
            List<Map<String, Object>> list = passes.stream().map(p -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", p.getId());
                map.put("name", p.getName());
                map.put("description", p.getDescription());
                map.put("category", p.getCategory());
                map.put("isDefault", p.isDefault());
                return map;
            }).collect(Collectors.toList());
            return Map.of("status", "success", "passes", list);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_compiler_profiles",
            description = "Lists available compiler optimization profiles: NONE, BASIC, TRANSFORMER, GPU, FULL.")
    public Map<String, Object> listCompilerProfiles(ListRegistryModelsInput input) {
        try {
            List<OptimizationProfileInfo> profiles = compilerService.getProfiles();
            List<Map<String, Object>> list = profiles.stream().map(p -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("profileName", p.getProfileName());
                map.put("description", p.getDescription());
                map.put("includedPasses", p.getIncludedPasses());
                return map;
            }).collect(Collectors.toList());
            return Map.of("status", "success", "profiles", list);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_get_cache_status",
            description = "Gets the status of compiler caches (execution plan cache, DAG cache).")
    public Map<String, Object> getCacheStatus(ListRegistryModelsInput input) {
        try {
            CacheStatusResponse cache = compilerService.getCacheStatus();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("executionPlanCacheSize", cache.getExecutionPlanCacheSize());
            response.put("executionPlanCacheEnabled", cache.isExecutionPlanCacheEnabled());
            response.put("dagCacheSize", cache.getDagCacheSize());
            response.put("dagCacheEnabled", cache.isDagCacheEnabled());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_clear_cache",
            description = "Clears compiler caches. Specify cacheType: 'execution_plan', 'dag', or 'all'.")
    public Map<String, Object> clearCache(ClearCacheInput input) {
        try {
            return compilerService.clearCache(input.cacheType() != null ? input.cacheType() : "all");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_start_compilation_job",
            description = "Starts an async compilation job. CompilationMode: REDUCE_OVERHEAD, SPLIT_STITCH, MAX_AUTOTUNE. " +
                    "ExecutionMode: AUTO, SLOT_BY_SLOT, CUDA_GRAPHS, NVRTC_JIT, PTX_JIT, TRITON.")
    public Map<String, Object> startCompilationJob(StartCompilationInput input) {
        if (input.modelId() == null || input.modelId().isEmpty()) {
            return Map.of("status", "error", "error", "modelId is required");
        }
        try {
            CompilationRequest.CompilationRequestBuilder builder = CompilationRequest.builder()
                    .modelId(input.modelId());
            if (input.compilationMode() != null) builder.compilationMode(input.compilationMode());
            if (input.executionMode() != null) builder.executionMode(input.executionMode());
            if (input.selectedPasses() != null) {
                builder.selectedPasses(Arrays.asList(input.selectedPasses().split(",")));
            }
            if (input.profile() != null) builder.profile(input.profile());
            if (input.maxIterations() != null) builder.maxIterations(input.maxIterations());
            if (input.createBackup() != null) builder.createBackup(input.createBackup());
            if (input.outputModelId() != null) builder.outputModelId(input.outputModelId());

            CompilationJobStatus job = compilerService.startCompilationJob(builder.build());
            return compilationJobToMap(job);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to start compilation: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_compilation_jobs",
            description = "Gets all active compilation jobs and their statuses.")
    public Map<String, Object> getCompilationJobs(ListRegistryModelsInput input) {
        try {
            List<CompilationJobStatus> jobs = compilerService.getActiveJobs();
            List<Map<String, Object>> list = jobs.stream().map(this::compilationJobToMap).collect(Collectors.toList());
            return Map.of("status", "success", "jobs", list, "totalJobs", list.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_cancel_compilation_job",
            description = "Cancels an active compilation job by job ID.")
    public Map<String, Object> cancelCompilationJob(CancelJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            return compilerService.cancelJob(input.jobId());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Training Operations ====================

    @Tool(name = "staging_start_training",
            description = "Starts a training job for a model. Specify the modelId and datasetId. " +
                    "Optional PEFT type: LORA, QLORA, ADALORA, DYLORA, DORA, IA3, PROMPT_TUNING, PREFIX_TUNING. " +
                    "LR schedule: COSINE, LINEAR, CONSTANT, POLYNOMIAL.")
    public Map<String, Object> startTraining(StartTrainingInput input) {
        if (input.modelId() == null || input.datasetId() == null) {
            return Map.of("status", "error", "error", "modelId and datasetId are required");
        }
        try {
            TrainingConfigRequest.TrainingConfigRequestBuilder builder = TrainingConfigRequest.builder()
                    .modelId(input.modelId())
                    .datasetId(input.datasetId());
            if (input.peftType() != null) {
                builder.peftConfig(PeftConfigRequest.builder().peftType(input.peftType()).build());
            }
            if (input.lrSchedule() != null) builder.lrSchedule(input.lrSchedule());
            if (input.epochs() != null) builder.epochs(input.epochs());
            if (input.batchSize() != null) builder.batchSize(input.batchSize());
            if (input.fp16() != null) builder.fp16(input.fp16());
            if (input.loggingSteps() != null) builder.loggingSteps(input.loggingSteps());
            if (input.saveSteps() != null) builder.saveSteps(input.saveSteps());
            if (input.seed() != null) builder.seed(input.seed());

            TrainingJobStatus job = trainingService.startTraining(builder.build());
            return trainingJobToMap(job);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to start training: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_training_job",
            description = "Gets the status of a training job by job ID.")
    public Map<String, Object> getTrainingJob(GetJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            TrainingJobStatus job = trainingService.getJob(input.jobId());
            return job != null ? trainingJobToMap(job) : Map.of("status", "error", "error", "Job not found: " + input.jobId());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_training_jobs",
            description = "Lists all training jobs and their statuses.")
    public Map<String, Object> listTrainingJobs(ListRegistryModelsInput input) {
        try {
            List<TrainingJobStatus> jobs = trainingService.getAllJobs();
            List<Map<String, Object>> list = jobs.stream().map(this::trainingJobToMap).collect(Collectors.toList());
            return Map.of("status", "success", "jobs", list, "totalJobs", list.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_cancel_training_job",
            description = "Cancels an active training job.")
    public Map<String, Object> cancelTrainingJob(CancelJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            boolean cancelled = trainingService.cancelJob(input.jobId());
            return Map.of("status", cancelled ? "success" : "error",
                    "message", cancelled ? "Job cancelled" : "Could not cancel job");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_get_training_logs",
            description = "Gets the log entries for a training job.")
    public Map<String, Object> getTrainingLogs(GetJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            List<TrainingLogEntry> logs = trainingService.getJobLogs(input.jobId());
            List<Map<String, Object>> list = logs.stream().map(l -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("timestamp", l.getTimestamp());
                map.put("level", l.getLevel());
                map.put("message", l.getMessage());
                map.put("step", l.getStep());
                map.put("loss", l.getLoss());
                map.put("learningRate", l.getLearningRate());
                return map;
            }).collect(Collectors.toList());
            return Map.of("status", "success", "logs", list);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_get_training_metrics",
            description = "Gets the metrics history for a training job (loss, learning rate, tokens/sec over time).")
    public Map<String, Object> getTrainingMetrics(GetJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            List<TrainingMetricsSnapshot> metrics = trainingService.getMetricsHistory(input.jobId());
            List<Map<String, Object>> list = metrics.stream().map(m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("step", m.getStep());
                map.put("epoch", m.getEpoch());
                map.put("trainLoss", m.getTrainLoss());
                map.put("evalLoss", m.getEvalLoss());
                map.put("learningRate", m.getLearningRate());
                map.put("tokensPerSecond", m.getTokensPerSecond());
                return map;
            }).collect(Collectors.toList());
            return Map.of("status", "success", "metrics", list);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Distillation Operations ====================

    @Tool(name = "staging_start_distillation",
            description = "Starts a knowledge distillation job. Transfer knowledge from a teacher model to a smaller " +
                    "student model. Types: LOGIT_KD, FEATURE_KD, ATTENTION_KD, COMBINED.")
    public Map<String, Object> startDistillation(StartDistillationInput input) {
        if (input.teacherModelId() == null || input.studentModelId() == null) {
            return Map.of("status", "error", "error", "teacherModelId and studentModelId are required");
        }
        try {
            DistillationConfigRequest.DistillationConfigRequestBuilder builder = DistillationConfigRequest.builder()
                    .teacherModelId(input.teacherModelId())
                    .studentModelId(input.studentModelId());
            if (input.distillationType() != null) builder.distillationType(input.distillationType());
            if (input.temperature() != null) builder.temperature(input.temperature());
            if (input.alpha() != null) builder.alpha(input.alpha());
            if (input.datasetId() != null) builder.datasetId(input.datasetId());
            if (input.epochs() != null || input.batchSize() != null) {
                TrainingConfigRequest.TrainingConfigRequestBuilder tcb = TrainingConfigRequest.builder();
                if (input.epochs() != null) tcb.epochs(input.epochs());
                if (input.batchSize() != null) tcb.batchSize(input.batchSize());
                builder.trainingConfig(tcb.build());
            }

            TrainingJobStatus job = distillationService.startDistillation(builder.build());
            return trainingJobToMap(job);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to start distillation: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_distillation_job",
            description = "Gets the status of a distillation job.")
    public Map<String, Object> getDistillationJob(GetJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            TrainingJobStatus job = distillationService.getJob(input.jobId());
            return job != null ? trainingJobToMap(job) : Map.of("status", "error", "error", "Job not found");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_cancel_distillation",
            description = "Cancels an active distillation job.")
    public Map<String, Object> cancelDistillation(CancelJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            boolean cancelled = distillationService.cancelJob(input.jobId());
            return Map.of("status", cancelled ? "success" : "error",
                    "message", cancelled ? "Distillation cancelled" : "Could not cancel");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_distillation_types",
            description = "Lists available distillation types: LOGIT_KD, FEATURE_KD, ATTENTION_KD, COMBINED.")
    public Map<String, Object> listDistillationTypes(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "types", distillationService.getAvailableDistillationTypes());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Alignment Operations ====================

    @Tool(name = "staging_start_alignment",
            description = "Starts an alignment training job (RLHF/DPO). Algorithms: DPO, KTO, ORPO, PPO, GRPO. " +
                    "Requires a base model and dataset with preference pairs.")
    public Map<String, Object> startAlignment(StartAlignmentInput input) {
        if (input.algorithm() == null || input.baseModelId() == null) {
            return Map.of("status", "error", "error", "algorithm and baseModelId are required");
        }
        try {
            AlignmentConfigRequest.AlignmentConfigRequestBuilder builder = AlignmentConfigRequest.builder()
                    .algorithm(input.algorithm())
                    .baseModelId(input.baseModelId());
            if (input.rewardModelId() != null) builder.rewardModelId(input.rewardModelId());
            if (input.datasetId() != null) builder.datasetId(input.datasetId());
            if (input.beta() != null) builder.beta(input.beta());
            if (input.maxPromptLength() != null) builder.maxPromptLength(input.maxPromptLength());
            if (input.maxCompletionLength() != null) builder.maxCompletionLength(input.maxCompletionLength());
            if (input.epochs() != null || input.batchSize() != null) {
                TrainingConfigRequest.TrainingConfigRequestBuilder tcb = TrainingConfigRequest.builder();
                if (input.epochs() != null) tcb.epochs(input.epochs());
                if (input.batchSize() != null) tcb.batchSize(input.batchSize());
                builder.trainingConfig(tcb.build());
            }

            TrainingJobStatus job = alignmentService.startAlignment(builder.build());
            return trainingJobToMap(job);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to start alignment: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_alignment_job",
            description = "Gets the status of an alignment job.")
    public Map<String, Object> getAlignmentJob(GetJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            TrainingJobStatus job = alignmentService.getJob(input.jobId());
            return job != null ? trainingJobToMap(job) : Map.of("status", "error", "error", "Job not found");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_cancel_alignment",
            description = "Cancels an active alignment job.")
    public Map<String, Object> cancelAlignment(CancelJobInput input) {
        if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
        try {
            boolean cancelled = alignmentService.cancelJob(input.jobId());
            return Map.of("status", cancelled ? "success" : "error",
                    "message", cancelled ? "Alignment cancelled" : "Could not cancel");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_alignment_algorithms",
            description = "Lists available alignment algorithms: DPO, KTO, ORPO, PPO, GRPO.")
    public Map<String, Object> listAlignmentAlgorithms(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "algorithms", alignmentService.getAvailableAlgorithms());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Evaluation Operations ====================

    @Tool(name = "staging_evaluate_model",
            description = "Evaluates a model on a dataset. Metrics: perplexity, accuracy, f1, bleu, rouge. " +
                    "Provide comma-separated metric names.")
    public Map<String, Object> evaluateModel(EvaluateModelInput input) {
        if (input.modelId() == null || input.datasetId() == null) {
            return Map.of("status", "error", "error", "modelId and datasetId are required");
        }
        try {
            EvaluationRequest.EvaluationRequestBuilder builder = EvaluationRequest.builder()
                    .modelId(input.modelId())
                    .datasetId(input.datasetId());
            if (input.metrics() != null) {
                builder.metrics(Arrays.asList(input.metrics().split(",")));
            }
            if (input.batchSize() != null) builder.batchSize(input.batchSize());
            if (input.maxSamples() != null) builder.maxSamples(input.maxSamples());

            EvaluationResult result = evaluationService.startEvaluation(builder.build());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("evaluationId", result.getEvaluationId());
            response.put("modelId", result.getModelId());
            response.put("metrics", result.getMetrics());
            response.put("samplesEvaluated", result.getSamplesEvaluated());
            response.put("evaluationTimeMs", result.getEvaluationTimeMs());
            if (result.getError() != null) response.put("error", result.getError());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to evaluate model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_evaluation_result",
            description = "Gets a previous evaluation result by evaluation ID.")
    public Map<String, Object> getEvaluationResult(GetEvaluationInput input) {
        if (input.evaluationId() == null) return Map.of("status", "error", "error", "evaluationId is required");
        try {
            EvaluationResult result = evaluationService.getResult(input.evaluationId());
            if (result == null) return Map.of("status", "error", "error", "Evaluation not found");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("evaluationId", result.getEvaluationId());
            response.put("modelId", result.getModelId());
            response.put("metrics", result.getMetrics());
            response.put("samplesEvaluated", result.getSamplesEvaluated());
            response.put("evaluationTimeMs", result.getEvaluationTimeMs());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_evaluation_metrics",
            description = "Lists available evaluation metrics: perplexity, accuracy, f1, bleu, rouge.")
    public Map<String, Object> listEvaluationMetrics(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "metrics", evaluationService.getAvailableMetrics());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== PEFT Operations ====================

    @Tool(name = "staging_list_peft_types",
            description = "Lists available PEFT (Parameter-Efficient Fine-Tuning) types: LORA, QLORA, ADALORA, " +
                    "DYLORA, DORA, IA3, PROMPT_TUNING, PREFIX_TUNING.")
    public Map<String, Object> listPeftTypes(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "peftTypes", peftService.getAvailablePeftTypes());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_create_peft_model",
            description = "Creates a PEFT adapter for a base model. Specify the peftType and optionally LoRA " +
                    "parameters (rank, alpha, dropout, target modules as comma-separated list).")
    public Map<String, Object> createPeftModel(CreatePeftInput input) {
        if (input.modelId() == null || input.peftType() == null) {
            return Map.of("status", "error", "error", "modelId and peftType are required");
        }
        try {
            PeftConfigRequest.PeftConfigRequestBuilder builder = PeftConfigRequest.builder()
                    .peftType(input.peftType())
                    .baseModelId(input.modelId());
            if (input.loraRank() != null || input.loraAlpha() != null || input.loraDropout() != null || input.targetModules() != null) {
                LoraConfigDto.LoraConfigDtoBuilder loraBuilder = LoraConfigDto.builder();
                if (input.loraRank() != null) loraBuilder.rank(input.loraRank());
                if (input.loraAlpha() != null) loraBuilder.alpha(input.loraAlpha());
                if (input.loraDropout() != null) loraBuilder.dropout(input.loraDropout());
                if (input.targetModules() != null) {
                    loraBuilder.targetModules(Arrays.asList(input.targetModules().split(",")));
                }
                builder.loraConfig(loraBuilder.build());
            }

            Map<String, Object> result = peftService.createPeftModel(input.modelId(), builder.build());
            result.put("status", "success");
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to create PEFT model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_merge_peft_weights",
            description = "Merges PEFT adapter weights back into the base model, creating a full model.")
    public Map<String, Object> mergePeftWeights(MergePeftInput input) {
        if (input.peftModelId() == null) return Map.of("status", "error", "error", "peftModelId is required");
        try {
            Map<String, Object> result = peftService.mergeWeights(input.peftModelId());
            result.put("status", "success");
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to merge weights: " + e.getMessage());
        }
    }

    @Tool(name = "staging_get_peft_info",
            description = "Gets PEFT adapter information for a model (type, rank, config).")
    public Map<String, Object> getPeftInfo(GetPeftInfoInput input) {
        if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
        try {
            Map<String, Object> result = peftService.getPeftInfo(input.modelId());
            result.put("status", "success");
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_updater_types",
            description = "Lists available optimizer/updater types for training (SGD, Adam, AdaGrad, etc.).")
    public Map<String, Object> listUpdaterTypes(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "updaterTypes", peftService.getAvailableUpdaterTypes());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_list_lr_schedules",
            description = "Lists available learning rate schedules (COSINE, LINEAR, CONSTANT, POLYNOMIAL).")
    public Map<String, Object> listLrSchedules(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "lrSchedules", peftService.getAvailableLrSchedules());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Dataset Operations ====================

    @Tool(name = "staging_list_datasets",
            description = "Lists all uploaded datasets available for training and evaluation.")
    public Map<String, Object> listDatasets(ListRegistryModelsInput input) {
        try {
            List<DatasetInfo> datasets = datasetService.listDatasets();
            List<Map<String, Object>> list = datasets.stream().map(this::datasetInfoToMap).collect(Collectors.toList());
            return Map.of("status", "success", "datasets", list, "totalDatasets", list.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_get_dataset",
            description = "Gets detailed information about a dataset by ID.")
    public Map<String, Object> getDataset(GetDatasetInput input) {
        if (input.id() == null) return Map.of("status", "error", "error", "id is required");
        try {
            DatasetInfo info = datasetService.getDataset(input.id());
            if (info == null) return Map.of("status", "error", "error", "Dataset not found");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.putAll(datasetInfoToMap(info));
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_delete_dataset",
            description = "Deletes a dataset by ID.")
    public Map<String, Object> deleteDataset(DeleteDatasetInput input) {
        if (input.id() == null) return Map.of("status", "error", "error", "id is required");
        try {
            datasetService.deleteDataset(input.id());
            return Map.of("status", "success", "message", "Dataset deleted: " + input.id());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_preview_dataset",
            description = "Previews dataset rows. Returns the first N rows (default 10).")
    public Map<String, Object> previewDataset(PreviewDatasetInput input) {
        if (input.id() == null) return Map.of("status", "error", "error", "id is required");
        try {
            int rows = input.rows() != null ? input.rows() : 10;
            List<Map<String, Object>> preview = datasetService.previewDataset(input.id(), rows);
            return Map.of("status", "success", "rows", preview, "totalRows", preview.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_compute_dataset_stats",
            description = "Computes statistics for a dataset: total samples, train/val split, token length stats.")
    public Map<String, Object> computeDatasetStats(ComputeDatasetStatsInput input) {
        if (input.id() == null) return Map.of("status", "error", "error", "id is required");
        try {
            DatasetStats stats = datasetService.computeStats(input.id());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("totalSamples", stats.getTotalSamples());
            response.put("trainSamples", stats.getTrainSamples());
            response.put("valSamples", stats.getValSamples());
            response.put("avgTokenLength", stats.getAvgTokenLength());
            response.put("maxTokenLength", stats.getMaxTokenLength());
            response.put("minTokenLength", stats.getMinTokenLength());
            if (stats.getLabelDistribution() != null) response.put("labelDistribution", stats.getLabelDistribution());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== LLM Execution Operations ====================

    @Tool(name = "staging_llm_load",
            description = "Loads an LLM model for text generation. Specify modelId and optionally a modelPath " +
                    "and kvCacheType (PAGED, EVICTABLE_PAGED).")
    public Map<String, Object> llmLoad(LlmLoadInput input) {
        if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
        try {
            LlmModelStatusResponse status = llmExecutionService.loadModel(
                    input.modelId(), input.modelPath(), input.kvCacheType());
            return llmStatusToMap(status);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to load LLM: " + e.getMessage());
        }
    }

    @Tool(name = "staging_llm_unload",
            description = "Unloads the currently loaded LLM model, freeing memory.")
    public Map<String, Object> llmUnload(ListRegistryModelsInput input) {
        try {
            LlmModelStatusResponse status = llmExecutionService.unloadModel();
            return llmStatusToMap(status);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to unload LLM: " + e.getMessage());
        }
    }

    @Tool(name = "staging_llm_status",
            description = "Gets the status of the currently loaded LLM model (loaded, memory usage, KV cache type).")
    public Map<String, Object> llmStatus(ListRegistryModelsInput input) {
        try {
            LlmModelStatusResponse status = llmExecutionService.getStatus();
            return llmStatusToMap(status);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_llm_generate",
            description = "Generates text using the loaded LLM. Parameters: prompt (required), maxTokens (default 256), " +
                    "temperature (default 1.0), topK, topP, repetitionPenalty, doSample, presetName " +
                    "(greedy, default, creative, precise).")
    public Map<String, Object> llmGenerate(LlmGenerateInput input) {
        if (input.prompt() == null || input.prompt().isEmpty()) {
            return Map.of("status", "error", "error", "prompt is required");
        }
        try {
            LlmGenerateRequest.LlmGenerateRequestBuilder builder = LlmGenerateRequest.builder()
                    .prompt(input.prompt());
            if (input.maxTokens() != null) builder.maxTokens(input.maxTokens());
            if (input.temperature() != null) builder.temperature(input.temperature());
            if (input.topK() != null) builder.topK(input.topK());
            if (input.topP() != null) builder.topP(input.topP());
            if (input.repetitionPenalty() != null) builder.repetitionPenalty(input.repetitionPenalty());
            if (input.doSample() != null) builder.doSample(input.doSample());
            if (input.presetName() != null) builder.presetName(input.presetName());

            LlmGenerateResponse result = llmExecutionService.generate(builder.build());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("generatedText", result.getGeneratedText());
            response.put("tokensPerSecond", result.getTokensPerSecond());
            response.put("firstTokenLatencyMs", result.getFirstTokenLatencyMs());
            response.put("totalTokens", result.getTotalTokens());
            response.put("finishReason", result.getFinishReason());
            response.put("totalTimeMs", result.getTotalTimeMs());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to generate: " + e.getMessage());
        }
    }

    @Tool(name = "staging_llm_cancel",
            description = "Cancels an in-progress LLM text generation.")
    public Map<String, Object> llmCancel(ListRegistryModelsInput input) {
        try {
            llmExecutionService.cancelGeneration();
            return Map.of("status", "success", "message", "Generation cancelled");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_llm_sampling_presets",
            description = "Lists available LLM sampling presets (greedy, default, creative, precise).")
    public Map<String, Object> llmSamplingPresets(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "presets", llmExecutionService.getSamplingPresets());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_llm_speculative_config",
            description = "Gets or updates speculative decoding configuration. Provide parameters to update, " +
                    "or call with no parameters to get current config.")
    public Map<String, Object> llmSpeculativeConfig(LlmSpeculativeConfigInput input) {
        try {
            if (input.enabled() != null || input.ngramSize() != null || input.maxSpeculativeTokens() != null) {
                SpeculativeDecodingConfig config = llmExecutionService.getSpeculativeConfig();
                if (input.enabled() != null) config.setEnabled(input.enabled());
                if (input.ngramSize() != null) config.setNgramSize(input.ngramSize());
                if (input.maxSpeculativeTokens() != null) config.setMaxSpeculativeTokens(input.maxSpeculativeTokens());
                if (input.useDraftModel() != null) config.setUseDraftModel(input.useDraftModel());
                if (input.draftModelId() != null) config.setDraftModelId(input.draftModelId());
                config = llmExecutionService.updateSpeculativeConfig(config);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "success");
                response.put("message", "Speculative config updated");
                response.put("enabled", config.isEnabled());
                response.put("ngramSize", config.getNgramSize());
                response.put("maxSpeculativeTokens", config.getMaxSpeculativeTokens());
                response.put("useDraftModel", config.isUseDraftModel());
                response.put("draftModelId", config.getDraftModelId());
                return response;
            } else {
                SpeculativeDecodingConfig config = llmExecutionService.getSpeculativeConfig();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "success");
                response.put("enabled", config.isEnabled());
                response.put("ngramSize", config.getNgramSize());
                response.put("maxSpeculativeTokens", config.getMaxSpeculativeTokens());
                response.put("useDraftModel", config.isUseDraftModel());
                response.put("draftModelId", config.getDraftModelId());
                return response;
            }
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== LLM Chat Operations ====================

    public record LlmChatInput(List<ChatMessage> messages, String chatTemplate, Integer maxTokens,
                                Double temperature, String presetName) {}

    @Tool(name = "staging_llm_chat",
            description = "Chat with the loaded LLM using multi-turn conversation. Parameters: messages (list of {role, content}), " +
                    "chatTemplate (chatml, llama2, vicuna, alpaca, simple), maxTokens, temperature, presetName.")
    public Map<String, Object> llmChat(LlmChatInput input) {
        if (input.messages() == null || input.messages().isEmpty()) {
            return Map.of("status", "error", "error", "messages list is required");
        }
        try {
            ChatRequest.ChatRequestBuilder builder = ChatRequest.builder().messages(input.messages());
            if (input.chatTemplate() != null) builder.chatTemplate(input.chatTemplate());
            if (input.maxTokens() != null) builder.maxTokens(input.maxTokens());
            if (input.temperature() != null) builder.temperature(input.temperature());
            if (input.presetName() != null) builder.presetName(input.presetName());

            ChatResponse result = llmExecutionService.chat(builder.build(), chatTemplateService);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("assistantMessage", result.getAssistantMessage());
            response.put("tokensPerSecond", result.getTokensPerSecond());
            response.put("totalTokens", result.getTotalTokens());
            response.put("finishReason", result.getFinishReason());
            response.put("totalTimeMs", result.getTotalTimeMs());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Chat failed: " + e.getMessage());
        }
    }

    public record LlmBatchInput(List<String> prompts, Integer maxTokens, Double temperature, String presetName) {}

    @Tool(name = "staging_llm_batch_generate",
            description = "Generate text for multiple prompts in batch. Parameters: prompts (list of strings), maxTokens, temperature, presetName.")
    public Map<String, Object> llmBatchGenerate(LlmBatchInput input) {
        if (input.prompts() == null || input.prompts().isEmpty()) {
            return Map.of("status", "error", "error", "prompts list is required");
        }
        try {
            BatchGenerateRequest.BatchGenerateRequestBuilder builder = BatchGenerateRequest.builder().prompts(input.prompts());
            if (input.maxTokens() != null) builder.maxTokens(input.maxTokens());
            if (input.temperature() != null) builder.temperature(input.temperature());
            if (input.presetName() != null) builder.presetName(input.presetName());

            BatchGenerateResponse result = llmExecutionService.generateBatch(builder.build());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("successCount", result.getSuccessCount());
            response.put("errorCount", result.getErrorCount());
            response.put("totalTimeMs", result.getTotalTimeMs());
            List<Map<String, Object>> resultsList = new ArrayList<>();
            for (LlmGenerateResponse r : result.getResults()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("generatedText", r.getGeneratedText());
                item.put("finishReason", r.getFinishReason());
                item.put("totalTokens", r.getTotalTokens());
                resultsList.add(item);
            }
            response.put("results", resultsList);
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Batch generation failed: " + e.getMessage());
        }
    }

    // ==================== Prompt Template Operations ====================

    public record CreateTemplateInput(String name, String template, String description) {}

    @Tool(name = "staging_llm_create_template",
            description = "Create a prompt template with {{variable}} placeholders. Parameters: name, template, description.")
    public Map<String, Object> llmCreateTemplate(CreateTemplateInput input) {
        if (input.template() == null || input.template().isEmpty()) {
            return Map.of("status", "error", "error", "template text is required");
        }
        try {
            PromptTemplate pt = PromptTemplate.builder()
                    .name(input.name())
                    .template(input.template())
                    .description(input.description())
                    .build();
            PromptTemplate created = promptTemplateService.create(pt);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("id", created.getId());
            response.put("name", created.getName());
            response.put("variables", created.getVariables());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to create template: " + e.getMessage());
        }
    }

    public record ApplyTemplateInput(String id, Map<String, String> variables) {}

    @Tool(name = "staging_llm_apply_template",
            description = "Apply a prompt template by substituting variables. Parameters: id (template ID), variables (map of key-value pairs).")
    public Map<String, Object> llmApplyTemplate(ApplyTemplateInput input) {
        if (input.id() == null) {
            return Map.of("status", "error", "error", "template id is required");
        }
        try {
            String result = promptTemplateService.apply(input.id(), input.variables());
            if (result == null) {
                return Map.of("status", "error", "error", "Template not found: " + input.id());
            }
            return Map.of("status", "success", "result", result, "templateId", input.id());
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to apply template: " + e.getMessage());
        }
    }

    @Tool(name = "staging_llm_list_templates",
            description = "Lists all saved prompt templates.")
    public Map<String, Object> llmListTemplates(ListRegistryModelsInput input) {
        try {
            List<PromptTemplate> templates = promptTemplateService.listAll();
            List<Map<String, Object>> list = new ArrayList<>();
            for (PromptTemplate t : templates) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", t.getId());
                item.put("name", t.getName());
                item.put("variables", t.getVariables());
                item.put("description", t.getDescription());
                list.add(item);
            }
            return Map.of("status", "success", "templates", list, "count", list.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Text Pipeline Operations ====================

    public record CreatePipelineInput(String name, List<TextPipelineStep> steps, Map<String, String> variables) {}

    @Tool(name = "staging_llm_create_pipeline",
            description = "Create a text generation pipeline with sequential steps. Each step has a promptTemplate " +
                    "that can reference {{input}} and {{stepId_output}} variables. Parameters: name, steps (list), variables.")
    public Map<String, Object> llmCreatePipeline(CreatePipelineInput input) {
        if (input.steps() == null || input.steps().isEmpty()) {
            return Map.of("status", "error", "error", "steps list is required");
        }
        try {
            TextPipelineDefinition def = TextPipelineDefinition.builder()
                    .name(input.name())
                    .steps(input.steps())
                    .variables(input.variables())
                    .build();
            TextPipelineDefinition created = textPipelineService.create(def);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("id", created.getId());
            response.put("name", created.getName());
            response.put("stepCount", created.getSteps().size());
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to create pipeline: " + e.getMessage());
        }
    }

    public record ExecutePipelineInput(String id, Map<String, String> variables) {}

    @Tool(name = "staging_llm_execute_pipeline",
            description = "Execute a text generation pipeline by ID. Parameters: id (pipeline ID), variables (input variables).")
    public Map<String, Object> llmExecutePipeline(ExecutePipelineInput input) {
        if (input.id() == null) {
            return Map.of("status", "error", "error", "pipeline id is required");
        }
        try {
            TextPipelineResult result = textPipelineService.execute(input.id(), input.variables());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("pipelineId", result.getPipelineId());
            response.put("finalOutput", result.getFinalOutput());
            response.put("totalTimeMs", result.getTotalTimeMs());
            response.put("finishReason", result.getFinishReason());
            response.put("stepCount", result.getStepOutputs() != null ? result.getStepOutputs().size() : 0);
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Pipeline execution failed: " + e.getMessage());
        }
    }

    @Tool(name = "staging_llm_list_pipelines",
            description = "Lists all saved text generation pipelines.")
    public Map<String, Object> llmListPipelines(ListRegistryModelsInput input) {
        try {
            List<TextPipelineDefinition> pipelines = textPipelineService.listAll();
            List<Map<String, Object>> list = new ArrayList<>();
            for (TextPipelineDefinition p : pipelines) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.getId());
                item.put("name", p.getName());
                item.put("stepCount", p.getSteps() != null ? p.getSteps().size() : 0);
                list.add(item);
            }
            return Map.of("status", "success", "pipelines", list, "count", list.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== VLM Execution Operations ====================

    @Tool(name = "staging_vlm_available_models",
            description = "Lists available VLM (Vision Language Model) model sets that can be loaded.")
    public Map<String, Object> vlmAvailableModels(ListRegistryModelsInput input) {
        try {
            return Map.of("status", "success", "models", vlmExecutionService.getAvailableModels());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_vlm_load",
            description = "Loads a VLM model set for vision-language tasks (image understanding, OCR).")
    public Map<String, Object> vlmLoad(VlmLoadInput input) {
        if (input.modelSetId() == null) return Map.of("status", "error", "error", "modelSetId is required");
        try {
            Map<String, Object> result = vlmExecutionService.loadModel(input.modelSetId());
            result.put("status", "success");
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to load VLM: " + e.getMessage());
        }
    }

    @Tool(name = "staging_vlm_unload",
            description = "Unloads the currently loaded VLM model, freeing memory.")
    public Map<String, Object> vlmUnload(ListRegistryModelsInput input) {
        try {
            Map<String, Object> result = vlmExecutionService.unloadModel();
            result.put("status", "success");
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_vlm_status",
            description = "Gets the status of the currently loaded VLM model.")
    public Map<String, Object> vlmStatus(ListRegistryModelsInput input) {
        try {
            Map<String, Object> result = vlmExecutionService.getModelStatus();
            result.put("status", "success");
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_vlm_ocr_config",
            description = "Gets or updates VLM OCR configuration. Provide parameters to update, " +
                    "or call with no parameters to get current config.")
    public Map<String, Object> vlmOcrConfig(VlmOcrConfigInput input) {
        try {
            if (input.engineType() != null || input.language() != null || input.confidenceThreshold() != null) {
                OcrConfigRequest config = new OcrConfigRequest();
                if (input.engineType() != null) config.setEngineType(input.engineType());
                if (input.language() != null) config.setLanguage(input.language());
                if (input.confidenceThreshold() != null) config.setConfidenceThreshold(input.confidenceThreshold());
                Map<String, Object> result = vlmExecutionService.updateOcrConfig(config);
                result.put("status", "success");
                return result;
            } else {
                Map<String, Object> result = vlmExecutionService.getOcrConfig();
                result.put("status", "success");
                return result;
            }
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_vlm_parse_doctags",
            description = "Parses raw DocTags output from VLM into structured markdown and HTML.")
    public Map<String, Object> vlmParseDocTags(GetModelInput input) {
        if (input.modelId() == null) return Map.of("status", "error", "error", "rawDocTags text is required (pass as modelId)");
        try {
            DocTagsParseResponse result = vlmExecutionService.parseDocTags(input.modelId());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.isSuccess() ? "success" : "error");
            if (result.isSuccess()) {
                response.put("markdown", result.getMarkdown());
                response.put("html", result.getHtml());
                response.put("structuredElements", result.getStructuredElements());
            } else {
                response.put("error", result.getError());
            }
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Pipeline Operations ====================

    @Tool(name = "staging_pipeline_download",
            description = "Downloads a HuggingFace model for pipeline use. Provide the repoId (e.g. 'BAAI/bge-base-en-v1.5'). " +
                    "Optionally specify revision and authToken.")
    public Map<String, Object> pipelineDownload(PipelineDownloadInput input) {
        if (input.repoId() == null) return Map.of("status", "error", "error", "repoId is required");
        try {
            Path path = pipelineService.downloadFromHuggingFace(
                    input.repoId(),
                    input.revision() != null ? input.revision() : "main",
                    input.authToken());
            return Map.of("status", "success", "downloadPath", path.toString(), "repoId", input.repoId());
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to download: " + e.getMessage());
        }
    }

    @Tool(name = "staging_pipeline_inspect",
            description = "Inspects a downloaded model directory and returns pipeline information.")
    public Map<String, Object> pipelineInspect(PipelineInspectInput input) {
        if (input.modelPath() == null) return Map.of("status", "error", "error", "modelPath is required");
        try {
            var info = pipelineService.inspectModel(new java.io.File(input.modelPath()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("modelPath", input.modelPath());
            // Convert PipelineInfo to map using ObjectMapper
            @SuppressWarnings("unchecked")
            Map<String, Object> infoMap = objectMapper.convertValue(info, Map.class);
            response.putAll(infoMap);
            return response;
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to inspect model: " + e.getMessage());
        }
    }

    @Tool(name = "staging_pipeline_list_cached",
            description = "Lists all cached/downloaded pipeline models.")
    public Map<String, Object> pipelineListCached(ListRegistryModelsInput input) {
        try {
            List<String> cached = pipelineService.listCachedModels();
            return Map.of("status", "success", "cachedModels", cached, "totalCached", cached.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "staging_pipeline_delete_cached",
            description = "Deletes a cached pipeline model by model ID.")
    public Map<String, Object> pipelineDeleteCached(PipelineCacheInput input) {
        if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
        try {
            boolean deleted = pipelineService.deleteCachedModel(input.modelId());
            return Map.of("status", deleted ? "success" : "error",
                    "message", deleted ? "Model deleted from cache" : "Model not found in cache");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> trainingJobToMap(TrainingJobStatus job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "success");
        map.put("jobId", job.getJobId());
        map.put("jobStatus", job.getStatus());
        map.put("modelId", job.getModelId());
        map.put("datasetId", job.getDatasetId());
        map.put("currentEpoch", job.getCurrentEpoch());
        map.put("totalEpochs", job.getTotalEpochs());
        map.put("currentStep", job.getCurrentStep());
        map.put("totalSteps", job.getTotalSteps());
        map.put("loss", job.getLoss());
        map.put("learningRate", job.getLearningRate());
        map.put("overallProgress", job.getOverallProgress());
        if (job.getStartedAt() != null) map.put("startedAt", job.getStartedAt());
        if (job.getCompletedAt() != null) map.put("completedAt", job.getCompletedAt());
        if (job.getError() != null) map.put("error", job.getError());
        if (job.getOutputModelPath() != null) map.put("outputModelPath", job.getOutputModelPath());
        return map;
    }

    private Map<String, Object> compilationJobToMap(CompilationJobStatus job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "success");
        map.put("jobId", job.getJobId());
        map.put("modelId", job.getModelId());
        map.put("jobStatus", job.getStatus());
        map.put("compilationMode", job.getCompilationMode());
        map.put("executionMode", job.getExecutionMode());
        map.put("progressPercent", job.getProgressPercent());
        map.put("currentPhase", job.getCurrentPhase());
        map.put("message", job.getMessage());
        if (job.getStartedAt() != null) map.put("startedAt", job.getStartedAt());
        if (job.getCompletedAt() != null) map.put("completedAt", job.getCompletedAt());
        if (job.getError() != null) map.put("error", job.getError());
        return map;
    }

    private Map<String, Object> llmStatusToMap(LlmModelStatusResponse status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "success");
        map.put("modelId", status.getModelId());
        map.put("loaded", status.isLoaded());
        map.put("memoryUsageMb", status.getMemoryUsageMb());
        map.put("kvCacheType", status.getKvCacheType());
        map.put("message", status.getMessage());
        return map;
    }

    private Map<String, Object> datasetInfoToMap(DatasetInfo info) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", info.getId());
        map.put("name", info.getName());
        map.put("format", info.getFormat());
        map.put("task", info.getTask());
        map.put("sizeBytes", info.getSizeBytes());
        map.put("totalSamples", info.getTotalSamples());
        map.put("createdAt", info.getCreatedAt());
        return map;
    }

    private Map<String, Object> modelEntryToMap(ModelEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelId", entry.getModelId());
        map.put("type", entry.getType() != null ? entry.getType().getValue() : null);
        map.put("typeDisplay", entry.getType() != null ? entry.getType().getDisplayName() : null);
        map.put("status", entry.getStatus() != null ? entry.getStatus().getValue() : null);
        map.put("path", entry.getPath());
        map.put("modelFile", entry.getModelFile());
        map.put("vocabFile", entry.getVocabFile());
        map.put("version", entry.getEffectiveVersion());
        map.put("promotedAt", entry.getPromotedAt());

        if (entry.getMetadata() != null) {
            ModelMetadata meta = entry.getMetadata();
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (meta.getEmbeddingDim() != null && meta.getEmbeddingDim() > 0) metadata.put("embeddingDim", meta.getEmbeddingDim());
            if (meta.getHiddenSize() != null && meta.getHiddenSize() > 0) metadata.put("hiddenSize", meta.getHiddenSize());
            if (meta.getNumLayers() != null && meta.getNumLayers() > 0) metadata.put("numLayers", meta.getNumLayers());
            if (meta.getMaxSequenceLength() != null && meta.getMaxSequenceLength() > 0) metadata.put("maxSequenceLength", meta.getMaxSequenceLength());
            if (meta.getFramework() != null) metadata.put("framework", meta.getFramework());
            if (meta.getDescription() != null) metadata.put("description", meta.getDescription());
            if (meta.getVocabSize() != null && meta.getVocabSize() > 0) metadata.put("vocabSize", meta.getVocabSize());
            if (meta.getSourceOrigin() != null) metadata.put("sourceOrigin", meta.getSourceOrigin());
            if (meta.getSourceRepository() != null) metadata.put("sourceRepository", meta.getSourceRepository());
            if (meta.getTrainingData() != null) metadata.put("trainingData", meta.getTrainingData());
            if (meta.getEncoderType() != null) metadata.put("encoderType", meta.getEncoderType());
            if (meta.getRagRole() != null) metadata.put("ragRole", meta.getRagRole());
            if (meta.getVersion() != null) metadata.put("version", meta.getVersion());
            // OCR fields
            if (meta.getInputHeight() != null && meta.getInputHeight() > 0) metadata.put("inputHeight", meta.getInputHeight());
            if (meta.getInputWidth() != null && meta.getInputWidth() > 0) metadata.put("inputWidth", meta.getInputWidth());
            if (meta.getSupportedLanguages() != null && !meta.getSupportedLanguages().isEmpty()) metadata.put("supportedLanguages", meta.getSupportedLanguages());
            if (meta.getSupportsBatch() != null) metadata.put("supportsBatch", meta.getSupportsBatch());
            if (meta.getMaxBatchSize() != null && meta.getMaxBatchSize() > 0) metadata.put("maxBatchSize", meta.getMaxBatchSize());
            if (meta.getSupportsHandwriting() != null) metadata.put("supportsHandwriting", meta.getSupportsHandwriting());
            if (meta.getAverageAccuracy() != null) metadata.put("averageAccuracy", meta.getAverageAccuracy());
            if (meta.getOcrVocabSize() != null && meta.getOcrVocabSize() > 0) metadata.put("ocrVocabSize", meta.getOcrVocabSize());
            if (meta.getUsesCtc() != null) metadata.put("usesCtc", meta.getUsesCtc());
            // VLM fields
            if (meta.getVisionFrames() != null && meta.getVisionFrames() > 0) metadata.put("visionFrames", meta.getVisionFrames());
            if (meta.getImageSize() != null && meta.getImageSize() > 0) metadata.put("imageSize", meta.getImageSize());
            if (meta.getTileSize() != null && meta.getTileSize() > 0) metadata.put("tileSize", meta.getTileSize());
            if (meta.getComponents() != null && !meta.getComponents().isEmpty()) metadata.put("components", meta.getComponents());
            if (meta.getVisionEncoderPixelValuesName() != null) metadata.put("visionEncoderPixelValuesName", meta.getVisionEncoderPixelValuesName());
            if (meta.getVisionEncoderPixelAttentionMaskName() != null) metadata.put("visionEncoderPixelAttentionMaskName", meta.getVisionEncoderPixelAttentionMaskName());
            if (meta.getVisionEncoderPrimaryOutputName() != null) metadata.put("visionEncoderPrimaryOutputName", meta.getVisionEncoderPrimaryOutputName());
            if (meta.getVisionEncoderOutputNames() != null && !meta.getVisionEncoderOutputNames().isEmpty()) metadata.put("visionEncoderOutputNames", meta.getVisionEncoderOutputNames());
            if (!metadata.isEmpty()) map.put("metadata", metadata);
        }

        if (entry.getTokenizer() != null) {
            TokenizerConfig tok = entry.getTokenizer();
            Map<String, Object> tokenizer = new LinkedHashMap<>();
            tokenizer.put("doLowerCase", tok.isDoLowerCase());
            tokenizer.put("addSpecialTokens", tok.isAddSpecialTokens());
            if (tok.getMaxLength() > 0) tokenizer.put("maxLength", tok.getMaxLength());
            map.put("tokenizer", tokenizer);
        }

        if (entry.getPreprocessor() != null) {
            ImagePreprocessorConfig pp = entry.getPreprocessor();
            Map<String, Object> preprocessor = new LinkedHashMap<>();
            if (pp.getImageProcessorType() != null) preprocessor.put("imageProcessorType", pp.getImageProcessorType());
            preprocessor.put("doResize", pp.isDoResize());
            if (pp.getSizeHeight() != null) preprocessor.put("sizeHeight", pp.getSizeHeight());
            if (pp.getSizeWidth() != null) preprocessor.put("sizeWidth", pp.getSizeWidth());
            if (pp.getSizeShortestEdge() != null) preprocessor.put("sizeShortestEdge", pp.getSizeShortestEdge());
            if (pp.getSizeLongestEdge() != null) preprocessor.put("sizeLongestEdge", pp.getSizeLongestEdge());
            if (pp.getResample() != null) preprocessor.put("resample", pp.getResample());
            preprocessor.put("doRescale", pp.isDoRescale());
            if (pp.getRescaleFactor() != 0.0) preprocessor.put("rescaleFactor", pp.getRescaleFactor());
            preprocessor.put("doNormalize", pp.isDoNormalize());
            if (pp.getImageMean() != null) preprocessor.put("imageMean", pp.getImageMean());
            if (pp.getImageStd() != null) preprocessor.put("imageStd", pp.getImageStd());
            preprocessor.put("doConvertRgb", pp.isDoConvertRgb());
            preprocessor.put("doCenterCrop", pp.isDoCenterCrop());
            if (pp.getCropSizeHeight() != null) preprocessor.put("cropSizeHeight", pp.getCropSizeHeight());
            if (pp.getCropSizeWidth() != null) preprocessor.put("cropSizeWidth", pp.getCropSizeWidth());
            preprocessor.put("doPad", pp.isDoPad());
            if (pp.getPadSizeHeight() != null) preprocessor.put("padSizeHeight", pp.getPadSizeHeight());
            if (pp.getPadSizeWidth() != null) preprocessor.put("padSizeWidth", pp.getPadSizeWidth());
            if (pp.getPatchSize() != null) preprocessor.put("patchSize", pp.getPatchSize());
            if (pp.getNumChannels() > 0) preprocessor.put("numChannels", pp.getNumChannels());
            map.put("preprocessor", preprocessor);
        }

        return map;
    }

    private Map<String, Object> catalogModelToMap(CatalogModel model) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", model.getId());
        map.put("source", model.getSource());
        map.put("repo", model.getRepo());
        map.put("format", model.getFormat());
        if (model.getMetadata() != null) {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (model.getMetadata().getEmbeddingDim() != null) meta.put("embeddingDim", model.getMetadata().getEmbeddingDim());
            if (model.getMetadata().getHiddenSize() != null) meta.put("hiddenSize", model.getMetadata().getHiddenSize());
            if (model.getMetadata().getDescription() != null) meta.put("description", model.getMetadata().getDescription());
            if (!meta.isEmpty()) map.put("metadata", meta);
        }
        return map;
    }

    private Map<String, Object> stagingModelInfoToMap(StagingModelInfo info) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "success");
        map.put("modelId", info.getModelId());
        map.put("stagingStatus", info.getStatus() != null ? info.getStatus().name() : null);
        map.put("message", info.getMessage());
        map.put("progress", info.getProgress());
        map.put("source", info.getSource());
        map.put("type", info.getType() != null ? info.getType().getValue() : null);
        if (info.getStartedAt() != null) map.put("startedAt", info.getStartedAt());
        if (info.getCompletedAt() != null) map.put("completedAt", info.getCompletedAt());
        if (info.getError() != null) map.put("error", info.getError());
        return map;
    }
}
