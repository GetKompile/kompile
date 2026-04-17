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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptCondition.
 */
class PromptConditionTest {

    @Test
    void testStateCondition() {
        PromptCondition condition = PromptCondition.forState("FAILED");

        assertTrue(condition.evaluate("FAILED", null, null, null, null, null, 0));
        assertFalse(condition.evaluate("EXECUTING", null, null, null, null, null, 0));
    }

    @Test
    void testPreviousStateCondition() {
        PromptCondition condition = PromptCondition.fromState("EXECUTING");

        assertTrue(condition.evaluate("FAILED", "EXECUTING", null, null, null, null, 0));
        assertFalse(condition.evaluate("FAILED", "WAITING", null, null, null, null, 0));
    }

    @Test
    void testExitCodeCondition() {
        PromptCondition condition = PromptCondition.exitCode(0);

        assertTrue(condition.evaluate(null, null, null, null, 0, null, 0));
        assertFalse(condition.evaluate(null, null, null, null, 1, null, 0));
    }

    @Test
    void testExitCodeNonZero() {
        PromptCondition condition = PromptCondition.exitCodeNonZero();

        assertFalse(condition.evaluate(null, null, null, null, 0, null, 0));
        assertTrue(condition.evaluate(null, null, null, null, 1, null, 0));
        assertTrue(condition.evaluate(null, null, null, null, 139, null, 0));
    }

    @Test
    void testErrorPatternCondition() {
        PromptCondition condition = PromptCondition.matchesError("\\[ERROR\\].*\\.java:\\[\\d+");

        assertTrue(condition.evaluate(null, null, null,
                "[ERROR] Test.java:[10,5] error", null, null, 0));
        assertFalse(condition.evaluate(null, null, null,
                "[INFO] Build successful", null, null, 0));
    }

    @Test
    void testContextCondition() {
        PromptCondition condition = PromptCondition.contextEquals("status", "ready");

        Map<String, Object> context = new HashMap<>();
        context.put("status", "ready");

        assertTrue(condition.evaluate(null, null, context, null, null, null, 0));

        context.put("status", "pending");
        assertFalse(condition.evaluate(null, null, context, null, null, null, 0));
    }

    @Test
    void testClassificationCondition() {
        PromptCondition condition = PromptCondition.classification("COMPILATION_ERROR");

        assertTrue(condition.evaluate(null, null, null, null, null, "COMPILATION_ERROR", 0));
        assertFalse(condition.evaluate(null, null, null, null, null, "RUNTIME_ERROR", 0));
    }

    @Test
    void testAlwaysCondition() {
        PromptCondition condition = PromptCondition.always();

        assertTrue(condition.evaluate(null, null, null, null, null, null, 0));
        assertTrue(condition.evaluate("ANY", "STATE", new HashMap<>(), "output", 1, "classification", 5));
    }

    @Test
    void testAndCondition() {
        PromptCondition condition = PromptCondition.and(
                PromptCondition.forState("FAILED"),
                PromptCondition.exitCodeNonZero()
        );

        assertTrue(condition.evaluate("FAILED", null, null, null, 1, null, 0));
        assertFalse(condition.evaluate("FAILED", null, null, null, 0, null, 0));
        assertFalse(condition.evaluate("EXECUTING", null, null, null, 1, null, 0));
    }

    @Test
    void testOrCondition() {
        PromptCondition condition = PromptCondition.or(
                PromptCondition.matchesError("timeout"),
                PromptCondition.matchesError("connection refused")
        );

        assertTrue(condition.evaluate(null, null, null, "Connection timeout occurred", null, null, 0));
        assertTrue(condition.evaluate(null, null, null, "connection refused", null, null, 0));
        assertFalse(condition.evaluate(null, null, null, "Build successful", null, null, 0));
    }

