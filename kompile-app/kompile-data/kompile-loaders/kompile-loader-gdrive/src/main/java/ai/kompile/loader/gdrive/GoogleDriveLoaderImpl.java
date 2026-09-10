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
import ai.kompile.cli.common.util.JsonUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li>{@code metadata.folderId} = when no explicit ids are given, load the file children of
 *       this Drive folder instead (immediate children only, files only; recursive traversal is
 *       the crawler's job). Optional {@code metadata.maxFiles} caps the listing (default 500).</li>
 *   <li>{@code metadata.accessToken} = optional OAuth access token override</li>
 * </ul>
 */
@Component
public class GoogleDriveLoaderImpl implements DocumentLoader, ai.kompile.core.loaders.FileDownloadingLoader {

    private static final Logger logger = LoggerFactory.getLogger(GoogleDriveLoaderImpl.class);

    private static final String DRIVE_API_BASE = "https://www.googleapis.com/drive/v3";
    private static final String FILE_FIELDS = "id,name,mimeType,size,modifiedTime,webViewLink,parents";
    private static final String GOOGLE_DOC_MIME_PREFIX = "application/vnd.google-apps.";
    private static final String OAUTH_PROVIDER_ID = "google";
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L; // 64 MiB hard cap per file

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String LIST_FIELDS = "nextPageToken,files(id,mimeType)";
    private static final int DEFAULT_MAX_FOLDER_FILES = 500;

    /** Drive API base; overridable for tests. */
    protected String apiBase() {
        return DRIVE_API_BASE;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
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

    /** Whether this provider delivers original files suitable for pipeline processing. */
    public boolean downloadsOriginalFiles() {
        return true;
    }

    /**
     * Downloads the source's files as original bytes into {@code destination}, returning the
     * list of written paths. Google Workspace documents are exported as PDF so downstream
     * content-type pipelines (text extraction, VLM OCR, table-aware, ...) process them like
     * any local file. This is the download step; processing is a pipeline decision.
     */
    public List<Path> downloadTo(DocumentSourceDescriptor sourceDescriptor, Path destination)
            throws Exception {
        String accessToken = resolveAccessToken(sourceDescriptor);
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException(
                    "No Google OAuth access token available. Connect the 'google' provider via the OAuth connections UI "
                            + "or pass metadata.accessToken.");
        }
        List<String> fileIds = resolveFileIds(sourceDescriptor);
        if (fileIds.isEmpty()) {
            String folderId = metadataString(sourceDescriptor, "folderId");
            if (folderId != null) {
                fileIds = listFolderFileIds(folderId, accessToken, sourceDescriptor);
            }
        }
        if (fileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Google Drive file ids provided. Set pathOrUrl or metadata.fileIds to a "
                            + "comma-separated list, or metadata.folderId to load a folder's files.");
        }
        Files.createDirectories(destination);
        List<Path> written = new ArrayList<>();
        for (String fileId : fileIds) {
            try {
                Path file = downloadOriginal(fileId, accessToken, destination);
                if (file != null) {
                    written.add(file);
                }
            } catch (Exception e) {
                logger.warn("Failed to download Google Drive file {}: {}", fileId, e.getMessage());
            }
        }
        return written;
    }

    /** Downloads one file as original bytes; Workspace docs export to PDF. */
    private Path downloadOriginal(String fileId, String accessToken, Path destination)
            throws Exception {
        JsonNode meta = fetchFileMetadata(fileId, accessToken);
        if (meta == null) {
            return null;
        }
        String name = meta.path("name").asText(fileId);
        String mimeType = meta.path("mimeType").asText("application/octet-stream");

        URI uri;
        String extension;
        if (mimeType.startsWith(GOOGLE_DOC_MIME_PREFIX)) {
            uri = URI.create(apiBase() + "/files/"
                    + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                    + "/export?mimeType="
                    + URLEncoder.encode("application/pdf", StandardCharsets.UTF_8));
            extension = ".pdf";
        } else {
            uri = URI.create(apiBase() + "/files/"
                    + URLEncoder.encode(fileId, StandardCharsets.UTF_8) + "?alt=media");
            extension = extensionForName(name);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            logger.warn("Google Drive download failed for {}: HTTP {}", fileId, response.statusCode());
            return null;
        }
        Path target = uniqueDestination(destination, name, extension);
        Files.write(target, response.body() == null ? new byte[0] : response.body());
        return target;
    }

    private static String extensionForName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    private static Path uniqueDestination(Path directory, String name, String extension) {
        String base = extension.isEmpty() ? name : name.substring(0, name.length() - extension.length());
        String sanitized = base.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (sanitized.isBlank()) sanitized = "file";
        Path candidate = directory.resolve(sanitized + extension);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(sanitized + "-" + suffix++ + extension);
        }
        return candidate;
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
            String folderId = metadataString(sourceDescriptor, "folderId");
            if (folderId != null) {
                fileIds = listFolderFileIds(folderId, accessToken, sourceDescriptor);
            }
        }
        if (fileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Google Drive file ids provided. Set pathOrUrl or metadata.fileIds to a "
                            + "comma-separated list, or metadata.folderId to load a folder's files.");
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
        String url = apiBase() + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
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
        String url = apiBase() + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
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
        String url = apiBase() + "/files/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
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

    private String metadataString(DocumentSourceDescriptor sourceDescriptor, String key) {
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get(key);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    /**
     * Lists the immediate file children of a Drive folder (sub-folders are skipped; recursive
     * traversal belongs to the crawler). Paginates until exhausted, capped at {@code maxFiles}.
     */
    private List<String> listFolderFileIds(
            String folderId, String accessToken, DocumentSourceDescriptor sourceDescriptor)
            throws Exception {
        int maxFiles = DEFAULT_MAX_FOLDER_FILES;
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata != null && metadata.get("maxFiles") instanceof Number number
                && number.intValue() > 0) {
            maxFiles = number.intValue();
        }
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            StringBuilder url = new StringBuilder(apiBase())
                    .append("/files?q=")
                    .append(URLEncoder.encode(
                            "'" + folderId.replace("'", "\\'") + "' in parents and trashed=false",
                            StandardCharsets.UTF_8))
                    .append("&fields=").append(URLEncoder.encode(LIST_FIELDS, StandardCharsets.UTF_8))
                    .append("&pageSize=200");
            if (pageToken != null) {
                url.append("&pageToken=")
                        .append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Google Drive folder listing failed for "
                        + folderId + ": HTTP " + response.statusCode());
            }
            JsonNode page = objectMapper.readTree(response.body());
            for (JsonNode file : page.path("files")) {
                if (FOLDER_MIME.equals(file.path("mimeType").asText(""))) {
                    continue;
                }
                String id = file.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                    if (ids.size() >= maxFiles) {
                        return ids;
                    }
                }
            }
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return ids;
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
