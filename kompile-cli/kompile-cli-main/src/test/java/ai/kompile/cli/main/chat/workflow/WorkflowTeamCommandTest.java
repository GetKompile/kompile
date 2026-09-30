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
import ai.kompile.cli.main.chat.config.ChatProfiles;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code /workflow} command both chat REPLs share: showing the active team,
 * approving its gates, rebinding participant models (saved, and swapped into
 * the running chat), and creating, listing, and deleting saved teams. The
 * team's roles are created in the user's home, so the class runs in a
 * temporary one.
 */
@TemporaryUserHome
class WorkflowTeamCommandTest {

    private static final WorkflowTeam.ModelBinding FAST = new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null);
    /** The chat's model as a CLI-agent chat runs it, through Claude Code. */
    private static final WorkflowTeam.ModelBinding CLI_CHAT_MODEL =
            new WorkflowTeam.ModelBinding("anthropic", "claude-opus-5-5", null, null, null, "claude");

    @TempDir
    Path project;

    private WorkflowTeam team;

    @BeforeEach
    void saveTheTemplateTeamAndItsRoles() throws IOException {
        team = WorkflowWizard.designerWorkersTeam();
        assertTrue(WorkflowTeamStore.save(project, team, true));
        RoleManager roleManager = new RoleManager(project);
        for (WorkflowWizard.RoleDefinition role : WorkflowWizard.designerWorkerRoles()) {
            if (roleManager.getRole(role.name()) == null) {
                roleManager.createRole(role.name(), role.name(), role.description(), "workflow", role.systemPrompt());
            }
        }
    }

    @AfterEach
    void deactivate() {
        WorkflowSessionContext.activate(null);
    }

    @Test
    void anUnknownCommandShowsTheUsage() {
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("Frobnicate now", console, project, chat());

        assertEquals(List.of("Unknown /workflow command 'frobnicate'.", "Usage: " + WorkflowTeamCommand.USAGE),
                console.lines());
    }

    // ── show ────────────────────────────────────────────────────────────────

    @Test
    void showWithoutAnActiveTeamSaysHowToStartOne() {
        for (String args : List.of("", "show", "status")) {
            ScriptedConsole console = console();

            WorkflowTeamCommand.run(args, console, project, chat());

            assertTrue(console.text().startsWith("No workflow team is active in this chat."), args);
            assertTrue(console.text().contains("/workflow create builds one."), args);
        }
    }

    @Test
    void showNamesTheCallerTheGatesAndEachParticipantsModel() {
        activate();
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("show", console, project, chat());

        List<String> lines = console.lines();
        assertTrue(lines.contains("Workflow 'designer-workers' — you are 'designer' (architect)"), console.text());
        assertTrue(lines.contains("Outstanding: implementation gate: user-approved-design;"
                + " completion gate: all-tasks-accepted"), console.text());
        assertTrue(lines.contains("Participant models"), console.text());
        assertTrue(lines.contains("designer (lead) the chat's model (anthropic/claude-opus-5-5)"), console.text());
        assertTrue(lines.contains("worker the lead's model (anthropic/claude-opus-5-5)"), console.text());
        assertTrue(lines.contains("Approve the next gate with /workflow approve [gate]."), console.text());
        assertTrue(lines.contains("Change a participant's model with /workflow model <participant>."), console.text());
    }

    @Test
    void showDropsTheApproveHintOnceEveryGateIsApproved() {
        WorkflowSessionContext context = activate();
        context.approve(null);
        context.approve(null);
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("", console, project, chat());

        List<String> lines = console.lines();
        assertTrue(lines.contains("Outstanding: none"), console.text());
        assertFalse(lines.contains("Approve the next gate with /workflow approve [gate]."), console.text());
    }

    // ── approve ─────────────────────────────────────────────────────────────

    @Test
    void approveWithoutAnActiveTeamSaysWhereGatesAreApproved() {
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("approve", console, project, chat());

        assertEquals(List.of("No workflow team is active in this chat; gates are approved in the chat that"
                + " leads the team."), console.lines());
    }

    @Test
    void approveTakesTheGateThatBlocksNextUntilNoneIsLeft() {
        WorkflowSessionContext context = activate();

        ScriptedConsole first = console();
        WorkflowTeamCommand.run("approve", first, project, chat());
        assertEquals(List.of("Approved 'user-approved-design'.", "Outstanding: completion gate: all-tasks-accepted"),
                first.lines());
        assertTrue(context.enforcement().implementationGateSatisfied());

        ScriptedConsole second = console();
        WorkflowTeamCommand.run("approve", second, project, chat());
        assertEquals(List.of("Approved 'all-tasks-accepted'.", "Outstanding: none"), second.lines());

        ScriptedConsole third = console();
        WorkflowTeamCommand.run("approve", third, project, chat());
        assertEquals(List.of("Every gate of workflow 'designer-workers' is already approved."), third.lines());
    }

    @Test
    void approveNamesAGateInAnyCaseAndRefusesOneTheTeamDoesNotHave() {
        activate();

        ScriptedConsole named = console();
        WorkflowTeamCommand.run("approve All-Tasks-Accepted", named, project, chat());
        assertEquals(List.of("Approved 'all-tasks-accepted'.", "Outstanding: implementation gate: user-approved-design"),
                named.lines());

        ScriptedConsole unknown = console();
        WorkflowTeamCommand.run("approve shipped", unknown, project, chat());
        assertEquals(List.of("Workflow 'designer-workers' has no gate 'shipped'. Its gates: implementation"
                + " 'user-approved-design', completion 'all-tasks-accepted'."), unknown.lines());
    }

    // ── model / models ──────────────────────────────────────────────────────

    @Test
    void modelWithoutAnActiveTeamPointsToReviewingASavedOne() {
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("model worker", console, project, chat());

        assertEquals(List.of("No workflow team is active in this chat. Review a saved team's models with"
                + " /workflow models <name>."), console.lines());
    }

    @Test
    void modelRebindsAParticipantSavesItAndSwapsItIntoTheActiveTeam() throws IOException {
        saveFastProfile();
        activate();
        // 1 Same as the lead, 2 this chat's model, 3 the 'fast' profile.
        ScriptedConsole console = console("3");

        WorkflowTeamCommand.run("model worker", console, project, chat());

        assertTrue(console.exhausted());
        WorkflowTeam stored = WorkflowTeamStore.get(project, "designer-workers");
        assertEquals(FAST, stored.participant("worker").model());
        assertEquals(2, stored.version(), "a changed definition is stored under the next version");
        assertEquals(stored, WorkflowSessionContext.current().snapshot().team());
        assertTrue(console.lines().contains("Delegations from this chat now use the updated models."), console.text());
        assertFalse(console.text().contains("The lead's model applies"), console.text());
    }

    @Test
    void rebindingTheLeadSaysItAppliesWhenAChatStartsWithTheTeam() throws IOException {
        saveFastProfile();
        activate();
        // 1 the chat's configured model, 2 this chat's model, 3 the 'fast' profile.
        ScriptedConsole console = console("3");

        WorkflowTeamCommand.run("model Designer", console, project, chat());

        assertEquals(FAST, WorkflowSessionContext.current().snapshot().team().participant("designer").model());
        List<String> lines = console.lines();
        assertTrue(lines.contains("Delegations from this chat now use the updated models."), console.text());
        assertTrue(lines.contains("The lead's model applies when a chat starts with this team; /model switches"
                + " this chat now."), console.text());
    }

    @Test
    void pickingTheModelAParticipantAlreadyRunsOnSavesNothing() throws IOException {
        activate();
        // 1 Same as the lead, which the unbound worker already follows.
        ScriptedConsole console = console("1");

        WorkflowTeamCommand.run("model worker", console, project, chat());

        assertEquals(team, WorkflowTeamStore.get(project, "designer-workers"));
        assertFalse(console.text().contains("Saved workflow"), console.text());
        assertFalse(console.text().contains("Delegations from this chat"), console.text());
    }

    @Test
    void modelRefusesAParticipantTheTeamDoesNotHave() throws IOException {
        activate();
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("model intruder", console, project, chat());

        assertEquals(List.of("Workflow 'designer-workers' has no participant 'intruder'."), console.lines());
        assertEquals(team, WorkflowTeamStore.get(project, "designer-workers"));
    }

    @Test
    void aCliAgentChatLeadsAsItselfAndBindsDelegatesToAnAgentAndModel() throws IOException {
        activate();

        ScriptedConsole lead = console();
        WorkflowTeamCommand.run("model designer", lead, project, cliChat());
        assertEquals(List.of("'designer' leads as this chat's CLI agent; change the chat's model with /model."),
                lead.lines());

        // Only the worker needs a model, so the command does not ask whose:
        // 1 unassigned, 2 this chat's agent and model.
        ScriptedConsole worker = console("2");
        WorkflowTeamCommand.run("model", worker, project, cliChat());

        assertFalse(worker.text().contains("Change which participant's model?"), worker.text());
        assertEquals(CLI_CHAT_MODEL,
                WorkflowTeamStore.get(project, "designer-workers").participant("worker").model());
        assertEquals(CLI_CHAT_MODEL,
                WorkflowSessionContext.current().snapshot().team().participant("worker").model());
        assertTrue(worker.lines().contains("Delegations from this chat now use the updated models."), worker.text());
    }

    @Test
    void modelsReviewsTheActiveTeamAndSwapsInTheChange() throws IOException {
        saveFastProfile();
        activate();
        ScriptedConsole console = console(
                "2",    // Change a participant's model
                "2",    // the worker
                "3",    // the 'fast' profile
                "1");   // Use these models

        WorkflowTeamCommand.run("models", console, project, chat());

        assertTrue(console.exhausted());
        assertEquals(FAST, WorkflowTeamStore.get(project, "designer-workers").participant("worker").model());
        assertEquals(FAST, WorkflowSessionContext.current().snapshot().team().participant("worker").model());
        assertTrue(console.lines().contains("Delegations from this chat now use the updated models."), console.text());
    }

    @Test
    void modelsForAnotherTeamSavesTheChangeWithoutTouchingTheActiveOne() throws IOException {
        saveFastProfile();
        assertTrue(WorkflowTeamStore.save(project, WorkflowWizard.designerWorkersReviewerTeam(), true));
        activate();
        ScriptedConsole console = console("2", "2", "3", "1");

        WorkflowTeamCommand.run("models Designer-Workers-Reviewer", console, project, chat());

        assertTrue(console.exhausted());
        assertEquals(FAST, WorkflowTeamStore.get(project, "designer-workers-reviewer").participant("worker").model());
        assertEquals(team, WorkflowSessionContext.current().snapshot().team());
        assertFalse(console.text().contains("Delegations from this chat"), console.text());
    }

    @Test
    void modelsNamesAnUnknownTeam() {
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("models nosuch", console, project, chat());

        assertEquals(List.of("No workflow named 'nosuch'. See /workflow list."), console.lines());
    }

    @Test
    void modelsWithoutAnActiveTeamPicksASavedOne() throws IOException {
        ScriptedConsole console = console(
                "1",    // designer-workers
                "1");   // Use these models

        WorkflowTeamCommand.run("models", console, project, chat());

        assertTrue(console.exhausted());
        assertTrue(console.lines().contains("Review which workflow's models?"), console.text());
        assertTrue(console.lines().contains("Participant models"), console.text());
        assertEquals(team, WorkflowTeamStore.get(project, "designer-workers"), "keeping the models saves nothing");
    }

    @Test
    void modelsWithNothingSavedSaysHowToCreateATeam() throws IOException {
        assertTrue(WorkflowTeamStore.delete(project, "designer-workers"));
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("models", console, project, chat());

        assertEquals(List.of("No workflow teams saved yet. Create one with /workflow create."), console.lines());
    }

    // ── create / list / delete ──────────────────────────────────────────────

    @Test
    void createSavesATemplateTeamWithItsRoles() throws IOException {
        ScriptedConsole console = console(
                "2",    // Designer + workers + reviewer (template)
                "1");   // Use these models

        WorkflowTeamCommand.run("create", console, project, chat());

        assertTrue(console.exhausted());
        assertEquals(WorkflowWizard.designerWorkersReviewerTeam(),
                WorkflowTeamStore.get(project, "designer-workers-reviewer"));
        assertNotNull(new RoleManager(project).getRole("reviewer"));
        assertTrue(console.lines().contains("Start a chat with it: kompile chat --workflow designer-workers-reviewer"),
                console.text());
    }

    @Test
    void createCancelledWritesNothing() throws IOException {
        WorkflowTeamCommand.run("new", console(), project, chat());

        assertEquals(List.of(team), WorkflowTeamStore.list(project));
    }

    @Test
    void listMarksTheActiveTeamAndShowsEachParticipantsModel() throws IOException {
        assertTrue(WorkflowTeamStore.save(project, WorkflowWizard.designerWorkersReviewerTeam(), true));
        activate();
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("ls", console, project, chat());

        List<String> lines = console.lines();
        assertEquals("Workflow teams", lines.get(0));
        assertTrue(lines.contains("designer-workers v1 [active] — 2 participants, purposes: implement"),
                console.text());
        assertTrue(lines.contains("designer-workers-reviewer v1 — 3 participants, purposes: implement, review"),
                console.text());
        assertTrue(lines.contains("reviewer the lead's model (anthropic/claude-opus-5-5)"), console.text());
    }

    @Test
    void listWithNoTeamsSaysWhereTheyAreSaved() throws IOException {
        assertTrue(WorkflowTeamStore.delete(project, "designer-workers"));
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("list", console, project, chat());

        assertEquals(List.of("No workflow teams saved in " + WorkflowTeamStore.path(project)
                + ". Create one with /workflow create."), console.lines());
    }

    @Test
    void deleteRefusesTheActiveTeam() throws IOException {
        activate();
        ScriptedConsole console = console("y");

        WorkflowTeamCommand.run("delete Designer-Workers", console, project, chat());

        assertEquals(List.of("'Designer-Workers' is this chat's active team; it cannot be deleted while in use."),
                console.lines());
        assertFalse(console.exhausted(), "an active team is refused without asking");
        assertEquals(team, WorkflowTeamStore.get(project, "designer-workers"));
    }

    @Test
    void deleteAsksFirstAndKeepsTheTeamByDefault() throws IOException {
        WorkflowTeamCommand.run("delete designer-workers", console(""), project, chat());
        assertEquals(team, WorkflowTeamStore.get(project, "designer-workers"), "blank takes the [y/N] default");

        ScriptedConsole confirmed = console("y");
        WorkflowTeamCommand.run("rm designer-workers", confirmed, project, chat());
        assertTrue(confirmed.lines().contains("Deleted workflow 'designer-workers'."), confirmed.text());
        assertNull(WorkflowTeamStore.get(project, "designer-workers"));

        ScriptedConsole again = console("y");
        WorkflowTeamCommand.run("remove designer-workers", again, project, chat());
        assertTrue(again.lines().contains("No workflow named 'designer-workers'."), again.text());
    }

    @Test
    void deleteWithoutANameShowsItsUsage() {
        ScriptedConsole console = console();

        WorkflowTeamCommand.run("delete", console, project, chat());

        assertEquals(List.of("Usage: /workflow delete <name>"), console.lines());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Makes the saved template team this chat's active team, led by this process. */
    private WorkflowSessionContext activate() {
        WorkflowSessionContext.activate(WorkflowTeamSnapshot.resolve(team, new RoleManager(project)));
        return WorkflowSessionContext.current();
    }

    private void saveFastProfile() throws IOException {
        assertTrue(ChatProfiles.save(project, new ChatProfiles.Profile("fast", "openai", "standard",
                FAST.provider(), FAST.model(), null, null, null, false, false, null), false));
    }

    /** An in-process chat on Claude Opus. */
    private static ChatConfig chat() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setChatMode("standard");
        return config;
    }

    /** A managed CLI-agent chat running Claude Code on the same model. */
    private static ChatConfig cliChat() {
        ChatConfig config = new ChatConfig(null, null, "claude-opus-5-5", null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent("claude");
        return config;
    }

    private static ScriptedConsole console(String... answers) {
        return new ScriptedConsole(List.of(answers));
    }

    /** Answers prompts from a script and records everything shown; EOF once the script runs out. */
    private static final class ScriptedConsole implements WorkflowWizard.Console {
        private final ArrayDeque<String> answers;
        private final List<String> shown = new ArrayList<>();

        ScriptedConsole(List<String> answers) {
            this.answers = new ArrayDeque<>(answers);
        }

        @Override
        public void println(String line) {
            shown.add(line);
        }

        @Override
        public String readLine(String prompt) {
            shown.add(prompt);
            return answers.pollFirst();
        }

        boolean exhausted() {
            return answers.isEmpty();
        }

        /** The non-blank lines shown, without colors, trimmed, with runs of spaces collapsed. */
        List<String> lines() {
            return shown.stream()
                    .map(line -> line.replaceAll("\033\\[[0-9;]*m", "").trim().replaceAll("\\s+", " "))
                    .filter(line -> !line.isEmpty())
                    .toList();
        }

        String text() {
            return String.join("\n", lines());
        }
    }
}