    @Test
    void testContainsOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.STATE)
                .operator(PromptCondition.ConditionOperator.CONTAINS)
                .value("FAIL")
                .build();

        assertTrue(condition.evaluate("FAILED", null, null, null, null, null, 0));
        assertTrue(condition.evaluate("FAILURE", null, null, null, null, null, 0));
        assertFalse(condition.evaluate("SUCCESS", null, null, null, null, null, 0));
    }

    @Test
    void testInOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.STATE)
                .operator(PromptCondition.ConditionOperator.IN)
                .values(List.of("FAILED", "ERROR", "TIMEOUT"))
                .build();

        assertTrue(condition.evaluate("FAILED", null, null, null, null, null, 0));
        assertTrue(condition.evaluate("ERROR", null, null, null, null, null, 0));
        assertFalse(condition.evaluate("SUCCESS", null, null, null, null, null, 0));
    }

    @Test
    void testNotInOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.STATE)
                .operator(PromptCondition.ConditionOperator.NOT_IN)
                .values(List.of("FAILED", "ERROR"))
                .build();

        assertFalse(condition.evaluate("FAILED", null, null, null, null, null, 0));
        assertTrue(condition.evaluate("SUCCESS", null, null, null, null, null, 0));
    }

    @Test
    void testGreaterThanOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.RETRY_COUNT)
                .operator(PromptCondition.ConditionOperator.GREATER_THAN)
                .value("3")
                .build();

        assertTrue(condition.evaluate(null, null, null, null, null, null, 4));
        assertTrue(condition.evaluate(null, null, null, null, null, null, 5));
        assertFalse(condition.evaluate(null, null, null, null, null, null, 3));
        assertFalse(condition.evaluate(null, null, null, null, null, null, 2));
    }

    @Test
    void testLessThanOrEqualOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.EXIT_CODE)
                .operator(PromptCondition.ConditionOperator.LESS_OR_EQUAL)
                .value("1")
                .build();

        assertTrue(condition.evaluate(null, null, null, null, 0, null, 0));
        assertTrue(condition.evaluate(null, null, null, null, 1, null, 0));
        assertFalse(condition.evaluate(null, null, null, null, 2, null, 0));
    }

    @Test
    void testExistsOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.CONTEXT)
                .field("errorMessage")
                .operator(PromptCondition.ConditionOperator.EXISTS)
                .build();

        Map<String, Object> context = new HashMap<>();
        context.put("errorMessage", "Some error");
        assertTrue(condition.evaluate(null, null, context, null, null, null, 0));

        context.put("errorMessage", null);
        assertFalse(condition.evaluate(null, null, context, null, null, null, 0));

        context.remove("errorMessage");
        assertFalse(condition.evaluate(null, null, context, null, null, null, 0));
    }

    @Test
    void testNotExistsOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.CONTEXT)
                .field("errorMessage")
                .operator(PromptCondition.ConditionOperator.NOT_EXISTS)
                .build();

        Map<String, Object> context = new HashMap<>();
        assertTrue(condition.evaluate(null, null, context, null, null, null, 0));

        context.put("errorMessage", "Some error");
        assertFalse(condition.evaluate(null, null, context, null, null, null, 0));
    }

    @Test
    void testStartsWithOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.STATE)
                .operator(PromptCondition.ConditionOperator.STARTS_WITH)
                .value("WAIT")
                .build();

        assertTrue(condition.evaluate("WAITING", null, null, null, null, null, 0));
        assertTrue(condition.evaluate("WAITING_APPROVAL", null, null, null, null, null, 0));
        assertFalse(condition.evaluate("EXECUTING", null, null, null, null, null, 0));
    }

    @Test
    void testEndsWithOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.STATE)
                .operator(PromptCondition.ConditionOperator.ENDS_WITH)
                .value("_ERROR")
                .build();

        assertTrue(condition.evaluate("COMPILATION_ERROR", null, null, null, null, null, 0));
        assertTrue(condition.evaluate("RUNTIME_ERROR", null, null, null, null, null, 0));
        assertFalse(condition.evaluate("SUCCESS", null, null, null, null, null, 0));
    }

    @Test
    void testMatchesOperator() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.ERROR_PATTERN)
                .pattern("(?i)error|fail|exception")
                .build();

        assertTrue(condition.evaluate(null, null, null, "Error occurred", null, null, 0));
        assertTrue(condition.evaluate(null, null, null, "Test FAILED", null, null, 0));
        assertTrue(condition.evaluate(null, null, null, "NullPointerException", null, null, 0));
        assertFalse(condition.evaluate(null, null, null, "Build successful", null, null, 0));
    }

    @Test
    void testNullValues() {
        PromptCondition condition = PromptCondition.forState("FAILED");

        assertFalse(condition.evaluate(null, null, null, null, null, null, 0));
    }

    @Test
    void testNestedCompositeConditions() {
        PromptCondition condition = PromptCondition.and(
                PromptCondition.forState("FAILED"),
                PromptCondition.or(
                        PromptCondition.matchesError("compilation"),
                        PromptCondition.matchesError("syntax error")
                )
        );

        assertTrue(condition.evaluate("FAILED", null, null, "compilation failed", null, null, 0));
        assertTrue(condition.evaluate("FAILED", null, null, "syntax error found", null, null, 0));
        assertFalse(condition.evaluate("FAILED", null, null, "runtime error", null, null, 0));
        assertFalse(condition.evaluate("SUCCESS", null, null, "compilation failed", null, null, 0));
    }

    @Test
    void testContextWithNumericValue() {
        PromptCondition condition = PromptCondition.builder()
                .type(PromptCondition.ConditionType.CONTEXT)
                .field("retries")
                .operator(PromptCondition.ConditionOperator.GREATER_THAN)
                .value("2")
                .build();

        Map<String, Object> context = new HashMap<>();
        context.put("retries", 3);

        assertTrue(condition.evaluate(null, null, context, null, null, null, 0));

        context.put("retries", 1);
        assertFalse(condition.evaluate(null, null, context, null, null, null, 0));
    }
}
