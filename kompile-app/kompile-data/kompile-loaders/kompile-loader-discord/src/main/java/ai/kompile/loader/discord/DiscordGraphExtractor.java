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

package ai.kompile.loader.discord;

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
 * Deterministic, rule-based graph extractor for Discord message documents.
 * <p>
 * Produces entities and relationships that model the full Discord server graph:
 *
 * <h3>Entity Types:</h3>
 * <ul>
 *   <li><b>DISCORD_SERVER</b> — A Discord guild/server</li>
 *   <li><b>DISCORD_CHANNEL</b> — A text channel, forum, or announcement channel</li>
 *   <li><b>DISCORD_THREAD</b> — A thread within a channel</li>
 *   <li><b>DISCORD_USER</b> — A Discord user who authored a message</li>
 *   <li><b>DISCORD_MESSAGE</b> — An individual message</li>
 *   <li><b>DISCORD_ATTACHMENT</b> — A file attached to a message</li>
 *   <li><b>DISCORD_ROLE</b> — A mentioned role</li>
 *   <li><b>DISCORD_REACTION</b> — A distinct emoji used to react to messages</li>
 * </ul>
 *
 * <h3>Relationship Types:</h3>
 * <ul>
 *   <li><b>SENT_BY</b> — Message → User</li>
 *   <li><b>POSTED_IN</b> — Message → Channel/Thread</li>
 *   <li><b>CHANNEL_IN</b> — Channel/Thread → Server</li>
 *   <li><b>THREAD_IN</b> — Thread → Channel</li>
 *   <li><b>REPLIED_TO</b> — Message → Message (reply reference)</li>
 *   <li><b>HAS_ATTACHMENT</b> — Message → Attachment</li>
 *   <li><b>MENTIONS_USER</b> — Message → User</li>
 *   <li><b>MENTIONS_ROLE</b> — Message → Role</li>
 *   <li><b>MEMBER_OF</b> — User → Server (inferred from authorship)</li>
 *   <li><b>HAS_REACTION</b> — Message → Reaction (with {@code count} property on the edge)</li>
 * </ul>
 *
 * <p>Entity IDs are deterministic: {@code UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))} where key
 * is a Discord snowflake ID or composite key, enabling cross-document deduplication when
 * {@link #extractBatch(List)} merges entities.
 */
@Component
public class DiscordGraphExtractor implements DocumentGraphExtractor {

    private static final Pattern URL_PATTERN =
            Pattern.compile("(?:https?|ftps?|mailto):[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("discord", "discord_message", "discord_history", "discord_attachment");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        return meta.get("discord.guildId") != null || meta.get("discord.channelId") != null;
    }

    /**
     * Extract graph entities and relationships from a single Discord message document.
     */
    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) return ExtractionResult.of(List.of(), List.of(), null);

        Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        String messageId = str(meta.get("discord.messageId"));
        String channelId = str(meta.get("discord.channelId"));
        String channelName = str(meta.get("discord.channelName"));
        String channelType = str(meta.get("discord.channelType"));
        String guildId = str(meta.get("discord.guildId"));
        String guildName = str(meta.get("discord.guildName"));
        String authorId = str(meta.get("discord.authorId"));
        String authorName = str(meta.get("discord.authorName"));
        String authorUsername = str(meta.get("discord.authorUsername"));
        String timestamp = str(meta.get("discord.timestamp"));
        Boolean isThread = meta.get("discord.isThread") instanceof Boolean b ? b : false;
        String parentChannelId = str(meta.get("discord.parentChannelId"));
        String replyToMessageId = str(meta.get("discord.replyToMessageId"));
        String replyToChannelId = str(meta.get("discord.replyToChannelId"));

        // -- Server entity --
        if (guildId != null) {
            Map<String, String> serverProps = new LinkedHashMap<>();
            serverProps.put("guildId", guildId);
            String guildDescription = str(meta.get("discord.guildDescription"));
            if (guildDescription != null) serverProps.put("description", guildDescription);
            Object guildMemberCount = meta.get("discord.guildMemberCount");
            if (guildMemberCount != null) serverProps.put("memberCount", guildMemberCount.toString());
            String guildLocale = str(meta.get("discord.guildLocale"));
            if (guildLocale != null) serverProps.put("locale", guildLocale);

            addEntity(entities, new ExtractedEntity(
                    entityId("guild:" + guildId),
                    guildName != null ? guildName : guildId,
                    GraphConstants.ENTITY_DISCORD_SERVER,
                    List.of(),
                    "Discord server " + (guildName != null ? guildName : guildId),
                    1.0,
                    serverProps
            ));

            // -- Guild owner OWNED_BY relation --
            String guildOwnerId = str(meta.get("discord.guildOwnerId"));
            if (guildOwnerId != null) {
                addEntity(entities, new ExtractedEntity(
                        entityId("user:" + guildOwnerId),
                        guildOwnerId,
                        GraphConstants.ENTITY_DISCORD_USER,
                        List.of(),
                        "Discord guild owner",
                        0.9,
                        Map.of("userId", guildOwnerId)
                ));
                relations.add(new ExtractedRelation(
                        entityId("guild:" + guildId),
                        entityId("user:" + guildOwnerId),
                        GraphConstants.REL_OWNED_BY,
                        "Server owned by user",
                        1.0, Map.of()
                ));
            }
        }

        // -- Channel entity --
        if (channelId != null) {
            String channelEntityType = isThread ? GraphConstants.ENTITY_DISCORD_THREAD : GraphConstants.ENTITY_DISCORD_CHANNEL;
            Map<String, String> channelProps = new LinkedHashMap<>();
            channelProps.put("channelId", channelId);
            if (channelType != null) channelProps.put("channelType", channelType);

            // Channel topic (text channels)
            String channelTopic = str(meta.get("discord.channelTopic"));
            if (channelTopic != null) channelProps.put("topic", channelTopic);

            // Channel message count
            Object channelMessageCount = meta.get("discord.channelMessageCount");
            if (channelMessageCount != null) channelProps.put("messageCount", channelMessageCount.toString());

            // Thread-specific metadata
            if (isThread) {
                Object threadArchived = meta.get("discord.threadArchived");
                if (threadArchived != null) channelProps.put("archived", threadArchived.toString());
                Object autoArchiveDuration = meta.get("discord.threadAutoArchiveDuration");
                if (autoArchiveDuration != null) channelProps.put("autoArchiveDuration", autoArchiveDuration.toString());
                String archiveTimestamp = str(meta.get("discord.threadArchiveTimestamp"));
                if (archiveTimestamp != null) channelProps.put("archiveTimestamp", archiveTimestamp);
                Object threadLocked = meta.get("discord.threadLocked");
                if (threadLocked != null) channelProps.put("locked", threadLocked.toString());
            }

            addEntity(entities, new ExtractedEntity(
                    entityId("channel:" + channelId),
                    channelName != null ? "#" + channelName : channelId,
                    channelEntityType,
                    channelName != null ? List.of(channelName) : List.of(),
                    (isThread ? "Thread" : "Channel") + " " + (channelName != null ? "#" + channelName : channelId),
                    1.0,
                    channelProps
            ));

            // Channel topic → TOPIC entity + HAS_TOPIC relation
            if (channelTopic != null) {
                String channelEntityId = entityId("channel:" + channelId);
                String topicId = entityId("topic:discord:" + channelId + ":" + channelTopic.toLowerCase());
                Map<String, String> topicProps = new LinkedHashMap<>();
                topicProps.put("topicText", channelTopic);
                topicProps.put("channelId", channelId);
                String topicLabel = "Topic: " + (channelTopic.length() > 80 ? channelTopic.substring(0, 80) + "..." : channelTopic);
                addEntity(entities, new ExtractedEntity(
                        topicId, topicLabel,
                        GraphConstants.ENTITY_TOPIC, List.of(),
                        "Channel topic for " + (channelName != null ? "#" + channelName : channelId),
                        1.0, topicProps
                ));
                relations.add(new ExtractedRelation(
                        channelEntityId, topicId,
                        GraphConstants.REL_HAS_TOPIC,
                        "Channel has topic", 1.0, Map.of()
                ));
            }

            // Channel/Thread → Server
            if (guildId != null) {
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId),
                        entityId("guild:" + guildId),
                        GraphConstants.REL_CHANNEL_IN,
                        (isThread ? "Thread" : "Channel") + " in server",
                        1.0, Map.of()
                ));
            }

            // Thread → Parent Channel
            if (isThread && parentChannelId != null) {
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId),
                        entityId("channel:" + parentChannelId),
                        GraphConstants.REL_THREAD_IN,
                        "Thread belongs to parent channel",
                        1.0, Map.of()
                ));
            }

            // Non-thread channel → Category (parent channel is a category for non-thread channels)
            if (!isThread && parentChannelId != null) {
                String categoryEntityId = entityId("category:" + parentChannelId);
                Map<String, String> catProps = new LinkedHashMap<>();
                catProps.put("channelId", parentChannelId);
                addEntity(entities, new ExtractedEntity(
                        categoryEntityId,
                        "Category " + parentChannelId,
                        GraphConstants.ENTITY_DISCORD_CATEGORY,
                        List.of(),
                        "Discord channel category",
                        0.9,
                        catProps
                ));
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId),
                        categoryEntityId,
                        GraphConstants.REL_IN_CATEGORY,
                        "Channel in category",
                        1.0, Map.of()
                ));
                // Category → Server
                if (guildId != null) {
                    relations.add(new ExtractedRelation(
                            categoryEntityId,
                            entityId("guild:" + guildId),
                            GraphConstants.REL_CHANNEL_IN,
                            "Category in server",
                            1.0, Map.of()
                    ));
                }
            }
        }

        // -- User/Bot entity --
        Boolean authorIsBot = meta.get("discord.authorIsBot") instanceof Boolean b2 ? b2 : false;
        if (authorId != null) {
            List<String> aliases = new ArrayList<>();
            if (authorUsername != null) aliases.add(authorUsername);

            Map<String, String> userProps = new LinkedHashMap<>();
            userProps.put("userId", authorId);
            if (authorUsername != null) userProps.put("username", authorUsername);
            if (authorIsBot) userProps.put("isBot", "true");
            // Guild member enrichment (nickname, join date)
            String authorNick = str(meta.get("discord.authorNick"));
            String authorJoinedAt = str(meta.get("discord.authorJoinedAt"));
            if (authorNick != null) userProps.put("nickname", authorNick);
            if (authorJoinedAt != null) userProps.put("joinedAt", authorJoinedAt);
            String authorGlobalName = str(meta.get("discord.authorGlobalName"));
            String authorDiscriminator = str(meta.get("discord.authorDiscriminator"));
            if (authorGlobalName != null) userProps.put("globalName", authorGlobalName);
            if (authorDiscriminator != null) userProps.put("discriminator", authorDiscriminator);
            String authorAvatarUrl = str(meta.get("discord.authorAvatarUrl"));
            if (authorAvatarUrl != null) userProps.put("avatarUrl", authorAvatarUrl);

            String entityType = authorIsBot ? GraphConstants.ENTITY_DISCORD_BOT : GraphConstants.ENTITY_DISCORD_USER;
            String entityDesc = authorIsBot
                    ? "Discord bot " + (authorName != null ? authorName : authorId)
                    : "Discord user " + (authorName != null ? authorName : authorId);

            String userEntityId = entityId("user:" + authorId);
            addEntity(entities, new ExtractedEntity(
                    userEntityId,
                    authorName != null ? authorName : authorId,
                    entityType,
                    aliases,
                    entityDesc,
                    1.0,
                    userProps
            ));

            // User → Server (inferred membership)
            if (guildId != null) {
                relations.add(new ExtractedRelation(
                        userEntityId,
                        entityId("guild:" + guildId),
                        GraphConstants.REL_MEMBER_OF,
                        "User is a member of the server",
                        0.9, Map.of()
                ));
            }

            // User → Roles (guild member role assignments)
            Object authorRolesObj = meta.get("discord.authorRoles");
            if (authorRolesObj instanceof List<?> rolesList) {
                for (Object roleObj : rolesList) {
                    if (roleObj instanceof Map<?, ?> roleEntry) {
                        String roleId = roleEntry.get("roleId") != null ? roleEntry.get("roleId").toString() : null;
                        String roleName = roleEntry.get("roleName") != null ? roleEntry.get("roleName").toString() : null;
                        if (roleId == null) continue;
                        String roleEntityId = entityId("role:" + guildId + ":" + roleId);
                        Map<String, String> roleProps = new LinkedHashMap<>();
                        roleProps.put("roleId", roleId);
                        if (roleName != null) roleProps.put("roleName", roleName);
                        addEntity(entities, new ExtractedEntity(
                                roleEntityId,
                                roleName != null ? roleName : "Role " + roleId,
                                GraphConstants.ENTITY_DISCORD_ROLE,
                                List.of(),
                                "Discord role in server",
                                0.9,
                                roleProps
                        ));
                        relations.add(new ExtractedRelation(
                                userEntityId, roleEntityId,
                                GraphConstants.REL_HAS_ROLE,
                                (authorName != null ? authorName : authorId) + " has role " + (roleName != null ? roleName : roleId),
                                0.9, Map.of()
                        ));
                    }
                }
            }
        }

        // -- Message entity --
        if (messageId != null) {
            Map<String, String> msgProps = new LinkedHashMap<>();
            msgProps.put("messageId", messageId);
            if (timestamp != null) msgProps.put("timestamp", timestamp);
            String editedTimestamp = str(meta.get("discord.editedTimestamp"));
            if (editedTimestamp != null) msgProps.put("editedTimestamp", editedTimestamp);
            String messageType = str(meta.get("discord.messageType"));
            if (messageType != null) msgProps.put("messageType", messageType);
            Boolean pinned = meta.get("discord.pinned") instanceof Boolean b ? b : false;
            if (pinned) msgProps.put("pinned", "true");
            Object embedCount = meta.get("discord.embedCount");
            if (embedCount != null) msgProps.put("embedCount", embedCount.toString());
            Object attachmentCount = meta.get("discord.attachmentCount");
            if (attachmentCount != null) msgProps.put("attachmentCount", attachmentCount.toString());
            Object reactionCount = meta.get("discord.reactionCount");
            if (reactionCount != null) msgProps.put("reactionCount", reactionCount.toString());

            String msgContent = doc.getText();
            String description = msgContent != null && msgContent.length() > 200
                    ? msgContent.substring(0, 200) + "..."
                    : msgContent;

            addEntity(entities, new ExtractedEntity(
                    entityId("msg:" + messageId),
                    "Message " + messageId,
                    GraphConstants.ENTITY_DISCORD_MESSAGE,
                    List.of(),
                    description,
                    1.0,
                    msgProps
            ));

            // DATE entity from message timestamp
            if (timestamp != null) {
                String dateId = entityId("date:" + timestamp);
                addEntity(entities, new ExtractedEntity(
                        dateId, timestamp, GraphConstants.ENTITY_DATE,
                        List.of(), "Message date: " + timestamp, 0.85,
                        Map.of("date", timestamp, "dateType", "sent")
                ));
                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId), dateId,
                        GraphConstants.REL_PUBLISHED_ON,
                        "Message sent on " + timestamp,
                        0.85, Map.of()
                ));
            }

            // DATE entity from edited timestamp (MODIFIED_ON)
            String editedTs = str(meta.get("discord.editedTimestamp"));
            if (editedTs != null) {
                String editDateId = entityId("date:" + editedTs);
                addEntity(entities, new ExtractedEntity(
                        editDateId, editedTs, GraphConstants.ENTITY_DATE,
                        List.of(), "Message edited: " + editedTs, 0.85,
                        Map.of("date", editedTs, "dateType", "edited")
                ));
                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId), editDateId,
                        GraphConstants.REL_MODIFIED_ON,
                        "Message edited on " + editedTs,
                        0.85, Map.of()
                ));
            }

            // Message → User/Bot (SENT_BY / SENT_BY_BOT)
            if (authorId != null) {
                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId("user:" + authorId),
                        authorIsBot ? GraphConstants.REL_SENT_BY_BOT : GraphConstants.REL_SENT_BY,
                        authorIsBot ? "Message sent by bot" : "Message sent by user",
                        1.0, Map.of()
                ));
            }

            // Message → Channel (POSTED_IN)
            if (channelId != null) {
                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId("channel:" + channelId),
                        GraphConstants.REL_POSTED_IN,
                        "Message posted in channel",
                        1.0, Map.of()
                ));
            }

            // Message → Message (REPLIED_TO)
            if (replyToMessageId != null) {
                // Create a stub entity for the referenced message
                Map<String, String> replyMsgProps = new LinkedHashMap<>();
                replyMsgProps.put("messageId", replyToMessageId);
                // If the reply is cross-channel, record the source channel
                if (replyToChannelId != null && !replyToChannelId.equals(channelId)) {
                    replyMsgProps.put("channelId", replyToChannelId);
                    // Stub out the referenced channel so traversal is possible
                    addEntity(entities, new ExtractedEntity(
                            entityId("channel:" + replyToChannelId),
                            replyToChannelId,
                            GraphConstants.ENTITY_DISCORD_CHANNEL,
                            List.of(),
                            "Channel containing referenced message",
                            0.4,
                            Map.of("channelId", replyToChannelId)
                    ));
                    // Link the stub channel to the server so it's not an orphan
                    if (guildId != null) {
                        relations.add(new ExtractedRelation(
                                entityId("channel:" + replyToChannelId),
                                entityId("guild:" + guildId),
                                GraphConstants.REL_CHANNEL_IN,
                                "Channel in server",
                                0.4, Map.of()
                        ));
                    }
                    // Stub message → Stub channel (POSTED_IN)
                    relations.add(new ExtractedRelation(
                            entityId("msg:" + replyToMessageId),
                            entityId("channel:" + replyToChannelId),
                            GraphConstants.REL_POSTED_IN,
                            "Referenced message posted in channel",
                            0.4, Map.of()
                    ));
                }
                addEntity(entities, new ExtractedEntity(
                        entityId("msg:" + replyToMessageId),
                        "Message " + replyToMessageId,
                        GraphConstants.ENTITY_DISCORD_MESSAGE,
                        List.of(),
                        "Referenced message",
                        0.5,
                        replyMsgProps
                ));
                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId("msg:" + replyToMessageId),
                        GraphConstants.REL_REPLIED_TO,
                        "Message is a reply to another message",
                        1.0, Map.of()
                ));
            }

            // -- Pinned item entity and relations --
            if (pinned && channelId != null) {
                String pinnedKey = "pinned:" + messageId;
                addEntity(entities, new ExtractedEntity(
                        entityId(pinnedKey),
                        "Pinned " + messageId,
                        GraphConstants.ENTITY_PINNED_ITEM,
                        List.of(),
                        "Pinned message in channel",
                        1.0,
                        Map.of("messageId", messageId)
                ));
                relations.add(new ExtractedRelation(
                        entityId(pinnedKey),
                        entityId("channel:" + channelId),
                        GraphConstants.REL_PINNED_IN,
                        "Item pinned in channel",
                        1.0, Map.of()
                ));
                relations.add(new ExtractedRelation(
                        entityId("channel:" + channelId),
                        entityId(pinnedKey),
                        GraphConstants.REL_CONTAINS,
                        "Channel contains pinned item",
                        1.0, Map.of()
                ));
            }
        }

        // -- Attachments --
        Object attachmentsObj = meta.get("discord.attachments");
        if (attachmentsObj instanceof List<?> attList && messageId != null) {
            for (Object attObj : attList) {
                if (attObj instanceof Map<?, ?> attMap) {
                    String attId = str(attMap.get("id"));
                    String attFilename = str(attMap.get("filename"));
                    String attContentType = str(attMap.get("contentType"));
                    Object attSize = attMap.get("size");
                    String attUrl = str(attMap.get("url"));
                    Object attWidth = attMap.get("width");
                    Object attHeight = attMap.get("height");

                    if (attId != null) {
                        Map<String, String> attProps = new LinkedHashMap<>();
                        attProps.put("attachmentId", attId);
                        if (attFilename != null) attProps.put("filename", attFilename);
                        if (attContentType != null) attProps.put("contentType", attContentType);
                        if (attSize != null) attProps.put("size", attSize.toString());
                        if (attUrl != null) attProps.put("url", attUrl);
                        if (attWidth != null) attProps.put("width", attWidth.toString());
                        if (attHeight != null) attProps.put("height", attHeight.toString());

                        addEntity(entities, new ExtractedEntity(
                                entityId("attach:" + attId),
                                attFilename != null ? attFilename : "Attachment " + attId,
                                GraphConstants.ENTITY_DISCORD_ATTACHMENT,
                                List.of(),
                                "File attachment: " + (attFilename != null ? attFilename : attId),
                                1.0,
                                attProps
                        ));

                        relations.add(new ExtractedRelation(
                                entityId("msg:" + messageId),
                                entityId("attach:" + attId),
                                GraphConstants.REL_HAS_ATTACHMENT,
                                "Message has file attachment",
                                1.0, Map.of()
                        ));

                        // Attachment → Author (UPLOADED_BY)
                        if (authorId != null) {
                            relations.add(new ExtractedRelation(
                                    entityId("attach:" + attId),
                                    entityId("user:" + authorId),
                                    GraphConstants.REL_UPLOADED_BY,
                                    "Attachment uploaded by user",
                                    0.9, Map.of()
                            ));
                        }

                        // Attachment URL as EXTERNAL_RESOURCE
                        if (attUrl != null && !attUrl.isBlank()) {
                            String attUrlId = entityId("url:" + attUrl.toLowerCase());
                            addEntity(entities, new ExtractedEntity(attUrlId, attUrl,
                                    GraphConstants.ENTITY_EXTERNAL_RESOURCE, List.of(),
                                    "CDN URL for Discord attachment: " + (attFilename != null ? attFilename : attId), 0.85,
                                    Map.of("url", attUrl)));
                            relations.add(new ExtractedRelation(
                                    entityId("attach:" + attId), attUrlId,
                                    GraphConstants.REL_HYPERLINKS_TO,
                                    (attFilename != null ? attFilename : "Attachment") + " available at " + attUrl,
                                    0.85, Map.of()));
                        }
                    }
                }
            }
        }

        // -- Mentioned users --
        Object mentionUserIds = meta.get("discord.mentionUserIds");
        Object mentionUserNames = meta.get("discord.mentionUserNames");
        if (mentionUserIds instanceof List<?> idList && messageId != null) {
            List<?> nameList = mentionUserNames instanceof List<?> nl ? nl : List.of();
            for (int i = 0; i < idList.size(); i++) {
                String mentionId = str(idList.get(i));
                String mentionName = i < nameList.size() ? str(nameList.get(i)) : null;
                if (mentionId == null) continue;

                addEntity(entities, new ExtractedEntity(
                        entityId("user:" + mentionId),
                        mentionName != null ? mentionName : mentionId,
                        GraphConstants.ENTITY_DISCORD_USER,
                        List.of(),
                        "Discord user (mentioned)",
                        0.8,
                        Map.of("userId", mentionId)
                ));

                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId("user:" + mentionId),
                        GraphConstants.REL_MENTIONS_USER,
                        "Message mentions user",
                        1.0, Map.of()
                ));
            }
        }

        // -- Mentioned roles --
        Object mentionRoleIds = meta.get("discord.mentionRoleIds");
        if (mentionRoleIds instanceof List<?> roleList && messageId != null) {
            List<?> roleNames = meta.get("discord.mentionRoleNames") instanceof List<?> rn ? rn : List.of();
            List<?> roleColors = meta.get("discord.mentionRoleColors") instanceof List<?> rc ? rc : List.of();
            List<?> roleHoist = meta.get("discord.mentionRoleHoist") instanceof List<?> rh ? rh : List.of();
            List<?> rolePositions = meta.get("discord.mentionRolePositions") instanceof List<?> rp ? rp : List.of();
            List<?> roleMentionable = meta.get("discord.mentionRoleMentionable") instanceof List<?> rm ? rm : List.of();
            for (int ri = 0; ri < roleList.size(); ri++) {
                String roleId = str(roleList.get(ri));
                if (roleId == null) continue;

                String roleName = ri < roleNames.size() ? str(roleNames.get(ri)) : null;
                Map<String, String> roleProps = new LinkedHashMap<>();
                roleProps.put("roleId", roleId);
                if (roleName != null) roleProps.put("roleName", roleName);
                if (ri < roleColors.size() && roleColors.get(ri) instanceof Number color && color.intValue() != 0) {
                    roleProps.put("color", String.format("#%06X", color.intValue()));
                }
                if (ri < roleHoist.size() && Boolean.TRUE.equals(roleHoist.get(ri))) {
                    roleProps.put("hoist", "true");
                }
                if (ri < rolePositions.size() && rolePositions.get(ri) instanceof Number pos) {
                    roleProps.put("position", String.valueOf(pos.intValue()));
                }
                if (ri < roleMentionable.size() && Boolean.TRUE.equals(roleMentionable.get(ri))) {
                    roleProps.put("mentionable", "true");
                }

                // Use guild-scoped key for consistent dedup with author-role entities
                String roleEntityKey = guildId != null
                        ? "role:" + guildId + ":" + roleId : "role:" + roleId;
                addEntity(entities, new ExtractedEntity(
                        entityId(roleEntityKey),
                        roleName != null ? roleName : "Role " + roleId,
                        GraphConstants.ENTITY_DISCORD_ROLE,
                        List.of(),
                        "Discord role" + (roleName != null ? ": " + roleName : ""),
                        0.7,
                        roleProps
                ));

                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId(roleEntityKey),
                        GraphConstants.REL_MENTIONS_ROLE,
                        "Message mentions role",
                        1.0, Map.of()
                ));
            }
        }

        // -- @everyone broadcast mention → MENTIONS_CHANNEL edge to the channel --
        Object mentionEveryone = meta.get("discord.mentionEveryone");
        if (Boolean.TRUE.equals(mentionEveryone) && channelId != null && messageId != null) {
            relations.add(new ExtractedRelation(
                    entityId("msg:" + messageId),
                    entityId("channel:" + channelId),
                    GraphConstants.REL_MENTIONS_CHANNEL,
                    "Message uses @everyone broadcast mention",
                    0.9, Map.of("broadcastType", "everyone")
            ));
        }

        // -- Reactions --
        // The loader stores reactions as a List<String> in "emoji:count" or "name:id:count" format,
        // e.g. ["thumbsup:12", "customEmoji:123456:3"].
        // We extract each distinct emoji as a DISCORD_REACTION entity and link it to the message
        // via a HAS_REACTION relationship that carries the count as a property.
        // The loader also stores discord.reactionDetails (List<Map>) with animated flag for custom emoji.
        Object reactionsObj = meta.get("discord.reactions");
        // Build animated lookup from reactionDetails if available
        Map<String, Boolean> animatedByDisplay = new LinkedHashMap<>();
        Object detailsObj = meta.get("discord.reactionDetails");
        if (detailsObj instanceof List<?> detailsList) {
            for (Object dObj : detailsList) {
                if (dObj instanceof Map<?, ?> dMap) {
                    String disp = dMap.get("display") != null ? dMap.get("display").toString() : null;
                    if (disp != null && Boolean.TRUE.equals(dMap.get("animated"))) {
                        animatedByDisplay.put(disp, true);
                    }
                }
            }
        }
        if (reactionsObj instanceof List<?> reactionList && messageId != null) {
            for (Object reactionObj : reactionList) {
                String reactionStr = str(reactionObj);
                if (reactionStr == null) continue;

                // Format from loader: "<emojiDisplay>:<count>"
                // where emojiDisplay is either "name" (unicode) or "name:id" (custom).
                // We split from the last colon to isolate the count.
                int lastColon = reactionStr.lastIndexOf(':');
                if (lastColon <= 0) continue;

                String emojiDisplay = reactionStr.substring(0, lastColon);
                String countStr = reactionStr.substring(lastColon + 1);
                int count;
                try {
                    count = Integer.parseInt(countStr);
                } catch (NumberFormatException e) {
                    continue; // malformed entry
                }

                // Determine if this is a custom emoji (has an id segment) or unicode
                String emojiId = null;
                String emojiName = emojiDisplay;
                int innerColon = emojiDisplay.lastIndexOf(':');
                if (innerColon > 0) {
                    // custom emoji: "name:id"
                    emojiName = emojiDisplay.substring(0, innerColon);
                    emojiId = emojiDisplay.substring(innerColon + 1);
                }

                // Stable entity key: custom emoji by ID, unicode emoji by name
                String reactionKey = emojiId != null ? "reaction:custom:" + emojiId : "reaction:unicode:" + emojiName;

                Map<String, String> reactionProps = new LinkedHashMap<>();
                reactionProps.put("emojiName", emojiName);
                if (emojiId != null) reactionProps.put("emojiId", emojiId);
                reactionProps.put("emojiDisplay", emojiDisplay);
                if (animatedByDisplay.containsKey(emojiDisplay)) {
                    reactionProps.put("animated", "true");
                }

                addEntity(entities, new ExtractedEntity(
                        entityId(reactionKey),
                        emojiDisplay,
                        GraphConstants.ENTITY_DISCORD_REACTION,
                        List.of(),
                        "Discord reaction emoji: " + emojiDisplay,
                        1.0,
                        reactionProps
                ));

                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId(reactionKey),
                        GraphConstants.REL_HAS_REACTION,
                        "Message received " + count + " reaction(s) with " + emojiDisplay,
                        1.0,
                        Map.of("count", String.valueOf(count))
                ));
            }
        }

        // -- Embeds --
        Object embedsObj = meta.get("discord.embeds");
        if (embedsObj instanceof List<?> embedList && messageId != null) {
            for (Object embedObj : embedList) {
                if (!(embedObj instanceof Map<?, ?> embedMap)) continue;

                Object idxObj = embedMap.get("index");
                String embedIdx = idxObj != null ? idxObj.toString() : "0";
                String embedTitle = str(embedMap.get("title"));
                String embedType = str(embedMap.get("type"));
                String embedDesc = str(embedMap.get("description"));
                String embedUrl = str(embedMap.get("url"));
                String embedAuthor = str(embedMap.get("authorName"));
                String embedAuthorUrl = str(embedMap.get("authorUrl"));
                String embedFooter = str(embedMap.get("footerText"));
                String embedImageUrl = str(embedMap.get("imageUrl"));
                String embedThumbUrl = str(embedMap.get("thumbnailUrl"));

                // Use message ID + index as the stable key
                String embedKey = "embed:" + messageId + ":" + embedIdx;
                String embedName = embedTitle != null ? embedTitle
                        : (embedType != null ? "Embed (" + embedType + ")" : "Embed " + embedIdx);

                Map<String, String> embedProps = new LinkedHashMap<>();
                embedProps.put("messageId", messageId);
                embedProps.put("embedIndex", embedIdx);
                if (embedType != null) embedProps.put("embedType", embedType);
                if (embedTitle != null) embedProps.put("title", embedTitle);
                if (embedUrl != null) embedProps.put("url", embedUrl);
                if (embedAuthor != null) embedProps.put("authorName", embedAuthor);
                if (embedAuthorUrl != null) embedProps.put("authorUrl", embedAuthorUrl);
                if (embedFooter != null) embedProps.put("footerText", embedFooter);
                if (embedImageUrl != null) embedProps.put("imageUrl", embedImageUrl);
                if (embedThumbUrl != null) embedProps.put("thumbnailUrl", embedThumbUrl);
                String embedVideoUrl = str(embedMap.get("videoUrl"));
                String embedVideoWidth = str(embedMap.get("videoWidth"));
                String embedVideoHeight = str(embedMap.get("videoHeight"));
                String embedProviderName = str(embedMap.get("providerName"));
                String embedProviderUrl = str(embedMap.get("providerUrl"));
                if (embedVideoUrl != null) embedProps.put("videoUrl", embedVideoUrl);
                if (embedVideoWidth != null) embedProps.put("videoWidth", embedVideoWidth);
                if (embedVideoHeight != null) embedProps.put("videoHeight", embedVideoHeight);
                if (embedProviderName != null) embedProps.put("providerName", embedProviderName);
                if (embedProviderUrl != null) embedProps.put("providerUrl", embedProviderUrl);
                if (embedDesc != null) embedProps.put("description", embedDesc);
                Object fieldCount = embedMap.get("fieldCount");
                if (fieldCount != null) embedProps.put("fieldCount", fieldCount.toString());

                // Capture embed fields (name/value pairs)
                Object fieldsObj = embedMap.get("fields");
                if (fieldsObj instanceof List<?> fieldsList) {
                    for (int fi = 0; fi < fieldsList.size(); fi++) {
                        Object fieldObj = fieldsList.get(fi);
                        if (fieldObj instanceof Map<?, ?> fieldMap) {
                            String fieldName = str(fieldMap.get("name"));
                            String fieldValue = str(fieldMap.get("value"));
                            if (fieldName != null) embedProps.put("field." + fi + ".name", fieldName);
                            if (fieldValue != null) embedProps.put("field." + fi + ".value", fieldValue);
                        }
                    }
                }

                String description = embedDesc != null
                        ? (embedDesc.length() > 200 ? embedDesc.substring(0, 200) + "..." : embedDesc)
                        : "Discord embed in message " + messageId;

                addEntity(entities, new ExtractedEntity(
                        entityId(embedKey),
                        embedName,
                        GraphConstants.ENTITY_DISCORD_EMBED,
                        List.of(),
                        description,
                        0.95,
                        embedProps
                ));

                relations.add(new ExtractedRelation(
                        entityId("msg:" + messageId),
                        entityId(embedKey),
                        GraphConstants.REL_HAS_EMBED,
                        "Message has embed: " + embedName,
                        1.0, Map.of()
                ));

                // Promote embed URLs to EXTERNAL_RESOURCE entities
                String embedEntityId = entityId(embedKey);
                for (String urlField : new String[]{embedUrl, embedAuthorUrl, embedImageUrl,
                        embedThumbUrl, embedVideoUrl, embedProviderUrl}) {
                    if (urlField != null && (urlField.startsWith("http://") || urlField.startsWith("https://"))) {
                        String urlEntityId = entityId("url:" + urlField.toLowerCase());
                        addEntity(entities, new ExtractedEntity(
                                urlEntityId, urlField,
                                GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                List.of(), "Embed resource: " + urlField, 0.8,
                                Map.of("url", urlField, GraphConstants.PROP_SOURCE_FIELD, "discord.embed")));
                        relations.add(new ExtractedRelation(
                                embedEntityId, urlEntityId,
                                GraphConstants.REL_HYPERLINKS_TO,
                                "Embed links to: " + urlField, 0.8, Map.of()));
                    }
                }
            }
        }

        // -- Extract URLs from message body → HYPERLINKS_TO → EXTERNAL_RESOURCE --
        if (messageId != null) {
            String bodyText = doc.getText();
            if (bodyText != null && !bodyText.isBlank()) {
                Set<String> seenUrls = new LinkedHashSet<>();
                Matcher urlMatcher = URL_PATTERN.matcher(bodyText);
                while (urlMatcher.find() && seenUrls.size() < 30) {
                    String url = urlMatcher.group();
                    if (seenUrls.add(url)) {
                        String urlEntityId = entityId("url:" + url);
                        addEntity(entities, new ExtractedEntity(
                                urlEntityId, url, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                                List.of(), "URL shared in Discord message", 0.8,
                                Map.of("url", url)
                        ));
                        relations.add(new ExtractedRelation(
                                entityId("msg:" + messageId), urlEntityId,
                                GraphConstants.REL_HYPERLINKS_TO,
                                "Message contains link to " + url,
                                0.8, Map.of()
                        ));
                    }
                }
            }
        }

        // -- Standalone attachment CrawlItems (flat key format) --
        // When DiscordCrawler/DiscordAttachmentExtractor produces a dedicated attachment document,
        // it uses flat keys (discord.attachmentId, discord.attachmentFilename, etc.) instead of
        // the discord.attachments list-of-maps format.
        if (messageId == null && attachmentsObj == null) {
            String standaloneAttId = str(meta.get("discord.attachmentId"));
            if (standaloneAttId != null) {
                String attFilename = str(meta.get("discord.attachmentFilename"));
                String attContentType = str(meta.get("discord.attachmentContentType"));
                Object attSize = meta.get("discord.attachmentSize");
                String attUrl = str(meta.get("discord.attachmentUrl"));
                String parentMsgId = str(meta.get("discord.parentMessageId"));

                Map<String, String> attProps = new LinkedHashMap<>();
                attProps.put("attachmentId", standaloneAttId);
                if (attFilename != null) attProps.put("filename", attFilename);
                if (attContentType != null) attProps.put("contentType", attContentType);
                if (attSize != null) attProps.put("size", attSize.toString());
                if (attUrl != null) attProps.put("url", attUrl);

                addEntity(entities, new ExtractedEntity(
                        entityId("attach:" + standaloneAttId),
                        attFilename != null ? attFilename : "Attachment " + standaloneAttId,
                        GraphConstants.ENTITY_DISCORD_ATTACHMENT,
                        List.of(),
                        "File attachment: " + (attFilename != null ? attFilename : standaloneAttId),
                        1.0,
                        attProps
                ));

                // Link to parent message if available
                if (parentMsgId != null) {
                    String parentAuthorName = str(meta.get("discord.parentAuthorName"));
                    String parentAuthorId = str(meta.get("discord.parentAuthorId"));
                    String parentTimestamp = str(meta.get("discord.parentTimestamp"));

                    Map<String, String> parentMsgProps = new LinkedHashMap<>();
                    parentMsgProps.put("messageId", parentMsgId);
                    if (parentTimestamp != null) parentMsgProps.put("timestamp", parentTimestamp);

                    addEntity(entities, new ExtractedEntity(
                            entityId("msg:" + parentMsgId),
                            parentAuthorName != null ? "Message by " + parentAuthorName : "Message " + parentMsgId,
                            GraphConstants.ENTITY_DISCORD_MESSAGE,
                            List.of(),
                            "Parent message for attachment",
                            0.5,
                            parentMsgProps
                    ));
                    relations.add(new ExtractedRelation(
                            entityId("msg:" + parentMsgId),
                            entityId("attach:" + standaloneAttId),
                            GraphConstants.REL_HAS_ATTACHMENT,
                            "Message has file attachment",
                            1.0, Map.of()
                    ));

                    // DATE entity for parent message timestamp
                    if (parentTimestamp != null) {
                        String parentDateId = entityId("date:" + parentTimestamp);
                        addEntity(entities, new ExtractedEntity(
                                parentDateId, parentTimestamp, GraphConstants.ENTITY_DATE,
                                List.of(), "Message date: " + parentTimestamp, 0.85,
                                Map.of("date", parentTimestamp, "dateType", "sent")
                        ));
                        relations.add(new ExtractedRelation(
                                entityId("msg:" + parentMsgId), parentDateId,
                                GraphConstants.REL_PUBLISHED_ON,
                                "Parent message sent on " + parentTimestamp,
                                0.85, Map.of()
                        ));
                    }

                    // Standalone attachment → Author (UPLOADED_BY)
                    if (parentAuthorId != null) {
                        relations.add(new ExtractedRelation(
                                entityId("attach:" + standaloneAttId),
                                entityId("user:" + parentAuthorId),
                                GraphConstants.REL_UPLOADED_BY,
                                "Attachment uploaded by user",
                                0.9, Map.of()
                        ));
                    }

                    // Link parent message to its author
                    if (parentAuthorId != null) {
                        Map<String, String> authorProps = new LinkedHashMap<>();
                        authorProps.put("userId", parentAuthorId);
                        if (parentAuthorName != null) authorProps.put("displayName", parentAuthorName);
                        addEntity(entities, new ExtractedEntity(
                                entityId("user:" + parentAuthorId),
                                parentAuthorName != null ? parentAuthorName : parentAuthorId,
                                GraphConstants.ENTITY_DISCORD_USER,
                                List.of(),
                                "Discord user",
                                0.7,
                                authorProps
                        ));
                        relations.add(new ExtractedRelation(
                                entityId("msg:" + parentMsgId),
                                entityId("user:" + parentAuthorId),
                                GraphConstants.REL_SENT_BY,
                                "Message sent by user",
                                0.7, Map.of()
                        ));
                    }
                }

                // Link standalone attachment to its channel if available
                // Only use channelId for entity key — never use channel name as an ID
                String parentChannelName2 = str(meta.get("discord.parentChannelName"));
                if (channelId != null) {
                    String chId = channelId;
                    String chName = channelName != null ? channelName : parentChannelName2;
                    if (chId != null) {
                        addEntity(entities, new ExtractedEntity(
                                entityId("channel:" + chId),
                                chName != null ? chName : chId,
                                GraphConstants.ENTITY_DISCORD_CHANNEL,
                                List.of(),
                                "Discord channel",
                                0.5,
                                Map.of("channelId", chId)
                        ));
                        if (parentMsgId != null) {
                            relations.add(new ExtractedRelation(
                                    entityId("msg:" + parentMsgId),
                                    entityId("channel:" + chId),
                                    GraphConstants.REL_POSTED_IN,
                                    "Message posted in channel",
                                    0.5, Map.of()
                            ));
                        }
                        // Link channel to server for standalone attachment documents
                        if (guildId != null) {
                            relations.add(new ExtractedRelation(
                                    entityId("channel:" + chId),
                                    entityId("guild:" + guildId),
                                    GraphConstants.REL_CHANNEL_IN,
                                    "Channel in server",
                                    0.5, Map.of()
                            ));
                        }
                    }
                }

                // Standalone attachment URL as EXTERNAL_RESOURCE
                if (attUrl != null && !attUrl.isBlank()) {
                    String attUrlId = entityId("url:" + attUrl.toLowerCase());
                    addEntity(entities, new ExtractedEntity(attUrlId, attUrl,
                            GraphConstants.ENTITY_EXTERNAL_RESOURCE, List.of(),
                            "CDN URL for Discord attachment: " + (attFilename != null ? attFilename : standaloneAttId), 0.85,
                            Map.of("url", attUrl)));
                    relations.add(new ExtractedRelation(
                            entityId("attach:" + standaloneAttId), attUrlId,
                            GraphConstants.REL_HYPERLINKS_TO,
                            (attFilename != null ? attFilename : "Attachment") + " available at " + attUrl,
                            0.85, Map.of()));
                }
            }
        }

        String sourceId = str(meta.get(GraphConstants.META_SOURCE_ID));
        ExtractionMetadata extractionMeta = ExtractionMetadata.forChunk(
                messageId, sourceId, "discord-rule-extractor"
        );

        return ExtractionResult.of(
                new ArrayList<>(entities.values()),
                relations,
                extractionMeta
        );
    }

    /**
     * Extract and merge graphs from a batch of Discord documents.
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
                ExtractionMetadata.forChunk(null, null, "discord-rule-extractor")
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

        // Merge: union aliases, keep higher confidence, prefer non-generic name
        Set<String> aliases = new LinkedHashSet<>();
        if (existing.aliases() != null) aliases.addAll(existing.aliases());
        if (entity.aliases() != null) aliases.addAll(entity.aliases());

        String name = existing.name();
        // Prefer a real name over a generic "Message X" or bare ID
        if (name.startsWith("Message ") || name.startsWith("Role ") || name.matches("\\d+")) {
            if (!entity.name().startsWith("Message ") && !entity.name().startsWith("Role ")
                    && !entity.name().matches("\\d+")) {
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

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }
}
