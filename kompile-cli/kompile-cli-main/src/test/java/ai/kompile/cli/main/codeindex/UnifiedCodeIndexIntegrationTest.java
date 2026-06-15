package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration tests verifying all code indexing components work
 * together: splan parsing, spath resolution, ranked search, impact analysis,
 * signature extraction, health scoring, complexity routing, and code tokenization.
 *
 * <p>Indexes a mixed-language project (Java + splan + YAML config) and
 * exercises cross-component workflows that an MCP tool consumer would use.</p>
 */
class UnifiedCodeIndexIntegrationTest {

    private static final String PROJECT_ID = "unified-test-project";
    private static Path tempDir;
    private static Path indexDir;

    // -----------------------------------------------------------------------
    // Setup: create and index a mixed-language project
    // -----------------------------------------------------------------------

    @BeforeAll
    static void indexProject() throws Exception {
        tempDir = Files.createTempDirectory("unified-codeindex-test");

        // -- Java source files --
        Path srcMain = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcMain);

        Files.writeString(srcMain.resolve("User.java"), """
                package com.example;
                /**
                 * Domain entity representing a user.
                 */
                public class User {
                    private String name;
                    private String email;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getEmail() { return email; }
                    public void setEmail(String email) { this.email = email; }
                }
                """);

        Files.writeString(srcMain.resolve("UserRepository.java"), """
                package com.example;
                import java.util.List;
                import java.util.Optional;
                /**
                 * Persistence layer for users.
                 */
                public interface UserRepository {
                    Optional<User> findById(long id);
                    List<User> findByEmail(String email);
                    void save(User user);
                    void deleteById(long id);
                }
                """);

        Files.writeString(srcMain.resolve("UserService.java"), """
                package com.example;
                import java.util.List;
                /**
                 * Business logic for user operations.
                 */
                public class UserService {
                    private final UserRepository userRepository;
                    public UserService(UserRepository userRepository) {
                        this.userRepository = userRepository;
                    }
                    public User getUserByEmail(String email) {
                        List<User> users = userRepository.findByEmail(email);
                        return users.isEmpty() ? null : users.get(0);
                    }
                    public void createUser(String name, String email) {
                        User user = new User();
                        user.setName(name);
                        user.setEmail(email);
                        userRepository.save(user);
                    }
                    public void removeUser(long id) {
                        userRepository.deleteById(id);
                    }
                }
                """);

        Files.writeString(srcMain.resolve("UserController.java"), """
                package com.example;
                /**
                 * REST controller for user endpoints.
                 */
                public class UserController {
                    private final UserService userService;
                    public UserController(UserService userService) {
                        this.userService = userService;
                    }
                    public User getUser(String email) {
                        return userService.getUserByEmail(email);
                    }
                    public void createUser(String name, String email) {
                        userService.createUser(name, email);
                    }
                }
                """);

        // Security file (should be classified as POWERFUL)
        Path srcSecurity = tempDir.resolve("src/main/java/com/example/security");
        Files.createDirectories(srcSecurity);
        Files.writeString(srcSecurity.resolve("AuthFilter.java"), """
                package com.example.security;
                import com.example.User;
                /**
                 * Authentication filter for securing endpoints.
                 */
                public class AuthFilter {
                    public boolean authenticate(String token) {
                        return token != null && !token.isEmpty();
                    }
                    public User resolveUser(String token) {
                        return null;
                    }
                    public boolean authorize(User user, String resource) {
                        return user != null;
                    }
                    public String generateToken(User user) {
                        return "token-" + user.getName();
                    }
                    public void revokeToken(String token) {
                        // revoke
                    }
                }
                """);

        // Test file (should be penalized in ranked search)
        Path srcTest = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(srcTest);
        Files.writeString(srcTest.resolve("UserServiceTest.java"), """
                package com.example;
                /**
                 * Tests for UserService.
                 */
                public class UserServiceTest {
                    public void testGetUserByEmail() {
                        // test
                    }
                    public void testCreateUser() {
                        // test
                    }
                }
                """);

