/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.codeindexer.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A code project that can be managed as a fact sheet.
 * Links a code indexing project to fact sheet semantics with summary stats,
 * indexing configuration, and UI metadata.
 */
@Entity
@Table(name = "code_projects",
       uniqueConstraints = @UniqueConstraint(columnNames = "projectId"),
       indexes = {
           @Index(name = "idx_code_project_id", columnList = "projectId"),
           @Index(name = "idx_code_project_active", columnList = "isActive")
       })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CodeProject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Unique project identifier (matches CodebaseIndexer projectId) */
    @Column(nullable = false, unique = true)
    private String projectId;

    /** Display name for the project */
    @Column(nullable = false)
    private String name;

    /** Description of the project */
    @Column(length = 2000)
    private String description;

    /** Whether this project is currently active for chat/retrieval */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /** Color for UI display (hex) */
    @Column(length = 7)
    @Builder.Default
    private String color = "#4caf50";

    /** Material icon name */
    @Column(length = 50)
    @Builder.Default
    private String icon = "code";

    /** Associated fact sheet ID (if linked to document RAG) */
    private Long factSheetId;

    // ── Stats (updated after each index run) ─────────────────────

    private int totalFiles;
    private int totalEntities;
    private int totalRelations;
    private int totalErrors;

    /** Languages detected (comma-separated) */
    @Column(length = 2000)
    private String languages;

    // ── Index state ─────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectIndexState indexState = ProjectIndexState.NOT_INDEXED;

    private Instant lastIndexedAt;
    private Instant createdAt;
    private Instant updatedAt;

    /** Auto-index on file changes (via watcher) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoIndex = false;

    /** Include patterns for indexing (comma-separated globs) */
    @Column(length = 2000)
    private String includePatterns;

    /** Exclude patterns for indexing (comma-separated globs) */
    @Column(length = 2000)
    private String excludePatterns;

    /** Tags for categorization */
    @Column(length = 1000)
    private String tags;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public enum ProjectIndexState {
        NOT_INDEXED, INDEXING, INDEXED, FAILED, STALE
    }
}
