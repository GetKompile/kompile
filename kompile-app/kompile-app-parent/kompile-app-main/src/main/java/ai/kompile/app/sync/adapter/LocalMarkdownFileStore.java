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
import ai.kompile.app.sync.convert.ObsidianFrontmatterConverter;
import ai.kompile.app.sync.convert.ObsidianFrontmatterConverter.ParsedObsidianNote;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem-backed Markdown note store used by local folders, git repositories,
 * and locally-mounted Obsidian vaults.
 */
@Service
public class LocalMarkdownFileStore {

    private static final Logger log = LoggerFactory.getLogger(LocalMarkdownFileStore.class);
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(".git", ".obsidian", "node_modules", ".trash");

    @Autowired
    private ObsidianFrontmatterConverter frontmatterConverter;

    public List<SyncAdapter.ExternalNoteSnapshot> fetchChangedSince(NoteSyncConnection conn, Instant since) {
        Path root = ensureRoot(conn);
        List<SyncAdapter.ExternalNoteSnapshot> snapshots = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isMarkdownFile)
                    .filter(path -> !isIgnored(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> readSnapshot(root, path, since).ifPresent(snapshots::add));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read markdown folder " + root + ": " + e.getMessage(), e);
        }

        log.info("Local markdown fetch: found {} changed files since {} in {}", snapshots.size(), since, root);
        return snapshots;
    }

    public Optional<SyncAdapter.ExternalNoteSnapshot> fetchById(NoteSyncConnection conn, String externalId) {
        Path root = ensureRoot(conn);
        Path path = resolveExternalPath(root, externalId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return readSnapshot(root, path, Instant.EPOCH);
    }

    public String createExternal(NoteSyncConnection conn, Note note, String markdownContent) {
        Path root = ensureRoot(conn);
        String title = note.getTitle() != null && !note.getTitle().isBlank()
                ? note.getTitle()
                : "Note " + (note.getId() != null ? note.getId() : UUID.randomUUID().toString().substring(0, 8));
        String baseName = frontmatterConverter.sanitizeFileName(title);
        Path path = uniqueMarkdownPath(root, baseName);
        writeNoteFile(path, note, markdownContent);
        String externalId = toExternalId(root, path);
        log.info("Created markdown note '{}' for note '{}'", externalId, note.getTitle());
        return externalId;
    }

    public void updateExternal(NoteSyncConnection conn, String externalId, Note note, String markdownContent) {
        Path root = ensureRoot(conn);
        Path path = resolveExternalPath(root, externalId);
        writeNoteFile(path, note, markdownContent);
        log.info("Updated markdown note '{}' for note '{}'", externalId, note.getTitle());
    }

    public void deleteExternal(NoteSyncConnection conn, String externalId) {
        Path root = ensureRoot(conn);
        Path path = resolveExternalPath(root, externalId);
        try {
            Files.deleteIfExists(path);
            log.info("Deleted markdown note '{}'", externalId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete markdown note " + externalId + ": " + e.getMessage(), e);
        }
    }

    public Path ensureRoot(NoteSyncConnection conn) {
        if (conn.getExternalScope() == null || conn.getExternalScope().isBlank()) {
            throw new IllegalArgumentException("externalScope must be an absolute local folder or vault path");
        }
        Path root = Paths.get(conn.getExternalScope()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create markdown folder " + root + ": " + e.getMessage(), e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Markdown folder is not a directory: " + root);
        }
        return root;
    }

    public Path resolveExternalPath(Path root, String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        Path resolved = root.resolve(externalId).toAbsolutePath().normalize();
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Invalid markdown path outside repository: " + externalId);
        }
        return resolved;
    }

    public String toExternalId(Path root, Path path) {
        return root.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private Optional<SyncAdapter.ExternalNoteSnapshot> readSnapshot(Path root, Path path, Instant since) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            ParsedObsidianNote parsed = frontmatterConverter.fromObsidianFormat(content);
            Instant modified = parsed.updatedAt() != null
                    ? parsed.updatedAt()
                    : Files.getLastModifiedTime(path).toInstant();
            if (modified.isBefore(since) || modified.equals(since)) {
                return Optional.empty();
            }
            String externalId = toExternalId(root, path);
            String title = parsed.title() != null && !parsed.title().isBlank()
                    ? parsed.title()
                    : fileNameToTitle(path.getFileName().toString());
            return Optional.of(new SyncAdapter.ExternalNoteSnapshot(
                    externalId,
                    title,
                    parsed.body(),
                    parsed.tags(),
                    modified
            ));
        } catch (IOException e) {
            log.warn("Failed to read markdown note {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeNoteFile(Path path, Note note, String markdownContent) {
        try {
            Files.createDirectories(path.getParent());
            String content = frontmatterConverter.toObsidianFormat(note, markdownContent);
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write markdown note " + path + ": " + e.getMessage(), e);
        }
    }

    private Path uniqueMarkdownPath(Path root, String baseName) {
        String cleanedBase = baseName == null || baseName.isBlank() ? "Untitled" : baseName;
        Path candidate = root.resolve(cleanedBase + ".md").normalize();
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = root.resolve(cleanedBase + "-" + suffix + ".md").normalize();
            suffix++;
        }
        return candidate;
    }

    private boolean isMarkdownFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".md") || fileName.endsWith(".markdown");
    }

    private boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (IGNORED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String fileNameToTitle(String fileName) {
        String name = fileName;
        if (name.endsWith(".markdown")) {
            name = name.substring(0, name.length() - ".markdown".length());
        } else if (name.endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        return name.replace('_', ' ').replace('-', ' ').trim();
    }
}
