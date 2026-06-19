/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graphchangetracking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A reactive rule over graph changes: when a completed changeset matches the (threshold)
 * condition, fire an outbound action (log / webhook). This is the "act on graph events" half
 * of reactive graphs — complementing the GraphUpdatePipelineConfig (which ingests INTO the graph).
 */
@Entity
@Table(name = "graph_rule_configs", indexes = {
        @Index(name = "idx_grc_rule_id", columnList = "ruleId", unique = true),
        @Index(name = "idx_grc_enabled", columnList = "enabled"),
        @Index(name = "idx_grc_fact_sheet", columnList = "factSheetId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphRuleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, unique = true, nullable = false)
    private String ruleId;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Optional fact-sheet scope; null means the rule applies to changesets in any fact sheet. */
    @Column
    private Long factSheetId;

    /** Fire when a changeset created at least this many nodes (null = no node threshold). */
    @Column
    private Integer minNodesCreated;

    /** Fire when a changeset created at least this many edges (null = no edge threshold). */
    @Column
    private Integer minEdgesCreated;

    /** Action to fire on match: {@code LOG} or {@code WEBHOOK}. */
    @Column(length = 20, nullable = false)
    @Builder.Default
    private String actionType = "LOG";

    /** Action target — e.g. the webhook URL for {@code WEBHOOK}. */
    @Column(length = 2048)
    private String actionTarget;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (ruleId == null) {
            ruleId = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = true;
        if (actionType == null) actionType = "LOG";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
