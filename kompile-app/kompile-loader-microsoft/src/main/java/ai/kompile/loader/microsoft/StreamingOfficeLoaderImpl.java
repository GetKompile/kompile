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

package ai.kompile.loader.microsoft;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.LargeDocumentInfo;
import ai.kompile.core.loaders.StreamingDocumentLoader;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Streaming Office loader that processes documents section-by-section.
 *
 * <p>This implementation extends {@link MicrosoftOfficeLoaderImpl} to add streaming capability
 * for large Office documents. Instead of loading the entire document into memory, it provides
 * iterators that yield sections (paragraphs, sheets, slides) one at a time.</p>
 *
 * <h2>Supported Formats for Streaming</h2>
 * <ul>
 *   <li><b>DOCX</b>: Streams paragraphs in batches of 50</li>
 *   <li><b>XLSX</b>: Streams sheets one at a time</li>
 *   <li><b>PPTX</b>: Streams slides one at a time</li>
 * </ul>
 *
 * <p>Note: Legacy formats (.doc, .xls, .ppt) are not supported for streaming due to their
 * binary nature which doesn't allow efficient section-by-section access.</p>
 *
 * <h2>Memory Efficiency</h2>
 * <ul>
 *   <li>For DOCX: Only 50 paragraphs processed at a time</li>
 *   <li>For XLSX: Only one sheet loaded at a time</li>
 *   <li>For PPTX: Only one slide loaded at a time</li>
 * </ul>
 */
