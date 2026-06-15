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

package ai.kompile.loader.gdocs;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

/**
 * Low-level HTTP client for the Google Drive v3 and Google Docs v1 REST APIs.
 * Uses java.net.http.HttpClient directly — no Google SDK dependency.
 *
 * Drive API: listing files, getting metadata, exporting content
 * Docs API: getting structured document content (headings, tables, lists)
 */
@Slf4j
public class GoogleDocsApiClient {

    private static final String DRIVE_API = "https://www.googleapis.com/drive/v3";
    private static final String DOCS_API = "https://docs.googleapis.com/v1/documents";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 3;

    /** Fields requested from Drive file metadata */
    private static final String FILE_FIELDS =
            "id,name,mimeType,size,modifiedTime,createdTime,webViewLink,parents,owners,lastModifyingUser,version";

    private final String accessToken;
    private final HttpClient httpClient;

    public GoogleDocsApiClient(String accessToken) {
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ── Drive API: List / Search ──────────────────────────────────────────

    /**
     * Lists Google Docs files in Drive matching the given query.
     * Automatically filters to Google Docs MIME type and paginates.
     *
     * @param query additional Drive query (appended to mimeType filter), or null
     * @param maxResults maximum number of file entries to return
     * @return list of file metadata JsonNodes (each with id, name, mimeType, etc.)
     */
    public List<JsonNode> listDocFiles(String query, int maxResults) throws IOException, InterruptedException {
        List<JsonNode> files = new ArrayList<>();
        String pageToken = null;
        int pageSize = Math.min(maxResults, 1000);

        String baseQuery = "mimeType='application/vnd.google-apps.document' and trashed=false";
        if (query != null && !query.isBlank()) {
            baseQuery += " and " + query;
        }

        while (files.size() < maxResults) {
            StringBuilder url = new StringBuilder(DRIVE_API)
                    .append("/files?pageSize=").append(pageSize)
                    .append("&fields=").append(enc("nextPageToken,files(" + FILE_FIELDS + ")"))
                    .append("&q=").append(enc(baseQuery))
                    .append("&orderBy=modifiedTime%20desc");
            if (pageToken != null) {
                url.append("&pageToken=").append(enc(pageToken));
            }

            JsonNode response = getJson(url.toString());
            JsonNode fileNodes = response.get("files");
            if (fileNodes == null || !fileNodes.isArray() || fileNodes.isEmpty()) {
                break;
            }

            for (JsonNode f : fileNodes) {
                if (files.size() >= maxResults) break;
                files.add(f);
            }

            JsonNode nextToken = response.get("nextPageToken");
            if (nextToken == null || nextToken.isNull()) {
                break;
            }
            pageToken = nextToken.asText();
        }

        return files;
    }

    /**
     * Lists files in a specific Drive folder. Includes all file types, not just Docs.
     */
    public List<JsonNode> listFilesInFolder(String folderId, int maxResults)
            throws IOException, InterruptedException {
        String query = "'" + folderId + "' in parents and trashed=false";
        List<JsonNode> files = new ArrayList<>();
        String pageToken = null;

        while (files.size() < maxResults) {
            StringBuilder url = new StringBuilder(DRIVE_API)
                    .append("/files?pageSize=").append(Math.min(maxResults, 1000))
                    .append("&fields=").append(enc("nextPageToken,files(" + FILE_FIELDS + ")"))
                    .append("&q=").append(enc(query));
            if (pageToken != null) {
                url.append("&pageToken=").append(enc(pageToken));
            }

            JsonNode response = getJson(url.toString());
            JsonNode fileNodes = response.get("files");
            if (fileNodes == null || !fileNodes.isArray() || fileNodes.isEmpty()) {
                break;
            }
            for (JsonNode f : fileNodes) {
                if (files.size() >= maxResults) break;
                files.add(f);
            }

            JsonNode nextToken = response.get("nextPageToken");
            if (nextToken == null || nextToken.isNull()) break;
            pageToken = nextToken.asText();
        }

        return files;
    }

    // ── Drive API: Metadata and Export ─────────────────────────────────────

    /**
     * Fetches file metadata for a single file by ID.
     */
    public JsonNode getFileMetadata(String fileId) throws IOException, InterruptedException {
        String url = DRIVE_API + "/files/" + enc(fileId)
                + "?fields=" + enc(FILE_FIELDS);
        return getJson(url);
    }

    /**
     * Exports a Google Docs file as plain text via Drive API.
     */
    public String exportAsText(String fileId) throws IOException, InterruptedException {
        return exportFile(fileId, "text/plain");
    }

    /**
     * Exports a Google Docs file as HTML via Drive API.
     */
    public String exportAsHtml(String fileId) throws IOException, InterruptedException {
        return exportFile(fileId, "text/html");
    }

    /**
     * Exports a Drive file in the specified MIME type.
     */
    public String exportFile(String fileId, String exportMimeType) throws IOException, InterruptedException {
        String url = DRIVE_API + "/files/" + enc(fileId)
                + "/export?mimeType=" + enc(exportMimeType);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            }

            if (response.statusCode() == 429) {
                handleRateLimit(response, attempt);
                continue;
            }

            if (response.statusCode() >= 500) {
                log.warn("Drive export server error {}, retrying (attempt {}/{})",
                        response.statusCode(), attempt + 1, MAX_RETRIES);
                Thread.sleep(1000L * (attempt + 1));
                continue;
            }

            throw new IOException("Drive export failed with status " + response.statusCode()
                    + " for file " + fileId + ": " + response.body());
        }

