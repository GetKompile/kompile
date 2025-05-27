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
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing and executing different text chunking strategies.
 * 
 * <p>
 * This service acts as a facade for all available text chunkers, allowing
 * for easy strategy selection and configuration. It supports automatic
 * chunker discovery and provides a unified interface for chunking operations.
 * </p>
 * 
 * <p>
 * The service can be configured through application properties to set
 * default chunking strategies and parameters.
 * </p>
 */
@Service
@ConfigurationProperties(prefix = "kompile.chunking")
public class ChunkingService {
    
    private final Map<String, TextChunker> chunkers = new HashMap<>();
    private String defaultStrategy = "recursive-character";
    private Map<String, Object> defaultOptions = new HashMap<>();
    
    @Autowired(required = false)
    private List<TextChunker> availableChunkers = new ArrayList<>();
    
    @PostConstruct
    public void initialize() {
        // Register all available chunkers
        for (TextChunker chunker : availableChunkers) {
            chunkers.put(chunker.getName(), chunker);
        }
        
        // Set default options if not configured
        if (defaultOptions.isEmpty()) {
            defaultOptions.put("chunkSize", 1000);
            defaultOptions.put("overlap", 200);
            defaultOptions.put("preserveParagraphs", true);
        }
    }
    
    /**
     * Chunks a document using the default chunking strategy.
     * 
     * @param document the document to chunk
     * @return a list of chunked documents
     */
    public List<RetrievedDoc> chunk(RetrievedDoc document) {
        return chunk(document, defaultStrategy, null);
    }
    
    /**
     * Chunks a document using the specified strategy.
     * 
     * @param document the document to chunk
     * @param strategy the chunking strategy to use
     * @return a list of chunked documents
     */
    public List<RetrievedDoc> chunk(RetrievedDoc document, String strategy) {
        return chunk(document, strategy, null);
    }
    
    /**
     * Chunks a document using the specified strategy and options.
     * 
     * @param document the document to chunk
     * @param strategy the chunking strategy to use
     * @param options additional options for the chunking process
     * @return a list of chunked documents
     */
    public List<RetrievedDoc> chunk(RetrievedDoc document, String strategy, Map<String, Object> options) {
        TextChunker chunker = getChunker(strategy);
        Map<String, Object> mergedOptions = mergeOptions(options);
        return chunker.chunk(document, mergedOptions);
    }
    
    /**
     * Chunks multiple documents using the default strategy.
     * 
     * @param documents the documents to chunk
     * @return a list of all chunked documents
     */
    public List<RetrievedDoc> chunkBatch(List<RetrievedDoc> documents) {
        return chunkBatch(documents, defaultStrategy, null);
    }
    
    /**
     * Chunks multiple documents using the specified strategy.
     * 
     * @param documents the documents to chunk
     * @param strategy the chunking strategy to use
     * @return a list of all chunked documents
     */
    public List<RetrievedDoc> chunkBatch(List<RetrievedDoc> documents, String strategy) {
        return chunkBatch(documents, strategy, null);
    }
    
    /**
     * Chunks multiple documents using the specified strategy and options.
     * 
     * @param documents the documents to chunk
     * @param strategy the chunking strategy to use
     * @param options additional options for the chunking process
     * @return a list of all chunked documents
     */
    public List<RetrievedDoc> chunkBatch(List<RetrievedDoc> documents, String strategy, Map<String, Object> options) {
        return documents.stream()
            .flatMap(doc -> chunk(doc, strategy, options).stream())
            .collect(Collectors.toList());
    }
    
    /**
     * Gets a chunker by strategy name.
     * 
     * @param strategy the strategy name
     * @return the corresponding TextChunker
     * @throws IllegalArgumentException if the strategy is not found
     */
    public TextChunker getChunker(String strategy) {
        TextChunker chunker = chunkers.get(strategy);
        if (chunker == null) {
            throw new IllegalArgumentException("Unknown chunking strategy: " + strategy + 
                ". Available strategies: " + getAvailableStrategies());
        }
        return chunker;
    }
    
    /**
     * Returns a list of all available chunking strategies.
     * 
     * @return a list of strategy names
     */
    public Set<String> getAvailableStrategies() {
        return chunkers.keySet();
    }
    
    /**
     * Returns information about all available chunkers.
     * 
     * @return a map of strategy names to chunker information
     */
    public Map<String, ChunkerInfo> getChunkerInfo() {
        return chunkers.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new ChunkerInfo(
                    entry.getValue().getName(),
                    entry.getValue().getSupportedLanguages(),
                    entry.getValue().getDefaultOptions()
                )
            ));
    }
    
    /**
     * Finds the best chunker for a given language.
     * 
     * @param language the language code (e.g., "en", "es")
     * @return the name of the best chunker for the language
     */
    public String getBestChunkerForLanguage(String language) {
        // First, look for chunkers that specifically support the language
        for (TextChunker chunker : chunkers.values()) {
            if (chunker.getSupportedLanguages().contains(language)) {
                return chunker.getName();
            }
        }
        
        // Fallback to language-agnostic chunkers
        for (TextChunker chunker : chunkers.values()) {
            if (chunker.getSupportedLanguages().contains("*")) {
                return chunker.getName();
            }
        }
        
        // If no suitable chunker found, return default
        return defaultStrategy;
    }
    
    private Map<String, Object> mergeOptions(Map<String, Object> options) {
        Map<String, Object> merged = new HashMap<>(defaultOptions);
        if (options != null) {
            merged.putAll(options);
        }
        return merged;
    }
    
    // Configuration properties setters
    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }
    
    public void setDefaultOptions(Map<String, Object> defaultOptions) {
        this.defaultOptions = defaultOptions;
    }
    
    public String getDefaultStrategy() {
        return defaultStrategy;
    }
    
    public Map<String, Object> getDefaultOptions() {
        return new HashMap<>(defaultOptions);
    }
    
    /**
     * Information about a text chunker.
     */
    public static class ChunkerInfo {
        private final String name;
        private final List<String> supportedLanguages;
        private final Map<String, Object> defaultOptions;
        
        public ChunkerInfo(String name, List<String> supportedLanguages, Map<String, Object> defaultOptions) {
            this.name = name;
            this.supportedLanguages = new ArrayList<>(supportedLanguages);
            this.defaultOptions = new HashMap<>(defaultOptions);
        }
        
        public String getName() {
            return name;
        }
        
        public List<String> getSupportedLanguages() {
            return new ArrayList<>(supportedLanguages);
        }
        
        public Map<String, Object> getDefaultOptions() {
            return new HashMap<>(defaultOptions);
        }
        
        @Override
        public String toString() {
            return "ChunkerInfo{" +
                "name='" + name + '\'' +
                ", supportedLanguages=" + supportedLanguages +
                ", defaultOptions=" + defaultOptions +
                '}';
        }
    }
}
