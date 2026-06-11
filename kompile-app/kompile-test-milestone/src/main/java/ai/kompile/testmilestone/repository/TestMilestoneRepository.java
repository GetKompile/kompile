package ai.kompile.testmilestone.repository;

import ai.kompile.testmilestone.domain.MilestoneStatus;
import ai.kompile.testmilestone.domain.TestMilestoneEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TestMilestoneRepository extends JpaRepository<TestMilestoneEntity, String> {

    Optional<TestMilestoneEntity> findByCommitHashAndModuleName(String commitHash, String moduleName);

    List<TestMilestoneEntity> findByCommitHash(String commitHash);

    Page<TestMilestoneEntity> findByBranchOrderByCreatedAtDesc(String branch, Pageable pageable);

    Page<TestMilestoneEntity> findByModuleNameOrderByCreatedAtDesc(String moduleName, Pageable pageable);

    Page<TestMilestoneEntity> findByBranchAndModuleNameOrderByCreatedAtDesc(String branch, String moduleName, Pageable pageable);

    Page<TestMilestoneEntity> findByStatusOrderByCreatedAtDesc(MilestoneStatus status, Pageable pageable);

    @Query("SELECT m FROM TestMilestoneEntity m WHERE m.branch = :branch AND m.moduleName = :moduleName ORDER BY m.createdAt DESC")
    List<TestMilestoneEntity> findLatestByBranchAndModule(@Param("branch") String branch, @Param("moduleName") String moduleName, Pageable pageable);

    @Query("SELECT m FROM TestMilestoneEntity m LEFT JOIN FETCH m.testCaseResults WHERE m.id = :id")
    Optional<TestMilestoneEntity> findByIdWithTestCases(@Param("id") String id);

    @Query("SELECT m FROM TestMilestoneEntity m LEFT JOIN FETCH m.testCaseResults WHERE m.commitHash = :commitHash AND m.moduleName = :moduleName")
    Optional<TestMilestoneEntity> findByCommitAndModuleWithTestCases(@Param("commitHash") String commitHash, @Param("moduleName") String moduleName);

    @Query("SELECT m FROM TestMilestoneEntity m WHERE m.createdAt BETWEEN :from AND :to AND m.moduleName = :moduleName ORDER BY m.createdAt ASC")
    List<TestMilestoneEntity> findByModuleInDateRange(@Param("moduleName") String moduleName, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT m FROM TestMilestoneEntity m WHERE m.createdAt BETWEEN :from AND :to ORDER BY m.createdAt ASC")
    List<TestMilestoneEntity> findInDateRange(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT DISTINCT m.moduleName FROM TestMilestoneEntity m ORDER BY m.moduleName")
    List<String> findDistinctModuleNames();

    @Query("SELECT DISTINCT m.branch FROM TestMilestoneEntity m ORDER BY m.branch")
    List<String> findDistinctBranches();

    long countByStatus(MilestoneStatus status);
}
