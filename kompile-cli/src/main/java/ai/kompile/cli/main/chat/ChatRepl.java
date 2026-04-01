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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.agent.*;;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Interactive REPL for chatting with LLMs.
 * <p>
 * Supports two operational modes:
 * <ul>
 *   <li><b>Server mode</b>: Connected to a running kompile-app instance via MCP.
 *       Supports inline RAG chat, agent streaming, and agentic tool loop.</li>
 *   <li><b>Local mode</b>: Direct LLM API calls without a server.
 *       All chat goes through the agentic tool loop with local tool execution.</li>
 * </ul>
 */
public class ChatRepl {

    private final McpSseClient mcpClient; // null in local mode
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl; // null in local mode
    private final String sessionId;
    private final ChatHistory chatHistory;
    private final ChatMemory chatMemory;
    private boolean ragEnabled;
    private String agentName;
    private String localAgentName;
    private List<McpSseClient.ToolInfo> cachedTools;

    // Tool & agent system
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final PermissionService permissionService;
    private final AgenticChatLoop agenticLoop;
    private final TerminalRenderer renderer;
    private final AsciiRenderer ascii;

    // Mode
    private final boolean localMode;
    private ChatConfig chatConfig; // non-null in local mode

    /**
     * Server mode constructor.
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, true, null);
    }

    /**
     * Server mode constructor with memory option.
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName, boolean memoryEnabled) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, memoryEnabled, null);
    }

    /**
     * Full constructor supporting both server and local modes.
     *
     * @param mcpClient   MCP client (null for local mode)
     * @param baseUrl     Server URL (null for local mode)
     * @param sessionId   Chat session ID
     * @param ragEnabled  Whether RAG is enabled
     * @param agentName   Server agent name
     * @param memoryEnabled Whether memory is enabled
     * @param chatConfig  LLM config for local mode (null for server mode)
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName, boolean memoryEnabled,
                    ChatConfig chatConfig) {
        this.mcpClient = mcpClient;
        this.localMode = (mcpClient == null);
        this.chatConfig = chatConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (mcpClient != null) {
            this.objectMapper = mcpClient.getObjectMapper();
        } else {
            this.objectMapper = new ObjectMapper();
        }

        this.baseUrl = baseUrl;
        this.sessionId = sessionId;
        this.ragEnabled = localMode ? false : ragEnabled;
        this.agentName = agentName;
        this.localAgentName = "coder";
        this.chatHistory = new ChatHistory(sessionId);

        // ChatMemory works in both modes: persistent memory + transcripts always,
        // RAG search only when server is connected
        this.chatMemory = new ChatMemory(mcpClient, sessionId, memoryEnabled);

        // Initialize tool & agent system
        this.permissionService = new PermissionService();
        this.agentRegistry = new AgentRegistry();
        this.renderer = new TerminalRenderer();
        this.ascii = new AsciiRenderer(renderer);

        Path workDir = Paths.get(System.getProperty("user.dir"));

        // Load custom agents from .kompile/agents/ and ~/.kompile/agents/
        CustomAgentLoader customAgentLoader = new CustomAgentLoader(workDir);
        Map<String, AgentConfig> customAgents = customAgentLoader.loadAll();
        for (AgentConfig custom : customAgents.values()) {
            agentRegistry.register(custom);
        }

        // Create background process manager for this session
        BackgroundProcessManager processManager = new BackgroundProcessManager(sessionId);

        this.toolRegistry = ToolRegistryFactory.create(
                objectMapper, baseUrl != null ? baseUrl : "", agentRegistry,
                permissionService, renderer, processManager,
                localMode ? chatConfig : null);

        // Create DirectLlmClient for local mode
        DirectLlmClient directClient = null;
        if (localMode && chatConfig != null) {
            directClient = new DirectLlmClient(chatConfig, objectMapper);
        }

        this.agenticLoop = new AgenticChatLoop(
                baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workDir, directClient, processManager);
    }

    public void run() throws Exception {
        // Restore previous conversation if resuming
        restoreSession();

        // Open transcript file for writing
        chatHistory.open(baseUrl != null ? baseUrl : "(local)", agentName, ragEnabled);

        // Pre-cache tools for completion (server mode only)
        if (!localMode) {
            try {
                cachedTools = mcpClient.listTools();
            } catch (Exception e) {
                cachedTools = List.of();
            }
        } else {
            cachedTools = List.of();
        }

        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        Path historyFile = new File(KompileHome.homeDirectory(), "chat_input_history").toPath();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new ChatCompleter(() -> cachedTools))
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();

        reader.getHistory().load();

        // Print welcome banner
        if (localMode) {
            System.out.println(ascii.welcomePanel(sessionId, localAgentName, false));
            System.out.println();
            String provider = chatConfig != null ? chatConfig.getProvider() : "unknown";
            String model = chatConfig != null ? chatConfig.getModel() : "unknown";
            System.out.println(renderer.dim("  Mode: local (direct LLM) — " + provider + "/" + model));
            System.out.println(renderer.dim("  All messages use the agentic tool loop with local tools."));
            System.out.println(renderer.dim("  Type /help for commands, /setup to reconfigure."));
        } else {
            System.out.println(ascii.welcomePanel(sessionId, agentName, ragEnabled));
        }

        // Show AGENTS.md status
        String agentsMd = agenticLoop.getAgentsMdContent();
        if (agentsMd != null && !agentsMd.isEmpty()) {
            AgentsMdLoader loader = new AgentsMdLoader(Paths.get(System.getProperty("user.dir")));
            List<Path> files = loader.listFiles();
            System.out.println(renderer.dim("  Loaded AGENTS.md from: " +
                    files.stream().map(p -> p.getParent().toString()).collect(java.util.stream.Collectors.joining(", "))));
        }

        // Show available subagents
        List<AgentConfig> subagents = agentRegistry.getSubagents();
        long customCount = subagents.stream().filter(AgentConfig::isCustom).count();
        long builtinCount = subagents.size() - customCount;
        StringBuilder agentInfo = new StringBuilder();
        agentInfo.append("  Subagents: ").append(builtinCount).append(" built-in");
        if (customCount > 0) {
            agentInfo.append(", ").append(customCount).append(" custom");
        }
        agentInfo.append(" (");
        agentInfo.append(subagents.stream()
                .map(a -> a.getName() + (a.isCustom() ? "*" : ""))
                .collect(Collectors.joining(", ")));
        agentInfo.append(")");
        System.out.println(renderer.dim(agentInfo.toString()));
        System.out.println();

        try {
            while (true) {
                String line;
                try {
                    line = reader.readLine("kompile> ");
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (line == null || line.isBlank()) {
                    continue;
                }

                String trimmed = line.trim();

                if (trimmed.startsWith("/")) {
                    if (!handleSlashCommand(trimmed)) {
                        break;
                    }
                } else {
                    handleChatMessage(trimmed);
                }
            }
        } finally {
            reader.getHistory().save();
            chatHistory.close();
        }

        System.out.println("Goodbye. Transcript saved to " + chatHistory.getTranscriptFile());
    }

    /**
     * Restore a previous conversation session by replaying transcript turns
     * into the DirectLlmClient history (local mode) or printing the transcript (server mode).
     */
    private void restoreSession() {
        if (!ChatHistory.exists(sessionId)) return;

        try {
            ChatHistory history = new ChatHistory(sessionId);
            List<ChatHistory.Turn> turns = history.readTurns();

            if (turns.isEmpty()) return;

            // Print the previous transcript
            String transcript = history.readTranscript();
            if (transcript != null) {
                System.out.println();
                // Print a condensed version — last 50 lines
                String[] lines = transcript.split("\n");
                if (lines.length > 50) {
                    System.out.println(renderer.dim("  ... (" + (lines.length - 50) + " earlier lines)"));
                    for (int i = lines.length - 50; i < lines.length; i++) {
                        System.out.println(lines[i]);
                    }
                } else {
                    System.out.println(transcript);
                }
                System.out.println(renderer.dim("─── end of previous conversation (" + turns.size() + " turns) ───"));
                System.out.println();
            }

            // In local mode, replay turns into the DirectLlmClient
            if (localMode && agenticLoop != null) {
                agenticLoop.restoreHistory(turns);
                System.out.println(renderer.dim("  Restored " + turns.size() + " turns to context."));
            }

        } catch (Exception e) {
            System.err.println("Warning: Could not restore session: " + e.getMessage());
        }
    }

