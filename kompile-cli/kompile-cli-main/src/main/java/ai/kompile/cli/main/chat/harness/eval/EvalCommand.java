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

package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command for running agent evaluation suites.
 *
 * <p>Usage:
 * <pre>
 *   kompile eval run suite.yaml               # Run a suite
 *   kompile eval run suite.yaml --agent codex  # Override agent
 *   kompile eval run suite.json --json         # JSON output
 *   kompile eval report                        # Show past results
 *   kompile eval report --suite "My Suite"     # Filter by suite
 *   kompile eval list                          # List known suites
 * </pre>
 */
@CommandLine.Command(
        name = "eval",
        description = "Run agent evaluation suites — automated testing with outcome tracking.",
        subcommands = {
                EvalCommand.RunCommand.class,
                EvalCommand.ReportCommand.class,
                EvalCommand.ListCommand.class,
                EvalCommand.ShowCommand.class,
                EvalCommand.InspectCommand.class,
                EvalCommand.CreateCommand.class,
                EvalCommand.DeleteCommand.class,
                EvalCommand.CompareCommand.class
        },
        mixinStandardHelpOptions = true
)
public class EvalCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Usage: kompile eval [run|report|list|show|inspect|create|delete|compare]");
        System.out.println();
        System.out.println("  run <suite.yaml>    Run an evaluation suite against an agent");
        System.out.println("  report              Show results from past evaluation runs");
        System.out.println("  list                List known evaluation suites");
        System.out.println("  show <run-id>       Show detailed results for a specific run");
        System.out.println("  inspect <file>      Validate and summarize a suite file");
        System.out.println("  create <file>       Scaffold a new eval suite file");
        System.out.println("  delete <run-id>     Delete a stored run result");
        System.out.println("  compare <a> <b>     Compare two runs side-by-side");
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval run <suite-file>
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "run", description = "Run an evaluation suite against an agent",
            mixinStandardHelpOptions = true)
    static class RunCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Path to eval suite file (YAML or JSON)")
        File suiteFile;

        @CommandLine.Option(names = "--agent", description = "Override agent for all cases")
        String agent;

        @CommandLine.Option(names = "--model", description = "Override model for all cases")
        String model;

        @CommandLine.Option(names = "--provider", description = "Override provider for all cases")
        String provider;

        @CommandLine.Option(names = "--tag", description = "Only run cases with this tag")
        String tag;

        @CommandLine.Option(names = "--json", description = "Output results in JSON format")
        boolean json;

        @CommandLine.Option(names = "--judge", description = "Enable judge LLM scoring (correctness/completeness)")
        boolean useJudge;

        @CommandLine.Option(names = "--workdir", description = "Working directory for agent execution")
        File workdir;

        @Override
        public Integer call() {
            TerminalRenderer term = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(term);

            if (!suiteFile.exists()) {
                System.err.println("Suite file not found: " + suiteFile.getAbsolutePath());
                return 1;
            }

            // Load suite
            ObjectMapper mapper = createMapper(suiteFile.getName());
            EvalSuite suite;
            try {
                suite = mapper.readValue(suiteFile, EvalSuite.class);
            } catch (IOException e) {
                System.err.println("Failed to parse suite file: " + e.getMessage());
                return 1;
            }

            // Apply overrides
            if (agent != null) suite.setAgent(agent);
            if (model != null) suite.setModel(model);
            if (provider != null) suite.setProvider(provider);

            // Filter by tag
            if (tag != null) {
                suite.getCases().removeIf(c -> c.getTags() == null || !c.getTags().contains(tag));
            }

            List<EvalCase> cases = suite.enabledCases();
            if (cases.isEmpty()) {
                System.err.println("No enabled cases to run" + (tag != null ? " with tag '" + tag + "'" : ""));
                return 1;
            }

            // Print header
            if (!json) {
                System.out.println();
                System.out.println(ascii.sectionHeader("Eval: " + suite.getName()));
                System.out.println(term.dim("  " + cases.size() + " cases | agent=" +
                        (suite.getAgent() != null ? suite.getAgent() : "claude") +
                        " | required pass rate=" + (int)(suite.getRequiredPassRate() * 100) + "%"));
                System.out.println();
            }

            // Run
            Path runWorkDir = workdir != null ? workdir.toPath() : Path.of(System.getProperty("user.dir"));
            EvalRunner runner = new EvalRunner(runWorkDir);

            if (useJudge) {
                runner.enableJudge();
            }

            if (!json) {
                runner.setCaseListener((result, index, total) -> {
                    String icon = result.passed() ? term.green("PASS") : term.red("FAIL");
                    String outcome = result.getOutcome() != null ? result.getOutcome().name() : "?";
                    System.out.printf("  [%d/%d] %s  %-30s  %s  %s%n",
                            index, total, icon,
                            StringUtils.truncate(result.getCaseId(), 30),
                            term.dim(outcome),
                            term.dim(result.getExecutionTimeMs() + "ms"));
                });
            }

            EvalRunResult runResult = runner.run(suite, suiteFile.getAbsolutePath());

            // Persist
            EvalResultStore store = new EvalResultStore();
            store.load();
            store.record(runResult);

            // Output
            if (json) {
                printJsonResult(runResult);
            } else {
                printTextResult(runResult, term, ascii);
            }

            return runResult.isSuitePassed() ? 0 : 1;
        }

        private void printJsonResult(EvalRunResult result) {
            try {
                ObjectMapper jsonMapper = JsonUtils.newStandardMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT);
                System.out.println(jsonMapper.writeValueAsString(result));
            } catch (Exception e) {
                System.err.println("Error serializing result: " + e.getMessage());
            }
        }

        private void printTextResult(EvalRunResult result, TerminalRenderer term, AsciiRenderer ascii) {
            System.out.println();

            // Summary
            String verdict = result.isSuitePassed()
                    ? term.green("SUITE PASSED")
                    : term.red("SUITE FAILED");

            StringBuilder summary = new StringBuilder();
            summary.append(verdict).append("\n\n");
            summary.append(String.format("  Pass rate:    %d/%d (%.0f%%)%n",
                    result.getPassedCases(), result.getTotalCases(), result.getPassRate() * 100));
            summary.append(String.format("  Required:     %.0f%%%n", result.getRequiredPassRate() * 100));
            summary.append(String.format("  Duration:     %dms%n", result.getTotalDurationMs()));
            if (result.getTotalTokens() > 0) {
                summary.append(String.format("  Tokens:       %d%n", result.getTotalTokens()));
            }
            if (result.getAvgCompositeScore() > 0) {
                summary.append(String.format("  Avg score:    %.1f/5.0%n", result.getAvgCompositeScore()));
            }
            if (result.getAvgJudgeCorrectness() > 0) {
                summary.append(String.format("  Judge correct: %.1f/5.0%n", result.getAvgJudgeCorrectness()));
                summary.append(String.format("  Judge complete: %.1f/5.0%n", result.getAvgJudgeCompleteness()));
            }

            // Outcome breakdown
            if (!result.getOutcomeCounts().isEmpty()) {
                summary.append("\n  Outcomes:\n");
                for (Map.Entry<String, Integer> e : result.getOutcomeCounts().entrySet()) {
                    String icon = "COMPLETED".equals(e.getKey()) ? term.green(e.getKey())
                            : "ESCAPED".equals(e.getKey()) || "FAILED".equals(e.getKey()) ? term.red(e.getKey())
                            : term.yellow(e.getKey());
                    summary.append(String.format("    %-12s %d%n", icon, e.getValue()));
                }
            }

            System.out.println(ascii.panel("Results: " + result.getSuiteName(),
                    summary.toString().stripTrailing()));

            // Failed cases detail
            List<EvalCaseResult> failures = result.getCaseResults().stream()
                    .filter(r -> !r.passed()).toList();
            if (!failures.isEmpty()) {
                System.out.println();
                StringBuilder failBody = new StringBuilder();
                for (EvalCaseResult f : failures) {
                    failBody.append(term.red("  " + f.getCaseId())).append("\n");
                    failBody.append(term.dim("    Outcome: ")).append(f.getOutcome()).append("\n");
                    failBody.append(term.dim("    Reason:  ")).append(f.getOutcomeReason()).append("\n");
                    if (f.getJudgeReasoning() != null) {
                        failBody.append(term.dim("    Judge:   ")).append(f.getJudgeReasoning()).append("\n");
                    }

                    // Show failed assertions
                    for (AssertionResult ar : f.getAssertionResults()) {
                        if (!ar.passed()) {
                            String label = ar.critical() ? term.red("FAIL") : term.yellow("WARN");
                            failBody.append("    ").append(label).append(" ").append(ar.description());
                            if (ar.detail() != null) {
                                failBody.append(" — ").append(ar.detail());
                            }
                            failBody.append("\n");
                        }
                    }
                    failBody.append("\n");
                }
                System.out.println(ascii.panel("Failed Cases", failBody.toString().stripTrailing()));
            }

            System.out.println();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval report
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "report", description = "Show results from past evaluation runs",
            mixinStandardHelpOptions = true)
    static class ReportCommand implements Callable<Integer> {

        @CommandLine.Option(names = "--suite", description = "Filter by suite name")
        String suiteName;

        @CommandLine.Option(names = "--last", description = "Show last N runs (default: 10)", defaultValue = "10")
        int last;

        @CommandLine.Option(names = "--json", description = "Output in JSON format")
        boolean json;

        @Override
        public Integer call() {
            TerminalRenderer term = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(term);

            EvalResultStore store = new EvalResultStore();
            store.load();

            if (store.size() == 0) {
                System.out.println("No evaluation results found.");
                System.out.println("Run 'kompile eval run <suite.yaml>' to generate results.");
                return 0;
            }

            List<EvalRunResult> runs = store.getRecentRuns(suiteName, last);
            if (runs.isEmpty()) {
                System.out.println("No runs found" +
                        (suiteName != null ? " for suite '" + suiteName + "'" : ""));
                return 0;
            }

            if (json) {
                try {
                    ObjectMapper jsonMapper = JsonUtils.newStandardMapper()
                            .enable(SerializationFeature.INDENT_OUTPUT);
                    System.out.println(jsonMapper.writeValueAsString(runs));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
                return 0;
            }

            System.out.println();
            System.out.println(ascii.sectionHeader("Eval History (" + runs.size() + " runs)"));
            System.out.println();

            // Per-suite summary
            for (String suite : store.getSuiteNames()) {
                if (suiteName != null && !suiteName.equals(suite)) continue;

                double[] trend = store.getPassRateTrend(suite, 12);
                EvalRunResult latest = store.getLatestRun(suite);
                if (latest == null) continue;

                StringBuilder body = new StringBuilder();
                String verdict = latest.isSuitePassed() ? term.green("PASS") : term.red("FAIL");
                body.append("  Latest: ").append(verdict);
                body.append(String.format(" %d/%d (%.0f%%)",
                        latest.getPassedCases(), latest.getTotalCases(), latest.getPassRate() * 100));
                body.append("\n");

                if (trend.length > 1) {
                    body.append("  Trend:  ");
                    for (double v : trend) {
                        body.append(String.format("%.0f%% ", v * 100));
                    }
                    body.append("\n");
                }

                // Outcome breakdown from latest run
                if (!latest.getOutcomeCounts().isEmpty()) {
                    body.append("  Outcomes: ");
                    latest.getOutcomeCounts().forEach((k, v) -> body.append(k).append("=").append(v).append(" "));
                    body.append("\n");
                }

                System.out.println(ascii.panel(suite, body.toString().stripTrailing()));
                System.out.println();
            }

            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval list
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "list", description = "List known evaluation suites",
            mixinStandardHelpOptions = true)
    static class ListCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            EvalResultStore store = new EvalResultStore();
            store.load();

            List<String> suites = store.getSuiteNames();
            if (suites.isEmpty()) {
                System.out.println("No evaluation suites have been run yet.");
                System.out.println();
                System.out.println("Create a suite YAML file and run it with:");
                System.out.println("  kompile eval run <suite.yaml>");
                System.out.println();
                System.out.println("Example suite format:");
                System.out.println("  name: My Agent Tests");
                System.out.println("  agent: claude");
                System.out.println("  requiredPassRate: 0.8");
                System.out.println("  cases:");
                System.out.println("    - id: test-1");
                System.out.println("      name: Basic file reading");
                System.out.println("      prompt: \"Read /tmp/test.txt and report its contents\"");
                System.out.println("      assertions:");
                System.out.println("        - type: NO_ESCAPE");
                System.out.println("        - type: TOOL_WAS_CALLED");
                System.out.println("          value: read");
                return 0;
            }

            System.out.println("Known evaluation suites:");
            for (String suite : suites) {
                EvalRunResult latest = store.getLatestRun(suite);
                if (latest != null) {
                    String verdict = latest.isSuitePassed() ? "PASS" : "FAIL";
                    System.out.printf("  %-30s %s  %d/%d (%.0f%%)  %s%n",
                            suite, verdict,
                            latest.getPassedCases(), latest.getTotalCases(),
                            latest.getPassRate() * 100,
                            latest.getStartTime());
                } else {
                    System.out.printf("  %-30s (no results)%n", suite);
                }
            }
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval show <run-id>
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "show", description = "Show detailed results for a specific run",
            mixinStandardHelpOptions = true)
    static class ShowCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Run ID to show")
        String runId;

        @CommandLine.Option(names = "--json", description = "Output in JSON format")
        boolean json;

        @Override
        public Integer call() {
            TerminalRenderer term = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(term);

            EvalResultStore store = new EvalResultStore();
            store.load();

            EvalRunResult run = store.getRunById(runId);
            if (run == null) {
                System.err.println("Run not found: " + runId);
                System.err.println("Use 'kompile eval report' to see available runs.");
                return 1;
            }

            if (json) {
                try {
                    ObjectMapper jsonMapper = JsonUtils.newStandardMapper()
                            .enable(SerializationFeature.INDENT_OUTPUT);
                    System.out.println(jsonMapper.writeValueAsString(run));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
                return 0;
            }

            System.out.println();
            String verdict = run.isSuitePassed()
                    ? term.green("PASSED") : term.red("FAILED");

            StringBuilder header = new StringBuilder();
            header.append("  Run ID:   ").append(run.getRunId()).append("\n");
            header.append("  Suite:    ").append(run.getSuiteName()).append("\n");
            header.append("  File:     ").append(run.getSuiteFile()).append("\n");
            header.append("  Result:   ").append(verdict).append("\n");
            header.append(String.format("  Pass:     %d/%d (%.0f%%) — required: %.0f%%%n",
                    run.getPassedCases(), run.getTotalCases(),
                    run.getPassRate() * 100, run.getRequiredPassRate() * 100));
            header.append("  Duration: ").append(run.getTotalDurationMs()).append("ms\n");
            header.append("  Started:  ").append(run.getStartTime()).append("\n");
            if (run.getTotalTokens() > 0)
                header.append("  Tokens:   ").append(run.getTotalTokens()).append("\n");
            if (run.getAvgCompositeScore() > 0)
                header.append(String.format("  Avg score: %.1f/5.0%n", run.getAvgCompositeScore()));

            System.out.println(ascii.panel("Run Details", header.toString().stripTrailing()));
            System.out.println();

            // Case details
            StringBuilder cases = new StringBuilder();
            for (EvalCaseResult cr : run.getCaseResults()) {
                String icon = cr.passed() ? term.green("PASS") : term.red("FAIL");
                cases.append(String.format("  %s  %-30s  %-10s  %dms%n",
                        icon,
                        StringUtils.truncate(cr.getCaseId(), 30),
                        cr.getOutcome(),
                        cr.getExecutionTimeMs()));

                if (!cr.passed()) {
                    if (cr.getOutcomeReason() != null)
                        cases.append(term.dim("       Reason: " + cr.getOutcomeReason())).append("\n");
                    if (cr.getJudgeReasoning() != null)
                        cases.append(term.dim("       Judge:  " + cr.getJudgeReasoning())).append("\n");

                    for (AssertionResult ar : cr.getAssertionResults()) {
                        if (!ar.passed()) {
                            String label = ar.critical() ? term.red("FAIL") : term.yellow("WARN");
                            cases.append("       ").append(label).append(" ").append(ar.description());
                            if (ar.detail() != null) cases.append(" — ").append(ar.detail());
                            cases.append("\n");
                        }
                    }
                }
            }
            System.out.println(ascii.panel("Cases (" + run.getTotalCases() + ")",
                    cases.toString().stripTrailing()));
            System.out.println();
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval inspect <suite-file>
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "inspect", description = "Validate and summarize a suite file",
            mixinStandardHelpOptions = true)
    static class InspectCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Path to eval suite file")
        File suiteFile;

        @Override
        public Integer call() {
            TerminalRenderer term = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(term);

            if (!suiteFile.exists()) {
                System.err.println("Suite file not found: " + suiteFile.getAbsolutePath());
                return 1;
            }

            ObjectMapper mapper = createMapper(suiteFile.getName());
            EvalSuite suite;
            try {
                suite = mapper.readValue(suiteFile, EvalSuite.class);
            } catch (IOException e) {
                System.err.println("Failed to parse suite file: " + e.getMessage());
                return 1;
            }

            List<String> warnings = new java.util.ArrayList<>();
            List<String> errors = new java.util.ArrayList<>();

            if (suite.getName() == null || suite.getName().isBlank())
                errors.add("Suite has no name");
            if (suite.getCases() == null || suite.getCases().isEmpty())
                errors.add("Suite has no cases");
            if (suite.getRequiredPassRate() < 0 || suite.getRequiredPassRate() > 1)
                errors.add("requiredPassRate must be between 0.0 and 1.0");
            if (suite.getAgent() == null)
                warnings.add("No default agent — each case must specify its own");

            int enabled = 0, disabled = 0, totalAssertions = 0;
            Map<String, Integer> assertionTypes = new java.util.LinkedHashMap<>();

            if (suite.getCases() != null) {
                for (int i = 0; i < suite.getCases().size(); i++) {
                    EvalCase c = suite.getCases().get(i);
                    if (c.isEnabled()) enabled++; else disabled++;

                    if (c.getId() == null || c.getId().isBlank())
                        errors.add("Case " + i + " has no id");
                    if (c.getPrompt() == null || c.getPrompt().isBlank())
                        errors.add("Case '" + c.getId() + "' has no prompt");
                    if (c.getAssertions() == null || c.getAssertions().isEmpty())
                        warnings.add("Case '" + c.getId() + "' has no assertions");
                    else {
                        totalAssertions += c.getAssertions().size();
                        for (Assertion a : c.getAssertions())
                            assertionTypes.merge(a.getType().name(), 1, Integer::sum);
                    }
                }
            }

            boolean valid = errors.isEmpty();
            System.out.println();

            StringBuilder body = new StringBuilder();
            body.append("  Status:     ").append(valid ? term.green("VALID") : term.red("INVALID")).append("\n");
            body.append("  Name:       ").append(suite.getName()).append("\n");
            if (suite.getDescription() != null)
                body.append("  Desc:       ").append(suite.getDescription()).append("\n");
            body.append("  Agent:      ").append(suite.getAgent() != null ? suite.getAgent() : "(not set)").append("\n");
            body.append("  Model:      ").append(suite.getModel() != null ? suite.getModel() : "(not set)").append("\n");
            body.append(String.format("  Pass rate:  %.0f%%%n", suite.getRequiredPassRate() * 100));
            body.append(String.format("  Cases:      %d enabled, %d disabled%n", enabled, disabled));
            body.append("  Assertions: ").append(totalAssertions).append("\n");

            if (!assertionTypes.isEmpty()) {
                body.append("\n  Assertion types:\n");
                assertionTypes.forEach((k, v) ->
                        body.append(String.format("    %-28s %d%n", k, v)));
            }

            System.out.println(ascii.panel("Suite: " + suiteFile.getName(), body.toString().stripTrailing()));

            if (!errors.isEmpty()) {
                System.out.println();
                StringBuilder errBody = new StringBuilder();
                errors.forEach(e -> errBody.append("  ").append(term.red("ERROR")).append(": ").append(e).append("\n"));
                System.out.println(ascii.panel("Errors", errBody.toString().stripTrailing()));
            }
            if (!warnings.isEmpty()) {
                System.out.println();
                StringBuilder warnBody = new StringBuilder();
                warnings.forEach(w -> warnBody.append("  ").append(term.yellow("WARN")).append(": ").append(w).append("\n"));
                System.out.println(ascii.panel("Warnings", warnBody.toString().stripTrailing()));
            }

            System.out.println();
            return valid ? 0 : 1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval create <suite-file>
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "create", description = "Scaffold a new eval suite file",
            mixinStandardHelpOptions = true)
    static class CreateCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Path to create (YAML or JSON)")
        File suiteFile;

        @CommandLine.Option(names = "--name", description = "Suite name", defaultValue = "New Eval Suite")
        String name;

        @CommandLine.Option(names = "--agent", description = "Default agent", defaultValue = "claude")
        String agent;

        @CommandLine.Option(names = "--pass-rate", description = "Required pass rate (0.0-1.0)", defaultValue = "0.8")
        double passRate;

        @Override
        public Integer call() {
            if (suiteFile.exists()) {
                System.err.println("File already exists: " + suiteFile.getAbsolutePath());
                System.err.println("Use 'kompile eval inspect " + suiteFile.getName() + "' to validate it.");
                return 1;
            }

            boolean isYaml = suiteFile.getName().endsWith(".yaml") || suiteFile.getName().endsWith(".yml");

            String content;
            if (isYaml) {
                content = String.format("""
                        name: "%s"
                        description: "Agent evaluation suite"
                        agent: "%s"
                        requiredPassRate: %.1f
                        cases:
                          - id: example-1
                            name: "Example test case"
                            prompt: "Say hello and tell me what 2+2 is"
                            assertions:
                              - type: NO_ESCAPE
                              - type: OUTPUT_CONTAINS
                                value: "4"
                                description: "Output should contain the answer"
                            tags:
                              - basic
                          - id: example-2
                            name: "File reading test"
                            prompt: "List the files in the current directory"
                            assertions:
                              - type: NO_ESCAPE
                              - type: TOOL_WAS_CALLED
                                value: "list"
                                description: "Should use the list tool"
                              - type: NO_TOOL_ERRORS
                                critical: false
                            tags:
                              - file-io
                        """, name, agent, passRate);
            } else {
                content = String.format("""
                        {
                          "name": "%s",
                          "description": "Agent evaluation suite",
                          "agent": "%s",
                          "requiredPassRate": %.1f,
                          "cases": [
                            {
                              "id": "example-1",
                              "name": "Example test case",
                              "prompt": "Say hello and tell me what 2+2 is",
                              "assertions": [
                                { "type": "NO_ESCAPE" },
                                { "type": "OUTPUT_CONTAINS", "value": "4", "description": "Output should contain the answer" }
                              ],
                              "tags": ["basic"]
                            },
                            {
                              "id": "example-2",
                              "name": "File reading test",
                              "prompt": "List the files in the current directory",
                              "assertions": [
                                { "type": "NO_ESCAPE" },
                                { "type": "TOOL_WAS_CALLED", "value": "list", "description": "Should use the list tool" },
                                { "type": "NO_TOOL_ERRORS", "critical": false }
                              ],
                              "tags": ["file-io"]
                            }
                          ]
                        }
                        """, name, agent, passRate);
            }

            try {
                if (suiteFile.getParentFile() != null)
                    suiteFile.getParentFile().mkdirs();
                Files.writeString(suiteFile.toPath(), content);
            } catch (IOException e) {
                System.err.println("Failed to write file: " + e.getMessage());
                return 1;
            }

            System.out.println("Created eval suite: " + suiteFile.getAbsolutePath());
            System.out.println();
            System.out.println("Edit the file to add your test cases, then run with:");
            System.out.println("  kompile eval run " + suiteFile.getName());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval delete <run-id>
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "delete", description = "Delete a stored run result",
            mixinStandardHelpOptions = true)
    static class DeleteCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Run ID to delete")
        String runId;

        @Override
        public Integer call() {
            EvalResultStore store = new EvalResultStore();
            store.load();

            EvalRunResult run = store.getRunById(runId);
            if (run == null) {
                System.err.println("Run not found: " + runId);
                System.err.println("Use 'kompile eval report' to see available runs.");
                return 1;
            }

            System.out.println("Deleting run: " + runId);
            System.out.println("  Suite: " + run.getSuiteName());
            System.out.println("  Date:  " + run.getStartTime());
            System.out.println("  Rate:  " + String.format("%.0f%%", run.getPassRate() * 100));

            store.deleteRun(runId);
            System.out.println("Deleted.");
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  kompile eval compare <run-id-a> <run-id-b>
    // ═══════════════════════════════════════════════════════════════════
    @CommandLine.Command(name = "compare", description = "Compare two runs side-by-side",
            mixinStandardHelpOptions = true)
    static class CompareCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "First run ID")
        String runIdA;

        @CommandLine.Parameters(index = "1", description = "Second run ID")
        String runIdB;

        @CommandLine.Option(names = "--json", description = "Output in JSON format")
        boolean json;

        @Override
        public Integer call() {
            TerminalRenderer term = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(term);

            EvalResultStore store = new EvalResultStore();
            store.load();

            EvalRunResult runA = store.getRunById(runIdA);
            if (runA == null) {
                System.err.println("Run A not found: " + runIdA);
                return 1;
            }
            EvalRunResult runB = store.getRunById(runIdB);
            if (runB == null) {
                System.err.println("Run B not found: " + runIdB);
                return 1;
            }

            if (json) {
                try {
                    ObjectMapper jsonMapper = JsonUtils.newStandardMapper()
                            .enable(SerializationFeature.INDENT_OUTPUT);
                    Map<String, Object> out = new java.util.LinkedHashMap<>();
                    out.put("runA", runA);
                    out.put("runB", runB);
                    System.out.println(jsonMapper.writeValueAsString(out));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
                return 0;
            }

            System.out.println();

            // Summary comparison
            StringBuilder body = new StringBuilder();
            body.append(String.format("  %-20s %-30s %-30s%n", "", "Run A", "Run B"));
            body.append("  " + "-".repeat(80)).append("\n");
            body.append(String.format("  %-20s %-30s %-30s%n", "Run ID",
                    StringUtils.truncate(runA.getRunId(), 30), StringUtils.truncate(runB.getRunId(), 30)));
            body.append(String.format("  %-20s %-30s %-30s%n", "Suite",
                    runA.getSuiteName(), runB.getSuiteName()));
            body.append(String.format("  %-20s %-30s %-30s%n", "Result",
                    runA.isSuitePassed() ? term.green("PASSED") : term.red("FAILED"),
                    runB.isSuitePassed() ? term.green("PASSED") : term.red("FAILED")));
            body.append(String.format("  %-20s %-30s %-30s%n", "Pass rate",
                    String.format("%d/%d (%.0f%%)", runA.getPassedCases(), runA.getTotalCases(), runA.getPassRate() * 100),
                    String.format("%d/%d (%.0f%%)", runB.getPassedCases(), runB.getTotalCases(), runB.getPassRate() * 100)));
            body.append(String.format("  %-20s %-30s %-30s%n", "Duration",
                    runA.getTotalDurationMs() + "ms", runB.getTotalDurationMs() + "ms"));
            if (runA.getAvgCompositeScore() > 0 || runB.getAvgCompositeScore() > 0) {
                body.append(String.format("  %-20s %-30s %-30s%n", "Avg score",
                        String.format("%.1f/5.0", runA.getAvgCompositeScore()),
                        String.format("%.1f/5.0", runB.getAvgCompositeScore())));
            }

            System.out.println(ascii.panel("Comparison", body.toString().stripTrailing()));

            // Per-case diff (same suite only)
            if (runA.getSuiteName() != null && runA.getSuiteName().equals(runB.getSuiteName())) {
                Map<String, EvalCaseResult> casesA = new java.util.LinkedHashMap<>();
                for (EvalCaseResult cr : runA.getCaseResults()) casesA.put(cr.getCaseId(), cr);
                Map<String, EvalCaseResult> casesB = new java.util.LinkedHashMap<>();
                for (EvalCaseResult cr : runB.getCaseResults()) casesB.put(cr.getCaseId(), cr);

                java.util.LinkedHashMap<String, Boolean> allCases = new java.util.LinkedHashMap<>();
                casesA.keySet().forEach(k -> allCases.put(k, true));
                casesB.keySet().forEach(k -> allCases.put(k, true));

                StringBuilder diff = new StringBuilder();
                diff.append(String.format("  %-30s %-10s %-10s %s%n", "CASE", "RUN A", "RUN B", "DELTA"));
                diff.append("  " + "-".repeat(65)).append("\n");

                int improved = 0, regressed = 0, unchanged = 0;
                for (String caseId : allCases.keySet()) {
                    EvalCaseResult a = casesA.get(caseId);
                    EvalCaseResult b = casesB.get(caseId);
                    String statusA = a != null ? (a.passed() ? "PASS" : "FAIL") : "N/A";
                    String statusB = b != null ? (b.passed() ? "PASS" : "FAIL") : "N/A";
                    String delta;
                    if (statusA.equals(statusB)) {
                        delta = "=";
                        unchanged++;
                    } else if ("PASS".equals(statusB)) {
                        delta = term.green("IMPROVED");
                        improved++;
                    } else {
                        delta = term.red("REGRESSED");
                        regressed++;
                    }
                    diff.append(String.format("  %-30s %-10s %-10s %s%n",
                            StringUtils.truncate(caseId, 30), statusA, statusB, delta));
                }

                diff.append("\n  ").append(term.green(improved + " improved"))
                        .append("  ").append(term.red(regressed + " regressed"))
                        .append("  ").append(unchanged + " unchanged");

                System.out.println();
                System.out.println(ascii.panel("Per-Case Diff", diff.toString().stripTrailing()));
            }

            System.out.println();
            return 0;
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────

    public static ObjectMapper createMapper(String filename) {
        ObjectMapper mapper;
        if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
            try {
                // Use Jackson YAML if available
                Class<?> yamlFactoryClass = Class.forName(
                        "com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
                Object yamlFactory = yamlFactoryClass.getDeclaredConstructor().newInstance();
                mapper = new ObjectMapper((com.fasterxml.jackson.core.JsonFactory) yamlFactory);
            } catch (Exception e) {
                System.err.println("Warning: jackson-dataformat-yaml not on classpath, falling back to JSON parser");
                mapper = JsonUtils.standardMapper();
            }
        } else {
            mapper = JsonUtils.standardMapper();
        }
        return mapper;
    }

}
