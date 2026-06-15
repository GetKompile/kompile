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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.harness.eval.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool for managing agent evaluation suites and viewing results.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code list_suites} — list all known suite names with latest pass/fail</li>
 *   <li>{@code list_runs} — list recent runs, optionally filtered by suite</li>
 *   <li>{@code show_run} — show detailed results for a specific run</li>
 *   <li>{@code inspect_suite} — validate and summarize a suite file</li>
 *   <li>{@code create_suite} — scaffold a new eval suite file</li>
 *   <li>{@code delete_run} — delete a stored run result by ID</li>
 *   <li>{@code compare_runs} — compare two runs side-by-side</li>
 *   <li>{@code trend} — show pass rate trend for a suite</li>
 * </ul>
 */
public class EvalTool implements CliTool {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    static {
        JSON_MAPPER.registerModule(new JavaTimeModule());
        JSON_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JSON_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public String id() {
        return "eval";
    }

    @Override
    public String description() {
        return "Manage agent evaluation suites — list suites, view run results, inspect/create suite files, compare runs, and track trends.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = JSON_MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action to perform: list_suites, list_runs, show_run, inspect_suite, create_suite, delete_run, compare_runs, trend");
        addStringProp(props, "suite_name", "Suite name for filtering (list_runs, trend)");
        addStringProp(props, "suite_file", "Path to suite file (inspect_suite, create_suite)");
        addStringProp(props, "run_id", "Run ID (show_run, delete_run)");
        addStringProp(props, "run_id_a", "First run ID for comparison (compare_runs)");
        addStringProp(props, "run_id_b", "Second run ID for comparison (compare_runs)");
        addIntProp(props, "limit", "Max results to return (list_runs, trend). Default: 10");
        addStringProp(props, "name", "Suite name for create_suite");
        addStringProp(props, "agent", "Agent name for create_suite");
        addNumberProp(props, "required_pass_rate", "Required pass rate for create_suite (0.0-1.0). Default: 0.8");

        ArrayNode required = schema.putArray("required");
        required.add("action");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "eval";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "eval management");

        String action = params.has("action") ? params.get("action").asText() : null;
        if (action == null || action.isEmpty()) {
            return ToolResult.error("Missing required parameter: action");
        }

