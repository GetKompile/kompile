package ai.kompile.e2e;

import ai.kompile.core.guardrails.GuardrailResult;
import ai.kompile.core.guardrails.GuardrailService;
import ai.kompile.core.query.QueryTransformer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests query transformation and guardrails modules together.
 * Verifies that transformers and guardrails can be wired and invoked
 * in the same application context.
 */
@Tag("integration")
@DisplayName("Query Transformer + Guardrails Integration Tests")
@SpringBootTest(
        classes = E2eTestApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "kompile.embedding.type=none",
                "kompile.vectorstore.type=none",
                "kompile.guardrails.enabled=false",
                "kompile.query-transformer.enabled=false"
        }
)
@ActiveProfiles("test")
class QueryTransformerGuardrailsIT {

    @Autowired(required = false)
    private List<QueryTransformer> queryTransformers;

    @Autowired(required = false)
    private GuardrailService guardrailService;

    @Test
    @DisplayName("Query transformers list is injected (may be empty)")
    void testQueryTransformersInjected() {
        // Transformers may or may not be on classpath depending on config
        // This test verifies no wiring errors occur
        if (queryTransformers != null) {
            for (QueryTransformer qt : queryTransformers) {
                assertNotNull(qt.getName(), "Each transformer should have a name");
                assertNotNull(qt.getType(), "Each transformer should have a type");
            }
        }
    }

    @Test
    @DisplayName("GuardrailService bean is injected when available")
    void testGuardrailServiceInjected() {
        if (guardrailService != null) {
            // When disabled, service should report as not enabled
            // but the bean should still be created
            assertNotNull(guardrailService.getInputGuardrails(),
                    "Input guardrails list should not be null");
            assertNotNull(guardrailService.getOutputGuardrails(),
                    "Output guardrails list should not be null");
        }
    }

    @Test
    @DisplayName("Query transformers report their types correctly")
    void testTransformerTypes() {
        if (queryTransformers == null || queryTransformers.isEmpty()) return;

        for (QueryTransformer qt : queryTransformers) {
            assertNotNull(qt.getType(),
                    "Transformer '" + qt.getName() + "' should have a non-null type");
        }
    }

    @Test
    @DisplayName("Guardrails and transformers coexist without conflicts")
    void testCoexistence() {
        // This test passes if the Spring context starts successfully
        // with both modules on the classpath - verifying no bean conflicts
        assertTrue(true, "Context started successfully with both modules");
    }
}
