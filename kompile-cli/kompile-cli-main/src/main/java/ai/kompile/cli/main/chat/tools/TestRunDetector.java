package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runner intent plus execution evidence; successful compilation is never test evidence. */
final class TestRunDetector {
    record Plan(Path directory, Set<String> frameworks, boolean explicit, boolean suppressed) {}
    record Result(String framework, String outcome, Long total, Long passed, Long failed, Long errors,
                  Long skipped, String unit, String basis) {
        Boolean passing() {
            if ("passed".equals(outcome)) return Boolean.TRUE;
            if ("failed".equals(outcome)) return Boolean.FALSE;
            return null;
        }
        Result withOutcome(String value) { return new Result(framework, value, total, passed, failed, errors, skipped, unit, basis); }
    }
    private static final int MAX_SUITES = 4096;
    private static final int MAX_CASES = 65536;
    private static final Set<String> NON_EXECUTION = Set.of("echo", "printf", "cat", "head", "tail", "less", "more",
            "grep", "rg", "ag", "sed", "awk", "read", "ls", "find", "git", "test", "[", "true", "false", "tee",
            "claude", "codex", "gemini", "qwen", "opencode", "pi", "kompile");
    private static final Set<String> BUILD_ONLY = Set.of("javac", "kotlinc", "scalac", "gcc", "g++", "cc", "c++", "clang",
            "clang++", "nvcc", "ninja", "msbuild", "tsc", "docker", "podman");
    private static final Pattern ANSI = Pattern.compile("\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07]*(?:\\x07|\\x1B\\\\))");
    private static final Pattern SUREFIRE = p("^Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+).*(?:-- in (.+))?$");
    private static final Pattern RUST = p("^test result: (ok|FAILED)\\. (\\d+) passed; (\\d+) failed; (\\d+) ignored;.*$");
    private static final Pattern CTEST = p("^\\d+% tests passed, (\\d+) tests failed out of (\\d+).*$");
    private static final Pattern MINI = p("^(\\d+) runs, \\d+ assertions, (\\d+) failures, (\\d+) errors, (\\d+) skips$");
    private static final Pattern RSPEC = p("^(\\d+) examples?, (\\d+) failures?(?:, (\\d+) pending)?.*$");
    private static final Pattern LUA = p("^(\\d+) successes? / (\\d+) failures? / (\\d+) errors? / (\\d+) pending.*$");
    private static final Pattern UNITTEST = p("^Ran (\\d+) tests? in .*$");
    private static final Pattern XCTEST = p("^Executed (\\d+) tests?, with (\\d+) failures?.*$");
    private static final Pattern SWIFT = p("^.*Test run with (\\d+) tests?.* (passed|failed).*$");
    private static final Pattern GTEST = p("^\\[==========] (\\d+) tests? from .* ran\\..*$");
    private static final Pattern GTEST_COUNT = p("^\\[\\s*(PASSED|FAILED|SKIPPED)\\s*] (\\d+) tests?\\..*$");
    private static final Pattern VADER = p("^Success/Total: (\\d+)/(\\d+)(?: .*)?$");
    private static final Pattern CATCH = p("^test cases: (\\d+) \\| (.*)$");
    private static final Pattern CATCH_OK = p("^All tests passed \\(\\d+ assertions? in (\\d+) test cases?\\).*$");
    private static final Pattern GENERIC = p("^(\\d+) tests? (?:run|executed): (\\d+) passed, (\\d+) failed(?:, (\\d+) skipped)?$");
    private static final Pattern TAP_RESULT = p("^(not ok|ok)(?: ([0-9]+))?(?:[ .].*)?$");
    private static final Pattern TAP_PLAN = p("^1\\.\\.(\\d+)(?:\\s*#.*)?$");
    private static final Pattern NUMBER_LABEL = p("(\\d+) (passed|failed|skipped|errors?|xfailed|xpassed|deselected|todo|pending|ignored)");

