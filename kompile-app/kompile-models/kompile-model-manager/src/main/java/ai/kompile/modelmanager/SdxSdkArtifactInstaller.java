/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.modelmanager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs a verified manifest-selected mobile SDK artifact into a generated project. */
public final class SdxSdkArtifactInstaller {
    private static final long MAX_XCFRAMEWORK_EXPANDED_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int MAX_XCFRAMEWORK_ENTRIES = 100_000;

    private SdxSdkArtifactInstaller() {}

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path destination) throws IOException;
    }

    /**
     * Materializes an Android AAR or Apple XCFramework archive in {@code destinationDirectory}.
     *
     * @return the copied AAR or extracted XCFramework directory
     */
    public static Path install(KompileModelManager.ResolvedSdxSdkArtifact resolved,
                               Path destinationDirectory) throws IOException {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(destinationDirectory, "destinationDirectory");
        SdxSdkManifest.Artifact artifact = resolved.artifact();
        Path source = resolved.path();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resolved SDX SDK artifact is not a regular file: " + source);
        }
        Path destinationRoot = prepareDestination(destinationDirectory);

        return switch (artifact.packageRole()) {
            case "android-aar" -> installAndroidAar(source, artifact, destinationRoot);
            case "apple-xcframework" -> installAppleXcframework(source, artifact, destinationRoot);
            default -> throw new IOException("Unsupported mobile SDX package role: " + artifact.packageRole());
        };
    }

    private static Path prepareDestination(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("SDX SDK installation destination must not be a symbolic link: " + requested);
        }
        Files.createDirectories(absolute);
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SDX SDK installation destination is unsafe: " + requested);
        }
        return absolute.toRealPath();
    }

    private static Path installAndroidAar(Path source, SdxSdkManifest.Artifact artifact,
                                          Path destinationRoot) throws IOException {
        if (!"aar".equals(artifact.packaging()) || !artifact.fileName().endsWith(".aar")) {
            throw new IOException("Android SDX SDK artifact must be an AAR: " + artifact.fileName());
        }
        Path destination = destinationRoot.resolve(artifact.fileName());
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Refusing to replace symbolic-link AAR destination: " + destination);
        }
        Path temporary = Files.createTempFile(destinationRoot, ".sdx-aar-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            verifyCopy(temporary, artifact);
            moveReplacing(temporary, destination);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path installAppleXcframework(Path source, SdxSdkManifest.Artifact artifact,
                                                Path destinationRoot) throws IOException {
        if (!"xcframework.zip".equals(artifact.packaging())
                || !artifact.fileName().endsWith(".xcframework.zip")) {
            throw new IOException("Apple SDX SDK release artifact must be an .xcframework.zip archive: "
                    + artifact.fileName());
        }

        String expectedFrameworkRoot = artifact.fileName().substring(0, artifact.fileName().length() - ".zip".length());
        Path temporaryRoot = Files.createTempDirectory(destinationRoot, ".sdx-xcframework-");
        try {
            extractXcframework(source, temporaryRoot, expectedFrameworkRoot);
            Path extracted = temporaryRoot.resolve(expectedFrameworkRoot);
            Path infoPlist = extracted.resolve("Info.plist");
            if (!Files.isDirectory(extracted, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(infoPlist, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("XCFramework ZIP did not produce a complete framework root: " + extracted);
            }

            Path installed = destinationRoot.resolve(expectedFrameworkRoot);
            if (Files.isSymbolicLink(installed)) {
                throw new IOException("Refusing to replace symbolic-link XCFramework destination: " + installed);
            }
            promoteDirectory(extracted, installed, SdxSdkArtifactInstaller::moveReplacing);
            return installed;
        } finally {
            deleteTree(temporaryRoot);
        }
    }

    private static void extractXcframework(Path source, Path temporaryRoot, String expectedFrameworkRoot)
            throws IOException {
        long expandedBytes = 0;
        int entryCount = 0;
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_XCFRAMEWORK_ENTRIES) {
                    throw new IOException("XCFramework ZIP exceeds the entry-count limit");
                }
                String rawName = entry.getName();
                if (rawName == null || rawName.isBlank() || rawName.startsWith("/")
                        || rawName.indexOf('\\') >= 0 || rawName.indexOf('\0') >= 0
                        || rawName.contains("//")) {
                    throw new IOException("Unsafe XCFramework ZIP entry: " + rawName);
                }
                String stripped = rawName.endsWith("/") ? rawName.substring(0, rawName.length() - 1) : rawName;
                String[] components = stripped.split("/", -1);
                for (String component : components) {
                    if (component.isEmpty() || component.equals(".") || component.equals("..")
                            || component.indexOf(':') >= 0) {
                        throw new IOException("Unsafe XCFramework ZIP entry: " + rawName);
                    }
                }
                Path relative = Path.of(stripped).normalize();
                if (relative.isAbsolute() || relative.getNameCount() == 0
                        || !relative.getName(0).toString().equals(expectedFrameworkRoot)) {
                    throw new IOException("XCFramework ZIP entry is outside its expected framework root: " + rawName);
                }
                String identity = relative.toString();
                if (!entries.add(identity)) {
                    throw new IOException("XCFramework ZIP contains a duplicate entry: " + rawName);
                }
                if (entry.getSize() > MAX_XCFRAMEWORK_EXPANDED_BYTES - expandedBytes) {
                    throw new IOException("XCFramework ZIP exceeds the expanded size limit");
                }

                Path destination = temporaryRoot.resolve(relative).normalize();
                if (!destination.startsWith(temporaryRoot)) {
                    throw new IOException("XCFramework ZIP entry escapes destination: " + rawName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                        expandedBytes = copyBounded(zip, output, expandedBytes);
                    }
                }
                zip.closeEntry();
            }
        }
        if (entryCount == 0) {
            throw new IOException("XCFramework ZIP is empty: " + source);
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long total) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > MAX_XCFRAMEWORK_EXPANDED_BYTES - total) {
                throw new IOException("XCFramework ZIP exceeds the expanded size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static void verifyCopy(Path path, SdxSdkManifest.Artifact artifact) throws IOException {
        if (Files.size(path) != artifact.size()) {
            throw new IOException("Copied SDK artifact size does not match the manifest: " + artifact.fileName());
        }
        if (!sha256(path).equalsIgnoreCase(artifact.sha256())) {
            throw new IOException("Copied SDK artifact checksum does not match the manifest: " + artifact.fileName());
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void promoteDirectory(Path staged, Path installed, MoveOperation mover) throws IOException {
        Path backup = installed.resolveSibling("." + installed.getFileName() + ".sdx-backup");
        if (Files.isSymbolicLink(backup)) {
            throw new IOException("Refusing unsafe XCFramework backup path: " + backup);
        }
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("XCFramework backup path is not a directory: " + backup);
        }
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(backup);
            } else {
                mover.move(backup, installed);
            }
        }
        if (!Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
            mover.move(staged, installed);
            return;
        }
        if (!Files.isDirectory(installed, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("XCFramework destination is not a directory: " + installed);
        }

        mover.move(installed, backup);
        try {
            mover.move(staged, installed);
        } catch (IOException promotionFailure) {
            try {
                if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(installed);
                }
                mover.move(backup, installed);
            } catch (IOException rollbackFailure) {
                promotionFailure.addSuppressed(rollbackFailure);
            }
            throw promotionFailure;
        }
        deleteTree(backup);
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
