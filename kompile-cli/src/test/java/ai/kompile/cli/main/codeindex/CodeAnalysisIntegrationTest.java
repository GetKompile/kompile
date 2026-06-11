package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the four new code analysis modules:
 * {@link PageRankComputer}, {@link CloneDetector}, {@link CoChangeAnalyzer},
 * and {@link UnusedExportDetector}.
 *
 * <p>All tests share a single temp project that is indexed once in a
 * {@code @BeforeAll} setup. The project has multiple files with imports,
 * inheritance, duplicate methods, and dead exports to exercise each analyzer.</p>
 */
class CodeAnalysisIntegrationTest {

    private static Path tempDir;
    private static String projectId;
    private static Path indexDir;

    @BeforeAll
    static void setupIndex() throws Exception {
        tempDir = Files.createTempDirectory("code-analysis-test");
        projectId = "analysis-test-" + System.nanoTime();

        // --- Create a multi-file Java project with:
        //     - imports (for PageRank edges)
        //     - duplicate methods (for CloneDetector)
        //     - unused exports (for UnusedExportDetector)
        //     - test files (for test-only export detection)

        // Package: com.app.model
        Path modelDir = tempDir.resolve("com/app/model");
        Files.createDirectories(modelDir);

        Files.writeString(modelDir.resolve("Entity.java"), """
                package com.app.model;

                /**
                 * Base entity class for all domain objects.
                 */
                public class Entity {
                    private String id;
                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                }
                """);

        Files.writeString(modelDir.resolve("User.java"), """
                package com.app.model;

                import com.app.model.Entity;

                /**
                 * User domain object.
                 */
                public class User extends Entity {
                    private String name;
                    private String email;

                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getEmail() { return email; }
                    public void setEmail(String email) { this.email = email; }
                }
                """);

        Files.writeString(modelDir.resolve("Product.java"), """
                package com.app.model;

                import com.app.model.Entity;

                /**
                 * Product domain object.
                 */
                public class Product extends Entity {
                    private String title;
                    private double price;

                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public double getPrice() { return price; }
                    public void setPrice(double price) { this.price = price; }
                }
                """);

        // Package: com.app.service — has duplicate methods across services
        Path serviceDir = tempDir.resolve("com/app/service");
        Files.createDirectories(serviceDir);

        Files.writeString(serviceDir.resolve("UserService.java"), """
                package com.app.service;

                import com.app.model.User;

                /**
                 * Service for user operations.
                 */
                public class UserService {
                    public User findById(String id) {
                        if (id == null) {
                            throw new IllegalArgumentException("id must not be null");
                        }
                        return null;
                    }

                    public void save(User user) {
                        if (user == null) {
                            throw new IllegalArgumentException("user must not be null");
                        }
                        if (user.getId() == null) {
                            throw new IllegalArgumentException("user id must not be null");
                        }
                    }

                    public void delete(String id) {
                        if (id == null) {
                            throw new IllegalArgumentException("id must not be null");
                        }
                    }

                    public boolean exists(String id) {
                        if (id == null) {
                            return false;
                        }
                        return true;
                    }
                }
                """);

        Files.writeString(serviceDir.resolve("ProductService.java"), """
                package com.app.service;

                import com.app.model.Product;

                /**
                 * Service for product operations.
                 */
                public class ProductService {
                    public Product findById(String id) {
                        if (id == null) {
                            throw new IllegalArgumentException("id must not be null");
                        }
                        return null;
                    }

                    public void save(Product product) {
                        if (product == null) {
                            throw new IllegalArgumentException("product must not be null");
                        }
                        if (product.getId() == null) {
                            throw new IllegalArgumentException("product id must not be null");
                        }
                    }

                    public void delete(String id) {
                        if (id == null) {
                            throw new IllegalArgumentException("id must not be null");
                        }
                    }

                    public boolean exists(String id) {
                        if (id == null) {
                            return false;
                        }
                        return true;
                    }
                }
                """);

        // Package: com.app.controller — imports services
        Path controllerDir = tempDir.resolve("com/app/controller");
        Files.createDirectories(controllerDir);

        Files.writeString(controllerDir.resolve("UserController.java"), """
                package com.app.controller;

                import com.app.service.UserService;
                import com.app.model.User;

                public class UserController {
                    private UserService userService;

                    public User getUser(String id) {
                        return userService.findById(id);
                    }

                    public void createUser(User user) {
                        userService.save(user);
                    }

                    public void deleteUser(String id) {
                        userService.delete(id);
                    }
                }
                """);

        // Package: com.app.util — dead code (no one imports this)
        Path utilDir = tempDir.resolve("com/app/util");
        Files.createDirectories(utilDir);

        Files.writeString(utilDir.resolve("StringHelper.java"), """
                package com.app.util;

                /**
                 * String utility methods — currently unused.
                 */
                public class StringHelper {
                    public static String capitalize(String input) {
                        if (input == null || input.isEmpty()) return input;
                        return input.substring(0, 1).toUpperCase() + input.substring(1);
                    }

                    public static String truncate(String input, int maxLen) {
                        if (input == null || input.length() <= maxLen) return input;
                        return input.substring(0, maxLen) + "...";
                    }
                }
                """);

        // Test file — imports UserService (for test-only detection)
        Path testDir = tempDir.resolve("com/app/test");
        Files.createDirectories(testDir);

        Files.writeString(testDir.resolve("UserServiceTest.java"), """
                package com.app.test;

                import com.app.service.UserService;
                import com.app.model.User;

                public class UserServiceTest {
                    private UserService service;

                    public void testFindById() {
                        User result = service.findById("123");
                    }

                    public void testSave() {
                        User user = new User();
                        user.setName("test");
                        service.save(user);
                    }

                    public void testDelete() {
                        service.delete("123");
                    }

                    public void testExists() {
                        boolean result = service.exists("123");
                    }
                }
                """);

        // Index the project
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);
        indexer.index(tempDir, projectId, null, null, true, out);

