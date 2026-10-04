package ai.kompile.cli.main.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit mounts and the shared read/write boundary for both ends of sync. */
final class SyncPaths {
    private static final List<String> SETTINGS = List.of(
            "codex/config.toml", "claude/settings.json", "claude/settings.local.json",
            "claude/global-state.json");
    private static final List<String> CREDENTIALS = List.of(
            "codex/auth.json", "claude/.credentials.json");

    private final Path scopeRoot;
    private final String scope;
    private final Path userHome;
    private final Path codexHome;
    private final Path claudeHome;
    private final Path claudeGlobalState;

    SyncPaths(Path scopeRoot, String scope, Path userHome) {
        this(scopeRoot, scope, userHome, userHome.resolve(".codex"), userHome.resolve(".claude"));
    }

    SyncPaths(Path scopeRoot, String scope, Path userHome, Path codexHome, Path claudeHome) {
        this(scopeRoot, scope, userHome, codexHome, claudeHome,
                claudeHome.toAbsolutePath().normalize().equals(userHome.resolve(".claude").toAbsolutePath().normalize())
                        ? userHome.resolve(".claude.json") : claudeHome.resolve(".claude.json"));
    }

    private SyncPaths(Path scopeRoot, String scope, Path userHome, Path codexHome, Path claudeHome, Path claudeGlobalState) {
        this.scopeRoot = expandHome(scopeRoot).toAbsolutePath().normalize();
        if (!SyncScope.GLOBAL.equals(scope) && !SyncScope.PROJECT.equals(scope)) {
            throw new IllegalArgumentException("Unknown sync scope: " + scope);
        }
        this.scope = scope;
        this.userHome = expandHome(userHome).toAbsolutePath().normalize();
        this.codexHome = expandHome(codexHome).toAbsolutePath().normalize();
        this.claudeHome = expandHome(claudeHome).toAbsolutePath().normalize();
        this.claudeGlobalState = expandHome(claudeGlobalState).toAbsolutePath().normalize();
    }

    static SyncPaths configured(Path root, String scope, Path explicitUserHome) {
        if (explicitUserHome != null) return new SyncPaths(root, scope, explicitUserHome);
        Path home = Path.of(System.getProperty("user.home"));
        String codex = System.getProperty("kompile.codex.home");
        if (codex == null || codex.isBlank()) codex = System.getenv("CODEX_HOME");
        String claude = System.getenv("CLAUDE_CONFIG_DIR");
        boolean customClaude = claude != null && !claude.isBlank();
        Path claudeRoot = customClaude ? Path.of(claude) : home.resolve(".claude");
        return new SyncPaths(root, scope, home,
                codex == null || codex.isBlank() ? home.resolve(".codex") : Path.of(codex),
                claudeRoot, customClaude ? claudeRoot.resolve(".claude.json") : home.resolve(".claude.json"));
    }

    static Path expandHome(Path path) {
        if (!path.isAbsolute() && path.getNameCount() > 0 && path.getName(0).toString().equals("~")) {
            Path home = Path.of(System.getProperty("user.home"));
            return path.getNameCount() == 1 ? home : home.resolve(path.subpath(1, path.getNameCount()));
        }
        return path;
    }

    String scope() { return scope; }

    /** Stable profile identity, never credential contents. */
    String mountIdentity() {
        return SyncSession.sha256((scope + "\n" + scopeRoot + "\n" + userHome + "\n"
                + codexHome + "\n" + claudeHome + "\n" + claudeGlobalState).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void validateComponents(List<String> components) {
        if (SyncScope.PROJECT.equals(scope) && SyncCatalog.includesHarness(components)) {
            throw new IllegalArgumentException("Harness settings and credentials require --scope global; they are never copied into projects.");
        }
    }

    Path componentRoot(String component) {
        return scopeRoot.resolve(SyncCatalog.componentDir(SyncScope.GLOBAL, component));
    }

    Map<String, Path> harnessFiles(String component) {
        validateComponents(List.of(component));
        List<String> names = switch (component) {
            case SyncCatalog.HARNESS_SETTINGS -> SETTINGS;
            case SyncCatalog.HARNESS_CREDENTIALS -> CREDENTIALS;
            default -> throw new IllegalArgumentException("Not a harness component: " + component);
        };
        Map<String, Path> paths = new LinkedHashMap<>();
        for (String name : names) {
            Path path;
            if (name.equals("claude/global-state.json")) {
                // Claude's default global preferences/MCP state lives BESIDE .claude.
                // With CLAUDE_CONFIG_DIR it lives in that directory as .claude.json.
                path = claudeGlobalState;
            } else {
                int slash = name.indexOf('/');
                Path root = name.startsWith("codex/") ? codexHome : claudeHome;
                path = root.resolve(name.substring(slash + 1));
            }
            paths.put(name, path);
        }
        return paths;
    }

    Path resolve(SyncEntry entry) throws IOException {
        Path target;
        if (SyncCatalog.isHarness(entry.component())) {
            target = harnessFiles(entry.component()).get(entry.relativePath());
            if (target == null) throw new IOException("Harness path is not allowlisted: " + entry.packagePath());
        } else {
            Path root = componentRoot(entry.component()).toAbsolutePath().normalize();
            target = entry.toPath(root).toAbsolutePath().normalize();
            if (!target.startsWith(root)) throw new IOException("Path escapes sync root: " + entry.packagePath());
        }
        rejectLinks(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular sync file: " + entry.packagePath());
        }
        return target;
    }

    static void rejectLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("Symlinks are not allowed in sync paths: " + p);
        }
    }

