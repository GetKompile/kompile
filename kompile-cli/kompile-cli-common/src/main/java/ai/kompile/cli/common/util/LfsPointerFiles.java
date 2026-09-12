/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Detection for Git LFS / Xet "pointer" stubs left behind when a model
 * repository is cloned without the large-file transfer filters installed.
 *
 * <p>A pointer stub is a small ASCII text file whose content names the real
 * object, e.g. {@code version https://git-lfs.github.com/spec/v1}. Serving or
 * converting such a file fails with a confusing binary-format error, so model
 * acquisition paths validate their artifacts through {@link #requireRealFiles}.</p>
 */
public final class LfsPointerFiles {

    private static final long MAX_POINTER_BYTES = 4096L;

    private LfsPointerFiles() {
    }

    /**
     * Whether this file is an unfetched Git LFS pointer stub: small, textual,
     * and starting with the LFS pointer header.
     */
    public static boolean isPointer(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (Files.size(file) == 0 || Files.size(file) > MAX_POINTER_BYTES) {
                return false;
            }
            String head = Files.readString(file, StandardCharsets.US_ASCII);
            return head.startsWith("version https://git-lfs")
                    || head.startsWith("version https://xet");
        } catch (IOException | java.io.IOError | RuntimeException ignored) {
            // A real binary weight file cannot be read as ASCII and larger
            // reads fail fast; either way it is not a pointer stub.
            return false;
        }
    }

    /**
     * Collect every pointer stub under {@code directory} (depth 4), mirroring
     * the walk limits used by model resolution.
     */
    public static List<Path> findPointers(Path directory) throws IOException {
        List<Path> pointers = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return pointers;
        }
        try (Stream<Path> files = Files.walk(directory, 4)) {
            files.filter(Files::isRegularFile)
                    .filter(LfsPointerFiles::isPointer)
                    .forEach(pointers::add);
        }
        return pointers;
    }

    /**
     * Guard for freshly acquired model directories. Throws an actionable
     * {@link IOException} when required weight files are unfetched pointer
     * stubs instead of real binaries.
     *
     * @param directory cloned model repository root
     * @param required  file names that must be real (non-pointer) files
     */
    public static void requireRealFiles(Path directory, String... required) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            Path file = directory == null ? null : directory.resolve(name);
            if (file == null || !Files.isRegularFile(file) || isPointer(file)) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        throw new IOException("Cloned repository is missing real model files: "
                + String.join(", ", missing)
                + " — the large files were not fetched. Install git-lfs or git-xet "
                + "(`kompile install git-xet`), then run `git lfs pull` inside "
                + directory + ", or delete the clone and re-clone with --xet.");
    }

    /**
     * Non-fatal scan used after clones: returns an actionable description when
     * any pointer stub exists, or {@code null} when the clone looks complete.
     */
    public static String describeProblem(Path directory) throws IOException {
        List<Path> pointers = findPointers(directory);
        if (pointers.isEmpty()) {
            return null;
        }
        StringBuilder description = new StringBuilder(
                "Unfetched Git LFS/Xet pointer stubs detected (large files were not downloaded):");
        int shown = 0;
        for (Path pointer : pointers) {
            if (shown++ >= 10) {
                description.append("\n  … and ").append(pointers.size() - shown).append(" more");
                break;
            }
            description.append("\n  ").append(pointer);
        }
        description.append("\nInstall git-lfs or git-xet (`kompile install git-xet`), "
                + "then run `git lfs pull` inside ").append(directory);
        return description.toString();
    }
}
