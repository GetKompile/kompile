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

package ai.kompile.app.web.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Request DTO for agent chat with optional RAG augmentation.
 */
@Getter
@Setter
public class AgentChatRequest {

    private String message;
    private String agentName;
    private boolean skipPermissions = true;
    private String workingDirectory;

    // RAG configuration (vector-based retrieval)
    private boolean enableRag = false;
    private int ragMaxResults = 5;
    private double ragSimilarityThreshold = 0.0;
    private boolean includeKeywordSearch = true;
    private boolean includeSemanticSearch = true;

    // GraphRAG configuration (knowledge graph-based retrieval)
    private boolean enableGraphRag = false;
    private int graphRagMaxResults = 5;
    private String graphRagSearchType = "LOCAL"; // LOCAL or GLOBAL
    private String graphRagConversationId; // For conversation context

    // MCP tools injection - automatically adds --mcp-server flag if agent supports it
    private boolean injectMcpTools = true;

    // Chat history for context
    private boolean includeHistory = true;
    private List<ChatHistoryEntry> chatHistory;
    private int maxHistoryMessages = 20;

    // Folder context - inject file paths from folder into prompt
    private String folderId;

    // Extra CLI arguments to pass through to the underlying agent command
    private List<String> agentArgs;

    // Timeout configuration (in seconds, 0 = no timeout)
    private int timeoutSeconds = 300; // Default 5 minutes

    /**
     * Chat history entry for conversation context.
     */
    @Getter
    @Setter
    public static class ChatHistoryEntry {
        private String role;
        private String content;

        public ChatHistoryEntry() {}

        public ChatHistoryEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * File or image attachment for multimodal chat requests.
     *
     * @param filename     original file name (e.g., "photo.png")
     * @param mimeType     MIME type (e.g., "image/png", "text/plain")
     * @param base64Data   base-64-encoded binary content, or {@code null} for text files
     * @param textContent  plain-text file content, or {@code null} for binary files
     * @param isImage      {@code true} if the attachment should be treated as an image
     */
    public record MessageAttachment(
            String filename,
            String mimeType,
            String base64Data,
            String textContent,
            boolean isImage
    ) {}

    // ── attachments ──────────────────────────────────────────────────────────

    private List<MessageAttachment> attachments;
}
