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

package ai.kompile.app.services;

import ai.kompile.app.config.ContextualRagConfig;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceAttributionHelper;
import ai.kompile.core.source.SourceMetadataConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service for enriching document chunks with contextual information using LLMs.
 *
 * <p>This service implements the Contextual Retrieval approach where each chunk
 * is augmented with a contextual description explaining where it fits within
 * the source document. This significantly improves retrieval quality.</p>
 *
 * <p><b>Example transformation:</b></p>
 * <pre>
 * Original chunk: "Revenue increased by 23% reaching $4.2M in September."
 *
 * Contextualized: "This chunk from the 2024 Q3 Financial Report presents key
 * performance metrics for September. It shows that the company achieved
 * significant growth with a 23% revenue increase to $4.2 million."
 * </pre>
 *
 * <p>Reference: <a href="https://github.com/Abhay-404/Eternal-Contextual-RAG">Eternal-Contextual-RAG</a></p>
 */
@Service
public class ContextualChunkEnricher {

    private static final Logger log = LoggerFactory.getLogger(ContextualChunkEnricher.class);
    private static final String CONTEXT_PREFIX_KEY = "contextual_prefix";
    private static final String DOCUMENT_SUMMARY_KEY = "document_summary";
    private static final String CONTEXTUALIZED_KEY = "contextualized";

    private final ContextualRagConfigService configService;
    private final ChatModel chatModel;
    private final ExecutorService executorService;

    // Cache for document summaries (document hash -> summary)
    private final Map<String, String> documentSummaryCache = new ConcurrentHashMap<>();

    // Cache for contextualized chunks (chunk hash -> context)
    private final Map<String, String> chunkContextCache = new ConcurrentHashMap<>();

    @Autowired
    public ContextualChunkEnricher(
            ContextualRagConfigService configService,
            @Autowired(required = false) ChatModel chatModel) {
        this.configService = configService;
        this.chatModel = chatModel;
        this.executorService = Executors.newFixedThreadPool(10,
                r -> { Thread t = new Thread(r, "chunk-enricher"); t.setDaemon(true); return t; });
        log.info("ContextualChunkEnricher initialized. ChatModel available: {}", chatModel != null);
    }

    /**
     * Enriches a list of chunks with contextual information.
     *
     * @param chunks       The chunks to enrich
     * @param documentText The full document text (for generating summaries)
     * @param documentTitle The document title/filename
     * @return Enriched chunks with contextual prefixes
     */
    public List<RetrievedDoc> enrichChunks(List<RetrievedDoc> chunks, String documentText, String documentTitle) {
        ContextualRagConfig config = configService.getConfiguration();

        if (!Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("Contextual enrichment disabled, returning original chunks");
            return addSourceAttribution(chunks, documentTitle);
        }

        if (chatModel == null) {
            log.warn("ChatModel not available, cannot perform contextual enrichment");
            return addSourceAttribution(chunks, documentTitle);
        }

        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        log.info("Enriching {} chunks for document: {}", chunks.size(), documentTitle);

        try {
            // Generate document summary if enabled
            String documentSummary = null;
            if (Boolean.TRUE.equals(config.getIncludeDocumentSummary()) && documentText != null) {
                documentSummary = getOrGenerateDocumentSummary(documentText, documentTitle, config);
            }

            // Enrich chunks in batches
            return enrichChunksInBatches(chunks, documentText, documentTitle, documentSummary, config);

        } catch (Exception e) {
            log.error("Error during chunk enrichment: {}", e.getMessage(), e);
            if (Boolean.TRUE.equals(config.getFallbackOnError())) {
                log.info("Falling back to original chunks due to error");
                return addSourceAttribution(chunks, documentTitle);
            }
            throw new RuntimeException("Chunk enrichment failed", e);
        }
    }

    /**
     * Enriches a single chunk with contextual information.
     *
     * @param chunk         The chunk to enrich
     * @param documentText  The full document text
     * @param documentTitle The document title
     * @param chunkIndex    Index of this chunk
     * @param totalChunks   Total number of chunks
     * @return Enriched chunk
     */
    public RetrievedDoc enrichSingleChunk(RetrievedDoc chunk, String documentText,
                                           String documentTitle, int chunkIndex, int totalChunks) {
        ContextualRagConfig config = configService.getConfiguration();

        if (!Boolean.TRUE.equals(config.getEnabled()) || chatModel == null) {
            return addSourceAttributionToChunk(chunk, documentTitle, chunkIndex, totalChunks);
        }

        try {
            String documentSummary = null;
            if (Boolean.TRUE.equals(config.getIncludeDocumentSummary()) && documentText != null) {
                documentSummary = getOrGenerateDocumentSummary(documentText, documentTitle, config);
            }

            String contextPrefix = generateContextPrefix(chunk, documentSummary, documentTitle,
                    chunkIndex, totalChunks, config);

            return createEnrichedChunk(chunk, contextPrefix, documentSummary, chunkIndex, totalChunks);

        } catch (Exception e) {
            log.error("Error enriching chunk {}", chunkIndex, e);
            if (Boolean.TRUE.equals(config.getFallbackOnError())) {
                return addSourceAttributionToChunk(chunk, documentTitle, chunkIndex, totalChunks);
            }
            throw new RuntimeException("Failed to enrich chunk", e);
        }
    }

