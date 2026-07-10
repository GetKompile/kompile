package ai.kompile.graphchangetracking.domain;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphUpdatePipelineConfig {

    private Long id;

    private String pipelineId;

    private String pipelineName;

    @Builder.Default
    private Boolean enabled = true;

    private String triggerChannels;

    private String triggerEventTypes;

    private String filterJson;

    private Long targetFactSheetId;

    private String processingSteps;

    @Builder.Default
    private Boolean requireApproval = false;

    @Builder.Default
    private Integer priority = 0;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void initDefaults() {
        if (pipelineId == null) pipelineId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    public void markUpdated() {
        updatedAt = LocalDateTime.now();
    }
}
