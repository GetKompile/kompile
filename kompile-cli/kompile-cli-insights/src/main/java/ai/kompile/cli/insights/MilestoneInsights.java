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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.insights;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Test results recorded as milestone JSON, read through {@link TestMilestoneReader}: the run
 * history that the {@code test_milestone} tool keeps in a project, and the latest result per
 * test class that test sweeps keep in the kompile home.
 *
 * <p>A run is told from a class result by its id ({@link TestMilestoneReader#isRunId(String)}),
 * not by the directory it is in, so a chat started in the home directory, where the two stores
 * are one directory, still reads both. Milestones belong to a project rather than to a chat
 * session and are written a few times a day, so a report covers all time unless the question
 * names a range.</p>
 */
public final class MilestoneInsights implements InsightSource {

    public static final String TOPIC = "tests";

    static final String NO_MODULE = "(no module)";
    private static final String REGRESSIONS = "knownRegressions";
    private static final int MAX_NOTE = 100;
    private static final int MAX_DETAIL = 600;
    /** A commit column fits a short hash; a label such as "working-tree" is cut. */
    private static final int MAX_COMMIT = 12;
    /** The newest runs a module's chart draws; older runs in range still count everywhere else. */
    private static final int MAX_CHART_RUNS = 100;
    /** Files the panel tries, newest first, when the newest cannot be read or is incomplete. */
    private static final int PANEL_CANDIDATES = 5;

    private final List<Path> baseDirs;
    private final InsightsConfig config;
    /** The files the previous panel row examined, parsed. Guarded by this. */
    private Map<Path, Parsed> parsed = Map.of();

    /**
     * @param projectBaseDir the project's {@code .kompile/test-milestones}
     * @param globalBaseDir  {@code test-milestones} in the kompile home; read once when it is the
     *                       project's directory
     */
    public MilestoneInsights(Path projectBaseDir, Path globalBaseDir, InsightsConfig config) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path dir : new Path[]{projectBaseDir, globalBaseDir}) {
            if (dir != null) {
                dirs.add(dir.toAbsolutePath().normalize());
            }
        }
        this.baseDirs = List.copyOf(dirs);
        this.config = config;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String description() {
        return "test milestones: pass rate and latest result per module, failing test classes, known regressions";
    }

    @Override
    public List<String> keywords() {
        return List.of("test", "milestone", "pass", "regress", "green", "module", "suite", "junit");
    }

    /** One recorded result: a run of a module, or the latest result of one test class. */
    record Result(String id, boolean run, String module, Instant at, boolean passing, long total, long passed,
                  String commit, String branch, String message, String tags, String notes) {

        /** The share of tests that passed; 1 or 0 by the pass flag when the count is unknown. */
        double score() {
            return total > 0 && passed >= 0 ? Math.min(1.0, (double) passed / total) : (passing ? 1 : 0);
        }

        String result() {
            return passing ? "pass" : "FAIL";
        }

        /** "118/120", or "-" when no test count was recorded. */
        String tests() {
            if (total < 0) {
                return "-";
            }
            return (passed < 0 ? "?" : Format.count(passed)) + "/" + Format.count(total);
        }
    }

    /** A milestone file as listed: its write time orders the panel's candidates. */
    private record Candidate(Path file, long size, long modified) {
    }

    /** A milestone file the panel parsed, reused until its size or write time changes; null when unusable. */
    private record Parsed(long size, long modified, Result result) {
    }

    private static final class Scan {
        final Map<String, Result> runs = new HashMap<>();
        final Map<String, Result> classes = new HashMap<>();
        final List<Map<String, Object>> regressions = new ArrayList<>();
        final List<Path> unreadable = new ArrayList<>();
        int stores;
        int incomplete;
    }

    /** One module's runs, oldest first. */
    private static final class ModuleRuns {
        final String module;
        final List<Result> runs = new ArrayList<>();
        long green;

        ModuleRuns(String module) {
            this.module = module;
        }

        Result latest() {
            return runs.get(runs.size() - 1);
        }
    }

    @Override
    public InsightReport report(InsightsQuery query) throws IOException {
        InsightsWindow window = query.getWindow().explicit() ? query.getWindow()
                : new InsightsWindow(Instant.EPOCH, query.getNow(), "all time", false);
        Scan scan = scan(window, query.getZone());
        Set<String> modules = new LinkedHashSet<>();
        scan.runs.values().forEach(run -> modules.add(run.module()));
        scan.classes.values().forEach(result -> modules.add(result.module()));
        modules.remove(NO_MODULE);
        Set<String> names = new LinkedHashSet<>(modules);
        names.addAll(scan.runs.keySet());
        names.addAll(scan.classes.keySet());
        String subject = query.matchSubject(names);
        if (subject == null) {
            return overview(query, window, scan);
        }
        if (modules.contains(subject)) {
            return module(query, window, scan, subject);
        }
        return detail(query, scan, scan.runs.containsKey(subject) ? scan.runs.get(subject) : scan.classes.get(subject));
    }

    private InsightReport overview(InsightsQuery query, InsightsWindow window, Scan scan) {
        ZoneId zone = query.getZone();
        List<Result> runs = oldestFirst(scan.runs.values());
        Map<String, ModuleRuns> modules = byModule(runs);
        long green = runs.stream().filter(Result::passing).count();
        long failingModules = modules.values().stream().filter(module -> !module.latest().passing()).count();
        List<Result> classes = newestFirst(scan.classes.values());
        long classesPassing = classes.stream().filter(Result::passing).count();

        List<String> parts = new ArrayList<>();
        if (!runs.isEmpty()) {
            parts.add(Format.count(runs.size(), "run") + " of " + Format.count(modules.size(), "module") + ", "
                    + Format.percent(green, runs.size()) + " green");
            parts.add(failingModules == 0 ? "every module passed its latest run"
                    : Format.count(failingModules, "module") + " failing at "
                    + (failingModules == 1 ? "its" : "their") + " latest run");
        }
        if (!classes.isEmpty()) {
            parts.add(Format.count(classes.size(), "test class", "test classes") + ", "
                    + Format.count(classesPassing) + " passing");
        }
        String headline = "Tests, " + window.label() + ": "
                + (parts.isEmpty() ? "no test results recorded" : String.join("; ", parts));

        StringBuilder text = new StringBuilder(headline).append('\n');
        ObjectNode chart = null;
        if (!runs.isEmpty()) {
            TimeBuckets buckets = TimeBuckets.of(window, runs.get(0).at(), config.getSparklineBuckets(), zone);
            long[] passingPer = new long[buckets.size()];
            long[] failingPer = new long[buckets.size()];
            long[] runsPer = new long[buckets.size()];
            for (Result run : runs) {
                int bucket = buckets.indexOf(run.at());
                if (bucket >= 0) {
                    runsPer[bucket]++;
                    passingPer[bucket] += run.passing() ? 1 : 0;
                    failingPer[bucket] += run.passing() ? 0 : 1;
                }
            }
            text.append('\n')
                    .append("runs     ").append(Sparkline.of(runsPer)).append("  ").append(buckets.unit())
                    .append(" from ").append(buckets.labels().get(0))
                    .append(", peak ").append(Format.count(Values.max(runsPer))).append('\n')
                    .append("failing  ").append(Sparkline.of(failingPer))
                    .append("  peak ").append(Format.count(Values.max(failingPer))).append('\n');
            appendModules(text, modules.values(), zone);

            Map<String, double[]> series = new LinkedHashMap<>();
            series.put("passing", Charts.toDoubles(passingPer));
            series.put("failing", Charts.toDoubles(failingPer));
            chart = Charts.bar("Test runs " + buckets.unit() + ", " + window.label(), "runs", buckets.labels(),
                    series);
        }
        appendRegressions(text, scan.regressions, null, zone);
        if (!classes.isEmpty()) {
            appendClassModules(text, classes, zone);
            appendFailingClasses(text, classes, zone);
        }
        return finish(headline, text, chart, query, scan);
    }

    private InsightReport module(InsightsQuery query, InsightsWindow window, Scan scan, String module) {
        ZoneId zone = query.getZone();
        List<Result> runs = oldestFirst(scan.runs.values().stream().filter(run -> module.equals(run.module())).toList());
        List<Result> classes = newestFirst(
                scan.classes.values().stream().filter(result -> module.equals(result.module())).toList());
        long green = runs.stream().filter(Result::passing).count();
        long classesPassing = classes.stream().filter(Result::passing).count();

        List<String> parts = new ArrayList<>();
        if (!runs.isEmpty()) {
            Result latest = runs.get(runs.size() - 1);
            parts.add(Format.count(runs.size(), "run") + ", " + Format.percent(green, runs.size()) + " green");
            parts.add("latest " + latest.result() + " " + Format.dateTime(latest.at(), zone)
                    + (latest.total() < 0 ? "" : " (" + latest.tests() + " tests)"));
        }
        if (!classes.isEmpty()) {
            parts.add(Format.count(classes.size(), "test class", "test classes") + ", "
                    + Format.count(classesPassing) + " passing");
        }
        // The module was named by one of its results, so there is always a part.
        String headline = "Tests for " + module + ", " + window.label() + ": " + String.join("; ", parts);

        StringBuilder text = new StringBuilder(headline).append('\n');
        ObjectNode chart = null;
        if (!runs.isEmpty()) {
            int trendRuns = Math.min(runs.size(), config.getSparklineBuckets());
            text.append('\n').append("pass rate  ").append(trend(runs)).append("  last ")
                    .append(Format.count(trendRuns, "run")).append(", oldest first\n");

            TextTable latest = new TextTable("when", "result", "tests", "commit", "notes")
                    .left(1, 3, 4).maxCell(MAX_NOTE);
            int shown = Math.min(runs.size(), config.getMaxRows());
            for (int i = runs.size() - 1; i >= runs.size() - shown; i--) {
                Result run = runs.get(i);
                latest.row(Format.dateTime(run.at(), zone), run.result(), run.tests(),
                        Format.clamp(run.commit(), MAX_COMMIT), run.notes());
            }
            text.append("\nLatest runs:\n").append(latest.render());
            more(text, runs.size(), shown, "runs");

            List<Result> charted = runs.subList(Math.max(0, runs.size() - MAX_CHART_RUNS), runs.size());
            List<String> labels = new ArrayList<>(charted.size());
            double[] rates = new double[charted.size()];
            for (int i = 0; i < charted.size(); i++) {
                labels.add(Format.dateTime(charted.get(i).at(), zone));
                rates[i] = charted.get(i).score() * 100;
            }
            chart = Charts.line("Pass rate for " + module + ", " + window.label(), "%", labels,
                    Map.of("passed", rates));
        }
        appendRegressions(text, scan.regressions, module, zone);
        if (!classes.isEmpty()) {
            appendFailingClasses(text, classes, zone);
        }
        return finish(headline, text, chart, query, scan);
    }

    private InsightReport detail(InsightsQuery query, Scan scan, Result result) {
        String headline = (result.run() ? "Test run " : "Test class ") + result.id() + " (" + result.module()
                + "), " + Format.dateTime(result.at(), query.getZone()) + ": " + result.result()
                + (result.total() < 0 ? "" : ", " + result.tests() + " tests");
        StringBuilder text = new StringBuilder(headline).append("\n\n");
        field(text, "commit", result.commit() == null ? null
                : result.commit() + (result.branch() == null ? "" : " on " + result.branch()));
        field(text, "message", result.message());
        field(text, "tags", result.tags());
        field(text, "notes", result.notes());
        return finish(headline, text, null, query, scan);
    }

    private void appendModules(StringBuilder text, Collection<ModuleRuns> modules, ZoneId zone) {
        TextTable table = new TextTable("module", "runs", "green", "latest", "tests", "when", "trend")
                .left(3, 6).maxCell(MAX_NOTE);
        modules.stream()
                .sorted(Comparator.<ModuleRuns>comparingInt(module -> module.latest().passing() ? 1 : 0)
                        .thenComparing((ModuleRuns module) -> module.latest().at(), Comparator.reverseOrder())
                        .thenComparing(module -> module.module))
                .limit(config.getMaxRows())
                .forEach(module -> table.row(module.module, Format.count(module.runs.size()),
                        Format.percent(module.green, module.runs.size()), module.latest().result(),
                        module.latest().tests(), Format.dateTime(module.latest().at(), zone), trend(module.runs)));
        text.append('\n').append(table.render());
        more(text, modules.size(), Math.min(modules.size(), config.getMaxRows()), "modules");
    }

    /** Per test-sweep module: how many classes there are and how many passed at their latest result. */
    private void appendClassModules(StringBuilder text, List<Result> classes, ZoneId zone) {
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, Instant> updated = new HashMap<>();
        for (Result result : classes) {
            long[] row = counts.computeIfAbsent(result.module(), module -> new long[4]);
            row[0]++;
            row[1] += result.passing() ? 1 : 0;
            if (result.total() > 0) {
                row[2] += Math.max(0, result.passed());
                row[3] += result.total();
            }
            updated.merge(result.module(), result.at(), (a, b) -> b.isAfter(a) ? b : a);
        }
        TextTable table = new TextTable("module", "classes", "passing", "failing", "tests", "updated")
                .maxCell(MAX_NOTE);
        counts.entrySet().stream().limit(config.getMaxRows()).forEach(entry -> {
            long[] row = entry.getValue();
            table.row(entry.getKey(), Format.count(row[0]), Format.count(row[1]), Format.count(row[0] - row[1]),
                    Format.count(row[2]) + "/" + Format.count(row[3]),
                    Format.dateTime(updated.get(entry.getKey()), zone));
        });
        text.append("\nTest classes, latest result each:\n").append(table.render());
        more(text, counts.size(), Math.min(counts.size(), config.getMaxRows()), "modules");
    }

    private void appendFailingClasses(StringBuilder text, List<Result> classes, ZoneId zone) {
        List<Result> failing = classes.stream().filter(result -> !result.passing()).toList();
        if (failing.isEmpty()) {
            return;
        }
        TextTable table = new TextTable("class", "module", "tests", "when", "notes").left(1, 4).maxCell(MAX_NOTE);
        int shown = Math.min(failing.size(), config.getMaxExamples());
        failing.stream().limit(shown).forEach(result -> table.row(result.id(), result.module(), result.tests(),
                Format.dateTime(result.at(), zone), result.notes()));
        text.append("\nFailing classes, newest first:\n").append(table.render());
        more(text, failing.size(), shown, "failing classes");
    }

    /** The known regressions in each store's config, all of them or one module's. */
    private void appendRegressions(StringBuilder text, List<Map<String, Object>> regressions, String module,
                                   ZoneId zone) {
        List<Map<String, Object>> shown = module == null ? regressions
                : regressions.stream().filter(entry -> module.equals(Values.text(entry.get("module")))).toList();
        if (shown.isEmpty()) {
            return;
        }
        TextTable table = new TextTable("test", "module", "since", "added").left(1, 2).maxCell(MAX_NOTE);
        int rows = Math.min(shown.size(), config.getMaxRows());
        shown.stream().limit(rows).forEach(entry -> table.row(Values.text(entry.get("test")),
                Values.text(entry.get("module")), Values.text(entry.get("sinceCommit")),
                Format.date(Values.instant(Values.text(entry.get("added"))), zone)));
        text.append("\nKnown regressions (").append(Format.count(shown.size())).append("):\n")
                .append(table.render());
        more(text, shown.size(), rows, "regressions");
    }

    /** The pass rate of a module's newest runs, one bar per run, oldest first. */
    private String trend(List<Result> runs) {
        int from = Math.max(0, runs.size() - config.getSparklineBuckets());
        double[] scores = new double[runs.size() - from];
        for (int i = from; i < runs.size(); i++) {
            scores[i - from] = runs.get(i).score();
        }
        return Sparkline.of(scores, 1.0);
    }

    private InsightReport finish(String headline, StringBuilder text, ObjectNode chart, InsightsQuery query,
                                 Scan scan) {
        List<String> notes = notes(query, scan);
        if (!notes.isEmpty()) {
            text.append('\n');
            notes.forEach(note -> text.append(note).append('\n'));
        }
        return InsightReport.builder().topic(TOPIC).headline(headline).text(text.toString()).chart(chart).build();
    }

    /**
     * "Tests: kompile-cli-main 118/120 FAIL · 2h ago" for the most recently written milestone, a
     * run or a test class, with a failure's notes as the detail. Milestones belong to the project,
     * not to a session, so the row shows the latest one whichever session recorded it. Each refresh
     * lists the stores, but parses only a file that is new or changed.
     */
    @Override
    public synchronized Panel.Line panelLine(InsightsQuery query) throws IOException {
        List<Candidate> candidates = newestFiles();
        if (candidates.isEmpty()) {
            return Panel.Line.of("Tests: no milestones recorded");
        }
        Map<Path, Parsed> examined = new HashMap<>();
        Result result = null;
        for (Candidate candidate : candidates) {
            Parsed entry = parsed.get(candidate.file());
            if (entry == null || entry.size() != candidate.size() || entry.modified() != candidate.modified()) {
                Result read;
                try {
                    read = result(TestMilestoneReader.readFile(candidate.file()), query.getZone());
                } catch (IOException | RuntimeException unreadable) {
                    read = null;
                }
                entry = new Parsed(candidate.size(), candidate.modified(), read);
            }
            examined.put(candidate.file(), entry);
            if (entry.result() != null) {
                result = entry.result();
                break;
            }
        }
        parsed = examined;
        if (result == null) {
            return Panel.Line.of("Tests: the newest milestone files cannot be read, such as "
                    + Format.clamp(candidates.get(0).file().getFileName().toString(), 60));
        }
        String name;
        if (result.run()) {
            name = NO_MODULE.equals(result.module()) ? "run " + result.id() : result.module();
        } else {
            String simple = result.id().substring(result.id().lastIndexOf('.') + 1);
            name = simple.isEmpty() ? result.id() : simple;
            name = NO_MODULE.equals(result.module()) ? name : name + " (" + result.module() + ")";
        }
        StringBuilder text = new StringBuilder("Tests: ").append(Format.clamp(name, 60)).append(' ');
        if (result.total() >= 0) {
            text.append(result.tests()).append(' ');
        }
        text.append(result.result()).append(" · ").append(Format.ago(result.at(), query.getNow()));
        String detail = !result.passing() && result.notes() != null && !result.notes().isBlank()
                ? "↳ " + Format.clamp(result.notes(), MAX_NOTE) : null;
        return new Panel.Line(text.toString(), detail, false);
    }

    /** Each store's milestones directory, or the store itself until that directory is created. */
    @Override
    public List<Panel.Watch> panelWatches(InsightsQuery query) {
        List<Panel.Watch> watches = new ArrayList<>();
        for (Path baseDir : baseDirs) {
            Path dir = baseDir.resolve(TestMilestoneReader.MILESTONES_DIR);
            if (Files.isDirectory(dir)) {
                watches.add(new Panel.Watch(dir, null));
            } else if (Files.isDirectory(baseDir)) {
                watches.add(new Panel.Watch(baseDir, TestMilestoneReader.MILESTONES_DIR));
            }
        }
        return watches;
    }

    /** The {@value #PANEL_CANDIDATES} most recently written milestone files across the stores, newest first. */
    private List<Candidate> newestFiles() throws IOException {
        ToolUsageInsights.TopK<Candidate> newest = new ToolUsageInsights.TopK<>(PANEL_CANDIDATES,
                Comparator.comparingLong(Candidate::modified).thenComparing(Candidate::file));
        for (Path baseDir : baseDirs) {
            Path dir = baseDir.resolve(TestMilestoneReader.MILESTONES_DIR);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path file : stream) {
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    } catch (IOException vanished) {
                        continue;
                    }
                    if (attributes.isRegularFile()) {
                        newest.offer(new Candidate(file, attributes.size(), attributes.lastModifiedTime().toMillis()));
                    }
                }
            }
        }
        return newest.descending();
    }

    private List<String> notes(InsightsQuery query, Scan scan) {
        List<String> notes = new ArrayList<>();
        if (query.sessionScoped()) {
            notes.add("Test milestones are recorded per project, not per chat session, so this covers all of them.");
        }
        if (baseDirs.isEmpty()) {
            notes.add("No test-milestone store is configured.");
        } else if (scan.stores == 0) {
            List<String> dirs = baseDirs.stream()
                    .map(dir -> dir.resolve(TestMilestoneReader.MILESTONES_DIR).toString()).toList();
            notes.add("No test milestones in " + String.join(" or ", dirs)
                    + "; the test_milestone tool records them.");
        }
        if (scan.incomplete > 0) {
            notes.add(Format.count(scan.incomplete, "milestone file") + " without an id or a readable timestamp "
                    + (scan.incomplete == 1 ? "is" : "are") + " not counted.");
        }
        if (!scan.unreadable.isEmpty()) {
            notes.add(Format.count(scan.unreadable.size(), "milestone file") + " could not be parsed, such as "
                    + scan.unreadable.get(0) + ".");
        }
        if (config.getWarning() != null) {
            notes.add(config.getWarning());
        }
        return notes;
    }

    private Scan scan(InsightsWindow window, ZoneId zone) throws IOException {
        Scan scan = new Scan();
        for (Path baseDir : baseDirs) {
            Path dir = baseDir.resolve(TestMilestoneReader.MILESTONES_DIR);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            scan.stores++;
            TestMilestoneReader.Contents contents = TestMilestoneReader.readDir(dir);
            scan.unreadable.addAll(contents.unreadable());
            for (Map<String, Object> milestone : contents.milestones()) {
                Result result = result(milestone, zone);
                if (result == null) {
                    scan.incomplete++;
                } else if (window.contains(result.at())) {
                    (result.run() ? scan.runs : scan.classes).merge(result.id(), result, MilestoneInsights::newer);
                }
            }
            scan.regressions.addAll(regressions(baseDir));
        }
        return scan;
    }

    /**
     * A normalized milestone as a result, or null when it has no id or no readable timestamp. A
     * timestamp without an offset is local to {@code zone}. The commit is shortened from the full
     * one: older tool records cut labels such as "working-tree" to seven characters too.
     */
    static Result result(Map<String, Object> milestone, ZoneId zone) {
        String id = Values.text(milestone.get("id"));
        Instant at = Values.instant(Values.text(milestone.get("timestamp")), zone);
        if (id == null || id.isBlank() || at == null) {
            return null;
        }
        String module = Values.text(milestone.get("module"));
        String commit = Values.text(milestone.get("commit"));
        return new Result(id, TestMilestoneReader.isRunId(id), module == null || module.isBlank() ? NO_MODULE : module,
                at, passing(milestone.get("passing")), count(milestone.get("totalTests")),
                count(milestone.get("passed")),
                commit != null ? TestMilestoneReader.shortCommit(commit) : Values.text(milestone.get("commitShort")),
                Values.text(milestone.get("branch")), Values.text(milestone.get("commitMessage")),
                Values.text(milestone.get("tags")), Values.text(milestone.get("notes")));
    }

    private static List<Map<String, Object>> regressions(Path baseDir) {
        Map<String, Object> config = TestMilestoneReader.readConfig(baseDir);
        List<Map<String, Object>> result = new ArrayList<>();
        if (config != null && config.get(REGRESSIONS) instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
        }
        return result;
    }

    private static boolean passing(Object value) {
        return value instanceof Boolean flag ? flag : "true".equalsIgnoreCase(Values.text(value));
    }

    /** A recorded count, or -1 when none was recorded. */
    private static long count(Object value) {
        Long n = Values.number(value);
        return n == null ? -1 : n;
    }

    private static Result newer(Result a, Result b) {
        return b.at().isAfter(a.at()) ? b : a;
    }

    private static Map<String, ModuleRuns> byModule(List<Result> runsOldestFirst) {
        Map<String, ModuleRuns> modules = new HashMap<>();
        for (Result run : runsOldestFirst) {
            ModuleRuns module = modules.computeIfAbsent(run.module(), ModuleRuns::new);
            module.runs.add(run);
            module.green += run.passing() ? 1 : 0;
        }
        return modules;
    }

    private static List<Result> oldestFirst(Collection<Result> results) {
        List<Result> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(Result::at).thenComparing(Result::id));
        return sorted;
    }

    private static List<Result> newestFirst(Collection<Result> results) {
        List<Result> sorted = oldestFirst(results);
        Collections.reverse(sorted);
        return sorted;
    }

    private static void field(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(" ".repeat(Math.max(1, 9 - label.length())))
                    .append(Format.clamp(value, MAX_DETAIL)).append('\n');
        }
    }

    private static void more(StringBuilder text, int total, int shown, String noun) {
        if (total > shown) {
            text.append("(+").append(Format.count(total - shown)).append(" more ").append(noun).append(")\n");
        }
    }
}
