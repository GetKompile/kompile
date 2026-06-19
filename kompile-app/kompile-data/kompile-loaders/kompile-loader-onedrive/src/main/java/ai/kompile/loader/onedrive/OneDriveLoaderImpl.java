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

package ai.kompile.loader.onedrive;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Document loader for ingesting files from Microsoft OneDrive via the Graph API.
 *
 * <p>The access token is looked up at load time from {@link OAuthConnectionService}
 * under provider id {@code "microsoft"}. Callers must have completed the OAuth flow
 * via the OAuth connections UI before indexing. A token can also be provided
 * per-request via {@code metadata.accessToken}.</p>
 *
 * <p>Source descriptor expectations:</p>
 * <ul>
 *   <li>{@code type} = {@link DocumentSourceDescriptor.SourceType#ONEDRIVE}</li>
 *   <li>{@code pathOrUrl} = a single item id, OR a comma-separated list of item ids</li>
 *   <li>{@code metadata.itemIds} = alternative {@code List<String>} or comma-separated string of item ids</li>
 *   <li>{@code metadata.driveId} = optional OneDrive drive id (defaults to the signed-in user's drive)</li>
 *   <li>{@code metadata.accessToken} = optional OAuth access token override</li>
 * </ul>
 */
@Component
public class OneDriveLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(OneDriveLoaderImpl.class);

    private static final String GRAPH_API_BASE = "https://graph.microsoft.com/v1.0";
    private static final String OAUTH_PROVIDER_ID = "microsoft";
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L; // 64 MiB hard cap per file

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final OAuthConnectionService oauthService;

    @Autowired
    public OneDriveLoaderImpl(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getName() {
        return "OneDrive Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor != null
                && sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.ONEDRIVE;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (!supports(sourceDescriptor)) {
            throw new IllegalArgumentException("OneDriveLoader only supports ONEDRIVE source type.");
        }

        String accessToken = resolveAccessToken(sourceDescriptor);
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException(
                    "No Microsoft OAuth access token available. Connect the 'microsoft' provider via the OAuth connections UI "
                            + "or pass metadata.accessToken.");
        }

        String driveId = resolveDriveId(sourceDescriptor);
        List<String> itemIds = resolveItemIds(sourceDescriptor);
        if (itemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No OneDrive item ids provided. Set pathOrUrl to a comma-separated list or metadata.itemIds.");
        }

        List<Document> documents = new ArrayList<>();
        for (String itemId : itemIds) {
            try {
                Document doc = loadSingleItem(itemId, driveId, accessToken, sourceDescriptor);
                if (doc != null) {
                    documents.add(doc);
                }
            } catch (Exception e) {
                logger.warn("Failed to load OneDrive item {}: {}", itemId, e.getMessage());
            }
        }
        return documents;
    }

    private Document loadSingleItem(String itemId, String driveId, String accessToken,
                                     DocumentSourceDescriptor sourceDescriptor) throws Exception {
        JsonNode meta = fetchItemMetadata(itemId, driveId, accessToken);
        if (meta == null) {
            return null;
        }
        String name = meta.path("name").asText(itemId);
        JsonNode fileNode = meta.path("file");
        if (fileNode.isMissingNode() || fileNode.isNull()) {
            logger.info("Skipping OneDrive item {} because it is not a file (folder or other)", itemId);
            return null;
        }
        String mimeType = fileNode.path("mimeType").asText("application/octet-stream");

        String content = downloadItemAsText(itemId, driveId, accessToken, mimeType);

        Document document = new Document(content == null ? "" : content);
        Map<String, Object> md = document.getMetadata();
        md.put("source", "onedrive");
        md.put("source_type", "ONEDRIVE");
        md.put("loader", getName());
        md.put("onedrive_item_id", itemId);
        md.put("onedrive_name", name);
        md.put("onedrive_mime_type", mimeType);
        if (driveId != null) {
            md.put("onedrive_drive_id", driveId);
        }
        if (meta.has("size")) {
            md.put("onedrive_size_bytes", meta.get("size").asLong());
        }
        if (meta.has("lastModifiedDateTime")) {
            md.put("onedrive_last_modified", meta.get("lastModifiedDateTime").asText());
        }
        if (meta.has("webUrl")) {
            md.put("onedrive_web_url", meta.get("webUrl").asText());
        }
        if (sourceDescriptor.getCollectionName() != null) {
            md.put("collection_name", sourceDescriptor.getCollectionName());
        }
        if (sourceDescriptor.getSourceId() != null) {
            md.put("source_id", sourceDescriptor.getSourceId());
        }
        return document;
    }

    private JsonNode fetchItemMetadata(String itemId, String driveId, String accessToken) throws Exception {
        String url = itemPath(driveId, itemId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            logger.warn("OneDrive metadata lookup failed for {}: HTTP {}", itemId, response.statusCode());
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    private String downloadItemAsText(String itemId, String driveId, String accessToken, String mimeType)
            throws Exception {
        String baseUrl = itemPath(driveId, itemId) + "/content";
        String url = needsTextFormatConversion(mimeType) ? baseUrl + "?format=text" : baseUrl;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("OneDrive download failed: HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body == null) {
            return "";
        }
        int len = (int) Math.min(body.length, MAX_DOWNLOAD_BYTES);
        return new String(body, 0, len, StandardCharsets.UTF_8);
    }

    private boolean needsTextFormatConversion(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return mimeType.contains("officedocument") || mimeType.equals("application/msword")
                || mimeType.equals("application/vnd.ms-excel")
                || mimeType.equals("application/vnd.ms-powerpoint");
    }

    private String itemPath(String driveId, String itemId) {
        String encodedItem = URLEncoder.encode(itemId, StandardCharsets.UTF_8);
        if (driveId != null && !driveId.isEmpty()) {
            return GRAPH_API_BASE + "/drives/" + URLEncoder.encode(driveId, StandardCharsets.UTF_8)
                    + "/items/" + encodedItem;
        }
        return GRAPH_API_BASE + "/me/drive/items/" + encodedItem;
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

    private String resolveDriveId(DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get("driveId");
        if (raw instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    private List<String> resolveItemIds(DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata != null) {
            Object raw = metadata.get("itemIds");
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
