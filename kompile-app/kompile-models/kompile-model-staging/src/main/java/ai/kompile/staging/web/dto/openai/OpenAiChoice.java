package ai.kompile.staging.web.dto.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiChoice {
    private int index;
    private OpenAiMessage message;
    private OpenAiMessage delta;
    @JsonProperty("finish_reason")
    private String finishReason;
}