    static Plan plan(String command, Path directory, TestCaptureConfig config) {
        if (command == null || command.length() > 16384 || !config.enabled || config.disabledFrameworks.contains("*")) return null;
        if (config.excludes.stream().anyMatch(p -> p.matcher(command).find())) return null;
        if (!config.includes.isEmpty() && config.includes.stream().noneMatch(p -> p.matcher(command).find())) return null;
        // Substitution/detachment defeats attribution to a completed shell invocation. Fail closed.
        if (command.contains("$(") || command.contains("`") || command.contains("<(") || command.contains(">(")) return null;
        Path cwd = directory.toAbsolutePath().normalize();
        Set<String> frameworks = new LinkedHashSet<>();
        boolean explicit = false, suppressed = false, generic = false, replayedOutput = false;
        for (List<String> original : segments(command)) {
            List<String> t = new ArrayList<>(original);
            while (!t.isEmpty() && (t.get(0).matches("[A-Za-z_][A-Za-z0-9_]*=.*")
                    || Set.of("env", "command", "exec", "sudo", "stdbuf", "timeout", "nice").contains(base(t.get(0))))) {
                String wrapper = base(t.remove(0));
                if (wrapper.equals("timeout") && !t.isEmpty() && t.get(0).matches("[0-9.]+[smhd]?")) t.remove(0);
                while (!t.isEmpty() && t.get(0).startsWith("-")) t.remove(0);
            }
            if (t.isEmpty()) continue;
            String exe = base(t.get(0));
            if (exe.equals("cd")) {
                if (t.size() != 2 || t.get(1).contains("$") || t.get(1).contains("~") || t.get(1).equals("-")) return null;
                cwd = cwd.resolve(t.get(1)).normalize();
                continue;
            }
            if (Set.of("cat", "head", "tail", "less", "more", "grep", "rg", "sed", "awk", "echo", "printf", "git").contains(exe)) replayedOutput = true;
            if (NON_EXECUTION.contains(exe) && !(exe.equals("test") && t.get(0).contains("/")) || BUILD_ONLY.contains(exe)) continue;
            if (Set.of("sh", "bash", "zsh", "fish").contains(exe) && t.size() >= 3 && t.get(1).matches("-[A-Za-z]*c[A-Za-z]*")) {
                Plan nested = plan(t.get(2), cwd, config);
                if (nested != null) { frameworks.addAll(nested.frameworks()); explicit |= nested.explicit(); cwd = nested.directory(); }
                continue;
            }
            String args = String.join(" ", t.subList(1, t.size()));
            String f = null;
            if (Set.of("mvn", "mvnw", "gradle", "gradlew", "sbt").contains(exe)) {
                if (has(args, "(?:test|verify|install|package|build|check|integration-test|surefire:test|failsafe:integration-test|[A-Za-z0-9_:-]*Test)")) f = "jvm";
            } else if (exe.equals("java") && args.matches(".*(?:junit-platform-console|org\\.junit\\.platform\\.console\\.ConsoleLauncher|org\\.testng\\.TestNG|org\\.scalatest\\.tools\\.Runner).*")) f = "jvm";
            else if (exe.equals("ctest") || exe.equals("cmake") && has(args, "--build") && has(args, "test")) f = "ctest";
            else if (exe.equals("cargo") && has(args, "test")) f = "rust";
            else if (exe.equals("go") && has(args, "test")) f = "go";
            else if (exe.startsWith("pytest") || (exe.matches("python[0-9.]*") && args.matches(".*(?:^| )-m pytest(?: .*|$)"))) f = "pytest";
            else if (exe.matches("python[0-9.]*") && args.matches(".*(?:^| )-m (?:unittest|doctest)(?: .*|$)")) f = "unittest";
            else if (exe.equals("sage") && has(args, "-t|-tp|--test")) f = "sage";
            else if (exe.equals("dotnet") && has(args, "test")) f = "dotnet";
            else if (exe.equals("swift") && has(args, "test") || exe.equals("xcodebuild") && has(args, "test|test-without-building")) f = "swift";
            else if (Set.of("dart", "flutter").contains(exe) && has(args, "test")) f = "dart";
            else if (Set.of("pio", "platformio").contains(exe) && has(args, "test")) f = "platformio";
            else if (Set.of("jest", "vitest", "mocha").contains(exe)) f = exe;
            else if (exe.equals("playwright") && has(args, "test")) f = "playwright";
            else if (has(args, "--gtest_[A-Za-z_]+(?:=.*)?")) f = "gtest";
            else if (exe.equals("node") && has(args, "--test(?:=.*)?")) f = "node";
            else if (Set.of("npx", "npm", "pnpm", "yarn", "bun").contains(exe)) {
                for (String runner : List.of("jest", "vitest", "mocha", "playwright")) if (has(args, runner)) f = runner;
                if (f == null && has(args, "test|test:[A-Za-z0-9_-]+")) f = "javascript";
            } else if (exe.equals("rspec") || exe.equals("bundle") && has(args, "rspec")) f = "rspec";
            else if (exe.equals("rake") && has(args, "test|spec")) f = "ruby";
            else if (exe.equals("prove")) f = "perl";
            else if (exe.equals("pg_prove")) f = "pgtap";
            else if (exe.equals("bats") || exe.equals("shellspec") || exe.equals("shunit2")) f = exe;
            else if (exe.equals("busted")) f = "lua";
            else if (Set.of("pwsh", "powershell").contains(exe) && args.toLowerCase(Locale.ROOT).contains("invoke-pester")) f = "pester";
            else if (Set.of("R", "Rscript").contains(exe) && args.contains("testthat::test")) f = "testthat";
            else if (Set.of("vim", "nvim").contains(exe) && args.contains("Vader")) f = "vader";
            else if (exe.equals("make") && has(args, "test|check")) f = "generic";
            if (f != null) {
                if (!config.allows(f)) continue;
                boolean skip = has(args, "--no-run|--collect-only|--co|--show-only(?:=.*)?|--list-tests|--listTests|--list-reporters|--gtest_list_tests|--list|--dry-run|--test-dry-run|--without-testing")
                        || f.equals("ctest") && has(args, "-N") || f.equals("bats") && has(args, "-c|--count")
                        || args.matches(".*(?:^| )-D(?:skipTests|maven\\.test\\.skip)(?:=true)?(?: |$).*")
                        || has(args, "--help|-h|--version|--showConfig|--clearCache|--merge-reports(?:=.*)?")
                        || f.equals("rust") && has(args, "-- --list")
                        || f.equals("go") && has(args, "-c|-list(?:=.*)?")
                        || f.equals("swift") && has(args, "list")
                        || f.equals("go") && args.contains("-bench") && args.contains("-run=^$");
                suppressed |= skip;
                if (skip) continue;
                explicit = true;
                frameworks.add(f);
                // Directory-changing runner flags, never interpreted as arbitrary shell syntax.
                for (String flag : List.of("-f", "--file", "--manifest-path", "--test-dir", "--project-dir", "-C")) {
                    int i = t.indexOf(flag);
                    if (i >= 0 && i + 1 < t.size() && (flag.equals("--test-dir") || flag.equals("--project-dir")
                            || flag.equals("-C") || f.equals("jvm") && (flag.equals("-f") || flag.equals("--file"))
                            || f.equals("rust") && flag.equals("--manifest-path"))) {
                        Path target = cwd.resolve(t.get(i + 1)).normalize();
                        cwd = flag.equals("--manifest-path") || target.toString().endsWith(".xml") ? target.getParent() : target;
                    }
                }
            } else if (!Set.of("mvn", "mvnw", "gradle", "gradlew", "cargo", "go", "dotnet", "swift", "xcodebuild", "dart", "flutter", "pio", "platformio", "make", "cmake", "npm", "npx", "pnpm", "yarn", "bun").contains(exe)
                    && (t.get(0).contains("/") || exe.matches("python[0-9.]*|ruby|perl|sh|bash|zsh|fish|Rscript|lua|pwsh|powershell")
                    || exe.endsWith("test") || exe.endsWith("tests") || exe.endsWith(".sh") || exe.endsWith(".bat"))) generic = true;
        }
        for (TestCaptureConfig.Rule rule : config.rules) if (config.allows("custom") && rule.command().matcher(command).find()) {
            explicit = true;
            frameworks.add("custom");
        }
        if (replayedOutput) return null; // Cannot distinguish runner output from printed/replayed old summaries.
        if (suppressed && frameworks.isEmpty()) return null;
        if (frameworks.isEmpty() && !generic) return null;
        if (frameworks.isEmpty() && !config.allows("generic")) return null;
        if (frameworks.isEmpty()) frameworks.add("generic");
        return new Plan(cwd, Collections.unmodifiableSet(new LinkedHashSet<>(frameworks)), explicit, false);
    }