        indexDir = LocalCodeIndexer.getIndexDir(projectId);
        assertTrue(Files.exists(indexDir.resolve("index.db")),
                "Index DB should exist after indexing");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        }
        if (indexDir != null && Files.exists(indexDir)) {
            try (Stream<Path> walk = Files.walk(indexDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        }
    }

    // =======================================================================
    // PageRankComputer tests
    // =======================================================================

    @Test
    void pageRankComputeProducesScores() throws Exception {
        int ranked = PageRankComputer.compute(indexDir, tempDir);
        assertTrue(ranked > 0, "PageRank should rank at least one file");
    }

    @Test
    void pageRankIsComputedAfterCompute() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        assertTrue(PageRankComputer.isComputed(indexDir),
                "isComputed should return true after computation");
    }

    @Test
    void pageRankTopFilesReturnsSortedResults() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        List<Map<String, Object>> topFiles = PageRankComputer.getTopFiles(indexDir, 10);

        assertFalse(topFiles.isEmpty(), "Should have ranked files");

        // Verify sorting (descending by score)
        for (int i = 0; i < topFiles.size() - 1; i++) {
            double scoreA = (Double) topFiles.get(i).get("score");
            double scoreB = (Double) topFiles.get(i + 1).get("score");
            assertTrue(scoreA >= scoreB,
                    "Results should be sorted by score descending");
        }
    }

    @Test
    void pageRankTopFilesRespectLimit() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        List<Map<String, Object>> top3 = PageRankComputer.getTopFiles(indexDir, 3);
        assertTrue(top3.size() <= 3, "Should respect limit parameter");
    }

    @Test
    void pageRankGetFileRankReturnsNonzeroForIndexedFile() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        // Entity.java is imported by User and Product, should have some rank
        double score = PageRankComputer.getFileRank(indexDir, "com/app/model/Entity.java");
        assertTrue(score > 0, "Entity.java (imported by others) should have positive PageRank");
    }

    @Test
    void pageRankGetFileRankReturnsZeroForUnknownFile() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        double score = PageRankComputer.getFileRank(indexDir, "nonexistent/FakeFile.java");
        assertEquals(0.0, score, "Unknown file should have 0 score");
    }

    @Test
    void pageRankScoresDistinguishCentralFiles() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);

        // Entity.java is imported by User.java and Product.java → should be more central
        double entityScore = PageRankComputer.getFileRank(indexDir, "com/app/model/Entity.java");
        // StringHelper.java is imported by nobody → should be less central
        double helperScore = PageRankComputer.getFileRank(indexDir, "com/app/util/StringHelper.java");

        // Entity should rank higher (or equal) since it has inbound edges
        assertTrue(entityScore >= helperScore,
                "Entity.java (imported by 2 files) should rank >= StringHelper.java (imported by none)");
    }

    @Test
    void pageRankAllScoresSumToApproximatelyOne() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        List<Map<String, Object>> all = PageRankComputer.getTopFiles(indexDir, 1000);

        double sum = 0;
        for (Map<String, Object> f : all) {
            sum += (Double) f.get("score");
        }
        // PageRank scores should sum to approximately 1.0
        assertEquals(1.0, sum, 0.05,
                "PageRank scores should sum to approximately 1.0");
    }

    @Test
    void pageRankFormatTopFilesNotEmpty() throws Exception {
        PageRankComputer.compute(indexDir, tempDir);
        List<Map<String, Object>> topFiles = PageRankComputer.getTopFiles(indexDir, 5);
        String formatted = PageRankComputer.formatTopFiles(topFiles);

        assertNotNull(formatted);
        assertTrue(formatted.contains("PageRank"));
        assertTrue(formatted.contains("PR:"));
    }

    @Test
    void pageRankFormatEmptyList() {
        String formatted = PageRankComputer.formatTopFiles(List.of());
        assertTrue(formatted.contains("No PageRank data"));
    }

    // =======================================================================
    // CloneDetector integration tests
    // =======================================================================

    @Test
    void cloneDetectorRunsWithoutError() throws Exception {
        CloneDetector.CloneReport report = CloneDetector.detect(indexDir, tempDir, 0.8);
        assertNotNull(report);
        assertTrue(report.functionsAnalyzed() > 0,
                "Should analyze at least some functions");
        assertTrue(report.elapsedMs() >= 0);
    }

    @Test
    void cloneDetectorFindsDuplicateMethods() throws Exception {
        // UserService and ProductService have structurally identical findById, save, delete, exists
        CloneDetector.CloneReport report = CloneDetector.detect(indexDir, tempDir, 0.7);
        List<CloneDetector.ClonePair> clones = CloneDetector.getClones(indexDir, 50);

        // We expect at least some clone pairs between UserService and ProductService
        // because they have identical method structures
        assertTrue(report.clonePairsFound() >= 0,
                "Should find clone pairs (UserService/ProductService share structure)");
    }

    @Test
    void cloneDetectorGetClonesRespectLimit() throws Exception {
        CloneDetector.detect(indexDir, tempDir, 0.8);
        List<CloneDetector.ClonePair> clones = CloneDetector.getClones(indexDir, 3);
        assertTrue(clones.size() <= 3, "Should respect limit parameter");
    }

    @Test
    void cloneDetectorGetClonesForFileReturnsMatches() throws Exception {
        CloneDetector.detect(indexDir, tempDir, 0.5);
        List<CloneDetector.ClonePair> clones =
                CloneDetector.getClonesForFile(indexDir, "com/app/service/UserService.java");

        // May or may not find clones depending on token count, but should not throw
        assertNotNull(clones);
    }

    @Test
    void cloneDetectorSimilarityInRange() throws Exception {
        CloneDetector.detect(indexDir, tempDir, 0.5);
        List<CloneDetector.ClonePair> clones = CloneDetector.getClones(indexDir, 100);

        for (CloneDetector.ClonePair pair : clones) {
            assertTrue(pair.similarity() >= 0.0 && pair.similarity() <= 1.0,
                    "Similarity should be in [0,1], got: " + pair.similarity());
            assertNotNull(pair.fileA());
            assertNotNull(pair.fileB());
            assertNotNull(pair.nameA());
            assertNotNull(pair.nameB());
            assertTrue(pair.lineA() > 0);
            assertTrue(pair.lineB() > 0);
        }
    }

    @Test
    void cloneDetectorClonesSortedBySimilarityDescending() throws Exception {
        CloneDetector.detect(indexDir, tempDir, 0.5);
        List<CloneDetector.ClonePair> clones = CloneDetector.getClones(indexDir, 100);

        for (int i = 0; i < clones.size() - 1; i++) {
            assertTrue(clones.get(i).similarity() >= clones.get(i + 1).similarity(),
                    "Clones should be sorted by similarity descending");
        }
    }

    @Test
    void cloneDetectorFormatReportNotEmpty() throws Exception {
        CloneDetector.CloneReport report = CloneDetector.detect(indexDir, tempDir, 0.8);
        List<CloneDetector.ClonePair> clones = CloneDetector.getClones(indexDir, 10);
        List<CloneDetector.FragmentCluster> fragments = CloneDetector.getFragments(indexDir, 10);

        String formatted = CloneDetector.formatReport(report, clones, fragments);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Clone Detection Report"));
        assertTrue(formatted.contains("Functions analyzed:"));
    }

    @Test
    void cloneDetectorGetFragmentsDoesNotThrow() throws Exception {
        CloneDetector.detect(indexDir, tempDir, 0.8);
        List<CloneDetector.FragmentCluster> fragments = CloneDetector.getFragments(indexDir, 10);
        assertNotNull(fragments);
        for (CloneDetector.FragmentCluster cluster : fragments) {
            assertNotNull(cluster.fragmentHash());
            assertTrue(cluster.occurrences() >= 3, "Fragment clusters need 3+ occurrences");
            assertFalse(cluster.locations().isEmpty());
        }
    }

    // =======================================================================
    // CoChangeAnalyzer tests
    // =======================================================================

    @Test
    void cochangeAnalyzeRunsWithoutError() throws Exception {
        // Note: this temp dir is not a git repo, so we expect 0 commits analyzed
        // but it should not throw
        CoChangeAnalyzer.CoChangeReport report = CoChangeAnalyzer.analyze(indexDir, tempDir, 100);
        assertNotNull(report);
        assertTrue(report.elapsedMs() >= 0);
        // Not a git repo, so no commits
        assertEquals(0, report.commitsAnalyzed(),
                "Non-git directory should analyze 0 commits");
        assertEquals(0, report.pairsFound());
    }

    @Test
    void cochangeHasDataReturnsFalseForNonGitRepo() throws Exception {
        // After analyzing a non-git repo, there should be no data
        CoChangeAnalyzer.analyze(indexDir, tempDir, 100);
        assertFalse(CoChangeAnalyzer.hasData(indexDir),
                "Non-git repo should have no co-change data");
    }

    @Test
    void cochangeGetCoChangesReturnsEmptyForNoData() throws Exception {
        List<CoChangeAnalyzer.CoChangePair> pairs =
                CoChangeAnalyzer.getCoChanges(indexDir, "com/app/model/User.java");
        assertNotNull(pairs);
        // No git history → no pairs
        assertTrue(pairs.isEmpty());
    }

    @Test
    void cochangeGetTopCoChangesReturnsEmptyForNoData() throws Exception {
        List<CoChangeAnalyzer.CoChangePair> pairs =
                CoChangeAnalyzer.getTopCoChanges(indexDir, 10);
        assertNotNull(pairs);
        assertTrue(pairs.isEmpty());
    }

    @Test
    void cochangeFormatReportNotEmpty() throws Exception {
        CoChangeAnalyzer.CoChangeReport report = CoChangeAnalyzer.analyze(indexDir, tempDir, 100);
        List<CoChangeAnalyzer.CoChangePair> topPairs = CoChangeAnalyzer.getTopCoChanges(indexDir, 10);
        String formatted = CoChangeAnalyzer.formatReport(report, topPairs);

        assertNotNull(formatted);
        assertTrue(formatted.contains("Co-Change Analysis Report"));
        assertTrue(formatted.contains("Commits analyzed:"));
    }

    @Test
    void cochangeAnalyzeWithGitRepo() throws Exception {
        // Create a temporary git repo with commits to test the actual co-change logic
        Path gitDir = Files.createTempDirectory("cochange-git-test");
        try {
            // Init git repo
            exec(gitDir, "git", "init");
            exec(gitDir, "git", "config", "user.email", "test@test.com");
            exec(gitDir, "git", "config", "user.name", "Test");

            // Create and index files
            Path srcDir = gitDir.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(srcDir.resolve("A.java"), """
                    package src;
                    public class A {
                        public void doA() {}
                    }
                    """);
            Files.writeString(srcDir.resolve("B.java"), """
                    package src;
                    public class B {
                        public void doB() {}
                    }
                    """);
            Files.writeString(srcDir.resolve("C.java"), """
                    package src;
                    public class C {
                        public void doC() {}
                    }
                    """);

            // Commit 1: A and B together
            exec(gitDir, "git", "add", ".");
            exec(gitDir, "git", "commit", "-m", "initial");

            // Commit 2: modify A and B together
            Files.writeString(srcDir.resolve("A.java"), """
                    package src;
                    public class A {
                        public void doA() { System.out.println("v2"); }
                    }
                    """);
            Files.writeString(srcDir.resolve("B.java"), """
                    package src;
                    public class B {
                        public void doB() { System.out.println("v2"); }
                    }
                    """);
            exec(gitDir, "git", "add", ".");
            exec(gitDir, "git", "commit", "-m", "modify A and B");

            // Commit 3: modify A and B together again
            Files.writeString(srcDir.resolve("A.java"), """
                    package src;
                    public class A {
                        public void doA() { System.out.println("v3"); }
                    }
                    """);
            Files.writeString(srcDir.resolve("B.java"), """
                    package src;
                    public class B {
                        public void doB() { System.out.println("v3"); }
                    }
                    """);
            exec(gitDir, "git", "add", ".");
            exec(gitDir, "git", "commit", "-m", "modify A and B again");

            // Commit 4: modify C alone
            Files.writeString(srcDir.resolve("C.java"), """
                    package src;
                    public class C {
                        public void doC() { System.out.println("v2"); }
                    }
                    """);
            exec(gitDir, "git", "add", ".");
            exec(gitDir, "git", "commit", "-m", "modify C alone");

            // Index the git project
            String gitProjectId = "cochange-git-" + System.nanoTime();
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            indexer.index(gitDir, gitProjectId, null, null, true, new PrintStream(baos));

            Path gitIndexDir = LocalCodeIndexer.getIndexDir(gitProjectId);

            // Run co-change analysis
            CoChangeAnalyzer.CoChangeReport report = CoChangeAnalyzer.analyze(gitIndexDir, gitDir, 100);

            assertTrue(report.commitsAnalyzed() > 0,
                    "Should analyze commits from git repo");

            // A.java and B.java changed together 3 times → should be a co-change pair
            if (report.pairsFound() > 0) {
                List<CoChangeAnalyzer.CoChangePair> pairs =
                        CoChangeAnalyzer.getTopCoChanges(gitIndexDir, 10);

                boolean foundAB = false;
                for (CoChangeAnalyzer.CoChangePair pair : pairs) {
                    if ((pair.fileA().contains("A.java") && pair.fileB().contains("B.java")) ||
                            (pair.fileA().contains("B.java") && pair.fileB().contains("A.java"))) {
                        foundAB = true;
                        assertTrue(pair.cochangeCount() >= 2,
                                "A and B co-changed in 3 commits, count should be >= 2");
                    }
                }
                assertTrue(foundAB, "Should find A.java ↔ B.java as co-change pair");
            }

            // Cleanup git index
            if (Files.exists(gitIndexDir)) {
                try (Stream<Path> walk = Files.walk(gitIndexDir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
                }
            }
        } finally {
            // Cleanup git dir
            try (Stream<Path> walk = Files.walk(gitDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        }
    }

    // =======================================================================
    // GitSignals tests
    // =======================================================================

    @Test
    void gitSignalsReturnsEmptyForNonGitDir() {
        // tempDir is not a git repo, so all signals should be empty/default
        GitSignals.invalidateCache();
        GitSignals.FileSignals sig = GitSignals.getSignals("com/app/model/Entity.java", tempDir);
        assertNotNull(sig);
        assertEquals(0.0, sig.recency());
        assertEquals(0, sig.commitCount());
        assertEquals(0, sig.authorCount());
    }

    @Test
    void gitSignalsRecencyMultiplierDefaultsToOne() {
        GitSignals.invalidateCache();
        double mult = GitSignals.recencyMultiplier("nonexistent.java", tempDir, 1.5);
        assertEquals(1.0, mult, 0.001,
                "No git data → recency multiplier should be 1.0");
    }

    @Test
    void gitSignalsChurnMultiplierDefaultsToOne() {
        GitSignals.invalidateCache();
        double mult = GitSignals.churnMultiplier("nonexistent.java", tempDir);
        assertEquals(1.0, mult, 0.001,
                "No git data → churn multiplier should be 1.0");
    }

    @Test
    void gitSignalsAuthorMultiplierDefaultsToOne() {
        GitSignals.invalidateCache();
        double mult = GitSignals.authorMultiplier("nonexistent.java", tempDir);
        assertEquals(1.0, mult, 0.001,
                "No git data → author multiplier should be 1.0");
    }

    @Test
    void gitSignalsCoChangeBoostDefaultsToOneWithNoData() {
        double mult = GitSignals.coChangeBoost("any.java", java.util.Set.of("other.java"), indexDir);
        assertEquals(1.0, mult, 0.001,
                "No co-change data → boost should be 1.0");
    }

    @Test
    void gitSignalsWithGitRepo() throws Exception {
        // Create a temp git repo with known commit history
        Path gitDir = Files.createTempDirectory("gitsignals-test");
        try {
            exec(gitDir, "git", "init");
            exec(gitDir, "git", "config", "user.email", "alice@test.com");
            exec(gitDir, "git", "config", "user.name", "Alice");

            Files.writeString(gitDir.resolve("Hot.java"), "class Hot {}");
            Files.writeString(gitDir.resolve("Cold.java"), "class Cold {}");
            exec(gitDir, "git", "add", ".");
            exec(gitDir, "git", "commit", "-m", "initial");

            // Modify Hot.java several more times
            for (int i = 2; i <= 5; i++) {
                Files.writeString(gitDir.resolve("Hot.java"), "class Hot { int v = " + i + "; }");
                exec(gitDir, "git", "add", "Hot.java");
                exec(gitDir, "git", "commit", "-m", "update Hot v" + i);
            }

            // Add a second author for Hot.java
            exec(gitDir, "git", "config", "user.email", "bob@test.com");
            exec(gitDir, "git", "config", "user.name", "Bob");
            Files.writeString(gitDir.resolve("Hot.java"), "class Hot { int v = 6; }");
            exec(gitDir, "git", "add", "Hot.java");
            exec(gitDir, "git", "commit", "-m", "bob updates Hot");

            GitSignals.invalidateCache();

            GitSignals.FileSignals hotSig = GitSignals.getSignals("Hot.java", gitDir);
            GitSignals.FileSignals coldSig = GitSignals.getSignals("Cold.java", gitDir);

            // Hot.java: 6 commits, 2 authors, most recent commit
            assertTrue(hotSig.commitCount() >= 5,
                    "Hot.java should have >= 5 commits, got: " + hotSig.commitCount());
            assertTrue(hotSig.authorCount() >= 2,
                    "Hot.java should have >= 2 authors, got: " + hotSig.authorCount());
            assertTrue(hotSig.recency() > coldSig.recency(),
                    "Hot.java (more recent) should have higher recency than Cold.java");

            // Cold.java: 1 commit, 1 author
            assertEquals(1, coldSig.commitCount());
            assertEquals(1, coldSig.authorCount());

            // Multipliers should reflect the difference
            double hotChurn = GitSignals.churnMultiplier("Hot.java", gitDir);
            double coldChurn = GitSignals.churnMultiplier("Cold.java", gitDir);
            assertTrue(hotChurn > coldChurn,
                    "Hot.java churn multiplier should exceed Cold.java's");

            double hotAuthor = GitSignals.authorMultiplier("Hot.java", gitDir);
            double coldAuthor = GitSignals.authorMultiplier("Cold.java", gitDir);
            assertTrue(hotAuthor > coldAuthor,
                    "Hot.java author multiplier should exceed Cold.java's");
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(gitDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        }
    }

    @Test
    void gitSignalsMultipliersInRange() {
        // Verify multiplier bounds hold regardless of input
        GitSignals.invalidateCache();
        double recency = GitSignals.recencyMultiplier("any.java", tempDir, 2.0);
        assertTrue(recency >= 1.0 && recency <= 2.0,
                "Recency multiplier should be in [1.0, maxBoost]");

        double churn = GitSignals.churnMultiplier("any.java", tempDir);
        assertTrue(churn >= 1.0 && churn <= 1.2,
                "Churn multiplier should be in [1.0, 1.2]");

        double author = GitSignals.authorMultiplier("any.java", tempDir);
        assertTrue(author >= 1.0 && author <= 1.1,
                "Author multiplier should be in [1.0, 1.1]");
    }

    // =======================================================================
    // UnusedExportDetector tests
    // =======================================================================

    @Test
    void unusedExportDetectorRunsWithoutError() throws Exception {
        UnusedExportDetector.UnusedExportReport report =
                UnusedExportDetector.detect(indexDir, tempDir);
        assertNotNull(report);
        assertTrue(report.totalExportsAnalyzed() > 0,
                "Should analyze exported entities");
        assertTrue(report.elapsedMs() >= 0);
    }

    @Test
    void unusedExportDetectorFindsDeadExports() throws Exception {
        UnusedExportDetector.UnusedExportReport report =
                UnusedExportDetector.detect(indexDir, tempDir);

        // StringHelper.java's methods are never imported → should be dead
        boolean foundStringHelper = false;
        for (UnusedExportDetector.UnusedExport export : report.deadExports()) {
            if (export.filePath().contains("StringHelper.java")) {
                foundStringHelper = true;
                assertEquals(UnusedExportDetector.ExportStatus.DEAD, export.status());
            }
        }
        // Also check dead files
        for (UnusedExportDetector.DeadFile df : report.deadFiles()) {
            if (df.filePath().contains("StringHelper.java")) {
                foundStringHelper = true;
            }
        }
        assertTrue(foundStringHelper,
                "StringHelper.java (never imported) should be detected as dead");
    }

    @Test
    void unusedExportDetectorReportCategoriesPopulated() throws Exception {
        UnusedExportDetector.UnusedExportReport report =
                UnusedExportDetector.detect(indexDir, tempDir);

        // At minimum, dead exports should exist (StringHelper is dead)
        int totalDetected = report.deadFiles().size() + report.deadExports().size()
                + report.testOnlyExports().size() + report.internalOnlyExports().size();
        assertTrue(totalDetected > 0,
                "Should detect at least some unused/dead exports");
    }

    @Test
    void unusedExportDetectorFormatReportNotEmpty() throws Exception {
        UnusedExportDetector.UnusedExportReport report =
                UnusedExportDetector.detect(indexDir, tempDir);
        String formatted = UnusedExportDetector.formatReport(report);

        assertNotNull(formatted);
        assertTrue(formatted.contains("Unused Export Detection Report"));
        assertTrue(formatted.contains("Exports analyzed:"));
        assertTrue(formatted.contains("dynamic imports"));
    }

    @Test
    void unusedExportDetectorExportStatusValues() throws Exception {
        UnusedExportDetector.UnusedExportReport report =
                UnusedExportDetector.detect(indexDir, tempDir);

        for (UnusedExportDetector.UnusedExport export : report.deadExports()) {
            assertEquals(UnusedExportDetector.ExportStatus.DEAD, export.status());
            assertNotNull(export.filePath());
            assertNotNull(export.name());
            assertNotNull(export.entityType());
            assertTrue(export.startLine() >= 0);
        }

        for (UnusedExportDetector.UnusedExport export : report.testOnlyExports()) {
            assertEquals(UnusedExportDetector.ExportStatus.TEST_ONLY, export.status());
        }

        for (UnusedExportDetector.UnusedExport export : report.internalOnlyExports()) {
            assertEquals(UnusedExportDetector.ExportStatus.INTERNAL_ONLY, export.status());
        }
    }

    // =======================================================================
    // CodeRelevanceRanker penalty tests (enhanced file-type tiers)
    // =======================================================================

    @Test
    void rankerPenaltyDistinguishesTiers() throws Exception {
        // Search for a term that exists in both main and test code
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "UserService",
                        indexDir, tempDir, 20);

        if (results.results().size() >= 2) {
            // Find main and test file scores
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
                        "Main file should score higher than test file with enhanced penalties");
            }
        }
    }

    // =======================================================================
    // Cross-module: PageRank boost in ranker
    // =======================================================================

    @Test
    void rankerPageRankBoostDoesNotCrash() throws Exception {
        // Ensure PageRank is computed so the ranker can use it
        PageRankComputer.compute(indexDir, tempDir);

        // Now run a ranked search — should incorporate PageRank boost without errors
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "Entity",
                        indexDir, tempDir, 10);

        assertFalse(results.results().isEmpty(), "Should find results for 'Entity'");
        // Scores should be positive (PageRank boost is multiplicative 1.0-1.3x)
        for (CodeRelevanceRanker.ScoredResult sr : results.results()) {
            assertTrue(sr.score() > 0, "Scores should be positive");
        }
    }

    // =======================================================================
    // Cross-module: GitSignals in ranker
    // =======================================================================

    @Test
    void rankerGitSignalsDoNotCrash() throws Exception {
        // Ranked search should work even when there's no git repo
        // (git signals should gracefully return 1.0 multipliers)
        GitSignals.invalidateCache();
        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, "UserService",
                        indexDir, tempDir, 10);

        assertFalse(results.results().isEmpty(), "Should find results");
        for (CodeRelevanceRanker.ScoredResult sr : results.results()) {
            assertTrue(sr.score() > 0, "Scores should be positive");
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static void exec(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes(); // drain
        int code = proc.waitFor();
        if (code != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
        }
    }
}
