/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable project-wide activity state consumed by terminal renderers. */
public record AgentActivitySnapshot(
        Instant capturedAt,
        Duration recentToolWindow,
        List<AgentActivity> agents,
        List<String> warnings) {

    public AgentActivitySnapshot {
        capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
        recentToolWindow = recentToolWindow == null ? Duration.ZERO : recentToolWindow;
        agents = agents == null ? List.of() : List.copyOf(agents);
        warnings = warnings == null ? List.of() : warnings.stream()
                .filter(Objects::nonNull)
                .map(ActivityToolText::summary)
                .toList();
    }

    public static AgentActivitySnapshot empty(Duration recentToolWindow) {
        return new AgentActivitySnapshot(Instant.EPOCH, recentToolWindow, List.of(), List.of());
    }

    public long confirmedAgentCount() {
        return agents.stream().filter(AgentActivity::confirmed).count();
    }

    public long unreachableAgentCount() {
        return agents.stream().filter(agent -> agent.registered() && !agent.processAlive()).count();
    }

    public long orphanOwnerCount() {
        return agents.stream().filter(AgentActivity::orphaned).count();
    }

    public long runningProcessCount() {
        return agents.stream().flatMap(agent -> agent.processes().stream())
                .filter(ProcessActivity::verifiedRunning).count();
    }

    public long unverifiedRunningProcessCount() {
        return agents.stream().flatMap(agent -> agent.processes().stream())
                .filter(process -> process.running() && !process.pidAlive()).count();
    }

    public int recentToolCount() {
        return agents.stream().mapToInt(agent -> agent.recentTools().size()).sum();
    }

    public boolean hasActivity() {
        return !agents.isEmpty();
    }

    public record AgentActivity(
            String coordinationSessionId,
            String toolSessionId,
            String agentName,
            String roleName,
            String agentType,
            String parentSessionId,
            int depth,
            String task,
            long pid,
            Instant startedAt,
            Instant lastHeartbeat,
            boolean registered,
            boolean processAlive,
            List<ProcessActivity> processes,
            List<ToolActivity> recentTools,
            int malformedToolLines) {

        public AgentActivity {
            coordinationSessionId = ActivityToolText.boundedClean(coordinationSessionId, 200);
            toolSessionId = ActivityToolText.boundedClean(toolSessionId, 200);
            agentName = ActivityToolText.boundedClean(agentName, 128);
            roleName = ActivityToolText.boundedClean(roleName, 128);
            agentType = ActivityToolText.boundedClean(agentType, 64);
            parentSessionId = ActivityToolText.boundedClean(parentSessionId, 200);
            task = ActivityToolText.summary(task);
            processes = processes == null ? List.of() : List.copyOf(processes);
            recentTools = recentTools == null ? List.of() : List.copyOf(recentTools);
            malformedToolLines = Math.max(0, malformedToolLines);
        }

        public boolean confirmed() {
            return registered && processAlive;
        }

        public boolean orphaned() {
            return !registered;
        }

        public String identity() {
            return !coordinationSessionId.isBlank() ? coordinationSessionId : toolSessionId;
        }
    }

    /** Dashboard-safe projection that deliberately excludes raw tool arguments. */
    public record ToolActivity(
            String id,
            String toolName,
            String inputSummary,
            Instant timestamp,
            boolean error,
            long durationMs) {

        public ToolActivity {
            id = ActivityToolText.boundedClean(id, 256);
            toolName = ActivityToolText.boundedClean(toolName, 128);
            inputSummary = ActivityToolText.summary(inputSummary);
            durationMs = Math.max(0L, durationMs);
        }
    }

    public record ProcessActivity(
            String ownerSessionId,
            String processId,
            String agentName,
            String roleName,
            String kind,
            String command,
            String description,
            long pid,
            String state,
            Instant startedAt,
            Instant endedAt,
            Integer exitCode,
            String outputFile,
            Duration duration,
            boolean running,
            boolean pidAlive) {

        public ProcessActivity {
            ownerSessionId = ActivityToolText.boundedClean(ownerSessionId, 200);
            processId = ActivityToolText.boundedClean(processId, 160);
            agentName = ActivityToolText.boundedClean(agentName, 128);
            roleName = ActivityToolText.boundedClean(roleName, 128);
            kind = ActivityToolText.boundedClean(kind, 64);
            command = ActivityToolText.summary(command);
            description = ActivityToolText.summary(description);
            state = ActivityToolText.boundedClean(state, 64);
            outputFile = ActivityToolText.boundedClean(outputFile, 512);
            duration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
        }

        public String key() {
            return ownerSessionId + "/" + processId;
        }

        public boolean verifiedRunning() {
            return running && pidAlive;
        }
    }

}
