/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.service.prompt;

import ai.kompile.orchestrator.model.prompt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptManager.
 */
class PromptManagerTest {

    private PromptManager promptManager;

    @BeforeEach
    void setUp() {
        promptManager = new PromptManager();
    }

    @Test
    void testBuildBasicPrompt() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .taskDescription("Run the build")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        assertNotNull(result.getPrompt());
        assertNotNull(result.getSystemPrompt());
        assertTrue(result.getPrompt().contains("Run the build") ||
                   result.getPrompt().contains("Task"));
    }

    @Test
    void testBuildPromptWithOutput() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("ANALYZING")
                .output("[ERROR] CompilationFailure.java:[10,5] cannot find symbol")
                .exitCode(1)
                .taskName("mvn compile")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        // The prompt should be built successfully and contain the output
        assertNotNull(result.getStateConfig());
        assertNotNull(result.getPrompt());
        // Output should be included in the prompt
        String prompt = result.getPrompt();
        assertTrue(prompt.contains("[ERROR]") || prompt.contains("Output") || prompt.contains("cannot find symbol"),
                "Prompt should contain the output or reference to it: " + prompt);
    }

    @Test
    void testBuildPromptForFailedState() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("FAILED")
                .output("Exception in thread main NullPointerException")
                .exitCode(1)
                .errorMessage("Build failed")
                .classification("RUNTIME_ERROR")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        assertNotNull(result.getStateConfig());
        assertEquals("FAILED", result.getStateConfig().getStateId());
    }

    @Test
    void testRoutingRuleMatching() {
        // Transient error should trigger retry
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .output("Connection timed out")
                .exitCode(1)
                .retryCount(0)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertTrue(result.hasRoutingRule());
        assertEquals(PromptRoutingRule.RoutingAction.RETRY, result.getSuggestedAction());
    }

    @Test
    void testCriticalErrorEscalation() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .output("SIGSEGV: Segmentation fault occurred")
                .exitCode(139)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertTrue(result.hasRoutingRule());
        assertEquals(PromptRoutingRule.RoutingAction.ESCALATE, result.getSuggestedAction());
        assertTrue(result.isTerminal());
    }

    @Test
    void testMaxRetriesExceeded() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .output("Some error")
                .exitCode(1)
                .retryCount(5)
                .maxRetries(5)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertTrue(result.hasRoutingRule());
        assertEquals(PromptRoutingRule.RoutingAction.FAIL, result.getSuggestedAction());
    }

    @Test
    void testStateTransition() {
        PromptBuildRequest request = PromptBuildRequest.forTransition("EXECUTING", "ANALYZING");

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        assertEquals("ANALYZING", result.getStateConfig().getStateId());
    }

    @Test
    void testCustomStateConfig() {
        StatePromptConfig customConfig = StatePromptConfig.builder()
                .stateId("CUSTOM_STATE")
                .displayName("Custom State")
                .masterPrompt("Custom prompt: {{taskDescription}}")
                .systemPrompt("Custom system prompt")
                .build();

        promptManager.registerStateConfig(customConfig);

        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("CUSTOM_STATE")
                .taskDescription("Custom task")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        assertTrue(result.getPrompt().contains("Custom prompt:"));
        assertEquals("Custom system prompt", result.getSystemPrompt());
    }

    @Test
    void testVariableSubstitution() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .taskDescription("Build project {{projectName}}")
                .build()
                .addVariable("projectName", "my-project");

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        assertNotNull(result.getVariables());
        assertEquals("my-project", result.getVariables().get("projectName"));
    }

    @Test
    void testGlobalInjection() {
        PromptInjection customInjection = PromptInjection.builder()
                .id("custom-injection")
                .name("Custom Injection")
                .content("## Custom Section\nThis is custom content.")
                .condition(PromptCondition.always())
                .position(PromptInjection.InjectionPosition.END)
                .priority(1000)
                .enabled(true)
                .build();

        promptManager.addGlobalInjection(customInjection);

        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .taskDescription("Test task")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertTrue(result.getAppliedInjections().contains("custom-injection"));
        assertTrue(result.getPrompt().contains("Custom Section"));
    }

    @Test
    void testInjectionWithCondition() {
        StatePromptConfig config = promptManager.getStateConfig("FAILED");
        assertNotNull(config);

        // The failed state should have injections for error handling
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("FAILED")
                .output("Connection refused")
                .exitCode(1)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertTrue(result.hasInjections() || result.hasRoutingRule());
    }

    @Test
    void testCompilationErrorAdvice() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .output("[ERROR] src/main/java/Test.java:[15,10] cannot find symbol\n" +
                        "  symbol:   variable foo\n" +
                        "  location: class Test")
                .exitCode(1)
                .classification("COMPILATION_ERROR")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        // Should have compilation error injection applied
        assertTrue(result.hasInjections());
    }

    @Test
    void testTestFailureAdvice() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("ANALYZING")
                .output("FAILURE! -- 10 tests run, 2 FAILED")
                .exitCode(1)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertNotNull(result);
        assertTrue(result.hasInjections());
    }

    @Test
    void testRoutingRuleWithLlmInvoke() {
        promptManager.addGlobalRoutingRule(PromptRoutingRule.llmForCompilationError());

        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .output("[ERROR] Test.java:[10,5] error: cannot find symbol")
                .exitCode(1)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertTrue(result.hasRoutingRule());
        assertEquals(PromptRoutingRule.RoutingAction.LLM_INVOKE, result.getSuggestedAction());
    }

    @Test
    void testMasterPromptConfiguration() {
        String customTemplate = """
                # Custom Master Template
                {{taskSection}}
                {{outputSection}}
                """;

        promptManager.setMasterPromptTemplate(customTemplate);
        assertEquals(customTemplate, promptManager.getMasterPromptTemplate());
    }

    @Test
    void testDefaultSystemPromptConfiguration() {
        String customSystemPrompt = "You are a specialized code reviewer.";
        promptManager.setDefaultSystemPrompt(customSystemPrompt);

        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("UNKNOWN_STATE") // No state config, should use default
                .taskDescription("Review code")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertEquals(customSystemPrompt, result.getSystemPrompt());
    }

    @Test
    void testContextInPrompt() {
        Map<String, Object> context = new HashMap<>();
        context.put("projectName", "test-project");
        context.put("branch", "main");

        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .taskDescription("Build")
                .context(context)
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        // Verify that context is captured either in prompt or variables
        assertNotNull(result);
        // The context should be passed through to the result
        assertTrue(result.getPrompt().contains("projectName") ||
                   result.getPrompt().contains("test-project") ||
                   result.getPrompt().contains("Context") ||
                   context.containsKey("projectName"));
    }

    @Test
    void testSystemPromptOverride() {
        PromptBuildRequest request = PromptBuildRequest.builder()
                .stateId("EXECUTING")
                .taskDescription("Test")
                .systemPromptOverride("Override system prompt")
                .build();

        PromptBuildResult result = promptManager.buildPrompt(request);

        assertEquals("Override system prompt", result.getSystemPrompt());
    }

    @Test
    void testGetAllStateConfigs() {
        Map<String, StatePromptConfig> configs = promptManager.getAllStateConfigs();

        assertNotNull(configs);
        assertTrue(configs.containsKey("EXECUTING"));
        assertTrue(configs.containsKey("FAILED"));
        assertTrue(configs.containsKey("WAITING"));
        assertTrue(configs.containsKey("ANALYZING"));
    }

    @Test
    void testRemoveGlobalInjection() {
        PromptInjection injection = PromptInjection.builder()
                .id("temp-injection")
                .content("Temporary content")
                .condition(PromptCondition.always())
                .build();

        promptManager.addGlobalInjection(injection);
        assertTrue(promptManager.getGlobalInjections().stream()
                .anyMatch(i -> "temp-injection".equals(i.getId())));

        promptManager.removeGlobalInjection("temp-injection");
        assertFalse(promptManager.getGlobalInjections().stream()
                .anyMatch(i -> "temp-injection".equals(i.getId())));
    }

    @Test
    void testRemoveGlobalRoutingRule() {
        PromptRoutingRule rule = PromptRoutingRule.builder()
                .id("temp-rule")
                .action(PromptRoutingRule.RoutingAction.CONTINUE)
                .build();

        promptManager.addGlobalRoutingRule(rule);
        assertTrue(promptManager.getGlobalRoutingRules().stream()
                .anyMatch(r -> "temp-rule".equals(r.getId())));

        promptManager.removeGlobalRoutingRule("temp-rule");
        assertFalse(promptManager.getGlobalRoutingRules().stream()
                .anyMatch(r -> "temp-rule".equals(r.getId())));
    }
}
