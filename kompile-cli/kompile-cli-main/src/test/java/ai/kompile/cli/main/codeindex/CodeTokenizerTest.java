package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the code-aware identifier tokenizer.
 */
class CodeTokenizerTest {

    // -----------------------------------------------------------------------
    // camelCase splitting
    // -----------------------------------------------------------------------

    @Test
    void testCamelCaseSplit() {
        List<String> tokens = CodeTokenizer.tokenize("getUserName");
        assertTrue(tokens.contains("user"));
        assertTrue(tokens.contains("name"));
    }

    @Test
    void testPascalCaseSplit() {
        List<String> tokens = CodeTokenizer.tokenize("LocalCodeIndexer");
        assertTrue(tokens.contains("local"));
        assertTrue(tokens.contains("code"));
        assertTrue(tokens.contains("indexer"));
    }

    @Test
    void testPascalAcronymSplit() {
        // "XMLParser" should split to "XML" + "Parser"
        List<String> tokens = CodeTokenizer.tokenize("XMLParser");
        assertTrue(tokens.contains("xml"));
        assertTrue(tokens.contains("parser"));
    }

    @Test
    void testHTTPResponseHandlerSplit() {
        List<String> tokens = CodeTokenizer.tokenize("HTTPResponseHandler");
        assertTrue(tokens.contains("http"));
        assertTrue(tokens.contains("response"));
        assertTrue(tokens.contains("handler"));
    }

    // -----------------------------------------------------------------------
    // snake_case / kebab-case splitting
    // -----------------------------------------------------------------------

    @Test
    void testSnakeCaseSplit() {
        List<String> tokens = CodeTokenizer.tokenize("find_all_users");
        assertTrue(tokens.contains("find"));
        // "all" is a stop word, so it gets removed with default settings
        assertFalse(tokens.contains("all"));
        assertTrue(tokens.contains("users"));

        // With stop words kept, "all" should be present
        List<String> withStop = CodeTokenizer.tokenizeKeepStopWords("find_all_users");
        assertTrue(withStop.contains("all"));
    }

    @Test
    void testKebabCaseSplit() {
        List<String> tokens = CodeTokenizer.tokenize("my-component-name");
        assertTrue(tokens.contains("my"));
        assertTrue(tokens.contains("component"));
        assertTrue(tokens.contains("name"));
    }

    // -----------------------------------------------------------------------
    // Dot notation / file paths
    // -----------------------------------------------------------------------

    @Test
    void testDotNotation() {
        // The tokenizer treats short dot segments as file extensions and strips them,
        // but longer names like "kompile" survive. Use tokenizeSymbol for identifiers.
        List<String> tokens = CodeTokenizer.tokenizeSymbol("ai.kompile.cli.main");
        assertTrue(tokens.contains("ai"));
        assertTrue(tokens.contains("kompile"));
    }

    @Test
    void testFileExtensionStripped() {
        List<String> tokens = CodeTokenizer.tokenize("Handler.java");
        assertTrue(tokens.contains("handler"));
        // ".java" is 4 chars, stripped as file extension
        assertFalse(tokens.contains("java"));
    }

    @Test
    void testPathTokenization() {
        List<String> tokens = CodeTokenizer.tokenizePath("src/main/java/ai/kompile/Handler");
        assertTrue(tokens.contains("src"));
        assertTrue(tokens.contains("kompile"));
        assertTrue(tokens.contains("handler"));
    }

    // -----------------------------------------------------------------------
    // Stop word removal
    // -----------------------------------------------------------------------

    @Test
    void testStopWordsRemoved() {
        List<String> tokens = CodeTokenizer.tokenize("the quick brown fox");
        assertFalse(tokens.contains("the"));
        assertTrue(tokens.contains("quick"));
        assertTrue(tokens.contains("brown"));
        assertTrue(tokens.contains("fox"));
    }

    @Test
    void testCodeStopWordsRemoved() {
        List<String> tokens = CodeTokenizer.tokenize("public static void main");
        // "public", "static", "void" are code stop words
        assertFalse(tokens.contains("public"));
        assertFalse(tokens.contains("static"));
        assertFalse(tokens.contains("void"));
        assertTrue(tokens.contains("main"));
    }

    @Test
    void testKeepStopWords() {
        List<String> tokens = CodeTokenizer.tokenizeKeepStopWords("the quick brown");
        assertTrue(tokens.contains("the"));
        assertTrue(tokens.contains("quick"));
        assertTrue(tokens.contains("brown"));
    }

