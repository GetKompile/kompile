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

import ai.kompile.core.crawler.*;
import ai.kompile.utils.MapUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import ai.kompile.loader.discord.DiscordModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Crawler that traverses a Discord server, emitting {@link CrawlItem}s for:
 * <ul>
 *   <li>Messages (with full metadata including author, reactions, embeds)</li>
 *   <li>Attachments (downloaded to temp files for pipeline routing)</li>
 *   <li>Thread messages</li>
 * </ul>
 *
 * <p>Supports incremental crawling via channel-level "lastMessageId" tracking.
 *
 * <p>CrawlConfig properties:
 * <ul>
 *   <li>{@code botToken} - Discord bot token (required)</li>
 *   <li>{@code guildId} - Guild ID (or use {@code seed} / pathOrUrl)</li>
 *   <li>{@code channelIds} - Comma-separated channel IDs (empty = all text channels)</li>
 *   <li>{@code includeThreads} - Crawl threads (default true)</li>
 *   <li>{@code includeAttachments} - Download and emit attachments (default true)</li>
 *   <li>{@code includeArchivedThreads} - Include archived threads (default true)</li>
 *   <li>{@code rateLimitDelayMs} - Milliseconds between API requests (default 500)</li>
 * </ul>
 */
@Slf4j
@Component
public class DiscordCrawler extends AbstractCrawler {

    @Override
    public String getId() {
        return "discord";
    }

    @Override
    public String getName() {
        return "Discord Server Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls a Discord server, extracting messages, attachments, threads, and member information";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.DISCORD, SourceType.DISCORD_HISTORY);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        String token = str(props.get("botToken"));
        if (token == null || token.isEmpty()) {
            errors.add("Discord bot token is required (property: botToken)");
        }

