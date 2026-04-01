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

package ai.kompile.ocr.models.pipeline;

import ai.kompile.ocr.*;
import ai.kompile.ocr.audit.AuditTrail;
import ai.kompile.ocr.audit.ConfidenceScore;
import ai.kompile.ocr.audit.ModelResult;
import ai.kompile.ocr.document.DocumentComplexity;
import ai.kompile.ocr.document.DocumentType;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.models.factory.OcrModelFactory;
import ai.kompile.ocr.structured.StructuredTable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Default OCR pipeline implementation.
 * Combines detection, recognition, table extraction, and layout analysis.
 */
@Component
@Qualifier("defaultOcrPipeline")
public class DefaultOcrPipeline implements OcrPipeline {

    private static final Logger logger = LoggerFactory.getLogger(DefaultOcrPipeline.class);

    private final OcrModelFactory modelFactory;

    private TextDetectionModel detectionModel;
    private TextRecognitionModel recognitionModel;
    private TableExtractionModel tableModel;
    private LayoutModel layoutModel;

    @Autowired
    public DefaultOcrPipeline(OcrModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public String getName() {
        return "Default OCR Pipeline";
    }

    @Override
    public String getDescription() {
        return "Standard OCR pipeline with detection, recognition, and optional table/layout extraction";
    }

    @Override
    public ParsedDocument processImage(INDArray image) {
        return processImage(image, OcrPipelineConfig.defaultConfig());
    }

    @Override
    public ParsedDocument processImage(INDArray image, OcrPipelineConfig config) {
        AuditTrail audit = AuditTrail.forPage(config.getSourceId(), 1);

        try {
            ensureModelsLoaded(config);

            // Step 1: Detection
            long detStartTime = System.currentTimeMillis();
            List<TextDetectionModel.DetectedRegion> detectedRegions = detectionModel.detect(image);
            long detTime = System.currentTimeMillis() - detStartTime;

            audit.addModelResult(ModelResult.success(
                    detectionModel.getModelId(),
                    OcrModelType.OCR_DETECTION,
                    detTime,
                    detectedRegions.size(),
                    detectedRegions.stream()
                            .mapToDouble(TextDetectionModel.DetectedRegion::confidence)
                            .average()
                            .orElse(0.0)
            ));

            // Step 2: Recognition
            long recStartTime = System.currentTimeMillis();
            List<OcrRegion> ocrRegions = new ArrayList<>();

            for (int i = 0; i < detectedRegions.size(); i++) {
                TextDetectionModel.DetectedRegion region = detectedRegions.get(i);

                // Crop region from image
                INDArray cropped = cropRegion(image, region.bbox());

                // Recognize text
                TextRecognitionModel.RecognizedText recognized = recognitionModel.recognize(cropped);

                OcrRegion ocrRegion = OcrRegion.builder()
                        .index(i)
                        .text(recognized.text())
                        .boundingBox(region.bbox())
                        .detectionConfidence(region.confidence())
                        .recognitionConfidence(recognized.confidence())
                        .build();

                ocrRegions.add(ocrRegion);

                cropped.close();
            }

            long recTime = System.currentTimeMillis() - recStartTime;

            audit.addModelResult(ModelResult.success(
                    recognitionModel.getModelId(),
                    OcrModelType.OCR_RECOGNITION,
                    recTime,
                    ocrRegions.size(),
                    ocrRegions.stream()
                            .mapToDouble(OcrRegion::getRecognitionConfidence)
                            .average()
                            .orElse(0.0)
            ));

            // Build OCR result
            OcrResult ocrResult = OcrResult.builder()
                    .id(UUID.randomUUID().toString())
                    .sourceId(config.getSourceId())
                    .pageNumber(1)
                    .regions(ocrRegions)
                    .overallConfidence(ocrRegions.stream()
                            .mapToDouble(OcrRegion::getCombinedConfidence)
                            .average()
                            .orElse(0.0))
                    .detectionModelId(detectionModel.getModelId())
                    .recognitionModelId(recognitionModel.getModelId())
                    .processingTimeMs(detTime + recTime)
                    .success(true)
                    .build();

            // Build full text
            StringBuilder fullText = new StringBuilder();
            for (OcrRegion region : ocrRegions) {
                if (fullText.length() > 0) {
                    fullText.append("\n");
                }
                fullText.append(region.getText());
            }

            // Step 3: Table extraction (optional)
            List<StructuredTable> tables = new ArrayList<>();
            if (config.isEnableTableExtraction() && tableModel != null) {
                long tableStartTime = System.currentTimeMillis();
                tables = tableModel.extractTablesWithText(image, recognitionModel);
                long tableTime = System.currentTimeMillis() - tableStartTime;

                audit.addModelResult(ModelResult.success(
                        tableModel.getModelId(),
                        OcrModelType.OCR_TABLE,
                        tableTime,
                        tables.size(),
                        1.0
                ));
            }

            // Compute overall confidence
            double overallConfidence = ocrResult.getOverallConfidence();
            audit.addConfidenceScore("overall", ConfidenceScore.overall(overallConfidence));

            audit.complete();

            return ParsedDocument.builder()
                    .id(UUID.randomUUID().toString())
                    .sourceId(config.getSourceId())
                    .pageNumber(1)
                    .documentType(DocumentType.SCANNED)
                    .complexity(tables.isEmpty() ? DocumentComplexity.SIMPLE : DocumentComplexity.TABLES)
                    .text(fullText.toString())
                    .ocrResult(ocrResult)
                    .tables(tables)
                    .auditTrail(config.isIncludeAuditTrail() ? audit : null)
                    .success(true)
                    .processingTimeMs(audit.getTotalProcessingTimeMs())
                    .build();

        } catch (Exception e) {
            logger.error("OCR processing failed: {}", e.getMessage(), e);
            audit.complete();
            return ParsedDocument.failed(config.getSourceId(), 1, e.getMessage());
        }
    }

    @Override
    public List<ParsedDocument> processPdf(File pdfFile) {
        return processPdf(pdfFile, OcrPipelineConfig.defaultConfig(), null);
    }

    @Override
    public List<ParsedDocument> processPdf(File pdfFile, OcrPipelineConfig config,
                                           Consumer<PipelineProgress> progressCallback) {
        List<ParsedDocument> results = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();

            if (progressCallback != null) {
                progressCallback.accept(PipelineProgress.starting(totalPages));
            }

            List<Integer> pagesToProcess = config.parsePageRange(totalPages);
            if (pagesToProcess == null) {
                pagesToProcess = new ArrayList<>();
                for (int i = 1; i <= totalPages; i++) {
                    pagesToProcess.add(i);
                }
            }

            for (int pageNum : pagesToProcess) {
                if (progressCallback != null) {
                    progressCallback.accept(PipelineProgress.processing(pageNum, totalPages, "OCR"));
                }

                // Render page to image
                BufferedImage pageImage = renderer.renderImageWithDPI(pageNum - 1, config.getPdfRenderDpi());

                // Convert to INDArray
                INDArray imageArray = bufferedImageToINDArray(pageImage);

                // Update config with page-specific info
                OcrPipelineConfig pageConfig = OcrPipelineConfig.builder()
                        .detectionModelId(config.getDetectionModelId())
                        .recognitionModelId(config.getRecognitionModelId())
                        .tableModelId(config.getTableModelId())
                        .layoutModelId(config.getLayoutModelId())
                        .enableTableExtraction(config.isEnableTableExtraction())
                        .enableLayoutAnalysis(config.isEnableLayoutAnalysis())
                        .includeAuditTrail(config.isIncludeAuditTrail())
                        .sourceId(pdfFile.getAbsolutePath())
                        .collectionName(config.getCollectionName())
                        .build();

                // Process page
                ParsedDocument pageResult = processImage(imageArray, pageConfig);
                pageResult.setPageNumber(pageNum);
                pageResult.setTotalPages(totalPages);

                results.add(pageResult);

                imageArray.close();
            }

            if (progressCallback != null) {
                progressCallback.accept(PipelineProgress.completed(totalPages));
            }

        } catch (Exception e) {
            logger.error("Failed to process PDF: {}", e.getMessage(), e);
            results.add(ParsedDocument.failed(pdfFile.getAbsolutePath(), 0, e.getMessage()));
        }

        return results;
    }

