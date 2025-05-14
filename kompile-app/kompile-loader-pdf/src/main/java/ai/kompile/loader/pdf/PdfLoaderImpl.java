/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.pdf;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig; // Keep for other configs like .withPages()
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component("pdfDocumentLoader")
public class PdfLoaderImpl implements DocumentLoader {
    private static final Logger logger = LogManager.getLogger(PdfLoaderImpl.class);

    @Override
    public boolean supports(DocumentSourceDescriptor sd) {
        return sd != null && sd.getPathOrUrl() != null &&
                sd.getType() == DocumentSourceDescriptor.SourceType.FILE &&
                sd.getPathOrUrl().toLowerCase().endsWith(".pdf");
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sd) throws IOException {
        logger.debug("PdfLoader attempting to load: {}", sd.getPathOrUrl());
        File file = new File(sd.getPathOrUrl()); // Orchestrator should provide absolute path
        if (!file.exists() || !file.canRead()) {
            logger.error("PDF File resource does not exist or is not readable by PdfLoader: {}", file.getAbsolutePath());
            return Collections.emptyList();
        }
        Resource resource = new FileSystemResource(file);

        try {
            // Use default text extraction.
            // If you need to specify page ranges, you can still use the config.
            PdfDocumentReaderConfig.Builder configBuilder = PdfDocumentReaderConfig.builder();
            // Example: If you wanted to process only the first 5 pages:
            // configBuilder.withPages(1, 5);
            // For all pages, usually no .withPages() call is needed or use Integer.MAX_VALUE for end.

            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource, configBuilder.build());
            List<Document> docs = pdfReader.get();

            // Enrich documents with original source information
            for(Document doc : docs) {
                Map<String, Object> metadata = doc.getMetadata();
                metadata.putIfAbsent("original_filename", sd.getOriginalFileName() != null ? sd.getOriginalFileName() : resource.getFilename());
                metadata.putIfAbsent("source_path_or_url", sd.getPathOrUrl());
                metadata.putIfAbsent("source_type", sd.getType().name());
                metadata.putIfAbsent("loader_type", "PdfLoaderImpl");
                // PagePdfDocumentReader adds 'page_number' (1-based by default) and 'total_pages' metadata.
            }
            logger.info("PdfLoader successfully loaded {} page(s)/document(s) from: {}", docs.size(), sd.getPathOrUrl());
            return docs;
        } catch (Exception e) {
            logger.error("PDFBox processing failed for PDF resource {}: {}", sd.getPathOrUrl(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}