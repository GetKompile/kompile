/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.modelmanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Direct, atomic, checksum-verifying component acquisition for managed model manifests. */
public final class ManagedModelArtifactDownloader {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_REDIRECTS = 8;

    public record Acquisition(
            ManagedModelArtifactCatalog.Definition definition,
            Path directory,
            Map<String, Path> components,
            boolean downloaded) {
        public Acquisition {
            components = Map.copyOf(components);
        }

        public Path primaryModel() {
            return components.get(definition.primaryComponentKey());
        }

        public Path tokenizer() {
            return components.get(definition.tokenizerComponentKey());
        }
    }

    public Acquisition acquire(
            ManagedModelArtifactCatalog.Definition definition,
            Path targetDirectory,
            boolean force,
            boolean dryRun) throws IOException {
        Path directory = targetDirectory.toAbsolutePath().normalize();
        Map<String, Path> components = new LinkedHashMap<>();
        boolean downloaded = false;
        if (!dryRun) {
            Files.createDirectories(directory);
        }

        for (ManagedModelArtifactCatalog.Component component : definition.components()) {
            Path target = directory.resolve(component.localFileName()).normalize();
            if (!target.startsWith(directory)) {
                throw new IOException("Managed model component escapes target directory: "
                        + component.localFileName());
            }
            components.put(component.key(), target);
            if (dryRun) {
                continue;
            }
            if (!force && validCached(target, component.sha256(), component.expectedBytes())) {
                continue;
            }
            URL url = componentUrl(definition, component);
            download(url, target, component.sha256(), component.expectedBytes(), 0);
            downloaded = true;
        }
        return new Acquisition(definition, directory, components, downloaded);
    }

    public static URL componentUrl(
            ManagedModelArtifactCatalog.Definition definition,
            ManagedModelArtifactCatalog.Component component) throws IOException {
        if (!"HUGGINGFACE".equalsIgnoreCase(definition.source())) {
            throw new IOException("Unsupported managed model source: " + definition.source());
        }
        return URI.create("https://huggingface.co/" + definition.repository()
                + "/resolve/" + definition.revision() + "/" + component.remotePath()).toURL();
    }

    private void download(
            URL url, Path target, String expectedSha256, long expectedBytes, int redirects)
            throws IOException {
        if (redirects > MAX_REDIRECTS) {
            throw new IOException("Too many redirects downloading " + url);
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "Kompile-ModelManager/1.0");
        int status = connection.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect without Location downloading " + url);
            }
            download(resolveRedirect(url, location), target, expectedSha256, expectedBytes,
                    redirects + 1);
            return;
        }
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " downloading " + url);
        }

        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(temp))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            }
            verifySize(temp, expectedBytes);
            verifyChecksum(temp, expectedSha256);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temp);
        }
    }

    static URL resolveRedirect(URL current, String location) throws IOException {
        URL resolved = URI.create(current.toString()).resolve(location).toURL();
        if (!"https".equalsIgnoreCase(resolved.getProtocol())) {
            throw new IOException("Refusing non-HTTPS managed model redirect from "
                    + current + " to " + resolved);
        }
        String host = resolved.getHost() == null ? "" : resolved.getHost().toLowerCase();
        if (!(host.equals("huggingface.co") || host.endsWith(".huggingface.co")
                || host.equals("hf.co") || host.endsWith(".hf.co")
                || host.equals("xethub.hf.co") || host.endsWith(".xethub.hf.co"))) {
            throw new IOException("Refusing managed model redirect to untrusted host: " + resolved);
        }
        return resolved;
    }

    private static boolean validCached(
            Path target, String expectedSha256, long expectedBytes) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (expectedBytes > 0 && Files.size(target) != expectedBytes) {
            return false;
        }
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return Files.size(target) > 0;
        }
        return expectedSha256.equalsIgnoreCase(sha256(target));
    }

    private static void verifySize(Path file, long expectedBytes) throws IOException {
        if (expectedBytes > 0 && Files.size(file) != expectedBytes) {
            throw new IOException("Size mismatch for " + file.getFileName()
                    + ": expected " + expectedBytes + " bytes but got " + Files.size(file));
        }
    }

    private static void verifyChecksum(Path file, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            if (Files.size(file) == 0) {
                throw new IOException("Downloaded model component is empty: " + file);
            }
            return;
        }
        String actual = sha256(file);
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 mismatch for " + file.getFileName()
                    + ": expected " + expectedSha256 + " but got " + actual);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
