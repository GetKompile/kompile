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

package ai.kompile.staging.download;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Generic HTTP downloader for direct URLs, GitHub releases, S3, etc.
 */
@Component
public class HttpDownloader implements DownloadService {

    private static final Logger log = LoggerFactory.getLogger(HttpDownloader.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECTION_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;

    @Override
    public String getSourceName() {
        return "http";
    }

    @Override
    public boolean canHandle(String source) {
        return "http".equalsIgnoreCase(source) ||
               "https".equalsIgnoreCase(source) ||
               "github".equalsIgnoreCase(source) ||
               "s3".equalsIgnoreCase(source);
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination) {
        return download(request, destination, progress -> {});
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination,
                                   Consumer<DownloadProgress> progressCallback) {
        long startTime = System.currentTimeMillis();
        Map<String, Path> downloadedFiles = new HashMap<>();

        try {
            progressCallback.accept(DownloadProgress.initializing(
                    "Preparing to download from: " + request.getRepository()));

            Files.createDirectories(destination);

            String url = request.getRepository();

            // For GitHub, construct release URL
            if ("github".equalsIgnoreCase(request.getSource())) {
                url = buildGitHubReleaseUrl(request);
            }

            // Download the file
            String fileName = getFileName(url);
            Path downloadPath = destination.resolve(fileName);

            long bytesDownloaded = downloadFile(url, downloadPath, request.getAuthToken(),
                    progress -> progressCallback.accept(progress));

            downloadedFiles.put("archive", downloadPath);

            // Extract if it's an archive
            if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
                progressCallback.accept(DownloadProgress.extracting(fileName));
                extractTarGz(downloadPath, destination);
                Files.deleteIfExists(downloadPath);
                downloadedFiles.remove("archive");

                // Find extracted files
                findExtractedFiles(destination, downloadedFiles);
            } else {
                // Raw single-file download (.gguf, .ggml, .onnx, .pb, .h5, .sdz, .fb, ...).
                // StagingService routes on modelPath, so the downloaded file IS the model.
                downloadedFiles.put("model", downloadPath);
            }

            // Calculate checksum
            Path modelPath = downloadedFiles.get("model");
            String checksum = modelPath != null && Files.exists(modelPath)
                    ? calculateSha256(modelPath) : null;

            // Verify checksum if expected
            if (request.getExpectedChecksum() != null && checksum != null) {
                progressCallback.accept(DownloadProgress.verifying(
                        modelPath != null ? modelPath.getFileName().toString() : "model"));
                if (!request.getExpectedChecksum().equals(checksum)) {
                    return DownloadResult.failure("Checksum mismatch. Expected: " +
                            request.getExpectedChecksum() + ", Got: " + checksum);
                }
            }

            progressCallback.accept(DownloadProgress.completed(
                    "Download completed: " + downloadedFiles.size() + " files"));

            long duration = System.currentTimeMillis() - startTime;

            return DownloadResult.builder()
                    .success(true)
                    .modelPath(downloadedFiles.get("model"))
                    .vocabPath(downloadedFiles.get("vocab"))
                    .downloadedFiles(downloadedFiles)
                    .checksum(checksum)
                    .totalBytes(bytesDownloaded)
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            log.error("Failed to download: {}", request.getRepository(), e);
            progressCallback.accept(DownloadProgress.failed(e.getMessage()));
            return DownloadResult.failure("Download failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable(DownloadRequest request) {
        try {
            String url = request.getRepository();
            if ("github".equalsIgnoreCase(request.getSource())) {
                url = buildGitHubReleaseUrl(request);
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(CONNECTION_TIMEOUT);
                int responseCode = conn.getResponseCode();
                return responseCode == 200 || responseCode == 302;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String buildGitHubReleaseUrl(DownloadRequest request) {
        // Format: owner/repo for repository, revision for tag
        String[] parts = request.getRepository().split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GitHub repository format");
        }
        String owner = parts[0];
        String repo = parts[1];
        String tag = request.getRevision() != null ? request.getRevision() : "latest";
        String fileName = request.getModelId() + ".tar.gz";

        return String.format("https://github.com/%s/%s/releases/download/%s/%s",
                owner, repo, tag, fileName);
    }

    private String getFileName(String url) {
        int queryStart = url.indexOf('?');
        String path = queryStart > 0 ? url.substring(0, queryStart) : url;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : "download";
    }

    private long downloadFile(String urlStr, Path destination, String authToken,
                             Consumer<DownloadProgress> progressCallback) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECTION_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Kompile-Model-Staging/1.0");
        conn.setInstanceFollowRedirects(true);

        if (authToken != null && !authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + authToken);
        }

        int responseCode = conn.getResponseCode();

        // Handle redirects manually for cross-domain
        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == 307 || responseCode == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            return downloadFile(newUrl, destination, authToken, progressCallback);
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + responseCode + " for " + urlStr);
        }

        long contentLength = conn.getContentLengthLong();
        String fileName = destination.getFileName().toString();

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination))) {

            byte[] buffer = new byte[BUFFER_SIZE];
            long bytesDownloaded = 0;
            int bytesRead;
            long lastProgressUpdate = System.currentTimeMillis();
            long bytesAtLastUpdate = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                bytesDownloaded += bytesRead;

                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate >= 500) {
                    long elapsed = now - lastProgressUpdate;
                    long bytesDelta = bytesDownloaded - bytesAtLastUpdate;
                    long bytesPerSecond = elapsed > 0 ? (bytesDelta * 1000) / elapsed : 0;

                    progressCallback.accept(DownloadProgress.downloading(
                            fileName, bytesDownloaded, contentLength, bytesPerSecond));

                    lastProgressUpdate = now;
                    bytesAtLastUpdate = bytesDownloaded;
                }
            }

            return bytesDownloaded;
        } finally {
            conn.disconnect();
        }
    }

    private void extractTarGz(Path archive, Path destination) throws IOException {
        try (InputStream fi = Files.newInputStream(archive);
             BufferedInputStream bi = new BufferedInputStream(fi);
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                Path entryPath = destination.resolve(entry.getName()).normalize();

                // Security check - prevent path traversal
                if (!entryPath.startsWith(destination)) {
                    throw new IOException("Tar entry outside destination: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = ti.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }
        }
    }

    private void findExtractedFiles(Path directory, Map<String, Path> files) throws IOException {
        Files.walk(directory)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    if (name.endsWith(".sdz") || name.endsWith(".fb")) {
                        files.put("model", path);
                    } else if (name.equals("vocab.txt")) {
                        files.put("vocab", path);
                    } else if (name.equals("tokenizer_config.json")) {
                        files.put("tokenizer_config", path);
                    } else if (name.equals("tokenizer.json")) {
                        files.put("tokenizer", path);
                    }
                });
    }

    private String calculateSha256(Path file) throws IOException, NoSuchAlgorithmException {
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
    }
}
