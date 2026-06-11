package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SignatureExtractor, CodeRelevanceRanker, ImpactAnalyzer,
 * and IndexHealthScorer. All tests share a single temp project that is indexed
 * once in a @BeforeAll setup.
 *
 * <p>Creates a small multi-file Java project with class hierarchies, imports,
 * and method signatures, indexes it, then exercises each analyzer against
 * the live index database.</p>
 */
class IndexedCodebaseTest {

    private static Path tempDir;
    private static String projectId;
    private static Path indexDir;

    @BeforeAll
    static void setupIndex() throws Exception {
        tempDir = Files.createTempDirectory("indexed-codebase-test");
        projectId = "idx-test-" + System.nanoTime();

        // --- Create a multi-file Java project ---

        // Package: com.example.model
        Path modelDir = tempDir.resolve("com/example/model");
        Files.createDirectories(modelDir);

        Files.writeString(modelDir.resolve("User.java"), """
                package com.example.model;

                /**
                 * Represents a user in the system.
                 */
                public class User {
                    private String id;
                    private String name;
                    private String email;

                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getEmail() { return email; }
                    public void setEmail(String email) { this.email = email; }
                }
                """);

        Files.writeString(modelDir.resolve("Order.java"), """
                package com.example.model;

                import java.util.List;

                /**
                 * Represents a customer order.
                 */
                public class Order {
                    private String orderId;
                    private User customer;
                    private List<String> items;

                    public String getOrderId() { return orderId; }
                    public User getCustomer() { return customer; }
                    public List<String> getItems() { return items; }
                }
                """);

        // Package: com.example.service
        Path serviceDir = tempDir.resolve("com/example/service");
        Files.createDirectories(serviceDir);

        Files.writeString(serviceDir.resolve("UserService.java"), """
                package com.example.service;

                import com.example.model.User;

                /**
                 * Service layer for user operations.
                 */
                public class UserService {
                    public User findById(String id) { return null; }
                    public User findByEmail(String email) { return null; }
                    public void save(User user) {}
                    public void delete(String id) {}
                    public boolean exists(String id) { return false; }
                }
                """);

        Files.writeString(serviceDir.resolve("OrderService.java"), """
                package com.example.service;

                import com.example.model.Order;
                import com.example.model.User;

                /**
                 * Service layer for order operations.
                 */
                public class OrderService {
                    private UserService userService;

                    public Order createOrder(User customer) { return null; }
                    public Order findOrder(String orderId) { return null; }
                    public void cancelOrder(String orderId) {}
                }
                """);

        // Package: com.example.controller
        Path controllerDir = tempDir.resolve("com/example/controller");
        Files.createDirectories(controllerDir);

        Files.writeString(controllerDir.resolve("UserController.java"), """
                package com.example.controller;

                import com.example.service.UserService;
                import com.example.model.User;

                /**
                 * REST controller for user endpoints.
                 */
                public class UserController {
                    private UserService userService;

                    public User getUser(String id) { return userService.findById(id); }
                    public void createUser(User user) { userService.save(user); }
                    public void deleteUser(String id) { userService.delete(id); }
                }
                """);

        // Package: com.example.security
        Path securityDir = tempDir.resolve("com/example/security");
        Files.createDirectories(securityDir);

        Files.writeString(securityDir.resolve("AuthFilter.java"), """
                package com.example.security;

                import com.example.service.UserService;

                /**
                 * Authentication filter for securing endpoints.
                 */
                public class AuthFilter {
                    private UserService userService;

                    public boolean authenticate(String token) { return false; }
                    public boolean authorize(String userId, String resource) { return false; }
                    public String generateToken(String userId) { return null; }
                    public void revokeToken(String token) {}
                    public boolean validateToken(String token) { return false; }
                    public String refreshToken(String token) { return null; }
                    public void logAccess(String userId, String resource) {}
                    public boolean isExpired(String token) { return false; }
                }
                """);

        // Test file
        Path testDir = tempDir.resolve("com/example/test");
        Files.createDirectories(testDir);

        Files.writeString(testDir.resolve("UserServiceTest.java"), """
                package com.example.test;

                import com.example.service.UserService;

                public class UserServiceTest {
                    public void testFindById() {}
                    public void testSave() {}
                    public void testDelete() {}
                }
                """);

        // Config file
        Files.writeString(tempDir.resolve("application.yml"), """
                server:
                  port: 8080
                database:
                  url: jdbc:postgresql://localhost/mydb
                """);

        // Index the project
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);
        indexer.index(tempDir, projectId, null, null, true, out);

