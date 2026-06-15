package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.codeindex.IntentClassifier.Intent;
import ai.kompile.cli.main.codeindex.IntentClassifier.WeightProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the query intent classifier.
 */
class IntentClassifierTest {

    // -----------------------------------------------------------------------
    // Default intent
    // -----------------------------------------------------------------------

    @Test
    void testDefaultIsSearch() {
        assertEquals(Intent.SEARCH, IntentClassifier.classify("all classes in project"));
    }

    @Test
    void testNullInputDefaultsToSearch() {
        assertEquals(Intent.SEARCH, IntentClassifier.classify(null));
    }

    @Test
    void testEmptyInputDefaultsToSearch() {
        assertEquals(Intent.SEARCH, IntentClassifier.classify(""));
    }

    // -----------------------------------------------------------------------
    // Debug intent
    // -----------------------------------------------------------------------

    @Test
    void testDebugBugKeyword() {
        assertEquals(Intent.DEBUG, IntentClassifier.classify("where is the bug in login"));
    }

    @Test
    void testDebugErrorKeyword() {
        assertEquals(Intent.DEBUG, IntentClassifier.classify("NullPointerException error in service"));
    }

    @Test
    void testDebugExceptionKeyword() {
        assertEquals(Intent.DEBUG, IntentClassifier.classify("exception thrown in handler"));
    }

    @Test
    void testDebugWhyDoesPhrase() {
        assertEquals(Intent.DEBUG, IntentClassifier.classify("why does the test fail"));
    }

    @Test
    void testDebugFixPhrase() {
        assertEquals(Intent.DEBUG, IntentClassifier.classify("how to fix this crash"));
    }

    // -----------------------------------------------------------------------
    // Explain intent
    // -----------------------------------------------------------------------

    @Test
    void testExplainHowDoes() {
        assertEquals(Intent.EXPLAIN, IntentClassifier.classify("how does auth work"));
    }

    @Test
    void testExplainWhatDoes() {
        assertEquals(Intent.EXPLAIN, IntentClassifier.classify("what does this function do"));
    }

    @Test
    void testExplainOverview() {
        assertEquals(Intent.EXPLAIN, IntentClassifier.classify("explain the algorithm used here"));
    }

    // -----------------------------------------------------------------------
    // Refactor intent
    // -----------------------------------------------------------------------

    @Test
    void testRefactorKeyword() {
        assertEquals(Intent.REFACTOR, IntentClassifier.classify("refactor the user service"));
    }

    @Test
    void testRefactorExtract() {
        assertEquals(Intent.REFACTOR, IntentClassifier.classify("extract method from this block"));
    }

    @Test
    void testRefactorRename() {
        assertEquals(Intent.REFACTOR, IntentClassifier.classify("rename variable to something meaningful"));
    }

    // -----------------------------------------------------------------------
    // Review intent
    // -----------------------------------------------------------------------

    @Test
    void testReviewAudit() {
        assertEquals(Intent.REVIEW, IntentClassifier.classify("audit the authentication module"));
    }

    @Test
    void testReviewSecurity() {
        assertEquals(Intent.REVIEW, IntentClassifier.classify("check for vulnerability in input handling"));
    }

    // -----------------------------------------------------------------------
    // Test intent
    // -----------------------------------------------------------------------

    @Test
    void testTestKeyword() {
        assertEquals(Intent.TEST, IntentClassifier.classify("unit test for the parser"));
    }

    @Test
    void testTestCoverage() {
        assertEquals(Intent.TEST, IntentClassifier.classify("improve test coverage for handler"));
    }

    @Test
    void testTestMock() {
        assertEquals(Intent.TEST, IntentClassifier.classify("mock the database in tests"));
    }

    // -----------------------------------------------------------------------
    // Integrate intent
    // -----------------------------------------------------------------------

    @Test
    void testIntegrateConnect() {
        assertEquals(Intent.INTEGRATE, IntentClassifier.classify("how do services connect to database"));
    }

    @Test
    void testIntegrateDependency() {
        assertEquals(Intent.INTEGRATE, IntentClassifier.classify("what depends on the user module"));
    }

