/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.repository;

import ai.kompile.orchestrator.model.audit.AuditEntityType;
import ai.kompile.orchestrator.model.audit.AuditEventType;
import ai.kompile.orchestrator.model.audit.AuditLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for audit log entries.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    /**
     * Find by orchestrator instance ID.
     */
    List<AuditLogEntry> findByOrchestratorInstanceIdOrderByTimestampDesc(String orchestratorInstanceId);

    /**
     * Find by orchestrator instance ID with pagination.
     */
    Page<AuditLogEntry> findByOrchestratorInstanceIdOrderByTimestampDesc(String orchestratorInstanceId, Pageable pageable);

    /**
     * Find by event type.
     */
    List<AuditLogEntry> findByEventTypeOrderByTimestampDesc(AuditEventType eventType);

    /**
     * Find by entity type and ID.
     */
    List<AuditLogEntry> findByEntityTypeAndEntityIdOrderByTimestampDesc(AuditEntityType entityType, String entityId);

    /**
     * Find errors.
     */
    List<AuditLogEntry> findByErrorTrueOrderByTimestampDesc();

    /**
     * Find errors for an orchestrator.
     */
    List<AuditLogEntry> findByOrchestratorInstanceIdAndErrorTrueOrderByTimestampDesc(String orchestratorInstanceId);

    /**
     * Find by time range.
     */
    List<AuditLogEntry> findByTimestampBetweenOrderByTimestampDesc(LocalDateTime start, LocalDateTime end);

    /**
     * Find by orchestrator and time range.
     */
    List<AuditLogEntry> findByOrchestratorInstanceIdAndTimestampBetweenOrderByTimestampDesc(
            String orchestratorInstanceId, LocalDateTime start, LocalDateTime end);

    /**
     * Find by action.
     */
    List<AuditLogEntry> findByActionOrderByTimestampDesc(String action);

    /**
     * Find by trigger ID.
     */
    List<AuditLogEntry> findByTriggerIdOrderByTimestampDesc(String triggerId);

    /**
     * Find by hook ID.
     */
    List<AuditLogEntry> findByHookIdOrderByTimestampDesc(String hookId);

    /**
     * Count errors in time range.
     */
    @Query("SELECT COUNT(a) FROM AuditLogEntry a WHERE a.error = true AND a.timestamp BETWEEN :start AND :end")
    long countErrorsInTimeRange(LocalDateTime start, LocalDateTime end);

    /**
     * Count events by type.
     */
    @Query("SELECT a.eventType, COUNT(a) FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId GROUP BY a.eventType")
    List<Object[]> countByEventType(String instanceId);

    /**
     * Find recent entries.
     */
    @Query("SELECT a FROM AuditLogEntry a ORDER BY a.timestamp DESC")
    Page<AuditLogEntry> findRecentEntries(Pageable pageable);

    /**
     * Delete old entries.
     */
    void deleteByTimestampBefore(LocalDateTime cutoff);

    /**
     * Search by message content (case-insensitive).
     */
    @Query("SELECT a FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId AND LOWER(a.message) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY a.timestamp DESC")
    Page<AuditLogEntry> searchByMessage(@Param("instanceId") String instanceId, @Param("search") String search, Pageable pageable);

    /**
     * Find with multiple filters.
     */
    @Query("SELECT a FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId " +
           "AND (:eventType IS NULL OR a.eventType = :eventType) " +
           "AND (:entityType IS NULL OR a.entityType = :entityType) " +
           "AND (:fromTime IS NULL OR a.timestamp >= :fromTime) " +
           "AND (:toTime IS NULL OR a.timestamp <= :toTime) " +
           "AND (:errorsOnly = false OR a.error = true) " +
           "ORDER BY a.timestamp DESC")
    Page<AuditLogEntry> findWithFilters(
            @Param("instanceId") String instanceId,
            @Param("eventType") AuditEventType eventType,
            @Param("entityType") AuditEntityType entityType,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime,
            @Param("errorsOnly") boolean errorsOnly,
            Pageable pageable);

    /**
     * Find with filters and text search.
     */
    @Query("SELECT a FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId " +
           "AND (:eventType IS NULL OR a.eventType = :eventType) " +
           "AND (:entityType IS NULL OR a.entityType = :entityType) " +
           "AND (:fromTime IS NULL OR a.timestamp >= :fromTime) " +
           "AND (:toTime IS NULL OR a.timestamp <= :toTime) " +
           "AND (:errorsOnly = false OR a.error = true) " +
           "AND (:search IS NULL OR LOWER(a.message) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(a.action) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY a.timestamp DESC")
    Page<AuditLogEntry> findWithFiltersAndSearch(
            @Param("instanceId") String instanceId,
            @Param("eventType") AuditEventType eventType,
            @Param("entityType") AuditEntityType entityType,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime,
            @Param("errorsOnly") boolean errorsOnly,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Count by entity type for an instance.
     */
    @Query("SELECT a.entityType, COUNT(a) FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId GROUP BY a.entityType")
    List<Object[]> countByEntityType(@Param("instanceId") String instanceId);

    /**
     * Count errors for an instance.
     */
    @Query("SELECT COUNT(a) FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId AND a.error = true")
    long countErrors(@Param("instanceId") String instanceId);

    /**
     * Count total events for an instance.
     */
    long countByOrchestratorInstanceId(String instanceId);

    /**
     * Calculate average duration for an instance.
     */
    @Query("SELECT AVG(a.durationMs) FROM AuditLogEntry a WHERE a.orchestratorInstanceId = :instanceId AND a.durationMs IS NOT NULL")
    Double averageDuration(@Param("instanceId") String instanceId);

    /**
     * Count events by hour for the last 24 hours.
     */
    @Query("SELECT HOUR(a.timestamp), COUNT(a) FROM AuditLogEntry a " +
           "WHERE a.orchestratorInstanceId = :instanceId AND a.timestamp >= :since " +
           "GROUP BY HOUR(a.timestamp) ORDER BY HOUR(a.timestamp)")
    List<Object[]> countByHour(@Param("instanceId") String instanceId, @Param("since") LocalDateTime since);

    /**
     * Find by actor ID.
     */
    List<AuditLogEntry> findByActorIdOrderByTimestampDesc(String actorId);

    /**
     * Find by actor ID for an instance.
     */
    List<AuditLogEntry> findByOrchestratorInstanceIdAndActorIdOrderByTimestampDesc(String instanceId, String actorId);
}
