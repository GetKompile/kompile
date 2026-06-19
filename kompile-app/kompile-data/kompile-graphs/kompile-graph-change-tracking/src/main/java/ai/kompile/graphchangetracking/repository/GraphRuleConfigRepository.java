/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graphchangetracking.repository;

import ai.kompile.graphchangetracking.domain.GraphRuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GraphRuleConfigRepository extends JpaRepository<GraphRuleConfig, Long> {

    Optional<GraphRuleConfig> findByRuleId(String ruleId);

    List<GraphRuleConfig> findByEnabledTrue();

    boolean existsByRuleId(String ruleId);
}
