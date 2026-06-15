package ai.kompile.staging.web.dto.openai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiChatCompletionResponse {
    private String id;
    @Builder.Default
    private String object = "chat.completion";
    private long created;
    private String model;
    private List<OpenAiChoice> choices;
    private OpenAiUsage usage;
}
