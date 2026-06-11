package ai.kompile.graph;

import ai.kompile.compute.graph.config.ComputeGraphConfigService;
import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.engine.PassthroughNodeExecutor;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.rest.ComputeGraphController;
import ai.kompile.compute.graph.scripting.ExpressionNodeExecutor;
import ai.kompile.compute.graph.scripting.ScriptingNodeExecutor;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the availability endpoint with real executors.
 * Verifies that the availability probe correctly detects actually-registered
 * executors and classpath presence across a realistic configuration.
 */
class ComputeGraphAvailabilityIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_withRealExecutors() {
        // Wire up real executors like the production configuration
        List<NodeExecutor> executors = List.of(
                new ScriptingNodeExecutor(),    // handles JAVASCRIPT
                new ExpressionNodeExecutor(),   // handles EXPRESSION
                new PassthroughNodeExecutor()   // handles PASSTHROUGH
        );

        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        DefaultGraphExecutor engine = new DefaultGraphExecutor(executors, artifactStore);
        ComputeGraphConfigService configService = new ComputeGraphConfigService();
        ComputeGraphController controller = new ComputeGraphController(
                engine, artifactStore, configService, executors);

        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> body = response.getBody();
        Map<String, Object> backends = (Map<String, Object>) body.get("backends");

        // JavaScript: GraalVM polyglot IS on classpath, executor IS registered
        Map<String, Object> js = (Map<String, Object>) backends.get("javascript");
        assertTrue((boolean) js.get("classpathAvailable"), "GraalVM should be on classpath");
        assertTrue((boolean) js.get("executorRegistered"), "ScriptingNodeExecutor should be registered");
        assertTrue((boolean) js.get("ready"), "JavaScript should be ready");
        assertNotNull(js.get("version"), "Should report GraalVM version");

        // Expression: SpEL IS on classpath, executor IS registered
        Map<String, Object> expr = (Map<String, Object>) backends.get("expression");
        assertTrue((boolean) expr.get("classpathAvailable"));
        assertTrue((boolean) expr.get("executorRegistered"));
        assertTrue((boolean) expr.get("ready"));

        // Passthrough: always available, IS registered
        Map<String, Object> pt = (Map<String, Object>) backends.get("passthrough");
        assertTrue((boolean) pt.get("ready"));

        // Drools: NOT on classpath, NOT registered
        Map<String, Object> drools = (Map<String, Object>) backends.get("drools_rule");
        assertFalse((boolean) drools.get("classpathAvailable"));
        assertFalse((boolean) drools.get("executorRegistered"));
        assertFalse((boolean) drools.get("ready"));

        // Camel: NOT on classpath
        Map<String, Object> camel = (Map<String, Object>) backends.get("camel_route");
        assertFalse((boolean) camel.get("ready"));

        // Python: classpath check depends on whether python4j-core resolved.
        // The executor is NOT registered (we didn't add Python4jNodeExecutor)
        Map<String, Object> python = (Map<String, Object>) backends.get("python");
        assertFalse((boolean) python.get("executorRegistered"),
                "Python executor not registered in this test config");
        assertFalse((boolean) python.get("ready"),
                "Python should not be ready without executor");

        // Registered executors summary
        List<Map<String, Object>> registeredExecs =
                (List<Map<String, Object>>) body.get("registeredExecutors");
        assertEquals(3, registeredExecs.size());

        // Verify all 3 executor classes are listed
        List<String> classNames = registeredExecs.stream()
                .map(e -> (String) e.get("class"))
                .toList();
        assertTrue(classNames.contains("ScriptingNodeExecutor"));
        assertTrue(classNames.contains("ExpressionNodeExecutor"));
        assertTrue(classNames.contains("PassthroughNodeExecutor"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAvailability_readyCountMatchesRegisteredTypes() {
        List<NodeExecutor> executors = List.of(
                new ScriptingNodeExecutor(),
                new ExpressionNodeExecutor(),
                new PassthroughNodeExecutor()
        );

        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        DefaultGraphExecutor engine = new DefaultGraphExecutor(executors, artifactStore);
        ComputeGraphController controller = new ComputeGraphController(
                engine, artifactStore, new ComputeGraphConfigService(), executors);

        ResponseEntity<Map<String, Object>> response = controller.getAvailability();
        Map<String, Object> backends = (Map<String, Object>) response.getBody().get("backends");

        // Count how many backends report ready=true
        long readyCount = backends.values().stream()
                .map(v -> (Map<String, Object>) v)
                .filter(info -> (boolean) info.get("ready"))
                .count();

        // We registered 3 executors covering JAVASCRIPT, EXPRESSION, PASSTHROUGH
        // All 3 should have classpath available + executor registered = ready
        assertTrue(readyCount >= 3,
                "At least 3 backends should be ready (JS, Expression, Passthrough), got " + readyCount);
    }
}
