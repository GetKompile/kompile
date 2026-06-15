package ai.kompile.app.tools;

import ai.kompile.testmilestone.domain.TestMilestoneEntity;
import ai.kompile.testmilestone.service.TestMilestoneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class TestMilestoneTool {

    private final TestMilestoneService milestoneService;

    @Autowired
    public TestMilestoneTool(@Autowired(required = false) TestMilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    // --- Input records ---

    public record RecordMilestoneInput(String projectPath, String moduleName, String tags) {}
    public record RecordManualMilestoneInput(String commitHash, String branch, String moduleName,
                                             int totalTests, int passed, int failed,
                                             int skipped, int errors, long durationMs, String tags) {}
    public record GetMilestoneInput(String id) {}
    public record GetByCommitInput(String commitHash) {}
    public record GetLatestInput(String branch, String moduleName) {}
    public record ListMilestonesInput(String branch, String moduleName, Integer page, Integer size) {}
    public record CompareMilestonesInput(String fromId, String toId) {}
    public record FindRegressionsInput(String branch, String moduleName) {}
    public record TestHistoryInput(String className, String methodName, String moduleName) {}
    public record TrendInput(String moduleName, Integer days) {}
    public record DeleteMilestoneInput(String id) {}
    public record SummaryInput() {}

    // --- Tools ---

    @Tool(name = "record_test_milestone",
            description = "Record a test milestone by scanning Maven Surefire XML reports from a project directory. " +
                    "Captures git commit info and all test pass/fail results at the current commit. " +
                    "Use after running 'mvn test' to snapshot which tests pass.")
    public Map<String, Object> recordMilestone(RecordMilestoneInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.moduleName() == null) return Map.of("status", "error", "error", "moduleName is required");
            Path projectRoot = Paths.get(input.projectPath() != null ? input.projectPath() : ".");
            TestMilestoneEntity milestone = milestoneService.recordMilestone(projectRoot, input.moduleName(), input.tags());
            if (milestone == null) return Map.of("status", "error", "error", "No test results found in surefire-reports");
            return Map.of("status", "success", "milestoneId", milestone.getId(),
                    "commitHash", milestone.getCommitHash(), "passRate", milestone.getPassRate(),
                    "total", milestone.getTotalTests(), "passed", milestone.getPassed(),
                    "failed", milestone.getFailed(), "milestoneStatus", milestone.getStatus().name());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "record_manual_test_milestone",
            description = "Record a test milestone with manually provided data (no surefire parsing). " +
                    "Use when test results come from a non-Maven source or you want to log results without surefire reports.")
    public Map<String, Object> recordManualMilestone(RecordManualMilestoneInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.commitHash() == null) return Map.of("status", "error", "error", "commitHash is required");
            if (input.moduleName() == null) return Map.of("status", "error", "error", "moduleName is required");
            TestMilestoneEntity milestone = milestoneService.recordManualMilestone(
                    input.commitHash(), input.branch(), input.moduleName(),
                    input.totalTests(), input.passed(), input.failed(),
                    input.skipped(), input.errors(), input.durationMs(), input.tags());
            return Map.of("status", "success", "milestoneId", milestone.getId(),
                    "milestoneStatus", milestone.getStatus().name());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_test_milestone",
            description = "Get a test milestone by ID including all individual test case results. " +
                    "Shows commit info, pass/fail counts, and each test's status.")
    public Map<String, Object> getMilestone(GetMilestoneInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            return milestoneService.getMilestone(input.id())
                    .map(m -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.put("id", m.getId());
                        result.put("commitHash", m.getCommitHash());
                        result.put("branch", m.getBranch());
                        result.put("commitMessage", m.getCommitMessage());
                        result.put("moduleName", m.getModuleName());
                        result.put("milestoneStatus", m.getStatus().name());
                        result.put("passRate", m.getPassRate());
                        result.put("total", m.getTotalTests());
                        result.put("passed", m.getPassed());
                        result.put("failed", m.getFailed());
                        result.put("skipped", m.getSkipped());
                        result.put("errors", m.getErrors());
                        result.put("durationMs", m.getDurationMs());
                        List<Map<String, Object>> testCases = new ArrayList<>();
                        for (var tc : m.getTestCaseResults()) {
                            Map<String, Object> tcMap = new LinkedHashMap<>();
                            tcMap.put("name", tc.getFullyQualifiedName());
                            tcMap.put("status", tc.getStatus().name());
                            tcMap.put("durationMs", tc.getDurationMs());
                            if (tc.getErrorMessage() != null) tcMap.put("error", tc.getErrorMessage());
                            testCases.add(tcMap);
                        }
                        result.put("testCases", testCases);
                        return result;
                    })
                    .orElse(Map.of("status", "error", "error", "Milestone not found"));
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_milestones_by_commit",
            description = "Get all test milestones recorded at a specific git commit hash. " +
                    "Returns milestone summaries for all modules tested at that commit.")
    public Map<String, Object> getMilestonesByCommit(GetByCommitInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.commitHash() == null) return Map.of("status", "error", "error", "commitHash is required");
            List<TestMilestoneEntity> milestones = milestoneService.getMilestonesByCommit(input.commitHash());
            return Map.of("status", "success", "count", milestones.size(),
                    "milestones", milestones.stream().map(this::toSummary).toList());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_latest_test_milestone",
            description = "Get the most recent test milestone for a specific branch and module. " +
                    "Quick way to check current test health.")
    public Map<String, Object> getLatestMilestone(GetLatestInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.branch() == null || input.moduleName() == null)
                return Map.of("status", "error", "error", "branch and moduleName are required");
            return milestoneService.getLatestMilestone(input.branch(), input.moduleName())
                    .map(m -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.putAll(toSummary(m));
                        return result;
                    })
                    .orElse(Map.of("status", "success", "message", "No milestones found for this branch/module"));
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_test_milestones",
            description = "List test milestones with optional filtering by branch and module name. " +
                    "Supports pagination. Returns summary info for each milestone.")
    public Map<String, Object> listMilestones(ListMilestonesInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            int page = input.page() != null ? input.page() : 0;
            int size = input.size() != null ? input.size() : 20;
            var result = milestoneService.listMilestones(input.branch(), input.moduleName(), page, size);
            return Map.of("status", "success", "total", result.getTotalElements(),
                    "page", result.getNumber(), "pages", result.getTotalPages(),
                    "milestones", result.getContent().stream().map(this::toSummary).toList());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "compare_test_milestones",
            description = "Compare two test milestones to see what changed: new failures (regressions), " +
                    "fixed tests, added tests, removed tests, and pass rate delta. " +
                    "Essential for understanding impact of code changes on test health.")
    public Map<String, Object> compareMilestones(CompareMilestonesInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.fromId() == null || input.toId() == null)
                return Map.of("status", "error", "error", "fromId and toId are required");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(milestoneService.compareMilestones(input.fromId(), input.toId()));
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "find_test_regressions",
            description = "Find test regressions by comparing the two most recent milestones for a branch/module. " +
                    "Shows tests that were passing but now fail, indicating broken code.")
    public Map<String, Object> findRegressions(FindRegressionsInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.branch() == null || input.moduleName() == null)
                return Map.of("status", "error", "error", "branch and moduleName are required");
            List<Map<String, Object>> regressions = milestoneService.findRegressions(input.branch(), input.moduleName());
            return Map.of("status", "success", "regressionCount", regressions.size(), "regressions", regressions);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_test_case_history",
            description = "Get the pass/fail history of a specific test case across milestones. " +
                    "Shows when a test started failing or was fixed. Provide className and methodName.")
    public Map<String, Object> getTestCaseHistory(TestHistoryInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.className() == null || input.methodName() == null)
                return Map.of("status", "error", "error", "className and methodName are required");
            List<Map<String, Object>> history = milestoneService.getTestCaseHistory(
                    input.className(), input.methodName(), input.moduleName());
            return Map.of("status", "success", "testName", input.className() + "#" + input.methodName(),
                    "entries", history.size(), "history", history);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_test_pass_rate_trend",
            description = "Get the pass rate trend for a module over time. " +
                    "Returns a time series of pass rates to visualize test health trajectory. " +
                    "Default last 30 days, adjustable via 'days' parameter.")
    public Map<String, Object> getPassRateTrend(TrendInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.moduleName() == null) return Map.of("status", "error", "error", "moduleName is required");
            int days = input.days() != null ? input.days() : 30;
            Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
            Instant to = Instant.now();
            List<Map<String, Object>> trend = milestoneService.getPassRateTrend(input.moduleName(), from, to);
            return Map.of("status", "success", "moduleName", input.moduleName(),
                    "days", days, "dataPoints", trend.size(), "trend", trend);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_test_milestone_summary",
            description = "Get an overall summary of test milestone data: total milestones, " +
                    "pass/fail/partial counts, tracked modules, and branches.")
    public Map<String, Object> getSummary(SummaryInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(milestoneService.getSummary());
            return result;
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_test_milestone",
            description = "Delete a test milestone by ID.")
    public Map<String, Object> deleteMilestone(DeleteMilestoneInput input) {
        try {
            if (milestoneService == null) return Map.of("status", "error", "error", "Service not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            milestoneService.deleteMilestone(input.id());
            return Map.of("status", "success");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // --- Helpers ---

    private Map<String, Object> toSummary(TestMilestoneEntity m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", m.getId());
        s.put("commitHash", m.getCommitHash());
        s.put("branch", m.getBranch());
        s.put("commitMessage", m.getCommitMessage());
        s.put("moduleName", m.getModuleName());
        s.put("milestoneStatus", m.getStatus().name());
        s.put("passRate", m.getPassRate());
        s.put("total", m.getTotalTests());
        s.put("passed", m.getPassed());
        s.put("failed", m.getFailed());
        s.put("createdAt", m.getCreatedAt());
        return s;
    }
}
