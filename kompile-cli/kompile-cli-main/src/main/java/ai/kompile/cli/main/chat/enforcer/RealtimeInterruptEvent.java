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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.utils.StringUtils;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Structured event emitted in real-time by judges and enforcers during
 * a managed TUI passthrough session. Events are fired as conversation text
 * streams through the {@link ScoringRealtimeMonitor} and delivered to
 * registered {@link RealtimeInterruptListener}s.
 * <p>
 * Each event captures:
 * <ul>
 *   <li>A compliance score (0.0 = total violation, 1.0 = fully compliant)</li>
 *   <li>The action taken (continue, interrupt, reprompt, block)</li>
 *   <li>Violations and correction prompts for auto-reprompting</li>
 * </ul>
 */
public record RealtimeInterruptEvent(
        String eventId,
        Instant timestamp,
        EventType type,
        String severity,
        double score,
        List<String> violations,
        String reason,
        String correctionPrompt,
        Action action,
        String agentName,
        String triggerText,
        String toolName,
        int attemptNumber
) {

    /**
     * Classification of interrupt events.
     */
    public enum EventType {
        /** Partial text was scored — informational, no interrupt. */
        SCORE_UPDATE,
        /** Text violated a rule — interrupt triggered. */
        TEXT_VIOLATION,
        /** A tool call violated a rule — interrupt triggered. */
        TOOL_VIOLATION,
        /** Agent was interrupted and a correction prompt was sent. */
        REPROMPT,
        /** Turn completed — final score for the turn. */
        TURN_SCORED,
        /** Agent turn was blocked entirely (stop=true from judge). */
        BLOCKED
    }

    /**
     * Action taken in response to the event.
     */
    public enum Action {
        /** No intervention — agent continues normally. */
        CONTINUE,
        /** Agent subprocess was killed mid-stream. */
        INTERRUPT,
        /** Agent was interrupted and a correction prompt was auto-sent. */
        REPROMPT,
        /** Turn was blocked — no further attempts will be made. */
        BLOCK
    }

    /**
     * Returns true if this event caused an interruption of the agent.
     */
    public boolean isInterrupt() {
        return action == Action.INTERRUPT || action == Action.REPROMPT || action == Action.BLOCK;
    }

    // ── Factory methods ─────────────────────────────────────────────────

    public static RealtimeInterruptEvent scoreUpdate(double score, String agentName,
                                                      String triggerText) {
        return new RealtimeInterruptEvent(
                newId(), Instant.now(), EventType.SCORE_UPDATE, "info",
                score, List.of(), "score update", "",
                Action.CONTINUE, agentName, StringUtils.truncate(triggerText, 200), null, 0);
    }

    public static RealtimeInterruptEvent textViolation(double score, List<String> violations,
                                                        String reason, String correctionPrompt,
                                                        String agentName, String triggerText,
                                                        int attempt) {
        Action action = correctionPrompt != null && !correctionPrompt.isBlank()
                ? Action.REPROMPT : Action.INTERRUPT;
        return new RealtimeInterruptEvent(
                newId(), Instant.now(), EventType.TEXT_VIOLATION, "error",
                score, violations != null ? violations : List.of(),
                reason, correctionPrompt != null ? correctionPrompt : "",
                action, agentName, StringUtils.truncate(triggerText, 200), null, attempt);
    }

    public static RealtimeInterruptEvent toolViolation(String toolName, List<String> violations,
                                                        String reason, String correctionPrompt,
                                                        String agentName) {
        return new RealtimeInterruptEvent(
                newId(), Instant.now(), EventType.TOOL_VIOLATION, "error",
                0.0, violations != null ? violations : List.of(),
                reason, correctionPrompt != null ? correctionPrompt : "",
                Action.BLOCK, agentName, null, toolName, 0);
    }

    public static RealtimeInterruptEvent reprompt(String correctionPrompt, String agentName,
                                                    int attempt) {
        return new RealtimeInterruptEvent(
                newId(), Instant.now(), EventType.REPROMPT, "warning",
                -1.0, List.of(), "auto-reprompt",
                correctionPrompt != null ? correctionPrompt : "",
                Action.REPROMPT, agentName, null, null, attempt);
    }

    public static RealtimeInterruptEvent turnScored(double score, String agentName) {
        return new RealtimeInterruptEvent(
                newId(), Instant.now(), EventType.TURN_SCORED,
                score >= 0.8 ? "info" : "warning",
                score, List.of(), "turn complete", "",
                Action.CONTINUE, agentName, null, null, 0);
    }

    public static RealtimeInterruptEvent blocked(List<String> violations, String reason,
                                                   String agentName) {
        return new RealtimeInterruptEvent(
                newId(), Instant.now(), EventType.BLOCKED, "critical",
                0.0, violations != null ? violations : List.of(),
                reason, "",
                Action.BLOCK, agentName, null, null, 0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

}
