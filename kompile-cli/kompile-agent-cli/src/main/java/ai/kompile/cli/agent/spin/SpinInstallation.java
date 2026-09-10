/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Versioned, checksum-verified installation lifecycle for a Kompile spin. */
public final class SpinInstallation {
    public static final String CURRENT_FILE = "current";

    private SpinInstallation() { }

    public static Installed install(Path archive, Path requestedHome) throws IOException {
        return install(archive, requestedHome, null, null);
    }

    public static Installed install(Path archive, Path requestedHome,
                                    Path signature, Path trustedPublicKey) throws IOException {
        if (archive == null) throw new IOException("spin archive is required");
        if (trustedPublicKey == null) {
            if (signature != null) {
                throw new IOException("A spin signature requires a trusted publisher public key");
            }
            return installArchive(archive, requestedHome, null);
        }
        Path sidecar = signature == null
                ? SpinSignature.defaultSignaturePath(archive) : signature;
        try (SpinSignature.VerifiedArchive verified = SpinSignature.stageVerified(
                archive, sidecar, trustedPublicKey)) {
            return installArchive(verified.archive(), requestedHome, verified.verification());
        }
    }

    private static Installed installArchive(Path archive, Path requestedHome,
                                            SpinSignature.Verification publisher) throws IOException {
        SpinArchive.Identity identity = SpinArchive.readIdentity(archive);
        Path home = requestedHome == null ? defaultHome(identity.id())
                : requestedHome.toAbsolutePath().normalize();
        rejectSymlinkComponents(home, "spin home");
        Files.createDirectories(home);
        Path installLock = home.resolve(".spin-install.lock");
        rejectSymlinkComponents(installLock, "spin install lock");
        try (FileChannel channel = FileChannel.open(installLock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return installLocked(archive, identity, home, publisher);
        }
    }

    private static Installed installLocked(
            Path archive, SpinArchive.Identity identity, Path home,
            SpinSignature.Verification publisher) throws IOException {
        Path componentsRoot = home.resolve("components");
        rejectSymlinkComponents(componentsRoot, "spin components directory");
        if (Files.isDirectory(componentsRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (var components = Files.list(componentsRoot)) {
                boolean hasAnotherSpin = components
                        .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .anyMatch(path -> !path.getFileName().toString().equals(identity.id()));
                if (hasAnotherSpin) {
                    throw new IOException("Spin home already belongs to another spin: " + home);
                }
            }
        }
        Path componentRoot = componentsRoot.resolve(identity.id()).normalize();
        requireWithin(home, componentRoot);
        Path releases = componentRoot.resolve("releases");
        rejectSymlinkComponents(releases, "spin release directory");
        Files.createDirectories(releases);
        String releaseId = identity.version() + "-" + identity.contentSha256().substring(0, 12);
        Path release = releases.resolve(releaseId).normalize();
        requireWithin(releases, release);
        Path staging = releases.resolve(".staging-" + UUID.randomUUID()).normalize();

        SpinDefinition extracted;
        try {
            extracted = SpinArchive.extractVerified(archive, staging);
            if (!identity.id().equals(extracted.id())
                    || !identity.version().equals(extracted.version())) {
                throw new IOException("Spin identity changed between manifest inspection and extraction");
            }
            if (Files.exists(release)) {
                SpinArchive.verifyDirectory(release);
                SpinArchive.deleteTree(staging);
            } else {
                SpinArchive.moveAtomic(staging, release);
            }
        } catch (IOException | RuntimeException failure) {
            SpinArchive.deleteTree(staging);
            throw failure;
        }

        SpinDefinition installed = SpinDefinition.load(release);
        SpinArchive.verifyDirectory(release);
        SpinDefinition previous = Files.isRegularFile(componentRoot.resolve(CURRENT_FILE),
                LinkOption.NOFOLLOW_LINKS)
                ? current(home, identity.id()).definition() : null;
        try {
            SpinWorkspace.Prepared workspace = SpinWorkspace.prepareForInstall(installed, home);
            // Publish the stable launchers first and switch the current pointer last.
            writeLaunchers(installed, home);
            writeCurrent(componentRoot, releaseId);
            return new Installed(installed, home, componentRoot, releaseId, release,
                    workspace.workspace(), identity.contentSha256(),
                    publisher == null ? null : publisher.publicKeySha256());
        } catch (IOException | RuntimeException failure) {
            if (previous != null) {
                try {
                    SpinWorkspace.prepareForInstall(previous, home);
                    writeLaunchers(previous, home);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    public static Current current(Path home) throws IOException {
        if (home == null) throw new IOException("Spin home is required");
        Path normalizedHome = home.toAbsolutePath().normalize();
        rejectSymlinkComponents(normalizedHome, "spin home");
        Path components = normalizedHome.resolve("components");
        rejectSymlinkComponents(components, "spin components directory");
        if (!Files.isDirectory(components, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("No spin is installed under " + normalizedHome);
        }
        try (var ids = Files.list(components)) {
            var installedIds = ids.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
            if (installedIds.size() != 1) {
                throw new IOException("Expected exactly one installed spin under " + normalizedHome
                        + ", found " + installedIds.size());
            }
            return current(normalizedHome, installedIds.get(0).getFileName().toString());
        }
    }

    public static Current current(Path home, String id) throws IOException {
        Path normalizedHome = home.toAbsolutePath().normalize();
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IOException("Invalid spin id: " + id);
        }
        Path componentRoot = normalizedHome.resolve("components").resolve(id).normalize();
        requireWithin(normalizedHome, componentRoot);
        rejectSymlinkComponents(componentRoot, "spin component directory");
        Path pointer = componentRoot.resolve(CURRENT_FILE);
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(pointer)) {
            throw new IOException("Installed spin has no valid current pointer: " + pointer);
        }
        String releaseId = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        if (!releaseId.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,160}")) {
            throw new IOException("Invalid current spin release id: " + releaseId);
        }
        Path release = componentRoot.resolve("releases").resolve(releaseId).normalize();
        requireWithin(componentRoot.resolve("releases"), release);
        SpinArchive.verifyDirectory(release);
        SpinDefinition definition = SpinDefinition.load(release);
        if (!id.equals(definition.id())) {
            throw new IOException("Current release id does not match installed component: "
                    + definition.id() + " != " + id);
        }
        return new Current(definition, normalizedHome, componentRoot, releaseId, release,
                normalizedHome.resolve("workspace"));
    }

    public static Path defaultHome(String id) {
        return Path.of(System.getProperty("user.home"), "." + id)
                .toAbsolutePath().normalize();
    }

    private static void writeCurrent(Path componentRoot, String releaseId) throws IOException {
        Files.createDirectories(componentRoot);
        Path pointer = componentRoot.resolve(CURRENT_FILE);
        writeAtomic(pointer, releaseId + "\n");
    }

    private static void writeLaunchers(SpinDefinition definition, Path home) throws IOException {
        Path bin = home.resolve("bin");
        rejectSymlinkComponents(bin, "spin launcher directory");
        Files.createDirectories(bin);
        Path unix = bin.resolve(definition.commandName());
        String script = """
                #!/usr/bin/env sh
                set -eu
                SPIN_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
                SPIN_ID="%s"
                IFS= read -r SPIN_RELEASE < "$SPIN_HOME/components/$SPIN_ID/current"
                EMBEDDED="$SPIN_HOME/components/$SPIN_ID/releases/$SPIN_RELEASE/runtime"

                if [ -n "${KOMPILE_AGENT:-}" ] && [ -x "$KOMPILE_AGENT" ]; then
                  exec "$KOMPILE_AGENT" spin launch --home "$SPIN_HOME" "$@"
                fi
                if [ -x "$EMBEDDED/bin/kompile-agent" ]; then
                  exec "$EMBEDDED/bin/kompile-agent" spin launch --home "$SPIN_HOME" "$@"
                fi
                if command -v kompile-agent >/dev/null 2>&1; then
                  exec kompile-agent spin launch --home "$SPIN_HOME" "$@"
                fi
                KOMPILE_ROOT="${KOMPILE_INSTALL_DIR:-$HOME/.kompile}"
                if [ -x "$KOMPILE_ROOT/bin/kompile-agent" ]; then
                  exec "$KOMPILE_ROOT/bin/kompile-agent" spin launch --home "$SPIN_HOME" "$@"
                fi

                AGENT_JAR=""
                [ -f "$EMBEDDED/lib/kompile-agent.jar" ] && AGENT_JAR="$EMBEDDED/lib/kompile-agent.jar"
                [ -z "$AGENT_JAR" ] && [ -f "$KOMPILE_ROOT/lib/kompile-agent.jar" ] && AGENT_JAR="$KOMPILE_ROOT/lib/kompile-agent.jar"
                if [ -n "$AGENT_JAR" ]; then
                  JAVA_BIN="${KOMPILE_JAVA:-}"
                  [ -z "$JAVA_BIN" ] && [ -x "$EMBEDDED/runtime/bin/java" ] && JAVA_BIN="$EMBEDDED/runtime/bin/java"
                  [ -z "$JAVA_BIN" ] && [ -x "$KOMPILE_ROOT/runtime/bin/java" ] && JAVA_BIN="$KOMPILE_ROOT/runtime/bin/java"
                  [ -z "$JAVA_BIN" ] && JAVA_BIN="java"
                  exec "$JAVA_BIN" -jar "$AGENT_JAR" spin launch --home "$SPIN_HOME" "$@"
                fi
                echo "error: kompile-agent is required to launch $SPIN_ID" >&2
                exit 1
                """.formatted(definition.id());
        writeAtomic(unix, script);
        if (!isWindows() && !unix.toFile().setExecutable(true, false)
                && !Files.isExecutable(unix)) {
            throw new IOException("Could not make spin launcher executable: " + unix);
        }

        Path windows = bin.resolve(definition.commandName() + ".cmd");
        String cmd = """
                @echo off
                setlocal
                set "SPIN_HOME=%%~dp0.."
                set "SPIN_ID=%s"
                set /p SPIN_RELEASE=<"%%SPIN_HOME%%\\components\\%%SPIN_ID%%\\current"
                set "EMBEDDED=%%SPIN_HOME%%\\components\\%%SPIN_ID%%\\releases\\%%SPIN_RELEASE%%\\runtime"
                if not "%%KOMPILE_AGENT%%"=="" goto explicit
                if exist "%%EMBEDDED%%\\bin\\kompile-agent.exe" (
                  "%%EMBEDDED%%\\bin\\kompile-agent.exe" spin launch --home "%%SPIN_HOME%%" %%*
                  exit /b %%ERRORLEVEL%%
                )
                where kompile-agent.exe >nul 2>nul && (
                  kompile-agent.exe spin launch --home "%%SPIN_HOME%%" %%*
                  exit /b %%ERRORLEVEL%%
                )
                set "KROOT=%%KOMPILE_INSTALL_DIR%%"
                if "%%KROOT%%"=="" set "KROOT=%%USERPROFILE%%\\.kompile"
                if exist "%%KROOT%%\\bin\\kompile-agent.exe" (
                  "%%KROOT%%\\bin\\kompile-agent.exe" spin launch --home "%%SPIN_HOME%%" %%*
                  exit /b %%ERRORLEVEL%%
                )
                set "AGENT_JAR="
                if exist "%%EMBEDDED%%\\lib\\kompile-agent.jar" set "AGENT_JAR=%%EMBEDDED%%\\lib\\kompile-agent.jar"
                if "%%AGENT_JAR%%"=="" if exist "%%KROOT%%\\lib\\kompile-agent.jar" set "AGENT_JAR=%%KROOT%%\\lib\\kompile-agent.jar"
                if not "%%AGENT_JAR%%"=="" goto jar
                echo error: kompile-agent is required to launch %%SPIN_ID%% 1>&2
                exit /b 1
                :jar
                set "JAVA_BIN=%%KOMPILE_JAVA%%"
                if "%%JAVA_BIN%%"=="" if exist "%%EMBEDDED%%\\runtime\\bin\\java.exe" set "JAVA_BIN=%%EMBEDDED%%\\runtime\\bin\\java.exe"
                if "%%JAVA_BIN%%"=="" if exist "%%KROOT%%\\runtime\\bin\\java.exe" set "JAVA_BIN=%%KROOT%%\\runtime\\bin\\java.exe"
                if "%%JAVA_BIN%%"=="" set "JAVA_BIN=java"
                "%%JAVA_BIN%%" -jar "%%AGENT_JAR%%" spin launch --home "%%SPIN_HOME%%" %%*
                exit /b %%ERRORLEVEL%%
                :explicit
                "%%KOMPILE_AGENT%%" spin launch --home "%%SPIN_HOME%%" %%*
                exit /b %%ERRORLEVEL%%
                """.formatted(definition.id());
        writeAtomic(windows, cmd);
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        rejectSymlinkComponents(target, "managed spin install file");
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling("." + target.getFileName()
                + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void rejectSymlinkComponents(Path path, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException(label + " contains a symbolic link: " + current);
            }
        }
    }

    private static void requireWithin(Path root, Path target) throws IOException {
        if (!target.startsWith(root)) throw new IOException("Install path escapes spin root: " + target);
    }

    public record Installed(SpinDefinition definition, Path home, Path componentRoot,
                            String releaseId, Path release, Path workspace,
                            String contentSha256, String publisherKeySha256) { }

    public record Current(SpinDefinition definition, Path home, Path componentRoot,
                          String releaseId, Path release, Path workspace) { }
}
