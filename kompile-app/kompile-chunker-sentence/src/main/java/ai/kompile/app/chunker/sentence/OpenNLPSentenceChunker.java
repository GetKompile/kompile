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

package ai.kompile.app.chunker.sentence;

import ai.kompile.app.core.chunking.SentenceFilter;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import lombok.extern.slf4j.Slf4j;
import opennlp.tools.sentdetect.SentenceDetectorFactory;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Component("openNLPSentenceChunker")
@ImportRuntimeHints(OpenNLPSentenceChunker.Hints.class)
public class OpenNLPSentenceChunker implements TextChunker {

    private static final String CHUNKER_NAME = "opennlp_sentence";
    public static final String OPTION_LANGUAGE = "language";
    private static final String DEFAULT_LANGUAGE = "en";

    private final Map<String, SentenceDetectorME> sentenceDetectors = new ConcurrentHashMap<>();
    private final List<String> availableLanguages = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final KompileModelManager modelManager;

    public OpenNLPSentenceChunker() {
        this.modelManager = new KompileModelManager();
        
        // Discover available languages from ModelConstants
        discoverAvailableLanguages();
        
        // Attempt to load a default model if available, e.g., English
        if (availableLanguages.contains(DEFAULT_LANGUAGE)) {
            try {
                loadModel(DEFAULT_LANGUAGE);
            } catch (IOException e) {
                log.warn("Failed to load default OpenNLP sentence model for language '{}': {}", DEFAULT_LANGUAGE, e.getMessage());
            }
        } else if (!availableLanguages.isEmpty()) {
            log.info("Default language model '{}' not found. Available models: {}", DEFAULT_LANGUAGE, availableLanguages);
        } else {
            log.warn("No OpenNLP sentence models available. Models will be downloaded on-demand when requested.");
        }
    }

