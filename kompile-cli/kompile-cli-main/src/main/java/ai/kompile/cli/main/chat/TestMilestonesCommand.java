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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.utils.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only {@code /test-milestones} presenter: recent milestones for one chat session, from both
 * writers of the milestone store — the harness's automatic capture of authorized test runs, and
 * the agent's own {@code test_milestone} records. No model, no server, no store mutation.
 *
 * <p>Session attribution is exactly what the recorder stamped: milestones written inside this
 * chat (directly, or through an MCP call whose producer context bound this session) carry the
 * session id; records written outside any session have no stamp and cannot be attributed, so a
 * session scope excludes them and says so. {@code all} lifts the filter. Sources are classified
 * from stored fields ({@code source=harness} or an {@code executionId} means automatic capture;
 * anything else counts as agent-invoked), and the basis is disclosed in the report.</p>
 */
final class TestMilestonesCommand {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    private static final String STORE_KEY = "_store";
    private static final String PROJECT_STORE = "project";
    private static final String GLOBAL_STORE = "user global";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final String USAGE = "Usage: /test-milestones [count] [all | session:ID] [source:auto|agent] [module:NAME]\n"
            + "  Recent test milestones for this session, from both writers of the store:\n"
            + "  automatic harness capture and the agent's own test_milestone records.\n"
            + "  Defaults: current session, both sources, newest " + DEFAULT_LIMIT + ".";

    /** Immutable parse of the command arguments; {@link #parse} throws with usage on bad input. */
    private record Options(int limit, String sessionId, boolean allSessions, String source, String module) {
        static Options parse(String arguments) {
            int limit = DEFAULT_LIMIT;
            String session = null;
            boolean all = false;
            String source = null;
            String module = null;
            String text = arguments == null ? "" : arguments.trim();
            if (text.isEmpty()) return new Options(limit, session, all, source, module);
            for (String token : text.split("\\s+")) {
                if (token.isEmpty()) continue;
                if (token.matches("[0-9]+")) {
                    if (limit != DEFAULT_LIMIT) throw new IllegalArgumentException("Duplicate count: " + token);
                    int parsed = Integer.parseInt(token);
                    if (parsed < 1 || parsed > MAX_LIMIT) {
                        throw new IllegalArgumentException("count must be between 1 and " + MAX_LIMIT);
                    }
                    limit = parsed;
                    continue;
                }
                switch (token) {
                    case "all" -> {
                        if (all) throw new IllegalArgumentException("Duplicate selector: all");
                        all = true;
                    }
                    case "help", "-h", "--help" -> {
                        throw new IllegalArgumentException(USAGE);
                    }
                    default -> {
                        int colon = token.indexOf(':');
                        if (colon <= 0 || colon == token.length() - 1) {
                            throw new IllegalArgumentException("Unrecognized argument: " + token);
                        }
                        String key = token.substring(0, colon);
                        String value = token.substring(colon + 1);
                        switch (key) {
                            case "session" -> {
                                if (all) throw new IllegalArgumentException("all and session:ID are exclusive");
                                if (session != null) throw new IllegalArgumentException("Duplicate selector: " + token);
                                session = value;
                            }
                            case "source" -> {
                                if (!Set.of("auto", "agent").contains(value)) {
                                    throw new IllegalArgumentException("source must be auto or agent, not '" + value + "'");
                                }
                                if (source != null) throw new IllegalArgumentException("Duplicate selector: " + token);
                                source = value;
                            }
                            case "module" -> {
                                if (module != null) throw new IllegalArgumentException("Duplicate selector: " + token);
                                module = value;
                            }
                            default -> throw new IllegalArgumentException(
                                    "Unknown selector '" + key + ":' (supported: session, source, module)");
                        }
                    }
                }
            }
            return new Options(limit, session, all, source, module);
        }
    }

    private final Path workingDirectory;

    TestMilestonesCommand(Path workingDirectory) {
        this.workingDirectory = workingDirectory == null ? Path.of(".") : workingDirectory;
    }

