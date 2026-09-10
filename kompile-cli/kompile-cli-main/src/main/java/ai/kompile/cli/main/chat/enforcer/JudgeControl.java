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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Session-scoped user control over the chat judge.
 *
 * <p>Capabilities driven from the {@code /judge} slash command:</p>
 * <ol>
 *   <li><b>Durable guidance</b> — free-form user feedback ("the judge was wrong about X",
 *       "file edits are approved") that is injected into every subsequent judge prompt so the
 *       judge can actually be corrected, not just observed.</li>
 *   <li><b>One-shot override</b> — when armed, the NEXT chat turn runs the judges in
 *       report-only mode: they still evaluate and log their opinion, but they cannot block,
 *       correct, rewrite, or cancel anything in that turn. This is both "manually override
 *       the judge" and "turn off judge cancellation for the next turn".</li>
 *   <li><b>Command approval</b> — {@code /judge approve [--pattern] <command>} overrides the
 *       tool judge for matching bash commands during the next turn (exact by default), without disabling
 *       permissions, workflow, or dedicated-tool protections. Not persisted.</li>
 * </ol>
 *
 * <p>The guidance is persisted to {@code ~/.kompile/sessions/<sessionId>/judge-control.json}
 * so it survives process restarts. The one-shot override is deliberately NOT persisted: a
 * decision to disable judge teeth must never silently outlive the session that made it.</p>
 */
public class JudgeControl {

    /** Immutable judge posture for one chat turn, captured at turn start. */
    public record TurnSnapshot(String guidance, boolean reportOnly, boolean enabled, String approvedCommand,
                               CommandApprovalPattern approvalPattern) {

        public TurnSnapshot(String guidance, boolean reportOnly, boolean enabled, String approvedCommand) {
            this(guidance, reportOnly, enabled, approvedCommand, null);
        }

        public TurnSnapshot(String guidance, boolean reportOnly, boolean enabled) {
            this(guidance, reportOnly, enabled, "");
        }

        /** Whole-command matching; wildcard semantics require explicit user opt-in. */
        public boolean approvesCommand(String toolName, String toolInput) {
            return approvedCommand != null && !approvedCommand.isBlank()
                    && "bash".equals(JudgeToolPolicy.canonicalToolName(toolName))
                    && (approvalPattern == null
                    ? approvedCommand.equals(ShellMandatePolicy.extractCommandFromJson(toolInput))
                    : approvalPattern.matches(ShellMandatePolicy.extractCommandFromJson(toolInput)));
        }

        public static final TurnSnapshot NONE = new TurnSnapshot(null, false, true);

        public boolean hasGuidance() {
            return guidance != null && !guidance.isBlank();
        }
    }

    private final String sessionId;
    private final Path file;
    private final ObjectMapper mapper;

    private volatile String guidance = "";
    private volatile boolean overrideNext;
    private String approvedCommandNext = "";
    private CommandApprovalPattern approvalPatternNext;

    /** User-owned, non-persistent approval; expires at the end of the next turn. */
    public synchronized void approveCommandNext(String command) {
        approvedCommandNext = normalize(command);
        approvalPatternNext = null;
    }

    public synchronized void approvePatternNext(String expression) {
        CommandApprovalPattern compiled = new CommandApprovalPattern(expression);
        approvedCommandNext = normalize(expression);
        approvalPatternNext = compiled;
    }

    public synchronized boolean isApprovalPatternNext() {
        return approvalPatternNext != null;
    }

    public synchronized String getApprovedCommandNext() {
        return approvedCommandNext;
    }
    /** Session-only switch. Deliberately not persisted across process restarts. */
    private volatile boolean enabled = true;

    /**
     * Creates the control for a session and best-effort loads previously persisted
     * guidance from {@code <sessionsDir>/<sessionId>/judge-control.json}.
     */
    public JudgeControl(String sessionId, Path sessionsDir) {
        this.sessionId = sessionId;
        this.file = sessionsDir.resolve(sessionId).resolve("judge-control.json");
        this.mapper = JsonUtils.newStandardMapper();
        load();
    }

    /** Load control for a session under the standard {@code ~/.kompile/sessions} root. */
    public static JudgeControl load(String sessionId) {
        return new JudgeControl(sessionId, JudgementLog.sessionsRoot());
    }

    // ── Durable guidance ─────────────────────────────────────────────────────

    /** Current durable guidance, or the empty string when none is set. */
    public String getGuidance() {
        return guidance;
    }

    public boolean hasGuidance() {
        return guidance != null && !guidance.isBlank();
    }

    /** Replace the durable guidance injected into every future judge prompt. */
    public void setGuidance(String text) {
        this.guidance = normalize(text);
        save();
    }

    /** Remove the durable guidance. */
    public void clearGuidance() {
        this.guidance = "";
        save();
    }

    // ── One-shot override ────────────────────────────────────────────────────

    /** Arm (or explicitly disarm) the report-only override for the next turn. */
    public void setOverrideNext(boolean override) {
        this.overrideNext = override;
    }

    public void clearOverrideNext() {
        this.overrideNext = false;
    }

    public boolean isOverrideNextSet() {
        return overrideNext;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            // A stale one-shot override must not unexpectedly fire after re-enabling.
            this.overrideNext = false;
            this.approvedCommandNext = "";
            this.approvalPatternNext = null;
        }
    }

    /**
     * Capture the judge posture for a new chat turn and consume the one-shot override.
     * Called exactly once per turn by {@code AgenticChatLoop}, so a single user decision
     * covers every model iteration and tool call within that turn.
     */
    public synchronized TurnSnapshot beginTurn() {
        boolean reportOnly = overrideNext;
        overrideNext = false;
        String approvedCommand = approvedCommandNext;
        CommandApprovalPattern approvalPattern = approvalPatternNext;
        approvedCommandNext = "";
        approvalPatternNext = null;
        String turnGuidance = guidance;
        if (!approvedCommand.isBlank()) {
            turnGuidance += "\nUser explicitly approved this "
                    + (approvalPattern == null ? "exact bash command" : "bash token pattern (* within an argument, ** trailing arguments)")
                    + " for this turn only: "
                    + approvedCommand + "\nThis approval overrides judge objections to that command,"
                    + " not permissions, workflow, or dedicated-tool/managed-memory protections.";
        }
        return new TurnSnapshot(turnGuidance, reportOnly, enabled, approvedCommand, approvalPattern);
    }

    public String getSessionId() {
        return sessionId;
    }

    /** Persistence file for this session's guidance (also shown by /judge status). */
    public Path getFile() {
        return file;
    }

    // ── Persistence (guidance only) ──────────────────────────────────────────

    private void save() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("guidance", guidance);
            root.put("updatedAt", java.time.Instant.now().toString());
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Persistence is best-effort; in-memory state stays authoritative for this run.
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            this.guidance = normalize(root.path("guidance").asText(""));
        } catch (Exception ignored) {
            // Corrupted state: start clean rather than fail the session.
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip();
    }
}