    static class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
         for(Class clazz : new Class[] {
                 SentenceDetectorFactory.class,
                 opennlp.tools.sentdetect.SentenceModel.class,
                 opennlp.tools.util.model.BaseModel.class
         }) {
             hints.reflection().registerType(clazz,
                     MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                     MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
                     MemberCategory.DECLARED_CLASSES,MemberCategory.DECLARED_FIELDS,
                     MemberCategory.PUBLIC_FIELDS,MemberCategory.PUBLIC_CLASSES);
         }
        }
    }

    private void discoverAvailableLanguages() {
        // Use ModelConstants to discover supported languages
        availableLanguages.addAll(ModelConstants.getSupportedOpenNLPLanguages());
        log.info("Discovered supported OpenNLP sentence languages: {}", availableLanguages);
    }

    private SentenceDetectorME loadModel(String languageCode) throws IOException {
        if (sentenceDetectors.containsKey(languageCode)) {
            return sentenceDetectors.get(languageCode);
        }

        // Check if language is supported
        if (!ModelConstants.isOpenNLPLanguageSupported(languageCode)) {
            throw new IOException("Unsupported language code for OpenNLP sentence detection: " + languageCode);
        }

        // Create model descriptor for this language
        ModelDescriptor descriptor = ModelConstants.createOpenNLPSentenceModelDescriptor(languageCode);

        // Ensure model is available through model manager
        Path modelPath = modelManager.ensureModelAvailable(descriptor);
        
        if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
            throw new IOException("Model file not found at expected path after download: " + modelPath);
        }

        log.info("Loading OpenNLP sentence model for language '{}' from: {}", languageCode, modelPath.toAbsolutePath());

        InputStream modelIn = null;
        try {
            modelIn = Files.newInputStream(modelPath);
            SentenceModel model = new SentenceModel(modelIn);
            SentenceDetectorME detector = new SentenceDetectorME(model);
            sentenceDetectors.put(languageCode, detector);
            
            // Ensure language is in available list
            if (!availableLanguages.contains(languageCode)) {
                availableLanguages.add(languageCode);
            }
            
            log.info("Successfully loaded OpenNLP sentence model for language: {}", languageCode);
            return detector;
        } finally {
            if (modelIn != null) {
                try {
                    modelIn.close();
                } catch (IOException e) {
                    log.error("Failed to close model input stream", e);
                }
            }
        }
    }

    // Segment size for processing large documents - process in ~50KB chunks for better performance
    private static final int SEGMENT_SIZE = 50_000;
    // Overlap to avoid cutting sentences at segment boundaries
    private static final int SEGMENT_OVERLAP = 500;

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        return chunk(document, options, null);
    }

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options,
                                     Consumer<ChunkingProgress> progressCallback) {
        // Validate document using the interface method
        validateDocument(document);

        // Prepare options with defaults
        Map<String, Object> mergedOptions = prepareOptions(options);
        boolean collectGarbage = (Boolean) mergedOptions.getOrDefault(OPTION_COLLECT_GARBAGE, true);
        boolean includeGarbageChunk = (Boolean) mergedOptions.getOrDefault(OPTION_INCLUDE_GARBAGE_CHUNK, true);

        String text = document.getText();
        String language = (String) mergedOptions.getOrDefault(OPTION_LANGUAGE, DEFAULT_LANGUAGE);
        int totalChars = text.length();

        // Report initial progress
        reportProgress(progressCallback, "initializing", 0, 0, 0, totalChars,
                "Loading OpenNLP model for " + language);

        SentenceDetectorME detector;
        try {
            detector = sentenceDetectors.get(language);
            if(detector == null) { // Attempt to load if not already loaded
                detector = loadModel(language);
            }
        } catch (IOException e) {
            log.error("Failed to load OpenNLP sentence model for language '{}'. Defaulting to English if available, or failing.", language, e);
            if (!language.equals(DEFAULT_LANGUAGE) && sentenceDetectors.containsKey(DEFAULT_LANGUAGE)) {
                log.warn("Falling back to default English model.");
                detector = sentenceDetectors.get(DEFAULT_LANGUAGE);
            } else {
                log.error("No suitable OpenNLP model available for language '{}' or default '{}'. Chunking will fail.", language, DEFAULT_LANGUAGE);
                throw new RuntimeException("Failed to initialize OpenNLPSentenceChunker for language: " + language, e);
            }
        }

        if (detector == null) {
            log.error("OpenNLP SentenceDetector is null for language '{}' even after attempting load/fallback. Cannot proceed.", language);
            throw new RuntimeException("Sentence detector could not be initialized for language: " + language);
        }

        reportProgress(progressCallback, "processing", 5, 0, 0, totalChars,
                "Model loaded, starting sentence detection");

        List<RetrievedDoc> chunks = new ArrayList<>();
        int chunkNumber = 0;

        // For small documents, process directly (faster)
        if (text.length() <= SEGMENT_SIZE) {
            reportProgress(progressCallback, "detecting", 10, 0, 0, totalChars,
                    "Detecting sentences in document (" + totalChars + " chars)");

            String[] sentences;
            try {
                sentences = detector.sentDetect(text);
            } catch (IndexOutOfBoundsException e) {
                // OpenNLP 2.5.4 has a bug where certain text can cause IndexOutOfBoundsException
                // in sentPosDetect. Fall back to treating the entire text as one sentence.
                log.warn("OpenNLP sentDetect failed with IndexOutOfBoundsException, " +
                        "falling back to simple sentence splitting: {}", e.getMessage());
                sentences = simpleSentenceSplit(text);
            }

            reportProgress(progressCallback, "creating_chunks", 80, 0, totalChars, totalChars,
                    "Found " + sentences.length + " sentences, creating chunks");

            for (String sentence : sentences) {
                if (!sentence.isBlank()) {
                    chunks.add(createChunk(document, sentence.trim(), chunkNumber++, language));
                }
            }

            reportProgress(progressCallback, "complete", 100, chunks.size(), totalChars, totalChars,
                    "Created " + chunks.size() + " chunks");
        } else {
            // For large documents, process in segments to improve performance and allow interruption
            log.info("Processing large document ({} chars) in segments for better performance", text.length());
            long startTime = System.currentTimeMillis();

            int processedChars = 0;
            String lastSentenceFragment = "";
            int segmentCount = 0;
            int totalSegments = (int) Math.ceil((double) text.length() / SEGMENT_SIZE);

            reportProgress(progressCallback, "segmenting", 10, 0, 0, totalChars,
                    String.format("Processing %d chars in %d segments", totalChars, totalSegments));

            while (processedChars < text.length()) {
                // Check for thread interruption to allow cancellation
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Chunking interrupted after processing {} chars", processedChars);
                    break;
                }

                segmentCount++;
                int segmentEnd = Math.min(processedChars + SEGMENT_SIZE, text.length());

                // Extend to include overlap to avoid cutting sentences
                int extendedEnd = Math.min(segmentEnd + SEGMENT_OVERLAP, text.length());
                String segment = lastSentenceFragment + text.substring(processedChars, extendedEnd);

                String[] sentences;
                try {
                    sentences = detector.sentDetect(segment);
                } catch (IndexOutOfBoundsException e) {
                    // OpenNLP 2.5.4 has a bug where certain text can cause IndexOutOfBoundsException
                    // in sentPosDetect. Fall back to simple sentence splitting for this segment.
                    log.warn("OpenNLP sentDetect failed with IndexOutOfBoundsException in segment {}, " +
                            "falling back to simple sentence splitting: {}", segmentCount, e.getMessage());
                    sentences = simpleSentenceSplit(segment);
                }

                // Process all sentences except potentially the last incomplete one
                int sentencesToProcess = (extendedEnd < text.length()) ? sentences.length - 1 : sentences.length;

                for (int i = 0; i < sentencesToProcess && i < sentences.length; i++) {
                    String sentence = sentences[i];
                    if (!sentence.isBlank()) {
                        chunks.add(createChunk(document, sentence.trim(), chunkNumber++, language));
                    }
                }

                // Keep the last sentence fragment for the next segment (may be incomplete)
                if (sentences.length > 0 && extendedEnd < text.length()) {
                    lastSentenceFragment = sentences[sentences.length - 1];
                } else {
                    lastSentenceFragment = "";
                }

                processedChars = segmentEnd;

                // Calculate progress (10-95% range for segmented processing)
                int progressPercent = 10 + (int) ((processedChars / (double) totalChars) * 85);
                long elapsed = System.currentTimeMillis() - startTime;
                double charsPerSec = elapsed > 0 ? (processedChars * 1000.0 / elapsed) : 0;

                // Report progress on every segment
                reportProgress(progressCallback, "detecting", progressPercent, chunks.size(), processedChars, totalChars,
                        String.format("Segment %d/%d: %d sentences (%.0f chars/sec)",
                                segmentCount, totalSegments, chunks.size(), charsPerSec));

                // Log progress for large documents
                if (segmentCount % 10 == 0 || processedChars >= text.length()) {
                    double progress = (double) processedChars / text.length() * 100;
                    log.debug("OpenNLP chunking progress: {}/{} segments ({}%), {} sentences found, {}ms elapsed",
                            segmentCount, totalSegments, String.format("%.1f", progress), chunks.size(), elapsed);
                }
            }

            // Process any remaining fragment
            if (!lastSentenceFragment.isBlank() && !Thread.currentThread().isInterrupted()) {
                chunks.add(createChunk(document, lastSentenceFragment.trim(), chunkNumber++, language));
            }

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("Completed OpenNLP chunking of {} chars in {}ms ({} sentences, {} chars/ms)",
                    text.length(), totalTime, chunks.size(), String.format("%.1f", (double) text.length() / totalTime));

            reportProgress(progressCallback, "complete", 100, chunks.size(), totalChars, totalChars,
                    String.format("Created %d sentences in %dms", chunks.size(), totalTime));
        }

        log.debug("Split document {} into {} chunks using OpenNLP for language {}.", document.getId(), chunks.size(), language);

        // Apply sentence filtering and garbage collection if enabled
        if (collectGarbage) {
            return SentenceFilter.filterAndCollectGarbage(chunks, document, getName(), includeGarbageChunk);
        }

        return chunks;
    }

    /**
     * Simple fallback sentence splitter when OpenNLP fails.
     * Splits on common sentence-ending punctuation followed by whitespace.
     */
    private String[] simpleSentenceSplit(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        // Split on sentence-ending punctuation followed by whitespace or end of string
        // This regex handles: period, question mark, exclamation followed by whitespace
        return text.split("(?<=[.!?])\\s+");
    }

    /**
     * Helper to report progress if callback is provided.
     */
    private void reportProgress(Consumer<ChunkingProgress> callback, String phase, int percent,
                                 int chunks, int processed, int total, String message) {
        if (callback != null) {
            callback.accept(ChunkingProgress.of(phase, percent, chunks, processed, total, message));
        }
    }

    /**
     * Creates a chunk RetrievedDoc with proper metadata.
     */
    private RetrievedDoc createChunk(RetrievedDoc document, String sentenceText, int chunkNumber, String language) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("original_document_id", document.getId());
        metadata.put("chunk_number", chunkNumber);
        metadata.put("chunker", getName());
        metadata.put("language", language);
        metadata.put("chunk_type", "sentence");

        if (document.getScore() != null) {
            return new RetrievedDoc(UUID.randomUUID().toString(), sentenceText, metadata, document.getScore());
        } else {
            return new RetrievedDoc(UUID.randomUUID().toString(), sentenceText, metadata);
        }
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        return Collections.unmodifiableList(new ArrayList<>(availableLanguages));
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(OPTION_LANGUAGE, DEFAULT_LANGUAGE);
        defaults.put("preserveParagraphs", false); // Sentence chunking doesn't preserve paragraphs by nature
        defaults.put("chunkSize", Integer.MAX_VALUE); // Sentence chunking doesn't use character limits
        defaults.put("overlap", 0); // Sentence chunking doesn't overlap by default
        // Garbage collection options - disabled by default (see TextChunker interface)
        defaults.put(OPTION_COLLECT_GARBAGE, false);
        defaults.put(OPTION_INCLUDE_GARBAGE_CHUNK, true);
        return defaults;
    }

    @Override
    public void validateDocument(RetrievedDoc document) {
        // Use the default validation from the interface
        TextChunker.super.validateDocument(document);
        
        // Add any OpenNLP-specific validation if needed
        String text = document.getText();
        if (text.length() > 1_000_000) { // 1MB text limit as example
            log.warn("Document text is very large ({} characters). This may impact performance.", text.length());
        }
    }

    @Override
    public Map<String, Object> prepareOptions(Map<String, Object> options) {
        // Use the default preparation from the interface
        Map<String, Object> mergedOptions = TextChunker.super.prepareOptions(options);
        
        // Validate OpenNLP-specific options
        String language = (String) mergedOptions.get(OPTION_LANGUAGE);
        if (language != null && !ModelConstants.isOpenNLPLanguageSupported(language)) {
            log.warn("Requested language '{}' is not supported by OpenNLP. Falling back to default '{}'.", 
                    language, DEFAULT_LANGUAGE);
            mergedOptions.put(OPTION_LANGUAGE, DEFAULT_LANGUAGE);
        }
        
        return mergedOptions;
    }
}
