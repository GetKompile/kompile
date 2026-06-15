package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CloneDetector}'s tokenization, MinHash, and
 * similarity estimation. These tests exercise the pure-function internals
 * without needing a database or filesystem.
 */
class CloneDetectorUnitTest {

    // =======================================================================
    // Token normalization
    // =======================================================================

    @Test
    void normalizeIdentifierBecomesPlaceholder() {
        assertEquals("$I", CloneDetector.normalizeToken("myVariable"));
        assertEquals("$I", CloneDetector.normalizeToken("SomeClass"));
        assertEquals("$I", CloneDetector.normalizeToken("_private"));
        assertEquals("$I", CloneDetector.normalizeToken("$special"));
    }

    @Test
    void normalizeKeywordPreserved() {
        assertEquals("if", CloneDetector.normalizeToken("if"));
        assertEquals("return", CloneDetector.normalizeToken("return"));
        assertEquals("class", CloneDetector.normalizeToken("class"));
        assertEquals("for", CloneDetector.normalizeToken("for"));
        assertEquals("def", CloneDetector.normalizeToken("def"));
        assertEquals("fn", CloneDetector.normalizeToken("fn"));
        assertEquals("func", CloneDetector.normalizeToken("func"));
    }

    @Test
    void normalizeStringBecomesPlaceholder() {
        assertEquals("$S", CloneDetector.normalizeToken("\"hello world\""));
        assertEquals("$S", CloneDetector.normalizeToken("'c'"));
        assertEquals("$S", CloneDetector.normalizeToken("`template`"));
    }

    @Test
    void normalizeNumberBecomesPlaceholder() {
        assertEquals("$N", CloneDetector.normalizeToken("42"));
        assertEquals("$N", CloneDetector.normalizeToken("3.14"));
        assertEquals("$N", CloneDetector.normalizeToken("0xFF"));
        assertEquals("$N", CloneDetector.normalizeToken("0b1010"));
    }

    @Test
    void normalizeOperatorsPreserved() {
        assertEquals("{", CloneDetector.normalizeToken("{"));
        assertEquals("}", CloneDetector.normalizeToken("}"));
        assertEquals("(", CloneDetector.normalizeToken("("));
        assertEquals(";", CloneDetector.normalizeToken(";"));
        assertEquals("=", CloneDetector.normalizeToken("="));
    }

    // =======================================================================
    // Tokenization
    // =======================================================================

    @Test
    void tokenizeJavaMethod() {
        String source = "public void process(String input) { return input.trim(); }";
        List<String> tokens = CloneDetector.tokenize(source);

        assertFalse(tokens.isEmpty());
        // Keywords should be preserved
        assertTrue(tokens.contains("public"));
        assertTrue(tokens.contains("void"));
        assertTrue(tokens.contains("return"));
        // Identifiers should be normalized
        assertTrue(tokens.contains("$I"));
        // Operators/delimiters present
        assertTrue(tokens.contains("("));
        assertTrue(tokens.contains(")"));
        assertTrue(tokens.contains("{"));
        assertTrue(tokens.contains("}"));
    }

    @Test
    void tokenizeReplacesAllIdentifiers() {
        // Two methods that differ only in names should produce identical tokens
        String methodA = "public int calculate(int x) { return x * 2; }";
        String methodB = "public int compute(int y) { return y * 2; }";

        List<String> tokensA = CloneDetector.tokenize(methodA);
        List<String> tokensB = CloneDetector.tokenize(methodB);

        assertEquals(tokensA, tokensB,
                "Methods differing only in identifiers should tokenize identically");
    }

    @Test
    void tokenizeReplacesStringsAndNumbers() {
        String codeA = "log(\"starting\", 100);";
        String codeB = "log(\"finished\", 999);";

        List<String> tokensA = CloneDetector.tokenize(codeA);
        List<String> tokensB = CloneDetector.tokenize(codeB);

        assertEquals(tokensA, tokensB,
                "Methods differing only in string/number literals should tokenize identically");
    }

    @Test
    void tokenizeEmptySourceReturnsEmpty() {
        assertTrue(CloneDetector.tokenize("").isEmpty());
        assertTrue(CloneDetector.tokenize("   ").isEmpty());
    }

    // =======================================================================
    // MinHash
    // =======================================================================

    @Test
    void computeMinHashReturnsSigOfCorrectLength() {
        List<String> tokens = CloneDetector.tokenize(
                "public void process(String input) { int x = 0; x++; return; }");
        int[] sig = CloneDetector.computeMinHash(tokens);

        assertNotNull(sig, "Should produce MinHash for sufficient tokens");
        assertEquals(128, sig.length, "MinHash signature should have 128 hashes");
    }

    @Test
    void computeMinHashReturnNullForTooFewTokens() {
        // Less than SHINGLE_K + 2 = 5 tokens → null
        List<String> tokens = List.of("a", "b", "c");
        int[] sig = CloneDetector.computeMinHash(tokens);
        assertNull(sig, "Too few tokens should produce null signature");
    }

    @Test
    void identicalTokensProduceIdenticalSignature() {
        List<String> tokens = CloneDetector.tokenize(
                "public void foo(int x) { int y = x + 1; return y; }");
        int[] sig1 = CloneDetector.computeMinHash(tokens);
        int[] sig2 = CloneDetector.computeMinHash(tokens);

        assertNotNull(sig1);
        assertNotNull(sig2);
        assertArrayEquals(sig1, sig2, "Same tokens should produce same MinHash");
    }

