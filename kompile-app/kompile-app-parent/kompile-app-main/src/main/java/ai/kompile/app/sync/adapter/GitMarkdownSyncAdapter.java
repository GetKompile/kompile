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

package ai.kompile.app.sync.adapter;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncAuthMode;
import ai.kompile.app.sync.dto.SyncConnectionTestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Sync adapter for Markdown notes stored in a git repository.
 */
@Service
public class GitMarkdownSyncAdapter implements SyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(GitMarkdownSyncAdapter.class);

    @Autowired
    private LocalMarkdownFileStore fileStore;

    @Autowired(required = false)
    private ai.kompile.oauth.service.TokenEncryptionService tokenEncryptionService;

    @Override
    public String adapterId() {
        return "git_repository";
    }

    @Override
    public List<ExternalNoteSnapshot> fetchChangedSince(NoteSyncConnection conn, Instant since) {
        prepareRepository(conn, true);
        return fileStore.fetchChangedSince(conn, since);
    }

    @Override
    public Optional<ExternalNoteSnapshot> fetchById(NoteSyncConnection conn, String externalId) {
        prepareRepository(conn, true);
        return fileStore.fetchById(conn, externalId);
    }

    @Override
    public String createExternal(NoteSyncConnection conn, Note note, String markdownContent) {
        prepareRepository(conn, true);
        String externalId = fileStore.createExternal(conn, note, markdownContent);
        commitAndPush(conn, "Add note: " + safeTitle(note));
        return externalId;
    }

    @Override
    public void updateExternal(NoteSyncConnection conn, String externalId, Note note, String markdownContent) {
        prepareRepository(conn, true);
        fileStore.updateExternal(conn, externalId, note, markdownContent);
        commitAndPush(conn, "Update note: " + safeTitle(note));
    }

    @Override
    public void deleteExternal(NoteSyncConnection conn, String externalId) {
        prepareRepository(conn, true);
        fileStore.deleteExternal(conn, externalId);
        commitAndPush(conn, "Delete note: " + externalId);
    }

    private void prepareRepository(NoteSyncConnection conn, boolean pullRemote) {
        Path root = fileStore.ensureRoot(conn);
        String remoteUrl = trimToNull(conn.getRepositoryUrl());
        String branch = branch(conn);
        Path gitDir = root.resolve(".git");

        if (!Files.exists(gitDir)) {
            if (remoteUrl != null && isDirectoryEmpty(root)) {
                cloneRepository(conn, root, remoteUrl, branch);
                return;
            }
            runGit(root, false, "init");
            runGit(root, true, "checkout", "-B", branch);
        }

        if (remoteUrl != null) {
            CommandResult remote = runGit(root, true, "remote", "get-url", "origin");
            if (remote.exitCode() == 0) {
                runGit(root, false, "remote", "set-url", "origin", remoteUrl);
            } else {
                runGit(root, false, "remote", "add", "origin", remoteUrl);
            }
        }

        runGit(root, true, "checkout", branch);
        runGit(root, true, "checkout", "-B", branch);

        if (pullRemote && remoteUrl != null && remoteSyncEnabled(conn)) {
            runGitWithAuth(conn, root, false, "pull", "--ff-only", "origin", branch);
        }
    }

    private void cloneRepository(NoteSyncConnection conn, Path root, String remoteUrl, String branch) {
        Path parent = root.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Git repository path must have a parent: " + root);
        }
        try {
            Files.deleteIfExists(root);
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare git repository path " + root + ": " + e.getMessage(), e);
        }
        CommandResult clone = runGitWithAuth(conn, parent, true, "clone", "--branch", branch, remoteUrl, root.getFileName().toString());
        if (clone.exitCode() != 0) {
            runGitWithAuth(conn, parent, false, "clone", remoteUrl, root.getFileName().toString());
            runGit(root, true, "checkout", "-B", branch);
        }
    }

    private void commitAndPush(NoteSyncConnection conn, String message) {
        if (Boolean.FALSE.equals(conn.getAutoCommit())) {
            return;
        }

        Path root = fileStore.ensureRoot(conn);
        runGit(root, false, "add", "-A");
        CommandResult status = runGit(root, false, "status", "--porcelain");
        if (status.output().isBlank()) {
            return;
        }

        runGit(root, false,
                "-c", "user.name=Kompile",
                "-c", "user.email=kompile@local",
                "commit", "-m", message);

        String remoteUrl = trimToNull(conn.getRepositoryUrl());
        if (remoteUrl != null && remoteSyncEnabled(conn)) {
            runGitWithAuth(conn, root, false, "push", "-u", "origin", branch(conn));
        }
    }

    @Override
    public SyncConnectionTestResponse testConnection(NoteSyncConnection conn) {
        try {
            Path root = fileStore.ensureRoot(conn);
            runGit(root, false, "--version");
            if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
                return SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(),
                        "Git working tree path is not readable and writable: " + root);
            }
            String remoteUrl = trimToNull(conn.getRepositoryUrl());
            if (remoteUrl == null || !remoteSyncEnabled(conn)) {
                return SyncConnectionTestResponse.success(conn.getId(), conn.getAuthMode(),
                        "Local git working tree is available. Remote pull/push is disabled.");
            }
            runGitWithAuth(conn, root, false, "ls-remote", "--heads", remoteUrl);
            return SyncConnectionTestResponse.success(conn.getId(), conn.getAuthMode(),
                    "Git remote is reachable with the configured auth mode.");
        } catch (Exception e) {
            return SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(),
                    "Git auth test failed: " + e.getMessage());
        }
    }

    private CommandResult runGit(Path root, boolean allowFailure, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return runProcess(root, allowFailure, null, command.toArray(String[]::new));
    }

    private CommandResult runGitWithAuth(NoteSyncConnection conn, Path root, boolean allowFailure, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return runProcess(root, allowFailure, gitAuthContext(conn), command.toArray(String[]::new));
    }

    private CommandResult runProcess(Path directory, boolean allowFailure, GitAuthContext authContext, String... command) {
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
            String output;
            int exitCode;
            try {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Git command timed out after 300s: " + String.join(" ", command));
                }
                exitCode = process.exitValue();
            } finally {
                process.destroyForcibly();
            }
            if (exitCode != 0 && !allowFailure) {
                throw new IllegalStateException("Command failed (" + String.join(" ", command) + "): " + output.trim());
            }
            if (exitCode != 0) {
                log.debug("Allowed git command failure ({}): {}", String.join(" ", command), output.trim());
            }
            return new CommandResult(exitCode, output);
        } catch (IOException e) {
            if (allowFailure) {
                return new CommandResult(127, e.getMessage());
            }
            throw new IllegalStateException("Failed to run command " + String.join(" ", command) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running command " + String.join(" ", command), e);
        } finally {
            if (askPassScript != null) {
                try {
                    Files.deleteIfExists(askPassScript);
                } catch (IOException e) {
                    log.debug("Failed to delete temporary git askpass script {}: {}", askPassScript, e.getMessage());
                }
            }
        }
    }

    private GitAuthContext gitAuthContext(NoteSyncConnection conn) {
        if (conn.getAuthMode() != SyncAuthMode.HTTPS_TOKEN) {
            return null;
        }
        String remoteUrl = trimToNull(conn.getRepositoryUrl());
        if (remoteUrl == null || !(remoteUrl.startsWith("https://") || remoteUrl.startsWith("http://"))) {
            throw new IllegalStateException("HTTPS token auth requires an HTTP(S) git remote URL.");
        }
        String token = decryptGitToken(conn);
        String username = trimToNull(conn.getGitUsername());
        return new GitAuthContext(username != null ? username : "x-access-token", token);
    }

    private Path createAskPassScript() throws IOException {
        Path script = Files.createTempFile("kompile-git-askpass-", ".sh");
        String content = "#!/bin/sh\n" +
                "case \"$1\" in\n" +
                "  *Username*) printf '%s\\n' \"$KOMPILE_GIT_USERNAME\" ;;\n" +
                "  *) printf '%s\\n' \"$KOMPILE_GIT_TOKEN\" ;;\n" +
                "esac\n";
        Files.writeString(script, content, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        script.toFile().setExecutable(true, true);
        return script;
    }

    private String decryptGitToken(NoteSyncConnection conn) {
        String encrypted = conn.getGitTokenEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("No Git HTTPS token configured for this connection.");
        }
        if (tokenEncryptionService != null) {
            try {
                return tokenEncryptionService.decrypt(encrypted);
            } catch (Exception e) {
                log.warn("Failed to decrypt Git token, using stored value as-is: {}", e.getMessage());
                return encrypted;
            }
        }
        return encrypted;
    }

    private boolean isDirectoryEmpty(Path path) {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private String branch(NoteSyncConnection conn) {
        String branch = trimToNull(conn.getGitBranch());
        return branch != null ? branch : "main";
    }

    private boolean remoteSyncEnabled(NoteSyncConnection conn) {
        return !Boolean.FALSE.equals(conn.getRemoteSyncEnabled());
    }

    private String safeTitle(Note note) {
        String title = note.getTitle();
        if (title == null || title.isBlank()) {
            return note.getId() != null ? "Note " + note.getId() : "Untitled";
        }
        return title.length() > 80 ? title.substring(0, 80) : title;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record GitAuthContext(String username, String token) {}

    private record CommandResult(int exitCode, String output) {}
}
