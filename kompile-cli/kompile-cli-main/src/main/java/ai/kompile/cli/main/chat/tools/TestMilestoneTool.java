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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Language-agnostic test milestone tracker with per-directory project configuration.
 *
 * <p>Stores all data under {@code .kompile/test-milestones/} in the project directory:
 * <ul>
 *   <li>{@code config.json} — project config: modules, build commands, targets, regressions</li>
 *   <li>{@code milestones/*.json} — individual milestone snapshots</li>
 * </ul>
 *
 * <p>Milestone actions: record, fail, list, get, check, compare, latest, delete, summary
 * <p>Config actions: init, config, add_module, remove_module, set_target, status
 * <p>Regression actions: add_regression, remove_regression, list_regressions
 */
public class TestMilestoneTool implements CliTool {

    private static final String CONFIG_FILE = TestMilestoneReader.CONFIG_FILE;
    private static final String MILESTONES_SUBDIR = TestMilestoneReader.MILESTONES_DIR;
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String id() {
        return "test_milestone";
    }

    @Override
    public String description() {
        return "Track which git commits have working tests, with per-directory project configuration. " +
                "Records milestones when tests pass so you can always find the last known-good commit. " +
                "Stores config and milestones under .kompile/test-milestones/ in the project directory. " +
                "Language and build-system agnostic (Java/Maven, Python/pytest, Rust/cargo, JS/npm, etc.).\n\n" +
                "Milestone actions: record, fail, list, get, check, compare, latest, delete, summary\n" +
                "Config actions: init, config, add_module, remove_module, set_target, status\n" +
                "Regression actions: add_regression, remove_regression, list_regressions";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action: record | fail | list | get | check | compare | latest | delete | summary | " +
                "init | config | add_module | remove_module | set_target | status | " +
                "add_regression | remove_regression | list_regressions");

        // Milestone params
        addStringProp(props, "module",
                "Module name within the project (e.g. 'kompile-cli', 'backend', 'tests'). " +
                "For record/fail, tags the milestone. For list/latest, filters by module.");
        addStringProp(props, "branch",
                "Git branch name. Auto-detected from working directory if omitted.");
        addStringProp(props, "commit",
                "Git commit hash. Auto-detected (HEAD) if omitted.");
        addStringProp(props, "notes",
                "Free-form notes about what was tested or what broke.");
        addStringProp(props, "tags",
                "Comma-separated tags (e.g. 'unit,integration', 'cuda', 'cpu-only').");
        addStringProp(props, "id",
                "Milestone or regression ID for get/delete operations.");
        addStringProp(props, "from_id",
                "Source milestone ID for compare.");
        addStringProp(props, "to_id",
                "Target milestone ID for compare.");
        addIntProp(props, "limit",
                "Max number of results for list (default 20).");
        addIntProp(props, "total_tests",
                "Total number of tests run (optional).");
        addIntProp(props, "passed",
                "Number of tests passed (optional).");
        addIntProp(props, "failed",
                "Number of tests failed (optional).");
        addIntProp(props, "skipped",
                "Number of tests skipped (optional).");

        // Config params
        addStringProp(props, "project",
                "Project name (for init). Defaults to directory name.");
        addStringProp(props, "path",
                "Relative path to module directory (for add_module).");
        addStringProp(props, "build_command",
                "Shell command to build the module (for add_module/init).");
        addStringProp(props, "test_command",
                "Shell command to run tests (for add_module/init).");
        addNumberProp(props, "min_pass_rate",
                "Minimum pass rate target 0.0-1.0 (for set_target).");
        addIntProp(props, "max_failures",
                "Maximum allowed test failures (for set_target).");

        // Regression params
        addStringProp(props, "test_name",
                "Fully qualified test name for add_regression (e.g. 'com.example.FooTest#testBar').");
        addStringProp(props, "since_commit",
                "Commit hash where regression was first observed (for add_regression).");

        ArrayNode required = schema.putArray("required");
        required.add("action");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "test_milestone";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "test milestone tracking");

        String action = params.path("action").asText("");
        Path workDir = context.getWorkingDirectory();

        try {
            return switch (action) {
                // Milestone actions
                case "record"  -> doRecord(params, workDir, true);
                case "fail"    -> doRecord(params, workDir, false);
                case "list"    -> doList(params, workDir);
                case "get"     -> doGet(params, workDir);
                case "check"   -> doCheck(params, workDir);
                case "compare" -> doCompare(params, workDir);
                case "latest"  -> doLatest(params, workDir);
                case "delete"  -> doDelete(params, workDir);
                case "summary" -> doSummary(params, workDir);
                // Config actions
                case "init"          -> doInit(params, workDir);
                case "config"        -> doConfig(params, workDir);
                case "add_module"    -> doAddModule(params, workDir);
                case "remove_module" -> doRemoveModule(params, workDir);
                case "set_target"    -> doSetTarget(params, workDir);
                case "status"        -> doStatus(params, workDir);
                // Regression actions
                case "add_regression"    -> doAddRegression(params, workDir);
                case "remove_regression" -> doRemoveRegression(params, workDir);
                case "list_regressions"  -> doListRegressions(params, workDir);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Milestone: record|fail|list|get|check|compare|latest|delete|summary. " +
                        "Config: init|config|add_module|remove_module|set_target|status. " +
                        "Regressions: add_regression|remove_regression|list_regressions");
            };
        } catch (IOException e) {
            return ToolResult.error("I/O error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CONFIG ACTIONS
    // ═══════════════════════════════════════════════════════════════════════

    private ToolResult doInit(JsonNode params, Path workDir) throws IOException {
        Path configPath = configPath(workDir);
        if (Files.exists(configPath)) {
            Map<String, Object> existing = loadConfig(workDir);
            return ToolResult.success("Project already initialized",
                    "Config exists at " + configPath + "\n" + formatConfig(existing));
        }

        Files.createDirectories(configPath.getParent());
        Files.createDirectories(milestonesDir(workDir));

        String project = params.path("project").asText(null);
        if (project == null) {
            project = workDir.getFileName() != null ? workDir.getFileName().toString() : "unknown";
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("project", project);
        config.put("created", Instant.now().toString());

        // Default build/test commands if provided
        String buildCmd = params.path("build_command").asText(null);
        String testCmd = params.path("test_command").asText(null);
        if (buildCmd != null) config.put("buildCommand", buildCmd);
        if (testCmd != null) config.put("testCommand", testCmd);

        // Default targets
        Map<String, Object> targets = new LinkedHashMap<>();
        targets.put("minPassRate", 1.0);
        targets.put("maxFailures", 0);
        config.put("targets", targets);

        config.put("modules", new LinkedHashMap<>());
        config.put("knownRegressions", new ArrayList<>());
        config.put("trackedTests", new ArrayList<>());

        saveConfig(workDir, config);

        StringBuilder sb = new StringBuilder();
        sb.append("Initialized test milestone tracking for '").append(project).append("'\n");
        sb.append("Config: ").append(configPath).append("\n");
        sb.append("Milestones: ").append(milestonesDir(workDir)).append("\n\n");
        sb.append("Next steps:\n");
        sb.append("  - Add modules: action=add_module module=<name> path=<dir> test_command=<cmd>\n");
        sb.append("  - Set targets: action=set_target min_pass_rate=0.95\n");
        sb.append("  - Record a milestone: action=record\n");

        return ToolResult.success("Project initialized", sb.toString());
    }

    private ToolResult doConfig(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadConfig(workDir);
        if (config == null) {
            return ToolResult.error("No config found. Run action=init first.");
        }

        // If any update fields are provided, update them
        boolean updated = false;
        if (params.has("build_command") && !params.path("build_command").asText("").isEmpty()) {
            config.put("buildCommand", params.get("build_command").asText());
            updated = true;
        }
        if (params.has("test_command") && !params.path("test_command").asText("").isEmpty()) {
            config.put("testCommand", params.get("test_command").asText());
            updated = true;
        }
        if (params.has("project") && !params.path("project").asText("").isEmpty()) {
            config.put("project", params.get("project").asText());
            updated = true;
        }

        if (updated) {
            saveConfig(workDir, config);
            return ToolResult.success("Config updated", formatConfig(config));
        }

        return ToolResult.success("Project config", formatConfig(config));
    }

    @SuppressWarnings("unchecked")
    private ToolResult doAddModule(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadOrInitConfig(workDir);

        String name = params.path("module").asText(null);
        if (name == null || name.isEmpty()) {
            return ToolResult.error("'module' is required for add_module.");
        }

        Map<String, Object> modules = (Map<String, Object>) config.computeIfAbsent("modules",
                k -> new LinkedHashMap<>());

        Map<String, Object> mod = new LinkedHashMap<>();
        String path = params.path("path").asText(null);
        if (path != null) mod.put("path", path);

        String buildCmd = params.path("build_command").asText(null);
        if (buildCmd != null) mod.put("buildCommand", buildCmd);

        String testCmd = params.path("test_command").asText(null);
        if (testCmd != null) mod.put("testCommand", testCmd);

        // Module-level targets
        Map<String, Object> targets = new LinkedHashMap<>();
        if (params.has("min_pass_rate")) targets.put("minPassRate", params.get("min_pass_rate").asDouble());
        if (params.has("max_failures")) targets.put("maxFailures", params.get("max_failures").asInt());
        if (!targets.isEmpty()) mod.put("targets", targets);

        mod.put("added", Instant.now().toString());
        modules.put(name, mod);

        saveConfig(workDir, config);

        StringBuilder sb = new StringBuilder();
        sb.append("Added module '").append(name).append("'\n");
        if (path != null) sb.append("  Path: ").append(path).append("\n");
        if (buildCmd != null) sb.append("  Build: ").append(buildCmd).append("\n");
        if (testCmd != null) sb.append("  Test:  ").append(testCmd).append("\n");
        if (!targets.isEmpty()) sb.append("  Targets: ").append(targets).append("\n");

        return ToolResult.success("Module added", sb.toString());
    }

    @SuppressWarnings("unchecked")
    private ToolResult doRemoveModule(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadConfig(workDir);
        if (config == null) return ToolResult.error("No config found. Run action=init first.");

        String name = params.path("module").asText(null);
        if (name == null || name.isEmpty()) {
            return ToolResult.error("'module' is required for remove_module.");
        }

        Map<String, Object> modules = (Map<String, Object>) config.getOrDefault("modules", new LinkedHashMap<>());
        if (!modules.containsKey(name)) {
            return ToolResult.error("Module not found: " + name);
        }

        modules.remove(name);
        saveConfig(workDir, config);
        return ToolResult.success("Removed module '" + name + "'");
    }

    @SuppressWarnings("unchecked")
    private ToolResult doSetTarget(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadOrInitConfig(workDir);

        String moduleName = params.path("module").asText(null);

        Map<String, Object> targets;
        String scope;

        if (moduleName != null) {
            // Module-level target
            Map<String, Object> modules = (Map<String, Object>) config.computeIfAbsent("modules",
                    k -> new LinkedHashMap<>());
            Map<String, Object> mod = (Map<String, Object>) modules.computeIfAbsent(moduleName,
                    k -> new LinkedHashMap<>());
            targets = (Map<String, Object>) mod.computeIfAbsent("targets", k -> new LinkedHashMap<>());
            scope = "module '" + moduleName + "'";
        } else {
            // Project-level target
            targets = (Map<String, Object>) config.computeIfAbsent("targets", k -> new LinkedHashMap<>());
            scope = "project";
        }

        if (params.has("min_pass_rate")) targets.put("minPassRate", params.get("min_pass_rate").asDouble());
        if (params.has("max_failures")) targets.put("maxFailures", params.get("max_failures").asInt());

        saveConfig(workDir, config);

        return ToolResult.success("Targets updated for " + scope + ": " + targets);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  REGRESSION ACTIONS
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private ToolResult doAddRegression(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadOrInitConfig(workDir);

        String testName = params.path("test_name").asText(null);
        if (testName == null || testName.isEmpty()) {
            return ToolResult.error("'test_name' is required for add_regression.");
        }

        List<Map<String, Object>> regressions = (List<Map<String, Object>>) config.computeIfAbsent(
                "knownRegressions", k -> new ArrayList<>());

        // Check for duplicate
        boolean exists = regressions.stream().anyMatch(r -> testName.equals(r.get("test")));
        if (exists) {
            return ToolResult.error("Regression already tracked: " + testName);
        }

        Map<String, Object> regression = new LinkedHashMap<>();
        String regId = UUID.randomUUID().toString().substring(0, 8);
        regression.put("id", regId);
        regression.put("test", testName);

        String module = params.path("module").asText(null);
        if (module != null) regression.put("module", module);

        String sinceCommit = params.path("since_commit").asText(null);
        if (sinceCommit == null) sinceCommit = runGit(workDir, "rev-parse", "--short", "HEAD");
        regression.put("sinceCommit", sinceCommit);

        String notes = params.path("notes").asText(null);
        if (notes != null) regression.put("notes", notes);

        regression.put("added", Instant.now().toString());
        regressions.add(regression);

        saveConfig(workDir, config);

        return ToolResult.success("Regression tracked",
                "Added known regression: " + testName + "\n" +
                "  ID: " + regId + "\n" +
                (sinceCommit != null ? "  Since: " + sinceCommit + "\n" : "") +
                (module != null ? "  Module: " + module + "\n" : "") +
                (notes != null ? "  Notes: " + notes + "\n" : ""));
    }

    @SuppressWarnings("unchecked")
    private ToolResult doRemoveRegression(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadConfig(workDir);
        if (config == null) return ToolResult.error("No config found. Run action=init first.");

        String id = params.path("id").asText(null);
        String testName = params.path("test_name").asText(null);
        if ((id == null || id.isEmpty()) && (testName == null || testName.isEmpty())) {
            return ToolResult.error("'id' or 'test_name' is required for remove_regression.");
        }

        List<Map<String, Object>> regressions = (List<Map<String, Object>>) config.getOrDefault(
                "knownRegressions", new ArrayList<>());

        boolean removed = regressions.removeIf(r ->
                (id != null && id.equals(r.get("id"))) ||
                (testName != null && testName.equals(r.get("test"))));

        if (!removed) {
            return ToolResult.error("Regression not found.");
        }

        saveConfig(workDir, config);
        return ToolResult.success("Regression removed.");
    }

    @SuppressWarnings("unchecked")
    private ToolResult doListRegressions(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadConfig(workDir);
        if (config == null) return ToolResult.error("No config found. Run action=init first.");

        List<Map<String, Object>> regressions = (List<Map<String, Object>>) config.getOrDefault(
                "knownRegressions", new ArrayList<>());

        String filterModule = params.path("module").asText(null);
        if (filterModule != null) {
            regressions = regressions.stream()
                    .filter(r -> filterModule.equals(r.get("module")))
                    .collect(Collectors.toList());
        }

        if (regressions.isEmpty()) {
            return ToolResult.success("No known regressions" +
                    (filterModule != null ? " for module '" + filterModule + "'" : "") + ".");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-8s %-45s %-12s %-8s %s\n", "ID", "TEST", "MODULE", "SINCE", "NOTES"));
        sb.append("-".repeat(100)).append("\n");

        for (Map<String, Object> r : regressions) {
            sb.append(String.format("%-8s %-45s %-12s %-8s %s\n",
                    str(r.get("id")),
                    StringUtils.truncate(str(r.get("test")), 45),
                    StringUtils.truncate(str(r.get("module")), 12),
                    str(r.get("sinceCommit")),
                    StringUtils.truncate(str(r.get("notes")), 30)));
        }

        sb.append("\nTotal: ").append(regressions.size()).append(" known regressions");
        return ToolResult.success("Known regressions", sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STATUS (config + milestones + targets + regressions combined)
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private ToolResult doStatus(JsonNode params, Path workDir) throws IOException {
        Map<String, Object> config = loadConfig(workDir);
        if (config == null) {
            return ToolResult.error("No config found. Run action=init first.");
        }

        String projectName = str(config.get("project"));
        Map<String, Object> projectTargets = (Map<String, Object>) config.getOrDefault("targets",
                Map.of());
        Map<String, Object> modules = (Map<String, Object>) config.getOrDefault("modules",
                Map.of());
        List<Map<String, Object>> regressions = (List<Map<String, Object>>) config.getOrDefault(
                "knownRegressions", List.of());

        List<Map<String, Object>> milestones = loadAllMilestones(workDir);
        String currentBranch = readGitBranch(workDir);
        String currentCommit = runGit(workDir, "rev-parse", "--short", "HEAD");

        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(projectName).append("\n");
        // Outside a git repository there is no branch or commit to name.
        if (currentBranch != null) sb.append("Branch:  ").append(currentBranch).append("\n");
        if (currentCommit != null) sb.append("Commit:  ").append(currentCommit).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        // Project-level targets
        sb.append("Project targets: ");
        if (projectTargets.isEmpty()) {
            sb.append("(none set)\n");
        } else {
            sb.append("minPassRate=").append(projectTargets.getOrDefault("minPassRate", "?"));
            sb.append(" maxFailures=").append(projectTargets.getOrDefault("maxFailures", "?"));
            sb.append("\n");
        }

        // Latest milestone status
        Optional<Map<String, Object>> latestAll = milestones.stream()
                .filter(m -> currentBranch == null || currentBranch.equals(m.get("branch")))
                .max(Comparator.comparing(m -> str(m.get("timestamp"))));
        if (latestAll.isPresent()) {
            Map<String, Object> latest = latestAll.get();
            boolean passing = Boolean.TRUE.equals(latest.get("passing"));
            sb.append("\nLatest milestone: ").append(passing ? "WORKING" : "FAILING");
            String label = joined(" — ", latest.get("commitShort"), latest.get("id"));
            sb.append(label.isEmpty() ? "" : " (" + label + ")").append("\n");

            // Check against targets
            Integer total = TestMilestoneReader.count(latest, "totalTests");
            if (total != null && !projectTargets.isEmpty()) {
                int passedCount = count(latest, "passed");
                int failedCount = count(latest, "failed");
                double passRate = total > 0 ? (double) passedCount / total : 0;

                double minRate = target(projectTargets, "minPassRate", 0);
                int maxFail = (int) target(projectTargets, "maxFailures", Integer.MAX_VALUE);

                boolean meetsRate = passRate >= minRate;
                boolean meetsFailures = failedCount <= maxFail;
                sb.append("  Pass rate: ").append(String.format("%.1f%%", passRate * 100));
                sb.append(meetsRate ? " [OK]" : " [BELOW TARGET " + String.format("%.0f%%", minRate * 100) + "]");
                sb.append("\n");
                sb.append("  Failures: ").append(failedCount);
                sb.append(meetsFailures ? " [OK]" : " [ABOVE MAX " + maxFail + "]");
                sb.append("\n");
            }
        } else {
            sb.append("\nNo milestones recorded")
                    .append(currentBranch != null ? " for branch '" + currentBranch + "'" : "").append("\n");
        }

        // Module status
        if (!modules.isEmpty()) {
            sb.append("\nModules (").append(modules.size()).append("):\n");
            for (Map.Entry<String, Object> entry : modules.entrySet()) {
                String modName = entry.getKey();
                Map<String, Object> mod = (Map<String, Object>) entry.getValue();
                sb.append("  ").append(modName);
                if (mod.containsKey("path")) sb.append(" (").append(mod.get("path")).append(")");
                sb.append("\n");
                if (mod.containsKey("testCommand")) sb.append("    test:  ").append(mod.get("testCommand")).append("\n");
                if (mod.containsKey("buildCommand")) sb.append("    build: ").append(mod.get("buildCommand")).append("\n");
                if (mod.containsKey("targets")) {
                    Map<String, Object> mt = (Map<String, Object>) mod.get("targets");
                    sb.append("    targets: ").append(mt).append("\n");
                }

                // Latest milestone for this module
                Optional<Map<String, Object>> modLatest = milestones.stream()
                        .filter(m -> modName.equals(m.get("module")))
                        .filter(m -> Boolean.TRUE.equals(m.get("passing")))
                        .max(Comparator.comparing(m -> str(m.get("timestamp"))));
                if (modLatest.isPresent()) {
                    Map<String, Object> ml = modLatest.get();
                    sb.append("    last working: ").append(where(ml)).append("\n");
                }
            }
        }

        // Regressions
        if (!regressions.isEmpty()) {
            sb.append("\nKnown regressions (").append(regressions.size()).append("):\n");
            for (Map<String, Object> r : regressions) {
                sb.append("  - ").append(r.get("test"));
                if (r.containsKey("module")) sb.append(" [").append(r.get("module")).append("]");
                if (r.get("sinceCommit") != null) sb.append(" since ").append(r.get("sinceCommit"));
                sb.append("\n");
            }
        }

        // Milestone history summary
        long totalMilestones = milestones.size();
        long workingCount = milestones.stream().filter(m -> Boolean.TRUE.equals(m.get("passing"))).count();
        sb.append("\nHistory: ").append(totalMilestones).append(" milestones (")
                .append(workingCount).append(" working, ").append(totalMilestones - workingCount).append(" failing)\n");

        return ToolResult.success("Status", sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MILESTONE ACTIONS
    // ═══════════════════════════════════════════════════════════════════════

    private ToolResult doRecord(JsonNode params, Path workDir, boolean passing) throws IOException {
        Path msDir = milestonesDir(workDir);
        Files.createDirectories(msDir);
        // Auto-init config if not present
        loadOrInitConfig(workDir);

        GitInfo git = resolveGitInfo(params, workDir);
        String module = params.path("module").asText(null);
        String notes = params.path("notes").asText(null);
        String tags = params.path("tags").asText(null);

        Map<String, Object> milestone = new LinkedHashMap<>();
        String id = TestMilestoneReader.newRunId();
        milestone.put("id", id);
        milestone.put("branch", git.branch);
        milestone.put("commit", git.commitHash);
        milestone.put("commitShort", TestMilestoneReader.shortCommit(git.commitHash));
        milestone.put("commitMessage", git.commitMessage);
        milestone.put("commitAuthor", git.commitAuthor);
        milestone.put("passing", passing);

        if (module != null) milestone.put("module", module);
        if (params.has("total_tests")) milestone.put("totalTests", params.get("total_tests").asInt());
        if (params.has("passed")) milestone.put("passed", params.get("passed").asInt());
        if (params.has("failed")) milestone.put("failed", params.get("failed").asInt());
        if (params.has("skipped")) milestone.put("skipped", params.get("skipped").asInt());
        if (notes != null) milestone.put("notes", notes);
        if (tags != null) milestone.put("tags", tags);
        milestone.put("timestamp", Instant.now().toString());

        MAPPER.writeValue(msDir.resolve(id + ".json").toFile(), milestone);

        String status = passing ? "WORKING" : "FAILING";
        StringBuilder sb = new StringBuilder();
        sb.append("Recorded ").append(status).append(" milestone");
        // Outside a git repository there is no commit or branch to name.
        if (git.commitHash != null) sb.append(": ").append(milestone.get("commitShort"));
        if (git.branch != null) sb.append(" on ").append(git.branch);
        sb.append("\n");
        sb.append("ID: ").append(id).append("\n");
        if (module != null) sb.append("Module: ").append(module).append("\n");
        if (git.commitMessage != null) sb.append("Commit: ").append(git.commitMessage).append("\n");
        if (notes != null) sb.append("Notes: ").append(notes).append("\n");
        if (tags != null) sb.append("Tags: ").append(tags).append("\n");
        if (milestone.containsKey("totalTests")) {
            sb.append("Tests: ").append(milestone.get("totalTests"));
            if (milestone.containsKey("passed")) sb.append(" passed=").append(milestone.get("passed"));
            if (milestone.containsKey("failed")) sb.append(" failed=").append(milestone.get("failed"));
            if (milestone.containsKey("skipped")) sb.append(" skipped=").append(milestone.get("skipped"));
            sb.append("\n");
        }

        // Check against targets
        Map<String, Object> config = loadConfig(workDir);
        if (config != null && milestone.containsKey("totalTests")) {
            String targetCheck = checkTargets(milestone, config, module);
            if (targetCheck != null) sb.append("\n").append(targetCheck);
        }

        return ToolResult.success("Milestone recorded", sb.toString(),
                Map.of("id", id, "passing", passing));
    }

    private ToolResult doList(JsonNode params, Path workDir) throws IOException {
        List<Map<String, Object>> milestones = loadAllMilestones(workDir);

        String filterModule = params.path("module").asText(null);
        String filterBranch = params.path("branch").asText(null);
        int limit = params.path("limit").asInt(20);

        if (filterModule != null) {
            milestones = milestones.stream()
                    .filter(m -> filterModule.equals(m.get("module")))
                    .collect(Collectors.toList());
        }
        if (filterBranch != null) {
            milestones = milestones.stream()
                    .filter(m -> filterBranch.equals(m.get("branch")))
                    .collect(Collectors.toList());
        }

        milestones.sort((a, b) -> str(b.get("timestamp")).compareTo(str(a.get("timestamp"))));
        int matched = milestones.size();
        if (matched > limit) milestones = milestones.subList(0, limit);

        if (milestones.isEmpty()) {
            return ToolResult.success("No milestones found.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-8s %-7s %-20s %-15s %-7s %s\n",
                "ID", "COMMIT", "BRANCH", "MODULE", "STATUS", "DATE"));
        sb.append("-".repeat(80)).append("\n");

        for (Map<String, Object> m : milestones) {
            boolean passing = Boolean.TRUE.equals(m.get("passing"));
            sb.append(String.format("%-8s %-7s %-20s %-15s %-7s %s\n",
                    str(m.get("id")),
                    str(m.get("commitShort")),
                    StringUtils.truncate(str(m.get("branch")), 20),
                    StringUtils.truncate(str(m.get("module")), 15),
                    passing ? "PASS" : "FAIL",
                    day(m.get("timestamp"))
            ));
        }

        sb.append("\nTotal: ").append(matched).append(" milestones");
        if (matched > milestones.size()) sb.append(", newest ").append(milestones.size()).append(" shown");
        return ToolResult.success("Milestones", sb.toString());
    }

    private ToolResult doGet(JsonNode params, Path workDir) throws IOException {
        String id = params.path("id").asText(null);
        if (id == null || id.isEmpty()) return ToolResult.error("'id' is required.");

        Map<String, Object> milestone;
        try {
            milestone = loadMilestone(workDir, id);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (milestone == null) return ToolResult.error("Milestone not found: " + id);

        return ToolResult.success("Milestone " + id,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(milestone));
    }

    private ToolResult doCheck(JsonNode params, Path workDir) throws IOException {
        GitInfo git = resolveGitInfo(params, workDir);
        String module = params.path("module").asText(null);
        if (git.commitHash == null) {
            return ToolResult.error("No commit to check: pass 'commit', or run inside a git repository.");
        }

        List<Map<String, Object>> milestones = loadAllMilestones(workDir);
        List<Map<String, Object>> matches = milestones.stream()
                .filter(m -> sameCommit(git.commitHash, m.get("commit")))
                .filter(m -> module == null || module.equals(m.get("module")))
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            return ToolResult.success("No milestone for commit "
                    + TestMilestoneReader.shortCommit(git.commitHash) +
                    (module != null ? " module=" + module : ""));
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : matches) {
            boolean passing = Boolean.TRUE.equals(m.get("passing"));
            sb.append("ID: ").append(str(m.get("id")))
                    .append(" | ").append(passing ? "WORKING" : "FAILING");
            if (m.get("module") != null) sb.append(" | Module: ").append(m.get("module"));
            if (m.get("notes") != null) sb.append(" | ").append(m.get("notes"));
            sb.append("\n");
        }

        return ToolResult.success("Milestone check", sb.toString());
    }

    private ToolResult doCompare(JsonNode params, Path workDir) throws IOException {
        String fromId = params.path("from_id").asText(null);
        String toId = params.path("to_id").asText(null);
        if (fromId == null || toId == null) {
            return ToolResult.error("'from_id' and 'to_id' are required.");
        }

        Map<String, Object> from;
        Map<String, Object> to;
        try {
            from = loadMilestone(workDir, fromId);
            to = loadMilestone(workDir, toId);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (from == null) return ToolResult.error("Milestone not found: " + fromId);
        if (to == null) return ToolResult.error("Milestone not found: " + toId);

        boolean fromPassing = Boolean.TRUE.equals(from.get("passing"));
        boolean toPassing = Boolean.TRUE.equals(to.get("passing"));

        StringBuilder sb = new StringBuilder();
        sb.append("FROM: ").append(fromId).append(commitSuffix(from))
                .append(" — ").append(fromPassing ? "WORKING" : "FAILING").append("\n");
        sb.append("TO:   ").append(toId).append(commitSuffix(to))
                .append(" — ").append(toPassing ? "WORKING" : "FAILING").append("\n\n");

        if (fromPassing && !toPassing) sb.append("REGRESSION detected\n");
        else if (!fromPassing && toPassing) sb.append("FIX detected\n");
        else sb.append("Both ").append(fromPassing ? "WORKING" : "FAILING").append("\n");

        // Delta on test counts
        appendDelta(sb, from, to, "totalTests", "Test count");
        appendDelta(sb, from, to, "passed", "Passed");
        appendDelta(sb, from, to, "failed", "Failed");
        appendDelta(sb, from, to, "skipped", "Skipped");

        if (from.get("notes") != null) sb.append("\nFrom notes: ").append(from.get("notes")).append("\n");
        if (to.get("notes") != null) sb.append("To notes: ").append(to.get("notes")).append("\n");

        return ToolResult.success("Comparison", sb.toString());
    }

    private ToolResult doLatest(JsonNode params, Path workDir) throws IOException {
        String branch = params.path("branch").asText(null);
        if (branch == null) branch = readGitBranch(workDir);
        String module = params.path("module").asText(null);

        List<Map<String, Object>> milestones = loadAllMilestones(workDir);
        String filterBranch = branch;
        Optional<Map<String, Object>> latest = milestones.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("passing")))
                .filter(m -> filterBranch == null || filterBranch.equals(m.get("branch")))
                .filter(m -> module == null || module.equals(m.get("module")))
                .max(Comparator.comparing(m -> str(m.get("timestamp"))));

        if (latest.isEmpty()) {
            return ToolResult.success("No working milestone found" +
                    (branch != null ? " for branch=" + branch : "") +
                    (module != null ? " module=" + module : ""));
        }

        Map<String, Object> m = latest.get();
        StringBuilder sb = new StringBuilder();
        sb.append("Last known working commit:\n");
        // A line for each field the milestone records: sweeps and hand-written files lack some.
        appendIfPresent(sb, "  ID:      ", m.get("id"));
        Object commit = m.get("commit");
        appendIfPresent(sb, "  Commit:  ", joined(" ", m.get("commitShort"),
                commit != null && !commit.equals(m.get("commitShort")) ? "(" + commit + ")" : null));
        appendIfPresent(sb, "  Branch:  ", m.get("branch"));
        appendIfPresent(sb, "  Module:  ", m.get("module"));
        appendIfPresent(sb, "  Message: ", m.get("commitMessage"));
        appendIfPresent(sb, "  Date:    ", m.get("timestamp"));
        appendIfPresent(sb, "  Notes:   ", m.get("notes"));

        return ToolResult.success("Latest working milestone", sb.toString(),
                Map.of("commit", str(m.get("commit")), "id", str(m.get("id"))));
    }

    private ToolResult doDelete(JsonNode params, Path workDir) throws IOException {
        String id = params.path("id").asText(null);
        if (id == null || id.isEmpty()) return ToolResult.error("'id' is required.");

        Path file;
        try {
            file = TestMilestoneReader.fileFor(milestonesDir(workDir), id);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(file)) return ToolResult.error("Milestone not found: " + id);

        Files.delete(file);
        return ToolResult.success("Deleted milestone " + id);
    }

    private ToolResult doSummary(JsonNode params, Path workDir) throws IOException {
        List<Map<String, Object>> milestones = loadAllMilestones(workDir);

        if (milestones.isEmpty()) {
            return ToolResult.success("No milestones recorded. Run action=init then action=record.");
        }

        long passing = milestones.stream().filter(m -> Boolean.TRUE.equals(m.get("passing"))).count();
        long failing = milestones.size() - passing;

        Set<String> moduleNames = milestones.stream()
                .map(m -> str(m.get("module"))).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> branches = milestones.stream()
                .map(m -> str(m.get("branch"))).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        // Last working per module
        Map<String, Map<String, Object>> latestPerModule = new LinkedHashMap<>();
        milestones.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("passing")))
                .filter(m -> !str(m.get("module")).isEmpty())
                .sorted((a, b) -> str(b.get("timestamp")).compareTo(str(a.get("timestamp"))))
                .forEach(m -> latestPerModule.putIfAbsent(str(m.get("module")), m));

        // Last working overall
        Optional<Map<String, Object>> latestOverall = milestones.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("passing")))
                .max(Comparator.comparing(m -> str(m.get("timestamp"))));

        StringBuilder sb = new StringBuilder();
        sb.append("Test Milestone Summary\n");
        sb.append("======================\n\n");
        sb.append("Total milestones: ").append(milestones.size()).append("\n");
        sb.append("  Working: ").append(passing).append("\n");
        sb.append("  Failing: ").append(failing).append("\n");
        if (!moduleNames.isEmpty()) sb.append("Modules: ").append(String.join(", ", moduleNames)).append("\n");
        if (!branches.isEmpty()) sb.append("Branches: ").append(String.join(", ", branches)).append("\n");

        latestOverall.ifPresent(m -> sb.append("\nLast working: ").append(where(m)).append("\n"));

        if (!latestPerModule.isEmpty()) {
            sb.append("\nPer module:\n");
            for (Map.Entry<String, Map<String, Object>> entry : latestPerModule.entrySet()) {
                Map<String, Object> m = entry.getValue();
                sb.append("  ").append(entry.getKey()).append(": ").append(where(m)).append("\n");
            }
        }

        return ToolResult.success("Summary", sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STORAGE
    // ═══════════════════════════════════════════════════════════════════════

    /** The milestone store of a project, or of the user when given the home directory. */
    static Path storeDir(Path workDir) {
        return TestMilestoneReader.storeDir(workDir);
    }

    private Path configPath(Path workDir) {
        return storeDir(workDir).resolve(CONFIG_FILE);
    }

    private Path milestonesDir(Path workDir) {
        return storeDir(workDir).resolve(MILESTONES_SUBDIR);
    }

    private Map<String, Object> loadConfig(Path workDir) throws IOException {
        Path path = configPath(workDir);
        if (!Files.exists(path)) return null;
        return MAPPER.readValue(path.toFile(), MAP_TYPE);
    }

    private Map<String, Object> loadOrInitConfig(Path workDir) throws IOException {
        Map<String, Object> config = loadConfig(workDir);
        if (config != null) return config;

        // Auto-init with defaults
        Files.createDirectories(configPath(workDir).getParent());
        Files.createDirectories(milestonesDir(workDir));

        String project = workDir.getFileName() != null ? workDir.getFileName().toString() : "unknown";
        config = new LinkedHashMap<>();
        config.put("project", project);
        config.put("created", Instant.now().toString());
        config.put("targets", new LinkedHashMap<>(Map.of("minPassRate", 1.0, "maxFailures", 0)));
        config.put("modules", new LinkedHashMap<>());
        config.put("knownRegressions", new ArrayList<>());
        config.put("trackedTests", new ArrayList<>());
        saveConfig(workDir, config);
        return config;
    }

    private void saveConfig(Path workDir, Map<String, Object> config) throws IOException {
        Path path = configPath(workDir);
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), config);
    }

    /** Every readable milestone, in the tool's field names whichever layout wrote it. */
    private List<Map<String, Object>> loadAllMilestones(Path workDir) throws IOException {
        return new ArrayList<>(TestMilestoneReader.readAll(milestonesDir(workDir)));
    }

    /** @throws IllegalArgumentException when the id could name a file outside the store */
    private Map<String, Object> loadMilestone(Path workDir, String id) throws IOException {
        return TestMilestoneReader.read(milestonesDir(workDir), id);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TARGET CHECKING
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String checkTargets(Map<String, Object> milestone, Map<String, Object> config, String module) {
        Map<String, Object> targets = null;

        // Module-level targets take priority
        if (module != null) {
            Map<String, Object> modules = (Map<String, Object>) config.getOrDefault("modules", Map.of());
            Map<String, Object> mod = (Map<String, Object>) modules.get(module);
            if (mod != null) targets = (Map<String, Object>) mod.get("targets");
        }

        // Fall back to project-level targets
        if (targets == null) targets = (Map<String, Object>) config.get("targets");
        if (targets == null || targets.isEmpty()) return null;

        int total = count(milestone, "totalTests");
        int passedCount = count(milestone, "passed");
        int failedCount = count(milestone, "failed");

        if (total == 0) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Target check:\n");
        boolean allMet = true;
        boolean checked = false;

        if (targets.get("minPassRate") instanceof Number minPassRate) {
            checked = true;
            double minRate = minPassRate.doubleValue();
            double actual = (double) passedCount / total;
            boolean met = actual >= minRate;
            if (!met) allMet = false;
            sb.append("  Pass rate: ").append(String.format("%.1f%%", actual * 100))
                    .append(" target=").append(String.format("%.0f%%", minRate * 100))
                    .append(met ? " [MET]" : " [NOT MET]").append("\n");
        }

        if (targets.get("maxFailures") instanceof Number maxFailures) {
            checked = true;
            int max = maxFailures.intValue();
            boolean met = failedCount <= max;
            if (!met) allMet = false;
            sb.append("  Failures: ").append(failedCount)
                    .append(" max=").append(max)
                    .append(met ? " [MET]" : " [NOT MET]").append("\n");
        }

        // Targets that are not numbers (config.json can be edited by hand) check nothing.
        if (!checked) return null;

        sb.append("  Overall: ").append(allMet ? "ALL TARGETS MET" : "TARGETS NOT MET");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GIT HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private GitInfo resolveGitInfo(JsonNode params, Path workDir) {
        String commit = params.path("commit").asText(null);
        String branch = params.path("branch").asText(null);

        if (commit == null) commit = runGit(workDir, "rev-parse", "HEAD");
        if (branch == null) branch = readGitBranch(workDir);

        // The message and author are the recorded commit's, which need not be HEAD. Only a hash
        // reaches git, ahead of "--", so no value can act as an option or name a file instead.
        boolean hash = commit != null && commit.matches("[0-9a-fA-F]{4,64}");
        String message = hash ? runGit(workDir, "log", "-1", "--pretty=%s", commit, "--") : null;
        String author = hash ? runGit(workDir, "log", "-1", "--pretty=%an", commit, "--") : null;

        return new GitInfo(commit, branch, message, author);
    }

    private String readGitBranch(Path workDir) {
        return runGit(workDir, "rev-parse", "--abbrev-ref", "HEAD");
    }

    private String runGit(Path workDir, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
                // Errors share the stream, so a failed command's first line is git's error message.
                return process.exitValue() == 0 && line != null ? line.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String formatConfig(Map<String, Object> config) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(config.get("project")).append("\n");
        if (config.containsKey("buildCommand")) sb.append("Build: ").append(config.get("buildCommand")).append("\n");
        if (config.containsKey("testCommand")) sb.append("Test:  ").append(config.get("testCommand")).append("\n");
        if (config.containsKey("targets")) sb.append("Targets: ").append(config.get("targets")).append("\n");

        Map<String, Object> modules = (Map<String, Object>) config.getOrDefault("modules", Map.of());
        if (!modules.isEmpty()) {
            sb.append("Modules (").append(modules.size()).append("):\n");
            for (Map.Entry<String, Object> entry : modules.entrySet()) {
                Map<String, Object> mod = (Map<String, Object>) entry.getValue();
                sb.append("  ").append(entry.getKey());
                if (mod.containsKey("path")) sb.append(" -> ").append(mod.get("path"));
                sb.append("\n");
                if (mod.containsKey("testCommand")) sb.append("    test: ").append(mod.get("testCommand")).append("\n");
                if (mod.containsKey("buildCommand")) sb.append("    build: ").append(mod.get("buildCommand")).append("\n");
            }
        }

        List<?> regressions = (List<?>) config.getOrDefault("knownRegressions", List.of());
        if (!regressions.isEmpty()) {
            sb.append("Known regressions: ").append(regressions.size()).append("\n");
        }

        return sb.toString();
    }

    private static void appendDelta(StringBuilder sb, Map<String, Object> from, Map<String, Object> to,
                                     String key, String label) {
        Integer fromVal = TestMilestoneReader.count(from, key);
        Integer toVal = TestMilestoneReader.count(to, key);
        if (fromVal != null && toVal != null && !fromVal.equals(toVal)) {
            int delta = toVal - fromVal;
            sb.append(label).append(": ").append(fromVal).append(" -> ").append(toVal)
                    .append(" (").append(delta > 0 ? "+" : "").append(delta).append(")\n");
        }
    }

    /** A count the milestone records, or 0 when it records none (see {@link TestMilestoneReader#count}). */
    private static int count(Map<String, Object> milestone, String key) {
        Integer value = TestMilestoneReader.count(milestone, key);
        return value != null ? value : 0;
    }

    /** A target from config.json, or {@code otherwise} when it is unset or not a number. */
    private static double target(Map<String, Object> targets, String key, double otherwise) {
        return targets.get(key) instanceof Number n ? n.doubleValue() : otherwise;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    /** The date of an ISO timestamp; sweep results and hand-written files may have none. */
    private static String day(Object timestamp) {
        String text = str(timestamp);
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    /** The values a milestone records, joined; null and empty ones are left out. */
    private static String joined(String separator, Object... values) {
        return Arrays.stream(values).map(TestMilestoneTool::str).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(separator));
    }

    /** "abc1234 on main (2026-10-03)": as much of a milestone's commit, branch and date as it records. */
    private static String where(Map<String, Object> m) {
        String branch = str(m.get("branch"));
        String day = day(m.get("timestamp"));
        String where = joined(" ", m.get("commitShort"), branch.isEmpty() ? null : "on " + branch,
                day.isEmpty() ? null : "(" + day + ")");
        return where.isEmpty() ? "(no commit, branch or date recorded)" : where;
    }

    /** " (abc1234)" for a milestone that records its commit, else nothing. */
    private static String commitSuffix(Map<String, Object> m) {
        String commit = str(m.get("commitShort"));
        return commit.isEmpty() ? "" : " (" + commit + ")";
    }

    /** Appends a labelled line when the milestone records the value. */
    private static void appendIfPresent(StringBuilder sb, String label, Object value) {
        if (!str(value).isEmpty()) sb.append(label).append(value).append("\n");
    }

    /** True when two hashes name the same commit; sweeps record abbreviated ones. */
    private static boolean sameCommit(String commit, Object recorded) {
        String other = str(recorded);
        if (commit.equalsIgnoreCase(other)) return true;
        // A label such as "uncommitted" matches only itself; hashes match on git's default
        // seven-digit abbreviation or more.
        int n = Math.min(commit.length(), other.length());
        return n >= 7 && commit.matches("[0-9a-fA-F]+") && other.matches("[0-9a-fA-F]+")
                && commit.regionMatches(true, 0, other, 0, n);
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

    record GitInfo(String commitHash, String branch, String commitMessage, String commitAuthor) {}
}
