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
package ai.kompile.orchestrator.model;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single orchestrator instance with its own state machine,
 * running tasks, and configuration context.
 */
@Entity
@Table(name = "orchestrator_instances")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorInstance {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    @Id
    @Column(name = "instance_id", nullable = false, unique = true)
    private String instanceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private OrchestratorStatus status = OrchestratorStatus.CREATED;

    @Column(name = "current_state_id")
    private String currentStateId;

    @Column(name = "previous_state_id")
    private String previousStateId;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Transient
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();

    @Column(name = "working_directory")
    private String workingDirectory;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "tags")
    private String tags;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if the orchestrator is in an active state.
     */
    public boolean isActive() {
        return status == OrchestratorStatus.RUNNING || status == OrchestratorStatus.PAUSED;
    }

    /**
     * Check if the orchestrator has completed (success or failure).
     */
    public boolean isTerminal() {
        return status == OrchestratorStatus.COMPLETED ||
               status == OrchestratorStatus.FAILED ||
               status == OrchestratorStatus.CANCELLED;
    }

    /**
     * Update context with new values.
     * Serializes the updated context to contextJson for persistence.
     */
    public void updateContext(Map<String, Object> updates) {
        if (context == null) {
            context = new HashMap<>();
        }
        context.putAll(updates);
        // Serialize to contextJson for JPA persistence
        try {
            this.contextJson = OBJECT_MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            // Best effort — serialization failure shouldn't block context update
        }
    }

    /**
     * Load context from contextJson if the transient map is empty.
     */
    @PostLoad
    protected void onLoad() {
        if (contextJson != null && !contextJson.isEmpty() && (context == null || context.isEmpty())) {
            try {
                context = OBJECT_MAPPER.readValue(contextJson,
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                context = new HashMap<>();
            }
        }
    }

    /**
     * Get a typed value from context.
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextValue(String key, Class<T> type) {
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Get a value from context with a default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextValue(String key, T defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }
}
