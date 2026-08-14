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

import ai.kompile.utils.StringUtils;
import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Handles chat message dispatch: inline RAG, local/agentic chat, server streaming, and
 * SSE event parsing. Extracted from ChatRepl to reduce its size.
 */
public class ChatMessageHandler {

    private final ChatRepl repl;
    private final McpSseClient mcpClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String sessionId;
    private final boolean localMode;
    private final ChatHistory chatHistory;
    private final ChatMemory chatMemory;
    private final ChatSessionMetrics sessionMetrics;
    private final TerminalRenderer renderer;
    private final AsciiRenderer asciiRenderer;
    private final AgenticChatLoop agenticLoop;
    private final BackgroundTaskManager backgroundTaskManager;
    private final MessageQueue messageQueue;
    private final AtomicBoolean cancelSignal;
    private final List<ChatRepl.PendingAttachment> pendingAttachments;
    private final Object turnDispatchLock = new Object();
    private final AtomicReference<Thread> activeDispatchThread = new AtomicReference<>();

    // Mutable llmBusy flag — read/written by ChatRepl main loop as well
    // We access it via ChatRepl accessors to keep a single source of truth.

    public ChatMessageHandler(
            ChatRepl repl,
            McpSseClient mcpClient,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String sessionId,
            boolean localMode,
            ChatHistory chatHistory,
            ChatMemory chatMemory,
            ChatSessionMetrics sessionMetrics,
            TerminalRenderer renderer,
            AsciiRenderer asciiRenderer,
            AgenticChatLoop agenticLoop,
            BackgroundTaskManager backgroundTaskManager,
            MessageQueue messageQueue,
            AtomicBoolean cancelSignal,
            List<ChatRepl.PendingAttachment> pendingAttachments) {
        this.repl = repl;
        this.mcpClient = mcpClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sessionId = sessionId;
        this.localMode = localMode;
        this.chatHistory = chatHistory;
        this.chatMemory = chatMemory;
        this.sessionMetrics = sessionMetrics;
        this.renderer = renderer;
        this.asciiRenderer = asciiRenderer;
        this.agenticLoop = agenticLoop;
        this.backgroundTaskManager = backgroundTaskManager;
        this.messageQueue = messageQueue;
        this.cancelSignal = cancelSignal;
        this.pendingAttachments = pendingAttachments;
        ChatCompleter.setQueueSupplier(() -> this.messageQueue.getAll().stream()
                .map(MessageQueue.QueuedMessage::getContent)
                .collect(Collectors.toList()));
    }

    // ========================================================================
    // Entry point
    // ========================================================================

    /**
     * Handles a user chat message entered at the REPL prompt.
     * Auto-queues the message if the LLM is already busy.
     */
    public void handleChatMessage(String message) {
        // Crawl/headless runs deliberately stay synchronous so callers do not
        // tear down the transcript before the one requested turn completes.
        if (repl.isForceAgentic()) {
            cancelSignal.set(false);
            handleAcceptedChatMessage(message);
            return;
        }

        synchronized (turnDispatchLock) {
            if (repl.isLlmBusy()) {
                enqueueChatMessage(message);
                return;
            }

            // Reserve the turn before starting the worker. Without this, a fast
            // second Enter can race the worker and launch two model turns.
            repl.setLlmBusy(true);
            cancelSignal.set(false);
            Thread dispatchThread = new Thread(() -> {
                try {
                    handleAcceptedChatMessage(message);
                } finally {
                    activeDispatchThread.compareAndSet(Thread.currentThread(), null);
                }
            }, "standard-chat-dispatch");
            dispatchThread.setDaemon(true);
            activeDispatchThread.set(dispatchThread);
            dispatchThread.start();
        }
    }

    /**
     * Cancels the currently accepted turn. The shared signal cooperatively stops
     * stream parsers and tools; interrupting the owner thread also wakes blocking
     * HTTP sends and process waits immediately.
     */
    public boolean requestCancel() {
        cancelSignal.set(true);
        Thread active = activeDispatchThread.get();
        if (active != null && active != Thread.currentThread()) {
            active.interrupt();
        }
        return active != null;
    }

