# Kompile OCR Module - Architecture Design

## Executive Summary

This document outlines the design for a comprehensive OCR (Optical Character Recognition) module for Kompile that supports intelligent document parsing with table extraction, multiple OCR models, layout understanding, and LLM-based post-processing. The module integrates seamlessly into the existing "Add Fact" workflow as a parse step before chunking.

**Key Design Principle**: All OCR capabilities are implemented as **importable models** that run natively in SameDiff/ND4J. External services (Python subprocesses, external APIs) are separated into an optional external module for cases where native models aren't available.

---

## Goals

1. **Model-Based OCR**: PaddleOCR, DocTR, CRNN models imported to SameDiff format
2. **Intelligent Table Extraction**: TableFormer and similar models for structured table parsing
3. **Layout Understanding**: LayoutLM/LayoutLMv3 as SameDiff models for spatial field mapping
4. **LLM Post-Processing**: Qwen 2.5 (or other LLMs) for OCR correction and normalization
5. **Full Auditability**: Every extracted field traceable to source coordinates with confidence scores
6. **Model Management Integration**: All OCR models managed through the staging/registry system
7. **External Services Separation**: Python/external services in separate optional module
8. **Developer Experience**: Configuration via UI and model debugging tools

---

## Architecture Overview

### Model-First Design

All OCR functionality is implemented as **SameDiff models** that are:
1. Imported from ONNX/PyTorch via the model staging service
2. Registered in the model registry with type `ocr_*`
3. Loaded and executed natively in Java via ND4J

