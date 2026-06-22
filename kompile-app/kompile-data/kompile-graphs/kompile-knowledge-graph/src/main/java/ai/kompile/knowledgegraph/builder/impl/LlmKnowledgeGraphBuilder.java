/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.builder.impl;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import ai.kompile.knowledgegraph.builder.repository.ExtractionJobRepository;
import ai.kompile.knowledgegraph.builder.repository.ExtractionLogRepository;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.confidence.SourceTrustResolver;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * LLM-based knowledge graph builder.
 *
 * <p>Uses a language model to extract entities and relationships from document chunks.
 * Provides full transparency by logging prompts and responses for each extraction.
 *
 * <p>The LLMChat dependency is optional. If no LLM is configured, this builder will
 * be available but will return empty results when invoked.
 */
@Component
@Slf4j
public class LlmKnowledgeGraphBuilder implements KnowledgeGraphBuilder {

    public static final String BUILDER_ID = "llm-builder";

    // Optional dependency - injected via setter
    private LLMChat llmChat;

    private final ObjectMapper objectMapper;
    private final ExtractionJobRepository jobRepository;
    private final ExtractionLogRepository logRepository;

    /**
     * Optional SourceTrustResolver — when available (Spring context), resolves the
     * per-type trust scalar for evidence-based confidence initialisation (Pillar 5, Slice 1).
     * When null (plain-Java test contexts), falls back to a literal 0.60 default.
     */
    @Autowired(required = false)
    private SourceTrustResolver sourceTrustResolver;

    /**
     * Kompile-managed KB config — supplies the Beta prior strength W ({@code evidencePriorStrength})
     * and the LLM-extraction trust default. Null in plain-Java contexts → {@link KbConfig#defaults()}.
     */
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    private BuilderConfig config;

    // Cache extraction logs for retrieval
    private final Map<String, List<ExtractionLogEntry>> extractionLogsCache = new LinkedHashMap<>();

    @Autowired
    public LlmKnowledgeGraphBuilder(
            ObjectMapper objectMapper,
            ExtractionJobRepository jobRepository,
            ExtractionLogRepository logRepository) {
        this.objectMapper = objectMapper;
        this.jobRepository = jobRepository;
        this.logRepository = logRepository;
        this.config = BuilderConfig.defaults();
    }

    /**
     * Optional setter for LLMChat - allows this builder to work without an LLM configured.
     */
    @Autowired(required = false)
    public void setLlmChat(LLMChat llmChat) {
        this.llmChat = llmChat;
        if (llmChat != null) {
            log.info("LlmKnowledgeGraphBuilder: LLMChat configured successfully");
        }
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (llmChat == null) {
            log.warn("LlmKnowledgeGraphBuilder initialized without LLMChat - extraction will not work until an LLM is configured");
        }
    }

    /**
     * Check if this builder is ready to perform extraction.
     * Returns false if no LLM is configured.
     */
    public boolean isReady() {
        return llmChat != null;
    }

    @Override
    public String getId() {
        return BUILDER_ID;
    }

    @Override
    public String getDisplayName() {
        return "LLM Entity Extractor";
    }

    @Override
    public GraphBuilderType getType() {
        return GraphBuilderType.LLM;
    }

    @Override
    public String getDescription() {
        return "Uses a large language model to automatically extract entities and relationships " +
               "from document text. Provides full transparency with logged prompts and responses.";
    }

    @Override
    public void configure(BuilderConfig config) {
        this.config = config != null ? config : BuilderConfig.defaults();
        log.info("Configured LLM builder: provider={}, model={}, temperature={}, entityTypes={}",
                this.config.modelProvider(),
                this.config.modelName(),
                this.config.temperature(),
                this.config.entityTypes());
    }

    @Override
    public BuilderConfig getConfig() {
        return config;
    }

