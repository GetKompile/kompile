/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A repeatable flow pattern discovered in the knowledge graph.
 * Represents a sequence of interactions (email exchanges, data transformations)
 * that occurs across multiple instances.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowPattern {

    /** The fact sheet this pattern was discovered from. */
    private Long factSheetId;

    /** Pattern type: EMAIL_FLOW, EXCEL_COMPUTATION, DOCUMENT_PIPELINE */
    private String type;

    /** Human-readable description */
    private String description;

    /** Number of times this pattern was observed */
    private int occurrenceCount;

    /** Confidence that this is a real pattern (0.0 to 1.0) */
    private double confidence;

    /** Ordered sequence of steps in this flow */
    @Builder.Default
    private List<FlowStep> steps = new ArrayList<>();

    /** KG node IDs involved in this pattern */
    @Builder.Default
    private List<String> involvedNodeIds = new ArrayList<>();

    /**
     * Child flow patterns that are sub-processes of this flow.
     * For example, an email flow that references a spreadsheet procedure
     * would have the spreadsheet computation as a child pattern.
     */
    @Builder.Default
    private List<FlowPattern> childPatterns = new ArrayList<>();

    /** The type of the parent flow that contains this one, if any. */
    private String parentFlowType;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowStep {
        /** Step description (e.g., "Person A sends email to Person B") */
        private String description;
        /** Actor (person, system, formula cell) */
        private String actor;
        /** Action type (SEND, RECEIVE, COMPUTE, APPROVE, TRANSFORM) */
        private String action;
        /** Target of the action */
        private String target;
        /** KG node ID if applicable */
        private String nodeId;
    }
}
