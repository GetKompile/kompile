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

package ai.kompile.app.web.controllers;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/documents")
public class DocumentManagementController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentManagementController.class);

    private final Path uploadsPath;
    private final AppDocumentSourceProperties sourceProperties;
    private final RestTemplate restTemplate;
    private final List<DocumentLoader> documentLoaders;
    private final DocumentLoadingService documentLoadingService;
    private final List<TextChunker> textChunkers;
    private  IndexerService indexerService;

    @Autowired
    public DocumentManagementController(
            @Autowired AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired RestTemplate restTemplate,
            @Autowired List<DocumentLoader> documentLoaders,
            @Autowired DocumentLoadingService documentLoadingService,
            @Autowired List<TextChunker> textChunkers,
            @Autowired  List<IndexerService> indexerService
    ) {
        this.sourceProperties = appDocumentSourceProperties;
        this.restTemplate = restTemplate;
        this.documentLoaders = documentLoaders;
        this.documentLoadingService = documentLoadingService;
        this.textChunkers = textChunkers;
        if(indexerService.size() > 1) {
            for (IndexerService indexerService1 : indexerService) {
                if (indexerService1 instanceof NoOpIndexerService) {
                    continue;
                } else {
                    this.indexerService = indexerService1;
                    break;
                }
            }
        } else {
            this.indexerService = indexerService.get(0);
        }

        if (appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            logger.error("CRITICAL: 'app.document.uploads-path' is not configured in application.properties. Document upload/add URL functionality will be impaired.");
            this.uploadsPath = Paths.get("./error_uploads_path_not_configured");
        } else {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        }
    }

    @PostConstruct
    private void initializeUploadsDirectory() {
        try {
            if (this.uploadsPath != null && !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
                if (!Files.exists(this.uploadsPath)) {
                    Files.createDirectories(this.uploadsPath);
                    logger.info("Created uploads directory for DocumentManagementController: {}", this.uploadsPath);
                } else {
                    logger.info("Uploads directory already exists: {}", this.uploadsPath);
                }
            } else {
                logger.error("Uploads path is not properly configured (remains as fallback). Upload functionality will likely fail.");
            }
        } catch (IOException e) {
            logger.error("FATAL: Could not create or access uploads directory at {}: {}", this.uploadsPath, e.getMessage(), e);
        }
    }

    public record AddUrlRequest(String url, String fileName, String loader, String chunkerName) {}
    public record LoaderInfo(String name, String className) {}
    public record ChunkerInfo(String name, String className) {}

    public record ControllerBatchLoadRequestItem(
            DocumentSourceDescriptor source,
            String loaderName,
            String chunkerName,
            Map<String, Object> chunkerOptions,
            String vectorStoreName,
            Map<String, Object> metadata
    ) {}

    public record BatchProcessRequest(
            List<ControllerBatchLoadRequestItem> items,
            String defaultLoaderName,
            String defaultChunkerName,
            Map<String, Object> defaultChunkerOptions,
            String defaultVectorStoreName
    ) {}

    public record DocumentProcessingResult(
            int originalDocumentCount,
            int finalChunkCount,
            List<String> processedDocumentIds,
            String loaderUsed,
            String chunkerUsed,
            boolean indexingSuccessful,
            String processingDetails
    ) {}

    public record RetrievedDocInfo(
            String id,
            String text,
            Double score,
            Map<String, Object> metadata,
            int contentLength,
            String contentPreview
    ) {}

    @GetMapping("/loaders")
    public ResponseEntity<List<LoaderInfo>> listAvailableLoaders() {
        if (documentLoaders == null || documentLoaders.isEmpty()) {
            logger.warn("No DocumentLoader beans found. listAvailableLoaders will return an empty list.");
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<LoaderInfo> loaderInfos = documentLoaders.stream()
                .map(loader -> new LoaderInfo(loader.getName(), loader.getClass().getName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(loaderInfos);
    }

    @GetMapping("/chunkers")
    public ResponseEntity<List<ChunkerInfo>> listAvailableChunkers() {
        if (textChunkers == null || textChunkers.isEmpty()) {
            logger.warn("No TextChunker beans found. listAvailableChunkers will return an empty list.");
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<ChunkerInfo> chunkerInfos = textChunkers.stream()
                .map(chunker -> new ChunkerInfo(chunker.getName(), chunker.getClass().getName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(chunkerInfos);
    }

    /**
     * Converts a RetrievedDoc to a display-friendly info record
     */
    private RetrievedDocInfo convertToRetrievedDocInfo(RetrievedDoc doc) {
        String text = doc.getText();
        String preview = null;
        
        if (text != null) {
            preview = text.length() > 300 ? text.substring(0, 300) + "..." : text;
        }
        
        return new RetrievedDocInfo(
                doc.getId(),
                text,
                doc.getScore(),
                doc.getMetadata(),
                text != null ? text.length() : 0,
                preview
        );
    }

    /**
     * Converts a Spring AI Document to a RetrievedDoc
     */
    private RetrievedDoc convertToRetrievedDoc(Document document) {
        return RetrievedDoc.builder()
                .id(document.getId())
                .text(document.getText())
                .metadata(document.getMetadata())
                .build();
    }

    /**
     * Converts a list of Spring AI Documents to RetrievedDocs
     */
    private List<RetrievedDoc> convertToRetrievedDocs(List<Document> documents) {
        return documents.stream()
                .map(this::convertToRetrievedDoc)
                .collect(Collectors.toList());
    }

    // ... (rest of the methods remain the same, but I'll truncate for brevity)
    // The full implementation is available in the artifact above
}