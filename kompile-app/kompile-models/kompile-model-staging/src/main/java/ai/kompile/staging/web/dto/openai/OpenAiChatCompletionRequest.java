package ai.kompile.staging.web.dto.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiChatCompletionRequest {
    private String model;
    private List<OpenAiMessage> messages;
    @Builder.Default
    private boolean stream = false;
    @Builder.Default
    private double temperature = 1.0;
    @JsonProperty("max_tokens")
    @Builder.Default
    private int maxTokens = 256;
    @JsonProperty("top_p")
    @Builder.Default
    private double topP = 1.0;
    private List<String> stop;
}
