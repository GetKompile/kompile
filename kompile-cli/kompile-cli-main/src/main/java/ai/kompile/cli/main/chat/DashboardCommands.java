package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small, model-free CLI grammar and bounded report presenter. No source reads. */
public final class DashboardCommands {
    private DashboardCommands() { }

    public static final List<String> TOPICS = List.of("summary", "tools", "judge", "tests", "crawl", "graph",
            "tokens", "tasks", "processes", "timeline");
    private static final Set<String> CONTROLS = Set.of("refresh", "reload", "show", "on", "hide", "off", "status");
    private static final int MAX_LINES = 100;
    private static final int MAX_CHARS = 16_000;
    public static final String DISCOVERY = "Reports: /dashboard-summary /dashboard-tools /dashboard-judge /dashboard-tests\n"
            + "         /dashboard-crawl /dashboard-graph /dashboard-tokens /dashboard-tasks /dashboard-processes\n"
            + "Timeline: /timeline [session:ID run:ID kind:TYPE] | next | inspect:EVENT_ID | follow | stop (alias: /dashboard-timeline)\n"
            + "Controls: /dashboard refresh|show|hide|status (aliases: reload|on|off)\n"
            + "Filters: session:ID tool:NAME call:ID offset:N last 2 hours; offset is tool-token rows only.";

    public record Request(String topic, String control, String question, String sessionId) {
        public ObjectNode insightsArguments(ObjectMapper mapper) {
            ObjectNode args = mapper.createObjectNode();
            args.put("topic", topic.equals("summary") ? "overview" : topic.equals("tokens") ? "tools" : topic);
            args.put("question", question);
            return args;
        }
        public String scope() {
            return switch (topic) {
                case "tests" -> "tests: project + global milestones (not session-filtered)";
                case "crawl" -> "crawl: current project / configured backend (not session-filtered)";
                case "graph" -> "graph: current state, current project / configured backend (not historical/session-filtered)";
                case "summary" -> "tools/judge: " + sessionScope() + "; tests: project + global; crawl: project/backend; graph: current state";
                case "processes" -> "processes: current chat and shared processes, as provided by /processes";
                default -> topic + ": " + sessionScope();
            };
        }
        private String sessionScope() {
            return sessionId != null ? "session " + sessionId : topic.equals("tasks")
                    ? "current transcript session" : "current session (and parent where available)";
        }
    }

    public static Request parse(String command, String arguments) {
        String name = command == null ? "" : command.toLowerCase(Locale.ROOT);
        String rest = arguments == null ? "" : arguments.trim();
        if (rest.length() > 4096) throw new IllegalArgumentException("Dashboard arguments exceed 4096 characters; narrow the query.");
        if (name.equals("/dashboard") && CONTROLS.contains(rest.toLowerCase(Locale.ROOT))) {
            return new Request(null, rest.toLowerCase(Locale.ROOT), "", null);
        }
        String topic = name.equals("/dashboard") ? "summary" : name.replaceFirst("^/dashboard-", "");
        if (!TOPICS.contains(topic)) throw new IllegalArgumentException("Unknown dashboard topic: " + topic);
        Map<String, String> selectors = new LinkedHashMap<>();
        for (String word : rest.split("\\s+")) {
            int colon = word.indexOf(':');
            if (colon < 0) continue;
            String key = word.substring(0, colon);
            String value = word.substring(colon + 1);
            if (!Set.of("session", "tool", "call", "offset").contains(key) || value.isEmpty()) {
                throw new IllegalArgumentException("Unknown or empty dashboard selector: " + word);
            }
            String previous = selectors.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException("Conflicting " + key + " selectors; provide one exact value.");
            }
            if (!key.equals("session") && !Set.of("tools", "tokens").contains(topic)) {
                throw new IllegalArgumentException(key + ": is supported only by /dashboard-tools or /dashboard-tokens.");
            }
            if (key.equals("offset")) {
                try {
                    if (!value.matches("[0-9]+")) throw new NumberFormatException();
                    if (Integer.parseInt(value) < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("offset:N must be a nonnegative integer (tool-token rows only).");
                }
            }
        }
        if (selectors.containsKey("session") && Set.of("tests", "crawl", "graph", "processes").contains(topic)) {
            throw new IllegalArgumentException(topic + " does not support session: filtering; " + new Request(topic, null, "", null).scope());
        }
        if (Set.of("tasks", "processes").contains(topic) && !rest.isEmpty()
                && !(topic.equals("tasks") && rest.equals("session:" + selectors.get("session")))) {
            throw new IllegalArgumentException(topic + " supports " + (topic.equals("tasks") ? "only session:ID" : "no selectors or time windows") + ".");
        }
        String question = rest;
        if (Set.of("summary", "tools", "tokens", "judge").contains(topic) && !selectors.containsKey("session")) {
            if (rest.toLowerCase(Locale.ROOT).contains("all sessions")) {
                throw new IllegalArgumentException("Use session:ID for an explicit session; dashboard reports default to the current session.");
            }
            question = "this session " + question;
        }
        if (topic.equals("tokens") || selectors.containsKey("tool") || selectors.containsKey("call") || selectors.containsKey("offset")) {
            question = "tokens " + question;
        }
        return new Request(topic, null, question.trim(), selectors.get("session"));
    }

    /** Put scope/coverage before detail, and explicitly disclose every presenter cut. */
    public static String present(Request request, String output) {
        List<String> lines = List.of((output == null ? "No report returned." : output).split("\\R", -1));
        StringBuilder text = new StringBuilder("Scope: ").append(clean(request.scope())).append('\n');
        List<String> coverage = lines.stream().filter(DashboardCommands::isCoverage).toList();
        if (coverage.isEmpty()) text.append("Coverage: source-reported snapshot only; missing data is not extrapolated.\n");
        java.util.ArrayList<String> ordered = new java.util.ArrayList<>(coverage);
        lines.stream().filter(line -> !isCoverage(line)).forEach(ordered::add);
        int shown = 0;
        boolean cut = false;
        for (String line : ordered) {
            if (shown >= MAX_LINES || text.length() + line.length() + 1 > MAX_CHARS) { cut = true; break; }
            // Strip terminal controls, preserving printable text and tabs used by source tables.
            text.append(clean(line)).append('\n');
            shown++;
        }
        if (cut) text.append("[Dashboard display bounded: detail omitted; refine filters/time window")
                .append(Set.of("tools", "tokens").contains(request.topic()) ? " or use offset:N for tool-token rows" : "")
                .append(". Coverage above describes the source scan, not just displayed rows.]\n");
        return text.toString();
    }

    private static String clean(String text) {
        return text.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cc}&&[^\\t]]", "");
    }

    private static boolean isCoverage(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("coverage:") || lower.startsWith("totals include") || lower.startsWith("truncated journal:")
                || lower.startsWith("token coverage") || lower.startsWith("rows are limited")
                || lower.startsWith("no tool usage journal") || lower.startsWith("warning:")
                || lower.startsWith("read the ") || lower.contains("older records are not counted")
                || lower.contains("could not be read") || lower.startsWith("no judge log")
                || lower.startsWith("no tool-call index") || lower.startsWith("no tool calls are indexed")
                || lower.startsWith("recorded by:") || lower.contains("insights.json");
    }
}