External services (Python subprocesses, cloud APIs) are in a **separate optional module** for:
- Development/testing when models aren't yet imported
- Fallback when native models have issues
- Access to capabilities not yet available as importable models

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            OCR Processing Pipeline                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                         Document Intake (Existing)                       │   │
│  │   DocumentLoader → DocumentSourceDescriptor → Fact Entity               │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                           │
│                                      ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                       OCR Processing Step (NEW)                          │   │
│  │                                                                           │   │
│  │   ┌─────────────────────────────────────────────────────────────────┐   │   │
│  │   │                    Document Analyzer                             │   │   │
│  │   │   - Detect document type (scanned, digital, mixed)               │   │   │
│  │   │   - Identify complexity (tables, forms, handwriting)             │   │   │
│  │   │   - Route to appropriate OCR model(s)                            │   │   │
│  │   └─────────────────────────────────────────────────────────────────┘   │   │
│  │                              │                                           │   │
│  │                              ▼                                           │   │
│  │   ┌───────────────────────────────────────────────────────────────┐    │   │
│  │   │              OCR Models (SameDiff - Native Execution)          │    │   │
│  │   │                                                                │    │   │
│  │   │  ┌────────────────────────────────────────────────────────┐   │    │   │
│  │   │  │  TEXT DETECTION MODELS (ocr_detection)                  │   │    │   │
│  │   │  │  - PaddleOCR Detection (DB/EAST)                        │   │    │   │
│  │   │  │  - DocTR Detection (DBNet)                              │   │    │   │
│  │   │  │  - CRAFT Text Detector                                  │   │    │   │
│  │   │  └────────────────────────────────────────────────────────┘   │    │   │
│  │   │                         │                                      │    │   │
│  │   │                         ▼                                      │    │   │
│  │   │  ┌────────────────────────────────────────────────────────┐   │    │   │
│  │   │  │  TEXT RECOGNITION MODELS (ocr_recognition)              │   │    │   │
│  │   │  │  - PaddleOCR Recognition (CRNN/SVTR)                    │   │    │   │
│  │   │  │  - DocTR Recognition (CRNN/ViTSTR)                      │   │    │   │
│  │   │  │  - TrOCR (Transformer OCR)                              │   │    │   │
│  │   │  └────────────────────────────────────────────────────────┘   │    │   │
│  │   │                         │                                      │    │   │
│  │   │                         ▼                                      │    │   │
│  │   │  ┌────────────────────────────────────────────────────────┐   │    │   │
│  │   │  │  TABLE EXTRACTION MODELS (ocr_table)                    │   │    │   │
│  │   │  │  - TableFormer                                          │   │    │   │
│  │   │  │  - PubLayNet Table Detector                             │   │    │   │
│  │   │  │  - Docling TableStructure                               │   │    │   │
│  │   │  └────────────────────────────────────────────────────────┘   │    │   │
│  │   └───────────────────────────────────────────────────────────────┘    │   │
│  │                              │                                           │   │
│  │                              ▼                                           │   │
│  │   ┌───────────────────────────────────────────────────────────────┐    │   │
│  │   │           Layout Understanding Models (SameDiff)               │    │   │
│  │   │                                                                │    │   │
│  │   │  ┌────────────────────────────────────────────────────────┐   │    │   │
│  │   │  │  LAYOUT MODELS (layout_model)                           │   │    │   │
│  │   │  │  - LayoutLM / LayoutLMv2 / LayoutLMv3                   │   │    │   │
│  │   │  │  - DiT (Document Image Transformer)                     │   │    │   │
│  │   │  │  - Donut (Document Understanding Transformer)           │   │    │   │
│  │   │  │                                                         │   │    │   │
│  │   │  │  Tasks:                                                 │   │    │   │
│  │   │  │  - Form field extraction (name, date, amount)           │   │    │   │
│  │   │  │  - Table cell relationships                             │   │    │   │
│  │   │  │  - Multi-column structure                               │   │    │   │
│  │   │  │  - Document classification                              │   │    │   │
│  │   │  └────────────────────────────────────────────────────────┘   │    │   │
│  │   └───────────────────────────────────────────────────────────────┘    │   │
│  │                              │                                           │   │
│  │                              ▼                                           │   │
│  │   ┌───────────────────────────────────────────────────────────────┐    │   │
│  │   │               LLM Post-Processing (Optional)                   │    │   │
│  │   │                                                                │    │   │
│  │   │  Uses existing LLM providers (OpenAI, Anthropic, Local):       │    │   │
│  │   │  - Handwriting interpretation                                  │    │   │
│  │   │  - Format normalization                                        │    │   │
│  │   │  - Error correction                                            │    │   │
│  │   │  - Field identification                                        │    │   │
│  │   └───────────────────────────────────────────────────────────────┘    │   │
│  │                              │                                           │   │
│  │                              ▼                                           │   │
│  │   ┌───────────────────────────────────────────────────────────────┐    │   │
│  │   │                Rule-Based Validation                           │    │   │
│  │   │                                                                │    │   │
│  │   │   - Cross-document consistency checks                          │    │   │
│  │   │   - Value range validation (dates, amounts)                    │    │   │
│  │   │   - Format verification (SSN, phone, etc)                      │    │   │
│  │   │   - Conflict detection (flag, don't auto-correct)              │    │   │
│  │   └───────────────────────────────────────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                           │
│                                      ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                  OCR Result → Parsed Document                            │   │
│  │                                                                           │   │
│  │   ParsedDocument {                                                       │   │
│  │     text: String,                                                        │   │
│  │     tables: List<StructuredTable>,                                       │   │
│  │     fields: List<ExtractedField>,                                        │   │
│  │     audit: AuditTrail {                                                  │   │
│  │       regions: List<Region>,        // Source coordinates                │   │
│  │       confidence: Map<Region, Float>,                                    │   │
│  │       models: List<ModelResult>,    // Per-model results                │   │
│  │       validations: List<ValidationResult>                                │   │
│  │     }                                                                    │   │
│  │   }                                                                      │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                           │
│                                      ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                      Existing Chunking Pipeline                          │   │
│  │   Chunker → Embeddings → VectorStore → KnowledgeGraph (optional)        │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│                    EXTERNAL SERVICES (Separate Module)                          │
│                       kompile-ocr-external (optional)                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Used when native models aren't available or for specific capabilities:         │
│                                                                                  │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                 │
│  │  Tesseract CLI  │  │  Cloud OCR APIs │  │  Python Scripts │                 │
│  │  (subprocess)   │  │  (Google, AWS)  │  │  (PaddleOCR,    │                 │
│  │                 │  │                 │  │   DocTR, etc)   │                 │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘                 │
│                                                                                  │
│  Interface: ExternalOcrService                                                   │
│  - Same OcrModel interface as native models                                      │
│  - Fallback when registry model not found                                        │
│  - Development/testing before model import                                       │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Module Structure

### Core Modules (Model-Based)

```
kompile-app/
├── kompile-ocr-core/                          # Core OCR interfaces and types
│   ├── src/main/java/ai/kompile/ocr/
│   │   ├── OcrModel.java                      # Base OCR model interface
│   │   ├── OcrModelType.java                  # Model type enum
│   │   ├── OcrModelRouter.java                # Routes documents to models
│   │   ├── OcrResult.java                     # OCR result with confidence
│   │   ├── OcrRegion.java                     # Bounding box + text
│   │   ├── OcrPipeline.java                   # Full pipeline orchestrator
│   │   ├── OcrPipelineConfig.java             # Configuration
│   │   │
│   │   ├── document/
│   │   │   ├── DocumentAnalyzer.java          # Analyzes document type
│   │   │   ├── DocumentType.java              # Enum: SCANNED, DIGITAL, MIXED
│   │   │   ├── DocumentComplexity.java        # Enum: SIMPLE, TABLES, FORMS, MIXED
│   │   │   └── ParsedDocument.java            # Final parsed result
│   │   │
│   │   ├── structured/
│   │   │   ├── StructuredTable.java           # Table with rows/cols
│   │   │   ├── ExtractedField.java            # Named field extraction
│   │   │   ├── FieldType.java                 # DATE, AMOUNT, NAME, etc.
│   │   │   └── TableCell.java                 # Cell with position
│   │   │
│   │   ├── audit/
│   │   │   ├── AuditTrail.java                # Full audit record
│   │   │   ├── ModelResult.java               # Per-model result
│   │   │   ├── ValidationResult.java          # Validation outcome
│   │   │   └── ConfidenceScore.java           # Confidence with source
│   │   │
│   │   └── validation/
│   │       ├── OcrValidator.java              # Validation interface
│   │       ├── DateValidator.java             # Date format validation
│   │       ├── AmountValidator.java           # Currency/amount validation
│   │       ├── CrossDocumentValidator.java    # Cross-doc consistency
│   │       └── ValidationRule.java            # Configurable rules
│   │
│   └── pom.xml
│
├── kompile-ocr-models/                        # SameDiff-based OCR model implementations
│   ├── src/main/java/ai/kompile/ocr/models/
│   │   │
│   │   ├── detection/                         # Text detection models
│   │   │   ├── TextDetectionModel.java        # Detection model interface
│   │   │   ├── DBNetDetector.java             # DBNet (PaddleOCR/DocTR style)
│   │   │   ├── EASTDetector.java              # EAST text detector
│   │   │   └── CRAFTDetector.java             # CRAFT text detector
│   │   │
│   │   ├── recognition/                       # Text recognition models
│   │   │   ├── TextRecognitionModel.java      # Recognition model interface
│   │   │   ├── CRNNRecognizer.java            # CRNN-based recognition
│   │   │   ├── SVTRRecognizer.java            # SVTR (PaddleOCR v4)
│   │   │   ├── ViTSTRRecognizer.java          # Vision Transformer STR
│   │   │   └── TrOCRRecognizer.java           # Transformer OCR
│   │   │
│   │   ├── table/                             # Table extraction models
│   │   │   ├── TableExtractionModel.java      # Table model interface
│   │   │   ├── TableFormerModel.java          # TableFormer structure recognition
│   │   │   ├── TableDetectorModel.java        # Table detection (PubLayNet)
│   │   │   └── DoclingTableModel.java         # Docling-style table extraction
│   │   │
│   │   ├── layout/                            # Layout understanding models
│   │   │   ├── LayoutModel.java               # Layout model interface
│   │   │   ├── LayoutLMModel.java             # LayoutLM implementation
│   │   │   ├── LayoutLMv3Model.java           # LayoutLMv3 implementation
│   │   │   ├── DiTModel.java                  # Document Image Transformer
│   │   │   ├── DonutModel.java                # Donut document understanding
│   │   │   └── FieldMapper.java               # Maps OCR to structured fields
│   │   │
│   │   ├── pipeline/                          # End-to-end OCR pipelines
│   │   │   ├── PaddleOcrPipeline.java         # PaddleOCR detection + recognition
│   │   │   ├── DocTrPipeline.java             # DocTR detection + recognition
│   │   │   └── CustomOcrPipeline.java         # User-configured pipeline
│   │   │
│   │   └── OcrModelFactory.java               # Creates models from registry
│   │
│   └── pom.xml
│
├── kompile-ocr-postprocess/                   # LLM post-processing
│   ├── src/main/java/ai/kompile/ocr/postprocess/
│   │   ├── OcrPostProcessor.java              # Post-processing interface
│   │   ├── LlmPostProcessor.java              # LLM-based post-processing
│   │   ├── HandwritingInterpreter.java        # Handwriting normalization
│   │   ├── FormatNormalizer.java              # Format standardization
│   │   └── OcrCorrector.java                  # Error correction
│   │
│   └── pom.xml
│
└── kompile-ocr-integration/                   # Integration with main app
    ├── src/main/java/ai/kompile/ocr/integration/
    │   ├── OcrDocumentProcessor.java          # Pre-chunking processor
    │   ├── OcrFactSheetExtension.java         # FactSheet OCR config
    │   ├── OcrConfigController.java           # REST API for config
    │   └── OcrPipelineService.java            # Main orchestration service
    │
    └── pom.xml
```

### External Services Module (Separate - Optional)

```
kompile-ocr-external/                          # EXTERNAL SERVICES (separate repo or module)
├── src/main/java/ai/kompile/ocr/external/
│   │
│   ├── ExternalOcrService.java                # Interface matching OcrModel
│   ├── ExternalOcrServiceRegistry.java        # Registry of available services
│   │
│   ├── tesseract/                             # Tesseract CLI wrapper
│   │   ├── TesseractCliService.java           # Subprocess execution
│   │   └── TesseractConfig.java               # Configuration
│   │
│   ├── cloud/                                 # Cloud OCR APIs
│   │   ├── GoogleVisionOcrService.java        # Google Cloud Vision
│   │   ├── AwsTextractService.java            # AWS Textract
│   │   └── AzureFormRecognizerService.java    # Azure Form Recognizer
│   │
│   ├── python/                                # Python subprocess wrappers
│   │   ├── PythonOcrService.java              # Base Python subprocess
│   │   ├── PaddleOcrPythonService.java        # PaddleOCR via Python
│   │   ├── DocTrPythonService.java            # DocTR via Python
│   │   └── CamelotPythonService.java          # Camelot table extraction
│   │
│   └── config/
│       └── ExternalOcrAutoConfiguration.java  # Spring auto-config
│
├── src/main/python/                           # Python scripts
│   ├── paddle_ocr_runner.py
│   ├── doctr_runner.py
│   └── camelot_extractor.py
│
└── pom.xml
```

### Model Import Extensions (in kompile-model-staging)

```
kompile-model-staging/
└── src/main/java/ai/kompile/staging/
    ├── conversion/
    │   ├── ocr/                               # OCR model converters
    │   │   ├── PaddleOcrImporter.java         # Import PaddleOCR models
    │   │   ├── DocTrImporter.java             # Import DocTR models
    │   │   ├── LayoutLMImporter.java          # Import LayoutLM models
    │   │   ├── TableFormerImporter.java       # Import TableFormer
    │   │   └── TrOCRImporter.java             # Import TrOCR models
    │   │
    │   └── OcrModelConverter.java             # Orchestrates OCR model import
    │
    └── catalog/
        └── ocr-models.yml                     # Catalog of importable OCR models
```

---

## Core Interfaces

### 1. OcrModel Interface (Model-Based)

```java
package ai.kompile.ocr;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.nd4j.linalg.api.ndarray.INDArray;

/**
 * Base interface for OCR models.
 * All OCR capabilities are implemented as SameDiff models loaded from the registry.
 */
public interface OcrModel {

    /**
     * Model ID from the registry (e.g., "paddleocr-det-db", "layoutlm-base").
     */
    String getModelId();

    /**
     * Model type (detection, recognition, table, layout).
     */
    OcrModelType getModelType();

    /**
     * Human-readable name.
     */
    String getName();

    /**
     * Checks if this model is loaded and ready.
     */
    boolean isLoaded();

    /**
     * Loads the model from the registry.
     */
    void load() throws Exception;

    /**
     * Unloads the model to free resources.
     */
    void unload();

    /**
     * Returns model capabilities.
     */
    ModelCapabilities getCapabilities();

    /**
     * Model capabilities descriptor.
     */
    record ModelCapabilities(
        OcrModelType type,
        boolean supportsHandwriting,
        boolean supportsBatch,
        List<String> supportedLanguages,
        int maxBatchSize,
        long inputHeight,
        long inputWidth,
        double averageAccuracy          // 0.0-1.0
    ) {}
}

/**
 * Model type enumeration.
 */
public enum OcrModelType {
    // Text detection - finds text regions in images
    OCR_DETECTION,

    // Text recognition - converts text region images to strings
    OCR_RECOGNITION,

    // Table extraction - identifies and structures tables
    OCR_TABLE,

    // Layout understanding - maps text to semantic fields
    LAYOUT_MODEL,

    // End-to-end pipeline (detection + recognition combined)
    OCR_PIPELINE,

    // Document classification
    DOCUMENT_CLASSIFIER
}
```

### 1b. Specialized Model Interfaces

```java
/**
 * Text detection model - finds text regions in images.
 * Examples: DBNet, EAST, CRAFT
 */
public interface TextDetectionModel extends OcrModel {

    /**
     * Detects text regions in an image.
     *
     * @param image Input image as INDArray [1, C, H, W]
     * @return List of detected text regions with bounding boxes
     */
    List<DetectedRegion> detect(INDArray image);

    /**
     * Batch detection for multiple images.
     */
    List<List<DetectedRegion>> detectBatch(INDArray images);

    record DetectedRegion(
        BoundingBox bbox,
        double confidence,
        INDArray polygon          // Optional: polygon points for rotated text
    ) {}
}

/**
 * Text recognition model - converts text region images to strings.
 * Examples: CRNN, SVTR, ViTSTR, TrOCR
 */
public interface TextRecognitionModel extends OcrModel {

    /**
     * Recognizes text in a cropped text region image.
     *
     * @param regionImage Cropped text region as INDArray [1, C, H, W]
     * @return Recognized text with confidence
     */
    RecognizedText recognize(INDArray regionImage);

    /**
     * Batch recognition for multiple regions.
     */
    List<RecognizedText> recognizeBatch(INDArray regionImages);

    /**
     * Gets the character vocabulary for this model.
     */
    List<String> getVocabulary();

    record RecognizedText(
        String text,
        double confidence,
        List<CharConfidence> charConfidences  // Per-character confidence
    ) {}

    record CharConfidence(char character, double confidence) {}
}

/**
 * Table extraction model - identifies and structures tables.
 * Examples: TableFormer, PubLayNet, Docling TableStructure
 */
public interface TableExtractionModel extends OcrModel {

    /**
     * Extracts table structure from an image.
     *
     * @param image Page image containing tables
     * @return List of detected tables with structure
     */
    List<DetectedTable> extractTables(INDArray image);

    /**
     * Extracts table structure given known table bounds.
     */
    TableStructure extractStructure(INDArray tableImage, BoundingBox tableBbox);

    record DetectedTable(
        BoundingBox bbox,
        double confidence,
        TableStructure structure
    ) {}

    record TableStructure(
        int rowCount,
        int columnCount,
        List<TableCell> cells,
        List<Integer> rowSeparators,    // Y coordinates
        List<Integer> colSeparators     // X coordinates
    ) {}

    record TableCell(
        int row,
        int column,
        int rowSpan,
        int colSpan,
        BoundingBox bbox,
        boolean isHeader
    ) {}
}

/**
 * Layout understanding model - maps text to semantic fields.
 * Examples: LayoutLM, LayoutLMv3, DiT, Donut
 */
public interface LayoutModel extends OcrModel {

    /**
     * Extracts structured fields from OCR output + image.
     *
     * @param words      OCR words with bounding boxes
     * @param pageImage  Original page image (for visual features)
     * @param fieldTypes Target field types to extract
     * @return Extracted fields with confidence
     */
    List<ExtractedField> extractFields(
        List<OcrWord> words,
        INDArray pageImage,
        List<FieldType> fieldTypes
    );

    /**
     * Classifies document type.
     */
    DocumentClassification classifyDocument(INDArray pageImage);

    /**
     * Input word with bounding box for layout model.
     */
    record OcrWord(
        String text,
        BoundingBox bbox,
        double confidence
    ) {}

    record DocumentClassification(
        String documentType,
        double confidence,
        Map<String, Double> allScores
    ) {}
}
```

### 2. OcrResult

```java
package ai.kompile.ocr;

import java.util.List;
import java.util.Map;

/**
 * Result from an OCR engine including text, regions, and confidence.
 */
public record OcrResult(
    String engineId,
    int pageNumber,
    String fullText,
    List<OcrRegion> regions,
    List<OcrLine> lines,
    List<OcrWord> words,
    double overallConfidence,
    Map<String, Object> engineMetadata,
    long processingTimeMs
) {
    /**
     * A detected region in the document.
     */
    public record OcrRegion(
        String id,
        BoundingBox bbox,
        String text,
        double confidence,
        RegionType type
    ) {}

    /**
     * A line of text.
     */
    public record OcrLine(
        String id,
        BoundingBox bbox,
        String text,
        List<OcrWord> words,
        double confidence
    ) {}

    /**
     * A single word.
     */
    public record OcrWord(
        String id,
        BoundingBox bbox,
        String text,
        double confidence,
        Map<String, Object> attributes  // font, size, style hints
    ) {}

    /**
     * Bounding box with coordinates.
     */
    public record BoundingBox(
        int x,
        int y,
        int width,
        int height,
        double rotation  // degrees
    ) {
        public int x2() { return x + width; }
        public int y2() { return y + height; }

        public boolean contains(int px, int py) {
            return px >= x && px <= x2() && py >= y && py <= y2();
        }

        public boolean overlaps(BoundingBox other) {
            return !(other.x() > x2() || other.x2() < x ||
                     other.y() > y2() || other.y2() < y);
        }
    }

    public enum RegionType {
        TEXT_BLOCK,
        TABLE,
        FIGURE,
        HEADER,
        FOOTER,
        FORM_FIELD,
        SIGNATURE,
        HANDWRITING,
        BARCODE,
        QR_CODE
    }
}
```

### 3. OcrPipeline

```java
package ai.kompile.ocr;

import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.audit.AuditTrail;

import java.io.File;
import java.util.function.Consumer;

/**
 * Main OCR pipeline orchestrator.
 * Coordinates document analysis, engine routing, layout understanding,
 * post-processing, and validation.
 */
public interface OcrPipeline {

    /**
     * Processes a document through the full OCR pipeline.
     *
     * @param document The input document file
     * @param config Pipeline configuration
     * @param progressCallback Optional progress callback
     * @return Parsed document with full audit trail
     */
    ParsedDocument process(
        File document,
        OcrPipelineConfig config,
        Consumer<OcrProgress> progressCallback
    );

    /**
     * Processes a document with default configuration.
     */
    default ParsedDocument process(File document) {
        return process(document, OcrPipelineConfig.defaults(), null);
    }

    /**
     * Gets the current pipeline configuration.
     */
    OcrPipelineConfig getConfig();

    /**
     * Updates pipeline configuration.
     */
    void updateConfig(OcrPipelineConfig config);

    /**
     * Progress information during processing.
     */
    record OcrProgress(
        Phase phase,
        int currentPage,
        int totalPages,
        String currentEngine,
        double progressPercent,
        String message
    ) {
        public enum Phase {
            ANALYZING,          // Document analysis
            OCR_PROCESSING,     // Running OCR engines
            TABLE_EXTRACTION,   // Extracting tables
            LAYOUT_ANALYSIS,    // Layout understanding
            POST_PROCESSING,    // LLM post-processing
            VALIDATION,         // Running validators
            COMPLETE
        }
    }
}
```

### 4. ParsedDocument

```java
package ai.kompile.ocr.document;

import ai.kompile.ocr.structured.*;
import ai.kompile.ocr.audit.AuditTrail;

import java.util.List;
import java.util.Map;

/**
 * Fully parsed document with structured content and audit trail.
 */
public record ParsedDocument(
    String documentId,
    String sourceFile,
    int pageCount,

    // Extracted content
    String fullText,                           // Plain text content
    List<PageContent> pages,                   // Per-page content
    List<StructuredTable> tables,              // Extracted tables
    List<ExtractedField> fields,               // Identified fields

    // Audit and traceability
    AuditTrail audit,

    // Metadata
    Map<String, Object> metadata,
    long totalProcessingTimeMs
) {
    /**
     * Content for a single page.
     */
    public record PageContent(
        int pageNumber,
        String text,
        List<OcrResult.OcrRegion> regions,
        List<StructuredTable> tables,
        List<ExtractedField> fields,
        double confidence
    ) {}

    /**
     * Converts to Spring AI Documents for chunking.
     */
    public List<org.springframework.ai.document.Document> toSpringDocuments() {
        // Implementation converts parsed content to Spring AI Documents
        // with metadata including audit information
        return null; // Implemented in actual class
    }
}
```

### 5. StructuredTable

```java
package ai.kompile.ocr.structured;

import ai.kompile.ocr.OcrResult.BoundingBox;

import java.util.List;
import java.util.Map;

/**
 * Represents an extracted table with full structure.
 */
public record StructuredTable(
    String tableId,
    int pageNumber,
    BoundingBox bbox,

    // Structure
    int rowCount,
    int columnCount,
    List<String> headers,
    List<List<TableCell>> rows,

    // Extraction info
    String extractionMethod,       // "camelot", "tabula", "doctr", "paddleocr"
    double confidence,

    // For audit
    Map<TableCell, BoundingBox> cellBoundingBoxes
) {
    /**
     * Converts to markdown format.
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        // Header row
        if (headers != null && !headers.isEmpty()) {
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|").append(" --- |".repeat(headers.size())).append("\n");
        }

        // Data rows
        for (List<TableCell> row : rows) {
            sb.append("| ");
            for (TableCell cell : row) {
                sb.append(cell.value()).append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Converts to CSV format.
     */
    public String toCsv() {
        // Implementation
        return null;
    }

    /**
     * Converts to JSON structure.
     */
    public Map<String, Object> toJson() {
        // Implementation
        return null;
    }
}
```

### 6. ExtractedField

```java
package ai.kompile.ocr.structured;

import ai.kompile.ocr.OcrResult.BoundingBox;

import java.util.Map;

/**
 * A field extracted from the document with semantic meaning.
 */
public record ExtractedField(
    String fieldId,
    String name,                    // e.g., "applicant_name", "loan_amount"
    FieldType type,
    String rawValue,                // As extracted
    String normalizedValue,         // After normalization
    int pageNumber,
    BoundingBox bbox,
    double confidence,
    String extractionSource,        // Which engine/model extracted it
    Map<String, Object> attributes  // Additional type-specific attributes
) {
    public enum FieldType {
        TEXT,
        DATE,
        AMOUNT,
        CURRENCY,
        PERCENTAGE,
        NAME,
        ADDRESS,
        PHONE,
        EMAIL,
        SSN,
        ACCOUNT_NUMBER,
        SIGNATURE,
        CHECKBOX,
        CUSTOM
    }
}
```

### 7. AuditTrail

```java
package ai.kompile.ocr.audit;

import ai.kompile.ocr.OcrResult;
import ai.kompile.ocr.OcrResult.BoundingBox;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete audit trail for document processing.
 * Provides full traceability for every extraction.
 */
public record AuditTrail(
    String documentId,
    Instant processedAt,

    // Engine results (before aggregation)
    List<EngineResult> engineResults,

    // Per-region provenance
    Map<String, RegionProvenance> regionProvenance,

    // Validation results
    List<ValidationResult> validationResults,

    // Conflicts detected
    List<Conflict> conflicts,

    // Processing log
    List<ProcessingLogEntry> processingLog
) {
    /**
     * Result from a single OCR engine.
     */
    public record EngineResult(
        String engineId,
        String engineVersion,
        int pageNumber,
        OcrResult result,
        long processingTimeMs,
        Map<String, Object> engineMetadata
    ) {}

    /**
     * Provenance information for a region.
     * Tracks exactly where each piece of text came from.
     */
    public record RegionProvenance(
        String regionId,
        int pageNumber,
        BoundingBox originalBbox,
        String sourceEngine,
        double confidence,
        String rawText,
        String normalizedText,
        List<String> contributingEngines,  // If merged from multiple
        Map<String, Double> engineConfidences
    ) {}

    /**
     * Validation result for a check.
     */
    public record ValidationResult(
        String validatorId,
        String fieldId,
        boolean passed,
        String message,
        ValidationSeverity severity
    ) {
        public enum ValidationSeverity { INFO, WARNING, ERROR }
    }

    /**
     * Conflict between extractions.
     */
    public record Conflict(
        String fieldId,
        List<ConflictingValue> values,
        ConflictType type,
        String resolution           // null if unresolved (flagged for review)
    ) {
        public record ConflictingValue(
            String value,
            String source,
            double confidence
        ) {}

        public enum ConflictType {
            ENGINE_DISAGREEMENT,        // Different engines extracted different values
            CROSS_DOCUMENT_MISMATCH,    // Same field differs across documents
            VALIDATION_FAILURE,         // Value failed validation
            FORMAT_INCONSISTENCY        // Format differs from expected
        }
    }

    /**
     * Log entry for processing steps.
     */
    public record ProcessingLogEntry(
        Instant timestamp,
        String phase,
        String message,
        Map<String, Object> details
    ) {}
}
```

---

## Configuration

### OcrPipelineConfig

```java
package ai.kompile.ocr;

import java.util.List;
import java.util.Map;

/**
 * Configuration for the OCR pipeline.
 */
public record OcrPipelineConfig(
    // Engine selection
    EngineConfig engines,

    // Layout understanding
    LayoutConfig layout,

    // Post-processing
    PostProcessingConfig postProcessing,

    // Validation
    ValidationConfig validation,

    // Output options
    OutputConfig output,

    // Performance
    PerformanceConfig performance
) {
    public record EngineConfig(
        String primaryEngine,               // "paddleocr", "doctr", "tesseract"
        String fallbackEngine,              // Used if primary fails
        boolean enableMultiEngine,          // Run multiple and aggregate
        List<String> enabledEngines,        // When multiEngine=true
        Map<String, Object> engineOptions   // Per-engine options
    ) {}

    public record LayoutConfig(
        boolean enabled,
        String modelId,                     // "layoutlm-base", "layoutlmv3"
        String modelSource,                 // "registry", "archive", "default"
        List<String> targetFieldTypes,      // Fields to extract
        double confidenceThreshold
    ) {}

    public record PostProcessingConfig(
        boolean enabled,
        String llmProvider,                 // "openai", "anthropic", "local"
        String modelId,                     // "qwen-2.5-72b", "gpt-4", etc.
        boolean correctHandwriting,
        boolean normalizeFormats,
        boolean identifyFields,
        double confidenceThreshold          // Only post-process below this
    ) {}

    public record ValidationConfig(
        boolean enabled,
        List<String> enabledValidators,
        boolean flagConflicts,              // Don't auto-correct, flag for review
        boolean crossDocumentChecks
    ) {}

    public record OutputConfig(
        boolean includeFullAudit,
        boolean preserveBoundingBoxes,
        String tableFormat,                 // "markdown", "csv", "json"
        boolean embedTablesInline
    ) {}

    public record PerformanceConfig(
        int maxConcurrentPages,
        int batchSize,
        boolean enableGpu,
        long timeoutMs
    ) {}

    public static OcrPipelineConfig defaults() {
        return new OcrPipelineConfig(
            new EngineConfig("paddleocr", "tesseract", false, List.of(), Map.of()),
            new LayoutConfig(true, "layoutlm-base", "default", List.of(), 0.7),
            new PostProcessingConfig(false, null, null, false, false, false, 0.5),
            new ValidationConfig(true, List.of("date", "amount"), true, false),
            new OutputConfig(true, true, "markdown", false),
            new PerformanceConfig(4, 10, false, 300000)
        );
    }
}
```

---

## FactSheet Integration

The OCR configuration will be part of the FactSheet entity, allowing per-sheet OCR settings:

```java
// Extension to FactSheet entity

/**
 * OCR configuration fields for FactSheet.
 */

// ==================== OCR Configuration ====================

/**
 * Whether OCR processing is enabled for documents in this fact sheet.
 */
@Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
@Builder.Default
private Boolean ocrEnabled = false;

/**
 * Primary OCR engine to use: 'tesseract', 'paddleocr', 'doctr', 'custom'.
 */
@Column(length = 32)
@Builder.Default
private String ocrPrimaryEngine = "paddleocr";

/**
 * Fallback OCR engine if primary fails.
 */
@Column(length = 32)
@Builder.Default
private String ocrFallbackEngine = "tesseract";

/**
 * Whether to enable layout understanding (LayoutLM).
 */
@Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
@Builder.Default
private Boolean ocrLayoutEnabled = true;

/**
 * Layout model ID from registry.
 */
@Column(length = 128)
private String ocrLayoutModelId;

/**
 * Whether to enable LLM post-processing.
 */
@Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
@Builder.Default
private Boolean ocrLlmPostProcessEnabled = false;

/**
 * LLM provider for post-processing.
 */
@Column(length = 32)
private String ocrLlmProvider;

/**
 * LLM model ID for post-processing.
 */
@Column(length = 128)
private String ocrLlmModelId;

/**
 * JSON-serialized full OCR pipeline configuration.
 * Allows advanced users to configure all options.
 */
@Column(columnDefinition = "TEXT")
private String ocrPipelineConfigJson;
```

---

## Model Management Integration

All OCR models are managed through the existing model staging/registry system, following the same pattern as embedding models.

### Registry Extension for OCR Models

```json
{
  "version": "1.0",
  "updated_at": "2025-01-15T10:30:00Z",
  "models": {
    // ==================== TEXT DETECTION MODELS ====================
    "paddleocr-det-db-en": {
      "type": "ocr_detection",
      "path": "ocr/detection/paddleocr-det-db-en",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "promoted_at": "2025-01-15T10:30:00Z",
      "metadata": {
        "framework": "samediff",
        "source_framework": "paddle",
        "architecture": "DBNet",
        "input_height": 960,
        "input_width": 960,
        "languages": ["en"]
      }
    },
    "doctr-det-db-resnet50": {
      "type": "ocr_detection",
      "path": "ocr/detection/doctr-det-db-resnet50",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "DBNet-ResNet50",
        "input_height": 1024,
        "input_width": 1024,
        "languages": ["en", "fr", "de"]
      }
    },
    "craft-text-detector": {
      "type": "ocr_detection",
      "path": "ocr/detection/craft",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "CRAFT",
        "supports_curved_text": true
      }
    },

    // ==================== TEXT RECOGNITION MODELS ====================
    "paddleocr-rec-svtr-en": {
      "type": "ocr_recognition",
      "path": "ocr/recognition/paddleocr-rec-svtr-en",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "paddle",
        "architecture": "SVTR",
        "input_height": 48,
        "input_width": 320,
        "vocab_size": 6625,
        "languages": ["en"]
      }
    },
    "doctr-rec-crnn-vgg16": {
      "type": "ocr_recognition",
      "path": "ocr/recognition/doctr-rec-crnn-vgg16",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "CRNN-VGG16",
        "input_height": 32,
        "input_width": 128
      }
    },
    "trocr-base-handwritten": {
      "type": "ocr_recognition",
      "path": "ocr/recognition/trocr-base-handwritten",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "TrOCR",
        "supports_handwriting": true,
        "hidden_size": 768
      }
    },

    // ==================== TABLE EXTRACTION MODELS ====================
    "tableformer-structure": {
      "type": "ocr_table",
      "path": "ocr/table/tableformer-structure",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "TableFormer",
        "task": "table_structure_recognition"
      }
    },
    "docling-tablestructure": {
      "type": "ocr_table",
      "path": "ocr/table/docling-tablestructure",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "DoclingTableStructure",
        "task": "table_structure_recognition"
      }
    },
    "publaynet-table-detector": {
      "type": "ocr_table",
      "path": "ocr/table/publaynet-table-detector",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "MaskRCNN",
        "task": "table_detection"
      }
    },

    // ==================== LAYOUT UNDERSTANDING MODELS ====================
    "layoutlm-base-uncased": {
      "type": "layout_model",
      "path": "ocr/layout/layoutlm-base-uncased",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "LayoutLM",
        "hidden_size": 768,
        "num_layers": 12,
        "max_position_embeddings": 512,
        "max_2d_position_embeddings": 1024,
        "input_types": ["text", "bbox"]
      }
    },
    "layoutlmv3-base": {
      "type": "layout_model",
      "path": "ocr/layout/layoutlmv3-base",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "LayoutLMv3",
        "hidden_size": 768,
        "num_layers": 12,
        "max_2d_position_embeddings": 1024,
        "input_types": ["text", "bbox", "image"]
      }
    },
    "dit-base-finetuned-rvlcdip": {
      "type": "layout_model",
      "path": "ocr/layout/dit-base-finetuned-rvlcdip",
      "model_file": "model.sdz",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "DiT",
        "task": "document_classification",
        "num_classes": 16
      }
    },
    "donut-base-finetuned-docvqa": {
      "type": "layout_model",
      "path": "ocr/layout/donut-base-finetuned-docvqa",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:...",
      "status": "active",
      "metadata": {
        "framework": "samediff",
        "source_framework": "pytorch",
        "architecture": "Donut",
        "task": "document_qa",
        "ocr_free": true
      }
    }
  }
}
```

### OCR Model Catalog (ocr-models.yml)

This catalog defines available OCR models that can be imported via the staging service:

```yaml
# ~/.kompile/staging/catalog/ocr-models.yml

ocr_models:
  # ==================== TEXT DETECTION ====================
  detection:
    - id: paddleocr-det-db-en
      name: "PaddleOCR DBNet (English)"
      source: huggingface
      repo: PaddlePaddle/PaddleOCR
      format: paddle
      files:
        model: ch_PP-OCRv4_det_infer/inference.pdmodel
        params: ch_PP-OCRv4_det_infer/inference.pdiparams
      metadata:
        architecture: DBNet
        input_shape: [1, 3, 960, 960]
        languages: [en]

    - id: doctr-det-db-resnet50
      name: "DocTR DBNet ResNet50"
      source: huggingface
      repo: mindee/doctr
      format: pytorch
      files:
        model: db_resnet50/model.pt
      metadata:
        architecture: DBNet-ResNet50
        input_shape: [1, 3, 1024, 1024]

    - id: craft-text-detector
      name: "CRAFT Text Detector"
      source: huggingface
      repo: clovaai/CRAFT
      format: pytorch
      files:
        model: craft_mlt_25k.pth
      metadata:
        architecture: CRAFT
        supports_curved_text: true

  # ==================== TEXT RECOGNITION ====================
  recognition:
    - id: paddleocr-rec-svtr-en
      name: "PaddleOCR SVTR (English)"
      source: huggingface
      repo: PaddlePaddle/PaddleOCR
      format: paddle
      files:
        model: en_PP-OCRv4_rec_infer/inference.pdmodel
        params: en_PP-OCRv4_rec_infer/inference.pdiparams
        vocab: en_dict.txt
      metadata:
        architecture: SVTR
        input_shape: [1, 3, 48, 320]

    - id: trocr-base-handwritten
      name: "TrOCR Base Handwritten"
      source: huggingface
      repo: microsoft/trocr-base-handwritten
      format: pytorch
      files:
        model: pytorch_model.bin
        vocab: vocab.json
      metadata:
        architecture: TrOCR
        supports_handwriting: true

    - id: doctr-rec-crnn-vgg16
      name: "DocTR CRNN VGG16"
      source: huggingface
      repo: mindee/doctr
      format: pytorch
      files:
        model: crnn_vgg16_bn/model.pt
        vocab: vocab.txt
      metadata:
        architecture: CRNN-VGG16

  # ==================== TABLE EXTRACTION ====================
  table:
    - id: tableformer-structure
      name: "TableFormer Structure Recognition"
      source: huggingface
      repo: microsoft/table-transformer-structure-recognition
      format: pytorch
      files:
        model: pytorch_model.bin
      metadata:
        architecture: TableFormer
        task: table_structure_recognition

    - id: docling-tablestructure
      name: "Docling Table Structure"
      source: huggingface
      repo: ds4sd/docling-models
      format: pytorch
      files:
        model: tableformer/model.pt
      metadata:
        architecture: DoclingTableStructure

    - id: publaynet-table-detector
      name: "PubLayNet Table Detector"
      source: huggingface
      repo: hantian/publaynet
      format: pytorch
      files:
        model: model_final.pth
      metadata:
        architecture: MaskRCNN
        task: table_detection

  # ==================== LAYOUT UNDERSTANDING ====================
  layout:
    - id: layoutlm-base-uncased
      name: "LayoutLM Base"
      source: huggingface
      repo: microsoft/layoutlm-base-uncased
      format: pytorch
      files:
        model: pytorch_model.bin
        vocab: vocab.txt
        config: config.json
      metadata:
        architecture: LayoutLM
        hidden_size: 768
        num_layers: 12

    - id: layoutlmv3-base
      name: "LayoutLMv3 Base"
      source: huggingface
      repo: microsoft/layoutlmv3-base
      format: pytorch
      files:
        model: pytorch_model.bin
        vocab: sentencepiece.bpe.model
        config: config.json
      metadata:
        architecture: LayoutLMv3
        hidden_size: 768
        num_layers: 12

    - id: dit-base-finetuned-rvlcdip
      name: "DiT Document Classification"
      source: huggingface
      repo: microsoft/dit-base-finetuned-rvlcdip
      format: pytorch
      files:
        model: pytorch_model.bin
      metadata:
        architecture: DiT
        task: document_classification
        num_classes: 16

    - id: donut-base-finetuned-docvqa
      name: "Donut Document QA"
      source: huggingface
      repo: naver-clova-ix/donut-base-finetuned-docvqa
      format: pytorch
      files:
        model: pytorch_model.bin
        vocab: vocab.json
      metadata:
        architecture: Donut
        task: document_qa
        ocr_free: true
```

### Model Import Process

OCR models are imported using the existing staging service:

```bash
# Import a detection model
kompile-staging import-ocr \
  --model-id=paddleocr-det-db-en \
  --from-catalog

# Import from HuggingFace directly
kompile-staging import-ocr \
  --model-id=layoutlmv3-base \
  --source=huggingface \
  --repo=microsoft/layoutlmv3-base \
  --format=pytorch

# Promote to production
kompile-staging promote \
  --model=paddleocr-det-db-en \
  --run-inference-test

# List available OCR models
kompile-staging list --type=ocr_detection
kompile-staging list --type=ocr_recognition
kompile-staging list --type=ocr_table
kompile-staging list --type=layout_model
```

---

## REST API

### OCR Configuration Controller

```java
@RestController
@RequestMapping("/api/ocr")
public class OcrConfigController {

    /**
     * Get available OCR engines.
     */
    @GetMapping("/engines")
    public List<OcrEngineInfo> getAvailableEngines();

    /**
     * Get available layout models.
     */
    @GetMapping("/models/layout")
    public List<ModelInfo> getLayoutModels();

    /**
     * Get OCR configuration for a fact sheet.
     */
    @GetMapping("/factsheets/{factSheetId}/config")
    public OcrPipelineConfig getFactSheetOcrConfig(@PathVariable Long factSheetId);

    /**
     * Update OCR configuration for a fact sheet.
     */
    @PutMapping("/factsheets/{factSheetId}/config")
    public OcrPipelineConfig updateFactSheetOcrConfig(
        @PathVariable Long factSheetId,
        @RequestBody OcrPipelineConfig config
    );

    /**
     * Test OCR on a sample document.
     */
    @PostMapping("/test")
    public OcrTestResult testOcr(
        @RequestParam("file") MultipartFile file,
        @RequestBody OcrPipelineConfig config
    );

    /**
     * Get OCR processing status for a fact.
     */
    @GetMapping("/facts/{factId}/status")
    public OcrProcessingStatus getOcrStatus(@PathVariable Long factId);

    /**
     * Get audit trail for a processed document.
     */
    @GetMapping("/facts/{factId}/audit")
    public AuditTrail getAuditTrail(@PathVariable Long factId);

    /**
     * Get extracted fields for a document.
     */
    @GetMapping("/facts/{factId}/fields")
    public List<ExtractedField> getExtractedFields(@PathVariable Long factId);

    /**
     * Get extracted tables for a document.
     */
    @GetMapping("/facts/{factId}/tables")
    public List<StructuredTable> getExtractedTables(@PathVariable Long factId);
}
```

---

## UI Integration

### Model Debug Component Extension

The existing model-debug component will be extended to show OCR models:

```typescript
// OCR model display in model-debug.component.ts

interface OcrEngineInfo {
  engineId: string;
  name: string;
  version: string;
  capabilities: {
    supportsHandwriting: boolean;
    supportsTables: boolean;
    supportsLayout: boolean;
    supportedLanguages: string[];
  };
  status: 'available' | 'unavailable' | 'loading';
}

interface LayoutModelInfo {
  modelId: string;
  type: 'layoutlm' | 'layoutlmv3' | 'custom';
  hiddenSize: number;
  maxPositions: number;
  status: 'active' | 'staged' | 'downloading';
  source: 'registry' | 'archive';
}

// New section in model-debug component
getOcrEngines(): OcrEngineInfo[] {
  // Fetch from /api/ocr/engines
}

getLayoutModels(): LayoutModelInfo[] {
  // Fetch from /api/ocr/models/layout
}
```

### OCR Configuration UI

New component for configuring OCR in the fact sheet editor:

```typescript
// ocr-config.component.ts

@Component({
  selector: 'app-ocr-config',
  template: `
    <div class="ocr-config-panel">
      <mat-slide-toggle [(ngModel)]="config.ocrEnabled">
        Enable OCR Processing
      </mat-slide-toggle>

      <div *ngIf="config.ocrEnabled" class="ocr-options">
        <!-- Engine Selection -->
        <mat-form-field>
          <mat-label>Primary OCR Engine</mat-label>
          <mat-select [(ngModel)]="config.ocrPrimaryEngine">
            <mat-option value="paddleocr">PaddleOCR (Recommended)</mat-option>
            <mat-option value="doctr">DocTR (Layout-heavy)</mat-option>
            <mat-option value="tesseract">Tesseract (Fallback)</mat-option>
          </mat-select>
        </mat-form-field>

        <!-- Layout Understanding -->
        <mat-slide-toggle [(ngModel)]="config.ocrLayoutEnabled">
          Enable Layout Understanding (LayoutLM)
        </mat-slide-toggle>

        <mat-form-field *ngIf="config.ocrLayoutEnabled">
          <mat-label>Layout Model</mat-label>
          <mat-select [(ngModel)]="config.ocrLayoutModelId">
            <mat-option *ngFor="let model of layoutModels" [value]="model.modelId">
              {{ model.modelId }}
            </mat-option>
          </mat-select>
        </mat-form-field>

        <!-- LLM Post-Processing -->
        <mat-slide-toggle [(ngModel)]="config.ocrLlmPostProcessEnabled">
          Enable LLM Post-Processing
        </mat-slide-toggle>

        <!-- Advanced Config -->
        <mat-expansion-panel>
          <mat-expansion-panel-header>Advanced Configuration</mat-expansion-panel-header>
          <textarea [(ngModel)]="config.ocrPipelineConfigJson"
                    placeholder="JSON configuration"></textarea>
        </mat-expansion-panel>
      </div>
    </div>
  `
})
export class OcrConfigComponent {
  @Input() factSheetId: number;
  config: OcrConfig;
  layoutModels: LayoutModelInfo[];
}
```

---

## Processing Flow

### Document Ingestion with OCR

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Document Upload (Add Fact)                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. User uploads document to FactSheet                                  │
│                                                                         │
│  2. DocumentLoader detects file type                                    │
│     - PDF, image (TIFF, PNG, JPG), Office docs                          │
│                                                                         │
│  3. Check FactSheet.ocrEnabled                                          │
│     - If false: proceed to standard text extraction                     │
│     - If true: route to OCR pipeline                                    │
│                                                                         │
│  4. OCR Pipeline Processing                                             │
│     a. DocumentAnalyzer examines document:                              │
│        - Is it scanned or digital?                                      │
│        - Does it contain tables?                                        │
│        - Is there handwriting?                                          │
│                                                                         │
│     b. Route to appropriate engine(s) based on analysis                 │
│        - Clean scans → PaddleOCR + Camelot                              │
│        - Layout-heavy → DocTR                                           │
│        - Simple text → Tesseract (or fallback)                          │
│                                                                         │
│     c. Run OCR engines (possibly in parallel)                           │
│                                                                         │
│     d. Aggregate results                                                │
│        - Merge overlapping regions                                      │
│        - Vote on conflicts                                              │
│        - Track provenance                                               │
│                                                                         │
│     e. Layout understanding (if enabled)                                │
│        - Run LayoutLM/LayoutLMv3                                        │
│        - Map text to structured fields                                  │
│        - Extract form fields, table relationships                       │
│                                                                         │
│     f. LLM post-processing (if enabled)                                 │
│        - Correct OCR errors                                             │
│        - Normalize formats                                              │
│        - Interpret handwriting                                          │
│                                                                         │
│     g. Validation                                                       │
│        - Run validators (dates, amounts, etc.)                          │
│        - Cross-document checks                                          │
│        - Flag conflicts (don't auto-correct)                            │
│                                                                         │
│  5. Generate ParsedDocument                                             │
│     - Full text with regions                                            │
│     - Structured tables                                                 │
│     - Extracted fields                                                  │
│     - Complete audit trail                                              │
│                                                                         │
│  6. Convert to Spring AI Documents                                      │
│     - Include audit metadata                                            │
│     - Preserve bounding boxes                                           │
│     - Add provenance information                                        │
│                                                                         │
│  7. Proceed to Chunking Pipeline                                        │
│     - Table-aware chunking respects extracted tables                    │
│     - Field metadata preserved through embedding                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Model Implementations (SameDiff-Based)

All OCR models are loaded from the registry and executed natively via SameDiff/ND4J.

### 1. Base SameDiff OCR Model

```java
/**
 * Base implementation for SameDiff-based OCR models.
 * Handles model loading, caching, and resource management.
 */
public abstract class BaseSameDiffOcrModel implements OcrModel {

    protected final KompileModelManager modelManager;
    protected SameDiff model;
    protected boolean loaded = false;
    protected final String modelId;

    public BaseSameDiffOcrModel(KompileModelManager modelManager, String modelId) {
        this.modelManager = modelManager;
        this.modelId = modelId;
    }

    @Override
    public String getModelId() { return modelId; }

    @Override
    public boolean isLoaded() { return loaded && model != null; }

    @Override
    public void load() throws Exception {
        if (loaded) return;

        OcrModelBundle bundle = modelManager.getOcrModel(modelId)
            .orElseThrow(() -> new IllegalStateException("OCR model not found: " + modelId));

        this.model = SameDiff.fromFlatFile(bundle.modelPath().toFile());
        this.loaded = true;
        onModelLoaded(bundle);
    }

    @Override
    public void unload() {
        if (model != null) {
            model.close();
            model = null;
        }
        loaded = false;
    }

    /**
     * Hook for subclasses to initialize after model is loaded.
     */
    protected void onModelLoaded(OcrModelBundle bundle) {}

    /**
     * Preprocesses image to model input format.
     */
    protected INDArray preprocessImage(BufferedImage image, int targetH, int targetW) {
        // Resize, normalize, convert to CHW format
        return ImagePreProcessingScaler.preProcess(image, targetH, targetW);
    }
}
```

### 2. Text Detection Model (DBNet/CRAFT)

```java
/**
 * DBNet-based text detection model (PaddleOCR/DocTR style).
 */
@Component
public class DBNetDetector extends BaseSameDiffOcrModel implements TextDetectionModel {

    private float binaryThreshold = 0.3f;
    private float boxThreshold = 0.6f;

    public DBNetDetector(KompileModelManager modelManager,
                         @Value("${kompile.ocr.detection.model:paddleocr-det-db-en}") String modelId) {
        super(modelManager, modelId);
    }

    @Override
    public OcrModelType getModelType() { return OcrModelType.OCR_DETECTION; }

    @Override
    public String getName() { return "DBNet Text Detector"; }

    @Override
    public List<DetectedRegion> detect(INDArray image) {
        ensureLoaded();

        // Preprocess image
        INDArray input = preprocessDetectionInput(image);

        // Run inference
        Map<String, INDArray> outputs = model.output(Map.of("input", input));
        INDArray probabilityMap = outputs.get("probability_map");

        // Post-process: threshold and find contours
        List<DetectedRegion> regions = postProcessDetection(probabilityMap, image);

        return regions;
    }

    @Override
    public List<List<DetectedRegion>> detectBatch(INDArray images) {
        ensureLoaded();
        // Batch processing implementation
        List<List<DetectedRegion>> results = new ArrayList<>();
        // Process batch and split results
        return results;
    }

    private List<DetectedRegion> postProcessDetection(INDArray probMap, INDArray originalImage) {
        // 1. Apply binary threshold
        // 2. Find contours
        // 3. Filter by box threshold
        // 4. Convert to bounding boxes
        // 5. Return regions with confidence
        return new ArrayList<>(); // Implementation
    }

    @Override
    public ModelCapabilities getCapabilities() {
        return new ModelCapabilities(
            OcrModelType.OCR_DETECTION,
            false,              // supportsHandwriting
            true,               // supportsBatch
            List.of("en"),
            8,                  // maxBatchSize
            960, 960            // input dimensions
            0.92                // averageAccuracy
        );
    }
}
```

### 3. Text Recognition Model (CRNN/SVTR)

```java
/**
 * CRNN/SVTR-based text recognition model.
 */
@Component
public class CRNNRecognizer extends BaseSameDiffOcrModel implements TextRecognitionModel {

    private List<String> vocabulary;
    private int blankIndex;

    public CRNNRecognizer(KompileModelManager modelManager,
                          @Value("${kompile.ocr.recognition.model:paddleocr-rec-svtr-en}") String modelId) {
        super(modelManager, modelId);
    }

    @Override
    public OcrModelType getModelType() { return OcrModelType.OCR_RECOGNITION; }

    @Override
    public String getName() { return "CRNN Text Recognizer"; }

    @Override
    protected void onModelLoaded(OcrModelBundle bundle) {
        // Load vocabulary
        this.vocabulary = loadVocabulary(bundle.vocabPath());
        this.blankIndex = vocabulary.indexOf("[BLANK]");
    }

    @Override
    public RecognizedText recognize(INDArray regionImage) {
        ensureLoaded();

        // Preprocess: resize to fixed height, variable width
        INDArray input = preprocessRecognitionInput(regionImage);

        // Run inference
        Map<String, INDArray> outputs = model.output(Map.of("input", input));
        INDArray logits = outputs.get("logits");  // [1, seq_len, vocab_size]

        // CTC decode
        return ctcDecode(logits);
    }

    @Override
    public List<RecognizedText> recognizeBatch(INDArray regionImages) {
        ensureLoaded();
        // Batch recognition with padding
        return new ArrayList<>(); // Implementation
    }

    @Override
    public List<String> getVocabulary() {
        return vocabulary;
    }

    private RecognizedText ctcDecode(INDArray logits) {
        // 1. Argmax over vocabulary dimension
        // 2. Remove consecutive duplicates
        // 3. Remove blank tokens
        // 4. Map to characters
        // 5. Calculate confidence from softmax probabilities
        return new RecognizedText("", 0.0, List.of()); // Implementation
    }

    @Override
    public ModelCapabilities getCapabilities() {
        return new ModelCapabilities(
            OcrModelType.OCR_RECOGNITION,
            false,              // supportsHandwriting (use TrOCR for handwriting)
            true,               // supportsBatch
            List.of("en"),
            32,                 // maxBatchSize
            48, 320,            // input dimensions (height fixed, width variable)
            0.94                // averageAccuracy
        );
    }
}
```

### 4. Table Extraction Model (TableFormer)

```java
/**
 * TableFormer-based table structure recognition.
 */
@Component
public class TableFormerModel extends BaseSameDiffOcrModel implements TableExtractionModel {

    public TableFormerModel(KompileModelManager modelManager,
                            @Value("${kompile.ocr.table.model:tableformer-structure}") String modelId) {
        super(modelManager, modelId);
    }

    @Override
    public OcrModelType getModelType() { return OcrModelType.OCR_TABLE; }

    @Override
    public String getName() { return "TableFormer Table Extractor"; }

    @Override
    public List<DetectedTable> extractTables(INDArray image) {
        ensureLoaded();

        // Preprocess
        INDArray input = preprocessImage(image, 1024, 1024);

        // Detect table regions first (or use provided bounds)
        Map<String, INDArray> outputs = model.output(Map.of("input", input));

        // Parse table structure from model output
        return parseTableStructures(outputs);
    }

    @Override
    public TableStructure extractStructure(INDArray tableImage, BoundingBox tableBbox) {
        ensureLoaded();

        // Crop to table region
        INDArray croppedTable = cropRegion(tableImage, tableBbox);

        // Run structure recognition
        Map<String, INDArray> outputs = model.output(Map.of("input", croppedTable));

        // Parse cell structure
        return parseTableStructure(outputs);
    }

    private List<DetectedTable> parseTableStructures(Map<String, INDArray> outputs) {
        // Parse DETR-style outputs for table detection and structure
        return new ArrayList<>(); // Implementation
    }

    private TableStructure parseTableStructure(Map<String, INDArray> outputs) {
        // Parse row/column structure from model outputs
        return new TableStructure(0, 0, List.of(), List.of(), List.of()); // Implementation
    }

    @Override
    public ModelCapabilities getCapabilities() {
        return new ModelCapabilities(
            OcrModelType.OCR_TABLE,
            false,
            false,              // Tables processed one at a time
            List.of("en"),
            1,
            1024, 1024,
            0.90
        );
    }
}
```

### 5. Layout Understanding Model (LayoutLM)

```java
/**
 * LayoutLM/LayoutLMv3 for document understanding and field extraction.
 */
@Component
public class LayoutLMModel extends BaseSameDiffOcrModel implements LayoutModel {

    private WordPieceTokenizer tokenizer;
    private List<String> labelMap;
    private int maxSeqLength = 512;

    public LayoutLMModel(KompileModelManager modelManager,
                         @Value("${kompile.ocr.layout.model:layoutlmv3-base}") String modelId) {
        super(modelManager, modelId);
    }

    @Override
    public OcrModelType getModelType() { return OcrModelType.LAYOUT_MODEL; }

    @Override
    public String getName() { return "LayoutLM Document Understanding"; }

    @Override
    protected void onModelLoaded(OcrModelBundle bundle) {
        // Load tokenizer vocabulary
        this.tokenizer = new WordPieceTokenizer(bundle.vocabPath());
        // Load label map if fine-tuned for NER/field extraction
        this.labelMap = loadLabelMap(bundle);
    }

    @Override
    public List<ExtractedField> extractFields(
            List<OcrWord> words,
            INDArray pageImage,
            List<FieldType> targetFieldTypes) {

        ensureLoaded();

        // Prepare inputs
        // 1. Tokenize words
        List<Integer> inputIds = new ArrayList<>();
        List<int[]> bboxes = new ArrayList<>();

        for (OcrWord word : words) {
            List<String> tokens = tokenizer.tokenize(word.text());
            for (String token : tokens) {
                inputIds.add(tokenizer.convertTokenToId(token));
                bboxes.add(normalizeBbox(word.bbox()));
            }
        }

        // 2. Create input tensors
        INDArray inputIdsTensor = Nd4j.createFromArray(inputIds.toArray(new Integer[0]));
        INDArray bboxTensor = Nd4j.createFromArray(bboxes.toArray(new int[0][]));
        INDArray attentionMask = Nd4j.ones(inputIds.size());

        // 3. Add image features for LayoutLMv3
        INDArray imageFeatures = preprocessLayoutImage(pageImage);

        // 4. Run inference
        Map<String, INDArray> inputs = Map.of(
            "input_ids", inputIdsTensor,
            "bbox", bboxTensor,
            "attention_mask", attentionMask,
            "pixel_values", imageFeatures
        );

        Map<String, INDArray> outputs = model.output(inputs);
        INDArray logits = outputs.get("logits");

        // 5. Parse field predictions
        return parseFieldPredictions(logits, words, targetFieldTypes);
    }

    @Override
    public DocumentClassification classifyDocument(INDArray pageImage) {
        ensureLoaded();
        // Run classification head
        INDArray input = preprocessLayoutImage(pageImage);
        Map<String, INDArray> outputs = model.output(Map.of("pixel_values", input));
        return parseClassification(outputs.get("logits"));
    }

    private List<ExtractedField> parseFieldPredictions(
            INDArray logits,
            List<OcrWord> words,
            List<FieldType> targetTypes) {
        // Map token predictions back to words
        // Group into fields based on BIO tags
        // Filter by target field types
        return new ArrayList<>(); // Implementation
    }

    @Override
    public ModelCapabilities getCapabilities() {
        return new ModelCapabilities(
            OcrModelType.LAYOUT_MODEL,
            false,
            false,
            List.of("en"),
            1,
            224, 224,           // Image input size
            0.88
        );
    }
}
```

### 6. OCR Model Factory

```java
/**
 * Factory for creating OCR models from the registry.
 */
@Component
public class OcrModelFactory {

    private final KompileModelManager modelManager;

    public OcrModelFactory(KompileModelManager modelManager) {
        this.modelManager = modelManager;
    }

    /**
     * Creates a text detection model.
     */
    public TextDetectionModel createDetectionModel(String modelId) {
        OcrModelBundle bundle = modelManager.getOcrModel(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Detection model not found: " + modelId));

        return switch (bundle.metadata().architecture()) {
            case "DBNet", "DBNet-ResNet50" -> new DBNetDetector(modelManager, modelId);
            case "CRAFT" -> new CRAFTDetector(modelManager, modelId);
            case "EAST" -> new EASTDetector(modelManager, modelId);
            default -> throw new IllegalArgumentException("Unknown detection architecture: " + bundle.metadata().architecture());
        };
    }

    /**
     * Creates a text recognition model.
     */
    public TextRecognitionModel createRecognitionModel(String modelId) {
        OcrModelBundle bundle = modelManager.getOcrModel(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Recognition model not found: " + modelId));

        return switch (bundle.metadata().architecture()) {
            case "CRNN", "CRNN-VGG16" -> new CRNNRecognizer(modelManager, modelId);
            case "SVTR" -> new SVTRRecognizer(modelManager, modelId);
            case "TrOCR" -> new TrOCRRecognizer(modelManager, modelId);
            case "ViTSTR" -> new ViTSTRRecognizer(modelManager, modelId);
            default -> throw new IllegalArgumentException("Unknown recognition architecture: " + bundle.metadata().architecture());
        };
    }

    /**
     * Creates a table extraction model.
     */
    public TableExtractionModel createTableModel(String modelId) {
        OcrModelBundle bundle = modelManager.getOcrModel(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Table model not found: " + modelId));

        return switch (bundle.metadata().architecture()) {
            case "TableFormer", "DoclingTableStructure" -> new TableFormerModel(modelManager, modelId);
            case "MaskRCNN" -> new TableDetectorModel(modelManager, modelId);
            default -> throw new IllegalArgumentException("Unknown table architecture: " + bundle.metadata().architecture());
        };
    }

    /**
     * Creates a layout understanding model.
     */
    public LayoutModel createLayoutModel(String modelId) {
        OcrModelBundle bundle = modelManager.getOcrModel(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Layout model not found: " + modelId));

        return switch (bundle.metadata().architecture()) {
            case "LayoutLM" -> new LayoutLMModel(modelManager, modelId);
            case "LayoutLMv3" -> new LayoutLMv3Model(modelManager, modelId);
            case "DiT" -> new DiTModel(modelManager, modelId);
            case "Donut" -> new DonutModel(modelManager, modelId);
            default -> throw new IllegalArgumentException("Unknown layout architecture: " + bundle.metadata().architecture());
        };
    }

    /**
     * Lists available models by type.
     */
    public List<String> listAvailableModels(OcrModelType type) {
        return modelManager.listOcrModels(type);
    }
}

---

## Validation Framework

### Built-in Validators

```java
/**
 * Date format validator.
 */
@Component
public class DateValidator implements OcrValidator {

    @Override
    public String getValidatorId() { return "date"; }

    @Override
    public ValidationResult validate(ExtractedField field, ValidationContext context) {
        if (field.type() != FieldType.DATE) {
            return ValidationResult.skip();
        }

        // Try to parse as various date formats
        // Check if date is reasonable (not in future for DOB, etc.)
        // Return result with message
    }
}

/**
 * Currency/amount validator.
 */
@Component
public class AmountValidator implements OcrValidator {

    @Override
    public String getValidatorId() { return "amount"; }

    @Override
    public ValidationResult validate(ExtractedField field, ValidationContext context) {
        if (field.type() != FieldType.AMOUNT && field.type() != FieldType.CURRENCY) {
            return ValidationResult.skip();
        }

        // Verify numeric format
        // Check decimal places
        // Validate currency symbol if present
        // Check reasonable ranges
    }
}

/**
 * Cross-document consistency validator.
 */
@Component
public class CrossDocumentValidator implements OcrValidator {

    @Override
    public String getValidatorId() { return "cross_document"; }

    @Override
    public ValidationResult validate(ExtractedField field, ValidationContext context) {
        // Compare field value against same field in other documents
        // Flag if values differ (e.g., applicant name differs across forms)
        // Don't auto-correct - just flag for review
    }
}
```

---

## Performance Considerations

### Parallel Processing

```java
public class OcrPipelineImpl implements OcrPipeline {

    @Override
    public ParsedDocument process(File document, OcrPipelineConfig config, Consumer<OcrProgress> progressCallback) {

        // 1. Convert document to page images
        List<BufferedImage> pages = documentToImages(document);

        // 2. Process pages in parallel (configurable concurrency)
        ExecutorService executor = Executors.newFixedThreadPool(config.performance().maxConcurrentPages());

        List<Future<PageOcrResult>> futures = pages.stream()
            .map(page -> executor.submit(() -> processPage(page, config)))
            .collect(Collectors.toList());

        // 3. Collect results with progress updates
        List<PageOcrResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            PageOcrResult result = futures.get(i).get();
            results.add(result);

            if (progressCallback != null) {
                progressCallback.accept(new OcrProgress(
                    Phase.OCR_PROCESSING,
                    i + 1,
                    pages.size(),
                    currentEngine,
                    ((double) (i + 1) / pages.size()) * 100,
                    "Processing page " + (i + 1)
                ));
            }
        }

        // 4. Continue with layout analysis, post-processing, validation...
    }
}
```

### GPU Acceleration

```java
public class GpuOcrConfig {

    @Value("${kompile.ocr.gpu.enabled:false}")
    private boolean gpuEnabled;

    @Value("${kompile.ocr.gpu.device:0}")
    private int gpuDevice;

    /**
     * Configure GPU for PaddleOCR.
     */
    public Map<String, Object> getPaddleOcrGpuConfig() {
        if (!gpuEnabled) {
            return Map.of("use_gpu", false);
        }
        return Map.of(
            "use_gpu", true,
            "gpu_mem", 500,
            "gpu_id", gpuDevice
        );
    }

    /**
     * Configure GPU for DocTR.
     */
    public Map<String, Object> getDocTrGpuConfig() {
        if (!gpuEnabled) {
            return Map.of("device", "cpu");
        }
        return Map.of("device", "cuda:" + gpuDevice);
    }
}
```

---

## Migration Path

### Phase 1: Core Infrastructure
1. Create `kompile-ocr-core` with interfaces and types
2. Implement `TesseractOcrEngine` (simplest, Java-native)
3. Basic `OcrPipeline` implementation
4. Add OCR config fields to FactSheet entity

### Phase 2: Advanced Engines
1. Implement `PaddleOcrEngine` with Python subprocess
2. Add `CamelotTableExtractor`
3. Implement `DocTrOcrEngine`
4. Engine routing logic in `OcrEngineRouter`

### Phase 3: Layout Understanding
1. Import LayoutLM models to SameDiff format
2. Add to model registry
3. Implement `LayoutLMEngine`
4. Field extraction and mapping

### Phase 4: LLM Post-Processing
1. Integrate with existing LLM providers
2. Implement `LlmPostProcessor`
3. Handwriting interpretation
4. Format normalization

### Phase 5: Validation & Audit
1. Implement validators
2. Cross-document validation
3. Complete audit trail
4. UI for reviewing conflicts

### Phase 6: UI Integration
1. OCR config in fact sheet editor
2. Model debug component extension
3. Audit trail viewer
4. Field extraction reviewer

---

## Dependencies

### kompile-ocr-core
```xml
<dependencies>
    <!-- Core interfaces only, minimal deps -->
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-context</artifactId>
    </dependency>
</dependencies>
```

### kompile-ocr-tesseract
```xml
<dependencies>
    <dependency>
        <groupId>net.sourceforge.tess4j</groupId>
        <artifactId>tess4j</artifactId>
        <version>5.8.0</version>
    </dependency>
</dependencies>
```

### kompile-ocr-paddleocr
```xml
<dependencies>
    <!-- Uses Python subprocess, no Java deps -->
    <dependency>
        <groupId>ai.kompile</groupId>
        <artifactId>kompile-pipelines-steps-python</artifactId>
    </dependency>
</dependencies>
```

### kompile-ocr-layout
```xml
<dependencies>
    <dependency>
        <groupId>ai.kompile</groupId>
        <artifactId>kompile-model-manager</artifactId>
    </dependency>
    <dependency>
        <groupId>org.nd4j</groupId>
        <artifactId>nd4j-native</artifactId>
    </dependency>
</dependencies>
```

---

## OCR Testing & Debugging

### Overview

The OCR module includes comprehensive testing and debugging tools, following the patterns established by the model debugger and document debugging features. These tools allow developers to:

1. Test individual OCR models (detection, recognition, table, layout)
2. Debug OCR pipeline execution on documents
3. Visualize bounding boxes and confidence scores
4. Compare results between different models
5. Validate extraction accuracy against ground truth

### OCR Model Debugger

Similar to the embedding model debugger, this provides real-time testing of OCR models.

#### Backend: OcrModelDebugController

```java
@RestController
@RequestMapping("/api/debug/ocr")
public class OcrModelDebugController {

    private final OcrModelFactory modelFactory;
    private final KompileModelManager modelManager;

    // ==================== Model Status ====================

    /**
     * Get status of all OCR models.
     */
    @GetMapping("/models/status")
    public OcrModelsStatus getModelsStatus() {
        return new OcrModelsStatus(
            getModelsByType(OcrModelType.OCR_DETECTION),
            getModelsByType(OcrModelType.OCR_RECOGNITION),
            getModelsByType(OcrModelType.OCR_TABLE),
            getModelsByType(OcrModelType.LAYOUT_MODEL)
        );
    }

    public record OcrModelsStatus(
        List<OcrModelInfo> detectionModels,
        List<OcrModelInfo> recognitionModels,
        List<OcrModelInfo> tableModels,
        List<OcrModelInfo> layoutModels
    ) {}

    public record OcrModelInfo(
        String modelId,
        String name,
        OcrModelType type,
        String architecture,
        String status,           // "available", "loaded", "loading", "error"
        boolean isLoaded,
        ModelCapabilities capabilities,
        Map<String, Object> metadata
    ) {}

    // ==================== Detection Testing ====================

    /**
     * Test text detection on an image.
     */
    @PostMapping("/detection/test")
    public DetectionTestResult testDetection(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "modelId", defaultValue = "paddleocr-det-db-en") String modelId,
            @RequestParam(value = "returnImage", defaultValue = "true") boolean returnImage) {

        TextDetectionModel model = modelFactory.createDetectionModel(modelId);
        model.load();

        long startTime = System.currentTimeMillis();
        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());
        INDArray imageArray = preprocessImage(bufferedImage);

        List<DetectedRegion> regions = model.detect(imageArray);
        long processingTime = System.currentTimeMillis() - startTime;

        // Generate annotated image with bounding boxes
        String annotatedImageBase64 = null;
        if (returnImage) {
            BufferedImage annotated = drawDetectionBoxes(bufferedImage, regions);
            annotatedImageBase64 = encodeImageBase64(annotated);
        }

        return new DetectionTestResult(
            modelId,
            regions.size(),
            regions.stream().map(this::toRegionDto).toList(),
            processingTime,
            annotatedImageBase64,
            bufferedImage.getWidth(),
            bufferedImage.getHeight()
        );
    }

    public record DetectionTestResult(
        String modelId,
        int regionCount,
        List<DetectedRegionDto> regions,
        long processingTimeMs,
        String annotatedImageBase64,
        int imageWidth,
        int imageHeight
    ) {}

    public record DetectedRegionDto(
        int x, int y, int width, int height,
        double confidence,
        List<int[]> polygon  // For rotated text
    ) {}

    // ==================== Recognition Testing ====================

    /**
     * Test text recognition on a cropped region.
     */
    @PostMapping("/recognition/test")
    public RecognitionTestResult testRecognition(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "modelId", defaultValue = "paddleocr-rec-svtr-en") String modelId) {

        TextRecognitionModel model = modelFactory.createRecognitionModel(modelId);
        model.load();

        long startTime = System.currentTimeMillis();
        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());
        INDArray imageArray = preprocessImage(bufferedImage);

        RecognizedText result = model.recognize(imageArray);
        long processingTime = System.currentTimeMillis() - startTime;

        return new RecognitionTestResult(
            modelId,
            result.text(),
            result.confidence(),
            result.charConfidences().stream()
                .map(c -> new CharConfidenceDto(c.character(), c.confidence()))
                .toList(),
            processingTime
        );
    }

    public record RecognitionTestResult(
        String modelId,
        String text,
        double confidence,
        List<CharConfidenceDto> charConfidences,
        long processingTimeMs
    ) {}

    public record CharConfidenceDto(char character, double confidence) {}

    // ==================== Full OCR Pipeline Testing ====================

    /**
     * Test full OCR pipeline (detection + recognition) on an image.
     */
    @PostMapping("/pipeline/test")
    public PipelineTestResult testPipeline(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "detectionModel", defaultValue = "paddleocr-det-db-en") String detectionModelId,
            @RequestParam(value = "recognitionModel", defaultValue = "paddleocr-rec-svtr-en") String recognitionModelId,
            @RequestParam(value = "returnImage", defaultValue = "true") boolean returnImage) {

        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());

        // Step 1: Detection
        long detStartTime = System.currentTimeMillis();
        TextDetectionModel detModel = modelFactory.createDetectionModel(detectionModelId);
        detModel.load();
        List<DetectedRegion> regions = detModel.detect(preprocessImage(bufferedImage));
        long detectionTime = System.currentTimeMillis() - detStartTime;

        // Step 2: Recognition for each region
        long recStartTime = System.currentTimeMillis();
        TextRecognitionModel recModel = modelFactory.createRecognitionModel(recognitionModelId);
        recModel.load();

        List<OcrWordResult> words = new ArrayList<>();
        for (DetectedRegion region : regions) {
            BufferedImage cropped = cropRegion(bufferedImage, region.bbox());
            RecognizedText text = recModel.recognize(preprocessImage(cropped));
            words.add(new OcrWordResult(
                region.bbox().x(), region.bbox().y(),
                region.bbox().width(), region.bbox().height(),
                text.text(),
                text.confidence(),
                region.confidence()
            ));
        }
        long recognitionTime = System.currentTimeMillis() - recStartTime;

        // Generate annotated image
        String annotatedImageBase64 = null;
        if (returnImage) {
            BufferedImage annotated = drawOcrResults(bufferedImage, words);
            annotatedImageBase64 = encodeImageBase64(annotated);
        }

        // Combine into full text
        String fullText = words.stream()
            .sorted(Comparator.comparingInt(OcrWordResult::y).thenComparingInt(OcrWordResult::x))
            .map(OcrWordResult::text)
            .collect(Collectors.joining(" "));

        return new PipelineTestResult(
            detectionModelId,
            recognitionModelId,
            words,
            fullText,
            regions.size(),
            detectionTime,
            recognitionTime,
            detectionTime + recognitionTime,
            annotatedImageBase64
        );
    }

    public record PipelineTestResult(
        String detectionModelId,
        String recognitionModelId,
        List<OcrWordResult> words,
        String fullText,
        int wordCount,
        long detectionTimeMs,
        long recognitionTimeMs,
        long totalTimeMs,
        String annotatedImageBase64
    ) {}

    public record OcrWordResult(
        int x, int y, int width, int height,
        String text,
        double recognitionConfidence,
        double detectionConfidence
    ) {}

    // ==================== Table Extraction Testing ====================

    /**
     * Test table extraction on an image.
     */
    @PostMapping("/table/test")
    public TableTestResult testTableExtraction(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "modelId", defaultValue = "tableformer-structure") String modelId,
            @RequestParam(value = "returnImage", defaultValue = "true") boolean returnImage) {

        TableExtractionModel model = modelFactory.createTableModel(modelId);
        model.load();

        long startTime = System.currentTimeMillis();
        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());
        List<DetectedTable> tables = model.extractTables(preprocessImage(bufferedImage));
        long processingTime = System.currentTimeMillis() - startTime;

        // Generate annotated image with table structure
        String annotatedImageBase64 = null;
        if (returnImage) {
            BufferedImage annotated = drawTableStructure(bufferedImage, tables);
            annotatedImageBase64 = encodeImageBase64(annotated);
        }

        return new TableTestResult(
            modelId,
            tables.size(),
            tables.stream().map(this::toTableDto).toList(),
            processingTime,
            annotatedImageBase64
        );
    }

    public record TableTestResult(
        String modelId,
        int tableCount,
        List<ExtractedTableDto> tables,
        long processingTimeMs,
        String annotatedImageBase64
    ) {}

    public record ExtractedTableDto(
        int x, int y, int width, int height,
        int rowCount,
        int columnCount,
        double confidence,
        List<TableCellDto> cells,
        String markdownPreview
    ) {}

    public record TableCellDto(
        int row, int column, int rowSpan, int colSpan,
        int x, int y, int width, int height,
        boolean isHeader
    ) {}

    // ==================== Layout Model Testing ====================

    /**
     * Test layout understanding on OCR results.
     */
    @PostMapping("/layout/test")
    public LayoutTestResult testLayout(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "layoutModelId", defaultValue = "layoutlmv3-base") String layoutModelId,
            @RequestParam(value = "detectionModelId", defaultValue = "paddleocr-det-db-en") String detectionModelId,
            @RequestParam(value = "recognitionModelId", defaultValue = "paddleocr-rec-svtr-en") String recognitionModelId,
            @RequestParam(value = "fieldTypes", required = false) List<String> fieldTypes,
            @RequestParam(value = "returnImage", defaultValue = "true") boolean returnImage) {

        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());

        // Step 1: Run OCR pipeline
        List<OcrWord> ocrWords = runOcrPipeline(bufferedImage, detectionModelId, recognitionModelId);

        // Step 2: Run layout model
        long startTime = System.currentTimeMillis();
        LayoutModel layoutModel = modelFactory.createLayoutModel(layoutModelId);
        layoutModel.load();

        List<FieldType> targetTypes = fieldTypes != null
            ? fieldTypes.stream().map(FieldType::valueOf).toList()
            : List.of(FieldType.values());

        List<ExtractedField> fields = layoutModel.extractFields(
            ocrWords,
            preprocessImage(bufferedImage),
            targetTypes
        );
        long processingTime = System.currentTimeMillis() - startTime;

        // Document classification
        DocumentClassification classification = layoutModel.classifyDocument(
            preprocessImage(bufferedImage)
        );

        // Generate annotated image
        String annotatedImageBase64 = null;
        if (returnImage) {
            BufferedImage annotated = drawLayoutResults(bufferedImage, ocrWords, fields);
            annotatedImageBase64 = encodeImageBase64(annotated);
        }

        return new LayoutTestResult(
            layoutModelId,
            fields.stream().map(this::toFieldDto).toList(),
            new DocumentClassificationDto(
                classification.documentType(),
                classification.confidence()
            ),
            processingTime,
            annotatedImageBase64
        );
    }

    public record LayoutTestResult(
        String modelId,
        List<ExtractedFieldDto> fields,
        DocumentClassificationDto classification,
        long processingTimeMs,
        String annotatedImageBase64
    ) {}

    public record ExtractedFieldDto(
        String fieldId,
        String name,
        String type,
        String rawValue,
        String normalizedValue,
        int x, int y, int width, int height,
        double confidence
    ) {}

    public record DocumentClassificationDto(
        String documentType,
        double confidence
    ) {}

    // ==================== Model Comparison ====================

    /**
     * Compare multiple models on the same image.
     */
    @PostMapping("/compare")
    public ModelComparisonResult compareModels(
            @RequestParam("image") MultipartFile image,
            @RequestParam("modelIds") List<String> modelIds,
            @RequestParam("modelType") OcrModelType modelType) {

        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());
        List<ModelComparisonEntry> results = new ArrayList<>();

        for (String modelId : modelIds) {
            long startTime = System.currentTimeMillis();

            try {
                Object result = switch (modelType) {
                    case OCR_DETECTION -> {
                        TextDetectionModel model = modelFactory.createDetectionModel(modelId);
                        model.load();
                        yield model.detect(preprocessImage(bufferedImage));
                    }
                    case OCR_RECOGNITION -> {
                        TextRecognitionModel model = modelFactory.createRecognitionModel(modelId);
                        model.load();
                        yield model.recognize(preprocessImage(bufferedImage));
                    }
                    case OCR_TABLE -> {
                        TableExtractionModel model = modelFactory.createTableModel(modelId);
                        model.load();
                        yield model.extractTables(preprocessImage(bufferedImage));
                    }
                    case LAYOUT_MODEL -> {
                        LayoutModel model = modelFactory.createLayoutModel(modelId);
                        model.load();
                        yield model.classifyDocument(preprocessImage(bufferedImage));
                    }
                    default -> throw new IllegalArgumentException("Unsupported model type");
                };

                results.add(new ModelComparisonEntry(
                    modelId,
                    "success",
                    System.currentTimeMillis() - startTime,
                    result,
                    null
                ));
            } catch (Exception e) {
                results.add(new ModelComparisonEntry(
                    modelId,
                    "error",
                    System.currentTimeMillis() - startTime,
                    null,
                    e.getMessage()
                ));
            }
        }

        return new ModelComparisonResult(modelType, results);
    }

    public record ModelComparisonResult(
        OcrModelType modelType,
        List<ModelComparisonEntry> results
    ) {}

    public record ModelComparisonEntry(
        String modelId,
        String status,
        long processingTimeMs,
        Object result,
        String error
    ) {}
}
```

### Document OCR Debugger

For debugging OCR on full documents (PDFs, multi-page images).

#### Backend: DocumentOcrDebugController

```java
@RestController
@RequestMapping("/api/debug/ocr/document")
public class DocumentOcrDebugController {

