package ai.kompile.pipeline.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePipelineRequest {
    private String pipelineId;
    @Builder.Default
    private String pipelineType = "sequence";
    private List<StepConfigRequest> steps;
}
