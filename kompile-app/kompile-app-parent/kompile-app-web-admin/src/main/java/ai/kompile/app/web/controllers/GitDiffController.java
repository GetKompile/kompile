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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for browsing git commit history and diffs of the current
 * project repository. Feeds the "Git Commits" and "Compare" modes of the code
 * diff browser. Read-only.
 */
@RestController
@RequestMapping("/api/git")
public class GitDiffController {

    private final GitDiffService gitDiffService;

    public GitDiffController(GitDiffService gitDiffService) {
        this.gitDiffService = gitDiffService;
    }

    /** Whether the project is a git repo, its root path, and current branch. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean repo = gitDiffService.isGitRepo();
        body.put("repo", repo);
        body.put("root", gitDiffService.repoRoot().toString());
        body.put("branch", repo ? gitDiffService.currentBranch() : "");
        return body;
    }

    @GetMapping("/branches")
    public List<String> branches() {
        return gitDiffService.branches();
    }

    /**
     * List commits, optionally filtered by branch, date range ({@code since}/
     * {@code until}), a touched {@code path}, and commit-message {@code query}.
     */
    @GetMapping("/commits")
    public List<GitCommit> commits(
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String query) {
        return gitDiffService.listCommits(branch, limit, since, until, path, query);
    }

    /** Per-file diffs introduced by a single commit. */
    @GetMapping("/commits/{hash}/diff")
    public List<GitFileDiff> commitDiff(@PathVariable String hash) {
        return gitDiffService.commitDiff(hash);
    }

    /**
     * Every change a file underwent (newest first), each tied to its commit.
     * Drives the git side of the "Compare against agent changes" view.
     */
    @GetMapping("/file-history")
    public List<GitFileDiff> fileHistory(
            @RequestParam String path,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until) {
        return gitDiffService.fileHistory(path, limit, since, until);
    }

    /** Raw file content at a revision (for full-file side-by-side compare). */
    @GetMapping("/file")
    public ResponseEntity<Map<String, Object>> fileAtRef(
            @RequestParam String ref,
            @RequestParam String path) {
        String content = gitDiffService.fileAtRef(ref, path);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", ref);
        body.put("path", path);
        body.put("content", content);
        return ResponseEntity.ok(body);
    }
}
