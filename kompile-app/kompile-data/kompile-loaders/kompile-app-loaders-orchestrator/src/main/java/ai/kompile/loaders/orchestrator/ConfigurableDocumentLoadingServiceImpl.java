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

// File: kompile-app/kompile-app-loaders-orchestrator/src/main/java/ai/kompile/loaders/orchestrator/ConfigurableDocumentLoadingServiceImpl.java
package ai.kompile.loaders.orchestrator;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ConfigurableDocumentLoadingServiceImpl implements DocumentLoadingService {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurableDocumentLoadingServiceImpl.class);
    private final AppDocumentSourceProperties sourceProperties;
    private final List<DocumentLoader> documentLoaders;

    public ConfigurableDocumentLoadingServiceImpl(AppDocumentSourceProperties sourceProperties, List<DocumentLoader> documentLoaders) {
        this.sourceProperties = sourceProperties;
        this.documentLoaders = documentLoaders;
        if (documentLoaders == null || documentLoaders.isEmpty()) {
            logger.warn("ConfigurableDocumentLoadingServiceImpl initialized with NO document loaders! Document loading will be limited.");
        } else {
            logger.info("ConfigurableDocumentLoadingServiceImpl initialized with {} document loader(s): {}",
                    documentLoaders.size(),
                    documentLoaders.stream().map(l -> l.getClass().getSimpleName()).toList()
            );
        }
    }

    @Override
    public List<Document> loadAllConfiguredDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        List<DocumentSourceDescriptor> sourceDescriptors = new ArrayList<>();

        if (sourceProperties.getSources() != null && !sourceProperties.getSources().isEmpty()) {
            for (String sourceString : sourceProperties.getSources()) {
                generateDescriptorsFromString(sourceString, sourceDescriptors);
            }
        } else {
            logger.warn("No sources configured in 'app.document.sources'.");
        }

        if (sourceProperties.getUploadsPath() != null && !sourceProperties.getUploadsPath().trim().isEmpty()) {
            File uploadsDirFile = new File(sourceProperties.getUploadsPath()).getAbsoluteFile();
            if (uploadsDirFile.exists() && uploadsDirFile.isDirectory()) {
                logger.info("Processing uploads directory for sources: {}", uploadsDirFile.getAbsolutePath());
                generateDescriptorsFromString(uploadsDirFile.getAbsolutePath(), sourceDescriptors);
            } else {
                logger.info("Configured uploads path '{}' does not yet exist or is not a directory. Will be skipped if not created.", uploadsDirFile.getAbsolutePath());
            }
        }

        if (sourceDescriptors.isEmpty()) {
            logger.warn("No valid source descriptors generated after processing all configurations. No documents will be loaded.");
            return allDocuments;
        }
        logger.info("Generated {} source descriptors to process.", sourceDescriptors.size());

        for (DocumentSourceDescriptor descriptor : sourceDescriptors) {
            boolean loadedSuccessfully = false;
            for (DocumentLoader loader : documentLoaders) {
                if (loader.supports(descriptor)) {
                    try {
                        logger.info("Using loader {} for source: {}", loader.getClass().getSimpleName(), descriptor.getPathOrUrl());
                        List<Document> docs = loader.load(descriptor);
                        if (docs != null && !docs.isEmpty()) {
                            for (Document doc : docs) {
                                doc.getMetadata().putIfAbsent("source", descriptor.getPathOrUrl());
                                if (descriptor.getOriginalFileName() != null) {
                                    doc.getMetadata().putIfAbsent("fileName", descriptor.getOriginalFileName());
                                }
                            }
                            allDocuments.addAll(docs);
                            logger.info("Loader {} successfully loaded {} document(s) from source: {}",
                                    loader.getClass().getSimpleName(), docs.size(), descriptor.getPathOrUrl());
                            loadedSuccessfully = true;
                        } else {
                            logger.info("Loader {} processed source {} but returned no documents.",
                                    loader.getClass().getSimpleName(), descriptor.getPathOrUrl());
                        }
                        break;
                    } catch (Exception e) {
                        logger.error("Loader {} failed for source {}: {}",
                                loader.getClass().getSimpleName(), descriptor.getPathOrUrl(), e.getMessage(), e);
                    }
                }
            }
            if (!loadedSuccessfully) {
                logger.warn("No suitable loader successfully processed source: {}", descriptor.getPathOrUrl());
            }
        }

        logger.info("ConfigurableDocumentLoadingServiceImpl: Total documents loaded from all sources: {}", allDocuments.size());
        return allDocuments;
    }

    @Override
    public List<Document> loadDocumentsFromSource(DocumentSourceDescriptor sourceDescriptor, String loaderName) throws Exception {
        Optional<DocumentLoader> selectedLoaderOpt = documentLoaders.stream()
                .filter(loader -> loader.getName().equalsIgnoreCase(loaderName) || loader.getClass().getSimpleName().equalsIgnoreCase(loaderName) || loader.getClass().getName().equalsIgnoreCase(loaderName))
                .findFirst();

        if (selectedLoaderOpt.isEmpty()) {
            throw new IllegalArgumentException("No loader found with name: " + loaderName);
        }

        DocumentLoader selectedLoader = selectedLoaderOpt.get();
        if (!selectedLoader.supports(sourceDescriptor)) {
            throw new IllegalArgumentException("Loader '" + selectedLoader.getName() + "' does not support the provided source: " + sourceDescriptor.getPathOrUrl());
        }

        logger.info("Directly loading with loader {} for source: {}", selectedLoader.getName(), sourceDescriptor.getPathOrUrl());
        List<Document> loadedDocs = selectedLoader.load(sourceDescriptor);

        // Add standard metadata from descriptor to the loaded Spring AI documents
        if (loadedDocs != null) {
            for (Document doc : loadedDocs) {
                doc.getMetadata().putIfAbsent("source", sourceDescriptor.getPathOrUrl());
                if (sourceDescriptor.getOriginalFileName() != null) {
                    doc.getMetadata().putIfAbsent("fileName", sourceDescriptor.getOriginalFileName());
                }
                // If BatchLoadRequestItem were to have a metadata map, it would be applied here:
                // e.g., if (itemMetadata != null) { doc.getMetadata().putAll(itemMetadata); }
            }
        }
        return loadedDocs;
    }

    @Override
    public Map<String, Object> loadDocumentsBatch(List<BatchLoadRequestItem> sourceRequests, String defaultLoaderName) {
        Map<String, Object> results = new HashMap<>();
        if (sourceRequests == null || sourceRequests.isEmpty()) {
            return results;
        }

        for (BatchLoadRequestItem item : sourceRequests) {
            String effectiveLoaderName = (item.loaderName() != null && !item.loaderName().isEmpty()) ? item.loaderName() : defaultLoaderName;
            if (effectiveLoaderName == null || effectiveLoaderName.isEmpty()) {
                results.put(item.pathOrUrl(), Map.of("error", "No loader specified and no default loader provided."));
                logger.warn("Skipping batch item {}: No loader specified and no default.", item.pathOrUrl());
                continue;
            }


            DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                    .type(item.type())
                    .sourceId(item.pathOrUrl())
                    .collectionName(extractFileName(item.pathOrUrl(), item.type()))
                    .build();

            try {
                List<Document> loadedDocs = loadDocumentsFromSource(descriptor, effectiveLoaderName);

                List<Map<String, Object>> docSummaries = new ArrayList<>();
                if (loadedDocs != null) {
                    for (Document doc : loadedDocs) {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("id", doc.getId());
                        summary.put("metadata", doc.getMetadata());
                        summary.put("contentSnippet", doc.getText() != null ? doc.getText().substring(0, Math.min(doc.getText().length(), 100)) + "..." : "[no content]");
                        docSummaries.add(summary);
                    }
                }
                results.put(item.pathOrUrl(), Map.of("count", docSummaries.size(), "summaries", docSummaries));
                logger.info("Batch loaded {} documents from {} using loader {}", docSummaries.size(), item.pathOrUrl(), effectiveLoaderName);
            } catch (Exception e) {
                logger.error("Error batch loading from {}: {}", item.pathOrUrl(), e.getMessage(), e);
                results.put(item.pathOrUrl(), Map.of("error", e.getMessage()));
            }
        }
        return results;
    }

    private void generateDescriptorsFromString(String sourceString, List<DocumentSourceDescriptor> descriptors) {
        if (sourceString == null || sourceString.trim().isEmpty()) {
            return;
        }
        logger.debug("Generating descriptors from source string: {}", sourceString);
        DocumentSourceDescriptor.SourceType type;
        String path;
        String originalFileName = null;

        if (sourceString.toLowerCase().startsWith("http://") || sourceString.toLowerCase().startsWith("https://")) {
            type = DocumentSourceDescriptor.SourceType.URL;
            path = sourceString;
            originalFileName = extractFileNameFromUrl(sourceString);
            descriptors.add(DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .collectionName(path)
                    .originalFileName(originalFileName)
                    .build()); // Corrected
        } else {
            File sourceFileOrDir = new File(sourceString).getAbsoluteFile();
            path = sourceFileOrDir.getAbsolutePath();
            originalFileName = sourceFileOrDir.getName();
            if (sourceFileOrDir.exists()) {
                if (sourceFileOrDir.isDirectory()) {
                    type = DocumentSourceDescriptor.SourceType.DIRECTORY;
                    logger.info("Expanding directory source for descriptors: {}", path);
                    try (Stream<Path> walk = Files.walk(sourceFileOrDir.toPath())) {
                        String finalOriginalFileName = originalFileName;
                        walk.filter(Files::isRegularFile)
                                .forEach(filePath -> {
                                    descriptors.add(DocumentSourceDescriptor.builder()
                                            .type(DocumentSourceDescriptor.SourceType.FILE)
                                            .collectionName(String.valueOf(filePath.getParent().getFileName()))
                                            .originalFileName(finalOriginalFileName)
                                            .build());

                                });
                        // Do not add the directory itself as a descriptor if individual files are added.
                    } catch (IOException e) {
                        logger.error("Error walking directory {}: {}", path, e.getMessage());
                        // Optionally, you could add the directory descriptor here if walking fails
                        // but current logic skips it, which might be intended.
                        // If you want to add it even if walking fails, uncomment the next line:
                        // descriptors.add(new DocumentSourceDescriptor(type, path, originalFileName));
                    }
                } else if (sourceFileOrDir.isFile()) {
                    type = DocumentSourceDescriptor.SourceType.FILE;
                    descriptors.add(DocumentSourceDescriptor.builder()
                            .type(type)
                            .originalFileName(originalFileName)
                            .collectionName(originalFileName).build()); // Corrected
                } else {
                    logger.warn("Path exists but is not a recognized file or directory: {}", path);
                }
            } else {
                logger.warn("Path does not exist: {} (was resolved from: {})", path, sourceString);
            }
        }
    }


    private String extractFileName(String pathOrUrl, DocumentSourceDescriptor.SourceType type) {
        if (pathOrUrl == null) return "unknown_source";
        try {
            if (type == DocumentSourceDescriptor.SourceType.URL) {
                return extractFileNameFromUrl(pathOrUrl);
            } else if (type == DocumentSourceDescriptor.SourceType.FILE || type == DocumentSourceDescriptor.SourceType.DIRECTORY) {
                return Paths.get(pathOrUrl).getFileName().toString();
            }
        } catch (Exception e) {
            logger.warn("Could not extract filename from path/URL '{}'.", pathOrUrl, e);
        }
        return "unknown_source_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String extractFileNameFromUrl(String urlString) {
        try {
            Path path = Paths.get(new java.net.URI(urlString).getPath());
            String fileName = path.getFileName() != null ? path.getFileName().toString() : null;
            if (fileName == null || fileName.isEmpty() || fileName.equals("/")) {
                String host = new java.net.URI(urlString).getHost();
                if (host != null) {
                    fileName = host.replace("www.","").replaceAll("[^a-zA-Z0-9.-]", "_") + ".html";
                } else {
                    fileName = "url_doc_" + UUID.randomUUID().toString().substring(0, 8) + ".html";
                }
            }
            if (!fileName.matches(".*\\.[a-zA-Z0-9]{1,6}$")) {
                return fileName + ".html";
            }
            return fileName;
        } catch (Exception e) {
            logger.warn("Could not extract filename from URL '{}', generating UUID based name.", urlString, e);
            return "url_doc_" + UUID.randomUUID().toString().substring(0, 8) + ".html";
        }
    }
}