    // -----------------------------------------------------------------------
    // Symbol tokenization
    // -----------------------------------------------------------------------

    @Test
    void testTokenizeSymbol() {
        List<String> tokens = CodeTokenizer.tokenizeSymbol("getUserByEmail");
        // Symbol tokenization does NOT remove code stop words like "get"
        assertTrue(tokens.contains("get"));
        assertTrue(tokens.contains("user"));
        assertTrue(tokens.contains("by"));
        assertTrue(tokens.contains("email"));
    }

    @Test
    void testTokenizeSymbolSnakeCase() {
        List<String> tokens = CodeTokenizer.tokenizeSymbol("process_all_items");
        assertTrue(tokens.contains("process"));
        assertTrue(tokens.contains("all"));
        assertTrue(tokens.contains("items"));
    }

    // -----------------------------------------------------------------------
    // Uniqueness and ordering
    // -----------------------------------------------------------------------

    @Test
    void testDeduplicated() {
        List<String> tokens = CodeTokenizer.tokenize("user user user");
        assertEquals(1, tokens.stream().filter(t -> t.equals("user")).count());
    }

    @Test
    void testOrderPreserved() {
        List<String> tokens = CodeTokenizer.tokenize("alpha beta gamma");
        assertEquals(List.of("alpha", "beta", "gamma"), tokens);
    }

    // -----------------------------------------------------------------------
    // Min length filtering
    // -----------------------------------------------------------------------

    @Test
    void testMinLengthDefault() {
        List<String> tokens = CodeTokenizer.tokenize("a bb ccc");
        assertFalse(tokens.contains("a")); // too short (< 2)
        assertTrue(tokens.contains("bb"));
        assertTrue(tokens.contains("ccc"));
    }

    @Test
    void testCustomMinLength() {
        List<String> tokens = CodeTokenizer.tokenize("ab cde fghi", true, 3);
        assertFalse(tokens.contains("ab")); // too short (< 3)
        assertTrue(tokens.contains("cde"));
        assertTrue(tokens.contains("fghi"));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void testNullInput() {
        assertEquals(List.of(), CodeTokenizer.tokenize(null));
        assertEquals(List.of(), CodeTokenizer.tokenizeSymbol(null));
        assertEquals(List.of(), CodeTokenizer.tokenizePath(null));
    }

    @Test
    void testEmptyInput() {
        assertEquals(List.of(), CodeTokenizer.tokenize(""));
        assertEquals(List.of(), CodeTokenizer.tokenizeSymbol(""));
        assertEquals(List.of(), CodeTokenizer.tokenizePath(""));
    }

    @Test
    void testAllStopWords() {
        List<String> tokens = CodeTokenizer.tokenize("the a an in of to");
        assertTrue(tokens.isEmpty());
    }

    @Test
    void testSpecialCharactersStripped() {
        List<String> tokens = CodeTokenizer.tokenize("hello@world#test");
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("world"));
        assertTrue(tokens.contains("test"));
    }

    // -----------------------------------------------------------------------
    // Token estimation
    // -----------------------------------------------------------------------

    @Test
    void testEstimateTokensSingleString() {
        assertEquals(3, CodeTokenizer.estimateTokens("hello world!")); // 12 chars / 4 = 3
        assertEquals(0, CodeTokenizer.estimateTokens(""));
        assertEquals(0, CodeTokenizer.estimateTokens((String) null));
    }

    @Test
    void testEstimateTokensCollection() {
        List<String> sigs = List.of("public void run()", "private int count");
        int estimated = CodeTokenizer.estimateTokens(sigs);
        assertTrue(estimated > 0);
        // Total chars = 17 + 17 = 34, / 4 = ~9
        assertEquals(9, estimated);
    }

    // -----------------------------------------------------------------------
    // Parameterized: complex identifier splitting
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "getElementById, element",
            "parseJSON, parse",
            "parseJSON, json",
            "myHTTPServer, my",
            "myHTTPServer, http",
            "myHTTPServer, server",
            "UserService, user",
            "UserService, service",
            "findByEmail, find",
            "findByEmail, email"
    })
    void testComplexIdentifierSplit(String input, String expectedToken) {
        List<String> tokens = CodeTokenizer.tokenizeSymbol(input);
        assertTrue(tokens.contains(expectedToken),
                "Expected token '" + expectedToken + "' in tokenization of '" + input +
                        "', got: " + tokens);
    }
}
