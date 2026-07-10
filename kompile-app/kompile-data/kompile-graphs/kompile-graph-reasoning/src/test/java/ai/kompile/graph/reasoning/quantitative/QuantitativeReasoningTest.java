/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.GapCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QuantitativeReasoningTest {

    private final GraphExecutableModelRetriever retriever = new GraphExecutableModelRetriever();
    private final QuantitativeScenarioEngine scenarioEngine = new QuantitativeScenarioEngine();

    @Test
    void retrievesDependencyClosureAndPropagatesScenarioThroughIntermediateRules() {
        MutableReasoningGraph graph = marginGraph(true);
        QuantitativeQuery query = QuantitativeQuery.scenario(
                new QuantitativeQuery.MeasureSelector(
                        null, "Gross Margin", null, "ratio", Map.of("region", "APAC")),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.entity("sales"),
                        QuantitativeQuery.Operation.SCALE, 0.25)),
                Map.of("region", "APAC"));

        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals(List.of("rule:gross-profit", "rule:gross-margin"),
                retrieval.plan().rules().stream().map(QuantitativeRule::sourceEntityId).toList());
        assertTrue(retrieval.candidates().stream().anyMatch(candidate ->
                candidate.rule().outputEntityId().equals("gross-margin-eu")
                        && !candidate.compatible()));
        assertEquals(ScenarioResult.Status.COMPLETED, result.status());
        assertEquals(0.40, result.baselineValue(), 1.0e-9);
        assertEquals(0.52, result.scenarioValue(), 1.0e-9);
        assertEquals(125.0, result.scenarioValues().get("sales"), 1.0e-9);
        assertEquals(65.0, result.scenarioValues().get("gross-profit"), 1.0e-9);
        assertNotNull(result.trace());
        assertTrue(result.trace().steps().stream()
                .anyMatch(step -> step.kind() == ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind.CALCULATION));
    }

    @Test
    void discoversAndExecutesCrawlerStyleFormulaCellWithRange() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
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
                .addRelation(GraphRelation.builder("d1", "a3", "a1")
                        .type("DEPENDS_ON").build())
                .addRelation(GraphRelation.builder("d2", "a3", "a2")
                        .type("DEPENDS_ON").build());

        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.entity("a3"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.entity("a1"),
                        QuantitativeQuery.Operation.ADD, 5.0)),
                Map.of());

        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals(1, retrieval.plan().rules().size());
        assertEquals("excel-formula", retrieval.plan().rules().get(0).engineId());
        assertEquals(30.0, result.baselineValue(), 1.0e-9);
        assertEquals(35.0, result.scenarioValue(), 1.0e-9);
    }

    @Test
    void reportsMissingInputInsteadOfUsingAnInventedValue() {
        MutableReasoningGraph graph = marginGraph(false);
        ModelRetrieval retrieval = retriever.retrieve(
                graph, QuantitativeQuery.scenario(
                        new QuantitativeQuery.MeasureSelector(
                                null, "Gross Margin", null, "ratio", Map.of("region", "APAC")),
                        List.of(), Map.of("region", "APAC")));

        assertEquals(ModelRetrieval.Status.PARTIAL, retrieval.status());
        assertTrue(retrieval.gaps().stream()
                .anyMatch(gap -> gap.code() == GapCode.MISSING_INPUT_VALUE
                        && "cogs".equals(gap.entityId())));

        ScenarioResult result = scenarioEngine.execute(graph, retrieval);
        assertEquals(ScenarioResult.Status.FAILED, result.status());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("cogs")));
    }

    @Test
    void boundedGoalSeekFindsControlValueFromTheSameGraphModel() {
        MutableReasoningGraph graph = marginGraph(true);
        QuantitativeQuery query = QuantitativeQuery.solveTarget(
                new QuantitativeQuery.MeasureSelector(
                        null, "Gross Margin", null, "ratio", Map.of("region", "APAC")),
                List.of(),
                Map.of("region", "APAC"),
                new QuantitativeQuery.Goal(
                        QuantitativeQuery.MeasureSelector.entity("sales"),
                        0.50, 61.0, 300.0, 1.0e-8, 200));

        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        QuantitativeScenarioEngine.GoalSeekResult result =
                scenarioEngine.solveTarget(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals(QuantitativeScenarioEngine.GoalSeekResult.Status.SOLVED, result.status());
        assertEquals(120.0, result.controlValue(), 1.0e-5);
        assertEquals(0.50, result.achievedValue(), 1.0e-8);
        assertNotNull(result.trace());
    }

    @Test
    void exposesScenarioThroughTheUnifiedGraphQueryFacade() {
        MutableReasoningGraph graph = marginGraph(true);
        QuantitativeQuery quantitative = QuantitativeQuery.scenario(
                new QuantitativeQuery.MeasureSelector(
                        null, "Gross Margin", null, "ratio", Map.of("region", "APAC")),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.entity("sales"),
                        QuantitativeQuery.Operation.SCALE, 0.25)),
                Map.of("region", "APAC"));

        GraphQueryEngine.Result result = new GraphQueryEngine().query(
                graph, GraphQueryEngine.Query.scenario(quantitative));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertInstanceOf(ScenarioResult.class, result.data().get("scenario"));
        ScenarioResult scenario = (ScenarioResult) result.data().get("scenario");
        assertEquals(0.52, scenario.scenarioValue(), 1.0e-9);
        assertEquals(
                ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind.CALCULATION,
                result.trace().conclusion().kind());
        assertTrue(result.trace().steps().stream()
                .anyMatch(step -> step.operation().equals("rank_executable_model")));
    }

    @Test
    void normalizesMonthNamesWhenRankingTimeScopedOutputs() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        addObservedRule(graph, "rule:jun", "output:jun",
                "Gross Margin / Jun 2026", "input:jun");
        addObservedRule(graph, "rule:jul", "output:jul",
                "Gross Margin / Jul 2026", "input:jul");

        ModelRetrieval retrieval = retriever.retrieve(
                graph, QuantitativeQuery.calculate("Gross Margin July"));

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals("output:jul", retrieval.plan().targetEntityId());
    }

    @Test
    void refusesNearTiedTargetResolution() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        addObservedRule(graph, "rule:a", "output:a", "Gross Margin", "input:a");
        addObservedRule(graph, "rule:b", "output:b", "Gross Margin", "input:b");

        ModelRetrieval retrieval = retriever.retrieve(
                graph, QuantitativeQuery.calculate("Gross Margin"));

        assertEquals(ModelRetrieval.Status.AMBIGUOUS, retrieval.status());
        assertNull(retrieval.plan());
        assertTrue(retrieval.gaps().stream()
                .anyMatch(gap -> gap.code() == GapCode.AMBIGUOUS_TARGET));
    }

    @Test
    void punctuationOnlyLabelsDoNotMatchEverySelectorOrBecomeNumbers() {
        GraphEntity punctuation = GraphEntity.builder("punctuation")
                .type("CELL").label("—").attribute("displayValue", "—").build();
        GraphEntity numberedText = GraphEntity.builder("instruction-number")
                .type("CELL").label("6.")
                .attribute("cellType", "STRING").attribute("displayValue", "6.").build();

        assertEquals(0.0, QuantitativeGraphSupport.selectorScore(
                punctuation, QuantitativeQuery.MeasureSelector.text("Gross Margin July")), 0.0);
        assertEquals(0.0, QuantitativeGraphSupport.selectorScore(numberedText,
                QuantitativeQuery.MeasureSelector.text("Restful Bath Salt 16oz July")), 0.0);
        assertFalse(QuantitativeGraphSupport.quantitativeCandidate(numberedText));
        assertTrue(QuantitativeGraphSupport.parseNumber("Restful Bath Salt 16oz").isEmpty());
        assertEquals(1234.5,
                QuantitativeGraphSupport.parseNumber("$1,234.50").orElseThrow(), 1.0e-9);
        assertEquals(-0.25,
                QuantitativeGraphSupport.parseNumber("(25%)").orElseThrow(), 1.0e-9);
    }

    @Test
    void interventionResolutionExcludesTextLabels() {
        MutableReasoningGraph graph = marginGraph(true)
                .addEntity(GraphEntity.builder("sales-label")
                        .type("CELL").label("APAC Sales")
                        .attribute("displayValue", "APAC Sales")
                        .build());
        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.entity("gross-margin"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text("APAC Sales"),
                        QuantitativeQuery.Operation.SCALE, 0.10)),
                Map.of());

        ModelRetrieval retrieval = retriever.retrieve(graph, query);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals("sales", retrieval.plan().interventions().get(0).entityId());
    }

    @Test
    void weakPartialTargetMatchAbstains() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        addObservedRule(graph, "rule:revenue", "output:revenue",
                "Net Revenue", "input:revenue");

        ModelRetrieval retrieval = retriever.retrieve(
                graph, QuantitativeQuery.calculate("Net Income"));

        assertEquals(ModelRetrieval.Status.NOT_FOUND, retrieval.status());
        assertTrue(retrieval.gaps().stream()
                .anyMatch(gap -> gap.code() == GapCode.MISSING_TARGET));
    }

    @Test
    void weakInterventionMatchIsUnresolvedInsteadOfArbitrarilySelected() {
        MutableReasoningGraph graph = marginGraph(true)
                .addEntity(GraphEntity.builder("july-sales")
                        .type("MEASURE_OBSERVATION").label("Sales July")
                        .attribute("value", 10.0).build())
                .addEntity(GraphEntity.builder("july-cost")
                        .type("MEASURE_OBSERVATION").label("Cost July")
                        .attribute("value", 8.0).build());
        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.entity("gross-margin"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text("Restful Eye Mask July"),
                        QuantitativeQuery.Operation.SCALE, -0.31)),
                Map.of());

        ModelRetrieval retrieval = retriever.retrieve(graph, query);

        assertTrue(retrieval.gaps().stream()
                .anyMatch(gap -> gap.code() == GapCode.UNRESOLVED_INTERVENTION));
        assertTrue(retrieval.plan().interventions().isEmpty());
    }

    @Test
    void requestedDimensionsAndUnitsMustBePresentOnTheModelOutput() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        addObservedRule(graph, "rule:margin", "output:margin",
                "Gross Margin", "input:sales");
        QuantitativeQuery query = new QuantitativeQuery(
                QuantitativeQuery.Mode.CALCULATE,
                new QuantitativeQuery.MeasureSelector(
                        null, "Gross Margin", null, "ratio", Map.of("region", "US")),
                List.of(), Map.of(), null, null, 10, null);

        ModelRetrieval retrieval = retriever.retrieve(graph, query);

        assertEquals(ModelRetrieval.Status.NOT_FOUND, retrieval.status());
        assertFalse(retrieval.candidates().isEmpty());
        assertFalse(retrieval.candidates().get(0).compatible());
        assertTrue(retrieval.candidates().get(0).rejectionReasons().stream()
                .anyMatch(reason -> reason.contains("dimension region is missing")));
        assertTrue(retrieval.candidates().get(0).rejectionReasons().stream()
                .anyMatch(reason -> reason.contains("unit is missing")));
    }

    @Test
    void requestedInterventionDimensionCannotResolveToAnUnscopedValue() {
        MutableReasoningGraph graph = marginGraph(true)
                .addEntity(GraphEntity.builder("unscoped-us-sales")
                        .type("MEASURE_OBSERVATION").label("US Sales July")
                        .attribute("value", 100.0).build());
        QuantitativeQuery.MeasureSelector scopedControl = new QuantitativeQuery.MeasureSelector(
                null, "US Sales July", null, null, Map.of("region", "US"));
        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.entity("gross-margin"),
                List.of(new QuantitativeQuery.Intervention(
                        scopedControl, QuantitativeQuery.Operation.SCALE, -0.10)),
                Map.of());

        ModelRetrieval retrieval = retriever.retrieve(graph, query);

        assertTrue(retrieval.gaps().stream()
                .anyMatch(gap -> gap.code() == GapCode.UNRESOLVED_INTERVENTION));
        assertTrue(retrieval.plan().interventions().isEmpty());
    }

    @Test
    void expandsInterventionAcrossRowInstancesOfOneMeasureColumn() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        gridCell(graph, "rev-dtc", "Grid!P4", 4, "Jul rev", 100.0,
                Map.of("product", "Alpha Widget", "channel", "DTC"));
        gridCell(graph, "rev-amazon", "Grid!P5", 5, "Jul rev", 200.0,
                Map.of("product", "Alpha Widget", "channel", "Amazon"));
        gridCell(graph, "rev-wholesale", "Grid!P6", 6, "Jul rev", 300.0,
                Map.of("product", "Alpha Widget", "channel", "Wholesale"));
        totalFormula(graph, "rev-total", "Grid!P8", "Total revenue", "P4 + P5 + P6",
                List.of("rev-dtc", "rev-amazon", "rev-wholesale"));

        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("Total revenue"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text("Alpha Widget Jul revenue"),
                        QuantitativeQuery.Operation.SCALE, -0.10)),
                Map.of());
        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals(3, retrieval.plan().interventions().size());
        assertEquals(600.0, result.baselineValue(), 1.0e-9);
        assertEquals(540.0, result.scenarioValue(), 1.0e-9);
    }

    @Test
    void expandsInterventionAcrossPeriodInstancesOfOneRow() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        gridCell(graph, "apac-jun", "Entity!B7", 7, "Jun 2026", 100.0,
                Map.of("row", "APAC entity", "entity", "APAC entity"));
        gridCell(graph, "apac-jul", "Entity!C7", 7, "Jul 2026", 110.0,
                Map.of("row", "APAC entity", "entity", "APAC entity"));
        gridCell(graph, "apac-aug", "Entity!D7", 7, "Aug 2026", 120.0,
                Map.of("row", "APAC entity", "entity", "APAC entity"));
        totalFormula(graph, "apac-total", "Entity!E7", "APAC 3-mo total", "B7 + C7 + D7",
                List.of("apac-jun", "apac-jul", "apac-aug"));

        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("APAC 3-mo total"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text("APAC entity"),
                        QuantitativeQuery.Operation.SCALE, 0.25)),
                Map.of());
        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals(3, retrieval.plan().interventions().size());
        assertEquals(330.0, result.baselineValue(), 1.0e-9);
        assertEquals(412.5, result.scenarioValue(), 1.0e-9);
    }

    @Test
    void interventionReachabilitySelectsAmongLexicallyTiedTargets() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        gridCell(graph, "a-x1", "SheetA!B5", 5, "Jun 2026", 40.0,
                Map.of("row", "APAC region"));
        gridCell(graph, "a-x2", "SheetA!B6", 6, "Jun 2026", 60.0,
                Map.of("row", "US region"));
        totalFormula(graph, "total-a", "SheetA!B8", "Total net revenue", "B5 + B6",
                List.of("a-x1", "a-x2"));
        gridCell(graph, "b-y1", "SheetB!B5", 5, "Jun 2026", 10.0,
                Map.of("row", "Channel one"));
        gridCell(graph, "b-y2", "SheetB!B6", 6, "Jun 2026", 20.0,
                Map.of("row", "Channel two"));
        totalFormula(graph, "total-b", "SheetB!B8", "Total net revenue", "B5 + B6",
                List.of("b-y1", "b-y2"));

        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("Total net revenue"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.entity("a-x1"),
                        QuantitativeQuery.Operation.SCALE, 0.25)),
                Map.of());
        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals("total-a", retrieval.plan().targetEntityId());
        assertEquals(100.0, result.baselineValue(), 1.0e-9);
        assertEquals(110.0, result.scenarioValue(), 1.0e-9);
    }

    @Test
    void synthesizesRatioRollupFromLearnedTemplateAndExecutesScenario() {
        MutableReasoningGraph graph = ratioTemplateGraph();
        gridCell(graph, "grid-m4", "Grid!M4", 4, "Jul GM%", 0.60,
                Map.of("product", "Alpha Widget"));
        gridCell(graph, "grid-p4", "Grid!P4", 4, "Jul rev", 100.0,
                Map.of("product", "Alpha Widget"));
        gridCell(graph, "grid-m5", "Grid!M5", 5, "Jul GM%", 0.40,
                Map.of("product", "Beta Widget"));
        gridCell(graph, "grid-p5", "Grid!P5", 5, "Jul rev", 300.0,
                Map.of("product", "Beta Widget"));

        List<QuantitativeMeasureSynthesizer.SynthesizedMeasure> synthesized =
                new QuantitativeMeasureSynthesizer().synthesize(graph);
        assertFalse(synthesized.isEmpty());
        QuantitativeMeasureSynthesizer.SynthesizedMeasure rollup = synthesized.stream()
                .filter(measure -> measure.sheet().equals("Grid")
                        && measure.templateOutputLabel().equals("Gross margin %"))
                .findFirst().orElseThrow();
        assertEquals(2, rollup.pairCount());
        // The implied numerator aggregate materializes alongside the ratio rollup.
        QuantitativeMeasureSynthesizer.SynthesizedMeasure numerator = synthesized.stream()
                .filter(measure -> measure.sheet().equals("Grid")
                        && measure.templateOutputLabel().equals("Gross profit"))
                .findFirst().orElseThrow();
        assertEquals(2, numerator.pairCount());

        QuantitativeQuery query = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("Gross margin % Jul"),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text("Alpha Widget Jul revenue"),
                        QuantitativeQuery.Operation.SCALE, 1.0)),
                Map.of());
        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(rollup.entityId(), retrieval.plan().targetEntityId());
        assertEquals(0.45, result.baselineValue(), 1.0e-9);
        assertEquals(0.48, result.scenarioValue(), 1.0e-9);

        ModelRetrieval numeratorRetrieval = retriever.retrieve(
                graph, QuantitativeQuery.calculate("Gross profit Jul"));
        assertEquals(ModelRetrieval.Status.READY, numeratorRetrieval.status(),
                () -> "gaps=" + numeratorRetrieval.gaps()
                        + " resolutions=" + numeratorRetrieval.resolutions()
                        + " candidates=" + numeratorRetrieval.candidates().stream()
                                .map(candidate -> candidate.rule().outputEntityId()
                                        + ":" + candidate.scores().total()).toList());
        ScenarioResult numeratorResult = scenarioEngine.execute(graph, numeratorRetrieval);
        assertEquals(numerator.entityId(), numeratorRetrieval.plan().targetEntityId());
        assertEquals(180.0, numeratorResult.baselineValue(), 1.0e-9);
    }

    @Test
    void learnsDimensionAliasesFromTaxonomyTables() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity(GraphEntity.builder("taxonomy-dtc")
                        .type("CELL").label("DTC, Direct-to-Consumer, Direct, ecom, EC")
                        .attribute("cellType", "STRING")
                        .attribute("rowLabel", "DTC")
                        .attribute("columnLabel", "Acceptable regional names")
                        .attribute("displayValue", "DTC, Direct-to-Consumer, Direct, ecom, EC")
                        .build())
                .addEntity(GraphEntity.builder("taxonomy-wholesale")
                        .type("CELL").label("Wholesale synonyms")
                        .attribute("cellType", "STRING")
                        .attribute("rowLabel", "Wholesale")
                        .attribute("columnLabel", "Acceptable regional names")
                        .attribute("displayValue",
                                "Wholesale, Wholesale - {customer}, Whlsl, Retail, Distributor")
                        .build());

        DimensionAliasCatalog aliases = DimensionAliasCatalog.learn(graph);

        assertEquals(2, aliases.groupCount());
        assertTrue(aliases.sameGroup("Direct-to-Consumer", "DTC"));
        assertTrue(aliases.sameGroup("ecom", "dtc"));
        assertTrue(aliases.sameGroup("Wholesale - Target", "Wholesale"));
        assertFalse(aliases.sameGroup("Retail", "DTC"));
    }

    @Test
    void goalSeekSolvesEveryControlCandidateAndRanksAlternativesByLeastChange() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        gridCell(graph, "units-alpha", "S!A4", 4, "Jul units", 10.0,
                Map.of("product", "Alpha Widget"));
        gridCell(graph, "units-beta", "S!A5", 5, "Jul units", 20.0,
                Map.of("product", "Beta Widget"));
        totalFormula(graph, "units-total", "S!A8", "Total units", "A4 + A5",
                List.of("units-alpha", "units-beta"));

        QuantitativeQuery query = QuantitativeQuery.solveTarget(
                QuantitativeQuery.MeasureSelector.text("Total units"),
                List.of(),
                Map.of(),
                new QuantitativeQuery.Goal(
                        QuantitativeQuery.MeasureSelector.text("Jul units"),
                        40.0, 0.0, 1000.0, 1.0e-9, 200));
        ModelRetrieval retrieval = retriever.retrieve(graph, query);
        QuantitativeScenarioEngine.GoalSeekResult result =
                scenarioEngine.solveTarget(graph, retrieval);

        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertEquals(2, retrieval.plan().goalControlCandidateIds().size());
        assertEquals(QuantitativeScenarioEngine.GoalSeekResult.Status.SOLVED, result.status());
        assertEquals(2, result.alternatives().size());
        // Raising Beta from 20 to 30 is a 50% change; raising Alpha from 10 to 20 is 100%.
        assertEquals("units-beta", result.controlEntityId());
        assertEquals(30.0, result.controlValue(), 1.0e-6);
        assertEquals("units-beta", result.alternatives().get(0).controlEntityId());
        assertEquals("units-alpha", result.alternatives().get(1).controlEntityId());
        assertEquals(20.0, result.alternatives().get(1).controlValue(), 1.0e-6);
        assertEquals(10.0, result.alternatives().get(1).baselineControlValue(), 1.0e-9);
    }

    @Test
    void aliasResolverTranslatesColloquialVocabularyIntoGraphMembers() {
        MutableReasoningGraph graph = regionVocabularyGraph();
        java.util.List<DimensionAliasResolver.Question> asked = new java.util.ArrayList<>();
        DimensionAliasResolver resolver = question -> {
            asked.add(question);
            return java.util.Optional.of(new DimensionAliasResolver.Resolution(
                    "AMER", 0.9, "scripted-small-model"));
        };
        GraphExecutableModelRetriever resolving = retriever.withAliasResolver(resolver);

        QuantitativeQuery query = new QuantitativeQuery(
                QuantitativeQuery.Mode.CALCULATE,
                new QuantitativeQuery.MeasureSelector(
                        null, "Total revenue", null, null, Map.of("region", "US")),
                List.of(), Map.of(), null, null, 10, null);
        ModelRetrieval retrieval = resolving.retrieve(graph, query);
        ScenarioResult result = scenarioEngine.execute(graph, retrieval);

        assertEquals(1, asked.size());
        assertEquals("region", asked.get(0).dimensionKey());
        assertEquals("US", asked.get(0).requestedValue());
        assertTrue(asked.get(0).members().contains("AMER"));
        assertTrue(asked.get(0).members().contains("EMEA"));
        assertEquals(ModelRetrieval.Status.READY, retrieval.status());
        assertTrue(retrieval.resolutions().get("alias:region").startsWith("US -> AMER"));
        assertTrue(retrieval.trace().steps().stream().anyMatch(step ->
                step.operation().equals("alias_resolution")));
        assertEquals(300.0, result.baselineValue(), 1.0e-9);
    }

    @Test
    void aliasResolverAnswersOutsideTheOfferedVocabularyAreDiscarded() {
        MutableReasoningGraph graph = regionVocabularyGraph();
        DimensionAliasResolver hallucinating = question -> java.util.Optional.of(
                new DimensionAliasResolver.Resolution("US-LAND", 0.99, "scripted"));

        ModelRetrieval retrieval = retriever.withAliasResolver(hallucinating).retrieve(
                graph, new QuantitativeQuery(
                        QuantitativeQuery.Mode.CALCULATE,
                        new QuantitativeQuery.MeasureSelector(
                                null, "Total revenue", null, null, Map.of("region", "US")),
                        List.of(), Map.of(), null, null, 10, null));

        assertEquals(ModelRetrieval.Status.NOT_FOUND, retrieval.status());
        assertNull(retrieval.resolutions().get("alias:region"));
    }

    @Test
    void withoutAResolverUnsupportedVocabularyStillAbstains() {
        MutableReasoningGraph graph = regionVocabularyGraph();

        ModelRetrieval retrieval = retriever.retrieve(
                graph, new QuantitativeQuery(
                        QuantitativeQuery.Mode.CALCULATE,
                        new QuantitativeQuery.MeasureSelector(
                                null, "Total revenue", null, null, Map.of("region", "US")),
                        List.of(), Map.of(), null, null, 10, null));

        assertEquals(ModelRetrieval.Status.NOT_FOUND, retrieval.status());
    }

    @Test
    void typedTableMembersProvideVocabularyAndCodeNameAliases() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        gridCell(graph, "grid-p4", "Detail!P4", 4, "Jul rev", 100.0,
                Map.of("sku", "GRO-203", "product", "Grove Reed Diffuser"));
        graph.addEntity(GraphEntity.builder("member:sku:gro-203")
                .type("SKU").label("Grove Reed Diffuser")
                .attribute("tableMember", true)
                .attribute("memberType", "SKU")
                .attribute("memberKey", "GRO-203")
                .attribute("aliases", List.of("GRO-203", "Grove Reed Diffuser"))
                .build());

        List<String> skuMembers = DimensionVocabulary.members(graph, "sku");
        assertTrue(skuMembers.contains("GRO-203"));
        assertTrue(skuMembers.contains("Grove Reed Diffuser"));

        DimensionAliasCatalog aliases = DimensionAliasCatalog.learn(graph);
        assertTrue(aliases.sameGroup("GRO-203", "Grove Reed Diffuser"));

        // A dimension scoped by CODE matches a cell whose dimension carries the display NAME.
        GraphEntity cell = graph.entity("grid-p4").orElseThrow();
        assertEquals(QuantitativeGraphSupport.DimensionMatch.MATCH,
                QuantitativeGraphSupport.dimensionCompatibility(
                        cell, QuantitativeGraphSupport.dimensions(cell),
                        "product", "GRO-203", aliases));
    }

    @Test
    void owlSchemaSubclassAxiomsPropagateToTableMemberVocabulary() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("member:sku:gro-203")
                .type("SKU").label("Grove Reed Diffuser")
                .attribute("tableMember", true)
                .attribute("memberType", "SKU")
                .attribute("memberKey", "GRO-203")
                .build());

        // Without a declared TBox, member types still become OWL classes.
        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology tableOnly =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .ontologyFromTableMembers(graph, null);
        assertTrue(tableOnly.classes().keySet().stream().anyMatch(iri -> iri.endsWith("#SKU")));

        // A declared schema axiom SKU subClassOf Product composes with the table classes.
        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology declared =
                ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology.of("urn:kompile:schema")
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of("urn:kompile:schema#Product").build())
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of(ai.kompile.graph.reasoning.mebn.type.owl
                                        .TableMemberOntologyBridge.TABLE_CLASS_IRI_PREFIX + "SKU")
                                .subClassOf("urn:kompile:schema#Product").build())
                        .build();
        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology merged =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .ontologyFromTableMembers(graph, declared);
        ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult result =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .materializeInferredTypes(graph, merged);

        assertTrue(result.isConsistent());
        GraphEntity member = graph.entity("member:sku:gro-203").orElseThrow();
        assertTrue(member.typeMemberships().stream().anyMatch("Product"::equalsIgnoreCase),
                () -> "memberships=" + member.typeMemberships());

        // The vocabulary for "product" now includes SKU members through the schema closure.
        List<String> productMembers = DimensionVocabulary.members(graph, "product");
        assertTrue(productMembers.contains("GRO-203"));
        assertTrue(productMembers.contains("Grove Reed Diffuser"));
    }

    @Test
    void graphCarriedTurtleTboxDisjointnessAndReferencePropertiesIntegrate() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("member:sku:rst-001")
                .type("SKU").label("Restful Bath Salt 16oz")
                .attribute("tableMember", true).attribute("memberType", "SKU")
                .attribute("memberKey", "RST-001").build());
        graph.addEntity(GraphEntity.builder("member:brand:restful")
                .type("BRAND").label("Restful")
                .attribute("tableMember", true).attribute("memberType", "BRAND")
                .attribute("memberKey", "Restful").build());
        graph.addRelation(GraphRelation.builder(
                        "ref-1", "member:sku:rst-001", "member:brand:restful")
                .type("HAS_BRAND").attribute("memberReference", true).build());
        // A miscoded row carrying two member types at once.
        graph.addEntity(GraphEntity.builder("member:bad")
                .type("SKU").label("Miscoded row")
                .attribute("tableMember", true).attribute("memberType", "SKU")
                .attribute("memberKey", "BAD-1")
                .attribute("entity_type", "CHANNEL").build());

        // The TBox travels WITH the graph as Turtle: SKU is a Product, and SKU/CHANNEL disjoint.
        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology declared =
                ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology.of("urn:kompile:schema")
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of("urn:kompile:schema#Product").build())
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of("urn:kompile:schema#CHANNEL").build())
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of(ai.kompile.graph.reasoning.mebn.type.owl
                                        .TableMemberOntologyBridge.TABLE_CLASS_IRI_PREFIX + "SKU")
                                .subClassOf("urn:kompile:schema#Product")
                                .disjointWith("urn:kompile:schema#CHANNEL").build())
                        .build();
        String turtle = new ai.kompile.graph.reasoning.mebn.type.owl.OwlTurtleWriter()
                .write(declared);
        graph.addEntity(GraphEntity.builder("ontology:declared")
                .type("ONTOLOGY").label("Declared schema")
                .attribute("ontologyTurtle", turtle).build());

        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology merged =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .ontologyFromGraph(graph);
        // The member-reference relation is declared as an object property with domain/range.
        assertTrue(merged.objectProperties().values().stream().anyMatch(property ->
                property.localName().equalsIgnoreCase("HAS_BRAND")
                        && property.domainClassIri() != null
                        && property.domainClassIri().endsWith("#SKU")
                        && property.rangeClassIri() != null
                        && property.rangeClassIri().endsWith("#BRAND")));

        ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult result =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .materializeInferredTypes(graph, merged);

        // Subclass closure flows from the Turtle-carried axiom...
        assertTrue(graph.entity("member:sku:rst-001").orElseThrow().typeMemberships().stream()
                .anyMatch("Product"::equalsIgnoreCase));
        // ...and the miscoded SKU+CHANNEL row is a detected inconsistency, not silent data.
        assertFalse(result.isConsistent());
    }

    /**
     * A workbook whose cells carry AMER context plus a region member table (the vocabulary a
     * resolver chooses from), like a consolidation template's tie-check sheet.
     */
    private static MutableReasoningGraph regionVocabularyGraph() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        gridCellInWorkbook(graph, "rev-1", "Detail!P4", 4, "Jul rev", 100.0,
                Map.of("product", "Alpha Widget"), "amer_forecast.xlsx");
        gridCellInWorkbook(graph, "rev-2", "Detail!P5", 5, "Jul rev", 200.0,
                Map.of("product", "Beta Widget"), "amer_forecast.xlsx");
        graph.addEntity(GraphEntity.builder("rev-total")
                        .type("FORMULA_CELL").label("Total revenue")
                        .attribute("cell_reference", "Detail!P8")
                        .attribute("sheetName", "Detail")
                        .attribute("workbook", "amer_forecast.xlsx")
                        .attribute("rowLabel", "Total revenue")
                        .attribute("formula", "P4 + P5")
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder("rev-total:in0", "rev-total", "rev-1")
                        .type("DEPENDS_ON").build())
                .addRelation(GraphRelation.builder("rev-total:in1", "rev-total", "rev-2")
                        .type("DEPENDS_ON").build());
        for (String member : List.of("AMER", "EMEA", "APAC")) {
            graph.addEntity(GraphEntity.builder("vocab-" + member.toLowerCase(java.util.Locale.ROOT))
                    .type("CELL").label(member)
                    .attribute("cellType", "STRING")
                    .attribute("columnLabel", "Region")
                    .attribute("displayValue", member)
                    .build());
        }
        return graph;
    }

    private static MutableReasoningGraph ratioTemplateGraph() {
        // Ids are neutral coordinates, like production cell ids: semantic tokens live in labels.
        MutableReasoningGraph graph = new MutableReasoningGraph();
        plConstant(graph, "pl-b17", "PL!B17", "Gross profit", 60.0);
        plConstant(graph, "pl-b9", "PL!B9", "Net revenue", 100.0);
        plRatioFormula(graph, "pl-b18", "PL!B18", "B17/B9", 0.60, "pl-b17", "pl-b9");
        plConstant(graph, "pl-c17", "PL!C17", "Gross profit", 66.0);
        plConstant(graph, "pl-c9", "PL!C9", "Net revenue", 110.0);
        plRatioFormula(graph, "pl-c18", "PL!C18", "C17/C9", 0.60, "pl-c17", "pl-c9");
        return graph;
    }

    private static void plConstant(
            MutableReasoningGraph graph, String id, String reference, String rowLabel,
            double value) {
        graph.addEntity(GraphEntity.builder(id)
                .type("CELL").label(rowLabel)
                .attribute("cell_reference", reference)
                .attribute("rowLabel", rowLabel)
                .attribute("sheetName", "PL")
                .attribute("value", value)
                .build());
    }

    private static void plRatioFormula(
            MutableReasoningGraph graph, String id, String reference, String formula,
            double value, String numeratorId, String denominatorId) {
        graph.addEntity(GraphEntity.builder(id)
                        .type("FORMULA_CELL").label("Gross margin %")
                        .attribute("cell_reference", reference)
                        .attribute("rowLabel", "Gross margin %")
                        .attribute("sheetName", "PL")
                        .attribute("formula", formula)
                        .attribute("displayValue", value)
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder(id + ":num", id, numeratorId)
                        .type("DEPENDS_ON").build())
                .addRelation(GraphRelation.builder(id + ":den", id, denominatorId)
                        .type("DEPENDS_ON").build());
    }

    private static void gridCell(
            MutableReasoningGraph graph, String id, String reference, int row,
            String columnLabel, double value, Map<String, String> rowDimensions) {
        gridCellInWorkbook(graph, id, reference, row, columnLabel, value, rowDimensions,
                "test-workbook.xlsx");
    }

    private static void gridCellInWorkbook(
            MutableReasoningGraph graph, String id, String reference, int row,
            String columnLabel, double value, Map<String, String> rowDimensions,
            String workbook) {
        java.util.TreeMap<String, String> orderedRowDimensions =
                new java.util.TreeMap<>(rowDimensions);
        java.util.LinkedHashMap<String, String> dimensions = new java.util.LinkedHashMap<>();
        dimensions.put("sheet", reference.substring(0, reference.indexOf('!')));
        dimensions.put("workbook", workbook);
        dimensions.putAll(orderedRowDimensions);
        String rowLabel = String.join(" / ",
                new java.util.LinkedHashSet<>(orderedRowDimensions.values()));
        graph.addEntity(GraphEntity.builder(id)
                .type("CELL")
                .label(rowLabel + " / " + columnLabel)
                .attribute("cell_reference", reference)
                .attribute("sheetName", reference.substring(0, reference.indexOf('!')))
                .attribute("row", row)
                .attribute("columnLabel", columnLabel)
                .attribute("rowLabel", rowLabel)
                .attribute("semanticLabel", rowLabel + " / " + columnLabel)
                .attribute("value", value)
                .attribute("dimensions", dimensions)
                .build());
    }

    private static void totalFormula(
            MutableReasoningGraph graph, String id, String reference, String label,
            String formula, List<String> inputIds) {
        graph.addEntity(GraphEntity.builder(id)
                .type("FORMULA_CELL").label(label)
                .attribute("cell_reference", reference)
                .attribute("sheetName", reference.substring(0, reference.indexOf('!')))
                .attribute("rowLabel", label)
                .attribute("formula", formula)
                .attribute("validated", true)
                .build());
        for (int i = 0; i < inputIds.size(); i++) {
            graph.addRelation(GraphRelation.builder(id + ":in" + i, id, inputIds.get(i))
                    .type("DEPENDS_ON").build());
        }
    }

    private static MutableReasoningGraph marginGraph(boolean includeCogsValue) {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity(GraphEntity.builder("sales")
                        .type("MEASURE_OBSERVATION").label("APAC Sales")
                        .attribute("value", 100.0)
                        .attribute("unit", "USD")
                        .attribute("dimension.region", "APAC")
                        .build())
                .addEntity(GraphEntity.builder("cogs")
                        .type("MEASURE_OBSERVATION").label("APAC Cost of Goods Sold")
                        .attribute("unit", "USD")
                        .attribute("dimension.region", "APAC")
                        .attributes(includeCogsValue ? Map.of("value", 60.0) : Map.of())
                        .build())
                .addEntity(GraphEntity.builder("gross-profit")
                        .type("MEASURE_OBSERVATION").label("APAC Gross Profit")
                        .attribute("unit", "USD")
                        .attribute("dimension.region", "APAC")
                        .build())
                .addEntity(GraphEntity.builder("gross-margin")
                        .type("MEASURE_OBSERVATION").label("Gross Margin")
                        .attribute("unit", "ratio")
                        .attribute("dimension.region", "APAC")
                        .build())
                .addEntity(GraphEntity.builder("rule:gross-profit")
                        .type("CALCULATION_RULE").label("Gross profit calculation")
                        .attribute("expression", "SALES - COGS")
                        .attribute("validated", true)
                        .build())
                .addEntity(GraphEntity.builder("rule:gross-margin")
                        .type("CALCULATION_RULE").label("Gross margin calculation")
                        .attribute("expression", "GROSS_PROFIT / SALES")
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder("gp-output", "rule:gross-profit", "gross-profit")
                        .type("PRODUCES").build())
                .addRelation(GraphRelation.builder("gp-sales", "rule:gross-profit", "sales")
                        .type("REQUIRES").attribute("alias", "SALES").build())
                .addRelation(GraphRelation.builder("gp-cogs", "rule:gross-profit", "cogs")
                        .type("REQUIRES").attribute("alias", "COGS").build())
                .addRelation(GraphRelation.builder("gm-output", "rule:gross-margin", "gross-margin")
                        .type("PRODUCES").build())
                .addRelation(GraphRelation.builder("gm-gp", "rule:gross-margin", "gross-profit")
                        .type("REQUIRES").attribute("alias", "GROSS_PROFIT").build())
                .addRelation(GraphRelation.builder("gm-sales", "rule:gross-margin", "sales")
                        .type("REQUIRES").attribute("alias", "SALES").build());

        graph.addEntity(GraphEntity.builder("gross-margin-eu")
                        .type("MEASURE_OBSERVATION").label("Gross Margin")
                        .attribute("unit", "ratio")
                        .attribute("dimension.region", "EU")
                        .build())
                .addEntity(GraphEntity.builder("rule:gross-margin-eu")
                        .type("CALCULATION_RULE").label("EU gross margin calculation")
                        .attribute("expression", "GROSS_PROFIT / SALES")
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder("gm-eu-output",
                        "rule:gross-margin-eu", "gross-margin-eu").type("PRODUCES").build())
                .addRelation(GraphRelation.builder("gm-eu-gp",
                        "rule:gross-margin-eu", "gross-profit")
                        .type("REQUIRES").attribute("alias", "GROSS_PROFIT").build())
                .addRelation(GraphRelation.builder("gm-eu-sales",
                        "rule:gross-margin-eu", "sales")
                        .type("REQUIRES").attribute("alias", "SALES").build());
        return graph;
    }

    private static GraphEntity cell(String id, String reference, String label, double value) {
        return GraphEntity.builder(id)
                .type("CELL").label(label)
                .attribute("cell_reference", reference)
                .attribute("value", value)
                .build();
    }

    private static void addObservedRule(
            MutableReasoningGraph graph,
            String ruleId,
            String outputId,
            String outputLabel,
            String inputId) {
        graph.addEntity(GraphEntity.builder(inputId)
                        .type("MEASURE_OBSERVATION").label(inputId)
                        .attribute("value", 10.0).build())
                .addEntity(GraphEntity.builder(outputId)
                        .type("MEASURE_OBSERVATION").label(outputLabel).build())
                .addEntity(GraphEntity.builder(ruleId)
                        .type("CALCULATION_RULE").label(ruleId)
                        .attribute("expression", "INPUT")
                        .attribute("validated", true).build())
                .addRelation(GraphRelation.builder(ruleId + ":out", ruleId, outputId)
                        .type("PRODUCES").build())
                .addRelation(GraphRelation.builder(ruleId + ":in", ruleId, inputId)
                        .type("REQUIRES").attribute("alias", "INPUT").build());
    }
}
