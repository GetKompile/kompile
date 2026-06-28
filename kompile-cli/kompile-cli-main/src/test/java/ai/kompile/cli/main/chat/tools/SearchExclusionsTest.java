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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the consolidated exclusion source of truth and, in particular, the {@code .gitignore}
 * directory classification that keeps the search tools from walking a repo's multi-gigabyte
 * git-ignored data directories (the root cause of grep/glob/explore timeouts) while still
 * letting them search ignored file <em>types</em> (e.g. {@code *.json}).
 */
class SearchExclusionsTest {

    @Test
    void staticDirsArePrunedRegardlessOfHiddenFlag() {
        assertTrue(SearchExclusions.isExcludedDir("target"));
        assertTrue(SearchExclusions.isExcludedDir("node_modules"));
        // .claude holds full repo copies under worktrees — must be pruned even with hidden on.
        assertTrue(SearchExclusions.isExcludedDir(".claude", true));
        assertTrue(SearchExclusions.isExcludedDir(".git", true));
    }

    @Test
    void hiddenFlagOnlyGovernsOtherDotDirs() {
        assertTrue(SearchExclusions.isExcludedDir(".github", false));
        assertFalse(SearchExclusions.isExcludedDir(".github", true));
        assertFalse(SearchExclusions.isExcludedDir("src", false));
    }

    @Test
    void gitignoreLiteralDirectoryIsPrunedAndExported() {
        // Mirrors the real repo: a plain directory entry for a huge data dir.
        var f = SearchExclusions.fromLines(List.of("kompile-rag-builds/", "anserini-models/"));
        assertFalse(f.isEmpty());
        assertTrue(f.isIgnoredDir("kompile-rag-builds", "kompile-rag-builds"));
        assertTrue(f.isIgnoredDir("some/nested/kompile-rag-builds", "kompile-rag-builds"));
        assertTrue(f.isIgnoredDir("anserini-models", "anserini-models"));
        // The literal name is passed straight to grep --exclude-dir / rg !glob.
        assertTrue(f.excludeDirArgs().contains("kompile-rag-builds"));
    }

    @Test
    void gitignoreFileGlobsAreNotTreatedAsDirectories() {
        // *.json / *.onnx are file-type ignores — they must NOT prune any directory, so config
        // and data files of those types stay searchable (grep keeps --no-ignore for this).
        var f = SearchExclusions.fromLines(List.of("*.json", "*.onnx", "*.log", "*.bin"));
        assertTrue(f.isEmpty());
        assertFalse(f.isIgnoredDir("config", "config"));
        assertTrue(f.excludeDirArgs().isEmpty());
    }

    @Test
    void gitignoreSingleSegmentGlobMatchesByBasename() {
        var f = SearchExclusions.fromLines(List.of("gcc-*/"));
        assertTrue(f.isIgnoredDir("gcc-4.9.4", "gcc-4.9.4"));
        assertFalse(f.isIgnoredDir("gccfoo", "gccfoo"));
        assertTrue(f.excludeDirArgs().contains("gcc-*"));
    }

    @Test
    void gitignoreAnchoredMultiSegmentMatchesRelativePathOnly() {
        var f = SearchExclusions.fromLines(List.of("kompile-demo/logs/"));
        assertTrue(f.isIgnoredDir("kompile-demo/logs", "logs"));
        // A bare "logs" dir elsewhere must NOT be pruned by an anchored pattern.
        assertFalse(f.isIgnoredDir("src/logs", "logs"));
        // Anchored/multi-segment patterns are not safe as a basename --exclude-dir arg.
        assertFalse(f.excludeDirArgs().contains("logs"));
    }

    @Test
    void negationsAndCommentsAreIgnored() {
        var f = SearchExclusions.fromLines(List.of("# a comment", "", "!keep/", "build/"));
        assertTrue(f.isIgnoredDir("build", "build"));
        assertFalse(f.isIgnoredDir("keep", "keep"));
    }

    @Test
    void missingGitignoreYieldsEmptyNoOpFilter() {
        // null lines / no directory entries => empty filter, callers need no null checks.
        assertTrue(SearchExclusions.fromLines(null).isEmpty());
        assertTrue(SearchExclusions.fromLines(List.of("# only comments")).isEmpty());
    }
}
