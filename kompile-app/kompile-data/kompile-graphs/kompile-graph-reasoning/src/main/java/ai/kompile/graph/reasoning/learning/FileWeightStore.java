/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Durable, file-backed implementation of {@link WeightStore}.
 *
 * <p>Each (programId, version) is persisted as a JSON file under the base directory
 * supplied at construction, with the filename pattern
 * {@code <sanitizedProgramId>.v<version>.json}. The programId is sanitized by replacing
 * characters that are not alphanumeric, dash, or dot with underscores.</p>
 *
 * <p>On construction the directory is scanned and the in-memory version index is rebuilt
 * from existing files, so the store survives restarts over the same directory.</p>
 *
 * <p>JSON serialization is hand-rolled (no external library dependency). Reading delegates
 * to {@link PslWeightLearningService#parseWeights(String)} which is already battle-tested;
 * writing uses a local map-to-JSON writer that mirrors
 * {@link PslWeightLearningService#weightsToJson(List)} in format.</p>
 *
 * <h3>Backups</h3>
 * <p>Backups are stored under {@code <baseDir>/backups/} as
 * {@code <sanitizedProgramId>.bak.<timestamp>.json}.  A configurable
 * {@code maxBackups} limit (default 10) controls retention; the oldest backups are pruned
 * when the limit is exceeded.  Backup metadata is derived by scanning the backup directory
 * at call time — no separate index is maintained.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe. External synchronization is the caller's responsibility.</p>
 */
public class FileWeightStore implements WeightStore {

    /** Pattern for learning-version files: {@code <sanitized>.v<n>.json}. */
    private static final Pattern FILE_PATTERN =
            Pattern.compile("^(.+)\\.v(\\d+)\\.json$");

    /** Pattern for backup files: {@code <sanitized>.bak.<timestamp>.json}. */
    private static final Pattern BACKUP_PATTERN =
            Pattern.compile("^(.+)\\.bak\\.(.+)\\.json$");

    private static final DateTimeFormatter BACKUP_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** Default maximum number of backups retained per programId. */
    private static final int DEFAULT_MAX_BACKUPS = 10;

    private final Path baseDir;
    private final Path backupDir;
    private final int maxBackups;

    /**
     * In-memory index: programId → sorted list of version numbers (ascending).
     * This is rebuilt from disk at construction and kept up-to-date on each save.
     */
    private final Map<String, List<Integer>> versionIndex = new LinkedHashMap<>();

    /**
     * Construct a {@code FileWeightStore} rooted at {@code baseDir} with the default
     * backup retention limit ({@value DEFAULT_MAX_BACKUPS}).
     *
     * @param baseDir root directory for weight files; must not be null
     * @throws UncheckedIOException if the directory cannot be created or scanned
     */
    public FileWeightStore(Path baseDir) {
        this(baseDir, DEFAULT_MAX_BACKUPS);
    }

    /**
     * Construct a {@code FileWeightStore} rooted at {@code baseDir}.
     * The directory is created if it does not exist. Existing {@code *.v<n>.json} files
     * are scanned to reconstruct the version index.
     *
     * @param baseDir    root directory for weight files; must not be null
     * @param maxBackups maximum backup snapshots to retain per programId (older ones pruned)
     * @throws UncheckedIOException if the directory cannot be created or scanned
     */
    public FileWeightStore(Path baseDir, int maxBackups) {
        this.baseDir = baseDir;
        this.backupDir = baseDir.resolve("backups");
        this.maxBackups = maxBackups;
        try {
            Files.createDirectories(baseDir);
            Files.createDirectories(backupDir);
            rebuildIndex();
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot initialize base dir " + baseDir, e);
        }
    }

    // ─── Accessors ───────────────────────────────────────────────────────────────

    /**
     * Return the base directory for this store (the root under which versioned weight files
     * are written). Used by callers that need to pass the directory to an out-of-process
     * learning subprocess so it can write weight files directly to the same location.
     */
    public Path baseDir() {
        return baseDir;
    }

    // ─── WeightStore ─────────────────────────────────────────────────────────────

    @Override
    public int save(String programId, Map<String, Double> weights) {
        if (programId == null) throw new IllegalArgumentException("programId must not be null");
        if (weights == null) throw new IllegalArgumentException("weights must not be null");

        List<Integer> vers = versionIndex.computeIfAbsent(programId, k -> new ArrayList<>());
        int nextVersion = vers.isEmpty() ? 1 : vers.get(vers.size() - 1) + 1;

        Path file = fileFor(programId, nextVersion);
        String json = toJson(weights);
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot write " + file, e);
        }

        vers.add(nextVersion);
        return nextVersion;
    }

    @Override
    public Optional<Map<String, Double>> latest(String programId) {
        List<Integer> vers = versionIndex.get(programId);
        if (vers == null || vers.isEmpty()) return Optional.empty();
        return get(programId, vers.get(vers.size() - 1));
    }

    @Override
    public int latestVersion(String programId) {
        List<Integer> vers = versionIndex.get(programId);
        return (vers == null || vers.isEmpty()) ? 0 : vers.get(vers.size() - 1);
    }

    @Override
    public Optional<Map<String, Double>> get(String programId, int version) {
        List<Integer> vers = versionIndex.get(programId);
        if (vers == null || !vers.contains(version)) return Optional.empty();
        Path file = fileFor(programId, version);
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(PslWeightLearningService.parseWeights(json));
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot read " + file, e);
        }
    }

    @Override
    public List<Integer> versions(String programId) {
        List<Integer> vers = versionIndex.get(programId);
        if (vers == null || vers.isEmpty()) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(vers));
    }

    @Override
    public Set<String> programIds() {
        return Collections.unmodifiableSet(versionIndex.keySet());
    }

    // ─── Backup / reset ───────────────────────────────────────────────────────

    /**
     * Snapshot the current (latest) weights for {@code programId} into the backups directory.
     * Backup file name: {@code <sanitized>.bak.<yyyyMMddTHHmmssZ>.json}.
     * After writing, excess backups (beyond {@code maxBackups}) are pruned oldest-first.
     *
     * @return the backupId (the timestamp string), or {@code null} if there are no weights to back up
     */
    @Override
    public String backup(String programId) {
        Optional<Map<String, Double>> latest = latest(programId);
        if (latest.isEmpty()) {
            return null;
        }
        String timestamp = BACKUP_TS_FMT.format(Instant.now());
        Path backupFile = backupDir.resolve(sanitize(programId) + ".bak." + timestamp + ".json");
        try {
            Files.writeString(backupFile, toJson(latest.get()), StandardCharsets.UTF_8);
            pruneBackups(programId);
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot write backup " + backupFile, e);
        }
        return timestamp;
    }

    /**
     * List all backup snapshots for {@code programId}, newest first.
     *
     * @return list of {@link WeightBackupInfo} descriptors; empty if none
     */
    @Override
    public List<WeightBackupInfo> listBackups(String programId) {
        String prefix = sanitize(programId) + ".bak.";
        List<WeightBackupInfo> result = new ArrayList<>();
        if (!Files.isDirectory(backupDir)) {
            return result;
        }
        try (var stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith(prefix)) return;
                Matcher m = BACKUP_PATTERN.matcher(name);
                if (!m.matches()) return;
                String ts = m.group(2);
                int entryCount = 0;
                try {
                    Map<String, Double> w = PslWeightLearningService.parseWeights(
                            Files.readString(p, StandardCharsets.UTF_8));
                    entryCount = w.size();
                } catch (IOException ignored) {
                    // keep entryCount=0 for unreadable backups
                }
                // versionAt=0 — the version at backup time is not encoded in the filename
                result.add(new WeightBackupInfo(ts, programId, ts, 0, entryCount));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot list backups for " + programId, e);
        }
        // Sort newest-first by backupId (timestamp string — ISO sortable)
        result.sort(Comparator.comparing(WeightBackupInfo::backupId).reversed());
        return result;
    }

    /**
     * Restore weights from the backup identified by {@code backupId} (a timestamp string
     * returned by {@link #backup}).  The restored weights are written as a new version via
     * {@link #save}, making them the new "latest".
     *
     * @return {@code true} if the restore succeeded; {@code false} if the backup file was not found
     */
    @Override
    public boolean restoreBackup(String programId, String backupId) {
        Path backupFile = backupDir.resolve(sanitize(programId) + ".bak." + backupId + ".json");
        if (!Files.exists(backupFile)) {
            return false;
        }
        try {
            String json = Files.readString(backupFile, StandardCharsets.UTF_8);
            Map<String, Double> weights = PslWeightLearningService.parseWeights(json);
            save(programId, weights);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot read backup " + backupFile, e);
        }
    }

    /**
     * Back up the current weights, then reset every rule's weight to {@code defaultWeight},
     * saving the reset state as a new version.  If there are no existing weights the store
     * just starts fresh — no backup is taken.
     *
     * <p>The "default weight" is {@link ai.kompile.knowledgegraph.confidence.KbConfig#pslDefaultRuleWeight}
     * (0.8) when called from the service layer.  Here we accept it as a parameter so this
     * infra-free class stays free of KbConfig dependencies.</p>
     *
     * @param programId     the program/ruleset to reset; must not be null
     * @param defaultWeight weight assigned to every rule key after reset (e.g. 0.8)
     * @return the backupId of the auto-backup, or {@code null} if there were no weights to back up
     */
    @Override
    public String reset(String programId, double defaultWeight) {
        Optional<Map<String, Double>> current = latest(programId);
        String backupId = null;
        if (current.isPresent()) {
            backupId = backup(programId);
            // Build a fresh weight map — same rule keys, all reset to defaultWeight
            Map<String, Double> fresh = new LinkedHashMap<>();
            for (String key : current.get().keySet()) {
                fresh.put(key, defaultWeight);
            }
            // Clear existing version index for this programId; delete version files so the
            // next save() starts at v1 of the new session.
            clearVersionFiles(programId);
            save(programId, fresh);
        }
        return backupId;
    }

    // ─── Internals ───────────────────────────────────────────────────────────────

    /**
     * Return the file path for a given programId and version.
     */
    private Path fileFor(String programId, int version) {
        return baseDir.resolve(sanitize(programId) + ".v" + version + ".json");
    }

    /**
     * Sanitize a programId to a safe filename component.
     * Characters that are not alphanumeric, dash ({@code -}), dot ({@code .}), or underscore
     * ({@code _}) are replaced with {@code _}.
     */
    static String sanitize(String programId) {
        return programId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Scan {@link #baseDir} for files matching the {@code <sanitized>.v<n>.json} pattern and
     * rebuild {@link #versionIndex}. Called once at construction.
     */
    private void rebuildIndex() throws IOException {
        versionIndex.clear();
        // Temporary map: sanitized-name → programId is not invertible in general.
        // We read the actual programIds by reverse-matching: for each file that matches
        // the pattern, we store (sanitized → sorted versions). But we cannot recover the
        // ORIGINAL programId from the sanitized filename — so we key the index by the
        // sanitized form. To map back to the caller's programId, save() and get() always
        // go through sanitize(), so the index is consistently keyed by the ORIGINAL
        // programId passed to save(). On a fresh construction from existing files, the only
        // available key is the sanitized filename. We therefore key the index by the
        // sanitized filename here (and accept that programIds() will return the sanitized
        // forms for entries loaded from disk without a prior save() in this JVM session).
        Map<String, TreeMap<Integer, Void>> found = new LinkedHashMap<>();
        try (var stream = Files.list(baseDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                Matcher m = FILE_PATTERN.matcher(p.getFileName().toString());
                if (m.matches()) {
                    String sanitizedId = m.group(1);
                    int version = Integer.parseInt(m.group(2));
                    found.computeIfAbsent(sanitizedId, k -> new TreeMap<>()).put(version, null);
                }
            });
        }
        for (Map.Entry<String, TreeMap<Integer, Void>> entry : found.entrySet()) {
            versionIndex.put(entry.getKey(), new ArrayList<>(entry.getValue().keySet()));
        }
    }

    /**
     * Delete all version files for the given programId and clear the version index for it.
     * Used by {@link #reset} to start a new session from v1.
     */
    private void clearVersionFiles(String programId) {
        List<Integer> vers = versionIndex.remove(programId);
        if (vers == null) return;
        for (int v : vers) {
            Path f = fileFor(programId, v);
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) {
                // best-effort; leave orphan files in place
            }
        }
    }

    /**
     * Prune backups for {@code programId} so that at most {@code maxBackups} snapshots remain,
     * removing the oldest (lexicographically earliest timestamp-named) ones first.
     */
    private void pruneBackups(String programId) throws IOException {
        if (maxBackups <= 0) return;
        String prefix = sanitize(programId) + ".bak.";
        List<Path> backups;
        try (var stream = Files.list(backupDir)) {
            backups = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        while (backups.size() > maxBackups) {
            Files.deleteIfExists(backups.remove(0));
        }
    }

    /**
     * Serialize a {@code Map<String, Double>} as a JSON object. Numbers are formatted with
     * {@link Locale#ROOT} to avoid locale-specific decimal separators. Keys are JSON-escaped.
     *
     * <p>Format mirrors {@link PslWeightLearningService#weightsToJson(List)} so that
     * {@link PslWeightLearningService#parseWeights(String)} can read files written here.</p>
     */
    static String toJson(Map<String, Double> weights) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(entry.getKey())).append("\":");
            sb.append(String.format(Locale.ROOT, "%.17g", entry.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
