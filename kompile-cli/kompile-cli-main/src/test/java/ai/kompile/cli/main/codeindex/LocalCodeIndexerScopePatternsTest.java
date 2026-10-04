/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The include and exclude patterns of an index pass are path globs matched below
 * the index root: {@code **} spans directories, a pattern with a slash is anchored
 * at the root, and neither the root's own name nor where it lives excludes it.
 * The comma list tolerates spaces, empty entries and repeats, and a plain word
 * still matches any file or directory name containing it.
 */
@TemporaryUserHome
class LocalCodeIndexerScopePatternsTest {

    @TempDir Path temp;
    private final List<String> projects = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (String project : projects) {
            Path dir = LocalCodeIndexer.getIndexDir(project);
            IndexMaintenance.invalidate(dir);
            deleteRecursively(dir);
        }
    }

    @Test
    void doubleStarExcludeSkipsTheDirectoryAtAnyDepth() throws Exception {
        Path root = tree("double-star", "src/A.java", "generated/B.java",
                "src/generated/C.java", "src/generatedX/D.java");

        assertEquals(Set.of("src/A.java", "src/generatedX/D.java"), indexed(root, null, "**/generated/**"));
    }

    @Test
    void excludeIsNeverMatchedAgainstTheRootsLocation() throws Exception {
        Path root = tree("generated-root", "A.java", "lib/B.java", "generated/C.java");

        assertEquals(Set.of("A.java", "lib/B.java"), indexed(root, null, "generated"));
    }

    @Test
    void rootNamedLikeAnAlwaysSkippedDirectoryIsIndexed() throws Exception {
        Path root = tree("build", "A.java", "sub/B.java", "target/C.java");

        assertEquals(Set.of("A.java", "sub/B.java"), indexed(root, null, null));
    }

    @Test
    void includeListToleratesSpacesAfterCommas() throws Exception {
        Path root = tree("spaces", "A.java", "b.py", "c.js");

        assertEquals(Set.of("A.java", "b.py"), indexed(root, "*.java, *.py", null));
    }

    @Test
    void repeatedPatternIsNotAnError() throws Exception {
        Path root = tree("repeats", "A.java", "b.py");

        assertEquals(Set.of("A.java"), indexed(root, "*.java,*.java", null));
    }

    @Test
    void emptyListEntryMatchesNothing() throws Exception {
        Path root = tree("empty-entry", "A.java", "b.py");

        assertEquals(Set.of("A.java", "b.py"), indexed(root, null, ",zzz"));
    }

    @Test
    void includeWithDirectoriesIsAnchoredAtTheRoot() throws Exception {
        Path root = tree("anchored", "src/A.java", "src/a/b/C.java", "lib/src/D.java", "src/E.py", "F.java");

        assertEquals(Set.of("src/A.java", "src/a/b/C.java"), indexed(root, "src/**/*.java", null));
    }

    @Test
    void gitignoreRulesAreMatchedRelativeToTheGitignore() throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".gitignore"), "/sub/ignored-data/\n/data\n");
        write(repo, "sub/A.java", "sub/ignored-data/B.java", "sub/data/C.java", "data/D.java");

        // "/sub/ignored-data/" names a directory of the indexed sub/; "/data" only the repository's own.
        assertEquals(Set.of("A.java", "data/C.java"), indexed(repo.resolve("sub"), null, null));
    }

    @Test
    void patternsThatAlwaysWorkedKeepTheirMeaning() throws Exception {
        Path root = tree("legacy", "A.java", "b.py", "sub/C.java", "FooTest.java", "sub/BarTest.java",
                "Foo.java", "FooBar.java", "generated-sources/G.java", "src/generated/H.java");

        assertEquals(Set.of("A.java", "sub/C.java", "FooTest.java", "sub/BarTest.java", "Foo.java",
                "FooBar.java", "generated-sources/G.java", "src/generated/H.java"), indexed(root, "*.java", null));
        assertEquals(Set.of("FooTest.java", "sub/BarTest.java"), indexed(root, "*Test.java", null));
        assertEquals(Set.of("Foo.java", "FooBar.java", "FooTest.java"), indexed(root, "Foo*", null));
        assertEquals(Set.of("A.java", "b.py", "sub/C.java", "FooTest.java", "sub/BarTest.java", "Foo.java",
                "FooBar.java"), indexed(root, null, "generated"));
    }

    private Set<String> indexed(Path root, String includes, String excludes) throws IOException {
        String project = "scope-patterns-test-" + UUID.randomUUID();
        projects.add(project);
        new LocalCodeIndexer().index(root, project, includes, excludes, silent());
        IndexFileStore store = new IndexFileStore(LocalCodeIndexer.getIndexDir(project), JsonUtils.standardMapper());
        Set<String> paths = new TreeSet<>();
        for (String relPath : store.loadFingerprints().keySet()) {
            paths.add(relPath.replace('\\', '/'));
        }
        return paths;
    }

    private Path tree(String name, String... relPaths) throws IOException {
        Path root = temp.resolve(name);
        Files.createDirectories(root);
        write(root, relPaths);
        return root;
    }

    private static void write(Path root, String... relPaths) throws IOException {
        for (String relPath : relPaths) {
            Path file = root.resolve(relPath);
            Files.createDirectories(file.getParent());
            String name = file.getFileName().toString();
            String stem = name.substring(0, name.lastIndexOf('.'));
            String source;
            if (name.endsWith(".java")) {
                source = "final class " + stem + " {}\n";
            } else if (name.endsWith(".py")) {
                source = "def " + stem + "():\n    pass\n";
            } else {
                source = "function " + stem + "() {}\n";
            }
            Files.writeString(file, source);
        }
    }

    private static PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
