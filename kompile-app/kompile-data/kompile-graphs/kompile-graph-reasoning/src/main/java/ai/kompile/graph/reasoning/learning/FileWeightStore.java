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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <h3>Thread safety</h3>
 * <p>Not thread-safe. External synchronization is the caller's responsibility.</p>
 */
public class FileWeightStore implements WeightStore {

    private static final Pattern FILE_PATTERN =
            Pattern.compile("^(.+)\\.v(\\d+)\\.json$");

    private final Path baseDir;

    /**
     * In-memory index: programId → sorted list of version numbers (ascending).
     * This is rebuilt from disk at construction and kept up-to-date on each save.
     */
    private final Map<String, List<Integer>> versionIndex = new LinkedHashMap<>();

    /**
     * Construct a {@code FileWeightStore} rooted at {@code baseDir}.
     * The directory is created if it does not exist. Existing {@code *.v<n>.json} files
     * are scanned to reconstruct the version index.
     *
     * @param baseDir root directory for weight files; must not be null
     * @throws UncheckedIOException if the directory cannot be created or scanned
     */
    public FileWeightStore(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
            rebuildIndex();
        } catch (IOException e) {
            throw new UncheckedIOException("FileWeightStore: cannot initialize base dir " + baseDir, e);
        }
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
