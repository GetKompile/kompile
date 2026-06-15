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

package io.anserini.encoder.samediff;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Image encoder that delegates to a VLM model's vision encoder for image embeddings.
 *
 * This encoder can work in two modes:
 * <ul>
 *   <li><b>REST mode</b>: Delegates to a staging service REST API at /api/vlm/embed</li>
 *   <li><b>Local mode</b>: Uses VisionLanguageModel directly (requires samediff-vlm on classpath)</li>
 * </ul>
 *
 * The REST mode is preferred when the staging service already has the VLM model loaded,
 * avoiding the need to load heavy multi-component models in the main application.
 */
public class VlmImageEncoder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VlmImageEncoder.class);

    private final String modelId;
    private final String stagingServiceUrl;
    private final ObjectMapper objectMapper;
    private int cachedDimension = -1;

    /**
     * Create a VlmImageEncoder that delegates to a staging service REST API.
     *
     * @param modelId the VLM model identifier (e.g., "smoldocling-256m")
     * @param stagingServiceUrl the staging service base URL (e.g., "http://localhost:8090")
     */
    public VlmImageEncoder(String modelId, String stagingServiceUrl) {
        this.modelId = modelId;
        this.stagingServiceUrl = stagingServiceUrl;
        this.objectMapper = new ObjectMapper();
        log.info("VlmImageEncoder created for model: {} via staging service: {}", modelId, stagingServiceUrl);
    }

    /**
     * Get the model identifier.
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * Encode an image file to a float array embedding via the staging service.
     *
     * @param imageFile the image file to encode
     * @return float array embedding from the vision encoder
     * @throws IOException if encoding fails
     */
    public float[] encodeImage(File imageFile) throws IOException {
        if (stagingServiceUrl == null || stagingServiceUrl.isBlank()) {
            throw new IOException("No staging service URL configured for VLM image encoder");
        }

        byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
        return encodeImageBytes(imageBytes, imageFile.getName());
    }

    /**
     * Encode raw image bytes to a float array embedding.
     *
     * @param imageBytes raw image data
     * @param fileName optional filename for content type detection
     * @return float array embedding
     * @throws IOException if encoding fails
     */
    public float[] encodeImageBytes(byte[] imageBytes, String fileName) throws IOException {
        String endpoint = stagingServiceUrl.replaceAll("/$", "") + "/api/vlm/embed";

        String boundary = "----VlmImageBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            writeMultipartFile(os, boundary, "image", fileName != null ? fileName : "image.png", imageBytes);
            os.write(("--" + boundary + "--\r\n").getBytes());
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readStream(conn.getErrorStream());
            throw new IOException("Staging service returned " + responseCode + ": " + error);
        }

        String responseBody = readStream(conn.getInputStream());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

        Boolean success = (Boolean) result.get("success");
        if (success == null || !success) {
            String error = (String) result.get("error");
            throw new IOException("Image embedding failed: " + (error != null ? error : "unknown error"));
        }

        @SuppressWarnings("unchecked")
        List<Number> embeddingList = (List<Number>) result.get("embedding");
        if (embeddingList == null || embeddingList.isEmpty()) {
            throw new IOException("Empty embedding returned from staging service");
        }

        float[] embedding = new float[embeddingList.size()];
        for (int i = 0; i < embeddingList.size(); i++) {
            embedding[i] = embeddingList.get(i).floatValue();
        }

        cachedDimension = embedding.length;
        return embedding;
    }

    /**
     * Encode multiple images in batch.
     *
     * @param imageFiles list of image files to encode
     * @return list of float array embeddings
     * @throws IOException if any encoding fails
     */
    public List<float[]> encodeImageBatch(List<File> imageFiles) throws IOException {
        // For now, encode sequentially via single endpoint
        // Future: use /api/vlm/embed/batch for true batch processing
        List<float[]> results = new ArrayList<>();
        for (File imageFile : imageFiles) {
            results.add(encodeImage(imageFile));
        }
        return results;
    }

    /**
     * Get the embedding dimension.
     * Queries the staging service if dimension is not yet cached.
     *
     * @return embedding dimension, or -1 if unknown
     */
    public int getEmbeddingDimension() {
        if (cachedDimension > 0) {
            return cachedDimension;
        }

        // Try to get dimension from info endpoint
        try {
            String endpoint = stagingServiceUrl.replaceAll("/$", "") + "/api/vlm/embed/info";
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                @SuppressWarnings("unchecked")
                Map<String, Object> info = objectMapper.readValue(body, Map.class);
                Object dim = info.get("dimensions");
                if (dim instanceof Number) {
                    cachedDimension = ((Number) dim).intValue();
                    return cachedDimension;
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch embedding dimension from staging service: {}", e.getMessage());
        }

        return getEstimatedDimension();
    }

    /**
     * Check if the staging service has a VLM model loaded.
     */
    public boolean isAvailable() {
        try {
            String endpoint = stagingServiceUrl.replaceAll("/$", "") + "/api/vlm/embed/info";
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                @SuppressWarnings("unchecked")
                Map<String, Object> info = objectMapper.readValue(body, Map.class);
                return Boolean.TRUE.equals(info.get("loaded"));
            }
        } catch (Exception e) {
            log.debug("Staging service not available: {}", e.getMessage());
        }
        return false;
    }

    @Override
    public void close() {
        // No resources to close in REST mode
        log.debug("VlmImageEncoder closed for model: {}", modelId);
    }

    // ==================== Private Helpers ====================

    private int getEstimatedDimension() {
        if (modelId == null) return -1;
        String lower = modelId.toLowerCase();
        if (lower.contains("smoldocling-256m")) return 256;
        if (lower.contains("smoldocling-512m")) return 512;
        if (lower.contains("siglip")) return 768;
        if (lower.contains("clip")) return 512;
        if (lower.contains("donut")) return 768;
        return -1;
    }

    private void writeMultipartFile(OutputStream os, String boundary, String fieldName,
                                     String fileName, byte[] data) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n").getBytes());
        os.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes());
        os.write(data);
        os.write("\r\n".getBytes());
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