    byte[] read(SyncEntry entry) throws IOException {
        Path target = resolve(entry);
        if (!SyncCatalog.isHarness(entry.component())) return Files.readAllBytes(target);
        try (var parent = secureParent(target);
             var channel = parent.newByteChannel(target.getFileName(),
                     java.util.Set.of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return java.nio.channels.Channels.newInputStream(channel).readAllBytes();
        }
    }

    /** Traverse from the filesystem root using anchored, no-follow directory handles. */
    static java.nio.file.SecureDirectoryStream<Path> secureParent(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        var current = requireSecure(Files.newDirectoryStream(absolute.getRoot()));
        try {
            for (Path segment : absolute.getRoot().relativize(absolute.getParent())) {
                var next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException e) {
            current.close();
            throw e;
        }
    }

    static java.nio.file.SecureDirectoryStream<Path> requireSecure(java.nio.file.DirectoryStream<Path> stream) throws IOException {
        if (stream instanceof java.nio.file.SecureDirectoryStream<Path> secure) return secure;
        stream.close();
        throw new IOException("Secure directory handles are required for harness sync on this filesystem.");
    }

    void checkUnchanged(SyncEntry entry, String expected) throws IOException {
        Path path = resolve(entry);
        String actual;
        if (SyncCatalog.isHarness(entry.component())) {
            try { actual = SyncSession.sha256(read(entry)); }
            catch (java.nio.file.NoSuchFileException missing) { actual = null; }
        } else {
            actual = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? SyncInventoryScanner.sha256(path) : null;
        }
        if (!Objects.equals(expected, actual)) {
            throw new IOException("Sync target changed since inventory; retry: " + entry.packagePath());
        }
    }

    void write(SyncEntry entry, byte[] bytes) throws IOException {
        Path target = resolve(entry);
        boolean sensitive = SyncCatalog.isHarness(entry.component());
        createParents(target.getParent(), sensitive);
        rejectLinks(target);
        if (sensitive) {
            writeHarness(target, bytes);
            return;
        }
        // Random, privately created temporary files: never follow a predictable .tmp symlink.
        boolean posix = Files.getFileStore(target.getParent()).supportsFileAttributeView("posix");
        Path tmp = posix ? Files.createTempFile(target.getParent(), ".kompile-sync-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(target.getParent(), ".kompile-sync-", ".tmp");
        try {
            Files.write(tmp, bytes);
            rejectLinks(target);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeHarness(Path target, byte[] bytes) throws IOException {
        try (var parent = secureParent(target)) {
            if (parent.getFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class) == null) {
                throw new IOException("Private POSIX permissions are required for harness sync on this filesystem.");
            }
            Path tmp = Path.of(".kompile-sync-" + java.util.UUID.randomUUID() + ".tmp");
            boolean created = false;
            try {
                // CREATE_NEW and NOFOLLOW_LINKS are applied to the anchored parent;
                // keep this handle through writing and replacement, never reopen the temp name.
                try (var channel = parent.newByteChannel(tmp,
                        java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    created = true;
                    var buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    parent.move(tmp, parent, target.getFileName());
                    created = false;
                }
            } finally {
                if (created) parent.deleteFile(tmp);
            }
        }
    }

    private static void createParents(Path path, boolean sensitive) throws IOException {
        rejectLinks(path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (path.getParent() != null) createParents(path.getParent(), sensitive);
        if (sensitive && Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(path);
        }
    }

    void delete(SyncEntry entry) throws IOException {
        Path target = resolve(entry);
        if (!SyncCatalog.isHarness(entry.component())) {
            Files.deleteIfExists(target);
            return;
        }
        try (var parent = secureParent(target)) {
            parent.deleteFile(target.getFileName());
        } catch (java.nio.file.NoSuchFileException missing) {
            // Already absent, including an absent provider directory.
        }
    }
}
