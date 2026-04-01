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
public class StepConfigRequest {
    private String runnerClassName;
    private Map<String, Object> parameters;
}
