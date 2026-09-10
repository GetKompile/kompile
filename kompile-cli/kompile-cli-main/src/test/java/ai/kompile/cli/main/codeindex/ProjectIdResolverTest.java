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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProjectIdResolver}: explicit param → project manifest →
 * registration.json → deepest indexed root → cwd directory name.
 */
class ProjectIdResolverTest {

    @TempDir
    Path tempDir;

    private Path baseIndexDir() throws Exception {
        Path base = tempDir.resolve("code-index");
        Files.createDirectories(base);
        return base;
    }

    private void writeIndexedProject(Path base, String projectId, Path rootPath,
                                     String indexedAt) throws Exception {
        Path projectDir = base.resolve(projectId);
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("metadata.json"),
                "{\"projectId\":\"" + projectId + "\",\"rootPath\":\""
                        + rootPath.toAbsolutePath() + "\",\"indexedAt\":\"" + indexedAt + "\"}");
        Files.createFile(projectDir.resolve("index.db"));
    }

    private void writeRegistration(Path projectDir, String projectId) throws Exception {
        Path kompileDir = projectDir.resolve(".kompile");
        Files.createDirectories(kompileDir);
        Files.writeString(kompileDir.resolve("registration.json"),
                "{\"projectId\":\"" + projectId + "\"}");
    }

    private void writeManifest(Path projectDir, String projectId, Path rootPath) throws Exception {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("kompile.project.json"), """
                {"codingProjects":[{"id":"%s","codeProjectId":"%s","rootPath":"%s","lifecycle":"ACTIVE"}]}
                """.formatted(projectId, projectId, rootPath.toAbsolutePath()));
    }

    @Test
    void explicitParamWinsUnchanged() throws Exception {
        ProjectIdResolver.Resolution r =
                ProjectIdResolver.resolve("my-project", tempDir, baseIndexDir());
        assertEquals("my-project", r.projectId());
        assertEquals("explicit", r.source());
        assertFalse(r.autoResolved());
    }

    @Test
    void unsafeExplicitProjectIdRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectIdResolver.resolve("../../outside", tempDir, baseIndexDir()));
    }

    @Test
    void registrationWinsWhenItsIndexExists() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("workdir/repo");
        Path nested = project.resolve("src/main/java");
        Files.createDirectories(nested);
        writeRegistration(project, "registered-id");
        writeIndexedProject(base, "registered-id", project, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", nested, base);
        assertEquals("registered-id", r.projectId());
        assertEquals("registration", r.source());
        assertTrue(r.autoResolved());
    }

    @Test
    void registrationWithoutIndexReportedAsUnindexed() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("unindexed-repo");
        Files.createDirectories(project);
        writeRegistration(project, "not-yet-indexed");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", project, base);
        assertEquals("not-yet-indexed", r.projectId());
        assertEquals("registration-unindexed", r.source());
    }

    @Test
    void projectManifestWinsOverNewerDuplicateIndexForSameRoot() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("manifest-repo");
        Path nested = project.resolve("src/main/java");
        Files.createDirectories(nested);
        writeManifest(project, "canonical-id", project);
        writeIndexedProject(base, "canonical-id", project, "2026-01-01T00:00:00Z");
        writeIndexedProject(base, "temporary-newer-id", project, "2026-08-27T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", nested, base);
        assertEquals("canonical-id", r.projectId());
        assertEquals("project-manifest", r.source());
    }

    @Test
    void projectManifestKeepsCanonicalIdBeforeIndexExists() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("manifest-unindexed-repo");
        Files.createDirectories(project);
        writeManifest(project, "canonical-unindexed", project);

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", project, base);
        assertEquals("canonical-unindexed", r.projectId());
        assertEquals("project-manifest-unindexed", r.source());
    }

    @Test
    void incompleteRegistrationIndexDoesNotHideValidRootIndex() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("partial-registration-repo");
        Files.createDirectories(project);
        writeRegistration(project, "partial-id");
        Path partial = base.resolve("partial-id");
        Files.createDirectories(partial);
        Files.writeString(partial.resolve("metadata.json"),
                "{\"projectId\":\"partial-id\",\"rootPath\":\"" + project.toAbsolutePath()
                        + "\",\"indexedAt\":\"2026-08-27T00:00:00Z\"}");
        writeIndexedProject(base, "valid-id", project, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", project, base);
        assertEquals("valid-id", r.projectId());
        assertEquals("index-root", r.source());
    }

    @Test
    void unsafeManifestProjectIdCannotEscapeIndexRoot() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("unsafe-manifest-repo");
        Files.createDirectories(project);
        writeManifest(project, "../../outside", project);
        writeIndexedProject(base, "safe-id", project, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", project, base);
        assertEquals("safe-id", r.projectId());
        assertEquals("index-root", r.source());
        assertThrows(IllegalArgumentException.class,
                () -> LocalCodeIndexer.getIndexDir("../../outside"));
    }

    @Test
    void unsafeRegistrationProjectIdIsIgnored() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("unsafe-registration-repo");
        Files.createDirectories(project);
        writeRegistration(project, "../../outside");
        writeIndexedProject(base, "safe-registration-fallback", project, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", project, base);
        assertEquals("safe-registration-fallback", r.projectId());
        assertEquals("index-root", r.source());
    }

    @Test
    void inactiveManifestEntryDoesNotOverrideActiveIndex() throws Exception {
        Path base = baseIndexDir();
        Path project = tempDir.resolve("inactive-manifest-repo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("kompile.project.json"), """
                {"codingProjects":[{"codeProjectId":"paused-id","rootPath":"%s","lifecycle":"PAUSED"}]}
                """.formatted(project.toAbsolutePath()));
        writeIndexedProject(base, "active-id", project, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", project, base);
        assertEquals("active-id", r.projectId());
        assertEquals("index-root", r.source());
    }

    @Test
    void deepestIndexedRootContainingCwdWins() throws Exception {
        Path base = baseIndexDir();
        Path outerRoot = tempDir.resolve("work");
        Path innerRoot = outerRoot.resolve("service");
        Path cwd = innerRoot.resolve("src");
        Files.createDirectories(cwd);
        writeIndexedProject(base, "outer", outerRoot, "2026-01-01T00:00:00Z");
        writeIndexedProject(base, "inner", innerRoot, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", cwd, base);
        assertEquals("inner", r.projectId());
        assertEquals("index-root", r.source());
    }

    @Test
    void indexRootMatchesWhenCwdEqualsRoot() throws Exception {
        Path base = baseIndexDir();
        Path root = tempDir.resolve("exact");
        Files.createDirectories(root);
        writeIndexedProject(base, "exact-project", root, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", root, base);
        assertEquals("exact-project", r.projectId());
        assertEquals("index-root", r.source());
    }

    @Test
    void unrelatedIndexedRootsIgnored() throws Exception {
        Path base = baseIndexDir();
        Path elsewhere = tempDir.resolve("elsewhere");
        Path cwd = tempDir.resolve("here/deep");
        Files.createDirectories(elsewhere);
        Files.createDirectories(cwd);
        writeIndexedProject(base, "other", elsewhere, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", cwd, base);
        assertEquals("deep", r.projectId());
        assertEquals("cwd-name", r.source());
    }

    @Test
    void fallsBackToCwdDirectoryName() throws Exception {
        Path base = baseIndexDir();
        Path cwd = tempDir.resolve("plain-dir");
        Files.createDirectories(cwd);

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", cwd, base);
        assertEquals("plain-dir", r.projectId());
        assertEquals("cwd-name", r.source());
        assertTrue(r.autoResolved());
    }

    @Test
    void corruptMetadataDoesNotBreakResolution() throws Exception {
        Path base = baseIndexDir();
        Path good = tempDir.resolve("goodroot");
        Path cwd = good.resolve("sub");
        Files.createDirectories(cwd);
        Path corrupt = base.resolve("corrupt-project");
        Files.createDirectories(corrupt);
        Files.writeString(corrupt.resolve("metadata.json"), "{not json at all");
        writeIndexedProject(base, "good-project", good, "2026-01-01T00:00:00Z");

        ProjectIdResolver.Resolution r = ProjectIdResolver.resolve("", cwd, base);
        assertEquals("good-project", r.projectId());
    }
}
