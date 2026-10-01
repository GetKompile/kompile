/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery;
import ai.kompile.graph.reasoning.quantitative.QuantitativeScenarioEngine.GoalSeekResult;
import ai.kompile.graph.reasoning.quantitative.ScenarioResult;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Intent;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Query;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Result;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantitativeRequestParserTest {

    @Test
    void parsesEveryMemberOfAScenarioSpec() {
        QuantitativeQuery query = QuantitativeRequestParser.parse(Intent.SCENARIO, Map.of(
                "target", Map.of("text", "Gross Margin", "unit", "ratio",
                        "dimensions", Map.of("region", "APAC")),
                "interventions", List.of(Map.of(
                        "target", Map.of("entityId", "sales"), "operation", "scale", "value", 0.25)),
                "dimensions", Map.of("region", "APAC", "year", 2026),
                "asOf", "2026-06-30",
                "topK", 5), null, null);

        assertEquals(QuantitativeQuery.Mode.SCENARIO, query.mode());
        assertEquals("Gross Margin", query.target().text());
        assertEquals("ratio", query.target().unit());
        assertEquals(Map.of("region", "APAC"), query.target().dimensions());
        assertEquals(1, query.interventions().size());
        QuantitativeQuery.Intervention intervention = query.interventions().get(0);
        assertEquals("sales", intervention.target().entityId());
        assertEquals(QuantitativeQuery.Operation.SCALE, intervention.operation());
        assertEquals(0.25, intervention.value(), 0.0);
        assertEquals(Map.of("region", "APAC", "year", "2026"), query.dimensions());
        assertEquals(Instant.parse("2026-06-30T00:00:00Z"), query.asOf());
        assertEquals(5, query.topK());
        assertNull(query.goal());
    }

    @Test
    void acceptsAJsonStringSpecWithBareStringSelectorsAndASingleIntervention() {
        QuantitativeQuery query = QuantitativeRequestParser.parse(Intent.SCENARIO,
                "{\"target\":\"Total\",\"interventions\":"
                        + "{\"target\":\"Input A\",\"operation\":\"ADD\",\"value\":\"5\"}}",
                null, null);

        assertEquals("Total", query.target().text());
        assertEquals(1, query.interventions().size());
        assertEquals("Input A", query.interventions().get(0).target().text());
        assertEquals(QuantitativeQuery.Operation.ADD, query.interventions().get(0).operation());
        assertEquals(5.0, query.interventions().get(0).value(), 0.0);
    }

    @Test
    void anInterventionMustNameItsOperation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SCENARIO, Map.of(
                        "target", "Gross Margin",
                        "interventions", List.of(Map.of("target", "Sales", "value", 0.1))), null, null));

        assertTrue(error.getMessage().contains("quantitative.interventions[0].operation is required"),
                error.getMessage());
        assertTrue(error.getMessage().contains("SET, ADD, SCALE"), error.getMessage());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SCENARIO, Map.of(
                        "target", "Gross Margin",
                        "interventions", List.of(Map.of(
                                "target", "Sales", "operation", "increase", "value", 0.1))), null, null));
        assertTrue(unknown.getMessage().contains("must be one of SET, ADD, SCALE"), unknown.getMessage());
    }

    @Test
    void unknownKeysAreRejectedWithTheValidKeySet() {
        IllegalArgumentException selector = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.CALCULATE,
                        Map.of("target", Map.of("name", "Sales")), null, null));
        assertTrue(selector.getMessage().contains("Unknown key quantitative.target.name"),
                selector.getMessage());
        assertTrue(selector.getMessage().contains("entityId, text, type, unit, dimensions"),
                selector.getMessage());

        IllegalArgumentException spec = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.CALCULATE,
                        Map.of("target", "Sales", "mode", "SCENARIO"), null, null));
        assertTrue(spec.getMessage().contains("Unknown key quantitative.mode"), spec.getMessage());
    }

    @Test
    void missingContractMembersAreNamedWithAnExample() {
        IllegalArgumentException scenario = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SCENARIO,
                        Map.of("target", "Gross Margin"), null, null));
        assertTrue(scenario.getMessage().startsWith("SCENARIO requires quantitative.interventions."),
                scenario.getMessage());
        assertTrue(scenario.getMessage().contains("Example: quantitative={\"target\""),
                scenario.getMessage());
        assertTrue(scenario.getMessage().contains("\"operation\":\"SCALE\""), scenario.getMessage());

        IllegalArgumentException solve = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SOLVE_TARGET,
                        Map.of("target", "Gross Margin"), null, null));
        assertTrue(solve.getMessage().startsWith("SOLVE_TARGET requires quantitative.goal."),
                solve.getMessage());

        IllegalArgumentException models = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.MODELS, null, null, null));
        assertTrue(models.getMessage().startsWith("MODELS requires quantitative.target."),
                models.getMessage());

        IllegalArgumentException unspecified = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.MODELS,
                        Map.of("target", Map.of("unit", "USD")), null, null));
        assertTrue(unspecified.getMessage().contains("quantitative.target needs entityId, text, or type"),
                unspecified.getMessage());
    }

    @Test
    void nonQuantitativeIntentsTakeNoSpec() {
        assertNull(QuantitativeRequestParser.parse(Intent.SEARCH, null, 5, null));
        assertNull(QuantitativeRequestParser.parse(Intent.SEARCH, Map.of(), 5, null));
        assertNull(QuantitativeRequestParser.parse(Intent.SEARCH, "  ", 5, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SEARCH, Map.of("target", "Sales"), 5, null));
        assertTrue(error.getMessage().contains(
                "only applies to MODELS, CALCULATE, SCENARIO, SOLVE_TARGET, not SEARCH"), error.getMessage());
    }

    @Test
    void goalsNeedOrderedFiniteBoundsAndNumbersAreNeverGuessed() {
        IllegalArgumentException bounds = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SOLVE_TARGET, Map.of(
                        "target", "Gross Margin",
                        "goal", Map.of("control", "Sales", "targetValue", 0.5,
                                "minimum", 10, "maximum", 5)), null, null));
        assertTrue(bounds.getMessage().contains("quantitative.goal.minimum must be below"),
                bounds.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SOLVE_TARGET, Map.of(
                        "target", "Gross Margin",
                        "goal", Map.of("control", "Sales", "minimum", 0, "maximum", 5)), null, null));
        assertTrue(missing.getMessage().contains("missing targetValue"), missing.getMessage());

        IllegalArgumentException percent = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.SCENARIO, Map.of(
                        "target", "Gross Margin",
                        "interventions", List.of(Map.of(
                                "target", "Sales", "operation", "SCALE", "value", "16%"))), null, null));
        assertTrue(percent.getMessage().contains("quantitative.interventions[0].value must be a number"),
                percent.getMessage());

        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class, () ->
                QuantitativeRequestParser.parse(Intent.CALCULATE, "{\"target\":", null, null));
        assertTrue(malformed.getMessage().startsWith("quantitative is not valid JSON"),
                malformed.getMessage());
    }

    @Test
    void specTopKWinsOverTheRequestTopK() {
        assertEquals(7, QuantitativeRequestParser.parse(
                Intent.MODELS, Map.of("target", "Sales"), 7, null).topK());
        assertEquals(3, QuantitativeRequestParser.parse(
                Intent.MODELS, Map.of("target", "Sales", "topK", 3), 7, null).topK());
        assertEquals(10, QuantitativeRequestParser.parse(
                Intent.MODELS, Map.of("target", "Sales"), null, null).topK());
    }

    @Test
    void theEngineContractDecidesWhichIntentsAreQuantitative() {
        assertEquals(List.of(Intent.MODELS, Intent.CALCULATE, Intent.SCENARIO, Intent.SOLVE_TARGET),
                QuantitativeRequestParser.quantitativeIntents());
        assertFalse(QuantitativeRequestParser.isQuantitative(Intent.SEARCH));
        assertTrue(GraphQueryEngine.capabilityContract().stream()
                .filter(QuantitativeRequestParser::isQuantitative)
                .allMatch(capability -> capability.requiredFields().contains("quantitative.target")));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) QuantitativeRequestParser.jsonSchema().get("properties");
        assertEquals(List.of("target", "interventions", "goal", "dimensions", "asOf", "topK"),
                List.copyOf(properties.keySet()));
    }

    @Test
    void capabilitiesCarryTheGuidanceAndEveryExampleParses() {
        String guidance = QuantitativeRequestParser.guidance();
        assertTrue(guidance.startsWith("MODELS, CALCULATE, SCENARIO, SOLVE_TARGET take a quantitative object."),
                guidance);
        assertTrue(guidance.contains("(SET, ADD, SCALE)"), guidance);
        for (Intent intent : QuantitativeRequestParser.quantitativeIntents()) {
            String example = QuantitativeRequestParser.example(intent);
            assertTrue(example.startsWith(QuantitativeRequestParser.FIELD + "="), example);
            assertEquals(QuantitativeQuery.Mode.valueOf(intent.name()), QuantitativeRequestParser.parse(intent,
                    example.substring(QuantitativeRequestParser.FIELD.length() + 1), null, null).mode());
        }

        Result capabilities = new GraphQueryEngine().query(new MutableReasoningGraph(), Query.capabilities());
        assertTrue(capabilities.guidance().contains(guidance), String.valueOf(capabilities.guidance()));
    }

    @Test
    void parsedRequestsRunThroughTheEngine() {
        GraphQueryEngine engine = new GraphQueryEngine();

        Result calculated = engine.query(formulaGraph(), query(Intent.CALCULATE,
                "{\"target\":{\"entityId\":\"a3\"}}"));
        assertEquals(Status.OK, calculated.status(), calculated.summary());
        assertEquals("Calculated a3 = 30.0.", calculated.summary());

        Result scenario = engine.query(formulaGraph(), query(Intent.SCENARIO,
                "{\"target\":{\"entityId\":\"a3\"},\"interventions\":"
                        + "[{\"target\":{\"entityId\":\"a1\"},\"operation\":\"ADD\",\"value\":5}]}"));
        assertEquals(Status.OK, scenario.status(), scenario.summary());
        ScenarioResult values = assertInstanceOf(ScenarioResult.class, scenario.data().get("scenario"));
        assertEquals(30.0, values.baselineValue(), 1.0e-9);
        assertEquals(35.0, values.scenarioValue(), 1.0e-9);

        Result solved = engine.query(formulaGraph(), query(Intent.SOLVE_TARGET,
                "{\"target\":{\"entityId\":\"a3\"},\"goal\":{\"control\":{\"entityId\":\"a1\"},"
                        + "\"targetValue\":40,\"minimum\":0,\"maximum\":100}}"));
        assertEquals(Status.OK, solved.status(), solved.summary());
        GoalSeekResult goal = assertInstanceOf(GoalSeekResult.class, solved.data().get("goalSeek"));
        assertEquals(20.0, goal.controlValue(), 1.0e-5);
    }

    @Test
    void theEngineRefusesInterventionsOnCalculateAndGoalsOnScenario() {
        GraphQueryEngine engine = new GraphQueryEngine();
        QuantitativeQuery.Intervention addFive = new QuantitativeQuery.Intervention(
                QuantitativeQuery.MeasureSelector.entity("a1"), QuantitativeQuery.Operation.ADD, 5.0);

        Result calculate = engine.query(formulaGraph(), Query.calculate(new QuantitativeQuery(
                QuantitativeQuery.Mode.CALCULATE, QuantitativeQuery.MeasureSelector.entity("a3"),
                List.of(addFive), Map.of(), null, null, 10, null)));
        assertEquals(Status.INVALID, calculate.status());
        assertTrue(calculate.summary().contains("takes no quantitative.interventions"),
                calculate.summary());

        Result scenario = engine.query(formulaGraph(), Query.scenario(new QuantitativeQuery(
                QuantitativeQuery.Mode.SCENARIO, QuantitativeQuery.MeasureSelector.entity("a3"),
                List.of(addFive), Map.of(), null, null, 10,
                new QuantitativeQuery.Goal(QuantitativeQuery.MeasureSelector.entity("a1"),
                        40.0, 0.0, 100.0, 0.0, 0))));
        assertEquals(Status.INVALID, scenario.status());
        assertTrue(scenario.summary().contains("goal only applies to SOLVE_TARGET"), scenario.summary());
    }

    private static Query query(Intent intent, String spec) {
        return new Query(intent, null, null, null, List.of(), null, null, null, null, null,
                QuantitativeRequestParser.parse(intent, spec, null, null));
    }

    private static MutableReasoningGraph formulaGraph() {
        return new MutableReasoningGraph()
                .addEntity(cell("a1", "Sheet1!A1", "Input A", 10.0))
                .addEntity(cell("a2", "Sheet1!A2", "Input B", 20.0))
                .addEntity(GraphEntity.builder("a3")
                        .type("FORMULA_CELL")
                        .label("Total")
                        .attribute("cell_reference", "Sheet1!A3")
                        .attribute("formula", "SUM(Sheet1!A1:Sheet1!A2)")
                        .attribute("displayValue", "30")
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder("d1", "a3", "a1").type("DEPENDS_ON").build())
                .addRelation(GraphRelation.builder("d2", "a3", "a2").type("DEPENDS_ON").build());
    }

    private static GraphEntity cell(String id, String reference, String label, double value) {
        return GraphEntity.builder(id)
                .type("CELL").label(label)
                .attribute("cell_reference", reference)
                .attribute("value", value)
                .build();
    }
}