    /** The full report: scope and coverage first, then bounded rows. Never throws for data problems. */
    String show(String arguments, String currentSessionId) {
        Options options;
        try {
            options = Options.parse(arguments);
        } catch (IllegalArgumentException e) {
            return USAGE + "\n" + (USAGE.equals(e.getMessage()) ? "" : "Error: " + e.getMessage() + "\n");
        }
        String selected = options.allSessions() ? null
                : options.sessionId() != null ? options.sessionId() : currentSessionId;
        if (!options.allSessions() && (selected == null || selected.isBlank())) {
            return "No session id is available to scope /test-milestones; use 'all'.\n" + USAGE + "\n";
        }

        List<Path> unreadable = new ArrayList<>();
        boolean projectStoreMissing;
        boolean globalStoreMissing;
        List<Map<String, Object>> records = new ArrayList<>();
        try {
            Path projectMilestones = TestMilestoneReader.storeDir(workingDirectory)
                    .resolve(TestMilestoneReader.MILESTONES_DIR);
            projectStoreMissing = !storeReadable(projectMilestones);
            collect(records, projectMilestones, PROJECT_STORE, unreadable);
            Path globalMilestones = TestMilestoneReader.storeDir(Path.of(System.getProperty("user.home")))
                    .resolve(TestMilestoneReader.MILESTONES_DIR);
            globalStoreMissing = !storeReadable(globalMilestones);
            collect(records, globalMilestones, GLOBAL_STORE, unreadable);
        } catch (IOException e) {
            return "Test milestones could not be read: " + e.getMessage() + "\n";
        }

        List<Map<String, Object>> matched = new ArrayList<>();
        int unstampedInScope = 0;
        for (Map<String, Object> record : records) {
            String stamped = str(record.get("sessionId"));
            if (selected != null) {
                if (stamped == null) {
                    unstampedInScope++;
                    continue;
                }
                if (!stamped.equals(selected)) continue;
            }
            if (options.source() != null && !options.source().equals(source(record))) continue;
            if (options.module() != null && !options.module().equals(str(record.get("module")))) continue;
            matched.add(record);
        }
        matched.sort(Comparator.comparing((Map<String, Object> m) -> timestampKey(m)).reversed()
                .thenComparing(m -> str(m.get("id"))));
        int shown = Math.min(matched.size(), options.limit());

        StringBuilder text = new StringBuilder();
        text.append("Scope: ").append(selected == null ? "all sessions" : "session " + selected)
                .append(" | stores: ").append(projectStoreMissing && globalStoreMissing
                        ? "none found" : (projectStoreMissing ? "" : PROJECT_STORE)
                        + (!projectStoreMissing && !globalStoreMissing ? " + " : "")
                        + (globalStoreMissing ? "" : GLOBAL_STORE))
                .append(" | sources: auto capture + agent records\n");
        text.append("Coverage: ").append(matched.size()).append(" matched, ").append(shown).append(" shown (limit ")
                .append(options.limit()).append(")");
        if (selected != null && unstampedInScope > 0) {
            text.append("; ").append(unstampedInScope)
                    .append(" record(s) without a session stamp excluded (cannot be attributed; use 'all')");
        }
        int globalShown = 0;
        for (int i = 0; i < shown; i++) {
            if (GLOBAL_STORE.equals(str(matched.get(i).get(STORE_KEY)))) globalShown++;
        }
        if (globalShown > 0) text.append("; ").append(globalShown).append(" shown from the user-global store");
        if (!unreadable.isEmpty()) {
            text.append("; unreadable milestone files skipped: ").append(unreadable.size());
            for (int i = 0; i < Math.min(3, unreadable.size()); i++) {
                text.append(i == 0 ? " (" : ", ").append(unreadable.get(i).getFileName());
            }
            text.append(unreadable.size() > 3 ? ", …)" : ")");
        }
        text.append("\n");

        if (matched.isEmpty()) {
            text.append("No milestones match this scope.");
            if (projectStoreMissing && globalStoreMissing) {
                text.append(" A store appears after automatic capture observes an authorized test run, "
                        + "or the agent records one with the test_milestone tool.");
            }
            return text.append("\n").toString();
        }

        text.append(String.format("%-10s %-6s %-10s %-16s %-12s %s\n",
                "ID", "SRC", "STATUS", "MODULE", "TOTALS", "WHEN"));
        for (int i = 0; i < shown; i++) {
            Map<String, Object> m = matched.get(i);
            // Plain cells: escape sequences would break the fixed-width table for pipe readers.
            text.append(String.format("%-10s %-6s %-10s %-16s %-12s %s\n",
                    StringUtils.truncate(str(m.get("id")), 10),
                    source(m),
                    status(m),
                    StringUtils.truncate(str(m.get("module")), 16),
                    totals(m),
                    when(m)));
        }
        text.append("Legend: auto = harness-observed test run (source/executionId stamped); "
                + "agent = agent-recorded test_milestone entry.\n");
        if (matched.size() > shown) {
            text.append("Total: ").append(shown).append(" of ").append(matched.size())
                    .append(" matched milestones shown; raise the count to see more.\n");
        } else {
            text.append("Total: ").append(matched.size()).append(" milestone(s).\n");
        }
        return text.toString();
    }

