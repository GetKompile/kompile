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

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Step 4 — External Function Predicates (Gap E-5).
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link ExternalFunction} interface and lambda registration</li>
 *   <li>{@link BuiltinSimilarityFunctions}: Jaccard, Jaro-Winkler, Cosine-binary, Normalised-edit</li>
 *   <li>{@link PslProgram#registerFunction}: functions evaluated at grounding time</li>
 *   <li>Zero-valued function atoms are skipped (open-world default)</li>
 *   <li>A similarity-driven rule: entities with high label similarity infer SameAs</li>
 * </ul>
 */
@DisplayName("Step 4 — External Function Predicates")
class ExternalFunctionTest {

    // ─── BuiltinSimilarityFunctions ──────────────────────────────────────────

    @Nested
    @DisplayName("BuiltinSimilarityFunctions")
    class BuiltinTests {

        @Test
        @DisplayName("Jaccard: identical token sets → 1.0")
        void jaccardIdentical() {
            assertEquals(1.0, BuiltinSimilarityFunctions.JACCARD.evaluate("cat dog", "cat dog"), 1e-9);
        }

        @Test
        @DisplayName("Jaccard: disjoint sets → 0.0")
        void jaccardDisjoint() {
            assertEquals(0.0, BuiltinSimilarityFunctions.JACCARD.evaluate("cat", "dog"), 1e-9);
        }

        @Test
        @DisplayName("Jaccard: partial overlap = |∩|/|∪|")
        void jaccardPartialOverlap() {
            // {cat, dog} ∩ {dog, fish} = {dog}, ∪ = {cat, dog, fish} → 1/3
            double j = BuiltinSimilarityFunctions.JACCARD.evaluate("cat dog", "dog fish");
            assertEquals(1.0 / 3.0, j, 1e-9);
        }

        @Test
        @DisplayName("Jaccard: both empty → 0.0")
        void jaccardBothEmpty() {
            assertEquals(0.0, BuiltinSimilarityFunctions.JACCARD.evaluate("", ""), 1e-9);
        }

        @Test
        @DisplayName("Jaccard: wrong arity throws")
        void jaccardWrongArity() {
            assertThrows(IllegalArgumentException.class,
                    () -> BuiltinSimilarityFunctions.JACCARD.evaluate("a"));
        }

        @Test
        @DisplayName("JaroWinkler: equal strings → 1.0")
        void jaroWinklerEqual() {
            assertEquals(1.0, BuiltinSimilarityFunctions.JARO_WINKLER.evaluate("hello", "hello"), 1e-9);
        }

        @Test
        @DisplayName("JaroWinkler: completely different strings → low similarity")
        void jaroWinklerDifferent() {
            double sim = BuiltinSimilarityFunctions.JARO_WINKLER.evaluate("abc", "xyz");
            assertTrue(sim < 0.5, "Very different strings should have low Jaro-Winkler: " + sim);
        }

        @Test
        @DisplayName("JaroWinkler: prefix bonus applies")
        void jaroWinklerPrefixBonus() {
            // "MARTHA" vs "MARHTA" — classic Jaro-Winkler example
            double sim = BuiltinSimilarityFunctions.JARO_WINKLER.evaluate("MARTHA", "MARHTA");
            assertTrue(sim > 0.9, "MARTHA/MARHTA should be >0.9 similar, got " + sim);
        }

        @Test
        @DisplayName("JaroWinkler: empty strings → 0.0")
        void jaroWinklerEmpty() {
            assertEquals(0.0, BuiltinSimilarityFunctions.JARO_WINKLER.evaluate("hello", ""), 1e-9);
        }

        @Test
        @DisplayName("CosineBinary (Dice): identical sets → 1.0")
        void cosineBinaryIdentical() {
            assertEquals(1.0, BuiltinSimilarityFunctions.COSINE_BINARY.evaluate("a b c", "a b c"), 1e-9);
        }

        @Test
        @DisplayName("CosineBinary: disjoint → 0.0")
        void cosineBinaryDisjoint() {
            assertEquals(0.0, BuiltinSimilarityFunctions.COSINE_BINARY.evaluate("a b", "c d"), 1e-9);
        }

        @Test
        @DisplayName("CosineBinary: partial = 2|∩|/(|A|+|B|) = Dice")
        void cosineBinaryPartial() {
            // {a,b} ∩ {b,c} = {b}; Dice = 2*1/(2+2) = 0.5
            assertEquals(0.5, BuiltinSimilarityFunctions.COSINE_BINARY.evaluate("a b", "b c"), 1e-9);
        }

        @Test
        @DisplayName("NormalisedEdit: identical strings → 1.0")
        void normalizedEditIdentical() {
            assertEquals(1.0, BuiltinSimilarityFunctions.NORMALIZED_EDIT.evaluate("hello", "hello"), 1e-9);
        }

        @Test
        @DisplayName("NormalisedEdit: single char change → high similarity")
        void normalizedEditOneChange() {
            // "kitten" vs "sitten" → edit distance 1, max length 6 → similarity = 1 - 1/6 ≈ 0.833
            double sim = BuiltinSimilarityFunctions.NORMALIZED_EDIT.evaluate("kitten", "sitten");
            assertEquals(1.0 - 1.0 / 6.0, sim, 1e-9);
        }

        @Test
        @DisplayName("NormalisedEdit: completely different → low similarity")
        void normalizedEditDifferent() {
            double sim = BuiltinSimilarityFunctions.NORMALIZED_EDIT.evaluate("abc", "xyz");
            assertTrue(sim < 0.5);
        }

        @Test
        @DisplayName("NormalisedEdit: both empty strings → 0.0")
        void normalizedEditBothEmpty() {
            assertEquals(0.0, BuiltinSimilarityFunctions.NORMALIZED_EDIT.evaluate("", ""), 1e-9);
        }

        @Test
        @DisplayName("All builtins return values in [0,1]")
        void allBuiltinsInRange() {
            String[] s1 = {"hello world", "cat dog bird", "ABCDEF", "abc"};
            String[] s2 = {"helo world",  "dog fish cat",  "ABCXYZ", "xyz"};
            ExternalFunction[] fns = {
                BuiltinSimilarityFunctions.JACCARD,
                BuiltinSimilarityFunctions.JARO_WINKLER,
                BuiltinSimilarityFunctions.COSINE_BINARY,
                BuiltinSimilarityFunctions.NORMALIZED_EDIT,
            };
            for (ExternalFunction fn : fns) {
                for (int i = 0; i < s1.length; i++) {
                    double v = fn.evaluate(s1[i], s2[i]);
                    assertTrue(v >= 0.0 && v <= 1.0,
                            fn + ".evaluate(" + s1[i] + ", " + s2[i] + ")=" + v + " out of [0,1]");
                }
            }
        }
    }

    // ─── External function grounding ──────────────────────────────────────────

    @Nested
    @DisplayName("PslProgram.registerFunction — grounding")
    class FunctionGroundingTests {

        @Test
        @DisplayName("Function predicate evaluates and registers non-zero atom")
        void functionAtomRegisteredWhenNonZero() {
            // Always-true function: LabelSim(A, B) = 1.0
            ExternalFunction alwaysOne = args -> 1.0;

            PslProgram prog = new PslProgram()
                    .registerFunction("LabelSim", alwaysOne)
                    .observe("Entity", 1.0, "e1")
                    .observe("Entity", 1.0, "e2")
                    .target("SameAs", "e1", "e2")
                    .addRule("1.0: LabelSim(A, B) & Entity(A) & Entity(B) -> SameAs(A, B) ^2");

            List<GroundRule> ground = prog.ground();
            // LabelSim should have been evaluated and registered, enabling grounding
            assertFalse(ground.isEmpty(), "Function atom should enable grounding");
        }

        @Test
        @DisplayName("Function atom with value 0 suppresses grounding branch")
        void zeroFunctionAtomSuppressesBranch() {
            // Always-zero function: LabelSim(A, B) = 0.0 → atom skipped
            ExternalFunction alwaysZero = args -> 0.0;

            PslProgram prog = new PslProgram()
                    .registerFunction("LabelSim", alwaysZero)
                    .observe("Entity", 1.0, "e1")
                    .observe("Entity", 1.0, "e2")
                    .target("SameAs", "e1", "e2")
                    .addRule("1.0: LabelSim(A, B) & Entity(A) & Entity(B) -> SameAs(A, B) ^2");

            List<GroundRule> ground = prog.ground();
            // LabelSim=0 → no function atoms registered → no ground rules
            assertEquals(0, ground.size(), "Zero function atom should suppress grounding");
        }

        @Test
        @DisplayName("Similarity-driven rule: high similarity infers high SameAs")
        void similarityDrivenRule() {
            // Set up a similarity function that returns 0.9 for "alice"/"alicia" pair
            Map<String, Double> labels = Map.of("e1", 0.0, "e2", 0.0); // not used, just entities
            ExternalFunction simFn = args -> {
                if (args[0].equals("e1") && args[1].equals("e2")) return 0.9;
                if (args[0].equals("e2") && args[1].equals("e1")) return 0.9;
                return 0.0;
            };

            PslProgram prog = new PslProgram()
                    .registerFunction("NameSim", simFn)
                    .observe("HasName", 1.0, "e1")
                    .observe("HasName", 1.0, "e2")
                    .target("SameAs", "e1", "e2")
                    .addRule("2.0: NameSim(A, B) & HasName(A) & HasName(B) -> SameAs(A, B) ^2");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double sameAs = result.values().getOrDefault("SameAs(e1, e2)", 0.0);
            // With NameSim=0.9 (high) and a weight-2 rule, SameAs should be pushed high
            assertTrue(sameAs > 0.5, "High similarity should infer high SameAs, got " + sameAs);
        }

        @Test
        @DisplayName("Jaccard function predicate correctly wired into grounding")
        void jaccardFunctionPredicate() {
            // Use Jaccard as an external function predicate
            PslProgram prog = new PslProgram()
                    .registerFunction("TagSim", BuiltinSimilarityFunctions.JACCARD)
                    .observe("Entity", 1.0, "e1")
                    .observe("Entity", 1.0, "e2")
                    .target("Related", "e1", "e2")
                    // The function takes the entity IDs but we return a fixed value for brevity
                    .addRule("1.0: TagSim(A, B) & Entity(A) & Entity(B) -> Related(A, B) ^2");

            // TagSim("e1", "e2") = Jaccard of two entity ID strings = 0 for different strings
            // → no grounding expected unless the strings share tokens
            List<GroundRule> ground = prog.ground();
            // "e1" and "e2" have no common tokens → Jaccard=0 → suppressed
            assertEquals(0, ground.size(),
                    "Jaccard of different single-token strings should be 0, suppressing grounding");
        }

        @Test
        @DisplayName("isFunction() returns true for registered functions")
        void isFunctionQuery() {
            PslProgram prog = new PslProgram()
                    .registerFunction("MySim", args -> 0.5);
            assertTrue(prog.isFunction("MySim"));
            assertFalse(prog.isFunction("NotAFunction"));
        }
    }
}
