/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filesystem-backed graph snapshots. Each fact sheet owns an isolated directory of portable
 * {@code .kgraph} files; restores always create a pre-restore safety snapshot first.
 */
@Service
public class GraphSnapshotService {

    private static final DateTimeFormatter SNAPSHOT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);
    private static final String SNAPSHOT_DIR = "graph-snapshots";
    private static final String EXTENSION = ".kgraph";

    private final UnifiedGraphBridge bridge;

    @Value("${kompile.data.dir:}")
    private String dataDir;

    @Value("${kompile.graph.snapshots.max-per-sheet:20}")
    private int maxPerSheet;

    public GraphSnapshotService(UnifiedGraphBridge bridge) {
        this.bridge = bridge;
    }

    public record SnapshotMetadata(
            String snapshotId,
            Long factSheetId,
            String label,
            Instant createdAt,
            long sizeBytes) {
    }

    public record RestoreResult(
            Long factSheetId,
            String restoredSnapshotId,
            String preRestoreSnapshotId,
            UnifiedGraphBridge.ImportSummary importSummary) {
    }

    /** Snapshots are enabled only when the project/application data directory is explicit. */
    public boolean isEnabled() {
        return dataDir != null && !dataDir.isBlank();
    }

    public synchronized SnapshotMetadata createSnapshot(Long factSheetId, String label) throws IOException {
        return createSnapshot(factSheetId, label, null);
    }

    private SnapshotMetadata createSnapshot(
            Long factSheetId, String label, String protectedSnapshotId) throws IOException {
        requireEnabled();
        if (factSheetId == null) throw new IllegalArgumentException("factSheetId is required");

        Instant now = Instant.now();
        String normalizedLabel = slug(label);
        String timestamp = SNAPSHOT_TIME.format(now);
        String labelSuffix = normalizedLabel == null ? "" : "_" + normalizedLabel;
        Path directory = sheetDirectory(factSheetId);
        Files.createDirectories(directory);
        Path file = nextAvailableSnapshot(directory, timestamp, labelSuffix);
        bridge.exportToFile(file, factSheetId);

        SnapshotMetadata metadata = metadata(file, factSheetId);
        Set<String> protectedIds = new HashSet<>();
        protectedIds.add(metadata.snapshotId());
        if (protectedSnapshotId != null) protectedIds.add(protectedSnapshotId);
        pruneOldSnapshots(factSheetId, protectedIds);
        return metadata;
    }

    public List<SnapshotMetadata> listSnapshots(Long factSheetId) throws IOException {
        requireEnabled();
        if (factSheetId == null) throw new IllegalArgumentException("factSheetId is required");
        Path directory = sheetDirectory(factSheetId);
        if (!Files.isDirectory(directory)) return List.of();

        List<SnapshotMetadata> snapshots = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION)).toList()) {
                snapshots.add(metadata(file, factSheetId));
            }
        }
        snapshots.sort(Comparator.comparing(SnapshotMetadata::createdAt).reversed()
                .thenComparing(SnapshotMetadata::snapshotId));
        return List.copyOf(snapshots);
    }

    public synchronized RestoreResult restoreSnapshot(Long factSheetId, String snapshotId) throws IOException {
        requireEnabled();
        Path snapshot = resolveSnapshot(factSheetId, snapshotId);
        if (!Files.isRegularFile(snapshot)) {
            throw new IllegalArgumentException("Snapshot not found: " + snapshotId
                    + " for factSheet=" + factSheetId);
        }

        // Protect the restore source from retention pruning while the safety snapshot is written.
        SnapshotMetadata safety = createSnapshot(factSheetId, "pre-restore", snapshotId);
        UnifiedGraphBridge.ImportSummary summary = bridge.importFromFile(snapshot, factSheetId);
        return new RestoreResult(factSheetId, snapshotId, safety.snapshotId(), summary);
    }

    public boolean deleteSnapshot(Long factSheetId, String snapshotId) throws IOException {
        requireEnabled();
        return Files.deleteIfExists(resolveSnapshot(factSheetId, snapshotId));
    }

    private void pruneOldSnapshots(Long factSheetId, Set<String> protectedSnapshotIds) throws IOException {
        int retain = Math.max(1, maxPerSheet);
        List<SnapshotMetadata> snapshots = listSnapshots(factSheetId);
        int excess = snapshots.size() - retain;
        for (int i = snapshots.size() - 1; i >= 0 && excess > 0; i--) {
            String snapshotId = snapshots.get(i).snapshotId();
            if (protectedSnapshotIds.contains(snapshotId)) continue;
            if (Files.deleteIfExists(sheetDirectory(factSheetId).resolve(snapshotId))) {
                excess--;
            }
        }
    }

    private static Path nextAvailableSnapshot(Path directory, String timestamp, String labelSuffix) {
        Path candidate = directory.resolve(timestamp + labelSuffix + EXTENSION);
        int sequence = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(timestamp + "-" + sequence++ + labelSuffix + EXTENSION);
        }
        return candidate;
    }

    private SnapshotMetadata metadata(Path file, Long factSheetId) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String snapshotId = file.getFileName().toString();
        return new SnapshotMetadata(
                snapshotId,
                factSheetId,
                labelFromSnapshotId(snapshotId),
                attributes.lastModifiedTime().toInstant(),
                attributes.size());
    }

    private Path resolveSnapshot(Long factSheetId, String snapshotId) {
        if (factSheetId == null) throw new IllegalArgumentException("factSheetId is required");
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId is required");
        }
        if (!snapshotId.endsWith(EXTENSION)
                || snapshotId.contains("/") || snapshotId.contains("\\") || snapshotId.contains("..")) {
            throw new IllegalArgumentException("Invalid snapshotId: " + snapshotId);
        }
        Path directory = sheetDirectory(factSheetId).toAbsolutePath().normalize();
        Path resolved = directory.resolve(snapshotId).normalize();
        if (!resolved.getParent().equals(directory)) {
            throw new IllegalArgumentException("Invalid snapshotId: " + snapshotId);
        }
        return resolved;
    }

    private Path sheetDirectory(Long factSheetId) {
        return Path.of(dataDir, SNAPSHOT_DIR, "factsheet-" + factSheetId);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "Graph snapshot feature is disabled: kompile.data.dir is not configured");
        }
    }

    private static String slug(String label) {
        if (label == null || label.isBlank()) return null;
        String slug = label.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? null : slug.substring(0, Math.min(64, slug.length()));
    }

    private static String labelFromSnapshotId(String snapshotId) {
        String stem = snapshotId.substring(0, snapshotId.length() - EXTENSION.length());
        int separator = stem.indexOf('_');
        return separator < 0 || separator == stem.length() - 1 ? null : stem.substring(separator + 1);
    }
}
