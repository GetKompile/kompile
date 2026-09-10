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

import ai.kompile.cli.main.chat.ToolCallRecord;
import ai.kompile.cli.main.coordination.AgentEntry;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds a bounded, correlated snapshot of active project agents and their work. */
public final class AgentActivitySnapshotService {

    public static final Duration DEFAULT_RECENT_TOOL_WINDOW = Duration.ofMinutes(5);
    public static final int DEFAULT_TOOL_LIMIT_PER_AGENT = 12;
    private static final Duration PROCESS_START_TOLERANCE = Duration.ofSeconds(5);

    private final CoordinationStateManager coordinator;
    private final ToolCallTailReader toolCalls;
    private final Clock clock;
    private final Duration recentToolWindow;

    public AgentActivitySnapshotService(CoordinationStateManager coordinator,
                                        ToolCallTailReader toolCalls) {
        this(coordinator, toolCalls, Clock.systemUTC(), DEFAULT_RECENT_TOOL_WINDOW);
    }

    AgentActivitySnapshotService(CoordinationStateManager coordinator,
                                 ToolCallTailReader toolCalls,
                                 Clock clock,
                                 Duration recentToolWindow) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.toolCalls = Objects.requireNonNull(toolCalls, "toolCalls");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recentToolWindow = Objects.requireNonNull(recentToolWindow, "recentToolWindow");
        if (recentToolWindow.isNegative()) {
            throw new IllegalArgumentException("recentToolWindow must not be negative");
        }
    }

    public AgentActivitySnapshot capture() {
        return capture(DEFAULT_TOOL_LIMIT_PER_AGENT);
    }

    public AgentActivitySnapshot capture(int toolLimitPerAgent) {
        int boundedToolLimit = Math.max(0, Math.min(100, toolLimitPerAgent));
        Instant now = clock.instant();
        Instant cutoff = now.minus(recentToolWindow);
        List<String> warnings = new ArrayList<>();

        List<AgentEntry> registeredAgents = coordinator.queryAgents();
        List<ProcessCoordEntry> processEntries = coordinator.queryProcesses();
        Map<String, List<AgentActivitySnapshot.ProcessActivity>> processesByOwner =
                processesByOwner(processEntries);
        Map<String, ToolCallTailReader.TailResult> toolTails = new HashMap<>();
        List<AgentActivitySnapshot.AgentActivity> activities = new ArrayList<>();

        for (AgentEntry agent : registeredAgents) {
            if (agent == null || blank(agent.getSessionId())) continue;
            String coordinationId = agent.getSessionId();
            String toolSessionId = blank(agent.getToolSessionId())
                    ? coordinationId : agent.getToolSessionId();
            ToolCallTailReader.TailResult tail = readTail(
                    toolSessionId, boundedToolLimit, toolTails, warnings);
            List<AgentActivitySnapshot.ToolActivity> recentTools = recentTools(
                    tail.records(), cutoff, warnings, toolSessionId);
            activities.add(new AgentActivitySnapshot.AgentActivity(
                    coordinationId,
                    toolSessionId,
                    agent.getAgentName(),
                    agent.getRoleName(),
                    agent.getAgentType(),
                    agent.getParentSessionId(),
                    agent.getDepth(),
                    agent.getTask(),
                    agent.getPid(),
                    agent.getStartedAt(),
                    agent.getLastHeartbeat(),
                    true,
                    isSameLiveProcess(agent.getPid(), agent.getStartedAt()),
                    processesByOwner.remove(coordinationId),
                    recentTools,
                    tail.malformedLines()));
        }

        // A process can outlive or lose its owner presence after a hard exit. Preserve
        // it as explicitly orphaned; do not guess a tool-session identity from timing.
        for (Map.Entry<String, List<AgentActivitySnapshot.ProcessActivity>> orphan
                : processesByOwner.entrySet()) {
            List<AgentActivitySnapshot.ProcessActivity> processes = orphan.getValue();
            AgentActivitySnapshot.ProcessActivity representative = processes.isEmpty()
                    ? null : processes.get(0);
            activities.add(new AgentActivitySnapshot.AgentActivity(
                    orphan.getKey(),
                    "",
                    representative == null ? "" : representative.agentName(),
                    representative == null ? "" : representative.roleName(),
                    "orphan",
                    "",
                    0,
                    "Process owner is not registered",
                    0L,
                    earliestStart(processes),
                    null,
                    false,
                    false,
                    processes,
                    List.of(),
                    0));
        }

        activities.sort(Comparator
                .comparing(AgentActivitySnapshot.AgentActivity::orphaned)
                .thenComparing(Comparator.comparing(
                        AgentActivitySnapshot.AgentActivity::confirmed).reversed())
                .thenComparing(AgentActivitySnapshot.AgentActivity::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentActivitySnapshot.AgentActivity::identity));
        return new AgentActivitySnapshot(now, recentToolWindow, activities, warnings);
    }

    private Map<String, List<AgentActivitySnapshot.ProcessActivity>> processesByOwner(
            List<ProcessCoordEntry> entries) {
        Map<String, List<AgentActivitySnapshot.ProcessActivity>> byOwner = new LinkedHashMap<>();
        for (ProcessCoordEntry entry : entries) {
            if (entry == null || blank(entry.getSessionId()) || blank(entry.getProcessId())) continue;
            boolean running = entry.isRunningState();
            boolean pidAlive = running && isSameLiveProcess(entry.getPid(), entry.getStartedAt());
            AgentActivitySnapshot.ProcessActivity process = new AgentActivitySnapshot.ProcessActivity(
                    entry.getSessionId(), entry.getProcessId(), entry.getAgentName(),
                    entry.getRoleName(), entry.getKind(), entry.getCommand(), entry.getDescription(),
                    entry.getPid(), entry.getState(), entry.getStartedAt(), entry.getEndedAt(),
                    entry.getExitCode(), entry.getOutputFile(), entry.getDuration(), running, pidAlive);
            byOwner.computeIfAbsent(entry.getSessionId(), ignored -> new ArrayList<>()).add(process);
        }
        Comparator<AgentActivitySnapshot.ProcessActivity> order = Comparator
                .comparing(AgentActivitySnapshot.ProcessActivity::verifiedRunning).reversed()
                .thenComparing(AgentActivitySnapshot.ProcessActivity::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentActivitySnapshot.ProcessActivity::processId);
        byOwner.values().forEach(processes -> processes.sort(order));
        return byOwner;
    }

    private ToolCallTailReader.TailResult readTail(
            String toolSessionId,
            int limit,
            Map<String, ToolCallTailReader.TailResult> cache,
            List<String> warnings) {
        if (blank(toolSessionId) || limit == 0) {
            return new ToolCallTailReader.TailResult(List.of(), 0, false, 0L);
        }
        ToolCallTailReader.TailResult cached = cache.get(toolSessionId);
        if (cached != null) return cached;
        try {
            ToolCallTailReader.TailResult result = toolCalls.readRecent(toolSessionId, limit);
            cache.put(toolSessionId, result);
            return result;
        } catch (IOException | IllegalArgumentException e) {
            warnings.add("Tool activity unavailable for " + shortId(toolSessionId)
                    + ": " + oneLine(e.getMessage()));
            ToolCallTailReader.TailResult empty =
                    new ToolCallTailReader.TailResult(List.of(), 0, false, 0L);
            cache.put(toolSessionId, empty);
            return empty;
        }
    }

    private static List<AgentActivitySnapshot.ToolActivity> recentTools(
            List<ToolCallRecord> records,
            Instant cutoff,
            List<String> warnings,
            String toolSessionId) {
        if (records == null || records.isEmpty()) return List.of();
        List<AgentActivitySnapshot.ToolActivity> recent = new ArrayList<>();
        for (ToolCallRecord record : records) {
            if (record == null) continue;
            try {
                Instant timestamp = record.getTimestampInstant();
                if (!timestamp.isBefore(cutoff)) {
                    recent.add(new AgentActivitySnapshot.ToolActivity(
                            record.getId(), record.getToolName(), record.getToolInputSummary(),
                            timestamp, record.isError(), record.getDurationMs()));
                }
            } catch (DateTimeParseException | NullPointerException malformedTimestamp) {
                warnings.add(shortId(toolSessionId) + " skipped a tool call with invalid timestamp");
            }
        }
        recent.sort(Comparator.comparing(
                AgentActivitySnapshot.ToolActivity::timestamp).reversed());
        return List.copyOf(recent);
    }

    private static boolean isSameLiveProcess(long pid, Instant registeredAt) {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(handle -> {
                if (registeredAt == null) return true;
                return handle.info().startInstant()
                        .map(start -> !start.isAfter(registeredAt.plus(PROCESS_START_TOLERANCE)))
                        .orElse(true);
            }).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Instant earliestStart(List<AgentActivitySnapshot.ProcessActivity> processes) {
        return processes.stream().map(AgentActivitySnapshot.ProcessActivity::startedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private static String shortId(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        return value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
