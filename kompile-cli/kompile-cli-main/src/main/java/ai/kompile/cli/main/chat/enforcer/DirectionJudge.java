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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.ResilientJudgeBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Direction judge: monitors the DIRECTION of a conversation relative to the user's goal,
 * rather than per-turn rule compliance.
 *
 * <p>Long agentic conversations drift: the model starts circling, re-litigating settled
 * decisions, chasing tangents, or quietly abandoning development guidelines. The direction
 * judge periodically reviews the recent conversation trail against the goal and returns a
 * verdict:</p>
 * <ul>
 *   <li>{@code on_track} — keep going (no action)</li>
 *   <li>{@code off_track} + {@code redirect_prompt} — the caller feeds the redirect back to
 *       the agent so it adjusts its approach IN PLACE, without losing conversation context</li>
 *   <li>{@code off_track} without a redirect, or after the redirect cap — the caller halts
 *       the turn and hands control back through the supervisor feedback lane</li>
 * </ul>
 *
 * <p>Across turns, one confidently drift-affected turn increments a session streak; a clean,
 * confidently assessed turn resets it. Low-confidence, fail-open, unassessed, and cancelled
 * turns preserve the prior state. The optional state file makes that signal survive judge
 * reload and chat resume. Reaching the configured streak limit lets the caller escalate instead
 * of repeating the same redirect pattern indefinitely.</p>
 *
 * <p><b>Fail-open by construction.</b> A buggy or unavailable judge must never derail a
 * working conversation: every parse error, backend error, timeout, or missing field is
 * treated as {@code on_track} and merely logged. Only a clear, confident, well-formed
 * verdict can redirect or halt a turn.</p>
 *
 * <p>This lane is deliberately NOT subject to the one-shot {@code /judge override}
 * (report-only) posture: that override governs compliance judges, while direction
 * monitoring is an explicit, separately configured supervision contract.</p>
 */
public class DirectionJudge {

    /** Immutable tuning for one direction judge. */
    public record Options(String goal, int checkEvery, int maxRedirects, boolean reportOnly,
                          double confidenceThreshold, int crossTurnDriftLimit) {

        public static final int HARD_MAX_REDIRECTS = 4;
        public static final int HARD_MAX_CROSS_TURN_DRIFT_LIMIT = 20;
        public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;
        public static final int DEFAULT_CROSS_TURN_DRIFT_LIMIT = 3;

        /** Backward-compatible per-turn-only constructor; cross-turn escalation stays off. */
        public Options(String goal, int checkEvery, int maxRedirects, boolean reportOnly) {
            this(goal, checkEvery, maxRedirects, reportOnly,
                    DEFAULT_CONFIDENCE_THRESHOLD, 0);
        }

        public Options {
            checkEvery = Math.max(1, checkEvery);
            maxRedirects = Math.max(0, Math.min(HARD_MAX_REDIRECTS, maxRedirects));
            if (!Double.isFinite(confidenceThreshold)) {
                confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
            }
            confidenceThreshold = Math.max(0.0, Math.min(1.0, confidenceThreshold));
            crossTurnDriftLimit = Math.max(0,
                    Math.min(HARD_MAX_CROSS_TURN_DRIFT_LIMIT, crossTurnDriftLimit));
        }
    }

    /** Durable session-level direction summary (current-turn flags are intentionally absent). */
    public record SessionState(int assessedTurns, int consecutiveDriftTurns,
                               int totalDriftTurns, int crossTurnDriftLimit,
                               String lastDriftReason) {
        public boolean escalationEnabled() {
            return crossTurnDriftLimit > 0;
        }

        public boolean escalationArmed() {
            return escalationEnabled() && consecutiveDriftTurns >= crossTurnDriftLimit;
        }
    }

    /** One direction verdict. {@code failOpen} marks degraded checks (logged, never acted on). */
    public record Verdict(boolean onTrack, double confidence, String reason,
                          String redirectPrompt, boolean failOpen, long latencyMs) {

        public boolean isOffTrack() {
            return !onTrack;
        }

        public boolean isActionable(double confidenceFloor) {
            return isOffTrack() && !failOpen && confidence >= confidenceFloor;
        }

        public static Verdict onTrack(String reason) {
            return new Verdict(true, 1.0, reason, null, false, 0);
        }

        public static Verdict failOpen(String reason) {
            return new Verdict(true, 0.0, reason, null, true, 0);
        }
    }

