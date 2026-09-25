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
import ai.kompile.cli.main.chat.roles.RoleManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Harness-owned workflow state for the CURRENT process (the chat lead).
 *
 * <p>Why this exists: {@link WorkflowTeamEnforcement}'s child channel is the
 * process environment, but a JVM cannot mutate its own environment. The parent
 * chat therefore keeps its team here — explicit, set once at launch or restore
 * — instead of relying on {@code System.getenv}, which is always workflow-empty
 * for a top-level chat. Child processes (the {@code kompile mcp-stdio} server,
 * delegated agents) still receive {@code KOMPILE_WORKFLOW_NAME} /
 * {@code KOMPILE_WORKFLOW_PARTICIPANT} through the spawn channels that really
 * do carry environments; this class is also what those children read at their
 * own resolution points, so env values keep working across the boundary.</p>
 *
 * <p>Persistence: {@link #persist(String, Path)} writes the team snapshot next
 * to the transcript so {@code kompile chat --resume} restores the SAME team —
 * including the delegation edges and gates the session started with — without
 * re-asking the launch question. A resume never prompts; a changed team
 * aborts the restore (see {@link WorkflowTeamSnapshot#deserialize}).</p>
 */
public final class WorkflowSessionContext {

    private static volatile WorkflowSessionContext active;

    private final WorkflowTeamEnforcement enforcement;

    private WorkflowSessionContext(WorkflowTeamEnforcement enforcement) {
        this.enforcement = enforcement;
    }

    /** Installs this process's workflow identity; per-process, replaces any prior value. */
    public static void activate(WorkflowTeamSnapshot snapshot) {
        if (snapshot == null) {
            active = null;
            return;
        }
        active = new WorkflowSessionContext(WorkflowTeamEnforcement.forCaller(
                snapshot, WorkflowTeamEnforcement.resolveCallerParticipant(snapshot.team())));
    }

    /** The active context, or {@code null} when this session has no workflow team. */
    public static WorkflowSessionContext current() {
        return active;
    }

    public WorkflowTeamEnforcement enforcement() {
        return enforcement;
    }

    public WorkflowTeamSnapshot snapshot() {
        return enforcement.snapshot();
    }

    /** Team name for banner/status display, or {@code null} without a workflow. */
    public String workflowName() {
        return active == null ? null : snapshot().workflowName();
    }

    /** True when this process holds a workflow identity. */
    public static boolean isActive() {
        return active != null;
    }

    /** Environment values the harness exports to spawned children (MCP server, agents). */
    public java.util.Map<String, String> childEnvironment() {
        return active == null
                ? java.util.Map.of()
                : enforcement.childEnvironment(enforcement.callerParticipant());
    }

    /**
     * The workflow environment this process passes to every child it spawns:
     * the in-process context when set (the chat lead), else this process's own
     * inherited variables (a delegated agent that is itself a workflow
     * participant spawning further children). Empty when no workflow applies.
     */
    public static java.util.Map<String, String> inheritableEnvironment() {
        WorkflowSessionContext context = active;
        if (context != null) {
            return context.childEnvironment();
        }
        String name = System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME);
        if (name == null || name.isBlank()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, name);
        String participant = System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT);
        env.put(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT,
                participant == null ? "" : participant);
        return env;
    }

    /**
     * Workflow identity for the chat system prompt: names the team, this
     * process's participant, every member with capabilities, and the routing
     * purposes. Static for the whole session, so it stays inside the
     * cache-stable system prefix.
     */
    public String systemPromptSection() {
        WorkflowTeam team = snapshot().team();
        String caller = enforcement().callerParticipant();
        StringBuilder sb = new StringBuilder();
        sb.append("Active workflow team: ").append(team.name())
                .append(" (v").append(team.version()).append("). You are participant '")
                .append(caller).append("' (role: ")
                .append(team.participant(caller).role()).append(").\n");
        sb.append("Team members: ");
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            sb.append(participant.id()).append(" (").append(participant.role());
            if (!participant.capabilities().isEmpty()) {
                sb.append("; ").append(String.join(", ", participant.capabilities()));
            }
            sb.append(") ");
        }
        if (!team.routing().isEmpty()) {
            sb.append("\nDelegation purposes: ");
            team.routing().forEach((purpose, target) ->
                    sb.append(purpose).append(" -> ").append(target).append("  "));
        }
        sb.append("\nDelegate with task/multi_task by purpose; the harness validates routing, ")
                .append("delegation edges, and gates before any launch. Run /workflow for your ")
                .append("outstanding obligations.");
        return sb.toString();
    }

    // ── Per-transcript persistence (resume restores the same team) ─────────

    /** Session sidecar path: {@code <conversations>/<sessionId>.workflow}. */
    public static Path sessionPath(String sessionId) {
        return KompileHome.homeDirectory().toPath().resolve("conversations")
                .resolve(sessionId + ".workflow");
    }

    /** Writes the snapshot so a later resume can restore it. Best effort: failures are reported, not thrown. */
    public static void persist(String sessionId, WorkflowTeamSnapshot snapshot) {
        if (sessionId == null || sessionId.isBlank() || snapshot == null) return;
        try {
            Path path = sessionPath(sessionId);
            Files.createDirectories(path.getParent());
            Files.writeString(path, snapshot.serialize() + "\n", StandardCharsets.UTF_8);
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
     * {@link java.io.UncheckedIOException} — resuming must never silently drop
     * the enforcement the session started under.
     */
    public static WorkflowTeamSnapshot restore(String sessionId, Path projectRoot)
            throws IOException {
        if (sessionId == null || sessionId.isBlank()) return null;
        Path path = sessionPath(sessionId);
        if (!Files.isRegularFile(path)) return null;
        String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (encoded.isBlank()) return null;
        WorkflowTeamSnapshot snapshot = WorkflowTeamSnapshot.deserialize(
                encoded, new RoleManager(projectRoot));
        return snapshot;
    }
}
