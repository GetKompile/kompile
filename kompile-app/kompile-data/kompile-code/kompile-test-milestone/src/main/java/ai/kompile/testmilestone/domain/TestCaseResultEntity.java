package ai.kompile.testmilestone.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "test_case_results", indexes = {
        @Index(name = "idx_tcr_milestone", columnList = "milestone_id"),
        @Index(name = "idx_tcr_class", columnList = "className"),
        @Index(name = "idx_tcr_status", columnList = "status"),
        @Index(name = "idx_tcr_fqn", columnList = "className,methodName")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseResultEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "milestone_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TestMilestoneEntity milestone;

    @Column(nullable = false, length = 512)
    private String className;

    @Column(nullable = false, length = 255)
    private String methodName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TestCaseStatus status;

    @Column(length = 2048)
    private String errorMessage;

    @Lob
    private String errorStackTrace;

    private long durationMs;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getFullyQualifiedName() {
        return className + "#" + methodName;
    }
}
