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

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.loader.discord.DiscordModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Discord DocumentLoader that fetches messages from a Discord server via the REST API.
 * Supports both real-time channel loading (DISCORD) and historical bulk loading (DISCORD_HISTORY).
 *
 * <p>Configuration is passed via {@link DocumentSourceDescriptor#getMetadata()}:
 * <ul>
 *   <li>{@code botToken} - Discord bot token (required)</li>
 *   <li>{@code guildId} - Server/guild ID (required, or use pathOrUrl)</li>
 *   <li>{@code channelIds} - Comma-separated channel IDs to load (empty = all text channels)</li>
 *   <li>{@code includeThreads} - Whether to crawl threads (default true)</li>
 *   <li>{@code includeAttachments} - Whether to include attachment metadata (default true)</li>
 *   <li>{@code daysBack} - Number of days of history to fetch (default 30)</li>
 *   <li>{@code maxMessages} - Max messages per channel (default 0 = unlimited)</li>
 *   <li>{@code startDate} - ISO date to start from (overrides daysBack)</li>
 *   <li>{@code endDate} - ISO date to end at</li>
 * </ul>
 */
@Slf4j
@Component
public class DiscordLoaderImpl implements DocumentLoader {

    private final Map<String, String> userDisplayNameCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "Discord Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor.getType() == SourceType.DISCORD
                || sourceDescriptor.getType() == SourceType.DISCORD_HISTORY;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor, Consumer<LoaderProgress> progressCallback)
            throws Exception {
        Map<String, Object> meta = sourceDescriptor.getMetadata() != null
                ? sourceDescriptor.getMetadata() : Map.of();

        String botToken = str(meta.get("botToken"));
        if (botToken == null || botToken.isEmpty()) {
            throw new IllegalArgumentException("Discord bot token is required (metadata key: botToken)");
        }

        String guildId = str(meta.get("guildId"));
        if ((guildId == null || guildId.isEmpty()) && sourceDescriptor.getPathOrUrl() != null) {
            guildId = sourceDescriptor.getPathOrUrl();
        }
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("Discord guild ID is required (metadata key: guildId or pathOrUrl)");
        }

        boolean includeThreads = boolVal(meta.get("includeThreads"), true);
        boolean includeAttachments = boolVal(meta.get("includeAttachments"), true);
        int maxMessages = intVal(meta.get("maxMessages"), 0);
        int daysBack = intVal(meta.get("daysBack"), 30);

        // Calculate time bounds
        String afterSnowflake = computeAfterSnowflake(meta, daysBack);
        String beforeSnowflake = computeBeforeSnowflake(meta);

        Duration rateLimitDelay = Duration.ofMillis(intVal(meta.get("rateLimitDelayMs"), 500));
        DiscordApiService api = new DiscordApiService(botToken, rateLimitDelay);

        Guild guild = api.getGuild(guildId);
        log.info("Loading Discord server: {} ({})", guild.name(), guild.id());

        // Fetch guild roles once for role name resolution in mention metadata
        Map<String, DiscordModels.Role> roleMap = new HashMap<>();
        try {
            List<DiscordModels.Role> roles = api.getGuildRoles(guildId);
            for (DiscordModels.Role role : roles) {
                roleMap.put(role.id(), role);
            }
            log.debug("Fetched {} guild roles", roleMap.size());
        } catch (Exception e) {
            log.debug("Could not fetch guild roles: {}", e.getMessage());
        }

        notifyProgress(progressCallback, "Fetching channels", 5, "Loading channel list");

        // Determine channels to crawl
        List<Channel> channels = api.getGuildChannels(guildId);
        List<Channel> targetChannels = filterTargetChannels(channels, meta);
        log.info("Found {} text channels to load", targetChannels.size());

        // Collect threads if requested
        List<Channel> threads = new ArrayList<>();
        if (includeThreads) {
            threads.addAll(api.getActiveThreads(guildId));
            for (Channel ch : targetChannels) {
                threads.addAll(api.getArchivedPublicThreads(ch.id()));
                threads.addAll(api.getArchivedPrivateThreads(ch.id()));
            }
            log.info("Found {} threads to load", threads.size());
        }

        List<Document> documents = new ArrayList<>();
        int totalChannels = targetChannels.size() + threads.size();
        int processedChannels = 0;

        // Load messages from each channel
        for (Channel channel : targetChannels) {
            if (Thread.currentThread().isInterrupted()) break;
            processedChannels++;
            int pct = 10 + (80 * processedChannels / Math.max(totalChannels, 1));
            notifyProgress(progressCallback, "Loading messages", pct,
                    "Channel: #" + channel.name() + " (" + processedChannels + "/" + totalChannels + ")");

            List<Message> messages = api.getChannelMessages(channel.id(), maxMessages, afterSnowflake, beforeSnowflake);
            log.info("Loaded {} messages from #{}", messages.size(), channel.name());

            for (Message msg : messages) {
                documents.add(convertMessageToDocument(msg, channel, guild, sourceDescriptor, includeAttachments, roleMap));
            }
        }

        // Load messages from threads
        for (Channel thread : threads) {
            if (Thread.currentThread().isInterrupted()) break;
            processedChannels++;
            int pct = 10 + (80 * processedChannels / Math.max(totalChannels, 1));
            notifyProgress(progressCallback, "Loading threads", pct,
                    "Thread: " + thread.name() + " (" + processedChannels + "/" + totalChannels + ")");

            List<Message> messages = api.getChannelMessages(thread.id(), maxMessages, afterSnowflake, beforeSnowflake);
            log.info("Loaded {} messages from thread '{}'", messages.size(), thread.name());

            for (Message msg : messages) {
                documents.add(convertMessageToDocument(msg, thread, guild, sourceDescriptor, includeAttachments, roleMap));
            }
        }

        notifyProgress(progressCallback, "Complete", 100,
                "Loaded " + documents.size() + " messages from " + guild.name());
        log.info("Discord loading complete: {} documents from server '{}'", documents.size(), guild.name());
        return documents;
    }

    private Document convertMessageToDocument(Message msg, Channel channel, Guild guild,
                                              DocumentSourceDescriptor sourceDescriptor,
                                              boolean includeAttachments,
                                              Map<String, DiscordModels.Role> roleMap) {
        StringBuilder content = new StringBuilder();
        String authorName = msg.author() != null ? msg.author().displayName() : "Unknown";

        if (msg.author() != null) {
            userDisplayNameCache.put(msg.author().id(), authorName);
        }

        // Build readable content
        content.append(authorName);
        if (msg.timestamp() != null) {
            content.append(" [").append(msg.timestamp()).append("]");
        }
        content.append(": ");

        if (msg.content() != null && !msg.content().isEmpty()) {
            content.append(msg.content());
        } else {
            content.append("[no text content]");
        }

        // Append embed summaries
        if (msg.embeds() != null && !msg.embeds().isEmpty()) {
            for (Embed embed : msg.embeds()) {
                content.append("\n  [Embed");
                if (embed.title() != null) content.append(": ").append(embed.title());
                if (embed.description() != null) content.append(" - ").append(embed.description());
                content.append("]");
            }
        }

        // Append attachment info
        if (includeAttachments && msg.attachments() != null && !msg.attachments().isEmpty()) {
            for (Attachment att : msg.attachments()) {
                content.append("\n  [Attachment: ").append(att.filename());
                if (att.contentType() != null) content.append(" (").append(att.contentType()).append(")");
                content.append(", ").append(att.size()).append(" bytes]");
            }
        }

        Document doc = new Document(content.toString());
        Map<String, Object> metadata = doc.getMetadata();

        // Core identifiers
        String sourcePath = "discord:" + guild.id() + "/" + channel.id() + "/" + msg.id();
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, sourceDescriptor.getType().name());
        metadata.put(GraphConstants.META_LOADER, getName());
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "discord_message");
        metadata.put(GraphConstants.META_FILE_NAME, "Discord message " + msg.id());
        metadata.put("discord.messageId", msg.id());
        metadata.put("discord.channelId", channel.id());
        metadata.put("discord.channelName", channel.name() != null ? channel.name() : "");
        metadata.put("discord.channelType", channel.typeName());
        if (channel.topic() != null && !channel.topic().isEmpty()) {
            metadata.put("discord.channelTopic", channel.topic());
        }
        if (channel.messageCount() != null && channel.messageCount() > 0) {
            metadata.put("discord.channelMessageCount", channel.messageCount());
        }
        metadata.put("discord.guildId", guild.id());
        metadata.put("discord.guildName", guild.name());
        if (guild.description() != null) {
            metadata.put("discord.guildDescription", guild.description());
        }
        if (guild.preferredLocale() != null) {
            metadata.put("discord.guildLocale", guild.preferredLocale());
        }
        if (guild.memberCount() > 0) {
            metadata.put("discord.guildMemberCount", guild.memberCount());
        }
        if (guild.ownerId() != null) {
            metadata.put("discord.guildOwnerId", guild.ownerId());
        }
        metadata.put("discord.timestamp", msg.timestamp() != null ? msg.timestamp() : "");
        metadata.put("discord.messageType", msg.type());

        // Author info
        if (msg.author() != null) {
            metadata.put("discord.authorId", msg.author().id());
            metadata.put("discord.authorName", authorName);
            metadata.put("discord.authorUsername", msg.author().username());
            metadata.put("discord.authorIsBot", msg.author().bot());
        }

        // Thread info
        if (channel.isThread()) {
            metadata.put("discord.isThread", true);
            if (channel.parentId() != null) {
                metadata.put("discord.parentChannelId", channel.parentId());
            }
            if (channel.threadMetadata() != null) {
                DiscordModels.ThreadMetadata tm = channel.threadMetadata();
                metadata.put("discord.threadArchived", tm.archived());
                metadata.put("discord.threadAutoArchiveDuration", tm.autoArchiveDuration());
                if (tm.archiveTimestamp() != null) {
                    metadata.put("discord.threadArchiveTimestamp", tm.archiveTimestamp());
                }
                metadata.put("discord.threadLocked", tm.locked());
            }
        }

        // Reply info
        if (msg.messageReference() != null && msg.messageReference().messageId() != null) {
            metadata.put("discord.replyToMessageId", msg.messageReference().messageId());
            if (msg.messageReference().channelId() != null) {
                metadata.put("discord.replyToChannelId", msg.messageReference().channelId());
            }
        }

        // Edit timestamp
        if (msg.editedTimestamp() != null) {
            metadata.put("discord.editedTimestamp", msg.editedTimestamp());
        }

        // Pinned
        metadata.put("discord.pinned", msg.pinned());

        // Mentions
        if (msg.mentions() != null && !msg.mentions().isEmpty()) {
            List<String> mentionIds = msg.mentions().stream().map(User::id).toList();
            List<String> mentionNames = msg.mentions().stream().map(User::displayName).toList();
            metadata.put("discord.mentionUserIds", mentionIds);
            metadata.put("discord.mentionUserNames", mentionNames);
        }
        if (msg.mentionRoles() != null && !msg.mentionRoles().isEmpty()) {
            metadata.put("discord.mentionRoleIds", msg.mentionRoles());
            if (roleMap != null && !roleMap.isEmpty()) {
                List<String> roleNames = new ArrayList<>();
                List<Integer> roleColors = new ArrayList<>();
                for (String roleId : msg.mentionRoles()) {
                    DiscordModels.Role role = roleMap.get(roleId);
                    roleNames.add(role != null ? role.name() : null);
                    roleColors.add(role != null ? role.color() : 0);
                }
                metadata.put("discord.mentionRoleNames", roleNames);
                metadata.put("discord.mentionRoleColors", roleColors);
            }
        }

        // Reactions summary (includes animated flag for custom emoji)
        if (msg.reactions() != null && !msg.reactions().isEmpty()) {
            List<Map<String, Object>> reactionDetails = new ArrayList<>();
            List<String> reactionSummary = new ArrayList<>();
            for (var r : msg.reactions()) {
                reactionSummary.add(r.emoji().display() + ":" + r.count());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("display", r.emoji().display());
                detail.put("count", r.count());
                if (r.emoji().id() != null) detail.put("emojiId", r.emoji().id());
                if (r.emoji().name() != null) detail.put("emojiName", r.emoji().name());
                if (r.emoji().animated()) detail.put("animated", true);
                reactionDetails.add(detail);
            }
            metadata.put("discord.reactions", reactionSummary);
            metadata.put("discord.reactionDetails", reactionDetails);
            int totalReactions = msg.reactions().stream().mapToInt(DiscordModels.Reaction::count).sum();
            metadata.put("discord.reactionCount", totalReactions);
        }

        // Attachments
        if (includeAttachments && msg.attachments() != null && !msg.attachments().isEmpty()) {
            metadata.put("discord.attachmentCount", msg.attachments().size());
            List<Map<String, Object>> attachmentMeta = new ArrayList<>();
            for (Attachment att : msg.attachments()) {
                Map<String, Object> attMap = new LinkedHashMap<>();
                attMap.put("id", att.id());
                attMap.put("filename", att.filename());
                attMap.put("contentType", att.contentType());
                attMap.put("size", att.size());
                attMap.put("url", att.url());
                if (att.width() != null) attMap.put("width", att.width());
                if (att.height() != null) attMap.put("height", att.height());
                attachmentMeta.add(attMap);
            }
            metadata.put("discord.attachments", attachmentMeta);
        }

        // Embeds
        if (msg.embeds() != null && !msg.embeds().isEmpty()) {
            metadata.put("discord.embedCount", msg.embeds().size());
            List<Map<String, Object>> embedMeta = new ArrayList<>();
            for (int i = 0; i < msg.embeds().size(); i++) {
                Embed embed = msg.embeds().get(i);
                Map<String, Object> embedMap = new LinkedHashMap<>();
                embedMap.put("index", i);
                if (embed.type() != null) embedMap.put("type", embed.type());
                if (embed.title() != null) embedMap.put("title", embed.title());
                if (embed.description() != null) embedMap.put("description", embed.description());
                if (embed.url() != null) embedMap.put("url", embed.url());
                if (embed.author() != null && embed.author().name() != null) {
                    embedMap.put("authorName", embed.author().name());
                    if (embed.author().url() != null) embedMap.put("authorUrl", embed.author().url());
                }
                if (embed.footer() != null && embed.footer().text() != null) {
                    embedMap.put("footerText", embed.footer().text());
                }
                if (embed.fields() != null && !embed.fields().isEmpty()) {
                    embedMap.put("fieldCount", embed.fields().size());
                    List<Map<String, Object>> fieldsList = new ArrayList<>();
                    for (EmbedField field : embed.fields()) {
                        Map<String, Object> fm = new LinkedHashMap<>();
                        if (field.name() != null) fm.put("name", field.name());
                        if (field.value() != null) fm.put("value", field.value());
                        fm.put("inline", field.inline());
                        fieldsList.add(fm);
                    }
                    embedMap.put("fields", fieldsList);
                }
                if (embed.image() != null && embed.image().url() != null) {
                    embedMap.put("imageUrl", embed.image().url());
                }
                if (embed.thumbnail() != null && embed.thumbnail().url() != null) {
                    embedMap.put("thumbnailUrl", embed.thumbnail().url());
                }
                embedMeta.add(embedMap);
            }
            metadata.put("discord.embeds", embedMeta);
        }

        // Collection
        if (sourceDescriptor.getCollectionName() != null) {
            metadata.put("collection_name", sourceDescriptor.getCollectionName());
        }
        if (sourceDescriptor.getSourceId() != null) {
            metadata.put(GraphConstants.META_SOURCE_ID, sourceDescriptor.getSourceId());
        }

        return doc;
    }

    private List<Channel> filterTargetChannels(List<Channel> allChannels, Map<String, Object> meta) {
        String channelIdsStr = str(meta.get("channelIds"));

        if (channelIdsStr != null && !channelIdsStr.isEmpty()) {
            Set<String> requested = new HashSet<>(Arrays.asList(channelIdsStr.split(",")));
            requested.removeIf(String::isEmpty);
            return allChannels.stream()
                    .filter(ch -> requested.contains(ch.id()) || requested.contains(ch.name()))
                    .toList();
        }

        // Default: all text-based channels
        return allChannels.stream()
                .filter(Channel::isTextBased)
                .toList();
    }

    /**
     * Compute a Discord snowflake ID that represents a point in time for "after" filtering.
     */
    private String computeAfterSnowflake(Map<String, Object> meta, int defaultDaysBack) {
        String startDate = str(meta.get("startDate"));
        Instant startInstant;
        if (startDate != null && !startDate.isEmpty()) {
            startInstant = parseDate(startDate);
        } else {
            startInstant = Instant.now().minus(Duration.ofDays(defaultDaysBack));
        }
        if (startInstant == null) return null;
        long snowflake = (startInstant.toEpochMilli() - DiscordModels.DISCORD_EPOCH) << 22;
        return Long.toUnsignedString(snowflake);
    }

    private String computeBeforeSnowflake(Map<String, Object> meta) {
        String endDate = str(meta.get("endDate"));
        if (endDate == null || endDate.isEmpty()) return null;
        Instant endInstant = parseDate(endDate);
        if (endInstant == null) return null;
        long snowflake = (endInstant.toEpochMilli() - DiscordModels.DISCORD_EPOCH) << 22;
        return Long.toUnsignedString(snowflake);
    }

    private Instant parseDate(String dateStr) {
        try {
            return OffsetDateTime.parse(dateStr).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(dateStr);
            } catch (DateTimeParseException e2) {
                try {
                    // Try plain date (yyyy-MM-dd)
                    return java.time.LocalDate.parse(dateStr)
                            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException e3) {
                    log.warn("Unable to parse date '{}', ignoring", dateStr);
                    return null;
                }
            }
        }
    }

    private void notifyProgress(Consumer<LoaderProgress> callback, String phase, int pct, String step) {
        if (callback != null) {
            callback.accept(new LoaderProgress(phase, pct, step, null, null));
        }
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    private static boolean boolVal(Object obj, boolean defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Boolean b) return b;
        return Boolean.parseBoolean(obj.toString());
    }

    private static int intVal(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
