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

package ai.kompile.cli.main.models;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import java.io.BufferedInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Manages the download, caching, and retrieval of ML/NLP models.
 */
public class KompileModelManager {
    private static final Logger LOGGER = Logger.getLogger(KompileModelManager.class.getName());
    private final Path baseCachePath;

    /**
     * Initializes the model manager with a specific base cache path.
     * If the KOMPILE_MODEL_CACHE_DIR environment variable is set, it's used.
     * Otherwise, defaults to ~/.kompile/models.
     */
    public KompileModelManager() {
        String cacheDirEnv = System.getenv(ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR);
        if (cacheDirEnv != null && !cacheDirEnv.trim().isEmpty()) {
            this.baseCachePath = Paths.get(cacheDirEnv.trim());
        } else {
            this.baseCachePath = Paths.get(System.getProperty("user.home"), ModelConstants.DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR);
        }
        try {
            Files.createDirectories(this.baseCachePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create base model cache directory: " + this.baseCachePath, e);
        }
        LOGGER.info("KompileModelManager initialized. Cache path: " + this.baseCachePath.toAbsolutePath());
    }

    public Path getBaseCachePath() {
        return baseCachePath;
    }

    /**
     * Ensures a model is available in the cache, downloading it if necessary.
     *
     * @param descriptor The descriptor of the model to ensure.
     * @return The path to the cached model artifact or directory.
     * @throws IOException if an I/O error occurs during download or caching.
     */
    public Path ensureModelAvailable(ModelDescriptor descriptor) throws IOException {
        Path modelPathInCache = baseCachePath.resolve(descriptor.getExpectedCacheSubpath());

        // A simple check: if a directory is expected, check if it exists and is not empty.
        // If a file is expected, check if it exists.
        // More sophisticated versioning/update checks could be added here.
        boolean needsDownload = true;
        if (Files.exists(modelPathInCache)) {
            if (descriptor.getDownloadUrl().endsWith(".tar.gz")) { // Assuming tar.gz implies a directory after extraction
                if (Files.isDirectory(modelPathInCache) && modelPathInCache.toFile().list().length > 0) {
                    LOGGER.info("Model directory " + descriptor.getModelId() + " already exists in cache and is not empty: " + modelPathInCache);
                    needsDownload = false;
                } else {
                    LOGGER.info("Model directory " + descriptor.getModelId() + " exists but is empty or not a directory. Re-downloading.");
                }
            } else { // Assuming it's a single file
                LOGGER.info("Model file " + descriptor.getModelId() + " already exists in cache: " + modelPathInCache);
                needsDownload = false;
            }
        }


        if (needsDownload) {
            LOGGER.info("Downloading model " + descriptor.getModelId() + " from " + descriptor.getDownloadUrl() + " to " + modelPathInCache.getParent());
            Files.createDirectories(modelPathInCache.getParent()); // Ensure parent directory exists

            Path tempDownloadPath = Files.createTempFile(baseCachePath, descriptor.getModelId() + "_download", ".tmp");
            try {
                URL url = new URL(descriptor.getDownloadUrl());
                try (InputStream in = url.openStream();
                     ReadableByteChannel rbc = Channels.newChannel(in);
                     FileOutputStream fos = new FileOutputStream(tempDownloadPath.toFile())) {
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                }

                LOGGER.info("Successfully downloaded " + descriptor.getModelId() + " to temporary location: " + tempDownloadPath);

                if (descriptor.getChecksum() != null && !descriptor.getChecksum().trim().isEmpty()) {
                    String fileChecksum = calculateSha256(tempDownloadPath);
                    if (!descriptor.getChecksum().equalsIgnoreCase(fileChecksum)) {
                        Files.deleteIfExists(tempDownloadPath);
                        throw new IOException("Checksum mismatch for model " + descriptor.getModelId() + ". Expected " +
                                descriptor.getChecksum() + ", but got " + fileChecksum);
                    }
                    LOGGER.info("Checksum verified for model " + descriptor.getModelId());
                }


                if (descriptor.getDownloadUrl().endsWith(".tar.gz")) {
                    LOGGER.info("Extracting " + tempDownloadPath + " to " + modelPathInCache.getParent());
                    // Ensure the target modelPathInCache directory is clean or created
                    if(Files.exists(modelPathInCache) && Files.isDirectory(modelPathInCache)) {
                        // Simple cleanup, for robust solution use more careful deletion
                        Files.walk(modelPathInCache).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                    }
                    Files.createDirectories(modelPathInCache);
                    extractTarGz(tempDownloadPath, modelPathInCache.getParent()); // Extract into parent, then rename/move if structure differs
                    // Often tarballs contain a single root folder, adjust modelPathInCache if necessary
                    // For simplicity, we assume extraction creates descriptor.getExpectedCacheSubpath() directly or its contents.
                    // A more robust solution would inspect the tar contents.
                    // If tar extracts to "modelPathInCache/archive_root/", we might want modelPathInCache to be "archive_root"
                    LOGGER.info("Successfully extracted " + descriptor.getModelId() + " to " + modelPathInCache.getParent());
                    // Assuming the tarball extracts its contents into a folder named like the tarball (minus .tar.gz)
                    // or directly into the expectedCacheSubpath.
                    // If the tarball creates a single directory, this code assumes
                    // expectedCacheSubpath *is* that directory.
                } else {
                    Files.move(tempDownloadPath, modelPathInCache, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Moved " + descriptor.getModelId() + " to " + modelPathInCache);
                }

            } finally {
                Files.deleteIfExists(tempDownloadPath); // Clean up temp file
            }
        }
        return modelPathInCache;
    }


    private String calculateSha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(path)) {
                byte[] byteArray = new byte[1024];
                int bytesCount;
                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
            }
            byte[] bytes = digest.digest();
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private void extractTarGz(Path tarGzPath, Path destinationDir) throws IOException {
        LOGGER.info("Extracting TAR.GZ file: " + tarGzPath + " to " + destinationDir);
        try (InputStream fi = Files.newInputStream(tarGzPath);
             BufferedInputStream bi = new BufferedInputStream(fi);
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                Path newPath = destinationDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent()); // Ensure parent dir exists
                    Files.copy(ti, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        LOGGER.info("Finished extracting TAR.GZ file: " + tarGzPath);
    }
}