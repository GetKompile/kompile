package ai.kompile.testmilestone.service;

import ai.kompile.testmilestone.domain.*;
import ai.kompile.testmilestone.repository.TestCaseResultRepository;
import ai.kompile.testmilestone.repository.TestMilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestMilestoneService {

    private final TestMilestoneRepository milestoneRepository;
    private final TestCaseResultRepository testCaseResultRepository;

    /**
     * Record a new test milestone by scanning surefire reports and git state.
     */
    @Transactional
    public TestMilestoneEntity recordMilestone(Path projectRoot, String moduleName, String tags) {
        Path surefireDir = projectRoot.resolve("target/surefire-reports");
        SurefireReportParser.ParseResult parseResult = SurefireReportParser.parseDirectory(surefireDir);

        if (parseResult.totalTests() == 0) {
            log.warn("No test results found in {}", surefireDir);
            return null;
        }

        GitInfo gitInfo = readGitInfo(projectRoot);

        // Check for existing milestone at this commit+module
        Optional<TestMilestoneEntity> existing = milestoneRepository
                .findByCommitHashAndModuleName(gitInfo.commitHash(), moduleName);
        if (existing.isPresent()) {
            log.info("Updating existing milestone for commit {} module {}", gitInfo.commitHash(), moduleName);
            milestoneRepository.delete(existing.get());
        }

        TestMilestoneEntity milestone = TestMilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .commitHash(gitInfo.commitHash())
                .branch(gitInfo.branch())
                .commitMessage(gitInfo.commitMessage())
                .commitAuthor(gitInfo.commitAuthor())
                .commitTimestamp(gitInfo.commitTimestamp())
                .moduleName(moduleName)
                .totalTests(parseResult.totalTests())
                .passed(parseResult.passed())
                .failed(parseResult.failed())
                .skipped(parseResult.skipped())
                .errors(parseResult.errors())
                .durationMs(parseResult.totalDurationMs())
                .tags(tags)
                .build();

        for (TestCaseResultEntity tc : parseResult.testCases()) {
            milestone.addTestCaseResult(tc);
        }

        return milestoneRepository.save(milestone);
    }

    /**
     * Record a milestone from manually provided data (no surefire parsing).
     */
    @Transactional
    public TestMilestoneEntity recordManualMilestone(String commitHash, String branch, String moduleName,
                                                     int totalTests, int passed, int failed,
                                                     int skipped, int errors, long durationMs,
                                                     String tags) {
        TestMilestoneEntity milestone = TestMilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .commitHash(commitHash)
                .branch(branch)
                .moduleName(moduleName)
                .totalTests(totalTests)
                .passed(passed)
                .failed(failed)
                .skipped(skipped)
                .errors(errors)
                .durationMs(durationMs)
                .tags(tags)
                .build();

        return milestoneRepository.save(milestone);
    }

    @Transactional(readOnly = true)
    public Optional<TestMilestoneEntity> getMilestone(String id) {
        return milestoneRepository.findByIdWithTestCases(id);
    }

    @Transactional(readOnly = true)
    public List<TestMilestoneEntity> getMilestonesByCommit(String commitHash) {
        return milestoneRepository.findByCommitHash(commitHash);
    }

    @Transactional(readOnly = true)
    public Page<TestMilestoneEntity> listMilestones(String branch, String moduleName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (branch != null && moduleName != null) {
            return milestoneRepository.findByBranchAndModuleNameOrderByCreatedAtDesc(branch, moduleName, pageable);
        } else if (branch != null) {
            return milestoneRepository.findByBranchOrderByCreatedAtDesc(branch, pageable);
        } else if (moduleName != null) {
            return milestoneRepository.findByModuleNameOrderByCreatedAtDesc(moduleName, pageable);
        }
        return milestoneRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Optional<TestMilestoneEntity> getLatestMilestone(String branch, String moduleName) {
        List<TestMilestoneEntity> results = milestoneRepository.findLatestByBranchAndModule(
                branch, moduleName, PageRequest.of(0, 1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Compare two milestones and show what changed: new failures, fixed tests, new tests, removed tests.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> compareMilestones(String fromId, String toId) {
        Optional<TestMilestoneEntity> fromOpt = milestoneRepository.findByIdWithTestCases(fromId);
        Optional<TestMilestoneEntity> toOpt = milestoneRepository.findByIdWithTestCases(toId);

        if (fromOpt.isEmpty() || toOpt.isEmpty()) {
            return Map.of("error", "One or both milestones not found");
        }

        TestMilestoneEntity from = fromOpt.get();
        TestMilestoneEntity to = toOpt.get();

        Map<String, TestCaseResultEntity> fromTests = from.getTestCaseResults().stream()
                .collect(Collectors.toMap(TestCaseResultEntity::getFullyQualifiedName, t -> t, (a, b) -> a));
        Map<String, TestCaseResultEntity> toTests = to.getTestCaseResults().stream()
                .collect(Collectors.toMap(TestCaseResultEntity::getFullyQualifiedName, t -> t, (a, b) -> a));

        List<Map<String, String>> newFailures = new ArrayList<>();
        List<Map<String, String>> fixed = new ArrayList<>();
        List<String> newTests = new ArrayList<>();
        List<String> removedTests = new ArrayList<>();

        // Tests in both milestones
        for (Map.Entry<String, TestCaseResultEntity> entry : toTests.entrySet()) {
            String fqn = entry.getKey();
            TestCaseResultEntity toTest = entry.getValue();
            TestCaseResultEntity fromTest = fromTests.get(fqn);

            if (fromTest == null) {
                newTests.add(fqn);
            } else {
                boolean wasOk = fromTest.getStatus() == TestCaseStatus.PASSED;
                boolean isOk = toTest.getStatus() == TestCaseStatus.PASSED;
                if (wasOk && !isOk) {
                    newFailures.add(Map.of(
                            "test", fqn,
                            "status", toTest.getStatus().name(),
                            "error", toTest.getErrorMessage() != null ? toTest.getErrorMessage() : ""
                    ));
                } else if (!wasOk && isOk) {
                    fixed.add(Map.of(
                            "test", fqn,
                            "previousStatus", fromTest.getStatus().name()
                    ));
                }
            }
        }

        for (String fqn : fromTests.keySet()) {
            if (!toTests.containsKey(fqn)) {
                removedTests.add(fqn);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", milestoneToSummary(from));
        result.put("to", milestoneToSummary(to));
        result.put("newFailures", newFailures);
        result.put("fixed", fixed);
        result.put("newTests", newTests);
        result.put("removedTests", removedTests);
        result.put("passRateDelta", to.getPassRate() - from.getPassRate());
        return result;
    }

    /**
     * Get the history of a specific test case across milestones.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTestCaseHistory(String className, String methodName, String moduleName) {
        List<TestCaseResultEntity> history;
        if (moduleName != null) {
            history = testCaseResultRepository.findTestHistoryByModule(moduleName, className, methodName);
        } else {
            history = testCaseResultRepository.findTestHistory(className, methodName);
        }

        return history.stream().map(tc -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("milestoneId", tc.getMilestone().getId());
            entry.put("commitHash", tc.getMilestone().getCommitHash());
            entry.put("branch", tc.getMilestone().getBranch());
            entry.put("status", tc.getStatus().name());
            entry.put("durationMs", tc.getDurationMs());
            entry.put("errorMessage", tc.getErrorMessage());
            entry.put("timestamp", tc.getMilestone().getCreatedAt());
            return entry;
        }).collect(Collectors.toList());
    }

    /**
     * Get pass rate trend for a module over time.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPassRateTrend(String moduleName, Instant from, Instant to) {
        List<TestMilestoneEntity> milestones = milestoneRepository.findByModuleInDateRange(moduleName, from, to);
        return milestones.stream().map(m -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("milestoneId", m.getId());
            point.put("commitHash", m.getCommitHash());
            point.put("branch", m.getBranch());
            point.put("passRate", m.getPassRate());
            point.put("totalTests", m.getTotalTests());
            point.put("passed", m.getPassed());
            point.put("failed", m.getFailed());
            point.put("status", m.getStatus().name());
            point.put("timestamp", m.getCreatedAt());
            return point;
        }).collect(Collectors.toList());
    }

    /**
     * Find regressions: tests that were passing in a recent milestone but now fail.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findRegressions(String branch, String moduleName) {
        List<TestMilestoneEntity> recent = milestoneRepository.findLatestByBranchAndModule(
                branch, moduleName, PageRequest.of(0, 2));
        if (recent.size() < 2) {
            return List.of();
        }
        Map<String, Object> comparison = compareMilestones(recent.get(1).getId(), recent.get(0).getId());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> newFailures = (List<Map<String, String>>) comparison.get("newFailures");
        List<Map<String, Object>> regressions = new ArrayList<>();
        for (Map<String, String> f : newFailures) {
            Map<String, Object> regression = new LinkedHashMap<>();
            regression.put("test", f.get("test"));
            regression.put("status", f.get("status"));
            regression.put("error", f.get("error"));
            regression.put("previousCommit", recent.get(1).getCommitHash());
            regression.put("currentCommit", recent.get(0).getCommitHash());
            regressions.add(regression);
        }
        return regressions;
    }

    /**
     * Get overall summary stats.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalMilestones", milestoneRepository.count());
        summary.put("passingMilestones", milestoneRepository.countByStatus(MilestoneStatus.PASS));
        summary.put("failingMilestones", milestoneRepository.countByStatus(MilestoneStatus.FAIL));
        summary.put("partialMilestones", milestoneRepository.countByStatus(MilestoneStatus.PARTIAL));
        summary.put("modules", milestoneRepository.findDistinctModuleNames());
        summary.put("branches", milestoneRepository.findDistinctBranches());
        return summary;
    }

    @Transactional
    public void deleteMilestone(String id) {
        milestoneRepository.deleteById(id);
    }

    private Map<String, Object> milestoneToSummary(TestMilestoneEntity m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", m.getId());
        s.put("commitHash", m.getCommitHash());
        s.put("branch", m.getBranch());
        s.put("moduleName", m.getModuleName());
        s.put("status", m.getStatus().name());
        s.put("passRate", m.getPassRate());
        s.put("totalTests", m.getTotalTests());
        s.put("passed", m.getPassed());
        s.put("failed", m.getFailed());
        s.put("createdAt", m.getCreatedAt());
        return s;
    }

    private GitInfo readGitInfo(Path projectRoot) {
        try {
            String commitHash = runGitCommand(projectRoot, "git", "rev-parse", "HEAD");
            String branch = runGitCommand(projectRoot, "git", "rev-parse", "--abbrev-ref", "HEAD");
            String commitMessage = runGitCommand(projectRoot, "git", "log", "-1", "--pretty=%s");
            String commitAuthor = runGitCommand(projectRoot, "git", "log", "-1", "--pretty=%an");
            String timestampStr = runGitCommand(projectRoot, "git", "log", "-1", "--pretty=%aI");
            Instant commitTimestamp = null;
            if (timestampStr != null && !timestampStr.isEmpty()) {
                try {
                    commitTimestamp = java.time.OffsetDateTime.parse(timestampStr).toInstant();
                } catch (Exception e) {
                    log.warn("Failed to parse commit timestamp: {}", timestampStr);
                }
            }
            return new GitInfo(commitHash, branch, commitMessage, commitAuthor, commitTimestamp);
        } catch (Exception e) {
            log.warn("Failed to read git info from {}", projectRoot, e);
            return new GitInfo("unknown", "unknown", null, null, null);
        }
    }

    private String runGitCommand(Path workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    record GitInfo(String commitHash, String branch, String commitMessage, String commitAuthor, Instant commitTimestamp) {}
}
