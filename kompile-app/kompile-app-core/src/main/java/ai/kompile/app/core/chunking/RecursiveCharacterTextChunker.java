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
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.regex.Pattern;

/**
 * A high-performance recursive character-based text chunker that splits documents
 * using a hierarchy of separators with parallel processing for large documents.
 *
 * <p>
 * This chunker attempts to split text on separators in order of preference:
 * <ol>
 * <li>Double newlines (paragraphs)</li>
 * <li>Single newlines</li>
 * <li>Sentences (periods, exclamation marks, question marks)</li>
 * <li>Clauses (commas, semicolons)</li>
 * <li>Words (spaces)</li>
 * <li>Characters (as last resort)</li>
 * </ol>
 * </p>
 *
 * <p>
 * Performance optimizations:
 * <ul>
 * <li>Pre-compiled regex patterns</li>
 * <li>Parallel chunk creation for large documents (>100KB)</li>
 * <li>Efficient StringBuilder usage</li>
 * <li>Fast indexOf() instead of contains()+split()</li>
 * </ul>
 * </p>
 */
@Component("recursiveCharacterTextChunker")
public class RecursiveCharacterTextChunker implements TextChunker {

    // Separator hierarchy - ordered by preference (most semantic to least)
    private static final String[] SEPARATORS = {
        "\n\n",    // Paragraph breaks
        "\n",      // Line breaks
        ". ",      // Sentence endings
        "! ",      // Exclamations
        "? ",      // Questions
        "; ",      // Semicolons
        ", ",      // Commas
        " ",       // Spaces
    };

    // Pre-compiled patterns for each separator (for efficient splitting)
    private static final Pattern[] SEPARATOR_PATTERNS;
    static {
        SEPARATOR_PATTERNS = new Pattern[SEPARATORS.length];
        for (int i = 0; i < SEPARATORS.length; i++) {
            SEPARATOR_PATTERNS[i] = Pattern.compile(Pattern.quote(SEPARATORS[i]));
        }
    }

    // Threshold for parallel processing (100KB)
    private static final int PARALLEL_THRESHOLD = 100_000;

