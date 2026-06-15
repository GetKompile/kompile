package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.codeindex.SpathParser.PathSegment;
import ai.kompile.cli.main.codeindex.SpathParser.SegmentKind;
import ai.kompile.cli.main.codeindex.SpathParser.SpathQuery;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for spath parsing and resolution.
 */
class SpathTest {

    // -----------------------------------------------------------------------
    // Parser tests
    // -----------------------------------------------------------------------

    @Test
    void testExactClassPath() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.main.LocalCodeIndexer");
        assertFalse(q.hasWildcard());
        assertNull(q.property());
        assertNull(q.selector());
        assertTrue(q.isExact());
        assertEquals("ai.kompile.cli.main", q.packagePath());
        assertEquals("LocalCodeIndexer", q.symbolPath());
    }

    @Test
    void testExactMethodPath() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.main.LocalCodeIndexer.search");
        assertFalse(q.hasWildcard());
        assertEquals("ai.kompile.cli.main", q.packagePath());
        assertEquals("LocalCodeIndexer.search", q.symbolPath());
    }

    @Test
    void testSingleWildcard() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.main.*");
        assertTrue(q.hasWildcard());
        assertFalse(q.hasRecursiveWildcard());
        assertEquals("ai.kompile.cli.main", q.packagePath());
        assertEquals("*", q.symbolPath());
    }

    @Test
    void testRecursiveWildcard() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.**");
        assertTrue(q.hasWildcard());
        assertTrue(q.hasRecursiveWildcard());
        assertEquals("ai.kompile.cli", q.packagePath());
    }

    @Test
    void testSuffixPattern() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.*Indexer");
        assertTrue(q.hasWildcard());
        assertEquals("ai.kompile.cli", q.packagePath());
        PathSegment last = q.segments().get(q.segments().size() - 1);
        assertEquals(SegmentKind.PATTERN, last.kind());
        assertEquals("*Indexer", last.name());
    }

    @Test
    void testSelector() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli[LocalCodeIndexer.java].search");
        assertEquals("LocalCodeIndexer.java", q.selector());
        assertFalse(q.hasWildcard());
    }

    @Test
    void testProperty() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.main.LocalCodeIndexer/imports");
        assertEquals("imports", q.property());
        assertFalse(q.hasWildcard());
    }

    @Test
    void testGoStylePath() {
        SpathQuery q = SpathParser.parse("internal/service.Handler");
        assertEquals("internal.service", q.packagePath());
        assertEquals("Handler", q.symbolPath());
    }

    @Test
    void testGoSubpackageEnumeration() {
        SpathQuery q = SpathParser.parse("internal/...");
        assertTrue(q.hasRecursiveWildcard());
        assertEquals("internal", q.packagePath());
    }

    @Test
    void testFqnLikePatternExact() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.main.LocalCodeIndexer");
        assertEquals("ai.kompile.cli.main.LocalCodeIndexer", q.toFqnLikePattern());
    }

    @Test
    void testFqnLikePatternWildcard() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.*");
        assertEquals("ai.kompile.cli.%", q.toFqnLikePattern());
    }

    @Test
    void testFqnLikePatternRecursive() {
        SpathQuery q = SpathParser.parse("ai.kompile.**");
        assertEquals("ai.kompile.%", q.toFqnLikePattern());
    }

    @Test
    void testFqnLikePatternSuffix() {
        SpathQuery q = SpathParser.parse("ai.kompile.*Indexer");
        assertEquals("ai.kompile.%Indexer", q.toFqnLikePattern());
    }

    @Test
    void testEmptyInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> SpathParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SpathParser.parse("   "));
    }

    @Test
    void testSimpleName() {
        SpathQuery q = SpathParser.parse("LocalCodeIndexer");
        assertEquals(1, q.segments().size());
        assertEquals("LocalCodeIndexer", q.segments().get(0).name());
        assertEquals(SegmentKind.SYMBOL, q.segments().get(0).kind());
    }

    @Test
    void testAllLowercasePath() {
        SpathQuery q = SpathParser.parse("ai.kompile.cli.main.codeindex");
        assertEquals("ai.kompile.cli.main.codeindex", q.packagePath());
        assertNull(q.symbolPath());
    }

    @Test
    void testPropertyWithoutPath() {
        // /imports should still parse (matches all imports)
        SpathQuery q = SpathParser.parse("/imports");
        assertEquals("imports", q.property());
        assertTrue(q.segments().isEmpty());
    }

    @Test
    void testWildcardWithProperty() {
        SpathQuery q = SpathParser.parse("ai.kompile.*/imports");
        assertTrue(q.hasWildcard());
        assertEquals("imports", q.property());
    }

    // -----------------------------------------------------------------------
    // Integration test: parse, index, resolve
    // -----------------------------------------------------------------------

    @Test
    void testSpathResolution() throws Exception {
        // Create a temp project with Java and splan files
        Path tempDir = Files.createTempDirectory("spath-test");
        Path pkgDir = tempDir.resolve("com/example/service");
        Files.createDirectories(pkgDir);

        Files.writeString(pkgDir.resolve("Handler.java"), """
                package com.example.service;

                import java.util.List;

                public class Handler {
                    public void process(String input) {}
                    public List<String> getResults() { return null; }
                }
                """);

        Files.writeString(pkgDir.resolve("Repository.java"), """
                package com.example.service;

                public interface Repository {
                    void save(Object entity);
                    Object findById(String id);
                }
                """);

        Files.writeString(tempDir.resolve("build.splan"), """
                # Build pipeline
                :config:::build settings:::
                compile src :config
                run tests
                """);

        String projectId = "spath-test-" + System.nanoTime();

        try {
            // Index the project
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos);
            indexer.index(tempDir, projectId, null, null, true, out);

            SpathResolver resolver = new SpathResolver(projectId);

            // Exact class match
            SpathResolver.SpathResult r1 = resolver.resolve("com.example.service.Handler", 10);
            assertFalse(r1.matches().isEmpty(), "Should find Handler class");
            assertTrue(r1.matches().stream().anyMatch(m ->
                    "Handler".equals(m.name()) && "CLASS".equals(m.entityType())));

            // Exact method match
            SpathResolver.SpathResult r2 = resolver.resolve("com.example.service.Handler.process", 10);
            assertFalse(r2.matches().isEmpty(), "Should find process method");
            assertTrue(r2.matches().stream().anyMatch(m ->
                    "process".equals(m.name()) && "METHOD".equals(m.entityType())));

            // Wildcard: all direct children of package
            SpathResolver.SpathResult r3 = resolver.resolve("com.example.service.*", 50);
            assertFalse(r3.matches().isEmpty(), "Should find entities in package");

            // Recursive wildcard
            SpathResolver.SpathResult r4 = resolver.resolve("com.example.**", 100);
            assertFalse(r4.matches().isEmpty(), "Should find all entities under com.example");
            assertTrue(r4.matches().size() >= r3.matches().size(),
                    "Recursive should find at least as many as single-level");

            // Suffix pattern
            SpathResolver.SpathResult r5 = resolver.resolve("com.example.service.*Handler", 10);
            assertFalse(r5.matches().isEmpty(), "Should find *Handler pattern");

            // Property: imports
            SpathResolver.SpathResult r6 = resolver.resolve("com.example.service.Handler/imports", 10);
            assertFalse(r6.matches().isEmpty(), "Should find imports for Handler's file");
            assertTrue(r6.matches().stream().allMatch(m -> "IMPORT".equals(m.entityType())),
                    "All results should be IMPORT type");

            // Simple name match
            SpathResolver.SpathResult r7 = resolver.resolve("Repository", 10);
            assertFalse(r7.matches().isEmpty(), "Should find Repository by simple name");

            // Splan entities
            SpathResolver.SpathResult r8 = resolver.resolve("compile", 10);
            assertFalse(r8.matches().isEmpty(), "Should find splan compile operation");

        } finally {
            // Cleanup
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (Files.exists(indexDir)) {
                Files.walk(indexDir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void testInheritanceTracking() throws Exception {
        Path tempDir = Files.createTempDirectory("inheritance-test");
        Path javaDir = tempDir.resolve("com/example");
        Files.createDirectories(javaDir);
        Path pyDir = tempDir.resolve("pylib");
        Files.createDirectories(pyDir);

        // Java class with extends + implements
        Files.writeString(javaDir.resolve("Derived.java"), """
                package com.example;

                public class Derived extends Base implements Runnable, Serializable {
                    public void run() {}
                }
                """);

        // Python class with base classes
        Files.writeString(pyDir.resolve("models.py"), """
                class Foo(Bar, Baz):
                    def process(self):
                        pass
                """);

        String projectId = "inheritance-test-" + System.nanoTime();

        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos);
            indexer.index(tempDir, projectId, null, null, true, out);

            SpathResolver resolver = new SpathResolver(projectId);

            // Java: check extends and implements
            SpathResolver.SpathResult r1 = resolver.resolve("com.example.Derived", 10);
            assertFalse(r1.matches().isEmpty(), "Should find Derived class");
            SpathResolver.SpathMatch javaMatch = r1.matches().stream()
                    .filter(m -> "CLASS".equals(m.entityType()) && "Derived".equals(m.name()))
                    .findFirst().orElseThrow();
            assertEquals("Base", javaMatch.inheritedFrom(), "Should track extends Base");
            assertEquals("Runnable, Serializable", javaMatch.implementsList(),
                    "Should track implements list");

            // Python: check base classes
            SpathResolver.SpathResult r2 = resolver.resolve("Foo", 10);
            assertFalse(r2.matches().isEmpty(), "Should find Foo class");
            SpathResolver.SpathMatch pyMatch = r2.matches().stream()
                    .filter(m -> "CLASS".equals(m.entityType()) && "Foo".equals(m.name()))
                    .findFirst().orElseThrow();
            assertEquals("Bar, Baz", pyMatch.inheritedFrom(),
                    "Python bases should be in inheritedFrom");

        } finally {
            // Cleanup
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (Files.exists(indexDir)) {
                Files.walk(indexDir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
