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

package ai.kompile.utility.conversion;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Service for downloading model files from remote URLs.
 */
public class ModelDownloader {
    private static final Logger logger = LoggerFactory.getLogger(ModelDownloader.class);
    
    private final Path workingDirectory;
    private final CloseableHttpClient httpClient;

    public ModelDownloader(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.httpClient = HttpClients.createDefault();
    }

    /**
     * Download a file from a URL to the specified local path.
     *
     * @param url The URL to download from
     * @param targetPath The local path to save to
     * @return DownloadResult containing success status and metadata
     */
    public DownloadResult downloadFile(String url, Path targetPath) {
        return downloadFile(url, targetPath, null);
    }

    /**
     * Download a file from a URL to the specified local path with checksum verification.
     *
     * @param url The URL to download from
     * @param targetPath The local path to save to
     * @param expectedChecksum Expected SHA-256 checksum (null to skip verification)
     * @return DownloadResult containing success status and metadata
     */
    public DownloadResult downloadFile(String url, Path targetPath, String expectedChecksum) {
        logger.info("Downloading {} to {}", url, targetPath);
        
        try {
            // Create parent directories if needed
            Files.createDirectories(targetPath.getParent());
            
            // Download the file
            try (InputStream in = new URL(url).openStream();
                 ReadableByteChannel rbc = Channels.newChannel(in);
                 FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                
                long bytesTransferred = fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                logger.info("Downloaded {} bytes from {}", bytesTransferred, url);
            }
            
            // Verify checksum if provided
            String actualChecksum = null;
            if (expectedChecksum != null) {
                actualChecksum = calculateChecksum(targetPath);
                if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                    Files.deleteIfExists(targetPath);
                    return DownloadResult.failure("Checksum mismatch. Expected: " + expectedChecksum + 
                                                ", Actual: " + actualChecksum);
                }
                logger.info("Checksum verified for {}", targetPath.getFileName());
            } else {
                actualChecksum = calculateChecksum(targetPath);
            }
            
            long fileSize = Files.size(targetPath);
            return DownloadResult.success(fileSize, actualChecksum);
            
        } catch (Exception e) {
            logger.error("Failed to download {} to {}", url, targetPath, e);
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException cleanupException) {
                logger.warn("Failed to cleanup incomplete download: {}", targetPath, cleanupException);
            }
            return DownloadResult.failure("Download failed: " + e.getMessage());
        }
    }

    /**
     * Download a file to the working directory with the specified filename.
     */
    public DownloadResult downloadToWorkingDirectory(String url, String filename) {
        Path targetPath = workingDirectory.resolve(filename);
        return downloadFile(url, targetPath);
    }

    /**
     * Download a file to the working directory with checksum verification.
     */
    public DownloadResult downloadToWorkingDirectory(String url, String filename, String expectedChecksum) {
        Path targetPath = workingDirectory.resolve(filename);
        return downloadFile(url, targetPath, expectedChecksum);
    }

    /**
     * Check if a URL is accessible (returns 200 OK).
     */
    public boolean isUrlAccessible(String url) {
        try {
            HttpGet request = new HttpGet(url);
            try (ClassicHttpResponse response = httpClient.execute(request)) {
                return response.getCode() == 200;
            }
        } catch (Exception e) {
            logger.debug("URL not accessible: {}", url, e);
            return false;
        }
    }

    /**
     * Get the content length of a URL without downloading.
     */
    public long getContentLength(String url) {
        try {
            HttpGet request = new HttpGet(url);
            try (ClassicHttpResponse response = httpClient.execute(request)) {
                if (response.getCode() == 200 && response.getEntity() != null) {
                    return response.getEntity().getContentLength();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get content length for: {}", url, e);
        }
        return -1;
    }

    private String calculateChecksum(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(filePath)) {
                byte[] byteArray = new byte[8192];
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

    public void close() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    /**
     * Result of a download operation.
     */
    public static class DownloadResult {
        private final boolean success;
        private final String errorMessage;
        private final long fileSize;
        private final String checksum;

        private DownloadResult(boolean success, String errorMessage, long fileSize, String checksum) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.fileSize = fileSize;
            this.checksum = checksum;
        }

        public static DownloadResult success(long fileSize, String checksum) {
            return new DownloadResult(true, null, fileSize, checksum);
        }

        public static DownloadResult failure(String errorMessage) {
            return new DownloadResult(false, errorMessage, 0, null);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public long getFileSize() { return fileSize; }
        public String getChecksum() { return checksum; }
    }
}