    private final OcrPipeline ocrPipeline;
    private final OcrModelFactory modelFactory;

    /**
     * Process a document with OCR and return detailed debug info.
     */
    @PostMapping("/process")
    public DocumentOcrDebugResult processDocument(
            @RequestParam("file") MultipartFile file,
            @RequestBody OcrDebugConfig config) {

        // Convert document to images
        List<BufferedImage> pages = documentToImages(file);

        List<PageOcrDebugResult> pageResults = new ArrayList<>();

        for (int i = 0; i < pages.size(); i++) {
            BufferedImage page = pages.get(i);
            PageOcrDebugResult pageResult = processPage(page, i + 1, config);
            pageResults.add(pageResult);
        }

        // Aggregate results
        return new DocumentOcrDebugResult(
            file.getOriginalFilename(),
            pages.size(),
            pageResults,
            aggregateMetrics(pageResults),
            config
        );
    }

    public record OcrDebugConfig(
        String detectionModelId,
        String recognitionModelId,
        String tableModelId,
        String layoutModelId,
        boolean enableTableExtraction,
        boolean enableLayoutAnalysis,
        boolean returnAnnotatedImages,
        boolean returnWordLevelDetails,
        double confidenceThreshold
    ) {}

    public record DocumentOcrDebugResult(
        String fileName,
        int pageCount,
        List<PageOcrDebugResult> pages,
        OcrMetrics aggregateMetrics,
        OcrDebugConfig config
    ) {}

