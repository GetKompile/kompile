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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Archives file diffs and original file copies for each agent turn in an enforcer session.
 * When a violation occurs the archive provides the data needed to undo changes.
 *
 * <p>Archive layout under {@code .kompile/enforcer-archive/<sessionId>/}:
 * <pre>
 *   manifest.json          — ordered list of snapshots with timestamps and diffs
 *   snapshots/
 *     turn-001/
 *       originals/...      — copies of files BEFORE the turn modified them
 *       diff.patch         — unified diff of changes made during the turn
 *       metadata.json      — turn number, timestamp, files touched, violation flag
 * </pre>
 */
public class EnforcerDiffArchive {

    private final String sessionId;
    private final Path archiveRoot;
    private final Path workingDir;
    private final ObjectMapper objectMapper;
    private int turnCounter;

    public EnforcerDiffArchive(String sessionId, Path workingDir, ObjectMapper objectMapper) throws IOException {
        this.sessionId = sessionId;
        this.workingDir = workingDir.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.archiveRoot = workingDir.resolve(".kompile").resolve("enforcer-archive").resolve(sessionId);
        Files.createDirectories(archiveRoot.resolve("snapshots"));
        this.turnCounter = countExistingSnapshots();
    }

    /**
     * Take a pre-turn snapshot: run git diff to capture the baseline, then
     * return a handle that can be completed after the turn with the final diff.
     */
    public TurnSnapshot beginTurn() throws IOException {
        turnCounter++;
        String turnId = String.format("turn-%03d", turnCounter);
        Path turnDir = archiveRoot.resolve("snapshots").resolve(turnId);
        Files.createDirectories(turnDir.resolve("originals"));

        // Capture current git status for files that might change
        String baselineDiff = captureGitDiff();
        return new TurnSnapshot(turnId, turnDir, Instant.now(), baselineDiff);
    }

    /**
     * Complete a turn snapshot: capture the diff produced by the agent, copy
     * originals for all modified files, and write metadata.
     */
    public void completeTurn(TurnSnapshot snapshot, boolean violated) throws IOException {
        String afterDiff = captureGitDiff();

        // Compute the incremental diff (what happened during this turn)
        String turnDiff = afterDiff;
        Files.writeString(snapshot.turnDir.resolve("diff.patch"), turnDiff, StandardCharsets.UTF_8);

        // Parse changed files from the diff and copy originals
        List<String> changedFiles = parseChangedFilesFromGit();
        copyOriginals(snapshot, changedFiles);

        // Write metadata
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("turnId", snapshot.turnId);
        meta.put("turnNumber", turnCounter);
        meta.put("timestamp", snapshot.startTime.toString());
        meta.put("violated", violated);
        meta.put("sessionId", sessionId);
        ArrayNode filesNode = meta.putArray("changedFiles");
        for (String f : changedFiles) {
            filesNode.add(f);
        }
        Files.writeString(snapshot.turnDir.resolve("metadata.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta),
                StandardCharsets.UTF_8);

        // Update manifest
        updateManifest(snapshot, changedFiles, violated);
    }

    /**
     * Rollback changes from a specific turn by restoring the archived originals.
     */
    public RollbackResult rollback(String turnId) throws IOException {
        Path turnDir = archiveRoot.resolve("snapshots").resolve(turnId);
        if (!Files.isDirectory(turnDir)) {
            return new RollbackResult(false, List.of(), "Turn not found: " + turnId);
        }

        Path originalsDir = turnDir.resolve("originals");
        if (!Files.isDirectory(originalsDir)) {
            return new RollbackResult(false, List.of(), "No originals archived for turn: " + turnId);
        }

        List<String> restoredFiles = new ArrayList<>();
        Files.walkFileTree(originalsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = originalsDir.relativize(file);
                Path target = workingDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                restoredFiles.add(relative.toString());
                return FileVisitResult.CONTINUE;
            }
        });

