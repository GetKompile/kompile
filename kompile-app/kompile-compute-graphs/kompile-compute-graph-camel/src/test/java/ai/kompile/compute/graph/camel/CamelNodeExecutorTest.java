package ai.kompile.compute.graph.camel;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CamelNodeExecutorTest {

    private CamelContextManager contextManager;
    private CamelRouteParser routeParser;
    private CamelNodeExecutor executor;

    @BeforeEach
    void setUp() {
        contextManager = new CamelContextManager();
        routeParser = new CamelRouteParser();
        executor = new CamelNodeExecutor(contextManager, routeParser, 30000);
    }

    @AfterEach
    void tearDown() {
        contextManager.close();
    }

    @Test
    void testSupportedTypes() {
        assertEquals(Set.of(NodeExecutionType.CAMEL_ROUTE), executor.supportedTypes());
    }

    @Test
    void testSimpleExpressionRoute() {
        ComputeNode node = ComputeNode.builder()
                .id("test-simple")
                .name("Simple Expression")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${header.greeting} ${header.name}")
                .build();

        Map<String, Object> inputs = Map.of(
                "greeting", "Hello",
                "name", "World"
        );

        ComputeGraph graph = ComputeGraph.builder()
                .id("test-graph")
                .name("Test Graph")
                .nodes(List.of(node))
                .edges(List.of())
                .build();

        ExecutionContext context = new ExecutionContext(
                UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());

        ExecutionResult result = executor.execute(node, inputs, context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertNotNull(result.getOutputs());
        assertEquals("Hello World", result.getOutputs().get("result"));
    }

    @Test
    void testValidationEmptyScript() {
        ComputeNode node = ComputeNode.builder()
                .id("test-empty")
                .name("Empty Script")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("")
                .build();

        String error = executor.validate(node);
        assertNotNull(error);
        assertTrue(error.contains("empty"));
    }

    @Test
    void testValidationNullScript() {
        ComputeNode node = ComputeNode.builder()
                .id("test-null")
                .name("Null Script")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .build();

        String error = executor.validate(node);
        assertNotNull(error);
    }

    @Test
    void testRouteFormatDetection() {
        assertEquals(CamelRouteParser.RouteFormat.XML_DSL,
                routeParser.detectFormat("<route><from uri=\"direct:test\"/></route>"));
        assertEquals(CamelRouteParser.RouteFormat.XML_DSL,
                routeParser.detectFormat("<?xml version=\"1.0\"?><routes></routes>"));
        assertEquals(CamelRouteParser.RouteFormat.YAML_DSL,
                routeParser.detectFormat("- route:\n    from: direct:test"));
        assertEquals(CamelRouteParser.RouteFormat.SIMPLE_EXPRESSION,
                routeParser.detectFormat("${body} processed"));
    }
}
