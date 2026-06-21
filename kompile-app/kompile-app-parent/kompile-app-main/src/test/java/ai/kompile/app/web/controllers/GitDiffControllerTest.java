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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.git.GitCommit;
import ai.kompile.app.services.git.GitDiffService;
import ai.kompile.app.services.git.GitFileDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link GitDiffController} wires the {@link GitDiffService} through
 * to JSON-shaped responses. Backed by a real one-commit temp repository.
 */
class GitDiffControllerTest {

    @TempDir
    Path repo;

    GitDiffController controller;

    @BeforeEach
    void setUp() throws Exception {
        GitDiffService service = new GitDiffService();
        ReflectionTestUtils.setField(service, "configuredRoot", repo.toString());
        controller = new GitDiffController(service);

        git("init");
        git("symbolic-ref", "HEAD", "refs/heads/main");
        git("config", "user.email", "test@kompile.ai");
        git("config", "user.name", "Kompile Test");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "hello world\n");
        git("add", "-A");
        git("commit", "-m", "Initial commit");
    }

    @Test
    void statusReportsRepoRootAndBranch() {
        Map<String, Object> status = controller.status();
        assertThat(status.get("repo")).isEqualTo(true);
        assertThat(status.get("branch")).isEqualTo("main");
        assertThat(status).containsKey("root");
        assertThat(status.get("root").toString()).isNotBlank();
    }

    @Test
    void commitsAndCommitDiffAreExposed() {
        List<GitCommit> commits = controller.commits(null, 10, null, null, null, null);
        assertThat(commits).isNotEmpty();
        assertThat(commits.get(0).getSubject()).isEqualTo("Initial commit");

        List<GitFileDiff> diff = controller.commitDiff(commits.get(0).getHash());
        assertThat(diff).extracting(GitFileDiff::getPath).contains("README.md");
        assertThat(diff.get(0).getChangeType()).isEqualTo("ADDED");
        assertThat(diff.get(0).getUnifiedDiff()).contains("hello world");
    }

    @Test
    void fileEndpointReturnsContentAtRef() {
        ResponseEntity<Map<String, Object>> resp = controller.fileAtRef("HEAD", "README.md");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("content").toString()).contains("hello world");
    }

    @Test
    void fileHistoryEndpointReturnsEntries() {
        List<GitFileDiff> history = controller.fileHistory("README.md", 10, null, null);
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).getSubject()).isEqualTo("Initial commit");
        assertThat(history.get(0).getCommitHash()).isNotBlank();
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_AUTHOR_DATE", "2026-01-01T00:00:00");
        pb.environment().put("GIT_COMMITTER_DATE", "2026-01-01T00:00:00");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git timed out: " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + out);
        }
    }
}
