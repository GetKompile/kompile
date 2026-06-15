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

import ai.kompile.orchestrator.model.output.ClassificationRule;
import ai.kompile.orchestrator.model.output.ClassificationType;
import ai.kompile.orchestrator.model.output.ClassificationSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for classification rules.
 */
@Repository
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, Long> {

    /**
     * Find all rules for a classifier, ordered by rule order.
     */
    List<ClassificationRule> findByClassifierIdOrderByRuleOrderAsc(Long classifierId);

    /**
     * Find enabled rules for a classifier, ordered by rule order.
     */
    List<ClassificationRule> findByClassifierIdAndEnabledTrueOrderByRuleOrderAsc(Long classifierId);

    /**
     * Find rules by classification type.
     */
    List<ClassificationRule> findByClassifierIdAndClassificationType(Long classifierId, ClassificationType type);

    /**
     * Find rules by severity.
     */
    List<ClassificationRule> findByClassifierIdAndSeverity(Long classifierId, ClassificationSeverity severity);

    /**
     * Find rules by tag.
     */
    @Query("SELECT r FROM ClassificationRule r WHERE r.classifier.id = :classifierId AND r.tags LIKE %:tag%")
    List<ClassificationRule> findByClassifierIdAndTag(@Param("classifierId") Long classifierId, @Param("tag") String tag);

    /**
     * Get the maximum rule order for a classifier.
     */
    @Query("SELECT MAX(r.ruleOrder) FROM ClassificationRule r WHERE r.classifier.id = :classifierId")
    Integer findMaxRuleOrderByClassifierId(@Param("classifierId") Long classifierId);

    /**
     * Count rules for a classifier.
     */
    long countByClassifierId(Long classifierId);

    /**
     * Delete all rules for a classifier.
     */
    void deleteByClassifierId(Long classifierId);
}
