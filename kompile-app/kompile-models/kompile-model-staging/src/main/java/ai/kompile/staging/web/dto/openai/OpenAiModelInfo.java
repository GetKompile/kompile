package ai.kompile.staging.web.dto.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiModelInfo {
    private String id;
    @Builder.Default
    private String object = "model";
    private long created;
    @JsonProperty("owned_by")
    @Builder.Default
    private String ownedBy = "kompile-local";
}