    public record PageOcrDebugResult(
        int pageNumber,
        int width,
        int height,

        // Detection results
        int regionsDetected,
        long detectionTimeMs,
        List<DetectedRegionDto> detectedRegions,

        // Recognition results
        int wordsRecognized,
        long recognitionTimeMs,
        List<OcrWordResult> words,
        String fullText,

        // Table extraction
        int tablesExtracted,
        long tableExtractionTimeMs,
        List<ExtractedTableDto> tables,

        // Layout analysis
        int fieldsExtracted,
        long layoutAnalysisTimeMs,
        List<ExtractedFieldDto> fields,
        DocumentClassificationDto classification,

        // Confidence distribution
        ConfidenceDistribution confidenceDistribution,

        // Annotated images (base64)
        String detectionAnnotatedImage,
        String recognitionAnnotatedImage,
        String tableAnnotatedImage,
        String layoutAnnotatedImage,

        // Timing breakdown
        TimingBreakdown timing
    ) {}

    public record ConfidenceDistribution(
        double min,
        double max,
        double mean,
        double median,
        int lowConfidenceCount,     // Below threshold
        int highConfidenceCount,    // Above 0.9
        List<ConfidenceBucket> histogram
    ) {}

    public record ConfidenceBucket(
        double rangeStart,
        double rangeEnd,
        int count
    ) {}