        return new RollbackResult(true, restoredFiles,
                "Restored " + restoredFiles.size() + " files from " + turnId);
    }

    /**
     * Rollback all turns that were flagged as violations.
     */
    public RollbackResult rollbackViolations() throws IOException {
        List<String> allRestored = new ArrayList<>();
        StringBuilder messages = new StringBuilder();

        // Walk manifest in reverse to undo most recent first
        Path manifestPath = archiveRoot.resolve("manifest.json");
        if (!Files.exists(manifestPath)) {
            return new RollbackResult(false, List.of(), "No manifest found");
        }

        var root = objectMapper.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        var snapshots = root.path("snapshots");
        List<String> violatedTurns = new ArrayList<>();
        for (var node : snapshots) {
            if (node.path("violated").asBoolean(false)) {
                violatedTurns.add(node.path("turnId").asText());
            }
        }

        // Reverse order — newest first
        for (int i = violatedTurns.size() - 1; i >= 0; i--) {
            RollbackResult partial = rollback(violatedTurns.get(i));
            allRestored.addAll(partial.restoredFiles);
            messages.append(partial.message).append("\n");
        }

        return new RollbackResult(!violatedTurns.isEmpty(), allRestored, messages.toString().trim());
    }

    /**
     * Get the diff recorded for a specific turn.
     */
    public String getTurnDiff(String turnId) throws IOException {
        Path diffFile = archiveRoot.resolve("snapshots").resolve(turnId).resolve("diff.patch");
        if (!Files.exists(diffFile)) {
            return null;
        }
        return Files.readString(diffFile, StandardCharsets.UTF_8);
    }

    /**
     * List all archived turns with their metadata.
     */
    public List<TurnMetadata> listTurns() throws IOException {
        Path manifestPath = archiveRoot.resolve("manifest.json");
        if (!Files.exists(manifestPath)) {
            return List.of();
        }

        List<TurnMetadata> turns = new ArrayList<>();
        var root = objectMapper.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        for (var node : root.path("snapshots")) {
            turns.add(new TurnMetadata(
                    node.path("turnId").asText(),
                    node.path("turnNumber").asInt(),
                    node.path("timestamp").asText(),
                    node.path("violated").asBoolean(false),
                    parseStringArray(node.path("changedFiles"))
            ));
        }
        return turns;
    }

    /**
     * Purge all archive data for this session.
     */
    public void purge() throws IOException {
        if (Files.isDirectory(archiveRoot)) {
            Files.walkFileTree(archiveRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Purge all enforcer archives across all sessions.
     */
    public static void purgeAll(Path workingDir) throws IOException {
        Path archiveBase = workingDir.toAbsolutePath().normalize()
                .resolve(".kompile").resolve("enforcer-archive");
        if (Files.isDirectory(archiveBase)) {
            Files.walkFileTree(archiveBase, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Purge archives older than the specified number of hours.
     */
    public static int purgeOlderThan(Path workingDir, int hours) throws IOException {
        Path archiveBase = workingDir.toAbsolutePath().normalize()
                .resolve(".kompile").resolve("enforcer-archive");
        if (!Files.isDirectory(archiveBase)) {
            return 0;
        }

        Instant cutoff = Instant.now().minusSeconds((long) hours * 3600);
        ObjectMapper om = new ObjectMapper();
        int purged = 0;

        try (var sessions = Files.list(archiveBase)) {
            for (Path sessionDir : sessions.toList()) {
                if (!Files.isDirectory(sessionDir)) continue;
                Path manifest = sessionDir.resolve("manifest.json");
                if (!Files.exists(manifest)) {
                    deleteTree(sessionDir);
                    purged++;
                    continue;
                }
                try {
                    var root = om.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
                    String created = root.path("createdAt").asText("");
                    if (!created.isEmpty()) {
                        Instant ts = Instant.parse(created);
                        if (ts.isBefore(cutoff)) {
                            deleteTree(sessionDir);
                            purged++;
                        }
                    }
                } catch (Exception e) {
                    // Malformed manifest — purge it
                    deleteTree(sessionDir);
                    purged++;
                }
            }
        }
        return purged;
    }

    public Path getArchiveRoot() {
        return archiveRoot;
    }

    public String getSessionId() {
        return sessionId;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private String captureGitDiff() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-color");
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }

            // Also include untracked files status
            ProcessBuilder pbStatus = new ProcessBuilder("git", "diff", "--cached", "--no-color");
            pbStatus.directory(workingDir.toFile());
            pbStatus.redirectErrorStream(true);
            Process procStatus = pbStatus.start();
            String staged = new String(procStatus.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!procStatus.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                procStatus.destroyForcibly();
            }

            return output + (staged.isEmpty() ? "" : "\n--- STAGED ---\n" + staged);
        } catch (Exception e) {
            return "# Could not capture git diff: " + e.getMessage();
        }
    }

    private List<String> parseChangedFilesFromGit() {
        List<String> files = new ArrayList<>();
        try {
            // Modified tracked files
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only");
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            for (String line : output.split("\n")) {
                if (!line.isBlank()) files.add(line.trim());
            }

            // Staged files
            ProcessBuilder pbStaged = new ProcessBuilder("git", "diff", "--cached", "--name-only");
            pbStaged.directory(workingDir.toFile());
            pbStaged.redirectErrorStream(true);
            Process procStaged = pbStaged.start();
            String staged = new String(procStaged.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!procStaged.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                procStaged.destroyForcibly();
            }
            for (String line : staged.split("\n")) {
                if (!line.isBlank() && !files.contains(line.trim())) {
                    files.add(line.trim());
                }
            }
        } catch (Exception e) {
            // Best effort
        }
        return files;
    }

    private void copyOriginals(TurnSnapshot snapshot, List<String> changedFiles) throws IOException {
        Path originalsDir = snapshot.turnDir.resolve("originals");
        for (String relativePath : changedFiles) {
            Path source = workingDir.resolve(relativePath);
            if (Files.exists(source) && Files.isRegularFile(source)) {
                Path dest = originalsDir.resolve(relativePath);
                Files.createDirectories(dest.getParent());
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void updateManifest(TurnSnapshot snapshot, List<String> changedFiles, boolean violated)
            throws IOException {
        Path manifestPath = archiveRoot.resolve("manifest.json");
        ObjectNode manifest;
        ArrayNode snapshots;

        if (Files.exists(manifestPath)) {
            manifest = (ObjectNode) objectMapper.readTree(
                    Files.readString(manifestPath, StandardCharsets.UTF_8));
            snapshots = (ArrayNode) manifest.path("snapshots");
            if (snapshots.isMissingNode()) {
                snapshots = manifest.putArray("snapshots");
            }
        } else {
            manifest = objectMapper.createObjectNode();
            manifest.put("sessionId", sessionId);
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("workingDir", workingDir.toString());
            snapshots = manifest.putArray("snapshots");
        }

        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("turnId", snapshot.turnId);
        entry.put("turnNumber", turnCounter);
        entry.put("timestamp", snapshot.startTime.toString());
        entry.put("violated", violated);
        ArrayNode filesNode = entry.putArray("changedFiles");
        for (String f : changedFiles) {
            filesNode.add(f);
        }
        snapshots.add(entry);

        Files.writeString(manifestPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    private int countExistingSnapshots() {
        Path snapshotsDir = archiveRoot.resolve("snapshots");
        if (!Files.isDirectory(snapshotsDir)) return 0;
        try (var list = Files.list(snapshotsDir)) {
            return (int) list.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private List<String> parseStringArray(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── Public types ────────────────────────────────────────────────────

    public static class TurnSnapshot {
        final String turnId;
        final Path turnDir;
        final Instant startTime;
        final String baselineDiff;

        TurnSnapshot(String turnId, Path turnDir, Instant startTime, String baselineDiff) {
            this.turnId = turnId;
            this.turnDir = turnDir;
            this.startTime = startTime;
            this.baselineDiff = baselineDiff;
        }

        public String getTurnId() { return turnId; }
        public Path getTurnDir() { return turnDir; }
    }

    public record TurnMetadata(String turnId, int turnNumber, String timestamp,
                               boolean violated, List<String> changedFiles) {}

    public record RollbackResult(boolean success, List<String> restoredFiles, String message) {}
}
