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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Low-level HTTP client for the Gmail REST API v1.
 * Uses java.net.http.HttpClient directly — no Google SDK dependency.
 */
@Slf4j
public class GmailApiClient {

    private static final String GMAIL_API = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int MAX_RETRIES = 3;

    private final String accessToken;
    private final HttpClient httpClient;

    public GmailApiClient(String accessToken) {
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Lists message IDs matching the given Gmail search query.
     * Paginates automatically up to maxResults.
     */
    public List<String> listMessageIds(String query, int maxResults) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        int batchSize = Math.min(maxResults, 500);

        while (ids.size() < maxResults) {
            StringBuilder url = new StringBuilder(GMAIL_API)
                    .append("/messages?maxResults=").append(batchSize);
            if (query != null && !query.isBlank()) {
                url.append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }
            if (pageToken != null) {
                url.append("&pageToken=").append(pageToken);
            }

            JsonNode response = getJson(url.toString());
            JsonNode messages = response.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                break;
            }

            for (JsonNode msg : messages) {
                if (ids.size() >= maxResults) break;
                ids.add(msg.get("id").asText());
            }

            JsonNode nextToken = response.get("nextPageToken");
            if (nextToken == null || nextToken.isNull()) {
                break;
            }
            pageToken = nextToken.asText();
        }

        return ids;
    }

    /**
     * Fetches a full message by ID (format=full).
     */
    public JsonNode getMessage(String messageId) throws IOException, InterruptedException {
        return getJson(GMAIL_API + "/messages/" + messageId + "?format=full");
    }

    /**
     * Fetches message metadata only (format=metadata), with threading and date headers.
     */
    public JsonNode getMessageMetadata(String messageId) throws IOException, InterruptedException {
        return getJson(GMAIL_API + "/messages/" + messageId
                + "?format=metadata&metadataHeaders=From&metadataHeaders=To"
                + "&metadataHeaders=Subject&metadataHeaders=Date"
                + "&metadataHeaders=Message-ID&metadataHeaders=In-Reply-To"
                + "&metadataHeaders=References&metadataHeaders=Cc"
                + "&metadataHeaders=Bcc&metadataHeaders=List-Id"
                + "&metadataHeaders=Reply-To");
    }

    /**
     * Downloads an attachment by message ID and attachment ID.
     * Returns the raw bytes (base64url-decoded).
     */
    public byte[] getAttachment(String messageId, String attachmentId) throws IOException, InterruptedException {
        JsonNode response = getJson(GMAIL_API + "/messages/" + messageId
                + "/attachments/" + attachmentId);
        String data = response.get("data").asText();
        return Base64.getUrlDecoder().decode(data);
    }

    /**
     * Lists all labels for the authenticated user.
     */
    public List<JsonNode> listLabels() throws IOException, InterruptedException {
        JsonNode response = getJson(GMAIL_API + "/labels");
        JsonNode labels = response.get("labels");
        List<JsonNode> result = new ArrayList<>();
        if (labels != null && labels.isArray()) {
            labels.forEach(result::add);
        }
        return result;
    }

    /**
     * Lists threads matching the query. Returns thread IDs with snippet.
     */
    public List<JsonNode> listThreads(String query, int maxResults) throws IOException, InterruptedException {
        List<JsonNode> threads = new ArrayList<>();
        String pageToken = null;
        int batchSize = Math.min(maxResults, 500);

        while (threads.size() < maxResults) {
            StringBuilder url = new StringBuilder(GMAIL_API)
                    .append("/threads?maxResults=").append(batchSize);
            if (query != null && !query.isBlank()) {
                url.append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }
            if (pageToken != null) {
                url.append("&pageToken=").append(pageToken);
            }

            JsonNode response = getJson(url.toString());
            JsonNode threadNodes = response.get("threads");
            if (threadNodes == null || !threadNodes.isArray() || threadNodes.isEmpty()) {
                break;
            }

            for (JsonNode t : threadNodes) {
                if (threads.size() >= maxResults) break;
                threads.add(t);
            }

            JsonNode nextToken = response.get("nextPageToken");
            if (nextToken == null || nextToken.isNull()) {
                break;
            }
            pageToken = nextToken.asText();
        }

        return threads;
    }

    /**
     * Fetches a full thread (all messages in the thread).
     */
    public JsonNode getThread(String threadId) throws IOException, InterruptedException {
        return getJson(GMAIL_API + "/threads/" + threadId + "?format=full");
    }

    /**
     * Gets the user's Gmail profile (email address, messages total, threads total, history ID).
     */
    public JsonNode getProfile() throws IOException, InterruptedException {
        return getJson(GMAIL_API + "/profile");
    }

    /**
     * Lists history records since the given historyId.
     * Used for incremental sync — returns added/deleted message IDs.
     */
    public JsonNode listHistory(String startHistoryId, int maxResults) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(GMAIL_API)
                .append("/history?startHistoryId=").append(startHistoryId)
                .append("&maxResults=").append(Math.min(maxResults, 500));
        return getJson(url.toString());
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return MAPPER.readTree(response.body());
            }

            if (response.statusCode() == 429) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse("5");
                long waitSeconds = Long.parseLong(retryAfter);
                log.warn("Gmail API rate limited, waiting {}s before retry (attempt {}/{})",
                        waitSeconds, attempt + 1, MAX_RETRIES);
                Thread.sleep(waitSeconds * 1000);
                continue;
            }

            if (response.statusCode() == 401) {
                throw new IOException("Gmail API authentication failed (401). Access token may be expired.");
            }

            if (response.statusCode() == 403) {
                throw new IOException("Gmail API access forbidden (403). Check OAuth scopes. Response: "
                        + response.body());
            }

            if (response.statusCode() == 404) {
                throw new IOException("Gmail API resource not found (404): " + url);
            }

            if (response.statusCode() >= 500) {
                log.warn("Gmail API server error {}, retrying (attempt {}/{})",
                        response.statusCode(), attempt + 1, MAX_RETRIES);
                Thread.sleep(1000L * (attempt + 1));
                continue;
            }

            throw new IOException("Gmail API request failed with status " + response.statusCode()
                    + ": " + response.body());
        }

        throw new IOException("Gmail API request failed after " + MAX_RETRIES + " retries: " + url);
    }
}
