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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FlowPattern model")
class FlowPatternTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("steps list defaults to empty, not null")
        void stepsDefaultToEmptyList() {
            FlowPattern fp = FlowPattern.builder()
                    .type("EMAIL_FLOW")
                    .build();
            assertNotNull(fp.getSteps());
            assertTrue(fp.getSteps().isEmpty());
        }

        @Test
        @DisplayName("involvedNodeIds defaults to empty, not null")
        void involvedNodeIdsDefaultToEmptyList() {
            FlowPattern fp = FlowPattern.builder().build();
            assertNotNull(fp.getInvolvedNodeIds());
            assertTrue(fp.getInvolvedNodeIds().isEmpty());
        }

        @Test
        @DisplayName("no-arg constructor produces empty collections from field defaults")
        void noArgConstructorCollections() {
            FlowPattern fp = new FlowPattern();
            // Lombok @NoArgsConstructor uses field initializers, so lists are non-null
            assertNotNull(fp.getSteps());
            assertTrue(fp.getSteps().isEmpty());
            assertNotNull(fp.getInvolvedNodeIds());
            assertTrue(fp.getInvolvedNodeIds().isEmpty());
        }
    }

    @Nested
    @DisplayName("FlowStep builder")
    class FlowStepBuilder {

        @Test
        @DisplayName("builds a step with all fields")
        void buildsWithAllFields() {
            FlowPattern.FlowStep step = FlowPattern.FlowStep.builder()
                    .description("Person A sends email to Person B")
                    .actor("alice@example.com")
                    .action("SEND")
                    .target("bob@example.com")
                    .nodeId("node-123")
                    .build();

            assertEquals("Person A sends email to Person B", step.getDescription());
            assertEquals("alice@example.com", step.getActor());
            assertEquals("SEND", step.getAction());
            assertEquals("bob@example.com", step.getTarget());
            assertEquals("node-123", step.getNodeId());
        }

        @Test
        @DisplayName("step fields are independently settable via setters")
        void settersMutateIndependently() {
            FlowPattern.FlowStep step = new FlowPattern.FlowStep();
            step.setAction("COMPUTE");
            step.setNodeId("cell-A1");
            assertEquals("COMPUTE", step.getAction());
            assertEquals("cell-A1", step.getNodeId());
            assertNull(step.getActor());
        }
    }

    @Nested
    @DisplayName("Full pattern construction")
    class FullPattern {

        @Test
        @DisplayName("email flow pattern with multiple steps")
        void emailFlowWithMultipleSteps() {
            FlowPattern pattern = FlowPattern.builder()
                    .type("EMAIL_FLOW")
                    .description("Monthly report submission")
                    .occurrenceCount(12)
                    .confidence(0.87)
                    .steps(List.of(
                            FlowPattern.FlowStep.builder()
                                    .actor("analyst@co.com").action("SEND").target("manager@co.com")
                                    .description("Analyst sends report").build(),
                            FlowPattern.FlowStep.builder()
                                    .actor("manager@co.com").action("APPROVE").target("analyst@co.com")
                                    .description("Manager approves report").build()
                    ))
                    .involvedNodeIds(List.of("node-a", "node-b", "node-c"))
                    .build();

            assertEquals("EMAIL_FLOW", pattern.getType());
            assertEquals(12, pattern.getOccurrenceCount());
            assertEquals(0.87, pattern.getConfidence(), 0.001);
            assertEquals(2, pattern.getSteps().size());
            assertEquals(3, pattern.getInvolvedNodeIds().size());
            assertEquals("SEND", pattern.getSteps().get(0).getAction());
            assertEquals("APPROVE", pattern.getSteps().get(1).getAction());
        }

        @Test
        @DisplayName("excel computation pattern")
        void excelComputationPattern() {
            FlowPattern pattern = FlowPattern.builder()
                    .type("EXCEL_COMPUTATION")
                    .description("Quarterly revenue calculation")
                    .occurrenceCount(4)
                    .confidence(0.95)
                    .steps(List.of(
                            FlowPattern.FlowStep.builder()
                                    .actor("Sheet1!A1").action("COMPUTE").target("Sheet1!B1")
                                    .nodeId("cell-a1").build()
                    ))
                    .build();

            assertEquals("EXCEL_COMPUTATION", pattern.getType());
            assertEquals(1, pattern.getSteps().size());
            assertEquals("cell-a1", pattern.getSteps().get(0).getNodeId());
        }
    }

    @Nested
    @DisplayName("Equals and hashCode via @Data")
    class EqualsAndHash {

        @Test
        @DisplayName("equal patterns have equal hashCodes")
        void equalPatternsEqualHash() {
            FlowPattern a = FlowPattern.builder().type("T").confidence(0.5).occurrenceCount(3).build();
            FlowPattern b = FlowPattern.builder().type("T").confidence(0.5).occurrenceCount(3).build();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different types produce unequal patterns")
        void differentTypesNotEqual() {
            FlowPattern a = FlowPattern.builder().type("EMAIL_FLOW").build();
            FlowPattern b = FlowPattern.builder().type("EXCEL_COMPUTATION").build();
            assertNotEquals(a, b);
        }
    }
}