    // ForkJoinPool for parallel chunk creation
    private static final ForkJoinPool CHUNK_POOL = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);

        Map<String, Object> mergedOptions = prepareOptions(options);
        int chunkSize = (Integer) mergedOptions.get("chunkSize");
        int overlap = (Integer) mergedOptions.get("overlap");
        boolean collectGarbage = (Boolean) mergedOptions.getOrDefault(OPTION_COLLECT_GARBAGE, true);
        boolean includeGarbageChunk = (Boolean) mergedOptions.getOrDefault(OPTION_INCLUDE_GARBAGE_CHUNK, true);

        String text = document.getText();
        if (text.length() <= chunkSize) {
            // Document is already small enough, check if it's valid content
            if (collectGarbage && SentenceFilter.isGarbage(text)) {
                if (includeGarbageChunk) {
                    return List.of(SentenceFilter.createGarbageChunk(
                        document, List.of(text.trim()), getName(), 0, 1));
                }
                return List.of();
            }
            return createSingleChunk(document, 0, 1);
        }

        // Split text into chunks
        List<String> chunks = splitTextOptimized(text, chunkSize, overlap);

        // Create chunk documents
        List<RetrievedDoc> chunkDocs;
        if (text.length() > PARALLEL_THRESHOLD && chunks.size() > 10) {
            chunkDocs = createChunkedDocumentsParallel(document, chunks);
        } else {
            chunkDocs = createChunkedDocuments(document, chunks);
        }

        // Apply sentence filtering and garbage collection if enabled
        if (collectGarbage) {
            return SentenceFilter.filterAndCollectGarbage(chunkDocs, document, getName(), includeGarbageChunk);
        }

        return chunkDocs;
    }

    /**
     * Optimized text splitting using indexOf() instead of contains()+split().
     */
    private List<String> splitTextOptimized(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        // Find the best separator to use
        int bestSeparatorIdx = findBestSeparator(text, chunkSize);

        if (bestSeparatorIdx >= 0) {
            // Split using the best separator
            String separator = SEPARATORS[bestSeparatorIdx];
            List<String> parts = fastSplit(text, separator);
            return createChunksFromParts(parts, chunkSize, overlap, separator, bestSeparatorIdx);
        }

        // Fallback to character-level splitting
        return splitByCharacters(text, chunkSize, overlap);
    }

    /**
     * Find the best separator that produces reasonable chunk sizes.
     */
    private int findBestSeparator(String text, int chunkSize) {
        for (int i = 0; i < SEPARATORS.length; i++) {
            int idx = text.indexOf(SEPARATORS[i]);
            if (idx >= 0 && idx < chunkSize * 2) {
                // This separator exists and first occurrence is within reasonable range
                return i;
            }
        }
        return -1; // No suitable separator found
    }

    /**
     * Fast split using indexOf() - avoids regex overhead.
     */
    private List<String> fastSplit(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int sepLen = separator.length();

        int idx;
        while ((idx = text.indexOf(separator, start)) >= 0) {
            parts.add(text.substring(start, idx));
            start = idx + sepLen;
        }

        // Add remaining text
        if (start < text.length()) {
            parts.add(text.substring(start));
        }

        return parts;
    }

    private List<String> createChunksFromParts(List<String> parts, int chunkSize, int overlap,
                                                String separator, int separatorIdx) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(chunkSize + overlap);

        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);

            // Skip empty parts
            if (part.isEmpty()) continue;

            int potentialLength = currentChunk.length() == 0
                ? part.length()
                : currentChunk.length() + separator.length() + part.length();

            if (potentialLength <= chunkSize) {
                // Add to current chunk
                if (currentChunk.length() > 0) {
                    currentChunk.append(separator);
                }
                currentChunk.append(part);
            } else {
                // Current chunk is full, save it and start a new one
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());

                    // Create overlap for next chunk
                    if (overlap > 0) {
                        String overlapText = getOverlapText(currentChunk.toString(), overlap);
                        currentChunk = new StringBuilder(chunkSize + overlap);
                        currentChunk.append(overlapText);

                        // Try to add the current part
                        int withPartLength = currentChunk.length() + separator.length() + part.length();
                        if (withPartLength <= chunkSize) {
                            if (currentChunk.length() > 0) {
                                currentChunk.append(separator);
                            }
                            currentChunk.append(part);
                        } else {
                            // Part is too large, need to split it further
                            if (part.length() > chunkSize && separatorIdx < SEPARATORS.length - 1) {
                                // Recursively split using next separator
                                List<String> subChunks = splitTextOptimized(part, chunkSize, overlap);
                                // First subchunk gets the overlap
                                if (!subChunks.isEmpty()) {
                                    if (currentChunk.length() > 0) {
                                        currentChunk.append(separator);
                                    }
                                    currentChunk.append(subChunks.get(0));
                                    if (currentChunk.length() > chunkSize) {
                                        chunks.add(currentChunk.toString());
                                        currentChunk = new StringBuilder(chunkSize + overlap);
                                    }
                                    for (int j = 1; j < subChunks.size(); j++) {
                                        chunks.add(subChunks.get(j));
                                    }
                                }
                            } else {
                                currentChunk = new StringBuilder(chunkSize + overlap);
                                currentChunk.append(part);
                            }
                        }
                    } else {
                        currentChunk = new StringBuilder(chunkSize + overlap);
                        currentChunk.append(part);
                    }
                } else {
                    // Part is larger than chunk size, split it further
                    if (part.length() > chunkSize) {
                        List<String> subChunks = splitTextOptimized(part, chunkSize, overlap);
                        chunks.addAll(subChunks);
                    } else {
                        currentChunk.append(part);
                    }
                }
            }
        }

        // Add the last chunk if it's not empty
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    private List<String> splitByCharacters(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int textLen = text.length();

        while (start < textLen) {
            int end = Math.min(start + chunkSize, textLen);

            // Try to find a word boundary near the end
            if (end < textLen) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start + chunkSize / 2) {
                    end = lastSpace;
                }
            }

            chunks.add(text.substring(start, end));
            start = end - overlap;
            if (start <= chunks.size() * (chunkSize - overlap) - chunkSize) {
                // Prevent infinite loop
                start = end;
            }
        }

        return chunks;
    }

    private String getOverlapText(String text, int overlapSize) {
        if (text.length() <= overlapSize) {
            return text;
        }

        // Try to start overlap at a word boundary
        int startPos = text.length() - overlapSize;
        int spacePos = text.indexOf(' ', startPos);
        if (spacePos > startPos && spacePos < text.length() - overlapSize / 2) {
            startPos = spacePos + 1;
        }

        return text.substring(startPos);
    }

    /**
     * Create chunk documents in parallel for large documents.
     */
    private List<RetrievedDoc> createChunkedDocumentsParallel(RetrievedDoc originalDoc, List<String> chunks) {
        return CHUNK_POOL.invoke(new ChunkCreationTask(originalDoc, chunks, 0, chunks.size()));
    }

    /**
     * RecursiveTask for parallel chunk document creation.
     */
    private static class ChunkCreationTask extends RecursiveTask<List<RetrievedDoc>> {
        private static final int THRESHOLD = 50; // Process at least 50 chunks per task

        private final RetrievedDoc originalDoc;
        private final List<String> chunks;
        private final int start;
        private final int end;

        ChunkCreationTask(RetrievedDoc originalDoc, List<String> chunks, int start, int end) {
            this.originalDoc = originalDoc;
            this.chunks = chunks;
            this.start = start;
            this.end = end;
        }

        @Override
        protected List<RetrievedDoc> compute() {
            int size = end - start;

            if (size <= THRESHOLD) {
                // Process directly
                List<RetrievedDoc> result = new ArrayList<>(size);
                int total = chunks.size();
                for (int i = start; i < end; i++) {
                    result.add(createChunkDoc(originalDoc, chunks.get(i), i, total));
                }
                return result;
            }

            // Split into two tasks
            int mid = start + size / 2;
            ChunkCreationTask left = new ChunkCreationTask(originalDoc, chunks, start, mid);
            ChunkCreationTask right = new ChunkCreationTask(originalDoc, chunks, mid, end);

            left.fork();
            List<RetrievedDoc> rightResult = right.compute();
            List<RetrievedDoc> leftResult = left.join();

            // Combine results
            List<RetrievedDoc> combined = new ArrayList<>(leftResult.size() + rightResult.size());
            combined.addAll(leftResult);
            combined.addAll(rightResult);
            return combined;
        }

        private static RetrievedDoc createChunkDoc(RetrievedDoc originalDoc, String chunkText, int index, int total) {
            Map<String, Object> chunkMetadata = new HashMap<>(originalDoc.getMetadata());
            chunkMetadata.put("chunk.strategy", "recursive-character");
            chunkMetadata.put("chunk.index", index);
            chunkMetadata.put("chunk.total", total);
            chunkMetadata.put("chunk.originalId", originalDoc.getId());
            chunkMetadata.put("chunk.size", chunkText.length());

            return RetrievedDoc.builder()
                .id(originalDoc.getId() + "-chunk-" + index)
                .text(chunkText.trim())
                .metadata(chunkMetadata)
                .score(originalDoc.getScore())
                .build();
        }
    }

    private List<RetrievedDoc> createChunkedDocuments(RetrievedDoc originalDoc, List<String> chunks) {
        List<RetrievedDoc> result = new ArrayList<>(chunks.size());
        int total = chunks.size();

        for (int i = 0; i < total; i++) {
            result.add(createChunk(originalDoc, chunks.get(i), i, total));
        }

        return result;
    }

    private List<RetrievedDoc> createSingleChunk(RetrievedDoc originalDoc, int index, int total) {
        return List.of(createChunk(originalDoc, originalDoc.getText(), index, total));
    }

    private RetrievedDoc createChunk(RetrievedDoc originalDoc, String chunkText, int index, int total) {
        Map<String, Object> chunkMetadata = new HashMap<>(originalDoc.getMetadata());
        chunkMetadata.put("chunk.strategy", getName());
        chunkMetadata.put("chunk.index", index);
        chunkMetadata.put("chunk.total", total);
        chunkMetadata.put("chunk.originalId", originalDoc.getId());
        chunkMetadata.put("chunk.size", chunkText.length());

        return RetrievedDoc.builder()
            .id(originalDoc.getId() + "-chunk-" + index)
            .text(chunkText.trim())
            .metadata(chunkMetadata)
            .score(originalDoc.getScore())
            .build();
    }

    @Override
    public String getName() {
        return "recursive-character";
    }

    @Override
    public List<String> getSupportedLanguages() {
        // This chunker works with any language that uses standard punctuation
        return List.of("*");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        // OPTIMIZED: Chunk size ~512 tokens * 4 chars/token = ~2000 chars
        // Most embedding models (BGE, Arctic, etc.) work best with 512-1024 tokens
        defaults.put("chunkSize", 2000);
        // OPTIMIZED: 10% overlap is sufficient for context preservation
        // Reduces memory overhead and indexing time by ~50% vs 20% overlap
        defaults.put("overlap", 200);
        defaults.put("preserveParagraphs", true);
        // Garbage collection options - disabled by default (see TextChunker interface)
        defaults.put(OPTION_COLLECT_GARBAGE, false);
        defaults.put(OPTION_INCLUDE_GARBAGE_CHUNK, true);
        return defaults;
    }
}
