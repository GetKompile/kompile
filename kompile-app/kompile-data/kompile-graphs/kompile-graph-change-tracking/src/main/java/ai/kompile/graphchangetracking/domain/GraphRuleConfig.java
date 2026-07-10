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
package ai.kompile.graphchangetracking.domain;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A reactive rule over graph changes: when a completed changeset matches the (threshold)
 * condition, fire an outbound action (log / webhook). This is the "act on graph events" half
 * of reactive graphs — complementing the GraphUpdatePipelineConfig (which ingests INTO the graph).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphRuleConfig {

    private Long id;

    private String ruleId;

    private String name;

    @Builder.Default
    private Boolean enabled = true;

    /** Optional fact-sheet scope; null means the rule applies to changesets in any fact sheet. */
    private Long factSheetId;

    /** Fire when a changeset created at least this many nodes (null = no node threshold). */
    private Integer minNodesCreated;

    /** Fire when a changeset created at least this many edges (null = no edge threshold). */
    private Integer minEdgesCreated;

    /**
     * When this rule is evaluated: {@code CHANGESET} (default — on changeset-complete, using the
     * min*Created thresholds) or {@code MUTATION} (per individual node/edge mutation, using the
     * on* match fields below).
     */
    @Builder.Default
    private String triggerType = "CHANGESET";

    /** MUTATION rules only: match this mutation type ({@code NODE_CREATED}, {@code EDGE_DELETED}, …); null = any. */
    private String onMutationType;

    /** MUTATION rules only: match this entity kind ({@code NODE}/{@code EDGE}); null = any. */
    private String onEntityKind;

    /** MUTATION rules only: match this semantic entity type (nodeType / edgeType from the snapshot); null = any. */
    private String onEntityType;

    /** Action to fire on match: {@code LOG} or {@code WEBHOOK}. */
    @Builder.Default
    private String actionType = "LOG";

    /** Action target — e.g. the webhook URL for {@code WEBHOOK}. */
    private String actionTarget;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void initDefaults() {
        if (ruleId == null) ruleId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = true;
        if (actionType == null) actionType = "LOG";
        if (triggerType == null) triggerType = "CHANGESET";
    }

    public void markUpdated() {
        updatedAt = LocalDateTime.now();
    }
}
