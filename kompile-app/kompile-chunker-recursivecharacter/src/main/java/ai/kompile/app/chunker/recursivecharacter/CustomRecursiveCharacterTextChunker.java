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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@Component("customRecursiveCharacterTextChunker")
public class CustomRecursiveCharacterTextChunker implements TextChunker {

    private static final String CHUNKER_NAME = "custom_recursive_character";
    public static final String OPTION_CHUNK_SIZE = "chunkSize";
    public static final String OPTION_CHUNK_OVERLAP = "chunkOverlap";
    public static final String OPTION_SEPARATORS = "separators";
    public static final String OPTION_LENGTH_FUNCTION = "lengthFunction"; // "chars" or "tokens" (if token-based length needed)

    public static final int DEFAULT_CHUNK_SIZE = 1000;
    public static final int DEFAULT_CHUNK_OVERLAP = 200;
    private static final List<String> DEFAULT_SEPARATORS = Arrays.asList("\n\n", "\n", ". ", " ", "");

    private final Function<String, Integer> defaultLengthFunction = String::length;

    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        Assert.notNull(options, "Options map cannot be null");

        String text = document.getText();
        if (StringUtils.isBlank(text)) {
            return List.of();
        }

        int chunkSize = (int) options.getOrDefault(OPTION_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
        int chunkOverlap = (int) options.getOrDefault(OPTION_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP);
        @SuppressWarnings("unchecked")
        List<String> separators = (List<String>) options.getOrDefault(OPTION_SEPARATORS, DEFAULT_SEPARATORS);

        // For simplicity, this implementation uses character length.
        // A more advanced version could accept a Function<String, Integer> for length calculation (e.g. token count)
        // Function<String, Integer> lengthFunction = (Function<String, Integer>) options.getOrDefault(OPTION_LENGTH_FUNCTION, defaultLengthFunction);


        List<String> textChunks = splitTextWithSeparators(text, separators, chunkSize, chunkOverlap, defaultLengthFunction);

        List<Document> resultDocuments = new ArrayList<>();
        int chunkNumber = 0;
        for (String chunkText : textChunks) {
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.put("original_document_id", document.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", getName());
            resultDocuments.add(new Document(UUID.randomUUID().toString(), chunkText, metadata));
        }
        log.debug("Split document {} into {} chunks using {}. Options: chunkSize={}, chunkOverlap={}",
                document.getId(), resultDocuments.size(), getName(), chunkSize, chunkOverlap);
        return resultDocuments;
    }

    private List<String> splitTextWithSeparators(String text, List<String> separators, int chunkSize, int chunkOverlap, Function<String, Integer> lengthFunction) {
        List<String> finalChunks = new ArrayList<>();
        String separator = separators.get(0);
        List<String> remainingSeparators = separators.subList(1, separators.size());

        List<String> splits;
        if (separator.isEmpty()) { // Base case: no more separators, split by character if needed
            for (int i = 0; i < text.length(); i += chunkSize) {
                String chunk = text.substring(i, Math.min(text.length(), i + chunkSize));
                finalChunks.add(chunk);
            }
            return finalChunks; // Should merge these small pieces later if they are too small. This part needs refinement for proper merging.
        } else {
            splits = Arrays.asList(text.split(Pattern.quote(separator))); // Using Pattern.quote for safety
        }

        List<String> goodSplits = new ArrayList<>();
        String currentChunkBuffer = "";
        for (String s : splits) {
            if (lengthFunction.apply(s) < chunkSize) {
                if (!currentChunkBuffer.isEmpty()) {
                    currentChunkBuffer += separator;
                }
                currentChunkBuffer += s;
            } else {
                if (!currentChunkBuffer.isEmpty()) {
                    goodSplits.add(currentChunkBuffer);
                    currentChunkBuffer = ""; // reset buffer
                }
                // This part needs to recursively call splitTextWithSeparators if s is still too large
                if (lengthFunction.apply(s) > chunkSize && !remainingSeparators.isEmpty()) {
                    goodSplits.addAll(splitTextWithSeparators(s, remainingSeparators, chunkSize, chunkOverlap, lengthFunction));
                } else {
                    goodSplits.add(s);
                }
            }
        }
        if(!currentChunkBuffer.isEmpty()) {
            goodSplits.add(currentChunkBuffer);
        }

        // Merge small splits and handle overlap - This is a simplified merge
        return mergeSplits(goodSplits, separator, chunkSize, chunkOverlap, lengthFunction);
    }

    private List<String> mergeSplits(List<String> splits, String separator, int chunkSize, int chunkOverlap, Function<String, Integer> lengthFunction) {
        List<String> mergedChunks = new ArrayList<>();
        if (splits.isEmpty()) {
            return mergedChunks;
        }

        List<String> currentChunkList = new ArrayList<>();
        int currentTotalLength = 0;

        for (int i = 0; i < splits.size(); i++) {
            String split = splits.get(i);
            int splitLength = lengthFunction.apply(split);
            int lengthWithSeparator = currentTotalLength == 0 ? splitLength : currentTotalLength + lengthFunction.apply(separator) + splitLength;

            if (lengthWithSeparator > chunkSize && !currentChunkList.isEmpty()) {
                mergedChunks.add(String.join(separator, currentChunkList));

                // Handle overlap: Start new chunk with overlapping part of the previous
                List<String> tempListForOverlap = new ArrayList<>();
                int overlapLength = 0;
                for(int j = currentChunkList.size() -1; j >=0; j--) {
                    String prevPiece = currentChunkList.get(j);
                    tempListForOverlap.add(0, prevPiece); // add to beginning
                    overlapLength += lengthFunction.apply(prevPiece);
                    if (j > 0) overlapLength += lengthFunction.apply(separator); // separator length
                    if (overlapLength >= chunkOverlap && tempListForOverlap.size() > 1) break; // ensure some overlap content
                }

                currentChunkList.clear();
                // If there was a meaningful overlap collected that isn't the entire previous chunk
                if (overlapLength > 0 && overlapLength < currentTotalLength) {
                    currentChunkList.addAll(tempListForOverlap);
                    currentTotalLength = overlapLength;
                } else {
                    currentTotalLength = 0; // No meaningful overlap to carry, reset
                }
            }

            currentChunkList.add(split);
            currentTotalLength = lengthFunction.apply(String.join(separator, currentChunkList));


            // If the current split itself is larger than chunk_size (after trying to split by finer separators)
            // it should be added as is, or further split if possible (though current logic might not fully handle this perfectly)
            if (splitLength > chunkSize && currentChunkList.size() == 1) {
                mergedChunks.add(split); // Add as is if it's a single large piece
                currentChunkList.clear();
                currentTotalLength = 0;
            }
        }

        if (!currentChunkList.isEmpty()) {
            mergedChunks.add(String.join(separator, currentChunkList));
        }

        return mergedChunks;
    }


    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        return Arrays.asList("en");
    }
}