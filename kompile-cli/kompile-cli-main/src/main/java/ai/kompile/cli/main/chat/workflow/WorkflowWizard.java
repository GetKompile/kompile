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

import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.roles.RoleManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interactive wizard for creating workflow team definitions — no hand-editing
 * of {@code chat-workflows.json} required. Follows the established wizard
 * contract: strict yes/no with explicit cancellation, numbered selection, and
 * EOF/cancel aborts without writing anything.
 *
 * <p>Creation flow: workflow name → participants (id, role, capabilities)
 * with inline role creation → lead → delegation edges → routing purposes →
 * concurrency limit → gates → participant models → save. Referenced roles
 * that do not exist can be created on the spot, because a workflow whose
 * roles are missing can never activate (enforcement fails closed).</p>
 *
 * <p>Participant models default to what the project already has configured —
 * the chat's own model and its saved chat profiles (see
 * {@link WorkflowModelDefaults}) — and every choice can be changed before the
 * team is saved or launched, including to any model in the provider's live
 * catalog.</p>
 *
 * <p>Every prompt and message goes through a {@link Console}, so the same
 * wizard runs on a plain terminal and inside the managed CLI-agent REPL,
 * whose fixed input box a raw {@code System.out} write would corrupt.</p>
 */
public final class WorkflowWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";

    /** Ordered capability menu: index → capability id. */
    private static final List<String> CAPABILITY_MENU = List.of(
            "read", "plan", "delegate", "edit-assigned-files", "validate", "chat-only");

    private WorkflowWizard() {}

    // ── Console: where the wizard prints and reads ──────────────────────────

    /**
     * Where the wizard prints and reads. A line reader backs it on a plain
     * terminal; the managed CLI-agent REPL supplies one that keeps its screen
     * intact.
     */
    public interface Console {

        /** Prints one line. */
        void println(String line);

        /** Reads the answer to {@code prompt}; null when the user cancels or input ends. */
        String readLine(String prompt);

        default void println() {
            println("");
        }

        /**
         * The line reader behind this console, for pickers that drive one
         * directly (the provider's live model catalog); null when there is none.
         */
        default LineReader reader() {
            return null;
        }

        /** A console on {@code reader}, printing to standard out. */
        static Console of(LineReader reader) {
            return new Console() {
                @Override
                public void println(String line) {
                    System.out.println(line);
                }

                @Override
                public String readLine(String prompt) {
                    try {
                        return reader.readLine(prompt);
                    } catch (UserInterruptException | EndOfFileException e) {
                        return null;
                    }
                }

                @Override
                public LineReader reader() {
                    return reader;
                }
            };
        }
    }

    /**
     * A console on the system terminal, opened on the first read so a command
     * that only prints never takes the terminal over. Close it when done.
     */
    public static final class TerminalConsole implements Console, AutoCloseable {
        private Terminal terminal;
        private LineReader lineReader;
        private boolean failed;

        @Override
        public void println(String line) {
            System.out.println(line);
        }

        @Override
        public String readLine(String prompt) {
            LineReader reader = reader();
            if (reader == null) return null;
            try {
                return reader.readLine(prompt);
            } catch (UserInterruptException | EndOfFileException e) {
                return null;
            }
        }

        @Override
        public LineReader reader() {
            if (lineReader == null && !failed) {
                try {
                    terminal = TerminalBuilder.builder().system(true).build();
                    lineReader = LineReaderBuilder.builder().terminal(terminal).build();
                } catch (IOException e) {
                    failed = true;
                    System.err.println("Could not open the terminal: " + e.getMessage());
                }
            }
            return lineReader;
        }

        @Override
        public void close() {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (IOException ignored) {
                    // Best effort: the terminal is released with the process anyway.
                }
            }
            terminal = null;
            lineReader = null;
        }
    }

    // ── Templates: zero-config starting points ──────────────────────────────

    /** A template's role: created on demand if the project does not define it yet. */
    public record RoleDefinition(String name, String description, String systemPrompt) {}

    /** The Designer + workers template: design approval gates implementation. */
    public static WorkflowTeam designerWorkersTeam() {
        Map<String, String> routing = new LinkedHashMap<>();
        routing.put("implement", "worker");
        return new WorkflowTeam("designer-workers", 1, "designer",
                participants(
                        new WorkflowTeam.Participant("designer", "architect", "cli",
                                List.of("read", "plan", "delegate"), List.of("worker")),
                        new WorkflowTeam.Participant("worker", "implementer", "cli",
                                List.of("read", "edit-assigned-files", "validate"), List.of())),
                routing,
                new WorkflowTeam.Limits(3),
                new WorkflowTeam.Gates("user-approved-design", "all-tasks-accepted"));
    }

    /** The Designer + workers + reviewer template: review additionally gates completion. */
    public static WorkflowTeam designerWorkersReviewerTeam() {
        Map<String, String> routing = new LinkedHashMap<>();
        routing.put("implement", "worker");
        routing.put("review", "reviewer");
        return new WorkflowTeam("designer-workers-reviewer", 1, "designer",
                participants(
                        new WorkflowTeam.Participant("designer", "architect", "cli",
                                List.of("read", "plan", "delegate"), List.of("worker", "reviewer")),
                        new WorkflowTeam.Participant("worker", "implementer", "cli",
                                List.of("read", "edit-assigned-files", "validate"), List.of()),
                        new WorkflowTeam.Participant("reviewer", "reviewer", "cli",
                                List.of("read", "plan", "validate"), List.of())),
                routing,
                new WorkflowTeam.Limits(3),
                new WorkflowTeam.Gates("user-approved-design", "review-accepted"));
    }

    /** Participants in declaration order, which is the order every view presents them in. */
    private static Map<String, WorkflowTeam.Participant> participants(WorkflowTeam.Participant... members) {
        Map<String, WorkflowTeam.Participant> result = new LinkedHashMap<>();
        for (WorkflowTeam.Participant member : members) {
            result.put(member.id(), member);
        }
        return result;
    }

    public static List<RoleDefinition> designerWorkerRoles() {
        return List.of(
                new RoleDefinition("architect",
                        "Designs solutions and delegates implementation",
                        "You are the designer. Produce a concrete plan with file scope before any "
                                + "implementation. You do not edit source files; you delegate "
                                + "implementation by purpose and synthesize accepted results."),
                new RoleDefinition("implementer",
                        "Implements the approved design",
                        "You are the implementer. Work only within the scope assigned to your task, "
                                + "make the edits, and validate the result. You do not delegate."));
    }

    public static List<RoleDefinition> designerWorkerReviewerRoles() {
        List<RoleDefinition> roles = new ArrayList<>(designerWorkerRoles());
        roles.add(new RoleDefinition("reviewer",
                "Reviews the resulting changes against the approved design",
                "You are the reviewer. Read the resulting changes and evaluate them against the "
                        + "approved design. Report accept or the concrete gaps. You do not edit."));
        return roles;
    }

    /** {@link #createFromTemplate(Console, Path, WorkflowTeam, List, ChatConfig)} on {@code reader}. */
    public static WorkflowTeam createFromTemplate(LineReader reader, Path projectRoot, WorkflowTeam team,
                                                  List<RoleDefinition> roles, ChatConfig current) {
        return createFromTemplate(Console.of(reader), projectRoot, team, roles, current);
    }

    /**
     * Materializes a template for the chat {@code current} configures (null
     * when unknown): confirms replacing a same-name team, assigns the
     * participants' models, creates any missing roles, then saves. Returns the
     * saved team, or null on cancel/failure; declining any step writes nothing.
     */
    public static WorkflowTeam createFromTemplate(Console console, Path projectRoot, WorkflowTeam team,
                                                  List<RoleDefinition> roles, ChatConfig current) {
        boolean replaced;
        try {
            replaced = WorkflowTeamStore.get(projectRoot, team.name()) != null;
        } catch (IOException e) {
            console.println("  " + RED + "Could not read project workflows: " + e.getMessage() + RESET);
            return null;
        }
        if (replaced) {
            Boolean replace = yesNo(console, "Replace existing workflow '" + team.name() + "'? [y/N]");
            if (replace == null || !replace) return null;
        }
        WorkflowTeam assigned = assignModels(console, projectRoot, team, current);
        if (assigned == null) return null;
        RoleManager roleManager = new RoleManager(projectRoot);
        for (RoleDefinition definition : roles) {
            if (roleManager.getRole(definition.name()) != null) continue;
            try {
                roleManager.createRole(definition.name(), definition.name(),
                        definition.description(), "workflow", definition.systemPrompt());
                console.println("  " + GREEN + "Role '" + definition.name() + "' created." + RESET);
            } catch (IOException e) {
                console.println("  " + RED + "Could not create role '" + definition.name() + "': "
                        + e.getMessage() + RESET);
                return null;
            }
        }
        return save(console, projectRoot, assigned, replaced);
    }

    /**
     * Opens a system terminal and runs creation for the chat {@code current}
     * configures. Returns the saved team, or null on cancel.
     */
    public static WorkflowTeam create(Path projectRoot, ChatConfig current) {
        try (TerminalConsole console = new TerminalConsole()) {
            return create(console, projectRoot, current);
        }
    }

    /** {@link #create(Console, Path, ChatConfig)} on {@code reader}. */
    public static WorkflowTeam create(LineReader reader, Path projectRoot, ChatConfig current) {
        return create(Console.of(reader), projectRoot, current);
    }

    /**
     * Runs creation on {@code console} for the chat {@code current} configures
     * (null when unknown). Returns the saved team, or null when the user
     * cancels or aborts; the team is written only after the final
     * confirmation.
     */
    public static WorkflowTeam create(Console console, Path projectRoot, ChatConfig current) {
        console.println();
        console.println(BOLD + CYAN + "  ╭──────────────────────────────────────────╮" + RESET);
        console.println(BOLD + CYAN + "  │       Create Workflow Team               │" + RESET);
        console.println(BOLD + CYAN + "  ╰──────────────────────────────────────────╯" + RESET);
        console.println();
        console.println("  A workflow splits work across participants: each has a role,");
        console.println("  capabilities, the participants it may delegate to, and the model");
        console.println("  it runs on. The lead is this chat. The harness enforces the result.");

        String name;
        boolean replaced;
        while (true) {
            name = prompt(console, "\n  Workflow name (blank cancels): ");
            if (name == null || name.isBlank()) return null;
            name = name.trim();
            try {
                replaced = WorkflowTeamStore.get(projectRoot, name) != null;
            } catch (IOException e) {
                console.println("  " + RED + "Could not read project workflows: " + e.getMessage() + RESET);
                return null;
            }
            if (!replaced) break;
            Boolean replace = yesNo(console, "Replace existing workflow '" + name + "'? [y/N]");
            if (replace == null) return null;
            if (replace) break;
        }

        RoleManager roleManager = new RoleManager(projectRoot);
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        while (true) {
            WorkflowTeam.Participant participant = createParticipant(console, roleManager,
                    participants.keySet());
            if (participant == null) return null;
            participants.put(participant.id(), participant);
            Boolean more = yesNo(console, "Add another participant? [y/N]");
            if (more == null) return null;
            if (!more) break;
        }

        List<String> ids = new ArrayList<>(participants.keySet());
        String lead = ids.get(0);
        if (ids.size() > 1) {
            int leadIdx = selectNumbered(console, "Which participant leads? (the lead is this chat)", ids);
            if (leadIdx < 0) return null;
            lead = ids.get(leadIdx);
        }

        Map<String, List<String>> edges = selectDelegationEdges(console, participants, lead);
        if (edges == null) return null;
        edges.forEach((id, targets) -> participants.put(id, participants.get(id).withDelegatesTo(targets)));

        Map<String, String> routing = selectRouting(console, participants, lead);
        if (routing == null) return null;

        String workersRaw = prompt(console, "\n  Max concurrent workers [1]: ");
        if (workersRaw == null) return null;
        int maxWorkers = 1;
        if (!workersRaw.isBlank()) {
            try {
                maxWorkers = Math.max(1, Integer.parseInt(workersRaw.trim()));
            } catch (NumberFormatException e) {
                console.println("  " + RED + "Not a number; using 1." + RESET);
            }
        }

        console.println();
        console.println("  " + DIM + "A gate is an approval you give with /workflow approve <gate>." + RESET);
        String implementationGate = prompt(console,
                "  Gate before implementation, e.g. 'user-approved-design' (blank for none): ");
        if (implementationGate == null) return null;
        String completionGate = prompt(console,
                "  Gate before completion, e.g. 'review-accepted' (blank for none): ");
        if (completionGate == null) return null;

        WorkflowTeam team;
        try {
            team = new WorkflowTeam(name, 1, lead, participants, routing,
                    new WorkflowTeam.Limits(maxWorkers),
                    new WorkflowTeam.Gates(implementationGate, completionGate));
        } catch (IllegalArgumentException e) {
            console.println("  " + RED + "Workflow is invalid: " + e.getMessage() + RESET);
            return null;
        }

        team = assignModels(console, projectRoot, team, current);
        if (team == null) return null;

        console.println();
        console.println(teamPreview(team, current));
        Boolean save = yesNo(console, "Save this workflow? [Y/n]");
        if (save == null || !save) {
            console.println("  " + DIM + "Discarded." + RESET);
            return null;
        }
        return save(console, projectRoot, team, replaced);
    }

    /** Saves the team and returns it as stored (the store versions changed definitions). */
    private static WorkflowTeam save(Console console, Path projectRoot, WorkflowTeam team, boolean replace) {
        try {
            if (!WorkflowTeamStore.save(projectRoot, team, replace)) {
                console.println("  " + RED + "A workflow named '" + team.name()
                        + "' already exists; nothing was written." + RESET);
                return null;
            }
            console.println("  " + GREEN + "Saved workflow '" + team.name() + "' to "
                    + WorkflowTeamStore.path(projectRoot) + RESET);
            WorkflowTeam stored = WorkflowTeamStore.get(projectRoot, team.name());
            return stored != null ? stored : team;
        } catch (IOException e) {
            console.println("  " + RED + "Could not save workflow: " + e.getMessage() + RESET);
            return null;
        }
    }

    private static WorkflowTeam.Participant createParticipant(Console console,
                                                               RoleManager roleManager,
                                                               Set<String> existingIds) {
        console.println();
        console.println(BOLD + "  New participant" + RESET);
        String id;
        while (true) {
            id = prompt(console, "  Participant id (lowercase, e.g. 'designer'; blank cancels): ");
            if (id == null || id.isBlank()) return null;
            id = WorkflowTeam.key(id);
            if (!id.matches("[a-z0-9][a-z0-9_-]*")) {
                console.println("  " + RED + "Invalid id '" + id
                        + "'. Use lowercase letters, digits, '-' or '_'." + RESET);
            } else if (existingIds.contains(id)) {
                console.println("  " + RED + "Participant '" + id + "' already exists in this workflow." + RESET);
            } else {
                break;
            }
        }

        String role = chooseOrCreateRole(console, roleManager);
        if (role == null) return null;

        List<String> capabilities = selectCapabilities(console, id);
        if (capabilities == null) return null;

        // Delegation edges are chosen once every participant exists.
        return new WorkflowTeam.Participant(id, role, "cli", capabilities, List.of());
    }

    /** Picks an existing role (numbered) or creates one inline. */
    static String chooseOrCreateRole(Console console, RoleManager roleManager) {
        List<String> roleNames = roleManager.getAllRoles().stream()
                .map(r -> r.getName()).sorted().toList();
        List<String> options = new ArrayList<>(roleNames);
        options.add("Create a new role");
        int idx = selectNumbered(console, "Select the role for this participant:", options);
        if (idx < 0) return null;
        if (idx == roleNames.size()) {
            return createRoleInline(console, roleManager);
        }
        return roleNames.get(idx);
    }

    /** Inline role creation: name, description, prompt. Returns the role name or null. */
    static String createRoleInline(Console console, RoleManager roleManager) {
        String roleName = prompt(console, "  New role name (e.g. 'designer-role'; blank cancels): ");
        if (roleName == null || roleName.isBlank()) return null;
        roleName = roleName.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        String description = prompt(console, "  Short description: ");
        if (description == null) return null;
        console.println("  " + DIM + "System prompt (one line; this is the participant's behavior contract)" + RESET);
        String systemPrompt = prompt(console, "  > ");
        if (systemPrompt == null) return null;
        try {
            roleManager.createRole(roleName,
                    roleName, description.isBlank() ? "Workflow role: " + roleName : description.trim(),
                    "workflow", systemPrompt.isBlank() ? "You fulfill the " + roleName + " responsibility." : systemPrompt.trim());
            console.println("  " + GREEN + "Role '" + roleName + "' created." + RESET);
            return roleName;
        } catch (IOException e) {
            console.println("  " + RED + "Could not create role: " + e.getMessage() + RESET);
            return null;
        }
    }

    /** Multi-select capability picker; 'chat-only' is exclusive. Null on cancel. */
    static List<String> selectCapabilities(Console console, String participantId) {
        Set<String> selected = new LinkedHashSet<>();
        console.println();
        console.println(BOLD + "  Capabilities for '" + participantId + "':" + RESET);
        while (true) {
            List<String> options = new ArrayList<>();
            for (int i = 0; i < CAPABILITY_MENU.size(); i++) {
                String capability = CAPABILITY_MENU.get(i);
                String marker = selected.contains(capability) ? " ✓" : "";
                options.add(capability + marker);
            }
            options.add("Done");
            int idx = selectNumbered(console, "Toggle or finish:", options);
            if (idx < 0) return null;
            if (idx == CAPABILITY_MENU.size()) {
                if (selected.isEmpty()) {
                    console.println("  " + RED + "Select at least one capability." + RESET);
                    continue;
                }
                return new ArrayList<>(selected);
            }
            String capability = CAPABILITY_MENU.get(idx);
            if (capability.equals("chat-only")) {
                // chat-only is exclusive: selecting it clears the others.
                if (selected.contains("chat-only")) {
                    selected.remove("chat-only");
                } else {
                    selected.clear();
                    selected.add("chat-only");
                }
            } else {
                selected.remove("chat-only");
                if (selected.contains(capability)) {
                    selected.remove(capability);
                } else {
                    selected.add(capability);
                }
            }
        }
    }

    /**
     * Delegation edges — the enforced allow-list of who may hand work to whom —
     * chosen once every participant exists. Each participant with the
     * {@code delegate} capability, lead first, starts allowed to delegate to
     * every other participant except the lead that does not already reach it;
     * a toggle that would form a cycle is refused. Returns the edges of every
     * participant, or null on cancel.
     */
    static Map<String, List<String>> selectDelegationEdges(Console console,
                                                           Map<String, WorkflowTeam.Participant> participants,
                                                           String lead) {
        List<String> order = new ArrayList<>();
        order.add(lead);
        participants.keySet().stream().filter(id -> !id.equals(lead)).forEach(order::add);
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        order.forEach(id -> edges.put(id, new LinkedHashSet<>()));
        if (order.size() > 1 && !participants.get(lead).canDelegate()) {
            console.println();
            console.println("  " + YELLOW + "The lead '" + lead + "' lacks the 'delegate' capability, so it"
                    + " cannot hand work to the other participants." + RESET);
        }
        for (String id : order) {
            List<String> others = order.stream().filter(other -> !other.equals(id)).toList();
            if (!participants.get(id).canDelegate() || others.isEmpty()) continue;
            Set<String> selected = edges.get(id);
            for (String other : others) {
                if (!other.equals(lead) && !reaches(edges, other, id)) selected.add(other);
            }
            console.println();
            console.println(BOLD + "  '" + id + "' may delegate to:" + RESET);
            while (true) {
                List<String> options = new ArrayList<>();
                others.forEach(other -> options.add(other + (selected.contains(other) ? " ✓" : "")));
                options.add("Done");
                int idx = selectNumbered(console, "Toggle or finish:", options);
                if (idx < 0) return null;
                if (idx == others.size()) break;
                String target = others.get(idx);
                if (selected.remove(target)) continue;
                if (reaches(edges, target, id)) {
                    console.println("  " + RED + "'" + target + "' already delegates to '" + id
                            + "' (directly or through others); delegation cannot loop." + RESET);
                    continue;
                }
                selected.add(target);
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        participants.keySet().forEach(id -> result.put(id, List.copyOf(edges.get(id))));
        return result;
    }

    /** Whether {@code from} reaches {@code target} over delegation edges. */
    private static boolean reaches(Map<String, Set<String>> edges, String from, String target) {
        for (String next : edges.getOrDefault(from, Set.of())) {
            if (next.equals(target) || reaches(edges, next, target)) return true;
        }
        return false;
    }

    /**
     * Routing purposes: names the lead delegates by ('implement', 'review'),
     * each routed to the participant that does that work. Null on cancel.
     */
    private static Map<String, String> selectRouting(Console console,
                                                     Map<String, WorkflowTeam.Participant> participants,
                                                     String lead) {
        Map<String, String> routing = new LinkedHashMap<>();
        List<String> targets = participants.keySet().stream().filter(id -> !id.equals(lead)).toList();
        if (targets.isEmpty()) return routing;
        console.println();
        console.println("  " + DIM + "Purposes let the lead delegate by intent (e.g. 'implement', 'review');"
                + " each routes to the participant that does that work." + RESET);
        while (true) {
            String purpose = prompt(console, "\n  Routing purpose (blank when done): ");
            if (purpose == null) return null;
            if (purpose.isBlank()) return routing;
            String key = WorkflowTeam.key(purpose);
            int targetIdx = 0;
            if (targets.size() > 1) {
                targetIdx = selectNumbered(console, "Route '" + key + "' to:", targets);
                if (targetIdx < 0) return null;
            }
            String target = targets.get(targetIdx);
            routing.put(key, target);
            console.println("  " + GREEN + key + " → " + target + RESET);
            boolean reachable = participants.values().stream()
                    .anyMatch(participant -> participant.canDelegate() && participant.delegatesTo().contains(target));
            if (!reachable) {
                console.println("  " + YELLOW + "No participant may delegate to '" + target + "', so '" + key
                        + "' is refused until a delegation edge allows it." + RESET);
            }
        }
    }

    // ── Participant models ──────────────────────────────────────────────────

    /**
     * Assigns models to the participants that have none — from the chat
     * {@code current} configures and the project's saved chat profiles — then
     * lets the user review and change them. Returns the team to save, or null
     * on cancel. Skipped when there is nothing to choose from.
     */
    static WorkflowTeam assignModels(Console console, Path projectRoot, WorkflowTeam team,
                                     ChatConfig current) {
        List<WorkflowModelDefaults.Candidate> candidates = candidates(console, projectRoot, current);
        if (!offersModels(console, current, candidates)) return team;
        WorkflowModelDefaults.Proposal proposal = WorkflowModelDefaults.assign(team, current, candidates);
        return modelMenu(console, proposal.team(), current, candidates, proposal.notes());
    }

    /** {@link #reviewModels(Console, Path, WorkflowTeam, ChatConfig)} on {@code reader}. */
    public static WorkflowTeam reviewModels(LineReader reader, Path projectRoot, WorkflowTeam team,
                                            ChatConfig current) {
        return reviewModels(Console.of(reader), projectRoot, team, current);
    }

    /**
     * Shows a saved team's participant models for the chat {@code current}
     * configures and lets the user change them before it launches. Changes are
     * saved; returns the team to launch, or null on cancel.
     */
    public static WorkflowTeam reviewModels(Console console, Path projectRoot, WorkflowTeam team,
                                            ChatConfig current) {
        List<WorkflowModelDefaults.Candidate> candidates = candidates(console, projectRoot, current);
        if (!offersModels(console, current, candidates)) return team;
        WorkflowTeam reviewed = modelMenu(console, team, current, candidates,
                WorkflowModelDefaults.notes(team, current));
        if (reviewed == null || reviewed.equals(team)) return reviewed;
        return save(console, projectRoot, reviewed, true);
    }

    /**
     * Changes one participant's model for the chat {@code current} configures
     * — the {@code /workflow model} command — and saves the change. Returns
     * the team as stored (unchanged when the same model was picked), or null
     * on cancel or failure.
     */
    public static WorkflowTeam changeModel(Console console, Path projectRoot, WorkflowTeam team,
                                           String participantId, ChatConfig current) {
        WorkflowTeam changed = changeModel(console, team, participantId, current,
                candidates(console, projectRoot, current));
        if (changed == null || changed.equals(team)) return changed;
        return save(console, projectRoot, changed, true);
    }

    /** Candidate models; a project whose profiles cannot be read still offers this chat's model. */
    static List<WorkflowModelDefaults.Candidate> candidates(Console console, Path projectRoot,
                                                            ChatConfig current) {
        try {
            return WorkflowModelDefaults.candidates(projectRoot, current);
        } catch (IOException e) {
            console.println("  " + YELLOW + "Could not read the project's chat profiles (" + e.getMessage()
                    + "); offering this chat's model only." + RESET);
            WorkflowTeam.ModelBinding own = WorkflowModelDefaults.bindingOf(current);
            return own == null ? List.of() : List.of(new WorkflowModelDefaults.Candidate(own, "this chat"));
        }
    }

    /** Whether there is a model to choose: a configured one, a live catalog, or a CLI agent. */
    private static boolean offersModels(Console console, ChatConfig current,
                                        List<WorkflowModelDefaults.Candidate> candidates) {
        return !candidates.isEmpty() || liveCatalog(console, current)
                || WorkflowModelDefaults.delegatesToCliAgents(current);
    }

    /**
     * Whether other models can be picked from the chat's live catalog: its own
     * route has one, and the console has a line reader to drive the picker.
     */
    private static boolean liveCatalog(Console console, ChatConfig current) {
        return current != null && !WorkflowModelDefaults.delegatesToCliAgents(current)
                && !current.isKompileServer() && current.getProvider() != null
                && !current.getProvider().isBlank() && console.reader() != null;
    }

    private static WorkflowTeam modelMenu(Console console, WorkflowTeam team, ChatConfig current,
                                          List<WorkflowModelDefaults.Candidate> candidates, List<String> notes) {
        boolean cliAgents = WorkflowModelDefaults.delegatesToCliAgents(current);
        WorkflowTeam result = team;
        List<String> shown = notes;
        while (true) {
            console.println();
            console.println(BOLD + "  Participant models" + RESET);
            modelRows(result, current, candidates).forEach(row -> console.println("    " + row));
            for (String note : shown) {
                console.println("  " + YELLOW + "- " + note + RESET);
            }
            List<String> options = new ArrayList<>();
            options.add("Use these models");
            options.add("Change a participant's model");
            int everyone = -1;
            if (!cliAgents || WorkflowModelDefaults.bindingOf(current) != null) {
                everyone = options.size();
                options.add(cliAgents ? "Run every participant on this chat's agent and model"
                        : "Run every participant on the chat's model");
            }
            int choice = selectNumbered(console, "Participant models:", options);
            if (choice < 0) return null;
            if (choice == 0) return result;
            if (choice == everyone) {
                result = WorkflowModelDefaults.onChatModel(result, current);
            } else {
                WorkflowTeam changed = changeModel(console, result, null, current, candidates);
                if (changed != null) result = changed;
            }
            shown = WorkflowModelDefaults.notes(result, current);
        }
    }

    /**
     * The team's participant models as display rows: each participant, the
     * model it runs on, and where that model is configured.
     */
    public static List<String> modelRows(WorkflowTeam team, ChatConfig current,
                                         List<WorkflowModelDefaults.Candidate> candidates) {
        int width = team.participants().keySet().stream().mapToInt(String::length).max().orElse(0)
                + " (lead)".length();
        List<String> rows = new ArrayList<>();
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            String name = participant.id() + (participant.id().equals(team.lead()) ? " (lead)" : "");
            String model = WorkflowModelDefaults.describe(team, participant, current);
            WorkflowTeam.ModelBinding binding = participant.model();
            // Where the model is configured, when the row shows the participant's own binding.
            if (binding != null && model.equals(binding.label())) {
                model += " [" + candidates.stream().filter(candidate -> candidate.binding().equals(binding))
                        .map(WorkflowModelDefaults.Candidate::source).findFirst().orElse("custom") + "]";
            }
            rows.add(String.format("%-" + width + "s  %s", name, model));
        }
        return rows;
    }

    /**
     * Rebinds one participant: to its default (unbound), a configured model, or
     * another model from the chat's live catalog or a CLI agent's. Asks which
     * participant when {@code participantId} is null. Null on cancel.
     */
    private static WorkflowTeam changeModel(Console console, WorkflowTeam team, String participantId,
                                            ChatConfig current, List<WorkflowModelDefaults.Candidate> candidates) {
        boolean cliAgents = WorkflowModelDefaults.delegatesToCliAgents(current);
        String id = participantId == null ? null : WorkflowTeam.key(participantId);
        if (id == null) {
            List<String> ids = team.participants().keySet().stream()
                    .filter(candidate -> !(cliAgents && candidate.equals(team.lead())))
                    .toList();
            if (ids.isEmpty()) {
                console.println("  " + DIM + "The lead runs as this chat's CLI agent; no other participant"
                        + " needs a model." + RESET);
                return null;
            }
            int idx = ids.size() == 1 ? 0 : selectNumbered(console, "Change which participant's model?", ids);
            if (idx < 0) return null;
            id = ids.get(idx);
        } else if (team.participant(id) == null) {
            console.println("  " + RED + "Workflow '" + team.name() + "' has no participant '" + id + "'." + RESET);
            return null;
        } else if (cliAgents && id.equals(team.lead())) {
            console.println("  " + DIM + "'" + id + "' leads as this chat's CLI agent; change the chat's"
                    + " model with /model." + RESET);
            return null;
        }
        boolean lead = id.equals(team.lead());

        List<String> options = new ArrayList<>();
        List<WorkflowTeam.ModelBinding> bindings = new ArrayList<>();
        if (cliAgents) {
            options.add("Unassigned: the lead chooses the CLI agent");
        } else if (lead) {
            WorkflowTeam.ModelBinding own = WorkflowModelDefaults.bindingOf(current);
            options.add("The chat's configured model" + (own == null ? "" : " (" + own.label() + ")"));
        } else {
            WorkflowTeam.ModelBinding base = WorkflowModelDefaults.baseModel(team, current);
            options.add("Same as the lead" + (base == null ? "" : " (" + base.label() + ")"));
        }
        bindings.add(null);
        for (WorkflowModelDefaults.Candidate candidate : candidates) {
            options.add(candidate.label());
            bindings.add(candidate.binding());
        }
        int catalog = -1;
        if (liveCatalog(console, current)) {
            catalog = options.size();
            options.add("Another " + SetupWizard.vendorLabel(SetupWizard.vendorForProvider(current.getProvider()))
                    + " model (live catalog)");
        }
        int agentModel = -1;
        if (cliAgents) {
            agentModel = options.size();
            options.add("Another CLI agent model");
        }
        int choice = selectNumbered(console, "Model for '" + id + "':", options);
        if (choice < 0) return null;

        WorkflowTeam.ModelBinding picked;
        if (choice == catalog) {
            SetupWizard.ModelPick pick = SetupWizard.pickModel(console.reader(), current);
            if (pick == null) return null;
            picked = new WorkflowTeam.ModelBinding(current.getProvider(), pick.model(), pick.thinking(),
                    current.getAuthenticationMethod(), current.getBaseUrl(), null);
        } else if (choice == agentModel) {
            picked = pickAgentModel(console, current);
            if (picked == null) return null;
        } else {
            picked = bindings.get(choice);
        }
        return WorkflowModelDefaults.withModel(team, id, picked, current);
    }

    /**
     * A CLI agent and the model it runs — from the agent's own catalog when it
     * has one, otherwise as typed — with the chat's own agent offered first.
     * Null on cancel, or when the agent cannot take the value on its command
     * line.
     */
    private static WorkflowTeam.ModelBinding pickAgentModel(Console console, ChatConfig current) {
        List<String> agents = new ArrayList<>();
        String own = current.getPassthroughAgent();
        if (own != null && AgentLaunchDefaults.SUPPORTED_AGENTS.contains(own)) agents.add(own);
        AgentLaunchDefaults.SUPPORTED_AGENTS.stream().filter(agent -> !agents.contains(agent)).forEach(agents::add);
        int idx = selectNumbered(console, "Which CLI agent runs it?", agents);
        if (idx < 0) return null;
        String agent = agents.get(idx);

        List<String> models = SetupWizard.agentModelIds(agent);
        String model;
        if (!models.isEmpty()) {
            int selected = selectNumbered(console, "Select " + agent + " Model:", models);
            if (selected < 0) return null;
            model = models.get(selected);
        } else {
            model = prompt(console, "  " + agent + " model id (blank cancels): ");
            if (model == null || model.isBlank()) return null;
            model = model.trim();
        }
        String thinking = null;
        if (SetupWizard.supportsPassthroughThinking(agent, true)) {
            String value = prompt(console, "  Thinking/effort value (blank keeps the agent's default): ");
            if (value == null) return null;
            thinking = value.isBlank() ? null : value.trim();
        }
        try {
            // Refuse a value the agent cannot take on its command line before it is saved.
            AgentLaunchDefaults.commandArguments(agent, model, thinking, AgentLaunchDefaults.LaunchMode.MANAGED);
            WorkflowTeam.ModelBinding binding = WorkflowModelDefaults.agentBinding(agent, model, thinking);
            if (binding == null) {
                console.println("  " + RED + "'" + agent + "' cannot run '" + model
                        + "' as a workflow participant." + RESET);
            }
            return binding;
        } catch (IllegalArgumentException e) {
            console.println("  " + RED + e.getMessage() + RESET);
            return null;
        }
    }

    /** Human-readable preview of the workflow before saving. */
    static String teamPreview(WorkflowTeam team, ChatConfig current) {
        WorkflowTeamSnapshot snapshot = new WorkflowTeamSnapshot(team,
                team.participants().keySet().stream()
                        .collect(Collectors.toMap(id -> id,
                                id -> team.participant(id).role(),
                                (a, b) -> a, LinkedHashMap::new)),
                null);
        return snapshot.summarize(current);
    }

    // ── Shared wizard primitives (same contract as SetupWizard) ────────────

    static String prompt(Console console, String question) {
        return console.readLine(question);
    }

    /** Strict yes/no; null on cancel/EOF. Blank follows the bracket default ([Y/n] = yes, [y/N] = no). */
    static Boolean yesNo(Console console, String question) {
        while (true) {
            String input = prompt(console, "  " + question + ": ");
            if (input == null) return null;
            switch (input.trim().toLowerCase(Locale.ROOT)) {
                case "y", "yes" -> { return true; }
                case "n", "no" -> { return false; }
                case "" -> {
                    // Blank takes the bracketed default: [Y/n] means yes, [y/N] means no.
                    return question.contains("[Y/n]");
                }
                case "q", "quit", "cancel" -> { return null; }
                default -> console.println("  Please enter yes or no.");
            }
        }
    }

    /** Numbered menu identical in spirit to SetupWizard.selectNumbered; -1 cancels. */
    static int selectNumbered(Console console, String title, List<String> items) {
        console.println(BOLD + "  " + title + RESET);
        console.println();
        for (int i = 0; i < items.size(); i++) {
            console.println(String.format("  " + CYAN + "%2d" + RESET + "  %s", i + 1, items.get(i)));
        }
        console.println();
        while (true) {
            String input = prompt(console, "  Select 1-" + items.size() + " (blank/`q` cancels): ");
            if (input == null || input.isBlank() || input.trim().equalsIgnoreCase("q")) return -1;
            try {
                int choice = Integer.parseInt(input.trim());
                if (choice >= 1 && choice <= items.size()) return choice - 1;
            } catch (NumberFormatException ignored) {
                // fall through
            }
            console.println("  Please enter a number between 1 and " + items.size() + ".");
        }
    }
}
