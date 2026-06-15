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

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, rule-based graph extractor for Slack message documents.
 * <p>
 * Produces entities and relationships that model the full Slack workspace graph:
 *
 * <h3>Entity Types:</h3>
 * <ul>
 *   <li><b>SLACK_WORKSPACE</b> — A Slack workspace (inferred from message collection)</li>
 *   <li><b>SLACK_CHANNEL</b> — A channel (public, private, DM, group DM)</li>
 *   <li><b>SLACK_USER</b> — A Slack user who authored or is mentioned in a message</li>
 *   <li><b>SLACK_MESSAGE</b> — An individual message</li>
 *   <li><b>SLACK_THREAD</b> — A conversation thread (parent message with replies)</li>
 *   <li><b>SLACK_FILE</b> — A file shared in a message</li>
 * </ul>
 *
 * <h3>Relationship Types:</h3>
 * <ul>
 *   <li><b>SENT_BY</b> — Message → User</li>
 *   <li><b>POSTED_IN</b> — Message → Channel</li>
 *   <li><b>REPLIED_IN_THREAD</b> — Message → Thread (for thread replies)</li>
 *   <li><b>THREAD_IN</b> — Thread → Channel</li>
 *   <li><b>MEMBER_OF</b> — User → Channel (inferred from authorship)</li>
 *   <li><b>MENTIONS_USER</b> — Message → User (from &lt;@U...&gt; patterns)</li>
 *   <li><b>MENTIONS_CHANNEL</b> — Message → Channel (from &lt;#C...&gt; patterns)</li>
 *   <li><b>HAS_FILE</b> — Message → File</li>
 *   <li><b>REACTED_TO</b> — User → Message (from reaction metadata)</li>
 * </ul>
 *
 * <p>Entity IDs are deterministic: {@code UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))} for
 * cross-document deduplication when {@link #extractBatch(List)} merges entities.
 *
 * <p>Supports both old-style metadata keys ({@code channel_id}, {@code user_id}) from the
 * existing loaders and new namespaced keys ({@code slack.channelId}, {@code slack.userId})
 * from the crawler.
 */
@Component
public class SlackGraphExtractor implements DocumentGraphExtractor {

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("slack", "slack_history");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        return meta.get("slack.channelId") != null || meta.get("channel_id") != null;
    }

    /** Matches Slack user mentions: <@U123456> */
    private static final Pattern USER_MENTION_PATTERN = Pattern.compile("<@(U[A-Z0-9]+)(?:\\|[^>]*)?>");

    /** Matches Slack channel mentions: <#C123456|channel-name> */
    private static final Pattern CHANNEL_MENTION_PATTERN = Pattern.compile("<#(C[A-Z0-9]+)(?:\\|([^>]*))?>");

    /** Matches Slack broadcast mentions: <!here>, <!channel>, <!everyone> */
    private static final Pattern BROADCAST_MENTION_PATTERN = Pattern.compile("<!(here|channel|everyone)>");

    /** Matches URLs in Slack messages: <http://url|display> or bare URLs */
    private static final Pattern SLACK_URL_PATTERN = Pattern.compile("<(https?://[^|>]+)(?:\\|[^>]*)?>|(?<![<])(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)");

    /**
     * Extract graph entities and relationships from a single Slack message document.
     */
    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) return ExtractionResult.of(List.of(), List.of(), null);

        Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        // Read metadata (support both old and new key patterns)
        String channelId = coalesce(meta, "slack.channelId", "channel_id");
        String channelName = coalesce(meta, "slack.channelName", "channel_name");
        String channelType = coalesce(meta, "slack.channelType", null);
        String messageTs = coalesce(meta, "slack.messageTs", "message_ts");
        String userId = coalesce(meta, "slack.userId", "user_id");
        String userName = coalesce(meta, "slack.userName", "user_name");
        String messageType = coalesce(meta, "slack.messageType", "message_type");
        String threadTs = coalesce(meta, "slack.threadTs", "thread_ts");
        Boolean isThreadReply = boolMeta(meta, "slack.isThreadReply", "is_thread_reply");
        Object replyCount = meta.get("slack.replyCount") != null ? meta.get("slack.replyCount") : meta.get("reply_count");
        String collectionName = coalesce(meta, "collection_name", null);
        String messageSubtype = coalesce(meta, "slack.messageSubtype", "message_subtype");

        // -- Workspace entity (inferred from collection_name or workspace metadata) --
        String workspaceId = coalesce(meta, "slack.workspaceId", "workspace_id");
        String workspaceName = coalesce(meta, "slack.workspaceName", "workspace_name");
        if (workspaceName == null && collectionName != null) {
            workspaceName = collectionName;
        }
        if (workspaceId != null || workspaceName != null) {
            String wsKey = workspaceId != null ? workspaceId : workspaceName;
            String wsEntityId = entityId("workspace:" + wsKey);
            addEntity(entities, new ExtractedEntity(
                    wsEntityId,
                    workspaceName != null ? workspaceName : workspaceId,
                    GraphConstants.ENTITY_SLACK_WORKSPACE,
                    List.of(),
                    "Slack workspace " + (workspaceName != null ? workspaceName : workspaceId),
                    1.0,
                    workspaceId != null ? Map.of("workspaceId", workspaceId) : Map.of()
            ));
        }

        // -- Channel entity --
        String channelTopic = coalesce(meta, "slack.channelTopic", null);
        String channelPurpose = coalesce(meta, "slack.channelPurpose", null);
        String channelMemberCount = coalesce(meta, "slack.channelMemberCount", null);

        if (channelId != null) {
            Map<String, String> channelProps = new LinkedHashMap<>();
            channelProps.put("channelId", channelId);
            if (channelName != null) channelProps.put("channelName", channelName);
            if (channelType != null) channelProps.put("channelType", channelType);
            if (channelTopic != null) channelProps.put("topic", channelTopic);
            if (channelPurpose != null) channelProps.put("purpose", channelPurpose);
            if (channelMemberCount != null) channelProps.put("memberCount", channelMemberCount);
            // Channel creation date, creator, and flags
            String channelCreated = str(meta.get("slack.channelCreated"));
            if (channelCreated != null) channelProps.put("createdTimestamp", channelCreated);
            String channelCreator = coalesce(meta, "slack.channelCreator", null);
            if (channelCreator != null) channelProps.put("creatorUserId", channelCreator);
            if (Boolean.TRUE.equals(meta.get("slack.channelIsArchived"))) channelProps.put("isArchived", "true");
            if (Boolean.TRUE.equals(meta.get("slack.channelIsPrivate"))) channelProps.put("isPrivate", "true");

            addEntity(entities, new ExtractedEntity(
                    entityId("channel:" + channelId),
                    channelName != null ? "#" + channelName : channelId,
                    GraphConstants.ENTITY_SLACK_CHANNEL,
                    channelName != null ? List.of(channelName) : List.of(),
                    "Slack channel " + (channelName != null ? "#" + channelName : channelId),
                    1.0,
                    channelProps
            ));

            // Channel → Workspace
            String wsKey2 = workspaceId != null ? workspaceId : workspaceName;
            if (wsKey2 != null) {
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId),
                        entityId("workspace:" + wsKey2),
                        GraphConstants.REL_CHANNEL_IN,
                        "Channel belongs to workspace",
                        1.0, Map.of()
                ));
            }

            // Channel → Creator user (CREATED_BY relationship)
            if (channelCreator != null) {
                String creatorEntityId = entityId("user:" + channelCreator);
                addEntity(entities, new ExtractedEntity(
                        creatorEntityId,
                        channelCreator,
                        GraphConstants.ENTITY_SLACK_USER,
                        null,
                        "Slack user (channel creator): " + channelCreator,
                        0.7,
                        Map.of("userId", channelCreator)
                ));
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId),
                        creatorEntityId,
                        GraphConstants.REL_CREATED_BY,
                        "#" + (channelName != null ? channelName : channelId) + " created by " + channelCreator,
                        0.85, Map.of()
                ));
            }

            // Channel topic → TOPIC entity
            if (channelTopic != null && !channelTopic.isBlank()) {
                String topicEntityId = entityId("topic:" + channelTopic.toLowerCase().trim());
                addEntity(entities, new ExtractedEntity(
                        topicEntityId,
                        channelTopic.length() > 100 ? channelTopic.substring(0, 100) + "..." : channelTopic,
                        GraphConstants.ENTITY_TOPIC,
                        null,
                        "Channel topic: " + channelTopic,
                        0.8,
                        Map.of("text", channelTopic, GraphConstants.PROP_SOURCE_FIELD, "slack.channelTopic")
                ));
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId), topicEntityId,
                        GraphConstants.REL_HAS_TOPIC,
                        "#" + (channelName != null ? channelName : channelId) + " has topic",
                        0.8, Map.of()
                ));
            }

            // Channel purpose → TOPIC entity (distinct from topic)
            if (channelPurpose != null && !channelPurpose.isBlank()
                    && !channelPurpose.equals(channelTopic)) {
                String purposeEntityId = entityId("purpose:" + channelPurpose.toLowerCase().trim());
                addEntity(entities, new ExtractedEntity(
                        purposeEntityId,
                        channelPurpose.length() > 100 ? channelPurpose.substring(0, 100) + "..." : channelPurpose,
                        GraphConstants.ENTITY_TOPIC,
                        null,
                        "Channel purpose: " + channelPurpose,
                        0.75,
                        Map.of("text", channelPurpose, GraphConstants.PROP_SOURCE_FIELD, "slack.channelPurpose")
                ));
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId), purposeEntityId,
                        GraphConstants.REL_HAS_TOPIC,
                        "#" + (channelName != null ? channelName : channelId) + " has purpose",
                        0.75, Map.of()
                ));
            }
        }

        // -- User entity --
        String userEmail = coalesce(meta, "slack.userEmail", null);
        String userDisplayName = coalesce(meta, "slack.userDisplayName", null);
        String userTitle = coalesce(meta, "slack.userTitle", null);
        String userTimeZone = coalesce(meta, "slack.userTimeZone", null);
        String userStatusText = coalesce(meta, "slack.userStatusText", null);
        String userStatusEmoji = coalesce(meta, "slack.userStatusEmoji", null);
        Boolean userIsBot = boolMeta(meta, "slack.userIsBot", null);
        Boolean userIsAdmin = boolMeta(meta, "slack.userIsAdmin", null);

        if (userId != null) {
            Map<String, String> userProps = new LinkedHashMap<>();
            userProps.put("userId", userId);
            if (userName != null) userProps.put("userName", userName);
            if (userEmail != null) userProps.put("email", userEmail);
            if (userDisplayName != null) userProps.put("displayName", userDisplayName);
            if (userTitle != null) userProps.put("title", userTitle);
            if (userTimeZone != null) userProps.put("timeZone", userTimeZone);
            if (userStatusText != null) userProps.put("statusText", userStatusText);
            if (userStatusEmoji != null) userProps.put("statusEmoji", userStatusEmoji);
            if (Boolean.TRUE.equals(userIsBot)) userProps.put("isBot", "true");
            if (Boolean.TRUE.equals(userIsAdmin)) userProps.put("isAdmin", "true");

            addEntity(entities, new ExtractedEntity(
                    entityId("user:" + userId),
                    userName != null ? userName : userId,
                    GraphConstants.ENTITY_SLACK_USER,
                    userName != null && !userName.equals(userId) ? List.of(userId) : List.of(),
                    "Slack user " + (userName != null ? userName : userId),
                    1.0,
                    userProps
            ));

            // User → Channel (inferred membership)
            if (channelId != null) {
                relations.add(new ExtractedRelation(
                        entityId("user:" + userId),
                        entityId("channel:" + channelId),
                        GraphConstants.REL_MEMBER_OF,
                        "User posted in channel, implying membership",
                        0.9, Map.of()
                ));
            }
        }

        // -- Thread entity (if this message is a thread parent or reply) --
        if (threadTs != null) {
            addEntity(entities, new ExtractedEntity(
                    entityId("thread:" + channelId + ":" + threadTs),
                    "Thread " + threadTs,
                    GraphConstants.ENTITY_SLACK_THREAD,
                    List.of(),
                    "Conversation thread in " + (channelName != null ? "#" + channelName : "channel"),
                    1.0,
                    Map.of("threadTs", threadTs, "channelId", channelId != null ? channelId : "")
            ));

            // Thread → Channel
            if (channelId != null) {
                relations.add(new ExtractedRelation(
                        entityId("thread:" + channelId + ":" + threadTs),
                        entityId("channel:" + channelId),
                        GraphConstants.REL_THREAD_IN,
                        "Thread belongs to channel",
                        1.0, Map.of()
                ));
            }
        }

        // -- Message entity --
        if (messageTs != null) {
            Map<String, String> msgProps = new LinkedHashMap<>();
            msgProps.put("messageTs", messageTs);
            if (messageType != null) msgProps.put("messageType", messageType);
            if (messageSubtype != null) msgProps.put("messageSubtype", messageSubtype);
            if (replyCount != null) msgProps.put("replyCount", replyCount.toString());
            Object reactionCount = meta.get("slack.reactionCount");
            if (reactionCount != null) msgProps.put("reactionCount", reactionCount.toString());
            Object fileCount = meta.get("slack.fileCount");
            if (fileCount != null) msgProps.put("fileCount", fileCount.toString());

            String msgContent = doc.getText();
            String description = msgContent != null && msgContent.length() > 200
                    ? msgContent.substring(0, 200) + "..."
                    : msgContent;

            String msgEntityId = entityId("msg:" + channelId + ":" + messageTs);

            addEntity(entities, new ExtractedEntity(
                    msgEntityId,
                    "Message " + messageTs,
                    GraphConstants.ENTITY_SLACK_MESSAGE,
                    List.of(),
                    description,
                    1.0,
                    msgProps
            ));

            // DATE entity from message timestamp
            if (messageTs != null) {
                String dateId = entityId("date:" + messageTs);
                addEntity(entities, new ExtractedEntity(
                        dateId, messageTs, GraphConstants.ENTITY_DATE,
                        List.of(), "Message date: " + messageTs, 0.85,
                        Map.of("date", messageTs, "dateType", "sent")
                ));
                relations.add(new ExtractedRelation(
                        msgEntityId, dateId,
                        GraphConstants.REL_PUBLISHED_ON,
                        "Message sent on " + messageTs,
                        0.85, Map.of()
                ));
            }

            // Message → User (SENT_BY)
            if (userId != null) {
                relations.add(new ExtractedRelation(
                        msgEntityId,
                        entityId("user:" + userId),
                        GraphConstants.REL_SENT_BY,
                        "Message sent by user",
                        1.0, Map.of()
                ));
            }

            // Edited message → editor user (EDITED_BY)
            Boolean isEdited = boolMeta(meta, "slack.isEdited", null);
            String editedByUserId = coalesce(meta, "slack.editedByUserId", null);
            String editedTs = coalesce(meta, "slack.editedTs", null);
            if (Boolean.TRUE.equals(isEdited)) {
                msgProps.put("isEdited", "true");
                if (editedTs != null) msgProps.put("editedTs", editedTs);
            }
            if (editedByUserId != null) {
                // Create the editor user entity (may be different from sender)
                String editorEntityId = entityId("user:" + editedByUserId);
                addEntity(entities, new ExtractedEntity(
                        editorEntityId,
                        editedByUserId,
                        GraphConstants.ENTITY_SLACK_USER,
                        List.of(),
                        "Slack user (editor) " + editedByUserId,
                        0.7,
                        Map.of("userId", editedByUserId)
                ));
                relations.add(new ExtractedRelation(
                        msgEntityId,
                        editorEntityId,
                        GraphConstants.REL_EDITED_BY,
                        "Message edited by user",
                        0.9, editedTs != null ? Map.of("editedTs", editedTs) : Map.of()
                ));
            }

            // Message → Channel (POSTED_IN)
            if (channelId != null) {
                relations.add(new ExtractedRelation(
                        msgEntityId,
                        entityId("channel:" + channelId),
                        GraphConstants.REL_POSTED_IN,
                        "Message posted in channel",
                        1.0, Map.of()
                ));
            }

            // Message → Thread (REPLIED_IN_THREAD) for thread replies
            if (isThreadReply != null && isThreadReply && threadTs != null) {
                relations.add(new ExtractedRelation(
                        msgEntityId,
                        entityId("thread:" + channelId + ":" + threadTs),
                        GraphConstants.REL_REPLIED_IN_THREAD,
                        "Message is a reply in thread",
                        1.0, Map.of()
                ));
                // Direct REPLIES_TO edge from reply message to thread parent message
                // threadTs is the parent message's timestamp
                if (channelId != null && !threadTs.equals(messageTs)) {
                    relations.add(new ExtractedRelation(
                            msgEntityId,
                            entityId("msg:" + channelId + ":" + threadTs),
                            GraphConstants.REL_REPLIES_TO,
                            "Reply to parent message",
                            0.95, Map.of()
                    ));
                }
            } else if (threadTs != null && replyCount != null) {
                // This is a thread parent
                relations.add(new ExtractedRelation(
                        msgEntityId,
                        entityId("thread:" + channelId + ":" + threadTs),
                        GraphConstants.REL_STARTED_THREAD,
                        "Message started this thread",
                        1.0, Map.of()
                ));
            }

            // -- Extract user mentions from message content --
            if (msgContent != null) {
                Matcher userMatcher = USER_MENTION_PATTERN.matcher(msgContent);
                while (userMatcher.find()) {
                    String mentionedUserId = userMatcher.group(1);
                    addEntity(entities, new ExtractedEntity(
                            entityId("user:" + mentionedUserId),
                            mentionedUserId,
                            GraphConstants.ENTITY_SLACK_USER,
                            List.of(),
                            "Slack user (mentioned)",
                            0.7,
                            Map.of("userId", mentionedUserId)
                    ));
                    relations.add(new ExtractedRelation(
                            msgEntityId,
                            entityId("user:" + mentionedUserId),
                            GraphConstants.REL_MENTIONS_USER,
                            "Message mentions user",
                            1.0, Map.of()
                    ));
                }

                // -- Extract channel mentions from message content --
                Matcher channelMatcher = CHANNEL_MENTION_PATTERN.matcher(msgContent);
                while (channelMatcher.find()) {
                    String mentionedChannelId = channelMatcher.group(1);
                    String mentionedChannelName = channelMatcher.group(2);
                    addEntity(entities, new ExtractedEntity(
                            entityId("channel:" + mentionedChannelId),
                            mentionedChannelName != null ? "#" + mentionedChannelName : mentionedChannelId,
                            GraphConstants.ENTITY_SLACK_CHANNEL,
                            mentionedChannelName != null ? List.of(mentionedChannelName) : List.of(),
                            "Slack channel (mentioned)",
                            0.8,
                            Map.of("channelId", mentionedChannelId)
                    ));
                    relations.add(new ExtractedRelation(
                            msgEntityId,
                            entityId("channel:" + mentionedChannelId),
                            GraphConstants.REL_MENTIONS_CHANNEL,
                            "Message mentions channel",
                            1.0, Map.of()
                    ));
                }

                // -- Extract broadcast mentions (@here, @channel, @everyone) --
                Matcher broadcastMatcher = BROADCAST_MENTION_PATTERN.matcher(msgContent);
                while (broadcastMatcher.find()) {
                    String broadcastType = broadcastMatcher.group(1); // "here", "channel", "everyone"
                    msgProps.put("broadcast_mention_" + broadcastType, "true");
                    // Create a MENTIONS_CHANNEL edge back to the channel for @channel/@here/@everyone
                    if (channelId != null && ("channel".equals(broadcastType)
                            || "here".equals(broadcastType) || "everyone".equals(broadcastType))) {
                        relations.add(new ExtractedRelation(
                                msgEntityId,
                                entityId("channel:" + channelId),
                                GraphConstants.REL_MENTIONS_CHANNEL,
                                "Message uses @" + broadcastType + " broadcast mention",
                                0.9, Map.of("broadcastType", broadcastType)
                        ));
                    }
                }

                // -- Extract URLs from message body → HYPERLINKS_TO → EXTERNAL_RESOURCE --
                Set<String> seenUrls = new LinkedHashSet<>();
                Matcher urlMatcher = SLACK_URL_PATTERN.matcher(msgContent);
                while (urlMatcher.find() && seenUrls.size() < 30) {
                    String url = urlMatcher.group(1) != null ? urlMatcher.group(1) : urlMatcher.group(2);
                    if (url != null && seenUrls.add(url)) {
                        String urlEntityId = entityId("url:" + url);
                        addEntity(entities, new ExtractedEntity(
                                urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                List.of(), "URL shared in Slack message", 0.8,
                                Map.of("url", url)
                        ));
                        relations.add(new ExtractedRelation(
                                msgEntityId, urlEntityId,
                                GraphConstants.REL_HYPERLINKS_TO,
                                "Message contains link to " + url,
                                0.8, Map.of()
                        ));
                    }
                }
            }

            // -- Reactions (from crawler metadata) --
            // Supports two formats:
            //   1. "emoji_name:count" — aggregate only, stored as message property
            //   2. "emoji_name:userId" — per-user, creates REACTED_TO relation
            Object reactions = meta.get("slack.reactions");
            if (reactions instanceof List<?> reactionList) {
                for (Object r : reactionList) {
                    String reactionStr = str(r);
                    if (reactionStr != null && reactionStr.contains(":")) {
                        String emojiName = reactionStr.substring(0, reactionStr.lastIndexOf(':'));
                        String afterColon = reactionStr.substring(reactionStr.lastIndexOf(':') + 1);

                        // If value looks like a Slack user ID (e.g. U12345ABC), create REACTED_TO edge
                        if (afterColon.matches("U[A-Z0-9]+")) {
                            String reactorUserId = afterColon;
                            addEntity(entities, new ExtractedEntity(
                                    entityId("user:" + reactorUserId),
                                    reactorUserId,
                                    GraphConstants.ENTITY_SLACK_USER,
                                    List.of(),
                                    "Slack user (reacted)",
                                    0.7,
                                    Map.of("userId", reactorUserId)
                            ));
                            relations.add(new ExtractedRelation(
                                    entityId("user:" + reactorUserId),
                                    msgEntityId,
                                    GraphConstants.REL_REACTED_TO,
                                    "User reacted with :" + emojiName + ": to message",
                                    1.0, Map.of("emoji", emojiName)
                            ));
                        } else {
                            // Aggregate format — create SLACK_REACTION entity + HAS_REACTION edge
                            // so reactions are always graphed, not silently stored as properties
                            String reactionEntityId = entityId("reaction:" + channelId + ":" + messageTs + ":" + emojiName);
                            Map<String, String> reactionProps = new LinkedHashMap<>();
                            reactionProps.put("emoji", emojiName);
                            reactionProps.put("count", afterColon);
                            addEntity(entities, new ExtractedEntity(
                                    reactionEntityId,
                                    ":" + emojiName + ": (" + afterColon + ")",
                                    GraphConstants.ENTITY_SLACK_REACTION,
                                    List.of(),
                                    "Reaction :" + emojiName + ": with count " + afterColon,
                                    0.85,
                                    reactionProps
                            ));
                            relations.add(new ExtractedRelation(
                                    msgEntityId,
                                    reactionEntityId,
                                    GraphConstants.REL_HAS_REACTION,
                                    "Message has reaction :" + emojiName + ":",
                                    0.85, Map.of("emoji", emojiName, "count", afterColon)
                            ));
                        }
                    }
                }
            }

            // Also handle slack.reactionUsers format: List of Maps with "emoji" and "users" keys
            Object reactionUsers = meta.get("slack.reactionUsers");
            if (reactionUsers instanceof List<?> reactionUsersList) {
                for (Object item : reactionUsersList) {
                    if (item instanceof Map<?, ?> reactionMap) {
                        String emoji = str(reactionMap.get("emoji"));
                        Object users = reactionMap.get("users");
                        if (emoji != null && users instanceof List<?> userList) {
                            for (Object u : userList) {
                                String reactorId = str(u);
                                if (reactorId != null) {
                                    addEntity(entities, new ExtractedEntity(
                                            entityId("user:" + reactorId),
                                            reactorId,
                                            GraphConstants.ENTITY_SLACK_USER,
                                            List.of(),
                                            "Slack user (reacted)",
                                            0.7,
                                            Map.of("userId", reactorId)
                                    ));
                                    relations.add(new ExtractedRelation(
                                            entityId("user:" + reactorId),
                                            msgEntityId,
                                            GraphConstants.REL_REACTED_TO,
                                            "User reacted with :" + emoji + ": to message",
                                            1.0, Map.of("emoji", emoji)
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }

        // -- Pinned items --
        // A message is a pinned item when messageSubtype == "pinned_item" OR slack.pinned == true
        boolean isPinned = "pinned_item".equals(messageSubtype)
                || Boolean.TRUE.equals(boolMeta(meta, "slack.pinned", "pinned"));
        if (isPinned && messageTs != null && channelId != null) {
            String msgEntityId = entityId("msg:" + channelId + ":" + messageTs);
            String pinnedEntityId = entityId("pinned:" + channelId + ":" + messageTs);
            Map<String, String> pinnedProps = new LinkedHashMap<>();
            pinnedProps.put("messageTs", messageTs);
            if (channelId != null) pinnedProps.put("channelId", channelId);
            if (channelName != null) pinnedProps.put("channelName", channelName);
            addEntity(entities, new ExtractedEntity(
                    pinnedEntityId,
                    "Pinned: " + messageTs,
                    GraphConstants.ENTITY_PINNED_ITEM,
                    List.of(),
                    "Pinned message in " + (channelName != null ? "#" + channelName : channelId),
                    1.0,
                    pinnedProps
            ));
            // PINNED_ITEM → channel
            relations.add(new ExtractedRelation(
                    pinnedEntityId,
                    entityId("channel:" + channelId),
                    GraphConstants.REL_PINNED_IN,
                    "Pinned item in channel",
                    1.0, Map.of()
            ));
            // message → pinned item link via CONTAINS
            relations.add(new ExtractedRelation(
                    pinnedEntityId,
                    msgEntityId,
                    GraphConstants.REL_CONTAINS,
                    "Pinned item wraps message",
                    1.0, Map.of()
            ));
        }

        // -- Channel topic / purpose / name changes --
        // Slack sends special subtypes when a user changes a channel's topic, purpose, or name.
        // These are critical metadata for understanding what channels are about.
        if (messageSubtype != null && channelId != null) {
            String channelEntityId = entityId("channel:" + channelId);
            String msgEntityId2 = messageTs != null ? entityId("msg:" + channelId + ":" + messageTs) : null;
            String msgContent = doc.getText();

            if ("channel_topic".equals(messageSubtype) || "group_topic".equals(messageSubtype)) {
                // Extract the topic text from the message body
                String topicText = msgContent != null ? msgContent.trim() : null;
                if (topicText != null && !topicText.isEmpty()) {
                    String topicId = entityId("topic:" + channelId + ":topic");
                    Map<String, String> topicProps = new LinkedHashMap<>();
                    topicProps.put("topicText", topicText);
                    topicProps.put("channelId", channelId);
                    if (messageTs != null) topicProps.put("setAt", messageTs);
                    if (userId != null) topicProps.put("setBy", userId);

                    addEntity(entities, new ExtractedEntity(
                            topicId, "Topic: " + (topicText.length() > 80 ? topicText.substring(0, 80) + "..." : topicText),
                            GraphConstants.ENTITY_TOPIC, List.of(),
                            "Channel topic for " + (channelName != null ? "#" + channelName : channelId),
                            1.0, topicProps
                    ));
                    relations.add(new ExtractedRelation(
                            channelEntityId, topicId,
                            GraphConstants.REL_HAS_TOPIC,
                            "Channel has topic", 1.0, Map.of()
                    ));
                    if (userId != null) {
                        relations.add(new ExtractedRelation(
                                topicId, entityId("user:" + userId),
                                GraphConstants.REL_CHANGED_BY,
                                "Topic set by user", 1.0, Map.of()
                        ));
                    }
                    if (msgEntityId2 != null) {
                        relations.add(new ExtractedRelation(
                                msgEntityId2, topicId,
                                GraphConstants.REL_CONTAINS,
                                "Message contains topic change", 1.0, Map.of()
                        ));
                    }
                }
            } else if ("channel_purpose".equals(messageSubtype) || "group_purpose".equals(messageSubtype)) {
                String purposeText = msgContent != null ? msgContent.trim() : null;
                if (purposeText != null && !purposeText.isEmpty()) {
                    String purposeId = entityId("topic:" + channelId + ":purpose");
                    Map<String, String> purposeProps = new LinkedHashMap<>();
                    purposeProps.put("purposeText", purposeText);
                    purposeProps.put("channelId", channelId);
                    if (messageTs != null) purposeProps.put("setAt", messageTs);
                    if (userId != null) purposeProps.put("setBy", userId);

                    addEntity(entities, new ExtractedEntity(
                            purposeId, "Purpose: " + (purposeText.length() > 80 ? purposeText.substring(0, 80) + "..." : purposeText),
                            GraphConstants.ENTITY_TOPIC, List.of(),
                            "Channel purpose for " + (channelName != null ? "#" + channelName : channelId),
                            1.0, purposeProps
                    ));
                    relations.add(new ExtractedRelation(
                            channelEntityId, purposeId,
                            GraphConstants.REL_HAS_TOPIC,
                            "Channel has purpose", 1.0, Map.of()
                    ));
                    if (userId != null) {
                        relations.add(new ExtractedRelation(
                                purposeId, entityId("user:" + userId),
                                GraphConstants.REL_CHANGED_BY,
                                "Purpose set by user", 1.0, Map.of()
                        ));
                    }
                    if (msgEntityId2 != null) {
                        relations.add(new ExtractedRelation(
                                msgEntityId2, purposeId,
                                GraphConstants.REL_CONTAINS,
                                "Message contains purpose change", 1.0, Map.of()
                        ));
                    }
                }
            } else if ("channel_name".equals(messageSubtype) || "group_name".equals(messageSubtype)) {
                // Channel rename — enrich the channel entity with the old/new name from the message
                if (msgContent != null && !msgContent.trim().isEmpty()) {
                    // Typical format: "renamed the channel from X to Y"
                    // Store the rename event as a property on the channel entity
                    Map<String, String> renameProps = new LinkedHashMap<>();
                    renameProps.put("channelId", channelId);
                    renameProps.put("renameMessage", msgContent.trim());
                    if (channelName != null) renameProps.put("channelName", channelName);

                    addEntity(entities, new ExtractedEntity(
                            entityId("channel:" + channelId),
                            channelName != null ? "#" + channelName : channelId,
                            GraphConstants.ENTITY_SLACK_CHANNEL,
                            List.of(), "Slack channel (renamed)",
                            1.0, renameProps
                    ));
                }
            } else if ("channel_join".equals(messageSubtype) || "group_join".equals(messageSubtype)) {
                // User joined channel — ensure MEMBER_OF relation exists (already handled above for message author,
                // but this makes it explicit with higher confidence for join events)
                if (userId != null) {
                    relations.add(new ExtractedRelation(
                            entityId("user:" + userId),
                            channelEntityId,
                            GraphConstants.REL_MEMBER_OF,
                            "User joined channel",
                            1.0, Map.of("joinedVia", "channel_join")
                    ));
                }
            } else if ("channel_leave".equals(messageSubtype) || "group_leave".equals(messageSubtype)) {
                // User left channel — we still record the membership (historical), but mark as left
                if (userId != null) {
                    relations.add(new ExtractedRelation(
                            entityId("user:" + userId),
                            channelEntityId,
                            GraphConstants.REL_MEMBER_OF,
                            "User was member of channel (left)",
                            0.5, Map.of("leftChannel", "true")
                    ));
                }
            }
        }

        // -- Bot messages --
        // A bot message is indicated by messageSubtype containing "bot_message" or slack.botId being set
        String botId = coalesce(meta, "slack.botId", "bot_id");
        String botName = coalesce(meta, "slack.botName", "bot_name");
        boolean isBotMessage = botId != null
                || (messageSubtype != null && messageSubtype.contains("bot_message"));
        if (isBotMessage && messageTs != null && channelId != null) {
            String msgEntityId = entityId("msg:" + channelId + ":" + messageTs);
            String botKey = botId != null ? botId : (botName != null ? botName : "unknown-bot");
            String botEntityId = entityId("bot:" + botKey);
            Map<String, String> botProps = new LinkedHashMap<>();
            if (botId != null) botProps.put("botId", botId);
            if (botName != null) botProps.put("botName", botName);
            addEntity(entities, new ExtractedEntity(
                    botEntityId,
                    botName != null ? botName : botId != null ? botId : "Bot",
                    GraphConstants.ENTITY_SLACK_BOT,
                    List.of(),
                    "Slack bot" + (botName != null ? " " + botName : ""),
                    1.0,
                    botProps
            ));
            relations.add(new ExtractedRelation(
                    msgEntityId,
                    botEntityId,
                    GraphConstants.REL_SENT_BY_BOT,
                    "Message sent by bot",
                    1.0, Map.of()
            ));
        }

        // -- Files / attachments (outside messageTs guard so file CrawlItems are handled) --
        // Handle multi-file list format (slack.files) and legacy single-file format (slack.fileId)
        Set<String> processedFileIds = new LinkedHashSet<>();
        Object filesObj = meta.get("slack.files");
        if (filesObj instanceof List<?> filesList) {
            for (Object item : filesList) {
                if (!(item instanceof Map<?, ?> fileMap)) continue;
                String fId = str(fileMap.get("id"));
                if (fId == null || !processedFileIds.add(fId)) continue;
                String fName = str(fileMap.get("name"));
                String fMime = str(fileMap.get("mimeType"));
                String fType = str(fileMap.get("filetype"));
                Object fSize = fileMap.get("size");
                String fPermalink = str(fileMap.get("permalink"));
                String fTitle = str(fileMap.get("title"));

                Map<String, String> fileProps = new LinkedHashMap<>();
                fileProps.put("fileId", fId);
                if (fName != null) fileProps.put(GraphConstants.META_FILE_NAME, fName);
                if (fMime != null) fileProps.put("mimeType", fMime);
                if (fType != null) fileProps.put("fileType", fType);
                if (fSize != null) fileProps.put(GraphConstants.META_FILE_SIZE, fSize.toString());
                if (fPermalink != null) fileProps.put("permalink", fPermalink);
                if (fTitle != null) fileProps.put("title", fTitle);

                String fileEntityId = entityId("file:" + fId);
                addEntity(entities, new ExtractedEntity(
                        fileEntityId,
                        fName != null ? fName : "File " + fId,
                        GraphConstants.ENTITY_SLACK_FILE,
                        List.of(),
                        "File shared in Slack: " + (fName != null ? fName : fId),
                        1.0,
                        fileProps
                ));

                // Link file to message
                String fileMsgTs = messageTs;
                if (fileMsgTs == null) fileMsgTs = coalesce(meta, "slack.parentMessageTs", null);
                if (fileMsgTs != null && channelId != null) {
                    relations.add(new ExtractedRelation(
                            entityId("msg:" + channelId + ":" + fileMsgTs),
                            fileEntityId,
                            GraphConstants.REL_HAS_FILE,
                            "Message has file attachment",
                            1.0, Map.of()
                    ));
                }

                // UPLOADED_BY: file → uploader user entity (from message author)
                if (userId != null) {
                    relations.add(new ExtractedRelation(
                            fileEntityId,
                            entityId("user:" + userId),
                            GraphConstants.REL_UPLOADED_BY,
                            "File uploaded by user",
                            1.0, Map.of()
                    ));
                }

                // SHARED_IN_CHANNEL: direct file → channel link
                // Mirrors the legacy single-file path — ensures files are always
                // connected to their channel even when parent message is absent
                if (channelId != null) {
                    relations.add(new ExtractedRelation(
                            fileEntityId,
                            entityId("channel:" + channelId),
                            GraphConstants.REL_SHARED_IN_CHANNEL,
                            "File shared in channel",
                            0.9, Map.of()
                    ));
                }

                // Permalink as EXTERNAL_RESOURCE
                if (fPermalink != null && !fPermalink.isBlank()) {
                    String permalinkId = entityId("url:" + fPermalink.toLowerCase());
                    addEntity(entities, new ExtractedEntity(permalinkId, fPermalink,
                            GraphConstants.ENTITY_EXTERNAL_RESOURCE, List.of(),
                            "Permalink for Slack file: " + (fName != null ? fName : fId), 0.85,
                            Map.of("url", fPermalink)));
                    relations.add(new ExtractedRelation(fileEntityId, permalinkId,
                            GraphConstants.REL_HYPERLINKS_TO,
                            (fName != null ? fName : "File") + " viewable at " + fPermalink, 0.85, Map.of()));
                }
            }
        }

        // Fallback: legacy single-file keys (slack.fileId / slack.fileName)
        Object fileId = meta.get("slack.fileId");
        Object fileName = meta.get("slack.fileName");

        if (fileId != null && processedFileIds.add(str(fileId))) {
            Map<String, String> fileProps = new LinkedHashMap<>();
            fileProps.put("fileId", str(fileId));
            if (fileName != null) fileProps.put(GraphConstants.META_FILE_NAME, str(fileName));
            Object fileMimeType = meta.get("slack.fileMimeType");
            if (fileMimeType != null) fileProps.put("mimeType", str(fileMimeType));
            Object fileType = meta.get("slack.fileType");
            if (fileType != null) fileProps.put("fileType", str(fileType));
            Object fileSize = meta.get("slack.fileSize");
            if (fileSize != null) fileProps.put("size", fileSize.toString());
            String uploaderUserId = coalesce(meta, "slack.uploaderUserId", null);
            if (uploaderUserId != null) fileProps.put("uploaderUserId", uploaderUserId);

            String fileEntityId = entityId("file:" + str(fileId));
            addEntity(entities, new ExtractedEntity(
                    fileEntityId,
                    fileName != null ? str(fileName) : "File " + str(fileId),
                    GraphConstants.ENTITY_SLACK_FILE,
                    List.of(),
                    "File shared in Slack: " + (fileName != null ? str(fileName) : str(fileId)),
                    1.0,
                    fileProps
            ));

            // UPLOADED_BY: file → uploader user entity
            if (uploaderUserId != null) {
                addEntity(entities, new ExtractedEntity(
                        entityId("user:" + uploaderUserId),
                        uploaderUserId,
                        GraphConstants.ENTITY_SLACK_USER,
                        List.of(),
                        "Slack user (file uploader)",
                        0.7,
                        Map.of("userId", uploaderUserId)
                ));
                relations.add(new ExtractedRelation(
                        fileEntityId,
                        entityId("user:" + uploaderUserId),
                        GraphConstants.REL_UPLOADED_BY,
                        "File uploaded by user",
                        1.0, Map.of()
                ));
            }

            // Link file to message: use messageTs if available, else parentMessageTs
            String fileMsgTs = messageTs;
            if (fileMsgTs == null) {
                fileMsgTs = coalesce(meta, "slack.parentMessageTs", null);
            }
            if (fileMsgTs != null && channelId != null) {
                String fileMsgEntityId = entityId("msg:" + channelId + ":" + fileMsgTs);
                relations.add(new ExtractedRelation(
                        fileMsgEntityId,
                        entityId("file:" + str(fileId)),
                        GraphConstants.REL_HAS_FILE,
                        "Message has file attachment",
                        1.0, Map.of()
                ));
            }

            // SHARED_IN_CHANNEL: direct file → channel link
            // Ensures files are always connected to their channel even when parent message
            // is not separately ingested (e.g., file-only CrawlItems)
            if (channelId != null) {
                relations.add(new ExtractedRelation(
                        fileEntityId,
                        entityId("channel:" + channelId),
                        GraphConstants.REL_SHARED_IN_CHANNEL,
                        "File shared in channel",
                        0.9, Map.of()
                ));
            }
        }

        String sourceId = coalesce(meta, GraphConstants.META_SOURCE_ID, null);
        ExtractionMetadata extractionMeta = ExtractionMetadata.forChunk(
                messageTs, sourceId, "slack-rule-extractor"
        );

        return ExtractionResult.of(
                new ArrayList<>(entities.values()),
                relations,
                extractionMeta
        );
    }

    /**
     * Extract and merge graphs from a batch of Slack documents.
     * Entities with the same ID are merged (aliases unioned, higher confidence kept).
     */
    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        Map<String, ExtractedEntity> mergedEntities = new LinkedHashMap<>();
        List<ExtractedRelation> allRelations = new ArrayList<>();

        for (Document doc : docs) {
            ExtractionResult result = extract(doc);
            for (ExtractedEntity entity : result.entities()) {
                addEntity(mergedEntities, entity);
            }
            allRelations.addAll(result.relations());
        }

        // Deduplicate relations
        Set<String> seen = new HashSet<>();
        List<ExtractedRelation> uniqueRelations = allRelations.stream()
                .filter(r -> seen.add(r.source() + "|" + r.target() + "|" + r.type()))
                .collect(Collectors.toCollection(ArrayList::new));

        return ExtractionResult.of(
                new ArrayList<>(mergedEntities.values()),
                uniqueRelations,
                ExtractionMetadata.forChunk(null, null, "slack-rule-extractor")
        );
    }

    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private void addEntity(Map<String, ExtractedEntity> index, ExtractedEntity entity) {
        ExtractedEntity existing = index.get(entity.id());
        if (existing == null) {
            index.put(entity.id(), entity);
            return;
        }

        // Merge: union aliases, keep higher confidence, prefer real name over ID
        Set<String> aliases = new LinkedHashSet<>();
        if (existing.aliases() != null) aliases.addAll(existing.aliases());
        if (entity.aliases() != null) aliases.addAll(entity.aliases());

        String name = existing.name();
        // Prefer a real name over a bare ID or generic label
        if (name.matches("[A-Z][A-Z0-9]+") || name.startsWith("Message ") || name.startsWith("Thread ")) {
            if (!entity.name().matches("[A-Z][A-Z0-9]+") && !entity.name().startsWith("Message ")
                    && !entity.name().startsWith("Thread ")) {
                name = entity.name();
            }
        }

        double confidence = Math.max(
                existing.confidence() != null ? existing.confidence() : 0,
                entity.confidence() != null ? entity.confidence() : 0
        );

        String description = existing.description();
        if ((description == null || description.length() < 20) && entity.description() != null) {
            description = entity.description();
        }

        Map<String, String> props = new LinkedHashMap<>();
        if (existing.properties() != null) props.putAll(existing.properties());
        if (entity.properties() != null) props.putAll(entity.properties());

        index.put(entity.id(), new ExtractedEntity(
                entity.id(), name, existing.type(),
                new ArrayList<>(aliases), description, confidence, props
        ));
    }

    /**
     * Read a metadata value supporting both namespaced (slack.xxx) and legacy (xxx) keys.
     */
    private static String coalesce(Map<String, Object> meta, String key1, String key2) {
        Object val = meta.get(key1);
        if (val != null) return val.toString().trim();
        if (key2 != null) {
            val = meta.get(key2);
            if (val != null) return val.toString().trim();
        }
        return null;
    }

    private static Boolean boolMeta(Map<String, Object> meta, String key1, String key2) {
        Object val = meta.get(key1);
        if (val == null && key2 != null) val = meta.get(key2);
        if (val == null) return null;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }
}
