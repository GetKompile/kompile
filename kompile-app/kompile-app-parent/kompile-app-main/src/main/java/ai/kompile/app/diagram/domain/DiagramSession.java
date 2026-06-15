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

package ai.kompile.app.diagram.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persists an agent-driven diagram generation session.
 * Stores the full transcript of the LLM's thinking process, tool calls,
 * and the resulting Mermaid diagram code.
 */
@Entity
@Table(name = "diagram_sessions", indexes = {
    @Index(name = "idx_diagram_session_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_diagram_session_created", columnList = "createdAt"),
    @Index(name = "idx_diagram_session_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagramSession {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /** The fact sheet this diagram was generated from. */
    @Column
    private Long factSheetId;

    /** User-supplied prompt that initiated the diagram generation. */
    @Column(length = 4096)
    private String prompt;

    /** The agent used for generation (e.g., "claude-cli", "gemini-cli"). */
    @Column(length = 128)
    private String agentName;

    /** Status: RUNNING, COMPLETED, FAILED, CANCELLED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "RUNNING";

    /**
     * Full transcript of the agent conversation as JSON array.
     * Each entry: {role, content, timestamp, type (text|tool_use|tool_result|thinking)}.
     */
    @Column(columnDefinition = "TEXT")
    @Lob
    private String transcriptJson;

    /**
     * The generated Mermaid diagram code (final result).
     */
    @Column(columnDefinition = "TEXT")
    @Lob
    private String mermaidCode;

    /** Human-readable title for the diagram (LLM-generated or user-set). */
    @Column(length = 512)
    private String title;

    /** Description of the business process. */
    @Column(columnDefinition = "TEXT")
    @Lob
    private String description;

    /**
     * JSON array of source references that the diagram was derived from.
     * Each entry: {nodeId, title, type, snippet}.
     */
    @Column(columnDefinition = "TEXT")
    @Lob
    private String sourcesJson;

    /**
     * ID of the ProcessDefinition created from this diagram via "Convert to Process".
     * Null until the user explicitly converts the diagram to an executable process.
     */
    @Column(length = 64)
    private String processDefinitionId;

    /** Error message if generation failed. */
    @Column(length = 2048)
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
