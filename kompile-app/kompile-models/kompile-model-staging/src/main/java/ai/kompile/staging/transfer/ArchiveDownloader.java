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

package ai.kompile.staging.transfer;

import ai.kompile.staging.archive.ArchiveImporter;
import ai.kompile.staging.archive.KompileArchive;
import ai.kompile.staging.auth.AuthProviderChain;
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.http.SafeHttpTransport;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Service for downloading Kompile archives from URLs with resume support.
 */
@Service
public class ArchiveDownloader {

    private static final Logger log = LoggerFactory.getLogger(ArchiveDownloader.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECTION_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 300000; // 5 minutes for large archives

    private final AuthProviderChain authProviderChain;
    private final ArchiveImporter archiveImporter;
    private final StagingAssetLimits limits;
    private final SafeHttpTransport httpTransport;

    @Value("${kompile.archive.download.resume-enabled:true}")
    private boolean resumeEnabled;

    @Value("${kompile.archive.download.directory:#{systemProperties['user.home']}/.kompile/downloads}")
    private String downloadDirectory;

    public ArchiveDownloader(
            AuthProviderChain authProviderChain,
            ArchiveImporter archiveImporter) {
        this(
                authProviderChain,
                archiveImporter,
                new StagingAssetLimits(),
                new SafeHttpTransport());
    }

    @Autowired
    public ArchiveDownloader(
            AuthProviderChain authProviderChain,
            ArchiveImporter archiveImporter,
            StagingAssetLimits limits,
            SafeHttpTransport httpTransport) {
        this.authProviderChain = authProviderChain;
        this.archiveImporter = archiveImporter;
        this.limits = limits;
        this.httpTransport = httpTransport;
    }

    /**
     * Download an archive from a URL.
     *
     * @param url URL to download from
     * @param options Download options
     * @return Result of the download
     */
    public DownloadResult download(String url, DownloadOptions options) {
        return download(url, options, progress -> {});
    }

    /**
     * Download an archive from a URL with progress callback.
     *
     * @param url URL to download from
     * @param options Download options
     * @param progressCallback Callback for progress updates
     * @return Result of the download
     */
    public DownloadResult download(String url, DownloadOptions options,
                                    Consumer<TransferProgress> progressCallback) {
        log.info("Downloading archive from: {}", safeUrlForDiagnostics(url));

        try {
            // Determine destination path
            Path destPath = options.getDestinationPath() != null
                    ? options.getDestinationPath()
                    : getDefaultDownloadPath(url);

            Files.createDirectories(destPath.getParent());

            String fileName = destPath.getFileName().toString();
            progressCallback.accept(TransferProgress.initializing(fileName));

            // Check for resumable download
            ResumeableDownload resumeState = null;
            if (resumeEnabled && options.isAllowResume()) {
                resumeState = ResumeableDownload.load(destPath);
                if (resumeState != null && resumeState.canResume()) {
                    log.info("Resuming download from {} bytes", resumeState.getBytesDownloaded());
                    progressCallback.accept(TransferProgress.resuming(
                            fileName, resumeState.getBytesDownloaded(), resumeState.getTotalBytes()));
                } else {
                    resumeState = null;
                }
            }

            // If not resuming, create new state
            if (resumeState == null) {
                resumeState = ResumeableDownload.create(url, destPath);
            }

            // Perform download
            long bytesDownloaded =
                    performDownload(url, destPath, resumeState, progressCallback);

            // Verify checksum if provided
            String actualChecksum = null;
            if (options.getExpectedChecksum() != null || options.isVerifyChecksum()) {
                progressCallback.accept(TransferProgress.verifying(fileName));
                actualChecksum = calculateSha256(destPath);

                if (options.getExpectedChecksum() != null &&
                    !options.getExpectedChecksum().equals(actualChecksum)) {
                    return DownloadResult.failure(
                            "Checksum mismatch. Expected: " + options.getExpectedChecksum() +
                            ", Got: " + actualChecksum);
                }
            }

            // Cleanup state file
            resumeState.deleteStateFile();

            progressCallback.accept(TransferProgress.completed(fileName, bytesDownloaded));

            log.info("Download completed: {} bytes", bytesDownloaded);

            return DownloadResult.success(destPath, bytesDownloaded, actualChecksum);

        } catch (Exception e) {
            String safeUrl = safeUrlForDiagnostics(url);
            log.error("Download failed for {}", safeUrl, e);
            progressCallback.accept(TransferProgress.failed(safeUrl, e.getMessage()));
            return DownloadResult.failure("Download failed: " + e.getMessage());
        }
    }

    /**
     * Download and import an archive in one operation.
     */
    public DownloadResult downloadAndImport(String url, DownloadOptions downloadOptions,
                                             ArchiveImporter.ImportOptions importOptions,
                                             Consumer<TransferProgress> progressCallback) {
        // First download
        DownloadResult downloadResult = download(url, downloadOptions, progressCallback);
        if (!downloadResult.isSuccess()) {
            return downloadResult;
        }

        // Then import
        ArchiveImporter.ImportResult importResult = archiveImporter.importArchive(
                downloadResult.getArchivePath(), importOptions);

        if (!importResult.isSuccess()) {
            return DownloadResult.failure("Import failed: " + importResult.getErrorMessage());
        }

        return downloadResult;
    }

    /**
     * Preview an archive from a URL without downloading.
     */
    public KompileArchive previewRemoteArchive(String url) {
        // For preview, we'd need to stream and parse just the manifest
        // For now, this is a simplified implementation
        log.info("Preview not implemented for remote archives. Download first.");
        return null;
    }

    private long performDownload(
            String url,
            Path destPath,
            ResumeableDownload state,
            Consumer<TransferProgress> progressCallback) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid archive download URI", invalid);
        }

