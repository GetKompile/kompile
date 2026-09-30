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
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interactive creation must produce a valid, activation-ready workflow, with
 * each participant on a configured model, without the user ever editing
 * chat-workflows.json by hand. Roles are created in the user's home, so the
 * class runs in a temporary one.
 */
@TemporaryUserHome
class WorkflowWizardTest {

    /** A second model the project saved as the chat profile 'fast'. */
    private static final WorkflowTeam.ModelBinding PROFILE_MODEL =
            new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null);

    @TempDir
    Path project;

    // ── Full creation wizard ────────────────────────────────────────────────

    @Test
    void abandonedCreationWritesNothing() {
        // EOF at the second participant's role abandons creation; nothing is saved.
        WorkflowTeam created = WorkflowWizard.create(console(
                "designer-workers",                     // workflow name
                "designer",                             // participant 1 id
                "1",                                    // role: first sorted existing role
                "1", "7",                               // capabilities: read; Done
                "y",                                    // add another participant
                "worker"                                // participant 2 id, then EOF
        ), project, null);
        assertNull(created);
        assertFalse(Files.exists(WorkflowTeamStore.path(project)));
    }

    @Test
    void completeTwoParticipantWorkflowIsSavedAndResolvable() throws Exception {
        // No chat configuration and no profiles: there is no model to choose,
        // so the model step is skipped and every participant follows the chat.
        ScriptedConsole console = console(designerWorkerAnswers(), "");  // save: [Y/n] default yes
        WorkflowTeam created = WorkflowWizard.create(console, project, null);
        assertNotNull(created, "creation should complete and save");
        assertTrue(console.exhausted(), "every scripted answer should be consumed");
        assertEquals("designer-workers", created.name());
        assertEquals("designer", created.lead());
        assertEquals(2, created.participants().size());
        assertEquals(List.of("worker"), created.participant("designer").delegatesTo());
        assertEquals(List.of(), created.participant("worker").delegatesTo());
        assertEquals("worker", created.route("implement").id());
        assertEquals(3, created.maxConcurrentWorkers());
        assertTrue(created.gates().hasImplementationGate());
        assertFalse(created.gates().hasCompletionGate());
        assertNull(created.participant("worker").model());

        // Must be activatable: every referenced role resolves.
        WorkflowTeam stored = WorkflowTeamStore.get(project, "designer-workers");
        assertEquals(created, stored);
        assertDoesNotThrow(() -> WorkflowTeamSnapshot.resolve(stored, new RoleManager(project)));
    }

    @Test
    void createdWorkflowRunsWorkersOnAnotherConfiguredModel() throws Exception {
        saveProfile();
        ChatConfig chat = chat();
        ScriptedConsole console = console(designerWorkerAnswers(),
                "1",                                    // Use these models
                "");                                    // save
        WorkflowTeam created = WorkflowWizard.create(console, project, chat);
        assertNotNull(created);
        assertTrue(console.exhausted());
        assertEquals(PROFILE_MODEL, created.participant("worker").model(),
                "the worker defaults to the configured model the lead does not run on");
        assertNull(created.participant("designer").model(), "the lead follows the chat's own model");
        assertTrue(console.transcript().contains("Participant models"));
        assertTrue(console.transcript().contains(PROFILE_MODEL.label() + " [profile 'fast']"));
        assertEquals(created, WorkflowTeamStore.get(project, "designer-workers"));
    }

    @Test
    void cancelAtNamePromptWritesNothing() {
        assertNull(WorkflowWizard.create(console(), project, null));
        assertNull(WorkflowWizard.create(console("cancel-ish-name", "designer"), project, null));
        assertFalse(Files.exists(WorkflowTeamStore.path(project)));
    }

    @Test
    void duplicateWorkflowReplacementRequiresExplicitYes() throws Exception {
        WorkflowTeam existing = new WorkflowTeam("dup", 1, "p1",
                Map.of("p1", new WorkflowTeam.Participant("p1", "r1", "cli",
                        List.of("read"), List.of())),
                Map.of(), null, null);
        assertTrue(WorkflowTeamStore.save(project, existing, true));

        // Blank takes [y/N]'s default (no) and asks for another name; EOF cancels.
        assertNull(WorkflowWizard.create(console("dup", ""), project, null));
        assertEquals(existing, WorkflowTeamStore.get(project, "dup"));

        ScriptedConsole console = console(
                "dup", "y",                             // name; replace it
                "p1", "1", "1", "2", "7",               // id, role, read + plan, Done
                "n",                                    // no more participants
                "",                                     // max concurrent workers: 1
                "", "",                                 // no gates
                "");                                    // save
        WorkflowTeam replaced = WorkflowWizard.create(console, project, null);
        assertNotNull(replaced);
        assertTrue(console.exhausted());
        assertEquals(List.of("read", "plan"),
                WorkflowTeamStore.get(project, "dup").participant("p1").capabilities());
    }

    // ── Templates and participant models ────────────────────────────────────

    @Test
    void templateRunsWorkersOnAnotherModelAndKeepsTheReviewerIndependent() throws Exception {
        saveProfile();
        ScriptedConsole console = console("1");     // Use these models
        WorkflowTeam created = WorkflowWizard.createFromTemplate(console, project,
                WorkflowWizard.designerWorkersReviewerTeam(), WorkflowWizard.designerWorkerReviewerRoles(), chat());
        assertNotNull(created);
        assertTrue(console.exhausted());
        assertEquals(PROFILE_MODEL, created.participant("worker").model());
        // The reviewer takes the model the worker does not use: the chat's, which
        // stays implicit so the reviewer follows the chat.
        assertNull(created.participant("reviewer").model());
        assertNull(created.participant("designer").model());
        assertEquals(created, WorkflowTeamStore.get(project, "designer-workers-reviewer"));

        RoleManager roleManager = new RoleManager(project);
        for (WorkflowWizard.RoleDefinition role : WorkflowWizard.designerWorkerReviewerRoles()) {
            assertNotNull(roleManager.getRole(role.name()), role.name());
        }
        assertDoesNotThrow(() -> WorkflowTeamSnapshot.resolve(created, roleManager));
    }

    @Test
    void participantModelCanBeChangedBeforeSaving() {
        saveProfileUnchecked();
        ChatConfig chat = chat();
        ScriptedConsole console = console(
                "2",                                    // Change a participant's model
                "2",                                    // worker
                "1",                                    // Same as the lead
                "1");                                   // Use these models
        WorkflowTeam created = WorkflowWizard.createFromTemplate(console, project,
                WorkflowWizard.designerWorkersTeam(), WorkflowWizard.designerWorkerRoles(), chat);
        assertNotNull(created);
        assertTrue(console.exhausted());
        assertNull(created.participant("worker").model(), "the worker now follows the lead");
        assertTrue(console.transcript().contains(
                "Same as the lead (" + WorkflowModelDefaults.bindingOf(chat).label() + ")"));
    }

    @Test
    void everyParticipantCanRunOnTheChatModel() {
        saveProfileUnchecked();
        ScriptedConsole console = console(
                "3",                                    // Run every participant on the chat's model
                "1");                                   // Use these models
        WorkflowTeam created = WorkflowWizard.createFromTemplate(console, project,
                WorkflowWizard.designerWorkersTeam(), WorkflowWizard.designerWorkerRoles(), chat());
        assertNotNull(created);
        created.participants().values().forEach(participant ->
                assertNull(participant.model(), participant.id() + " should follow the chat"));
    }

    @Test
    void cancellingTheModelStepWritesNothing() {
        saveProfileUnchecked();
        assertNull(WorkflowWizard.createFromTemplate(console(), project,
                WorkflowWizard.designerWorkersTeam(), WorkflowWizard.designerWorkerRoles(), chat()));
        assertFalse(Files.exists(WorkflowTeamStore.path(project)));
    }

    @Test
    void reviewingASavedTeamSavesAChangedModel() throws Exception {
        saveProfile();
        WorkflowTeam team = WorkflowWizard.designerWorkersTeam();
        assertTrue(WorkflowTeamStore.save(project, team, true));

        // Unchanged: nothing is rewritten.
        assertEquals(team, WorkflowWizard.reviewModels(console("1"), project, team, chat()));
        assertEquals(team, WorkflowTeamStore.get(project, team.name()));

        ScriptedConsole console = console(
                "2",                                    // Change a participant's model
                "2",                                    // worker
                "3",                                    // profile 'fast'
                "1");                                   // Use these models
        WorkflowTeam reviewed = WorkflowWizard.reviewModels(console, project, team, chat());
        assertNotNull(reviewed);
        assertTrue(console.exhausted());
        assertEquals(PROFILE_MODEL, reviewed.participant("worker").model());
        assertEquals(reviewed, WorkflowTeamStore.get(project, team.name()));
    }

    @Test
    void changeModelBindsOneParticipantAndRefusesUnknownOnes() throws Exception {
        saveProfile();
        WorkflowTeam team = WorkflowWizard.designerWorkersTeam();
        assertTrue(WorkflowTeamStore.save(project, team, true));

        WorkflowTeam changed = WorkflowWizard.changeModel(console("3"), project, team, "worker", chat());
        assertNotNull(changed);
        assertEquals(PROFILE_MODEL, WorkflowTeamStore.get(project, team.name()).participant("worker").model());

        ScriptedConsole console = console();
        assertNull(WorkflowWizard.changeModel(console, project, changed, "ghost", chat()));
        assertTrue(console.transcript().contains("has no participant 'ghost'"));
    }

    @Test
    void modelRowsNameWhereEachModelIsConfigured() throws Exception {
        saveProfile();
        ChatConfig chat = chat();
        WorkflowTeam.ModelBinding custom = new WorkflowTeam.ModelBinding("openai", "gpt-5", "high");
        WorkflowTeam team = WorkflowWizard.designerWorkersReviewerTeam();
        team = team.withParticipant(team.participant("worker").withModel(PROFILE_MODEL));
        team = team.withParticipant(team.participant("reviewer").withModel(custom));

        List<String> rows = WorkflowWizard.modelRows(team, chat, WorkflowModelDefaults.candidates(project, chat));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).startsWith("designer (lead)"), rows.get(0));
        assertTrue(rows.get(0).contains("the chat's model (" + WorkflowModelDefaults.bindingOf(chat).label() + ")"),
                rows.get(0));
        assertTrue(rows.get(1).contains(PROFILE_MODEL.label() + " [profile 'fast']"), rows.get(1));
        assertTrue(rows.get(2).contains(custom.label() + " [custom]"), rows.get(2));
    }

    @Test
    void unreadableProfilesStillOfferTheChatModel() throws Exception {
        Path profiles = ChatProfiles.path(project);
        Files.createDirectories(profiles.getParent());
        Files.writeString(profiles, "[]");
        ChatConfig chat = chat();
        ScriptedConsole console = console();

        List<WorkflowModelDefaults.Candidate> candidates = WorkflowWizard.candidates(console, project, chat);
        assertEquals(1, candidates.size());
        assertEquals(WorkflowModelDefaults.bindingOf(chat), candidates.get(0).binding());
        assertEquals("this chat", candidates.get(0).source());
        assertTrue(console.transcript().contains("Could not read the project's chat profiles"));
    }

    // ── Wizard steps ────────────────────────────────────────────────────────

    @Test
    void inlineRoleCreationRegistersTheRole() throws Exception {
        RoleManager roleManager = new RoleManager(project);
        String role = WorkflowWizard.createRoleInline(console(
                "SRE Oncall_Team",
                "Keeps production healthy",
                "Own incidents and rollbacks"
        ), roleManager);
        assertEquals("sre-oncall-team", role);
        RoleConfig created = new RoleManager(project).getRole(role);
        assertNotNull(created, "the role must persist");
        assertEquals("Keeps production healthy", created.getDescription());
        assertEquals("Own incidents and rollbacks", created.getSystemPrompt());

        // The role picker's last option creates one inline.
        String createOption = String.valueOf(roleManager.getAllRoles().size() + 1);
        assertEquals("release-captain", WorkflowWizard.chooseOrCreateRole(
                console(createOption, "release captain", "", ""), roleManager));
        assertNotNull(new RoleManager(project).getRole("release-captain"));
    }

    @Test
    void capabilityPickerEnforcesChatOnlyExclusivity() {
        // Numbered menu: 1=read … 6=chat-only, 7=Done.
        // read toggled on, then chat-only selected (clears read), then done.
        assertEquals(List.of("chat-only"), WorkflowWizard.selectCapabilities(console("1", "6", "7"), "x"));

        // chat-only toggled off again, then read on, then done.
        assertEquals(List.of("read"), WorkflowWizard.selectCapabilities(console("6", "6", "1", "7"), "x"));
    }

    @Test
    void emptyCapabilitySelectionIsRejected() {
        ScriptedConsole console = console("7");
        assertNull(WorkflowWizard.selectCapabilities(console, "x"));
        assertTrue(console.transcript().contains("Select at least one capability."));
    }

    @Test
    void delegationEdgesDefaultToReachableParticipantsAndRefuseLoops() {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("designer", participant("designer", "read", "plan", "delegate"));
        participants.put("worker", participant("worker", "read", "edit-assigned-files", "delegate"));
        participants.put("reviewer", participant("reviewer", "read", "validate"));

        ScriptedConsole console = console(
                "3",                                    // designer: worker ✓, reviewer ✓ preselected; Done
                "1",                                    // worker → designer: refused, designer reaches worker
                "2",                                    // worker → reviewer: toggled off
                "3");                                   // Done
        Map<String, List<String>> edges = WorkflowWizard.selectDelegationEdges(console, participants, "designer");
        assertNotNull(edges);
        assertTrue(console.exhausted());
        assertEquals(List.of("worker", "reviewer"), edges.get("designer"));
        assertEquals(List.of(), edges.get("worker"));
        assertEquals(List.of(), edges.get("reviewer"));
        assertTrue(console.transcript().contains("delegation cannot loop"));
    }

    @Test
    void leadWithoutDelegateIsWarned() {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("a", participant("a", "read"));
        participants.put("b", participant("b", "read"));
        ScriptedConsole console = console();
        Map<String, List<String>> edges = WorkflowWizard.selectDelegationEdges(console, participants, "a");
        assertEquals(Map.of("a", List.of(), "b", List.of()), edges);
        assertTrue(console.transcript().contains("lacks the 'delegate' capability"));
    }

    @Test
    void blankDefaultsFollowBracketHints() {
        // "[Y/n]" → blank means yes; "[y/N]" → blank means no.
        assertTrue(WorkflowWizard.yesNo(console(""), "Save this workflow? [Y/n]"));
        assertFalse(WorkflowWizard.yesNo(console(""), "Add another participant? [y/N]"));
        assertNull(WorkflowWizard.yesNo(console("q"), "Anything [y/N]"));
        assertNull(WorkflowWizard.yesNo(console(), "Anything [y/N]"));

        ScriptedConsole console = console("maybe", "YES");
        assertTrue(WorkflowWizard.yesNo(console, "Anything [y/N]"));
        assertTrue(console.transcript().contains("Please enter yes or no."));
    }

    @Test
    void numberedMenusRejectOutOfRangeInput() {
        ScriptedConsole console = console("0", "3", "two", "2");
        assertEquals(1, WorkflowWizard.selectNumbered(console, "Pick one:", List.of("a", "b")));
        assertTrue(console.transcript().contains("Please enter a number between 1 and 2."));
        assertEquals(-1, WorkflowWizard.selectNumbered(console("q"), "Pick one:", List.of("a", "b")));
        assertEquals(-1, WorkflowWizard.selectNumbered(console(""), "Pick one:", List.of("a", "b")));
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /**
     * The full wizard up to the model step: designer (read + delegate) leads,
     * worker (read + edit) takes 'implement', three concurrent workers, and an
     * implementation gate.
     */
    private static List<String> designerWorkerAnswers() {
        return List.of(
                "designer-workers",                     // workflow name
                "designer", "1", "1", "3", "7",         // id, first role, read + delegate, Done
                "y",                                    // add another participant
                "worker", "1", "1", "4", "7",           // id, first role, read + edit-assigned-files, Done
                "n",                                    // no more participants
                "1",                                    // lead: designer
                "2",                                    // designer delegates to worker (preselected): Done
                "implement", "",                        // a purpose for the only target; done
                "3",                                    // max concurrent workers
                "approved-design", "");                 // implementation gate; no completion gate
    }

    /** An in-process chat leading the team. */
    private static ChatConfig chat() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setChatMode("standard");
        return config;
    }

    private void saveProfile() throws IOException {
        assertTrue(ChatProfiles.save(project, new ChatProfiles.Profile("fast", "openai", "standard",
                PROFILE_MODEL.provider(), PROFILE_MODEL.model(), null, null, null, false, false, null), false));
    }

    private void saveProfileUnchecked() {
        try {
            saveProfile();
        } catch (IOException e) {
            throw new AssertionError("Could not save the chat profile", e);
        }
    }

    private static WorkflowTeam.Participant participant(String id, String... capabilities) {
        return new WorkflowTeam.Participant(id, "implementer", "cli", List.of(capabilities), List.of());
    }

    private static ScriptedConsole console(String... answers) {
        return new ScriptedConsole(List.of(answers));
    }

    private static ScriptedConsole console(List<String> script, String... more) {
        List<String> answers = new ArrayList<>(script);
        answers.addAll(List.of(more));
        return new ScriptedConsole(answers);
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

        String transcript() {
            return String.join("\n", shown);
        }
    }
}
