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

package ai.kompile.app.chunker.markdown;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component("springMarkdownTextChunker")
public class SpringMarkdownChunker implements TextChunker {

    private static final String CHUNKER_NAME = "spring_markdown";

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        // Validate document using the interface method
        validateDocument(document);
        
        // Prepare options with defaults
        Map<String, Object> mergedOptions = prepareOptions(options);
        
        // Optionally, check if the document is indeed Markdown based on metadata
        // String mimeType = (String) document.getMetadata().getOrDefault("mime_type", "");
        // String fileName = (String) document.getMetadata().getOrDefault("file_name", "");
        // if (!"text/markdown".equalsIgnoreCase(mimeType) && !fileName.toLowerCase().endsWith(".md")) {
        //    log.warn("SpringMarkdownChunker is intended for Markdown documents, but received mime_type: {}, file_name: {}", mimeType, fileName);
        // }

        CustomMarkdownTextChunker textSplitter = new CustomMarkdownTextChunker();
        List<RetrievedDoc> splitDocuments = textSplitter.chunk(document, mergedOptions);

        // Add chunk metadata to each split document
        for (int i = 0; i < splitDocuments.size(); i++) {
            RetrievedDoc chunk = splitDocuments.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.putIfAbsent("original_document_id", document.getId());
            metadata.put("chunk_number", i);
            metadata.put("chunker", getName());

            // Create new RetrievedDoc with updated metadata
            RetrievedDoc updatedChunk;
            if (chunk.getScore() != null) {
                updatedChunk = new RetrievedDoc(chunk.getId(), chunk.getText(), metadata, chunk.getScore());
            } else {
                updatedChunk = new RetrievedDoc(chunk.getId(), chunk.getText(), metadata);
            }
            splitDocuments.set(i, updatedChunk);
        }
        
        log.debug("Split Markdown document {} into {} chunks using {}.", document.getId(), splitDocuments.size(), getName());
        return splitDocuments;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        // Markdown structure is language-agnostic
        return List.of("*");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("chunkSize", 2000);
        defaults.put("overlap", 200);
        defaults.put("preserveParagraphs", true);
        defaults.put("splitOnHeadings", true);
        defaults.put("maxCharsPerChunk", 2000);
        return defaults;
    }
}
