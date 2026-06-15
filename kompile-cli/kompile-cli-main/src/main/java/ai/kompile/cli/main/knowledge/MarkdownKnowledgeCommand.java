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

package ai.kompile.cli.main.knowledge;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
        name = "markdown",
        description = "Manage Markdown notes in a local folder, Git repo, or Obsidian vault.",
        subcommands = {
                MarkdownKnowledgeCommand.ListCommand.class,
                MarkdownKnowledgeCommand.ReadCommand.class,
                MarkdownKnowledgeCommand.CreateCommand.class,
                MarkdownKnowledgeCommand.UpdateCommand.class,
                MarkdownKnowledgeCommand.DeleteCommand.class,
                MarkdownKnowledgeCommand.SyncCommand.class,
                MarkdownKnowledgeCommand.TestCommand.class
        },
        mixinStandardHelpOptions = true
)
public class MarkdownKnowledgeCommand implements Callable<Integer> {

    @Option(names = "--store", defaultValue = "local-folder",
            description = "Store type: local-folder, git-repository, obsidian. Default: ${DEFAULT-VALUE}")
    private String store;

    @Option(names = "--path", required = true,
            description = "Folder path, Git working tree path, or Obsidian vault folder/scope.")
    private String path;

    @Option(names = "--repo-url", description = "Git remote URL when --store=git-repository.")
    private String repositoryUrl;

    @Option(names = "--branch", defaultValue = "main", description = "Git branch. Default: ${DEFAULT-VALUE}")
    private String branch;

    @Option(names = "--no-remote-sync", description = "Disable Git pull/push for this operation.")
    private boolean noRemoteSync;

    @Option(names = "--no-auto-commit", description = "Do not auto-commit writes/deletes in Git stores.")
    private boolean noAutoCommit;

    @Option(names = "--auth-mode", defaultValue = "system-git",
            description = "Git auth mode: none, system-git, https-token. Default: ${DEFAULT-VALUE}")
    private String authMode;

    @Option(names = "--git-username", defaultValue = "x-access-token",
            description = "Username for --auth-mode=https-token. Default: ${DEFAULT-VALUE}")
    private String gitUsername;

    @Option(names = "--git-token", description = "Git HTTPS token. Defaults to KOMPILE_GIT_TOKEN when omitted.")
    private String gitToken;

    @Option(names = "--obsidian-api-url",
            description = "Obsidian Local REST API URL. Omit to treat --path as a local vault path.")
    private String obsidianApiUrl;

    @Option(names = "--obsidian-token", description = "Obsidian Local REST API token. Defaults to KOMPILE_OBSIDIAN_TOKEN when omitted.")
    private String obsidianToken;

    @Option(names = "--strict-obsidian-tls", description = "Do not trust the Obsidian plugin self-signed certificate.")
    private boolean strictObsidianTls;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    MarkdownStore openStore() {
        String normalized = normalizeStore(store);
        return switch (normalized) {
            case "git-repository" -> new GitMarkdownStore(
                    Paths.get(path), repositoryUrl, branch, !noRemoteSync, !noAutoCommit,
                    parseGitAuthMode(authMode), gitUsername, firstNonBlank(gitToken, System.getenv("KOMPILE_GIT_TOKEN")));
            case "obsidian" -> {
                if (isBlank(obsidianApiUrl)) {
                    yield new LocalMarkdownStore(Paths.get(path));
                }
                yield new ObsidianRestMarkdownStore(
                        path, obsidianApiUrl, firstNonBlank(obsidianToken, System.getenv("KOMPILE_OBSIDIAN_TOKEN")), !strictObsidianTls);
            }
            case "local-folder" -> new LocalMarkdownStore(Paths.get(path));
            default -> throw new IllegalArgumentException("Unsupported markdown store: " + store);
        };
    }

    private static String normalizeStore(String store) {
        if (store == null) return "local-folder";
        return store.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static GitAuthMode parseGitAuthMode(String value) {
        if (value == null) return GitAuthMode.SYSTEM_GIT;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "none" -> GitAuthMode.NONE;
            case "https-token", "token" -> GitAuthMode.HTTPS_TOKEN;
            case "system-git", "system" -> GitAuthMode.SYSTEM_GIT;
            default -> throw new IllegalArgumentException("Unsupported Git auth mode: " + value);
        };
    }

