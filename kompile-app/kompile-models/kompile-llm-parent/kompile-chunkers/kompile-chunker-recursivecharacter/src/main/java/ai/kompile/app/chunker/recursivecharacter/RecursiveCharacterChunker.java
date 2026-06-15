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

package ai.kompile.app.chunker.recursivecharacter;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component("recursiveCharacterTextChunker")
public class RecursiveCharacterChunker implements TextChunker {

    private static final String CHUNKER_NAME = "spring_recursive_character";
    public static final String OPTION_CHUNK_SIZE = "chunkSize";
    public static final String OPTION_CHUNK_OVERLAP = "chunkOverlap";
    // Keep separators default from Spring AI or allow configuration
    // public static final String OPTION_SEPARATORS = "separators";

    public static final int DEFAULT_CHUNK_SIZE = 1000; // Default based on Spring AI's default
    public static final int DEFAULT_CHUNK_OVERLAP = 200; // Default based on Spring AI's default

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        // Validate document using the interface method
        validateDocument(document);
        
        // Prepare options with defaults
        Map<String, Object> mergedOptions = prepareOptions(options);

        // Spring AI 1.0.0 RecursiveCharacterTextSplitter constructor is used by CustomRecursiveCharacterTextChunker
        CustomRecursiveCharacterTextChunker textSplitter = new CustomRecursiveCharacterTextChunker();

        // Spring AI TextSplitters take a List<Document> and return a List<Document>
        // The custom chunker now directly implements the chunking logic based on options passed.
        List<RetrievedDoc> splitDocuments = textSplitter.chunk(document, mergedOptions);

        // Enrich metadata for each new chunk
        for (int i = 0; i < splitDocuments.size(); i++) {
            RetrievedDoc chunk = splitDocuments.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata()); // Start with existing metadata from splitter
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
        
        log.debug("Split document {} into {} chunks using {}. Options: chunkSize={}, chunkOverlap={}",
                document.getId(), splitDocuments.size(), getName(),
                mergedOptions.getOrDefault(OPTION_CHUNK_SIZE, DEFAULT_CHUNK_SIZE),
                mergedOptions.getOrDefault(OPTION_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP));
        return splitDocuments;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        // This chunker is based on character splitting and is language-agnostic.
        return Collections.singletonList("*");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(OPTION_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
        defaults.put(OPTION_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP);
        defaults.put("chunkSize", DEFAULT_CHUNK_SIZE);
        defaults.put("overlap", DEFAULT_CHUNK_OVERLAP);
        defaults.put("preserveParagraphs", true);
        return defaults;
    }
}