@Component
public class StreamingOfficeLoaderImpl extends MicrosoftOfficeLoaderImpl implements StreamingDocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(StreamingOfficeLoaderImpl.class);

    /** Number of paragraphs to batch together for DOCX files */
    private static final int PARAGRAPH_BATCH_SIZE = 50;

    /** Supported extensions for streaming (modern XML-based formats only) */
    private static final Set<String> STREAMING_EXTENSIONS = Set.of("docx", "xlsx", "pptx");

    @Override
    public boolean supportsStreaming(DocumentSourceDescriptor source) {
        if (!supports(source)) {
            return false;
        }

        String path = source.getPathOrUrl().toLowerCase();
        return STREAMING_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    @Override
    public LargeDocumentInfo getDocumentInfo(DocumentSourceDescriptor source) throws Exception {
        File file = new File(source.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + source.getPathOrUrl());
        }

        String filename = file.getName().toLowerCase();
        int sectionCount = -1;
        String docType = "Unknown";

        // Get section count without loading full content
        if (filename.endsWith(".docx")) {
            sectionCount = countDocxSections(file);
            docType = "DOCX";
        } else if (filename.endsWith(".xlsx")) {
            sectionCount = countXlsxSheets(file);
            docType = "XLSX";
        } else if (filename.endsWith(".pptx")) {
            sectionCount = countPptxSlides(file);
            docType = "PPTX";
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", file.getAbsolutePath());
        metadata.put("fileName", file.getName());
        metadata.put("fileSize", file.length());
        metadata.put("lastModified", file.lastModified());
        metadata.put("documentType", docType);
        metadata.put("sectionCount", sectionCount);

        return LargeDocumentInfo.of(
            source.getSourceId() != null ? source.getSourceId() : file.getName(),
            file.getName(),
            docType,
            file.length(),
            sectionCount,
            metadata
        );
    }

    @Override
    public Iterator<Document> streamPages(DocumentSourceDescriptor source,
                                          Consumer<PageProgress> progressCallback) throws Exception {
        File file = new File(source.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + source.getPathOrUrl());
        }

        String filename = file.getName().toLowerCase();

        if (filename.endsWith(".docx")) {
            return streamDocxParagraphs(file, progressCallback);
        } else if (filename.endsWith(".xlsx")) {
            return streamXlsxSheets(file, progressCallback);
        } else if (filename.endsWith(".pptx")) {
            return streamPptxSlides(file, progressCallback);
        }

        throw new IllegalArgumentException("Unsupported format for streaming: " + filename);
    }

    /**
     * Count paragraph batches in a DOCX file.
     */
    private int countDocxSections(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            int paragraphs = document.getParagraphs().size();
            return (paragraphs + PARAGRAPH_BATCH_SIZE - 1) / PARAGRAPH_BATCH_SIZE;
        }
    }

    /**
     * Count sheets in an XLSX file.
     */
    private int countXlsxSheets(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            return workbook.getNumberOfSheets();
        }
    }

    /**
     * Count slides in a PPTX file.
     */
    private int countPptxSlides(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow slideShow = new XMLSlideShow(fis)) {
            return slideShow.getSlides().size();
        }
    }

    /**
     * Stream DOCX paragraphs in batches.
     */
    private Iterator<Document> streamDocxParagraphs(File file, Consumer<PageProgress> callback) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        XWPFDocument document = new XWPFDocument(fis);
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        int totalBatches = (paragraphs.size() + PARAGRAPH_BATCH_SIZE - 1) / PARAGRAPH_BATCH_SIZE;

        logger.info("Starting streaming DOCX load: {} paragraphs in {} batches from {}",
                    paragraphs.size(), totalBatches, file.getName());

        return new Iterator<>() {
            private int currentBatch = 0;
            private boolean closed = false;

            @Override
            public boolean hasNext() {
                boolean hasMore = currentBatch < totalBatches && !closed;
                if (!hasMore && !closed) {
                    closeResources();
                }
                return hasMore;
            }

            @Override
            public Document next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more paragraph batches");
                }

                int start = currentBatch * PARAGRAPH_BATCH_SIZE;
                int end = Math.min(start + PARAGRAPH_BATCH_SIZE, paragraphs.size());

                StringBuilder content = new StringBuilder();
                for (int i = start; i < end; i++) {
                    String text = paragraphs.get(i).getText();
                    if (text != null && !text.trim().isEmpty()) {
                        content.append(text).append("\n");
                    }
                }

                currentBatch++;

                if (callback != null) {
                    callback.accept(new PageProgress(
                        currentBatch,
                        totalBatches,
                        "paragraphs " + start + "-" + end
                    ));
                }

                Document doc = new Document(content.toString());
                addMetadata(doc, file, "DOCX", currentBatch, totalBatches);
                doc.getMetadata().put("paragraphStart", start);
                doc.getMetadata().put("paragraphEnd", end);

                if (currentBatch >= totalBatches) {
                    closeResources();
                }

                return doc;
            }

            private void closeResources() {
                if (!closed) {
                    closed = true;
                    try {
                        document.close();
                        fis.close();
                        logger.debug("Closed DOCX document: {}", file.getName());
                    } catch (IOException e) {
                        logger.warn("Error closing DOCX: {}", e.getMessage());
                    }
                }
            }
        };
    }

    /**
     * Stream XLSX sheets one at a time.
     */
    private Iterator<Document> streamXlsxSheets(File file, Consumer<PageProgress> callback) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        XSSFWorkbook workbook = new XSSFWorkbook(fis);
        int totalSheets = workbook.getNumberOfSheets();

        logger.info("Starting streaming XLSX load: {} sheets from {}", totalSheets, file.getName());

        return new Iterator<>() {
            private int currentSheet = 0;
            private boolean closed = false;

            @Override
            public boolean hasNext() {
                boolean hasMore = currentSheet < totalSheets && !closed;
                if (!hasMore && !closed) {
                    closeResources();
                }
                return hasMore;
            }

            @Override
            public Document next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more sheets");
                }

                Sheet sheet = workbook.getSheetAt(currentSheet);
                String sheetName = sheet.getSheetName();

                StringBuilder content = new StringBuilder();
                content.append("Sheet: ").append(sheetName).append("\n\n");

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String cellValue = getCellValueAsString(cell);
                        if (!cellValue.trim().isEmpty()) {
                            content.append(cellValue).append("\t");
                        }
                    }
                    content.append("\n");
                }

                currentSheet++;

                if (callback != null) {
                    callback.accept(new PageProgress(
                        currentSheet,
                        totalSheets,
                        "sheet: " + sheetName
                    ));
                }

                Document doc = new Document(content.toString());
                addMetadata(doc, file, "XLSX", currentSheet, totalSheets);
                doc.getMetadata().put("sheetName", sheetName);
                doc.getMetadata().put("sheetIndex", currentSheet - 1);

                if (currentSheet >= totalSheets) {
                    closeResources();
                }

                return doc;
            }

            private void closeResources() {
                if (!closed) {
                    closed = true;
                    try {
                        workbook.close();
                        fis.close();
                        logger.debug("Closed XLSX workbook: {}", file.getName());
                    } catch (IOException e) {
                        logger.warn("Error closing XLSX: {}", e.getMessage());
                    }
                }
            }
        };
    }

    /**
     * Stream PPTX slides one at a time.
     */
    private Iterator<Document> streamPptxSlides(File file, Consumer<PageProgress> callback) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        XMLSlideShow slideShow = new XMLSlideShow(fis);
        List<XSLFSlide> slides = slideShow.getSlides();
        int totalSlides = slides.size();

        logger.info("Starting streaming PPTX load: {} slides from {}", totalSlides, file.getName());

        return new Iterator<>() {
            private int currentSlide = 0;
            private boolean closed = false;

            @Override
            public boolean hasNext() {
                boolean hasMore = currentSlide < totalSlides && !closed;
                if (!hasMore && !closed) {
                    closeResources();
                }
                return hasMore;
            }

            @Override
            public Document next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more slides");
                }

                XSLFSlide slide = slides.get(currentSlide);
                String slideTitle = slide.getTitle();

                // Extract text from all shapes in the slide
                StringBuilder content = new StringBuilder();
                content.append("Slide ").append(currentSlide + 1);
                if (slideTitle != null && !slideTitle.isEmpty()) {
                    content.append(": ").append(slideTitle);
                }
                content.append("\n\n");

                // Use slide extractor pattern
                slide.getShapes().forEach(shape -> {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        org.apache.poi.xslf.usermodel.XSLFTextShape textShape =
                            (org.apache.poi.xslf.usermodel.XSLFTextShape) shape;
                        String text = textShape.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            content.append(text).append("\n");
                        }
                    }
                });

                currentSlide++;

                if (callback != null) {
                    callback.accept(new PageProgress(
                        currentSlide,
                        totalSlides,
                        "slide " + currentSlide + (slideTitle != null ? ": " + slideTitle : "")
                    ));
                }

                Document doc = new Document(content.toString());
                addMetadata(doc, file, "PPTX", currentSlide, totalSlides);
                doc.getMetadata().put("slideNumber", currentSlide);
                if (slideTitle != null) {
                    doc.getMetadata().put("slideTitle", slideTitle);
                }

                if (currentSlide >= totalSlides) {
                    closeResources();
                }

                return doc;
            }

            private void closeResources() {
                if (!closed) {
                    closed = true;
                    try {
                        slideShow.close();
                        fis.close();
                        logger.debug("Closed PPTX slideshow: {}", file.getName());
                    } catch (IOException e) {
                        logger.warn("Error closing PPTX: {}", e.getMessage());
                    }
                }
            }
        };
    }

    private String getCellValueAsString(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    private void addMetadata(Document doc, File file, String docType, int section, int totalSections) {
        doc.getMetadata().put("source", file.getAbsolutePath());
        doc.getMetadata().put("fileName", file.getName());
        doc.getMetadata().put("fileSize", file.length());
        doc.getMetadata().put("lastModified", file.lastModified());
        doc.getMetadata().put("documentType", docType);
        doc.getMetadata().put("loader", getName());
        doc.getMetadata().put("extractionType", "streaming");
        doc.getMetadata().put("sectionNumber", section);
        doc.getMetadata().put("totalSections", totalSections);
    }
}
