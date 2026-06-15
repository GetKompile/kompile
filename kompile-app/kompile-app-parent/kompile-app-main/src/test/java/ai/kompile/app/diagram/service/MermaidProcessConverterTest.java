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

package ai.kompile.app.diagram.service;

import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MermaidProcessConverterTest {

    private MermaidProcessConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MermaidProcessConverter();
    }

    // ── fromMermaid: basic parsing ───────────────────────────────────────────

    @Test
    void fromMermaid_simpleFlowchart_parsesNodesAndEdges() {
        String mermaid = """
                flowchart TD
                    A[Start Task] --> B[Process Data]
                    B --> C[End Task]
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "Simple Flow");

        assertEquals("Simple Flow", def.getName());
        assertNotNull(def.getPhases());
        assertEquals(1, def.getPhases().size());

        ProcessPhase phase = def.getPhases().get(0);
        assertEquals("Main", phase.getName());
        assertEquals(3, phase.getSteps().size());

        // Verify step order and types
        Map<String, ProcessStep> steps = phase.getSteps().stream()
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        assertEquals("Start Task", steps.get("A").getName());
        assertEquals(StepType.AUTO, steps.get("A").getStepType());

        assertEquals("Process Data", steps.get("B").getName());
        assertEquals(StepType.AUTO, steps.get("B").getStepType());
        assertTrue(steps.get("B").getDependsOn().contains("A"));

        assertEquals("End Task", steps.get("C").getName());
        assertTrue(steps.get("C").getDependsOn().contains("B"));
    }

    @Test
    void fromMermaid_emptyCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> converter.fromMermaid("", "test"));
        assertThrows(IllegalArgumentException.class, () -> converter.fromMermaid(null, "test"));
        assertThrows(IllegalArgumentException.class, () -> converter.fromMermaid("   ", "test"));
    }

    // ── fromMermaid: shape → StepType mapping ───────────────────────────────

    @Test
    void fromMermaid_shapeInference_mapsCorrectly() {
        String mermaid = """
                flowchart TD
                    A[Auto Step]
                    B{Decision Gate}
                    C([Human Review])
                    D[[Pipeline Run]]
                    E[/Script Exec/]
                    F>Tool Call]
                    G{{Drools Rules}}
                    H[Decision Table Rules]
                    I[Inference Reasoning]
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "Shape Test");
        Map<String, ProcessStep> steps = def.getPhases().get(0).getSteps().stream()
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        assertEquals(StepType.AUTO, steps.get("A").getStepType());
        assertEquals(StepType.CONTROL_GATE, steps.get("B").getStepType());
        assertEquals(StepType.HUMAN, steps.get("C").getStepType());
        assertEquals(StepType.PIPELINE, steps.get("D").getStepType());
        assertEquals(StepType.SCRIPT, steps.get("E").getStepType());
        assertEquals(StepType.TOOL_CALL, steps.get("F").getStepType());
        assertEquals(StepType.DROOLS_RULE, steps.get("G").getStepType());
        assertEquals(StepType.DROOLS_DECISION_TABLE, steps.get("H").getStepType());
        assertEquals(StepType.DROOLS_INFERENCE, steps.get("I").getStepType());
    }

    @Test
    void fromMermaid_circleAndRounded_mapToAuto() {
        String mermaid = """
                flowchart TD
                    A((Circle Node))
                    B(Rounded Node)
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "test");
        Map<String, ProcessStep> steps = def.getPhases().get(0).getSteps().stream()
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        assertEquals(StepType.AUTO, steps.get("A").getStepType());
        assertEquals(StepType.AUTO, steps.get("B").getStepType());
    }

    // ── fromMermaid: subgraphs → phases ─────────────────────────────────────

    @Test
    void fromMermaid_subgraphs_createPhases() {
        String mermaid = """
                flowchart TD
                    subgraph "Data Collection"
                        A[Gather Data] --> B[Validate]
                    end
                    subgraph "Processing"
                        C[Transform] --> D[Load]
                    end
                    B --> C
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "Multi-Phase");

        // Should have 2 subgraph phases (nodes B and C appear in edges connecting phases,
        // but unassigned nodes go to a catch-all if needed)
        assertTrue(def.getPhases().size() >= 2, "Expected at least 2 phases");

        ProcessPhase dataPhase = def.getPhases().get(0);
        assertEquals("Data Collection", dataPhase.getName());
        assertEquals(1, dataPhase.getOrder());

        ProcessPhase procPhase = def.getPhases().get(1);
        assertEquals("Processing", procPhase.getName());
        assertEquals(2, procPhase.getOrder());
    }

    @Test
    void fromMermaid_noSubgraphs_createsSinglePhase() {
        String mermaid = """
                flowchart LR
                    A[Step 1] --> B[Step 2]
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "test");
        assertEquals(1, def.getPhases().size());
        assertEquals("Main", def.getPhases().get(0).getName());
    }

    // ── fromMermaid: edges and dependencies ──────────────────────────────────

    @Test
    void fromMermaid_edgeLabels_storedAsMetadata() {
        String mermaid = """
                flowchart TD
                    A[Check] --> B{Approved?}
                    B -->|Yes| C[Continue]
                    B -->|No| D[Reject]
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "Decision Flow");
        Map<String, ProcessStep> steps = def.getPhases().get(0).getSteps().stream()
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        // B is a CONTROL_GATE — its metadata should have outgoing labels
        ProcessStep decision = steps.get("B");
        assertEquals(StepType.CONTROL_GATE, decision.getStepType());
        assertNotNull(decision.getMetadata());
        assertEquals("Yes", decision.getMetadata().get("outgoingLabel_C"));
        assertEquals("No", decision.getMetadata().get("outgoingLabel_D"));
    }

    @Test
    void fromMermaid_droolsEdgeLabelsBecomeBranchConditions() {
        String mermaid = """
                flowchart TD
                    R{{Eligibility Rules}} -->|APPROVE| A[Approve Request]
                    R -->|REJECT| B[Reject Request]
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "Rules Flow");
        Map<String, ProcessStep> steps = def.getPhases().get(0).getSteps().stream()
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        assertEquals(StepType.DROOLS_RULE, steps.get("R").getStepType());
        assertEquals(List.of("R"), steps.get("A").getDependsOn());
        assertEquals("APPROVE", steps.get("A").getConditionLabel());
        assertTrue(steps.get("A").getConditionExpression().contains("#R_decision"));
        assertTrue(steps.get("A").getConditionExpression().contains("APPROVE"));
        assertEquals("REJECT", steps.get("B").getConditionLabel());
        assertTrue(steps.get("B").getConditionExpression().contains("#R_decision"));
    }

    @Test
    void fromMermaid_multipleDependencies() {
        String mermaid = """
                flowchart TD
                    A[Step A] --> C[Merge]
                    B[Step B] --> C
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "test");
        Map<String, ProcessStep> steps = def.getPhases().get(0).getSteps().stream()
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        ProcessStep merge = steps.get("C");
        assertNotNull(merge.getDependsOn());
        assertEquals(2, merge.getDependsOn().size());
        assertTrue(merge.getDependsOn().containsAll(List.of("A", "B")));
    }

    // ── fromMermaid: comments and classDef lines are skipped ────────────────

    @Test
    void fromMermaid_skipsCommentsAndClassDefs() {
        String mermaid = """
                flowchart TD
                    %% This is a comment
                    A[Step 1] --> B[Step 2]
                    classDef human fill:#e1bee7,stroke:#8e24aa
                    class A human
                    style A fill:#f9f
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "test");
        assertEquals(1, def.getPhases().size());
        assertEquals(2, def.getPhases().get(0).getSteps().size());
    }

    // ── fromMermaid: null processName fallback ──────────────────────────────

    @Test
    void fromMermaid_nullProcessName_usesDefault() {
        String mermaid = "flowchart TD\n    A[Step]";
        ProcessDefinition def = converter.fromMermaid(mermaid, null);
        assertEquals("Converted Process", def.getName());
    }

    // ── toMermaid: basic rendering ──────────────────────────────────────────

    @Test
    void toMermaid_singlePhase_noSubgraphs() {
        ProcessDefinition def = ProcessDefinition.builder()
                .name("Test")
                .phases(List.of(ProcessPhase.builder()
                        .id("p1")
                        .name("Phase 1")
                        .order(1)
                        .steps(List.of(
                                ProcessStep.builder().id("s1").name("Start").stepType(StepType.AUTO).build(),
                                ProcessStep.builder().id("s2").name("End").stepType(StepType.AUTO)
                                        .dependsOn(List.of("s1")).build()
                        ))
                        .build()))
                .build();

        String mermaid = converter.toMermaid(def);

        assertTrue(mermaid.startsWith("flowchart TD"));
        assertTrue(mermaid.contains("s1[\"Start\"]"));
        assertTrue(mermaid.contains("s2[\"End\"]"));
        assertTrue(mermaid.contains("s1 --> s2"));
        // Single phase should NOT produce subgraphs
        assertFalse(mermaid.contains("subgraph"));
    }

    @Test
    void toMermaid_multiplePhases_usesSubgraphs() {
        ProcessDefinition def = ProcessDefinition.builder()
                .name("Test")
                .phases(List.of(
                        ProcessPhase.builder()
                                .id("p1").name("Phase 1").order(1)
                                .steps(List.of(
                                        ProcessStep.builder().id("s1").name("Step 1").stepType(StepType.AUTO).build()
                                ))
                                .build(),
                        ProcessPhase.builder()
                                .id("p2").name("Phase 2").order(2)
                                .steps(List.of(
                                        ProcessStep.builder().id("s2").name("Step 2").stepType(StepType.HUMAN)
                                                .dependsOn(List.of("s1")).build()
                                ))
                                .build()
                ))
                .build();

        String mermaid = converter.toMermaid(def);

        assertTrue(mermaid.contains("subgraph p1[\"Phase 1\"]"));
        assertTrue(mermaid.contains("subgraph p2[\"Phase 2\"]"));
        assertTrue(mermaid.contains("end"));
    }

    // ── toMermaid: StepType → shape rendering ───────────────────────────────

    @Test
    void toMermaid_stepTypeShapes() {
        ProcessDefinition def = ProcessDefinition.builder()
                .name("Test")
                .phases(List.of(ProcessPhase.builder()
                        .id("p1").name("Phase").order(1)
                        .steps(List.of(
                                ProcessStep.builder().id("a").name("Auto").stepType(StepType.AUTO).build(),
                                ProcessStep.builder().id("h").name("Human").stepType(StepType.HUMAN).build(),
                                ProcessStep.builder().id("g").name("Gate").stepType(StepType.CONTROL_GATE).build(),
                                ProcessStep.builder().id("pl").name("Pipe").stepType(StepType.PIPELINE).build(),
                                ProcessStep.builder().id("sc").name("Script").stepType(StepType.SCRIPT).build(),
                                ProcessStep.builder().id("tc").name("Tool").stepType(StepType.TOOL_CALL).build(),
                                ProcessStep.builder().id("dr").name("Rules").stepType(StepType.DROOLS_RULE).build(),
                                ProcessStep.builder().id("di").name("Reason").stepType(StepType.DROOLS_INFERENCE).build(),
                                ProcessStep.builder().id("dt").name("Table").stepType(StepType.DROOLS_DECISION_TABLE).build(),
                                ProcessStep.builder().id("ap").name("Approve").stepType(StepType.APPROVE).build()
                        ))
                        .build()))
                .build();

        String mermaid = converter.toMermaid(def);

        assertTrue(mermaid.contains("a[\"Auto\"]"), "AUTO → rectangle");
        assertTrue(mermaid.contains("h([\"Human\"])"), "HUMAN → stadium");
        assertTrue(mermaid.contains("g{\"Gate\"}"), "CONTROL_GATE → diamond");
        assertTrue(mermaid.contains("pl[[\"Pipe\"]]"), "PIPELINE → subroutine");
        assertTrue(mermaid.contains("sc[/\"Script\"/]"), "SCRIPT → parallelogram");
        assertTrue(mermaid.contains("tc>\"Tool\"]"), "TOOL_CALL → asymmetric");
        assertTrue(mermaid.contains("dr{{\"Rules\"}}"), "DROOLS_RULE → hexagon");
        assertTrue(mermaid.contains("di{{\"Reason\"}}"), "DROOLS_INFERENCE → hexagon");
        assertTrue(mermaid.contains("dt{{\"Table\"}}"), "DROOLS_DECISION_TABLE → hexagon");
        assertTrue(mermaid.contains("ap([\"Approve\"])"), "APPROVE → stadium");
    }

    @Test
    void toMermaid_rendersDroolsBranchLabels() {
        ProcessDefinition def = ProcessDefinition.builder()
                .name("Rules Flow")
                .phases(List.of(ProcessPhase.builder()
                        .id("p1").name("Main").order(1)
                        .steps(List.of(
                                ProcessStep.builder().id("R").name("Eligibility Rules")
                                        .stepType(StepType.DROOLS_RULE).build(),
                                ProcessStep.builder().id("A").name("Approve Request")
                                        .stepType(StepType.AUTO)
                                        .dependsOn(List.of("R"))
                                        .conditionLabel("APPROVE")
                                        .conditionExpression("#R_decision == 'APPROVE'")
                                        .build()
                        ))
                        .build()))
                .build();

        String mermaid = converter.toMermaid(def);

        assertTrue(mermaid.contains("R{{\"Eligibility Rules\"}}"));
        assertTrue(mermaid.contains("R -->|APPROVE| A"));
        assertTrue(mermaid.contains("class R drools"));
    }

    @Test
    void toMermaid_classDefs_included() {
        ProcessDefinition def = ProcessDefinition.builder()
                .name("Test")
                .phases(List.of(ProcessPhase.builder()
                        .id("p1").name("Phase").order(1)
                        .steps(List.of(
                                ProcessStep.builder().id("h").name("Human").stepType(StepType.HUMAN).build()
                        ))
                        .build()))
                .build();

        String mermaid = converter.toMermaid(def);

        assertTrue(mermaid.contains("classDef human"));
        assertTrue(mermaid.contains("classDef drools"));
        assertTrue(mermaid.contains("class h human"));
    }

    // ── toMermaid: edge cases ────────────────────────────────────────────────

    @Test
    void toMermaid_nullDefinition_returnsPlaceholder() {
        String result = converter.toMermaid(null);
        assertTrue(result.contains("No process defined"));
    }

    @Test
    void toMermaid_emptyPhases_returnsPlaceholder() {
        ProcessDefinition def = ProcessDefinition.builder().name("Test").phases(null).build();
        String result = converter.toMermaid(def);
        assertTrue(result.contains("No process defined"));
    }

    @Test
    void toMermaid_sanitizesSpecialCharsInIds() {
        ProcessDefinition def = ProcessDefinition.builder()
                .name("Test")
                .phases(List.of(ProcessPhase.builder()
                        .id("phase-1")
                        .name("Test Phase")
                        .order(1)
                        .steps(List.of(
                                ProcessStep.builder().id("step-1.1").name("Step With Dots").stepType(StepType.AUTO).build()
                        ))
                        .build()))
                .build();

        String mermaid = converter.toMermaid(def);
        // IDs with special chars should be sanitized to underscores
        assertTrue(mermaid.contains("step_1_1"));
    }

    // ── Round-trip tests ────────────────────────────────────────────────────

    @Test
    void roundTrip_fromMermaid_toMermaid_preservesStructure() {
        String original = """
                flowchart TD
                    A[Gather Requirements] --> B{Review Complete?}
                    B -->|Yes| C([Manual Approval])
                    B -->|No| D[Revise]
                    D --> A
                    C --> E[[Run Pipeline]]
                    E --> F[Done]
                """;

        ProcessDefinition def = converter.fromMermaid(original, "Round Trip");
        String rendered = converter.toMermaid(def);

        // Re-parse the rendered output
        ProcessDefinition roundTripped = converter.fromMermaid(rendered, "Round Trip 2");

        // Same number of steps
        int originalSteps = def.getPhases().stream()
                .mapToInt(p -> p.getSteps() != null ? p.getSteps().size() : 0).sum();
        int roundTrippedSteps = roundTripped.getPhases().stream()
                .mapToInt(p -> p.getSteps() != null ? p.getSteps().size() : 0).sum();
        assertEquals(originalSteps, roundTrippedSteps, "Step count should survive round-trip");

        // Collect step types from original
        Set<StepType> originalTypes = def.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .map(ProcessStep::getStepType)
                .collect(Collectors.toSet());
        Set<StepType> roundTrippedTypes = roundTripped.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .map(ProcessStep::getStepType)
                .collect(Collectors.toSet());
        assertEquals(originalTypes, roundTrippedTypes, "StepTypes should survive round-trip");
    }

    @Test
    void roundTrip_toMermaid_fromMermaid_preservesStepTypes() {
        ProcessDefinition original = ProcessDefinition.builder()
                .name("RT Test")
                .phases(List.of(ProcessPhase.builder()
                        .id("p1").name("Main").order(1)
                        .steps(List.of(
                                ProcessStep.builder().id("s1").name("Auto Step").stepType(StepType.AUTO).build(),
                                ProcessStep.builder().id("s2").name("Gate").stepType(StepType.CONTROL_GATE)
                                        .dependsOn(List.of("s1")).build(),
                                ProcessStep.builder().id("s3").name("Human Task").stepType(StepType.HUMAN)
                                        .dependsOn(List.of("s2")).build(),
                                ProcessStep.builder().id("s4").name("Pipeline").stepType(StepType.PIPELINE)
                                        .dependsOn(List.of("s3")).build()
                        ))
                        .build()))
                .build();

        String mermaid = converter.toMermaid(original);
        ProcessDefinition parsed = converter.fromMermaid(mermaid, "RT Parsed");

        Map<String, ProcessStep> steps = parsed.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .collect(Collectors.toMap(ProcessStep::getId, s -> s));

        assertEquals(StepType.AUTO, steps.get("s1").getStepType());
        assertEquals(StepType.CONTROL_GATE, steps.get("s2").getStepType());
        assertEquals(StepType.HUMAN, steps.get("s3").getStepType());
        assertEquals(StepType.PIPELINE, steps.get("s4").getStepType());
    }

    // ── fromMermaid: realistic business process ─────────────────────────────

    @Test
    void fromMermaid_realisticInvoiceProcess() {
        String mermaid = """
                flowchart TD
                    subgraph "Receipt"
                        A([Receive Invoice]) --> B[/Extract Data/]
                        B --> C{Amount > 10K?}
                    end
                    subgraph "Approval"
                        C -->|Yes| D([Manager Approval])
                        C -->|No| E[Auto Approve]
                        D --> F[[Run Compliance Check]]
                        E --> F
                    end
                    subgraph "Payment"
                        F --> G>Schedule Payment]
                        G --> H[Record in Ledger]
                    end
                """;

        ProcessDefinition def = converter.fromMermaid(mermaid, "Invoice Process");

        assertEquals("Invoice Process", def.getName());
        assertTrue(def.getPhases().size() >= 2, "Should have at least 2 phases");

        // Verify key step types (use merge function since a node can appear in multiple phases)
        Map<String, ProcessStep> allSteps = def.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .collect(Collectors.toMap(ProcessStep::getId, s -> s, (a, b) -> a));

        assertEquals(StepType.HUMAN, allSteps.get("A").getStepType(), "Stadium → HUMAN");
        assertEquals(StepType.SCRIPT, allSteps.get("B").getStepType(), "Parallelogram → SCRIPT");
        assertEquals(StepType.CONTROL_GATE, allSteps.get("C").getStepType(), "Diamond → CONTROL_GATE");
        assertEquals(StepType.HUMAN, allSteps.get("D").getStepType(), "Stadium → HUMAN");
        assertEquals(StepType.PIPELINE, allSteps.get("F").getStepType(), "Subroutine → PIPELINE");
        assertEquals(StepType.TOOL_CALL, allSteps.get("G").getStepType(), "Asymmetric → TOOL_CALL");
    }
}
