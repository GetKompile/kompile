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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical set of directory names that are never worth searching: version-control
 * metadata, build output, dependency trees, caches, editor/agent metadata. This is the
 * <em>single source of truth</em> shared by every traversal in the CLI — {@link GrepTool},
 * {@link GlobTool}, {@link ExploreTool}, {@code FileWatcherService}, and the local code
 * indexer — so they all agree on what to skip (previously each kept its own divergent copy).
 *
 * <p>Without this, a plain walk descends into {@code .git/}, every {@code target/}, and (on
 * large repos) multi-gigabyte data directories, taking minutes and tripping tool timeouts.</p>
 *
 * <h3>.gitignore directory awareness</h3>
 * The static {@link #DIRS} list cannot know about project-specific data directories (model
 * builds, downloaded corpora, generated indices). Those are almost always already listed in
 * the project's {@code .gitignore}. {@link #loadGitignoreDirFilter(Path)} reads that file and
 * prunes the <em>directories</em> it ignores — generally, with no hard-coded project names —
 * while deliberately NOT applying file-type ignores (e.g. {@code *.json}). That threads the
 * needle: huge ignored data <em>directories</em> are skipped, yet ignored file <em>types</em>
 * (config/log files developers legitimately grep) remain searchable.
 */
public final class SearchExclusions {

    private SearchExclusions() {}

    /** Named directories pruned from any code search/walk regardless of the hidden flag. */
    public static final List<String> DIRS = List.of(
            // version control
            ".git", ".svn", ".hg",
            // build output / dependencies
            "node_modules", "target", "build", "dist", "out",
            "__pycache__", ".gradle", ".mvn", "vendor",
            // python envs / test caches
            ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache",
            // framework / tooling caches
            ".next", ".nuxt", ".angular", ".cache", ".nyc_output", "coverage",
            // editor metadata
            ".idea", ".vscode",
            // agent / kompile runtime + worktree metadata.
            // NOTE: full repo copies live under .claude/worktrees, so .claude must be pruned
            // even when a caller opts into hidden files — hence it lives here, not just under
            // the generic dot-prefix rule.
            ".kompile", ".claude", ".codex", ".gemini", ".opencode", ".cursor");

    private static final Set<String> DIR_SET = Set.copyOf(DIRS);

    /**
     * Returns true if a directory with this basename should be skipped while traversing.
     * Hidden directories (name starting with {@code .}) are skipped by default.
     */
    public static boolean isExcludedDir(String name) {
        return isExcludedDir(name, false);
    }