    // -----------------------------------------------------------------------
    // Navigate intent
    // -----------------------------------------------------------------------

    @Test
    void testNavigateWhereIs() {
        assertEquals(Intent.NAVIGATE, IntentClassifier.classify("where is auth handled"));
    }

    @Test
    void testNavigateFind() {
        assertEquals(Intent.NAVIGATE, IntentClassifier.classify("find the config file"));
    }

    @Test
    void testNavigateShowMe() {
        assertEquals(Intent.NAVIGATE, IntentClassifier.classify("show me the router setup"));
    }

    // -----------------------------------------------------------------------
    // Weight profiles
    // -----------------------------------------------------------------------

    @Test
    void testDefaultWeightProfile() {
        WeightProfile wp = IntentClassifier.getWeights(Intent.SEARCH);
        assertEquals(1.0, wp.exactToken());
        assertEquals(0.5, wp.symbolMatch());
        assertEquals(0.3, wp.prefixMatch());
        assertEquals(0.8, wp.pathMatch());
        assertEquals(1.5, wp.recencyBoost());
        assertEquals(0.4, wp.graphBoost());
    }

    @Test
    void testDebugWeightProfile() {
        WeightProfile wp = IntentClassifier.getWeights(Intent.DEBUG);
        // Debug boosts exact token match
        assertTrue(wp.exactToken() > WeightProfile.DEFAULT.exactToken());
        // Debug reduces path match importance
        assertTrue(wp.pathMatch() < WeightProfile.DEFAULT.pathMatch());
    }

    @Test
    void testIntegrateWeightProfile() {
        WeightProfile wp = IntentClassifier.getWeights(Intent.INTEGRATE);
        // Integrate boosts graph connectivity
        assertTrue(wp.graphBoost() > WeightProfile.DEFAULT.graphBoost());
    }

    @Test
    void testNavigateWeightProfile() {
        WeightProfile wp = IntentClassifier.getWeights(Intent.NAVIGATE);
        // Navigate boosts path matching
        assertTrue(wp.pathMatch() > WeightProfile.DEFAULT.pathMatch());
    }

    @Test
    void testClassifyAndGetWeightsComposition() {
        WeightProfile wp = IntentClassifier.classifyAndGetWeights("where is the login handler");
        assertNotNull(wp);
        // Should classify as NAVIGATE and return navigate weights
        assertEquals(IntentClassifier.getWeights(Intent.NAVIGATE), wp);
    }

    // -----------------------------------------------------------------------
    // All intents have a weight profile
    // -----------------------------------------------------------------------

    @Test
    void testAllIntentsHaveWeightProfiles() {
        for (Intent intent : Intent.values()) {
            WeightProfile wp = IntentClassifier.getWeights(intent);
            assertNotNull(wp, "No weight profile for intent: " + intent);
            assertTrue(wp.exactToken() > 0, "exactToken should be > 0 for: " + intent);
        }
    }

    // -----------------------------------------------------------------------
    // All intents have descriptions
    // -----------------------------------------------------------------------

    @Test
    void testAllIntentsHaveDescriptions() {
        for (Intent intent : Intent.values()) {
            String desc = IntentClassifier.describeIntent(intent);
            assertNotNull(desc);
            assertFalse(desc.isEmpty(), "Empty description for: " + intent);
        }
    }

    // -----------------------------------------------------------------------
    // Parameterized intent detection
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "'fix the null pointer exception', DEBUG",
            "'what does handleRequest do', EXPLAIN",
            "'refactor the user service code', REFACTOR",
            "'check for security issues', REVIEW",
            "'add test for the validator', TEST",
            "'how do services interact', INTEGRATE",
            "'where is the main entry point', NAVIGATE"
    })
    void testIntentDetection(String query, String expectedIntent) {
        Intent detected = IntentClassifier.classify(query);
        assertEquals(Intent.valueOf(expectedIntent), detected,
                "Query '" + query + "' should be classified as " + expectedIntent +
                        ", but was " + detected);
    }
}
