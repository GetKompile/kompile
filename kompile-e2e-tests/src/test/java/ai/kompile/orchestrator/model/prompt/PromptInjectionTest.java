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
package ai.kompile.orchestrator.model.prompt;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptInjection.
 */
class PromptInjectionTest {

    @Test
    void testRenderWithVariables() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Hello {{name}}, your task is {{taskName}}.")
                .build();

        Map<String, Object> variables = new HashMap<>();
        variables.put("name", "User");
        variables.put("taskName", "Build Project");

        String rendered = injection.render(variables);

        assertEquals("Hello User, your task is Build Project.", rendered);
    }

    @Test
    void testRenderWithMissingVariables() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Hello {{name}}, your task is {{taskName}}.")
                .build();

        Map<String, Object> variables = new HashMap<>();
        variables.put("name", "User");
        // taskName is missing

        String rendered = injection.render(variables);

        assertEquals("Hello User, your task is .", rendered);
    }

    @Test
    void testRenderWithEmptyVariables() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Hello {{name}}!")
                .build();

        String rendered = injection.render(new HashMap<>());

        assertEquals("Hello !", rendered);
    }

    @Test
    void testRenderWithNullVariables() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Hello {{name}}!")
                .build();

        String rendered = injection.render(null);

        assertEquals("Hello !", rendered);
    }

    @Test
    void testAppliesWithNoCondition() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Content")
                .enabled(true)
                .build();

        assertTrue(injection.applies("ANY", null, null, null, null, null, 0));
    }

    @Test
    void testAppliesWithCondition() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Content")
                .condition(PromptCondition.forState("FAILED"))
                .enabled(true)
                .build();

        assertTrue(injection.applies("FAILED", null, null, null, null, null, 0));
        assertFalse(injection.applies("SUCCESS", null, null, null, null, null, 0));
    }

    @Test
    void testAppliesWhenDisabled() {
        PromptInjection injection = PromptInjection.builder()
                .id("test")
                .content("Content")
                .enabled(false)
                .build();

        assertFalse(injection.applies("ANY", null, null, null, null, null, 0));
    }

    @Test
    void testCompilationErrorAdviceFactory() {
        PromptInjection injection = PromptInjection.compilationErrorAdvice();

        assertNotNull(injection);
        assertEquals("compilation-error-advice", injection.getId());
        assertEquals(PromptInjection.InjectionType.ERROR_HANDLING, injection.getType());
        assertEquals(PromptInjection.InjectionPosition.BEFORE_OUTPUT, injection.getPosition());

        // Test that it matches compilation errors
        assertTrue(injection.applies(null, null, null,
                "[ERROR] Test.java:[10,5] cannot find symbol", null, null, 0));
        assertFalse(injection.applies(null, null, null,
                "Build successful", null, null, 0));
    }

    @Test
    void testTestFailureAdviceFactory() {
        PromptInjection injection = PromptInjection.testFailureAdvice();

        assertNotNull(injection);
        assertEquals("test-failure-advice", injection.getId());

        assertTrue(injection.applies(null, null, null,
                "FAILURE! -- Tests run: 10, FAILED: 2", null, null, 0));
        assertFalse(injection.applies(null, null, null,
                "Tests run: 10, Passed: 10", null, null, 0));
    }

    @Test
    void testRuntimeExceptionAdviceFactory() {
        PromptInjection injection = PromptInjection.runtimeExceptionAdvice();

        assertNotNull(injection);
        assertEquals("runtime-exception-advice", injection.getId());

        assertTrue(injection.applies(null, null, null,
                "Exception in thread main: NullPointerException", null, null, 0));
        assertTrue(injection.applies(null, null, null,
                "at com.example.Test.run(Test.java:42)", null, null, 0));
    }

    @Test
    void testFailedStateRoutingFactory() {
        PromptInjection injection = PromptInjection.failedStateRouting();

        assertNotNull(injection);
        assertEquals("failed-state-routing", injection.getId());
        assertEquals(PromptInjection.InjectionType.ROUTING, injection.getType());

        assertTrue(injection.applies("FAILED", null, null, null, null, null, 0));
        assertFalse(injection.applies("SUCCESS", null, null, null, null, null, 0));
    }

    @Test
    void testRetryContextFactory() {
        PromptInjection injection = PromptInjection.retryContext(5);

        assertNotNull(injection);
        assertEquals("retry-context", injection.getId());
        assertEquals(PromptInjection.InjectionType.CONTEXT, injection.getType());

        assertTrue(injection.applies(null, null, null, null, null, null, 1));
        assertTrue(injection.applies(null, null, null, null, null, null, 3));
        assertFalse(injection.applies(null, null, null, null, null, null, 0));
    }

    @Test
    void testDependencyErrorAdviceFactory() {
        PromptInjection injection = PromptInjection.dependencyErrorAdvice();

        assertNotNull(injection);
        assertEquals("dependency-error-advice", injection.getId());

        assertTrue(injection.applies(null, null, null,
                "Could not resolve dependencies", null, null, 0));
        assertTrue(injection.applies(null, null, null,
                "NoClassDefFoundError: org.example.Missing", null, null, 0));
    }

    @Test
    void testSafeOperationsConstraintFactory() {
        PromptInjection injection = PromptInjection.safeOperationsConstraint();

        assertNotNull(injection);
        assertEquals("safe-operations", injection.getId());
        assertEquals(PromptInjection.InjectionType.CONSTRAINTS, injection.getType());

        // Should always apply
        assertTrue(injection.applies("ANY", null, null, null, null, null, 0));
    }

    @Test
    void testInjectionPositions() {
        assertEquals(PromptInjection.InjectionPosition.START,
                PromptInjection.InjectionPosition.valueOf("START"));
        assertEquals(PromptInjection.InjectionPosition.BEFORE_SYSTEM,
                PromptInjection.InjectionPosition.valueOf("BEFORE_SYSTEM"));
        assertEquals(PromptInjection.InjectionPosition.AFTER_SYSTEM,
                PromptInjection.InjectionPosition.valueOf("AFTER_SYSTEM"));
        assertEquals(PromptInjection.InjectionPosition.BEFORE_TASK,
                PromptInjection.InjectionPosition.valueOf("BEFORE_TASK"));
        assertEquals(PromptInjection.InjectionPosition.END,
                PromptInjection.InjectionPosition.valueOf("END"));
    }

    @Test
    void testInjectionTypes() {
        assertEquals(PromptInjection.InjectionType.ADVICE,
                PromptInjection.InjectionType.valueOf("ADVICE"));
        assertEquals(PromptInjection.InjectionType.CONTEXT,
                PromptInjection.InjectionType.valueOf("CONTEXT"));
        assertEquals(PromptInjection.InjectionType.ROUTING,
                PromptInjection.InjectionType.valueOf("ROUTING"));
        assertEquals(PromptInjection.InjectionType.ERROR_HANDLING,
                PromptInjection.InjectionType.valueOf("ERROR_HANDLING"));
    }

    @Test
    void testPriorityOrdering() {
        PromptInjection high = PromptInjection.builder()
                .id("high")
                .priority(100)
                .build();

        PromptInjection low = PromptInjection.builder()
                .id("low")
                .priority(10)
                .build();

        assertTrue(high.getPriority() > low.getPriority());
    }
}
