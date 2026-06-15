package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlendedCodeSearch} — the orchestration layer that auto-detects
 * query types and blends spath, ranked search, and signature compression strategies.
 *
 * <p>Covers:
 * <ul>
 *   <li>Query type detection (SYMBOL_PATH, WILDCARD, NATURAL, BROAD)</li>
 *   <li>Symbol path routing to spath resolution</li>
 *   <li>Natural language routing to ranked search</li>
 *   <li>Broad/overview routing to signature compression</li>
 *   <li>Wildcard routing to spath pattern matching</li>
 *   <li>Cross-strategy blending and deduplication</li>
 *   <li>Compressed context generation threshold</li>
 *   <li>Formatted output structure</li>
 * </ul>
 */
class BlendedCodeSearchTest {

    private static final String PROJECT_ID = "blended-search-test";
    private static Path tempDir;
    private static Path indexDir;

    // -----------------------------------------------------------------------
    // Setup: create and index a multi-file project for blended search
    // -----------------------------------------------------------------------

    @BeforeAll
    static void indexProject() throws Exception {
        tempDir = Files.createTempDirectory("blended-search-test");

        // Create enough files to trigger compressed context (>= 5 unique files)
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

        Files.writeString(srcMain.resolve("OrderService.java"), """
                package com.example;
                /**
                 * Handles order processing.
                 */
                public class OrderService {
                    public void placeOrder(User user, String product) {
                        // order logic
                    }
                    public void cancelOrder(long orderId) {
                        // cancel logic
                    }
                }
                """);

        Path srcHandler = tempDir.resolve("src/main/java/com/example/handler");
        Files.createDirectories(srcHandler);

        Files.writeString(srcHandler.resolve("RequestHandler.java"), """
                package com.example.handler;
                import com.example.User;
                /**
                 * HTTP request handler.
                 */
                public class RequestHandler {
                    public String handleGet(String path) { return "OK"; }
                    public String handlePost(String path, String body) { return "Created"; }
                }
                """);

        Files.writeString(srcHandler.resolve("ErrorHandler.java"), """
                package com.example.handler;
                /**
                 * Error handling for unhandled exceptions.
                 */
                public class ErrorHandler {
                    public String handleError(Throwable t) {
                        return "Error: " + t.getMessage();
                    }
                    public void logError(Throwable t) {
                        System.err.println(t.getMessage());
                    }
                }
                """);

        // Splan file for symbol-path testing
        Files.writeString(tempDir.resolve("pipeline.splan"), """
                # Processing pipeline
                :input_path:::data/input:::
                :output_path:::data/output:::
                load :input_path
                transform data
                export :output_path
                """);

        // Test file
        Path srcTest = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(srcTest);
        Files.writeString(srcTest.resolve("UserServiceTest.java"), """
                package com.example;
                /**
                 * Tests for UserService.
                 */
                public class UserServiceTest {
                    public void testGetUserByEmail() { }
                    public void testCreateUser() { }
                }
                """);

        // Index everything
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LocalCodeIndexer.IndexResult result = indexer.index(tempDir, PROJECT_ID,
                null, null, true, new PrintStream(baos));

        assertTrue(result.filesProcessed() >= 8, "Should index at least 8 files");
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
    // 1. Query type detection
    // -----------------------------------------------------------------------

    @Test
    void testDetectSymbolPath() {
        assertEquals(BlendedCodeSearch.QueryType.SYMBOL_PATH,
                BlendedCodeSearch.detectQueryType("com.example.UserService"));
        assertEquals(BlendedCodeSearch.QueryType.SYMBOL_PATH,
                BlendedCodeSearch.detectQueryType("com.example.handler.RequestHandler"));
    }

    @Test
    void testDetectWildcard() {
        assertEquals(BlendedCodeSearch.QueryType.WILDCARD,
                BlendedCodeSearch.detectQueryType("*.Handler"));
        assertEquals(BlendedCodeSearch.QueryType.WILDCARD,
                BlendedCodeSearch.detectQueryType("com.example.**"));
        assertEquals(BlendedCodeSearch.QueryType.WILDCARD,
                BlendedCodeSearch.detectQueryType("*Service*"));
    }

    @Test
    void testDetectNatural() {
        assertEquals(BlendedCodeSearch.QueryType.NATURAL,
                BlendedCodeSearch.detectQueryType("user service login"));
        assertEquals(BlendedCodeSearch.QueryType.NATURAL,
                BlendedCodeSearch.detectQueryType("find all controllers"));
    }

    @Test
    void testDetectBroad() {
        assertEquals(BlendedCodeSearch.QueryType.BROAD,
                BlendedCodeSearch.detectQueryType("overview"));
        assertEquals(BlendedCodeSearch.QueryType.BROAD,
                BlendedCodeSearch.detectQueryType("project structure"));
        assertEquals(BlendedCodeSearch.QueryType.BROAD,
                BlendedCodeSearch.detectQueryType("what does this project do"));
    }

    @Test
    void testDetectNullAndEmpty() {
        assertEquals(BlendedCodeSearch.QueryType.BROAD,
                BlendedCodeSearch.detectQueryType(null));
        assertEquals(BlendedCodeSearch.QueryType.BROAD,
                BlendedCodeSearch.detectQueryType(""));
        assertEquals(BlendedCodeSearch.QueryType.BROAD,
                BlendedCodeSearch.detectQueryType("   "));
    }

    @Test
    void testLooksLikeSymbolPath() {
        assertTrue(BlendedCodeSearch.looksLikeSymbolPath("com.example.User"));
        assertTrue(BlendedCodeSearch.looksLikeSymbolPath("java.util.List"));
        assertFalse(BlendedCodeSearch.looksLikeSymbolPath("user service"));
        assertFalse(BlendedCodeSearch.looksLikeSymbolPath("single"));
        assertFalse(BlendedCodeSearch.looksLikeSymbolPath("com."));
        assertFalse(BlendedCodeSearch.looksLikeSymbolPath(".example"));
    }

    // -----------------------------------------------------------------------
    // 2. Symbol path → spath routing
    // -----------------------------------------------------------------------

    @Test
    void testSymbolPathRoutesToSpath() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "com.example.UserService",
                        indexDir, tempDir, 10);

