/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Harness-owned workflow state for the CURRENT process (the chat lead).
 *
 * <p>Why this exists: {@link WorkflowTeamEnforcement}'s child channel is the
 * process environment, but a JVM cannot mutate its own environment. The parent
 * chat therefore keeps its team here — explicit, set once at launch or restore
 * — instead of relying on {@code System.getenv}, which is always workflow-empty
 * for a top-level chat. Child processes (the {@code kompile mcp-stdio} server,
 * delegated agents) still receive {@code KOMPILE_WORKFLOW_NAME} /
 * {@code KOMPILE_WORKFLOW_PARTICIPANT} / {@code KOMPILE_WORKFLOW_SESSION}
 * through the spawn channels that really do carry environments; this class is
 * also what those children read at their own resolution points, so env values
 * keep working across the boundary.</p>
 *
 * <p>Persistence: {@link #start(String, WorkflowTeamSnapshot)} writes the team
 * snapshot next to the transcript so {@code kompile chat --resume} restores the
 * SAME team — including the delegation edges and gates the session started
 * with — without re-asking the launch question. A second {@code gates=} line
 * records the gates the user satisfied with {@code /workflow approve}; a resume
 * and the MCP server delegating for a passthrough lead (which finds the session
 * through {@code KOMPILE_WORKFLOW_SESSION}) read the same approvals. A resume
 * never prompts; a changed team aborts the restore (see
 * {@link WorkflowTeamSnapshot#deserialize}).</p>
 */
public final class WorkflowSessionContext {

    private static final String GATES_LINE_PREFIX = "gates=";

    private static volatile WorkflowSessionContext active;

    private final WorkflowTeamEnforcement enforcement;

    private WorkflowSessionContext(WorkflowTeamEnforcement enforcement) {
        this.enforcement = enforcement;
    }

    /** Installs this process's workflow identity without a session; replaces any prior value. */
    public static void activate(WorkflowTeamSnapshot snapshot) {
        install(snapshot, null, Set.of());
    }

    /**
     * Starts the workflow for a new transcript: records the team in the session
     * sidecar with no gates satisfied, then installs it. A session that already
     * recorded this team at this version is the same conversation starting again
     * (a web session whose first run ended before its transcript was written), so
     * it keeps the approvals the user gave it.
     */
    public static void start(String sessionId, WorkflowTeamSnapshot snapshot) {
        Set<String> gates = recordsTeam(sessionId, snapshot) ? satisfiedGates(sessionId) : Set.of();
        install(snapshot, sessionId, gates);
        persist(sessionId, snapshot, gates);
    }

    private static boolean recordsTeam(String sessionId, WorkflowTeamSnapshot snapshot) {
        if (snapshot == null) return false;
        try {
            List<String> lines = sidecarLines(sessionId);
            if (lines.isEmpty()) return false;
            String[] recorded = lines.get(0).trim().split("\\|");
            return recorded.length >= 3 && recorded[0].equals(snapshot.workflowName())
                    && recorded[1].equals(String.valueOf(snapshot.team().version()));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Re-installs a restored team with the gates its session had already satisfied. */
    public static void resume(String sessionId, WorkflowTeamSnapshot snapshot) {
        install(snapshot, sessionId, satisfiedGates(sessionId));
    }

    /**
     * Follows the chat into another transcript (/clear starts one): the team
     * carries over and is recorded for the new session, while gate approvals
     * reset — they belong to the conversation that granted them.
     */
    public static void switchTranscript(String sessionId) {
        WorkflowSessionContext context = active;
        if (context == null || sessionId == null || sessionId.isBlank()
                || sessionId.equals(context.enforcement.sessionId())) {
            return;
        }
        start(sessionId, context.snapshot());
    }

    /**
     * Swaps in an updated definition of the active team (a participant was
     * rebound to another model) while keeping the session and the gates it
     * satisfied, and records it so a resume restores the updated team.
     */
    public static void replace(WorkflowTeamSnapshot snapshot) {
        WorkflowSessionContext context = active;
        if (context == null || snapshot == null) return;
        String sessionId = context.enforcement.sessionId();
        Set<String> gates = context.enforcement.satisfiedGates();
        install(snapshot, sessionId, gates);
        persist(sessionId, snapshot, gates);
    }

    private static void install(WorkflowTeamSnapshot snapshot, String sessionId, Set<String> gates) {
        active = snapshot == null ? null : create(snapshot, sessionId, gates);
    }

    private static WorkflowSessionContext create(WorkflowTeamSnapshot snapshot, String sessionId, Set<String> gates) {
        WorkflowTeamEnforcement enforcement = WorkflowTeamEnforcement.forCaller(snapshot,
                WorkflowTeamEnforcement.resolveCallerParticipant(snapshot.team()), sessionId);
        gates.forEach(enforcement::satisfyGate);
        return new WorkflowSessionContext(enforcement);
    }

    /**
     * The team {@code sessionId} recorded, with the gates it satisfied, without
     * installing it in this process: a web approval between runs, when no
     * harness holds the team. {@code null} when the session has none.
     *
     * @throws IOException when the recorded team can no longer be resolved
     */
    public static WorkflowSessionContext recorded(String sessionId, Path projectRoot) throws IOException {
        WorkflowTeamSnapshot snapshot = restore(sessionId, projectRoot);
        return snapshot == null ? null : create(snapshot, sessionId, satisfiedGates(sessionId));
    }

    /** The active context, or {@code null} when this session has no workflow team. */
    public static WorkflowSessionContext current() {
        return active;
    }

    /**
     * The workflow participant a tool call acts as: the one bound on its
     * context (a delegated child, or the lead during its turn), else this
     * process's own identity. {@code null} outside a workflow.
     */
    public static WorkflowTeamEnforcement enforcementFor(ToolContext context) {
        WorkflowTeamEnforcement bound = context == null ? null : context.getWorkflow();
        if (bound != null) return bound;
        WorkflowSessionContext session = active;
        return session == null ? null : session.enforcement;
    }

    /**
     * The enforcement a delegated participant's tools run under: the
     * delegating context's team, seen from that participant. {@code null} when
     * the launch is not a workflow participant; a participant launch without an
     * active team fails closed, so it never runs with the lead's unrestricted tools.
     */
    public static WorkflowTeamEnforcement participantEnforcement(String participant, ToolContext delegatingContext) {
        if (participant == null) return null;
        WorkflowTeamEnforcement delegating = enforcementFor(delegatingContext);
        if (delegating == null) {
            throw new IllegalStateException("Workflow participant '" + participant
                    + "' was delegated outside an active workflow team");
        }
        return WorkflowTeamEnforcement.forCaller(delegating.snapshot(), participant, delegating.sessionId());
    }

    public WorkflowTeamEnforcement enforcement() {
        return enforcement;
    }

    public WorkflowTeamSnapshot snapshot() {
        return enforcement.snapshot();
    }

    /** Team name for banner/status display. */
    public String workflowName() {
        return snapshot().workflowName();
    }

    /** True when this process holds a workflow identity. */
    public static boolean isActive() {
        return active != null;
    }

    /**
     * Marks a gate satisfied for this session and records it in the session
     * sidecar. Only the user does this ({@code /workflow approve}); no tool
     * reaches it, so the lead cannot approve its own design.
     */
    public void satisfyGate(String gate) {
        enforcement.satisfyGate(gate);
        persist(enforcement.sessionId(), snapshot(), enforcement.satisfiedGates());
    }

    /**
     * The user's {@code /workflow approve [gate]}: approves {@code gate}, or
     * with none the gate that blocks next — implementation until it is
     * satisfied, then completion. Returns the gate approved.
     *
     * @throws IllegalArgumentException when the team has no such gate, or
     *         nothing is left to approve
     */
    public String approve(String gate) {
        WorkflowTeam.Gates gates = snapshot().team().gates();
        String requested = WorkflowTeam.key(gate);
        String approved;
        if (!requested.isEmpty()) {
            if (!requested.equals(gates.implementationRequires()) && !requested.equals(gates.completionRequires())) {
                throw new IllegalArgumentException("Workflow '" + workflowName() + "' has no gate '"
                        + gate.trim() + "'. " + gateSummary(gates));
            }
            approved = requested;
        } else if (!enforcement.implementationGateSatisfied()) {
            approved = gates.implementationRequires();
        } else if (!enforcement.completionGateSatisfied()) {
            approved = gates.completionRequires();
        } else {
            throw new IllegalArgumentException(gates.hasImplementationGate() || gates.hasCompletionGate()
                    ? "Every gate of workflow '" + workflowName() + "' is already approved."
                    : "Workflow '" + workflowName() + "' has no gates to approve.");
        }
        satisfyGate(approved);
        return approved;
    }

    private static String gateSummary(WorkflowTeam.Gates gates) {
        List<String> names = new ArrayList<>();
        if (gates.hasImplementationGate()) names.add("implementation '" + gates.implementationRequires() + "'");
        if (gates.hasCompletionGate()) names.add("completion '" + gates.completionRequires() + "'");
        return names.isEmpty() ? "It has no gates." : "Its gates: " + String.join(", ", names) + ".";
    }

    /** Environment values the harness exports to spawned children (MCP server, agents). */
    public Map<String, String> childEnvironment() {
        return enforcement.childEnvironment(enforcement.callerParticipant());
    }

    /**
     * The workflow environment this process passes to every child it spawns:
     * the in-process context when set (the chat lead), else this process's own
     * inherited variables (a delegated agent that is itself a workflow
     * participant spawning further children). Empty when no workflow applies.
     */
    public static Map<String, String> inheritableEnvironment() {
        WorkflowSessionContext context = active;
        if (context != null) {
            return context.childEnvironment();
        }
        String name = System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME);
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, name);
        String participant = System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT);
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT,
                participant == null ? "" : participant);
        String session = System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION);
        if (session != null && !session.isBlank()) {
            env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION, session);
        }
        return env;
    }

    /**
     * The workflow environment for a child launched as {@code participant}: this
     * process's team seen from that participant, so the delegate is enforced as
     * itself rather than as the participant that delegated. With no participant
     * it is {@link #inheritableEnvironment()}. A participant launch outside a
     * workflow fails closed, so a delegate never runs unenforced.
     */
    public static Map<String, String> inheritableEnvironment(String participant) {
        if (participant == null || participant.isBlank()) {
            return inheritableEnvironment();
        }
        WorkflowSessionContext context = active;
        if (context != null) {
            return context.enforcement.childEnvironment(participant);
        }
        Map<String, String> env = new LinkedHashMap<>(inheritableEnvironment());
        if (env.isEmpty()) {
            throw new IllegalStateException("Workflow participant '" + participant
                    + "' was delegated outside an active workflow team");
        }
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT, WorkflowTeam.key(participant));
        return env;
    }

    /**
     * The workflow identity a child's launch environment carries, or {@code null}
     * when it carries none. The kompile MCP server configured for that child must
     * run as the same participant as the child itself, not as this process.
     */
    public static Map<String, String> workflowEnvironmentOf(Map<String, String> environment) {
        String name = environment == null ? null : environment.get(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME);
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : List.of(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME,
                WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT, WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION)) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) {
                env.put(key, value);
            }
        }
        return env;
    }

    /** The system-prompt section when the chat's configuration is unknown. */
    public String systemPromptSection() {
        return systemPromptSection(null);
    }

    /**
     * Workflow identity for the chat system prompt under the chat
     * {@code current} configures: names the team, this process's participant,
     * every member with its model, capabilities, and delegation targets, the
     * routing purposes, and how gates are satisfied. Changes only when the user
     * rebinds a model, so it stays inside the cache-stable system prefix.
     */
    public String systemPromptSection(ChatConfig current) {
        WorkflowTeam team = snapshot().team();
        String caller = enforcement().callerParticipant();
        StringBuilder sb = new StringBuilder();
        sb.append("Active workflow team: ").append(team.name())
                .append(" (v").append(team.version()).append("). You are participant '")
                .append(caller).append("' (role: ")
                .append(team.participant(caller).role()).append(").\n");
        sb.append("Team members:");
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            sb.append("\n- ").append(participant.id()).append(" (role: ").append(participant.role())
                    .append("; model: ").append(WorkflowModelDefaults.describe(team, participant, current));
            if (!participant.capabilities().isEmpty()) {
                sb.append("; ").append(String.join(", ", participant.capabilities()));
            }
            if (participant.canDelegate() && !participant.delegatesTo().isEmpty()) {
                sb.append("; may delegate to ").append(String.join(", ", participant.delegatesTo()));
            }
            sb.append(')');
        }
        if (!team.routing().isEmpty()) {
            List<String> purposes = new ArrayList<>();
            team.routing().forEach((purpose, target) -> purposes.add(purpose + " -> " + target));
            sb.append("\nDelegation purposes: ").append(String.join(", ", purposes)).append('.');
        }
        WorkflowTeam.Gates gates = team.gates();
        if (gates.hasImplementationGate()) {
            sb.append("\nImplementation gate '").append(gates.implementationRequires())
                    .append("': delegations to edit-capable members are refused until the user approves it. ")
                    .append("Present the design and ask for approval first.");
        }
        if (gates.hasCompletionGate()) {
            sb.append("\nCompletion gate '").append(gates.completionRequires())
                    .append("': the work is finished only once the user approves it; ask them to review ")
                    .append("before reporting done.");
        }
        if (gates.hasImplementationGate() || gates.hasCompletionGate()) {
            sb.append("\nOnly the user approves gates, with /workflow approve; you cannot.");
        }
        if (WorkflowModelDefaults.delegatesToCliAgents(current)) {
            sb.append("\nDelegate with the kompile task or multi_task tool, passing purpose (or a team role) ")
                    .append("and no agent or model: each delegate runs as its participant's CLI agent and model.");
        } else {
            sb.append("\nDelegate with the task tool, passing purpose (or a team role): each delegate runs ")
                    .append("on its participant's model with that participant's tools, and cannot delegate further.");
        }
        sb.append(" The harness picks the participant and refuses delegations outside the team's ")
                .append("purposes, delegation edges, and gates.");
        return sb.toString();
    }

    // ── Per-transcript persistence (resume restores the same team) ─────────

    /** Session sidecar path: {@code <conversations>/<sessionId>.workflow}. */
    public static Path sessionPath(String sessionId) {
        return KompileHome.homeDirectory().toPath().resolve("conversations")
                .resolve(sessionId + ".workflow");
    }

    /** Writes the snapshot, with no gates satisfied, so a later resume can restore it. */
    public static void persist(String sessionId, WorkflowTeamSnapshot snapshot) {
        persist(sessionId, snapshot, Set.of());
    }

    /**
     * Writes the snapshot and the gates satisfied so far. Best effort: failures
     * are reported, not thrown.
     */
    public static void persist(String sessionId, WorkflowTeamSnapshot snapshot, Set<String> satisfiedGates) {
        if (sessionId == null || sessionId.isBlank() || snapshot == null) return;
        StringBuilder content = new StringBuilder(snapshot.serialize()).append('\n');
        if (satisfiedGates != null && !satisfiedGates.isEmpty()) {
            content.append(GATES_LINE_PREFIX)
                    .append(String.join(",", new TreeSet<>(satisfiedGates))).append('\n');
        }
        try {
            Path path = sessionPath(sessionId);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: could not persist workflow session state: " + e.getMessage());
        }
    }

    /** Removes the sidecar (e.g. after a resume aborted the restore and no team applies). */
    public static void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            Files.deleteIfExists(sessionPath(sessionId));
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /**
     * Restores the team recorded for a resumed transcript. Returns {@code null}
     * when the session had no workflow. A recorded workflow that can no longer
     * be resolved (team deleted/changed, role removed) aborts the resume via
     * {@link IOException} — resuming must never silently drop the enforcement
     * the session started under.
     */
    public static WorkflowTeamSnapshot restore(String sessionId, Path projectRoot)
            throws IOException {
        List<String> lines = sidecarLines(sessionId);
        if (lines.isEmpty() || lines.get(0).isBlank()) return null;
        return WorkflowTeamSnapshot.deserialize(lines.get(0).trim(), new RoleManager(projectRoot));
    }

    /**
     * Gates the user satisfied in a session, read from its sidecar; empty when
     * the session is unknown or recorded none. The delegating MCP server calls
     * this per delegation so approvals given after it started still apply.
     */
    public static Set<String> satisfiedGates(String sessionId) {
        try {
            for (String line : sidecarLines(sessionId)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(GATES_LINE_PREFIX)) {
                    Set<String> gates = new LinkedHashSet<>();
                    Arrays.stream(trimmed.substring(GATES_LINE_PREFIX.length()).split(","))
                            .map(WorkflowTeam::key)
                            .filter(gate -> !gate.isEmpty())
                            .forEach(gates::add);
                    return Set.copyOf(gates);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read workflow gate state: " + e.getMessage());
        }
        return Set.of();
    }

    private static List<String> sidecarLines(String sessionId) throws IOException {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        Path path = sessionPath(sessionId.trim());
        if (!Files.isRegularFile(path)) return List.of();
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
}
