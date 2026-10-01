/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.quantitative.ScenarioResult;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphReasoningQueryTool}.
 *
 * <p>The tool is now a thin delegate to {@link GraphReasoningQueryService}. Tests verify that
 * (a) delegation is correct and (b) the service behaviour is observable through the tool,
 * matching the original test contract so callers can rely on stable tool semantics.</p>
 */
class GraphReasoningQueryToolTest {

    // ── Construction helpers ──────────────────────────────────────────────────

    /**
     * Build a tool that uses a real {@link GraphReasoningQueryService} backed by a mocked bridge.
     */
    private static GraphReasoningQueryTool toolWithBridge(UnifiedGraphBridge bridge) {
        return new GraphReasoningQueryTool(new GraphReasoningQueryService(bridge));
    }

    // ── Tool delegates to service ─────────────────────────────────────────────

    @Test
    void delegatesToServiceNotDirectlyToBridge() {
        GraphReasoningQueryService service = mock(GraphReasoningQueryService.class);
        when(service.execute(any())).thenReturn(
                GraphReasoningQueryService.invalid("stub"));

        GraphReasoningQueryTool tool = new GraphReasoningQueryTool(service);
        tool.query(new GraphReasoningQueryTool.QueryInput(
                null, "overview", null, null, null, null, null, null, null, null, null));

        verify(service, times(1)).execute(any());
    }

    @Test
    void generatedToolInputMarksEveryComponentOptionalAndExposesQuestion() {
        List<String> components = java.util.Arrays.stream(GraphReasoningQueryTool.QueryInput.class
                .getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        assertTrue(components.contains("question"));
        Constructor<?> canonical = java.util.Arrays.stream(
                        GraphReasoningQueryTool.QueryInput.class.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == components.size())
                .findFirst()
                .orElseThrow();
        Parameter[] parameters = canonical.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            ToolParam parameter = parameters[index].getAnnotation(ToolParam.class);
            assertNotNull(parameter, components.get(index));
            assertFalse(parameter.required(), components.get(index) + " must be optional");
        }
    }

    @Test
    void nullInputReturnsInvalidWithoutCallingService() {
        GraphReasoningQueryService service = mock(GraphReasoningQueryService.class);
        GraphReasoningQueryTool tool = new GraphReasoningQueryTool(service);

        GraphQueryEngine.Result result = tool.query(null);

        assertEquals(GraphQueryEngine.Status.INVALID, result.status());
        verifyNoInteractions(service);
    }

    // ── Original contract (now exercised through delegation) ──────────────────

