package ai.kompile.rag.pipeline.steps;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pipeline step that retrieves documents using the configured retrieval strategy.
 * <p>
 * Input: {@code "query"} (String) + optional {@code "query_embedded"} (boolean)<br>
 * Context: optional {@code "query_embedding"} (INDArray) from embedding step<br>
 * Output: {@code "query"} (pass-through) + {@code "documents"} (List of doc content strings) +
 *         {@code "document_count"} (int)
 */
public class RagRetrievalStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalStepRunner.class);

    public static final String PARAM_STRATEGY = "strategy";
    public static final String PARAM_TOP_K = "topK";
    public static final String PARAM_SIMILARITY_THRESHOLD = "similarityThreshold";

    private VectorStore vectorStore;
    private DocumentRetriever documentRetriever;
    private String strategy;
    private int topK;
    private double similarityThreshold;
    private boolean initialized;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        this.strategy = stepConfig.get(PARAM_STRATEGY, "HYBRID");
        this.topK = stepConfig.get(PARAM_TOP_K, 10);
        this.similarityThreshold = stepConfig.get(PARAM_SIMILARITY_THRESHOLD, 0.0);

        this.vectorStore = context.get("vectorStore", VectorStore.class).orElse(null);
        this.documentRetriever = context.get("documentRetriever", DocumentRetriever.class).orElse(null);

        boolean needsSemantic = "SEMANTIC".equals(strategy) || "HYBRID".equals(strategy);
        boolean needsKeyword = "KEYWORD".equals(strategy) || "HYBRID".equals(strategy);

        if (needsSemantic && vectorStore == null) {
            throw new IllegalStateException("VectorStore required for " + strategy + " retrieval but not found in context");
        }
        if (needsKeyword && documentRetriever == null) {
            throw new IllegalStateException("DocumentRetriever required for " + strategy + " retrieval but not found in context");
        }

        log.info("Initialized RAG retrieval step: strategy={}, topK={}, threshold={}", strategy, topK, similarityThreshold);
        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        String query = input.get("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Missing required input key 'query'");
        }

        List<String> documents = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        boolean needsSemantic = "SEMANTIC".equals(strategy) || "HYBRID".equals(strategy);
        boolean needsKeyword = "KEYWORD".equals(strategy) || "HYBRID".equals(strategy);

        // Semantic retrieval
        if (needsSemantic && vectorStore != null) {
            try {
                // Try using pre-computed embedding from context
                INDArray queryEmbedding = context.get("query_embedding", INDArray.class).orElse(null);
                List<org.springframework.ai.document.Document> semanticResults =
                        vectorStore.similaritySearch(query, topK, similarityThreshold);
                for (org.springframework.ai.document.Document doc : semanticResults) {
                    String content = doc.getText();
                    if (content != null && seen.add(content)) {
                        documents.add(content);
                    }
                }
                log.debug("Semantic retrieval returned {} results", semanticResults.size());
            } catch (Exception e) {
                log.warn("Semantic retrieval failed, continuing with keyword: {}", e.getMessage());
            }
        }

        // Keyword retrieval
        if (needsKeyword && documentRetriever != null) {
            try {
                List<RetrievedDoc> keywordResults = documentRetriever.retrieveWithDetails(query, topK);
                for (RetrievedDoc doc : keywordResults) {
                    String content = doc.getText();
                    if (content != null && seen.add(content)) {
                        documents.add(content);
                    }
                }
                log.debug("Keyword retrieval returned {} results", keywordResults.size());
            } catch (Exception e) {
                log.warn("Keyword retrieval failed: {}", e.getMessage());
            }
        }

        // Limit to topK total
        if (documents.size() > topK) {
            documents = documents.subList(0, topK);
        }

        Data output = Data.empty();
        output.put("query", query);
        output.putList("documents", documents, ai.kompile.pipelines.framework.api.data.ValueType.STRING);
        output.put("document_count", (long) documents.size());

        log.debug("Retrieval step produced {} documents for query", documents.size());
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