        throw new IOException("Drive export failed after " + MAX_RETRIES + " retries for file " + fileId);
    }

    // ── Docs API: Structured Document ──────────────────────────────────────

    /**
     * Fetches the full structured document content via the Google Docs API v1.
     * Returns the raw JSON with body, headers, footnotes, named ranges, revision info, etc.
     */
    public JsonNode getDocument(String documentId) throws IOException, InterruptedException {
        String url = DOCS_API + "/" + enc(documentId);
        return getJson(url);
    }

    /**
     * Fetches document with specific fields only (for lighter payloads).
     */
    public JsonNode getDocumentFields(String documentId, String fields) throws IOException, InterruptedException {
        String url = DOCS_API + "/" + enc(documentId) + "?fields=" + enc(fields);
        return getJson(url);
    }

    // ── Drive API: Revisions ──────────────────────────────────────────────

    /**
     * Lists revisions for a file (useful for tracking changes over time).
     */
    public List<JsonNode> listRevisions(String fileId, int maxResults) throws IOException, InterruptedException {
        String url = DRIVE_API + "/files/" + enc(fileId) + "/revisions"
                + "?fields=" + enc("revisions(id,modifiedTime,lastModifyingUser)")
                + "&pageSize=" + Math.min(maxResults, 200);
        JsonNode response = getJson(url);
        List<JsonNode> revisions = new ArrayList<>();
        JsonNode items = response.get("revisions");
        if (items != null && items.isArray()) {
            items.forEach(revisions::add);
        }
        return revisions;
    }

    // ── Drive API: Comments ───────────────────────────────────────────────

    /**
     * Lists comments on a file.
     */
    public List<JsonNode> listComments(String fileId, int maxResults) throws IOException, InterruptedException {
        String url = DRIVE_API + "/files/" + enc(fileId) + "/comments"
                + "?fields=" + enc("comments(id,content,author,createdTime,resolved,replies)")
                + "&pageSize=" + Math.min(maxResults, 100);
        JsonNode response = getJson(url);
        List<JsonNode> comments = new ArrayList<>();
        JsonNode items = response.get("comments");
        if (items != null && items.isArray()) {
            items.forEach(comments::add);
        }
        return comments;
    }

    // ── Drive API: About (user info) ──────────────────────────────────────

    /**
     * Gets the authenticated user's Drive profile info.
     */
    public JsonNode getAbout() throws IOException, InterruptedException {
        String url = DRIVE_API + "/about?fields=" + enc("user(displayName,emailAddress)");
        return getJson(url);
    }

    // ── Internal HTTP plumbing ────────────────────────────────────────────

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
                handleRateLimit(response, attempt);
                continue;
            }

            if (response.statusCode() == 401) {
                throw new IOException("Google API authentication failed (401). Access token may be expired.");
            }

            if (response.statusCode() == 403) {
                throw new IOException("Google API access forbidden (403). Check OAuth scopes. Response: "
                        + response.body());
            }

            if (response.statusCode() == 404) {
                throw new IOException("Google API resource not found (404): " + url);
            }

            if (response.statusCode() >= 500) {
                log.warn("Google API server error {}, retrying (attempt {}/{})",
                        response.statusCode(), attempt + 1, MAX_RETRIES);
                Thread.sleep(1000L * (attempt + 1));
                continue;
            }

            throw new IOException("Google API request failed with status " + response.statusCode()
                    + ": " + response.body());
        }

        throw new IOException("Google API request failed after " + MAX_RETRIES + " retries: " + url);
    }

    private void handleRateLimit(HttpResponse<?> response, int attempt) throws InterruptedException {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("5");
        long waitSeconds = Long.parseLong(retryAfter);
        log.warn("Google API rate limited, waiting {}s before retry (attempt {}/{})",
                waitSeconds, attempt + 1, MAX_RETRIES);
        Thread.sleep(waitSeconds * 1000);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