    @Override
    public List<ProposedTriple> buildFromChunks(
            List<RetrievedDoc> chunks,
            GraphBuildContext context,
            Consumer<BuildProgress> progressCallback) {

        if (chunks == null || chunks.isEmpty()) {
            if (progressCallback != null) {
                progressCallback.accept(BuildProgress.completed(context.jobId(), 0, 0));
            }
            return Collections.emptyList();
        }

        // Check if LLM is available
        if (llmChat == null) {
            log.error("Cannot perform LLM extraction - no LLMChat bean is configured. " +
                     "Please configure an LLM provider (OpenAI, Anthropic, etc.)");
            if (progressCallback != null) {
                progressCallback.accept(BuildProgress.completed(context.jobId(), 0, 0));
            }
            return Collections.emptyList();
        }

        List<ProposedTriple> allProposals = new ArrayList<>();
        List<ExtractionLogEntry> logs = new ArrayList<>();
        int processedCount = 0;

        for (RetrievedDoc chunk : chunks) {
            try {
                long startTime = System.currentTimeMillis();

                // Create the extraction prompt
                String prompt = createExtractionPrompt(chunk.getText());

                // Build chat options
                ChatOptions.Builder optionsBuilder = ChatOptions.builder();
                if (config.temperature() != null) {
                    optionsBuilder.temperature(config.temperature());
                }
                if (config.maxTokens() != null) {
                    optionsBuilder.maxTokens(config.maxTokens());
                }
                ChatOptions options = optionsBuilder.build();

                // Call LLM
                String response = llmChat.prompt()
                        .user(prompt)
                        .options(options)
                        .call()
                        .content();

                long latencyMs = System.currentTimeMillis() - startTime;

                // Parse response
                ExtractedGraphDTO.ExtractedGraph extracted = parseResponse(response);
                List<ProposedTriple> chunkProposals = convertToProposals(extracted, chunk, context);

                // Filter by minimum confidence
                if (config.minConfidence() != null && config.minConfidence() > 0) {
                    chunkProposals = chunkProposals.stream()
                            .filter(p -> p.confidence() >= config.minConfidence())
                            .collect(Collectors.toList());
                }

                allProposals.addAll(chunkProposals);

                // Create log entry with FULL prompt and response
                ExtractionLogEntry logEntry = ExtractionLogEntry.success(
                        chunk.getId(),
                        getDocumentId(chunk),
                        prompt,          // FULL prompt
                        response,        // FULL response
                        chunkProposals,
                        config.modelProvider(),
                        config.modelName(),
                        latencyMs,
                        estimateTokens(prompt),
                        estimateTokens(response)
                );
                logs.add(logEntry);

                // Persist log to database
                persistLog(context.jobId(), logEntry, chunk.getText(), extracted);

            } catch (Exception e) {
                log.error("Failed to extract from chunk: {}", chunk.getId(), e);

                // Create failure log
                String prompt = createExtractionPrompt(chunk.getText());
                ExtractionLogEntry failLog = ExtractionLogEntry.failure(
                        chunk.getId(),
                        getDocumentId(chunk),
                        prompt,
                        e.getMessage(),
                        config.modelProvider(),
                        config.modelName(),
                        0
                );
                logs.add(failLog);
                persistFailureLog(context.jobId(), failLog, chunk.getText());
            }

            processedCount++;

            // Report progress
            if (progressCallback != null) {
                progressCallback.accept(BuildProgress.processing(
                        context.jobId(),
                        chunks.size(),
                        processedCount,
                        allProposals.size(),
                        chunk.getId()
                ));
            }
        }

        // Cache logs for retrieval
        extractionLogsCache.put(context.jobId(), logs);

        // Report completion
        if (progressCallback != null) {
            progressCallback.accept(BuildProgress.completed(
                    context.jobId(),
                    chunks.size(),
                    allProposals.size()
            ));
        }

        return allProposals;
    }

    @Override
    public Optional<List<ExtractionLogEntry>> getExtractionLog(String jobId) {
        // First check cache
        List<ExtractionLogEntry> cached = extractionLogsCache.get(jobId);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Load from database
        List<ExtractionLogRecord> records = logRepository.findByJobId(jobId);
        if (records.isEmpty()) {
            return Optional.empty();
        }

        List<ExtractionLogEntry> entries = records.stream()
                .map(this::convertToLogEntry)
                .collect(Collectors.toList());

        return Optional.of(entries);
    }

    @Override
    public boolean supportsExtractionLog() {
        return true;
    }

    @Override
    public boolean supportsConcurrentIndexing() {
        return true;
    }

    // ==================== Private Methods ====================

    private String createExtractionPrompt(String text) {
        // Use custom prompt if configured
        if (config.customPrompt() != null && !config.customPrompt().isEmpty()) {
            return config.customPrompt()
                    .replace("{{TEXT}}", text)
                    .replace("{text}", text);
        }

        // Build entity types list
        String entityTypes = config.entityTypes() != null
                ? String.join(", ", config.entityTypes())
                : "PERSON, ORGANIZATION, LOCATION, CONCEPT";

        return """
                Extract entities and their relationships from the following text.

                Entity types to look for: %s

                %s

                Text to analyze:
                \"\"\"
                %s
                \"\"\"
                """.formatted(entityTypes, GraphExtractionValidator.getExtractionPromptInstructions(), text);
    }

