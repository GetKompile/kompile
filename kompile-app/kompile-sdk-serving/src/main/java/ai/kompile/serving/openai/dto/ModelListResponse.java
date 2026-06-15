package ai.kompile.serving.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelListResponse {

    private String object;
    private List<ModelObject> data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ModelObject {
        private String id;
        private String object;
        private long created;

        @JsonProperty("owned_by")
        private String ownedBy;
    }
}