        // -- Splan file --
        Files.writeString(tempDir.resolve("build.splan"), """
                # Build pipeline for the project
                :source_dir:::src/main/java:::
                :output_dir:::target/classes:::
                compile :source_dir :output_dir
                run tests
                ---
                # Deployment section
                :deploy_target:::production-server:::
                package :output_dir
                deploy :deploy_target ::: deployment config :::
                """);

        // -- Config file (should be FAST tier) --
        Files.writeString(tempDir.resolve("application.yml"), """
                server:
                  port: 8080
                spring:
                  datasource:
                    url: jdbc:h2:mem:testdb
                """);

        // Index everything
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LocalCodeIndexer.IndexResult result = indexer.index(tempDir, PROJECT_ID,
                null, null, true, new PrintStream(baos));

        assertTrue(result.filesProcessed() >= 7, "Should index at least 7 files");
        assertTrue(result.entitiesFound() > 0, "Should find entities");

        indexDir = LocalCodeIndexer.getIndexDir(PROJECT_ID);
        assertTrue(Files.exists(indexDir.resolve("index.db")), "Index DB should exist");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
        if (indexDir != null && Files.exists(indexDir)) {
            Files.walk(indexDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    // -----------------------------------------------------------------------
    // 1. Splan entities integrate with new features
    // -----------------------------------------------------------------------

    @Test
    void testSplanEntitiesIndexed() throws Exception {
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        // Splan operations are indexed as FUNCTION
        List<Map<String, Object>> ops = indexer.search(PROJECT_ID, "compile", "FUNCTION", 10);
        assertFalse(ops.isEmpty(), "Should find splan 'compile' operation as FUNCTION");

        // Splan declarations are indexed as CONSTANT
        List<Map<String, Object>> decls = indexer.search(PROJECT_ID, "source_dir", "CONSTANT", 10);
        assertFalse(decls.isEmpty(), "Should find splan 'source_dir' declaration as CONSTANT");
    }

    @Test
    void testRankedSearchFindsSplanEntities() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(PROJECT_ID, "compile source",
                        indexDir, tempDir, 20);
        assertFalse(results.results().isEmpty(), "Ranked search should return results for splan terms");

        // At least one result should reference the splan file
        boolean hasSplan = results.results().stream()
                .anyMatch(r -> r.filePath() != null && r.filePath().endsWith(".splan"));
        assertTrue(hasSplan, "Ranked search should include splan file results");
    }

    @Test
    void testSignaturesExtractSplanFile() throws Exception {
        SignatureExtractor.FileSignatures fs =
                SignatureExtractor.extractFile(PROJECT_ID, "build.splan", indexDir, tempDir);
        assertNotNull(fs, "Should extract signatures from splan file");
        assertEquals("splan", fs.language());
        assertFalse(fs.signatures().isEmpty(), "Splan file should have signatures");

        String formatted = SignatureExtractor.formatFileContext(fs);
        assertNotNull(formatted);
        assertTrue(formatted.contains("build.splan"), "Formatted output should contain file name");
    }

    @Test
    void testComplexityRoutesSplanFile() throws Exception {
        // Splan files have .splan extension — not in FAST_EXTENSIONS, so tier depends on entity count
        ComplexityClassifier.ProjectClassification pc =
                ComplexityClassifier.classifyProject(PROJECT_ID, indexDir);
        assertNotNull(pc);
        assertTrue(pc.fastCount() + pc.balancedCount() + pc.powerfulCount() > 0,
                "Project classification should have at least one file");
    }

    @Test
    void testHealthScoringIncludesSplan() throws Exception {
        IndexHealthScorer.HealthScore hs = IndexHealthScorer.score(PROJECT_ID, indexDir);
        assertTrue(hs.totalEntities() > 0, "Health score should count splan entities");
        assertTrue(hs.totalFiles() >= 7, "Health score should include splan files in count");
        assertTrue(hs.languageCount() >= 2,
                "Should detect at least 2 languages (java + splan), got: " + hs.languageCount());
    }

    @Test
    void testCodeTokenizerHandlesSplanIdentifiers() {
        // Splan operation names should tokenize correctly
        List<String> tokens = CodeTokenizer.tokenizeSymbol("compile");
        assertTrue(tokens.contains("compile"));

        // Splan declaration names with underscores
        tokens = CodeTokenizer.tokenizeSymbol("source_dir");
        assertTrue(tokens.contains("source"));
        assertTrue(tokens.contains("dir"));

        // Splan declaration names with colons stripped
        tokens = CodeTokenizer.tokenize("deploy_target");
        assertTrue(tokens.contains("deploy"));
        assertTrue(tokens.contains("target"));
    }

    // -----------------------------------------------------------------------
    // 2. Spath integration with new features
    // -----------------------------------------------------------------------

    @Test
    void testSpathFindsJavaEntities() throws Exception {
        SpathResolver resolver = new SpathResolver(PROJECT_ID);
        SpathResolver.SpathResult result = resolver.resolve("com.example.UserService", 50);
        assertFalse(result.matches().isEmpty(),
                "Spath should find UserService via fully qualified path");
    }

    @Test
    void testSpathWildcardAcrossPackage() throws Exception {
        SpathResolver resolver = new SpathResolver(PROJECT_ID);
        SpathResolver.SpathResult result = resolver.resolve("com.example.*", 50);
        assertTrue(result.totalMatches() > 1,
                "Wildcard spath should find multiple entities in package");
    }

    @Test
    void testSpathRecursiveIncludesSecurityPackage() throws Exception {
        SpathResolver resolver = new SpathResolver(PROJECT_ID);
        SpathResolver.SpathResult result = resolver.resolve("com.example.**", 100);
        // Should include both com.example and com.example.security entities
        boolean hasSecurityEntity = result.matches().stream()
                .anyMatch(m -> m.filePath() != null && m.filePath().contains("security"));
        assertTrue(hasSecurityEntity,
                "Recursive spath should include com.example.security entities");
    }

    @Test
    void testSpathAndRankedSearchConsistency() throws Exception {
        // Both spath and ranked_search should find the same entity
        SpathResolver resolver = new SpathResolver(PROJECT_ID);
        SpathResolver.SpathResult spathResult = resolver.resolve("com.example.UserService", 10);

        CodeRelevanceRanker.RankedResults rankedResult =
                CodeRelevanceRanker.rankedSearch(PROJECT_ID, "UserService",
                        indexDir, tempDir, 10);

        assertFalse(spathResult.matches().isEmpty(), "Spath should find UserService");
        assertFalse(rankedResult.results().isEmpty(), "Ranked search should find UserService");

        // Both should reference the same file
        String spathFile = spathResult.matches().stream()
                .filter(m -> "CLASS".equals(m.entityType()))
                .map(SpathResolver.SpathMatch::filePath)
                .findFirst().orElse(null);

        boolean rankedHasSameFile = rankedResult.results().stream()
                .anyMatch(r -> r.filePath() != null && r.filePath().equals(spathFile));

        if (spathFile != null) {
            assertTrue(rankedHasSameFile,
                    "Ranked search and spath should agree on UserService file location");
        }
    }

    // -----------------------------------------------------------------------
    // 3. Cross-component workflows
    // -----------------------------------------------------------------------

    @Test
    void testImpactToSignaturesWorkflow() throws Exception {
        // Step 1: Analyze impact of changing User.java
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile(
                        "src/main/java/com/example/User.java", indexDir, 0);

        assertNotNull(impact);
        // User.java should impact UserService, UserController, AuthFilter, etc.
        Set<String> allImpacted = new HashSet<>();
        allImpacted.addAll(impact.directDependents());
        allImpacted.addAll(impact.transitiveDependents());

        // Step 2: Extract signatures for impacted files
        for (String depFile : allImpacted) {
            SignatureExtractor.FileSignatures fs =
                    SignatureExtractor.extractFile(PROJECT_ID, depFile, indexDir, tempDir);
            // Some impacted files might not have extractable signatures (e.g., non-indexed)
            // but at least the workflow should not throw
            if (fs != null) {
                assertFalse(fs.signatures().isEmpty(),
                        "Impacted file " + depFile + " should have signatures");
            }
        }
    }

    @Test
    void testImpactCategorizesTestFiles() throws Exception {
        // Changing UserService should identify UserServiceTest as affected test
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile(
                        "src/main/java/com/example/UserService.java", indexDir, 0);

        // Tests might appear in affectedTests
        // The impact analyzer classifies files with "test" in the path
        Set<String> allAffected = new HashSet<>();
        allAffected.addAll(impact.directDependents());
        allAffected.addAll(impact.transitiveDependents());
        allAffected.addAll(impact.affectedTests());
        // UserController depends on UserService, so it should be in impact
        boolean hasController = allAffected.stream()
                .anyMatch(f -> f.contains("UserController"));
        assertTrue(hasController || impact.totalImpact() > 0,
                "Changing UserService should have downstream impact");
    }

    @Test
    void testRankedSearchIntentAffectsResults() throws Exception {
        // Debug query should detect DEBUG intent
        CodeRelevanceRanker.RankedResults debugResults =
                CodeRelevanceRanker.rankedSearch(PROJECT_ID,
                        "bug in UserService", indexDir, tempDir, 10);
        assertEquals(IntentClassifier.Intent.DEBUG, debugResults.intent());

        // Navigate query should detect NAVIGATE intent
        CodeRelevanceRanker.RankedResults navResults =
                CodeRelevanceRanker.rankedSearch(PROJECT_ID,
                        "where is UserController", indexDir, tempDir, 10);
        assertEquals(IntentClassifier.Intent.NAVIGATE, navResults.intent());
    }

    @Test
    void testRankedSearchPenalizesTestFiles() throws Exception {
        // Search for "user" should rank source files above test files
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(PROJECT_ID, "user service",
                        indexDir, tempDir, 20);

        if (results.results().size() > 1) {
            // Find test file result and source file result
            CodeRelevanceRanker.ScoredResult testResult = null;
            CodeRelevanceRanker.ScoredResult sourceResult = null;
            for (CodeRelevanceRanker.ScoredResult r : results.results()) {
                if (r.filePath() != null && r.filePath().contains("Test")) {
                    testResult = r;
                } else if (r.filePath() != null && r.filePath().contains("UserService")) {
                    sourceResult = r;
                }
            }
            if (testResult != null && sourceResult != null) {
                assertTrue(sourceResult.score() > testResult.score(),
                        "Source file should score higher than test file");
            }
        }
    }

    @Test
    void testComplexityRoutingClassifiesAllTiers() throws Exception {
        ComplexityClassifier.ProjectClassification pc =
                ComplexityClassifier.classifyProject(PROJECT_ID, indexDir);

        // application.yml should be FAST
        assertTrue(pc.fastCount() > 0,
                "Config files (application.yml) should be classified as FAST");

        // AuthFilter.java in security/ should be POWERFUL
        List<String> powerfulFiles = pc.byTier().getOrDefault(
                ComplexityClassifier.Tier.POWERFUL, List.of());
        boolean hasPowerful = powerfulFiles.stream()
                .anyMatch(f -> f.contains("security"));
        assertTrue(hasPowerful,
                "Security files should be classified as POWERFUL, powerful files: " + powerfulFiles);

        String formatted = ComplexityClassifier.formatClassification(pc);
        assertNotNull(formatted);
        assertTrue(formatted.contains("FAST") || formatted.contains("fast"),
                "Formatted routing should mention tier names");
    }

    @Test
    void testHealthScoreFormatsCorrectly() throws Exception {
        IndexHealthScorer.HealthScore hs = IndexHealthScorer.score(PROJECT_ID, indexDir);

        assertTrue(hs.score() >= 0 && hs.score() <= 100,
                "Health score should be 0-100, got: " + hs.score());
        assertNotNull(hs.grade());
        assertTrue(hs.grade().matches("[ABCD]"),
                "Grade should be A, B, C, or D, got: " + hs.grade());

        String formatted = IndexHealthScorer.formatHealth(hs);
        assertTrue(formatted.contains(hs.grade()),
                "Formatted health should include grade");
    }

    // -----------------------------------------------------------------------
    // 4. Signature extraction across languages
    // -----------------------------------------------------------------------

    @Test
    void testProjectSignaturesIncludeAllLanguages() throws Exception {
        SignatureExtractor.ProjectSignatures ps =
                SignatureExtractor.extractProject(PROJECT_ID, indexDir, tempDir);

        assertTrue(ps.totalFiles() > 0, "Should have files with signatures");
        assertTrue(ps.totalSignatures() > 0, "Should have extracted signatures");

        // Check that at least Java files produced signatures
        boolean hasJavaFile = ps.files().stream()
                .anyMatch(f -> "java".equals(f.language()));
        assertTrue(hasJavaFile, "Should have Java file signatures");

        String formatted = SignatureExtractor.formatAsContext(ps);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty(), "Formatted context should not be empty");
    }