    @Test
    void capabilitiesIsSelfDescribingAndDoesNotLoadGraph() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                null, "capabilities", null, null, null, null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(GraphReasoningQueryService.queryRequestOperations().size(),
                result.capabilities().size());
        assertNotNull(result.trace());
        verifyNoInteractions(bridge);
    }

    @Test
    void omittedOperationUsesQueryTextAsSearch() throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("orchid", "PROJECT", "Orchid");
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(graph);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, null, null, null, null, null, null, 5, null, null, "Orchid"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(GraphQueryEngine.Intent.SEARCH, result.intent());
        assertTrue(result.entities().stream().anyMatch(entity -> "orchid".equals(entity.id())));
    }

    @Test
    void naturalLanguageQuestionAliasExecutesSearch() throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("orchid", "PROJECT", "Orchid");
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(graph);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, null, null, null, null, null, null, 5, null, null,
                null, "Find Orchid"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(GraphQueryEngine.Intent.SEARCH, result.intent());
        assertTrue(result.entities().stream().anyMatch(entity -> "orchid".equals(entity.id())));
    }

    @Test
    void omittedOperationAndQueryTextReturnsCapabilitiesWithoutLoadingGraph() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(GraphQueryEngine.Intent.CAPABILITIES, result.intent());
        verifyNoInteractions(bridge);
    }

    @Test
    void executesReadablePathQueryOverLiveUnifiedGraph() throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("person", "PERSON", "Jordan Lee");
        graph.addEntity("email", "EMAIL", "Close package email");
        graph.addEntity("file", "DOCUMENT", "regional-close.xlsx");
        graph.addRelation("r1", "person", "email", "SENT", 0.95);
        graph.addRelation("r2", "email", "file", "HAS_ATTACHMENT", 0.9);

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.resolveSeedIds(42L, "Jordan Lee")).thenReturn(List.of("person"));
        when(bridge.resolveSeedIds(42L, "regional-close.xlsx")).thenReturn(List.of("file"));
        when(bridge.exportNeighborhood(eq(42L), eq(List.of("person", "file")), eq(List.of("person")),
                anyInt(), anyInt(), eq(GraphQueryEngine.Direction.OUTGOING), anyInt()))
                .thenReturn(graph);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, "path", "Jordan Lee", "regional-close.xlsx", "forward", List.of(),
                4, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(2, result.resolutions().size());
        assertNotNull(result.trace());
        assertEquals(List.of("Jordan Lee", "Close package email", "regional-close.xlsx"),
                result.path().stream().map(step -> step.entity().label()).toList());
        assertEquals(List.of("SENT", "HAS_ATTACHMENT"),
                result.relations().stream().map(GraphQueryEngine.RelationView::type).toList());
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"resolutions\""));
        assertTrue(json.contains("\"trace\":{"));
        assertTrue(json.contains("\"conclusion\""));
        assertTrue(json.contains("\"premises\""));
        verify(bridge, never()).export(42L);
    }

    @Test
    void verifiesTypedClaimsThroughTheSingleTool() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("person", "PERSON", "Jordan Lee");
        graph.addEntity("email", "EMAIL", "Close package email");
        graph.addRelation("r1", "person", "email", "SENT", 0.95);

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.resolveSeedIds(42L, "Jordan Lee")).thenReturn(List.of("person"));
        when(bridge.resolveSeedIds(42L, "Close package email")).thenReturn(List.of("email"));
        when(bridge.exportNeighborhood(eq(42L), eq(List.of("person", "email")), eq(List.of("person")),
                anyInt(), anyInt(), eq(GraphQueryEngine.Direction.OUTGOING), anyInt()))
                .thenReturn(graph);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, "verify", "Jordan Lee", "Close package email", null, List.of("sent"),
                null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status());
        assertEquals("r1", result.relations().get(0).id());
        assertEquals(2, result.resolutions().size());
        assertNotNull(result.trace());
        verify(bridge, never()).export(42L);
    }

    @Test
    void rejectsInvalidOptionsBeforeLoadingTheGraph() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, "path", "a", "b", "sideways", null,
                null, null, null, "unknown", null));

        assertEquals(GraphQueryEngine.Status.INVALID, result.status());
        assertTrue(result.summary().contains("direction"));
        assertNotNull(result.trace());
        verifyNoInteractions(bridge);
    }

    @Test
    void rejectsUnknownOperationsWithRecoveryGuidance() {
        GraphReasoningQueryTool tool = toolWithBridge(mock(UnifiedGraphBridge.class));

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                null, "guess", null, null, null, null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.INVALID, result.status());
        assertFalse(result.guidance().isEmpty());
        assertNotNull(result.trace());
    }

    @Test
    void quantitativeObjectRunsThroughTheTool() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(formulaGraph());
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result calculated = tool.query(quantitative(
                "CALCULATE", Map.of("target", Map.of("text", "Total"))));
        assertEquals(GraphQueryEngine.Status.OK, calculated.status(), calculated.summary());
        assertEquals("Calculated a3 = 30.0.", calculated.summary());

        GraphQueryEngine.Result scenario = tool.query(quantitative("SCENARIO", Map.of(
                "target", Map.of("entityId", "a3"),
                "interventions", List.of(Map.of(
                        "target", Map.of("entityId", "a1"), "operation", "SCALE", "value", -0.5)))));
        assertEquals(GraphQueryEngine.Status.OK, scenario.status(), scenario.summary());
        ScenarioResult values = assertInstanceOf(ScenarioResult.class, scenario.data().get("scenario"));
        assertEquals(30.0, values.baselineValue(), 1.0e-9);
        assertEquals(25.0, values.scenarioValue(), 1.0e-9);

        GraphQueryEngine.Result missingTarget = tool.query(quantitative("SOLVE_TARGET", null));
        assertEquals(GraphQueryEngine.Status.INVALID, missingTarget.status());
        assertTrue(missingTarget.summary().contains("Example: quantitative="), missingTarget.summary());
    }

    @Test
    void toolDescriptionNamesEveryOperationTheServiceRuns() throws Exception {
        String description = queryMethod().getAnnotation(Tool.class).description();
        Set<String> words = Arrays.stream(description.split("[^A-Z_]+")).collect(Collectors.toSet());
        for (String operation : GraphReasoningQueryService.queryRequestOperations()) {
            assertTrue(words.contains(operation), operation + " is missing from: " + description);
        }
    }

    @Test
    void generatedSchemaExposesQuantitativeAsAnObject() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(JsonSchemaGenerator.generateForMethodInput(queryMethod()));
        JsonNode quantitative = schema.findValue("quantitative");
        assertNotNull(quantitative, schema.toString());
        assertEquals("object", quantitative.path("type").asText(), quantitative.toString());
        assertTrue(quantitative.path("description").asText().contains("SOLVE_TARGET"), quantitative.toString());
    }

    private static Method queryMethod() throws NoSuchMethodException {
        return GraphReasoningQueryTool.class.getMethod("query", GraphReasoningQueryTool.QueryInput.class);
    }

    private static GraphReasoningQueryTool.QueryInput quantitative(String operation, Map<String, Object> spec) {
        return new GraphReasoningQueryTool.QueryInput(
                42L, operation, null, null, null, null, null, null, null, null, null, null, spec);
    }

    private static UnifiedGraph formulaGraph() {
        return new UnifiedGraph()
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