    public record TimingBreakdown(
        long preprocessingMs,
        long detectionMs,
        long recognitionMs,
        long tableExtractionMs,
        long layoutAnalysisMs,
        long postprocessingMs,
        long totalMs
    ) {}

    public record OcrMetrics(
        int totalPages,
        int totalWords,
        int totalTables,
        int totalFields,
        double averageConfidence,
        double averageWordsPerPage,
        long totalProcessingTimeMs,
        long averagePageProcessingTimeMs
    ) {}

    /**
     * Get OCR debug results for a specific page.
     */
    @PostMapping("/process/page/{pageNumber}")
    public PageOcrDebugResult processPage(
            @RequestParam("file") MultipartFile file,
            @PathVariable int pageNumber,
            @RequestBody OcrDebugConfig config) {

        List<BufferedImage> pages = documentToImages(file);
        if (pageNumber < 1 || pageNumber > pages.size()) {
            throw new IllegalArgumentException("Page number out of range");
        }

        return processPage(pages.get(pageNumber - 1), pageNumber, config);
    }

    /**
     * Compare OCR results with ground truth.
     */
    @PostMapping("/validate")
    public OcrValidationResult validateOcr(
            @RequestParam("file") MultipartFile file,
            @RequestParam("groundTruth") MultipartFile groundTruthFile,
            @RequestBody OcrDebugConfig config) {

        // Process document
        DocumentOcrDebugResult ocrResult = processDocument(file, config);

        // Load ground truth (JSON format)
        GroundTruth groundTruth = loadGroundTruth(groundTruthFile);

        // Compare
        return compareWithGroundTruth(ocrResult, groundTruth);
    }

