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

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive session metrics tracking for chat sessions.
 * Tracks turns, tokens, timing, tool usage, queue activity, and more.
 * Inspired by metrics displayed by Claude Code, Codex CLI, and Aider.
 */
public class ChatSessionMetrics {

    private final String sessionId;
    private final Instant sessionStart;
    private String provider;
    private String model;
    private String agentName;
    private boolean ragEnabled;
    
    // Role tracking
    private String activeRole;
    private final List<RoleChangeEvent> roleChanges = Collections.synchronizedList(new ArrayList<>());

    // Turn counts
    private final AtomicInteger userTurns = new AtomicInteger(0);
    private final AtomicInteger assistantTurns = new AtomicInteger(0);
    private final AtomicInteger systemEvents = new AtomicInteger(0);

    // Token tracking (actual from API when available)
    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);
    private final AtomicLong cacheReadTokens = new AtomicLong(0);
    private final AtomicLong cacheCreationTokens = new AtomicLong(0);

    // Estimated tokens (chars/4 fallback)
    private final AtomicLong estimatedInputChars = new AtomicLong(0);
    private final AtomicLong estimatedOutputChars = new AtomicLong(0);

    // Timing
    private final AtomicLong totalApiLatencyMs = new AtomicLong(0);
    private final AtomicInteger apiCalls = new AtomicInteger(0);
    private final AtomicLong longestTurnMs = new AtomicLong(0);
    private final AtomicLong shortestTurnMs = new AtomicLong(Long.MAX_VALUE);

    // Tool tracking
    private final Map<String, AtomicInteger> toolCallCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> toolErrorCounts = new ConcurrentHashMap<>();
    private final AtomicInteger totalToolCalls = new AtomicInteger(0);
    private final AtomicInteger totalToolErrors = new AtomicInteger(0);
    private final AtomicLong totalToolDurationMs = new AtomicLong(0);

    // Agentic loop tracking
    private final AtomicInteger agenticSteps = new AtomicInteger(0);
    private final AtomicInteger compactionEvents = new AtomicInteger(0);
    private long tokensBeforeCompaction = 0;
    private long tokensAfterCompaction = 0;

    // RAG tracking
    private final AtomicInteger ragQueries = new AtomicInteger(0);
    private final AtomicInteger documentsRetrieved = new AtomicInteger(0);

    // Queue tracking
    private final AtomicInteger messagesQueued = new AtomicInteger(0);
    private final AtomicInteger messagesAutoDequeued = new AtomicInteger(0);
    private final AtomicInteger tasksBackgrounded = new AtomicInteger(0);

    // Performance harness tracking
    private final AtomicInteger escapeCount = new AtomicInteger(0);
    private final Map<String, AtomicInteger> escapesByType = new ConcurrentHashMap<>();
    private final AtomicInteger judgeCallCount = new AtomicInteger(0);
    private final AtomicInteger modelSwapCount = new AtomicInteger(0);
    private final AtomicInteger subagentsSpawned = new AtomicInteger(0);
    private final AtomicLong thinkingTokens = new AtomicLong(0);
    private final Map<String, List<Double>> qualityScoresByModel = new ConcurrentHashMap<>();

    // Session outcome tracking
    private volatile TaskOutcome sessionOutcome;
    private volatile String outcomeReason;
    private volatile String taskPrompt;

    // Per-turn log for detailed analysis
    private final List<TurnMetric> turnLog = Collections.synchronizedList(new ArrayList<>());

    public ChatSessionMetrics(String sessionId) {
        this.sessionId = sessionId;
        this.sessionStart = Instant.now();
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    public void setProvider(String provider) { this.provider = provider; }
    public String getProvider() { return provider; }
    public void setModel(String model) { this.model = model; }
    public String getModel() { return model; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public void setRagEnabled(boolean ragEnabled) { this.ragEnabled = ragEnabled; }
    
    public void setActiveRole(String roleName) {
        String oldRole = this.activeRole;
        this.activeRole = roleName;
        if (roleName != null) {
            roleChanges.add(new RoleChangeEvent(roleName, oldRole, Instant.now()));
        }
    }
    public String getActiveRole() { return activeRole; }
    public List<RoleChangeEvent> getRoleChanges() { return Collections.unmodifiableList(roleChanges); }

    public void setSessionOutcome(TaskOutcome outcome, String reason) {
        this.sessionOutcome = outcome;
        this.outcomeReason = reason;
    }
    public TaskOutcome getSessionOutcome() { return sessionOutcome; }
    public String getOutcomeReason() { return outcomeReason; }

    public void setTaskPrompt(String taskPrompt) { this.taskPrompt = taskPrompt; }
    public String getTaskPrompt() { return taskPrompt; }

    // ========================================================================
    // Turn tracking
    // ========================================================================

    public void recordUserTurn(String message) {
        userTurns.incrementAndGet();
        estimatedInputChars.addAndGet(message.length());
    }

    public void recordAssistantTurn(String response, long durationMs) {
        assistantTurns.incrementAndGet();
        estimatedOutputChars.addAndGet(response.length());
        totalApiLatencyMs.addAndGet(durationMs);
        apiCalls.incrementAndGet();

        longestTurnMs.accumulateAndGet(durationMs, Math::max);
        if (durationMs > 0) {
            shortestTurnMs.accumulateAndGet(durationMs, Math::min);
        }

        turnLog.add(new TurnMetric(Instant.now(), "assistant", response.length(), durationMs));
    }

    public void recordSystemEvent() {
        systemEvents.incrementAndGet();
    }

    // ========================================================================
    // Token tracking (actual from API)
    // ========================================================================

    public void recordTokenUsage(long input, long output, long cacheRead, long cacheCreation) {
        if (input > 0) inputTokens.addAndGet(input);
        if (output > 0) outputTokens.addAndGet(output);
        if (cacheRead > 0) cacheReadTokens.addAndGet(cacheRead);
        if (cacheCreation > 0) cacheCreationTokens.addAndGet(cacheCreation);
    }

    public boolean hasActualTokenCounts() {
        return inputTokens.get() > 0 || outputTokens.get() > 0;
    }

    // ========================================================================
    // Tool tracking
    // ========================================================================

    public void recordToolCall(String toolName, boolean isError, long durationMs) {
        totalToolCalls.incrementAndGet();
        toolCallCounts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
        if (isError) {
            totalToolErrors.incrementAndGet();
            toolErrorCounts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
        }
        totalToolDurationMs.addAndGet(durationMs);
    }

    // ========================================================================
    // Agentic loop tracking
    // ========================================================================

    public void recordAgenticStep() {
        agenticSteps.incrementAndGet();
    }

    public void recordCompaction(long tokensBefore, long tokensAfter) {
        compactionEvents.incrementAndGet();
        tokensBeforeCompaction = tokensBefore;
        tokensAfterCompaction = tokensAfter;
    }

    // ========================================================================
    // RAG tracking
    // ========================================================================

    public void recordRagQuery(int docsRetrieved) {
        ragQueries.incrementAndGet();
        documentsRetrieved.addAndGet(docsRetrieved);
    }

    // ========================================================================
    // Queue tracking
    // ========================================================================

    public void recordMessageQueued() { messagesQueued.incrementAndGet(); }
    public void recordMessageAutoDequeued() { messagesAutoDequeued.incrementAndGet(); }
    public void recordTaskBackgrounded() { tasksBackgrounded.incrementAndGet(); }

    // ========================================================================
    // Getters for display
    // ========================================================================

    public String getSessionId() { return sessionId; }
    public Instant getSessionStart() { return sessionStart; }
    public Duration getSessionDuration() { return Duration.between(sessionStart, Instant.now()); }
    public int getUserTurns() { return userTurns.get(); }
    public int getAssistantTurns() { return assistantTurns.get(); }
    public int getTotalTurns() { return userTurns.get() + assistantTurns.get(); }
    public long getInputTokens() { return inputTokens.get(); }
    public long getOutputTokens() { return outputTokens.get(); }
    public long getTotalTokens() { return inputTokens.get() + outputTokens.get(); }
    public long getCacheReadTokens() { return cacheReadTokens.get(); }
    public long getCacheCreationTokens() { return cacheCreationTokens.get(); }
    public long getEstimatedInputTokens() { return estimatedInputChars.get() / 4; }
    public long getEstimatedOutputTokens() { return estimatedOutputChars.get() / 4; }
    public long getTotalApiLatencyMs() { return totalApiLatencyMs.get(); }
    public int getApiCalls() { return apiCalls.get(); }
    public double getAvgResponseTimeMs() { return apiCalls.get() > 0 ? (double) totalApiLatencyMs.get() / apiCalls.get() : 0; }
    public int getTotalToolCalls() { return totalToolCalls.get(); }
    public int getTotalToolErrors() { return totalToolErrors.get(); }
    public int getAgenticSteps() { return agenticSteps.get(); }
    public int getCompactionEvents() { return compactionEvents.get(); }
    public int getRagQueries() { return ragQueries.get(); }
    public int getDocumentsRetrieved() { return documentsRetrieved.get(); }
    public int getMessagesQueued() { return messagesQueued.get(); }
    public int getMessagesAutoDequeued() { return messagesAutoDequeued.get(); }
    public int getTasksBackgrounded() { return tasksBackgrounded.get(); }
    public Map<String, AtomicInteger> getToolCallCounts() { return toolCallCounts; }

    // ========================================================================
    // Performance harness tracking
    // ========================================================================

    /**
     * Record an escape event of the given type. Null or blank types are ignored.
     */
    public void recordEscape(String type) {
        escapeCount.incrementAndGet();
        String key = (type != null && !type.isBlank()) ? type : "unknown";
        escapesByType.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** Increment the judge-call counter. */
    public void recordJudgeCall() {
        judgeCallCount.incrementAndGet();
    }

    /** Increment the model-swap counter. */
    public void recordModelSwap() {
        modelSwapCount.incrementAndGet();
    }

    /**
     * Record a quality score for the given model. Negative values are ignored.
     */
    public void recordQualityScore(String model, double score) {
        if (score < 0 || model == null) return;
        qualityScoresByModel.computeIfAbsent(model, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(score);
    }

    /** Increment the subagents-spawned counter. */
    public void recordSubagentSpawned() {
        subagentsSpawned.incrementAndGet();
    }

    /**
     * Accumulate thinking tokens. Non-positive values are ignored.
     */
    public void recordThinkingTokens(long tokens) {
        if (tokens > 0) {
            thinkingTokens.addAndGet(tokens);
        }
    }

    public int getEscapeCount() { return escapeCount.get(); }

    /** Returns a live, unmodifiable view of escape counts keyed by type. */
    public Map<String, AtomicInteger> getEscapesByType() {
        return Collections.unmodifiableMap(escapesByType);
    }

    public int getJudgeCallCount() { return judgeCallCount.get(); }

    public int getModelSwapCount() { return modelSwapCount.get(); }

    public int getSubagentsSpawned() { return subagentsSpawned.get(); }

    public long getThinkingTokens() { return thinkingTokens.get(); }

    /**
     * Returns a snapshot map of all quality scores recorded per model.
     */
    public Map<String, List<Double>> getQualityScoresByModel() {
        Map<String, List<Double>> snapshot = new LinkedHashMap<>();
        qualityScoresByModel.forEach((model, scores) -> {
            synchronized (scores) {
                snapshot.put(model, new ArrayList<>(scores));
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns the average quality score per model, computed from all recorded scores.
     * Models with no scores are excluded.
     */
    public Map<String, Double> getAvgScoreByModel() {
        Map<String, Double> result = new LinkedHashMap<>();
        qualityScoresByModel.forEach((model, scores) -> {
            synchronized (scores) {
                if (!scores.isEmpty()) {
                    double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    result.put(model, avg);
                }
            }
        });
        return Collections.unmodifiableMap(result);
    }

    // ========================================================================
    // Formatted output
    // ========================================================================

    public String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /**
     * Formats a number with comma separators.
     */
    private String formatNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }

    /**
     * Returns the top N tools by call count.
     */
    public List<Map.Entry<String, Integer>> getTopTools(int n) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        toolCallCounts.forEach((k, v) -> entries.add(Map.entry(k, v.get())));
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries.subList(0, Math.min(n, entries.size()));
    }

    // ========================================================================
    // JSON serialization for metrics file
    // ========================================================================

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();

        // Session info
        ObjectNode session = mapper.createObjectNode();
        session.put("sessionId", sessionId);
        session.put("started", sessionStart.toString());
        session.put("ended", Instant.now().toString());
        session.put("durationSeconds", getSessionDuration().getSeconds());
        if (provider != null) session.put("provider", provider);
        if (model != null) session.put("model", model);
        if (agentName != null) session.put("agent", agentName);
        session.put("ragEnabled", ragEnabled);
        root.set("session", session);

        // Turns
        ObjectNode turns = mapper.createObjectNode();
        turns.put("user", userTurns.get());
        turns.put("assistant", assistantTurns.get());
        turns.put("system", systemEvents.get());
        turns.put("total", getTotalTurns());
        root.set("turns", turns);

        // Tokens
        ObjectNode tokens = mapper.createObjectNode();
        if (hasActualTokenCounts()) {
            tokens.put("input", inputTokens.get());
            tokens.put("output", outputTokens.get());
            tokens.put("total", getTotalTokens());
            if (cacheReadTokens.get() > 0) tokens.put("cacheRead", cacheReadTokens.get());
            if (cacheCreationTokens.get() > 0) tokens.put("cacheCreation", cacheCreationTokens.get());
        }
        tokens.put("estimatedInput", getEstimatedInputTokens());
        tokens.put("estimatedOutput", getEstimatedOutputTokens());
        tokens.put("estimatedTotal", getEstimatedInputTokens() + getEstimatedOutputTokens());
        root.set("tokens", tokens);

        // Timing
        ObjectNode timing = mapper.createObjectNode();
        timing.put("totalApiLatencyMs", totalApiLatencyMs.get());
        timing.put("apiCalls", apiCalls.get());
        timing.put("avgResponseTimeMs", Math.round(getAvgResponseTimeMs()));
        if (longestTurnMs.get() > 0) timing.put("longestTurnMs", longestTurnMs.get());
        if (shortestTurnMs.get() < Long.MAX_VALUE) timing.put("shortestTurnMs", shortestTurnMs.get());
        root.set("timing", timing);

        // Tools
        ObjectNode tools = mapper.createObjectNode();
        tools.put("totalCalls", totalToolCalls.get());
        tools.put("totalErrors", totalToolErrors.get());
        tools.put("totalDurationMs", totalToolDurationMs.get());
        ObjectNode toolBreakdown = mapper.createObjectNode();
        toolCallCounts.forEach((k, v) -> toolBreakdown.put(k, v.get()));
        tools.set("breakdown", toolBreakdown);
        root.set("tools", tools);

        // Agentic
        ObjectNode agentic = mapper.createObjectNode();
        agentic.put("steps", agenticSteps.get());
        agentic.put("compactions", compactionEvents.get());
        root.set("agentic", agentic);

        // RAG
        if (ragEnabled || ragQueries.get() > 0) {
            ObjectNode rag = mapper.createObjectNode();
            rag.put("queries", ragQueries.get());
            rag.put("documentsRetrieved", documentsRetrieved.get());
            root.set("rag", rag);
        }

        // Queue
        if (messagesQueued.get() > 0 || tasksBackgrounded.get() > 0) {
            ObjectNode queue = mapper.createObjectNode();
            queue.put("messagesQueued", messagesQueued.get());
            queue.put("messagesAutoDequeued", messagesAutoDequeued.get());
            queue.put("tasksBackgrounded", tasksBackgrounded.get());
            root.set("queue", queue);
        }

        // Outcome
        if (sessionOutcome != null) {
            ObjectNode outcome = mapper.createObjectNode();
            outcome.put("outcome", sessionOutcome.name());
            if (outcomeReason != null) outcome.put("reason", outcomeReason);
            if (taskPrompt != null) outcome.put("taskPrompt", taskPrompt);
            root.set("outcome", outcome);
        }

        // Escapes
        if (escapeCount.get() > 0) {
            ObjectNode escapes = mapper.createObjectNode();
            escapes.put("totalEscapes", escapeCount.get());
            ObjectNode byType = mapper.createObjectNode();
            escapesByType.forEach((k, v) -> byType.put(k, v.get()));
            escapes.set("byType", byType);
            root.set("escapes", escapes);
        }

        // Quality scores
        if (!qualityScoresByModel.isEmpty()) {
            ObjectNode scores = mapper.createObjectNode();
            getAvgScoreByModel().forEach(scores::put);
            root.set("qualityScores", scores);
        }

        // Harness fields in agentic section
        ObjectNode agenticNode = (ObjectNode) root.get("agentic");
        if (judgeCallCount.get() > 0) agenticNode.put("judgeCalls", judgeCallCount.get());
        if (modelSwapCount.get() > 0) agenticNode.put("modelSwaps", modelSwapCount.get());
        if (thinkingTokens.get() > 0) agenticNode.put("thinkingTokens", thinkingTokens.get());
        if (subagentsSpawned.get() > 0) agenticNode.put("subagentsSpawned", subagentsSpawned.get());

        return root;
    }

    /**
     * Saves metrics to a JSON file alongside the transcript.
     */
    public void saveToFile(Path metricsFile, ObjectMapper mapper) {
        try {
            Files.createDirectories(metricsFile.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(mapper));
            Files.writeString(metricsFile, json);
        } catch (IOException e) {
            System.err.println("Warning: Failed to save session metrics: " + e.getMessage());
        }
    }

    // ========================================================================
    // Per-turn metric record
    // ========================================================================

    public static class TurnMetric {
        public final Instant timestamp;
        public final String role;
        public final int chars;
        public final long durationMs;

        public TurnMetric(Instant timestamp, String role, int chars, long durationMs) {
            this.timestamp = timestamp;
            this.role = role;
            this.chars = chars;
            this.durationMs = durationMs;
        }
    }

    /**
     * Record of a role change event during the session.
     */
    public static class RoleChangeEvent {
        public final String newRole;
        public final String oldRole;
        public final Instant timestamp;

        public RoleChangeEvent(String newRole, String oldRole, Instant timestamp) {
            this.newRole = newRole;
            this.oldRole = oldRole;
            this.timestamp = timestamp;
        }
    }
}