    /**
     * @return false if the REPL should exit
     */
    private boolean handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/quit":
            case "/exit":
                return false;

            case "/help":
                printHelp();
                return true;

            case "/setup":
                runSetup();
                return true;

            case "/tools":
                if (localMode) {
                    listLocalTools();
                } else {
                    listTools();
                }
                return true;

            case "/local-tools":
                listLocalTools();
                return true;

            case "/tool":
                if (localMode) {
                    invokeLocalTool(rest);
                } else {
                    invokeTool(rest);
                }
                return true;

            case "/local-tool":
                invokeLocalTool(rest);
                return true;

            case "/status":
                printStatus();
                return true;

            case "/history":
                if (localMode) {
                    showTranscript();
                } else {
                    showHistory();
                }
                return true;

            case "/clear":
                if (localMode) {
                    System.out.println("Session transcript cleared (local mode).");
                } else {
                    clearSession();
                }
                return true;

            case "/rag":
                if (localMode) {
                    System.out.println(renderer.dim("RAG is not available in local mode. "
                            + "Connect to a kompile-app server for RAG support."));
                } else {
                    toggleRag(rest);
                }
                return true;

            case "/agents":
                if (localMode) {
                    listLocalAgents();
                } else {
                    listAgents();
                }
                return true;

            case "/local-agents":
                listLocalAgents();
                return true;

            case "/agent":
                if (localMode) {
                    switchLocalAgent(rest);
                } else {
                    switchAgent(rest);
                }
                return true;

            case "/local-agent":
                switchLocalAgent(rest);
                return true;

            case "/config":
                if (localMode) {
                    showLocalConfig();
                } else {
                    handleConfig(rest);
                }
                return true;

            case "/sessions":
                if (localMode) {
                    listConversations();
                } else {
                    listSessions();
                }
                return true;

            case "/ask":
                if (localMode) {
                    // In local mode, /ask goes through the agentic loop too
                    agenticChat(rest);
                } else {
                    streamAgentChat(rest);
                }
                return true;

            case "/agent-chat":
                agenticChat(rest);
                return true;

            case "/conversations":
                listConversations();
                return true;

            case "/transcript":
                showTranscript();
                return true;

            case "/memory":
                handleMemory(rest);
                return true;

            case "/recall":
                handleRecall(rest);
                return true;

            case "/permissions":
                handlePermissions(rest);
                return true;

            case "/todos":
                showTodos();
                return true;