    /**
     * Generates a summary of the document for providing context.
     */
    private String getOrGenerateDocumentSummary(String documentText, String documentTitle,
                                                 ContextualRagConfig config) {
        String docHash = hashString(documentText);

        // Check cache first
        if (Boolean.TRUE.equals(config.getCachingEnabled())) {
            String cached = documentSummaryCache.get(docHash);
            if (cached != null) {
                log.debug("Using cached document summary for: {}", documentTitle);
                return cached;
            }
        }

        log.debug("Generating document summary for: {}", documentTitle);

        String summaryPrompt = String.format("""
            Provide a concise summary of the following document titled "%s".
            Focus on the main topics, key entities, and overall purpose.
            Keep the summary under %d words.

            Document:
            %s
            """,
            documentTitle,
            config.getDocumentSummaryMaxTokens() != null ? config.getDocumentSummaryMaxTokens() / 2 : 250,
            truncateText(documentText, 8000) // Limit context window usage
        );

        try {
            ChatClient client = ChatClient.create(chatModel);
            String summary = client.prompt()
                    .user(summaryPrompt)
                    .call()
                    .content();

            if (Boolean.TRUE.equals(config.getCachingEnabled())) {
                documentSummaryCache.put(docHash, summary);
            }

            return summary;

        } catch (Exception e) {
            log.warn("Failed to generate document summary", e);
            return "Document: " + documentTitle;
        }
    }

