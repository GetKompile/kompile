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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single enforcement point for a workflow team session. Both delegation
 * tools (MCP {@code task}/{@code multi_task} and the direct-chat {@code task})
 * ask this evaluator before launching anything, and mutation-capable tools ask
 * it before executing.
 *
 * <p>Key properties:</p>
 * <ul>
 *   <li>The caller's participant identity is harness-owned (session environment),
 *       never read from tool arguments — passing {@code role=designer} in a
 *       delegation request confers nothing.</li>
 *   <li>A workflow restriction only narrows existing permissions; it never
 *       widens them.</li>
 *   <li>Denials are actionable: they name the participant, the capability, and
 *       the permitted alternative.</li>
 * </ul>
 */
public final class WorkflowTeamEnforcement {

    /** Tool id prefixes that require the edit capability. */
    private static final List<String> EDIT_TOOL_PREFIXES = List.of(
            "edit", "write", "patch", "bash.write", "bash.destructive");

    /** Tool ids that require the delegate capability. */
    private static final Set<String> DELEGATION_TOOLS = Set.of("task", "multi_task", "quorum_task");

    private final WorkflowTeamSnapshot snapshot;
    private final String callerParticipant;
    private final Set<String> satisfiedGates = ConcurrentHashMap.newKeySet();

    public WorkflowTeamEnforcement(WorkflowTeamSnapshot snapshot, String callerParticipant) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.callerParticipant = WorkflowTeam.key(callerParticipant);
        if (snapshot.team().participant(this.callerParticipant) == null) {
            throw new IllegalArgumentException("Harness identity '" + callerParticipant
                    + "' is not a participant of workflow '" + snapshot.workflowName() + "'");
        }
    }

    public WorkflowTeamSnapshot snapshot() { return snapshot; }
    public WorkflowTeam team() { return snapshot.team(); }
    public String callerParticipant() { return callerParticipant; }

    // ── Delegation enforcement ──────────────────────────────────────────────

    /**
     * One delegation decision. On allow, {@link DelegationDecision.Allowed}
     * carries the workflow-resolved participant and role the tool MUST launch with.
     */
    public sealed interface DelegationDecision {
        String resolvedParticipant();

        record Allowed(String resolvedParticipant, String resolvedRole) implements DelegationDecision {
            @Override public String resolvedParticipant() { return resolvedParticipant; }
        }

        record Denied(String reason, String resolvedParticipant) implements DelegationDecision {
            @Override public String resolvedParticipant() { return resolvedParticipant; }
        }
    }

    /**
     * Evaluate one delegation. Selection precedence: explicit {@code purpose}
     * routing, else a role that unambiguously matches one participant, else
     * refusal. Explicit agent/model/thinking overrides in a workflow session
     * are launch details owned by the workflow; the resolved participant is
     * authoritative and callers must apply it.
     */
    public DelegationDecision evaluateDelegation(String purpose, String requestedRole) {
        WorkflowTeam team = snapshot.team();
        WorkflowTeam.Participant caller = team.participant(callerParticipant);

        if (!caller.canDelegate()) {
            return new DelegationDecision.Denied(
                    "Participant '" + callerParticipant + "' lacks the 'delegate' capability in workflow '"
                            + team.name() + "'. " + describeAssignment(caller),
                    callerParticipant);
        }

        // Resolve the destination.
        WorkflowTeam.Participant target;
        if (purpose != null && !purpose.isBlank()) {
            target = team.route(purpose);
            if (target == null) {
                return new DelegationDecision.Denied(
                        "Purpose '" + purpose + "' has no route in workflow '" + team.name()
                                + "'. Available purposes: " + team.routing().keySet()
                                + (team.routing().isEmpty() ? " (this workflow defines none)" : ""),
                        callerParticipant);
            }
        } else if (requestedRole != null && !requestedRole.isBlank()) {
            WorkflowTeam.Participant matched = participantForRole(requestedRole);
            if (matched == null) {
                return new DelegationDecision.Denied(
                        "Role '" + requestedRole + "' is not bound to any participant of workflow '"
                                + team.name() + "'. Delegate by purpose instead: " + team.routing().keySet(),
                        callerParticipant);
            }
            target = matched;
        } else {
            return new DelegationDecision.Denied(
                    "Delegation inside workflow '" + team.name() + "' needs a routing 'purpose' from: "
                            + team.routing().keySet()
                            + ". Select roles/agents directly instead of work purposes.",
                    callerParticipant);
        }

        // Delegation edges.
        if (!team.mayDelegate(caller.id(), target.id())) {
            return new DelegationDecision.Denied(
                    "Workflow '" + team.name() + "' does not allow '" + caller.id() + "' to delegate to '"
                            + target.id() + "'. Permitted edges from '" + caller.id() + "': "
                            + caller.delegatesTo(),
                    callerParticipant);
        }

        // Implementation gate: delegating to an edit-capable participant requires it.
        if (target.canEdit() && !implementationGateSatisfied()) {
            WorkflowTeam.Gates gates = team.gates();
            return new DelegationDecision.Denied(
                    "Workflow '" + team.name() + "' gates implementation: "
                            + (gates.hasImplementationGate()
                            ? "'" + gates.implementationRequires() + "' must be satisfied first"
                            : "the implementation gate is unsatisfied")
                            + ". Complete it (e.g. obtain the user's design approval) before delegating edits.",
                    target.id());
        }

        return new DelegationDecision.Allowed(target.id(), snapshot.resolvedRole(target.id()));
    }

    /** Resolves how many instances a delegation batch may launch. */
    public DelegationDecision evaluateBatchSize(int requestedInstances) {
        WorkflowTeam team = snapshot.team();
        int instances = Math.max(1, requestedInstances);
        if (instances > team.maxConcurrentWorkers()) {
            return new DelegationDecision.Denied(
                    "Requested " + instances + " instances but workflow '" + team.name()
                            + "' allows at most " + team.maxConcurrentWorkers() + " concurrent workers.",
                    callerParticipant);
        }
        return new DelegationDecision.Allowed(callerParticipant, snapshot.resolvedRole(callerParticipant));
    }

    // ── Tool enforcement ────────────────────────────────────────────────────

    /** Typed result for tool-use checks. */
    public record ToolDecision(boolean allowed, String reason) {
        public static ToolDecision allow() {
            return new ToolDecision(true, null);
        }

        public static ToolDecision deny(String reason) {
            return new ToolDecision(false, Objects.requireNonNull(reason, "reason"));
        }
    }

    /**
     * Whether the caller may use this tool. Called by tools themselves before
     * execution; denial is absolute for the session (a workflow narrows, never
     * widens, existing permissions).
     */
    public ToolDecision evaluateToolUse(String toolId) {
        WorkflowTeam team = snapshot.team();
        WorkflowTeam.Participant caller = team.participant(callerParticipant);
        String tool = toolId == null ? "" : toolId.toLowerCase(Locale.ROOT);

        if (caller.isChatOnly()) {
            return ToolDecision.deny("Participant '" + callerParticipant
                    + "' is chat-only in workflow '" + team.name() + "'.");
        }
        if (!caller.canRead()) {
            boolean readish = tool.equals("read") || tool.equals("grep") || tool.equals("glob")
                    || tool.equals("list") || tool.equals("search");
            if (readish) {
                return ToolDecision.deny("Participant '" + callerParticipant
                        + "' lacks 'read' in workflow '" + team.name() + "'.");
            }
        }
        if (DELEGATION_TOOLS.contains(tool) && !caller.canDelegate()) {
            return ToolDecision.deny("Participant '" + callerParticipant
                    + "' lacks the 'delegate' capability in workflow '" + team.name() + "'.");
        }
        boolean mutating = EDIT_TOOL_PREFIXES.stream().anyMatch(tool::startsWith);
        if (mutating && !caller.canEdit()) {
            return ToolDecision.deny("Participant '" + callerParticipant
                    + "' lacks 'edit-assigned-files' in workflow '" + team.name() + "'. "
                    + describeAssignment(caller)
                    + " Delegate edits to a participant that holds that capability.");
        }
        return ToolDecision.allow();
    }

    // ── Gates ───────────────────────────────────────────────────────────────

    public boolean implementationGateSatisfied() {
        WorkflowTeam.Gates gates = team().gates();
        if (!gates.hasImplementationGate()) return true;
        return satisfiedGates.contains(gates.implementationRequires());
    }

    public boolean completionGateSatisfied() {
        WorkflowTeam.Gates gates = team().gates();
        if (!gates.hasCompletionGate()) return true;
        return satisfiedGates.contains(gates.completionRequires());
    }

    /** Marks a gate satisfied (e.g. the user approved the design). Idempotent. */
    public void satisfyGate(String gate) {
        String key = WorkflowTeam.key(gate);
        if (!key.isBlank()) satisfiedGates.add(key);
    }

    public Set<String> satisfiedGates() {
        return Set.copyOf(satisfiedGates);
    }

    /** Outstanding workflow obligations for /workflow status display. */
    public String outstandingObligations() {
        WorkflowTeam.Gates gates = team().gates();
        List<String> outstanding = new java.util.ArrayList<>();
        if (gates.hasImplementationGate() && !implementationGateSatisfied()) {
            outstanding.add("implementation gate: " + gates.implementationRequires());
        }
        if (gates.hasCompletionGate() && !completionGateSatisfied()) {
            outstanding.add("completion gate: " + gates.completionRequires());
        }
        return outstanding.isEmpty() ? "none" : String.join("; ", outstanding);
    }

    /** Live status block for /workflow. */
    public String statusLine() {
        WorkflowTeam team = team();
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow '").append(team.name()).append("' — you are '")
                .append(callerParticipant).append("' (")
                .append(team.participant(callerParticipant).role()).append(")\n");
        sb.append("Purposes: ").append(team.routing().isEmpty() ? "(none)" : team.routing()).append("\n");
        sb.append("Delegable from you: ").append(team.participant(callerParticipant).delegatesTo()).append("\n");
        sb.append("Outstanding: ").append(outstandingObligations()).append("\n");
        sb.append("Satisfied gates: ").append(satisfiedGates.isEmpty() ? "(none)" : satisfiedGates);
        return sb.toString();
    }

    // ── Environment contract ────────────────────────────────────────────────

    /** Environment variable carrying the active workflow name into child processes. */
    public static final String ENV_WORKFLOW_NAME = "KOMPILE_WORKFLOW_NAME";
    /** Environment variable carrying the harness-owned participant id into child processes. */
    public static final String ENV_WORKFLOW_PARTICIPANT = "KOMPILE_WORKFLOW_PARTICIPANT";

    /**
     * Environment values children inherit so a delegated child becomes that
     * participant under the same workflow. The harness sets these; tool
     * arguments cannot alter them.
     */
    public Map<String, String> childEnvironment(String childParticipant) {
        WorkflowTeam.Participant child = team().participant(childParticipant);
        if (child == null) {
            throw new IllegalArgumentException("Unknown workflow participant: " + childParticipant);
        }
        Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put(ENV_WORKFLOW_NAME, team().name());
        env.put(ENV_WORKFLOW_PARTICIPANT, child.id());
        return env;
    }

    /**
     * Resolves the harness-owned identity of the current process: the
     * participant inherited from {@link #ENV_WORKFLOW_PARTICIPANT}, defaulting
     * to the workflow lead when absent (top-level chat process).
     */
    public static String resolveCallerParticipant(WorkflowTeam team) {
        String inherited = System.getenv(ENV_WORKFLOW_PARTICIPANT);
        if (inherited != null && !inherited.isBlank()) {
            String key = WorkflowTeam.key(inherited);
            if (team.participant(key) != null) return key;
        }
        return team.lead();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private WorkflowTeam.Participant participantForRole(String role) {
        String wanted = WorkflowTeam.key(role);
        for (WorkflowTeam.Participant participant : team().participants().values()) {
            if (WorkflowTeam.key(snapshot.resolvedRole(participant.id())).equals(wanted)) {
                return participant;
            }
        }
        return null;
    }

    private String describeAssignment(WorkflowTeam.Participant participant) {
        return "This participant's assignment: " + participant.id() + " (" + participant.role()
                + "), capabilities: " + participant.capabilities();
    }

    /** Factory with an explicit name for readability at call sites. */
    public static WorkflowTeamEnforcement forCaller(WorkflowTeamSnapshot snapshot, String participant) {
        return new WorkflowTeamEnforcement(snapshot, participant);
    }

    /** Participants reachable through delegation edges from the caller (diagnostics). */
    public Set<String> reachableFromCaller() {
        Set<String> seen = new LinkedHashSet<>();
        collect(callerParticipant, seen);
        seen.remove(callerParticipant);
        return seen;
    }

    private void collect(String from, Set<String> seen) {
        WorkflowTeam.Participant participant = team().participant(from);
        if (participant == null) return;
        for (String next : participant.delegatesTo()) {
            if (seen.add(next)) collect(next, seen);
        }
    }
}
