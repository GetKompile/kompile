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
import java.util.Map;

/**
 * A suggested process definition discovered from knowledge graph analysis.
 * Contains enough information to create a ProcessDefinition via the process engine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSuggestion {

    /** The fact sheet this suggestion was discovered from. */
    private Long factSheetId;

    /** Suggested process name */
    private String name;

    /** Human-readable description of the discovered process */
    private String description;

    /** How the process was discovered: EMAIL_FLOW, EXCEL_COMPUTATION, DOCUMENT_PIPELINE, COMMUNITY */
    private String discoverySource;

    /** Confidence that this is a real, repeatable process (0.0 to 1.0) */
    private double confidence;

    /** Suggested phases with their steps */
    @Builder.Default
    private List<SuggestedPhase> phases = new ArrayList<>();

    /** KG node IDs that this process is derived from */
    @Builder.Default
    private List<String> sourceGraphNodeIds = new ArrayList<>();

    /** Evidence supporting this suggestion */
    @Builder.Default
    private List<String> evidence = new ArrayList<>();

    /**
     * Child process suggestions that are sub-processes of this one.
     * For example, an "Email-driven Budget Review" process may have a
     * "Spreadsheet Computation" child process extracted from a referenced attachment.
     */
    @Builder.Default
    private List<ProcessSuggestion> childSuggestions = new ArrayList<>();

    /** ID of the parent suggestion if this is a sub-process. */
    private String parentSuggestionId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedPhase {
        private String name;
        private String description;
        @Builder.Default
        private List<SuggestedStep> steps = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedStep {
        /** Step name */
        private String name;
        /** Step type: AUTO, HUMAN, APPROVE, TOOL_CALL, EXCEL_COMPUTE, SCRIPT, HTTP_CALL */
        private String stepType;
        /** Description of what this step does */
        private String description;
        /** For TOOL_CALL: suggested tool name */
        private String toolName;
        /** For EXCEL_COMPUTE: the graph node IDs containing the spreadsheet */
        @Builder.Default
        private List<String> graphNodeIds = new ArrayList<>();
        /** Input mapping: runData keys this step reads */
        @Builder.Default
        private Map<String, String> inputMapping = Map.of();
        /** Suggested assignee (for HUMAN/APPROVE steps) */
        private String suggestedAssignee;
    }
}
