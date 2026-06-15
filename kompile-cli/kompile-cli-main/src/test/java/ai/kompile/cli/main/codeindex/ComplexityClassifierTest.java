package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.codeindex.ComplexityClassifier.FileClassification;
import ai.kompile.cli.main.codeindex.ComplexityClassifier.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the file complexity tier classifier.
 */
class ComplexityClassifierTest {

    // -----------------------------------------------------------------------
    // Fast tier: file extensions
    // -----------------------------------------------------------------------

    @Test
    void testJsonFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("config/settings.json", 2, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testYamlFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("deploy/config.yml", 1, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testMarkdownFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("docs/README.md", 0, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testHtmlFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("templates/index.html", 2, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testCssFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("styles/main.css", 1, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testShellScriptIsFast() {
        FileClassification fc = ComplexityClassifier.classify("scripts/deploy.sh", 2, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testSqlFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("db/migration.sql", 3, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Fast tier: filenames
    // -----------------------------------------------------------------------

    @Test
    void testDockerfileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("Dockerfile", 2, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testMakefileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("Makefile", 1, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testPackageJsonIsFast() {
        FileClassification fc = ComplexityClassifier.classify("package.json", 0, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testPomXmlIsFast() {
        FileClassification fc = ComplexityClassifier.classify("pom.xml", 2, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Fast tier: path patterns
    // -----------------------------------------------------------------------

    @Test
    void testConfigPathIsFast() {
        FileClassification fc = ComplexityClassifier.classify("src/config/AppConfig.java", 3, 1);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testFixturePathIsFast() {
        FileClassification fc = ComplexityClassifier.classify("test/fixtures/data.py", 2, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testMigrationPathIsFast() {
        FileClassification fc = ComplexityClassifier.classify("db/migrations/001_create.java", 4, 1);
        assertEquals(Tier.FAST, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Fast tier: trivial entity count
    // -----------------------------------------------------------------------

    @Test
    void testTrivialFileIsFast() {
        FileClassification fc = ComplexityClassifier.classify("src/main/utils/Constants.java", 1, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testZeroEntitiesIsFast() {
        FileClassification fc = ComplexityClassifier.classify("src/main/Empty.java", 0, 0);
        assertEquals(Tier.FAST, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Powerful tier: path patterns
    // -----------------------------------------------------------------------

    @Test
    void testSecurityPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/security/AuthFilter.java", 5, 3);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testAuthPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/auth/JwtProvider.java", 5, 3);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testCryptoPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("pkg/crypto/aes_encrypt.go", 4, 2);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testCorePathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/core/Engine.java", 6, 3);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testCompilerPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("lib/compiler/Parser.java", 5, 3);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testParserPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/parser/ExpressionParser.java", 5, 3);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testSchedulerPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/scheduler/TaskRunner.java", 4, 2);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testOrchestratorPathIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/orchestration/Pipeline.java", 4, 2);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Powerful tier: high entity density
    // -----------------------------------------------------------------------

    @Test
    void testHighEntityCountIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/main/BigService.java", 15, 6);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testHighMethodCountIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/main/ApiController.java", 10, 9);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testExactlyTwelveEntitiesIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/main/Service.java", 12, 4);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    @Test
    void testExactlyEightMethodsIsPowerful() {
        FileClassification fc = ComplexityClassifier.classify("src/main/Dao.java", 10, 8);
        assertEquals(Tier.POWERFUL, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Balanced tier: standard code
    // -----------------------------------------------------------------------

    @Test
    void testStandardJavaClassIsBalanced() {
        FileClassification fc = ComplexityClassifier.classify("src/main/UserService.java", 6, 4);
        assertEquals(Tier.BALANCED, fc.tier());
    }

    @Test
    void testMediumComplexityIsBalanced() {
        FileClassification fc = ComplexityClassifier.classify("src/main/Repository.java", 5, 3);
        assertEquals(Tier.BALANCED, fc.tier());
    }

    @Test
    void testThreeEntitiesIsBalanced() {
        FileClassification fc = ComplexityClassifier.classify("src/main/Helper.java", 3, 1);
        assertEquals(Tier.BALANCED, fc.tier());
    }

    @Test
    void testElevenEntitiesIsBalanced() {
        FileClassification fc = ComplexityClassifier.classify("src/main/BigHelper.java", 11, 5);
        assertEquals(Tier.BALANCED, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void testNullPathIsBalanced() {
        FileClassification fc = ComplexityClassifier.classify(null, 5, 2);
        assertEquals(Tier.BALANCED, fc.tier());
    }

    @Test
    void testSimpleExtensionOverriddenByHighEntityCount() {
        // A .json file with 10 entities is surprisingly complex — not fast
        FileClassification fc = ComplexityClassifier.classify("src/data.json", 10, 0);
        assertNotEquals(Tier.FAST, fc.tier());
    }

    @Test
    void testConfigPathNotFastIfHighEntities() {
        // Config path but 8 entities — entity check takes precedence over path
        FileClassification fc = ComplexityClassifier.classify("src/config/BigConfig.java", 8, 5);
        assertNotEquals(Tier.FAST, fc.tier());
    }

    // -----------------------------------------------------------------------
    // Classification result structure
    // -----------------------------------------------------------------------

    @Test
    void testClassificationIncludesReason() {
        FileClassification fc = ComplexityClassifier.classify("src/security/Auth.java", 5, 3);
        assertNotNull(fc.reason());
        assertFalse(fc.reason().isEmpty());
    }

    @Test
    void testClassificationIncludesCounts() {
        FileClassification fc = ComplexityClassifier.classify("src/main/Service.java", 7, 4);
        assertEquals(7, fc.entityCount());
        assertEquals(4, fc.methodCount());
    }

    @Test
    void testClassificationIncludesFilePath() {
        String path = "src/main/Handler.java";
        FileClassification fc = ComplexityClassifier.classify(path, 5, 2);
        assertEquals(path, fc.filePath());
    }

    // -----------------------------------------------------------------------
    // Parameterized tests
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "config.yml, 1, 0, FAST",
            "Dockerfile, 0, 0, FAST",
            "src/main/Empty.java, 0, 0, FAST",
            "src/main/Simple.java, 2, 1, FAST",
            "src/main/Service.java, 6, 3, BALANCED",
            "src/main/Controller.java, 11, 7, BALANCED",
            "src/security/Filter.java, 5, 3, POWERFUL",
            "src/main/BigService.java, 15, 10, POWERFUL"
    })
    void testTierClassification(String path, int entities, int methods, String expectedTier) {
        FileClassification fc = ComplexityClassifier.classify(path, entities, methods);
        assertEquals(Tier.valueOf(expectedTier), fc.tier(),
                "Expected " + expectedTier + " for " + path +
                        " (" + entities + " entities, " + methods + " methods), got " + fc.tier());
    }
}
