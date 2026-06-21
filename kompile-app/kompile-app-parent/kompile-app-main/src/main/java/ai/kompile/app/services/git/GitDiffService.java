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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads git commit history and diffs for the current project repository and
 * exposes them in a shape the diff-browsing UI can render side-by-side.
 *
 * <p>This is a read-only service: it only runs {@code git log}/{@code show}/
 * {@code diff}/{@code rev-parse}, never anything that mutates the repo. It runs
 * git via a self-contained {@link ProcessBuilder} (no dependency on the CLI
 * module) with a hard timeout so a wedged subprocess can never hang a request.</p>
 */
@Service
public class GitDiffService {

    private static final Logger log = LoggerFactory.getLogger(GitDiffService.class);

    /** ASCII record separator — placed before each commit in custom log formats. */
    private static final String RS = "";
    /** ASCII unit separator — placed between fields of a commit. */
    private static final String US = "";

    private static final Pattern FILES_CHANGED = Pattern.compile("(\\d+) files? changed");
    private static final Pattern INSERTIONS = Pattern.compile("(\\d+) insertions?\\(\\+\\)");
    private static final Pattern DELETIONS = Pattern.compile("(\\d+) deletions?\\(-\\)");

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    /** Result of a git subprocess: exit code plus raw (untrimmed) combined output. */
    private record GitResult(int exitCode, String output) {
        boolean ok() { return exitCode == 0; }
    }

    // ── Repository resolution ────────────────────────────────────────

    /**
     * Resolve the working tree root of the current project's git repository.
     * Honors {@code kompile.project.root}, falling back to the JVM working
     * directory, then asks git for the actual toplevel.
     */
    public Path repoRoot() {
        Path start = (configuredRoot != null && !configuredRoot.isBlank())
                ? Path.of(configuredRoot)
                : Path.of(System.getProperty("user.dir", "."));
        GitResult r = exec(start, List.of("rev-parse", "--show-toplevel"));
        if (r.ok() && !r.output().isBlank()) {
            return Path.of(r.output().trim());
        }
        return start.toAbsolutePath().normalize();
    }

    /** Whether the resolved root is inside a git work tree. */
    public boolean isGitRepo() {
        GitResult r = exec(repoRoot(), List.of("rev-parse", "--is-inside-work-tree"));
        return r.ok() && r.output().trim().equals("true");
    }

    /** Current branch name, or {@code "HEAD"} when detached. */
    public String currentBranch() {
        GitResult r = exec(repoRoot(), List.of("rev-parse", "--abbrev-ref", "HEAD"));
        return r.ok() ? r.output().trim() : "";
    }

    /** Local branch names, current branch first. */
    public List<String> branches() {
        GitResult r = exec(repoRoot(), List.of("branch", "--format=%(refname:short)"));
        List<String> out = new ArrayList<>();
        if (r.ok()) {
            for (String line : r.output().split("\n")) {
                String b = line.trim();
                if (!b.isEmpty()) out.add(b);
            }
        }
        return out;
    }

    // ── Commit listing ───────────────────────────────────────────────

    /**
     * List commits with per-commit line stats, optionally filtered by branch,
     * date range, touched path, and commit-message text.
     */
    public List<GitCommit> listCommits(String branch, Integer limit, String since,
                                       String until, String path, String query) {
        int max = (limit != null && limit > 0) ? Math.min(limit, 1000) : 100;
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("--no-color");
        args.add("--no-decorate");
        args.add("-n");
        args.add(String.valueOf(max));
        args.add("--pretty=format:" + RS + "%H" + US + "%h" + US + "%an" + US + "%ae" + US + "%aI" + US + "%s");
        args.add("--shortstat");
        if (notBlank(since)) args.add("--since=" + since);
        if (notBlank(until)) args.add("--until=" + until);
        if (notBlank(query)) {
            args.add("-i");
            args.add("--grep=" + query);
        }
        if (notBlank(branch)) args.add(branch);
        if (notBlank(path)) {
            args.add("--");
            args.add(path);
        }

        GitResult r = exec(repoRoot(), args);
        List<GitCommit> commits = new ArrayList<>();
        if (!r.ok()) {
            if (!r.output().isBlank()) log.debug("git log failed: {}", r.output());
            return commits;
        }
        for (String record : r.output().split(RS)) {
            if (record.isBlank()) continue;
            String[] f = record.split(US, -1);
            if (f.length < 6) continue;
            GitCommit.GitCommitBuilder b = GitCommit.builder()
                    .hash(f[0].trim())
                    .shortHash(f[1].trim())
                    .author(f[2])
                    .email(f[3])
                    .dateIso(f[4].trim());
            // f[5] = subject, possibly followed by the --shortstat line(s).
            String[] tail = f[5].split("\n");
            b.subject(tail.length > 0 ? tail[0] : "");
            for (int i = 1; i < tail.length; i++) {
                String line = tail[i];
                if (line.contains("changed")) {
                    b.filesChanged(intGroup(FILES_CHANGED, line));
                    b.linesAdded(intGroup(INSERTIONS, line));
                    b.linesRemoved(intGroup(DELETIONS, line));
                    break;
                }
            }
            commits.add(b.build());
        }
        return commits;
    }

