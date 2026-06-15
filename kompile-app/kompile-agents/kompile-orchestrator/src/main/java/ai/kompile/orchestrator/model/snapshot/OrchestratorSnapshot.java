/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.model.snapshot;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Snapshot of orchestrator state for recovery purposes.
 */
@Entity
@Table(name = "orchestrator_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "orchestrator_instance_id", nullable = false)
    private String orchestratorInstanceId;

    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    @Column(name = "current_state_id", nullable = false)
    private String currentStateId;

    @Column(name = "previous_state_id")
    private String previousStateId;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "running_task_ids")
    private String runningTaskIds;

    @Column(name = "active_workflow_id")
    private Long activeWorkflowId;

    @Column(name = "active_llm_session_id")
    private Long activeLlmSessionId;

    @Column(name = "is_active")
    @Builder.Default
    private boolean active = true;

    @Column(name = "orchestrator_status")
    private String orchestratorStatus;

    @Column(name = "recovery_notes", columnDefinition = "TEXT")
    private String recoveryNotes;

    @Version
    @Column(name = "version")
    private Integer version;

    @PrePersist
    protected void onCreate() {
        if (snapshotTime == null) {
            snapshotTime = LocalDateTime.now();
        }
    }

    /**
     * Get running task IDs as an array.
     */
    public Long[] getRunningTaskIdArray() {
        if (runningTaskIds == null || runningTaskIds.isEmpty()) {
            return new Long[0];
        }
        String[] parts = runningTaskIds.split(",");
        Long[] ids = new Long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ids[i] = Long.parseLong(parts[i].trim());
        }
        return ids;
    }

    /**
     * Set running task IDs from an array.
     */
    public void setRunningTaskIdArray(Long[] ids) {
        if (ids == null || ids.length == 0) {
            this.runningTaskIds = null;
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(ids[i]);
        }
        this.runningTaskIds = sb.toString();
    }
}
