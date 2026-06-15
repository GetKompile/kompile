package ai.kompile.pipeline.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineExecutionResult {
    private String executionId;
    private String pipelineId;
    private String status;
    private Map<String, Object> outputData;
    private String errorMessage;
    private long durationMs;
}
