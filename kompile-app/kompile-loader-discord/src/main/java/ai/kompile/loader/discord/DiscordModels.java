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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Discord REST API v10 data models.
 */
public final class DiscordModels {

    private DiscordModels() {}

    /** Discord snowflake ID epoch (2015-01-01T00:00:00.000Z) */
    public static final long DISCORD_EPOCH = 1420070400000L;

    /**
     * Extracts the Unix timestamp (millis) from a Discord snowflake ID.
     */
    public static long snowflakeToTimestamp(String snowflakeId) {
        long snowflake = Long.parseUnsignedLong(snowflakeId);
        return (snowflake >> 22) + DISCORD_EPOCH;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Guild(
            String id,
            String name,
            @JsonProperty("icon") String iconHash,
            @JsonProperty("owner_id") String ownerId,
            String description,
            @JsonProperty("member_count") int memberCount,
            @JsonProperty("preferred_locale") String preferredLocale
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Channel(
            String id,
            int type,
            @JsonProperty("guild_id") String guildId,
            String name,
            String topic,
            int position,
            @JsonProperty("parent_id") String parentId,
            @JsonProperty("last_message_id") String lastMessageId,
            @JsonProperty("message_count") Integer messageCount,
            @JsonProperty("thread_metadata") ThreadMetadata threadMetadata
    ) {
        /** Channel type constants from Discord API */
        public static final int TYPE_GUILD_TEXT = 0;
        public static final int TYPE_DM = 1;
        public static final int TYPE_GUILD_VOICE = 2;
        public static final int TYPE_GROUP_DM = 3;
        public static final int TYPE_GUILD_CATEGORY = 4;
        public static final int TYPE_GUILD_ANNOUNCEMENT = 5;
        public static final int TYPE_ANNOUNCEMENT_THREAD = 10;
        public static final int TYPE_PUBLIC_THREAD = 11;
        public static final int TYPE_PRIVATE_THREAD = 12;
        public static final int TYPE_GUILD_STAGE_VOICE = 13;
        public static final int TYPE_GUILD_FORUM = 15;
        public static final int TYPE_GUILD_MEDIA = 16;

        public boolean isTextBased() {
            return type == TYPE_GUILD_TEXT || type == TYPE_GUILD_ANNOUNCEMENT
                    || type == TYPE_GUILD_FORUM;
        }

        public boolean isThread() {
            return type == TYPE_PUBLIC_THREAD || type == TYPE_PRIVATE_THREAD
                    || type == TYPE_ANNOUNCEMENT_THREAD;
        }

        public String typeName() {
            return switch (type) {
                case TYPE_GUILD_TEXT -> "text";
                case TYPE_DM -> "dm";
                case TYPE_GUILD_VOICE -> "voice";
                case TYPE_GROUP_DM -> "group_dm";
                case TYPE_GUILD_CATEGORY -> "category";
                case TYPE_GUILD_ANNOUNCEMENT -> "announcement";
                case TYPE_ANNOUNCEMENT_THREAD -> "announcement_thread";
                case TYPE_PUBLIC_THREAD -> "public_thread";
                case TYPE_PRIVATE_THREAD -> "private_thread";
                case TYPE_GUILD_STAGE_VOICE -> "stage_voice";
                case TYPE_GUILD_FORUM -> "forum";
                case TYPE_GUILD_MEDIA -> "media";
                default -> "unknown(" + type + ")";
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadMetadata(
            boolean archived,
            @JsonProperty("auto_archive_duration") int autoArchiveDuration,
            @JsonProperty("archive_timestamp") String archiveTimestamp,
            boolean locked
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String id,
            @JsonProperty("channel_id") String channelId,
            User author,
            String content,
            String timestamp,
            @JsonProperty("edited_timestamp") String editedTimestamp,
            int type,
            @JsonProperty("message_reference") MessageReference messageReference,
            List<Attachment> attachments,
            List<Embed> embeds,
            List<Reaction> reactions,
            List<User> mentions,
            @JsonProperty("mention_roles") List<String> mentionRoles,
            @JsonProperty("mention_everyone") boolean mentionEveryone,
            boolean pinned,
            @JsonProperty("thread") Channel thread
    ) {
        /** Message type constants */
        public static final int TYPE_DEFAULT = 0;
        public static final int TYPE_REPLY = 19;
        public static final int TYPE_THREAD_STARTER = 21;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageReference(
            @JsonProperty("message_id") String messageId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("guild_id") String guildId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(
            String id,
            String username,
            String discriminator,
            @JsonProperty("global_name") String globalName,
            String avatar,
            boolean bot
    ) {
        public String displayName() {
            if (globalName != null && !globalName.isEmpty()) return globalName;
            return username;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(
            User user,
            String nick,
            List<String> roles,
            @JsonProperty("joined_at") String joinedAt
    ) {
        public String displayName() {
            if (nick != null && !nick.isEmpty()) return nick;
            if (user != null) return user.displayName();
            return "Unknown";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Role(
            String id,
            String name,
            int color,
            boolean hoist,
            int position,
            String permissions,
            boolean managed,
            boolean mentionable
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attachment(
            String id,
            String filename,
            @JsonProperty("content_type") String contentType,
            long size,
            String url,
            @JsonProperty("proxy_url") String proxyUrl,
            Integer width,
            Integer height
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Embed(
            String type,
            String title,
            String description,
            String url,
            EmbedAuthor author,
            EmbedFooter footer,
            List<EmbedField> fields,
            EmbedImage image,
            EmbedThumbnail thumbnail,
            EmbedVideo video,
            EmbedProvider provider
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedAuthor(String name, String url, @JsonProperty("icon_url") String iconUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedFooter(String text, @JsonProperty("icon_url") String iconUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedField(String name, String value, boolean inline) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedImage(String url, int width, int height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedThumbnail(String url, int width, int height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedVideo(String url, int width, int height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedProvider(String name, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reaction(
            int count,
            boolean me,
            Emoji emoji
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Emoji(
            String id,
            String name,
            boolean animated
    ) {
        public String display() {
            if (id != null) return name + ":" + id;
            return name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadListResponse(
            List<Channel> threads,
            List<ThreadMember> members,
            @JsonProperty("has_more") boolean hasMore
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadMember(
            String id,
            @JsonProperty("user_id") String userId,
            @JsonProperty("join_timestamp") String joinTimestamp
    ) {}
}
