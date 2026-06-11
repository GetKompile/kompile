/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.enrichment.repository;

import ai.kompile.enrichment.domain.EnrichmentAuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrichmentAuditRepository extends JpaRepository<EnrichmentAuditEntry, Long> {

    Optional<EnrichmentAuditEntry> findByAuditId(String auditId);

    Page<EnrichmentAuditEntry> findByFactSheetIdOrderByCreatedAtDesc(Long factSheetId, Pageable pageable);

    Page<EnrichmentAuditEntry> findByFactSheetIdAndEnrichmentJobIdOrderByCreatedAtDesc(
            Long factSheetId, String enrichmentJobId, Pageable pageable);

    Page<EnrichmentAuditEntry> findByFactSheetIdAndEnrichmentJobIdAndPhaseOrderByCreatedAtDesc(
            Long factSheetId, String enrichmentJobId, String phase, Pageable pageable);

    List<EnrichmentAuditEntry> findByEnrichmentJobIdAndRevertedFalseOrderByCreatedAtDesc(String enrichmentJobId);

    List<EnrichmentAuditEntry> findByEnrichmentJobIdAndPhaseAndRevertedFalseOrderByCreatedAtDesc(
            String enrichmentJobId, String phase);

    @Query("SELECT e.action, COUNT(e) FROM EnrichmentAuditEntry e " +
            "WHERE e.enrichmentJobId = :jobId GROUP BY e.action")
    List<Object[]> countByActionForJob(@Param("jobId") String jobId);

    @Query("SELECT DISTINCT e.phase FROM EnrichmentAuditEntry e WHERE e.enrichmentJobId = :jobId")
    List<String> findDistinctPhasesByJobId(@Param("jobId") String jobId);

    long countByEnrichmentJobIdAndRevertedTrue(String enrichmentJobId);
}
