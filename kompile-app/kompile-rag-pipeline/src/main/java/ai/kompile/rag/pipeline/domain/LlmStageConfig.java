package ai.kompile.rag.pipeline.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmStageConfig {

    public enum LlmProvider {
        OPENAI, ANTHROPIC, GEMINI, LOCAL_SAMEDIFF
    }

    @Builder.Default
    private LlmProvider provider = LlmProvider.OPENAI;
    /** Model name, e.g. "gpt-4", "claude-3-5-sonnet", "smollm-135m-instruct" */
    private String model;
    /** System prompt prepended to context */
    private String systemPrompt;
    @Builder.Default
    private double temperature = 0.7;
    @Builder.Default
    private int maxTokens = 1024;
}