    /** auto when the harness stamped it, agent otherwise — the report discloses this basis. */
    static String source(Map<String, Object> milestone) {
        if ("harness".equals(milestone.get("source")) || milestone.get("executionId") != null) return "auto";
        return "agent";
    }

    private static void collect(List<Map<String, Object>> into, Path dir, String store, List<Path> unreadable)
            throws IOException {
        TestMilestoneReader.Contents contents = TestMilestoneReader.readDir(dir);
        unreadable.addAll(contents.unreadable());
        for (Map<String, Object> milestone : contents.milestones()) {
            Map<String, Object> tagged = new LinkedHashMap<>(milestone);
            tagged.put(STORE_KEY, store);
            into.add(tagged);
        }
    }

    private static boolean storeReadable(Path dir) {
        return java.nio.file.Files.isDirectory(dir);
    }

    /** PASS/FAIL when the record is conclusive; otherwise the stored outcome verbatim, uppercased. */
    static String status(Map<String, Object> milestone) {
        if (!TestMilestoneReader.isConclusive(milestone)) {
            return TestMilestoneReader.outcome(milestone).toUpperCase(Locale.ROOT);
        }
        Object passing = milestone.get("passing");
        if (passing == null) return TestMilestoneReader.outcome(milestone).toUpperCase(Locale.ROOT);
        return Boolean.TRUE.equals(passing) ? "PASS" : "FAIL";
    }

    /** "12/12 f0" for test-count records, the observed counts for build-level ones, "—" when none. */
    static String totals(Map<String, Object> milestone) {
        Integer total = TestMilestoneReader.count(milestone, "totalTests");
        Integer passed = TestMilestoneReader.count(milestone, "passed");
        Integer failed = TestMilestoneReader.count(milestone, "failed");
        Integer skipped = TestMilestoneReader.count(milestone, "skipped");
        if (total == null && milestone.get("observations") instanceof Map<?, ?> observations) {
            @SuppressWarnings("unchecked") Map<String, Object> observed = (Map<String, Object>) observations;
            total = TestMilestoneReader.count(observed, "total");
            passed = TestMilestoneReader.count(observed, "passed");
            failed = TestMilestoneReader.count(observed, "failed");
            skipped = TestMilestoneReader.count(observed, "skipped");
        }
        if (total == null) return "—";
        StringBuilder text = new StringBuilder().append(total).append('/').append(passed == null ? "?" : passed);
        if (failed != null) text.append(" f").append(failed);
        if (skipped != null && skipped > 0) text.append(" s").append(skipped);
        return text.toString();
    }

    private static String timestampKey(Map<String, Object> milestone) {
        String stamp = str(milestone.get("timestamp"));
        // Records predate timestamps only if hand-written; empty sorts oldest, never null.
        return stamp != null ? stamp : String.valueOf(milestone.get("startedAt"));
    }

    private static String when(Map<String, Object> milestone) {
        String stamp = timestampKey(milestone);
        if (stamp == null) return "—";
        try {
            return WHEN.format(Instant.parse(stamp).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException unparsable) {
            return StringUtils.truncate(stamp, 16);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