    @Test
    void identicalMethodsDifferingOnlyInNamesHavePerfectSimilarity() {
        String methodA = """
                public int calculate(int x, int y) {
                    if (x > y) {
                        return x - y;
                    } else {
                        return y - x;
                    }
                }
                """;
        String methodB = """
                public int compute(int a, int b) {
                    if (a > b) {
                        return a - b;
                    } else {
                        return b - a;
                    }
                }
                """;

        List<String> tokensA = CloneDetector.tokenize(methodA);
        List<String> tokensB = CloneDetector.tokenize(methodB);
        int[] sigA = CloneDetector.computeMinHash(tokensA);
        int[] sigB = CloneDetector.computeMinHash(tokensB);

        assertNotNull(sigA);
        assertNotNull(sigB);
        double similarity = CloneDetector.jaccardSimilarity(sigA, sigB);
        assertEquals(1.0, similarity, 0.001,
                "Structurally identical methods (differing only in names) should have ~100% similarity");
    }

    @Test
    void dissimilarMethodsHaveLowSimilarity() {
        String methodA = """
                public void sort(int[] arr) {
                    for (int i = 0; i < arr.length; i++) {
                        for (int j = i + 1; j < arr.length; j++) {
                            if (arr[i] > arr[j]) {
                                int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
                            }
                        }
                    }
                }
                """;
        String methodB = """
                public String format(String template, Map<String, Object> params) {
                    StringBuilder sb = new StringBuilder();
                    Matcher m = Pattern.compile("\\\\{(\\\\w+)\\\\}").matcher(template);
                    while (m.find()) {
                        String key = m.group(1);
                        Object val = params.getOrDefault(key, "");
                        m.appendReplacement(sb, val.toString());
                    }
                    m.appendTail(sb);
                    return sb.toString();
                }
                """;

        List<String> tokensA = CloneDetector.tokenize(methodA);
        List<String> tokensB = CloneDetector.tokenize(methodB);
        int[] sigA = CloneDetector.computeMinHash(tokensA);
        int[] sigB = CloneDetector.computeMinHash(tokensB);

        assertNotNull(sigA);
        assertNotNull(sigB);
        double similarity = CloneDetector.jaccardSimilarity(sigA, sigB);
        assertTrue(similarity < 0.5,
                "Structurally different methods should have low similarity, got: " + similarity);
    }

    // =======================================================================
    // Fragment hashing
    // =======================================================================

    @Test
    void computeFragmentHashesProducesResults() {
        List<String> tokens = CloneDetector.tokenize(
                "public void process(String input) { int x = 0; for (int i = 0; i < 10; i++) { x += i; } return; }");
        List<CloneDetector.FragmentHash> frags = CloneDetector.computeFragmentHashes(tokens);

        assertFalse(frags.isEmpty(), "Should produce fragment hashes for sufficient tokens");
        for (CloneDetector.FragmentHash fh : frags) {
            assertNotNull(fh.hash());
            assertFalse(fh.hash().isEmpty());
            assertTrue(fh.tokenOffset() >= 0);
        }
    }

    @Test
    void fragmentHashesEmptyForShortTokens() {
        // Less than MIN_FRAGMENT_TOKENS (16) → empty
        List<String> tokens = List.of("a", "b", "c", "d", "e");
        List<CloneDetector.FragmentHash> frags = CloneDetector.computeFragmentHashes(tokens);
        assertTrue(frags.isEmpty(), "Too few tokens should produce no fragments");
    }

    @Test
    void identicalFragmentsProduceSameHash() {
        // Create two code blocks with an identical fragment embedded
        String sharedFragment = "for (int i = 0; i < arr.length; i++) { sum += arr[i]; }";
        String codeA = "public void sumA(int[] arr) { int sum = 0; " + sharedFragment + " return; }";
        String codeB = "public void sumB(int[] vals) { int total = 0; " + sharedFragment + " return; }";

        List<String> tokensA = CloneDetector.tokenize(codeA);
        List<String> tokensB = CloneDetector.tokenize(codeB);

        List<CloneDetector.FragmentHash> fragsA = CloneDetector.computeFragmentHashes(tokensA);
        List<CloneDetector.FragmentHash> fragsB = CloneDetector.computeFragmentHashes(tokensB);

        // There should be at least some overlapping fragment hashes
        java.util.Set<String> hashSetA = new java.util.HashSet<>();
        for (CloneDetector.FragmentHash fh : fragsA) hashSetA.add(fh.hash());

        boolean hasOverlap = false;
        for (CloneDetector.FragmentHash fh : fragsB) {
            if (hashSetA.contains(fh.hash())) {
                hasOverlap = true;
                break;
            }
        }
        assertTrue(hasOverlap, "Code with shared fragments should produce overlapping fragment hashes");
    }

    // =======================================================================
    // Jaccard similarity edge cases
    // =======================================================================

    @Test
    void jaccardSimilaritySameArrayIsOne() {
        int[] sig = new int[128];
        for (int i = 0; i < 128; i++) sig[i] = i * 37;
        assertEquals(1.0, CloneDetector.jaccardSimilarity(sig, sig), 0.001);
    }

    @Test
    void jaccardSimilarityDifferentArraysLow() {
        int[] sigA = new int[128];
        int[] sigB = new int[128];
        for (int i = 0; i < 128; i++) {
            sigA[i] = i;
            sigB[i] = i + 1000;
        }
        assertEquals(0.0, CloneDetector.jaccardSimilarity(sigA, sigB), 0.001);
    }
}
