/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.reddit;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Loads Reddit posts and optional comment threads through Reddit OAuth. */
public class RedditDocumentLoader implements DocumentLoader {

    private static final String API_BASE = "https://oauth.reddit.com";
    private static final String DEFAULT_USER_AGENT = "Kompile/0.1 (Reddit source integration)";
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final Set<String> SORT_TYPES =
            Set.of("hot", "new", "top", "rising", "controversial");
    private static final Set<String> TIME_PERIODS =
            Set.of("hour", "day", "week", "month", "year", "all");

    private final OAuthConnectionService oauthService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String apiBase;

    public RedditDocumentLoader(OAuthConnectionService oauthService, ObjectMapper mapper) {
        this(oauthService, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20)).build(), API_BASE);
    }

    RedditDocumentLoader(
            OAuthConnectionService oauthService,
            ObjectMapper mapper,
            HttpClient httpClient,
            String apiBase) {
        this.oauthService = oauthService;
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override public String getName() { return "Reddit Loader"; }

    @Override
    public boolean supports(DocumentSourceDescriptor descriptor) {
        return descriptor != null
                && descriptor.getType() == DocumentSourceDescriptor.SourceType.REDDIT;
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
            throw new IllegalArgumentException("RedditDocumentLoader only supports REDDIT sources");
        }
        Map<String, Object> metadata = descriptor.getMetadata() == null
                ? Map.of() : descriptor.getMetadata();
        String subreddit = normalizeSubreddit(descriptor.getPathOrUrl());
        Optional<String> explicitToken = text(metadata, "accessToken");
        TokenAccess tokenAccess = explicitToken
                .map(TokenAccess::fixed)
                .orElseGet(() -> TokenAccess.managed(oauthService));
        String initialToken = tokenAccess.token();
        if (initialToken == null || initialToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Reddit requires a connected Reddit OAuth account or metadata.accessToken");
        }
        String sort = text(metadata, "sortType").orElse("hot").toLowerCase(Locale.ROOT);
        if (!SORT_TYPES.contains(sort)) throw new IllegalArgumentException("Unsupported Reddit sortType: " + sort);
        String time = text(metadata, "timePeriod").orElse("week").toLowerCase(Locale.ROOT);
        if (!TIME_PERIODS.contains(time)) throw new IllegalArgumentException("Unsupported Reddit timePeriod: " + time);
        int postLimit = boundedInt(metadata.get("postLimit"), 100, 1, 1000);
        int commentLimit = boundedInt(metadata.get("commentLimit"), 50, 0, 500);
        int commentDepth = boundedInt(metadata.get("commentDepth"), 3, 1, 10);
        int minScore = boundedInt(metadata.get("minScore"), 0, 0, Integer.MAX_VALUE);
        boolean includeComments = booleanValue(metadata.get("includeComments"), true);
        boolean includeNsfw = booleanValue(metadata.get("includeNsfw"), false);
        String searchQuery = text(metadata, "searchQuery").orElse(null);
        String userAgent = text(metadata, "userAgent").orElse(DEFAULT_USER_AGENT);

        List<Document> documents = new ArrayList<>();
        String after = null;
        report(progressCallback, 2, "Discovering r/" + subreddit + " posts");
        while (documents.size() < postLimit) {
            int limit = Math.min(100, postLimit - documents.size());
            JsonNode listing = getJson(listingUrl(
                    subreddit, sort, time, searchQuery, limit, after), tokenAccess, userAgent);
            JsonNode children = listing.path("data").path("children");
            if (!children.isArray() || children.isEmpty()) break;
            for (JsonNode child : children) {
                JsonNode post = child.path("data");
                if (post.path("score").asInt(0) < minScore) continue;
                if (post.path("over_18").asBoolean(false) && !includeNsfw) continue;
                List<Comment> comments = List.of();
                if (includeComments && commentLimit != 0) {
                    try {
                        comments = fetchComments(post.path("id").asText(), tokenAccess, userAgent,
                                commentDepth, commentLimit);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    } catch (Exception commentFailure) {
                        report(progressCallback,
                                Math.min(95, 5 + (int) (90.0 * Math.max(1, documents.size()) / postLimit)),
                                "Skipped an unavailable Reddit comment thread: "
                                        + SourceCredentialRedactor.redact(commentFailure.getMessage()));
                    }
                }
                documents.add(toDocument(post, comments,
                        descriptor.getCollectionName(), descriptor.getSourceId()));
                report(progressCallback,
                        Math.min(95, 5 + (int) (90.0 * documents.size() / postLimit)),
                        "Loaded " + documents.size() + " Reddit post(s)");
                if (documents.size() >= postLimit) break;
            }
            after = listing.path("data").path("after").asText(null);
            if (after == null || after.isBlank()) break;
        }
        report(progressCallback, 100, "Loaded " + documents.size() + " Reddit post(s)");
        return List.copyOf(documents);
    }

    private List<Comment> fetchComments(
            String postId,
            TokenAccess tokenAccess,
            String userAgent,
            int depth,
            int limit) throws Exception {
        JsonNode response = getJson(apiBase + "/comments/" + encode(postId)
                + ".json?raw_json=1&sort=top&depth=" + depth + "&limit=" + limit,
                tokenAccess, userAgent);
        if (!response.isArray() || response.size() < 2) return List.of();
        List<Comment> comments = new ArrayList<>();
        collectComments(response.get(1).path("data").path("children"), comments, limit);
        return List.copyOf(comments);
    }

    private static void collectComments(JsonNode children, List<Comment> comments, int limit) {
        if (!children.isArray() || comments.size() >= limit) return;
        for (JsonNode child : children) {
            if (comments.size() >= limit) return;
            if (!"t1".equals(child.path("kind").asText())) continue;
            JsonNode data = child.path("data");
            String body = data.path("body").asText("").trim();
            if (!body.isBlank()) {
                comments.add(new Comment(
                        data.path("id").asText(), data.path("author").asText("[deleted]"),
                        body, data.path("score").asInt(0)));
            }
            JsonNode replies = data.path("replies");
            if (replies.isObject()) {
                collectComments(replies.path("data").path("children"), comments, limit);
            }
        }
    }

    private Document toDocument(
            JsonNode post,
            List<Comment> comments,
            String collectionName,
            String sourceId) {
        String id = post.path("id").asText();
        String title = post.path("title").asText("Reddit post");
        String selfText = post.path("selftext").asText("").trim();
        StringBuilder content = new StringBuilder("# ").append(title);
        if (!selfText.isBlank()) content.append("\n\n").append(selfText);
        String externalUrl = post.path("url_overridden_by_dest").asText(
                post.path("url").asText(""));
        if (selfText.isBlank() && !externalUrl.isBlank()) {
            content.append("\n\nLink: ").append(externalUrl);
        }
        if (!comments.isEmpty()) {
            content.append("\n\n## Comments");
            for (Comment comment : comments) {
                content.append("\n\n").append(comment.author()).append(" (")
                        .append(comment.score()).append("): ").append(comment.body());
            }
        }
        String permalink = post.path("permalink").asText("");
        String webUrl = permalink.startsWith("http") ? permalink
                : "https://www.reddit.com" + permalink;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GraphConstants.META_SOURCE, "reddit:" + id);
        metadata.put(GraphConstants.META_SOURCE_PATH, webUrl);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "reddit");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "reddit_post");
        metadata.put(GraphConstants.META_LOADER, getName());
        metadata.put(GraphConstants.META_FILE_NAME, title);
        metadata.put("reddit.postId", id);
        putText(metadata, "reddit.name", post.path("name"));
        putText(metadata, "reddit.subreddit", post.path("subreddit"));
        metadata.put("reddit.author", post.path("author").asText("[deleted]"));
        metadata.put("reddit.score", post.path("score").asInt(0));
        metadata.put("reddit.numComments", post.path("num_comments").asInt(0));
        metadata.put("reddit.commentsLoaded", comments.size());
        metadata.put("reddit.nsfw", post.path("over_18").asBoolean(false));
        metadata.put("reddit.createdAt", Instant.ofEpochSecond(
                post.path("created_utc").asLong(0)).toString());
        metadata.put("reddit.webUrl", webUrl);
        metadata.put("reddit.externalUrl", externalUrl);
        if (collectionName != null) metadata.put("collection_name", collectionName);
        if (sourceId != null) metadata.put(GraphConstants.META_SOURCE_ID, sourceId);
        return new Document(content.toString(), metadata);
    }

    private static void putText(Map<String, Object> metadata, String key, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return;
        String text = value.asText("").trim();
        if (!text.isEmpty()) metadata.put(key, text);
    }

    private JsonNode getJson(String url, TokenAccess tokenAccess, String userAgent) throws Exception {
        IOException lastFailure = null;
        boolean authenticationRetried = false;
        for (int attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt++) {
            String accessToken = tokenAccess.token();
            if (accessToken == null || accessToken.isBlank()) {
                throw new IOException("Reddit OAuth connection is no longer usable");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("User-Agent", userAgent)
                    .GET().build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException transportFailure) {
                lastFailure = transportFailure;
                if (attempt == MAX_REQUEST_ATTEMPTS) throw transportFailure;
                sleepBeforeRetry(null, attempt);
                continue;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return mapper.readTree(response.body());
            }
            if (response.statusCode() == 401 && tokenAccess.managed()
                    && !authenticationRetried && attempt < MAX_REQUEST_ATTEMPTS) {
                authenticationRetried = true;
                tokenAccess.forceRefresh();
                continue;
            }
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            lastFailure = new IOException("Reddit API request failed with HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
            if (!retryable || attempt == MAX_REQUEST_ATTEMPTS) throw lastFailure;
            sleepBeforeRetry(response, attempt);
        }
        throw lastFailure == null ? new IOException("Reddit API request failed") : lastFailure;
    }

    private static void sleepBeforeRetry(HttpResponse<?> response, int attempt) throws InterruptedException {
        long delayMillis = Math.min(30_000L, 500L << Math.max(0, attempt - 1));
        if (response != null) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfter != null) {
                try {
                    delayMillis = Math.min(30_000L, Math.max(0L, Long.parseLong(retryAfter.trim()) * 1_000L));
                } catch (NumberFormatException ignored) {
                    // Reddit normally emits seconds; use bounded exponential backoff for other forms.
                }
            }
        }
        if (delayMillis > 0) Thread.sleep(delayMillis);
    }

    private String listingUrl(
            String subreddit,
            String sort,
            String time,
            String searchQuery,
            int limit,
            String after) {
        StringBuilder url = new StringBuilder(apiBase).append("/r/").append(encode(subreddit));
        if (searchQuery != null && !searchQuery.isBlank()) {
            url.append("/search.json?q=").append(encode(searchQuery))
                    .append("&restrict_sr=1&sort=").append(encode(sort));
        } else {
            url.append('/').append(encode(sort)).append(".json?");
        }
        if (url.charAt(url.length() - 1) != '?' && url.indexOf("?") >= 0) url.append('&');
        url.append("raw_json=1&limit=").append(limit).append("&t=").append(encode(time));
        if (after != null && !after.isBlank()) url.append("&after=").append(encode(after));
        return url.toString();
    }

    static String normalizeSubreddit(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Subreddit is required");
        String candidate = value.trim();
        if (candidate.regionMatches(true, 0, "http://", 0, 7)
                || candidate.regionMatches(true, 0, "https://", 0, 8)) {
            URI uri = URI.create(candidate);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("reddit.com".equals(host) || "www.reddit.com".equals(host))) {
                throw new IllegalArgumentException("Subreddit URL must use reddit.com");
            }
            String path = uri.getPath();
            int marker = path == null ? -1 : path.toLowerCase(Locale.ROOT).indexOf("/r/");
            candidate = marker >= 0 ? path.substring(marker + 3) : path;
        }
        candidate = candidate.replaceFirst("(?i)^/?r/", "").replaceAll("/.*$", "");
        if (!candidate.matches("[A-Za-z0-9_]{2,21}")) {
            throw new IllegalArgumentException("Invalid subreddit name");
        }
        return candidate;
    }

    private static Optional<String> text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null || value.toString().isBlank()
                ? Optional.empty() : Optional.of(value.toString().trim());
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String safe = SourceCredentialRedactor.redact(value);
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    private static void report(Consumer<LoaderProgress> callback, int percent, String message) {
        if (callback != null) callback.accept(new LoaderProgress(
                "reddit", percent, null, message, Map.of()));
    }

    private record Comment(String id, String author, String body, int score) {
    }

    private record TokenAccess(String fixedToken, OAuthConnectionService oauthService) {
        static TokenAccess fixed(String token) {
            return new TokenAccess(token, null);
        }

        static TokenAccess managed(OAuthConnectionService oauthService) {
            return new TokenAccess(null, oauthService);
        }

        boolean managed() {
            return oauthService != null;
        }

        String token() {
            return managed() ? oauthService.getValidAccessToken("reddit") : fixedToken;
        }

        void forceRefresh() {
            if (managed()) oauthService.refreshConnection("reddit");
        }
    }
}
