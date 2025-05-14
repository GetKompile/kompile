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

package ai.kompile.loaders.orchestrator; // New package

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties; // Correct import
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ConfigurableDocumentLoadingServiceImpl implements DocumentLoadingService {
    private static final Logger logger = LogManager.getLogger(ConfigurableDocumentLoadingServiceImpl.class);
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

        // 1. Gather all source descriptors from app.document.sources
        if (sourceProperties.getSources() != null && !sourceProperties.getSources().isEmpty()) {
            for (String sourceString : sourceProperties.getSources()) {
                generateDescriptorsFromString(sourceString, sourceDescriptors);
            }
        } else {
            logger.warn("No sources configured in 'app.document.sources'.");
        }

        // 2. Add descriptors from the specific uploadsPath if configured
        if (sourceProperties.getUploadsPath() != null && !sourceProperties.getUploadsPath().trim().isEmpty()) {
            File uploadsDirFile = new File(sourceProperties.getUploadsPath()).getAbsoluteFile();
            if (uploadsDirFile.exists() && uploadsDirFile.isDirectory()) {
                logger.info("Processing uploads directory for sources: {}", uploadsDirFile.getAbsolutePath());
                generateDescriptorsFromString(uploadsDirFile.getAbsolutePath(), sourceDescriptors);
            } else {
                // It's fine if uploadsPath doesn't exist initially, DocumentManagementController creates it.
                logger.info("Configured uploads path '{}' does not yet exist or is not a directory. Will be skipped if not created.", uploadsDirFile.getAbsolutePath());
            }
        }

        if (sourceDescriptors.isEmpty()) {
            logger.warn("No valid source descriptors generated after processing all configurations. No documents will be loaded.");
            return allDocuments;
        }
        logger.info("Generated {} source descriptors to process.", sourceDescriptors.size());

        // 3. Load documents using appropriate loaders
        for (DocumentSourceDescriptor descriptor : sourceDescriptors) {
            boolean loadedSuccessfully = false;
            for (DocumentLoader loader : documentLoaders) {
                if (loader.supports(descriptor)) {
                    try {
                        logger.info("Using loader {} for source: {}", loader.getClass().getSimpleName(), descriptor.getPathOrUrl());
                        List<Document> docs = loader.load(descriptor);
                        if (docs != null && !docs.isEmpty()) {
                            allDocuments.addAll(docs);
                            logger.info("Loader {} successfully loaded {} document(s) from source: {}",
                                    loader.getClass().getSimpleName(), docs.size(), descriptor.getPathOrUrl());
                            loadedSuccessfully = true;
                        } else {
                            logger.info("Loader {} processed source {} but returned no documents.",
                                    loader.getClass().getSimpleName(), descriptor.getPathOrUrl());
                        }
                        // Even if one loader supports and returns docs, we usually assume it's the definitive one.
                        // If multiple loaders could support it, the first one in the injected list wins.
                        break;
                    } catch (Exception e) {
                        logger.error("Loader {} failed for source {}: {}",
                                loader.getClass().getSimpleName(), descriptor.getPathOrUrl(), e.getMessage());
                        // Continue to try other loaders if this one failed, though supports() should be exclusive ideally
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

    private void generateDescriptorsFromString(String sourceString, List<DocumentSourceDescriptor> descriptors) {
        if (sourceString == null || sourceString.trim().isEmpty()) {
            return;
        }
        logger.debug("Generating descriptors from source string: {}", sourceString);
        if (sourceString.toLowerCase().startsWith("http://") || sourceString.toLowerCase().startsWith("https://")) {
            descriptors.add(new DocumentSourceDescriptor(DocumentSourceDescriptor.SourceType.URL, sourceString, extractFileNameFromUrl(sourceString)));
        } else {
            File sourceFileOrDir = new File(sourceString).getAbsoluteFile();
            if (sourceFileOrDir.exists()) {
                if (sourceFileOrDir.isDirectory()) {
                    logger.info("Expanding directory source for descriptors: {}", sourceFileOrDir.getAbsolutePath());
                    try (Stream<Path> walk = Files.walk(sourceFileOrDir.toPath())) {
                        walk.filter(Files::isRegularFile)
                                .forEach(filePath -> {
                                    descriptors.add(new DocumentSourceDescriptor(DocumentSourceDescriptor.SourceType.FILE,
                                            filePath.toString(),
                                            filePath.getFileName().toString()));
                                });
                    } catch (IOException e) {
                        logger.error("Error walking directory {}: {}", sourceFileOrDir.getAbsolutePath(), e.getMessage());
                    }
                } else if (sourceFileOrDir.isFile()) {
                    descriptors.add(new DocumentSourceDescriptor(DocumentSourceDescriptor.SourceType.FILE,
                            sourceFileOrDir.getAbsolutePath(),
                            sourceFileOrDir.getName()));
                } else {
                    logger.warn("Path exists but is not a recognized file or directory: {}", sourceFileOrDir.getAbsolutePath());
                }
            } else {
                logger.warn("Path does not exist: {} (was resolved from: {})", sourceFileOrDir.getAbsolutePath(), sourceString);
            }
        }
    }

    private String extractFileNameFromUrl(String urlString) {
        try {
            Path path = Paths.get(new java.net.URI(urlString).getPath());
            String fileName = path.getFileName() != null ? path.getFileName().toString() : null;
            if (fileName == null || fileName.isEmpty()) {
                return "url_doc_" + UUID.randomUUID().toString().substring(0, 8);
            }
            return fileName;
        } catch (Exception e) {
            logger.warn("Could not extract filename from URL '{}', generating UUID based name.", urlString, e);
            return "url_doc_" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}