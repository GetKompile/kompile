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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side state for a single enforcer session.
 * <p>
 * Each session wraps a managed passthrough agent subprocess with an enforcer
 * judge running alongside it. The session tracks compliance score, violations,
 * and interrupt events in real time.
 */
public class EnforcerSessionState {

    /**
     * A recorded violation or interrupt event.
     */
    public record InterruptEvent(
            String eventId,
            Instant timestamp,
            String type,
            String severity,
            double score,
            List<String> violations,
            String reason,
            String correctionPrompt,
            String action
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "eventId", eventId,
                    "timestamp", timestamp.toString(),
                    "type", type,
                    "severity", severity,
                    "score", score,
                    "violations", violations,
                    "reason", reason != null ? reason : "",
                    "correctionPrompt", correctionPrompt != null ? correctionPrompt : "",
                    "action", action
            );
        }
    }

    private final String sessionId;
    private final String agentName;
    private final String rules;
    private final int maxCorrections;
    private final String judgeBackend;
    private final Instant startedAt;

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<Double> currentScore = new AtomicReference<>(1.0);
    private final AtomicInteger correctionAttempts = new AtomicInteger(0);
    private final AtomicInteger totalTurns = new AtomicInteger(0);
    private final AtomicInteger violationCount = new AtomicInteger(0);
    private final AtomicBoolean active = new AtomicBoolean(true);

    // Rolling event log (capped at MAX_EVENTS)
    private static final int MAX_EVENTS = 200;
    private final CopyOnWriteArrayList<InterruptEvent> events = new CopyOnWriteArrayList<>();

    // Passthrough session ID for linking to the underlying agent subprocess
    private volatile String passthroughSessionId;

    // Working directory for the agent
    private final String workingDirectory;

    // Coding project ID from the kompile project manifest (null if not scoped to a coding project)
    private final String codingProjectId;

    public EnforcerSessionState(String sessionId, String agentName, String rules,
                                 int maxCorrections, String judgeBackend,
                                 String workingDirectory) {
        this(sessionId, agentName, rules, maxCorrections, judgeBackend, workingDirectory, null);
    }

    public EnforcerSessionState(String sessionId, String agentName, String rules,
                                 int maxCorrections, String judgeBackend,
                                 String workingDirectory, String codingProjectId) {
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.rules = rules;
        this.maxCorrections = maxCorrections;
        this.judgeBackend = judgeBackend != null ? judgeBackend : "";
        this.workingDirectory = workingDirectory;
        this.codingProjectId = codingProjectId;
        this.startedAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getRules() {
        return rules;
    }

    public int getMaxCorrections() {
        return maxCorrections;
    }

    public String getJudgeBackend() {
        return judgeBackend;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public String getCodingProjectId() {
        return codingProjectId;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isActive() {
        return active.get();
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public double getCurrentScore() {
        return currentScore.get();
    }

    public void setCurrentScore(double score) {
        this.currentScore.set(score);
    }

    public int getCorrectionAttempts() {
        return correctionAttempts.get();
    }

    public void incrementCorrectionAttempts() {
        correctionAttempts.incrementAndGet();
    }

    public int getTotalTurns() {
        return totalTurns.get();
    }

    public void incrementTotalTurns() {
        totalTurns.incrementAndGet();
    }

    public int getViolationCount() {
        return violationCount.get();
    }

    public String getPassthroughSessionId() {
        return passthroughSessionId;
    }

    public void setPassthroughSessionId(String passthroughSessionId) {
        this.passthroughSessionId = passthroughSessionId;
    }

    public void addEvent(InterruptEvent event) {
        events.add(event);
        if ("TEXT_VIOLATION".equals(event.type()) || "TOOL_VIOLATION".equals(event.type())
                || "BLOCKED".equals(event.type())) {
            violationCount.incrementAndGet();
        }
        // Trim old events
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    public List<InterruptEvent> getEvents() {
        return List.copyOf(events);
    }

    public List<InterruptEvent> getRecentEvents(int limit) {
        int size = events.size();
        if (size <= limit) {
            return List.copyOf(events);
        }
        return List.copyOf(events.subList(size - limit, size));
    }

    /**
     * Produce a summary map suitable for JSON serialization.
     */
    public Map<String, Object> toSummaryMap() {
        return Map.ofEntries(
                Map.entry("sessionId", sessionId),
                Map.entry("agentName", agentName),
                Map.entry("enabled", enabled.get()),
                Map.entry("active", active.get()),
                Map.entry("score", currentScore.get()),
                Map.entry("corrections", correctionAttempts.get()),
                Map.entry("maxCorrections", maxCorrections),
                Map.entry("totalTurns", totalTurns.get()),
                Map.entry("violations", violationCount.get()),
                Map.entry("judgeBackend", judgeBackend),
                Map.entry("startedAt", startedAt.toString()),
                Map.entry("workingDirectory", workingDirectory != null ? workingDirectory : ""),
                Map.entry("codingProjectId", codingProjectId != null ? codingProjectId : "")
        );
    }

    /**
     * Produce a detail map including recent events.
     */
    public Map<String, Object> toDetailMap(int recentEventLimit) {
        List<Map<String, Object>> recentEvents = new ArrayList<>();
        for (InterruptEvent event : getRecentEvents(recentEventLimit)) {
            recentEvents.add(event.toMap());
        }
        return Map.ofEntries(
                Map.entry("sessionId", sessionId),
                Map.entry("agentName", agentName),
                Map.entry("rules", rules),
                Map.entry("enabled", enabled.get()),
                Map.entry("active", active.get()),
                Map.entry("score", currentScore.get()),
                Map.entry("corrections", correctionAttempts.get()),
                Map.entry("maxCorrections", maxCorrections),
                Map.entry("totalTurns", totalTurns.get()),
                Map.entry("violations", violationCount.get()),
                Map.entry("judgeBackend", judgeBackend),
                Map.entry("startedAt", startedAt.toString()),
                Map.entry("workingDirectory", workingDirectory != null ? workingDirectory : ""),
                Map.entry("codingProjectId", codingProjectId != null ? codingProjectId : ""),
                Map.entry("events", recentEvents)
        );
    }
}
