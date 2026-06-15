package ai.kompile.testmilestone.repository;

import ai.kompile.testmilestone.domain.TestCaseResultEntity;
import ai.kompile.testmilestone.domain.TestCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseResultRepository extends JpaRepository<TestCaseResultEntity, String> {

    List<TestCaseResultEntity> findByMilestoneId(String milestoneId);

    List<TestCaseResultEntity> findByMilestoneIdAndStatus(String milestoneId, TestCaseStatus status);

    @Query("SELECT t FROM TestCaseResultEntity t WHERE t.className = :className AND t.methodName = :methodName " +
            "ORDER BY t.createdAt DESC")
    List<TestCaseResultEntity> findTestHistory(@Param("className") String className, @Param("methodName") String methodName);

    @Query("SELECT t FROM TestCaseResultEntity t JOIN t.milestone m WHERE m.moduleName = :moduleName " +
            "AND t.className = :className AND t.methodName = :methodName ORDER BY m.createdAt DESC")
    List<TestCaseResultEntity> findTestHistoryByModule(@Param("moduleName") String moduleName,
                                                       @Param("className") String className,
                                                       @Param("methodName") String methodName);

    @Query("SELECT DISTINCT t.className FROM TestCaseResultEntity t JOIN t.milestone m " +
            "WHERE m.id = :milestoneId AND t.status = :status")
    List<String> findDistinctFailingClasses(@Param("milestoneId") String milestoneId, @Param("status") TestCaseStatus status);
}