        String guildId = resolveGuildId(config);
        if (guildId == null || guildId.isEmpty()) {
            errors.add("Discord guild ID is required (property: guildId, or use seed/sourceType pathOrUrl)");
        }

        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new DiscordCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        DiscordCrawlJob job = (DiscordCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        String botToken = str(props.get("botToken"));
        String guildId = resolveGuildId(config);
        boolean includeThreads = boolVal(props.get("includeThreads"), true);
        boolean includeAttachments = boolVal(props.get("includeAttachments"), true);
        boolean includeArchivedThreads = boolVal(props.get("includeArchivedThreads"), true);
        int maxMessages = config.getMaxDocuments();
        Duration rateLimitDelay = Duration.ofMillis(MapUtils.toInt(props.get("rateLimitDelayMs"), 500));

        DiscordApiService api = new DiscordApiService(botToken, rateLimitDelay);

        // Fetch guild info
        Guild guild = api.getGuild(guildId);
        log.info("Crawling Discord server: {} ({})", guild.name(), guild.id());

        // Fetch channels
        List<Channel> allChannels = api.getGuildChannels(guildId);
        List<Channel> textChannels = filterTargetChannels(allChannels, props);
        log.info("Found {} text channels to crawl", textChannels.size());

        // Collect threads
        List<Channel> threads = new ArrayList<>();
        if (includeThreads) {
            threads.addAll(api.getActiveThreads(guildId));
            if (includeArchivedThreads) {
                for (Channel ch : textChannels) {
                    if (job.shouldStop()) break;
                    threads.addAll(api.getArchivedPublicThreads(ch.id()));
                    threads.addAll(api.getArchivedPrivateThreads(ch.id()));
                }
            }
            log.info("Found {} threads", threads.size());
        }

        // Fetch guild roles once for role name resolution
        Map<String, DiscordModels.Role> roleMap = new HashMap<>();
        try {
            List<DiscordModels.Role> roles = api.getGuildRoles(guildId);
            for (DiscordModels.Role role : roles) {
                roleMap.put(role.id(), role);
            }
            log.info("Fetched {} guild roles", roleMap.size());
        } catch (Exception e) {
            log.debug("Could not fetch guild roles: {}", e.getMessage());
        }

        // Fetch guild members once for role assignment graphing
        Map<String, DiscordModels.Member> memberMap = new HashMap<>();
        try {
            List<DiscordModels.Member> members = api.getGuildMembers(guildId, 1000);
            for (DiscordModels.Member member : members) {
                if (member.user() != null) memberMap.put(member.user().id(), member);
            }
            log.info("Fetched {} guild members", memberMap.size());
        } catch (Exception e) {
            log.debug("Could not fetch guild members: {}", e.getMessage());
        }

        int totalEmitted = 0;

        // Crawl each channel
        for (Channel channel : textChannels) {
            if (job.shouldStop()) break;
            if (!job.checkPauseAndContinue()) break;

            job.setCurrentItem("#" + channel.name());
            job.setCurrentDepth(0);

            // For incremental crawls, use the last seen message ID as "after"
            String afterId = job.getIncrementalAfterForChannel(channel.id());

            List<Message> messages;
            try {
                messages = api.getChannelMessages(channel.id(), maxMessages, afterId, null);
            } catch (Exception e) {
                job.recordError("channel:" + channel.id(), e);
                log.warn("Failed to fetch messages from #{}: {}", channel.name(), e.getMessage());
                continue;
            }

            log.info("Fetched {} messages from #{}", messages.size(), channel.name());

            // Track the newest message for incremental state
            if (!messages.isEmpty()) {
                job.recordNewestMessage(channel.id(), messages.get(0).id());
            }

            for (Message msg : messages) {
                if (job.shouldStop()) break;

                CrawlItem item = buildMessageCrawlItem(msg, channel, guild, config, roleMap, memberMap);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);
                totalEmitted++;

                // Emit attachments as separate crawl items
                if (includeAttachments && msg.attachments() != null) {
                    for (Attachment att : msg.attachments()) {
                        if (job.shouldStop()) break;
                        try {
                            CrawlItem attItem = buildAttachmentCrawlItem(api, att, msg, channel, guild, config);
                            job.incrementDiscovered();
                            job.getListener().onDocumentDiscovered(attItem);
                            job.incrementProcessed();
                            job.getListener().onDocumentProcessed(attItem);
                            totalEmitted++;
                        } catch (Exception e) {
                            job.recordError("attachment:" + att.filename(), e);
                            log.warn("Failed to download attachment {}: {}", att.filename(), e.getMessage());
                        }
                    }
                }
            }

            job.visitedChannels.add(channel.id());
        }

        // Crawl threads (depth=1)
        for (Channel thread : threads) {
            if (job.shouldStop()) break;
            if (!job.checkPauseAndContinue()) break;

            job.setCurrentItem("thread:" + thread.name());
            job.setCurrentDepth(1);

            String afterId = job.getIncrementalAfterForChannel(thread.id());

            List<Message> messages;
            try {
                messages = api.getChannelMessages(thread.id(), maxMessages, afterId, null);
            } catch (Exception e) {
                job.recordError("thread:" + thread.id(), e);
                log.warn("Failed to fetch messages from thread '{}': {}", thread.name(), e.getMessage());
                continue;
            }

            if (!messages.isEmpty()) {
                job.recordNewestMessage(thread.id(), messages.get(0).id());
            }

            for (Message msg : messages) {
                if (job.shouldStop()) break;

                CrawlItem item = buildMessageCrawlItem(msg, thread, guild, config, roleMap, memberMap);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);
                totalEmitted++;

                if (includeAttachments && msg.attachments() != null) {
                    for (Attachment att : msg.attachments()) {
                        if (job.shouldStop()) break;
                        try {
                            CrawlItem attItem = buildAttachmentCrawlItem(api, att, msg, thread, guild, config);
                            job.incrementDiscovered();
                            job.getListener().onDocumentDiscovered(attItem);
                            job.incrementProcessed();
                            job.getListener().onDocumentProcessed(attItem);
                            totalEmitted++;
                        } catch (Exception e) {
                            job.recordError("attachment:" + att.filename(), e);
                        }
                    }
                }
            }

