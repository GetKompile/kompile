package ai.kompile.rag.pipeline.steps;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.reranking.RerankerType;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pipeline step that reranks retrieved documents using the configured reranker.
 */
public class RagRerankingStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(RagRerankingStepRunner.class);

    public static final String PARAM_RERANKER_TYPE = "rerankerType";
    public static final String PARAM_CROSS_ENCODER_MODEL = "crossEncoderModel";
    public static final String PARAM_ENABLED = "enabled";
    public static final String PARAM_TOP_K = "topK";
    public static final String PARAM_MMR_LAMBDA = "mmrLambda";

    private RerankerService rerankerService;
    private boolean enabled;
    private RerankerConfig rerankerConfig;
    private boolean initialized;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        this.enabled = stepConfig.get(PARAM_ENABLED, false);

        if (enabled) {
            this.rerankerService = context.get("rerankerService", RerankerService.class).orElse(null);
            if (this.rerankerService == null) {
                log.warn("RerankerService not found in context, disabling reranking");
                this.enabled = false;
            } else {
                String typeStr = stepConfig.get(PARAM_RERANKER_TYPE, "none");
                this.rerankerConfig = buildRerankerConfig(stepConfig, typeStr);
                log.info("Initialized RAG reranking step: type={}, topK={}", typeStr, rerankerConfig.getTopK());
            }
        } else {
            log.info("Reranking step disabled, will pass through");
        }
        this.initialized = true;
    }

    private RerankerConfig buildRerankerConfig(StepConfig stepConfig, String typeStr) {
        RerankerType type;
        try {
            type = RerankerType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = switch (typeStr.toLowerCase()) {
                case "cross_encoder" -> RerankerType.CROSS_ENCODER;
                case "bm25prf", "bm25_prf" -> RerankerType.BM25_PRF;
                default -> {
                    log.warn("Unknown reranker type '{}', defaulting to NONE", typeStr);
                    yield RerankerType.NONE;
                }
            };
        }

        RerankerConfig config = new RerankerConfig();
        config.setType(type);
        config.setEnabled(true);

        int topK = stepConfig.get(PARAM_TOP_K, -1);
        if (topK > 0) {
            config.setTopK(topK);
        }

        if (type == RerankerType.MMR) {
            double mmrLambda = stepConfig.get(PARAM_MMR_LAMBDA, 0.5);
            config.setLambda((float) mmrLambda);
        }

        return config;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        String query = input.get("query");
        List<String> documents = input.getList("documents", ValueType.STRING);

        if (!enabled || documents == null || documents.isEmpty()) {
            Data output = Data.empty();
            output.put("query", query);
            if (documents != null) {
                output.putList("documents", documents, ValueType.STRING);
                output.put("document_count", (long) documents.size());
            } else {
                output.putList("documents", List.of(), ValueType.STRING);
                output.put("document_count", 0L);
            }
            return output;
        }

        // Convert strings to ScoredDocuments for the reranker
        List<ScoredDocument> scoredDocs = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = new Document(documents.get(i));
            doc.getMetadata().put("original_rank", i);
            scoredDocs.add(new ScoredDocument(doc, 1.0 - (i * 0.01)));
        }

        List<ScoredDocument> reranked = rerankerService.rerank(scoredDocs, query, rerankerConfig);

        List<String> rerankedTexts = reranked.stream()
                .map(sd -> sd.document().getText())
                .collect(Collectors.toList());

        log.debug("Reranked {} documents -> {} results", documents.size(), rerankedTexts.size());

        Data output = Data.empty();
        output.put("query", query);
        output.putList("documents", rerankedTexts, ValueType.STRING);
        output.put("document_count", (long) rerankedTexts.size());
        return output;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // Lifecycle managed by Spring
    }
}
