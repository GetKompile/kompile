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

package ai.kompile.app.chunker.token;

import ai.kompile.app.core.chunking.SentenceFilter;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component("springTokenTextChunker")
public class SpringTokenChunker implements TextChunker {

    private static final String CHUNKER_NAME = "spring_token";

    // Options aligned with Spring AI's TokenTextSplitter.Builder
    public static final String OPTION_CHUNK_SIZE_TOKENS = "chunkSize"; // Target size of each chunk in tokens
    public static final String OPTION_MIN_CHUNK_SIZE_CHARS = "minChunkSizeChars"; // Minimum size of each text chunk in characters
    public static final String OPTION_MIN_CHUNK_LENGTH_TO_EMBED_CHARS = "minChunkLengthToEmbed"; // Discard chunks shorter than this (chars)
    public static final String OPTION_MAX_NUM_CHUNKS = "maxNumChunks"; // Maximum number of chunks to generate
    public static final String OPTION_KEEP_SEPARATOR = "keepSeparator"; // Whether to keep separators
    public static final String OPTION_TOKENIZER_NAME = "tokenizerName"; // Kept for conceptual compatibility, though TokenTextSplitter uses fixed CL100K_BASE

    // Defaults from Spring AI's TokenTextSplitter
    public static final int DEFAULT_CHUNK_SIZE_TOKENS = 800;
    public static final int DEFAULT_MIN_CHUNK_SIZE_CHARS = 350;
    public static final int DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED_CHARS = 5;
    public static final int DEFAULT_MAX_NUM_CHUNKS = 10000;
    public static final boolean DEFAULT_KEEP_SEPARATOR = true;
    public static final String DEFAULT_TOKENIZER_NAME = EncodingType.CL100K_BASE.getName();

    public SpringTokenChunker() {
        // EncodingRegistry is handled internally by TokenTextSplitter if not using a custom one.
    }

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        // Validate document using the interface method
        validateDocument(document);

        // Prepare options with defaults
        Map<String, Object> mergedOptions = prepareOptions(options);
        boolean collectGarbage = (Boolean) mergedOptions.getOrDefault(OPTION_COLLECT_GARBAGE, true);
        boolean includeGarbageChunk = (Boolean) mergedOptions.getOrDefault(OPTION_INCLUDE_GARBAGE_CHUNK, true);

        int chunkSize = (int) mergedOptions.getOrDefault(OPTION_CHUNK_SIZE_TOKENS, DEFAULT_CHUNK_SIZE_TOKENS);
        int minChunkSizeChars = (int) mergedOptions.getOrDefault(OPTION_MIN_CHUNK_SIZE_CHARS, DEFAULT_MIN_CHUNK_SIZE_CHARS);
        int minChunkLengthToEmbed = (int) mergedOptions.getOrDefault(OPTION_MIN_CHUNK_LENGTH_TO_EMBED_CHARS, DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED_CHARS);
        int maxNumChunks = (int) mergedOptions.getOrDefault(OPTION_MAX_NUM_CHUNKS, DEFAULT_MAX_NUM_CHUNKS);
        boolean keepSeparator = (boolean) mergedOptions.getOrDefault(OPTION_KEEP_SEPARATOR, DEFAULT_KEEP_SEPARATOR);
        String tokenizerNameOption = (String) mergedOptions.getOrDefault(OPTION_TOKENIZER_NAME, DEFAULT_TOKENIZER_NAME);

        if (!DEFAULT_TOKENIZER_NAME.equalsIgnoreCase(tokenizerNameOption)) {
            log.warn("Provided tokenizerName option ('{}') is ignored. Spring AI's TokenTextSplitter internally uses '{}'.",
                    tokenizerNameOption, DEFAULT_TOKENIZER_NAME);
        }

        TokenTextSplitter textSplitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
                .withMaxNumChunks(maxNumChunks)
                .withKeepSeparator(keepSeparator)
                .build();

        // Convert RetrievedDoc to Spring AI Document for the splitter
        Document springDoc = new Document(document.getId(), document.getText(), document.getMetadata());
        List<Document> sourceDocuments = Collections.singletonList(springDoc);

        // The apply method in TextSplitter handles the conversion from List<Document> to List<String> for splitting,
        // then wraps the resulting strings back into Document objects.
        List<Document> splitDocumentsRaw = textSplitter.apply(sourceDocuments);

        List<RetrievedDoc> finalSplitDocuments = new ArrayList<>();
        int chunkNumber = 0;
        for(Document chunk : splitDocumentsRaw) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata()); // Start with existing metadata from splitter
            metadata.putIfAbsent("original_document_id", document.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", getName());
            // The actual tokenizer used by TokenTextSplitter is CL100K_BASE
            metadata.put("tokenizer", DEFAULT_TOKENIZER_NAME);

            // Create RetrievedDoc using proper constructor
            RetrievedDoc retrievedChunk;
            if (document.getScore() != null) {
                retrievedChunk = new RetrievedDoc(UUID.randomUUID().toString(), chunk.getText(), metadata, document.getScore());
            } else {
                retrievedChunk = new RetrievedDoc(UUID.randomUUID().toString(), chunk.getText(), metadata);
            }
            finalSplitDocuments.add(retrievedChunk);
        }

        log.debug("Split document {} into {} chunks using {}. Options: chunkSize={}, minChunkSizeChars={}, minChunkLengthToEmbed={}, maxNumChunks={}, keepSeparator={}. Tokenizer used: {}",
                document.getId(), finalSplitDocuments.size(), getName(),
                chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator, DEFAULT_TOKENIZER_NAME);

        // Apply sentence filtering and garbage collection if enabled
        if (collectGarbage) {
            return SentenceFilter.filterAndCollectGarbage(finalSplitDocuments, document, getName(), includeGarbageChunk);
        }

        return finalSplitDocuments;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        // jtokkit tokenizer CL100K_BASE is trained on diverse data but primarily optimized for English.
        return Arrays.asList("en", "multi");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(OPTION_CHUNK_SIZE_TOKENS, DEFAULT_CHUNK_SIZE_TOKENS);
        defaults.put(OPTION_MIN_CHUNK_SIZE_CHARS, DEFAULT_MIN_CHUNK_SIZE_CHARS);
        defaults.put(OPTION_MIN_CHUNK_LENGTH_TO_EMBED_CHARS, DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED_CHARS);
        defaults.put(OPTION_MAX_NUM_CHUNKS, DEFAULT_MAX_NUM_CHUNKS);
        defaults.put(OPTION_KEEP_SEPARATOR, DEFAULT_KEEP_SEPARATOR);
        defaults.put(OPTION_TOKENIZER_NAME, DEFAULT_TOKENIZER_NAME);
        defaults.put("chunkSize", DEFAULT_CHUNK_SIZE_TOKENS);
        defaults.put("overlap", 0); // Token chunker doesn't use overlap in the same way
        defaults.put("preserveParagraphs", DEFAULT_KEEP_SEPARATOR);
        // Garbage collection options - disabled by default (see TextChunker interface)
        defaults.put(OPTION_COLLECT_GARBAGE, false);
        defaults.put(OPTION_INCLUDE_GARBAGE_CHUNK, true);
        return defaults;
    }
}