    static final String DIRECTION_SYSTEM_PROMPT = """
            You are the direction judge for an autonomous coding agent. Your ONLY job is to
            monitor whether the conversation is still moving toward the user's goal, and to
            catch long-conversation derailment: circular work, re-litigating settled decisions,
            tangent-chasing, scope runaway, contradicting explicit development guidelines,
            or quietly abandoning the requested approach.

            You are NOT a code reviewer and NOT a compliance checker. Do not judge style,
            correctness of individual outputs, or static rules — only DIRECTION relative to
            the goal.

            Be conservative: an active agent exploring solutions is NOT drift. Flag off_track
            ONLY when the evidence clearly shows the work is not converging on the goal
            (or is violating an explicit development guideline), and only when you are
            confident. When you flag drift, redirect_prompt must be one specific, actionable
            instruction that puts the work back on track — never a restatement of the task.

            Cross-turn drift state may be supplied as context. It is NOT proof that the current
            turn is off track: judge the current evidence independently. Use the prior streak only
            to recognize repeated failed approaches when the current trail supports that finding.

            Respond with exactly one JSON object and no surrounding prose:
            {"on_track":true,"confidence":0.0,"drift_reason":"","redirect_prompt":"","notes":""}
            on_track: boolean. confidence: 0.0-1.0, your confidence in the verdict.
            drift_reason: required when on_track is false (one or two sentences).
            redirect_prompt: required when on_track is false (specific corrective instruction).
            notes: optional observations worth surfacing to the user.
            """;

    private static final int MAX_GOAL_CHARS = 2_000;
    private static final int MAX_CONVERSATION_CHARS = 6_000;
    private static final int MAX_OUTPUT_CHARS = 4_000;
    private static final String FORMAT_REPAIR_INSTRUCTION = """

            [FORMAT REPAIR]
            Return exactly one JSON object using the system prompt's direction-verdict schema.
            Do not add prose, markdown, code fences, comments, or placeholders.
            [END FORMAT REPAIR]
            """;
    private final JudgeBackend backend;
    private final ObjectMapper objectMapper;
    private final Options options;

    private final Object evalLock = new Object();
    private final AtomicInteger checksThisTurn = new AtomicInteger();
    private final AtomicInteger redirectsThisTurn = new AtomicInteger();
    private volatile boolean currentTurnHadConclusiveCheck;
    private volatile boolean currentTurnHadActionableDrift;
    private volatile boolean currentTurnCompleted;
    private volatile String currentTurnLastDriftReason;
    private volatile int assessedTurns;
    private volatile int consecutiveDriftTurns;
    private volatile int totalDriftTurns;
    private volatile String lastDriftReason;
    private volatile Path stateFile;
    private volatile boolean enabled = true;
    private volatile String goalOverride;
    private volatile boolean goalOverrideSet;
    private volatile JudgementLog judgementLog;
    /** Supplies the user's durable judge guidance (JudgeControl), or null. */
    private volatile java.util.function.Supplier<String> guidanceSupplier = () -> null;

    public DirectionJudge(JudgeBackend backend, ObjectMapper objectMapper, Options options) {
        this.backend = backend;
        this.objectMapper = objectMapper;
        this.options = options == null ? new Options(null, 3, 2, false) : options;
    }

    // ── Configuration ────────────────────────────────────────────────────────

    /** The configured explicit goal, or null when the caller should supply the turn goal. */
    public String getGoal() {
        return goalOverrideSet ? goalOverride : options.goal();
    }

    /** Session-scoped goal update (does not rewrite the project config file). */
    public void setGoal(String goal) {
        synchronized (evalLock) {
            String before = normalize(getGoal());
            this.goalOverride = normalize(goal);
            this.goalOverrideSet = true;
            if (!Objects.equals(before, normalize(getGoal()))) {
                resetCrossTurnStateLocked();
            }
        }
    }

    public int getCheckEvery() {
        return options.checkEvery();
    }

    public int getMaxRedirects() {
        return options.maxRedirects();
    }

    public boolean isReportOnly() {
        return options.reportOnly();
    }

    public double getConfidenceThreshold() {
        return options.confidenceThreshold();
    }

