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

package ai.kompile.loader.pdf;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.LargeDocumentInfo;
import ai.kompile.core.loaders.StreamingDocumentLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Streaming PDF loader that processes pages one at a time.
 *
 * <p>This implementation extends {@link PdfExtendedLoaderImpl} to add streaming capability
 * for large PDF documents. Instead of loading the entire document into memory, it provides
 * an iterator that yields one page at a time.</p>
 *
 * <h2>Memory Efficiency</h2>
 * <ul>
 *   <li>Only one page's text is held in memory at a time</li>
 *   <li>PDDocument is kept open during iteration (minimal memory for PDF structure)</li>
 *   <li>Automatically closes document when iteration completes</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * StreamingPdfLoaderImpl loader = new StreamingPdfLoaderImpl();
 * DocumentSourceDescriptor source = ...;
 *
 * if (loader.supportsStreaming(source)) {
 *     Iterator<Document> pages = loader.streamPages(source, progress -> {
 *         System.out.println("Page " + progress.currentPage() + "/" + progress.totalPages());
 *     });
 *
 *     while (pages.hasNext()) {
 *         Document page = pages.next();
 *         // Process page...
 *     }
 * }
 * }</pre>
 */
@Component
public class StreamingPdfLoaderImpl extends PdfExtendedLoaderImpl implements StreamingDocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(StreamingPdfLoaderImpl.class);

    @Override
    public boolean supportsStreaming(DocumentSourceDescriptor source) {
        return supports(source);
    }

    @Override
    public LargeDocumentInfo getDocumentInfo(DocumentSourceDescriptor source) throws Exception {
        File file = new File(source.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + source.getPathOrUrl());
        }

        // Quick scan - just get page count without loading content
        try (PDDocument doc = Loader.loadPDF(file)) {
            Map<String, Object> metadata = extractMetadataMap(doc, file);

            return LargeDocumentInfo.of(
                source.getSourceId() != null ? source.getSourceId() : file.getName(),
                file.getName(),
                "PDF",
                file.length(),
                doc.getNumberOfPages(),
                metadata
            );
        }
    }

    @Override
    public Iterator<Document> streamPages(DocumentSourceDescriptor source,
                                          Consumer<PageProgress> progressCallback) throws Exception {
        File file = new File(source.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + source.getPathOrUrl());
        }

        // Load the document - it will be closed when iteration completes
        PDDocument document = Loader.loadPDF(file);
        int totalPages = document.getNumberOfPages();

        logger.info("Starting streaming PDF load: {} pages from {}", totalPages, file.getName());

        return new PdfPageIterator(document, file, totalPages, progressCallback);
    }

    /**
     * Extract metadata from a PDDocument without loading text content.
     */
    private Map<String, Object> extractMetadataMap(PDDocument doc, File file) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", file.getAbsolutePath());
        metadata.put("fileName", file.getName());
        metadata.put("fileSize", file.length());
        metadata.put("lastModified", file.lastModified());
        metadata.put("documentType", "PDF Document");
        metadata.put("pageCount", doc.getNumberOfPages());

        try {
            PDDocumentInformation info = doc.getDocumentInformation();
            if (info != null) {
                if (info.getTitle() != null) metadata.put("title", info.getTitle());
                if (info.getAuthor() != null) metadata.put("author", info.getAuthor());
                if (info.getSubject() != null) metadata.put("subject", info.getSubject());
                if (info.getKeywords() != null) metadata.put("keywords", info.getKeywords());
                if (info.getCreator() != null) metadata.put("creator", info.getCreator());
                if (info.getProducer() != null) metadata.put("producer", info.getProducer());
                if (info.getCreationDate() != null) {
                    metadata.put("creationDate", info.getCreationDate().getTime());
                }
                if (info.getModificationDate() != null) {
                    metadata.put("modificationDate", info.getModificationDate().getTime());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract PDF metadata: {}", e.getMessage());
        }

        return metadata;
    }

    /**
     * Iterator that yields one PDF page at a time.
     */
    private class PdfPageIterator implements Iterator<Document> {
        private final PDDocument document;
        private final File file;
        private final int totalPages;
        private final Consumer<PageProgress> progressCallback;
        private final PDFTextStripper stripper;
        private final Map<String, Object> baseMetadata;

        private int currentPage = 0;
        private boolean closed = false;

        PdfPageIterator(PDDocument document, File file, int totalPages,
                        Consumer<PageProgress> progressCallback) throws IOException {
            this.document = document;
            this.file = file;
            this.totalPages = totalPages;
            this.progressCallback = progressCallback;
            this.stripper = new PDFTextStripper();
            this.baseMetadata = extractMetadataMap(document, file);
        }

        @Override
        public boolean hasNext() {
            boolean hasMore = currentPage < totalPages && !closed;
            if (!hasMore && !closed) {
                closeDocument();
            }
            return hasMore;
        }

        @Override
        public Document next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more pages in PDF");
            }

            try {
                currentPage++;
                stripper.setStartPage(currentPage);
                stripper.setEndPage(currentPage);

                String pageText = stripper.getText(document);

                // Report progress
                if (progressCallback != null) {
                    progressCallback.accept(new PageProgress(
                        currentPage,
                        totalPages,
                        "page " + currentPage
                    ));
                }

                // Create document with page metadata
                Document pageDoc = new Document(pageText != null ? pageText : "");

                // Copy base metadata
                pageDoc.getMetadata().putAll(baseMetadata);

                // Add page-specific metadata
                pageDoc.getMetadata().put("pageNumber", currentPage);
                pageDoc.getMetadata().put("totalPages", totalPages);
                pageDoc.getMetadata().put("extractionType", "streaming");
                pageDoc.getMetadata().put("loader", getName());

                // Close document when done
                if (currentPage >= totalPages) {
                    closeDocument();
                }

                logger.debug("Streamed page {}/{} from {}", currentPage, totalPages, file.getName());
                return pageDoc;

            } catch (IOException e) {
                closeDocument();
                logger.error("Error reading page {} from {}: {}", currentPage, file.getName(), e.getMessage());
                throw new RuntimeException("Error reading page " + currentPage + ": " + e.getMessage(), e);
            }
        }

        private void closeDocument() {
            if (!closed) {
                closed = true;
                try {
                    document.close();
                    logger.debug("Closed PDF document: {}", file.getName());
                } catch (IOException e) {
                    logger.warn("Error closing PDF document: {}", e.getMessage());
                }
            }
        }
    }
}
