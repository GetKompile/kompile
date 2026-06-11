package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffPatternEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SAMPLE_DIFF = """
            diff --git a/src/Main.java b/src/Main.java
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -10,6 +10,9 @@ public class Main {
                 public static void main(String[] args) {
                     System.out.println("Hello");
            +        System.exit(1);
            +        Runtime.getRuntime().exec("rm -rf /");
            +        String password = "secret123";
                 }
             }
            """;

    private static final String MULTI_FILE_DIFF = """
            diff --git a/src/App.ts b/src/App.ts
            --- a/src/App.ts
            +++ b/src/App.ts
            @@ -1,4 +1,6 @@
             import React from 'react';
            +import { eval } from 'some-lib';
            +const x = eval("code");

             export default function App() {
            diff --git a/src/utils.java b/src/utils.java
            --- a/src/utils.java
            +++ b/src/utils.java
            @@ -5,3 +5,5 @@ class Utils {
                 void process() {
            +        try { doStuff(); } catch (Exception e) {}
            +        var date = new java.util.Date();
                 }
            """;

    @Test
    void detectsLiteralPatternInDiff() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: System.exit(", objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(1, eval.ruleCount());

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertFalse(result.passed());
        assertEquals(1, result.violations().size());
        assertEquals("src/Main.java", result.violations().get(0).filePath());
        assertTrue(result.violations().get(0).matchedLine().contains("System.exit"));
    }

    @Test
    void detectsRegexPatternInDiff() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF_REGEX: password\\s*=\\s*\"[^\"]+\"", objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertFalse(result.passed());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).matchedLine().contains("password"));
    }

    @Test
    void passesWhenNoViolations() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: Thread.sleep(", objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertTrue(result.passed());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void multipleRulesMultipleViolations() {
        String rules = """
                BAN_DIFF: System.exit(
                BAN_DIFF: Runtime.getRuntime().exec(
                BAN_DIFF_REGEX: password\\s*=\\s*"[^"]+"
                """;
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(rules, objectMapper);
        assertEquals(3, eval.ruleCount());

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertFalse(result.passed());
        assertEquals(3, result.violations().size());
    }

    @Test
    void criticalSeveritySetsShouldStop() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "STOP_DIFF: System.exit(", objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertFalse(result.passed());
        assertTrue(result.shouldStop());
    }

    @Test
    void multiFileDiffParsesCorrectly() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: eval(", objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(MULTI_FILE_DIFF);
        assertFalse(result.passed());
        // Should find it in App.ts
        assertEquals("src/App.ts", result.violations().get(0).filePath());
    }

    @Test
    void jsonFormatParsesCorrectly() {
        String json = """
                [
                  {"pattern": "System.exit", "regex": false, "description": "Use exceptions instead", "severity": "error"},
                  {"pattern": "Runtime\\\\.getRuntime\\\\(\\\\)\\\\.exec", "regex": true, "description": "No shell exec", "severity": "critical"}
                ]
                """;
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(json, objectMapper);
        assertTrue(eval.isAvailable());
        assertEquals(2, eval.ruleCount());
    }

    @Test
    void jsonWithFileGlobFiltersCorrectly() {
        String json = """
                [
                  {"pattern": "eval(", "regex": false, "description": "No eval", "severity": "error", "fileGlob": "*.ts"}
                ]
                """;
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(json, objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(MULTI_FILE_DIFF);
        assertFalse(result.passed());
        // Only the .ts file should match
        for (DiffPatternEvaluator.DiffViolation v : result.violations()) {
            assertTrue(v.filePath().endsWith(".ts"), "Should only match .ts files: " + v.filePath());
        }
    }

    @Test
    void emptyDiffPasses() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: System.exit(", objectMapper);

        assertTrue(eval.evaluate("").passed());
        assertTrue(eval.evaluate(null).passed());
    }

    @Test
    void correctionPromptContainsViolationDetails() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: System.exit(", objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertNotNull(result.correctionPrompt());
        assertTrue(result.correctionPrompt().contains("System.exit"));
        assertTrue(result.correctionPrompt().contains("src/Main.java"));
        assertTrue(result.correctionPrompt().contains("STOP"));
        assertTrue(result.correctionPrompt().contains("Re-Comply"));
    }

    @Test
    void onlyChecksAddedLinesNotRemovedLines() {
        String diffWithRemoval = """
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -10,4 +10,4 @@ public class Main {
                -        System.exit(0);
                +        throw new RuntimeException("done");
                 }
                """;
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: System.exit(", objectMapper);

        // System.exit only appears on a removed line, should pass
        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(diffWithRemoval);
        assertTrue(result.passed());
    }

    @Test
    void commentsAndBlankLinesIgnoredInRuleFile() {
        String rules = """
                # This is a comment
                // This is also a comment

                BAN_DIFF: System.exit(
                # Another comment
                BAN_DIFF: Runtime.exec(
                """;
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(rules, objectMapper);
        assertEquals(2, eval.ruleCount());
    }

    @Test
    void fromKeywordRulesExtractsDiffScopeOnly() {
        List<KeywordEnforcerEvaluator.KeywordRule> allRules = List.of(
                new KeywordEnforcerEvaluator.KeywordRule("bash", false, false, "no bash", "error", "tool"),
                new KeywordEnforcerEvaluator.KeywordRule("System.exit", false, false, "no exit", "error", "diff"),
                new KeywordEnforcerEvaluator.KeywordRule("badword", false, false, "no bad", "error", "output")
        );

        DiffPatternEvaluator eval = DiffPatternEvaluator.fromKeywordRules(allRules, "");
        assertEquals(1, eval.ruleCount());
        assertEquals("System.exit", eval.getRules().get(0).getPattern());
    }

    @Test
    void bootstrapPromptContainsLanguageAndDescription() {
        String prompt = DiffPatternEvaluator.buildBootstrapPrompt(
                "No System.exit, no hardcoded passwords, no eval()", "java");

        assertTrue(prompt.contains("java"));
        assertTrue(prompt.contains("No System.exit"));
        assertTrue(prompt.contains("JSON array"));
        assertTrue(prompt.contains("pattern"));
    }

    @Test
    void parseDiffExtractsCorrectLineNumbers() {
        List<DiffPatternEvaluator.DiffHunk> hunks = DiffPatternEvaluator.parseDiff(SAMPLE_DIFF);
        assertFalse(hunks.isEmpty());

        DiffPatternEvaluator.DiffHunk hunk = hunks.get(0);
        assertEquals("src/Main.java", hunk.filePath);
        // Added lines start at line 12 (hunk header says +10, context lines advance to 12)
        assertEquals(3, hunk.addedLines.size());
        assertTrue(hunk.addedLines.get(0).contains("System.exit"));
    }

    @Test
    void caseInsensitiveMatchByDefault() {
        DiffPatternEvaluator eval = DiffPatternEvaluator.fromText(
                "BAN_DIFF: system.exit(", objectMapper);

        DiffPatternEvaluator.DiffEvaluation result = eval.evaluate(SAMPLE_DIFF);
        assertFalse(result.passed(), "Should match case-insensitively by default");
    }
}
