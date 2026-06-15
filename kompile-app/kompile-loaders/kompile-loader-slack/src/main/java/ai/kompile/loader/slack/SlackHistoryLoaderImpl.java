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

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
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

    private final Slack slack = Slack.getInstance();
    private final Map<String, String> userCache = new ConcurrentHashMap<>();

    // Runtime configurable defaults (set via UI/API)
    private String slackToken = "";
    private boolean includeThreads = true;
    private int defaultDays = 30;

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

        MethodsClient client = slack.methods(token);

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
        return slackToken;
    }

    private void loadAllChannelsHistory(MethodsClient client, DocumentSourceDescriptor sourceDescriptor,
                                         List<Document> documents) throws IOException, SlackApiException {
        ConversationsListResponse listResponse = client.conversationsList(
                ConversationsListRequest.builder()
                        .types(Arrays.asList(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                        .limit(1000)
                        .build());

        if (!listResponse.isOk()) {
            logger.error("Failed to list Slack channels: {}", listResponse.getError());
            return;
        }

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
        if (metadata.containsKey("oldest")) {
            return (String) metadata.get("oldest");
        }

        if (metadata.containsKey("startDate")) {
            return parseDate((String) metadata.get("startDate"));
        }

        if (metadata.containsKey("daysBack")) {
            int days = ((Number) metadata.get("daysBack")).intValue();
            return String.valueOf(Instant.now().minus(Duration.ofDays(days)).getEpochSecond());
        }

        // Default to configured days back
        return String.valueOf(Instant.now().minus(Duration.ofDays(defaultDays)).getEpochSecond());
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
        int totalMessages = 0;
        int maxMessages = getMaxMessages(sourceDescriptor);

        do {
            ConversationsHistoryRequest.ConversationsHistoryRequestBuilder requestBuilder =
                    ConversationsHistoryRequest.builder()
                            .channel(channelId)
                            .oldest(oldest)
                            .latest(latest)
                            .inclusive(true)
                            .limit(100);

            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }

            ConversationsHistoryResponse response = client.conversationsHistory(requestBuilder.build());

            if (!response.isOk()) {
                logger.error("Failed to fetch Slack history for channel {}: {}", channelName, response.getError());
                break;
            }

            List<Message> messages = response.getMessages();
            if (messages == null || messages.isEmpty()) {
                break;
            }

            for (Message message : messages) {
                if (maxMessages > 0 && totalMessages >= maxMessages) {
                    break;
                }

                Document doc = convertMessageToDocument(client, message, channelId, channelName, sourceDescriptor, false);
                documents.add(doc);
                totalMessages++;
            }

            if (maxMessages > 0 && totalMessages >= maxMessages) {
                break;
            }

            // Get next page cursor
            cursor = response.getResponseMetadata() != null ?
                    response.getResponseMetadata().getNextCursor() : null;

        } while (cursor != null && !cursor.isEmpty());

        logger.info("Loaded {} messages from channel {}", totalMessages, channelName);
    }

    private void loadThreadReplies(MethodsClient client, String channelId, String channelName,
                                    String oldest, String latest,
                                    DocumentSourceDescriptor sourceDescriptor,
                                    List<Document> documents) throws IOException, SlackApiException {
        // First, get all parent messages that have threads
        Set<String> threadTimestamps = new HashSet<>();

        for (Document doc : new ArrayList<>(documents)) {
            Object replyCount = doc.getMetadata().get("reply_count");
            Object threadTs = doc.getMetadata().get("message_ts");

            if (replyCount != null && ((Number) replyCount).intValue() > 0 && threadTs != null) {
                threadTimestamps.add((String) threadTs);
            }
        }

        // Load replies for each thread
        for (String threadTs : threadTimestamps) {
            loadThreadRepliesForMessage(client, channelId, channelName, threadTs, sourceDescriptor, documents);
        }
    }

    private void loadThreadRepliesForMessage(MethodsClient client, String channelId, String channelName,
                                              String threadTs, DocumentSourceDescriptor sourceDescriptor,
                                              List<Document> documents) throws IOException, SlackApiException {
        String cursor = null;

        do {
            ConversationsRepliesRequest.ConversationsRepliesRequestBuilder requestBuilder =
                    ConversationsRepliesRequest.builder()
                            .channel(channelId)
                            .ts(threadTs)
                            .limit(100);

            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }

            ConversationsRepliesResponse response = client.conversationsReplies(requestBuilder.build());

            if (!response.isOk()) {
                logger.warn("Failed to fetch thread replies for {}: {}", threadTs, response.getError());
                break;
            }

            List<Message> messages = response.getMessages();
            if (messages == null || messages.isEmpty()) {
                break;
            }

            // Skip the first message (it's the parent) and add replies
            for (int i = 1; i < messages.size(); i++) {
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
            } else if (max instanceof String) {
                return Integer.parseInt((String) max);
            }
        }
        return 0; // 0 means unlimited
    }

    private String resolveChannelId(MethodsClient client, String channelIdOrName) throws IOException, SlackApiException {
        if (channelIdOrName.startsWith("C") || channelIdOrName.startsWith("G") || channelIdOrName.startsWith("D")) {
            return channelIdOrName;
        }

        String searchName = channelIdOrName.startsWith("#") ? channelIdOrName.substring(1) : channelIdOrName;

        ConversationsListResponse listResponse = client.conversationsList(
                ConversationsListRequest.builder()
                        .types(Arrays.asList(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                        .limit(1000)
                        .build());

        if (listResponse.isOk()) {
            for (Conversation channel : listResponse.getChannels()) {
                if (channel.getName().equalsIgnoreCase(searchName)) {
                    return channel.getId();
                }
            }
        }

        throw new IllegalArgumentException("Channel not found: " + channelIdOrName);
    }

    private String getChannelName(MethodsClient client, String channelId) throws IOException, SlackApiException {
        ConversationsInfoResponse infoResponse = client.conversationsInfo(
                ConversationsInfoRequest.builder()
                        .channel(channelId)
                        .build());

        if (infoResponse.isOk() && infoResponse.getChannel() != null) {
            return infoResponse.getChannel().getName();
        }

        return channelId;
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

    private void addMetadata(Document document, Message message, String channelId,
                             String channelName, String userName,
                             DocumentSourceDescriptor sourceDescriptor, boolean isThreadReply) {
        Map<String, Object> metadata = document.getMetadata();

        metadata.put("source", "slack_history");
        metadata.put("source_type", "SLACK_HISTORY");
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