            default:
                System.out.println("Unknown command: " + cmd + ". Type /help for available commands.");
                return true;
        }
    }

    // ========================================================================
    // Chat message handling
    // ========================================================================

    private void handleChatMessage(String message) {
        chatHistory.logUserMessage(message);

        if (localMode) {
            // In local mode, all messages go through the agentic loop
            handleLocalChat(message);
        } else {
            handleServerChat(message);
        }
    }

    private void handleLocalChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        System.out.println();
        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, localAgentName, agentName, false);

            System.out.println("\n");
            chatHistory.logAgentResponse(localAgentName, response, 0);
        } catch (Exception e) {
            System.err.println("\nError in chat: " + e.getMessage());
        }
    }

    private void handleServerChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("message", enrichedMessage);
            args.put("enableRag", ragEnabled);
            args.put("maxResults", 10);
            args.put("similarityThreshold", 0.5);

            String rawResponse = mcpClient.callTool("send_chat_message", args);

            try {
                JsonNode json = objectMapper.readTree(rawResponse);
                String answer = json.path("answer").asText(null);
                int docsRetrieved = json.path("documentsRetrieved").asInt(0);
                long timeMs = json.path("executionTimeMs").asLong(0);

                if (answer != null) {
                    System.out.println("\n" + answer);
                } else {
                    answer = rawResponse;
                    System.out.println("\n" + rawResponse);
                }

                if (docsRetrieved > 0) {
                    System.out.printf("  [%d docs retrieved, %dms]%n", docsRetrieved, timeMs);
                }
                System.out.println();

                chatHistory.logAssistantMessage(
                        answer != null ? answer : rawResponse,
                        docsRetrieved, timeMs);
            } catch (Exception e) {
                System.out.println("\n" + rawResponse + "\n");
                chatHistory.logAssistantMessage(rawResponse, 0, 0);
            }
        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
        }
    }

    // ========================================================================
    // Agent streaming (/ask command) via REST /api/agents/chat/stream SSE
    // ========================================================================

    private void streamAgentChat(String message) {
        if (message.isBlank()) {
            System.out.println("Usage: /ask <message>");
            System.out.println("Sends a message to the configured agent with streaming output.");
            return;
        }

        chatHistory.logUserMessage("/ask " + message);

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("message", enrichedMessage);
            request.put("agentName", agentName);
            request.put("enableRag", ragEnabled);
            request.put("ragMaxResults", 5);
            request.put("ragSimilarityThreshold", 0.5);
            request.put("includeKeywordSearch", true);
            request.put("includeSemanticSearch", true);
            request.put("injectMcpTools", true);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);

            String body = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/agents/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                System.err.println("Agent stream failed: HTTP " + response.statusCode());
                return;
            }

            System.out.println();

            // Accumulate full response for transcript
            StringBuilder fullResponse = new StringBuilder();
            long[] durationMs = {0};

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String eventType = null;
                StringBuilder dataBuffer = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring(5).trim());
                    } else if (line.isEmpty() && eventType != null) {
                        handleStreamEvent(eventType, dataBuffer.toString(), fullResponse, durationMs);
                        eventType = null;
                        dataBuffer.setLength(0);
                    }
                }
            }

            System.out.println("\n");

            chatHistory.logAgentResponse(agentName, fullResponse.toString(), durationMs[0]);

        } catch (Exception e) {
            System.err.println("\nError in agent stream: " + e.getMessage());
        }
    }

    // ========================================================================
    // Agentic tool loop (/agent-chat command) - local tool execution
    // ========================================================================

    private void agenticChat(String message) {
        if (message.isBlank()) {
            System.out.println("Usage: /agent-chat <message>");
            System.out.println("Sends a message through the agentic tool loop with local tool execution.");
            System.out.println("Current local agent: " + localAgentName);
            System.out.println("Available local agents: " + String.join(", ",
                    agentRegistry.getPrimaryAgents().stream().map(AgentConfig::getName).toArray(String[]::new)));
            return;
        }

        chatHistory.logUserMessage("/agent-chat " + message);

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        System.out.println();

        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, localAgentName, agentName, ragEnabled);

            System.out.println("\n");
            chatHistory.logAgentResponse(localAgentName, response, 0);

        } catch (Exception e) {
            System.err.println("\nError in agentic chat: " + e.getMessage());
        }
    }

    private void handleStreamEvent(String eventType, String data,
                                   StringBuilder fullResponse, long[] durationMs) {
        switch (eventType) {
            case "chunk":
                String chunk = data;
                if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                    try {
                        chunk = objectMapper.readValue(chunk, String.class);
                    } catch (Exception e) {
                        // Use as-is
                    }
                }
                System.out.print(chunk);
                System.out.flush();
                fullResponse.append(chunk);
                break;

            case "start":
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    System.out.println("[Agent: " + agent + "]");
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "sources":
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        System.out.println("[Retrieved " + sources.size() + " documents]");
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "stats":
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    durationMs[0] = stats.path("durationMs").asLong(0);
                    if (durationMs[0] > 0) {
                        System.out.printf("%n  [completed in %dms]", durationMs[0]);
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "error":
                try {
                    JsonNode error = objectMapper.readTree(data);
                    System.err.println("\nError: " + error.path("message").asText(data));
                } catch (Exception e) {
                    System.err.println("\nError: " + data);
                }
                break;

            case "complete":
                break;

            case "cancelled":
                System.out.println("\n[Cancelled]");
                break;

            default:
                break;
        }
    }

    // ========================================================================
    // Slash commands
    // ========================================================================

    private void printHelp() {
        StringBuilder body = new StringBuilder();

        if (localMode) {
            body.append(renderer.bold(renderer.cyan("Chat"))).append("\n");
            body.append("  ").append(renderer.cyan("<text>")).append("              Send a message (agentic tool loop)\n");
            body.append("  ").append(renderer.cyan("/agent-chat <text>")).append("  Same as above (explicit)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Tools & Agents"))).append("\n");
            body.append("  ").append(renderer.cyan("/tools")).append("              List local CLI tools\n");
            body.append("  ").append(renderer.cyan("/tool")).append(" name [json]   Invoke a tool directly\n");
            body.append("  ").append(renderer.cyan("/agents")).append("             List local agent types\n");
            body.append("  ").append(renderer.cyan("/agent")).append(" name         Switch agent type\n");
            body.append("  ").append(renderer.cyan("/permissions")).append("        View or set tool permissions\n");
            body.append("  ").append(renderer.cyan("/todos")).append("              Show the session task list\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Session"))).append("\n");
            body.append("  ").append(renderer.cyan("/history")).append("            Show conversation transcript\n");
            body.append("  ").append(renderer.cyan("/conversations")).append("      List all saved conversations\n");
            body.append("  ").append(renderer.cyan("/config")).append("             Show LLM configuration\n");
            body.append("  ").append(renderer.cyan("/setup")).append("              Reconfigure LLM provider\n");
            body.append("  ").append(renderer.cyan("/status")).append("             Session info\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("General"))).append("\n");
            body.append("  ").append(renderer.cyan("/help")).append("               This help message\n");
            body.append("  ").append(renderer.cyan("/quit")).append("               Exit the chat");
        } else {
            body.append(renderer.bold(renderer.cyan("Chat Commands"))).append("\n");
            body.append("  ").append(renderer.cyan("<text>")).append("              Send a message (inline RAG chat)\n");
            body.append("  ").append(renderer.cyan("/ask <text>")).append("         Send via server agent with streaming\n");
            body.append("  ").append(renderer.cyan("/agent-chat <text>")).append("  Agentic tool loop (local tools)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Local Tools & Agents"))).append("\n");
            body.append("  ").append(renderer.cyan("/local-tools")).append("        List all local CLI tools\n");
            body.append("  ").append(renderer.cyan("/local-tool")).append(" name    Invoke a local tool directly\n");
            body.append("  ").append(renderer.cyan("/local-agents")).append("       List local agent types\n");
            body.append("  ").append(renderer.cyan("/local-agent")).append(" name   Switch local agent type\n");
            body.append("  ").append(renderer.cyan("/permissions")).append("        View or set tool permissions\n");
            body.append("  ").append(renderer.cyan("/todos")).append("              Show the session task list\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Memory & Recall"))).append("\n");
            body.append("  ").append(renderer.cyan("/memory")).append("             Show memory status / toggle\n");
            body.append("  ").append(renderer.cyan("/recall <query>")).append("     Search conversations and RAG\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Session & History"))).append("\n");
            body.append("  ").append(renderer.cyan("/history")).append("            Server-side conversation history\n");
            body.append("  ").append(renderer.cyan("/transcript")).append("         Local transcript file\n");
            body.append("  ").append(renderer.cyan("/conversations")).append("      List all saved conversations\n");
            body.append("  ").append(renderer.cyan("/clear")).append("              Clear server history\n");
            body.append("  ").append(renderer.cyan("/config")).append("             Show/update session config\n");
            body.append("  ").append(renderer.cyan("/setup")).append("              Reconfigure LLM provider\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("RAG & Server Agents"))).append("\n");
            body.append("  ").append(renderer.cyan("/rag")).append(" on|off         Toggle RAG retrieval\n");
            body.append("  ").append(renderer.cyan("/agents")).append("             List server agents\n");
            body.append("  ").append(renderer.cyan("/agent")).append(" <name>       Switch server agent\n");
            body.append("  ").append(renderer.cyan("/tools")).append("              List MCP tools\n");
            body.append("  ").append(renderer.cyan("/tool")).append(" <name> [json] Invoke MCP tool\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("General"))).append("\n");
            body.append("  ").append(renderer.cyan("/status")).append("             Connection and session info\n");
            body.append("  ").append(renderer.cyan("/help")).append("               This help message\n");
            body.append("  ").append(renderer.cyan("/quit")).append("               Exit the chat");
        }

        System.out.println(ascii.panel("Help", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("  Conversations saved to ~/.kompile/conversations/"));
        System.out.println(renderer.dim("  Resume: kompile chat --resume <session-id>"));
        System.out.println(renderer.dim("  Continue last: kompile chat --continue"));
    }

    private void runSetup() {
        ChatConfig newConfig = SetupWizard.run();
        if (newConfig != null) {
            this.chatConfig = newConfig;
            System.out.println(renderer.green("Configuration updated. New messages will use the updated settings."));
            if (localMode) {
                System.out.println(renderer.dim("Note: restart the chat to fully apply the new configuration."));
            }
        } else {
            System.out.println("Setup cancelled.");
        }
    }

    private void showLocalConfig() {
        if (chatConfig == null) {
            System.out.println("No LLM configuration. Run /setup to configure.");
            return;
        }

        java.util.LinkedHashMap<String, String> configMap = new java.util.LinkedHashMap<>();
        configMap.put("Provider", renderer.cyan(chatConfig.getProvider()));
        configMap.put("Model", renderer.cyan(chatConfig.getModel()));
        configMap.put("Base URL", chatConfig.resolveBaseUrl());
        configMap.put("API Key", chatConfig.getApiKey() != null ?
                renderer.dim(chatConfig.getApiKey().substring(0, Math.min(4, chatConfig.getApiKey().length())) + "...") :
                renderer.red("not set"));
        configMap.put("Config file", "~/.kompile/chat-config.json");

        System.out.println(ascii.panel("LLM Configuration", ascii.keyValueList(configMap), AsciiRenderer.ROUNDED, "blue"));
        System.out.println();
        System.out.println(renderer.dim("  /setup to reconfigure"));
    }

    private void listTools() {
        try {
            cachedTools = mcpClient.listTools();
            if (cachedTools.isEmpty()) {
                System.out.println("No MCP tools available.");
                return;
            }
            java.util.List<String> headers = java.util.List.of("Tool", "Description");
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            for (McpSseClient.ToolInfo tool : cachedTools) {
                rows.add(java.util.List.of(tool.getName(), truncate(tool.getDescription(), 55)));
            }
            System.out.println(ascii.sectionHeader("MCP Tools (" + cachedTools.size() + ")"));
            System.out.println(ascii.table(headers, rows));
        } catch (Exception e) {
            System.err.println("Error listing tools: " + e.getMessage());
        }
    }

    private void listLocalTools() {
        AgentConfig agent = agentRegistry.get(localAgentName);
        if (agent == null) agent = agentRegistry.getDefault();

        List<CliTool> tools = toolRegistry.getToolsForAgent(agent);
        java.util.List<String> headers = java.util.List.of("Tool", "Permission", "Description");
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        for (CliTool tool : tools) {
            String desc = tool.description();
            if (desc.length() > 55) desc = desc.substring(0, 52) + "...";
            rows.add(java.util.List.of(tool.id(), tool.permissionKey(), desc));
        }

        String title = "Local CLI Tools (" + tools.size() + ") — agent: " + agent.getName();
        System.out.println(ascii.sectionHeader(title));
        System.out.println(ascii.table(headers, rows));
        System.out.println();
        if (localMode) {
            System.out.println(renderer.dim("  /tool <name> [json]   invoke directly"));
        } else {
            System.out.println(renderer.dim("  /local-tool <name> [json]   invoke directly"));
            System.out.println(renderer.dim("  /agent-chat <message>       use via agentic loop"));
        }
    }

    private void invokeLocalTool(String rest) {
        if (rest.isBlank()) {
            System.out.println("Usage: /tool <tool_name> [json_arguments]");
            System.out.println("Example: /tool read {\"file_path\":\"pom.xml\"}");
            System.out.println("Example: /tool glob {\"pattern\":\"**/*.java\"}");
            System.out.println("Example: /tool bash {\"command\":\"git status\"}");
            return;
        }

        String[] parts = rest.split("\\s+", 2);
        String toolName = parts[0];
        String argsJson = parts.length > 1 ? parts[1] : "{}";

        CliTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            System.err.println("Unknown local tool: " + toolName);
            System.out.println("Available: " + String.join(", ", toolRegistry.ids()));
            return;
        }

        try {
            JsonNode args = objectMapper.readTree(argsJson);
            AgentConfig agent = agentRegistry.get(localAgentName);
            if (agent == null) agent = agentRegistry.getDefault();

            Path workDir = Paths.get(System.getProperty("user.dir"));
            ToolContext ctx = new ToolContext(sessionId, agent, permissionService, workDir, toolRegistry);

            TerminalRenderer.SpinnerHandle spinner = renderer.startSpinner(toolName);
            ToolResult result = tool.execute(args, ctx);
            spinner.stop();

            System.out.println(renderer.renderToolCallComplete(toolName, result));
        } catch (ToolExecutionException e) {
            if (e.isPermissionDenied()) {
                System.out.println(renderer.renderToolCallDenied(toolName, e.getMessage()));
            } else {
                System.err.println(renderer.red("Tool error: " + e.getMessage()));
            }
        } catch (Exception e) {
            System.err.println(renderer.red("Error: " + e.getMessage()));
        }
    }

    private void listLocalAgents() {
        java.util.List<String> agentHeaders = java.util.List.of("Agent", "Type", "Description", "Active");
        java.util.List<java.util.List<String>> agentRows = new java.util.ArrayList<>();
        for (AgentConfig a : agentRegistry.getPrimaryAgents()) {
            String active = a.getName().equals(localAgentName) ? renderer.green("●") : "";
            agentRows.add(java.util.List.of(a.getName(), "primary", a.getDisplayName(), active));
        }
        for (AgentConfig a : agentRegistry.getSubagents()) {
            agentRows.add(java.util.List.of(a.getName(), renderer.dim("subagent"), a.getDisplayName(), ""));
        }

        System.out.println(ascii.sectionHeader("Local Agents"));
        System.out.println(ascii.table(agentHeaders, agentRows));
        System.out.println();
        String switchCmd = localMode ? "/agent <name>" : "/local-agent <name>";
        System.out.println(renderer.dim("  Switch with: " + switchCmd));
    }

    private void switchLocalAgent(String name) {
        if (name.isBlank()) {
            System.out.println("Current local agent: " + localAgentName);
            System.out.println("Usage: /agent <name>");
            return;
        }

        AgentConfig agent = agentRegistry.get(name.trim());
        if (agent == null) {
            System.err.println("Unknown agent: " + name);
            return;
        }
        if (agent.isSubagent()) {
            System.err.println("Cannot switch to subagent '" + name + "'. Use a primary agent.");
            return;
        }

        localAgentName = name.trim();
        chatHistory.logSystem("Switched local agent to: " + localAgentName);
        System.out.println("Switched local agent to: " + localAgentName);
    }

    private void handlePermissions(String rest) {
        if (rest.isBlank()) {
            java.util.List<String> permHeaders = java.util.List.of("Tool", "Level", "Description");
            java.util.List<java.util.List<String>> permRows = java.util.List.of(
                    java.util.List.of("read", renderer.green("allow"), "File reading"),
                    java.util.List.of("grep", renderer.green("allow"), "Content search"),
                    java.util.List.of("glob", renderer.green("allow"), "File search"),
                    java.util.List.of("list", renderer.green("allow"), "Directory listing"),
                    java.util.List.of("edit", renderer.yellow("ask"), "File modification"),
                    java.util.List.of("bash", renderer.yellow("ask"), "Shell execution"),
                    java.util.List.of("webfetch", renderer.green("allow"), "URL fetching"),
                    java.util.List.of("task", renderer.green("allow"), "Subagent spawning")
            );
            System.out.println(ascii.sectionHeader("Tool Permissions"));
            System.out.println(ascii.table(permHeaders, permRows));
            System.out.println();
            System.out.println(renderer.dim("  /permissions <key> <allow|deny|ask>"));
            System.out.println(renderer.dim("  /permissions allow-all"));
            return;
        }

        String[] parts = rest.trim().split("\\s+", 2);
        if ("allow-all".equals(parts[0])) {
            permissionService.allowAll();
            System.out.println("All permissions set to allow for this session.");
            return;
        }

        if (parts.length < 2) {
            System.out.println("Usage: /permissions <key> <allow|deny|ask>");
            return;
        }

        String key = parts[0];
        String level = parts[1].toUpperCase();
        try {
            PermissionService.PermissionLevel pl = PermissionService.PermissionLevel.valueOf(level);
            permissionService.setUserOverride(key, pl);
            System.out.println("Set " + key + " = " + level.toLowerCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid level: " + parts[1] + ". Use allow, deny, or ask.");
        }
    }

    private void showTodos() {
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
        if (todos.isEmpty()) {
            System.out.println("No tasks in the current session.");
            return;
        }

        System.out.println(renderer.renderTodoList(todos));
    }

    private void invokeTool(String rest) {
        if (rest.isBlank()) {
            System.out.println("Usage: /tool <tool_name> [json_arguments]");
            System.out.println("Example: /tool get_document_count");
            System.out.println("Example: /tool rag_query {\"query\":\"what is kompile?\",\"maxResults\":3}");
            return;
        }

        String[] parts = rest.split("\\s+", 2);
        String toolName = parts[0];
        String argsJson = parts.length > 1 ? parts[1] : null;

        try {
            String result = mcpClient.callTool(toolName, argsJson);
            try {
                JsonNode json = objectMapper.readTree(result);
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
            } catch (Exception e) {
                System.out.println(result);
            }
        } catch (Exception e) {
            System.err.println("Error invoking tool '" + toolName + "': " + e.getMessage());
        }
    }

    private void printStatus() {
        java.util.LinkedHashMap<String, String> statusMap = new java.util.LinkedHashMap<>();

        if (localMode) {
            statusMap.put("Mode", renderer.cyan("local (direct LLM)"));
            if (chatConfig != null) {
                statusMap.put("Provider", chatConfig.getProvider());
                statusMap.put("Model", chatConfig.getModel());
            }
            statusMap.put("Session", sessionId);
            statusMap.put("Local agent", renderer.cyan(localAgentName));
            statusMap.put("Transcript", chatHistory.getTranscriptFile().toString());
            statusMap.put("Working dir", System.getProperty("user.dir"));
            statusMap.put("Local tools", toolRegistry.ids().size() + " available");
        } else {
            boolean connected = mcpClient.isConnected();
            statusMap.put("Mode", renderer.green("server"));
            statusMap.put("Connection", connected ? renderer.green("● connected") : renderer.red("● disconnected"));
            statusMap.put("Server", baseUrl);
            statusMap.put("Session", sessionId);
            statusMap.put("Server agent", renderer.cyan(agentName));
            statusMap.put("Local agent", renderer.cyan(localAgentName));
            statusMap.put("RAG", ragEnabled ? renderer.green("enabled") : renderer.dim("disabled"));
            statusMap.put("Transcript", chatHistory.getTranscriptFile().toString());
            statusMap.put("Working dir", System.getProperty("user.dir"));
            if (cachedTools != null) {
                statusMap.put("MCP tools", cachedTools.size() + " available");
            }
            statusMap.put("Local tools", toolRegistry.ids().size() + " available");
        }

        System.out.println(ascii.panel("Status", ascii.keyValueList(statusMap), AsciiRenderer.ROUNDED, "blue"));
        System.out.println();
        if (chatMemory != null) {
            System.out.println(chatMemory.getStatus());
        }
    }

    private void showHistory() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("lastN", 20);
            String rawResult = mcpClient.callTool("get_chat_history", args);

            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode messages = json.path("messages");
                int total = json.path("totalMessages").asInt(0);

                if (!messages.isArray() || messages.isEmpty()) {
                    System.out.println("No messages in server session.");
                    System.out.println("(Use /transcript to view the local conversation log)");
                    return;
                }

                System.out.println("Server history (" + messages.size() + " of " + total + " messages):");
                System.out.println("---");
                for (JsonNode msg : messages) {
                    String role = msg.path("role").asText("unknown");
                    String content = msg.path("content").asText("");
                    String label = role.toUpperCase().contains("USER") ? "You" : "Assistant";
                    System.out.println(label + ": " + content);
                    System.out.println("---");
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error fetching history: " + e.getMessage());
        }
    }

    private void showTranscript() {
        try {
            String transcript = chatHistory.readTranscript();
            if (transcript == null || transcript.isBlank()) {
                System.out.println("Transcript is empty.");
            } else {
                System.out.println(transcript);
            }
        } catch (Exception e) {
            System.err.println("Error reading transcript: " + e.getMessage());
        }
    }

    private void listConversations() {
        List<ChatHistory.ConversationSummary> conversations = ChatHistory.listConversations();
        if (conversations.isEmpty()) {
            System.out.println("No saved conversations.");
            return;
        }

        java.util.List<String> headers = java.util.List.of("Session", "Started", "Agent", "Title");
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        for (ChatHistory.ConversationSummary c : conversations) {
            String sid = c.sessionId().equals(sessionId)
                    ? renderer.green("● " + c.sessionId()) : c.sessionId();
            String title = c.title().isEmpty() ? renderer.dim("(empty)") : c.title();
            rows.add(java.util.List.of(sid, c.started(), c.agent(), title));
        }

        System.out.println(ascii.sectionHeader("Saved Conversations"));
        System.out.println(ascii.table(headers, rows));
        System.out.println();
        System.out.println(renderer.dim("  Resume: kompile chat --resume <session-id>"));
    }

    private void clearSession() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            mcpClient.callTool("clear_chat_session", args);
            chatHistory.logSystem("Session cleared.");
            System.out.println("Chat session cleared.");
        } catch (Exception e) {
            System.err.println("Error clearing session: " + e.getMessage());
        }
    }

    private void toggleRag(String arg) {
        String trimmed = arg.trim();
        if ("on".equalsIgnoreCase(trimmed)) {
            ragEnabled = true;
            updateSessionRag(true);
            chatHistory.logSystem("RAG enabled.");
            System.out.println("RAG enabled.");
        } else if ("off".equalsIgnoreCase(trimmed)) {
            ragEnabled = false;
            updateSessionRag(false);
            chatHistory.logSystem("RAG disabled.");
            System.out.println("RAG disabled.");
        } else {
            System.out.println("RAG is currently " + (ragEnabled ? "enabled" : "disabled") + ".");
            System.out.println("Usage: /rag on|off");
        }
    }

    private void updateSessionRag(boolean enabled) {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", agentName);
            args.put("enableRag", enabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", "");
            mcpClient.callTool("update_session_config", args);
        } catch (Exception e) {
            // Best effort
        }
    }

    private void listAgents() {
        try {
            String rawResult = mcpClient.callTool("list_agents", (JsonNode) null);
            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode agents = json.path("agents");
                if (!agents.isArray() || agents.isEmpty()) {
                    System.out.println("No server agents available.");
                    return;
                }
                System.out.println("Available server agents:");
                for (JsonNode agent : agents) {
                    String name = agent.path("name").asText();
                    boolean available = agent.path("available").asBoolean(false);
                    boolean isDefault = agent.path("isDefault").asBoolean(false);
                    String status = available ? "available" : "unavailable";
                    String marker = isDefault ? " (default)" : "";
                    String active = name.equals(agentName) ? " *" : "";
                    System.out.printf("  %-20s [%s]%s%s%n", name, status, marker, active);
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error listing agents: " + e.getMessage());
        }
    }

    private void switchAgent(String name) {
        if (name.isBlank()) {
            System.out.println("Current server agent: " + agentName);
            System.out.println("Usage: /agent <name> (see /agents for available agents)");
            return;
        }

        String newAgent = name.trim();
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", newAgent);
            args.put("enableRag", ragEnabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", "");
            String rawResult = mcpClient.callTool("update_session_config", args);

            JsonNode json = objectMapper.readTree(rawResult);
            if ("error".equals(json.path("status").asText())) {
                System.err.println("Error: " + json.path("error").asText());
                return;
            }

            agentName = newAgent;
            chatHistory.logSystem("Switched to server agent: " + agentName);
            System.out.println("Switched to server agent: " + agentName);
        } catch (Exception e) {
            System.err.println("Error switching agent: " + e.getMessage());
        }
    }

    private void handleConfig(String rest) {
        if (rest.isBlank()) {
            showConfig();
            return;
        }

        String[] parts = rest.trim().split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("Usage: /config <key> <value>");
            System.out.println("Keys: semanticK, keywordK, similarityThreshold, systemPrompt");
            return;
        }

        String key = parts[0];
        String value = parts[1];

        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", agentName);
            args.put("enableRag", ragEnabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", "");

            switch (key.toLowerCase()) {
                case "semantick":
                    args.put("semanticK", Integer.parseInt(value));
                    break;
                case "keywordk":
                    args.put("keywordK", Integer.parseInt(value));
                    break;
                case "similaritythreshold":
                    args.put("similarityThreshold", Double.parseDouble(value));
                    break;
                case "systemprompt":
                    args.put("systemPrompt", value);
                    break;
                default:
                    System.out.println("Unknown config key: " + key);
                    System.out.println("Keys: semanticK, keywordK, similarityThreshold, systemPrompt");
                    return;
            }

            mcpClient.callTool("update_session_config", args);
            chatHistory.logSystem("Config updated: " + key + " = " + value);
            System.out.println("Updated " + key + " = " + value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid value for " + key + ": " + value);
        } catch (Exception e) {
            System.err.println("Error updating config: " + e.getMessage());
        }
    }

    private void showConfig() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            String rawResult = mcpClient.callTool("get_session_config", args);

            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode config = json.path("configuration");
                if (config.isMissingNode()) {
                    System.out.println(rawResult);
                    return;
                }

                System.out.println("Session configuration:");
                System.out.println("  Session ID:           " + config.path("sessionId").asText(""));
                System.out.println("  Server agent:         " + config.path("agentName").asText(""));
                System.out.println("  Local agent:          " + localAgentName);
                System.out.println("  RAG enabled:          " + config.path("enableRag").asBoolean(false));
                System.out.println("  Semantic K:           " + config.path("semanticK").asInt(0));
                System.out.println("  Keyword K:            " + config.path("keywordK").asInt(0));
                System.out.println("  Similarity threshold: " + config.path("similarityThreshold").asDouble(0));
                System.out.println("  Keyword search:       " + config.path("enableKeywordSearch").asBoolean(false));
                System.out.println("  Semantic search:      " + config.path("enableSemanticSearch").asBoolean(false));
                System.out.println("  Max history:          " + config.path("maxHistoryMessages").asInt(0));
                String prompt = config.path("systemPrompt").asText("");
                if (!prompt.isEmpty()) {
                    System.out.println("  System prompt:        " + truncate(prompt, 60));
                }

                int convSize = json.path("conversationSize").asInt(-1);
                if (convSize >= 0) {
                    System.out.println("  Conversation size:    " + convSize + " messages");
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error fetching config: " + e.getMessage());
        }
    }

    private void listSessions() {
        try {
            String rawResult = mcpClient.callTool("list_chat_sessions", (JsonNode) null);
            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode sessions = json.path("sessions");
                int count = json.path("sessionCount").asInt(0);

                if (!sessions.isArray() || sessions.isEmpty()) {
                    System.out.println("No active server chat sessions.");
                    return;
                }

                System.out.println("Active server sessions (" + count + "):");
                for (JsonNode session : sessions) {
                    String sid = session.path("sessionId").asText("");
                    String agent = session.path("agentName").asText("none");
                    boolean rag = session.path("enableRag").asBoolean(false);
                    int msgCount = session.path("messageCount").asInt(0);
                    String active = sid.equals(sessionId) ? " *" : "";
                    System.out.printf("  %-30s agent=%-10s rag=%-5s msgs=%d%s%n",
                            sid, agent, rag, msgCount, active);
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error listing sessions: " + e.getMessage());
        }
    }

    private void handleMemory(String rest) {
        if (chatMemory == null) return;

        String trimmed = rest.trim().toLowerCase();

        if (trimmed.isEmpty()) {
            System.out.println(chatMemory.getStatus());
            return;
        }

        switch (trimmed) {
            case "on":
                chatMemory.setEnabled(true);
                chatHistory.logSystem("Memory enabled.");
                System.out.println("Memory enabled.");
                break;

            case "off":
                chatMemory.setEnabled(false);
                chatHistory.logSystem("Memory disabled.");
                System.out.println("Memory disabled.");
                break;

            case "persistent on":
                chatMemory.setPersistentMemoryEnabled(true);
                chatMemory.reloadPersistentMemory();
                chatHistory.logSystem("Persistent memory (MEMORY.md) enabled.");
                System.out.println("Persistent memory enabled. MEMORY.md files will be loaded.");
                break;

            case "persistent off":
                chatMemory.setPersistentMemoryEnabled(false);
                chatHistory.logSystem("Persistent memory (MEMORY.md) disabled.");
                System.out.println("Persistent memory disabled.");
                break;

            case "transcripts on":
                chatMemory.setTranscriptSearchEnabled(true);
                chatHistory.logSystem("Transcript memory search enabled.");
                System.out.println("Transcript search enabled.");
                break;

            case "transcripts off":
                chatMemory.setTranscriptSearchEnabled(false);
                chatHistory.logSystem("Transcript memory search disabled.");
                System.out.println("Transcript search disabled.");
                break;

            case "rag on":
                chatMemory.setRagSearchEnabled(true);
                chatHistory.logSystem("RAG memory search enabled.");
                System.out.println("RAG memory search enabled.");
                break;

            case "rag off":
                chatMemory.setRagSearchEnabled(false);
                chatHistory.logSystem("RAG memory search disabled.");
                System.out.println("RAG memory search disabled.");
                break;

            case "reload":
                chatMemory.reloadPersistentMemory();
                System.out.println("Persistent memory reloaded from MEMORY.md files.");
                break;

            case "show":
                String content = chatMemory.getPersistentMemoryContent();
                if (content != null && !content.isBlank()) {
                    System.out.println(content);
                } else {
                    System.out.println("No MEMORY.md files found.");
                    System.out.println("Create one at .kompile/memory/MEMORY.md (project) or ~/.kompile/memory/MEMORY.md (global).");
                }
                break;

            default:
                if (trimmed.startsWith("search ")) {
                    String query = rest.trim().substring(7).trim();
                    if (query.isEmpty()) {
                        System.out.println("Usage: /memory search <query>");
                        return;
                    }
                    System.out.println(chatMemory.search(query));
                } else {
                    System.out.println("Usage:");
                    System.out.println("  /memory                 Show memory status");
                    System.out.println("  /memory on|off          Toggle all memory");
                    System.out.println("  /memory persistent on|off  Toggle MEMORY.md loading");
                    System.out.println("  /memory transcripts on|off Toggle transcript search");
                    System.out.println("  /memory rag on|off      Toggle RAG search");
                    System.out.println("  /memory show            Show loaded MEMORY.md content");
                    System.out.println("  /memory reload          Reload MEMORY.md from disk");
                    System.out.println("  /memory search <query>  Search all memory sources");
                }
                break;
        }
    }

    private void handleRecall(String rest) {
        if (chatMemory == null) return;

        if (rest.isBlank()) {
            System.out.println("Usage: /recall <query>");
            System.out.println("Search across MEMORY.md, previous conversations, and RAG documents.");
            return;
        }

        System.out.println(chatMemory.search(rest.trim()));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
