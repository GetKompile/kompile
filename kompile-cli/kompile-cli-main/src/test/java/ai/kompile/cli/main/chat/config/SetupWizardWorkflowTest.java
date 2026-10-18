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

package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import ai.kompile.cli.main.chat.workflow.WorkflowWizard;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Only the explicit Workflow chat mode prompts for a team. With no saved
 * workflows the wizard offers templates that create roles and save the team
 * — a user is never asked to hand-edit chat-workflows.json. Roles are
 * created in the user's home, so the class runs in a temporary one.
 */
@TemporaryUserHome
class SetupWizardWorkflowTest {

    @TempDir
    Path project;

    @Test
    void ordinaryChatsSkipTeamSelectionWithoutReadingInput() {
        LineReader noPrompt = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    throw new AssertionError("Ordinary chat must not prompt for a workflow: " + method);
                });
        for (String mode : List.of("standard", "passthrough")) {
            ChatConfig config = new ChatConfig("custom", null, "model", "http://localhost:9000/v1");
            config.setChatMode(mode);
            SetupWizard.WorkflowSelection skipped =
                    SetupWizard.workflowStep(noPrompt, project, config, false);
            assertFalse(skipped.cancelled());
            assertNull(skipped.snapshot());
        }
    }

    @Test
    void designerWorkersTemplateCreatesRolesAndSavesAndActivates() throws Exception {
        // Forced picker (workflow mode): template is option 1 in an empty store.
        SetupWizard.WorkflowSelection selection = SetupWizard.workflowStep(
                reader("1"), project, null, true);
        assertNotNull(selection.snapshot(), "template creation must yield an activatable workflow");
        assertFalse(selection.cancelled());

        WorkflowTeam saved = WorkflowTeamStore.get(project, "designer-workers");
        assertNotNull(saved);
        assertEquals("designer", saved.lead());
        assertEquals("worker", saved.route("implement").id());

        RoleManager roleManager = new RoleManager(project);
        assertNotNull(roleManager.getRole("architect"));
        assertNotNull(roleManager.getRole("implementer"));
    }

    @Test
    void designerWorkersReviewerTemplateSavesRoutingAndRoles() throws Exception {
        SetupWizard.WorkflowSelection selection = SetupWizard.chooseWorkflow(
                reader("2"), project);
        assertNotNull(selection.snapshot());
        WorkflowTeam saved = WorkflowTeamStore.get(project, "designer-workers-reviewer");
        assertNotNull(saved);
        assertEquals("reviewer", saved.route("review").id());
        RoleManager roleManager = new RoleManager(project);
        assertNotNull(roleManager.getRole("reviewer"));
    }

    @Test
    void templateReplacementOfExistingTeamRequiresExplicitYes() throws Exception {
        WorkflowTeam existing = WorkflowWizardTestHelper.designerWorkersTemplate();
        assertTrue(WorkflowTeamStore.save(project, existing, true));

        // Forced picker with one saved workflow: 1=saved team, 2=designer+workers
        // template, 3=+reviewer template, 4=create. Pick the template (2).
        // Decline replace → nothing written; selection cancelled.
        SetupWizard.WorkflowSelection declined =
                SetupWizard.chooseWorkflow(reader("2", "n"), project);
        assertNull(declined.snapshot());
        assertTrue(declined.cancelled());
        assertEquals(existing, WorkflowTeamStore.get(project, "designer-workers"));

        // Accept replace → proceeds, activation succeeds.
        SetupWizard.WorkflowSelection replaced =
                SetupWizard.chooseWorkflow(reader("2", "y"), project);
        assertNotNull(replaced.snapshot());
    }

    @Test
    void savedWorkflowsListFirstAndSelectDirectly() throws Exception {
        WorkflowTeam team = WorkflowWizardTestHelper.designerWorkersTemplate();
        assertTrue(WorkflowTeamStore.save(project, team, true));
        createTemplateRoles(project);
        SetupWizard.WorkflowSelection selection =
                SetupWizard.chooseWorkflow(reader("1"), project);
        assertNotNull(selection.snapshot());
        assertEquals("designer-workers", selection.snapshot().workflowName());
    }

    @Test
    void explicitWorkflowModeHonorsCancelAndEof() {
        assertTrue(SetupWizard.workflowStep(reader(), project, null, true).cancelled());
        assertTrue(SetupWizard.workflowStep(reader("q"), project, null, true).cancelled());
    }

    @Test
    void reusedProfileSkipsTeamSelectionEvenWithSavedWorkflows() throws Exception {
        WorkflowTeamStore.save(project, WorkflowWizard.designerWorkersTeam(), true);
        ChatConfig config = new ChatConfig("custom", null, "model", "http://localhost:9000/v1");
        ChatProfiles.save(project, ChatProfiles.capture("local", config), false);
        ChatConfig reused = SetupWizard.selectProjectProfile(
                reader("y", "1", "1"), project, "standard").config();
        assertNotNull(reused);
        SetupWizard.WorkflowSelection skipped = SetupWizard.workflowStep(reader(), project, reused, false);
        assertFalse(skipped.cancelled());
        assertNull(skipped.snapshot());
    }

    /** A saved team activates only when its roles exist, as they do once the template has run. */
    private static void createTemplateRoles(Path project) throws IOException {
        RoleManager roleManager = new RoleManager(project);
        for (WorkflowWizard.RoleDefinition role : WorkflowWizard.designerWorkerRoles()) {
            if (roleManager.getRole(role.name()) == null) {
                roleManager.createRole(role.name(), role.name(), role.description(), "workflow", role.systemPrompt());
            }
        }
    }

    /** Shared template builder so tests don't each hand-build the team. */
    static final class WorkflowWizardTestHelper {
        static WorkflowTeam designerWorkersTemplate() {
            return WorkflowWizard.designerWorkersTeam();
        }
    }

    private static LineReader reader(String... answers) {
        ArrayDeque<String> input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new AssertionError("Unexpected reader call: " + method);
                });
    }
}