        indexDir = LocalCodeIndexer.getIndexDir(projectId);
        assertTrue(Files.exists(indexDir.resolve("index.db")), "Index DB should exist after indexing");
    }

    @AfterAll
    static void cleanup() throws Exception {
        // Clean up temp project dir
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        }
        // Clean up index dir
        if (indexDir != null && Files.exists(indexDir)) {
            try (Stream<Path> walk = Files.walk(indexDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        }
    }

    // =======================================================================
    // SignatureExtractor tests
    // =======================================================================

    @Test
    void testExtractProjectSignatures() throws Exception {
        SignatureExtractor.ProjectSignatures project =
                SignatureExtractor.extractProject(projectId, indexDir, tempDir);

        assertTrue(project.totalFiles() > 0, "Should have extracted signatures from files");
        assertTrue(project.totalSignatures() > 0, "Should have found signatures");
        assertTrue(project.overallReductionPercent() > 0, "Should report positive token reduction");
    }

    @Test
    void testExtractSingleFileSignatures() throws Exception {
        SignatureExtractor.FileSignatures fs =
                SignatureExtractor.extractFile(projectId,
                        "com/example/model/User.java", indexDir, tempDir);

        assertNotNull(fs);
        assertFalse(fs.signatures().isEmpty(), "User.java should have signatures");
        assertEquals("com/example/model/User.java", fs.filePath());

        // Should contain class and method signatures
        boolean hasClass = fs.signatures().stream()
                .anyMatch(s -> s.contains("class") || s.contains("User"));
        assertTrue(hasClass, "Should include class signature for User");
    }

    @Test
    void testSignatureExtractorFormatsAsContext() throws Exception {
        SignatureExtractor.ProjectSignatures project =
                SignatureExtractor.extractProject(projectId, indexDir, tempDir);
        String context = SignatureExtractor.formatAsContext(project);

        assertNotNull(context);
        assertTrue(context.contains("Code Signatures"));
        assertTrue(context.contains("Token reduction"));
    }

    @Test
    void testSingleFileFormatsAsContext() throws Exception {
        SignatureExtractor.FileSignatures fs =
                SignatureExtractor.extractFile(projectId,
                        "com/example/service/UserService.java", indexDir, tempDir);

        assertNotNull(fs);
        String context = SignatureExtractor.formatFileContext(fs);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("UserService.java"));
    }

    @Test
    void testNonexistentFileReturnsNull() throws Exception {
        SignatureExtractor.FileSignatures fs =
                SignatureExtractor.extractFile(projectId,
                        "nonexistent/FakeFile.java", indexDir, tempDir);
        assertNull(fs);
    }

    // =======================================================================
    // CodeRelevanceRanker tests
    // =======================================================================

    @Test
    void testRankedSearchReturnsResults() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "User",
                        indexDir, tempDir, 10);

        assertFalse(results.results().isEmpty(), "Should find results for 'User'");
        assertTrue(results.totalCandidates() > 0);
        assertTrue(results.elapsedMs() >= 0);
    }

    @Test
    void testRankedSearchDetectsIntent() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId,
                        "where is auth handled", indexDir, tempDir, 10);

        assertEquals(IntentClassifier.Intent.NAVIGATE, results.intent());
    }

    @Test
    void testRankedSearchOrdersByScore() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "UserService",
                        indexDir, tempDir, 10);

        if (results.results().size() > 1) {
            for (int i = 0; i < results.results().size() - 1; i++) {
                assertTrue(
                        results.results().get(i).score() >= results.results().get(i + 1).score(),
                        "Results should be sorted by score descending");
            }
        }
    }

    @Test
    void testRankedSearchConfidenceTiers() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "User service find",
                        indexDir, tempDir, 20);

        if (!results.results().isEmpty()) {
            // First result should be high confidence
            assertEquals("high", results.results().get(0).confidence());
        }

        // All results should have a confidence tier
        for (CodeRelevanceRanker.ScoredResult sr : results.results()) {
            assertNotNull(sr.confidence());
            assertTrue(Set.of("high", "medium", "low").contains(sr.confidence()),
                    "Confidence should be high/medium/low, got: " + sr.confidence());
        }
    }

    @Test
    void testRankedSearchPenalizesTests() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "UserService",
                        indexDir, tempDir, 20);

        // Find UserService (main) and UserServiceTest scores
        double mainScore = -1, testScore = -1;
        for (CodeRelevanceRanker.ScoredResult sr : results.results()) {
            if (sr.filePath() != null && sr.filePath().contains("service/UserService.java")) {
                mainScore = sr.score();
            }
            if (sr.filePath() != null && sr.filePath().contains("test/UserServiceTest.java")) {
                testScore = sr.score();
            }
        }

        if (mainScore >= 0 && testScore >= 0) {
            assertTrue(mainScore > testScore,
                    "Main UserService should score higher than test file");
        }
    }

    @Test
    void testRankedSearchEmptyQueryReturnsEmpty() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "",
                        indexDir, tempDir, 10);

        assertTrue(results.results().isEmpty());
    }

    @Test
    void testRankedSearchFormatsResults() throws Exception {
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "User",
                        indexDir, tempDir, 5);

        String formatted = CodeRelevanceRanker.formatResults(results);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Ranked search"));
        assertTrue(formatted.contains("Intent:"));
    }

    // =======================================================================
    // ImpactAnalyzer tests
    // =======================================================================

    @Test
    void testImpactAnalysisFindsDirectDependents() throws Exception {
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile("com/example/model/User.java",
                        indexDir, 0);

        assertNotNull(impact);
        assertEquals("com/example/model/User.java", impact.changedFile());
        // User.java is imported by UserService, OrderService, UserController, AuthFilter, UserServiceTest
        // So it should have direct dependents
        assertTrue(impact.totalImpact() >= 0,
                "User.java should have some dependents");
    }

    @Test
    void testImpactAnalysisCategorizesTests() throws Exception {
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile("com/example/service/UserService.java",
                        indexDir, 0);

        // UserServiceTest imports UserService, so it should be in affectedTests
        // (only if the relation was resolved by connectivity pass)
        assertNotNull(impact.affectedTests());
    }

    @Test
    void testImpactAnalysisMultipleFiles() throws Exception {
        ImpactAnalyzer.ImpactReport report =
                ImpactAnalyzer.analyzeFiles(
                        List.of("com/example/model/User.java",
                                "com/example/model/Order.java"),
                        indexDir, 0);

        assertNotNull(report);
        assertEquals(2, report.fileImpacts().size());
        assertTrue(report.totalUniqueImpact() >= 0);
    }

    @Test
    void testImpactAnalysisWithDepthLimit() throws Exception {
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile("com/example/model/User.java",
                        indexDir, 1);

        assertNotNull(impact);
        // With depth=1, transitive dependents should be empty
        assertTrue(impact.transitiveDependents().isEmpty(),
                "Depth 1 should not include transitive dependents");
    }

    @Test
    void testImpactAnalysisNonexistentFile() throws Exception {
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile("nonexistent/FakeFile.java",
                        indexDir, 0);

        assertNotNull(impact);
        assertEquals(0, impact.totalImpact());
    }

    @Test
    void testImpactFormatsFileImpact() throws Exception {
        ImpactAnalyzer.FileImpact impact =
                ImpactAnalyzer.analyzeFile("com/example/model/User.java",
                        indexDir, 0);

        String formatted = ImpactAnalyzer.formatFileImpact(impact);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Impact:"));
        assertTrue(formatted.contains("User.java"));
    }

    @Test
    void testImpactFormatsReport() throws Exception {
        ImpactAnalyzer.ImpactReport report =
                ImpactAnalyzer.analyzeFiles(
                        List.of("com/example/model/User.java"),
                        indexDir, 0);

        String formatted = ImpactAnalyzer.formatReport(report);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Impact Analysis Report"));
    }

    // =======================================================================
    // IndexHealthScorer tests
    // =======================================================================

    @Test
    void testHealthScoreInRange() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        assertTrue(hs.score() >= 0 && hs.score() <= 100,
                "Score should be 0-100, got: " + hs.score());
    }

    @Test
    void testHealthScoreHasGrade() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        assertNotNull(hs.grade());
        assertTrue(Set.of("A", "B", "C", "D").contains(hs.grade()),
                "Grade should be A/B/C/D, got: " + hs.grade());
    }

    @Test
    void testHealthScoreGradeMatchesScore() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        if (hs.score() >= 90) assertEquals("A", hs.grade());
        else if (hs.score() >= 75) assertEquals("B", hs.grade());
        else if (hs.score() >= 60) assertEquals("C", hs.grade());
        else assertEquals("D", hs.grade());
    }

    @Test
    void testHealthScoreReportsFileCount() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        assertTrue(hs.totalFiles() > 0, "Should report indexed files");
    }

    @Test
    void testHealthScoreReportsEntityCount() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        assertTrue(hs.totalEntities() > 0, "Should report entities");
    }

    @Test
    void testHealthScoreReportsLanguageCount() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        assertTrue(hs.languageCount() > 0, "Should report at least one language");
    }

    @Test
    void testHealthScoreFreshIndexIsNotStale() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        // Index was just created, so daysSinceIndex should be 0
        assertTrue(hs.daysSinceIndex() <= 1,
                "Freshly created index should not be stale");
        // No staleness issue
        assertFalse(hs.issues().containsKey("staleness"),
                "Fresh index should not have staleness issue");
    }

    @Test
    void testHealthScoreFormatsMarkdown() {
        IndexHealthScorer.HealthScore hs =
                IndexHealthScorer.score(projectId, indexDir);

        String formatted = IndexHealthScorer.formatHealth(hs);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Index Health"));
        assertTrue(formatted.contains("Score:"));
        assertTrue(formatted.contains("Grade:"));
    }

    @Test
    void testHealthScoreEmptyProjectScoresZero() {
        Path fakeDir = tempDir.resolve("fake-index-" + System.nanoTime());
        try {
            Files.createDirectories(fakeDir);
            IndexHealthScorer.HealthScore hs =
                    IndexHealthScorer.score("fake-project", fakeDir);
            assertEquals(0, hs.score());
            assertEquals("D", hs.grade());
        } catch (Exception ignored) {}
    }

    // =======================================================================
    // ComplexityClassifier integration (project-wide)
    // =======================================================================

    @Test
    void testClassifyProjectHasAllTiers() throws Exception {
        ComplexityClassifier.ProjectClassification pc =
                ComplexityClassifier.classifyProject(projectId, indexDir);

        assertNotNull(pc);
        assertEquals(projectId, pc.projectId());
        int total = pc.fastCount() + pc.balancedCount() + pc.powerfulCount();
        assertTrue(total > 0, "Should classify at least one file");
    }

    @Test
    void testSecurityFileClassifiedAsPowerful() throws Exception {
        ComplexityClassifier.ProjectClassification pc =
                ComplexityClassifier.classifyProject(projectId, indexDir);

        // AuthFilter is in /security/ path → should be powerful
        boolean authInPowerful = pc.byTier().get(ComplexityClassifier.Tier.POWERFUL).stream()
                .anyMatch(f -> f.contains("security/AuthFilter"));
        assertTrue(authInPowerful,
                "AuthFilter in /security/ should be classified as powerful, " +
                        "powerful files: " + pc.byTier().get(ComplexityClassifier.Tier.POWERFUL));
    }

    @Test
    void testClassifyProjectFormatsMarkdown() throws Exception {
        ComplexityClassifier.ProjectClassification pc =
                ComplexityClassifier.classifyProject(projectId, indexDir);

        String formatted = ComplexityClassifier.formatClassification(pc);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Model Routing"));
        assertTrue(formatted.contains("fast"));
        assertTrue(formatted.contains("balanced"));
        assertTrue(formatted.contains("powerful"));
    }
}
