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

package ai.kompile.app.services.enforcer;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.utils.StringUtils;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ClaudeStreamParser;
import ai.kompile.core.agent.AgentProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages enforcer-controlled agent subprocess sessions on the server side.
 * <p>
 * Each session wraps a CLI agent subprocess with a background judge that
 * evaluates streamed output in real time. The session exposes its state
 * and events via SSE for the web UI and CLI monitor.
 * <p>
 * This is the server-side counterpart to the CLI-side
 * {@code ScoringRealtimeMonitor} + {@code EnforcerCommand}.
 */
@Service
public class EnforcerSessionManager {

    private static final Logger log = LoggerFactory.getLogger(EnforcerSessionManager.class);

    private final AgentRegistryService agentRegistry;
    private final AgentChatService agentChatService;
    private final ClaudeStreamParser streamParser;
    private final EnforcerMetricsService metricsService;

    private final Map<String, ManagedEnforcerSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public EnforcerSessionManager(
            AgentRegistryService agentRegistry,
            AgentChatService agentChatService,
            ClaudeStreamParser streamParser,
            EnforcerMetricsService metricsService) {
        this.agentRegistry = agentRegistry;
        this.agentChatService = agentChatService;
        this.streamParser = streamParser;
        this.metricsService = metricsService;
    }

    /**
     * A live enforcer session wrapping an agent subprocess.
     */
    static class ManagedEnforcerSession {
        final EnforcerSessionState state;
        final Process process;
        final OutputStream stdin;
        final Thread readerThread;
        final List<SseEmitter> eventSubscribers = new CopyOnWriteArrayList<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final StringBuffer responseBuffer = new StringBuffer();

        ManagedEnforcerSession(EnforcerSessionState state, Process process,
                                Thread readerThread) {
            this.state = state;
            this.process = process;
            this.stdin = process != null ? process.getOutputStream() : null;
            this.readerThread = readerThread;
        }
    }

    /**
     * Create and start a new enforcer session.
     */
    public EnforcerSessionState createSession(String agentName, String rules,
                                               int maxCorrections, String judgeBackend,
                                               String workingDirectory,
                                               boolean skipPermissions,
                                               boolean injectMcpTools) {
        return createSession(agentName, rules, maxCorrections, judgeBackend,
                workingDirectory, skipPermissions, injectMcpTools, null);
    }

    /**
     * Create and start a new enforcer session scoped to an optional coding project.
     */
    public EnforcerSessionState createSession(String agentName, String rules,
                                               int maxCorrections, String judgeBackend,
                                               String workingDirectory,
                                               boolean skipPermissions,
                                               boolean injectMcpTools,
                                               String codingProjectId) {
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) {
            throw new IllegalArgumentException("Agent not found: " + agentName);
        }

        AgentProvider agent = agentOpt.get();
        if (!agent.isAvailable()) {
            throw new IllegalStateException("Agent not available: " + agent.getDisplayName());
        }

