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

package ai.kompile.ocr.datapipeline;

import ai.kompile.ocr.BoundingBox;
import ai.kompile.ocr.OcrRegion;
import ai.kompile.ocr.datapipeline.api.PipelineConfigStore;
import ai.kompile.ocr.datapipeline.config.DataPipelineConfig;
import ai.kompile.ocr.datapipeline.entity.DocumentEntity;
import ai.kompile.ocr.datapipeline.index.EntityIndexer;
import ai.kompile.ocr.datapipeline.parse.OutputParser;
import ai.kompile.ocr.datapipeline.parse.ParsedOutput;
import ai.kompile.ocr.datapipeline.preprocess.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for OCR data pipelines.
 * Handles preprocessing, output parsing, and entity indexing.
 *
 * <p>This class coordinates the data transformation pipeline:
 * <ol>
 *   <li>Preprocess: Image → Tensor (with optional bbox normalization)</li>
 *   <li>Parse: Model output → Structured entities</li>
 *   <li>Index: Entities → Searchable documents</li>
 * </ol>
 *
 * <p>Model execution is external - this class only handles data transformation.
 */
public class OcrDataPipeline {

    private static final Logger log = LoggerFactory.getLogger(OcrDataPipeline.class);

    private final PipelineConfigStore configStore;
    private final ImagePreprocessor preprocessor;
    private final BBoxNormalizer bboxNormalizer;
    private final OutputParser outputParser;
    private final EntityIndexer entityIndexer;

    /**
     * Creates a pipeline with the specified config directory.
     *
     * @param configDir Directory for storing pipeline configurations
     */
    public OcrDataPipeline(Path configDir) {
        this.configStore = new PipelineConfigStore(configDir);
        this.preprocessor = new ImagePreprocessor();
        this.bboxNormalizer = new BBoxNormalizer();
        this.outputParser = new OutputParser();
        this.entityIndexer = new EntityIndexer();
    }

    /**
     * Creates a pipeline with an existing config store.
     */
    public OcrDataPipeline(PipelineConfigStore configStore) {
        this.configStore = configStore;
        this.preprocessor = new ImagePreprocessor();
        this.bboxNormalizer = new BBoxNormalizer();
        this.outputParser = new OutputParser();
        this.entityIndexer = new EntityIndexer();
    }

    // ============ Preprocessing ============

    /**
     * Preprocesses an image for model input.
     *
     * @param image Input image
     * @param configId Pipeline configuration ID
     * @return Preprocessed input ready for model
     */
    public PreprocessedInput preprocess(BufferedImage image, String configId) {
        DataPipelineConfig config = configStore.getOrDefault(configId);
        return preprocessor.process(image, config.getPreprocess());
    }

    /**
     * Preprocesses with OCR results (for LayoutLM-style models).
     *
     * @param image Input image
     * @param ocrRegions OCR regions with text and bounding boxes
     * @param configId Pipeline configuration ID
     * @return Preprocessed input with aligned bounding boxes
     */
    public PreprocessedInput preprocessWithOcr(BufferedImage image, List<OcrRegion> ocrRegions, String configId) {
        DataPipelineConfig config = configStore.getOrDefault(configId);

        // Preprocess image
        PreprocessedInput baseInput = preprocessor.process(image, config.getPreprocess());

        // Normalize bounding boxes
        List<BoundingBox> boxes = ocrRegions.stream()
                .map(OcrRegion::getBoundingBox)
                .toList();

        List<int[]> normalizedBoxes = bboxNormalizer.normalize(
                boxes,
                baseInput.originalWidth(),
                baseInput.originalHeight(),
                config.getPreprocess().getBbox()
        );

        // Create tokenized regions
        List<TokenizedRegion> regions = new ArrayList<>();
        for (int i = 0; i < ocrRegions.size(); i++) {
            OcrRegion region = ocrRegions.get(i);
            regions.add(new TokenizedRegion(
                    null,  // Tokens added by tokenizer
                    null,  // Token IDs added by tokenizer
                    List.of(normalizedBoxes.get(i)),
                    region.getText(),
                    region.getRecognitionConfidence()
            ));
        }

        return PreprocessedInput.withRegions(
                baseInput.image(),
                regions,
                baseInput.originalWidth(),
                baseInput.originalHeight(),
                baseInput.processedWidth(),
                baseInput.processedHeight()
        );
    }

