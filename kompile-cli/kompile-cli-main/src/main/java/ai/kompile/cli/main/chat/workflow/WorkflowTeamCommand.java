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

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@code /workflow} chat command, shared by the standard chat and the
 * managed CLI-agent REPL so a team is managed the same way in both: show the
 * active team with its participants' models and outstanding gates, approve a
 * gate, review or change participant models, and create, list, or delete saved
 * teams. All interaction goes through a {@link WorkflowWizard.Console}.
 */
public final class WorkflowTeamCommand {

    public static final String USAGE =
            "/workflow [show|approve [gate]|models [name]|model <participant>|create|list|delete <name>]";

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";

    private WorkflowTeamCommand() {}

    /**
     * Runs {@code /workflow <args>} in {@code projectRoot} for the chat
     * {@code current} configures (null when unknown).
     */
    public static void run(String args, WorkflowWizard.Console console, Path projectRoot, ChatConfig current) {
        String trimmed = args == null ? "" : args.trim();
        int space = trimmed.indexOf(' ');
        String command = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : trimmed.substring(space + 1).trim();
        switch (command) {
            case "", "show", "status" -> show(console, projectRoot, current);
            case "approve" -> approve(console, rest);
            case "models" -> models(console, projectRoot, rest, current);
            case "model" -> model(console, projectRoot, rest, current);
            case "create", "new" -> create(console, projectRoot, current);
            case "list", "ls" -> list(console, projectRoot, current);
            case "delete", "remove", "rm" -> delete(console, projectRoot, rest);
            default -> {
                console.println("  " + YELLOW + "Unknown /workflow command '" + command + "'." + RESET);
                console.println("  Usage: " + USAGE);
            }
        }
    }

    /** The active team: who this chat is in it, its gates, and the model each participant runs on. */
    private static void show(WorkflowWizard.Console console, Path projectRoot, ChatConfig current) {
        WorkflowSessionContext context = WorkflowSessionContext.current();
        if (context == null) {
            console.println("  No workflow team is active in this chat. Start one with"
                    + " `kompile chat --workflow <name>` or the setup wizard; /workflow create builds one.");
            return;
        }
        WorkflowTeamEnforcement enforcement = context.enforcement();
        for (String line : enforcement.statusLine().split("\n")) {
            console.println("  " + line);
        }
        WorkflowTeam team = enforcement.team();
        console.println();
        console.println(BOLD + "  Participant models" + RESET);
        WorkflowWizard.modelRows(team, current, WorkflowWizard.candidates(console, projectRoot, current))
                .forEach(row -> console.println("    " + row));
        for (String note : WorkflowModelDefaults.notes(team, current)) {
            console.println("  " + YELLOW + "- " + note + RESET);
        }
        console.println();
        if (!"none".equals(enforcement.outstandingObligations())) {
            console.println("  " + DIM + "Approve the next gate with /workflow approve [gate]." + RESET);
        }
        console.println("  " + DIM + "Change a participant's model with /workflow model <participant>." + RESET);
    }

    /** The user's gate approval; no tool reaches it, so the lead cannot approve its own design. */
    private static void approve(WorkflowWizard.Console console, String gate) {
        WorkflowSessionContext context = WorkflowSessionContext.current();
        if (context == null) {
            console.println("  " + YELLOW + "No workflow team is active in this chat; gates are approved in"
                    + " the chat that leads the team." + RESET);
            return;
        }
        try {
            String approved = context.approve(gate);
            console.println("  " + GREEN + "Approved '" + approved + "'." + RESET);
            console.println("  Outstanding: " + context.enforcement().outstandingObligations());
        } catch (IllegalArgumentException e) {
            console.println("  " + YELLOW + e.getMessage() + RESET);
        }
    }

    /**
     * Reviews a team's participant models: the named team, else the active
     * one, else a saved team the user picks. Changes are saved, and a change
     * to the active team applies to this chat's next delegation.
     */
    private static void models(WorkflowWizard.Console console, Path projectRoot, String name, ChatConfig current) {
        WorkflowSessionContext context = WorkflowSessionContext.current();
        WorkflowTeam team;
        try {
            if (!name.isBlank()) {
                team = WorkflowTeamStore.get(projectRoot, name);
                if (team == null) {
                    console.println("  " + YELLOW + "No workflow named '" + name + "'. See /workflow list." + RESET);
                    return;
                }
            } else if (context != null) {
                team = context.snapshot().team();
            } else {
                List<WorkflowTeam> teams = WorkflowTeamStore.list(projectRoot);
                if (teams.isEmpty()) {
                    console.println("  No workflow teams saved yet. Create one with /workflow create.");
                    return;
                }
                int idx = WorkflowWizard.selectNumbered(console, "Review which workflow's models?",
                        teams.stream().map(WorkflowTeam::name).toList());
                if (idx < 0) return;
                team = teams.get(idx);
            }
        } catch (IOException | IllegalStateException e) {
            readFailure(console, e.getMessage());
            return;
        } catch (UncheckedIOException e) {
            readFailure(console, e.getCause().getMessage());
            return;
        }
        WorkflowTeam reviewed = WorkflowWizard.reviewModels(console, projectRoot, team, current);
        if (reviewed != null && !reviewed.equals(team)) applyToActive(console, projectRoot, reviewed);
    }

