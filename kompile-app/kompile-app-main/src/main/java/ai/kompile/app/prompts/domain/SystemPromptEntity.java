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
package ai.kompile.app.prompts.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity for system prompts with versioning support.
 * Each prompt is scoped to a fact sheet, with one active prompt per fact sheet.
 */
@Entity
@Table(name = "system_prompts", indexes = {
    @Index(name = "idx_system_prompt_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_system_prompt_active", columnList = "is_active"),
    @Index(name = "idx_system_prompt_version", columnList = "fact_sheet_id, version"),
    @Index(name = "idx_system_prompt_parent", columnList = "parent_version_id"),
    @Index(name = "idx_system_prompt_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemPromptEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * Human-readable name for this prompt.
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Description of the prompt's purpose.
     */
    @Column(length = 1000)
    private String description;

    /**
     * The actual prompt content/template.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * The fact sheet this prompt is associated with.
     */
    @Column(name = "fact_sheet_id", nullable = false)
    private Long factSheetId;

    /**
     * Version number (auto-incremented per fact sheet).
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * The ID of the parent version (for version chain tracking).
     */
    @Column(name = "parent_version_id", length = 36)
    private String parentVersionId;

    /**
     * Whether this is the active prompt for the fact sheet.
     * Only one prompt per fact sheet should be active.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * JSON array of variable definitions used in the prompt.
     * Format: [{"name": "user_name", "type": "string", "required": true, "defaultValue": "User"}]
     */
    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    /**
     * JSON array of tags for categorization.
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    /**
     * Notes about changes in this version.
     */
    @Column(name = "change_notes", length = 2000)
    private String changeNotes;

    /**
     * Who created this prompt version.
     */
    @Column(name = "created_by", length = 255)
    private String createdBy;

    /**
     * When this prompt was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When this prompt was last updated.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
