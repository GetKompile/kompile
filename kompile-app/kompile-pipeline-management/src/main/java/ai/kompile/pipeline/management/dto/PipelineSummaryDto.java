package ai.kompile.pipeline.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineSummaryDto {
    private String pipelineId;
    private String pipelineType;
    private int stepCount;
    private List<String> stepTypes;
    private Instant createdAt;
    private Instant updatedAt;
    private boolean serving;
}