    /** Rebinds one participant of the active team, asking which when none is named. */
    private static void model(WorkflowWizard.Console console, Path projectRoot, String participant,
                              ChatConfig current) {
        WorkflowSessionContext context = WorkflowSessionContext.current();
        if (context == null) {
            console.println("  " + YELLOW + "No workflow team is active in this chat. Review a saved team's"
                    + " models with /workflow models <name>." + RESET);
            return;
        }
        WorkflowTeam team = context.snapshot().team();
        WorkflowTeam changed = WorkflowWizard.changeModel(console, projectRoot, team,
                participant.isBlank() ? null : participant, current);
        if (changed != null && !changed.equals(team)) applyToActive(console, projectRoot, changed);
    }

    /**
     * Swaps an updated definition of the active team into this chat, keeping
     * its session and approved gates. Nothing happens for another team.
     */
    private static void applyToActive(WorkflowWizard.Console console, Path projectRoot, WorkflowTeam team) {
        WorkflowSessionContext context = WorkflowSessionContext.current();
        if (!isActive(context, team.name())) return;
        WorkflowTeam previous = context.snapshot().team();
        try {
            WorkflowSessionContext.replace(WorkflowTeamSnapshot.resolve(team, new RoleManager(projectRoot)));
        } catch (IllegalArgumentException e) {
            console.println("  " + YELLOW + "Saved, but this chat keeps its current team: " + e.getMessage() + RESET);
            return;
        }
        console.println("  " + GREEN + "Delegations from this chat now use the updated models." + RESET);
        WorkflowTeam.Participant lead = team.participant(team.lead());
        WorkflowTeam.Participant previousLead = previous.participant(team.lead());
        if (lead != null && previousLead != null && !Objects.equals(lead.model(), previousLead.model())) {
            console.println("  " + DIM + "The lead's model applies when a chat starts with this team;"
                    + " /model switches this chat now." + RESET);
        }
    }

    /** Creates a team from a template or step by step. */
    private static void create(WorkflowWizard.Console console, Path projectRoot, ChatConfig current) {
        List<String> options = List.of(
                "Designer + workers (template)",
                "Designer + workers + reviewer (template)",
                "Build a team step by step");
        int choice = WorkflowWizard.selectNumbered(console, "Create a workflow team:", options);
        if (choice < 0) return;
        WorkflowTeam team = switch (choice) {
            case 0 -> WorkflowWizard.createFromTemplate(console, projectRoot,
                    WorkflowWizard.designerWorkersTeam(), WorkflowWizard.designerWorkerRoles(), current);
            case 1 -> WorkflowWizard.createFromTemplate(console, projectRoot,
                    WorkflowWizard.designerWorkersReviewerTeam(), WorkflowWizard.designerWorkerReviewerRoles(),
                    current);
            default -> WorkflowWizard.create(console, projectRoot, current);
        };
        if (team != null) {
            console.println("  Start a chat with it: kompile chat --workflow " + team.name());
        }
    }

    /** Saved teams with their participants' models under this chat. */
    private static void list(WorkflowWizard.Console console, Path projectRoot, ChatConfig current) {
        List<WorkflowTeam> teams;
        try {
            teams = WorkflowTeamStore.list(projectRoot);
        } catch (IOException | IllegalStateException e) {
            readFailure(console, e.getMessage());
            return;
        } catch (UncheckedIOException e) {
            readFailure(console, e.getCause().getMessage());
            return;
        }
        if (teams.isEmpty()) {
            console.println("  No workflow teams saved in " + WorkflowTeamStore.path(projectRoot)
                    + ". Create one with /workflow create.");
            return;
        }
        WorkflowSessionContext context = WorkflowSessionContext.current();
        List<WorkflowModelDefaults.Candidate> candidates = WorkflowWizard.candidates(console, projectRoot, current);
        console.println(BOLD + "  Workflow teams" + RESET);
        for (WorkflowTeam team : teams) {
            boolean active = isActive(context, team.name());
            console.println("  " + BOLD + team.name() + RESET + DIM + " v" + team.version() + RESET
                    + (active ? " " + GREEN + "[active]" + RESET : "")
                    + " — " + team.participants().size() + " participants"
                    + (team.routing().isEmpty() ? "" : ", purposes: " + String.join(", ", team.routing().keySet())));
            WorkflowWizard.modelRows(team, current, candidates).forEach(row -> console.println("      " + row));
        }
    }

    /** Deletes a saved team after confirmation; the active team is kept. */
    private static void delete(WorkflowWizard.Console console, Path projectRoot, String name) {
        if (name.isBlank()) {
            console.println("  Usage: /workflow delete <name>");
            return;
        }
        WorkflowSessionContext context = WorkflowSessionContext.current();
        if (isActive(context, name)) {
            console.println("  " + YELLOW + "'" + name + "' is this chat's active team; it cannot be deleted"
                    + " while in use." + RESET);
            return;
        }
        Boolean confirm = WorkflowWizard.yesNo(console, "Delete workflow '" + name + "'? [y/N]");
        if (confirm == null || !confirm) return;
        try {
            if (WorkflowTeamStore.delete(projectRoot, name)) {
                console.println("  " + GREEN + "Deleted workflow '" + name + "'." + RESET);
            } else {
                console.println("  " + YELLOW + "No workflow named '" + name + "'." + RESET);
            }
        } catch (IOException e) {
            console.println("  " + RED + "Could not delete workflow: " + e.getMessage() + RESET);
        }
    }

    /** Workflow names are stored and looked up case-insensitively. */
    private static boolean isActive(WorkflowSessionContext context, String name) {
        return context != null && WorkflowTeam.key(context.workflowName()).equals(WorkflowTeam.key(name));
    }

    private static void readFailure(WorkflowWizard.Console console, String message) {
        console.println("  " + RED + "Could not read project workflows: " + message + RESET);
    }
}