    /**
     * Returns true if a directory with this basename should be skipped while traversing.
     *
     * <p>The explicit {@link #DIRS} names — heavy VCS/build/dependency/metadata trees such as
     * {@code .git}, {@code target}, and {@code .claude} — are <em>always</em> pruned; searching
     * them is never useful. The {@code includeHidden} flag only governs <em>other</em>
     * dot-prefixed directories (e.g. {@code .github}, {@code .config}): they are pruned when
     * {@code includeHidden} is false and traversed when it is true.</p>
     */
    public static boolean isExcludedDir(String name, boolean includeHidden) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (DIR_SET.contains(name)) {
            return true;
        }
        return !includeHidden && name.startsWith(".");
    }

    // ── .gitignore directory awareness ──────────────────────────────────────

    /**
     * A reusable directory-prune predicate seeded from a project's {@code .gitignore}.
     * Only directory entries (those ending in {@code /}) are honored; file-type globs are
     * ignored on purpose so ignored file <em>types</em> stay searchable. Immutable and
     * thread-safe.
     */
    public static final class GitignoreDirFilter {
        /** Single-segment literal directory names (match basename at any depth). */
        private final Set<String> literalNames;
        /** Single-segment glob directory names (e.g. {@code gcc-*}); matched against basename. */
        private final List<PathMatcher> nameGlobs;
        /** Anchored / multi-segment patterns (e.g. {@code a/b/out}); matched against the rel path. */
        private final List<PathMatcher> anchoredGlobs;
        /** Single-segment names/globs usable verbatim as grep {@code --exclude-dir} / rg {@code !glob}. */
        private final Set<String> excludeDirArgs;

        private GitignoreDirFilter(Set<String> literalNames, List<PathMatcher> nameGlobs,
                                   List<PathMatcher> anchoredGlobs, Set<String> excludeDirArgs) {
            this.literalNames = literalNames;
            this.nameGlobs = nameGlobs;
            this.anchoredGlobs = anchoredGlobs;
            this.excludeDirArgs = excludeDirArgs;
        }

        static final GitignoreDirFilter EMPTY =
                new GitignoreDirFilter(Set.of(), List.of(), List.of(), Set.of());

        public boolean isEmpty() {
            return literalNames.isEmpty() && nameGlobs.isEmpty() && anchoredGlobs.isEmpty();
        }

        /**
         * True if a directory at {@code relativePath} (relative to the .gitignore root, using
         * {@code /} or platform separators) with basename {@code name} is git-ignored.
         */
        public boolean isIgnoredDir(String relativePath, String name) {
            if (name != null && literalNames.contains(name)) {
                return true;
            }
            if (name != null && !nameGlobs.isEmpty()) {
                Path n = Path.of(name);
                for (PathMatcher m : nameGlobs) {
                    if (m.matches(n)) {
                        return true;
                    }
                }
            }
            if (relativePath != null && !relativePath.isEmpty() && !anchoredGlobs.isEmpty()) {
                Path rel = Path.of(relativePath.replace('\\', '/'));
                for (PathMatcher m : anchoredGlobs) {
                    if (m.matches(rel)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Single-segment directory names/globs that can be passed straight to grep/ripgrep. */
        public Set<String> excludeDirArgs() {
            return excludeDirArgs;
        }
    }

    /**
     * Build a {@link GitignoreDirFilter} from {@code <root>/.gitignore}. Returns an empty
     * (no-op) filter when the file is absent or unreadable — callers never need a null check.
     */
    public static GitignoreDirFilter loadGitignoreDirFilter(Path root) {
        if (root == null) {
            return GitignoreDirFilter.EMPTY;
        }
        Path gitignore = root.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return GitignoreDirFilter.EMPTY;
        }
        try {
            return fromLines(Files.readAllLines(gitignore));
        } catch (IOException e) {
            return GitignoreDirFilter.EMPTY;
        }
    }

    /**
     * Parse {@code .gitignore} lines into a {@link GitignoreDirFilter}. Package-private so the
     * directory-classification logic can be unit-tested without touching the filesystem.
     *
     * <p>Only entries ending in {@code /} are treated as directories. A {@code !} negation is
     * skipped (this filter only ever <em>adds</em> prunes, so ignoring a negation can never
     * remove a directory a caller wanted). A leading or internal {@code /} anchors the pattern
     * to the root; a bare single segment matches at any depth.</p>
     */
    static GitignoreDirFilter fromLines(List<String> lines) {
        Set<String> literalNames = new LinkedHashSet<>();
        List<PathMatcher> nameGlobs = new ArrayList<>();
        List<PathMatcher> anchoredGlobs = new ArrayList<>();
        Set<String> excludeDirArgs = new LinkedHashSet<>();

        if (lines != null) {
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                // Directory entries only — skips file-type globs (*.json) so ignored file
                // types stay searchable while ignored data directories get pruned.
                if (!line.endsWith("/")) {
                    continue;
                }
                String pat = line.substring(0, line.length() - 1);
                boolean anchored = pat.startsWith("/") || pat.contains("/");
                if (pat.startsWith("/")) {
                    pat = pat.substring(1);
                }
                if (pat.isEmpty()) {
                    continue;
                }
                boolean hasGlob = pat.indexOf('*') >= 0 || pat.indexOf('?') >= 0 || pat.indexOf('[') >= 0;
                if (anchored && pat.contains("/")) {
                    try {
                        anchoredGlobs.add(FileSystems.getDefault().getPathMatcher("glob:" + pat));
                    } catch (RuntimeException ignored) {
                        // Unparseable glob — skip rather than fail the whole load.
                    }
                } else if (hasGlob) {
                    try {
                        nameGlobs.add(FileSystems.getDefault().getPathMatcher("glob:" + pat));
                        excludeDirArgs.add(pat);
                    } catch (RuntimeException ignored) {
                    }
                } else {
                    literalNames.add(pat);
                    excludeDirArgs.add(pat);
                }
            }
        }

        if (literalNames.isEmpty() && nameGlobs.isEmpty() && anchoredGlobs.isEmpty()) {
            return GitignoreDirFilter.EMPTY;
        }
        return new GitignoreDirFilter(literalNames, nameGlobs, anchoredGlobs, excludeDirArgs);
    }
}