    @Test
    void testSignatureTokenReduction() throws Exception {
        SignatureExtractor.ProjectSignatures ps =
                SignatureExtractor.extractProject(PROJECT_ID, indexDir, tempDir);

        // Files with entities should show some token reduction
        for (SignatureExtractor.FileSignatures fs : ps.files()) {
            if (fs.originalLines() > 0 && fs.signatureTokens() > 0) {
                assertTrue(fs.reductionPercent() >= 0,
                        "Reduction percent should be non-negative for " + fs.filePath());
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. Full pipeline: index → health → search → impact → signatures → routing
    // -----------------------------------------------------------------------

    @Test
    void testFullPipeline() throws Exception {
        // 1. Health check
        IndexHealthScorer.HealthScore health = IndexHealthScorer.score(PROJECT_ID, indexDir);
        assertTrue(health.score() > 0, "Index should have positive health score");

        // 2. Ranked search
        CodeRelevanceRanker.RankedResults searchResults =
                CodeRelevanceRanker.rankedSearch(PROJECT_ID, "user management",
                        indexDir, tempDir, 5);
        assertNotNull(searchResults);

        // 3. If we got results, analyze impact of the top result
        if (!searchResults.results().isEmpty()) {
            String topFile = searchResults.results().get(0).filePath();
            if (topFile != null) {
                ImpactAnalyzer.FileImpact impact =
                        ImpactAnalyzer.analyzeFile(topFile, indexDir, 3);
                assertNotNull(impact);

                // 4. Extract signatures for the top file
                SignatureExtractor.FileSignatures fs =
                        SignatureExtractor.extractFile(PROJECT_ID, topFile, indexDir, tempDir);
                // May be null if no entities in file, but should not throw

                // 5. Get complexity tier for the top file
                try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                    List<Map<String, Object>> entities = db.getEntitiesForFile(topFile);
                    int entityCount = (int) entities.stream()
                            .filter(e -> !"FILE".equals(e.get("entityType"))
                                    && !"IMPORT".equals(e.get("entityType"))
                                    && !"PACKAGE".equals(e.get("entityType")))
                            .count();
                    int methodCount = (int) entities.stream()
                            .filter(e -> "METHOD".equals(e.get("entityType"))
                                    || "FUNCTION".equals(e.get("entityType")))
                            .count();
                    ComplexityClassifier.FileClassification fc =
                            ComplexityClassifier.classify(topFile, entityCount, methodCount);
                    assertNotNull(fc.tier());
                    assertNotNull(fc.reason());
                }
            }
        }

        // 6. Complexity routing for whole project
        ComplexityClassifier.ProjectClassification routing =
                ComplexityClassifier.classifyProject(PROJECT_ID, indexDir);
        int totalRouted = routing.fastCount() + routing.balancedCount() + routing.powerfulCount();
        assertTrue(totalRouted > 0, "Routing should classify files");
    }

    // -----------------------------------------------------------------------
    // 6. Graph relations bridge Java and splan
    // -----------------------------------------------------------------------

    @Test
    void testGraphRelationsExistForJava() throws Exception {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            // UserService should have outgoing relations (CALLS to UserRepository methods)
            Map<String, Object> graph = db.getFileGraph(
                    "src/main/java/com/example/UserService.java");
            assertNotNull(graph);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entities =
                    (List<Map<String, Object>>) graph.get("entities");
            assertNotNull(entities);
            assertFalse(entities.isEmpty(), "UserService should have entities in file graph");
        }
    }

    @Test
    void testSymbolGraphForClass() throws Exception {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            Map<String, Object> graph = db.getSymbolGraph("com.example.UserService", 2);
            assertNotNull(graph);

            @SuppressWarnings("unchecked")
            Map<String, Object> entity = (Map<String, Object>) graph.get("entity");
            assertNotNull(entity, "Should find UserService entity");
            assertEquals("UserService", entity.get("name"));
        }
    }