    // ── Per-commit diff ──────────────────────────────────────────────

    /**
     * The per-file diffs introduced by a commit (vs. its first parent).
     */
    public List<GitFileDiff> commitDiff(String hash) {
        if (!notBlank(hash)) return List.of();
        // --format= suppresses the commit header so the output is just the patch.
        GitResult r = exec(repoRoot(), List.of(
                "show", "--no-color", "--format=", "-U3", "-M", hash));
        if (!r.ok()) {
            if (!r.output().isBlank()) log.debug("git show failed: {}", r.output());
            return List.of();
        }
        return parsePatch(r.output(), null);
    }

    // ── File history ─────────────────────────────────────────────────

    /**
     * The change a file underwent at each commit that touched it (newest
     * first), with the commit context attached to every diff. Follows renames.
     */
    public List<GitFileDiff> fileHistory(String path, Integer limit, String since, String until) {
        if (!notBlank(path)) return List.of();
        int max = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        // A bare filename (no slash) is matched anywhere in the tree via a glob pathspec
        // so the user can compare "Foo.java" without knowing its full repo-relative path.
        // --follow only works with a single concrete path, so it is used only then.
        boolean specificPath = path.contains("/");
        String pathspec = specificPath ? path : ":(glob)**/" + path;
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("--no-color");
        if (specificPath) args.add("--follow");
        args.add("-p");
        args.add("-U3");
        args.add("-n");
        args.add(String.valueOf(max));
        args.add("--pretty=format:" + RS + "%H" + US + "%h" + US + "%an" + US + "%ae" + US + "%aI" + US + "%s");
        if (notBlank(since)) args.add("--since=" + since);
        if (notBlank(until)) args.add("--until=" + until);
        args.add("--");
        args.add(pathspec);

        GitResult r = exec(repoRoot(), args);
        List<GitFileDiff> history = new ArrayList<>();
        if (!r.ok()) {
            if (!r.output().isBlank()) log.debug("git log -p failed: {}", r.output());
            return history;
        }
        for (String record : r.output().split(RS)) {
            if (record.isBlank()) continue;
            String[] f = record.split(US, -1);
            if (f.length < 6) continue;
            String hash = f[0].trim();
            String shortHash = f[1].trim();
            String author = f[2];
            String dateIso = f[4].trim();
            // f[5] = subject + "\n" + patch body for this file.
            int nl = f[5].indexOf('\n');
            String subject = nl >= 0 ? f[5].substring(0, nl) : f[5];
            String patch = nl >= 0 ? f[5].substring(nl + 1) : "";
            for (GitFileDiff diff : parsePatch(patch, specificPath ? path : null)) {
                diff.setCommitHash(hash);
                diff.setCommitShortHash(shortHash);
                diff.setAuthor(author);
                diff.setDateIso(dateIso);
                diff.setSubject(subject);
                history.add(diff);
            }
        }
        return history;
    }

    // ── File content at a revision ───────────────────────────────────

    /** Raw content of {@code path} at {@code ref} (e.g. for full-file compare). */
    public String fileAtRef(String ref, String path) {
        if (!notBlank(ref) || !notBlank(path)) return "";
        GitResult r = exec(repoRoot(), List.of("show", ref + ":" + path));
        return r.ok() ? r.output() : "";
    }

    // ── Patch parsing ────────────────────────────────────────────────

