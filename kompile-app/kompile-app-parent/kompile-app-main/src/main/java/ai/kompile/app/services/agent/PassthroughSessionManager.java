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

package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.PassthroughSessionRequest;
import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.service.ChatHistoryService;
import ai.kompile.core.agent.AgentProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Manages long-lived interactive CLI agent subprocesses for passthrough chat.
 * Each session keeps a CLI agent alive and allows multi-turn conversations
 * by writing to stdin and reading from stdout.
 */
@Service
public class PassthroughSessionManager {

    private static final Logger log = LoggerFactory.getLogger(PassthroughSessionManager.class);

    // Scheduling intervals
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L; // 30 seconds

    private final AgentRegistryService agentRegistry;
    private final AgentChatService agentChatService;
    private final ClaudeStreamParser streamParser;
    private final ChatHistoryService chatHistoryService;

    private final Map<String, InteractiveSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public PassthroughSessionManager(
            AgentRegistryService agentRegistry,
            AgentChatService agentChatService,
            ClaudeStreamParser streamParser,
            @Autowired(required = false) ChatHistoryService chatHistoryService) {
        this.agentRegistry = agentRegistry;
        this.agentChatService = agentChatService;
        this.streamParser = streamParser;
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * Represents a live interactive session with a CLI agent subprocess.
     */
    static class InteractiveSession {
        final String sessionId;
        final String agentName;
        final Process process;
        final OutputStream stdin;
        final SseEmitter emitter;
        final Thread readerThread;
        final String chatHistorySessionId;
        final StringBuffer responseBuffer = new StringBuffer();
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger messageCount = new AtomicInteger(0);
        final Instant startedAt = Instant.now();
        final boolean useStreamParser;
        final Pattern promptPattern;

        InteractiveSession(String sessionId, String agentName, Process process,
                           SseEmitter emitter, Thread readerThread,
                           String chatHistorySessionId, boolean useStreamParser,
                           Pattern promptPattern) {
            this.sessionId = sessionId;
            this.agentName = agentName;
            this.process = process;
            this.stdin = process.getOutputStream();
            this.emitter = emitter;
            this.readerThread = readerThread;
            this.chatHistorySessionId = chatHistorySessionId;
            this.useStreamParser = useStreamParser;
            this.promptPattern = promptPattern;
        }
    }

    /**
     * Start a new passthrough session.
     */
    public String startSession(PassthroughSessionRequest request, SseEmitter emitter) {
        String agentName = request.getAgentName();
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) {
            sendEvent(emitter, "error", Map.of("message", "Agent not found: " + agentName));
            return null;
        }

        AgentProvider agent = agentOpt.get();
        if (!agent.isAvailable()) {
            sendEvent(emitter, "error", Map.of("message", "Agent not available: " + agent.getDisplayName()));
            return null;
        }

        if (agent.isApiAgent()) {
            sendEvent(emitter, "error", Map.of("message", "Passthrough mode only supports CLI agents"));
            return null;
        }

        String sessionId = UUID.randomUUID().toString();

        try {
            // Build the interactive command (no -p flag), with optional pass-through args
            List<String> command = agentChatService.buildInteractiveCommand(
                    agent, request.isSkipPermissions(), request.isInjectMcpTools(), request.getAgentArgs());

            log.info("Starting passthrough session {} with command: {} (mcpTools={})", sessionId, command, request.isInjectMcpTools());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            if (request.getWorkingDirectory() != null && !request.getWorkingDirectory().isEmpty()) {
                File workDir = new File(request.getWorkingDirectory());
                if (workDir.isDirectory()) {
                    pb.directory(workDir);
                }
            }

            pb.environment().putAll(agent.safeEnvironment());

            Process process = pb.start();

            // Create chat history session
            String chatHistorySessionId = null;
            if (chatHistoryService != null) {
                try {
                    String title = request.getSessionName() != null ? request.getSessionName()
                            : "Passthrough: " + agent.getDisplayName();
                    var chatSession = chatHistoryService.createSession(title, "system", null,
                            "passthrough-" + agentName);
                    chatHistorySessionId = chatSession.getSessionId();
                } catch (Exception e) {
                    log.warn("Failed to create chat history session: {}", e.getMessage());
                }
            }

            boolean useStreamParser = streamParser.supportsStreamJson(agentName);
            Pattern promptPattern = null;
            if (agent.getInteractivePromptPattern() != null) {
                promptPattern = Pattern.compile(agent.getInteractivePromptPattern());
            }

            final String finalChatHistorySessionId = chatHistorySessionId;
            final Pattern finalPromptPattern = promptPattern;

            // Create the reader thread
            Thread readerThread = new Thread(() -> readProcessOutput(
                    sessionId, process, emitter, useStreamParser, finalPromptPattern, finalChatHistorySessionId),
                    "passthrough-reader-" + sessionId);
            readerThread.setDaemon(true);

            InteractiveSession session = new InteractiveSession(
                    sessionId, agentName, process, emitter, readerThread,
                    chatHistorySessionId, useStreamParser, promptPattern);

            sessions.put(sessionId, session);

            readerThread.start();

            sendEvent(emitter, "session_started", Map.of(
                    "sessionId", sessionId,
                    "agent", agentName,
                    "agentDisplayName", agent.getDisplayName()));

            log.info("Passthrough session {} started for agent {}", sessionId, agentName);
            return sessionId;

        } catch (Exception e) {
            log.error("Failed to start passthrough session", e);
            sendEvent(emitter, "error", Map.of("message", "Failed to start session: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Send a message to an active passthrough session.
     */
    public boolean sendMessage(String sessionId, String message) {
        InteractiveSession session = sessions.get(sessionId);
        if (session == null || !session.active.get()) {
            return false;
        }

        try {
            // Write to stdin
            session.stdin.write((message + "\n").getBytes());
            session.stdin.flush();
            session.messageCount.incrementAndGet();

            // Reset response buffer for new turn
            session.responseBuffer.setLength(0);

            // Record user message in chat history
            if (chatHistoryService != null && session.chatHistorySessionId != null) {
                try {
                    chatHistoryService.addMessage(session.chatHistorySessionId,
                            ChatMessage.MessageRole.USER, message, null);
                } catch (Exception e) {
                    log.debug("Failed to save user message to chat history: {}", e.getMessage());
                }
            }

            return true;
        } catch (IOException e) {
            log.error("Failed to send message to session {}", sessionId, e);
            return false;
        }
    }

    /**
     * End a passthrough session.
     */
    public void endSession(String sessionId) {
        InteractiveSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }

        session.active.set(false);

        try {
            // Close stdin to signal EOF
            session.stdin.close();
        } catch (IOException e) {
            log.debug("Error closing stdin for session {}", sessionId);
        }

        // Give process time to exit gracefully
        try {
            boolean exited = session.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                session.process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            session.process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        try {
            sendEvent(session.emitter, "session_ended", Map.of("sessionId", sessionId));
            session.emitter.complete();
        } catch (Exception e) {
            log.debug("Error completing emitter for session {}", sessionId);
        }

        log.info("Passthrough session {} ended. Messages: {}", sessionId, session.messageCount.get());
    }

    /**
     * List active sessions.
     */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (InteractiveSession session : sessions.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("sessionId", session.sessionId);
            info.put("agentName", session.agentName);
            info.put("active", session.active.get() && session.process.isAlive());
            info.put("messageCount", session.messageCount.get());
            info.put("startedAt", session.startedAt.toString());
            info.put("uptimeSeconds", Instant.now().getEpochSecond() - session.startedAt.getEpochSecond());
            result.add(info);
        }
        return result;
    }

    /**
     * Get status of a specific session.
     */
    public Map<String, Object> getStatus(String sessionId) {
        InteractiveSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("sessionId", session.sessionId);
        status.put("agentName", session.agentName);
        status.put("alive", session.process.isAlive());
        status.put("active", session.active.get());
        status.put("messageCount", session.messageCount.get());
        status.put("startedAt", session.startedAt.toString());
        status.put("uptimeSeconds", Instant.now().getEpochSecond() - session.startedAt.getEpochSecond());
        return status;
    }

    /**
     * Read process output and forward to SSE emitter.
     */
    private void readProcessOutput(String sessionId, Process process, SseEmitter emitter,
                                   boolean useStreamParser, Pattern promptPattern,
                                   String chatHistorySessionId) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder responseBuffer = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                InteractiveSession session = sessions.get(sessionId);
                if (session == null || !session.active.get()) {
                    break;
                }

                if (useStreamParser) {
                    // Claude stream-json parsing
                    ClaudeStreamParser.ParseResult result = streamParser.parseLine(sessionId, line);
                    if (result != null) {
                        if (result.textContent() != null && !result.textContent().isEmpty()) {
                            responseBuffer.append(result.textContent());
                            sendEvent(emitter, "chunk", result.textContent());
                        }
                        if ("tool_use".equals(result.type()) && result.toolName() != null) {
                            sendEvent(emitter, "tool_use", Map.of(
                                    "toolName", result.toolName(),
                                    "input", result.toolInput() != null ? result.toolInput().toString() : ""));
                        }
                        if (result.isResult()) {
                            // Turn complete
                            String response = responseBuffer.toString();
                            saveAssistantMessage(chatHistorySessionId, response);
                            sendEvent(emitter, "turn_complete", Map.of(
                                    "content", response,
                                    "durationMs", result.durationMs() != null ? result.durationMs() : 0,
                                    "costUsd", result.costUsd() != null ? result.costUsd() : 0.0));
                            responseBuffer.setLength(0);
                            streamParser.clearSession(sessionId);
                            streamParser.clearModifiedFiles(sessionId);
                        }
                    }
                } else {
                    // Plain text mode for non-Claude agents — strip TUI escape sequences
                    String cleanLine = ClaudeStreamParser.stripAnsi(line);
                    if (cleanLine.isEmpty()) continue;
                    responseBuffer.append(cleanLine).append("\n");
                    sendEvent(emitter, "chunk", cleanLine + "\n");

                    // Check for prompt pattern (turn boundary)
                    if (promptPattern != null && promptPattern.matcher(line).matches()) {
                        String response = responseBuffer.toString();
                        saveAssistantMessage(chatHistorySessionId, response);
                        sendEvent(emitter, "turn_complete", Map.of("content", response));
                        responseBuffer.setLength(0);
                    }
                }
            }

            // Process ended - save any remaining buffer
            if (responseBuffer.length() > 0) {
                saveAssistantMessage(chatHistorySessionId, responseBuffer.toString());
            }

        } catch (IOException e) {
            if (sessions.containsKey(sessionId)) {
                log.debug("IO error reading process output for session {}: {}", sessionId, e.getMessage());
            }
        } finally {
            InteractiveSession session = sessions.get(sessionId);
            if (session != null) {
                session.active.set(false);
                try {
                    sendEvent(emitter, "session_ended", Map.of("sessionId", sessionId,
                            "reason", "process_exited"));
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing emitter on process exit");
                }
            }
            // Clean up dead sessions
            sessions.remove(sessionId);
        }
    }

    private void saveAssistantMessage(String chatHistorySessionId, String content) {
        if (chatHistoryService != null && chatHistorySessionId != null && !content.isBlank()) {
            try {
                chatHistoryService.addMessage(chatHistorySessionId,
                        ChatMessage.MessageRole.ASSISTANT, content, null);
            } catch (Exception e) {
                log.debug("Failed to save assistant message to chat history: {}", e.getMessage());
            }
        }
    }

    /**
     * Heartbeat to keep SSE connections alive.
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void sendHeartbeats() {
        for (InteractiveSession session : sessions.values()) {
            if (session.active.get() && session.process.isAlive()) {
                sendEvent(session.emitter, "heartbeat", Map.of("timestamp", Instant.now().toString()));
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up {} passthrough sessions", sessions.size());
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            endSession(sessionId);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventType, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventType).data(data));
        } catch (IOException e) {
            log.debug("Error sending SSE event '{}': {}", eventType, e.getMessage());
        }
    }
}
