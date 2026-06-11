package ai.kompile.app.web.controllers;

import ai.kompile.testmilestone.domain.TestCaseResultEntity;
import ai.kompile.testmilestone.domain.TestMilestoneEntity;
import ai.kompile.testmilestone.service.TestMilestoneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/test-milestones")
@CrossOrigin(origins = "*")
public class TestMilestoneController {

    private final TestMilestoneService milestoneService;

    @Autowired
    public TestMilestoneController(@Autowired(required = false) TestMilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("available", false, "message", "Test milestone service not loaded"));
        }
        return ResponseEntity.ok(Map.of("available", true));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return ResponseEntity.ok(milestoneService.getSummary());
    }

    @GetMapping
    public ResponseEntity<Page<TestMilestoneEntity>> list(
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String moduleName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Page.empty());
        }
        return ResponseEntity.ok(milestoneService.listMilestones(branch, moduleName, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return milestoneService.getMilestone(id)
                .map(m -> ResponseEntity.ok(toDetailDto(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/commit/{commitHash}")
    public ResponseEntity<List<Map<String, Object>>> getByCommit(@PathVariable String commitHash) {
        if (milestoneService == null) {
            return ResponseEntity.ok(List.of());
        }
        List<TestMilestoneEntity> milestones = milestoneService.getMilestonesByCommit(commitHash);
        return ResponseEntity.ok(milestones.stream().map(this::toSummaryDto).collect(Collectors.toList()));
    }

    @GetMapping("/latest")
    public ResponseEntity<?> getLatest(
            @RequestParam String branch,
            @RequestParam String moduleName) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return milestoneService.getLatestMilestone(branch, moduleName)
                .map(m -> ResponseEntity.ok(toSummaryDto(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> record(@RequestBody RecordRequest request) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("status", "error", "error", "Service not available"));
        }
        Path projectRoot = Paths.get(request.projectPath != null ? request.projectPath : ".");
        TestMilestoneEntity milestone = milestoneService.recordMilestone(
                projectRoot, request.moduleName, request.tags);
        if (milestone == null) {
            return ResponseEntity.ok(Map.of("status", "error", "error", "No test results found"));
        }
        return ResponseEntity.ok(Map.of("status", "success", "milestone", toSummaryDto(milestone)));
    }

    @PostMapping("/record-manual")
    public ResponseEntity<Map<String, Object>> recordManual(@RequestBody ManualRecordRequest request) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("status", "error", "error", "Service not available"));
        }
        TestMilestoneEntity milestone = milestoneService.recordManualMilestone(
                request.commitHash, request.branch, request.moduleName,
                request.totalTests, request.passed, request.failed,
                request.skipped, request.errors, request.durationMs, request.tags);
        return ResponseEntity.ok(Map.of("status", "success", "milestone", toSummaryDto(milestone)));
    }

    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compare(
            @RequestParam String fromId,
            @RequestParam String toId) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return ResponseEntity.ok(milestoneService.compareMilestones(fromId, toId));
    }

    @GetMapping("/regressions")
    public ResponseEntity<List<Map<String, Object>>> regressions(
            @RequestParam String branch,
            @RequestParam String moduleName) {
        if (milestoneService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(milestoneService.findRegressions(branch, moduleName));
    }

    @GetMapping("/test-history")
    public ResponseEntity<List<Map<String, Object>>> testHistory(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(required = false) String moduleName) {
        if (milestoneService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(milestoneService.getTestCaseHistory(className, methodName, moduleName));
    }

    @GetMapping("/trend")
    public ResponseEntity<List<Map<String, Object>>> trend(
            @RequestParam String moduleName,
            @RequestParam(required = false) Long fromEpochMs,
            @RequestParam(required = false) Long toEpochMs) {
        if (milestoneService == null) {
            return ResponseEntity.ok(List.of());
        }
        Instant from = fromEpochMs != null ? Instant.ofEpochMilli(fromEpochMs) : Instant.now().minus(30, ChronoUnit.DAYS);
        Instant to = toEpochMs != null ? Instant.ofEpochMilli(toEpochMs) : Instant.now();
        return ResponseEntity.ok(milestoneService.getPassRateTrend(moduleName, from, to));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        if (milestoneService == null) {
            return ResponseEntity.ok(Map.of("status", "error", "error", "Service not available"));
        }
        milestoneService.deleteMilestone(id);
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    // --- DTOs ---

    private Map<String, Object> toSummaryDto(TestMilestoneEntity m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", m.getId());
        dto.put("commitHash", m.getCommitHash());
        dto.put("branch", m.getBranch());
        dto.put("commitMessage", m.getCommitMessage());
        dto.put("commitAuthor", m.getCommitAuthor());
        dto.put("moduleName", m.getModuleName());
        dto.put("status", m.getStatus().name());
        dto.put("passRate", m.getPassRate());
        dto.put("totalTests", m.getTotalTests());
        dto.put("passed", m.getPassed());
        dto.put("failed", m.getFailed());
        dto.put("skipped", m.getSkipped());
        dto.put("errors", m.getErrors());
        dto.put("durationMs", m.getDurationMs());
        dto.put("tags", m.getTags());
        dto.put("createdAt", m.getCreatedAt());
        return dto;
    }

    private Map<String, Object> toDetailDto(TestMilestoneEntity m) {
        Map<String, Object> dto = toSummaryDto(m);
        dto.put("testCases", m.getTestCaseResults().stream().map(tc -> {
            Map<String, Object> tcDto = new LinkedHashMap<>();
            tcDto.put("id", tc.getId());
            tcDto.put("className", tc.getClassName());
            tcDto.put("methodName", tc.getMethodName());
            tcDto.put("fullyQualifiedName", tc.getFullyQualifiedName());
            tcDto.put("status", tc.getStatus().name());
            tcDto.put("durationMs", tc.getDurationMs());
            tcDto.put("errorMessage", tc.getErrorMessage());
            return tcDto;
        }).collect(Collectors.toList()));
        return dto;
    }

    // --- Request bodies ---

    static class RecordRequest {
        public String projectPath;
        public String moduleName;
        public String tags;
    }

    static class ManualRecordRequest {
        public String commitHash;
        public String branch;
        public String moduleName;
        public int totalTests;
        public int passed;
        public int failed;
        public int skipped;
        public int errors;
        public long durationMs;
        public String tags;
    }
}
