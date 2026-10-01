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
import ai.kompile.project.KompileProjectStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A find-and-replace re-indexes and re-projects within the scope the index
 * records; the replacement never changes that scope.
 */
@TemporaryUserHome
class CodeSearchEngineReplaceTest {

    @Test
    void replacementReindexesAndProjectsTheRecordedScope() throws Exception {
        String projectId = "replace-scope-test-" + System.nanoTime();
        Path root = Files.createTempDirectory("replace-scope-root").toAbsolutePath().normalize();
        try {
            Files.writeString(root.resolve("Alpha.java"), "final class Alpha {}\n");
            Files.writeString(root.resolve("kestrel.py"), "class Kestrel:\n    pass\n");
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            indexer.index(root, projectId, "*.java", null, silent());
            Files.writeString(root.resolve("heron.py"), "class Heron:\n    pass\n");

            CodeSearchEngine.ReplaceResult result = new CodeSearchEngine(indexer).findAndReplace(
                    projectId, "Alpha", "Swift", new CodeSearchEngine.FindOptions(), false, silent());

            assertEquals(1, result.filesModified());
            assertNotNull(result.indexResult());
            assertEquals("*.java", indexer.getStats(projectId).get("includePatterns"));
            assertFalse(indexer.search(projectId, "Swift", null, 10).isEmpty());
            assertTrue(indexer.search(projectId, "Heron", null, 10).isEmpty());
            assertTrue(indexer.search(projectId, "Kestrel", null, 10).isEmpty());
            assertEquals("*.java", new KompileProjectStore().load(root).getCodingProjects().get(0)
                    .getMetadata().get("codeProjectionIncludes"));
        } finally {
            deleteRecursively(LocalCodeIndexer.getIndexDir(projectId));
            deleteRecursively(root);
        }
    }

    private static PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