    private ExtractedGraphDTO.ExtractedGraph parseResponse(String response) throws JsonProcessingException {
        // Clean up response if needed
        String cleanResponse = response.trim();

        // Handle markdown code blocks
        if (cleanResponse.startsWith("```json")) {
            cleanResponse = cleanResponse.substring(7);
        } else if (cleanResponse.startsWith("```")) {
            cleanResponse = cleanResponse.substring(3);
        }
        if (cleanResponse.endsWith("```")) {
            cleanResponse = cleanResponse.substring(0, cleanResponse.length() - 3);
        }
        cleanResponse = cleanResponse.trim();

        try {
            return objectMapper.readValue(cleanResponse, ExtractedGraphDTO.ExtractedGraph.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM response as JSON: {}", cleanResponse);
            // Return empty graph on parse failure
            ExtractedGraphDTO.ExtractedGraph empty = new ExtractedGraphDTO.ExtractedGraph();
            empty.setEntities(new ArrayList<>());
            empty.setRelationships(new ArrayList<>());
            return empty;
        }
    }

    private List<ProposedTriple> convertToProposals(
            ExtractedGraphDTO.ExtractedGraph extracted,
            RetrievedDoc chunk,
            GraphBuildContext context) {

        List<ProposedTriple> proposals = new ArrayList<>();

        if (extracted.getRelationships() == null || extracted.getEntities() == null) {
            return proposals;
        }

        // Build entity lookup
        Map<String, ExtractedGraphDTO.ExtractedEntity> entityMap = new HashMap<>();
        for (ExtractedGraphDTO.ExtractedEntity entity : extracted.getEntities()) {
            if (entity.getId() != null) {
                entityMap.put(entity.getId(), entity);
            }
        }

        // Convert relationships to proposals
        for (ExtractedGraphDTO.ExtractedRelationship rel : extracted.getRelationships()) {
            ExtractedGraphDTO.ExtractedEntity sourceEntity = entityMap.get(rel.getSource());
            ExtractedGraphDTO.ExtractedEntity targetEntity = entityMap.get(rel.getTarget());

            if (sourceEntity == null || targetEntity == null) {
                log.debug("Skipping relationship with missing entity: {} -> {}",
                        rel.getSource(), rel.getTarget());
                continue;
            }

            // Resolve source trust for LLM extraction (tier-1 Pillar 5, Slice 1).
            // If SourceTrustResolver is not wired (plain-Java tests), fall back to 0.60 default.
            double sourceTrust = sourceTrustResolver != null
                    ? sourceTrustResolver.trustFor("LLM_EXTRACTION")
                    : kbCfg().trustLlmExtraction;

            // Evidence-based confidence initialisation (Pillar 1, Slice 1):
            // A single LLM extraction with W=2, trust=0.6 → expectation ≈ 0.23 (LOW band).
            // This replaces the hardcoded 0.8 which caused all LLM extractions to start at HIGH.
            // If the LLM returns an explicit confidence score, honour it (it's already calibrated).
            double confidence;
            if (rel.getConfidence() != null) {
                confidence = rel.getConfidence();
            } else {
                double W = kbCfg().evidencePriorStrength;
                confidence = Opinion.fromBetaEvidence(sourceTrust, 0.0, 0.5, W).expectation();
            }

            // Build evidence metadata for Pillar 1/3 (persisted in edge metadataJson)
            double W = kbCfg().evidencePriorStrength;
            Opinion opinion = Opinion.fromBetaEvidence(sourceTrust, 0.0, 0.5, W);
            Map<String, Object> evidenceMeta = new LinkedHashMap<>();
            evidenceMeta.put(GraphProvenanceKeys.OPINION, opinion.toJson());
            evidenceMeta.put(GraphProvenanceKeys.EVIDENCE_POS, sourceTrust);
            evidenceMeta.put(GraphProvenanceKeys.EVIDENCE_NEG, 0.0);
            evidenceMeta.put(GraphProvenanceKeys.PRIOR_STRENGTH, W);
            evidenceMeta.put(GraphProvenanceKeys.SOURCE_TRUST, sourceTrust);
            evidenceMeta.put(GraphProvenanceKeys.BASIS_TYPE, "LLM_EXTRACTION");
            evidenceMeta.put(GraphProvenanceKeys.CORROBORATION_COUNT, 1);
            evidenceMeta.put(GraphProvenanceKeys.VALID_FROM, System.currentTimeMillis());

            Map<String, Object> extraMeta = new LinkedHashMap<>();
            extraMeta.put("sourceDescription", sourceEntity.getDescription() != null ? sourceEntity.getDescription() : "");
            extraMeta.put("targetDescription", targetEntity.getDescription() != null ? targetEntity.getDescription() : "");
            extraMeta.put("relationshipDescription", rel.getDescription() != null ? rel.getDescription() : "");
            extraMeta.putAll(evidenceMeta);

            ProposedTriple proposal = new ProposedTriple(
                    sourceEntity.getTitle(),
                    sourceEntity.getNodeLabel(),
                    rel.getRelationshipType(),
                    targetEntity.getTitle(),
                    targetEntity.getNodeLabel(),
                    confidence,
                    chunk.getId(),
                    getDocumentId(chunk),
                    truncateContext(chunk.getText(), 500),
                    extraMeta
            );

            proposals.add(proposal);
        }

        return proposals;
    }

    private void persistLog(String jobId, ExtractionLogEntry entry, String inputText, ExtractedGraphDTO.ExtractedGraph extracted) {
        try {
            ExtractionJob job = jobRepository.findByJobId(jobId).orElse(null);
            if (job == null) {
                log.warn("Job not found for log persistence: {}", jobId);
                return;
            }

            String entitiesJson = null;
            String relationshipsJson = null;
            int entityCount = 0;
            int relCount = 0;

            if (extracted != null) {
                if (extracted.getEntities() != null) {
                    entitiesJson = objectMapper.writeValueAsString(extracted.getEntities());
                    entityCount = extracted.getEntities().size();
                }
                if (extracted.getRelationships() != null) {
                    relationshipsJson = objectMapper.writeValueAsString(extracted.getRelationships());
                    relCount = extracted.getRelationships().size();
                }
            }

            ExtractionLogRecord record = ExtractionLogRecord.success(
                    job,
                    entry.chunkId(),
                    entry.documentId(),
                    inputText,
                    entry.prompt(),
                    entry.response(),
                    entitiesJson,
                    relationshipsJson,
                    entityCount,
                    relCount,
                    entry.modelProvider(),
                    entry.modelName(),
                    entry.latencyMs(),
                    entry.promptTokens(),
                    entry.responseTokens()
            );

            logRepository.save(record);
        } catch (Exception e) {
            log.error("Failed to persist extraction log", e);
        }
    }

    private void persistFailureLog(String jobId, ExtractionLogEntry entry, String inputText) {
        try {
            ExtractionJob job = jobRepository.findByJobId(jobId).orElse(null);
            if (job == null) {
                log.warn("Job not found for log persistence: {}", jobId);
                return;
            }

            ExtractionLogRecord record = ExtractionLogRecord.failure(
                    job,
                    entry.chunkId(),
                    entry.documentId(),
                    inputText,
                    entry.prompt(),
                    entry.errorMessage(),
                    entry.modelProvider(),
                    entry.modelName(),
                    entry.latencyMs()
            );

            logRepository.save(record);
        } catch (Exception e) {
            log.error("Failed to persist failure log", e);
        }
    }

    private ExtractionLogEntry convertToLogEntry(ExtractionLogRecord record) {
        List<ProposedTriple> proposals = new ArrayList<>();

        // Parse stored entities and relationships to recreate proposals
        try {
            if (record.getParsedEntitiesJson() != null && record.getParsedRelationshipsJson() != null) {
                ExtractedGraphDTO.ExtractedGraph extracted = new ExtractedGraphDTO.ExtractedGraph();
                extracted.setEntities(objectMapper.readValue(record.getParsedEntitiesJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExtractedGraphDTO.ExtractedEntity.class)));
                extracted.setRelationships(objectMapper.readValue(record.getParsedRelationshipsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExtractedGraphDTO.ExtractedRelationship.class)));

                // Simplified conversion for log display — use the same evidence-based
                // confidence as the live extraction path (no hardcoded 0.8 fallback).
                double displaySourceTrust = sourceTrustResolver != null
                        ? sourceTrustResolver.trustFor("LLM_EXTRACTION") : kbCfg().trustLlmExtraction;
                double displayW = kbCfg().evidencePriorStrength;
                double displayDefaultConf = Opinion.fromBetaEvidence(
                        displaySourceTrust, 0.0, 0.5, displayW).expectation();
                for (ExtractedGraphDTO.ExtractedRelationship rel : extracted.getRelationships()) {
                    proposals.add(ProposedTriple.of(
                            rel.getSource(),
                            null,
                            rel.getRelationshipType(),
                            rel.getTarget(),
                            null,
                            rel.getConfidence() != null ? rel.getConfidence() : displayDefaultConf
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse stored proposals for log entry", e);
        }

        return new ExtractionLogEntry(
                record.getChunkId(),
                record.getDocumentId(),
                record.getPromptText(),
                record.getResponseText(),
                proposals,
                record.getModelProvider(),
                record.getModelName(),
                record.getLatencyMs() != null ? record.getLatencyMs() : 0,
                record.getPromptTokens(),
                record.getResponseTokens(),
                record.getCreatedAt() != null
                        ? record.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant()
                        : Instant.now(),
                record.getSuccess(),
                record.getErrorMessage()
        );
    }

    private String getDocumentId(RetrievedDoc chunk) {
        if (chunk.getMetadata() != null && chunk.getMetadata().get("documentId") != null) {
            return chunk.getMetadata().get("documentId").toString();
        }
        return chunk.getId();
    }

    private String truncateContext(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private Integer estimateTokens(String text) {
        if (text == null) return 0;
        // Rough estimate: ~4 characters per token
        return text.length() / 4;
    }
}
