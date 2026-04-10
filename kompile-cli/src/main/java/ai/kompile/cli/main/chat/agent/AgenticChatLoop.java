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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.OutputTruncator;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agentic chat loop with proper terminal rendering, output truncation,
 * and context compaction. Comparable to OpenCode's SessionPrompt.loop().
 * <p>
 * Supports two backends:
 * <ul>
 *   <li><b>Server mode</b>: Streams via kompile-app /api/agents/chat/stream endpoint</li>
 *   <li><b>Direct mode</b>: Calls LLM APIs directly via DirectLlmClient (no server needed)</li>
 * </ul>
 * <p>
 * Flow:
 * 1. Send user message + tool definitions to agent
 * 2. Stream response, collecting text + tool call requests
 * 3. Execute requested tools locally with spinner + colored output
 * 4. Send tool results back as follow-up message
 * 5. Repeat until agent returns text-only response (no tool calls)
 */
public class AgenticChatLoop {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read", "grep", "glob", "list", "todoread", "webfetch",
            "transcript_search", "rag_search", "graph_search");

    private final String baseUrl; // null for direct mode
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final AgentRegistry agentRegistry;
    private final Path workingDirectory;
    private final TerminalRenderer renderer;
    private final OutputTruncator truncator;
    private final CompactionService compactionService;
    private final DirectLlmClient directLlmClient; // null for server mode
    private final String agentsMdContent; // loaded AGENTS.md content
    private final ai.kompile.cli.main.chat.tools.BackgroundProcessManager processManager; // background process tracking
    private ToolResultStore toolResultStore; // persists tool outputs to disk
    private ai.kompile.cli.main.chat.ChatSessionMetrics sessionMetrics; // session metrics
    private volatile AgentConfig currentAgentConfig; // currently active agent config (can be updated with roles)

    // Conversation history for compaction
    private final List<CompactionService.ConversationEntry> conversationHistory = new ArrayList<>();

    // Cancel signal - set by ChatRepl when user presses Escape
    private volatile AtomicBoolean cancelSignal;

    // Callback fired once when the first output (step indicator or text chunk) is printed.
    // Used by ChatRepl to stop the generating spinner.
    private volatile Runnable onFirstOutput;

    /**
     * Server mode constructor - uses kompile-app REST endpoint.
     */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory) {
        this(baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workingDirectory, null, null);
    }

    /**
     * Dual mode constructor - uses DirectLlmClient when baseUrl is null.
     */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory,
                            DirectLlmClient directLlmClient) {
        this(baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workingDirectory, directLlmClient, null);
    }

    /**
     * Full constructor with background process manager.
     */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory,
                            DirectLlmClient directLlmClient,
                            ai.kompile.cli.main.chat.tools.BackgroundProcessManager processManager) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.agentRegistry = agentRegistry;
        this.workingDirectory = workingDirectory;
        this.renderer = new TerminalRenderer();
        this.truncator = new OutputTruncator();
        this.compactionService = new CompactionService(objectMapper);
        this.directLlmClient = directLlmClient;
        this.processManager = processManager;

        // Set up exit callback for background process notifications
        if (processManager != null) {
            final TerminalRenderer r = this.renderer;
            processManager.setExitCallback(entry -> {
                String durationStr = ai.kompile.cli.main.chat.tools.ProcessManagementTool.formatDuration(
                        entry.getDuration());
                String desc = entry.getDescription() != null ? entry.getDescription() : entry.getCommand();
                int code = entry.getExitCode() != null ? entry.getExitCode() : -1;

                String notification;
                if (code == 0) {
                    notification = r.dim("[process:" + entry.getId() + "] exited with code 0 (took "
                            + durationStr + ") — \"" + desc + "\"");
                } else {
                    notification = r.red("[process:" + entry.getId() + "] exited with code " + code
                            + " (took " + durationStr + ") — \"" + desc + "\"");
                }
                System.out.println();
                System.out.println(notification);
                System.out.println();
            });
        }

        // Initialize current agent config with default
        this.currentAgentConfig = agentRegistry.getDefault();

        // Load AGENTS.md files from project hierarchy
        AgentsMdLoader loader = new AgentsMdLoader(workingDirectory);
        this.agentsMdContent = loader.load();
    }

    /**
     * Sets the session metrics tracker for recording tool calls, steps, and token usage.
     */
    public void setSessionMetrics(ai.kompile.cli.main.chat.ChatSessionMetrics metrics) {
        this.sessionMetrics = metrics;
    }

    /**
     * Sets a callback to be fired once when the first output is produced.
     * The callback is consumed after firing (set to null).
     */
    public void setOnFirstOutput(Runnable onFirstOutput) {
        this.onFirstOutput = onFirstOutput;
    }

    /**
     * Fire the onFirstOutput callback if set, then clear it.
     */
    private void fireFirstOutput() {
        Runnable cb = this.onFirstOutput;
        if (cb != null) {
            this.onFirstOutput = null;
            cb.run();
        }
    }

    /**
     * Sets the cancel signal for interrupting in-progress operations.
     * The signal is checked between agentic steps and during LLM streaming.
     */
    public void setCancelSignal(AtomicBoolean cancelSignal) {
        this.cancelSignal = cancelSignal;
        if (directLlmClient != null) {
            directLlmClient.setCancelSignal(cancelSignal);
        }
    }

    /**
     * Updates the current agent configuration (e.g., when a role is assigned).
     */
    public void setAgentConfig(AgentConfig agentConfig) {
        this.currentAgentConfig = agentConfig;
    }

    /**
     * Gets the current agent configuration.
     */
    public AgentConfig getCurrentAgentConfig() {
        return currentAgentConfig;
    }

    /**
     * Check if cancellation has been requested.
     */
    private boolean isCancelled() {
        AtomicBoolean signal = this.cancelSignal;
        return signal != null && signal.get();
    }

    /**
     * Whether this loop operates in direct LLM mode (no server).
     */
    public boolean isDirectMode() {
        return directLlmClient != null && (baseUrl == null || baseUrl.isEmpty());
    }

    /**
     * Build the full system prompt by combining the agent's base prompt
     * with AGENTS.md content and tool result store info.
     */
    private String buildSystemPrompt(AgentConfig agent) {
        StringBuilder sb = new StringBuilder();

        String base = agent.getSystemPrompt();
        if (base != null && !base.isBlank()) {
            sb.append(base.strip());
        }

        // Tool result store info
        if (toolResultStore != null) {
            sb.append("\n\n# Tool Result Files\n\n");
            sb.append("All tool call outputs are saved to: ").append(toolResultStore.getResultDir()).append("\n");
            sb.append("After context compaction, you can use the `read` tool to access any previous tool output.\n");
            sb.append("Compacted tool results include the file path — use `read` with that path to get the full output.\n");
            sb.append("Use `glob` with pattern \"").append(toolResultStore.getResultDir()).append("/*.txt\" to list all saved results.\n");

            // If there are already saved results, include a summary
            String summary = toolResultStore.generateResultsSummary();
            if (!summary.isEmpty()) {
                sb.append("\n").append(summary);
            }
        }

        // AGENTS.md content
        if (agentsMdContent != null && !agentsMdContent.isEmpty()) {
            sb.append("\n\n# Project Instructions (from AGENTS.md)\n\n");
            sb.append(agentsMdContent);
        }

        return sb.toString();
    }

    /**
     * Get the loaded AGENTS.md content (for display by /help or /config).
     */
    public String getAgentsMdContent() {
        return agentsMdContent;
    }

    /**
     * Restore conversation history from previous session turns.
     * Replays turns into the DirectLlmClient and conversation history.
     */
    public void restoreHistory(java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns) {
        if (directLlmClient != null) {
            for (var turn : turns) {
                directLlmClient.addToHistory(turn.role(), turn.content());
            }
        }
        // Also restore into conversation history for compaction tracking
        for (var turn : turns) {
            if ("user".equals(turn.role())) {
                conversationHistory.add(CompactionService.ConversationEntry.user(turn.content()));
            } else if ("assistant".equals(turn.role())) {
                conversationHistory.add(CompactionService.ConversationEntry.assistant(turn.content()));
            }
        }
    }

    /**
     * Run the agentic chat loop for a single user message.
     */
    public String chat(String message, String sessionId, String agentName,
                        String serverAgent, boolean ragEnabled) {
        AgentConfig agent = agentRegistry.get(agentName);
        if (agent == null) agent = agentRegistry.getDefault();

        // Initialize tool result store for this session
        this.toolResultStore = new ToolResultStore(sessionId);

        ToolContext toolContext = new ToolContext(
                sessionId, agent, permissionService, workingDirectory, toolRegistry);

        ArrayNode toolDefs = toolRegistry.buildToolDefinitions(agent);

        // Compose system prompt: agent prompt + AGENTS.md content + result store info
        String systemPrompt = buildSystemPrompt(agent);

        StringBuilder fullResponse = new StringBuilder();
        int step = 0;
        int maxSteps = agent.getMaxSteps();

        String currentMessage = message;
        List<ToolCallResult> pendingToolResults = null;

        // Track conversation for compaction
        conversationHistory.add(CompactionService.ConversationEntry.user(message));

        while (step < maxSteps) {
            // Check cancellation before each step
            if (isCancelled()) {
                System.out.println("\n" + renderer.yellow("  ⊘ Cancelled"));
                fullResponse.append("\n[Cancelled by user]");
                break;
            }

            step++;
            if (sessionMetrics != null) sessionMetrics.recordAgenticStep();
            fireFirstOutput();
            System.out.println(renderer.renderAgentTurnStart(step, maxSteps));

            // Check compaction
            if (compactionService.needsCompaction(conversationHistory)) {
                CompactionService.CompactionResult compResult =
                        compactionService.compact(conversationHistory);
                if (compResult.isCompacted()) {
                    System.out.println(renderer.renderCompactionNotice(
                            compResult.getTokensBefore(), compResult.getTokensAfter()));
                    if (sessionMetrics != null) {
                        sessionMetrics.recordCompaction(compResult.getTokensBefore(), compResult.getTokensAfter());
                    }
                    conversationHistory.clear();
                    conversationHistory.addAll(compResult.getEntries());
                }
            }

            StreamResult result;
            if (isDirectMode()) {
                result = streamDirectTurn(
                        currentMessage, systemPrompt, toolDefs, pendingToolResults);
            } else {
                result = streamServerTurn(
                        currentMessage, sessionId, serverAgent, ragEnabled,
                        systemPrompt, toolDefs, pendingToolResults);
            }

            // Check if cancelled during streaming
            if (isCancelled()) {
                if (!result.text.isEmpty()) {
                    fullResponse.append(result.text);
                }
                System.out.println("\n" + renderer.yellow("  ⊘ Cancelled"));
                fullResponse.append("\n[Cancelled by user]");
                break;
            }

            // Accumulate text output
            if (!result.text.isEmpty()) {
                fullResponse.append(result.text);
                conversationHistory.add(
                        CompactionService.ConversationEntry.assistant(result.text));
            }

            // If no tool calls, we're done
            if (result.toolCalls.isEmpty()) {
                break;
            }

            // Execute tool calls with proper rendering
            System.out.println();
            List<ToolCallResult> toolResults = new ArrayList<>();

            // Track read-only tools for context grouping
            Map<String, Integer> readOnlyToolCounts = new LinkedHashMap<>();
            List<ToolCallResult> readOnlyResults = new ArrayList<>();

            for (ToolCallRequest call : result.toolCalls) {
                // Check cancellation before each tool
                if (isCancelled()) {
                    System.out.println("\n" + renderer.yellow("  ⊘ Cancelled — skipping remaining tools"));
                    fullResponse.append("\n[Cancelled by user — tools skipped]");
                    break;
                }

                boolean isReadOnly = READ_ONLY_TOOLS.contains(call.name);

                // Start spinner for long-running tools
                TerminalRenderer.SpinnerHandle spinner = null;
                if (!isReadOnly) {
                    System.out.println(renderer.renderToolCallStart(call.name, getToolDescription(call)));
                    spinner = renderer.startSpinner(call.name);
                }

                try {
                    CliTool tool = toolRegistry.get(call.name);
                    if (tool == null) {
                        if (spinner != null) spinner.stop();
                        String errMsg = "Unknown tool: " + call.name;
                        System.out.println(renderer.renderToolCallComplete(call.name,
                                ToolResult.error(errMsg)));
                        toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                        if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                        continue;
                    }

                    long toolStart = System.currentTimeMillis();
                    ToolResult toolResult = tool.execute(call.arguments, toolContext);
                    long toolDurationMs = System.currentTimeMillis() - toolStart;

                    // Truncate large outputs
                    OutputTruncator.TruncationResult truncResult =
                            truncator.truncate(toolResult.getOutput(), call.name);
                    if (truncResult.isTruncated()) {
                        toolResult = new ToolResult(toolResult.getTitle(),
                                truncResult.getOutput(), toolResult.getMetadata(), toolResult.isError());
                    }

                    if (spinner != null) spinner.stop();

                    if (isReadOnly && !toolResult.isError()) {
                        // Group read-only tools
                        readOnlyToolCounts.merge(call.name, 1, Integer::sum);
                        readOnlyResults.add(new ToolCallResult(call.id, call.name,
                                toolResult.getOutput(), false));
                    } else {
                        System.out.println(renderer.renderToolCallComplete(call.name, toolResult));
                    }

                    // Save result to disk for later access
                    String argsStr = call.arguments != null ? call.arguments.toString() : "";
                    Path savedPath = toolResultStore.save(
                            call.name, call.id, argsStr,
                            toolResult.getOutput(), toolResult.isError());

                    // Append file path to output so compacted summaries reference it
                    String outputWithPath = toolResult.getOutput();
                    if (savedPath != null) {
                        outputWithPath += "\n[saved to: " + savedPath + "]";
                    }

                    toolResults.add(new ToolCallResult(call.id, call.name,
                            toolResult.getOutput(), toolResult.isError()));

                    // Record metrics
                    if (sessionMetrics != null) {
                        sessionMetrics.recordToolCall(call.name, toolResult.isError(), toolDurationMs);
                    }

                    // Track in conversation history (include file path for compaction)
                    conversationHistory.add(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, outputWithPath));

                } catch (ToolExecutionException e) {
                    if (spinner != null) spinner.stop();

                    if (e.isPermissionDenied()) {
                        System.out.println(renderer.renderToolCallDenied(call.name, e.getMessage()));
                    } else {
                        System.out.println(renderer.renderToolCallComplete(call.name,
                                ToolResult.error(e.getMessage())));
                    }

                    String errMsg = "Error: " + e.getMessage();
                    toolResultStore.save(call.name, call.id, null, errMsg, true);
                    toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                    if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                }
            }

            // Render context group for read-only tools
            if (!readOnlyToolCounts.isEmpty()) {
                System.out.println(renderer.renderContextGroup(readOnlyToolCounts));
            }

            // Set up next iteration with tool results
            pendingToolResults = toolResults;
            currentMessage = null;
        }

        if (step >= maxSteps) {
            System.out.println(renderer.renderMaxStepsWarning(maxSteps));
            fullResponse.append("\n[Agent reached maximum steps (").append(maxSteps).append(")]");
        }

        // Cleanup old truncation files
        truncator.cleanupOldFiles();

        return fullResponse.toString();
    }

    private String getToolDescription(ToolCallRequest call) {
        if (call.arguments == null) return "";
        if (call.arguments.has("file_path")) {
            return call.arguments.path("file_path").asText("");
        }
        if (call.arguments.has("command")) {
            String cmd = call.arguments.path("command").asText("");
            return cmd.length() > 60 ? cmd.substring(0, 57) + "..." : cmd;
        }
        if (call.arguments.has("pattern")) {
            return call.arguments.path("pattern").asText("");
        }
        if (call.arguments.has("description")) {
            return call.arguments.path("description").asText("");
        }
        return "";
    }

    // ========================================================================
    // Direct LLM mode (no server)
    // ========================================================================

    private StreamResult streamDirectTurn(String message, String systemPrompt,
                                           ArrayNode toolDefs, List<ToolCallResult> toolResults) {
        StreamResult result = new StreamResult();

        // Convert tool results to DirectLlmClient format
        List<DirectLlmClient.ToolCallResultInput> directToolResults = null;
        if (toolResults != null && !toolResults.isEmpty()) {
            directToolResults = new ArrayList<>();
            for (ToolCallResult tr : toolResults) {
                directToolResults.add(new DirectLlmClient.ToolCallResultInput(
                        tr.callId, tr.toolName, tr.output, tr.isError));
            }
        }

        DirectLlmClient.StreamResult directResult =
                directLlmClient.streamChat(message, systemPrompt, toolDefs, directToolResults);

        // Record token usage from API response
        if (sessionMetrics != null) {
            sessionMetrics.recordTokenUsage(
                    directResult.inputTokens, directResult.outputTokens,
                    directResult.cacheReadTokens, directResult.cacheCreationTokens);
        }

        result.text = directResult.text;
        for (DirectLlmClient.ToolCallOutput tc : directResult.toolCalls) {
            ToolCallRequest req = new ToolCallRequest();
            req.id = tc.id;
            req.name = tc.name;
            req.arguments = tc.arguments;
            result.toolCalls.add(req);
        }

        return result;
    }

    // ========================================================================
    // Server mode (kompile-app)
    // ========================================================================

    private StreamResult streamServerTurn(String message, String sessionId, String serverAgent,
                                           boolean ragEnabled, String systemPrompt,
                                           ArrayNode toolDefs, List<ToolCallResult> toolResults) {
        StreamResult result = new StreamResult();

        try {
            ObjectNode request = objectMapper.createObjectNode();
            if (message != null) {
                request.put("message", message);
            }
            request.put("agentName", serverAgent);
            request.put("enableRag", ragEnabled);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);

            if (toolDefs != null && toolDefs.size() > 0) {
                request.set("tools", toolDefs);
            }

            if (toolResults != null && !toolResults.isEmpty()) {
                ArrayNode resultsArray = objectMapper.createArrayNode();
                for (ToolCallResult tr : toolResults) {
                    ObjectNode trNode = objectMapper.createObjectNode();
                    trNode.put("tool_call_id", tr.callId);
                    trNode.put("name", tr.toolName);
                    trNode.put("output", tr.output);
                    trNode.put("error", tr.isError);
                    resultsArray.add(trNode);
                }
                request.set("toolResults", resultsArray);
            }

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                request.put("systemPromptOverride", systemPrompt);
            }

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
                result.text = "[Agent HTTP error " + response.statusCode() + "]";
                return result;
            }

            // Parse SSE stream
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String eventType = null;
                StringBuilder dataBuffer = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (isCancelled()) {
                        break;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring(5).trim());
                    } else if (line.isEmpty() && eventType != null) {
                        String data = dataBuffer.toString();
                        processStreamEvent(eventType, data, result);
                        eventType = null;
                        dataBuffer.setLength(0);
                    }
                }
            }

        } catch (Exception e) {
            result.text = "[Error: " + e.getMessage() + "]";
        }

        return result;
    }

    private void processStreamEvent(String eventType, String data, StreamResult result) {
        switch (eventType) {
            case "chunk":
                String chunk = data;
                if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                    try { chunk = objectMapper.readValue(chunk, String.class); }
                    catch (Exception ignored) {}
                }
                System.out.print(chunk);
                System.out.flush();
                result.text += chunk;
                break;

            case "tool_call":
                try {
                    JsonNode toolCall = objectMapper.readTree(data);
                    ToolCallRequest req = new ToolCallRequest();
                    req.id = toolCall.path("id").asText("call_" + result.toolCalls.size());
                    req.name = toolCall.path("name").asText("");
                    req.arguments = toolCall.path("arguments");
                    result.toolCalls.add(req);
                } catch (Exception e) {
                    System.err.println(renderer.red("  [Error parsing tool call: " + e.getMessage() + "]"));
                }
                break;

            case "start":
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    if (!agent.isEmpty()) {
                        System.out.println(renderer.dim("[Agent: " + agent + "]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "sources":
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        System.out.println(renderer.dim("[Retrieved " + sources.size() + " documents]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "stats":
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    long durationMs = stats.path("durationMs").asLong(0);
                    if (durationMs > 0) {
                        System.out.println(renderer.dim("  [completed in " + durationMs + "ms]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "error":
                try {
                    JsonNode error = objectMapper.readTree(data);
                    String msg = error.path("message").asText(data);
                    System.err.println(renderer.red("\n[Error: " + msg + "]"));
                    result.text += "\n[Error: " + msg + "]";
                } catch (Exception e) {
                    System.err.println(renderer.red("\n[Error: " + data + "]"));
                }
                break;

            case "complete":
            case "cancelled":
                break;
        }
    }

    public TerminalRenderer getRenderer() {
        return renderer;
    }

    // ========================================================================
    // Internal data classes
    // ========================================================================

    static class StreamResult {
        String text = "";
        List<ToolCallRequest> toolCalls = new ArrayList<>();
    }

    static class ToolCallRequest {
        String id;
        String name;
        JsonNode arguments;
    }

    static class ToolCallResult {
        String callId;
        String toolName;
        String output;
        boolean isError;

        ToolCallResult(String callId, String toolName, String output, boolean isError) {
            this.callId = callId;
            this.toolName = toolName;
            this.output = output;
            this.isError = isError;
        }
    }
}
