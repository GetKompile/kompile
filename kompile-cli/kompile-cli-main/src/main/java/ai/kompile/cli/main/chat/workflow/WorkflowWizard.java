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

import ai.kompile.cli.main.chat.roles.RoleManager;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interactive wizard for creating workflow team definitions — no hand-editing
 * of {@code chat-workflows.json} required. Follows the established wizard
 * contract: strict yes/no with explicit cancellation, numbered selection, and
 * EOF/cancel aborts without writing anything.
 *
 * <p>Creation flow: workflow name → participants (id, role, capabilities,
 * delegation targets) with inline role creation → lead → routing purposes →
 * concurrency limit → gates → save. Referenced roles that do not exist can be
 * created on the spot, because a workflow whose roles are missing can never
 * activate (enforcement fails closed).</p>
 */
public final class WorkflowWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";

    /** Ordered capability menu: index → capability id. */
    private static final List<String> CAPABILITY_MENU = List.of(
            "read", "plan", "delegate", "edit-assigned-files", "validate", "chat-only");

    private WorkflowWizard() {}

    // ── Templates: zero-config starting points ──────────────────────────────

    /** A template's role: created on demand if the project does not define it yet. */
    public record RoleDefinition(String name, String description, String systemPrompt) {}

    /** The Designer + workers template: design approval gates implementation. */
    public static WorkflowTeam designerWorkersTeam() {
        return new WorkflowTeam("designer-workers", 1, "designer",
                java.util.Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                                List.of("read", "plan", "delegate"), List.of("worker")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                                List.of("read", "edit-assigned-files", "validate"), List.of())),
                java.util.Map.of("implement", "worker"),
                new WorkflowTeam.Limits(3),
                new WorkflowTeam.Gates("user-approved-design", "all-tasks-accepted"));
    }

    /** The Designer + workers + reviewer template: review additionally gates completion. */
    public static WorkflowTeam designerWorkersReviewerTeam() {
        return new WorkflowTeam("designer-workers-reviewer", 1, "designer",
                java.util.Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                                List.of("read", "plan", "delegate"), List.of("worker", "reviewer")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                                List.of("read", "edit-assigned-files", "validate"), List.of()),
                        "reviewer", new WorkflowTeam.Participant("reviewer", "reviewer", "cli",
                                List.of("read", "plan", "validate"), List.of())),
                java.util.Map.of("implement", "worker", "review", "reviewer"),
                new WorkflowTeam.Limits(3),
                new WorkflowTeam.Gates("user-approved-design", "review-accepted"));
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

    /**
     * Materializes a template: creates any missing roles, then saves the team
     * (replacement of an existing same-name team requires explicit confirmation).
     * Returns the saved team, or null on cancel/failure.
     */
    public static WorkflowTeam createFromTemplate(LineReader reader, Path projectRoot,
                                                  WorkflowTeam team, List<RoleDefinition> roles) {
        RoleManager roleManager = new RoleManager(projectRoot);
        for (RoleDefinition definition : roles) {
            if (roleManager.getRole(definition.name()) != null) continue;
            try {
                roleManager.createRole(definition.name(), definition.name(),
                        definition.description(), "workflow", definition.systemPrompt());
                System.out.println("  " + GREEN + "Role '" + definition.name() + "' created." + RESET);
            } catch (IOException e) {
                System.err.println("Could not create role '" + definition.name() + "': " + e.getMessage());
                return null;
            }
        }
        try {
            boolean replaced = WorkflowTeamStore.get(projectRoot, team.name()) != null;
            if (replaced) {
                Boolean replace = yesNo(reader, "Replace existing workflow '" + team.name() + "'? [y/N]");
                if (replace == null || !replace) return null;
            }
            if (!WorkflowTeamStore.save(projectRoot, team, replaced)) {
                System.out.println("  " + RED + "A workflow named '" + team.name()
                        + "' already exists; nothing was written." + RESET);
                return null;
            }
            System.out.println("  " + GREEN + "Saved workflow '" + team.name() + "' to "
                    + WorkflowTeamStore.path(projectRoot) + RESET);
            return team;
        } catch (IOException e) {
            System.err.println("Could not save workflow: " + e.getMessage());
            return null;
        }
    }

    /** Opens a system terminal and runs creation. Returns the saved team, or null on cancel. */
    public static WorkflowTeam create(Path projectRoot) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            return create(LineReaderBuilder.builder().terminal(terminal).build(), projectRoot);
        } catch (IOException e) {
            System.err.println("Could not open workflow wizard: " + e.getMessage());
            return null;
        }
    }

    /**
     * Runs creation against the given reader (testable). Returns the saved team,
     * or null when the user cancels or aborts.
     */
    public static WorkflowTeam create(LineReader reader, Path projectRoot) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────────╮" + RESET);
        System.out.println(BOLD + CYAN + "  │       Create Workflow Team               │" + RESET);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────────╯" + RESET);
        System.out.println();
        System.out.println("  A workflow assigns roles, capabilities, and delegation");
        System.out.println("  edges to participants. The harness enforces the result.");

        String name = prompt(reader, "\n  Workflow name (blank cancels): ");
        if (name == null || name.isBlank()) return null;
        name = name.trim();

        RoleManager roleManager = new RoleManager(projectRoot);
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();

        while (true) {
            WorkflowTeam.Participant participant = createParticipant(reader, roleManager,
                    participants.keySet());
            if (participant == null) return null;
            participants.put(participant.id(), participant);
            Boolean more = yesNo(reader, "Add another participant? [y/N]");
            if (more == null) return null;
            if (!more) break;
        }

        // Lead selection.
        List<String> ids = new ArrayList<>(participants.keySet());
        int leadIdx = selectNumbered(reader, "Select the lead participant:", ids);
        if (leadIdx < 0) return null;
        String lead = ids.get(leadIdx);

        // Routing purposes (optional but strongly recommended).
        Map<String, String> routing = new LinkedHashMap<>();
        while (true) {
            String purpose = prompt(reader,
                    "\n  Routing purpose (e.g. 'implement'; blank when done): ");
            if (purpose == null) return null;
            if (purpose.isBlank()) break;
            List<String> targets = new ArrayList<>(participants.keySet());
            int targetIdx = selectNumbered(reader, "Route '" + purpose.trim() + "' to:", targets);
            if (targetIdx < 0) return null;
            routing.put(WorkflowTeam.key(purpose), targets.get(targetIdx));
        }

        String workersRaw = prompt(reader, "\n  Max concurrent workers [1]: ");
        if (workersRaw == null) return null;
        int maxWorkers = 1;
        if (!workersRaw.isBlank()) {
            try {
                maxWorkers = Math.max(1, Integer.parseInt(workersRaw.trim()));
            } catch (NumberFormatException e) {
                System.out.println("  " + RED + "Not a number; using 1." + RESET);
            }
        }

        String implementationGate = prompt(reader,
                "  Gate before implementation (blank for none): ");
        if (implementationGate == null) return null;
        String completionGate = prompt(reader, "  Gate before completion (blank for none): ");
        if (completionGate == null) return null;

        WorkflowTeam team;
        try {
            team = new WorkflowTeam(name, 1, lead, participants, routing,
                    new WorkflowTeam.Limits(maxWorkers),
                    new WorkflowTeam.Gates(implementationGate, completionGate));
        } catch (IllegalArgumentException e) {
            System.out.println("  " + RED + "Workflow is invalid: " + e.getMessage() + RESET);
            return null;
        }

        // Show and confirm.
        System.out.println();
        System.out.println(teamPreview(team));
        Boolean save = yesNo(reader, "Save this workflow? [Y/n]");
        if (save == null || !save) {
            System.out.println("  " + DIM + "Discarded." + RESET);
            return null;
        }
        try {
            boolean replaced = WorkflowTeamStore.get(projectRoot, name) != null;
            if (replaced) {
                Boolean replace = yesNo(reader, "Replace existing '" + name + "'? [y/N]");
                if (replace == null || !replace) return null;
            }
            if (!WorkflowTeamStore.save(projectRoot, team, replaced)) {
                System.out.println("  " + RED + "A workflow named '" + name
                        + "' already exists; nothing was written." + RESET);
                return null;
            }
            System.out.println("  " + GREEN + "Saved workflow '" + team.name() + "' to "
                    + WorkflowTeamStore.path(projectRoot) + RESET);
            return team;
        } catch (IOException e) {
            System.err.println("Could not save workflow: " + e.getMessage());
            return null;
        }
    }

    private static WorkflowTeam.Participant createParticipant(LineReader reader,
                                                               RoleManager roleManager,
                                                               Set<String> existingIds) {
        System.out.println();
        System.out.println(BOLD + "  New participant" + RESET);
        String id = prompt(reader, "  Participant id (lowercase, e.g. 'designer'; blank cancels): ");
        if (id == null || id.isBlank()) return null;
        id = WorkflowTeam.key(id);
        if (!id.matches("[a-z0-9][a-z0-9_-]*")) {
            System.out.println("  " + RED + "Invalid id '" + id + "'. Use lowercase letters, digits, '-' or '_'." + RESET);
            return null;
        }
        if (existingIds.contains(id)) {
            System.out.println("  " + RED + "Participant '" + id + "' already exists in this workflow." + RESET);
            return null;
        }

        String role = chooseOrCreateRole(reader, roleManager);
        if (role == null) return null;

        List<String> capabilities = selectCapabilities(reader, id);
        if (capabilities == null) return null;

        // Delegation targets: other participants are chosen after all participants exist,
        // so collect the choice at the end of creation; represent unresolvable edges as none
        // and let the routing loop + a dedicated pass after creation fix edges.
        List<String> delegatesTo = selectDelegationTargets(reader, id, existingIds);

        try {
            return new WorkflowTeam.Participant(id, role, "cli", capabilities, delegatesTo);
        } catch (IllegalArgumentException e) {
            System.out.println("  " + RED + e.getMessage() + RESET);
            return null;
        }
    }

    /** Picks an existing role (numbered) or creates one inline. */
    static String chooseOrCreateRole(LineReader reader, RoleManager roleManager) {
        List<String> roleNames = roleManager.getAllRoles().stream()
                .map(r -> r.getName()).sorted().toList();
        List<String> options = new ArrayList<>(roleNames);
        options.add("➕ Create a new role");
        int idx = selectNumbered(reader, "Select the role for this participant:", options);
        if (idx < 0) return null;
        if (idx == roleNames.size()) {
            return createRoleInline(reader, roleManager);
        }
        return roleNames.get(idx);
    }

    /** Inline role creation: name, description, prompt. Returns the role name or null. */
    static String createRoleInline(LineReader reader, RoleManager roleManager) {
        String roleName = prompt(reader, "  New role name (e.g. 'designer-role'; blank cancels): ");
        if (roleName == null || roleName.isBlank()) return null;
        roleName = roleName.trim().toLowerCase().replaceAll("[\\s_]+", "-");
        String description = prompt(reader, "  Short description: ");
        if (description == null) return null;
        System.out.println("  " + DIM + "System prompt (one line; this is the participant's behavior contract)" + RESET);
        String systemPrompt = prompt(reader, "  > ");
        if (systemPrompt == null) return null;
        try {
            roleManager.createRole(roleName,
                    roleName, description.isBlank() ? "Workflow role: " + roleName : description.trim(),
                    "workflow", systemPrompt.isBlank() ? "You fulfill the " + roleName + " responsibility." : systemPrompt.trim());
            System.out.println("  " + GREEN + "Role '" + roleName + "' created." + RESET);
            return roleName;
        } catch (IOException e) {
            System.err.println("Could not create role: " + e.getMessage());
            return null;
        }
    }

    /** Multi-select capability picker; 'chat-only' is exclusive. Null on cancel. */
    static List<String> selectCapabilities(LineReader reader, String participantId) {
        Set<String> selected = new LinkedHashSet<>();
        System.out.println();
        System.out.println(BOLD + "  Capabilities for '" + participantId + "':" + RESET);
        while (true) {
            List<String> options = new ArrayList<>();
            for (int i = 0; i < CAPABILITY_MENU.size(); i++) {
                String capability = CAPABILITY_MENU.get(i);
                String marker = selected.contains(capability) ? " ✓" : "";
                options.add(capability + marker);
            }
            options.add("Done");
            int idx = selectNumbered(reader, "Toggle or finish:", options);
            if (idx < 0) return null;
            if (idx == CAPABILITY_MENU.size()) {
                if (selected.isEmpty()) {
                    System.out.println("  " + RED + "Select at least one capability." + RESET);
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

    /** Multi-select delegation targets from the participants defined so far. Null on cancel. */
    static List<String> selectDelegationTargets(LineReader reader, String participantId,
                                                Set<String> existingIds) {
        if (existingIds.isEmpty()) return List.of();
        Set<String> selected = new LinkedHashSet<>();
        System.out.println();
        System.out.println(BOLD + "  Delegation targets for '" + participantId + "':" + RESET);
        System.out.println("  " + DIM + "(Later participants can still be routed to via purposes; edges here are the enforced allow-list.)" + RESET);
        while (true) {
            List<String> options = new ArrayList<>();
            for (String id : existingIds) {
                options.add(id + (selected.contains(id) ? " ✓" : ""));
            }
            options.add("Done");
            int idx = selectNumbered(reader, "Toggle or finish:", options);
            if (idx < 0) return null;
            if (idx == existingIds.size()) {
                return new ArrayList<>(selected);
            }
            String target = new ArrayList<>(existingIds).get(idx);
            if (selected.contains(target)) {
                selected.remove(target);
            } else {
                selected.add(target);
            }
        }
    }

    /** Human-readable preview of the workflow before saving. */
    static String teamPreview(WorkflowTeam team) {
        WorkflowTeamSnapshot snapshot = new WorkflowTeamSnapshot(team,
                team.participants().keySet().stream()
                        .collect(java.util.stream.Collectors.toMap(id -> id,
                                id -> team.participant(id).role(),
                                (a, b) -> a, java.util.LinkedHashMap::new)),
                null);
        return snapshot.summarize();
    }

    // ── Shared wizard primitives (same contract as SetupWizard) ────────────

    static String prompt(LineReader reader, String question) {
        try {
            return reader.readLine(question);
        } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
            return null;
        }
    }

    /** Strict yes/no; null on cancel/EOF. Blank follows the bracket default ([Y/n] = yes, [y/N] = no). */
    static Boolean yesNo(LineReader reader, String question) {
        while (true) {
            String input = prompt(reader, "  " + question + ": ");
            if (input == null) return null;
            switch (input.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "y", "yes" -> { return true; }
                case "n", "no" -> { return false; }
                case "" -> {
                    // Blank takes the bracketed default: [Y/n] means yes, [y/N] means no.
                    return question.contains("[Y/n]");
                }
                case "q", "quit", "cancel" -> { return null; }
                default -> System.out.println("  Please enter yes or no.");
            }
        }
    }

    /** Numbered menu identical in spirit to SetupWizard.selectNumbered; -1 cancels. */
    static int selectNumbered(LineReader reader, String title, List<String> items) {
        System.out.println(BOLD + "  " + title + RESET);
        System.out.println();
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, items.get(i));
        }
        System.out.println();
        while (true) {
            String input = prompt(reader, "  Select 1-" + items.size() + " (blank/`q` cancels): ");
            if (input == null || input.isBlank() || input.trim().equalsIgnoreCase("q")) return -1;
            try {
                int choice = Integer.parseInt(input.trim());
                if (choice >= 1 && choice <= items.size()) return choice - 1;
            } catch (NumberFormatException ignored) {
                // fall through
            }
            System.out.println("  Please enter a number between 1 and " + items.size() + ".");
        }
    }
}
