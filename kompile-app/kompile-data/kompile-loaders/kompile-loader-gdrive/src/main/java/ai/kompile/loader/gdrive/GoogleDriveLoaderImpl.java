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

package ai.kompile.loader.gdrive;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Document loader for ingesting files from Google Drive.
 *
 * <p>The access token is looked up at load time from {@link OAuthConnectionService}
 * under provider id {@code "google"}. Callers must have completed the OAuth flow via
 * the OAuth connections UI before indexing. A token can also be provided per-request
 * via {@code metadata.accessToken} for ad-hoc scripted use.</p>
 *
 * <p>Source descriptor expectations:</p>
 * <ul>
 *   <li>{@code type} = {@link DocumentSourceDescriptor.SourceType#GDRIVE}</li>
 *   <li>{@code pathOrUrl} = a single file id, OR a comma-separated list of file ids</li>
 *   <li>{@code metadata.fileIds} = alternative {@code List<String>} or comma-separated string of ids</li>
 *   <li>{@code metadata.accessToken} = optional OAuth access token override</li>
 * </ul>
 */
@Component
public class GoogleDriveLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(GoogleDriveLoaderImpl.class);

    private static final String DRIVE_API_BASE = "https://www.googleapis.com/drive/v3";
    private static final String FILE_FIELDS = "id,name,mimeType,size,modifiedTime,webViewLink,parents";
    private static final String GOOGLE_DOC_MIME_PREFIX = "application/vnd.google-apps.";
    private static final String OAUTH_PROVIDER_ID = "google";
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L; // 64 MiB hard cap per file

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OAuthConnectionService oauthService;

    @Autowired
    public GoogleDriveLoaderImpl(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getName() {
        return "Google Drive Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor != null
                && sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.GDRIVE;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (!supports(sourceDescriptor)) {
            throw new IllegalArgumentException("GoogleDriveLoader only supports GDRIVE source type.");
        }

        String accessToken = resolveAccessToken(sourceDescriptor);
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException(
                    "No Google OAuth access token available. Connect the 'google' provider via the OAuth connections UI "
                            + "or pass metadata.accessToken.");
        }

        List<String> fileIds = resolveFileIds(sourceDescriptor);
        if (fileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Google Drive file ids provided. Set pathOrUrl to a comma-separated list or metadata.fileIds.");
        }

        List<Document> documents = new ArrayList<>();
        for (String fileId : fileIds) {
            try {
                Document doc = loadSingleFile(fileId, accessToken, sourceDescriptor);
                if (doc != null) {
                    documents.add(doc);
                }
            } catch (Exception e) {
                logger.warn("Failed to load Google Drive file {}: {}", fileId, e.getMessage());
            }
        }
        return documents;
    }

    private Document loadSingleFile(String fileId, String accessToken,
                                     DocumentSourceDescriptor sourceDescriptor) throws Exception {
        JsonNode meta = fetchFileMetadata(fileId, accessToken);
        if (meta == null) {
            return null;
        }
        String name = meta.path("name").asText(fileId);
        String mimeType = meta.path("mimeType").asText("application/octet-stream");

        String content;
        if (mimeType.startsWith(GOOGLE_DOC_MIME_PREFIX)) {
            String exportMime = chooseExportMimeType(mimeType);
            if (exportMime == null) {
                logger.info("Skipping unsupported Google Workspace type {} for file {}", mimeType, fileId);
                return null;
            }
            content = exportGoogleDoc(fileId, exportMime, accessToken);
        } else {
            content = downloadBinaryAsText(fileId, accessToken);
        }

        Document document = new Document(content == null ? "" : content);
        Map<String, Object> md = document.getMetadata();
        md.put("source", "gdrive");
        md.put("source_type", "GDRIVE");
        md.put("loader", getName());
        md.put("gdrive_file_id", fileId);
        md.put("gdrive_file_name", name);
        md.put("gdrive_mime_type", mimeType);
        if (meta.has("size")) {
            md.put("gdrive_size_bytes", meta.get("size").asText());
        }
        if (meta.has("modifiedTime")) {
            md.put("gdrive_modified_time", meta.get("modifiedTime").asText());
        }
        if (meta.has("webViewLink")) {
            md.put("gdrive_web_view_link", meta.get("webViewLink").asText());
        }
        if (sourceDescriptor.getCollectionName() != null) {
            md.put("collection_name", sourceDescriptor.getCollectionName());
        }
        if (sourceDescriptor.getSourceId() != null) {
            md.put("source_id", sourceDescriptor.getSourceId());
        }
        return document;
    }

    private JsonNode fetchFileMetadata(String fileId, String accessToken) throws Exception {
        String url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                + "?fields=" + URLEncoder.encode(FILE_FIELDS, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            logger.warn("Google Drive metadata lookup failed for {}: HTTP {}", fileId, response.statusCode());
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    private String exportGoogleDoc(String fileId, String exportMime, String accessToken) throws Exception {
        String url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                + "/export?mimeType=" + URLEncoder.encode(exportMime, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Google Drive export failed: HTTP " + response.statusCode());
        }
        return clampAndDecode(response.body());
    }

    private String downloadBinaryAsText(String fileId, String accessToken) throws Exception {
        String url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                + "?alt=media";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Google Drive download failed: HTTP " + response.statusCode());
        }
        return clampAndDecode(response.body());
    }

    private String clampAndDecode(byte[] body) {
        if (body == null) {
            return "";
        }
        int len = (int) Math.min(body.length, MAX_DOWNLOAD_BYTES);
        return new String(body, 0, len, StandardCharsets.UTF_8);
    }

    private String chooseExportMimeType(String googleMime) {
        switch (googleMime) {
            case "application/vnd.google-apps.document":
                return "text/plain";
            case "application/vnd.google-apps.spreadsheet":
                return "text/csv";
            case "application/vnd.google-apps.presentation":
                return "text/plain";
            case "application/vnd.google-apps.drawing":
                return "image/png";
            default:
                return null;
        }
    }

    private String resolveAccessToken(DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata != null) {
            Object override = metadata.get("accessToken");
            if (override instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        if (oauthService != null) {
            return oauthService.getValidAccessToken(OAUTH_PROVIDER_ID);
        }
        return null;
    }

    private List<String> resolveFileIds(DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata != null) {
            Object raw = metadata.get("fileIds");
            if (raw instanceof List<?> list) {
                List<String> ids = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item != null) {
                        String s = item.toString().trim();
                        if (!s.isEmpty()) {
                            ids.add(s);
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    return ids;
                }
            } else if (raw instanceof String s && !s.isEmpty()) {
                return splitIds(s);
            }
        }
        String path = sourceDescriptor.getPathOrUrl();
        if (path != null && !path.isEmpty()) {
            return splitIds(path);
        }
        return List.of();
    }

    private List<String> splitIds(String commaSeparated) {
        List<String> ids = new ArrayList<>();
        for (String piece : Arrays.asList(commaSeparated.split(","))) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids;
    }
}