    // -----------------------------------------------------------------------
    // 7. Intent classifier ↔ ranked search integration
    // -----------------------------------------------------------------------

    @Test
    void testAllIntentTypesProduceResults() throws Exception {
        // Verify that different intent types all work with the ranker
        Map<String, IntentClassifier.Intent> queries = Map.of(
                "user service", IntentClassifier.Intent.SEARCH,
                "bug in authenticate method", IntentClassifier.Intent.DEBUG,
                "how does AuthFilter work", IntentClassifier.Intent.EXPLAIN,
                "where is UserController", IntentClassifier.Intent.NAVIGATE
        );

        for (Map.Entry<String, IntentClassifier.Intent> entry : queries.entrySet()) {
            String query = entry.getKey();
            IntentClassifier.Intent expectedIntent = entry.getValue();

            CodeRelevanceRanker.RankedResults results =
                    CodeRelevanceRanker.rankedSearch(PROJECT_ID, query,
                            indexDir, tempDir, 5);

            assertEquals(expectedIntent, results.intent(),
                    "Query '" + query + "' should be classified as " + expectedIntent);
            // Results may be empty for some queries, but the pipeline should not throw
        }
    }

    // -----------------------------------------------------------------------
    // 8. Multi-file impact analysis
    // -----------------------------------------------------------------------

