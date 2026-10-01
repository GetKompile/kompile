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

package ai.kompile.loader.slack;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackApiTextResponse;
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsInfoRequest;
import com.slack.api.methods.request.conversations.ConversationsListRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import com.slack.api.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document loader for ingesting Slack channel messages.
 * Supports loading messages from public channels, private channels, and direct messages.
 *
 * <p>Configuration is per-request via metadata in the source descriptor:</p>
 * <ul>
 *   <li>slackToken - Slack API token</li>
 *   <li>limit - Maximum number of messages to load</li>
 * </ul>
 */
@Component
public class SlackLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(SlackLoaderImpl.class);

    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    /** Like the Discord loader's contract: never let a Retry-After header stall a crawl for minutes/hours. */
    private static final long MAX_RETRY_AFTER_MILLIS = 60_000L;

    private final Slack slack = Slack.getInstance();
    private final Map<String, String> userCache = new ConcurrentHashMap<>();

    // Runtime configurable defaults (set via UI/API)
    private String slackToken = "";
    private int defaultLimit = 100;
    private boolean includeThreads = true;
    private final OAuthConnectionService oauthService;

    private RetrySleeper retrySleeper = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private MethodsClient clientOverrideForTesting;

    public SlackLoaderImpl() { this(null); }

    @Autowired
    public SlackLoaderImpl(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    /**
     * Test-only hook to replace the backoff sleep with a fast/no-op implementation.
     */
    void setRetrySleeperForTesting(RetrySleeper retrySleeper) {
        this.retrySleeper = retrySleeper;
    }

    /**
     * Test-only hook to bypass Slack token-based client construction and inject a mock
     * {@link MethodsClient} directly.
     */
    void setClientForTesting(MethodsClient client) {
        this.clientOverrideForTesting = client;
    }

    @FunctionalInterface
    interface SlackCall<T> {
        T call() throws IOException, SlackApiException;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Invokes a Slack API call, retrying with backoff when Slack responds with HTTP 429
     * (rate limited). Honors the {@code Retry-After} header when present. Any other
     * {@link SlackApiException}, or a 429 that persists past {@link #MAX_RATE_LIMIT_RETRIES}
     * attempts, is rethrown to the caller.
     */
    private <T> T callWithRetry(SlackCall<T> call) throws IOException, SlackApiException {
        int attempt = 0;
        while (true) {
            try {
                return call.call();
            } catch (SlackApiException e) {
                boolean rateLimited = e.getResponse() != null && e.getResponse().code() == 429;
                attempt++;
                if (!rateLimited || attempt >= MAX_RATE_LIMIT_RETRIES) {
                    throw e;
                }
                sleepBeforeRetry(e, attempt);
            }
        }
    }

    private void sleepBeforeRetry(SlackApiException e, int attempt) throws InterruptedIOException {
        long delayMillis = 1000L * attempt;
        String retryAfter = e.getResponse() != null ? e.getResponse().header("Retry-After") : null;
        if (retryAfter != null) {
            try {
                long parsedMillis = Long.parseLong(retryAfter.trim()) * 1000L;
                // A negative Retry-After is garbage; keep the default backoff computed above.
                // A huge one must not stall the crawl for minutes/hours, so cap it.
                if (parsedMillis >= 0) {
                    delayMillis = Math.min(parsedMillis, MAX_RETRY_AFTER_MILLIS);
                }
            } catch (NumberFormatException ignored) {
                // keep the default backoff computed above
            }
        }
        try {
            retrySleeper.sleep(delayMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to retry Slack rate limit");
        }
    }

    private IllegalStateException slackError(String apiMethod, SlackApiTextResponse response) {
        return new IllegalStateException(slackErrorMessage(apiMethod, response));
    }

    private String slackErrorMessage(String apiMethod, SlackApiTextResponse response) {
        String error = response.getError();
        return "Slack API " + apiMethod + " failed with error '" + error + "': " + slackErrorHint(error, response);
    }

    private String slackErrorHint(String error, SlackApiTextResponse response) {
        if (error == null) {
            return "no error code returned by Slack.";
        }
        switch (error) {
            case "not_in_channel":
                return "invite the Slack bot/app to this channel and retry.";
            case "channel_not_found":
                return "check the channel id/name and confirm the bot can see it.";
            case "missing_scope":
                String needed = response.getNeeded();
                return "the Slack app token needs additional scope(s): "
                        + (needed != null ? needed : "see Slack response for required scopes") + ".";
            case "invalid_auth":
            case "token_revoked":
            case "not_authed":
            case "account_inactive":
                return "the Slack credential is invalid or expired; reconnect the Slack integration.";
            default:
                return "see Slack API documentation for error code '" + error + "'.";
        }
    }

    @Override
    public String getName() {
        return "Slack Channel Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.SLACK;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.SLACK) {
            throw new IllegalArgumentException("SlackLoader only supports SLACK source type.");
        }

        String token = getToken(sourceDescriptor);
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Slack API token is required. Set kompile.slack.token or provide in metadata.");
        }

        String channelId = sourceDescriptor.getPathOrUrl();
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("Channel ID or name is required in pathOrUrl.");
        }

        MethodsClient client = clientOverrideForTesting != null ? clientOverrideForTesting : slack.methods(token);

        // Resolve channel ID if name was provided
        channelId = resolveChannelId(client, channelId);

        // Get channel info
        String channelName = getChannelName(client, channelId);

        // Get message limit from metadata or use default
        int limit = getLimit(sourceDescriptor);

        // Load messages from channel
        List<Document> documents = new ArrayList<>();
        loadMessages(client, channelId, channelName, limit, documents, sourceDescriptor);

        return documents;
    }

    private String getToken(DocumentSourceDescriptor sourceDescriptor) {
        // Check metadata first
        if (sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("slackToken")) {
            return (String) sourceDescriptor.getMetadata().get("slackToken");
        }
        if (slackToken != null && !slackToken.isBlank()) return slackToken;
        return oauthService == null ? null : oauthService.getValidAccessToken("slack");
    }

    private int getLimit(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("limit")) {
            Object limitObj = sourceDescriptor.getMetadata().get("limit");
            Integer parsed = null;
            if (limitObj instanceof Number) {
                parsed = ((Number) limitObj).intValue();
            } else if (limitObj instanceof String && !((String) limitObj).isBlank()) {
                parsed = Integer.parseInt(((String) limitObj).trim());
            }
            // <= 0 means "no explicit cap" - fall back to the loader's own default.
            if (parsed != null && parsed > 0) {
                return parsed;
            }
        }
        return defaultLimit;
    }

    private String resolveChannelId(MethodsClient client, String channelIdOrName) throws IOException, SlackApiException {
        // If it looks like a channel ID, return it
        if (channelIdOrName.startsWith("C") || channelIdOrName.startsWith("G") || channelIdOrName.startsWith("D")) {
            return channelIdOrName;
        }

        // Otherwise, search for channel by name, following cursor pagination until found or exhausted.
        // The request already asks for both public and private channels the bot is a member of.
        String searchName = channelIdOrName.startsWith("#") ? channelIdOrName.substring(1) : channelIdOrName;

        String cursor = null;
        do {
            ConversationsListRequest.ConversationsListRequestBuilder requestBuilder =
                    ConversationsListRequest.builder()
                            .types(Arrays.asList(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                            .limit(1000);
            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }

            ConversationsListRequest request = requestBuilder.build();
            ConversationsListResponse listResponse = callWithRetry(() -> client.conversationsList(request));

            if (!listResponse.isOk()) {
                throw slackError("conversations.list", listResponse);
            }

            if (listResponse.getChannels() != null) {
                for (Conversation channel : listResponse.getChannels()) {
                    if (channel.getName() != null && channel.getName().equalsIgnoreCase(searchName)) {
                        return channel.getId();
                    }
                }
            }

            cursor = listResponse.getResponseMetadata() != null ? listResponse.getResponseMetadata().getNextCursor() : null;
        } while (cursor != null && !cursor.isEmpty());

        throw new IllegalArgumentException("Channel not found: " + channelIdOrName);
    }

    private String getChannelName(MethodsClient client, String channelId) throws IOException, SlackApiException {
        ConversationsInfoRequest request = ConversationsInfoRequest.builder()
                .channel(channelId)
                .build();
        ConversationsInfoResponse infoResponse = callWithRetry(() -> client.conversationsInfo(request));

        if (!infoResponse.isOk()) {
            throw slackError("conversations.info", infoResponse);
        }

        return infoResponse.getChannel() != null ? infoResponse.getChannel().getName() : channelId;
    }

    private void loadMessages(MethodsClient client, String channelId, String channelName,
                              int limit, List<Document> documents, DocumentSourceDescriptor sourceDescriptor)
            throws IOException, SlackApiException {

        String oldest = resolveEffectiveOldest(sourceDescriptor);
        String latest = sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("latest")
                ? String.valueOf(sourceDescriptor.getMetadata().get("latest")) : null;

        String cursor = null;

        // Total cap across messages AND replies - "documents" is the single shared accumulator,
        // so comparing its size against "limit" enforces the cap while fetching (newest first).
        while (documents.size() < limit) {
            int batchSize = Math.min(limit - documents.size(), 100);

            ConversationsHistoryRequest.ConversationsHistoryRequestBuilder requestBuilder =
                    ConversationsHistoryRequest.builder()
                            .channel(channelId)
                            .limit(batchSize);

            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }
            if (oldest != null) {
                requestBuilder.oldest(oldest);
            }
            if (latest != null) {
                requestBuilder.latest(latest);
            }

            ConversationsHistoryRequest request = requestBuilder.build();
            ConversationsHistoryResponse historyResponse = callWithRetry(() -> client.conversationsHistory(request));

            if (!historyResponse.isOk()) {
                throw slackError("conversations.history", historyResponse);
            }

            List<Message> messages = historyResponse.getMessages();
            if (messages == null || messages.isEmpty()) {
                break;
            }

            for (Message message : messages) {
                if (documents.size() >= limit) {
                    break;
                }
                documents.add(convertMessageToDocument(client, message, channelId, channelName, sourceDescriptor));
            }

            // Check for pagination
            if (documents.size() < limit
                    && historyResponse.getResponseMetadata() != null
                    && historyResponse.getResponseMetadata().getNextCursor() != null
                    && !historyResponse.getResponseMetadata().getNextCursor().isEmpty()) {
                cursor = historyResponse.getResponseMetadata().getNextCursor();
            } else {
                break;
            }
        }

        if (resolveIncludeThreads(sourceDescriptor) && documents.size() < limit) {
            loadRepliesForMessages(client, channelId, channelName, documents, limit, sourceDescriptor);
        }
    }

    /**
     * Fetches conversations.replies for every already-loaded parent message with reply_count &gt; 0,
     * counting each reply toward the same total cap as the primary messages. Parent candidates are
     * snapshotted before fetching starts so replies appended during the loop are not re-scanned.
     */
    private void loadRepliesForMessages(MethodsClient client, String channelId, String channelName,
                                         List<Document> documents, int limit,
                                         DocumentSourceDescriptor sourceDescriptor) throws IOException, SlackApiException {
        List<String> parentTimestamps = new ArrayList<>();
        for (Document doc : new ArrayList<>(documents)) {
            Object replyCount = doc.getMetadata().get("reply_count");
            Object messageTs = doc.getMetadata().get("message_ts");
            if (replyCount instanceof Number && ((Number) replyCount).intValue() > 0 && messageTs != null) {
                parentTimestamps.add((String) messageTs);
            }
        }

        for (String parentTs : parentTimestamps) {
            if (documents.size() >= limit) {
                break;
            }
            loadRepliesForMessage(client, channelId, channelName, parentTs, documents, limit, sourceDescriptor);
        }
    }

    private void loadRepliesForMessage(MethodsClient client, String channelId, String channelName,
                                        String threadTs, List<Document> documents, int limit,
                                        DocumentSourceDescriptor sourceDescriptor) throws IOException, SlackApiException {
        String cursor = null;

        do {
            if (documents.size() >= limit) {
                return;
            }

            ConversationsRepliesRequest.ConversationsRepliesRequestBuilder requestBuilder =
                    ConversationsRepliesRequest.builder()
                            .channel(channelId)
                            .ts(threadTs)
                            .limit(100);
            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }

            ConversationsRepliesRequest request = requestBuilder.build();
            ConversationsRepliesResponse response = callWithRetry(() -> client.conversationsReplies(request));

            if (!response.isOk()) {
                logger.warn("Failed to fetch thread replies for {}/{}: {}",
                        channelId, threadTs, slackErrorMessage("conversations.replies", response));
                return;
            }

            List<Message> messages = response.getMessages();
            if (messages == null || messages.size() <= 1) {
                // Only the parent echo (or nothing) came back - no replies to add.
                return;
            }

            // Element 0 is the parent message itself; replies start at index 1.
            for (int i = 1; i < messages.size(); i++) {
                if (documents.size() >= limit) {
                    return;
                }
                Message reply = messages.get(i);
                documents.add(convertMessageToDocument(client, reply, channelId, channelName, sourceDescriptor));
            }

            cursor = response.getResponseMetadata() != null ? response.getResponseMetadata().getNextCursor() : null;
        } while (cursor != null && !cursor.isEmpty());
    }

    private boolean resolveIncludeThreads(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("includeThreads")) {
            return Boolean.TRUE.equals(sourceDescriptor.getMetadata().get("includeThreads"));
        }
        return includeThreads;
    }

    /**
     * Resolves the effective "oldest" bound: the later of an explicit "oldest" metadata value
     * and the "since" ISO-8601 instant (converted to epoch seconds), per the shared SINCE contract.
     */
    private String resolveEffectiveOldest(DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = sourceDescriptor.getMetadata();
        if (metadata == null) {
            return null;
        }

        String explicitOldest = metadata.containsKey("oldest") ? String.valueOf(metadata.get("oldest")) : null;
        String sinceOldest = sinceToEpochSeconds(metadata.get("since"));

        if (explicitOldest == null) {
            return sinceOldest;
        }
        if (sinceOldest == null) {
            return explicitOldest;
        }
        try {
            return Double.parseDouble(sinceOldest) > Double.parseDouble(explicitOldest) ? sinceOldest : explicitOldest;
        } catch (NumberFormatException e) {
            return explicitOldest;
        }
    }

    private String sinceToEpochSeconds(Object sinceValue) {
        if (sinceValue == null) {
            return null;
        }
        String since = String.valueOf(sinceValue).trim();
        if (since.isEmpty()) {
            return null;
        }
        try {
            return String.valueOf(Instant.parse(since).getEpochSecond());
        } catch (DateTimeParseException e) {
            try {
                return String.valueOf(OffsetDateTime.parse(since).toEpochSecond());
            } catch (DateTimeParseException e2) {
                logger.warn("Could not parse 'since' timestamp '{}': expected ISO-8601", since);
                return null;
            }
        }
    }

    private Document convertMessageToDocument(MethodsClient client, Message message,
                                               String channelId, String channelName,
                                               DocumentSourceDescriptor sourceDescriptor) {
        StringBuilder content = new StringBuilder();

        // Get user name
        String userName = resolveUserName(client, message.getUser());

        // Format timestamp
        String timestamp = formatTimestamp(message.getTs());

        boolean isThreadReply = message.getThreadTs() != null && !message.getThreadTs().equals(message.getTs());
        if (isThreadReply) {
            content.append("  [Reply] ");
        }

        // Build message content
        content.append("[").append(timestamp).append("] ");
        content.append(userName).append(": ");
        content.append(message.getText());

        // Handle thread replies if present
        if (!isThreadReply && message.getReplyCount() != null && message.getReplyCount() > 0) {
            content.append("\n  [Thread: ").append(message.getReplyCount()).append(" replies]");
        }

        Document document = new Document(content.toString());

        // Add metadata
        addMetadata(document, message, channelId, channelName, userName, sourceDescriptor);

        return document;
    }

    private String resolveUserName(MethodsClient client, String userId) {
        if (userId == null) {
            return "Unknown";
        }

        return userCache.computeIfAbsent(userId, id -> {
            try {
                UsersInfoResponse userInfo = client.usersInfo(
                        UsersInfoRequest.builder().user(id).build());
                if (userInfo.isOk() && userInfo.getUser() != null) {
                    User user = userInfo.getUser();
                    return user.getRealName() != null ? user.getRealName() : user.getName();
                }
            } catch (Exception e) {
                logger.debug("Failed to resolve user name for {}: {}", id, e.getMessage());
            }
            return id;
        });
    }

    private String formatTimestamp(String ts) {
        if (ts == null) {
            return "";
        }
        try {
            double timestamp = Double.parseDouble(ts);
            Instant instant = Instant.ofEpochSecond((long) timestamp);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(instant);
        } catch (NumberFormatException e) {
            return ts;
        }
    }

    void addMetadata(Document document, Message message, String channelId,
                     String channelName, String userName, DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = document.getMetadata();

        // A reply's thread_ts points at its parent's ts and differs from its own ts;
        // a thread parent's thread_ts equals its own ts; a message with no thread has no thread_ts.
        boolean isThreadReply = message.getThreadTs() != null && !message.getThreadTs().equals(message.getTs());

        metadata.put("source", "slack");
        metadata.put("source_type", "SLACK");
        metadata.put(GraphConstants.META_SOURCE_PATH, "slack://channel/" + channelId + "/message/" + message.getTs());
        metadata.put("loader", getName());
        metadata.put("channel_id", channelId);
        metadata.put("channel_name", channelName);
        metadata.put("message_ts", message.getTs());
        metadata.put("user_id", message.getUser());
        metadata.put("user_name", userName);
        metadata.put("is_thread_reply", isThreadReply);

        if (message.getType() != null) {
            metadata.put("message_type", message.getType());
        }

        if (message.getSubtype() != null) {
            metadata.put("message_subtype", message.getSubtype());
        }

        if (message.getReplyCount() != null) {
            metadata.put("reply_count", message.getReplyCount());
        }

        if (message.getThreadTs() != null) {
            metadata.put("thread_ts", message.getThreadTs());
            if (isThreadReply) {
                metadata.put("thread_parent_source_path", "slack://channel/" + channelId + "/message/" + message.getThreadTs());
            }
        }

        // Include source descriptor metadata
        if (sourceDescriptor.getCollectionName() != null) {
            metadata.put("collection_name", sourceDescriptor.getCollectionName());
        }

        if (sourceDescriptor.getSourceId() != null) {
            metadata.put("source_id", sourceDescriptor.getSourceId());
        }
    }

    // Configuration methods for UI/API

    /**
     * Sets the default Slack API token. Can be overridden per-request via metadata.
     */
    public void setSlackToken(String slackToken) {
        this.slackToken = slackToken;
    }

    /**
     * Gets the current default Slack API token.
     */
    public String getSlackToken() {
        return slackToken;
    }

    /**
     * Sets the default message limit.
     */
    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    /**
     * Gets the default message limit.
     */
    public int getDefaultLimit() {
        return defaultLimit;
    }

    /**
     * Sets whether to include thread replies by default.
     */
    public void setIncludeThreads(boolean includeThreads) {
        this.includeThreads = includeThreads;
    }

    /**
     * Gets whether thread replies are included by default.
     */
    public boolean isIncludeThreads() {
        return includeThreads;
    }
}
