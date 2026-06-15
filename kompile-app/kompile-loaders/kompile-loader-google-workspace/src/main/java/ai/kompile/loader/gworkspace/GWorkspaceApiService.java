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

package ai.kompile.loader.gworkspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * HTTP client for Google Workspace REST APIs:
 * <ul>
 *   <li>Gmail API v1</li>
 *   <li>Drive API v3</li>
 *   <li>Calendar API v3</li>
 * </ul>
 * Uses raw {@link HttpClient} (no Google SDK dependency) and the OAuth access token
 * obtained from the kompile OAuth2 client module.
 */
@Slf4j
public class GWorkspaceApiService {

    private static final String GMAIL_API = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String DRIVE_API = "https://www.googleapis.com/drive/v3";
    private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String accessToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GWorkspaceApiService(String accessToken) {
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ========== Gmail API ==========

    /**
     * List Gmail message IDs matching a query.
     *
     * @param query    Gmail search query (e.g. "after:2025/01/01 before:2025/02/01")
     * @param maxResults max messages to return (0 = unlimited)
     * @return list of message IDs
     */
    public List<String> listGmailMessageIds(String query, int maxResults) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        boolean unlimited = maxResults <= 0;

        while (unlimited || ids.size() < maxResults) {
            if (Thread.currentThread().isInterrupted()) break;

            int batchSize = unlimited ? 500 : Math.min(maxResults - ids.size(), 500);
            StringBuilder url = new StringBuilder(GMAIL_API + "/messages?maxResults=" + batchSize);
            if (query != null && !query.isEmpty()) {
                url.append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            JsonNode response = getJson(url.toString());
            JsonNode messages = response.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) break;

            for (JsonNode msg : messages) {
                ids.add(msg.get("id").asText());
            }

            JsonNode nextPageToken = response.get("nextPageToken");
            if (nextPageToken == null || nextPageToken.isNull()) break;
            pageToken = nextPageToken.asText();
        }

        return ids;
    }

    /**
     * Get a full Gmail message by ID.
     */
    public JsonNode getGmailMessage(String messageId) throws IOException, InterruptedException {
        return getJson(GMAIL_API + "/messages/" + messageId + "?format=full");
    }

    /**
     * Get a Gmail message in metadata-only format (headers but no body).
     */
    public JsonNode getGmailMessageMetadata(String messageId) throws IOException, InterruptedException {
        return getJson(GMAIL_API + "/messages/" + messageId + "?format=metadata"
                + "&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Cc&metadataHeaders=Bcc"
                + "&metadataHeaders=Subject&metadataHeaders=Date&metadataHeaders=Message-ID"
                + "&metadataHeaders=In-Reply-To&metadataHeaders=References&metadataHeaders=List-Id"
                + "&metadataHeaders=Reply-To&metadataHeaders=Auto-Submitted");
    }

    /**
     * Download a Gmail attachment.
     */
    public byte[] getGmailAttachment(String messageId, String attachmentId) throws IOException, InterruptedException {
        JsonNode response = getJson(GMAIL_API + "/messages/" + messageId + "/attachments/" + attachmentId);
        String data = response.get("data").asText();
        return Base64.getUrlDecoder().decode(data);
    }

    /**
     * List Gmail labels.
     */
    public List<JsonNode> listGmailLabels() throws IOException, InterruptedException {
        JsonNode response = getJson(GMAIL_API + "/labels");
        JsonNode labels = response.get("labels");
        if (labels == null || !labels.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        labels.forEach(result::add);
        return result;
    }

    // ========== Drive API ==========

    /**
     * List Drive files matching a query.
     *
     * @param query Drive search query (e.g. "modifiedTime > '2025-01-01T00:00:00'")
     * @param maxResults max files to return
     * @return list of file metadata nodes
     */
    public List<JsonNode> listDriveFiles(String query, int maxResults) throws IOException, InterruptedException {
        List<JsonNode> files = new ArrayList<>();
        String pageToken = null;
        boolean unlimited = maxResults <= 0;
        String fields = "nextPageToken,files(id,name,mimeType,size,modifiedTime,createdTime,"
                + "webViewLink,parents,owners,sharingUser,shared,permissions,lastModifyingUser,"
                + "description,trashed)";

        while (unlimited || files.size() < maxResults) {
            if (Thread.currentThread().isInterrupted()) break;

            int batchSize = unlimited ? 1000 : Math.min(maxResults - files.size(), 1000);
            StringBuilder url = new StringBuilder(DRIVE_API + "/files?pageSize=" + batchSize
                    + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8));
            if (query != null && !query.isEmpty()) {
                url.append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }
            url.append("&orderBy=modifiedTime%20desc");
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            JsonNode response = getJson(url.toString());
            JsonNode fileList = response.get("files");
            if (fileList == null || !fileList.isArray() || fileList.isEmpty()) break;
            fileList.forEach(files::add);

            JsonNode nextPageToken = response.get("nextPageToken");
            if (nextPageToken == null || nextPageToken.isNull()) break;
            pageToken = nextPageToken.asText();
        }

        return files;
    }

