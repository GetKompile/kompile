/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Non-destructive upgrader for persisted {@code .kgraph} archives.
 *
 * <p>Normal reads remain backward-compatible and never rewrite their source. This class is the
 * explicit migration seam: source-to-target migration is the default, while in-place migration
 * first retains a versioned backup and relies on {@link UnifiedGraph#save(Path)} for atomic
 * publication.</p>
 */
public final class GraphArchiveMigrator {

    public static final String META_SOURCE_SHA256 = "migration.sourceSha256";
    public static final String META_SOURCE_FORMAT_VERSION = "migration.sourceFormatVersion";

    private GraphArchiveMigrator() { }

    public enum Status {
        MIGRATED,
        SKIPPED_CURRENT,
        SKIPPED_MATCHING_TARGET
    }

    public record MigrationResult(
            Path source, Path target, Path backup, int sourceVersion, Status status) { }

    public static MigrationResult migrate(Path source, Path target) throws IOException {
        Path normalizedSource = requireArchive(source);
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedTarget)) {
            return migrateInPlace(normalizedSource);
        }

        int sourceVersion = detectVersion(normalizedSource);
        String sourceHash = sha256(normalizedSource);
        if (Files.exists(normalizedTarget)) {
            UnifiedGraph existing = UnifiedGraph.load(normalizedTarget);
            if (sourceHash.equals(existing.meta().get(META_SOURCE_SHA256))) {
                return new MigrationResult(normalizedSource, normalizedTarget, null, sourceVersion,
                        Status.SKIPPED_MATCHING_TARGET);
            }
        }

        UnifiedGraph graph = UnifiedGraph.load(normalizedSource);
        graph.meta(META_SOURCE_SHA256, sourceHash);
        graph.meta(META_SOURCE_FORMAT_VERSION, sourceVersion);
        graph.save(normalizedTarget);
        return new MigrationResult(normalizedSource, normalizedTarget, null, sourceVersion, Status.MIGRATED);
    }

    public static MigrationResult migrateInPlace(Path source) throws IOException {
        Path normalizedSource = requireArchive(source);
        int sourceVersion = detectVersion(normalizedSource);
        if (sourceVersion == UnifiedGraphFormat.CURRENT_VERSION) {
            return new MigrationResult(normalizedSource, normalizedSource, null, sourceVersion,
                    Status.SKIPPED_CURRENT);
        }

        UnifiedGraph graph = UnifiedGraph.load(normalizedSource);
        String sourceHash = sha256(normalizedSource);
        graph.meta(META_SOURCE_SHA256, sourceHash);
        graph.meta(META_SOURCE_FORMAT_VERSION, sourceVersion);

        Path backup = normalizedSource.resolveSibling(
                normalizedSource.getFileName() + ".v" + sourceVersion + ".bak");
        if (!Files.exists(backup)) {
            Files.copy(normalizedSource, backup, StandardCopyOption.COPY_ATTRIBUTES);
        } else if (!sourceHash.equals(sha256(backup))) {
            throw new IOException("Migration backup already exists with different content: " + backup);
        }

        graph.save(normalizedSource);
        return new MigrationResult(normalizedSource, normalizedSource, backup, sourceVersion, Status.MIGRATED);
    }

    public static List<MigrationResult> migrateDirectory(Path sourceDirectory, Path targetDirectory)
            throws IOException {
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path target = targetDirectory.toAbsolutePath().normalize();
        Files.createDirectories(target);
        List<MigrationResult> results = new ArrayList<>();
        try (var files = Files.list(source)) {
            for (Path archive : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(UnifiedGraphFormat.EXTENSION))
                    .sorted()
                    .toList()) {
                results.add(migrate(archive, target.resolve(archive.getFileName())));
            }
        }
        return List.copyOf(results);
    }

    public static int detectVersion(Path archive) throws IOException {
        Path normalized = requireArchive(archive);
        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            var entry = zip.getEntry(UnifiedGraphFormat.ENTRY_MANIFEST);
            if (entry == null) {
                throw new IOException("Not a unified-graph file: missing "
                        + UnifiedGraphFormat.ENTRY_MANIFEST);
            }
            Map<String, Object> manifest;
            try (var in = zip.getInputStream(entry)) {
                manifest = MiniJson.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            Object raw = manifest.get("formatVersion");
            if (!(raw instanceof Number number) || number.intValue() != number.doubleValue()) {
                throw new IOException("Invalid unified-graph formatVersion");
            }
            return number.intValue();
        }
    }

    private static Path requireArchive(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Graph archive path is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Graph archive does not exist: " + normalized);
        }
        return normalized;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && "--in-place".equals(args[0])) {
            System.out.println(migrateInPlace(Path.of(args[1])));
            return;
        }
        if (args.length == 2) {
            Path source = Path.of(args[0]);
            Path target = Path.of(args[1]);
            if (Files.isDirectory(source)) {
                for (MigrationResult result : migrateDirectory(source, target)) {
                    System.out.println(result);
                }
            } else {
                System.out.println(migrate(source, target));
            }
            return;
        }
        throw new IllegalArgumentException(
                "Usage: GraphArchiveMigrator <source> <target> | --in-place <archive>");
    }
}
