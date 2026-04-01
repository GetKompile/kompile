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
public class OpenAiModelsResponse {
    @Builder.Default
    private String object = "list";
    private List<OpenAiModelInfo> data;
}