    private void enqueueChatMessage(String message) {
        messageQueue.enqueue(message);
        repl.requestStatusRedraw();
        sessionMetrics.recordMessageQueued();
        int queueSize = messageQueue.size();
        emitLine("");
        emitLine(renderer.yellow("  ⏳ Queued ") + renderer.dim("(" + queueSize + " pending)"));
        emitLine(renderer.dim("     → ") + StringUtils.truncate(message, 60));
        if (repl.isAutoDequeueEnabled()) {
            emitLine(renderer.dim("     Will auto-send when current task completes"));
        } else {
            emitLine(renderer.dim("     Use /queue-send to send manually"));
        }
        emitLine("");
        chatHistory.logUserMessage("(queued) " + message);
    }

    private void handleAcceptedChatMessage(String message) {
        sessionMetrics.recordUserTurn(message);
        if (!repl.isForceAgentic()) {
            chatHistory.logUserMessage(message);
        }

        try {
            if (repl.isForceAgentic()) {
                // Crawl profile: every ordinary message is an agentic control/reasoning turn.
                runAgenticChat(message);
            } else if (localMode) {
                // In local mode, all messages go through the agentic loop
                handleLocalChat(message);
            } else {
                handleServerChat(message);
            }
        } finally {
            ChatCompleter.setActivity(null);
            repl.requestStatusRedraw();
            synchronized (turnDispatchLock) {
                // completeTaskWithAutoDequeue may synchronously call back into
                // handleChatMessage. The re-entrant lock keeps llmBusy reserved
                // across that hand-off so a newly typed message cannot overtake it.
                repl.completeTaskWithAutoDequeue();
            }
        }
    }

    private void emitLine(String line) {
        ChatCompleter.printAbove(line);
    }

    private void startActivityIndicator() {
        ChatCompleter.setActivity("Thinking");
        repl.requestStatusRedraw();
        // With an active LineReader the persistent status bar renders the
        // foreground RUNNING task. A carriage-return spinner would overwrite
        // the draft the user is typing for the queue.
        if (!ChatCompleter.hasLineReader()) {
            repl.printGeneratingIndicator();
        }
    }

    // ========================================================================
    // Local / agentic chat
    // ========================================================================

    public void handleLocalChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        // Load pending attachments and pass to agentic loop
        List<DirectLlmClient.AttachmentInput> attachments = loadAttachments();
        if (attachments != null) {
            agenticLoop.setPendingAttachments(attachments);
        }

