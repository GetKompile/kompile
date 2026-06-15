package ai.kompile.graphchangetracking.repository;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GraphMutationRecordRepository extends JpaRepository<GraphMutationRecord, Long> {

    List<GraphMutationRecord> findByEntityKindAndEntityIdOrderByOccurredAtDesc(
            String entityKind, String entityId);

    Page<GraphMutationRecord> findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long factSheetId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    List<GraphMutationRecord> findByChangesetId(String changesetId);

    Page<GraphMutationRecord> findByTriggerSourceStartingWithOrderByOccurredAtDesc(
            String triggerSourcePrefix, Pageable pageable);

    Page<GraphMutationRecord> findByOccurredAtBetweenOrderByOccurredAtDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<GraphMutationRecord> findByFactSheetIdOrderByOccurredAtDesc(
            Long factSheetId, Pageable pageable);

    @Query("SELECT r FROM GraphMutationRecord r WHERE r.entityKind = :entityKind " +
            "AND r.entityId = :entityId AND r.occurredAt <= :asOf " +
            "ORDER BY r.occurredAt DESC")
    List<GraphMutationRecord> findMostRecentBefore(
            @Param("entityKind") String entityKind,
            @Param("entityId") String entityId,
            @Param("asOf") LocalDateTime asOf,
            Pageable pageable);

    @Modifying
    @Query("DELETE FROM GraphMutationRecord r WHERE r.occurredAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
