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

package ai.kompile.app.core.chunking;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A no-operation chunker that returns the original document as a single chunk.
 * 
 * <p>
 * This chunker is useful when you want to disable chunking or when dealing with
 * documents that are already appropriately sized for your use case.
 * </p>
 * 
 * <p>
 * The NoOpChunker adds minimal metadata to track that the document has been
 * processed through the chunking pipeline, but otherwise leaves the document unchanged.
 * </p>
 */
@Component("noOpChunker")
@ConditionalOnProperty(name = "kompile.chunking.strategy", havingValue = "noop", matchIfMissing = false)
public class NoOpChunker implements TextChunker {
    
    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);

        Map<String, Object> mergedOptions = prepareOptions(options);
        boolean collectGarbage = (Boolean) mergedOptions.getOrDefault(OPTION_COLLECT_GARBAGE, true);
        boolean includeGarbageChunk = (Boolean) mergedOptions.getOrDefault(OPTION_INCLUDE_GARBAGE_CHUNK, true);

        String text = document.getText();

        // Check if the document is garbage (not a complete sentence)
        if (collectGarbage && SentenceFilter.isGarbage(text)) {
            if (includeGarbageChunk) {
                return List.of(SentenceFilter.createGarbageChunk(
                    document, List.of(text.trim()), getName(), 0, 1));
            }
            return List.of();
        }

        // Create a new document with additional chunk metadata
        Map<String, Object> chunkMetadata = new HashMap<>(document.getMetadata());
        chunkMetadata.put("chunk.strategy", getName());
        chunkMetadata.put("chunk.index", 0);
        chunkMetadata.put("chunk.total", 1);
        chunkMetadata.put("chunk.originalId", document.getId());
        chunkMetadata.put("chunk.isGarbage", false);
        chunkMetadata.put("chunk.contentType", "sentence");

        RetrievedDoc chunkedDoc = RetrievedDoc.builder()
            .id(document.getId() + "-chunk-0")
            .text(document.getText())
            .metadata(chunkMetadata)
            .score(document.getScore())
            .build();

        return List.of(chunkedDoc);
    }

    @Override
    public String getName() {
        return "noop";
    }

    @Override
    public List<String> getSupportedLanguages() {
        // Indicates that this chunker is language-agnostic
        return Collections.singletonList("*");
    }
    
    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        // Garbage collection options - disabled by default (see TextChunker interface)
        defaults.put(OPTION_COLLECT_GARBAGE, false);
        defaults.put(OPTION_INCLUDE_GARBAGE_CHUNK, true);
        return defaults;
    }
}
