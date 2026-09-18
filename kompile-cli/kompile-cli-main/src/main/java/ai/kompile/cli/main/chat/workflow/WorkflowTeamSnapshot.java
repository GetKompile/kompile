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

import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable resolution of a {@link WorkflowTeam} at chat-launch time: the
 * team, the role resolved for every participant, and the launch timestamp.
 *
 * <p>Resolution fails hard on unknown roles: an enforceable team cannot be
 * built from a role that does not exist, and falling back to prompt-only
 * instructions would present instructions as enforcement. Activation refuses
 * instead.</p>
 */
public final class WorkflowTeamSnapshot {

    private final WorkflowTeam team;
    private final Map<String, String> resolvedRoles;
    private final String launchedAt;

    public WorkflowTeamSnapshot(WorkflowTeam team, Map<String, String> resolvedRoles, String launchedAt) {
        Objects.requireNonNull(team, "team");
        this.team = team;
        this.resolvedRoles = resolvedRoles == null ? Map.of() : Map.copyOf(resolvedRoles);
        this.launchedAt = launchedAt == null || launchedAt.isBlank()
                ? Instant.now().toString() : launchedAt;
    }

    /** Resolves every participant's role eagerly against the project's roles. */
    public static WorkflowTeamSnapshot resolve(WorkflowTeam team, RoleManager roleManager) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            RoleConfig role = roleManager == null ? null : roleManager.getRole(participant.role());
            if (role == null) {
                throw new IllegalArgumentException("Workflow '" + team.name() + "' participant '"
                        + participant.id() + "' references unknown role '" + participant.role()
                        + "'. Create the role (or fix the workflow) before starting a chat with it.");
            }
            roles.put(participant.id(), participant.role());
        }
        return new WorkflowTeamSnapshot(team, roles, Instant.now().toString());
    }

    public WorkflowTeam team() { return team; }
    public Map<String, String> resolvedRoles() { return resolvedRoles; }
    public String launchedAt() { return launchedAt; }
    public String workflowName() { return team.name(); }

    public String resolvedRole(String participantId) {
        return resolvedRoles.get(WorkflowTeam.key(participantId));
    }

    /** Session-state persistence: {@code name|version|launchedAt|p=r|...}. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(team.name()).append('|').append(team.version()).append('|').append(launchedAt);
        resolvedRoles.forEach((participant, role) -> sb.append('|')
                .append(participant).append('=').append(role));
        return sb.toString();
    }

    /**
     * Restores a snapshot written by {@link #serialize()}. The team definition must
     * still exist with the same version; a changed team aborts the restore so a
     * resume never silently adopts different enforcement than the session started with.
     */
    public static WorkflowTeamSnapshot deserialize(String encoded,
                                                   ai.kompile.cli.main.chat.roles.RoleManager roleManager)
            throws java.io.IOException {
        if (encoded == null || encoded.isBlank()) return null;
        String[] segments = encoded.split("\\|");
        if (segments.length < 3) return null;
        WorkflowTeam team = WorkflowTeamStore.get(roleManager.workingDirectory(), segments[0]);
        if (team == null) {
            throw new java.io.IOException("Workflow '" + segments[0] + "' no longer exists; the session "
                    + "snapshot cannot be restored. Re-run `kompile chat` to pick a workflow.");
        }
        if (team.version() != Integer.parseInt(segments[1])) {
            throw new java.io.IOException("Workflow '" + segments[0] + "' changed since this session "
                    + "started (v" + segments[1] + " → v" + team.version() + "). Re-run `kompile chat`.");
        }
        Map<String, String> roles = new LinkedHashMap<>();
        for (int i = 3; i < segments.length; i++) {
            int eq = segments[i].indexOf('=');
            if (eq > 0) roles.put(segments[i].substring(0, eq), segments[i].substring(eq + 1));
        }
        // Cross-check restored role bindings against live roles.
        WorkflowTeamSnapshot snapshot = new WorkflowTeamSnapshot(team, roles, segments[2]);
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            if (roleManager.getRole(entry.getValue()) == null) {
                throw new java.io.IOException("Workflow '" + team.name() + "' role '" + entry.getValue()
                        + "' no longer exists; cannot restore the session workflow snapshot.");
            }
        }
        return snapshot;
    }

    /** Team summary for the wizard, launch banner, and /workflow view. */
    public String summarize() {
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow: ").append(team.name()).append(" (v").append(team.version()).append(")\n");
        WorkflowTeam.Participant lead = team.participant(team.lead());
        sb.append("\nLead       ").append(lead.id()).append(" — ").append(lead.role()).append('\n');
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            if (participant.id().equals(team.lead())) continue;
            sb.append(String.format("%-10s %s — %s, capabilities: %s%n",
                    participant.id(), participant.role(), participant.executor(),
                    String.join(", ", participant.capabilities())));
        }
        if (!team.routing().isEmpty()) {
            sb.append("\nRouting:\n");
            team.routing().forEach((purpose, target) ->
                    sb.append("  ").append(purpose).append(" → ").append(target).append('\n'));
        }
        WorkflowTeam.Gates gates = team.gates();
        if (gates.hasImplementationGate() || gates.hasCompletionGate()) {
            sb.append("\nGates:\n");
            if (gates.hasImplementationGate()) {
                sb.append("  Implementation begins after: ").append(gates.implementationRequires()).append('\n');
            }
            if (gates.hasCompletionGate()) {
                sb.append("  Workflow completes after: ").append(gates.completionRequires()).append('\n');
            }
        }
        sb.append("\nParallel workers: ").append(team.maxConcurrentWorkers()).append('\n');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorkflowTeamSnapshot other
                && team.equals(other.team)
                && resolvedRoles.equals(other.resolvedRoles)
                && launchedAt.equals(other.launchedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(team, resolvedRoles, launchedAt);
    }
}
