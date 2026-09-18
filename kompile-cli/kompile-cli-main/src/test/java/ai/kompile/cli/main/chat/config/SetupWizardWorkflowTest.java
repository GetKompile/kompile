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
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import ai.kompile.cli.main.chat.workflow.WorkflowWizard;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plain `kompile chat` path MUST surface the workflow question. With no
 * saved workflows the wizard offers templates that create roles and save the
 * team — a user is never asked to hand-edit chat-workflows.json.
 */
class SetupWizardWorkflowTest {

    @TempDir
    Path project;

    @Test
    void emptyStoreStillAsksAndOffersTemplates() {
        // Gated wrapper: "no" skips with null; EOF cancels.
        SetupWizard.WorkflowSelection declined =
                SetupWizard.selectWorkflow(reader("no"), project);
        assertFalse(declined.cancelled());
        assertNull(declined.snapshot());

        // Forced picker (the Workflow chat-mode path): no yes/no gate — the menu
        // IS the question, even with an empty store.
        SetupWizard.WorkflowSelection cancelled =
                SetupWizard.chooseWorkflow(reader(), project);
        assertTrue(cancelled.cancelled());
    }

    @Test
    void designerWorkersTemplateCreatesRolesAndSavesAndActivates() throws Exception {
        // Forced picker (workflow mode): template is option 1 in an empty store.
        SetupWizard.WorkflowSelection selection = SetupWizard.chooseWorkflow(
                reader("1"), project);
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
        SetupWizard.WorkflowSelection selection =
                SetupWizard.chooseWorkflow(reader("1"), project);
        assertNotNull(selection.snapshot());
        assertEquals("designer-workers", selection.snapshot().workflowName());
    }

    @Test
    void gatedWrapperStillHonorsYesNoAndCancel() throws Exception {
        // The opt-in gate survives for non-mode callers (e.g. resume flows).
        SetupWizard.WorkflowSelection skipped =
                SetupWizard.selectWorkflow(reader("no"), project);
        assertFalse(skipped.cancelled());
        assertNull(skipped.snapshot());
        assertTrue(SetupWizard.selectWorkflow(reader(), project).cancelled());
        assertTrue(SetupWizard.selectWorkflow(reader("y", "q"), project).cancelled());
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
