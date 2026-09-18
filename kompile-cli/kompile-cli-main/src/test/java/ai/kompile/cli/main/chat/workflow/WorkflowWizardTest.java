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
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interactive creation must produce a valid, activation-ready workflow without
 * the user ever editing chat-workflows.json by hand.
 */
class WorkflowWizardTest {

    @TempDir
    Path project;

    @Test
    void abandonedCreationWritesNothing() throws Exception {
        // EOF after the first participant ("no more participants" never answered)
        // abandons creation; nothing is saved.
        WorkflowTeam created = WorkflowWizard.create(reader(
                "designer-workers",                     // workflow name
                "designer",                             // participant 1 id
                "1",                                    // role: first sorted existing role
                "1", "done",                            // capabilities: read; finish
                "done",                                 // delegation targets: none yet
                "y",                                    // add another participant
                "worker"                                // participant 2 id — then EOF
        ), project);
        assertNull(created);
        assertFalse(Files.exists(WorkflowTeamStore.path(project)));
    }

    @Test
    void completeTwoParticipantWorkflowIsSavedAndResolvable() throws Exception {
        // Menu order: roles sorted; "1" picks the first sorted existing role.
        // Capabilities menu: 1=read, 2=plan, 3=delegate, 4=edit-assigned-files,
        // 5=validate, 6=chat-only, 7=Done.
        WorkflowTeam created = WorkflowWizard.create(reader(
                "designer-workers",                     // name
                "designer",                             // participant id 1
                "1",                                    // role: first sorted existing role
                "1", "3", "7",                          // capabilities: read + delegate
                "1",                                    // delegation targets: menu is [Done] → Done
                "y",                                    // add another participant
                "worker",                               // participant id 2
                "1",                                    // role: first sorted existing role
                "1", "4", "7",                          // capabilities: read + edit-assigned-files
                "2",                                    // delegation targets: menu is [designer, Done] → Done
                "n",                                    // no more participants
                "1",                                    // lead: designer
                "implement",                            // routing purpose
                "2",                                    // route to worker
                "",                                     // end routing
                "3",                                    // max concurrent workers
                "approved-design",                      // implementation gate
                "",                                     // no completion gate
                ""                                      // save — [Y/n] default yes
        ), project);
        assertNotNull(created, "creation should complete and save");
        assertEquals("designer-workers", created.name());
        assertEquals("designer", created.lead());
        assertEquals(2, created.participants().size());
        assertEquals("worker", created.route("implement").id());
        assertEquals(3, created.maxConcurrentWorkers());
        assertTrue(created.gates().hasImplementationGate());

        // Must be activatable: every referenced role resolves.
        WorkflowTeam stored = WorkflowTeamStore.get(project, "designer-workers");
        assertEquals(created, stored);
        assertDoesNotThrow(() -> WorkflowTeamSnapshot.resolve(stored, new RoleManager(project)));
    }

    @Test
    void inlineRoleCreationRegistersTheRole() throws Exception {
        RoleManager roleManager = new RoleManager(project);
        String role = WorkflowWizard.createRoleInline(reader(
                "sre-oncall-" + System.nanoTime(),
                "Keeps production healthy",
                "Own incidents and rollbacks"
        ), roleManager);
        assertNotNull(role);
        assertTrue(role.startsWith("sre-oncall-"));
        assertNotNull(roleManager.getRole(role));
    }

    @Test
    void capabilityPickerEnforcesChatOnlyExclusivity() {
        // Numbered menu: 1=read … 6=chat-only, 7=Done.
        // read toggled on, then chat-only selected (clears read), then done.
        List<String> capabilities = WorkflowWizard.selectCapabilities(reader(
                "1", "6", "7"), "x");
        assertEquals(List.of("chat-only"), capabilities);

        // chat-only toggled off again, then read on, then done.
        List<String> toggled = WorkflowWizard.selectCapabilities(reader(
                "6", "6", "1", "7"), "x");
        assertEquals(List.of("read"), toggled);
    }

    @Test
    void emptyCapabilitySelectionIsRejected() {
        assertNull(WorkflowWizard.selectCapabilities(reader("7"), "x"));
    }

    @Test
    void delegationTargetsToggleAndFinish() {
        // Deterministic order: pass a LinkedHashSet-backed ordered set.
        java.util.Set<String> existing = new java.util.LinkedHashSet<>(List.of("a", "b"));
        List<String> selected = WorkflowWizard.selectDelegationTargets(reader(
                "1", "2", "1", "3"), "w", existing);
        // toggled a on, b on, a off → [b]
        assertEquals(List.of("b"), selected);
    }

    @Test
    void cancelAtNamePromptWritesNothing() {
        assertNull(WorkflowWizard.create(reader(), project));
        assertNull(WorkflowWizard.create(reader("cancel-ish-name", "designer"), project));
        assertFalse(Files.exists(WorkflowTeamStore.path(project)));
    }

    @Test
    void blankDefaultsFollowBracketHints() {
        // "[Y/n]" → blank means yes; "[y/N]" → blank means no.
        assertTrue(WorkflowWizard.yesNo(reader(""), "Save this workflow? [Y/n]:"));
        assertFalse(WorkflowWizard.yesNo(reader(""), "Add another participant? [y/N]:"));
        assertNull(WorkflowWizard.yesNo(reader("q"), "Anything [y/N]:"));
    }

    @Test
    void duplicateWorkflowReplacementRequiresExplicitYes() throws Exception {
        WorkflowTeam existing = new WorkflowTeam("dup", 1, "p1",
                java.util.Map.of("p1", new WorkflowTeam.Participant("p1", "r1", "cli",
                        List.of("read"), List.of())),
                java.util.Map.of(), null, null);
        assertTrue(WorkflowTeamStore.save(project, existing, true));
        RoleManager roleManager = new RoleManager(project);
        String role = roleManager.getAllRoles().stream().map(r -> r.getName()).sorted().findFirst().orElseThrow();

        // Same name, decline replace → nothing written, returns null.
        WorkflowTeam declined = WorkflowWizard.create(reader(
                "dup",
                "p1", "1", "1", "done", "done", "n",
                "1",
                "",                       // end routing (blank)
                "", "",                   // gates blank
                "n"                       // decline save? No — save prompt is [Y/n]; 'n' declines.
        ), project);
        assertNull(declined);

        // The store still holds the original untouched.
        assertEquals(existing, WorkflowTeamStore.get(project, "dup"));
        assertTrue(Files.exists(WorkflowTeamStore.path(project)));
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