    public int getCrossTurnDriftLimit() {
        return options.crossTurnDriftLimit();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getChecksThisTurn() {
        return checksThisTurn.get();
    }

    public int getRedirectsThisTurn() {
        return redirectsThisTurn.get();
    }

    /** Whether this verdict is allowed to change the agent's control flow. */
    public boolean isActionable(Verdict verdict) {
        return verdict != null && verdict.isActionable(options.confidenceThreshold());
    }

    /** Snapshot of completed, confidently assessed turns in this session. */
    public SessionState getSessionState() {
        synchronized (evalLock) {
            return sessionStateLocked();
        }
    }

    /** Consecutive drift count including the current turn when it already drifted. */
    public int projectedConsecutiveDriftTurns() {
        synchronized (evalLock) {
            return consecutiveDriftTurns + (currentTurnHadActionableDrift ? 1 : 0);
        }
    }

    /** True when current drift reaches the configured cross-turn escalation limit. */
    public boolean isCrossTurnEscalationDue() {
        synchronized (evalLock) {
            return options.crossTurnDriftLimit() > 0
                    && currentTurnHadActionableDrift
                    && consecutiveDriftTurns + 1 >= options.crossTurnDriftLimit();
        }
    }

    /** Bind/load durable state for this chat session (best-effort, never affects judging). */
    public void bindStateFile(Path file) {
        synchronized (evalLock) {
            this.stateFile = file == null ? null : file.toAbsolutePath().normalize();
            loadStateLocked();
        }
    }

    public Path getStateFile() {
        return stateFile;
    }

    /** Explicitly clear cross-turn history without changing judge configuration. */
    public void resetCrossTurnState() {
        synchronized (evalLock) {
            resetCrossTurnStateLocked();
        }
    }

    /** Bind the session judgement log so /judge judgements shows direction verdicts. */
    public void setJudgementLog(JudgementLog log) {
        this.judgementLog = log;
    }

    /** Bind the user's durable judge guidance into every direction prompt. */
    public void setGuidanceSupplier(java.util.function.Supplier<String> supplier) {
        this.guidanceSupplier = supplier == null ? () -> null : supplier;
    }

    public String describe() {
        return "direction-judge(" + (backend != null ? backend.describe() : "none") + ")";
    }

    // ── Turn lifecycle ───────────────────────────────────────────────────────

    /** Reset per-turn counters. Called once per chat turn by the loop. */
    public void beginTurn() {
        synchronized (evalLock) {
            checksThisTurn.set(0);
            redirectsThisTurn.set(0);
            currentTurnHadConclusiveCheck = false;
            currentTurnHadActionableDrift = false;
            currentTurnCompleted = false;
            currentTurnLastDriftReason = null;
        }
    }

    /** Whether another in-place redirect is still allowed this turn. */
    public boolean canRedirect() {
        return redirectsThisTurn.get() < options.maxRedirects();
    }

    /** Record that one redirect was issued this turn. */
    public void recordRedirect() {
        redirectsThisTurn.incrementAndGet();
    }

    /**
     * Finalize one turn's session-level state. A cancelled/failed turn passes
     * {@code countTurn=false}, preserving the prior streak. Repeated calls are idempotent.
     */
    public SessionState completeTurn(boolean countTurn) {
        synchronized (evalLock) {
            if (currentTurnCompleted) {
                return sessionStateLocked();
            }
            currentTurnCompleted = true;
            if (countTurn && currentTurnHadConclusiveCheck) {
                assessedTurns++;
                if (currentTurnHadActionableDrift) {
                    consecutiveDriftTurns++;
                    totalDriftTurns++;
                    lastDriftReason = normalize(currentTurnLastDriftReason);
                } else {
                    consecutiveDriftTurns = 0;
                }
                persistStateLocked();
            }
            return sessionStateLocked();
        }
    }

    // ── Evaluation ───────────────────────────────────────────────────────────

    /**
     * Evaluate the conversation direction against the goal. Never throws and never
     * returns an actionable drift verdict for degraded checks: any failure is fail-open.
     */
    public Verdict checkTurn(String goal, String userMessage, String recentConversation,
                             String currentOutput, int iteration) {
        synchronized (evalLock) {
            checksThisTurn.incrementAndGet();
            long start = System.currentTimeMillis();
            Verdict verdict;
            if (backend == null || !backend.isAvailable()) {
                verdict = Verdict.failOpen("direction judge backend unavailable");
            } else {
                try {
                    String prompt = buildPrompt(
                            goal, userMessage, recentConversation, currentOutput, iteration);
                    String response = generateVerdict(prompt);
                    verdict = ResilientJudgeBackend.isErrorResponse(response)
                            ? Verdict.failOpen("direction judge backend error response")
                            : parse(response, System.currentTimeMillis() - start);
                } catch (Exception failure) {
                    verdict = Verdict.failOpen("direction judge failed: " + failure.getMessage());
                }
            }
            if (!verdict.failOpen()
                    && verdict.confidence() < options.confidenceThreshold()) {
                verdict = Verdict.failOpen("direction verdict confidence "
                        + verdict.confidence() + " is below action threshold "
                        + options.confidenceThreshold() + ": " + verdict.reason());
            }
            Verdict stamped = record(verdict, start);
            observeVerdictLocked(stamped);
            return stamped;
        }
    }

    /** Release backend resources. Best-effort; safe to call more than once. */
    public void close() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // cleanup is best-effort
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private String generateVerdict(String prompt) throws Exception {
        JudgeBackend.JsonSchema schema = directionVerdictSchema();
        String initial = backend.generateJson(prompt, DIRECTION_SYSTEM_PROMPT, schema);
        if (ResilientJudgeBackend.isErrorResponse(initial) || hasParseableJsonObject(initial)) {
            return initial;
        }
        return backend.generateJson(
                prompt + FORMAT_REPAIR_INSTRUCTION, DIRECTION_SYSTEM_PROMPT, schema);
    }

    private boolean hasParseableJsonObject(String response) {
        String json = extractJson(response);
        if (json == null) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    private JudgeBackend.JsonSchema directionVerdictSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("on_track").put("type", "boolean");
        properties.putObject("confidence").put("type", "number")
                .put("minimum", 0.0).put("maximum", 1.0);
        properties.putObject("drift_reason").put("type", "string");
        properties.putObject("redirect_prompt").put("type", "string");
        properties.putObject("notes").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("on_track").add("confidence").add("drift_reason")
                .add("redirect_prompt").add("notes");
        return new JudgeBackend.JsonSchema("kompile_direction_judge", schema, true);
    }

    private String buildPrompt(String goal, String userMessage, String recentConversation,
                               String currentOutput, int iteration) {
        StringBuilder sb = new StringBuilder();
        sb.append("[USER GOAL]\n")
                .append(bound(firstNonBlank(goal, userMessage), MAX_GOAL_CHARS))
                .append("\n[END USER GOAL]\n\n");
        sb.append("[ORIGINAL USER REQUEST]\n")
                .append(bound(userMessage, MAX_GOAL_CHARS))
                .append("\n[END ORIGINAL USER REQUEST]\n\n");
        sb.append("[RECENT CONVERSATION TRAIL]\n")
                .append(bound(recentConversation, MAX_CONVERSATION_CHARS))
                .append("\n[END RECENT CONVERSATION TRAIL]\n\n");
        sb.append("[CURRENT MODEL OUTPUT]\n")
                .append(bound(currentOutput, MAX_OUTPUT_CHARS))
                .append("\n[END CURRENT MODEL OUTPUT]\n\n");
        SessionState state = sessionStateLocked();
        sb.append("[CROSS-TURN DIRECTION STATE]\n")
                .append("confidently assessed turns: ").append(state.assessedTurns()).append('\n')
                .append("consecutive drift-affected turns before this turn: ")
                .append(state.consecutiveDriftTurns()).append('\n')
                .append("total drift-affected turns: ").append(state.totalDriftTurns()).append('\n')
                .append("cross-turn escalation limit: ")
                .append(state.crossTurnDriftLimit() == 0 ? "disabled" : state.crossTurnDriftLimit())
                .append('\n')
                .append("last drift reason: ")
                .append(state.lastDriftReason() == null ? "(none)" : state.lastDriftReason())
                .append("\n[END CROSS-TURN DIRECTION STATE]\n\n");
        sb.append("Direction check #").append(checksThisTurn.get())
                .append(" at model iteration ").append(iteration)
                .append(". Action confidence threshold: ")
                .append(options.confidenceThreshold()).append(".\n");
        String guidance = guidanceSupplier.get();
        if (guidance != null && !guidance.isBlank()) {
            sb.append("\n[USER GUIDANCE TO THE JUDGE]\n")
                    .append("The user has given you standing feedback. Treat it as high-priority:\n")
                    .append(bound(guidance, MAX_GOAL_CHARS))
                    .append("\n[END USER GUIDANCE TO THE JUDGE]");
        }
        return sb.toString();
    }

    private Verdict parse(String response, long latencyMs) {
        String json = extractJson(response);
        if (json == null) {
            return Verdict.failOpen("direction judge did not return JSON");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject() || !node.path("on_track").isBoolean()) {
                return Verdict.failOpen("direction judge response is missing boolean on_track");
            }
            if (!node.path("confidence").isNumber()) {
                return Verdict.failOpen("direction judge response is missing numeric confidence");
            }
            boolean onTrack = node.path("on_track").asBoolean();
            double confidence = node.path("confidence").asDouble();
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                return Verdict.failOpen("direction judge confidence is outside 0.0-1.0");
            }
            String reason = firstNonBlank(node.path("drift_reason").asText(null),
                    node.path("notes").asText(null));
            String redirect = node.path("redirect_prompt").asText(null);
            if (redirect != null && redirect.isBlank()) {
                redirect = null;
            }
            if (!onTrack && (reason == null || reason.isBlank())) {
                // An off-track verdict without a stated reason is not actionable evidence.
                return Verdict.failOpen("direction judge flagged drift without a reason");
            }
            return new Verdict(onTrack, confidence, reason == null ? "on track" : reason.strip(),
                    redirect == null ? null : redirect.strip(), false, latencyMs);
        } catch (Exception parseFailure) {
            return Verdict.failOpen("direction judge JSON parse error: " + parseFailure.getMessage());
        }
    }

    private Verdict record(Verdict verdict, long start) {
        Verdict stamped = new Verdict(verdict.onTrack(), verdict.confidence(), verdict.reason(),
                verdict.redirectPrompt(), verdict.failOpen(),
                verdict.latencyMs() > 0 ? verdict.latencyMs() : System.currentTimeMillis() - start);
        boolean actionable = isActionable(stamped);
        JudgementLog log = judgementLog;
        if (log != null) {
            JudgementRecord record = JudgementRecord.builder()
                    .phase("JUDGE_DIRECTION")
                    .judgeMode("llm")
                    .backend(describe())
                    .latencyMs(stamped.latencyMs())
                    .compliant(!actionable)
                    .stop(actionable && stamped.redirectPrompt() == null)
                    .severity(stamped.failOpen() ? "info"
                            : actionable ? "warning" : "info")
                    .violations(actionable
                            ? java.util.List.of(stamped.reason()) : null)
                    .correctionPrompt(actionable ? stamped.redirectPrompt() : null)
                    .reasoning(stamped.reason())
                    .build();
            log.record(record);
        }
        return stamped;
    }

    private void observeVerdictLocked(Verdict verdict) {
        if (verdict == null || verdict.failOpen()
                || verdict.confidence() < options.confidenceThreshold()) {
            return;
        }
        currentTurnHadConclusiveCheck = true;
        if (verdict.isOffTrack()) {
            currentTurnHadActionableDrift = true;
            currentTurnLastDriftReason = verdict.reason();
        }
    }

    private SessionState sessionStateLocked() {
        return new SessionState(assessedTurns, consecutiveDriftTurns, totalDriftTurns,
                options.crossTurnDriftLimit(), lastDriftReason);
    }

    private void resetCrossTurnStateLocked() {
        assessedTurns = 0;
        consecutiveDriftTurns = 0;
        totalDriftTurns = 0;
        lastDriftReason = null;
        currentTurnHadConclusiveCheck = false;
        currentTurnHadActionableDrift = false;
        currentTurnLastDriftReason = null;
        persistStateLocked();
    }

    private void loadStateLocked() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(
                    Files.readString(stateFile, StandardCharsets.UTF_8));
            String persistedGoal = normalize(root.path("goal").asText(null));
            String currentGoal = normalize(getGoal());
            if (!Objects.equals(persistedGoal, currentGoal)) {
                resetCrossTurnStateLocked();
                return;
            }
            assessedTurns = Math.max(0, root.path("assessedTurns").asInt(0));
            consecutiveDriftTurns = Math.max(0,
                    root.path("consecutiveDriftTurns").asInt(0));
            totalDriftTurns = Math.max(0, root.path("totalDriftTurns").asInt(0));
            lastDriftReason = normalize(root.path("lastDriftReason").asText(null));
        } catch (Exception ignored) {
            // Corrupted or unreadable state is ignored; judging remains fail-open.
        }
    }

    private void persistStateLocked() {
        if (stateFile == null) {
            return;
        }
        try {
            var root = objectMapper.createObjectNode();
            root.put("schemaVersion", 1);
            if (getGoal() != null) root.put("goal", getGoal());
            root.put("assessedTurns", assessedTurns);
            root.put("consecutiveDriftTurns", consecutiveDriftTurns);
            root.put("totalDriftTurns", totalDriftTurns);
            if (lastDriftReason != null) root.put("lastDriftReason", lastDriftReason);
            root.put("updatedAt", Instant.now().toString());
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Persistence must never affect chat control flow.
        }
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        text = text.strip();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private static String bound(String value, int maxChars) {
        if (value == null || value.isBlank()) return "(none)";
        String text = value.strip();
        return text.length() <= maxChars
                ? text : text.substring(0, maxChars) + "\n… [truncated]";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String normalize(String text) {
        if (text == null) return null;
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
