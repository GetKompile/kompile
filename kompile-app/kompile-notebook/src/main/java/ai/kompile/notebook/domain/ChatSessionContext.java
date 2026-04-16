/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.notebook.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-session, per-source context control for chat sessions within a notebook.
 *
 * This is the "ChatSessionContext" entity from the plan.  It stores per-session,
 * per-source visibility settings so users can include or exclude specific sources
 * from a chat session without affecting other sessions or the notebook itself.
 *
 * Each row links one ChatSession (by sessionId) to one NotebookSource (by factId)
 * with a SourceMode that controls how the source is used during retrieval.
 *
 * This extends the existing ChatSession entity ADDITIVELY — the existing chat flow
 * remains unchanged.  The RAG retrieval layer (RagToolImpl / HybridOptimizedRetriever)
 * can consult this table to filter or summarise sources before returning results.
 */
@Entity
@Table(name = "chat_session_source_contexts", indexes = {
    @Index(name = "idx_cssc_session", columnList = "sessionId"),
    @Index(name = "idx_cssc_notebook", columnList = "notebookId"),
    @Index(name = "idx_cssc_fact", columnList = "factId"),
    @Index(name = "idx_cssc_session_fact", columnList = "sessionId,factId", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionContext {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The chat session this context applies to.
     * References ChatSession.sessionId (the UUID string, not the DB pk).
     */
    @Column(nullable = false, length = 255)
    private String sessionId;

    /**
     * The notebook this session is scoped to.
     * Null means global (not scoped to a notebook).
     */
    @Column
    private Long notebookId;

    /**
     * The factId (Fact.id) of the source this entry controls.
     * Matches NotebookSource.factId.
     */
    @Column(nullable = false)
    private Long factId;

    /**
     * Display name of the source (denormalized for quick UI rendering).
     */
    @Column(length = 512)
    private String sourceDisplayName;

    /**
     * How this source should be used in the chat session.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SourceMode mode = SourceMode.FULL;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

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

    /**
     * Controls how a source participates in a chat session.
     */
    public enum SourceMode {
        /**
         * Full source content is included in retrieval — default behaviour.
         * Equivalent to "include this source normally".
         */
        FULL,

        /**
         * Only the source summary/insight is included.
         * When a summary SourceInsight exists for the source it is used;
         * otherwise the first available chunk is used as a proxy.
         * (Phase 3 transformations will improve this.)
         */
        SUMMARY_ONLY,

        /**
         * This source is completely excluded from the session's retrieval.
         * Chunks from this source are filtered out before ranking.
         */
        EXCLUDED
    }
}