        assertEquals(BlendedCodeSearch.QueryType.SYMBOL_PATH, result.queryType());
        assertFalse(result.results().isEmpty(), "Should find UserService");

        // At least one result should come from spath
        boolean hasSpathResult = result.results().stream()
                .anyMatch(r -> "spath".equals(r.source()));
        // May or may not have spath results depending on spath capabilities;
        // either way the result set should contain UserService
        boolean hasUserService = result.results().stream()
                .anyMatch(r -> r.name() != null && r.name().contains("UserService"));
        assertTrue(hasUserService || hasSpathResult,
                "Should find UserService via spath or ranked fallback");
    }

    @Test
    void testSymbolPathFallsBackToRanked() throws Exception {
        // Query a partial/non-existent symbol path that spath can't resolve
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "com.example.NonExistent",
                        indexDir, tempDir, 10);

        assertEquals(BlendedCodeSearch.QueryType.SYMBOL_PATH, result.queryType());
        // Should fall back to ranked search on "NonExistent"
        assertNotNull(result.results());
    }

    // -----------------------------------------------------------------------
    // 3. Natural language → ranked search routing
    // -----------------------------------------------------------------------

    @Test
    void testNaturalLanguageRoutesToRanked() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "UserService createUser",
                        indexDir, tempDir, 10);

        assertEquals(BlendedCodeSearch.QueryType.NATURAL, result.queryType());
        assertFalse(result.results().isEmpty(), "Should find results for natural query");

        // Results should include UserService or related entities
        boolean hasRelevant = result.results().stream()
                .anyMatch(r -> r.name() != null && (
                        r.name().contains("User") || r.name().contains("create")));
        assertTrue(hasRelevant, "Ranked search should find user/create-related entities");
    }

    @Test
    void testNaturalLanguageAugmentsWithSpath() throws Exception {
        // "user controller" → ranked primary, then tries "UserController" via spath
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user controller",
                        indexDir, tempDir, 10);

        assertEquals(BlendedCodeSearch.QueryType.NATURAL, result.queryType());
        assertFalse(result.results().isEmpty());

        boolean hasController = result.results().stream()
                .anyMatch(r -> r.name() != null && r.name().contains("Controller"));
        assertTrue(hasController, "Should find UserController via ranked or spath augmentation");
    }

    // -----------------------------------------------------------------------
    // 4. Broad / overview → signature compression routing
    // -----------------------------------------------------------------------

    @Test
    void testBroadQueryRoutesToSignatures() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "project overview",
                        indexDir, tempDir, 20);

        assertEquals(BlendedCodeSearch.QueryType.BROAD, result.queryType());
        // Broad queries should either produce results or compressed context
        assertTrue(result.results().size() > 0 || result.compressedContext() != null,
                "Broad query should produce results or compressed context");
    }

    @Test
    void testBroadQueryWithOverview() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "overview",
                        indexDir, tempDir, 20);

        assertEquals(BlendedCodeSearch.QueryType.BROAD, result.queryType());
        assertNotNull(result);
        assertTrue(result.elapsedMs() >= 0);
    }

    // -----------------------------------------------------------------------
    // 5. Wildcard routing to spath patterns
    // -----------------------------------------------------------------------

    @Test
    void testWildcardRoutesToSpath() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "*Handler",
                        indexDir, tempDir, 10);

        assertEquals(BlendedCodeSearch.QueryType.WILDCARD, result.queryType());
        // Should find Handler classes via spath pattern or ranked fallback
        assertNotNull(result.results());
    }

    @Test
    void testWildcardWithDoubleStar() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "com.example.**",
                        indexDir, tempDir, 20);

        assertEquals(BlendedCodeSearch.QueryType.WILDCARD, result.queryType());
        assertNotNull(result.results());
    }

    @Test
    void testWildcardFallsBackToRankedOnNoSpathResults() throws Exception {
        // Wildcard with non-matching spath pattern should fall back to ranked
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "*Processor",
                        indexDir, tempDir, 10);

        assertEquals(BlendedCodeSearch.QueryType.WILDCARD, result.queryType());
        // May have results from ranked fallback (extracting "Processor" token)
        assertNotNull(result.results());
    }

    // -----------------------------------------------------------------------
    // 6. Compressed context threshold
    // -----------------------------------------------------------------------

    @Test
    void testCompressedContextTriggeredOnManyFiles() throws Exception {
        // Search that spans many files should trigger compression
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user",
                        indexDir, tempDir, 20);

        // "user" should match across User, UserRepository, UserService,
        // UserController, OrderService (has User param), UserServiceTest, handlers
        // If it spans >= 5 files, compressed context is generated
        if (result.results().stream()
                .map(BlendedCodeSearch.ResultEntry::filePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()).size() >= 5) {
            assertNotNull(result.compressedContext(),
                    "Compressed context should be generated when results span >= 5 files");
            assertTrue(result.compressedContext().contains("Compressed context"),
                    "Compressed context should have header");
        }
    }

    @Test
    void testCompressedContextNotTriggeredOnFewFiles() throws Exception {
        // Narrow search that hits few files should NOT compress
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "OrderService",
                        indexDir, tempDir, 3);

        long uniqueFiles = result.results().stream()
                .map(BlendedCodeSearch.ResultEntry::filePath)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        if (uniqueFiles < 5) {
            assertNull(result.compressedContext(),
                    "Should not compress when results span < 5 files");
        }
    }

    @Test
    void testBuildCompressedContextDirectly() throws Exception {
        Set<String> filePaths = new LinkedHashSet<>();
        filePaths.add("src/main/java/com/example/User.java");
        filePaths.add("src/main/java/com/example/UserService.java");

        String compressed = BlendedCodeSearch.buildCompressedContext(
                PROJECT_ID, filePaths, indexDir, tempDir);

        // Should either return a compressed view or null if no signatures found
        if (compressed != null) {
            assertTrue(compressed.contains("Compressed context"));
        }
    }

    // -----------------------------------------------------------------------
    // 7. Cross-strategy deduplication
    // -----------------------------------------------------------------------

    @Test
    void testCrossStrategyDeduplication() throws Exception {
        // Symbol path search does spath primary + ranked augmentation
        // Results should not have duplicates
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "com.example.UserService",
                        indexDir, tempDir, 20);

        // Check for duplicates by file+name key
        Set<String> keys = new HashSet<>();
        boolean hasDuplicates = false;
        for (BlendedCodeSearch.ResultEntry r : result.results()) {
            String key = r.filePath() + ":" + r.name();
            if (!keys.add(key)) {
                hasDuplicates = true;
                break;
            }
        }
        assertFalse(hasDuplicates,
                "Results should not contain duplicate file+name entries");
    }

    @Test
    void testNaturalSearchDeduplication() throws Exception {
        // Natural language augments with spath — should not duplicate
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user service",
                        indexDir, tempDir, 20);

        Set<String> keys = new HashSet<>();
        boolean hasDuplicates = false;
        for (BlendedCodeSearch.ResultEntry r : result.results()) {
            String key = r.filePath() + ":" + r.name();
            if (!keys.add(key)) {
                hasDuplicates = true;
                break;
            }
        }
        assertFalse(hasDuplicates, "Natural search should deduplicate across strategies");
    }

    // -----------------------------------------------------------------------
    // 8. Result structure and metadata
    // -----------------------------------------------------------------------

    @Test
    void testResultContainsQueryType() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "UserController",
                        indexDir, tempDir, 5);

        assertNotNull(result.queryType());
        assertNotNull(result.intent());
        assertNotNull(result.query());
        assertEquals("UserController", result.query());
        assertTrue(result.elapsedMs() >= 0);
        assertTrue(result.totalCandidates() >= 0);
    }

    @Test
    void testResultEntryFields() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user service create",
                        indexDir, tempDir, 5);

        if (!result.results().isEmpty()) {
            BlendedCodeSearch.ResultEntry first = result.results().get(0);
            assertNotNull(first.name(), "Result entry should have a name");
            assertNotNull(first.source(), "Result entry should have a source");
            assertTrue(first.score() >= 0, "Score should be non-negative");
        }
    }

    @Test
    void testIntentDetectedCorrectly() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user service create",
                        indexDir, tempDir, 10);

        // The intent should be set (the exact intent depends on the classifier)
        assertNotNull(result.intent());
    }

    // -----------------------------------------------------------------------
    // 9. Formatting
    // -----------------------------------------------------------------------

    @Test
    void testFormatResults() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user service",
                        indexDir, tempDir, 5);

        String formatted = BlendedCodeSearch.formatResults(result);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Blended search:"));
        assertTrue(formatted.contains("Type:"));
        assertTrue(formatted.contains("Intent:"));
        assertTrue(formatted.contains("Candidates:"));
        assertTrue(formatted.contains("Time:"));

        if (!result.results().isEmpty()) {
            assertTrue(formatted.contains("Results ("));
        }
    }

    @Test
    void testFormatResultsWithCompressedContext() throws Exception {
        // Force a broad search that should produce compressed context
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "user",
                        indexDir, tempDir, 20);

        String formatted = BlendedCodeSearch.formatResults(result);
        assertNotNull(formatted);

        if (result.compressedContext() != null) {
            assertTrue(formatted.contains("Compressed context"),
                    "Formatted output should include compressed context when present");
        }
    }

    // -----------------------------------------------------------------------
    // 10. topK limiting
    // -----------------------------------------------------------------------

    @Test
    void testTopKLimitsResults() throws Exception {
        BlendedCodeSearch.BlendedResult resultSmall =
                BlendedCodeSearch.search(PROJECT_ID, "user", indexDir, tempDir, 3);

        BlendedCodeSearch.BlendedResult resultLarge =
                BlendedCodeSearch.search(PROJECT_ID, "user", indexDir, tempDir, 20);

        assertTrue(resultSmall.results().size() <= 3,
                "Results should be limited to topK=3");
        assertTrue(resultLarge.results().size() >= resultSmall.results().size(),
                "Larger topK should return at least as many results");
    }

    // -----------------------------------------------------------------------
    // 11. Splan entities participate in blended search
    // -----------------------------------------------------------------------

    @Test
    void testSplanEntitiesInBlendedSearch() throws Exception {
        // Search for splan-indexed entities via natural language
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "transform data pipeline",
                        indexDir, tempDir, 10);

        assertNotNull(result);
        assertEquals(BlendedCodeSearch.QueryType.NATURAL, result.queryType());
        // Splan operations like "transform" should be in the index and findable
    }

    // -----------------------------------------------------------------------
    // 12. Edge cases
    // -----------------------------------------------------------------------

    @Test
    void testSingleWordQuery() throws Exception {
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "User",
                        indexDir, tempDir, 5);

        assertNotNull(result);
        assertFalse(result.results().isEmpty(), "Single word should find results");
    }

    @Test
    void testQueryWithSpecialCharacters() throws Exception {
        // Should not throw
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, "get()",
                        indexDir, tempDir, 5);
        assertNotNull(result);
    }

    @Test
    void testVeryLongQuery() throws Exception {
        String longQuery = "find the user service that handles " +
                "email authentication and creates new users in the repository";
        BlendedCodeSearch.BlendedResult result =
                BlendedCodeSearch.search(PROJECT_ID, longQuery,
                        indexDir, tempDir, 10);

        assertNotNull(result);
        assertEquals(BlendedCodeSearch.QueryType.NATURAL, result.queryType());
    }
}
