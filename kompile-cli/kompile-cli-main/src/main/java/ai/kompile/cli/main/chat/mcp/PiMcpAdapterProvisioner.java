/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Provisions the Pi MCP adapter extension from the CLI classpath.
 *
 * <p>The release build may replace the classpath resources with the complete
 * pi-mcp-adapter distribution. Keeping extraction here makes the Java launch
 * paths independent of whether the CLI is running from a jar or a native image.</p>
 */
public final class PiMcpAdapterProvisioner {
    public static final String ADAPTER_VERSION = "2.21.0";
    private static final String RESOURCE_ROOT = "pi-mcp-adapter/";
    private static final List<String> RESOURCE_FILES = List.of("package.json", "index.ts");

    private PiMcpAdapterProvisioner() {
    }

    /**
     * Return a local Pi extension directory, extracting the embedded adapter
     * once per version/platform. An explicit path is useful for development and
     * release smoke tests and is never copied or modified.
     */
    public static Path ensureProvisioned() throws IOException {
        String explicit = System.getProperty("kompile.pi.adapter.path");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("KOMPILE_PI_MCP_ADAPTER");
        }
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new IOException("Configured Pi MCP adapter path does not exist: " + path);
            }
            return path;
        }

        String platform = platformId();
        Path cacheParent = Path.of(System.getProperty("user.home"), ".kompile", "cache",
                "pi-mcp-adapter", ADAPTER_VERSION);
        Path cacheRoot = cacheParent.resolve(platform);
        Files.createDirectories(cacheParent);
        Path lockPath = cacheParent.resolve(platform + ".lock");

        try (var channel = java.nio.channels.FileChannel.open(lockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            Path marker = cacheRoot.resolve(".complete");
            if (Files.isRegularFile(marker) && Files.isRegularFile(cacheRoot.resolve("package.json"))
                    && Files.isRegularFile(cacheRoot.resolve("index.ts"))) {
                return cacheRoot;
            }

            Path temp = cacheParent.resolve(platform + ".tmp-" + ProcessHandle.current().pid());
            deleteTree(temp);
            Files.createDirectories(temp);

            ClassLoader loader = PiMcpAdapterProvisioner.class.getClassLoader();
            String archiveResource = RESOURCE_ROOT + "adapter.zip";
            try (InputStream archive = loader.getResourceAsStream(archiveResource)) {
                if (archive != null) {
                    extractArchive(archive, temp);
                } else {
                    for (String file : RESOURCE_FILES) {
                        String resource = RESOURCE_ROOT + file;
                        try (InputStream input = loader.getResourceAsStream(resource)) {
                            if (input == null) {
                                throw new IOException("Embedded Pi MCP adapter resource is missing: " + resource);
                            }
                            Files.copy(input, temp.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            Files.writeString(temp.resolve(".complete"), ADAPTER_VERSION + "\n", StandardCharsets.UTF_8);
            deleteTree(cacheRoot);
            try {
                Files.move(temp, cacheRoot, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, cacheRoot, StandardCopyOption.REPLACE_EXISTING);
            }
            return cacheRoot;
        }
    }

    private static void extractArchive(InputStream input, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = target.resolve(entry.getName()).normalize();
                if (!output.startsWith(target)) {
                    throw new IOException("Unsafe Pi MCP adapter archive entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static List<String> launchArguments() throws IOException {
        return List.of("-e", ensureProvisioned().toString());
    }

    public static boolean isPiAgent(String agentName) {
        if (agentName == null) {
            return false;
        }
        String normalized = agentName.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("pi") || normalized.equals("pi-cli")
                || normalized.endsWith("/pi") || normalized.endsWith("\\pi");
    }

    private static String platformId() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        String osId = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin")
                ? "macos" : os.contains("linux") ? "linux" : os.replaceAll("[^a-z0-9]+", "-");
        String archId = arch.contains("aarch64") || arch.contains("arm64") ? "arm64"
                : arch.contains("x86_64") || arch.contains("amd64") ? "x86_64"
                : arch.replaceAll("[^a-z0-9]+", "-");
        return osId + "-" + archId;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new TreeDeleteException(e);
                }
            });
        } catch (TreeDeleteException e) {
            throw e.cause;
        }
    }

    private static final class TreeDeleteException extends RuntimeException {
        private final IOException cause;

        private TreeDeleteException(IOException cause) {
            this.cause = cause;
        }
    }
}
