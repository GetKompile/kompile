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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.type.AttributeDefinition;
import ai.kompile.graph.reasoning.mebn.type.TypeAttributeSchema;
import ai.kompile.graph.reasoning.mebn.type.TypeConstraint;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.psl.GroundRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeConstraintFolRuleCompilerTest {

    @Test
    @DisplayName("Type hierarchy constraints compile to hard FOL rules")
    void compilesRelationAndRequiredAttributeRules() {
        TypeAttributeSchema employeeSchema = TypeAttributeSchema.builder()
                .add(AttributeDefinition.requiredString("employeeId"))
                .build();
        TypeRegistry registry = new TypeRegistry()
                .declare("Employee", employeeSchema)
                .declare("Person")
                .declare("Robot")
                .constraint("Employee",
                        new TypeConstraint.RelationConstraint("MANAGES", "Employee", "Person"));

        TypeHierarchy hierarchy = registry.buildFor(schemaViolationGraph());
        FolRuleSet rules = TypeConstraintFolRuleCompiler.compile(hierarchy);

        assertEquals(2, rules.size());
        assertTrue(rules.rules().stream().allMatch(rule -> rule.weight() == Double.POSITIVE_INFINITY));
        assertTrue(rules.rules().stream().anyMatch(rule ->
                rule.name().equals("schema_relation_MANAGES_Employee_Person")));
        assertTrue(rules.rules().stream().anyMatch(rule ->
                rule.name().equals("schema_required_Employee_employeeId")));

        FolInferenceResult result = new FolInferenceService().infer(schemaViolationGraph(), rules);
        List<GroundRule> hardViolations = result.pslResult().hardViolations();

        assertFalse(hardViolations.isEmpty(), "Schema violations must surface as hard PSL violations");
        assertTrue(hardViolations.stream().anyMatch(rule ->
                rule.toString().contains("schema_relation_MANAGES_Employee_Person")));
        assertTrue(hardViolations.stream().anyMatch(rule ->
                rule.toString().contains("schema_required_Employee_employeeId")));
    }

    private static MutableReasoningGraph schemaViolationGraph() {
        return new MutableReasoningGraph()
                .addEntity(GraphEntity.builder("alice")
                        .type("Employee")
                        .label("Alice")
                        .attribute("employeeId", "E-1")
                        .build())
                .addEntity(GraphEntity.builder("eve")
                        .type("Employee")
                        .label("Eve")
                        .build())
                .addEntity(GraphEntity.builder("bob")
                        .type("Person")
                        .label("Bob")
                        .build())
                .addEntity(GraphEntity.builder("bot")
                        .type("Robot")
                        .label("Bot")
                        .build())
                .addRelation("ok", "alice", "bob", "MANAGES", 1.0)
                .addRelation("bad-domain", "bot", "bob", "MANAGES", 1.0);
    }
}
