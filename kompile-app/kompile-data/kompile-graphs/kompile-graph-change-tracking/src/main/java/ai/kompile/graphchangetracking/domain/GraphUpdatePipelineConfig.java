package ai.kompile.graphchangetracking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "graph_update_pipeline_configs", indexes = {
        @Index(name = "idx_gupc_pipeline_id", columnList = "pipelineId", unique = true),
        @Index(name = "idx_gupc_target_fs", columnList = "targetFactSheetId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphUpdatePipelineConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, unique = true, nullable = false)
    private String pipelineId;

    @Column(length = 255, nullable = false)
    private String pipelineName;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String triggerChannels;

    @Column(columnDefinition = "TEXT")
    private String triggerEventTypes;

    @Column(columnDefinition = "TEXT")
    private String filterJson;

    @Column
    private Long targetFactSheetId;

    @Column(columnDefinition = "TEXT")
    private String processingSteps;

    @Column(nullable = false)
    @Builder.Default
    private Boolean requireApproval = false;

    @Column
    @Builder.Default
    private Integer priority = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (pipelineId == null) {
            pipelineId = java.util.UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
