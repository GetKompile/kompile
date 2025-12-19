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
 * Download service for HuggingFace models.
 * Downloads ONNX models and associated vocabulary files.
 */
@Component
public class HuggingFaceDownloader implements DownloadService {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceDownloader.class);
    private static final String BASE_URL = "https://huggingface.co";
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECTION_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 60000;

    @Override
    public String getSourceName() {
        return "huggingface";
    }

    @Override
    public boolean canHandle(String source) {
        return "huggingface".equalsIgnoreCase(source) || "hf".equalsIgnoreCase(source);
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
        long totalBytes = 0;

        try {
            progressCallback.accept(DownloadProgress.initializing(
                    "Preparing to download from HuggingFace: " + request.getRepository()));

            // Create destination directory
            Files.createDirectories(destination);

            // Determine files to download
            Map<String, String> files = request.getFiles();
            if (files.isEmpty()) {
                // Default files for ONNX models
                files = getDefaultOnnxFiles();
            }

            // Download each file
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String fileKey = entry.getKey();
                String filePath = entry.getValue();

                String url = buildDownloadUrl(request.getRepository(), filePath, request.getRevision());
                String fileName = getFileName(filePath);
                Path targetPath = destination.resolve(fileName);

                progressCallback.accept(DownloadProgress.initializing(
                        "Downloading " + fileName + " from " + request.getRepository()));

                long fileBytes = downloadFile(url, targetPath, request.getAuthToken(),
                        progress -> progressCallback.accept(progress));

                downloadedFiles.put(fileKey, targetPath);
                totalBytes += fileBytes;

                log.info("Downloaded {} ({} bytes)", fileName, fileBytes);
            }

            // Calculate checksum of model file
            Path modelPath = downloadedFiles.get("model");
            String checksum = modelPath != null ? calculateSha256(modelPath) : null;

            progressCallback.accept(DownloadProgress.completed(
                    "Downloaded " + downloadedFiles.size() + " files from " + request.getRepository()));

            long duration = System.currentTimeMillis() - startTime;

            return DownloadResult.builder()
                    .success(true)
                    .modelPath(downloadedFiles.get("model"))
                    .vocabPath(downloadedFiles.get("vocab"))
                    .tokenizerConfigPath(downloadedFiles.get("tokenizer_config"))
                    .downloadedFiles(downloadedFiles)
                    .checksum(checksum)
                    .totalBytes(totalBytes)
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            log.error("Failed to download from HuggingFace: {}", request.getRepository(), e);
            progressCallback.accept(DownloadProgress.failed(e.getMessage()));
            return DownloadResult.failure("Download failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable(DownloadRequest request) {
        try {
            String url = buildDownloadUrl(request.getRepository(), "config.json", request.getRevision());
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECTION_TIMEOUT);
            int responseCode = conn.getResponseCode();
            return responseCode == 200;
        } catch (Exception e) {
            log.debug("Model not available: {}", request.getRepository(), e);
            return false;
        }
    }

    private String buildDownloadUrl(String repo, String filePath, String revision) {
        String rev = revision != null ? revision : "main";
        return String.format("%s/%s/resolve/%s/%s", BASE_URL, repo, rev, filePath);
    }

    private Map<String, String> getDefaultOnnxFiles() {
        Map<String, String> files = new HashMap<>();
        files.put("model", "onnx/model.onnx");
        files.put("vocab", "vocab.txt");
        files.put("tokenizer_config", "tokenizer_config.json");
        return files;
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private long downloadFile(String urlStr, Path destination, String authToken,
                             Consumer<DownloadProgress> progressCallback) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECTION_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Kompile-Model-Staging/1.0");

        if (authToken != null && !authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        // Handle redirects
        int responseCode = conn.getResponseCode();
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
                if (now - lastProgressUpdate >= 500) { // Update every 500ms
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
