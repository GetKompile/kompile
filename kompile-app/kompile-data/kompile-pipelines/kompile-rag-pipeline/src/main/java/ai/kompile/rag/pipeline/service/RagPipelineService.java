package ai.kompile.rag.pipeline.service;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.RegistryBasedModelManager;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import ai.kompile.rag.pipeline.domain.*;
import ai.kompile.rag.pipeline.steps.*;
import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class RagPipelineService {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineService.class);

    private final ObjectMapper objectMapper;
    private final Path storageDir;
    private final ConcurrentHashMap<String, RagPipelineDefinition> pipelines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RagPipelineDefinition> builtins = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    @Autowired(required = false)
    private VectorStore vectorStore;
    @Autowired(required = false)
    private DocumentRetriever documentRetriever;
    @Autowired(required = false)
    private RerankerService rerankerService;
    @Autowired(required = false)
    private LanguageModel languageModel;

    public RagPipelineService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.storageDir = KompileHome.dataDir().toPath().resolve("rag-pipelines");
    }

    @PostConstruct
    public void init() {
        // Load builtins
        for (RagPipelineDefinition template : RagPipelineTemplates.allBuiltins()) {
            builtins.put(template.getId(), template);
        }
        log.info("Loaded {} built-in RAG pipeline templates", builtins.size());

        // Load custom pipelines from disk
        loadFromDisk();
    }

    // ===================== CRUD =====================

    public List<RagPipelineDefinition> listAll() {
        List<RagPipelineDefinition> all = new ArrayList<>(builtins.values());
        all.addAll(pipelines.values());
        all.sort(Comparator.comparing(RagPipelineDefinition::getName));
        return all;
    }

    public List<RagPipelineDefinition> getTemplates() {
        return new ArrayList<>(builtins.values());
    }

    public Optional<RagPipelineDefinition> getById(String id) {
        RagPipelineDefinition def = builtins.get(id);
        if (def == null) {
            def = pipelines.get(id);
        }
        return Optional.ofNullable(def);
    }

    public RagPipelineDefinition create(RagPipelineDefinition def) {
        if (def.getId() == null || def.getId().isBlank()) {
            def.setId(UUID.randomUUID().toString());
        }
        if (builtins.containsKey(def.getId())) {
            throw new IllegalArgumentException("Cannot create pipeline with built-in ID: " + def.getId());
        }
        def.setBuiltin(false);
        def.setCreatedAt(Instant.now());
        def.setUpdatedAt(Instant.now());
        pipelines.put(def.getId(), def);
        saveToDisk(def);
        log.info("Created custom RAG pipeline: {} ({})", def.getName(), def.getId());
        return def;
    }

    public RagPipelineDefinition update(String id, RagPipelineDefinition def) {
        if (builtins.containsKey(id)) {
            throw new IllegalArgumentException("Cannot modify built-in pipeline: " + id);
        }
        RagPipelineDefinition existing = pipelines.get(id);
        if (existing == null) {
            throw new NoSuchElementException("Pipeline not found: " + id);
        }
        def.setId(id);
        def.setBuiltin(false);
        def.setCreatedAt(existing.getCreatedAt());
        def.setUpdatedAt(Instant.now());
        pipelines.put(id, def);
        saveToDisk(def);
        log.info("Updated RAG pipeline: {} ({})", def.getName(), id);
        return def;
    }

    public void delete(String id) {
        if (builtins.containsKey(id)) {
            throw new IllegalArgumentException("Cannot delete built-in pipeline: " + id);
        }
        RagPipelineDefinition removed = pipelines.remove(id);
        if (removed != null) {
            deleteFromDisk(id);
            log.info("Deleted RAG pipeline: {} ({})", removed.getName(), id);
        }
    }

    // ===================== Model Status =====================

    public PipelineModelStatus getModelStatus(String pipelineId) {
        RagPipelineDefinition def = getById(pipelineId)
                .orElseThrow(() -> new NoSuchElementException("Pipeline not found: " + pipelineId));

        List<PipelineModelStatus.ModelRequirement> requirements = new ArrayList<>();

        // Check embedding model
        if (def.getEmbedding() != null && !"none".equals(def.getEmbedding().getModelId())) {
            String modelId = def.getEmbedding().getModelId();
            String status = checkModelAvailability(modelId, "encoder");
            requirements.add(new PipelineModelStatus.ModelRequirement(
                    "embedding", modelId, status, def.getEmbedding().getModelSource()));
        }

        // Check cross-encoder model
        if (def.getReranking() != null && def.getReranking().isEnabled()
                && "cross_encoder".equals(def.getReranking().getRerankerType())) {
            String modelId = def.getReranking().getCrossEncoderModel();
            if (modelId != null) {
                String status = checkModelAvailability(modelId, "cross_encoder");
                requirements.add(new PipelineModelStatus.ModelRequirement(
                        "reranking", modelId, status, def.getReranking().getCrossEncoderModelSource()));
            }
        }

        // Check LLM
        if (def.getLlm() != null) {
            String status = def.getLlm().getProvider() == LlmStageConfig.LlmProvider.LOCAL_SAMEDIFF
                    ? checkModelAvailability(def.getLlm().getModel(), "llm")
                    : "ready"; // External LLMs are always "ready" (API-based)
            requirements.add(new PipelineModelStatus.ModelRequirement(
                    "llm", def.getLlm().getModel(), status,
                    def.getLlm().getProvider() != null ? def.getLlm().getProvider().name().toLowerCase() : "default"));
        }

        boolean allReady = requirements.stream().allMatch(r -> "ready".equals(r.getStatus()));
        return new PipelineModelStatus(pipelineId, requirements, allReady);
    }

    private String checkModelAvailability(String modelId, String type) {
        if (modelId == null || modelId.isBlank()) return "missing";

        // Check if it's a known builtin model
        if ("encoder".equals(type) && ModelConstants.getAnseriniEncoderModelDescriptor(modelId) != null) {
            return "ready";
        }
        if ("cross_encoder".equals(type) && ModelConstants.getCrossEncoderModelDescriptor(modelId) != null) {
            return "ready";
        }

        // For external LLMs, always ready
        if ("llm".equals(type)) {
            return "available";
        }

        return "available";
    }

    // ===================== Execution =====================

    public RagPipelineResult execute(String pipelineId, String query) {
        RagPipelineDefinition def = getById(pipelineId)
                .orElseThrow(() -> new NoSuchElementException("Pipeline not found: " + pipelineId));

        long startTime = System.currentTimeMillis();

        try {
            SequencePipeline pipeline = buildSequencePipeline(def);
            DefaultContext context = new DefaultContext(Data.empty());

            // Inject Spring beans into context for step runners
            if (embeddingModel != null) context.put("embeddingModel", embeddingModel);
            if (vectorStore != null) context.put("vectorStore", vectorStore);
            if (documentRetriever != null) context.put("documentRetriever", documentRetriever);
            if (rerankerService != null) context.put("rerankerService", rerankerService);
            if (languageModel != null) context.put("languageModel", languageModel);

            try (PipelineExecutor executor = pipeline.createExecutor()) {
                Data input = Data.empty();
                input.put("query", query);

                Data output = executor.exec(input, context);

                long durationMs = System.currentTimeMillis() - startTime;
                String response = output.has("response") ? output.get("response") : null;
                String contextStr = output.has("context") ? output.get("context") : null;
                long docCount = output.has("document_count") ? output.get("document_count") : 0L;

                return new RagPipelineResult(
                        pipelineId, def.getName(), "completed",
                        response, contextStr, (int) docCount, durationMs, null);
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Pipeline execution failed: {}", e.getMessage(), e);
            return new RagPipelineResult(
                    pipelineId, def.getName(), "error",
                    null, null, 0, durationMs, e.getMessage());
        }
    }

    public SequencePipeline buildSequencePipeline(RagPipelineDefinition def) {
        SequencePipeline.Builder builder = SequencePipeline.builder();

        // Step 1: Embedding
        if (def.getEmbedding() != null) {
            GenericStepConfig embeddingStep = new GenericStepConfig(RagEmbeddingStepRunner.class.getName());
            embeddingStep.put(RagEmbeddingStepRunner.PARAM_EMBEDDING_MODEL_ID,
                    def.getEmbedding().getModelId());
            embeddingStep.put(RagEmbeddingStepRunner.PARAM_EMBEDDING_MODEL_SOURCE,
                    def.getEmbedding().getModelSource());
            if (def.getEmbedding().getArchiveId() != null) {
                embeddingStep.put(RagEmbeddingStepRunner.PARAM_EMBEDDING_ARCHIVE_ID,
                        def.getEmbedding().getArchiveId());
            }
            builder.add(embeddingStep);
        }

        // Step 2: Retrieval
        if (def.getRetrieval() != null) {
            GenericStepConfig retrievalStep = new GenericStepConfig(RagRetrievalStepRunner.class.getName());
            retrievalStep.put(RagRetrievalStepRunner.PARAM_STRATEGY,
                    def.getRetrieval().getStrategy().name());
            retrievalStep.put(RagRetrievalStepRunner.PARAM_TOP_K, def.getRetrieval().getTopK());
            retrievalStep.put(RagRetrievalStepRunner.PARAM_SIMILARITY_THRESHOLD,
                    def.getRetrieval().getSimilarityThreshold());
            builder.add(retrievalStep);
        }

        // Step 3: Reranking
        if (def.getReranking() != null) {
            GenericStepConfig rerankStep = new GenericStepConfig(RagRerankingStepRunner.class.getName());
            rerankStep.put(RagRerankingStepRunner.PARAM_ENABLED, def.getReranking().isEnabled());
            rerankStep.put(RagRerankingStepRunner.PARAM_RERANKER_TYPE,
                    def.getReranking().getRerankerType());
            if (def.getReranking().getCrossEncoderModel() != null) {
                rerankStep.put(RagRerankingStepRunner.PARAM_CROSS_ENCODER_MODEL,
                        def.getReranking().getCrossEncoderModel());
            }
            rerankStep.put(RagRerankingStepRunner.PARAM_TOP_K, def.getReranking().getTopK());
            rerankStep.put(RagRerankingStepRunner.PARAM_MMR_LAMBDA, def.getReranking().getMmrLambda());
            builder.add(rerankStep);
        }

        // Step 4: LLM Generation
        if (def.getLlm() != null) {
            GenericStepConfig llmStep = new GenericStepConfig(RagLlmGenerationStepRunner.class.getName());
            if (def.getLlm().getProvider() != null) {
                llmStep.put(RagLlmGenerationStepRunner.PARAM_PROVIDER, def.getLlm().getProvider().name());
            }
            if (def.getLlm().getModel() != null) {
                llmStep.put(RagLlmGenerationStepRunner.PARAM_MODEL, def.getLlm().getModel());
            }
            if (def.getLlm().getSystemPrompt() != null) {
                llmStep.put(RagLlmGenerationStepRunner.PARAM_SYSTEM_PROMPT, def.getLlm().getSystemPrompt());
            }
            llmStep.put(RagLlmGenerationStepRunner.PARAM_TEMPERATURE, def.getLlm().getTemperature());
            llmStep.put(RagLlmGenerationStepRunner.PARAM_MAX_TOKENS, def.getLlm().getMaxTokens());
            builder.add(llmStep);
        }

        return builder.build();
    }

    // ===================== Persistence =====================

    private void loadFromDisk() {
        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                return;
            }
            try (Stream<Path> files = Files.list(storageDir)) {
                files.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            try {
                                RagPipelineDefinition def = objectMapper.readValue(p.toFile(), RagPipelineDefinition.class);
                                if (def.getId() != null && !def.isBuiltin()) {
                                    pipelines.put(def.getId(), def);
                                }
                            } catch (IOException e) {
                                log.warn("Failed to load pipeline from {}: {}", p.getFileName(), e.getMessage());
                            }
                        });
            }
            log.info("Loaded {} custom RAG pipelines from disk", pipelines.size());
        } catch (IOException e) {
            log.warn("Failed to scan pipeline storage directory: {}", e.getMessage());
        }
    }

    private void saveToDisk(RagPipelineDefinition def) {
        try {
            Files.createDirectories(storageDir);
            Path file = storageDir.resolve(def.getId() + ".json");
            objectMapper.writeValue(file.toFile(), def);
        } catch (IOException e) {
            log.error("Failed to save pipeline to disk: {}", e.getMessage());
        }
    }

    private void deleteFromDisk(String id) {
        try {
            Path file = storageDir.resolve(id + ".json");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete pipeline file: {}", e.getMessage());
        }
    }

    // ===================== DTOs =====================

    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineModelStatus {
        private String pipelineId;
        private List<ModelRequirement> requirements;
        private boolean allModelsReady;

        @lombok.Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ModelRequirement {
            private String stage;
            private String modelId;
            private String status; // "ready", "available", "staging", "missing"
            private String source;
        }
    }

    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagPipelineResult {
        private String pipelineId;
        private String pipelineName;
        private String status; // "completed", "error"
        private String response;
        private String context;
        private int documentCount;
        private long durationMs;
        private String errorMessage;
    }
}