    public record OcrValidationResult(
        double characterAccuracy,       // CER (Character Error Rate)
        double wordAccuracy,            // WER (Word Error Rate)
        double fieldAccuracy,           // Field-level accuracy
        double tableAccuracy,           // Table structure accuracy
        List<ValidationError> errors,
        ConfusionMatrix characterConfusion,
        List<FieldValidation> fieldValidations
    ) {}

    public record ValidationError(
        int pageNumber,
        String errorType,       // "missing", "extra", "mismatch"
        String expected,
        String actual,
        BoundingBox location
    ) {}

    public record FieldValidation(
        String fieldName,
        String expectedValue,
        String actualValue,
        boolean matches,
        double similarity      // Levenshtein similarity
    ) {}
}
```

### Frontend: OCR Debug Components

#### OCR Model Debugger Component

```typescript
// ocr-model-debug.component.ts

@Component({
  selector: 'app-ocr-model-debug',
  template: `
    <div class="ocr-debug-container">
      <!-- Model Status Panel -->
      <mat-card class="model-status-card">
        <mat-card-header>
          <mat-card-title>OCR Models Status</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <mat-tab-group>
            <mat-tab label="Detection ({{modelsStatus?.detectionModels?.length || 0}})">
              <app-model-list
                [models]="modelsStatus?.detectionModels"
                [selectedModel]="selectedDetectionModel"
                (selectModel)="onSelectDetectionModel($event)">
              </app-model-list>
            </mat-tab>
            <mat-tab label="Recognition ({{modelsStatus?.recognitionModels?.length || 0}})">
              <app-model-list
                [models]="modelsStatus?.recognitionModels"
                [selectedModel]="selectedRecognitionModel"
                (selectModel)="onSelectRecognitionModel($event)">
              </app-model-list>
            </mat-tab>
            <mat-tab label="Table ({{modelsStatus?.tableModels?.length || 0}})">
              <app-model-list
                [models]="modelsStatus?.tableModels"
                [selectedModel]="selectedTableModel"
                (selectModel)="onSelectTableModel($event)">
              </app-model-list>
            </mat-tab>
            <mat-tab label="Layout ({{modelsStatus?.layoutModels?.length || 0}})">
              <app-model-list
                [models]="modelsStatus?.layoutModels"
                [selectedModel]="selectedLayoutModel"
                (selectModel)="onSelectLayoutModel($event)">
              </app-model-list>
            </mat-tab>
          </mat-tab-group>
        </mat-card-content>
      </mat-card>

      <!-- Image Upload & Test Panel -->
      <mat-card class="test-panel-card">
        <mat-card-header>
          <mat-card-title>Test OCR</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <!-- Image Upload -->
          <div class="upload-area"
               (drop)="onDrop($event)"
               (dragover)="onDragOver($event)">
            <input type="file" #fileInput
                   accept="image/*,.pdf"
                   (change)="onFileSelect($event)"
                   style="display: none">
            <button mat-stroked-button (click)="fileInput.click()">
              <mat-icon>upload</mat-icon>
              Upload Image or PDF
            </button>
            <span *ngIf="selectedFile">{{ selectedFile.name }}</span>
          </div>

          <!-- Test Type Selection -->
          <mat-button-toggle-group [(ngModel)]="testType" class="test-type-toggle">
            <mat-button-toggle value="detection">Detection Only</mat-button-toggle>
            <mat-button-toggle value="recognition">Recognition Only</mat-button-toggle>
            <mat-button-toggle value="pipeline">Full Pipeline</mat-button-toggle>
            <mat-button-toggle value="table">Table Extraction</mat-button-toggle>
            <mat-button-toggle value="layout">Layout Analysis</mat-button-toggle>
          </mat-button-toggle-group>

          <!-- Run Test Button -->
          <button mat-raised-button color="primary"
                  [disabled]="!selectedFile || isProcessing"
                  (click)="runTest()">
            <mat-spinner *ngIf="isProcessing" diameter="20"></mat-spinner>
            <span *ngIf="!isProcessing">Run Test</span>
          </button>
        </mat-card-content>
      </mat-card>

      <!-- Results Panel -->
      <mat-card class="results-card" *ngIf="testResult">
        <mat-card-header>
          <mat-card-title>Results</mat-card-title>
          <span class="processing-time">{{ testResult.processingTimeMs }}ms</span>
        </mat-card-header>
        <mat-card-content>
          <div class="results-container">
            <!-- Annotated Image -->
            <div class="image-panel">
              <img [src]="'data:image/png;base64,' + testResult.annotatedImageBase64"
                   class="annotated-image"
                   (click)="openImageViewer()">

              <!-- Overlay Controls -->
              <div class="overlay-controls">
                <mat-checkbox [(ngModel)]="showBoundingBoxes">Bounding Boxes</mat-checkbox>
                <mat-checkbox [(ngModel)]="showConfidence">Confidence</mat-checkbox>
                <mat-checkbox [(ngModel)]="showText">Text Labels</mat-checkbox>
              </div>
            </div>

            <!-- Details Panel -->
            <div class="details-panel">
              <!-- Detection Results -->
              <mat-expansion-panel *ngIf="testResult.regions">
                <mat-expansion-panel-header>
                  Detected Regions ({{ testResult.regions.length }})
                </mat-expansion-panel-header>
                <table mat-table [dataSource]="testResult.regions">
                  <ng-container matColumnDef="index">
                    <th mat-header-cell *matHeaderCellDef>#</th>
                    <td mat-cell *matCellDef="let r; let i = index">{{ i + 1 }}</td>
                  </ng-container>
                  <ng-container matColumnDef="bbox">
                    <th mat-header-cell *matHeaderCellDef>Bounding Box</th>
                    <td mat-cell *matCellDef="let r">
                      ({{ r.x }}, {{ r.y }}) {{ r.width }}x{{ r.height }}
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="confidence">
                    <th mat-header-cell *matHeaderCellDef>Confidence</th>
                    <td mat-cell *matCellDef="let r">
                      <app-confidence-bar [value]="r.confidence"></app-confidence-bar>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="['index', 'bbox', 'confidence']"></tr>
                  <tr mat-row *matRowDef="let row; columns: ['index', 'bbox', 'confidence']"
                      (mouseenter)="highlightRegion(row)"
                      (mouseleave)="clearHighlight()"></tr>
                </table>
              </mat-expansion-panel>

              <!-- Recognition Results -->
              <mat-expansion-panel *ngIf="testResult.words">
                <mat-expansion-panel-header>
                  Recognized Words ({{ testResult.words.length }})
                </mat-expansion-panel-header>
                <div class="words-list">
                  <div *ngFor="let word of testResult.words"
                       class="word-item"
                       [class.low-confidence]="word.recognitionConfidence < 0.7"
                       (mouseenter)="highlightWord(word)"
                       (mouseleave)="clearHighlight()">
                    <span class="word-text">{{ word.text }}</span>
                    <app-confidence-bar [value]="word.recognitionConfidence"></app-confidence-bar>
                  </div>
                </div>
              </mat-expansion-panel>

              <!-- Full Text -->
              <mat-expansion-panel *ngIf="testResult.fullText">
                <mat-expansion-panel-header>
                  Extracted Text
                </mat-expansion-panel-header>
                <pre class="extracted-text">{{ testResult.fullText }}</pre>
                <button mat-button (click)="copyText()">
                  <mat-icon>content_copy</mat-icon> Copy
                </button>
              </mat-expansion-panel>

              <!-- Table Results -->
              <mat-expansion-panel *ngIf="testResult.tables">
                <mat-expansion-panel-header>
                  Extracted Tables ({{ testResult.tables.length }})
                </mat-expansion-panel-header>
                <div *ngFor="let table of testResult.tables; let i = index">
                  <h4>Table {{ i + 1 }} ({{ table.rowCount }}x{{ table.columnCount }})</h4>
                  <div class="table-preview" [innerHTML]="table.markdownPreview | markdown"></div>
                </div>
              </mat-expansion-panel>

              <!-- Layout/Fields Results -->
              <mat-expansion-panel *ngIf="testResult.fields">
                <mat-expansion-panel-header>
                  Extracted Fields ({{ testResult.fields.length }})
                </mat-expansion-panel-header>
                <table mat-table [dataSource]="testResult.fields">
                  <ng-container matColumnDef="name">
                    <th mat-header-cell *matHeaderCellDef>Field</th>
                    <td mat-cell *matCellDef="let f">{{ f.name }}</td>
                  </ng-container>
                  <ng-container matColumnDef="type">
                    <th mat-header-cell *matHeaderCellDef>Type</th>
                    <td mat-cell *matCellDef="let f">
                      <mat-chip>{{ f.type }}</mat-chip>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="value">
                    <th mat-header-cell *matHeaderCellDef>Value</th>
                    <td mat-cell *matCellDef="let f">{{ f.normalizedValue || f.rawValue }}</td>
                  </ng-container>
                  <ng-container matColumnDef="confidence">
                    <th mat-header-cell *matHeaderCellDef>Confidence</th>
                    <td mat-cell *matCellDef="let f">
                      <app-confidence-bar [value]="f.confidence"></app-confidence-bar>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="['name', 'type', 'value', 'confidence']"></tr>
                  <tr mat-row *matRowDef="let row; columns: ['name', 'type', 'value', 'confidence']"
                      (mouseenter)="highlightField(row)"
                      (mouseleave)="clearHighlight()"></tr>
                </table>
              </mat-expansion-panel>
            </div>
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `
})
export class OcrModelDebugComponent implements OnInit {
  modelsStatus: OcrModelsStatus;
  selectedDetectionModel: string;
  selectedRecognitionModel: string;
  selectedTableModel: string;
  selectedLayoutModel: string;

