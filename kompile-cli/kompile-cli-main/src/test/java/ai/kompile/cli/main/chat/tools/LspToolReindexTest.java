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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-index after an LSP rename refreshes the index within the root and
 * scope it records, whatever the session's working directory.
 */
@TemporaryUserHome
class LspToolReindexTest {

    @Test
    void renameReindexKeepsTheRecordedRootAndScope() throws Exception {
        String projectId = "lsp-reindex-test-" + System.nanoTime();
        Path root = Files.createTempDirectory("lsp-reindex-root").toAbsolutePath().normalize();
        Path module = Files.createDirectories(root.resolve("module"));
        try {
            Files.writeString(root.resolve("Alpha.java"), "final class Alpha {}\n");
            Files.writeString(module.resolve("Beta.java"), "final class Beta {}\n");
            Files.writeString(root.resolve("kestrel.py"), "class Kestrel:\n    pass\n");
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            indexer.index(root, projectId, "*.java", null, silent());
            // What the rename wrote.
            Files.writeString(root.resolve("Alpha.java"), "final class Swift {}\n");

            // The session's working directory is a subdirectory of the indexed root.
            new LspTool().reindex(module, projectId);

            Map<String, Object> stats = indexer.getStats(projectId);
            assertEquals(root.toString(), stats.get("rootPath"));
            assertEquals("*.java", stats.get("includePatterns"));
            assertFalse(indexer.search(projectId, "Swift", null, 10).isEmpty());
            assertFalse(indexer.search(projectId, "Beta", null, 10).isEmpty());
            assertTrue(indexer.search(projectId, "Kestrel", null, 10).isEmpty());
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