    // ============ Output Parsing ============

    /**
     * Parses model output into structured entities.
     *
     * @param modelOutput Raw model output string
     * @param configId Pipeline configuration ID
     * @return Parsed output with extracted entities
     */
    public ParsedOutput parseOutput(String modelOutput, String configId) {
        DataPipelineConfig config = configStore.getOrDefault(configId);
        return outputParser.parse(modelOutput, config.getOutputParse());
    }

    /**
     * Parses with auto-detected format.
     *
     * @param modelOutput Raw model output string
     * @param configId Pipeline configuration ID (for other settings)
     * @return Parsed output with extracted entities
     */
    public ParsedOutput parseOutputAutoDetect(String modelOutput, String configId) {
        DataPipelineConfig config = configStore.getOrDefault(configId);

        // Detect format
        var detectedFormat = outputParser.detectFormat(modelOutput);

        // Create config with detected format
        var parseConfig = config.getOutputParse().toBuilder()
                .expectedFormat(detectedFormat)
                .build();

        return outputParser.parse(modelOutput, parseConfig);
    }

    // ============ Entity Indexing ============

    /**
     * Indexes extracted entities for vector search.
     *
     * @param entities List of extracted entities
     * @param sourceDocId ID of the source document
     * @param configId Pipeline configuration ID
     * @return List of searchable documents
     */
    public List<Document> indexEntities(List<DocumentEntity> entities, String sourceDocId, String configId) {
        DataPipelineConfig config = configStore.getOrDefault(configId);
        return entityIndexer.index(entities, sourceDocId, config.getEntityIndex());
    }

    // ============ Full Pipeline ============

    /**
     * Runs the complete data pipeline: parse output → extract entities → index.
     *
     * @param modelOutput Raw model output string
     * @param sourceDocId ID of the source document
     * @param configId Pipeline configuration ID
     * @return Pipeline result with entities and indexed documents
     */
    public PipelineResult process(String modelOutput, String sourceDocId, String configId) {
        // Parse output
        ParsedOutput parsed = parseOutput(modelOutput, configId);

        // Index entities
        List<Document> indexedDocs = indexEntities(parsed.entities(), sourceDocId, configId);

        return new PipelineResult(
                parsed.entities(),
                indexedDocs,
                parsed.rawOutput(),
                parsed.fullText()
        );
    }

    /**
     * Runs preprocessing and returns configuration for external model execution.
     *
     * @param image Input image
     * @param configId Pipeline configuration ID
     * @return Preprocessing result with model input
     */
    public PreprocessResult preprocessForModel(BufferedImage image, String configId) {
        DataPipelineConfig config = configStore.getOrDefault(configId);
        PreprocessedInput input = preprocessor.process(image, config.getPreprocess());

        return new PreprocessResult(
                input,
                config.getPipelineType(),
                config.getPreprocess().isRequiresExternalOcr()
        );
    }

    // ============ Configuration Access ============

    /**
     * Gets a pipeline configuration.
     */
    public DataPipelineConfig getConfig(String configId) {
        return configStore.get(configId);
    }

    /**
     * Lists all available configurations.
     */
    public List<DataPipelineConfig> listConfigs() {
        return configStore.list();
    }

    /**
     * Gets the config store for direct access.
     */
    public PipelineConfigStore getConfigStore() {
        return configStore;
    }

    // ============ Result Records ============

    /**
     * Result of the complete pipeline.
     */
    public record PipelineResult(
            List<DocumentEntity> entities,
            List<Document> indexedDocuments,
            String rawOutput,
            String fullText
    ) {
        public int getEntityCount() {
            return entities != null ? entities.size() : 0;
        }

        public int getDocumentCount() {
            return indexedDocuments != null ? indexedDocuments.size() : 0;
        }
    }

    /**
     * Result of preprocessing stage.
     */
    public record PreprocessResult(
            PreprocessedInput input,
            DataPipelineConfig.PipelineType pipelineType,
            boolean requiresExternalOcr
    ) {}
}
