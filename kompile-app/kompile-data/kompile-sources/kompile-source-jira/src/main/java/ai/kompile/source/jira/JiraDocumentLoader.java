/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.jira;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;
import ai.kompile.oauth.service.providers.AtlassianCloudResourceResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Loads Jira Cloud issues through Atlassian 3LO or site-local API-token authentication. */
public class JiraDocumentLoader implements DocumentLoader {

    private static final int DEFAULT_MAX_ISSUES = 250;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_COMMENTS_PER_ISSUE = 1_000;

    private final OAuthConnectionService oauthService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final boolean enforceTrustedCloudSite;

    public JiraDocumentLoader(OAuthConnectionService oauthService, ObjectMapper mapper) {
        this(oauthService, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20)).build(), true);
    }

    JiraDocumentLoader(
            OAuthConnectionService oauthService, ObjectMapper mapper, HttpClient httpClient) {
        this(oauthService, mapper, httpClient, false);
    }

    private JiraDocumentLoader(
            OAuthConnectionService oauthService,
            ObjectMapper mapper,
            HttpClient httpClient,
            boolean enforceTrustedCloudSite) {
        this.oauthService = oauthService;
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.enforceTrustedCloudSite = enforceTrustedCloudSite;
    }

    @Override
    public String getName() {
        return "Jira Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor descriptor) {
        return descriptor != null
                && descriptor.getType() == DocumentSourceDescriptor.SourceType.JIRA;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor) throws Exception {
        return load(descriptor, null);
    }

    @Override
    public List<Document> load(
            DocumentSourceDescriptor descriptor,
            Consumer<LoaderProgress> progressCallback) throws Exception {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("JiraDocumentLoader only supports JIRA sources");
        }
        String siteBaseUrl = requireSiteUrl(descriptor.getPathOrUrl(), enforceTrustedCloudSite);
        Map<String, Object> metadata = descriptor.getMetadata() == null
                ? Map.of() : descriptor.getMetadata();
        ApiAccess access = resolveAccess(siteBaseUrl, metadata);
        String jql = resolveJql(metadata);
        int maxIssues = boundedInt(metadata.get("maxIssues"), DEFAULT_MAX_ISSUES, 1, 10_000);
        boolean includeComments = booleanValue(metadata.get("includeComments"), true);
        int commentLimit = boundedInt(metadata.get("commentLimit"), MAX_COMMENTS_PER_ISSUE,
                0, MAX_COMMENTS_PER_ISSUE);
        boolean includeAttachments = booleanValue(metadata.get("includeAttachments"), false);

        List<Document> documents = new ArrayList<>();
        String nextPageToken = null;
        report(progressCallback, 2, "Discovering Jira issues");
        while (documents.size() < maxIssues) {
            int pageSize = Math.min(PAGE_SIZE, maxIssues - documents.size());
            JsonNode response = search(access, jql, nextPageToken, pageSize,
                    includeComments, includeAttachments);
            JsonNode issues = response.path("issues");
            if (!issues.isArray() || issues.isEmpty()) break;
            for (JsonNode issue : issues) {
                List<JsonNode> comments = includeComments && commentLimit > 0
                        ? loadComments(access, issue, commentLimit) : List.of();
                documents.add(toDocument(
                        issue, siteBaseUrl, comments, includeAttachments,
                        descriptor.getCollectionName(), descriptor.getSourceId()));
                report(progressCallback,
                        Math.min(95, 5 + (int) (90.0 * documents.size() / maxIssues)),
                        "Loaded " + documents.size() + " Jira issue(s)");
                if (documents.size() >= maxIssues) break;
            }
            nextPageToken = response.path("nextPageToken").asText(null);
            if (response.path("isLast").asBoolean(false)
                    || nextPageToken == null || nextPageToken.isBlank()) break;
        }
        report(progressCallback, 100, "Loaded " + documents.size() + " Jira issue(s)");
        return List.copyOf(documents);
    }

    private JsonNode search(
            ApiAccess access,
            String jql,
            String nextPageToken,
            int maxResults,
            boolean includeComments,
            boolean includeAttachments) throws Exception {
        List<String> fields = new ArrayList<>(List.of(
                "summary", "description", "status", "issuetype", "project", "priority",
                "assignee", "reporter", "labels", "components", "created", "updated",
                "resolution", "resolutiondate", "parent", "subtasks"));
        if (includeComments) fields.add("comment");
        if (includeAttachments) fields.add("attachment");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        body.put("fields", fields);
        if (nextPageToken != null && !nextPageToken.isBlank()) {
            body.put("nextPageToken", nextPageToken);
        }
        String requestBody = mapper.writeValueAsString(body);
        HttpResponse<String> response = sendWithRetry(access, authorization ->
                HttpRequest.newBuilder(
                                URI.create(trimSlash(access.apiBaseUrl()) + "/rest/api/3/search/jql"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", authorization)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Jira API search failed with HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        return mapper.readTree(response.body());
    }

    private Document toDocument(
            JsonNode issue,
            String siteBaseUrl,
            List<JsonNode> commentsPage,
            boolean includeAttachments,
            String collectionName,
            String sourceId) {
        String key = issue.path("key").asText(issue.path("id").asText("unknown"));
        JsonNode fields = issue.path("fields");
        String summary = fields.path("summary").asText(key);
        StringBuilder text = new StringBuilder("# ").append(key).append(": ").append(summary);
        appendSection(text, "Description", adfText(fields.get("description")));
        if (!commentsPage.isEmpty()) {
            StringBuilder comments = new StringBuilder();
            for (JsonNode comment : commentsPage) {
                String author = comment.path("author").path("displayName").asText("unknown");
                String body = adfText(comment.get("body"));
                if (!body.isBlank()) {
                    if (comments.length() > 0) comments.append("\n\n");
                    comments.append(author).append(": ").append(body);
                }
            }
            appendSection(text, "Comments", comments.toString());
        }
        if (includeAttachments) {
            StringBuilder attachments = new StringBuilder();
            for (JsonNode attachment : fields.path("attachment")) {
                if (attachments.length() > 0) attachments.append('\n');
                attachments.append("- ").append(attachment.path("filename").asText("attachment"));
                String contentUrl = attachment.path("content").asText("");
                if (!contentUrl.isBlank()) attachments.append(" (").append(contentUrl).append(')');
            }
            appendSection(text, "Attachments", attachments.toString());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GraphConstants.META_SOURCE, "jira:" + key);
        metadata.put(GraphConstants.META_SOURCE_PATH, trimSlash(siteBaseUrl) + "/browse/" + key);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "jira");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "jira_issue");
        metadata.put(GraphConstants.META_LOADER, getName());
        metadata.put(GraphConstants.META_FILE_NAME, key + " - " + summary);
        putText(metadata, "jira.issueId", issue.path("id"));
        metadata.put("jira.issueKey", key);
        metadata.put("jira.summary", summary);
        putText(metadata, "jira.status", fields.path("status").path("name"));
        putText(metadata, "jira.issueType", fields.path("issuetype").path("name"));
        putText(metadata, "jira.projectKey", fields.path("project").path("key"));
        putText(metadata, "jira.projectName", fields.path("project").path("name"));
        putText(metadata, "jira.priority", fields.path("priority").path("name"));
        putText(metadata, "jira.assignee", fields.path("assignee").path("displayName"));
        putText(metadata, "jira.reporter", fields.path("reporter").path("displayName"));
        putText(metadata, "jira.created", fields.path("created"));
        putText(metadata, "jira.updated", fields.path("updated"));
        metadata.put("jira.labels", strings(fields.path("labels")));
        metadata.put("jira.commentCount", fields.path("comment").path("total").asInt(0));
        metadata.put("jira.commentsLoaded", commentsPage.size());
        metadata.put("jira.attachmentCount", fields.path("attachment").size());
        if (collectionName != null) metadata.put("collection_name", collectionName);
        if (sourceId != null) metadata.put(GraphConstants.META_SOURCE_ID, sourceId);
        return new Document(text.toString(), metadata);
    }

    private ApiAccess resolveAccess(String siteBaseUrl, Map<String, Object> metadata) {
        Optional<String> explicitBearer = text(metadata, "accessToken");
        if (explicitBearer.isPresent()) {
            String cloudId = text(metadata, "cloudId").orElseThrow(() ->
                    new IllegalArgumentException(
                            "Jira metadata.accessToken requires metadata.cloudId; bearer tokens are never sent to arbitrary site URLs"));
            return ApiAccess.fixed(AtlassianCloudResourceResolver.jiraApiBase(cloudId),
                    "Bearer " + explicitBearer.get());
        }
        Optional<String> explicitEmail = text(metadata, "email").or(() -> text(metadata, "username"));
        Optional<String> explicitApiToken = text(metadata, "apiToken").or(() -> text(metadata, "password"));
        if (explicitEmail.isPresent() || explicitApiToken.isPresent()) {
            if (explicitEmail.isEmpty() || explicitApiToken.isEmpty()) {
                throw new IllegalArgumentException(
                        "Jira API-token authentication requires both metadata.email and metadata.apiToken");
            }
            String basic = Base64.getEncoder().encodeToString(
                    (explicitEmail.get() + ":" + explicitApiToken.get()).getBytes(StandardCharsets.UTF_8));
            return ApiAccess.fixed(siteBaseUrl, "Basic " + basic);
        }
        if (oauthService != null) {
            String accessToken = oauthService.getValidAccessToken("atlassian");
            if (accessToken != null && !accessToken.isBlank()) {
                String cloudId = text(metadata, "cloudId").orElseGet(() ->
                        AtlassianCloudResourceResolver.resolveCloudId(
                                mapper, oauthService.getProviderData("atlassian"), siteBaseUrl));
                if (cloudId == null || cloudId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Atlassian OAuth is connected, but no accessible Jira cloud matches "
                                    + SourceCredentialRedactor.redact(siteBaseUrl)
                                    + "; pass metadata.cloudId explicitly");
                }
                return ApiAccess.managed(
                        AtlassianCloudResourceResolver.jiraApiBase(cloudId), oauthService);
            }
        }
        throw new IllegalArgumentException(
                "Jira requires an Atlassian OAuth connection, metadata.accessToken, or an email/API-token pair");
    }

    private static String resolveJql(Map<String, Object> metadata) {
        Optional<String> explicit = text(metadata, "jql");
        if (explicit.isPresent()) return explicit.get();
        Optional<String> project = text(metadata, "projectKey");
        if (project.isPresent()) {
            if (!project.get().matches("[A-Za-z][A-Za-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("Invalid Jira projectKey");
            }
            return "project = \"" + project.get() + "\" ORDER BY updated DESC";
        }
        return "ORDER BY updated DESC";
    }

    static String adfText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) return node.asText().trim();
        StringBuilder output = new StringBuilder();
        appendAdf(node, output);
        return output.toString().replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static void appendAdf(JsonNode node, StringBuilder output) {
        if (node.has("text")) output.append(node.path("text").asText());
        String type = node.path("type").asText();
        if ("hardBreak".equals(type)) output.append('\n');
        JsonNode content = node.path("content");
        if (content.isArray()) for (JsonNode child : content) appendAdf(child, output);
        if (List.of("paragraph", "heading", "listItem", "blockquote", "codeBlock")
                .contains(type) && output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
            output.append('\n');
        }
    }

    private static void appendSection(StringBuilder target, String title, String value) {
        if (value != null && !value.isBlank()) {
            target.append("\n\n## ").append(title).append("\n").append(value.trim());
        }
    }

    private static void putText(Map<String, Object> metadata, String key, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull()) {
            String text = value.asText();
            if (!text.isBlank()) metadata.put(key, text);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> values.add(value.asText()));
        }
        return List.copyOf(values);
    }

    private static Optional<String> text(Map<String, Object> metadata, String name) {
        Object value = metadata.get(name);
        if (value == null || value.toString().isBlank()) return Optional.empty();
        return Optional.of(value.toString().trim());
    }

    private static int boundedInt(Object value, int defaultValue, int min, int max) {
        int parsed = value instanceof Number number ? number.intValue() : value == null
                ? defaultValue : Integer.parseInt(value.toString());
        return Math.max(min, Math.min(max, parsed));
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        return value == null ? defaultValue
                : value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private static List<JsonNode> initialComments(JsonNode issue) {
        List<JsonNode> comments = new ArrayList<>();
        issue.path("fields").path("comment").path("comments").forEach(comments::add);
        return comments;
    }

    private List<JsonNode> loadComments(ApiAccess access, JsonNode issue, int commentLimit) throws Exception {
        List<JsonNode> embeddedComments = initialComments(issue);
        List<JsonNode> comments = new ArrayList<>(embeddedComments.subList(
                0, Math.min(commentLimit, embeddedComments.size())));
        int total = issue.path("fields").path("comment").path("total").asInt(comments.size());
        String issueKey = issue.path("key").asText();
        int startAt = comments.size();
        while (!issueKey.isBlank() && startAt < total && comments.size() < commentLimit) {
            int maxResults = Math.min(PAGE_SIZE, commentLimit - comments.size());
            String url = trimSlash(access.apiBaseUrl()) + "/rest/api/3/issue/" + issueKey
                    + "/comment?startAt=" + startAt + "&maxResults=" + maxResults;
            HttpResponse<String> response = sendWithRetry(access, authorization ->
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(60))
                            .header("Accept", "application/json")
                            .header("Authorization", authorization)
                            .GET().build());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Jira comment request failed with HTTP " + response.statusCode()
                        + ": " + abbreviate(response.body()));
            }
            JsonNode page = mapper.readTree(response.body());
            JsonNode pageComments = page.path("comments");
            if (!pageComments.isArray() || pageComments.isEmpty()) break;
            pageComments.forEach(comments::add);
            startAt += pageComments.size();
            total = page.path("total").asInt(total);
        }
        return List.copyOf(comments.size() <= commentLimit
                ? comments : comments.subList(0, commentLimit));
    }

    private HttpResponse<String> sendWithRetry(
            ApiAccess access, Function<String, HttpRequest> requestFactory) throws Exception {
        boolean authenticationRetried = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(
                        requestFactory.apply(access.authorization()),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException transportFailure) {
                if (attempt == 3) throw transportFailure;
                sleepBeforeRetry(null, attempt);
                continue;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response;
            if (response.statusCode() == 401 && access.managed()
                    && !authenticationRetried && attempt < 3) {
                authenticationRetried = true;
                access.forceRefresh();
                continue;
            }
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            if (!retryable || attempt == 3) return response;
            sleepBeforeRetry(response, attempt);
        }
        throw new IOException("Jira API request failed without a response");
    }

    private static void sleepBeforeRetry(HttpResponse<?> response, int attempt)
            throws InterruptedException {
        long delayMillis = Math.min(30_000L, 500L << Math.max(0, attempt - 1));
        if (response != null) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfter != null) {
                try {
                    delayMillis = Math.min(30_000L,
                            Math.max(0L, Long.parseLong(retryAfter.trim()) * 1_000L));
                } catch (NumberFormatException ignored) {
                    // Jira normally emits seconds; retain bounded exponential backoff otherwise.
                }
            }
        }
        if (delayMillis > 0) Thread.sleep(delayMillis);
    }

    private static String requireSiteUrl(String value, boolean enforceTrustedCloudSite) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Jira base URL is required");
        URI uri = URI.create(value.trim());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Jira base URL must be an absolute URL without credentials, query, or fragment");
        }
        if (enforceTrustedCloudSite && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Jira Cloud base URL must use HTTPS");
        }
        if (!enforceTrustedCloudSite && !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Jira base URL must use HTTP or HTTPS");
        }
        if (enforceTrustedCloudSite) {
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!host.endsWith(".atlassian.net") || host.length() <= ".atlassian.net".length()
                    || uri.getPort() != -1 && uri.getPort() != 443
                    || uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
                throw new IllegalArgumentException(
                        "Jira Cloud base URL must be the HTTPS root of an *.atlassian.net site");
            }
        }
        return trimSlash(value.trim());
    }

    private static String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String safe = SourceCredentialRedactor.redact(value);
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    private static void report(Consumer<LoaderProgress> callback, int percent, String message) {
        if (callback != null) callback.accept(new LoaderProgress(
                "jira", percent, null, message, Map.of()));
    }

    private record ApiAccess(
            String apiBaseUrl,
            String fixedAuthorization,
            OAuthConnectionService oauthService) {

        static ApiAccess fixed(String apiBaseUrl, String authorization) {
            return new ApiAccess(apiBaseUrl, authorization, null);
        }

        static ApiAccess managed(String apiBaseUrl, OAuthConnectionService oauthService) {
            return new ApiAccess(apiBaseUrl, null, oauthService);
        }

        boolean managed() {
            return oauthService != null;
        }

        String authorization() {
            if (!managed()) return fixedAuthorization;
            String token = oauthService.getValidAccessToken("atlassian");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Atlassian OAuth connection is no longer usable");
            }
            return "Bearer " + token;
        }

        void forceRefresh() {
            if (managed()) oauthService.refreshConnection("atlassian");
        }
    }
}