    /**
     * Split a unified patch (the body of {@code git show}/{@code git log -p})
     * into one {@link GitFileDiff} per file. When {@code onlyPath} is non-null,
     * only the block for that path is returned (file-history restricts the log
     * to one path, but rename-following can still surface the old name).
     */
    private List<GitFileDiff> parsePatch(String patch, String onlyPath) {
        List<GitFileDiff> files = new ArrayList<>();
        if (patch == null) return files;
        int start = patch.indexOf("diff --git ");
        if (start < 0) return files;
        String[] blocks = patch.substring(start).split("\\ndiff --git ");
        for (String raw : blocks) {
            String block = raw.startsWith("diff --git ")
                    ? raw.substring("diff --git ".length())
                    : raw;
            GitFileDiff diff = parseBlock(block);
            if (diff == null) continue;
            files.add(diff);
        }
        if (onlyPath != null && files.size() > 1) {
            // file-history: keep only the block for the requested file (by either name).
            List<GitFileDiff> filtered = new ArrayList<>();
            for (GitFileDiff d : files) {
                if (onlyPath.equals(d.getPath()) || onlyPath.equals(d.getOldPath())) {
                    filtered.add(d);
                }
            }
            if (!filtered.isEmpty()) return filtered;
        }
        return files;
    }

    /** Parse one {@code diff --git} block (with the leading marker stripped). */
    private GitFileDiff parseBlock(String block) {
        String[] lines = block.split("\n", -1);
        String oldPath = null;
        String newPath = null;
        String changeType = "MODIFIED";
        boolean binary = false;
        long added = 0;
        long removed = 0;
        StringBuilder unified = new StringBuilder();
        boolean inBody = false;

        for (String ln : lines) {
            if (ln.startsWith("new file mode")) {
                changeType = "ADDED";
            } else if (ln.startsWith("deleted file mode")) {
                changeType = "DELETED";
            } else if (ln.startsWith("rename from ")) {
                changeType = "RENAMED";
                oldPath = ln.substring("rename from ".length()).trim();
            } else if (ln.startsWith("rename to ")) {
                changeType = "RENAMED";
                newPath = ln.substring("rename to ".length()).trim();
            } else if (ln.startsWith("copy from ")) {
                changeType = "COPIED";
                oldPath = ln.substring("copy from ".length()).trim();
            } else if (ln.startsWith("copy to ")) {
                changeType = "COPIED";
                newPath = ln.substring("copy to ".length()).trim();
            } else if (ln.startsWith("Binary files") || ln.startsWith("GIT binary patch")) {
                binary = true;
                unified.append(ln).append("\n");
            } else if (ln.startsWith("--- ")) {
                inBody = true;
                String p = ln.substring(4).trim();
                if (!isDevNull(p)) oldPath = stripPrefix(p);
                unified.append(ln).append("\n");
            } else if (ln.startsWith("+++ ")) {
                String p = ln.substring(4).trim();
                if (!isDevNull(p)) newPath = stripPrefix(p);
                unified.append(ln).append("\n");
            } else if (inBody) {
                unified.append(ln).append("\n");
                if (ln.startsWith("+")) added++;
                else if (ln.startsWith("-")) removed++;
            }
        }

        // Fallback path extraction from the "a/old b/new" header line.
        if (newPath == null && oldPath == null && lines.length > 0) {
            String header = lines[0];
            int bIdx = header.indexOf(" b/");
            if (header.startsWith("a/") && bIdx > 0) {
                oldPath = header.substring(2, bIdx);
                newPath = header.substring(bIdx + 3);
            }
        }

        String path = newPath != null ? newPath : oldPath;
        if (path == null) return null;

        return GitFileDiff.builder()
                .path(path)
                .oldPath((oldPath != null && !oldPath.equals(path)) ? oldPath : null)
                .changeType(changeType)
                .unifiedDiff(unified.toString())
                .linesAdded(added)
                .linesRemoved(removed)
                .binary(binary)
                .build();
    }

    // ── git subprocess ───────────────────────────────────────────────

    private GitResult exec(Path dir, List<String> args) {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add("git");
        cmd.addAll(args);
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(dir != null ? dir.toFile() : null)
                    .redirectErrorStream(true);
            // Avoid any pager / locale surprises in a non-interactive context.
            pb.environment().put("GIT_PAGER", "cat");
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = pb.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitResult(124, new String(bytes, StandardCharsets.UTF_8));
            }
            return new GitResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return new GitResult(127, "git not available: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new GitResult(130, "interrupted");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isDevNull(String p) {
        return "/dev/null".equals(p);
    }

    private static String stripPrefix(String p) {
        if (p.startsWith("a/") || p.startsWith("b/")) return p.substring(2);
        return p;
    }

    private static int intGroup(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
