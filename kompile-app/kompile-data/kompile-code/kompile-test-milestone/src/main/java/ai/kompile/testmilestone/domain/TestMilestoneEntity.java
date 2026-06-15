package ai.kompile.testmilestone.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "test_milestones", indexes = {
        @Index(name = "idx_milestone_commit", columnList = "commitHash"),
        @Index(name = "idx_milestone_branch", columnList = "branch"),
        @Index(name = "idx_milestone_module", columnList = "moduleName"),
        @Index(name = "idx_milestone_status", columnList = "status"),
        @Index(name = "idx_milestone_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestMilestoneEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 40)
    private String commitHash;

    @Column(length = 255)
    private String branch;

    @Column(length = 512)
    private String commitMessage;

    @Column(length = 255)
    private String commitAuthor;

    private Instant commitTimestamp;

    @Column(nullable = false, length = 255)
    private String moduleName;

    private int totalTests;
    private int passed;
    private int failed;
    private int skipped;
    private int errors;
    private double passRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MilestoneStatus status;

    private long durationMs;

    @Column(length = 1024)
    private String tags;

    private Instant createdAt;
    private Instant updatedAt;

    @OneToMany(mappedBy = "milestone", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<TestCaseResultEntity> testCaseResults = new ArrayList<>();

    public void addTestCaseResult(TestCaseResultEntity result) {
        testCaseResults.add(result);
        result.setMilestone(this);
    }

    public void removeTestCaseResult(TestCaseResultEntity result) {
        testCaseResults.remove(result);
        result.setMilestone(null);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        computeStatus();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
        computeStatus();
    }

    private void computeStatus() {
        if (totalTests == 0) {
            status = MilestoneStatus.ERROR;
        } else if (failed == 0 && errors == 0) {
            status = MilestoneStatus.PASS;
        } else if (passed == 0) {
            status = MilestoneStatus.FAIL;
        } else {
            status = MilestoneStatus.PARTIAL;
        }
        passRate = totalTests > 0 ? (double) passed / totalTests : 0.0;
    }
}
