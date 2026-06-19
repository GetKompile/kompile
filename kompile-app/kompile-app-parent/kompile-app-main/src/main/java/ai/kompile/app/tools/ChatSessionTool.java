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

package ai.kompile.app.tools;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.DiscoveredTool;
import ai.kompile.app.services.mcp.ToolParameter;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.util.FieldNames;
import ai.kompile.core.rag.ConversationalRagOptions;
import ai.kompile.core.rag.ConversationalRagResult;
import ai.kompile.core.rag.ConversationalRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Tool for chat session management and conversational interactions.
 * Exposes functionality to start chat sessions, send messages with RAG augmentation,
 * configure agents, and manage conversation history.
 */
@Component
public class ChatSessionTool {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionTool.class);

    private final ConversationalRagService ragService;
    private final AgentRegistryService agentRegistry;
    private final BuiltInToolDiscoveryService toolDiscoveryService;
    private final ServerPortService serverPortService;

    // Session configuration storage
    private final Map<String, SessionConfig> sessionConfigs = new ConcurrentHashMap<>();

    @Autowired
    public ChatSessionTool(
            @Autowired(required = false) ConversationalRagService ragService,
            @Autowired(required = false) AgentRegistryService agentRegistry,
            @Autowired(required = false) BuiltInToolDiscoveryService toolDiscoveryService,
            ServerPortService serverPortService) {
        this.ragService = ragService;
        this.agentRegistry = agentRegistry;
        this.toolDiscoveryService = toolDiscoveryService;
        this.serverPortService = serverPortService;
        logger.info("ChatSessionTool initialized - RAG service: {}, Agent registry: {}, MCP tools: {}",
                ragService != null ? "available" : "unavailable",
                agentRegistry != null ? "available" : "unavailable",
                toolDiscoveryService != null ? "available" : "unavailable");
    }

    // ========================================================================
    // Input Records
    // ========================================================================

    public record ListAgentsInput() {}

    public record GetAgentInfoInput(String agentName) {}

    public record CreateChatSessionInput(
            String sessionId,
            String agentName,
            Boolean enableRag,
            Integer semanticK,
            Integer keywordK,
            Double similarityThreshold,
            Boolean enableKeywordSearch,
            Boolean enableSemanticSearch,
            Integer maxHistoryMessages,
            String systemPrompt
    ) {}

    public record SendMessageInput(
            String sessionId,
            String message,
            Boolean enableRag,
            Integer maxResults,
            Double similarityThreshold
    ) {}

    public record GetChatHistoryInput(String sessionId, Integer lastN) {}

    public record ClearChatSessionInput(String sessionId) {}

    public record GetSessionConfigInput(String sessionId) {}

    public record UpdateSessionConfigInput(
            String sessionId,
            String agentName,
            Boolean enableRag,
            Integer semanticK,
            Integer keywordK,
            Double similarityThreshold,
            String systemPrompt
    ) {}

    public record QuickChatInput(
            String message,
            Boolean enableRag,
            Integer maxResults
    ) {}

    // ========================================================================
    // Agent Management Tools
    // ========================================================================

    /**
     * Lists all available agents.
     */
    @Tool(name = "list_agents",
            description = "Lists all available AI agents for chat including Claude Code, Codex, and Gemini CLI. Returns agent names, availability status, and capabilities.")
    public Map<String, Object> listAgents(ListAgentsInput input) {
        logger.info("Listing available agents");

        try {
            if (agentRegistry == null) {
                return Map.of("status", "error", "error", "Agent registry not available");
            }

            List<Map<String, Object>> agents = new ArrayList<>();

            for (AgentProvider agent : agentRegistry.getAllAgents()) {
                Map<String, Object> agentInfo = new LinkedHashMap<>();
                agentInfo.put("name", agent.getName());
                agentInfo.put("displayName", agent.getDisplayName());
                agentInfo.put("available", agent.isAvailable());
                agentInfo.put("isDefault", agent.isDefault());
                agentInfo.put("description", agent.getDescription());
                agentInfo.put("command", agent.getCommand());
                agentInfo.put("supportsSkipPermissions", agent.isSkipPermissions());
                agents.add(agentInfo);
            }

            // Get default agent
            Optional<AgentProvider> defaultAgent = agentRegistry.getDefaultAgent();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("totalAgents", agents.size());
            result.put("availableCount", agentRegistry.getAvailableAgentCount());
            result.put("defaultAgent", defaultAgent.map(AgentProvider::getName).orElse(null));
            result.put("agents", agents);

            return result;

        } catch (Exception e) {
            logger.error("Error listing agents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list agents: " + e.getMessage());
        }
    }

    /**
     * Gets detailed information about a specific agent.
     */
    @Tool(name = "get_agent_info",
            description = "Gets detailed information about a specific agent by name. Returns availability, capabilities, command, and configuration options.")
    public Map<String, Object> getAgentInfo(GetAgentInfoInput input) {
        if (input.agentName() == null || input.agentName().isEmpty()) {
            return Map.of("status", "error", "error", "Agent name is required");
        }

        logger.info("Getting info for agent: {}", input.agentName());

        try {
            if (agentRegistry == null) {
                return Map.of("status", "error", "error", "Agent registry not available");
            }

            Optional<AgentProvider> agentOpt = agentRegistry.getAgent(input.agentName());

            if (agentOpt.isEmpty()) {
                List<String> availableNames = agentRegistry.getAllAgents().stream()
                        .map(AgentProvider::getName)
                        .toList();
                return Map.of("status", "error", "error", "Agent not found: " + input.agentName(),
                        "availableAgents", availableNames);
            }

            AgentProvider agent = agentOpt.get();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("name", agent.getName());
            result.put("displayName", agent.getDisplayName());
            result.put("description", agent.getDescription());
            result.put("available", agent.isAvailable());
            result.put("isDefault", agent.isDefault());
            result.put("command", agent.getCommand());
            result.put("skipPermissionsFlag", agent.getSkipPermissionsFlag());
            result.put("supportsSkipPermissions", agent.isSkipPermissions());

            if (agent.getArgs() != null && !agent.getArgs().isEmpty()) {
                result.put("additionalArgs", new ArrayList<>(agent.getArgs()));
            }

            if (agent.getEnvironment() != null && !agent.getEnvironment().isEmpty()) {
                result.put("environmentVariables", agent.getEnvironment().keySet());
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting agent info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get agent info: " + e.getMessage());
        }
    }

    // ========================================================================
    // Chat Session Management Tools
    // ========================================================================

    /**
     * Creates a new chat session with optional configuration.
     */
    @Tool(name = "create_chat_session",
            description = "Creates a new chat session with configurable RAG settings and agent selection. Returns a session ID for subsequent interactions. Configure semanticK/keywordK for document retrieval counts, similarityThreshold (0.0-1.0), and optional system prompt.")
    public Map<String, Object> createChatSession(CreateChatSessionInput input) {
        String sessionId = input.sessionId() != null ? input.sessionId() : UUID.randomUUID().toString();

        logger.info("Creating chat session: {}, agent: {}, enableRag: {}",
                sessionId, input.agentName(), input.enableRag());

        try {
            // Build session configuration
            SessionConfig config = new SessionConfig();
            config.sessionId = sessionId;
            config.agentName = input.agentName();
            config.enableRag = input.enableRag() != null ? input.enableRag() : true;
            config.semanticK = input.semanticK() != null ? input.semanticK() : 5;
            config.keywordK = input.keywordK() != null ? input.keywordK() : 5;
            config.similarityThreshold = input.similarityThreshold() != null ? input.similarityThreshold() : 0.5;
            config.enableKeywordSearch = input.enableKeywordSearch() != null ? input.enableKeywordSearch() : true;
            config.enableSemanticSearch = input.enableSemanticSearch() != null ? input.enableSemanticSearch() : true;
            config.maxHistoryMessages = input.maxHistoryMessages() != null ? input.maxHistoryMessages() : 10;
            config.systemPrompt = input.systemPrompt();
            config.createdAt = new Date();

            // Validate agent if specified
            if (config.agentName != null && agentRegistry != null) {
                Optional<AgentProvider> agent = agentRegistry.getAgent(config.agentName);
                if (agent.isEmpty()) {
                    return Map.of("status", "error", "error", "Agent not found: " + config.agentName);
                }
                if (!agent.get().isAvailable()) {
                    return Map.of("status", "warning", FieldNames.SESSION_ID, sessionId,
                            "message", "Session created but agent '" + config.agentName + "' is not currently available");
                }
            }

            // Store configuration
            sessionConfigs.put(sessionId, config);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put(FieldNames.SESSION_ID, sessionId);
            result.put("message", "Chat session created successfully");
            result.put("configuration", configToMap(config));

            return result;

        } catch (Exception e) {
            logger.error("Error creating chat session: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to create session: " + e.getMessage());
        }
    }

    /**
     * Sends a message in a chat session and gets a response.
     */
    @Tool(name = "send_chat_message",
            description = "Sends a message in an existing chat session and returns the AI response. Uses session's RAG and agent configuration. Optionally override RAG settings per message. If sessionId doesn't exist, creates a new session automatically.")
    public Map<String, Object> sendChatMessage(SendMessageInput input) {
        if (input.message() == null || input.message().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Message cannot be empty");
        }

        String sessionId = input.sessionId() != null ? input.sessionId() : UUID.randomUUID().toString();

        logger.info("Sending message to session: {}, message length: {}", sessionId, input.message().length());

        try {
            if (ragService == null) {
                return Map.of("status", "error", "error", "RAG service not available");
            }

            // Get or create session config
            SessionConfig config = sessionConfigs.computeIfAbsent(sessionId, id -> {
                SessionConfig newConfig = new SessionConfig();
                newConfig.sessionId = id;
                newConfig.enableRag = true;
                newConfig.semanticK = 5;
                newConfig.keywordK = 5;
                newConfig.similarityThreshold = 0.5;
                newConfig.enableKeywordSearch = true;
                newConfig.enableSemanticSearch = true;
                newConfig.maxHistoryMessages = 10;
                newConfig.createdAt = new Date();
                return newConfig;
            });

            // Build RAG options (with per-message overrides)
            boolean enableRag = input.enableRag() != null ? input.enableRag() : config.enableRag;
            int semanticK = input.maxResults() != null ? input.maxResults() / 2 : config.semanticK;
            int keywordK = input.maxResults() != null ? input.maxResults() / 2 : config.keywordK;
            double threshold = input.similarityThreshold() != null ? input.similarityThreshold() : config.similarityThreshold;

            ConversationalRagOptions options = new ConversationalRagOptions(
                    enableRag ? semanticK : 0,
                    enableRag ? keywordK : 0,
                    threshold,
                    config.enableKeywordSearch && enableRag,
                    config.enableSemanticSearch && enableRag,
                    true, // enableQueryProcessing
                    config.maxHistoryMessages,
                    4000, // maxContextTokens
                    false, // useToolCalling
                    config.systemPrompt,
                    Map.of(),
                    null
            );

            long startTime = System.currentTimeMillis();

            // Execute chat
            ConversationalRagResult result = ragService.chat(sessionId, input.message(), options);

            long duration = System.currentTimeMillis() - startTime;

            // Build response
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put(FieldNames.SESSION_ID, sessionId);
            response.put("answer", result.answer());
            response.put("executionTimeMs", duration);

            // Include retrieved documents info
            if (result.retrievedDocuments() != null && !result.retrievedDocuments().isEmpty()) {
                response.put("documentsRetrieved", result.retrievedDocuments().size());

                List<Map<String, Object>> docs = new ArrayList<>();
                for (var doc : result.retrievedDocuments()) {
                    Map<String, Object> docInfo = new LinkedHashMap<>();
                    docInfo.put("id", doc.document().getId());
                    docInfo.put(FieldNames.SCORE, doc.score());
                    String content = doc.document().getText();
                    if (content != null) {
                        docInfo.put("contentPreview", content.length() > 200 ? content.substring(0, 200) + "..." : content);
                    }
                    docs.add(docInfo);
                }
                response.put("retrievedDocuments", docs);
            } else {
                response.put("documentsRetrieved", 0);
            }

            // Include metrics if available
            if (result.retrievalMetrics() != null) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("totalRetrievalTimeMs", result.retrievalMetrics().totalTimeMs());
                metrics.put("embeddingTimeMs", result.retrievalMetrics().embeddingTimeMs());
                metrics.put("semanticSearchTimeMs", result.retrievalMetrics().semanticSearchTimeMs());
                metrics.put("keywordSearchTimeMs", result.retrievalMetrics().keywordSearchTimeMs());
                response.put("retrievalMetrics", metrics);
            }

            response.put("generationTimeMs", result.generationTimeMs());
            response.put("conversationSize", ragService.getConversationSize(sessionId));

            return response;

        } catch (Exception e) {
            logger.error("Error sending chat message: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to send message: " + e.getMessage());
        }
    }

    /**
     * Gets the chat history for a session.
     */
    @Tool(name = "get_chat_history",
            description = "Gets the conversation history for a chat session. Optionally specify lastN to get only the most recent messages.")
    public Map<String, Object> getChatHistory(GetChatHistoryInput input) {
        if (input.sessionId() == null || input.sessionId().isEmpty()) {
            return Map.of("status", "error", "error", "Session ID is required");
        }

        logger.info("Getting chat history for session: {}", input.sessionId());

        try {
            if (ragService == null) {
                return Map.of("status", "error", "error", "RAG service not available");
            }

            List<Message> history = ragService.getConversationHistory(input.sessionId());

            if (history == null || history.isEmpty()) {
                return Map.of("status", "success", FieldNames.SESSION_ID, input.sessionId(),
                        "messageCount", 0, "messages", Collections.emptyList());
            }

            // Apply lastN filter
            if (input.lastN() != null && input.lastN() > 0 && input.lastN() < history.size()) {
                history = history.subList(history.size() - input.lastN(), history.size());
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            for (Message msg : history) {
                Map<String, Object> msgInfo = new LinkedHashMap<>();
                msgInfo.put("role", msg.getMessageType().toString());
                String content = msg.getText();
                if (content != null) {
                    // Truncate very long messages
                    msgInfo.put("content", content.length() > 1000 ? content.substring(0, 1000) + "..." : content);
                    msgInfo.put("contentLength", content.length());
                }
                messages.add(msgInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put(FieldNames.SESSION_ID, input.sessionId());
            result.put("messageCount", messages.size());
            result.put("totalMessages", ragService.getConversationSize(input.sessionId()));
            result.put("messages", messages);

            // Include session config if available
            SessionConfig config = sessionConfigs.get(input.sessionId());
            if (config != null) {
                result.put("sessionConfig", configToMap(config));
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting chat history: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get history: " + e.getMessage());
        }
    }

    /**
     * Clears a chat session's history.
     */
    @Tool(name = "clear_chat_session",
            description = "Clears the conversation history for a chat session. The session configuration is preserved.")
    public Map<String, Object> clearChatSession(ClearChatSessionInput input) {
        if (input.sessionId() == null || input.sessionId().isEmpty()) {
            return Map.of("status", "error", "error", "Session ID is required");
        }

        logger.info("Clearing chat session: {}", input.sessionId());

        try {
            if (ragService == null) {
                return Map.of("status", "error", "error", "RAG service not available");
            }

            int sizeBefore = ragService.getConversationSize(input.sessionId());
            ragService.clearConversation(input.sessionId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put(FieldNames.SESSION_ID, input.sessionId());
            result.put("messagesCleared", sizeBefore);
            result.put("message", "Chat session cleared successfully");

            return result;

        } catch (Exception e) {
            logger.error("Error clearing chat session: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to clear session: " + e.getMessage());
        }
    }

    /**
     * Gets the configuration for a chat session.
     */
    @Tool(name = "get_session_config",
            description = "Gets the current configuration for a chat session including RAG settings, agent, and system prompt.")
    public Map<String, Object> getSessionConfig(GetSessionConfigInput input) {
        if (input.sessionId() == null || input.sessionId().isEmpty()) {
            return Map.of("status", "error", "error", "Session ID is required");
        }

        logger.info("Getting config for session: {}", input.sessionId());

        try {
            SessionConfig config = sessionConfigs.get(input.sessionId());

            if (config == null) {
                return Map.of("status", "error", "error", "Session not found: " + input.sessionId());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put(FieldNames.SESSION_ID, input.sessionId());
            result.put("configuration", configToMap(config));

            // Add conversation stats
            if (ragService != null) {
                result.put("conversationSize", ragService.getConversationSize(input.sessionId()));
                result.put("hasConversation", ragService.hasConversation(input.sessionId()));
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting session config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get config: " + e.getMessage());
        }
    }

    /**
     * Updates the configuration for a chat session.
     */
    @Tool(name = "update_session_config",
            description = "Updates the configuration for an existing chat session. Only specified fields are updated; others remain unchanged.")
    public Map<String, Object> updateSessionConfig(UpdateSessionConfigInput input) {
        if (input.sessionId() == null || input.sessionId().isEmpty()) {
            return Map.of("status", "error", "error", "Session ID is required");
        }

        logger.info("Updating config for session: {}", input.sessionId());

        try {
            SessionConfig config = sessionConfigs.get(input.sessionId());

            if (config == null) {
                return Map.of("status", "error", "error", "Session not found: " + input.sessionId());
            }

            // Update only specified fields
            List<String> updatedFields = new ArrayList<>();

            if (input.agentName() != null) {
                // Validate agent
                if (agentRegistry != null) {
                    Optional<AgentProvider> agent = agentRegistry.getAgent(input.agentName());
                    if (agent.isEmpty()) {
                        return Map.of("status", "error", "error", "Agent not found: " + input.agentName());
                    }
                }
                config.agentName = input.agentName();
                updatedFields.add("agentName");
            }

            if (input.enableRag() != null) {
                config.enableRag = input.enableRag();
                updatedFields.add("enableRag");
            }

            if (input.semanticK() != null) {
                config.semanticK = input.semanticK();
                updatedFields.add("semanticK");
            }

            if (input.keywordK() != null) {
                config.keywordK = input.keywordK();
                updatedFields.add("keywordK");
            }

            if (input.similarityThreshold() != null) {
                config.similarityThreshold = input.similarityThreshold();
                updatedFields.add("similarityThreshold");
            }

            if (input.systemPrompt() != null) {
                config.systemPrompt = input.systemPrompt();
                updatedFields.add("systemPrompt");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put(FieldNames.SESSION_ID, input.sessionId());
            result.put("updatedFields", updatedFields);
            result.put("configuration", configToMap(config));

            return result;

        } catch (Exception e) {
            logger.error("Error updating session config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to update config: " + e.getMessage());
        }
    }

    /**
     * Quick one-off chat without session management.
     */
    @Tool(name = "quick_chat",
            description = "Sends a one-off message without session management. Creates a temporary session that is automatically cleaned up. Good for simple queries that don't need conversation context.")
    public Map<String, Object> quickChat(QuickChatInput input) {
        if (input.message() == null || input.message().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Message cannot be empty");
        }

        logger.info("Quick chat: message length {}", input.message().length());

        try {
            if (ragService == null) {
                return Map.of("status", "error", "error", "RAG service not available");
            }

            boolean enableRag = input.enableRag() != null ? input.enableRag() : true;
            int maxResults = input.maxResults() != null ? input.maxResults() : 5;

            ConversationalRagOptions options = ConversationalRagOptions.simpleRag(
                    maxResults,
                    0.5
            );

            if (!enableRag) {
                options = new ConversationalRagOptions(
                        0, 0, 0.0, false, false, false,
                        0, 4000, false, null, Map.of(), null
                );
            }

            String tempSessionId = "quick-" + System.currentTimeMillis();

            long startTime = System.currentTimeMillis();
            ConversationalRagResult result;
            try {
                result = ragService.chat(tempSessionId, input.message(), options);
            } finally {
                // Clean up temp session
                ragService.clearConversation(tempSessionId);
            }
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("answer", result.answer());
            response.put("executionTimeMs", duration);

            if (result.retrievedDocuments() != null) {
                response.put("documentsRetrieved", result.retrievedDocuments().size());
            }

            return response;

        } catch (Exception e) {
            logger.error("Error in quick chat: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to process message: " + e.getMessage());
        }
    }

    /**
     * Lists all active chat sessions.
     */
    @Tool(name = "list_chat_sessions",
            description = "Lists all active chat sessions with their configurations and message counts.")
    public Map<String, Object> listChatSessions(ListAgentsInput input) {
        logger.info("Listing chat sessions");

        try {
            List<Map<String, Object>> sessions = new ArrayList<>();

            for (SessionConfig config : sessionConfigs.values()) {
                Map<String, Object> sessionInfo = new LinkedHashMap<>();
                sessionInfo.put(FieldNames.SESSION_ID, config.sessionId);
                sessionInfo.put("agentName", config.agentName);
                sessionInfo.put("enableRag", config.enableRag);
                sessionInfo.put("createdAt", config.createdAt != null ? config.createdAt.toString() : null);

                if (ragService != null) {
                    sessionInfo.put("messageCount", ragService.getConversationSize(config.sessionId));
                }

                sessions.add(sessionInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("sessionCount", sessions.size());
            result.put("sessions", sessions);

            return result;

        } catch (Exception e) {
            logger.error("Error listing sessions: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list sessions: " + e.getMessage());
        }
    }

    // ========================================================================
    // MCP Tools Discovery
    // ========================================================================

    public record GetMcpToolsInput(String category, Boolean includeParameters) {}

    /**
     * Gets available MCP tools that can be used by the agent.
     */
    @Tool(name = "get_available_mcp_tools",
            description = "Gets information about available MCP tools that can be invoked during chat sessions. Optionally filter by category and include parameter details.")
    public Map<String, Object> getAvailableMcpTools(GetMcpToolsInput input) {
        logger.info("Getting available MCP tools, category: {}", input.category());

        try {
            if (toolDiscoveryService == null) {
                return Map.of("status", "error", "error", "MCP tool discovery service not available");
            }

            List<DiscoveredTool> tools = toolDiscoveryService.getDiscoveredTools();
            boolean includeParams = input.includeParameters() != null && input.includeParameters();

            // Group by category
            Map<String, List<Map<String, Object>>> toolsByCategory = new LinkedHashMap<>();
            for (DiscoveredTool tool : tools) {
                String category = extractCategoryFromBeanClass(tool.getBeanClass());

                // Filter by category if specified
                if (input.category() != null && !input.category().isEmpty() &&
                        !category.equalsIgnoreCase(input.category())) {
                    continue;
                }

                Map<String, Object> toolInfo = new LinkedHashMap<>();
                toolInfo.put("name", tool.getName());
                toolInfo.put("description", tool.getDescription());

                if (includeParams && tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    List<Map<String, Object>> params = new ArrayList<>();
                    for (ToolParameter param : tool.getParameters()) {
                        Map<String, Object> paramInfo = new LinkedHashMap<>();
                        paramInfo.put("name", param.getName());
                        paramInfo.put("type", param.getType());
                        paramInfo.put("required", param.isRequired());
                        params.add(paramInfo);
                    }
                    toolInfo.put("parameters", params);
                }

                toolsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(toolInfo);
            }

            int actualPort = serverPortService.getActualPort();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("serverPort", actualPort);
            result.put("invokeEndpoint", "POST /api/mcp/tools/invoke-direct");
            result.put("totalTools", tools.size());
            result.put("categories", toolsByCategory.keySet());
            result.put("toolsByCategory", toolsByCategory);

            // Include usage instructions
            result.put("usageExample", Map.of(
                    "endpoint", serverPortService.getToolsInvokeUrl(),
                    "method", "POST",
                    "contentType", "application/json",
                    "body", Map.of(
                            "toolName", "<tool_name>",
                            "arguments", Map.of("param1", "value1")
                    )
            ));

            return result;

        } catch (Exception e) {
            logger.error("Error getting MCP tools: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get tools: " + e.getMessage());
        }
    }

    /**
     * Extracts a human-readable category name from the bean class.
     */
    private String extractCategoryFromBeanClass(String beanClass) {
        if (beanClass == null) return "Other";

        String simpleName = beanClass.substring(beanClass.lastIndexOf('.') + 1);

        if (simpleName.contains("ModelManagement")) return "Model Management";
        if (simpleName.contains("ModelDebug")) return "Model Debugging";
        if (simpleName.contains("DocumentManagement")) return "Document Management";
        if (simpleName.contains("SystemDiagnostics")) return "System Diagnostics";
        if (simpleName.contains("IndexOperations")) return "Index Operations";
        if (simpleName.contains("ApplicationConfig")) return "Application Configuration";
        if (simpleName.contains("ChatSession")) return "Chat Sessions";
        if (simpleName.contains("AgentDelegation")) return "Agent Delegation";
        if (simpleName.contains("Rag")) return "RAG Operations";
        if (simpleName.contains("Filesystem")) return "Filesystem Operations";

        String category = simpleName.replace("Tool", "").replaceAll("([a-z])([A-Z])", "$1 $2");
        return category.isEmpty() ? "Other" : category;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Map<String, Object> configToMap(SessionConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(FieldNames.SESSION_ID, config.sessionId);
        map.put("agentName", config.agentName);
        map.put("enableRag", config.enableRag);
        map.put("semanticK", config.semanticK);
        map.put("keywordK", config.keywordK);
        map.put("similarityThreshold", config.similarityThreshold);
        map.put("enableKeywordSearch", config.enableKeywordSearch);
        map.put("enableSemanticSearch", config.enableSemanticSearch);
        map.put("maxHistoryMessages", config.maxHistoryMessages);
        map.put("systemPrompt", config.systemPrompt);
        map.put("createdAt", config.createdAt != null ? config.createdAt.toString() : null);
        return map;
    }

    // ========================================================================
    // Session Configuration Storage
    // ========================================================================

    private static class SessionConfig {
        String sessionId;
        String agentName;
        boolean enableRag = true;
        int semanticK = 5;
        int keywordK = 5;
        double similarityThreshold = 0.5;
        boolean enableKeywordSearch = true;
        boolean enableSemanticSearch = true;
        int maxHistoryMessages = 10;
        String systemPrompt;
        Date createdAt;
    }
}
