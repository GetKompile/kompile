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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            "node_modules", "bower_components", "target", "build", ".build", "dist", "out",
            "__pycache__", ".gradle", ".mvn", "vendor", "Pods", "DerivedData",
            // python envs / test caches
            ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache", ".ruff_cache",
            "site-packages", ".ipynb_checkpoints",
            // framework / tooling caches
            ".next", ".nuxt", ".angular", ".svelte-kit", ".cache", ".nyc_output", "coverage",
            ".turbo", ".parcel-cache", ".yarn", ".pnpm-store", ".dart_tool", ".serverless",
            ".aws-sam", ".terraform", ".stack-work",
            // editor metadata
            ".idea", ".vscode",
            // agent / kompile runtime + worktree metadata.
            // NOTE: full repo copies live under .claude/worktrees, so .claude must be pruned
            // even when a caller opts into hidden files — hence it lives here, not just under
            // the generic dot-prefix rule.
            ".kompile", ".claude", ".codex", ".gemini", ".opencode", ".cursor");

    private static final Set<String> DIR_SET = Set.copyOf(DIRS);
    private static final int BINARY_PROBE_BYTES = 8192;
    /**
     * Any file larger than this gets a second probe near EOF, which catches
     * late-appearing binary payloads in otherwise text-like headers.
     */
    private static final long BINARY_TAIL_PROBE_MIN_BYTES = 4L * 1024L;
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "7z", "a", "app", "arrow", "avif", "bin", "bmp", "bz2", "class", "dat", "db",
            "deb", "dll", "dmg", "doc", "docx", "dylib", "ear", "egg", "exe", "feather", "gem",
            "gif", "gz", "h5", "hdf5", "ico", "idx", "iso", "jar", "jpeg", "jpg", "joblib",
            "lib", "mdb", "mov", "mp3", "mp4", "msi", "node", "npy", "npz", "o", "onnx",
            "pak", "parquet", "pdf", "pickle", "pkl", "png", "ppt", "pptx", "pt", "pth", "pyc",
            "pyo", "rar", "rlib", "rpm", "safetensors", "so", "sqlite", "sqlite3", "tar", "tflite",
            "tgz", "tiff", "wasm", "war", "webp", "whl", "xls", "xlsx", "xz", "zip", "zst");

    /**
     * File glob patterns for extension-based binary skips. Shared by both Java fallback and
     * ripgrep command construction so traversal behavior stays consistent across backends.
     */
    static List<String> binaryFileGlobs() {
        List<String> globs = new ArrayList<>(BINARY_EXTENSIONS.size());
        for (String extension : BINARY_EXTENSIONS) {
            globs.add("*." + extension);
        }
        globs.sort(String::compareTo);
        return List.copyOf(globs);
    }

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

    /** True for file extensions that are overwhelmingly binary/search-hostile. */
    public static boolean isLikelyBinaryExtension(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tar.xz") || name.endsWith(".tar.bz2")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 && BINARY_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * Cheap binary classifier for tools that may otherwise scan arbitrary files.
     * Extension checks avoid opening obvious archives/models; the byte probe catches NULs,
     * control-heavy payloads, and invalid UTF-8 before callers allocate whole-file strings.
     */
    public static boolean isLikelyBinaryFile(Path path) throws IOException {
        if (isLikelyBinaryExtension(path)) {
            return true;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            for (long offset : binaryProbeOffsets(size)) {
                if (looksBinary(channel, offset, BINARY_PROBE_BYTES)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long[] binaryProbeOffsets(long size) {
        if (size <= BINARY_TAIL_PROBE_MIN_BYTES) {
            return new long[] {0L};
        }

        long[] offsets = new long[5];
        int count = 0;

        offsets[count++] = 0L;
        offsets[count++] = Math.max(0L, size - BINARY_PROBE_BYTES);

        if (size >= 4L * BINARY_PROBE_BYTES) {
            long quarter = Math.max(0L, (size / 4L) - (BINARY_PROBE_BYTES / 2L));
            long mid = Math.max(0L, (size / 2L) - (BINARY_PROBE_BYTES / 2L));
            long threeQuarter = Math.max(0L, (3L * size / 4L) - (BINARY_PROBE_BYTES / 2L));
            offsets[count++] = quarter;
            offsets[count++] = mid;
            offsets[count++] = threeQuarter;
        } else if (size >= 2L * BINARY_PROBE_BYTES) {
            offsets[count++] = Math.max(0L, (size / 2L) - (BINARY_PROBE_BYTES / 2L));
        }

        java.util.Arrays.sort(offsets, 0, count);
        long[] compact = new long[count];
        int compactCount = 0;
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            long current = offsets[i];
            if (compactCount == 0 || current != prev) {
                compact[compactCount++] = current;
                prev = current;
            }
        }
        return java.util.Arrays.copyOf(compact, compactCount);
    }

    private static boolean looksBinary(FileChannel channel, long offset, int maxBytes) throws IOException {
        if (channel.size() <= offset) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.allocate(maxBytes);
        int read = channel.read(buffer, offset);
        if (read <= 0) {
            return false;
        }
        int limit = Math.min(read, maxBytes);

        int control = 0;
        byte[] bytes = buffer.array();
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return true;
            }
            if ((b < 0x09) || (b > 0x0D && b < 0x20) || b == 0x7F) {
                control++;
            }
        }
        if (control > Math.max(1, limit / 10)) {
            return true;
        }
        // Attempt a strict UTF-8 decode of the sample.
        // When the probe reads from a non-zero file offset the window can start and end in the
        // middle of a multibyte character.  Skip leading continuation bytes (at most 3) that
        // are "orphaned" because their lead byte is before the window, and strip trailing bytes
        // that form an incomplete sequence that extends past the window.  Without these
        // adjustments, files dense with multibyte characters (box-drawing dividers like U+2500
        // ─ = E2 94 80, em-dashes, §, →) are spuriously reported as binary.
        int decodeStart = (offset == 0) ? 0 : skipLeadingContinuationBytes(bytes, limit);
        int decodeLimit = trimIncompleteUtf8Tail(bytes, limit);
        // If skipping + trimming consumed the entire window we cannot draw a conclusion —
        // treat as text (a real binary probe at offset 0 or a later probe will decide).
        if (decodeStart >= decodeLimit) {
            return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, decodeStart, decodeLimit - decodeStart));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }

    /**
     * Returns the index of the first byte in {@code bytes[0..length)} that is not a UTF-8
     * continuation byte (10xxxxxx).  When a probe window starts at a non-zero file offset the
     * first few bytes may be continuation bytes of a multibyte sequence whose lead byte lies
     * before the window start; skipping them avoids spurious {@link CharacterCodingException}s.
     * At most 3 bytes are skipped (the maximum number of continuation bytes in a valid UTF-8
     * sequence).  If every byte in the window is a continuation byte we return 0 so the whole
     * window is passed to the decoder, which will reject it as genuinely malformed.
     */
    static int skipLeadingContinuationBytes(byte[] bytes, int length) {
        int skip = 0;
        while (skip < 3 && skip < length && (bytes[skip] & 0xC0) == 0x80) {
            skip++;
        }
        return skip;
    }

    /**
     * Returns the largest prefix of {@code bytes[0..length)} that ends on a complete UTF-8
     * character boundary.  The last 1–3 bytes of a fixed-size probe window can be a truncated
     * multibyte lead sequence (e.g. the file has {@code E2 94 80} but the window ended after
     * {@code E2 94}); stripping those bytes prevents a {@link CharacterCodingException} on
     * otherwise valid UTF-8 content.
     *
     * <p>The trimmed portion is at most 3 bytes.  If we'd trim 4+ bytes it means the tail is
     * genuinely malformed (not just truncated), so we leave the limit unchanged and let the
     * decoder report the error normally.</p>
     */
    static int trimIncompleteUtf8Tail(byte[] bytes, int length) {
        if (length <= 0) {
            return length;
        }
        // Walk back up to 3 bytes from the end looking for a lead byte that starts a multibyte
        // sequence that would extend past the current limit.
        for (int trim = 1; trim <= 3 && trim <= length; trim++) {
            int idx = length - trim;
            int b = bytes[idx] & 0xFF;
            // Check if this byte is a UTF-8 lead byte for a sequence longer than `trim` bytes.
            // Lead byte patterns: 110xxxxx (2-byte), 1110xxxx (3-byte), 11110xxx (4-byte).
            int seqLen;
            if ((b & 0xE0) == 0xC0) {
                seqLen = 2;
            } else if ((b & 0xF0) == 0xE0) {
                seqLen = 3;
            } else if ((b & 0xF8) == 0xF0) {
                seqLen = 4;
            } else {
                // Not a lead byte: it's either an ASCII byte (0xxxxxxx) or a continuation
                // byte (10xxxxxx). If it's a continuation byte this loop will continue
                // walking back; if it's ASCII the tail is already complete.
                if ((b & 0xC0) == 0x80) {
                    // Continuation byte — keep looking for the lead.
                    continue;
                }
                // ASCII or already-complete sequence — no trim needed.
                break;
            }
            if (seqLen > trim) {
                // The lead byte at `idx` starts a sequence that extends past `length` — trim it.
                return idx;
            }
            // The lead byte's sequence fits entirely within `length` — tail is complete.
            break;
        }
        return length;
    }

    // ── .gitignore directory awareness ──────────────────────────────────────

    /**
     * A reusable directory-prune predicate seeded from a project's {@code .gitignore}. Directory
     * entries are interpreted as directory filters unless they are clearly file-globs. File-type
     * globs are ignored on purpose so ignored file <em>types</em> stay searchable. Immutable and
     * thread-safe.
     */
    public static final class GitignoreDirFilter {
        /** Single-segment literal directory names (match basename at any depth). */
        private final Set<String> literalNames;
        /** Single-segment directory names anchored to the .gitignore root. */
        private final Set<String> rootedLiteralNames;
        /** Single-segment glob directory names (e.g. {@code gcc-*}); matched against basename. */
        private final List<PathMatcher> nameGlobs;
        /** Anchored / multi-segment patterns (e.g. {@code a/b/out}); matched against the rel path. */
        private final List<PathMatcher> anchoredGlobs;
        /** Directory patterns usable as grep/rg glob exclusions (root-anchored entries are prefixed with {@code /}). */
        private final Set<String> excludeDirArgs;
        /** Directory containing the .gitignore that seeded this filter, if known. */
        private final Path root;

        private GitignoreDirFilter(Set<String> literalNames, List<PathMatcher> nameGlobs,
                                   List<PathMatcher> anchoredGlobs, Set<String> rootedLiteralNames,
                                   Set<String> excludeDirArgs, Path root) {
            this.literalNames = literalNames;
            this.nameGlobs = nameGlobs;
            this.anchoredGlobs = anchoredGlobs;
            this.rootedLiteralNames = rootedLiteralNames;
            this.excludeDirArgs = excludeDirArgs;
            this.root = root == null ? null : root.toAbsolutePath().normalize();
        }

        static final GitignoreDirFilter EMPTY =
                new GitignoreDirFilter(Set.of(), List.of(), List.of(), Set.of(), Set.of(), null);

        public boolean isEmpty() {
            return literalNames.isEmpty() && rootedLiteralNames.isEmpty()
                    && nameGlobs.isEmpty() && anchoredGlobs.isEmpty();
        }

        /**
         * True if a directory at {@code relativePath} (relative to the .gitignore root, using
         * {@code /} or platform separators) with basename {@code name} is git-ignored.
         */
        public boolean isIgnoredDir(String relativePath, String name) {
            if (name != null && literalNames.contains(name)) {
                return true;
            }
            String normalizedRelativePath = relativePath == null ? null : relativePath.replace('\\', '/');
            if (normalizedRelativePath != null && rootedLiteralNames.contains(normalizedRelativePath)) {
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
            if (normalizedRelativePath != null && !normalizedRelativePath.isEmpty() && !anchoredGlobs.isEmpty()) {
                Path rel = Path.of(normalizedRelativePath);
                for (PathMatcher m : anchoredGlobs) {
                    if (m.matches(rel)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Return {@code path} relative to the .gitignore root when known, otherwise to the
         * supplied fallback root. Directory filters parsed from a repo-level .gitignore must be
         * matched against repo-relative paths even when a tool search starts in a subdirectory.
         */
        public String relativePath(Path path, Path fallbackRoot) {
            String rel = relativize(root, path);
            if (rel != null) {
                return rel;
            }
            return relativize(fallbackRoot, path);
        }

        private static String relativize(Path base, Path path) {
            if (base == null || path == null) {
                return null;
            }
            try {
                return base.toAbsolutePath().normalize()
                        .relativize(path.toAbsolutePath().normalize())
                        .toString();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** Directory names/globs that can be passed straight to grep/ripgrep. */
        public Set<String> excludeDirArgs() {
            return excludeDirArgs;
        }

        /**
         * Directory names/globs rewritten for a ripgrep/grep invocation whose search root may be
         * below the repo root. A repo ignore like {@code project/logs/} must become {@code logs}
         * when the tool searches directly inside {@code project/}; otherwise rg sees paths
         * relative to the subdirectory and never prunes the ignored tree.
         */
        public Set<String> excludeDirArgs(Path searchRoot) {
            if (root == null || searchRoot == null || excludeDirArgs.isEmpty()) {
                return excludeDirArgs;
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedSearchRoot = searchRoot.toAbsolutePath().normalize();
            if (normalizedRoot.equals(normalizedSearchRoot)) {
                return excludeDirArgs;
            }

            Set<String> remapped = new LinkedHashSet<>();
            for (String ex : excludeDirArgs) {
                if (ex == null || ex.isEmpty()) {
                    continue;
                }
                boolean rootAnchored = ex.startsWith("/");
                String pattern = rootAnchored ? ex.substring(1) : ex;
                if (pattern.isEmpty()) {
                    continue;
                }
                boolean singleSegment = !pattern.contains("/");
                if (singleSegment && !rootAnchored) {
                    remapped.add(ex);
                    continue;
                }
                Path absolutePattern = normalizedRoot.resolve(pattern).normalize();
                if (absolutePattern.startsWith(normalizedSearchRoot)
                        && !absolutePattern.equals(normalizedSearchRoot)) {
                    String rel = normalizedSearchRoot.relativize(absolutePattern).toString().replace('\\', '/');
                    if (!rel.isEmpty()) {
                        remapped.add(rel);
                    }
                }
            }
            return remapped.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(remapped);
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
        Path searchRoot = Files.isDirectory(root) ? root : root.getParent();
        if (searchRoot == null) {
            return GitignoreDirFilter.EMPTY;
        }
        Path repoRoot = findGitignoreRoot(searchRoot);
        if (repoRoot == null) {
            return GitignoreDirFilter.EMPTY;
        }
        Path gitignore = repoRoot.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return GitignoreDirFilter.EMPTY;
        }
        try {
            return fromLines(Files.readAllLines(gitignore), repoRoot);
        } catch (IOException e) {
            return GitignoreDirFilter.EMPTY;
        }
    }

    private static Path findGitignoreRoot(Path start) {
        if (start == null) {
            return null;
        }

        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path gitignore = current.resolve(".gitignore");
            if (Files.isRegularFile(gitignore)) {
                return current;
            }
            Path gitDir = current.resolve(".git");
            if (Files.isDirectory(gitDir) || Files.isRegularFile(gitDir)) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Parse {@code .gitignore} lines into a {@link GitignoreDirFilter}. Package-private so the
     * directory-classification logic can be unit-tested without touching the filesystem.
     *
     * <p>Entries from a repo-level {@code .gitignore} are interpreted as directory-ignore rules
     * when they are unambiguous, even if they omit a trailing slash (for compatibility with
     * common real-world entries). A {@code !} negation is skipped (this filter only ever
     * adds prunes, so ignoring a negation can never remove a directory a caller wanted). A
     * leading or internal {@code /} anchors the pattern to the root; a bare single segment
     * matches at any depth.</p>
     */
    static GitignoreDirFilter fromLines(List<String> lines) {
        return fromLines(lines, null);
    }

    static GitignoreDirFilter fromLines(List<String> lines, Path root) {
        Set<String> literalNames = new LinkedHashSet<>();
        Set<String> rootedLiteralNames = new LinkedHashSet<>();
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
                boolean directoryOnly = line.endsWith("/");
                String pat = directoryOnly ? line.substring(0, line.length() - 1) : line;
                boolean anchoredToRoot = pat.startsWith("/") || pat.startsWith("./");
                if (pat.startsWith("./")) {
                    pat = pat.substring(2);
                } else if (anchoredToRoot) {
                    pat = pat.substring(1);
                }
                if (pat.isEmpty()) {
                    continue;
                }
                boolean hasGlob = hasGlobPattern(pat);
                boolean hasSlash = pat.contains("/");
                if (hasSlash) {
                    if (!directoryOnly && looksLikeFileName(pat)) {
                        continue;
                    }
                    String matcherPattern = pat.endsWith("/**") ? pat.substring(0, pat.length() - 3) : pat;
                    PathMatcher matcher = createPathMatcher(matcherPattern);
                    if (matcher == null) {
                        continue;
                    }
                    anchoredGlobs.add(matcher);
                    if (pat.endsWith("/**")) {
                        PathMatcher subtreeMatcher = createPathMatcher(pat);
                        if (subtreeMatcher != null) {
                            anchoredGlobs.add(subtreeMatcher);
                        }
                    }
                    if (!matcherPattern.isEmpty()) {
                        excludeDirArgs.add(anchoredToRoot ? "/" + matcherPattern : matcherPattern);
                    }
                    continue;
                }

                if (!hasGlob) {
                    if (looksLikeFileName(pat)) {
                        continue;
                    }
                    if (anchoredToRoot) {
                        rootedLiteralNames.add(pat);
                    } else {
                        literalNames.add(pat);
                    }
                    excludeDirArgs.add(anchoredToRoot ? "/" + pat : pat);
                } else {
                    // File-glob patterns like *.json stay searchable.
                    if (looksLikeFileGlob(pat)) {
                        continue;
                    }
                    PathMatcher matcher = createPathMatcher(pat);
                    if (matcher == null) {
                        continue;
                    }
                    nameGlobs.add(matcher);
                    excludeDirArgs.add(anchoredToRoot ? "/" + pat : pat);
                }
            }
        }

        if (literalNames.isEmpty() && nameGlobs.isEmpty() && anchoredGlobs.isEmpty()) {
            return rootedLiteralNames.isEmpty() ? GitignoreDirFilter.EMPTY
                    : new GitignoreDirFilter(literalNames, nameGlobs, anchoredGlobs, rootedLiteralNames, excludeDirArgs, root);
        }
        return new GitignoreDirFilter(literalNames, nameGlobs, anchoredGlobs, rootedLiteralNames,
                excludeDirArgs, root);
    }

    private static boolean hasGlobPattern(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0;
    }

    private static PathMatcher createPathMatcher(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean looksLikeFileName(String value) {
        int slash = value.lastIndexOf('/');
        String base = slash >= 0 ? value.substring(slash + 1) : value;
        if (base.startsWith("*.") || base.contains("*.")) {
            return true;
        }
        if (base.endsWith(".egg-info")) {
            return false;
        }
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return false;
        }
        return true;
    }

    private static boolean looksLikeFileGlob(String value) {
        if (hasGlobPattern(value) && value.startsWith("*.")) {
            return true;
        }
        int dot = value.lastIndexOf('.');
        int slash = value.lastIndexOf('/');
        String base = slash >= 0 ? value.substring(slash + 1) : value;
        return hasGlobPattern(value) && dot > 0 && dot < base.length() - 1;
    }
}