        String sessionId = "enforcer-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcerSessionState state = new EnforcerSessionState(
                sessionId, agentName, rules, maxCorrections,
                judgeBackend != null ? judgeBackend : "",
                workingDirectory != null ? workingDirectory : System.getProperty("user.dir"),
                codingProjectId);

        try {
            List<String> command = agentChatService.buildInteractiveCommand(
                    agent, skipPermissions, injectMcpTools, null);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(state.getWorkingDirectory()));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            Thread readerThread = new Thread(
                    () -> readProcessOutput(sessionId, process),
                    "enforcer-reader-" + sessionId);
            readerThread.setDaemon(true);

            ManagedEnforcerSession session = new ManagedEnforcerSession(state, process, readerThread);
            sessions.put(sessionId, session);

            readerThread.start();

            log.info("Enforcer session {} started: agent={}, judge={}", sessionId, agentName, judgeBackend);
            broadcastEvent(sessionId, "session_started", state.toSummaryMap());

            metricsService.recordSessionStart(codingProjectId, agentName, sessionId);

            return state;
        } catch (IOException e) {
            throw new RuntimeException("Failed to start enforcer session: " + e.getMessage(), e);
        }
    }

    /**
     * Send a message to an active enforcer session's agent subprocess.
     */
    public boolean sendMessage(String sessionId, String message) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null || !session.running.get()) {
            return false;
        }

        try {
            session.stdin.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            session.stdin.flush();
            session.state.incrementTotalTurns();
            session.responseBuffer.setLength(0);
            broadcastEvent(sessionId, "message_sent", Map.of(
                    "message", StringUtils.truncate(message, 200),
                    "turn", session.state.getTotalTurns()));
            return true;
        } catch (IOException e) {
            log.warn("Failed to send message to enforcer session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * Enable enforcement for a session.
     */
    public boolean enableEnforcement(String sessionId) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.state.setEnabled(true);
        broadcastEvent(sessionId, "enforcement_enabled", Map.of("enabled", true));
        return true;
    }

    /**
     * Disable enforcement for a session (agent runs unchecked).
     */
    public boolean disableEnforcement(String sessionId) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.state.setEnabled(false);
        broadcastEvent(sessionId, "enforcement_disabled", Map.of("enabled", false));
        return true;
    }

    /**
     * Record an interrupt event from a CLI-side enforcer.
     * The CLI calls this to push events to the server for display.
     */
    public boolean recordEvent(String sessionId, EnforcerSessionState.InterruptEvent event) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.state.addEvent(event);
        session.state.setCurrentScore(event.score());
        broadcastEvent(sessionId, "interrupt_event", event.toMap());

        // Record metrics for this event
        String cpId = session.state.getCodingProjectId();
        String agent = session.state.getAgentName();
        String evtType = event.type();
        if ("TEXT_VIOLATION".equals(evtType) || "TOOL_VIOLATION".equals(evtType)
                || "BLOCKED".equals(evtType)) {
            metricsService.recordViolation(cpId, agent, sessionId,
                    evtType, event.score(), event.reason(), event.violations());
        } else {
            metricsService.recordInterruption(cpId, agent, sessionId,
                    evtType, event.score(), event.reason());
        }

        return true;
    }

    /**
     * Register a session from a CLI-side enforcer (for monitoring only).
     * The CLI creates the enforcer locally but registers its state here
     * so the web UI can monitor it.
     */
    public EnforcerSessionState registerExternalSession(String sessionId, String agentName,
                                                         String rules, int maxCorrections,
                                                         String judgeBackend,
                                                         String workingDirectory) {
        return registerExternalSession(sessionId, agentName, rules, maxCorrections,
                judgeBackend, workingDirectory, null);
    }

    /**
     * Register an externally-managed enforcer session scoped to an optional coding project.
     */
    public EnforcerSessionState registerExternalSession(String sessionId, String agentName,
                                                         String rules, int maxCorrections,
                                                         String judgeBackend,
                                                         String workingDirectory,
                                                         String codingProjectId) {
        EnforcerSessionState state = new EnforcerSessionState(
                sessionId, agentName, rules, maxCorrections,
                judgeBackend, workingDirectory, codingProjectId);
        // Create a shell session with no subprocess (externally managed)
        ManagedEnforcerSession session = new ManagedEnforcerSession(state, null, null);
        sessions.put(sessionId, session);
        log.info("External enforcer session registered: {}", sessionId);
        broadcastEvent(sessionId, "session_registered", state.toSummaryMap());

        metricsService.recordSessionStart(codingProjectId, agentName, sessionId);

        return state;
    }

    /**
     * End and remove an enforcer session.
     */
    public void endSession(String sessionId) {
        ManagedEnforcerSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }

        session.running.set(false);
        session.state.setActive(false);

        metricsService.recordSessionEnd(
                session.state.getCodingProjectId(),
                session.state.getAgentName(),
                sessionId,
                session.state.getCurrentScore(),
                session.state.getViolationCount(),
                session.state.getTotalTurns());

        // Close subprocess if we own it
        if (session.process != null) {
            try {
                session.stdin.close();
            } catch (IOException e) {
                log.warn("Failed to close stdin for enforcer session {}: {}", sessionId, e.getMessage());
            }

            try {
                if (!session.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    session.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                session.process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        broadcastEvent(sessionId, "session_ended", Map.of("sessionId", sessionId));

        // Complete all SSE emitters
        for (SseEmitter emitter : session.eventSubscribers) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("Failed to complete SSE emitter for enforcer session {}: {}", sessionId, e.getMessage());
            }
        }
        session.eventSubscribers.clear();

        log.info("Enforcer session {} ended", sessionId);
    }

    /**
     * Restart an enforcer session — end the current one and create a new one
     * with the same parameters.
     */
    public EnforcerSessionState restartSession(String sessionId) {
        ManagedEnforcerSession old = sessions.get(sessionId);
        if (old == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        String agentName = old.state.getAgentName();
        String rules = old.state.getRules();
        int maxCorrections = old.state.getMaxCorrections();
        String judgeBackend = old.state.getJudgeBackend();
        String workingDirectory = old.state.getWorkingDirectory();
        String codingProjectId = old.state.getCodingProjectId();

        endSession(sessionId);

        return createSession(agentName, rules, maxCorrections, judgeBackend,
                workingDirectory, true, true, codingProjectId);
    }

    /**
     * Subscribe to real-time events for a session via SSE.
     */
    public SseEmitter subscribeEvents(String sessionId) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }

        SseEmitter emitter = new SseEmitter(-1L);
        session.eventSubscribers.add(emitter);

        emitter.onCompletion(() -> session.eventSubscribers.remove(emitter));
        emitter.onTimeout(() -> session.eventSubscribers.remove(emitter));
        emitter.onError(e -> session.eventSubscribers.remove(emitter));

        // Send current state as initial event
        try {
            emitter.send(SseEmitter.event()
                    .name("session_state")
                    .data(session.state.toDetailMap(50)));
        } catch (IOException e) {
            session.eventSubscribers.remove(emitter);
        }

        return emitter;
    }

    /**
     * Get the state of a specific session.
     */
    public EnforcerSessionState getSession(String sessionId) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        return session != null ? session.state : null;
    }

    /**
     * List all sessions.
     */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ManagedEnforcerSession session : sessions.values()) {
            result.add(session.state.toSummaryMap());
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("startedAt"))));
        return result;
    }

    /**
     * List active judge/enforcer watcher processes for dashboard display.
     */
    public List<Map<String, Object>> listActiveProcesses() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ManagedEnforcerSession session : sessions.values()) {
            if (!session.state.isActive()) {
                continue;
            }
            result.add(toProcessMap(session, "enforcer"));
            if (session.state.getJudgeBackend() != null && !session.state.getJudgeBackend().isBlank()) {
                result.add(toProcessMap(session, "judge"));
            }
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("startedAt"))));
        return result;
    }

    private Map<String, Object> toProcessMap(ManagedEnforcerSession session, String kind) {
        EnforcerSessionState state = session.state;
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("processId", kind + "-" + state.getSessionId());
        process.put("kind", kind);
        process.put("sessionId", state.getSessionId());
        process.put("agentName", state.getAgentName());
        process.put("judgeBackend", state.getJudgeBackend());
        process.put("active", state.isActive());
        process.put("enabled", state.isEnabled());
        process.put("startedAt", state.getStartedAt().toString());
        process.put("workingDirectory", state.getWorkingDirectory() != null ? state.getWorkingDirectory() : "");
        process.put("codingProjectId", state.getCodingProjectId() != null ? state.getCodingProjectId() : "");
        process.put("owner", session.process != null ? "server" : "cli");
        process.put("pid", session.process != null ? session.process.pid() : -1L);
        process.put("description", "judge".equals(kind)
                ? "Judge watcher for enforcer session"
                : "Enforcer watcher for " + state.getAgentName());
        return process;
    }

    /**
     * Get session detail map with recent events.
     */
    public Map<String, Object> getSessionDetail(String sessionId) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        return session.state.toDetailMap(50);
    }

    /**
     * Get violations for a specific session.
     */
    public List<Map<String, Object>> getViolations(String sessionId) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        for (EnforcerSessionState.InterruptEvent event : session.state.getEvents()) {
            if ("TEXT_VIOLATION".equals(event.type()) || "TOOL_VIOLATION".equals(event.type())
                    || "BLOCKED".equals(event.type())) {
                violations.add(event.toMap());
            }
        }
        return violations;
    }

    // ========================================================================
    // Internal — subprocess output reading
    // ========================================================================

    private void readProcessOutput(String sessionId, Process process) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (session.running.get() && (line = reader.readLine()) != null) {
                processOutputLine(sessionId, session, line);
            }
        } catch (IOException e) {
            if (session.running.get()) {
                log.debug("Enforcer session {} reader error: {}", sessionId, e.getMessage());
            }
        } finally {
            session.running.set(false);
            session.state.setActive(false);
            broadcastEvent(sessionId, "session_ended", Map.of("sessionId", sessionId));
            // Remove dead session from tracking map to prevent unbounded growth
            sessions.remove(sessionId);
            log.debug("Removed ended enforcer session {} from tracking", sessionId);
        }
    }

    private void processOutputLine(String sessionId, ManagedEnforcerSession session, String line) {
        String agentName = session.state.getAgentName().toLowerCase();
        boolean isClaude = agentName.contains("claude");

        if (isClaude) {
            ClaudeStreamParser.ParseResult result = streamParser.parseLine(line, sessionId);
            if (result != null) {
                if (result.textContent() != null && !result.textContent().isEmpty()) {
                    session.responseBuffer.append(result.textContent());
                    broadcastEvent(sessionId, "chunk", Map.of(
                            "text", result.textContent(),
                            "score", session.state.getCurrentScore()));
                }
                if (result.toolName() != null && !result.toolName().isEmpty()) {
                    broadcastEvent(sessionId, "tool_use", Map.of(
                            "name", result.toolName(),
                            "score", session.state.getCurrentScore()));
                }
                if (result.isResult()) {
                    broadcastEvent(sessionId, "turn_complete", Map.of(
                            "content", session.responseBuffer.toString(),
                            "durationMs", result.durationMs(),
                            "costUsd", result.costUsd(),
                            "score", session.state.getCurrentScore(),
                            "turn", session.state.getTotalTurns()));
                    session.responseBuffer.setLength(0);
                }
            }
        } else {
            // Plain text fallback
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                session.responseBuffer.append(trimmed).append('\n');
                broadcastEvent(sessionId, "chunk", Map.of(
                        "text", trimmed,
                        "score", session.state.getCurrentScore()));
            }
        }
    }

    // ========================================================================
    // SSE broadcasting
    // ========================================================================

    private void broadcastEvent(String sessionId, String eventName, Map<String, Object> data) {
        ManagedEnforcerSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        Map<String, Object> eventData = new LinkedHashMap<>(data);
        eventData.put("sessionId", sessionId);
        eventData.put("timestamp", Instant.now().toString());

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : session.eventSubscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(eventData));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        session.eventSubscribers.removeAll(dead);
    }

    /**
     * Heartbeat to keep SSE connections alive.
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        for (ManagedEnforcerSession session : sessions.values()) {
            if (!session.eventSubscribers.isEmpty()) {
                broadcastEvent(session.state.getSessionId(), "heartbeat",
                        Map.of("score", session.state.getCurrentScore(),
                                "enabled", session.state.isEnabled()));
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            endSession(sessionId);
        }
    }

}