        emitLine("");
        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("LLM response: " + StringUtils.truncate(message, 50));
        startActivityIndicator();
        agenticLoop.setOnFirstOutput(repl::stopGeneratingSpinner);
        long turnStart = System.currentTimeMillis();

        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, repl.getLocalAgentName(), repl.getAgentName(), false);

            repl.stopGeneratingSpinner();
            long turnDuration = System.currentTimeMillis() - turnStart;
            emitLine("");
            chatHistory.logAgentResponse(repl.getLocalAgentName(), response, turnDuration);
            task.appendOutput(response);
            sessionMetrics.recordAssistantTurn(response, turnDuration);
        } catch (Exception e) {
            repl.stopGeneratingSpinner();
            emitLine(renderer.red("Error in chat: " + e.getMessage()));
            task.setError(e);
        }
    }

    // ========================================================================
    // Server RAG chat
    // ========================================================================

    public void handleServerChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("LLM response: " + StringUtils.truncate(message, 50));
        startActivityIndicator();
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("message", enrichedMessage);
            args.put("enableRag", repl.isRagEnabled());
            args.put("maxResults", 10);
            args.put("similarityThreshold", 0.5);

            String rawResponse = mcpClient.callTool("send_chat_message", args);
            repl.stopGeneratingSpinner();

            try {
                JsonNode json = objectMapper.readTree(rawResponse);
                String answer = json.path("answer").asText(null);
                int docsRetrieved = json.path("documentsRetrieved").asInt(0);
                long timeMs = json.path("executionTimeMs").asLong(0);

                if (answer != null) {
                    emitLine("");
                    emitLine(asciiRenderer.renderMarkdown(answer));
                } else {
                    answer = rawResponse;
                    emitLine("");
                    emitLine(asciiRenderer.renderMarkdown(rawResponse));
                }

                if (docsRetrieved > 0) {
                    emitLine("  [" + docsRetrieved + " docs retrieved, " + timeMs + "ms]");
                    sessionMetrics.recordRagQuery(docsRetrieved);
                }
                emitLine("");

                String finalAnswer = answer != null ? answer : rawResponse;
                sessionMetrics.recordAssistantTurn(finalAnswer, timeMs);
                chatHistory.logAssistantMessage(finalAnswer, docsRetrieved, timeMs);
            } catch (Exception e) {
                emitLine("");
                emitLine(asciiRenderer.renderMarkdown(rawResponse));
                emitLine("");
                chatHistory.logAssistantMessage(rawResponse, 0, 0);
            }
        } catch (Exception e) {
            repl.stopGeneratingSpinner();
            emitLine(renderer.red("Error sending message: " + e.getMessage()));
            task.setError(e);
        }
    }

    // ========================================================================
    // Agent streaming (/ask command) via REST /api/agents/chat/stream SSE
    // ========================================================================

    public void streamAgentChat(String message) {
        if (message.isBlank()) {
            emitLine("Usage: /ask <message>");
            emitLine("Sends a message to the configured agent with streaming output.");
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

        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("Streaming LLM response: " + StringUtils.truncate(message, 40));
        startActivityIndicator();
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("message", enrichedMessage);
            request.put("agentName", repl.getAgentName());
            request.put("enableRag", repl.isRagEnabled());
            request.put("ragMaxResults", 5);
            request.put("ragSimilarityThreshold", 0.5);
            request.put("includeKeywordSearch", true);
            request.put("includeSemanticSearch", true);
            request.put("injectMcpTools", true);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);

            String body = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(repl.getBaseUrl() + "/api/agents/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                repl.stopGeneratingSpinner();
                emitLine(renderer.red("Agent stream failed: HTTP " + response.statusCode()));
                return;
            }

            repl.stopGeneratingSpinner();
            ChatCompleter.setActivity("Responding");
            repl.requestStatusRedraw();
            emitLine("");

            // Accumulate full response for transcript
            StringBuilder fullResponse = new StringBuilder();
            long[] durationMs = {0};
            StreamingMarkdownRenderer streamingMd =
                    new StreamingMarkdownRenderer(asciiRenderer, this::emitLine);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String eventType = null;
                StringBuilder dataBuffer = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (cancelSignal.get()) {
                        streamingMd.flush();
                        emitLine("\n" + renderer.yellow("  ⊘ Cancelled"));
                        fullResponse.append("\n[Cancelled by user]");
                        break;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring(5).trim());
                    } else if (line.isEmpty() && eventType != null) {
                        handleStreamEvent(eventType, dataBuffer.toString(), fullResponse, durationMs, streamingMd);
                        eventType = null;
                        dataBuffer.setLength(0);
                    }
                }
            }
            streamingMd.flush();

            emitLine("");

            String responseText = fullResponse.toString();
            chatHistory.logAgentResponse(repl.getAgentName(), responseText, durationMs[0]);
            task.appendOutput(responseText);
            sessionMetrics.recordAssistantTurn(responseText, durationMs[0]);

        } catch (Exception e) {
            repl.stopGeneratingSpinner();
            emitLine(renderer.red("Error in agent stream: " + e.getMessage()));
            task.setError(e);
        } finally {
            ChatCompleter.setActivity(null);
            repl.requestStatusRedraw();
            repl.completeTaskWithAutoDequeue();
        }
    }

    // ========================================================================
    // Agentic tool loop (/agent-chat command)
    // ========================================================================

    public void agenticChat(String message) {
        try {
            runAgenticChat(message);
        } finally {
            ChatCompleter.setActivity(null);
            repl.requestStatusRedraw();
            synchronized (turnDispatchLock) {
                repl.completeTaskWithAutoDequeue();
            }
        }
    }

    private void runAgenticChat(String message) {
        if (message.isBlank()) {
            emitLine("Usage: /agent-chat <message>");
            emitLine("Sends a message through the agentic tool loop with local tool execution.");
            emitLine("Current local agent: " + repl.getLocalAgentName());
            emitLine("Available local agents: " + String.join(", ",
                    repl.getAgentRegistry().getPrimaryAgents().stream()
                            .map(a -> a.getName()).toArray(String[]::new)));
            return;
        }

        chatHistory.logUserMessage(repl.isForceAgentic() ? message : "/agent-chat " + message);

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        emitLine("");

        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("Agentic chat: " + StringUtils.truncate(message, 40));
        startActivityIndicator();
        agenticLoop.setOnFirstOutput(repl::stopGeneratingSpinner);
        long turnStart = System.currentTimeMillis();
        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, repl.getLocalAgentName(), repl.getAgentName(), repl.isRagEnabled());

            repl.stopGeneratingSpinner();
            long turnDuration = System.currentTimeMillis() - turnStart;
            emitLine("");
            chatHistory.logAgentResponse(repl.getLocalAgentName(), response, turnDuration);
            task.appendOutput(response);
            sessionMetrics.recordAssistantTurn(response, turnDuration);

        } catch (Exception e) {
            repl.stopGeneratingSpinner();
            emitLine(renderer.red("Error in agentic chat: " + e.getMessage()));
            task.setError(e);
        }
    }

    // ========================================================================
    // SSE event handling
    // ========================================================================

    public void handleStreamEvent(String eventType, String data,
                                  StringBuilder fullResponse, long[] durationMs,
                                  StreamingMarkdownRenderer streamingMd) {
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
                streamingMd.accept(chunk);
                fullResponse.append(chunk);
                break;

            case "start":
                streamingMd.flush();
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    emitLine(renderer.dim("[Agent: " + agent + "]"));
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "sources":
                streamingMd.flush();
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        emitLine(renderer.dim("[Retrieved " + sources.size() + " documents]"));
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "stats":
                streamingMd.flush();
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    durationMs[0] = stats.path("durationMs").asLong(0);
                    if (durationMs[0] > 0) {
                        emitLine(renderer.dim("  [completed in " + durationMs[0] + "ms]"));
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "error":
                streamingMd.flush();
                try {
                    JsonNode error = objectMapper.readTree(data);
                    emitLine(renderer.red("Error: " + error.path("message").asText(data)));
                } catch (Exception e) {
                    emitLine(renderer.red("Error: " + data));
                }
                break;

            case "complete":
                streamingMd.flush();
                break;

            case "cancelled":
                streamingMd.flush();
                emitLine("[Cancelled]");
                break;

            default:
                break;
        }
    }

    // ========================================================================
    // Attachment loading
    // ========================================================================

    /**
     * Load pending attachments into DirectLlmClient.AttachmentInput format,
     * reading file contents (base64 for images, text for text files).
     */
    public List<DirectLlmClient.AttachmentInput> loadAttachments() {
        if (pendingAttachments.isEmpty()) return null;

        List<DirectLlmClient.AttachmentInput> loaded = new ArrayList<>();
        for (ChatRepl.PendingAttachment att : pendingAttachments) {
            try {
                if (att.isImage()) {
                    byte[] bytes = Files.readAllBytes(att.path());
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    loaded.add(new DirectLlmClient.AttachmentInput(
                            att.path().toString(), att.mimeType(), true, base64, null));
                } else {
                    String text = Files.readString(att.path());
                    loaded.add(new DirectLlmClient.AttachmentInput(
                            att.path().toString(), att.mimeType(), false, null, text));
                }
            } catch (Exception e) {
                emitLine(renderer.yellow("Warning: Could not read attachment "
                        + att.path().getFileName() + ": " + e.getMessage()));
            }
        }

        // Clear pending after loading
        pendingAttachments.clear();
        return loaded.isEmpty() ? null : loaded;
    }

}
