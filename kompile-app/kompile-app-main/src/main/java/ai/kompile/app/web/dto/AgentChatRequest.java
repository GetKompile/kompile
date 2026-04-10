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

import java.util.List;

/**
 * Request DTO for agent chat with optional RAG augmentation.
 */
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

    // Getters and Setters
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public boolean isSkipPermissions() {
        return skipPermissions;
    }

    public void setSkipPermissions(boolean skipPermissions) {
        this.skipPermissions = skipPermissions;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public boolean isEnableRag() {
        return enableRag;
    }

    public void setEnableRag(boolean enableRag) {
        this.enableRag = enableRag;
    }

    public int getRagMaxResults() {
        return ragMaxResults;
    }

    public void setRagMaxResults(int ragMaxResults) {
        this.ragMaxResults = ragMaxResults;
    }

    public double getRagSimilarityThreshold() {
        return ragSimilarityThreshold;
    }

    public void setRagSimilarityThreshold(double ragSimilarityThreshold) {
        this.ragSimilarityThreshold = ragSimilarityThreshold;
    }

    public boolean isIncludeKeywordSearch() {
        return includeKeywordSearch;
    }

    public void setIncludeKeywordSearch(boolean includeKeywordSearch) {
        this.includeKeywordSearch = includeKeywordSearch;
    }

    public boolean isIncludeSemanticSearch() {
        return includeSemanticSearch;
    }

    public void setIncludeSemanticSearch(boolean includeSemanticSearch) {
        this.includeSemanticSearch = includeSemanticSearch;
    }

    public boolean isEnableGraphRag() {
        return enableGraphRag;
    }

    public void setEnableGraphRag(boolean enableGraphRag) {
        this.enableGraphRag = enableGraphRag;
    }

    public int getGraphRagMaxResults() {
        return graphRagMaxResults;
    }

    public void setGraphRagMaxResults(int graphRagMaxResults) {
        this.graphRagMaxResults = graphRagMaxResults;
    }

    public String getGraphRagSearchType() {
        return graphRagSearchType;
    }

    public void setGraphRagSearchType(String graphRagSearchType) {
        this.graphRagSearchType = graphRagSearchType;
    }

    public String getGraphRagConversationId() {
        return graphRagConversationId;
    }

    public void setGraphRagConversationId(String graphRagConversationId) {
        this.graphRagConversationId = graphRagConversationId;
    }

    public boolean isInjectMcpTools() {
        return injectMcpTools;
    }

    public void setInjectMcpTools(boolean injectMcpTools) {
        this.injectMcpTools = injectMcpTools;
    }

    public boolean isIncludeHistory() {
        return includeHistory;
    }

    public void setIncludeHistory(boolean includeHistory) {
        this.includeHistory = includeHistory;
    }

    public List<ChatHistoryEntry> getChatHistory() {
        return chatHistory;
    }

    public void setChatHistory(List<ChatHistoryEntry> chatHistory) {
        this.chatHistory = chatHistory;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public List<String> getAgentArgs() {
        return agentArgs;
    }

    public void setAgentArgs(List<String> agentArgs) {
        this.agentArgs = agentArgs;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Chat history entry for conversation context.
     */
    public static class ChatHistoryEntry {
        private String role;
        private String content;

        public ChatHistoryEntry() {}

        public ChatHistoryEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
