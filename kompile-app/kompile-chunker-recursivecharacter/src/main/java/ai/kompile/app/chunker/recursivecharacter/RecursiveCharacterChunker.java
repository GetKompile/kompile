/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package ai.kompile.app.chunker.recursivecharacter;

import ai.kompile.app.core.chunking.TextChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.*;

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
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        Assert.notNull(options, "Options map cannot be null");

        // int chunkSize = (int) options.getOrDefault(OPTION_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
        // int chunkOverlap = (int) options.getOrDefault(OPTION_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP);

        // Spring AI 1.0.0 RecursiveCharacterTextSplitter constructor is used by CustomRecursiveCharacterTextChunker
        CustomRecursiveCharacterTextChunker textSplitter = new CustomRecursiveCharacterTextChunker();
        // For custom separators:
        // List<String> separators = (List<String>) options.getOrDefault(OPTION_SEPARATORS, List.of("\n\n", "\n", " ", ""));
        // RecursiveCharacterTextSplitter textSplitter = new RecursiveCharacterTextSplitter(null, chunkSize, chunkOverlap, separators, true);


        // Spring AI TextSplitters take a List<Document> and return a List<Document>
        // The custom chunker now directly implements the chunking logic based on options passed.
        List<Document> splitDocuments = textSplitter.chunk(document, options);

        // Enrich metadata for each new chunk
        int chunkNumber = 0;
        for(Document chunk : splitDocuments) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata()); // Start with existing metadata from splitter
            metadata.putIfAbsent("original_document_id", document.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", getName());

            // Replace metadata with the enriched one
            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(metadata);
        }
        log.debug("Split document {} into {} chunks using {}. Options: chunkSize={}, chunkOverlap={}",
                document.getId(), splitDocuments.size(), getName(),
                options.getOrDefault(OPTION_CHUNK_SIZE, DEFAULT_CHUNK_SIZE),
                options.getOrDefault(OPTION_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP));
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
}