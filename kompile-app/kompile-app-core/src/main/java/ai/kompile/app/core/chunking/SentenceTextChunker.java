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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sentence-based text chunker that splits documents at sentence boundaries.
 * 
 * <p>
 * This chunker attempts to preserve sentence integrity while respecting chunk size limits.
 * It uses sophisticated sentence detection patterns that handle common abbreviations
 * and edge cases in sentence boundary detection.
 * </p>
 * 
 * <p>
 * The chunker is particularly useful for documents where semantic coherence at the
 * sentence level is important, such as academic papers, articles, or books.
 * </p>
 */
@Component("sentenceTextChunker")
public class SentenceTextChunker implements TextChunker {
    
    // Pattern to detect sentence boundaries while handling common abbreviations
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
        "(?<![A-Z][a-z]\\.)(?<![A-Z]\\.)(?<=\\.|\\!|\\?)\\s+(?=[A-Z])"
    );
    
    // Common abbreviations that shouldn't trigger sentence breaks
    private static final Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList(
        "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "Sr.", "Jr.", "vs.", "etc.", "i.e.", "e.g.",
        "U.S.", "U.K.", "U.N.", "Ph.D.", "M.D.", "B.A.", "M.A.", "CEO", "CTO", "CFO"
    ));
    
    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);
        
        Map<String, Object> mergedOptions = prepareOptions(options);
        int chunkSize = (Integer) mergedOptions.get("chunkSize");
        int overlap = (Integer) mergedOptions.get("overlap");
        boolean preserveParagraphs = (Boolean) mergedOptions.getOrDefault("preserveParagraphs", true);
        
        String text = document.getText();
        if (text.length() <= chunkSize) {
            return createSingleChunk(document, 0, 1);
        }
        
        List<String> sentences = extractSentences(text, preserveParagraphs);
        List<String> chunks = createChunksFromSentences(sentences, chunkSize, overlap);
        return createChunkedDocuments(document, chunks);
    }
    
    private List<String> extractSentences(String text, boolean preserveParagraphs) {
        List<String> sentences = new ArrayList<>();
        
        if (preserveParagraphs) {
            // Split by paragraphs first, then sentences
            String[] paragraphs = text.split("\n\n+");
            for (String paragraph : paragraphs) {
                if (!paragraph.trim().isEmpty()) {
                    List<String> paragraphSentences = splitIntoSentences(paragraph.trim());
                    sentences.addAll(paragraphSentences);
                    // Add paragraph marker if not the last paragraph
                    if (!paragraph.equals(paragraphs[paragraphs.length - 1])) {
                        sentences.add("\n\n");
                    }
                }
            }
        } else {
            sentences = splitIntoSentences(text);
        }
        
        return sentences;
    }
    
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        
        Matcher matcher = SENTENCE_PATTERN.matcher(text);
        int lastEnd = 0;
        
        while (matcher.find()) {
            int start = matcher.start();
            String sentence = text.substring(lastEnd, start).trim();
            
            if (!sentence.isEmpty() && !isAbbreviation(sentence)) {
                sentences.add(sentence);
            }
            
            lastEnd = start;
        }
        
        // Add the last sentence
        if (lastEnd < text.length()) {
            String lastSentence = text.substring(lastEnd).trim();
            if (!lastSentence.isEmpty()) {
                sentences.add(lastSentence);
            }
        }
        
        return sentences;
    }
    
    private boolean isAbbreviation(String text) {
        String trimmed = text.trim();
        return ABBREVIATIONS.contains(trimmed) || 
               (trimmed.length() <= 4 && trimmed.endsWith("."));
    }
    
    private List<String> createChunksFromSentences(List<String> sentences, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        List<String> currentSentences = new ArrayList<>();
        
        for (String sentence : sentences) {
            // Handle paragraph markers
            if (sentence.equals("\n\n")) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(sentence);
                }
                continue;
            }
            
            String potentialChunk = currentChunk.length() == 0 
                ? sentence 
                : currentChunk.toString() + " " + sentence;
            
            if (potentialChunk.length() <= chunkSize) {
                currentChunk = new StringBuilder(potentialChunk);
                currentSentences.add(sentence);
            } else {
                // Current chunk is full, save it and start a new one
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    
                    // Create overlap for next chunk
                    List<String> overlapSentences = getOverlapSentences(currentSentences, overlap);
                    currentChunk = new StringBuilder();
                    currentSentences = new ArrayList<>();
                    
                    for (String overlapSentence : overlapSentences) {
                        if (currentChunk.length() == 0) {
                            currentChunk.append(overlapSentence);
                        } else {
                            currentChunk.append(" ").append(overlapSentence);
                        }
                        currentSentences.add(overlapSentence);
                    }
                    
                    // Add current sentence
                    String withCurrent = currentChunk.length() == 0 
                        ? sentence 
                        : currentChunk.toString() + " " + sentence;
                    
                    if (withCurrent.length() <= chunkSize) {
                        currentChunk = new StringBuilder(withCurrent);
                        currentSentences.add(sentence);
                    } else {
                        // Sentence is too long even with minimal overlap, add it separately
                        if (currentChunk.length() > 0) {
                            chunks.add(currentChunk.toString().trim());
                        }
                        
                        // Handle oversized sentence
                        if (sentence.length() > chunkSize) {
                            chunks.addAll(splitLongSentence(sentence, chunkSize, overlap));
                            currentChunk = new StringBuilder();
                            currentSentences = new ArrayList<>();
                        } else {
                            currentChunk = new StringBuilder(sentence);
                            currentSentences = Arrays.asList(sentence);
                        }
                    }
                }
            }
        }
        
        // Add the last chunk if it's not empty
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        
        return chunks;
    }
    
    private List<String> getOverlapSentences(List<String> sentences, int overlapChars) {
        List<String> overlap = new ArrayList<>();
        int totalChars = 0;
        
        // Add sentences from the end until we reach the overlap size
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            if (totalChars + sentence.length() <= overlapChars) {
                overlap.add(0, sentence);
                totalChars += sentence.length();
            } else {
                break;
            }
        }
        
        return overlap;
    }
    
    private List<String> splitLongSentence(String sentence, int chunkSize, int overlap) {
        // Fallback to character-based splitting for very long sentences
        List<String> parts = new ArrayList<>();
        int start = 0;
        
        while (start < sentence.length()) {
            int end = Math.min(start + chunkSize, sentence.length());
            parts.add(sentence.substring(start, end));
            start = end - overlap;
            if (start >= end) break;
        }
        
        return parts;
    }
    
    private List<RetrievedDoc> createChunkedDocuments(RetrievedDoc originalDoc, List<String> chunks) {
        List<RetrievedDoc> result = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            result.add(createChunk(originalDoc, chunks.get(i), i, chunks.size()));
        }
        
        return result;
    }
    
    private List<RetrievedDoc> createSingleChunk(RetrievedDoc originalDoc, int index, int total) {
        return Arrays.asList(createChunk(originalDoc, originalDoc.getText(), index, total));
    }
    
    private RetrievedDoc createChunk(RetrievedDoc originalDoc, String chunkText, int index, int total) {
        Map<String, Object> chunkMetadata = new HashMap<>(originalDoc.getMetadata());
        chunkMetadata.put("chunk.strategy", getName());
        chunkMetadata.put("chunk.index", index);
        chunkMetadata.put("chunk.total", total);
        chunkMetadata.put("chunk.originalId", originalDoc.getId());
        chunkMetadata.put("chunk.size", chunkText.length());
        chunkMetadata.put("chunk.sentenceLevel", true);
        
        return RetrievedDoc.builder()
            .id(originalDoc.getId() + "-chunk-" + index)
            .text(chunkText)
            .metadata(chunkMetadata)
            .score(originalDoc.getScore())
            .build();
    }

    @Override
    public String getName() {
        return "sentence";
    }

    @Override
    public List<String> getSupportedLanguages() {
        // This chunker works well with languages that use Western punctuation
        return Arrays.asList("en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da");
    }
    
    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("chunkSize", 800);
        defaults.put("overlap", 100);
        defaults.put("preserveParagraphs", true);
        return defaults;
    }
}
