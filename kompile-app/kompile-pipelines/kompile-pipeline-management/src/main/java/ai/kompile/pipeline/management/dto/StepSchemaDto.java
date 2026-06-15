package ai.kompile.pipeline.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepSchemaDto {
    private String name;
    private String runnerClassName;
    private String description;
    private List<Map<String, Object>> parameters;
    private List<Map<String, Object>> inputs;
    private List<Map<String, Object>> outputs;
}