    /** Small quote-aware tokenizer: operators split invocations; quoted prose never becomes a command. */
    static List<List<String>> segments(String command) {
        List<List<String>> result = new ArrayList<>();
        List<String> segment = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) { token.append(c); escaped = false; continue; }
            if (c == '\\' && quote != '\'') { escaped = true; continue; }
            if (quote != 0) { if (c == quote) quote = 0; else token.append(c); continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (Character.isWhitespace(c) || "|;&<>".indexOf(c) >= 0) {
                if (!token.isEmpty()) { segment.add(token.toString()); token.setLength(0); }
                if ("|;&\n".indexOf(c) >= 0 && !segment.isEmpty()) { result.add(List.copyOf(segment)); segment.clear(); }
            } else token.append(c);
        }
        if (quote != 0 || escaped) return List.of();
        if (!token.isEmpty()) segment.add(token.toString());
        if (!segment.isEmpty()) result.add(List.copyOf(segment));
        return result;
    }
    private static String base(String value) { int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')); return value.substring(slash + 1).replaceFirst("\\.(cmd|exe)$", ""); }
    private static boolean has(String args, String expression) { return Pattern.compile("(?:^| )(?:" + expression + ")(?: |$)").matcher(args).find(); }
    private static Pattern p(String regex) { return Pattern.compile(regex); }

    private final TestCaptureConfig config;
    private final Plan plan;
    private final String command;
    private final Map<String, Result> suites = new LinkedHashMap<>();
    private final Map<String, Result> summaries = new LinkedHashMap<>();
    private boolean noTests, cached, setupError, started, complete, overflow;
    private final Set<String> ctestSkipped = new HashSet<>();
    private final Set<String> cachedPackages = new HashSet<>();
    private long unittestTotal = -1, googleTotal = -1, googlePassed, googleFailed, googleSkipped;
    private long mochaPassed = -1, mochaFailed, sageTotal;
    private long harnessTotal = -1, nodeTotal = -1, nodePassed, nodeFailed, nodeSkipped, nodeCancelled, nodeTodo;
    private long junitPassed = -1, junitFailed = -1, junitSkipped, junitAborted;
    private long playwrightPassed, playwrightFailed, playwrightSkipped;
    private boolean playwrightComplete, nodeComplete;
    private long dartPassed, dartFailed, dartSkipped;
    private long tapPlan = -1, tapSeen, tapFailed, tapSkipped;
    private boolean tapInvalid, tapBailout;
    private final Map<String, String> jsonTests = new LinkedHashMap<>();

    TestRunDetector(TestCaptureConfig config, Plan plan, String command) { this.config = config; this.plan = plan; this.command = command; }

    synchronized void accept(String raw) {
        if (raw == null || plan.suppressed()) return;
        // Bound every regex input; split CR progress lines as well as ordinary lines.
        for (String part : raw.split("[\\r\\n]")) {
            if (part.length() > 8192) continue;
            String text = ANSI.matcher(part).replaceAll("");
            boolean topLevel = !text.startsWith(" ") && !text.startsWith("\t");
            String line = text.strip().replaceFirst("(?i)^\\[(?:INFO|ERROR|WARNING)]\\s*", "");
            parse(line, topLevel);
        }
    }

    private boolean allowed(String f) {
        if (!config.allows(f)) return false;
        Set<String> fs = plan.frameworks();
        return fs.contains(f) || fs.contains("generic") || fs.contains("custom")
                || fs.contains("javascript") && Set.of("jest", "vitest", "mocha", "node", "playwright").contains(f)
                || fs.contains("ruby") && Set.of("minitest", "rspec").contains(f)
                || fs.contains("platformio") && Set.of("unity", "gtest", "catch2").contains(f)
                || fs.contains("ctest") && Set.of("gtest", "catch2").contains(f)
                || fs.contains("swift") && f.equals("xctest")
                || fs.contains("jvm") && Set.of("jvm", "scalatest").contains(f)
                || fs.contains("shellspec") && f.equals("rspec")
                || fs.contains("shunit2") && f.equals("unittest");
    }
    private void use(Result result) { if (allowed(result.framework())) { summaries.put(result.framework(), result); complete = true; } }
    private static long n(Matcher m, int group) { String v = m.group(group); return v == null ? 0 : Long.parseLong(v); }
    static Result counts(String f, long total, long failed, long errors, long skipped, String basis) {
        if (total < 0 || failed < 0 || errors < 0 || skipped < 0 || failed + errors + skipped > total) return null;
        String outcome = total == 0 ? "no_tests" : skipped == total ? "skipped" : failed + errors > 0 ? "failed" : "passed";
        return new Result(f, outcome, total, total - failed - errors - skipped, failed, errors, skipped, "tests", basis);
    }
    private void useCounts(String f, long total, long failed, long errors, long skipped) {
        Result value = counts(f, total, failed, errors, skipped, "runner-summary");
        if (value != null) use(value);
    }

    private void parse(String line, boolean topLevel) {
        Matcher m;
        if (line.startsWith("{")) { json(line); return; }
        if (line.contains("ERROR collecting") || line.contains("Failed to collect")) setupError = true;
        if (line.matches("(?i).*(?:no tests (?:ran|collected|found|were found)|no test files|no tests to run).*")) noTests = true;
        if (line.contains("FROM-CACHE") || line.contains("UP-TO-DATE") || line.contains("(cached)")) cached = true;
        if (allowed("ctest")) {
            m = p("^\\d+/\\d+ Test\\s+#(\\d+):.*(?:\\*\\*\\*Skipped|\\*\\*\\*Not Run \\(Disabled\\)).*$").matcher(line);
            if (!m.matches()) m = p("^(\\d+) - .* \\((?:Skipped|Disabled)\\)$").matcher(line);
            if (m.matches()) {
                if (ctestSkipped.size() < MAX_SUITES) ctestSkipped.add(m.group(1)); else overflow = true;
            }
            if (line.contains("Unable to find executable") || line.contains("Could not find executable")) setupError = true;
        }
        if (allowed("jvm")) {
            m = p("^\\[\\s*(\\d+) tests (successful|failed|skipped|aborted)\\s*]$").matcher(line);
            if (m.matches()) {
                switch (m.group(2)) { case "successful" -> junitPassed = n(m, 1); case "failed" -> junitFailed = n(m, 1); case "skipped" -> junitSkipped = n(m, 1); case "aborted" -> junitAborted = n(m, 1); }
                return;
            }
            m = p("^Total tests run: (\\d+), Passes: (\\d+), Failures: (\\d+), Skips: (\\d+).*$").matcher(line);
            if (m.matches() && n(m, 1) == n(m, 2) + n(m, 3) + n(m, 4)) { useCounts("jvm", n(m, 1), n(m, 3), 0, n(m, 4)); return; }
        }
        if (allowed("scalatest")) {
            m = p("^Tests: succeeded (\\d+), failed (\\d+), canceled (\\d+), ignored (\\d+), pending (\\d+).*$").matcher(line);
            if (m.matches()) { useCounts("scalatest", n(m, 1) + n(m, 2) + n(m, 3) + n(m, 4) + n(m, 5), n(m, 2), 0, n(m, 3) + n(m, 4) + n(m, 5)); return; }
        }
        if (allowed("platformio") || allowed("unity")) {
            m = p("^(\\d+) Tests (\\d+) Failures (\\d+) Ignored.*$").matcher(line);
            if (m.matches()) { useCounts("unity", n(m, 1), n(m, 2), 0, n(m, 3)); return; }
        }
        if (allowed("node")) {
            m = p("^(?:ℹ |# )?(tests|pass|fail|skipped|cancelled|todo) (\\d+)$").matcher(line);
            if (m.matches()) {
                switch (m.group(1)) {
                    case "tests" -> nodeTotal = n(m, 2); case "pass" -> nodePassed = n(m, 2); case "fail" -> nodeFailed = n(m, 2);
                    case "skipped" -> nodeSkipped = n(m, 2); case "cancelled" -> nodeCancelled = n(m, 2); case "todo" -> nodeTodo = n(m, 2);
                }
                return;
            }
            if (line.matches("^(?:ℹ |# )?duration_ms [0-9.]+$")) nodeComplete = true;
        }
        if (allowed("playwright")) {
            m = p("^(\\d+) (passed|failed|skipped|flaky)(?: \\(.*\\))?$").matcher(line);
            if (m.matches()) {
                switch (m.group(2)) { case "passed", "flaky" -> playwrightPassed += n(m, 1); case "failed" -> playwrightFailed += n(m, 1); case "skipped" -> playwrightSkipped += n(m, 1); }
                playwrightComplete = true; return;
            }
        }
        if (allowed("perl") || allowed("pgtap")) {
            m = p("^Files=\\d+, Tests=(\\d+),.*$").matcher(line); if (m.matches()) harnessTotal = n(m, 1);
            if (harnessTotal >= 0 && line.matches("^Result: (PASS|FAIL)$")) {
                String hf = plan.frameworks().contains("pgtap") ? "pgtap" : "perl";
                use(new Result(hf, harnessTotal == 0 ? "no_tests" : line.endsWith("PASS") ? "passed" : "failed", harnessTotal, null, null, null, null, "tests", "tap-harness-summary")); return;
            }
        }
        if (line.startsWith("=== RUN") || line.startsWith("[ RUN      ]") || line.matches("^test .+ \\.\\.\\. (ok|FAILED)$")) started = true;
        m = SUREFIRE.matcher(line);
        if (m.matches() && allowed("jvm")) {
            Result r = counts("jvm", n(m, 1), n(m, 2), n(m, 3), n(m, 4), "runner-summary");
            if (r != null) {
                int suffix = line.indexOf("-- in ");
                if (suffix >= 0) {
                    String key = line.substring(suffix + 6);
                    if (suites.size() < MAX_SUITES || suites.containsKey(key)) suites.put(key, r); else overflow = true;
                } else summaries.put("jvm", r);
                complete = true;
            }
            return;
        }
        m = RUST.matcher(line); if (m.matches() && allowed("rust")) { addSuite(counts("rust", n(m, 2) + n(m, 3) + n(m, 4), n(m, 3), 0, n(m, 4), "runner-summary")); return; }
        m = CTEST.matcher(line); if (m.matches()) { useCounts("ctest", n(m, 2), n(m, 1), 0, 0); return; }
        m = MINI.matcher(line); if (m.matches()) { useCounts("minitest", n(m, 1), n(m, 2), n(m, 3), n(m, 4)); return; }
        m = RSPEC.matcher(line); if (m.matches()) { useCounts("rspec", n(m, 1), n(m, 2), 0, n(m, 3)); return; }
        m = LUA.matcher(line); if (m.matches()) { useCounts("lua", n(m, 1) + n(m, 2) + n(m, 3) + n(m, 4), n(m, 2), n(m, 3), n(m, 4)); return; }
        if (allowed("unittest")) {
            m = p("^(\\d+) passed and (\\d+) failed\\.$").matcher(line);
            if (m.matches()) { useCounts("unittest", n(m, 1) + n(m, 2), n(m, 2), 0, 0); return; }
        }
        m = UNITTEST.matcher(line); if (m.matches() && allowed("unittest")) { unittestTotal = n(m, 1); return; }
        m = p("^Ran (\\d+) tests?\\.$").matcher(line); if (m.matches() && allowed("unittest")) { unittestTotal = n(m, 1); return; }
        if (unittestTotal >= 0 && line.matches("^(OK|FAILED)(?: \\(.*\\))?$")) {
            long failures = assignment(line, "failures"), errors = assignment(line, "errors"), skips = assignment(line, "skipped");
            useCounts("unittest", unittestTotal, failures, errors, skips); return;
        }
        m = XCTEST.matcher(line); if (m.matches()) { useCounts("xctest", n(m, 1), n(m, 2), 0, 0); return; }
        m = SWIFT.matcher(line); if (m.matches() && allowed("swift")) {
            // The terminal summary supplies a verdict, not a failure count.
            use(new Result("swift", n(m, 1) == 0 ? "no_tests" : m.group(2), n(m, 1), null, null, null, null, "tests", "runner-summary")); return;
        }
        m = GTEST.matcher(line); if (m.matches() && allowed("gtest")) { googleTotal = n(m, 1); complete = true; return; }
        m = GTEST_COUNT.matcher(line); if (m.matches() && allowed("gtest")) {
            switch (m.group(1)) { case "PASSED" -> googlePassed = n(m, 2); case "FAILED" -> googleFailed = n(m, 2); case "SKIPPED" -> googleSkipped = n(m, 2); }
            return;
        }
        m = VADER.matcher(line); if (m.matches()) { useCounts("vader", n(m, 2), n(m, 2) - n(m, 1), 0, 0); return; }
        m = CATCH_OK.matcher(line); if (m.matches()) { useCounts("catch2", n(m, 1), 0, 0, 0); return; }
        m = CATCH.matcher(line); if (m.matches()) { useCounts("catch2", n(m, 1), label(m.group(2), "failed"), 0, label(m.group(2), "skipped")); return; }
        if (allowed("pytest") && line.matches("^=+ .* in [0-9.]+s.* =+$")) {
            long passed = label(line, "passed"), failed = label(line, "failed"), errors = label(line, "errors?"),
                    skipped = label(line, "skipped") + label(line, "xfailed"), xpassed = label(line, "xpassed");
            long total = passed + failed + errors + skipped + xpassed;
            if (total > 0) useCounts("pytest", total, failed, errors, skipped); return;
        }
        if (allowed("pytest") && line.matches("^.*(?:\\d+ passed|\\d+ failed|\\d+ skipped|\\d+ error).* in [0-9.]+s.*$")) {
            long passed = label(line, "passed"), failed = label(line, "failed"), errors = label(line, "errors?"), skipped = label(line, "skipped") + label(line, "xfailed");
            if (passed + failed + errors + skipped > 0) useCounts("pytest", passed + failed + errors + skipped + label(line, "xpassed"), failed, errors, skipped); return;
        }
        if (line.matches("^Tests: .*$") || line.matches("^Tests +.*\\(\\d+\\)$")) {
            long passed = label(line, "passed"), failed = label(line, "failed"), skipped = label(line, "skipped") + label(line, "todo");
            String f = plan.frameworks().contains("vitest") ? "vitest" : "jest";
            if (passed + failed + skipped > 0) useCounts(f, passed + failed + skipped, failed, 0, skipped); return;
        }
        if (allowed("mocha")) {
            m = p("^(\\d+) passing(?: \\(.*\\))?$").matcher(line); if (m.matches()) { mochaPassed = n(m, 1); complete = true; }
            m = p("^(\\d+) failing$").matcher(line); if (m.matches()) { mochaFailed = n(m, 1); complete = true; }
        }
        if (allowed("dotnet")) {
            m = p("^(?:Passed!|Failed!|Test Run .*)?.*Failed:\\s*(\\d+),\\s*Passed:\\s*(\\d+),\\s*Skipped:\\s*(\\d+),\\s*Total:\\s*(\\d+).*$").matcher(line);
            if (m.matches()) { useCounts("dotnet", n(m, 4), n(m, 1), 0, n(m, 3)); return; }
        }
        if (allowed("dart")) {
            m = p(".*\\+(\\d+)(?:\\s*~(\\d+))?(?:\\s*-(\\d+))?:.*").matcher(line);
            if (m.matches()) { dartPassed = n(m, 1); dartSkipped = n(m, 2); dartFailed = n(m, 3); }
            if (line.contains("All tests passed!") || line.contains("Some tests failed.")) {
                useCounts("dart", dartPassed + dartFailed + dartSkipped, dartFailed, 0, dartSkipped); return;
            }
        }
        if (allowed("pester")) {
            m = p("^Tests Passed: (\\d+), Failed: (\\d+), Skipped: (\\d+)(?:, .*|$)").matcher(line);
            if (m.matches()) { useCounts("pester", n(m, 1) + n(m, 2) + n(m, 3), n(m, 2), 0, n(m, 3)); return; }
        }
        if (allowed("testthat")) {
            m = p("^\\[ FAIL (\\d+) \\| WARN \\d+ \\| SKIP (\\d+) \\| PASS (\\d+) ]$").matcher(line);
            if (m.matches()) {
                Result r = counts("testthat", n(m, 1) + n(m, 2) + n(m, 3), n(m, 1), 0, n(m, 2), "runner-summary");
                if (r != null) use(new Result(r.framework(), r.outcome(), r.total(), r.passed(), r.failed(), r.errors(), r.skipped(), "expectations", r.basis()));
                return;
            }
        }
        if (allowed("sage")) {
            m = p("^.*\\[(\\d+) tests?,.*]$").matcher(line); if (m.matches()) sageTotal += n(m, 1);
            if (line.equals("All tests passed!") && sageTotal > 0) useCounts("sage", sageTotal, 0, 0, 0);
        }
        if (allowed("go")) {
            if (line.matches("^ok\\s+\\S+\\s+[0-9.]+s(?:\\s.*)?$") && !line.contains("no tests to run"))
                addSuite(new Result("go", "passed", null, null, null, null, null, "tests", "package-summary"));
            if (line.matches("^FAIL\\s+\\S+.*$") && started) addSuite(new Result("go", "failed", null, null, null, null, null, "tests", "package-summary"));
        }
        boolean tap = allowed("node") || allowed("perl") || allowed("pgtap") || allowed("bats") || allowed("shellspec") || allowed("lua") || plan.frameworks().contains("generic");
        if (tap && topLevel) {
            m = TAP_PLAN.matcher(line); if (m.matches()) { if (tapPlan >= 0) tapInvalid = true; tapPlan = n(m, 1); return; }
            m = TAP_RESULT.matcher(line); if (m.matches()) {
                if (m.group(2) != null && n(m, 2) != tapSeen + 1) tapInvalid = true;
                tapSeen++;
                boolean skip = line.matches("(?i).*#\\s*(SKIP|TODO).*" );
                if (skip) tapSkipped++; else if (m.group(1).equals("not ok")) tapFailed++;
                return;
            }
            if (line.startsWith("Bail out!")) tapBailout = true;
        }
        if (config.fallback && config.allows("generic")) {
            m = GENERIC.matcher(line); if (m.matches()) {
                if (n(m, 1) == n(m, 2) + n(m, 3) + n(m, 4)) useCounts("generic", n(m, 1), n(m, 3), 0, n(m, 4));
            }
        }
        for (TestCaptureConfig.Rule rule : config.rules) {
            if (!rule.command().matcher(command).find()) continue;
            m = rule.summary().matcher(line);
            if (m.matches()) {
                try {
                    long total = Long.parseLong(m.group("total")), passed = Long.parseLong(m.group("passed")), failed = Long.parseLong(m.group("failed"));
                    long skipped = named(m, "skipped"), errors = named(m, "errors");
                    if (total == passed + failed + skipped + errors) useCounts("custom", total, failed, errors, skipped);
                } catch (IllegalArgumentException ignored) { /* Invalid counts/group names provide no evidence. */ }
            }
        }
    }
    private static long named(Matcher m, String key) { try { String v = m.group(key); return v == null ? 0 : Long.parseLong(v); } catch (IllegalArgumentException absent) { return 0; } }
    private static long assignment(String line, String key) { Matcher m = p(key + "=(\\d+)").matcher(line); return m.find() ? n(m, 1) : 0; }
    private static long label(String line, String label) { Matcher m = p("(?:^|[^A-Za-z0-9_])(\\d+) " + label + "(?:[^A-Za-z_]|$)").matcher(line); return m.find() ? n(m, 1) : 0; }
    private void addSuite(Result r) {
        if (r == null || !allowed(r.framework())) return;
        if (suites.size() >= MAX_SUITES) { overflow = true; return; }
        suites.put("suite-" + suites.size(), r); complete = true;
    }
    private void jsonTest(String id, String result) {
        if (jsonTests.size() >= MAX_CASES && !jsonTests.containsKey(id)) { overflow = true; return; }
        jsonTests.put(id, result);
    }

    private void json(String line) {
        try {
            JsonNode j = JsonUtils.standardMapper().readTree(line);
            if (allowed("go") && j.has("Action")) {
                String action = j.path("Action").asText(), test = j.path("Test").asText();
                if (j.has("FailedBuild")) { setupError = true; return; }
                if (action.equals("run") && !test.isEmpty()) started = true;
                if (!test.isEmpty() && Set.of("pass", "fail", "skip").contains(action))
                    jsonTest(j.path("Package").asText() + ":" + test, action);
                if (test.isEmpty() && Set.of("pass", "fail", "skip").contains(action)) complete = true;
                if (action.equals("output")) {
                    String output = j.path("Output").asText();
                    if (output.contains("(cached)")) {
                        if (cachedPackages.size() < MAX_CASES) cachedPackages.add(j.path("Package").asText()); else overflow = true;
                    }
                    accept(output);
                }
            } else if (allowed("dart") && j.path("type").asText().equals("testDone") && !j.path("hidden").asBoolean()) {
                jsonTest(j.path("testID").asText(), j.path("skipped").asBoolean() ? "skip" : j.path("result").asText().equals("success") ? "pass" : "fail");
            } else if (allowed("dart") && j.path("type").asText().equals("done")) complete = true;
        } catch (Exception ignored) { /* Not a supported machine event. */ }
    }

    synchronized Result result(Integer exit, boolean interrupted) {
        String f = plan.frameworks().iterator().next();
        if (plan.suppressed()) return null;
        Map<String, Result> results = new LinkedHashMap<>(summaries);
        Map<String, List<Result>> grouped = new LinkedHashMap<>();
        for (Result suite : suites.values()) grouped.computeIfAbsent(suite.framework(), ignored -> new ArrayList<>()).add(suite);
        grouped.forEach((framework, values) -> results.put(framework, aggregate(values)));
        if (googleTotal >= 0 && googlePassed + googleFailed + googleSkipped == googleTotal)
            results.put("gtest", counts("gtest", googleTotal, googleFailed, 0, googleSkipped, "runner-summary"));
        if (allowed("mocha") && (mochaPassed >= 0 || mochaFailed > 0)) results.put("mocha", counts("mocha", Math.max(0, mochaPassed) + mochaFailed, mochaFailed, 0, 0, "runner-summary"));
        if (nodeComplete && nodeTotal >= 0 && nodeTotal == nodePassed + nodeFailed + nodeSkipped + nodeCancelled + nodeTodo)
            results.put("node", counts("node", nodeTotal, nodeFailed, nodeCancelled, nodeSkipped + nodeTodo, "runner-summary"));
        if (playwrightComplete) results.put("playwright", counts("playwright", playwrightPassed + playwrightFailed + playwrightSkipped, playwrightFailed, 0, playwrightSkipped, "runner-summary"));
        if (tapPlan >= 0 && !tapInvalid && !tapBailout && tapPlan == tapSeen) {
            String tf = plan.frameworks().stream().filter(Set.of("node", "perl", "pgtap", "bats", "shellspec", "lua")::contains).findFirst().orElse("generic");
            results.put(tf, counts(tf, tapSeen, tapFailed, 0, tapSkipped, "tap-plan-and-results"));
        }
        if (!jsonTests.isEmpty() && complete) {
            List<String> statuses = jsonTests.entrySet().stream()
                    .filter(entry -> cachedPackages.stream().noneMatch(pkg -> entry.getKey().startsWith(pkg + ":")))
                    .map(Map.Entry::getValue).toList();
            if (!statuses.isEmpty()) {
                long fail = statuses.stream().filter("fail"::equals).count(), skip = statuses.stream().filter("skip"::equals).count();
                String jf = allowed("go") && plan.frameworks().contains("go") ? "go" : "dart";
                results.put(jf, counts(jf, statuses.size(), fail, 0, skip, "runner-json"));
            }
        }
        if (!ctestSkipped.isEmpty() && results.get("ctest") != null) {
            Result r = results.get("ctest");
            results.put("ctest", counts("ctest", r.total(), r.failed(), r.errors(), ctestSkipped.size(), "runner-summary"));
        }
        if (junitPassed >= 0 && junitFailed >= 0) results.put("jvm", counts("jvm", junitPassed + junitFailed + junitSkipped + junitAborted, junitFailed, 0, junitSkipped + junitAborted, "runner-summary"));
        // Verbose parent runners repeat their children's summaries. Count at one level only.
        if (results.containsKey("ctest") && plan.frameworks().equals(Set.of("ctest"))) { results.remove("gtest"); results.remove("catch2"); }
        if (results.containsKey("jvm") && plan.frameworks().equals(Set.of("jvm"))) results.remove("scalatest");
        results.values().removeIf(Objects::isNull);
        Result result = results.isEmpty() ? null : results.size() == 1 ? results.values().iterator().next() : aggregate(results.values());
        if (interrupted) return result == null ? neutral(f, "interrupted") : result.withOutcome("interrupted");
        if (overflow) return new Result(f, "unknown", null, null, null, null, null, "tests", "output-count-limit");
        if (setupError && !started) return neutral(f, "setup_error");
        if (tapBailout || tapInvalid || tapPlan >= 0 && tapPlan != tapSeen) return neutral(f, "unknown");
        if (result != null) {
            // Keep confirmed counts, but do not certify a passing run when its process failed.
            if ("passed".equals(result.outcome()) && exit != null && exit != 0) return result.withOutcome("unknown");
            return result;
        }
        if (cached) return neutral(f, "cached");
        if (noTests) return neutral(f, "no_tests");
        if (plan.explicit()) return neutral(f, exit != null && exit != 0 && !started ? "execution_error" : "unknown");
        return null;
    }
    private static Result neutral(String f, String outcome) { return new Result(f, outcome, null, null, null, null, null, "tests", "execution-observation"); }
    static Result aggregate(Collection<Result> results) {
        long total = 0, passed = 0, failed = 0, errors = 0, skipped = 0;
        boolean known = true, anyPass = false, anyFail = false;
        Set<String> frameworks = new LinkedHashSet<>(), units = new LinkedHashSet<>();
        for (Result r : results) {
            frameworks.add(r.framework()); units.add(r.unit());
            known &= r.total() != null && r.passed() != null && r.failed() != null && r.errors() != null && r.skipped() != null;
            total += r.total() == null ? 0 : r.total(); passed += r.passed() == null ? 0 : r.passed();
            failed += r.failed() == null ? 0 : r.failed(); errors += r.errors() == null ? 0 : r.errors(); skipped += r.skipped() == null ? 0 : r.skipped();
            anyPass |= Boolean.TRUE.equals(r.passing()); anyFail |= Boolean.FALSE.equals(r.passing());
        }
        String framework = frameworks.size() == 1 ? frameworks.iterator().next() : "mixed";
        String unit = units.size() == 1 ? units.iterator().next() : "mixed";
        known &= units.size() == 1;
        String outcome = anyFail ? "failed" : anyPass ? "passed" : total == 0 ? "no_tests" : "skipped";
        return new Result(framework, outcome, known ? total : null, known ? passed : null, known ? failed : null,
                known ? errors : null, known ? skipped : null, unit, "runner-suites");
    }
}