    @Test
    void testMultiFileImpactReport() throws Exception {
        List<String> changedFiles = List.of(
                "src/main/java/com/example/User.java",
                "src/main/java/com/example/UserRepository.java"
        );

        ImpactAnalyzer.ImpactReport report =
                ImpactAnalyzer.analyzeFiles(changedFiles, indexDir, 0);

        assertNotNull(report);
        assertEquals(2, report.fileImpacts().size());

        String formatted = ImpactAnalyzer.formatReport(report);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // Should mention both changed files
        assertTrue(formatted.contains("User.java") || formatted.contains("user"),
                "Report should reference changed files");
    }

    // -----------------------------------------------------------------------
    // 9. CodeTokenizer ↔ CodeRelevanceRanker integration
    // -----------------------------------------------------------------------

    @Test
    void testTokenizerFeedsRanker() {
        // Verify that tokens produced by CodeTokenizer match what the ranker uses
        List<String> queryTokens = CodeTokenizer.tokenize("getUserByEmail");
        assertTrue(queryTokens.contains("user"), "Should tokenize camelCase 'user'");
        assertTrue(queryTokens.contains("email"), "Should tokenize camelCase 'email'");

        // These tokens should match UserService.getUserByEmail entities
        List<String> nameTokens = CodeTokenizer.tokenizeSymbol("getUserByEmail");
        assertTrue(nameTokens.contains("get"));
        assertTrue(nameTokens.contains("user"));
        assertTrue(nameTokens.contains("email"));

        // Path tokens should include directory components
        List<String> pathTokens = CodeTokenizer.tokenizePath(
                "src/main/java/com/example/UserService");
        assertTrue(pathTokens.contains("example"));
    }

    // -----------------------------------------------------------------------
    // 10. Weight profiles affect scoring
    // -----------------------------------------------------------------------

    @Test
    void testIntegrateIntentBoostsGraphWeight() {
        IntentClassifier.WeightProfile integWp =
                IntentClassifier.getWeights(IntentClassifier.Intent.INTEGRATE);
        IntentClassifier.WeightProfile searchWp =
                IntentClassifier.getWeights(IntentClassifier.Intent.SEARCH);

        assertTrue(integWp.graphBoost() > searchWp.graphBoost(),
                "INTEGRATE intent should boost graph weight more than SEARCH");
    }

    @Test
    void testNavigateIntentBoostsPathWeight() {
        IntentClassifier.WeightProfile navWp =
                IntentClassifier.getWeights(IntentClassifier.Intent.NAVIGATE);
        IntentClassifier.WeightProfile searchWp =
                IntentClassifier.getWeights(IntentClassifier.Intent.SEARCH);

        assertTrue(navWp.pathMatch() > searchWp.pathMatch(),
                "NAVIGATE intent should boost path weight more than SEARCH");
    }
}