            job.visitedChannels.add(thread.id());
        }

        log.info("Discord crawl complete: {} items emitted from server '{}'", totalEmitted, guild.name());
    }

    private CrawlItem buildMessageCrawlItem(Message msg, Channel channel, Guild guild, CrawlConfig config,
                                              Map<String, DiscordModels.Role> roleMap,
                                              Map<String, DiscordModels.Member> memberMap) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String discordSource = "discord://" + guild.id() + "/" + channel.id() + "/" + msg.id();
        metadata.put(GraphConstants.META_SOURCE, discordSource);
        metadata.put(GraphConstants.META_SOURCE_PATH, discordSource);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "DISCORD");
        metadata.put(GraphConstants.META_LOADER, "Discord Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "discord_message");
        metadata.put(GraphConstants.META_FILE_NAME, "Discord message " + msg.id());
        metadata.put(GraphConstants.META_SOURCE_ID, "discord:" + msg.id());
        metadata.put("discord.messageId", msg.id());
        metadata.put("discord.channelId", channel.id());
        metadata.put("discord.channelName", channel.name());
        metadata.put("discord.channelType", channel.typeName());
        metadata.put("discord.guildId", guild.id());
        metadata.put("discord.guildName", guild.name());
        if (guild.ownerId() != null) metadata.put("discord.guildOwnerId", guild.ownerId());
        if (guild.description() != null) metadata.put("discord.guildDescription", guild.description());
        if (guild.preferredLocale() != null) metadata.put("discord.guildLocale", guild.preferredLocale());
        if (guild.memberCount() > 0) metadata.put("discord.guildMemberCount", guild.memberCount());
        metadata.put("discord.timestamp", msg.timestamp());
        metadata.put("discord.messageType", msg.type());
        if (msg.editedTimestamp() != null) metadata.put("discord.editedTimestamp", msg.editedTimestamp());

        if (msg.author() != null) {
            metadata.put("discord.authorId", msg.author().id());
            metadata.put("discord.authorName", msg.author().displayName());
            if (msg.author().username() != null) {
                metadata.put("discord.authorUsername", msg.author().username());
            }
            metadata.put("discord.authorIsBot", msg.author().bot());
            if (msg.author().globalName() != null && !msg.author().globalName().isEmpty()) {
                metadata.put("discord.authorGlobalName", msg.author().globalName());
            }
            if (msg.author().discriminator() != null && !msg.author().discriminator().isEmpty()
                    && !"0".equals(msg.author().discriminator())) {
                metadata.put("discord.authorDiscriminator", msg.author().discriminator());
            }
            // Construct avatar CDN URL from hash
            if (msg.author().avatar() != null && !msg.author().avatar().isEmpty()) {
                String ext = msg.author().avatar().startsWith("a_") ? "gif" : "png";
                metadata.put("discord.authorAvatarUrl",
                        "https://cdn.discordapp.com/avatars/" + msg.author().id() + "/" + msg.author().avatar() + "." + ext);
            }

            // Enrich with guild member data (nickname, role IDs, join date)
            DiscordModels.Member member = memberMap.get(msg.author().id());
            if (member != null) {
                if (member.nick() != null && !member.nick().isEmpty())
                    metadata.put("discord.authorNick", member.nick());
                if (member.joinedAt() != null)
                    metadata.put("discord.authorJoinedAt", member.joinedAt());
                if (member.roles() != null && !member.roles().isEmpty()) {
                    // Resolve role IDs to names and forward both
                    List<Map<String, String>> memberRoles = new ArrayList<>();
                    for (String roleId : member.roles()) {
                        Map<String, String> roleEntry = new LinkedHashMap<>();
                        roleEntry.put("roleId", roleId);
                        DiscordModels.Role role = roleMap.get(roleId);
                        if (role != null && role.name() != null) roleEntry.put("roleName", role.name());
                        memberRoles.add(roleEntry);
                    }
                    metadata.put("discord.authorRoles", memberRoles);
                }
            }
        }
        if (channel.topic() != null && !channel.topic().isEmpty()) {
            metadata.put("discord.channelTopic", channel.topic());
        }
        if (channel.messageCount() != null) {
            metadata.put("discord.channelMessageCount", channel.messageCount());
        }
        // Forward parentChannelId for ALL channels (threads AND non-threads).
        // For threads, parentId is the parent text channel.
        // For non-thread channels, parentId is the category channel.
        if (channel.parentId() != null && !channel.parentId().isEmpty()) {
            metadata.put("discord.parentChannelId", channel.parentId());
        }
        if (channel.isThread()) {
            metadata.put("discord.isThread", true);
            if (channel.threadMetadata() != null) {
                DiscordModels.ThreadMetadata tm = channel.threadMetadata();
                metadata.put("discord.threadArchived", tm.archived());
                metadata.put("discord.threadAutoArchiveDuration", tm.autoArchiveDuration());
                if (tm.archiveTimestamp() != null)
                    metadata.put("discord.threadArchiveTimestamp", tm.archiveTimestamp());
                metadata.put("discord.threadLocked", tm.locked());
            }
        }
        if (msg.messageReference() != null && msg.messageReference().messageId() != null) {
            metadata.put("discord.replyToMessageId", msg.messageReference().messageId());
        }
        if (msg.reactions() != null && !msg.reactions().isEmpty()) {
            metadata.put("discord.reactionCount", msg.reactions().stream().mapToInt(Reaction::count).sum());
            // Forward individual reactions so the graph extractor can create DISCORD_REACTION entities
            List<String> reactionStrings = new ArrayList<>();
            List<Map<String, Object>> reactionDetails = new ArrayList<>();
            for (Reaction r : msg.reactions()) {
                String emojiName = r.emoji() != null ? r.emoji().display() : "?";
                reactionStrings.add(emojiName + ":" + r.count());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("display", emojiName);
                detail.put("count", r.count());
                if (r.emoji() != null) {
                    if (r.emoji().id() != null) detail.put("emojiId", r.emoji().id());
                    if (r.emoji().name() != null) detail.put("emojiName", r.emoji().name());
                    if (r.emoji().animated()) detail.put("animated", true);
                }
                reactionDetails.add(detail);
            }
            metadata.put("discord.reactions", reactionStrings);
            metadata.put("discord.reactionDetails", reactionDetails);
        }
        if (msg.attachments() != null) {
            metadata.put("discord.attachmentCount", msg.attachments().size());
            // Forward attachment details so the graph extractor can create DISCORD_ATTACHMENT entities
            if (!msg.attachments().isEmpty()) {
                List<Map<String, String>> attachmentList = new ArrayList<>();
                for (var att : msg.attachments()) {
                    Map<String, String> attMap = new LinkedHashMap<>();
                    attMap.put("id", att.id());
                    attMap.put("filename", att.filename());
                    if (att.contentType() != null) attMap.put("contentType", att.contentType());
                    attMap.put("size", String.valueOf(att.size()));
                    if (att.url() != null) attMap.put("url", att.url());
                    if (att.width() != null) attMap.put("width", String.valueOf(att.width()));
                    if (att.height() != null) attMap.put("height", String.valueOf(att.height()));
                    attachmentList.add(attMap);
                }
                metadata.put("discord.attachments", attachmentList);
            }
        }
        metadata.put("discord.pinned", msg.pinned());
        // Forward mention user IDs and names for graph extraction
        if (msg.mentions() != null && !msg.mentions().isEmpty()) {
            List<String> mentionIds = new ArrayList<>();
            List<String> mentionNames = new ArrayList<>();
            for (User u : msg.mentions()) {
                mentionIds.add(u.id());
                mentionNames.add(u.displayName());
            }
            metadata.put("discord.mentionUserIds", mentionIds);
            metadata.put("discord.mentionUserNames", mentionNames);
        }
        // Forward @everyone mention flag
        if (msg.mentionEveryone()) {
            metadata.put("discord.mentionEveryone", true);
        }
        // Forward mention role IDs with names/colors from role lookup
        if (msg.mentionRoles() != null && !msg.mentionRoles().isEmpty()) {
            metadata.put("discord.mentionRoleIds", msg.mentionRoles());
            if (roleMap != null && !roleMap.isEmpty()) {
                List<String> roleNames = new ArrayList<>();
                List<Integer> roleColors = new ArrayList<>();
                List<Boolean> roleHoist = new ArrayList<>();
                List<Integer> rolePositions = new ArrayList<>();
                List<Boolean> roleMentionable = new ArrayList<>();
                for (String roleId : msg.mentionRoles()) {
                    DiscordModels.Role role = roleMap.get(roleId);
                    roleNames.add(role != null ? role.name() : null);
                    roleColors.add(role != null ? role.color() : 0);
                    roleHoist.add(role != null && role.hoist());
                    rolePositions.add(role != null ? role.position() : 0);
                    roleMentionable.add(role != null && role.mentionable());
                }
                metadata.put("discord.mentionRoleNames", roleNames);
                metadata.put("discord.mentionRoleColors", roleColors);
                metadata.put("discord.mentionRoleHoist", roleHoist);
                metadata.put("discord.mentionRolePositions", rolePositions);
                metadata.put("discord.mentionRoleMentionable", roleMentionable);
            }
        }
        // Forward embeds with full structural metadata for graph extraction
        if (msg.embeds() != null && !msg.embeds().isEmpty()) {
            List<Map<String, Object>> embedList = new ArrayList<>();
            int embedIdx = 0;
            for (var embed : msg.embeds()) {
                Map<String, Object> embedMap = new LinkedHashMap<>();
                embedMap.put("index", String.valueOf(embedIdx++));
                if (embed.type() != null) embedMap.put("type", embed.type());
                if (embed.title() != null) embedMap.put("title", embed.title());
                if (embed.description() != null) embedMap.put("description", embed.description());
                if (embed.url() != null) embedMap.put("url", embed.url());
                if (embed.author() != null) {
                    if (embed.author().name() != null) embedMap.put("authorName", embed.author().name());
                    if (embed.author().url() != null) embedMap.put("authorUrl", embed.author().url());
                }
                if (embed.footer() != null && embed.footer().text() != null) {
                    embedMap.put("footerText", embed.footer().text());
                }
                if (embed.image() != null && embed.image().url() != null) {
                    embedMap.put("imageUrl", embed.image().url());
                }
                if (embed.thumbnail() != null && embed.thumbnail().url() != null) {
                    embedMap.put("thumbnailUrl", embed.thumbnail().url());
                }
                if (embed.video() != null && embed.video().url() != null) {
                    embedMap.put("videoUrl", embed.video().url());
                    if (embed.video().width() > 0) embedMap.put("videoWidth", String.valueOf(embed.video().width()));
                    if (embed.video().height() > 0) embedMap.put("videoHeight", String.valueOf(embed.video().height()));
                }
                if (embed.provider() != null) {
                    if (embed.provider().name() != null) embedMap.put("providerName", embed.provider().name());
                    if (embed.provider().url() != null) embedMap.put("providerUrl", embed.provider().url());
                }
                if (embed.fields() != null && !embed.fields().isEmpty()) {
                    embedMap.put("fieldCount", String.valueOf(embed.fields().size()));
                    List<Map<String, String>> fieldsList = new ArrayList<>();
                    for (var field : embed.fields()) {
                        Map<String, String> fieldMap = new LinkedHashMap<>();
                        if (field.name() != null) fieldMap.put("name", field.name());
                        if (field.value() != null) fieldMap.put("value", field.value());
                        fieldsList.add(fieldMap);
                    }
                    embedMap.put("fields", fieldsList);
                }
                embedList.add(embedMap);
            }
            metadata.put("discord.embeds", embedList);
            metadata.put("discord.embedCount", embedList.size());
        }
        // Forward reply-to channel ID for cross-channel reply stubs
        if (msg.messageReference() != null && msg.messageReference().channelId() != null) {
            metadata.put("discord.replyToChannelId", msg.messageReference().channelId());
        }

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(config.getSourceType() != null ? config.getSourceType() : SourceType.DISCORD)
                .pathOrUrl("discord://guild/" + guild.id() + "/channel/" + channel.id() + "/message/" + msg.id())
                .sourceId("discord:" + msg.id())
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .build();

        return CrawlItem.builder()
                .url(descriptor.getPathOrUrl())
                .depth(channel.isThread() ? 1 : 0)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType("text/plain")
                .discoveredAt(Instant.now())
                .build();
    }

    private CrawlItem buildAttachmentCrawlItem(DiscordApiService api, Attachment att,
                                                Message parentMsg, Channel channel,
                                                Guild guild, CrawlConfig config) throws Exception {
        // Download attachment to temp file
        Path tempFile = api.downloadAttachment(att);

        Map<String, Object> metadata = new LinkedHashMap<>();
        String attSource = "discord-att://" + guild.id() + "/" + channel.id() + "/" + parentMsg.id() + "/" + att.id();
        metadata.put(GraphConstants.META_SOURCE, attSource);
        metadata.put(GraphConstants.META_SOURCE_PATH, attSource);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "DISCORD");
        metadata.put(GraphConstants.META_LOADER, "Discord Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "discord_attachment");
        metadata.put(GraphConstants.META_SOURCE_ID, "discord-att:" + att.id());
        metadata.put(GraphConstants.META_FILE_NAME, att.filename());
        metadata.put("discord.attachmentId", att.id());
        metadata.put("discord.attachmentFilename", att.filename());
        metadata.put("discord.attachmentContentType", att.contentType());
        metadata.put("discord.attachmentSize", att.size());
        metadata.put("discord.attachmentUrl", att.url());
        metadata.put("discord.parentMessageId", parentMsg.id());
        metadata.put("discord.channelId", channel.id());
        metadata.put("discord.channelName", channel.name());
        metadata.put("discord.parentChannelName", channel.name());
        metadata.put("discord.guildId", guild.id());
        metadata.put("discord.guildName", guild.name());
        if (guild.ownerId() != null) metadata.put("discord.guildOwnerId", guild.ownerId());
        if (guild.description() != null) metadata.put("discord.guildDescription", guild.description());
        if (guild.memberCount() > 0) metadata.put("discord.guildMemberCount", guild.memberCount());
        if (parentMsg.author() != null) {
            metadata.put("discord.parentAuthorName", parentMsg.author().displayName());
            metadata.put("discord.parentAuthorId", parentMsg.author().id());
        }
        if (parentMsg.timestamp() != null) {
            metadata.put("discord.parentTimestamp", parentMsg.timestamp());
        }

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(tempFile.toString())
                .originalFileName(att.filename())
                .sourceId("discord-att:" + att.id())
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .sizeBytes(att.size())
                .build();

        return CrawlItem.builder()
                .url(att.url())
                .parentUrl("discord://guild/" + guild.id() + "/channel/" + channel.id()
                        + "/message/" + parentMsg.id())
                .depth(channel.isThread() ? 2 : 1)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType(att.contentType() != null ? att.contentType() : "application/octet-stream")
                .contentLength(att.size())
                .discoveredAt(Instant.now())
                .build();
    }

    private List<Channel> filterTargetChannels(List<Channel> allChannels, Map<String, Object> props) {
        String channelIdsStr = str(props.get("channelIds"));

        if (channelIdsStr != null && !channelIdsStr.isEmpty()) {
            Set<String> requested = new HashSet<>(Arrays.asList(channelIdsStr.split(",")));
            requested.removeIf(String::isEmpty);
            return allChannels.stream()
                    .filter(ch -> requested.contains(ch.id()) || requested.contains(ch.name()))
                    .toList();
        }

        return allChannels.stream()
                .filter(Channel::isTextBased)
                .toList();
    }

    private String resolveGuildId(CrawlConfig config) {
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();
        String guildId = str(props.get("guildId"));
        if (guildId != null && !guildId.isEmpty()) return guildId;
        if (config.getSeed() != null && !config.getSeed().isEmpty()) return config.getSeed();
        return null;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    private static boolean boolVal(Object obj, boolean defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Boolean b) return b;
        return Boolean.parseBoolean(obj.toString());
    }

}
