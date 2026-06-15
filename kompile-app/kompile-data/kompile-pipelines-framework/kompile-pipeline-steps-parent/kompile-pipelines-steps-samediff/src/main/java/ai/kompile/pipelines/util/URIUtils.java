/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.util;

import ai.kompile.pipelines.framework.api.context.Context;
import org.jetbrains.annotations.NotNull; // Assuming this is your preferred annotation
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
// UUID import was unused, removed for now unless needed for temp file naming logic without context
// import java.util.UUID;

/**
 * Utility methods for handling URIs, particularly for resolving resource paths
 * like model files.
 */
public class URIUtils {

    private static final Logger log = LoggerFactory.getLogger(URIUtils.class);

    private URIUtils() {}

    /**
     * Resolves a URI string (supporting file:/, classpath:/, or plain paths)
     * to a local File object. Classpath resources are copied to a temporary file.
     *
     * @param uriString The URI string of the resource. Must not be null.
     * @param context The pipeline context, used to potentially get a shared temp directory. Must not be null.
     * @return A File object pointing to the local resource.
     * @throws IOException If the resource cannot be found or accessed.
     * @throws URISyntaxException If the URI string is invalid (and not a valid plain path).
     */
    public static File resolveToTempOrLocalFile(@NotNull String uriString, @NotNull Context context) throws IOException, URISyntaxException {
        Objects.requireNonNull(uriString, "URI string cannot be null");
        Objects.requireNonNull(context, "Context cannot be null for resolveToTempOrLocalFile");

        URI uri;
        try {
            // Attempt to parse as a formal URI first
            uri = new URI(uriString);
        } catch (URISyntaxException e) {
            // If parsing as URI fails, treat it as a plain file path.
            // This allows users to provide simple paths without a scheme.
            File plainFile = new File(uriString);
            if (!plainFile.exists()) {
                throw new FileNotFoundException("File not found at plain path: " + plainFile.getAbsolutePath() +
                        " (original input: " + uriString + ")");
            }
            if (!plainFile.isFile()) {
                throw new FileNotFoundException("Path exists but is not a regular file: " + plainFile.getAbsolutePath() +
                        " (original input: " + uriString + ")");
            }
            log.debug("Resolved plain path '{}' to file: {}", uriString, plainFile.getAbsolutePath());
            return plainFile;
        }

        String scheme = uri.getScheme();
        File resolvedFile;

        if (scheme == null) {
            // No scheme, could be an absolute or relative file path.
            // URI.getPath() on a schemeless URI might be null or empty if it's opaque.
            // uri.getPath() is preferred over uri.getSchemeSpecificPart() for schemeless hierarchical URIs.
            String pathPart = uri.getPath();
            if (pathPart == null || pathPart.isEmpty()) { // Fallback for opaque schemeless URIs like "my_model.zip"
                pathPart = uri.getSchemeSpecificPart();
            }
            resolvedFile = new File(pathPart);
            log.debug("No scheme in URI '{}', attempting to resolve path part '{}' as file: {}", uriString, pathPart, resolvedFile.getAbsolutePath());
        } else if ("file".equalsIgnoreCase(scheme)) {
            // Standard file URI, e.g., "file:///path/to/file" or "file:relative/path"
            // For "file:relative/path", uri.getPath() would be "relative/path".
            // For "file:///tmp/foo", uri.getPath() would be "/tmp/foo".
            resolvedFile = new File(uri.getPath());
            log.debug("File scheme URI '{}', resolved path with uri.getPath() to: {}", uriString, resolvedFile.getAbsolutePath());
        } else if ("classpath".equalsIgnoreCase(scheme)) {
            String resourcePath = uri.getSchemeSpecificPart();
            // Clean up potential leading slashes if getSchemeSpecificPart() includes them for classpath.
            // ClassLoader.getResourceAsStream usually wants path relative to classpath root without leading slash.
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            log.debug("Classpath scheme URI '{}', attempting to load resource: {}", uriString, resourcePath);

            try (InputStream resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    throw new FileNotFoundException("Resource not found in classpath at: " + resourcePath +
                            " (derived from URI: " + uriString + ")");
                }

                Path tempDir = findOrCreateTempDirectory(context); // Uses context

                String fileName = new File(resourcePath).getName(); // Basic way to get filename part
                if (fileName.isEmpty()) fileName = "resource"; // Default if path ends with /
                String prefix = fileName;
                String suffix = null;
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < fileName.length() - 1) { // ensure dot is not first or last char
                    prefix = fileName.substring(0, dotIndex);
                    suffix = fileName.substring(dotIndex);
                } else if (dotIndex == 0) { // like ".bashrc"
                    suffix = fileName;
                    prefix = "";
                }


                Path tempFile = Files.createTempFile(tempDir, prefix + "-", suffix);
                Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                resolvedFile = tempFile.toFile();
                log.info("Copied classpath resource '{}' to temporary file: {}", resourcePath, resolvedFile.getAbsolutePath());
                // Consider lifecycle of this temp file. Who deletes it?
                // resolvedFile.deleteOnExit(); // Use with caution; might be deleted before a long-running app using it finishes.
            }
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: '" + scheme + "' in URI: " + uriString +
                    ". Supported schemes are 'file', 'classpath', or a plain path.");
        }

        if (!resolvedFile.exists()) {
            throw new FileNotFoundException("Resolved file does not exist: " + resolvedFile.getAbsolutePath() +
                    " (from URI: " + uriString + ")");
        }
        if (!resolvedFile.isFile()) {
            throw new FileNotFoundException("Resolved path is not a regular file: " + resolvedFile.getAbsolutePath() +
                    " (from URI: " + uriString + ")");
        }
        return resolvedFile;
    }

    /**
     * Resolves a URI string (supporting file:/, classpath:/, or plain paths)
     * to a local File object. Classpath resources are copied to a temporary file in the system's default temp directory.
     * This method does NOT use the pipeline Context for determining the temporary directory.
     *
     * @param modelUriString The URI string or plain path of the resource. Must not be null.
     * @return A File object pointing to the local resource.
     * @throws IOException If the resource cannot be found or accessed.
     * @throws URISyntaxException If the URI string is invalid (and not a valid plain path).
     */
    public static File getFileFromUriOrPath(@NotNull String modelUriString) throws IOException, URISyntaxException {
        Objects.requireNonNull(modelUriString, "Model URI string cannot be null");

        URI uri;
        try {
            uri = new URI(modelUriString);
        } catch (URISyntaxException e) {
            log.debug("'{}' is not a valid URI, attempting to treat as a plain file path.", modelUriString, e);
            File plainFile = new File(modelUriString);
            if (!plainFile.exists()) {
                throw new FileNotFoundException("File not found at plain path: " + plainFile.getAbsolutePath() +
                        " (original input: " + modelUriString + ")");
            }
            if (!plainFile.isFile()) {
                throw new FileNotFoundException("Path exists but is not a regular file: " + plainFile.getAbsolutePath() +
                        " (original input: " + modelUriString + ")");
            }
            log.debug("Resolved plain path '{}' to file: {}", modelUriString, plainFile.getAbsolutePath());
            return plainFile;
        }

        String scheme = uri.getScheme();
        File resolvedFile;

        if (scheme == null) {
            String pathPart = uri.getPath();
            if (pathPart == null || pathPart.isEmpty()) {
                pathPart = uri.getSchemeSpecificPart();
            }
            resolvedFile = new File(pathPart);
            log.debug("No scheme in URI '{}', attempting to resolve path part '{}' as file: {}", modelUriString, pathPart, resolvedFile.getAbsolutePath());
        } else if ("file".equalsIgnoreCase(scheme)) {
            resolvedFile = new File(uri.getPath());
            log.debug("File scheme URI '{}', resolved path with uri.getPath() to: {}", modelUriString, resolvedFile.getAbsolutePath());
        } else if ("classpath".equalsIgnoreCase(scheme)) {
            String resourcePath = uri.getSchemeSpecificPart();
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            log.debug("Classpath scheme URI '{}', attempting to load resource: {}", modelUriString, resourcePath);

            try (InputStream resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    throw new FileNotFoundException("Resource not found in classpath at: " + resourcePath +
                            " (derived from URI: " + modelUriString + ")");
                }

                // Create in system default temp directory as no context is available
                Path tempDir = Files.createTempDirectory("kompile-global-temp-"); // Or a more specific prefix

                String fileName = new File(resourcePath).getName();
                String prefix = fileName;
                String suffix = null;
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
                    prefix = fileName.substring(0, dotIndex);
                    suffix = fileName.substring(dotIndex);
                } else if (dotIndex == 0) {
                    suffix = fileName;
                    prefix = "";
                }


                Path tempFile = Files.createTempFile(tempDir, prefix + "-", suffix);
                Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                resolvedFile = tempFile.toFile();
                log.info("Copied classpath resource '{}' to system temporary file: {}", resourcePath, resolvedFile.getAbsolutePath());
                // Make these files delete on exit as they are created in a generic system temp location
                // and this method doesn't have context for more nuanced lifecycle management.
                resolvedFile.deleteOnExit();
                // Also try to delete the temp directory we created if it's empty on exit.
                // This is harder to manage reliably with just deleteOnExit for the directory itself.
                // Consider a shutdown hook for cleaning up directories if many are created.
            }
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: '" + scheme + "' in URI: " + modelUriString +
                    ". Supported schemes are 'file', 'classpath', or a plain path.");
        }

        if (!resolvedFile.exists()) {
            throw new FileNotFoundException("Resolved file does not exist: " + resolvedFile.getAbsolutePath() +
                    " (from URI: " + modelUriString + ")");
        }
        if (!resolvedFile.isFile()) {
            throw new FileNotFoundException("Resolved path is not a regular file: " + resolvedFile.getAbsolutePath() +
                    " (from URI: " + modelUriString + ")");
        }
        return resolvedFile;
    }


    /**
     * Finds a temporary directory from the context or creates a default one.
     * This method is private and used by {@code resolveToTempOrLocalFile} which takes a Context.
     */
    private static Path findOrCreateTempDirectory(Context context) throws IOException {
        // The Context interface you provided has: <T> Optional<T> get(String key, Class<T> type);
        Optional<String> contextTempDirStr = context.get("kompile.tempDir", String.class);
        Path tempDir;

        if (contextTempDirStr.isPresent()) {
            tempDir = Path.of(contextTempDirStr.get());
            log.debug("Using configured temporary directory from context key 'kompile.tempDir': {}", tempDir);
        } else {
            // Create a default Kompile-specific temporary directory within the system's temp area
            String systemTemp = System.getProperty("java.io.tmpdir");
            tempDir = Path.of(systemTemp, "kompile-pipelines-temp-" + System.currentTimeMillis()); // More unique name
            log.debug("No 'kompile.tempDir' in context, creating default temporary directory: {}", tempDir);
            // It's a choice whether to put this back into context. If this method is called multiple times
            // for the same context, putting it back would allow reuse.
            // context.put("kompile.tempDir.created", tempDir.toString()); // Example
        }

        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
            log.debug("Created temporary directory: {}", tempDir);
        } else if (!Files.isDirectory(tempDir)){
            throw new IOException("Configured temporary path exists but is not a directory: " + tempDir);
        }
        return tempDir;
    }

    /**
     * Deletes a file quietly, logging warnings on failure. Intended for cleaning up temporary files.
     * Also attempts to delete the parent directory if it looks like one of our temporary directories and becomes empty.
     * @param file The file to delete (can be null).
     */
    public static void deleteTempFileQuietly(File file) {
        if (file == null || !file.exists()) return;

        // Basic check if it's likely one of *our* temp files to avoid deleting unrelated files
        // This check could be made more robust, e.g., by checking if the file is within a known temp dir.
        boolean isLikelyOurTempFile = file.getName().contains("-") && file.toPath().getParent().getFileName().toString().startsWith("kompile-");


        if (!file.delete()) {
            if (file.exists()) { // Check if delete actually failed
                log.warn("Failed to delete temporary file: {}", file.getAbsolutePath());
            }
        } else {
            log.debug("Successfully deleted temporary file: {}", file.getAbsolutePath());
            // Try deleting parent if it's one of our temp dirs and is empty
            Path parentDir = file.getParentFile().toPath();
            String parentDirName = parentDir.getFileName().toString();
            boolean isOurTempDir = parentDirName.startsWith("kompile-pipelines-temp-") ||
                    parentDirName.startsWith("kompile-global-temp-");

            if (isOurTempDir) {
                try (var stream = Files.list(parentDir)) {
                    if (stream.findAny().isEmpty()) { // Check if directory is empty
                        if(Files.deleteIfExists(parentDir)) {
                            log.debug("Successfully deleted empty temporary directory: {}", parentDir);
                        } else {
                            // This might happen if another file was created in the meantime, or permissions issue.
                            log.debug("Temporary directory {} was not deleted (either not empty or other issue).", parentDir);
                        }
                    } else {
                        log.debug("Temporary directory {} is not empty, not deleting.", parentDir);
                    }
                } catch (IOException e) {
                    log.warn("IOException while trying to check or delete temporary directory {}: {}", parentDir, e.getMessage());
                }
            }
        }
    }
}