  selectedFile: File;
  testType: 'detection' | 'recognition' | 'pipeline' | 'table' | 'layout' = 'pipeline';
  isProcessing = false;
  testResult: any;

  showBoundingBoxes = true;
  showConfidence = true;
  showText = true;

  constructor(private ocrDebugService: OcrDebugService) {}

  ngOnInit() {
    this.loadModelsStatus();
  }

  async loadModelsStatus() {
    this.modelsStatus = await this.ocrDebugService.getModelsStatus();

    // Select defaults
    if (this.modelsStatus.detectionModels.length > 0) {
      this.selectedDetectionModel = this.modelsStatus.detectionModels[0].modelId;
    }
    if (this.modelsStatus.recognitionModels.length > 0) {
      this.selectedRecognitionModel = this.modelsStatus.recognitionModels[0].modelId;
    }
  }

  async runTest() {
    if (!this.selectedFile) return;

    this.isProcessing = true;
    try {
      switch (this.testType) {
        case 'detection':
          this.testResult = await this.ocrDebugService.testDetection(
            this.selectedFile, this.selectedDetectionModel);
          break;
        case 'recognition':
          this.testResult = await this.ocrDebugService.testRecognition(
            this.selectedFile, this.selectedRecognitionModel);
          break;
        case 'pipeline':
          this.testResult = await this.ocrDebugService.testPipeline(
            this.selectedFile, this.selectedDetectionModel, this.selectedRecognitionModel);
          break;
        case 'table':
          this.testResult = await this.ocrDebugService.testTable(
            this.selectedFile, this.selectedTableModel);
          break;
        case 'layout':
          this.testResult = await this.ocrDebugService.testLayout(
            this.selectedFile, this.selectedLayoutModel,
            this.selectedDetectionModel, this.selectedRecognitionModel);
          break;
      }
    } finally {
      this.isProcessing = false;
    }
  }
}
```

#### Confidence Bar Component

```typescript
// confidence-bar.component.ts

@Component({
  selector: 'app-confidence-bar',
  template: `
    <div class="confidence-bar-container">
      <div class="confidence-bar"
           [style.width.%]="value * 100"
           [class.low]="value < 0.5"
           [class.medium]="value >= 0.5 && value < 0.8"
           [class.high]="value >= 0.8">
      </div>
      <span class="confidence-value">{{ (value * 100).toFixed(1) }}%</span>
    </div>
  `,
  styles: [`
    .confidence-bar-container {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .confidence-bar {
      height: 8px;
      border-radius: 4px;
      min-width: 20px;
      max-width: 100px;
    }
    .confidence-bar.low { background: #f44336; }
    .confidence-bar.medium { background: #ff9800; }
    .confidence-bar.high { background: #4caf50; }
    .confidence-value {
      font-size: 12px;
      font-family: monospace;
    }
  `]
})
export class ConfidenceBarComponent {
  @Input() value: number = 0;
}
```

#### Document OCR Debug Component

```typescript
// document-ocr-debug.component.ts

@Component({
  selector: 'app-document-ocr-debug',
  template: `
    <div class="document-ocr-debug">
      <!-- Configuration Panel -->
      <mat-card class="config-card">
        <mat-card-header>
          <mat-card-title>OCR Configuration</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="config-grid">
            <mat-form-field>
              <mat-label>Detection Model</mat-label>
              <mat-select [(ngModel)]="config.detectionModelId">
                <mat-option *ngFor="let m of detectionModels" [value]="m.modelId">
                  {{ m.name }}
                </mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field>
              <mat-label>Recognition Model</mat-label>
              <mat-select [(ngModel)]="config.recognitionModelId">
                <mat-option *ngFor="let m of recognitionModels" [value]="m.modelId">
                  {{ m.name }}
                </mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field>
              <mat-label>Table Model</mat-label>
              <mat-select [(ngModel)]="config.tableModelId">
                <mat-option *ngFor="let m of tableModels" [value]="m.modelId">
                  {{ m.name }}
                </mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field>
              <mat-label>Layout Model</mat-label>
              <mat-select [(ngModel)]="config.layoutModelId">
                <mat-option *ngFor="let m of layoutModels" [value]="m.modelId">
                  {{ m.name }}
                </mat-option>
              </mat-select>
            </mat-form-field>
          </div>

          <div class="options-row">
            <mat-checkbox [(ngModel)]="config.enableTableExtraction">
              Table Extraction
            </mat-checkbox>
            <mat-checkbox [(ngModel)]="config.enableLayoutAnalysis">
              Layout Analysis
            </mat-checkbox>
            <mat-checkbox [(ngModel)]="config.returnAnnotatedImages">
              Annotated Images
            </mat-checkbox>
          </div>

          <mat-slider min="0" max="1" step="0.1"
                      [(ngModel)]="config.confidenceThreshold">
            <span>Confidence Threshold: {{ config.confidenceThreshold }}</span>
          </mat-slider>
        </mat-card-content>
      </mat-card>

      <!-- Document Upload -->
      <mat-card class="upload-card">
        <mat-card-content>
          <input type="file" #fileInput
                 accept=".pdf,image/*"
                 (change)="onFileSelect($event)"
                 style="display: none">
          <button mat-raised-button color="primary" (click)="fileInput.click()">
            <mat-icon>upload_file</mat-icon>
            Upload Document
          </button>
          <span *ngIf="selectedFile" class="file-name">{{ selectedFile.name }}</span>

          <button mat-raised-button color="accent"
                  [disabled]="!selectedFile || isProcessing"
                  (click)="processDocument()">
            <mat-spinner *ngIf="isProcessing" diameter="20"></mat-spinner>
            Process Document
          </button>
        </mat-card-content>
      </mat-card>

      <!-- Results -->
      <div class="results-container" *ngIf="result">
        <!-- Summary Metrics -->
        <mat-card class="metrics-card">
          <mat-card-header>
            <mat-card-title>Processing Summary</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <div class="metrics-grid">
              <div class="metric">
                <span class="metric-value">{{ result.pageCount }}</span>
                <span class="metric-label">Pages</span>
              </div>
              <div class="metric">
                <span class="metric-value">{{ result.aggregateMetrics.totalWords }}</span>
                <span class="metric-label">Words</span>
              </div>
              <div class="metric">
                <span class="metric-value">{{ result.aggregateMetrics.totalTables }}</span>
                <span class="metric-label">Tables</span>
              </div>
              <div class="metric">
                <span class="metric-value">{{ result.aggregateMetrics.totalFields }}</span>
                <span class="metric-label">Fields</span>
              </div>
              <div class="metric">
                <span class="metric-value">
                  {{ (result.aggregateMetrics.averageConfidence * 100).toFixed(1) }}%
                </span>
                <span class="metric-label">Avg Confidence</span>
              </div>
              <div class="metric">
                <span class="metric-value">{{ result.aggregateMetrics.totalProcessingTimeMs }}ms</span>
                <span class="metric-label">Total Time</span>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <!-- Page Navigator -->
        <mat-card class="page-navigator-card">
          <mat-card-content>
            <div class="page-thumbnails">
              <div *ngFor="let page of result.pages; let i = index"
                   class="page-thumbnail"
                   [class.selected]="selectedPageIndex === i"
                   (click)="selectPage(i)">
                <img *ngIf="page.detectionAnnotatedImage"
                     [src]="'data:image/png;base64,' + page.detectionAnnotatedImage">
                <span class="page-number">{{ i + 1 }}</span>
                <span class="page-words">{{ page.wordsRecognized }} words</span>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <!-- Selected Page Details -->
        <mat-card class="page-details-card" *ngIf="selectedPage">
          <mat-card-header>
            <mat-card-title>Page {{ selectedPage.pageNumber }}</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <mat-tab-group>
              <mat-tab label="Image">
                <div class="page-image-container">
                  <img [src]="'data:image/png;base64,' + getAnnotatedImage()"
                       class="page-image">
                  <div class="annotation-toggle">
                    <mat-button-toggle-group [(ngModel)]="annotationMode">
                      <mat-button-toggle value="detection">Detection</mat-button-toggle>
                      <mat-button-toggle value="recognition">Recognition</mat-button-toggle>
                      <mat-button-toggle value="table">Tables</mat-button-toggle>
                      <mat-button-toggle value="layout">Layout</mat-button-toggle>
                    </mat-button-toggle-group>
                  </div>
                </div>
              </mat-tab>

              <mat-tab label="Text ({{ selectedPage.wordsRecognized }} words)">
                <pre class="page-text">{{ selectedPage.fullText }}</pre>
              </mat-tab>

              <mat-tab label="Words">
                <app-word-list [words]="selectedPage.words"></app-word-list>
              </mat-tab>

              <mat-tab label="Tables ({{ selectedPage.tablesExtracted }})" *ngIf="config.enableTableExtraction">
                <div *ngFor="let table of selectedPage.tables; let i = index">
                  <h4>Table {{ i + 1 }}</h4>
                  <div [innerHTML]="table.markdownPreview | markdown"></div>
                </div>
              </mat-tab>

              <mat-tab label="Fields ({{ selectedPage.fieldsExtracted }})" *ngIf="config.enableLayoutAnalysis">
                <app-field-list [fields]="selectedPage.fields"></app-field-list>
              </mat-tab>

              <mat-tab label="Confidence">
                <app-confidence-histogram
                  [distribution]="selectedPage.confidenceDistribution">
                </app-confidence-histogram>
              </mat-tab>

              <mat-tab label="Timing">
                <app-timing-breakdown [timing]="selectedPage.timing"></app-timing-breakdown>
              </mat-tab>
            </mat-tab-group>
          </mat-card-content>
        </mat-card>
      </div>
    </div>
  `
})
export class DocumentOcrDebugComponent {
  config: OcrDebugConfig = {
    detectionModelId: 'paddleocr-det-db-en',
    recognitionModelId: 'paddleocr-rec-svtr-en',
    tableModelId: 'tableformer-structure',
    layoutModelId: 'layoutlmv3-base',
    enableTableExtraction: true,
    enableLayoutAnalysis: true,
    returnAnnotatedImages: true,
    returnWordLevelDetails: true,
    confidenceThreshold: 0.5
  };

