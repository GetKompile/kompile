package ai.kompile.cli.main.chat.harness.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalSuiteTest {

    // ========================================================================
    // JSON deserialization
    // ========================================================================

    @Test
    void deserializeSuiteFromJson() throws Exception {
        String json = """
                {
                  "name": "Test Suite",
                  "description": "A test suite",
                  "agent": "claude",
                  "model": "claude-sonnet-4-20250514",
                  "provider": "anthropic",
                  "requiredPassRate": 0.9,
                  "cases": [
                    {
                      "id": "test-1",
                      "name": "Hello Test",
                      "prompt": "Say hello",
                      "assertions": [
                        {"type": "NO_ESCAPE"},
                        {"type": "OUTPUT_CONTAINS", "value": "hello"}
                      ],
                      "timeoutMs": 60000,
                      "tags": ["basic"]
                    },
                    {
                      "id": "test-2",
                      "name": "Disabled Test",
                      "prompt": "Should be skipped",
                      "enabled": false
                    }
                  ]
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        EvalSuite suite = mapper.readValue(json, EvalSuite.class);

        assertEquals("Test Suite", suite.getName());
        assertEquals("A test suite", suite.getDescription());
        assertEquals("claude", suite.getAgent());
        assertEquals("claude-sonnet-4-20250514", suite.getModel());
        assertEquals("anthropic", suite.getProvider());
        assertEquals(0.9, suite.getRequiredPassRate(), 0.001);
        assertEquals(2, suite.getCases().size());
    }

    @Test
    void enabledCases_filtersDisabled() throws Exception {
        String json = """
                {
                  "name": "Suite",
                  "cases": [
                    {"id": "a", "prompt": "p1", "enabled": true},
                    {"id": "b", "prompt": "p2", "enabled": false},
                    {"id": "c", "prompt": "p3"}
                  ]
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        EvalSuite suite = mapper.readValue(json, EvalSuite.class);

        List<EvalCase> enabled = suite.enabledCases();
        assertEquals(2, enabled.size());
        assertEquals("a", enabled.get(0).getId());
        assertEquals("c", enabled.get(1).getId());
    }

    // ========================================================================
    // Effective agent/model/provider resolution
    // ========================================================================

    @Test
    void effectiveAgent_caseOverridesSuite() {
        EvalSuite suite = new EvalSuite();
        suite.setAgent("claude");

        EvalCase caseWithOverride = new EvalCase();
        caseWithOverride.setAgent("codex");

        EvalCase caseWithoutOverride = new EvalCase();

        assertEquals("codex", suite.effectiveAgent(caseWithOverride));
        assertEquals("claude", suite.effectiveAgent(caseWithoutOverride));
    }

    @Test
    void effectiveModel_caseOverridesSuite() {
        EvalSuite suite = new EvalSuite();
        suite.setModel("default-model");

        EvalCase caseWithOverride = new EvalCase();
        caseWithOverride.setModel("specific-model");

        assertEquals("specific-model", suite.effectiveModel(caseWithOverride));
    }

    @Test
    void effectiveProvider_caseOverridesSuite() {
        EvalSuite suite = new EvalSuite();
        suite.setProvider("anthropic");

        EvalCase caseWithOverride = new EvalCase();
        caseWithOverride.setProvider("openai");

        assertEquals("openai", suite.effectiveProvider(caseWithOverride));
    }

    @Test
    void effectiveAgent_nullSuiteAndCase_returnsNull() {
        EvalSuite suite = new EvalSuite();
        EvalCase evalCase = new EvalCase();

        assertNull(suite.effectiveAgent(evalCase));
    }

    // ========================================================================
    // Defaults
    // ========================================================================

    @Test
    void suiteDefaults() {
        EvalSuite suite = new EvalSuite();
        assertEquals(0.8, suite.getRequiredPassRate(), 0.001);
    }

    @Test
    void caseDefaults() {
        EvalCase evalCase = new EvalCase();
        assertEquals(120_000, evalCase.getTimeoutMs());
        assertEquals(50, evalCase.getMaxSteps());
        assertTrue(evalCase.isEnabled());
        assertEquals(3, evalCase.getPriority());
    }
}
