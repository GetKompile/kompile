/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectCommandUtils#requireExistingProjectRoot}.
 *
 * <p>Covers three cases:
 * <ol>
 *   <li>A directory with no {@code kompile.project.json} throws {@link IllegalStateException}
 *       with a clear, actionable message (not a raw Jackson error).</li>
 *   <li>A directory that IS a project root resolves correctly.</li>
 *   <li>A nested subdirectory of a project root also resolves correctly (walk-up behaviour).</li>
 * </ol>
 */
class ProjectCommandUtilsTest {

    // ── 1. No manifest → clear error ─────────────────────────────────────────

    @Test
    void requireExistingProjectRoot_noManifest_throwsIllegalStateWithClearMessage(@TempDir Path tmp) {
        KompileProjectStore store = new KompileProjectStore();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ProjectCommandUtils.requireExistingProjectRoot(store, tmp.toFile()),
                "Should throw when no kompile.project.json is present");

        String msg = ex.getMessage();
        assertNotNull(msg, "Exception message must not be null");
        assertTrue(msg.contains("No kompile project found"),
                "Message should say 'No kompile project found', got: " + msg);
        assertTrue(msg.contains(KompileProjectStore.MANIFEST_FILE),
                "Message should include the manifest filename (" + KompileProjectStore.MANIFEST_FILE
                        + "), got: " + msg);
    }

    // ── 2. Directory IS a project root → resolves to itself ──────────────────

    @Test
    void requireExistingProjectRoot_directoryWithManifest_resolvesToItself(@TempDir Path tmp) throws Exception {
        // Create a minimal project using store.init (same pattern used in other project tests)
        KompileProjectStore store = new KompileProjectStore();
        ai.kompile.project.KompileProjectInitRequest req = new ai.kompile.project.KompileProjectInitRequest();
        req.setName("test-project");
        req.setBackend(ai.kompile.project.KompileProjectStorageBackend.LOCAL);
        req.setIncludeStandardComponents(false);
        store.init(tmp, req);

        // Verify the manifest was created
        assertTrue(Files.isRegularFile(tmp.resolve(KompileProjectStore.MANIFEST_FILE)),
                "Manifest should exist after init");

        Path resolved = ProjectCommandUtils.requireExistingProjectRoot(store, tmp.toFile());
        assertEquals(tmp.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize(),
                "Should resolve to the project root itself");
    }

    // ── 3. Subdirectory inside a project → walks up to find the root ─────────

    @Test
    void requireExistingProjectRoot_fromNestedSubdir_walksUpToProjectRoot(@TempDir Path tmp) throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        ai.kompile.project.KompileProjectInitRequest req = new ai.kompile.project.KompileProjectInitRequest();
        req.setName("test-project");
        req.setBackend(ai.kompile.project.KompileProjectStorageBackend.LOCAL);
        req.setIncludeStandardComponents(false);
        store.init(tmp, req);

        // Create a nested subdirectory three levels deep
        Path deepSubdir = tmp.resolve("some/nested/subdir");
        Files.createDirectories(deepSubdir);

        Path resolved = ProjectCommandUtils.requireExistingProjectRoot(store, deepSubdir.toFile());
        assertEquals(tmp.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize(),
                "Should walk up from nested subdir and find the project root at " + tmp);
    }
}
