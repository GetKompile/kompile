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

package ai.kompile.app.services.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GitDiffService} against a real, throwaway git repository so the
 * git-invocation and unified-diff parsing are tested end-to-end. Commit dates are
 * pinned via {@code GIT_AUTHOR_DATE}/{@code GIT_COMMITTER_DATE} so the date-range
 * filters are deterministic.
 *
 * <p>History built in {@link #setUp()} (oldest → newest):</p>
 * <ol>
 *   <li>2026-01-01 "Add alpha"     — add src/alpha.txt (2 lines)</li>
 *   <li>2026-02-01 "Modify alpha"  — rewrite src/alpha.txt (+/- lines)</li>
 *   <li>2026-03-01 "Add beta"      — add nested src/sub/beta.txt</li>
 *   <li>2026-04-01 "Rename alpha"  — git mv src/alpha.txt -> src/alpha-renamed.txt</li>
 *   <li>2026-05-01 "Delete beta"   — git rm src/sub/beta.txt</li>
 * </ol>
 */
class GitDiffServiceTest {

    @TempDir
    Path repo;
    @TempDir
    Path notARepo;

    GitDiffService service;
    List<GitCommit> allCommits;

    @BeforeEach
    void setUp() throws Exception {
        service = new GitDiffService();
        ReflectionTestUtils.setField(service, "configuredRoot", repo.toString());

        runGit(null, "init");
        // Deterministic, version-independent default branch.
        runGit(null, "symbolic-ref", "HEAD", "refs/heads/main");
        runGit(null, "config", "user.email", "test@kompile.ai");
        runGit(null, "config", "user.name", "Kompile Test");
        runGit(null, "config", "commit.gpgsign", "false");

        write("src/alpha.txt", "line1\nline2\n");
        commit("Add alpha", "2026-01-01T00:00:00");

        write("src/alpha.txt", "line1\nCHANGED\nline3\n");
        commit("Modify alpha", "2026-02-01T00:00:00");

        write("src/sub/beta.txt", "b1\nb2\n");
        commit("Add beta", "2026-03-01T00:00:00");

        runGit(null, "mv", "src/alpha.txt", "src/alpha-renamed.txt");
        commit("Rename alpha", "2026-04-01T00:00:00");

        runGit(null, "rm", "src/sub/beta.txt");
        commit("Delete beta", "2026-05-01T00:00:00");

        runGit(null, "branch", "feature");

        allCommits = service.listCommits(null, 50, null, null, null, null);
    }

    // ── status / repo resolution ─────────────────────────────────────

    @Test
    void detectsGitRepoAndBranch() throws Exception {
        assertThat(service.isGitRepo()).isTrue();
        assertThat(service.currentBranch()).isEqualTo("main");
        assertThat(service.repoRoot().toRealPath()).isEqualTo(repo.toRealPath());
    }

    @Test
    void nonRepoDirectoryIsNotAGitRepo() {
        GitDiffService other = new GitDiffService();
        ReflectionTestUtils.setField(other, "configuredRoot", notARepo.toString());
        assertThat(other.isGitRepo()).isFalse();
    }

    @Test
    void listsLocalBranches() {
        assertThat(service.branches()).contains("main", "feature");
    }

    // ── commit listing ───────────────────────────────────────────────

    @Test
    void listsCommitsNewestFirstWithMetadata() {
        assertThat(allCommits)
                .extracting(GitCommit::getSubject)
                .containsExactly("Delete beta", "Rename alpha", "Add beta", "Modify alpha", "Add alpha");

        GitCommit addAlpha = bySubject("Add alpha");
        assertThat(addAlpha.getAuthor()).isEqualTo("Kompile Test");
        assertThat(addAlpha.getEmail()).isEqualTo("test@kompile.ai");
        assertThat(addAlpha.getHash()).isNotBlank();
        assertThat(addAlpha.getShortHash()).isNotBlank();
        assertThat(addAlpha.getDateIso()).startsWith("2026-01-01");
        assertThat(addAlpha.getFilesChanged()).isEqualTo(1);
        assertThat(addAlpha.getLinesAdded()).isEqualTo(2);
    }

    @Test
    void filtersCommitsByPath() {
        List<GitCommit> touchingAlpha = service.listCommits(null, 50, null, null, "src/alpha.txt", null);
        assertThat(touchingAlpha)
                .extracting(GitCommit::getSubject)
                .contains("Add alpha", "Modify alpha")
                .doesNotContain("Add beta");
    }

    @Test
    void filtersCommitsBySinceAndUntil() {
        List<GitCommit> since = service.listCommits(null, 50, "2026-02-15T00:00:00", null, null, null);
        assertThat(since)
                .extracting(GitCommit::getSubject)
                .containsExactlyInAnyOrder("Add beta", "Rename alpha", "Delete beta");

        List<GitCommit> until = service.listCommits(null, 50, null, "2026-02-15T00:00:00", null, null);
        assertThat(until)
                .extracting(GitCommit::getSubject)
                .containsExactlyInAnyOrder("Add alpha", "Modify alpha");
    }

    @Test
    void filtersCommitsByMessageQuery() {
        List<GitCommit> alpha = service.listCommits(null, 50, null, null, null, "alpha");
        assertThat(alpha)
                .extracting(GitCommit::getSubject)
                .containsExactlyInAnyOrder("Add alpha", "Modify alpha", "Rename alpha");
    }

    // ── per-commit diffs ─────────────────────────────────────────────

    @Test
    void commitDiffForAddedFile() {
        List<GitFileDiff> diffs = service.commitDiff(bySubject("Add alpha").getHash());
        assertThat(diffs).hasSize(1);
        GitFileDiff d = diffs.get(0);
        assertThat(d.getPath()).isEqualTo("src/alpha.txt");
        assertThat(d.getChangeType()).isEqualTo("ADDED");
        assertThat(d.getLinesAdded()).isEqualTo(2);
        assertThat(d.getLinesRemoved()).isEqualTo(0);
        assertThat(d.isBinary()).isFalse();
        assertThat(d.getUnifiedDiff()).contains("line1").contains("line2");
    }

    @Test
    void commitDiffForModifiedFile() {
        GitFileDiff d = service.commitDiff(bySubject("Modify alpha").getHash()).get(0);
        assertThat(d.getChangeType()).isEqualTo("MODIFIED");
        assertThat(d.getLinesAdded()).isGreaterThanOrEqualTo(1);
        assertThat(d.getLinesRemoved()).isGreaterThanOrEqualTo(1);
        assertThat(d.getUnifiedDiff()).contains("CHANGED");
    }

    @Test
    void commitDiffForDeletedFile() {
        GitFileDiff d = service.commitDiff(bySubject("Delete beta").getHash()).get(0);
        assertThat(d.getPath()).isEqualTo("src/sub/beta.txt");
        assertThat(d.getChangeType()).isEqualTo("DELETED");
        assertThat(d.getLinesAdded()).isEqualTo(0);
        assertThat(d.getLinesRemoved()).isEqualTo(2);
    }

    @Test
    void commitDiffDetectsRename() {
        GitFileDiff d = service.commitDiff(bySubject("Rename alpha").getHash()).get(0);
        assertThat(d.getChangeType()).isEqualTo("RENAMED");
        assertThat(d.getOldPath()).isEqualTo("src/alpha.txt");
        assertThat(d.getPath()).isEqualTo("src/alpha-renamed.txt");
    }

    @Test
    void commitDiffForUnknownOrBlankHashIsEmpty() {
        assertThat(service.commitDiff("")).isEmpty();
        assertThat(service.commitDiff("0123456789abcdef0123456789abcdef01234567")).isEmpty();
    }

    // ── file history ─────────────────────────────────────────────────

    @Test
    void fileHistoryFollowsRenamesAndAttachesCommitContext() {
        List<GitFileDiff> history = service.fileHistory("src/alpha-renamed.txt", 50, null, null);
        // --follow tracks the file back through the rename to its creation.
        assertThat(history).extracting(GitFileDiff::getSubject)
                .contains("Add alpha", "Modify alpha");
        assertThat(history).allSatisfy(d -> {
            assertThat(d.getCommitHash()).isNotBlank();
            assertThat(d.getCommitShortHash()).isNotBlank();
            assertThat(d.getSubject()).isNotBlank();
            assertThat(d.getDateIso()).isNotBlank();
        });
    }

    @Test
    void fileHistoryByBareFilenameMatchesNestedFile() {
        // "beta.txt" has no slash -> matched anywhere in the tree via glob pathspec.
        List<GitFileDiff> history = service.fileHistory("beta.txt", 50, null, null);
        assertThat(history)
                .extracting(GitFileDiff::getSubject)
                .contains("Add beta", "Delete beta");
        assertThat(history).allSatisfy(d -> assertThat(d.getPath()).endsWith("beta.txt"));
    }

    @Test
    void fileHistoryBlankPathIsEmpty() {
        assertThat(service.fileHistory("", 50, null, null)).isEmpty();
    }

    // ── file at revision ─────────────────────────────────────────────

    @Test
    void fileAtRefReturnsContentAtRevisionAndEmptyWhenAbsent() {
        String atAdd = service.fileAtRef(bySubject("Add beta").getHash(), "src/sub/beta.txt");
        assertThat(atAdd).contains("b1").contains("b2");

        // beta was deleted by HEAD, so it no longer exists there.
        assertThat(service.fileAtRef("HEAD", "src/sub/beta.txt")).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private GitCommit bySubject(String subject) {
        return allCommits.stream()
                .filter(c -> subject.equals(c.getSubject()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No commit with subject: " + subject));
    }

    private void write(String relPath, String content) throws IOException {
        Path f = repo.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private void commit(String message, String isoDate) throws Exception {
        runGit(isoDate, "add", "-A");
        runGit(isoDate, "commit", "-m", message);
    }

    /** Run a git command in the temp repo, failing the test if git fails. */
    private String runGit(String isoDate, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true);
        Map<String, String> env = new HashMap<>();
        env.put("GIT_AUTHOR_NAME", "Kompile Test");
        env.put("GIT_AUTHOR_EMAIL", "test@kompile.ai");
        env.put("GIT_COMMITTER_NAME", "Kompile Test");
        env.put("GIT_COMMITTER_EMAIL", "test@kompile.ai");
        if (isoDate != null) {
            env.put("GIT_AUTHOR_DATE", isoDate);
            env.put("GIT_COMMITTER_DATE", isoDate);
        }
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git timed out: " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + out);
        }
        return out;
    }
}