    @Command(name = "list", description = "List Markdown files in the store.", mixinStandardHelpOptions = true)
    static class ListCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Override
        public Integer call() throws Exception {
            List<MarkdownEntry> entries = parent.openStore().list();
            if (entries.isEmpty()) {
                System.out.println("No Markdown files found.");
                return 0;
            }
            for (MarkdownEntry entry : entries) {
                System.out.printf("%s\t%s\t%s\t%s%n",
                        entry.path(), nullToEmpty(entry.title()), nullToEmpty(entry.tags()), nullToEmpty(entry.updatedAt()));
            }
            return 0;
        }
    }

    @Command(name = "read", description = "Print a Markdown file body.", mixinStandardHelpOptions = true)
    static class ReadCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Parameters(index = "0", description = "Relative Markdown file path.")
        private String file;

        @Option(names = "--with-frontmatter", description = "Print the full file including frontmatter.")
        private boolean withFrontmatter;

        @Override
        public Integer call() throws Exception {
            MarkdownDocument doc = parent.openStore().read(file)
                    .orElseThrow(() -> new IllegalArgumentException("Markdown file not found: " + file));
            System.out.println(withFrontmatter ? doc.rawContent() : doc.body());
            return 0;
        }
    }

    @Command(name = "create", description = "Create a Markdown note.", mixinStandardHelpOptions = true)
    static class CreateCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Option(names = "--title", required = true, description = "Note title.")
        private String title;

        @Option(names = "--file", description = "Relative output path. Defaults to a sanitized title.")
        private String file;

        @Option(names = "--content", description = "Markdown body content.")
        private String content;

        @Option(names = "--content-file", description = "Read Markdown body from this local file.")
        private Path contentFile;

        @Option(names = "--tags", description = "Comma-separated tags.")
        private String tags;

        @Option(names = "--fact-sheet-id", description = "Kompile FactSheet ID to store in frontmatter.")
        private Long factSheetId;

        @Option(names = "--note-id", description = "Kompile Note ID to store in frontmatter.")
        private Long noteId;

        @Override
        public Integer call() throws Exception {
            String target = isBlank(file) ? sanitizeFileName(title) + ".md" : ensureMarkdownExtension(file);
            String body = readBody(content, contentFile);
            MarkdownDocument doc = MarkdownDocument.create(target, title, tags, noteId, factSheetId, body);
            parent.openStore().write(target, doc.rawContent(), false);
            System.out.println("Created " + target);
            return 0;
        }
    }

    @Command(name = "update", description = "Update or replace an existing Markdown note.", mixinStandardHelpOptions = true)
    static class UpdateCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Parameters(index = "0", description = "Relative Markdown file path.")
        private String file;

        @Option(names = "--title", description = "Replace note title.")
        private String title;

        @Option(names = "--content", description = "Replace Markdown body content.")
        private String content;

        @Option(names = "--content-file", description = "Replace Markdown body from this local file.")
        private Path contentFile;

        @Option(names = "--tags", description = "Replace comma-separated tags.")
        private String tags;

        @Override
        public Integer call() throws Exception {
            MarkdownStore store = parent.openStore();
            MarkdownDocument existing = store.read(file)
                    .orElseThrow(() -> new IllegalArgumentException("Markdown file not found: " + file));
            String newTitle = firstNonBlank(title, existing.title(), stripExtension(Paths.get(file).getFileName().toString()));
            String newTags = tags != null ? tags : existing.tags();
            String newBody = content != null || contentFile != null ? readBody(content, contentFile) : existing.body();
            MarkdownDocument updated = MarkdownDocument.create(file, newTitle, newTags,
                    existing.noteId(), existing.factSheetId(), newBody, existing.createdAt());
            store.write(file, updated.rawContent(), true);
            System.out.println("Updated " + file);
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a Markdown note.", mixinStandardHelpOptions = true)
    static class DeleteCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Parameters(index = "0", description = "Relative Markdown file path.")
        private String file;

        @Override
        public Integer call() throws Exception {
            parent.openStore().delete(file);
            System.out.println("Deleted " + file);
            return 0;
        }
    }

    @Command(name = "sync", description = "Run Git pull/commit/push for a Git-backed Markdown store.", mixinStandardHelpOptions = true)
    static class SyncCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Option(names = "--message", defaultValue = "Update Kompile Markdown notes", description = "Commit message.")
        private String message;

        @Override
        public Integer call() throws Exception {
            parent.openStore().sync(message);
            System.out.println("Sync complete.");
            return 0;
        }
    }

    @Command(name = "test", description = "Test store access and configured auth.", mixinStandardHelpOptions = true)
    static class TestCommand implements Callable<Integer> {
        @ParentCommand MarkdownKnowledgeCommand parent;

        @Override
        public Integer call() throws Exception {
            StoreTestResult result = parent.openStore().test();
            System.out.println((result.success() ? "OK: " : "ERROR: ") + result.message());
            return result.success() ? 0 : 1;
        }
    }

    interface MarkdownStore {
        List<MarkdownEntry> list() throws Exception;
        Optional<MarkdownDocument> read(String relativePath) throws Exception;
        void write(String relativePath, String content, boolean replace) throws Exception;
        void delete(String relativePath) throws Exception;
        StoreTestResult test() throws Exception;
        default void sync(String message) throws Exception {
            test();
        }
    }

    static class LocalMarkdownStore implements MarkdownStore {
        protected final Path root;

        LocalMarkdownStore(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public List<MarkdownEntry> list() throws Exception {
            ensureRoot();
            try (Stream<Path> stream = Files.walk(root)) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .filter(path -> !isIgnored(path))
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                        .map(path -> {
                            try {
                                String relative = root.relativize(path).toString().replace('\\', '/');
                                MarkdownDocument doc = MarkdownDocument.parse(relative, Files.readString(path, StandardCharsets.UTF_8));
                                return new MarkdownEntry(relative, doc.title(), doc.tags(), doc.updatedAt());
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .collect(Collectors.toList());
            }
        }

        @Override
        public Optional<MarkdownDocument> read(String relativePath) throws Exception {
            Path path = resolve(relativePath);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(MarkdownDocument.parse(relativePath, Files.readString(path, StandardCharsets.UTF_8)));
        }

        @Override
        public void write(String relativePath, String content, boolean replace) throws Exception {
            Path path = resolve(relativePath);
            if (Files.exists(path) && !replace) {
                throw new IllegalArgumentException("File already exists: " + relativePath);
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }

        @Override
        public void delete(String relativePath) throws Exception {
            Path path = resolve(relativePath);
            if (!Files.deleteIfExists(path)) {
                throw new IllegalArgumentException("File not found: " + relativePath);
            }
        }

        @Override
        public StoreTestResult test() throws Exception {
            ensureRoot();
            if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
                return new StoreTestResult(false, "Folder is not readable and writable: " + root);
            }
            return new StoreTestResult(true, "Folder is readable and writable: " + root);
        }

        protected void ensureRoot() throws IOException {
            Files.createDirectories(root);
        }

        protected Path resolve(String relativePath) throws IOException {
            ensureRoot();
            String sanitized = normalizeRelativePath(relativePath);
            Path path = root.resolve(sanitized).normalize();
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("Path escapes Markdown store: " + relativePath);
            }
            return path;
        }

        private boolean isIgnored(Path path) {
            Path relative = root.relativize(path);
            for (Path part : relative) {
                String name = part.toString();
                if (name.equals(".git") || name.equals(".obsidian") || name.equals("node_modules") || name.equals(".trash")) {
                    return true;
                }
            }
            return false;
        }
    }

    static class GitMarkdownStore extends LocalMarkdownStore {
        private final String repositoryUrl;
        private final String branch;
        private final boolean remoteSync;
        private final boolean autoCommit;
        private final GitAuthMode authMode;
        private final String username;
        private final String token;

        GitMarkdownStore(Path root, String repositoryUrl, String branch, boolean remoteSync, boolean autoCommit,
                         GitAuthMode authMode, String username, String token) {
            super(root);
            this.repositoryUrl = trimToNull(repositoryUrl);
            this.branch = firstNonBlank(branch, "main");
            this.remoteSync = remoteSync;
            this.autoCommit = autoCommit;
            this.authMode = authMode == null ? GitAuthMode.SYSTEM_GIT : authMode;
            this.username = firstNonBlank(username, "x-access-token");
            this.token = trimToNull(token);
        }

        @Override
        public List<MarkdownEntry> list() throws Exception {
            prepare(true);
            return super.list();
        }

        @Override
        public Optional<MarkdownDocument> read(String relativePath) throws Exception {
            prepare(true);
            return super.read(relativePath);
        }

        @Override
        public void write(String relativePath, String content, boolean replace) throws Exception {
            prepare(true);
            super.write(relativePath, content, replace);
            commitAndPush("Update Markdown note: " + relativePath);
        }

        @Override
        public void delete(String relativePath) throws Exception {
            prepare(true);
            super.delete(relativePath);
            commitAndPush("Delete Markdown note: " + relativePath);
        }

        @Override
        public StoreTestResult test() throws Exception {
            ensureRoot();
            runGit(root, false, "--version");
            if (repositoryUrl != null && remoteSync) {
                runGitWithAuth(root, false, "ls-remote", "--heads", repositoryUrl);
                return new StoreTestResult(true, "Git remote is reachable with " + authMode.label() + ".");
            }
            return new StoreTestResult(true, "Local Git working tree is available. Remote sync is disabled or no remote URL was provided.");
        }

        @Override
        public void sync(String message) throws Exception {
            prepare(true);
            commitAndPush(message);
        }

        private void prepare(boolean pullRemote) throws Exception {
            ensureRoot();
            Path gitDir = root.resolve(".git");
            if (!Files.exists(gitDir)) {
                if (repositoryUrl != null && isDirectoryEmpty(root)) {
                    cloneRepository();
                    return;
                }
                runGit(root, false, "init");
                runGit(root, true, "checkout", "-B", branch);
            }
            if (repositoryUrl != null) {
                CommandResult remote = runGit(root, true, "remote", "get-url", "origin");
                if (remote.exitCode() == 0) {
                    runGit(root, false, "remote", "set-url", "origin", repositoryUrl);
                } else {
                    runGit(root, false, "remote", "add", "origin", repositoryUrl);
                }
            }
            runGit(root, true, "checkout", branch);
            runGit(root, true, "checkout", "-B", branch);
            if (pullRemote && repositoryUrl != null && remoteSync) {
                runGitWithAuth(root, false, "pull", "--ff-only", "origin", branch);
            }
        }

        private void cloneRepository() throws Exception {
            Path parent = root.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Git repository path must have a parent: " + root);
            }
            Files.deleteIfExists(root);
            Files.createDirectories(parent);
            CommandResult clone = runGitWithAuth(parent, true, "clone", "--branch", branch, repositoryUrl, root.getFileName().toString());
            if (clone.exitCode() != 0) {
                runGitWithAuth(parent, false, "clone", repositoryUrl, root.getFileName().toString());
                runGit(root, true, "checkout", "-B", branch);
            }
        }

        private void commitAndPush(String message) throws Exception {
            if (!autoCommit) {
                return;
            }
            runGit(root, false, "add", "-A");
            CommandResult status = runGit(root, false, "status", "--porcelain");
            if (status.output().isBlank()) {
                return;
            }
            runGit(root, false,
                    "-c", "user.name=Kompile",
                    "-c", "user.email=kompile@local",
                    "commit", "-m", firstNonBlank(message, "Update Kompile Markdown notes"));
            if (repositoryUrl != null && remoteSync) {
                runGitWithAuth(root, false, "push", "-u", "origin", branch);
            }
        }

        private CommandResult runGit(Path directory, boolean allowFailure, String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            return runProcess(directory, allowFailure, null, command.toArray(String[]::new));
        }

        private CommandResult runGitWithAuth(Path directory, boolean allowFailure, String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            return runProcess(directory, allowFailure, gitAuthContext(), command.toArray(String[]::new));
        }

        private GitAuthContext gitAuthContext() {
            if (authMode != GitAuthMode.HTTPS_TOKEN) {
                return null;
            }
            if (repositoryUrl == null || !(repositoryUrl.startsWith("https://") || repositoryUrl.startsWith("http://"))) {
                throw new IllegalStateException("HTTPS token auth requires an HTTP(S) git remote URL.");
            }
            if (isBlank(token)) {
                throw new IllegalStateException("No Git HTTPS token provided. Use --git-token or KOMPILE_GIT_TOKEN.");
            }
            return new GitAuthContext(username, token);
        }
    }

    static class ObsidianRestMarkdownStore implements MarkdownStore {
        private static final Pattern FILENAME_PATTERN = Pattern.compile("\\\"filename\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        private final String scope;
        private final String baseUrl;
        private final String token;
        private final HttpClient client;

        ObsidianRestMarkdownStore(String scope, String baseUrl, String token, boolean insecureTls) {
            this.scope = trimSlashes(firstNonBlank(scope, ""));
            this.baseUrl = stripTrailingSlash(baseUrl);
            this.token = trimToNull(token);
            if (this.token == null) {
                throw new IllegalArgumentException("Obsidian REST mode requires --obsidian-token or KOMPILE_OBSIDIAN_TOKEN.");
            }
            this.client = insecureTls ? insecureHttpClient() : HttpClient.newHttpClient();
        }

        @Override
        public List<MarkdownEntry> list() throws Exception {
            String response = request("POST", "/search/simple/?query=.md", scope, 200);
            List<String> files = parseFilenames(response).stream()
                    .filter(file -> file.endsWith(".md"))
                    .filter(file -> scope.isEmpty() || file.startsWith(scope + "/") || file.equals(scope))
                    .sorted()
                    .toList();
            List<MarkdownEntry> entries = new ArrayList<>();
            for (String file : files) {
                Optional<MarkdownDocument> doc = read(file);
                doc.ifPresent(markdownDocument -> entries.add(new MarkdownEntry(file,
                        markdownDocument.title(), markdownDocument.tags(), markdownDocument.updatedAt())));
            }
            return entries;
        }

        @Override
        public Optional<MarkdownDocument> read(String relativePath) throws Exception {
            String vaultPath = vaultPath(relativePath);
            HttpResponse<String> response = send("GET", "/vault/" + encodeVaultPath(vaultPath), null);
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            requireSuccess(response, 200);
            return Optional.of(MarkdownDocument.parse(vaultPath, response.body()));
        }

        @Override
        public void write(String relativePath, String content, boolean replace) throws Exception {
            String vaultPath = vaultPath(relativePath);
            if (!replace && read(vaultPath).isPresent()) {
                throw new IllegalArgumentException("File already exists: " + vaultPath);
            }
            request("PUT", "/vault/" + encodeVaultPath(vaultPath), content, 200, 201, 204);
        }

        @Override
        public void delete(String relativePath) throws Exception {
            String vaultPath = vaultPath(relativePath);
            request("DELETE", "/vault/" + encodeVaultPath(vaultPath), null, 200, 204);
        }

        @Override
        public StoreTestResult test() throws Exception {
            request("POST", "/search/simple/?query=.md", scope, 200);
            return new StoreTestResult(true, "Obsidian Local REST API is reachable and the token is valid.");
        }

        private String vaultPath(String relativePath) {
            String normalized = trimSlashes(normalizeRelativePath(relativePath));
            if (scope.isEmpty() || normalized.startsWith(scope + "/")) {
                return normalized;
            }
            return scope + "/" + normalized;
        }

        private String request(String method, String path, String body, int... expectedStatus) throws Exception {
            HttpResponse<String> response = send(method, path, body);
            requireSuccess(response, expectedStatus);
            return response.body();
        }

        private HttpResponse<String> send(String method, String path, String body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "*/*");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "text/plain");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private static List<String> parseFilenames(String body) {
            List<String> files = new ArrayList<>();
            Matcher matcher = FILENAME_PATTERN.matcher(body == null ? "" : body);
            while (matcher.find()) {
                files.add(unescapeJson(matcher.group(1)));
            }
            if (files.isEmpty() && body != null && body.startsWith("[") && body.contains(".md")) {
                Matcher stringMatcher = Pattern.compile("\\\"([^\\\"]+\\.md)\\\"").matcher(body);
                while (stringMatcher.find()) {
                    files.add(unescapeJson(stringMatcher.group(1)));
                }
            }
            return files;
        }
    }

    static MarkdownDocument readDocument(String relativePath, String content) {
        return MarkdownDocument.parse(relativePath, content);
    }

    record MarkdownEntry(String path, String title, String tags, String updatedAt) {}

    record StoreTestResult(boolean success, String message) {}

    record GitAuthContext(String username, String token) {}

    record CommandResult(int exitCode, String output) {}

    enum GitAuthMode {
        NONE("no remote credentials"),
        SYSTEM_GIT("system Git credentials"),
        HTTPS_TOKEN("HTTPS token");

        private final String label;

        GitAuthMode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record MarkdownDocument(
            String path,
            String title,
            String tags,
            Long noteId,
            Long factSheetId,
            String createdAt,
            String updatedAt,
            String body,
            String rawContent
    ) {
        static MarkdownDocument create(String path, String title, String tags, Long noteId, Long factSheetId, String body) {
            return create(path, title, tags, noteId, factSheetId, body, Instant.now().toString());
        }

        static MarkdownDocument create(String path, String title, String tags, Long noteId, Long factSheetId, String body, String createdAt) {
            String now = Instant.now().toString();
            String effectiveTitle = firstNonBlank(title, stripExtension(Paths.get(path).getFileName().toString()), "Untitled");
            String effectiveBody = firstNonBlank(body, "");
            if (!effectiveBody.startsWith("# ")) {
                effectiveBody = "# " + effectiveTitle + "\n\n" + effectiveBody;
            }
            String frontmatter = frontmatter(tags, noteId, factSheetId, firstNonBlank(createdAt, now), now);
            return new MarkdownDocument(path, effectiveTitle, tags, noteId, factSheetId,
                    firstNonBlank(createdAt, now), now, effectiveBody, frontmatter + "\n" + effectiveBody.trim() + "\n");
        }

        static MarkdownDocument parse(String path, String raw) {
            String body = raw == null ? "" : raw;
            Map<String, List<String>> fm = new LinkedHashMap<>();
            if (body.startsWith("---")) {
                int end = body.indexOf("\n---", 3);
                if (end >= 0) {
                    String block = body.substring(3, end).trim();
                    body = body.substring(end + 4).trim();
                    fm = parseFrontmatter(block);
                }
            }
            String title = scalar(fm, "title")
                    .orElse(titleFromBody(body).orElse(stripExtension(Paths.get(path).getFileName().toString())));
            String tags = scalar(fm, "tags").orElse(String.join(",", fm.getOrDefault("tags[]", List.of())));
            Long noteId = scalar(fm, "kompile_note_id").flatMap(MarkdownKnowledgeCommand::parseLong).orElse(null);
            Long factSheetId = scalar(fm, "kompile_fact_sheet").flatMap(MarkdownKnowledgeCommand::parseLong).orElse(null);
            String created = scalar(fm, "created").orElse(null);
            String updated = scalar(fm, "updated").orElse(null);
            return new MarkdownDocument(path, title, tags, noteId, factSheetId, created, updated, body, raw == null ? "" : raw);
        }

        private static String frontmatter(String tags, Long noteId, Long factSheetId, String createdAt, String updatedAt) {
            StringBuilder sb = new StringBuilder("---\n");
            if (noteId != null) {
                sb.append("kompile_note_id: ").append(noteId).append("\n");
            }
            if (factSheetId != null) {
                sb.append("kompile_fact_sheet: ").append(factSheetId).append("\n");
            }
            if (!isBlank(tags)) {
                sb.append("tags:\n");
                for (String tag : tags.split(",")) {
                    String cleaned = tag.trim();
                    if (!cleaned.isBlank()) {
                        sb.append("  - ").append(escapeYaml(cleaned)).append("\n");
                    }
                }
            }
            sb.append("created: ").append(createdAt).append("\n");
            sb.append("updated: ").append(updatedAt).append("\n");
            sb.append("---\n");
            return sb.toString();
        }
    }

    private static CommandResult runProcess(Path directory, boolean allowFailure, GitAuthContext authContext, String... command) throws Exception {
        Path askPassScript = null;
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        try {
            if (authContext != null) {
                askPassScript = createAskPassScript();
                builder.environment().put("GIT_ASKPASS", askPassScript.toString());
                builder.environment().put("GIT_TERMINAL_PROMPT", "0");
                builder.environment().put("KOMPILE_GIT_USERNAME", authContext.username());
                builder.environment().put("KOMPILE_GIT_TOKEN", authContext.token());
            }
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + String.join(" ", command));
            }
            int exitCode = process.exitValue();
            if (exitCode != 0 && !allowFailure) {
                throw new IllegalStateException("Command failed (" + String.join(" ", command) + "): " + output.trim());
            }
            return new CommandResult(exitCode, output);
        } finally {
            if (askPassScript != null) {
                Files.deleteIfExists(askPassScript);
            }
        }
    }

    private static Path createAskPassScript() throws IOException {
        Path script = Files.createTempFile("kompile-cli-git-askpass-", ".sh");
        String content = "#!/bin/sh\n" +
                "case \"$1\" in\n" +
                "  *Username*) printf '%s\\n' \"$KOMPILE_GIT_USERNAME\" ;;\n" +
                "  *) printf '%s\\n' \"$KOMPILE_GIT_TOKEN\" ;;\n" +
                "esac\n";
        Files.writeString(script, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        script.toFile().setExecutable(true, true);
        return script;
    }

    private static String readBody(String content, Path contentFile) throws IOException {
        if (contentFile != null) {
            return Files.readString(contentFile, StandardCharsets.UTF_8);
        }
        if (content != null) {
            return content;
        }
        throw new IllegalArgumentException("Provide --content or --content-file.");
    }

    private static Map<String, List<String>> parseFrontmatter(String block) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        String activeListKey = null;
        for (String rawLine : block.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.trim().startsWith("-") && activeListKey != null) {
                values.computeIfAbsent(activeListKey + "[]", ignored -> new ArrayList<>())
                        .add(unquote(line.trim().substring(1).trim()));
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                activeListKey = null;
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                activeListKey = key;
            } else {
                activeListKey = null;
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unquote(value));
            }
        }
        return values;
    }

    private static Optional<String> scalar(Map<String, List<String>> values, String key) {
        List<String> list = values.get(key);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(list.get(0));
    }

    private static Optional<String> titleFromBody(String body) {
        if (body == null) return Optional.empty();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return Optional.of(trimmed.substring(2).trim());
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void requireSuccess(HttpResponse<String> response, int... expected) {
        for (int status : expected) {
            if (response.statusCode() == status) {
                return;
            }
        }
        throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
    }

    private static HttpClient insecureHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder().sslContext(context).build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create insecure HTTP client", e);
        }
    }

    private static String normalizeRelativePath(String relativePath) {
        String value = firstNonBlank(relativePath, "").replace('\\', '/');
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.contains("..")) {
            Path normalized = Paths.get(value).normalize();
            value = normalized.toString().replace('\\', '/');
        }
        return ensureMarkdownExtension(value);
    }

    private static String ensureMarkdownExtension(String value) {
        String trimmed = firstNonBlank(value, "Untitled.md").trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") ? trimmed : trimmed + ".md";
    }

    private static String sanitizeFileName(String title) {
        String cleaned = firstNonBlank(title, "Untitled")
                .replaceAll("[/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? "Untitled" : cleaned;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "Untitled";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String trimSlashes(String value) {
        String result = firstNonBlank(value, "").replace('\\', '/').trim();
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String stripTrailingSlash(String value) {
        String result = firstNonBlank(value, "").trim();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static String encodeVaultPath(String path) {
        return Stream.of(path.split("/"))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static String escapeYaml(String value) {
        if (value.matches("[A-Za-z0-9_.-]+")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return trimmed;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static boolean isDirectoryEmpty(Path path) {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