    @Override
    public List<ParsedDocument> processPdf(InputStream pdfStream, String sourceId) {
        // Create temp file and delegate
        try {
            File tempFile = File.createTempFile("ocr_", ".pdf");
            tempFile.deleteOnExit();
            java.nio.file.Files.copy(pdfStream, tempFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            OcrPipelineConfig config = OcrPipelineConfig.builder()
                    .sourceId(sourceId)
                    .build();

            return processPdf(tempFile, config, null);
        } catch (Exception e) {
            logger.error("Failed to process PDF stream: {}", e.getMessage(), e);
            List<ParsedDocument> results = new ArrayList<>();
            results.add(ParsedDocument.failed(sourceId, 0, e.getMessage()));
            return results;
        }
    }

    @Override
    public ParsedDocument processPage(File pdfFile, int pageNumber) {
        OcrPipelineConfig config = OcrPipelineConfig.builder()
                .sourceId(pdfFile.getAbsolutePath())
                .pageRange(String.valueOf(pageNumber))
                .build();

        List<ParsedDocument> results = processPdf(pdfFile, config, null);
        return results.isEmpty() ?
                ParsedDocument.failed(pdfFile.getAbsolutePath(), pageNumber, "No results") :
                results.get(0);
    }

    @Override
    public PipelineModels getModels() {
        return new PipelineModels(detectionModel, recognitionModel, tableModel, layoutModel);
    }

    @Override
    public boolean isReady() {
        return detectionModel != null && detectionModel.isLoaded() &&
               recognitionModel != null && recognitionModel.isLoaded();
    }

    @Override
    public void loadModels() throws Exception {
        if (detectionModel == null) {
            detectionModel = modelFactory.getDefaultDetectionModel().orElse(null);
        }
        if (recognitionModel == null) {
            recognitionModel = modelFactory.getDefaultRecognitionModel().orElse(null);
        }

        if (detectionModel != null && !detectionModel.isLoaded()) {
            detectionModel.load();
        }
        if (recognitionModel != null && !recognitionModel.isLoaded()) {
            recognitionModel.load();
        }
    }

    @Override
    public void unloadModels() {
        if (detectionModel != null) {
            detectionModel.unload();
        }
        if (recognitionModel != null) {
            recognitionModel.unload();
        }
        if (tableModel != null) {
            tableModel.unload();
        }
        if (layoutModel != null) {
            layoutModel.unload();
        }
    }

    /**
     * Ensures required models are loaded based on config.
     */
    private void ensureModelsLoaded(OcrPipelineConfig config) throws Exception {
        // Detection model
        String detModelId = config.getDetectionModelId();
        if (detModelId != null) {
            detectionModel = modelFactory.getDetectionModel(detModelId).orElse(null);
        }
        if (detectionModel == null) {
            detectionModel = modelFactory.getDefaultDetectionModel().orElse(null);
        }
        if (detectionModel == null) {
            throw new IllegalStateException("No detection model available");
        }
        if (!detectionModel.isLoaded()) {
            detectionModel.load();
        }

        // Recognition model
        String recModelId = config.getRecognitionModelId();
        if (recModelId != null) {
            recognitionModel = modelFactory.getRecognitionModel(recModelId).orElse(null);
        }
        if (recognitionModel == null) {
            recognitionModel = modelFactory.getDefaultRecognitionModel().orElse(null);
        }
        if (recognitionModel == null) {
            throw new IllegalStateException("No recognition model available");
        }
        if (!recognitionModel.isLoaded()) {
            recognitionModel.load();
        }

        // Table model (optional)
        if (config.isEnableTableExtraction() && config.getTableModelId() != null) {
            tableModel = modelFactory.getTableModel(config.getTableModelId()).orElse(null);
            if (tableModel != null && !tableModel.isLoaded()) {
                tableModel.load();
            }
        }

        // Layout model (optional)
        if (config.isEnableLayoutAnalysis() && config.getLayoutModelId() != null) {
            layoutModel = modelFactory.getLayoutModel(config.getLayoutModelId()).orElse(null);
            if (layoutModel != null && !layoutModel.isLoaded()) {
                layoutModel.load();
            }
        }
    }

    /**
     * Crops a region from an image.
     */
    private INDArray cropRegion(INDArray image, BoundingBox bbox) {
        long[] shape = image.shape();
        int h = (int) shape[2];
        int w = (int) shape[3];

        int x1 = Math.max(0, bbox.getX());
        int y1 = Math.max(0, bbox.getY());
        int x2 = Math.min(w, bbox.getX() + bbox.getWidth());
        int y2 = Math.min(h, bbox.getY() + bbox.getHeight());

        if (x2 <= x1 || y2 <= y1) {
            // Return small empty region
            return Nd4j.zeros(1, shape[1], 32, 32);
        }

        return image.get(
                org.nd4j.linalg.indexing.NDArrayIndex.all(),
                org.nd4j.linalg.indexing.NDArrayIndex.all(),
                org.nd4j.linalg.indexing.NDArrayIndex.interval(y1, y2),
                org.nd4j.linalg.indexing.NDArrayIndex.interval(x1, x2)
        ).dup();
    }

    /**
     * Converts a BufferedImage to INDArray.
     * Uses bulk pixel extraction to avoid per-pixel JNI overhead.
     */
    private INDArray bufferedImageToINDArray(BufferedImage image) {
        int h = image.getHeight();
        int w = image.getWidth();

        // Bulk extract all pixels at once (single call vs w*h calls)
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

        // Pre-allocate flat array for tensor data
        float[] data = new float[3 * h * w];

        // Single pass: separate channels (pure Java, no JNI)
        int hwSize = h * w;
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // NCHW format - vectorized channel placement
            data[i] = r / 255.0f;
            data[hwSize + i] = g / 255.0f;
            data[2 * hwSize + i] = b / 255.0f;
        }

        return Nd4j.create(data, new int[]{1, 3, h, w}, 'c');
    }
}