  selectedFile: File;
  isProcessing = false;
  result: DocumentOcrDebugResult;
  selectedPageIndex = 0;
  annotationMode: 'detection' | 'recognition' | 'table' | 'layout' = 'detection';

  get selectedPage() {
    return this.result?.pages[this.selectedPageIndex];
  }

  getAnnotatedImage(): string {
    if (!this.selectedPage) return '';
    switch (this.annotationMode) {
      case 'detection': return this.selectedPage.detectionAnnotatedImage;
      case 'recognition': return this.selectedPage.recognitionAnnotatedImage;
      case 'table': return this.selectedPage.tableAnnotatedImage;
      case 'layout': return this.selectedPage.layoutAnnotatedImage;
    }
  }

  async processDocument() {
    this.isProcessing = true;
    try {
      this.result = await this.ocrDebugService.processDocument(this.selectedFile, this.config);
      this.selectedPageIndex = 0;
    } finally {
      this.isProcessing = false;
    }
  }
}
```

### OCR Debug Service

```typescript
// ocr-debug.service.ts

@Injectable({ providedIn: 'root' })
export class OcrDebugService {
  private baseUrl = `${environment.apiUrl}/debug/ocr`;

  constructor(private http: HttpClient) {}

  getModelsStatus(): Promise<OcrModelsStatus> {
    return this.http.get<OcrModelsStatus>(`${this.baseUrl}/models/status`).toPromise();
  }

  testDetection(image: File, modelId: string): Promise<DetectionTestResult> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<DetectionTestResult>(
      `${this.baseUrl}/detection/test?modelId=${modelId}`,
      formData
    ).toPromise();
  }

  testRecognition(image: File, modelId: string): Promise<RecognitionTestResult> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<RecognitionTestResult>(
      `${this.baseUrl}/recognition/test?modelId=${modelId}`,
      formData
    ).toPromise();
  }

  testPipeline(image: File, detectionModel: string, recognitionModel: string): Promise<PipelineTestResult> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<PipelineTestResult>(
      `${this.baseUrl}/pipeline/test?detectionModel=${detectionModel}&recognitionModel=${recognitionModel}`,
      formData
    ).toPromise();
  }

  testTable(image: File, modelId: string): Promise<TableTestResult> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<TableTestResult>(
      `${this.baseUrl}/table/test?modelId=${modelId}`,
      formData
    ).toPromise();
  }

  testLayout(image: File, layoutModel: string, detectionModel: string, recognitionModel: string): Promise<LayoutTestResult> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<LayoutTestResult>(
      `${this.baseUrl}/layout/test?layoutModelId=${layoutModel}&detectionModelId=${detectionModel}&recognitionModelId=${recognitionModel}`,
      formData
    ).toPromise();
  }

  processDocument(file: File, config: OcrDebugConfig): Promise<DocumentOcrDebugResult> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<DocumentOcrDebugResult>(
      `${this.baseUrl}/document/process`,
      formData,
      { params: config as any }
    ).toPromise();
  }

  compareModels(image: File, modelIds: string[], modelType: OcrModelType): Promise<ModelComparisonResult> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<ModelComparisonResult>(
      `${this.baseUrl}/compare?modelIds=${modelIds.join(',')}&modelType=${modelType}`,
      formData
    ).toPromise();
  }

  validateOcr(file: File, groundTruth: File, config: OcrDebugConfig): Promise<OcrValidationResult> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('groundTruth', groundTruth);
    return this.http.post<OcrValidationResult>(
      `${this.baseUrl}/document/validate`,
      formData,
      { params: config as any }
    ).toPromise();
  }
}
```

### Visual Debugging Features

#### Bounding Box Visualization

```java
/**
 * Utility for drawing OCR debug visualizations.
 */
public class OcrVisualizationUtils {

    private static final Color DETECTION_COLOR = new Color(0, 128, 255, 180);  // Blue
    private static final Color RECOGNITION_COLOR = new Color(0, 200, 100, 180);  // Green
    private static final Color TABLE_COLOR = new Color(255, 165, 0, 180);  // Orange
    private static final Color FIELD_COLOR = new Color(255, 50, 50, 180);  // Red
    private static final Color LOW_CONFIDENCE_COLOR = new Color(255, 0, 0, 200);

    /**
     * Draws detection bounding boxes on an image.
     */
    public static BufferedImage drawDetectionBoxes(
            BufferedImage image,
            List<DetectedRegion> regions) {

        BufferedImage result = copyImage(image);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int i = 0; i < regions.size(); i++) {
            DetectedRegion region = regions.get(i);
            BoundingBox bbox = region.bbox();

            // Color based on confidence
            Color color = region.confidence() < 0.7 ? LOW_CONFIDENCE_COLOR : DETECTION_COLOR;
            g.setColor(color);
            g.setStroke(new BasicStroke(2));
            g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());

            // Draw index and confidence
            String label = String.format("#%d (%.0f%%)", i + 1, region.confidence() * 100);
            drawLabel(g, label, bbox.x(), bbox.y() - 5, color);
        }

        g.dispose();
        return result;
    }

    /**
     * Draws OCR results (text + boxes) on an image.
     */
    public static BufferedImage drawOcrResults(
            BufferedImage image,
            List<OcrWordResult> words) {

        BufferedImage result = copyImage(image);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (OcrWordResult word : words) {
            // Color based on confidence
            Color color = word.recognitionConfidence() < 0.7 ? LOW_CONFIDENCE_COLOR : RECOGNITION_COLOR;
            g.setColor(color);
            g.setStroke(new BasicStroke(2));
            g.drawRect(word.x(), word.y(), word.width(), word.height());

            // Draw text above box
            drawLabel(g, word.text(), word.x(), word.y() - 5, color);
        }

        g.dispose();
        return result;
    }

    /**
     * Draws table structure on an image.
     */
    public static BufferedImage drawTableStructure(
            BufferedImage image,
            List<DetectedTable> tables) {

        BufferedImage result = copyImage(image);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int t = 0; t < tables.size(); t++) {
            DetectedTable table = tables.get(t);

            // Draw table border
            g.setColor(TABLE_COLOR);
            g.setStroke(new BasicStroke(3));
            BoundingBox bbox = table.bbox();
            g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());

            // Draw table label
            String label = String.format("Table %d (%dx%d)", t + 1,
                table.structure().rowCount(), table.structure().columnCount());
            drawLabel(g, label, bbox.x(), bbox.y() - 5, TABLE_COLOR);

            // Draw cell boundaries
            g.setStroke(new BasicStroke(1));
            for (TableCell cell : table.structure().cells()) {
                g.setColor(cell.isHeader() ? new Color(200, 100, 100, 150) : new Color(100, 100, 200, 150));
                g.drawRect(cell.bbox().x(), cell.bbox().y(), cell.bbox().width(), cell.bbox().height());
            }
        }

        g.dispose();
        return result;
    }

    /**
     * Draws layout fields on an image.
     */
    public static BufferedImage drawLayoutResults(
            BufferedImage image,
            List<OcrWord> words,
            List<ExtractedField> fields) {

        BufferedImage result = copyImage(image);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw words (faded)
        g.setColor(new Color(150, 150, 150, 100));
        for (OcrWord word : words) {
            g.drawRect(word.bbox().x(), word.bbox().y(), word.bbox().width(), word.bbox().height());
        }

        // Draw fields (highlighted)
        Map<FieldType, Color> fieldColors = Map.of(
            FieldType.NAME, new Color(255, 100, 100, 180),
            FieldType.DATE, new Color(100, 255, 100, 180),
            FieldType.AMOUNT, new Color(100, 100, 255, 180),
            FieldType.ADDRESS, new Color(255, 255, 100, 180)
        );

        for (ExtractedField field : fields) {
            Color color = fieldColors.getOrDefault(field.type(), FIELD_COLOR);
            g.setColor(color);
            g.setStroke(new BasicStroke(2));
            BoundingBox bbox = field.bbox();
            g.drawRect(bbox.x(), bbox.y(), bbox.width(), bbox.height());

            // Draw field label
            String label = String.format("%s: %s", field.name(), field.normalizedValue());
            drawLabel(g, label, bbox.x(), bbox.y() - 5, color);
        }

        g.dispose();
        return result;
    }

    private static void drawLabel(Graphics2D g, String text, int x, int y, Color color) {
        Font font = new Font("SansSerif", Font.BOLD, 12);
        g.setFont(font);

        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getHeight();

        // Draw background
        g.setColor(new Color(255, 255, 255, 200));
        g.fillRect(x, y - textHeight + 4, textWidth + 4, textHeight);

        // Draw text
        g.setColor(color.darker());
        g.drawString(text, x + 2, y);
    }
}
```

### Integration with Model Debugger UI

The OCR debug functionality integrates into the existing model debugger:

```typescript
// model-debug.component.ts (extended)

@Component({
  selector: 'app-model-debug',
  template: `
    <mat-tab-group>
      <!-- Existing embedding model debug -->
      <mat-tab label="Embeddings">
        <app-embedding-model-debug></app-embedding-model-debug>
      </mat-tab>

      <!-- New OCR model debug -->
      <mat-tab label="OCR Models">
        <app-ocr-model-debug></app-ocr-model-debug>
      </mat-tab>

      <!-- Document OCR debug -->
      <mat-tab label="Document OCR">
        <app-document-ocr-debug></app-document-ocr-debug>
      </mat-tab>

      <!-- Cross-encoder/reranker debug (existing) -->
      <mat-tab label="Rerankers">
        <app-reranker-debug></app-reranker-debug>
      </mat-tab>
    </mat-tab-group>
  `
})
export class ModelDebugComponent {}
```

---

## Summary

This OCR module design provides:

1. **Model-First Architecture**: All OCR capabilities (detection, recognition, tables, layout) are SameDiff models imported via the staging service
2. **Importable OCR Models**: PaddleOCR, DocTR, TrOCR, LayoutLM models converted to SameDiff format
3. **Table Extraction**: TableFormer and Docling models for structured table extraction
4. **Layout Understanding**: LayoutLM/LayoutLMv3/DiT/Donut models for semantic field extraction
5. **LLM Post-Processing**: Pluggable LLM providers for error correction and normalization
6. **Full Auditability**: Complete provenance tracking with bounding boxes and confidence scores
7. **Model Management**: Fully integrated with existing staging/registry system
8. **Seamless Integration**: Works as a parse step before chunking in the fact workflow
9. **External Services Separation**: Python/cloud services in optional separate module

### Key Design Decisions

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| OCR Engines | SameDiff models, not Python subprocesses | Native execution, no Python dependency |
| PaddleOCR | Import as detection + recognition models | Model import follows existing patterns |
| LayoutLM | SameDiff model from HuggingFace import | Unified model management |
| Table Extraction | TableFormer model, not Camelot library | Model-based, no external dependencies |
| External Services | Separate optional module | Clean separation, fallback only |
| Configuration | Per-FactSheet with registry models | Consistent with embedding model config |

### OCR Model Types in Registry

| Type | Examples | Purpose |
|------|----------|---------|
| `ocr_detection` | DBNet, CRAFT, EAST | Find text regions in images |
| `ocr_recognition` | CRNN, SVTR, TrOCR | Convert text regions to strings |
| `ocr_table` | TableFormer, Docling | Extract table structure |
| `layout_model` | LayoutLM, LayoutLMv3, DiT | Semantic field extraction |

### Architecture Principles

The design follows Kompile's existing patterns:
- **Interface-based pluggability**: `TextDetectionModel`, `TextRecognitionModel`, `TableExtractionModel`, `LayoutModel`
- **Model registry integration**: All models in `~/.kompile/models/ocr/`
- **Spring Boot conditional configuration**: `@ConditionalOnProperty` for model selection
- **FactSheet-level configuration**: Per-sheet OCR settings with model IDs
- **Staging workflow**: Import → Stage → Verify → Promote
- **Comprehensive audit trails**: Full traceability for every extraction
