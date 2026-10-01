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
import com.slack.api.methods.request.conversations.*;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.*;
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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document loader for ingesting historical Slack messages.
 * Supports loading message history with date ranges, including threads.
 *
 * <p>Configuration is per-request via metadata in the source descriptor:</p>
 * <ul>
 *   <li>slackToken - Slack API token</li>
 *   <li>includeThreads - Whether to include thread replies (default: true)</li>
 *   <li>daysBack - Number of days of history to load (default: 30)</li>
 * </ul>
 */
@Component
public class SlackHistoryLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(SlackHistoryLoaderImpl.class);

    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    /** Like the Discord loader's contract: never let a Retry-After header stall a crawl for minutes/hours. */
    private static final long MAX_RETRY_AFTER_MILLIS = 60_000L;

    private final Slack slack = Slack.getInstance();
    private final Map<String, String> userCache = new ConcurrentHashMap<>();

    // Runtime configurable defaults (set via UI/API)
    private String slackToken = "";
    private boolean includeThreads = true;
    private int defaultDays = 30;
    private final OAuthConnectionService oauthService;

    private RetrySleeper retrySleeper = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private MethodsClient clientOverrideForTesting;

    public SlackHistoryLoaderImpl() { this(null); }

    @Autowired
    public SlackHistoryLoaderImpl(
            @Autowired(required = false) OAuthConnectionService oauthService) {
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
        return "Slack History Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.SLACK_HISTORY;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.SLACK_HISTORY) {
            throw new IllegalArgumentException("SlackHistoryLoader only supports SLACK_HISTORY source type.");
        }

        String token = getToken(sourceDescriptor);
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Slack API token is required. Set kompile.slack.token or provide in metadata.");
        }

        MethodsClient client = clientOverrideForTesting != null ? clientOverrideForTesting : slack.methods(token);

        // Get configuration from metadata
        Map<String, Object> metadata = sourceDescriptor.getMetadata() != null ? sourceDescriptor.getMetadata() : new HashMap<>();
        boolean loadAllChannels = Boolean.TRUE.equals(metadata.get("loadAllChannels"));

        List<Document> documents = new ArrayList<>();

        if (loadAllChannels) {
            // Load from all accessible channels
            loadAllChannelsHistory(client, sourceDescriptor, documents);
        } else {
            // Load from specific channel(s)
            String channelInput = sourceDescriptor.getPathOrUrl();
            if (channelInput == null || channelInput.isEmpty()) {
                throw new IllegalArgumentException("Channel ID, name, or 'all' is required in pathOrUrl.");
            }

            // Support comma-separated channel list
            String[] channels = channelInput.split(",");
            for (String channel : channels) {
                String trimmedChannel = channel.trim();
                if (!trimmedChannel.isEmpty()) {
                    loadChannelHistory(client, trimmedChannel, sourceDescriptor, documents);
                }
            }
        }

        return documents;
    }

    private String getToken(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("slackToken")) {
            return (String) sourceDescriptor.getMetadata().get("slackToken");
        }
        if (slackToken != null && !slackToken.isBlank()) return slackToken;
        return oauthService == null ? null : oauthService.getValidAccessToken("slack");
    }

    private void loadAllChannelsHistory(MethodsClient client, DocumentSourceDescriptor sourceDescriptor,
                                         List<Document> documents) throws IOException, SlackApiException {
        // Follows response_metadata.next_cursor until the channel list is exhausted, so "all channels"
        // really means all of them, not just the first page. Includes private channels the bot is in.
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
                    if (channel.isMember()) {
                        try {
                            loadChannelHistory(client, channel.getId(), sourceDescriptor, documents);
                        } catch (Exception e) {
                            logger.warn("Failed to load history for channel {}: {}", channel.getName(), e.getMessage());
                        }
                    }
                }
            }

            cursor = listResponse.getResponseMetadata() != null ? listResponse.getResponseMetadata().getNextCursor() : null;
        } while (cursor != null && !cursor.isEmpty());
    }

    private void loadChannelHistory(MethodsClient client, String channelIdOrName,
                                     DocumentSourceDescriptor sourceDescriptor,
                                     List<Document> documents) throws IOException, SlackApiException {
        String channelId = resolveChannelId(client, channelIdOrName);
        String channelName = getChannelName(client, channelId);

        Map<String, Object> metadata = sourceDescriptor.getMetadata() != null ? sourceDescriptor.getMetadata() : new HashMap<>();

        // Calculate time range
        String oldest = calculateOldestTimestamp(metadata);
        String latest = calculateLatestTimestamp(metadata);

        logger.info("Loading Slack history for channel {} from {} to {}", channelName, oldest, latest);

        // Load main channel messages
        loadMessagesWithPagination(client, channelId, channelName, oldest, latest, sourceDescriptor, documents);

        // Load thread replies if enabled
        boolean loadThreads = metadata.containsKey("includeThreads") ?
                Boolean.TRUE.equals(metadata.get("includeThreads")) : includeThreads;

        if (loadThreads) {
            loadThreadReplies(client, channelId, channelName, oldest, latest, sourceDescriptor, documents);
        }
    }

    private String calculateOldestTimestamp(Map<String, Object> metadata) {
        String explicitOldest;
        if (metadata.containsKey("oldest")) {
            explicitOldest = String.valueOf(metadata.get("oldest"));
        } else if (metadata.containsKey("startDate")) {
            explicitOldest = parseDate((String) metadata.get("startDate"));
        } else if (metadata.containsKey("daysBack")) {
            int days = ((Number) metadata.get("daysBack")).intValue();
            explicitOldest = String.valueOf(Instant.now().minus(Duration.ofDays(days)).getEpochSecond());
        } else {
            explicitOldest = null;
        }

        // SINCE contract: when another start bound is explicitly configured, use the later of the
        // two. The default days-back fallback below is NOT a "configured" bound, so it must never
        // be allowed to win over an explicitly-provided "since" - it only applies when neither an
        // explicit bound nor "since" was given.
        String sinceOldest = sinceToEpochSeconds(metadata.get("since"));
        if (explicitOldest != null) {
            return laterOf(explicitOldest, sinceOldest);
        }
        if (sinceOldest != null) {
            return sinceOldest;
        }
        // Default to configured days back
        return String.valueOf(Instant.now().minus(Duration.ofDays(defaultDays)).getEpochSecond());
    }

    private static String laterOf(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        try {
            return Double.parseDouble(b) > Double.parseDouble(a) ? b : a;
        } catch (NumberFormatException e) {
            return a;
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

    private String calculateLatestTimestamp(Map<String, Object> metadata) {
        if (metadata.containsKey("latest")) {
            return (String) metadata.get("latest");
        }

        if (metadata.containsKey("endDate")) {
            return parseDate((String) metadata.get("endDate"));
        }

        // Default to now
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String parseDate(String dateString) {
        try {
            // Try parsing as ISO date
            LocalDate date = LocalDate.parse(dateString);
            return String.valueOf(date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond());
        } catch (DateTimeParseException e) {
            try {
                // Try parsing as ISO datetime
                LocalDateTime dateTime = LocalDateTime.parse(dateString);
                return String.valueOf(dateTime.atZone(ZoneId.systemDefault()).toEpochSecond());
            } catch (DateTimeParseException e2) {
                // Assume it's already a timestamp
                return dateString;
            }
        }
    }

    private void loadMessagesWithPagination(MethodsClient client, String channelId, String channelName,
                                             String oldest, String latest,
                                             DocumentSourceDescriptor sourceDescriptor,
                                             List<Document> documents) throws IOException, SlackApiException {
        String cursor = null;
        int channelMessages = 0;
        int maxMessages = getMaxMessages(sourceDescriptor);

        // maxMessages is a TOTAL cap across channels/threads/replies. "documents" is the single
        // accumulator shared across the whole load() call (including prior channels), so comparing
        // its size against maxMessages - rather than a call-local counter - enforces that total cap.
        do {
            if (maxMessages > 0 && documents.size() >= maxMessages) {
                break;
            }

            ConversationsHistoryRequest.ConversationsHistoryRequestBuilder requestBuilder =
                    ConversationsHistoryRequest.builder()
                            .channel(channelId)
                            .oldest(oldest)
                            .latest(latest)
                            .inclusive(true)
                            .limit(maxMessages > 0 ? Math.min(maxMessages - documents.size(), 100) : 100);

            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }

            ConversationsHistoryRequest request = requestBuilder.build();
            ConversationsHistoryResponse response = callWithRetry(() -> client.conversationsHistory(request));

            if (!response.isOk()) {
                throw slackError("conversations.history", response);
            }

            List<Message> messages = response.getMessages();
            if (messages == null || messages.isEmpty()) {
                break;
            }

            for (Message message : messages) {
                if (maxMessages > 0 && documents.size() >= maxMessages) {
                    break;
                }

                Document doc = convertMessageToDocument(client, message, channelId, channelName, sourceDescriptor, false);
                documents.add(doc);
                channelMessages++;
            }

            if (maxMessages > 0 && documents.size() >= maxMessages) {
                break;
            }

            // Get next page cursor
            cursor = response.getResponseMetadata() != null ?
                    response.getResponseMetadata().getNextCursor() : null;

        } while (cursor != null && !cursor.isEmpty());

        logger.info("Loaded {} messages from channel {}", channelMessages, channelName);
    }

    private void loadThreadReplies(MethodsClient client, String channelId, String channelName,
                                    String oldest, String latest,
                                    DocumentSourceDescriptor sourceDescriptor,
                                    List<Document> documents) throws IOException, SlackApiException {
        int maxMessages = getMaxMessages(sourceDescriptor);
        if (maxMessages > 0 && documents.size() >= maxMessages) {
            return;
        }

        // First, get all parent messages in THIS channel that have threads. Scoping to channelId
        // avoids re-scanning (and re-fetching replies for) parents from channels already processed
        // earlier in a multi-channel load(), since "documents" accumulates across all channels.
        Set<String> threadTimestamps = new LinkedHashSet<>();

        for (Document doc : new ArrayList<>(documents)) {
            Object replyCount = doc.getMetadata().get("reply_count");
            Object threadTs = doc.getMetadata().get("message_ts");
            Object docChannelId = doc.getMetadata().get("channel_id");

            if (replyCount != null && ((Number) replyCount).intValue() > 0 && threadTs != null
                    && channelId.equals(docChannelId)) {
                threadTimestamps.add((String) threadTs);
            }
        }

        // Load replies for each thread, stopping once the shared cap is reached
        for (String threadTs : threadTimestamps) {
            if (maxMessages > 0 && documents.size() >= maxMessages) {
                return;
            }
            loadThreadRepliesForMessage(client, channelId, channelName, threadTs, sourceDescriptor, documents, maxMessages);
        }
    }

    private void loadThreadRepliesForMessage(MethodsClient client, String channelId, String channelName,
                                              String threadTs, DocumentSourceDescriptor sourceDescriptor,
                                              List<Document> documents, int maxMessages) throws IOException, SlackApiException {
        String cursor = null;

        do {
            if (maxMessages > 0 && documents.size() >= maxMessages) {
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

            // Skip the first message (it's the parent) and add replies
            for (int i = 1; i < messages.size(); i++) {
                if (maxMessages > 0 && documents.size() >= maxMessages) {
                    return;
                }
                Message reply = messages.get(i);
                Document doc = convertMessageToDocument(client, reply, channelId, channelName, sourceDescriptor, true);
                documents.add(doc);
            }

            cursor = response.getResponseMetadata() != null ?
                    response.getResponseMetadata().getNextCursor() : null;

        } while (cursor != null && !cursor.isEmpty());
    }

    private int getMaxMessages(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("maxMessages")) {
            Object max = sourceDescriptor.getMetadata().get("maxMessages");
            if (max instanceof Number) {
                return ((Number) max).intValue();
            } else if (max instanceof String && !((String) max).isBlank()) {
                return Integer.parseInt(((String) max).trim());
            }
        }
        return 0; // <= 0 means unlimited (this loader's own default)
    }

    private String resolveChannelId(MethodsClient client, String channelIdOrName) throws IOException, SlackApiException {
        if (channelIdOrName.startsWith("C") || channelIdOrName.startsWith("G") || channelIdOrName.startsWith("D")) {
            return channelIdOrName;
        }

        // Follows cursor pagination until the channel is found or the list is exhausted. Includes
        // private channels the bot is a member of, since the request already asks for both types.
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

    private Document convertMessageToDocument(MethodsClient client, Message message,
                                               String channelId, String channelName,
                                               DocumentSourceDescriptor sourceDescriptor,
                                               boolean isThreadReply) {
        StringBuilder content = new StringBuilder();

        String userName = resolveUserName(client, message.getUser());
        String timestamp = formatTimestamp(message.getTs());

        if (isThreadReply) {
            content.append("  [Reply] ");
        }

        content.append("[").append(timestamp).append("] ");
        content.append(userName).append(": ");
        content.append(message.getText());

        if (!isThreadReply && message.getReplyCount() != null && message.getReplyCount() > 0) {
            content.append("\n  [Thread: ").append(message.getReplyCount()).append(" replies]");
        }

        Document document = new Document(content.toString());
        addMetadata(document, message, channelId, channelName, userName, sourceDescriptor, isThreadReply);

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
                     String channelName, String userName,
                     DocumentSourceDescriptor sourceDescriptor, boolean isThreadReply) {
        Map<String, Object> metadata = document.getMetadata();

        metadata.put("source", "slack_history");
        metadata.put("source_type", "SLACK_HISTORY");
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

    /**
     * Sets the default number of days of history to load.
     */
    public void setDefaultDays(int defaultDays) {
        this.defaultDays = defaultDays;
    }

    /**
     * Gets the default number of days of history to load.
     */
    public int getDefaultDays() {
        return defaultDays;
    }
}
