package ai.kompile.rag.pipeline.steps;

import ai.kompile.core.llm.LanguageModel;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Pipeline step that generates an LLM response from query + retrieved documents.
 */
public class RagLlmGenerationStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(RagLlmGenerationStepRunner.class);

    public static final String PARAM_PROVIDER = "provider";
    public static final String PARAM_MODEL = "model";
    public static final String PARAM_SYSTEM_PROMPT = "systemPrompt";
    public static final String PARAM_TEMPERATURE = "temperature";
    public static final String PARAM_MAX_TOKENS = "maxTokens";

    private LanguageModel languageModel;
    private String systemPrompt;
    private boolean initialized;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        this.languageModel = context.get("languageModel", LanguageModel.class).orElse(null);
        if (this.languageModel == null) {
            throw new IllegalStateException("LanguageModel not found in pipeline context.");
        }
        this.systemPrompt = stepConfig.get(PARAM_SYSTEM_PROMPT);
        String provider = stepConfig.get(PARAM_PROVIDER, "OPENAI");
        String model = stepConfig.get(PARAM_MODEL, "gpt-4");
        log.info("Initialized RAG LLM generation step: provider={}, model={}", provider, model);
        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        String query = input.get("query");
        List<String> documents = input.getList("documents", ValueType.STRING);

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Missing required input key 'query'");
        }

        List<String> contextList;
        if (documents != null && !documents.isEmpty()) {
            contextList = documents;
        } else {
            contextList = List.of();
        }

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            context.put("systemPrompt", systemPrompt);
        }

        String response = languageModel.generateResponse(query, contextList);

        String contextStr = String.join("\n\n---\n\n", contextList);

        Data output = Data.empty();
        output.put("query", query);
        if (documents != null) {
            output.putList("documents", documents, ValueType.STRING);
        }
        output.put("response", response);
        output.put("context", contextStr);
        output.put("document_count", (long) contextList.size());

        log.debug("LLM generated response of {} chars from {} context documents",
                response != null ? response.length() : 0, contextList.size());
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