        Map<String, String> headers =
                new HashMap<>(authProviderChain.getAuthHeaders(uri.toString()));
        headers.put("User-Agent", "Kompile-Archive-Downloader/1.0");

        long requestedStartByte = 0;
        if (state.getBytesDownloaded() > 0 && state.canResume()) {
            requestedStartByte = state.getBytesDownloaded();
            headers.put("Range", "bytes=" + requestedStartByte + "-");
        }

        try (SafeHttpTransport.Response response = httpTransport.execute(
                uri,
                "GET",
                headers,
                CONNECTION_TIMEOUT,
                READ_TIMEOUT,
                limits.getMaxRedirects())) {
            state.setUrl(response.uri().toString());

            int responseCode = response.statusCode();
            boolean isResume = responseCode == 206;
            if (responseCode != 200 && !isResume) {
                throw new IOException("HTTP " + responseCode + " for "
                        + SafeHttpTransport.safeUriForDiagnostics(response.uri()));
            }
            if (isResume) {
                validateContentRange(response.header("Content-Range"), requestedStartByte);
            }

            long initialBytes = isResume ? requestedStartByte : 0;
            long maximumBytes = limits.getTotalBytes();
            long contentLength = response.contentLength();
            long totalSize = contentLength < 0
                    ? -1
                    : checkedAdd(initialBytes, contentLength);
            if (totalSize > maximumBytes) {
                throw new IOException("Archive download exceeds its configured byte limit");
            }

            state.setTotalBytes(totalSize);
            state.setSupportsResume(
                    isResume || response.header("Accept-Ranges") != null);
            state.setEtag(response.header("ETag"));

            String fileName = destPath.getFileName().toString();
            boolean append = isResume && initialBytes > 0;
            try (InputStream in = new BufferedInputStream(response.body());
                 OutputStream out = new BufferedOutputStream(
                         append
                                 ? Files.newOutputStream(
                                         destPath,
                                         java.nio.file.StandardOpenOption.APPEND)
                                 : Files.newOutputStream(destPath))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long bytesDownloaded = initialBytes;
                int bytesRead;
                long lastProgressUpdate = System.currentTimeMillis();
                long bytesAtLastUpdate = bytesDownloaded;

                while ((bytesRead = in.read(buffer)) != -1) {
                    bytesDownloaded = checkedAdd(bytesDownloaded, bytesRead);
                    if (bytesDownloaded > maximumBytes) {
                        throw new IOException(
                                "Archive download exceeded its configured byte limit");
                    }
                    out.write(buffer, 0, bytesRead);

                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdate >= 500) {
                        long elapsed = now - lastProgressUpdate;
                        long bytesDelta = bytesDownloaded - bytesAtLastUpdate;
                        long bytesPerSecond =
                                elapsed > 0 ? (bytesDelta * 1000) / elapsed : 0;

                        progressCallback.accept(TransferProgress.downloading(
                                fileName, bytesDownloaded, totalSize, bytesPerSecond));

                        state.updateProgress(bytesDownloaded);
                        state.save();

                        lastProgressUpdate = now;
                        bytesAtLastUpdate = bytesDownloaded;
                    }
                }
                state.updateProgress(bytesDownloaded);
                return bytesDownloaded;
            }
        }
    }

    private static void validateContentRange(String contentRange, long expectedStart)
            throws IOException {
        String expectedPrefix = "bytes " + expectedStart + "-";
        if (contentRange == null
                || !contentRange.regionMatches(
                        true, 0, expectedPrefix, 0, expectedPrefix.length())) {
            throw new IOException(
                    "Archive resume response did not match the requested byte range");
        }
    }

    private static long checkedAdd(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IOException("Archive download byte count overflowed", overflow);
        }
    }

    private static String safeUrlForDiagnostics(String value) {
        try {
            return SafeHttpTransport.safeUriForDiagnostics(URI.create(value));
        } catch (RuntimeException invalid) {
            return "<invalid-remote-uri>";
        }
    }

    private Path getDefaultDownloadPath(String url) {
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.contains("?")) {
            fileName = fileName.substring(0, fileName.indexOf('?'));
        }
        if (!KompileArchive.isKarchFile(fileName)) {
            fileName = fileName + KompileArchive.EXTENSION;
        }
        return Paths.get(downloadDirectory, fileName);
    }

    private String calculateSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /**
     * Download options.
     */
    @Data
    @Builder
    public static class DownloadOptions {
        /**
         * Destination path for the download.
         */
        private Path destinationPath;

        /**
         * Expected checksum for verification.
         */
        private String expectedChecksum;

        /**
         * Whether to verify checksum after download.
         */
        @Builder.Default
        private boolean verifyChecksum = true;

        /**
         * Whether to allow resuming interrupted downloads.
         */
        @Builder.Default
        private boolean allowResume = true;

        /**
         * Default download options.
         */
        public static DownloadOptions defaults() {
            return DownloadOptions.builder().build();
        }

        /**
         * Download options with specific destination.
         */
        public static DownloadOptions toPath(Path path) {
            return DownloadOptions.builder()
                    .destinationPath(path)
                    .build();
        }
    }

    /**
     * Result of a download operation.
     */
    @Data
    @Builder
    public static class DownloadResult {
        private final boolean success;
        private final String errorMessage;
        private final Path archivePath;
        private final long bytesDownloaded;
        private final String checksum;

        public static DownloadResult success(Path path, long bytes, String checksum) {
            return DownloadResult.builder()
                    .success(true)
                    .archivePath(path)
                    .bytesDownloaded(bytes)
                    .checksum(checksum)
                    .build();
        }

        public static DownloadResult failure(String error) {
            return DownloadResult.builder()
                    .success(false)
                    .errorMessage(error)
                    .build();
        }
    }
}
