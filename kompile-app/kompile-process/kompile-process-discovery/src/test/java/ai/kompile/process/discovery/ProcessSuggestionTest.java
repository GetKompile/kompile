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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessSuggestion model")
class ProcessSuggestionTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("phases list defaults to empty")
        void phasesDefaultToEmpty() {
            ProcessSuggestion ps = ProcessSuggestion.builder().name("test").build();
            assertNotNull(ps.getPhases());
            assertTrue(ps.getPhases().isEmpty());
        }

        @Test
        @DisplayName("sourceGraphNodeIds defaults to empty")
        void sourceGraphNodeIdsDefaultToEmpty() {
            ProcessSuggestion ps = ProcessSuggestion.builder().build();
            assertNotNull(ps.getSourceGraphNodeIds());
            assertTrue(ps.getSourceGraphNodeIds().isEmpty());
        }

        @Test
        @DisplayName("evidence defaults to empty")
        void evidenceDefaultsToEmpty() {
            ProcessSuggestion ps = ProcessSuggestion.builder().build();
            assertNotNull(ps.getEvidence());
            assertTrue(ps.getEvidence().isEmpty());
        }
    }

    @Nested
    @DisplayName("SuggestedPhase builder")
    class SuggestedPhaseTests {

        @Test
        @DisplayName("steps list defaults to empty")
        void stepsDefaultToEmpty() {
            ProcessSuggestion.SuggestedPhase phase = ProcessSuggestion.SuggestedPhase.builder()
                    .name("Intake")
                    .build();
            assertNotNull(phase.getSteps());
            assertTrue(phase.getSteps().isEmpty());
        }

        @Test
        @DisplayName("phase with steps preserves order")
        void phaseStepsPreserveOrder() {
            ProcessSuggestion.SuggestedPhase phase = ProcessSuggestion.SuggestedPhase.builder()
                    .name("Processing")
                    .steps(List.of(
                            ProcessSuggestion.SuggestedStep.builder().name("step1").stepType("AUTO").build(),
                            ProcessSuggestion.SuggestedStep.builder().name("step2").stepType("HUMAN").build(),
                            ProcessSuggestion.SuggestedStep.builder().name("step3").stepType("APPROVE").build()
                    ))
                    .build();
            assertEquals(3, phase.getSteps().size());
            assertEquals("step1", phase.getSteps().get(0).getName());
            assertEquals("step3", phase.getSteps().get(2).getName());
        }
    }

    @Nested
    @DisplayName("SuggestedStep builder")
    class SuggestedStepTests {

        @Test
        @DisplayName("graphNodeIds defaults to empty")
        void graphNodeIdsDefaultToEmpty() {
            ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                    .name("Compute")
                    .stepType("EXCEL_COMPUTE")
                    .build();
            assertNotNull(step.getGraphNodeIds());
            assertTrue(step.getGraphNodeIds().isEmpty());
        }

        @Test
        @DisplayName("inputMapping defaults to empty map")
        void inputMappingDefaultsToEmptyMap() {
            ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder().build();
            assertNotNull(step.getInputMapping());
            assertTrue(step.getInputMapping().isEmpty());
        }

        @Test
        @DisplayName("TOOL_CALL step has toolName")
        void toolCallStepHasToolName() {
            ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                    .name("Enrich Data")
                    .stepType("TOOL_CALL")
                    .toolName("rag-query")
                    .build();
            assertEquals("TOOL_CALL", step.getStepType());
            assertEquals("rag-query", step.getToolName());
        }

        @Test
        @DisplayName("HUMAN step has suggestedAssignee")
        void humanStepHasSuggestedAssignee() {
            ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                    .name("Review")
                    .stepType("HUMAN")
                    .suggestedAssignee("manager@co.com")
                    .build();
            assertEquals("manager@co.com", step.getSuggestedAssignee());
        }

        @Test
        @DisplayName("EXCEL_COMPUTE step carries graph node IDs")
        void excelComputeStepCarriesNodeIds() {
            ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                    .name("Calculate Revenue")
                    .stepType("EXCEL_COMPUTE")
                    .graphNodeIds(List.of("node-sheet1", "node-cell-A1"))
                    .build();
            assertEquals(2, step.getGraphNodeIds().size());
            assertTrue(step.getGraphNodeIds().contains("node-sheet1"));
        }
    }

    @Nested
    @DisplayName("Full suggestion construction")
    class FullSuggestion {

        @Test
        @DisplayName("email flow suggestion with two phases")
        void emailFlowSuggestion() {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Monthly Report Approval")
                    .description("Analyst submits report, manager approves")
                    .discoverySource("EMAIL_FLOW")
                    .confidence(0.82)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Submission")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Submit Report").stepType("HUMAN")
                                                    .suggestedAssignee("analyst@co.com").build()
                                    ))
                                    .build(),
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Approval")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Manager Review").stepType("APPROVE")
                                                    .suggestedAssignee("manager@co.com").build()
                                    ))
                                    .build()
                    ))
                    .sourceGraphNodeIds(List.of("email-1", "email-2", "email-3"))
                    .evidence(List.of("12 instances of this pattern found", "All within last 6 months"))
                    .build();

            assertEquals("Monthly Report Approval", suggestion.getName());
            assertEquals("EMAIL_FLOW", suggestion.getDiscoverySource());
            assertEquals(0.82, suggestion.getConfidence(), 0.001);
            assertEquals(2, suggestion.getPhases().size());
            assertEquals("Submission", suggestion.getPhases().get(0).getName());
            assertEquals("Approval", suggestion.getPhases().get(1).getName());
            assertEquals(3, suggestion.getSourceGraphNodeIds().size());
            assertEquals(2, suggestion.getEvidence().size());
        }

        @Test
        @DisplayName("excel computation suggestion with input mapping")
        void excelComputationSuggestion() {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Quarterly Revenue Calc")
                    .discoverySource("EXCEL_COMPUTATION")
                    .confidence(0.95)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Compute")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Run Formula")
                                                    .stepType("EXCEL_COMPUTE")
                                                    .graphNodeIds(List.of("cell-revenue-total"))
                                                    .inputMapping(Map.of("q1_sales", "runData.q1", "q2_sales", "runData.q2"))
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            assertEquals("EXCEL_COMPUTATION", suggestion.getDiscoverySource());
            ProcessSuggestion.SuggestedStep step = suggestion.getPhases().get(0).getSteps().get(0);
            assertEquals("EXCEL_COMPUTE", step.getStepType());
            assertEquals(2, step.getInputMapping().size());
            assertEquals("runData.q1", step.getInputMapping().get("q1_sales"));
        }
    }

    @Nested
    @DisplayName("Equals and hashCode via @Data")
    class EqualsAndHash {

        @Test
        @DisplayName("equal suggestions have same hashCode")
        void equalSuggestionsSameHash() {
            ProcessSuggestion a = ProcessSuggestion.builder()
                    .name("test").discoverySource("EMAIL_FLOW").confidence(0.9).build();
            ProcessSuggestion b = ProcessSuggestion.builder()
                    .name("test").discoverySource("EMAIL_FLOW").confidence(0.9).build();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different names produce unequal suggestions")
        void differentNamesNotEqual() {
            ProcessSuggestion a = ProcessSuggestion.builder().name("A").build();
            ProcessSuggestion b = ProcessSuggestion.builder().name("B").build();
            assertNotEquals(a, b);
        }
    }
}
