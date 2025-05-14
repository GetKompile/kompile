package ai.kompile.pipelines.util;

import ai.kompile.pipelines.framework.api.context.Context;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Utility methods for handling URIs, particularly for resolving resource paths
 * like model files.
 */

public class URIUtils {

    private URIUtils() {}

    /**
     * Resolves a URI string (supporting file:/, classpath:/, or plain paths)
     * to a local File object. Classpath resources are copied to a temporary file.
     *
     * @param uriString The URI string of the resource.
     * @param context The pipeline context, used to potentially get a shared temp directory.
     * @return A File object pointing to the local resource.
     * @throws IOException If the resource cannot be found or accessed.
     * @throws URISyntaxException If the URI string is invalid.
     */
    public static File resolveToTempOrLocalFile(String uriString, Context context) throws IOException, URISyntaxException {
        Objects.requireNonNull(uriString, "URI string cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        URI uri;
        try {
            uri = new URI(uriString);
        } catch (URISyntaxException e) {
            // Assume it might be a plain file path if URI syntax fails
            File plainFile = new File(uriString);
            if (!plainFile.exists()) {
                throw new FileNotFoundException("File not found at path: " + plainFile.getAbsolutePath());
            }
            return plainFile;
        }


        String scheme = uri.getScheme();
        File resolvedFile;

        if ("file".equalsIgnoreCase(scheme) || scheme == null) {
            // Handle absolute paths and potentially relative paths (relative to working dir)
            if (uri.isAbsolute()) {
                resolvedFile = new File(uri.getPath()); // Handles file:// paths correctly
            } else {
                // If no scheme and not absolute, treat as potentially relative path string
                resolvedFile = new File(uriString);
            }
            if (!resolvedFile.exists()) {
                throw new FileNotFoundException("File not found at resolved path: " + resolvedFile.getAbsolutePath() + " (from URI: " + uriString + ")");
            }
        } else if ("classpath".equalsIgnoreCase(scheme)) {
            String resourcePath = uri.getSchemeSpecificPart();
            // Clean up potential leading slashes from classpath URI definition
            if (resourcePath.startsWith("//")) resourcePath = resourcePath.substring(2);
            if (resourcePath.startsWith("/")) resourcePath = resourcePath.substring(1);

            try (InputStream resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    throw new FileNotFoundException("Resource not found in classpath at: " + resourcePath +
                            " (derived from URI: " + uriString + ")");
                }

                // Use context to find a temp directory if configured, otherwise create a default one
                Path tempDir = findOrCreateTempDirectory(context);

                // Extract filename to use in temp file name
                String fileName = new File(resourcePath).getName();
                if (fileName.isEmpty()) fileName = "resource";
                String suffix = "";
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0) {
                    suffix = fileName.substring(dotIndex);
                    fileName = fileName.substring(0, dotIndex);
                }

                Path tempFile = Files.createTempFile(tempDir, fileName + "-", suffix);
                Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                resolvedFile = tempFile.toFile();
                // IMPORTANT: Mark for deletion on exit ONLY if you don't need it later (e.g., model saved elsewhere after training)
                // resolvedFile.deleteOnExit(); // Use with caution
            }
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: '" + scheme + "' in URI: " + uriString +
                    ". Supported schemes are 'file' and 'classpath'.");
        }

        if (!resolvedFile.isFile()) {
            throw new FileNotFoundException("Resolved path is not a regular file: " + resolvedFile.getAbsolutePath() + " (from URI: " + uriString + ")");
        }
        return resolvedFile;
    }


    /**
     * Finds a temporary directory from the context or creates a default one.
     */
    private static Path findOrCreateTempDirectory(Context context) throws IOException {
        Optional<Path> contextTempDir = context.get("kompile.tempDir", Path.class);
        Path tempDir;
        if (contextTempDir.isPresent()) {
            tempDir = contextTempDir.get();
        } else {
            // Create a default directory (consider making this configurable)
            tempDir = Files.createTempDirectory("kompile-pipelines-temp-");
            // Maybe add the created path back to context for sharing?
            // context.put("kompile.tempDir", tempDir);
        }
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        return tempDir;
    }

    /**
     * Deletes a file quietly, logging warnings on failure. Intended for cleaning up temporary files.
     * Also attempts to delete the parent directory if it looks like one of our temporary directories and becomes empty.
     * @param file The file to delete (can be null).
     */
    public static void deleteTempFileQuietly(File file) {
        if (file == null || !file.exists() || !file.getName().contains("kompile-")) return; // Basic check if it's likely our temp file

        Path parentDir = file.getParentFile().toPath();
        boolean isTempDir = parentDir.getFileName().toString().startsWith("kompile-pipelines-temp-") ||
                parentDir.getFileName().toString().startsWith("kompile-pipelines-global-temp-");


        if (!file.delete()) {
        } else {
            // Try deleting parent if it's one of our temp dirs and is empty
            if (isTempDir) {
                try (var stream = Files.list(parentDir)) {
                    if (stream.findAny().isEmpty()) {
                        if(Files.deleteIfExists(parentDir)) {
                        } else {
                        }
                    }
                } catch (IOException e) {
                }
            }
        }
    }
}