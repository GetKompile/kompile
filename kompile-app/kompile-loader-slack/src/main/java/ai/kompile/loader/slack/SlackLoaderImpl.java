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
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsInfoRequest;
import com.slack.api.methods.request.conversations.ConversationsListRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private final Slack slack = Slack.getInstance();
    private final Map<String, String> userCache = new ConcurrentHashMap<>();

    // Runtime configurable defaults (set via UI/API)
    private String slackToken = "";
    private int defaultLimit = 100;

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

        MethodsClient client = slack.methods(token);

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
        return slackToken;
    }

    private int getLimit(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getMetadata() != null && sourceDescriptor.getMetadata().containsKey("limit")) {
            Object limitObj = sourceDescriptor.getMetadata().get("limit");
            if (limitObj instanceof Number) {
                return ((Number) limitObj).intValue();
            } else if (limitObj instanceof String) {
                return Integer.parseInt((String) limitObj);
            }
        }
        return defaultLimit;
    }

    private String resolveChannelId(MethodsClient client, String channelIdOrName) throws IOException, SlackApiException {
        // If it looks like a channel ID, return it
        if (channelIdOrName.startsWith("C") || channelIdOrName.startsWith("G") || channelIdOrName.startsWith("D")) {
            return channelIdOrName;
        }

        // Otherwise, search for channel by name
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

    private void loadMessages(MethodsClient client, String channelId, String channelName,
                              int limit, List<Document> documents, DocumentSourceDescriptor sourceDescriptor)
            throws IOException, SlackApiException {

        String cursor = null;
        int remaining = limit;

        while (remaining > 0) {
            int batchSize = Math.min(remaining, 100);

            ConversationsHistoryRequest.ConversationsHistoryRequestBuilder requestBuilder =
                    ConversationsHistoryRequest.builder()
                            .channel(channelId)
                            .limit(batchSize);

            if (cursor != null) {
                requestBuilder.cursor(cursor);
            }

            // Add oldest/latest from metadata if provided
            if (sourceDescriptor.getMetadata() != null) {
                if (sourceDescriptor.getMetadata().containsKey("oldest")) {
                    requestBuilder.oldest((String) sourceDescriptor.getMetadata().get("oldest"));
                }
                if (sourceDescriptor.getMetadata().containsKey("latest")) {
                    requestBuilder.latest((String) sourceDescriptor.getMetadata().get("latest"));
                }
            }

            ConversationsHistoryResponse historyResponse = client.conversationsHistory(requestBuilder.build());

            if (!historyResponse.isOk()) {
                logger.error("Failed to fetch Slack history: {}", historyResponse.getError());
                break;
            }

            List<Message> messages = historyResponse.getMessages();
            if (messages == null || messages.isEmpty()) {
                break;
            }

            for (Message message : messages) {
                Document doc = convertMessageToDocument(client, message, channelId, channelName, sourceDescriptor);
                documents.add(doc);
                remaining--;

                if (remaining <= 0) {
                    break;
                }
            }

            // Check for pagination
            if (historyResponse.getResponseMetadata() != null &&
                    historyResponse.getResponseMetadata().getNextCursor() != null &&
                    !historyResponse.getResponseMetadata().getNextCursor().isEmpty()) {
                cursor = historyResponse.getResponseMetadata().getNextCursor();
            } else {
                break;
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

        // Build message content
        content.append("[").append(timestamp).append("] ");
        content.append(userName).append(": ");
        content.append(message.getText());

        // Handle thread replies if present
        if (message.getReplyCount() != null && message.getReplyCount() > 0) {
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

    private void addMetadata(Document document, Message message, String channelId,
                             String channelName, String userName, DocumentSourceDescriptor sourceDescriptor) {
        Map<String, Object> metadata = document.getMetadata();

        metadata.put("source", "slack");
        metadata.put("source_type", "SLACK");
        metadata.put("loader", getName());
        metadata.put("channel_id", channelId);
        metadata.put("channel_name", channelName);
        metadata.put("message_ts", message.getTs());
        metadata.put("user_id", message.getUser());
        metadata.put("user_name", userName);

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
}
