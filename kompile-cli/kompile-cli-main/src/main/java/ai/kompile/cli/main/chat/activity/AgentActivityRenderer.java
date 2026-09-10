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

import ai.kompile.utils.AnsiConstants;
import ai.kompile.utils.FormatUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Width-bounded, control-character-safe rendering for the project activity view. */
public final class AgentActivityRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");

    public String title(String filter) {
        String normalized = clean(filter);
        return normalized.isBlank() ? "Project activity" : "Project activity · " + normalized;
    }

    public String compactStatus(AgentActivitySnapshot snapshot) {
        if (snapshot == null || snapshot.capturedAt().equals(Instant.EPOCH)) return "loading";
        List<String> parts = new ArrayList<>();
        parts.add(snapshot.confirmedAgentCount() + " agent"
                + (snapshot.confirmedAgentCount() == 1 ? "" : "s"));
        parts.add(snapshot.runningProcessCount() + " running");
        parts.add(snapshot.recentToolCount() + " recent call"
                + (snapshot.recentToolCount() == 1 ? "" : "s"));
        long issues = snapshot.unreachableAgentCount() + snapshot.orphanOwnerCount()
                + snapshot.unverifiedRunningProcessCount() + snapshot.warnings().size();
        if (issues > 0) parts.add(issues + " issue" + (issues == 1 ? "" : "s"));
        return String.join(" · ", parts);
    }

    public String render(AgentActivitySnapshot snapshot, String filter,
                         String currentSessionId, int terminalWidth) {
        int width = Math.max(24, Math.min(200, terminalWidth > 0 ? terminalWidth : 100));
        if (snapshot == null || snapshot.capturedAt().equals(Instant.EPOCH)) {
            return truncate("  Loading project activity…", width);
        }

        String normalizedFilter = clean(filter).toLowerCase(Locale.ROOT);
        List<AgentActivitySnapshot.AgentActivity> matched = snapshot.agents().stream()
                .filter(agent -> matches(agent, normalizedFilter))
                .toList();
        StringBuilder out = new StringBuilder();
        out.append("  updated ").append(TIME.format(snapshot.capturedAt()))
                .append(" · ").append(snapshot.confirmedAgentCount()).append(" confirmed")
                .append(" · ").append(snapshot.unreachableAgentCount()).append(" unreachable")
                .append(" · ").append(snapshot.runningProcessCount()).append(" running processes")
                .append(" · ").append(snapshot.recentToolCount()).append(" calls / ")
                .append(windowLabel(snapshot.recentToolWindow())).append('\n');

        if (matched.isEmpty()) {
            if (normalizedFilter.isBlank()) {
                out.append("\n  No active or recently orphaned project agents.");
            } else {
                out.append("\n  No project agent matches \"")
                        .append(truncate(clean(filter), Math.max(8, width - 32)))
                        .append("\".");
                appendAvailableAgents(out, snapshot.agents(), width);
            }
        } else {
            boolean orphanHeadingWritten = false;
            for (AgentActivitySnapshot.AgentActivity agent : matched) {
                if (agent.orphaned() && !orphanHeadingWritten) {
                    out.append("\n  UNREGISTERED / ORPHANED ACTIVITY\n");
                    orphanHeadingWritten = true;
                }
                renderAgent(out, agent, snapshot.capturedAt(), currentSessionId, width);
            }
        }

        if (!snapshot.warnings().isEmpty()) {
            out.append("\n  WARNINGS\n");
            snapshot.warnings().stream().limit(5).forEach(warning -> out.append("  ⚠ ")
                    .append(truncate(clean(warning), Math.max(8, width - 4))).append('\n'));
            if (snapshot.warnings().size() > 5) {
                out.append("  … ").append(snapshot.warnings().size() - 5)
                        .append(" more warning(s)\n");
            }
        }

        out.append("\n  /activity close · /activity refresh · PageUp/PageDown scroll");
        return boundLines(out.toString().stripTrailing(), width);
    }

    private void renderAgent(StringBuilder out, AgentActivitySnapshot.AgentActivity agent,
                             Instant capturedAt, String currentSessionId, int width) {
        boolean current = sameSession(agent, currentSessionId);
        String marker = agent.orphaned() ? "⚠" : agent.confirmed() ? "●" : "○";
        String state = agent.orphaned() ? "ORPHAN" : agent.confirmed() ? "ACTIVE" : "UNREACHABLE";
        String identity = shortId(agent.identity());
        String owner = ownerLabel(agent.agentName(), agent.roleName());
        String elapsed = elapsed(agent.startedAt(), capturedAt);
        out.append("\n  ").append(marker).append(' ');
        if (current) out.append("YOU  ");
        out.append(owner).append("  ").append(identity).append("  ")
                .append(state);
        if (!elapsed.isBlank()) out.append(" · ").append(elapsed);
        out.append('\n');
        if (!agent.task().isBlank()) {
            out.append("    ").append(truncate(clean(agent.task()), Math.max(8, width - 4)))
                    .append('\n');
        }
        if (agent.registered() && agent.pid() > 0) {
            out.append("    pid ").append(agent.pid())
                    .append(" · depth ").append(agent.depth());
            if (!agent.agentType().isBlank()) out.append(" · ").append(clean(agent.agentType()));
            out.append('\n');
        }

        for (AgentActivitySnapshot.ProcessActivity process : agent.processes()) {
            String processMarker = process.verifiedRunning() ? "■"
                    : process.running() ? "⚠" : "□";
            String label = firstNonBlank(process.description(), process.command(), "process");
            String declaredState = process.state().isBlank()
                    ? "UNKNOWN" : process.state().toUpperCase(Locale.ROOT);
            String stateLabel = process.running() && !process.pidAlive()
                    ? "UNVERIFIED (declared " + declaredState + ")" : declaredState;
            out.append("    ").append(processMarker).append(' ')
                    .append(shortId(process.ownerSessionId())).append('/')
                    .append(clean(process.processId())).append("  ")
                    .append(stateLabel).append(" · ")
                    .append(FormatUtils.formatDuration(process.duration())).append(" · ")
                    .append(truncate(clean(label), Math.max(8, width - 32))).append('\n');
        }

        for (AgentActivitySnapshot.ToolActivity call : agent.recentTools()) {
            String timestamp = toolTimestamp(call);
            String tool = truncate(clean(call.toolName()), 18);
            String summary = ActivityToolText.summary(call.inputSummary());
            String duration = call.durationMs() > 0 ? " · " + call.durationMs() + "ms" : "";
            out.append("    ").append(call.error() ? "✗" : "✓").append(' ')
                    .append(timestamp).append(' ')
                    .append(String.format("%-18s", tool))
                    .append(truncate(summary, Math.max(8, width - 35)))
                    .append(duration).append('\n');
        }

        if (agent.processes().isEmpty() && agent.recentTools().isEmpty()) {
            out.append("    no running processes or recent tool calls\n");
        }
        if (agent.malformedToolLines() > 0) {
            out.append("    ⚠ ").append(agent.malformedToolLines())
                    .append(" malformed or oversized tool-call line(s) skipped\n");
        }
    }

    private static void appendAvailableAgents(StringBuilder out,
                                              List<AgentActivitySnapshot.AgentActivity> agents,
                                              int width) {
        if (agents.isEmpty()) return;
        out.append("\n  Available: ");
        String available = agents.stream().limit(8)
                .map(agent -> shortId(agent.identity()) + "=" + ownerLabel(
                        agent.agentName(), agent.roleName()))
                .reduce((left, right) -> left + ", " + right).orElse("");
        out.append(truncate(available, Math.max(8, width - 13)));
    }

    private static boolean matches(AgentActivitySnapshot.AgentActivity agent, String filter) {
        if (filter == null || filter.isBlank()) return true;
        return contains(agent.coordinationSessionId(), filter)
                || contains(agent.toolSessionId(), filter)
                || contains(agent.agentName(), filter)
                || contains(agent.roleName(), filter)
                || contains(agent.task(), filter);
    }

    private static boolean sameSession(AgentActivitySnapshot.AgentActivity agent, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return sessionId.equals(agent.coordinationSessionId())
                || sessionId.equals(agent.toolSessionId());
    }

    private static boolean contains(String value, String filter) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter);
    }

    private static String toolTimestamp(AgentActivitySnapshot.ToolActivity call) {
        try {
            return TIME.format(call.timestamp());
        } catch (DateTimeParseException | NullPointerException ignored) {
            return "??:??:??";
        }
    }

    private static String ownerLabel(String agentName, String roleName) {
        String agent = clean(agentName);
        String role = clean(roleName);
        if (agent.isBlank() && role.isBlank()) return "unknown";
        if (agent.isBlank()) return role;
        if (role.isBlank() || role.equalsIgnoreCase(agent)) return agent;
        return agent + "/" + role;
    }

    private static String elapsed(Instant start, Instant end) {
        if (start == null || end == null || end.isBefore(start)) return "";
        return FormatUtils.formatDuration(Duration.between(start, end));
    }

    private static String windowLabel(Duration window) {
        if (window == null) return "recent";
        long minutes = window.toMinutes();
        if (minutes > 0) return minutes + "m";
        return Math.max(0, window.toSeconds()) + "s";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String plain = AnsiConstants.stripAnsi(value).replace('\n', ' ').replace('\r', ' ');
        return CONTROL.matcher(plain).replaceAll("").replaceAll("[ \\t]+", " ").strip();
    }

    private static String shortId(String value) {
        String clean = clean(value);
        return clean.length() <= 8 ? clean : clean.substring(0, 8);
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value;
        if (maxLength <= 0) return "";
        if (text.length() <= maxLength) return text;
        if (maxLength == 1) return "…";
        return text.substring(0, maxLength - 1) + "…";
    }

    private static String boundLines(String value, int width) {
        String[] lines = value.split("\\R", -1);
        StringBuilder bounded = new StringBuilder(value.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) bounded.append('\n');
            bounded.append(truncate(lines[i], width));
        }
        return bounded.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
