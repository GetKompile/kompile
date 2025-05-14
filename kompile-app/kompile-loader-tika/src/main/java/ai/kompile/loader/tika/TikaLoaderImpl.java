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

package ai.kompile.loader.tika; // New package

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Component("tikaDocumentLoader") // Explicit bean name
public class TikaLoaderImpl implements DocumentLoader {
    private static final Logger logger = LogManager.getLogger(TikaLoaderImpl.class);

    @Override
    public boolean supports(DocumentSourceDescriptor sd) {
        String pathLower = sd.getPathOrUrl().toLowerCase();
        // Tika is quite versatile, good for URLs and common text-based file formats
        return sd.getType() == DocumentSourceDescriptor.SourceType.URL ||
                (sd.getType() == DocumentSourceDescriptor.SourceType.FILE &&
                        (pathLower.endsWith(".txt") ||
                                pathLower.endsWith(".md") ||
                                pathLower.endsWith(".html") ||
                                pathLower.endsWith(".htm") ||
                                // Add other formats Tika handles well, e.g., .doc, .docx, .ppt if Tika parsers are on classpath
                                pathLower.endsWith(".doc") ||
                                pathLower.endsWith(".docx")
                        ));
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sd) throws IOException {
        Resource resource;
        logger.debug("TikaLoader attempting to load: {}", sd.getPathOrUrl());
        if (sd.getType() == DocumentSourceDescriptor.SourceType.URL) {
            resource = new UrlResource(sd.getPathOrUrl());
        } else { // FILE type
            File file = new File(sd.getPathOrUrl()).getAbsoluteFile(); // Path should already be absolute from orchestrator
            if (!file.exists() || !file.canRead()) {
                logger.error("File resource does not exist or is not readable by TikaLoader: {}", file.getAbsolutePath());
                return Collections.emptyList();
            }
            resource = new FileSystemResource(file);
        }

        if (!resource.exists()) { // Check after creating UrlResource as well
            logger.error("Resource does not exist (URL or File): {}", sd.getPathOrUrl());
            return Collections.emptyList();
        }
        if (!resource.isReadable() && sd.getType() != DocumentSourceDescriptor.SourceType.URL) {
            // For URLs, isReadable might be false before connection, TikaDocumentReader handles it.
            logger.error("Resource is not readable: {}", sd.getPathOrUrl());
            return Collections.emptyList();
        }

        try {
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> docs = tikaReader.get();
            // TikaDocumentReader might add a "source" metadata key with the resource description.
            // We can enhance it if needed.
            for(Document doc : docs) {
                doc.getMetadata().putIfAbsent("original_file_name", sd.getOriginalFileName());
                doc.getMetadata().putIfAbsent("source_path", sd.getPathOrUrl());
                doc.getMetadata().putIfAbsent("loader_type", "TikaLoaderImpl");
            }
            return docs;
        } catch (Exception e) {
            logger.error("Tika failed to read resource {}: {}", sd.getPathOrUrl(), e.getMessage(), e);
            // Do not throw IOException here, let the orchestrator decide if one failure is critical
            return Collections.emptyList(); // Or throw specific custom exception
        }
    }
}