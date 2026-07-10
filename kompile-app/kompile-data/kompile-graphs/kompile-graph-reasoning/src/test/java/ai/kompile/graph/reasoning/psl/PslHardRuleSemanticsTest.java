/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PslHardRuleSemanticsTest {

    @Test
    @DisplayName("PslRule.weighted(+Infinity) is normalized to a hard rule")
    void infiniteWeightedLogicalRuleIsHard() {
        PslRule finite = PslRule.weighted(2.0, true,
                List.of(PslAtom.parse("A(X)")),
                List.of(PslAtom.parse("B(X)")));
        assertFalse(finite.hard(), "Finite weighted rules must remain soft");

        PslRule infinite = PslRule.weighted(Double.POSITIVE_INFINITY, false,
                List.of(PslAtom.parse("A(X)")),
                List.of(PslAtom.parse("B(X)")));
        assertTrue(infinite.hard(), "Infinite weighted rules must be hard");
        assertTrue(infinite.squared(), "Hard rules use the squared hard-penalty path");
    }

    @Test
    @DisplayName("Infinite weighted logical rules ground as hard and report hard violations")
    void infiniteWeightedLogicalRuleReportsHardViolation() {
        PslRule rule = PslRule.weighted(Double.POSITIVE_INFINITY, true,
                List.of(PslAtom.parse("A(X)")),
                List.of(PslAtom.parse("B(X)")));

        PslProgram program = new PslProgram()
                .observe("A", 1.0, "x")
                .observe("B", 0.0, "x")
                .addRule(rule);

        List<GroundRule> groundRules = program.ground();
        assertTrue(groundRules.stream().allMatch(GroundRule::hard),
                "Ground rules produced from an infinite weighted rule must be hard");

        HlMrfMapInference.Result result = new AdmmHlMrfInference().solve(program, groundRules);
        assertFalse(result.hardViolations().isEmpty(),
                "Pinned observed violation of an infinite weighted rule must be reported as hard");
    }

    @Test
    @DisplayName("ArithmeticRule.weighted(+Infinity) is normalized to hard")
    void infiniteWeightedArithmeticRuleIsHard() {
        ArithmeticRule rule = ArithmeticRule.weighted(
                Double.POSITIVE_INFINITY,
                false,
                List.of(new ArithmeticRule.ArithmeticTerm("A", List.of(Term.var("X")), 1.0, false)),
                List.of(new ArithmeticRule.ArithmeticTerm(null, List.of(), 1.0, false)),
                RelOp.LEQ);

        assertTrue(rule.hard(), "Infinite weighted arithmetic rules must be hard");
        assertTrue(rule.squared(), "Hard arithmetic rules use the squared hard-penalty path");
    }
}
