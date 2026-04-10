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

package ai.kompile.chat.history.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a chat session.
 * A session contains multiple chat messages and represents a conversation thread.
 */
@Entity
@Table(name = "chat_sessions", indexes = {
    @Index(name = "idx_chat_session_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_chat_session_user", columnList = "userId"),
    @Index(name = "idx_chat_session_updated", columnList = "updatedAt"),
    @Index(name = "idx_chat_session_source", columnList = "source")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sessionId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    @Column
    private String userId;

    /**
     * The fact sheet this chat session is scoped to.
     * Chats are isolated per fact sheet.
     */
    @Column
    private Long factSheetId;

    @Column
    private String metadata;

    /**
     * Source of the conversation: "app", "kompile", "claude-code", "opencode", "codex", "qwen".
     * Null or "app" means created in the web UI.
     */
    @Column
    private String source;

    /**
     * Optional folder this session is associated with.
     * Sessions in a folder have access to the folder's files for context.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private ChatFolder folder;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        message.setSession(this);
    }

    public void removeMessage(ChatMessage message) {
        messages.remove(message);
        message.setSession(null);
    }
}
