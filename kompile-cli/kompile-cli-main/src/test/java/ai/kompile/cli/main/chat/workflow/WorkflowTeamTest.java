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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTeamTest {

    @TempDir
    Path project;

    private static WorkflowTeam designerWorkers() {
        return new WorkflowTeam("designer-workers", 1, "designer",
                Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect",
                                "cli", List.of("read", "plan", "delegate"), List.of("worker")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer",
                                "cli", List.of("read", "edit-assigned-files", "validate"), List.of())),
                Map.of("implement", "worker"),
                new WorkflowTeam.Limits(3),
                new WorkflowTeam.Gates("user-approved-design", null));
    }

    @Test
    void participantIdsAndPurposesAreCaseInsensitive() {
        WorkflowTeam team = designerWorkers();
        assertEquals(team.participant("DESIGNER"), team.participant("designer"));
        assertEquals("worker", team.route("IMPLEMENT").id());
        assertNull(team.route("unknown-purpose"));
    }

    @Test
    void unknownCapabilityAndSelfDelegationAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTeam.Participant(
                "x", "r", "cli", List.of("brainwash"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTeam.Participant(
                "x", "r", "cli", List.of("read"), List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTeam.Participant(
                "x", "r", "cli", List.of("chat-only", "read"), List.of()));
    }

    @Test
    void leadMustBeAParticipantAndRoutingMustNameKnownParticipants() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTeam("w", 1, "ghost",
                Map.of("a", new WorkflowTeam.Participant("a", "r", "cli", List.of("read"), List.of())),
                Map.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTeam("w", 1, "a",
                Map.of("a", new WorkflowTeam.Participant("a", "r", "cli", List.of("read"), List.of())),
                Map.of("do", "ghost"), null, null));
    }

    @Test
    void delegationCyclesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTeam("cycle", 1, "a",
                Map.of(
                        "a", new WorkflowTeam.Participant("a", "ra", "cli",
                                List.of("delegate"), List.of("b")),
                        "b", new WorkflowTeam.Participant("b", "rb", "cli",
                                List.of("delegate"), List.of("a"))),
                Map.of(), null, null));
    }

    @Test
    void storeRoundTripsAndRefusesSilentReplacement() throws IOException {
        WorkflowTeam team = designerWorkers();
        assertTrue(WorkflowTeamStore.save(project, team, false));
        assertFalse(WorkflowTeamStore.save(project, team, false));
        assertTrue(WorkflowTeamStore.save(project, team, true));
        assertEquals(team, WorkflowTeamStore.get(project, "Designer-Workers"));
        assertEquals(1, WorkflowTeamStore.list(project).size());
        assertTrue(WorkflowTeamStore.delete(project, "designer-workers"));
        assertFalse(WorkflowTeamStore.delete(project, "designer-workers"));
        assertNull(WorkflowTeamStore.get(project, "designer-workers"));
    }

    @Test
    void malformedStoreFileFailsLoudly() throws IOException {
        java.nio.file.Files.createDirectories(WorkflowTeamStore.path(project).getParent());
        java.nio.file.Files.writeString(WorkflowTeamStore.path(project), "{ not json");
        assertThrows(IOException.class, () -> WorkflowTeamStore.list(project));
    }
}
