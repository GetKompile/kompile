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

package ai.kompile.loader.tika;

// ... other imports
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.tika.Tika; // Import Tika
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;


import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

@Component // Ensure it's a Spring component
public class TikaLoaderImpl implements DocumentLoader {

    private final Tika tika = new Tika();

    @Override
    public String getName() {
        return "Apache Tika Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        // Tika supports many file types, so it could be a fallback or check extensions it's good at.
        // For simplicity, let's say it supports if it's a file and not explicitly handled by others.
        // A more robust check might involve trying to detect type with Tika itself.
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.FILE;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            throw new IllegalArgumentException("TikaLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        String content;
        try (InputStream stream = new FileInputStream(file)) {
            content = tika.parseToString(stream);
        }

        Document springDoc = new Document(content);
        springDoc.getMetadata().put("source", file.getAbsolutePath());
        springDoc.getMetadata().put("fileName", file.getName());
        // You might want to add more metadata from Tika if available
        // org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
        // try (InputStream streamMeta = new FileInputStream(file)) {
        //    tika.parse(streamMeta, tikaMetadata); // Fills metadata
        // }
        // for(String name : tikaMetadata.names()) {
        //    springDoc.getMetadata().put(name, tikaMetadata.get(name));
        // }
        return List.of(springDoc);
    }
}