        return switch (action) {
            case "list_suites" -> doListSuites();
            case "list_runs" -> doListRuns(params);
            case "show_run" -> doShowRun(params);
            case "inspect_suite" -> doInspectSuite(params, context);
            case "create_suite" -> doCreateSuite(params, context);
            case "delete_run" -> doDeleteRun(params);
            case "compare_runs" -> doCompareRuns(params);
            case "trend" -> doTrend(params);
            default -> ToolResult.error("Unknown action: " + action
                    + ". Valid: list_suites, list_runs, show_run, inspect_suite, create_suite, delete_run, compare_runs, trend");
        };
    }

    // ── list_suites ──────────────────────────────────────────────────────

    private ToolResult doListSuites() {
        EvalResultStore store = loadStore();

        List<String> suites = store.getSuiteNames();
        if (suites.isEmpty()) {
            return ToolResult.success("eval", "No evaluation suites found. Run 'kompile eval run <suite.yaml>' or use the create_suite action to get started.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Known evaluation suites:\n\n");
        for (String suite : suites) {
            EvalRunResult latest = store.getLatestRun(suite);
            if (latest != null) {
                sb.append(String.format("  %-30s %s  %d/%d (%.0f%%)  runs: %d  last: %s\n",
                        suite,
                        latest.isSuitePassed() ? "PASS" : "FAIL",
                        latest.getPassedCases(), latest.getTotalCases(),
                        latest.getPassRate() * 100,
                        store.getRecentRuns(suite, Integer.MAX_VALUE).size(),
                        latest.getStartTime()));
            }
        }
        return ToolResult.success("eval", sb.toString().stripTrailing());
    }

    // ── list_runs ────────────────────────────────────────────────────────

    private ToolResult doListRuns(JsonNode params) {
        EvalResultStore store = loadStore();

        String suiteName = textOrNull(params, "suite_name");
        int limit = params.has("limit") ? params.get("limit").asInt(10) : 10;

        List<EvalRunResult> runs = store.getRecentRuns(suiteName, limit);
        if (runs.isEmpty()) {
            return ToolResult.success("eval", "No runs found" +
                    (suiteName != null ? " for suite '" + suiteName + "'" : "") + ".");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Recent runs%s (%d):\n\n",
                suiteName != null ? " for '" + suiteName + "'" : "", runs.size()));
        sb.append(String.format("  %-36s %-20s %s  %-12s %s\n",
                "RUN ID", "SUITE", "PASS", "RATE", "TIME"));
        sb.append("  " + "-".repeat(96) + "\n");
        for (EvalRunResult r : runs) {
            sb.append(String.format("  %-36s %-20s %s  %d/%d (%.0f%%)  %dms  %s\n",
                    truncate(r.getRunId(), 36),
                    truncate(r.getSuiteName(), 20),
                    r.isSuitePassed() ? "PASS" : "FAIL",
                    r.getPassedCases(), r.getTotalCases(),
                    r.getPassRate() * 100,
                    r.getTotalDurationMs(),
                    r.getStartTime()));
        }
        return ToolResult.success("eval", sb.toString().stripTrailing());
    }

    // ── show_run ─────────────────────────────────────────────────────────

    private ToolResult doShowRun(JsonNode params) {
        String runId = textOrNull(params, "run_id");
        if (runId == null) {
            return ToolResult.error("Missing required parameter: run_id");
        }

        EvalResultStore store = loadStore();
        EvalRunResult run = store.getRunById(runId);
        if (run == null) {
            return ToolResult.error("Run not found: " + runId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Run: ").append(run.getRunId()).append("\n");
        sb.append("Suite: ").append(run.getSuiteName()).append("\n");
        sb.append("File: ").append(run.getSuiteFile()).append("\n");
        sb.append("Result: ").append(run.isSuitePassed() ? "PASSED" : "FAILED").append("\n");
        sb.append(String.format("Pass rate: %d/%d (%.0f%%) — required: %.0f%%\n",
                run.getPassedCases(), run.getTotalCases(),
                run.getPassRate() * 100, run.getRequiredPassRate() * 100));
        sb.append("Duration: ").append(run.getTotalDurationMs()).append("ms\n");
        sb.append("Started: ").append(run.getStartTime()).append("\n");
        sb.append("Ended: ").append(run.getEndTime()).append("\n");

        if (run.getTotalTokens() > 0) {
            sb.append("Tokens: ").append(run.getTotalTokens()).append("\n");
        }
        if (run.getAvgCompositeScore() > 0) {
            sb.append(String.format("Avg score: %.1f/5.0\n", run.getAvgCompositeScore()));
        }
        if (run.getAvgJudgeCorrectness() > 0) {
            sb.append(String.format("Judge correctness: %.1f/5.0\n", run.getAvgJudgeCorrectness()));
            sb.append(String.format("Judge completeness: %.1f/5.0\n", run.getAvgJudgeCompleteness()));
        }

        if (!run.getOutcomeCounts().isEmpty()) {
            sb.append("\nOutcomes:\n");
            run.getOutcomeCounts().forEach((k, v) ->
                    sb.append(String.format("  %-12s %d\n", k, v)));
        }

        sb.append("\nCase Results:\n");
        sb.append(String.format("  %-30s %-10s %-12s %s\n", "CASE", "RESULT", "OUTCOME", "TIME"));
        sb.append("  " + "-".repeat(80) + "\n");
        for (EvalCaseResult cr : run.getCaseResults()) {
            sb.append(String.format("  %-30s %-10s %-12s %dms\n",
                    truncate(cr.getCaseId(), 30),
                    cr.passed() ? "PASS" : "FAIL",
                    cr.getOutcome(),
                    cr.getExecutionTimeMs()));

            if (!cr.passed() && cr.getOutcomeReason() != null) {
                sb.append("    Reason: ").append(cr.getOutcomeReason()).append("\n");
            }
            if (cr.getJudgeReasoning() != null) {
                sb.append("    Judge: ").append(cr.getJudgeReasoning()).append("\n");
            }

            for (AssertionResult ar : cr.getAssertionResults()) {
                if (!ar.passed()) {
                    sb.append("    ").append(ar.critical() ? "FAIL" : "WARN")
                            .append(" ").append(ar.description());
                    if (ar.detail() != null) sb.append(" — ").append(ar.detail());
                    sb.append("\n");
                }
            }
        }

        return ToolResult.success("eval", sb.toString().stripTrailing());
    }

    // ── inspect_suite ────────────────────────────────────────────────────

    private ToolResult doInspectSuite(JsonNode params, ToolContext context) {
        String suiteFilePath = textOrNull(params, "suite_file");
        if (suiteFilePath == null) {
            return ToolResult.error("Missing required parameter: suite_file");
        }

        Path resolved = context.getWorkingDirectory().resolve(suiteFilePath).normalize();
        File suiteFile = resolved.toFile();
        if (!suiteFile.exists()) {
            return ToolResult.error("Suite file not found: " + resolved);
        }

        ObjectMapper mapper = EvalCommand.createMapper(suiteFile.getName());
        EvalSuite suite;
        try {
            suite = mapper.readValue(suiteFile, EvalSuite.class);
        } catch (IOException e) {
            return ToolResult.error("Failed to parse suite file: " + e.getMessage());
        }

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (suite.getName() == null || suite.getName().isBlank()) {
            errors.add("Suite has no name");
        }
        if (suite.getCases() == null || suite.getCases().isEmpty()) {
            errors.add("Suite has no cases");
        }
        if (suite.getRequiredPassRate() < 0 || suite.getRequiredPassRate() > 1) {
            errors.add("requiredPassRate must be between 0.0 and 1.0, got: " + suite.getRequiredPassRate());
        }
        if (suite.getAgent() == null) {
            warnings.add("No default agent set — each case must specify its own");
        }

        int enabled = 0;
        int disabled = 0;
        int totalAssertions = 0;
        Map<String, Integer> assertionTypes = new LinkedHashMap<>();

        if (suite.getCases() != null) {
            for (int i = 0; i < suite.getCases().size(); i++) {
                EvalCase c = suite.getCases().get(i);
                if (c.isEnabled()) enabled++;
                else disabled++;

                if (c.getId() == null || c.getId().isBlank()) {
                    errors.add("Case " + i + " has no id");
                }
                if (c.getPrompt() == null || c.getPrompt().isBlank()) {
                    errors.add("Case '" + c.getId() + "' has no prompt");
                }
                if (c.getAssertions() == null || c.getAssertions().isEmpty()) {
                    warnings.add("Case '" + c.getId() + "' has no assertions");
                } else {
                    totalAssertions += c.getAssertions().size();
                    for (Assertion a : c.getAssertions()) {
                        assertionTypes.merge(a.getType().name(), 1, Integer::sum);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Suite: ").append(suite.getName()).append("\n");
        if (suite.getDescription() != null) {
            sb.append("Description: ").append(suite.getDescription()).append("\n");
        }
        sb.append("File: ").append(resolved).append("\n");
        sb.append("Agent: ").append(suite.getAgent() != null ? suite.getAgent() : "(not set)").append("\n");
        sb.append("Model: ").append(suite.getModel() != null ? suite.getModel() : "(not set)").append("\n");
        sb.append(String.format("Required pass rate: %.0f%%\n", suite.getRequiredPassRate() * 100));
        sb.append(String.format("Cases: %d enabled, %d disabled, %d total\n",
                enabled, disabled, enabled + disabled));
        sb.append("Total assertions: ").append(totalAssertions).append("\n");

        if (!assertionTypes.isEmpty()) {
            sb.append("\nAssertion types:\n");
            assertionTypes.forEach((k, v) -> sb.append(String.format("  %-30s %d\n", k, v)));
        }

        if (!errors.isEmpty()) {
            sb.append("\nErrors (").append(errors.size()).append("):\n");
            errors.forEach(e -> sb.append("  ERROR: ").append(e).append("\n"));
        }
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings (").append(warnings.size()).append("):\n");
            warnings.forEach(w -> sb.append("  WARN: ").append(w).append("\n"));
        }

        String status = errors.isEmpty() ? "VALID" : "INVALID";
        sb.insert(0, "Inspection: " + status + "\n\n");

        return ToolResult.success("eval", sb.toString().stripTrailing());
    }

    // ── create_suite ─────────────────────────────────────────────────────

    private ToolResult doCreateSuite(JsonNode params, ToolContext context) {
        String suiteFilePath = textOrNull(params, "suite_file");
        if (suiteFilePath == null) {
            return ToolResult.error("Missing required parameter: suite_file");
        }

        Path resolved = context.getWorkingDirectory().resolve(suiteFilePath).normalize();
        if (Files.exists(resolved)) {
            return ToolResult.error("File already exists: " + resolved + ". Use inspect_suite to validate it.");
        }

        String name = params.has("name") ? params.get("name").asText() : "New Eval Suite";
        String agent = params.has("agent") ? params.get("agent").asText() : "claude";
        double passRate = params.has("required_pass_rate")
                ? params.get("required_pass_rate").asDouble(0.8) : 0.8;

        boolean isYaml = suiteFilePath.endsWith(".yaml") || suiteFilePath.endsWith(".yml");

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
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
        } catch (IOException e) {
            return ToolResult.error("Failed to write suite file: " + e.getMessage());
        }

        return ToolResult.success("eval",
                "Created eval suite: " + resolved + "\n\n" +
                        "Edit the file to add your test cases, then run with:\n" +
                        "  kompile eval run " + suiteFilePath);
    }

    // ── delete_run ───────────────────────────────────────────────────────

    private ToolResult doDeleteRun(JsonNode params) {
        String runId = textOrNull(params, "run_id");
        if (runId == null) {
            return ToolResult.error("Missing required parameter: run_id");
        }

        EvalResultStore store = loadStore();
        boolean deleted = store.deleteRun(runId);
        if (deleted) {
            return ToolResult.success("eval", "Deleted run: " + runId);
        } else {
            return ToolResult.error("Run not found: " + runId);
        }
    }

    // ── compare_runs ─────────────────────────────────────────────────────

    private ToolResult doCompareRuns(JsonNode params) {
        String runIdA = textOrNull(params, "run_id_a");
        String runIdB = textOrNull(params, "run_id_b");
        if (runIdA == null || runIdB == null) {
            return ToolResult.error("Missing required parameters: run_id_a and run_id_b");
        }

        EvalResultStore store = loadStore();
        EvalRunResult runA = store.getRunById(runIdA);
        EvalRunResult runB = store.getRunById(runIdB);

        if (runA == null) return ToolResult.error("Run A not found: " + runIdA);
        if (runB == null) return ToolResult.error("Run B not found: " + runIdB);

        StringBuilder sb = new StringBuilder();
        sb.append("Comparison:\n\n");
        sb.append(String.format("  %-25s %-30s %-30s\n", "", "Run A", "Run B"));
        sb.append("  " + "-".repeat(85) + "\n");
        sb.append(String.format("  %-25s %-30s %-30s\n", "Run ID",
                truncate(runA.getRunId(), 30), truncate(runB.getRunId(), 30)));
        sb.append(String.format("  %-25s %-30s %-30s\n", "Suite",
                runA.getSuiteName(), runB.getSuiteName()));
        sb.append(String.format("  %-25s %-30s %-30s\n", "Result",
                runA.isSuitePassed() ? "PASSED" : "FAILED",
                runB.isSuitePassed() ? "PASSED" : "FAILED"));
        sb.append(String.format("  %-25s %-30s %-30s\n", "Pass rate",
                String.format("%d/%d (%.0f%%)", runA.getPassedCases(), runA.getTotalCases(), runA.getPassRate() * 100),
                String.format("%d/%d (%.0f%%)", runB.getPassedCases(), runB.getTotalCases(), runB.getPassRate() * 100)));
        sb.append(String.format("  %-25s %-30s %-30s\n", "Duration",
                runA.getTotalDurationMs() + "ms", runB.getTotalDurationMs() + "ms"));
        sb.append(String.format("  %-25s %-30s %-30s\n", "Tokens",
                runA.getTotalTokens(), runB.getTotalTokens()));
        sb.append(String.format("  %-25s %-30s %-30s\n", "Started",
                String.valueOf(runA.getStartTime()), String.valueOf(runB.getStartTime())));

        if (runA.getAvgCompositeScore() > 0 || runB.getAvgCompositeScore() > 0) {
            sb.append(String.format("  %-25s %-30s %-30s\n", "Avg score",
                    String.format("%.1f/5.0", runA.getAvgCompositeScore()),
                    String.format("%.1f/5.0", runB.getAvgCompositeScore())));
        }

        // Per-case diff for same suite
        if (runA.getSuiteName() != null && runA.getSuiteName().equals(runB.getSuiteName())) {
            Map<String, EvalCaseResult> casesA = new LinkedHashMap<>();
            for (EvalCaseResult cr : runA.getCaseResults()) casesA.put(cr.getCaseId(), cr);
            Map<String, EvalCaseResult> casesB = new LinkedHashMap<>();
            for (EvalCaseResult cr : runB.getCaseResults()) casesB.put(cr.getCaseId(), cr);

            sb.append("\nPer-case diff:\n");
            sb.append(String.format("  %-30s %-12s %-12s %s\n", "CASE", "RUN A", "RUN B", "DELTA"));
            sb.append("  " + "-".repeat(70) + "\n");

            // Union of all case IDs
            LinkedHashMap<String, Boolean> allCases = new LinkedHashMap<>();
            casesA.keySet().forEach(k -> allCases.put(k, true));
            casesB.keySet().forEach(k -> allCases.put(k, true));

            for (String caseId : allCases.keySet()) {
                EvalCaseResult a = casesA.get(caseId);
                EvalCaseResult b = casesB.get(caseId);
                String statusA = a != null ? (a.passed() ? "PASS" : "FAIL") : "N/A";
                String statusB = b != null ? (b.passed() ? "PASS" : "FAIL") : "N/A";
                String delta = statusA.equals(statusB) ? "=" :
                        ("PASS".equals(statusB) ? "IMPROVED" : "REGRESSED");
                sb.append(String.format("  %-30s %-12s %-12s %s\n",
                        truncate(caseId, 30), statusA, statusB, delta));
            }
        }

        return ToolResult.success("eval", sb.toString().stripTrailing());
    }

    // ── trend ────────────────────────────────────────────────────────────

    private ToolResult doTrend(JsonNode params) {
        String suiteName = textOrNull(params, "suite_name");
        if (suiteName == null) {
            return ToolResult.error("Missing required parameter: suite_name");
        }

        int buckets = params.has("limit") ? params.get("limit").asInt(10) : 10;

        EvalResultStore store = loadStore();
        double[] trend = store.getPassRateTrend(suiteName, buckets);
        if (trend.length == 0) {
            return ToolResult.error("No runs found for suite: " + suiteName);
        }

        List<EvalRunResult> runs = store.getRecentRuns(suiteName, buckets);

        StringBuilder sb = new StringBuilder();
        sb.append("Pass rate trend for '").append(suiteName).append("':\n\n");

        for (int i = 0; i < trend.length; i++) {
            int barLen = (int) (trend[i] * 40);
            String bar = "█".repeat(barLen) + "░".repeat(40 - barLen);
            String runTime = i < runs.size() && runs.get(i).getStartTime() != null
                    ? runs.get(i).getStartTime().toString() : "?";
            sb.append(String.format("  %3.0f%% %s  %s\n", trend[i] * 100, bar, runTime));
        }

        // Summary
        double avg = 0;
        for (double v : trend) avg += v;
        avg /= trend.length;
        double latest = trend[trend.length - 1];
        double oldest = trend[0];
        String direction = latest > oldest ? "IMPROVING" : latest < oldest ? "DECLINING" : "STABLE";

        sb.append(String.format("\n  Average: %.0f%%  Latest: %.0f%%  Direction: %s  (%d runs)\n",
                avg * 100, latest * 100, direction, trend.length));

        return ToolResult.success("eval", sb.toString().stripTrailing());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private EvalResultStore loadStore() {
        EvalResultStore store = new EvalResultStore();
        store.load();
        return store;
    }

    private static String textOrNull(JsonNode params, String field) {
        if (params.has(field) && !params.get(field).isNull()) {
            String val = params.get(field).asText();
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void addStringProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "string");
        prop.put("description", desc);
    }

    private static void addIntProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "integer");
        prop.put("description", desc);
    }

    private static void addNumberProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "number");
        prop.put("description", desc);
    }
}
