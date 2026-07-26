/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.conversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Explicit physical artifact produced by model conversion.
 *
 * <p>The normal staging pipeline accepts exactly one canonical SDZ archive. SDNB shards are
 * an implementation detail of the mainline SDZ serializer and are never exposed as a public
 * staging format.</p>
 */
public record ConversionArtifact(
        Representation representation,
        Path canonicalPath,
        List<Path> physicalFiles) {

    public enum Representation {
        SDZ_ARCHIVE
    }

    public ConversionArtifact {
        representation = Objects.requireNonNull(representation, "representation");
        canonicalPath = normalize(Objects.requireNonNull(canonicalPath, "canonicalPath"));
        physicalFiles = List.copyOf(
                Objects.requireNonNull(physicalFiles, "physicalFiles").stream()
                        .map(ConversionArtifact::normalize)
                        .distinct()
                        .toList());
        if (physicalFiles.isEmpty() || !physicalFiles.contains(canonicalPath)) {
            throw new IllegalArgumentException(
                    "Conversion artifact must include its canonical path in physicalFiles");
        }
        if (representation == Representation.SDZ_ARCHIVE && physicalFiles.size() != 1) {
            throw new IllegalArgumentException("A canonical SDZ artifact must be one physical file");
        }
    }

    public static ConversionArtifact canonicalSdz(Path path) throws IOException {
        Path canonical = normalize(Objects.requireNonNull(path, "path"));
        requireCanonicalSdz(canonical);
        return new ConversionArtifact(Representation.SDZ_ARCHIVE, canonical, List.of(canonical));
    }

    public Path requireCanonicalSdz() throws IOException {
        if (representation != Representation.SDZ_ARCHIVE) {
            throw new IOException("Conversion did not produce a canonical SDZ archive");
        }
        requireCanonicalSdz(canonicalPath);
        return canonicalPath;
    }

    private static void requireCanonicalSdz(Path path) throws IOException {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sdz")) {
            throw new IOException("Canonical conversion artifact must end with .sdz: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) <= 0L) {
            throw new IOException("Canonical conversion artifact is missing or empty: " + path);
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