    /**
     * Get Drive file metadata.
     */
    public JsonNode getDriveFile(String fileId) throws IOException, InterruptedException {
        String fields = "id,name,mimeType,size,modifiedTime,createdTime,webViewLink,parents,"
                + "owners,sharingUser,shared,permissions,lastModifyingUser,description,trashed";
        return getJson(DRIVE_API + "/files/" + fileId + "?fields="
                + URLEncoder.encode(fields, StandardCharsets.UTF_8));
    }

    /**
     * Get just the name of a Drive file/folder by ID.
     * Returns null if the lookup fails (deleted, no permission, etc.).
     */
    public String getDriveFileName(String fileId) {
        try {
            JsonNode node = getJson(DRIVE_API + "/files/" + fileId + "?fields=name");
            if (node != null && node.has("name")) {
                return node.get("name").asText(null);
            }
        } catch (Exception e) {
            log.debug("Could not resolve Drive file name for {}: {}", fileId, e.getMessage());
        }
        return null;
    }

    /**
     * List comments on a Drive file.
     */
    public List<JsonNode> getDriveComments(String fileId) throws IOException, InterruptedException {
        List<JsonNode> comments = new ArrayList<>();
        String pageToken = null;

        while (true) {
            StringBuilder url = new StringBuilder(DRIVE_API + "/files/" + fileId
                    + "/comments?pageSize=100&fields="
                    + URLEncoder.encode("nextPageToken,comments(id,author,content,createdTime,modifiedTime,resolved,replies)", StandardCharsets.UTF_8));
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            JsonNode response = getJson(url.toString());
            JsonNode commentList = response.get("comments");
            if (commentList == null || !commentList.isArray() || commentList.isEmpty()) break;
            commentList.forEach(comments::add);

            JsonNode nextPageToken = response.get("nextPageToken");
            if (nextPageToken == null || nextPageToken.isNull()) break;
            pageToken = nextPageToken.asText();
        }

        return comments;
    }

    /**
     * Download a Drive file's content to a temp file.
     */
    public Path downloadDriveFile(String fileId, String fileName) throws IOException, InterruptedException {
        String suffix = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) suffix = fileName.substring(dotIdx);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API + "/files/" + fileId + "?alt=media"))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download Drive file " + fileName + ": HTTP " + response.statusCode());
        }

        Path tempFile = Files.createTempFile("kompile-gdrive-", suffix);
        tempFile.toFile().deleteOnExit();
        try (InputStream is = response.body()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return tempFile;
    }

    // ========== Calendar API ==========

    /**
     * List calendars the user has access to.
     */
    public List<JsonNode> listCalendars() throws IOException, InterruptedException {
        List<JsonNode> calendars = new ArrayList<>();
        String pageToken = null;

        while (true) {
            StringBuilder url = new StringBuilder(CALENDAR_API + "/users/me/calendarList?maxResults=250");
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            JsonNode response = getJson(url.toString());
            JsonNode items = response.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) break;
            items.forEach(calendars::add);

            JsonNode nextPageToken = response.get("nextPageToken");
            if (nextPageToken == null || nextPageToken.isNull()) break;
            pageToken = nextPageToken.asText();
        }

        return calendars;
    }

    /**
     * List events from a calendar.
     *
     * @param calendarId  calendar ID ("primary" for the main calendar)
     * @param timeMin     RFC3339 start time (e.g. "2025-01-01T00:00:00Z")
     * @param timeMax     RFC3339 end time
     * @param maxResults  max events
     */
    public List<JsonNode> listCalendarEvents(String calendarId, String timeMin, String timeMax, int maxResults)
            throws IOException, InterruptedException {
        List<JsonNode> events = new ArrayList<>();
        String pageToken = null;
        boolean unlimited = maxResults <= 0;

        while (unlimited || events.size() < maxResults) {
            if (Thread.currentThread().isInterrupted()) break;

            int batchSize = unlimited ? 2500 : Math.min(maxResults - events.size(), 2500);
            StringBuilder url = new StringBuilder(CALENDAR_API + "/calendars/"
                    + URLEncoder.encode(calendarId, StandardCharsets.UTF_8)
                    + "/events?maxResults=" + batchSize + "&singleEvents=true&orderBy=startTime");
            if (timeMin != null) url.append("&timeMin=").append(URLEncoder.encode(timeMin, StandardCharsets.UTF_8));
            if (timeMax != null) url.append("&timeMax=").append(URLEncoder.encode(timeMax, StandardCharsets.UTF_8));
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            JsonNode response = getJson(url.toString());
            JsonNode items = response.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) break;
            items.forEach(events::add);

            JsonNode nextPageToken = response.get("nextPageToken");
            if (nextPageToken == null || nextPageToken.isNull()) break;
            pageToken = nextPageToken.asText();
        }

        return events;
    }

    // ========== Common ==========

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse("2");
            long retryMs = (long) (Double.parseDouble(retryAfter) * 1000);
            log.warn("Google API rate limited, waiting {}ms", retryMs);
            Thread.sleep(retryMs + 100);
            return getJson(url);
        }

        if (response.statusCode() == 401) {
            throw new IOException("Unauthorized: OAuth access token may be expired");
        }
        if (response.statusCode() == 403) {
            throw new IOException("Forbidden: missing API scope or quota exceeded for " + url);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google API error " + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readTree(response.body());
    }
}
