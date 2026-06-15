package ai.kompile.compute.graph.rest;

import ai.kompile.compute.graph.config.ComputeGraphConfigService;
import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.engine.PassthroughNodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import ai.kompile.compute.graph.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the /availability endpoint in ComputeGraphController.
 * Verifies classpath probing, executor registration detection, and response structure.
 */
class ComputeGraphControllerAvailabilityTest {

    private ComputeGraphController controller;

    @BeforeEach
    void setUp() {
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        // Register only PassthroughNodeExecutor — mimics a minimal deployment
        List<NodeExecutor> executors = List.of(new PassthroughNodeExecutor());
        DefaultGraphExecutor engine = new DefaultGraphExecutor(executors, artifactStore);
        ComputeGraphConfigService configService = new ComputeGraphConfigService();
        controller = new ComputeGraphController(engine, artifactStore, configService, executors);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_returnsAllBackends() {
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        Map<String, Object> backends = (Map<String, Object>) body.get("backends");
        assertNotNull(backends);

        // All expected backend keys are present
        List<String> expectedKeys = List.of(
                "javascript", "python", "drools_rule", "drools_inference",
                "drools_decision_table", "expression", "passthrough",
                "excel", "camel_route", "xircuits", "n8n");
        for (String key : expectedKeys) {
            assertTrue(backends.containsKey(key), "Missing backend key: " + key);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_passthroughIsReady() {
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");
        Map<String, Object> passthrough = (Map<String, Object>) backends.get("passthrough");

        assertEquals("Passthrough (no-op)", passthrough.get("name"));
        assertTrue((boolean) passthrough.get("classpathAvailable"));
        assertTrue((boolean) passthrough.get("executorRegistered"));
        assertTrue((boolean) passthrough.get("ready"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_graalvmJsDetected() {
        // GraalVM polyglot is on the test classpath (it's a dependency of compute-graph-core's
        // sibling module), so this may or may not be true depending on test classpath.
        // We just verify the structure is correct.
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");
        Map<String, Object> js = (Map<String, Object>) backends.get("javascript");

        assertEquals("GraalVM Polyglot JS", js.get("name"));
        assertNotNull(js.get("classpathAvailable"));
        // No JS executor is registered (only passthrough), so executorRegistered should be false
        assertFalse((boolean) js.get("executorRegistered"));
        assertFalse((boolean) js.get("ready"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_droolsNotOnClasspath() {
        // Drools KIE is NOT on the test classpath for compute-graph-core
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");
        Map<String, Object> drools = (Map<String, Object>) backends.get("drools_rule");

        assertEquals("Drools / KIE", drools.get("name"));
        assertFalse((boolean) drools.get("classpathAvailable"));
        assertFalse((boolean) drools.get("executorRegistered"));
        assertFalse((boolean) drools.get("ready"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_camelNotOnClasspath() {
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");
        Map<String, Object> camel = (Map<String, Object>) backends.get("camel_route");

        assertFalse((boolean) camel.get("classpathAvailable"));
        assertFalse((boolean) camel.get("executorRegistered"));
        assertFalse((boolean) camel.get("ready"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_spelAlwaysAvailable() {
        // SpEL is always on classpath since spring-expression is a dependency
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");
        Map<String, Object> expr = (Map<String, Object>) backends.get("expression");

        assertEquals("Spring Expression Language (SpEL)", expr.get("name"));
        assertTrue((boolean) expr.get("classpathAvailable"));
        // ExpressionNodeExecutor is NOT registered (only passthrough), so executor is false
        assertFalse((boolean) expr.get("executorRegistered"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_registeredExecutorsSummary() {
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> body = response.getBody();
        List<Map<String, Object>> executorList = (List<Map<String, Object>>) body.get("registeredExecutors");

        assertNotNull(executorList);
        assertEquals(1, executorList.size());

        Map<String, Object> entry = executorList.get(0);
        assertEquals("PassthroughNodeExecutor", entry.get("class"));
        List<String> types = (List<String>) entry.get("types");
        assertTrue(types.contains("PASSTHROUGH"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_withMultipleExecutors() {
        // Create a fake executor that handles JAVASCRIPT
        NodeExecutor fakeJsExecutor = new NodeExecutor() {
            @Override
            public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .status(ExecutionStatus.COMPLETED)
                        .outputs(Map.of())
                        .startedAt(Instant.now())
                        .completedAt(Instant.now())
                        .build();
            }

            @Override
            public Set<NodeExecutionType> supportedTypes() {
                return Set.of(NodeExecutionType.JAVASCRIPT);
            }
        };

        List<NodeExecutor> executors = List.of(new PassthroughNodeExecutor(), fakeJsExecutor);
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        DefaultGraphExecutor engine = new DefaultGraphExecutor(executors, artifactStore);
        ComputeGraphControllerAvailabilityTest.this.controller =
                new ComputeGraphController(engine, artifactStore, new ComputeGraphConfigService(), executors);

        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");
        Map<String, Object> js = (Map<String, Object>) backends.get("javascript");

        // Now JS has an executor registered
        assertTrue((boolean) js.get("executorRegistered"));

        List<Map<String, Object>> executorSummary = (List<Map<String, Object>>) response.getBody().get("registeredExecutors");
        assertEquals(2, executorSummary.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_backendInfoStructure() {
        // Verify every backend entry has the required fields
        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");

        for (Map.Entry<String, Object> entry : backends.entrySet()) {
            Map<String, Object> info = (Map<String, Object>) entry.getValue();
            assertNotNull(info.get("name"), "name missing for " + entry.getKey());
            assertNotNull(info.get("classpathAvailable"), "classpathAvailable missing for " + entry.getKey());
            assertNotNull(info.get("executorRegistered"), "executorRegistered missing for " + entry.getKey());
            assertNotNull(info.get("ready"), "ready missing for " + entry.getKey());

            // ready should be the AND of classpathAvailable and executorRegistered
            boolean cp = (boolean) info.get("classpathAvailable");
            boolean exec = (boolean) info.get("executorRegistered");
            assertEquals(cp && exec, info.get("ready"),
                    "ready mismatch for " + entry.getKey());
        }
    }
}
