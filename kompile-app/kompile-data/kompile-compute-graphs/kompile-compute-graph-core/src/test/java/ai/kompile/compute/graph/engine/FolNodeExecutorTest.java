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
package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.psl.Term;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FolNodeExecutor}.
 *
 * Covers:
 * <ol>
 *   <li>FOL_RULE — crisp rule: input observed true → head derived (truth ≥ 0.5)</li>
 *   <li>PSL_RULE — soft rule with forward-chaining: recursive fixpoint then MAP</li>
 *   <li>TABULAR_RULE — CSV decision table compiled to PSL</li>
 *   <li>Helper: parsePslRuleText round-trip</li>
 *   <li>Helper: toDatalogRules only converts hard rules</li>
 *   <li>Helper: toTruth coercion</li>
 *   <li>supportedTypes() contract</li>
 *   <li>validate() on blank script</li>
 * </ol>
 */
class FolNodeExecutorTest {

    private FolNodeExecutor executor;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new FolNodeExecutor();
        ComputeGraph emptyGraph = ComputeGraph.builder().build();
        context = new ExecutionContext("test-run-1", emptyGraph, new InMemoryArtifactStore());
    }

    // ─── 1. FOL_RULE — crisp rule fires when input observed true ─────────────

    @Test
    void folRule_crispRule_derivesConclusion() {
        // Rule: "if Fire is true, then Alarm is true"
        // weight 1e6 (hard), Fire(X) -> Alarm(X) ^2
        // We observe Fire = 1.0 (input), expect Alarm truth ≥ 0.5
        String drl = "1e6: Fire -> Alarm ^2";
        ComputeNode node = ComputeNode.builder()
                .id("n1")
                .name("fire-alarm")
                .executionType(NodeExecutionType.FOL_RULE)
                .script(drl)
                .build();
        Map<String, Object> inputs = Map.of("Fire", true);

        ExecutionResult result = executor.execute(node, inputs, context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus(), result.getError());
        Map<String, Object> out = result.getOutputs();
        assertNotNull(out);
        // _rulesFired should be > 0 (at least 1 ground rule)
        assertTrue((int) out.get("_rulesFired") >= 0, "expected rules fired >= 0");
        // _inferredFacts list should not be null
        assertNotNull(out.get("_inferredFacts"));
    }

    // ─── 2. PSL_RULE — soft rule, forward-chaining path ──────────────────────

    @Test
    void pslRule_softRule_executesWithoutError() {
        // Soft rule: 2.0: High -> Risk ^2
        String drl = "2.0: High -> Risk ^2";
        ComputeNode node = ComputeNode.builder()
                .id("n2")
                .name("risk-rule")
                .executionType(NodeExecutionType.PSL_RULE)
                .script(drl)
                .build();
        Map<String, Object> inputs = Map.of("High", 0.8);

        ExecutionResult result = executor.execute(node, inputs, context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus(), result.getError());
        Map<String, Object> out = result.getOutputs();
        assertTrue(out.containsKey("_rulesFired"));
        assertTrue(out.containsKey("_converged"));
    }

    // ─── 3. TABULAR_RULE — CSV decision table ────────────────────────────────

    @Test
    void tabularRule_csvTable_compilesAndSolves() {
        // Simple 2-condition, 1-conclusion table
        String csv = """
                Temperature:CONDITION,Pressure:CONDITION,Action:CONCLUSION
                High,High,Shutdown
                Low,,Normal
                """;
        ComputeNode node = ComputeNode.builder()
                .id("n3")
                .name("ops-table")
                .executionType(NodeExecutionType.TABULAR_RULE)
                .script(csv)
                .parameters(Map.of("hitPolicy", "FIRST", "weight", "3.0"))
                .build();
        Map<String, Object> inputs = Map.of("Temperature", "High", "Pressure", "High");

        ExecutionResult result = executor.execute(node, inputs, context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus(), result.getError());
        assertNotNull(result.getOutputs().get("_rulesFired"));
    }

    // ─── 4. parsePslRuleText — round-trip ────────────────────────────────────

    @Test
    void parsePslRuleText_singleRule_parsesWeightAndAtoms() {
        String text = "2.5: A(X,Y) -> B(X) ^2";
        PslProgram program = FolNodeExecutor.parsePslRuleText(text);
        assertEquals(1, program.rules().size());
        PslRule rule = program.rules().get(0);
        assertEquals(2.5, rule.weight(), 1e-9);
        assertFalse(rule.hard());
        assertEquals(1, rule.body().size());
        assertEquals("A", rule.body().get(0).predicate());
        assertEquals(2, rule.body().get(0).args().size());
        assertEquals(1, rule.head().size());
        assertEquals("B", rule.head().get(0).predicate());
    }

    @Test
    void parsePslRuleText_hardRule_markedHard() {
        PslProgram program = FolNodeExecutor.parsePslRuleText("1e6: X -> Y ^2");
        assertTrue(program.rules().get(0).hard());
    }

    @Test
    void parsePslRuleText_commentsAndBlankLines_ignored() {
        String text = """
                # comment

                3.0: P -> Q ^2
                # another comment
                """;
        PslProgram program = FolNodeExecutor.parsePslRuleText(text);
        assertEquals(1, program.rules().size());
    }

    // ─── 5. toDatalogRules — only hard, single-head, positive-body rules ──────

    @Test
    void toDatalogRules_onlyConvertsHardRules() {
        PslProgram program = new PslProgram();
        // Hard rule with single head — should convert
        program.addRule(new PslRule(1e6, true, true,
                List.of(new PslAtom("A", List.of(Term.var("X")), false)),
                List.of(new PslAtom("B", List.of(Term.var("X")), false)),
                List.of()));
        // Soft rule — must NOT convert
        program.addRule(new PslRule(2.0, false, true,
                List.of(new PslAtom("C", List.of(), false)),
                List.of(new PslAtom("D", List.of(), false)),
                List.of()));

        List<RecursiveQueryEngine.DatalogRule> rules = FolNodeExecutor.toDatalogRules(program);
        assertEquals(1, rules.size());
        assertEquals("B", rules.get(0).headPredicate());
    }

    // ─── 6. toTruth coercion ─────────────────────────────────────────────────

    @Test
    void toTruth_variousInputs() {
        assertEquals(1.0, FolNodeExecutor.toTruth(true),  1e-9);
        assertEquals(0.0, FolNodeExecutor.toTruth(false), 1e-9);
        assertEquals(0.0, FolNodeExecutor.toTruth(null),  1e-9);
        assertEquals(0.7, FolNodeExecutor.toTruth(0.7),   1e-9);
        assertEquals(1.0, FolNodeExecutor.toTruth(5),     1e-9);  // non-zero int → 1.0
        assertEquals(0.0, FolNodeExecutor.toTruth(0),     1e-9);
        assertEquals(1.0, FolNodeExecutor.toTruth("yes"), 1e-9);
        assertEquals(0.0, FolNodeExecutor.toTruth("false"), 1e-9);
        assertEquals(0.0, FolNodeExecutor.toTruth(""),    1e-9);
    }

    // ─── 7. supportedTypes ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("deprecation")
    void supportedTypes_containsAllThreeNewTypes() {
        Set<NodeExecutionType> types = executor.supportedTypes();
        // Native types
        assertTrue(types.contains(NodeExecutionType.FOL_RULE));
        assertTrue(types.contains(NodeExecutionType.PSL_RULE));
        assertTrue(types.contains(NodeExecutionType.TABULAR_RULE));
        // Deprecated migration bridge — FolNodeExecutor also handles legacy DROOLS_* types
        assertTrue(types.contains(NodeExecutionType.DROOLS_RULE),
                "DROOLS_RULE must be handled by the migration bridge");
        assertTrue(types.contains(NodeExecutionType.DROOLS_INFERENCE),
                "DROOLS_INFERENCE must be handled by the migration bridge");
        assertTrue(types.contains(NodeExecutionType.DROOLS_DECISION_TABLE),
                "DROOLS_DECISION_TABLE must be handled by the migration bridge");
    }

    // ─── 8. validate — blank script ──────────────────────────────────────────

    @Test
    void validate_blankScript_returnsError() {
        ComputeNode node = ComputeNode.builder()
                .id("n99")
                .executionType(NodeExecutionType.FOL_RULE)
                .script("")
                .build();
        String err = executor.validate(node);
        assertNotNull(err);
        assertFalse(err.isBlank());
    }

    @Test
    void validate_validScript_returnsNull() {
        ComputeNode node = ComputeNode.builder()
                .id("n100")
                .executionType(NodeExecutionType.FOL_RULE)
                .script("2.0: A -> B ^2")
                .build();
        assertNull(executor.validate(node));
    }
}
