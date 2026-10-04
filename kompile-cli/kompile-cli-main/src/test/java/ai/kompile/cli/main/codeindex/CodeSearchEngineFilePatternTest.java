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

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The file pattern of a find or replace is a path glob: {@code **} spans
 * directories, a pattern with a slash is anchored at the index root, and a
 * wildcard ahead of the name matches the file name. The forms the filter has
 * always taken keep their meaning: {@code *.ext} is a suffix, {@code prefix*} a
 * path prefix, and a plain string a substring of the path.
 */
@TemporaryUserHome
class CodeSearchEngineFilePatternTest {

    private static final Set<String> ALL_JAVA = Set.of("Root.java", "src/main/java/a/Alpha.java",
            "src/main/java/a/AlphaTest.java", "src/test/java/a/BetaTest.java", "lib/src/Gamma.java");

    @TempDir Path temp;
    private final String project = "file-pattern-test-" + UUID.randomUUID();
    private CodeSearchEngine engine;

    @BeforeEach
    void indexTree() throws Exception {
        for (String relPath : ALL_JAVA) {
            write(relPath, "// MARKER\nfinal class " + stem(relPath) + " {}\n");
        }
        write("scripts/tool.py", "# MARKER\ndef tool():\n    pass\n");
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        indexer.index(temp, project, null, null, silent());
        engine = new CodeSearchEngine(indexer);
    }

    @AfterEach
    void cleanup() throws Exception {
        Path dir = LocalCodeIndexer.getIndexDir(project);
        IndexMaintenance.invalidate(dir);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void doubleStarMatchesAtAnyDepth() throws Exception {
        assertEquals(ALL_JAVA, filesMatching("**/*.java"));
    }

    @Test
    void patternWithADirectoryIsAnchoredAtTheRoot() throws Exception {
        assertEquals(Set.of("src/main/java/a/Alpha.java", "src/main/java/a/AlphaTest.java",
                "src/test/java/a/BetaTest.java"), filesMatching("src/**/*.java"));
    }

    @Test
    void wildcardAheadOfTheNameMatchesTheFileName() throws Exception {
        assertEquals(Set.of("src/main/java/a/AlphaTest.java", "src/test/java/a/BetaTest.java"),
                filesMatching("*Test.java"));
    }

    @Test
    void braceAlternativesMatchEitherExtension() throws Exception {
        Set<String> expected = new TreeSet<>(ALL_JAVA);
        expected.add("scripts/tool.py");
        assertEquals(expected, filesMatching("*.{java,py}"));
    }

    @Test
    void formsTheFilterHasAlwaysTakenKeepTheirMeaning() throws Exception {
        assertEquals(ALL_JAVA, filesMatching("*.java"));
        assertEquals(Set.of("src/main/java/a/Alpha.java", "src/main/java/a/AlphaTest.java"),
                filesMatching("src/main/*"));
        assertEquals(Set.of("src/test/java/a/BetaTest.java"), filesMatching("src/test"));
        assertEquals(Set.of("src/main/java/a/Alpha.java", "src/main/java/a/AlphaTest.java"),
                filesMatching("Alpha"));
        assertEquals(Set.of(), filesMatching("*.kt"));
    }

    private Set<String> filesMatching(String filePattern) throws IOException {
        CodeSearchEngine.FindResult result = engine.findInFiles(project, "MARKER",
                new CodeSearchEngine.FindOptions().withFilePattern(filePattern));
        Set<String> files = new TreeSet<>();
        for (CodeSearchEngine.FileMatch match : result.matches()) {
            files.add(match.filePath().replace('\\', '/'));
        }
        return files;
    }

    private void write(String relPath, String content) throws IOException {
        Path file = temp.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String stem(String relPath) {
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream());
    }
}
