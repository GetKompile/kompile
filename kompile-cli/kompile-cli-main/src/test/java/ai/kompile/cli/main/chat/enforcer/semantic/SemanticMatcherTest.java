/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticMatcherTest {

    @Test
    void wordNetMatcher_expandsSingleWord() {
        WordNetMatcher matcher = new WordNetMatcher();
        assertTrue(matcher.isAvailable());

        List<String> expanded = matcher.expand("existing");
        assertTrue(expanded.contains("existing"));
        assertTrue(expanded.contains("present"));
        assertTrue(expanded.contains("current"));
    }

    @Test
    void wordNetMatcher_expandsMultiWordPhrase() {
        WordNetMatcher matcher = new WordNetMatcher();
        List<String> expanded = matcher.expand("pre-existing");
        assertFalse(expanded.isEmpty());
        // Should contain the original
        assertTrue(expanded.contains("pre-existing"));
        // Should contain expanded combinations
        assertTrue(expanded.stream().anyMatch(e -> e.contains("prior")));
        assertTrue(expanded.stream().anyMatch(e -> e.contains("previous")));
    }

    @Test
    void wordNetMatcher_matchesExpandedCombination() {
        WordNetMatcher matcher = new WordNetMatcher();

        // "prior existing" and "previous present" are synonym expansions of "pre-existing"
        // "pre" → "prior", "existing" stays → "prior existing"
        SemanticMatch match = matcher.matches("This is a prior existing issue in the system", "pre-existing");
        assertNotNull(match);
        assertEquals("pre-existing", match.concept());
        assertEquals("wordnet", match.matcherType());
    }

    @Test
    void wordNetMatcher_matchesPreviousVariant() {
        WordNetMatcher matcher = new WordNetMatcher();

        // Structural variant: "previously existing" via addStructuralVariants
        SemanticMatch match = matcher.matches("This was previously existing before the change", "pre-existing");
        assertNotNull(match);
    }

    @Test
    void wordNetMatcher_matchesStructuralVariant() {
        WordNetMatcher matcher = new WordNetMatcher();

        // Structural variants: "existing before", "prior existing", "already existing"
        SemanticMatch match1 = matcher.matches("This issue was existing before the change", "pre-existing");
        assertNotNull(match1);

        SemanticMatch match2 = matcher.matches("This was already existing in the codebase", "pre-existing");
        assertNotNull(match2);
    }

    @Test
    void wordNetMatcher_noFalsePositiveOnUnrelatedText() {
        WordNetMatcher matcher = new WordNetMatcher();

        SemanticMatch match = matcher.matches("The weather is nice today", "pre-existing");
        assertNull(match);
    }

    @Test
    void wordNetMatcher_catchesDeflectionPatterns() {
        WordNetMatcher matcher = new WordNetMatcher();

        // "environmental" → "infrastructure", "external" etc.
        List<String> expanded = matcher.expand("environmental");
        assertTrue(expanded.contains("infrastructure"));
        assertTrue(expanded.contains("external"));
    }

    @Test
    void wordNetMatcher_matchesLegacySynonyms() {
        WordNetMatcher matcher = new WordNetMatcher();

        // "legacy" → "inherited", "historical", "pre-existing" etc.
        SemanticMatch match = matcher.matches("This is inherited code from years ago", "legacy");
        assertNotNull(match);
        assertEquals("legacy", match.concept());
    }

    @Test
    void wordNetMatcher_wordBoundaryForShortWords() {
        WordNetMatcher matcher = new WordNetMatcher();

        // "pre" is a short word — should use word boundaries
        List<String> expanded = matcher.expand("pre");
        assertTrue(expanded.contains("pre"));
        assertTrue(expanded.contains("prior"));

        // Should NOT match "presence" (contains "pre" but not at word boundary)
        SemanticMatch match = matcher.matchesWithExpansion("The presence of errors", "pre", expanded);
        // "pre" is in expanded, but word boundary check should prevent matching inside "presence"
        // Note: longer variants like "prior" won't match either since text doesn't contain them
        assertNull(match);
    }

    @Test
    void wordNetMatcher_nullAndEmptyHandling() {
        WordNetMatcher matcher = new WordNetMatcher();

        assertEquals(List.of(), matcher.expand(null));
        assertEquals(List.of(), matcher.expand(""));
        assertEquals(List.of(), matcher.expand("   "));
        assertNull(matcher.matches(null, "test"));
        assertNull(matcher.matches("", "test"));
    }

    @Test
    void compositeSemanticMatcher_wordnetOnly() {
        CompositeSemanticMatcher matcher = CompositeSemanticMatcher.create("wordnet", null, 0.78);
        assertTrue(matcher.isAvailable());
        assertEquals("wordnet", matcher.matcherType());

        // "existing before" is a structural variant expansion of "pre-existing"
        SemanticMatch match = matcher.matches("This issue was existing before the migration", "pre-existing");
        assertNotNull(match);
    }

    @Test
    void compositeSemanticMatcher_noneMode() {
        CompositeSemanticMatcher matcher = CompositeSemanticMatcher.create("none", null, 0.78);
        assertFalse(matcher.isAvailable());
        assertEquals("none", matcher.matcherType());
    }

    @Test
    void compositeSemanticMatcher_embeddingUnavailableGraceful() {
        // Embedding endpoint doesn't exist — should be unavailable but not throw
        CompositeSemanticMatcher matcher = CompositeSemanticMatcher.create("embedding", "http://localhost:99999/embed", 0.78);
        // May or may not be available depending on if port is open, but should not throw
        assertNotNull(matcher);
    }

    @Test
    void compositeSemanticMatcher_bothMode() {
        CompositeSemanticMatcher matcher = CompositeSemanticMatcher.create("both", null, 0.78);
        // WordNet should be available, embedding will be unavailable (no URL)
        assertTrue(matcher.isAvailable());
        assertTrue(matcher.matcherType().contains("wordnet"));
    }

    @Test
    void semanticMatch_describeExact() {
        SemanticMatch match = new SemanticMatch("pre-existing", "prior condition", 1.0, "wordnet");
        assertTrue(match.isExact());
        assertTrue(match.describe().contains("exact match"));
        assertTrue(match.describe().contains("prior condition"));
    }

    @Test
    void semanticMatch_describeFuzzy() {
        SemanticMatch match = new SemanticMatch("pre-existing", "previously known issue", 0.85, "embedding");
        assertFalse(match.isExact());
        assertTrue(match.describe().contains("semantic match"));
        assertTrue(match.describe().contains("85%"));
    }

    @Test
    void wordNetMatcher_dictionarySize() {
        WordNetMatcher matcher = new WordNetMatcher();
        assertTrue(matcher.dictionarySize() > 0);
    }

    @Test
    void keywordEvaluator_withSemantics_catchesRewordedViolation() {
        // Integration: KeywordEnforcerEvaluator + semantic matching
        var config = new ai.kompile.cli.main.chat.enforcer.EnforcerConfig();
        config.setSemanticMode("wordnet");
        config.setBannedKeywords(List.of("pre-existing"));
        config.setKeywordMode(true);

        var rules = List.of(
                new ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator.KeywordRule(
                        "pre-existing", false, false,
                        "Banned: pre-existing", "error", "output")
        );

        var evaluator = ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator.withSemantics(
                rules, "BAN: pre-existing", config);

        assertNotNull(evaluator.getSemanticMatcher());
        assertTrue(evaluator.getSemanticMatcher().isAvailable());

        // Direct keyword match
        var policy = new ai.kompile.cli.main.chat.enforcer.EnforcerPolicy("BAN: pre-existing", 2, false);
        var decision1 = evaluator.evaluate("fix this", "This is a pre-existing bug", policy, 1);
        assertFalse(decision1.isCompliant());

        // Reworded via synonym — caught by semantic matcher
        // "already existing" is a structural variant of "pre-existing"
        var decision2 = evaluator.evaluate("fix this", "This bug was already existing before we started the project", policy, 1);
        assertFalse(decision2.isCompliant());
    }

    @Test
    void keywordEvaluator_withSemantics_noFalsePositive() {
        var config = new ai.kompile.cli.main.chat.enforcer.EnforcerConfig();
        config.setSemanticMode("wordnet");

        var rules = List.of(
                new ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator.KeywordRule(
                        "pre-existing", false, false,
                        "Banned: pre-existing", "error", "output")
        );

        var evaluator = ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator.withSemantics(
                rules, "BAN: pre-existing", config);

        var policy = new ai.kompile.cli.main.chat.enforcer.EnforcerPolicy("BAN: pre-existing", 2, false);
        var decision = evaluator.evaluate("fix this", "I've fixed the compilation error in the test class.", policy, 1);
        assertTrue(decision.isCompliant());
    }
}