    /**
     * Enriches chunks in batches for efficiency.
     */
    private List<RetrievedDoc> enrichChunksInBatches(List<RetrievedDoc> chunks, String documentText,
                                                      String documentTitle, String documentSummary,
                                                      ContextualRagConfig config) {
        int batchSize = config.getBatchSize() != null ? config.getBatchSize() : 10;
        int maxConcurrent = config.getMaxConcurrentRequests() != null ? config.getMaxConcurrentRequests() : 5;
        int totalChunks = chunks.size();

        List<RetrievedDoc> enrichedChunks = new ArrayList<>(totalChunks);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Process in batches
        List<List<RetrievedDoc>> batches = partitionList(chunks, batchSize);
        Semaphore semaphore = new Semaphore(maxConcurrent);

        List<CompletableFuture<List<RetrievedDoc>>> futures = new ArrayList<>();

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<RetrievedDoc> batch = batches.get(batchIdx);
            int startIndex = batchIdx * batchSize;

            CompletableFuture<List<RetrievedDoc>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    List<RetrievedDoc> enrichedBatch = new ArrayList<>();

                    for (int i = 0; i < batch.size(); i++) {
                        RetrievedDoc chunk = batch.get(i);
                        int chunkIndex = startIndex + i;

                        try {
                            String contextPrefix = generateContextPrefix(chunk, documentSummary,
                                    documentTitle, chunkIndex, totalChunks, config);

                            RetrievedDoc enriched = createEnrichedChunk(chunk, contextPrefix,
                                    documentSummary, chunkIndex, totalChunks);
                            enrichedBatch.add(enriched);

                        } catch (Exception e) {
                            log.warn("Failed to enrich chunk {}, using original", chunkIndex, e);
                            enrichedBatch.add(addSourceAttributionToChunk(chunk, documentTitle,
                                    chunkIndex, totalChunks));
                        }

                        int count = processedCount.incrementAndGet();
                        if (count % 50 == 0 || count == totalChunks) {
                            log.info("Enriched {}/{} chunks for: {}", count, totalChunks, documentTitle);
                        }
                    }

                    return enrichedBatch;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Batch processing interrupted", e);
                } finally {
                    semaphore.release();
                }
            }, executorService);

            futures.add(future);
        }

        // Collect all results
        for (CompletableFuture<List<RetrievedDoc>> future : futures) {
            try {
                int timeout = config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 30;
                enrichedChunks.addAll(future.get(timeout * 2L, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.error("Error waiting for batch enrichment", e);
                if (!Boolean.TRUE.equals(config.getFallbackOnError())) {
                    throw new RuntimeException("Batch enrichment failed", e);
                }
            }
        }

        log.info("Completed enrichment of {} chunks for: {}", enrichedChunks.size(), documentTitle);
        return enrichedChunks;
    }

    /**
     * Generates the contextual prefix for a chunk using LLM.
     */
    private String generateContextPrefix(RetrievedDoc chunk, String documentSummary,
                                          String documentTitle, int chunkIndex, int totalChunks,
                                          ContextualRagConfig config) {
        String chunkText = chunk.getText();
        if (chunkText == null || chunkText.isBlank()) {
            return "";
        }

        // Check cache
        String cacheKey = hashString(chunkText + documentTitle + chunkIndex);
        if (Boolean.TRUE.equals(config.getCachingEnabled())) {
            String cached = chunkContextCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // Build the prompt
        String promptTemplate = configService.getPromptTemplate();
        String prompt = promptTemplate
                .replace("{document_title}", documentTitle != null ? documentTitle : "Unknown Document")
                .replace("{document_summary}", documentSummary != null ? documentSummary : "No summary available")
                .replace("{chunk_text}", truncateText(chunkText, 2000))
                .replace("{chunk_index}", String.valueOf(chunkIndex + 1))
                .replace("{total_chunks}", String.valueOf(totalChunks));

        try {
            ChatClient client = ChatClient.create(chatModel);
            String context = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Cache the result
            if (Boolean.TRUE.equals(config.getCachingEnabled()) && context != null) {
                chunkContextCache.put(cacheKey, context);
            }

            return context != null ? context.trim() : "";

        } catch (Exception e) {
            log.warn("Failed to generate context for chunk {}", chunkIndex, e);
            return "";
        }
    }

    /**
     * Creates an enriched chunk with contextual information in metadata.
     */
    private RetrievedDoc createEnrichedChunk(RetrievedDoc original, String contextPrefix,
                                              String documentSummary, int chunkIndex, int totalChunks) {
        Map<String, Object> enrichedMetadata = new HashMap<>(original.getMetadata() != null ?
                original.getMetadata() : new HashMap<>());

        // Add contextual information to metadata
        if (contextPrefix != null && !contextPrefix.isBlank()) {
            enrichedMetadata.put(CONTEXT_PREFIX_KEY, contextPrefix);
            enrichedMetadata.put(CONTEXTUALIZED_KEY, true);
        }

        if (documentSummary != null) {
            enrichedMetadata.put(DOCUMENT_SUMMARY_KEY, documentSummary);
        }

        // Add chunk position info
        enrichedMetadata.put(SourceMetadataConstants.CHUNK_INDEX, chunkIndex);
        enrichedMetadata.put(SourceMetadataConstants.TOTAL_CHUNKS, totalChunks);

        // Create enriched text: context prefix + original text
        String enrichedText = original.getText();
        if (contextPrefix != null && !contextPrefix.isBlank()) {
            enrichedText = contextPrefix + "\n\n" + original.getText();
        }

        return RetrievedDoc.builder()
                .id(original.getId())
                .text(enrichedText)
                .metadata(enrichedMetadata)
                .score(original.getScore())
                .build();
    }

    /**
     * Adds source attribution metadata without LLM enrichment.
     */
    private List<RetrievedDoc> addSourceAttribution(List<RetrievedDoc> chunks, String documentTitle) {
        List<RetrievedDoc> result = new ArrayList<>();
        int totalChunks = chunks.size();

        for (int i = 0; i < chunks.size(); i++) {
            result.add(addSourceAttributionToChunk(chunks.get(i), documentTitle, i, totalChunks));
        }

        return result;
    }

    /**
     * Adds source attribution to a single chunk.
     */
    private RetrievedDoc addSourceAttributionToChunk(RetrievedDoc chunk, String documentTitle,
                                                      int chunkIndex, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata() != null ?
                chunk.getMetadata() : new HashMap<>());

        metadata.put(SourceMetadataConstants.CHUNK_INDEX, chunkIndex);
        metadata.put(SourceMetadataConstants.TOTAL_CHUNKS, totalChunks);
        metadata.put(CONTEXTUALIZED_KEY, false);

        if (documentTitle != null && !metadata.containsKey(SourceMetadataConstants.SOURCE_FILENAME)) {
            metadata.put(SourceMetadataConstants.SOURCE_FILENAME, documentTitle);
        }

        return RetrievedDoc.builder()
                .id(chunk.getId())
                .text(chunk.getText())
                .metadata(metadata)
                .score(chunk.getScore())
                .build();
    }

    /**
     * Clears the context caches.
     */
    public void clearCaches() {
        documentSummaryCache.clear();
        chunkContextCache.clear();
        log.info("Cleared contextual enrichment caches");
    }

    /**
     * Gets cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
                "documentSummaryCount", documentSummaryCache.size(),
                "chunkContextCount", chunkContextCache.size()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String hashString(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // Use first 16 chars
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
